package python.multiplatform.env

/** No-op: iOS and Android-native processes take `PYTHONHOME` from a real process environment. */
internal actual fun applyPackagedPythonHome() = Unit
