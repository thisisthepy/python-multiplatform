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
 * Wrapper around a Python coroutine object, what calling an `async def` returns (#524, the
 * baseline's `types/iteration/PyCoroutine.kt`).
 *
 * A coroutine is driven the way an event loop drives it: [send] runs it to its next suspension point
 * and returns what it is awaiting on (for a bare `await` of a Future, the Future), and when it
 * finishes the call raises [python.multiplatform.ffi.exceptions.errors.PyStopIteration] whose
 * `returnValue` is the coroutine's result. [throwInto] raises at the suspension point, [finish] is
 * `coroutine.close()`. Running one on an event loop is `asyncio.run(coroutine)` from Python, or
 * python-multiplatform's suspend bridge (SPEC U-5) from Kotlin.
 */
open class PyCoroutine(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** [obj] as a coroutine (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyCoroutine =
            adoptChecked(obj, "types", "CoroutineType", "a coroutine") { PyCoroutine(it, borrowed = true) }
    }

    /** `coroutine.__name__`. */
    val name: String get() = attrAsString("__name__")

    /** `coroutine.__qualname__`. */
    val qualifiedName: String get() = attrAsString("__qualname__")

    /** `cr_running`. */
    val isRunning: Boolean get() = attrAsBoolean("cr_running")

    /** `cr_suspended`: whether it is paused at an `await`. */
    val isSuspended: Boolean get() = attrAsBoolean("cr_suspended")

    /** `cr_frame`, or `null` once finished. */
    val frame: PyFrame? get() = attrWrappedOrNull("cr_frame") { PyFrame(it, borrowed = true) }

    /** `cr_code`. */
    val code: PyCode get() = getAttr("cr_code").use { PyCode(it.pointer, borrowed = true) }

    /** `cr_await`: the awaitable it is suspended on, or `null`. */
    val awaiting: PyObject? get() = attrObjectOrNull("cr_await")

    /** `coroutine.send(value)` (`None` when `null`); the first call must send `null`. */
    fun send(value: PyObject? = null): PyObject = getAttr("send").use { it(noneIfNull(value)) }

    /** `coroutine.throw(exception)`. */
    fun throwInto(exception: PyObject): PyObject = getAttr("throw").use { it(exception) }

    /** `coroutine.close()`. */
    fun finish() {
        getAttr("close").use { it() }.close()
    }
}
