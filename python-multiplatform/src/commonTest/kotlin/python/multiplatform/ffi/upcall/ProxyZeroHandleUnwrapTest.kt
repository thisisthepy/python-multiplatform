package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.TestAppFragment
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * `_PmGcObject._pm_handle` is a real `Py_T_LONG` member (`ProxyTypeFactory`'s reserved slot), not a
 * `__slots__` attribute like `_PmObject`'s. A C member of that type is never *missing* -- it always
 * reads as an integer, and the zero-initialised default `PyType_GenericAlloc` gives every instance
 * before `__init__` runs is `0`, not `None`. `_pm_unwrap` used to test `if _pm_h is None:`, a check
 * borrowed from `_PmObject`'s `getattr(..., None)` convention, which a C `long` slot can never
 * satisfy -- `0 is None` is always `False`. An instance whose slot was never assigned (a constructor
 * that raised before `self._pm_handle = ...`, or, as built here, `object.__new__` bypassing `__init__`
 * outright) would sail through `_pm_unwrap` and hand Kotlin handle `0` as if it were a live one.
 *
 * `PythonProxySourceTest` pins the generated source as text and does not execute it, so it cannot see
 * this -- the bug is in what a C member actually reads as, not in what the generator emits.
 * [OwnedResultLifetimeTest] and [ProxyHandleLifetimeTest] cover the same "already released" refusal
 * for `_PmObject`, whose `getattr(..., None)`-based check was never wrong the same way.
 */
class ProxyZeroHandleUnwrapTest {

    @BeforeTest
    fun setUp() {
        UpcallTable.install(listOf(TestAppFragment))
    }

    @AfterTest
    fun tearDown() {
        UpcallTable.clear()
        if (PythonTestFixture.available) Python3.exec("_zh = None")
    }

    @Test
    fun aGcEligibleInstanceWithAZeroHandleSlotRefusesToUnwrapRatherThanSendingZero() =
        PythonTestFixture.withInterpreter {
            if (!publishesProxyEntryPoints) return@withInterpreter
            assertTrue(bindUpcallOrNull("test.app.RefHolder.<init>"), "the fixture table is not installed")

            PythonProxySource.install()
            Python3.exec("import test.app")

            // `test.app.RefHolder` carries a generated `tp_traverse` (TestFragments' `traverse =`
            // lambda), so [PythonProxySource.renderClass] must have rendered it on `_PmGcObject`,
            // not `_PmObject` -- confirmed here rather than assumed, so a target where
            // `ProxyTypeFactory.installGcBase` silently fell back would not make this test pass for
            // the wrong reason (a `__slots__` miss raises `AttributeError`, not "already released").
            // Checking `__dict__['_pm_handle']`'s type is not enough to tell the two apart: a
            // `__slots__` attribute descriptor is a `member_descriptor` too. What differs is *which*
            // class is behind the name -- the fallback line `_PmGcObject = _PmObject` in OWNED_HANDLE
            // makes them literally the same object, so the base's own `__name__` says which case ran.
            assertEquals(
                "_PmGcObject",
                py("test.app.RefHolder.__mro__[1].__name__"),
                "RefHolder must be _PmGcObject-based (a real C slot) rather than have fallen back " +
                    "to _PmObject (a __slots__ attribute) -- otherwise this test cannot exercise the " +
                    "zero-handle case at all",
            )

            // `object.__new__` bypasses `__init__`, so `_pm_handle` is never assigned and is left at
            // the C slot's zero-initialised default.
            Python3.exec("_zh = object.__new__(test.app.RefHolder)")
            assertEquals(
                "0", py("_zh._pm_handle"),
                "the slot must read as the int 0, not None -- that is the entire defect this test " +
                    "exists to catch",
            )

            val failure = assertFails { Python3.exec("_pm_unwrap(_zh)") }
            assertTrue(
                failure.message?.contains("already released") == true,
                "a zero handle slot must be refused, not sent to Kotlin as handle 0: $failure",
            )
        }

    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()
}
