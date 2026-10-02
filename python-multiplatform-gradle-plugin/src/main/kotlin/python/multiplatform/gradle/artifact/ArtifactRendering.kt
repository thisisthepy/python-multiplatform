package python.multiplatform.gradle.artifact

import java.io.Serializable

/** The package walked artefacts' fragments land in. Deliberately **not**
 * `python.multiplatform.generated.fragments`, which `PackageScanFragmentDiscovery` sweeps into
 * KSP's `FunctionTable` -- see [renderArtifactTableSource]. It is still under
 * `python.multiplatform.generated`, which `python-multiplatform-ksp`'s
 * `ALWAYS_EXCLUDED_PACKAGE_PREFIXES` excludes by prefix, so the processor never rescans this
 * generated source as if a consumer had written it. */
internal const val ARTIFACTS_PACKAGE = "python.multiplatform.generated.artifacts"

private const val FRAGMENT_INTERFACE = "python.multiplatform.reflection.FunctionTableFragment"
private const val EXPOSED_CALLABLE = "python.multiplatform.reflection.ExposedCallable"
private const val TYPE_TAG = "python.multiplatform.reflection.TypeTag"
private const val CALLABLE_KIND = "python.multiplatform.reflection.CallableKind"
private const val UPCALL_TABLE = "python.multiplatform.reflection.UpcallTable"

/**
 * One binding taken out of a compiled artefact.
 *
 * Mirrors `python.multiplatform.ksp.CallableEntryModel` field for field, minus what a jar cannot
 * answer: there is no `isSuspend` (a `suspend` function's JVM shape takes a `Continuation`, which
 * [boundaryTypeOf] declines, so no suspending declaration ever reaches here) and no `kind` (every
 * entry is a `FUNCTION`, because only statics are bound -- an instance method would need a
 * `ReflectedClass` and a receiver handle, which is the next step and not this one).
 *
 * `Serializable`: [KlibScanWorkAction] hands one of these back across the `WorkerExecutor` isolation
 * boundary it runs [KlibScanner] behind.
 */
internal data class ArtifactCallable(
    val name: String,
    val arity: Int,
    val paramTags: List<String>,
    val returnTag: String,
    val lambdaBody: String,
    /** `import ... as ...` lines [lambdaBody] depends on -- non-empty only for a Kotlin extension
     * function, which [lambdaBody] calls as `receiver.alias(...)`. Kotlin has no fully-qualified
     * call syntax for an extension (the receiver can never be a positional argument), so the alias
     * has to come from an import instead; see `ArtifactScanner`'s KDoc. */
    val imports: List<String> = emptyList(),
    /**
     * The Kotlin type this declaration extends, or `null` when it is not an extension function.
     *
     * Carried rather than recomputed because it is not recoverable from anything else here: the
     * receiver is [ArtifactCallable.paramTags]'s slot 0, and a tag says how a value is marshalled,
     * not what it is. `docs/archive/pythonx-adapter-design.md` §2.4 row 3 records this exact loss --
     * "`ArtifactScanner` has both at the moment it constructs the call expression and discards them
     * one line later" -- and §4.2 records that `docs/design/kotlin-extensions-in-python.md` §4.1
     * (an extension becomes a method on its receiver's proxy) has no input without it.
     */
    val receiverTypeName: String? = null,
    /** The declaration's parameter names, receiver slot included as `<receiver>`. */
    val paramNames: List<String> = emptyList(),
    /** The Kotlin type each parameter was declared as, receiver slot included -- **not** the type it
     * marshals as. A `Dp` parameter is `paramTags` `FLOAT` and `paramTypeNames`
     * `androidx.compose.ui.unit.Dp`. */
    val paramTypeNames: List<String> = emptyList(),
    /** The declared Kotlin return type. */
    val returnTypeName: String? = null,
    /**
     * What [returnTypeName] **is a**, nearest first -- the ancestry `pythonx._coerce` needs to let a
     * `BitmapPainter` fill a `Painter` slot. Empty whenever the return does not cross as a handle,
     * and whenever it has no public supertype worth naming.
     *
     * Kept beside [returnTypeName] rather than folded into it, so that everything reading a *type*
     * keeps reading one: [renderEntry] is the single place the two are joined, and the fragment's
     * `returnTypeName` literal is where they travel together. See [SUPERTYPE_SEPARATOR].
     */
    val returnSupertypes: List<String> = emptyList(),
    val kind: String = "FUNCTION",
    /**
     * Whether each parameter may be **left out of a call**, which is a statement about [lambdaBody]
     * and not quite about the declaration.
     *
     * `ArtifactScanner.applyDefaultOmission` sets it from the omission sets it actually generated a
     * call for; the two answers differ where it declined to (a declaration past
     * `ArtifactScanner.MAX_OMITTABLE_PARAMETERS`, or one whose parameters have no writable names).
     * `pythonx._bind` fills a slot this marks with the `null` sentinel [lambdaBody] branches on, so
     * the body's answer is the one that has to be told. [omittableSlotsOf] is where that invariant is
     * enforced for both producers.
     */
    val paramHasDefault: List<Boolean> = emptyList(),
    /**
     * Set only for a `@Composable`: the JVM call [lambdaBody] delegates to, because Kotlin source
     * cannot make it. See [ComposableThunks.kt] for the two measurements that close every other
     * route.
     *
     * `null` for everything else, which is every binding that existed before composables did --
     * their [lambdaBody] is a real Kotlin call expression and needs no `.class` beside it.
     */
    val thunk: ThunkSpec? = null,
    /** Which `t<i>` of its fragment's thunk class [thunk] is, so that the generated `.kt` and the
     * generated `.class` cannot disagree about the index. `-1` when [thunk] is `null`. */
    val thunkIndex: Int = -1,
) : Serializable

/** One artefact's worth of bindings: what becomes a single `FunctionTableFragment` object. */
internal data class ArtifactFragment(
    val objectName: String,
    val moduleName: String,
    val entries: List<ArtifactCallable>,
)

/**
 * A Kotlin string literal for [this].
 *
 * The `$` escape is not decoration: a composable's synthetic parameter names are literally
 * `$composer`, `$changed` and `$default` (`ComposableShape.syntheticParameterNames`), and an
 * unescaped one in generated Kotlin is a *template expression* -- `"$composer"` compiles as a
 * reference to a variable named `composer` and fails with "Unresolved reference", which is exactly
 * how this was found.
 */
private fun String.quoted(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

/**
 * A Kotlin identifier for a resolved artefact's coordinates.
 *
 * `python.multiplatform.ksp.sanitiseModuleName`'s rule, widened: a `ComponentIdentifier`'s display
 * name is `group:name:version` for a module and `project :path` for a project, so spaces and colons
 * have to go the way periods and hyphens do.
 */
internal fun artifactFragmentObjectName(coordinates: String): String =
    "ArtifactFragment_" + coordinates.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/**
 * `paramHasDefault` is a statement about [ArtifactCallable.lambdaBody], and this is where that is
 * enforced rather than assumed.
 *
 * The column used to be inert -- carried into the table and read by nothing -- so a producer could
 * fill it from the *declaration* and be right enough. It is not inert any more: `pythonx._bind`
 * fills a slot it marks with `null`, and a body that has no branch for that `null` casts it and
 * throws. `ArtifactScanner` sets the column from what it generated; [KlibScanner] still sets it from
 * `AbiValueParameter.hasDefaultArg` while emitting a body that passes every argument, which is the
 * old, now-wrong pairing.
 *
 * Rather than trust two producers to agree, the flag is derived here from the one thing that cannot
 * lie about the body: whether the body tests for the sentinel at all. A klib entry therefore reaches
 * Python as "every argument required", which is what it is.
 *
 * **A `@Composable` omits by a second mechanism and so has a second piece of evidence.** Its body has
 * no sentinel test and never will: the `$default` bitmask is a *declared trailing parameter* of the
 * JVM method (`docs/archive/pythonx-adapter-design.md` §5.2), so `pythonx` computes an integer instead of
 * writing `None` into a slot, and there is no branch for the body to have. Carrying a [ThunkSpec] is
 * that evidence -- it is set by exactly one path, the one that emits the mask parameter -- so the
 * rule stays "derived from the body", with two bodies to derive from rather than one.
 */
internal fun omittableSlotsOf(entry: ArtifactCallable): List<Boolean> =
    if ("== null" in entry.lambdaBody || entry.thunk != null) entry.paramHasDefault else entry.paramHasDefault.map { false }

/**
 * What separates a declared return type from the types it **is a**, inside the one string the
 * boundary already carries for it.
 *
 * ### Why the answer travels in the type name
 *
 * The same reason `ArtifactScanner.functionSlotTypeName`'s does, and its KDoc makes the argument in
 * full: `ExposedCallable` already carries a declared type name to every target, and a second column
 * that only some producers fill would be one more thing that can disagree with the first. `<:` is not
 * a character sequence a Kotlin fully-qualified name can contain -- neither `<` nor `:` is legal in
 * one -- so nothing legitimate can collide with the grammar, and `pythonx._split_supertypes` is the
 * single reader.
 *
 * It also keeps the change where the two ends of it are. `ExposedCallable` is the runtime's model and
 * is written by KSP as well; a walked jar is the only producer that *has* a class hierarchy to read,
 * and this is the only shape that lets it say so without every other producer growing a field it
 * would leave empty.
 */
internal const val SUPERTYPE_SEPARATOR = "<:"

/** `returnTypeName` as the fragment writes it: the declared type, then its ancestry. */
internal fun renderedReturnTypeName(entry: ArtifactCallable): String? =
    entry.returnTypeName?.let { (listOf(it) + entry.returnSupertypes).joinToString(SUPERTYPE_SEPARATOR) }

private fun renderEntry(entry: ArtifactCallable): String {
    val paramTags = entry.paramTags.joinToString(", ") { "$TYPE_TAG.$it" }
    val paramNames = entry.paramNames.joinToString(", ") { it.quoted() }
    val paramTypeNames = entry.paramTypeNames.joinToString(", ") { it.quoted() }
    val paramHasDefault = omittableSlotsOf(entry).joinToString(", ")
    return """
        |$EXPOSED_CALLABLE(
        |    name = ${entry.name.quoted()},
        |    arity = ${entry.arity},
        |    paramTypes = listOf($paramTags),
        |    returnType = $TYPE_TAG.${entry.returnTag},
        |    kind = $CALLABLE_KIND.${entry.kind},
        |    paramNames = listOf($paramNames),
        |    paramTypeNames = listOf($paramTypeNames),
        |    returnTypeName = ${renderedReturnTypeName(entry)?.quoted() ?: "null"},
        |    isExtension = ${entry.receiverTypeName != null},
        |    receiverTypeName = ${entry.receiverTypeName?.quoted() ?: "null"},
        |    paramHasDefault = listOf($paramHasDefault),
        |    callable = ${entry.lambdaBody},
        |)
    """.trimMargin()
}

/**
 * The stand-in [ArtifactScanner] writes into a composable's body for the thunk class, resolved here
 * because only the fragment knows its own name.
 *
 * A token rather than the real name because the walk that builds the body does not yet know which
 * artefact's fragment it will land in -- and passing the name into `scanJar` would put a Gradle
 * concern (what a coordinate is called) inside the walker. Substituted in exactly one place, so the
 * `.kt` and the `.class` [generateThunkClass] emits cannot name different classes.
 */
internal const val THUNK_CLASS_TOKEN = "%THUNKS%"

/** Every thunk one fragment needs, in the order [ArtifactCallable.thunkIndex] promises. */
internal fun thunkSpecsOf(entries: List<ArtifactCallable>): List<ThunkSpec> {
    val thunked = entries.filter { it.thunk != null }.sortedBy { it.thunkIndex }
    require(thunked.mapIndexed { position, entry -> entry.thunkIndex == position }.all { it }) {
        "thunk indices must be dense and start at 0: ${thunked.map { it.thunkIndex }}"
    }
    return thunked.map { it.thunk!! }
}

/**
 * Renders one artefact's fragment.
 *
 * The same file shape `python-multiplatform-ksp`'s `renderFragmentSource` emits, because the
 * runtime must not be able to tell the two producers apart: `UpcallTable`, `CallableHandle` and
 * `PythonProxySource` all see a `FunctionTableFragment` and nothing more.
 *
 * `DEPRECATION` joins `UNCHECKED_CAST` in the file-level suppression for a reason KSP's version does
 * not have: the declarations here are somebody else's. JUnit 4's own `junit.framework.Assert` is
 * deprecated, and a consumer who asked for a binding should not be handed a warning about source
 * they did not write and cannot change.
 *
 * `OPT_IN_USAGE_ERROR` is there for the same reason one size up. Compose marks a large part of its
 * public API with `@ExperimentalFoundationApi` and friends, which are `RequiresOptIn` at `ERROR`
 * level, so a fragment binding `Modifier.combinedClickable` would fail to compile in a consumer's
 * build unless that consumer happened to have opted in. The *marker* is not knowable here -- it is
 * one annotation among any number a library may invent -- so the diagnostic is suppressed rather
 * than an `@OptIn` guessed at. What that costs is real and worth stating: a consumer gets no warning
 * that a bound declaration is experimental. What it buys is that asking for a package does not fail
 * the build over which of its declarations someone else marked.
 */
/**
 * How many entries one generated method may build before [renderArtifactFragmentSource] splits.
 *
 * The JVM caps a method at 64 KB of bytecode, and every entry's four per-slot lists are built inline.
 * A composable is the widest case -- its declared parameters plus `$composer`/`$changed`/`$default`,
 * each costing roughly 30 bytes across the four lists -- so about 1 KB for the widest Material 3
 * signatures. 40 of those stays well under the cap; the common small entry is a fraction of that.
 */
internal const val FRAGMENT_ENTRIES_PER_METHOD = 40

internal fun renderArtifactFragmentSource(fragment: ArtifactFragment): String = buildString {
    appendLine("// GENERATED by python-multiplatform-gradle-plugin. Do not edit.")
    appendLine(
        "@file:Suppress(\"UNCHECKED_CAST\", \"DEPRECATION\", \"DEPRECATION_ERROR\", " +
            "\"OPT_IN_USAGE\", \"OPT_IN_USAGE_ERROR\")",
    )
    appendLine()
    appendLine("package $ARTIFACTS_PACKAGE")
    appendLine()
    // Deduplicated and sorted: two entries binding the same extension function (possible once an
    // ambiguous-overload group is what prevents it, not the walker's enumeration order) must not
    // produce two conflicting `as` aliases for the same import.
    fragment.entries.flatMap { it.imports }.toSortedSet().forEach { appendLine(it) }
    if (fragment.entries.any { it.imports.isNotEmpty() }) appendLine()
    appendLine("object ${fragment.objectName} : $FRAGMENT_INTERFACE {")
    appendLine("    override val moduleName: String = ${fragment.moduleName.quoted()}")
    appendLine()
    val thunks = thunkClassQualifiedName(fragment.objectName)
    fun StringBuilder.appendList(entries: List<ArtifactCallable>) {
        entries.forEach {
            appendLine(renderEntry(it).replace(THUNK_CLASS_TOKEN, thunks).prependIndent("        ") + ",")
        }
        appendLine("    )")
    }
    if (fragment.entries.size <= FRAGMENT_ENTRIES_PER_METHOD) {
        appendLine("    override fun entries(): List<$EXPOSED_CALLABLE> = listOf(")
        appendList(fragment.entries)
    } else {
        // Split so no one method passes the JVM's 64 KB limit -- see [FRAGMENT_ENTRIES_PER_METHOD].
        val chunks = fragment.entries.chunked(FRAGMENT_ENTRIES_PER_METHOD)
        appendLine("    override fun entries(): List<$EXPOSED_CALLABLE> =")
        appendLine("        " + chunks.indices.joinToString(" + ") { "entries$it()" })
        chunks.forEachIndexed { index, chunk ->
            appendLine()
            appendLine("    private fun entries$index(): List<$EXPOSED_CALLABLE> = listOf(")
            appendList(chunk)
        }
    }
    appendLine("}")
}

/**
 * Renders the aggregator over every walked artefact.
 *
 * A second list beside KSP's `FunctionTable` rather than an extension of it. The reasoning is in
 * `ArtifactTableRenderingTest`'s class doc, and the short form is in the generated doc comment
 * below so that a reader who only ever sees the output gets it too.
 */
internal fun renderArtifactTableSource(fragmentObjectNames: List<String>): String = buildString {
    appendLine("// GENERATED by python-multiplatform-gradle-plugin. Do not edit.")
    appendLine("package $ARTIFACTS_PACKAGE")
    appendLine()
    appendLine("/**")
    appendLine(" * Every resolved artefact this module asked to have bound.")
    appendLine(" *")
    appendLine(" * Not merged into `python.multiplatform.generated.FunctionTable`: that one means \"every")
    appendLine(" * module in this build graph that was compiled with the processor\", and a walked")
    appendLine(" * third-party jar is not one. Install both:")
    appendLine(" *")
    appendLine(" *     UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)")
    appendLine(" */")
    appendLine("object ArtifactTable {")
    appendLine("    val fragments: List<$FRAGMENT_INTERFACE> = listOf(")
    fragmentObjectNames.forEach { appendLine("        $it,") }
    appendLine("    )")
    appendLine()
    appendLine("    /** Adds these to whatever is already installed, leaving it in place. */")
    appendLine("    fun registerInto() {")
    appendLine("        fragments.forEach { $UPCALL_TABLE.register(it) }")
    appendLine("    }")
    appendLine("}")
}
