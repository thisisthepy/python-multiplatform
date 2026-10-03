package python.multiplatform.ref

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that operations leave CPython's reference counts where they found them.
 *
 * This is a different question from [GCLeakTest]. That one asks whether the collector eventually
 * releases what nobody holds any more. This header used to add that it was blocked by the GIL
 * still being held by the initialising thread -- it is not any more. ROADMAP §1 is closed:
 * `Python3.initialize()` parks its thread state with `PyEval_SaveThread()`, cleaner threads can
 * attach through `PyGILState_Ensure`, and [GCLeakTest] passes.
 *
 * The two files still ask different things. These tests close every wrapper explicitly, so they
 * exercise the accounting itself: does each operation take the references it claims to take, and
 * give back exactly those?
 *
 * Both failure directions matter and both are silent without a test like this. Releasing one
 * too few leaks; releasing one too many frees an object still in use, and the crash surfaces
 * later somewhere unrelated -- which is how the interpreter corruption found during the
 * lifetime work first showed up, as a segfault inside Py_Finalize.
 *
 * `sys.getrefcount` itself adds a temporary reference for its own argument, so only the
 * DIFFERENCE between two readings is meaningful; the absolute numbers are not.
 */
class RefCountTest {

    private fun withRefCounter(block: (target: PyObject, refCount: () -> Long) -> Unit) {
        if (!Python3.isInitialized) Python3.initialize()

        val sys = Python3.import("sys")
        val getrefcount = sys.getAttr("getrefcount")
        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val target = listType()
        try {
            block(target) {
                val n = getrefcount(target)
                try {
                    n.toString().toLong()
                } finally {
                    n.close()
                }
            }
        } finally {
            target.close()
            listType.close()
            builtins.close()
            getrefcount.close()
            sys.close()
        }
    }

    @Test
    fun borrowedWrapperTakesOneReferenceAndGivesItBack() = withRefCounter { target, refCount ->
        val before = refCount()
        val wrapper = PyObject(target.pointer, borrowed = true)
        assertEquals(before + 1, refCount(), "a borrowed wrapper should take exactly one reference")
        wrapper.close()
        assertEquals(before, refCount(), "closing it should give back exactly one")
    }

    @Test
    fun closingTwiceReleasesOnlyOnce() = withRefCounter { target, refCount ->
        val before = refCount()
        val wrapper = PyObject(target.pointer, borrowed = true)
        wrapper.close()
        wrapper.close()
        wrapper.close()
        assertEquals(
            before, refCount(),
            "close() must be idempotent -- releasing more than once frees an object still in use, " +
                "and the crash lands somewhere unrelated"
        )
    }

    @Test
    fun manyBorrowedWrappersBalanceExactly() = withRefCounter { target, refCount ->
        val before = refCount()
        val count = 100
        val wrappers = List(count) { PyObject(target.pointer, borrowed = true) }
        assertEquals(before + count, refCount(), "$count wrappers should take $count references")
        wrappers.forEach { it.close() }
        assertEquals(before, refCount(), "closing all of them should give back all $count")
    }

    @Test
    fun attributeAccessDoesNotDisturbTheOwnersCount() = withRefCounter { target, refCount ->
        val sys = Python3.import("sys")
        try {
            val before = refCount()
            repeat(50) {
                val attr = sys.getAttr("version")
                attr.close()
            }
            assertEquals(
                before, refCount(),
                "reading an unrelated object's attribute must not move this object's count"
            )
        } finally {
            sys.close()
        }
    }

    @Test
    fun repeatedAttributeAccessBalancesOnItsOwnTarget() {
        if (!Python3.isInitialized) Python3.initialize()
        val sys = Python3.import("sys")
        val getrefcount = sys.getAttr("getrefcount")
        try {
            val version = sys.getAttr("version")
            val refCount = {
                val n = getrefcount(version)
                try { n.toString().toLong() } finally { n.close() }
            }
            val before = refCount()
            repeat(50) { sys.getAttr("version").close() }
            assertEquals(
                before, refCount(),
                "getAttr returns a new reference each time; closing each one should leave the " +
                    "attribute's count unchanged"
            )
            version.close()
        } finally {
            getrefcount.close()
            sys.close()
        }
    }

    @Test
    fun invokeResultIsOwnedAndReleasable() = withRefCounter { _, _ ->
        val builtins = Python3.import("builtins")
        val listType = builtins.getAttr("list")
        val getrefcount = Python3.import("sys").getAttr("getrefcount")
        try {
            val made = listType()
            val n = getrefcount(made)
            val count = n.toString().toLong()
            n.close()
            // A freshly constructed object is held by this wrapper and by getrefcount's argument
            // slot at the moment of reading; what matters is that it is a small positive number
            // rather than something that has already been over-released.
            assertTrue(count in 1..3, "a freshly created object should hold a small count, got $count")
            made.close()
        } finally {
            getrefcount.close()
            listType.close()
            builtins.close()
        }
    }
}
