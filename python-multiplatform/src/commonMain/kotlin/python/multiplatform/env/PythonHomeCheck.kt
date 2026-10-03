package python.multiplatform.env

import python.multiplatform.Versions

/**
 * Pre-flight check for `PYTHONHOME`, run once by [python.multiplatform.ffi.Python3.initialize]
 * before it ever reaches `Py_Initialize()`.
 *
 * Why this exists: CPython does not fail reliably when `PYTHONHOME` is unusable. Reproduced directly
 * on desktop (see `PythonHomeCheckTest` and the measurement this file's history records): a
 * nonexistent `PYTHONHOME` is already caught earlier, by `manager.loadLibPython`'s own library
 * search, with a message naming the path. But a `PYTHONHOME` whose shared library is present and
 * whose *stdlib* is missing or unreadable sails past that check, reaches `Py_Initialize()`, and
 * that call's own contract is to treat the failure as fatal -- it calls `Py_FatalError()` and
 * aborts the whole process, with no Kotlin exception and no interpreter left to report through.
 * On iOS it can be worse than an abort: ROADMAP (2026-08-13, "PYTHONHOME on an external volume
 * hangs the app instead of failing it") found a sandboxed simulator app parked forever inside
 * `open$NOCANCEL` while importing `encodings` from a path under `/Volumes/` -- 0% CPU, a blank
 * window, nothing in the console. The sandbox does not deny the open; it never completes it.
 *
 * This check cannot promise to catch that exact case -- it reads the same directory through the
 * same kind of syscall CPython's own import machinery uses, so a `PYTHONHOME` whose access is
 * *parked* rather than *denied* can park this check identically. It is written anyway because
 * every other misconfiguration -- a wrong path, a stdlib that was never staged, permissions that
 * return synchronously -- turns into a clear, catchable exception instead of a silent hang or an
 * uncatchable abort. See `iosMain/README.md` for what to do about the case this cannot catch.
 */
internal object PythonHomeCheck {

    /**
     * Throws with a message naming what is wrong when `PYTHONHOME` is set but unusable. Returns
     * normally, doing nothing, when it is unset -- an embedder that relies on CPython's own
     * default prefix search is not this check's business -- or when a marker file for the
     * configured version is readable under it.
     */
    fun verifyOrThrow() = verifyOrThrow(readEnvVar("PYTHONHOME"))

    /**
     * Same as the no-arg overload, taking [home] directly instead of reading it from the
     * environment. Exists so tests can exercise the exact exception a bad `PYTHONHOME` produces
     * without mutating process-wide environment state -- see `PythonHomeCheckTest`.
     */
    internal fun verifyOrThrow(home: String?) {
        val nonBlankHome = home?.takeIf { it.isNotBlank() } ?: return
        val problem = diagnose(nonBlankHome) ?: return
        throw IllegalStateException(
            "PYTHONHOME='$nonBlankHome' $problem Py_Initialize() cannot start without a CPython " +
                "${Versions.currentVersion.compactVersionString} standard library under it " +
                "(expected to find, e.g., 'lib/python${Versions.currentVersion.taggedVersionString}/os.py' " +
                "or 'Lib/os.py')."
        )
    }

    /**
     * Null when [home] looks usable, otherwise a sentence fragment describing what is wrong with
     * it (grammatically continuing "PYTHONHOME='...' ...").
     *
     * Checks both stdlib layouts CPython's own build systems produce -- `lib/pythonX.Y/` on macOS
     * and Linux, `Lib/` on Windows -- since desktop compiles one source set for all three and
     * decides which applies at runtime, not at compile time (see `manager.resolveSidecarLibrary`,
     * which probes the same two shapes for the shared library).
     */
    internal fun diagnose(home: String): String? {
        val tag = Versions.currentVersion.taggedVersionString
        val markers = listOf("lib/python$tag/os.py", "Lib/os.py")
        if (markers.any { pathIsAccessible("$home/$it") }) return null
        return if (pathIsAccessible(home)) {
            "exists but has no readable standard library under it."
        } else {
            "does not exist or is not readable."
        }
    }
}

/** Reads process environment variable [name], or null when unset. Null on targets with no such concept (wasmJs). */
internal expect fun readEnvVar(name: String): String?

/**
 * Whether a file or directory exists at [path] and can be read. A quick, synchronous filesystem
 * probe -- see [PythonHomeCheck] for the syscall-parking case it cannot detect.
 */
internal expect fun pathIsAccessible(path: String): Boolean
