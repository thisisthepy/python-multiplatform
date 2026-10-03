package python.multiplatform.gradle.artifact

import kotlin.metadata.KmClassifier
import kotlin.metadata.KmType
import kotlin.metadata.KmVariance
import kotlin.metadata.Visibility
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.isValue
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.visibility
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import java.io.File
import java.util.jar.JarFile

/**
 * `docs/design/ecosystem.md` §5b's second producer: bindings taken out of the artefacts a build resolves,
 * rather than out of the source it compiles.
 *
 * ### Why this exists at all
 *
 * KSP only sees declarations being compiled, so a third-party binary -- `androidx.compose.material3`
 * being the case that motivated it -- can never have a fragment. `PyREPL` already solved the reading
 * half of this: `app/build.gradle.kts` makes a resolvable copy of each source set's
 * `implementation`/`api` configurations and walks the resulting jars with ASM. This is that walk,
 * pointed at a table instead of at `.pyi` stubs.
 *
 * ### Where this parts company with PyREPL's filters
 *
 * PyREPL drops private and protected members, `<init>`, `Companion`, and any name containing `-`.
 * Judged against what a *binding* needs rather than what a stub needs:
 *
 * | PyREPL's filter | here | why |
 * |---|---|---|
 * | not `private`/`protected` | **not enough** — must be `public`, and Kotlin-`public` rather than only JVM-`public` | PyREPL keeps package-private members, which is harmless in a stub; `internal` is JVM-public but not a name generated Kotlin may call |
 * | drop `<init>` | kept | a constructor needs a `ReflectedClass` and a receiver handle, which is the next step |
 * | drop `Companion` | subsumed | only statics are bound, and `Companion` is an instance field |
 * | drop names containing `-` | **not kept** | see below |
 * | (none) | drop non-`static` | an instance method needs a receiver; see [ArtifactCallable] |
 * | (none) | drop unbindable types | see [boundaryTypeOf] and `resolveKotlinType` |
 * | (none) | **rename** ambiguous overloads rather than drop them | see [disambiguateOverloads] |
 * | (none) | drop `suspend` | its JVM shape takes a `Continuation`, and `resolveKotlinType`/`@Metadata` both know this before `boundaryTypeOf` would have to discover it by rejecting the parameter |
 *
 * **Why the `-` filter is gone rather than kept.** A hyphen in a JVM method name is Kotlin's
 * value-class mangling suffix, and it was dropped on the reasoning that a value class *erases to the
 * type it wraps*: `Duration` is a `long`, so `getInWholeSeconds-impl(J)J` would pass [boundaryTypeOf]'s
 * type filter while meaning something entirely different from `long -> long`. That reasoning is sound
 * only if the walker calls the JVM method by its mangled name -- which it never does. It generates
 * *Kotlin source* (`renderArtifactFragmentSource`) and lets `kotlinc` compile it, the same as a
 * human would write `Modifier.padding(16.dp)`; mangling is something the compiler does on the way
 * out, not a name this walker's own generated code ever spells. Filtering on it discarded the
 * declaration a value class exists to describe correctly, for a reason that only applied to a
 * different (rejected) design.
 *
 * What replaced it: `resolveKotlinType` (`KotlinMetadata.kt`), which reads `@Metadata`'s `d1`/`d2`
 * payload to learn the *Kotlin* type a parameter or return actually has -- `Meters`, not the `Double`
 * it erases to -- and, when that type is a value class, whether its constructor and its accessor are
 * public enough to construct and unwrap from generated code outside the module.
 * `kotlin.time.Duration` (`getInWholeSeconds-impl`, the case that motivated the old filter) is
 * *still* declined under the new rule, but for a fact rather than a heuristic: `Duration`'s
 * constructor is `internal`, so no Kotlin source outside `kotlin-stdlib` can ever build one, in
 * either direction. See `ValueClassFixtures.kt`'s `Meters` for the same shape with a public
 * constructor, and `ArtifactScannerTest` for both proved against real bytecode.
 *
 * ### What it takes to reach Compose, and what each piece was worth
 *
 * `docs/design/kotlin-extensions-in-python.md` §3 measured this walker binding **zero** declarations from
 * all 19 Compose Multiplatform desktop jars, and identified three separate rules each of which
 * produced that zero on its own. All three are now gone, and the measurement that replaced it --
 * `ArtifactScannerTest.composeModifierExtensionsSurviveBothGates`, over the same jars -- is
 * **788 declarations, 157 of them `Modifier` extensions** (314/104 when the three rules below were
 * the whole story; composables and then function-typed slots account for the rest):
 *
 * | rule | what it cost | where it went |
 * |---|---|---|
 * | the metadata-kind gate: only `k=1` (an ordinary class) was dispatched on | **all 1,411** public top-level Compose functions, because a Kotlin top-level function compiles into a file facade (`k=2`) or a multi-file part (`k=5`) | the `when` in [scanClassNode], which now dispatches on `FileFacade` and `MultiFileClassFacade` too |
 * | the type gate: no boundary type for an ordinary object | every remaining declaration, `Modifier` being an interface | `resolveKotlinType`'s object-handle case (`KotlinMetadata.kt`) |
 * | drop a name carried by more than one binding | 33 of `Modifier`'s 130 names, `padding`/`size`/`background`/`border`/`clickable` among them | [disambiguateOverloads] |
 *
 * A fourth rule cost most of what was left. Of the 46 public top-level `Modifier` extensions still
 * declined, **44 declared a function-typed parameter**, because `resolveKotlinType` looks a
 * classifier up as a public class file and Kotlin's built-in `FunctionN` has none. That is
 * [functionSlotOrNull]'s subject, and the same test now measures **157 `Modifier` extensions bound
 * and 8 declined, 6 of them function-typed** -- `clickable`, `combinedClickable`, `semantics`,
 * `drawBehind`, `toggleable` and 45 others where there were none.
 *
 * What is left is not a metadata problem either, and each remaining case has a reason rather than an
 * absence: a `suspend` lambda (`pointerInput`), a `@Composable` lambda inside a declaration that is
 * not one (`composed`), a type argument that is a type *parameter* (`swipeable`). See
 * [functionSlotOrNull].
 *
 * ### Facades, parts, and extension receivers
 *
 * Compose's public surface is not reached by widening [boundaryTypeOf] alone. Two more ASM-only
 * limits stood in the way, and `KotlinMetadata.kt` is what removes them:
 *
 * 1. **Kotlin top-level functions hide behind a facade.** `kotlin.text.trimIndent` compiles onto
 *    the package-private `StringsKt__IndentKt`; the public class `StringsKt` (a
 *    `MULTI_FILE_CLASS_FACADE`) declares no methods of its own, only a list of part class names.
 *    ASM alone can see that the facade exists and that it is public, and nothing past that --
 *    `ArtifactScannerTest.theWholeJUnitJarYieldsExactlyTheseDeclarations`'s Kotlin-jar counterpart
 *    used to pin exactly this limit. `KotlinClassMetadata.MultiFileClassFacade.partClassNames`
 *    supplies the part names; each part's own `@Metadata` (`MultiFileClassPart.kmPackage`) supplies
 *    the real declarations and their true (unmangled) Kotlin names.
 * 2. **An extension receiver is invisible in a JVM descriptor.** `fun Modifier.padding(Dp): Modifier`
 *    compiles to a static method whose *first* JVM parameter is the receiver, indistinguishable by
 *    descriptor alone from an ordinary parameter of the same erased type. `KmFunction
 *    .receiverParameterType` is the only place that survives. Kotlin has no fully-qualified call
 *    syntax for an extension (see [ArtifactCallable.imports]'s KDoc), so a bound extension is called
 *    `receiver.alias(...)` after an `import owner.name as alias` -- the only Kotlin can address it.
 *
 * ### What is still declined, on purpose
 *
 * A value-class *receiver* on a true member of that class (not an extension declared elsewhere) --
 * `Duration.inWholeSeconds` is one -- is declined by [resolveKotlinType] refusing an unreadable
 * constructor, but even a value class with a public one would still be declined here: a member
 * function's own JVM shape puts the (unboxed) instance in JVM parameter position 0 with **no**
 * declared Kotlin parameter to account for it, which the arity check in [scanClassNode] catches as a
 * mismatch. Only a class's plain statics (`@JvmStatic`, a Java `static`) and package-level top-level
 * declarations are bound; instance dispatch is `docs/design/ecosystem.md` §5b's next step, same as before.
 * `suspend` is declined explicitly via `@Metadata` rather than relying on [boundaryTypeOf] rejecting
 * its `Continuation` parameter, per AGENTS.md's "제외한 것을 조용히 빠뜨리지 마라".
 */
/**
 * One declaration, seen by both consumers of a walk at the moment it is read.
 *
 * [callable] is `null` exactly when the binder declined it, and [declaration] says why. Keeping them
 * together up to the end of the walk is what makes `docs/design/pyi-generation-design.md` §2.2's "the two
 * renderers cannot drift" a property of the code rather than a convention.
 *
 * **Top-level, not nested in [ArtifactScanner], because there are two producers.** [KlibScanner]
 * builds these too and hands them to [ArtifactScanner.disambiguateOverloads], so that the `name__<types>`
 * spelling has exactly one implementation: both producers' output lands in one
 * `python.multiplatform.generated.artifacts.ArtifactTable`, and a consumer that had to learn two
 * meanings for `__` depending on which walker found the declaration would be reading a table that is
 * only accidentally consistent. It is also what gives a klib declaration a
 * [DeclarationModel] and therefore a `.pyi` stub.
 */
internal data class Candidate(
    val callable: ArtifactCallable?,
    val declaration: DeclarationModel,
    /**
     * Set only by [ArtifactScanner]'s own Kotlin path, and only until
     * [ArtifactScanner.applyDefaultOmission] has consumed it.
     *
     * Whether a defaulted argument may be left out of a generated call is not decidable where the
     * call is built -- see [ArtifactScanner.DefaultOmissionPlan] -- so the body is built twice: once
     * passing everything, and again once the whole walk is visible. A candidate that reaches
     * [ArtifactScanner.disambiguateOverloads] still carrying one has not been through that pass;
     * that constructor drops it, which is correct, because by then the body is settled.
     */
    val plan: ArtifactScanner.DefaultOmissionPlan? = null,
    val isConstructor: Boolean = false,
)

internal object ArtifactScanner {

    /** `kotlin.Metadata` is `RUNTIME`-retained, so ASM reads it as an ordinary visible annotation --
     * no metadata library needed for the *kind*, only for the payload. */
    private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

    /** `k = 1`: an ordinary class, object or interface. Every other kind is a compiler-invented
     * carrier -- 2 file facade, 3 synthetic class, 4 multi-file facade, 5 multi-file part -- whose
     * JVM name has no Kotlin spelling. Used only by [metadataKinds], which reads just the kind byte
     * without decoding the rest of the payload; [scanClassNode] dispatches on the full
     * `KotlinClassMetadata` instead. */
    private const val KOTLIN_KIND_CLASS = 1

    /** What `kotlin.Any` gives every object and an `object` may override; not part of its API. */
    private val ANY_MEMBER_SIGNATURES = setOf(
        "toString" to "()Ljava/lang/String;",
        "hashCode" to "()I",
        "equals" to "(Ljava/lang/Object;)Z",
    )

    /**
     * Every binding this jar (or, for a test fixture, this directory of `.class` files -- see
     * `ArtifactClasspath`) offers, sorted by name.
     *
     * @param includePrefixes package or class names, each matched as a *namespace*: `junit.runner`
     *   covers `junit.runner.Version` but `junit.run` covers nothing. Empty means every class,
     *   which is what the type filter makes affordable.
     * @param classpath every root a value-class parameter or an extension receiver might need to be
     *   resolved against, [target] included. Defaults to `[target]` alone, which is correct whenever
     *   a value class used by [target] is also declared in [target] -- true for this walker's own
     *   test fixtures, false for `androidx.compose.foundation.layout`, whose `Dp` lives in
     *   `androidx.compose.ui.unit`. [PythonArtifactBindingsTask] passes the whole resolved
     *   configuration.
     */
    fun scanJar(target: File, includePrefixes: List<String> = emptyList(), classpath: List<File> = listOf(target)): List<ArtifactCallable> =
        scan(target, includePrefixes, classpath).mapNotNull { it.callable }.sortedBy { it.name }

    /**
     * The same walk, with the half `docs/design/pyi-generation-design.md` §2.2 asks for kept: one
     * [DeclarationModel] per public declaration this walker considered, **including the ones it
     * declined**, each carrying the table key the binder gave it or the reason there is none.
     *
     * Not a second scan. §2.2 rejects "two readers of the same jars that can disagree", so every
     * model here is built at the point its binding is built, from the same `ResolvedFunction`; the
     * two results of one walk are separated only at the end. `DeclarationModelTest
     * .theModelAndTheBindingsAgreeBecauseTheyComeFromTheSameWalk` pins that they cannot drift.
     */
    fun scanDeclarations(
        target: File,
        includePrefixes: List<String> = emptyList(),
        classpath: List<File> = listOf(target),
    ): List<DeclarationModel> = scan(target, includePrefixes, classpath)
        .map { it.declaration }
        .sortedWith(compareBy({ it.owner }, { it.simpleName }, { it.bindingName ?: "" }))

    private fun scan(target: File, includePrefixes: List<String>, classpath: List<File>): List<Candidate> {
        val artifactClasspath = ArtifactClasspath(classpath)
        val entries = mutableListOf<Candidate>()
        forEachClassEntry(target) { relativePath, bytes ->
            // A nested class's binary name uses `$`, which is not how Kotlin spells the qualified
            // name (`Outer.Inner`), and `$1` anonymous classes have no Kotlin name at all. Skipped
            // wholesale, as PyREPL does.
            if ('$' in relativePath) return@forEachClassEntry
            val qualified = binaryNameToQualified(relativePath.removeSuffix(".class"))
            if (!matchesInclude(qualified, includePrefixes)) return@forEachClassEntry
            val node = readClassNodeOrNull(bytes) ?: return@forEachClassEntry
            entries += scanClassNode(node, artifactClasspath)
        }
        // Both passes are over the whole walk rather than per class, and for the same reason: an
        // overload set is a property of a *package*, and Kotlin lets one live in two files.
        // `docs/design/kotlin-extensions-in-python.md` §2.5 counts 11 such pairs in Compose alone -- two
        // file facades, two `ClassNode`s, one Kotlin name -- which a per-class grouping cannot see.
        // It would have emitted them twice under one table key, and it would have generated calls
        // that do not compile (see [applyDefaultOmission]).
        //
        // Defaults first: [disambiguateOverloads] renames entries, and the sibling test needs the
        // Kotlin name they still share.
        return assignThunkIndices(disambiguateOverloads(applyDefaultOmission(dropCollidingConstructors(entries))))
            // The ancestry the binding carries is the model's too (issue #31): copied at the end, from the
            // callable that was actually bound, so the two cannot be read differently.
            .map { candidate ->
                val callable = candidate.callable ?: return@map candidate
                // ... and so is what the binding lets Python leave out. The model's `declaresDefault` is
                // Kotlin's own view until [applyDefaultOmission] narrows it, and that pass returns a
                // candidate untouched when nothing is omittable (past [MAX_OMITTABLE_PARAMETERS], an
                // unwritable name, an ambiguous sibling): the binding then requires every argument
                // while the stub would still offer `= ...`. One view, taken from the binding.
                val omittable = omittableSlotsOf(callable)
                val receiverIndex = if (callable.receiverTypeName != null) 1 else 0
                candidate.copy(
                    declaration = candidate.declaration.copy(
                        returnSupertypes = callable.returnSupertypes,
                        parameters = candidate.declaration.parameters.mapIndexed { index, parameter ->
                            parameter.copy(declaresDefault = omittable.getOrElse(index + receiverIndex) { false })
                        },
                    ),
                )
            }
    }

    /**
     * Two rules, both "a constructor cannot claim a name something else bound needs", and both
     * caught here rather than by restricting *which classes* [constructorCandidates] attempts:
     *
     * 1. **Exact-name collision with a factory function.** A top-level factory function (like
     *    `BitmapPainter`) is already what Python users call. A constructor sharing that exact bare
     *    name would force both into `name__<types>` mangling and break existing Python code, so the
     *    constructor yields.
     * 2. **Prefix collision with a constant the class itself publishes.** [objectConstantCandidates]
     *    binds `TextStyle.Default` under the dotted name `"TextStyle.Default"`, which needs the bare
     *    name `TextStyle` free to act as the namespace that constant hangs off of. A constructor
     *    bound to `TextStyle` claims that name as a callable instead, and `TextStyle.Default` becomes
     *    unreachable. `TextStyle` has this problem and `Typography`/`Shapes` do not, which is the
     *    measured fact this rule generalises (`ArtifactScannerTest
     *    .aFocusRequesterConstructorIsDroppedBecauseItWouldShadowFocusRequesterDefault` pins the rule
     *    itself against a class where it is the one doing the work; `TextStyle` gets closed by the
     *    other check below instead, see [constructorCandidates]) -- replacing what used to be two
     *    hard-coded class names in [constructorCandidates] with a structural check that does not grow
     *    a line per class someone wants opened next.
     *
     * Both must run BEFORE [applyDefaultOmission] so a dropped constructor's presence doesn't cost
     * the surviving bound declaration its omittable defaults.
     */
    private fun dropCollidingConstructors(candidates: List<Candidate>): List<Candidate> {
        val (bound, declined) = candidates.partition { it.callable != null }
        val byName = bound.groupBy { it.callable!!.name }
        val boundNames = byName.keys

        val dropReasons = mutableMapOf<Candidate, String>()
        for (group in byName.values) {
            if (group.any { !it.isConstructor }) {
                group.filter { it.isConstructor }.forEach {
                    dropReasons.putIfAbsent(it, "constructor name collides with a factory function")
                }
            }
        }
        for (candidate in bound) {
            if (!candidate.isConstructor) continue
            val name = candidate.callable!!.name
            if (boundNames.any { it != name && it.startsWith("$name.") }) {
                dropReasons.putIfAbsent(candidate, "constructor name collides with a constant this class publishes under its own name")
            }
        }

        val remainingBound = bound.filter { it !in dropReasons }
        val newlyDeclined = dropReasons.map { (candidate, reason) ->
            candidate.copy(
                callable = null,
                declaration = candidate.declaration.copy(bindingName = null, declineReason = reason),
            )
        }
        return remainingBound + declined + newlyDeclined
    }

    /**
     * The `k` of each named class's `kotlin.Metadata`, or absent from the map when the class carries
     * none. Exposed for `ArtifactScannerTest` to pin against a real Kotlin artefact, since it is the
     * fact that bounds what [scanClassNode] can dispatch on.
     *
     * @param binaryNames internal names, e.g. `kotlin/text/StringsKt`.
     */
    fun metadataKinds(jar: File, binaryNames: List<String>): Map<String, Int> {
        val wanted = binaryNames.toSet()
        val kinds = mutableMapOf<String, Int>()
        JarFile(jar).use { file ->
            wanted.forEach { name ->
                val entry = file.getJarEntry("$name.class") ?: return@forEach
                val node = file.getInputStream(entry).use { readClassNodeOrNull(it.readBytes()) } ?: return@forEach
                kotlinMetadataKind(node)?.let { kinds[name] = it }
            }
        }
        return kinds
    }

    private fun forEachClassEntry(root: File, action: (relativePath: String, bytes: ByteArray) -> Unit) {
        if (root.isDirectory) {
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") }
                .forEach { file -> action(file.relativeTo(root).invariantSeparatorsPath, file.readBytes()) }
        } else {
            JarFile(root).use { jarFile ->
                jarFile.entries().asSequence()
                    .filter { it.name.endsWith(".class") }
                    .forEach { entry -> action(entry.name, jarFile.getInputStream(entry).use { it.readBytes() }) }
            }
        }
    }

    private fun kotlinMetadataKind(node: ClassNode): Int? {
        val annotation = node.visibleAnnotations?.firstOrNull { it.desc == KOTLIN_METADATA_DESCRIPTOR } ?: return null
        val values = annotation.values ?: return KOTLIN_KIND_CLASS
        // `values` is a flat name/value list. `k` is absent when it holds its default, which is 1.
        for (i in 0 until values.size - 1 step 2) {
            if (values[i] == "k") return values[i + 1] as? Int
        }
        return KOTLIN_KIND_CLASS
    }

    private fun binaryNameToQualified(binaryName: String): String = binaryName.replace('/', '.')

    private fun packageNameOf(binaryName: String): String =
        binaryNameToQualified(binaryName.substringBeforeLast('/', missingDelimiterValue = ""))

    /** A namespace match, not a text one: `junit.run` is a prefix of the string `junit.runner` and
     * of no package or class. */
    private fun matchesInclude(qualifiedName: String, includePrefixes: List<String>): Boolean =
        includePrefixes.isEmpty() || includePrefixes.any {
            qualifiedName == it || qualifiedName.startsWith("$it.")
        }

    private fun scanClassNode(node: ClassNode, classpath: ArtifactClasspath): List<Candidate> {
        if (!node.access.hasFlag(Opcodes.ACC_PUBLIC)) return emptyList()
        if (node.access.hasFlag(Opcodes.ACC_SYNTHETIC) or node.access.hasFlag(Opcodes.ACC_ANNOTATION)) return emptyList()

        // Ambiguity is counted over what *would be bound*, over the whole group a facade
        // contributes -- not per class -- so ambiguity introduced by splitting one Kotlin name
        // across two multi-file parts is still caught. See this object's KDoc.
        val candidates: List<Candidate> = when (val metadata = kotlinClassMetadataOf(node)) {
            // No `kotlin.Metadata` at all: a Java class (or a Kotlin class whose metadata payload
            // this library could not decode -- declined the same way unparsable bytecode is, not
            // fatal). Bound the same way this walker always has, by JVM descriptor alone.
            null -> javaStaticCandidates(node)
            is KotlinClassMetadata.Class -> kotlinCandidates(
                owner = binaryNameToQualified(node.name),
                ownerIsClass = true,
                functions = functionsOf(metadata.kmClass).filterNot { it.isExtension },
                ownerNode = node,
                classpath = classpath,
            ) + constructorCandidates(metadata.kmClass, node, classpath) +
                objectConstantCandidates(metadata.kmClass, node, classpath)
            is KotlinClassMetadata.FileFacade -> kotlinCandidates(
                owner = kotlinPackageNameOverrideOf(node) ?: packageNameOf(node.name),
                ownerIsClass = false,
                functions = functionsOf(metadata.kmPackage),
                ownerNode = node,
                classpath = classpath,
            )
            is KotlinClassMetadata.MultiFileClassFacade -> metadata.partClassNames.flatMap { partBinaryName ->
                val partNode = classpath.classNode(partBinaryName) ?: return@flatMap emptyList()
                val partMetadata = kotlinClassMetadataOf(partNode) as? KotlinClassMetadata.MultiFileClassPart
                    ?: return@flatMap emptyList()
                kotlinCandidates(
                    owner = kotlinPackageNameOverrideOf(partNode) ?: packageNameOf(partBinaryName),
                    ownerIsClass = false,
                    functions = functionsOf(partMetadata.kmPackage),
                    ownerNode = partNode,
                    classpath = classpath,
                )
            }
            // A lone part is package-private and never reaches here on its own (the `ACC_PUBLIC`
            // guard above already excluded it) -- only through the facade case, which fetches it by
            // name. `SyntheticClass` and `Unknown` carry no Kotlin declarations this walker binds.
            else -> emptyList()
        }

        return candidates
    }

    /**
     * Gives every member of an overload set a name of its own, instead of dropping the set.
     *
     * ### What changed, and what did not
     *
     * The old rule dropped a Kotlin name outright as soon as more than one binding would carry it.
     * Its reasoning was about **arbitration** and is still right: `org.junit.Assert.assertEquals` has
     * eight bindable overloads, and letting a sort order pick one means `assertEquals(3, 3)` from
     * Python silently calls the deprecated `(double, double)` that always fails. Nothing here picks.
     *
     * What changed is that there is now a third option between "arbitrate" and "drop". The old rule
     * predates `@Metadata`: with only JVM descriptors there was no *Kotlin* parameter type to name an
     * overload by, and 108 mangled JVM names are ambiguous within their own class
     * (`docs/design/kotlin-extensions-in-python.md` §2.3), so the JVM name could not do it either. Metadata
     * supplies the declared Kotlin types, so the overloads can be **told apart** rather than
     * arbitrated between.
     *
     * The price of not doing so was measured: **33 of `Modifier`'s 130 names**
     * (`docs/design/kotlin-extensions-in-python.md` §3.1), and the casualty list is the API's centre of
     * gravity -- `padding`, `size`, `background`, `border`, `clickable`, `width`, `height`. For
     * `padding` specifically, the *only* unmangled overload is the `PaddingValues` one nobody wants,
     * so "keep whichever the descriptor-era filter happened to leave" was also the worst answer.
     *
     * ### The rule
     *
     * The bare name is bound only for a group of one. A group of more than one gets one name per
     * member, `name__<types>`, under the first of three schemes that separates the group:
     *
     * 1. the simple names of the declared **value parameters** -- `padding__Dp`, `padding__Dp_Dp`,
     *    `padding__PaddingValues`;
     * 2. the **receiver** joined to them, for overloads that differ only in what they extend
     *    (`Int.times` and `Double.times`, both in `androidx.compose.ui.unit`);
     * 3. **fully qualified** names, for the case where two parameter types share a simple name.
     *
     * A group no scheme separates is dropped, which is the old rule surviving as the floor: two
     * declarations this walker genuinely cannot tell apart must not both claim a table key, and
     * neither may be picked.
     *
     * ### Why a name and not a Python-side dispatcher
     *
     * A dispatcher is the better surface and it cannot be built here. `UpcallTable` is keyed by name
     * and `ExposedCallable` carries one fixed `arity` that `UpcallTrampoline.unmarshalArguments`
     * enforces exactly, so one name reaches one signature by construction; and
     * `PythonProxySource.renderOne` publishes an entry by `setattr`ing its *leaf* name onto a module,
     * so two entries sharing a leaf would silently overwrite each other. A dispatcher therefore lives
     * in `pythonx` (`docs/archive/pythonx-adapter-design.md` §4.1) and selects among these names -- which is
     * why they have to exist and be distinguishable, and why `ExposedCallable` now carries
     * `paramNames` and `paramTypeNames` for it to select on. This layer's job is to make the choice
     * *possible*, not to make it.
     */
    internal fun disambiguateOverloads(candidates: List<Candidate>): List<Candidate> {
        val schemes: List<(ArtifactCallable) -> String> = listOf(
            { it.overloadSuffix(includeReceiver = false, qualified = false) },
            { it.overloadSuffix(includeReceiver = true, qualified = false) },
            { it.overloadSuffix(includeReceiver = true, qualified = true) },
        )
        // A candidate the binder already declined has no name to be ambiguous about; it passes
        // through so that `docs/design/pyi-generation-design.md` §2.2's "what is declined stays visible"
        // survives this stage too.
        val (bound, declined) = candidates.partition { it.callable != null }

        val disambiguated = bound.groupBy { it.callable!!.name }.values.flatMap { group ->
            if (group.size == 1) return@flatMap group
            val scheme = schemes.firstOrNull { scheme -> group.mapTo(HashSet()) { scheme(it.callable!!) }.size == group.size }
                ?: return@flatMap group.map { candidate ->
                    candidate.copy(
                        callable = null,
                        declaration = candidate.declaration.copy(
                            bindingName = null,
                            declineReason = "one of ${group.size} overloads no naming scheme separates",
                        ),
                    )
                }
            group.map { candidate ->
                val name = "${candidate.callable!!.name}__${scheme(candidate.callable)}"
                candidate.copy(
                    callable = candidate.callable.copy(name = name),
                    declaration = candidate.declaration.copy(bindingName = name),
                )
            }
        }
        return disambiguated + declined
    }

    /**
     * The `__`-suffix for one member of an overload set.
     *
     * The receiver is *not* included by default even though it is `paramTypeNames` slot 0: including
     * it always would spell every `Modifier` extension `padding__Modifier_Dp`, and the receiver is
     * the one parameter a reader already knows from where the name is attached
     * (`docs/design/kotlin-extensions-in-python.md` §4.1). It joins only when it is what separates the
     * group.
     */
    private fun ArtifactCallable.overloadSuffix(includeReceiver: Boolean, qualified: Boolean): String {
        val skipReceiver = if (receiverTypeName != null && !includeReceiver) 1 else 0
        // A composable's synthetic slots are the same for every member of its overload set --
        // `Composer_Int_Int_Int` on all of them -- so they separate nothing and would only make the
        // Python name unreadable. Dropped, which leaves exactly the declared parameters the two
        // overloads actually differ in.
        val declaredEnd = paramNames.indexOf(COMPOSER_PARAMETER_NAME).takeIf { it >= 0 } ?: paramTypeNames.size
        val parts = paramTypeNames.take(declaredEnd).drop(skipReceiver)
        // A zero-parameter member of a group -- there can be at most one, so this only ever has to
        // be distinct from the others, not descriptive.
        if (parts.isEmpty()) return "0"
        return parts.joinToString("_") { name ->
            val chosen = if (qualified) name else name.substringAfterLast('.')
            chosen.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        }
    }

    private fun javaStaticCandidates(node: ClassNode): List<Candidate> {
        val owner = binaryNameToQualified(node.name)
        return node.methods
            .filter { it.access.hasFlag(Opcodes.ACC_PUBLIC) && it.access.hasFlag(Opcodes.ACC_STATIC) }
            .filter { !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) && !it.access.hasFlag(Opcodes.ACC_BRIDGE) }
            .filter { !it.name.startsWith("<") }
            // `$` is a Kotlin/Java synthetic (`fn$default`, `access$000`). `-` would be value-class
            // mangling, but nothing reaches this branch *with* Kotlin metadata to interpret it by --
            // a name like that here is unreadable, not merely unsupported, so it stays declined.
            .filter { '$' !in it.name && '-' !in it.name }
            .mapNotNull { candidateFromDescriptor(owner, it.name, it.desc) }
    }

    private fun kotlinCandidates(
        owner: String,
        ownerIsClass: Boolean,
        functions: List<ResolvedFunction>,
        ownerNode: ClassNode,
        classpath: ArtifactClasspath,
    ): List<Candidate> {
        val bySignature = functions.associateBy { it.jvmSignature.name to it.jvmSignature.descriptor }
        // A Kotlin `object`'s functions are instance methods of its singleton (issue #53:
        // `Arrangement.spacedBy`); generated source reaches them as `Owner.name(...)`, so they bind
        // like a static. A composable one is declined below -- its thunk is a static call.
        val isObject = ownerIsClass && ownerNode.fields.any { it.name == "INSTANCE" && it.access.hasFlag(Opcodes.ACC_STATIC) }
        val staticSignatures = ownerNode.methods.filter { it.access.hasFlag(Opcodes.ACC_STATIC) }.map { it.name to it.desc }.toSet()
        return ownerNode.methods
            .filter { it.access.hasFlag(Opcodes.ACC_PUBLIC) }
            .filter {
                it.access.hasFlag(Opcodes.ACC_STATIC) ||
                    (isObject && (it.name to it.desc) !in staticSignatures && (it.name to it.desc) !in ANY_MEMBER_SIGNATURES)
            }
            .filter { !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) && !it.access.hasFlag(Opcodes.ACC_BRIDGE) }
            .filter { !it.name.startsWith("<") && '$' !in it.name }
            .mapNotNull { method ->
                val function = bySignature[method.name to method.desc]
                    // A `suspend` declaration's JVM shape carries a trailing `Continuation`, so its
                    // descriptor never matches the one metadata records and it would fall out here
                    // anyway. Looked up by Kotlin name instead so that it is declined *explicitly*
                    // and reaches the model flagged -- AGENTS.md's "제외한 것을 조용히 빠뜨리지 마라".
                    ?: return@mapNotNull functions
                        .firstOrNull { it.isSuspend && it.jvmSignature.name == method.name }
                        ?.let { suspending ->
                            declinedCandidate(owner, ownerIsClass, suspending, classpath, "suspend", isComposable(method))
                        }
                if (function.isSuspend) return@mapNotNull declinedCandidate(owner, ownerIsClass, function, classpath, "suspend", isComposable(method))
                if (!method.access.hasFlag(Opcodes.ACC_STATIC) && isComposable(method)) {
                    return@mapNotNull declinedCandidate(
                        owner, ownerIsClass, function, classpath,
                        "a @Composable member of an object: its generated call is a static invocation", true,
                    )
                }
                // A member whose Kotlin-declared parameter count does not match its JVM parameter
                // count has an implicit JVM parameter metadata does not account for -- a value
                // class's own instance turned into an unboxed receiver, or a composable's synthetic
                // `$composer`/`$changed`. There is no declared parameter to bind it to, so it is
                // declined here rather than misread as one fewer parameter than the method actually
                // takes. See this object's KDoc.
                val (paramDescriptors, returnDescriptor) = splitMethodDescriptor(method.desc)
                if (function.allParameterTypes.size != paramDescriptors.size) {
                    // A composable's extra JVM parameters are not *unaccounted for* -- they are
                    // `$composer`, the `$changed` masks and the `$default` mask, in that order, and
                    // `ComposableShape` reads them off the descriptor. Bound as ordinary slots
                    // Python fills; everything else with an arity mismatch is still an implicit
                    // receiver nothing can supply, and still declined.
                    if (isComposable(method)) {
                        composableCandidate(
                            owner, ownerIsClass, function, classpath, paramDescriptors, returnDescriptor, method.name,
                            ownerNode.name,
                        )?.let { return@mapNotNull it }
                    }
                    return@mapNotNull declinedCandidate(
                        owner,
                        ownerIsClass,
                        function,
                        classpath,
                        "the JVM signature carries ${paramDescriptors.size - function.allParameterTypes.size} " +
                            "parameter(s) metadata does not declare",
                        isComposable(method),
                    )
                }
                candidateFromFunction(owner, ownerIsClass, function, classpath, paramDescriptors, isComposable(method))
            }
    }

    /**
     * A class's own public constructors, bound the same way a top-level function is: [constructorsOf]
     * gives each one a [ResolvedFunction] whose name is the class and whose "return type" is the
     * class itself, so the ordinary non-extension call [candidateFromFunction] already writes --
     * `owner.SimpleName(args)` -- is a valid fully-qualified constructor call with no change to that
     * function. `owner` here is the **package**, not the class (contrast [kotlinCandidates]'s own
     * `owner`, which for this same [ownerNode] is the class): a constructor is not a static member of
     * its class, it is the name a caller writes in the class's place, so it publishes the way a
     * top-level function does -- see `docs/design/pyi-generation-design.md` §2.2's `DeclarationModel.owner`
     * KDoc for why that distinction is what selects the `sys.modules` entry a binding lands on.
     *
     * `<init>` is filtered out of [kotlinCandidates] by that function's own `ACC_STATIC` check (a
     * constructor is never `static`), so nothing here duplicates a declaration that path already
     * handles.
     *
     * ### Why this never looks at [ownerNode]'s own `<init>` bytecode
     *
     * Every other producer in this file cross-checks a [ResolvedFunction] against the real
     * `MethodNode`: the JVM parameter count can exceed the declared one (a composable's synthetic
     * slots, a value class's own member with an implicit unboxed receiver), and only the bytecode
     * says so. A constructor has no such gap -- `KmConstructor.signature`'s descriptor is compiled
     * *from* `valueParameters`, so [ResolvedFunction.allParameterTypes] and the descriptor
     * [candidateFromFunction] needs can both come from the one [KmConstructor], with nothing left for
     * a `MethodNode` to add.
     *
     * Looking one up would actively break value classes. `@JvmInline value class Meters(val value:
     * Double)` declares its constructor with no visibility modifier -- Kotlin-public, same as any
     * other -- but `javap` on the compiled box shows `private fixture.artifactvalueclass.Meters
     * (double)`: the compiler makes the *box's* `<init>` private on purpose, to push every external
     * caller through `constructor-impl`/`box-impl` instead. Matching against `ACC_PUBLIC` bytecode
     * would decline every value class's constructor for a visibility the *language* never gave it --
     * caught by [aValueClasssOwnConstructorRoundTripsThroughItsUnderlyingPrimitive] running red
     * first. Generated Kotlin source is unaffected either way: `Meters(3.0)` is source calling a
     * constructor, and the compiler lowers it through the private box the same as it would inside the
     * declaring module -- nothing here ever spells `<init>` or reads its access flags.
     */
    /**
     * Public properties of an `object` declaration or a companion, bound as zero-argument getters.
     *
     * These are the declarations Python reaches as `Alignment.Center` -- a singleton instance behind
     * a name, not a function. Nothing bound them before, which is why two downstream files sat empty
     * with a note saying no bound declaration produces an object handle. This is that declaration.
     *
     * **A companion loses `Companion` from its bound name and keeps it in its call.** Kotlin source
     * writes `Alignment.Center`, and that is the name a caller expects; the JVM holds the value on
     * `Alignment.Companion`, and that is what the generated body has to say. The two differ, so both
     * are computed rather than one derived from the other.
     *
     * A property whose *return type's* class is not public is declined even when the property is,
     * because the generated body names that type's owner and could not be compiled outside its
     * module. The property's own visibility is checked as well, and so is the owner's.
     */
    private fun objectConstantCandidates(
        kmClass: kotlin.metadata.KmClass,
        ownerNode: ClassNode,
        classpath: ArtifactClasspath,
    ): List<Candidate> {
        if (kmClass.visibility != Visibility.PUBLIC) return emptyList()
        val qualified = binaryNameToQualified(ownerNode.name)

        // An `object` declaration holds its values on itself.
        val ownProperties = if (ownerNode.fields.any { it.name == "INSTANCE" && it.access.hasFlag(Opcodes.ACC_STATIC) }) {
            constantsOf(kmClass, ownerNode, boundOwner = qualified, callOwner = qualified, classpath)
        } else {
            emptyList()
        }

        // A companion's values are reached through the outer class, and the walk never meets the
        // companion itself: `scan` skips every binary name containing `$`, so `Outer$Companion` is
        // never read as an entry. It is loaded from here instead, by the name the outer class's own
        // metadata gives it -- which is also why the bound name can drop `Companion` while the
        // generated call keeps it.
        val companionProperties = kmClass.companionObject?.let { companionName ->
            val companionNode = classpath.classNode(ownerNode.name + "\$" + companionName) ?: return@let null
            val companionMetadata = kotlinClassMetadataOf(companionNode) as? KotlinClassMetadata.Class ?: return@let null
            if (companionMetadata.kmClass.visibility != Visibility.PUBLIC) return@let null
            constantsOf(
                companionMetadata.kmClass,
                companionNode,
                boundOwner = qualified,
                callOwner = "$qualified.$companionName",
                classpath,
            )
        }.orEmpty()

        return ownProperties + companionProperties
    }

    /**
     * Public, receiverless properties of a singleton holder, as zero-argument getters.
     *
     * These are the declarations Python reaches as `Alignment.Center` -- a value behind a name, not
     * a function. Nothing bound them before, which is why two files downstream sat empty under a
     * note saying no bound declaration produces an object handle. This is that declaration.
     *
     * [boundOwner] and [callOwner] differ for a companion and are therefore both passed: a caller
     * writes `Alignment.Center`, and the generated body has to say `Alignment.Companion.Center`.
     *
     * A property whose *return type* is not public is declined even when the property is, because
     * the body names that type and could not compile outside its module.
     */
    private fun constantsOf(
        kmClass: kotlin.metadata.KmClass,
        declaringNode: ClassNode,
        boundOwner: String,
        callOwner: String,
        classpath: ArtifactClasspath,
    ): List<Candidate> = kmClass.properties.mapNotNull { property ->
        if (property.visibility != Visibility.PUBLIC) return@mapNotNull null
        if (property.receiverParameterType != null) return@mapNotNull null
        // A `@Composable get()` reads the composition, so its value does not exist outside one --
        // `MaterialTheme.colorScheme` is the shape. The generated body is an ordinary lambda, and
        // Kotlin rejects the call there: "@Composable invocations can only happen from the context
        // of a @Composable function". Found by generating them and watching the fixture fail to
        // compile, which is also why the check reads the getter's own annotations rather than the
        // property's -- metadata does not carry them for a release build.
        val getterName = "get" + property.name.replaceFirstChar { it.uppercase() }
        if (declaringNode.methods.any { it.name == getterName && isComposable(it) }) return@mapNotNull null
        val returnModel = kotlinTypeModelOf(property.returnType, classpath) ?: return@mapNotNull null
        val returnType = resolveKotlinType(property.returnType, classpath, BoundaryDirection.RETURN)
            ?: return@mapNotNull null
        val classifier = property.returnType.classifier as? KmClassifier.Class
        val returnMetadata = classifier?.name?.let { classpath.classNode(it) }
            ?.let { kotlinClassMetadataOf(it) } as? KotlinClassMetadata.Class
        if (returnMetadata != null && returnMetadata.kmClass.visibility != Visibility.PUBLIC) return@mapNotNull null

        val name = "$boundOwner.${property.name}"
        val call = "$callOwner.${property.name}"
        Candidate(
            callable = ArtifactCallable(
                name = name,
                arity = 0,
                paramTags = emptyList(),
                returnTag = returnType.tag,
                lambdaBody = "{ ${returnType.wrapReturn(call)} }",
                returnTypeName = kotlinClassifierNameOf(property.returnType),
                returnSupertypes = returnSupertypesOf(property.returnType, returnType.tag, classpath),
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                paramHasDefault = emptyList(),
                kind = "STATIC_GETTER",
            ),
            declaration = DeclarationModel(
                simpleName = property.name,
                owner = boundOwner,
                ownerIsClass = true,
                receiver = null,
                parameters = emptyList(),
                returnType = returnModel,
                isComposable = false,
                isSuspend = false,
                kind = "STATIC_GETTER",
                bindingName = name,
                returnBoundaryTag = returnType.tag,
            ),
        )
    }

    private fun constructorCandidates(
        kmClass: kotlin.metadata.KmClass,
        ownerNode: ClassNode,
        classpath: ArtifactClasspath,
    ): List<Candidate> {
        // An abstract class cannot be instantiated -- `Cannot create an instance of an abstract
        // class` would appear in the generated fragment. An internal class's name cannot be spelled
        // in source outside its module -- the same rule `resolvedFunctionOrNull` applies to
        // individual functions via their visibility. Both are checked on the *class*, not the
        // constructor: `constructorsOf` / `resolvedConstructorOrNull` already checks the
        // constructor's own visibility separately.
        if (ownerNode.access.hasFlag(Opcodes.ACC_ABSTRACT)) return emptyList()
        if (kmClass.visibility != Visibility.PUBLIC) return emptyList()
        // Every public class's constructor is attempted, value class or not -- what a constructor
        // could overshadow (a factory function already claiming the bare name, or a constant this
        // same class publishes under a dotted continuation of it, `TextStyle.Default`) is a fact
        // about the *whole walk*, not about this class in isolation, so it is caught once, after the
        // walk, by [dropCollidingConstructors] -- not by guessing which classes are safe here. This
        // used to special-case two class names (`Typography`, `Shapes`) for the same reason `Meters`
        // needs none: neither publishes a same-named constant, and [dropCollidingConstructors] now
        // says so structurally instead of by name.
        // A generic one cannot be called without naming its type argument, and Python has no way to
        // supply one: `SessionMutex()` does not compile, it needs `SessionMutex<T>()`. Surfaced by
        // widening the walked packages, which brought the first generic value class into range --
        // the same unspellable-type-parameter limit the modifier wrappers already work around by
        // fixing a concrete type.
        if (kmClass.typeParameters.isNotEmpty()) return emptyList()

        // `@Deprecated(level = DeprecationLevel.HIDDEN)` on a constructor keeps its *metadata* entry
        // (for binary compatibility, ABI consumers still need to see it existed) while the compiler
        // makes the constructor it actually describes JVM-`private` and publishes only a synthetic
        // `$default`-masked bridge (`DefaultConstructorMarker` trailing) as the public one -- with a
        // *different* descriptor metadata never records. Found widening constructor binding past
        // value classes to `androidx.compose.ui.input.pointer.PointerInputChange`, whose old
        // `consumed: ConsumedData` constructor is exactly this shape: `e: None of the following
        // candidates is applicable` against a generated call naming a constructor Kotlin source can
        // no longer resolve.
        //
        // Checked for an **ordinary class only**. A value class's own box constructor is the other
        // case this object's KDoc already warns bytecode cannot judge -- "the compiler makes the
        // *box's* `<init>` private on purpose, to push every external caller through
        // `constructor-impl`/`box-impl` instead" -- so the same bytecode check that catches a
        // hidden-deprecated ordinary constructor would wrongly decline every public value class
        // constructor, `Meters` included. `kmClass.isValue` is exactly the fact that tells these two
        // "the real `<init>` is not the one this metadata entry means" cases apart.
        val owner = packageNameOf(ownerNode.name)
        return constructorsOf(kmClass, ownerNode.name)
            .filter { kmClass.isValue || it.jvmSignature.descriptor in realPublicInitDescriptorsOf(ownerNode) }
            .mapNotNull { function ->
                val (paramDescriptors, _) = splitMethodDescriptor(function.jvmSignature.descriptor)
                candidateFromFunction(owner, false, function, classpath, paramDescriptors, isComposable = false)?.copy(isConstructor = true)
            }
    }

    private fun realPublicInitDescriptorsOf(ownerNode: ClassNode): Set<String> =
        ownerNode.methods
            .filter { it.name == "<init>" && it.access.hasFlag(Opcodes.ACC_PUBLIC) && !it.access.hasFlag(Opcodes.ACC_SYNTHETIC) }
            .mapTo(mutableSetOf()) { it.desc }

    /**
     * Whether this JVM method is a `@Composable`.
     *
     * ### It is an *invisible* annotation, and reading the visible list found none, ever
     *
     * This used to read `MethodNode.visibleAnnotations` on the stated grounds that
     * "`@Composable` is `RUNTIME`-retained, so ASM reads it as an ordinary visible annotation". That
     * is wrong, and `javap -v androidx/compose/material3/TextKt.class` says so directly: every
     * composable in the jar carries
     *
     *     RuntimeInvisibleAnnotations:
     *       androidx.compose.runtime.Composable
     *
     * because `androidx.compose.runtime.Composable` is declared
     * `@Retention(AnnotationRetention.BINARY)`. `RuntimeInvisible*` is exactly what ASM puts in
     * `invisibleAnnotations`, so the old predicate answered `false` for **every real composable**,
     * and `DeclarationModel.isComposable` -- which drives `docs/archive/pyi-generation-pythonic-stubs.md` §3.6's
     * PascalCase rule -- was dead. Nothing caught it because the only composables reaching it were
     * being declined anyway, and a declined entry's flag is not asserted anywhere.
     *
     * Both lists are read rather than just the invisible one: retention is the annotation author's
     * choice and a future Compose could change it, whereas a method carrying the descriptor in
     * either list means the same thing.
     *
     * ### Why not `@Metadata`
     *
     * `KmFunction` has an annotations list, but `kotlin-metadata-jvm` only populates it when the
     * compiler was asked to keep annotations in metadata, which Compose's release build was not.
     * The class file is the fact here; metadata is how the *parameters* are then interpreted.
     *
     * Two consumers: the name rule above, and [composableCandidate], which is what stops the arity
     * check from declining every composable.
     */
    private fun isComposable(method: org.objectweb.asm.tree.MethodNode): Boolean =
        sequenceOf(method.visibleAnnotations, method.invisibleAnnotations)
            .filterNotNull()
            .any { list -> list.any { it.desc == ComposableShape.COMPOSABLE_ANNOTATION_DESCRIPTOR } }

    /**
     * A `@Composable`, bound as an ordinary function whose `$composer`, `$changed` and `$default`
     * slots are Python's to fill.
     *
     * ### Why the synthetic parameters are exposed rather than hidden
     *
     * `docs/archive/pythonx-adapter-design.md` §4.5 rejected every way of *hiding* them, and each rejection
     * still stands: an arity-prefix entry cannot express "pass `text`, skip `modifier`", presence
     * branching costs 2^15 call expressions for `Text`, `Text$default` does not exist, and a
     * generated wrapper cannot restate defaults metadata never carries. What none of those noticed is
     * that the mask is a **declared trailing parameter** (§5.2) -- so it does not have to be a
     * compile-time constant at all. Compute it at run time and every one of those problems is
     * somebody else's: `$default` bit *i* set means "parameter *i* was not passed, substitute its
     * default", which is a fact about one integer rather than about 2^15 branches.
     *
     * Where that integer is computed matters and is not here. `pythonx` calls `androidx`, never the
     * other way round, so the mask is Python's arithmetic over which arguments the caller wrote;
     * this layer's job is to leave a slot for it. `$composer` is the one value Python cannot invent
     * -- it exists only inside a composition -- and comes from the single hand-written entry point.
     *
     * ### An extension composable, and the numbering that used to be guessed at
     *
     * This used to decline one outright, on the stated grounds that "Compose's `$changed` slots count
     * receivers and its `$default` bits are assigned over value parameters, so a receiver shifts one
     * numbering and not the other. Nothing here has measured which." It is measured now, out of the
     * callee, the way `docs/archive/pythonx-adapter-design.md` §5.2 measured the mask in the first place --
     * `javap -c androidx/compose/material3/NavigationBarKt`, whose `NavigationBarItem` is
     * `RowScope.NavigationBarItem(selected, onClick, icon, modifier = …, …)`:
     *
     *     iload $default; ldc -2147483648; iand; ifeq …   ; aload_0 -- the RowScope
     *     iload $default; iconst_1;         iand; ifeq …  ; iload_1 -- `selected`
     *     …
     *     iload $default; bipush 8; iand; ifeq +11
     *     getstatic androidx/compose/ui/Modifier.Companion
     *     astore 4                                        ; `modifier`, JVM local 4
     *
     * Both halves of the guess are answered, and they answer differently:
     *
     * | | does the receiver shift it |
     * |---|---|
     * | `$changed` | **yes** -- the receiver is slot 0, so value parameter *i* occupies bits `3(i+1)`…`3(i+1)+2` |
     * | `$default` | **no** -- bit *i* is value parameter *i*; `modifier` is the 4th value parameter and is guarded by `& 8` |
     *
     * The receiver is not left out of `$default` altogether: it is given **bit 31**, the top bit of
     * the first word, which is what `& -2147483648` above is. It is never set by a caller -- a
     * receiver cannot be omitted -- so it costs this binding nothing, and the same encoding appears
     * on `SegmentedButton` (two `$changed` words, `MultiChoiceSegmentedButtonRowScope` receiver).
     *
     * Only the `$default` answer is load-bearing here, because `pythonx` passes `$changed` as `0`
     * unconditionally (it is a claim about staticness that this caller cannot make); so the whole of
     * what an extension composable needed was for the mask arithmetic to count value parameters
     * rather than slots. `ComposableShape` was already right: `changedCountFor` is computed from
     * `allParameterTypes.size`, which **includes** the receiver, which is what the `$changed`
     * numbering does.
     *
     * ### What is still declined here, and why
     *
     * - **A shape [ComposableShape.of] does not recognise**, which then falls back to the decline
     *   this method was reached from. A future lowering that adds a fourth kind of synthetic
     *   parameter degrades to "not bound" rather than to a call with the wrong arguments.
     * - **A `char` parameter**, for `boundaryTypeOf`'s reason: Python has no character type.
     * - **A composable with more than 31 value parameters *and* a receiver**, which would need the
     *   receiver's bit 31 and a 32nd value parameter's bit 31 at once. `ComposableShape` predicts two
     *   `$default` words there and Compose would emit one, so the shape check declines it rather than
     *   writing a mask into a slot that does not exist. Nothing measured declares one -- the widest
     *   composable in three jars takes 17.
     */
    private fun composableCandidate(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        paramDescriptors: List<String>,
        returnDescriptor: String,
        jvmMethodName: String,
        ownerInternalName: String,
    ): Candidate? {
        val declaredCount = function.allParameterTypes.size
        val shape = ComposableShape.of(declaredCount, paramDescriptors) ?: return null
        if (shape.totalCount != paramDescriptors.size) return null

        val model = declarationModelOf(owner, ownerIsClass, function, classpath, isComposable = true) ?: return null
        // The declared slots are typed from `@Metadata`, the synthetic ones from the descriptor --
        // see [composableDeclaredSlot] for why the pairing is safe and what refuses it.
        val declaredSlots = (0 until declaredCount).map { index ->
            composableDeclaredSlot(function.allParameterTypes[index], paramDescriptors[index], classpath) ?: return null
        }
        val syntheticTags = paramDescriptors.drop(declaredCount).map { composableSlotTagOf(it) ?: return null }
        val paramTags = declaredSlots.map { it.tag } + syntheticTags
        val returnTag = if (returnDescriptor == "V") "UNIT" else composableSlotTagOf(returnDescriptor) ?: return null
        val declaredTypeNames = function.allParameterTypes.mapIndexed { index, type ->
            val declared = kotlinClassifierNameOf(type) ?: return null
            functionSlotTypeName(type, declared, paramDescriptors[index]) ?: declared
        }

        val syntheticNames = shape.syntheticParameterNames()
        val paramNames = (0 until declaredCount).map { function.allParameterNames.getOrNull(it) ?: "p$it" } + syntheticNames
        val paramTypeNames = declaredTypeNames + syntheticNames.map {
            if (it == COMPOSER_PARAMETER_NAME) COMPOSER_TYPE_NAME else "kotlin.Int"
        }
        val qualifiedName = "$owner.${function.kotlinName}"

        return Candidate(
            callable = ArtifactCallable(
                name = qualifiedName,
                arity = paramDescriptors.size,
                paramTags = paramTags,
                returnTag = returnTag,
                // Filled by [assignThunkIndices], which is the only place that knows which `t<i>`
                // this is -- and therefore the only place the `.kt` and the `.class` can agree.
                lambdaBody = "",
                // Slot 0 when there is one, exactly as for a plain extension: that is what puts the
                // declaration on `_BY_RECEIVER` and makes it a method on the scope's proxy, which is
                // the only spelling that can reach it -- a Python caller has no other way to produce
                // a `RowScope`.
                receiverTypeName = function.receiverType?.let { kotlinClassifierNameOf(it) },
                paramNames = paramNames,
                paramTypeNames = paramTypeNames,
                returnTypeName = kotlinClassifierNameOf(function.returnType),
                returnSupertypes = returnSupertypesOf(function.returnType, returnTag, classpath),
                // The declaration's own answer, unlike every other producer here -- and it is the
                // honest one, because the mask reaches *every* default with no cap and no branch to
                // decline. A synthetic slot is never omittable: `pythonx` always computes all three.
                paramHasDefault = (0 until declaredCount).map { function.allParameterDefaults.getOrElse(it) { false } } +
                    List(syntheticNames.size) { false },
                thunk = ThunkSpec(
                    ownerInternalName,
                    jvmMethodName,
                    "(${paramDescriptors.joinToString("")})$returnDescriptor",
                    valueClassUnboxOwners = declaredSlots.map { it.unboxOwner } + List(syntheticTags.size) { null },
                ),
            ),
            // `paramTags` is slot-aligned, so an extension's receiver is slot 0 and `model.parameters`
            // (receiver excluded) starts at slot 1. Reading it unshifted gave `NavigationBarItem`'s
            // `colors` the tag of `alwaysShowLabel`: `bool` in the stub (issue #53).
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnTag,
                receiverBoundaryTag = if (function.isExtension) paramTags.firstOrNull() else null,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(boundaryTag = paramTags.getOrNull(index + (if (function.isExtension) 1 else 0)))
                },
            ),
        )
    }

    /** The name of the slot the composer arrives in. Not a Kotlin identifier on purpose -- it is the
     * compiler's own spelling, it is what `pythonx` matches on to recognise a composable, and no
     * keyword argument can collide with it. */
    internal const val COMPOSER_PARAMETER_NAME = "\$composer"
    private const val COMPOSER_TYPE_NAME = "androidx.compose.runtime.Composer"

    /**
     * The name a **function-typed** parameter of a composable is reported under, or `null` when this
     * is not one and the declared name should stand.
     *
     * ### Why the declared name alone is not enough
     *
     * `androidx.compose.foundation.layout.Column` declares
     * `content: @Composable ColumnScope.() -> Unit`, which `@Metadata` records as `kotlin.Function1`
     * -- an extension function type over `ColumnScope`. Its JVM descriptor is
     * `Lkotlin/jvm/functions/Function3;`, because the Compose plugin appends a `Composer` and one
     * `$changed` to a composable function *type* exactly as it appends them to a composable
     * function. `androidx.compose.material3.Button` declares `onClick: () -> Unit`, which is
     * `kotlin.Function0` in **both**.
     *
     * `pythonx` has to hand Kotlin an object implementing the right interface, so it needs the
     * compiled arity; and it has to know whether to thread a composer through the invocation, so it
     * needs to know which of the two cases this is. Both are answered by comparing the two arities
     * the walker already has, and neither is guessed:
     *
     * | | declared | compiled | reported |
     * |---|---|---|---|
     * | `Button.onClick` | `Function0` | `Function0` | `kotlin.Function0()->kotlin.Unit` |
     * | `Column.content` | `Function1` | `Function3` | `kotlin.Function3@Composable(…ColumnScope)->kotlin.Unit` |
     * | `Slider.onValueChange` | `Function1` | `Function1` | `kotlin.Function1(kotlin.Float)->kotlin.Unit` |
     *
     * ### Why the compiled arity is not the whole answer
     *
     * It was, for as long as the only thing a Python callable could be was a `content` that takes
     * nothing. Two arities say *which interface* to implement and *whether a composer is threaded*,
     * and say nothing at all about **what to do with the arguments that interface is invoked with**.
     * `Column.content`'s `Function3` and `Slider.onValueChange`'s `Function1` both have arguments to
     * forward, and forwarding one needs two facts the arity cannot carry:
     *
     * - **whether it is a value or a Kotlin object.** A `Float` reaches Python as a number and a
     *   `ColumnScope` as a proxy over a handle; the erased `invoke(Object)` makes them the same
     *   thing at the call site, and only the declared type argument tells them apart. This is why
     *   the receiver of a scoped content could not be forwarded before -- not a different problem
     *   from `(Float) -> Unit`, the *same* one.
     * - **what Kotlin type the proxy is over**, which is what `_BY_RECEIVER` is keyed on and
     *   therefore the whole of whether `ColumnScope`'s extensions are reachable from the value.
     *
     * The **return** type is here for the opposite reason: so that a slot whose lambda has to give
     * something back keeps being refused. `Function1` is the spelling of both `(Float) -> Unit` and
     * `(Float) -> Boolean`, and the second cannot be bound -- `TypeTag` has one `INT` for `Byte`
     * through `Long` and one `FLOAT` for both floating widths, so nothing here can say which boxed
     * type the caller will cast the answer to. 19 of the 560 function-typed slots three Compose jars
     * declare return something other than `Unit` (`ComposableBindingTest.functionTypedSlotsCarry…`);
     * without the return type in the name they would be indistinguishable from the 541 that do, and
     * would fail as a `ClassCastException` inside Compose rather than as a refusal at the call.
     *
     * ### Why the answer travels in the type name
     *
     * The same reason `$composer` travels in the *parameter* name: `ExposedCallable` already carries
     * a per-slot declared type name to every target, and a column that only composables use would be
     * a second thing that can disagree with the first. Neither `@` nor `(` nor `>` is a character a
     * Kotlin fully-qualified name can contain, so nothing legitimate can collide with the grammar,
     * and `pythonx._function_slot` is the single reader.
     *
     * Anything the walker cannot fully describe returns `null`, so the slot keeps its bare declared
     * name and `pythonx` declines it as an ordinary object handle rather than casting a wrapper to an
     * interface it does not implement. That covers an arity relationship neither rule predicts, and
     * also a type argument with no classifier -- `SwipeableKt.rememberSwipeableState`'s
     * `(T) -> Boolean` is the one such slot in the three jars, and a type *parameter* has no name a
     * proxy could be built over.
     */
    internal fun functionSlotTypeName(type: kotlin.metadata.KmType, declaredName: String, descriptor: String): String? {
        if (!declaredName.startsWith(KOTLIN_FUNCTION_PREFIX)) return null
        val declaredArity = declaredName.removePrefix(KOTLIN_FUNCTION_PREFIX).toIntOrNull() ?: return null
        val jvmArity = jvmFunctionArityOf(descriptor) ?: return null
        val composable = when (jvmArity) {
            declaredArity -> false
            declaredArity + COMPOSABLE_LOWERED_SLOTS -> true
            else -> return null
        }
        // `Function1<ColumnScope, Unit>` -- the declared arity plus one, the last being the return.
        // Read off the type rather than the name because the name is only the classifier.
        val arguments = type.arguments
        if (arguments.size != declaredArity + 1) return null
        val names = arguments.map { kotlinClassifierNameOf(it.type ?: return null) ?: return null }
        val mark = if (composable) COMPOSABLE_TYPE_MARK else ""
        return KOTLIN_FUNCTION_PREFIX + jvmArity + mark +
            "(" + names.dropLast(1).joinToString(",") + ")" + FUNCTION_RETURNS + names.last()
    }

    /** Separates a function slot's forwarded argument types from what it has to give back. Not a
     * character a Kotlin classifier name can contain, so the grammar cannot be ambiguous. */
    internal const val FUNCTION_RETURNS: String = "->"

    /** The only return type a Python callable can currently stand in for; see [functionSlotTypeName]. */
    internal const val UNIT_TYPE_NAME: String = "kotlin.Unit"

    /** How many parameters the Compose plugin appends to a composable function *type*: the
     * `Composer` and one `$changed`. Unlike a composable *function*, whose `$changed` count grows
     * with its parameters, a lowered lambda carries exactly one -- checked against every composable
     * three Compose jars declare by `ComposableBindingTest.everyFunctionTypedSlotOfAComposableIsEitherPlainOrLoweredByTwo`. */
    private const val COMPOSABLE_LOWERED_SLOTS = 2

    private const val KOTLIN_FUNCTION_PREFIX = "kotlin.Function"

    /** Must match `pythonx`'s `_COMPOSABLE_MARK`. */
    internal const val COMPOSABLE_TYPE_MARK = "@Composable"

    /** The arity of `Lkotlin/jvm/functions/FunctionN;`, or `null` for anything else. */
    internal fun jvmFunctionArityOf(descriptor: String): Int? {
        if (!descriptor.startsWith(JVM_FUNCTION_PREFIX) || !descriptor.endsWith(";")) return null
        return descriptor.substring(JVM_FUNCTION_PREFIX.length, descriptor.length - 1).toIntOrNull()
    }

    private const val JVM_FUNCTION_PREFIX = "Lkotlin/jvm/functions/Function"

    /**
     * What one function-typed parameter of a **non**-`@Composable` declaration becomes: the slot name
     * `pythonx` reads, the boundary type generated Kotlin casts through, or the reason there is
     * neither.
     *
     * `null` from [functionSlotOrNull] means "not a function type at all", which is the ordinary
     * path; an instance with a [declineReason] means "a function type this walker will not bind".
     */
    private class FunctionSlot(
        val typeName: String? = null,
        val boundary: BoundaryType? = null,
        val declineReason: String? = null,
    )

    /**
     * The gap `a179b747` left, and where it was.
     *
     * That commit made a function-typed slot describable and fillable, and both halves are producer-
     * independent: [functionSlotTypeName] writes the signature and `pythonx._make_function` builds a
     * `FunctionN` from it. What is **not** shared is how a slot's type reaches a call:
     *
     * | producer | the call site | what the type has to be |
     * |---|---|---|
     * | `@Composable` | [generateThunkClass]'s bytecode | erased -- a `CHECKCAST` to `kotlin/jvm/functions/FunctionN` needs no Kotlin name |
     * | everything else | generated Kotlin source | **spellable** -- `args[i] as (Float) -> Unit` |
     *
     * So the composable path never asked `resolveKotlinType` about a function type and this one did,
     * and got the only answer that function can give: `objectBoundaryTypeOrNull` requires the
     * classifier to exist as a public class file, and Kotlin's built-in `FunctionN` has none anywhere
     * -- they live in `.kotlin_builtins`. 44 of the 46 declined public top-level `Modifier`
     * extensions declined for exactly that (`ArtifactScannerTest`), `clickable` among them.
     *
     * `objectBoundaryTypeOrNull` is deliberately **not** widened. Its rule is right for what it can
     * see: a classifier with no class file is one generated source must not name, and it has no
     * access to the JVM descriptor that says a `kotlin.Function1` slot is compiled as `Function1`
     * rather than as a lowered `Function3`. The exception is opted into here, by the one caller that
     * holds both facts.
     *
     * ### What stays declined
     *
     * - **`suspend`.** `Modifier.pointerInput(block: suspend PointerInputScope.() -> Unit)` records
     *   its type as `Function2<PointerInputScope, Continuation<Unit>, Any?>`, which [functionSlotTypeName]
     *   describes perfectly well -- and Kotlin will not let generated source assign a `Function2` to a
     *   `suspend` function type, nor can a Python callable answer `COROUTINE_SUSPENDED`. It is a
     *   *different* limit from the one this method opens, which is why it is named rather than left
     *   to fail as a compile error in somebody's generated fragment.
     * - **`@Composable`, inside a declaration that is not.** `Modifier.composed(factory:)` is the
     *   case. The fragment is compiled without the Compose plugin (`:ksp-fixtures:artifact`'s
     *   `build.gradle.kts` says so, and it is the whole shape of that fixture's claim), so
     *   `@Composable Modifier.() -> Modifier` written there is an ordinary `Function1` while the slot
     *   is a lowered `Function3`. **This one does not fail as a compile error**, which is why it is
     *   declined by name rather than left to the compiler: removing this guard and asking for
     *   `androidx.compose.ui.ComposedModifierKt` produces a fragment that compiles, casting
     *   `args[2] as kotlin.Function1<Modifier, Modifier>` beside a slot reported as
     *   `kotlin.Function3@Composable(…)->…` -- so `pythonx` would build a `Function3` from the name and
     *   hand it to a `Function1` call site, and the disagreement would surface inside Compose. The
     *   thunk path has no such problem, which is why a composable *declaration* binds these and a
     *   non-composable one cannot.
     * - **anything [renderKotlinFunctionType] cannot write**, which is a type argument that is a type
     *   *parameter* or a star projection (`Modifier.swipeable`'s `thresholds`), a use-site variance
     *   this walker will not reproduce, or a classifier that is not a nameable public class.
     *
     * A slot returning something other than `kotlin.Unit` is **not** declined here, and that is the
     * same judgement `a179b747` made for composables rather than a new one: the return type is in the
     * name precisely so that `pythonx._coerce` can refuse to fill it *with a reason* -- `TypeTag`
     * carries one `INT` for `Byte` through `Long`, so neither side can say which boxed type the callee
     * will cast the erased answer to. Binding the declaration around it costs nothing and buys a
     * caller of `Modifier.onKeyEvent` a message about the slot instead of an `AttributeError` about
     * the whole name.
     */
    private fun functionSlotOrNull(type: KmType, descriptor: String, classpath: ArtifactClasspath): FunctionSlot? {
        val declared = kotlinClassifierNameOf(type) ?: return null
        if (!declared.startsWith(KOTLIN_FUNCTION_PREFIX)) return null
        if (type.isSuspend) {
            return FunctionSlot(
                declineReason = "a suspend function-typed parameter ($declared): its compiled shape takes a " +
                    "Continuation and answers COROUTINE_SUSPENDED, which no Python callable can do",
            )
        }
        val name = functionSlotTypeName(type, declared, descriptor)
            ?: return FunctionSlot(
                declineReason = "a function-typed parameter this walk cannot describe ($declared): a type " +
                    "argument has no name a proxy could be built over",
            )
        if (name.startsWith(KOTLIN_FUNCTION_PREFIX + jvmFunctionArityOf(descriptor) + COMPOSABLE_TYPE_MARK)) {
            return FunctionSlot(
                declineReason = "a @Composable function-typed parameter of a declaration that is not itself " +
                    "@Composable: generated Kotlin is compiled without the Compose plugin, so it cannot " +
                    "produce the lowered function type the callee takes",
            )
        }
        val rendered = renderKotlinFunctionType(type, classpath)
            ?: return FunctionSlot(
                declineReason = "a function-typed parameter generated Kotlin cannot spell ($declared): some " +
                    "part of its signature is not a nameable public type",
            )
        // The same OBJECT boundary an ordinary handle crosses on -- what Python puts in the slot is
        // the handle `pythonx._make_function` got back from `PythonCallables.newFunction`, and the
        // trampoline has already resolved it to the wrapper by the time this cast runs. The cast is
        // unchecked (the wrapper is a `Function1<Any?, Unit>` whatever the slot said), which the
        // fragment's file-level `@Suppress("UNCHECKED_CAST")` already covers.
        return FunctionSlot(typeName = name, boundary = BoundaryType("OBJECT", "(%s as $rendered)", "(%s)"))
    }

    /** The Kotlin-source spelling of a function type: `kotlin.Function1<kotlin.Float, kotlin.Unit>`.
     * Written as the `FunctionN` classifier rather than as `(Float) -> Unit` because the two are the
     * same type and the first needs no bracket rules; assignability to an extension function type
     * (`Modifier.() -> Unit`) holds either way, since Kotlin distinguishes those only at declaration
     * sites. */
    private fun renderKotlinFunctionType(type: KmType, classpath: ArtifactClasspath): String? =
        renderKotlinSourceType(type, classpath, depth = 0)

    /**
     * A type this walker is willing to **write into generated Kotlin**, or `null`.
     *
     * Deliberately stricter than `renderKotlinTypeName`, which serves an `as` cast on an ordinary
     * object handle and therefore only has to be a name. This one lands inside a generic argument
     * list, where three things it can ignore become wrong answers:
     *
     * - **nullability of an argument.** `Function1` is contravariant in its argument, so
     *   `Function1<LayoutCoordinates, Unit>` is not assignable to `Function1<LayoutCoordinates?, Unit>`
     *   -- `Modifier.onFocusedBoundsChanged` is the case, and a cast rebuilt from the slot *name*
     *   (which drops `?`, being a key) would not compile.
     * - **use-site variance.** A projection this walker reproduced by dropping it would silently be a
     *   different type; declined instead, since nothing measured needs one.
     * - **a Kotlin built-in.** `kotlin.Float` and `kotlin.Unit` have no class file and are perfectly
     *   writable, which is the one direction `isNameablePublicClass` is too strict in --
     *   [kotlinPrimitiveBoundaryTypeOf] is the list of the ones this boundary already knows, plus
     *   `kotlin.Any`, which is what an unconstrained argument becomes.
     */
    private fun renderKotlinSourceType(type: KmType, classpath: ArtifactClasspath, depth: Int): String? {
        if (depth > 4) return null
        val classifier = type.classifier as? KmClassifier.Class ?: return null
        val internal = classifier.name
        val writable = internal == KOTLIN_ANY_INTERNAL_NAME ||
            // The function type itself, which is the whole point and is the one classifier
            // `isNameablePublicClass` is guaranteed to reject: `kotlin.Function1` is mapped onto a
            // JVM interface and has no class file of its own under that name anywhere.
            internal.startsWith(KOTLIN_FUNCTION_INTERNAL_PREFIX) ||
            kotlinPrimitiveBoundaryTypeOf(internal) != null ||
            classpath.isNameablePublicClass(internal)
        if (!writable) return null
        val suffix = if (type.isNullable) "?" else ""
        val base = internal.replace('/', '.')
        if (type.arguments.isEmpty()) return base + suffix
        val arguments = type.arguments.map { projection ->
            if (projection.variance != KmVariance.INVARIANT) return null
            val argument = projection.type ?: return null
            renderKotlinSourceType(argument, classpath, depth + 1) ?: return null
        }
        return "$base<${arguments.joinToString(", ")}>$suffix"
    }

    private const val KOTLIN_ANY_INTERNAL_NAME = "kotlin/Any"

    /** [KOTLIN_FUNCTION_PREFIX] as `KmClassifier.Class` spells it. */
    private const val KOTLIN_FUNCTION_INTERNAL_PREFIX = "kotlin/Function"

    /**
     * One **declared** slot of a composable: how it marshals, and what the thunk has to do to the
     * object the boundary sends before the erased JVM signature will take it.
     *
     * @param unboxOwner the `@JvmInline value class` box the boundary carries, which the thunk
     *   unwraps with `unbox-impl`; `null` when the slot needs no unwrapping.
     * @param upgraded whether the answer differs from what the JVM descriptor alone would have said.
     *   Carried for the measurement in `ComposableValueClassSlotTest` and for nothing else: a slot
     *   that was never upgraded proves the fix reached nothing.
     */
    internal class ComposableSlot(val tag: String, val unboxOwner: String?, val upgraded: Boolean)

    /**
     * **The wall `ebe3365f` pinned**: the same walker typed `Color` two ways, and a `@Composable`'s
     * `Color` slot refused the very handle an ordinary declaration accepted.
     *
     * ### The two views, and why there were two
     *
     * An ordinary declaration is typed by [resolveKotlinType] from `@Metadata`, which knows `Color`
     * is a `@JvmInline value class` over a `ULong` whose own constructor is `internal` -- so it can
     * neither be built from a raw number nor unwrapped, and crosses as an object handle. A
     * composable's parameters were typed by [composableSlotTagOf] from the **JVM descriptor**, where
     * the same `Color` is the letter `J`. `INT` and `OBJECT` for one type, in one jar.
     *
     * The descriptor is not gratuitous. A composable's `$composer`, `$changed` and `$default` slots
     * are added by the Compose plugin during IR lowering and are **not declared anywhere in
     * metadata**, so the JVM parameter list is genuinely longer than the Kotlin one and only the
     * descriptor can say what the trailing slots are. What did not follow -- and was the defect -- is
     * that the *declared* slots had to be read from it too.
     *
     * ### How the two lists are paired
     *
     * Positionally, over the first [ComposableShape.declaredCount] JVM parameters, and that is a
     * *checked* relation rather than an assumption: [ComposableShape.of] has already established
     * that JVM parameter `declaredCount` is the `Composer` and that every parameter after it is an
     * `int`, and [composableCandidate] additionally requires `shape.totalCount` to equal the JVM
     * parameter count. Two arities, related in exactly one admissible way -- the same argument
     * [functionSlotTypeName] makes for a function-typed slot, where a declared `Function1` may be a
     * compiled `Function1` or a compiled `Function3` and nothing else.
     *
     * ### And what stops a wrong pairing from being silent
     *
     * The arity relation says the lists are the same length; it cannot say that slot *i* of one is
     * slot *i* of the other. So every pair is **cross-checked** here, and there are only three ways
     * for a pair to be admitted:
     *
     * | the two views | what it means | what happens |
     * |---|---|---|
     * | the same tag | a primitive against its own descriptor, an object against a reference, a `Dp` (`FLOAT`) against an `F` | bound, unchanged |
     * | metadata says `OBJECT`, and the carrier's own `unbox-impl` returns **exactly** this descriptor | a value class the walker cannot open, erased into the slot | bound as a handle, and the thunk unwraps it |
     * | metadata resolves to nothing at all | a nullable primitive, a built-in with no class file, a function type | the descriptor's answer stands, which is what shipped before |
     *
     * Anything else -- a `Modifier` against a `J`, a `Color` against a `Ljava/lang/String;` -- returns
     * `null` and **declines the whole declaration**. It cannot be bound to "whichever view was
     * consulted last", because that is precisely the failure this method exists to remove.
     *
     * `ComposableValueClassSlotTest.everyComposableSlotAgreesBetweenMetadataAndDescriptorWhenPairedInOrder`
     * measures that no composable in three Compose jars is declined by that rule, and its sibling
     * shifts the pairing by one slot to show the rule is not vacuous.
     */
    internal fun composableDeclaredSlot(
        type: KmType,
        descriptor: String,
        classpath: ArtifactClasspath,
    ): ComposableSlot? {
        val descriptorTag = composableSlotTagOf(descriptor)
        val resolved = resolveKotlinBoundary(type, classpath, BoundaryDirection.PARAMETER)
            // Nothing metadata can say about this type, so there is nothing to disagree with: a
            // nullable primitive, a Kotlin built-in with no class file, `kotlin.FunctionN`.
            ?: return descriptorTag?.let { ComposableSlot(it, unboxOwner = null, upgraded = false) }
        val metadataTag = resolved.boundary.tag
        if (metadataTag == descriptorTag) return ComposableSlot(metadataTag, unboxOwner = null, upgraded = false)

        val carrier = resolved.carrier
        if (metadataTag == OBJECT_TAG && carrier != null &&
            classpath.valueClassUnboxDescriptor(carrier) == descriptor
        ) {
            return ComposableSlot(OBJECT_TAG, unboxOwner = carrier, upgraded = true)
        }
        // Metadata says an object and the slot is erased, but nothing on this classpath can turn the
        // one into the other -- the box class is not resolvable. Declining would take away a binding
        // that exists today, so the descriptor's answer stands and `pythonx` keeps refusing the call
        // with the message it already gives. Counted by the sibling test rather than hidden.
        if (metadataTag == OBJECT_TAG && descriptorTag != null && !descriptor.startsWith("L") && !descriptor.startsWith("[")) {
            return ComposableSlot(descriptorTag, unboxOwner = null, upgraded = false)
        }
        return null
    }

    private const val OBJECT_TAG = "OBJECT"

    /**
     * The ancestry of one declaration's return type, or nothing at all.
     *
     * Asked **only** when the return crosses as a handle, which is the only case in which the answer
     * can ever be read: `pythonx._coerce` consults it for an owned `OBJECT` value on its way into
     * another slot, and a `Dp` result -- `FLOAT`, a raw number -- has no identity to carry an
     * ancestry on. That single condition is also what keeps the cost down; see
     * [nameablePublicSupertypesOf] and `ReturnSupertypeTest` for what it comes to.
     */
    private fun returnSupertypesOf(returnType: KmType, returnTag: String, classpath: ArtifactClasspath): List<String> {
        if (returnTag != OBJECT_TAG) return emptyList()
        val classifier = returnType.classifier as? KmClassifier.Class ?: return emptyList()
        return classpath.nameablePublicSupertypesOf(classifier.name)
    }

    /** A boundary tag for one JVM slot of a composable, which is the **compiled** shape rather than
     * the declared one: the thunk calls the erased signature, so a `Color` parameter is the `long`
     * it erases to. `null` declines the whole declaration. Still the answer for the **synthetic**
     * slots, which metadata does not declare at all; see [composableDeclaredSlot] for the declared
     * ones. */
    private fun composableSlotTagOf(descriptor: String): String? = when {
        descriptor == "C" || descriptor == "V" -> null
        else -> boundaryTypeOf(descriptor)?.tag
            ?: if (descriptor.startsWith("L") || descriptor.startsWith("[")) "OBJECT" else null
    }

    /**
     * Numbers the thunks of one walk and writes each composable's body around its number.
     *
     * Deliberately the last pass and deliberately over the whole walk: [disambiguateOverloads] can
     * turn a bound candidate into a declined one, and a thunk emitted for a declaration nothing calls
     * would be a method in the generated class with no caller -- harmless, but it would also make
     * [thunkSpecsOf]'s density check a lie.
     */
    private fun assignThunkIndices(candidates: List<Candidate>): List<Candidate> {
        var next = 0
        return candidates.map { candidate ->
            val callable = candidate.callable ?: return@map candidate
            if (callable.thunk == null) return@map candidate
            val index = next++
            candidate.copy(
                callable = callable.copy(
                    thunkIndex = index,
                    lambdaBody = "{ args -> $THUNK_CLASS_TOKEN.${thunkMethodName(index)}(args) }",
                ),
            )
        }
    }

    /** The model for a declaration that will not be bound, with the reason. The Kotlin types are
     * still read: a stub generator has to be able to say *what* was declined. */
    private fun declinedCandidate(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        reason: String,
        isComposable: Boolean,
    ): Candidate? {
        val declaration = declarationModelOf(owner, ownerIsClass, function, classpath, isComposable) ?: return null
        return Candidate(callable = null, declaration = declaration.copy(bindingName = null, declineReason = reason))
    }

    /**
     * The declared shape of one Kotlin function, independent of whether the boundary can carry it.
     *
     * `null` when some part of the signature has no name a stub could write -- a type *parameter*, a
     * flexible type. `docs/archive/pyi-generation-pythonic-stubs.md` §3.1's last row and §7: `BindingPolicy` rejects
     * generic declarations, and stubbing what cannot be called would be a lie.
     */
    private fun declarationModelOf(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        isComposable: Boolean,
    ): DeclarationModel? {
        val receiverIndex = if (function.isExtension) 1 else 0
        val receiverModel = function.receiverType?.let { kotlinTypeModelOf(it, classpath) ?: return null }
        val parameters = function.allParameterTypes.drop(receiverIndex).mapIndexed { index, type ->
            DeclaredParameter(
                name = function.allParameterNames.getOrNull(index + receiverIndex),
                type = kotlinTypeModelOf(type, classpath) ?: return null,
                declaresDefault = function.allParameterDefaults.getOrNull(index + receiverIndex) ?: false,
            )
        }
        return DeclarationModel(
            simpleName = function.kotlinName,
            owner = owner,
            ownerIsClass = ownerIsClass,
            receiver = receiverModel,
            parameters = parameters,
            returnType = kotlinTypeModelOf(function.returnType, classpath) ?: return null,
            isComposable = isComposable,
            isSuspend = function.isSuspend,
        )
    }

    private fun candidateFromFunction(
        owner: String,
        ownerIsClass: Boolean,
        function: ResolvedFunction,
        classpath: ArtifactClasspath,
        paramDescriptors: List<String>,
        isComposable: Boolean,
    ): Candidate? {
        val model = declarationModelOf(owner, ownerIsClass, function, classpath, isComposable) ?: return null
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }
        val receiverIndex = if (function.isExtension) 1 else 0

        // Read before `resolveKotlinType` is asked anything, because for a function-typed parameter
        // it is the one that would answer -- and would answer "no". See [functionSlotOrNull].
        val functionSlots = function.allParameterTypes.mapIndexed { index, type ->
            functionSlotOrNull(type, paramDescriptors[index], classpath)
        }
        functionSlots.firstNotNullOfOrNull { it?.declineReason }?.let { return declined(it) }

        val resolvedParams = function.allParameterTypes.mapIndexed { index, type ->
            functionSlots[index]?.boundary
                ?: resolveKotlinType(type, classpath, BoundaryDirection.PARAMETER)
                ?: return declined("no boundary type for parameter ${kotlinClassifierNameOf(type) ?: type.classifier}")
        }
        val returnType = resolveKotlinType(function.returnType, classpath, BoundaryDirection.RETURN)
            ?: return declined("no boundary type for return ${kotlinClassifierNameOf(function.returnType) ?: function.returnType.classifier}")
        // Declared, not marshalled: a `Dp` parameter's tag is FLOAT and its declared name is
        // `androidx.compose.ui.unit.Dp`. `docs/archive/pythonx-adapter-design.md` §2.4 row 4. A function slot
        // is the one exception and is the reason [functionSlotTypeName] exists: its declared name
        // alone (`kotlin.Function1`) says neither what the lambda is invoked with nor what it must
        // give back, and `pythonx` needs both to build a wrapper at all.
        val paramTypeNames = function.allParameterTypes.mapIndexed { index, type ->
            functionSlots[index]?.typeName
                ?: kotlinClassifierNameOf(type)
                ?: return declined("unnameable parameter type")
        }
        val returnTypeName = kotlinClassifierNameOf(function.returnType)

        val qualifiedName = "$owner.${function.kotlinName}"
        val argumentExpressions = resolvedParams.mapIndexed { index, type -> type.read("args[$index]") }

        // Kotlin has no syntax to call an extension function by fully qualifying it the way an
        // ordinary top-level function or a Java static can be (`pkg.fn(args)`) -- the receiver can
        // never be a positional argument, only `receiver.fn(args)`. An import is the only way to
        // name it without writing the receiver's own type out, which this walker does not otherwise
        // need to know.
        val alias = "artifact_ext_" + qualifiedName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        val imports =
            if (function.isExtension) listOf("import $owner.${function.kotlinName} as $alias") else emptyList()

        /** The call that passes everything, positionally -- byte for byte what this generator
         * emitted before defaults existed, and still the `else`-less first branch below. */
        val call = if (function.isExtension) {
            "${argumentExpressions.first()}.$alias(${argumentExpressions.drop(1).joinToString(", ")})"
        } else {
            "$owner.${function.kotlinName}(${argumentExpressions.joinToString(", ")})"
        }

        val arity = resolvedParams.size
        // The all-present body, and **only** that one, is decided here. Which arguments may be left
        // out cannot be: it depends on what else carries this Kotlin name, and an overload set is a
        // property of a package that one file does not see (this object's KDoc, and
        // `docs/design/kotlin-extensions-in-python.md` §2.5's 11 split pairs). [applyDefaultOmission] runs
        // over the finished walk and fills this in.
        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = resolvedParams.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ ${returnType.wrapReturn(call)} }"
            else "{ args -> ${returnType.wrapReturn(call)} }",
            imports = imports,
            receiverTypeName = function.receiverType?.let { kotlinClassifierNameOf(it) },
            paramNames = function.allParameterNames,
            paramTypeNames = paramTypeNames,
            returnTypeName = returnTypeName,
            returnSupertypes = returnSupertypesOf(function.returnType, returnType.tag, classpath),
            // Deliberately all `false` until [applyDefaultOmission] says otherwise: this column is a
            // statement about **this body**, and this body passes everything.
            paramHasDefault = List(resolvedParams.size) { false },
        )
        val omittable = omittableParameterIndices(function, receiverIndex)
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnType.tag,
                receiverBoundaryTag = if (function.isExtension) resolvedParams.first().tag else null,
                parameters = model.parameters.mapIndexed { index, parameter ->
                    parameter.copy(boundaryTag = resolvedParams[index + receiverIndex].tag)
                },
            ),
            plan = if (omittable.isEmpty()) null else DefaultOmissionPlan(
                omittable = omittable.sorted(),
                valueIndices = (receiverIndex until resolvedParams.size).toList(),
                declaredDefaults = function.allParameterDefaults,
                buildBody = { allowed ->
                    val branched = presenceBranchedCall(
                        function, owner, alias, argumentExpressions, receiverIndex,
                        qualifiedName, omittable.sorted(), call, allowed,
                    )
                    "{ args -> ${returnType.wrapReturn(branched)} }"
                },
            ),
        )
    }

    /**
     * What one bound declaration would need in order to offer default omission, held until the whole
     * walk is visible.
     *
     * The decision cannot be local. Kotlin resolves `receiver.padding()` against **every** overload
     * of `padding` that is applicable, so whether a branch that writes no argument compiles at all is
     * a fact about the other members of the overload set -- and those may be in another file, another
     * facade or another multi-file part. Building the body eagerly and pruning it afterwards was
     * tried and is worse: the pruning would be textual.
     *
     * Measured, not anticipated. Emitting every subset unconditionally failed to compile
     * `foundation-layout-desktop-1.6.11` in six places -- `WindowInsets(Dp×4)` against
     * `WindowInsets(Int×4)`, `paddingFromBaseline(Dp,Dp)` against `(TextUnit,TextUnit)`, and
     * `paddingFrom(AlignmentLine,Dp,Dp)` against its `TextUnit` twin -- each one the branch that
     * writes nothing, each one *"Overload resolution ambiguity between candidates"*.
     */
    internal class DefaultOmissionPlan(
        /** Indices into `allParameterTypes` that declare a default and that this generator is
         * willing to write a call without. */
        val omittable: List<Int>,
        /** Every value-parameter index, the extension receiver excluded. */
        val valueIndices: List<Int>,
        /** What the *declaration* says, as opposed to what the binding will offer -- the sibling test
         * needs Kotlin's own view, because Kotlin is what resolves the generated call. */
        val declaredDefaults: List<Boolean>,
        /** @param allowed the omission sets to generate a call for. Everything else becomes a branch
         *   that refuses at run time; see [presenceBranchedCall]. */
        val buildBody: (allowed: Set<Set<Int>>) -> String,
    )

    /**
     * How many defaulted parameters one declaration may have before the generator stops offering
     * omission at all.
     *
     * Presence branching costs **one generated call expression per subset** of the defaulted
     * parameters, so the cost is 2^n and it is paid at build time, on every build, by whoever asked
     * for the package. Measured over everything the walker binds today -- all of
     * `foundation-layout-desktop-1.6.11`, `kotlin-stdlib`'s `kotlin.text` and the whole JUnit 4 jar
     * -- the widest bound declaration declares **four** defaults (`padding(start, top, end, bottom)`
     * and six others), so 64 is well clear of the corpus while bounding an artefact nobody has
     * measured.
     *
     * `docs/archive/pythonx-adapter-design.md` §4.5 rejects presence branching outright on "2^15 branches for
     * `Text`". That objection is to an unbounded version of it and, separately, to a case that is not
     * in the table: `Text` is a `@Composable`, and the arity check in [kotlinCandidates] declines
     * every composable for the synthetic `$composer`/`$changed` parameters its JVM signature carries.
     * If composables ever become bindable they will arrive with 15+ defaults and land past this cap
     * -- which is the honest outcome, because the mechanism that suits them is the `$default` mask
     * §5.2 found is a *declared* trailing parameter there, not this one.
     */
    internal const val MAX_OMITTABLE_PARAMETERS = 6

    /**
     * The slots the generated body will let Python leave out, as indices into `allParameterTypes`.
     *
     * Empty means "this declaration is bound exactly as it was before defaults existed". Four
     * separate reasons produce that, and all four are deliberate:
     *
     * 1. nothing declares a default -- the common case, 43 of `foundation-layout`'s 70 entries;
     * 2. more than [MAX_OMITTABLE_PARAMETERS] do (see there);
     * 3. a parameter name is missing or is not one a named argument can be written from. Every
     *    partial branch names what it passes, because an omission that is not a trailing one has no
     *    positional spelling, so a nameless parameter makes the whole set unusable;
     * 4. the receiver, always. It is positional in Kotlin and [RECEIVER_PARAMETER_NAME] is
     *    deliberately not an identifier.
     */
    private fun omittableParameterIndices(function: ResolvedFunction, receiverIndex: Int): Set<Int> {
        val names = function.allParameterNames
        if (names.size != function.allParameterTypes.size) return emptySet()
        val indices = function.allParameterDefaults.indices.filter { index ->
            index >= receiverIndex &&
                function.allParameterDefaults.getOrElse(index) { false } &&
                names[index] != RECEIVER_PARAMETER_NAME
        }
        if (indices.isEmpty() || indices.size > MAX_OMITTABLE_PARAMETERS) return emptySet()
        // Every *passed* argument in a partial branch is named, not only the defaulted ones, so a
        // name this cannot write anywhere in the signature sinks the whole set rather than just its
        // own slot.
        if (names.drop(receiverIndex).any { !isWritableParameterName(it) }) return emptySet()
        return indices.toSet()
    }

    /**
     * Decides, over the finished walk, which omission sets each planned declaration may offer, and
     * rewrites its body and its `paramHasDefault` accordingly.
     *
     * ### The rule
     *
     * A branch that writes the arguments *W* is refused when **any sibling overload of the same
     * Kotlin name would also accept exactly *W***: same receiver, a parameter of the same name and
     * declared type for every member of *W*, and a default on everything else it declares. That is
     * the shape Kotlin reports as `Overload resolution ambiguity`, and the generated call has no way
     * to break the tie -- there are no arguments left to type-annotate, which is exactly why the tie
     * exists.
     *
     * Deliberately **not** a model of Kotlin's specificity rules. Kotlin does resolve some of these
     * (it prefers the two-`Dp` `padding` over the four-`Dp` one when nothing is written, on parameter
     * count), and reproducing that here would mean a generated call silently binding to a *different
     * declaration* than the table entry names. Refusing the branch instead costs one spelling --
     * `padding__Dp_Dp()` with no arguments -- and buys that every branch calls the declaration its
     * entry is named after.
     *
     * ### What a refused branch becomes
     *
     * Not a missing branch: `pythonx` reads `paramHasDefault` per slot and cannot express "these two
     * but not both at once", so the call is reachable and has to answer for itself. It throws, naming
     * the declaration and saying what to write, which is the same answer
     * [disambiguateOverloads] gives one level up for the same reason.
     */
    private fun applyDefaultOmission(candidates: List<Candidate>): List<Candidate> {
        if (candidates.none { it.plan != null }) return candidates
        val byName = candidates.filter { it.callable != null }.groupBy { it.callable!!.name }
        return candidates.map { candidate ->
            val plan = candidate.plan ?: return@map candidate
            val callable = candidate.callable ?: return@map candidate
            val siblings = byName[callable.name].orEmpty().filter { it !== candidate }
            val allowed = plan.omittable.powerSet()
                .filter { it.isNotEmpty() && !anySiblingAlsoAccepts(candidate, plan, siblings, it) }
                .toSet()
            if (allowed.isEmpty()) return@map candidate.copy(plan = null)
            val omittableNow = plan.omittable.filter { index -> allowed.any { index in it } }.toSet()
            Candidate(
                callable = callable.copy(
                    lambdaBody = plan.buildBody(allowed),
                    // The binding's contract, not the declaration's: exactly the slots the rewritten
                    // body has a call for that does not mention them. `pythonx._bind` fills the
                    // sentinel from this, so it has to describe the body and not the Kotlin source.
                    paramHasDefault = callable.paramHasDefault.indices.map { it in omittableNow },
                ),
                declaration = candidate.declaration.copy(
                    // Kept in step one consumer further out: `PyiRendering` writes `= ...` from this
                    // field, and a stub promising an omission the binding refuses would type-check at
                    // the call site and fail at run time -- `docs/design/pyi-generation-design.md` §3.2's
                    // rule about parameter names, applied to their defaults.
                    parameters = candidate.declaration.parameters.mapIndexed { index, parameter ->
                        parameter.copy(declaresDefault = plan.valueIndices[index] in omittableNow)
                    },
                ),
            )
        }
    }

    private fun anySiblingAlsoAccepts(
        candidate: Candidate,
        plan: DefaultOmissionPlan,
        siblings: List<Candidate>,
        omitted: Set<Int>,
    ): Boolean {
        val callable = candidate.callable ?: return false
        val written = plan.valueIndices.filter { it !in omitted }
            .map { callable.paramNames[it] to callable.paramTypeNames[it] }
        return siblings.any { sibling ->
            val other = sibling.callable ?: return@any false
            if (other.receiverTypeName != callable.receiverTypeName) return@any false
            val otherValues = other.paramNames.indices
                .filter { other.paramNames[it] != RECEIVER_PARAMETER_NAME }
                .map { Triple(other.paramNames[it], other.paramTypeNames.getOrNull(it), plan.siblingDeclares(sibling, it)) }
            written.all { (name, type) -> otherValues.any { it.first == name && it.second == type } } &&
                otherValues.filterNot { written.any { w -> w.first == it.first && w.second == it.second } }
                    .all { it.third }
        }
    }

    /** The sibling's *declared* defaults, which is Kotlin's view and therefore the one that decides
     * applicability. `ArtifactCallable.paramHasDefault` is the binding's view and is not it -- at
     * this point in the walk it is still all `false` for everyone. */
    private fun DefaultOmissionPlan.siblingDeclares(sibling: Candidate, index: Int): Boolean {
        val receiverIndex = if (sibling.callable?.receiverTypeName != null) 1 else 0
        return sibling.declaration.parameters.getOrNull(index - receiverIndex)?.declaresDefault ?: false
    }

    private fun List<Int>.powerSet(): List<Set<Int>> =
        (0 until (1 shl size)).map { mask -> filterIndexed { bit, _ -> (mask shr bit) and 1 == 1 }.toSet() }

    /**
     * One `when` over which defaulted slots arrived as `null`, with one call expression per subset.
     *
     * The subject is a bitmask built from the sentinel tests themselves rather than a chain of
     * `if`s, so the generated text is 2^n *branches* but only n null tests, and reads as a table of
     * the omission sets rather than as nested conditionals. Mask bit `j` is set when the `j`-th
     * omittable slot was **left out**, so mask `0` is [allPresentCall] -- unchanged from what this
     * generator emitted before -- and the all-omitted mask becomes the `else` Kotlin requires.
     *
     * A mask outside [allowed] is a call [applyDefaultOmission] refused to generate. It becomes a
     * `throw` rather than a silent fallback to the all-present call, which would pass a `null` into
     * a cast and fail somewhere else entirely.
     */
    private fun presenceBranchedCall(
        function: ResolvedFunction,
        owner: String,
        alias: String,
        argumentExpressions: List<String>,
        receiverIndex: Int,
        qualifiedName: String,
        ordered: List<Int>,
        allPresentCall: String,
        allowed: Set<Set<Int>>,
    ): String {
        val names = function.allParameterNames
        val subject = ordered.mapIndexed { bit, index ->
            "(if (args[$index] == null) ${1 shl bit} else 0)"
        }.joinToString(" + ")

        fun callOmitting(omitted: Set<Int>): String {
            val arguments = argumentExpressions.indices
                .filter { it >= receiverIndex && it !in omitted }
                .joinToString(", ") { "${writeParameterName(names[it])} = ${argumentExpressions[it]}" }
            return if (function.isExtension) "${argumentExpressions.first()}.$alias($arguments)"
            else "$owner.${function.kotlinName}($arguments)"
        }

        val last = (1 shl ordered.size) - 1
        return buildString {
            appendLine("when ($subject) {")
            for (mask in 0..last) {
                val omitted = ordered.filterIndexed { bit, _ -> (mask shr bit) and 1 == 1 }.toSet()
                val label = if (mask == last) "else" else "$mask"
                val branch = when {
                    mask == 0 -> allPresentCall
                    omitted in allowed -> callOmitting(omitted)
                    else -> "throw IllegalArgumentException(" +
                        "\"$qualifiedName: leaving out " +
                        omitted.joinToString(" and ") { names[it] } +
                        " is ambiguous with another overload of the same name; write ${
                            if (omitted.size == 1) "it" else "at least one of them"
                        }\")"
                }
                appendLine("    $label -> $branch")
            }
            append("}")
        }
    }

    /** Kotlin's hard keywords: a parameter declared with one carries backticks in source and none in
     * `@Metadata`, so a named argument written from the metadata name alone would not parse. */
    private val KOTLIN_HARD_KEYWORDS = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
        "try", "typealias", "typeof", "val", "var", "when", "while",
    )

    private fun isWritableParameterName(name: String): Boolean =
        name.isNotEmpty() && name.first().let { it.isLetter() || it == '_' } &&
            name.all { it.isLetterOrDigit() || it == '_' }

    private fun writeParameterName(name: String): String =
        if (name in KOTLIN_HARD_KEYWORDS) "`$name`" else name

    private fun candidateFromDescriptor(owner: String, methodName: String, descriptor: String): Candidate? {
        val (paramDescriptors, returnDescriptor) = splitMethodDescriptor(descriptor)
        val returnType = boundaryTypeOf(returnDescriptor) ?: return null
        val paramTypes = paramDescriptors.map { boundaryTypeOf(it) ?: return null }
        if (paramTypes.any { it.isReturnOnly }) return null

        val argumentExpressions = paramTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val call = "$owner.$methodName(${argumentExpressions.joinToString(", ")})"
        val body = returnType.wrapReturn(call)
        val paramTypeNames = paramDescriptors.map { kotlinNameOfAdmittedDescriptor(it) }
        val returnTypeName = kotlinNameOfAdmittedDescriptor(returnDescriptor)
        val callable = ArtifactCallable(
            name = "$owner.$methodName",
            arity = paramTypes.size,
            paramTags = paramTypes.map { it.tag },
            returnTag = returnType.tag,
            // Arity 0 has no `args` to name, exactly as `FragmentScanner`'s static-getter bodies do.
            lambdaBody = if (paramTypes.isEmpty()) "{ $body }" else "{ args -> $body }",
            // `paramNames` stays empty: a Java class file carries parameter names only when it was
            // compiled with `-parameters`, and JUnit 4 was not. Empty means "not supplied" (see
            // `ExposedCallable.paramNames`), which is the truth here rather than an invented `arg0`.
            paramTypeNames = paramTypeNames,
            returnTypeName = returnTypeName,
        )
        return Candidate(
            callable = callable,
            declaration = DeclarationModel(
                simpleName = methodName,
                owner = owner,
                ownerIsClass = true,
                receiver = null,
                // `docs/design/pyi-generation-design.md` §3.2: the names are genuinely absent, and a wrong
                // keyword name is worse than no keyword name because it type-checks at the call site
                // and fails at run time.
                parameters = paramTypeNames.mapIndexed { index, name ->
                    DeclaredParameter(null, KotlinTypeModel(name), declaresDefault = false, boundaryTag = paramTypes[index].tag)
                },
                returnType = KotlinTypeModel(returnTypeName),
                returnBoundaryTag = returnType.tag,
                bindingName = "$owner.$methodName",
                parameterNamesKnown = false,
            ),
        )
    }

    /**
     * The Kotlin spelling of a descriptor [boundaryTypeOf] has **already admitted**, which is the
     * only reason this can be total: the admitted set is the primitives, `String`, `byte[]` and
     * `void`, all of which have a Kotlin name. It is not a general descriptor-to-Kotlin mapping --
     * see [boundaryTypeOf]'s KDoc for why no such mapping exists.
     */
    private fun kotlinNameOfAdmittedDescriptor(descriptor: String): String = when (descriptor) {
        "Z" -> "kotlin.Boolean"
        "B" -> "kotlin.Byte"
        "S" -> "kotlin.Short"
        "I" -> "kotlin.Int"
        "J" -> "kotlin.Long"
        "F" -> "kotlin.Float"
        "D" -> "kotlin.Double"
        "Ljava/lang/String;" -> "kotlin.String"
        "[B" -> "kotlin.ByteArray"
        "V" -> "kotlin.Unit"
        else -> error("descriptor $descriptor is not one boundaryTypeOf admits")
    }

    private fun Int.hasFlag(flag: Int): Boolean = (this and flag) != 0
}
