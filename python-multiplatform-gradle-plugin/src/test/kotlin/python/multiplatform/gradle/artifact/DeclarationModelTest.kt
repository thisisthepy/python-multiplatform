package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `docs/design/pyi-generation-design.md` §2.2's model, taken out of the same walk that produces the
 * bindings.
 *
 * ### Why this is a third representation and not a widened `ArtifactCallable`
 *
 * §2.2 rejects widening in place: `ArtifactCallable` is consumed by `renderArtifactFragmentSource`,
 * where every field is load-bearing, and a stub needs a strict superset of what a *call site* needs.
 * §2.1 lists what both existing models read and then drop -- parameter names, defaults, nullability,
 * the declared Kotlin type, the extension-receiver flag, and the overload grouping.
 *
 * `958c0082` had already put `paramNames`, `paramTypeNames`, `returnTypeName`, `isExtension`,
 * `receiverTypeName` and `paramHasDefault` on `ExposedCallable` for the *dispatcher*, so some of that
 * loss is already repaired -- but as flat `List<String>`, with no nullability, no type arguments and
 * no value-class identity, and only for declarations that were bound. Those three are exactly what a
 * stub cannot do without.
 *
 * ### One walk, not two
 *
 * §2.2 also rejects "a second scan, independent of the binder's" -- two readers of the same jars that
 * can disagree. So the model is built at the same point the binding is
 * (`ArtifactScanner.buildCallableFromFunction`), from the same `ResolvedFunction`, and
 * [ArtifactScanner.scanDeclarations] is the same walk with the other half of its result kept.
 */
class DeclarationModelTest {

    private val junitJar: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureJar")
            assertNotNull(path, "the walkerFixtureJar system property is not set; see build.gradle.kts")
            return File(path)
        }

    private val kotlinStdlibJar: File
        get() = File(Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location.toURI())

    private val fixtureClasses: File
        get() = File(Class.forName("fixture.artifactvalueclass.Meters").protectionDomain.codeSource.location.toURI())

    private fun fixtureDeclarations() =
        ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))

    /** Parameter names are what `FragmentScanner` and `ArtifactScanner` both read and drop
     * (§2.1). They are the difference between `def padding(all: Dp)` and `def padding(a0: float)`. */
    @Test
    fun aKotlinDeclarationKeepsItsParameterNames() {
        val sumMeters = fixtureDeclarations().single { it.simpleName == "sumMeters" }
        assertEquals(listOf("a", "b"), sumMeters.parameters.map { it.name })
        assertTrue(sumMeters.parameterNamesKnown)
        assertEquals("fixture.artifactvalueclass", sumMeters.owner)
        assertNull(sumMeters.receiver)
        assertEquals("fixture.artifactvalueclass.sumMeters", sumMeters.bindingName)
    }

    /** The declared Kotlin type, not the tag it marshals as: `Meters` is `TypeTag.FLOAT` and the
     * two are not the same fact. The value class's own identity -- underlying type and the two
     * visibility booleans `ValueClassInfo` already computes -- comes with it, which is what §3.4's
     * allowlist decision needs to be *expressible*. */
    @Test
    fun aValueClassParameterCarriesItsIdentityAndNotOnlyItsTag() {
        val sumMeters = fixtureDeclarations().single { it.simpleName == "sumMeters" }
        val first = sumMeters.parameters.first()
        assertEquals("fixture.artifactvalueclass.Meters", first.type.qualifiedName)
        assertEquals("FLOAT", first.boundaryTag, "the tag is still there -- the point is that it is not the only thing")
        val valueClass = assertNotNull(first.type.valueClass)
        assertEquals("kotlin.Double", valueClass.underlying.qualifiedName)
        assertTrue(valueClass.constructorIsPublic)
        assertTrue(valueClass.propertyIsPublic)
    }

    /** An extension receiver is `KmFunction.receiverParameterType` and is spent on choosing an
     * alias one line later (§2.1). Here it stays a receiver, which is what §4.1 needs to decide the
     * declaration is a *method on `Modifier`* rather than a module-level function. */
    @Test
    fun anExtensionReceiverStaysAReceiverAndIsNotAParameter() {
        val trimIndent = ArtifactScanner
            .scanDeclarations(kotlinStdlibJar, includePrefixes = listOf("kotlin.text"))
            .single { it.simpleName == "trimIndent" && it.parameters.isEmpty() }
        assertEquals("kotlin.String", trimIndent.receiver?.qualifiedName)
        assertEquals("kotlin.text", trimIndent.owner)
        assertEquals("kotlin.String", trimIndent.returnType.qualifiedName)
    }

    /** §3.2: a Java class file carries parameter names only when it was compiled with `-parameters`,
     * and JUnit 4 was not. The model says so rather than inventing `arg0`, because a wrong keyword
     * name type-checks at the call site and fails at run time. */
    @Test
    fun aJavaDeclarationReportsThatItHasNoParameterNames() {
        val setPreference = ArtifactScanner
            .scanDeclarations(junitJar, includePrefixes = listOf("junit.runner.BaseTestRunner"))
            .single { it.simpleName == "setPreference" }
        assertTrue(!setPreference.parameterNamesKnown)
        assertEquals(listOf(null, null), setPreference.parameters.map { it.name })
        assertEquals("junit.runner.BaseTestRunner", setPreference.owner)
        assertTrue(setPreference.ownerIsClass)
    }

    /**
     * §2.2 property 2: the binder drops an ambiguous name because it cannot dispatch, and a stub can
     * state all of them. Today those are the same line of code; here the model keeps every member of
     * the overload set, each carrying the table key the binder actually gave it.
     */
    @Test
    fun everyMemberOfAnOverloadSetSurvivesWithItsOwnTableKey() {
        val addMeters = fixtureDeclarations().filter { it.simpleName == "addMeters" }
        assertEquals(2, addMeters.size, addMeters.map { it.bindingName }.toString())
        assertEquals(
            setOf(
                "fixture.artifactvalueclass.addMeters__Int_Int",
                "fixture.artifactvalueclass.addMeters__Meters_Meters",
            ),
            addMeters.mapNotNull { it.bindingName }.toSet(),
        )
    }

    /**
     * §2.2 property 3: "a model entry can be marked *not bound, reason X* and still be stubbed -- or
     * deliberately not stubbed... Today a declined declaration returns `null` and vanishes."
     *
     * `withSuspendCallback` is the fixture for the function-typed shape that is **still** declined.
     * `withCallback` used to be -- the shape 44 of the 46 unbound `Modifier` extensions had -- and it
     * binds now (`FunctionSlotBindingTest`), so the two are asserted together: what moved and what
     * did not.
     */
    @Test
    fun aDeclinedDeclarationStaysVisibleWithItsReason() {
        val declined = fixtureDeclarations().single { it.simpleName == "withSuspendCallback" }
        assertNull(declined.bindingName, "the binder declined it, and that is the fact being kept")
        val reason = assertNotNull(declined.declineReason)
        assertTrue("suspend" in reason, reason)
        assertEquals("kotlin.Function1", declined.parameters.single().type.qualifiedName)

        // The half that moved. A plain `() -> Unit` binds now, and the *model* still spells its
        // parameter with the declared classifier: the signature grammar is the binding's answer
        // (`ArtifactCallable.paramTypeNames`), not the stub's.
        val bound = fixtureDeclarations().single { it.simpleName == "withCallback" }
        assertEquals("fixture.artifactvalueclass.withCallback", bound.bindingName)
        assertNull(bound.declineReason)
        assertEquals("kotlin.Function0", bound.parameters.single().type.qualifiedName)
    }

    /** `suspend` is declined by both producers and §3.1 says it must not be stubbed. It is still in
     * the model, flagged, so that "not stubbed" is a decision the renderer makes rather than an
     * absence nobody can account for. */
    @Test
    fun aSuspendDeclarationIsFlaggedRatherThanSilentlyAbsent() {
        val neverBound = fixtureDeclarations().singleOrNull { it.simpleName == "neverBound" }
        assertNotNull(neverBound, "suspend declarations must reach the model")
        assertTrue(neverBound.isSuspend)
        assertNull(neverBound.bindingName)
    }

    /** `internal` is not a name anybody outside the module may say, so it is not in the model at
     * all -- unlike a declined *public* declaration, there is nothing a consumer could be told. */
    @Test
    fun anInternalDeclarationIsNotInTheModelAtAll() {
        assertTrue(fixtureDeclarations().none { it.simpleName == "secretlyInternal" })
    }

    /** The two halves of one walk cannot disagree, because they are produced together: every table
     * key the binder emitted is a `bindingName` in the model, and nothing else is. */
    @Test
    fun theModelAndTheBindingsAgreeBecauseTheyComeFromTheSameWalk() {
        val bound = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        val modelled = fixtureDeclarations().mapNotNull { it.bindingName }
        assertEquals(bound.sorted(), modelled.sorted())
    }
}
