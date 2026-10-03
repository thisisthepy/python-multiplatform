package python.multiplatform.gradle

import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Issue #74. Gradle's `tarTree` extracts the archive's `lib/libpython3.14.so -> libpython3.14.so.1.0`
 * as a 0-byte file, which the staged prefix, the packaged `python-multiplatform-home` and the
 * desktop jar then all carried. Expected red before the fix: the extractor did not exist; with
 * `tarTree` the staged link is empty.
 */
class StagedPrefixLinksTest {

    private fun entry(out: ByteArrayOutputStream, name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "") {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "0000755\u0000")
        put(124, String.format("%011o\u0000", data.size))
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar\u000000")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        put(148, String.format("%06o\u0000 ", h.sumOf { it.toInt() and 0xff }))
        out.write(h)
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }

    private fun fakeArchive(dir: File, payload: ByteArray): File {
        val tar = ByteArrayOutputStream()
        entry(tar, "python/", '5')
        entry(tar, "python/lib/", '5')
        entry(tar, "python/lib/libpython3.14.so.1.0", '0', payload)
        entry(tar, "python/lib/libpython3.14.so", '2', link = "libpython3.14.so.1.0")
        entry(tar, "python/lib/libpython3.so", '2', link = "libpython3.14.so") // link to a link
        tar.write(ByteArray(1024))
        val f = File(dir, "fake.tar.gz")
        GZIPOutputStream(f.outputStream()).use { it.write(tar.toByteArray()) }
        return f
    }

    @Test
    fun symlinksBecomeCopiesOfTheirTargets() {
        val work = Files.createTempDirectory(File("../.tmp").apply { mkdirs() }.toPath(), "links").toFile()
        try {
            val payload = ByteArray(5000) { (it % 251).toByte() }
            val root = File(work, "out")
            extractTarGzMaterialisingLinks(fakeArchive(work, payload), root)

            val real = File(root, "python/lib/libpython3.14.so.1.0")
            val link = File(root, "python/lib/libpython3.14.so")
            val chained = File(root, "python/lib/libpython3.so")
            assertContentEquals(payload, real.readBytes())
            assertTrue(link.length() > 0, "staged symlink is empty")
            assertContentEquals(real.readBytes(), link.readBytes())
            assertContentEquals(real.readBytes(), chained.readBytes())
            assertTrue(!Files.isSymbolicLink(link.toPath()))
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun theStampChangedSoOldPrefixesAreReExtracted() {
        assertTrue("links-materialised" in stagingStamp("3.14.7", "20260807", "linux-x86_64", false))
    }

    @Test
    fun aPackagedHomeWithAnEmptyLibraryFailsTheBuild() {
        val work = Files.createTempDirectory(File("../.tmp").apply { mkdirs() }.toPath(), "packaged").toFile()
        try {
            val lib = File(work, "lib").apply { mkdirs() }
            File(lib, "libpython3.14.so.1.0").writeBytes(ByteArray(10))
            requireNoEmptyLibraries(work) // clean tree passes
            File(lib, "libpython3.14.so").writeBytes(ByteArray(0))
            assertFailsWith<GradleException> { requireNoEmptyLibraries(work) }
        } finally {
            work.deleteRecursively()
        }
    }
}
