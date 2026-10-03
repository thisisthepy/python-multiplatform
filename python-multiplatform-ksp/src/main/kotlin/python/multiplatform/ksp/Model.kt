package python.multiplatform.ksp

/**
 * `CallableKind` mirrors `python.multiplatform.reflection.CallableKind` by name -- kept as a
 * string here (rather than an import of the runtime enum) so this whole model stays independent
 * of the runtime module and is reachable from unit tests that never touch KSP or a real
 * classpath.
 */
data class CallableEntryModel(
    val name: String,
    val arity: Int,
    val paramTags: List<Tag>,
    val returnTag: Tag,
    val kind: String,
    /** The full lambda expression text, e.g. `{ args -> greet(args[0] as String) }`. */
    val lambdaBody: String,
    /**
     * Mirrors `python.multiplatform.reflection.ExposedCallable.isSuspend`: the declaration was a
     * `suspend fun`, so [lambdaBody] starts a coroutine and evaluates to a `PendingCall` rather
     * than to the value. [returnTag] still describes the *declared* return type, which is what the
     * boundary marshals once the coroutine finishes.
     */
    val isSuspend: Boolean = false,
    /**
     * Mirrors `ExposedCallable.paramNames`: the declared parameter names, in [paramTags] order.
     * Always fillable here -- unlike the artefact walker's ASM path over a jar with no debug
     * info, KSP reads Kotlin source, where every parameter has a name. Empty only for a shape
     * with no parameters to name (arity 0), which is indistinguishable from "not supplied" and is
     * why [python.multiplatform.reflection.ExposedCallable]'s own `init` treats an empty list as
     * either.
     */
    val paramNames: List<String> = emptyList(),
    /**
     * Mirrors `ExposedCallable.paramTypeNames`: the declared Kotlin type of each parameter --
     * [TypeShape.rendered], not [paramTags] -- so a `Dp`-typed parameter (were one ever visible to
     * KSP; this repository's only source of one is the artefact walker) would read `Dp` here and
     * `FLOAT` there. Filled from the same [TypeShape] [paramTags] is already computed from, so
     * this costs no extra type resolution.
     */
    val paramTypeNames: List<String> = emptyList(),
    /**
     * Mirrors `ExposedCallable.returnTypeName`. This is the field `fbab1a68` gates ownership of a
     * `TypeTag.OBJECT` result on: [python.multiplatform.ffi.upcall.PythonProxySource] will not
     * wrap a result in a finaliser-bearing owner unless the producer names the Kotlin type it
     * came back as, because `OBJECT` also covers a `PyObject` that happens to be an `int`, and
     * owning that would release a handle nobody issued. Leaving this `null` was the whole reason
     * every KSP-generated entry kept leaking a handle per `TypeTag.OBJECT` result --
     * `WalkedArtifactComposeModifierTest`'s `emptyModifier()` measured one per run. Filled from
     * [TypeShape.rendered] wherever a declared return type is known; `null` only where none is
     * (there is no such case among the [CallableEntryModel]-producing call sites in
     * `FragmentScanner` today).
     */
    val returnTypeName: String? = null,
    /**
     * Mirrors `ExposedCallable.paramHasDefault`. A flag nothing acts on yet -- see the runtime
     * field's own doc -- carried because [com.google.devtools.ksp.symbol.KSValueParameter
     * .hasDefault] is a direct read, not a derived fact, so there is no reason to withhold it.
     */
    val paramHasDefault: List<Boolean> = emptyList(),
)

data class ClassModel(
    val name: String,
    val memberNames: List<String>,
    /** The full lambda expression text for `traverse`, or `null` if the class has no
     * `PyObject`-typed fields and therefore needs no `tp_traverse` slot. */
    val traverseBody: String?,
    /** Mirrors `python.multiplatform.reflection.ReflectedClassKind` by name, for the same reason
     * [CallableEntryModel.kind] mirrors `CallableKind`. */
    val kind: String = "CLASS",
    /** Entry names in declaration order, for [kind] `"ENUM"`; empty otherwise. */
    val enumEntryNames: List<String> = emptyList(),
)

data class FragmentModel(
    val moduleName: String,
    val entries: List<CallableEntryModel>,
    val classes: List<ClassModel>,
)

/**
 * Drops entries whose name was already taken, keeping the first.
 *
 * Two declarations can legally share one exposed name -- an instance property and a
 * companion-object property of the same name, or an `expect`/`actual` pair both visible to a
 * platform compilation. `UpcallTable.register` only rejects collisions *between* fragments, so a
 * duplicate inside one fragment would silently make `resolve` return whichever entry landed last.
 * Scanning order puts instance members first, so the receiver-taking entry is the survivor.
 */
fun List<CallableEntryModel>.distinctByName(): List<CallableEntryModel> = distinctBy { it.name }
