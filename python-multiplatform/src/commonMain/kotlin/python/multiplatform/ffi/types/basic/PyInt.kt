package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyCompareOp
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyNumber_Add
import python.native.ffi.PyNumber_Multiply
import python.native.ffi.PyNumber_Subtract
import python.native.ffi.PyObject_RichCompareBool

/**
 * Wrapper around a Python `int` object.
 *
 * CPython ints are arbitrary precision, but the Stable ABI surface available
 * here (`PyLong_As/FromLongLong`) only bridges the 64-bit range. Values
 * outside `Long` range will not round-trip through [toKotlin] -- this is a
 * known limitation to revisit (e.g. a `BigInteger`-backed path) rather than
 * an oversight.
 */
open class PyInt(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<Long> {
    companion object {
        /** The `PyType` for `int` (`builtins.int`). */
        val TYPE: PyType by lazy { val obj = from(0L); val t = obj.Type; obj.close(); t }

        /** Wraps [value] as a new Python `int` object (`PyLong_FromLongLong`). */
        fun from(value: Long): PyInt {
            val ptr = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(value) } ?: throw PyException.fromCurrentError()!!
            return PyInt(ptr, false)
        }
    }

    override var cachedNativeValue: Long?
        get() {
            val res = python.multiplatform.ffi.Python3.withPython { PyLong_AsLongLong(pointer) }
            if (res == -1L && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) {
                throw PyException.fromCurrentError()!!
            }
            return res
        }
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = this
        set(value) {}

    operator fun plus(other: PyInt): PyInt {
        val res = python.multiplatform.ffi.Python3.withPython { PyNumber_Add(pointer, other.pointer) } ?: throw PyException.fromCurrentError()!!
        return PyInt(res, false)
    }
    operator fun minus(other: PyInt): PyInt {
        val res = python.multiplatform.ffi.Python3.withPython { PyNumber_Subtract(pointer, other.pointer) } ?: throw PyException.fromCurrentError()!!
        return PyInt(res, false)
    }
    operator fun times(other: PyInt): PyInt {
        val res = python.multiplatform.ffi.Python3.withPython { PyNumber_Multiply(pointer, other.pointer) } ?: throw PyException.fromCurrentError()!!
        return PyInt(res, false)
    }
    operator fun compareTo(other: PyInt): Int {
        val resLT = python.multiplatform.ffi.Python3.withPython { PyObject_RichCompareBool(pointer, other.pointer, PyCompareOp.LT.opId) }
        if (resLT == -1 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) throw PyException.fromCurrentError()!!
        if (resLT == 1) return -1
        
        val resGT = python.multiplatform.ffi.Python3.withPython { PyObject_RichCompareBool(pointer, other.pointer, PyCompareOp.GT.opId) }
        if (resGT == -1 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) throw PyException.fromCurrentError()!!
        if (resGT == 1) return 1
        return 0
    }
}

fun Long.asPyObject(): PyInt = PyInt.from(this)
fun Int.asPyObject(): PyInt = PyInt.from(this.toLong())
