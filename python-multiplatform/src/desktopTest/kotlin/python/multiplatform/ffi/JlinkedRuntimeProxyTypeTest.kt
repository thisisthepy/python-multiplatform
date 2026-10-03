package python.multiplatform.ffi

import python.multiplatform.reflection.HandleTable
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
 * SPEC L-10 (issue #77): the desktop library runs on a Java runtime that contains **only
 * `java.base`** -- in particular, the proxy heap type is built and torn down without
 * `sun.misc.Unsafe`.
 *
 * Why a child JVM with `--limit-modules`. A packaged desktop app does not run on the JDK the build
 * used: Compose Desktop's `createDistributable` `jlink`s a runtime holding only the modules it was
 * told about -- `java.base java.datatransfer java.xml java.prefs java.desktop java.logging
 * jdk.crypto.ec` in the sample's app image (its `runtime/Contents/Home/release`). `sun.misc.Unsafe`
 * lives in `jdk.unsupported`, which is not among them. The test JVM is a full JDK where every module
 * is observable, so in-process tests could never see this; `--limit-modules` makes a full JDK
 * behave like the jlinked image without building one. `java.base` alone is stricter than Compose's
 * set (which is its transitive closure plus AWT), and is the runtime every `jlink` image has.
 *
 * Observed before the fix, in the packaged sample: `proxies : ExceptionInInitializerError: null`,
 * thrown from `ProxyType.<clinit>`'s `Class.forName("sun.misc.Unsafe")` (ClassNotFoundException),
 * then `No module named 'org'` from every step that imports a Kotlin namespace the proxy install
 * never built. The same jars on a full JDK printed `proxies : installed: ...`.
 *
 * Expected red before the fix: [theProxyTypeWorksOnAJavaBaseOnlyRuntime] fails with the child's
 * `PROBE_FAILED java.lang.ExceptionInInitializerError ... Caused by:
 * java.lang.ClassNotFoundException: sun.misc.Unsafe` and exit code [JlinkedRuntimeProbe.FAILED_EXIT_CODE].
 * [theProbeItselfWorksOnTheFullJdk] is the control, green before and after: it runs the same probe
 * without `--limit-modules`, so a red in the first test is the module set and not a broken probe.
 */
class JlinkedRuntimeProxyTypeTest {

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

    /** Under `build/`, not the system temporary directory -- AGENTS.md rule 2. */
    private fun newScratchDir(): File =
        File("build/tmp/jlinkedRuntimeProxyTypeTest/${java.util.UUID.randomUUID()}").absoluteFile
            .also { it.mkdirs(); scratchDirs += it }

    private class Outcome(val exitCode: Int, val output: String)

    /**
     * Launches [JlinkedRuntimeProbe] in a child JVM.
     *
     * `PYTHONHOME` is inherited from this test JVM and `-Djava.library.path=.` lets the child load
     * the classpath copy of `libpython` it extracts into its working directory -- exactly what
     * `PackagedPythonHomeLaunchTest`'s environment guard does. Both are held fixed on purpose: the
     * only thing this test varies is which JDK modules exist, so that is the only thing a red can
     * be about. (Where a packaged app finds its prefix is L-9's test.)
     */
    private fun launchProbe(limitModules: String?): Outcome {
        val workDir = newScratchDir()
        val log = File(workDir, "probe.log")
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = buildList {
            add(java)
            if (limitModules != null) {
                add("--limit-modules")
                add(limitModules)
            }
            // java.lang.foreign is a preview API on JDK 21, which is what desktopTest runs on.
            add("--enable-preview")
            add("--enable-native-access=ALL-UNNAMED")
            add("-Djava.library.path=.")
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(JlinkedRuntimeProbe::class.java.name)
        }
        val process = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Outcome(-1, "timed out after 120 s\n" + log.readText())
        }
        return Outcome(process.exitValue(), log.readText())
    }

    private fun assertProbeOk(outcome: Outcome) {
        assertNotNull(System.getenv("PYTHONHOME"), "PYTHONHOME is unset for desktopTest; the child cannot start CPython")
        assertEquals(0, outcome.exitCode, outcome.output)
        assertTrue(
            outcome.output.lineSequence().any { it == "PROBE_OK" },
            "the child never reported PROBE_OK:\n${outcome.output}",
        )
    }

    @Test
    fun theProxyTypeWorksOnAJavaBaseOnlyRuntime() {
        assertProbeOk(launchProbe(limitModules = "java.base"))
    }

    @Test
    fun theProbeItselfWorksOnTheFullJdk() {
        assertProbeOk(launchProbe(limitModules = null))
    }
}

/**
 * The child's `main`: the step the packaged sample died in, and the two native-memory accesses
 * behind it.
 *
 * 1. `ProxyTypeFactory.installGcBase()` -- what `PythonProxySource.install()` runs first. It builds
 *    the `PyType_Spec`/slot/member arrays in native memory and publishes `_pm_proxy_base`.
 * 2. A Python subclass instance that carries a real [HandleTable] handle in `_pm_handle` and is then
 *    dropped. Its `tp_dealloc` *reads* the handle slot and *writes* 0 into it before releasing the
 *    handle -- the peek and poke that went through `sun.misc.Unsafe`. A handle that still resolves
 *    afterwards means the dealloc never released it.
 *
 * Prints `PROBE_OK`, or `PROBE_FAILED <stack trace>` and exits with [FAILED_EXIT_CODE].
 */
object JlinkedRuntimeProbe {
    const val FAILED_EXIT_CODE = 4

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Python3.initialize(silent = true)
            check(ProxyTypeFactory.installGcBase()) { "installGcBase() returned false" }

            val handle = HandleTable.register(Any()).raw
            Python3.exec(
                """
                import gc
                class _JlinkProbeProxy(_pm_proxy_base):
                    pass
                _jp = _JlinkProbeProxy()
                _jp._pm_handle = $handle
                assert _jp._pm_handle == $handle, _jp._pm_handle
                del _jp
                gc.collect()
                """.trimIndent()
            )
            check(HandleTable.resolveRaw(handle) == null) {
                "tp_dealloc did not release handle $handle: the handle slot was not read or not cleared"
            }
        } catch (t: Throwable) {
            println("PROBE_FAILED ${t.stackTraceToString()}")
            System.out.flush()
            exitProcess(FAILED_EXIT_CODE)
        }
        println("PROBE_OK")
        System.out.flush()
        exitProcess(0)
    }
}
