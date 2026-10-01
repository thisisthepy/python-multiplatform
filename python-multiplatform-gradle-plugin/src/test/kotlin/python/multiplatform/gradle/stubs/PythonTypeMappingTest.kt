package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.KotlinTypeModel
import python.multiplatform.gradle.model.ValueClassModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/design/pyi-generation-design.md` §3.1's table, one row per test where the row carries a decision
 * and grouped where it does not.
 *
 * The mapping is from the **declared Kotlin type** (`KotlinTypeModel`), not from a `TypeTag` and not
 * from a JVM descriptor: §2.2's whole argument is that a tag says how a value marshals and a stub
 * has to say what it *is*. `Dp` and `Float` are both `TypeTag.FLOAT` and they are not the same row.
 */
class PythonTypeMappingTest {

    private fun type(name: String, nullable: Boolean = false, vararg args: KotlinTypeModel) =
        KotlinTypeModel(name, isNullable = nullable, arguments = args.toList())

    private fun render(type: KotlinTypeModel, allowlist: Set<String> = emptySet()) =
        PythonTypes.render(type, allowlist)

    @Test
    fun everyKotlinIntegerWidthIsOnePythonInt() {
        listOf("kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long").forEach {
            assertEquals("int", render(type(it)).expression, it)
        }
    }

    @Test
    fun theRemainingPrimitivesMapOneForOne() {
        assertEquals("bool", render(type("kotlin.Boolean")).expression)
        assertEquals("float", render(type("kotlin.Float")).expression)
        assertEquals("float", render(type("kotlin.Double")).expression)
        assertEquals("str", render(type("kotlin.String")).expression)
        assertEquals("bytes", render(type("kotlin.ByteArray")).expression)
        assertEquals("None", render(type("kotlin.Unit")).expression)
    }

    /** `Nothing` is `typing.Never` -- a function returning it never returns, and `Never` is the
     * exact Python spelling for that. The import has to be reported or the stub does not resolve. */
    @Test
    fun nothingIsNeverAndAsksForItsImport() {
        val rendered = render(type("kotlin.Nothing"))
        assertEquals("Never", rendered.expression)
        assertEquals(setOf("Never"), rendered.typingImports)
    }

    /** §3.3: `T | None`, not `Optional[T]` -- a stub is never executed, so the 3.10+ spelling costs
     * nothing and needs no `from __future__ import annotations`. */
    @Test
    fun aNullableTypeIsAUnionWithNone() {
        assertEquals("str | None", render(type("kotlin.String", nullable = true)).expression)
        assertEquals("int | None", render(type("kotlin.Int", nullable = true)).expression)
        assertEquals(
            "Modifier | None",
            render(type("androidx.compose.ui.Modifier", nullable = true)).expression,
        )
    }

    @Test
    fun collectionsCarryTheirTypeArguments() {
        assertEquals("list[str]", render(type("kotlin.collections.List", false, type("kotlin.String"))).expression)
        assertEquals("list[str]", render(type("kotlin.collections.MutableList", false, type("kotlin.String"))).expression)
        assertEquals("set[int]", render(type("kotlin.collections.Set", false, type("kotlin.Int"))).expression)
        assertEquals(
            "dict[str, int]",
            render(type("kotlin.collections.Map", false, type("kotlin.String"), type("kotlin.Int"))).expression,
        )
    }

    /** `Collection`/`Iterable` are read-only shapes: `Sequence` would over-promise indexing. */
    @Test
    fun aReadOnlyCollectionIsIterableAndNotSequence() {
        val rendered = render(type("kotlin.collections.Iterable", false, type("kotlin.String")))
        assertEquals("Iterable[str]", rendered.expression)
        assertEquals(setOf("Iterable"), rendered.typingImports)
        assertEquals("Iterable[str]", render(type("kotlin.collections.Collection", false, type("kotlin.String"))).expression)
    }

    /** `Array<T>` is a list on the Python side -- judged in §3.1 -- and the primitive arrays follow,
     * `ByteArray` excepted because it is `bytes`. */
    @Test
    fun arraysAreListsExceptForByteArray() {
        assertEquals("list[str]", render(type("kotlin.Array", false, type("kotlin.String"))).expression)
        assertEquals("list[int]", render(type("kotlin.IntArray")).expression)
        assertEquals("list[int]", render(type("kotlin.LongArray")).expression)
        assertEquals("list[float]", render(type("kotlin.DoubleArray")).expression)
        assertEquals("bytes", render(type("kotlin.ByteArray")).expression)
    }

    /** §3.5. `Function0`'s single type argument is its return; `FunctionN`'s last one is. */
    @Test
    fun functionTypesBecomeCallable() {
        val nullary = render(type("kotlin.Function0", false, type("kotlin.Unit")))
        assertEquals("Callable[[], None]", nullary.expression)
        assertEquals(setOf("Callable"), nullary.typingImports)

        assertEquals(
            "Callable[[int, str], bool]",
            render(type("kotlin.Function2", false, type("kotlin.Int"), type("kotlin.String"), type("kotlin.Boolean"))).expression,
        )
    }

    /**
     * §3.4, the one row where the mapping is a *policy* and not a translation.
     *
     * `Dp` is on the allowlist because its public constructor is the identity on the float it wraps,
     * so `padding(16)` and `padding(Dp(16f))` mean the same thing. `TextUnit` is not: it packs, and
     * a raw `16` decodes as `Unspecified` -- silently no value at all. The stub states the difference
     * as one union member, and §4.4's mypy run confirms a checker acts on it.
     */
    @Test
    fun aValueClassOnTheAllowlistAdmitsItsRawUnderlyingType() {
        val dp = KotlinTypeModel(
            "androidx.compose.ui.unit.Dp",
            valueClass = ValueClassModel(KotlinTypeModel("kotlin.Float"), constructorIsPublic = true, propertyIsPublic = true),
        )
        assertEquals("Dp | float", render(dp, allowlist = setOf("androidx.compose.ui.unit.Dp")).expression)
        assertEquals("Dp", render(dp, allowlist = emptySet()).expression, "off the allowlist it is the wrapper alone")
    }

    @Test
    fun aValueClassOffTheAllowlistIsTheWrapperAlone() {
        val textUnit = KotlinTypeModel(
            "androidx.compose.ui.unit.TextUnit",
            valueClass = ValueClassModel(KotlinTypeModel("kotlin.Long"), constructorIsPublic = true, propertyIsPublic = true),
        )
        assertEquals("TextUnit", render(textUnit, allowlist = setOf("androidx.compose.ui.unit.Dp")).expression)
    }

    /** Any other class is its own stub name, and the generator has to know it referred to it so the
     * emitting module can import or declare it. */
    @Test
    fun anOrdinaryClassIsItsOwnStubNameAndIsReported() {
        val rendered = render(type("androidx.compose.ui.Modifier"))
        assertEquals("Modifier", rendered.expression)
        assertEquals(setOf("androidx.compose.ui.Modifier"), rendered.referencedClasses)
        assertEquals(emptySet(), rendered.typingImports)
    }
}
