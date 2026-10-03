package python.multiplatform.ref

import python.native.ffi.NativePointer


expect interface PlatformCleaner : AutoCloseable {
    override fun close()
}

expect fun registerCleaner(pointer: NativePointer, closeAction: (NativePointer) -> Unit): PlatformCleaner

abstract class PyAutoCloseable(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
) : AutoCloseable {
    private val platformCleaner = registerCleaner(pointer, closeAction)

    override fun close() {
        platformCleaner.close()
    }
}
