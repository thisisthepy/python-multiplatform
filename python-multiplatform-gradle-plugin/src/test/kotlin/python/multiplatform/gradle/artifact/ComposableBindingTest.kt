package python.multiplatform.gradle.artifact

import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `docs/archive/pythonx-adapter-design.md` §5, measured against real Compose Multiplatform jars.
 *
 * ### What this test exists to pin
 *
 * A `@Composable` used to be declined by [ArtifactScanner] for the one reason its KDoc gave: "the
 * JVM signature carries N parameter(s) metadata does not declare". §5.2 measured what those
 * parameters *are* -- `$composer`, one or more `$changed` bitmasks and (when anything is defaulted)
 * one or more `$default` bitmasks -- and this walks every public `@Composable` in the jars to check
 * that the shape is **derivable**, which is what makes binding one possible at all.
 *
 * Nothing here guesses. The trailing shape is read off the descriptor, and the arithmetic that
 * predicts it (`SLOTS_PER_INT = 10`, `BITS_PER_INT = 31`) is asserted against every composable the
 * jars declare rather than against the nine `javap` rows §5.2 recorded by hand.
 */
class ComposableBindingTest {

    private val caches: File? = listOf(
        File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
        File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
    ).firstOrNull { it.isDirectory }

    private fun jarUnder(group: String, artifact: String): File? = caches?.resolve(group)?.resolve(artifact)
        ?.walkTopDown()?.firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

    private val kotlinStdlibJar: File
        get() = File(Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location.toURI())

    /** Everything a Compose type might have to be resolved against. */
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
     * The measurement §5.2 did by hand for nine functions, run over every public top-level
     * `@Composable` three Compose jars declare.
     *
     * For each one: the JVM parameter list is the Kotlin parameter list, then **one** `Composer`,
     * then nothing but `int`s. The number of those ints is `changedParamCount(n) +
     * defaultParamCount(n)` when the declaration defaults anything and `changedParamCount(n)` when
     * it does not -- which is what makes it possible to say *which* trailing slot is the mask.
     *
     * Deliberately not an assertion about a fixed count: it prints how many it checked, and fails on
     * the first composable whose shape the arithmetic does not predict.
     */
    @Test
    fun everyComposableJvmSignatureIsKotlinParamsThenComposerThenInts() {
        val jars = listOfNotNull(
            jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
            jarUnder("org.jetbrains.compose.material", "material-desktop"),
        )
        if (jars.isEmpty()) return // no local Gradle cache with Compose in it
        val classpath = composeClasspath()

        var checked = 0
        var withDefaults = 0
        val wrong = mutableListOf<String>()
        forEachPublicComposable(jars, classpath) { owner, function, method ->
            val (descriptors, _) = splitMethodDescriptor(method.desc)
            val n = function.allParameterTypes.size
            val shape = ComposableShape.of(n, descriptors)
            checked++
            if (shape == null) {
                wrong += "$owner.${function.kotlinName} ${method.desc}"
                return@forEachPublicComposable
            }
            if (shape.defaultCount > 0) withDefaults++
            // The `$default` masks exist exactly when the declaration defaults something.
            val declares = function.allParameterDefaults.any { it }
            if (declares != (shape.defaultCount > 0)) {
                wrong += "$owner.${function.kotlinName}: declares=$declares defaultCount=${shape.defaultCount}"
            }
        }

        println("composable shape: $checked public top-level composables checked, $withDefaults carry a \$default mask")
        assertTrue(checked > 100, "expected the jars to declare composables; checked only $checked")
        assertEquals(emptyList(), wrong, "composables whose JVM shape the arithmetic did not predict")
    }

    /**
     * The binding itself: `androidx.compose.material3.Text` reaches the table, with the three
     * synthetic parameter groups as **ordinary slots** Python fills.
     *
     * Before this, the same walk produced a declined [python.multiplatform.gradle.model.DeclarationModel]
     * whose reason was "the JVM signature carries 4 parameter(s) metadata does not declare".
     */
    @Test
    fun composablesAreBoundWithTheirSyntheticParametersExposed() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            material3,
            includePrefixes = listOf("androidx.compose.material3"),
            classpath = composeClasspath(),
        )
        val texts = entries.filter { it.name.substringAfterLast('.').substringBefore("__") == "Text" }
        assertTrue(texts.isNotEmpty(), "no Text binding among ${entries.size} entries")

        val stringText = texts.firstOrNull { it.paramTypeNames.firstOrNull() == "kotlin.String" }
        assertNotNull(stringText, "no String-taking Text among ${texts.map { it.name to it.arity }}")

        // The declared parameters, then Composer, two $changed masks and one $default -- the shape
        // `javap -p` shows and `everyComposableJvmSignatureIsKotlinParamsThenComposerThenInts`
        // proves is derivable. **How many** declared parameters is Compose's business and changes
        // between versions (1.6.11's `Text` takes 16, 1.7.0's takes 17 -- `minLines`), so the
        // assertion is the relation and not the number.
        val declared = stringText.paramNames.indexOf("\$composer")
        assertTrue(declared >= 16, "expected Text to declare at least 16 parameters, got $declared")
        assertEquals(declared + 4, stringText.arity, stringText.paramNames.toString())
        assertEquals(stringText.arity, stringText.paramNames.size)
        assertEquals(stringText.arity, stringText.paramTags.size)
        // The synthetic slots separate nothing, so they must not reach the Python-visible name.
        assertTrue("Composer" !in stringText.name, stringText.name)
        assertEquals(
            listOf("\$composer", "\$changed", "\$changed1", "\$default"),
            stringText.paramNames.takeLast(4),
        )
        assertEquals(
            listOf("OBJECT", "INT", "INT", "INT"),
            stringText.paramTags.takeLast(4),
        )
        assertEquals("STRING", stringText.paramTags.first())
        // The declaration's own defaults survive onto the binding: the mask is what makes them
        // reachable, so unlike the sentinel mechanism there is no cap and no branch to decline.
        assertTrue(stringText.paramHasDefault.count { it } >= 14, stringText.paramHasDefault.toString())
        assertEquals(false, stringText.paramHasDefault.first(), "text has no default")
        assertEquals(
            listOf(false, false, false, false),
            stringText.paramHasDefault.takeLast(4),
            "a synthetic slot is never omittable: pythonx always computes it",
        )
    }

    /**
     * The generated Kotlin never names the composable, and it cannot: a Kotlin file facade
     * (`androidx.compose.material3.TextKt`) has **no Kotlin name at all**, so
     * `TextKt.\`Text-fLXpl1I\`(...)` does not compile -- "unresolved reference 'TextKt'", measured
     * with `kotlinc` 2.4.20-Beta2 against the real jar. The call site is therefore emitted as
     * bytecode; see [ComposableThunks].
     */
    @Test
    fun aComposableBindingCallsAGeneratedThunkRatherThanNamingTheDeclaration() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val entries = ArtifactScanner.scanJar(
            material3,
            includePrefixes = listOf("androidx.compose.material3"),
            classpath = composeClasspath(),
        )
        val text = entries.first { it.name.substringAfterLast('.').substringBefore("__") == "Text" }
        val thunk = assertNotNull(text.thunk, "a composable binding must carry a thunk spec")
        assertEquals("androidx/compose/material3/TextKt", thunk.ownerInternalName)
        assertTrue(thunk.methodName.startsWith("Text"), thunk.methodName)
        assertTrue(text.lambdaBody.contains("$THUNK_CLASS_TOKEN.t"), text.lambdaBody)
    }

    /**
     * The emitted thunk really links: generated with ASM against a class this test compiled itself,
     * loaded, and called with a boxed `Object[]` exactly as the boundary hands one over.
     *
     * The target deliberately has a **mangled** JVM name, because that is the case that closes every
     * other route: a hyphen is not a Java identifier and a Kotlin file facade is not a Kotlin name.
     */
    @Test
    fun aGeneratedThunkCallsAMangledJvmNameAndUnboxesTheBoundaryTypes() {
        val targetInternal = "fixture/thunk/MangledProbe"
        val target = mangledProbeClass(targetInternal)
        val bytes = generateThunkClass(
            "Probe",
            listOf(
                ThunkSpec(targetInternal, "sum-abc123", "(IJFZLjava/lang/String;)J"),
                ThunkSpec(targetInternal, "nothing", "(I)V"),
            ),
        )
        val loader = object : ClassLoader(javaClass.classLoader) {
            fun define(name: String, data: ByteArray): Class<*> = defineClass(name, data, 0, data.size)
        }
        loader.define(targetInternal.replace('/', '.'), target)
        val cls = loader.define("$THUNK_PACKAGE.ProbeThunks", bytes)

        // INT carries a Long and FLOAT carries a Double, per `boundaryTypeOf`; the thunk narrows
        // each one on the way in and widens the `long` return back to what INT promises.
        val t0 = cls.getMethod(thunkMethodName(0), Array<Any?>::class.java)
        assertEquals(7L + 8L + 2L + 1L, t0.invoke(null, arrayOf<Any?>(7L, 8L, 2.5, true, "x")))
        val t1 = cls.getMethod(thunkMethodName(1), Array<Any?>::class.java)
        assertEquals(Unit, t1.invoke(null, arrayOf<Any?>(1L)))
    }

    /**
     * A class whose static method is called `sum-abc123`. Emitted rather than compiled on purpose:
     * a hyphen is Kotlin's value-class mangling suffix, is not a Java identifier, and the exact
     * suffix `kotlinc` would pick is not something a test may assume -- so the name is *chosen* here
     * and the point (that the thunk can call a name no source file can spell) is made directly.
     *
     * `sum-abc123(i, j, f, z, s)` returns `i + j + f.toLong() + (if (z) 1 else 0)`; `nothing(i)`
     * returns `void`.
     */
    private fun mangledProbeClass(internalName: String): ByteArray {
        val writer = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sum-abc123", "(IJFZLjava/lang/String;)J", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 0); visitInsn(Opcodes.I2L)
            visitVarInsn(Opcodes.LLOAD, 1); visitInsn(Opcodes.LADD)
            visitVarInsn(Opcodes.FLOAD, 3); visitInsn(Opcodes.F2L); visitInsn(Opcodes.LADD)
            visitVarInsn(Opcodes.ILOAD, 4); visitInsn(Opcodes.I2L); visitInsn(Opcodes.LADD)
            visitInsn(Opcodes.LRETURN)
            visitMaxs(0, 0); visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "nothing", "(I)V", null, null).apply {
            visitCode(); visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /**
     * The rule `ArtifactScanner.functionSlotTypeName` decides a `content` slot by, measured rather
     * than assumed: **a function-typed parameter of a composable is either plain or lowered by
     * exactly two.**
     *
     * A `@Composable` function *type* gains a `Composer` and **one** `$changed` when the Compose
     * plugin lowers it -- one, not the `ceil(n/10)` a lowered composable *function* carries, which is
     * what makes the two cases distinguishable at all. That relation is the whole basis on which
     * `pythonx` decides whether to thread a composer through an invocation, so a declaration that
     * contradicted it would produce a wrapper cast to an interface it does not implement, at the
     * call site, with nothing said earlier.
     *
     * Deliberately not a fixed count: it prints how many slots it checked and fails on the first one
     * whose two arities are related in neither way.
     */
    @Test
    fun everyFunctionTypedSlotOfAComposableIsEitherPlainOrLoweredByTwo() {
        val jars = listOfNotNull(
            jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
            jarUnder("org.jetbrains.compose.material", "material-desktop"),
        )
        if (jars.isEmpty()) return
        var plain = 0
        var lowered = 0
        val wrong = mutableListOf<String>()
        forEachPublicComposable(jars, composeClasspath()) { owner, function, method ->
            val (descriptors, _) = splitMethodDescriptor(method.desc)
            function.allParameterTypes.forEachIndexed { index, type ->
                val descriptor = descriptors.getOrNull(index) ?: return@forEachIndexed
                val jvmArity = ArtifactScanner.jvmFunctionArityOf(descriptor) ?: return@forEachIndexed
                val declared = kotlinClassifierNameOf(type)
                val declaredArity = declared?.removePrefix("kotlin.Function")?.toIntOrNull()
                when {
                    declaredArity == null ->
                        wrong += "$owner.${function.kotlinName} slot $index: declared=$declared jvm=Function$jvmArity"
                    jvmArity == declaredArity -> plain++
                    jvmArity == declaredArity + 2 -> lowered++
                    else ->
                        wrong += "$owner.${function.kotlinName} slot $index: declared=$declaredArity jvm=$jvmArity"
                }
            }
        }
        println("function-typed composable slots: $plain plain, $lowered lowered by two")
        assertTrue(plain + lowered > 100, "expected the jars to declare function-typed slots; saw ${plain + lowered}")
        assertEquals(emptyList(), wrong, "function-typed slots whose declared and compiled arities are unrelated")
    }

    /**
     * The second half of what a slot's name has to say, measured the same way: **what the lambda is
     * invoked with and what it has to give back.**
     *
     * The arity relation above decides which `FunctionN` to implement and whether a composer is
     * threaded. It cannot decide what to do with the arguments, and until it did, everything with an
     * argument was refused -- which is every interactive control (`onValueChange`,
     * `onCheckedChange`) and every scoped container (`ColumnScope`, `RowScope`, `BoxScope`).
     *
     * Three counts come out of this walk and each one is a decision:
     *
     * | count | what it decides |
     * |---|---|
     * | slots forwarding no argument | the case that already worked |
     * | slots forwarding at least one | what [ArtifactScanner.functionSlotTypeName] now has to describe |
     * | slots returning something other than `kotlin.Unit` | what has to keep being refused, because `TypeTag` cannot say which boxed numeric type the caller will cast to |
     *
     * Printed rather than pinned to a literal, because they are facts about a Compose version.
     */
    @Test
    fun functionTypedSlotsCarryTheirArgumentAndReturnTypes() {
        val jars = listOfNotNull(
            jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
            jarUnder("org.jetbrains.compose.material", "material-desktop"),
        )
        if (jars.isEmpty()) return
        var described = 0
        var undescribed = 0
        var withArguments = 0
        var nonUnitReturn = 0
        val malformed = mutableListOf<String>()
        forEachPublicComposable(jars, composeClasspath()) { owner, function, method ->
            val (descriptors, _) = splitMethodDescriptor(method.desc)
            function.allParameterTypes.forEachIndexed { index, type ->
                val descriptor = descriptors.getOrNull(index) ?: return@forEachIndexed
                val jvmArity = ArtifactScanner.jvmFunctionArityOf(descriptor) ?: return@forEachIndexed
                val declared = kotlinClassifierNameOf(type) ?: return@forEachIndexed
                val reported = ArtifactScanner.functionSlotTypeName(type, declared, descriptor)
                if (reported == null) {
                    undescribed++
                    return@forEachIndexed
                }
                described++
                // The grammar, parsed the way `pythonx._function_slot` parses it, so a name this
                // walker can emit and that reader cannot understand fails here rather than at a call.
                val open = reported.indexOf('(')
                val close = reported.indexOf(')', open)
                val arrow = reported.indexOf(ArtifactScanner.FUNCTION_RETURNS, close)
                if (open < 0 || close < 0 || arrow != close + 1) {
                    malformed += "$owner.${function.kotlinName} slot $index: $reported"
                    return@forEachIndexed
                }
                val head = reported.substring(0, open)
                val composable = head.endsWith(ArtifactScanner.COMPOSABLE_TYPE_MARK)
                val arity = head.removeSuffix(ArtifactScanner.COMPOSABLE_TYPE_MARK)
                    .removePrefix("kotlin.Function").toIntOrNull()
                val args = reported.substring(open + 1, close).split(',').filter { it.isNotEmpty() }
                val returns = reported.substring(arrow + ArtifactScanner.FUNCTION_RETURNS.length)
                if (arity != jvmArity) {
                    malformed += "$owner.${function.kotlinName} slot $index: $reported is not Function$jvmArity"
                }
                // A lowered lambda's two appended slots are not declared arguments, so the forwarded
                // count is the compiled arity less those two -- which is what the wrapper relies on
                // to know how many of the values it is invoked with belong to Python.
                val forwarded = jvmArity - if (composable) 2 else 0
                if (args.size != forwarded) {
                    malformed += "$owner.${function.kotlinName} slot $index: $reported forwards ${args.size} of $forwarded"
                }
                if (args.isNotEmpty()) withArguments++
                if (returns != ArtifactScanner.UNIT_TYPE_NAME) nonUnitReturn++
            }
        }
        println(
            "function-typed slot signatures: $described described ($withArguments forward an argument, " +
                "$nonUnitReturn return something other than Unit), $undescribed not describable",
        )
        assertEquals(emptyList(), malformed, "slot names pythonx's grammar cannot read back")
        assertTrue(described > 100, "expected the jars to declare function-typed slots; saw $described")
        assertTrue(withArguments > 50, "expected scoped containers and value callbacks; saw $withArguments")
        assertTrue(nonUnitReturn > 0, "expected some slot to return a value, so the refusal is not vacuous")
    }

    /**
     * The four rows that decide whether an interactive UI is expressible, named individually.
     *
     * `Column.content` is a scope receiver, `Button.onClick` takes nothing, and
     * `Slider.onValueChange` / `Checkbox.onCheckedChange` are the value callbacks -- one `Float`, one
     * `Boolean` -- that were refused outright before the argument types were carried. A rule that
     * described the arity but not the arguments would still pass
     * [everyFunctionTypedSlotOfAComposableIsEitherPlainOrLoweredByTwo] and fail here.
     */
    @Test
    fun aValueCallbackSlotNamesTheTypeItsArgumentArrivesAs() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val entries = ArtifactScanner.scanJar(material3, listOf("androidx.compose.material3"), composeClasspath())

        fun slotOf(declaration: String, parameter: String): String? = entries
            .filter { it.name.substringAfterLast('.').substringBefore("__") == declaration }
            .firstNotNullOfOrNull { entry ->
                entry.paramNames.indexOf(parameter).takeIf { it >= 0 }?.let { entry.paramTypeNames[it] }
            }

        assertEquals("kotlin.Function1(kotlin.Float)->kotlin.Unit", slotOf("Slider", "onValueChange"))
        assertEquals("kotlin.Function1(kotlin.Boolean)->kotlin.Unit", slotOf("Checkbox", "onCheckedChange"))
        // The one that is an *object* rather than a value, and reached through a real composable
        // rather than a scope: `Text`'s layout callback is handed a `TextLayoutResult`.
        assertEquals(
            "kotlin.Function1(androidx.compose.ui.text.TextLayoutResult)->kotlin.Unit",
            slotOf("Text", "onTextLayout"),
        )
    }

    /**
     * The two rows of that distinction that decide whether a container is usable, named individually.
     *
     * `Column.content` is the acceptance criterion's slot and it declares **no default**, so a
     * `Column` whose `content` cannot be filled is not reachable at all -- unlike `Text`, which is
     * reachable with everything but its string omitted. `Button.onClick` is the plain case in the
     * same walk, so a rule that marked every function type as composable would fail here rather than
     * only at the call site.
     */
    @Test
    fun theContentSlotIsMarkedComposableAndAnOnClickIsNot() {
        val layout = jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop") ?: return
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val classpath = composeClasspath()

        val column = ArtifactScanner.scanJar(layout, listOf("androidx.compose.foundation.layout"), classpath)
            .single { it.name == "androidx.compose.foundation.layout.Column" }
        assertEquals(
            listOf("modifier", "verticalArrangement", "horizontalAlignment", "content"),
            column.paramNames.take(4),
        )
        assertEquals(
            "kotlin.Function3@Composable(androidx.compose.foundation.layout.ColumnScope)->kotlin.Unit",
            column.paramTypeNames[3],
            "Column.content",
        )
        assertEquals(false, column.paramHasDefault[3], "content declares no default, so it must be fillable")

        val button = ArtifactScanner.scanJar(material3, listOf("androidx.compose.material3"), classpath)
            .single { it.name.substringAfterLast('.') == "Button" }
        assertEquals("onClick", button.paramNames.first())
        assertEquals("kotlin.Function0()->kotlin.Unit", button.paramTypeNames[0], "Button.onClick is not lowered")
        assertEquals(
            "kotlin.Function3@Composable(androidx.compose.foundation.layout.RowScope)->kotlin.Unit",
            button.paramTypeNames[button.paramNames.indexOf("content")],
            "Button.content is",
        )
    }

    /**
     * **Which numbering a receiver shifts**, read out of the callee rather than guessed at.
     *
     * `composableCandidate` declined every extension composable for exactly one reason, quoted from
     * its own KDoc: *"Compose's `$changed` slots count receivers and its `$default` bits are assigned
     * over value parameters, so a receiver shifts one numbering and not the other. Nothing here has
     * measured which."* This is that measurement, and it is a test rather than a `javap` transcript
     * so that a Compose version that changed the encoding fails here instead of in a frame.
     *
     * ### How the bit is attributed to a parameter
     *
     * Not by pattern-matching a disassembly. Every composable that defaults anything opens a
     * `Composer.startDefaults()` … `endDefaults()` region containing one branch per defaulted
     * parameter, of the shape
     *
     *     ILOAD $default ; push 1 << bit ; IAND ; IFEQ past
     *     …the default expression…
     *     xSTORE <the parameter's own JVM local>
     *     past:
     *
     * so reading the guard's constant and the local the branch assigns pairs a **bit** with a
     * **parameter**, with no assumption about which is which. Stores to a local at or above the first
     * synthetic parameter are skipped: the same region clears bits of the `$dirty` accumulator.
     *
     * The two rows are asserted against each other, because either alone is consistent with the
     * wrong rule: `Text` has no receiver and its bit *i* is parameter *i*; `RowScope.NavigationBarItem`
     * has one and its bit *i* is parameter *i* + 1, which is value parameter *i*.
     */
    @Test
    fun theDefaultBitOfAnExtensionComposableNumbersValueParametersAndNotSlots() {
        val material3 = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return

        // No receiver: the case the existing arithmetic was measured on.
        val text = defaultBitsOf(material3, "androidx/compose/material3/TextKt") { it.startsWith("Text") }
        assertTrue(text.isNotEmpty(), "no defaulted parameter found in Text's prologue")
        assertEquals(
            text.keys.associateWith { it },
            text,
            "a composable with no receiver must number its \$default bits by parameter index",
        )

        // A receiver, and it does **not** take bit 0: `selected` does, and `modifier` -- parameter 4
        // of the JVM method and value parameter 3 -- is guarded by `& 8`.
        val item = defaultBitsOf(material3, "androidx/compose/material3/NavigationBarKt") {
            it == "NavigationBarItem"
        }
        assertTrue(item.isNotEmpty(), "no defaulted parameter found in NavigationBarItem's prologue")
        assertEquals(
            item.keys.associateWith { it + 1 },
            item,
            "an extension composable's \$default bit i must be its value parameter i, not its slot i",
        )
        assertEquals(3, item.keys.min(), "expected NavigationBarItem's first default to be `modifier`")
        assertTrue(31 !in item.keys, "bit 31 is the receiver's and nothing may assign a default over it")
    }

    /**
     * `$default` bit -> the index in `allParameterTypes` of the parameter that bit defaults, for one
     * method of [ownerInternalName] whose name [nameMatches].
     */
    private fun defaultBitsOf(jar: File, ownerInternalName: String, nameMatches: (String) -> Boolean): Map<Int, Int> {
        // Read with the code kept, unlike [readClassNodeOrNull], which skips it: the walker never
        // needs an instruction and this is the one measurement that is *about* them.
        val node = java.util.jar.JarFile(jar).use { file ->
            val entry = file.getEntry("$ownerInternalName.class") ?: return emptyMap()
            file.getInputStream(entry).use { stream ->
                org.objectweb.asm.tree.ClassNode().also {
                    org.objectweb.asm.ClassReader(stream.readBytes()).accept(it, org.objectweb.asm.ClassReader.SKIP_DEBUG)
                }
            }
        }
        val method = node.methods.firstOrNull {
            nameMatches(it.name) && ComposableShape.COMPOSER_DESCRIPTOR in it.desc && it.desc.endsWith(")V")
        } ?: return emptyMap()

        val (descriptors, _) = splitMethodDescriptor(method.desc)
        // A static method's locals are its parameters, `long` and `double` taking two each. The map
        // is inverted so a store instruction can name the parameter it wrote.
        val parameterOfLocal = HashMap<Int, Int>()
        var local = 0
        descriptors.forEachIndexed { index, descriptor ->
            parameterOfLocal[local] = index
            local += if (descriptor == "J" || descriptor == "D") 2 else 1
        }
        // Everything from the `Composer` on is the compiler's; a store into one of those is the
        // `$dirty` accumulator rather than a defaulted parameter.
        val firstSynthetic = descriptors.indexOfFirst { it == ComposableShape.COMPOSER_DESCRIPTOR }
        val defaultLocal = parameterOfLocal.entries.first { it.value == descriptors.size - 1 }.key

        val bits = HashMap<Int, Int>()
        val instructions = method.instructions.toArray()
        var i = 0
        while (i < instructions.size - 3) {
            val load = instructions[i] as? org.objectweb.asm.tree.VarInsnNode
            if (load == null || load.opcode != Opcodes.ILOAD || load.`var` != defaultLocal) { i++; continue }
            val constant = constantOf(instructions[i + 1])
            if (constant == null || instructions[i + 2].opcode != Opcodes.IAND ||
                instructions[i + 3].opcode != Opcodes.IFEQ
            ) { i++; continue }
            val past = (instructions[i + 3] as org.objectweb.asm.tree.JumpInsnNode).label
            var j = i + 4
            while (j < instructions.size && instructions[j] !== past) {
                val store = instructions[j] as? org.objectweb.asm.tree.VarInsnNode
                if (store != null && store.opcode in STORE_OPCODES) {
                    val parameter = parameterOfLocal[store.`var`]
                    if (parameter != null && parameter < firstSynthetic) {
                        bits[Integer.numberOfTrailingZeros(constant)] = parameter
                    }
                }
                j++
            }
            i++
        }
        return bits
    }

    private fun constantOf(instruction: org.objectweb.asm.tree.AbstractInsnNode): Int? = when {
        instruction.opcode in Opcodes.ICONST_0..Opcodes.ICONST_5 -> instruction.opcode - Opcodes.ICONST_0
        instruction is org.objectweb.asm.tree.IntInsnNode -> instruction.operand
        instruction is org.objectweb.asm.tree.LdcInsnNode -> instruction.cst as? Int
        else -> null
    }

    private companion object {
        val STORE_OPCODES = setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)
    }

    private fun forEachPublicComposable(
        jars: List<File>,
        classpath: List<File>,
        action: (owner: String, function: ResolvedFunction, method: org.objectweb.asm.tree.MethodNode) -> Unit,
    ) {
        val artifactClasspath = ArtifactClasspath(classpath)
        jars.forEach { jar ->
            java.util.jar.JarFile(jar).use { file ->
                file.entries().asSequence().filter { it.name.endsWith(".class") && '$' !in it.name }.forEach { entry ->
                    val node = file.getInputStream(entry).use { readClassNodeOrNull(it.readBytes()) } ?: return@forEach
                    if ((node.access and Opcodes.ACC_PUBLIC) == 0) return@forEach
                    val containers = when (val metadata = kotlinClassMetadataOf(node)) {
                        is kotlin.metadata.jvm.KotlinClassMetadata.FileFacade -> listOf(metadata.kmPackage to node)
                        is kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassFacade -> metadata.partClassNames.mapNotNull { part ->
                            val partNode = artifactClasspath.classNode(part) ?: return@mapNotNull null
                            val partMetadata = kotlinClassMetadataOf(partNode) as? kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassPart
                                ?: return@mapNotNull null
                            partMetadata.kmPackage to partNode
                        }
                        else -> return@forEach
                    }
                    containers.forEach { (kmPackage, ownerNode) ->
                        val bySignature = functionsOf(kmPackage).associateBy { it.jvmSignature.name to it.jvmSignature.descriptor }
                        ownerNode.methods.forEach { method ->
                            if ((method.access and Opcodes.ACC_PUBLIC) == 0) return@forEach
                            if ((method.access and Opcodes.ACC_STATIC) == 0) return@forEach
                            // `invisibleAnnotations`, deliberately: `@Composable` is
                            // `AnnotationRetention.BINARY`, so it is a `RuntimeInvisibleAnnotation`
                            // and the visible list is empty for every composable in the jar. Read
                            // out of `javap -v`, and the reason `ArtifactScanner.isComposable` used
                            // to answer `false` for all 500 of them.
                            val annotations = method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()
                            if (annotations.none { it.desc == "Landroidx/compose/runtime/Composable;" }) return@forEach
                            // By name **and descriptor**: `Text--4IGK_g` names two different
                            // overloads in one class, so a name-only lookup would pair a composable
                            // with the wrong declaration and make the arithmetic check vacuous.
                            // `KmFunction.jvmSignature` records the *lowered* descriptor, synthetic
                            // parameters included, which is why this matches at all.
                            val function = bySignature[method.name to method.desc] ?: return@forEach
                            action(ownerNode.name.replace('/', '.'), function, method)
                        }
                    }
                }
            }
        }
    }
}
