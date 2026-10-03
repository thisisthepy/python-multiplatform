package python.multiplatform.env

import java.io.File

/**
 * Where a **packaged** desktop application's CPython prefix is -- SPEC L-9, issue #60.
 *
 * During development the Gradle plugin's `stagePythonHome` sets `PYTHONHOME` on every `run` and
 * `test` task, and CPython reads it with `getenv(3)`. A packaged application (`createDistributable`,
 * an installer, a plain `java -jar`) is launched by something else, and nothing puts `PYTHONHOME` in
 * its environment: jpackage launchers accept `--java-options`, not environment variables. What they
 * *can* carry is a system property, and jpackage expands `$APPDIR` inside one. So a packaged prefix
 * is named by a system property and handed to CPython in-process, with `Py_SetPythonHome`, before
 * `Py_Initialize` (`applyPackagedPythonHome` in `desktopMain`).
 *
 * Lives in `jvmMain` rather than `desktopMain` only because `manager.loadLibPython` -- also
 * `jvmMain` -- loads `libpython` out of the same prefix. It reads nothing Android sets, so on Android
 * it always resolves to null.
 */
internal object PackagedPythonHome {

    /** An explicit prefix, e.g. `-Dpython.multiplatform.home=$APPDIR/python-multiplatform-home`. */
    const val HOME_PROPERTY = "python.multiplatform.home"

    /**
     * Compose Desktop sets this on every launcher it builds (`$APPDIR/resources` in a packaged app)
     * and on its own `run` task. The Gradle plugin copies the prefix into those resources under
     * [DIRECTORY_NAME], so a Compose app needs no property of its own.
     */
    const val COMPOSE_RESOURCES_PROPERTY = "compose.application.resources.dir"

    /** `PACKAGED_HOME_DIRECTORY` in `python-multiplatform-gradle-plugin`; the two must agree. */
    const val DIRECTORY_NAME = "python-multiplatform-home"

    /** A resolved prefix and a human-readable account of where it came from, for error messages. */
    data class Resolved(val path: String, val source: String)

    /** [resolve] against this process's environment and system properties. */
    fun resolve(): Resolved? = resolve(
        environmentHome = System.getenv("PYTHONHOME"),
        property = System::getProperty,
        isDirectory = { File(it).isDirectory },
    )

    /**
     * The packaged prefix, or null when there is none to apply.
     *
     * 1. A non-blank `PYTHONHOME` in the environment wins, and the answer is null: CPython reads that
     *    variable itself and `PythonHomeCheck` already checks it. Overriding it would make a launcher
     *    property beat a developer's (or a test task's) explicit choice.
     * 2. [HOME_PROPERTY], whether or not the path exists -- a wrong explicit path must reach
     *    `PythonHomeCheck` and be reported by name, not silently ignored into a `Py_FatalError`.
     * 3. `<`[COMPOSE_RESOURCES_PROPERTY]`>/`[DIRECTORY_NAME], **only if that directory exists**:
     *    every Compose app has the property, including one whose build did not package a prefix,
     *    and that app must behave exactly as it did before this existed.
     */
    fun resolve(
        environmentHome: String?,
        property: (String) -> String?,
        isDirectory: (String) -> Boolean,
    ): Resolved? {
        if (!environmentHome.isNullOrBlank()) return null
        property(HOME_PROPERTY)?.takeIf { it.isNotBlank() }?.let {
            return Resolved(it, "system property $HOME_PROPERTY")
        }
        val resources = property(COMPOSE_RESOURCES_PROPERTY)?.takeIf { it.isNotBlank() } ?: return null
        val candidate = File(resources, DIRECTORY_NAME).path
        return if (isDirectory(candidate)) {
            Resolved(candidate, "$DIRECTORY_NAME under system property $COMPOSE_RESOURCES_PROPERTY")
        } else {
            null
        }
    }
}
