package python.multiplatform.env

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.R_OK
import platform.posix.access
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun readEnvVar(name: String): String? = getenv(name)?.toKString()

/**
 * `access(path, R_OK)`, matching the JVM actual's `File.canRead()`: CPython's import machinery
 * needs to *read* the stdlib, not merely see that it exists, and a directory that exists but is
 * not readable (measured on desktop: `chmod 000`) is exactly the shape of misconfiguration this
 * check exists to turn into a clear exception instead of a raw `Py_FatalError` dump.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun pathIsAccessible(path: String): Boolean = access(path, R_OK) == 0
