package python.multiplatform.ffi.types.callables

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.attrAsString
import python.multiplatform.ffi.attrAsStringOrNull
import python.native.ffi.NativePointer

/**
 * Wrapper around a plain Python function object (`def f(...): ...` or a
 * `lambda`). Calling it is already covered by the inherited
 * [PyObject.invoke]; this adds the function-specific introspection bits.
 *
 * ### Why these three, and not `__code__` / `__globals__` / `__defaults__`
 *
 * The full C API reaches a function's code object, globals and defaults
 * through `PyFunction_GetCode`, `PyFunction_GetGlobals` and
 * `PyFunction_GetDefaults`, which read fields of `PyFunctionObject`. None of
 * those is in the limited API, and the struct they read is opaque under
 * `Py_LIMITED_API` -- there is no abi3-legal *direct* accessor for them, and
 * this project must not synthesise one by assuming a struct layout.
 *
 * That restricts the route, not the data: all three are ordinary attributes,
 * so `getAttr("__code__")` reaches them through `PyObject_GetAttrString` like
 * everything else here. They are not surfaced as typed properties because the
 * wrapper they would return (`PyCode`) is itself unimplementable through the
 * limited API in any richer way than the same attribute lookups -- see
 * `types/code/`.
 */
open class PyFunction(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    /** `function.__name__`. */
    val name: String
        get() = attrAsString("__name__")

    /** `function.__doc__`, or `null` if undocumented (i.e. `__doc__` is `None`). */
    val doc: String?
        get() = attrAsStringOrNull("__doc__")

    /**
     * `function.__qualname__`: the dotted path from module scope, e.g.
     * `"Outer.method"` for a function defined in a class body.
     */
    val qualifiedName: String
        get() = attrAsString("__qualname__")
}
