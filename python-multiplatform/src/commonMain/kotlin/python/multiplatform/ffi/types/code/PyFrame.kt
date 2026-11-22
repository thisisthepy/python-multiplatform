package python.multiplatform.ffi.types.code

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.attrAsInt
import python.multiplatform.ffi.attrAsIntOrNull
import python.multiplatform.ffi.types.adoptChecked
import python.multiplatform.ffi.types.attrWrappedOrNull
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python frame object, one activation of a code object (`sys._getframe()`, a
 * traceback's `tb_frame`, a paused generator's `gi_frame`) (#524, the baseline's
 * `types/code/PyFrame.kt`).
 *
 * `PyFrame_GetCode` / `PyFrame_GetLineNumber` / `PyFrame_GetBack` are not part of this project's
 * Stable ABI surface, so each property is the documented `f_*` attribute.
 */
open class PyFrame(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** [obj] as a frame (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyFrame =
            adoptChecked(obj, "types", "FrameType", "a frame") { PyFrame(it, borrowed = true) }
    }

    /** `f_code`: the code this frame runs. */
    val code: PyCode get() = getAttr("f_code").use { PyCode(it.pointer, borrowed = true) }

    /** `f_lineno`: the current line, or `null` when the frame has none (`None`). */
    val lineNumber: Int? get() = attrAsIntOrNull("f_lineno")

    /** `f_lasti`: the index of the last bytecode instruction run. */
    val lastInstruction: Int get() = attrAsInt("f_lasti")

    /** `f_back`: the calling frame, or `null` for the outermost one. */
    val back: PyFrame? get() = attrWrappedOrNull("f_back") { PyFrame(it, borrowed = true) }

    /** `f_globals`: the module globals this frame resolves names in. */
    val globals: PyObject get() = getAttr("f_globals")

    /** `f_builtins`. */
    val builtins: PyObject get() = getAttr("f_builtins")

    /**
     * `f_locals`: the frame's local variables. For a function frame this is a write-through proxy
     * (PEP 667), so a write reaches the variable.
     */
    val locals: PyObject get() = getAttr("f_locals")

    /** `frame.clear()`: drops the frame's local references; a running or suspended frame refuses. */
    fun clearLocals() {
        getAttr("clear").use { it() }.close()
    }
}
