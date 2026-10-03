package python.native.ffi

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #74. The desktop jar once carried a 0-byte `lib/linux-x86_64/libpython3.14.so` (a symlink in
 * the python-build-standalone archive, extracted by Gradle as an empty file) next to the real
 * library under a name nothing asked for. Expected red before the fix: the zero-length entry.
 */
class DesktopJarLibrariesTest {
    private fun jar(): ZipFile {
        val path = System.getProperty("pm.desktopJar")
        assertNotNull(path, "pm.desktopJar is not set; desktopTest must depend on desktopJar")
        assertTrue(File(path).isFile, "desktop jar not built: $path")
        return ZipFile(path)
    }

    @Test
    fun noLibraryEntryIsEmpty() {
        jar().use { zip ->
            val libs = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("lib/") }.toList()
            assertTrue(libs.isNotEmpty(), "the desktop jar has no lib/** entries")
            val empty = libs.filter { it.size == 0L }.map { it.name }
            assertTrue(empty.isEmpty(), "0-byte library entries (symlink stubs?): $empty")
        }
    }

    @Test
    fun linuxCarriesTheRealLibraryUnderTheLoaderName() {
        jar().use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            if (names.none { it.startsWith("lib/linux-x86_64/") }) return
            val py = names.filter { it.startsWith("lib/linux-x86_64/libpython3.") && it.endsWith(".so") && !it.endsWith("libpython3.so") }
            assertEquals(1, py.size, "expected exactly one libpython3.<minor>[t].so, got $py")
            assertTrue(names.none { Regex("""\.so\.\d""").containsMatchIn(it) }, "versioned .so.N.M names must not ship: $names")
        }
    }

    /**
     * Issue #86. python-build-standalone's plain `install_only` linux library carries debug info
     * (252 MB); the `install_only_stripped` flavour does not. Expected red before the switch: the
     * `.debug_info` section of `lib/linux-x86_64/libpython3.14.so`.
     */
    @Test
    fun linuxLibrariesHaveNoDebugInfo() {
        jar().use { zip ->
            val libs = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/linux-") && Regex("""libpython.*\.so$""").containsMatchIn(it.name) }
                .toList()
            val offenders = libs.filter { e -> elfSectionNames(zip.getInputStream(e).use { it.readBytes() }).any { it.startsWith(".debug_info") } }
                .map { "${it.name} (${it.size} bytes)" }
            assertTrue(offenders.isEmpty(), "linux libpython with a .debug_info section: $offenders")
        }
    }

    @Test
    fun elfParserSeesDebugInfoInItsOwnFixture() {
        // Guards the parser: a check that cannot find a section proves nothing.
        val names = elfSectionNames(buildElf64(listOf(".text", ".debug_info")))
        assertTrue(".debug_info" in names, "parsed $names")
        assertTrue(".debug_info" !in elfSectionNames(buildElf64(listOf(".text"))))
    }

    /** Section names of a little-endian ELF64 image, read from its section header table. */
    private fun elfSectionNames(d: ByteArray): List<String> {
        require(d.size > 0x40 && d[0] == 0x7f.toByte() && d[1] == 'E'.code.toByte() && d[4].toInt() == 2 && d[5].toInt() == 1) { "not a little-endian ELF64" }
        val bb = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val shoff = bb.getLong(0x28).toInt()
        val shentsize = bb.getShort(0x3A).toInt() and 0xFFFF
        val shnum = bb.getShort(0x3C).toInt() and 0xFFFF
        val shstrndx = bb.getShort(0x3E).toInt() and 0xFFFF
        if (shoff == 0 || shnum == 0) return emptyList()
        val strOff = bb.getLong(shoff + shstrndx * shentsize + 0x18).toInt()
        return (0 until shnum).map { i ->
            var p = strOff + bb.getInt(shoff + i * shentsize)
            val sb = StringBuilder()
            while (d[p].toInt() != 0) sb.append(d[p++].toInt().toChar())
            sb.toString()
        }
    }

    /** A minimal ELF64 image: header, a string table, and section headers named [names] (index 0 is null). */
    private fun buildElf64(names: List<String>): ByteArray {
        val strtab = java.io.ByteArrayOutputStream().also { it.write(0) }
        val nameOffsets = names.map { n -> strtab.size().also { strtab.write(n.toByteArray()); strtab.write(0) } }
        val shstrName = strtab.size().also { strtab.write(".shstrtab".toByteArray()); strtab.write(0) }
        val str = strtab.toByteArray()
        val shnum = names.size + 2 // null + names + .shstrtab
        val shoff = 0x40 + str.size
        val bb = java.nio.ByteBuffer.allocate(shoff + shnum * 64).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        bb.putLong(0x28, shoff.toLong()); bb.putShort(0x3A, 64); bb.putShort(0x3C, shnum.toShort()); bb.putShort(0x3E, (shnum - 1).toShort())
        bb.position(0x40); bb.put(str)
        fun header(index: Int, nameOff: Int, off: Int, size: Int) {
            val b = shoff + index * 64
            bb.putInt(b, nameOff); bb.putInt(b + 4, if (index == shnum - 1) 3 else 1)
            bb.putLong(b + 0x18, off.toLong()); bb.putLong(b + 0x20, size.toLong())
        }
        names.forEachIndexed { i, _ -> header(i + 1, nameOffsets[i], 0, 0) }
        header(shnum - 1, shstrName, 0x40, str.size)
        return bb.array()
    }
}
