package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Where the inheritance relation comes from**, and what carrying it costs the table.
 *
 * `ebe3365f` pinned the second wall: `pythonx._coerce` compared an owned value's declared type name
 * against the slot's for **equality**, so `BitmapPainter(...)` was refused where a `Painter` was
 * wanted, and every `Painter` in Compose was out of reach except the one function that happens to
 * declare the base type.
 *
 * ### The relation is the walker's to answer, and only the walker's
 *
 * `pythonx` holds names. A handle is an integer, `TypeTag.OBJECT` says nothing about what it points
 * at, and there is no reflection shared by five targets to ask -- agent-rules §12 retires dynamic
 * binding for exactly that reason. The walker, on the other hand, has the class files open: a
 * supertype is `ClassNode.superName` and `ClassNode.interfaces`, one already-cached read per link.
 *
 * ### Which direction is carried, and why that is the cheap one
 *
 * The **value's** ancestry, not the slot's subtypes. A `Painter` slot accepts every `Painter` that
 * will ever exist, which is not knowable from one walk; a produced value has one finite chain. So
 * the question is asked once per declared *return type that crosses as a handle*, and nowhere else.
 *
 * ### And what it costs
 *
 * Measured rather than asserted to be small: see
 * [theAncestryTheTableCarriesIsMeasuredRatherThanAssumed], which prints the number of entries that
 * gain anything, the characters added, and the longest chain.
 */
class ReturnSupertypeTest {

    private val caches: File? = listOf(
        File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
        File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
    ).firstOrNull { it.isDirectory }

    private fun jarUnder(group: String, artifact: String): File? = caches?.resolve(group)?.resolve(artifact)
        ?.walkTopDown()?.firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

    private val kotlinStdlibJar: File
        get() = File(Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location.toURI())

    private fun composeClasspath(): List<File> = listOfNotNull(
        jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-text-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
        jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
        jarUnder("org.jetbrains.compose.collection-internal", "collection-desktop"),
        jarUnder("org.jetbrains.compose.annotation-internal", "annotation-desktop"),
        kotlinStdlibJar,
    )

    /**
     * The row the fixture's render test turns on: `BitmapPainter` **is a** `Painter`, taken out of
     * the jar rather than written down here.
     *
     * The negative half is in the same test on purpose. `ImageBitmap` is what a `BitmapPainter` is
     * built *from* and is not a `Painter`, so a rule that had said "any object fills any object slot"
     * would pass the first assertion and fail the second -- and `Icon`, which overloads on exactly
     * these two types, would then match both candidates and refuse for ambiguity instead.
     */
    @Test
    fun aBoundDeclarationCarriesWhatItsReturnTypeIsA() {
        val graphics = jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            graphics,
            includePrefixes = listOf("androidx.compose.ui.graphics"),
            classpath = composeClasspath(),
        )
        // BitmapPainter's constructor is not bound (only value class constructors are), so its
        // top-level factory function survives without ambiguity and carries return ancestry.
        val painter = entries.first { it.name == "androidx.compose.ui.graphics.painter.BitmapPainter" }
        assertEquals("androidx.compose.ui.graphics.painter.BitmapPainter", painter.returnTypeName)
        assertEquals(listOf("androidx.compose.ui.graphics.painter.Painter"), painter.returnSupertypes)

        val bitmap = entries.first { it.name == "androidx.compose.ui.graphics.ImageBitmap" }
        assertEquals("androidx.compose.ui.graphics.ImageBitmap", bitmap.returnTypeName)
        assertTrue(
            "androidx.compose.ui.graphics.painter.Painter" !in bitmap.returnSupertypes,
            "an ImageBitmap must not claim to be a Painter: ${bitmap.returnSupertypes}",
        )
    }

    /**
     * The same ancestry on the declaration model, which is what the stub generator reads: a stub class
     * extends what the binding layer's `_is_a` would accept it for (issue #31). One walk, so the two
     * cannot disagree.
     */
    @Test
    fun theDeclarationModelCarriesTheSameAncestryAsTheBinding() {
        val graphics = jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop") ?: return
        val declarations = ArtifactScanner.scanDeclarations(
            graphics,
            includePrefixes = listOf("androidx.compose.ui.graphics"),
            classpath = composeClasspath(),
        )
        val painter = declarations.first { it.bindingName == "androidx.compose.ui.graphics.painter.BitmapPainter" }
        assertEquals(listOf("androidx.compose.ui.graphics.painter.Painter"), painter.returnSupertypes)
    }

    /**
     * A return that does **not** cross as a handle carries no ancestry, because nothing could read
     * one: a `Dp` result is a raw `FLOAT` with no identity to hang it on, and a `Boolean` even less.
     * Asked only where it can be answered is also the whole of why the cost below is what it is.
     */
    @Test
    fun aReturnThatIsNotAHandleCarriesNoAncestry() {
        val unit = jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            unit,
            includePrefixes = listOf("androidx.compose.ui.unit"),
            classpath = composeClasspath(),
        )
        val nonHandles = entries.filter { it.returnTag != "OBJECT" }
        assertTrue(nonHandles.isNotEmpty(), "expected some declaration to return a value rather than a handle")
        assertEquals(
            emptyList(),
            nonHandles.filter { it.returnSupertypes.isNotEmpty() }.map { it.name to it.returnSupertypes },
            "a non-handle return carried an ancestry nothing can read",
        )
    }

    /**
     * **The price.** Over the four packages `:ksp-fixtures:compose` actually walks, how many entries
     * gain anything, how many characters that is against the rendered fragments, and how long the
     * longest chain gets.
     *
     * Printed rather than pinned to a literal -- they are facts about a Compose version -- with
     * bounds loose enough to survive one and tight enough to catch the shape going wrong: a chain
     * that ran away, or an ancestry attached to everything.
     */
    @Test
    fun theAncestryTheTableCarriesIsMeasuredRatherThanAssumed() {
        val jars = listOfNotNull(
            jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
        )
        if (jars.size < 3) return
        val classpath = composeClasspath()
        val entries = jars.flatMap { jar ->
            ArtifactScanner.scanJar(
                jar,
                includePrefixes = listOf(
                    "androidx.compose.material3",
                    "androidx.compose.foundation.layout",
                    "androidx.compose.ui.graphics",
                    "androidx.compose.ui.res",
                ),
                classpath = classpath,
            )
        }
        assertTrue(entries.size > 200, "expected a substantial walk; got ${entries.size}")

        val carrying = entries.filter { it.returnSupertypes.isNotEmpty() }
        val addedChars = carrying.sumOf { entry ->
            entry.returnSupertypes.sumOf { it.length + SUPERTYPE_SEPARATOR.length }
        }
        val rendered = renderArtifactFragmentSource(ArtifactFragment("Measured", "artifact:measured", entries)).length
        val longest = carrying.maxOfOrNull { it.returnSupertypes.size } ?: 0
        println(
            "return ancestry: ${carrying.size} of ${entries.size} entries carry one, " +
                "+$addedChars characters against $rendered rendered " +
                "(${"%.3f".format(100.0 * addedChars / rendered)}%), longest chain $longest",
        )

        assertTrue(carrying.isNotEmpty(), "nothing carries an ancestry, so this measures nothing")
        assertTrue(
            carrying.size * 4 < entries.size,
            "${carrying.size} of ${entries.size} entries carry an ancestry -- it is being asked too widely",
        )
        assertTrue(longest in 1..8, "longest chain $longest")
        assertTrue(addedChars * 100 < rendered, "the ancestry added $addedChars characters to $rendered")
    }

    /**
     * The two halves of the string travel together and come apart again exactly where they were
     * joined. `<:` is the separator because neither character is legal in a Kotlin fully-qualified
     * name -- the same property `${'$'}composer` and `@Composable` rely on -- so a name that
     * contained one would be a name no Kotlin declaration can have.
     */
    @Test
    fun theRenderedNameJoinsTheDeclaredTypeToItsAncestryAndNothingElse() {
        val plain = ArtifactCallable(
            name = "p.q", arity = 0, paramTags = emptyList(), returnTag = "OBJECT", lambdaBody = "{ x() }",
            returnTypeName = "p.A",
        )
        assertEquals("p.A", renderedReturnTypeName(plain))
        assertEquals(
            "p.A<:p.B<:p.C",
            renderedReturnTypeName(plain.copy(returnSupertypes = listOf("p.B", "p.C"))),
        )
        assertEquals(null, renderedReturnTypeName(plain.copy(returnTypeName = null)))
        // No Kotlin fully-qualified name can contain either character, so the grammar cannot be
        // ambiguous -- checked against every name this walk produced rather than argued.
        val graphics = jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop") ?: return
        val names = ArtifactScanner.scanJar(
            graphics,
            includePrefixes = listOf("androidx.compose.ui.graphics"),
            classpath = composeClasspath(),
        ).flatMap { listOfNotNull(it.returnTypeName) + it.returnSupertypes }
        assertEquals(
            emptyList(),
            names.filter { '<' in it || ':' in it },
            "a declared type name contains the separator's characters",
        )
    }
}
