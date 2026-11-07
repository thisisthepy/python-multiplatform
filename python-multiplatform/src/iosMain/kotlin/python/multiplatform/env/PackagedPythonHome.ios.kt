package python.multiplatform.env

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager

/**
 * Where an installed iOS app's CPython prefix is -- SPEC L-11, issue #59.
 *
 * `Python.xcframework` carries no standard library, and an installed app is launched by SpringBoard
 * (or `simctl launch`) with no `PYTHONHOME`. The simulator *test* task sets one, pointing at the
 * workspace (`SIMCTL_CHILD_PYTHONHOME`), but the same shape for an app parks it at 0% CPU when the
 * path is on an external volume (`iosMain/README.md`). So the app carries its prefix inside its own
 * bundle -- `python-multiplatform-gradle-plugin/src/main/resources/xcode/install-python.sh` copies it to `<app>/`[DIRECTORY_NAME] -- and it is handed
 * to CPython in-process, before `Py_Initialize`, by [applyPackagedPythonHome].
 *
 * The desktop counterpart is `PackagedPythonHome` (`jvmMain`, SPEC L-9); same order, same name.
 */
internal object IosPythonHome {

    /**
     * `IosPythonHomeLayout.BUNDLE_DIRECTORY` in the Gradle plugin, which stages it; the two builds
     * share no code, so each pins the string in a test. Not upstream's `python/`: that is the
     * consumer's payload root ([PythonPayload.PAYLOAD_ROOT]).
     */
    const val DIRECTORY_NAME = "python-multiplatform-home"

    /** A resolved prefix and a human-readable account of where it came from, for error messages. */
    data class Resolved(val path: String, val source: String)

    /** [resolve] against this process's environment and main bundle. */
    fun resolve(): Resolved? = resolve(
        environmentHome = readEnvVar("PYTHONHOME"),
        resourcePath = NSBundle.mainBundle.resourcePath,
        isDirectory = ::isDirectory,
    )

    /**
     * The bundled prefix, or null when there is none to apply.
     *
     * 1. A non-blank `PYTHONHOME` in the environment wins, and the answer is null: CPython reads it
     *    itself and `PythonHomeCheck` already checked it. This is what keeps the simulator test task
     *    (`SIMCTL_CHILD_PYTHONHOME`) and an Xcode scheme's explicit variable working unchanged.
     * 2. `<resourcePath>/`[DIRECTORY_NAME], **only if that directory exists**: an app built without
     *    the Xcode phase must behave exactly as it did before this existed.
     */
    fun resolve(
        environmentHome: String?,
        resourcePath: String?,
        isDirectory: (String) -> Boolean,
    ): Resolved? {
        if (!environmentHome.isNullOrBlank()) return null
        val resources = resourcePath?.takeIf { it.isNotBlank() } ?: return null
        val candidate = "${resources.trimEnd('/')}/$DIRECTORY_NAME"
        return if (isDirectory(candidate)) {
            Resolved(candidate, "$DIRECTORY_NAME in the app bundle's resource directory")
        } else {
            null
        }
    }

    /** `NSFileManager`'s answer: a directory exists at [path] (a file there is not one). */
    @OptIn(ExperimentalForeignApi::class)
    internal fun isDirectory(path: String): Boolean = memScoped {
        val directory = alloc<BooleanVar>()
        NSFileManager.defaultManager.fileExistsAtPath(path, directory.ptr) && directory.value
    }
}

/**
 * iOS's half of SPEC L-11: hand the bundled prefix to CPython with `Py_SetPythonHome`.
 *
 * `Py_SetPythonHome` rather than `setenv("PYTHONHOME")`: it writes CPython's path configuration
 * directly, so there is no process-wide variable for a child process or a later reader to inherit,
 * and it is the same call desktop makes (L-9). `PyConfig.home` is the non-deprecated route, but the
 * library carries no `PyConfig` layout (see `Python3.initialize`).
 *
 * The string is produced by `Py_DecodeLocale`, which CPython documents as safe before
 * initialisation and which decodes UTF-8 on Apple platforms regardless of locale -- what upstream's
 * own iOS testbed does for `config.home`. Its buffer is never freed: the C API asks for storage that
 * lives as long as the program, and this runs once per process.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal actual fun applyPackagedPythonHome() {
    val home = IosPythonHome.resolve() ?: return
    try {
        PythonHomeCheck.verifyOrThrow(home.path)
    } catch (e: IllegalStateException) {
        throw IllegalStateException(
            "The Python home this app bundles (${home.source}) is unusable: ${e.message}",
            e,
        )
    }
    val wide = python.native.ffi.bindings.Py_DecodeLocale(home.path, null)
        ?: throw IllegalStateException("Py_DecodeLocale could not decode the bundled Python home '${home.path}'.")
    python.native.ffi.bindings.Py_SetPythonHome(wide)
}
