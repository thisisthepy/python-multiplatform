package python.multiplatform.env

/**
 * Points CPython at the prefix a packaged application ships, when there is one -- SPEC L-9.
 *
 * Called once by [python.multiplatform.ffi.Python3.initialize], after [PythonHomeCheck] and before
 * `Py_Initialize()`, which is the only window in which a home can still be chosen. Only desktop
 * does anything: a packaged desktop app is started by a launcher that cannot put `PYTHONHOME` in
 * its environment (see `desktopMain/README.md`). Android sets `PYTHONHOME` with `Os.setenv` in
 * `PythonBootstrap`, iOS and Android-native processes have a real environment, and wasm bakes its
 * stdlib into the virtual filesystem, so each of those is a no-op.
 *
 * Must throw, rather than continue, when the packaged prefix is unusable: the next call is
 * `Py_Initialize()`, which aborts the process instead of failing.
 */
internal expect fun applyPackagedPythonHome()
