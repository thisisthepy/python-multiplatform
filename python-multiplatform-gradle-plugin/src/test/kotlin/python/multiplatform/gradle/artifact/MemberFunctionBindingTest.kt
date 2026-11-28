package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #215: the public member functions of a class or interface are bound, as `METHOD` entries.
 *
 * Before it, the walker bound statics, an `object`'s functions (#53), constructors and properties
 * (#38), and nothing else on a class: `kotlinx.coroutines.Job.cancel()` and
 * `CoroutineDispatcher.dispatch(...)` were unreachable from a handle Python already held. A member
 * is the shape KSP's `METHOD` already has -- `args[0]` is the receiver, not counted in `arity` -- and
 * generated Kotlin source calls it as `(args[0] as Owner).name(...)`; nothing here looks a JVM method
 * up by name.
 *
 * ### Red before the implementation, and what each red says
 *
 * Against the walker before #215 every "is bound" test fails with no declaration of that name
 * (`single` throws, or the binding name is `null`); the guards that pin what stays (the constructor,
 * the property, the object's static) pass both before and after, and a failure there is a regression.
 */
class MemberFunctionBindingTest {

    private val fixtureClasses: File
        get() = File(Class.forName("fixture.artifactmember.Counter").protectionDomain.codeSource.location.toURI())

    private fun declarations() =
        ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactmember"))

    private fun callables() =
        ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactmember"))

    private val counter = "fixture.artifactmember.Counter"
    private val handle = "fixture.artifactmember.Handle"

    @Test
    fun aClassMemberIsBoundAsAMethodOfItsOwner() {
        val reset = declarations().single { it.simpleName == "reset" }
        assertEquals("$counter.reset", reset.bindingName)
        assertEquals("METHOD", reset.kind)
        assertEquals(counter, reset.owner)
        assertTrue(reset.ownerIsClass)
        assertEquals(counter, reset.receiver?.qualifiedName)
        assertTrue(reset.parameters.isEmpty())
    }

    @Test
    fun theTableEntryCarriesTheReceiverOutsideItsSlots() {
        val stop = callables().single { it.name == "$handle.stop" }
        assertEquals("METHOD", stop.kind)
        assertEquals(0, stop.arity)
        assertEquals(handle, stop.receiverTypeName)
        assertTrue("(args[0] as $handle).stop()" in stop.lambdaBody, stop.lambdaBody)
        // A zero-parameter member still reads its receiver: the body must take `args`.
        assertTrue(stop.lambdaBody.startsWith("{ args ->"), stop.lambdaBody)
        val follows = callables().single { it.name == "$handle.follows" }
        assertEquals(1, follows.arity)
        assertEquals(listOf("other"), follows.paramNames)
        assertEquals(listOf(handle), follows.paramTypeNames)
        assertTrue("(args[0] as $handle).follows(" in follows.lambdaBody, follows.lambdaBody)
        assertTrue("args[1]" in follows.lambdaBody, follows.lambdaBody)
    }

    @Test
    fun anInterfacesMembersAreBound() {
        val bound = declarations().filter { it.owner == handle && it.kind == "METHOD" }.mapNotNull { it.bindingName }.sorted()
        assertEquals(listOf("$handle.follows", "$handle.stop"), bound)
    }

    @Test
    fun theOverloadsOfAMemberAreToldApart() {
        val bound = declarations().filter { it.simpleName == "add" }.mapNotNull { it.bindingName }.sorted()
        assertEquals(listOf("$counter.add__Int", "$counter.add__Int_String"), bound)
    }

    /**
     * A member's default is omittable as a function's is (SPEC B-5b, #164 item 4: `Job.cancel()`).
     * Red before: `memberFunctionCandidates` built no omission plan for a member, so `paramHasDefault`
     * was `[false]` and Python's `job.cancel()` failed with "no value for cause".
     */
    @Test
    fun aMembersDefaultIsOmittable() {
        val scaled = callables().single { it.name == "$counter.scaled" }
        assertEquals(listOf(true), scaled.paramHasDefault)
        assertTrue(declarations().single { it.bindingName == "$counter.scaled" }.parameters.single().declaresDefault)
        // The sentinel test reads the argument's slot, which follows the receiver's `args[0]`.
        assertTrue("args[1] == null" in scaled.lambdaBody, scaled.lambdaBody)
        assertTrue("(args[0] as $counter).scaled()" in scaled.lambdaBody, scaled.lambdaBody)
    }

    @Test
    fun anInterfaceMembersDefaultIsOmittable() {
        val stop = callables().single { it.name == "fixture.artifactmember.Stoppable.stop" }
        assertEquals(listOf(true), stop.paramHasDefault)
        assertTrue("(args[0] as fixture.artifactmember.Stoppable).stop()" in stop.lambdaBody, stop.lambdaBody)
    }

    /** A call leaving `label` out could resolve against the inherited `ping(times = 1)` as well. */
    @Test
    fun aMemberThatAnInheritedOverloadWouldAlsoAcceptKeepsEveryArgument() {
        val derived = callables().single { it.name == "fixture.artifactmember.DerivedPing.ping" }
        assertEquals(listOf(false), derived.paramHasDefault)
        assertFalse("== null" in derived.lambdaBody, derived.lambdaBody)
        val base = callables().single { it.name == "fixture.artifactmember.BasePing.ping" }
        assertEquals(listOf(true), base.paramHasDefault, "the base class's own member has no inherited twin")
    }

    /**
     * Batch run 44945907475: `AbsoluteCutCornerShape.copy` required every argument while
     * `CornerBasedShape.copy` offered `= ...`, and mypy rejected the override. Kotlin gives an override
     * the overridden declaration's defaults; the inherited `reshape(all)` names no `start`/`end`, so it
     * refuses no omission. Red before: `[false, false]`.
     */
    @Test
    fun anOverrideTakesTheDefaultsOfTheDeclarationItOverrides() {
        val square = callables().single { it.name == "fixture.artifactmember.Square.reshape" }
        assertEquals(listOf(true, true), square.paramHasDefault)
        assertTrue("(args[0] as fixture.artifactmember.Square).reshape()" in square.lambdaBody, square.lambdaBody)
        val stub = declarations().single { it.owner == "fixture.artifactmember.Square" && it.simpleName == "reshape" }
        assertEquals(listOf(true, true), stub.parameters.map { it.declaresDefault })
    }

    /**
     * Batch run 44945907475: `SnackbarHostState.showSnackbar`'s partial calls were generated outside
     * the pending call, so `kotlinc` refused a suspend call from a non-suspend lambda. Red before: the
     * branched body had no `PendingCall.start`.
     */
    @Test
    fun aSuspendMembersOmittingCallsRunInsideThePendingCall() {
        val pause = callables().single { it.name == "fixture.artifactmember.Waiter.pause" }
        assertEquals(listOf(true), pause.paramHasDefault)
        assertTrue(pause.lambdaBody.startsWith("{ args -> python.multiplatform.ffi.upcall.PendingCall.start {"), pause.lambdaBody)
        assertTrue("(args[0] as fixture.artifactmember.Waiter).pause()" in pause.lambdaBody, pause.lambdaBody)
    }

    @Test
    fun aSuspendMemberIsBoundWithPendingCall() {
        val laterDecl = declarations().single { it.simpleName == "later" }
        assertEquals("$counter.later", laterDecl.bindingName)
        assertTrue(laterDecl.isSuspend)
        val later = callables().single { it.name == "$counter.later" }
        assertEquals("METHOD", later.kind)
        assertTrue(later.isSuspend)
        assertTrue("PendingCall.start" in later.lambdaBody, later.lambdaBody)
        val rendered = renderArtifactFragmentSource(ArtifactFragment("SuspendMemberFragment", "fixture:artifactmember:1.0", listOf(later)))
        assertTrue("isSuspend = true" in rendered, rendered)
    }

    @Test
    fun aMemberOfAnUnboundedOrBoundedGenericClassIsBound() {
        val describe = declarations().single { it.owner == "fixture.artifactmember.Box" && it.simpleName == "describe" }
        assertEquals("fixture.artifactmember.Box.describe", describe.bindingName)
        val swap = callables().single { it.name == "fixture.artifactmember.Box.swap" }
        assertEquals("METHOD", swap.kind)
        assertTrue("fixture.artifactmember.Box<kotlin.Any?>" in swap.lambdaBody, swap.lambdaBody)

        val replace = callables().single { it.name == "fixture.artifactmember.NumberBox.replace" }
        assertEquals("METHOD", replace.kind)
        assertTrue("fixture.artifactmember.NumberBox<kotlin.Number>" in replace.lambdaBody, replace.lambdaBody)
        assertTrue("(args[1] as kotlin.Number)" in replace.lambdaBody, replace.lambdaBody)

        val numberSetter = callables().single { it.name == "fixture.artifactmember.NumberBox.value=" }
        assertEquals("SETTER", numberSetter.kind)
        assertTrue("fixture.artifactmember.NumberBox<kotlin.Number>" in numberSetter.lambdaBody, numberSetter.lambdaBody)
    }

    @Test
    fun aMemberOfAGenericClassWithAnUnsupportedBoundIsDeclinedWithItsReason() {
        val describe = declarations().single { it.simpleName == "describeComparable" }
        assertNull(describe.bindingName)
        assertNotNull(describe.declineReason)
        assertTrue("generic" in describe.declineReason!!, describe.declineReason)
    }

    @Test
    fun aMemberNamedLikeAPropertyIsDeclinedAndThePropertyKept() {
        val sized = declarations().filter { it.owner == "fixture.artifactmember.Sized" && it.simpleName == "size" }
        assertEquals("GETTER", sized.single { it.bindingName != null }.kind)
        val method = sized.single { it.kind == "METHOD" }
        assertNull(method.bindingName)
        assertTrue("property" in method.declineReason!!, method.declineReason)
    }

    @Test
    fun anyMembersAreNotBound() {
        assertFalse(declarations().any { it.simpleName in setOf("toString", "equals", "hashCode") && it.bindingName != null })
    }

    @Test
    fun theClassesConstructorIsStillBound() {
        val constructor = declarations().single { it.isConstructor && it.simpleName == "Counter" }
        assertEquals(counter, constructor.bindingName)
    }

    @Test
    fun thePropertiesAreStillBound() {
        assertEquals("$counter.count", declarations().single { it.simpleName == "count" && it.kind == "GETTER" }.bindingName)
        assertEquals("$handle.active", declarations().single { it.owner == handle && it.simpleName == "active" }.bindingName)
    }

    @Test
    fun anObjectsFunctionStaysAStaticUnderTheObjectsName() {
        val lookup = declarations().single { it.simpleName == "lookup" }
        assertEquals("FUNCTION", lookup.kind)
        assertEquals("fixture.artifactmember.Registry.lookup", lookup.bindingName)
    }
}
