package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pyErrorOrGeneric
import python.native.ffi.NativePointer
import python.native.ffi.PyDict_GetItemString
import python.native.ffi.PyEval_GetBuiltins

/**
 * Wrapper around Python's `Ellipsis` (`...`) singleton. See
 * [python.multiplatform.ffi.types.basic.PyNone] for the same
 * singleton-access caveat.
 *
 * `Py_Ellipsis` is a *data* symbol, not a function, so it is not something
 * this project's function-oriented FFI layer can reach: the Stable ABI
 * guarantees the symbol exists, but `EmbedAPI` only declares entry points.
 * The name is however bound in the builtins namespace, so a single dictionary
 * lookup gets at the very same object -- CPython creates exactly one
 * `Ellipsis` per interpreter, and `builtins.Ellipsis` *is* it, not a copy.
 * That is what makes the identity comparison in [isEllipsis] meaningful.
 */
class PyEllipsis private constructor(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        private var _instance: PyEllipsis? = null

        /** Returns the singleton `Ellipsis` object, initialising it on first access. */
        fun get(): PyEllipsis {
            return _instance ?: run {
                // PyEval_GetBuiltins and PyDict_GetItemString both return BORROWED references,
                // so the wrapper is built with borrowed = true and takes one of its own.
                val builtins = Python3.withPython { PyEval_GetBuiltins() }
                    ?: throw pyErrorOrGeneric("The builtins namespace is unavailable")
                val ellipsisPointer = Python3.withPython { PyDict_GetItemString(builtins, "Ellipsis") }
                    ?: throw pyErrorOrGeneric("builtins.Ellipsis is not available")
                val ellipsis = PyEllipsis(ellipsisPointer, borrowed = true)
                _instance = ellipsis
                ellipsis
            }
        }

        /** Whether [obj] is Python's `Ellipsis` singleton (`obj is ...`). */
        fun isEllipsis(obj: PyObject): Boolean = obj.pointer == get().pointer
    }
}
