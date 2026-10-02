package python.native.ffi

import python.multiplatform.ffi.upcall.UpcallTrampoline
import python.multiplatform.reflection.CallableHandle
import python.multiplatform.reflection.UpcallTable
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

object UpcallTarget {
    @JvmStatic
    fun upcallPrimitive(x: Long): Long {
        return x
    }

    @JvmStatic
    fun upcallString(addr: Long): Long {
        val s = Panama.readUtf8String(addr) ?: return 0L
        return s.length.toLong()
    }

    /**
     * Verification entry point for ROADMAP §7's "does the manual registration path survive
     * GraalVM native-image" question: Python passes a `const char*`, this resolves it against
     * [UpcallTable] the same way a generated trampoline would, and hands back the raw
     * [CallableHandle] as a plain long. `CallableHandle.NONE.raw` (-1) means "no such name" --
     * the boundary carries that back to Python rather than throwing, same as
     * [UpcallTable.resolve] itself.
     */
    @JvmStatic
    fun upcallResolveHandle(nameAddr: Long): Long {
        val name = Panama.readUtf8String(nameAddr) ?: return CallableHandle.NONE.raw
        return UpcallTable.resolve(name).raw
    }

    /**
     * The fast path this whole design exists for: a handle already resolved once, invoked with
     * no further name lookup. Restricted to zero-argument, `Long`-returning entries because the
     * shape a Panama upcall stub can target here is `(long) -> long`.
     *
     * Kept as it is because `sample-bindings` and the GraalVM closed-world question it answers
     * both use it; anything that needs to *pass* something uses [upcallInvokeWithArgs].
     */
    @JvmStatic
    fun upcallInvokeHandle(handleRaw: Long): Long {
        return UpcallTable.invoke(CallableHandle(handleRaw), emptyArray()) as Long
    }

    /**
     * The general entry point: a resolved handle plus a borrowed `PyObject *` argument tuple,
     * returning a **new** reference (or `0` with Python's error indicator set).
     *
     * All of the work is in [UpcallTrampoline], which is `commonMain` -- there is nothing
     * desktop-specific about turning a tuple into an `Array<Any?>`. What is desktop-specific is
     * only how the address of this method reaches Python, i.e. `Panama.createUpcallStubII_L`.
     * See `docs/design/upcall-design.md` for what the other platforms need instead.
     */
    @JvmStatic
    fun upcallInvokeWithArgs(handleRaw: Long, argsTuple: Long): Long =
        UpcallTrampoline.invoke(handleRaw, argsTuple)

    /**
     * Drops the [python.multiplatform.reflection.HandleTable] root behind an object handle
     * Python was given, returning 1 if it released a live entry and 0 otherwise.
     *
     * Shape `(long) -> int`, which `Panama` already builds for `tp_clear`, so this costs no new
     * stub. A generated proxy type calls this from `tp_dealloc` instead; the ctypes-level bridge
     * has no `tp_dealloc` to hang it off, and without it every object returned to Python stays
     * rooted forever.
     */
    @JvmStatic
    fun upcallReleaseObject(objectHandle: Long): Int = UpcallTrampoline.releaseObject(objectHandle)

    /**
     * Tells a suspended Kotlin call that the `asyncio.Future` carrying its result was cancelled,
     * returning 1 if that was news.
     *
     * Same `(long) -> int` shape as [upcallReleaseObject], so it reuses `Panama`'s existing stub
     * builder and costs no new shape. `PythonProxySource`'s `_pm_watch` is what calls it, from a
     * `Future.add_done_callback`; see [UpcallTrampoline.cancelCall].
     */
    @JvmStatic
    fun upcallCancelCall(callHandle: Long): Int = UpcallTrampoline.cancelCall(callHandle)
}

object UpcallStub {
    val primitiveStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallPrimitive" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubLongToLong(handle)
    }

    val stringStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallString" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubLongToLong(handle)
    }

    /** `Python's ctypes.CFUNCTYPE(c_long, c_char_p)` target: name -> handle. */
    val resolveHandleStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallResolveHandle" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubLongToLong(handle)
    }

    /** `Python's ctypes.CFUNCTYPE(c_long, c_long)` target: handle -> result. */
    val invokeHandleStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallInvokeHandle" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubLongToLong(handle)
    }

    /**
     * `ctypes.CFUNCTYPE(py_object, c_long, py_object)` target: (handle, args tuple) -> result.
     *
     * The one stub that carries arguments, and the same C shape a `PyCFunction` slot takes. See
     * [UpcallTarget.upcallInvokeWithArgs].
     */
    val invokeWithArgsStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallInvokeWithArgs" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubII_L(handle)
    }

    /** `ctypes.CFUNCTYPE(c_int, c_long)` target: object handle -> 1 if it released a live entry. */
    val releaseObjectStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallReleaseObject" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubI_I(handle)
    }

    /** `ctypes.CFUNCTYPE(c_int, c_long)` target: pending-call handle -> 1 if it cancelled a live call. */
    val cancelCallStubAddr: Long by lazy {
        val method = UpcallTarget::class.java.methods.first { it.name == "upcallCancelCall" }
        val handle = MethodHandles.lookup().unreflect(method)
        Panama.createUpcallStubI_I(handle)
    }
}
