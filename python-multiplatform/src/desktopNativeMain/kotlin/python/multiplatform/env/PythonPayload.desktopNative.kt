package python.multiplatform.env

/**
 * Desktop Kotlin/Native's answer to "where did the payload land": `python/` beside the executable
 * or, in a macOS `.app` bundle, under `../Resources/python`.
 */
internal actual fun discoverStagedPayloadRoots(): List<String> =
    discoverStagedPayloadRoots(executableDirectory(), ::pathIsAccessible)

internal fun discoverStagedPayloadRoots(
    executableDir: String?,
    isAccessible: (String) -> Boolean,
): List<String> {
    val dir = executableDir?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: return emptyList()
    val roots = mutableListOf<String>()
    val beside = "$dir/${PythonPayload.PAYLOAD_ROOT}"
    if (isAccessible(beside)) roots += beside
    val parent = dir.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isNotEmpty()) {
        val resources = "$parent/Resources/${PythonPayload.PAYLOAD_ROOT}"
        if (resources != beside && isAccessible(resources)) roots += resources
    }
    return roots
}
