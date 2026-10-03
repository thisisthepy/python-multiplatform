@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package python.native.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import python.multiplatform.ffi.allocPermanentCString
import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.reflection.CallableHandle
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindings.PyCMethod_New
import python.native.ffi.bindings.PyMethodDef
import python.native.ffi.bindings.PyObject as CPyObject
import kotlin.experimental.ExperimentalNativeApi

/** `METH_VARARGS` from `methodobject.h`. Flag values are ABI, not header-version-dependent. */
private const val METH_VARARGS = 0x0001

/** `METH_O`: exactly one argument, handed over directly instead of inside a tuple. */
private const val METH_O = 0x0008

// -------------------------------------------------------------------------------------------
// The raw C entry points, published as global symbols
// -------------------------------------------------------------------------------------------

/**
 * `PyObject *pm_upcall_invoke(long callableHandle, PyObject *args)` -- the one C shape argument
 * passing needs, and the same one `PyCFunction` has once `self` takes the handle's place.
 *
 * This is the symbol `docs/design/upcall.md` expects a host to reach with `dlopen(NULL)` /
 * `ctypes.CDLL(None)`. Whether it is *findable* that way depends entirely on which binary the
 * Kotlin/Native code ends up in, and that turned out to vary; see [UpcallEntry] for the three
 * measurements. [UpcallEntry.invokeAddress] is the route that does not depend on the link at all.
 *
 * @param args a **borrowed** `PyObject *` tuple, or `NULL` for no arguments.
 * @return a **new** reference, or `NULL` with Python's error indicator set.
 */
@CName("pm_upcall_invoke")
fun pmUpcallInvoke(callableHandle: Long, args: COpaquePointer?): COpaquePointer? =
    // UpcallTrampoline.invoke already takes its own PyGILState_Ensure and catches everything;
    // the extra guard is for the two conversions around it, since a Throwable leaving here
    // terminates the process rather than unwinding into C.
    try {
        UpcallTrampoline.invoke(callableHandle, args.toLong()).toCPointer()
    } catch (t: Throwable) {
        null
    }

/**
 * `long pm_upcall_resolve(const char *name)` -- a name resolved once, an integer from then on.
 *
 * Returns `CallableHandle.NONE.raw` (-1) for a name nothing claims, exactly as
 * [UpcallTable.resolve] does; raising `AttributeError` is Python's job. Touches no C API, so
 * unlike [pmUpcallInvoke] it needs no GIL -- the desktop counterpart
 * (`UpcallTarget.upcallResolveHandle`) takes none either.
 */
@CName("pm_upcall_resolve")
fun pmUpcallResolve(name: CPointer<ByteVar>?): Long =
    try {
        val text = name?.toKString() ?: return CallableHandle.NONE.raw
        UpcallTable.resolve(text).raw
    } catch (t: Throwable) {
        CallableHandle.NONE.raw
    }

/**
 * `int pm_upcall_release_object(long objectHandle)` -- what a proxy's `tp_dealloc` calls.
 *
 * 1 if it released a live entry, 0 for a handle already released or never issued. A double
 * release must stay a no-op: the slot may belong to someone else by then.
 */
@CName("pm_upcall_release_object")
fun pmUpcallReleaseObject(objectHandle: Long): Int =
    try {
        UpcallTrampoline.releaseObject(objectHandle)
    } catch (t: Throwable) {
        0
    }

/**
 * `int pm_upcall_cancel_call(long callHandle)` -- what a cancelled `asyncio.Future`'s done callback
 * calls, so a suspended Kotlin coroutine hears about it at its next `ensureActive()` rather than at
 * completion.
 *
 * 1 if it cancelled a live call, 0 for a handle that is stale, never issued, or names something
 * that is not a pending call. Same `(long) -> int` shape as [pmUpcallReleaseObject], deliberately.
 */
@CName("pm_upcall_cancel_call")
fun pmUpcallCancelCall(callHandle: Long): Int =
    try {
        UpcallTrampoline.cancelCall(callHandle)
    } catch (t: Throwable) {
        0
    }

// -------------------------------------------------------------------------------------------
// The same entry points, shaped as real PyCFunctions
// -------------------------------------------------------------------------------------------

/**
 * `PyCFunction`, `METH_VARARGS`, **unbound**: `args` is `(handle, args_tuple)`.
 *
 * This is the shape [python.multiplatform.ffi.upcall.PythonProxySource]'s generated module calls,
 * and the same one desktop's `ctypes.CFUNCTYPE(py_object, c_long, py_object)` shim presents. It
 * grants nothing [pmBindMethod] did not already grant -- `_pm_bind` takes a raw handle straight
 * from Python and hands back a callable over it, so `_pm_bind(h)(*a)` and `_pm_invoke(h, a)` are
 * the same capability with the same failure mode. See [UpcallEntry.publish] for why the earlier
 * decision to withhold this one did not survive being looked at.
 *
 * A handle that is not an integer, or names nothing, is rejected by [UpcallTrampoline] with
 * Python's error indicator set -- not a crash, and not a call to whatever entry 0 happens to be.
 */
private fun pmInvokeFreeMethod(self: CPointer<CPyObject>?, args: CPointer<CPyObject>?): CPointer<CPyObject>? =
    try {
        entered {
            val tuple = args?.let { NativePointer(it) }
            if (tuple == null || PyTuple_Size(tuple) != 2L) {
                if (PyErr_Occurred() == null) {
                    raiseTypeError("_pm_invoke(handle, args_tuple) takes exactly two arguments")
                }
                null
            } else {
                // Both items are borrowed; nothing here releases either.
                val handleItem = PyTuple_GetItem(tuple, 0)
                val raw = if (handleItem == null) CallableHandle.NONE.raw else PyLong_AsLongLong(handleItem)
                if (PyErr_Occurred() != null) {
                    null
                } else {
                    // `PyTuple_GetItem` cannot fail for index 1 of a size-2 tuple; the elvis is
                    // for the type checker, and 0 is the trampoline's own "no arguments".
                    val callArgs = PyTuple_GetItem(tuple, 1)?.toRawValue() ?: UpcallTrampoline.NULL
                    UpcallTrampoline.invoke(raw, callArgs).toCPointer()
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

/**
 * Sets a `TypeError` without a raw `PyExc_TypeError` symbol.
 *
 * Reached through `builtins` for the same reason [UpcallTrampoline]'s own `raiseInPython` does it
 * that way: the exception type objects are data symbols rather than functions, and pulling them
 * out of the builtins dict costs one lookup on a path that is already failing.
 */
private fun raiseTypeError(message: String) {
    val builtins = PyEval_GetBuiltins() ?: return
    val typeError = PyDict_GetItemString(builtins, "TypeError") ?: return
    PyErr_SetString(typeError, message)
}

/**
 * `PyCFunction` for a handle already bound: `self` carries it, `args` is the argument tuple.
 *
 * This is the shape [pmUpcallInvoke] was written to converge on, reached the way CPython reaches
 * any built-in function. It exists as well as the raw symbol because **the iOS distribution has
 * no `_ctypes`** (`Python.framework` exports `PyInit__abc` ... `PyInit_time` and nothing else,
 * and carries no `lib-dynload`), so `ctypes.CDLL(None)` -- the route `docs/design/upcall.md`
 * names for this platform -- cannot be used there at all. A `PyMethodDef` is the only way to hand
 * Python a callable that lands on a C function pointer on that target, and it is also what the
 * generated proxy type will install, so nothing here is scaffolding for the test alone.
 *
 * A bad `self` becomes [CallableHandle.NONE], which [UpcallTrampoline] rejects with the error
 * indicator set -- not a crash, and not a call to whatever entry 0 happens to be.
 */
private fun pmInvokeMethod(self: CPointer<CPyObject>?, args: CPointer<CPyObject>?): CPointer<CPyObject>? =
    try {
        UpcallTrampoline.invoke(handleOf(self), args.toLong()).toCPointer()
    } catch (t: Throwable) {
        null
    }

/**
 * `PyCFunction`, `METH_O`: `str` **or** `bytes` -> handle, or -1. The Python face of
 * [pmUpcallResolve].
 *
 * Both spellings are accepted because both are forced on real callers, and neither can be made to
 * yield. A `PyMethodDef` host writes `_pm_resolve('pkg.fn')`, which is what `str` is for. Desktop
 * reaches its resolver through `ctypes.CFUNCTYPE(c_long, c_char_p)`, and `c_char_p` **refuses a
 * `str`** -- so the one piece of Python that has to work against every host,
 * [python.multiplatform.ffi.upcall.PythonProxySource]'s `_pm_lookup`, has no choice but to send
 * `bytes`. Before this accepted them, that call reached `PyUnicode_AsUTF8`, left a `TypeError`
 * pending and returned NULL: the generated module could not resolve a single name on this target.
 *
 * Costs one failed `PyUnicode_AsUTF8` and a `PyErr_Clear` on the `bytes` path, at install time
 * only -- a name is resolved once and is an integer from then on.
 */
private fun pmResolveMethod(self: CPointer<CPyObject>?, name: CPointer<CPyObject>?): CPointer<CPyObject>? =
    try {
        entered {
            val text = name?.let { raw ->
                val pointer = NativePointer(raw)
                PyUnicode_AsUTF8(pointer) ?: run {
                    // The failed str read left a TypeError pending; it is not the answer yet.
                    PyErr_Clear()
                    PyBytes_AsString(pointer)
                }
            }
            val handle = if (text == null) CallableHandle.NONE.raw else UpcallTable.resolve(text).raw
            // Neither spelling matched, so `PyBytes_AsString`'s TypeError is the honest answer --
            // better than -1, which would read as "no such name". Returning NULL hands it to
            // Python untouched.
            if (text == null && PyErr_Occurred() != null) null
            else PyLong_FromLongLong(handle)?.toPlatformPointer()
        }
    } catch (t: Throwable) {
        null
    }

/**
 * `PyCFunction`, `METH_O`: handle -> a callable that invokes it.
 *
 * The handle is **not** validated here. A stale one is rejected by [UpcallTrampoline] at call
 * time with a proper Python exception, and validating twice would only move the same failure
 * earlier while adding a second place that has to agree with the table's epoch rule.
 */
private fun pmBindMethod(self: CPointer<CPyObject>?, handle: CPointer<CPyObject>?): CPointer<CPyObject>? =
    try {
        entered {
            val raw = handle?.let { PyLong_AsLongLong(NativePointer(it)) } ?: CallableHandle.NONE.raw
            if (PyErr_Occurred() != null) null else bindHandle(raw)
        }
    } catch (t: Throwable) {
        null
    }

/** `PyCFunction`, `METH_O`: object handle -> 1 if it released a live entry. */
private fun pmReleaseMethod(self: CPointer<CPyObject>?, handle: CPointer<CPyObject>?): CPointer<CPyObject>? =
    try {
        entered {
            val raw = handle?.let { PyLong_AsLongLong(NativePointer(it)) } ?: 0L
            if (PyErr_Occurred() != null) null
            else PyLong_FromLongLong(UpcallTrampoline.releaseObject(raw).toLong())?.toPlatformPointer()
        }
    } catch (t: Throwable) {
        null
    }

/** `PyCFunction`, `METH_O`: pending-call handle -> 1 if it cancelled a live call. */
private fun pmCancelMethod(self: CPointer<CPyObject>?, handle: CPointer<CPyObject>?): CPointer<CPyObject>? =
    try {
        entered {
            val raw = handle?.let { PyLong_AsLongLong(NativePointer(it)) } ?: 0L
            if (PyErr_Occurred() != null) null
            else PyLong_FromLongLong(UpcallTrampoline.cancelCall(raw).toLong())?.toPlatformPointer()
        }
    } catch (t: Throwable) {
        null
    }

/**
 * Takes the GIL for [block] unconditionally.
 *
 * The same rule as [UpcallTrampoline]'s own `attached`, and for the same reason: these are
 * entered from C, and `withGIL`'s nesting depth records only scopes *Kotlin* opened. C is free to
 * have dropped the GIL inside one of them. `PyGILState_Ensure` is reentrant, so the second one
 * inside [UpcallTrampoline.invoke] costs a counter bump.
 */
private inline fun <T> entered(block: () -> T): T {
    val state = PyGILState_Ensure()
    try {
        return block()
    } finally {
        PyGILState_Release(state)
    }
}

/** Reads the callable handle out of a `PyCFunction`'s `self`, under its own GIL. */
private fun handleOf(self: CPointer<CPyObject>?): Long {
    val bound = self ?: return CallableHandle.NONE.raw
    return entered {
        val raw = PyLong_AsLongLong(NativePointer(bound))
        if (PyErr_Occurred() != null) {
            PyErr_Clear()
            CallableHandle.NONE.raw
        } else {
            raw
        }
    }
}

/**
 * Builds a Python callable over [raw]. Caller must hold the GIL; returns a **new** reference.
 *
 * `PyCMethod_New` takes its own reference to `self`, so the `PyLong` built here is released
 * immediately afterwards -- keeping it would leak one integer per bound method.
 */
private fun bindHandle(raw: Long): CPointer<CPyObject>? {
    val boundSelf = PyLong_FromLongLong(raw) ?: return null
    return try {
        PyCMethod_New(UpcallEntry.invokeDef, boundSelf.toPlatformPointer(), null, null)
    } finally {
        Py_DecRef(boundSelf)
    }
}

/**
 * The Kotlin/Native half of the upcall boundary: the few lines that give Python the *address* of
 * an entry point. All of the marshalling is in [UpcallTrampoline], which is `commonMain`.
 *
 * Lives in `nativeMain` rather than `iosMain` because `nativeMain` is shared with androidNative;
 * putting it under `iosMain` leaves the androidNative compilation without it, which is the exact
 * failure `nativeMain/README.md` warns about. Nothing here is Android-shaped, so nothing here
 * belongs in `artMain`.
 *
 * ### Two routes, because the documented one only half exists
 *
 * `docs/design/upcall.md` named one route for this platform -- a `@CName` symbol picked up by
 * `ctypes.CDLL(None)`, on the grounds that Python and Kotlin/Native are one binary so no C glue
 * is needed. Checked, and it holds in one of the three binaries this project produces:
 *
 * | Binary | `pm_upcall_invoke` |
 * |---|---|
 * | androidNative `libmultiplatform_python3.14.so` | exported (`T` in `nm -D`) |
 * | iOS `PythonMultiplatform.framework` | **absent** -- a framework's export set is the Objective-C surface plus the Konan runtime; a `@CName` alias is not in it |
 * | Kotlin/Native test executable (either target) | **absent**, and absent from the per-file caches too, so it is never emitted rather than dropped at link time |
 *
 * And on iOS the other half fails independently: this project's `Python.framework` has **no
 * `_ctypes`** (it exports `PyInit__abc` through `PyInit_time` and ships no `lib-dynload`), so
 * `import ctypes` raises `ModuleNotFoundError` and nothing on that target can call a bare address
 * from Python at all. The route is therefore real for androidNative -- whose CPython does ship
 * `_ctypes` -- and unavailable on iOS in both directions at once.
 *
 * So the Python-facing route here is a real `PyMethodDef`: [bind] hands back an ordinary built-in
 * function object whose `ml_meth` is a [staticCFunction] over [pmInvokeMethod] and whose `self`
 * is the callable handle. That is not a workaround so much as the thing `ctypes` was standing in
 * for on desktop -- desktop's ctypes shim in `UpcallEntryTest` (`commonTest`) exists precisely
 * because a `PyCFunction` slot is what a finished binder installs, and this skips the stand-in.
 *
 * The `@CName` symbols stay: they are what a C host embeds against, they are what `ctypes`
 * reaches on Android, and [invokeAddress] makes them callable regardless of what the linker did
 * with the name.
 *
 * ### Lifetime
 *
 * The `PyMethodDef`s and their names are allocated on [nativeHeap] and never freed: a
 * `PyCFunction` object stores the `PyMethodDef *` it was built from rather than copying it, so
 * freeing one would leave every callable built from it pointing at released memory. Six structs
 * for the life of the process.
 */
object UpcallEntry {

    /** Address of [pmUpcallInvoke], `(long, PyObject *) -> PyObject *`. */
    val invokeAddress: Long by lazy { staticCFunction(::pmUpcallInvoke).toLong() }

    /** Address of [pmUpcallResolve], `(const char *) -> long`. */
    val resolveAddress: Long by lazy { staticCFunction(::pmUpcallResolve).toLong() }

    /** Address of [pmUpcallReleaseObject], `(long) -> int`. */
    val releaseObjectAddress: Long by lazy { staticCFunction(::pmUpcallReleaseObject).toLong() }

    /** Address of [pmUpcallCancelCall], `(long) -> int`. */
    val cancelCallAddress: Long by lazy { staticCFunction(::pmUpcallCancelCall).toLong() }

    internal val invokeDef: CPointer<PyMethodDef> by lazy {
        methodDef("pm_invoke", METH_VARARGS, staticCFunction(::pmInvokeMethod))
    }

    private val invokeFreeDef: CPointer<PyMethodDef> by lazy {
        methodDef("pm_invoke", METH_VARARGS, staticCFunction(::pmInvokeFreeMethod))
    }

    private val resolveDef: CPointer<PyMethodDef> by lazy {
        methodDef("pm_resolve", METH_O, staticCFunction(::pmResolveMethod))
    }

    private val bindDef: CPointer<PyMethodDef> by lazy {
        methodDef("pm_bind", METH_O, staticCFunction(::pmBindMethod))
    }

    private val releaseDef: CPointer<PyMethodDef> by lazy {
        methodDef("pm_release", METH_O, staticCFunction(::pmReleaseMethod))
    }

    private val cancelDef: CPointer<PyMethodDef> by lazy {
        methodDef("pm_cancel", METH_O, staticCFunction(::pmCancelMethod))
    }

    /**
     * A **new** reference to a Python callable that invokes [handle], or `null`.
     *
     * Callable from Kotlin without the GIL; takes it for the duration.
     */
    fun bind(handle: Long): NativePointer? = entered { bindHandle(handle)?.let { NativePointer(it) } }

    /**
     * Installs `_pm_resolve`, `_pm_invoke`, `_pm_bind`, `_pm_release` and `_pm_cancel` into
     * [namespace] (a Python dict).
     *
     * That set is the whole bootstrap: everything after it is Python calling Python objects.
     *
     * ### Why `_pm_invoke` is here now
     *
     * It was deliberately withheld, on the grounds that a handle should only ever be reached
     * through the callable `_pm_bind` returns -- "which is what keeps the handle out of Python's
     * hands as a separate argument". That reason does not survive being checked against the file
     * next to it:
     *
     * - **Nothing enforced it.** [pmBindMethod] takes a raw integer *from Python* and hands back a
     *   callable over it, and `_pm_release`/`_pm_cancel` take raw handles too. `_pm_bind(h)(*a)`
     *   is `_pm_invoke(h, a)` with the same arguments, the same trampoline and the same rejection
     *   of a bad handle. Publishing it grants no capability that was not already published.
     * - **The one consumer needs it.** Every call
     *   [python.multiplatform.ffi.upcall.PythonProxySource] generates is `_pm_invoke(handle,
     *   args)`, and its guard refuses to install the module without the name. Withholding it did
     *   not keep handles out of Python's hands; it kept the generated proxies out of every target
     *   but desktop, whose `ctypes` shim publishes `_pm_invoke` and never had the argument. That
     *   was invisible until `PythonProxyInstallTest` moved to `commonTest`, where the iOS
     *   simulator answered `RuntimeError: the raw upcall entry points are not bound`.
     * - **The alternative costs more.** Teaching the generator to synthesise `_pm_invoke` from
     *   `_pm_bind` means a `PyCMethod_New` allocation *per call* rather than one `PyMethodDef` per
     *   process, and leaves two bootstrap conventions in the tree for the generated Python to
     *   arbitrate between at run time.
     *
     * `_pm_bind` stays: it is what `UpcallEntryTest`'s per-platform binding step uses, and a
     * callable bound once is still the cheaper shape for a caller that has one.
     *
     * `_pm_cancel` is what `PythonProxySource`'s `_pm_watch` needs to exist before it will arm a
     * cancellation notice at all; without it the async boundary still works, but a `Future.cancel()`
     * only reaches Kotlin when the coroutine finishes.
     *
     * @return true if all five landed.
     */
    fun publish(namespace: NativePointer): Boolean = entered {
        install(namespace, "_pm_resolve", resolveDef) &&
            install(namespace, "_pm_invoke", invokeFreeDef) &&
            install(namespace, "_pm_bind", bindDef) &&
            install(namespace, "_pm_release", releaseDef) &&
            install(namespace, "_pm_cancel", cancelDef)
    }

    /** `PyDict_SetItemString` takes a reference of its own, so the one built here is given back. */
    private fun install(namespace: NativePointer, name: String, def: CPointer<PyMethodDef>): Boolean {
        val fn = PyCMethod_New(def, null, null, null) ?: return false
        val wrapped = NativePointer(fn)
        return try {
            PyDict_SetItemString(namespace, name, wrapped) == 0
        } finally {
            Py_DecRef(wrapped)
        }
    }

    private fun methodDef(
        name: String,
        flags: Int,
        function: CPointer<CFunction<(CPointer<CPyObject>?, CPointer<CPyObject>?) -> CPointer<CPyObject>?>>,
    ): CPointer<PyMethodDef> {
        val def = nativeHeap.alloc<PyMethodDef>()
        def.ml_name = allocPermanentCString(name)
        def.ml_meth = function.reinterpret()
        def.ml_flags = flags
        def.ml_doc = null
        return def.ptr
    }
}
