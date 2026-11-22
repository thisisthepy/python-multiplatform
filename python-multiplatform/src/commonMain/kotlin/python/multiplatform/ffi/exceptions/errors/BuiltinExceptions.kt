package python.multiplatform.ffi.exceptions.errors

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.PyTraceback

/**
 * Which Kotlin class a raised Python exception becomes (#524: the baseline's `exceptions/errors/`
 * skeleton, one class per built-in exception).
 *
 * Every built-in exception class of CPython 3.15 has a `Py<Name>` mirror whose Kotlin superclass is
 * its Python base (`PyKeyError : PyLookupError`), so `catch (e: PyLookupError)` catches a `KeyError`
 * as `except LookupError` does. Three names have no class of their own: `BaseException` is
 * [PyException] itself, `Exception` has none (Kotlin has single inheritance, and the classes directly
 * under `Exception` extend [PyException]), and `_IncompleteInputError` is private to the REPL.
 * `ExceptionGroup`'s second base, `Exception`, is therefore not represented either.
 *
 * A Python class derived from a built-in one (`class MyError(KeyError)`) becomes the nearest built-in
 * class in its MRO; one derived only from `Exception` stays a plain [PyException].
 */
internal object BuiltinExceptions {
    private val factories: Map<String, (String, PyType?, PyObject?, PyTraceback?, PyException?, PyException?) -> PyException> = mapOf(
        "BaseExceptionGroup" to ::PyBaseExceptionGroup,
        "GeneratorExit" to ::PyGeneratorExit,
        "KeyboardInterrupt" to ::PyKeyboardInterrupt,
        "SystemExit" to ::PySystemExit,
        "ArithmeticError" to ::PyArithmeticError,
        "AssertionError" to ::PyAssertionError,
        "AttributeError" to ::PyAttributeError,
        "BufferError" to ::PyBufferError,
        "EOFError" to ::PyEOFError,
        "ImportError" to ::PyImportError,
        "LookupError" to ::PyLookupError,
        "MemoryError" to ::PyMemoryError,
        "NameError" to ::PyNameError,
        "OSError" to ::PyOSError,
        "ReferenceError" to ::PyReferenceError,
        "RuntimeError" to ::PyRuntimeError,
        "StopAsyncIteration" to ::PyStopAsyncIteration,
        "StopIteration" to ::PyStopIteration,
        "SyntaxError" to ::PySyntaxError,
        "SystemError" to ::PySystemError,
        "TypeError" to ::PyTypeError,
        "ValueError" to ::PyValueError,
        "Warning" to ::PyWarning,
        "BlockingIOError" to ::PyBlockingIOError,
        "BytesWarning" to ::PyBytesWarning,
        "ChildProcessError" to ::PyChildProcessError,
        "ConnectionError" to ::PyConnectionError,
        "DeprecationWarning" to ::PyDeprecationWarning,
        "EncodingWarning" to ::PyEncodingWarning,
        "ExceptionGroup" to ::PyExceptionGroup,
        "FileExistsError" to ::PyFileExistsError,
        "FileNotFoundError" to ::PyFileNotFoundError,
        "FloatingPointError" to ::PyFloatingPointError,
        "FutureWarning" to ::PyFutureWarning,
        "ImportCycleError" to ::PyImportCycleError,
        "ImportWarning" to ::PyImportWarning,
        "IndentationError" to ::PyIndentationError,
        "IndexError" to ::PyIndexError,
        "InterruptedError" to ::PyInterruptedError,
        "IsADirectoryError" to ::PyIsADirectoryError,
        "KeyError" to ::PyKeyError,
        "ModuleNotFoundError" to ::PyModuleNotFoundError,
        "NotADirectoryError" to ::PyNotADirectoryError,
        "NotImplementedError" to ::PyNotImplementedError,
        "OverflowError" to ::PyOverflowError,
        "PendingDeprecationWarning" to ::PyPendingDeprecationWarning,
        "PermissionError" to ::PyPermissionError,
        "ProcessLookupError" to ::PyProcessLookupError,
        "PythonFinalizationError" to ::PyPythonFinalizationError,
        "RecursionError" to ::PyRecursionError,
        "ResourceWarning" to ::PyResourceWarning,
        "RuntimeWarning" to ::PyRuntimeWarning,
        "SyntaxWarning" to ::PySyntaxWarning,
        "TimeoutError" to ::PyTimeoutError,
        "UnboundLocalError" to ::PyUnboundLocalError,
        "UnicodeError" to ::PyUnicodeError,
        "UnicodeWarning" to ::PyUnicodeWarning,
        "UserWarning" to ::PyUserWarning,
        "ZeroDivisionError" to ::PyZeroDivisionError,
        "BrokenPipeError" to ::PyBrokenPipeError,
        "ConnectionAbortedError" to ::PyConnectionAbortedError,
        "ConnectionRefusedError" to ::PyConnectionRefusedError,
        "ConnectionResetError" to ::PyConnectionResetError,
        "TabError" to ::PyTabError,
        "UnicodeDecodeError" to ::PyUnicodeDecodeError,
        "UnicodeEncodeError" to ::PyUnicodeEncodeError,
        "UnicodeTranslateError" to ::PyUnicodeTranslateError,
    )

    /** The built-in exception names that have a Kotlin class. */
    val names: Set<String> get() = factories.keys

    /**
     * The exception [PyException.fromCurrentError] returns for a raised object of [type]: the class of
     * the first built-in entry of `type.__mro__` that has one, or [PyException].
     */
    fun create(
        message: String,
        type: PyType?,
        value: PyObject?,
        traceback: PyTraceback?,
        context: PyException?,
        cause: PyException?,
    ): PyException {
        val factory = type?.let(::factoryFor)
            ?: return PyException(message, type, value, traceback, context, cause)
        return factory(message, type, value, traceback, context, cause)
    }

    private fun factoryFor(type: PyType) = try {
        type.mro.firstNotNullOfOrNull { entry ->
            factories[entry.name]?.takeIf { entry.isBuiltin() }
        }
    } catch (e: PyException) {
        // Reading the MRO of an exception's type should not fail; if it does, the exception being
        // reported still is, as a plain PyException.
        null
    }

    private fun PyType.isBuiltin(): Boolean = getAttrOrNull("__module__")?.use { it.toString() == "builtins" } ?: false
}
