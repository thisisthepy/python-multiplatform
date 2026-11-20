package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.upcall.PendingCall
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.multiplatform.reflection.PlatformMutex
import python.multiplatform.reflection.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.suspendCoroutine

/**
 * One Python awaitable submitted to an `asyncio` loop, and the Kotlin side of waiting for it
 * (SPEC U-5d; issue #164, item 2).
 *
 * This is the callback primitive under [await]: [submit] hands the awaitable to the delivery loop
 * once and [onComplete] is called once, on the loop thread, when the task it became has finished.
 * [cancel] hands one `Task.cancel()` to that loop. It is public, and callback-shaped rather than
 * `suspend`-shaped, so that a wrapper which does have `kotlinx.coroutines` can put a
 * `suspendCancellableCoroutine` over it and route `Job` cancellation to the task through
 * `invokeOnCancellation { pyAwait.cancel() }`; this library keeps no `kotlinx` dependency and its own
 * [await] uses `kotlin.coroutines.suspendCoroutine`, with [PendingCall] as its one cancellation source.
 *
 * ### The path, and who holds what
 *
 * [submit] runs under [withGIL] on the calling thread, whichever thread that is: it checks the
 * receiver has `__await__`, registers this object in [HandleTable], and calls the binding module's
 * `_submit_suspend_awaitable` (SPEC U-5a's, shared), which resolves the loop in U-5's order and queues
 * one `_schedule` callback on it. The task is created on the loop thread, inside `_schedule`, never on
 * the submitting one. Python's closure holds the awaitable from the submit on and the task holds it
 * after `_schedule`; nothing here takes an extra reference to it. The task's done callback calls
 * [PythonCallables.completeSuspendCall] through the upcall table, which lands in [complete] on the
 * loop thread with the GIL held, inside the trampoline's attached frame.
 *
 * ### Exactly once
 *
 * [finish] is the single way out: a `done` flag under [lock] lets the first caller through, whether
 * that is the done callback or the closed-loop branch of [cancel], and the handle release that
 * follows is generational, so the release [PythonCallables.completeSuspendCall] already performed
 * is a no-op rather than someone else's slot. [cancel] flips `cancelRequested` under the same lock
 * before touching Python, so the loop is handed at most one cancel per await.
 */
class PyAwait private constructor(
    private val onComplete: (Result<PyObject>) -> Unit,
) {
    private val lock = PlatformMutex()
    private var done = false
    private var cancelRequested = false
    private var handle: ObjectReference = ObjectReference.NONE
    private var cancelDisposer: (() -> Unit)? = null

    /** True once [onComplete] has run (or is running): the task finished, or the loop was closed. */
    val isDone: Boolean get() = lock.withLock { done }

    /** True once [cancel] has been asked for, whether or not the task has reacted yet. */
    val isCancellationRequested: Boolean get() = lock.withLock { cancelRequested }

    /**
     * Asks the loop to cancel the task, at most once.
     *
     * Non-blocking: the Python side only queues `_cancel_suspend_on_loop` with
     * `call_soon_threadsafe`, after the `_schedule` the submit queued (one FIFO), so a cancel that
     * lands before the task exists cancels the task the loop is about to create. The task's done
     * callback then resumes with [CancellationException] (or with a value, if the coroutine caught
     * `CancelledError` and returned one). When the target loop is already closed nothing on it
     * will run again, so this completes the await itself with [CancellationException], on the
     * calling thread.
     *
     * Takes [withGIL] (an attach on any thread; re-entrant on one that holds it, which every
     * [PendingCall.cancel] path does). May be called from any thread.
     *
     * @return `true` if this call was in flight and the cancel was handed on (or applied locally
     *   because the loop is closed); `false` if the await is already done, or a cancel was already
     *   requested.
     */
    fun cancel(): Boolean {
        val raw = lock.withLock {
            if (done || cancelRequested) return false
            cancelRequested = true
            handle.raw
        }
        val verdict = withGIL { cancelOnLoop(raw) }
        when (verdict) {
            CANCEL_QUEUED -> return true
            CANCEL_LOOP_CLOSED -> {
                finish(Result.failure(CancellationException("the delivery loop is closed")))
                return true
            }
            else -> return false
        }
    }

    /**
     * Keeps the disposer of the [PendingCall.onCancelled] registration [await] made, and runs it as
     * soon as [finish] has run, or right away if it already has. Mirrors
     * [PendingSuspendInvocation.attachCancelListenerDisposer]: the registration is made on the
     * submitting thread after the submit, [finish] runs on the loop thread, and either can come first.
     */
    internal fun attachCancelDisposer(dispose: () -> Unit) {
        val runNow = lock.withLock {
            if (done) {
                true
            } else {
                cancelDisposer = dispose
                false
            }
        }
        if (runNow) dispose()
    }

    /**
     * The done callback's arrival: on the loop thread, GIL held, through
     * [PythonCallables.completeSuspendCall]. [payload] is the task's result (a wrapper holding its
     * own reference; `null` for `None`) or the exception instance it raised.
     */
    internal fun complete(ok: Boolean, payload: PyObject?) {
        val result: Result<PyObject> = if (ok) {
            // The trampoline spells `None` as null; the caller asked for the object.
            Result.success(payload ?: PyObject(PyNone.get().pointer, borrowed = true))
        } else if (payload == null) {
            Result.failure(IllegalStateException("the Python awaitable failed without an exception"))
        } else {
            val pyExc = try {
                // Raise-and-capture, as U-5a does, so type, value, traceback, cause and context are
                // populated the same way as for any other Python exception crossing into Kotlin.
                // `PyErr_SetRaisedException` steals a reference; `payload` keeps its own.
                python.native.ffi.Py_IncRef(payload.pointer)
                python.native.ffi.PyErr_SetRaisedException(payload.pointer)
                PyException.fromCurrentError() ?: PyException(payload.toString())
            } catch (_: Throwable) {
                PyException(payload.toString())
            }
            Result.failure(pyExc.toKotlinException())
        }
        finish(result)
    }

    private fun finish(result: Result<PyObject>) {
        val (dispose, raw) = lock.withLock {
            if (done) return
            done = true
            val d = cancelDisposer
            cancelDisposer = null
            d to handle
        }
        // Generational: a release the completion entry already performed is a no-op.
        if (raw.isValid) HandleTable.release(raw)
        // Before resuming: the continuation may complete or cancel its call inline, and a listener
        // for an await that is over has nothing left to cancel.
        dispose?.invoke()
        onComplete(result)
    }

    companion object {
        /** `_cancel_suspend_awaitable`'s answers. */
        private const val CANCEL_QUEUED = 1L
        private const val CANCEL_NOT_IN_FLIGHT = 0L
        private const val CANCEL_LOOP_CLOSED = -1L

        /**
         * Submits [awaitable] to the delivery loop and returns once its first step is queued there.
         *
         * [onComplete] runs exactly once, on the loop thread with the GIL held (or on the thread
         * that called [cancel], when the loop was already closed), with the task's result as a new
         * owned [PyObject], the [PyException] it raised, or a [CancellationException] caused by its
         * `CancelledError`. The loop is the one running on the calling thread, else
         * `use_delivery_loop`'s, else `set_delivery_loop`'s (SPEC U-5).
         *
         * @throws IllegalArgumentException if [awaitable] has no `__await__`.
         * @throws IllegalStateException if the binding module is not installed
         *   ([PythonxAdapter.install]), or no loop is available, or the configured one is closed.
         *   In every refusal nothing was scheduled and nothing was registered.
         */
        fun submit(awaitable: PyObject, onComplete: (Result<PyObject>) -> Unit): PyAwait {
            val pending = PyAwait(onComplete)
            withGIL {
                val awaitMethod = awaitable.getAttrOrNull("__await__")
                    ?: throw IllegalArgumentException(
                        "await() needs an awaitable (an object with __await__): a coroutine, an " +
                            "asyncio.Future or Task; got ${awaitable.Type.name}",
                    )
                awaitMethod.close()
                val binding = try {
                    Python3.import(PythonxAdapter.MODULE_NAME)
                } catch (e: PyException) {
                    throw IllegalStateException(
                        "await() needs the binding module ${PythonxAdapter.MODULE_NAME}, which carries " +
                            "the completion back to Kotlin; call PythonxAdapter.install() first",
                        e,
                    )
                }
                val submit = binding.getAttr("_submit_suspend_awaitable")
                val handle = HandleTable.register(pending)
                pending.lock.withLock { pending.handle = handle }
                try {
                    submit.invoke(awaitable, PyInt.from(handle.raw)).close()
                } catch (t: Throwable) {
                    // Nothing was queued (the Python side raises only before `call_soon`, or when
                    // `call_soon` itself refused), so no callback will ever look this handle up.
                    HandleTable.release(handle)
                    pending.lock.withLock { pending.handle = ObjectReference.NONE }
                    throw mapSubmitFailure(t)
                }
            }
            return pending
        }

        /** The loop refusals (`RuntimeError`) become [IllegalStateException] with the Python error as cause. */
        private fun mapSubmitFailure(t: Throwable): Throwable {
            if (t is PyException && t.type?.name == "RuntimeError") {
                return IllegalStateException(t.errMsg, t)
            }
            return t
        }

        /** `_cancel_suspend_awaitable(handle)`; caller holds the GIL. Only queues; never cancels on the calling thread. */
        private fun cancelOnLoop(raw: Long): Long {
            val binding = Python3.import(PythonxAdapter.MODULE_NAME)
            val answer = binding.getAttr("_cancel_suspend_awaitable").invoke(PyInt.from(raw))
            return try {
                answer.toString().toLongOrNull() ?: CANCEL_NOT_IN_FLIGHT
            } finally {
                answer.close()
            }
        }
    }
}

/**
 * Suspends until this awaitable (a coroutine, an `asyncio.Future` or `Task`, anything with
 * `__await__`) has run to completion on an `asyncio` loop, and returns its result as a new owned
 * [PyObject] (SPEC U-5d; issue #164, item 2).
 *
 * Built on [PyAwait.submit]; see there for which loop, which thread resumes, and what is thrown. The
 * GIL is never held across the suspension: the submit's [withGIL] scope closes before
 * `suspendCoroutine` parks. The continuation resumes on the loop thread, inside the done callback,
 * so the code after this call blocks the loop while it runs unless a `ContinuationInterceptor` in the
 * coroutine's context moves it elsewhere.
 *
 * Cancellation comes from the [PendingCall] this coroutine runs under, if any: cancelling it
 * (Python's `Future.cancel()` on an exposed `suspend fun`, or [PendingCall.cancel] from Kotlin on a
 * call started with [PendingCall.start]) cancels the task once, and this resumes with
 * [CancellationException] caused by the `CancelledError`. A coroutine with no [PendingCall] has no
 * cancellation source here; a `kotlinx` wrapper builds its own over [PyAwait.submit].
 *
 * A refusal (not awaitable, no binding module, no loop, closed loop) is thrown from here before the
 * coroutine suspends; nothing was scheduled.
 */
suspend fun PyObject.await(): PyObject {
    // Read before suspending: the call's cancellation is forwarded to the task below.
    val call = coroutineContext[PendingCall]
    return suspendCoroutine { cont ->
        // A throw from `submit` leaves this block without a resume, and `suspendCoroutine` rethrows
        // it to the caller: the coroutine continues with the exception, never parked.
        val pending = PyAwait.submit(this) { result -> cont.resumeWith(result) }
        if (call != null) {
            // Registered after a successful submit only. A call that is already cancelled runs the
            // action inline, which cancels the task the loop is about to start. Disposed when the
            // await completes, so a body that awaits in a loop does not accumulate one per step.
            pending.attachCancelDisposer(call.onCancelled { pending.cancel() })
        }
    }
}
