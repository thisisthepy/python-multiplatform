package python.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #551 (SPEC P-2). `JVMPlatform` reads the OS build number by running `wmic` (Windows),
 * `sw_vers` (macOS) or `lsb_release` (Linux). `Runtime.exec` throws `IOException` when the program
 * does not exist, rather than returning a non-zero exit, and that exception escaped the initializer.
 * `wmic` is not installed on Windows Server 2025 (GitHub's windows-latest) or on current Windows 11,
 * and `lsb_release` is missing from many Linux installs, so `currentPlatform`, and with it
 * `Python3.initialize`, failed there. The build number is informational, so a missing command must
 * mean "unknown" (null), never a failed start.
 *
 * Red before the fix: `readBuildVersion` did not exist; the code it replaces threw
 * `java.io.IOException: Cannot run program "pmp-no-such-command-551"`.
 */
class PlatformBuildVersionTest {

    /** A shell command on this host: `cmd /c` on Windows, `/bin/sh -c` elsewhere. */
    private fun shell(posix: String, windows: String): Array<String> =
        if (System.getProperty("os.name").lowercase().contains("win")) arrayOf("cmd", "/c", windows)
        else arrayOf("/bin/sh", "-c", posix)

    @Test
    fun aMissingCommandMeansUnknownNotAFailedStart() {
        assertNull(readBuildVersion(OSType.Linux, arrayOf("pmp-no-such-command-551", "-r")))
    }

    @Test
    fun aFailingCommandMeansUnknown() {
        assertNull(readBuildVersion(OSType.Linux, shell("exit 3", "exit 3")))
    }

    @Test
    fun theLastLineIsTheBuildOnLinuxAndWindows() {
        assertEquals("26100", readBuildVersion(OSType.Windows, shell("printf 'BuildNumber\\n26100\\n'", "echo BuildNumber& echo 26100")))
    }

    @Test
    fun theFirstLineIsTheBuildOnMacos() {
        assertEquals("25F71", readBuildVersion(OSType.MacOS, shell("printf '25F71\\n'", "echo 25F71")))
    }

    @Test
    fun thePlatformOfThisHostInitializes() {
        // Touches the initializer that used to throw on a host without the command.
        assertEquals(PlatformType.JVM, currentPlatform.platformType)
    }
}
