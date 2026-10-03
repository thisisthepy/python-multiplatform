package python.multiplatform.gradle.artifact

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.Serializable
import kotlin.math.ceil

/**
 * The one call site in this whole generator that `kotlinc` cannot emit, and the measurement that
 * says so.
 *
 * ### Why a `.class` and not generated Kotlin
 *
 * Every other binding this walker produces is *Kotlin source* that `kotlinc` compiles, precisely so
 * that mangling stays the compiler's job (agent-rules §12). A `@Composable` is the one shape where
 * that route is closed, for two independent reasons, both measured against
 * `material3-desktop-1.6.11` with `kotlinc` 2.4.20-Beta2:
 *
 * 1. **The synthetic parameters are unreachable from source.** `androidx.compose.material3.Text`
 *    declares 16 Kotlin parameters and its JVM method takes 20 -- `$composer`, two `$changed`
 *    bitmasks and one `$default` (`docs/archive/pythonx-adapter-design.md` §5.2, and
 *    `ComposableBindingTest.everyComposableJvmSignatureIsKotlinParamsThenComposerThenInts` over
 *    every composable in three jars). The Compose plugin adds them during IR lowering, so at source
 *    level they are not parameters of anything and cannot be named or passed. Whatever the Compose
 *    plugin computes for `$default` is a **compile-time constant** derived from which named
 *    arguments were written -- which is why "let `kotlinc` do it" costs one generated call
 *    expression per subset, 2^15 for `Text` (§4.5).
 * 2. **The declaring class has no Kotlin name.** The obvious escape -- call the JVM method
 *    directly, `TextKt.\`Text-fLXpl1I\`(...)` -- does not compile:
 *
 *        probe.kt:2:35: error: unresolved reference 'TextKt'.
 *
 *    A Kotlin file facade is not addressable from Kotlin at all, so this is not about the hyphen and
 *    not about `-Xjvm-default` or backticks. Java cannot spell the method either (a hyphen is not a
 *    Java identifier), and `Text$default` -- Kotlin's ordinary synthetic for defaulted calls -- does
 *    **not exist** for a composable, which is what the `$default` parameter replaces.
 *
 * So the call site is emitted as bytecode: one `public static Object t<i>(Object[])` per bound
 * composable, doing nothing but unboxing the boundary's argument array and `INVOKESTATIC`. The
 * generated Kotlin fragment calls *that*, by a name this generator chose and therefore can spell.
 *
 * ### Why this is not "looking a method up by name"
 *
 * agent-rules §12 retires *runtime* lookup by Kotlin name, because the mangling suffix hashes only
 * the value-class signature and `padding`/`size`/`width`/`height` all share `-3ABfNKs`. Nothing here
 * derives a name: [ThunkSpec] carries the JVM name and descriptor **copied verbatim from the
 * `MethodNode` being walked**, and the resulting `INVOKESTATIC` is resolved by the JVM's own linker
 * at class-load time, exactly as the one `kotlinc` would have emitted. There is no reflection, no
 * `MethodHandles.lookup`, and nothing for GraalVM's closed-world assumption to object to.
 */
internal data class ThunkSpec(
    /** e.g. `androidx/compose/material3/TextKt`. */
    val ownerInternalName: String,
    /** The JVM method name, mangling suffix included: `Text-fLXpl1I`. */
    val methodName: String,
    /** The JVM method descriptor, synthetic parameters included. */
    val descriptor: String,
    /**
     * Per JVM parameter, the `@JvmInline value class` **box** the boundary sends for that slot, or
     * `null` when the slot needs no unwrapping. Empty means "none of them do", which is every thunk
     * emitted before a composable's declared slots were typed from `@Metadata`.
     *
     * Why a box arrives at a slot the descriptor says is a `long`: `Icon`'s `tint` is a `Color`, and
     * `ArtifactScanner.composableDeclaredSlot` types it `OBJECT` because that is what an ordinary
     * declaration taking the same `Color` has always done. The value crossing the boundary is
     * therefore a handle to an *instance* -- of `kotlin.ULong`, the carrier
     * `resolveKotlinBoundary`'s descent stopped at -- while the callee's signature takes the `J` it
     * erases to. `unbox-impl` is the one operation that bridges them, and the name recorded here is
     * the carrier's, read off its own class file together with the descriptor that method returns.
     */
    val valueClassUnboxOwners: List<String?> = emptyList(),
) : Serializable

/** Where generated thunk classes live. Under [ARTIFACTS_PACKAGE] so that
 * `python-multiplatform-ksp`'s `ALWAYS_EXCLUDED_PACKAGE_PREFIXES` keeps ignoring it, and in a
 * sub-package of its own because these are `.class` files on a classpath rather than sources in a
 * source set. */
internal val THUNK_PACKAGE = "$ARTIFACTS_PACKAGE.thunk"

internal fun thunkClassQualifiedName(fragmentObjectName: String): String = "$THUNK_PACKAGE.${fragmentObjectName}Thunks"

internal fun thunkClassInternalName(fragmentObjectName: String): String =
    thunkClassQualifiedName(fragmentObjectName).replace('.', '/')

internal fun thunkMethodName(index: Int): String = "t$index"

/**
 * How a composable's JVM parameter list divides into declared parameters and synthetic ones.
 *
 * `null` from [of] means "this is not the shape a composable has", which is the honest answer for
 * anything the arithmetic below does not predict -- a future Compose lowering, or a non-composable
 * that merely carries the annotation. Declining is what the walker did for *every* composable
 * before; keeping it as the fallback means an unrecognised shape degrades to that rather than to a
 * call with the wrong number of arguments.
 */
internal class ComposableShape(
    /** Kotlin-declared parameters, extension receiver included. */
    val declaredCount: Int,
    /** How many `$changed` bitmasks follow the `Composer`. */
    val changedCount: Int,
    /** How many `$default` bitmasks follow those -- `0` when nothing is defaulted. */
    val defaultCount: Int,
) {
    val composerIndex: Int get() = declaredCount
    val firstChangedIndex: Int get() = declaredCount + 1
    val firstDefaultIndex: Int get() = declaredCount + 1 + changedCount
    val totalCount: Int get() = declaredCount + 1 + changedCount + defaultCount

    /**
     * What the synthetic slots are called on the binding, in JVM parameter order.
     *
     * The compiler's own spellings, and they are load-bearing rather than decorative: `pythonx`
     * recognises a composable by finding `$composer` among a declaration's parameter names, and
     * computes the mask for the slots named `$default...`. A `$` is not a Python identifier
     * character either, so no keyword argument can ever collide with one.
     */
    fun syntheticParameterNames(): List<String> = buildList {
        add("\$composer")
        repeat(changedCount) { add(if (it == 0) "\$changed" else "\$changed$it") }
        repeat(defaultCount) { add(if (it == 0) "\$default" else "\$default$it") }
    }

    companion object {
        /** Compose's `SLOTS_PER_INT`: three bits of `$changed` per parameter plus one, so ten
         * parameters fit one `int`. Verified against every composable three Compose jars declare,
         * not taken from the compiler's source. */
        private const val SLOTS_PER_INT = 10

        /** Compose's `BITS_PER_INT`: one `$default` bit per parameter, 31 to an `int`. */
        private const val BITS_PER_INT = 31

        private fun changedCountFor(declared: Int): Int =
            if (declared == 0) 1 else ceil(declared / SLOTS_PER_INT.toDouble()).toInt()

        private fun defaultCountFor(declared: Int): Int =
            if (declared == 0) 0 else ceil(declared / BITS_PER_INT.toDouble()).toInt()

        /**
         * Reads the shape off the descriptor rather than predicting it: the parameter at
         * [declaredCount] must be the `Composer` and everything after it must be `int`. Only *how
         * many* of those ints are `$changed` comes from arithmetic, and the two admissible totals
         * (with and without `$default`) are far enough apart that the check is not vacuous.
         */
        fun of(declaredCount: Int, jvmDescriptors: List<String>): ComposableShape? {
            if (declaredCount < 0 || jvmDescriptors.size <= declaredCount) return null
            if (jvmDescriptors[declaredCount] != COMPOSER_DESCRIPTOR) return null
            val trailing = jvmDescriptors.drop(declaredCount + 1)
            if (trailing.any { it != "I" }) return null
            val changed = changedCountFor(declaredCount)
            val defaults = trailing.size - changed
            if (defaults != 0 && defaults != defaultCountFor(declaredCount)) return null
            return ComposableShape(declaredCount, changed, defaults)
        }

        const val COMPOSER_DESCRIPTOR = "Landroidx/compose/runtime/Composer;"
        const val COMPOSABLE_ANNOTATION_DESCRIPTOR = "Landroidx/compose/runtime/Composable;"
    }
}

/**
 * One class holding every thunk a single artefact fragment needs.
 *
 * The thunk bodies themselves have **no branches**, which is why [ClassWriter.COMPUTE_MAXS] is
 * enough and no `StackMapTable` has to be computed for them -- `COMPUTE_FRAMES` would need a
 * `ClassLoader` that can see Compose, and this runs inside a Gradle worker that deliberately cannot.
 *
 * The one exception is [visitValueClassUnwrapper], which has exactly one branch and writes its own
 * frame by hand for that reason. It is a separate method rather than an inline sequence precisely so
 * that the frame it needs is a fixed, trivial one (`F_SAME`, a single `Object` local, empty stack)
 * instead of whatever the enclosing thunk's operand stack happened to hold at that point.
 */
internal fun generateThunkClass(fragmentObjectName: String, specs: List<ThunkSpec>): ByteArray {
    val internalName = thunkClassInternalName(fragmentObjectName)
    val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
    writer.visit(
        Opcodes.V1_8,
        Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_SUPER,
        internalName,
        null,
        "java/lang/Object",
        null,
    )
    // One unwrapper per distinct (box, erased type) pair rather than per slot: `Color`'s `tint`
    // appears on three `Icon` overloads alone, and every one of them wants the same two instructions.
    val unwrappers = LinkedHashMap<Pair<String, String>, String>()
    specs.forEach { spec ->
        val (parameters, _) = splitMethodDescriptor(spec.descriptor)
        parameters.forEachIndexed { slot, descriptor ->
            val box = spec.valueClassUnboxOwners.getOrNull(slot) ?: return@forEachIndexed
            unwrappers.getOrPut(box to descriptor) { "u${unwrappers.size}" }
        }
    }

    specs.forEachIndexed { index, spec ->
        val method = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            thunkMethodName(index),
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null,
        )
        method.visitCode()
        val (parameters, returns) = splitMethodDescriptor(spec.descriptor)
        parameters.forEachIndexed { slot, descriptor ->
            method.visitVarInsn(Opcodes.ALOAD, 0)
            method.pushInt(slot)
            method.visitInsn(Opcodes.AALOAD)
            val box = spec.valueClassUnboxOwners.getOrNull(slot)
            if (box == null) {
                method.unbox(descriptor)
            } else {
                method.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    internalName,
                    unwrappers.getValue(box to descriptor),
                    "(Ljava/lang/Object;)$descriptor",
                    false,
                )
            }
        }
        method.visitMethodInsn(Opcodes.INVOKESTATIC, spec.ownerInternalName, spec.methodName, spec.descriptor, false)
        method.box(returns)
        method.visitInsn(Opcodes.ARETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
    }
    unwrappers.forEach { (key, name) -> writer.visitValueClassUnwrapper(name, key.first, key.second) }
    writer.visitEnd()
    return writer.toByteArray()
}

/**
 * `static <erased> u<i>(Object)`: the box `HandleTable` handed back, opened into the value the
 * callee's descriptor declares -- and **`null` turned into a zero rather than a
 * `NullPointerException`**.
 *
 * ### Why null arrives at all, and why zero is the right answer
 *
 * A `@Composable`'s omitted argument is not absent: the slot is still a real JVM parameter, and what
 * says it was left out is a bit of the `$default` mask (`docs/archive/pythonx-adapter-design.md` §5.2). So
 * `pythonx._absent` has to put *something* in the slot, and for an `OBJECT` slot the only thing it
 * can put there is `None`. Its own docstring states the invariant that makes any value safe: the
 * callee's generated prologue assigns over the slot before its first use, which is what the mask
 * *means*. `_absent` already relies on it for the primitive widths (a `0` into a `FLOAT` slot); this
 * is the same rule one type further on, and it is needed now because a slot that used to be `INT`
 * -- and therefore got a `0` -- is an `OBJECT` since `ArtifactScanner.composableDeclaredSlot` began
 * typing declared slots from `@Metadata`.
 *
 * `Icon(painter, contentDescription = ...)` with no `tint` is exactly that call, and without this it
 * is an NPE inside a generated class with no source.
 *
 * ### The frame
 *
 * One `IFNULL`, therefore one merge point, therefore one `StackMapTable` entry -- written here
 * rather than computed, because `ClassWriter.COMPUTE_FRAMES` resolves types through a `ClassLoader`
 * that would have to see Compose and this generator runs in a worker that cannot. The frame is the
 * simplest one there is: at the label, the single `Object` parameter is still the only local and the
 * stack is empty, which is `F_SAME`.
 */
private fun ClassWriter.visitValueClassUnwrapper(name: String, boxInternalName: String, descriptor: String) {
    val method = visitMethod(
        Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC,
        name,
        "(Ljava/lang/Object;)$descriptor",
        null,
        null,
    )
    method.visitCode()
    val absent = org.objectweb.asm.Label()
    method.visitVarInsn(Opcodes.ALOAD, 0)
    method.visitJumpInsn(Opcodes.IFNULL, absent)
    method.visitVarInsn(Opcodes.ALOAD, 0)
    method.visitTypeInsn(Opcodes.CHECKCAST, boxInternalName)
    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, boxInternalName, VALUE_CLASS_UNBOX_METHOD, "()$descriptor", false)
    method.visitInsn(returnOpcodeOf(descriptor))
    method.visitLabel(absent)
    method.visitFrame(Opcodes.F_SAME, 0, null, 0, null)
    method.pushZero(descriptor)
    method.visitInsn(returnOpcodeOf(descriptor))
    method.visitMaxs(0, 0)
    method.visitEnd()
}

private fun returnOpcodeOf(descriptor: String): Int = when (descriptor) {
    "J" -> Opcodes.LRETURN
    "D" -> Opcodes.DRETURN
    "F" -> Opcodes.FRETURN
    "Z", "B", "S", "C", "I" -> Opcodes.IRETURN
    else -> Opcodes.ARETURN
}

private fun MethodVisitor.pushZero(descriptor: String) {
    when (descriptor) {
        "J" -> visitInsn(Opcodes.LCONST_0)
        "D" -> visitInsn(Opcodes.DCONST_0)
        "F" -> visitInsn(Opcodes.FCONST_0)
        "Z", "B", "S", "C", "I" -> visitInsn(Opcodes.ICONST_0)
        else -> visitInsn(Opcodes.ACONST_NULL)
    }
}

private fun MethodVisitor.pushInt(value: Int) {
    when {
        value <= 5 -> visitInsn(Opcodes.ICONST_0 + value)
        value <= Byte.MAX_VALUE -> visitIntInsn(Opcodes.BIPUSH, value)
        value <= Short.MAX_VALUE -> visitIntInsn(Opcodes.SIPUSH, value)
        else -> visitLdcInsn(value)
    }
}

/**
 * Turns the `Object` in an argument slot into what the descriptor wants.
 *
 * The widths are the boundary's, not the declaration's: `boundaryTypeOf` says `TypeTag.INT` carries
 * a `Long` and `TypeTag.FLOAT` carries a `Double`, so every integral slot arrives boxed as `Long`
 * and every floating one as `Double`. `java.lang.Number` is what makes one sequence serve all of
 * them, and it narrows exactly the way the generated Kotlin's `(args[i] as Long).toInt()` does.
 */
private fun MethodVisitor.unbox(descriptor: String) {
    when (descriptor) {
        "Z" -> {
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean")
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
        }
        "B" -> numberValue("byteValue", "()B")
        "S" -> numberValue("shortValue", "()S")
        "I" -> numberValue("intValue", "()I")
        "J" -> numberValue("longValue", "()J")
        "F" -> numberValue("floatValue", "()F")
        "D" -> numberValue("doubleValue", "()D")
        // `C` never reaches here: `composableParamTagOf` declines a `char`, for the reason
        // `boundaryTypeOf` gives -- Python has no character type.
        else -> visitTypeInsn(Opcodes.CHECKCAST, Type.getType(descriptor).internalName)
    }
}

private fun MethodVisitor.numberValue(name: String, descriptor: String) {
    visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number")
    visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", name, descriptor, false)
}

/**
 * Boxes the return into what its `TypeTag` promises the trampoline.
 *
 * A `void` composable -- which is nearly all of them -- returns `kotlin.Unit`, not `null`, because
 * `TypeTag.UNIT` is what the generated Kotlin's `wrapReturn` produces for `V` and the trampoline
 * maps that to Python's `None`. The integral and floating cases widen for the same reason
 * `boundaryTypeOf`'s wrap templates do: `INT` is a `Long` on the wire and `FLOAT` is a `Double`.
 */
private fun MethodVisitor.box(descriptor: String) {
    when (descriptor) {
        "V" -> visitFieldInsn(Opcodes.GETSTATIC, "kotlin/Unit", "INSTANCE", "Lkotlin/Unit;")
        "Z" -> valueOf("java/lang/Boolean", "(Z)Ljava/lang/Boolean;")
        "B" -> { visitInsn(Opcodes.I2L); valueOf("java/lang/Long", "(J)Ljava/lang/Long;") }
        "S" -> { visitInsn(Opcodes.I2L); valueOf("java/lang/Long", "(J)Ljava/lang/Long;") }
        "I" -> { visitInsn(Opcodes.I2L); valueOf("java/lang/Long", "(J)Ljava/lang/Long;") }
        "J" -> valueOf("java/lang/Long", "(J)Ljava/lang/Long;")
        "F" -> { visitInsn(Opcodes.F2D); valueOf("java/lang/Double", "(D)Ljava/lang/Double;") }
        "D" -> valueOf("java/lang/Double", "(D)Ljava/lang/Double;")
        else -> Unit // already a reference
    }
}

private fun MethodVisitor.valueOf(owner: String, descriptor: String) {
    visitMethodInsn(Opcodes.INVOKESTATIC, owner, "valueOf", descriptor, false)
}
