package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #234: the klib walker carries Kotlin objects as handles (SPEC B-2).
 *
 * Before it, [KlibScanner] knew only `kotlin`'s primitives, `String`, `ByteArray` and `Unit`, so on
 * Kotlin/Native nothing that takes or returns a Kotlin object bound -- `kotlinx.coroutines.Job(parent:
 * Job? = null): CompletableJob` was declined, and member functions (#215) had no receiver to run on.
 * A type whose class is a public, non-generic, non-value `class`/`interface`/`enum class`/`object`
 * **of the walked klib** now crosses as an `OBJECT` handle, cast in generated source the way the jar
 * producer casts one; a type from another klib is still declined with its reason.
 *
 * ### Red before the implementation
 *
 * `Job` and `MainScope` have no binding (`single` finds no entry), and `CoroutineScope(context)` is
 * declined for its *result* (`no boundary type for return kotlinx.coroutines.CoroutineScope`); after,
 * the result crosses and the stdlib parameter is what still declines it.
 */
class KlibObjectHandleTest {

    private val coroutinesKlib: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureKlib")
            assertNotNull(path, "the walkerFixtureKlib system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    private val entries by lazy { KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines")) }
    private val declarations by lazy { KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines")) }

    private val job = "kotlinx.coroutines.Job"

    @Test
    fun aNullableHandleParameterAndAHandleResultBind() {
        val factory = entries.single { it.name == job }
        assertEquals(listOf("OBJECT"), factory.paramTags)
        assertEquals(listOf(job), factory.paramTypeNames)
        assertEquals("OBJECT", factory.returnTag)
        assertEquals("kotlinx.coroutines.CompletableJob", factory.returnTypeName)
        assertTrue("(args[0] as $job?)" in factory.lambdaBody, factory.lambdaBody)
    }

    @Test
    fun aHandleResultCarriesItsAncestryFromTheAbi() {
        // `_member` and `_is_a` read the ancestry the table carries: a `CompletableJob` is a `Job`.
        val factory = entries.single { it.name == job }
        assertTrue(job in factory.returnSupertypes, factory.returnSupertypes.toString())
    }

    @Test
    fun anInterfaceResultBinds() {
        val mainScope = entries.single { it.name == "kotlinx.coroutines.MainScope" }
        assertEquals("kotlinx.coroutines.CoroutineScope", mainScope.returnTypeName)
        assertEquals("OBJECT", mainScope.returnTag)
    }

    @Test
    fun aTypeFromAnotherKlibIsStillDeclinedWithItsReason() {
        val scope = declarations.single { it.simpleName == "CoroutineScope" && it.parameters.size == 1 }
        assertNull(scope.bindingName)
        assertEquals("no boundary type for parameter kotlin.coroutines.CoroutineContext", scope.declineReason)
    }

    @Test
    fun noBoundDeclarationCarriesAGenericType() {
        val bound = declarations.filter { it.bindingName != null }
        assertTrue(bound.isNotEmpty())
        val generic = bound.filter { d -> (d.parameters.map { it.type } + d.returnType).any { it.arguments.isNotEmpty() } }
        assertEquals(emptyList(), generic.map { it.bindingName })
    }
}
