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
