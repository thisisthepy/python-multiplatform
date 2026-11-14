package python.multiplatform.gradle.artifact

import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.abi.LibraryAbiReader
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dedicated tests for [KlibKnmScanner] (Step 2 / A1 `.knm` metadata reader and Step 3 / Blocker B1
 * pure-source `@Composable` binding for `<= 6` defaulted parameters):
 *
 * 1. Parameter names (`parameterNamesKnown == true`) and default-argument omission (`<= 6` defaults).
 * 2. Top-level extension functions and top-level extension properties (`GETTER`).
 * 3. `@JvmInline value class` unboxing (when both constructor and property are `public`, e.g. `Dp`)
 *    versus `OBJECT` handle retention (when the constructor is `private`, e.g. `Color`).
 * 4. Exact `PUBLIC` vs `PROTECTED` visibility on `open` and `abstract` classes (where
 *    `LibraryAbiReader` declined all members of non-final classes because its binary ABI dump
 *    merges `PROTECTED` into `PUBLIC`).
 * 5. Pure-source `@Composable` binding with `<= 6` defaulted parameters and explicit decline with
 *    `"more than 6 defaulted parameters on a klib @Composable: requires an IR lowering bridge"`
 *    above 6 defaulted parameters.
 * 6. Kotlin 2.1+ `.klib` (`org.thisisthepy.compose.designsystem:designsystem-breeze-macosarm64:1.11.1-ext.4`)
 *    where `LibraryAbiReader.readAbiInfo` in `kotlin-compiler-embeddable:2.0.20` fails to deserialize
 *    `ir/` while `KlibKnmScanner` reads `linkdata/` (`.knm`) and binds its theme declarations.
 */
@OptIn(ExperimentalLibraryAbiReader::class)
class KlibKnmScannerTest {

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

    private val composeKlib: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureComposeKlib")
            assertNotNull(path, "the walkerFixtureComposeKlib system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    @Test
    fun knmRecoversParameterNamesAndDefaultOmissionOnRealKlibs() {
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines"))
        val declarations = KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines"))

        val job = entries.single { it.name == "kotlinx.coroutines.Job" }
        assertEquals(listOf("parent"), job.paramNames)
        assertEquals(listOf(true), job.paramHasDefault)
        assertEquals(listOf(true), omittableSlotsOf(job))
        assertTrue("when ((if (args[0] == null) 1 else 0))" in job.lambdaBody)

        val jobModel = declarations.single { it.bindingName == "kotlinx.coroutines.Job" }
        assertTrue(jobModel.parameterNamesKnown)
        assertEquals(listOf<String?>("parent"), jobModel.parameters.map { it.name })
        assertEquals(listOf(true), jobModel.parameters.map { it.declaresDefault })

        val semaphore = entries.single { it.name == "kotlinx.coroutines.sync.Semaphore" }
        assertEquals(listOf("permits", "acquiredPermits"), semaphore.paramNames)
        assertEquals(listOf(false, true), semaphore.paramHasDefault)
        assertEquals(listOf(false, true), omittableSlotsOf(semaphore))
        assertTrue("permits = (args[0] as Long).toInt()" in semaphore.lambdaBody)

        val whileSubscribed = entries.single { it.name == "kotlinx.coroutines.flow.SharingStarted.WhileSubscribed" }
        assertEquals(listOf("stopTimeoutMillis", "replayExpirationMillis"), whileSubscribed.paramNames)
        assertEquals(listOf(true, true), whileSubscribed.paramHasDefault)
        assertEquals(listOf(true, true), omittableSlotsOf(whileSubscribed))
        assertTrue("stopTimeoutMillis = (args[0] as Long)" in whileSubscribed.lambdaBody)
        assertTrue("replayExpirationMillis = (args[1] as Long)" in whileSubscribed.lambdaBody)
    }

    @Test
    fun knmBindsTopLevelExtensionFunctionsAndExtensionProperties() {
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines"))

        val ensureActiveJob = entries.single { it.name == "kotlinx.coroutines.ensureActive__Job" }
        assertEquals("FUNCTION", ensureActiveJob.kind)
        assertEquals("kotlinx.coroutines.Job", ensureActiveJob.receiverTypeName)
        assertEquals(listOf(RECEIVER_PARAMETER_NAME), ensureActiveJob.paramNames)
        assertEquals(listOf("import kotlinx.coroutines.ensureActive as artifact_ext_kotlinx_coroutines_ensureActive"), ensureActiveJob.imports)

        val scopeIsActive = entries.single { it.name == "kotlinx.coroutines.isActive" }
        assertEquals("GETTER", scopeIsActive.kind)
        assertEquals("kotlinx.coroutines.CoroutineScope", scopeIsActive.receiverTypeName)
        assertEquals("BOOLEAN", scopeIsActive.returnTag)
        assertEquals(listOf("import kotlinx.coroutines.isActive as artifact_ext_kotlinx_coroutines_isActive"), scopeIsActive.imports)

        val dispatchersIo = entries.single { it.name == "kotlinx.coroutines.IO" }
        assertEquals("GETTER", dispatchersIo.kind)
        assertEquals("kotlinx.coroutines.Dispatchers", dispatchersIo.receiverTypeName)
        assertEquals("OBJECT", dispatchersIo.returnTag)

        if (konanStdlibKlib.isDirectory) {
            val mathEntries = KlibScanner.scanKlib(konanStdlibKlib, includePrefixes = listOf("kotlin.math"))
            val powDouble = mathEntries.single { it.name == "kotlin.math.pow__Double_Double" }
            assertEquals("kotlin.Double", powDouble.receiverTypeName)
            assertEquals(listOf(RECEIVER_PARAMETER_NAME, "x"), powDouble.paramNames)
            assertEquals(listOf("import kotlin.math.pow as artifact_ext_kotlin_math_pow"), powDouble.imports)
        }
    }

    @Test
    fun knmUnboxesValueClassWithPublicConstructorAndPropertyAndKeepsPrivateConstructorAsHandle() {
        val nr = KnmTestNameResolver(
            strings = listOf("value", "<init>", "dp", "color", "measure"),
            classes = listOf(
                "kotlin/Float",
                "kotlin/Long",
                "kotlin/Int",
                "kotlin/Unit",
                "androidx/compose/ui/unit/Dp",
                "androidx/compose/ui/graphics/Color",
            ),
        )
        val tt = TypeTable(ProtoBuf.TypeTable.getDefaultInstance())
        val floatType = ProtoBuf.Type.newBuilder().setClassName(0).setNullable(false).build()
        val longType = ProtoBuf.Type.newBuilder().setClassName(1).setNullable(false).build()
        val publicFlags = Flags.VISIBILITY.toFlags(ProtoBuf.Visibility.PUBLIC)
        val privateFlags = Flags.VISIBILITY.toFlags(ProtoBuf.Visibility.PRIVATE)
        val valueClassFlags = publicFlags or
            Flags.CLASS_KIND.toFlags(ProtoBuf.Class.Kind.CLASS) or
            Flags.MODALITY.toFlags(ProtoBuf.Modality.FINAL) or
            Flags.IS_VALUE_CLASS.toFlags(true)

        val dpProto = ProtoBuf.Class.newBuilder()
            .setFlags(valueClassFlags)
            .setFqName(4)
            .setInlineClassUnderlyingPropertyName(0)
            .setInlineClassUnderlyingType(floatType)
            .addConstructor(
                ProtoBuf.Constructor.newBuilder()
                    .setFlags(publicFlags)
                    .addValueParameter(ProtoBuf.ValueParameter.newBuilder().setName(0).setType(floatType)),
            )
            .addProperty(
                ProtoBuf.Property.newBuilder()
                    .setFlags(publicFlags)
                    .setName(0)
                    .setReturnType(floatType),
            )
            .build()

        val colorProto = ProtoBuf.Class.newBuilder()
            .setFlags(valueClassFlags)
            .setFqName(5)
            .setInlineClassUnderlyingPropertyName(0)
            .setInlineClassUnderlyingType(longType)
            .addConstructor(
                ProtoBuf.Constructor.newBuilder()
                    .setFlags(privateFlags)
                    .addValueParameter(ProtoBuf.ValueParameter.newBuilder().setName(0).setType(longType)),
            )
            .addProperty(
                ProtoBuf.Property.newBuilder()
                    .setFlags(publicFlags)
                    .setName(0)
                    .setReturnType(longType),
            )
            .build()

        val classes = mapOf(
            "androidx.compose.ui.unit.Dp" to KlibKnmScanner.KnmClassRecord(
                dottedName = "androidx.compose.ui.unit.Dp",
                packageName = "androidx.compose.ui.unit",
                relativeName = "Dp",
                proto = dpProto,
                nameResolver = nr,
                typeTable = tt,
            ),
            "androidx.compose.ui.graphics.Color" to KlibKnmScanner.KnmClassRecord(
                dottedName = "androidx.compose.ui.graphics.Color",
                packageName = "androidx.compose.ui.graphics",
                relativeName = "Color",
                proto = colorProto,
                nameResolver = nr,
                typeTable = tt,
            ),
        )

        val dpClassType = ProtoBuf.Type.newBuilder().setClassName(4).setNullable(false).build()
        val colorClassType = ProtoBuf.Type.newBuilder().setClassName(5).setNullable(false).build()
        val measureFn = ProtoBuf.Function.newBuilder()
            .setFlags(publicFlags or Flags.MEMBER_KIND.toFlags(ProtoBuf.MemberKind.DECLARATION))
            .setName(4)
            .setReturnType(dpClassType)
            .addValueParameter(ProtoBuf.ValueParameter.newBuilder().setName(2).setType(dpClassType))
            .addValueParameter(ProtoBuf.ValueParameter.newBuilder().setName(3).setType(colorClassType))
            .build()

        val candidate = assertNotNull(
            KlibKnmScanner.functionCandidateOrNull(
                fn = measureFn,
                owner = "fixture.compose",
                callOwner = "fixture.compose",
                ownerIsClass = false,
                nr = nr,
                tt = tt,
                classes = classes,
            ),
        )
        val callable = assertNotNull(candidate.callable)
        assertEquals(listOf("FLOAT", "OBJECT"), callable.paramTags)
        assertEquals(listOf("androidx.compose.ui.unit.Dp", "androidx.compose.ui.graphics.Color"), callable.paramTypeNames)
        assertEquals("FLOAT", callable.returnTag)
        assertEquals(
            "{ args -> ((fixture.compose.measure(androidx.compose.ui.unit.Dp((args[0] as Double).toFloat()), (args[1] as androidx.compose.ui.graphics.Color))).value).toDouble() }",
            callable.lambdaBody,
        )
    }

    @Test
    fun knmDistinguishesPublicFromProtectedMembersAndConstructorsOnOpenClasses() {
        // Real KLIB check: `CloseableCoroutineDispatcher` and `CoroutineDispatcher` are abstract/open
        // classes in `kotlinx-coroutines-core`. `LibraryAbiReader` declines all their members because
        // its ABI dump merges `PROTECTED` into `PUBLIC`; `.knm` preserves `Flags.VISIBILITY`, so
        // `public` members (`close`, `limitedParallelism`) bind while `protected` ones are skipped.
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines"))
        val closeableClose = entries.single { it.name == "kotlinx.coroutines.CloseableCoroutineDispatcher.close" }
        assertEquals("METHOD", closeableClose.kind)
        assertEquals("kotlinx.coroutines.CloseableCoroutineDispatcher", closeableClose.receiverTypeName)

        val limitedParallelism = entries.single { it.name == "kotlinx.coroutines.CoroutineDispatcher.limitedParallelism" }
        assertEquals("METHOD", limitedParallelism.kind)
        assertEquals("kotlinx.coroutines.CoroutineDispatcher", limitedParallelism.receiverTypeName)

        // Synthetic `.knm` check: on a single `open` class with both a `PUBLIC` and a `PROTECTED`
        // constructor, property, and method, `KlibKnmScanner` binds only the `PUBLIC` ones and
        // skips the `PROTECTED` ones completely.
        val nr = KnmTestNameResolver(
            strings = listOf(
                "x",
                "publicProp",
                "protectedProp",
                "publicMethod",
                "protectedMethod",
            ),
            classes = listOf(
                "fixture/klib/OpenWidget",
                "kotlin/Int",
                "kotlin/String",
                "kotlin/Unit",
            ),
        )
        val tt = TypeTable(ProtoBuf.TypeTable.getDefaultInstance())
        val intType = ProtoBuf.Type.newBuilder().setClassName(1).setNullable(false).build()
        val stringType = ProtoBuf.Type.newBuilder().setClassName(2).setNullable(false).build()
        val unitType = ProtoBuf.Type.newBuilder().setClassName(3).setNullable(false).build()

        val publicFlags = Flags.VISIBILITY.toFlags(ProtoBuf.Visibility.PUBLIC)
        val protectedFlags = Flags.VISIBILITY.toFlags(ProtoBuf.Visibility.PROTECTED)
        val declFlags = Flags.MEMBER_KIND.toFlags(ProtoBuf.MemberKind.DECLARATION)
        val openClassFlags = publicFlags or
            Flags.CLASS_KIND.toFlags(ProtoBuf.Class.Kind.CLASS) or
            Flags.MODALITY.toFlags(ProtoBuf.Modality.OPEN)

        val openWidgetProto = ProtoBuf.Class.newBuilder()
            .setFlags(openClassFlags)
            .setFqName(0)
            // Public 1-arg constructor `OpenWidget(x: Int)` vs protected 1-arg constructor `OpenWidget(x: String)`.
            .addConstructor(
                ProtoBuf.Constructor.newBuilder()
                    .setFlags(publicFlags)
                    .addValueParameter(ProtoBuf.ValueParameter.newBuilder().setName(0).setType(intType)),
            )
            .addConstructor(
                ProtoBuf.Constructor.newBuilder()
                    .setFlags(protectedFlags)
                    .addValueParameter(ProtoBuf.ValueParameter.newBuilder().setName(0).setType(stringType)),
            )
            // Public property vs protected property.
            .addProperty(
                ProtoBuf.Property.newBuilder()
                    .setFlags(publicFlags or declFlags)
                    .setName(1)
                    .setReturnType(intType),
            )
            .addProperty(
                ProtoBuf.Property.newBuilder()
                    .setFlags(protectedFlags or declFlags)
                    .setName(2)
                    .setReturnType(intType),
            )
            // Public method vs protected method.
            .addFunction(
                ProtoBuf.Function.newBuilder()
                    .setFlags(publicFlags or declFlags)
                    .setName(3)
                    .setReturnType(unitType),
            )
            .addFunction(
                ProtoBuf.Function.newBuilder()
                    .setFlags(protectedFlags or declFlags)
                    .setName(4)
                    .setReturnType(unitType),
            )
            .build()

        val record = KlibKnmScanner.KnmClassRecord(
            dottedName = "fixture.klib.OpenWidget",
            packageName = "fixture.klib",
            relativeName = "OpenWidget",
            proto = openWidgetProto,
            nameResolver = nr,
            typeTable = tt,
        )
        val classes = mapOf("fixture.klib.OpenWidget" to record)

        val ctors = KlibKnmScanner.constructorCandidatesOf(record, classes, classes)
        assertEquals(1, ctors.size, "only the PUBLIC constructor of an open class may bind")
        assertEquals(listOf("INT"), assertNotNull(ctors.single().callable).paramTags)

        val props = KlibKnmScanner.memberPropertyCandidatesOf(record, classes, classes)
        assertEquals(listOf("fixture.klib.OpenWidget.publicProp"), props.mapNotNull { it.callable?.name })

        val methods = KlibKnmScanner.memberCandidatesOf(record, classes, classes)
        assertEquals(listOf("fixture.klib.OpenWidget.publicMethod"), methods.mapNotNull { it.callable?.name })
    }

    @Test
    fun knmBindsPureSourceComposableWithAtMostSixDefaultsAndDeclinesAboveSix() {
        val nr = KnmTestNameResolver(
            strings = listOf(
                "MyBox",
                "modifier",
                "content",
                "TooManyDefaults",
                "p0",
                "p1",
                "p2",
                "p3",
                "p4",
                "p5",
                "p6",
            ),
            classes = listOf(
                "androidx/compose/runtime/Composable",
                "androidx/compose/ui/Modifier",
                "kotlin/Function0",
                "kotlin/Unit",
                "kotlin/Int",
            ),
        )
        val tt = TypeTable(ProtoBuf.TypeTable.getDefaultInstance())
        val publicFlags = Flags.VISIBILITY.toFlags(ProtoBuf.Visibility.PUBLIC)
        val modifierClassProto = ProtoBuf.Class.newBuilder()
            .setFlags(publicFlags or Flags.CLASS_KIND.toFlags(ProtoBuf.Class.Kind.INTERFACE))
            .setFqName(1)
            .build()
        val classes = mapOf(
            "androidx.compose.ui.Modifier" to KlibKnmScanner.KnmClassRecord(
                dottedName = "androidx.compose.ui.Modifier",
                packageName = "androidx.compose.ui",
                relativeName = "Modifier",
                proto = modifierClassProto,
                nameResolver = nr,
                typeTable = tt,
            ),
        )

        val composableAnn = ProtoBuf.Annotation.newBuilder().setId(0).build()
        val modifierType = ProtoBuf.Type.newBuilder().setClassName(1).setNullable(false).build()
        val unitType = ProtoBuf.Type.newBuilder().setClassName(3).setNullable(false).build()
        val intType = ProtoBuf.Type.newBuilder().setClassName(4).setNullable(false).build()
        val composableLambdaType = ProtoBuf.Type.newBuilder()
            .setClassName(2)
            .setNullable(false)
            .addArgument(
                ProtoBuf.Type.Argument.newBuilder()
                    .setProjection(ProtoBuf.Type.Argument.Projection.INV)
                    .setType(unitType),
            )
            .addExtension(KlibMetadataProtoBuf.typeAnnotation, composableAnn)
            .build()

        val defaultParamFlags = Flags.DECLARES_DEFAULT_VALUE.toFlags(true)
        val myBoxFn = ProtoBuf.Function.newBuilder()
            .setFlags(publicFlags or Flags.MEMBER_KIND.toFlags(ProtoBuf.MemberKind.DECLARATION))
            .setName(0)
            .setReturnType(unitType)
            .addValueParameter(
                ProtoBuf.ValueParameter.newBuilder()
                    .setFlags(defaultParamFlags)
                    .setName(1)
                    .setType(modifierType),
            )
            .addValueParameter(
                ProtoBuf.ValueParameter.newBuilder()
                    .setName(2)
                    .setType(composableLambdaType),
            )
            .addExtension(KlibMetadataProtoBuf.functionAnnotation, composableAnn)
            .build()

        val rawCandidate = assertNotNull(
            KlibKnmScanner.functionCandidateOrNull(
                fn = myBoxFn,
                owner = "fixture.compose",
                callOwner = "fixture.compose",
                ownerIsClass = false,
                nr = nr,
                tt = tt,
                classes = classes,
            ),
        )
        val settled = ArtifactScanner.applyDefaultOmission(listOf(rawCandidate)).single()
        val callable = assertNotNull(settled.callable)
        assertEquals("fixture.compose.MyBox", callable.name)
        assertEquals(5, callable.arity)
        assertEquals(listOf("modifier", "content", "\$composer", "\$changed", "\$default"), callable.paramNames)
        assertEquals(listOf("OBJECT", "OBJECT", "OBJECT", "INT", "INT"), callable.paramTags)
        assertEquals(
            listOf(
                "androidx.compose.ui.Modifier",
                "kotlin.Function2@Composable()->kotlin.Unit",
                "androidx.compose.runtime.Composer",
                "kotlin.Int",
                "kotlin.Int",
            ),
            callable.paramTypeNames,
        )
        assertEquals(listOf(true, false, false, false, false), callable.paramHasDefault)
        assertEquals(listOf(true, false, false, false, false), omittableSlotsOf(callable))
        assertTrue("val block: @androidx.compose.runtime.Composable () -> kotlin.Unit =" in callable.lambdaBody)
        assertTrue("(block as kotlin.Function2<androidx.compose.runtime.Composer?, kotlin.Int, kotlin.Unit>).invoke(" in callable.lambdaBody)
        assertTrue("content = (args[1] as @androidx.compose.runtime.Composable () -> kotlin.Unit)" in callable.lambdaBody)

        // A @Composable with 7 defaulted parameters (> MAX_OMITTABLE_PARAMETERS = 6) is declined cleanly.
        val tooManyFnBuilder = ProtoBuf.Function.newBuilder()
            .setFlags(publicFlags or Flags.MEMBER_KIND.toFlags(ProtoBuf.MemberKind.DECLARATION))
            .setName(3)
            .setReturnType(unitType)
            .addExtension(KlibMetadataProtoBuf.functionAnnotation, composableAnn)
        for (i in 0..6) {
            tooManyFnBuilder.addValueParameter(
                ProtoBuf.ValueParameter.newBuilder()
                    .setFlags(defaultParamFlags)
                    .setName(4 + i)
                    .setType(intType),
            )
        }
        val declinedCandidate = assertNotNull(
            KlibKnmScanner.functionCandidateOrNull(
                fn = tooManyFnBuilder.build(),
                owner = "fixture.compose",
                callOwner = "fixture.compose",
                ownerIsClass = false,
                nr = nr,
                tt = tt,
                classes = classes,
            ),
        )
        assertNull(declinedCandidate.callable)
        assertEquals(
            "more than 6 defaulted parameters on a klib @Composable: requires an IR lowering bridge",
            declinedCandidate.declaration.declineReason,
        )
    }

    @Test
    fun knmScansKotlin21ComposeExt4KlibWhereLibraryAbiReaderFailsOnIr() {
        // 1. `LibraryAbiReader.readAbiInfo` in `kotlin-compiler-embeddable:2.0.20` deserializes `ir/`
        //    and fails on Kotlin 2.1+ KLIBs (`designsystem-breeze-macosarm64:1.11.1-ext.4`).
        assertFailsWith<Throwable> {
            LibraryAbiReader.readAbiInfo(composeKlib)
        }

        // 2. `KlibKnmScanner` reads `linkdata/` (`.knm`) directly and binds `BreezeTheme`,
        //    `breezeLightColorScheme`, and `breezeDarkColorScheme` without touching `ir/`.
        val entries = KlibScanner.scanKlib(
            composeKlib,
            includePrefixes = listOf("androidx.compose.designsystem.breeze"),
        )
        val names = entries.map { it.name }
        assertTrue("androidx.compose.designsystem.breeze.BreezeTheme" in names, "expected BreezeTheme in $names")
        assertTrue("androidx.compose.designsystem.breeze.breezeLightColorScheme" in names, "expected breezeLightColorScheme in $names")
        assertTrue("androidx.compose.designsystem.breeze.breezeDarkColorScheme" in names, "expected breezeDarkColorScheme in $names")

        val breezeTheme = entries.single { it.name == "androidx.compose.designsystem.breeze.BreezeTheme" }
        assertEquals(listOf("darkTheme", "highContrast", "content", "\$composer", "\$changed", "\$default"), breezeTheme.paramNames)
        assertEquals(listOf(true, true, false, false, false, false), breezeTheme.paramHasDefault)
        assertEquals(listOf(true, true, false, false, false, false), omittableSlotsOf(breezeTheme))
        assertTrue("@androidx.compose.runtime.Composable" in breezeTheme.lambdaBody)
    }
}

private class KnmTestNameResolver(
    private val strings: List<String>,
    private val classes: List<String>,
) : NameResolver {
    override fun getString(index: Int): String = strings[index]
    override fun getQualifiedClassName(index: Int): String = classes[index]
    override fun isLocalClassName(index: Int): Boolean = false
}
