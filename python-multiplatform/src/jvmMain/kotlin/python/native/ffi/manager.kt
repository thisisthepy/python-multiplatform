package python.native.ffi

import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import java.io.File
import java.io.FileOutputStream
import java.util.*


internal object manager {
    /**
     * Absolute path to a CPython shared library, overriding every other lookup. Set this when the
     * library is neither on the classpath nor inside `PYTHONHOME` (a relocated install, a system
     * CPython, a test harness pointing at a specific build).
     */
    private const val LIBPYTHON_OVERRIDE_ENV = "PYTHON_MULTIPLATFORM_LIBPYTHON"

    @Synchronized
    fun loadLibPython() {
        val pyVer = Versions.currentVersion
        // `taggedVersionString`, not `compactVersionString`: a free-threaded install ships
        // `libpython3.14t.dylib` and has no `libpython3.14.dylib` at all.
        var libName = "python" + pyVer.taggedVersionString
        val libList = mutableListOf<File>()

        when (currentPlatform.os) {
            OSType.Windows -> {
                libName = libName.replace(".", "")
                // The MSVC runtime has to be resident before python*.dll resolves. Loading
                // python*.dll by absolute path does not add its own directory to the dependency
                // search, so the siblings are loaded explicitly, same as the extraction path does.
                if (loadFromSidecar(libName, prerequisites = listOf("vcruntime140", "vcruntime140_1"))) return
                libList.add(extractLibrary("vcruntime140"))
                libList.add(extractLibrary("vcruntime140_1"))
                libList.add(extractLibrary(libName))
            }
            OSType.Android -> {
                // Android ships the library in the APK's jniLibs, so there is nothing to extract
                // and no sidecar to look for -- the loader already knows where to find it.
                libName = "multiplatform_$libName"
            }
            else -> {
                if (loadFromSidecar(libName)) return
                libList.add(extractLibrary(libName))
            }
        }

        if (libList.isEmpty()) {
            System.loadLibrary(libName)
        } else {
            // Absolute path: the cache directory is not on `java.library.path`, and the extracted
            // files are the cache, so they are not deleted after loading (issue #74).
            libList.forEach { System.load(it.absolutePath) }
        }
    }

    /**
     * Loads CPython from the filesystem instead of from a classpath resource, and reports whether
     * it did.
     *
     * The classpath copy costs ~19 MB in a GraalVM native image, where a resource is not read from
     * a jar at runtime but *baked into the image heap*: the binary carried the whole
     * `libpython*.dylib` as a `byte[]` purely so that [extractLibrary] could write it straight back
     * out to a temporary file at startup. That is the single largest item in the image, and it is
     * redundant whenever CPython is already on disk -- which it must be, because `PYTHONHOME` has
     * to point at a real prefix with a matching stdlib for `Py_Initialize` to get past
     * `encodings`. Loading the library out of that same prefix also removes a real hazard the
     * extraction path has: the stdlib comes from `PYTHONHOME` while the binary came from the jar,
     * so the two can disagree about the version.
     *
     * Only [LIBPYTHON_OVERRIDE_ENV] takes precedence over the classpath copy. The `PYTHONHOME`
     * probe runs *after* it, and only when the resource is absent, so a classpath deployment loads
     * exactly the library it always did.
     */
    private fun loadFromSidecar(libraryName: String, prerequisites: List<String> = emptyList()): Boolean {
        val override = System.getenv(LIBPYTHON_OVERRIDE_ENV)
        val target = if (!override.isNullOrBlank()) {
            File(override).also {
                if (!it.isFile) {
                    throw UnsatisfiedLinkError("$LIBPYTHON_OVERRIDE_ENV points at $override, which is not a file")
                }
            }
        } else {
            if (hasBundledLibrary(libraryName)) return false
            resolveSidecarLibrary(libraryName)
                ?: throw UnsatisfiedLinkError(
                    "CPython is neither bundled on the classpath nor present under PYTHONHOME" +
                        (System.getenv("PYTHONHOME")?.let { " ($it)" } ?: " (PYTHONHOME is unset)") +
                        ". Set $LIBPYTHON_OVERRIDE_ENV to the absolute path of " +
                        System.mapLibraryName(libraryName) + "."
                )
        }

        for (name in prerequisites) {
            val sibling = File(target.parentFile, System.mapLibraryName(name))
            if (sibling.isFile) System.load(sibling.absolutePath)
        }
        System.load(target.absolutePath)
        return true
    }

    /**
     * `PYTHONHOME` is a prefix, and the standalone builds put the shared library in a different
     * place per platform: `lib/` on macOS and Linux, the prefix root on Windows. Both are probed
     * rather than branching on the OS, since either layout is valid input.
     */
    internal fun resolveSidecarLibrary(libraryName: String): File? {
        val home = System.getenv("PYTHONHOME")?.takeIf { it.isNotBlank() } ?: return null
        val fileName = System.mapLibraryName(libraryName)
        return sequenceOf(
            File(home, "lib/$fileName"),
            File(home, fileName),
        ).firstOrNull { it.isFile }
    }

    /**
     * Whether the CPython library for this platform is on the classpath at all.
     *
     * GraalVM's closed world answers this honestly: a resource that was not registered in
     * `reachability-metadata.json` is simply absent, which is exactly the signal wanted here. The
     * `Throwable` catch covers `--exact-reachability-metadata` builds, where the same condition is
     * reported by throwing `MissingResourceRegistrationError` instead of returning null -- a type
     * this source set cannot name, and an `Error` rather than an `Exception`.
     */
    private fun hasBundledLibrary(libraryName: String): Boolean = try {
        javaClass.getResource("/lib/${platformDirectory()}/${System.mapLibraryName(libraryName)}") != null
    } catch (_: Throwable) {
        false
    }

    private fun platformDirectory(): String =
        currentPlatform.os.name.lowercase(Locale.getDefault()) +
            (if (currentPlatform.isArm) "-aarch64" else "-x86_64")

    /**
     * Root of the extraction cache. Never the working directory (issue #74).
     *
     * macOS `~/Library/Caches/python-multiplatform`, Windows `%LOCALAPPDATA%\\python-multiplatform`,
     * elsewhere `$XDG_CACHE_HOME` or `~/.cache` + `/python-multiplatform`. When none of those is
     * usable the fallback is `java.io.tmpdir/python-multiplatform-<user>`. Overridable with the
     * `python.multiplatform.cache` system property (tests use it).
     */
    internal fun cacheRoot(): File {
        System.getProperty("python.multiplatform.cache")?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        val base = when (currentPlatform.os) {
            OSType.MacOS -> home?.let { File(it, "Library/Caches") }
            OSType.Windows -> System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { File(it) }
            else -> System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: home?.let { File(it, ".cache") }
        }
        val root = base?.let { File(it, "python-multiplatform") }
        if (root != null && (root.isDirectory || root.mkdirs()) && root.canWrite()) return root
        val user = System.getProperty("user.name") ?: "user"
        return File(System.getProperty("java.io.tmpdir"), "python-multiplatform-$user")
    }

    /**
     * Copies the bundled library into `<cacheRoot>/<python version>/<platform>/` and returns it,
     * reusing a previous copy whose size equals the resource's. The write goes to a temporary
     * sibling and is moved into place, so a concurrent process never loads a half-written file.
     */
    @Synchronized
    internal fun extractLibrary(
        libraryName: String,
        location: String = "/lib",
        cacheRoot: File = cacheRoot(),
    ): File {
        val fileName = System.mapLibraryName(libraryName)
        val resource = "$location/${platformDirectory()}/$fileName"
        val url = javaClass.getResource(resource)
            ?: throw UnsatisfiedLinkError("Library ${platformDirectory()}/$fileName not found in JAR")

        val dir = File(cacheRoot, Versions.currentVersion.versionString + "/" + platformDirectory())
        dir.mkdirs()
        val target = File(dir, fileName)

        val expected = url.openConnection().let { c -> c.contentLengthLong.also { (c as? java.net.HttpURLConnection)?.disconnect() } }
        if (target.isFile && expected > 0 && target.length() == expected) return target

        val tmp = File.createTempFile("$fileName.", ".part", dir)
        try {
            url.openStream().use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            if (tmp.length() == 0L) throw UnsatisfiedLinkError("Library $resource is empty in the JAR")
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            tmp.delete()
        }
        return target
    }
}
