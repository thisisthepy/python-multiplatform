package python.multiplatform.ksp

import kotlin.test.Test
import kotlin.test.assertEquals

class TypeShapeTest {

    @Test
    fun tagMapsThePrimitivesBundledUnderIntAndFloat() {
        assertEquals(Tag.INT, tagFor(TypeShape("kotlin.Long", false)))
        assertEquals(Tag.INT, tagFor(TypeShape("kotlin.Int", false)))
        assertEquals(Tag.INT, tagFor(TypeShape("kotlin.Short", false)))
        assertEquals(Tag.INT, tagFor(TypeShape("kotlin.Byte", false)))
        assertEquals(Tag.FLOAT, tagFor(TypeShape("kotlin.Double", false)))
        assertEquals(Tag.FLOAT, tagFor(TypeShape("kotlin.Float", false)))
    }

    @Test
    fun tagFallsBackToObjectForAnythingWithoutADedicatedMarshaller() {
        assertEquals(Tag.OBJECT, tagFor(TypeShape("com.example.MyClass", false)))
        assertEquals(Tag.OBJECT, tagFor(TypeShape("python.multiplatform.ffi.PyObject", true)))
    }

    @Test
    fun castOfTheBoundaryWidthTypeIsAPlainCast() {
        assertEquals("args[0] as Long", castExpression(TypeShape("kotlin.Long", false), "args[0]"))
        assertEquals("args[0] as Long?", castExpression(TypeShape("kotlin.Long", true), "args[0]"))
        assertEquals("args[0] as String", castExpression(TypeShape("kotlin.String", false), "args[0]"))
    }

    @Test
    fun castOfANarrowerIntTypeNarrowsAfterCastingToTheBoundaryWidth() {
        // The boundary always carries INT-tagged values as Long; a declared `Int` parameter
        // needs a narrowing conversion the cast alone does not give it.
        assertEquals("(args[0] as Long).toInt()", castExpression(TypeShape("kotlin.Int", false), "args[0]"))
        assertEquals("(args[0] as Long?)?.toInt()", castExpression(TypeShape("kotlin.Int", true), "args[0]"))
        assertEquals("(args[0] as Long).toShort()", castExpression(TypeShape("kotlin.Short", false), "args[0]"))
        assertEquals("(args[0] as Long).toByte()", castExpression(TypeShape("kotlin.Byte", false), "args[0]"))
    }

    @Test
    fun castOfFloatNarrowsFromTheBoundaryDouble() {
        assertEquals("(args[0] as Double).toFloat()", castExpression(TypeShape("kotlin.Float", false), "args[0]"))
        assertEquals("(args[0] as Double?)?.toFloat()", castExpression(TypeShape("kotlin.Float", true), "args[0]"))
    }

    @Test
    fun castOfAnObjectTypeUsesItsQualifiedName() {
        assertEquals(
            "args[0] as com.example.Counter",
            castExpression(TypeShape("com.example.Counter", false), "args[0]"),
        )
        assertEquals(
            "args[0] as com.example.Counter?",
            castExpression(TypeShape("com.example.Counter", true), "args[0]"),
        )
    }

    @Test
    fun returnWrapWidensNarrowerNumericTypesToTheBoundaryRepresentation() {
        assertEquals("(x.increment()).toLong()", wrapReturnExpression(TypeShape("kotlin.Int", false), "x.increment()"))
        assertEquals("(x.scale()).toDouble()", wrapReturnExpression(TypeShape("kotlin.Float", false), "x.scale()"))
    }

    @Test
    fun returnWrapOfUnitAppendsAnExplicitUnitSoTheLambdaTypeChecks() {
        assertEquals("x.reset(); Unit", wrapReturnExpression(TypeShape("kotlin.Unit", false), "x.reset()"))
    }

    @Test
    fun returnWrapOfTheBoundaryWidthOrObjectTypesPassesThrough() {
        assertEquals("x.add()", wrapReturnExpression(TypeShape("kotlin.Long", false), "x.add()"))
        assertEquals("x.build()", wrapReturnExpression(TypeShape("com.example.Widget", false), "x.build()"))
    }

    @Test
    fun castOfAGenericTypeKeepsItsTypeArgumentsOrTheGeneratedCodeDoesNotCompile() {
        // `args[0] as kotlin.collections.List` is not valid Kotlin -- "One type argument
        // expected". Observed as a real compile failure of a generated fragment before the
        // renderer carried arguments through.
        val listOfString = TypeShape(
            qualifiedName = "kotlin.collections.List",
            nullable = false,
            rendered = "kotlin.collections.List<kotlin.String>",
        )
        assertEquals("args[0] as kotlin.collections.List<kotlin.String>", castExpression(listOfString, "args[0]"))

        val nullableMap = TypeShape(
            qualifiedName = "kotlin.collections.Map",
            nullable = true,
            rendered = "kotlin.collections.Map<kotlin.String, kotlin.Long>",
        )
        assertEquals(
            "args[0] as kotlin.collections.Map<kotlin.String, kotlin.Long>?",
            castExpression(nullableMap, "args[0]"),
        )
    }

    @Test
    fun shapeWithoutAnExplicitRenderingFallsBackToItsQualifiedName() {
        assertEquals("com.example.Counter", TypeShape("com.example.Counter", false).rendered)
    }
}
