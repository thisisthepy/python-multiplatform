package python.multiplatform.env

import python.multiplatform.ffi.Python3
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC L-9 (issue #60), end to end: a JVM whose environment has **no** `PYTHONHOME` starts CPython
 * and imports from the standard library, given only what a packaged application has -- a system
 * property, or Compose's `compose.application.resources.dir`.
 *
 * It has to be a child process. This test JVM's interpreter was initialised from `PYTHONHOME` by an
 * earlier test (or will be by a later one), `Py_Initialize` runs once per process, and a JVM cannot
 * remove a variable from its own native environment. The child is launched the way a packaged app
 * is: the same class path, no `-Djava.library.path=.` (which is what lets this build's own tests load
 * the classpath copy of `libpython` out of the working directory), and `PYTHONHOME` removed.
 *
 * Before L-9 the first two tests fail with the child aborting in `Py_Initialize`
 * (`Fatal Python error: ... Failed to import encodings module`, non-zero exit, no `PROBE_OK`), and
 * the third with the same abort instead of a `PROBE_REFUSED` naming the path. The fourth is a guard,
 * green before and after: an environment `PYTHONHOME` must keep winning.
 */
class PackagedPythonHomeLaunchTest {

    private val scratchDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        scratchDirs.forEach { it.deleteRecursively() }
        scratchDirs.clear()
    }

    /** Under `build/`, not the system temporary directory -- AGENTS.md rule 2. */
    private fun newScratchDir(): File =
        File("build/tmp/packagedPythonHomeLaunchTest/${java.util.UUID.randomUUID()}").absoluteFile
            .also { it.mkdirs(); scratchDirs += it }

    private fun stagedPrefix(): String = assertNotNull(
        System.getenv("PYTHONHOME"),
        "PYTHONHOME is unset for desktopTest; there is no real prefix to package",
    )

    private class Outcome(val exitCode: Int, val output: String)

    // Literals rather than `PackagedPythonHome`'s constants, so that this file compiles before
    // L-9 exists and its red is a runtime abort, distinguishable from a compile error.
    private val homeProperty = "python.multiplatform.home"
    private val composeResourcesProperty = "compose.application.resources.dir"
    private val homeDirectoryName = "python-multiplatform-home"

    /**
     * @param dotOnLibraryPath adds `-Djava.library.path=.`, which is how this build's own test JVMs
     *   load the classpath copy of `libpython` (extracted into the working directory). Only the
     *   environment-`PYTHONHOME` guard uses it; a packaged app has no such flag.
     */
    private fun launchProbe(
        environmentHome: String?,
        systemProperties: Map<String, String>,
        dotOnLibraryPath: Boolean = false,
    ): Outcome {
        val workDir = newScratchDir()
        val log = File(workDir, "probe.log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = buildList {
            add(java)
            // java.lang.foreign is a preview API on JDK 21, which is what desktopTest runs on.
            add("--enable-preview")
            add("--enable-native-access=ALL-UNNAMED")
            if (dotOnLibraryPath) add("-Djava.library.path=.")
            systemProperties.forEach { (key, value) -> add("-D$key=$value") }
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(PackagedHomeProbe::class.java.name)
        }
        val builder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(log)
        builder.environment().remove("PYTHONHOME")
        builder.environment().remove("PYTHON_MULTIPLATFORM_LIBPYTHON")
        if (environmentHome != null) builder.environment()["PYTHONHOME"] = environmentHome
        val process = builder.start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Outcome(-1, "timed out after 120 s\n" + log.readText())
        }
        return Outcome(process.exitValue(), log.readText())
    }

    @Test
    fun aJvmWithoutPythonHomeImportsTheStdlibFromThePrefixAPropertyNames() {
        val prefix = stagedPrefix()
        val outcome = launchProbe(
            environmentHome = null,
            systemProperties = mapOf(homeProperty to prefix),
        )
        assertEquals(0, outcome.exitCode, outcome.output)
        val line = outcome.output.lineSequence().firstOrNull { it.startsWith("PROBE_OK ") }
        assertNotNull(line, "the child never reported a successful import:\n${outcome.output}")
        // sys.prefix is what CPython derived its stdlib path from; it must be ours, not a guess.
        assertEquals(File(prefix).canonicalPath, File(line.substringAfter("PROBE_OK ")).canonicalPath, outcome.output)
    }

    @Test
    fun aJvmWithoutPythonHomeImportsTheStdlibFromComposeResources() {
        // Exactly what `createDistributable` produces: `$APPDIR/resources/python-multiplatform-home`
        // and Compose's own `-Dcompose.application.resources.dir=$APPDIR/resources`. The prefix is
        // linked rather than copied to keep this test from writing 25 MB per run.
        val resources = newScratchDir()
        Files.createSymbolicLink(
            File(resources, homeDirectoryName).toPath(),
            File(stagedPrefix()).toPath(),
        )
        val outcome = launchProbe(
            environmentHome = null,
            systemProperties = mapOf(composeResourcesProperty to resources.path),
        )
        assertEquals(0, outcome.exitCode, outcome.output)
        val line = outcome.output.lineSequence().firstOrNull { it.startsWith("PROBE_OK ") }
        assertNotNull(line, "the child never reported a successful import:\n${outcome.output}")
        assertTrue(
            line.substringAfter("PROBE_OK ").trimEnd('/', '\\').endsWith(homeDirectoryName),
            "sys.prefix is not the Compose resources home: $line",
        )
    }

    @Test
    fun anUnusablePropertyPrefixIsACatchableExceptionNamingThePath() {
        val missing = File(newScratchDir(), "no-such-prefix").path
        val outcome = launchProbe(
            environmentHome = null,
            systemProperties = mapOf(homeProperty to missing),
        )
        assertEquals(PackagedHomeProbe.REFUSED_EXIT_CODE, outcome.exitCode, outcome.output)
        val line = outcome.output.lineSequence().firstOrNull { it.startsWith("PROBE_REFUSED ") }
        assertNotNull(line, "the child did not refuse with a Kotlin exception:\n${outcome.output}")
        assertTrue(missing in line, "the refusal does not name the path: $line")
        assertTrue(homeProperty in line, "the refusal does not say where the path came from: $line")
    }

    @Test
    fun anEnvironmentPythonHomeStillWinsOverTheProperty() {
        // Guard (green before L-9 as well): a developer's or a test task's PYTHONHOME must not be
        // overridden by a property baked into a launcher.
        val prefix = stagedPrefix()
        val outcome = launchProbe(
            environmentHome = prefix,
            systemProperties = mapOf(homeProperty to File(newScratchDir(), "ignored").path),
            dotOnLibraryPath = true,
        )
        assertEquals(0, outcome.exitCode, outcome.output)
        val line = outcome.output.lineSequence().firstOrNull { it.startsWith("PROBE_OK ") }
        assertNotNull(line, outcome.output)
        assertEquals(File(prefix).canonicalPath, File(line.substringAfter("PROBE_OK ")).canonicalPath, outcome.output)
    }
}

/**
 * The child's `main`. Prints `PROBE_OK <sys.prefix>` after a real stdlib import, or
 * `PROBE_REFUSED <message>` and exits with [REFUSED_EXIT_CODE] when `Python3.initialize()` throws.
 * A `Py_FatalError` abort exits with neither line, which is the pre-L-9 failure.
 */
object PackagedHomeProbe {
    const val REFUSED_EXIT_CODE = 3

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Python3.initialize(silent = true)
        } catch (e: IllegalStateException) {
            println("PROBE_REFUSED ${e.message}")
            System.out.flush()
            exitProcess(REFUSED_EXIT_CODE)
        }
        // `json` pulls in `encodings`, `re` and a C extension (`_json`), so it exercises the pure
        // Python tree and the extension-module directory alike.
        Python3.exec(
            """
            import json, sys
            json.dumps({"a": [1, 2]})
            print("PROBE_OK " + sys.prefix, flush=True)
            """.trimIndent()
        )
        exitProcess(0)
    }
}
