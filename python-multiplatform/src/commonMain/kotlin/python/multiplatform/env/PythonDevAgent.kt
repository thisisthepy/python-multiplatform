package python.multiplatform.env

import python.multiplatform.ffi.Python3
import python.native.ffi.PyImport_ExecCodeModule
import python.native.ffi.Py_CompileString
import python.native.ffi.Py_DecRef
import python.multiplatform.ffi.pyErrorOrGeneric

/**
 * The dev agent (SPEC L-12, #233): a debug-only channel from a notebook or a hot reload into this
 * interpreter, designed in toolchain's `docs/design/live-connection.md` (option B).
 *
 * [Python3.initialize] calls [startIfRequested]. It does nothing unless the launcher set
 * [ENVIRONMENT_VARIABLE] (`port=<n>;token=<t>[;overlay=<dir>]`) **and** the app was built for it: one of
 * its installed payload roots ([PythonPayload.installed]) holds [ENABLE_MARKER]. Then the agent's Python
 * ([DEV_AGENT_SOURCE], embedded from `src/commonMain/python/python_multiplatform_devagent.py`) is loaded as
 * the module [MODULE] and listens on 127.0.0.1 with that token, which must be at least 32 random bytes
 * (`secrets.token_urlsafe(32)`). A refused or malformed request is reported on stderr and does not stop the app.
 *
 * The marker is the build-time switch. This library is one artifact for an app's debug and release builds
 * alike, so a constant in it cannot tell them apart; the app's build can, and toolchain stages the marker
 * into a debug variant's payload only. Without it, a release build opens no port even if something starts
 * its process with the variable set: "a release launch never sets it" is the launcher's promise, which this
 * runtime does not rely on.
 */
internal object PythonDevAgent {
    const val ENVIRONMENT_VARIABLE = "PYTHON_MULTIPLATFORM_DEV_AGENT"
    const val MODULE = "python_multiplatform_devagent"
    const val ENABLE_MARKER = "python_multiplatform_devagent.enable"

    private const val PY_FILE_INPUT: Int = 257
    private var loaded = false
    private var requested = false

    fun startIfRequested() {
        if (requested || readEnvVar(ENVIRONMENT_VARIABLE).isNullOrBlank()) return
        requested = true
        if (enabledRoot() == null) {
            println(
                "WARNING: python-multiplatform dev agent not started: $ENVIRONMENT_VARIABLE is set, but no " +
                    "payload root holds $ENABLE_MARKER, so this is not a build made for it (a release build).",
            )
            return
        }
        try {
            load()
            Python3.exec("__import__(\"$MODULE\").start_from_env()")
        } catch (e: Exception) {
            println("WARNING: python-multiplatform dev agent not started: ${e.message}")
        }
    }

    /** The installed payload root that holds [ENABLE_MARKER], if any. */
    private fun enabledRoot(): String? =
        PythonPayload.installed.firstOrNull { pathIsAccessible("$it/$ENABLE_MARKER") }

    /** Loads [DEV_AGENT_SOURCE] as the module [MODULE] (once). */
    fun load() {
        if (loaded) return
        Python3.withPython {
            val code = Py_CompileString(DEV_AGENT_SOURCE, "<python-multiplatform:devagent>", PY_FILE_INPUT)
                ?: throw pyErrorOrGeneric("Failed to compile the dev agent")
            try {
                val module = PyImport_ExecCodeModule(MODULE, code)
                    ?: throw pyErrorOrGeneric("Failed to load the dev agent")
                Py_DecRef(module)
            } finally {
                Py_DecRef(code)
            }
        }
        loaded = true
    }
}
