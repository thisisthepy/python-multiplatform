package python.multiplatform.ffi.types.iteration

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.attrAsString
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.adoptChecked
import python.multiplatform.ffi.types.attrAsBoolean
import python.multiplatform.ffi.types.attrObjectOrNull
import python.multiplatform.ffi.types.attrWrappedOrNull
import python.multiplatform.ffi.types.code.PyCode
import python.multiplatform.ffi.types.code.PyFrame
import python.multiplatform.ffi.types.noneIfNull
import python.native.ffi.NativePointer
import python.native.ffi.PyObject_GetIter

/**
 * Wrapper around a Python generator object, what calling a `def` that contains `yield` returns
 * (#524, the baseline's `types/iteration/PyGenerator.kt`).
 *
 * The generator protocol is Python's own: [send] resumes it and returns the next yielded value, and
 * when the generator returns, the call raises
 * [python.multiplatform.ffi.exceptions.errors.PyStopIteration] whose `returnValue` is the
 * generator's `return` value. [throwInto] raises an exception at the paused `yield`, [finish] is
 * `generator.close()` (named so because [close] releases this wrapper's reference). There is no
 * `PyGen_*` function in the Stable ABI, so every member goes through the generator's attributes and
 * methods (`gi_frame`, `send`, ...).
 */
open class PyGenerator(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** [obj] as a generator (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyGenerator =
            adoptChecked(obj, "types", "GeneratorType", "a generator") { PyGenerator(it, borrowed = true) }
    }

    /** `generator.__name__`. */
    val name: String get() = attrAsString("__name__")

    /** `generator.__qualname__`. */
    val qualifiedName: String get() = attrAsString("__qualname__")

    /** `gi_running`: whether the generator is executing right now (on this or another thread). */
    val isRunning: Boolean get() = attrAsBoolean("gi_running")

    /** `gi_suspended`: whether it is paused at a `yield`. */
    val isSuspended: Boolean get() = attrAsBoolean("gi_suspended")

    /** `gi_frame`: the paused frame, or `null` once the generator has finished. */
    val frame: PyFrame? get() = attrWrappedOrNull("gi_frame") { PyFrame(it, borrowed = true) }

    /** `gi_code`: the code object the generator runs. */
    val code: PyCode get() = getAttr("gi_code").use { PyCode(it.pointer, borrowed = true) }

    /** `gi_yieldfrom`: the iterator a `yield from` is delegating to, or `null`. */
    val delegate: PyObject? get() = attrObjectOrNull("gi_yieldfrom")

    /**
     * `generator.send(value)`: resumes the generator with [value] (`None` when `null`) as the result
     * of the paused `yield`, and returns the next value it yields. The first call must send `null`.
     */
    fun send(value: PyObject? = null): PyObject = getAttr("send").use { it(noneIfNull(value)) }

    /** `next(generator)`: [send] with `None`. */
    fun next(): PyObject = send(null)

    /** `generator.throw(exception)`: raises [exception] at the paused `yield`; returns the next yielded value. */
    fun throwInto(exception: PyObject): PyObject = getAttr("throw").use { it(exception) }

    /** `generator.close()`: raises `GeneratorExit` at the paused `yield`, finishing the generator. */
    fun finish() {
        getAttr("close").use { it() }.close()
    }

    /** `iter(generator)`, which is the generator itself, as a [PyIterator]. */
    fun iterator(): PyIterator {
        val iterPointer = Python3.withPython { PyObject_GetIter(pointer) }
            ?: throw pyErrorOrGeneric("Failed to iterate over this generator")
        return PyIteratorObject(iterPointer, borrowed = false)
    }
}
