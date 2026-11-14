package python.multiplatform.env

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.readlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun executableDirectory(): String? = memScoped {
    val buf = allocArray<ByteVar>(PATH_MAX)
    val len = readlink("/proc/self/exe", buf, (PATH_MAX - 1).convert())
    if (len <= 0) return null
    buf[len.toInt()] = 0
    val path = buf.toKString()
    path.substringBeforeLast('/', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
}
