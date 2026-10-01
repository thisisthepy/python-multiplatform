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
        assertTrue("def padding__Dp(receiver: int, all: float, /) -> int:" in layout, layout.take(2000))
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
     * §4.4's shape, generated from the real `androidx.compose.foundation.layout` jar: the extension
     * is an attribute on `Modifier` whose type is a Protocol with an overloaded `__call__`, and
     * `Modifier` lives in the module its own Kotlin package maps to (`androidx.compose.ui`) even
     * though `padding` is declared in another (`androidx.compose.foundation.layout`).
     *
     * The metaclass of §4.2 is measured not to work in either CPython or mypy, and must not appear.
     */
    @Test
    fun thePythonicStubsPutEveryExtensionOnItsReceiverAsAProtocolAttribute() {
        val ui = stub("pythonx/compose/ui/__init__.pyi")
        assertTrue("class Modifier:" in ui, ui.take(2000))
        assertTrue("    padding: ClassVar[_Modifier_padding]" in ui, ui.take(4000))
        assertTrue("class _Modifier_padding(Protocol):" in ui, ui.take(4000))
        assertTrue("metaclass" !in ui, "§4.3: the metaclass shape does not work in mypy or in CPython")

        // §3.4's allowlist, read from this fixture's own `pythonx-map.toml`: `Dp` admits a raw
        // number and nothing else does.
        assertTrue("Dp | float" in ui, "the allowlist did not reach the renderer")
    }

    /**
     * §7's third open item, answered against the real jar rather than left open: "two Kotlin
     * overloads can map to identical Python signatures once `Dp | float` widening is applied. The
     * generator must detect that and drop or qualify, and how often it happens... is unknown -- it
     * needs the scan to be run."
     *
     * The scan was run over Compose 1.6.11's `foundation-layout-desktop`. It happens **once**, and
     * this is it: `WindowInsets(left: Int, top: Int, right: Int, bottom: Int)` is unreachable behind
     * `WindowInsets(left: Dp, ...)`, because a checker accepts an `int` wherever a `float` is wanted.
     * mypy 2.3.0 called it "signature 2 will never be matched" before this was handled.
     *
     * Dropped from the Pythonic product and named in a comment -- it is still bound, and the
     * Kotlin-FQN product still has it under its table key.
     */
    @Test
    fun theOneComposeOverloadThatCollapsesIsDroppedWithItsTableKeyNamed() {
        val layout = stub("pythonx/compose/layout/__init__.pyi")
        assertTrue(
            "androidx.compose.foundation.layout.WindowInsets__Int_Int_Int_Int is unreachable" in layout,
            "the collapsing overload is no longer reported: $layout",
        )
        assertTrue(
            "def __init__(self, left: Dp | float = ..., top: Dp | float = ..., " +
                "right: Dp | float = ..., bottom: Dp | float = ...) -> None: ..." in layout,
            "a Kotlin fake constructor belongs on the class, not beside it: $layout",
        )
    }

    /**
     * A lone `@overload` is an error mypy raises in the **consumer's** type check -- "Single overload
     * definition, multiple required" -- over a file they did not write. The first generated Compose
     * stub had 60 of them. Pinned over the whole generated tree rather than one file, because the
     * rule is a property of the renderer and not of this package.
     */
    @Test
    fun noProtocolCarriesASingleDecoratedOverload() {
        generatedPaths().filter { it.startsWith("pythonx/") && it.endsWith(".pyi") }.forEach { path ->
            val lines = stub(path).lines()
            lines.forEachIndexed { index, line ->
                if (line.trim() != "@overload") return@forEachIndexed
                val siblings = lines.drop(index + 1).takeWhile { it.isNotBlank() }.count { it.trim() == "@overload" }
                assertTrue(
                    siblings >= 1 || lines.take(index).takeLastWhile { it.isNotBlank() }.any { it.trim() == "@overload" },
                    "$path:${index + 1} is a lone @overload",
                )
            }
        }
    }

    /** §6.1 measurement 2: without `py.typed` every revealed type in an installed package is `Any`.
     * It goes in the top-level *regular* package -- `pythonx` is a namespace package (§5.1). */
    @Test
    fun theDistributionCarriesAPyTypedMarker() {
        assertTrue("pythonx/compose/py.typed" in generatedPaths(), generatedPaths().toString())
        assertTrue("pythonx/py.typed" !in generatedPaths(), generatedPaths().toString())
    }

    /**
     * §3.6: the `@overload` order and the dispatcher's resolution order have to come from one place.
     * The dispatcher does not exist, so the generator writes the order down beside the stub -- and
     * every name it writes down has to be a table key, or the dispatcher would call nothing.
     */
    @Test
    fun theDispatchOrderNamesOnlyKeysTheTableResolves() {
        ArtifactTable.registerInto()
        val installed = UpcallTable.entries().map { it.name }.toSet()

        val dispatchFiles = generatedPaths().filter { it.endsWith("_pm_dispatch.json") }
        assertTrue(dispatchFiles.isNotEmpty(), "no dispatch order was written: ${generatedPaths()}")

        val named = Regex("\"([A-Za-z0-9_.]+)\"").findAll(dispatchFiles.joinToString("\n") { stub(it) })
            .map { it.groupValues[1] }
            .filter { '.' in it && it.substringBeforeLast('.') in setOf("androidx.compose.foundation.layout", "kotlin.text") }
            .toList()
        assertTrue(named.isNotEmpty(), "the dispatch files named no table keys")
        named.forEach { assertTrue(it in installed, "$it is in a dispatch order and not in the table") }
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
            .filter { it.endsWith("__init__.pyi") && !it.startsWith("pythonx/") }
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
