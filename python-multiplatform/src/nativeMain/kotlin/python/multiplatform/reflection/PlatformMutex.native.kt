package python.multiplatform.reflection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * A default (non-recursive) POSIX mutex, the one primitive iOS and androidNative both provide.
 *
 * The `pthread_mutex_t` lives in [nativeHeap] and is never freed: the only user is the
 * [HandleTable] singleton, which lives for the life of the process, so there is nothing to
 * release it for.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class PlatformMutex actual constructor() {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>()

    init {
        pthread_mutex_init(mutex.ptr, null)
    }

    actual fun lock() {
        pthread_mutex_lock(mutex.ptr)
    }

    actual fun unlock() {
        pthread_mutex_unlock(mutex.ptr)
    }
}
