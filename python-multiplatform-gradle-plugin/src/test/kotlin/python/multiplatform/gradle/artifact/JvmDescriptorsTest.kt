package python.multiplatform.gradle.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the boundary can and cannot carry, stated as data rather than discovered at the point a
 * generated fragment fails to compile.
 *
 * The table this pins is the whole of the walker's admission policy: a declaration is bound if and
 * only if every one of its parameter types and its return type appears here. That is a much
 * stronger filter than `PyREPL`'s (which is name-based, because it emits `.pyi` stubs that never
 * have to compile), and it is what keeps the generated Kotlin buildable without any knowledge of
 * how a jar's types map back onto Kotlin's own.
 */
class JvmDescriptorsTest {

    @Test
    fun primitivesWidenToWhatTheirTagPromises() {
        // `TypeTag.INT` means the boundary carries a Long. A jar's `int` therefore has to narrow on
        // the way in and widen on the way out, exactly as `FragmentScanner` does for Kotlin source.
        val int = boundaryTypeOf("I")!!
        assertEquals("INT", int.tag)
        assertEquals("(args[0] as Long).toInt()", int.read("args[0]"))
        assertEquals("(junit.X.f()).toLong()", int.wrapReturn("junit.X.f()"))

        val long = boundaryTypeOf("J")!!
        assertEquals("INT", long.tag)
        assertEquals("(args[0] as Long)", long.read("args[0]"))

        val float = boundaryTypeOf("F")!!
        assertEquals("FLOAT", float.tag)
        assertEquals("(args[1] as Double).toFloat()", float.read("args[1]"))

        assertEquals("BOOLEAN", boundaryTypeOf("Z")!!.tag)
        assertEquals("FLOAT", boundaryTypeOf("D")!!.tag)
        assertEquals("INT", boundaryTypeOf("B")!!.tag)
        assertEquals("INT", boundaryTypeOf("S")!!.tag)
    }

    @Test
    fun stringAndByteArrayAndVoidAreCarriedDirectly() {
        assertEquals("STRING", boundaryTypeOf("Ljava/lang/String;")!!.tag)
        assertEquals("BYTES", boundaryTypeOf("[B")!!.tag)
        assertEquals("UNIT", boundaryTypeOf("V")!!.tag)
    }

    @Test
    fun everythingElseIsUnbindable() {
        // Not "bound as OBJECT". A `TypeTag.OBJECT` parameter needs a Kotlin type name to cast to,
        // and a jar gives only an erased JVM one: `Ljava/util/List;` has no Kotlin spelling at all
        // (Kotlin maps it to `kotlin.collections.(Mutable)List`), and `Ljava/lang/Object;` would
        // erase away the very type the cast exists to check. So the walker declines rather than
        // emitting source that may not compile.
        assertNull(boundaryTypeOf("Ljava/util/List;"))
        assertNull(boundaryTypeOf("Ljava/lang/Object;"))
        assertNull(boundaryTypeOf("[Ljava/lang/String;"))
        assertNull(boundaryTypeOf("[I"))
        // `char` has no Python counterpart and no obvious tag; declined rather than guessed at.
        assertNull(boundaryTypeOf("C"))
    }

    @Test
    fun descriptorsSplitIntoParametersAndAReturn() {
        assertEquals(
            listOf("Ljava/lang/String;", "Z") to "V",
            splitMethodDescriptor("(Ljava/lang/String;Z)V"),
        )
        assertEquals(emptyList<String>() to "Ljava/lang/String;", splitMethodDescriptor("()Ljava/lang/String;"))
        assertEquals(listOf("[B", "[B") to "V", splitMethodDescriptor("([B[B)V"))
        assertEquals(listOf("J", "J") to "V", splitMethodDescriptor("(JJ)V"))
        assertEquals(listOf("[Ljava/lang/String;") to "V", splitMethodDescriptor("([Ljava/lang/String;)V"))
    }

    @Test
    fun aVoidParameterIsNotAThing() {
        // `V` is a return-position-only descriptor. It is admitted by `boundaryTypeOf` because the
        // return position needs it; a parameter carrying it would be a malformed class file, and
        // the walker must not turn that into generated source that reads plausibly.
        assertTrue(boundaryTypeOf("V")!!.isReturnOnly)
    }
}
