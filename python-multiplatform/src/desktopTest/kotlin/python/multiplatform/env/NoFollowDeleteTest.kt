package python.multiplatform.env

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #227: a delete must remove a symbolic link as a link, never what it points at. */
class NoFollowDeleteTest {

    private fun scratch(): File =
        Files.createTempDirectory(File("build").apply { mkdirs() }.absoluteFile.toPath(), "nofollow").toFile()

    @Test
    fun aLinkToAnOutsideDirectoryIsRemovedButItsTargetSurvives() {
        val work = scratch()
        try {
            val outside = File(work, "outside").apply { mkdirs() }
            val keep = File(outside, "keep.txt").apply { writeText("keep") }
            val victim = File(work, "victim").apply { mkdirs() }
            File(victim, "own.txt").writeText("own")
            Files.createSymbolicLink(File(victim, "escape").toPath(), outside.toPath())
            Files.createSymbolicLink(File(victim, "dangling").toPath(), File(work, "nowhere").toPath())

            deleteWithoutFollowingLinks(victim)

            assertFalse(victim.exists(), "the directory itself should be gone")
            assertTrue(keep.isFile, "the link was followed: its target's content was deleted")
        } finally {
            deleteWithoutFollowingLinks(work)
        }
    }

    @Test
    fun aMissingPathIsNotAnError() {
        deleteWithoutFollowingLinks(File(scratch(), "absent"))
    }
}
