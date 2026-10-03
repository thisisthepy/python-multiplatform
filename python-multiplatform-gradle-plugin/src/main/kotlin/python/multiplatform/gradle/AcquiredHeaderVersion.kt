package python.multiplatform.gradle

import java.io.File

/**
 * Reads `PY_VERSION` out of the acquired CPython headers (`patchlevel.h`) and compares it with the
 * pinned `pythonVersion` (issue #47). The archive name says what a download was meant to be; the
 * header says what it is.
 */
object AcquiredHeaderVersion {
    private val pyVersion = Regex("""^\s*#\s*define\s+PY_VERSION\s+"([^"]+)"""", RegexOption.MULTILINE)

    fun parse(patchlevelText: String): String =
        requireNotNull(pyVersion.find(patchlevelText)) { "no PY_VERSION in patchlevel.h" }.groupValues[1]

    fun patchlevelFile(extractedRoot: File, target: String, pythonVersion: String): File =
        extractedRoot.resolve(CPythonIncludeLayout.relativePath(target, CPythonFlavour.GIL, pythonVersion)).resolve("patchlevel.h")

    /** target -> description, for every GIL target whose headers exist and report a different version. */
    fun mismatches(extractedRoot: File, pythonVersion: String): Map<String, String> =
        CPythonIncludeLayout.allTargets.mapNotNull { t ->
            val f = patchlevelFile(extractedRoot, t, pythonVersion)
            if (!f.isFile) return@mapNotNull null
            val actual = parse(f.readText())
            if (actual == pythonVersion) null else t to "headers say PY_VERSION $actual, expected $pythonVersion ($f)"
        }.toMap()

    fun requireAll(extractedRoot: File, pythonVersion: String) {
        val bad = mismatches(extractedRoot, pythonVersion)
        check(bad.isEmpty()) { "Acquired CPython does not match pythonVersion=$pythonVersion:\n" + bad.entries.joinToString("\n") { "  ${it.key}: ${it.value}" } }
    }
}
