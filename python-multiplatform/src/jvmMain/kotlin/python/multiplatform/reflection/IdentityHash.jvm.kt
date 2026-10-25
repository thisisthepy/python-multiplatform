package python.multiplatform.reflection

internal actual fun identityHashOf(value: Any): Int = System.identityHashCode(value)
