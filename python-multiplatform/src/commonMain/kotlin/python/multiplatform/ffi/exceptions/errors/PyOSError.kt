package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.attrAsIntOrNull
import python.multiplatform.ffi.attrAsStringOrNull
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback

/**
 * Kotlin-side mirror of Python's built-in `OSError`, a subclass of
 * `Exception` (which has no Kotlin class of its own, so this extends [PyException]).
 *
 * [PyException.fromCurrentError] builds this class for a raised `OSError` and for a Python class
 * derived from it (the nearest built-in class in the type's MRO decides, [BuiltinExceptions]), so
 * Kotlin can `catch (PyOSError)`. [type] is the raised object's actual Python type.
 */
open class PyOSError(
    errMsg: String,
    type: PyType? = null,
    value: PyObject? = null,
    traceback: PyTraceback? = null,
    context: PyException? = null,
    cause: PyException? = null,
) : PyException(errMsg, type, value, traceback, context, cause) {
    /** The C `errno` (`OSError.errno`), or `null`. Read from [value] when asked. */
    val errno: Int?
        get() = value?.attrAsIntOrNull("errno")

    /** The error text the OS gave (`OSError.strerror`), or `null`. Read from [value] when asked. */
    val strerror: String?
        get() = value?.attrAsStringOrNull("strerror")

    /** The path involved (`OSError.filename`), or `null` (or not a `str`). Read from [value] when asked. */
    val filename: String?
        get() = value?.attrAsStringOrNull("filename")

    companion object {
        /** The Python type name this Kotlin class mirrors. */
        const val PYTHON_TYPE_NAME: String = "OSError"
    }
}
