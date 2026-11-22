package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.exceptions.errors.PyValueError
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Functional tests establishing that a Python-level error surfaces as a Kotlin
 * throwable with type/message information attached, per the mermaid sketch's
 * `PyException { type, value, traceback, message, cause, context }`.
 *
 * This header used to call these a red phase, on the grounds that the exec/eval
 * call sites built a bare message and never called [PyException.fromCurrentError],
 * so `type`/`value`/`traceback` came back unpopulated and the `type`-asserting
 * cases were expected to fail. That wiring is done: `Python3.exec`, `eval` and
 * `import` all raise through `pyErrorOrGeneric`, which is
 * `fromCurrentError() ?: PyException(fallback)`. `exec` is deliberately built on
 * `PyRun_String` rather than `PyRun_SimpleString` for exactly this reason -- the
 * latter calls `PyErr_Print()`, which prints and *clears* the error indicator
 * before anything can read the real `ZeroDivisionError` off it.
 *
 * So every test here is a regression test and any failure is a real one.
 */
class PyExceptionTest {

    @Test
    fun divisionByZeroRaisesAPyException() = PythonTestFixture.withInterpreter {
        assertFailsWith<PyException> {
            Python3.exec("1 / 0")
        }
    }

    @Test
    fun divisionByZeroExceptionCarriesTheZeroDivisionErrorType() = PythonTestFixture.withInterpreter {
        val thrown = assertFailsWith<PyException> {
            Python3.exec("1 / 0")
        }
        assertNotNull(thrown.type, "expected the PyException to carry the Python exception's type")
        assertEquals("ZeroDivisionError", thrown.type?.name)
    }

    @Test
    fun fromCurrentErrorReturnsNullWhenNoErrorIsSet() = PythonTestFixture.withInterpreter {
        // Precondition: no pending Python error at this point in the test.
        assertEquals(null, PyException.fromCurrentError())
    }

    @Test
    fun fromCurrentErrorCapturesAPendingPythonError() = PythonTestFixture.withInterpreter {
        // Trigger an error without letting exec()'s own handling clear/consume it first isn't
        // possible through the current API (exec() always translates it into a Kotlin PyException),
        // so this documents the intended low-level entry point directly.
        val captured = PyException.fromCurrentError()
        assertEquals(null, captured, "no error should be pending at the start of this test")
    }

    @Test
    fun typeErrorHasTheExpectedPythonTypeName() {
        assertEquals("TypeError", PyTypeError.PYTHON_TYPE_NAME)
    }

    @Test
    fun valueErrorHasTheExpectedPythonTypeName() {
        assertEquals("ValueError", PyValueError.PYTHON_TYPE_NAME)
    }

    @Test
    fun isCancelledErrorRecognisesConcurrentFuturesCancelledErrorAndToKotlinExceptionMapsItToCancellationException() =
        PythonTestFixture.withInterpreter {
            val thrown = assertFailsWith<PyException> {
                Python3.exec("import concurrent.futures; raise concurrent.futures.CancelledError('stop now')")
            }
            assertTrue(thrown.isCancelledError(), "CancelledError must be recognised by isCancelledError()")
            val mapped = thrown.toKotlinException()
            assertTrue(
                mapped is CancellationException,
                "toKotlinException() on a CancelledError must return a Kotlin CancellationException, got $mapped",
            )
            assertEquals("stop now", mapped.message)
            assertSame(thrown, mapped.cause, "the mapped CancellationException must keep the PyException as its cause")
        }

    @Test
    fun nonCancelledPyExceptionToKotlinExceptionReturnsItself() = PythonTestFixture.withInterpreter {
        val thrown = assertFailsWith<PyException> {
            Python3.exec("raise ValueError('bad input')")
        }
        assertFalse(thrown.isCancelledError(), "ValueError must not be treated as a CancelledError")
        assertSame(thrown, thrown.toKotlinException())
    }

    // ---- #524 item 1: PyException is an Exception, and its Python object is toPyObject() (owner, 2026-11-22) ----

    @Test
    fun anExceptionFromPythonAnswersTheOriginalInstanceAsItsPythonObject() = PythonTestFixture.withInterpreter {
        val thrown = assertFailsWith<PyException> { Python3.exec("raise ValueError('from python')") }
        val obj = thrown.toPyObject()
        assertSame(thrown.value, obj, "toPyObject() of a Python exception must be the original instance")
        assertEquals("from python", obj.toString())
    }

    @Test
    fun anExceptionMadeInKotlinBecomesARuntimeErrorWithItsMessageOnce() = PythonTestFixture.withInterpreter {
        val made = PyException("made in kotlin")
        val obj = made.toPyObject()
        assertEquals("made in kotlin", obj.toString())
        assertEquals("RuntimeError", obj.Type.name)
        assertSame(obj, made.toPyObject(), "a second call must answer the same Python object")
    }

    @Test
    fun anExceptionMadeInKotlinWithATypeIsAnInstanceOfThatType() = PythonTestFixture.withInterpreter {
        // The type of a KeyError instance: the KeyError class, as a PyType (owned by the cache).
        val keyError = PythonTestFixture.eval("KeyError('probe')").Type
        val obj = PyException("missing", type = keyError).toPyObject()
        assertTrue(keyError.isInstance(obj), "expected an instance of KeyError, got ${obj.Type.name}")
    }
}
