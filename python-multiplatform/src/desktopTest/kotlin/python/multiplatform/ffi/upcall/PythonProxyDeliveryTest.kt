package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The half of `PythonProxyInstallTest` that needs a second thread: `await` over a generated proxy
 * whose Kotlin body genuinely suspended, resumed from a thread CPython is not running on.
 *
 * Everything else about the generated proxies is in `commonTest` and runs on every target with a
 * boundary shim. This stayed behind only because the thread primitive is not common --
 * `PythonProxyNativeDeliveryTest` is the same three tests over `pthread_create`, and both drive the
 * same [ProxyFragment].
 */
class PythonProxyDeliveryTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ProxyFragment))
        ProxyFragment.parked = null
        ProxyFragment.tally = 0
        ProxyFragment.created = 0
        assertTrue(bindUpcallOrNull("demo.calc.ping"), "the fixture table is not installed")
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        ProxyFragment.parked = null
    }

    @Test
    fun awaitingASuspendingInstanceMethodDeliversTheValueFromAKotlinThread() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            ProxyLoopHarness.install()
            Python3.exec("from proxycls import Counter\nc = Counter(3)")

            val outcome = runOnLoopThread("c.fetchLater(4)")

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertEquals("None", outcome.error, "the generated proxy raised instead of resolving")
            assertEquals("7", outcome.value)
            assertEquals(
                1,
                outcome.futuresCreated,
                "no Future was built, so this run took the fast path and proves nothing about delivery",
            )
        }

    @Test
    fun awaitingAGeneratedProxyForASuspendingCallDeliversTheValueFromAKotlinThread() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            ProxyLoopHarness.install()

            val outcome = runOnLoopThread("demo.calc.doubleLater(21)")

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertEquals("None", outcome.error, "the generated proxy raised instead of resolving")
            assertEquals("42", outcome.value)
            assertEquals(
                1,
                outcome.futuresCreated,
                "no Future was built, so this run took the fast path and proves nothing about delivery",
            )
        }

    @Test
    fun aKotlinFailureAfterSuspensionReachesTheAwaitOnTheGeneratedProxy() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            ProxyLoopHarness.install()

            val outcome = runOnLoopThread("demo.calc.failLater()")

            assertNull(outcome.completerFailure)
            assertEquals(1, outcome.futuresCreated, "this run never reached the delivery path")
            assertEquals("RuntimeError: proxy boom", outcome.error)
        }

    // ------------------------------------------------------------------------------------ harness

    private class Outcome(
        val value: String,
        val error: String,
        val futuresCreated: Int,
        val completerFailure: Throwable?,
    )

    /**
     * Drives [coroutineExpression] on this thread's loop while a Kotlin thread resumes the
     * continuation the fixture parked.
     *
     * The completer waits until a `Future` has actually been created before resuming, for the reason
     * `AsyncUpcallDeliveryTest` records: resuming while `AsyncUpcall.deliver` is still deciding what
     * to return makes `PendingCall.isDone` true early, the fast path is taken, and the run exercises
     * nothing. The counter `ProxyLoopHarness` installs is the cheapest honest signal for that, and
     * it is read under the GIL on a thread CPython has never seen.
     */
    private fun runOnLoopThread(coroutineExpression: String): Outcome {
        var completerFailure: Throwable? = null

        val completer = Thread {
            try {
                var polls = 0
                while (polls++ < 20_000) {
                    if (withGIL { PythonTestFixture.eval("_p['created']").toString() } != "0") break
                    Thread.sleep(1)
                }
                var waited = 0
                while (ProxyFragment.parked == null && waited++ < 20_000) Thread.sleep(1)
                val resume = ProxyFragment.parked
                    ?: throw AssertionError("no suspending Kotlin call ever reached the fixture")
                withGIL { resume.invoke() }
            } catch (t: Throwable) {
                completerFailure = t
            }
        }
        completer.name = "kotlin-proxy-completer"
        completer.start()

        Python3.exec("_drive($coroutineExpression)")
        completer.join(40_000)

        return Outcome(
            value = PythonTestFixture.eval("_p['value']").toString(),
            error = PythonTestFixture.eval("_p['error']").toString(),
            futuresCreated = PythonTestFixture.eval("_p['created']").toString().toInt(),
            completerFailure = completerFailure,
        )
    }
}
