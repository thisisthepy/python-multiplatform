package python.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC T-1 (issue #158): desktop Kotlin/Native (`macosArm64`, `linuxX64`) links the free-threaded
 * CPython 3.15t, the same build as the desktop JVM.
 *
 * Red before the change: `abiFlags` came from the one `BuildConfig.pythonFreeThreaded` (default
 * false), so it was `""` here, and the cinterop compiled against `include/python3.15` and linked
 * `-lpython3.15`. This source set shares `nativeMain` with iOS and androidNative, which must stay
 * GIL (`IosGilBuildTest`, `AndroidNativeGilBuildTest`), so a single shared flag cannot pass both.
 */
class DesktopNativeFreeThreadedBuildTest {

    @Test
    fun desktopNativeReportsTheFreeThreadedAbi() {
        val v = Versions.currentVersion
        assertEquals("t", v.abiFlags, "desktop Kotlin/Native links CPython ${v.compactVersionString}t (#158)")
        assertEquals(v.compactVersionString + "t", v.taggedVersionString)
    }
}
