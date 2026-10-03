package python.native.ffi

import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Issue #74. `extractLibrary` used to write `File(".", name)`: into the user's working directory.
 * Expected red before the fix: the file appears in the cwd and nothing is cached (extractLibrary
 * also had a different signature, so this fails to compile until the cache parameter exists).
 */
class ExtractLibraryCacheTest {
    private fun libName(): String {
        val tagged = Versions.currentVersion.taggedVersionString
        return if (currentPlatform.os == OSType.Windows) "python" + tagged.replace(".", "") else "python$tagged"
    }

    @Test
    fun extractsIntoTheCacheNotTheWorkingDirectory() {
        val cache = Files.createTempDirectory("pm-cache").toFile()
        val cwdCopy = File(".", System.mapLibraryName(libName()))
        val cwdBefore = cwdCopy.exists()
        try {
            val out = manager.extractLibrary(libName(), cacheRoot = cache)
            assertTrue(out.isFile && out.length() > 0, "extracted file missing or empty: $out")
            assertTrue(out.canonicalPath.startsWith(cache.canonicalPath), "$out is outside the cache $cache")
            assertTrue(out.canonicalPath.contains(Versions.currentVersion.versionString), "no per-version directory: $out")
            assertEquals(cwdBefore, cwdCopy.exists(), "extractLibrary touched the working directory")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun reusesAnIdenticalCachedCopy() {
        val cache = Files.createTempDirectory("pm-cache").toFile()
        try {
            val first = manager.extractLibrary(libName(), cacheRoot = cache)
            val stamp = first.lastModified() - 100_000
            assertTrue(first.setLastModified(stamp))
            val second = manager.extractLibrary(libName(), cacheRoot = cache)
            assertEquals(first.canonicalPath, second.canonicalPath)
            assertEquals(stamp, second.lastModified(), "an identical cached library was rewritten")
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun replacesACachedCopyOfTheWrongSize() {
        val cache = Files.createTempDirectory("pm-cache").toFile()
        try {
            val first = manager.extractLibrary(libName(), cacheRoot = cache)
            val size = first.length()
            first.writeBytes(ByteArray(7))
            val second = manager.extractLibrary(libName(), cacheRoot = cache)
            assertEquals(size, second.length())
            assertNotEquals(7L, second.length())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun cacheRootIsNotTheWorkingDirectory() {
        val root = manager.cacheRoot().canonicalFile
        assertNotEquals(File(".").canonicalFile, root)
        assertTrue(root.name.startsWith("python-multiplatform"), root.toString())
    }
}
