package python.multiplatform.ffi

/** `Thread.isVirtual` exists from JDK 21. Computed once; see [checkGILThread]. */
private val runtimeHasVirtualThreads: Boolean = Runtime.version().feature() >= 21

/**
 * Refuses to attach a JVM virtual thread (issue #161).
 *
 * `PyGILState_Ensure` binds the CPython thread state to the OS carrier thread, while the nesting
 * depth of [withGIL] lives in a `java.lang.ThreadLocal`, which belongs to the virtual thread. A
 * virtual thread that parks inside the scope unmounts, another virtual thread then runs on the same
 * carrier and sees depth 0 (so it calls Ensure again), and the first one may resume and Release on a
 * different carrier. None of that can be made correct without pinning, so the outermost scope fails
 * before Ensure instead.
 *
 * `Thread.isVirtual` is a JDK 21 API while the module targets JVM 17. The JVM links a call site only
 * when it is first executed, so the version flag is tested first and a JDK 17 to 20 runtime never
 * reaches the call. It is a direct call on purpose: no lookup by name, no reflection.
 */
@PublishedApi
internal actual fun checkGILThread() {
    if (runtimeHasVirtualThreads && Thread.currentThread().isVirtual) {
        throw IllegalStateException(
            "withGIL cannot be entered from a virtual thread: the CPython GIL thread state is bound to " +
                "the OS carrier thread, and a virtual thread can unmount and resume on another carrier " +
                "inside the scope. Run Python from a platform thread (a fixed thread pool, or " +
                "Dispatchers.IO-backed platform threads), not Executors.newVirtualThreadPerTaskExecutor " +
                "or Thread.ofVirtual."
        )
    }
}
