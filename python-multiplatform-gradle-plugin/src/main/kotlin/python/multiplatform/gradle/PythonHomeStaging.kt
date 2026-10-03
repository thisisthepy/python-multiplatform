package python.multiplatform.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Staging a CPython prefix for a consumer's build — ROADMAP §15e item 4.
 *
 * ### The gap this closes
 *
 * §15c published the library to `mavenLocal()` and built a genuine external JVM consumer against
 * it. Everything worked *except* start-up: `desktopJar` carries `libpython` for four host
 * platforms under `lib/<platform>/`, and `manager.loadLibPython` finds it there with no wiring at
 * all — but it carries **no standard library**, and `Py_Initialize()` dies with
 * `Fatal Python error: Failed to import encodings module` until `PYTHONHOME` names a prefix that
 * has one. Verified rather than read: the published jar's `lib/` tree is 14 entries, every one of
 * them a shared library.
 *
 * Every path that *produces* such a prefix (`downloadAllPythonBuilds` and its per-platform
 * `downloadPython_*` tasks) lives in `python-multiplatform/build.gradle.kts` and is reachable only
 * by building that repository from source. So a consumer who followed README to the letter had a
 * working interpreter binary, a working object model, working upcalls, and no way to obtain the
 * ~24 MB of Python source files the first `import` needs.
 *
 * ### Why this is a build-time task rather than a runtime helper
 *
 * Android solved the same problem at runtime (`PythonBootstrap`, §15f) because there was no
 * choice: the stdlib has to travel *inside* the APK, so something has to unpack it out of
 * `assets/` on the device. Neither half of that reasoning transfers to desktop, and one of them
 * inverts.
 *
 * - **The bytes do not have to ship in the artifact.** A desktop `PYTHONHOME` may name any path on
 *   the machine, so the prefix can be fetched once per machine and shared by every project on it,
 *   instead of being multiplied into an artifact every consumer downloads. That matters here more
 *   than usual: `desktopJar` is already 87.7 MB for four platforms' `libpython`, and the four
 *   platforms' stdlibs measure a further 43.5 MB compressed (macos-aarch64 8.1, macos-x86_64 7.1,
 *   linux-x86_64 8.2, windows-x86_64 20.1) — for payload of which any one consumer uses exactly
 *   one quarter. Putting per-platform payload into the artifact every platform resolves is the
 *   defect §15d diagnosed when `allMetadataJar` reached 87.4 MB, and it was fixed by *narrowing*
 *   scope, not by accepting the size.
 * - **`PYTHONHOME` cannot be set from inside a running JVM.** CPython reads it with `getenv(3)`,
 *   from the native process environment. Android's helper calls `Os.setenv`; the JVM has no
 *   equivalent — `System.getenv` is an immutable snapshot taken at start-up, and changing it (by
 *   reflection or otherwise) does not touch the native environment CPython actually reads. A
 *   runtime helper would therefore have to reach libc's `setenv` through Panama, which is a
 *   different symbol on Windows (`_putenv_s`), and would then be setting a value that
 *   [python.multiplatform.env.PythonHomeCheck] — which reads `System.getenv` — could no longer
 *   see. Setting it when the *process is launched*, which is what Gradle's `environment(...)`
 *   does, has neither problem: CPython and `PythonHomeCheck` read the same value from the same
 *   place, exactly as they already do for this repository's own `desktopTest`.
 *
 * ### A packaged application (SPEC L-9)
 *
 * Both points above are about the developer's machine. A packaged app has no Gradle to set its
 * environment, so the same staged prefix is also copied into Compose Desktop's application
 * resources ([packagedPrefixIncludes], `PythonBindingsPlugin.configurePackagedPythonHome`) and the
 * library hands it to CPython in-process with `Py_SetPythonHome` -- which is not an environment
 * variable, so the objection above does not apply to it. Only the host's platform is copied, into
 * the *application*, never into the library artifact. See `docs/platforms/desktop-packaged-app.md`.
 *
 * ### What it deliberately does not do
 *
 * It does not validate the prefix beyond [stdlibMarkerRelativePath], and that marker is the file
 * `PythonHomeCheck.diagnose` itself looks for. A second, independent notion of "usable prefix" is
 * a second thing that can drift from the layout `Py_Initialize()` wants; this produces the layout,
 * that verifies it, on the value this sets.
 */
internal object PythonHomeStaging {

    /** Where every project on this machine shares one staged prefix, under the Gradle user home. */
    internal const val CACHE_DIRECTORY = "python-multiplatform/python-home"

    /** Written after the last extracted byte; see [stagingStamp]. */
    internal const val STAMP_NAME = ".python-multiplatform-prefix"
}

/**
 * This library's name for the host, or null when the host is not one it ships for.
 *
 * The four returned names are not free-form: `manager.platformDirectory()` builds the same strings
 * to locate `lib/<platform>/libpython*` inside `desktopJar`, and `desktopTargets` in
 * `python-multiplatform/build.gradle.kts` keys the download tasks by them.
 *
 * `linux-aarch64` is included even though `desktopJar` carries no `libpython` for it, and that is
 * not an oversight: `manager.loadFromSidecar` falls back to loading the interpreter *out of
 * `PYTHONHOME`* whenever the classpath has no library for the running platform, so a staged prefix
 * is the whole of what that host needs. Staging is the only reason it can work at all — this is
 * not verified on such a host, see this file's own note in ROADMAP §15g.
 */
internal fun hostDesktopPlatform(osName: String, osArch: String): String? {
    val arch = when (osArch.lowercase()) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        else -> return null
    }
    val name = osName.lowercase()
    val os = when {
        name.startsWith("mac") || name.startsWith("darwin") -> "macos"
        name.startsWith("windows") -> "windows"
        name.startsWith("linux") -> "linux"
        else -> return null
    }
    // Upstream publishes no aarch64 Windows `install_only` build, and this library has never
    // shipped a `libpython` for one either.
    if (os == "windows" && arch == "aarch64") return null
    return "$os-$arch"
}

/** python-build-standalone's target triple for one of [hostDesktopPlatform]'s names. */
internal fun pbsTripleFor(platform: String): String = when (platform) {
    "macos-aarch64" -> "aarch64-apple-darwin"
    "macos-x86_64" -> "x86_64-apple-darwin"
    "linux-x86_64" -> "x86_64-unknown-linux-gnu"
    "linux-aarch64" -> "aarch64-unknown-linux-gnu"
    "windows-x86_64" -> "x86_64-pc-windows-msvc"
    else -> throw IllegalArgumentException("no python-build-standalone triple for platform '$platform'")
}

/**
 * The release asset holding a CPython prefix for [platform].
 *
 * Byte-for-byte the name `downloadPython_*` builds in `python-multiplatform/build.gradle.kts`. The
 * two must agree: a consumer that stages a *different* upstream build than the `libpython` in
 * `desktopJar` was taken from can pair a stdlib and an interpreter that disagree about ABI while
 * both calling themselves the same version.
 */
internal fun pbsAssetName(
    pythonVersion: String,
    pbsRelease: String,
    platform: String,
    freeThreaded: Boolean,
): String {
    val flavour = if (freeThreaded) "freethreaded-install_only" else "install_only"
    return "cpython-$pythonVersion+$pbsRelease-${pbsTripleFor(platform)}-$flavour.tar.gz"
}

internal fun pbsAssetUrl(pbsRelease: String, assetName: String): String =
    "https://github.com/astral-sh/python-build-standalone/releases/download/$pbsRelease/$assetName"

/**
 * The SHA-256 upstream records for [assetName], or null when the release does not list it.
 *
 * Matched on the whole filename rather than with `endsWith`, which the root build uses and which
 * is wrong in principle: `...-install_only.tar.gz` is a suffix of
 * `...-freethreaded-install_only.tar.gz`, so asking for the default asset could return the
 * free-threaded hash and fail verification on a file that is perfectly intact.
 */
internal fun sha256Of(sha256sums: String, assetName: String): String? =
    sha256sums.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            // Upstream's format is "<hex>  <filename>".
            val separator = trimmed.indexOf(' ')
            if (separator <= 0) return@mapNotNull null
            val name = trimmed.substring(separator).trim().removePrefix("*")
            if (name == assetName) trimmed.substring(0, separator) else null
        }
        .firstOrNull()

/**
 * A file that must be readable under a prefix for it to be usable, relative to the prefix.
 *
 * This is exactly what `PythonHomeCheck.diagnose` probes — `lib/python<tag>/os.py`, or `Lib/os.py`
 * on the layout CPython's Windows build produces — so a prefix this staging accepts is one
 * `Python3.initialize()` accepts. Asserting a named file rather than "the directory is not empty"
 * is §15f's lesson: the root build's own extraction step skips when `extractDir.list()` is
 * non-empty, which is true of a tree whose extraction was interrupted.
 */
internal fun stdlibMarkerRelativePath(pythonVersion: String, freeThreaded: Boolean, windows: Boolean): String {
    if (windows) return "Lib/os.py"
    val parts = pythonVersion.split('.')
    require(parts.size >= 2) { "python version '$pythonVersion' has no major.minor" }
    val tag = "${parts[0]}.${parts[1]}" + if (freeThreaded) "t" else ""
    return "lib/python$tag/os.py"
}

/**
 * Identifies precisely which distribution was extracted.
 *
 * Written after the last byte and deleted before a rewrite begins, so it cannot be true of a
 * partial tree — §15f found three hand-written Android copies that each probed a single entry of
 * the result and so accepted a tree whose copy had been interrupted.
 *
 * It names the upstream release as well as the Python version because astral republishes the same
 * CPython version under a new date tag, and both feed the same cache directory.
 */
internal fun stagingStamp(
    pythonVersion: String,
    pbsRelease: String,
    platform: String,
    freeThreaded: Boolean,
): String = "python-multiplatform $pythonVersion+$pbsRelease $platform " +
    (if (freeThreaded) "freethreaded" else "default")

/**
 * The directory a packaged desktop application carries its CPython prefix in, inside Compose
 * Desktop's application resources (`$APPDIR/resources/` in a `createDistributable` image) --
 * SPEC L-9, issue #60.
 *
 * `PackagedPythonHome.DIRECTORY_NAME` in the library's `jvmMain` is the run-time half and must be
 * the same string; the two builds share no code, so each side pins it in a test.
 */
internal const val PACKAGED_HOME_DIRECTORY = "python-multiplatform-home"

/**
 * What of a staged prefix a packaged application needs, as include patterns relative to the prefix.
 *
 * The standard library and the shared library `manager.loadLibPython` loads out of the same prefix.
 * Measured for 3.14.7 macos-aarch64 on this machine, `__pycache__` excluded: 1,195 files and
 * 39.6 MB (19.4 MB of it `libpython3.14.dylib`) out of the prefix's 68.9 MB. Left out:
 * `include/` and `lib/pkgconfig` (build-time only), `bin/` (an embedding app has no `python3`
 * executable to run), and Tcl/Tk (`libtcl*`, `tcl9.0/`, `tk9.0/`: `tkinter` is not something a
 * Compose app reaches, and they are a further 4 MB of libraries alone).
 *
 * The Windows layout differs and is matched as the library's own lookups match it: `Lib/` and
 * `DLLs/` (extension modules) under the prefix, and the interpreter plus the MSVC runtime as
 * `*.dll` at its root, which is where `manager.libraryUnder` looks on that layout.
 */
internal fun packagedPrefixIncludes(pythonVersion: String, freeThreaded: Boolean, windows: Boolean): List<String> {
    if (windows) return listOf("Lib/**", "DLLs/**", "*.dll")
    val parts = pythonVersion.split('.')
    require(parts.size >= 2) { "python version '$pythonVersion' has no major.minor" }
    val tag = "${parts[0]}.${parts[1]}" + if (freeThreaded) "t" else ""
    return listOf("lib/python$tag/**", "lib/libpython$tag.*")
}

/**
 * Never packaged: the staged prefix is shared by every project on the machine, and CPython writes
 * bytecode caches into it from every run that uses it, so its `__pycache__` directories reflect the
 * developer's history rather than the build.
 */
internal val PACKAGED_PREFIX_EXCLUDES: List<String> = listOf("**/__pycache__/**")

/**
 * Compose Desktop's `Sync` task that assembles an application's resources: `prepareAppResources`
 * for the default build type, `prepare<BuildType>AppResources` for the others (`Release`). Matched
 * by name, like the wasm wiring matches `wasmJsProcessResources`, so this plugin carries no
 * compile-time dependency on the Compose Gradle plugin; `Sync` itself is Gradle's own type.
 */
internal fun isComposeAppResourcesTask(name: String): Boolean =
    Regex("""prepare([A-Z]\w*)?AppResources""").matches(name)

/**
 * Whether the plugin should stage a prefix and set `PYTHONHOME`.
 *
 * A consumer who has already set `PYTHONHOME` — at a system CPython, at a conda prefix, at a build
 * they compiled themselves — keeps it. Replacing it would turn a working setup into a
 * differently-configured one on a library upgrade, and §15c's whole finding is that setting it by
 * hand is what consumers have had to do until now.
 */
internal fun shouldStagePythonHome(existingPythonHome: String?, stagingEnabled: Boolean): Boolean =
    stagingEnabled && existingPythonHome.isNullOrBlank()

/**
 * Downloads, verifies and extracts one python-build-standalone distribution into a shared
 * per-machine cache, then stamps it.
 *
 * Incremental in the way that matters: a matching stamp makes the whole task body two `stat` calls
 * and a short read, which is what keeps this off the critical path of every subsequent build.
 */
abstract class StagePythonHomeTask : DefaultTask() {

    @get:Input
    abstract val pythonVersion: Property<String>

    @get:Input
    abstract val pbsRelease: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val freeThreaded: Property<Boolean>

    /** The directory the archive is extracted into; the prefix itself is `python/` beneath it. */
    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    /** Where downloaded archives are kept, shared across every project on this machine. */
    @get:org.gradle.api.tasks.Internal
    abstract val downloadDir: DirectoryProperty

    @get:Inject
    protected abstract val fs: FileSystemOperations

    @get:Inject
    protected abstract val archives: ArchiveOperations

    @TaskAction
    fun stage() {
        val version = pythonVersion.get()
        val release = pbsRelease.get()
        val target = platform.get()
        val ft = freeThreaded.get()

        val root = destinationDir.get().asFile
        val stamp = File(root, PythonHomeStaging.STAMP_NAME)
        val expected = stagingStamp(version, release, target, ft)
        val prefix = File(root, "python")
        val marker = File(prefix, stdlibMarkerRelativePath(version, ft, windows = target.startsWith("windows")))

        // Both conditions, not either: the stamp proves the extraction finished, the marker proves
        // the tree is the shape `PythonHomeCheck` will demand of it. A stamp alone would survive
        // someone deleting the prefix out of the cache by hand.
        if (stamp.isFile && stamp.readText() == expected && marker.isFile) {
            didWork = false
            logger.info("CPython {} prefix already staged at {}", version, prefix)
            return
        }

        val assetName = pbsAssetName(version, release, target, ft)
        val archive = File(downloadDir.get().asFile, assetName)
        download(assetName, release, archive)

        // Order matters, and it is the same order `PythonBootstrap` uses on Android: the stamp
        // goes first and comes back last, so a stamp is never valid for a tree that is currently
        // being replaced.
        stamp.delete()
        fs.delete { delete(root) }
        if (!root.mkdirs() && !root.isDirectory) {
            throw GradleException("could not create $root to stage the CPython prefix into")
        }

        logger.lifecycle("Staging CPython $version ($target) into $prefix")
        fs.copy {
            from(archives.tarTree(archives.gzip(archive)))
            into(root)
        }

        if (!marker.isFile) {
            throw GradleException(
                "staged $assetName into $root but ${marker.absolutePath} is missing, so PYTHONHOME " +
                    "would not be usable. The archive layout may have changed upstream; this task " +
                    "expects a single top-level 'python/' directory holding the prefix."
            )
        }
        stamp.writeText(expected)
    }

    private fun download(assetName: String, release: String, archive: File) {
        if (archive.isFile) {
            // Re-verified below rather than trusted: a half-written archive from an interrupted
            // build is exactly the case a plain existence check accepts.
            if (verify(assetName, release, archive, throwOnMismatch = false)) return
            logger.lifecycle("Cached $assetName failed checksum verification; downloading again")
            archive.delete()
        }
        archive.parentFile.mkdirs()
        val url = pbsAssetUrl(release, assetName)
        logger.lifecycle("Downloading $url")
        val partial = File(archive.parentFile, "${archive.name}.part")
        partial.delete()
        URI(url).toURL().openStream().use { input ->
            partial.outputStream().use { output -> input.copyTo(output) }
        }
        // Renamed only once it is complete, so a build interrupted mid-download leaves a `.part`
        // behind rather than a plausible-looking archive.
        if (!partial.renameTo(archive)) {
            throw GradleException("could not move $partial into place at $archive")
        }
        verify(assetName, release, archive, throwOnMismatch = true)
    }

    private fun verify(assetName: String, release: String, archive: File, throwOnMismatch: Boolean): Boolean {
        val sumsFile = File(archive.parentFile, "SHA256SUMS-$release")
        if (!sumsFile.isFile) {
            val url = "https://github.com/astral-sh/python-build-standalone/releases/download/$release/SHA256SUMS"
            URI(url).toURL().openStream().use { input ->
                sumsFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val expected = sha256Of(sumsFile.readText(), assetName)
            ?: throw GradleException(
                "python-build-standalone release $release does not list $assetName in its " +
                    "SHA256SUMS. Check that pythonVersion and pythonBuildStandaloneRelease name a " +
                    "combination that upstream actually published."
            )

        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual == expected) return true
        if (throwOnMismatch) {
            archive.delete()
            throw GradleException("checksum mismatch for $assetName: expected $expected, got $actual")
        }
        return false
    }
}
