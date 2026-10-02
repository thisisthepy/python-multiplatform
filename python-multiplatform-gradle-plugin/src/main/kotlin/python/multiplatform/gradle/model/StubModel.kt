package python.multiplatform.gradle.model

import java.io.Serializable

/**
 * `docs/design/pyi-generation-design.md` §2.2's declaration model: **one representation, produced by the
 * scanner, consumed by two renderers.**
 *
 * ### Why a third model rather than a widened existing one
 *
 * `python.multiplatform.ksp.CallableEntryModel` and
 * `python.multiplatform.gradle.artifact.ArtifactCallable` are near-identical and both were cut down
 * to *exactly* what a call site needs -- the reasoning `PythonProxySource`'s KDoc gives for
 * `ExposedCallable`. A `.pyi` needs a strict superset of that, because a stub describes a signature
 * and a call site only has to produce one. §2.2 rejects widening either in place: it would put
 * fields on a hot path whose renderer must ignore them, and it would not fix the KSP/ASM asymmetry.
 *
 * §2.1 lists what every existing representation reads and then drops, one function before it would
 * be used: parameter **names**, **defaults**, **nullability**, the **declared Kotlin type**, the
 * **extension-receiver** flag, the **overload grouping**, and **value-class identity**. `958c0082`
 * has since put flat `paramNames`/`paramTypeNames`/`returnTypeName`/`isExtension`/`receiverTypeName`/
 * `paramHasDefault` on `ExposedCallable` for the Python-side dispatcher -- so part of that loss is
 * already repaired, but as strings, without nullability, without type arguments, without value-class
 * identity, and only for declarations that were *bound*. Those four are exactly what a stub cannot do
 * without, which is why this is a model and not six more `List<String>`s.
 *
 * ### One walk
 *
 * §2.2 also rejects "a second scan, independent of the binder's" -- two readers of the same jars that
 * can disagree. So these are built at the point the binding is built, from the same
 * `ResolvedFunction`, inside the same class walk. `ArtifactScanner.scanDeclarations` is that walk
 * with the other half of its result kept.
 *
 * ### Not implemented here
 *
 * The KSP producer (`python-multiplatform-ksp`) does not emit this model yet: KSP sees only the
 * consumer's own source, and the artefact walker is where the third-party surface that motivates
 * stubs comes from. §2.3 records that KSP would need to read *nothing new* to supply it. The klib
 * producer does not exist at all (§0, §7).
 *
 * `Serializable`, along with [DeclaredParameter], [KotlinTypeModel] and [ValueClassModel]: this
 * crosses the `WorkerExecutor` isolation boundary `KlibScanWorkAction` runs `KlibScanner` behind --
 * see that file's KDoc for why the boundary exists at all.
 */
internal data class DeclarationModel(
    /** The Kotlin declaration's own name -- `padding`, not `padding__Dp` and not the FQN. */
    val simpleName: String,
    /** The Kotlin package for a top-level declaration, or the qualified class name for a static.
     * Which of the two it is, is [ownerIsClass]; the distinction is not recoverable from the string
     * and decides which `sys.modules` entry the runtime publishes onto. */
    val owner: String,
    val ownerIsClass: Boolean,
    /** The extension receiver's declared type, or `null` when this is not an extension function. */
    val receiver: KotlinTypeModel?,
    /** How the receiver marshals -- it is the runtime's `a0` and therefore has a boundary type of
     * its own, which is not recoverable from [parameters] because it is not one of them. */
    val receiverBoundaryTag: String? = null,
    /** Declared value parameters, receiver **excluded**. */
    val parameters: List<DeclaredParameter>,
    val returnType: KotlinTypeModel,
    /**
     * How the return marshals (`python.multiplatform.reflection.TypeTag`'s name), or `null` when the
     * declaration was declined and therefore has no boundary at all. `OBJECT` means the value reaches
     * Python as a bare `HandleTable` integer, which is why the Kotlin-FQN stub product annotates it
     * `int` -- see `renderKotlinFqnStubs`.
     */
    val returnBoundaryTag: String? = null,
    /**
     * The `UpcallTable` key the binder gave this declaration, or `null` when it declined it.
     *
     * This is the whole of §2.2's third property: a declined declaration stays visible, with its
     * [declineReason], instead of vanishing at a `return null`. It is also what makes the stub
     * honest -- an entry with no table key has no runtime attribute, and stubbing it would promise a
     * call that raises (§4.5's rule, generalised).
     */
    val bindingName: String? = null,
    val declineReason: String? = null,
    /** `false` for a Java class compiled without `-parameters` (§3.2), where every [DeclaredParameter]
     * name is `null` and the stub must be positional-only rather than invent a keyword. */
    val parameterNamesKnown: Boolean = true,
    val isComposable: Boolean = false,
    val isSuspend: Boolean = false,
    val kind: String = "FUNCTION",
    /**
     * What the declared return type **is a**, nearest first, as qualified Kotlin names -- the
     * ancestry `ArtifactCallable.returnSupertypes` carries into the binding layer, kept here so a
     * stub class can extend the class a value of it may fill (`BitmapPainter` fills a `Painter`
     * slot). Empty for a type nothing could read an ancestry for, which is the truth rather than a
     * gap to fill.
     */
    val returnSupertypes: List<String> = emptyList(),
) : Serializable

/** @param name `null` when the producer could not read one (§3.2); never a synthesised `arg0`. */
internal data class DeclaredParameter(
    val name: String?,
    val type: KotlinTypeModel,
    val declaresDefault: Boolean,
    /** `python.multiplatform.reflection.TypeTag`'s name, or `null` when the type was declined. */
    val boundaryTag: String? = null,
) : Serializable

/**
 * A declared Kotlin type, as `@Metadata` spells it: qualified name, nullability, type arguments, and
 * -- when the classifier is a value class -- what it wraps and whether that wrapper can be opened
 * from outside its module.
 *
 * @param arguments a `null` element is a star projection.
 */
internal data class KotlinTypeModel(
    val qualifiedName: String,
    val isNullable: Boolean = false,
    val arguments: List<KotlinTypeModel?> = emptyList(),
    val valueClass: ValueClassModel? = null,
) : Serializable

/** The two booleans `python.multiplatform.gradle.artifact.ValueClassInfo` already computes, kept
 * rather than consumed inside a closure: §3.4's allowlist decision is not expressible without
 * knowing what the wrapper wraps. */
internal data class ValueClassModel(
    val underlying: KotlinTypeModel,
    val constructorIsPublic: Boolean,
    val propertyIsPublic: Boolean,
) : Serializable
