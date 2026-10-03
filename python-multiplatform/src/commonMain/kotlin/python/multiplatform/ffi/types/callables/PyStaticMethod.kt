package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.attrAs
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `staticmethod` descriptor object.
 *
 * As with [PyClassMethod], the descriptor object itself only appears in the
 * owning class's `__dict__`; attribute access on the class hands back the
 * plain [PyFunction] this wraps.
 */
open class PyStaticMethod(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `staticmethod.__func__`: the underlying plain function. */
    val function: PyFunction
        get() = attrAs("__func__") { PyFunction(it, borrowed = false) }
}
