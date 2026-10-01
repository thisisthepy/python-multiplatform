package python.multiplatform.gradle.stubs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `docs/design/pyi-generation-design.md` §5.3: `androidx.` -> `pythonx.` is the default rule, deviations
 * come from a manifest, and **the manifest belongs to the Python package** rather than to this
 * plugin.
 *
 * §5.2 is the measurement that forces it: `pythonx.compose.layout` wraps
 * `androidx.compose.foundation.layout`, dropping `foundation.`, and nothing in the artefact says so.
 * Hard-coding Compose's renames into a general-purpose Gradle plugin would also make every other
 * library's mapping unreachable.
 *
 * **Not verified:** no such manifest exists in `pythonx-compose` today and this shape has not been
 * agreed with that repository (§5.3's own closing note, and §7). What is implemented here is the
 * reader, not an agreement.
 */
class StubManifestTest {

    private val text = """
        # pythonx-map.toml
        [modules]
        "pythonx.compose.layout"  = "androidx.compose.foundation.layout"
        "pythonx.compose.ui.unit" = "androidx.compose.ui.unit"

        [value-classes]
        raw-primitive-allowed = ["androidx.compose.ui.unit.Dp"]
    """.trimIndent()

    @Test
    fun aManifestMapsAPythonModuleOntoAKotlinPackage() {
        val manifest = parseStubManifest(text)
        assertEquals("pythonx.compose.layout", manifest.pythonModuleFor("androidx.compose.foundation.layout"))
        assertEquals("pythonx.compose.ui.unit", manifest.pythonModuleFor("androidx.compose.ui.unit"))
    }

    /** The rule, for everything the manifest does not name. §5.2's table: four of the six rows are
     * mechanical and only `layout` is not. */
    @Test
    fun theDefaultRuleRenamesTheLeadingAndroidxToPythonx() {
        val manifest = parseStubManifest(text)
        assertEquals("pythonx.compose.material3", manifest.pythonModuleFor("androidx.compose.material3"))
        assertEquals("pythonx.compose.runtime", manifest.pythonModuleFor("androidx.compose.runtime"))
    }

    /** A Kotlin package with no counterpart at all -- `junit.runner` is not something `pythonx`
     * wraps -- has no Pythonic module and must not be invented one. */
    @Test
    fun aPackageOutsideTheRuleAndOutsideTheManifestHasNoPythonicModule() {
        assertNull(parseStubManifest(text).pythonModuleFor("junit.runner"))
    }

    @Test
    fun theValueClassAllowlistIsRead() {
        assertEquals(setOf("androidx.compose.ui.unit.Dp"), parseStubManifest(text).rawPrimitiveValueClasses)
    }

    /**
     * §6.1 measurement 2: the marker goes in the top-level **regular** package of the distribution.
     * Derived as the longest common package prefix of the declared modules, because `pythonx` alone
     * is a namespace package (§5.1) and a marker there would be in the wrong directory.
     */
    @Test
    fun theDistributionRootIsTheCommonPrefixOfTheDeclaredModules() {
        assertEquals("pythonx.compose", parseStubManifest(text).distributionRoot)
    }

    /** An explicit root wins over the derivation. */
    @Test
    fun anExplicitDistributionRootIsHonoured() {
        val manifest = parseStubManifest(
            """
            [distribution]
            root = "pythonx.compose"

            [modules]
            "pythonx.compose.layout" = "androidx.compose.foundation.layout"
            "pythonx.other.thing" = "com.example.thing"
            """.trimIndent(),
        )
        assertEquals("pythonx.compose", manifest.distributionRoot)
    }

    /** No manifest file is not an error: §5.3 says the plugin then emits only the Kotlin-FQN stubs. */
    @Test
    fun anEmptyManifestYieldsNoMappingAtAll() {
        assertEquals(StubManifest.EMPTY, parseStubManifest(""))
        assertNull(StubManifest.EMPTY.pythonModuleFor("androidx.compose.ui"))
    }
}
