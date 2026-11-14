package python.multiplatform

import python.multiplatform.env.PythonBootstrap
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC T-2 (issue #328): Android (ART, arm64-v8a and x86_64) embeds the free-threaded CPython 3.15t,
 * and only that build.
 *
 * The maintainer's decision on #158 (2026-10-04): "Free-threaded is the default and the only build.
 * The GIL build is not kept as an option. Baseline is CPython 3.15t." Desktop moved first (#398);
 * Android follows here, with no opt-in flag (leader, 2026-11-13).
 *
 * Red before the change: `embedsFreeThreadedPython` was `false` in `androidMain`, so `abiFlags` was
 * `""`, the stdlib was looked for under `lib/python3.15`, the AAR's `python.properties` said
 * `freeThreaded=false`, `autoDrainInterval` defaulted to 0, and the loaded `libpython3.15.so` was
 * python.org's GIL build (`Py_GIL_DISABLED` 0). Each assertion fails on that build with its own
 * message. (This replaces `AndroidGilBuildTest`, which pinned the GIL build until #328.)
 */
class AndroidFreeThreadedBuildTest {

    @Test
    fun androidReportsTheFreeThreadedAbi() {
        val v = Versions.currentVersion
        assertEquals("t", v.abiFlags, "Android embeds CPython ${v.compactVersionString}t (#328)")
        assertEquals(v.compactVersionString + "t", v.taggedVersionString)
    }

    @Test
    fun theStdlibIsLookedForUnderTheFreeThreadedDirectory() {
        val tagged = Versions.currentVersion.compactVersionString + "t"
        assertEquals("lib/python$tagged", PythonBootstrap.stdlibRelativePath())
        assertEquals(".python-multiplatform-stdlib.$tagged", PythonBootstrap.stampFile(File("/prefix")).name)
    }

    @Test
    fun theAarSaysItIsFreeThreaded() {
        // The AAR's `META-INF/python-multiplatform/python.properties` (generateAndroidPythonProperties).
        val text = assertNotNull(
            PythonBootstrap::class.java.getResourceAsStream("/META-INF/python-multiplatform/python.properties"),
            "the AAR carries no META-INF/python-multiplatform/python.properties",
        ).use { it.readBytes().decodeToString() }
        assertTrue("freeThreaded=true" in text, "the AAR's python.properties must say freeThreaded=true (#328): $text")
    }

    @Test
    fun theLoadedInterpreterIsReallyFreeThreaded() = PythonTestFixture.withInterpreter {
        // `Py_GIL_DISABLED` is a build-time fact of the libpython that was loaded;
        // `sys._is_gil_enabled()` is not asserted, because an extension module without `Py_mod_gil`
        // re-enables the GIL at run time on a free-threaded build.
        assertEquals(
            "1",
            PythonTestFixture.eval("__import__('sysconfig').get_config_var('Py_GIL_DISABLED')").toString(),
            "the Android libpython is a GIL build; Android must embed the free-threaded one (#328)",
        )
    }

    @Test
    fun theAutomaticCheckpointIsOnByDefault() {
        assertTrue(
            Python3.autoDrainInterval > 0,
            "autoDrainInterval=${Python3.autoDrainInterval}: the free-threaded default turns the checkpoint on",
        )
    }
}
