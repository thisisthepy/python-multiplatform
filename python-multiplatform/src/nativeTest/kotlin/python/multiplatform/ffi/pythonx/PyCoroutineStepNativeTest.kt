package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.getThreadGILState
import python.multiplatform.ffi.upcall.NativeThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * SPEC U-5e on Kotlin/Native (#164 item 13; pythonx-concurrent S7.13 and S9.6): a step issued from a
 * bare `pthread_create` thread, one neither the Kotlin/Native runtime nor CPython attached on its own,
 * attaches for the call, completes the coroutine and leaves the thread detached. [NativeThread] is the
 * same shape `AsyncUpcallNativeDeliveryTest` uses, for the same reason: a `Worker` would launder the
 * question.
 *
 * Red before `PyCoroutineStep.kt` existed: `step` and `PyStep` did not resolve.
 */
class PyCoroutineStepNativeTest {

    @Test
    fun aStepFromAThreadTheRuntimeNeverAttachedCompletesTheCoroutine() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            import threading
            _stn = {'tids': []}
            class _StnPause:
                def __await__(self):
                    return (yield self)
            async def _stn_body():
                _stn['tids'].append(threading.get_ident())
                v = await _StnPause()
                _stn['tids'].append(threading.get_ident())
                return v * 2
            _stn['coro'] = _stn_body()
            """.trimIndent(),
        )
        val coro = PythonTestFixture.eval("_stn['coro']")
        val sent = python.multiplatform.ffi.types.basic.PyInt.from(21)
        var first: PyStep? = null
        var second: PyStep? = null
        var depthAfter = -1
        var failure: Throwable? = null
        val stepper = NativeThread {
            try {
                first = coro.step()
                second = coro.step(sent)
                depthAfter = getThreadGILState().depth
            } catch (t: Throwable) {
                failure = t
            }
        }
        stepper.start()
        stepper.join()

        assertNull(failure, "the native stepping thread failed: $failure")
        assertIs<PyStep.Yielded>(first).value.close()
        val done = assertIs<PyStep.Returned>(second)
        assertEquals("42", done.value.toString())
        done.value.close()
        assertEquals(0, depthAfter, "the native thread must be detached after its steps")
        assertEquals("1", PythonTestFixture.eval("len(set(_stn['tids']))").toString(), "both steps ran on the one native thread")
        assertEquals("False", PythonTestFixture.eval("threading.get_ident() in _stn['tids']").toString(), "no step ran on the test thread")
    }
}
