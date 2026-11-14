package python.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #158 moved desktop to the free-threaded CPython 3.15t, and #328 moved Android, androidNative
 * and iOS; wasmJs still embeds the GIL build until #175. This pins that neither change leaked here.
 *
 * Not red before the change: wasmJs was GIL before and is GIL after. It is the guard against the
 * naive implementation (flipping one shared `BuildConfig.pythonFreeThreaded`), under which
 * `abiFlags` reads `"t"` here and the runtime looks for a `python3.15t` that wasmJs does not ship.
 */
class WasmGilBuildTest {

    @Test
    fun wasmStillReportsTheGilAbi() {
        val v = Versions.currentVersion
        assertEquals("", v.abiFlags, "wasmJs embeds the GIL CPython until #175")
        assertEquals(v.compactVersionString, v.taggedVersionString)
    }
}
