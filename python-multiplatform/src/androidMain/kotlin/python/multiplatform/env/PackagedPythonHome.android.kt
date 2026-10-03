package python.multiplatform.env

/** No-op: `PythonBootstrap` stages the stdlib and sets `PYTHONHOME` with `Os.setenv`. */
internal actual fun applyPackagedPythonHome() = Unit
