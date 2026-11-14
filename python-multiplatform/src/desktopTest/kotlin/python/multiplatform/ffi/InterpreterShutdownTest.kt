package python.multiplatform.ffi

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC L-1a (issue #339), end to end: what a consumer app sees on its process stdout when it ends
 * the interpreter with the public `Python3.finalize()` or flushes it with `Python3.flush()`.
 *
 * Each case is a child JVM. Finalizing destroys the interpreter every other test in this JVM shares,
 * and the point is the process's real stdout (a file here, so Python's `sys.stdout` is block
 * buffered exactly as in a packaged app whose output is redirected). The child is launched the way
 * `PackagedPythonHomeLaunchTest`'s environment-`PYTHONHOME` guard launches its probe.
 *
 * Red before #339: [ShutdownProbe] calls `Python3.flush()`, which did not exist, so this file did not
 * compile (a compile error, not a behavioural failure). [withoutFlushTheBufferedLineIsLost] is the
 * control that shows the test can fail: the same probe without the flush loses the line.
 */
class InterpreterShutdownTest {

    private val scratchDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        scratchDirs.forEach { dir ->
            if (Files.exists(dir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(dir.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
                }
            }
        }
        scratchDirs.clear()
    }

    /** Under `build/`, not the system temporary directory (AGENTS.md rule 2). */
    private fun newScratchDir(): File =
        File("build/tmp/interpreterShutdownTest/${java.util.UUID.randomUUID()}").absoluteFile
            .also { it.mkdirs(); scratchDirs += it }

    private class Outcome(val exitCode: Int, val output: String)

    private fun launch(scenario: String): Outcome {
        val home = assertNotNull(System.getenv("PYTHONHOME"), "PYTHONHOME is unset for desktopTest")
        val workDir = newScratchDir()
        val log = File(workDir, "probe.log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = listOf(
            java,
            "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "-Djava.library.path=.",
            "-cp",
            System.getProperty("java.class.path"),
            ShutdownProbe::class.java.name,
            scenario,
        )
        val builder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(log)
        builder.environment().remove("PYTHON_MULTIPLATFORM_LIBPYTHON")
        // Python would line-buffer or not buffer at all with this set, and the test would prove nothing.
        builder.environment().remove("PYTHONUNBUFFERED")
        builder.environment()["PYTHONHOME"] = home
        val process = builder.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Outcome(-1, "timed out after 120 s\n" + log.readText())
        }
        return Outcome(process.exitValue(), log.readText())
    }

    @Test
    fun finalizeFlushesBufferedOutputAndReportsStatus() {
        val outcome = launch("finalize")
        assertEquals(0, outcome.exitCode, outcome.output)
        assertTrue(ShutdownProbe.BUFFERED_LINE in outcome.output, "Python's buffered stdout was lost:\n${outcome.output}")
        assertTrue("FINALIZED status=0 initialized=false" in outcome.output, outcome.output)
    }

    @Test
    fun flushWithoutFinalizeKeepsBufferedOutput() {
        val outcome = launch("flush")
        assertEquals(0, outcome.exitCode, outcome.output)
        assertTrue(ShutdownProbe.BUFFERED_LINE in outcome.output, "Python's buffered stdout was lost:\n${outcome.output}")
    }

    @Test
    fun withoutFlushTheBufferedLineIsLost() {
        // Control: if this ever finds the line, stdout is not buffered in the child and the two
        // tests above no longer prove anything.
        val outcome = launch("noflush")
        assertEquals(0, outcome.exitCode, outcome.output)
        assertTrue("NOFLUSH_DONE" in outcome.output, outcome.output)
        assertFalse(ShutdownProbe.BUFFERED_LINE in outcome.output, "stdout was not buffered:\n${outcome.output}")
    }

    @Test
    fun finalizeFromAnotherThreadIsRefusedAndTheInterpreterSurvives() {
        val outcome = launch("otherthread")
        assertEquals(0, outcome.exitCode, outcome.output)
        assertTrue("OTHER_THREAD_REFUSED" in outcome.output, outcome.output)
        assertTrue("STILL_RUNNING 42" in outcome.output, outcome.output)
        assertTrue("FINALIZED status=0 initialized=false" in outcome.output, outcome.output)
    }

    @Test
    fun finalizeWhenNotRunningReturnsZero() {
        val outcome = launch("notrunning")
        assertEquals(0, outcome.exitCode, outcome.output)
        assertTrue("NOT_RUNNING status=0" in outcome.output, outcome.output)
    }
}

/** The child's `main`; `args[0]` names the scenario. Kotlin output always goes through `println` + flush. */
object ShutdownProbe {
    const val BUFFERED_LINE = "PMP_BUFFERED_LINE_339"

    private fun writeBuffered() {
        // No flush=True and no newline-triggered flush: the stream is a file, so it is block buffered.
        Python3.exec("import sys\nsys.stdout.write('$BUFFERED_LINE\\n')")
    }

    private fun say(line: String) {
        println(line)
        System.out.flush()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.single()) {
            "finalize" -> {
                Python3.initialize(silent = true)
                writeBuffered()
                val status = Python3.finalize(silent = true)
                say("FINALIZED status=$status initialized=${Python3.isInitialized}")
                exitProcess(0)
            }
            "flush" -> {
                Python3.initialize(silent = true)
                writeBuffered()
                Python3.flush()
                // halt: no shutdown hooks, nothing else gets a chance to flush on our behalf.
                Runtime.getRuntime().halt(0)
            }
            "noflush" -> {
                Python3.initialize(silent = true)
                writeBuffered()
                say("NOFLUSH_DONE")
                Runtime.getRuntime().halt(0)
            }
            "otherthread" -> {
                Python3.initialize(silent = true)
                var refused = false
                val thread = Thread {
                    try {
                        Python3.finalize(silent = true)
                    } catch (e: IllegalStateException) {
                        refused = true
                    }
                }
                thread.start()
                thread.join()
                if (refused) say("OTHER_THREAD_REFUSED")
                say("STILL_RUNNING ${Python3.eval("6 * 7")}")
                val status = Python3.finalize(silent = true)
                say("FINALIZED status=$status initialized=${Python3.isInitialized}")
                exitProcess(0)
            }
            "notrunning" -> {
                val status = Python3.finalize(silent = true)
                say("NOT_RUNNING status=$status")
                exitProcess(0)
            }
            else -> exitProcess(2)
        }
    }
}
