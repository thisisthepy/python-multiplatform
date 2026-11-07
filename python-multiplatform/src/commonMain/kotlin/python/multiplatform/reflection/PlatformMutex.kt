package python.multiplatform.reflection

/**
 * A minimal mutual-exclusion lock for the few tables that must be safe without the GIL.
 *
 * Callers hold it only for array and counter work: never across a call into Python, user code,
 * a cleaner or a callback. Not required to be re-entrant (the JVM actual is, the native one is
 * not), so a critical section must not take the same mutex again.
 *
 * Actuals: `jvmMain` (`ReentrantLock`), `nativeMain` (a POSIX mutex), `wasmJsMain` (no-op, the
 * target is single-threaded).
 */
internal expect class PlatformMutex() {
    fun lock()
    fun unlock()
}

internal inline fun <T> PlatformMutex.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
