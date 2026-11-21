package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #216: a parameter typed as a functional interface takes a Python callable, as a function
 * type does (B-5).
 *
 * `CoroutineDispatcher.dispatch(context, block: Runnable)` is the motivating case: `runRunnable(block:
 * Runnable)` was declined ("no boundary type for parameter java.lang.Runnable", because the JDK is not
 * on the walk's classpath) while `runLambda(block: () -> Unit)` bound. The slot is described to Python
 * as the `kotlin.FunctionN` it is equivalent to, so the existing wrapper path fills it, and generated
 * source converts that wrapper with a SAM lambda -- nothing is looked up at run time.
 *
 * The slot takes a Kotlin handle too: its type name is the interface's, then `|`, then the function
 * type, and the generated read casts anything that is not a wrapper to the interface. A functional
 * interface whose method returns a value, or a generic one, is left exactly as it was -- Compose's
 * `Alignment` is a `fun interface` returning `IntOffset`, and its slots must keep taking handles.
 *
 * ### Red before the implementation
 *
 * `runRunnable`, `maybeRun` and `notifyListener` have no functional-interface slot (the first two are
 * declined, so `single` finds no callable; the third crosses as a plain handle). The guards -- `keep`,
 * `produce` and `drain` still crossing as handles -- pass before and after; a failure there is a
 * regression.
 */
class SamParameterBindingTest {

    private val fixtureClasses: File
        get() = File(Class.forName("fixture.artifactsam.Listener").protectionDomain.codeSource.location.toURI())

    private fun declarations() =
        ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactsam"))

    private fun callables() =
        ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactsam"))

    private val pkg = "fixture.artifactsam"

    @Test
    fun aJdkRunnableParameterIsAZeroArgumentFunctionSlot() {
        val run = callables().single { it.name == "$pkg.runRunnable" }
        assertEquals(listOf("java.lang.Runnable|kotlin.Function0()->kotlin.Unit"), run.paramTypeNames)
        assertEquals(listOf("OBJECT"), run.paramTags)
        assertTrue("java.lang.Runnable {" in run.lambdaBody, run.lambdaBody)
        // A Kotlin handle is cast to the interface rather than refused.
        assertTrue("else v as java.lang.Runnable" in run.lambdaBody, run.lambdaBody)
    }

    @Test
    fun aKotlinFunInterfaceParameterCarriesItsMethodsParameters() {
        val notify = callables().single { it.name == "$pkg.notifyListener" }
        assertEquals("$pkg.Listener|kotlin.Function2(kotlin.String,kotlin.Int)->kotlin.Unit", notify.paramTypeNames.first())
        assertTrue("$pkg.Listener {" in notify.lambdaBody, notify.lambdaBody)
    }

    @Test
    fun aNullableFunctionalInterfaceParameterStaysNullable() {
        val declined = declarations().single { it.simpleName == "maybeRun" }
        assertNull(declined.declineReason, declined.declineReason)
        val maybe = callables().single { it.name == "$pkg.maybeRun" }
        assertTrue("?.let" in maybe.lambdaBody, maybe.lambdaBody)
    }

    @Test
    fun theStubSeesACallable() {
        val parameter = declarations().single { it.bindingName == "$pkg.runRunnable" }.parameters.single()
        assertEquals("kotlin.Function0", parameter.type.qualifiedName)
        assertEquals(listOf("kotlin.Unit"), parameter.type.arguments.map { it?.qualifiedName })
    }

    @Test
    fun aValueReturningFunctionalInterfaceStillCrossesAsAHandle() {
        val produce = callables().single { it.name == "$pkg.produce" }
        assertEquals(listOf("$pkg.Producer"), produce.paramTypeNames)
    }

    @Test
    fun aGenericFunctionalInterfaceIsLeftToTheRulesBefore() {
        val drain = declarations().single { it.simpleName == "drain" }
        assertEquals(null, drain.bindingName)
        // The reason it had before #216: a parameterised interface does not cross as a handle either.
        assertEquals("no boundary type for parameter fixture.artifactsam.Sink", drain.declineReason)
    }

    @Test
    fun anOrdinaryInterfaceStillCrossesAsAHandle() {
        val keep = callables().single { it.name == "$pkg.keep" }
        assertEquals(listOf("$pkg.NotFunctional"), keep.paramTypeNames)
        assertTrue("as $pkg.NotFunctional" in keep.lambdaBody, keep.lambdaBody)
    }

    @Test
    fun aGenericSuspendFunctionalInterfaceBindsAsSuspendSamSlot() {
        val accept = callables().single { it.name == "$pkg.acceptCollector" }
        assertEquals(
            listOf("$pkg.AsyncCollector|kotlin.coroutines.SuspendFunction1(kotlin.Any)->kotlin.Unit"),
            accept.paramTypeNames,
        )
        assertTrue("if (v is kotlin.Function2<*, *, *>)" in accept.lambdaBody, accept.lambdaBody)
        assertTrue("$pkg.AsyncCollector<kotlin.Any?>" in accept.lambdaBody, accept.lambdaBody)
    }

    @Test
    fun aSuspendSamTypeParameterNestedInTheMethodSignatureIsSubstitutedAtAnyDepth() {
        // The same shape the KSP fixture `fixture.library.BatchSink` pins (review finding on #497):
        // the walker substitutes `T` inside `Batch<T>` from the use site, so the SAM lambda's
        // parameter type and the interface cast agree. The nested type is a fixture class rather
        // than `List<T>`: the walker never writes a mapped Kotlin collection into generated source
        // (`renderKotlinSourceType` needs a class file), so `List<T>` declines the whole slot.
        // Passes before and after that fix; it is here so the two generators are checked against one shape.
        val accept = callables().single { it.name == "$pkg.acceptBatches" }
        assertEquals(
            listOf("$pkg.BatchCollector|kotlin.coroutines.SuspendFunction1($pkg.Batch)->kotlin.Unit"),
            accept.paramTypeNames,
        )
        assertTrue("$pkg.BatchCollector<kotlin.String>" in accept.lambdaBody, accept.lambdaBody)
        assertTrue(
            "(suspend ($pkg.Batch<kotlin.String>) -> kotlin.Unit)" in accept.lambdaBody,
            accept.lambdaBody,
        )
    }
    /**
     * #164 item 4: a `java.*` parameter type is nameable although no walked jar holds its class file;
     * it is read from the JDK running the walk. Red before: `cancelWith` was declined ("not nameable"),
     * which is how `kotlinx.coroutines.Job.cancel` was missing from Python.
     */
    @Test
    fun aJdkClassParameterSpelledThroughATypealiasIsBound() {
        val cancel = callables().single { it.name == "$pkg.cancelWith" }
        assertEquals(listOf("java.util.concurrent.CancellationException"), cancel.paramTypeNames)
        assertTrue("as java.util.concurrent.CancellationException?" in cancel.lambdaBody, cancel.lambdaBody)
    }

    /** The JDK fallback is `java/` only: a `javax.*` parameter is still declined. Passes before and after. */
    @Test
    fun aJavaxClassParameterStaysDeclined() {
        assertTrue(callables().none { it.name == "$pkg.takeJavaxName" })
        val declined = declarations().single { it.simpleName == "takeJavaxName" }
        assertTrue(declined.declineReason != null, "takeJavaxName should be declined")
    }
}
