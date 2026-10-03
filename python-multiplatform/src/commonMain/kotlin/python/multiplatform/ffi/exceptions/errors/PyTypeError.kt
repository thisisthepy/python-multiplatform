package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback

/**
 * Kotlin-side mirror of Python's built-in `TypeError`.
 *
 * `type` is left `null` at construction time -- resolving the actual
 * `PyType` for `TypeError` requires a live interpreter (it must be looked
 * up from `builtins`), which this constructor deliberately avoids doing
 * eagerly. Prefer [PyException.fromCurrentError] when the exception is
 * being built from a real Python error indicator, which can populate
 * [PyException.type] properly.
 */
class PyTypeError(
    errMsg: String,
    value: PyObject? = null,
    traceback: PyTraceback? = null,
) : PyException(errMsg, type = null, value = value, traceback = traceback) {
    companion object {
        /** The Python type name this Kotlin class mirrors. */
        const val PYTHON_TYPE_NAME: String = "TypeError"
    }
}
