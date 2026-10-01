@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package python.multiplatform.ffi.upcall

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar

/**
 * A bare `pthread_create`, not anything Kotlin-level (`Worker`, a dispatcher, ...).
 *
 * That is deliberate. `docs/design/upcall-async-design.md` §7.1 and §10.6 leave one question open on
 * every target but desktop: does async delivery work when the completing thread is one the
 * Kotlin/Native runtime has never attached to on its own? A `Worker` launders that question --
 * it is Kotlin/Native's own thread machinery, already known to interoperate with the runtime. A
 * raw POSIX thread, entered only through a [staticCFunction] trampoline the way CPython's own
 * `PyMethodDef` slots are, is the same shape `CycleCollectionTest`'s
 * `testDeallocOnAThreadCPythonCreated` already measured working for `tp_dealloc` -- this is that
 * same shape, for the async delivery path instead.
 */
internal class NativeThread(block: () -> Unit) {
    private val handle = nativeHeap.alloc<pthread_tVar>()
    private var launched = false
    private val ref = StableRef.create(block)

    fun start() {
        val rc = pthread_create(handle.ptr, null, staticCFunction(::runAndDispose), ref.asCPointer())
        if (rc != 0) {
            ref.dispose()
            error("pthread_create failed with $rc")
        }
        launched = true
    }

    fun join() {
        if (launched) pthread_join(handle.value, null)
        nativeHeap.free(handle)
    }
}

private fun runAndDispose(arg: COpaquePointer?): COpaquePointer? {
    val ref = arg!!.asStableRef<() -> Unit>()
    val block = ref.get()
    ref.dispose()
    block()
    return null
}
