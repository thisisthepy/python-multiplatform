package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback

/**
 * Kotlin-side mirror of Python's built-in `ValueError`.
 * See [PyTypeError] for why [PyException.type] is left unresolved here.
 */
class PyValueError(
    errMsg: String,
    value: PyObject? = null,
    traceback: PyTraceback? = null,
) : PyException(errMsg, type = null, value = value, traceback = traceback) {
    companion object {
        /** The Python type name this Kotlin class mirrors. */
        const val PYTHON_TYPE_NAME: String = "ValueError"
    }
}
