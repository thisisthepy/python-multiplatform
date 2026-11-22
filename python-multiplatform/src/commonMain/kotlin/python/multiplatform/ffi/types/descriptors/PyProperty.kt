package python.multiplatform.ffi.types.descriptors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.types.adoptChecked
import python.multiplatform.ffi.types.attrObjectOrNull
import python.multiplatform.ffi.types.noneIfNull
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `property` (#524, the baseline's `types/descriptors/PyProperty.kt`).
 *
 * [getter], [setter] and [deleter] are the functions the property was built from (`fget`, `fset`,
 * `fdel`); [get], [set] and [delete] ([PyDescriptor]) run them on an instance, raising
 * [python.multiplatform.ffi.exceptions.errors.PyAttributeError] where the property has no such
 * function, as Python does.
 */
open class PyProperty(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyDescriptor {
    companion object {
        /** [obj] as a property (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyProperty =
            adoptChecked(obj, "builtins", "property", "a property") { PyProperty(it, borrowed = true) }

        /** `property(fget, fset, fdel, doc)`. */
        fun of(getter: PyObject? = null, setter: PyObject? = null, deleter: PyObject? = null, doc: PyObject? = null): PyProperty {
            val property = Python3.import("builtins").use { it.getAttr("property") }
                .use { it(noneIfNull(getter), noneIfNull(setter), noneIfNull(deleter), noneIfNull(doc)) }
            return property.use { PyProperty(it.pointer, borrowed = true) }
        }
    }

    override val descriptor: PyObject get() = this

    /** `fget`, or `null`. */
    val getter: PyObject? get() = attrObjectOrNull("fget")

    /** `fset`, or `null` for a read-only property. */
    val setter: PyObject? get() = attrObjectOrNull("fset")

    /** `fdel`, or `null`. */
    val deleter: PyObject? get() = attrObjectOrNull("fdel")

    /** `property.setter(function)`: a copy of this property with [function] as its setter. */
    fun withSetter(function: PyObject): PyProperty =
        getAttr("setter").use { it(function) }.use { PyProperty(it.pointer, borrowed = true) }

    /** `property.deleter(function)`: a copy with [function] as its deleter. */
    fun withDeleter(function: PyObject): PyProperty =
        getAttr("deleter").use { it(function) }.use { PyProperty(it.pointer, borrowed = true) }
}
