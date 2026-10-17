package python.multiplatform.env

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Deletes [path] and everything under it without following symbolic links (issue #227). Shared by the Android and desktop payload staging (jvmMain; Android minSdk is 26, so java.nio.file is available).
 *
 * Kotlin's `File.deleteRecursively` descends into a directory symlink and deletes what it points
 * at. `Files.walkFileTree` without `FOLLOW_LINKS` reports a link as a file-like entry, so the link
 * itself is removed and its target is left alone. A missing path is not an error.
 */
internal fun deleteWithoutFollowingLinks(path: File) {
    val start = path.toPath()
    if (!Files.exists(start, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(start, object : SimpleFileVisitor<java.nio.file.Path>() {
        override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: java.nio.file.Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
