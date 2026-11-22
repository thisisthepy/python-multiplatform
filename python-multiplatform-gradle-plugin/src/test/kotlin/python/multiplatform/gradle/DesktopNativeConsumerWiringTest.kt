package python.multiplatform.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC L-9c (#191): what the plugin decides for a consumer's Kotlin/Native desktop targets. The
 * wiring itself (tasks, binaries) reaches KGP by reflection and is exercised by a consumer fixture in
 * CI; these pin the decisions it is made of.
 *
 * Red before: none of these functions existed; a consumer's `macosArm64` / `linuxX64` executable was
 * linked with no libpython at all ("undefined symbol: Py_Initialize").
 */
class DesktopNativeConsumerWiringTest {

    @Test
    fun eachDesktopNativeTargetStagesItsOwnPlatformsPrefix() {
        assertEquals("macos-aarch64", desktopNativePlatformFor("macos_arm64"))
        assertEquals("linux-x86_64", desktopNativePlatformFor("linux_x64"))
        // Not desktop native: iOS has its own wiring (L-11), Android ships libpython in the AAR.
        assertNull(desktopNativePlatformFor("ios_simulator_arm64"))
        assertNull(desktopNativePlatformFor("android_arm64"))
    }

    @Test
    fun theLibraryNameCarriesTheFreeThreadedTag() {
        assertEquals("python3.15t", desktopNativeLibPythonName("3.15.0", freeThreaded = true))
        assertEquals("python3.15", desktopNativeLibPythonName("3.15.0", freeThreaded = false))
    }

    @Test
    fun aReleaseBinarySearchesOnlyBesideItself() {
        val opts = desktopNativeLinkerOpts("macos_arm64", "/cache/python", "python3.15t", debug = false)
        assertEquals(
            listOf("-L/cache/python/lib", "-lpython3.15t", "-Wl,-rpath,@executable_path/python-multiplatform-home/lib"),
            opts,
        )
        assertFalse(opts.any { it.contains("-rpath,/cache") }, "a build path must not end up in a release binary")
    }

    @Test
    fun aDebugOrTestBinaryAlsoFindsTheStagedPrefix() {
        assertEquals(
            listOf(
                "-L/cache/python/lib", "-lpython3.15t",
                "-Wl,-rpath,\$ORIGIN/python-multiplatform-home/lib",
                "-Wl,-rpath,/cache/python/lib",
            ),
            desktopNativeLinkerOpts("linux_x64", "/cache/python", "python3.15t", debug = true),
        )
    }

    @Test
    fun theBinaryKindsThatAreLinkedAreExecutablesAndTests() {
        assertTrue(isLinkedDesktopNativeBinary("Executable"))
        assertTrue(isLinkedDesktopNativeBinary("TestExecutable"))
        assertTrue(isLinkedDesktopNativeBinary("SharedLibrary"))
        // A static library or framework is linked by whoever consumes it.
        assertFalse(isLinkedDesktopNativeBinary("StaticLibrary"))
        assertFalse(isLinkedDesktopNativeBinary("Framework"))
    }

    @Test
    fun theStagingTaskIsNamedForItsTarget() {
        assertEquals("stagePythonHomeMacosArm64", desktopNativeStageTaskName("macosArm64"))
        assertEquals("stagePythonHomeLinuxX64", desktopNativeStageTaskName("linuxX64"))
    }

    // ---- SPEC L-9c, Windows (mingwX64, SPEC P-1a) ----

    @Test
    fun windowsStagesTheWindowsPrefix() {
        assertEquals("windows-x86_64", desktopNativePlatformFor("mingw_x64"))
    }

    /** No rpath on Windows: the import library is linked, and the DLL is found beside the executable. */
    @Test
    fun windowsLinksTheImportLibraryAndCarriesNoSearchPath() {
        assertEquals(
            listOf("/cache/python/libs/python315t.lib"),
            desktopNativeLinkerOpts("mingw_x64", "/cache/python", "python3.15t", debug = true),
        )
        assertEquals(
            listOf("/cache/python/libs/python315t.lib"),
            desktopNativeLinkerOpts("mingw_x64", "/cache/python", "python3.15t", debug = false),
        )
    }

    /** The loader looks beside the executable, not in a subdirectory: the DLLs go there; the stdlib stays in the home. */
    @Test
    fun windowsPutsTheDllsBesideTheExecutable() {
        assertEquals(listOf("*.dll"), desktopNativeExecutableDirIncludes(windows = true))
        assertEquals(emptyList(), desktopNativeExecutableDirIncludes(windows = false))
        assertEquals(listOf("Lib/**", "DLLs/**"), desktopNativeHomeIncludes("3.15.0", freeThreaded = true, windows = true))
        assertEquals(
            listOf("lib/python3.15t/**", "lib/libpython3.15t.*"),
            desktopNativeHomeIncludes("3.15.0", freeThreaded = true, windows = false),
        )
    }
}
