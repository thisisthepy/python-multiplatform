package python.multiplatform.gradle.artifact

import python.multiplatform.gradle.stubs.renderKotlinFqnStubs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #73 over `TextStateFixtures.kt`: why `androidx.compose.foundation.text.input.TextFieldState` was
 * stubbed as a class with no members and no way to make one, reduced to the two walker rules that
 * caused it.
 *
 * 1. **A constructor with a value-class parameter was dropped without a word.** `kotlinc` compiles it to
 *    a JVM-`private` `<init>` behind a public `ACC_SYNTHETIC` bridge ending in
 *    `DefaultConstructorMarker`, and that bridge is the descriptor `@Metadata` records. The walker's
 *    check for `@Deprecated(level = HIDDEN)` constructors refused every synthetic `<init>`, so it
 *    refused this one too -- with no model, so nothing said so. `javap -v` on foundation-desktop
 *    1.11.1's `TextFieldState`: `(Ljava/lang/String;JLkotlin/jvm/internal/DefaultConstructorMarker;)V`,
 *    `ACC_PUBLIC, ACC_SYNTHETIC`, no annotation.
 * 2. **A `CharSequence` result had no boundary type.** `kotlin.CharSequence` is a built-in with no class
 *    file, so the object-handle case could not name it and `TextFieldState.text` was declined
 *    ("no boundary type for property type kotlin.CharSequence").
 *
 * (The third cause, that `:ksp-fixtures:compose` did not walk `androidx.compose.foundation.text.input`
 * at all, is configuration, and `TextFieldStateRenderTest` there is its test.)
 *
 * ### Red before the implementation
 *
 * Red: [aConstructorWithAValueClassParameterBinds], [aHiddenValueClassConstructorStaysOutAndItsVisibleSiblingKeepsTheBareName]
 * (no `Legacy` entry at all), [aCharSequencePropertyIsReadAsAStr], [aCharSequenceFunctionResultIsAStr] and
 * [theStubGivesTheClassItsConstructorAndItsTextProperty] -- each `entry(...)` fails with "is not
 * bound". Already green before, and kept as guards: [theExtensionsOnTheStateBindAsMethodsOfIt],
 * [aCharSequenceParameterIsStillDeclined], and the `Plain` half of the hidden-constructor test.
 */
class TextStateBindingTest {

    private val pkg = "fixture.artifacttextstate"

    private val fixtureClasses: File
        get() {
            val location = Class.forName("$pkg.Editable").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isDirectory, "expected a directory of .class files: $it") }
        }

    private val entries by lazy { ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf(pkg)) }

    private val declarations by lazy { ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf(pkg)) }

    private fun entry(name: String): ArtifactCallable =
        assertNotNull(entries.singleOrNull { it.name == name }, "$name is not bound; bound: ${entries.map { it.name }}")

    // ------------------------------------------------------------------------ the constructor

    /** `TextFieldState(initialText = "", initialSelection = TextRange(...))`'s shape. */
    @Test
    fun aConstructorWithAValueClassParameterBinds() {
        val editable = entry("$pkg.Editable")
        assertEquals(2, editable.arity)
        assertEquals(listOf("initialText", "initialSelection"), editable.paramNames)
        assertEquals(listOf("kotlin.String", "$pkg.Span"), editable.paramTypeNames)
        // `Span` cannot be built from a number (its constructor is internal): it crosses as a handle.
        assertEquals(listOf("STRING", "OBJECT"), editable.paramTags)
        assertEquals("OBJECT", editable.returnTag)
        assertEquals("$pkg.Editable", editable.returnTypeName)
        // Both defaults are Python's to leave out (`TextFieldState()` from Python).
        assertEquals(listOf(true, true), editable.paramHasDefault)
        // Kotlin source calling the constructor by its Kotlin name -- never the bridge, never `<init>`.
        assertTrue("$pkg.Editable((args[0] as String), (args[1] as $pkg.Span))" in editable.lambdaBody, editable.lambdaBody)
        assertFalse("DefaultConstructorMarker" in editable.lambdaBody, editable.lambdaBody)

        val model = declarations.single { it.owner == pkg && it.simpleName == "Editable" }
        assertEquals("$pkg.Editable", model.bindingName, model.toString())
        assertNull(model.declineReason, model.toString())
    }

    /**
     * `PointerInputChange`'s and `TextStyle`'s shape. The hidden constructor's bridge carries
     * `kotlin.Deprecated(level = HIDDEN)` and must stay out -- if it did not, the two would share a name
     * and both would be suffixed (`Legacy__Span`, `Legacy__Span_Boolean`), and the second would not
     * compile. `Plain` is the hidden constructor with no bridge, out before and after.
     */
    @Test
    fun aHiddenValueClassConstructorStaysOutAndItsVisibleSiblingKeepsTheBareName() {
        val names = entries.map { it.name }
        assertEquals(1, entry("$pkg.Legacy").arity)
        assertTrue(names.none { it.startsWith("$pkg.Legacy__") }, names.toString())
        assertEquals(1, entry("$pkg.Plain").arity)
        assertTrue(names.none { it.startsWith("$pkg.Plain__") }, names.toString())
    }

    // ---------------------------------------------------------------------- CharSequence results

    /** `TextFieldState.text`: read as Kotlin's own `toString()` of it, which crosses as `str`. */
    @Test
    fun aCharSequencePropertyIsReadAsAStr() {
        val text = entry("$pkg.Editable.text")
        assertEquals("GETTER", text.kind)
        assertEquals("STRING", text.returnTag)
        assertEquals("kotlin.CharSequence", text.returnTypeName)
        assertEquals("{ args -> ((args[0] as $pkg.Editable).text).toString() }", text.lambdaBody)

        val draft = entry("$pkg.Editable.draft")
        assertEquals("STRING", draft.returnTag)
        assertEquals("{ args -> ((args[0] as $pkg.Editable).draft)?.toString() }", draft.lambdaBody, "null must stay null")
        assertTrue(entries.none { it.name == "$pkg.Editable.text=" }, "a val has no setter")
    }

    @Test
    fun aCharSequenceFunctionResultIsAStr() {
        val snapshot = entry("$pkg.snapshot")
        assertEquals("STRING", snapshot.returnTag)
        assertEquals("kotlin.CharSequence", snapshot.returnTypeName)
        assertEquals(
            "{ args -> ((args[0] as $pkg.Editable).artifact_ext_fixture_artifacttextstate_snapshot()).toString() }",
            snapshot.lambdaBody,
        )
    }

    /** Only a declared *result* is converted (issue #73's scope): a parameter has no class to cast to. */
    @Test
    fun aCharSequenceParameterIsStillDeclined() {
        assertTrue(entries.none { it.name == "$pkg.lengthOf" }, entries.map { it.name }.toString())
        val lengthOf = declarations.single { it.simpleName == "lengthOf" }
        assertNull(lengthOf.bindingName)
        val reason = assertNotNull(lengthOf.declineReason)
        assertTrue("kotlin.CharSequence" in reason, reason)
    }

    // ------------------------------------------------------------------------- the extensions

    /** `setTextAndPlaceCursorAtEnd(String)` and `clearText()`'s shapes: methods of the state's proxy. */
    @Test
    fun theExtensionsOnTheStateBindAsMethodsOfIt() {
        val replaceAll = entry("$pkg.replaceAll")
        assertEquals("$pkg.Editable", replaceAll.receiverTypeName)
        assertEquals(listOf("OBJECT", "STRING"), replaceAll.paramTags)
        assertEquals("$pkg.Editable", entry("$pkg.clear").receiverTypeName)
    }

    // -------------------------------------------------------------------------------- the stub

    /**
     * What pythonx-compose reads: the class keeps its stub, with the constructor as its `__init__` and
     * `text` as a `str` property, instead of a module function `Editable(...) -> Any` that takes the
     * class's name and leaves its properties a comment.
     */
    @Test
    fun theStubGivesTheClassItsConstructorAndItsTextProperty() {
        entry("$pkg.Editable")
        val stub = renderKotlinFqnStubs(declarations).getValue("fixture/artifacttextstate/__init__.pyi")
        assertTrue("class Editable:" in stub, stub)
        assertTrue("    def __init__(self, initialText: str = ..., initialSelection: Span = ...) -> None:" in stub, stub)
        assertTrue("    @property\n    def text(self) -> str:" in stub, stub)
        assertTrue("    @property\n    def draft(self) -> str | None:" in stub, stub)
        assertTrue("    replaceAll: _t.ClassVar[" in stub, stub)
        assertFalse(Regex("^def Editable\\(", RegexOption.MULTILINE).containsMatchIn(stub), "the constructor took the class's name: $stub")
        assertTrue("class Legacy:" in stub && "    def __init__(self, span: Span) -> None:" in stub, stub)
    }
}
