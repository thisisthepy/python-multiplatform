package python.multiplatform.ffi.types.descriptors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.attrAsString
import python.multiplatform.ffi.types.adoptChecked
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python getset descriptor: the descriptor of an attribute a C type computes (`type(f).__dict__["__name__"]` for a function `f`)
 * (#524, the baseline's `types/descriptors/PyGetSetDescriptor.kt`).
 *
 * [get], [set] and [delete] ([PyDescriptor]) read, write and delete the attribute on an instance of
 * [ownerClass].
 */
open class PyGetSetDescriptor(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyDescriptor {
    companion object {
        /** [obj] as a getset descriptor (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyGetSetDescriptor =
            adoptChecked(obj, "types", "GetSetDescriptorType", "a getset descriptor") { PyGetSetDescriptor(it, borrowed = true) }
    }

    override val descriptor: PyObject get() = this

    /** The attribute name (`__name__`). */
    val name: String get() = attrAsString("__name__")

    /** The class that declares the attribute (`__objclass__`). */
    val ownerClass: PyType get() = attrAsType("__objclass__")
}
