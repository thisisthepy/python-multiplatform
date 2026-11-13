package python.multiplatform.gradle

import org.gradle.api.Project

/**
 * Which CPython a published `python-multiplatform` build embeds (issue #61). Published three ways so
 * a consumer never has to guess:
 *
 *  - as the project extension `pythonMultiplatform` (composite builds),
 *  - as the Gradle attributes [ATTRIBUTE_VERSION] / [ATTRIBUTE_FREE_THREADED] on every consumable
 *    `*Elements` configuration (published Gradle module metadata),
 *  - as the classpath resource [RESOURCE_PATH] (read from the jar/AAR, no Gradle needed).
 *
 * One instance describes one build of CPython. A project whose platforms embed different builds
 * (issue #158: desktop 3.15t, Android and iOS the GIL 3.15 until #328) passes a per-variant
 * `freeThreadedFor` to [publishAttributes] / [register], so each published variant states the build
 * it really carries, and renders one resource per artefact.
 */
class EmbeddedPythonVersion(val pythonVersion: String, val freeThreaded: Boolean) {

    /** `"3.14"` for `"3.14.7"`. */
    val majorMinor: String
        get() {
            val parts = pythonVersion.split('.')
            require(parts.size >= 2) { "python version '$pythonVersion' has no major.minor" }
            return "${parts[0]}.${parts[1]}"
        }

    fun renderProperties(): String = "pythonVersion=$pythonVersion\nfreeThreaded=$freeThreaded\n"

    /**
     * Adds the attributes to every consumable `*Elements` configuration, including ones created later.
     *
     * [ATTRIBUTE_VERSION] is [pythonVersion] everywhere. [ATTRIBUTE_FREE_THREADED] is
     * `freeThreadedFor(configurationName)`: by default [freeThreaded] for every variant; a
     * multi-platform project passes a function that answers per variant, and `null` leaves the
     * attribute off a variant that carries no CPython of its own (shared metadata).
     */
    fun publishAttributes(project: Project, freeThreadedFor: (configurationName: String) -> Boolean? = { freeThreaded }) {
        project.configurations
            .matching { it.isCanBeConsumed && it.name.endsWith("Elements") }
            .configureEach {
                attributes.attribute(org.gradle.api.attributes.Attribute.of(ATTRIBUTE_VERSION, String::class.java), pythonVersion)
                val ft = freeThreadedFor(name) ?: return@configureEach
                attributes.attribute(
                    org.gradle.api.attributes.Attribute.of(ATTRIBUTE_FREE_THREADED, String::class.java),
                    ft.toString(),
                )
            }
    }

    fun register(project: Project, freeThreadedFor: (configurationName: String) -> Boolean? = { freeThreaded }) {
        project.extensions.add(EXTENSION_NAME, this)
        publishAttributes(project, freeThreadedFor)
    }

    companion object {
        const val EXTENSION_NAME = "pythonMultiplatform"
        const val ATTRIBUTE_VERSION = "org.thisisthepy.python.version"
        const val ATTRIBUTE_FREE_THREADED = "org.thisisthepy.python.free-threaded"
        const val RESOURCE_PATH = "META-INF/python-multiplatform/python.properties"

        fun parse(text: String): EmbeddedPythonVersion {
            val map = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            val version = requireNotNull(map["pythonVersion"]) { "python.properties has no pythonVersion" }
            return EmbeddedPythonVersion(version, map["freeThreaded"]?.toBoolean() ?: false)
        }
    }
}
