package fixture.artifact

import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the artefact walker put in the table, checked against the jar it was walked from.
 *
 * The fixed set is `ArtifactScannerTest`'s, arrived at from the other end: that test walks the jar
 * directly in a plugin unit test, this one reads what the *build* produced, compiled and installed.
 * If they ever disagree, the wiring between them is what broke.
 */
class WalkedArtifactTableTest {

    @BeforeTest
    fun startFromAnEmptyTable() {
        UpcallTable.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * The wiring, pinned against the two artefacts this fixture fully controls.
     *
     * `junit:junit:4.13.2` and `:python-multiplatform-gradle-plugin-fixtures:artifact-valueclass` are both version-pinned by this
     * build, so their contribution is a fixed list and `ArtifactScannerTest
     * .theWholeJUnitJarYieldsExactlyTheseUnsuffixedDeclarations` pins the same seven names from the
     * other end -- that test walks the jar directly in a plugin unit test, this one reads what the
     * *build* produced, compiled and installed. If the two ever disagree, the wiring between them is
     * what broke.
     *
     * `kotlin-stdlib` and Compose are deliberately **not** pinned as lists. Their contribution is a
     * property of a version this repository bumps, and pinning ~250 names would turn every Kotlin or
     * Compose bump into a mechanical edit of this file while catching nothing the assertions below
     * do not. What is asserted about them instead is the thing that is a property of *this* code:
     * that a package asked for contributed something, and that the overload rule spelled it the way
     * `ArtifactScanner.disambiguateOverloads` says.
     */
    @Test
    fun theWalkerBoundExactlyTheJarsDeclarationsItCouldCarry() {
        ArtifactTable.registerInto()
        val names = UpcallTable.entries().map { it.name }.sorted()

        assertEquals(
            listOf(
                // `:python-multiplatform-gradle-plugin-fixtures:artifact-valueclass`, built for exactly one proof: see that
                // module's `build.gradle.kts`. `Meters` is the value class's own constructor,
                // bound since `e2d75100` -- this list predates that and was never updated, which
                // nothing noticed because `python-multiplatform-gradle-plugin-fixtures:artifact` is not in the usual verification
                // set. `ArtifactScannerTest` pins the same constructor from the plugin side.
                "fixture.valueclass.Meters",
                "fixture.valueclass.sumMeters",
                "junit.runner.BaseTestRunner.getFilteredTrace",
                "junit.runner.BaseTestRunner.getPreference__String",
                "junit.runner.BaseTestRunner.getPreference__String_Int",
                "junit.runner.BaseTestRunner.savePreferences",
                "junit.runner.BaseTestRunner.setPreference",
                "junit.runner.BaseTestRunner.truncate",
                "junit.runner.Version.id",
            ),
            names.filter { it.startsWith("junit.runner.") || it.startsWith("fixture.valueclass.") },
        )

        // `kotlin.text`: `kotlin-stdlib` needs no dependency of its own, and `trimIndent` is the
        // top-level extension that used to be unreachable behind `StringsKt`'s multi-file facade --
        // the case `ArtifactScanner`'s KDoc names. `WalkedArtifactPythonImportTest` calls it.
        assertTrue("kotlin.text.trimIndent" in names, "the multi-file facade case is missing")
        // `kotlin.text.get` is `MatchGroupCollection.get(String)`, which is declared in Kotlin's
        // `kotlin.text` but compiled into `kotlin/text/jdk8/` by `@file:JvmPackageName`. Binding it
        // under the JVM package produced `import kotlin.text.jdk8.get`, which does not compile --
        // see `kotlinPackageNameOverrideOf`. It is here because that is now read.
        assertTrue("kotlin.text.get" in names, "the @JvmPackageName case is missing")

        // Compose. `docs/design/kotlin-extensions-in-python.md` §3 measured **zero** declarations bound
        // from this package; `WalkedArtifactComposeModifierTest` calls two of these from Python.
        val layout = names.filter { it.startsWith("androidx.compose.foundation.layout.") }
        assertTrue(layout.size >= 60, "expected androidx.compose.foundation.layout to bind; got $layout")
        assertTrue("androidx.compose.foundation.layout.padding__Dp" in layout, layout.toString())
        assertTrue("androidx.compose.foundation.layout.size__Dp" in layout, layout.toString())
        assertTrue("androidx.compose.foundation.layout.fillMaxSize" in layout, layout.toString())

        // The rule, stated as an invariant over the whole table rather than per name: a bare Kotlin
        // name means exactly one declaration, and a `__`-suffixed one names a member of an overload
        // set whose bare name is therefore absent.
        val suffixed = names.filter { "__" in it }
        assertTrue(suffixed.isNotEmpty())
        suffixed.forEach { name ->
            assertFalse(name.substringBefore("__") in names, "$name coexists with its own bare name")
        }
    }

    /**
     * The call actually reaches the jar.
     *
     * `"4.13.2"` is JUnit 4's own version string, compiled into `junit/runner/Version.class`. No
     * generator, no table and no test fixture can produce it -- only the artefact can, which is why
     * this is the one declaration the Python-side test reads back too.
     */
    @Test
    fun aBoundDeclarationCallsIntoTheArtefact() {
        ArtifactTable.registerInto()
        val id = UpcallTable.resolve("junit.runner.Version.id")
        assertTrue(id.isValid, "the walker's entry is not in the table")
        assertEquals("4.13.2", UpcallTable.invoke(id, emptyArray()))
    }

    /**
     * The join: two aggregators, one table.
     *
     * `FunctionTable` is what KSP built out of this module's own source; `ArtifactTable` is what the
     * walker built out of the jars the build resolved. They are separate lists on purpose -- see
     * `ArtifactTableRenderingTest` -- and the install site is where they meet.
     */
    @Test
    fun bothProducersLandInOneTable() {
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)

        assertTrue(
            UpcallTable.resolve("fixture.artifact.whichSideAmIFrom").isValid,
            "KSP's half is missing from the joint install",
        )
        assertTrue(
            UpcallTable.resolve("junit.runner.Version.id").isValid,
            "the walker's half is missing from the joint install",
        )
        assertEquals("ksp", UpcallTable.invoke(UpcallTable.resolve("fixture.artifact.whichSideAmIFrom"), emptyArray()))
        assertEquals("4.13.2", UpcallTable.invoke(UpcallTable.resolve("junit.runner.Version.id"), emptyArray()))
    }

    /**
     * `FunctionTable` still means what it meant.
     *
     * The alternative design put walked fragments into KSP's own aggregator. This is the assertion
     * that would have had to change for it, and the reason it was not chosen: a consumer's table
     * must not grow because they added a dependency.
     */
    @Test
    fun theKspAggregatorDoesNotAbsorbWalkedArtefacts() {
        UpcallTable.install(FunctionTable.fragments)
        assertTrue(UpcallTable.resolve("fixture.artifact.whichSideAmIFrom").isValid)
        assertFalse(
            UpcallTable.resolve("junit.runner.Version.id").isValid,
            "FunctionTable absorbed a walked artefact; the two producers are supposed to keep separate lists",
        )
    }

    /** `registerInto` adds; it does not reinstall. A second call must not double the table, which is
     * `UpcallTable.register`'s own `moduleName` guard doing the work. */
    @Test
    fun registeringTwiceIsANoOp() {
        ArtifactTable.registerInto()
        val once = UpcallTable.callableCount
        ArtifactTable.registerInto()
        assertEquals(once, UpcallTable.callableCount)
    }
}
