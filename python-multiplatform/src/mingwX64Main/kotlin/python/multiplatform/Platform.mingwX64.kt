package python.multiplatform

/**
 * Windows Kotlin/Native desktop (SPEC P-1a). The target is x86_64 only; the Windows version is not
 * read (`RtlGetVersion` is the only call that reports it truthfully, and nothing here branches on it).
 */
object WindowsNativePlatform : Platform {
    override val os = OSType.Windows
    override val arch: String = "x86_64"
    override val version: String = "unknown"
    override val versionCode: Int? = null
    override val platformType = PlatformType.Native
    override val platformVersion: String? = null

    override fun toString(): String = name
}

actual val currentPlatform: Platform = WindowsNativePlatform
