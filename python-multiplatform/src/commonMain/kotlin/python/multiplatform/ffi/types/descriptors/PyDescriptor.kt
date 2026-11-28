package python.multiplatform.ffi.types.descriptors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.adoptingNewReference
import python.multiplatform.ffi.attrAsStringOrNull
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.basic.PyNone
import python.native.ffi.PyObject_GetAttrString

/**
 * The descriptor protocol shared by [PyMemberDescriptor], [PyGetSetDescriptor] and [PyProperty]
 * (#524): [get], [set] and [delete] are the descriptor's `__get__`, `__set__` and `__delete__` on an
 * instance, which is what `instance.attr`, `instance.attr = v` and `del instance.attr` do.
 */
interface PyDescriptor {
    /** The descriptor object itself. */
    val descriptor: PyObject

    /** `descriptor.__get__(instance, type(instance))`: the attribute's value on [instance]. */
    fun get(instance: PyObject): PyObject =
        descriptor.getAttr("__get__").use { it(instance, instance.Type) }

    /** `descriptor.__set__(instance, value)`. */
    fun set(instance: PyObject, value: PyObject) {
        descriptor.getAttr("__set__").use { it(instance, value) }.close()
    }

    /** `descriptor.__delete__(instance)`. */
    fun delete(instance: PyObject) {
        descriptor.getAttr("__delete__").use { it(instance) }.close()
    }

    /** `descriptor.__doc__`, or `null`. */
    val doc: String? get() = descriptor.attrAsStringOrNull("__doc__")
}

/** `obj.<attr>` read as a [PyType]. */
internal fun PyObject.attrAsType(attr: String): PyType =
    Python3.withPython { PyObject_GetAttrString(pointer, attr) }
        ?.adoptingNewReference { PyType.getInstance(it) }
        ?: throw pyErrorOrGeneric("Attribute '$attr' not found")
