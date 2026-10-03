package python.multiplatform.env

import java.io.File

internal actual fun readEnvVar(name: String): String? = System.getenv(name)

internal actual fun pathIsAccessible(path: String): Boolean =
    File(path).let { it.exists() && it.canRead() }
