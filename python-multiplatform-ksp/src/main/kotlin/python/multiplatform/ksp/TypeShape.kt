package python.multiplatform.ksp

/**
 * The boundary's five-plus-two marshalling categories (`docs/design/upcall.md` §2.1,
 * `python.multiplatform.reflection.TypeTag`). Kept as a processor-local copy rather than a
 * dependency on the runtime module's enum so this file has no KSP or runtime import and its
 * mapping logic is plain-JVM-testable.
 */
enum class Tag { INT, FLOAT, BOOLEAN, STRING, BYTES, UNIT, OBJECT }

/**
 * Everything the cast/wrap logic needs about one parameter or return type, independent of KSP.
 * A `KSType` is reduced to this before any codegen decision is made, so [tagFor],
 * [castExpression] and [wrapReturnExpression] can be unit-tested without a real `Resolver`.
 */
data class TypeShape(
    val qualifiedName: String,
    val nullable: Boolean,
    /**
     * The type as it must appear in generated source, **without** the trailing `?` --
     * [qualifiedName] plus its type arguments where it has them, e.g.
     * `kotlin.collections.List<kotlin.String>`. A cast to the bare qualified name of a generic
     * type is not valid Kotlin ("One type argument expected"), and that surfaced as a compile
     * failure of the *generated* file rather than as anything the processor could notice.
     */
    val rendered: String = qualifiedName,
)

/** Which [Tag] a Kotlin type is marshalled as. Anything not one of the seven primitives crosses
 * as [Tag.OBJECT] -- a raw Kotlin reference, not a boxed value. */
fun tagFor(shape: TypeShape): Tag = when (shape.qualifiedName) {
    "kotlin.Long", "kotlin.Int", "kotlin.Short", "kotlin.Byte" -> Tag.INT
    "kotlin.Double", "kotlin.Float" -> Tag.FLOAT
    "kotlin.Boolean" -> Tag.BOOLEAN
    "kotlin.String" -> Tag.STRING
    "kotlin.ByteArray" -> Tag.BYTES
    "kotlin.Unit" -> Tag.UNIT
    else -> Tag.OBJECT
}

/**
 * How to read one parameter out of the `args: Array<Any?>` the trampoline builds, given the
 * expression [argsExpr] that indexes into it (e.g. `"args[0]"`).
 *
 * The boundary carries exactly one representation per [Tag] -- `Long` for every INT-tagged
 * value regardless of whether the Kotlin parameter is `Int`, `Short` or `Byte`; `Double` for
 * every FLOAT-tagged one -- so a narrower declared type needs a narrowing conversion on top of
 * the plain cast. `TestFragments.kt`'s hand-written entries only ever declare `Long`/`Double`
 * parameters, which is why this case never shows up there; a generated entry over a user's
 * `fun scale(by: Int)` needs it.
 */
fun castExpression(shape: TypeShape, argsExpr: String): String {
    val q = if (shape.nullable) "?" else ""
    return when (shape.qualifiedName) {
        "kotlin.Long" -> "$argsExpr as Long$q"
        "kotlin.Double" -> "$argsExpr as Double$q"
        "kotlin.Boolean" -> "$argsExpr as Boolean$q"
        "kotlin.String" -> "$argsExpr as String$q"
        "kotlin.ByteArray" -> "$argsExpr as ByteArray$q"
        "kotlin.Int" -> if (shape.nullable) "($argsExpr as Long?)?.toInt()" else "($argsExpr as Long).toInt()"
        "kotlin.Short" -> if (shape.nullable) "($argsExpr as Long?)?.toShort()" else "($argsExpr as Long).toShort()"
        "kotlin.Byte" -> if (shape.nullable) "($argsExpr as Long?)?.toByte()" else "($argsExpr as Long).toByte()"
        "kotlin.Float" -> if (shape.nullable) "($argsExpr as Double?)?.toFloat()" else "($argsExpr as Double).toFloat()"
        else -> "$argsExpr as ${shape.rendered}$q"
    }
}

/** Wraps a call-result expression [callExpr] into the canonical boundary representation for
 * [shape]'s [Tag]. The inverse widening to [castExpression]'s narrowing. */
fun wrapReturnExpression(shape: TypeShape, callExpr: String): String = when (shape.qualifiedName) {
    "kotlin.Int", "kotlin.Short", "kotlin.Byte" ->
        if (shape.nullable) "($callExpr)?.toLong()" else "($callExpr).toLong()"
    "kotlin.Float" ->
        if (shape.nullable) "($callExpr)?.toDouble()" else "($callExpr).toDouble()"
    "kotlin.Unit" -> "$callExpr; Unit"
    else -> callExpr
}
