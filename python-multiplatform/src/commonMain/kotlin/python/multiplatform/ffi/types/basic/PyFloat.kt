package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyCompareOp
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyFloat_AsDouble
import python.native.ffi.PyFloat_FromDouble
import python.native.ffi.PyNumber_Add
import python.native.ffi.PyNumber_Multiply
import python.native.ffi.PyNumber_Subtract
import python.native.ffi.PyObject_RichCompareBool

/**
 * Wrapper around a Python `float` object (a C `double`).
 *
 * Note: the original stub had this implement `PyProxy<Boolean>` (presumably
 * copy-pasted from [PyBool]); corrected to `PyProxy<Double>` here since a
 * Python float's natural Kotlin projection is a [Double].
 */
open class PyFloat(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<Double> {
    companion object {
        /** The `PyType` for `float` (`builtins.float`). */
        val TYPE: PyType by lazy { val obj = from(0.0); val t = obj.Type; obj.close(); t }

        /** Wraps [value] as a new Python `float` object (`PyFloat_FromDouble`). */
        fun from(value: Double): PyFloat {
            val ptr = python.multiplatform.ffi.Python3.withPython { PyFloat_FromDouble(value) } ?: throw PyException.fromCurrentError()!!
            return PyFloat(ptr, false)
        }
    }

    override var cachedNativeValue: Double?
        get() {
            val res = python.multiplatform.ffi.Python3.withPython { PyFloat_AsDouble(pointer) }
            if (res == -1.0 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) {
                throw PyException.fromCurrentError()!!
            }
            return res
        }
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = this
        set(value) {}

    operator fun plus(other: PyFloat): PyFloat {
        val res = python.multiplatform.ffi.Python3.withPython { PyNumber_Add(pointer, other.pointer) } ?: throw PyException.fromCurrentError()!!
        return PyFloat(res, false)
    }
    operator fun minus(other: PyFloat): PyFloat {
        val res = python.multiplatform.ffi.Python3.withPython { PyNumber_Subtract(pointer, other.pointer) } ?: throw PyException.fromCurrentError()!!
        return PyFloat(res, false)
    }
    operator fun times(other: PyFloat): PyFloat {
        val res = python.multiplatform.ffi.Python3.withPython { PyNumber_Multiply(pointer, other.pointer) } ?: throw PyException.fromCurrentError()!!
        return PyFloat(res, false)
    }
    operator fun compareTo(other: PyFloat): Int {
        val resLT = python.multiplatform.ffi.Python3.withPython { PyObject_RichCompareBool(pointer, other.pointer, PyCompareOp.LT.opId) }
        if (resLT == -1 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) throw PyException.fromCurrentError()!!
        if (resLT == 1) return -1
        
        val resGT = python.multiplatform.ffi.Python3.withPython { PyObject_RichCompareBool(pointer, other.pointer, PyCompareOp.GT.opId) }
        if (resGT == -1 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) throw PyException.fromCurrentError()!!
        if (resGT == 1) return 1
        return 0
    }
}

fun Double.asPyObject(): PyFloat = PyFloat.from(this)
