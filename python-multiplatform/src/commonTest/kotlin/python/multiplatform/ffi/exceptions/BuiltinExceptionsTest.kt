package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.BuiltinExceptions
import python.multiplatform.ffi.exceptions.errors.PyExceptionGroup
import python.multiplatform.ffi.exceptions.errors.PyFileNotFoundError
import python.multiplatform.ffi.exceptions.errors.PyKeyError
import python.multiplatform.ffi.exceptions.errors.PyLookupError
import python.multiplatform.ffi.exceptions.errors.PyModuleNotFoundError
import python.multiplatform.ffi.exceptions.errors.PyOSError
import python.multiplatform.ffi.exceptions.errors.PySyntaxError
import python.multiplatform.ffi.exceptions.errors.PySystemExit
import python.multiplatform.ffi.exceptions.errors.PyUnicodeDecodeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * #524: a raised Python exception becomes the Kotlin class of its built-in Python class (the baseline's
 * `exceptions/errors/` skeleton, which had `PyTypeError` and `PyValueError` as empty classes). Red
 * before: [BuiltinExceptions] and the classes other than `PyTypeError` / `PyValueError` did not exist,
 * and `fromCurrentError` built a plain [PyException] for every Python exception.
 */
class BuiltinExceptionsTest {

    private fun raised(source: String): PyException = assertFailsWith<PyException> { Python3.exec(source) }

    @Test
    fun aKeyErrorIsAPyKeyErrorAndAPyLookupError() = PythonTestFixture.withInterpreter {
        val error = raised("raise KeyError('k')")
        assertIs<PyKeyError>(error)
        assertIs<PyLookupError>(error)
        assertEquals("KeyError", error.type?.name)
    }

    @Test
    fun aPythonSubclassBecomesItsNearestBuiltinClass() = PythonTestFixture.withInterpreter {
        val error = raised("class _TeMine(KeyError):\n    pass\nraise _TeMine('m')")
        assertIs<PyKeyError>(error)
        assertEquals("_TeMine", error.type?.name)
        val plain = raised("class _TePlain(Exception):\n    pass\nraise _TePlain()")
        assertEquals(PyException::class, plain::class, "a class derived only from Exception stays a plain PyException")
    }

    @Test
    fun everyBuiltinExceptionClassHasAKotlinClass() = PythonTestFixture.withInterpreter {
        val names = PythonTestFixture.eval(
            "'\\n'.join(sorted(n for n, v in vars(__import__('builtins')).items() " +
                "if isinstance(v, type) and issubclass(v, BaseException) and n == v.__name__ " +
                "and n not in ('BaseException', 'Exception', '_IncompleteInputError')))",
        ).use { it.toString().lines().toSet() }
        assertEquals(names, BuiltinExceptions.names)
    }

    @Test
    fun eachBuiltinExceptionRaisesAsItsOwnKotlinClass() = PythonTestFixture.withInterpreter {
        val needsArguments = setOf(
            "UnicodeDecodeError", "UnicodeEncodeError", "UnicodeTranslateError", "BaseExceptionGroup", "ExceptionGroup",
        )
        for (name in BuiltinExceptions.names - needsArguments) {
            val error = raised("raise $name()")
            assertEquals("Py$name", error::class.simpleName, "raise $name()")
        }
        assertIs<PyUnicodeDecodeError>(raised("b'\\xff'.decode('utf-8')"))
        assertIs<PyExceptionGroup>(raised("raise ExceptionGroup('g', [ValueError()])"))
    }

    @Test
    fun theDetailsOfOsSyntaxImportAndExitErrorsAreReadable() = PythonTestFixture.withInterpreter {
        val missing = raised("raise FileNotFoundError(2, 'No such file', 'x.txt')")
        assertIs<PyFileNotFoundError>(missing)
        assertIs<PyOSError>(missing)
        assertEquals(2, missing.errno)
        assertEquals("No such file", missing.strerror)
        assertEquals("x.txt", missing.filename)

        val syntax = raised("compile('x = (', 'f.py', 'exec')")
        assertIs<PySyntaxError>(syntax)
        assertEquals("f.py", syntax.filename)
        assertEquals(1, syntax.lineNumber)

        val notFound = raised("import _te_no_such_module_524")
        assertIs<PyModuleNotFoundError>(notFound)
        assertEquals("_te_no_such_module_524", notFound.moduleName)

        val exit = raised("raise SystemExit(3)")
        assertIs<PySystemExit>(exit)
        assertEquals("3", exit.code?.use { it.toString() })
    }
}
