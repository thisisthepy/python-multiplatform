package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.KotlinTypeModel
import python.multiplatform.gradle.model.ValueClassModel

/**
 * The stub product: `docs/design/pyi-generation-design.md` §5.3's Kotlin-FQN stubs, rendered from the
 * declaration model.
 *
 * One `def` per bound declaration, in the module the runtime publishes it onto, under the Kotlin
 * names. The binder never exports a Kotlin namespace under another name (AGENTS.md §12 rule 1), so
 * there is no second, renamed product and nothing under `pythonx`: a `pythonx` module is a real
 * Python package that imports these modules, and its own stubs are its own.
 *
 * ### Pythonic names (issue #131)
 *
 * The runtime serves every lower-case-first declaration and member under its snake_case alias too,
 * and takes each keyword by its Kotlin name or its snake_case one ([pythonName], [snakeCase],
 * [pythonicAliases]; the runtime's rule in `KotlinSurface.kt`). The stub says the same:
 *
 * - **parameters** are written under the keyword `inspect.signature` shows -- the snake_case alias
 *   where one is served for that declaration, else the Kotlin name. A stub can name a parameter only
 *   once, and it names it the way the signature does; a Kotlin keyword still works at run time, but a
 *   checker reports it;
 * - **module-level names** keep their Kotlin `def` (or constant), followed by `alias = kotlinName` for
 *   each alias the runtime serves in that module, so the alias has exactly the Kotlin name's type;
 * - **members of a receiver's class** get the alias as a second `ClassVar` of the same `Protocol`, or a
 *   second `@property` with the same table key in its docstring;
 * - members of a callable module (#78) get the alias as a second member.
 *
 * Not aliased: a class module folded into its parent for a case-insensitive filesystem (#44) -- its
 * functions keep their Kotlin names only in the stub, although the runtime serves the alias.
 *
 * Returns *relative path to file content*, so nothing here touches a disk and every assertion in
 * `PyiRenderingTest` and `TypedStubTest` is over text. `PythonStubsTask` is the only thing that writes.
 *
 * ### What a stub says about a type (issue #31)
 *
 * The declared Kotlin type, wherever the binder serves one:
 *
 * | Kotlin | stub | why |
 * |---|---|---|
 * | `Int` `Long` `Float` `Boolean` `String` `ByteArray` `Unit` | `int` `float` `bool` `str` `bytes` `None` | what crosses |
 * | any other class or interface | a stub class **in the module of its own Kotlin package** | `UpcallTrampoline` hands Python a handle and the binding layer wraps it in a proxy class named for the declared type |
 * | value class the binder binds as its primitive (`Dp`) | `Dp \| float` as a parameter, `float` as a result | a result is the raw number (`_wrap` only wraps `OBJECT`); the raw module accepts the number for a parameter, and the binding layer's allowlist (`allow_raw_primitive`) decides at run time, which no stub can know |
 * | value class the binder cannot open (`TextUnit`) | its own stub class | it crosses as an object handle |
 * | `(A, B) -> R` | `Callable[[A, B], R]` | `kotlin.FunctionN`'s type arguments |
 * | `T?` | `T \| None` | `KotlinTypeModel.isNullable` |
 *
 * A class whose name is also a function in its module (`PaddingValues(...)` the factory and
 * `PaddingValues` the interface) cannot be both in one Python module, and the runtime resolves the
 * name to the function, so such a type is annotated `Any`. A class's **own constructor** is the
 * exception (issue #73): it is rendered as the class's `__init__`, so the class keeps its stub --
 * members, properties and all -- and `TextFieldState(...)` type-checks as a `TextFieldState`, which is
 * what the runtime's constructor callable returns; its docstring carries the table key after
 * [CONSTRUCTOR_MARKER]. Its `__`-suffixed table-key spellings stay module functions as well. A value
 * class bound as its primitive is not the exception: its constructor returns the raw number. (`isinstance(x, TextFieldState)` is the one thing the stub promises that the
 * runtime, whose module attribute is the callable, does not do.)
 *
 * A Kotlin `object` (`Alignment`, `Arrangement`) is a module of its constants and functions, and the
 * types nested in it (`Alignment.Horizontal`) are classes of that same module, so
 * `Alignment.End` is annotated `Horizontal` rather than `Any` (issue #53). The object's own type
 * (`Alignment.Center: Alignment`) has no class to name and stays `Any`.
 *
 * ### Extension functions are also methods
 *
 * The runtime attaches an extension to the receiver's proxy class (`PythonxAdapter._attach`), reached
 * as `m.padding(...)` and `Modifier.padding(...)` alike. A plain method cannot say both -- the
 * second binds the argument to `self` -- so each extension is a **callable attribute** of the
 * receiver's stub class whose type is a `Protocol` with `__call__` (a measured shape: archive
 * `pyi-generation-pythonic-stubs.md` §4.4). Both the Kotlin name (an overload set, `@overload`ed) and
 * each table-key spelling (`padding__Dp`) are attributes, because the runtime indexes both.
 * The module-level `def`s stay, with the receiver as the first positional-only parameter.
 *
 * ### Properties (issue #38)
 *
 * A `GETTER`/`SETTER` key is not a module attribute: the binding layer serves it as a Python
 * `property` of the proxy of the type it is read on (`receiver`). So it is stubbed there, as
 * `@property` (plus `@<name>.setter` for a `var`), and each accessor's docstring carries its table key
 * after [PROPERTY_MARKER] -- the one place a test can match a key to its stub. A property whose
 * receiver has no stub class (its name is taken by a function or a module of its package, so the
 * type is annotated `Any`) cannot be written anywhere true; it gets a comment carrying the same
 * marker in the receiver package's module, saying so, rather than vanishing.
 *
 * ### Overloads
 *
 * An overload set is `@overload`ed under its base name, **in table-key order**: the artefact table is
 * sorted by name (`ArtifactScanner.scanJar`), the binding layer indexes its candidates in that order
 * and `_Overloads` tries them in it, and a checker takes the first match.
 */

private const val HEADER = "# GENERATED by python-multiplatform-gradle-plugin. Do not edit."

/** The module alias every stub imports `typing` as: a Kotlin declaration may be called `Any`. */
private const val TYPING = "_t"

private const val ANY = "$TYPING.Any"

private const val RECEIVER_NAME = "receiver"

internal fun renderKotlinFqnStubs(declarations: List<DeclarationModel>): Map<String, String> =
    StubRenderer(declarations.filter { it.bindingName != null && !it.isSuspend }).render()

/** What precedes a property accessor's table key in its stub; see this file's KDoc. */
internal const val PROPERTY_MARKER = "Kotlin property: "

/**
 * What precedes a constructor's table key in the docstring of the `__init__` it is stubbed as (issue
 * #73): the key (`pkg.Class`) is a module attribute at run time but has no module-level `def` in the
 * stub, so this is where a test matches the key to its stub, the same way [PROPERTY_MARKER] does for a
 * property. An `@overload`ed `__init__` carries none: every member of an overload set is also stubbed
 * as a module function under its `__`-suffixed key.
 */
internal const val CONSTRUCTOR_MARKER = "Kotlin constructor: "

private val PROPERTY_KINDS = setOf("GETTER", "SETTER")

/** Python's hard keywords (`keyword.kwlist`): none of these can be a parameter name. */
private val PYTHON_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
    "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
    "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
    "try", "while", "with", "yield",
)

private fun isIdentifier(name: String): Boolean =
    name.isNotEmpty() && name !in PYTHON_KEYWORDS &&
        (name[0] == '_' || name[0].isLetter()) &&
        name.all { it == '_' || it.isLetterOrDigit() }

private fun isWritableName(name: String?, reserved: String?): Boolean =
    name != null && name != reserved && isIdentifier(name)

/** What the docstring says: the declaration as Kotlin wrote it, which is the information the
 * annotations do not always carry (a type that fell back to `Any`, a function type's receiver). */
private fun kotlinSignatureOf(declaration: DeclarationModel): String {
    val owner = declaration.receiver?.qualifiedName ?: declaration.owner
    val parameters = declaration.parameters.joinToString(", ") { parameter ->
        val type = parameter.type.qualifiedName + if (parameter.type.isNullable) "?" else ""
        if (declaration.parameterNamesKnown && parameter.name != null) "${parameter.name}: $type" else type
    }
    return "$owner.${declaration.simpleName}($parameters): ${declaration.returnType.qualifiedName}"
}

private fun builtinOf(qualifiedName: String): String? = when (qualifiedName) {
    "kotlin.Boolean" -> "bool"
    "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long" -> "int"
    "kotlin.Float", "kotlin.Double" -> "float"
    "kotlin.String", "kotlin.Char" -> "str"
    "kotlin.ByteArray" -> "bytes"
    "kotlin.Unit" -> "None"
    "kotlin.Any" -> ANY
    "kotlin.Nothing" -> "$TYPING.NoReturn"
    else -> null
}

/** `python.multiplatform.reflection.TypeTag` to the Python type that actually crosses. */
private fun builtinOfTag(tag: String?): String? = when (tag) {
    "INT" -> "int"
    "FLOAT" -> "float"
    "BOOLEAN" -> "bool"
    "STRING" -> "str"
    "BYTES" -> "bytes"
    "UNIT" -> "None"
    else -> null
}

private val FUNCTION_TYPE = Regex("""kotlin\.Function\d+""")

/** A Kotlin class's qualified name split into its package and its (possibly nested) class path. */
private class ClassRef(val pkg: String, val path: List<String>) {
    val top: String get() = path[0]
}

/**
 * `androidx.compose.ui.Modifier.Companion` -> package `androidx.compose.ui`, path `Modifier.Companion`.
 *
 * Kotlin's qualified name does not say where the package ends, so this uses the convention every
 * JetBrains and AndroidX package follows: package segments are lower case, a class starts upper
 * case. A name with no upper-case segment is taken to be a package plus one lower-case class.
 */
private fun classRefOf(qualifiedName: String): ClassRef? {
    val segments = qualifiedName.split('.')
    val firstClass = segments.indexOfFirst { it.isNotEmpty() && it[0].isUpperCase() }
    val split = if (firstClass < 0) segments.size - 1 else firstClass
    if (split <= 0) return null
    return ClassRef(segments.take(split).joinToString("."), segments.drop(split))
}

private enum class Role { PARAM, RESULT }

private class StubRenderer(declarations: List<DeclarationModel>) {

    /** Everything that is a module attribute; properties are attributes of a proxy instead. */
    private val bound: List<DeclarationModel> = declarations.filter { it.kind !in PROPERTY_KINDS }

    /** receiver type -> property name -> (getter, setter). A setter never stands without its getter. */
    private val properties: Map<String, Map<String, Pair<DeclarationModel, DeclarationModel?>>> = run {
        val accessors = declarations.filter { it.kind in PROPERTY_KINDS && it.receiver != null }
        val setters = accessors.filter { it.kind == "SETTER" }.associateBy { it.bindingName!!.removeSuffix("=") }
        accessors.filter { it.kind == "GETTER" }
            .groupBy { it.receiver!!.qualifiedName }
            .mapValues { (_, getters) ->
                getters.sortedBy { it.simpleName }.associate { it.simpleName to (it to setters[it.bindingName]) }
            }
    }

    // ------------------------------------------------------------------------ what is taken

    private fun moduleOf(d: DeclarationModel) = d.bindingName!!.substringBeforeLast('.')
    private fun leafOf(d: DeclarationModel) = d.bindingName!!.substringAfterLast('.')

    /** Mirrors `_Decl`: `base, _, suffix = leaf.partition('__')`, and the runtime indexes both. */
    private fun isSuffixed(leaf: String): Boolean {
        val base = leaf.substringBefore("__")
        return "__" in leaf && base.isNotEmpty() && leaf.length > base.length + 2
    }

    private fun baseOf(leaf: String): String = leaf.substringBefore("__")

    private val byModule: Map<String, List<DeclarationModel>> =
        bound.groupBy { moduleOf(it) }.toSortedMap()

    /**
     * A class's own constructor, published in the class's own package under the class's name (issue
     * #73). It does not take that name away from the class: the stub writes it as the class's
     * `__init__`, so `TextFieldState(...)` type-checks as making a `TextFieldState` -- which is what the
     * runtime's constructor callable returns. A factory function of the same name is not one of these.
     *
     * Only a constructor whose result crosses as a **handle**: a value class the boundary opens
     * (`Meters`, `Dp`) is built and returned as its raw primitive, so `Meters(3.0)` gives a `float`, and
     * an `__init__` would promise a `Meters`. That one stays a module function and its class `Any`, as
     * before issue #73.
     */
    private fun isOwnConstructor(d: DeclarationModel): Boolean =
        d.isConstructor && d.kind == "FUNCTION" && d.receiver == null && d.returnBoundaryTag == "OBJECT" &&
            moduleOf(d) == d.owner && baseOf(leafOf(d)) == d.simpleName &&
            d.returnType.qualifiedName == "${d.owner}.${d.simpleName}"

    /** Every name a module defines at the top level, which a class stub must not take. A constructor's
     * base name is its class's, so it takes nothing but its `__`-suffixed table-key spelling. */
    private val takenNames: Map<String, Set<String>> = byModule.mapValues { (_, entries) ->
        entries.flatMap { d ->
            leafOf(d).let { leaf ->
                when {
                    isOwnConstructor(d) -> if (isSuffixed(leaf)) listOf(leaf) else emptyList()
                    isSuffixed(leaf) -> listOf(leaf, baseOf(leaf))
                    else -> listOf(leaf)
                }
            }
        }.toSet()
    }

    /** Class qualified name -> its bound constructors, in table-key order; filled by [renderModuleDefs]. */
    private val constructorsByClass = sortedMapOf<String, MutableList<DeclarationModel>>()

    /**
     * Module prefixes (every module and every directory above it) that name one directory on a
     * case-insensitive filesystem together with a sibling (issue #44). In each such group the
     * all-lower-case spelling keeps its path -- a Kotlin package -- and every other spelling is a
     * class module that is emitted inside its parent package's `__init__.pyi` instead.
     */
    private val foldedPrefixes: Set<String> = run {
        val prefixes = byModule.keys.flatMap { module ->
            val parts = module.split('.')
            parts.indices.map { parts.take(it + 1).joinToString(".") }
        }.toSet()
        prefixes.groupBy { it.lowercase() }.values
            .filter { it.size > 1 }
            .flatten()
            .filter { '.' in it && it.substringAfterLast('.') != it.substringAfterLast('.').lowercase() }
            .toSet()
    }

    /** The shortest folded prefix of [module] (the module itself included), or `null` if it keeps its own path. */
    private fun foldedPrefixOf(module: String): String? {
        val parts = module.split('.')
        return parts.indices.map { parts.take(it + 1).joinToString(".") }.firstOrNull { it in foldedPrefixes }
    }

    /** The modules that are still files: a folded class module is a class, not a module, for `classReference`. */
    private val moduleNames: Set<String> = byModule.keys.filter { foldedPrefixOf(it) == null }.toSet()

    private val supertypes: Map<String, List<String>> = bound
        .filter { it.returnSupertypes.isNotEmpty() }
        .groupBy { it.returnType.qualifiedName }
        .mapValues { (_, entries) -> entries.first().returnSupertypes }

    // ----------------------------------------------------------------------- per-module output

    private class ClassNode(val name: String) {
        val children = sortedMapOf<String, ClassNode>()
        var qualifiedName: String? = null
        var bases: String? = null
        var doc: String? = null
        val members = mutableListOf<String>()
    }

    private inner class ModuleOut(val module: String) {
        val imports = sortedSetOf<String>()
        val protocols = mutableListOf<String>()
        val classes = sortedMapOf<String, ClassNode>()
        val defs = mutableListOf<String>()

        fun node(path: List<String>): ClassNode {
            var current = classes.getOrPut(path[0]) { ClassNode(path[0]) }
            path.drop(1).forEach { current = current.children.getOrPut(it) { ClassNode(it) } }
            return current
        }
    }

    private val modules = sortedMapOf<String, ModuleOut>()
    private fun out(module: String): ModuleOut = modules.getOrPut(module) { ModuleOut(module) }

    /** Every class a signature mentioned, and the value class it wraps when it is one. */
    private val classes = sortedMapOf<String, ValueClassModel?>()

    // ------------------------------------------------------------------------------ types

    private fun annotate(type: KotlinTypeModel, tag: String?, role: Role, ctx: ModuleOut): String {
        val core = core(type, tag, role, ctx)
        return if (type.isNullable && core != "None") "$core | None" else core
    }

    private fun argument(type: KotlinTypeModel?, role: Role, ctx: ModuleOut): String =
        if (type == null) ANY else annotate(type, null, role, ctx)

    private fun core(type: KotlinTypeModel, tag: String?, role: Role, ctx: ModuleOut): String {
        if (FUNCTION_TYPE.matches(type.qualifiedName)) {
            if (type.arguments.isEmpty()) return "$TYPING.Callable[..., $ANY]"
            val parameters = type.arguments.dropLast(1).joinToString(", ") { argument(it, Role.RESULT, ctx) }
            return "$TYPING.Callable[[$parameters], ${argument(type.arguments.last(), Role.PARAM, ctx)}]"
        }
        val valueClass = type.valueClass
        if (valueClass != null && tag != null && tag != "OBJECT") {
            // Bound as its primitive: the raw number is what comes back, and what the raw module takes.
            val raw = builtinOfTag(tag) ?: builtinOf(valueClass.underlying.qualifiedName) ?: ANY
            if (role == Role.RESULT) return raw
            val ref = classReference(type, ctx)
            return if (ref == ANY) raw else "$ref | $raw"
        }
        builtinOf(type.qualifiedName)?.let { return it }
        if (tag != null && tag != "OBJECT") builtinOfTag(tag)?.let { return it }
        return classReference(type, ctx)
    }

    /** The spelling of a class stub from [ctx]'s module, registering it so that it gets emitted. */
    private fun classReference(type: KotlinTypeModel, ctx: ModuleOut): String =
        classReference(type.qualifiedName, type.valueClass, ctx)

    /**
     * [classRefOf], except that a type nested in a Kotlin `object` whose constants and functions are
     * a module of their own (issue #53) is a class **of that module**: `Alignment.Horizontal` is
     * `Horizontal` in `androidx.compose.ui.Alignment`, not a class `Alignment` in `androidx.compose.ui`
     * (a module and a class cannot share the name, which is why this used to fall back to `Any`).
     * The object's own type (`Alignment`) has no such home and stays unresolved here.
     */
    private fun resolveRef(qualifiedName: String): ClassRef? {
        val ref = classRefOf(qualifiedName) ?: return null
        val objectModule = "${ref.pkg}.${ref.top}"
        if (ref.path.size < 2 || objectModule !in moduleNames || ref.top in takenNames[ref.pkg].orEmpty()) return ref
        return ClassRef(objectModule, ref.path.drop(1))
    }

    private fun classReference(qualifiedName: String, valueClass: ValueClassModel?, ctx: ModuleOut): String {
        val ref = resolveRef(qualifiedName) ?: return ANY
        if (ref.top in takenNames[ref.pkg].orEmpty() || "${ref.pkg}.${ref.top}" in moduleNames) return ANY
        if (qualifiedName !in classes || classes[qualifiedName] == null) classes[qualifiedName] = valueClass ?: classes[qualifiedName]
        val path = ref.path.joinToString(".")
        if (ref.pkg == ctx.module) return path
        ctx.imports += ref.pkg
        return "${ref.pkg}.$path"
    }

    // --------------------------------------------------------------------------- parameters

    private fun parameters(d: DeclarationModel, ctx: ModuleOut, method: Boolean): String {
        val hasReceiver = !method && d.receiver != null
        val parameters = d.parameters
        val reserved = when {
            method -> "self"
            hasReceiver -> RECEIVER_NAME
            else -> null
        }
        // Issue #131: each parameter under the keyword `inspect.signature` shows at run time -- its
        // snake_case alias where one is served for this declaration, else its Kotlin name. The runtime
        // accepts both spellings; a stub can name only one.
        val shown = pythonicParameterNames(d)
        val writable = parameters.indices.map { d.parameterNamesKnown && isWritableName(shown[it], reserved) }
        // Everything up to and including the last unwritable name is positional-only.
        val positionalOnlyEnd = writable.indexOfLast { !it }
        val lastRequired = parameters.indexOfLast { !it.declaresDefault }
        // `inspect.signature` on the binder's callable makes a required parameter that follows a
        // defaulted one keyword-only (`KotlinSurface.kt`), and so does the stub -- except where a
        // positional-only prefix is already in play, where the old rule (mark a default only when no
        // required parameter follows it) keeps the prefix valid.
        val keywordOnlyStart = if (positionalOnlyEnd >= 0) -1 else
            parameters.indices.firstOrNull { index -> !parameters[index].declaresDefault && parameters.take(index).any { it.declaresDefault } } ?: -1

        val rendered = mutableListOf<String>()
        if (method) rendered += "self"
        if (hasReceiver) {
            rendered += "$RECEIVER_NAME: " + annotate(d.receiver!!, d.receiverBoundaryTag, Role.PARAM, ctx)
            if (positionalOnlyEnd < 0 && parameters.isNotEmpty()) rendered += "/"
        }
        parameters.forEachIndexed { index, parameter ->
            if (index == keywordOnlyStart) rendered += "*"
            val name = if (writable[index]) shown[index]!! else "__a$index"
            val marked = if (positionalOnlyEnd >= 0) parameter.declaresDefault && index > lastRequired else parameter.declaresDefault
            rendered += "$name: " + annotate(parameter.type, parameter.boundaryTag, Role.PARAM, ctx) + if (marked) " = ..." else ""
            if (index == positionalOnlyEnd) rendered += "/"
        }
        if (parameters.isEmpty() && hasReceiver) rendered += "/"
        return rendered.joinToString(", ")
    }

    /**
     * Per parameter, the name `KotlinSurface.signature_of` shows: the snake_case alias the runtime
     * serves for it (`keyword_slots`: not another parameter's Kotlin name, not shared by two
     * parameters, not a Python keyword), else the Kotlin name; `null` where the name is unknown.
     */
    private fun pythonicParameterNames(d: DeclarationModel): List<String?> {
        val kotlinNames = d.parameters.mapNotNull { it.name }
        val aliasOf = pythonicAliases(kotlinNames, ::snakeCase).entries.associate { (alias, kotlin) -> kotlin to alias }
        return d.parameters.map { parameter ->
            val name = parameter.name ?: return@map null
            aliasOf[name]?.takeIf { it !in PYTHON_KEYWORDS } ?: name
        }
    }

    private fun result(d: DeclarationModel, ctx: ModuleOut): String =
        annotate(d.returnType, d.returnBoundaryTag, Role.RESULT, ctx)

    // ---------------------------------------------------------------------- module level

    private fun renderDef(d: DeclarationModel, ctx: ModuleOut): String {
        val leaf = leafOf(d)
        // A Kotlin name that is a Python keyword (Compose's `Shadow.None`) cannot be written as an
        // attribute or a def -- `None: Shadow` makes the whole file a syntax error. Leave it out and
        // say how to reach it; the runtime still serves it under its Kotlin name.
        if (leaf in PYTHON_KEYWORDS) {
            return "# Kotlin: ${kotlinSignatureOf(d)} -- '$leaf' is a Python keyword; reach it with getattr(..., '$leaf')"
        }
        if (d.kind == "STATIC_GETTER") {
            return buildString {
                appendLine("$leaf: ${result(d, ctx)}")
                append("\"\"\"Kotlin: ${kotlinSignatureOf(d)}\"\"\"")
            }
        }
        return buildString {
            appendLine("def $leaf(${parameters(d, ctx, method = false)}) -> ${result(d, ctx)}:")
            appendLine("    \"\"\"Kotlin: ${kotlinSignatureOf(d)}\"\"\"")
            append("    ...")
        }
    }

    /**
     * A name that is a function (or an overload set) in its package **and** a module of its own (issue
     * #78): Kotlin's `TextRange(2)` and `TextRange.Zero`. At run time the module is callable, so the
     * stub says one attribute of the parent package whose type has `__call__` -- the function, its
     * `@overload`s -- and the module's constants and functions as members. A bare `def` would hide
     * `TextRange.Zero` from a checker; a bare module would hide `TextRange(2)`. The module's own file
     * still exists for `import pkg.TextRange`. Member order is table-key order, as everywhere.
     */
    private fun callableModule(
        name: String,
        calls: List<DeclarationModel>,
        members: List<DeclarationModel>,
        ctx: ModuleOut,
    ): String {
        if (name in PYTHON_KEYWORDS) return "# '$name' is a Python keyword; reach it with getattr(..., '$name')"
        val protocol = "_${name}_callable_module"
        val sortedMembers = members.sortedBy { it.bindingName }
        ctx.protocols += buildString {
            appendLine("class $protocol($TYPING.Protocol):")
            appendLine("    \"\"\"Kotlin: the function $name, and the members of the module $name\"\"\"")
            calls.forEachIndexed { index, d ->
                if (calls.size > 1) appendLine("    @$TYPING.overload")
                appendLine("    def __call__(${parameters(d, ctx, method = true)}) -> ${result(d, ctx)}: ..." + shadowed(index))
            }
            val attributes = sortedMapOf<String, MutableList<DeclarationModel>>()
            sortedMembers.forEach { d ->
                val leaf = leafOf(d)
                attributes.getOrPut(leaf) { mutableListOf() } += d
                if (d.kind != "STATIC_GETTER" && isSuffixed(leaf)) attributes.getOrPut(baseOf(leaf)) { mutableListOf() } += d
            }
            // Issue #131: the module's Pythonic aliases are members too, typed as their Kotlin names.
            val namespace = attributes.keys + childSegments("${ctx.module}.$name")
            pythonicAliases(namespace).forEach { (alias, kotlin) -> attributes[kotlin]?.let { attributes[alias] = it } }
            attributes.forEach { (attribute, group) ->
                if (!isIdentifier(attribute) || attribute == "__call__") {
                    appendLine("    # '$attribute' cannot be written as an attribute here; reach it with getattr(..., '$attribute')")
                    return@forEach
                }
                val first = group.first()
                if (first.kind == "STATIC_GETTER") {
                    appendLine("    $attribute: ${result(first, ctx)}")
                } else {
                    group.forEachIndexed { index, d ->
                        if (group.size > 1) appendLine("    @$TYPING.overload")
                        appendLine("    def $attribute(${parameters(d, ctx, method = true)}) -> ${result(d, ctx)}: ..." + shadowed(index))
                    }
                }
            }
        }.trimEnd()
        return "$name: $protocol"
    }

    private fun overloadDefs(name: String, group: List<DeclarationModel>, ctx: ModuleOut): String =
        if (name in PYTHON_KEYWORDS) "# '$name' is a Python keyword; reach it with getattr(..., '$name')"
        else group.withIndex().joinToString("\n") { (index, d) ->
            val signature = "def $name(${parameters(d, ctx, method = false)}) -> ${result(d, ctx)}: ..." + shadowed(index)
            if (group.size > 1) "@$TYPING.overload\n$signature" else signature
        }

    /**
     * Python's one `int`/`float` and a class that fell back to `Any` cannot tell two Kotlin overloads
     * apart (`lerp(Int, Int, Float)` against `lerp(Float, Float, Float)`), so a checker reports every
     * later one as "will never be matched". The runtime dispatcher *does* tell them apart, and the
     * explicit `name__Types` spelling reaches each one, so the diagnostic is silenced where it is a
     * statement about Python's types and not about the declaration.
     */
    private fun shadowed(index: Int): String = if (index > 0) "  # type: ignore[overload-cannot-match]" else ""

    private fun renderModuleDefs() {
        byModule.forEach { (module, entries) ->
            val folded = foldedPrefixOf(module)
            if (folded != null) {
                renderFoldedModule(module, folded, entries)
                return@forEach
            }
            val ctx = out(module)
            // A constructor is its class's `__init__` (see [isOwnConstructor]) whenever the class has a
            // stub to put it in; only its `__`-suffixed table-key spelling is also a module function,
            // because the runtime serves it as one. A class with no stub keeps the old rendering.
            val (constructors, sorted) = entries.sortedBy { it.bindingName }
                .partition { isOwnConstructor(it) && classReference(it.returnType, ctx) != ANY }
            // Issue #78: a name that is both a function here and a module of its own (`TextRange(2)`
            // and `TextRange.Zero`) is one callable attribute, not a bare `def`; see [callableModule].
            val companions = sorted.filter { it.kind != "STATIC_GETTER" }
                .map { baseOf(leafOf(it)) }
                .filter { "$module.$it" in moduleNames }
                .toSortedSet()
            sorted.filter { it.kind == "STATIC_GETTER" || isSuffixed(leafOf(it)) || leafOf(it) !in companions }
                .forEach { ctx.defs += renderDef(it, ctx) }
            companions.forEach { base ->
                val calls = sorted.filter { it.kind != "STATIC_GETTER" && baseOf(leafOf(it)) == base }
                ctx.defs += callableModule(base, calls, byModule.getValue("$module.$base"), ctx)
            }
            constructors.forEach { d ->
                if (isSuffixed(leafOf(d))) ctx.defs += renderDef(d, ctx)
                constructorsByClass.getOrPut(d.returnType.qualifiedName) { mutableListOf() } += d
            }
            // The base name of an overload set: the binding layer serves it, so the stub says it.
            val unsuffixedLeaves = sorted.filter { !isSuffixed(leafOf(it)) }.map { leafOf(it) }.toSet()
            val overloadBases = mutableSetOf<String>()
            sorted.filter { it.kind != "STATIC_GETTER" && isSuffixed(leafOf(it)) }
                .groupBy { baseOf(leafOf(it)) }
                .toSortedMap()
                .forEach { (base, group) ->
                    if (base !in unsuffixedLeaves && base !in companions) {
                        ctx.defs += overloadDefs(base, group, ctx)
                        overloadBases += base
                    }
                }
            // Issue #131: the Pythonic alias of every name written above, as an assignment, so a
            // checker gives it exactly the Kotlin name's type. The namespace is the runtime's
            // (`_package_aliases`): every bound name of the package and its child packages.
            val written = sorted.map { leafOf(it) } + constructors.filter { isSuffixed(leafOf(it)) }.map { leafOf(it) } +
                companions + overloadBases
            val namespace = entries.flatMap { listOf(leafOf(it), baseOf(leafOf(it))) } + childSegments(module)
            ctx.defs += aliasAssignments(pythonicAliases(namespace), written.toSet())
        }
    }

    /** The segment directly under [module] of every module below it: its child packages and objects. */
    private fun childSegments(module: String): List<String> =
        byModule.keys.filter { it.startsWith("$module.") }.map { it.removePrefix("$module.").substringBefore('.') }.distinct()

    /** `alias = kotlinName` for each alias whose Kotlin name the stub module writes, sorted by alias. */
    private fun aliasAssignments(aliases: Map<String, String>, written: Set<String>): List<String> =
        aliases.filter { (alias, kotlin) -> kotlin in written && kotlin !in PYTHON_KEYWORDS && alias !in PYTHON_KEYWORDS }
            .map { (alias, kotlin) -> "$alias = $kotlin" }

    /**
     * A class module that collides case-insensitively with a sibling path (issue #44): its functions
     * become static members of the class of that name in the parent package's module, nested for the
     * parts of [module] below [folded]. The runtime import path is unchanged; only the stub layout is.
     * If the parent already defines a function of that name, the class cannot be declared beside it
     * (the runtime resolves the name to the function, as for `classReference`), so nothing is emitted.
     */
    private fun renderFoldedModule(module: String, folded: String, entries: List<DeclarationModel>) {
        val parentModule = folded.substringBeforeLast('.')
        val className = folded.substringAfterLast('.')
        if (className in takenNames[parentModule].orEmpty()) return
        val ctx = out(parentModule)
        val path = listOf(className) + module.removePrefix(folded).split('.').filter { it.isNotEmpty() }
        val node = ctx.node(path)
        val sorted = entries.sortedBy { it.bindingName }
        val defs = sorted.map { renderDef(it, ctx) }
        val unsuffixedLeaves = sorted.filter { !isSuffixed(leafOf(it)) }.map { leafOf(it) }.toSet()
        val overloads = sorted.filter { it.kind != "STATIC_GETTER" && isSuffixed(leafOf(it)) }
            .groupBy { baseOf(leafOf(it)) }
            .toSortedMap()
            .mapNotNull { (base, group) -> if (base !in unsuffixedLeaves) overloadDefs(base, group, ctx) else null }
        (defs + overloads).forEach { text ->
            node.members += text.lines().joinToString("\n") { if (it.startsWith("def ")) "@staticmethod\n$it" else it }
        }
    }

    // --------------------------------------------------------------------------- classes

    /** The class a declaration's extension receiver names, when the runtime attaches it as a method. */
    private fun methodReceiver(d: DeclarationModel): String? {
        val receiver = d.receiver ?: return null
        if (builtinOf(receiver.qualifiedName) != null || FUNCTION_TYPE.matches(receiver.qualifiedName)) return null
        val tag = d.receiverBoundaryTag
        if (tag != null && tag != "OBJECT") return null
        if (tag == null && receiver.valueClass != null) return null
        return receiver.qualifiedName
    }

    private val extensionsByReceiver: Map<String, List<DeclarationModel>> = bound
        .filter { it.kind != "STATIC_GETTER" }
        .mapNotNull { d -> methodReceiver(d)?.let { it to d } }
        .groupBy({ it.first }, { it.second })

    /**
     * Issue #71: every Kotlin supertype that has a stub class is a base, not just the first one
     * (`Arrangement.HorizontalOrVertical` is both a `Horizontal` and a `Vertical`). The ancestry is
     * nearest-first and transitive, so a base is dropped when another candidate's own known ancestry
     * already contains it (Python would reject `class C(A, B)` with B a subclass of A as an
     * inconsistent MRO, and the repeat adds nothing); what remains keeps the ancestry's order.
     * `kotlin.Any` and `java.lang.*` are `object`, which every Python class already has.
     */
    private fun stubBasesOf(qualifiedName: String, ctx: ModuleOut): List<String> {
        val candidates = supertypes[qualifiedName].orEmpty()
            .filter { it != qualifiedName && it != "kotlin.Any" && !it.startsWith("java.lang.") && classRefOf(it) != null }
            .distinct()
        val kept = candidates.filter { base ->
            candidates.none { other -> other != base && base in supertypes[other].orEmpty() && other !in supertypes[base].orEmpty() }
        }
        return kept.map { classReference(it, null, ctx) }.filter { it != ANY }.distinct()
    }

    private fun renderClass(qualifiedName: String) {
        val ref = resolveRef(qualifiedName) ?: return
        val ctx = out(ref.pkg)
        val node = ctx.node(ref.path)
        node.qualifiedName = qualifiedName
        val valueClass = classes[qualifiedName]
        node.doc = if (valueClass != null) {
            "Kotlin value class $qualifiedName over ${valueClass.underlying.qualifiedName}. As a parameter the raw " +
                "primitive is also accepted (the binding layer's allowlist decides, at run time); a result is the raw primitive."
        } else {
            "Kotlin: $qualifiedName"
        }
        stubBasesOf(qualifiedName, ctx).takeIf { it.isNotEmpty() }?.let { node.bases = it.joinToString(", ") }

        constructorsByClass[qualifiedName]?.let { group ->
            group.forEachIndexed { index, d ->
                node.members += if (group.size == 1) {
                    "def __init__(${parameters(d, ctx, method = true)}) -> None:\n" +
                        "    \"\"\"$CONSTRUCTOR_MARKER${d.bindingName} (${kotlinSignatureOf(d)})\"\"\"\n" +
                        "    ..."
                } else {
                    "@$TYPING.overload\ndef __init__(${parameters(d, ctx, method = true)}) -> None: ..." + shadowed(index)
                }
            }
        }

        val attributes = sortedMapOf<String, MutableList<DeclarationModel>>()
        extensionsByReceiver[qualifiedName].orEmpty().sortedBy { it.bindingName }.forEach { d ->
            val leaf = leafOf(d)
            attributes.getOrPut(leaf) { mutableListOf() } += d
            if (isSuffixed(leaf)) attributes.getOrPut(baseOf(leaf)) { mutableListOf() } += d
        }
        // Issue #131: the proxy also serves each member's Pythonic alias, decided over every member
        // name the runtime looks up for this type -- its own and every supertype's (`_member_aliases`).
        val aliases = pythonicAliases(memberNamesOf(qualifiedName))
        val protocols = mutableMapOf<String, String>()
        attributes.forEach { (attribute, group) ->
            if (!isIdentifier(attribute) || attribute in node.children) return@forEach
            val protocol = "_" + ref.path.joinToString("_") + "_" + attribute
            ctx.protocols += buildString {
                appendLine("class $protocol($TYPING.Protocol):")
                group.forEachIndexed { index, d ->
                    if (group.size > 1) appendLine("    @$TYPING.overload")
                    append("    def __call__(${parameters(d, ctx, method = true)}) -> ${result(d, ctx)}: ..." + shadowed(index))
                    if (index != group.lastIndex) appendLine()
                }
            }
            node.members += "$attribute: $TYPING.ClassVar[$protocol]"
            protocols[attribute] = protocol
        }
        val stubbedProperties = mutableMapOf<String, Pair<DeclarationModel, DeclarationModel?>>()
        properties[qualifiedName].orEmpty().forEach { (name, accessors) ->
            val (getter, setter) = accessors
            if (!isIdentifier(name) || name in node.children || name in attributes) {
                node.members += propertyNotStubbed(getter, setter, "'$name' cannot be written as an attribute here")
                return@forEach
            }
            node.members += renderProperty(name, getter, setter, ctx)
            stubbedProperties[name] = accessors
        }
        aliases.forEach { (alias, kotlin) ->
            if (!isIdentifier(alias) || alias in node.children) return@forEach
            protocols[kotlin]?.let { node.members += "$alias: $TYPING.ClassVar[$it]" }
            stubbedProperties[kotlin]?.let { (getter, setter) -> node.members += renderProperty(alias, getter, setter, ctx) }
        }
    }

    /** Every member name the binding layer serves on a proxy of [qualifiedName]: `_member_names`. */
    private fun memberNamesOf(qualifiedName: String): Set<String> =
        (listOf(qualifiedName) + supertypes[qualifiedName].orEmpty()).flatMap { type ->
            extensionsByReceiver[type].orEmpty().flatMap { listOf(leafOf(it), baseOf(leafOf(it))) } +
                properties[type].orEmpty().keys
        }.toSet()

    private fun renderProperty(name: String, getter: DeclarationModel, setter: DeclarationModel?, ctx: ModuleOut): String = buildString {
        appendLine("@property")
        appendLine("def $name(self) -> ${result(getter, ctx)}:")
        appendLine("    \"\"\"$PROPERTY_MARKER${getter.bindingName} (${propertySignatureOf(getter)})\"\"\"")
        append("    ...")
        if (setter != null) {
            val parameter = setter.parameters.single()
            appendLine()
            appendLine("@$name.setter")
            appendLine("def $name(self, value: ${annotate(parameter.type, parameter.boundaryTag, Role.PARAM, ctx)}) -> None:")
            appendLine("    \"\"\"$PROPERTY_MARKER${setter.bindingName}\"\"\"")
            append("    ...")
        }
    }

    private fun propertyNotStubbed(getter: DeclarationModel, setter: DeclarationModel?, why: String): String =
        listOfNotNull(getter, setter).joinToString("\n") { "# $PROPERTY_MARKER${it.bindingName} is not stubbed: $why" }

    private fun propertySignatureOf(getter: DeclarationModel): String {
        val type = getter.returnType.qualifiedName + if (getter.returnType.isNullable) "?" else ""
        return "${getter.receiver!!.qualifiedName}.${getter.simpleName}: $type"
    }

    /**
     * Every receiver that has properties gets its stub class -- even one no signature mentions, since
     * a value of it can still reach Python (a `kotlin.Any?` result, a supertype's slot). One whose
     * class cannot be stubbed gets the marker comment instead; see this file's KDoc.
     */
    private fun registerPropertyOwners() {
        properties.forEach { (receiver, byName) ->
            val ref = classRefOf(receiver)
            val home = ref?.pkg ?: receiver.substringBeforeLast('.', "")
            val ctx = out(home)
            if (ref != null && classReference(receiver, null, ctx) != ANY) return@forEach
            byName.values.forEach { (getter, setter) ->
                ctx.defs += propertyNotStubbed(getter, setter, "${receiver.substringAfterLast('.')} has no stub class (its name is taken in its module)")
            }
        }
    }

    private fun renderClasses() {
        val done = mutableSetOf<String>()
        while (true) {
            val next = classes.keys.firstOrNull { it !in done } ?: break
            done += next
            renderClass(next)
        }
    }

    private fun renderNode(node: ClassNode, indent: String, into: StringBuilder) {
        into.append(indent).append("class ").append(node.name)
        node.bases?.let { into.append('(').append(it).append(')') }
        into.appendLine(":")
        into.append(indent).append("    \"\"\"").append(node.doc ?: "Kotlin: container of nested classes").appendLine("\"\"\"")
        node.members.forEach { member ->
            member.lines().forEach { into.append(indent).append("    ").appendLine(it) }
        }
        node.children.values.forEach { renderNode(it, "$indent    ", into) }
    }

    // ---------------------------------------------------------------------------- assembly

    fun render(): Map<String, String> {
        renderModuleDefs()
        registerPropertyOwners()
        renderClasses()
        return modules.filterValues { it.defs.isNotEmpty() || it.classes.isNotEmpty() }
            .mapValues { (_, ctx) ->
                buildString {
                    appendLine(HEADER)
                    appendLine()
                    appendLine("import typing as $TYPING")
                    ctx.imports.forEach { appendLine("import $it") }
                    appendLine()
                    ctx.protocols.forEach { appendLine(it); appendLine() }
                    ctx.classes.values.forEach { node ->
                        renderNode(node, "", this)
                        appendLine()
                    }
                    ctx.defs.forEach { appendLine(it); appendLine() }
                }
            }
            .mapKeys { (module, _) -> module.replace('.', '/') + "/__init__.pyi" }
    }
}

/**
 * Groups of stub paths that name one file on a case-insensitive filesystem. A Kotlin package and a
 * class may differ only by case (`graphics.shadow`, `graphics.Shadow`), which is legal on the JVM and
 * in Python and not on macOS or Windows, where `PythonStubsTask` would otherwise overwrite one with
 * the other without a word.
 */
internal fun caseCollidingPaths(paths: Collection<String>): List<List<String>> =
    paths.groupBy { it.lowercase() }.values.filter { it.size > 1 }.map { it.sorted() }
