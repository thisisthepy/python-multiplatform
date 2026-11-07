package python.multiplatform.ffi.pythonx

import kotlin.native.concurrent.ThreadLocal

/** `@ThreadLocal`: every thread sees its own instance, created on first access. */
@ThreadLocal
private object AmbientScopeStackHolder {
    val stack = ArrayList<PythonCallableScope>()
}

internal actual fun ambientScopeStack(): ArrayList<PythonCallableScope> = AmbientScopeStackHolder.stack
