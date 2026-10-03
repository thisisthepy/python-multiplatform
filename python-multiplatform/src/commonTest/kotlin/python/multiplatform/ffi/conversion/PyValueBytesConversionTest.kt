package python.multiplatform.ffi.conversion

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.types.basic.PyByteArray
import python.multiplatform.ffi.types.basic.PyBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * The one conversion ROADMAP §7b left open: `bytes`/`bytearray` -> Kotlin
 * `ByteArray`.
 *
 * §7b refused all three buffer types together, on the grounds that "their
 * native form is a pointer into the object's own buffer". That reason is about
 * the *pointer*, not about the type: a `ByteArray` is a copy by construction,
 * so it satisfies the same lifetime rule every other cached conversion does,
 * which is what §7b itself recorded ("a `ByteArray` copy would also be correct
 * under the rule and is not implemented"). `isIndependentOfPythonMemory`
 * already had the `ByteArray -> true` branch waiting for it.
 *
 * `memoryview` stays refused, and for a reason that survives the copy: a
 * memoryview is not necessarily a flat, C-contiguous run of bytes (it carries
 * a format, a shape and strides, and `.hex()` raises on a non-contiguous one),
 * so there is no single `ByteArray` that is its value.
 *
 * Red phase (recorded before any implementation existed): every assertion
 * about `bytes`/`bytearray` below failed -- `asNativeOrNull()` returned `null`
 * because neither `typedWrap` nor `pyObjectToNative` had an entry for either
 * type -- while `memoryviewIsStillRefused` passed. A failure here now is a
 * regression.
 */
class PyValueBytesConversionTest {

    /** Reuses the memory a just-released Python object occupied, so a dangling read would show. */
    private fun churnPythonHeap() {
        Python3.exec("_pm_bytes_churn = [''.join(['xx', str(i), 'yy']) for i in range(2000)]")
        Python3.exec("del _pm_bytes_churn")
    }

    // ------------------------------------------------------------------ the conversion itself

    /**
     * The payload deliberately contains an embedded NUL and bytes that are not
     * valid UTF-8. Both are the cases a "decode it as a string" shortcut gets
     * wrong -- `PyBytes_AsString` hands back a NUL-terminated `const char*`,
     * so `b'\x00'` would truncate and `b'\xfe'` would mojibake -- so they are
     * what pins the conversion to being byte-exact rather than text-shaped.
     */
    @Test
    fun bytesConvertToAByteExactByteArray() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("b'\\x00\\x01\\x7f\\x80\\xfe\\xff'")
        val value = PyValue<ByteArray>(obj)

        val converted = value.asNativeOrNull()
        assertNotNull(converted, "`bytes` must convert; it is a copy, so the lifetime rule allows it")
        assertContentEquals(
            byteArrayOf(0x00, 0x01, 0x7f, 0x80.toByte(), 0xfe.toByte(), 0xff.toByte()),
            converted,
            "every byte must survive, including the NUL and the non-UTF-8 ones"
        )

        obj.close()
    }

    @Test
    fun emptyBytesConvertToAnEmptyByteArray() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("b''")
        val value = PyValue<ByteArray>(obj)
        assertContentEquals(ByteArray(0), value.asNative())
        obj.close()
    }

    @Test
    fun aBytearrayConvertsToTheSameByteArrayShapeAsBytes() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("bytearray(b'\\x00\\xff\\x10')")
        val value = PyValue<ByteArray>(obj)
        assertContentEquals(
            byteArrayOf(0x00, 0xff.toByte(), 0x10), value.asNativeOrNull(),
            "a bytearray copies out exactly like bytes; refusing one and not the other would be arbitrary"
        )
        obj.close()
    }

    /** A typed wrapper as the source answers from its own accessor, like `PyInt`/`PyString` do. */
    @Test
    fun aTypedBytesWrapperSourceAnswersFromItsOwnAccessor() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("b'abc'")
        val typed = typedWrap(obj)
        try {
            assertNotSame(obj, typed, "typedWrap must have a dedicated wrapper for `bytes`")
            assertTrue(typed is PyProxy<*>, "that wrapper must be a PyProxy, or nothing can convert through it")
            assertContentEquals(byteArrayOf(0x61, 0x62, 0x63), PyValue<ByteArray>(typed).asNative())
        } finally {
            typed.close()
            obj.close()
        }
    }

    // ------------------------------------------------------------------------- caching, safely

    /**
     * The whole point of allowing the conversion: the `ByteArray` is a copy, so
     * it has to stay readable after the Python object it came from is freed and
     * the heap reused underneath it.
     */
    @Test
    fun aCachedByteArrayOutlivesItsPythonSource() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("bytes([1, 2, 3, 4, 5])")
        val value = PyValue<ByteArray>(obj)
        val first = value.asNative()
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), first)

        obj.close() // the only reference: the bytes object is freed here
        churnPythonHeap()

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5), value.asNative(),
            "the cache holds a Kotlin copy, so releasing the source must not reach it"
        )
        assertSame(first, value.asNative(), "the second read must come from the cache, not a second conversion")
    }

    /** The rule as code has to agree that a `ByteArray` may be cached. */
    @Test
    fun theCacheabilityRuleAcceptsAByteArrayIncludingInsideAContainer() = PythonTestFixture.withInterpreter {
        assertTrue(isIndependentOfPythonMemory(byteArrayOf(1, 2, 3)))
        assertTrue(isIndependentOfPythonMemory(ByteArray(0)))
        assertTrue(isIndependentOfPythonMemory(listOf(byteArrayOf(1), mapOf("k" to byteArrayOf(2)))))
    }

    /**
     * A `bytearray` is mutable, so its cached conversion is a snapshot for the
     * same reason a `list`'s is -- and gets the same remedy.
     */
    @Test
    fun aMutatedBytearrayKeepsItsSnapshotUntilTheCacheIsInvalidated() = PythonTestFixture.withInterpreter {
        Python3.exec("_pm_bytes_probe = bytearray(b'ab')")
        val obj = PythonTestFixture.eval("_pm_bytes_probe")
        val value = PyValue<ByteArray>(obj)

        val first = value.asNative()
        assertContentEquals(byteArrayOf(0x61, 0x62), first)

        Python3.exec("_pm_bytes_probe.append(0x63)")
        assertSame(first, value.asNative(), "the cache is a snapshot and does not follow the live bytearray")

        value.invalidateNativeCache()
        val second = value.asNative()
        assertNotSame(first, second)
        assertContentEquals(byteArrayOf(0x61, 0x62, 0x63), second, "invalidating the cache must force a fresh read")

        obj.close()
        Python3.exec("del _pm_bytes_probe")
    }

    // ------------------------------------------------------------- inside containers, and back

    /**
     * `pyObjectToNative` recurses, so `NATIVE` conversion of a container holding
     * `bytes` has to produce a `ByteArray` element rather than `str(obj)`'s
     * `"b'..'"` repr -- which is what the `else` branch would otherwise give.
     */
    @Test
    fun nativeConversionOfAContainerReachesBytesElements() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("{'k': [b'\\x01\\x02', bytearray(b'\\x03')]}")
        @Suppress("UNCHECKED_CAST")
        val native = PyContext(ConversionStrategy.NATIVE).convertValue(obj) as Map<Any?, Any?>

        val elements = native["k"] as List<*>
        assertContentEquals(byteArrayOf(0x01, 0x02), elements[0] as ByteArray)
        assertContentEquals(byteArrayOf(0x03), elements[1] as ByteArray)

        obj.close()
    }

    /**
     * The other direction. A `ByteArray` has exactly one unambiguous Python
     * spelling -- `bytes` -- so, unlike a `List`/`Map`/`Set`, `toPython()` can
     * materialise it without guessing (see [PyProxy.toPython]).
     */
    @Test
    fun aByteArrayMaterialisesBackIntoPythonBytes() = PythonTestFixture.withInterpreter {
        val value = PyValue<ByteArray>(initialNativeValue = byteArrayOf(0x00, 0x41, 0xff.toByte()))
        val obj: PyObject = value.asPyObject()
        try {
            assertEquals("bytes", obj.Type.name, "a ByteArray's Python counterpart is `bytes`")
            assertEquals("b'\\x00A\\xff'", obj.toString(), "and it must round-trip byte for byte")
            assertSame(obj, value.asPyObject(), "the Python side is cached too")
        } finally {
            obj.close()
        }
    }

    @Test
    fun aByteArrayRoundTripsThroughPythonAndBack() = PythonTestFixture.withInterpreter {
        val original = ByteArray(256) { it.toByte() }
        val pyObj = PyValue<ByteArray>(initialNativeValue = original).asPyObject()
        try {
            assertContentEquals(original, PyValue<ByteArray>(pyObj).asNative())
        } finally {
            pyObj.close()
        }
    }

    // ---------------------------------------------------------------------------- ownership

    /**
     * The conversion path builds and releases three Python objects of its own
     * (`hex`, its result, and -- on the write side -- the argument tuple), and
     * `typedWrap`'s probe in `hasNativeCounterpart` increfs a wrapper it then
     * closes. None of that may leave a net change on the source object: a
     * missing decref strands it forever, an extra one is a double free waiting
     * for an unrelated test to crash on.
     */
    @Test
    fun convertingBytesLeavesTheSourceRefcountUnchanged() = PythonTestFixture.withInterpreter {
        Python3.exec("_pm_bytes_refcount_probe = bytes([7, 8, 9])")
        val sys = Python3.import("sys")
        val getrefcount = sys.getAttr("getrefcount")
        sys.close()
        val obj = PythonTestFixture.eval("_pm_bytes_refcount_probe")
        try {
            fun refCount(): Long {
                val n = getrefcount(obj)
                try {
                    return n.toString().toLong()
                } finally {
                    n.close()
                }
            }

            val before = refCount()
            repeat(5) {
                val value = PyValue<ByteArray>(obj)
                assertContentEquals(byteArrayOf(7, 8, 9), value.asNative())
                value.invalidateNativeCache()
                assertContentEquals(byteArrayOf(7, 8, 9), value.asNative())
            }
            assertEquals(before, refCount(), "converting must not add or drop a reference on its source")

            // The write side too: five round trips through Python and back.
            repeat(5) {
                val built = PyValue<ByteArray>(initialNativeValue = byteArrayOf(7, 8, 9)).asPyObject()
                assertContentEquals(byteArrayOf(7, 8, 9), PyValue<ByteArray>(built).asNative())
                built.close()
            }
            assertEquals(before, refCount(), "building a new `bytes` must not touch an unrelated one")
        } finally {
            obj.close()
            getrefcount.close()
            Python3.exec("del _pm_bytes_refcount_probe")
        }
    }

    /** Both wrappers must report the Python type they actually stand for -- not `type`. */
    @Test
    fun theBufferWrappersReportTheirOwnPythonTypes() = PythonTestFixture.withInterpreter {
        assertEquals("bytes", PyBytes.TYPE.name)
        assertEquals("bytearray", PyByteArray.TYPE.name)
    }

    // ------------------------------------------------------------------------- still refused

    /**
     * `memoryview` is the buffer type the copy argument does *not* rescue: it
     * carries a format, a shape and strides, so there is no one `ByteArray`
     * that is its value, and a non-contiguous view cannot produce one at all.
     * Refused, with nothing left in the cache.
     */
    @Test
    fun memoryviewIsStillRefused() = PythonTestFixture.withInterpreter {
        val obj = PythonTestFixture.eval("memoryview(b'abc')")
        val value = PyValue<Any?>(obj)

        assertNull(value.asNativeOrNull(), "a memoryview is not a flat byte string and must not be guessed at")
        assertNull(value.cachedNativeValue, "a refused conversion must leave the cache empty")
        assertNull(value.asNativeOrNull(), "the refusal must be repeatable, not a one-shot")

        obj.close()
    }

    /** Exact-type dispatch, same as for every other builtin: a subclass of `bytes` has no counterpart. */
    @Test
    fun aBytesSubclassIsStillRefusedByExactTypeDispatch() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            class _PmBytesSubclass(bytes):
                pass
            """.trimIndent()
        )
        val obj = PythonTestFixture.eval("_PmBytesSubclass(b'abc')")
        val value = PyValue<Any?>(obj)
        assertNull(value.asNativeOrNull(), "dispatch is by exact type, mirroring PyLong_Check rather than isinstance")
        assertNull(value.cachedNativeValue)
        obj.close()
        Python3.exec("del _PmBytesSubclass")
    }

    // ------------------------------------------------------------------------------ overhead

    /**
     * What this conversion costs, and why the number is worth keeping.
     *
     * There is no `PyBytes_AsStringAndSize` in the Stable ABI subset this
     * library binds (only `PyBytes_AsString`, which returns a NUL-terminated
     * `String?` and is therefore unusable for arbitrary bytes), so the
     * conversion goes through `bytes.hex()`: three FFI crossings regardless of
     * length, plus an O(n) hex encode in CPython, a UTF-8 decode at the
     * platform boundary, and an O(n) hex decode in Kotlin -- four passes over
     * the data, against the one a direct buffer copy would cost.
     *
     * Reported against `str` of the same length, which *does* have a direct
     * binding (`PyUnicode_AsUTF8`), so the ratio is the price of not having
     * one. The assertion is only that the cache removes the cost entirely; the
     * ratio itself is printed, not asserted, because this machine is shared.
     */
    @Test
    fun bytesConversionCostIsPaidOncePerValueAndIsReportedAgainstStr() = PythonTestFixture.withInterpreter {
        val n = 64 * 1024
        val bytesObj = PythonTestFixture.eval("bytes($n)")
        val strObj = PythonTestFixture.eval("'a' * $n")
        val clock = TimeSource.Monotonic

        // Warm up both paths so neither measurement includes first-call resolution.
        PyValue<ByteArray>(bytesObj).asNative()
        PyValue<String>(strObj).asNative()

        val bytesValue = PyValue<ByteArray>(bytesObj)
        val bytesStart = clock.markNow()
        val convertedBytes = bytesValue.asNative()
        val bytesElapsed = bytesStart.elapsedNow()
        assertEquals(n, convertedBytes.size)

        val strValue = PyValue<String>(strObj)
        val strStart = clock.markNow()
        val convertedStr = strValue.asNative()
        val strElapsed = strStart.elapsedNow()
        assertEquals(n, convertedStr.length)

        val cachedStart = clock.markNow()
        repeat(1000) { bytesValue.asNative() }
        val cachedElapsed = cachedStart.elapsedNow()

        println(
            "PyValue bytes conversion ($n bytes): bytes via bytes.hex() = $bytesElapsed; " +
                "str via PyUnicode_AsUTF8 = $strElapsed; 1000 cached reads = $cachedElapsed"
        )
        assertTrue(
            cachedElapsed < bytesElapsed,
            "1000 cached reads ($cachedElapsed) must cost less than one conversion of $n bytes ($bytesElapsed)"
        )

        bytesObj.close()
        strObj.close()
    }
}
