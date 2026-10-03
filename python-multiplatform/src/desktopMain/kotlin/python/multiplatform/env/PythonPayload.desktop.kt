package python.multiplatform.env

import java.io.File
import java.io.IOException
import java.net.JarURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipEntry

/**
 * Desktop's answer to "where did the payload land": the root of the jar, as a classpath resource
 * named `python/`.
 *
 * `toolchain`'s `stagePythonBundleDesktop` registers `build/pythonStaging/desktop` as a JVM
 * resource root, so `processResources` copies `python/...` to the root of the consumer's jar. That
 * is a **classpath resource, not a filesystem path** — and CPython's importer opens modules with
 * `open(2)`, which cannot see inside a zip. So a jar payload has to be materialised before it can
 * go on `sys.path`; a payload sitting on the classpath as a plain directory (a Gradle `run`, an
 * exploded application image, an IDE run configuration) already is one and is used where it lies.
 *
 * This is the same shape of problem `PythonBootstrap.stageStdlib` solves on Android for the
 * standard library, and it borrows that class's central rule: **a completion marker, not a probe of
 * the result.** A tree whose extraction was interrupted has files in it and satisfies every "is it
 * there" test; the stamp is written after the last byte, so it cannot be true of a partial tree.
 */
internal actual fun discoverStagedPayloadRoots(): List<String> =
    ClasspathPayload.resolveRoots(ClasspathPayload.defaultLoaders(), ClasspathPayload.defaultCacheDir())
        .map { it.absolutePath }

internal object ClasspathPayload {

    /** The name of the completion marker, written into an extracted root after the last byte. */
    private const val STAMP_NAME = ".python-multiplatform-payload"

    /** Overrides where jar payloads are extracted to. */
    private const val CACHE_PROPERTY = "python.multiplatform.payload.cache"

    /**
     * Both loaders that can reasonably hold a consumer's payload, most specific first.
     *
     * The context class loader is what an application server, a Gradle test worker or any other
     * container sets to "the class path of the thing currently running", and it is the only one a
     * test can substitute — which is what makes the start-up path testable at all rather than only
     * its inner half. This class's own loader is the fallback and covers the ordinary case where
     * the library and the payload ship on the same class path.
     */
    fun defaultLoaders(): List<ClassLoader> = listOfNotNull(
        Thread.currentThread().contextClassLoader,
        ClasspathPayload::class.java.classLoader,
    ).distinct()

    /**
     * `<tmp>/python-multiplatform-payload`, overridable with the `python.multiplatform.payload.cache`
     * system property.
     *
     * The temporary directory rather than a user cache directory because the content is derived —
     * losing it costs one re-extraction, and the stamp key makes a stale copy impossible rather
     * than merely unlikely.
     */
    fun defaultCacheDir(): File =
        System.getProperty(CACHE_PROPERTY)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("java.io.tmpdir"), "python-multiplatform-payload")

    /** The stamp for an extracted root. Public to the test that deletes it to simulate a crash. */
    fun stampFileFor(extractedRoot: File): File = File(extractedRoot.parentFile, "${extractedRoot.name}$STAMP_NAME")

    fun resolveRoots(loader: ClassLoader, cacheDir: File): List<File> = resolveRoots(listOf(loader), cacheDir)

    /**
     * Every usable `python/` root reachable from [loaders], in class path order, deduplicated.
     *
     * Directory entries are returned as they are; jar entries are extracted into [cacheDir] and the
     * extraction directory returned.
     */
    fun resolveRoots(loaders: List<ClassLoader>, cacheDir: File): List<File> {
        val roots = LinkedHashSet<File>()
        for (loader in loaders) {
            for (url in payloadUrls(loader)) {
                when (url.protocol) {
                    "file" -> directoryRoot(url)?.let { roots += it }
                    "jar" -> jarOf(url)?.let { jar -> extract(jar, cacheDir)?.let { roots += it } }
                    // Anything else is a class path shape this library has never been deployed in
                    // (an OSGi `bundleresource:`, a Spring Boot `nested:`). Guessing at its
                    // internals would be worse than the empty result, which reads as "no payload"
                    // and is what a consumer on such a container has anyway.
                    else -> Unit
                }
            }
        }
        // Only when the cheap lookup found nothing at all: this reads the central directory of
        // every jar on the class path, which for an application is a handful and for this
        // library's own test run is upwards of fifty. Doing it unconditionally would put that
        // scan in every `Python3.initialize()` on the platform, to cover an archive shape that
        // Gradle's own `Jar` task does not produce.
        if (roots.isEmpty()) {
            for (loader in loaders) {
                for (file in classPathJarFiles(loader)) {
                    val extracted = runCatching {
                        JarFile(file).let { jar ->
                            if (jar.hasPayload()) extract(jar, cacheDir) else { jar.close(); null }
                        }
                    }.getOrNull()
                    if (extracted != null) roots += extracted
                }
            }
        }
        return roots.toList()
    }

    /**
     * `python/` as the class loader sees it, asked for both with and without the trailing slash.
     *
     * A jar can only answer either of these from a `python/` **directory entry**, and not every
     * archive has one — `JarOutputStream` writes directory entries only if asked. [classPathJarFiles]
     * is the fallback for that case; asking here first is what keeps the common case a single
     * lookup instead of a class path scan.
     */
    private fun payloadUrls(loader: ClassLoader): List<URL> =
        (loader.getResources("${PythonPayload.PAYLOAD_ROOT}/").toList() +
            loader.getResources(PythonPayload.PAYLOAD_ROOT).toList())
            .distinctBy { it.toString() }

    /**
     * A `file:` URL as a directory to use in place, or null when it is not a usable payload.
     *
     * An **empty** `python/` is deliberately not usable: `StagePythonBundleTask` creates its
     * destination whether or not a bundle exists ("a bundle with no `python/` root is silence, not
     * failure"), so an empty directory is the normal state of a consumer that has not pointed the
     * DSL at a `ppp` package yet. Putting it on `sys.path` would buy nothing and cost a `stat` on
     * every import miss for the life of the process.
     */
    private fun directoryRoot(url: URL): File? {
        val directory = runCatching { File(url.toURI()) }.getOrNull() ?: return null
        if (!directory.isDirectory) return null
        if (!directory.holdsPythonModules()) return null
        return directory.canonicalFile
    }

    /**
     * Whether a `python/` root actually holds Python, rather than merely being called `python`.
     *
     * **This is not a sanity check, it is the whole discriminator, and it exists because of a
     * collision nobody had noticed: this library's own top-level Kotlin package is `python`.**
     * `python.multiplatform` and `python.native` compile to class files under `python/multiplatform`
     * and `python/native`, so *every* class path this library is on already answers
     * `getResources("python/")` — with the library's own class output directory during a Gradle
     * build, and with a `jar:` URL into `python-multiplatform.jar` once it is published. Name-based
     * discovery would therefore have put a directory of `.class` files at `sys.path[0]` in every
     * application that used this library, and extracted the entire library jar to do it. Observed,
     * not theorised: this returned
     * `build/classes/kotlin/desktop/{main,test}/python` on the very first run of the suite.
     *
     * The test is what `ResourceBundler` itself produces — its `findPythonPackages` bundles exactly
     * those immediate children that are directories holding an `__init__.py`, plus whatever the
     * `src/<family>` overlay adds. So: at least one immediate child that is a module file, or a
     * directory with an `__init__` in it. A `python/` holding only `.class` files fails it, and so
     * does an empty one.
     *
     * The known gap is a payload whose top level is nothing but PEP 420 namespace packages, which
     * have no `__init__.py`. `ResourceBundler` does not produce one from `src/main` (assumption 4
     * of its KDoc requires the `__init__.py`), so reaching that state takes a platform-overlay-only
     * package; an embedder in that position can call `PythonPayload.install` with the path
     * directly. Guessing instead would mean accepting the library's own package again, and one of
     * those two failures is silent.
     */
    private fun File.holdsPythonModules(): Boolean =
        listFiles().orEmpty().any { child ->
            if (child.isDirectory) INIT_NAMES.any { File(child, it).isFile }
            else child.isModuleFileName()
        }

    /** [holdsPythonModules], applied to archive entry names relative to `python/`. */
    private fun Collection<String>.holdPythonModules(): Boolean = any { relative ->
        val segments = relative.split('/')
        when (segments.size) {
            1 -> segments[0].isModuleFileName()
            2 -> segments[1] in INIT_NAMES
            else -> false
        }
    }

    private fun File.isModuleFileName(): Boolean = name.isModuleFileName()

    private fun String.isModuleFileName(): Boolean = endsWith(".py") || endsWith(".pyc")

    /** `bytecode`-level bundles ship `.pyc` beside (or instead of) the `.py` — see `ResourceBundler` assumption 6. */
    private val INIT_NAMES = listOf("__init__.py", "__init__.pyc")

    private fun jarOf(url: URL): JarFile? = runCatching {
        (url.openConnection() as JarURLConnection).apply { useCaches = false }.jarFile
    }.getOrNull()

    /**
     * The jars on [loader]'s own class path, for the archives [payloadUrls] cannot see into.
     *
     * `URLClassLoader` is asked directly; for everything else — including the `AppClassLoader` a
     * plain `java -jar` run gets — `java.class.path` is the only description of the class path the
     * JVM exposes. Both are walked up the parent chain, because a payload jar is as likely to be
     * on a parent loader as on the one that loaded this class.
     */
    private fun classPathJarFiles(loader: ClassLoader): List<File> {
        val files = LinkedHashSet<File>()
        var current: ClassLoader? = loader
        while (current != null) {
            (current as? URLClassLoader)?.getURLs()?.forEach { url ->
                if (url.protocol == "file") runCatching { File(url.toURI()) }.getOrNull()?.let { files += it }
            }
            current = current.parent
        }
        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .forEach { files += File(it) }

        return files.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
    }

    private fun JarFile.hasPayload(): Boolean =
        payloadEntryNames().holdPythonModules()

    private fun JarFile.payloadEntryNames(): List<String> =
        entries().asSequence()
            .filter { it.isPayloadEntry() }
            .map { it.name.removePrefix("${PythonPayload.PAYLOAD_ROOT}/") }
            .toList()

    private fun ZipEntry.isPayloadEntry(): Boolean =
        !isDirectory && name.startsWith("${PythonPayload.PAYLOAD_ROOT}/") && name.length > PythonPayload.PAYLOAD_ROOT.length + 1

    /**
     * Materialises the `python/` subtree of [jar] under [cacheDir], or returns null when it holds
     * none.
     *
     * The directory is named by a digest of *what is in the archive* — every payload entry's name,
     * size and CRC — rather than by the jar's path or timestamp. Two consequences, both wanted:
     * a rebuilt jar with identical content reuses the extraction, and a jar rewritten in place with
     * different content cannot be mistaken for it. The CRCs come from the archive's central
     * directory, so the key costs no reading of file content at all.
     *
     * The stamp is written last and removed first, exactly as `PythonBootstrap.stageStdlib` does
     * and for the same reason: a tree that exists is not a tree that is complete.
     */
    private fun extract(jar: JarFile, cacheDir: File): File? = jar.use { archive ->
        val entries = archive.entries().asSequence().filter { it.isPayloadEntry() }.sortedBy { it.name }.toList()
        // Not merely "is there a `python/`" -- see holdsPythonModules for the collision with this
        // library's own `python.*` Kotlin package, which every jar of it carries as `python/`.
        if (!entries.map { it.name.removePrefix("${PythonPayload.PAYLOAD_ROOT}/") }.holdPythonModules()) return null

        val key = digestOf(entries)
        val root = File(cacheDir, key)
        val stamp = stampFileFor(root)
        if (stamp.isFile && stamp.readText() == key) return root

        stamp.delete()
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) {
            throw IOException("could not create $root to extract the Python payload from ${archive.name} into")
        }
        for (entry in entries) {
            val relative = entry.name.removePrefix("${PythonPayload.PAYLOAD_ROOT}/")
            val destination = File(root, relative)
            // A `../` in an archive entry writes outside the directory it was meant to fill. The
            // payload is produced by this project's own bundler, but an extraction that trusts
            // entry names is a hole regardless of who is expected to fill the archive.
            if (!destination.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                throw IOException("refusing to extract '${entry.name}' from ${archive.name}: it escapes $root")
            }
            destination.parentFile?.mkdirs()
            archive.getInputStream(entry).use { input ->
                destination.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
            }
        }
        stamp.writeText(key)
        root
    }

    private fun digestOf(entries: List<ZipEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            digest.update("${entry.name} ${entry.size} ${entry.crc}\n".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 64 KB, matching `PythonBootstrap`'s buffer: big enough that data files cross in a few reads. */
    private const val COPY_BUFFER_BYTES = 64 * 1024
}
