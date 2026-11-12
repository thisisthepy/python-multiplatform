package python.multiplatform

import python.multiplatform.env.PythonBootstrap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #158 moved desktop to the free-threaded CPython 3.15t; Android (ART) still embeds
 * python.org's GIL CPython 3.15 until #328. This pins that the desktop change did not leak here.
 *
 * Not red before the change: Android was GIL before and is GIL after. It is the guard against the
 * naive implementation (flipping one shared `BuildConfig.pythonFreeThreaded`), under which
 * [PythonBootstrap.stdlibRelativePath] becomes `lib/python3.15t`, a directory the staged GIL
 * prefix does not contain, and the app dies in `onCreate`. Nothing here loads the interpreter. It
 * lives in `androidInstrumentedTest` because `androidUnitTest` does not compile on develop (four
 * `commonTest` expects have no host-JVM actual), not because it needs a device.
 */
class AndroidGilBuildTest {

    @Test
    fun androidStillReportsTheGilAbi() {
        val v = Versions.currentVersion
        assertEquals("", v.abiFlags, "Android embeds the GIL CPython until #328")
        assertEquals(v.compactVersionString, v.taggedVersionString)
    }

    @Test
    fun theStdlibIsLookedForUnderTheGilDirectory() {
        val compact = Versions.currentVersion.compactVersionString
        assertEquals("lib/python$compact", PythonBootstrap.stdlibRelativePath())
        assertEquals(".python-multiplatform-stdlib.$compact", PythonBootstrap.stampFile(File("/prefix")).name)
    }
}
