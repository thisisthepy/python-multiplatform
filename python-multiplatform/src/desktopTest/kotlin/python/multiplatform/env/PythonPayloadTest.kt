package python.multiplatform.env

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.native.ffi.PyUnicode_AsUTF8
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The receiving half of the boundary `toolchain`'s `49da1d8` left open: a consumer's Python payload
 * reaches the desktop jar's root as `python/`, and until this existed nothing put it on `sys.path`.
 *
 * ### Why the fixtures are built here rather than checked in
 *
 * The two shapes a classpath entry can have are exactly what this has to distinguish, and only one
 * of them can be checked in: `src/desktopTest/resources/python/` would test the *directory* case
 * and could never test the *jar* case, which is the one the shipped artifact actually has. Both are
 * therefore constructed at run time -- a directory tree, and a real `JarOutputStream` archive --
 * and put on a `URLClassLoader` the test controls.
 *
 * ### Every fixture uses its own top-level package name
 *
 * `sys.modules` caches by name for the life of the process. A second test importing `example_py`
 * would get the first test's module back without touching `sys.path` at all, and would pass with
 * the payload staging completely broken. Each test therefore names its package after itself.
 */
class PythonPayloadTest {

    private val temporaries = mutableListOf<File>()
    private val savedContextLoader: ClassLoader? = Thread.currentThread().contextClassLoader

    @AfterTest
    fun cleanUp() {
        Thread.currentThread().contextClassLoader = savedContextLoader
        temporaries.forEach { it.deleteRecursively() }
    }

    // ---------------------------------------------------------------- discovery: directory entries

    @Test
    fun aDirectoryClasspathEntryIsUsedWhereItIsWithNoExtraction() {
        val classpathRoot = payloadDirectory("payload_dir_probe")
        val loader = URLClassLoader(arrayOf(classpathRoot.toURI().toURL()), null)

        val roots = ClasspathPayload.resolveRoots(loader, cacheDirectory())

        assertEquals(
            listOf(File(classpathRoot, "python").canonicalFile),
            roots,
            "a `python/` directory that is already on the filesystem is a usable sys.path entry as " +
                "it stands; copying it anywhere would be a second copy to keep in step",
        )
    }

    @Test
    fun aClasspathWithNoPythonRootYieldsNothing() {
        val empty = newTemporaryDirectory("no-payload")
        File(empty, "notpython/thing.txt").apply { parentFile.mkdirs(); writeText("x") }
        val loader = URLClassLoader(arrayOf(empty.toURI().toURL()), null)

        assertEquals(emptyList(), ClasspathPayload.resolveRoots(loader, cacheDirectory()))
    }

    @Test
    fun anEmptyPythonRootIsNotPutOnThePath() {
        val root = newTemporaryDirectory("empty-payload")
        File(root, "python").mkdirs()
        val loader = URLClassLoader(arrayOf(root.toURI().toURL()), null)

        assertEquals(
            emptyList(),
            ClasspathPayload.resolveRoots(loader, cacheDirectory()),
            "an empty `python/` directory is what a consumer that has not pointed the DSL at a ppp " +
                "package yet produces (StagePythonBundleTask creates the destination regardless), " +
                "and a sys.path entry with nothing in it is only a stat() on every import",
        )
    }

    // ---------------------------------------------------------------------- discovery: jar entries

    @Test
    fun aJarPayloadIsExtractedBecauseTheImporterCannotOpenAZipEntry() {
        val jar = payloadJar("payload_jar_probe", withDirectoryEntries = true)
        val cache = cacheDirectory()
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)

        val roots = ClasspathPayload.resolveRoots(loader, cache)

        assertEquals(1, roots.size, "expected exactly one extracted root, got $roots")
        val root = roots.single()
        assertTrue(root.isDirectory, "$root should be a real directory")
        assertTrue(
            root.startsWith(cache),
            "$root should live under the cache directory $cache, not inside the jar",
        )
        assertTrue(File(root, "payload_jar_probe/__init__.py").isFile)
        assertTrue(
            File(root, "payload_jar_probe/data/table.json").isFile,
            "a non-.py file has to travel too -- ResourceBundler carries all files, and the module " +
                "below reads its own data file at import time",
        )
    }

    @Test
    fun aJarWithoutDirectoryEntriesIsStillFound() {
        // `ClassLoader.getResources("python/")` can only answer from a `python/` *directory entry*.
        // Not every archive has one, so discovery cannot rest on that alone.
        val jar = payloadJar("payload_jar_nodirs", withDirectoryEntries = false)
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)

        val roots = ClasspathPayload.resolveRoots(loader, cacheDirectory())

        assertEquals(1, roots.size, "expected the payload to be found by scanning entries, got $roots")
        assertTrue(File(roots.single(), "payload_jar_nodirs/__init__.py").isFile)
    }

    @Test
    fun anUnchangedJarIsNotExtractedTwiceAndAChangedOneIs() {
        val cache = cacheDirectory()
        val jar = payloadJar("payload_stamp_probe", withDirectoryEntries = true)
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)

        val first = ClasspathPayload.resolveRoots(loader, cache).single()
        val marker = File(first, "payload_stamp_probe/__init__.py")
        val stampedAt = marker.lastModified()
        // A file the extraction did not write. Surviving a second call is what proves nothing was
        // rewritten -- a re-extraction clears the payload subtree first.
        val witness = File(first, "witness.txt").apply { writeText("still here") }

        val second = ClasspathPayload.resolveRoots(loader, cache).single()
        assertEquals(first, second)
        assertTrue(witness.isFile, "a stamp hit must not rewrite the tree")
        assertEquals(stampedAt, marker.lastModified())

        // Same jar path, different contents.
        val changed = payloadJar("payload_stamp_probe", withDirectoryEntries = true, extraFile = "extra.py")
        changed.copyTo(jar, overwrite = true)
        val third = ClasspathPayload.resolveRoots(URLClassLoader(arrayOf(jar.toURI().toURL()), null), cache).single()
        assertTrue(
            File(third, "extra.py").isFile,
            "a jar whose contents changed must produce the new payload, not the stamped old one",
        )
    }

    @Test
    fun anInterruptedExtractionIsNotMistakenForAFinishedOne() {
        val cache = cacheDirectory()
        val jar = payloadJar("payload_partial_probe", withDirectoryEntries = true)
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)

        val root = ClasspathPayload.resolveRoots(loader, cache).single()
        // What being killed halfway through looks like afterwards: files present, stamp absent.
        assertTrue(ClasspathPayload.stampFileFor(root).delete(), "expected a stamp to have been written")
        File(root, "payload_partial_probe/__init__.py").delete()

        val again = ClasspathPayload.resolveRoots(loader, cache).single()
        assertEquals(root, again)
        assertTrue(
            File(again, "payload_partial_probe/__init__.py").isFile,
            "the stamp goes in after the last byte, so a tree without one is re-extracted rather " +
                "than accepted -- the failure a result-probe accepts surfaces as an ImportError " +
                "for whichever module sorted after the interruption",
        )
    }

    // ------------------------------------------------------------------------------- sys.path

    @Test
    fun installPrependsAndDoesNotDuplicate() = PythonTestFixture.withInterpreter {
        val root = File(payloadDirectory("payload_syspath_probe"), "python").canonicalPath

        assertTrue(PythonPayload.install(root), "first install should add an entry")
        assertEquals(root, PythonPath.entries().first(), "the payload is the application's own code")

        assertFalse(PythonPayload.install(root), "a second install should be a no-op")
        assertEquals(
            1,
            PythonPath.entries().count { it == root },
            "sys.path should carry the payload exactly once",
        )
    }

    @Test
    fun installRefusesADirectoryThatIsNotThere() = PythonTestFixture.withInterpreter {
        val missing = File(newTemporaryDirectory("absent"), "nope").absolutePath
        val failure = kotlin.runCatching { PythonPayload.install(missing) }.exceptionOrNull()
        assertTrue(
            failure is IllegalArgumentException,
            "a payload root that is not readable should say so, not sit silently on sys.path; got $failure",
        )
        assertContains(failure!!.message.orEmpty(), missing)
    }

    // -------------------------------------------------------------------------------- end to end

    @Test
    fun aDirectoryPayloadIsImportableAndReadsItsOwnDataFile() = PythonTestFixture.withInterpreter {
        val classpathRoot = payloadDirectory("payload_e2e_dir")
        val roots = ClasspathPayload.resolveRoots(
            URLClassLoader(arrayOf(classpathRoot.toURI().toURL()), null),
            cacheDirectory(),
        )
        roots.forEach { PythonPayload.install(it.absolutePath) }

        assertEquals(EXPECTED_GREETING, greetingOf("payload_e2e_dir"))
    }

    @Test
    fun aJarPayloadIsImportableAndReadsItsOwnDataFile() = PythonTestFixture.withInterpreter {
        val jar = payloadJar("payload_e2e_jar", withDirectoryEntries = true)
        val roots = ClasspathPayload.resolveRoots(
            URLClassLoader(arrayOf(jar.toURI().toURL()), null),
            cacheDirectory(),
        )
        roots.forEach { PythonPayload.install(it.absolutePath) }

        assertEquals(EXPECTED_GREETING, greetingOf("payload_e2e_jar"))
    }

    /**
     * The whole relay, entered where `Python3.initialize` enters it: no classloader is handed in,
     * so this exercises the same lookup a shipped application gets.
     */
    @Test
    fun theStartupHookFindsAPayloadOnTheContextClassLoader() = PythonTestFixture.withInterpreter {
        val classpathRoot = payloadDirectory("payload_startup_probe")
        Thread.currentThread().contextClassLoader =
            URLClassLoader(arrayOf(classpathRoot.toURI().toURL()), null)

        val installed = PythonPayload.installStagedRoots()

        assertEquals(
            listOf(File(classpathRoot, "python").canonicalPath),
            installed,
            "installStagedRoots is what Python3.initialize calls; it has to find the payload by itself",
        )
        assertEquals(EXPECTED_GREETING, greetingOf("payload_startup_probe"))
    }

    /**
     * **This library's own top-level Kotlin package is `python`.**
     *
     * `python.multiplatform` compiles to class files under `python/multiplatform`, so every class path this
     * library is on answers `getResources("python/")` — with `build/classes/kotlin/desktop/main/python`
     * during a Gradle build, and with a `jar:` URL into `python-multiplatform.jar` once published.
     * Name-based discovery therefore puts a directory of `.class` files at `sys.path[0]` in every
     * application that uses this library. It is not hypothetical: this test failed on the first run
     * with exactly those two directories, which is why discovery asks what is *in* a `python/` root
     * rather than trusting its name.
     */
    @Test
    fun theLibrarysOwnPythonPackageIsNotMistakenForAPayload() {
        assertEquals(
            emptyList(),
            discoverStagedPayloadRoots(),
            "the suite's own class path carries `python/multiplatform` and `python/native` class " +
                "output, and neither is a Python payload",
        )
    }

    @Test
    fun aDirectoryOfClassFilesUnderPythonIsRejected() {
        val root = newTemporaryDirectory("class-output")
        File(root, "python/multiplatform/env/PythonPayload.class").apply {
            parentFile.mkdirs()
            writeText("not python")
        }
        val loader = URLClassLoader(arrayOf(root.toURI().toURL()), null)

        assertEquals(
            emptyList(),
            ClasspathPayload.resolveRoots(loader, cacheDirectory()),
            "a `python/` whose children hold no __init__ and no module file is this library's own " +
                "package, not a consumer payload",
        )
    }

    // ------------------------------------------------------------------------------ measurement

    /**
     * @HighOverheadNativeCall is not the right marker here -- nothing in this path is a C call per
     * item. What it costs is I/O, and the point of measuring is that the cost is paid *once* and
     * that the stamp is what makes the second time free.
     */
    @Test
    fun measureDiscoveryAndInstallCost() = PythonTestFixture.withInterpreter {
        val cache = cacheDirectory()
        val jar = payloadJar("payload_measure_probe", withDirectoryEntries = true, filler = 200)
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)

        val coldNanos = measure { ClasspathPayload.resolveRoots(loader, cache) }
        val warmNanos = (1..5).minOf { measure { ClasspathPayload.resolveRoots(loader, cache) } }

        val installRoot = File(payloadDirectory("payload_measure_path"), "python").canonicalPath
        val prependNanos = measure { PythonPath.prepend(installRoot) }
        val readNanos = measure { PythonPath.entries() }

        println(
            "PythonPayload cost: cold extraction (203 files) = ${coldNanos / 1_000} us, " +
                "stamp hit = ${warmNanos / 1_000} us, " +
                "sys.path prepend = ${prependNanos / 1_000} us, " +
                "sys.path read (${PythonPath.entries().size} entries) = ${readNanos / 1_000} us",
        )

        assertTrue(
            warmNanos < coldNanos,
            "a stamp hit must be cheaper than the extraction it skips (cold=$coldNanos ns, warm=$warmNanos ns)",
        )
    }

    private inline fun measure(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return System.nanoTime() - started
    }

    // ------------------------------------------------------------------------------- fixtures

    private fun greetingOf(packageName: String): String? {
        val greeting = PythonTestFixture.eval("__import__('$packageName').greeting()")
        return Python3.withPython { PyUnicode_AsUTF8(greeting.pointer) }
    }

    private fun newTemporaryDirectory(prefix: String): File =
        File.createTempFile("pmp-$prefix", "").let { file ->
            file.delete()
            file.mkdirs()
            temporaries += file
            file
        }

    private fun cacheDirectory(): File = newTemporaryDirectory("cache")

    /** A classpath *root* holding `python/<packageName>/...`. The sys.path entry is its `python/`. */
    private fun payloadDirectory(packageName: String): File {
        val root = newTemporaryDirectory(packageName)
        payloadFiles(packageName).forEach { (relative, content) ->
            File(root, "python/$relative").apply { parentFile.mkdirs(); writeText(content) }
        }
        return root
    }

    private fun payloadJar(
        packageName: String,
        withDirectoryEntries: Boolean,
        extraFile: String? = null,
        filler: Int = 0,
    ): File {
        val jar = File(newTemporaryDirectory("jar"), "$packageName.jar")
        val files = payloadFiles(packageName).toMutableMap()
        extraFile?.let { files[it] = "MARKER = 'changed'\n" }
        repeat(filler) { index -> files["$packageName/filler_$index.py"] = "VALUE = $index\n" }

        JarOutputStream(jar.outputStream().buffered()).use { out ->
            if (withDirectoryEntries) {
                files.keys
                    .flatMap { path -> path.split('/').dropLast(1).scan("python") { a, b -> "$a/$b" } }
                    .toSortedSet()
                    .forEach { out.putNextEntry(JarEntry("$it/")); out.closeEntry() }
            }
            files.forEach { (relative, content) ->
                out.putNextEntry(JarEntry("python/$relative"))
                out.write(content.toByteArray())
                out.closeEntry()
            }
            // Something outside `python/` so "extracted the right subtree" is testable.
            out.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            out.write("Manifest-Version: 1.0\n".toByteArray())
            out.closeEntry()
        }
        return jar
    }

    /**
     * Mirrors the fixture `toolchain`'s `49da1d8` added to `usage-example`: a module with a version
     * constant, plus a non-`.py` data file it reads at import time. Reading the data file is what
     * makes "the payload arrived" checkable by content rather than by path.
     */
    private fun payloadFiles(packageName: String): Map<String, String> = mapOf(
        "$packageName/__init__.py" to
            """
            import json
            import pathlib

            VERSION = "0.1.0"
            _NOTE = json.loads(
                (pathlib.Path(__file__).parent / "data" / "table.json").read_text()
            )["note"]


            def greeting() -> str:
                return f"example_py {VERSION}: {_NOTE}"
            """.trimIndent() + "\n",
        "$packageName/data/table.json" to
            """{"note": "a non-.py resource, carried because ResourceBundler carries all files"}""",
    )

    private companion object {
        const val EXPECTED_GREETING =
            "example_py 0.1.0: a non-.py resource, carried because ResourceBundler carries all files"
    }
}
