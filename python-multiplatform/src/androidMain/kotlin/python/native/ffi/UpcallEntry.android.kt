package python.native.ffi

import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.CallableHandle
import python.multiplatform.reflection.UpcallTable

/**
 * What the C entry points in `artMain/cinterop/jni_onload.def` call on every upcall.
 *
 * ### Why the boundary runs this way round
 *
 * Desktop turns a Kotlin method into a C function pointer with a Panama upcall stub, and
 * Kotlin/Native does the same with `staticCFunction`. Android has neither: `androidMain` is
 * Kotlin/JVM and the JVM it runs on is ART, which has no Panama. A `PyMethodDef`'s `ml_meth` has
 * to be a real C function pointer, so the entry points are C functions in `jni_onload.def` and
 * *they* call *this*, through `CallStaticLongMethod` against a class registered in `JNI_OnLoad`.
 *
 * That is the same inversion [python.multiplatform.ffi.ProxyCallbacks] already makes for
 * `tp_traverse`/`tp_clear`, and this file is deliberately its twin: same registration mechanism,
 * same `pmp_attach` on the C side, same rule that nothing may be thrown back into C.
 *
 * ### What is *not* here
 *
 * None of the marshalling. Turning a Python argument tuple into an `Array<Any?>` and a Kotlin
 * result back into a `PyObject *` is [UpcallTrampoline], which is `commonMain` and shared with
 * every other platform -- `docs/design/upcall.md` §3.7 is only the
 * address-publishing step, and on Android that step is the C shim plus these three methods.
 *
 * ### Rules these must obey
 *
 * - **Nothing may be thrown.** The caller is C reached from CPython. A Kotlin exception left
 *   pending across a JNI return aborts the ART runtime outright, so each of these is total: a
 *   failure leaves as `0` / `CallableHandle.NONE` / `0`, and the C side turns that into a Python
 *   error indicator. [UpcallTrampoline.invoke] already catches everything; the guards here are for
 *   the call itself, including a `<clinit>` that fails.
 * - **The GIL is taken by [UpcallTrampoline], not here.** CPython holds it when it calls a
 *   `PyCFunction`, but the trampoline still takes its own `PyGILState_Ensure`/`Release` pair
 *   unconditionally -- `commonMain/README.md`'s rule for any entry point reached from C, since the
 *   nesting-depth counter records only scopes Kotlin opened. `PyGILState_Ensure` is reentrant, so
 *   the second one costs a counter bump.
 * - **Reference conventions belong to the trampoline too.** Arguments arrive borrowed and the
 *   result leaves as a new reference; nothing on this file's path touches a refcount.
 *
 * ### What this still costs
 *
 * Measured by `UpcallOverheadTest` on `pmp_api26` / `pmp_api36`, two runs each, per upcall:
 *
 * | | API 26 | API 36 |
 * |---|---|---|
 * | steady state, any thread | 1209–1329 ns | 2301–3086 ns |
 * | **first upcall on a Python worker thread** | **139625–141958 ns** | **55917–125083 ns** |
 * | the same call shape as a downcall, for scale | 1213–1217 ns | 980–1541 ns |
 *
 * The first row is the JNI upcall and the trampoline's marshalling, and it is now within ~1-2x of
 * a downcall of the same shape. The second is the one cost that could not be removed: a
 * `threading.Thread` is a bare pthread, so the first time one reaches ART it must be attached.
 * `pmp_attach` holds that attachment for the life of the thread and gives it back from a
 * `pthread_key_create` destructor, which is what turned it from a per-call charge of 14–62 µs into
 * a per-thread one, but a thread that upcalls exactly once still pays all of it.
 *
 * It is not marked with [HighOverheadNativeCall]. That marker is `@RequiresOptIn`, and its job is
 * to make a Kotlin *caller* acknowledge a cost; nothing in Kotlin calls these, C does, from
 * CPython, so applying it here would be inert. The cost is recorded instead where the callers
 * that can act on it will look: here, in `pmp_upcall_invoke_meth`, and in `docs/design/upcall.md`.
 */
object UpcallCallbacks {

    /**
     * `pm_invoke`: a resolved handle plus a borrowed `PyObject *` argument tuple, returning a
     * **new** reference (or `0` with Python's error indicator set).
     *
     * @param callableHandle a raw [CallableHandle]; `-1` for a `self` that was not a valid one.
     */
    @JvmStatic
    fun invoke(callableHandle: Long, argsTuple: Long): Long =
        try {
            UpcallTrampoline.invoke(callableHandle, argsTuple)
        } catch (t: Throwable) {
            0L
        }

    /**
     * `_pm_resolve`: a name resolved once, an integer from then on.
     *
     * Returns `CallableHandle.NONE.raw` (-1) for a name nothing claims, exactly as
     * [UpcallTable.resolve] does; raising `AttributeError` is Python's job. Touches no C API, so
     * unlike [invoke] it needs no GIL.
     */
    @JvmStatic
    fun resolve(name: String?): Long =
        try {
            if (name == null) CallableHandle.NONE.raw else UpcallTable.resolve(name).raw
        } catch (t: Throwable) {
            CallableHandle.NONE.raw
        }

    /**
     * `_pm_release`: what a proxy's `tp_dealloc` calls.
     *
     * 1 if it released a live entry, 0 for a handle already released or never issued. A double
     * release must stay a no-op: the slot may belong to someone else by then.
     */
    @JvmStatic
    fun release(objectHandle: Long): Int =
        try {
            UpcallTrampoline.releaseObject(objectHandle)
        } catch (t: Throwable) {
            0
        }

    /**
     * `_pm_cancel`: what a cancelled `asyncio.Future`'s done callback calls, so a suspended Kotlin
     * coroutine hears about the cancellation at its next `ensureActive()` rather than only when it
     * finishes.
     *
     * 1 if it cancelled a live call, 0 for a handle that is stale, never issued, or names something
     * that is not a pending call. Same `(long) -> int` shape as [release], so the C side is a copy
     * of `pmp_upcall_release_meth` with one method ID changed.
     */
    @JvmStatic
    fun cancel(callHandle: Long): Int =
        try {
            UpcallTrampoline.cancelCall(callHandle)
        } catch (t: Throwable) {
            0
        }
}

/**
 * Android's half of the upcall boundary: the few lines that give Python the *address* of an entry
 * point. Named to match `nativeMain`'s `python.native.ffi.UpcallEntry`, because it is the same
 * thing reached by this platform's own mechanism.
 *
 * The entry points themselves are C (see [UpcallCallbacks] for why), so all that is left here is
 * asking for them to be installed under the GIL.
 */
object UpcallEntry {

    /**
     * Installs `_pm_resolve`, `_pm_invoke`, `_pm_bind`, `_pm_release` and `_pm_cancel` into
     * [namespace] (a Python dict).
     *
     * That set is the whole bootstrap: everything after it is Python calling Python objects.
     *
     * `_pm_invoke` -- the unbound `(handle, args_tuple) -> result` form -- used to be withheld, on
     * the argument that it would put the handle in Python's hands as a separate argument. But
     * `_pm_bind` already takes a raw handle from Python and gives back a callable over it, so
     * `_pm_bind(h)(*a)` and `_pm_invoke(h, a)` are one capability with one failure mode, and the
     * only consumer this bootstrap has is
     * [python.multiplatform.ffi.upcall.PythonProxySource]'s generated module, every call in which
     * is `_pm_invoke(handle, args)`. `nativeMain`'s `UpcallEntry.publish` reversed the same
     * decision first, for the same reason.
     *
     * Callable from Kotlin without the GIL; takes it for the duration.
     *
     * @return true if all five landed.
     */
    fun publish(namespace: NativePointer): Boolean {
        // Forces UpcallCallbacks' <clinit> -- and with it UpcallTrampoline's and UpcallTable's --
        // here rather than inside a running upcall. Otherwise the first class initialisation on
        // this path happens on whatever thread CPython called from, while it holds the GIL, which
        // is a strictly worse place for it; ProxyTypeFactory.createProxyType does the same for
        // ProxyCallbacks and for the same reason. Resolving a name nothing claims is a pure
        // lookup that returns -1.
        UpcallCallbacks.resolve(null)
        return withGIL { bindings.upcallPublish(namespace.toRawValue()) != 0 }
    }
}
