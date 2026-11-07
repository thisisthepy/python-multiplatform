package python.multiplatform.ffi

import python.native.ffi.Panama
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles

/** `Thread.isVirtual` exists from JDK 21. Computed once. */
private val runtimeHasVirtualThreads: Boolean = Runtime.version().feature() >= 21

/**
 * True on a JVM virtual thread (#161). `Thread.isVirtual` is a JDK 21 API while the module targets
 * JVM 17; the JVM links a call site only when it first runs, so the version flag is tested first and
 * a JDK 17 to 20 runtime never reaches the call. A direct call: no lookup by name, no reflection.
 */
@PublishedApi
internal actual fun needsCarrierPin(): Boolean = runtimeHasVirtualThreads && Thread.currentThread().isVirtual

/**
 * Pins the calling virtual thread for [body] (#161 option B).
 *
 * A virtual thread cannot unmount while a native frame is on its stack (JDK 21 to 25: "pinned"). So
 * [body] runs inside a foreign call: C's standard `bsearch` over a one-element array calls its
 * comparator exactly once, and the comparator is an upcall stub that runs [body]. The thread stays on
 * this carrier from the downcall to its return, which covers [withGIL]'s `PyGILState_Ensure` to
 * `Release`. An upcall must not throw (the JVM would terminate), so the comparator stores [body]'s
 * result or exception in [Trampoline.pending] and returns normally; it is rethrown here.
 */
@PublishedApi
internal actual fun <R> runOnPinnedCarrier(body: () -> R): R {
    val slot = Trampoline.Pending(body)
    val previous = Trampoline.pending.get()
    Trampoline.pending.set(slot)
    try {
        // invokeExact: (long key, long base, long nmemb, long size, long compar) -> long
        @Suppress("UNUSED_VARIABLE")
        val found = Trampoline.bsearch.invokeExact(
            Trampoline.element, Trampoline.element, 1L, 8L, Trampoline.comparator,
        ) as Long
    } finally {
        Trampoline.pending.set(previous)
    }
    slot.failure?.let { throw it }
    check(slot.ran) { "the pinned trampoline did not run its body (bsearch never called the comparator)" }
    @Suppress("UNCHECKED_CAST")
    return slot.result as R
}

/** The pinning trampoline's fixed parts, created once per process. */
internal object Trampoline {
    class Pending(val body: () -> Any?) {
        var result: Any? = null
        var failure: Throwable? = null
        var ran = false
    }

    /** The body for the comparator to run, per (virtual) thread; a nested pin saves and restores it. */
    val pending: ThreadLocal<Pending?> = ThreadLocal()

    /** `bsearch(const void *key, const void *base, size_t nmemb, size_t size, int (*compar)(...))`. */
    val bsearch: MethodHandle by lazy {
        val long = Long::class.javaPrimitiveType!!
        Panama.findSymbol("bsearch", long, arrayOf(long, long, long, long, long))
    }

    /** One 8-byte native element, used as both the key and the one-element array. */
    val element: Long by lazy { Panama.allocatePointerSlot() }

    /** The comparator's upcall stub address: [compare] as `int (*)(const void *, const void *)`. */
    val comparator: Long by lazy {
        val method = Trampoline::class.java.methods.first { it.name == "compare" }
        Panama.createUpcallStubII_I(MethodHandles.lookup().unreflect(method))
    }

    /** Runs the pending body; never throws (an upcall must not). Returns 0: "equal", found. */
    @JvmStatic
    fun compare(@Suppress("UNUSED_PARAMETER") a: Long, @Suppress("UNUSED_PARAMETER") b: Long): Int {
        val slot = pending.get() ?: return 0
        if (slot.ran) return 0
        slot.ran = true
        try {
            slot.result = slot.body()
        } catch (t: Throwable) {
            slot.failure = t
        }
        return 0
    }
}
