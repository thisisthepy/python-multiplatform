package fixture.app

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Issue #98: once the JVM collected the wrappers a test run had left behind, the cleaner released a
 * "reference" that was never one, and the JVM died with SIGSEGV in `Py_XDECREF` on `Cleaner-0`.
 *
 * ### What it was
 *
 * Not the upcall path. `GeneratedTableTest`'s traverse proof wrapped two made-up addresses,
 * `PyObject(0xAAAA, borrowed = false)` and `0xBBBB`, and every `PyObject` registers a cleaner that
 * calls `Py_DecRef(pointer)`. The test passed; the first collection after it in the same JVM ran
 * `Py_DecRef(0xAAAA)`. Measured on the #45 suite (`hs_err`): `x0=0xaaaa`, `si_addr: 0xaaaa`,
 * `Py_XDECREF+0x1c` ← `topLevelDecRefAction` ← `CleanupAction.run`, thread `Cleaner-0`, twice in
 * two runs; the same suite with `GeneratedTableTest` left out ran 63 tests clean with the leak test
 * enabled. A test that runs 10,000 upcalls, or calls `System.gc()`, is simply the first thing that
 * makes the collector run.
 *
 * The upcall path itself was measured clean before anything changed: 30,000 `PyObject` arguments,
 * kept or closed inside the call, then a forced collection, leave `sys.getrefcount` exactly where it
 * started. These tests keep that true.
 *
 * ### Why a child JVM
 *
 * The failure is a SIGSEGV. In the test JVM it would take every other test down with it and report
 * nothing; in a child it becomes an exit code and a log, as `PackagedPythonHomeLaunchTest` does it.
 * The child runs `GeneratedTableTest`'s traverse proof first -- the test that left the bad wrappers --
 * and then the upcalls, so the red before the fix is the crash itself: exit 134 with `Py_XDECREF`
 * in the `hs_err` file.
 */
class PyObjectArgumentReleaseTest {

    private val scratchDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        scratchDirs.forEach { dir ->
            if (dir.exists()) {
                Files.walk(dir.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        }
        scratchDirs.clear()
    }

    /** The cleaner path: 10,000 wrappers left for the JVM to collect. */
    @Test
    fun tenThousandPyObjectArgumentsLeftToTheCleanerReleaseEachReferenceOnce() {
        assertProbeRunsClean(PyObjectArgumentReleaseProbe.KEEP)
    }

    /** The close path: the argument closed inside the call, 10,000 times (issue #98's own criterion). */
    @Test
    fun tenThousandPyObjectArgumentsClosedInsideTheUpcallReleaseEachReferenceOnce() {
        assertProbeRunsClean(PyObjectArgumentReleaseProbe.CLOSE)
    }

    private fun assertProbeRunsClean(mode: String) {
        val outcome = launchProbe(mode)
        assertEquals(
            0, outcome.exitCode,
            "the child JVM died (134 is SIGSEGV/abort -- a reference released that was never held).\n" +
                outcome.crashSummary + "\n--- child output ---\n" + outcome.output,
        )
        assertNotNull(
            outcome.output.lineSequence().firstOrNull { it == PyObjectArgumentReleaseProbe.SENTINEL },
            "the child exited 0 but never reached the sentinel:\n${outcome.output}",
        )
        val counts = assertNotNull(
            outcome.output.lineSequence().firstOrNull { it.startsWith(PyObjectArgumentReleaseProbe.COUNTS) },
            "the child reported no reference counts:\n${outcome.output}",
        )
        val fields = counts.removePrefix(PyObjectArgumentReleaseProbe.COUNTS).trim().split(' ')
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(
            fields["before"], fields["after"],
            "sys.getrefcount(token) did not return to its value before the 10,000 calls once the JVM " +
                "had collected the wrappers ($counts)",
        )
        assertEquals("0", fields["handles"], "the calls left Kotlin objects rooted in HandleTable ($counts)")
    }

    private class Outcome(val exitCode: Int, val output: String, val crashSummary: String)

    private fun launchProbe(mode: String): Outcome {
        val workDir = File("build/tmp/pyObjectArgumentReleaseTest/${java.util.UUID.randomUUID()}").absoluteFile
        workDir.mkdirs()
        scratchDirs += workDir
        val log = File(workDir, "probe.log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = buildList {
            add(java)
            // java.lang.foreign is a preview API on JDK 21, which is what desktopTest runs on.
            add("--enable-preview")
            add("--enable-native-access=ALL-UNNAMED")
            add("-XX:ErrorFile=${File(workDir, "hs_err_%p.log").path}")
            System.getProperty("java.library.path")?.let { add("-Djava.library.path=$it") }
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(PyObjectArgumentReleaseProbe::class.java.name)
            add(mode)
        }
        // PYTHONHOME and the rest of the environment are inherited from this test JVM.
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Outcome(-1, "timed out after $PROBE_TIMEOUT_SECONDS s\n" + log.readText(), "")
        }
        val crash = workDir.listFiles().orEmpty().filter { it.name.startsWith("hs_err_") }.joinToString("\n") { f ->
            f.readLines().filter {
                it.startsWith("# C ") || it.startsWith("siginfo:") || it.startsWith("Current thread") ||
                    it.startsWith(" x0=") || "topLevelDecRefAction" in it
            }.take(8).joinToString("\n", prefix = "--- ${f.name} ---\n")
        }
        return Outcome(process.exitValue(), log.readText(), crash)
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 180L
    }
}

/**
 * The child side of [PyObjectArgumentReleaseTest]. Prints [COUNTS] and then [SENTINEL]; a crash
 * prints neither.
 */
object PyObjectArgumentReleaseProbe {
    const val KEEP = "keep"
    const val CLOSE = "close"
    const val COUNTS = "RELEASE_PROBE_COUNTS"
    const val SENTINEL = "RELEASE_PROBE_SENTINEL_OK"

    private const val ITERATIONS = 10_000
    private const val SETTLE_TIMEOUT_MS = 60_000L
    private const val MIN_ROUNDS = 10

    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: KEEP
        Python3.initialize(silent = true)

        // What every earlier test in this module's JVM has done by the time a long test forces a
        // collection: left wrappers for the cleaner. The one that crashed is this one, so it runs
        // here, in this process, before the calls. Its own install/cleanup clears the tables, so
        // the binder is installed after it.
        GeneratedTableTest().run {
            install()
            try {
                aClassWithPyObjectFieldsGetsAGeneratedTraverseThatVisitsTheirRawPointers()
            } finally {
                cleanup()
            }
        }

        UpcallTable.clear()
        HandleTable.releaseAll()
        UpcallTable.install(FunctionTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()

        // `echoObject(value: PyObject?)` keeps nothing: the wrapper the trampoline made is left for
        // the cleaner. `closeArgument` closes it inside the call. Either way each call takes one
        // reference through `PyObject(item, borrowed = true)` and must give back exactly one.
        val call = if (mode == CLOSE) "assert closeArgument(_rp_token) == 1" else "assert echoObject(_rp_token) is _rp_token"
        Python3.exec(
            """
            import gc, sys
            from fixture.library import echoObject
            from fixture.app import closeArgument
            _rp_token = object()

            def _rp_loop(n):
                for _ in range(n):
                    $call
            """.trimIndent(),
        )
        val handlesBefore = HandleTable.liveCount
        val before = refcount()
        Python3.exec("_rp_loop($ITERATIONS)")
        val during = refcount()
        val after = settle(before)
        println("$COUNTS mode=$mode before=$before during=$during after=$after handles=${HandleTable.liveCount - handlesBefore}")

        // Something that allocates and frees a great deal, so a corrupted free list or a freed
        // object still in use shows up here rather than in whatever runs next.
        Python3.exec("_rp_x = [object() for _ in range(200000)]\ndel _rp_x\ngc.collect()")
        println(SENTINEL)
        System.out.flush()
        exitProcess(0)
    }

    /** `sys.getrefcount(_rp_token)`, read between `exec` calls so this thread holds no GIL. */
    private fun refcount(): Long {
        Python3.exec("_rp_rc = sys.getrefcount(_rp_token)")
        return Python3.import("__main__").getAttr("_rp_rc").toString().toLong()
    }

    /**
     * Drives `System.gc()` until the count is back at [target] and at least [MIN_ROUNDS] rounds have
     * run -- the rounds are what make the cleaner reach the wrappers `GeneratedTableTest` left, which
     * the count alone does not need. Gives up after [SETTLE_TIMEOUT_MS] and returns the last reading.
     */
    private fun settle(target: Long): Long {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var rounds = 0
        var last = refcount()
        while (System.currentTimeMillis() < deadline) {
            if (last == target && rounds >= MIN_ROUNDS) return last
            System.gc()
            Thread.sleep(20)
            rounds++
            last = refcount()
        }
        return last
    }
}
