package python.multiplatform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.uname
import platform.posix.utsname

/**
 * The Linux Kotlin/Native (`linuxX64`) view of the running host.
 */
object LinuxNativePlatform : Platform {
    override val os = OSType.Linux
    override val arch: String = machineName()
    override val version: String = kernelRelease()
    override val versionCode: Int? = null
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String = name
}

@OptIn(ExperimentalForeignApi::class)
private fun machineName(): String = memScoped {
    val info = alloc<utsname>()
    if (uname(info.ptr) != 0) return "unknown"
    info.machine.toKString().ifEmpty { "unknown" }
}

@OptIn(ExperimentalForeignApi::class)
private fun kernelRelease(): String = memScoped {
    val info = alloc<utsname>()
    if (uname(info.ptr) != 0) return "unknown"
    info.release.toKString().ifEmpty { "unknown" }
}

actual val currentPlatform: Platform = LinuxNativePlatform
