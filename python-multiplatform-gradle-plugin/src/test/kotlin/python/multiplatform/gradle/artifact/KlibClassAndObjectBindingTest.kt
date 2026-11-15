package python.multiplatform.gradle.artifact

import org.jetbrains.kotlin.library.abi.AbiClass
import org.jetbrains.kotlin.library.abi.AbiClassKind
import org.jetbrains.kotlin.library.abi.AbiClassifierReference
import org.jetbrains.kotlin.library.abi.AbiCompoundName
import org.jetbrains.kotlin.library.abi.AbiDeclaration
import org.jetbrains.kotlin.library.abi.AbiFunction
import org.jetbrains.kotlin.library.abi.AbiModality
import org.jetbrains.kotlin.library.abi.AbiProperty
import org.jetbrains.kotlin.library.abi.AbiQualifiedName
import org.jetbrains.kotlin.library.abi.AbiSignatureVersion
import org.jetbrains.kotlin.library.abi.AbiSignatures
import org.jetbrains.kotlin.library.abi.AbiType
import org.jetbrains.kotlin.library.abi.AbiTypeArgument
import org.jetbrains.kotlin.library.abi.AbiTypeNullability
import org.jetbrains.kotlin.library.abi.AbiTypeParameter
import org.jetbrains.kotlin.library.abi.AbiValueParameter
import org.jetbrains.kotlin.library.abi.ExperimentalLibraryAbiReader
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #191 Phase B (Step 1 / A3): [KlibScanner] binds class constructors, `object` and `Companion`
 * handles, constants (`STATIC_GETTER`) and functions (`FUNCTION`), member properties (`GETTER` and
 * `SETTER`), and cross-klib handle types when `classpath` carries the dependency `.klib`s.
 */
@OptIn(ExperimentalLibraryAbiReader::class)
class KlibClassAndObjectBindingTest {

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

    private val entries by lazy {
        KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines"))
    }

    private val declarations by lazy {
        KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines"))
    }

    @Test
    fun aPublicFinalClassConstructorBindsUnderItsPackage() {
        val ctor = entries.single { it.name == "kotlinx.coroutines.CoroutineName" }
        assertEquals("FUNCTION", ctor.kind)
        assertEquals(1, ctor.arity)
        assertEquals(listOf("STRING"), ctor.paramTags)
        assertEquals("OBJECT", ctor.returnTag)
        assertEquals("kotlinx.coroutines.CoroutineName", ctor.returnTypeName)
        assertEquals("{ args -> (kotlinx.coroutines.CoroutineName((args[0] as String))) }", ctor.lambdaBody)

        val model = declarations.single { it.bindingName == "kotlinx.coroutines.CoroutineName" }
        assertTrue(model.isConstructor)
        assertEquals("kotlinx.coroutines", model.owner)
        assertEquals("CoroutineName", model.simpleName)
        assertTrue(!model.ownerIsClass)
    }

    @Test
    fun topLevelObjectConstantsAndMethodsBind() {
        val defaultDispatcher = entries.single { it.name == "kotlinx.coroutines.Dispatchers.Default" }
        assertEquals("STATIC_GETTER", defaultDispatcher.kind)
        assertEquals(0, defaultDispatcher.arity)
        assertEquals("OBJECT", defaultDispatcher.returnTag)
        assertEquals("kotlinx.coroutines.CoroutineDispatcher", defaultDispatcher.returnTypeName)
        assertEquals("{ (kotlinx.coroutines.Dispatchers.Default) }", defaultDispatcher.lambdaBody)

        val nonDisposableDispose = entries.single { it.name == "kotlinx.coroutines.NonDisposableHandle.dispose" }
        assertEquals("FUNCTION", nonDisposableDispose.kind)
        assertEquals(0, nonDisposableDispose.arity)
        assertEquals("UNIT", nonDisposableDispose.returnTag)
        assertEquals("{ (kotlinx.coroutines.NonDisposableHandle.dispose()) }", nonDisposableDispose.lambdaBody)

        val nonDisposableParent = entries.single { it.name == "kotlinx.coroutines.NonDisposableHandle.parent" }
        assertEquals("STATIC_GETTER", nonDisposableParent.kind)
        assertEquals("OBJECT", nonDisposableParent.returnTag)
        assertEquals("{ (kotlinx.coroutines.NonDisposableHandle.parent) }", nonDisposableParent.lambdaBody)
    }

    @Test
    fun companionHandleConstantsAndFactoryFunctionsBind() {
        val handle = entries.single { it.name == "kotlinx.coroutines.flow.SharingStarted.Companion" }
        assertEquals("STATIC_GETTER", handle.kind)
        assertEquals("OBJECT", handle.returnTag)
        assertEquals("kotlinx.coroutines.flow.SharingStarted.Companion", handle.returnTypeName)
        assertEquals("{ (kotlinx.coroutines.flow.SharingStarted.Companion) }", handle.lambdaBody)
        assertTrue(declarations.single { it.bindingName == handle.name }.isCompanionHandle)

        val eagerly = entries.single { it.name == "kotlinx.coroutines.flow.SharingStarted.Eagerly" }
        assertEquals("STATIC_GETTER", eagerly.kind)
        assertEquals("OBJECT", eagerly.returnTag)
        assertEquals("kotlinx.coroutines.flow.SharingStarted", eagerly.returnTypeName)
        assertEquals("{ (kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly) }", eagerly.lambdaBody)
        assertTrue(!declarations.single { it.bindingName == eagerly.name }.isCompanionHandle)

        val whileSubscribed = entries.single { it.name == "kotlinx.coroutines.flow.SharingStarted.WhileSubscribed" }
        assertEquals("FUNCTION", whileSubscribed.kind)
        assertEquals(2, whileSubscribed.arity)
        assertEquals(listOf("INT", "INT"), whileSubscribed.paramTags)
        assertEquals("OBJECT", whileSubscribed.returnTag)
        assertTrue("kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed(" in whileSubscribed.lambdaBody)
    }

    @Test
    fun nestedObjectHandlesAndPrimitiveConstantsOnAGenericOuterClassBind() {
        val jobKey = entries.single { it.name == "kotlinx.coroutines.Job.Key" }
        assertEquals("STATIC_GETTER", jobKey.kind)
        assertEquals("{ (kotlinx.coroutines.Job.Key) }", jobKey.lambdaBody)
        assertTrue(declarations.single { it.bindingName == jobKey.name }.isCompanionHandle)

        val channelFactory = entries.single { it.name == "kotlinx.coroutines.channels.Channel.Factory" }
        assertEquals("STATIC_GETTER", channelFactory.kind)
        assertEquals("{ (kotlinx.coroutines.channels.Channel.Factory) }", channelFactory.lambdaBody)

        val buffered = entries.single { it.name == "kotlinx.coroutines.channels.Channel.Factory.BUFFERED" }
        assertEquals("STATIC_GETTER", buffered.kind)
        assertEquals("INT", buffered.returnTag)
        assertEquals("{ (kotlinx.coroutines.channels.Channel.Factory.BUFFERED).toLong() }", buffered.lambdaBody)
    }

    @Test
    fun memberPropertiesOfFinalClassesInterfacesAndEnumsBindAsGetters() {
        val coroutineName = entries.single { it.name == "kotlinx.coroutines.CoroutineName.name" }
        assertEquals("GETTER", coroutineName.kind)
        assertEquals(0, coroutineName.arity)
        assertEquals("kotlinx.coroutines.CoroutineName", coroutineName.receiverTypeName)
        assertEquals("STRING", coroutineName.returnTag)
        assertEquals("{ args -> ((args[0] as kotlinx.coroutines.CoroutineName).name) }", coroutineName.lambdaBody)

        val isLazy = entries.single { it.name == "kotlinx.coroutines.CoroutineStart.isLazy" }
        assertEquals("GETTER", isLazy.kind)
        assertEquals("BOOLEAN", isLazy.returnTag)

        val isActive = entries.single { it.name == "kotlinx.coroutines.Job.isActive" }
        assertEquals("GETTER", isActive.kind)
        assertEquals("BOOLEAN", isActive.returnTag)
        assertEquals("kotlinx.coroutines.Job", isActive.receiverTypeName)

        val permits = entries.single { it.name == "kotlinx.coroutines.sync.Semaphore.availablePermits" }
        assertEquals("GETTER", permits.kind)
        assertEquals("INT", permits.returnTag)
    }

    @Test
    fun mutableMemberPropertyEmitsBothGetterAndSetter() {
        val ownerName = AbiQualifiedName(AbiCompoundName("fixture.klib"), AbiCompoundName("MutableBox"))
        val intType = object : AbiType.Simple {
            override val classifierReference: AbiClassifierReference = object : AbiClassifierReference.ClassReference {
                override val className: AbiQualifiedName = AbiQualifiedName(AbiCompoundName("kotlin"), AbiCompoundName("Int"))
            }
            override val arguments: List<AbiTypeArgument> = emptyList()
            override val nullability: AbiTypeNullability = AbiTypeNullability.NOT_SPECIFIED
        }
        val getterFn = FakeAccessorFunction(
            qualifiedName = AbiQualifiedName(AbiCompoundName("fixture.klib"), AbiCompoundName("MutableBox.count.<get-count>")),
            valueParameters = emptyList(),
            returnType = intType,
        )
        val setterFn = FakeAccessorFunction(
            qualifiedName = AbiQualifiedName(AbiCompoundName("fixture.klib"), AbiCompoundName("MutableBox.count.<set-count>")),
            valueParameters = listOf(
                object : AbiValueParameter {
                    override val type: AbiType = intType
                    override val isVararg: Boolean = false
                    override val hasDefaultArg: Boolean = false
                    override val isNoinline: Boolean = false
                    override val isCrossinline: Boolean = false
                },
            ),
            returnType = object : AbiType.Simple {
                override val classifierReference: AbiClassifierReference = object : AbiClassifierReference.ClassReference {
                    override val className: AbiQualifiedName = AbiQualifiedName(AbiCompoundName("kotlin"), AbiCompoundName("Unit"))
                }
                override val arguments: List<AbiTypeArgument> = emptyList()
                override val nullability: AbiTypeNullability = AbiTypeNullability.NOT_SPECIFIED
            },
        )
        val prop = object : AbiProperty {
            override val qualifiedName: AbiQualifiedName =
                AbiQualifiedName(AbiCompoundName("fixture.klib"), AbiCompoundName("MutableBox.count"))
            override val signatures: AbiSignatures = object : AbiSignatures {
                override fun get(signatureVersion: AbiSignatureVersion): String? = null
            }
            override fun hasAnnotation(annotationClassName: AbiQualifiedName): Boolean = false
            override val modality: AbiModality = AbiModality.FINAL
            override val kind: org.jetbrains.kotlin.library.abi.AbiPropertyKind =
                org.jetbrains.kotlin.library.abi.AbiPropertyKind.VAR
            override val getter: AbiFunction = getterFn
            override val setter: AbiFunction = setterFn
            override val backingField: org.jetbrains.kotlin.library.abi.AbiField? = null
        }
        val boxClass = object : AbiClass {
            override val qualifiedName: AbiQualifiedName = ownerName
            override val signatures: AbiSignatures = object : AbiSignatures {
                override fun get(signatureVersion: AbiSignatureVersion): String? = null
            }
            override fun hasAnnotation(annotationClassName: AbiQualifiedName): Boolean = false
            override val modality: AbiModality = AbiModality.FINAL
            override val kind: AbiClassKind = AbiClassKind.CLASS
            override val isInner: Boolean = false
            override val isValue: Boolean = false
            override val isFunction: Boolean = false
            override val typeParameters: List<AbiTypeParameter> = emptyList()
            override val superTypes: List<AbiType> = emptyList()
            override val declarations: List<AbiDeclaration> = listOf(prop)
        }
        val container = object : org.jetbrains.kotlin.library.abi.AbiDeclarationContainer {
            override val declarations: List<AbiDeclaration> = listOf(boxClass)
        }
        val classes = KlibScanner.classesOf(container)
        val candidates = KlibScanner.memberPropertyCandidatesOf(boxClass, classes, classes)
        assertEquals(2, candidates.size)
        val getter = assertNotNull(candidates[0].callable)
        assertEquals("fixture.klib.MutableBox.count", getter.name)
        assertEquals("GETTER", getter.kind)
        assertEquals(0, getter.arity)
        assertEquals("INT", getter.returnTag)
        assertEquals("{ args -> ((args[0] as fixture.klib.MutableBox).count).toLong() }", getter.lambdaBody)

        val setter = assertNotNull(candidates[1].callable)
        assertEquals("fixture.klib.MutableBox.count=", setter.name)
        assertEquals("SETTER", setter.kind)
        assertEquals(1, setter.arity)
        assertEquals(listOf("INT"), setter.paramTags)
        assertEquals("UNIT", setter.returnTag)
        assertEquals("{ args -> (args[0] as fixture.klib.MutableBox).count = (args[1] as Long).toInt(); Unit }", setter.lambdaBody)
    }

    @Test
    fun crossKlibClasspathResolvesHandleTypesFromDependencyKlibs() {
        if (!konanStdlibKlib.isDirectory) {
            println("skipped: no .konan cache at $konanStdlibKlib")
            return
        }
        // Without stdlib on the klib classpath, `CoroutineScope(context: CoroutineContext)` is declined
        // because `kotlin.coroutines.CoroutineContext` is in the stdlib klib rather than coroutinesKlib.
        val withoutClasspath = KlibScanner.scanKlib(
            coroutinesKlib,
            includePrefixes = listOf("kotlinx.coroutines"),
            classpath = listOf(coroutinesKlib),
        )
        assertNull(withoutClasspath.singleOrNull { it.name == "kotlinx.coroutines.CoroutineScope" })

        // With stdlib on the klib classpath, `kotlin.coroutines.CoroutineContext` (in package
        // `kotlin.coroutines`, not `kotlin`) resolves as a handle class and `CoroutineScope` binds!
        val withClasspath = KlibScanner.scanKlib(
            coroutinesKlib,
            includePrefixes = listOf("kotlinx.coroutines"),
            classpath = listOf(coroutinesKlib, konanStdlibKlib),
        )
        val scopeFactory = assertNotNull(withClasspath.singleOrNull { it.name == "kotlinx.coroutines.CoroutineScope" })
        assertEquals(listOf("OBJECT"), scopeFactory.paramTags)
        assertEquals(listOf("kotlin.coroutines.CoroutineContext"), scopeFactory.paramTypeNames)
        assertEquals("OBJECT", scopeFactory.returnTag)
        assertEquals("kotlinx.coroutines.CoroutineScope", scopeFactory.returnTypeName)
    }

    @Test
    fun knmMetadataEnrichesParameterNamesDefaultOmissionAndExtensions() {
        val job = entries.single { it.name == "kotlinx.coroutines.Job" }
        assertEquals(listOf("parent"), job.paramNames)
        assertEquals(listOf(true), job.paramHasDefault)
        assertEquals(listOf(true), omittableSlotsOf(job))
        assertTrue("when ((if (args[0] == null) 1 else 0))" in job.lambdaBody)

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

        val ensureActiveJob = entries.single { it.name == "kotlinx.coroutines.ensureActive__Job" }
        assertEquals("FUNCTION", ensureActiveJob.kind)
        assertEquals("kotlinx.coroutines.Job", ensureActiveJob.receiverTypeName)
        assertEquals(listOf(RECEIVER_PARAMETER_NAME), ensureActiveJob.paramNames)
        assertEquals(listOf("import kotlinx.coroutines.ensureActive as artifact_ext_kotlinx_coroutines_ensureActive"), ensureActiveJob.imports)

        val scopeIsActive = entries.single { it.name == "kotlinx.coroutines.isActive" }
        assertEquals("GETTER", scopeIsActive.kind)
        assertEquals("kotlinx.coroutines.CoroutineScope", scopeIsActive.receiverTypeName)
        assertEquals("BOOLEAN", scopeIsActive.returnTag)

        val dispatchersIo = entries.single { it.name == "kotlinx.coroutines.IO" }
        assertEquals("GETTER", dispatchersIo.kind)
        assertEquals("kotlinx.coroutines.Dispatchers", dispatchersIo.receiverTypeName)
        assertEquals("OBJECT", dispatchersIo.returnTag)

        val closeableClose = entries.single { it.name == "kotlinx.coroutines.CloseableCoroutineDispatcher.close" }
        assertEquals("METHOD", closeableClose.kind)
        assertEquals("kotlinx.coroutines.CloseableCoroutineDispatcher", closeableClose.receiverTypeName)
    }

    @Test
    fun klibValueClassWithPublicConstructorAndPropertyUnboxesAndPrivateStaysHandle() {
        val nr = MapNameResolver(
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
    fun klibComposableBindsPureSourceWithAtMostSixDefaultsAndDeclinesAboveSix() {
        val nr = MapNameResolver(
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
}

private class MapNameResolver(
    private val strings: List<String>,
    private val classes: List<String>,
) : NameResolver {
    override fun getString(index: Int): String = strings[index]
    override fun getQualifiedClassName(index: Int): String = classes[index]
    override fun isLocalClassName(index: Int): Boolean = false
}

@OptIn(ExperimentalLibraryAbiReader::class)
private class FakeAccessorFunction(
    override val qualifiedName: AbiQualifiedName,
    override val valueParameters: List<AbiValueParameter>,
    override val returnType: AbiType,
) : AbiFunction {
    override val signatures: AbiSignatures = object : AbiSignatures {
        override fun get(signatureVersion: AbiSignatureVersion): String? = null
    }
    override fun hasAnnotation(annotationClassName: AbiQualifiedName): Boolean = false
    override val modality: AbiModality = AbiModality.FINAL
    override val typeParameters: List<AbiTypeParameter> = emptyList()
    override val isConstructor: Boolean = false
    override val isInline: Boolean = false
    override val isSuspend: Boolean = false
    override val hasExtensionReceiverParameter: Boolean = false
    override val contextReceiverParametersCount: Int = 0
}
