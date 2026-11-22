package python.native.ffi

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.rawValue
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import python.multiplatform.Versions

/**
 * `processSymbolAddress` on Windows (SPEC P-1a): Windows has no global symbol table, so the symbol is
 * looked up in the interpreter DLL this binary imports, `python315t.dll` for 3.15t.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun processSymbolAddress(name: String): Long {
    val dll = "python" + Versions.currentVersion.taggedVersionString.replace(".", "") + ".dll"
    val module = GetModuleHandleW(dll) ?: return 0L
    return GetProcAddress(module, name)?.rawValue?.toLong() ?: 0L
}
