package python.multiplatform.env

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.closedir
import platform.posix.opendir

/**
 * The directory containing the running executable, or null when the OS cannot report it.
 */
internal expect fun executableDirectory(): String?

/**
 * Where a desktop Kotlin/Native application (`macosArm64`, `linuxX64`) finds its packaged CPython
 * prefix (SPEC L-9 / P-1, #191).
 *
 * A desktop native binary has no JVM, no system properties and no launcher to set `PYTHONHOME`:
 * like a GraalVM native image (#246), its prefix ships beside the executable under
 * [DIRECTORY_NAME] (or, inside a macOS `.app` bundle, under `../Resources/`[DIRECTORY_NAME]).
 */
internal object DesktopNativePythonHome {

    /** Matches `PackagedPythonHome.DIRECTORY_NAME` (`jvmMain`) and `IosPythonHome.DIRECTORY_NAME` (`iosMain`). */
    const val DIRECTORY_NAME = "python-multiplatform-home"

    /** A resolved prefix and a human-readable account of where it came from, for error messages. */
    data class Resolved(val path: String, val source: String)

    /** [resolve] against this process's environment and executable directory. */
    fun resolve(): Resolved? = resolve(
        environmentHome = readEnvVar("PYTHONHOME"),
        executableDir = executableDirectory(),
        isDirectory = ::isDirectory,
    )

    /**
     * The packaged prefix, or null when there is none to apply.
     *
     * 1. A non-blank `PYTHONHOME` in the environment wins, and the answer is null: CPython reads it
     *    itself and [PythonHomeCheck] already checked it. This keeps the Gradle host test tasks
     *    (`macosArm64Test`, `linuxX64Test`) and an explicit caller environment working unchanged.
     * 2. `<executableDir>/`[DIRECTORY_NAME], when that directory exists.
     * 3. `<executableDir>/../Resources/`[DIRECTORY_NAME], when that directory exists (a macOS
     *    `.app` bundle places the binary in `Contents/MacOS/` and resources in `Contents/Resources/`).
     */
    fun resolve(
        environmentHome: String?,
        executableDir: String?,
        isDirectory: (String) -> Boolean,
    ): Resolved? {
        if (!environmentHome.isNullOrBlank()) return null
        val dir = executableDir?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: return null
        val beside = "$dir/$DIRECTORY_NAME"
        if (isDirectory(beside)) {
            return Resolved(beside, "$DIRECTORY_NAME beside the executable ($dir)")
        }
        val parent = dir.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty()) {
            val resources = "$parent/Resources/$DIRECTORY_NAME"
            if (isDirectory(resources)) {
                return Resolved(resources, "$DIRECTORY_NAME in the app bundle's Resources directory ($parent/Resources)")
            }
        }
        return null
    }

    /**
     * A directory exists at [path] (a regular file there is not one): `opendir(3)` succeeds only on
     * a directory. Not `stat(2)`: `st_mode` is 16 bits on macOS and 32 on Linux, and the metadata
     * compilation of this shared source set, which publishing runs, rejects that mismatch.
     */
    @OptIn(ExperimentalForeignApi::class)
    internal fun isDirectory(path: String): Boolean {
        val dir = opendir(path) ?: return false
        closedir(dir)
        return true
    }
}

/**
 * Hands a packaged prefix to CPython with `Py_SetPythonHome` before `Py_Initialize()`.
 *
 * `Py_DecodeLocale` is documented by CPython as safe to call before initialisation and produces
 * storage that lives for the duration of the process, as `Py_SetPythonHome` requires.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal actual fun applyPackagedPythonHome() {
    val home = DesktopNativePythonHome.resolve() ?: return
    try {
        PythonHomeCheck.verifyOrThrow(home.path)
    } catch (e: IllegalStateException) {
        throw IllegalStateException(
            "The Python home this application was packaged with (${home.source}) is unusable: ${e.message}",
            e,
        )
    }
    val wide = python.native.ffi.bindings.Py_DecodeLocale(home.path, null)
        ?: throw IllegalStateException("Py_DecodeLocale could not decode the packaged Python home '${home.path}'.")
    python.native.ffi.bindings.Py_SetPythonHome(wide)
}
