package python.multiplatform.ffi.pythonx

/**
 * The calling thread's stack of open [PythonCallableScope]s (innermost last). Each thread gets its
 * own list, created empty on first use; callers mutate it only from the thread that asked for it.
 * Platform-specific because common Kotlin has no thread-local.
 */
internal expect fun ambientScopeStack(): ArrayList<PythonCallableScope>
