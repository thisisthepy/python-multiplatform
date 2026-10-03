package python.multiplatform.ffi.conversion

/**
 * Controls how much a [PyObject][python.multiplatform.ffi.PyObject] gets
 * converted towards a native Kotlin value when it crosses the Kotlin/Python
 * boundary, trading off safety/fidelity against overhead.
 *
 * Roughly, from cheapest/most-Python-native to most-eager/most-Kotlin-native:
 *
 * - [RAW]: keep only the raw `NativePointer`; no wrapper object is
 *   materialised at all. Cheapest, but loses type safety and refcount
 *   bookkeeping convenience.
 * - [UNMANAGED]: wrap in a [python.multiplatform.ffi.PyObject] (or the
 *   appropriate subclass) but leave it fully under Python's own refcount
 *   management -- no defensive copy, no caching of a converted value.
 * - [TYPED]: wrap in the appropriate typed [PyObject] subclass (e.g.
 *   [python.multiplatform.ffi.types.basic.PyInt]) and lazily convert to a
 *   native value on demand, caching the result (see
 *   [PyProxy.cachedNativeValue]).
 * - [NATIVE]: eagerly convert all the way down to native Kotlin
 *   types/collections (e.g. a Python `dict` becomes a Kotlin `Map`
 *   recursively). Most convenient, most expensive, and only possible for
 *   Python values that have a native Kotlin counterpart at all -- custom
 *   user-defined Python objects cannot go further than [TYPED].
 * - [DEFAULT]: defers to whatever [PyContext] considers the sane default
 *   (currently [TYPED]).
 */
enum class ConversionStrategy {
    DEFAULT,
    UNMANAGED,
    RAW,
    TYPED,
    NATIVE,
}
