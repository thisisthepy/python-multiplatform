package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.toNativePointer
import python.native.ffi.toRawValue
import kotlin.coroutines.Continuation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The part of the async boundary that works on **every** target, pinned where every target compiles
 * and runs it.
 *
 * `AsyncUpcallDeliveryTest` is `desktopTest` because delivery needs a second thread: the upcall has
 * to arrive from inside a coroutine while a Kotlin thread resolves the `Future`. That shape is not
 * portable. What is portable is the half of `docs/design/upcall.md` §5.2 that the doc calls the
 * fast path -- `suspend` is a signature, not a promise to suspend, and a body that never reaches a
 * suspension point must cross **without asyncio being involved at all**.
 *
 * That claim is worth a portable test precisely because of what was measured below.
 *
 * ### wasmJs: measured, and worse than §8.5 predicted
 *
 * §4 and §8.5 expected candidate (C) to fail on wasmJs by *deadlock* -- Kotlin's resumption arriving
 * as a JS microtask that `run_until_complete` never yields to. That is not what happens, and the
 * real answer arrives much earlier than the await:
 *
 * ```
 * import json        OK        import math         OK
 * import select      OK        import socket       OK
 * import contextvars OK        import selectors    RuntimeError: unreachable  <- traps
 * import asyncio.events / asyncio.base_events / asyncio   traps, through selectors
 * ```
 *
 * `import asyncio` **traps the wasm instance**, killing the Node process rather than raising a
 * Python exception. So on wasmJs candidate (C) is not slow, or deadlocked, or degraded: its first
 * step is unavailable, and the failure is not catchable from Kotlin or from Python. The deadlock
 * §8.5 reasoned about is unreachable because nothing gets that far.
 *
 * The consequence for this file is direct: **the "suspended with no running loop" case cannot live
 * in `commonTest`.** It reaches `asyncio.get_running_loop`, so on wasmJs it takes the whole suite
 * down with it -- observed, which is how the trap above was found. It stays in `AsyncUpcallDeliveryTest`
 * (desktop), and what is asserted here is only what is genuinely portable: the paths that return
 * before any import happens. Both of those matter more than the failing one, because they are the
 * ones an application on wasmJs can actually rely on.
 */
class AsyncUpcallPortabilityTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(PortableAsyncFragment))
        PortableAsyncFragment.parked = null
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        PortableAsyncFragment.parked = null
    }

    @Test
    fun aSuspendingEntryThatNeverSuspendsAnswersWithTheRealValueOnEveryTarget() =
        PythonTestFixture.withInterpreter {
            // No event loop exists anywhere in this process, and on wasmJs one could not exist at
            // all. If the boundary consulted asyncio for an already-complete call, this would fail
            // here -- on wasmJs by trapping. A pass is the fast path of §5 holding on whichever
            // target is running this.
            val args = PythonTestFixture.eval("(21,)")
            val raw = UpcallTrampoline.invoke(UpcallTable.resolve("portable.doubleNow").raw, args.pointer.toRawValue())
            assertNotEquals(0L, raw, "the fast path returned NULL; a Python error was set instead of a value")

            val result = PyObject(raw.toNativePointer()!!, borrowed = false)
            assertEquals("42", result.toString())
            assertEquals(
                "int",
                result.getAttr("__class__").getAttr("__name__").toString(),
                "an already-complete suspending call must marshal like a synchronous one",
            )
            assertNull(PortableAsyncFragment.parked, "nothing should have suspended")
        }

    @Test
    fun aSuspendingEntryThatFailsBeforeSuspendingRaisesWithNoAsyncioInvolvedAtAll() =
        PythonTestFixture.withInterpreter {
            // The other branch that returns before any import: a failure on the fast path leaves
            // through NULL exactly as a synchronous one does.
            val args = PythonTestFixture.eval("()")
            val raw = UpcallTrampoline.invoke(UpcallTable.resolve("portable.failNow").raw, args.pointer.toRawValue())
            assertEquals(0L, raw)

            // Reads and clears the indicator, so nothing downstream inherits it.
            val raised = PyException.fromCurrentError()
            assertTrue(
                raised?.message?.contains("portable boom") == true,
                "a failure before suspension has to reach Python's error indicator unchanged: ${raised?.message}",
            )
            assertNull(PortableAsyncFragment.parked, "nothing should have suspended")
        }
}

/**
 * Entries in the shape KSP emits for a `suspend fun`.
 *
 * Neither of the two exposed here ever suspends, which is what makes them safe on every target: a
 * body that reaches a real suspension point would send the boundary to `asyncio`, and see the class
 * note above for what that does on wasmJs. [parked] exists so the tests can assert that.
 */
object PortableAsyncFragment : FunctionTableFragment {

    override val moduleName: String = "test_portable_async"

    /** Set if anything ever suspends. Nothing here should. */
    var parked: Continuation<Long>? = null

    private suspend fun doubleNow(x: Long): Long = x * 2

    private suspend fun failNow(): Long = throw IllegalStateException("portable boom")

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "portable.doubleNow",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleNow(args[0] as Long) } },
        ExposedCallable(
            name = "portable.failNow",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { failNow() } },
    )
}
