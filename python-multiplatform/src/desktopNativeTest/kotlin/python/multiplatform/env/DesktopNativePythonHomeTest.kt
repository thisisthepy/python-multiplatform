package python.multiplatform.env

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a desktop Kotlin/Native application (`macosArm64`, `linuxX64`) resolves its packaged
 * CPython prefix and payload from.
 */
class DesktopNativePythonHomeTest {

    private val besideBinary = "/apps/demo/python-multiplatform-home"
    private val inAppBundle = "/apps/Demo.app/Contents/Resources/python-multiplatform-home"

    @Test
    fun theDirectoryNameMatchesThePluginAndDesktopAndIos() {
        assertEquals("python-multiplatform-home", DesktopNativePythonHome.DIRECTORY_NAME)
    }

    @Test
    fun anEnvironmentPythonHomeWinsAndNothingIsApplied() {
        assertNull(
            DesktopNativePythonHome.resolve(
                environmentHome = "/from/env",
                executableDir = "/apps/demo",
                isDirectory = { true },
            )
        )
    }

    @Test
    fun thePrefixBesideTheExecutableIsUsedWhenPresent() {
        for (environment in listOf(null, "", "   ")) {
            val resolved = DesktopNativePythonHome.resolve(
                environmentHome = environment,
                executableDir = "/apps/demo",
                isDirectory = { it == besideBinary },
            )
            assertEquals(besideBinary, resolved?.path, "PYTHONHOME='$environment'")
            assertTrue("beside the executable" in resolved!!.source, resolved.source)
        }
    }

    @Test
    fun anAppBundleResourceDirectoryIsCheckedWhenNothingSitsBesideTheBinary() {
        val resolved = DesktopNativePythonHome.resolve(
            environmentHome = null,
            executableDir = "/apps/Demo.app/Contents/MacOS",
            isDirectory = { it == inAppBundle },
        )
        assertEquals(inAppBundle, resolved?.path)
        assertTrue("Resources" in resolved!!.source, resolved.source)
    }

    @Test
    fun anUnpackagedExecutableBehavesAsBefore() {
        assertNull(DesktopNativePythonHome.resolve(null, "/apps/demo") { false })
        assertNull(DesktopNativePythonHome.resolve(null, null) { true })
        assertNull(DesktopNativePythonHome.resolve(null, "") { true })
    }

    @Test
    fun theHostTestTaskPassesPythonHomeAndExecutableDirectoryResolves() {
        val home = assertNotNull(readEnvVar("PYTHONHOME"), "the host test task must pass PYTHONHOME")
        assertNull(DesktopNativePythonHome.resolve())
        val exeDir = assertNotNull(executableDirectory(), "executableDirectory() returned null")
        assertTrue(DesktopNativePythonHome.isDirectory(exeDir), exeDir)
        assertTrue(DesktopNativePythonHome.isDirectory(home), home)
        assertFalse(DesktopNativePythonHome.isDirectory("$home/does-not-exist"))
    }

    @Test
    fun payloadDiscoveryFindsBesideExecutableAndBundleResources() {
        assertEquals(
            listOf("/apps/demo/python"),
            discoverStagedPayloadRoots("/apps/demo") { it == "/apps/demo/python" },
        )
        assertEquals(
            listOf("/apps/Demo.app/Contents/Resources/python"),
            discoverStagedPayloadRoots("/apps/Demo.app/Contents/MacOS") {
                it == "/apps/Demo.app/Contents/Resources/python"
            },
        )
        assertEquals(
            emptyList(),
            discoverStagedPayloadRoots("/apps/demo") { false },
        )
    }
}
