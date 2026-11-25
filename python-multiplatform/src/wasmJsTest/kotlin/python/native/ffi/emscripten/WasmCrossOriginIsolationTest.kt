package python.native.ffi.emscripten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC T-8: a browser page that is not cross-origin isolated fails at start-up with an explanation
 * when the runtime has a shared memory, and nothing else is checked.
 *
 * The decision is `cpython.mjs`'s `crossOriginIsolationError`, the function its start-up calls before
 * the Emscripten factory; the browser cases are tested through it because this suite runs under Node,
 * where the check never applies. The last test ties the decision's input to the real runtime: the
 * `SHARED_MEMORY` the staging generated must say what the loaded memory actually is, or a threaded
 * runtime would start unchecked (or a GIL one would be refused).
 */
class WasmCrossOriginIsolationTest {

    private fun browserError(sharedMemory: Boolean, isolated: JsAny?): String? =
        crossOriginIsolationError(isNode = false, sharedMemory = sharedMemory, isolated = isolated)?.toString()

    @Test
    fun aSharedMemoryRuntimeInAPageThatIsNotIsolatedFailsWithAnExplanation() {
        for (isolated in listOf<JsAny?>(null, false.toJsBoolean())) {
            val message = assertNotNull(
                browserError(sharedMemory = true, isolated = isolated),
                "crossOriginIsolated = $isolated with a shared memory must be refused",
            )
            for (part in listOf(
                "crossOriginIsolated",
                "Cross-Origin-Opener-Policy: same-origin",
                "Cross-Origin-Embedder-Policy",
                "require-corp",
                "credentialless",
                "service worker",
            )) {
                assertTrue(part in message, "the message must name '$part': $message")
            }
        }
    }

    @Test
    fun anIsolatedPageStarts() {
        assertNull(browserError(sharedMemory = true, isolated = true.toJsBoolean()))
    }

    @Test
    fun aRuntimeWithoutASharedMemoryIsNeverChecked() {
        assertNull(browserError(sharedMemory = false, isolated = false.toJsBoolean()))
        assertNull(browserError(sharedMemory = false, isolated = null))
    }

    @Test
    fun nodeIsNeverChecked() {
        assertNull(crossOriginIsolationError(isNode = true, sharedMemory = true, isolated = null))
        assertNull(crossOriginIsolationError(isNode = true, sharedMemory = true, isolated = false.toJsBoolean()))
    }

    @Test
    fun theGeneratedFlagSaysWhatTheLoadedMemoryIs() {
        assertEquals(
            memoryIsShared(wasmMemory), SHARED_MEMORY.toBoolean(),
            "cpython-config.mjs's SHARED_MEMORY must match the runtime's wasmMemory",
        )
    }
}

private fun memoryIsShared(memory: JsAny): Boolean =
    js("typeof SharedArrayBuffer !== 'undefined' && memory.buffer instanceof SharedArrayBuffer")
