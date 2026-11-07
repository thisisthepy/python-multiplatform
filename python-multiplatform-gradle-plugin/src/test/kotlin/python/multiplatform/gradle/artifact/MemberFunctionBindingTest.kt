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

    @Test
    fun aMembersDefaultIsNotOmittableYet() {
        val scaled = callables().single { it.name == "$counter.scaled" }
        assertEquals(listOf(false), scaled.paramHasDefault)
        assertFalse(declarations().single { it.bindingName == "$counter.scaled" }.parameters.single().declaresDefault)
    }

    @Test
    fun aSuspendMemberIsDeclinedWithItsReason() {
        val later = declarations().single { it.simpleName == "later" }
        assertNull(later.bindingName)
        assertEquals("suspend", later.declineReason)
    }

    @Test
    fun aMemberOfAGenericClassIsDeclinedWithItsReason() {
        val describe = declarations().single { it.simpleName == "describe" }
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
