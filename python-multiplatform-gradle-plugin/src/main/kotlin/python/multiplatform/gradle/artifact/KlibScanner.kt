package python.multiplatform.gradle.artifact

import org.jetbrains.kotlin.library.abi.AbiClassifierReference
import org.jetbrains.kotlin.library.abi.AbiCompoundName
import org.jetbrains.kotlin.library.abi.AbiFunction
import org.jetbrains.kotlin.library.abi.AbiQualifiedName
import org.jetbrains.kotlin.library.abi.AbiType
import org.jetbrains.kotlin.library.abi.AbiTypeArgument
import org.jetbrains.kotlin.library.abi.AbiTypeNullability
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import java.io.File

/**
 * `docs/design/ecosystem.md` §5b's second producer, adapted for Kotlin/Native klibs -- ROADMAP §16e.
 *
 * JVM uses ASM (plus `kotlin-metadata-jvm` for the payload `@Metadata` carries). A klib needs
 * neither: `LibraryAbiReader` decodes the ABI protobuf directly into Kotlin-typed declarations --
 * proper qualified names, `isSuspend`, extension receivers, unerased parameter and return types --
 * so there is no facade problem and no mangled-name problem to work around.
 *
 * ### This walker runs behind a worker classloader boundary, not in the calling task's own classloader
 *
 * Called directly, in the plugin's own classloader, this throws `NoSuchMethodError:
 * KotlinLibraryImplKt.createKotlinLibrary$default(...)` in a real consumer build while passing its own
 * unit tests -- and the difference between those two is the whole problem: in the plugin's test JVM
 * there is one `org.jetbrains.kotlin.library.*` on the classpath, and in a consumer build there are
 * two. The Kotlin Gradle Plugin puts `kotlin-util-klib`/`kotlin-util-io` on the **root project's**
 * buildscript scope, which is a *parent* of the plugin classloader, so parent-first delegation serves
 * half of `org.jetbrains.kotlin.library.*` from KGP's version and half -- the `.abi` half, which only
 * `kotlin-compiler-embeddable` ships -- from this plugin's 2.0.20.
 * `python-multiplatform-gradle-plugin/build.gradle.kts` records the `CodeSource` measurement that
 * proved it and both attempts at aligning the version that failed before classloader isolation was
 * tried. [KlibScanWorkAction] is that isolation: every caller of this object goes through
 * [WorkerExecutor.scanKlibIsolated][python.multiplatform.gradle.artifact.scanKlibIsolated] rather than
 * calling [scanKlib]/[scanKlibDeclarations] directly, and `:python-multiplatform-gradle-plugin-fixtures:klib-artifact
 * :generatePythonArtifactBindings`/`:generatePythonStubs` passing is what confirms the isolation
 * actually holds in a consumer build, not only in this file's own unit tests.
 *
 * **Experimental API usage**, kept entirely inside this file so any break across a Kotlin compiler
 * bump is contained here:
 * - `LibraryAbiReader.readAbiInfo`
 * - `LibraryAbi.topLevelDeclarations.declarations`
 * - `AbiFunction` (`qualifiedName`, `isSuspend`, `isConstructor`, `valueParameters`, `returnType`,
 *   `hasExtensionReceiverParameter`, `contextReceiverParametersCount`)
 * - `AbiValueParameter` (`type`, `hasDefaultArg`)
 * - `AbiQualifiedName` (`packageName`, `relativeName`, `toString`)
 * - `AbiType.Simple` (`classifierReference`, `arguments`, `nullability`) and `AbiTypeNullability`
 * - `AbiTypeArgument.TypeProjection`/`.StarProjection`
 * - `AbiClassifierReference.ClassReference` (`className`)
 *
 * ### Two results from one walk, the same as the JVM producer
 *
 * [scanKlib] returns the bindings, [scanKlibDeclarations] returns one [DeclarationModel] per
 * declaration *including the declined ones*, and both are `.map`s over the same [Candidate] list --
 * `docs/design/pyi-generation-design.md` §2.2's "one representation, produced by the scanner, consumed by
 * two renderers", which [ArtifactScanner] already satisfies and which this file used not to: it
 * dropped a declaration at a `filter` before anything could record that it had been seen. That is why
 * everything this walker declines is now declined *with a reason* instead of by disappearing, and why
 * klib declarations reach `PythonStubsTask` at all.
 *
 * ### Overload handling matches the JVM producer, not the JVM producer's floor
 *
 * A klib's ABI gives exact Kotlin types the same way `@Metadata` does for a jar, so
 * [ArtifactScanner.disambiguateOverloads] -- the `name__<types>` scheme -- applies unchanged, called
 * here rather than reimplemented. An earlier version of this file dropped every overload group
 * outright, reasoning that Python cannot dispatch on argument type; that reasoning is about
 * arbitration, not naming, and [ArtifactScanner]'s own KDoc already settles it: the JVM producer used
 * to drop for the same reason and stopped once metadata gave it a name to disambiguate by. Doing the
 * opposite here would mean the same `padding`-shaped overload set spells differently depending on
 * which producer found it, which the two producers landing in one
 * `python.multiplatform.generated.artifacts.ArtifactTable` cannot afford.
 *
 * ### What a klib can and cannot fill in the model
 *
 * | [DeclarationModel] field | klib | how |
 * |---|---|---|
 * | `simpleName`, `owner` | yes | `AbiQualifiedName`'s two halves, which is *better* than the JVM producer's: no facade to unwrap |
 * | `ownerIsClass` | yes, always `false` | only `topLevelDeclarations` is walked; a class's members live under `AbiClass` |
 * | `isSuspend`, `parameters[].declaresDefault` | yes | declared on `AbiFunction`/`AbiValueParameter` |
 * | `returnType`, `parameters[].type` incl. nullability and type arguments | yes | `AbiType.Simple`'s `nullability` and `arguments` |
 * | `parameters[].name` | **no** | 2.0.20's `AbiValueParameter` declares exactly five members (`type`, `isVararg`, `hasDefaultArg`, `isNoinline`, `isCrossinline`) and none is a name -- checked with `javap`. `parameterNamesKnown = false` is exactly the case §3.2 already defines for a Java jar built without `-parameters` |
 * | `isComposable` | yes | `AbiAnnotatedEntity.hasAnnotation`; see [COMPOSABLE] for what is and is not proved about it here |
 * | `receiver` | **no** -- and the declaration is declined | see below |
 * | value-class identity (`KotlinTypeModel.valueClass`) | **no** -- and the declaration is declined | see below |
 *
 * The last two rows are the point: they are exactly the two things this walker already refuses to
 * bind, so nothing here is *bound* on a field it had to leave empty. A declaration whose shape klib
 * cannot describe is declined with the reason, not admitted with a hole in it.
 *
 * ### Value classes are declined, not unwrapped -- for now
 *
 * [klibBoundaryTypeOf] recognises only `kotlin`'s own primitives, `String`, `ByteArray` and `Unit`.
 * A value class parameter or return (`androidx.compose.ui.unit.Dp`, `kotlinx.coroutines.CoroutineName`)
 * therefore maps to `null` and the whole declaration is declined -- never partially bound with a raw
 * underlying value standing in for the wrapper, which is what would let `16.0` silently decode as
 * `Dp.Unspecified` the way a naive JVM-descriptor reader would.
 *
 * This is the same *safety* property the JVM producer reached with `resolveKotlinType`, but not the
 * same *reach*: `resolveKotlinType` goes on to construct and unwrap a value class when its
 * constructor and accessor are visible, using `@Metadata`'s payload to know the underlying type and
 * visibility. `AbiClass` (a klib declaration alongside `AbiFunction`) carries `isValue`, but not a
 * value class's underlying type or its constructor's visibility -- that would mean resolving the
 * classifier reference to its own `AbiClass`, finding its constructor among its declarations, and
 * reading the *visibility* off it, none of which `AbiDeclarationWithModality` exposes (it carries
 * modality -- `open`/`final`/`abstract` -- not visibility). Doing that safely is a second walker,
 * not an extension of this one, and is left for the next pass rather than guessed at here. The model
 * says so too: `KotlinTypeModel.valueClass` stays `null` for every klib type rather than claiming a
 * `ValueClassModel` this walker cannot fill.
 *
 * ### Extension functions are declined, not bound -- a version floor, verified rather than assumed
 *
 * The JVM producer binds an extension by reading its receiver's *type* off `KmFunction
 * .receiverParameterType` (`kotlin-metadata-jvm`). The klib equivalent would be
 * `AbiValueParameterKind.EXTENSION_RECEIVER` surfacing the receiver inside `AbiFunction
 * .valueParameters`, typed the same as any other parameter -- and that shape exists, but only from
 * `kotlin-compiler-embeddable` **2.2.20** onward. This build pins **2.0.20**
 * (`python-multiplatform-gradle-plugin/build.gradle.kts`), whose `AbiValueParameter` carries no
 * `kind` at all and whose `AbiFunction` exposes only `hasExtensionReceiverParameter: Boolean` --
 * *that* one exists, but nothing behind it gives the receiver's type. Checked by decompiling both
 * jars' `AbiFunction`/`AbiValueParameter` class files with `javap`, not inferred from a changelog:
 * `2.0.20`'s `AbiFunction.class` is 1713 bytes against `2.2.20`'s 2417, and the extra bytes are
 * exactly `getHasExtensionReceiverParameter$annotations`, `getContextReceiverParametersCount` and
 * the `kind`-bearing `AbiValueParameterKind` enum, none of which `2.0.20` has. A function with
 * `hasExtensionReceiverParameter == true` is therefore declined outright here, the same way the
 * pre-metadata JVM producer declined every extension before `kotlin-metadata-jvm` was added --
 * binding one with the wrong arity (mistaking the receiver for absent, or misreading argument N for
 * N+1) would be worse than not binding it. Raising the pin to reach 2.2.20's shape is a real option
 * ROADMAP §16e already names as a cost to weigh (a newer, still-`@ExperimentalLibraryAbiReader`
 * compiler artefact on the plugin classpath) rather than one to spend inside this pass.
 */
@OptIn(ExperimentalLibraryAbiReader::class)
internal object KlibScanner {

    /**
     * `docs/archive/pyi-generation-pythonic-stubs.md` §3.6's name rule (the Pythonic naming, now pythonx-compose's) depends on this: a composable keeps its
     * PascalCase spelling where every other function becomes snake_case.
     *
     * Read here for the same reason [ArtifactScanner.isComposable] reads it off ASM -- carried in the
     * model, never *bound* (no composable can be, its `$composer`/`$changed` parameters are not
     * boundary types). **Not covered by a fixture:** neither klib this module's tests can reach
     * (`kotlin-stdlib`, `kotlinx-coroutines-core`) contains a composable, so what is proved here is
     * that reading it is free and cannot mis-bind, not that it returns `true` on a real Compose klib.
     */
    private val COMPOSABLE = AbiQualifiedName(AbiCompoundName("androidx.compose.runtime"), AbiCompoundName("Composable"))

    /**
     * The marker that put a wrong declaration into a real consumer build before this walker had a
     * fixture that actually *compiled* the generated fragment: `kotlinx-coroutines-core`'s
     * `checkIndexOverflow` is Kotlin-`internal`, kept in `LibraryAbiReader`'s ABI dump only because
     * `@PublishedApi` makes it binary-visible to inline call sites in other modules. That is a
     * bytecode-visibility fact, not a source-visibility one: [candidateOrNull]'s generated call
     * expression is ordinary Kotlin source in the *consumer's* module, and the Kotlin compiler still
     * enforces `internal` there, which `WalkedKlibArtifactPythonImportTest`'s KDoc records finding
     * the hard way -- `compileKotlinAndroidNativeArm64` failing with "it is internal in file" the
     * first time this walker's output was ever actually compiled, not merely unit-tested. A plain
     * (non-`@PublishedApi`) `internal` declaration never reaches here at all: `readAbiInfo`'s default
     * filtering already excludes it from `topLevelDeclarations`, which is *why* the one exception
     * `@PublishedApi` carves out is the one this walker has to check for by hand.
     */
    private val PUBLISHED_API = AbiQualifiedName(AbiCompoundName("kotlin"), AbiCompoundName("PublishedApi"))

    /** Every binding this klib offers, sorted by name -- the counterpart of [ArtifactScanner.scanJar]. */
    fun scanKlib(klib: File, includePrefixes: List<String>): List<ArtifactCallable> =
        scan(klib, includePrefixes).mapNotNull { it.callable }.sortedBy { it.name }

    /** The same walk with the other half of its result kept -- the counterpart of
     * [ArtifactScanner.scanDeclarations], sorted the same way so the two producers' models can be
     * concatenated without either one's order depending on which walked first. */
    fun scanKlibDeclarations(klib: File, includePrefixes: List<String>): List<DeclarationModel> =
        scan(klib, includePrefixes)
            .map { it.declaration }
            .sortedWith(compareBy({ it.owner }, { it.simpleName }, { it.bindingName ?: "" }))

    private fun scan(klib: File, includePrefixes: List<String>): List<Candidate> {
        val abi = try {
            LibraryAbiReader.readAbiInfo(klib)
        } catch (e: Exception) {
            return emptyList()
        }

        val candidates = abi.topLevelDeclarations.declarations
            .filterIsInstance<AbiFunction>()
            .filter { matchesInclude(it.qualifiedName, includePrefixes) }
            // Not a *top-level* shape at all: a constructor belongs to an `AbiClass` and needs a
            // receiver handle to be worth anything. Filtered rather than declined for the same reason
            // `ArtifactScanner` never builds a model for `<init>`.
            .filter { !it.isConstructor }
            .mapNotNull { candidateOrNull(it) }

        // Same rule, same implementation as the JVM producer -- see this object's KDoc.
        return ArtifactScanner.disambiguateOverloads(candidates)
    }

    /**
     * A namespace match against the declaration's *package*, not its full qualified name.
     *
     * `AbiQualifiedName.toString()` joins `packageName` and `relativeName` with `/`
     * (`kotlinx.coroutines.channels/broadcast`), not `.`. Matching `qualifiedName.toString()` against
     * an include prefix the way the JVM producer matches a dotted class name silently misses every
     * declaration whose package *is* the prefix rather than a sub-package of it: `kotlinx.coroutines`
     * (the package) contributes 37 top-level declarations in `kotlinx-coroutines-core`, none of
     * which `"kotlinx.coroutines".startsWith` logic on the full string would have matched, because
     * the character after the prefix is `/`, not `.`. `packageName.toString()` has no such
     * `/`-suffix problem: it is exactly the dotted package, so the JVM producer's own namespace rule
     * (`equal, or a dot-bounded prefix`) applies unchanged.
     */
    private fun matchesInclude(qualifiedName: AbiQualifiedName, includePrefixes: List<String>): Boolean {
        if (includePrefixes.isEmpty()) return true
        val pkg = qualifiedName.packageName.toString()
        return includePrefixes.any { pkg == it || pkg.startsWith("$it.") }
    }

    /**
     * One declaration's model and, when the boundary can carry it, its binding.
     *
     * `null` -- the declaration vanishing entirely rather than being declined -- only when its
     * signature has no name a stub could write: a type *parameter* (`fun <T> f(t: T)`), a dynamic or
     * error type. That is [ArtifactScanner.declarationModelOf]'s own rule and
     * `docs/archive/pyi-generation-pythonic-stubs.md` §3.1's last row: `BindingPolicy` rejects generic declarations
     * and stubbing what cannot even be spelled would be a lie, so there is nothing to record.
     *
     * `internal`, not `private`: no real klib available to this module's tests binds a declaration
     * that also declares a default (every one found in `kotlin-stdlib`/`kotlinx-coroutines-core` is
     * declined first, for an unrelated reason -- see `KlibScannerTest`'s
     * `declaresDefaultIsFalseForABoundDeclarationEvenWhenTheAbiDeclaresADefault`). Proving the
     * `declaresDefault`/`paramHasDefault` pairing therefore has to construct the `AbiFunction` by hand
     * rather than find one, which means calling this directly with a test double -- `scanKlib`/
     * `scanKlibDeclarations` only ever call `LibraryAbiReader.readAbiInfo` on a real file and have no
     * seam to inject one.
     */
    internal fun candidateOrNull(function: AbiFunction): Candidate? {
        val model = declarationModelOf(function) ?: return null
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }

        // A `suspend` function's Kotlin/Native shape is not one this walker's generated call
        // expression can supply (it needs a `Continuation`), the same reason the JVM producer
        // declines it -- but a klib says so directly rather than requiring the JVM producer's
        // inference from a transformed descriptor.
        if (function.isSuspend) return declined("suspend")
        // See PUBLISHED_API's KDoc: `@PublishedApi internal` is binary-visible but not
        // source-visible, and the generated call expression is source in the consumer's own module.
        if (function.hasAnnotation(PUBLISHED_API)) {
            return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
        }
        // See this object's KDoc, "Extension functions are declined, not bound": the pinned
        // compiler-embeddable version exposes the flag but not the receiver's type, which is also why
        // `model.receiver` is left `null` rather than filled with a guess.
        if (function.hasExtensionReceiverParameter) {
            return declined("an extension receiver whose type kotlin-compiler-embeddable 2.0.20's klib ABI does not expose")
        }
        // A context receiver has no call-site syntax this walker generates and, on this pinned
        // version, no type either (`AbiFunction.contextReceiverParametersCount` is a count, not a
        // list) -- declined rather than silently mis-arity'd.
        if (function.contextReceiverParametersCount > 0) {
            return declined("${function.contextReceiverParametersCount} context receiver(s), whose types this klib ABI does not expose")
        }

        val returnType = klibBoundaryTypeOf(model.returnType)
            ?: return declined("no boundary type for return ${model.returnType.render()}")
        val boundaryTypes = model.parameters.map { parameter ->
            klibBoundaryTypeOf(parameter.type)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        if (boundaryTypes.any { it.isReturnOnly }) {
            return declined("a parameter of a return-only boundary type")
        }

        val qualifiedName = function.qualifiedName.dotted()
        val argumentExpressions = boundaryTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val body = returnType.wrapReturn("$qualifiedName(${argumentExpressions.joinToString(", ")})")
        val arity = boundaryTypes.size

        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = boundaryTypes.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ $body }" else "{ args -> $body }",
            // A klib's `AbiValueParameter` carries no name, only a `type` -- unlike
            // `kotlin-metadata-jvm`'s `KmValueParameter`, which does. `paramNames` therefore stays
            // empty, which is what `ArtifactCallable.paramNames`'s KDoc already means by "not
            // supplied"; `DeclarationModel.parameterNamesKnown` says the same thing to the stub side.
            paramTypeNames = model.parameters.map { it.type.qualifiedName },
            returnTypeName = model.returnType.qualifiedName,
            // Deliberately all `false`, not `model.parameters.map { it.declaresDefault }`.
            // `ArtifactCallable.paramHasDefault`'s contract (see its KDoc) is a statement about
            // [lambdaBody] -- exactly the slots the generated call may omit -- and never about the
            // declaration. `lambdaBody` above passes every argument unconditionally; this scanner has
            // no `ArtifactScanner.applyDefaultOmission` pass to earn a narrower answer, because a
            // klib walk runs on its own in [KlibScanWorkAction] and never sees the whole-artifact
            // overload picture that pass needs. Reporting `declaresDefault` here was the old, now-
            // wrong pairing: `pythonx._bind` would have filled a slot with `null` that this body has
            // no branch for, and the generated call would have cast a `null` where the declaration
            // requires a value. `ArtifactRendering.omittableSlotsOf` already guarded against exactly
            // that by reading whether the body branches on the sentinel at all -- true for every klib
            // entry, since none of them do -- so this is the same answer moved to where it belongs,
            // and that render-time guard stays as the second line of defence for any producer that
            // repeats this mistake.
            paramHasDefault = List(arity) { false },
        )
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnType.tag,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(
                        boundaryTag = boundaryTypes[index].tag,
                        // Mirrors `paramHasDefault` just above, and for the identical reason: this is
                        // `ArtifactScanner.applyDefaultOmission`'s rewrite of `declaration.parameters
                        // [].declaresDefault` (see that function's KDoc), done here instead because a
                        // klib walk has no separate omission pass to do it in a second step. Left at
                        // `parameter.declaresDefault` -- the raw `AbiValueParameter.hasDefaultArg` --
                        // a bound declaration would describe the Kotlin source's defaults rather than
                        // the generated binding's, and `PyiRendering`'s `if (parameter.declaresDefault)
                        // " = ..."` would advertise an omission `lambdaBody` never implements: the
                        // `.pyi` would type-check a call the runtime binding then rejects.
                        // `KlibScannerTest.declaresDefaultIsFalseForABoundDeclarationEvenWhenTheAbi
                        // DeclaresADefault` pins this; `boundDeclarationWithADefaultDoesNotOccurIn
                        // AvailableRealKlibs` records why that test constructs its own `AbiFunction`
                        // instead of finding one.
                        declaresDefault = false,
                    )
                },
            ),
        )
    }

    /** The declared shape of one klib function, independent of whether the boundary can carry it --
     * [ArtifactScanner.declarationModelOf]'s counterpart, and `null` under the same condition. */
    private fun declarationModelOf(function: AbiFunction): DeclarationModel? {
        val parameters = function.valueParameters.map { parameter ->
            DeclaredParameter(
                name = null,
                type = typeModelOf(parameter.type) ?: return null,
                declaresDefault = parameter.hasDefaultArg,
            )
        }
        return DeclarationModel(
            simpleName = function.qualifiedName.relativeName.toString(),
            owner = function.qualifiedName.packageName.toString(),
            // Only `topLevelDeclarations` is walked, so every declaration reaching here is package-level.
            ownerIsClass = false,
            // Never filled: see this object's KDoc. A function that *has* one is declined just below,
            // so no bound declaration is ever missing a receiver it should have had.
            receiver = null,
            parameters = parameters,
            returnType = typeModelOf(function.returnType) ?: return null,
            parameterNamesKnown = false,
            isComposable = function.hasAnnotation(COMPOSABLE),
            isSuspend = function.isSuspend,
        )
    }

    /**
     * A klib type as [KotlinTypeModel] spells it, or `null` when the classifier has no Kotlin name to
     * write -- a type-parameter reference, a dynamic or error type.
     *
     * `valueClass` is always `null`: see this object's KDoc for why a klib cannot answer that
     * question on this compiler version, and why leaving it empty is the honest answer rather than a
     * gap. Nullability and type arguments, in contrast, *are* on `AbiType.Simple` and are read --
     * they are what lets [klibBoundaryTypeOf] refuse a nullable primitive.
     */
    private fun typeModelOf(type: AbiType?): KotlinTypeModel? {
        // A `null` return type is how the ABI spells `Unit` for some declarations; anything else
        // unrecognised is refused rather than guessed.
        if (type == null) return KotlinTypeModel("kotlin.Unit")
        if (type !is AbiType.Simple) return null
        val reference = type.classifierReference
        if (reference !is AbiClassifierReference.ClassReference) return null
        val arguments = type.arguments.map { argument ->
            when (argument) {
                is AbiTypeArgument.StarProjection -> null
                is AbiTypeArgument.TypeProjection -> typeModelOf(argument.type) ?: return null
                else -> return null
            }
        }
        return KotlinTypeModel(
            qualifiedName = reference.className.dotted(),
            isNullable = type.nullability == AbiTypeNullability.MARKED_NULLABLE,
            arguments = arguments,
        )
    }

    /** `packageName/relativeName` is `AbiQualifiedName`'s own spelling; Kotlin's is all dots. */
    private fun AbiQualifiedName.dotted(): String = toString().replace('/', '.')

    private fun KotlinTypeModel.render(): String = qualifiedName + if (isNullable) "?" else ""

    /**
     * Maps precise klib types to [BoundaryType]. See this object's KDoc for why an unrecognised type
     * -- a value class included -- maps to `null` and declines the whole declaration rather than
     * being read as its erased underlying primitive.
     *
     * A **nullable** one maps to `null` too, which is `resolveKotlinType`'s rule for the JVM producer
     * arrived at independently and for the same reason: `TypeTag.INT` carries a `Long` and the
     * generated read narrows it (`(args[0] as Long).toInt()`), so a Python `None` for an `Int?` would
     * throw inside the cast instead of reaching the declaration. The JVM producer can fall through to
     * an object handle in that case; this walker has no object boundary at all yet, so it declines.
     * Type *arguments* are ignored rather than refused, because no type this admits has any.
     */
    private fun klibBoundaryTypeOf(type: KotlinTypeModel): BoundaryType? {
        if (type.isNullable) return null
        return when (type.qualifiedName) {
            "kotlin.Boolean" -> BoundaryType("BOOLEAN", "(%s as Boolean)", "(%s)")
            "kotlin.Byte" -> BoundaryType("INT", "(%s as Long).toByte()", "(%s).toLong()")
            "kotlin.Short" -> BoundaryType("INT", "(%s as Long).toShort()", "(%s).toLong()")
            "kotlin.Int" -> BoundaryType("INT", "(%s as Long).toInt()", "(%s).toLong()")
            "kotlin.Long" -> BoundaryType("INT", "(%s as Long)", "(%s)")
            "kotlin.Float" -> BoundaryType("FLOAT", "(%s as Double).toFloat()", "(%s).toDouble()")
            "kotlin.Double" -> BoundaryType("FLOAT", "(%s as Double)", "(%s)")
            "kotlin.String" -> BoundaryType("STRING", "(%s as String)", "(%s)")
            "kotlin.ByteArray" -> BoundaryType("BYTES", "(%s as ByteArray)", "(%s)")
            "kotlin.Unit" -> BoundaryType("UNIT", "(%s as Unit)", "(%s)", isReturnOnly = true)
            else -> null
        }
    }
}
