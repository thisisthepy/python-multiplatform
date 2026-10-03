package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **The two paths that typed the same declaration differently**, and the measurement that closes the
 * gap `ebe3365f` pinned.
 *
 * ### What the pin said
 *
 * `IconRenderTest.aComposablesColorSlotRefusesAPythonBuiltColorBecauseTheTwoPathsTypeItDifferently`
 * recorded that `Icon`'s `tint` and `AlertDialog`'s `containerColor` were tagged `INT` while
 * `lightColorScheme`'s 36 `Color` parameters -- same type, same jar -- were tagged `OBJECT`. Not a
 * mistake in either: an ordinary declaration is typed by `resolveKotlinType` from `@Metadata`, which
 * knows `Color` is a `@JvmInline value class`; a `@Composable`'s parameters were typed by
 * [ArtifactScanner.composableSlotTagOf] from the **JVM descriptor**, where a `Color` is the letter
 * `J` and indistinguishable from a `Long`.
 *
 * ### Why the composable path read the descriptor in the first place
 *
 * Because it has to. A composable's `$composer`/`$changed`/`$default` slots exist **only** in the
 * descriptor -- metadata does not declare them -- so the JVM parameter list is longer than the Kotlin
 * one and the descriptor is the only thing that can say what the trailing slots are. What did not
 * follow, and was the actual defect, is that the *declared* slots have to be typed from it too.
 *
 * ### How the two lists are paired, and what stops a wrong pairing being silent
 *
 * The pairing is positional and is **not** an assumption: [ComposableShape.of] has already checked
 * that JVM parameter [declaredCount] is the `Composer` and that everything after it is `int`, so JVM
 * slots `0 until declaredCount` are the Kotlin parameters, in order. That is the same shape of
 * argument [ArtifactScanner.functionSlotTypeName] makes for a function-typed slot: compare the two
 * arities, and act only on a relation that holds.
 *
 * On top of the arity relation, every paired slot is **cross-checked**: the Kotlin type's own
 * boundary (a primitive, a value class unwrapped to one, or an object handle) must be able to arrive
 * in the JVM slot the descriptor declares. A `Modifier` against a `J`, or a `Color` against a
 * `Ljava/lang/String;`, is refused rather than bound -- see
 * [everyComposableSlotAgreesBetweenMetadataAndDescriptorWhenPairedInOrder], which measures that the
 * check is satisfied by every composable three Compose jars declare, and
 * [aDeliberatelyShiftedPairingIsCaughtRatherThanBoundToTheWrongType], which shifts the pairing by one
 * and counts the refusals that follow.
 */
class ComposableValueClassSlotTest {

    private val caches: File? = listOf(
        File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
        File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
    ).firstOrNull { it.isDirectory }

    private fun jarUnder(group: String, artifact: String): File? = caches?.resolve(group)?.resolve(artifact)
        ?.walkTopDown()?.firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

    private val kotlinStdlibJar: File
        get() = File(Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location.toURI())

    private fun composeClasspath(): List<File> = listOfNotNull(
        jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
        jarUnder("org.jetbrains.compose.material", "material-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-text-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
        jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
        jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
        jarUnder("org.jetbrains.compose.runtime", "runtime-saveable-desktop"),
        jarUnder("org.jetbrains.compose.animation", "animation-core-desktop"),
        jarUnder("org.jetbrains.compose.collection-internal", "collection-desktop"),
        jarUnder("org.jetbrains.compose.annotation-internal", "annotation-desktop"),
        kotlinStdlibJar,
    )

    /**
     * **The claim.** `Icon`'s `tint` is a `Color` and must be tagged the way `lightColorScheme`'s
     * `Color` parameters already were -- `OBJECT`, carrying the same handle -- rather than `INT`,
     * which is what its erased `long` looks like.
     *
     * Asserted alongside a slot of the *same declaration* that must not move: `contentDescription`
     * is a `String?` and stays `STRING`, and `modifier` stays `OBJECT`. A change that simply tagged
     * every declared slot `OBJECT` would pass the first assertion and fail those.
     */
    @Test
    fun aComposablesValueClassSlotIsTypedFromMetadataAndNotFromItsErasedDescriptor() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            material3,
            includePrefixes = listOf("androidx.compose.material3"),
            classpath = composeClasspath(),
        )
        val icons = entries.filter { it.name.substringAfterLast('.').substringBefore("__") == "Icon" }
        assertTrue(icons.isNotEmpty(), "no Icon binding among ${entries.size} entries")

        // Not every `Icon` overload tints with a `Color`: 1.7.0 added one taking a
        // `ColorProducer?`, which is an ordinary interface and was already an object handle. The
        // claim is about the `Color`-typed ones, and that there are some.
        var colorTints = 0
        icons.forEach { icon ->
            val tint = icon.paramNames.indexOf("tint")
            assertTrue(tint >= 0, "${icon.name} has no tint slot: ${icon.paramNames}")
            if (icon.paramTypeNames[tint] == "androidx.compose.ui.graphics.Color") {
                colorTints++
                assertEquals(
                    "OBJECT", icon.paramTags[tint],
                    "${icon.name}'s tint is still typed from the erased descriptor",
                )
            }
            val description = icon.paramNames.indexOf("contentDescription")
            assertEquals("STRING", icon.paramTags[description], "a String? slot must not move")
            val modifier = icon.paramNames.indexOf("modifier")
            assertEquals("OBJECT", icon.paramTags[modifier], "a plain object slot must not move")
            // The synthetic slots keep being read off the descriptor, because nothing else declares
            // them: `$composer` is an object and the masks are ints.
            assertEquals(listOf("OBJECT", "INT", "INT"), icon.paramTags.takeLast(3))
        }
        assertTrue(colorTints > 0, "no Icon overload declares a Color tint, so this measures nothing")
    }

    /**
     * **A `Dp` slot must not move**, which is the other half of "typed from metadata": `Dp` is a
     * value class too, and `resolveKotlinType` *can* open it (public constructor, public accessor),
     * so it stays `FLOAT` and keeps arriving as a raw number the way `pythonx.dp(16)` sends one.
     *
     * A rule that had said "a value class is an object" would tag this `OBJECT` and break every
     * `dp(...)` argument to a composable, silently, at the call site.
     */
    @Test
    fun aValueClassTheWalkerCanOpenStaysAPrimitiveSlot() {
        val layout = jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            layout,
            includePrefixes = listOf("androidx.compose.foundation.layout"),
            classpath = composeClasspath(),
        )
        val spacers = entries.filter { it.name.substringAfterLast('.').substringBefore("__") == "Spacer" }
        assertTrue(spacers.isNotEmpty(), "no Spacer binding among ${entries.size} entries")

        val dpSlots = entries.flatMap { entry ->
            entry.paramTypeNames.indices
                .filter { entry.paramTypeNames[it] == "androidx.compose.ui.unit.Dp" }
                .map { entry.name to entry.paramTags[it] }
        }
        assertTrue(dpSlots.isNotEmpty(), "expected some composable to take a Dp")
        assertEquals(
            emptyList(), dpSlots.filter { it.second != "FLOAT" },
            "a Dp slot must stay FLOAT: its constructor and its accessor are both public",
        )
    }

    /**
     * The thunk is the other end of the same change, and without it the tag would be a lie: the JVM
     * method takes a `long`, and what the boundary now hands over for a `Color` is a **boxed**
     * `kotlin.ULong` -- the carrier `resolveKotlinType`'s descent stopped at, because `ULong`'s own
     * constructor is `internal`.
     *
     * So the thunk has to unwrap it, and the name it calls is not guessed: `unbox-impl` is read off
     * the carrier's own class file, together with the descriptor it returns, and the slot is only
     * upgraded when that method exists and returns exactly what the JVM slot wants.
     */
    @Test
    fun theThunkUnwrapsTheValueClassBoxTheBoundaryCarries() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            material3,
            includePrefixes = listOf("androidx.compose.material3"),
            classpath = composeClasspath(),
        )
        val icon = entries.first { it.name.substringAfterLast('.').substringBefore("__") == "Icon" }
        val thunk = assertNotNull(icon.thunk)
        val tint = icon.paramNames.indexOf("tint")
        assertEquals(
            "kotlin/ULong", thunk.valueClassUnboxOwners.getOrNull(tint),
            "the tint slot arrives as a boxed ULong and the thunk must unbox it",
        )
        assertEquals(
            null, thunk.valueClassUnboxOwners.getOrNull(icon.paramNames.indexOf("modifier")),
            "a plain object slot needs no unwrapping",
        )
        assertEquals(icon.arity, thunk.valueClassUnboxOwners.size, "one entry per JVM slot, or none")
    }

    /**
     * The pairing, measured over every public top-level `@Composable` three Compose jars declare:
     * paired **in order**, every declared Kotlin type's boundary can arrive in the JVM slot at the
     * same index.
     *
     * Prints the counts rather than pinning them, because they are facts about a Compose version;
     * fails on the first slot the two views disagree about.
     */
    @Test
    fun everyComposableSlotAgreesBetweenMetadataAndDescriptorWhenPairedInOrder() {
        val jars = composeJars()
        if (jars.isEmpty()) return
        val counts = agreementCounts(jars, shift = 0)
        println(
            "composable slots paired in order: ${counts.agreed} agree " +
                "(${counts.upgraded} upgraded to an object handle), ${counts.disagreed} disagree",
        )
        assertTrue(counts.agreed > 500, "expected the jars to declare composable slots; saw ${counts.agreed}")
        assertTrue(counts.upgraded > 0, "no slot was upgraded, so this measures nothing")
        assertEquals(0, counts.disagreed, "a composable slot the two views type incompatibly")
    }

    /**
     * The tamper: the same walk with the JVM descriptors shifted by one against the Kotlin types.
     * If the cross-check were vacuous this would produce the same zero.
     */
    @Test
    fun aDeliberatelyShiftedPairingIsCaughtRatherThanBoundToTheWrongType() {
        val jars = composeJars()
        if (jars.isEmpty()) return
        val shifted = agreementCounts(jars, shift = 1)
        println("composable slots paired one off: ${shifted.agreed} agree, ${shifted.disagreed} disagree")
        assertTrue(
            shifted.disagreed > 100,
            "a one-off pairing was accepted, only ${shifted.disagreed} refusals -- the cross-check is vacuous",
        )
    }

    /**
     * The emitted unwrapper really links and really runs, against a hand-built class whose
     * `unbox-impl` is spelled exactly the way `kotlinc` spells one -- with a hyphen, which is not a
     * Java or Kotlin identifier character, so nothing but bytecode could call it.
     *
     * Both halves are exercised, and the second is the one that would otherwise be found at run time
     * inside a composition: **`null` is an omitted argument**, not an error. A `@Composable`'s
     * omitted slot is still a real JVM parameter and `pythonx._absent` can only put `None` in an
     * `OBJECT` one; the callee overwrites it from the `$default` mask before reading it.
     */
    @Test
    fun theGeneratedUnwrapperOpensABoxAndTurnsAnOmittedSlotIntoAZero() {
        val boxInternal = "fixture/thunk/ValueBoxProbe"
        val targetInternal = "fixture/thunk/ValueBoxTarget"
        val bytes = generateThunkClass(
            "Boxed",
            listOf(
                ThunkSpec(
                    targetInternal,
                    "take-abc123",
                    "(JLjava/lang/String;)J",
                    valueClassUnboxOwners = listOf(boxInternal, null),
                ),
            ),
        )
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(name: String, data: ByteArray): Class<*> = defineClass(name, data, 0, data.size)
        }
        loader.define(boxInternal.replace('/', '.'), valueBoxProbeClass(boxInternal))
        loader.define(targetInternal.replace('/', '.'), valueBoxTargetClass(targetInternal))
        val thunks = loader.define("$THUNK_PACKAGE.BoxedThunks", bytes)

        val boxClass = loader.loadClass(boxInternal.replace('/', '.'))
        val box = boxClass.getMethod("box-impl", Long::class.javaPrimitiveType).invoke(null, 42L)
        val t0 = thunks.getMethod(thunkMethodName(0), Array<Any?>::class.java)
        assertEquals(42L, t0.invoke(null, arrayOf<Any?>(box, "x")))
        assertEquals(0L, t0.invoke(null, arrayOf<Any?>(null, "x")))
    }

    /** A stand-in for a `@JvmInline value class` box: one `long` field, a static `box-impl` and the
     * `unbox-impl` `kotlinc` emits. Written as bytecode because the name has a hyphen in it. */
    private fun valueBoxProbeClass(internalName: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_FINAL,
            internalName,
            null,
            "java/lang/Object",
            null,
        )
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PRIVATE or org.objectweb.asm.Opcodes.ACC_FINAL, "value", "J", null, null).visitEnd()
        writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PRIVATE, "<init>", "(J)V", null, null).apply {
            visitCode()
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            visitVarInsn(org.objectweb.asm.Opcodes.LLOAD, 1)
            visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, internalName, "value", "J")
            visitInsn(org.objectweb.asm.Opcodes.RETURN)
            visitMaxs(0, 0); visitEnd()
        }
        writer.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC,
            "box-impl", "(J)L$internalName;", null, null,
        ).apply {
            visitCode()
            visitTypeInsn(org.objectweb.asm.Opcodes.NEW, internalName)
            visitInsn(org.objectweb.asm.Opcodes.DUP)
            visitVarInsn(org.objectweb.asm.Opcodes.LLOAD, 0)
            visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, internalName, "<init>", "(J)V", false)
            visitInsn(org.objectweb.asm.Opcodes.ARETURN)
            visitMaxs(0, 0); visitEnd()
        }
        writer.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_FINAL,
            VALUE_CLASS_UNBOX_METHOD, "()J", null, null,
        ).apply {
            visitCode()
            visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
            visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, internalName, "value", "J")
            visitInsn(org.objectweb.asm.Opcodes.LRETURN)
            visitMaxs(0, 0); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** `take-abc123(long, String): long` -- returns the long, so the thunk's answer is the unwrapped
     * value and nothing else. */
    private fun valueBoxTargetClass(internalName: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
        writer.visit(
            org.objectweb.asm.Opcodes.V1_8,
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_FINAL,
            internalName, null, "java/lang/Object", null,
        )
        writer.visitMethod(
            org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_STATIC,
            "take-abc123", "(JLjava/lang/String;)J", null, null,
        ).apply {
            visitCode()
            visitVarInsn(org.objectweb.asm.Opcodes.LLOAD, 0)
            visitInsn(org.objectweb.asm.Opcodes.LRETURN)
            visitMaxs(0, 0); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun composeJars(): List<File> = listOfNotNull(
        jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
        jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
        jarUnder("org.jetbrains.compose.material", "material-desktop"),
    )

    private class Counts(var agreed: Int = 0, var upgraded: Int = 0, var disagreed: Int = 0)

    /**
     * @param shift how far to slide the JVM descriptor list against the Kotlin parameter list. `0` is
     *   the pairing [ArtifactScanner.composableDeclaredSlot] really uses; anything else is a wrong
     *   pairing, and the point of being able to ask for one.
     */
    private fun agreementCounts(jars: List<File>, shift: Int): Counts {
        val classpath = ArtifactClasspath(composeClasspath())
        val counts = Counts()
        forEachPublicComposable(jars, classpath) { function, method ->
            val (descriptors, _) = splitMethodDescriptor(method.desc)
            val shape = ComposableShape.of(function.allParameterTypes.size, descriptors) ?: return@forEachPublicComposable
            if (shape.totalCount != descriptors.size) return@forEachPublicComposable
            function.allParameterTypes.forEachIndexed { index, type ->
                val descriptor = descriptors.getOrNull(index + shift) ?: return@forEachIndexed
                // `char` has no boundary type on either view, so the scanner declines the whole
                // declaration by design (`composableSlotTagOf`) -- a decline, not a disagreement.
                // Compose 1.6 declared no composable with a `Char` slot; material/material3 under
                // CMP 1.11 do (`SecureTextField`'s `obfuscationCharacter`), so it is skipped here
                // rather than counted as a mis-pairing.
                if (descriptor == "C") return@forEachIndexed
                when (val slot = ArtifactScanner.composableDeclaredSlot(type, descriptor, classpath)) {
                    null -> counts.disagreed++
                    else -> {
                        counts.agreed++
                        if (slot.upgraded) counts.upgraded++
                    }
                }
            }
        }
        return counts
    }

    /** Every public top-level `@Composable` in [jars], paired with its own `MethodNode`. A local
     * copy of `ComposableBindingTest`'s walk, deliberately: this file measures the *pairing* and
     * must not be able to inherit an assumption about it from the file it is checking. */
    private fun forEachPublicComposable(
        jars: List<File>,
        classpath: ArtifactClasspath,
        action: (function: ResolvedFunction, method: org.objectweb.asm.tree.MethodNode) -> Unit,
    ) {
        val public = org.objectweb.asm.Opcodes.ACC_PUBLIC
        val static = org.objectweb.asm.Opcodes.ACC_STATIC
        jars.forEach { jar ->
            java.util.jar.JarFile(jar).use { file ->
                file.entries().asSequence().filter { it.name.endsWith(".class") && '$' !in it.name }.forEach { entry ->
                    val node = file.getInputStream(entry).use { readClassNodeOrNull(it.readBytes()) } ?: return@forEach
                    if ((node.access and public) == 0) return@forEach
                    val containers = when (val metadata = kotlinClassMetadataOf(node)) {
                        is kotlin.metadata.jvm.KotlinClassMetadata.FileFacade -> listOf(metadata.kmPackage to node)
                        is kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassFacade ->
                            metadata.partClassNames.mapNotNull { part ->
                                val partNode = classpath.classNode(part) ?: return@mapNotNull null
                                val partMetadata = kotlinClassMetadataOf(partNode)
                                    as? kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassPart
                                    ?: return@mapNotNull null
                                partMetadata.kmPackage to partNode
                            }
                        else -> return@forEach
                    }
                    containers.forEach { (kmPackage, ownerNode) ->
                        val bySignature = functionsOf(kmPackage)
                            .associateBy { it.jvmSignature.name to it.jvmSignature.descriptor }
                        ownerNode.methods.forEach { method ->
                            if ((method.access and public) == 0 || (method.access and static) == 0) return@forEach
                            val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
                            if (annotations.none { it.desc == ComposableShape.COMPOSABLE_ANNOTATION_DESCRIPTOR }) {
                                return@forEach
                            }
                            val function = bySignature[method.name to method.desc] ?: return@forEach
                            action(function, method)
                        }
                    }
                }
            }
        }
    }
}
