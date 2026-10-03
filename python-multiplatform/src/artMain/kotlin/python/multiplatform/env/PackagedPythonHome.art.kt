package python.multiplatform.env

/**
 * No-op: androidNative is the Kotlin/Native half of an Android app, and `PythonBootstrap`
 * (`androidMain`) has already unpacked the stdlib and set `PYTHONHOME` with `Os.setenv` by the time
 * anything here runs.
 *
 * The `actual` lives here rather than in `nativeMain` because `nativeMain` is shared with iOS, where
 * the answer is a real lookup in the app bundle (SPEC L-10, `iosMain`) -- the same split
 * `discoverStagedPayloadRoots` already has.
 */
internal actual fun applyPackagedPythonHome() = Unit
