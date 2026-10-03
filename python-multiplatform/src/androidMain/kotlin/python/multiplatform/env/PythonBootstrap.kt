package python.multiplatform.env

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import python.multiplatform.Versions
import python.multiplatform.ffi.Python3
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Brings CPython up on Android in one call.
 *
 * Android is the only platform where `Python3.initialize()` is not the whole story. The library's
 * AAR ships the interpreter under `jni/<abi>/`, which the linker loads out of the APK, and the
 * standard library as `assets/<abi>/lib/python<X.Y>/`, which `Py_Initialize()` **cannot** read: it
 * opens stdlib modules with ordinary `open(2)` against a filesystem path, and an APK's asset
 * archive is not one. So the tree has to be unpacked to app-private storage once, and `PYTHONHOME`
 * has to name the *prefix* -- the directory holding `lib/python<X.Y>`, not that directory itself.
 *
 * ```kotlin
 * class MainActivity : ComponentActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         PythonBootstrap.initialize(this)
 *         // CPython is up; the object model is usable from here.
 *     }
 * }
 * ```
 *
 * This existed as a documented recipe before it existed as code, and the recipe was copied three
 * times -- into `sample`'s `MainActivity`, into the external consumer app that verified the
 * published AAR (ROADMAP §15d), and into this library's own `PythonOnDevice` test fixture. All
 * three agreed on the copying and **disagreed on when to skip it**, which is the part that is
 * actually hard: see [stageStdlib].
 *
 * ### Relationship to [PythonHomeCheck]
 *
 * There is deliberately no `PYTHONHOME` validation here. [Python3.initialize] runs
 * [PythonHomeCheck] on every call, and a second implementation of the same rule is a second thing
 * that can drift from the layout `Py_Initialize()` actually wants. This class *produces* the
 * layout; that check *verifies* it, on the value this class set. `PythonBootstrapTest` runs one
 * against the other rather than asserting each separately.
 */
object PythonBootstrap {

    /**
     * Stages the standard library if needed, points `PYTHONHOME` at [prefix], and starts the
     * interpreter.
     *
     * Idempotent and safe to call from every entry point an app has -- a second call re-checks the
     * stamp (two `stat` calls and a short file read) and returns without touching the interpreter.
     *
     * @param prefix where the stdlib tree lives, and the value `PYTHONHOME` is given. Defaults to
     *   `filesDir`, which is app-private, survives upgrades, and is not subject to the scoped
     *   storage rules that make external directories unreadable to `open(2)` without a content
     *   resolver.
     * @param silent suppresses [Python3.initialize]'s success line on stdout.
     * @return what staging did, for a host app that wants to log or display it.
     */
    @JvmOverloads
    fun initialize(
        context: Context,
        prefix: File = context.filesDir,
        silent: Boolean = false,
    ): Staging {
        val staging = stageStdlib(context, prefix)
        // CPython reads PYTHONHOME through getenv(3), from the process environment -- not from a
        // JVM system property, which it has no way to see. On Android the single-argument
        // System.getenv delegates to this same native environment (unlike the JVM's cached no-arg
        // map), which is why PythonHomeCheck can read back what is set here.
        Os.setenv(PYTHONHOME, staging.prefix.absolutePath, true)
        // `tempfile` asks `os.environ` for TMPDIR/TEMP/TMP, then falls back to `/tmp`, `/var/tmp`,
        // `/usr/tmp` and finally `os.getcwd()`. None of those hardcoded paths exist inside an app
        // sandbox and the cwd of an Android process is `/`, so with nothing set every candidate is
        // refused and `tempfile.mkstemp()` raises `[Errno 2] No usable temporary directory found`.
        // That is not a test-only inconvenience: `tempfile` is what the standard library itself
        // reaches for, so an embedder's Python code hits it the first time it needs a scratch file.
        //
        // The cache directory rather than filesDir, because that is the one Android is allowed to
        // reclaim under storage pressure, which is what a temporary directory is for. Set through
        // the same `Os.setenv` PYTHONHOME uses and for the same reason: CPython reads it with
        // getenv(3) and cannot see a JVM system property. Not overwritten if the host app already
        // set one -- an embedder that pointed TMPDIR somewhere deliberately keeps it.
        if (Os.getenv(TMPDIR).isNullOrEmpty()) {
            val cache = context.cacheDir.also { it.mkdirs() }
            Os.setenv(TMPDIR, cache.absolutePath, true)
        }
        // Unpacked and *registered* here, put on sys.path by Python3.initialize below. The split
        // is not a preference: reading assets needs this Context and sys.path does not exist until
        // Py_Initialize() has run, so the two halves cannot happen at the same moment. See
        // PythonPayload.
        payloadStaging = stagePayload(context, prefix)
        Python3.initialize(silent = silent)
        return staging
    }

    /**
     * What the last [stagePayload] did, for a host app that wants to log it. Null until
     * [initialize] or [stagePayload] has run, and after either of them when the APK carries no
     * `assets/python/`.
     */
    @Volatile
    var payloadStaging: PayloadStaging? = null
        private set

    /**
     * Unpacks `assets/python/` into `<prefix>/python/` and registers it as a `sys.path` entry for
     * [Python3.initialize] to install, unless a stamp says the same build already did.
     *
     * This is the *consumer's own Python code* -- `pypackpack`'s `ResourceBundler` payload, staged
     * into the APK by `toolchain`'s `stagePythonBundleAndroid`. It is a different tree from the one
     * [stageStdlib] unpacks and is deliberately kept separate rather than merged into it:
     *
     * - **The stdlib is ABI-scoped and this is not.** `assets/<abi>/lib/python<X.Y>` exists once
     *   per ABI because it carries `lib-dynload`'s native extension modules. A resource payload is
     *   pure data and source, identical on every ABI, and giving it an ABI directory would ship it
     *   as many times as the APK has ABIs.
     * - **They change on different schedules.** The stdlib changes when this library's version
     *   does; the payload changes on every edit to the app's own Python. One stamp covering both
     *   would re-unpack 800-odd stdlib files because one application module was edited.
     *
     * Same discipline as [stageStdlib] otherwise, and for the same reason recorded there: the stamp
     * is removed before the rewrite and written after the last byte, so an interrupted unpack
     * cannot be mistaken for a finished one.
     *
     * @return null when the APK has no `assets/python/`, which is the normal state of an app that
     *   has not configured a Python package.
     */
    @JvmOverloads
    @Synchronized
    fun stagePayload(context: Context, prefix: File = context.filesDir): PayloadStaging? {
        val started = System.nanoTime()
        val root = PythonPayload.PAYLOAD_ROOT
        if (context.assets.list(root).isNullOrEmpty()) return null

        val payloadDir = File(prefix, root)
        val stamp = expectedStamp(context, PAYLOAD_ABI_PLACEHOLDER)
        val stampFile = payloadStampFile(prefix)

        if (stampFile.takeIf { it.isFile }?.readText() == stamp) {
            AndroidPayloadRoots.register(payloadDir)
            return PayloadStaging(payloadDir, unpacked = false, fileCount = 0, bytes = 0L,
                elapsedMillis = elapsedMillis(started))
        }

        stampFile.delete()
        payloadDir.deleteRecursively()
        if (!payloadDir.mkdirs() && !payloadDir.isDirectory) {
            throw IOException("could not create $payloadDir to unpack the Python payload into")
        }

        val counter = Counter()
        copyAssetTree(context.assets, root, payloadDir, ByteArray(COPY_BUFFER_BYTES), counter)
        stampFile.writeText(stamp)

        AndroidPayloadRoots.register(payloadDir)
        return PayloadStaging(payloadDir, unpacked = true, fileCount = counter.files,
            bytes = counter.bytes, elapsedMillis = elapsedMillis(started))
    }

    /** The stamp [stagePayload] writes last. Public so a host app can implement "re-unpack next launch". */
    fun payloadStampFile(prefix: File): File = File(prefix, PAYLOAD_STAMP_NAME)

    /** What [stagePayload] did. */
    data class PayloadStaging(
        /** `<prefix>/python`, the directory that goes on `sys.path`. */
        val payloadDir: File,
        /** False when a matching stamp was found and nothing was written. */
        val unpacked: Boolean,
        /** Files written, zero when [unpacked] is false. */
        val fileCount: Int,
        /** Bytes written, zero when [unpacked] is false. */
        val bytes: Long,
        /** Wall time for this call, including the stamp check that decided to skip. */
        val elapsedMillis: Long,
    )

    /**
     * Unpacks `assets/<abi>/lib/python<X.Y>/` into `<prefix>/lib/python<X.Y>/`, unless a stamp
     * says the same build already did.
     *
     * ### Why a stamp rather than a probe
     *
     * The three hand-written copies this replaces each decided "already unpacked" by probing one
     * entry of the result -- `target.list()` non-empty in one, `encodings/` being a directory in
     * the other two. Both are true of a tree whose copy was interrupted: the app was killed, the
     * device ran out of space, the user swiped it away during a cold start. The next launch then
     * accepts a partial stdlib, and the failure surfaces as an `ImportError` for whichever module
     * happened to sort after the interruption -- nowhere near the cause.
     *
     * The stamp is written **after** the last byte of the last file, so it cannot be true of a
     * partial tree, and it names the build rather than just the Python version: an APK upgrade
     * restages assets while `filesDir` survives the install, so "same Python version" is not
     * enough to conclude "same files". Its content is
     * `python-multiplatform <version> <abi> <versionCode> <lastUpdateTime>`.
     *
     * No checksum: hashing the 804 files to decide whether to write the 804 files costs a large
     * fraction of just writing them, and the thing it would protect against -- someone editing the
     * staged tree in app-private storage -- is not a failure mode of this library.
     *
     * @return a [Staging] whose [Staging.unpacked] is false when the stamp matched and nothing was
     *   written.
     */
    @JvmOverloads
    @Synchronized
    fun stageStdlib(context: Context, prefix: File = context.filesDir): Staging {
        val started = System.nanoTime()
        val relative = stdlibRelativePath()
        val stdlibDir = File(prefix, relative)
        val abi = assetAbiDirectory(context.assets)
        val stamp = expectedStamp(context, abi)

        if (stampFile(prefix).takeIf { it.isFile }?.readText() == stamp) {
            return Staging(prefix, stdlibDir, abi, unpacked = false, fileCount = 0, bytes = 0L,
                elapsedMillis = elapsedMillis(started))
        }

        // Order matters: the stamp goes first and comes back last. A stamp left in place while
        // the tree is being rewritten is exactly the "accepted a partial tree" failure this whole
        // mechanism exists to prevent -- it would be a *valid* stamp describing files that are
        // currently half-deleted.
        stampFile(prefix).delete()
        // Whatever is there is either from another build or unfinished; neither can be merged
        // into safely, because a file that used to exist and no longer does would survive.
        stdlibDir.deleteRecursively()
        if (!stdlibDir.mkdirs() && !stdlibDir.isDirectory) {
            throw IOException("could not create $stdlibDir to unpack the CPython standard library into")
        }

        val counter = Counter()
        copyAssetTree(context.assets, "$abi/$relative", stdlibDir, ByteArray(COPY_BUFFER_BYTES), counter)
        if (counter.files == 0) {
            throw IOException(
                "assets/$abi/$relative is empty, so no CPython standard library was staged. The " +
                    "library module's copyAndroidPythonAssets task stages it under the ABI " +
                    "directory; check that the configured version (${Versions.currentVersion}) " +
                    "matches what was extracted into the AAR."
            )
        }
        stampFile(prefix).writeText(stamp)

        return Staging(prefix, stdlibDir, abi, unpacked = true, fileCount = counter.files,
            bytes = counter.bytes, elapsedMillis = elapsedMillis(started))
    }

    /**
     * `lib/python3.14` -- the layout `Py_Initialize()` looks for under `PYTHONHOME`, and the
     * layout `copyAndroidPythonAssets` stages under each ABI directory.
     *
     * Derived from [Versions] rather than written out, and [Versions.taggedVersionString] rather
     * than [Versions.compactVersionString]: the tagged form carries the `t` of a free-threaded
     * build. A literal here does not fail loudly on a version bump -- it stops matching the staged
     * assets, and the app dies in `onCreate`, which is how the sample once broke on the move to
     * 3.14.
     */
    fun stdlibRelativePath(): String = "lib/python${Versions.currentVersion.taggedVersionString}"

    /** The stamp [stageStdlib] writes last. Public so a host app can implement "re-unpack next launch". */
    fun stampFile(prefix: File): File = File(prefix, "$STAMP_NAME.${Versions.currentVersion.taggedVersionString}")

    /**
     * The asset directory whose stdlib matches this process's ABI.
     *
     * Chosen by intersecting `Build.SUPPORTED_ABIS` -- which is ordered most-preferred first, and
     * for a 64-bit process starts with the one the interpreter's own `.so` was loaded for -- with
     * what is actually present in the merged assets. Intersecting rather than assuming is what
     * turns "this AAR was repackaged without my ABI" into a message naming both lists instead of
     * an empty copy.
     */
    fun assetAbiDirectory(assets: AssetManager): String {
        val relative = stdlibRelativePath()
        val supported = android.os.Build.SUPPORTED_ABIS.orEmpty()
        supported.firstOrNull { !assets.list("$it/$relative").isNullOrEmpty() }?.let { return it }
        throw IOException(
            "no CPython standard library in assets for any ABI this device supports. " +
                "Build.SUPPORTED_ABIS=${supported.joinToString()}, looked for <abi>/$relative, " +
                "assets root contains ${assets.list("")?.joinToString().orEmpty()}"
        )
    }

    /** What [stageStdlib] did. */
    data class Staging(
        /** The directory `PYTHONHOME` names -- the parent of `lib/python<X.Y>`. */
        val prefix: File,
        /** `<prefix>/lib/python<X.Y>`, where the standard library itself is. */
        val stdlibDir: File,
        /** The asset ABI directory the tree came from, e.g. `arm64-v8a`. */
        val abi: String,
        /** False when a matching stamp was found and nothing was written. */
        val unpacked: Boolean,
        /** Files written, zero when [unpacked] is false. */
        val fileCount: Int,
        /** Bytes written, zero when [unpacked] is false. */
        val bytes: Long,
        /** Wall time for this call, including the stamp check that decided to skip. */
        val elapsedMillis: Long,
    )

    /**
     * Recursive copy, with two departures from the obvious version that are worth the words
     * because the obvious version is what all three hand-written copies used.
     *
     * **Directories are detected by failing to open them, not by listing them.** The obvious form
     * calls `assets.list(child)` on every entry to ask "is this a directory", which is a native
     * directory lookup per entry across 800-odd entries, *in addition to* the `open()` each file
     * needs anyway. Opening first and treating `FileNotFoundException` as "directory" folds the
     * classification into work that had to happen regardless, so files cost one native call
     * instead of two and only the directories pay for a thrown exception.
     * `PythonBootstrapTest.openBasedDirectoryDetectionAgreesWithListBased` asserts the two agree,
     * on device, on each API level -- `AssetManager` was reimplemented wholesale in API 28 and
     * this behaviour is not in its documented contract.
     *
     * **One 64 KB buffer for the whole tree.** `InputStream.copyTo` allocates a fresh 8 KB buffer
     * per call; at 804 calls that is 804 allocations and eight times as many read/write pairs on
     * the files that dominate the byte count (the `lib-dynload` extension modules, the `.pyc`
     * caches).
     *
     * Together they are worth roughly 2x, measured on both emulators by
     * `PythonBootstrapTest.optimisedCopyIsMeasuredAgainstTheHandWrittenOne`: 192 ms -> 124 ms on
     * API 26, 324 ms -> 150 ms on API 36, for the same 804 files.
     */
    private fun copyAssetTree(
        assets: AssetManager,
        from: String,
        to: File,
        buffer: ByteArray,
        counter: Counter,
    ) {
        val entries = assets.list(from) ?: return
        for (entry in entries) {
            val source = "$from/$entry"
            val destination = File(to, entry)

            val input = try {
                assets.open(source, AssetManager.ACCESS_STREAMING)
            } catch (_: FileNotFoundException) {
                // Not an error: AssetManager.open() only opens files, so this is how a directory
                // announces itself. A genuinely missing entry cannot occur here -- `entry` came
                // from list() one line above.
                destination.mkdirs()
                copyAssetTree(assets, source, destination, buffer, counter)
                continue
            }

            input.use { stream ->
                FileOutputStream(destination).use { output ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        counter.bytes += read
                    }
                }
            }
            counter.files++
            // The extension modules under lib-dynload are dlopen()ed from this tree by CPython's
            // own importer.
            if (entry.endsWith(".so")) destination.setExecutable(true)
        }
    }

    /**
     * Identifies the build whose assets were staged.
     *
     * `lastUpdateTime` is what makes this correct across an app upgrade: `filesDir` survives the
     * install, so a stale tree from the previous APK is still sitting there when the new one first
     * runs, and its Python version may well be identical. `versionCode` alone is not enough
     * either -- a debug build installed over itself by the IDE keeps the same code.
     */
    private fun expectedStamp(context: Context, abi: String): String {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        @Suppress("DEPRECATION")
        val code = info?.versionCode ?: -1
        val updated = info?.lastUpdateTime ?: -1L
        return "python-multiplatform ${Versions.currentVersion} $abi $code $updated"
    }

    private fun elapsedMillis(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private class Counter {
        var files = 0
        var bytes = 0L
    }

    private const val PYTHONHOME = "PYTHONHOME"
    private const val TMPDIR = "TMPDIR"
    private const val STAMP_NAME = ".python-multiplatform-stdlib"
    private const val PAYLOAD_STAMP_NAME = ".python-multiplatform-payload"

    /**
     * Stands in for the ABI in the payload's stamp. The payload is not ABI-scoped (see
     * [stagePayload]), but the stamp shares [expectedStamp] with the stdlib's so that the two
     * cannot drift on the parts that *do* matter -- the library version, the version code and
     * `lastUpdateTime`, which is what makes an upgrade restage.
     */
    private const val PAYLOAD_ABI_PLACEHOLDER = "payload"

    /**
     * 64 KB. Large enough that the `lib-dynload` extension modules -- the biggest files in the
     * tree -- cross in a handful of reads, small enough to stay off the large-object path.
     */
    private const val COPY_BUFFER_BYTES = 64 * 1024
}
