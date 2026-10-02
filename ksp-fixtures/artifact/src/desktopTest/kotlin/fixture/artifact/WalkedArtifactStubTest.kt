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
     * The annotations are the **declared Kotlin types** (issue #31): a `Modifier` receiver and result,
     * and `Dp | float` for the value class the binder binds as its primitive.
     */
    @Test
    fun theKotlinFqnStubsDescribeTheModulesTheRuntimePublishes() {
        val layout = stub("androidx/compose/foundation/layout/__init__.pyi")
        assertTrue(
            "def padding__Dp(receiver: androidx.compose.ui.Modifier, /, all: androidx.compose.ui.unit.Dp | float) " +
                "-> androidx.compose.ui.Modifier:" in layout,
            layout.take(2000),
        )
        // The class is stubbed in the module of its own package, with the extension as an attribute.
        val ui = stub("androidx/compose/ui/__init__.pyi")
        assertTrue("class Modifier:" in ui, ui.take(2000))
        assertTrue("padding__Dp: _t.ClassVar[_Modifier_padding__Dp]" in ui, ui.take(4000))
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
     * of the Pythonic product's side files (`py.typed`, `_pm_dispatch.json`). The `Protocol` classes
     * that type an extension as a method of its receiver's class are typing machinery, not a
     * renamed product: they are private (`_Receiver_name`) and carry the Kotlin name unchanged.
     */
    @Test
    fun theStubsUseKotlinNamesAndNothingIsExportedUnderPythonx() {
        val paths = generatedPaths()
        assertEquals(emptyList(), paths.filter { it.startsWith("pythonx/") }, paths.toString())
        assertTrue(paths.none { it.endsWith("py.typed") || it.endsWith("_pm_dispatch.json") }, paths.toString())

        val layout = stub("androidx/compose/foundation/layout/__init__.pyi")
        assertTrue(
            "def fillMaxWidth(receiver: androidx.compose.ui.Modifier, /, fraction: float = ...) " +
                "-> androidx.compose.ui.Modifier:" in layout,
            layout.take(3000),
        )
        assertTrue("fill_max_width" !in layout, "a Kotlin name was exported under another spelling")
        assertTrue("alignmentLine: " in layout, "a Kotlin parameter name was renamed: ${layout.take(3000)}")

        paths.filter { it.endsWith(".pyi") }.forEach { path ->
            val text = stub(path)
            assertTrue("pythonx" !in text, "$path mentions pythonx")
            assertTrue(
                Regex("""class _(?!\w+_\w+\(_t\.Protocol\))""").find(text) == null,
                "$path carries a private class that is not a receiver-method protocol",
            )
        }
    }

    /**
     * The runtime resolves a Kotlin default when a defaulted parameter is omitted, so the stub
     * marks it `= ...`; a parameter with no default stays required. Python rejects a required
     * parameter after a defaulted one **unless it is keyword-only**, which is exactly what
     * `inspect.signature` reports for such a parameter (`KotlinSurface.kt`), so the stub writes `*`
     * before it. Checked textually, since no Python parser runs in this module -- and bracket-aware,
     * because a `Callable[[A, B], R]` annotation has commas of its own.
     */
    @Test
    fun noGeneratedDefPutsARequiredPositionalParameterAfterADefaultedOne() {
        generatedPaths().filter { it.endsWith(".pyi") }.forEach { path ->
            Regex("""^(?:def [A-Za-z0-9_]+|    def __call__)\((.*)\) -> """, RegexOption.MULTILINE).findAll(stub(path)).forEach { match ->
                var sawDefault = false
                var keywordOnly = false
                splitTopLevel(match.groupValues[1]).filter { it != "/" }.forEach { parameter ->
                    if (parameter == "*") keywordOnly = true
                    else if ("= ..." in parameter) sawDefault = true
                    else assertTrue(!sawDefault || keywordOnly, "$path: required positional parameter after a default in ${match.value}")
                }
            }
        }
    }

    private fun splitTopLevel(parameters: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in parameters) {
            when {
                c == '[' -> { depth++; current.append(c) }
                c == ']' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { out += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
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

        // The base name of an overload set (`padding` for `padding__Dp`, ...) is served by the binding
        // layer's dispatcher and is stubbed as `@overload`s; it is not itself a table key.
        val overloadBases = installed.map { it.substringBeforeLast('.') + "." + it.substringAfterLast('.').substringBefore("__") }
            .filter { it !in installed }.toSet()
        assertEquals(emptySet(), stubbed - installed - overloadBases, "stubbed but not callable")
        assertEquals(emptySet(), installed - stubbed, "callable but not stubbed")
    }
}
