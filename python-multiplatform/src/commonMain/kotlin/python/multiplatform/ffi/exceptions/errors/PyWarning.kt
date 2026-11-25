package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback

/**
 * Kotlin-side mirror of Python's built-in `Warning`, a subclass of
 * `Exception` (which has no Kotlin class of its own, so this extends [PyException]).
 *
 * [PyException.fromCurrentError] builds this class for a raised `Warning` and for a Python class
 * derived from it (the nearest built-in class in the type's MRO decides, [BuiltinExceptions]), so
 * Kotlin can `catch (PyWarning)`. [type] is the raised object's actual Python type.
 */
open class PyWarning(
    errMsg: String,
    type: PyType? = null,
    value: PyObject? = null,
    traceback: PyTraceback? = null,
    context: PyException? = null,
    cause: PyException? = null,
) : PyException(errMsg, type, value, traceback, context, cause) {
    companion object {
        /** The Python type name this Kotlin class mirrors. */
        const val PYTHON_TYPE_NAME: String = "Warning"
    }
}
