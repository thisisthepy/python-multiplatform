package python.native.ffi

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.TrampolineFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The upcall contract every platform's address-publishing step must honour: Python builds a real
 * argument tuple, calls through the entry point *this platform actually published*, and reads the
 * result back.
 *
 * This used to be four near-identical copies -- `nativeTest` (iOS + androidNative),
 * `androidInstrumentedTest`, `wasmJsTest` and `desktopTest`'s `UpcallArgumentsTest` -- kept in sync
 * by hand. What varied between them was never the marshalling (that is `commonMain`'s
 * `UpcallTrampoline`, already covered by `UpcallTrampolineTest`, which drives it from Kotlin
 * directly) but only *how a name becomes a callable Python can hold*: a `PyMethodDef` bootstrap on
 * native/android, a ctypes shim over Panama stubs on desktop, a callable built and installed
 * straight from Kotlin on wasmJs (which has no separate published resolver -- only `pmp_invoke`
 * crosses through `call_indirect`). [bindUpcallOrNull] and [releaseUpcallHandle] are that one
 * remaining seam; everything below them is common.
 *
 * What is deliberately *not* here: [python.native.ffi.UpcallRawEntryPointTest]'s check that the
 * raw `pm_upcall_*` addresses are callable as C functions (native/androidNative-only -- there is no
 * comparable raw address on the other three targets), and the androidInstrumentedTest-only check
 * that an upcall from a `threading.Thread` reaches Kotlin on a pthread ART has never attached
 * (`UpcallThreadAttachTest` -- every other target reaches Kotlin without a runtime boundary at all,
 * so that scenario does not exist for them).
 */
class UpcallEntryTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(TrampolineFragment))
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        if (PythonTestFixture.available) Python3.exec("_pm_bound = None")
    }

    /** Evaluates [expression] in `__main__` and returns its `str()`. */
    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    @Test
    fun pythonCallsKotlinWithArgumentsOfEveryMarshalledTagAndReadsTheResultBack() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("trampoline.add"))
            Python3.exec("_pm_r0 = _pm_bound(3, 4)")
            assertTrue(bindUpcallOrNull("trampoline.scale"))
            Python3.exec("_pm_r1 = _pm_bound(2.5, 3.0)")
            assertTrue(bindUpcallOrNull("trampoline.negate"))
            Python3.exec("_pm_r2 = _pm_bound(False)")
            assertTrue(bindUpcallOrNull("trampoline.greet"))
            Python3.exec("_pm_r3 = _pm_bound('파이썬')")
            assertTrue(bindUpcallOrNull("trampoline.reverseBytes"))
            Python3.exec("_pm_r4 = _pm_bound(b'\\x01\\x00\\xff')")
            assertTrue(bindUpcallOrNull("trampoline.discard"))
            Python3.exec("_pm_r5 = _pm_bound(1)")

            assertEquals("7", py("_pm_r0"))
            assertEquals("7.5", py("_pm_r1"))
            assertEquals("True", py("_pm_r2"))
            assertEquals("Hello, 파이썬!", py("_pm_r3"))
            // Round-tripped through ByteArray, so the 0x00 and 0xff bytes had to survive a
            // marshaller that never decoded them as text.
            assertEquals("b'\\xff\\x00\\x01'", py("_pm_r4"))
            assertEquals("None", py("_pm_r5"))

            // The types are Python's own, not stand-ins: `True` must not arrive as `1`.
            assertEquals("<class 'bool'>", py("type(_pm_r2)"))
            assertEquals("<class 'bytes'>", py("type(_pm_r4)"))
            assertEquals("<class 'float'>", py("type(_pm_r1)"))
        }

    @Test
    fun pythonConstructsAKotlinObjectAndCallsAMethodOnItThroughTheSameEntryPoint() =
        PythonTestFixture.withInterpreter {
            val rootedBefore = HandleTable.liveCount
            assertTrue(bindUpcallOrNull("trampoline.Target.<init>"))
            Python3.exec("_pm_obj = _pm_bound('kotlin')")
            assertTrue(bindUpcallOrNull("trampoline.Target.combine"))
            Python3.exec("_pm_combined = _pm_bound(_pm_obj, 3, '!')")

            // Python holds the Kotlin object only as an ObjectReference integer -- it cannot hold
            // a Kotlin/JVM reference on any of these targets.
            assertEquals("<class 'int'>", py("type(_pm_obj)"))
            assertEquals("kotlin*3!", py("_pm_combined"))
            assertEquals(
                rootedBefore + 1, HandleTable.liveCount,
                "the constructor must root exactly one object, and the method call none",
            )

            // What a proxy's tp_dealloc is for. Without it the root outlives every Python
            // reference to it, which HandleTable's own doc calls leaking by construction.
            val objHandle = py("_pm_obj").toLong()
            assertEquals(1, releaseUpcallHandle(objHandle))
            assertEquals(rootedBefore, HandleTable.liveCount)
            assertEquals(0, releaseUpcallHandle(objHandle), "a double release must be a no-op")
        }

    @Test
    fun aKotlinExceptionArrivesInPythonAsARaiseRatherThanKillingTheProcess() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("trampoline.explode"))
            // A Throwable crossing back into C terminates the process on every one of these
            // targets -- there is no unwinding to catch it further out. If this test reports at
            // all, it did not escape.
            Python3.exec(
                """
                try:
                    _pm_bound()
                    _pm_raised = 'nothing was raised'
                except Exception as e:
                    _pm_raised = str(e)
                """.trimIndent(),
            )
            assertTrue(
                py("_pm_raised").contains("deliberate Kotlin failure"),
                "the Kotlin failure did not reach Python: ${py("_pm_raised")}",
            )
        }

    @Test
    fun aWrongArgumentCountIsRejectedAsAPythonExceptionRatherThanCallingTheTarget() =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("trampoline.add"))
            Python3.exec(
                """
                try:
                    _pm_arity = _pm_bound(1)
                except Exception as e:
                    _pm_arity = str(e)
                """.trimIndent(),
            )
            assertTrue(
                py("_pm_arity").contains("2 argument"),
                "expected an arity complaint naming the declared count, got: ${py("_pm_arity")}",
            )
        }

    @Test
    fun anUnknownNameResolvesToTheNoneHandleRatherThanCallingSomethingElse() =
        PythonTestFixture.withInterpreter {
            assertFalse(bindUpcallOrNull("trampoline.nothingIsCalledThis"))
        }

    @Test
    fun theReferenceReturnedToPythonIsTakenOverExactlyOnce() = PythonTestFixture.withInterpreter {
        // `trampoline.shared` hands back one fixed object every time, so anything the count does
        // across a hundred calls is the boundary's doing. Both failure directions are fatal and
        // both are visible here: one reference too few per call drives it to zero and frees an
        // object Kotlin still holds, one too many leaks it for the life of the interpreter.
        TrampolineFragment.shared = PythonTestFixture.eval("{'k': 'v'}")
        try {
            assertTrue(bindUpcallOrNull("trampoline.shared"))
            Python3.exec("_pm_shared_fn = _pm_bound")
            Python3.exec("_pm_base = __import__('sys').getrefcount(_pm_shared_fn())")
            Python3.exec(
                """
                for _ in range(100):
                    _pm_shared_fn()
                _pm_end = __import__('sys').getrefcount(_pm_shared_fn())
                """.trimIndent(),
            )

            assertEquals(
                py("_pm_base"), py("_pm_end"),
                "100 calls moved the refcount of the returned object; the result's ownership is unbalanced",
            )
            assertEquals("{'k': 'v'}", py("_pm_shared_fn()"))
        } finally {
            TrampolineFragment.shared = null
        }
    }

    @Test
    fun theArgumentsPythonPassesAreBorrowedAndTheEntryPointMustNotConsumeThem() =
        PythonTestFixture.withInterpreter {
            // The bug this pins is the one that crashed this repo twice: wrapping a *borrowed*
            // tuple item with `borrowed = false` gives one reference back per call that was never
            // taken. Over 200 calls the object would be freed while `_pm_holder` still names it,
            // so the count would fall (or the process would die), never rise.
            assertTrue(bindUpcallOrNull("trampoline.describe"))
            Python3.exec("_pm_holder = [1, 2, 3]")
            Python3.exec(
                """
                _pm_before = __import__('sys').getrefcount(_pm_holder)
                for _ in range(200):
                    _pm_bound(_pm_holder)
                _pm_after = __import__('sys').getrefcount(_pm_holder)
                """.trimIndent(),
            )

            val before = py("_pm_before").toLong()
            val after = py("_pm_after").toLong()
            // Upwards is legitimate and unbounded here: each call's `PyObject(_, borrowed = true)`
            // wrapper holds its own reference until a cleaner reclaims it, and nothing forces one.
            // Downwards is the bug, and it is one per call.
            assertTrue(after >= before, "200 calls dropped the refcount of a borrowed argument by ${before - after}")
            // ...and the object survived intact, which a run of over-releases would not have left it.
            assertEquals("[1, 2, 3]", py("_pm_bound(_pm_holder)"))
        }
}

/**
 * Resolves [name] through this platform's real published entry point and, if it resolves,
 * installs a genuine bound callable as `_pm_bound` in `__main__`'s globals -- ready to call as
 * `_pm_bound(*args)`, exactly as a finished proxy method would be. Returns `false` (and leaves
 * `_pm_bound` unset or `None`) for a name nothing claims.
 *
 * This is the one part of the contract that is genuinely per-platform: native/android route
 * through the `_pm_resolve`/`_pm_bind` `PyMethodDef` pair [UpcallEntry.publish] installs, desktop
 * through a ctypes shim over the Panama stubs in `UpcallStub`, and wasmJs resolves in Kotlin
 * directly (there being no separate published resolver on that target) and installs the callable
 * [UpcallEntry.bind] returns.
 */
expect fun bindUpcallOrNull(name: String): Boolean

/**
 * Releases the object handle behind a proxy's `tp_dealloc`, through this platform's real published
 * release entry point. Returns 1 if it released a live entry, 0 for a handle already released or
 * never issued.
 */
expect fun releaseUpcallHandle(handle: Long): Int
