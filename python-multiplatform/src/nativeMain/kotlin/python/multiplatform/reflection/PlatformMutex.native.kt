package python.multiplatform.reflection

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A non-recursive spin lock on one atomic word, for iOS and androidNative alike (#329).
 *
 * Not a `pthread_mutex_t`: `pthread_mutex_init` is not part of the POSIX surface Kotlin/Native
 * commonizes across Apple and Linux, so `nativeMain`'s shared metadata compile (the one publishing
 * runs) could not resolve it even though every per-target compile did. A spin lock needs only the
 * common atomics. That is enough here because [PlatformMutex]'s contract keeps every critical section
 * to a few array and counter operations, never a call into Python, user code or a cleaner, so a
 * waiter spins for a bounded, short time and a free-threaded stop-the-world pause cannot wait on it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal actual class PlatformMutex actual constructor() {
    private val held = AtomicInt(0)

    actual fun lock() {
        while (!held.compareAndSet(0, 1)) {
            // Spin: the holder is inside a section of a few array operations.
        }
    }

    actual fun unlock() {
        held.store(0)
    }
}
