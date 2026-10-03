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

    /** Adds the two attributes to every consumable `*Elements` configuration, including ones created later. */
    fun publishAttributes(project: Project) {
        project.configurations
            .matching { it.isCanBeConsumed && it.name.endsWith("Elements") }
            .configureEach {
                attributes.attribute(org.gradle.api.attributes.Attribute.of(ATTRIBUTE_VERSION, String::class.java), pythonVersion)
                attributes.attribute(
                    org.gradle.api.attributes.Attribute.of(ATTRIBUTE_FREE_THREADED, String::class.java),
                    freeThreaded.toString(),
                )
            }
    }

    fun register(project: Project) {
        project.extensions.add(EXTENSION_NAME, this)
        publishAttributes(project)
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
