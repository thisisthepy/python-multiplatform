package python.multiplatform.ffi

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
private var threadLocalState: ThreadGILState? = null

public actual fun getThreadGILState(): ThreadGILState {
    var state = threadLocalState
    if (state == null) {
        state = ThreadGILState()
        threadLocalState = state
    }
    return state
}

public actual fun setThreadGILState(state: ThreadGILState?) {
    threadLocalState = state
}
