package python.multiplatform.ffi.types.descriptors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.attrAsString
import python.multiplatform.ffi.types.adoptChecked
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python member descriptor: the descriptor of a `__slots__` entry or of a C struct member (`C.__dict__["x"]` for a class with `__slots__ = ("x",)`)
 * (#524, the baseline's `types/descriptors/PyMemberDescriptor.kt`).
 *
 * [get], [set] and [delete] ([PyDescriptor]) read, write and delete the attribute on an instance of
 * [ownerClass].
 */
open class PyMemberDescriptor(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyDescriptor {
    companion object {
        /** [obj] as a member descriptor (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyMemberDescriptor =
            adoptChecked(obj, "types", "MemberDescriptorType", "a member descriptor") { PyMemberDescriptor(it, borrowed = true) }
    }

    override val descriptor: PyObject get() = this

    /** The attribute name (`__name__`). */
    val name: String get() = attrAsString("__name__")

    /** The class that declares the attribute (`__objclass__`). */
    val ownerClass: PyType get() = attrAsType("__objclass__")
}
