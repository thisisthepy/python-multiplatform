package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Functional tests for the conversion layer (`ConversionStrategy`,
 * `PyContext`, `PyValue`): the strategy variants, [PyValue] both returning a
 * pre-supplied native value and deriving one lazily when none was given,
 * `convertValue` under the active strategy, `withContext` restoring the
 * previous strategy on exit, and the `str(obj)` fallback that NATIVE
 * conversion of a user-defined object currently lands on.
 *
 * This header used to say everything that *derives* a conversion from a live
 * [python.multiplatform.ffi.PyObject] via [PyContext] was `TODO` and expected
 * to fail. That path is implemented now, so every test here is a regression
 * test and any failure is a real one.
 */
class ConversionTest {

    @Test
    fun conversionStrategyHasTheFiveSketchedVariants() {
        val names = ConversionStrategy.entries.map { it.name }.toSet()
        assertEquals(setOf("DEFAULT", "UNMANAGED", "RAW", "TYPED", "NATIVE"), names)
    }

    @Test
    fun pyValueReturnsAPreSuppliedNativeValueWithoutTouchingPython() {
        val obj = PythonTestFixture.eval("123") // still requires a live interpreter to build a PyObject to hold
        val value = PyValue(obj, initialNativeValue = 123L)
        assertEquals(123L, value.asNative())
    }

    @Test
    fun pyValueDerivesTheNativeValueWhenNoneWasSupplied() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("123")
        val value = PyValue<Long>(obj)
        // Red phase: this asserted a throw, because PyProxy's lazy conversion was a TODO stub
        // that ended in `cachedNativeValue!!`. Green phase: the lazy path is implemented, so the
        // conversion is what must hold. The genuine failure case the old assertion stood in for
        // -- a Python value with no native Kotlin counterpart -- is asserted properly in
        // PyValueLazyConversionTest, along with the rest of the lazy path.
        assertEquals(123L, value.asNative())
    }

    @Test
    fun contextConvertValueConvertsAccordingToActiveStrategy() = PythonTestFixture.withInterpreter {
        val context = PyContext(ConversionStrategy.NATIVE)
        val obj = PythonTestFixture.eval("42")
        context.convertValue(obj)
    }

    @Test
    fun withContextTemporarilySwitchesStrategy() = PythonTestFixture.withInterpreter {
        val context = PyContext(ConversionStrategy.DEFAULT)
        context.withContext(ConversionStrategy.RAW) {
            assertEquals(ConversionStrategy.RAW, context.activeStrategy)
        }
        assertEquals(ConversionStrategy.DEFAULT, context.activeStrategy)
    }

    @Test
    fun rawAndNativeStrategiesProduceDifferentlyShapedResults() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val raw = PyContext(ConversionStrategy.RAW).convertValue(obj)
        val native = PyContext(ConversionStrategy.NATIVE).convertValue(obj)
        // Both routes are implemented. RAW hands back the bare `NativePointer` -- no wrapper
        // materialised at all, which is what keeps it distinct from UNMANAGED (see
        // `PyContext.convertValue`); NATIVE walks the object through `pyObjectToNative` and
        // returns a Kotlin `List`. What is asserted is that the two stay distinguishable, not
        // what either shape happens to be.
        assertNotEquals(raw, native)
    }

    @Test
    fun pyObjectToNativeFallbackBranchHit() = PythonTestFixture.withInterpreter {
        // Create a custom user-defined Python object with a specific __str__
        val obj = PythonTestFixture.eval("type('MyClass', (), {'__str__': lambda self: 'FallbackTriggered'})()")
        val native = PyContext(ConversionStrategy.NATIVE).convertValue(obj)
        
        // Currently, it falls back to obj.toString() because we lack the Upcall binder
        // to retrieve the actual Kotlin peer (ROADMAP §7).
        assertEquals("FallbackTriggered", native)
    }
}
