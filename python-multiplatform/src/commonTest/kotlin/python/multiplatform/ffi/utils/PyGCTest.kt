package python.multiplatform.ffi.utils

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Functional tests for [PyGC].
 *
 * The Stable ABI does define `PyGC_Collect`/`PyGC_Enable`/`PyGC_Disable`/
 * `PyGC_IsEnabled`, but this project's `EmbedAPI` surface does not declare
 * them, so [PyGC] drives the `gc` module instead. These tests pin the
 * behaviour, not the route: they would still hold if the direct entry points
 * were added later.
 */
class PyGCTest {

    @Test
    fun collectReclaimsAReferenceCycle() = PythonTestFixture.withInterpreter {
        // Start from a clean generation so the count below is attributable to the cycle we make.
        PyGC.collect()
        Python3.exec(
            """
            class _GcCycleProbe:
                pass

            def _make_gc_cycle():
                a = _GcCycleProbe()
                b = _GcCycleProbe()
                a.other = b
                b.other = a

            _make_gc_cycle()
            """.trimIndent()
        )
        val unreachable = PyGC.collect()
        assertTrue(
            unreachable > 0,
            "gc.collect() should report the unreachable cycle just dropped, but reported $unreachable"
        )
    }

    @Test
    fun enableAndDisableRoundTripThroughIsEnabled() = PythonTestFixture.withInterpreter {
        val wasEnabled = PyGC.isEnabled()
        try {
            PyGC.disable()
            assertFalse(PyGC.isEnabled(), "gc.isenabled() must observe gc.disable()")
            PyGC.enable()
            assertTrue(PyGC.isEnabled(), "gc.isenabled() must observe gc.enable()")
        } finally {
            // Leave the collector as this test found it; other tests in the binary depend on it.
            if (wasEnabled) PyGC.enable() else PyGC.disable()
        }
    }

    @Test
    fun refCountTracksWrappersTakingAndGivingBackReferences() = PythonTestFixture.withInterpreter {
        val target = PythonTestFixture.eval("object()")
        try {
            val before = PyGC.refCount(target)
            val extra = PyObject(target.pointer, borrowed = true)
            assertEquals(
                before + 1, PyGC.refCount(target),
                "a borrowed wrapper takes exactly one reference, and refCount() must see it"
            )
            extra.close()
            assertEquals(before, PyGC.refCount(target), "closing it must give back exactly one")
        } finally {
            target.close()
        }
    }
}
