package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.adoptChecked
import python.multiplatform.ffi.types.basic.PyNone
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyWeakref_GetRef
import python.native.ffi.PyWeakref_NewProxy
import python.native.ffi.PyWeakref_NewRef

/**
 * Wrapper around a Python weak reference (`weakref.ref`) (#524, the baseline's
 * `types/utilities/PyWeakRef.kt`).
 *
 * The weak reference does not keep its referent alive: [get] returns a new strong reference while
 * the referent lives and `null` afterwards. Built on the Stable ABI's `PyWeakref_NewRef` and
 * `PyWeakref_GetRef` (3.13+, the replacement for the removed `PyWeakref_GetObject`).
 */
open class PyWeakRef(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** [obj] as a weak reference (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyWeakRef =
            adoptChecked(obj, "weakref", "ReferenceType", "a weak reference") { PyWeakRef(it, borrowed = true) }

        /**
         * `weakref.ref(target, callback)`. [callback], when given, is called with the weak reference
         * once [target] is collected. Throws [python.multiplatform.ffi.exceptions.errors.PyTypeError]
         * when [target] cannot be weakly referenced (an `int`, a `str`, ...).
         */
        fun of(target: PyObject, callback: PyObject? = null): PyWeakRef {
            val refPointer = Python3.withPython { PyWeakref_NewRef(target.pointer, (callback ?: PyNone.get()).pointer) }
                ?: throw pyErrorOrGeneric("Failed to create a weak reference")
            return PyWeakRef(refPointer, borrowed = false)
        }

        /** `weakref.proxy(target, callback)`: an object that forwards to [target] while it lives. */
        fun proxy(target: PyObject, callback: PyObject? = null): PyObject {
            val proxyPointer = Python3.withPython { PyWeakref_NewProxy(target.pointer, (callback ?: PyNone.get()).pointer) }
                ?: throw pyErrorOrGeneric("Failed to create a weak proxy")
            return PyObject(proxyPointer, false)
        }
    }

    /** The referent as a new strong reference, or `null` once it has been collected. */
    fun get(): PyObject? {
        val referent = Python3.withPython { PyWeakref_GetRef(pointer) }
        if (referent == null) {
            if (Python3.withPython { PyErr_Occurred() } != null) throw pyErrorOrGeneric("Failed to resolve a weak reference")
            return null
        }
        return PyObject(referent, false)
    }

    /** Whether the referent is still alive. */
    val isAlive: Boolean get() = get()?.use { true } ?: false
}
