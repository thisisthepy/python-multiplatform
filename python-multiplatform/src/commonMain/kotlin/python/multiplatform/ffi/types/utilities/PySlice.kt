package python.multiplatform.ffi.types.utilities

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.attrAsIntOrNull
import python.multiplatform.ffi.callBuiltinStealing
import python.multiplatform.ffi.newIntsOrNone
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python `slice` object (`start:stop:step`).
 *
 * `PySlice_New` and `PySlice_Unpack` are both outside the limited API and are
 * not declared in this project's `EmbedAPI` surface either, so a slice is
 * built by calling `builtins.slice(...)` and its three components are read
 * back as the attributes they are. Each component is `None` rather than
 * absent when omitted, which is why the accessors are nullable: `None` maps
 * to `null`, and only `null` -- a non-integer, non-`None` component is still
 * an error.
 */
open class PySlice(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** Builds a new `slice(start, stop, step)`; any of the three may be `null` (Python's `None`). */
        fun of(start: Int? = null, stop: Int? = null, step: Int? = null): PySlice = Python3.withPython {
            // callBuiltinStealing consumes the three new references on every path.
            PySlice(callBuiltinStealing("slice", newIntsOrNone(start, stop, step)), borrowed = false)
        }
    }

    val start: Int? get() = attrAsIntOrNull("start")
    val stop: Int? get() = attrAsIntOrNull("stop")
    val step: Int? get() = attrAsIntOrNull("step")
}
