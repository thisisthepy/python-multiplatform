package python.multiplatform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert

/** glibc's `pthread_t` is a plain unsigned integral typedef (`unsigned long`), not a pointer. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadId(): Long =
    platform.posix.pthread_self().convert()
