package python.multiplatform

import python.multiplatform.JVMPlatform.os
import java.util.Locale
import java.util.concurrent.TimeUnit


private val buildVersion = getBuildVersion(os)


object JVMPlatform: Platform {
    override val os = getOperatingSystemType()
    override val arch: String = System.getProperty("os.arch")?.lowercase() ?: "unknown"
    override val version: String = System.getProperty("os.version") ?: "unknown"
    override val versionCode: Int? = buildVersion?.toIntOrNull()
    override val versionText: String? = if (versionCode != null) "Build $versionCode" else null
    override val platformType = PlatformType.JVM
    override val platformVersion: String? = System.getProperty("java.version")

    override val name = super.name

    override fun toString(): String {
        return name
    }
}

private fun getOperatingSystemType(): OSType {
    val name = System.getProperty("os.name").lowercase(Locale.getDefault())
    return when {
        name.indexOf("win") >= 0 -> OSType.Windows
        name.indexOf("mac") >= 0 -> OSType.MacOS
        name.indexOf("linux") >= 0 -> OSType.Linux
        else -> throw IllegalStateException("Unsupported operating system: $name")
    }
}

private fun getBuildVersion(os: OSType): String? = readBuildVersion(
    os,
    when (os) {
        OSType.Windows -> arrayOf("wmic", "os", "get", "BuildNumber")
        OSType.MacOS -> arrayOf("sw_vers", "-buildVersion")
        else -> arrayOf("lsb_release", "-r")  // Ubuntu
    },
)

/**
 * Runs [command] and returns the OS build it prints (the first line on macOS, the last elsewhere),
 * or null when it cannot be read. Issue #551: `Runtime.exec` throws `IOException` for a program
 * that does not exist, and `wmic` is not installed on Windows Server 2025 or current Windows 11,
 * nor `lsb_release` on many Linux installs. The value is informational, so failing to read it
 * must not fail [JVMPlatform]'s initializer, and with it `Python3.initialize`.
 */
internal fun readBuildVersion(os: OSType, command: Array<String>): String? {
    val process = try {
        ProcessBuilder(*command).redirectErrorStream(true).start()
    } catch (e: Exception) {
        return null
    }
    return try {
        process.outputStream.close()
        // Read before waiting: a child blocked on a full pipe never exits.
        val lines = process.inputStream.bufferedReader().use { it.readText() }
            .replace(" ", "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null
        if (os == OSType.MacOS) lines.firstOrNull() else lines.lastOrNull()
    } catch (e: Exception) {
        null
    } finally {
        process.destroy()
    }
}

actual val currentPlatform: Platform = JVMPlatform
