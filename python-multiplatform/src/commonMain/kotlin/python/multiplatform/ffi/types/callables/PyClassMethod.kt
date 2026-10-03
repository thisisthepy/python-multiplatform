package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.attrAs
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `classmethod` descriptor object.
 *
 * Note that the descriptor itself is only reachable through the owning
 * class's `__dict__` -- `SomeClass.method` has already run the descriptor
 * protocol and yields a bound [PyMethod], not this.
 */
open class PyClassMethod(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `classmethod.__func__`: the underlying function, called with the owning class as its first argument. */
    val function: PyFunction
        get() = attrAs("__func__") { PyFunction(it, borrowed = false) }
}
