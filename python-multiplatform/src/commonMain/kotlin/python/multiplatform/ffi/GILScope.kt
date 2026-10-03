package python.multiplatform.ffi

import python.native.ffi.PyGILState_Ensure
import python.native.ffi.PyGILState_Release
import python.native.ffi.PyEval_SaveThread
import python.native.ffi.PyEval_RestoreThread
import python.native.ffi.NativePointer

public class ThreadGILState(var state: Int = 0, var depth: Int = 0) {
    /**
     * Outermost [withGIL] scopes this thread may still enter before it takes an automatic
     * eval-loop checkpoint. See [Python3.drainPendingReleases].
     */
    public var checkpointCountdown: Int = 0

    /**
     * Set while this thread must not take a checkpoint: inside one already, or on a cleaner
     * running [python.multiplatform.ffi.ReleaseCounter]-counted release actions. Running Python
     * from a cleaner is what ROADMAP §1 turned into a deadlock and §9 explicitly rules out as a
     * fix, and it would drain the wrong thread's queue in any case.
     */
    public var checkpointsSuppressed: Boolean = false
}

/**
 * Decides whether this outermost scope should take an eval-loop checkpoint, and takes it.
 *
 * Two gates, both cheap. The countdown bounds how often a checkpoint can happen at all; the
 * release mark skips it entirely when no reference has been given back since the previous one, so
 * a workload that allocates nothing pays a field compare and nothing else.
 *
 * Not inline, so that [withGIL] — which is — can reach the internal counters through it.
 */
@PublishedApi
internal fun maybeReachEvalCheckpoint(tState: ThreadGILState) {
    val interval = Python3.autoDrainInterval
    if (interval <= 0 || tState.checkpointsSuppressed) return
    if (--tState.checkpointCountdown > 0) return
    tState.checkpointCountdown = interval

    val released = ReleaseCounter.released
    if (released == Python3.lastCheckpointReleaseMark) return
    Python3.lastCheckpointReleaseMark = released

    tState.checkpointsSuppressed = true
    try {
        Python3.reachEvalCheckpointHoldingGIL()
    } finally {
        tState.checkpointsSuppressed = false
    }
}

public expect fun getThreadGILState(): ThreadGILState
public expect fun setThreadGILState(state: ThreadGILState?)

/**
 * Attaches this thread to the interpreter for the duration of [block].
 *
 * ### Free-threaded builds
 *
 * The name is historical. `PyGILState_Ensure`/`Release` do two things: they attach a thread
 * state to the calling thread, and -- on a build with the global lock -- they serialise
 * execution. A free-threaded build drops the second, not the first: a thread still must be
 * attached before it may touch any object, including a bare `Py_IncRef`. So this wrapper is
 * required on both builds, and the reference-counting paths keep it.
 *
 * What does change is the cost of holding it. Under the GIL, keeping the scope open across a
 * long operation blocks every other thread, which is why long-running work belongs in
 * [withoutGIL]. Free-threaded, holding attachment costs other threads nothing, so widening a
 * scope to cover a whole operation -- one attach instead of one per C API call -- is free of
 * that penalty. The nesting counter here already makes inner scopes cheap; on a free-threaded
 * build the outermost one stops being a bottleneck as well.
 */
public inline fun <T> withGIL(block: () -> T): T {
    val tState = getThreadGILState()
    val isOutermost = tState.depth == 0
    if (isOutermost) {
        tState.state = PyGILState_Ensure()
    }
    tState.depth++
    try {
        // Attached and nested one level deep, so the checkpoint's own C API calls are cheap and
        // cannot recurse back into this branch.
        if (isOutermost) maybeReachEvalCheckpoint(tState)
        return block()
    } finally {
        tState.depth--
        if (tState.depth == 0) {
            val stateToRelease = tState.state
            PyGILState_Release(stateToRelease)
        }
    }
}

/**
 * Detaches this thread for the duration of [block], so other threads can run Python.
 *
 * Wrap anything long or blocking that does not touch the C API -- I/O, a native computation,
 * waiting on a lock. Under the GIL this is what keeps one slow call from stalling every other
 * thread. On a free-threaded build there is no global lock to yield, so this mostly stops
 * mattering; it stays correct either way, since detaching and reattaching is valid on both.
 *
 * The block must not call into CPython: after [PyEval_SaveThread] this thread has no thread
 * state, and any C API call made without one is undefined behaviour.
 */
public inline fun <T> withoutGIL(block: () -> T): T {
    val save = PyEval_SaveThread()
    try {
        return block()
    } finally {
        if (save != null) {
            PyEval_RestoreThread(save)
        }
    }
}
