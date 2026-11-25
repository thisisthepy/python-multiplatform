package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC B-5 on the klib walker (B-2, #191): a function-typed parameter takes a Python callable on any
 * declaration, as on the jar walker (B-1), not only on a `@Composable` one.
 *
 * Before, `KlibKnmScanner.functionSlotOrNull` returned no slot unless the owner was `@Composable`, so
 * every other function with a lambda parameter was declined on Kotlin/Native ("no boundary type for
 * parameter kotlin.FunctionN") while the JVM table bound it: Compose's `derivedStateOf` and
 * `snapshotFlow`, which notebook-native names for parity with notebook-e2e, and here
 * `kotlinx.coroutines.Runnable(block: () -> Unit)`.
 */
class KlibFunctionSlotTest {

    private val coroutinesKlib: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureKlib")
            assertNotNull(path, "the walkerFixtureKlib system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    private val runnable = "kotlinx.coroutines.Runnable"

    @Test
    fun aNonComposableFunctionTakesACallableInItsFunctionTypedParameter() {
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf(runnable))
        val declined = KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = listOf(runnable))
            .filter { it.bindingName == null }
            .map { "${it.owner}.${it.simpleName}: ${it.declineReason}" }
        val factory = entries.singleOrNull { it.name == runnable }
        assertNotNull(factory, "$runnable(block) is not bound; declined: $declined")
        assertEquals(listOf("block"), factory.paramNames)
        assertEquals(listOf("OBJECT"), factory.paramTags)
        assertEquals(
            listOf("kotlin.Function0()->kotlin.Unit"),
            factory.paramTypeNames,
        )
        assertTrue(
            "as kotlin.Function0<kotlin.Unit>)" in factory.lambdaBody,
            factory.lambdaBody,
        )
    }
}
