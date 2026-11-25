package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback
import python.multiplatform.ffi.types.attrObjectOrNull

/**
 * Kotlin-side mirror of Python's built-in `SystemExit`, a subclass of
 * `BaseException` ([PyException] here).
 *
 * [PyException.fromCurrentError] builds this class for a raised `SystemExit` and for a Python class
 * derived from it (the nearest built-in class in the type's MRO decides, [BuiltinExceptions]), so
 * Kotlin can `catch (PySystemExit)`. [type] is the raised object's actual Python type.
 */
class PySystemExit(
    errMsg: String,
    type: PyType? = null,
    value: PyObject? = null,
    traceback: PyTraceback? = null,
    context: PyException? = null,
    cause: PyException? = null,
) : PyException(errMsg, type, value, traceback, context, cause) {
    /** The exit status (`SystemExit.code`): an `int`, a message, or `null` for `None`. Read from [value] when asked. */
    val code: PyObject?
        get() = value?.attrObjectOrNull("code")

    companion object {
        /** The Python type name this Kotlin class mirrors. */
        const val PYTHON_TYPE_NAME: String = "SystemExit"
    }
}
