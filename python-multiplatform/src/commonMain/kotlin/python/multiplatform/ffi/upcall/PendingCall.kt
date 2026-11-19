package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.PlatformMutex
import python.multiplatform.reflection.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.startCoroutine

/**
 * One Kotlin coroutine started from a frame that had to return without it, and the place its
 * outcome waits until something asks for it.
 *
 * This is the half of `docs/design/upcall.md` that all three candidate conventions share.
 * The problem the doc states is structural: CPython calls a C function pointer, that C frame has
 * to hand back a `PyObject *` before it returns, and a suspension has nothing to hand back.
 * `runBlocking` "solves" it by re-seizing the very thread that would have been freed -- on Android
 * that thread is one CPython created, so blocking it stops the interpreter that is waiting on the
 * answer. What is left is to return *something else* and deliver the value later, and every way of
 * doing that begins here.
 *
 * ### What this deliberately does not do
 *
 * It does not deliver anything to Python. [onCompleted] is the single seam a delivery convention
 * attaches to, and [AsyncUpcall] is the one that does -- it resolves an `asyncio.Future` from the
 * completing thread. Keeping that out of here is not indecision: this half is pure Kotlin, so it
 * is identical on all five targets and testable with no interpreter at all (`PendingCallTest` is
 * in `commonTest` for that reason), while the delivery half is entirely C API calls.
 *
 * ### No `kotlinx.coroutines`
 *
 * `startCoroutine` and [Continuation] are `kotlin-stdlib`, common source set, every target. The
 * library has no coroutines dependency and must not grow one for this: the `suspend fun` being
 * exposed is the *user's*, and whatever dispatcher it uses is already on their classpath. Starting
 * it takes nothing more than the stdlib.
 *
 * ### Dispatcher and fast path
 *
 * When no `ContinuationInterceptor` is present in the caller-supplied [CoroutineContext], the
 * body runs on the calling thread until it actually suspends: a `suspend fun` that never reaches
 * a real suspension point is **already complete when [start] returns**, and the boundary hands
 * Python the real value with no async convention involved. When a `ContinuationInterceptor` is
 * supplied (SPEC U-5b), `startCoroutine` dispatches even the first slice onto the interceptor's
 * thread without the GIL, so [start] returns with [isDone] false and [complete] runs on that
 * thread.
 *
 * ### Threading
 *
 * All four state transitions ([complete], [onCompleted], [cancel], [onCancelled]) and every
 * state read are synchronised under [PlatformMutex]. Inside the lock, each transition atomically
 * decides whether to deliver inline or register a listener; listeners are always invoked outside
 * the lock so they cannot deadlock with re-entrant calls or concurrent threads. This holds both
 * when a `ContinuationInterceptor` resumes on a non-GIL thread and on free-threaded (3.15t)
 * builds where `withGIL` is not mutual exclusion.
 *
 * Nothing here allocates a handle. A `PendingCall` is an ordinary Kotlin object, so
 * [python.multiplatform.reflection.HandleTable] already gives it a generational handle and
 * [UpcallTrampoline.releaseObject] already gives Python the way to drop it.
 */
class PendingCall private constructor() : CoroutineContext.Element {

    /**
     * Makes this reachable from inside the coroutine as `coroutineContext[PendingCall]`, which is
     * what [ensureActive] and [currentPendingCall] read. Modelled on `kotlinx.coroutines`' `Job`,
     * whose companion is its own key for the same reason.
     */
    override val key: CoroutineContext.Key<PendingCall> get() = PendingCall

    private val mutex = PlatformMutex()

    private var done: Boolean = false
    private var resultValue: Any? = null
    private var resultFailure: Throwable? = null
    private var deliveryErr: Throwable? = null
    private var cancellationErr: Throwable? = null
    private var cancelled: Boolean = false

    /** True once the coroutine has finished, whether with a value or with a [failure]. */
    val isDone: Boolean get() = mutex.withLock { done }

    /** The result, or `null` -- which is also a legitimate result. Read [isDone] first. */
    val value: Any? get() = mutex.withLock { resultValue }

    /** What the coroutine threw, or `null`. Never rethrown from here; see [start]. */
    val failure: Throwable? get() = mutex.withLock { resultFailure }

    /**
     * What the [onCompleted] listener threw, if it threw.
     *
     * Kept rather than propagated for the same reason [failure] is: the listener runs on the
     * completing thread, which on the delivery path has C above it, and a Kotlin exception
     * crossing that frame terminates the process. A convention that cares can read this.
     */
    val deliveryFailure: Throwable? get() = mutex.withLock { deliveryErr }

    /**
     * First exception thrown by an [onCancelled] listener, if any (SPEC U-5b; #164 item 1).
     * Recorded here rather than propagated so a throwing cancellation listener never escapes into
     * C or prevents remaining listeners from running.
     */
    val cancellationFailure: Throwable? get() = mutex.withLock { cancellationErr }

    /**
     * True once someone on the Python side has stopped waiting for this -- see [cancel].
     *
     * Independent of [isDone]: a cancelled call is normally still running, and a call that
     * completes after being cancelled is both done and cancelled.
     */
    val isCancelled: Boolean get() = mutex.withLock { cancelled }

    private var listener: ((PendingCall) -> Unit)? = null

    private var cancelListeners: MutableList<() -> Unit>? = null

    /**
     * Records that whoever was waiting for this call has stopped, and returns whether that was news.
     *
     * ### What this is not
     *
     * **It does not stop the coroutine.** It cannot, and the reason is structural rather than a
     * missing feature. [start] hands its own [Continuation] to `startCoroutine` as the coroutine's
     * *completion*, which is resumed exactly once, when the whole body is finished. The
     * continuation that represents the body's current suspension point is a different object
     * entirely, and it belongs to whoever created the suspension -- the user's
     * `suspendCoroutine`, or their dispatcher, or `kotlinx.coroutines` machinery this library has
     * no dependency on. Nothing here ever sees it, and the stdlib offers no way to reach it.
     *
     * Resuming a continuation twice is undefined, so there is no back door either. Forcible
     * cancellation is what a `Job` tree is *for*, and building one is `kotlinx.coroutines`'
     * job; `docs/design/upcall.md` §5.4 states the no-dependency rule this obeys.
     *
     * ### What it is
     *
     * A cancellation flag published where a *cooperating* body can read it. Because the coroutine
     * runs with this object as its [CoroutineContext], the body reaches it as
     * `coroutineContext[PendingCall]`, and [ensureActive] is the one-line form:
     *
     *     suspend fun slowSum(n: Long): Long {
     *         var total = 0L
     *         for (i in 0 until n) { ensureActive(); total += step(i) }
     *         return total
     *     }
     *
     * That is the same bargain `kotlinx.coroutines` makes -- cancellation is cooperative there too,
     * and a body that never suspends and never checks is uncancellable there as well. The
     * difference is only that `kotlinx.coroutines`' own suspending functions do the checking for
     * you, and there are none here.
     *
     * A body that does not cooperate keeps running to completion, and its outcome is simply
     * dropped: [AsyncUpcall] refuses to deliver a result to a `Future` that is already settled.
     * That is the guarantee this half is actually responsible for.
     *
     * ### When Kotlin hears about it
     *
     * At the moment Python calls `Future.cancel()`, near enough: the `Future` the slow path hands
     * out carries a handle on this object, and its `add_done_callback` routes back through
     * [UpcallTrampoline.cancelCall] on the loop's next turn (`AsyncUpcall.armCancellationNotice`).
     * Before that existed, this flag was only ever set by the delivery path -- i.e. when the
     * coroutine had already finished, which is exactly when the news stops being worth anything.
     *
     * @return `true` if this call was live and is now cancelled; `false` if it had already
     *   completed (there was nothing left to stop) or was already cancelled.
     */
    fun cancel(): Boolean {
        var transitioned = false
        val toNotify: List<() -> Unit>? = mutex.withLock {
            if (done || cancelled) {
                null
            } else {
                cancelled = true
                transitioned = true
                val list = cancelListeners
                cancelListeners = null
                list
            }
        }
        if (!transitioned) return false
        if (toNotify != null) {
            for (action in toNotify) {
                invokeCancelListener(action)
            }
        }
        return true
    }

    /**
     * Registers [action] to be called once when [cancel] transitions this call to cancelled, or
     * immediately inline if [isCancelled] is already `true` (SPEC U-5b; #164 item 1).
     * If [isDone] is already `true` (and not cancelled), registration is a no-op and [action] is
     * dropped without running.
     *
     * ### Threading, GIL and exception contract
     *
     * [action] runs synchronously on whichever thread calls [cancel] (for Python `Future.cancel()`,
     * that is the asyncio loop thread inside `_pm_cancel` with the GIL held), outside [mutex].
     * Therefore [action] **must be non-blocking** (e.g. calling `job.cancel()` or setting a flag)
     * and must never wait on another thread that needs the GIL. Any exception thrown by [action]
     * is caught and recorded in [cancellationFailure] rather than escaping into C or aborting
     * remaining listeners.
     */
    fun onCancelled(action: () -> Unit) {
        val runNow = mutex.withLock {
            if (cancelled) {
                true
            } else if (done) {
                false
            } else {
                val list = cancelListeners ?: mutableListOf<() -> Unit>().also { cancelListeners = it }
                list.add(action)
                false
            }
        }
        if (runNow) invokeCancelListener(action)
    }

    private fun invokeCancelListener(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            mutex.withLock {
                if (cancellationErr == null) cancellationErr = t
            }
        }
    }

    /**
     * Registers the one thing that gets told when this completes.
     *
     * Fires inline if the call is already done, which is the common case for a body that never
     * suspended -- so a caller does not have to branch on [isDone] before attaching.
     *
     * @throws IllegalStateException if a listener is already waiting. A pending call belongs to
     *   exactly one Python-side waiter (one `Future`, one callback); silently replacing the first
     *   would strand it forever.
     */
    fun onCompleted(action: (PendingCall) -> Unit) {
        val runNow = mutex.withLock {
            if (done) {
                true
            } else {
                check(listener == null) { "this PendingCall already has a completion listener" }
                listener = action
                false
            }
        }
        if (runNow) deliver(action)
    }

    private fun complete(result: Result<Any?>) {
        // A Continuation cannot legitimately be resumed twice, but a second completion must not
        // overwrite the first one's outcome if a generator ever gets it wrong.
        val waiting: ((PendingCall) -> Unit)? = mutex.withLock {
            if (done) {
                null
            } else {
                result.fold({ resultValue = it }, { resultFailure = it })
                done = true
                cancelListeners = null
                val w = listener
                listener = null
                w
            }
        }
        if (waiting != null) deliver(waiting)
    }

    private fun deliver(action: (PendingCall) -> Unit) {
        try {
            action(this)
        } catch (t: Throwable) {
            mutex.withLock { deliveryErr = t }
        }
    }

    override fun toString(): String = when {
        !isDone -> if (isCancelled) "PendingCall(cancelled, still running)" else "PendingCall(pending)"
        failure != null -> "PendingCall(failed: ${failure?.message})"
        else -> "PendingCall(done: $value)"
    }

    /** The key `coroutineContext[PendingCall]` resolves through, and the home of [start]. */
    companion object : CoroutineContext.Key<PendingCall> {

        /**
         * Hook installed by [UpcallTrampoline] to read Python's ambient `CoroutineContext` while
         * inside an attached upcall frame. Kept as a hook so [PendingCall] stays pure Kotlin with
         * no dependency on `Python3` initialization.
         */
        internal var ambientContextProvider: (() -> CoroutineContext)? = null

        /**
         * Starts [block] and returns immediately, complete or not.
         *
         * Nothing is thrown out of here. [block]'s own failure is parked in [failure] the same way
         * its value would be parked in [value], because the caller is a C frame with no unwinding
         * above it -- the same rule [UpcallTrampoline] states for itself.
         *
         * When called inside an attached upcall frame (`UpcallTrampoline`), this overload reads
         * Python's ambient `CoroutineContext` via [ambientContextProvider]; direct Kotlin callers
         * outside an upcall (or inside `withoutGIL`) receive [EmptyCoroutineContext] and should
         * pass an explicit context to the two-argument overload when needed.
         */
        fun start(block: suspend () -> Any?): PendingCall =
            start(ambientContextProvider?.invoke() ?: EmptyCoroutineContext, block)

        /**
         * Starts [block] with `context + call` as its [CoroutineContext] and returns immediately,
         * complete or not (SPEC U-5b; #164 item 1).
         */
        fun start(context: CoroutineContext, block: suspend () -> Any?): PendingCall {
            val call = PendingCall()
            val completion = Continuation<Any?>(context + call) { result -> call.complete(result) }
            try {
                block.startCoroutine(completion)
            } catch (t: Throwable) {
                // startCoroutine routes the body's exceptions to `completion`, so reaching here
                // means the machinery itself failed. `complete` is a no-op if it already ran.
                call.complete(Result.failure(t))
            }
            return call
        }
    }
}

/**
 * The [PendingCall] the calling coroutine was started by, or `null` if it was not started by one.
 *
 * `null` is the ordinary answer for a `suspend fun` called from anywhere other than the boundary --
 * a unit test, or one exposed function calling another directly. Exposed code that wants to be
 * cancellable has to tolerate that, which is why [ensureActive] treats it as "not cancelled"
 * rather than as an error.
 */
suspend fun currentPendingCall(): PendingCall? = coroutineContext[PendingCall]

/**
 * Throws [CancellationException] if the Python side has stopped waiting for this call.
 *
 * The cooperation point [PendingCall.cancel] describes: an exposed `suspend fun` calls this
 * wherever it could usefully stop, and a call whose `Future` was cancelled unwinds instead of
 * running to the end for a result nobody will read.
 *
 * The throw is recorded in [PendingCall.failure] like any other failure, and goes no further --
 * [AsyncUpcall] does not deliver it, because the `Future` it would be delivered to is the settled
 * one that caused the cancellation in the first place.
 */
suspend fun ensureActive() {
    val call = coroutineContext[PendingCall] ?: return
    if (call.isCancelled) throw CancellationException("the Python side cancelled this call")
}
