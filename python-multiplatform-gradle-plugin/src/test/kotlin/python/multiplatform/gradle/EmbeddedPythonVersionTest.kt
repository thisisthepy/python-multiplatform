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

    @Test
    fun nonConsumableConfigurationsAreLeftAlone() {
        val project = ProjectBuilder.builder().build()
        EmbeddedPythonVersion("3.14.7", false).register(project)
        val resolvable = project.configurations.create("someElements") { isCanBeConsumed = false }
        val other = project.configurations.create("implementation") { isCanBeConsumed = true }
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
