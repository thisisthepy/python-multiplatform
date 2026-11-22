@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package python.multiplatform

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.windows.ERROR_SUCCESS
import platform.windows.HKEY_LOCAL_MACHINE
import platform.windows.RRF_RT_REG_DWORD
import platform.windows.RRF_RT_REG_SZ
import platform.windows.RegGetValueW

/**
 * Windows Kotlin/Native desktop (SPEC P-1a). The target is x86_64 only.
 *
 * [version] is the Windows release as `major.minor.build` (`10.0.26100`), read from the registry key
 * Windows itself keeps it in (`HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion`). `GetVersionEx`
 * is not used: without an application manifest it reports 6.2 on every Windows since 8.
 */
object WindowsNativePlatform : Platform {
    override val os = OSType.Windows
    override val arch: String = "x86_64"
    override val version: String = windowsRelease() ?: "unknown"
    override val versionCode: Int? = null
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String = name
}

private const val CURRENT_VERSION_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"

/** `major.minor.build` from the registry, or `null` when a value is missing. */
private fun windowsRelease(): String? = memScoped {
    fun dword(name: String): UInt? {
        val value = alloc<UIntVar>()
        val size = alloc<UIntVar>().apply { this.value = 4u }
        val rc = RegGetValueW(HKEY_LOCAL_MACHINE, CURRENT_VERSION_KEY, name, RRF_RT_REG_DWORD.toUInt(), null, value.ptr, size.ptr)
        return if (rc == ERROR_SUCCESS) value.value else null
    }

    fun string(name: String): String? {
        val chars = 64
        val buffer = allocArray<UShortVar>(chars)
        val size = alloc<UIntVar>().apply { this.value = (chars * 2).toUInt() }
        val rc = RegGetValueW(HKEY_LOCAL_MACHINE, CURRENT_VERSION_KEY, name, RRF_RT_REG_SZ.toUInt(), null, buffer, size.ptr)
        return if (rc == ERROR_SUCCESS) buffer.toKString() else null
    }

    val major = dword("CurrentMajorVersionNumber") ?: return@memScoped null
    val minor = dword("CurrentMinorVersionNumber") ?: return@memScoped null
    val build = string("CurrentBuild") ?: return@memScoped null
    "$major.$minor.$build"
}

actual val currentPlatform: Platform = WindowsNativePlatform
