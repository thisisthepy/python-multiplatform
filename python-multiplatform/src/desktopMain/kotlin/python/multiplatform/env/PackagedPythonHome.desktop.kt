package python.multiplatform.env

import python.multiplatform.OSType
import python.multiplatform.currentPlatform
import python.native.ffi.Panama
import python.native.ffi.bindings
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * Desktop's half of SPEC L-9: hand a packaged prefix to CPython with `Py_SetPythonHome`.
 *
 * ### Why in-process, when `desktopMain/README.md` says `PYTHONHOME` is set as the process starts
 *
 * That rule is about `PYTHONHOME` the *environment variable*: a JVM cannot set one for itself, so
 * the Gradle plugin sets it on `run`/`test`. A packaged application has no Gradle to do that, and
 * its launcher (jpackage, and Compose Desktop's tasks on top of it) takes `--java-options`, not
 * environment variables. `Py_SetPythonHome` is not an environment variable at all: it writes the
 * home straight into CPython's path configuration, which `Py_Initialize()` reads ahead of `getenv`
 * whenever the variable is unset. So nothing is set that `PythonHomeCheck` could fail to see --
 * the prefix is checked here, on the same value CPython is given, immediately before giving it.
 *
 * ### The buffer is never freed
 *
 * The C API asks for a string "in static storage whose contents will not change for the duration
 * of the program's execution". Current CPython copies it, but that is an implementation detail the
 * documentation does not promise, so the few hundred bytes stay allocated for the life of the
 * process. This runs once per process (`Python3.initialize` reaches it only before the first
 * `Py_Initialize`).
 */
internal actual fun applyPackagedPythonHome() {
    val home = PackagedPythonHome.resolve() ?: return
    try {
        PythonHomeCheck.verifyOrThrow(home.path)
    } catch (e: IllegalStateException) {
        throw IllegalStateException(
            "The Python home this application was packaged with (${home.source}) is unusable: ${e.message}",
            e,
        )
    }
    val wide = encodeWideString(home.path, windows = currentPlatform.os == OSType.Windows)
    bindings.Py_SetPythonHome(Panama.allocateBytesFreeable(wide))
}

/**
 * [value] as a NUL-terminated C `wchar_t` string: UTF-16 (2-byte terminator) on Windows, UTF-32
 * (4-byte terminator) on macOS and Linux, in [order].
 *
 * Encoded by hand rather than through `Py_DecodeLocale`, which before CPython's pre-initialisation
 * decodes with the C locale's encoding -- the ANSI code page on Windows -- and would turn a
 * non-ASCII install path (`C:\Users\José\...`) into a different one. UTF-32 is written code point
 * by code point rather than through the `UTF-32` charset, which a GraalVM native image does not
 * include by default.
 */
internal fun encodeWideString(value: String, windows: Boolean, order: ByteOrder = ByteOrder.nativeOrder()): ByteArray {
    if (windows) {
        val charset = if (order == ByteOrder.LITTLE_ENDIAN) Charsets.UTF_16LE else Charsets.UTF_16BE
        return value.toByteArray(charset) + ByteArray(2)
    }
    val out = ByteArrayOutputStream((value.length + 1) * 4)
    val writeUnit = { unit: Int ->
        if (order == ByteOrder.LITTLE_ENDIAN) {
            out.write(unit and 0xFF); out.write(unit ushr 8 and 0xFF)
            out.write(unit ushr 16 and 0xFF); out.write(unit ushr 24 and 0xFF)
        } else {
            out.write(unit ushr 24 and 0xFF); out.write(unit ushr 16 and 0xFF)
            out.write(unit ushr 8 and 0xFF); out.write(unit and 0xFF)
        }
    }
    value.codePoints().forEach { writeUnit(it) }
    writeUnit(0)
    return out.toByteArray()
}
