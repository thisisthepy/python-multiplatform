@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi

import kotlin.wasm.unsafe.Pointer
import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.CallableHandle
import python.multiplatform.reflection.UpcallTable

/** `METH_VARARGS` from `methodobject.h`. Flag values are ABI, not header-version-dependent. */
private const val METH_VARARGS = 0x0001

/**
 * `struct PyMethodDef { const char *ml_name; PyCFunction ml_meth; int ml_flags; const char *ml_doc; }`
 * -- four 4-byte fields on wasm32, unlike `nativeMain`'s where `ml_meth` alone is a real pointer.
 */
private const val METHODDEF_SIZE = 16

/**
 * The wasmJs half of the upcall boundary: [invokeMethod] is what CPython calls, reached the way
 * `docs/design/upcall.md` names for this target and ROADMAP §11 already measured --
 * `@WasmExport` plus `Table.set`, 3.1 ns/call through `call_indirect`. Everything past `self`/`args`
 * is [UpcallTrampoline], which is `commonMain` and knows nothing about wasm.
 *
 * `@WasmExport` is honoured only in the compilation that produces the `.wasm`, so the export
 * itself cannot live here -- the identical constraint `ProxyTypeFactory.kt` records for its three
 * type slots. `wasmJsTest/.../UpcallExports.kt` is the three-line file an application embedding
 * this library has to write; this object holds everything that could ever need changing.
 *
 * ### One export is enough for the whole bootstrap
 *
 * This used to publish nothing into Python at all: only a *bound* callable crossed, and
 * `docs/design/upcall.md` §3.7 recorded that `_pm_resolve`/`_pm_invoke`/`_pm_release`/
 * `_pm_cancel` would each need a **new `@WasmExport`**, which -- being honoured only in the
 * application's own compilation -- the library could never add. The consequence was that
 * `PythonProxySource.install()` refused on this target and no generated proxy existed here.
 *
 * That reasoning had a hole in it, and it is in the shape of a `PyCFunction`. `ml_meth` is a
 * function *pointer*, but a `PyCFunction` object also carries a `self`, and `PyCFunction_NewEx`
 * builds a fresh one per call. So **one** exported function pointer already backs arbitrarily many
 * distinct Python callables, told apart by what is boxed in `self` -- which is exactly what [bind]
 * has always done with a [CallableHandle]. Reserving a handful of values `self` can never
 * legitimately hold turns the same pointer into a dispatcher: [OP_RESOLVE] and its four siblings
 * are negative, and a real handle never is ([CallableHandle.isValid] is `raw >= 0`, and
 * [CallableHandle.NONE] is the single other negative value, `-1`, which is deliberately *not* an
 * op and still reaches the trampoline's own rejection).
 *
 * There is therefore no op *argument*: `self` is the discriminator that was already there. The
 * five entry points [publish] installs are five `PyCFunction` objects over one `PyMethodDef` shape
 * and one `Table.set`, and an embedding application still writes the same three lines it did
 * before.
 *
 * **What this does not buy** is the async half. `import asyncio` traps this wasm instance rather
 * than raising (`AsyncUpcallPortabilityTest`, `docs/design/upcall.md` §5.8), so a proxy whose
 * Kotlin body genuinely suspends still cannot be awaited here. The synchronous surface --
 * constructors, methods, properties, statics -- is what became reachable.
 *
 * ### A note on the GIL, for callers reading this rather than `nativeMain`'s equivalent
 *
 * [invokeMethod] is reached *from* C (`call_indirect`, with CPython already holding the GIL the
 * way any `PyCFunction` call does), so [handleOf] takes its own unconditional
 * `PyGILState_Ensure`/`Release` pair rather than trusting `withGIL`'s nesting depth -- the same
 * rule `UpcallTrampoline`'s own `attached` documents. [bind], in contrast, is called *from*
 * Kotlin, so it uses the ordinary [python.multiplatform.ffi.withGIL]. Both are safe to call
 * repeatedly on their own; what is not safe, and cost an afternoon to isolate, is a bare C API
 * call made directly by a *caller* after either one returns -- `PyGILState_Release` genuinely
 * detaches the thread here once the nesting count reaches zero, unlike a build where the main
 * thread stays implicitly attached forever. Every raw call after [bind] returns must go through
 * `withGIL` too; `UpcallEntryTest` is written that way for exactly this reason.
 */
object UpcallEntry {

    /**
     * `PyObject *(PyObject *self, PyObject *args)` -- the one C shape argument passing needs, and
     * the shape [bind] builds a `PyMethodDef` around. `self` carries the callable handle as a
     * boxed `PyLong`, the same convention `nativeMain`'s `UpcallEntry.pmInvokeMethod` uses, so that
     * when the generated proxy type lands, `self` takes the handle's place and no new shape
     * appears then either.
     *
     * A bad or unbound `self` becomes [CallableHandle.NONE], which [UpcallTrampoline] rejects with
     * Python's error indicator set -- not a crash, and not a call to whatever handle 0 happens to
     * resolve to.
     */
    fun invokeMethod(selfPtr: Int, argsPtr: Int): Int =
        try {
            when (val self = handleOf(selfPtr)) {
                // The dispatcher half. See the class doc: `self` was always the discriminator, and
                // these five values are ones a real handle cannot hold.
                OP_RESOLVE -> resolveOp(argsPtr)
                OP_INVOKE -> invokeOp(argsPtr)
                OP_BIND -> bindOp(argsPtr)
                OP_RELEASE -> unaryOp(argsPtr, "_pm_release") { UpcallTrampoline.releaseObject(it) }
                OP_CANCEL -> unaryOp(argsPtr, "_pm_cancel") { UpcallTrampoline.cancelCall(it) }
                // Widened the same way every raw wasm32 address crossing this boundary is:
                // `toUInt()` first so an address at or above 2 GiB does not sign-extend into a
                // negative Long. `CallableHandle.NONE` (-1) lands here on purpose -- the
                // trampoline rejects it with Python's error indicator set.
                else -> UpcallTrampoline.invoke(self, argsPtr.toUInt().toLong()).toInt()
            }
        } catch (t: Throwable) {
            0
        }

    // --------------------------------------------------------------------------- the five ops

    /**
     * `_pm_resolve(name) -> handle`, `METH_VARARGS`, accepting **`str` or `bytes`**.
     *
     * Both spellings, for the reason `nativeMain`'s `pmResolveMethod` records and the ART shim
     * learned the hard way (`docs/design/upcall.md` §3.7): `PythonProxySource`'s `_pm_lookup`
     * has to send `bytes` because desktop reaches its resolver through
     * `ctypes.CFUNCTYPE(c_long, c_char_p)`, which refuses a `str` outright -- while a `PyMethodDef`
     * host called by hand writes `str`. Costs one failed `PyUnicode_AsUTF8` per name, at install
     * time only.
     */
    private fun resolveOp(argsPtr: Int): Int = entered {
        val item = argument(argsPtr, 1, 0, "_pm_resolve(name)") ?: return@entered 0
        val text = PyUnicode_AsUTF8(item) ?: run {
            // The failed `str` read left a TypeError pending; it is not the answer yet.
            PyErr_Clear()
            PyBytes_AsString(item)
        }
        if (text == null) {
            // Neither spelling matched, so `PyBytes_AsString`'s own TypeError is the honest answer
            // -- better than -1, which would read as "no such name".
            if (PyErr_Occurred() == null) raiseTypeError("_pm_resolve takes a str or bytes name")
            return@entered 0
        }
        PyLong_FromLongLong(UpcallTable.resolve(text).raw)?.toPlatformPointer() ?: 0
    }

    /**
     * `_pm_invoke(handle, args_tuple) -> result`, `METH_VARARGS` and **unbound** -- the shape every
     * call `PythonProxySource` generates has, and the one its entry-point guard refuses to install
     * without.
     */
    private fun invokeOp(argsPtr: Int): Int = entered {
        val item = argument(argsPtr, 2, 0, "_pm_invoke(handle, args_tuple)") ?: return@entered 0
        val raw = PyLong_AsLongLong(item)
        if (PyErr_Occurred() != null) return@entered 0
        // Both items are borrowed from a tuple the caller still owns; nothing here releases either.
        // `PyTuple_GetItem` cannot fail for index 1 of a size-2 tuple, so the elvis is for the type
        // checker and `0` is the trampoline's own "no arguments".
        val callArgs = PyTuple_GetItem(NativePointer(argsPtr), 1)?.toRawValue() ?: UpcallTrampoline.NULL
        UpcallTrampoline.invoke(raw, callArgs).toInt()
    }

    /**
     * `_pm_bind(handle) -> callable`, the shape `UpcallEntryTest`'s per-platform binding step uses
     * on the other four targets.
     *
     * The handle is deliberately not validated: a stale one is rejected by [UpcallTrampoline] at
     * call time with a proper Python exception, and checking twice would only move the same failure
     * earlier while adding a second place that has to agree with the table's epoch rule.
     */
    private fun bindOp(argsPtr: Int): Int = entered {
        val item = argument(argsPtr, 1, 0, "_pm_bind(handle)") ?: return@entered 0
        val raw = PyLong_AsLongLong(item)
        if (PyErr_Occurred() != null) 0 else newCallable(invokeDef, raw)
    }

    /** The `(long) -> int` pair -- `_pm_release` and `_pm_cancel` -- which differ only in [body]. */
    private inline fun unaryOp(argsPtr: Int, name: String, body: (Long) -> Int): Int = entered {
        val item = argument(argsPtr, 1, 0, "$name(handle)") ?: return@entered 0
        val raw = PyLong_AsLongLong(item)
        if (PyErr_Occurred() != null) return@entered 0
        PyLong_FromLongLong(body(raw).toLong())?.toPlatformPointer() ?: 0
    }

    /**
     * Item [index] of the `METH_VARARGS` tuple at [argsPtr], or `null` with a `TypeError` set when
     * the tuple is missing or is not [arity] long.
     *
     * Caller must already be [entered].
     */
    private fun argument(argsPtr: Int, arity: Int, index: Long, signature: String): NativePointer? {
        val tuple = argsPtr.toNativePointerFromRaw()
        if (tuple == null || PyTuple_Size(tuple) != arity.toLong()) {
            if (PyErr_Occurred() == null) {
                raiseTypeError(
                    "$signature takes exactly $arity argument${if (arity == 1) "" else "s"}",
                )
            }
            return null
        }
        return PyTuple_GetItem(tuple, index)
    }

    /**
     * Sets a `TypeError` without a raw `PyExc_TypeError` symbol.
     *
     * Reached through `builtins` for the reason [UpcallTrampoline]'s own `raiseInPython` gives:
     * the exception types are data symbols rather than functions, and there is no `@WasmImport`
     * shape for a data symbol at all. One dict lookup, on a path that is already failing.
     */
    private fun raiseTypeError(message: String) {
        val builtins = PyEval_GetBuiltins() ?: return
        val typeError = PyDict_GetItemString(builtins, "TypeError") ?: return
        PyErr_SetString(typeError, message)
    }

    /**
     * A **new** reference to a Python callable that invokes [handle], or `null`.
     *
     * `PyCFunction_NewEx` takes its own reference to `self`, so the `PyLong` boxed here is
     * released immediately afterwards -- keeping it would leak one integer per bound callable.
     */
    fun bind(handle: Long): NativePointer? = withGIL {
        newCallable(invokeDef, handle).toNativePointerFromRaw()
    }

    /**
     * Installs `_pm_resolve`, `_pm_invoke`, `_pm_bind`, `_pm_release` and `_pm_cancel` into
     * [namespace] (a Python dict) -- the same five names `nativeMain`'s [UpcallEntry.publish]
     * installs, and the whole of what [python.multiplatform.ffi.upcall.PythonProxySource] asks a
     * host for.
     *
     * All five are `PyCFunction` objects over the **one** `@WasmExport` an embedding application
     * already declares; see the class doc for why no second export is needed.
     *
     * @return true if all five landed.
     */
    fun publish(namespace: NativePointer): Boolean = withGIL {
        install(namespace, "_pm_resolve", resolveDef, OP_RESOLVE) &&
            install(namespace, "_pm_invoke", invokeFreeDef, OP_INVOKE) &&
            install(namespace, "_pm_bind", bindDef, OP_BIND) &&
            install(namespace, "_pm_release", releaseDef, OP_RELEASE) &&
            install(namespace, "_pm_cancel", cancelDef, OP_CANCEL)
    }

    /** `PyDict_SetItemString` takes a reference of its own, so the one built here is given back. */
    private fun install(namespace: NativePointer, name: String, def: Int, op: Long): Boolean {
        val fn = newCallable(def, op).toNativePointerFromRaw() ?: return false
        return try {
            PyDict_SetItemString(namespace, name, fn) == 0
        } finally {
            Py_DecRef(fn)
        }
    }

    /**
     * A **new** `PyCFunction` over [def] whose `self` is the boxed [self]. `0` on failure.
     *
     * `PyCFunction_NewEx` takes its own reference to `self`, so the `PyLong` boxed here is released
     * immediately afterwards -- keeping it would leak one integer per callable built.
     */
    private fun newCallable(def: Int, self: Long): Int {
        val boxedSelf = PyLong_FromLongLong(self) ?: return 0
        return try {
            python.native.ffi.bindings.PyCFunction_NewEx(def, boxedSelf.toPlatformPointer(), 0)
        } finally {
            Py_DecRef(boxedSelf)
        }
    }

    /**
     * The one function pointer everything here goes through: the `@WasmExport` `pmp_invoke`, put
     * into CPython's `__indirect_function_table` once.
     *
     * Resolved once rather than per `PyMethodDef` because `Table.set` appends an entry: calling it
     * five times would grow the table by five for one function.
     */
    private val invokeFp: Int by lazy { registerUpcall("pmp_invoke") }

    /**
     * The `PyMethodDef *` every callable [bind] hands out points at, built once.
     *
     * Allocated through CPython's own `malloc` and never freed -- a `PyCFunction` object stores
     * the `PyMethodDef *` it was built from rather than copying it, so freeing this would leave
     * every bound callable pointing at released memory. One struct for the life of the process,
     * exactly as `ProxyTypeFactory`'s `PyType_Spec`/`PyType_Slot`s are.
     *
     * The five below are the same, and exist only so that each published name reports its own
     * `__name__` rather than all five answering `pm_invoke`; `ml_meth` is [invokeFp] in every one.
     */
    private val invokeDef: Int by lazy { buildMethodDef("pm_invoke", METH_VARARGS, invokeFp) }

    private val resolveDef: Int by lazy { buildMethodDef("pm_resolve", METH_VARARGS, invokeFp) }

    private val invokeFreeDef: Int by lazy { buildMethodDef("pm_invoke", METH_VARARGS, invokeFp) }

    private val bindDef: Int by lazy { buildMethodDef("pm_bind", METH_VARARGS, invokeFp) }

    private val releaseDef: Int by lazy { buildMethodDef("pm_release", METH_VARARGS, invokeFp) }

    private val cancelDef: Int by lazy { buildMethodDef("pm_cancel", METH_VARARGS, invokeFp) }

    /**
     * The op codes carried in `self`.
     *
     * Negative, because [CallableHandle.isValid] is `raw >= 0` and
     * [python.multiplatform.reflection.ObjectReference] handles are
     * `(generation shl 32) or slot` with a generation of at least 1 -- so no live handle of either
     * kind can collide with one of these. `-1` is left out deliberately: it is
     * [CallableHandle.NONE], and it has to keep reaching [UpcallTrampoline]'s rejection rather than
     * becoming a sixth entry point.
     */
    private const val OP_RESOLVE = -2L
    private const val OP_INVOKE = -3L
    private const val OP_BIND = -4L
    private const val OP_RELEASE = -5L
    private const val OP_CANCEL = -6L

    /**
     * Takes the GIL for [block] unconditionally.
     *
     * The same rule [handleOf] follows and for the same reason: these run inside a call that
     * arrived *from* C, where `withGIL`'s nesting depth records only scopes Kotlin opened.
     * `PyGILState_Ensure` is reentrant, so a nested one costs a counter bump.
     */
    private inline fun <T> entered(block: () -> T): T {
        val state = PyGILState_Ensure()
        try {
            return block()
        } finally {
            PyGILState_Release(state)
        }
    }

    /** Reads the callable handle out of `self`, under its own GIL -- C calls this with none held. */
    private fun handleOf(selfPtr: Int): Long {
        if (selfPtr == 0) return CallableHandle.NONE.raw
        val state = PyGILState_Ensure()
        return try {
            val raw = PyLong_AsLongLong(NativePointer(selfPtr))
            if (PyErr_Occurred() != null) {
                PyErr_Clear()
                CallableHandle.NONE.raw
            } else {
                raw
            }
        } finally {
            PyGILState_Release(state)
        }
    }

    /**
     * Puts the Kotlin `@WasmExport` named [name] into CPython's `__indirect_function_table` and
     * returns the index, which is the C function pointer -- the same call `ProxyTypeFactory` makes
     * for its three type slots, and the same failure modes.
     */
    private fun registerUpcall(name: String): Int {
        val fp = python.native.ffi.bindings.pmpRegisterUpcall(Wasm.internedUtf8(name))
        if (fp >= 0) return fp
        throw IllegalStateException(
            "could not register wasm export '$name' as an upcall entry point " +
                "(pmpRegisterUpcall returned $fp) -- see ProxyTypeFactory.registerUpcall for what " +
                "each negative code means; the wasmJsTest entry module must declare '$name' as a " +
                "delegating @WasmExport, the same way UpcallExports.kt does"
        )
    }

    private fun buildMethodDef(name: String, flags: Int, meth: Int): Int {
        val def = python.native.ffi.bindings.malloc(METHODDEF_SIZE)
        if (def == 0) throw OutOfMemoryError("malloc($METHODDEF_SIZE) failed for PyMethodDef")
        storeInt(def, Wasm.internedUtf8(name))
        storeInt(def + 4, meth)
        storeInt(def + 8, flags)
        storeInt(def + 12, 0)
        return def
    }

    private fun storeInt(address: Int, value: Int) {
        Pointer(address.toUInt()).storeInt(value)
    }
}
