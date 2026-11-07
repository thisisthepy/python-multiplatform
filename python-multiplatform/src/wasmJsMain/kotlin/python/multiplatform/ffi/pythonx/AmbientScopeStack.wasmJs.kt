package python.multiplatform.ffi.pythonx

/** wasmJs is single-threaded: one stack is the thread's stack. */
private val stack = ArrayList<PythonCallableScope>()

internal actual fun ambientScopeStack(): ArrayList<PythonCallableScope> = stack
