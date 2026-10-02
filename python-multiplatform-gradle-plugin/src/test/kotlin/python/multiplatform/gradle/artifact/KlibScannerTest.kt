package python.multiplatform.gradle.artifact

import org.jetbrains.kotlin.library.abi.AbiClassifierReference
import org.jetbrains.kotlin.library.abi.AbiCompoundName
import org.jetbrains.kotlin.library.abi.AbiFunction
import org.jetbrains.kotlin.library.abi.AbiModality
import org.jetbrains.kotlin.library.abi.AbiQualifiedName
import org.jetbrains.kotlin.library.abi.AbiSignatureVersion
import org.jetbrains.kotlin.library.abi.AbiSignatures
import org.jetbrains.kotlin.library.abi.AbiType
import org.jetbrains.kotlin.library.abi.AbiTypeArgument
import org.jetbrains.kotlin.library.abi.AbiTypeNullability
import org.jetbrains.kotlin.library.abi.AbiTypeParameter
import org.jetbrains.kotlin.library.abi.AbiValueParameter
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The klib counterpart of `ArtifactScannerTest`, against two real klibs: `kotlin-stdlib` (bundled
 * with the Kotlin/Native distribution this build's `.konan` cache already stages, so no extra
 * download) and `kotlinx-coroutines-core-androidnativearm64:1.8.1` (a real download, the klib
 * counterpart of `ArtifactScannerTest`'s `junit:junit:4.13.2`).
 *
 * ROADMAP §16e: reading a klib is the *easier* half of the two producers, not the harder one --
 * `LibraryAbiReader` gives exact Kotlin types directly, with no facade problem to work around. What
 * still needs proving is that this walker matches on the right thing and disambiguates the same way
 * the JVM walker does; both real klibs below are chosen to exercise exactly that.
 */
class KlibScannerTest {

    /**
     * `kotlin-stdlib`'s own klib, staged by the Kotlin/Native distribution this build already pins
     * (`kotlin-compiler-embeddable:2.0.20` here; the `.konan` prebuilt toolchain is the matching
     * version). `~/.konan` is this workspace's own symlink onto external storage -- AGENTS.md's
     * "캐시 ... 홈에서 심볼릭 링크" -- so this path is host-relative, not hardcoded to a volume.
     *
     * Guarded rather than asserted: a machine that has never run a Kotlin/Native build has no
     * `.konan` cache yet, and that is a setup gap this test should report plainly rather than fail
     * opaquely on. See each test's own early return.
     */
    private val konanStdlibKlib: File
        get() = File(
            System.getProperty("user.home") +
                "/.konan/kotlin-native-prebuilt-macos-aarch64-2.0.20/klib/common/stdlib",
        )

    private val coroutinesKlib: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureKlib")
            assertNotNull(path, "the walkerFixtureKlib system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    /**
     * The bug this pins: `AbiQualifiedName.toString()` joins package and relative name with `/`
     * (`kotlin.math/abs`), not `.`. An earlier version of [KlibScanner.matchesInclude] matched the
     * *full* qualified name against `"kotlin.math."` (the JVM producer's dotted-class rule, reused
     * unchanged) -- which a declaration directly inside the `kotlin.math` package can never satisfy,
     * because the character after the prefix is `/`. Every one of `kotlin.math`'s bindable
     * declarations is directly in that package (there is no `kotlin.math.trig` sub-package), so the
     * old rule bound **zero** of them; matching on `packageName` alone (this test) is what makes the
     * 68 below reachable at all.
     */
    @Test
    fun aPackageAnIncludePrefixNamesExactlyIsMatchedNotOnlyItsSubPackages() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val entries = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
        assertTrue(entries.isNotEmpty(), "kotlin.math yielded nothing; the package-prefix match regressed")
        assertEquals(68, entries.size)
    }

    /**
     * `kotlin.math.max`/`min` are each declared four times (`Int`, `Long`, `Float`, `Double`), and a
     * klib's ABI carries the exact Kotlin type for each -- the same fact `@Metadata` gives the JVM
     * producer, so [ArtifactScanner.disambiguateOverloads]'s `name__<types>` rule applies unchanged
     * (this test calls the *shared* function, not a klib-local copy). An earlier version of
     * [KlibScanner] grouped by bare name and kept only groups of size one, silently dropping `max`,
     * `min` and every other overloaded `kotlin.math` name; suffixing instead of dropping is what
     * [ArtifactScannerTest.theWholeJUnitJarYieldsExactlyTheseUnsuffixedDeclarations]'s KDoc calls "a
     * third option between arbitrate and drop", applied here to a klib for the first time.
     */
    @Test
    fun overloadedNamesAreSuffixedNotDropped() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val names = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math")).map { it.name }
        assertEquals(
            listOf("kotlin.math.max__Double_Double", "kotlin.math.max__Float_Float", "kotlin.math.max__Int_Int", "kotlin.math.max__Long_Long"),
            names.filter { it.startsWith("kotlin.math.max__") }.sorted(),
        )
        // The bare name must be absent -- exactly one binding may ever answer to it, and here there
        // are four, none of which this walker may pick for the others.
        assertTrue("kotlin.math.max" !in names)
        assertTrue("kotlin.math.min" !in names)
    }

    /** One entry, its tags and its call, pinned the same way
     * [ArtifactScannerTest.anEntryCarriesTheTagsAndTheCallItsDeclarationImplies] pins a JVM one. */
    @Test
    fun anEntryCarriesTheTagsItsDeclarationImplies() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val entries = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
        val absInt = entries.single { it.name == "kotlin.math.abs__Int" }
        assertEquals(1, absInt.arity)
        assertEquals(listOf("INT"), absInt.paramTags)
        assertEquals("INT", absInt.returnTag)
        assertEquals(listOf("kotlin.Int"), absInt.paramTypeNames)
    }

    /**
     * A real, separately-published klib -- the klib counterpart of `ArtifactScannerTest`'s JUnit jar
     * -- scanned with no include filter, over all ~400 of its top-level declarations. Proves the
     * walker does not crash on a large, diverse artefact: `suspend`, extension functions, value
     * classes and `@PublishedApi internal` declarations are all present in `kotlinx-coroutines-core`
     * and must all be *declined*, not mishandled.
     *
     * **Zero bindable, and that is the correct number, not a regression.** An earlier version of this
     * test pinned one -- `checkIndexOverflow`, `kotlinx.coroutines.flow.internal`'s bounds check -- as
     * bound. It was wrong: `checkIndexOverflow` is `@PublishedApi internal`, binary-visible so an
     * inline call site elsewhere in the *same* library can resolve it, but not source-visible outside
     * that module. `KlibScanWorkAction`'s KDoc records how this was found -- `:ksp-fixtures
     * :klib-artifact:compileKotlinAndroidNativeArm64` failing to compile the generated fragment with
     * "it is internal in file", the first time this walker's output was ever actually compiled rather
     * than only unit-tested. `KlibScanner`'s `PUBLISHED_API` constant has the rest. Every other candidate in
     * this klib's ~400 top-level declarations is an extension function (declined for the reason
     * [aDeclinedDeclarationIsRecordedWithItsReasonRatherThanVanishing] pins) or `suspend` -- coroutines'
     * entire public surface operates on `CoroutineScope`/`Job`/`Flow` through extensions, and none of
     * those are bindable boundary types today. `kotlin.math` above is the richer case; this one is the
     * "does it survive a real, messy artefact, and decline what it must decline" case.
     */
    @Test
    fun aRealThirdPartyKlibIsScannedWithoutCrashingAndDeclinesEveryDeclaration() {
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = emptyList())
        assertEquals(emptyList(), entries.map { it.name })
    }

    /** The specific bug [aRealThirdPartyKlibIsScannedWithoutCrashingAndDeclinesEveryDeclaration] pins
     * the fix for, isolated to one declaration and its reason. */
    @Test
    fun aPublishedApiInternalDeclarationIsDeclinedWithItsOwnReason() {
        val declarations = KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = emptyList())
        val checkIndexOverflow = declarations.single { it.simpleName == "checkIndexOverflow" }
        assertEquals(null, checkIndexOverflow.bindingName)
        assertTrue(
            checkIndexOverflow.declineReason?.contains("PublishedApi") == true,
            "expected a @PublishedApi decline reason, got ${checkIndexOverflow.declineReason}",
        )
    }

    /**
     * A namespace include prefix means "this package or a sub-package of it", not "a text prefix of
     * the package string". `kotlin.collections`, `kotlin.concurrent` and `kotlin.comparisons` are all
     * real `kotlin-stdlib` packages that literally start with the seven characters `kotlin.co` --
     * proof that [KlibScanner.matchesInclude] checks a `.`-bounded namespace and not
     * `String.startsWith` on its own, using packages that actually exist rather than an invented
     * negative case.
     */
    @Test
    fun anIncludePrefixIsANamespaceNotATextPrefix() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val entries = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.co"))
        assertEquals(emptyList(), entries, "\"kotlin.co\" is a text prefix of real packages but not their namespace")
    }

    // ------------------------------------------------------------------- the declaration model half

    /**
     * `docs/design/pyi-generation-design.md` §2.2's "what is declined stays visible", for the klib producer.
     *
     * Before this, [KlibScanner] dropped an extension, a `suspend` or an unbindable-typed declaration
     * at a `filter`, so nothing downstream could say it had ever been seen. `kotlin.math` is the case
     * that makes this concrete: `Double.pow`, `Double.withSign` and the rest of its extension surface
     * are the *majority* of the package, and a stub product that simply omitted them would read as if
     * `kotlin.math` were 68 functions wide.
     */
    @Test
    fun aDeclinedDeclarationIsRecordedWithItsReasonRatherThanVanishing() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val declarations = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
        val bound = declarations.filter { it.bindingName != null }
        val declined = declarations.filter { it.bindingName == null }

        assertEquals(68, bound.size, "the bound half must still be exactly what scanKlib returns")
        assertTrue(declined.isNotEmpty(), "kotlin.math's extensions must be recorded, not dropped")
        assertTrue(
            declined.all { it.declineReason != null },
            "a declined declaration with no reason is the silent drop this replaces",
        )
        // The one klib cannot answer and the JVM producer can: the receiver's own type.
        val pow = declined.filter { it.simpleName == "pow" }
        assertTrue(pow.isNotEmpty(), "kotlin.math.pow is an extension on Double and must appear declined")
        assertTrue(
            pow.all { it.declineReason!!.contains("extension receiver") },
            "expected an extension-receiver reason, got ${pow.map { it.declineReason }}",
        )
        assertTrue(pow.all { it.receiver == null }, "klib cannot name the receiver, so it must not claim one")
    }

    /** The two halves come out of one walk, exactly as `DeclarationModelTest
     * .theModelAndTheBindingsAgreeBecauseTheyComeFromTheSameWalk` pins for the JVM producer. */
    @Test
    fun theModelAndTheBindingsAgreeBecauseTheyComeFromTheSameWalk() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val bound = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math")).map { it.name }
        val modelled = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
            .mapNotNull { it.bindingName }
        assertEquals(bound.sorted(), modelled.sorted())
    }

    /**
     * The overload rule is *one* implementation shared with the JVM producer, and the model sees its
     * result: `kotlin.math.max`'s four members reach the table under `max__Int_Int` and friends, and
     * the [python.multiplatform.gradle.model.DeclarationModel] for each carries that suffixed key as
     * its `bindingName` while keeping `max` as its `simpleName`. A stub generator needs both -- the
     * key to name the attribute, the simple name to say what Kotlin called it.
     */
    @Test
    fun theSharedOverloadRuleReachesTheModelsBindingName() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val max = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
            .filter { it.simpleName == "max" && it.bindingName != null }
        assertEquals(
            listOf(
                "kotlin.math.max__Double_Double",
                "kotlin.math.max__Float_Float",
                "kotlin.math.max__Int_Int",
                "kotlin.math.max__Long_Long",
            ),
            max.map { it.bindingName!! }.sorted(),
        )
        assertTrue(max.all { it.simpleName == "max" }, "the Kotlin name must survive the table key")
    }

    /**
     * §3.2's rule, reached for a third reason. A klib's `AbiValueParameter` has a `type`, a
     * `hasDefaultArg` and no **name** -- `kotlin-compiler-embeddable` 2.0.20's interface declares
     * exactly five members and none of them is one (checked with `javap`, not assumed). So every klib
     * declaration is positional-only, the same as a Java jar compiled without `-parameters`, and
     * `parameterNamesKnown` is what says so rather than a synthesised `arg0`.
     */
    @Test
    fun aKlibSuppliesTypesAndDefaultsButNoParameterNames() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val absInt = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
            .single { it.bindingName == "kotlin.math.abs__Int" }
        assertEquals(false, absInt.parameterNamesKnown)
        assertEquals(listOf<String?>(null), absInt.parameters.map { it.name })
        assertEquals(listOf("kotlin.Int"), absInt.parameters.map { it.type.qualifiedName })
        assertEquals(listOf("INT"), absInt.parameters.map { it.boundaryTag })
        assertEquals("kotlin.Int", absInt.returnType.qualifiedName)
        assertEquals("INT", absInt.returnBoundaryTag)
        assertEquals("kotlin.math", absInt.owner)
        assertEquals(false, absInt.ownerIsClass)
    }

    /**
     * A nullable primitive is declined, the same rule `resolveKotlinType` applies to the JVM
     * producer's `Int?` -- and for the same reason: `TypeTag.INT` carries a `Long` and the generated
     * read is `(args[0] as Long).toInt()`, so a Python `None` would fail inside the cast rather than
     * reach the declaration.
     *
     * This is not a rule the klib walker used to have. `AbiType.Simple.nullability` exists on 2.0.20
     * and the first version of this file never read it, so `String?` mapped to `STRING` and would have
     * generated `(args[0] as String)` for a parameter that accepts null.
     */
    @Test
    fun aNullablePrimitiveIsDeclinedTheWayTheJvmProducerDeclinesOne() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val declarations = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = NULLABLE_PROBE_PACKAGES)
        val nullablePrimitiveTyped = declarations.filter { declaration ->
            declaration.parameters.any { it.type.isNullable && it.type.qualifiedName in KLIB_PRIMITIVE_NAMES }
        }
        assertTrue(
            nullablePrimitiveTyped.isNotEmpty(),
            "no declaration in $NULLABLE_PROBE_PACKAGES has a nullable primitive parameter; " +
                "this test's fixture assumption is wrong, not the rule",
        )
        assertTrue(
            nullablePrimitiveTyped.all { it.bindingName == null },
            "bound with a nullable primitive: ${nullablePrimitiveTyped.filter { it.bindingName != null }.map { it.bindingName }}",
        )
    }

    /**
     * What confirms `declaresDefault`'s bug is real rather than theoretical, and what rules out
     * "reproduce it end-to-end with a real klib" as the way to pin it: neither real klib this
     * module's tests can reach (`kotlin-stdlib`, unscoped -- every include prefix matches) binds a
     * single declaration that also declares a default. `kotlin-stdlib` has 183 declarations with at
     * least one defaulted parameter; every one of them is declined, overwhelmingly (141 of 183) for
     * "an extension receiver whose type ... does not expose" -- unrelated to the default itself, but
     * enough on its own to keep this combination from ever reaching [KlibScanner]'s bound path here.
     *
     * That absence is why `declaresDefaultIsFalseForABoundDeclarationEvenWhenTheAbiDeclaresADefault`
     * below has to construct its own [AbiFunction]: this test is the record of a real one not being
     * available, not a substitute for the synthetic one. It stays as a regression guard against that
     * going stale silently -- a future compiler bump that lets extension receivers bind (this file's
     * own KDoc names `2.2.20` as the version that would) would make a real klib exercise the bug this
     * test currently cannot observe, and this assertion would be the one to fail and say so.
     */
    @Test
    fun boundDeclarationWithADefaultDoesNotOccurInAvailableRealKlibs() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        val declarations = KlibScanner.scanKlibDeclarations(konanStdlibKlib, includePrefixes = emptyList())
        val boundWithDefault = declarations.filter { it.bindingName != null && it.parameters.any { p -> p.declaresDefault } }
        assertTrue(
            boundWithDefault.isEmpty(),
            "expected no bound declaration to declare a default in the available real klibs, found: " +
                boundWithDefault.map { it.bindingName },
        )
    }

    /**
     * The bug this pins: `DeclarationModel.parameters[].declaresDefault` used to copy
     * `AbiValueParameter.hasDefaultArg` straight through for a *bound* declaration too -- the Kotlin
     * declaration's own truth, not the generated binding's. `KlibScanner.candidateOrNull`'s own KDoc
     * on [ArtifactCallable.paramHasDefault] (the "Deliberately all `false`" comment) already settles
     * what the binding's truth is: a klib walk has no `ArtifactScanner.applyDefaultOmission`
     * counterpart, so every generated call passes every argument, always. Before the fix in this
     * commit, a bound klib parameter with `hasDefaultArg == true` still reported
     * `declaresDefault == true`, so `PyiRendering`'s `if (parameter.declaresDefault) " = ..."` would
     * write a default the runtime binding cannot honour -- a parameter the `.pyi` says is optional
     * but that `pythonx._bind` never receives a `null` sentinel for, because [candidateOrNull]'s
     * `lambdaBody` has no branch that omits it.
     *
     * Since [boundDeclarationWithADefaultDoesNotOccurInAvailableRealKlibs] just established that no
     * real klib available here exercises this combination, this test constructs the minimal
     * [AbiFunction] the bug needs by hand: a top-level, non-extension, non-suspend, two-`Int`-
     * parameter function whose second parameter declares a default. [KlibScanner.candidateOrNull] is
     * `internal` rather than `private` for exactly this call -- `scanKlib`/`scanKlibDeclarations` have
     * no seam to inject a declaration that was not read from a real file on disk.
     */
    @Test
    @OptIn(ExperimentalLibraryAbiReader::class)
    fun declaresDefaultIsFalseForABoundDeclarationEvenWhenTheAbiDeclaresADefault() {
        val function = FakeTopLevelFunction(
            simpleName = "topLevelWithDefault",
            valueParameters = listOf(
                FakeValueParameter(FakePrimitiveType("Int"), hasDefaultArg = false),
                FakeValueParameter(FakePrimitiveType("Int"), hasDefaultArg = true),
            ),
            returnType = FakePrimitiveType("Int"),
        )

        val candidate = KlibScanner.candidateOrNull(function)

        assertNotNull(candidate, "expected the fake declaration to produce a candidate at all")
        assertNotNull(
            candidate.declaration.bindingName,
            "expected the fake declaration to bind, not decline: ${candidate.declaration.declineReason}",
        )
        assertNotNull(candidate.callable, "a bound declaration must carry a callable")
        assertEquals(
            listOf(false, false),
            candidate.callable.paramHasDefault,
            "the binding's own contract: a klib walk never omits an argument",
        )
        assertEquals(
            listOf(false, false),
            candidate.declaration.parameters.map { it.declaresDefault },
            "a bound klib parameter must never advertise a default the generated call cannot omit " +
                "-- see this test's KDoc for what regresses when this reads [false, true] instead",
        )
    }

    private companion object {
        /** Packages searched for a real nullable-primitive parameter; see the test that uses them. */
        val NULLABLE_PROBE_PACKAGES = listOf("kotlin.text", "kotlin.collections", "kotlin.io", "kotlin")

        val KLIB_PRIMITIVE_NAMES = setOf(
            "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Int",
            "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.String", "kotlin.ByteArray",
        )
    }
}

/**
 * A `kotlin.<name>` primitive type, minimally -- exactly the shape
 * [KlibScanner]'s private `typeModelOf`/`klibBoundaryTypeOf` need to accept it as a boundary type:
 * an [AbiClassifierReference.ClassReference] classifier, no type arguments, unmarked nullability.
 */
@OptIn(ExperimentalLibraryAbiReader::class)
private class FakePrimitiveType(name: String) : AbiType.Simple {
    override val classifierReference: AbiClassifierReference = object : AbiClassifierReference.ClassReference {
        override val className: AbiQualifiedName = AbiQualifiedName(AbiCompoundName("kotlin"), AbiCompoundName(name))
    }
    override val arguments: List<AbiTypeArgument> = emptyList()
    override val nullability: AbiTypeNullability = AbiTypeNullability.NOT_SPECIFIED
}

/** A plain value parameter: not vararg, not `noinline`/`crossinline`, with [hasDefaultArg] the one
 * property [declaresDefaultIsFalseForABoundDeclarationEvenWhenTheAbiDeclaresADefault] varies. */
@OptIn(ExperimentalLibraryAbiReader::class)
private class FakeValueParameter(
    override val type: AbiType,
    override val hasDefaultArg: Boolean,
) : AbiValueParameter {
    override val isVararg: Boolean = false
    override val isNoinline: Boolean = false
    override val isCrossinline: Boolean = false
}

/**
 * The minimal [AbiFunction] `KlibScanner.candidateOrNull` needs to bind: top-level (package
 * `fixture.klib`), no extension receiver, no context receivers, not `suspend`, not a constructor, no
 * annotations (so never declined as `@PublishedApi internal`) -- every one of
 * `candidateOrNull`/`declarationModelOf`'s early `return declined(...)` checks reads a property this
 * class answers "no" to, so the only thing left deciding bound-vs-declined is [valueParameters] and
 * [returnType], which the test sets up to be bindable primitives.
 */
@OptIn(ExperimentalLibraryAbiReader::class)
private class FakeTopLevelFunction(
    simpleName: String,
    override val valueParameters: List<AbiValueParameter>,
    override val returnType: AbiType,
) : AbiFunction {
    override val qualifiedName: AbiQualifiedName =
        AbiQualifiedName(AbiCompoundName("fixture.klib"), AbiCompoundName(simpleName))
    override val signatures: AbiSignatures = object : AbiSignatures {
        override fun get(version: AbiSignatureVersion): String? = null
    }
    override fun hasAnnotation(name: AbiQualifiedName): Boolean = false
    override val modality: AbiModality = AbiModality.FINAL
    override val typeParameters: List<AbiTypeParameter> = emptyList()
    override val isConstructor: Boolean = false
    override val isInline: Boolean = false
    override val isSuspend: Boolean = false
    override val hasExtensionReceiverParameter: Boolean = false
    override val contextReceiverParametersCount: Int = 0
}
