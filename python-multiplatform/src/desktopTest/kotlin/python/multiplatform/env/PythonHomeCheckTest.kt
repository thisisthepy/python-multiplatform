package python.multiplatform.env

import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the exact diagnosis [PythonHomeCheck] produces for the misconfigurations reproduced
 * directly against this JVM (see the reproduction this test's history records, and ROADMAP
 * 2026-08-13 "PYTHONHOME on an external volume hangs the app instead of failing it" for the case
 * this check cannot catch). The message is the real product here -- see [PythonHomeCheck]'s doc
 * comment -- so these assertions are on its exact wording, not just "it throws".
 */
class PythonHomeCheckTest {

    private val scratchDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        scratchDirs.forEach { it.setReadable(true); it.deleteRecursively() }
        scratchDirs.clear()
    }

    private fun newScratchDir(): File =
        Files.createTempDirectory("python-home-check-test").toFile().also { scratchDirs += it }

    @Test
    fun realPythonHomeIsAccepted() {
        val pythonHome = System.getenv("PYTHONHOME")
        assertTrue(pythonHome != null, "PYTHONHOME is unset for desktopTest; nothing to check against")
        assertNull(PythonHomeCheck.diagnose(pythonHome), "the real PYTHONHOME this test binary uses was rejected")
        PythonHomeCheck.verifyOrThrow(pythonHome)
    }

    @Test
    fun nonexistentPathIsDiagnosedAsMissing() {
        val missing = File(newScratchDir(), "does-not-exist").absolutePath
        assertEquals("does not exist or is not readable.", PythonHomeCheck.diagnose(missing))
    }

    @Test
    fun nonexistentPathThrowsWithPathAndVersionInMessage() {
        val missing = File(newScratchDir(), "does-not-exist").absolutePath
        val thrown = assertFailsWith<IllegalStateException> { PythonHomeCheck.verifyOrThrow(missing) }
        assertTrue(thrown.message!!.contains(missing), "expected the bad path in the message, got: ${thrown.message}")
        assertTrue(
            thrown.message!!.contains("does not exist or is not readable."),
            "expected the 'missing' diagnosis in the message, got: ${thrown.message}"
        )
        assertTrue(
            thrown.message!!.contains(Versions.currentVersion.compactVersionString),
            "expected the required CPython version named in the message, got: ${thrown.message}"
        )
    }

    @Test
    fun existingDirectoryWithoutStdlibIsDiagnosedAsIncomplete() {
        // A real, readable directory that is not a Python prefix -- the shape a relocated install
        // or a typo'd sibling directory produces, as opposed to a path that is not there at all.
        val emptyHome = newScratchDir()
        assertEquals(
            "exists but has no readable standard library under it.",
            PythonHomeCheck.diagnose(emptyHome.absolutePath)
        )
    }

    @Test
    fun existingDirectoryWithoutStdlibThrowsWithThatDiagnosis() {
        val emptyHome = newScratchDir()
        val thrown = assertFailsWith<IllegalStateException> {
            PythonHomeCheck.verifyOrThrow(emptyHome.absolutePath)
        }
        assertTrue(
            thrown.message!!.contains("exists but has no readable standard library under it."),
            "expected the 'incomplete' diagnosis in the message, got: ${thrown.message}"
        )
    }

    @Test
    fun unreadableStdlibDirectoryIsDiagnosedAsIncomplete() {
        // Reproduces the shape of ROADMAP's chmod-000 case: the directory is there but this
        // process cannot read it, which is a `Py_FatalError` abort on desktop (measured directly)
        // and the case that, on iOS, can present as the hang this check exists to guard against.
        // `home` itself stays untouched and readable, so this lands on the same "no stdlib found"
        // diagnosis as a prefix that never had one -- this check does not walk intermediate
        // directories to tell "missing" apart from "blocked", only whether the marker resolves.
        if (currentPlatform.os == OSType.Windows) return // POSIX permission bits don't apply
        val home = newScratchDir()
        val tag = Versions.currentVersion.taggedVersionString
        val stdlib = File(home, "lib/python$tag").apply { mkdirs() }
        File(stdlib, "os.py").writeText("# stand-in for the real module\n")
        assertNull(PythonHomeCheck.diagnose(home.absolutePath), "expected the readable stdlib to be accepted first")

        // Both bits, not just read: a directory that keeps its execute (search) bit still lets
        // the kernel resolve a file inside it by exact name, so `os.py` alone would stay
        // statable. `chmod 000` (both stripped) is what actually blocks path resolution, and it
        // is what the desktop reproduction behind this test used.
        stdlib.setReadable(false, false)
        stdlib.setExecutable(false, false)
        try {
            assertEquals(
                "exists but has no readable standard library under it.",
                PythonHomeCheck.diagnose(home.absolutePath)
            )
        } finally {
            stdlib.setExecutable(true, false)
            stdlib.setReadable(true, false)
        }
    }
}
