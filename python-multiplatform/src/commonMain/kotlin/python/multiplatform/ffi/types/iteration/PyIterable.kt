package python.multiplatform.ffi.types.iteration

/**
 * A Python object Kotlin can iterate, as a read-only Kotlin [Collection] (issue #524 item 3).
 *
 * Source: the owner's `python_for_kotlin_binding.mermaid`: `Collection <|.. PyIterable`,
 * `PyIterable <|-- PyMutableIterable`, `PyIterable <|.. PyTuple`, `PyIterator --o PyIterable`, and
 * `+iterator() PyIterator`. Owner, 2026-11-22 (#524): restore the code shapes agents changed without
 * approval, to the pre-agent baseline and the mermaid.
 *
 * Elements are declared `Any?`, as the mermaid's members take and return `Any` (#524 item 4): a write
 * accepts any Kotlin value Python has a counterpart for, and a read answers the element as a
 * [python.multiplatform.ffi.PyObject] (`None` included), which is an `Any`. [iterator] is a
 * [PyIterator], whose `next()` answers a `PyObject`.
 *
 * The mermaid's `forEach(Consumer)` and `spliterator()` are `java.lang.Iterable` members, which a
 * Kotlin Multiplatform common type cannot declare; Kotlin's common `forEach` is an extension built on
 * [iterator], so it is available on every `PyIterable`.
 */
interface PyIterable : Collection<Any?> {
    override fun iterator(): PyIterator
}
