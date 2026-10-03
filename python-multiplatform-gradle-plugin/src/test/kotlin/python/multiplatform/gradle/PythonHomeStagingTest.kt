package python.multiplatform.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The decisions [PythonHomeStaging] makes, as pure functions.
 *
 * These exist because the thing they decide is not observable from this machine. ROADMAP §15e item
 * 4 is a *distribution* problem, and a distribution problem is by definition about the hosts you
 * are not sitting at: three of the four platforms staged here (`linux-x86_64`, `windows-x86_64`,
 * `macos-x86_64`) cannot be run in this repository's verification matrix at all. The URL, the
 * asset name, the prefix layout and the stamp are therefore pinned as values rather than left to
 * be discovered by a consumer on a platform nobody here can boot -- a wrong triple for Windows is
 * a 404 at *their* first build, and the only way to catch it here is to assert the string.
 */
class PythonHomeStagingTest {

    // ---- host detection ----

    @Test
    fun theHostPlatformNamesMatchTheDirectoryNamesTheLibraryAlreadyUses() {
        // These four names are not free: `manager.platformDirectory()` in `jvmMain` builds the
        // same strings to find `lib/<platform>/libpython*` inside `desktopJar`, and
        // `desktopTargets` in `python-multiplatform/build.gradle.kts` keys the download tasks by
        // them. A fifth spelling here would stage a prefix the loader cannot pair with a library.
        assertEquals("macos-aarch64", hostDesktopPlatform("Mac OS X", "aarch64"))
        assertEquals("macos-x86_64", hostDesktopPlatform("Mac OS X", "x86_64"))
        assertEquals("linux-x86_64", hostDesktopPlatform("Linux", "amd64"))
        assertEquals("windows-x86_64", hostDesktopPlatform("Windows 11", "amd64"))
    }

    @Test
    fun aarch64IsSpelledSeveralWaysBySeveralJdks() {
        // `os.arch` is whatever the JDK vendor put there. Azul and Temurin report `aarch64`;
        // some report `arm64`. Both have to land on the same prefix or an Apple Silicon consumer
        // silently stages the x86_64 tree and pairs it with the aarch64 `libpython`.
        assertEquals("macos-aarch64", hostDesktopPlatform("Mac OS X", "arm64"))
        assertEquals("linux-aarch64", hostDesktopPlatform("Linux", "arm64"))
        assertEquals("linux-aarch64", hostDesktopPlatform("Linux", "aarch64"))
    }

    @Test
    fun anUnsupportedHostIsNullRatherThanAGuess() {
        // A 32-bit or exotic host must produce a message naming what it is, not a download URL
        // that 404s. `Panama.POINTER_BYTES` already assumes 64-bit, so there is no silent
        // fallback that could work.
        assertNull(hostDesktopPlatform("Linux", "i386"))
        assertNull(hostDesktopPlatform("SunOS", "sparcv9"))
        assertNull(hostDesktopPlatform("Linux", "riscv64"))
    }

    // ---- asset naming ----

    @Test
    fun theAssetNameIsTheOneTheLibrarysOwnBuildAlreadyDownloads() {
        // Byte-for-byte the string `downloadPython_*` builds in
        // `python-multiplatform/build.gradle.kts`. If these two ever disagree, a consumer stages a
        // *different* CPython build than the `libpython` in `desktopJar` was taken from, and the
        // two can disagree about ABI while both being version 3.14.7.
        assertEquals(
            "cpython-3.14.7+20260807-aarch64-apple-darwin-install_only_stripped.tar.gz",
            pbsAssetName("3.14.7", "20260807", "macos-aarch64", freeThreaded = false),
        )
        assertEquals(
            "cpython-3.14.7+20260807-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz",
            pbsAssetName("3.14.7", "20260807", "linux-x86_64", freeThreaded = false),
        )
        assertEquals(
            "cpython-3.14.7+20260807-x86_64-pc-windows-msvc-install_only_stripped.tar.gz",
            pbsAssetName("3.14.7", "20260807", "windows-x86_64", freeThreaded = false),
        )
    }

    @Test
    fun aFreeThreadedBuildIsADifferentAssetNotAFlagOnTheSameOne() {
        // `-PpythonFreeThreaded=true` selects a different tarball upstream, and the resulting
        // prefix holds `lib/python3.14t/` rather than `lib/python3.14/`. Staging the default
        // tarball for a free-threaded consumer produces a prefix `PythonHomeCheck` rejects.
        assertEquals(
            "cpython-3.14.7+20260807-aarch64-apple-darwin-freethreaded-install_only_stripped.tar.gz",
            pbsAssetName("3.14.7", "20260807", "macos-aarch64", freeThreaded = true),
        )
    }

    @Test
    fun theUrlIsTheAstralReleaseTheRepoPins() {
        assertEquals(
            "https://github.com/astral-sh/python-build-standalone/releases/download/20260807/" +
                "cpython-3.14.7+20260807-aarch64-apple-darwin-install_only_stripped.tar.gz",
            pbsAssetUrl("20260807", "cpython-3.14.7+20260807-aarch64-apple-darwin-install_only_stripped.tar.gz"),
        )
    }

    // ---- checksum ----

    @Test
    fun theChecksumIsReadOutOfTheReleasesOwnSha256sumsFile() {
        // Upstream's format is "<hex>  <filename>", two spaces. Matching on the filename rather
        // than on a line index matters: the file lists every asset of the release, hundreds of
        // them, in no order this code should depend on.
        val sums = """
            1111111111111111111111111111111111111111111111111111111111111111  cpython-3.14.7+20260807-x86_64-pc-windows-msvc-install_only_stripped.tar.gz
            2222222222222222222222222222222222222222222222222222222222222222  cpython-3.14.7+20260807-aarch64-apple-darwin-install_only_stripped.tar.gz
        """.trimIndent()
        assertEquals(
            "2222222222222222222222222222222222222222222222222222222222222222",
            sha256Of(sums, "cpython-3.14.7+20260807-aarch64-apple-darwin-install_only_stripped.tar.gz"),
        )
    }

    @Test
    fun aFilenameThatIsOnlyASuffixOfAnotherIsNotMatched() {
        // `endsWith` is what the root build uses, and it is wrong in principle here: the
        // free-threaded asset name *contains* nothing of the default one, but
        // `...-install_only_stripped.tar.gz` is a suffix of `...-freethreaded-install_only_stripped.tar.gz`. Asking
        // for the default asset must not return the free-threaded hash, or verification fails on
        // a file that is actually intact.
        val sums = """
            3333333333333333333333333333333333333333333333333333333333333333  cpython-3.14.7+20260807-aarch64-apple-darwin-freethreaded-install_only_stripped.tar.gz
            4444444444444444444444444444444444444444444444444444444444444444  cpython-3.14.7+20260807-aarch64-apple-darwin-install_only_stripped.tar.gz
        """.trimIndent()
        assertEquals(
            "4444444444444444444444444444444444444444444444444444444444444444",
            sha256Of(sums, "cpython-3.14.7+20260807-aarch64-apple-darwin-install_only_stripped.tar.gz"),
        )
    }

    @Test
    fun anAssetMissingFromSha256sumsIsNullSoTheCallerCanRefuseRatherThanSkipVerification() {
        assertNull(sha256Of("aaaa  something-else.tar.gz", "cpython-3.14.7+20260807-x.tar.gz"))
    }

    // ---- the staged prefix ----

    @Test
    fun theMarkerIsTheOnePythonHomeCheckItselfLooksFor() {
        // `PythonHomeCheck.diagnose` accepts a prefix when `lib/python<tag>/os.py` or `Lib/os.py`
        // is readable under it. Staging asserts the *same* file rather than "the directory is not
        // empty", so a prefix this task reports as staged is one `Python3.initialize()` will
        // accept -- the two cannot drift into disagreeing about what a usable prefix is.
        assertEquals("lib/python3.14/os.py", stdlibMarkerRelativePath("3.14.7", freeThreaded = false, windows = false))
        assertEquals("lib/python3.14t/os.py", stdlibMarkerRelativePath("3.14.7", freeThreaded = true, windows = false))
        assertEquals("Lib/os.py", stdlibMarkerRelativePath("3.14.7", freeThreaded = false, windows = true))
    }

    @Test
    fun theStampNamesEveryInputThatChangesTheBytes() {
        // §15f's lesson, applied to a build-time cache instead of `filesDir`: the three
        // hand-written Android copies each probed one entry of the result and so accepted a tree
        // whose extraction had been interrupted. A stamp written after the last byte cannot be
        // true of a partial tree. It has to name the upstream release too, not just the Python
        // version -- astral re-releases the same CPython version under a new date tag, and the
        // cache directory is keyed by version alone.
        val stamp = stagingStamp("3.14.7", "20260807", "macos-aarch64", freeThreaded = false)
        assertTrue("3.14.7" in stamp, stamp)
        assertTrue("20260807" in stamp, stamp)
        assertTrue("macos-aarch64" in stamp, stamp)

        // Every input is load-bearing: changing any one of them must invalidate the cache.
        assertTrue(stamp != stagingStamp("3.14.8", "20260807", "macos-aarch64", freeThreaded = false))
        assertTrue(stamp != stagingStamp("3.14.7", "20260808", "macos-aarch64", freeThreaded = false))
        assertTrue(stamp != stagingStamp("3.14.7", "20260807", "linux-x86_64", freeThreaded = false))
        assertTrue(stamp != stagingStamp("3.14.7", "20260807", "macos-aarch64", freeThreaded = true))
    }

    // ---- when the plugin should act at all ----

    @Test
    fun aConsumerWhoAlreadySetPythonhomeKeepsTheirs() {
        // The whole point of §15c's gap is that a consumer today *has* to set `PYTHONHOME` by
        // hand. Someone who has already done that -- at a system CPython, at a conda prefix, at a
        // build they compiled -- must not have it silently replaced by a staged copy; that would
        // turn a working setup into a differently-configured one on upgrade.
        assertFalse(shouldStagePythonHome(existingPythonHome = "/opt/homebrew", stagingEnabled = true))
        assertTrue(shouldStagePythonHome(existingPythonHome = null, stagingEnabled = true))
        assertTrue(shouldStagePythonHome(existingPythonHome = "   ", stagingEnabled = true))
    }

    @Test
    fun stagingCanBeTurnedOffEntirely() {
        // An air-gapped build, or one that gets its prefix from its own packaging step, must be
        // able to opt out -- the task downloads from the network, which not every build may do.
        assertFalse(shouldStagePythonHome(existingPythonHome = null, stagingEnabled = false))
    }
}
