package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A throwable whose message can change after it was thrown: what tells a live read from a copy. */
class RewordableFailure(var text: String) : RuntimeException() {
    override val message: String get() = text
}

/** What the tests throw, kept so identity can be compared with what comes back. */
object ThrowableProxyFragment : FunctionTableFragment {
    override val moduleName: String = "test_throwable_proxy"

    var failure: Throwable = IllegalStateException("unset")

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "throwproxy.fail",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
        ) { throw failure },
        ExposedCallable(
            name = "throwproxy.same",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.BOOLEAN,
            paramNames = listOf("candidate"),
            paramTypeNames = listOf("kotlin.Throwable"),
        ) { args -> args[0] === failure },
    )
}

/**
 * SPEC U-2, #164 item 6 (owner decision 2026-11-22): the Kotlin throwable on `__cause__` is a Python
 * exception that is the proxy of the original object. Its members are read from that object when
 * they are asked for, it crosses back to Kotlin as the same instance, and its root is released when
 * the Python exception dies.
 *
 * Red before: `PyException.kotlinThrowable` does not exist (the file does not compile); with that
 * line removed, `__cause__` is a plain `RuntimeError` with no `message`, `cause` or handle.
 */
class KotlinThrowableProxyTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ThrowableProxyFragment))
    }

    @AfterTest
    fun cleanup() {
        if (Python3.isInitialized) {
            Python3.exec("globals().pop('_tp_e', None)\nimport gc as _tp_gc\n_tp_gc.collect()\ndel _tp_gc")
        }
        UpcallTable.clear()
        ThrowableProxyFragment.failure = IllegalStateException("unset")
    }

    /** Raises [failure] through the published entry points and leaves the Python exception in `_tp_e`. */
    private fun raiseIntoPython(failure: Throwable) {
        ThrowableProxyFragment.failure = failure
        assertTrue(UpcallBootstrap.publishToGlobals(), "publishToGlobals() reported failure")
        Python3.exec(
            """
            _tp_e = None
            try:
                _pm_invoke(_pm_resolve(b'throwproxy.fail'), ())
            except RuntimeError as _tp_caught:
                _tp_e = _tp_caught
            """.trimIndent(),
        )
        assertEquals("False", py("_tp_e is None"), "the Kotlin failure did not reach Python")
    }

    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    @Test
    fun theCauseIsAnExceptionThatIsTheProxyOfTheKotlinThrowable() = PythonTestFixture.withInterpreter {
        raiseIntoPython(IllegalStateException("outer", IllegalArgumentException("inner")))
        // What every existing caller reads stays as it was.
        assertEquals("outer", py("str(_tp_e)"))
        assertEquals("RuntimeError", py("type(_tp_e).__name__"))
        assertEquals("IllegalStateException: outer", py("str(_tp_e.__cause__)"))
        assertEquals("IllegalArgumentException: inner", py("str(_tp_e.__cause__.__cause__)"))
        // The cause is the proxy: an exception, a RuntimeError, and an owner of a handle.
        assertEquals("KotlinThrowable", py("type(_tp_e.__cause__).__name__"))
        assertEquals("python_multiplatform", py("type(_tp_e.__cause__).__module__"))
        assertEquals("True", py("isinstance(_tp_e.__cause__, RuntimeError)"))
        assertEquals("True", py("isinstance(_tp_e.__cause__._pm_handle, int) and _tp_e.__cause__._pm_handle > 0"))
        assertEquals("()", py("_tp_e.__cause__.args"), "the handle must not show up as the exception's arguments")
    }

    @Test
    fun membersAreReadFromTheOriginalThrowable() = PythonTestFixture.withInterpreter {
        raiseIntoPython(IllegalStateException("outer", IllegalArgumentException("inner")))
        assertEquals("outer", py("_tp_e.__cause__.message"))
        assertEquals("inner", py("_tp_e.__cause__.cause.message"))
        assertEquals("KotlinThrowable", py("type(_tp_e.__cause__.cause).__name__"))
        assertEquals("True", py("_tp_e.__cause__.cause.cause is None"))
        assertEquals("True", py("'IllegalStateException' in _tp_e.__cause__.stackTraceToString()"))
        assertEquals(
            "True",
            py("_tp_e.__cause__.stack_trace_to_string() == _tp_e.__cause__.stackTraceToString()"),
            "the snake_case name must answer the same member (AGENTS 12.1)",
        )
    }

    @Test
    fun aMessageWithNoTextIsNone() = PythonTestFixture.withInterpreter {
        raiseIntoPython(IllegalStateException())
        assertEquals("True", py("_tp_e.__cause__.message is None"))
        assertEquals("IllegalStateException", py("str(_tp_e.__cause__)"))
    }

    @Test
    fun nothingIsCopiedAMemberShowsWhatTheThrowableSaysNow() = PythonTestFixture.withInterpreter {
        val failure = RewordableFailure("first")
        raiseIntoPython(failure)
        assertEquals("first", py("_tp_e.__cause__.message"))
        failure.text = "second"
        assertEquals("second", py("_tp_e.__cause__.message"), "the message was copied when the exception was built")
        assertEquals("RewordableFailure: second", py("str(_tp_e.__cause__)"))
    }

    @Test
    fun passedBackToKotlinItIsTheSameInstance() = PythonTestFixture.withInterpreter {
        val inner = IllegalArgumentException("inner")
        val failure = IllegalStateException("outer", inner)
        raiseIntoPython(failure)
        // The handle is what an owner is worth on the wire (`_pm_unwrap`, the binding layer).
        assertEquals("True", py("_pm_invoke(_pm_resolve(b'throwproxy.same'), (_tp_e.__cause__._pm_handle,))"))
        assertEquals("False", py("_pm_invoke(_pm_resolve(b'throwproxy.same'), (_tp_e.__cause__.cause._pm_handle,))"))
    }

    @Test
    fun aPyExceptionBuiltFromItAnswersTheOriginalThrowable() = PythonTestFixture.withInterpreter {
        val inner = IllegalArgumentException("inner")
        val failure = IllegalStateException("outer", inner)
        ThrowableProxyFragment.failure = failure
        assertTrue(UpcallBootstrap.publishToGlobals(), "publishToGlobals() reported failure")
        val result = UpcallTrampoline.invoke(UpcallTable.resolve("throwproxy.fail").raw, UpcallTrampoline.NULL)
        assertEquals(0L, result)
        val raised = PyException.fromCurrentError()
        assertNotNull(raised)
        assertNull(raised.kotlinThrowable, "the RuntimeError itself is not a proxy")
        val cause = assertNotNull(raised.cause as? PyException, "expected __cause__")
        assertSame(failure, cause.kotlinThrowable, "the proxy must give back the original instance")
        val nested = assertNotNull(cause.cause as? PyException, "expected __cause__.__cause__")
        assertSame(inner, nested.kotlinThrowable)
    }

    @Test
    fun anOrdinaryPythonExceptionIsNotAKotlinThrowable() = PythonTestFixture.withInterpreter {
        val raised = try {
            Python3.exec("raise ValueError('plain')")
            null
        } catch (e: PyException) {
            e
        }
        assertNotNull(raised)
        assertNull(raised.kotlinThrowable)
    }

    @Test
    fun theRootIsReleasedWhenThePythonExceptionDies() = PythonTestFixture.withInterpreter {
        assertTrue(UpcallBootstrap.publishToGlobals(), "publishToGlobals() reported failure")
        Python3.exec("import gc as _tp_gc\n_tp_gc.collect()")
        val before = HandleTable.liveCount
        raiseIntoPython(IllegalStateException("outer", IllegalArgumentException("inner")))
        assertEquals(before + 2, HandleTable.liveCount, "one root per Kotlin throwable of the chain while the exception lives")
        Python3.exec("_tp_e = None\n_tp_gc.collect()\ndel _tp_gc")
        assertEquals(before, HandleTable.liveCount, "the roots must be released with the Python exception")
    }

    @Test
    fun aMemberReadAfterTheRootWasReleasedIsAPythonErrorNotACrash() = PythonTestFixture.withInterpreter {
        raiseIntoPython(IllegalStateException("outer"))
        Python3.exec(
            """
            _tp_c = _tp_e.__cause__
            _tp_c.__del__()
            try:
                _tp_c.message
                _tp_seen = 'no error'
            except ValueError as _tp_x:
                _tp_seen = str(_tp_x)
            """.trimIndent(),
        )
        assertEquals("this Kotlin throwable was already released", py("_tp_seen"))
        assertEquals("<Kotlin throwable>", py("str(_tp_c)"))
        Python3.exec("del _tp_c, _tp_seen")
    }
}
