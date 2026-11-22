package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.iteration.PyIterable
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer

/**
 * A mutable Python container, as a Kotlin [MutableCollection] (issue #524 item 3).
 *
 * Source: the owner's `python_for_kotlin_binding.mermaid`: `PyObject <|-- PyMutableIterable`,
 * `PyIterable <|-- PyMutableIterable`, `MutableCollection <|.. PyMutableIterable`,
 * `PyMutableIterable <|-- PyList`, `PyMutableIterable <|-- PySet`, with `+add(element Any) Boolean`,
 * `+remove(element Any) Boolean` and `+clear() void`.
 *
 * Elements are `Any?` (#524 item 4): a [PyObject] is stored as itself; `null` is `None`; a Kotlin
 * `Boolean`, integer, floating-point number, `String` or `ByteArray` becomes the Python value; a
 * Kotlin `List`, `Set` or `Map` becomes a new Python `list`, `set` or `dict` of its converted
 * elements. Anything else is refused with `PyTypeError`. Reads answer [PyObject]s.
 */
abstract class PyMutableIterable(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyIterable, MutableCollection<Any?> {

    abstract override fun iterator(): PyIterator

    abstract override fun add(element: Any?): Boolean

    abstract override fun remove(element: Any?): Boolean

    abstract override fun clear()
}
