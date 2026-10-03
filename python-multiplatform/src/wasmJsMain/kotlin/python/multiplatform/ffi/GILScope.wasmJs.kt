package python.multiplatform.ffi

/**
 * One state, not one per thread.
 *
 * `pyemscripten_2026_0` prohibits `-pthread` ("if -pthread is used, the resulting libraries will
 * not load"), and Kotlin/Wasm has no threads of its own either, so there is exactly one thread that
 * can ever reach CPython here. A thread-local would be a thread-local of one.
 *
 * `withGIL` still has to run: `PyGILState_Ensure`/`Release` attach a thread state as well as
 * serialise, and the attach half is required on every build. The depth counter in `withGIL` is what
 * makes nested scopes cheap, and it works off this object exactly as it does elsewhere.
 */
private var globalState: ThreadGILState? = null

public actual fun getThreadGILState(): ThreadGILState {
    var state = globalState
    if (state == null) {
        state = ThreadGILState()
        globalState = state
    }
    return state
}

public actual fun setThreadGILState(state: ThreadGILState?) {
    globalState = state
}
