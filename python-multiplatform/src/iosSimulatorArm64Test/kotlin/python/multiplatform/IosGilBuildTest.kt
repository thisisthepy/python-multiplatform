package python.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #158 moved desktop to the free-threaded CPython 3.15t; iOS still embeds the GIL build until
 * #328. This pins that the desktop change did not leak here.
 *
 * Not red before the change: iOS was GIL before and is GIL after. It is the guard against the
 * naive implementation (flipping one shared `BuildConfig.pythonFreeThreaded`), under which
 * `abiFlags` reads `"t"` here and the runtime looks for a `python3.15t` that iOS does not ship.
 */
class IosGilBuildTest {

    @Test
    fun iosStillReportsTheGilAbi() {
        val v = Versions.currentVersion
        assertEquals("", v.abiFlags, "iOS embeds the GIL CPython until #328")
        assertEquals(v.compactVersionString, v.taggedVersionString)
    }
}
