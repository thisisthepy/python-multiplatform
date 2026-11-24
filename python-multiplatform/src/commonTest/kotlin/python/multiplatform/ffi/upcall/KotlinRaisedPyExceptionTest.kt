package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.exceptions.errors.PyKeyError
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.exceptions.errors.PyValueError
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kotlin functions that throw a [PyException] made in Kotlin, with no live Python value. */
object KotlinRaisedPyExceptionFragment : FunctionTableFragment {
    override val moduleName: String = "test_kotlin_raised_py_exception"

    var failure: Throwable = IllegalStateException("unset")

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "kraise.fail",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
        ) { throw failure },
    )
}

/**
 * SPEC U-2 (#524, agent proposal): a [PyException] made in Kotlin is raised in Python as its own
 * Python class, the built-in class its Kotlin class mirrors (O-3a), rather than as a `RuntimeError`.
 * Without this, `throw PyTypeError(...)` from a Kotlin function (the collections' element conversion
 * does exactly that) reached Python as a `RuntimeError`, and `except TypeError` missed it.
 *
 * Red before: every case below raises `RuntimeError`.
 */
class KotlinRaisedPyExceptionTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(KotlinRaisedPyExceptionFragment))
    }

    @AfterTest
    fun cleanup() {
        if (Python3.isInitialized) Python3.exec("globals().pop('_kr', None)")
        UpcallTable.clear()
        KotlinRaisedPyExceptionFragment.failure = IllegalStateException("unset")
    }

    /** Raises [failure] through the published entry points and records `(class name, str, cause)` in `_kr`. */
    private fun raise(failure: Throwable): String {
        KotlinRaisedPyExceptionFragment.failure = failure
        assertTrue(UpcallBootstrap.publishToGlobals(), "publishToGlobals() reported failure")
        Python3.exec(
            """
            _kr = None
            try:
                _pm_invoke(_pm_resolve(b'kraise.fail'), ())
            except BaseException as _kr_e:
                _kr = (type(_kr_e).__name__, str(_kr_e), None if _kr_e.__cause__ is None else str(_kr_e.__cause__))
            """.trimIndent(),
        )
        return PythonTestFixture.eval("repr(_kr)").toString()
    }

    @Test
    fun aMirrorClassIsRaisedAsTheBuiltinClassItMirrors() = PythonTestFixture.withInterpreter {
        assertEquals("('TypeError', 'bad element', None)", raise(PyTypeError("bad element")))
        assertEquals("('ValueError', 'bad value', None)", raise(PyValueError("bad value")))
        // KeyError's str() is the repr of its argument.
        assertEquals("('KeyError', \"'missing'\", None)", raise(PyKeyError("missing")))
    }

    @Test
    fun anExceptClauseForTheBuiltinClassCatchesIt() = PythonTestFixture.withInterpreter {
        KotlinRaisedPyExceptionFragment.failure = PyKeyError("missing")
        assertTrue(UpcallBootstrap.publishToGlobals(), "publishToGlobals() reported failure")
        Python3.exec(
            """
            try:
                _pm_invoke(_pm_resolve(b'kraise.fail'), ())
                _kr = 'not raised'
            except LookupError:
                _kr = 'caught as LookupError'
            """.trimIndent(),
        )
        assertEquals("caught as LookupError", PythonTestFixture.eval("_kr").toString())
    }

    @Test
    fun anExplicitTypeWins() = PythonTestFixture.withInterpreter {
        // The type of an IndexError instance: the IndexError class.
        val indexError = PythonTestFixture.eval("IndexError('probe')").Type
        assertEquals("('IndexError', 'out of range', None)", raise(PyException("out of range", type = indexError)))
    }

    @Test
    fun aKotlinCauseIsKeptOnDunderCause() = PythonTestFixture.withInterpreter {
        // A cause with no live Python value crosses as the proxy of the Kotlin object (U-2), whose str()
        // is "<KotlinSimpleClassName>: <message>".
        val failure = PyValueError("bad value", cause = PyException("why"))
        assertEquals("('ValueError', 'bad value', 'PyException: why')", raise(failure))
    }

    @Test
    fun aPlainPyExceptionWithNoTypeIsStillARuntimeError() = PythonTestFixture.withInterpreter {
        assertTrue(raise(PyException("plain")).startsWith("('RuntimeError', 'plain', "))
    }
}
