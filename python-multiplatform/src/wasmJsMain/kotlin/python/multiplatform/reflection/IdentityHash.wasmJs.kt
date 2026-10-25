package python.multiplatform.reflection

// Kotlin/Wasm exposes no identity hash. The object's own `hashCode` is still equal for the same object,
// which is all `__hash__` needs next to an identity `__eq__`; it only costs spread for a type that
// overrides `hashCode`.
internal actual fun identityHashOf(value: Any): Int = value.hashCode()
