package python.multiplatform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #551 (SPEC P-2): a host without the OS build-number command (`wmic` on Windows Server 2025
 * and current Windows 11, `lsb_release` on many Linux installs, `sw_vers` here once `PATH` is empty)
 * still initializes [currentPlatform]. A child JVM runs with an empty `PATH`, so `Runtime.exec` cannot
 * find the command.
 *
 * Red before the fix: the child died with `ExceptionInInitializerError` caused by
 * `java.io.IOException: Cannot run program "sw_vers"`, and never printed `PLATFORM_OK`.
 */
class PlatformWithoutCommandsTest {

    @Test
    fun aHostWithoutTheBuildNumberCommandStillHasAPlatform() {
        val emptyPath = Files.createTempDirectory("pmp-551-empty-path").toFile()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").path
            val builder = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), PlatformProbe::class.java.name)
                .redirectErrorStream(true)
            builder.environment()["PATH"] = emptyPath.absolutePath
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the child JVM did not exit:\n$output")
            assertEquals(0, process.exitValue(), output)
            assertTrue(output.lineSequence().any { it.startsWith("PLATFORM_OK ") }, output)
        } finally {
            emptyPath.deleteRecursively()
        }
    }
}

/** Prints `PLATFORM_OK <os>` once [currentPlatform] has initialized. */
object PlatformProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println("PLATFORM_OK ${currentPlatform.os} versionCode=${currentPlatform.versionCode}")
        System.out.flush()
        exitProcess(0)
    }
}
