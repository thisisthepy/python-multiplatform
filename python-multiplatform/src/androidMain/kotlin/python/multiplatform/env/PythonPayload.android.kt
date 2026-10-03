package python.multiplatform.env

import java.io.File

/**
 * Android's answer to "where did the payload land": `assets/python/` in the APK.
 *
 * `toolchain`'s `stagePythonBundleAndroid` registers `build/pythonStaging/android` as an AGP asset
 * source root, so the payload merges into the APK's assets under `python/`. Like the standard
 * library that `PythonBootstrap.stageStdlib` unpacks out of `assets/<abi>/`, this is **not a
 * filesystem path**: `AssetManager` reads it out of the archive, and `Py_Initialize()`'s importer
 * opens modules with `open(2)`. So the same trick applies, and [PythonBootstrap.stagePayload] is
 * that trick with the same stamp discipline.
 *
 * ### Why discovery here is a registry rather than a scan
 *
 * Unpacking assets needs a `Context`, and `Python3.initialize()` has none — it is a `commonMain`
 * function on an object with no platform state. The two-step (stage, then register; discover, then
 * install) is what lets `PythonBootstrap.initialize(context)` do the part that needs a `Context`
 * before the part that does not. `PythonBootstrap` already sits in exactly that position for
 * `PYTHONHOME`: it stages, sets the variable, and only then calls `Python3.initialize()`.
 *
 * An app that brings CPython up without going through `PythonBootstrap` gets no payload, and that
 * is visible rather than silent: nothing on `sys.path`, `ModuleNotFoundError` on the first import
 * of its own package. There is no `Context`-free way to read an APK's assets, so the alternative
 * would be a `ContentProvider`-style ambient context grab, which is a worse thing to owe.
 */
internal actual fun discoverStagedPayloadRoots(): List<String> = AndroidPayloadRoots.registered()

/** The roots [PythonBootstrap.stagePayload] unpacked, in registration order. */
internal object AndroidPayloadRoots {

    private val roots = mutableListOf<String>()

    @Synchronized
    fun register(directory: File) {
        val path = directory.absolutePath
        if (!roots.contains(path)) roots.add(path)
    }

    @Synchronized
    fun registered(): List<String> = roots.toList()
}
