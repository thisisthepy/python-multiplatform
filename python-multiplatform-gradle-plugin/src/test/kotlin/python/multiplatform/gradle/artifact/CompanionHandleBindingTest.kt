package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #322 (SPEC U-4, B-1): a public companion object of a public class is bound as a value,
 * `Owner.Companion` (or `Owner.<CompanionName>`), by a zero-argument STATIC_GETTER whose generated
 * Kotlin source names the companion. Before the change the walker bound a companion's members only.
 */
class CompanionHandleBindingTest {

    private val fixtureClasses: File
        get() = File(Class.forName("fixture.artifactcompanion.Widget").protectionDomain.codeSource.location.toURI())

    private val pkg = "fixture.artifactcompanion"

    private fun candidates() = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf(pkg))

    private fun named(name: String) = candidates().singleOrNull { it.name == name }

    @Test
    fun aCompanionThatIsTheOwnersSubtypeIsBoundAsTheOwner() {
        val handle = assertNotNull(named("$pkg.Marker.Companion"))
        assertEquals("STATIC_GETTER", handle.kind)
        assertEquals(0, handle.arity)
        assertEquals("OBJECT", handle.returnTag)
        assertEquals("{ ($pkg.Marker.Companion) }", handle.lambdaBody)
        assertEquals("$pkg.Marker", handle.returnTypeName)
    }

    @Test
    fun aNamedCompanionIsBoundUnderItsName() {
        val handle = assertNotNull(named("$pkg.Widget.Factory"))
        assertEquals("STATIC_GETTER", handle.kind)
        assertEquals("{ ($pkg.Widget.Factory) }", handle.lambdaBody)
        assertNull(named("$pkg.Widget.Companion"))
    }

    @Test
    fun aCompanionThatIsNotTheOwnersSubtypeKeepsItsOwnType() {
        val handle = assertNotNull(named("$pkg.Widget.Factory"))
        assertEquals("$pkg.Widget.Factory", handle.returnTypeName)
        val holder = assertNotNull(named("$pkg.Holder.Companion"))
        assertEquals("$pkg.Holder.Companion", holder.returnTypeName)
    }

    @Test
    fun aGenericOwnersCompanionIsNamedWithoutTypeArguments() {
        val handle = assertNotNull(named("$pkg.Box.Companion"))
        assertEquals("{ ($pkg.Box.Companion) }", handle.lambdaBody)
        assertEquals("$pkg.Box", handle.returnTypeName)
    }

    @Test
    fun aNonPublicCompanionAndAClassWithoutOneBindNothing() {
        assertNull(named("$pkg.Hidden.Companion"))
        assertNull(named("$pkg.Plain.Companion"))
    }

    @Test
    fun theCompanionsConstantsAreStillBoundBesideTheHandle() {
        val default = assertNotNull(named("$pkg.Holder.Default"))
        assertEquals("{ ($pkg.Holder.Companion.Default) }", default.lambdaBody)
    }

    @Test
    fun theOwnersConstructorIsStillBoundBesideItsCompanionHandle() {
        // `Widget.Factory` starts with `Widget.`, which the constructor-collision rule would read as a
        // constant hiding the constructor. The handle is exempt, so the constructor survives.
        val constructor = assertNotNull(named("$pkg.Widget"))
        assertEquals(1, constructor.arity)
        assertTrue(named("$pkg.Widget.Factory") != null)
    }

    @Test
    fun onlyTheHandleIsFlaggedAsACompanionHandle() {
        val model = ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf(pkg))
        assertTrue(model.single { it.bindingName == "$pkg.Holder.Companion" }.isCompanionHandle)
        assertTrue(!model.single { it.bindingName == "$pkg.Holder.Default" }.isCompanionHandle)
    }
}
