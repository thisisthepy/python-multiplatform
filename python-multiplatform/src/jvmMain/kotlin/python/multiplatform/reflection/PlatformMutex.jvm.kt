package python.multiplatform.reflection

import java.util.concurrent.locks.ReentrantLock

internal actual class PlatformMutex actual constructor() {
    private val lock = ReentrantLock()

    actual fun lock() = lock.lock()

    actual fun unlock() = lock.unlock()
}
