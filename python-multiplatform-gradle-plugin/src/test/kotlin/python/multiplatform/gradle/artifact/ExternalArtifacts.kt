package python.multiplatform.gradle.artifact

import java.io.File

/**
 * Finds third-party jars in the Gradle cache for tests that scan real Compose artifacts, and turns
 * a missing one into a reported skip (AGENTS.md 17.5: a test that returns without asserting passes
 * when it cannot fail).
 *
 * The version is the one pinned as `compose-libraries` in `gradle/libs.versions.toml`. Only when
 * that version is absent from the cache does the lookup fall back to the first jar the cache walk
 * finds, and it prints a note saying so.
 */
internal object ExternalArtifacts {

    val caches: File? = listOf(
        File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
        File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
    ).firstOrNull { it.isDirectory }

    /** `compose-libraries` from `gradle/libs.versions.toml`, or null when the catalog cannot be found. */
    val pinnedComposeVersion: String? by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "gradle/libs.versions.toml") }
            .firstOrNull { it.isFile }
            ?.readLines()
            ?.firstNotNullOfOrNull { Regex("""^compose-libraries\s*=\s*"([^"]+)"""").find(it.trim())?.groupValues?.get(1) }
    }

    private fun jarsIn(dir: File): Sequence<File> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

    /**
     * The jar of [artifact] under [group] (or its `org.thisisthepy.compose` twin), preferring the pinned
     * version; null when the cache has no such jar at all.
     */
    fun jarUnder(group: String, artifact: String): File? {
        val dirs = listOf(
            group,
            group.replaceFirst("org.jetbrains.compose", "org.thisisthepy.compose"),
        ).mapNotNull { g -> caches?.resolve(g)?.resolve(artifact)?.takeIf { it.isDirectory } }
        val pinned = pinnedComposeVersion
        if (pinned != null) {
            dirs.firstNotNullOfOrNull { jarsIn(it.resolve(pinned)).firstOrNull() }?.let { return it }
        }
        val fallback = dirs.firstNotNullOfOrNull { jarsIn(it).firstOrNull() } ?: return null
        System.err.println("NOTE: $artifact at pinned version $pinned is not in the Gradle cache; using $fallback")
        return fallback
    }

    /** Reports the calling test as skipped (never passed) and does not return. */
    fun skip(reason: String): Nothing {
        System.err.println("SKIPPED: $reason")
        org.junit.jupiter.api.Assumptions.assumeTrue(false, reason)
        error("unreachable: assumption failed")
    }
}
