package python.multiplatform.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #47: every acquired CPython must be the version `pythonVersion` names. iOS shipped 3.14.6
 * (BeeWare `3.14-b10`) while every other target shipped 3.14.7, and nothing noticed.
 */
class AcquiredHeaderVersionTest {

    private fun patchlevel(version: String) = """
        /* Version parsed out into numeric values */
        #define PY_MAJOR_VERSION        3
        #define PY_VERSION              "$version"
        #define PY_VERSION_HEX 0x030e07f0
    """.trimIndent()

    @Test
    fun parsesPyVersionOutOfPatchlevelH() {
        assertEquals("3.14.7", AcquiredHeaderVersion.parse(patchlevel("3.14.7")))
        assertEquals("3.15.0rc1", AcquiredHeaderVersion.parse(patchlevel("3.15.0rc1")))
    }

    @Test
    fun aHeaderWithoutPyVersionIsAnError() {
        assertFailsWith<IllegalArgumentException> { AcquiredHeaderVersion.parse("#define PY_MAJOR_VERSION 3") }
    }

    @Test
    fun matchingTreePassesAndMismatchNamesTheTargetAndBothVersions() {
        val root = kotlin.io.path.createTempDirectory("acquired").toFile()
        try {
            for (t in CPythonIncludeLayout.allTargets) {
                val v = if (t.startsWith("ios")) "3.14.6" else "3.14.7"
                root.resolve(CPythonIncludeLayout.relativePath(t, CPythonFlavour.GIL, "3.14.7"))
                    .also { it.mkdirs() }.resolve("patchlevel.h").writeText(patchlevel(v))
            }
            val failures = AcquiredHeaderVersion.mismatches(root, "3.14.7")
            assertEquals(CPythonIncludeLayout.iosTargets.toSet(), failures.keys)
            assertTrue(failures.getValue("ios-arm64").contains("3.14.6"))
            val e = assertFailsWith<IllegalStateException> { AcquiredHeaderVersion.requireAll(root, "3.14.7") }
            assertTrue(e.message!!.contains("ios-arm64") && e.message!!.contains("3.14.6") && e.message!!.contains("3.14.7"))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The real acquired trees (`python-multiplatform/build/python-standalone/extracted/<version>/`),
     * as extracted by `downloadAllPythonBuilds`. Skipped only when nothing was acquired at all; a
     * target that is present must match, and iOS must be present once anything is.
     */
    @Test
    fun everyAcquiredTargetReportsThePinnedPythonVersion() {
        val gradleProps = File("../gradle.properties").readLines()
        val pinned = gradleProps.first { it.startsWith("pythonVersion=") }.substringAfter('=').trim()
        val root = File("../python-multiplatform/build/python-standalone/extracted/$pinned")
        if (!root.isDirectory) {
            println("SKIPPED: nothing acquired under $root; run :python-multiplatform:downloadAllPythonBuilds")
            return
        }
        val present = CPythonIncludeLayout.allTargets.filter { AcquiredHeaderVersion.patchlevelFile(root, it, pinned).isFile }
        assertTrue("ios-arm64" in present, "iOS headers missing under $root")
        val failures = AcquiredHeaderVersion.mismatches(root, pinned)
        assertTrue(failures.isEmpty(), "acquired headers disagree with pythonVersion=$pinned: $failures")
    }
}
