package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.PyLong_Check
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.getThreadGILState
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.reflection.CallableHandle
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.NativePointer
import python.native.ffi.PyBool_FromLong
import python.native.ffi.PyBytes_FromObject
import python.native.ffi.PyDict_GetItemString
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyErr_SetString
import python.native.ffi.PyEval_GetBuiltins
import python.native.ffi.PyFloat_AsDouble
import python.native.ffi.PyGILState_Ensure
import python.native.ffi.PyGILState_Release
import python.native.ffi.PyFloat_FromDouble
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_GetItem
import python.native.ffi.PyObject_IsTrue
import python.native.ffi.PyObject_Size
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.PyUnicode_FromString
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef
import python.native.ffi.toNativePointer
import python.native.ffi.toRawValue


/**
 * The piece ROADMAP §13 found missing: the thing that turns a Python argument tuple into the
 * `Array<Any?>` an [ExposedCallable] expects, and the Kotlin result back into a `PyObject *`.
 *
 * `UpcallTable` has carried arity and a per-argument [TypeTag] since it was written; until this
 * existed, nothing read them. Both desktop stubs were `(long) -> long`, so the only entries Python
 * could reach were zero-argument ones returning a `Long` -- Python could call Kotlin but could not
 * pass it anything.
 *
 * ### One C shape, not one per signature
 *
 * The entry point is `(callableHandle: long, args: PyObject * ) -> PyObject *`, i.e. the carrier
 * shape `(long, long) -> long`, and **that is the only shape argument passing needs**. Arity and
 * types travel inside the tuple and inside the table entry, never in the C signature, so the stub
 * count does not grow with the number of exposed Kotlin functions. A design that instead
 * specialised the stub per signature would need one for every `(arity, tag-vector)` combination --
 * unbounded, and impossible to pre-generate for a closed world.
 *
 * The shape is deliberately the one CPython's own `PyCFunction` slot uses
 * (`PyObject *(PyObject *self, PyObject *args)`): when the generated proxy type lands, `self`
 * takes the handle's place and no new stub is needed. `docs/design/upcall.md` §1.3 (formerly "왜 트램폴린이
 * 적어도 되는가") is exactly this argument, and the remaining CPython slot shapes
 * (`(long,long,long) -> int` for `initproc`/`setter`/`traverse`, `(long) -> int` for `clear`,
 * `(long) -> void` for `destructor`) are already in `Panama`'s vocabulary.
 *
 * ### Reference conventions
 *
 * Both directions are stated because CPython's are inconsistent and getting one wrong is silent:
 *
 * - **Arguments are borrowed.** `PyTuple_GetItem` lends; a Kotlin `PyObject` built over one is
 *   therefore constructed with `borrowed = true`, so the wrapper takes a reference of its own
 *   instead of adopting one it was never given. `borrowed = false` here gives back one reference
 *   per call that nobody ever took, and the resulting free lands somewhere unrelated -- twice in
 *   this repo's history.
 * - **The result is a new reference.** Python takes ownership of whatever comes back, so a
 *   `PyObject` handed out is incremented first. The constructors used for the primitive tags
 *   (`PyLong_FromLongLong` and friends) already return new references.
 *
 * ### Nothing may be thrown out of here
 *
 * The return path is C. A Kotlin exception crossing a Panama upcall stub terminates the VM, and on
 * Kotlin/Native it terminates the process; there is no unwinding to catch it further out. So every
 * failure -- a stale handle, a wrong argument count, a `ClassCastException` from the generated
 * lambda, anything the exposed function itself raises -- leaves through [NULL] with Python's error
 * indicator set, which is the one channel C understands.
 *
 * ### The GIL, and why the ordinary `withGIL` is not enough here
 *
 * `withGIL` skips `PyGILState_Ensure` when the calling thread's nesting depth is already non-zero,
 * which is right for Kotlin-side code: the depth counter is that thread's own record of scopes it
 * opened. It is **wrong for an entry point from C**, because C may have dropped the GIL under a
 * scope that is still open. `ctypes.CFUNCTYPE` does exactly that -- it releases the GIL around the
 * foreign call, unlike `PYFUNCTYPE` -- so a trampoline reached that way runs with a non-zero depth
 * and no thread state at all, and the first C API call segfaults inside `_PyThreadState_GET`.
 * Measured: `PyErr_Occurred+0x1c`, on a thread whose depth said the GIL was held.
 *
 * So this takes its own `PyGILState_Ensure`/`Release` pair unconditionally -- which is what that
 * pair is *for* -- and sets the depth to 1 for its duration so nested `withGIL` scopes inside stay
 * cheap. The counters are restored on the way out, and the release is LIFO with respect to any
 * outer scope. It also makes this callable from plain Kotlin on a thread holding nothing, which is
 * how `UpcallTrampolineTest` drives it.
 */
object UpcallTrampoline {

    /** What C reads as a null `PyObject *`, and the only failure signal this boundary has. */
    const val NULL: Long = 0L

    /**
     * Calls the entry [callableHandle] resolves to with the arguments in [argsTuple].
     *
     * @param callableHandle a raw [CallableHandle]; a stale or unknown one is an error, not a crash.
     * @param argsTuple a borrowed `PyObject *` to a tuple, or `0` for "no arguments". For an entry
     *   whose [python.multiplatform.reflection.CallableKind] has a receiver, item 0 is that
     *   receiver -- an [ObjectReference] integer -- and the declared parameters follow it.
     * @return a **new** reference, or [NULL] with Python's error indicator set.
     */
    fun invoke(callableHandle: Long, argsTuple: Long): Long = attached {
        try {
            val entry = UpcallTable.callable(CallableHandle(callableHandle))
            val result = entry.callable(unmarshalArguments(entry, argsTuple))
            // A suspending entry's body hands back a PendingCall rather than the value, because
            // this frame has to return before the coroutine can finish. `AsyncUpcall` either
            // unwraps an already-complete one -- the common case, and free -- or builds the
            // `asyncio.Future` that will carry the answer. See docs/design/upcall.md §5.2.
            if (entry.isSuspend) AsyncUpcall.deliver(entry, result)
            else marshalResult(entry.returnType, result)
        } catch (t: Throwable) {
            raiseInPython(t)
            NULL
        }
    }

    /**
     * Attaches this thread for the duration of [block], **without** consulting the nesting depth.
     *
     * See the class doc: the depth counter records scopes Kotlin opened, and C is free to have
     * released the GIL inside one of them. This is the only place in the codebase that must not
     * trust it.
     */
    private inline fun <T> attached(block: () -> T): T {
        val threadState = getThreadGILState()
        val outerState = threadState.state
        val outerDepth = threadState.depth
        val gilState = PyGILState_Ensure()
        threadState.state = gilState
        threadState.depth = 1
        try {
            return block()
        } finally {
            threadState.depth = outerDepth
            threadState.state = outerState
            PyGILState_Release(gilState)
        }
    }

    /**
     * Drops the root a [TypeTag.OBJECT] result handed to Python, and reports whether it did
     * anything.
     *
     * The counterpart to [marshalResult]'s [HandleTable.register]: a Kotlin object returned to
     * Python is rooted until this is called, which is what a proxy's `tp_dealloc` does. Its shape
     * is `(long) -> int`, already in `Panama`'s vocabulary, so exposing it adds no stub.
     *
     * @return 1 if a live entry was released, 0 for a handle already released or never issued.
     *   A double release must stay a no-op: the slot may belong to someone else by then.
     */
    fun releaseObject(objectHandle: Long): Int =
        if (HandleTable.release(ObjectReference(objectHandle))) 1 else 0

    /**
     * Tells the [PendingCall] behind [callHandle] that the Python side has stopped waiting for it.
     *
     * The Python end of `docs/design/upcall.md` §5.6's missing half. Without this, the only
     * moment Kotlin could learn that a `Future` had been cancelled was when the coroutine finished
     * and [AsyncUpcall] found the `Future` already settled -- which is the moment the news becomes
     * useless. `PythonProxySource`'s `_pm_watch` hangs a `Future.add_done_callback` on the `Future`
     * the slow path hands out and routes it here, so a body checking
     * [python.multiplatform.ffi.upcall.ensureActive] stops at its next checkpoint instead of running
     * to the end for a result nobody will read.
     *
     * Shape `(long) -> int`, deliberately the same one [releaseObject] already has, so no target
     * grows a new stub *shape* for this -- only one more binding of a shape it already builds.
     *
     * @param callHandle a raw [ObjectReference] issued by [HandleTable] for a [PendingCall]. A
     *   stale one -- the ordinary case for a callback that fires after the call was reclaimed --
     *   resolves to nothing and is a no-op, which is what the table's generation tag is for.
     * @return 1 if this call cancelled a live, not-yet-cancelled [PendingCall]; 0 otherwise,
     *   including for a handle that names something that is not a [PendingCall] at all.
     */
    fun cancelCall(callHandle: Long): Int = attached {
        try {
            val call = HandleTable.resolveRaw(callHandle) as? PendingCall ?: return@attached 0
            if (call.cancel()) 1 else 0
        } catch (t: Throwable) {
            // Reached from C on a path whose only job is to deliver news. Nothing above this can
            // act on a failure, and unwinding into C would take the process with it.
            0
        }
    }

    // ---------------------------------------------------------------------------------------
    // Arguments: Python tuple -> Array<Any?>
    // ---------------------------------------------------------------------------------------

    private fun unmarshalArguments(entry: ExposedCallable, argsTuple: Long): Array<Any?> {
        val expected = entry.expectedArgCount
        val tuple = if (argsTuple == NULL) null else argsTuple.toNativePointer()

        val given = if (tuple == null) 0L else PyTuple_Size(tuple)
        if (given < 0) failFromPython("${entry.name} expects its arguments as a tuple")
        if (given != expected.toLong()) {
            throw IllegalArgumentException(
                "${entry.name} takes $expected argument${if (expected == 1) "" else "s"}, $given given"
            )
        }

        val args = arrayOfNulls<Any?>(expected)
        if (expected == 0) return args

        // Item 0 is the receiver when the kind has one; the declared parameters follow, so a
        // paramTypes index and an args index differ by exactly that slot.
        val offset = if (entry.kind.hasReceiver) 1 else 0
        if (offset == 1) args[0] = toKotlinObject(item(tuple!!, 0))
        for (i in entry.paramTypes.indices) {
            args[offset + i] = toKotlin(entry.paramTypes[i], item(tuple!!, (offset + i).toLong()))
        }
        return args
    }

    /** `PyTuple_GetItem` **borrows**; nothing here releases what it returns. */
    private fun item(tuple: NativePointer, index: Long): NativePointer =
        PyTuple_GetItem(tuple, index) ?: failFromPython("argument $index is missing")

    private fun toKotlin(tag: TypeTag, value: NativePointer): Any? {
        // `None` is how Python spells a null argument, whatever the tag says it should have been.
        if (value.toRawValue() == noneAddress) return null
        return when (tag) {
            TypeTag.INT -> PyLong_AsLongLong(value).also { checkPythonError("expected an int") }
            TypeTag.FLOAT -> PyFloat_AsDouble(value).also { checkPythonError("expected a float") }
            TypeTag.BOOLEAN -> (PyObject_IsTrue(value) != 0).also { checkPythonError("expected a bool") }
            TypeTag.STRING -> PyUnicode_AsUTF8(value) ?: failFromPython("expected a str")
            TypeTag.BYTES -> toByteArray(value)
            TypeTag.UNIT -> null
            TypeTag.OBJECT -> toKotlinObject(value)
        }
    }

    /**
     * The one tag that is two things at once, resolved by what Python actually sent.
     *
     * A Kotlin object crosses as an [ObjectReference] integer -- Python cannot hold a Kotlin
     * reference on any target, which is the whole reason [HandleTable] exists. Anything else is a
     * Python object, and reaches a Kotlin parameter declared as [PyObject].
     */
    private fun toKotlinObject(value: NativePointer): Any {
        if (PyLong_Check(value) != 0) {
            val raw = PyLong_AsLongLong(value)
            checkPythonError("expected an object handle")
            return HandleTable.resolveRaw(raw)
                ?: throw IllegalArgumentException("stale or unknown Kotlin object handle: $raw")
        }
        // Borrowed, so the wrapper takes its own reference rather than adopting the tuple's.
        return PyObject(value, borrowed = true)
    }

    /**
     * Reads `bytes` an item at a time, which is the price of this ABI subset.
     *
     * `PyBytes_AsString` is the fast route and cannot be used: it is bound here as a
     * NUL-terminated UTF-8 *string* read, so it destroys exactly the payloads `ByteArray` exists
     * for. `PyBytes_AsStringAndSize` is not in `EmbedAPI` on any platform. Until it is, this is
     * O(n) C API crossings plus an index object per byte -- correct, and the slowest path across
     * this boundary by a wide margin.
     */
    private fun toByteArray(value: NativePointer): ByteArray {
        val size = PyObject_Size(value)
        if (size < 0) failFromPython("expected bytes")
        val out = ByteArray(size.toInt())
        for (i in out.indices) {
            val index = PyLong_FromLongLong(i.toLong()) ?: failFromPython("byte index $i")
            val element = PyObject_GetItem(value, index)
            Py_DecRef(index)
            if (element == null) failFromPython("byte $i")
            out[i] = PyLong_AsLongLong(element).toByte()
            Py_DecRef(element)
            checkPythonError("byte $i")
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Result: Kotlin value -> new PyObject reference
    // ---------------------------------------------------------------------------------------

    /** Internal so [AsyncUpcall] can marshal a completion with the same rules a return uses --
     * a value delivered late must reach Python as the same type it would have reached it as
     * synchronously, and the tag is the only thing that decides that. */
    internal fun marshalResult(tag: TypeTag, value: Any?): Long {
        if (tag == TypeTag.UNIT || value == null || value == Unit) return newNone()
        return when (tag) {
            TypeTag.INT -> newReference(PyLong_FromLongLong(value as Long), "int")
            TypeTag.FLOAT -> newReference(PyFloat_FromDouble(value as Double), "float")
            TypeTag.BOOLEAN -> newReference(PyBool_FromLong(if (value as Boolean) 1 else 0), "bool")
            TypeTag.STRING -> newReference(PyUnicode_FromString(value as String), "str")
            TypeTag.BYTES -> newReference(toPythonBytes(value as ByteArray), "bytes")
            TypeTag.UNIT -> newNone()
            TypeTag.OBJECT -> fromKotlinObject(value)
        }
    }

    private fun fromKotlinObject(value: Any): Long {
        if (value is PyObject) {
            // The wrapper keeps the reference it holds, so Python needs one of its own.
            Py_IncRef(value.pointer)
            return value.pointer.toRawValue()
        }
        // Rooted until Python gives it back through releaseObject(); see HandleTable's class doc
        // on why this table leaks by construction if nothing does.
        return newReference(PyLong_FromLongLong(HandleTable.register(value).raw), "object handle")
    }

    /** The mirror of [toByteArray], and the same caveat: a tuple of ints, one object per byte. */
    private fun toPythonBytes(bytes: ByteArray): NativePointer? {
        val tuple = PyTuple_New(bytes.size.toLong()) ?: return null
        for (i in bytes.indices) {
            val element = PyLong_FromLongLong(bytes[i].toLong() and 0xFF)
            if (element == null) {
                Py_DecRef(tuple)
                return null
            }
            PyTuple_SetItem(tuple, i.toLong(), element) // steals; nothing to release here
        }
        val result = PyBytes_FromObject(tuple)
        Py_DecRef(tuple)
        return result
    }

    private fun newReference(pointer: NativePointer?, what: String): Long =
        pointer?.toRawValue() ?: failFromPython("could not build the $what result")

    /** `None` is returned like any other value, so it owes Python a reference too. */
    private fun newNone(): Long {
        val none = noneObject
        Py_IncRef(none)
        return none.toRawValue()
    }

    // ---------------------------------------------------------------------------------------
    // Errors
    // ---------------------------------------------------------------------------------------

    /**
     * Turns [t] into a set error indicator. Never rethrows: this runs on the path back to C.
     *
     * An error Python has already raised (the exposed function called back into Python and it
     * failed) is left exactly as it is -- overwriting it would replace the real traceback with a
     * Kotlin wrapper's message.
     */
    private fun raiseInPython(t: Throwable) {
        try {
            if (PyErr_Occurred() != null) return
            val message = t.message ?: t::class.simpleName ?: "upcall failed"
            PyErr_SetString(runtimeErrorType, message)
        } catch (_: Throwable) {
            // Reporting the failure failed. Returning NULL with no indicator set is still a
            // Python-level error ("SystemError: returned NULL without setting an exception"),
            // which is strictly better than unwinding into C.
        }
    }

    /** Raises whatever Python already put on the indicator as a Kotlin exception, or a fallback. */
    private fun failFromPython(what: String): Nothing {
        val pending = python.multiplatform.ffi.exceptions.PyException.fromCurrentError()
        throw pending ?: IllegalArgumentException(what)
    }

    /**
     * The `-1`-and-check convention: `PyLong_AsLongLong` and friends signal failure with a value
     * that is also a legitimate result, so the indicator is the only reliable answer.
     */
    private fun checkPythonError(what: String) {
        if (PyErr_Occurred() == null) return
        val pending = python.multiplatform.ffi.exceptions.PyException.fromCurrentError()
        PyErr_Clear()
        throw pending ?: IllegalArgumentException(what)
    }

    // ---------------------------------------------------------------------------------------
    // Cached singletons. Data symbols, so they are reached the way PyNone/PyTypeChecks reach
    // theirs -- through builtins -- rather than by adding per-platform raw-symbol plumbing.
    // ---------------------------------------------------------------------------------------

    private val noneObject: NativePointer by lazy { PyNone.get().pointer }

    private val noneAddress: Long by lazy { noneObject.toRawValue() }

    private val runtimeErrorType: NativePointer by lazy {
        val builtins = PyEval_GetBuiltins() ?: error("builtins is unreachable")
        PyDict_GetItemString(builtins, "RuntimeError") ?: error("builtins.RuntimeError is unreachable")
    }

    /**
     * The same `RuntimeError` class [raiseInPython] uses, as a callable wrapper.
     *
     * [AsyncUpcall] needs to *construct* an instance rather than set the indicator, because
     * `Future.set_exception` takes an exception object. Sharing the lookup keeps a Kotlin failure
     * mapping to one Python type whether it is raised at the call or delivered to an `await`.
     *
     * The cached pointer is borrowed from the builtins dict, so the wrapper takes a reference of
     * its own.
     */
    internal fun runtimeErrorClass(): PyObject = PyObject(runtimeErrorType, borrowed = true)
}
