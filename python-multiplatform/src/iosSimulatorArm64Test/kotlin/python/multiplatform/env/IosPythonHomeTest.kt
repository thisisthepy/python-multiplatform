package python.multiplatform.env

import python.multiplatform.Versions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC L-11 (issue #59): where an installed iOS app's CPython prefix is resolved from.
 *
 * The build-time half -- what is staged and copied into the bundle under that name -- is
 * `python-multiplatform-gradle-plugin/src/test/.../IosPythonHomeLayoutTest.kt`.
 *
 * What this cannot find: whether an *installed* app really starts from its bundle. The test binary
 * is not an app bundle and always runs with `PYTHONHOME` set by the simulator test task, so the
 * bundle branch is exercised here only through the pure overload; the installed-app run in
 * `docs/platforms/ios-app-bundle.md` is the check for the rest.
 */
class IosPythonHomeTest {

    private val bundled = "/app/Demo.app/python-multiplatform-home"

    @Test
    fun theDirectoryNameIsTheOneThePluginStages() {
        // `IosPythonHomeLayout.BUNDLE_DIRECTORY` in python-multiplatform-gradle-plugin.
        assertEquals("python-multiplatform-home", IosPythonHome.DIRECTORY_NAME)
    }

    @Test
    fun anEnvironmentPythonHomeWinsAndNothingIsApplied() {
        assertNull(IosPythonHome.resolve("/somewhere/else", "/app/Demo.app") { true })
    }

    @Test
    fun theBundledPrefixIsUsedWhenTheEnvironmentHasNone() {
        for (environment in listOf(null, "", "   ")) {
            val resolved = IosPythonHome.resolve(environment, "/app/Demo.app") { it == bundled }
            assertEquals(bundled, resolved?.path, "PYTHONHOME='$environment'")
            assertTrue("app bundle" in resolved!!.source, resolved.source)
        }
    }

    @Test
    fun aTrailingSlashOnTheResourcePathIsHarmless() {
        assertEquals(bundled, IosPythonHome.resolve(null, "/app/Demo.app/") { it == bundled }?.path)
    }

    @Test
    fun anAppBuiltWithoutThePhaseBehavesAsBefore() {
        // No directory in the bundle: nothing is set, and Py_Initialize() does what it always did.
        assertNull(IosPythonHome.resolve(null, "/app/Demo.app") { false })
        assertNull(IosPythonHome.resolve(null, null) { true })
        assertNull(IosPythonHome.resolve(null, "") { true })
    }

    @Test
    fun theSimulatorTestTaskStillGetsItsOwnPythonHome() {
        // `KotlinNativeSimulatorTest` sets SIMCTL_CHILD_PYTHONHOME (python-multiplatform/build.gradle.kts).
        // If that ever stopped reaching this process, the line below would be resolving the test
        // binary's directory instead -- the regression this pins.
        assertNotNull(readEnvVar("PYTHONHOME"), "the simulator test task no longer passes PYTHONHOME")
        assertNull(IosPythonHome.resolve())
    }

    @Test
    fun theDirectoryProbeTellsADirectoryFromAFile() {
        val home = assertNotNull(readEnvVar("PYTHONHOME"))
        val tag = Versions.currentVersion.taggedVersionString
        assertTrue(IosPythonHome.isDirectory(home), home)
        assertTrue(IosPythonHome.isDirectory("$home/lib/python$tag"))
        assertFalse(IosPythonHome.isDirectory("$home/lib/python$tag/os.py"))
        assertFalse(IosPythonHome.isDirectory("$home/does-not-exist"))
    }
}
