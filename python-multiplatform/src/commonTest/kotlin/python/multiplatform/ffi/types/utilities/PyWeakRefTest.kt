package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #524: [PyWeakRef], an empty class at the baseline and until this change. Red before: none of these
 * members existed, so this file did not compile.
 */
class PyWeakRefTest {

    @Test
    fun aWeakReferenceResolvesWhileItsReferentLivesAndNotAfter() = PythonTestFixture.withInterpreter {
        Python3.exec("import gc\nclass _TwTarget:\n    pass\n_tw_target = _TwTarget()\n_tw_called = []")
        val ref = PythonTestFixture.eval("_tw_target").use { target ->
            PythonTestFixture.eval("_tw_called.append").use { callback -> PyWeakRef.of(target, callback) }
        }
        ref.use {
            assertTrue(it.isAlive)
            val resolved = it.get()
            val expected = PythonTestFixture.eval("_tw_target")
            try {
                assertEquals(expected, resolved, "get() must return the referent itself")
            } finally {
                resolved?.close()
                expected.close()
            }
            Python3.exec("del _tw_target\ngc.collect()")
            assertNull(it.get(), "the referent was collected, so get() must be null")
            assertFalse(it.isAlive)
            assertEquals("1", PythonTestFixture.eval("len(_tw_called)").use { n -> n.toString() }, "the callback runs once")
        }
    }

    @Test
    fun anIntCannotBeWeaklyReferenced() = PythonTestFixture.withInterpreter {
        PythonTestFixture.eval("12345").use { assertFailsWith<PyTypeError> { PyWeakRef.of(it) } }
    }

    @Test
    fun fromWrapsAnExistingRefAndRefusesOtherObjects() = PythonTestFixture.withInterpreter {
        Python3.exec("import weakref\nclass _TwOther:\n    pass\n_tw_other = _TwOther()\n_tw_ref = weakref.ref(_tw_other)")
        PythonTestFixture.eval("_tw_ref").use { raw -> PyWeakRef.from(raw) }.use { assertTrue(it.isAlive) }
        PythonTestFixture.eval("_tw_other").use { assertFailsWith<IllegalArgumentException> { PyWeakRef.from(it) } }
    }

    @Test
    fun aProxyForwardsToItsTarget() = PythonTestFixture.withInterpreter {
        Python3.exec("class _TwNamed:\n    label = 'named'\n_tw_named = _TwNamed()")
        val proxy = PythonTestFixture.eval("_tw_named").use { PyWeakRef.proxy(it) }
        assertEquals("named", proxy.use { it.getAttr("label").use { label -> label.toString() } })
    }
}
