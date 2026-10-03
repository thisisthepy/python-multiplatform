package python.multiplatform.gradle.artifact

/**
 * One JVM type as `python.multiplatform.reflection.TypeTag` sees it, plus the two Kotlin
 * expressions that get a value across the boundary in each direction.
 *
 * @param tag the `TypeTag` constant name. A string rather than the enum, for the reason
 *   `python.multiplatform.ksp.CallableEntryModel` gives for its own: this whole model stays
 *   independent of the runtime module, so it is reachable from a plugin unit test that has no
 *   Kotlin Multiplatform anything on its classpath.
 * @param readFn given the `args[i]` slot expression, produces the declared parameter type.
 * @param wrapFn given the call expression, produces what [tag] promises.
 * @param isReturnOnly `void`, which is a legal return descriptor and never a parameter one.
 */
internal class BoundaryType(
    val tag: String,
    private val readFn: (String) -> String,
    private val wrapFn: (String) -> String,
    val isReturnOnly: Boolean = false,
) {
    /**
     * @param readTemplate `%s` is the `args[i]` slot; the result is the declared parameter type.
     * @param wrapTemplate `%s` is the call expression; the result is what [tag] promises.
     *
     * The primitive table below only ever needs a single textual substitution, so it stays on this
     * constructor. `KotlinMetadata.kt`'s value-class case needs to *compose* one [BoundaryType]
     * inside another (`Meters(%s as Double)` wrapping `%s as Double`) for an arbitrary slot
     * expression supplied later, which a second `%s`-replace on an already-substituted string
     * cannot do -- hence the function-typed primary constructor these two forward to.
     */
    constructor(tag: String, readTemplate: String, wrapTemplate: String, isReturnOnly: Boolean = false) : this(
        tag,
        readFn = { slot -> readTemplate.replace("%s", slot) },
        wrapFn = { call -> wrapTemplate.replace("%s", call) },
        isReturnOnly = isReturnOnly,
    )

    fun read(slot: String): String = readFn(slot)
    fun wrapReturn(call: String): String = wrapFn(call)
}

/**
 * The **descriptor-only** half of the walker's type admission policy: what can be bound from a class
 * file that carries no `kotlin.Metadata` at all, i.e. a Java class.
 *
 * `null` means "this declaration is not bound", and there is still no `OBJECT` fallback here on
 * purpose. A `TypeTag.OBJECT` parameter needs a **Kotlin type name** to cast the boundary's handle
 * to, and a descriptor is not one: `Ljava/util/List;` has no Kotlin spelling at all (Kotlin maps it
 * onto `kotlin.collections.List`, and refuses `java.util.List` written out), a `Ljava/lang/Object;`
 * cast checks nothing, and a value class erases to the primitive it wraps so its descriptor actively
 * lies about what the method takes. Every one of those produces generated Kotlin that either does
 * not compile or compiles into the wrong call.
 *
 * **What changed, and where.** `resolveKotlinType` (`KotlinMetadata.kt`) *does* have an object case,
 * because `@Metadata`'s classifier already **is** the Kotlin name -- `androidx/compose/ui/Modifier`,
 * `/` for `.` and nothing else to guess. So the sentence above is not "the walker cannot bind
 * objects"; it is "a descriptor cannot name one". The Java path keeps the small,
 * obviously-correct subset; the Kotlin path is where Compose is reached.
 */
internal fun boundaryTypeOf(descriptor: String): BoundaryType? = when (descriptor) {
    // `TypeTag.INT` carries a Long, so a narrower integral type converts in both directions --
    // the same widening `FragmentScanner` emits for a Kotlin `Int`.
    "Z" -> BoundaryType("BOOLEAN", "(%s as Boolean)", "(%s)")
    "B" -> BoundaryType("INT", "(%s as Long).toByte()", "(%s).toLong()")
    "S" -> BoundaryType("INT", "(%s as Long).toShort()", "(%s).toLong()")
    "I" -> BoundaryType("INT", "(%s as Long).toInt()", "(%s).toLong()")
    "J" -> BoundaryType("INT", "(%s as Long)", "(%s)")
    "F" -> BoundaryType("FLOAT", "(%s as Double).toFloat()", "(%s).toDouble()")
    "D" -> BoundaryType("FLOAT", "(%s as Double)", "(%s)")
    "Ljava/lang/String;" -> BoundaryType("STRING", "(%s as String)", "(%s)")
    "[B" -> BoundaryType("BYTES", "(%s as ByteArray)", "(%s)")
    "V" -> BoundaryType("UNIT", "(%s as Unit)", "(%s)", isReturnOnly = true)
    // `C` is deliberately absent: Python has no character type, so there is no tag that means one,
    // and mapping it onto INT would make a round trip through Python change the declaration's type.
    else -> null
}

/**
 * Splits a JVM method descriptor into its parameter descriptors and its return descriptor.
 *
 * Written out rather than taken from ASM's `Type`: `Type.getArgumentTypes` allocates a `Type` per
 * parameter and then this would immediately turn each back into the descriptor string
 * [boundaryTypeOf] matches on.
 */
internal fun splitMethodDescriptor(descriptor: String): Pair<List<String>, String> {
    val close = descriptor.indexOf(')')
    require(descriptor.startsWith("(") && close > 0) { "not a method descriptor: $descriptor" }
    val params = mutableListOf<String>()
    var i = 1
    while (i < close) {
        val start = i
        while (descriptor[i] == '[') i++
        if (descriptor[i] == 'L') {
            i = descriptor.indexOf(';', i) + 1
        } else {
            i++
        }
        params += descriptor.substring(start, i)
    }
    return params to descriptor.substring(close + 1)
}
