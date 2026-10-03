package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.adoptingNewReference
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyErr_GetRaisedException
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyErr_SetRaisedException
import python.native.ffi.PyErr_SetString
import python.native.ffi.PyException_GetCause
import python.native.ffi.PyException_GetContext
import python.native.ffi.PyException_GetTraceback
import python.native.ffi.PyObject_Str
import python.native.ffi.PyObject_Type
import python.native.ffi.PyType_GetName
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * `True` if [this] points at Python's `None` singleton. There is no direct
 * `Py_None` accessor exposed through this project's FFI layer, so this is
 * approximated by comparing `type(x).__name__` to `"NoneType"` -- sufficient
 * here because the only place this is used ([PyException_GetCause] results)
 * only ever hands back either a real exception instance or `None`.
 *
 * `internal` (module-wide) rather than `private` because it's shared between
 * this file and [python.multiplatform.ffi.exceptions.PyTraceback], which is
 * a different file in the same package.
 */
internal fun NativePointer.isNoneObject(): Boolean {
    val typePointer = python.multiplatform.ffi.Python3.withPython { PyObject_Type(this) } ?: run {
        python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
        return false
    }
    // Two new references, neither owned by any wrapper, so both releases go in a finally
    // rather than after the decode.
    try {
        val namePointer = python.multiplatform.ffi.Python3.withPython { PyType_GetName(typePointer) } ?: return false
        val name = try {
            python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(namePointer) }
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(namePointer) }
        }
        return name == "NoneType"
    } finally {
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(typePointer) }
    }
}

/**
 * Kotlin-side mirror of a Python exception.
 *
 * Historically this only carried a plain message (used throughout
 * [python.multiplatform.ffi.PyObject] / [python.multiplatform.ffi.PyType] for
 * FFI-level failures where no live Python error is involved). It now also
 * exposes the pieces of `python.multiplatform.ffi.Python3.withPython { PyErr_Fetch() }` / `PyErr_GetRaisedException()`
 * (type, value, traceback) so a genuine Python-level error can be surfaced
 * as a fully-populated Kotlin throwable, matching the mermaid sketch
 * (`type`, `value`, `traceback`, `message`, `cause`, `context`).
 *
 * [errMsg] stays the primary constructor parameter (and thus [message]) so
 * every existing call site (`PyException("...")`) keeps compiling unchanged.
 */
open class PyException(
    val errMsg: String,
    val type: PyType? = null,
    val value: PyObject? = null,
    val traceback: PyTraceback? = null,
    /** Python's `__context__`: the exception that was being handled when this one was raised, if any. */
    val context: PyException? = null,
    cause: PyException? = null,
) : Exception(errMsg, cause) {

    companion object {
        /**
         * Builds a [PyException] from whatever CPython's error indicator
         * currently holds (via `python.multiplatform.ffi.Python3.withPython { PyErr_GetRaisedException() }`), clearing the
         * indicator in the process. Returns `null` if no error is currently
         * set.
         *
         * This is the one central bridge from "a C API call returned a null
         * that means an exception is pending" to a proper Kotlin throwable;
         * every other class in this module (`PyObject`, `PyType`, `Python3`)
         * routes its null-return error paths through here (via the
         * `pyErrorOrGeneric` helper in `PyObject.kt`) rather than raising a
         * bare, type-less [PyException].
         */
        fun fromCurrentError(): PyException? {
            // python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } is a *borrowed* reference used purely as a
            // presence check here -- it must not be decref'd.
            if (python.multiplatform.ffi.Python3.withPython { PyErr_Occurred() } == null) return null

            // python.multiplatform.ffi.Python3.withPython { PyErr_GetRaisedException() } hands back a new reference to the
            // exception *instance* and atomically clears the indicator. In
            // CPython >= 3.12 there is no separate "type"/"traceback" triple
            // to fetch -- the instance itself carries its type
            // (PyObject_Type) and traceback (PyException_GetTraceback).
            val excPointer = python.multiplatform.ffi.Python3.withPython { PyErr_GetRaisedException() } ?: return null
            return fromExceptionInstance(excPointer)
        }

        /**
         * Builds a [PyException] from [excPointer], an *owned* (new or
         * already-incref'd) reference to a live `BaseException` instance --
         * either the exception just pulled off the error indicator, or one
         * reached transitively via `__context__`/`__cause__`.
         *
         * Ownership of [excPointer] is transferred to the returned
         * [PyException.value] (a plain [PyObject] wrapping it with
         * `borrowed = false`, i.e. no extra incRef); the caller must not
         * touch [excPointer] again after calling this.
         */
        private fun fromExceptionInstance(excPointer: NativePointer): PyException {
            // First, before anything that can throw. PyObject(_, borrowed = false) takes
            // ownership of excPointer -- no extra incRef, matching the "new reference" we were
            // handed -- and from here on a cleaner can reclaim it. Doing this last (as it used
            // to be) meant a failure anywhere below stranded the exception instance itself, in
            // the one code path that exists to report failures.
            val value = PyObject(excPointer, false)

            // New reference; PyType.getInstance() settles it either way -- adopting it on a
            // cache miss, releasing it on a cache hit.
            val typePointer = python.multiplatform.ffi.Python3.withPython { PyObject_Type(excPointer) }
            if (typePointer == null) python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
            val type = typePointer?.adoptingNewReference { PyType.getInstance(it) }

            val message = messageOf(excPointer)

            // New reference, or null if this exception was never associated
            // with a traceback (e.g. constructed but never raised/propagated).
            val tracebackPointer = python.multiplatform.ffi.Python3.withPython { PyException_GetTraceback(excPointer) }
            val traceback = tracebackPointer?.let { PyTraceback(it, false) }

            // New reference; python.multiplatform.ffi.Python3.withPython { PyException_GetContext() } itself returns null
            // when there genuinely is no context (unlike GetCause, see below).
            val contextPointer = python.multiplatform.ffi.Python3.withPython { PyException_GetContext(excPointer) }
            val context = contextPointer?.let { fromExceptionInstance(it) }

            // New reference; unlike GetContext, python.multiplatform.ffi.Python3.withPython { PyException_GetCause() } always
            // returns *something* -- either a real exception instance, or the
            // `None` singleton when `raise ... from ...` was never used. Only
            // recurse for the former; release (and drop) the latter.
            val causePointer = python.multiplatform.ffi.Python3.withPython { PyException_GetCause(excPointer) }
            val cause = when {
                causePointer == null -> null
                causePointer.isNoneObject() -> {
                    python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(causePointer) }
                    null
                }
                else -> fromExceptionInstance(causePointer)
            }

            return PyException(message, type, value, traceback, context, cause)
        }

        /** `str(excPointer)`, used as this [PyException]'s [errMsg]. */
        private fun messageOf(excPointer: NativePointer): String {
            // PyObject_Str: new reference on success. This is already deep
            // inside error-reporting machinery, so on failure (e.g. a buggy
            // `__str__` on a custom exception) fall back to a placeholder
            // rather than letting a second exception escape from here --
            // but still clear the indicator it would otherwise leave set,
            // per the "never call back into the C API with a pending
            // exception" contract (see the *OrNull helpers on PyObject).
            val strPointer = python.multiplatform.ffi.Python3.withPython { PyObject_Str(excPointer) } ?: run {
                python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                return "<unprintable exception>"
            }
            val message = try {
                python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(strPointer) }
            } finally {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(strPointer) }
            }
            return message ?: "<unprintable exception>"
        }
    }

    /** Re-raises this exception into the current Python error indicator (`PyErr_Restore`/`PyErr_SetObject`). */
    fun restore() {
        val liveValue = value
        val liveType = type
        when {
            liveValue != null -> {
                // python.multiplatform.ffi.Python3.withPython { PyErr_SetRaisedException() } *steals* a reference to its
                // argument. `value` still owns its own reference for the rest
                // of its lifetime (released by PyObject.clean()/decRef on
                // close), so the interpreter needs a fresh one, not that one.
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(liveValue.pointer) }
                python.multiplatform.ffi.Python3.withPython { PyErr_SetRaisedException(liveValue.pointer) }
            }
            liveType != null -> {
                // No live exception instance to hand back (e.g. this
                // PyException was constructed by hand as
                // `PyException(message, type = someType)`); synthesize one
                // from the message instead. PyErr_SetString does not steal
                // `liveType.pointer` -- CPython increfs the type internally.
                python.multiplatform.ffi.Python3.withPython { PyErr_SetString(liveType.pointer, errMsg) }
            }
            // else: nothing Python-level to restore (a purely Kotlin-side
            // PyException with no captured type/value) -- there is no
            // meaningful error indicator to set.
        }
    }
}
