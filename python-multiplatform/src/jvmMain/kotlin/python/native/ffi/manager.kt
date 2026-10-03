package python.native.ffi

import python.multiplatform.OSType
import python.multiplatform.Versions
import python.multiplatform.currentPlatform
import python.multiplatform.env.PackagedPythonHome
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

        System.loadLibrary(libName)
        libList.forEach { it.delete() }
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
     * Precedence: [LIBPYTHON_OVERRIDE_ENV]; then a packaged application's prefix
     * ([PackagedPythonHome], SPEC L-9) when it holds the library; then the classpath copy. The
     * `PYTHONHOME` probe runs last, and only when the resource is absent. [PackagedPythonHome]
     * resolves to nothing while `PYTHONHOME` is set, so a development or test run loads exactly
     * the library it always did.
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
            // A packaged application's own prefix (SPEC L-9) comes before the classpath copy. That
            // copy is extracted into the *working directory* and then loaded through
            // `java.library.path` -- which works for this build's tests because they pass
            // `-Djava.library.path=.`, and does not for an app a desktop launches with `/` as its
            // working directory. Loading the library that sits beside the stdlib also keeps the
            // two from coming from different builds. Null whenever PYTHONHOME is set, so a
            // development run loads exactly what it always did.
            val packaged = PackagedPythonHome.resolve()?.let { libraryUnder(it.path, libraryName) }
            if (packaged == null && hasBundledLibrary(libraryName)) return false
            packaged ?: resolveSidecarLibrary(libraryName)
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
        return libraryUnder(home, libraryName)
    }

    /** [libraryName]'s shared library under the prefix [home], in either layout; null if absent. */
    internal fun libraryUnder(home: String, libraryName: String): File? {
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

    @Synchronized
    private fun extractLibrary(libraryName: String, location: String = "/lib"): File {
        var libraryFileName = System.mapLibraryName(libraryName)
        val tempFile = File(".", libraryFileName)

        libraryFileName = platformDirectory() + "/" + libraryFileName

        javaClass.getResourceAsStream("$location/$libraryFileName").use { inputStream ->
            if (inputStream == null) {
                throw UnsatisfiedLinkError("Library $libraryFileName not found in JAR")
            }
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        return tempFile
    }
}
