package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyDict_GetItemString
import python.native.ffi.PyEval_GetBuiltins
import python.native.ffi.PyFloat_AsDouble
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_IncRef
import python.native.ffi.Py_DecRef
import python.native.ffi.PyErr_Occurred

/**
 * Wrapper around a Python `complex` object.
 *
 * Kept intentionally minimal (per task scope: "if straightforward") since
 * `EmbedAPI` does not expose any `PyComplex_*` Stable ABI entry points --
 * only the generic `PyObject_*`/`PyNumber_*` protocol functions would be
 * available to implement this later (e.g. going through `complex(real, imag)`
 * via `PyObject_Call` against the builtin, and reading back `.real`/`.imag`
 * attributes rather than a dedicated C struct accessor).
 */
open class PyComplex(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** The `PyType` for `complex` (`builtins.complex`). */
        val TYPE: PyType by lazy { val obj = from(0.0, 0.0); val t = obj.Type; obj.close(); t }

        /** Constructs a new `complex(real, imag)` object. */
        fun from(real: Double, imag: Double): PyComplex {
            val builtins = python.multiplatform.ffi.Python3.withPython { PyEval_GetBuiltins() } ?: throw PyException.fromCurrentError()!!
            val complexTypePtr = python.multiplatform.ffi.Python3.withPython { PyDict_GetItemString(builtins, "complex") } ?: throw PyException.fromCurrentError()!!
            
            val args = python.multiplatform.ffi.Python3.withPython { PyTuple_New(2) } ?: throw PyException.fromCurrentError()!!
            val realObj = PyFloat.from(real)
            val imagObj = PyFloat.from(imag)
            
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(realObj.pointer) }
            python.multiplatform.ffi.Python3.withPython { PyTuple_SetItem(args, 0, realObj.pointer) }
            
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(imagObj.pointer) }
            python.multiplatform.ffi.Python3.withPython { PyTuple_SetItem(args, 1, imagObj.pointer) }
            
            val resPtr = python.multiplatform.ffi.Python3.withPython { PyObject_CallObject(complexTypePtr, args) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(args) }
            realObj.close()
            imagObj.close()
            
            if (resPtr == null) throw PyException.fromCurrentError()!!
            return PyComplex(resPtr, false)
        }
    }

    val real: Double
        get() {
            val realAttr = getAttr("real")
            val res = python.multiplatform.ffi.Python3.withPython { PyFloat_AsDouble(realAttr.pointer) }
            realAttr.close()
            if (res == -1.0 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) throw PyException.fromCurrentError()!!
            return res
        }

    val imag: Double
        get() {
            val imagAttr = getAttr("imag")
            val res = python.multiplatform.ffi.Python3.withPython { PyFloat_AsDouble(imagAttr.pointer) }
            imagAttr.close()
            if (res == -1.0 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) throw PyException.fromCurrentError()!!
            return res
        }
}
