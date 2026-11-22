package python.native.ffi

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.rawValue

// `processSymbolAddress` on a POSIX target: `dlopen(null, RTLD_NOW)` hands back the process's global
// symbol table, into which libpython was linked, without loading anything new. Identical in every
// POSIX leaf (iOS, androidNative, macOS, Linux); Windows differs (`mingwX64Main`, SPEC P-1a).

@OptIn(ExperimentalForeignApi::class)
private val globalDlHandle: COpaquePointer? by lazy {
    platform.posix.dlopen(null, platform.posix.RTLD_NOW)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun processSymbolAddress(name: String): Long =
    platform.posix.dlsym(globalDlHandle, name)?.rawValue?.toLong() ?: 0L
