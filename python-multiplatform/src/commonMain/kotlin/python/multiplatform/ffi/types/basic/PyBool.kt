package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyBool_FromLong
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyObject_IsTrue

/**
 * Wrapper around a Python `bool` object.
 *
 * In Python, `bool` is a subclass of `int` (`PyType.isSubtypeOf` will report
 * that at the Python level). That relationship is deliberately *not* mirrored
 * in the Kotlin class hierarchy here (`PyBool` does not extend [PyInt]):
 * both types implement [PyProxy] with different type arguments (`Boolean` vs
 * `Long`), and Kotlin does not allow a class to implement the same generic
 * interface twice with different arguments. Use [PyType.isSubtypeOf] if the
 * Python-level relationship needs to be observed.
 */
open class PyBool(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<Boolean> {
    companion object {
        /** The `PyType` for `bool` (`builtins.bool`). */
        val TYPE: PyType by lazy { val obj = from(false); val t = obj.Type; obj.close(); t }

        /** Returns the (singleton) `True` or `False` Python object for [value] (`PyBool_FromLong`). */
        fun from(value: Boolean): PyBool {
            val ptr = python.multiplatform.ffi.Python3.withPython { PyBool_FromLong(if (value) 1 else 0) } ?: throw PyException.fromCurrentError()!!
            return PyBool(ptr, false)
        }
    }

    override var cachedNativeValue: Boolean?
        get() {
            val res = python.multiplatform.ffi.Python3.withPython { PyObject_IsTrue(pointer) }
            if (res == -1 && python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } != null) {
                throw PyException.fromCurrentError()!!
            }
            return res == 1
        }
        set(value) {}
    override var cachedPyObjectValue: PyObject?
        get() = this
        set(value) {}
}

fun Boolean.asPyObject(): PyBool = PyBool.from(this)
