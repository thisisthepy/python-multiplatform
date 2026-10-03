package python.multiplatform.ffi

private val threadLocalState = object : java.lang.ThreadLocal<ThreadGILState>() {
    override fun initialValue() = ThreadGILState()
}

public actual fun getThreadGILState(): ThreadGILState = threadLocalState.get()

public actual fun setThreadGILState(state: ThreadGILState?) {
    if (state == null) {
        threadLocalState.remove()
    } else {
        threadLocalState.set(state)
    }
}
