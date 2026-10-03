package python.multiplatform

import kotlinx.cinterop.*
import platform.UIKit.UIDevice
import platform.Foundation.NSProcessInfo
import platform.posix.uname
import platform.posix.utsname

/**
 * The iOS view of the running device.
 *
 * `versionCode` used to be produced by mapping every character of
 * `NSProcessInfo.operatingSystemVersionString` to its code point and concatenating the digits, then
 * calling `toIntOrNull()` on the result. On this simulator that string is
 * `"Version 26.2 (Build 23C54)"`, so the intermediate was a 60-digit number; `toIntOrNull()`
 * returned null for it, as it would for any string that method could ever produce. `versionText`
 * was then the unconditional `"Build $versionCode"`, which interpolated the null instead of
 * propagating it -- so `Platform.name` rendered `iOS 26.2 (Build null, arm64) / Native`, and the
 * literal word "null" reached anything that printed a platform banner.
 *
 * The fields now line up with the other implementations, which split the OS release into a
 * human-readable string and a comparable integer:
 *
 * | field         | `androidMain` (ART)             | `artMain` (androidNative)          | here (iOS)                              |
 * |---------------|---------------------------------|------------------------------------|-----------------------------------------|
 * | `version`     | `Build.VERSION.RELEASE`         | `ro.build.version.release`         | `UIDevice.systemVersion`                |
 * | `versionCode` | `Build.VERSION.SDK_INT`         | `android_get_device_api_level(3)`  | `operatingSystemVersion`, Apple-encoded |
 * | `versionText` | `"SDK $versionCode"`            | `"SDK $versionCode"`               | `"SDK $versionCode"`                    |
 *
 * ### Why the version, and not the build identifier
 *
 * The old field name (`getBuildVersion`) and label ("Build") aimed at the OS *build*, which on
 * Apple platforms is alphanumeric by construction -- `23C54` here, `25F80` on the host -- so no
 * reading of it can ever be an `Int`. That is the same reason `Platform.desktop.kt` reports a null
 * `versionCode` on macOS: it runs `sw_vers -buildVersion` and `toIntOrNull()` declines the answer.
 * Chasing the build number on iOS could therefore only ever reproduce the null this replaces.
 *
 * What Android's `versionCode` supplies is not a build number but a *monotonic integer naming the
 * OS release*, the one availability decisions compare against. iOS has that too: it is the encoding
 * Apple's own availability macros use, `major * 10000 + minor * 100 + patch`, so that `__IPHONE_26_2`
 * is `260200` -- and it is what `@available` and `-mios-version-min` are checked against. Reading it
 * from `NSProcessInfo.operatingSystemVersion`, a documented struct, also avoids parsing
 * `operatingSystemVersionString`, whose format Apple explicitly documents as unsuitable for parsing.
 *
 * `versionText` is spelled the same as both Android implementations because it labels the same kind
 * of number: the release-gating integer, as opposed to desktop's `"Build"`, which really is an OS
 * build number there.
 *
 * ### Simulator and device
 *
 * `operatingSystemVersion` is read from the Foundation the process is linked against, so on the
 * simulator it reports the *simulated* runtime rather than the host. Measured here: the host is
 * macOS 26.5.1 (build 25F80) while the test process read 26.2.0 / `"Version 26.2 (Build 23C54)"`,
 * which is exactly the installed `iOS 26.2 (26.2 - 23C54)` simulator runtime. Device and simulator
 * therefore agree in kind -- both name the iOS release the code is running on -- and neither can
 * report null, so `versionCode` is non-null here as it is on androidNative.
 *
 * `platformVersion` stays null: there is no language runtime under this code.
 */
object IOSPlatform: Platform {
    override val os = OSType.IOS  // UIDevice.currentDevice.systemName()
    override val arch: String = getDeviceArch()
    override val version: String = UIDevice.currentDevice.systemVersion
    override val versionCode: Int = getOSVersionCode()
    override val versionText = "SDK $versionCode"
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String {
        return name
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun getDeviceArch(): String {
    memScoped {
        val systemInfo = alloc<utsname>()
        if (uname(systemInfo.ptr) != 0) {
            throw RuntimeException("Failed to get device arch info")
        }
        return systemInfo.machine.toKString()
    }
}

/**
 * The running iOS release as the integer Apple's availability macros use:
 * `major * 10000 + minor * 100 + patch`, so iOS 26.2 reads 260200.
 *
 * `NSOperatingSystemVersion`'s members are `NSInteger`, i.e. `Long` on every 64-bit target this
 * builds for; the narrowing is safe because the encoded value of any shipped release is far below
 * `Int.MAX_VALUE` (it would take iOS 214749 to overflow).
 */
@OptIn(ExperimentalForeignApi::class)
private fun getOSVersionCode(): Int =
    NSProcessInfo.processInfo.operatingSystemVersion().useContents {
        majorVersion.toInt() * 10_000 + minorVersion.toInt() * 100 + patchVersion.toInt()
    }

actual val currentPlatform: Platform = IOSPlatform
