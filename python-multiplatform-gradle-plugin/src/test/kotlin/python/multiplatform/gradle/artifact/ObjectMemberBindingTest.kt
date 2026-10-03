package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #53: the functions of a Kotlin `object` are bound, not only its constants.
 *
 * `Arrangement.spacedBy` is an instance method of the `Arrangement` singleton -- `INSTANCE.spacedBy`
 * in bytecode -- so a walk that looked only at `ACC_STATIC` methods bound `Arrangement.End` and never
 * `Arrangement.spacedBy`, and the stub (which renders what is bound) could not carry it. Generated
 * Kotlin source calls it as `Arrangement.spacedBy(x)`, which the compiler lowers and mangles; nothing
 * here looks a JVM method up by name.
 */
class ObjectMemberBindingTest {

    private val fixtureClasses: File
        get() = File(Class.forName("fixture.artifactobject.Gap").protectionDomain.codeSource.location.toURI())

    private fun declarations() =
        ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactobject"))

    private val spacing = "fixture.artifactobject.Spacing"

    @Test
    fun anObjectFunctionIsBoundUnderTheObjectsName() {
        val describe = declarations().single { it.simpleName == "describe" }
        assertEquals("$spacing.describe", describe.bindingName)
        assertEquals(spacing, describe.owner)
        assertTrue(describe.ownerIsClass)
        assertEquals("FUNCTION", describe.kind)
        assertEquals("fixture.artifactobject.Gap", describe.parameters.single().type.qualifiedName)
        assertEquals("kotlin.String", describe.returnType.qualifiedName)
    }

    @Test
    fun theOverloadsOfAnObjectFunctionAreToldApart() {
        val bound = declarations().filter { it.simpleName == "spacedBy" }.mapNotNull { it.bindingName }.sorted()
        assertEquals(listOf("$spacing.spacedBy__Int", "$spacing.spacedBy__Int_String"), bound)
    }

    @Test
    fun theObjectsConstantIsStillBound() {
        val tight = declarations().single { it.simpleName == "Tight" }
        assertEquals("STATIC_GETTER", tight.kind)
        assertEquals("$spacing.Tight", tight.bindingName)
    }

    @Test
    fun anObjectsToStringOverrideIsNotBound() {
        assertFalse(declarations().any { it.simpleName == "toString" && it.bindingName != null })
    }

    @Test
    fun aJvmStaticObjectMemberIsBoundOnce() {
        val twice = declarations().filter { it.simpleName == "twice" }
        assertEquals(listOf("fixture.artifactobject.StaticSpacing.twice"), twice.mapNotNull { it.bindingName })
    }

    @Test
    fun anOrdinaryClassInstanceMethodIsStillNotBound() {
        assertTrue(declarations().none { it.simpleName == "instanceMethod" && it.bindingName != null })
    }
}
