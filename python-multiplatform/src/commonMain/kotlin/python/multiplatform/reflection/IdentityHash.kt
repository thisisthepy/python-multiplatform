package python.multiplatform.reflection

/**
 * The identity hash of [value]: equal for the same object, whatever its own `hashCode` says.
 * Backs a proxy's `__hash__` (SPEC U-4), whose `==` is reference identity.
 */
internal expect fun identityHashOf(value: Any): Int
