package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.attrAs
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python bound method object (`instance.method`), i.e. a
 * function together with the `self` it is bound to.
 *
 * `PyMethod_Function` and `PyMethod_Self` exist in the full C API but not in
 * the limited one, so both accessors go through `PyObject_GetAttrString` on
 * the corresponding dunder. Each returns a **new** wrapper owning its own
 * reference -- reading `method.instance` twice gives two wrappers over the
 * same object, which is the safe shape: neither can free what the other still
 * uses.
 */
open class PyMethod(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `method.__self__`: the bound receiver. */
    val instance: PyObject
        get() = getAttr("__self__")

    /** `method.__func__`: the underlying unbound function. */
    val function: PyFunction
        get() = attrAs("__func__") { PyFunction(it, borrowed = false) }
}
