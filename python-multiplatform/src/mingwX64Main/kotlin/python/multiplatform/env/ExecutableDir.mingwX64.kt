package python.multiplatform.env

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import platform.windows.GetModuleFileNameW

/**
 * The directory of the running executable (SPEC P-1a, L-9c), with `/` separators: Windows accepts
 * them, and `DesktopNativePythonHome` splits on `/` on every desktop native target.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun executableDirectory(): String? = memScoped {
    val capacity = 32_768
    val buffer = allocArray<UShortVar>(capacity)
    val length = GetModuleFileNameW(null, buffer, capacity.toUInt()).toInt()
    if (length <= 0 || length >= capacity) return null
    val path = buffer.toKStringFromUtf16().replace('\\', '/')
    path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
}
