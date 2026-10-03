package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PyObject

/**
 * Base contract for wrapper types around Python objects that support the
 * iterable protocol (`__iter__`, i.e. `PyObject_GetIter`).
 *
 * The mermaid sketch models this after `java.lang.Iterable`
 * (`forEach(Consumer)`, `spliterator()`), but this is a Kotlin Multiplatform
 * `commonMain` type -- `java.util.function.Consumer` and
 * `java.util.Spliterator` are JVM-only and not available here. Kotlin's own
 * [Iterable] already provides `forEach` as a common extension function built
 * purely on top of [iterator], so nothing is lost by not redeclaring it.
 */
interface PyIterable : Iterable<PyObject> {
    override fun iterator(): PyIterator
}
