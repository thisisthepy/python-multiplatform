package python.multiplatform.gradle

import org.gradle.api.attributes.Attribute
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Issue #61: the embedded CPython version as extension, attributes and resource. */
class EmbeddedPythonVersionTest {

    private fun attr(name: String) = Attribute.of(name, String::class.java)

    @Test
    fun propertiesRoundTrip() {
        val v = EmbeddedPythonVersion("3.14.7", true)
        val back = EmbeddedPythonVersion.parse(v.renderProperties())
        assertEquals("3.14.7", back.pythonVersion)
        assertEquals(true, back.freeThreaded)
        assertEquals("pythonVersion=3.14.7\nfreeThreaded=true\n", v.renderProperties())
    }

    @Test
    fun keysAreStable() {
        assertEquals("org.thisisthepy.python.version", EmbeddedPythonVersion.ATTRIBUTE_VERSION)
        assertEquals("org.thisisthepy.python.free-threaded", EmbeddedPythonVersion.ATTRIBUTE_FREE_THREADED)
        assertEquals("META-INF/python-multiplatform/python.properties", EmbeddedPythonVersion.RESOURCE_PATH)
    }

    @Test
    fun parseRejectsMissingVersion() {
        assertFailsWith<IllegalArgumentException> { EmbeddedPythonVersion.parse("freeThreaded=false\n") }
    }

    @Test
    fun majorMinor() {
        assertEquals("3.14", EmbeddedPythonVersion("3.14.7", false).majorMinor)
        assertFailsWith<IllegalArgumentException> { EmbeddedPythonVersion("3", false).majorMinor }
    }

    @Test
    fun attributesLandOnConsumableElementsIncludingLaterOnes() {
        val project = ProjectBuilder.builder().build()
        val early = project.configurations.create("apiElements") { isCanBeConsumed = true }
        EmbeddedPythonVersion("3.14.7", false).register(project)
        val late = project.configurations.create("desktopRuntimeElements") { isCanBeConsumed = true }
        for (c in listOf(early, late)) {
            assertEquals("3.14.7", c.attributes.getAttribute(attr(EmbeddedPythonVersion.ATTRIBUTE_VERSION)))
            assertEquals("false", c.attributes.getAttribute(attr(EmbeddedPythonVersion.ATTRIBUTE_FREE_THREADED)))
        }
    }

    /**
     * Issue #158: desktop embeds 3.15t while Android and iOS embed the GIL 3.15 until #328, so the
     * free-threaded attribute is decided per variant. Red before: `register` took no per-variant
     * function and stamped one value on every variant, so the AAR's variants claimed what the
     * desktop jar's did (this test then does not compile, which is the pre-implementation state).
     */
    @Test
    fun freeThreadedIsDecidedPerVariant() {
        val project = ProjectBuilder.builder().build()
        val names = listOf("desktopRuntimeElements", "releaseRuntimeElements", "iosArm64ApiElements", "metadataApiElements")
        val configs = names.associateWith { n -> project.configurations.create(n) { isCanBeConsumed = true } }
        EmbeddedPythonVersion("3.15.0", true).register(project) { name ->
            when {
                name.startsWith("metadata") -> null
                name.startsWith("desktop") -> true
                else -> false
            }
        }
        val ft = attr(EmbeddedPythonVersion.ATTRIBUTE_FREE_THREADED)
        assertEquals("true", configs.getValue("desktopRuntimeElements").attributes.getAttribute(ft))
        assertEquals("false", configs.getValue("releaseRuntimeElements").attributes.getAttribute(ft))
        assertEquals("false", configs.getValue("iosArm64ApiElements").attributes.getAttribute(ft))
        assertNull(configs.getValue("metadataApiElements").attributes.getAttribute(ft))
        for (c in configs.values) {
            assertEquals("3.15.0", c.attributes.getAttribute(attr(EmbeddedPythonVersion.ATTRIBUTE_VERSION)))
        }
    }

    @Test
    fun nonConsumableConfigurationsAreLeftAlone() {
        val project = ProjectBuilder.builder().build()
        EmbeddedPythonVersion("3.14.7", false).register(project)
        // Only consumable `*Elements` configurations carry the attributes. Gradle's `create(name) {}`
        // adds the configuration before running its block, so the decision is taken with the
        // defaults; a configuration that is not named `*Elements` is never touched. (Every KMP/AGP
        // `*Elements` configuration is consumable.)
        val other = project.configurations.create("implementation") { isCanBeConsumed = true }
        val resolvable = project.configurations.create("compileClasspath") { isCanBeConsumed = false }
        assertNull(resolvable.attributes.getAttribute(attr(EmbeddedPythonVersion.ATTRIBUTE_VERSION)))
        assertNull(other.attributes.getAttribute(attr(EmbeddedPythonVersion.ATTRIBUTE_VERSION)))
    }

    @Test
    fun extensionIsRegisteredUnderItsName() {
        val project = ProjectBuilder.builder().build()
        val v = EmbeddedPythonVersion("3.14.7", false)
        v.register(project)
        assertEquals("pythonMultiplatform", EmbeddedPythonVersion.EXTENSION_NAME)
        assertSame(v, project.extensions.getByName("pythonMultiplatform"))
    }
}
