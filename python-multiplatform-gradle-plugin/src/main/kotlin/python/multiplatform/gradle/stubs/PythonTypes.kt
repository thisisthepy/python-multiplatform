package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.KotlinTypeModel

/**
 * One rendered Python type expression, and what emitting it obliges the file to carry.
 *
 * @param typingImports names from `typing` (`Callable`, `Iterable`, `Never`, `Any`).
 * @param referencedClasses Kotlin qualified names of classes whose own stub this expression points
 *   at. The emitting module has to declare or import each one, and a stub that references a class
 *   nobody stubs is `docs/design/pyi-generation-design.md` §3.1's last-but-one row -- "a promise the runtime
 *   does not yet keep".
 */
internal data class PythonType(
    val expression: String,
    val typingImports: Set<String> = emptySet(),
    val referencedClasses: Set<String> = emptySet(),
)

/**
 * The package half of a Kotlin qualified name, split on the case convention rather than on the last
 * dot.
 *
 * `@Metadata` spells a nested class `Outer.Inner` and its package with `/`
 * (`kotlinClassifierNameOf` turns that into `androidx.compose.ui.Alignment.Horizontal`), so
 * `substringBeforeLast('.')` reads `Alignment` as a package -- which produced a
 * `pythonx/compose/ui/Alignment/` **directory** for a class that has no module of its own. Kotlin
 * package segments are lowercase by convention and by every artefact this walks; the first segment
 * that is not is where the class name starts.
 */
internal fun packagePartOf(qualifiedName: String): String = qualifiedName.split('.')
    .takeWhile { it.firstOrNull()?.isUpperCase() != true }
    .joinToString(".")

/** The class half of the same split, as Python spells it: `Alignment.Horizontal`. */
internal fun pythonClassPathOf(qualifiedName: String): String = qualifiedName.split('.')
    .dropWhile { it.firstOrNull()?.isUpperCase() != true }
    .joinToString(".") { PythonNames.typeName(it) }
    .ifEmpty { PythonNames.typeName(qualifiedName.substringAfterLast('.')) }

/**
 * `docs/design/pyi-generation-design.md` §3.1's table.
 *
 * The input is the **declared Kotlin type**, never a `TypeTag`: §2.2's argument is that a tag says
 * how a value marshals and a stub has to say what it is. `Dp` and `Float` are both `TypeTag.FLOAT`
 * and they are not the same row -- which is the whole of why §3.4's allowlist is expressible at all.
 */
internal object PythonTypes {

    private val PRIMITIVES = mapOf(
        "kotlin.Boolean" to "bool",
        "kotlin.Byte" to "int",
        "kotlin.Short" to "int",
        "kotlin.Int" to "int",
        "kotlin.Long" to "int",
        // Python has one integer type, so narrowing the boundary's `Long` back down is invisible
        // and correct -- §3.1's note on the integer row.
        "kotlin.Float" to "float",
        "kotlin.Double" to "float",
        "kotlin.String" to "str",
        "kotlin.ByteArray" to "bytes",
        "kotlin.Unit" to "None",
        "kotlin.CharSequence" to "str",
        "kotlin.Char" to "str",
    )

    private val PRIMITIVE_ARRAYS = mapOf(
        "kotlin.IntArray" to "int",
        "kotlin.LongArray" to "int",
        "kotlin.ShortArray" to "int",
        "kotlin.CharArray" to "str",
        "kotlin.FloatArray" to "float",
        "kotlin.DoubleArray" to "float",
        "kotlin.BooleanArray" to "bool",
    )

    private val COLLECTIONS = mapOf(
        "kotlin.collections.List" to "list",
        "kotlin.collections.MutableList" to "list",
        "kotlin.collections.Set" to "set",
        "kotlin.collections.MutableSet" to "set",
        "kotlin.collections.Map" to "dict",
        "kotlin.collections.MutableMap" to "dict",
    )

    /**
     * @param rawPrimitiveValueClasses §3.4's allowlist, which is data and not a rule: the machine
     *   test "has a public constructor taking exactly its underlying type" selects 36 of 111 and
     *   admits `Color`, whose `Color(Int)` factory shifts by 32 while the constructor stores. Whether
     *   a wrapper packs is a semantic fact with no bytecode witness, so it comes from the manifest
     *   (§5.3).
     */
    fun render(type: KotlinTypeModel, rawPrimitiveValueClasses: Set<String>): PythonType {
        val base = renderNonNull(type, rawPrimitiveValueClasses)
        if (!type.isNullable) return base
        // §3.3: `T | None`, not `Optional[T]`. A stub is never executed, so the 3.10+ spelling costs
        // nothing and needs no `from __future__ import annotations`.
        return base.copy(expression = base.expression + " | None")
    }

    private fun renderNonNull(type: KotlinTypeModel, allowlist: Set<String>): PythonType {
        PRIMITIVES[type.qualifiedName]?.let { return PythonType(it) }
        PRIMITIVE_ARRAYS[type.qualifiedName]?.let { return PythonType("list[$it]") }
        if (type.qualifiedName == "kotlin.Nothing") return PythonType("Never", typingImports = setOf("Never"))
        if (type.qualifiedName == "kotlin.Any") return PythonType("Any", typingImports = setOf("Any"))

        COLLECTIONS[type.qualifiedName]?.let { container ->
            val arguments = type.arguments.map { renderArgument(it, allowlist) }
            return combine("$container[${arguments.joinToString(", ") { it.expression }}]", arguments)
        }
        if (type.qualifiedName == "kotlin.collections.Iterable" || type.qualifiedName == "kotlin.collections.Collection") {
            // Read-only shape: `Sequence` would over-promise indexing.
            val arguments = type.arguments.map { renderArgument(it, allowlist) }
            return combine("Iterable[${arguments.joinToString(", ") { it.expression }}]", arguments)
                .let { it.copy(typingImports = it.typingImports + "Iterable") }
        }
        if (type.qualifiedName == "kotlin.Array") {
            val arguments = type.arguments.map { renderArgument(it, allowlist) }
            return combine("list[${arguments.joinToString(", ") { it.expression }}]", arguments)
        }
        if (type.qualifiedName.startsWith("kotlin.Function")) {
            // §3.5. The last type argument is the return; the rest are the parameters. A
            // `@Composable () -> Unit` and a `() -> Unit` are the same Python `Callable` and the
            // difference is deliberately not faked -- Kotlin's "only in a composable context" rule
            // has no Python counterpart a checker could enforce.
            val arguments = type.arguments.map { renderArgument(it, allowlist) }
            val returned = arguments.lastOrNull()?.expression ?: "None"
            val parameters = arguments.dropLast(1).joinToString(", ") { it.expression }
            return combine("Callable[[$parameters], $returned]", arguments)
                .let { it.copy(typingImports = it.typingImports + "Callable") }
        }

        val simple = pythonClassPathOf(type.qualifiedName)
        val valueClass = type.valueClass
        if (valueClass != null && type.qualifiedName in allowlist) {
            // §3.4: `Dp | float`. The union's second member is the *underlying* type, which is what
            // makes `Modifier.padding(16)` check and `Text("hi", font_size=16)` not.
            val underlying = render(valueClass.underlying, allowlist)
            return PythonType(
                expression = "$simple | ${underlying.expression}",
                typingImports = underlying.typingImports,
                referencedClasses = setOf(type.qualifiedName),
            )
        }
        return PythonType(simple, referencedClasses = setOf(type.qualifiedName))
    }

    /** A star projection has no type of its own; `Any` is the only honest Python spelling for it. */
    private fun renderArgument(argument: KotlinTypeModel?, allowlist: Set<String>): PythonType =
        argument?.let { render(it, allowlist) } ?: PythonType("Any", typingImports = setOf("Any"))

    private fun combine(expression: String, parts: List<PythonType>): PythonType = PythonType(
        expression = expression,
        typingImports = parts.flatMapTo(LinkedHashSet()) { it.typingImports },
        referencedClasses = parts.flatMapTo(LinkedHashSet()) { it.referencedClasses },
    )
}
