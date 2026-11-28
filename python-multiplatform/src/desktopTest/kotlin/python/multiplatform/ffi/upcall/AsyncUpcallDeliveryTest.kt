package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import python.native.ffi.toNativePointer
import python.native.ffi.toRawValue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The delivery half of `docs/design/upcall.md` §5.2: candidate (C) as the surface Python sees,
 * candidate (B) as the mechanism underneath it.
 *
 * `PendingCallTest` already pins the half that starts the coroutine, and `AsyncCompletionProbeTest`
 * already measured the two facts the delivery depends on -- that a Kotlin-created thread can take
 * the GIL while the interpreter is parked in `run_until_complete`, and that `call_soon_threadsafe`
 * needs no binding that is not already in `EmbedAPI`. This is the thing built on top of both: an
 * exposed `suspend fun` that Python can `await`.
 *
 * ### What is deliberately driven from Python and not from Kotlin
 *
 * Two of these tests call the trampoline directly, because the fast path has no event loop in it
 * and calling it from Kotlin is the *stronger* statement -- there is no running loop anywhere in
 * this thread, so a call that reached the `asyncio` path at all would fail with "no running event
 * loop" instead of answering.
 *
 * The suspended path cannot be driven that way. `asyncio.get_running_loop()` answers for the
 * calling thread, and the loop's thread is inside `run_until_complete` for as long as it is
 * running, so the upcall has to arrive *from inside a coroutine*. That is what the `ctypes` bridge
 * (`UpcallEntryBridge.desktop.kt`) is for: `_pm_bound(...)` is the same `(long, long) -> long` stub
 * a generated proxy method would be wired to, reached through `CFUNCTYPE`, which drops the GIL
 * around the call exactly as the real boundary does.
 *
 * ### The Python-side wrapper is not test scaffolding
 *
 * `_await_kotlin` below -- "call it, and `await` the result only if the result is awaitable" -- is
 * the shape the generated Python proxy has to have, and it is what makes the fast path free:
 * `await kotlin_fn(x)` costs a `Future` only when the Kotlin body actually suspended. It is written
 * out here rather than generated because Python-side module generation does not exist yet
 * (`docs/design/upcall.md` §5.4, "Python 쪽 프록시가 `await` 를 이해하는 것").
 */
class AsyncUpcallDeliveryTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(AsyncTrampolineFragment))
        AsyncTrampolineFragment.parked.clear()
        AsyncTrampolineFragment.started.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        AsyncTrampolineFragment.parked.clear()
        AsyncTrampolineFragment.started.clear()
        keepAlive.clear()
    }

    private val keepAlive = mutableListOf<PyObject>()

    /** The argument tuple as the boundary receives it. Held so no cleaner frees it mid-call. */
    private fun tuple(expression: String): Long {
        val built = PythonTestFixture.eval(expression)
        keepAlive.add(built)
        return built.pointer.toRawValue()
    }

    // ------------------------------------------------------------------------------- fast path

    @Test
    fun aSuspendingEntryThatNeverSuspendsHandsBackTheRealValueAndBuildsNoFuture() =
        PythonTestFixture.withInterpreter {
            // No event loop is running on this thread, and that is the point: if the boundary
            // reached `asyncio.get_running_loop()` for a body that had already finished, this
            // would raise instead of answering. `docs/design/upcall.md` §5.2 -- `suspend` is a
            // signature, not a promise to suspend, and the common case must not pay for one.
            val raw = UpcallTrampoline.invoke(UpcallTable.resolve("async.doubleNow").raw, tuple("(21,)"))
            assertNotEquals(0L, raw, "the fast path returned NULL; a Python error was set instead of a value")

            val result = PyObject(raw.toNativePointer()!!, borrowed = false)
            assertEquals("42", result.toString())
            assertEquals(
                "int",
                result.getAttr("__class__").getAttr("__name__").toString(),
                "an already-complete suspending call must marshal like a synchronous one, not as a Future",
            )
        }

    @Test
    fun aSuspendingEntryThatFailsBeforeSuspendingRaisesInPythonRatherThanResolvingAnything() =
        PythonTestFixture.withInterpreter {
            val raw = UpcallTrampoline.invoke(UpcallTable.resolve("async.failNow").raw, tuple("()"))
            assertEquals(0L, raw, "a failure on the fast path has to leave through NULL, like any other")

            // Clears the indicator as it reads it, so the next test does not inherit it.
            val raised = PyException.fromCurrentError()
            assertTrue(
                raised?.message?.contains("early boom") == true,
                "the Kotlin exception did not reach Python's error indicator: ${raised?.message}",
            )
        }

    @Test
    fun aSuspendingEntryThatSuspendsWithNoRunningLoopFailsInsteadOfReturningSomethingUnusable() =
        PythonTestFixture.withInterpreter {
            // The honest failure mode of candidate (C): it requires the application to be async.
            // With nothing running there is nowhere to put the completion, and the call says so
            // rather than handing back a value that will never resolve.
            val raw = UpcallTrampoline.invoke(UpcallTable.resolve("async.doubleLater").raw, tuple("(21,)"))
            assertEquals(0L, raw)

            val raised = PyException.fromCurrentError()
            assertTrue(
                raised?.message?.contains("running event loop") == true,
                "expected a 'no running event loop' failure, got: ${raised?.message}",
            )

            // The coroutine was started before that was knowable, so its resumption is stranded.
            // Draining it here keeps the queue clean for the next test and records the cost.
            assertEquals(1, AsyncTrampolineFragment.parked.size, "the started coroutine is orphaned, by construction")
            AsyncTrampolineFragment.parked.take().invoke()
        }

    // ------------------------------------------------------------------------------ await, for real

    @Test
    fun pythonAwaitsASuspendedKotlinCallAndTheValueArrivesFromAKotlinThread() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("async.doubleLater"), "the suspending entry is not in the table")
            installPythonHarness()

            val outcome = runAwaitOnTheLoopThread("_await_kotlin(21)")

            assertNull(outcome.completerFailure, "the Kotlin completer thread failed: ${outcome.completerFailure}")
            assertTrue(
                outcome.sawLoopRunning,
                "the completer never observed the loop running with a Future outstanding, so this run " +
                    "proves nothing about the case the design depends on",
            )
            assertEquals("None", outcome.error, "the coroutine raised instead of resolving")
            assertEquals(
                "Future",
                outcome.returned,
                "the boundary answered synchronously; this run never exercised the delivery path",
            )
            assertEquals("42", outcome.value)
        }

    @Test
    fun aKotlinExceptionThrownAfterSuspensionSurfacesAsAPythonExceptionAtTheAwait() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("async.failLater"))
            installPythonHarness()

            val outcome = runAwaitOnTheLoopThread("_await_kotlin()")

            assertNull(outcome.completerFailure)
            assertTrue(outcome.sawLoopRunning)
            assertEquals("Future", outcome.returned)
            assertEquals(
                "RuntimeError: late boom",
                outcome.error,
                "a Kotlin failure after suspension has to reach the awaiting coroutine as a raise",
            )
            assertEquals(
                "KotlinThrowable: IllegalStateException: late boom",
                outcome.cause,
                "a Kotlin failure after suspension must preserve the Kotlin throwable as __cause__",
            )
        }

    @Test
    fun aKotlinCancellationExceptionThrownAfterSuspensionSurfacesAsCancelledErrorAtTheAwait() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("async.cancelLater"))
            installPythonHarness()

            val outcome = runAwaitOnTheLoopThread("_await_kotlin()")

            assertNull(outcome.completerFailure)
            assertTrue(outcome.sawLoopRunning)
            assertEquals("Future", outcome.returned)
            assertEquals(
                "CancelledError: late cancel",
                outcome.error,
                "a Kotlin CancellationException after suspension has to reach the awaiting coroutine as CancelledError",
            )
        }

    @Test
    fun aSuspendedStringResultCrossesTheSameWayASynchronousOneWould() =
        PythonTestFixture.withInterpreter {
            // The value delivered to `set_result` goes through the same marshaller the synchronous
            // return path uses, so the tag -- not the Kotlin type, which does not exist at runtime
            // on every target -- is what decides the Python type.
            assertTrue(bindUpcallOrNull("async.greetLater"))
            installPythonHarness()

            val outcome = runAwaitOnTheLoopThread("_await_kotlin('파이썬')")

            assertEquals("None", outcome.error)
            assertEquals("Future", outcome.returned)
            assertEquals("hi 파이썬", outcome.value)
        }

    @Test
    fun theSameAwaitExpressionTakesTheShortcutWhenTheKotlinBodyNeverSuspends() =
        PythonTestFixture.withInterpreter {
            // The point of the shortcut is that it is invisible at the call site: `await` reads the
            // same, and the difference is that no Future was built and no completion was scheduled.
            // Both halves are asserted -- the value, and that what crossed was an `int`.
            assertTrue(bindUpcallOrNull("async.doubleNow"))
            installPythonHarness()

            Python3.exec("_drive(_await_kotlin(21))")

            assertEquals("None", PythonTestFixture.eval("_r['error']").toString())
            assertEquals("42", PythonTestFixture.eval("_r['value']").toString())
            assertEquals(
                "int",
                PythonTestFixture.eval("_r['returned']").toString(),
                "an already-complete call must not be routed through asyncio",
            )
            assertTrue(AsyncTrampolineFragment.parked.isEmpty(), "nothing was ever left outstanding")
        }

    // ----------------------------------------------------------------- delivery loop (#164 item 10)

    @Test
    fun aSuspendingEntryThatFailsWithNoRunningLoopCancelsTheStartedPendingCall() =
        PythonTestFixture.withInterpreter {
            val raw = UpcallTrampoline.invoke(UpcallTable.resolve("async.tickUntilCancelled").raw, tuple("()"))
            assertEquals(0L, raw)

            val raised = PyException.fromCurrentError()
            assertTrue(
                raised?.message?.contains("running event loop") == true,
                "expected a 'no running event loop' failure, got: ${raised?.message}",
            )

            val started = AsyncTrampolineFragment.started.poll(5, TimeUnit.SECONDS)
            assertTrue(started != null, "the coroutine should have been started before the loop check")
            assertTrue(
                started.isCancelled,
                "failing to resolve a delivery loop must cancel the already-started PendingCall",
            )

            // One tick is now enough for the cancelled body's ensureActive() to stop it.
            val resume = AsyncTrampolineFragment.parked.poll(5, TimeUnit.SECONDS)
            assertTrue(resume != null)
            resume.invoke()
            assertTrue(started.isDone, "the cancelled PendingCall should finish after one tick")
            assertTrue(started.failure is CancellationException)
        }

    @Test
    fun setDeliveryLoopAndUseDeliveryLoopValidateLoopAttributesAndOrderPriority() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.settleFunction()
            Python3.exec(
                """
                import asyncio, contextvars, python_multiplatform as _pm
                _dl = {}
                _l1 = asyncio.new_event_loop()
                _l2 = asyncio.new_event_loop()
                try:
                    _dl['initial'] = _pm.get_delivery_loop() is None
                    _prev = _pm.set_delivery_loop(_l1)
                    _dl['prev_none'] = _prev is None
                    _dl['after_set'] = _pm.get_delivery_loop() is _l1
                    with _pm.use_delivery_loop(_l2):
                        _dl['in_with'] = _pm.get_delivery_loop() is _l2
                        _ctx = contextvars.copy_context()
                    _dl['after_with'] = _pm.get_delivery_loop() is _l1
                    _dl['in_copied_ctx'] = _ctx.run(_pm.get_delivery_loop) is _l2
                    _prev2 = _pm.set_delivery_loop(None)
                    _dl['prev2_l1'] = _prev2 is _l1
                    _dl['cleared'] = _pm.get_delivery_loop() is None
                    try:
                        _pm.set_delivery_loop(object())
                        _dl['bad_set'] = 'accepted'
                    except TypeError:
                        _dl['bad_set'] = 'TypeError'
                    try:
                        with _pm.use_delivery_loop(object()):
                            pass
                        _dl['bad_use'] = 'accepted'
                    except TypeError:
                        _dl['bad_use'] = 'TypeError'
                finally:
                    _pm.set_delivery_loop(None)
                    _l1.close()
                    _l2.close()
                """.trimIndent(),
            )
            assertEquals("True", PythonTestFixture.eval("_dl['initial']").toString())
            assertEquals("True", PythonTestFixture.eval("_dl['prev_none']").toString())
            assertEquals("True", PythonTestFixture.eval("_dl['after_set']").toString())
            assertEquals("True", PythonTestFixture.eval("_dl['in_with']").toString())
            assertEquals("True", PythonTestFixture.eval("_dl['after_with']").toString())
            assertEquals("True", PythonTestFixture.eval("_dl['in_copied_ctx']").toString())
            assertEquals("True", PythonTestFixture.eval("_dl['prev2_l1']").toString())
            assertEquals("True", PythonTestFixture.eval("_dl['cleared']").toString())
            assertEquals("TypeError", PythonTestFixture.eval("_dl['bad_set']").toString())
            assertEquals("TypeError", PythonTestFixture.eval("_dl['bad_use']").toString())
        }

    @Test
    fun aClosedConfiguredDeliveryLoopCancelsTheStartedPendingCallAndRaisesRuntimeError() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.settleFunction()
            Python3.exec(
                """
                import asyncio, python_multiplatform as _pm
                _closed_loop = asyncio.new_event_loop()
                _closed_loop.close()
                _pm.set_delivery_loop(_closed_loop)
                """.trimIndent(),
            )
            try {
                val raw = UpcallTrampoline.invoke(
                    UpcallTable.resolve("async.tickUntilCancelled").raw,
                    tuple("()"),
                )
                assertEquals(0L, raw)
                val raised = PyException.fromCurrentError()
                assertTrue(
                    raised?.message?.contains("closed") == true,
                    "expected a closed delivery loop failure, got: ${raised?.message}",
                )
                val started = AsyncTrampolineFragment.started.poll(5, TimeUnit.SECONDS)
                assertTrue(started != null)
                assertTrue(
                    started.isCancelled,
                    "a closed delivery loop must cancel the already-started PendingCall",
                )
                AsyncTrampolineFragment.parked.poll(5, TimeUnit.SECONDS)?.invoke()
            } finally {
                Python3.exec("import python_multiplatform as _pm; _pm.set_delivery_loop(None)")
            }
        }

    @Test
    fun aCoroutineSteppedWithNoRunningLoopOnItsThreadYieldsAFutureCreatedOnTheConfiguredDeliveryLoop() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("async.doubleLater"))
            PythonProxySource.settleFunction()
            Python3.exec(
                """
                import asyncio, concurrent.futures, contextvars, threading, python_multiplatform as _pm

                _step = {}
                _target_loop = asyncio.new_event_loop()
                _loop_ready = threading.Event()
                _step['create_threads'] = []

                _orig_create_future = _target_loop.create_future
                def _tracked_create_future():
                    _step['create_threads'].append(threading.get_ident())
                    return _orig_create_future()
                _target_loop.create_future = _tracked_create_future

                def _run_target_loop():
                    _step['loop_tid'] = threading.get_ident()
                    asyncio.set_event_loop(_target_loop)
                    _target_loop.call_soon(_loop_ready.set)
                    _target_loop.run_forever()

                _loop_thread = threading.Thread(target=_run_target_loop, name='target-delivery-loop', daemon=True)
                _loop_thread.start()
                _loop_ready.wait(timeout=10.0)

                async def _kotlin_mode_coro(x):
                    r = _pm_bound(x)
                    if hasattr(r, '__await__'):
                        return await r
                    return r

                with _pm.use_delivery_loop(_target_loop):
                    _task_ctx = contextvars.copy_context()

                _coro = _kotlin_mode_coro(21)
                # Step 1 on this thread (no running loop here!), inside the task's copied Context.
                _step['stepper_tid'] = threading.get_ident()
                _yielded = _task_ctx.run(_coro.send, None)
                _step['yielded_type'] = type(_yielded).__name__
                _step['same_loop'] = _yielded.get_loop() is _target_loop
                """.trimIndent(),
            )

            try {
                assertEquals("Future", PythonTestFixture.eval("_step['yielded_type']").toString())
                assertEquals("True", PythonTestFixture.eval("_step['same_loop']").toString())
                // CPython thread-safety requirement: create_future must have run on _target_loop's
                // thread via call_soon_threadsafe, never on the non-loop stepper thread.
                assertEquals(
                    "True",
                    PythonTestFixture.eval("_step['create_threads'] == [_step['loop_tid']] and _step['loop_tid'] != _step['stepper_tid']").toString(),
                    "create_future() must run on the target loop thread, not the stepper thread",
                )

                // Now resume the Kotlin coroutine from a separate Kotlin completer thread and
                // let the Kotlin-mode driver subscribe to the yielded Future on _target_loop.
                val resume = AsyncTrampolineFragment.parked.poll(10, TimeUnit.SECONDS)
                    ?: throw AssertionError("no suspending Kotlin call reached the fixture")
                val completer = Thread {
                    withGIL { resume.invoke() }
                }
                completer.start()
                completer.join(10_000)

                Python3.exec(
                    """
                    _bridge = concurrent.futures.Future()
                    def _subscribe():
                        def _on_done(f):
                            if f.cancelled():
                                _bridge.cancel()
                            elif f.exception() is not None:
                                _bridge.set_exception(f.exception())
                            else:
                                _bridge.set_result(f.result())
                        _yielded.add_done_callback(_on_done)
                    _target_loop.call_soon_threadsafe(_subscribe)
                    _resolved_val = _bridge.result(timeout=10.0)
                    try:
                        _task_ctx.run(_coro.send, _resolved_val)
                        _step['final'] = 'did not stop'
                    except StopIteration as _stop:
                        _step['final'] = _stop.value
                    """.trimIndent(),
                )
                assertEquals("42", PythonTestFixture.eval("_step['final']").toString())
            } finally {
                Python3.exec(
                    """
                    _target_loop.call_soon_threadsafe(_target_loop.stop)
                    _loop_thread.join(timeout=5.0)
                    _target_loop.close()
                    """.trimIndent(),
                )
            }
        }

    @Test
    fun aRunningLoopOnTheCallingThreadWinsOverAnyConfiguredDeliveryLoop() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("async.doubleLater"))
            installPythonHarness()
            Python3.exec(
                """
                import asyncio, python_multiplatform as _pm
                _other_loop = asyncio.new_event_loop()
                _pm.set_delivery_loop(_other_loop)
                """.trimIndent(),
            )
            try {
                // _r['loop'] is the running loop on the calling thread inside _drive(...); even with
                // _other_loop configured via set_delivery_loop and use_delivery_loop, the running
                // loop must win so `await` on the running loop does not fail with a cross-loop Future.
                val outcome = runAwaitOnTheLoopThread("_await_kotlin(21)")
                assertNull(outcome.completerFailure)
                assertTrue(outcome.sawLoopRunning)
                assertEquals("Future", outcome.returned)
                assertEquals("None", outcome.error)
                assertEquals("42", outcome.value)
            } finally {
                Python3.exec(
                    """
                    import python_multiplatform as _pm
                    _pm.set_delivery_loop(None)
                    _other_loop.close()
                    """.trimIndent(),
                )
            }
        }

    // ------------------------------------------------------------------------------------ harness

    private class AwaitOutcome(
        val value: String,
        val error: String,
        val cause: String,
        /** `type(...).__name__` of what the boundary actually handed back, before any `await`. */
        val returned: String,
        val sawLoopRunning: Boolean,
        val completerFailure: Throwable?,
    )

    /**
     * The Python half: a loop built up front so Kotlin can hold it before anything runs, and the
     * proxy shape a generated `async def` would have.
     */
    private fun installPythonHarness() {
        Python3.exec(
            """
            import asyncio
            _r = {}
            _r['loop'] = asyncio.new_event_loop()
            _r['returned'] = None

            def _await_kotlin(*args):
                # What the generated Python proxy for an exposed `suspend fun` has to do: the
                # boundary hands back the real value when the Kotlin body never suspended, and an
                # asyncio.Future when it did. Only the second case costs an await.
                async def _run():
                    r = _pm_bound(*args)
                    # Recorded so the Kotlin side can tell which of the two paths was taken,
                    # rather than inferring it from a value that both paths could produce.
                    _r['returned'] = type(r).__name__
                    if hasattr(r, '__await__'):
                        # wait_for is the timeout guard: if the completion never lands this
                        # raises instead of taking the whole suite with it.
                        return await asyncio.wait_for(r, 20.0)
                    return r
                return _run()

            def _drive(coro):
                loop = _r['loop']
                _r['value'] = None
                _r['error'] = None
                _r['cause'] = None
                try:
                    _r['value'] = loop.run_until_complete(coro)
                except BaseException as e:
                    _r['error'] = type(e).__name__ + ': ' + str(e)
                    if e.__cause__ is not None:
                        _r['cause'] = type(e.__cause__).__name__ + ': ' + str(e.__cause__)
            """.trimIndent(),
        )
    }

    /**
     * Runs [coroutineExpression] to completion on this thread's event loop while a Kotlin thread
     * delivers the resumption the suspending fixture parked.
     *
     * ### Why the completer waits for two things and not one
     *
     * Waiting only for `loop.is_running()` is not enough, and the first version of this test found
     * that out by failing: the completer got the GIL the instant `CFUNCTYPE` dropped it *for the
     * upcall itself*, saw the loop running, and resumed the continuation while `AsyncUpcall.deliver`
     * was still deciding what to return. `PendingCall.isDone` was then already true and the boundary
     * took the fast path -- which is not wrong (the coroutine really had finished) but means the run
     * never touched the delivery path it was written to exercise.
     *
     * So the second condition is that Python has already *received* whatever the boundary handed
     * back (`_r['returned']` is set), which cannot happen before the `Future` exists and the
     * listener is attached. Both conditions are read under one GIL acquisition on a thread CPython
     * has never seen, which is also the contended-GIL observation `AsyncCompletionProbeTest`
     * established as the thing worth asserting.
     *
     * ### And why the resumption itself happens inside a GIL scope
     *
     * `PendingCall`'s contract (`commonMain/README.md`, and its own class doc) is that every
     * mutation happens on a thread holding the GIL -- that is what publishes `isDone` and `value` to
     * whoever reads them next. Resuming outside one, as the first version did, leaves the ordering
     * to whatever the GIL mutex happened to do. Real integrations owe the same discipline.
     */
    private fun runAwaitOnTheLoopThread(coroutineExpression: String): AwaitOutcome {
        val loop = PythonTestFixture.eval("_r['loop']")
        val isRunning = loop.getAttr("is_running")

        var sawLoopRunning = false
        var completerFailure: Throwable? = null

        val completer = Thread {
            try {
                var polls = 0
                while (polls++ < 5000) {
                    val handedOff = withGIL {
                        isRunning.invoke().toString() == "True" &&
                            PythonTestFixture.eval("_r.get('returned')").toString() != "None"
                    }
                    if (handedOff) {
                        sawLoopRunning = true
                        break
                    }
                    Thread.sleep(1)
                }
                val resume = AsyncTrampolineFragment.parked.poll(20, TimeUnit.SECONDS)
                    ?: throw AssertionError("no suspending Kotlin call ever reached the fixture")
                withGIL { resume.invoke() }
            } catch (t: Throwable) {
                completerFailure = t
            }
        }
        completer.name = "kotlin-async-completer"
        completer.start()

        Python3.exec("_drive($coroutineExpression)")
        completer.join(40_000)

        return AwaitOutcome(
            value = PythonTestFixture.eval("_r['value']").toString(),
            error = PythonTestFixture.eval("_r['error']").toString(),
            cause = PythonTestFixture.eval("_r['cause']").toString(),
            returned = PythonTestFixture.eval("_r['returned']").toString(),
            sawLoopRunning = sawLoopRunning,
            completerFailure = completerFailure,
        )
    }
}

/**
 * Entries written exactly the way the KSP generator emits them for a `suspend fun`: the body is
 * `PendingCall.start { <the real call> }` and the entry carries `isSuspend = true`, while
 * `returnType` stays the *unwrapped* declared type so the fast path can marshal a real value.
 *
 * The suspending fixtures park their continuation in [parked] rather than using a dispatcher, for
 * the reason `PendingCallTest` gives: it needs no `kotlinx.coroutines` dependency, and it makes
 * "suspended" and "resumed" two separate, orderable events instead of a scheduling race.
 */
object AsyncTrampolineFragment : FunctionTableFragment {

    override val moduleName: String = "test_async_upcall"

    /** Resumptions waiting for the completer thread; one per outstanding suspended call. */
    val parked = LinkedBlockingQueue<() -> Unit>()

    /**
     * The [PendingCall] each `async.tickUntilCancelled` invocation produced.
     *
     * The trampoline consumes what a generated body returns, so a test that wants to read the
     * Kotlin side of a call in flight -- `isCancelled` while it is still suspended, which is the
     * whole subject of `AsyncUpcallEarlyCancellationTest` -- has no other way to reach it.
     */
    val started = LinkedBlockingQueue<PendingCall>()

    private suspend fun doubleLater(x: Long): Long = suspendCoroutine { c -> parked.put { c.resume(x * 2) } }

    private suspend fun greetLater(name: String): String =
        suspendCoroutine { c -> parked.put { c.resume("hi $name") } }

    private suspend fun failLater(): Long =
        suspendCoroutine { c -> parked.put { c.resumeWithException(IllegalStateException("late boom")) } }

    private suspend fun cancelLater(): Long =
        suspendCoroutine { c -> parked.put { c.resumeWithException(CancellationException("late cancel")) } }

    /** Reaches no suspension point at all, so it is complete before `start` returns. */
    private suspend fun doubleNow(x: Long): Long = x * 2

    private suspend fun failNow(): Long = throw IllegalStateException("early boom")

    /**
     * A cooperating body: one suspension per tick, and [ensureActive] immediately after each
     * resumption.
     *
     * Written this way so "the coroutine learned it was cancelled" and "the coroutine finished"
     * are two separately observable events. A body that only checked at the end could not tell an
     * early notice from a late one, which is exactly the distinction under test. The tick ceiling
     * is a runaway guard: a run that never observes cancellation ends rather than hanging the
     * suite, and it ends with a value the assertions reject.
     */
    private suspend fun tickUntilCancelled(): Long {
        var ticks = 0L
        while (ticks < TICK_CEILING) {
            suspendCoroutine { c: kotlin.coroutines.Continuation<Unit> -> parked.put { c.resume(Unit) } }
            ticks++
            ensureActive()
        }
        return ticks
    }

    private const val TICK_CEILING = 1000L

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "async.doubleLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleLater(args[0] as Long) } },
        ExposedCallable(
            name = "async.greetLater",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
            isSuspend = true,
        ) { args -> PendingCall.start { greetLater(args[0] as String) } },
        ExposedCallable(
            name = "async.failLater",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { failLater() } },
        ExposedCallable(
            name = "async.cancelLater",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { cancelLater() } },
        ExposedCallable(
            name = "async.doubleNow",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleNow(args[0] as Long) } },
        ExposedCallable(
            name = "async.failNow",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { failNow() } },
        ExposedCallable(
            name = "async.tickUntilCancelled",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { tickUntilCancelled() }.also { started.put(it) } },
    )
}
