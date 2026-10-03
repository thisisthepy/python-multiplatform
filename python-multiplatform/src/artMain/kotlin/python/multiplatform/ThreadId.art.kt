package python.multiplatform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert

/** Bionic's `pthread_t` is a plain unsigned integral typedef, not a pointer. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadId(): Long =
    platform.posix.pthread_self().convert()
