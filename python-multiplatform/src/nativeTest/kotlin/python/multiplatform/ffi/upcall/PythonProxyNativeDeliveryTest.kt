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
 * `PythonProxyDeliveryTest` (desktopTest) over `pthread_create` instead of `java.lang.Thread`.
 *
 * The same argument `AsyncUpcallNativeDeliveryTest` makes for existing at all applies one layer up:
 * the delivery path is `commonMain` and every target compiles it, but until this file only desktop
 * had ever been observed to `await` a *generated proxy* whose Kotlin body genuinely suspended. The
 * completing thread is a bare POSIX thread ([NativeThread]) rather than a `Worker`, so the runtime
 * has never attached it on its own -- which is the case the design leaves open.
 */
class PythonProxyNativeDeliveryTest {

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
    fun awaitingAGeneratedProxyForASuspendingCallDeliversTheValueFromAThreadTheRuntimeNeverAttached() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            ProxyLoopHarness.install()

            val outcome = runOnLoopThread("demo.calc.doubleLater(21)")

            assertNull(outcome.completerFailure, "the native completer thread failed: ${outcome.completerFailure}")
            assertEquals("None", outcome.error, "the generated proxy raised instead of resolving")
            assertEquals("42", outcome.value)
            assertEquals(
                1,
                outcome.futuresCreated,
                "no Future was built, so this run took the fast path and proves nothing about delivery",
            )
        }

    @Test
    fun awaitingASuspendingInstanceMethodOnAGeneratedClassDeliversTheValueToo() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            ProxyLoopHarness.install()
            Python3.exec("from proxycls import Counter\nc = Counter(3)")

            val outcome = runOnLoopThread("c.fetchLater(4)")

            assertNull(outcome.completerFailure, "the native completer thread failed: ${outcome.completerFailure}")
            assertEquals("None", outcome.error, "the generated proxy raised instead of resolving")
            assertEquals("7", outcome.value)
            assertEquals(1, outcome.futuresCreated, "this run took the fast path and proves nothing about delivery")
        }

    @Test
    fun aKotlinFailureAfterSuspensionReachesTheAwaitOnTheGeneratedProxyHereToo() =
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
     * The same two-condition wait `PythonProxyDeliveryTest` documents: resume only once a `Future`
     * has actually been created, because resuming while `AsyncUpcall.deliver` is still deciding
     * what to return makes `PendingCall.isDone` true early and the run silently exercises the fast
     * path instead of delivery. The counter is read under the GIL, on a thread CPython has never
     * seen.
     */
    private fun runOnLoopThread(coroutineExpression: String): Outcome {
        var completerFailure: Throwable? = null

        val completer = NativeThread {
            try {
                var polls = 0
                while (polls++ < 40_000) {
                    if (withGIL { PythonTestFixture.eval("_p['created']").toString() } != "0") break
                    platform.posix.usleep(500u)
                }
                var waited = 0
                while (ProxyFragment.parked == null && waited++ < 20_000) platform.posix.usleep(1_000u)
                val resume = ProxyFragment.parked
                    ?: error("no suspending Kotlin call ever reached the fixture")
                // Under the GIL, as PendingCall's contract requires -- the resumption runs the
                // listener inline, which is where delivery happens.
                withGIL { resume.invoke() }
            } catch (t: Throwable) {
                completerFailure = t
            }
        }
        completer.start()

        Python3.exec("_drive($coroutineExpression)")
        completer.join()

        return Outcome(
            value = PythonTestFixture.eval("_p['value']").toString(),
            error = PythonTestFixture.eval("_p['error']").toString(),
            futuresCreated = PythonTestFixture.eval("_p['created']").toString().toInt(),
            completerFailure = completerFailure,
        )
    }
}
