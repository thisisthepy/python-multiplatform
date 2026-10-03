package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import python.multiplatform.Versions
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture

/**
 * The low-level layer: EmbedAPI functions one at a time, without the object model on top.
 *
 * ### Why this file exists
 *
 * Every other test under `commonTest` exercises the assembled layer — `Python3`, `PyObject`,
 * the collections. That is the right default, but it means a broken individual binding is only
 * ever seen through whatever the object model happens to call, and only in the combinations it
 * happens to use. Android demonstrated the cost of the mirror-image gap: its instrumented tests
 * covered only the low level, so the assembled path was never run on a device and turned out to
 * crash on its first call.
 *
 * These tests take each function on its own terms: does it return what its C counterpart
 * returns, does it report failure the documented way, and does it leave the reference count
 * where it found it.
 *
 * The reference conventions are the part worth being explicit about, because getting one wrong
 * is silent in both directions — one too few leaks, one too many frees an object still in use
 * and the crash lands somewhere unrelated.
 */
class EmbedApiLowLevelTest {

    /**
     * Goes through the shared fixture rather than calling `Python3.initialize` directly.
     *
     * Doing it directly crashed the whole desktop suite -- zero tests reported, a fatal error
     * inside `PyGILState_Ensure` -> `new_threadstate`. The fixture initialises exactly once for
     * the process and other classes already rely on that; a second initialise racing it, or one
     * arriving after another class finalised, leaves the interpreter in a state where attaching
     * a thread crashes. Run alone these tests passed, which is exactly how the interaction
     * stayed invisible.
     */
    private fun ready() = PythonTestFixture.available.also {
        check(it) { "CPython could not be initialized: ${PythonTestFixture.failureReason}" }
    }

    @Test
    fun versionRoundTripsThroughTheCApi() {
        ready()
        val v = Python3.withPython { Py_GetVersion() }
        assertNotNull(v, "Py_GetVersion returned null")
        // Against the *configured* version rather than a literal. What this proves is that the
        // library loaded the interpreter the build downloaded; a hardcoded release line turns
        // every version bump into an unrelated failure, which is exactly what
        // `-PpythonVersion=3.15.0rc1` produced here and in three sibling tests.
        val expected = Versions.currentVersion.compactVersionString
        assertTrue(v.startsWith(expected), "expected $expected.x, got $v")
    }

    @Test
    fun longsRoundTripThroughPythonInts() {
        ready()
        val probes = listOf(0L, 1L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE)
        for (probe in probes) {
            val obj = Python3.withPython { PyLong_FromLongLong(probe) }
            assertNotNull(obj, "PyLong_FromLongLong($probe) returned null")
            try {
                assertEquals(probe, Python3.withPython { PyLong_AsLongLong(obj) }, "round trip failed for $probe")
            } finally {
                Python3.withPython { Py_DecRef(obj) }
            }
        }
    }

    @Test
    fun stringsRoundTripThroughPythonStr() {
        ready()
        // Ascii, empty, and non-ascii -- the last one is what a fast-path encoder gets wrong.
        for (probe in listOf("hello", "", "한글과 emoji 🐍")) {
            val obj = Python3.withPython { PyUnicode_FromString(probe) }
            assertNotNull(obj, "PyUnicode_FromString returned null for \"$probe\"")
            try {
                assertEquals(probe, Python3.withPython { PyUnicode_AsUTF8(obj) })
            } finally {
                Python3.withPython { Py_DecRef(obj) }
            }
        }
    }

    @Test
    fun importReturnsANewReferenceAndMissingModulesReportFailure() {
        ready()
        val sys = Python3.withPython { PyImport_ImportModule("sys") }
        if (sys == null) Python3.withPython { PyErr_Print() }; assertNotNull(sys, "importing sys returned null")
        Python3.withPython { Py_DecRef(sys) }

        val missing = Python3.withPython { PyImport_ImportModule("definitely_not_a_real_module_xyz") }
        assertNull(missing, "importing a missing module should return null")
        // A failed import sets the error indicator, and calling on with one pending corrupts
        // whatever runs next -- so clearing it is part of the contract, not tidiness.
        Python3.withPython { PyErr_Clear() }
        assertNull(Python3.withPython { PyErr_Occurred() }, "the error indicator should be clear again")
    }

    @Test
    fun attributeLookupReturnsANewReferenceAndMissingNamesSetTheIndicator() {
        ready()
        val sys = Python3.withPython { PyImport_ImportModule("sys") }
        assertNotNull(sys)
        try {
            val version = Python3.withPython { PyObject_GetAttrString(sys, "version") }
            assertNotNull(version, "sys.version lookup returned null")
            Python3.withPython { Py_DecRef(version) }

            val missing = Python3.withPython { PyObject_GetAttrString(sys, "no_such_attribute_xyz") }
            assertNull(missing, "a missing attribute should return null")
            assertNotNull(Python3.withPython { PyErr_Occurred() }, "a missing attribute should set the error indicator")
            Python3.withPython { PyErr_Clear() }
        } finally {
            Python3.withPython { Py_DecRef(sys) }
        }
    }

    @Test
    fun listSizeMatchesWhatWasPutIn() {
        ready()
        val list = Python3.withPython { PyList_New(3) }
        if (list == null) Python3.withPython { PyErr_Print() }; assertNotNull(list, "PyList_New returned null")
        try {
            assertEquals(3L, Python3.withPython { PyList_Size(list) })
        } finally {
            Python3.withPython { Py_DecRef(list) }
        }
    }

    @Test
    fun borrowedAndNewReferencesBehaveAsDocumented() {
        ready()
        // PyList_GetItem borrows; PyList_SetItem steals. Getting either backwards is silent
        // until something unrelated crashes, so state the expectation here.
        val list = Python3.withPython { PyList_New(1) }
        assertNotNull(list)
        try {
            val item = Python3.withPython { PyLong_FromLongLong(7L) }
            assertNotNull(item)
            // SetItem steals the reference; do not release `item` afterwards.
            assertEquals(0, Python3.withPython { PyList_SetItem(list, 0L, item) })

            // GetItem borrows; the list still owns it, so do not release what comes back.
            val borrowed = Python3.withPython { PyList_GetItem(list, 0L) }
            assertNotNull(borrowed, "PyList_GetItem returned null")
            assertEquals(7L, Python3.withPython { PyLong_AsLongLong(borrowed) })
        } finally {
            Python3.withPython { Py_DecRef(list) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Weak references
    //
    // `PyWeakref_GetObject` was deprecated in 3.13 and removed in 3.15; `PyWeakref_GetRef` is
    // its replacement and exists in 3.13 and 3.14 too, so this binding can move without
    // dropping the version the build actually defaults to. The two differ in the part that is
    // silent when it is wrong -- the old one returned a *borrowed* reference and `Py_None` for
    // a dead referent, the new one returns a *new* reference and reports death as null. A
    // binding that returned the borrowed pointer under the new name would pass an identity
    // check and then corrupt the heap on the caller's matching `Py_DecRef`, so the reference
    // count is asserted directly rather than inferred.
    // ---------------------------------------------------------------------------------------

    /**
     * Fails, clearing the error indicator first.
     *
     * A plain `assertNotNull` on a C API result that failed leaves the indicator set, and every
     * class in this binary shares one interpreter (see [ready]) -- so the next `PyImport_Module`
     * to run anywhere returns null and reports a failure that has nothing to do with its own
     * subject. That is not hypothetical: getting the weakly-referenceable type wrong below took
     * two unrelated tests down with it, and the cascade is what showed up first.
     */
    private fun failClearingIndicator(message: String): Nothing {
        Python3.withPython { PyErr_Clear() }
        throw AssertionError(message)
    }

    /**
     * `sys.getrefcount(target)` on a raw pointer.
     *
     * The returned number includes the argument tuple's temporary reference, so it is one
     * higher than the count that exists independently of asking. Only *differences* between
     * two readings are used below, which makes that constant offset cancel.
     */
    private fun refCountOf(target: NativePointer): Long = Python3.withPython {
        val sys = PyImport_ImportModule("sys") ?: failClearingIndicator("sys could not be imported")
        try {
            val getrefcount = PyObject_GetAttrString(sys, "getrefcount")
                ?: failClearingIndicator("sys.getrefcount is unavailable")
            try {
                val args = PyTuple_New(1L) ?: failClearingIndicator("could not allocate the argument tuple")
                try {
                    // PyTuple_SetItem steals, and the caller keeps its own reference.
                    Python3.withPython { Py_IncRef(target) }
                    if (PyTuple_SetItem(args, 0L, target) != 0) {
                        failClearingIndicator("could not populate the argument tuple")
                    }
                    val result = PyObject_CallObject(getrefcount, args)
                        ?: failClearingIndicator("sys.getrefcount() failed")
                    try {
                        PyLong_AsLongLong(result)
                    } finally {
                        Py_DecRef(result)
                    }
                } finally {
                    Py_DecRef(args)
                }
            } finally {
                Py_DecRef(getrefcount)
            }
        } finally {
            Py_DecRef(sys)
        }
    }

    /**
     * A fresh, weakly-referenceable object: an empty `set`.
     *
     * Most of the obvious candidates are not weakly referenceable -- `int`, `str`, `tuple`,
     * `list`, `dict` and `types.SimpleNamespace` all refuse, and `PyWeakref_NewRef` answers by
     * returning null and raising `TypeError`. `set` and `frozenset` do support it, and `set` is
     * reachable from the C API without constructing a class first.
     */
    private fun newWeaklyReferenceableObject(): NativePointer = Python3.withPython {
        // PySet_New takes an iterable rather than NULL, so build it from an empty tuple.
        val empty = PyTuple_New(0L) ?: failClearingIndicator("could not allocate the empty tuple")
        try {
            PySet_New(empty) ?: failClearingIndicator("PySet_New failed")
        } finally {
            Py_DecRef(empty)
        }
    }

    /** Borrowed `Py_None`, reached through the builtins mapping since the ABI exposes no accessor. */
    private fun noneSingleton(): NativePointer = Python3.withPython {
        val builtins = PyEval_GetBuiltins() ?: failClearingIndicator("PyEval_GetBuiltins returned null")
        PyDict_GetItemString(builtins, "None") ?: failClearingIndicator("builtins has no None")
    }

    @Test
    fun getRefResolvesALiveReferentAndHandsBackANewReference() {
        ready()
        val referent = newWeaklyReferenceableObject()
        val ref = Python3.withPython { PyWeakref_NewRef(referent, noneSingleton()) }
            ?: failClearingIndicator("PyWeakref_NewRef returned null")
        try {
            val before = refCountOf(referent)

            val resolved = Python3.withPython { PyWeakref_GetRef(ref) }
                ?: failClearingIndicator("a live referent must resolve")
            assertEquals(
                referent.toRawValue(), resolved.toRawValue(),
                "PyWeakref_GetRef must hand back the referent itself"
            )

            // The distinguishing property: it is a *new* reference, not the borrowed one the
            // removed PyWeakref_GetObject returned. If this binding were wired to the old
            // function the delta would be 0 and the release below would be an over-decref.
            assertEquals(
                before + 1, refCountOf(referent),
                "PyWeakref_GetRef must take a reference of its own"
            )

            Python3.withPython { Py_DecRef(resolved) }
            assertEquals(
                before, refCountOf(referent),
                "releasing the resolved reference must return the count to where it started"
            )
        } finally {
            Python3.withPython { Py_DecRef(ref) }
            Python3.withPython { Py_DecRef(referent) }
        }
    }

    @Test
    fun getRefReportsADeadReferentAsNullWithoutSettingTheErrorIndicator() {
        ready()
        val referent = newWeaklyReferenceableObject()
        val ref = Python3.withPython { PyWeakref_NewRef(referent, noneSingleton()) }
            ?: failClearingIndicator("PyWeakref_NewRef returned null")
        try {
            // The call above owns the only strong reference, and a weak reference does not
            // count, so releasing it must actually finalise the object.
            Python3.withPython { Py_DecRef(referent) }

            val resolved = Python3.withPython { PyWeakref_GetRef(ref) }
            assertNull(resolved, "a dead referent must resolve to null, not to Py_None")

            // 0 (dead) and -1 (error) are different outcomes in C and both arrive here as null.
            // A clear error indicator is what separates them, so it is part of the contract.
            assertNull(
                Python3.withPython { PyErr_Occurred() },
                "a dead referent is not an error and must leave the indicator clear"
            )
        } finally {
            Python3.withPython { Py_DecRef(ref) }
        }
    }

    @Test
    fun importModuleNoBlockRemainsAnAliasOfImportModule() {
        ready()
        // CPython removed PyImport_ImportModuleNoBlock in 3.15; it had been a plain alias of
        // PyImport_ImportModule since 3.3. The name survives here as a deprecated shim so
        // downstream source keeps compiling, and this pins the shim to the thing it aliases:
        // both must return the same already-imported module object.
        @Suppress("DEPRECATION")
        val viaAlias = Python3.withPython { PyImport_ImportModuleNoBlock("sys") }
        assertNotNull(viaAlias, "the alias returned null")
        try {
            val direct = Python3.withPython { PyImport_ImportModule("sys") }
            assertNotNull(direct, "PyImport_ImportModule returned null")
            try {
                assertEquals(
                    direct.toRawValue(), viaAlias.toRawValue(),
                    "the alias must resolve to the same module object"
                )
            } finally {
                Python3.withPython { Py_DecRef(direct) }
            }
        } finally {
            Python3.withPython { Py_DecRef(viaAlias) }
        }
    }
}
