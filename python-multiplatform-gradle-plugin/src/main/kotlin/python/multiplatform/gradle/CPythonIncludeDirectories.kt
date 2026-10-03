package python.multiplatform.gradle

import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider

/** Which CPython build a header directory belongs to. The two are different trees with different `Py_GIL_DISABLED` layouts. */
enum class CPythonFlavour { GIL, FREE_THREADED }

/**
 * Where, inside the build-time acquisition tree (`python-multiplatform/build/python-standalone/
 * extracted/<pythonVersion>/`), the headers of each target's CPython live. Pure functions, so the
 * layout is pinned by tests without downloading anything.
 *
 * The value is the directory that **directly contains `Python.h`** -- the one a compiler wants as
 * `-I`. That is one level below the `include/` directory on every target except Windows
 * (`include/python3.14/`, `include/python3.14t/` for free-threaded), which is why the issue's
 * table (`.../include`) and this differ by that last segment.
 */
object CPythonIncludeLayout {
    val desktopTargets = listOf("macos-aarch64", "macos-x86_64", "linux-x86_64", "windows-x86_64")
    val androidTargets = listOf("android-aarch64", "android-x86_64")

    /** XCFramework slice directory names; the simulator slice is a fat arm64+x86_64 one. */
    val iosTargets = listOf("ios-arm64", "ios-arm64_x86_64-simulator")

    val allTargets: List<String> = desktopTargets + androidTargets + iosTargets

    fun supports(target: String, flavour: CPythonFlavour): Boolean =
        target in allTargets && (flavour == CPythonFlavour.GIL || target in desktopTargets)

    /** The extraction task that produces [target] -- the name `python-multiplatform/build.gradle.kts` registers. */
    fun downloadTaskName(target: String): String = when (target) {
        in iosTargets -> "downloadPython_ios"
        in desktopTargets, in androidTargets -> "downloadPython_${target.replace("-", "_")}"
        else -> throw IllegalArgumentException("unknown CPython target '$target'")
    }

    /** Path of the include directory relative to the version-keyed extraction root. */
    fun relativePath(target: String, flavour: CPythonFlavour, pythonVersion: String): String {
        require(supports(target, flavour)) { "no $flavour CPython for target '$target'" }
        val parts = pythonVersion.split('.')
        require(parts.size >= 2) { "python version '$pythonVersion' has no major.minor" }
        val tag = "${parts[0]}.${parts[1]}" + if (flavour == CPythonFlavour.FREE_THREADED) "t" else ""
        return when (target) {
            "windows-x86_64" ->
                (if (flavour == CPythonFlavour.FREE_THREADED) "windows-x86_64-freethreaded" else "windows-x86_64") +
                    "/python/include"
            in desktopTargets -> {
                val dir = if (flavour == CPythonFlavour.FREE_THREADED) "$target-freethreaded" else target
                "$dir/python/include/python$tag"
            }
            in androidTargets -> "$target/prefix/include/python$tag"
            else -> "ios/Python.xcframework/$target/include/python$tag"
        }
    }
}

/**
 * The public output: one `Provider<Directory>` per (target, flavour), each carrying the extraction
 * task as its build dependency, so anything that wires it as an input (`-I`, an `@InputDirectory`)
 * triggers the download and unpack.
 *
 * Only the flavour the build was configured for (`-PpythonFreeThreaded`) has an extraction task;
 * asking for the other one fails with a message naming the property, rather than returning a path
 * nothing will ever create.
 *
 * @param extractedRoot the version-keyed extraction directory
 * @param extractionTask the task that fills [extractedRoot] for a target+flavour, or null when this build does not extract it
 */
class CPythonIncludeDirectories(
    private val extractedRoot: Directory,
    private val pythonVersion: String,
    private val extractionTask: (target: String, flavour: CPythonFlavour) -> TaskProvider<*>?,
) {
    val targets: List<String> get() = CPythonIncludeLayout.allTargets

    /** Whether [includeDir] would succeed for this pair in this build. */
    fun isAvailable(target: String, flavour: CPythonFlavour = CPythonFlavour.GIL): Boolean =
        CPythonIncludeLayout.supports(target, flavour) && extractionTask(target, flavour) != null

    /** The directory holding `Python.h` for [target]; resolving it makes Gradle run the extraction task first. */
    fun includeDir(target: String, flavour: CPythonFlavour = CPythonFlavour.GIL): Provider<Directory> {
        if (!CPythonIncludeLayout.supports(target, flavour)) {
            throw GradleException(
                "No $flavour CPython include directory for '$target'. Targets: ${CPythonIncludeLayout.allTargets}; " +
                    "free-threaded exists for desktop targets only.",
            )
        }
        val task = extractionTask(target, flavour) ?: throw GradleException(
            "The $flavour CPython for '$target' is not extracted by this build. " +
                "Only the flavour selected with -PpythonFreeThreaded is downloaded.",
        )
        val relative = CPythonIncludeLayout.relativePath(target, flavour, pythonVersion)
        // `TaskProvider.map` keeps the task as the producer, so the dependency travels with the value.
        return task.map { extractedRoot.dir(relative) }
    }
}
