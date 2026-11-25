package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.attrAsStringOrNull
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback

/**
 * Kotlin-side mirror of Python's built-in `ImportError`, a subclass of
 * `Exception` (which has no Kotlin class of its own, so this extends [PyException]).
 *
 * [PyException.fromCurrentError] builds this class for a raised `ImportError` and for a Python class
 * derived from it (the nearest built-in class in the type's MRO decides, [BuiltinExceptions]), so
 * Kotlin can `catch (PyImportError)`. [type] is the raised object's actual Python type.
 */
open class PyImportError(
    errMsg: String,
    type: PyType? = null,
    value: PyObject? = null,
    traceback: PyTraceback? = null,
    context: PyException? = null,
    cause: PyException? = null,
) : PyException(errMsg, type, value, traceback, context, cause) {
    /** The module that could not be imported (`ImportError.name`). Read from [value] when asked. */
    val moduleName: String?
        get() = value?.attrAsStringOrNull("name")

    /** The file that triggered it (`ImportError.path`). Read from [value] when asked. */
    val path: String?
        get() = value?.attrAsStringOrNull("path")

    companion object {
        /** The Python type name this Kotlin class mirrors. */
        const val PYTHON_TYPE_NAME: String = "ImportError"
    }
}
