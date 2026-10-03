package python.multiplatform.env

import platform.Foundation.NSBundle

/**
 * iOS's answer to "where did the payload land": `python/` inside the app bundle's resource
 * directory.
 *
 * `toolchain` produces the payload (`build/pythonStaging/ios/python`, or the app's own sources);
 * `tools/xcode/install-python.sh`, an Xcode Run Script phase, copies it there and wraps any
 * extension module in it as a framework (SPEC L-11, `docs/platforms/ios-app-bundle.md`). An app
 * built without that phase has no `python/`, and this returns an empty list.
 *
 * A payload read out of the app's own bundle is also what avoids the external-volume hang
 * `iosMain/README.md` records for a workspace path.
 *
 * ### `resourcePath`, not `bundlePath`
 *
 * They are the same directory on iOS and different on macOS (`Contents/Resources`). Asking for the
 * resource path is what a Copy Bundle Resources phase actually fills, on either.
 */
internal actual fun discoverStagedPayloadRoots(): List<String> {
    val resources = NSBundle.mainBundle.resourcePath ?: return emptyList()
    val candidate = "$resources/${PythonPayload.PAYLOAD_ROOT}"
    // pathIsAccessible is nativeMain's access(path, R_OK) -- the same probe PythonHomeCheck uses,
    // so "readable" means the same thing on both paths.
    return if (pathIsAccessible(candidate)) listOf(candidate) else emptyList()
}
