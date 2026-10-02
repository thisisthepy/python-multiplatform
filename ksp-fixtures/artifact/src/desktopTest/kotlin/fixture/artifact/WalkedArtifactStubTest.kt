package fixture.artifact

import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `.pyi` stubs the **build** generated, checked against the table the same build installed.
 *
 * Every assertion here reads `build/generated/pythonStubs/desktopMain`, written by
 * `generatePythonStubs` from the same jars `generatePythonArtifactBindings` walked. Nothing in this
 * file is a hand-written stub: `PyiRenderingTest` pins the renderer against models it constructs,
 * and this pins the renderer against Compose, JUnit and `kotlin-stdlib` as they actually are.
 *
 * The claim worth having is the last one. A stub's only job is to describe a surface that exists, and
 * `docs/design/pyi-generation-design.md` §4.5 states the failure mode precisely -- "a stub that says
 * `Modifier.weight(1.0)` checks is a stub that promises a call the runtime cannot make". So the test
 * is not "the file contains this text", it is **every `def` in the Kotlin-FQN stubs is a key
 * `UpcallTable` resolves, and every key is a `def`**.
 */
class WalkedArtifactStubTest {

    private val stubs: File
        get() {
            val path = System.getProperty("python.multiplatform.stubDir")
            assertTrue(path != null, "the stubDir system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isDirectory, "generatePythonStubs produced nothing at $it") }
        }

    private fun stub(relativePath: String): String {
        val file = stubs.resolve(relativePath)
        assertTrue(file.isFile, "no generated stub at $relativePath; generated: ${generatedPaths()}")
        return file.readText()
    }

    private fun generatedPaths(): List<String> = stubs.walkTopDown()
        .filter { it.isFile }
        .map { it.relativeTo(stubs).invariantSeparatorsPath }
        .sorted()
        .toList()

    @BeforeTest
    fun startFromAnEmptyTable() {
        UpcallTable.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * The Kotlin-FQN product, against the package `docs/design/kotlin-extensions-in-python.md` §3 measured
     * at zero bound declarations.
     *
     * The annotations are the **boundary's**: `Dp` marshals as a raw float and `Modifier` as a
     * `HandleTable` integer, which is what `androidx.compose.foundation.layout` -- a module that
     * exists only as a `sys.modules` entry `PythonProxySource` creates -- actually accepts and
     * returns. §7 lists the handle-to-proxy wrapping that would make it `Modifier` as not yet done.
     */
    @Test
    fun theKotlinFqnStubsDescribeTheModulesTheRuntimePublishes() {
        val layout = stub("androidx/compose/foundation/layout/__init__.pyi")
        assertTrue("def padding__Dp(receiver: int, /, all: float) -> int:" in layout, layout.take(2000))
        assertTrue(
            "\"\"\"Kotlin: androidx.compose.ui.Modifier.padding(all: androidx.compose.ui.unit.Dp): " +
                "androidx.compose.ui.Modifier\"\"\"" in layout,
            "the Kotlin signature belongs in the docstring, where no checker can act on it",
        )

        // A Java jar compiled without `-parameters` (§3.2): positional-only, and no invented names.
        val version = stub("junit/runner/Version/__init__.pyi")
        assertTrue("def id() -> str:" in version, version)
    }

    /**
     * The binder never exports a Kotlin namespace under another name (AGENTS.md section 12 rule 1).
     * `androidx.compose.foundation.layout` is stubbed under that very path, with Kotlin names and
     * Kotlin parameter names; making it Pythonic is the `pythonx-compose` package's job.
     *
     * Pinned over the whole generated tree: nothing under `pythonx/`, no snake_cased member, and none
     * of the Pythonic product's side files (`py.typed`, `_pm_dispatch.json`).
     */
    @Test
    fun theStubsUseKotlinNamesAndNothingIsExportedUnderPythonx() {
        val paths = generatedPaths()
        assertEquals(emptyList(), paths.filter { it.startsWith("pythonx/") }, paths.toString())
        assertTrue(paths.none { it.endsWith("py.typed") || it.endsWith("_pm_dispatch.json") }, paths.toString())

        val layout = stub("androidx/compose/foundation/layout/__init__.pyi")
        assertTrue("def fillMaxWidth(receiver: int, /, fraction: float = ...) -> int:" in layout, layout.take(3000))
        assertTrue("fill_max_width" !in layout, "a Kotlin name was exported under another spelling")
        assertTrue("alignmentLine: int" in layout, "a Kotlin parameter name was renamed: ${layout.take(3000)}")

        paths.filter { it.endsWith(".pyi") }.forEach { path ->
            val text = stub(path)
            assertTrue("pythonx" !in text, "$path mentions pythonx")
            assertTrue("class _" !in text && "Protocol" !in text, "$path carries the Pythonic Protocol shape")
        }
    }

    /**
     * The runtime resolves a Kotlin default when a defaulted parameter is omitted, so the stub
     * marks it `= ...`; a parameter with no default stays required. Python rejects a required
     * parameter after a defaulted one, so for the stub to be valid no `name: type` without `= ...` may
     * follow one with it. Checked textually, since no Python parser runs in this module.
     */
    @Test
    fun noGeneratedDefPutsARequiredParameterAfterADefaultedOne() {
        generatedPaths().filter { it.endsWith(".pyi") }.forEach { path ->
            Regex("""^def [A-Za-z0-9_]+\((.*)\) -> """, RegexOption.MULTILINE).findAll(stub(path)).forEach { match ->
                var sawDefault = false
                match.groupValues[1].split(", ").filter { it != "/" && it != "*" }.forEach { parameter ->
                    if ("= ..." in parameter) sawDefault = true
                    else assertTrue(!sawDefault, "$path: required parameter after a default in ${match.value}")
                }
            }
        }
    }

    /**
     * The invariant the whole product rests on, stated in both directions.
     *
     * A `def` with no table key is a stub promising a call that raises `AttributeError`; a table key
     * with no `def` is a declaration an editor cannot see, which is the entire problem stubs exist
     * to solve. Neither is allowed, and this is the assertion that would catch the day one of the two
     * generators is changed and the other is not.
     */
    @Test
    fun everyStubbedFunctionIsATableKeyAndEveryTableKeyIsStubbed() {
        ArtifactTable.registerInto()
        val installed = UpcallTable.entries().map { it.name }.toSet()

        val stubbed = generatedPaths()
            .filter { it.endsWith("__init__.pyi") }
            .flatMap { path ->
                val module = path.removeSuffix("/__init__.pyi").replace('/', '.')
                val source = stub(path)
                // Two shapes, because a table key is not always a call. A `STATIC_GETTER` -- a
                // constant an object or companion publishes, `Arrangement.Start` -- is read as an
                // attribute rather than called (`46be0212`), so `ac8e4708` renders it as a value
                // annotation instead of a `def`. Matching only `def` would call every such key
                // unstubbed, which is what this assertion reported after that change: the stub is
                // there, it just is not a function. Both shapes are stubs; neither may be missing.
                val functions = Regex("""^def ([A-Za-z0-9_]+)\(""", RegexOption.MULTILINE)
                    .findAll(source)
                    .map { it.groupValues[1] }
                val constants = Regex("""^([A-Za-z0-9_]+): """, RegexOption.MULTILINE)
                    .findAll(source)
                    .map { it.groupValues[1] }
                (functions + constants).map { "$module.$it" }.toList()
            }
            .toSet()

        assertEquals(emptySet(), stubbed - installed, "stubbed but not callable")
        assertEquals(emptySet(), installed - stubbed, "callable but not stubbed")
    }
}
