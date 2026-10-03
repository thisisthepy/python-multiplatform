package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.basic.PyString
import python.native.ffi.NativePointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.TimeSource

/**
 * The lazy half of [PyValue] -- the path `ConversionTest` never touches.
 *
 * `ConversionTest` exercises strategy switching and the shape difference
 * between `RAW` and `NATIVE`; every conversion it makes either supplies the
 * native value up front or goes through a typed wrapper whose own
 * `cachedNativeValue` accessor answers before [PyProxy.toKotlinOrNull]'s body
 * ever runs. This file is the other case: a plain [PyObject] handed to a
 * [PyValue], where the generic walk *is* the conversion. That is the path that
 * used to end in `cachedNativeValue!!`.
 *
 * The assertions are grouped by the per-type lifetime rule they pin down
 * (`docs/design/object-lifetime.md`, "Conversion caching, and where it stops"):
 *
 * - a converted value that shares nothing with CPython may be cached, and
 *   must stay readable after its source is released;
 * - a container is cached as a **snapshot**, so it goes stale rather than
 *   dangling, and [PyProxy.invalidateNativeCache] is the stated remedy;
 * - a type whose native form would be a *view* into Python-owned memory
 *   (`memoryview`) is refused outright rather than quietly cached -- `bytes`
 *   and `bytearray` were refused here too until they gained a `ByteArray`
 *   *copy*, which `PyValueBytesConversionTest` owns;
 * - a bare `NativePointer` -- what `ConversionStrategy.RAW` hands back -- is
 *   not a reference at all and may never be stored as a native value.
 */
class PyValueLazyConversionTest {

    /** `sys.getrefcount`, and the wrapper to release when the caller is done with it. */
    private fun refCounter(): Pair<PyObject, (PyObject) -> Long> {
        val sys = Python3.import("sys")
        val getrefcount = sys.getAttr("getrefcount")
        sys.close()
        return getrefcount to { obj: PyObject ->
            val n = getrefcount(obj)
            try {
                n.toString().toLong()
            } finally {
                n.close()
            }
        }
    }

    /**
     * Fails unless [value] is built exclusively out of Kotlin values that share
     * nothing with CPython -- no [PyObject], no [NativePointer], nothing else
     * whose validity depends on a Python object staying alive.
     */
    private fun assertIndependentOfPython(value: Any?, path: String = "<root>") {
        when (value) {
            null, is Long, is Double, is Boolean, is String -> Unit
            is List<*> -> value.forEachIndexed { i, e -> assertIndependentOfPython(e, "$path[$i]") }
            is Set<*> -> value.forEach { assertIndependentOfPython(it, "$path{}") }
            is Map<*, *> -> value.forEach { (k, v) ->
                assertIndependentOfPython(k, "$path.key")
                assertIndependentOfPython(v, "$path[$k]")
            }
            else -> fail(
                "$path holds a ${value::class.simpleName}, which is not independent of Python-owned " +
                    "memory; caching it would outlive the object it points into"
            )
        }
    }

    /** Reuses the memory a just-released Python object occupied, so a dangling read would show. */
    private fun churnPythonHeap() {
        Python3.exec("_pm_lazy_churn = [''.join(['xx', str(i), 'yy']) for i in range(2000)]")
        Python3.exec("del _pm_lazy_churn")
    }

    // ---------------------------------------------------------------- the path that used to NPE

    /**
     * [PythonTestFixture.eval] hands back a plain [PyObject], not a typed
     * wrapper, so nothing here can answer from a `cachedNativeValue` accessor:
     * every case below goes through [PyProxy.toKotlinOrNull]'s own walk. This
     * is the "anything without a dedicated wrapper fails" case from ROADMAP
     * §7b, one expression per builtin the walk understands.
     */
    @Test
    fun anUntypedSourceStillConvertsForEveryBuiltinTheWalkUnderstands() = PythonTestFixture.withInterpreter {
        val cases: List<Pair<String, Any?>> = listOf(
            "1 + 122" to 123L,
            "1.25 * 2" to 2.5,
            "1 == 1" to true,
            "''.join(['a', 'b', 'c'])" to "abc",
            "[1, 2]" to listOf(1L, 2L),
            "(1, 'x')" to listOf(1L, "x"),
            "{'k': 2}" to mapOf<Any?, Any?>("k" to 2L),
            "{1, 2}" to setOf(1L, 2L),
            "frozenset({3})" to setOf(3L),
        )
        for ((expression, expected) in cases) {
            val obj = PythonTestFixture.eval(expression)
            assertFalse(
                obj is PyProxy<*>,
                "the fixture must hand back an untyped PyObject for `$expression`, otherwise this " +
                    "test measures the wrapper's own accessor instead of the lazy walk"
            )
            val value = PyValue<Any?>(obj)
            assertEquals(expected, value.asNativeOrNull(), "lazy conversion of `$expression`")
            obj.close()
        }
    }

    /** A typed wrapper as the source still works -- its own accessor answers first. */
    @Test
    fun aTypedWrapperSourceAnswersFromItsOwnAccessor() = PythonTestFixture.withInterpreter {
        val i = PyInt.from(7)
        val s = PyString.from("seven")
        try {
            assertEquals(7L, PyValue<Long>(i).asNative())
            assertEquals("seven", PyValue<String>(s).asNative())
        } finally {
            i.close()
            s.close()
        }
    }

    // ------------------------------------------------------- caching is safe: independent values

    /**
     * A `str` converts to a Kotlin `String` *copy* (`PyUnicode_AsUTF8`'s
     * `const char*` is decoded at the platform boundary and never escapes), so
     * the cached value has to survive the release of the object it came from --
     * including the heap being reused underneath it.
     */
    @Test
    fun aCachedStringOutlivesItsPythonSource() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("''.join(['py', 'value', '-', 'lifetime'])")
        val value = PyValue<String>(obj)
        assertEquals("pyvalue-lifetime", value.asNative())

        obj.close() // the only reference: the str is freed here
        churnPythonHeap()

        assertEquals(
            "pyvalue-lifetime", value.asNative(),
            "the cache holds a Kotlin copy, so releasing the source must not reach it"
        )
    }

    /**
     * The container case. Two things have to hold at once: every element is
     * itself an independent Kotlin value (nothing in the cached structure is a
     * [PyObject] or a [NativePointer]), and the second read is the cache rather
     * than a second walk.
     */
    @Test
    fun aCachedContainerHoldsNoPythonReferencesAndIsConvertedOnce() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{'a': [1, 2.5, True, 'x'], 'b': (3, None)}")
        val value = PyValue<Map<Any?, Any?>>(obj)

        val converted = value.asNative()
        assertIndependentOfPython(converted)
        assertSame(converted, value.asNative(), "the second read must come from the cache, not a second walk")

        obj.close()
        churnPythonHeap()

        assertEquals(
            mapOf<Any?, Any?>("a" to listOf(1L, 2.5, true, "x"), "b" to listOf(3L, null)),
            value.asNative(),
            "a converted container shares nothing with CPython, so it must outlive its source"
        )
    }

    // ------------------------------------------------- caching is a snapshot: staleness, not UB

    /**
     * A cached container is independent of Python-owned memory precisely
     * *because* it is a copy, which is the same reason it stops tracking a
     * container that is still being mutated. Pinning both halves: the stale
     * read, and [PyProxy.invalidateNativeCache] as the stated way out.
     */
    @Test
    fun aMutatedContainerKeepsItsSnapshotUntilTheCacheIsInvalidated() = PythonTestFixture.withInterpreter {
        Python3.exec("_pm_snapshot_probe = [1, 2]")
        val obj = PythonTestFixture.eval("_pm_snapshot_probe")
        val value = PyValue<List<Any?>>(obj)

        val first = value.asNative()
        assertEquals(listOf(1L, 2L), first)

        Python3.exec("_pm_snapshot_probe.append(3)")
        assertSame(first, value.asNative(), "the cache is a snapshot and does not follow the live container")

        value.invalidateNativeCache()
        val second = value.asNative()
        assertNotSame(first, second)
        assertEquals(listOf(1L, 2L, 3L), second, "invalidating the cache must force a fresh walk")

        obj.close()
        Python3.exec("del _pm_snapshot_probe")
    }

    // --------------------------------------------------------------- refusals, not silent caches

    /**
     * A type whose native form would be a *view* of Python-owned memory is
     * refused rather than converted, and the refusal leaves nothing behind in
     * the cache.
     *
     * This used to cover `bytes` and `bytearray` too. It no longer does, and
     * the change is deliberate rather than a weakened assertion: those two are
     * converted now, to a `ByteArray` *copy* that shares nothing with CPython
     * (see `PyValueBytesConversionTest`, and `PyBytes`'s class doc for why the
     * read never touches a buffer pointer). `memoryview` is the case the copy
     * argument does not rescue -- it carries a format, a shape and strides, so
     * no single flat `ByteArray` is its value -- so it is what is left here.
     */
    @Test
    fun aBufferViewTypeIsRefusedRatherThanCachedAsAViewOfPythonMemory() = PythonTestFixture.withInterpreter {
        for (expression in listOf("memoryview(b'abc')", "memoryview(bytearray(b'abc'))")) {
            val obj = PythonTestFixture.eval(expression)
            val value = PyValue<Any?>(obj)

            assertNull(value.asNativeOrNull(), "`$expression` must not be converted to a view of Python memory")
            assertNull(value.cachedNativeValue, "a refused conversion must leave the cache empty")
            assertFailsWith<PyTypeError>("`$expression` has no native Kotlin counterpart") { value.asNative() }
            assertNull(value.asNativeOrNull(), "the refusal must be repeatable, not a one-shot")

            obj.close()
        }
    }

    /**
     * A user-defined object is the "TYPED is as far as it goes" case. What
     * matters here is the *shape* of the failure: `null` from the nullable
     * accessor, [PyTypeError] from the one that promises a `T`, nothing cached,
     * and -- the point of this whole file -- no `NullPointerException` from a
     * cache miss.
     */
    @Test
    fun aTypeWithNoNativeCounterpartIsRefusedWithoutTouchingTheCache() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            class _PmNoCounterpart:
                def __repr__(self):
                    return '<_PmNoCounterpart>'

            _pm_no_counterpart = _PmNoCounterpart()
            """.trimIndent()
        )
        val obj = PythonTestFixture.eval("_pm_no_counterpart")
        val value = PyValue<Any?>(obj)

        assertNull(value.asNativeOrNull())
        assertNull(value.cachedNativeValue, "nothing may be cached for a type with no native counterpart")
        val error = assertFailsWith<PyTypeError> { value.asNative() }
        assertTrue(
            error.errMsg.contains("No native Kotlin counterpart"),
            "the refusal must name its cause, was: ${error.errMsg}"
        )
        assertEquals(obj, value.asPyObject(), "toPython() still hands the Python side back")

        obj.close()
        Python3.exec("del _pm_no_counterpart")
    }

    /**
     * `typedWrap` and `pyObjectToNative` both dispatch on the *exact* type
     * object, mirroring `PyLong_Check`'s semantics rather than `isinstance`'s.
     * A subclass of a builtin therefore has no native counterpart even though
     * its base does, and so does `complex`, which has a wrapper class but no
     * entry in either dispatch. Asserted rather than assumed, because the
     * per-type table in `docs/design/object-lifetime.md` claims it.
     */
    @Test
    fun exactTypeDispatchLeavesBuiltinSubclassesAndComplexWithoutACounterpart() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            class _PmIntSubclass(int):
                pass

            class _PmListSubclass(list):
                pass
            """.trimIndent()
        )
        for (expression in listOf("_PmIntSubclass(5)", "_PmListSubclass([1, 2])", "complex(1, 2)")) {
            val obj = PythonTestFixture.eval(expression)
            val value = PyValue<Any?>(obj)
            assertNull(value.asNativeOrNull(), "`$expression` must not be converted by exact-type dispatch")
            assertNull(value.cachedNativeValue, "a refused conversion must leave the cache empty")
            obj.close()
        }
        Python3.exec("del _PmIntSubclass, _PmListSubclass")
    }

    /** `None` has a Kotlin counterpart (`null`) but not a `T`-typed one. */
    @Test
    fun noneIsNullFromTheNullableAccessorAndThrowsFromTheOther() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("None")
        val value = PyValue<Any?>(obj)

        assertNull(value.asNativeOrNull())
        assertNull(value.cachedNativeValue)
        val error = assertFailsWith<PyTypeError> { value.asNative() }
        assertTrue(error.errMsg.contains("None"), "the refusal must name its cause, was: ${error.errMsg}")

        obj.close()
    }

    /**
     * `ConversionStrategy.RAW` hands back a bare `NativePointer` -- deliberately
     * *not* a reference: nothing increfs it and nothing releases it. Storing one
     * as a [PyValue]'s native value would cache exactly the thing the lifetime
     * rule forbids, and would only surface once the address had been reused, so
     * it is refused at construction instead.
     */
    @Test
    fun aRawPointerIsRefusedAsACachedNativeValue() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        val raw = PyContext(ConversionStrategy.RAW).convertValue(obj)
        assertTrue(raw is NativePointer, "RAW must hand back a bare pointer for this test to mean anything")

        assertFailsWith<IllegalArgumentException>("a bare pointer is not a reference and must not be cached") {
            PyValue(obj, initialNativeValue = raw)
        }

        obj.close()
    }

    /** The cacheability rule itself, applied to the values it has to judge. */
    @Test
    fun theCacheabilityRuleAcceptsOnlyValuesIndependentOfPythonMemory() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[1, 2, 3]")
        try {
            assertTrue(isIndependentOfPythonMemory(null))
            assertTrue(isIndependentOfPythonMemory(123L))
            assertTrue(isIndependentOfPythonMemory(1.5))
            assertTrue(isIndependentOfPythonMemory(true))
            assertTrue(isIndependentOfPythonMemory("abc"))
            assertTrue(isIndependentOfPythonMemory(listOf(1L, mapOf("k" to setOf(true, "x")))))

            assertFalse(isIndependentOfPythonMemory(obj.pointer), "a bare pointer is not a reference")
            assertFalse(isIndependentOfPythonMemory(obj), "a live PyObject is a Python reference, not a native value")
            assertFalse(
                isIndependentOfPythonMemory(listOf(1L, obj.pointer)),
                "one borrowed pointer anywhere taints the whole container"
            )
            assertFalse(
                isIndependentOfPythonMemory(mapOf("k" to listOf(obj))),
                "the check has to reach through nesting, not just the top level"
            )
        } finally {
            obj.close()
        }
    }

    // ---------------------------------------------------------------------------- ownership

    /**
     * [PyContext] must hand every [PyValue] a wrapper that owns its own
     * reference, whether or not the source type has a dedicated wrapper.
     * `typedWrap` increfs for the nine builtins it knows; for everything else it
     * returns the caller's wrapper unchanged, and a [PyValue] built on that
     * would dangle the moment the caller closed it.
     *
     * Measured as a `sys.getrefcount` difference, so the absolute numbers (which
     * include `getrefcount`'s own temporary) do not matter.
     */
    @Test
    fun pyContextGivesEveryPyValueItsOwnReference() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            class _PmOwnershipProbe:
                pass

            _pm_ownership_probe = _PmOwnershipProbe()
            _pm_ownership_list = [1, 2, 3]
            """.trimIndent()
        )
        val (getrefcount, refCount) = refCounter()
        val unwrapped = PythonTestFixture.eval("_pm_ownership_probe") // no dedicated wrapper
        val wrapped = PythonTestFixture.eval("_pm_ownership_list")    // typedWrap knows `list`
        try {
            val context = PyContext(ConversionStrategy.TYPED)

            val unwrappedBefore = refCount(unwrapped)
            val unwrappedValue = context.convertValue(unwrapped)
            assertTrue(unwrappedValue is PyValue<*>)
            assertEquals(
                unwrappedBefore + 1, refCount(unwrapped),
                "a PyValue over a type with no dedicated wrapper must take its own reference, " +
                    "not share the caller's wrapper"
            )

            val wrappedBefore = refCount(wrapped)
            val wrappedValue = context.convertValue(wrapped)
            assertTrue(wrappedValue is PyValue<*>)
            assertEquals(
                wrappedBefore + 1, refCount(wrapped),
                "typedWrap already increfs; this is the invariant both branches have to share"
            )
        } finally {
            unwrapped.close()
            wrapped.close()
            getrefcount.close()
            Python3.exec("del _pm_ownership_probe, _pm_ownership_list")
        }
    }

    // --------------------------------------------------------------------------- overhead

    /**
     * What the cache is for. The first read walks the whole container across the
     * FFI boundary; every later read is a field access. Reported rather than
     * merely asserted -- the assertion is deliberately loose (1000 cached reads
     * against one walk of 2000 elements), because the point is the order of
     * magnitude, not a threshold.
     */
    @Test
    fun cachedReadsCostNothingAgainstTheFirstWalk() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("[i for i in range(2000)]")
        val value = PyValue<List<Any?>>(obj)

        val clock = TimeSource.Monotonic
        val walkStart = clock.markNow()
        val first = value.asNative()
        val walk = walkStart.elapsedNow()
        assertEquals(2000, first.size)

        val cachedStart = clock.markNow()
        repeat(1000) { value.asNative() }
        val cached = cachedStart.elapsedNow()

        println(
            "PyValue lazy conversion: first walk of 2000 elements = $walk; " +
                "1000 cached reads = $cached"
        )
        assertTrue(
            cached < walk,
            "1000 cached reads ($cached) must cost less than one walk of 2000 elements ($walk)"
        )

        obj.close()
    }
}
