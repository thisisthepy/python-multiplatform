package python.multiplatform.ffi.types.code

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.attrAsInt
import python.multiplatform.ffi.attrAsString
import python.multiplatform.ffi.types.adoptChecked
import python.multiplatform.ffi.types.attrAsStringList
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python code object, the compiled body of a function, module or class
 * (`f.__code__`, `frame.f_code`) (#524, the baseline's `types/code/PyCode.kt`).
 *
 * `PyCodeObject`'s fields are opaque under the limited API and `PyCode_*` is not in the Stable ABI,
 * so each property is the documented `co_*` attribute.
 */
open class PyCode(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** [obj] as a code object (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyCode =
            adoptChecked(obj, "types", "CodeType", "a code object") { PyCode(it, borrowed = true) }

        /** `co_flags` bit of a generator function. */
        const val CO_GENERATOR: Int = 0x20

        /** `co_flags` bit of an `async def`. */
        const val CO_COROUTINE: Int = 0x80

        /** `co_flags` bit of an `async def` containing `yield`. */
        const val CO_ASYNC_GENERATOR: Int = 0x200
    }

    /** `co_name`. */
    val name: String get() = attrAsString("co_name")

    /** `co_qualname`. */
    val qualifiedName: String get() = attrAsString("co_qualname")

    /** `co_filename`. */
    val filename: String get() = attrAsString("co_filename")

    /** `co_firstlineno`. */
    val firstLineNumber: Int get() = attrAsInt("co_firstlineno")

    /** `co_argcount`: positional parameters, positional-only ones included. */
    val argumentCount: Int get() = attrAsInt("co_argcount")

    /** `co_posonlyargcount`. */
    val positionalOnlyArgumentCount: Int get() = attrAsInt("co_posonlyargcount")

    /** `co_kwonlyargcount`. */
    val keywordOnlyArgumentCount: Int get() = attrAsInt("co_kwonlyargcount")

    /** `co_flags`. */
    val flags: Int get() = attrAsInt("co_flags")

    /** `co_varnames`: parameters first, then the other local variables. */
    val localNames: List<String> get() = attrAsStringList("co_varnames")

    /** `co_cellvars`: locals that inner functions capture. */
    val cellNames: List<String> get() = attrAsStringList("co_cellvars")

    /** `co_freevars`: variables this code captures from enclosing scopes. */
    val freeNames: List<String> get() = attrAsStringList("co_freevars")

    /** `co_names`: global and attribute names the bytecode uses. */
    val names: List<String> get() = attrAsStringList("co_names")

    val isGenerator: Boolean get() = flags and CO_GENERATOR != 0
    val isCoroutine: Boolean get() = flags and CO_COROUTINE != 0
    val isAsyncGenerator: Boolean get() = flags and CO_ASYNC_GENERATOR != 0
}
