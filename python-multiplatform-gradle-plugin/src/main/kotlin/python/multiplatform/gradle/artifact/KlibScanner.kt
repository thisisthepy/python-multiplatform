package python.multiplatform.gradle.artifact

import org.jetbrains.kotlin.library.abi.AbiClass
import org.jetbrains.kotlin.library.abi.AbiClassKind
import org.jetbrains.kotlin.library.abi.AbiClassifierReference
import org.jetbrains.kotlin.library.abi.AbiCompoundName
import org.jetbrains.kotlin.library.abi.AbiDeclarationContainer
import org.jetbrains.kotlin.library.abi.AbiFunction
import org.jetbrains.kotlin.library.abi.AbiModality
import org.jetbrains.kotlin.library.abi.AbiProperty
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
 * | `ownerIsClass` | yes | `false` for top-level functions and constructors; `true` for object constants, object methods, companion handles, and member functions/properties |
 * | `isSuspend`, `parameters[].declaresDefault` | yes | declared on `AbiFunction`/`AbiValueParameter` |
 * | `returnType`, `parameters[].type` incl. nullability and type arguments | yes | `AbiType.Simple`'s `nullability` and `arguments` |
 * | `parameters[].name` | **no** | 2.0.20's `AbiValueParameter` declares exactly five members (`type`, `isVararg`, `hasDefaultArg`, `isNoinline`, `isCrossinline`) and none is a name -- checked with `javap`. `parameterNamesKnown = false` is exactly the case §3.2 already defines for a Java jar built without `-parameters` |
 * | `isComposable` | yes | `AbiAnnotatedEntity.hasAnnotation`; see [COMPOSABLE] for what is and is not proved about it here |
 * | `receiver` | **no** for extensions (declined); **yes** for member functions and member properties | see below |
 * | value-class identity (`KotlinTypeModel.valueClass`) | **no** -- and the declaration is declined | see below |
 *
 * The last two rows are the point: they are exactly the two things this walker already refuses to
 * bind, so nothing here is *bound* on a field it had to leave empty. A declaration whose shape klib
 * cannot describe is declined with the reason, not admitted with a hole in it.
 *
 * ### Value classes are declined, not unwrapped -- for now
 *
 * [klibBoundaryTypeOf] recognises only `kotlin`'s own primitives, `String`, `ByteArray` and `Unit`,
 * plus handle classes from the walked klib and its `classpath`.
 * A value class parameter or return (`androidx.compose.ui.unit.Dp`, `kotlinx.coroutines.channels.ChannelResult`)
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

    private val DEPRECATED = AbiQualifiedName(AbiCompoundName("kotlin"), AbiCompoundName("Deprecated"))

    /** Every binding this klib offers, sorted by name -- the counterpart of [ArtifactScanner.scanJar]. */
    fun scanKlib(
        klib: File,
        includePrefixes: List<String>,
        excludePackages: List<String> = emptyList(),
        classpath: List<File> = listOf(klib),
    ): List<ArtifactCallable> =
        scan(klib, includePrefixes, excludePackages, classpath).mapNotNull { it.callable }.sortedBy { it.name }

    /** The same walk with the other half of its result kept -- the counterpart of
     * [ArtifactScanner.scanDeclarations], sorted the same way so the two producers' models can be
     * concatenated without either one's order depending on which walked first. */
    fun scanKlibDeclarations(
        klib: File,
        includePrefixes: List<String>,
        excludePackages: List<String> = emptyList(),
        classpath: List<File> = listOf(klib),
    ): List<DeclarationModel> =
        scan(klib, includePrefixes, excludePackages, classpath)
            .map { it.declaration }
            .sortedWith(compareBy({ it.owner }, { it.simpleName }, { it.bindingName ?: "" }))

    private fun scan(
        klib: File,
        includePrefixes: List<String>,
        excludePackages: List<String>,
        classpath: List<File>,
    ): List<Candidate> {
        KlibKnmScanner.scan(klib, includePrefixes, excludePackages, classpath)?.let { return it }

        val abi = try {
            LibraryAbiReader.readAbiInfo(klib)
        } catch (e: Exception) {
            return emptyList()
        }

        val ownClasses = classesOf(abi.topLevelDeclarations)
        val classes = LinkedHashMap<String, AbiClass>(ownClasses)
        for (dependency in classpath) {
            if (dependency == klib) continue
            val depAbi = try {
                LibraryAbiReader.readAbiInfo(dependency)
            } catch (e: Exception) {
                continue
            }
            for ((name, cls) in classesOf(depAbi.topLevelDeclarations)) {
                classes.putIfAbsent(name, cls)
            }
        }

        val topLevelFunctions = abi.topLevelDeclarations.declarations
            .filterIsInstance<AbiFunction>()
            .filter { matchesInclude(it.qualifiedName, includePrefixes) }
            // SPEC B-11: `internal` packages and `excludePackages`, as on the JVM producer.
            .filter { !ArtifactScanner.isExcludedPackage(it.qualifiedName.packageName.toString(), excludePackages) }
            // Not a *top-level* shape at all: a constructor belongs to an `AbiClass` and needs a
            // receiver handle to be worth anything. Filtered rather than declined for the same reason
            // `ArtifactScanner` never builds a model for `<init>`.
            .filter { !it.isConstructor }
            .mapNotNull { candidateOrNull(it, classes) }

        val matchedClasses = ownClasses.values
            .filter { matchesInclude(it.qualifiedName, includePrefixes) }
            .filter { !ArtifactScanner.isExcludedPackage(it.qualifiedName.packageName.toString(), excludePackages) }

        val constructors = matchedClasses.flatMap { constructorCandidatesOf(it, ownClasses, classes) }
        val objectCandidates = matchedClasses.flatMap { objectCandidatesOf(it, ownClasses, classes) }
        val memberProperties = matchedClasses.flatMap { memberPropertyCandidatesOf(it, ownClasses, classes) }

        // Issue #237: the member functions of the klib's classes, as #215 binds them from a jar.
        val members = matchedClasses.flatMap { memberCandidatesOf(it, ownClasses, classes) }

        val entries = topLevelFunctions + constructors + objectCandidates + memberProperties + members
        val (properties, rest) = entries.partition { it.declaration.kind in ArtifactScanner.PROPERTY_KINDS }
        val (methods, functions) = rest.partition { it.declaration.kind == ArtifactScanner.METHOD_KIND }
        val settledFunctions = ArtifactScanner.disambiguateOverloads(ArtifactScanner.dropCollidingConstructors(functions))
        val settledMethods = ArtifactScanner.settleMethodNames(ArtifactScanner.disambiguateOverloads(methods), settledFunctions)
        val settledProperties = ArtifactScanner.settlePropertyNames(properties, settledFunctions + settledMethods)
        return settledFunctions + settledMethods + settledProperties
    }

    /**
     * Whether [cls] or any enclosing class in [ownClasses] is marked `@PublishedApi` or `@Deprecated`,
     * or is an `inner` class.
     *
     * `LibraryDeserializer$FileDeserializer.computeVisibilityStatus` propagates
     * `VisibilityStatus.INTERNAL_PUBLISHED_API` from an `@PublishedApi internal` class down to its
     * constructors, properties, and functions without repeating the `@PublishedApi` annotation on each
     * child declaration. Checking the class hierarchy prevents binding constructors or properties of
     * `@PublishedApi internal` classes like `kotlinx.coroutines.YieldContext` or `JobImpl`.
     */
    private fun hasUnbindableClassAncestry(cls: AbiClass, ownClasses: Map<String, AbiClass>): Boolean {
        val pkg = cls.qualifiedName.packageName.toString()
        val segments = cls.qualifiedName.relativeName.toString().split('.')
        for (i in 1..segments.size) {
            val prefix = segments.take(i).joinToString(".")
            val fullName = if (pkg.isEmpty()) prefix else "$pkg.$prefix"
            val current = ownClasses[fullName] ?: continue
            if (current.hasAnnotation(PUBLISHED_API) || current.isInner) {
                return true
            }
        }
        return false
    }

    /**
     * Public constructors of a top-level, non-abstract, non-generic, non-value `class` (issue #191 Phase B).
     *
     * Mirrors `ArtifactScanner.constructorCandidates`: `owner` is the package (not the class),
     * `simpleName` is the class's simple name, `returnType` is the class itself, and `isConstructor`
     * is `true` so `ArtifactScanner.dropCollidingConstructors` and `PyiRendering` treat it identically
     * to a JVM constructor.
     */
    private fun constructorCandidatesOf(
        cls: AbiClass,
        ownClasses: Map<String, AbiClass>,
        classes: Map<String, AbiClass>,
    ): List<Candidate> {
        if (cls.kind != AbiClassKind.CLASS || cls.modality == AbiModality.ABSTRACT) return emptyList()
        if (cls.isInner || cls.isValue || cls.typeParameters.isNotEmpty() || cls.hasAnnotation(DEPRECATED)) return emptyList()
        // Only top-level classes publish under their package name as `pkg.ClassName`.
        val relative = cls.qualifiedName.relativeName.toString()
        if ('.' in relative) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()
        // In LibraryAbiReader, protected constructors of non-final classes appear in the ABI without a
        // visibility flag unless the class is final; however, a non-final class may also have a public
        // constructor. Wait: `LibraryDeserializer.computeVisibilityStatus` includes `PROTECTED`
        // constructors on `OPEN` and `SEALED` classes! Declining constructors of non-final classes avoids
        // emitting an uncompilable call to a `protected` constructor on an `open` or `sealed` class.
        if (cls.modality != AbiModality.FINAL) return emptyList()

        val clsQualified = cls.qualifiedName.dotted()
        val returnModel = KotlinTypeModel(clsQualified)
        if (handleClassOrNull(returnModel, classes) == null) return emptyList()
        val pkg = cls.qualifiedName.packageName.toString()

        return cls.declarations.filterIsInstance<AbiFunction>()
            .filter { it.isConstructor }
            .mapNotNull { constructor ->
                constructorCandidateOrNull(constructor, pkg, relative, returnModel, classes)
            }
    }

    private fun constructorCandidateOrNull(
        constructor: AbiFunction,
        pkg: String,
        clsSimpleName: String,
        returnModel: KotlinTypeModel,
        classes: Map<String, AbiClass>,
    ): Candidate? {
        val parameters = constructor.valueParameters.map { parameter ->
            DeclaredParameter(
                name = null,
                type = typeModelOf(parameter.type) ?: return null,
                declaresDefault = parameter.hasDefaultArg,
            )
        }
        val model = DeclarationModel(
            simpleName = clsSimpleName,
            owner = pkg,
            ownerIsClass = false,
            receiver = null,
            parameters = parameters,
            returnType = returnModel,
            parameterNamesKnown = false,
            isComposable = false,
            isSuspend = false,
            isConstructor = true,
        )
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason), isConstructor = true) }

        if (constructor.hasAnnotation(DEPRECATED)) {
            return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
        }
        if (constructor.hasAnnotation(PUBLISHED_API)) {
            return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
        }
        if (constructor.hasExtensionReceiverParameter) {
            return declined("an extension receiver whose type kotlin-compiler-embeddable 2.0.20's klib ABI does not expose")
        }
        if (constructor.contextReceiverParametersCount > 0) {
            return declined("${constructor.contextReceiverParametersCount} context receiver(s), whose types this klib ABI does not expose")
        }

        val returnType = klibBoundaryTypeOf(returnModel, classes)
            ?: return declined("no boundary type for return ${returnModel.render()}")
        val boundaryTypes = parameters.map { parameter ->
            klibBoundaryTypeOf(parameter.type, classes)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        if (boundaryTypes.any { it.isReturnOnly }) {
            return declined("a parameter of a return-only boundary type")
        }

        val qualifiedName = returnModel.qualifiedName
        val argumentExpressions = boundaryTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val body = returnType.wrapReturn("$qualifiedName(${argumentExpressions.joinToString(", ")})")
        val arity = boundaryTypes.size

        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = boundaryTypes.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ $body }" else "{ args -> $body }",
            paramTypeNames = parameters.map { it.type.qualifiedName },
            returnTypeName = qualifiedName,
            returnSupertypes = if (returnType.tag == OBJECT_TAG) supertypesOf(qualifiedName, classes) else emptyList(),
            paramHasDefault = List(arity) { false },
        )
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnType.tag,
                parameters = parameters.mapIndexed { index, parameter ->
                    parameter.copy(boundaryTag = boundaryTypes[index].tag, declaresDefault = false)
                },
            ),
            isConstructor = true,
        )
    }

    /**
     * Singleton `object` and `Companion` handles, constants (`STATIC_GETTER`), and functions (`FUNCTION`)
     * from a klib (issue #191 Phase B, mirroring #53 and #322 on the jar side).
     */
    private fun objectCandidatesOf(
        cls: AbiClass,
        ownClasses: Map<String, AbiClass>,
        classes: Map<String, AbiClass>,
    ): List<Candidate> {
        if (cls.kind != AbiClassKind.OBJECT || cls.hasAnnotation(DEPRECATED)) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()

        val qualified = cls.qualifiedName.dotted()
        val relative = cls.qualifiedName.relativeName.toString()
        val isNested = '.' in relative
        val outerQualified = if (isNested) qualified.substringBeforeLast('.') else null
        val outerClass = outerQualified?.let { ownClasses[it] }
        if (isNested && (outerClass == null || outerClass.hasAnnotation(DEPRECATED))) return emptyList()

        val simpleName = relative.substringAfterLast('.')
        // A nested `object` named `Companion` is the outer class's companion object: its constants are
        // bound under `Outer.Const` while generated source calls `Outer.Companion.Const`. Other nested
        // objects (and named companions such as `Job.Key` or `Channel.Factory`, which `LibraryAbiReader`
        // represents with the same `AbiClassKind.OBJECT`) keep their own qualified name as both
        // `boundOwner` and `callOwner`, and also publish a singleton handle `Outer.Name`.
        val isUnnamedCompanion = outerClass != null && simpleName == "Companion" && outerClass.kind != AbiClassKind.OBJECT
        val boundOwner = if (isUnnamedCompanion) outerQualified!! else qualified
        val callOwner = qualified

        val handleCandidate = if (outerClass != null && outerClass.kind in PROPERTY_OWNER_KINDS) {
            companionHandleCandidateOrNull(outerQualified, outerClass, simpleName, cls, classes)
        } else {
            null
        }

        // If the outer class is generic (`Channel<E>.Factory`), only `Companion` handle and primitive
        // constants under `Channel.Factory` can be spelled in Kotlin source without type arguments.
        val properties = cls.declarations.filterIsInstance<AbiProperty>().mapNotNull { property ->
            staticPropertyCandidateOrNull(property, boundOwner = boundOwner, callOwner = callOwner, ownerIsClass = true, classes = classes)
        }

        val functions = cls.declarations.filterIsInstance<AbiFunction>()
            .filter { !it.isConstructor && !isAnyMember(it) }
            .mapNotNull { function ->
                objectFunctionCandidateOrNull(function, owner = boundOwner, callOwner = callOwner, classes = classes)
            }

        return listOfNotNull(handleCandidate) + properties + functions
    }

    private fun companionHandleCandidateOrNull(
        outerQualified: String,
        outerClass: AbiClass,
        companionName: String,
        companionClass: AbiClass,
        classes: Map<String, AbiClass>,
    ): Candidate? {
        val companionQualified = "$outerQualified.$companionName"
        val ownerIsSupertype = companionClass.superTypes.any { superType ->
            ((superType as? AbiType.Simple)?.classifierReference as? AbiClassifierReference.ClassReference)
                ?.className?.dotted() == outerQualified
        }
        val typeName = if (ownerIsSupertype && outerClass.typeParameters.isEmpty()) outerQualified else companionQualified
        // Ensure the chosen return type is not a generic class that would require type arguments in a cast
        // unless it is the outer class of a generic companion (which `CompanionHandleBindingTest` casts as `Outer`?
        // No: in `CompanionHandleBindingTest`, `Box<T>.Companion` is NOT a subtype of `Box<T>`, its own type is `Box.Companion`!).
        val targetClass = classes[typeName] ?: return null
        if (targetClass.isValue || targetClass.hasAnnotation(PUBLISHED_API) || targetClass.hasAnnotation(DEPRECATED)) return null
        if (targetClass.typeParameters.isNotEmpty()) return null

        val boundary = BoundaryType(OBJECT_TAG, "(%s as $typeName)", "(%s)")
        val supertypes = supertypesOf(typeName, classes)
        return Candidate(
            callable = ArtifactCallable(
                name = companionQualified,
                arity = 0,
                paramTags = emptyList(),
                returnTag = boundary.tag,
                lambdaBody = "{ ${boundary.wrapReturn(companionQualified)} }",
                returnTypeName = typeName,
                returnSupertypes = supertypes,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                paramHasDefault = emptyList(),
                kind = STATIC_GETTER_KIND,
            ),
            declaration = DeclarationModel(
                simpleName = companionName,
                owner = outerQualified,
                ownerIsClass = true,
                receiver = null,
                parameters = emptyList(),
                returnType = KotlinTypeModel(
                    qualifiedName = typeName,
                    isNullable = false,
                    arguments = emptyList(),
                    valueClass = null,
                ),
                isComposable = false,
                isSuspend = false,
                kind = STATIC_GETTER_KIND,
                bindingName = companionQualified,
                returnBoundaryTag = boundary.tag,
                returnSupertypes = supertypes,
                isCompanionHandle = true,
            ),
            isCompanionHandle = true,
        )
    }

    private fun staticPropertyCandidateOrNull(
        property: AbiProperty,
        boundOwner: String,
        callOwner: String,
        ownerIsClass: Boolean,
        classes: Map<String, AbiClass>,
    ): Candidate? {
        val propName = property.leafName()
        if (!ArtifactScanner.isWritableParameterName(propName)) return null
        val getter = property.getter ?: return null
        val returnModel = typeModelOf(getter.returnType) ?: return null
        if (property.hasAnnotation(DEPRECATED) || getter.hasAnnotation(DEPRECATED)) return null
        if (property.hasAnnotation(PUBLISHED_API) || getter.hasAnnotation(PUBLISHED_API)) return null
        if (property.hasAnnotation(COMPOSABLE) || getter.hasAnnotation(COMPOSABLE)) return null
        if (getter.hasExtensionReceiverParameter || getter.contextReceiverParametersCount > 0) return null
        if (getter.isSuspend || getter.typeParameters.isNotEmpty()) return null

        val returnType = klibBoundaryTypeOf(returnModel, classes) ?: return null
        val name = "$boundOwner.$propName"
        val call = "$callOwner.$propName"
        val supertypes = if (returnType.tag == OBJECT_TAG) supertypesOf(returnModel.qualifiedName, classes) else emptyList()
        return Candidate(
            callable = ArtifactCallable(
                name = name,
                arity = 0,
                paramTags = emptyList(),
                returnTag = returnType.tag,
                lambdaBody = "{ ${returnType.wrapReturn(call)} }",
                returnTypeName = returnModel.qualifiedName,
                returnSupertypes = supertypes,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                paramHasDefault = emptyList(),
                kind = STATIC_GETTER_KIND,
            ),
            declaration = DeclarationModel(
                simpleName = propName,
                owner = boundOwner,
                ownerIsClass = ownerIsClass,
                receiver = null,
                parameters = emptyList(),
                returnType = returnModel,
                isComposable = false,
                isSuspend = false,
                kind = STATIC_GETTER_KIND,
                bindingName = name,
                returnBoundaryTag = returnType.tag,
                returnSupertypes = supertypes,
            ),
        )
    }

    private fun objectFunctionCandidateOrNull(
        function: AbiFunction,
        owner: String,
        callOwner: String,
        classes: Map<String, AbiClass>,
    ): Candidate? {
        val name = function.leafName()
        val model = declarationModelOf(function)?.copy(
            simpleName = name,
            owner = owner,
            ownerIsClass = true,
            receiver = null,
            kind = "FUNCTION",
        ) ?: return null
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }

        if (function.isSuspend) return declined("suspend")
        if (function.hasAnnotation(DEPRECATED)) {
            return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
        }
        if (function.hasAnnotation(PUBLISHED_API)) {
            return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
        }
        if (function.hasAnnotation(COMPOSABLE)) {
            return declined("a @Composable object function: not bound from a klib yet")
        }
        if (function.hasExtensionReceiverParameter) {
            return declined("an extension receiver whose type kotlin-compiler-embeddable 2.0.20's klib ABI does not expose")
        }
        if (function.contextReceiverParametersCount > 0) {
            return declined("${function.contextReceiverParametersCount} context receiver(s), whose types this klib ABI does not expose")
        }
        if (name == "copy" || DATA_COMPONENT.matches(name)) {
            return declined("a data-class synthetic (copy/componentN), or a member this klib ABI cannot tell from one")
        }

        val returnType = klibBoundaryTypeOf(model.returnType, classes)
            ?: return declined("no boundary type for return ${model.returnType.render()}")
        val boundaryTypes = model.parameters.map { parameter ->
            klibBoundaryTypeOf(parameter.type, classes)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        if (boundaryTypes.any { it.isReturnOnly }) {
            return declined("a parameter of a return-only boundary type")
        }

        val key = "$owner.$name"
        val argumentExpressions = boundaryTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val body = returnType.wrapReturn("$callOwner.$name(${argumentExpressions.joinToString(", ")})")
        val arity = boundaryTypes.size
        val supertypes = if (returnType.tag == OBJECT_TAG) supertypesOf(model.returnType.qualifiedName, classes) else emptyList()

        val callable = ArtifactCallable(
            name = key,
            arity = arity,
            paramTags = boundaryTypes.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ $body }" else "{ args -> $body }",
            paramTypeNames = model.parameters.map { it.type.qualifiedName },
            returnTypeName = model.returnType.qualifiedName,
            returnSupertypes = supertypes,
            paramHasDefault = List(arity) { false },
        )
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = key,
                returnBoundaryTag = returnType.tag,
                returnSupertypes = supertypes,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(boundaryTag = boundaryTypes[index].tag, declaresDefault = false)
                },
            ),
        )
    }

    /**
     * Member properties (`GETTER` and, for a `var`, `SETTER`) of a klib class, interface, or enum
     * (issue #191 Phase B, mirroring #38 on the jar side).
     */
    internal fun memberPropertyCandidatesOf(
        cls: AbiClass,
        ownClasses: Map<String, AbiClass>,
        classes: Map<String, AbiClass>,
    ): List<Candidate> {
        if (cls.kind !in PROPERTY_OWNER_KINDS) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()
        val owner = cls.qualifiedName.dotted()
        val ownerType = KotlinTypeModel(owner)
        val generic = cls.typeParameters.isNotEmpty()
        if (!generic && handleClassOrNull(ownerType, classes) == null) return emptyList()

        val unbindable = when {
            generic -> "a member of a generic class: not bound yet"
            cls.kind == AbiClassKind.CLASS && cls.modality != AbiModality.FINAL ->
                "a member of a non-final class: this klib ABI does not say whether it is protected"
            else -> null
        }
        val enum = cls.kind == AbiClassKind.ENUM_CLASS
        return cls.declarations.filterIsInstance<AbiProperty>()
            .filter { property -> !(enum && property.leafName() in ENUM_STATICS) }
            .flatMap { property ->
                memberPropertyCandidatesFor(property, owner, ownerType, unbindable, classes)
            }
    }

    private fun memberPropertyCandidatesFor(
        property: AbiProperty,
        owner: String,
        ownerType: KotlinTypeModel,
        unbindable: String?,
        classes: Map<String, AbiClass>,
    ): List<Candidate> {
        val propName = property.leafName()
        if (!ArtifactScanner.isWritableParameterName(propName)) return emptyList()
        val getter = property.getter ?: return emptyList()
        val typeModel = typeModelOf(getter.returnType) ?: return emptyList()
        val getterModel = DeclarationModel(
            simpleName = propName,
            owner = owner,
            ownerIsClass = true,
            receiver = ownerType,
            receiverBoundaryTag = OBJECT_TAG,
            parameters = emptyList(),
            returnType = typeModel,
            kind = ArtifactScanner.GETTER_KIND,
        )
        fun declined(reason: String) = listOf(Candidate(null, getterModel.copy(declineReason = reason)))

        when {
            unbindable != null -> return declined(unbindable)
            property.hasAnnotation(DEPRECATED) || getter.hasAnnotation(DEPRECATED) ->
                return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
            property.hasAnnotation(PUBLISHED_API) || getter.hasAnnotation(PUBLISHED_API) ->
                return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
            property.hasAnnotation(COMPOSABLE) || getter.hasAnnotation(COMPOSABLE) ->
                return declined("a @Composable getter: its value exists only inside a composition")
            getter.hasExtensionReceiverParameter ->
                return declined("a member extension: it needs two receivers")
            getter.contextReceiverParametersCount > 0 ->
                return declined("${getter.contextReceiverParametersCount} context receiver(s), whose types this klib ABI does not expose")
        }

        val returns = klibBoundaryTypeOf(typeModel, classes)
            ?: return declined("no boundary type for property type ${typeModel.render()}")
        val key = "$owner.$propName"
        val readExpr = "(args[0] as $owner).${ArtifactScanner.writeParameterName(propName)}"
        val supertypes = if (returns.tag == OBJECT_TAG) supertypesOf(typeModel.qualifiedName, classes) else emptyList()
        val getterCandidate = Candidate(
            callable = ArtifactCallable(
                name = key,
                arity = 0,
                paramTags = emptyList(),
                returnTag = returns.tag,
                lambdaBody = "{ args -> ${returns.wrapReturn(readExpr)} }",
                receiverTypeName = owner,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                returnTypeName = typeModel.qualifiedName,
                returnSupertypes = supertypes,
                paramHasDefault = emptyList(),
                kind = ArtifactScanner.GETTER_KIND,
            ),
            declaration = getterModel.copy(
                bindingName = key,
                returnBoundaryTag = returns.tag,
                returnSupertypes = supertypes,
            ),
        )

        val setter = property.setter
        val setterCandidate = if (
            setter != null &&
            !setter.hasAnnotation(DEPRECATED) &&
            !setter.hasAnnotation(PUBLISHED_API) &&
            !setter.hasAnnotation(COMPOSABLE)
        ) {
            val valueBoundary = klibBoundaryTypeOf(typeModel, classes)?.takeIf { !it.isReturnOnly }
            if (valueBoundary != null) {
                val setterKey = "$key="
                Candidate(
                    callable = ArtifactCallable(
                        name = setterKey,
                        arity = 1,
                        paramTags = listOf(valueBoundary.tag),
                        returnTag = "UNIT",
                        lambdaBody = "{ args -> $readExpr = ${valueBoundary.read("args[1]")}; Unit }",
                        receiverTypeName = owner,
                        paramNames = listOf("value"),
                        paramTypeNames = listOf(typeModel.qualifiedName),
                        returnTypeName = "kotlin.Unit",
                        paramHasDefault = listOf(false),
                        kind = ArtifactScanner.SETTER_KIND,
                    ),
                    declaration = getterModel.copy(
                        kind = ArtifactScanner.SETTER_KIND,
                        bindingName = setterKey,
                        parameters = listOf(DeclaredParameter("value", typeModel, declaresDefault = false, boundaryTag = valueBoundary.tag)),
                        returnType = KotlinTypeModel("kotlin.Unit"),
                        returnBoundaryTag = "UNIT",
                    ),
                )
            } else {
                null
            }
        } else {
            null
        }

        return listOfNotNull(getterCandidate, setterCandidate)
    }

    /**
     * The member functions of [cls] (issue #237), as `METHOD` entries `Owner.name` -- the shape #215
     * gives a jar's members and KSP gives its own: `args[0]` is the receiver, not counted in `arity`,
     * and the generated call is `(args[0] as Owner).name(...)`. The binding layer serves them as
     * methods of the receiver's proxy, and never as a module attribute.
     *
     * The class must be able to cross as a handle ([handleClassOrNull]); a generic one's members are
     * declined, as on the jar side. A member is declined, with its reason, when it is `suspend`,
     * `@Deprecated`, `@PublishedApi`, `@Composable`, a member extension or one with context receivers,
     * named like a property of its class (the property's name is the attribute), or typed by something
     * without a klib boundary type. Constructors are handled by [constructorCandidatesOf], and
     * `object` functions by [objectCandidatesOf].
     */
    private fun memberCandidatesOf(
        cls: AbiClass,
        ownClasses: Map<String, AbiClass>,
        classes: Map<String, AbiClass>,
    ): List<Candidate> {
        if (cls.kind == AbiClassKind.OBJECT || cls.kind == AbiClassKind.ANNOTATION_CLASS) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()
        val owner = cls.qualifiedName.dotted()
        val ownerType = KotlinTypeModel(owner)
        val generic = cls.typeParameters.isNotEmpty()
        if (!generic && handleClassOrNull(ownerType, classes) == null) return emptyList()
        val propertyNames = cls.declarations.filterIsInstance<AbiProperty>()
            .mapTo(HashSet()) { it.leafName() }
        // The pinned compiler's klib ABI records modality but no visibility, and lists the `protected`
        // members of an open, abstract or sealed class beside its public ones; source outside a subclass
        // cannot call a `protected` one. An interface has none, and a final class's are effectively
        // private, so only those members bind.
        val unbindable = when {
            cls.kind == AbiClassKind.CLASS && cls.modality != AbiModality.FINAL ->
                "a member of a non-final class: this klib ABI does not say whether it is protected"
            else -> null
        }
        val enum = cls.kind == AbiClassKind.ENUM_CLASS
        return cls.declarations.filterIsInstance<AbiFunction>()
            .filter { !it.isConstructor }
            // `Any`'s members are not the class's API, as on the jar side; an enum class's
            // `valueOf`/`values`/`entries` are statics the ABI lists among its members, and no
            // instance call reaches them.
            .filter { function -> !isAnyMember(function) && !(enum && function.leafName() in ENUM_STATICS) }
            .mapNotNull { function -> memberCandidateOrNull(function, owner, ownerType, generic, unbindable, propertyNames, classes) }
    }

    private fun memberCandidateOrNull(
        function: AbiFunction,
        owner: String,
        ownerType: KotlinTypeModel,
        generic: Boolean,
        unbindable: String?,
        propertyNames: Set<String>,
        classes: Map<String, AbiClass>,
    ): Candidate? {
        val name = function.leafName()
        val model = declarationModelOf(function)?.copy(
            simpleName = name,
            owner = owner,
            ownerIsClass = true,
            receiver = ownerType,
            receiverBoundaryTag = OBJECT_TAG,
            kind = ArtifactScanner.METHOD_KIND,
        ) ?: return null
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }
        when {
            function.isSuspend -> return declined("suspend")
            generic -> return declined("a member of a generic class: not bound yet")
            unbindable != null -> return declined(unbindable)
            function.hasAnnotation(DEPRECATED) ->
                return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
            function.hasAnnotation(PUBLISHED_API) ->
                return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
            function.hasAnnotation(COMPOSABLE) -> return declined("a @Composable member function: not bound on a proxy")
            function.hasExtensionReceiverParameter -> return declined("a member extension: it needs two receivers")
            function.contextReceiverParametersCount > 0 ->
                return declined("${function.contextReceiverParametersCount} context receiver(s), whose types this klib ABI does not expose")
            name in propertyNames -> return declined("shares its name with a property of the class, which keeps the attribute")
            // A data class's `copy`/`componentN` are synthetics the jar side never binds (U-4), and this
            // ABI cannot tell them from a hand-written member of the same name.
            name == "copy" || DATA_COMPONENT.matches(name) ->
                return declined("a data-class synthetic (copy/componentN), or a member this klib ABI cannot tell from one")
        }
        val returnType = klibBoundaryTypeOf(model.returnType, classes)
            ?: return declined("no boundary type for return ${model.returnType.render()}")
        val boundaryTypes = model.parameters.map { parameter ->
            klibBoundaryTypeOf(parameter.type, classes)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        if (boundaryTypes.any { it.isReturnOnly }) return declined("a parameter of a return-only boundary type")

        val key = "$owner.$name"
        val argumentExpressions = boundaryTypes.mapIndexed { index, type -> type.read("args[${index + 1}]") }
        val body = returnType.wrapReturn("(args[0] as $owner).$name(${argumentExpressions.joinToString(", ")})")
        val supertypes = if (returnType.tag == OBJECT_TAG) supertypesOf(model.returnType.qualifiedName, classes) else emptyList()
        val callable = ArtifactCallable(
            name = key,
            arity = boundaryTypes.size,
            paramTags = boundaryTypes.map { it.tag },
            returnTag = returnType.tag,
            // The receiver is read even with no declared parameter, so the body always takes `args`.
            lambdaBody = "{ args -> $body }",
            receiverTypeName = owner,
            paramTypeNames = model.parameters.map { it.type.qualifiedName },
            returnTypeName = model.returnType.qualifiedName,
            returnSupertypes = supertypes,
            // Every argument is passed, as for a top-level klib entry (see [candidateOrNull]).
            paramHasDefault = List(boundaryTypes.size) { false },
            kind = ArtifactScanner.METHOD_KIND,
        )
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = key,
                returnBoundaryTag = returnType.tag,
                returnSupertypes = supertypes,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(boundaryTag = boundaryTypes[index].tag, declaresDefault = false)
                },
            ),
        )
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
        val dotted = qualifiedName.dotted()
        return includePrefixes.any {
            pkg == it || pkg.startsWith("$it.") || dotted == it || dotted.startsWith("$it.")
        }
    }

    /**
     * Every class the klib declares, nested ones included, by its dotted Kotlin name -- what
     * [klibBoundaryTypeOf] asks before it lets a type cross as a handle (issue #234).
     */
    internal fun classesOf(container: AbiDeclarationContainer): Map<String, AbiClass> {
        val found = LinkedHashMap<String, AbiClass>()
        fun walk(current: AbiDeclarationContainer) {
            current.declarations.filterIsInstance<AbiClass>().forEach { cls ->
                found[cls.qualifiedName.dotted()] = cls
                walk(cls)
            }
        }
        walk(container)
        return found
    }

    /**
     * @param classes the walked klib's and its classpath's classes ([classesOf]): a parameter or result
     *   whose class is one of them, and qualifies ([handleClassOrNull]), crosses as an object handle.
     */
    internal fun candidateOrNull(function: AbiFunction, classes: Map<String, AbiClass> = emptyMap()): Candidate? {
        val model = declarationModelOf(function) ?: return null
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }

        // A `suspend` function's Kotlin/Native shape is not one this walker's generated call
        // expression can supply (it needs a `Continuation`), the same reason the JVM producer
        // declines it -- but a klib says so directly rather than requiring the JVM producer's
        // inference from a transformed descriptor.
        if (function.isSuspend) return declined("suspend")
        // See PUBLISHED_API's KDoc: `@PublishedApi internal` is binary-visible but not
        // source-visible, and the generated call expression is source in the consumer's own module.
        // A `@Deprecated(level = HIDDEN)` declaration stays in a klib for binary compatibility
        // (`kotlinx.coroutines.Job0`) but cannot be called from source, and this ABI exposes the
        // annotation but not its level (issue #234), so every deprecated declaration is declined.
        if (function.hasAnnotation(DEPRECATED)) {
            return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
        }
        if (function.hasAnnotation(PUBLISHED_API)) {
            return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
        }
        if (function.hasAnnotation(COMPOSABLE)) {
            return declined("a @Composable top-level function: not bound from a klib yet")
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

        val returnType = klibBoundaryTypeOf(model.returnType, classes)
            ?: return declined("no boundary type for return ${model.returnType.render()}")
        val boundaryTypes = model.parameters.map { parameter ->
            klibBoundaryTypeOf(parameter.type, classes)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        if (boundaryTypes.any { it.isReturnOnly }) {
            return declined("a parameter of a return-only boundary type")
        }

        val qualifiedName = function.qualifiedName.dotted()
        val argumentExpressions = boundaryTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val body = returnType.wrapReturn("$qualifiedName(${argumentExpressions.joinToString(", ")})")
        val arity = boundaryTypes.size
        val supertypes = if (returnType.tag == OBJECT_TAG) supertypesOf(model.returnType.qualifiedName, classes) else emptyList()

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
            // What a handle result **is a** (issue #234), read off the klib's own supertypes, nearest
            // first: `_member` and `_is_a` read it, so a `CompletableJob` serves `Job`'s members and fills
            // a `Job` slot. Empty for a non-handle result, as on the jar side.
            returnSupertypes = supertypes,
            // Deliberately all `false`, not `model.parameters.map { it.declaresDefault }`.
            // `ArtifactCallable.paramHasDefault`'s contract (see its KDoc) is a statement about
            // [lambdaBody] -- exactly the slots the generated call may omit -- and never about the
            // declaration. `lambdaBody` above passes every argument unconditionally; this scanner has
            // no `ArtifactScanner.applyDefaultOmission` pass to earn a narrower answer, because a
            // klib walk runs on its own in [KlibScanWorkAction] and never sees the whole-artifact
            // overload picture that pass needs.
            paramHasDefault = List(arity) { false },
        )
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnType.tag,
                returnSupertypes = supertypes,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(
                        boundaryTag = boundaryTypes[index].tag,
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
     * throw inside the cast instead of reaching the declaration.
     */
    private fun klibBoundaryTypeOf(type: KotlinTypeModel, classes: Map<String, AbiClass> = emptyMap()): BoundaryType? {
        // Issue #234: a class of the walked klib (or its klib classpath) crosses as an object handle --
        // the jar producer's `(%s as Type)` read and an unwrapped result, which the common trampoline
        // turns into a `HandleTable` handle and back. A nullable one carries the `?`: the handle is
        // already nullable at the boundary (`UpcallTrampoline.toKotlin` maps Python `None` to `null`).
        if (!type.isNullable) primitiveBoundaryTypeOf(type.qualifiedName)?.let { return it }
        if (handleClassOrNull(type, classes) != null) {
            val cast = type.qualifiedName + if (type.isNullable) "?" else ""
            return BoundaryType(OBJECT_TAG, "(%s as $cast)", "(%s)")
        }
        return null
    }

    private fun primitiveBoundaryTypeOf(qualifiedName: String): BoundaryType? {
        return when (qualifiedName) {
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

    private const val OBJECT_TAG = "OBJECT"

    private const val STATIC_GETTER_KIND = "STATIC_GETTER"

    private val ENUM_STATICS = setOf("valueOf", "values", "entries")

    private val DATA_COMPONENT = Regex("component[1-9][0-9]*")

    private val PROPERTY_OWNER_KINDS = setOf(AbiClassKind.CLASS, AbiClassKind.INTERFACE, AbiClassKind.ENUM_CLASS)

    private fun AbiFunction.leafName(): String = qualifiedName.relativeName.toString().substringAfterLast('.')

    private fun AbiProperty.leafName(): String = qualifiedName.relativeName.toString().substringAfterLast('.')

    /** `toString()`, `hashCode()` and `equals(other)`: what every class has from `Any`. */
    private fun isAnyMember(function: AbiFunction): Boolean = when (function.leafName()) {
        "toString", "hashCode" -> function.valueParameters.isEmpty()
        "equals" -> function.valueParameters.size == 1
        else -> false
    }

    /**
     * The class [type] names in [classes], when a handle of it can cross (issue #234, extended to
     * cross-klib `classpath` in #191 Phase B): a public `class`, `interface`, `enum class` or `object`
     * that is not a value class, has no type parameters, is used without type arguments, and is not
     * `@PublishedApi internal` or `@Deprecated`.
     */
    private fun handleClassOrNull(type: KotlinTypeModel, classes: Map<String, AbiClass>): AbiClass? {
        if (type.arguments.isNotEmpty()) return null
        // `kotlin.Int`, `kotlin.String` and the rest of package `kotlin` are classes of the stdlib klib,
        // and the boundary's own currency, never handles.
        if (type.qualifiedName.substringBeforeLast('.') == "kotlin") return null
        val cls = classes[type.qualifiedName] ?: return null
        if (cls.kind !in HANDLE_CLASS_KINDS || cls.isValue || cls.typeParameters.isNotEmpty()) return null
        if (cls.hasAnnotation(PUBLISHED_API)) return null
        return cls
    }

    private val HANDLE_CLASS_KINDS = setOf(AbiClassKind.CLASS, AbiClassKind.INTERFACE, AbiClassKind.ENUM_CLASS, AbiClassKind.OBJECT)

    /** [qualifiedName]'s supertypes, nearest first and transitive across [classes], `kotlin.Any` left out. */
    private fun supertypesOf(qualifiedName: String, classes: Map<String, AbiClass>): List<String> {
        val ordered = LinkedHashSet<String>()
        var frontier = listOf(qualifiedName)
        repeat(16) {
            val next = frontier.flatMap { name ->
                classes[name]?.superTypes.orEmpty().mapNotNull { superType ->
                    ((superType as? AbiType.Simple)?.classifierReference as? AbiClassifierReference.ClassReference)
                        ?.className?.dotted()
                }
            }.filter { it != "kotlin.Any" && it != qualifiedName && ordered.add(it) }
            if (next.isEmpty()) return ordered.toList()
            frontier = next
        }
        return ordered.toList()
    }
}
