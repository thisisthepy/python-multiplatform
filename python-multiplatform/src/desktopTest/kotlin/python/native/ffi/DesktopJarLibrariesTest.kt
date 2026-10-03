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
}
