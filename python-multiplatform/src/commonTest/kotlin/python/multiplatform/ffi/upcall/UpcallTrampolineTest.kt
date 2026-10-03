package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.multiplatform.ffi.withGIL
import python.native.ffi.Py_DecRef
import python.native.ffi.toNativePointer
import python.native.ffi.toRawValue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A Kotlin class reached as a receiver, so the [CallableKind.METHOD] path has something to resolve
 * out of [HandleTable].
 */
class TrampolineTarget(val label: String) {
    fun combine(times: Long, suffix: String): String = "$label*$times$suffix"
}

/**
 * One entry per [TypeTag], written the way the generator emits them. The point is that the
 * trampoline reads only [ExposedCallable.paramTypes]/[ExposedCallable.returnType] -- there is no
 * runtime type to read on Kotlin/Native -- so this fragment is the whole input to the marshaller.
 */
object TrampolineFragment : FunctionTableFragment {
    override val moduleName: String = "test_trampoline"

    /**
     * A single long-lived Python object returned by `trampoline.shared`.
     *
     * Held in a field on purpose: the wrapper's own reference is then a *constant*, so a refcount
     * measured across one call moves by exactly what the return path added and by nothing else.
     * Taking the same measurement over a `PyObject` the trampoline built from an argument cannot
     * separate the two increments.
     */
    var shared: PyObject? = null

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "trampoline.add",
            arity = 2,
            paramTypes = listOf(TypeTag.INT, TypeTag.INT),
            returnType = TypeTag.INT,
        ) { args -> (args[0] as Long) + (args[1] as Long) },
        ExposedCallable(
            name = "trampoline.scale",
            arity = 2,
            paramTypes = listOf(TypeTag.FLOAT, TypeTag.FLOAT),
            returnType = TypeTag.FLOAT,
        ) { args -> (args[0] as Double) * (args[1] as Double) },
        ExposedCallable(
            name = "trampoline.negate",
            arity = 1,
            paramTypes = listOf(TypeTag.BOOLEAN),
            returnType = TypeTag.BOOLEAN,
        ) { args -> !(args[0] as Boolean) },
        ExposedCallable(
            name = "trampoline.greet",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
        ) { args -> "Hello, ${args[0] as String}!" },
        ExposedCallable(
            name = "trampoline.reverseBytes",
            arity = 1,
            paramTypes = listOf(TypeTag.BYTES),
            returnType = TypeTag.BYTES,
        ) { args -> (args[0] as ByteArray).reversedArray() },
        ExposedCallable(
            name = "trampoline.discard",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.UNIT,
        ) { Unit },
        ExposedCallable(
            name = "trampoline.Target.<init>",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.CONSTRUCTOR,
        ) { args -> TrampolineTarget(args[0] as String) },
        ExposedCallable(
            name = "trampoline.Target.combine",
            arity = 2,
            paramTypes = listOf(TypeTag.INT, TypeTag.STRING),
            returnType = TypeTag.STRING,
            kind = CallableKind.METHOD,
        ) { args -> (args[0] as TrampolineTarget).combine(args[1] as Long, args[2] as String) },
        /** A Kotlin function whose parameter is a *Python* object rather than a Kotlin one. */
        ExposedCallable(
            name = "trampoline.describe",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.STRING,
        ) { args -> if (args[0] == null) "None" else (args[0] as PyObject).toString() },
        /** Hands back [shared], so the return path's new-reference rule can be measured alone. */
        ExposedCallable(
            name = "trampoline.shared",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
        ) { shared },
        ExposedCallable(
            name = "trampoline.explode",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
        ) { error("deliberate Kotlin failure") },
    )

    override fun classes(): List<ReflectedClass> = listOf(
        ReflectedClass(
            name = "trampoline.Target",
            memberNames = listOf("trampoline.Target.<init>", "trampoline.Target.combine"),
        ),
    )
}

/**
 * ROADMAP §13's gap: the table carries arity and per-argument [TypeTag]s, and until now nothing
 * marshalled them. These drive [UpcallTrampoline] directly with argument tuples built by the
 * interpreter, which is the platform-independent half; `UpcallEntryTest` (`commonTest`) drives the
 * same code through each platform's real published entry point from Python.
 */
class UpcallTrampolineTest {

    /**
     * Holds every tuple built during a test.
     *
     * Not tidiness: [tuple] hands out a raw pointer, and the wrapper that owns the only reference
     * to it would otherwise be unreachable the instant the pointer is read -- a cleaner firing
     * mid-test would free the tuple the trampoline is about to walk.
     */
    private val keepAlive = mutableListOf<PyObject>()

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(TrampolineFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        keepAlive.clear()
    }

    /** Builds the argument tuple the way the boundary receives it: a real Python tuple. */
    private fun tuple(expression: String): Long {
        val built = PythonTestFixture.eval(expression)
        keepAlive.add(built)
        return built.pointer.toRawValue()
    }

    /** Calls through the trampoline and renders the returned **new** reference as `str()`. */
    private fun callToString(name: String, argsExpression: String): String {
        val resultRaw = UpcallTrampoline.invoke(UpcallTable.resolve(name).raw, tuple(argsExpression))
        assertNotEquals(0L, resultRaw, "$name returned NULL; a Python error was set instead of a value")
        // The trampoline owes Python a new reference, so this wrapper adopts it rather than
        // taking a second one.
        val wrapper = PyObject(resultRaw.toNativePointer()!!, borrowed = false)
        return wrapper.toString()
    }

    @Test
    fun intArgumentsArriveAndTheIntResultComesBack() = PythonTestFixture.withInterpreter {
        assertEquals("7", callToString("trampoline.add", "(3, 4)"))
    }

    @Test
    fun floatArgumentsArriveAndTheFloatResultComesBack() = PythonTestFixture.withInterpreter {
        assertEquals("7.5", callToString("trampoline.scale", "(2.5, 3.0)"))
    }

    @Test
    fun booleanArgumentsMarshalToPythonBoolRatherThanInt() = PythonTestFixture.withInterpreter {
        // `True`, not `1`: TypeTag.BOOLEAN exists because Python's bool is a distinct type.
        assertEquals("True", callToString("trampoline.negate", "(False,)"))
    }

    @Test
    fun stringArgumentsCrossInBothDirections() = PythonTestFixture.withInterpreter {
        assertEquals("Hello, 파이썬!", callToString("trampoline.greet", "('파이썬',)"))
    }

    @Test
    fun bytesArgumentsCrossAsByteArrayIncludingNonUtf8AndEmbeddedNul() = PythonTestFixture.withInterpreter {
        // 0x00 and 0xFF are exactly what a NUL-terminated, UTF-8-decoding read would destroy.
        assertEquals("b'\\xff\\x00\\x01'", callToString("trampoline.reverseBytes", "(b'\\x01\\x00\\xff',)"))
    }

    @Test
    fun aUnitReturningEntryYieldsNoneRatherThanAnObjectHandle() = PythonTestFixture.withInterpreter {
        assertEquals("None", callToString("trampoline.discard", "(1,)"))
    }

    @Test
    fun aConstructorReturnsAnObjectHandleAndAMethodCallResolvesItBack() = PythonTestFixture.withInterpreter {
        val constructRaw = UpcallTrampoline.invoke(
            UpcallTable.resolve("trampoline.Target.<init>").raw,
            tuple("('kotlin',)"),
        )
        assertNotEquals(0L, constructRaw)
        val handleObject = PyObject(constructRaw.toNativePointer()!!, borrowed = false)
        val handleRaw = handleObject.toString().toLong()

        // The handle roots the Kotlin instance; that is the only thing keeping it reachable.
        val resolved = HandleTable.resolve(ObjectReference(handleRaw))
        assertTrue(resolved is TrampolineTarget)
        assertEquals("kotlin", resolved.label)

        // args[0] of a METHOD is the receiver, and it crosses as that same handle integer.
        assertEquals(
            "kotlin*3!",
            callToString("trampoline.Target.combine", "($handleRaw, 3, '!')"),
        )

        assertTrue(UpcallTrampoline.releaseObject(handleRaw) != 0)
        assertNull(HandleTable.resolve(ObjectReference(handleRaw)))
        assertFalse(UpcallTrampoline.releaseObject(handleRaw) != 0, "a double release must be a no-op")
    }

    @Test
    fun aPythonObjectArgumentReachesKotlinAsAPyObjectWrapper() = PythonTestFixture.withInterpreter {
        assertEquals("[1, 2, 3]", callToString("trampoline.describe", "([1, 2, 3],)"))
    }

    @Test
    fun noneCrossesAsKotlinNull() = PythonTestFixture.withInterpreter {
        assertEquals("None", callToString("trampoline.describe", "(None,)"))
    }

    @Test
    fun theArgumentTupleLendsItsItemsAndTheTrampolineMustNotConsumeThem() =
        PythonTestFixture.withInterpreter {
            // The bug this pins is the one that crashed this repo twice: wrapping a *borrowed*
            // pointer with `borrowed = false` gives one reference back per call that was never
            // taken. Over 200 calls the object would be freed while `holder` still names it, so
            // the refcount here would fall (or the process would die), never rise.
            python.multiplatform.ffi.Python3.exec("_pm_holder = [1, 2, 3]")
            // One tuple, reused: its own reference to `_pm_holder` is a constant offset, so the
            // count below moves only if the trampoline moves it.
            val args = tuple("(_pm_holder,)")
            val before = PythonTestFixture.eval("__import__('sys').getrefcount(_pm_holder)").toString().toLong()

            val handle = UpcallTable.resolve("trampoline.describe").raw
            repeat(200) {
                val r = UpcallTrampoline.invoke(handle, args)
                assertNotEquals(0L, r)
                withGIL { Py_DecRef(r.toNativePointer()!!) }
            }

            val after = PythonTestFixture.eval("__import__('sys').getrefcount(_pm_holder)").toString().toLong()
            // Upwards is legitimate and unbounded here: each call's `PyObject(_, borrowed = true)`
            // wrapper holds its own reference until a cleaner reclaims it, and nothing forces one.
            // Downwards is the bug, and it is one per call.
            assertTrue(
                after >= before,
                "200 calls dropped the refcount of a borrowed argument by ${before - after}",
            )
            // ...and the object survived intact, which a run of over-releases would not have left it.
            assertEquals("[1, 2, 3]", callToString("trampoline.describe", "(_pm_holder,)"))
        }

    @Test
    fun anObjectReturnedToPythonCarriesANewReferenceRatherThanABorrowedOne() =
        PythonTestFixture.withInterpreter {
            // Python takes ownership of whatever comes back, so the count must be exactly one
            // higher afterwards. Handing back the wrapper's pointer without incrementing leaves
            // it flat, and the caller's decref then frees an object Kotlin still holds.
            python.multiplatform.ffi.Python3.exec("_pm_returned = {'k': 'v'}")
            TrampolineFragment.shared = PythonTestFixture.eval("_pm_returned")
            try {
                val refcount = { PythonTestFixture.eval("__import__('sys').getrefcount(_pm_returned)").toString().toLong() }
                val before = refcount()

                val resultRaw = UpcallTrampoline.invoke(UpcallTable.resolve("trampoline.shared").raw, tuple("()"))
                assertNotEquals(0L, resultRaw)
                assertEquals(before + 1, refcount(), "the result must carry a reference Python owns")

                withGIL { Py_DecRef(resultRaw.toNativePointer()!!) }
                assertEquals(before, refcount(), "giving that reference back must land exactly where it started")
                assertEquals("{'k': 'v'}", PythonTestFixture.eval("_pm_returned").toString())
            } finally {
                TrampolineFragment.shared = null
            }
        }

    @Test
    fun aKotlinExceptionBecomesAPythonErrorRatherThanEscapingIntoC() =
        PythonTestFixture.withInterpreter {
            // A Throwable crossing back into C terminates the process on Native and on a Panama
            // upcall stub. The only channel out is NULL plus a set error indicator.
            val resultRaw = UpcallTrampoline.invoke(UpcallTable.resolve("trampoline.explode").raw, tuple("()"))
            assertEquals(0L, resultRaw)
            val message = python.multiplatform.ffi.exceptions.PyException.fromCurrentError()?.message
            assertTrue(
                message?.contains("deliberate Kotlin failure") == true,
                "the Kotlin failure did not reach Python's error indicator: $message",
            )
        }

    @Test
    fun aWrongArgumentCountIsRejectedBeforeTheKotlinTargetRuns() =
        PythonTestFixture.withInterpreter {
            val resultRaw = UpcallTrampoline.invoke(UpcallTable.resolve("trampoline.add").raw, tuple("(1,)"))
            assertEquals(0L, resultRaw)
            val message = python.multiplatform.ffi.exceptions.PyException.fromCurrentError()?.message
            assertTrue(
                message?.contains("2 argument") == true,
                "expected an arity complaint naming the declared count, got: $message",
            )
        }

    @Test
    fun aStaleCallableHandleIsRejectedRatherThanCallingWhateverTookItsIndex() =
        PythonTestFixture.withInterpreter {
            val handle = UpcallTable.resolve("trampoline.add").raw
            UpcallTable.clear()
            UpcallTable.install(listOf(TrampolineFragment))

            val resultRaw = UpcallTrampoline.invoke(handle, tuple("(3, 4)"))
            assertEquals(0L, resultRaw)
            assertTrue(python.multiplatform.ffi.exceptions.PyException.fromCurrentError() != null)
        }
}
