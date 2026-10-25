package python.multiplatform.reflection

/** wasmJs is single-threaded, so there is nothing to exclude. */
internal actual class PlatformMutex actual constructor() {
    actual fun lock() {}

    actual fun unlock() {}
}
