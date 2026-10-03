package python.multiplatform.env

/**
 * Points CPython at the prefix a packaged application ships, when there is one -- SPEC L-9
 * (desktop) and L-11 (iOS).
 *
 * Called once by [python.multiplatform.ffi.Python3.initialize], after [PythonHomeCheck] and before
 * `Py_Initialize()`, which is the only window in which a home can still be chosen. Desktop and iOS
 * do something: a packaged desktop app is started by a launcher that cannot put `PYTHONHOME` in its
 * environment (see `desktopMain/README.md`), and an installed iOS app is started with none while its
 * stdlib sits in its own bundle (`iosMain/README.md`). Android sets `PYTHONHOME` with `Os.setenv` in
 * `PythonBootstrap` (and androidNative runs inside that process), and wasm bakes its stdlib into the
 * virtual filesystem, so each of those is a no-op.
 *
 * Must throw, rather than continue, when the packaged prefix is unusable: the next call is
 * `Py_Initialize()`, which aborts the process instead of failing.
 */
internal expect fun applyPackagedPythonHome()
