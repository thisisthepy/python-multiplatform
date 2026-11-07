package python.multiplatform

import kotlinx.cinterop.ExperimentalForeignApi

/** Darwin's `pthread_self()` returns a pointer; its bit pattern is the thread identity. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadId(): Long =
    platform.posix.pthread_self()?.rawValue?.toLong() ?: 0L
