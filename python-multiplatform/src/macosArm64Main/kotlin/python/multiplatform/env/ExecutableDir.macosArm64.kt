package python.multiplatform.env

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.posix.realpath

@OptIn(ExperimentalForeignApi::class)
internal actual fun executableDirectory(): String? {
    val raw = NSBundle.mainBundle.executablePath
        ?: (NSProcessInfo.processInfo.arguments.firstOrNull() as? String)
        ?: return null
    val resolved = realpath(raw, null)
    val path = if (resolved != null) {
        try {
            resolved.toKString()
        } finally {
            nativeHeap.free(resolved)
        }
    } else {
        raw
    }
    return path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
}
