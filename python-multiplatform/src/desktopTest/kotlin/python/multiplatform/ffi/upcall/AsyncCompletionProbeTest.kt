package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.withGIL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The measurement `docs/design/upcall.md` is built on, and the only one of its three
 * candidates that can be tested without writing the mechanism first.
 *
 * The design question every async upcall convention has to answer is not "can Kotlin start work"
 * -- it plainly can -- but **on what thread does the completion arrive, and may that thread touch
 * the interpreter.** Candidate (B) (Kotlin calls a Python callable on completion) and candidate
 * (C) (Kotlin resolves an `asyncio.Future`) both stand or fall on the same two facts, and both are
 * checkable here:
 *
 * 1. A thread **Kotlin** created -- not one CPython created, so nothing has ever given it a thread
 *    state -- can take the GIL and call Python while the interpreter's main thread is parked
 *    inside `loop.run_until_complete`.
 * 2. `loop.call_soon_threadsafe` is reachable through the bound ABI subset. It is not a special
 *    entry point: `PyImport_ImportModule`, `PyObject_GetAttrString` and `PyObject_Call` are all
 *    already in `EmbedAPI`, so (C) needs **no new binding**, which is the thing that was worth
 *    checking rather than assuming.
 *
 * ### Why the timeout is in Python and not in Kotlin
 *
 * If the completion never arrives, `run_until_complete` waits forever and takes the whole suite
 * with it. The guard is `loop.call_later`, scheduled on the loop itself, so a hang becomes a
 * recorded `TIMED_OUT` and an ordinary assertion failure rather than a stuck build. A Kotlin-side
 * timeout could not do this: the thread that would have to notice is the one that is stuck.
 *
 * ### What this deliberately does not show
 *
 * Desktop only. Android's completion thread is a JVM thread ART already knows, so the
 * attach-a-bare-pthread problem `409da6fc` solved for the *upcall* direction does not recur here;
 * that claim is reasoning, not a measurement, and is recorded as such in the design doc. wasm has
 * no threads at all and this test could not be written for it in any form -- which is itself the
 * finding.
 */
class AsyncCompletionProbeTest {

    @Test
    fun aKotlinCreatedThreadCanResolveAnAsyncioFutureTheInterpreterIsWaitingOn() = PythonTestFixture.withInterpreter {
        // A loop and a future built up front, so the Kotlin side holds real references to both
        // before anything starts running. This is the shape a real convention would have too: the
        // future is created while Python is calling in, and resolved later from elsewhere.
        Python3.exec(
            """
            import asyncio, threading
            _probe = {}
            _probe['loop'] = asyncio.new_event_loop()
            _probe['fut'] = _probe['loop'].create_future()
            _probe['main_thread'] = threading.get_ident()

            def _probe_run():
                loop, fut = _probe['loop'], _probe['fut']
                # The guard: if nothing resolves the future, the loop cancels it and this
                # returns instead of hanging the suite.
                loop.call_later(15.0, lambda: fut.done() or fut.cancel())
                try:
                    _probe['value'] = loop.run_until_complete(fut)
                except asyncio.CancelledError:
                    _probe['value'] = 'TIMED_OUT'
                _probe['ran_on'] = threading.get_ident()
                loop.close()
            """.trimIndent(),
        )

        val loop = PythonTestFixture.eval("_probe['loop']")
        val future = PythonTestFixture.eval("_probe['fut']")
        val callSoonThreadsafe = loop.getAttr("call_soon_threadsafe")
        val isRunning = loop.getAttr("is_running")
        val setResult = future.getAttr("set_result")

        var completerThreadName: String? = null
        var completerFailure: Throwable? = null
        var sawLoopRunning = false
        var pollsTaken = 0
        val startedAt = System.nanoTime()

        val completer = Thread {
            try {
                // The ordering matters and is the whole test. Resolving the future *before*
                // `run_until_complete` starts would pass without ever exercising the contended
                // case -- the first version of this did exactly that, in 4 ms. So this waits for
                // the loop to report itself running, and each poll is a full GIL acquisition on a
                // thread nothing has ever attached: it can only succeed because the main thread is
                // inside the selector with the GIL dropped.
                var polls = 0
                while (polls++ < 3000) {
                    if (withGIL { isRunning.invoke() }.toString() == "True") {
                        sawLoopRunning = true
                        break
                    }
                    Thread.sleep(1)
                }
                pollsTaken = polls
                withGIL {
                    // Deliberately not `set_result` directly: a Future may only be resolved from
                    // its own loop's thread, and `call_soon_threadsafe` is the documented hand-off.
                    // This is the exact call a candidate-(C) completion would make.
                    callSoonThreadsafe.invoke(setResult, PyInt.from(42L))
                }
                completerThreadName = Thread.currentThread().name
            } catch (t: Throwable) {
                completerFailure = t
            }
        }
        completer.name = "kotlin-completer"
        completer.start()

        // Parks the interpreter's main thread in the loop until the completion lands.
        Python3.exec("_probe_run()")
        completer.join(30_000)

        println(
            "AsyncCompletionProbe: polls until the loop reported running = $pollsTaken, " +
                "handoff took ${(System.nanoTime() - startedAt) / 1_000_000}ms",
        )

        completerFailure?.let { throw AssertionError("the Kotlin completer thread failed: $it", it) }
        assertEquals("kotlin-completer", completerThreadName, "the completer never finished its GIL scope")
        assertTrue(
            sawLoopRunning,
            "the Kotlin thread never observed the loop running, so the GIL was never contended and " +
                "this run proves nothing about the case the design depends on",
        )

        val value = PythonTestFixture.eval("_probe['value']")
        assertNotEquals(
            "TIMED_OUT",
            value.toString(),
            "call_soon_threadsafe from a Kotlin thread never reached the loop",
        )
        assertEquals(42L, PyInt(value.pointer, borrowed = true).toKotlin())

        // and it really was resolved from the loop's own thread, not from Kotlin's
        val ranOn = PythonTestFixture.eval("_probe['ran_on']").toString()
        assertEquals(PythonTestFixture.eval("_probe['main_thread']").toString(), ranOn)
    }

    @Test
    fun everyApiThisNeedsIsAlreadyBoundSoAnAsyncioConventionAddsNoNewBinding() = PythonTestFixture.withInterpreter {
        // Candidate (C)'s whole surface, reached the way the trampoline would reach it: import a
        // module, read attributes off objects, call them. No new `expect` in `EmbedAPI`.
        val asyncio: PyObject = Python3.import("asyncio")
        for (name in listOf("new_event_loop", "get_event_loop", "Future")) {
            assertTrue(asyncio.getAttrOrNull(name) != null, "asyncio.$name is not reachable")
        }
        val loop = asyncio.getAttr("new_event_loop").invoke()
        try {
            for (name in listOf("call_soon_threadsafe", "create_future", "run_until_complete", "is_closed")) {
                assertTrue(loop.getAttrOrNull(name) != null, "loop.$name is not reachable")
            }
            val future = loop.getAttr("create_future").invoke()
            for (name in listOf("set_result", "set_exception", "done", "result", "cancel")) {
                assertTrue(future.getAttrOrNull(name) != null, "Future.$name is not reachable")
            }
        } finally {
            loop.getAttr("close").invoke()
        }
    }
}
