package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ref.PyAutoCloseable
import python.native.ffi.NativePointer
import python.native.ffi.PyCallable_Check
import python.native.ffi.PyDict_New
import python.native.ffi.PyDict_SetItemString
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyErr_Occurred
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_Call
import python.native.ffi.PyObject_CallNoArgs
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_DelAttrString
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_IsTrue
import python.native.ffi.PyObject_Repr
import python.native.ffi.PyObject_RichCompare
import python.native.ffi.PyObject_SetAttrString
import python.native.ffi.PyObject_Str
import python.native.ffi.PyObject_Type
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef
import python.native.ffi.memScoped


/**
 * Rich-comparison operators understood by `PyObject_RichCompare`
 * (`Py_LT` .. `Py_GE`, in CPython's fixed `0..5` ordering).
 */
enum class PyCompareOp(val opId: Int) {
    LT(0), LE(1), EQ(2), NE(3), GT(4), GE(5)
}

/**
 * Builds a [PyException] from CPython's current error indicator (via
 * [PyException.fromCurrentError]), falling back to a generic message if the
 * indicator happens not to be set (e.g. a `null`/failure return whose cause
 * was not, in fact, a live Python exception). Centralises the
 * "null means an exception is set" contract used throughout this file and
 * [PyType]/[python.multiplatform.ffi.Python3] (same package, so no import
 * needed at those call sites).
 */
internal fun pyErrorOrGeneric(fallback: String): PyException =
    PyException.fromCurrentError() ?: PyException(fallback)


/**
 * Releases one reference that no Kotlin object owns.
 *
 * Unlike [PyObject.decRef] this takes a bare pointer, because the whole point is the window in
 * which there is no wrapper yet. Guarded by the initialisation check for the same reason every
 * other refcount path is: after `Py_Finalize()` the pointer no longer addresses anything.
 */
internal fun releaseUnownedReference(pointer: NativePointer) {
    if (python.multiplatform.ffi.Python3.isInitialized) withGIL { Py_DecRef(pointer) }
}

/**
 * Runs [block] on a pointer the caller has just been handed as a **new reference**, releasing
 * that reference if -- and only if -- [block] throws.
 *
 * This closes the one leak in this architecture that no collector can reach. Between a C API
 * call returning a new reference and a wrapper adopting it, the reference belongs to nobody:
 * there is no Kotlin object for a cleaner to be attached to, so an exception thrown in that
 * window loses the reference for the life of the interpreter. See ROADMAP §4.
 *
 * ### The one thing this must not be wrapped around
 *
 * [block] may adopt the pointer only as its **last** act. [python.multiplatform.ref.PyAutoCloseable]
 * registers the cleaner in its own constructor, before any subclass initialiser runs, so a
 * wrapper that throws from `init` has *already* queued a release for that pointer -- releasing
 * it here too would decrement it twice, which corrupts CPython's free lists and surfaces
 * somewhere unrelated (ROADMAP §1 is the account of exactly that). [PyType.getInstance] is the
 * constructor in this codebase that can throw, and it validates before it constructs for this
 * reason.
 */
internal inline fun <R> NativePointer.adoptingNewReference(block: (NativePointer) -> R): R {
    try {
        return block(this)
    } catch (t: Throwable) {
        releaseUnownedReference(this)
        throw t
    }
}


/**
 * Releases one reference, from wherever the cleaner happens to run.
 *
 * A top-level function on purpose: registered as the cleanup action it must close over the
 * pointer value and nothing else. Anything that reached back to the wrapper would keep that
 * wrapper strongly reachable through the Cleanable, and the cleaner would then never run --
 * which is precisely the bug this replaced.
 *
 * The check is made twice. Once before touching anything, so a cleaner firing after shutdown
 * returns without entering the C API; and once more inside the GIL scope, because between the
 * two the interpreter may have started finalizing. [Python3.finalize] lowers the flag before
 * calling Py_Finalize() so this ordering actually holds.
 */
/** Diagnostic: how many times a cleanup action has actually run. Test-visible. */
internal object ReleaseCounter {
    var ran: Int = 0
    var released: Int = 0
}

private fun topLevelDecRefAction(ptr: NativePointer) {
    ReleaseCounter.ran++
    if (!python.multiplatform.ffi.Python3.isInitialized) return
    // No eval-loop checkpoint from here. This runs on the cleaner for every reference the
    // collector hands back, and on a free-threaded build the queue that needs draining belongs to
    // the thread that *owns* the object, never to this one -- so a checkpoint taken here would run
    // Python on a cleaner thread (what ROADMAP §1 turned into a deadlock, and what §9 rules out as
    // a fix) and would drain an empty queue for its trouble.
    val tState = getThreadGILState()
    val wasSuppressed = tState.checkpointsSuppressed
    tState.checkpointsSuppressed = true
    try {
        python.multiplatform.ffi.withGIL {
            if (!python.multiplatform.ffi.Python3.isInitialized) return@withGIL
            python.native.ffi.Py_DecRef(ptr)
            ReleaseCounter.released++
        }
    } finally {
        tState.checkpointsSuppressed = wasSuppressed
    }
}

/**
 * **Invariant: [pointer] is never the null pointer**, and that is enforced by the type system
 * rather than by a check here.
 *
 * This used to carry a TODO asking for one more null check across `PyObject`, `PyType` and
 * `PyException`. It was audited instead of guessed at: every `EmbedAPI` function that can return
 * `NULL` is declared `NativePointer?`, and all **721** pointer-returning `actual`s -- 179 desktop,
 * 182 android, 181 native, 179 wasmJs -- route their result through a conversion that maps address
 * 0 to Kotlin `null` (`toNativePointerFromRaw`, or Kotlin/Native's `toCPointer()?.let`). Exactly
 * one `expect` returns a non-nullable `NativePointer`, `AddressValue.toNativePointer()`, which
 * converts an already-valid pointer rather than obtaining one. `NativePointer`'s own constructor is
 * `internal`, so no caller outside the FFI layer can fabricate a zero one.
 *
 * So a null from C cannot reach this constructor without an explicit `!!`, and the `?: throw`
 * at each call site is the check -- adding a runtime one here would only re-test what the
 * compiler already proved.
 *
 * One inconsistency the audit did turn up, recorded because nothing tests it: androidMain's
 * `Long.toNativePointer()` uses `if (this > 0)` where every other platform uses `!= 0`. It agrees
 * for every address Android actually hands out (arm64 user-space VAs are well below 2^63), so it
 * is a discrepancy rather than a defect, and it is unreachable from the production path in any
 * case -- `bindings` returns raw longs that go through `toNativePointerFromRaw`.
 */
open class PyObject(val pointer: NativePointer, borrowed: Boolean): PyAutoCloseable(
    pointer,
    ::topLevelDecRefAction
) {

    init {
        // Yes, Py_IncRef is the right call here, and the question this used to carry has since
        // been answered the expensive way. `borrowed = true` states that the caller was *lent*
        // this pointer and that the wrapper must obtain its own reference, because
        // PyAutoCloseable has unconditionally registered a release for it. `borrowed = false`
        // states the caller is handing over a reference it owns, so no increment is due.
        //
        // Both halves of that have been paid for. Wrapping a lent pointer with `borrowed = false`
        // gave two owners for one reference; the resulting double free corrupted CPython's free
        // lists and surfaced as a segfault in an unrelated test, which is what made §1 look
        // unfixable for three attempts. In the other direction, declining the increment on a
        // reference the caller only borrowed loses one per call -- see ROADMAP §4 and
        // `OwnershipLeakTest`, where both directions are measured rather than argued.
        if (borrowed) incRef()
    }

    /**
     * The Python type of this object, as the design sketch names it.
     *
     * On JVM targets this compiles to a `getType()` accessor, which is why there is no separate
     * `getType()` method -- declaring both clashes on the JVM signature and breaks every
     * subclass on Android and Desktop.
     */
    val Type: PyType by lazy {
        // PyObject_Type returns a new reference; PyType.getInstance's private
        // constructor stores it via PyObject(pointer, borrowed = false), i.e.
        // it takes ownership of exactly that reference (no extra incRef).
        val typePointer: NativePointer = python.multiplatform.ffi.Python3.withPython { PyObject_Type(pointer) }
            ?: throw pyErrorOrGeneric("Failed to get the type of this object")
        // getInstance either adopts this reference (cache miss) or releases it (cache hit); it
        // throws for an object that is not a type, which is what adoptingNewReference covers.
        typePointer.adoptingNewReference { PyType.getInstance(it) }
    }

    /**
     * Takes an extra reference to this object.
     *
     * Refcounting is a C API call like any other and needs a thread state attached, so it goes
     * through [withGIL]. That is true of free-threaded builds too: removing the global lock
     * removes contention, not the requirement that the calling thread be attached.
     *
     * The initialisation check exists for [clean], which runs on a cleaner thread that may have
     * never touched Python and may run after `Py_Finalize()`; attaching to a finalized
     * interpreter is invalid.
     */
    protected fun incRef() {
        if (python.multiplatform.ffi.Python3.isInitialized) withGIL { Py_IncRef(pointer) }
    }

    /** Releases one reference. See [incRef] for why the GIL and the initialisation check are here. */
    protected fun decRef() {
        if (python.multiplatform.ffi.Python3.isInitialized) withGIL { Py_DecRef(pointer) }
    }

    @Throws(PyException::class)
    fun getAttr(name: String): PyObject {
        // PyObject_GetAttrString: new reference on success, null + AttributeError
        // (or similar) set on the error indicator on failure.
        val attr = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, name) }
            ?: throw pyErrorOrGeneric("Attribute '$name' not found")
        return PyObject(attr, false)
    }

    fun getAttrOrNull(name: String): PyObject? {
        val attr = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, name) }
        if (attr == null) {
            // A missing attribute sets the Python error indicator (typically
            // AttributeError). This is the "OrNull" variant -- the caller has
            // opted out of exception handling -- so the indicator must be
            // cleared here rather than left set: the C API contract is that
            // you must not call back into it with a pending exception, and
            // leaving one set would silently corrupt whatever Python call
            // runs next (observed as spurious failures in unrelated,
            // logically unconnected calls further down the line).
            python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
            return null
        }
        return PyObject(attr, false)
    }

    @Throws(PyException::class)
    fun setAttr(name: String, value: PyObject) {
        if (python.multiplatform.ffi.Python3.withPython { PyObject_SetAttrString(pointer, name, value.pointer) } != 0) {
            throw pyErrorOrGeneric("Failed to set attribute '$name'")
        }
    }

    fun setAttrOrNull(name: String, value: PyObject?) {
        val target = value?.pointer ?: return
        // See getAttrOrNull() for why a failure here must clear the error
        // indicator rather than leave it set for whatever runs next.
        if (python.multiplatform.ffi.Python3.withPython { PyObject_SetAttrString(pointer, name, target) } != 0) {
            python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
        }
    }

    @Throws(PyException::class)
    fun delAttr(name: String) {
        if (python.multiplatform.ffi.Python3.withPython { PyObject_DelAttrString(pointer, name) } != 0) {
            throw pyErrorOrGeneric("Failed to delete attribute '$name'")
        }
    }

    fun delAttrOrNull(name: String) {
        // See getAttrOrNull() for why a failure here must clear the error
        // indicator rather than leave it set for whatever runs next.
        if (python.multiplatform.ffi.Python3.withPython { PyObject_DelAttrString(pointer, name) } != 0) {
            python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
        }
    }

    /**
     * Calls this object as a Python callable.
     *
     * The arity-specific overloads are not boilerplate: a `vararg` parameter allocates an
     * `Array<out PyObject>` at every call site, and one-to-three argument calls dominate FFI
     * use. These build the argument tuple directly and allocate nothing on the Kotlin side.
     * The `vararg` form remains for wider calls and for spreading an existing array.
     *
     * Keyword arguments live on [call] rather than here so the common path never pays for a
     * `Map` parameter it does not use.
     */
    @Throws(PyException::class)
    open operator fun invoke(): PyObject = python.multiplatform.ffi.Python3.withPython {
        // No tuple at all -- PyObject_CallNoArgs is CPython's dedicated zero-argument path.
        PyObject(PyObject_CallNoArgs(pointer) ?: throw pyErrorOrGeneric("Call failed"), false)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject): PyObject = callWithTuple(1) { t ->
        setArg(t, 0, arg0)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject, arg1: PyObject): PyObject = callWithTuple(2) { t ->
        setArg(t, 0, arg0); setArg(t, 1, arg1)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject, arg1: PyObject, arg2: PyObject): PyObject = callWithTuple(3) { t ->
        setArg(t, 0, arg0); setArg(t, 1, arg1); setArg(t, 2, arg2)
    }

    @Throws(PyException::class)
    open operator fun invoke(arg0: PyObject, arg1: PyObject, arg2: PyObject, arg3: PyObject): PyObject = callWithTuple(4) { t ->
        setArg(t, 0, arg0); setArg(t, 1, arg1); setArg(t, 2, arg2); setArg(t, 3, arg3)
    }

    @Throws(PyException::class)
    open operator fun invoke(vararg args: PyObject): PyObject {
        if (args.isEmpty()) return invoke()
        return callWithTuple(args.size) { t ->
            for (i in args.indices) setArg(t, i, args[i])
        }
    }

    /**
     * `self(*args, **kwargs)`. Separate from [invoke] because keyword arguments require
     * building a dict as well as a tuple, and callers that do not need them should not pay
     * for the parameter.
     */
    @Throws(PyException::class)
    open fun call(args: Array<out PyObject> = emptyArray(), kwargs: Map<String, PyObject>): PyObject =
        python.multiplatform.ffi.Python3.withPython {
            if (kwargs.isEmpty()) return@withPython invoke(*args)

            val argTuple = PyTuple_New(args.size.toLong()) ?: throw pyErrorOrGeneric("Failed to build argument tuple")
            try {
                for (i in args.indices) {
                    // PyTuple_SetItem steals the reference it is given, and args[i].pointer stays
                    // owned by args[i], so hand the tuple a fresh +1 instead of that reference.
                    Py_IncRef(args[i].pointer)
                    if (PyTuple_SetItem(argTuple, i.toLong(), args[i].pointer) != 0) {
                        throw pyErrorOrGeneric("Failed to populate argument tuple")
                    }
                }
                val kwargsDict = PyDict_New() ?: throw pyErrorOrGeneric("Failed to build keyword argument dict")
                try {
                    for ((key, value) in kwargs) {
                        // PyDict_SetItemString does not steal; CPython increfs internally.
                        if (PyDict_SetItemString(kwargsDict, key, value.pointer) != 0) {
                            throw pyErrorOrGeneric("Failed to populate keyword argument dict")
                        }
                    }
                    PyObject(PyObject_Call(pointer, argTuple, kwargsDict) ?: throw pyErrorOrGeneric("Call failed"), false)
                } finally {
                    Py_DecRef(kwargsDict)
                }
            } finally {
                Py_DecRef(argTuple)
            }
        }

    /**
     * Builds an argument tuple of [size], lets [fill] populate it, and calls this object with it.
     * The whole sequence runs under a single GIL acquisition rather than one per C API call.
     */
    private inline fun callWithTuple(size: Int, fill: (NativePointer) -> Unit): PyObject =
        python.multiplatform.ffi.Python3.withPython {
            val argTuple = PyTuple_New(size.toLong()) ?: throw pyErrorOrGeneric("Failed to build argument tuple")
            try {
                fill(argTuple)
                PyObject(PyObject_CallObject(pointer, argTuple) ?: throw pyErrorOrGeneric("Call failed"), false)
            } finally {
                Py_DecRef(argTuple)
            }
        }

    /** Stores [arg] at [index]. Caller must already hold the GIL. */
    private fun setArg(tuple: NativePointer, index: Int, arg: PyObject) {
        // PyTuple_SetItem steals the reference; arg keeps its own, so give the tuple a fresh +1.
        Py_IncRef(arg.pointer)
        if (PyTuple_SetItem(tuple, index.toLong(), arg.pointer) != 0) {
            throw pyErrorOrGeneric("Failed to populate argument tuple")
        }
    }

    /** `callable(self)`, i.e. whether [invoke] has any chance of succeeding. */
    open val isCallable: Boolean
        get() = python.multiplatform.ffi.Python3.withPython { PyCallable_Check(pointer) } != 0

    /** `bool(self)`. Kotlin has no truthiness protocol, so this stays an explicit query. */
    open val isTruthy: Boolean
        get() {
            val result = python.multiplatform.ffi.Python3.withPython { PyObject_IsTrue(pointer) }
            if (result < 0) throw pyErrorOrGeneric("Failed to evaluate truthiness")
            return result != 0
        }

    /** `repr(self)`. */
    open fun repr(): String {
        // PyObject_Repr: new reference on success, null + exception set on failure.
        val reprPointer = python.multiplatform.ffi.Python3.withPython { PyObject_Repr(pointer) } ?: throw pyErrorOrGeneric("Failed to compute repr()")
        // The decode below is the only thing between the new reference and its release, so the
        // release goes in a finally: a throw from there would otherwise strand the repr string.
        val result = try {
            python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(reprPointer) }
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(reprPointer) }
        }
        return result ?: throw pyErrorOrGeneric("Failed to decode repr() result")
    }

    /** `python.multiplatform.ffi.Python3.withPython { PyObject_RichCompare(self, other, op) }`, i.e. the Python-level `<`, `<=`, `==`, `!=`, `>`, `>=` operators. */
    open fun richCompare(other: PyObject, op: PyCompareOp): Boolean {
        // PyObject_RichCompare: new reference to the (usually bool) result on
        // success, null + exception set on failure.
        val resultPointer = python.multiplatform.ffi.Python3.withPython { PyObject_RichCompare(pointer, other.pointer, op.opId) }
            ?: throw pyErrorOrGeneric("Comparison failed")
        // PyObject_IsTrue runs __bool__/__len__, i.e. arbitrary Python -- so the release of the
        // comparison result belongs in a finally rather than after it.
        val truthy = try {
            python.multiplatform.ffi.Python3.withPython { PyObject_IsTrue(resultPointer) }
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(resultPointer) }
        }
        if (truthy < 0) throw pyErrorOrGeneric("Failed to evaluate comparison result")
        return truthy != 0
    }

    /**
     * Bridges Python's rich comparison to Kotlin's `<`, `<=`, `>` and `>=`.
     *
     * Python has no single three-way comparison, so this asks `<` and then `>`; a type that
     * implements neither raises rather than silently reporting equality. Kotlin's `==` is left
     * to [equals], which compares identity of the underlying pointer -- deliberately not the
     * same question as Python's `==`, which [richCompare] answers.
     */
    open operator fun compareTo(other: PyObject): Int = when {
        richCompare(other, PyCompareOp.LT) -> -1
        richCompare(other, PyCompareOp.GT) -> 1
        else -> 0
    }

    override fun toString(): String {
        // PyObject_Str returns a new reference; release it once we've copied
        // the UTF-8 contents out into a Kotlin String. toString() is not
        // declared to throw, so on failure this clears whatever error
        // PyObject_Str set (via fromCurrentError()) and falls back to a
        // placeholder rather than propagating it.
        val strPointer = python.multiplatform.ffi.Python3.withPython { PyObject_Str(pointer) } ?: run {
            val message = PyException.fromCurrentError()?.errMsg
            return "<error converting to str${message?.let { ": $it" } ?: ""}>"
        }
        val result = try {
            python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(strPointer) }
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(strPointer) }
        }
        return result ?: "<error decoding str>"
    }

    /**
     * Hashes on the pointer, matching [equals], which compares pointers.
     *
     * **TODO(open design question)**, sharpened from "the same pointer should only ever produce
     * one wrapper". That would be *wrapper interning*: a global pointer -> `PyObject` map so
     * `a === b` whenever `a == b`. It is not obviously right, and what decides it is not taste:
     *
     * - **A pointer-keyed cache aliases across a free.** CPython reuses addresses aggressively
     *   (free lists, obmalloc pools), so an entry left behind after the last reference goes away
     *   can be handed out for a *different* object that happens to land on the same address. This
     *   is the hazard `reflection/HandleTable` already answers with a generation counter (§7), and
     *   it is why interning cannot be added without a removal path keyed to release.
     * - **[PyType.getInstance] is the existing precedent and it dodges the problem by never
     *   evicting**, which makes every type ever wrapped immortal for the life of the interpreter.
     *   That is affordable for types and not obviously affordable for every object.
     *
     * What to measure before deciding, rather than "is this right?":
     * 1. the cost of the lookup on the hot path -- a map probe per wrapper construction against
     *    the ~2.65 ns an `invokeExact` FFI call costs on desktop (§8), since wrappers are built
     *    per element in bulk conversion;
     * 2. how many wrappers a realistic workload builds for pointers it already holds -- if the
     *    hit rate is low, interning is pure overhead;
     * 3. whether any caller actually needs `===`. Nothing in this repo does today: `equals`/
     *    `hashCode` already make wrappers interchangeable as map keys and in `contains`, which is
     *    what the collection wrappers rely on.
     *
     * Until (3) produces a caller, the answer is "no", and the cost of being wrong is a leak plus
     * an aliasing bug rather than a missing feature.
     */
    override fun hashCode(): Int {
        return pointer.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other is PyObject) {
            return pointer == other.pointer
        }
        return false
    }

}