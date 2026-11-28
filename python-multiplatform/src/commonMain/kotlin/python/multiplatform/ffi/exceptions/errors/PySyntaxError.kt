package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.attrAsIntOrNull
import python.multiplatform.ffi.attrAsStringOrNull
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback

/**
 * Kotlin-side mirror of Python's built-in `SyntaxError`, a subclass of
 * `Exception` (which has no Kotlin class of its own, so this extends [PyException]).
 *
 * [PyException.fromCurrentError] builds this class for a raised `SyntaxError` and for a Python class
 * derived from it (the nearest built-in class in the type's MRO decides, [BuiltinExceptions]), so
 * Kotlin can `catch (PySyntaxError)`. [type] is the raised object's actual Python type.
 */
open class PySyntaxError(
    errMsg: String,
    type: PyType? = null,
    value: PyObject? = null,
    traceback: PyTraceback? = null,
    context: PyException? = null,
    cause: PyException? = null,
) : PyException(errMsg, type, value, traceback, context, cause) {
    /** `SyntaxError.filename`. Read from [value] when asked. */
    val filename: String?
        get() = value?.attrAsStringOrNull("filename")

    /** `SyntaxError.lineno`. Read from [value] when asked. */
    val lineNumber: Int?
        get() = value?.attrAsIntOrNull("lineno")

    /** `SyntaxError.offset`, the column. Read from [value] when asked. */
    val offset: Int?
        get() = value?.attrAsIntOrNull("offset")

    /** `SyntaxError.text`, the offending source line. Read from [value] when asked. */
    val text: String?
        get() = value?.attrAsStringOrNull("text")

    companion object {
        /** The Python type name this Kotlin class mirrors. */
        const val PYTHON_TYPE_NAME: String = "SyntaxError"
    }
}
