package python.multiplatform.env

/**
 * androidNative has no packaging step to receive a payload from, so there is nothing to discover.
 *
 * This target is the Kotlin/Native half of Android: it is built into a `.so` that the JVM half
 * loads through JNI, and everything an Android app *ships* — assets included — is packaged by AGP
 * on the JVM side. `assets/python/` is therefore read by `androidMain`'s
 * [PythonBootstrap.stagePayload], which has the `Context` that reading an APK's assets requires,
 * and this side sees the result as a plain directory that is already on `sys.path` by the time any
 * code here runs.
 *
 * The `actual` lives here rather than in `nativeMain` because `nativeMain` is shared with iOS,
 * where the answer is a real lookup (see `iosMain`). An `actual` placed there would have made the
 * iOS one impossible to write without reopening the declaration.
 */
internal actual fun discoverStagedPayloadRoots(): List<String> = emptyList()
