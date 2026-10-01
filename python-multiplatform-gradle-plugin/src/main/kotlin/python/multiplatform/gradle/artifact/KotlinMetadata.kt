package python.multiplatform.gradle.artifact

import kotlin.metadata.KmClass
import kotlin.metadata.KmClassifier
import kotlin.metadata.KmConstructor
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmFunction
import kotlin.metadata.KmType
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.isNullable
import kotlin.metadata.isSuspend
import kotlin.metadata.isValue
import kotlin.metadata.jvm.JvmMethodSignature
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarFile

/**
 * The part of the walker that reads `@Metadata`'s `d1`/`d2` payload -- the facts
 * [ArtifactScanner]'s KDoc names as ASM's limit: a multi-file facade's part classes, an extension
 * function's receiver, a value class's underlying type and public accessor, `suspend`.
 *
 * Split from `ArtifactScanner` because it has its own dependency (`kotlin-metadata-jvm`, see the
 * plugin's `build.gradle.kts`) and its own unit of correctness: given a class's bytes, what did the
 * compiler actually declare, independent of what the walker chooses to bind.
 */

private const val KOTLIN_METADATA_DESCRIPTOR = "Lkotlin/Metadata;"

/**
 * Every jar (or, for this module's own tests, every directory of `.class` files) a build resolved,
 * indexed once so a classifier can be looked up regardless of which root it actually lives in.
 *
 * Why this has to span more than the one jar being walked: `androidx.compose.foundation.layout`'s
 * `Modifier.padding(Dp)` declares `Dp` as a parameter, and `Dp` is compiled into
 * `androidx.compose.ui.unit`, a different artefact. A value class's underlying type and accessor
 * cannot be read without finding the class that declares them, and single-jar resolution would
 * decline every cross-artefact value-class parameter -- which is most of Compose's public surface.
 */
internal class ArtifactClasspath(roots: List<File>) {

    private val locationsByBinaryName: Map<String, File> by lazy {
        val map = LinkedHashMap<String, File>()
        roots.forEach { root ->
            when {
                root.isDirectory -> root.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { file ->
                        map.putIfAbsent(file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class"), root)
                    }
                root.isFile && root.name.endsWith(".jar") -> runCatching {
                    JarFile(root).use { jarFile ->
                        jarFile.entries().asSequence()
                            .filter { it.name.endsWith(".class") }
                            .forEach { entry -> map.putIfAbsent(entry.name.removeSuffix(".class"), root) }
                    }
                }
            }
        }
        map
    }

    private val nodeCache = mutableMapOf<String, ClassNode?>()

    /** @param binaryName e.g. `androidx/compose/ui/unit/Dp` -- no `.class` suffix. */
    fun classNode(binaryName: String): ClassNode? = nodeCache.getOrPut(binaryName) {
        val root = locationsByBinaryName[binaryName] ?: return@getOrPut null
        val bytes = readBytes(root, binaryName) ?: return@getOrPut null
        readClassNodeOrNull(bytes)
    }

    private fun readBytes(root: File, binaryName: String): ByteArray? = if (root.isDirectory) {
        root.resolve("$binaryName.class").takeIf { it.isFile }?.readBytes()
    } else {
        runCatching {
            JarFile(root).use { jarFile ->
                jarFile.getJarEntry("$binaryName.class")?.let { entry -> jarFile.getInputStream(entry).use { it.readBytes() } }
            }
        }.getOrNull()
    }
}

/**
 * A class file the running ASM cannot parse is skipped, not fatal -- see [ArtifactScanner]'s own
 * copy of this reasoning. Shared because [ArtifactClasspath] needs it for classes outside the jar
 * currently being walked.
 */
internal fun readClassNodeOrNull(bytes: ByteArray): ClassNode? = try {
    ClassNode().also {
        org.objectweb.asm.ClassReader(bytes).accept(
            it,
            org.objectweb.asm.ClassReader.SKIP_CODE or org.objectweb.asm.ClassReader.SKIP_DEBUG or org.objectweb.asm.ClassReader.SKIP_FRAMES,
        )
    }
} catch (_: IllegalArgumentException) {
    null
}

/** `null` when the class carries no `kotlin.Metadata` at all (a Java class), or when the payload
 * cannot be decoded (a metadata version this library does not understand -- declined, not fatal,
 * the same policy [readClassNodeOrNull] applies to bytecode ASM cannot parse). */
internal fun kotlinClassMetadataOf(node: ClassNode): KotlinClassMetadata? {
    val annotation = node.visibleAnnotations?.firstOrNull { it.desc == KOTLIN_METADATA_DESCRIPTOR } ?: return null
    val header = buildMetadataHeader(annotation) ?: return null
    return try {
        KotlinClassMetadata.readLenient(header)
    } catch (_: Exception) {
        null
    }
}

/**
 * Reconstructs a real `kotlin.Metadata` annotation instance from ASM's flat name/value view of it,
 * so `KotlinClassMetadata.readLenient` -- which wants the annotation, not its fields -- can decode
 * it. `kotlin.Metadata` is `RUNTIME`-retained (see [ArtifactScanner]'s KDoc), so every field ASM
 * hands back is exactly what the compiler wrote.
 */
private fun buildMetadataHeader(annotation: AnnotationNode): kotlin.Metadata? {
    val values = annotation.values ?: return null
    var kind: Int? = null
    var metadataVersion: IntArray? = null
    var data1: Array<String>? = null
    var data2: Array<String>? = null
    var extraString: String? = null
    var packageName: String? = null
    var extraInt: Int? = null
    var i = 0
    while (i < values.size - 1) {
        when (values[i] as? String) {
            "k" -> kind = values[i + 1] as? Int
            "mv" -> metadataVersion = (values[i + 1] as? List<*>)?.filterIsInstance<Int>()?.toIntArray()
            "d1" -> data1 = (values[i + 1] as? List<*>)?.filterIsInstance<String>()?.toTypedArray()
            "d2" -> data2 = (values[i + 1] as? List<*>)?.filterIsInstance<String>()?.toTypedArray()
            "xs" -> extraString = values[i + 1] as? String
            "pn" -> packageName = values[i + 1] as? String
            "xi" -> extraInt = values[i + 1] as? Int
        }
        i += 2
    }
    return kotlin.metadata.jvm.Metadata(
        kind = kind,
        metadataVersion = metadataVersion,
        data1 = data1,
        data2 = data2,
        extraString = extraString,
        packageName = packageName,
        extraInt = extraInt,
    )
}

/** Which side of the boundary a Kotlin type is being resolved for -- a value class needs a public
 * constructor to be read as a parameter, and a public accessor besides to be wrapped as a return. */
internal enum class BoundaryDirection { PARAMETER, RETURN }

/**
 * What a jar says about one `@JvmInline value class`: its own qualified name, its underlying type
 * (recursion base case: `kotlinPrimitiveBoundaryTypeOf` bottoms out, or another value class does),
 * and whether the two operations this walker needs -- construct one, read its wrapped value back
 * out -- are even public.
 *
 * `kotlin.time.Duration` is the case that motivates [propertyIsPublic] and [constructorIsPublic]
 * both existing rather than one boolean: its constructor is `internal`, so nothing outside the
 * stdlib module can ever construct one, in *either* direction. See
 * `ValueClassFixtures.kt`'s `Meters` for the shape that has both public.
 */
internal data class ValueClassInfo(
    val qualifiedName: String,
    val underlyingType: KmType,
    val constructorIsPublic: Boolean,
    val propertyName: String?,
    val propertyIsPublic: Boolean,
)

internal fun ArtifactClasspath.valueClassInfo(binaryName: String): ValueClassInfo? {
    val node = classNode(binaryName) ?: return null
    val metadata = kotlinClassMetadataOf(node) as? KotlinClassMetadata.Class ?: return null
    val kmClass = metadata.kmClass
    if (!kmClass.isValue) return null
    val underlyingType = kmClass.inlineClassUnderlyingType ?: return null
    val propertyName = kmClass.inlineClassUnderlyingPropertyName
    val constructorIsPublic = kmClass.constructors.any { it.visibility == Visibility.PUBLIC && it.valueParameters.size == 1 }
    val propertyIsPublic = propertyName != null &&
        kmClass.properties.any { it.name == propertyName && it.visibility == Visibility.PUBLIC }
    return ValueClassInfo(
        qualifiedName = binaryName.replace('/', '.'),
        underlyingType = underlyingType,
        constructorIsPublic = constructorIsPublic,
        propertyName = propertyName,
        propertyIsPublic = propertyIsPublic,
    )
}

/**
 * A [BoundaryType] together with **what the boundary actually carries** for it.
 *
 * The two are not the same statement and only one of them can be read off a [BoundaryType]. A `Color`
 * parameter's boundary type is a chain -- `Color(x as kotlin.ULong)` -- whose *tag* is `OBJECT` and
 * whose read expression is Kotlin source. Generated Kotlin needs the second; a generated **thunk**
 * (`ComposableThunks.kt`) needs the first, because it has to turn the object the handle table hands
 * back into the erased JVM value the callee's descriptor declares, and to do that it has to know
 * which class that object is an instance of.
 *
 * [carrier] is the classifier [resolveKotlinBoundary]'s descent **stopped** at, in the same
 * `/`-separated spelling `KmClassifier.Class` uses, or `null` when the descent bottomed out in a
 * Kotlin primitive (the value then arrives boxed as the width `TypeTag` promises -- `Long`, `Double`,
 * `Boolean`, `String`, `ByteArray` -- which needs no class name to unbox).
 *
 * Deliberately produced by the *same* descent rather than by a second function that reproduces it:
 * a carrier that disagreed with the tag would be a thunk casting to a class the boundary never sends.
 */
internal class ResolvedBoundary(val boundary: BoundaryType, val carrier: String?)

/**
 * The Kotlin-type half of [boundaryTypeOf]'s policy: a declaration is bound only if every type in
 * its signature resolves here, and this is the whole of what "resolves" means -- a primitive Kotlin
 * type, or a value class whose relevant operation ([direction]) is public, unwrapped one level
 * (recursively, in case that value class wraps another).
 *
 * Deliberately not "wraps [boundaryTypeOf]": that function keys off a JVM descriptor, which is
 * exactly the erased view a value class parameter lies about (see [ArtifactScanner]'s KDoc). This
 * keys off the Kotlin type metadata actually declares.
 */
internal fun resolveKotlinType(type: KmType, classpath: ArtifactClasspath, direction: BoundaryDirection): BoundaryType? =
    resolveKotlinBoundary(type, classpath, direction)?.boundary

/** [resolveKotlinType], keeping the carrier the descent stopped at. See [ResolvedBoundary]. */
internal fun resolveKotlinBoundary(
    type: KmType,
    classpath: ArtifactClasspath,
    direction: BoundaryDirection,
): ResolvedBoundary? {
    val classifier = type.classifier as? KmClassifier.Class ?: return null
    // A nullable *primitive* or value class stays declined. `TypeTag.INT` carries a `Long` and the
    // read narrows it (`(args[0] as Long).toInt()`), so a `null` arriving for an `Int?` would throw
    // inside the cast rather than reach the declaration -- and a nullable value class boxes, which
    // changes what the JVM method takes. A nullable *object* has neither problem: the handle is
    // already nullable at the boundary (`UpcallTrampoline.toKotlin` maps Python `None` to `null`)
    // and the cast simply carries the `?`.
    if (!type.isNullable) {
        kotlinPrimitiveBoundaryTypeOf(classifier.name)?.let { return ResolvedBoundary(it, carrier = null) }
    } else {
        nullablePrimitiveBoundaryTypeOf(classifier.name)?.let { return ResolvedBoundary(it, carrier = null) }
        if (kotlinPrimitiveBoundaryTypeOf(classifier.name) != null) return null
    }

    val info = if (type.isNullable) null else classpath.valueClassInfo(classifier.name)
    if (info != null) {
        val underlying = resolveKotlinBoundary(info.underlyingType, classpath, direction)
        val usable = when (direction) {
            BoundaryDirection.PARAMETER -> info.constructorIsPublic
            BoundaryDirection.RETURN -> info.propertyIsPublic && info.propertyName != null
        }
        // Falls through to the object handle below when the wrapper cannot be opened from outside
        // its module rather than declining outright: `kotlin.time.Duration` and
        // `androidx.compose.ui.unit.TextUnit` still cannot be *built from a raw number* -- which is
        // the whole of what an `internal` constructor forbids and what
        // `docs/design/kotlin-extensions-in-python.md` §4.4 insists on -- but an instance that came out of
        // Kotlin can still be carried back into Kotlin, which is what a handle is for.
        if (underlying != null && usable) {
            return ResolvedBoundary(
                valueClassBoundaryType(info.qualifiedName, underlying.boundary, info.propertyName),
                // The wrapper is opened, so what crosses is whatever the *underlying* type crosses
                // as: `Color` is unwrapped to its `ULong`, and a `ULong` is what the handle holds.
                carrier = underlying.carrier,
            )
        }
    }
    return objectBoundaryTypeOrNull(type, classpath)?.let { ResolvedBoundary(it, carrier = classifier.name) }
}

/**
 * The descriptor `unbox-impl` returns on a `@JvmInline value class`'s **box** class, or `null` when
 * this classifier has no such method -- which is every class that is not one.
 *
 * Read off the class file rather than derived from [ValueClassInfo.underlyingType], because the two
 * can differ by a level of erasure that only the bytecode settles: `Color` wraps a `ULong`, `ULong`
 * wraps a `Long`, and `Color.unbox-impl()` returns **`J`** -- the fully erased representation, not
 * `Lkotlin/ULong;`. A thunk that predicted the intermediate would emit an `INVOKEVIRTUAL` whose
 * return type does not match the slot it feeds, and the JVM's verifier would reject the generated
 * class at load time with nothing said at build time.
 */
internal fun ArtifactClasspath.valueClassUnboxDescriptor(kotlinInternalName: String): String? {
    val node = classNode(kotlinInternalName.replace('.', '$')) ?: return null
    val method = node.methods?.firstOrNull {
        it.name == VALUE_CLASS_UNBOX_METHOD &&
            it.desc.startsWith("()") &&
            (it.access and org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0 &&
            (it.access and org.objectweb.asm.Opcodes.ACC_STATIC) == 0
    } ?: return null
    return method.desc.substringAfter(')')
}

/** The accessor `kotlinc` emits on every `@JvmInline value class` box. Not a Kotlin name and never
 * spelled in generated source -- only in a generated `INVOKEVIRTUAL`, which is resolved by the JVM's
 * own linker exactly as `kotlinc`'s would be (`ComposableThunks.kt`'s KDoc, "why this is not looking
 * a method up by name"). */
internal const val VALUE_CLASS_UNBOX_METHOD = "unbox-impl"

/**
 * Every public class and interface [kotlinInternalName] **is a**, nearest first, `java.lang.Object`
 * excluded.
 *
 * ### Why the walker answers this and `pythonx` cannot
 *
 * `docs/design/kotlin-extensions-in-python.md`'s coercion compares an owned value's declared type name with
 * the slot's for equality, so `BitmapPainter` is refused where a `Painter` is wanted. Python holds
 * *names*: a handle is an integer, `TypeTag.OBJECT` says nothing about what it points at, and the
 * five targets have no shared reflection to ask (agent-rules §12 -- dynamic binding is a retired
 * option, and Kotlin/Native has no `KClass.isInstance` for a name). The walker, on the other hand,
 * has the class files open already: `ClassNode.superName` and `ClassNode.interfaces` are the answer,
 * and reading them costs one already-cached [classNode] per link.
 *
 * ### Which direction is carried, and why it is the small one
 *
 * The **value's** ancestry, not the slot's subtypes. A `Painter` slot accepts every `Painter` there
 * will ever be, which is unbounded and not knowable from one walk; a produced value has exactly one
 * finite chain. So this is asked once per *declared return type* that crosses as a handle, and the
 * result rides on that declaration's `returnTypeName`.
 *
 * ### What is excluded, and why each one
 *
 * - `java.lang.Object`, which every class has and no slot is ever declared as (`kotlin.Any` is a
 *   built-in with no class file, so `objectBoundaryTypeOrNull` already declines a parameter of it).
 *   Keeping it would add one entry to every chain and match nothing.
 * - a supertype that is not JVM-public, or that is Kotlin-`internal` -- the same rule
 *   [isNameablePublicClass] applies, and for the same reason: a name outside the module may not say
 *   it, so no slot can be declared as it either.
 * - anything past [limit] links. Not a defence against a hostile input -- these are jars the build
 *   resolved -- but the cost is paid at build time on every build, and nothing measured comes close:
 *   see `ReturnSupertypeTest.theAncestryTheTableCarriesIsMeasuredRatherThanAssumed`.
 *
 * A binary name becomes a Kotlin one the way metadata spells it: `/` and `$` both become `.`. A JVM
 * supertype Kotlin renames (`java.util.List` for `kotlin.collections.List`) therefore comes out under
 * its JVM name and simply never matches a slot, which is the honest failure -- a slot declared
 * `kotlin.collections.List` is declined by `objectBoundaryTypeOrNull` anyway.
 */
internal fun ArtifactClasspath.nameablePublicSupertypesOf(kotlinInternalName: String, limit: Int = 24): List<String> {
    val start = classNode(kotlinInternalName.replace('.', '$')) ?: return emptyList()
    val found = LinkedHashSet<String>()
    val pending = ArrayDeque<ClassNode>()
    pending.addLast(start)
    while (pending.isNotEmpty() && found.size < limit) {
        val node = pending.removeFirst()
        val parents = listOfNotNull(node.superName) + node.interfaces.orEmpty()
        parents.forEach { binaryName ->
            if (binaryName == JAVA_LANG_OBJECT) return@forEach
            val parent = classNode(binaryName) ?: return@forEach
            if ((parent.access and org.objectweb.asm.Opcodes.ACC_PUBLIC) == 0) return@forEach
            val metadata = kotlinClassMetadataOf(parent) as? KotlinClassMetadata.Class
            if (metadata != null && metadata.kmClass.visibility != Visibility.PUBLIC) return@forEach
            if (found.add(binaryName.replace('/', '.').replace('$', '.'))) pending.addLast(parent)
        }
    }
    return found.toList()
}

private const val JAVA_LANG_OBJECT = "java/lang/Object"

/**
 * The object-handle boundary type: `docs/design/kotlin-extensions-in-python.md` §6's "type gate", and the
 * second of the two things that independently held Compose at zero.
 *
 * ### What crosses
 *
 * Nothing of the object does. `python.multiplatform.reflection.TypeTag.OBJECT` is already a complete
 * marshalling category on both sides of the boundary and has been since the upcall trampoline was
 * written: `UpcallTrampoline.marshalResult` puts a returned Kotlin object into
 * `python.multiplatform.reflection.HandleTable` and hands Python the resulting integer, and
 * `toKotlinObject` resolves that integer back to the very same instance on the way in. Python never
 * sees a Kotlin reference, which is the only shape that works on all five targets (see
 * `ObjectReference`'s KDoc). KSP's own `tagFor` has emitted `OBJECT` for every non-primitive since it
 * existed; this is the walker finally being able to do the same.
 *
 * ### Why this could not be done from a JVM descriptor
 *
 * A cast needs a **Kotlin type name**, and that is exactly what [boundaryTypeOf] does not have: a
 * descriptor says `Ljava/util/List;`, whose Kotlin spelling is a different name (`kotlin.collections
 * .List`) that the compiler refuses to accept written out as `java.util.List`. `@Metadata`'s
 * classifier is already the Kotlin name -- `androidx/compose/ui/Modifier`, nested classes spelled
 * `Outer.Inner` -- so the only transformation needed is `/` to `.`. That is why this lives here and
 * not beside the descriptor table, and why the Java-class path in `ArtifactScanner` still has no
 * `OBJECT` case.
 *
 * ### Lifetime
 *
 * A handle is a **strong root** and the table cannot tell that Python has finished with it, so
 * exactly one of two things has to give it back (`HandleTable`'s own KDoc states the contract):
 *
 * - a value returned into a **generated proxy class** is released by that class's `__del__`;
 * - a value returned from a plain `CallableKind.FUNCTION` -- which is every entry this walker emits
 *   -- reaches Python as a **bare integer**, and `PythonProxySource`'s KDoc already records that
 *   such a handle "is the caller's to release": an integer has nothing to hang a finaliser off, so
 *   the caller must pass it to `_pm_release`.
 *
 * A chained `Modifier.padding(...).size(...)` therefore leaks one handle per intermediate link until
 * the Python surface of `docs/design/kotlin-extensions-in-python.md` §4.1 exists to own them. That is a
 * known, bounded cost of this step and not a defect introduced by it -- the same is already true of
 * every `OBJECT`-returning KSP entry -- but it is the reason this KDoc says so rather than leaving it
 * to be discovered.
 *
 * ### What is still declined
 *
 * A classifier this walker cannot **find on the classpath as a public class**. That declines Kotlin's
 * built-ins as a side effect, because they have no class file of their own anywhere -- `kotlin.Any`,
 * `kotlin.collections.List` and `kotlin.Function1` are all mapped onto JVM types and exist only in
 * `.kotlin_builtins` metadata. Declining them is also the right answer independently: a Python
 * callable cannot become a `Function1` (the trampoline would hand the cast a `PyObject`), and a
 * `List` parameter would need a collection conversion the boundary does not have. Being unable to see
 * the class is the mechanism; both would have to be declined anyway.
 */
private fun objectBoundaryTypeOrNull(type: KmType, classpath: ArtifactClasspath): BoundaryType? {
    val rendered = renderKotlinTypeName(type, classpath) ?: return null
    return BoundaryType("OBJECT", "(%s as $rendered)", "(%s)")
}

/**
 * The Kotlin-source spelling of [type], type arguments included, or `null` if any part of it is
 * something generated code must not write.
 *
 * Type arguments are rendered rather than dropped because a cast to a bare generic name is not valid
 * Kotlin ("One type argument expected") -- the same trap `python.multiplatform.ksp.TypeShape.rendered`
 * exists for, and one that surfaces as a compile failure of the *generated* file rather than as
 * anything the generator could notice.
 */
private fun renderKotlinTypeName(type: KmType, classpath: ArtifactClasspath): String? {
    val classifier = type.classifier as? KmClassifier.Class ?: return null
    if (!classpath.isNameablePublicClass(classifier.name)) return null
    val base = classifier.name.replace('/', '.')
    if (type.arguments.isEmpty()) return base + if (type.isNullable) "?" else ""
    val rendered = ArrayList<String>(type.arguments.size)
    for (argument in type.arguments) {
        val argumentType = argument.type
        // A star projection is spellable as-is; anything else has to be a nameable type.
        rendered += if (argumentType == null) "*" else renderKotlinTypeName(argumentType, classpath) ?: return null
    }
    return "$base<${rendered.joinToString(", ")}>" + if (type.isNullable) "?" else ""
}

/** Whether generated Kotlin in another module may write this classifier's name: it has to exist as
 * a class file this walk can see, be JVM-public, and -- when it is Kotlin -- be Kotlin-public too
 * (`internal` is JVM-public and is not a name anybody outside the module may say). */
internal fun ArtifactClasspath.isNameablePublicClass(kotlinInternalName: String): Boolean {
    // Metadata spells a nested class `Outer.Inner`; its class file is `Outer$Inner`.
    val node = classNode(kotlinInternalName.replace('.', '$')) ?: return false
    if ((node.access and org.objectweb.asm.Opcodes.ACC_PUBLIC) == 0) return false
    val metadata = kotlinClassMetadataOf(node) as? KotlinClassMetadata.Class ?: return true
    return metadata.kmClass.visibility == Visibility.PUBLIC
}

private fun valueClassBoundaryType(qualifiedName: String, underlying: BoundaryType, propertyName: String?): BoundaryType =
    BoundaryType(
        tag = underlying.tag,
        readFn = { slot -> "$qualifiedName(${underlying.read(slot)})" },
        wrapFn = { call ->
            val property = propertyName
                ?: error("$qualifiedName has no public accessor for its wrapped value; must not be used in return position")
            underlying.wrapReturn("($call).$property")
        },
    )

/** Kotlin's own names for the types [boundaryTypeOf] admits, keyed the way `KmClassifier.Class.name`
 * spells a top-level class: `/`-separated, no leading slash. One table, not two: both this and
 * [boundaryTypeOf] resolve to the same [BoundaryType] instances, because a JVM `I` and a Kotlin
 * `kotlin.Int` have to widen and narrow identically. */
/**
 * The two entries of [kotlinPrimitiveBoundaryTypeOf] that survive a `?`, and why the others do not.
 *
 * The nullable branch of [resolveKotlinType] declines every primitive on one stated reason: `TypeTag
 * .INT` carries a `Long` and the read *narrows* it (`(args[0] as Long).toInt()`), so a `null` would
 * throw inside the cast rather than reach the declaration. That reason is about the numeric widths
 * and it does not apply to a `String` or a `ByteArray`: their JVM shape is already a reference, their
 * read is a plain cast with nothing to narrow, and `UpcallTrampoline.toKotlin` already answers `null`
 * for a Python `None` **whatever the tag says** -- the one line that makes a nullable boundary work at
 * all. So the only thing that had to change is the `?` on the cast, and declining them was the rule
 * being coarser than its own justification.
 *
 * Measured cost of that coarseness: `Modifier.clickable`, `combinedClickable` and `mouseClickable`
 * were declined for `onClickLabel: String?` alone, after their `onClick` slot became bindable
 * (`FunctionSlotBindingTest`). A nullable `Boolean?`/`Int?` stays declined, and `ArtifactScannerTest`
 * keeps that pinned.
 */
private fun nullablePrimitiveBoundaryTypeOf(kotlinInternalName: String): BoundaryType? = when (kotlinInternalName) {
    "kotlin/String" -> BoundaryType("STRING", "(%s as String?)", "(%s)")
    "kotlin/ByteArray" -> BoundaryType("BYTES", "(%s as ByteArray?)", "(%s)")
    else -> null
}

internal fun kotlinPrimitiveBoundaryTypeOf(kotlinInternalName: String): BoundaryType? = when (kotlinInternalName) {
    "kotlin/Boolean" -> boundaryTypeOf("Z")
    "kotlin/Byte" -> boundaryTypeOf("B")
    "kotlin/Short" -> boundaryTypeOf("S")
    "kotlin/Int" -> boundaryTypeOf("I")
    "kotlin/Long" -> boundaryTypeOf("J")
    "kotlin/Float" -> boundaryTypeOf("F")
    "kotlin/Double" -> boundaryTypeOf("D")
    "kotlin/String" -> boundaryTypeOf("Ljava/lang/String;")
    "kotlin/ByteArray" -> boundaryTypeOf("[B")
    "kotlin/Unit" -> boundaryTypeOf("V")
    else -> null
}

/** One function's worth of what [ArtifactScanner] needs, already carrying its own JVM signature so
 * it can be matched back to the `MethodNode` that owns the bytecode. */
internal data class ResolvedFunction(
    val kotlinName: String,
    val isExtension: Boolean,
    val allParameterTypes: List<KmType>, // receiver (if [isExtension]) first, then declared value parameters
    val returnType: KmType,
    val jvmSignature: JvmMethodSignature,
    /** `null` unless [isExtension]; the same type that heads [allParameterTypes] when it is not. */
    val receiverType: KmType? = null,
    /** Aligned with [allParameterTypes]; the receiver slot is [RECEIVER_PARAMETER_NAME]. */
    val allParameterNames: List<String> = emptyList(),
    /** Aligned with [allParameterTypes]; the receiver slot is always `false` (a receiver cannot
     * declare a default). Read, never acted on -- see `ExposedCallable.paramHasDefault`. */
    val allParameterDefaults: List<Boolean> = emptyList(),
    /**
     * Carried rather than filtered out at the source, because the two consumers of this want
     * different things from it: the binder declines a `suspend` declaration outright, and the stub
     * model records it as declined-because-suspend (`docs/design/pyi-generation-design.md` §3.1 -- "declined
     * by both producers; must not be stubbed"). Dropping it here would make the second indistinguishable
     * from a declaration that was never declared.
     */
    val isSuspend: Boolean = false,
)

/** The name given to the extension-receiver slot. Deliberately not a Python identifier: a receiver
 * is positional in Kotlin too, so nothing should be able to address it by keyword. */
internal const val RECEIVER_PARAMETER_NAME = "<receiver>"

/**
 * A class's own public constructors, shaped as [ResolvedFunction] so [ArtifactScanner]'s existing
 * top-level-function path binds them with no code of its own: a constructor's [ResolvedFunction
 * .kotlinName] is the class's simple name and its [ResolvedFunction.returnType] is the class itself,
 * so the call [candidateFromFunction] already writes for a non-extension declaration -- `$owner.
 * ${'$'}{function.kotlinName}(args)` -- comes out as `androidx.compose.material3.Typography(args)`,
 * which is exactly how Kotlin spells a fully-qualified constructor call. No `isConstructor` branch
 * needed anywhere downstream.
 *
 * `<init>` never reaches [ArtifactScanner.kotlinCandidates]: that function's own `ACC_STATIC` filter
 * excludes it, a constructor never being `static`. That gap -- not a judgement about the class -- is
 * why `Typography` and `Shapes` were absent rather than declined; see this object's KDoc §10.
 *
 * @param ownerInternalName the class's own ASM binary name (`androidx/compose/material3/Typography`),
 *   used both as the returned type's classifier and, split on `/`, as the class's simple name.
 */
internal fun constructorsOf(kmClass: KmClass, ownerInternalName: String): List<ResolvedFunction> {
    val simpleName = ownerInternalName.substringAfterLast('/')
    val classType = KmType().apply { classifier = KmClassifier.Class(ownerInternalName) }
    return kmClass.constructors.mapNotNull { resolvedConstructorOrNull(it, simpleName, classType) }
}

private fun resolvedConstructorOrNull(constructor: KmConstructor, simpleName: String, classType: KmType): ResolvedFunction? {
    if (constructor.visibility != Visibility.PUBLIC) return null
    val signature = constructor.signature ?: return null
    return ResolvedFunction(
        kotlinName = simpleName,
        isExtension = false,
        allParameterTypes = constructor.valueParameters.map { it.type },
        returnType = classType,
        jvmSignature = signature,
        allParameterNames = constructor.valueParameters.map { it.name },
        allParameterDefaults = constructor.valueParameters.map { it.declaresDefaultValue },
    )
}

/**
 * The Kotlin package a facade's declarations really live in, when it is not the JVM one.
 *
 * `@file:JvmPackageName` moves the *class file* without moving the Kotlin declarations, and
 * `kotlin-stdlib-jdk8` uses it: `MatchGroupCollection.get(String)` is declared in Kotlin's
 * `kotlin.text` and compiled into `kotlin/text/jdk8/RegexExtensionsJDK8Kt`. Deriving the owner from
 * the binary name therefore produced `kotlin.text.jdk8.get`, and the import it generated
 * (`import kotlin.text.jdk8.get as ...`) named a package the Kotlin compiler has never heard of --
 * observed as `Unresolved reference 'get'` in a generated fragment, which is the failure mode
 * `boundaryTypeOf`'s KDoc warns about in general and this is a concrete instance of.
 *
 * `kotlin.Metadata`'s `pn` field exists for exactly this and is `null` whenever the two agree.
 */
internal fun kotlinPackageNameOverrideOf(node: ClassNode): String? {
    val annotation = node.visibleAnnotations?.firstOrNull { it.desc == KOTLIN_METADATA_DESCRIPTOR } ?: return null
    val values = annotation.values ?: return null
    var i = 0
    while (i < values.size - 1) {
        if (values[i] == "pn") return (values[i + 1] as? String)?.replace('/', '.')
        i += 2
    }
    return null
}

/** The Kotlin-source spelling of a classifier, or `null` for a type variable or a flexible type.
 * Metadata already spells a nested class `Outer.Inner` and a package with `/`, so only the package
 * separator changes -- which is exactly why a *metadata* name can be written into generated source
 * and a JVM descriptor cannot (see [boundaryTypeOf]'s KDoc). */
internal fun kotlinClassifierNameOf(type: KmType): String? =
    (type.classifier as? KmClassifier.Class)?.name?.replace('/', '.')

internal fun functionsOf(container: KmDeclarationContainer): List<ResolvedFunction> =
    container.functions.mapNotNull { function -> resolvedFunctionOrNull(function) }

private fun resolvedFunctionOrNull(function: KmFunction): ResolvedFunction? {
    if (function.visibility != Visibility.PUBLIC) return null
    val signature = function.signature ?: return null
    val receiver = function.receiverParameterType
    val allParams = listOfNotNull(receiver) + function.valueParameters.map { it.type }
    return ResolvedFunction(
        kotlinName = function.name,
        isExtension = receiver != null,
        allParameterTypes = allParams,
        returnType = function.returnType,
        jvmSignature = signature,
        receiverType = receiver,
        allParameterNames = (if (receiver != null) listOf(RECEIVER_PARAMETER_NAME) else emptyList()) +
            function.valueParameters.map { it.name },
        allParameterDefaults = (if (receiver != null) listOf(false) else emptyList()) +
            function.valueParameters.map { it.declaresDefaultValue },
        isSuspend = function.isSuspend,
    )
}

/**
 * The declared Kotlin type as `docs/design/pyi-generation-design.md` §2.2's model wants it -- qualified
 * name, nullability, type arguments, and value-class identity -- or `null` when the classifier is
 * something no stub can name (a type *parameter*, a flexible type).
 *
 * Deliberately separate from [resolveKotlinType], which answers a different question: that one says
 * *how a value marshals* and declines anything the boundary cannot carry, and this one says *what the
 * declaration says*. `Dp` is `TypeTag.FLOAT` there and `androidx.compose.ui.unit.Dp` wrapping
 * `kotlin.Float` here, and §2.2's whole argument is that a stub needs the second.
 *
 * [depth] bounds the recursion rather than trusting the input: a type argument list is attacker-free
 * here (it comes from a jar this build resolved) but the cost of a pathological generic signature is
 * paid at build time on every build, and nothing downstream needs more nesting than this.
 */
internal fun kotlinTypeModelOf(
    type: KmType,
    classpath: ArtifactClasspath,
    depth: Int = 0,
): python.multiplatform.gradle.model.KotlinTypeModel? {
    if (depth > 8) return null
    val classifier = type.classifier as? KmClassifier.Class ?: return null
    val qualifiedName = classifier.name.replace('/', '.')
    val arguments = type.arguments.map { argument ->
        val argumentType = argument.type ?: return@map null // a star projection
        kotlinTypeModelOf(argumentType, classpath, depth + 1)
    }
    val info = classpath.valueClassInfo(classifier.name)
    val valueClass = info?.let { value ->
        val underlying = kotlinTypeModelOf(value.underlyingType, classpath, depth + 1) ?: return@let null
        python.multiplatform.gradle.model.ValueClassModel(
            underlying = underlying,
            constructorIsPublic = value.constructorIsPublic,
            propertyIsPublic = value.propertyIsPublic,
        )
    }
    return python.multiplatform.gradle.model.KotlinTypeModel(
        qualifiedName = qualifiedName,
        isNullable = type.isNullable,
        arguments = arguments,
        valueClass = valueClass,
    )
}
