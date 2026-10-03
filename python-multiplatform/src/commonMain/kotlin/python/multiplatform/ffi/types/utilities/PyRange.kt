package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.attrAsInt
import python.multiplatform.ffi.callBuiltinStealing
import python.multiplatform.ffi.newIntsOrNone
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.iteration.PyIterable
import python.multiplatform.ffi.types.iteration.PyIterator
import python.native.ffi.NativePointer
import python.native.ffi.PyObject_GetIter
import python.native.ffi.PyObject_Length

/**
 * Wrapper around a Python `range` object.
 *
 * There is no `PyRange_*` family in the C API at all (let alone the limited
 * one) -- `range` is an ordinary builtin type with no dedicated entry points
 * -- so construction goes through `builtins.range(...)` and the three bounds
 * are read back as the plain attributes they are.
 */
open class PyRange(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyIterable {
    companion object {
        /**
         * Builds a new `range(start, stop, step)`.
         *
         * A zero [step] is rejected by Python itself with `ValueError`, which
         * surfaces here as a [python.multiplatform.ffi.exceptions.PyException]
         * rather than being pre-checked in Kotlin -- one source of truth for
         * the rule.
         */
        fun of(start: Int, stop: Int, step: Int = 1): PyRange = Python3.withPython {
            // callBuiltinStealing consumes the three new int references on every path.
            PyRange(callBuiltinStealing("range", newIntsOrNone(start, stop, step)), borrowed = false)
        }
    }

    val start: Int get() = attrAsInt("start")
    val stop: Int get() = attrAsInt("stop")
    val step: Int get() = attrAsInt("step")

    /**
     * `len(range(...))`.
     *
     * `range` computes its length arithmetically rather than iterating, so
     * this is one FFI crossing regardless of how wide the range is. A range
     * longer than `Int.MAX_VALUE` cannot be reported through this `Int`
     * return type; `len()` on such a range raises `OverflowError` in Python
     * too, so the ceiling is not new here.
     */
    val size: Int
        get() {
            val length = Python3.withPython { PyObject_Length(pointer) }
            if (length < 0) throw pyErrorOrGeneric("Failed to compute len() of this range")
            return length.toInt()
        }

    override fun iterator(): PyIterator {
        // PyObject_GetIter returns a new reference, which the PyIterator adopts.
        val iterPointer = Python3.withPython { PyObject_GetIter(pointer) }
            ?: throw pyErrorOrGeneric("Failed to obtain an iterator over this range")
        return PyIterator(iterPointer, borrowed = false)
    }
}
