package python.multiplatform.ref

import python.native.ffi.NativePointer
import java.lang.ref.Cleaner

actual interface PlatformCleaner : AutoCloseable {
    actual override fun close()
}

private val SHARED_CLEANER: Cleaner = Cleaner.create()

actual fun registerCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
): PlatformCleaner {
    return DesktopCleaner(pointer, closeAction)
}

private class DesktopCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
) : PlatformCleaner {
    private val cleanable: Cleaner.Cleanable = SHARED_CLEANER.register(this, CleanupAction(pointer, closeAction))

    override fun close() {
        cleanable.clean()
    }
}

private class CleanupAction(
    private val pointer: NativePointer,
    private val action: (NativePointer) -> Unit
) : Runnable {
    override fun run() {
        action(pointer)
    }
}
