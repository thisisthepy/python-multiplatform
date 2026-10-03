@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)
package python.multiplatform.ref

import python.native.ffi.NativePointer
import kotlin.native.ref.createCleaner
import kotlin.concurrent.AtomicInt

actual interface PlatformCleaner : AutoCloseable {
    actual override fun close()
}

actual fun registerCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
): PlatformCleaner {
    return NativePlatformCleaner(pointer, closeAction)
}

private class NativePlatformCleaner(
    private val pointer: NativePointer,
    private val closeAction: (NativePointer) -> Unit
) : PlatformCleaner {
    private val closed = AtomicInt(0)

    private val cleaner = createCleaner(CleanupState(pointer, closeAction, closed)) { state ->
        if (state.closed.compareAndSet(0, 1)) {
            state.action(state.pointer)
        }
    }

    override fun close() {
        if (closed.compareAndSet(0, 1)) {
            closeAction(pointer)
        }
    }
}

private class CleanupState(
    val pointer: NativePointer,
    val action: (NativePointer) -> Unit,
    val closed: AtomicInt
)
