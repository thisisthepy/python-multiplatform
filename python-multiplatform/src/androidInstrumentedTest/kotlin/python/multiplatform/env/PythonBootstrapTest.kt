package python.multiplatform.env

import android.content.res.AssetManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import python.multiplatform.Versions
import python.multiplatform.ffi.Python3
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract [PythonBootstrap] has to hold up, written before it existed.
 *
 * Three copies of this bootstrap were hand-written before the helper: `sample`'s `MainActivity`,
 * the external consumer app used to verify §15d, and `PythonOnDevice` in this very source set.
 * They disagreed on the one thing that matters -- how to tell "already unpacked" from "half
 * unpacked" -- so the tests below fix that decision rather than the copying, which all three got
 * right.
 *
 * Everything here stages into a scratch prefix under `cacheDir`, never `filesDir`: the live
 * interpreter this instrumentation process is already running reads its stdlib out of `filesDir`,
 * and a test that re-unpacked over it would be pulling the floor out from under the other 340-odd
 * tests in the same process.
 */
class PythonBootstrapTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratches = mutableListOf<File>()

    private fun scratch(name: String): File =
        File(context.cacheDir, "pmp-bootstrap-test/$name").also {
            it.deleteRecursively()
            it.mkdirs()
            scratches += it
        }

    @AfterTest
    fun cleanUp() {
        // ~26 MB a piece. Leaving them behind fills the emulator's data partition across runs.
        scratches.forEach { it.deleteRecursively() }
    }

    /**
     * The reason the helper exists at all: what it stages has to be exactly what
     * [PythonHomeCheck] -- which `Python3.initialize` now runs unconditionally -- accepts. Two
     * components deriving the same layout from the same [Versions] is not proof they agree; this
     * runs one against the other.
     */
    @Test
    fun stagedPrefixIsOneThatPythonHomeCheckAccepts() {
        val prefix = scratch("accepted")
        val staging = PythonBootstrap.stageStdlib(context, prefix)

        assertTrue(staging.unpacked, "a freshly emptied prefix should have been unpacked into")
        assertEquals(prefix, staging.prefix)
        assertNull(
            PythonHomeCheck.diagnose(prefix.absolutePath),
            "PythonHomeCheck rejected the prefix PythonBootstrap just staged -- the two disagree " +
                "about the layout, which is exactly the failure the helper is meant to remove",
        )
        assertTrue(
            File(staging.stdlibDir, "encodings/__init__.py").isFile,
            "no encodings package under ${staging.stdlibDir} -- Py_Initialize aborts on this",
        )
        assertTrue(staging.fileCount > 500, "only ${staging.fileCount} files staged")
    }

    /**
     * The second call must not re-unpack. This is the whole point of the stamp: 26 MB of asset
     * decompression on every cold start is not something a host app should pay for silently.
     *
     * The assertion is on the reported flag, not on the timing -- an emulator's disk is not a
     * stable enough clock to fail a build on. The timings are logged for the record.
     */
    @Test
    fun secondStagingIsSkippedRatherThanRepeated() {
        val prefix = scratch("idempotent")

        val first = PythonBootstrap.stageStdlib(context, prefix)
        assertTrue(first.unpacked, "first call did not unpack")

        val second = PythonBootstrap.stageStdlib(context, prefix)
        assertFalse(second.unpacked, "second call unpacked again despite a matching stamp")
        assertEquals(0, second.fileCount, "a skipped staging should report no files written")
        assertEquals(first.stdlibDir, second.stdlibDir)

        Log.i(
            TAG,
            "PYBOOTSTRAP_COST api=${android.os.Build.VERSION.SDK_INT} " +
                "first=${first.elapsedMillis}ms files=${first.fileCount} bytes=${first.bytes} " +
                "subsequent=${second.elapsedMillis}ms",
        )
        assertTrue(
            second.elapsedMillis <= first.elapsedMillis,
            "the skipped path (${second.elapsedMillis}ms) cost more than the unpack " +
                "(${first.elapsedMillis}ms)",
        )
    }

    /**
     * A tree with no stamp is a tree that was never finished.
     *
     * All three hand-written copies decided "already unpacked" by probing one entry --
     * `target.list()` being non-empty, or `encodings/` being a directory. A process killed partway
     * through the copy leaves both of those true and the tree incomplete, and the next start-up
     * accepts it. The stamp is written last, so it cannot be true of a partial tree.
     */
    @Test
    fun aTreeWithoutAStampIsUnpackedAgain() {
        val prefix = scratch("unfinished")
        val staged = PythonBootstrap.stageStdlib(context, prefix)
        assertTrue(staged.unpacked)

        // What a copy interrupted after `encodings/` but before the end looks like to the old
        // checks: present, non-empty, no stamp.
        assertTrue(PythonBootstrap.stampFile(prefix).delete(), "no stamp was written to delete")
        assertTrue(File(staged.stdlibDir, "encodings").isDirectory)

        val redone = PythonBootstrap.stageStdlib(context, prefix)
        assertTrue(redone.unpacked, "an unstamped tree was accepted as complete")
    }

    /**
     * A stamp from a different build must not be honoured. The realistic trigger is not a Python
     * version bump -- that changes the directory name too and would be caught anyway -- but an APK
     * upgrade that restages the same Python version's assets over an app whose `filesDir` survived
     * the install.
     */
    @Test
    fun aStampFromAnotherBuildIsNotHonoured() {
        val prefix = scratch("stale")
        assertTrue(PythonBootstrap.stageStdlib(context, prefix).unpacked)

        PythonBootstrap.stampFile(prefix).writeText("python-multiplatform 0.0.0 someotherabi 1 1")

        assertTrue(
            PythonBootstrap.stageStdlib(context, prefix).unpacked,
            "a stamp naming a different build was accepted",
        )
    }

    /**
     * [PythonBootstrap] classifies an asset entry as a directory by *failing* to open it, which
     * saves one `AssetManager.list()` per file across ~1700 files. That is an undocumented
     * behaviour of `AssetManager.open()`, and AssetManager was reimplemented wholesale in API 28 --
     * so this asserts the two classifications agree on whatever API level is running, rather than
     * trusting that they do.
     */
    @Test
    fun openBasedDirectoryDetectionAgreesWithListBased() {
        val assets = context.assets
        val root = "${PythonBootstrap.assetAbiDirectory(assets)}/${PythonBootstrap.stdlibRelativePath()}"

        val byList = sortedSetOf<String>()
        val byOpen = sortedSetOf<String>()
        collect(assets, root, byList, byOpen)

        assertTrue(byList.isNotEmpty(), "no assets found under $root")
        assertEquals(
            byList, byOpen,
            "open()-based and list()-based file classification disagree on API " +
                "${android.os.Build.VERSION.SDK_INT}",
        )
    }

    private fun collect(
        assets: AssetManager,
        path: String,
        byList: MutableSet<String>,
        byOpen: MutableSet<String>,
    ) {
        val entries = assets.list(path) ?: return
        for (entry in entries) {
            val child = "$path/$entry"
            if (assets.list(child).isNullOrEmpty()) byList += child

            val opened = try {
                assets.open(child).close(); true
            } catch (_: FileNotFoundException) {
                false
            }
            if (opened) byOpen += child else collect(assets, child, byList, byOpen)
        }
    }

    /**
     * [PythonBootstrap] departs from the copy loop all three hand-written versions used in two
     * ways -- it classifies directories by a failed `open()` instead of a `list()`, and it reuses
     * one 64 KB buffer instead of letting `copyTo` allocate an 8 KB one per file. Both are claims
     * about cost, so they are measured here rather than asserted in a comment.
     *
     * Alternating order, two samples each, because whichever runs first pays for a cold page
     * cache. Nothing is asserted about the *margin* -- an emulator's disk is the host's SSD behind
     * qemu and is not a stable enough clock for that. What is asserted is that the two produce the
     * same tree, which is the part that would be a bug.
     */
    @Test
    fun optimisedCopyIsMeasuredAgainstTheHandWrittenOne() {
        val assets = context.assets
        val from = "${PythonBootstrap.assetAbiDirectory(assets)}/${PythonBootstrap.stdlibRelativePath()}"

        val naive = LongArray(2)
        val helper = LongArray(2)
        var naiveFiles = 0
        var helperFiles = 0

        for (round in 0..1) {
            val n = scratch("naive$round")
            naive[round] = measure { naiveFiles = copyTheOldWay(assets, from, File(n, "out")) }

            val h = scratch("helper$round")
            helper[round] = measure { helperFiles = PythonBootstrap.stageStdlib(context, h).fileCount }
        }

        Log.i(
            TAG,
            "PYBOOTSTRAP_COPY api=${android.os.Build.VERSION.SDK_INT} files=$helperFiles " +
                "handwritten=${naive.joinToString("/")}ms helper=${helper.joinToString("/")}ms",
        )
        assertEquals(naiveFiles, helperFiles, "the two copy loops wrote different file counts")
    }

    private inline fun measure(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000
    }

    /** What `sample`, the consumer app and `PythonOnDevice` each wrote, verbatim in shape. */
    private fun copyTheOldWay(assets: AssetManager, from: String, to: File): Int {
        var files = 0
        to.mkdirs()
        val entries = assets.list(from) ?: return 0
        for (entry in entries) {
            val src = "$from/$entry"
            val dst = File(to, entry)
            if (!assets.list(src).isNullOrEmpty()) {
                files += copyTheOldWay(assets, src, dst)
                continue
            }
            assets.open(src).use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            files++
        }
        return files
    }

    /**
     * The end-to-end shape a host app writes: one call, and CPython is up.
     *
     * `PYTHONHOME` is asserted through `System.getenv` rather than `Os.getenv` on purpose. Those
     * are the same thing on Android -- libcore's single-argument `System.getenv` delegates to the
     * live native environment, unlike the JVM's cached no-arg map -- and it is the one
     * [PythonHomeCheck] reads, so this is the assertion that the value the bootstrap sets is the
     * value the check will see.
     */
    @Test
    fun initializeBringsTheInterpreterUpAndAgreesWithTheCheckOnPythonHome() {
        PythonBootstrap.initialize(context, silent = true)

        assertTrue(Python3.isInitialized, "PythonBootstrap.initialize left Python down")
        assertEquals(
            context.filesDir.absolutePath, System.getenv("PYTHONHOME"),
            "PYTHONHOME is not the prefix PythonBootstrap staged into",
        )
        assertNull(PythonHomeCheck.diagnose(System.getenv("PYTHONHOME")!!))

        val globals = Python3.import("__main__").dict
        assertEquals("6", Python3.eval("sum([1, 2, 3])", 258, globals, globals).toString())
    }

    /**
     * `tempfile.mkstemp()` is not optional plumbing -- it is what the standard library itself
     * reaches for whenever embedded Python code needs a scratch file. `tempfile.gettempdir()`
     * (via `_candidate_tempdir_list()`) tries `TMPDIR`/`TEMP`/`TMP` from `os.environ` first, then
     * the hardcoded `/tmp`, `/var/tmp`, `/usr/tmp`, then `os.getcwd()` as a last resort. None of
     * the hardcoded paths exist inside an Android app's sandbox, and the instrumentation
     * process's cwd is `/`, not app-private storage -- so with nothing set, every candidate is
     * refused and `tempfile.mkstemp()` raises `FileNotFoundError: [Errno 2] No usable temporary
     * directory found in ['/tmp', '/var/tmp', '/usr/tmp', '/']`.
     */
    @Test
    fun initializeLeavesTempfileUsable() {
        PythonBootstrap.initialize(context, silent = true)

        Python3.exec(
            "import tempfile as _pmp_bootstrap_tempfile, os as _pmp_bootstrap_os\n" +
                "_pmp_bootstrap_fd, __pmp_bootstrap_tmp_path__ = _pmp_bootstrap_tempfile.mkstemp()\n" +
                "_pmp_bootstrap_os.close(_pmp_bootstrap_fd)\n"
        )
        val globals = Python3.import("__main__").dict
        val path = Python3.eval("__pmp_bootstrap_tmp_path__", 258, globals, globals).toString()
        try {
            assertTrue(File(path).isFile, "tempfile.mkstemp() reported '$path' but no such file exists")
        } finally {
            Python3.exec("import os; os.remove(__pmp_bootstrap_tmp_path__)")
        }
    }

    private companion object {
        const val TAG = "PythonBootstrapTest"
    }
}
