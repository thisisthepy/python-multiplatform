package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Functional tests for the callable wrappers: [PyFunction], [PyMethod],
 * [PyClassMethod] and [PyStaticMethod].
 *
 * Every wrapper here is built with `borrowed = true` from a pointer that
 * some *other* wrapper already owns. Adopting (`borrowed = false`) a pointer
 * whose reference belongs to someone else is the double-release bug that
 * ROADMAP §1 is the account of, so the tests must not model it even when it
 * would be shorter.
 */
class PyCallablesTest {

    private fun defineFixtures() {
        Python3.exec(
            """
            def _tc_free_function(a, b=1):
                "a documented free function"
                return a + b

            _tc_lambda = lambda: 0

            class _TcClass:
                def method(self, x):
                    return x

                @classmethod
                def class_method(cls):
                    return "cm"

                @staticmethod
                def static_method():
                    return "sm"

            _tc_instance = _TcClass()
            """.trimIndent()
        )
    }

    /** Evaluates [expression] and hands [block] a wrapper that takes its own reference to the result. */
    private inline fun <T : PyObject> evaluating(expression: String, wrap: (PyObject) -> T, block: (T) -> Unit) {
        defineFixtures()
        val raw = PythonTestFixture.eval(expression)
        try {
            val wrapper = wrap(raw)
            try {
                block(wrapper)
            } finally {
                wrapper.close()
            }
        } finally {
            raw.close()
        }
    }

    @Test
    fun freeFunctionExposesNameQualifiedNameAndDoc() = PythonTestFixture.withInterpreter {
        evaluating("_tc_free_function", { PyFunction(it.pointer, borrowed = true) }) { fn ->
            assertEquals("_tc_free_function", fn.name)
            assertEquals("_tc_free_function", fn.qualifiedName)
            assertEquals("a documented free function", fn.doc)
        }
    }

    @Test
    fun undocumentedLambdaHasNullDocAndAnonymousName() = PythonTestFixture.withInterpreter {
        evaluating("_tc_lambda", { PyFunction(it.pointer, borrowed = true) }) { fn ->
            assertEquals("<lambda>", fn.name)
            assertNull(fn.doc, "a function with no docstring has __doc__ == None, which must map to null")
        }
    }

    @Test
    fun functionDefinedInsideAClassQualifiesItsName() = PythonTestFixture.withInterpreter {
        evaluating("_TcClass.method", { PyFunction(it.pointer, borrowed = true) }) { fn ->
            assertEquals("method", fn.name)
            assertEquals("_TcClass.method", fn.qualifiedName)
        }
    }

    @Test
    fun boundMethodExposesItsReceiverAndUnderlyingFunction() = PythonTestFixture.withInterpreter {
        evaluating("_tc_instance.method", { PyMethod(it.pointer, borrowed = true) }) { method ->
            val expectedSelf = PythonTestFixture.eval("_tc_instance")
            val self = method.instance
            val fn = method.function
            try {
                assertEquals(expectedSelf, self, "__self__ must be the very object the method was bound to")
                assertEquals("method", fn.name)
                assertEquals("_TcClass.method", fn.qualifiedName)
            } finally {
                fn.close()
                self.close()
                expectedSelf.close()
            }
        }
    }

    @Test
    fun classMethodDescriptorExposesItsUnderlyingFunction() = PythonTestFixture.withInterpreter {
        // `_TcClass.class_method` is already *bound*; the classmethod descriptor object itself
        // is only reachable through the class __dict__.
        evaluating("_TcClass.__dict__['class_method']", { PyClassMethod(it.pointer, borrowed = true) }) { cm ->
            val fn = cm.function
            try {
                assertEquals("class_method", fn.name)
                assertEquals("_TcClass.class_method", fn.qualifiedName)
            } finally {
                fn.close()
            }
        }
    }

    @Test
    fun staticMethodDescriptorExposesItsUnderlyingFunction() = PythonTestFixture.withInterpreter {
        evaluating("_TcClass.__dict__['static_method']", { PyStaticMethod(it.pointer, borrowed = true) }) { sm ->
            val fn = sm.function
            try {
                assertEquals("static_method", fn.name)
                assertEquals("_TcClass.static_method", fn.qualifiedName)
            } finally {
                fn.close()
            }
        }
    }

    /**
     * Measurement, not just behaviour: reading `__name__` costs one attribute
     * lookup plus one UTF-8 decode. The alternative spelling every caller
     * would otherwise reach for -- `getAttr("__name__").toString()` -- pays
     * for a Kotlin wrapper (with a cleaner registration) and a `PyObject_Str`
     * crossing on top of exactly the same two calls. Printed rather than
     * asserted on absolute time; the assertion is that the two agree, so the
     * cheap path cannot silently be the wrong one.
     */
    @Test
    fun nameDecodeCostAgainstTheGenericAttributePath() = PythonTestFixture.withInterpreter {
        evaluating("_tc_free_function", { PyFunction(it.pointer, borrowed = true) }) { fn ->
            val iterations = 2_000
            repeat(200) { fn.name }
            repeat(200) { fn.getAttr("__name__").let { o -> o.toString(); o.close() } }

            val direct = measureTime { repeat(iterations) { fn.name } }
            val viaWrapper = measureTime {
                repeat(iterations) { fn.getAttr("__name__").let { o -> o.toString(); o.close() } }
            }

            println(
                "PyFunction.name             : ${direct.inWholeNanoseconds / iterations} ns/op\n" +
                    "getAttr(\"__name__\").toString(): ${viaWrapper.inWholeNanoseconds / iterations} ns/op"
            )

            val viaWrapperValue = fn.getAttr("__name__").let { o -> val s = o.toString(); o.close(); s }
            assertEquals(viaWrapperValue, fn.name, "the direct decode must return what the generic path returns")
            assertTrue(direct.inWholeNanoseconds > 0, "the measurement clock must have advanced")
        }
    }
}
