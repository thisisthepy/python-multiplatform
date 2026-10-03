package python.multiplatform.ffi.utils

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.modules.PyModule
import python.native.ffi.PyDict_GetItemString
import python.native.ffi.PyImport_GetModuleDict
import python.native.ffi.PyImport_ReloadModule

/**
 * Import-related helpers that go beyond the single
 * [python.multiplatform.ffi.Python3.import] entry point (module reloading,
 * inspecting `sys.modules`).
 *
 * Backed by `PyImport_ReloadModule` and `PyImport_GetModuleDict`, both
 * present in `EmbedAPI` and both part of the Stable ABI.
 *
 * The `PyImport_AddModule` case named in the original sketch -- "adding a
 * module without fully importing it" -- is deliberately absent. That function
 * returns a **borrowed** reference to a module it creates *and registers in
 * `sys.modules`*, which is a mutation dressed as a lookup: exposing it beside
 * [getModuleOrNull] would give two similarly-named functions where one
 * silently installs a permanent, empty module under the name that was merely
 * being asked about. The one internal caller that genuinely needs it
 * (`__main__` bring-up) uses `PyImport_AddModuleRef` directly.
 */
object PyImport {
    /** `importlib.reload(module)`. */
    fun reload(module: PyModule): PyModule {
        // Read the name BEFORE the reload: it is only used in the failure message, and reading
        // it there would mean calling back into the C API with an exception already set, which
        // corrupts whatever runs next.
        val name = module.name
        return Python3.withPython {
            // PyImport_ReloadModule: new reference on success (to the *same* module object --
            // reload re-executes in place), null + exception set on failure.
            val reloaded = PyImport_ReloadModule(module.pointer)
                ?: throw pyErrorOrGeneric("Failed to reload module '$name'")
            PyModule(reloaded, borrowed = false)
        }
    }

    /** Looks up an already-imported module by name in `sys.modules`, without importing it. */
    fun getModuleOrNull(name: String): PyModule? = Python3.withPython {
        // PyImport_GetModuleDict returns a BORROWED reference to sys.modules.
        val modules = PyImport_GetModuleDict() ?: throw pyErrorOrGeneric("sys.modules is unavailable")
        // PyDict_GetItemString BORROWS as well, and returns null *without* setting the error
        // indicator for a missing key -- which is exactly the "not imported" answer here, so
        // there is nothing to clear.
        val module = PyDict_GetItemString(modules, name) ?: return@withPython null
        PyModule(module, borrowed = true)
    }

    /**
     * Whether [name] is currently present in `sys.modules`.
     *
     * Cheaper than `getModuleOrNull(name) != null`: the answer is a borrowed
     * pointer either way, and this never builds a wrapper (nor registers a
     * cleaner) just to throw it away.
     */
    fun isImported(name: String): Boolean = Python3.withPython {
        val modules = PyImport_GetModuleDict() ?: throw pyErrorOrGeneric("sys.modules is unavailable")
        PyDict_GetItemString(modules, name) != null
    }
}
