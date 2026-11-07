package python.multiplatform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import platform.Foundation.NSProcessInfo
import platform.posix.uname
import platform.posix.utsname

/**
 * The macOS Kotlin/Native (`macosArm64`) view of the running host.
 */
object MacosNativePlatform : Platform {
    override val os = OSType.MacOS
    override val arch: String = getDeviceArch()
    override val version: String = getOSVersionString()
    override val versionCode: Int = getOSVersionCode()
    override val versionText = "SDK $versionCode"
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String = name
}

@OptIn(ExperimentalForeignApi::class)
private fun getDeviceArch(): String = memScoped {
    val systemInfo = alloc<utsname>()
    if (uname(systemInfo.ptr) != 0) {
        throw RuntimeException("Failed to get device arch info")
    }
    systemInfo.machine.toKString()
}

@OptIn(ExperimentalForeignApi::class)
private fun getOSVersionString(): String =
    NSProcessInfo.processInfo.operatingSystemVersion().useContents {
        "$majorVersion.$minorVersion.$patchVersion"
    }

@OptIn(ExperimentalForeignApi::class)
private fun getOSVersionCode(): Int =
    NSProcessInfo.processInfo.operatingSystemVersion().useContents {
        majorVersion.toInt() * 10_000 + minorVersion.toInt() * 100 + patchVersion.toInt()
    }

actual val currentPlatform: Platform = MacosNativePlatform
