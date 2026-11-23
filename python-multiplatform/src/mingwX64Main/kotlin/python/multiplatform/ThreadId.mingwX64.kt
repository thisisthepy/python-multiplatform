package python.multiplatform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.GetCurrentThreadId

/** The Win32 thread id (SPEC P-1a): what `threading.get_ident()` reports on a Windows CPython. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadId(): Long = GetCurrentThreadId().toLong()
