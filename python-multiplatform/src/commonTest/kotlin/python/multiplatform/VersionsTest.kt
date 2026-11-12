package python.multiplatform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the version-string arithmetic that names the CPython artefacts on disk.
 *
 * The interesting case is the free-threaded flavour. A free-threaded install renames *everything*
 * the runtime looks up by name, `libpython3.14t.dylib`, `lib/python3.14t/`, `bin/python3.14t`,
 * and it does not ship the un-suffixed names at all. Getting [Versions.taggedVersionString] wrong
 * therefore does not degrade gracefully: `manager.loadLibPython` throws `UnsatisfiedLinkError` for
 * a file that was downloaded and is sitting right there under a different name.
 *
 * These assertions are written against the per-platform [embedsFreeThreadedPython] rather than
 * hard-coding one flavour, because the flavour differs by platform (issue #158): desktop embeds
 * 3.15t, Android and iOS the GIL 3.15 until #328. The platform test source sets pin the literal
 * value for their platform (`DesktopFreeThreadedBuildTest`, `DesktopNativeFreeThreadedBuildTest`,
 * `AndroidGilBuildTest`, `AndroidNativeGilBuildTest`, `IosGilBuildTest`), which is what a wrong
 * actual would fail.
 */
class VersionsTest {

    @Test
    fun abiFlagsFollowsTheConfiguredFlavour() {
        assertEquals(
            if (embedsFreeThreadedPython) "t" else "",
            Versions.currentVersion.abiFlags,
            "abiFlags must be CPython's flags suffix: 't' free-threaded (PEP 703), empty otherwise"
        )
    }

    @Test
    fun taggedVersionStringNamesTheArtefactsOnDisk() {
        val v = Versions.currentVersion
        assertEquals(v.compactVersionString + v.abiFlags, v.taggedVersionString)
        if (embedsFreeThreadedPython) {
            assertTrue(
                v.taggedVersionString.endsWith("t"),
                "a free-threaded build must ask for libpython${v.compactVersionString}t, " +
                    "which is the only shared library such an install contains"
            )
        } else {
            assertEquals(v.compactVersionString, v.taggedVersionString)
        }
    }

    @Test
    fun parsesAPreReleaseVersion() {
        // 3.15.0rc1 exists in python-build-standalone 20260807 and is the first release line with
        // an `abi3t` stable ABI. Parsing must not reject it, and the patch component must not be
        // read as 0 when it is really "0rc1".
        val v = Versions.parse("3.15.0rc1")
        assertEquals("3.15", v.compactVersionString)
        assertEquals(3, v.majorVersion)
        assertEquals(15, v.minorVersion)
        assertEquals(0, v.patchVersion)
    }

    @Test
    fun compactVersionStringIsMajorMinorOnly() {
        assertEquals("3.14", Versions.parse("3.14.7").compactVersionString)
    }
}
