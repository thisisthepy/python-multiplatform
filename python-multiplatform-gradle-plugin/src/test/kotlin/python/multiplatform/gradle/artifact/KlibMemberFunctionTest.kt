package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #237: the klib walker binds member functions of its classes and interfaces, as #215 binds them
 * from a jar -- `METHOD` entries `Owner.name`, the receiver in `args[0]` and uncounted in `arity`, the
 * call `(args[0] as Owner).name(...)`.
 *
 * Before it, only top-level functions were read, so on Kotlin/Native `CompletableJob.complete()` was
 * unreachable from the handle `Job()` returns (#234).
 *
 * ### Red before the implementation
 *
 * No entry or declaration exists for any member (`single` finds nothing).
 */
class KlibMemberFunctionTest {

    private val coroutinesKlib: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureKlib")
            assertNotNull(path, "the walkerFixtureKlib system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    private val entries by lazy { KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines")) }
    private val declarations by lazy { KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines")) }

    private val completableJob = "kotlinx.coroutines.CompletableJob"
    private val job = "kotlinx.coroutines.Job"
    private val semaphore = "kotlinx.coroutines.sync.Semaphore"

    @Test
    fun aMemberIsAMethodEntryWithItsReceiverOutsideItsSlots() {
        val complete = entries.single { it.name == "$completableJob.complete" }
        assertEquals("METHOD", complete.kind)
        assertEquals(0, complete.arity)
        assertEquals(completableJob, complete.receiverTypeName)
        assertEquals("BOOLEAN", complete.returnTag)
        assertTrue(complete.lambdaBody.startsWith("{ args ->"), complete.lambdaBody)
        assertTrue("(args[0] as $completableJob).complete()" in complete.lambdaBody, complete.lambdaBody)
    }

    @Test
    fun anInterfacesMembersBind() {
        assertNotNull(entries.singleOrNull { it.name == "$job.start" })
        assertNotNull(entries.singleOrNull { it.name == "$semaphore.tryAcquire" })
        assertNotNull(entries.singleOrNull { it.name == "$semaphore.release" })
    }

    @Test
    fun theModelIsAMethodOfItsOwner() {
        val start = declarations.single { it.bindingName == "$job.start" }
        assertEquals("METHOD", start.kind)
        assertEquals(job, start.owner)
        assertTrue(start.ownerIsClass)
        assertEquals(job, start.receiver?.qualifiedName)
    }

    @Test
    fun aSuspendMemberIsBoundWithPendingCall() {
        val joinDecl = declarations.single { it.owner == job && it.simpleName == "join" }
        assertEquals("$job.join", joinDecl.bindingName)
        assertTrue(joinDecl.isSuspend)
        val join = entries.single { it.name == "$job.join" }
        assertEquals("METHOD", join.kind)
        assertTrue(join.isSuspend)
        assertTrue("PendingCall.start" in join.lambdaBody, join.lambdaBody)
    }

    @Test
    fun aMemberTypedByAnotherKlibIsDeclinedWithItsReason() {
        // `Job.cancel(cause: CancellationException? = null)`: the cause is a stdlib class.
        val cancel = declarations.filter { it.owner == job && it.simpleName == "cancel" }
        assertTrue(cancel.isNotEmpty())
        assertTrue(cancel.all { it.bindingName == null }, cancel.map { it.bindingName }.toString())
        assertTrue(cancel.any { it.declineReason?.startsWith("no boundary type for parameter") == true }, cancel.map { it.declineReason }.toString())
    }

    @Test
    fun aNonSuspendMemberOfAGenericInterfaceIsBound() {
        val getCompleted = entries.single { it.name == "kotlinx.coroutines.Deferred.getCompleted" }
        assertEquals("METHOD", getCompleted.kind)
        assertTrue("kotlinx.coroutines.Deferred<kotlin.Any?>" in getCompleted.lambdaBody, getCompleted.lambdaBody)
        val await = declarations.single { it.owner == "kotlinx.coroutines.Deferred" && it.simpleName == "await" }
        assertEquals("kotlinx.coroutines.Deferred.await", await.bindingName)
        assertTrue(await.isSuspend)
        assertNull(await.declineReason)
    }

    @Test
    fun aMemberIsNotAModuleFunction() {
        assertTrue(entries.none { it.name == "kotlinx.coroutines.complete" || it.name == "kotlinx.coroutines.start" })
    }
}
