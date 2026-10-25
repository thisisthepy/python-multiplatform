package python.multiplatform.ffi.pythonx

private val stacks = ThreadLocal.withInitial { ArrayList<PythonCallableScope>() }

internal actual fun ambientScopeStack(): ArrayList<PythonCallableScope> = stacks.get()
