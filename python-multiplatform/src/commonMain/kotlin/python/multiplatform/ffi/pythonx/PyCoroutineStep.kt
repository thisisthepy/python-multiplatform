package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.ffi.withGIL
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyErr_ExceptionMatches
import python.native.ffi.PyErr_GetRaisedException
import python.native.ffi.PyImport_ImportModule
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_IsInstance
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef

/**
 * What one step of a Python coroutine produced (SPEC U-5e; issue #164, item 13).
 *
 * Exactly one of the three, for one call of [step] or [throwInto]. The [PyObject] a [Yielded] or
 * [Returned] carries is a new reference the caller owns and closes; a [Raised] carries the
 * [PyException] with the error indicator already cleared.
 */
sealed class PyStep {

    /**
     * The coroutine suspended on [value]: the object its innermost `__await__` yielded (for an
     * `await` of an `asyncio` future, that future; for a `suspend` upcall's proxy, the `Future` the
     * binder handed out). The driver decides what to do with it and steps again.
     */
    class Yielded(val value: PyObject) : PyStep()

    /**
     * The coroutine returned [value], the `StopIteration` value: `None` for a bare `return`, and
     * `None` again for every step of an exhausted generator, as its own `StopIteration` says.
     */
    class Returned(val value: PyObject) : PyStep()

    /**
     * The step raised [exception], which is what the coroutine let out: its own failure, a
     * `CancelledError` ([PyException.isCancelledError], the cancellation signal), the interpreter's
     * refusal to step a finished coroutine (`RuntimeError: cannot reuse already awaited coroutine`)
     * or one that is already executing (`ValueError`), or `Context.run`'s refusal of a context that
     * is already entered (`RuntimeError`, the coroutine untouched). [PyException.toKotlinException]
     * applies SPEC U-2's mapping.
     */
    class Raised(val exception: PyException) : PyStep()
}

/**
 * One step of this coroutine: `self.send(value)` (`None` when [value] is null), inside
 * `context.run(...)` when [context] is a `contextvars.Context`, else in the calling thread's
 * current context (SPEC U-5e; issue #164, item 13; pythonx-concurrent S7.9, S7.10, S7.13).
 *
 * ### One attachment, nothing parked under it
 *
 * The whole step is one [withGIL] scope on the calling thread, whichever thread that is: a thread
 * CPython never saw attaches for the call and is detached on return, the nesting depth is left as
 * it was. The code inside the scope is a handful of C API calls and contains no suspension point
 * and no caller-supplied Kotlin code, so the GIL (attachment, on 3.15t) is never held across a
 * Kotlin suspension, and on a JVM virtual thread the step is one pinned call (#161). Python code
 * that calls back into Kotlin from inside the coroutine runs under this frame as any upcall does.
 *
 * ### Threads
 *
 * Two steps of one coroutine must not overlap; the driver sequences them. Each may run on a
 * different thread (`threading.get_ident()` inside the coroutine changes with it) while
 * `contextvars` stay with the [context] the driver passes. A step that does overlap another is
 * answered by CPython with `ValueError: coroutine already executing`, reported as [PyStep.Raised].
 *
 * ### References
 *
 * The receiver and [value] are the caller's and are not retained beyond the call. The bound
 * `send`, `Context.run`, the argument tuple and a `StopIteration` instance are released inside the
 * scope; only the result is handed out, as a new reference the caller owns.
 *
 * @throws IllegalArgumentException before anything runs, the receiver untouched: the receiver has
 *   no `send`, or [context] is not a `contextvars.Context`.
 */
fun PyObject.step(value: PyObject? = null, context: PyObject? = null): PyStep =
    PyCoroutineStepper.run(this, "send", value, context)

/**
 * One step of this coroutine that throws [exception] (a `BaseException` instance or class) into it
 * at its suspension point: `self.throw(exception)`, inside `context.run(...)` when [context] is
 * given. Everything [step] says about attachment, threads and references holds here.
 *
 * @throws IllegalArgumentException before anything runs: the receiver has no `throw`, or [context]
 *   is not a `contextvars.Context`.
 */
fun PyObject.throwInto(exception: PyObject, context: PyObject? = null): PyStep =
    PyCoroutineStepper.run(this, "throw", exception, context)

/**
 * [throwInto] with a Kotlin [exception], built with SPEC U-2's mapping
 * ([UpcallTrampoline.pythonExceptionObjectFor]): a `CancellationException` arrives as
 * `asyncio.CancelledError` with any Kotlin cause as `__cause__`, a [PyException] as its original
 * Python instance, any other throwable as `RuntimeError(message)` with the Kotlin cause chain on
 * `__cause__`. The Python exception built here is released after the step.
 */
fun PyObject.throwInto(exception: Throwable, context: PyObject? = null): PyStep = withGIL {
    val pyException = UpcallTrampoline.pythonExceptionObjectFor(exception)
    try {
        PyCoroutineStepper.run(this, "throw", pyException, context)
    } finally {
        pyException.close()
    }
}

/**
 * The one routine under [step] and [throwInto]: attach, call, classify, detach. Internal so the
 * surface stays the two extension functions.
 */
internal object PyCoroutineStepper {

    /**
     * `contextvars.Context`, a new reference kept for the interpreter's life (the same lifetime
     * `UpcallTrampoline` gives its cached types). First read under the GIL, from [run].
     */
    private val contextType: NativePointer by lazy {
        val module = PyImport_ImportModule("contextvars") ?: throw pyErrorOrGeneric("contextvars is unreachable")
        try {
            PyObject_GetAttrString(module, "Context") ?: throw pyErrorOrGeneric("contextvars.Context is unreachable")
        } finally {
            Py_DecRef(module)
        }
    }

    /**
     * `builtins.StopIteration`, a new reference kept like [contextType]. First read under the GIL,
     * from [run], and read there **before** the coroutine is called: the first resolution imports
     * `builtins`, and an import with the error indicator set (the `StopIteration` the step just
     * produced) fails and surfaces that very exception as a [PyException]. Run 44894364653 showed
     * exactly that: every first `Returned` outcome in a process reported as `PyException: <value>`.
     */
    private val stopIterationType: NativePointer by lazy {
        val module = PyImport_ImportModule("builtins") ?: throw pyErrorOrGeneric("builtins is unreachable")
        try {
            PyObject_GetAttrString(module, "StopIteration") ?: throw pyErrorOrGeneric("builtins.StopIteration is unreachable")
        } finally {
            Py_DecRef(module)
        }
    }

    fun run(target: PyObject, method: String, argument: PyObject?, context: PyObject?): PyStep = withGIL {
        // Both cached types are resolved here, with the indicator clear, never after the call
        // (see [stopIterationType]).
        val contextTypePointer = contextType
        val stopIterationTypePointer = stopIterationType
        // Refusals first, with nothing of the coroutine's touched. The attribute lookup is the test
        // for "has this method": a coroutine, a generator and an async generator all answer it, and
        // the error a missing one leaves is cleared here, not reported.
        val bound = PyObject_GetAttrString(target.pointer, method) ?: run {
            PyErr_Clear()
            throw IllegalArgumentException(
                "the receiver has no $method(): a step needs a coroutine or a generator, not ${target.Type.name}",
            )
        }
        try {
            if (context != null && PyObject_IsInstance(context.pointer, contextTypePointer) != 1) {
                PyErr_Clear()
                throw IllegalArgumentException(
                    "the context of a step must be a contextvars.Context, not ${context.Type.name}",
                )
            }
            val argumentPointer = argument?.pointer ?: PyNone.get().pointer
            val result: NativePointer? = if (context == null) {
                callWith(bound, arrayOf(argumentPointer))
            } else {
                val runMethod = PyObject_GetAttrString(context.pointer, "run")
                    ?: throw pyErrorOrGeneric("contextvars.Context.run is unreachable")
                try {
                    callWith(runMethod, arrayOf(bound, argumentPointer))
                } finally {
                    Py_DecRef(runMethod)
                }
            }
            // A value: the coroutine suspended on it. New reference, the caller's.
            if (result != null) return@withGIL PyStep.Yielded(PyObject(result, borrowed = false))

            // StopIteration: the coroutine returned. The instance comes off the indicator as a new
            // reference, its `value` is read as another, and the instance itself is released here.
            if (PyErr_ExceptionMatches(stopIterationTypePointer) != 0) {
                val stop = PyErr_GetRaisedException()
                    ?: return@withGIL PyStep.Returned(PyObject(PyNone.get().pointer, borrowed = true))
                try {
                    val returned = PyObject_GetAttrString(stop, "value")
                    if (returned == null) {
                        PyErr_Clear()
                        PyStep.Returned(PyObject(PyNone.get().pointer, borrowed = true))
                    } else {
                        PyStep.Returned(PyObject(returned, borrowed = false))
                    }
                } finally {
                    Py_DecRef(stop)
                }
            } else {
                // Anything else the step raised. `fromCurrentError` adopts the instance and clears
                // the indicator; the fallback covers a NULL return with nothing set, which CPython
                // itself reports as a SystemError.
                PyStep.Raised(
                    PyException.fromCurrentError()
                        ?: PyException("$method() returned NULL without setting an exception"),
                )
            }
        } finally {
            Py_DecRef(bound)
        }
    }

    /**
     * `callable(*arguments)` with a tuple built here. `PyTuple_SetItem` steals, so each argument is
     * incref'd on its way in and the tuple's release gives those back; the caller keeps its own
     * references. Returns the new reference the call produced, or `null` with the indicator set.
     */
    private fun callWith(callable: NativePointer, arguments: Array<NativePointer>): NativePointer? {
        val tuple = PyTuple_New(arguments.size.toLong()) ?: throw pyErrorOrGeneric("Failed to build the argument tuple")
        try {
            for (i in arguments.indices) {
                Py_IncRef(arguments[i])
                if (PyTuple_SetItem(tuple, i.toLong(), arguments[i]) != 0) {
                    throw pyErrorOrGeneric("Failed to populate the argument tuple")
                }
            }
            return PyObject_CallObject(callable, tuple)
        } finally {
            Py_DecRef(tuple)
        }
    }
}
