package python.multiplatform.env

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * `Py_DecodeLocale` is documented by CPython as safe to call before initialisation and produces
 * storage that lives for the duration of the process, as `Py_SetPythonHome` requires.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("DEPRECATION")
internal actual fun setPythonHome(path: String) {
    val wide = python.native.ffi.bindings.Py_DecodeLocale(path, null)
        ?: throw IllegalStateException("Py_DecodeLocale could not decode the packaged Python home '$path'.")
    python.native.ffi.bindings.Py_SetPythonHome(wide)
}
