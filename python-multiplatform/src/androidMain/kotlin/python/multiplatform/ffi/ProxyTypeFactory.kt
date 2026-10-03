package python.multiplatform.ffi

import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.native.ffi.bindings
import python.native.ffi.internedUtf8

/**
 * The Kotlin half of the proxy type's `tp_traverse`, `tp_clear` and `tp_dealloc`.
 *
 * ### Why this shape and not the desktop one
 *
 * Desktop turns two Kotlin functions into C function pointers with Panama upcall stubs, and
 * Kotlin/Native does the same with `staticCFunction`. Android has neither: `androidMain` is
 * Kotlin/JVM, and the JVM it runs on is ART, which has no Panama. So the two slots are C
 * functions in `artMain/cinterop/jni_onload.def`, and *they* call *this* -- the direction of the
 * boundary is inverted relative to every other platform.
 *
 * That inversion is why [traverse] returns an array instead of taking a visitor. `visit` is a
 * bare C function pointer; there is no way to call one from Kotlin/JVM without a downcall
 * mechanism Android does not have. So the C side keeps the visiting and asks only which
 * pointers to visit.
 *
 * ### Rules these two must obey
 *
 * CPython holds the GIL when it calls the slots, so neither of these may take it ([withGIL]) or
 * release it. Neither may call back into the C API at all: `tp_clear` in particular must not
 * touch reference counts, because the collector is in the middle of accounting for them and a
 * `Py_DecRef` from here double-frees. Releasing the [HandleTable] entry is the whole job --
 * that entry is the Kotlin-side half of the cycle, and dropping it is what breaks the loop.
 *
 * Both swallow exceptions. A `Throwable` crossing back into a running collection has nowhere to
 * go: the C side clears the pending exception because `RegisterNatives`-bound code cannot leave
 * one set, and the only channel back into CPython is a return value that aborts the traversal
 * rather than reporting anything.
 *
 * ### Overhead
 *
 * One JNI upcall per traversed proxy per collection, plus one `LongArray` allocation, plus the
 * boxing `ReflectedClass.traverse`'s `(Long) -> Unit` visitor forces on the JVM. Desktop pays
 * the boxing too; the upcall and the array are Android's alone. Nothing here is on a hot path
 * until an application holds many proxies at once, but a collection sweeping N proxies costs N
 * JVM allocations inside CPython's collector, which is where to look first if a GC pause on
 * Android turns out worse than on desktop.
 */
object ProxyCallbacks {

    private val EMPTY = LongArray(0)

    /**
     * The raw `PyObject*` addresses the Kotlin object behind [handle] holds, for the C side to
     * hand to `visit`.
     *
     * A `null` return is not possible; an empty array means "nothing to report", which is also
     * what an unregistered class produces. That silence is by design and is worth knowing
     * about: it reads exactly like "the object was never GC-tracked". If a cycle is not
     * collected, check that [ClassLookup] has the class before suspecting the slots -- a test
     * that registers its class only on the path where it also initialises the interpreter
     * registers nothing in a full-suite run, and both desktop and iOS hit that once.
     */
    @JvmStatic
    fun traverse(handle: Long): LongArray {
        return try {
            val obj = HandleTable.resolveRaw(handle) ?: return EMPTY
            val cls = ClassLookup.find(obj::class.qualifiedName ?: "") ?: return EMPTY
            if (!cls.hasTraverse) return EMPTY

            var out = LongArray(4)
            var n = 0
            cls.traverse(obj) { raw ->
                if (raw != 0L) {
                    if (n == out.size) out = out.copyOf(out.size * 2)
                    out[n++] = raw
                }
            }
            if (n == 0) EMPTY else if (n == out.size) out else out.copyOf(n)
        } catch (t: Throwable) {
            EMPTY
        }
    }

    /**
     * Drops the [HandleTable] root for [handle], and nothing else.
     *
     * Called from both C slots. The C side zeroes the proxy's handle slot before calling, so an
     * object that dies inside a cycle -- which runs `tp_clear` and then `tp_dealloc` -- reaches
     * here exactly once. [HandleTable.release] is a no-op for a stale handle anyway, which is
     * the backstop that makes a double release safe rather than a slot hijack.
     */
    @JvmStatic
    fun clear(handle: Long) {
        try {
            if (handle != 0L) HandleTable.release(ObjectReference(handle))
        } catch (t: Throwable) {
            // Same reason as in traverse: there is nowhere for this to go.
        }
    }
}

/**
 * Android's cycle-collecting proxy type.
 *
 * The type itself is built in C (`proxy_create_type` in `jni_onload.def`) because its three slots
 * have to be C function pointers. This is the Kotlin entry point to that, plus the accessors a
 * caller needs to put a handle into a proxy instance.
 *
 * ### The foreign-thread path is exercised now
 *
 * The slots used to be reached only from a thread that already runs Kotlin -- the one that called
 * `gc.collect()` -- so `pmp_attach`'s `GetEnv` always succeeded and the
 * `AttachCurrentThreadAsDaemon` branch beside it was, as far as any test went, dead code.
 *
 * `CycleCollectionTest.testCycleCollectedOnAThreadCPythonCreated` and
 * `testDeallocOnAThreadCPythonCreated` close that. A `threading.Thread` is a bare pthread ART has
 * never seen, and those two run the collection, and the last-reference drop, on one. Neither
 * infers the thread from the effect: the `traverse` lambda they register records
 * `Thread.currentThread().id`, and they assert it differs from the instrumentation thread's -- so
 * a callback that had quietly been re-dispatched onto a thread ART already knew would fail rather
 * than pass. Green on `pmp_api26` and `pmp_api36`, 216 tests each, 0 failed, with no
 * `Native thread exiting without having called DetachCurrentThread` in either logcat. That line
 * is what ART prints immediately before aborting if the detach on the way out is ever missed, so
 * its absence is the attach/detach balance being checked rather than assumed.
 *
 * `tp_dealloc` (`pmp_proxy_dealloc` in `jni_onload.def`) closes what used to be the larger hole:
 * a proxy that dies without a cycle never runs `tp_clear`, so its [HandleTable] entry leaked --
 * and since cycles are the exception, almost every proxy died that way. All three platforms
 * carry the slot, and all three now measure it:
 * `CycleCollectionTest.testHandleReleasedWhenProxyDiesWithoutCycle` drops 100 cycle-free proxies
 * and checks both the handles and the type's own `ob_refcnt` (through [bindings.obRefCnt] here,
 * `sun.misc.Unsafe` on desktop, a `LongVar` load on Kotlin/Native).
 */
actual object ProxyTypeFactory {

    // @Volatile, unlike desktop's plain field: the fast-path read below happens outside withGIL,
    // and a non-volatile 64-bit field may be read torn on the JVM. The C side keeps its own
    // guard, so a lost race only means PyType_FromSpec is asked twice and answers the same;
    // a torn read would hand out a pointer that never existed.
    @Volatile
    private var proxyTypePtr: Long = 0L

    /** Creates the proxy type once and returns its address; later calls return the same one. */
    actual fun createProxyType(): Long {
        if (proxyTypePtr != 0L) return proxyTypePtr

        // Forces ProxyCallbacks' <clinit> here rather than inside a running collection. The
        // class is only ever reached from tp_traverse otherwise, and initialising a JVM class
        // for the first time while CPython's collector holds the GIL is a strictly worse place
        // for it to happen. resolveRaw(0) is HandleTable's null handle, so this is a no-op.
        ProxyCallbacks.traverse(0L)

        proxyTypePtr = withGIL { bindings.proxyCreateType() }
        return proxyTypePtr
    }

    /**
     * Reads the handle stored in [proxy]'s relative type data, or 0.
     *
     * Returns 0 for anything that is not an instance of the proxy type, and for a proxy whose
     * handle `tp_clear` has already zeroed.
     */
    fun handleOf(proxy: Long): Long = bindings.proxyGetHandle(proxy)

    /**
     * Stores [handle] in [proxy]'s relative type data, which is what makes the Python object
     * hold the Kotlin one.
     *
     * A no-op if [proxy] is not an instance of the proxy type -- there is no error channel here,
     * because `PyObject_GetTypeData` on a foreign object has no defined answer to report.
     */
    fun setHandle(proxy: Long, handle: Long) = bindings.proxySetHandle(proxy, handle)

    /**
     * Publishes [createProxyType]'s type into `__main__` as `_pm_proxy_base`.
     *
     * `PyObject_SetAttrStringN` takes the type's own address directly as the value -- a
     * `PyTypeObject*` is a valid `PyObject*`, its header is the same one every other object here
     * has -- so this needs no `ctypes` round trip through an integer and back. `PyObject_SetAttrStringN`
     * does not steal the reference it is given; the type is never freed anyway (see
     * `proxy_create_type`'s own spec/slots allocations in `jni_onload.def`), so nothing here needs
     * an extra incref to keep it alive under `__main__`.
     */
    actual fun installGcBase(): Boolean {
        val type = createProxyType()
        if (type == 0L) return false
        return withGIL {
            val main = bindings.PyImport_AddModuleN(internedUtf8("__main__"))
            if (main == 0L) return@withGIL false
            bindings.PyObject_SetAttrStringN(main, internedUtf8("_pm_proxy_base"), type) == 0
        }
    }
}
