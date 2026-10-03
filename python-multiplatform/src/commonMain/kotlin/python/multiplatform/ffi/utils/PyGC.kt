package python.multiplatform.ffi.utils

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.modules.PyModule
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyLong_AsInt
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyObject_CallNoArgs
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_IsTrue
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * Thin façade over CPython's garbage collector and refcounting.
 *
 * ### Why this drives Python code rather than C entry points
 *
 * `PyGC_Collect`, `PyGC_Enable`, `PyGC_Disable` and `PyGC_IsEnabled` *are*
 * part of the Stable ABI (the last three since 3.10), so this is not an abi3
 * restriction. `PyGC_Collect` is now declared in `EmbedAPI` and wired on
 * every platform's `actual` set (ROADMAP §9) -- callers who want the direct
 * C entry point instead of `gc.collect()` can reach it as
 * `python.native.ffi.PyGC_Collect()` from inside [Python3.withPython], or
 * indirectly through [Python3.drainPendingReleases]. `PyGC_Enable`,
 * `PyGC_Disable` and `PyGC_IsEnabled` are not, so [enable], [disable] and
 * [isEnabled] below still go through the `gc` module, with identical
 * semantics at the cost of one Python-level call per operation.
 *
 * [refCount] is a genuinely different case. Reading a refcount directly needs
 * `Py_REFCNT`, which is a macro over the object header and is therefore not
 * usable under `Py_LIMITED_API` at the versions this project targets.
 * `sys.getrefcount` is the abi3-legal way to ask the question, and is what
 * this uses -- there is no cheaper route available to us.
 *
 * The two module handles are resolved once and kept for the lifetime of the
 * interpreter. As with [python.multiplatform.ffi.types.basic.PyNone] they do
 * not survive a `Py_Finalize()`/`Py_Initialize()` cycle.
 */
object PyGC {
    private val gc: PyModule by lazy { Python3.import("gc") }
    private val sys: PyModule by lazy { Python3.import("sys") }

    /** Calls `gc.<name>()` and returns the **new reference** it produced; the caller releases it. */
    private fun callGc(name: String): NativePointer = Python3.withPython {
        // PyObject_GetAttrString: new reference to the module-level function.
        val function = PyObject_GetAttrString(gc.pointer, name)
            ?: throw pyErrorOrGeneric("gc.$name is unavailable")
        try {
            PyObject_CallNoArgs(function) ?: throw pyErrorOrGeneric("gc.$name() failed")
        } finally {
            Py_DecRef(function)
        }
    }

    /** Calls `gc.<name>()` for its side effect only, releasing the `None` it hands back. */
    private fun callGcForEffect(name: String) {
        val result = callGc(name)
        Python3.withPython { Py_DecRef(result) }
    }

    /**
     * `gc.collect()`; returns the number of unreachable objects found.
     *
     * Equivalent to `withPython { PyGC_Collect() }` from `python.native.ffi` -- both bottom out in
     * `gc_collect_internal` -- at the cost of one Python-level call. Use the direct binding instead
     * if that call is measurable in context.
     */
    fun collect(): Int {
        val result = callGc("collect")
        return Python3.withPython {
            try {
                val count = PyLong_AsInt(result)
                // -1 is both a legitimate value and the failure return; only the error
                // indicator tells them apart.
                if (count == -1 && PyErr_Occurred() != null) {
                    throw pyErrorOrGeneric("gc.collect() did not return an int")
                }
                count
            } finally {
                Py_DecRef(result)
            }
        }
    }

    /** `gc.enable()`. */
    fun enable() = callGcForEffect("enable")

    /** `gc.disable()`. */
    fun disable() = callGcForEffect("disable")

    /** `gc.isenabled()`. */
    fun isEnabled(): Boolean {
        val result = callGc("isenabled")
        return Python3.withPython {
            val truthy = try {
                PyObject_IsTrue(result)
            } finally {
                Py_DecRef(result)
            }
            if (truthy < 0) throw pyErrorOrGeneric("gc.isenabled() returned a value with no truth value")
            truthy != 0
        }
    }

    /**
     * `sys.getrefcount(obj) - 1`.
     *
     * The subtraction removes the temporary reference the call itself holds:
     * the argument tuple built below owns one for the duration of the call,
     * so the raw number is always one higher than what exists independently
     * of asking. Even so, only *differences* between two readings are firm --
     * CPython makes no promise about the absolute count of interned or
     * immortal objects.
     */
    fun refCount(obj: PyObject): Long = Python3.withPython {
        val getrefcount = PyObject_GetAttrString(sys.pointer, "getrefcount")
            ?: throw pyErrorOrGeneric("sys.getrefcount is unavailable")
        try {
            val argTuple = PyTuple_New(1) ?: throw pyErrorOrGeneric("Failed to build the argument tuple")
            try {
                // PyTuple_SetItem steals, and obj keeps its own reference, so hand over a fresh +1.
                Py_IncRef(obj.pointer)
                if (PyTuple_SetItem(argTuple, 0, obj.pointer) != 0) {
                    throw pyErrorOrGeneric("Failed to populate the argument tuple")
                }
                val result = PyObject_CallObject(getrefcount, argTuple)
                    ?: throw pyErrorOrGeneric("sys.getrefcount() failed")
                try {
                    val count = PyLong_AsLongLong(result)
                    if (count == -1L && PyErr_Occurred() != null) {
                        throw pyErrorOrGeneric("sys.getrefcount() did not return an int")
                    }
                    count - 1
                } finally {
                    Py_DecRef(result)
                }
            } finally {
                Py_DecRef(argTuple)
            }
        } finally {
            Py_DecRef(getrefcount)
        }
    }
}
