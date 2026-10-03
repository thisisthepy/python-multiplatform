package python.multiplatform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.__system_property_get
import platform.posix.android_get_device_api_level
import platform.posix.uname
import platform.posix.utsname


/**
 * The androidNative view of the running device.
 *
 * Every field used to be a placeholder (`"Kotlin/Native"`, `versionCode = 0`), which stopped being
 * harmless once the androidNative test binary started running on real devices: the measurement
 * header printed `Android Kotlin/Native (SDK 0, Kotlin/Native)` on an API 26 emulator and on an
 * API 36 one alike, so no measurement could be attributed to a device.
 *
 * The values are read the same way the other platforms read theirs, and are meant to line up
 * field-for-field with [AndroidPlatform] (`androidMain`), which is the same operating system seen
 * through the ART VM:
 *
 * | field         | `androidMain` (ART)                  | here (androidNative)                      |
 * |---------------|--------------------------------------|-------------------------------------------|
 * | `arch`        | `System.getProperty("os.arch")`      | `uname(2)` → `utsname.machine`            |
 * | `version`     | `Build.VERSION.RELEASE`              | `ro.build.version.release` system property |
 * | `versionCode` | `Build.VERSION.SDK_INT`              | `android_get_device_api_level(3)`         |
 * | `versionText` | `"SDK $versionCode"`                 | `"SDK $versionCode"`                      |
 *
 * Both `arch` sources report the kernel's machine name, so an arm64 device reads `aarch64` on
 * either side rather than `arm64-v8a`/`arm64`; `Platform.isArm` and `Platform.is64Bit` are written
 * against exactly that spelling. `version` is the user-visible OS release ("8.0.0", "16") on every
 * platform in this tree -- iOS uses `UIDevice.systemVersion` and desktop uses `os.version` -- so
 * the release property is the matching read, not the API level.
 *
 * `platformVersion` stays null: on ART it names the language runtime
 * (`java.specification.version`), and here there is no runtime under the code. iOS reports null for
 * the same reason.
 */
@OptIn(ExperimentalForeignApi::class)
object AndroidNativePlatform : Platform {
    override val os = OSType.Android
    override val arch: String = machineName()
    override val version: String = deviceApiRelease()
    override val versionCode: Int = deviceApiLevel()
    override val versionText = "SDK $versionCode"
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String {
        return name
    }
}

/**
 * `android_get_device_api_level()` is `platform.posix`, not `platform.android` -- Kotlin/Native's
 * posix def pulls in `<android/api-level.h>` for the android_* targets.
 *
 * Below API 29 bionic ships it as a `static __inline` that reads `ro.build.version.sdk` (see
 * `bits/get_device_api_level_inlines.h`), and above it as a real symbol. Which of the two a build
 * gets is decided by `__ANDROID_API__`, and Kotlin/Native's target triple
 * (`aarch64-unknown-linux-android`, no API suffix in `konan.properties`) leaves it at the NDK
 * minimum -- so the inline is what is compiled in, and the binary keeps loading on API 26 devices
 * where the extern symbol does not exist.
 *
 * It documents -1 on failure. That is folded into the property fallback rather than surfaced,
 * because a negative "SDK -1" in a report is no more useful than the "SDK 0" this replaced.
 */
@OptIn(ExperimentalForeignApi::class)
private fun deviceApiLevel(): Int {
    val reported = android_get_device_api_level()
    if (reported > 0) return reported
    return systemProperty("ro.build.version.sdk").toIntOrNull() ?: 0
}

@OptIn(ExperimentalForeignApi::class)
private fun deviceApiRelease(): String =
    systemProperty("ro.build.version.release").ifEmpty { "unknown" }

@OptIn(ExperimentalForeignApi::class)
private fun machineName(): String = memScoped {
    val info = alloc<utsname>()
    if (uname(info.ptr) != 0) return "unknown"
    info.machine.toKString().ifEmpty { "unknown" }
}

/**
 * `PROP_VALUE_MAX` (92) is a `#define` in `<sys/system_properties.h>`, and cinterop does not carry
 * plain object-like macros of this shape into the klib -- the constant is absent from
 * `platform.posix`, so it is spelled out here. `__system_property_get` returns the length written
 * and writes an empty string for an unset name.
 */
@OptIn(ExperimentalForeignApi::class)
private fun systemProperty(name: String): String = memScoped {
    val buffer = allocArray<ByteVar>(PROP_VALUE_MAX)
    val length = __system_property_get(name.cstr, buffer)
    if (length <= 0) "" else buffer.toKString()
}

private const val PROP_VALUE_MAX = 92

actual val currentPlatform: Platform = AndroidNativePlatform
