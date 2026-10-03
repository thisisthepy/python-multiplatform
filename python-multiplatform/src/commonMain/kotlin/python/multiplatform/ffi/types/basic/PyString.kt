package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.PyUnicode_Concat
import python.native.ffi.PyUnicode_Contains
import python.native.ffi.PyUnicode_FromString

/** Wrapper around a Python `str` object (`PyUnicode_*` family). */
open class PyString(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<String> {
    companion object {
        /** The `PyType` for `str` (`builtins.str`). */
        val TYPE: PyType by lazy { val obj = from(""); val t = obj.Type; obj.close(); t }

        /** Wraps [value] as a new Python `str` object (`PyUnicode_FromString`). */
        fun from(value: String): PyString {
            val ptr = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString(value) } ?: throw PyException.fromCurrentError()!!
            return PyString(ptr, false)
        }
    }

    override var cachedNativeValue: String?
        get() {
            val res = python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(pointer) }
            if (res == null && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) {
                throw PyException.fromCurrentError()!!
            }
            return res
        }
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = this
        set(value) {}

    /** `len(self)`. */
    val length: Int
        get() = toKotlin().length

    operator fun plus(other: PyString): PyString {
        val res = python.multiplatform.ffi.Python3.withPython { PyUnicode_Concat(pointer, other.pointer) } ?: throw PyException.fromCurrentError()!!
        return PyString(res, false)
    }
    
    operator fun get(index: Int): PyString {
        return PyString.from(toKotlin()[index].toString())
    }
    
    operator fun contains(substring: String): Boolean {
        val subObj = PyString.from(substring)
        val res = python.multiplatform.ffi.Python3.withPython { PyUnicode_Contains(pointer, subObj.pointer) }
        subObj.close()
        if (res == -1 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) throw PyException.fromCurrentError()!!
        return res == 1
    }
}

fun String.asPyObject(): PyString = PyString.from(this)
