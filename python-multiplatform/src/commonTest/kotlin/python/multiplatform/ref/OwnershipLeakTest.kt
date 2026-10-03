package python.multiplatform.ref

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.basic.asPyObject
import python.multiplatform.ffi.types.collections.PyDict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The leaks the collector cannot reach.
 *
 * [GCLeakTest] and [RefCountTest] both measure references that some Kotlin object owns: either
 * it is closed explicitly, or the collector takes the wrapper and its cleaner releases the
 * reference. This file measures the case where **no Kotlin object ever owns the reference at
 * all** -- a C API call hands back a new reference, and control leaves the function before any
 * wrapper adopts it. There is nothing for a cleaner to attach to, so the reference is lost for
 * the life of the interpreter no matter how hard the collector runs.
 *
 * Two shapes produce that, and both are measured here:
 *
 * 1. an exception thrown between the C call and the wrap (ROADMAP §4's first "cannot be freed");
 * 2. a wrapper *declining* to adopt the reference it was handed -- `PyType.getInstance` returning
 *    a cached instance and dropping the caller's reference on the floor.
 *
 * Every assertion here is a difference between two `sys.getrefcount` readings of an object that
 * stays alive throughout, so the absolute numbers (which include `getrefcount`'s own temporary
 * argument reference) do not matter.
 */
class OwnershipLeakTest {

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
     * `PyDict.fromMap` allocates the dict first and populates it afterwards. An unhashable key
     * (a `list`) makes `PyDict_SetItem` fail partway through, and the half-built dict -- which by
     * then holds a reference to every key and value already stored -- is still only a raw
     * pointer at that moment. Losing it leaks the dict *and* everything it had adopted.
     *
     * Repeated so the leak cannot be mistaken for measurement noise: before the fix each attempt
     * adds exactly one to both counts.
     */
    @Test
    fun aDictBuildThatFailsPartwayReleasesTheHalfBuiltDict() = PythonTestFixture.withInterpreter {
        val (getrefcount, refCount) = refCounter()
        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val storedValue = listType()
        val unhashableKey = listType() // a list is unhashable, so it can never become a dict key
        val goodKey = "ownership-leak-probe".asPyObject()
        try {
            val attempts = 50
            val valueBefore = refCount(storedValue)
            val keyBefore = refCount(goodKey)

            repeat(attempts) {
                assertFailsWith<PyException>("an unhashable key must make fromMap fail") {
                    // Insertion-ordered on purpose: the first pair goes in (and is adopted by the
                    // dict), the second one fails.
                    PyDict.fromMap(linkedMapOf(goodKey to storedValue, unhashableKey to storedValue))
                }
            }

            assertEquals(
                valueBefore, refCount(storedValue),
                "$attempts failed dict builds must not each leave a reference behind: the dict " +
                    "that adopted this value was never wrapped, so no cleaner can ever release it"
            )
            assertEquals(
                keyBefore, refCount(goodKey),
                "the key the half-built dict had already adopted must be released with it"
            )
        } finally {
            goodKey.close()
            unhashableKey.close()
            storedValue.close()
            listType.close()
            builtins.close()
            getrefcount.close()
        }
    }

    /**
     * `PyObject_Type` returns a **new** reference on every call, and `PyType.getInstance` is
     * documented to take ownership of it. On a cache hit it used not to: it returned the cached
     * `PyType` and left the caller's reference owned by nobody.
     *
     * That was argued to be harmless because built-in types are immortal, which is true of `int`
     * and `list` and false of every user-defined class -- so the probe class here is defined in
     * Python rather than borrowed from `builtins`.
     */
    @Test
    fun aCacheHitOnPyTypeReleasesTheReferenceItWasHanded() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            class OwnershipLeakProbeType:
                pass

            ownership_leak_probe_instance = OwnershipLeakProbeType()
            """.trimIndent()
        )
        val (getrefcount, refCount) = refCounter()
        val main = Python3.import("__main__")
        val probeType = main.getAttr("OwnershipLeakProbeType")
        val instance = main.getAttr("ownership_leak_probe_instance")
        try {
            // The first lookup is a cache miss; the cache legitimately keeps that reference for
            // the life of the interpreter, so it must not be part of the measurement.
            PyObject(instance.pointer, borrowed = true).let { it.Type; it.close() }

            val before = refCount(probeType)
            val lookups = 50
            repeat(lookups) {
                // `Type` is lazy per wrapper, so a fresh wrapper is what forces a fresh
                // PyObject_Type call -- and therefore a fresh new reference to the type.
                val wrapper = PyObject(instance.pointer, borrowed = true)
                wrapper.Type
                wrapper.close()
            }

            assertEquals(
                before, refCount(probeType),
                "$lookups cache hits on PyType.getInstance must not accumulate references to a " +
                    "user-defined type, which -- unlike a builtin -- is not immortal"
            )
        } finally {
            instance.close()
            probeType.close()
            main.close()
            getrefcount.close()
        }
    }

    /**
     * The same defect, in the fixture the rest of the suite is built on.
     *
     * `PythonTestFixture.mainGlobals()` reads `__main__.__dict__` with `PyObject_GetAttrString`,
     * which returns a **new** reference, and used to wrap it with `borrowed = true` -- i.e. take
     * a *second* reference on top of the one it was already handed. The wrapper releases one, so
     * every call left one behind, and `PythonTestFixture.eval()` calls it once per evaluation.
     *
     * It was survivable only because the object leaked is `__main__`'s namespace, which outlives
     * the interpreter anyway. It is measured here because it is the exact inverse of the
     * `borrowed = false` misuse that ROADMAP §1 spent three attempts on, and because a fixture
     * that models the ownership rule wrongly is the worst place to keep one.
     */
    @Test
    fun theTestFixtureDoesNotLeakMainGlobals() = PythonTestFixture.withInterpreter {
        val (getrefcount, refCount) = refCounter()
        // Not part of the measurement: this handle is held for the whole test, so its own
        // reference is constant across the loop below.
        val probe = PythonTestFixture.mainGlobals()
        try {
            val before = refCount(probe)
            val calls = 50
            repeat(calls) { PythonTestFixture.mainGlobals().close() }
            assertEquals(
                before, refCount(probe),
                "$calls balanced mainGlobals() calls must leave __main__.__dict__'s count where " +
                    "they found it; a rise means the wrapper took a reference it was already given"
            )
        } finally {
            probe.close()
            getrefcount.close()
        }
    }
}
