package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.attrAsString
import python.multiplatform.ffi.types.adoptChecked
import python.multiplatform.ffi.types.attrAsBoolean
import python.multiplatform.ffi.types.attrObjectOrNull
import python.multiplatform.ffi.types.attrWrappedOrNull
import python.multiplatform.ffi.types.code.PyCode
import python.multiplatform.ffi.types.code.PyFrame
import python.multiplatform.ffi.types.noneIfNull
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python asynchronous generator, what calling an `async def` that contains `yield`
 * returns (#524, the baseline's `types/iteration/PyAsyncGenerator.kt`).
 *
 * Each step is an awaitable, as in Python: [next] is `anext(agen)`, [send] is `agen.asend(value)`,
 * [throwInto] is `agen.athrow(exception)` and [finish] is `agen.aclose()`. Awaiting one is an event
 * loop's job; driven by hand, `send(null)` on the awaitable runs the step and its
 * [python.multiplatform.ffi.exceptions.errors.PyStopIteration] carries the yielded value, while the
 * end of the generator raises [python.multiplatform.ffi.exceptions.errors.PyStopAsyncIteration].
 */
open class PyAsyncGenerator(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** [obj] as an async generator (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyAsyncGenerator =
            adoptChecked(obj, "types", "AsyncGeneratorType", "an async generator") { PyAsyncGenerator(it, borrowed = true) }
    }

    /** `agen.__name__`. */
    val name: String get() = attrAsString("__name__")

    /** `agen.__qualname__`. */
    val qualifiedName: String get() = attrAsString("__qualname__")

    /** `ag_running`. */
    val isRunning: Boolean get() = attrAsBoolean("ag_running")

    /** `ag_frame`, or `null` once finished. */
    val frame: PyFrame? get() = attrWrappedOrNull("ag_frame") { PyFrame(it, borrowed = true) }

    /** `ag_code`. */
    val code: PyCode get() = getAttr("ag_code").use { PyCode(it.pointer, borrowed = true) }

    /** `ag_await`: the awaitable it is suspended on, or `null`. */
    val awaiting: PyObject? get() = attrObjectOrNull("ag_await")

    /** `anext(agen)`: an awaitable for the next value. */
    fun next(): PyObject = getAttr("__anext__").use { it() }

    /** `agen.asend(value)` (`None` when `null`): an awaitable. */
    fun send(value: PyObject? = null): PyObject = getAttr("asend").use { it(noneIfNull(value)) }

    /** `agen.athrow(exception)`: an awaitable. */
    fun throwInto(exception: PyObject): PyObject = getAttr("athrow").use { it(exception) }

    /** `agen.aclose()`: an awaitable that finishes the generator. */
    fun finish(): PyObject = getAttr("aclose").use { it() }
}
