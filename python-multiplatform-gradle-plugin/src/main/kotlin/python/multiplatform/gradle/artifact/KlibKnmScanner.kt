package python.multiplatform.gradle.artifact

import org.jetbrains.kotlin.konan.file.File as KonanFile
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.ToolingSingleFileKlibResolveStrategy
import org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf
import org.jetbrains.kotlin.library.metadata.parseModuleHeader
import org.jetbrains.kotlin.library.metadata.parsePackageFragment
import org.jetbrains.kotlin.metadata.ProtoBuf
import org.jetbrains.kotlin.metadata.deserialization.Flags
import org.jetbrains.kotlin.metadata.deserialization.NameResolver
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.deserialization.TypeTable
import org.jetbrains.kotlin.metadata.deserialization.inlineClassUnderlyingType
import org.jetbrains.kotlin.metadata.deserialization.receiverType
import org.jetbrains.kotlin.metadata.deserialization.returnType
import org.jetbrains.kotlin.metadata.deserialization.supertypes
import org.jetbrains.kotlin.metadata.deserialization.type
import org.jetbrains.kotlin.metadata.deserialization.upperBounds
import org.jetbrains.kotlin.metadata.deserialization.varargElementType
import org.jetbrains.kotlin.util.DummyLogger
import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import python.multiplatform.gradle.model.ValueClassModel
import java.io.File

/**
 * Step 2 (A1) + Step 3 (Blocker B1) for [KlibScanner] (`thisisthepy/python-multiplatform#191` Phase B):
 * reads `.knm` (`linkdata/`) protobuf fragments from `.klib` archives using the official reader in
 * `kotlin-compiler-embeddable:2.0.20` (`ToolingSingleFileKlibResolveStrategy` +
 * `KlibMetadataDeserializationUtilsKt.parseModuleHeader`/`parsePackageFragment`), with zero new
 * dependencies.
 *
 * ### Why `.knm` metadata instead of `LibraryAbiReader` alone
 *
 * 1. **Kotlin 2.1+ KLIB compatibility:** `LibraryAbiReader.readAbiInfo` deserializes `ir/` and throws
 *    `InvalidProtocolBufferException` on Kotlin 2.1+ KLIBs (`org.thisisthepy.compose.*:1.11.1-ext.4`).
 *    `ToolingSingleFileKlibResolveStrategy` + `parsePackageFragment` reads `linkdata/` (`.knm`) only and
 *    works across Kotlin 2.0 through 2.4 KLIBs.
 * 2. **Complete Kotlin declaration metadata (A1):** `.knm` preserves parameter names
 *    (`parameterNamesKnown = true`), default-value flags (`Flags.DECLARES_DEFAULT_VALUE`), extension
 *    function and property receivers (`receiverType`), `@JvmInline value class`
 *    underlying property names and types (`Dp`, `Color`, `TextUnit`), exact visibility (`PUBLIC` vs
 *    `PROTECTED` on open/abstract classes), and `COMPANION_OBJECT` kind.
 * 3. **Pure-source `@Composable` bindings with `<= 6` defaults (B1):** For `@Composable` top-level and
 *    `object` functions with at most [ArtifactScanner.MAX_OMITTABLE_PARAMETERS] (6) defaulted
 *    parameters, emits a `@androidx.compose.runtime.Composable () -> ReturnType` lambda cast to
 *    `kotlin.Function2<androidx.compose.runtime.Composer?, kotlin.Int, ReturnType>` so the consumer's
 *    Compose compiler plugin lowers the call site without JVM `.class` thunks. Declarations with more
 *    than 6 defaulted parameters are declined explicitly until an IR lowering bridge (B3) is approved.
 */
internal object KlibKnmScanner {

    private const val COMPOSABLE_ANNOTATION = "androidx/compose/runtime/Composable"
    private const val DEPRECATED_ANNOTATION = "kotlin/Deprecated"
    private const val PUBLISHED_API_ANNOTATION = "kotlin/PublishedApi"
    private const val KOTLIN_FUNCTION_PREFIX = "kotlin.Function"
    private const val OBJECT_TAG = "OBJECT"
    private const val STATIC_GETTER_KIND = "STATIC_GETTER"
    private val DATA_COMPONENT = Regex("component[1-9][0-9]*")
    private val ENUM_STATICS = setOf("valueOf", "values", "entries")
    private val ALWAYS_ON_COMPILE_CLASSPATH = listOf("kotlin.")

    private fun isDeprecated(annotations: List<ProtoBuf.Annotation>, nr: NameResolver): Boolean =
        annotations.any { nr.getQualifiedClassName(it.id) == DEPRECATED_ANNOTATION }

    private fun isHiddenDeprecated(annotations: List<ProtoBuf.Annotation>, nr: NameResolver): Boolean {
        val dep = annotations.firstOrNull { nr.getQualifiedClassName(it.id) == DEPRECATED_ANNOTATION } ?: return false
        return dep.argumentList.any { arg ->
            arg.value.hasEnumValueId() && nr.getString(arg.value.enumValueId) == "HIDDEN"
        }
    }

    internal class KnmPackagePart(
        val packageName: String,
        val proto: ProtoBuf.Package,
        val nameResolver: NameResolver,
        val typeTable: TypeTable,
    )

    internal class KnmClassRecord(
        val dottedName: String,
        val packageName: String,
        val relativeName: String,
        val proto: ProtoBuf.Class,
        val nameResolver: NameResolver,
        val typeTable: TypeTable,
    ) {
        val visibility: ProtoBuf.Visibility? get() = Flags.VISIBILITY.get(proto.flags)
        val modality: ProtoBuf.Modality? get() = Flags.MODALITY.get(proto.flags)
        val kind: ProtoBuf.Class.Kind? get() = Flags.CLASS_KIND.get(proto.flags)
        val isInner: Boolean get() = Flags.IS_INNER.get(proto.flags)
        val isValue: Boolean get() = Flags.IS_VALUE_CLASS.get(proto.flags)
        val isFunInterface: Boolean get() = Flags.IS_FUN_INTERFACE.get(proto.flags)
        val rawAnnotations: List<ProtoBuf.Annotation>
            get() = proto.getExtension(KlibMetadataProtoBuf.classAnnotation)
        val annotations: List<String> by lazy {
            rawAnnotations.map { nameResolver.getQualifiedClassName(it.id) }
        }
        val isDeprecated: Boolean get() = isDeprecated(rawAnnotations, nameResolver)
        val isHiddenDeprecated: Boolean get() = isHiddenDeprecated(rawAnnotations, nameResolver)
        val isPublishedApi: Boolean get() = PUBLISHED_API_ANNOTATION in annotations
    }

    internal class KnmLibraryData(
        val packages: List<KnmPackagePart>,
        val classes: Map<String, KnmClassRecord>,
    )

    fun readLibraryOrNull(file: File): KnmLibraryData? {
        val klib: KotlinLibrary = try {
            ToolingSingleFileKlibResolveStrategy.tryResolve(
                KonanFile(file.absolutePath),
                DummyLogger,
            )
        } catch (_: Throwable) {
            null
        } ?: return null

        return try {
            val header = parseModuleHeader(klib.moduleHeaderData)
            val packages = ArrayList<KnmPackagePart>()
            val classes = LinkedHashMap<String, KnmClassRecord>()

            for (pkgName in header.packageFragmentNameList) {
                for (part in klib.packageMetadataParts(pkgName)) {
                    val fragment = parsePackageFragment(
                        klib.packageMetadata(pkgName, part),
                    )
                    val nr = NameResolverImpl(fragment.strings, fragment.qualifiedNames)
                    if (fragment.hasPackage()) {
                        val pkgProto = fragment.`package`
                        packages += KnmPackagePart(
                            packageName = pkgName,
                            proto = pkgProto,
                            nameResolver = nr,
                            typeTable = TypeTable(pkgProto.typeTable),
                        )
                    }
                    for (clsProto in fragment.class_List) {
                        val raw = nr.getQualifiedClassName(clsProto.fqName)
                        val dotted = raw.replace('/', '.')
                        val rel = if (pkgName.isEmpty()) dotted else dotted.removePrefix("$pkgName.")
                        classes.putIfAbsent(
                            dotted,
                            KnmClassRecord(
                                dottedName = dotted,
                                packageName = pkgName,
                                relativeName = rel,
                                proto = clsProto,
                                nameResolver = nr,
                                typeTable = TypeTable(clsProto.typeTable),
                            ),
                        )
                    }
                }
            }
            KnmLibraryData(packages = packages, classes = classes)
        } catch (_: Throwable) {
            null
        }
    }

    fun scan(
        klib: File,
        includePrefixes: List<String>,
        excludePackages: List<String>,
        classpath: List<File>,
    ): List<Candidate>? {
        val ownData = readLibraryOrNull(klib) ?: return null
        val ownClasses = ownData.classes
        val classes = LinkedHashMap<String, KnmClassRecord>(ownClasses)
        for (dep in classpath) {
            if (dep == klib) continue
            val depData = readLibraryOrNull(dep) ?: continue
            for ((name, cls) in depData.classes) {
                classes.putIfAbsent(name, cls)
            }
        }

        val topLevelCandidates = ownData.packages
            .filter { !ArtifactScanner.isExcludedPackage(it.packageName, excludePackages) }
            .flatMap { part ->
                val fns = part.proto.functionList
                    .filter { matchesInclude(part.packageName, "${part.packageName}.${part.nameResolver.getString(it.name)}", includePrefixes) }
                    .mapNotNull { fn ->
                        topLevelFunctionCandidateOrNull(
                            fn = fn,
                            owner = part.packageName,
                            nr = part.nameResolver,
                            tt = part.typeTable,
                            classes = classes,
                        )
                    }
                val props = part.proto.propertyList
                    .filter { matchesInclude(part.packageName, "${part.packageName}.${part.nameResolver.getString(it.name)}", includePrefixes) }
                    .flatMap { prop ->
                        topLevelPropertyCandidatesOf(
                            prop = prop,
                            owner = part.packageName,
                            nr = part.nameResolver,
                            tt = part.typeTable,
                            classes = classes,
                        )
                    }
                fns + props
            }

        val matchedClasses = ownClasses.values
            .filter { matchesInclude(it.packageName, it.dottedName, includePrefixes) }
            .filter { !ArtifactScanner.isExcludedPackage(it.packageName, excludePackages) }

        val constructors = matchedClasses.flatMap { constructorCandidatesOf(it, ownClasses, classes) }
        val objectCandidates = matchedClasses.flatMap { objectCandidatesOf(it, ownClasses, classes) }
        val memberProperties = matchedClasses.flatMap { memberPropertyCandidatesOf(it, ownClasses, classes) }
        val members = matchedClasses.flatMap { memberCandidatesOf(it, ownClasses, classes) }

        val entries = topLevelCandidates + constructors + objectCandidates + memberProperties + members
        val (properties, rest) = entries.partition { it.declaration.kind in ArtifactScanner.PROPERTY_KINDS }
        val (methods, functions) = rest.partition { it.declaration.kind == ArtifactScanner.METHOD_KIND }
        val settledFunctions = ArtifactScanner.disambiguateOverloads(
            ArtifactScanner.applyDefaultOmission(
                ArtifactScanner.dropCollidingConstructors(functions),
            ),
        )
        val settledMethods = ArtifactScanner.settleMethodNames(
            ArtifactScanner.disambiguateOverloads(methods),
            settledFunctions,
        )
        val settledProperties = ArtifactScanner.settlePropertyNames(
            properties,
            settledFunctions + settledMethods,
        )
        return (settledFunctions + settledMethods + settledProperties).map { candidate ->
            val callable = candidate.callable ?: return@map candidate
            val omittable = omittableSlotsOf(callable)
            val receiverIndex = if (callable.receiverTypeName != null && callable.kind == "FUNCTION") 1 else 0
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

    private fun matchesInclude(pkg: String, dotted: String, includePrefixes: List<String>): Boolean {
        if (includePrefixes.isEmpty()) return true
        return includePrefixes.any {
            pkg == it || pkg.startsWith("$it.") || dotted == it || dotted.startsWith("$it.")
        }
    }

    private fun hasUnbindableClassAncestry(cls: KnmClassRecord, ownClasses: Map<String, KnmClassRecord>): Boolean {
        val pkg = cls.packageName
        val segments = cls.relativeName.split('.')
        for (i in 1..segments.size) {
            val prefix = segments.take(i).joinToString(".")
            val fullName = if (pkg.isEmpty()) prefix else "$pkg.$prefix"
            val current = ownClasses[fullName] ?: continue
            if (current.visibility != ProtoBuf.Visibility.PUBLIC || current.isPublishedApi || current.isInner || current.isDeprecated) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ top-level functions & properties

    private fun topLevelFunctionCandidateOrNull(
        fn: ProtoBuf.Function,
        owner: String,
        nr: NameResolver,
        tt: TypeTable,
        classes: Map<String, KnmClassRecord>,
    ): Candidate? {
        val vis = Flags.VISIBILITY.get(fn.flags)
        val annotations = fn.getExtension(KlibMetadataProtoBuf.functionAnnotation).map { nr.getQualifiedClassName(it.id) }
        if (vis != ProtoBuf.Visibility.PUBLIC && PUBLISHED_API_ANNOTATION !in annotations) return null
        if (Flags.MEMBER_KIND.get(fn.flags) == ProtoBuf.MemberKind.SYNTHESIZED) return null
        return functionCandidateOrNull(
            fn = fn,
            owner = owner,
            callOwner = owner,
            ownerIsClass = false,
            nr = nr,
            tt = tt,
            classes = classes,
        )
    }

    private fun topLevelPropertyCandidatesOf(
        prop: ProtoBuf.Property,
        owner: String,
        nr: NameResolver,
        tt: TypeTable,
        classes: Map<String, KnmClassRecord>,
    ): List<Candidate> {
        val propAnnotations = prop.getExtension(KlibMetadataProtoBuf.propertyAnnotation).map { nr.getQualifiedClassName(it.id) }
        val getterAnnotations = prop.getExtension(KlibMetadataProtoBuf.propertyGetterAnnotation).map { nr.getQualifiedClassName(it.id) }
        val allAnnotations = propAnnotations + getterAnnotations
        val vis = Flags.VISIBILITY.get(prop.flags)
        if (vis != ProtoBuf.Visibility.PUBLIC && PUBLISHED_API_ANNOTATION !in allAnnotations) return emptyList()
        if (Flags.MEMBER_KIND.get(prop.flags) == ProtoBuf.MemberKind.SYNTHESIZED) return emptyList()
        val propName = nr.getString(prop.name)
        if (!ArtifactScanner.isWritableParameterName(propName)) return emptyList()

        val receiverProto = prop.receiverType(tt)
        val returnProto = prop.returnType(tt)

        if (receiverProto == null) {
            // Top-level constant (`STATIC_GETTER`).
            if (prop.typeParameterCount > 0) return emptyList()
            if (prop.hasGetterFlags() && Flags.VISIBILITY.get(prop.getterFlags) != ProtoBuf.Visibility.PUBLIC) {
                return emptyList()
            }
            if (DEPRECATED_ANNOTATION in allAnnotations || PUBLISHED_API_ANNOTATION in allAnnotations || COMPOSABLE_ANNOTATION in allAnnotations) {
                return emptyList()
            }
            val returnModel = typeModelOf(returnProto, nr, tt, classes) ?: return emptyList()
            val returnBoundary = klibBoundaryTypeOf(returnModel, classes, BoundaryDirection.RETURN) ?: return emptyList()
            val name = "$owner.$propName"
            val supertypes = if (returnBoundary.tag == OBJECT_TAG) supertypesOf(returnModel.qualifiedName, classes) else emptyList()
            return listOf(
                Candidate(
                    callable = ArtifactCallable(
                        name = name,
                        arity = 0,
                        paramTags = emptyList(),
                        returnTag = returnBoundary.tag,
                        lambdaBody = "{ ${returnBoundary.wrapReturn(name)} }",
                        returnTypeName = returnModel.qualifiedName,
                        returnSupertypes = supertypes,
                        paramNames = emptyList(),
                        paramTypeNames = emptyList(),
                        paramHasDefault = emptyList(),
                        kind = STATIC_GETTER_KIND,
                    ),
                    declaration = DeclarationModel(
                        simpleName = propName,
                        owner = owner,
                        ownerIsClass = false,
                        receiver = null,
                        parameters = emptyList(),
                        returnType = returnModel,
                        parameterNamesKnown = true,
                        isComposable = false,
                        isSuspend = false,
                        kind = STATIC_GETTER_KIND,
                        bindingName = name,
                        returnBoundaryTag = returnBoundary.tag,
                        returnSupertypes = supertypes,
                    ),
                ),
            )
        }

        // Top-level extension property (`GETTER`).
        val receiverModel = typeModelOf(receiverProto, nr, tt, classes) ?: return emptyList()
        val returnModel = typeModelOf(returnProto, nr, tt, classes) ?: return emptyList()
        val key = "$owner.$propName"
        fun declined(reason: String): List<Candidate> = listOf(
            Candidate(
                callable = null,
                declaration = DeclarationModel(
                    simpleName = propName,
                    owner = owner,
                    ownerIsClass = false,
                    receiver = receiverModel,
                    parameters = emptyList(),
                    returnType = returnModel,
                    parameterNamesKnown = true,
                    kind = ArtifactScanner.GETTER_KIND,
                    declineReason = reason,
                ),
            ),
        )

        if (prop.typeParameterCount > 0) {
            return declined("a generic extension property: property syntax has no place to write its type argument")
        }
        if (DEPRECATED_ANNOTATION in allAnnotations) {
            return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
        }
        if (PUBLISHED_API_ANNOTATION in allAnnotations) {
            return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
        }
        if (COMPOSABLE_ANNOTATION in allAnnotations) {
            return declined("a @Composable getter: its value exists only inside a composition")
        }
        if (prop.hasGetterFlags() && Flags.VISIBILITY.get(prop.getterFlags) != ProtoBuf.Visibility.PUBLIC) {
            return declined("its getter is not a public, non-synthetic method (hidden or deprecated)")
        }
        val receiverBoundary = klibBoundaryTypeOf(receiverModel, classes, BoundaryDirection.PARAMETER)
            ?: return declined("no boundary type for receiver ${receiverModel.render()}")
        if (receiverBoundary.tag != OBJECT_TAG) {
            return declined("its receiver crosses as ${receiverBoundary.tag}, so no proxy of it exists to read the property on")
        }
        val missingSupertype = unreachableSupertypeOf(receiverModel.qualifiedName, classes)
        if (missingSupertype != null) {
            return declined(ArtifactScanner.unreachableReceiverReason(missingSupertype, receiverModel.qualifiedName))
        }
        val returnBoundary = klibBoundaryTypeOf(returnModel, classes, BoundaryDirection.RETURN)
            ?: return declined("no boundary type for property type ${returnModel.render()}")

        val alias = "artifact_ext_" + key.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        val imports = listOf("import $owner.$propName as $alias")
        val readExpr = "${receiverBoundary.read("args[0]")}.$alias"
        val supertypes = if (returnBoundary.tag == OBJECT_TAG) supertypesOf(returnModel.qualifiedName, classes) else emptyList()

        return listOf(
            Candidate(
                callable = ArtifactCallable(
                    name = key,
                    arity = 0,
                    paramTags = emptyList(),
                    returnTag = returnBoundary.tag,
                    lambdaBody = "{ args -> ${returnBoundary.wrapReturn(readExpr)} }",
                    imports = imports,
                    receiverTypeName = receiverModel.qualifiedName,
                    paramNames = emptyList(),
                    paramTypeNames = emptyList(),
                    returnTypeName = returnModel.qualifiedName,
                    returnSupertypes = supertypes,
                    paramHasDefault = emptyList(),
                    kind = ArtifactScanner.GETTER_KIND,
                ),
                declaration = DeclarationModel(
                    simpleName = propName,
                    owner = owner,
                    ownerIsClass = false,
                    receiver = receiverModel,
                    receiverBoundaryTag = OBJECT_TAG,
                    parameters = emptyList(),
                    returnType = returnModel,
                    parameterNamesKnown = true,
                    kind = ArtifactScanner.GETTER_KIND,
                    bindingName = key,
                    returnBoundaryTag = returnBoundary.tag,
                    returnSupertypes = supertypes,
                ),
            ),
        )
    }

    // ------------------------------------------------------------------ constructors

    private fun constructorCandidatesOf(
        cls: KnmClassRecord,
        ownClasses: Map<String, KnmClassRecord>,
        classes: Map<String, KnmClassRecord>,
    ): List<Candidate> {
        if (cls.kind != ProtoBuf.Class.Kind.CLASS) return emptyList()
        if (cls.modality == ProtoBuf.Modality.ABSTRACT || cls.modality == ProtoBuf.Modality.SEALED) return emptyList()
        if (cls.isInner || cls.proto.typeParameterCount > 0) return emptyList()
        if ('.' in cls.relativeName) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()
        if (unreachableSupertypeOf(cls.dottedName, classes) != null) return emptyList()

        val clsQualified = cls.dottedName
        val returnModel = classTypeModel(clsQualified, classes) ?: return emptyList()
        val returnBoundary = klibBoundaryTypeOf(returnModel, classes, BoundaryDirection.RETURN) ?: return emptyList()
        val pkg = cls.packageName

        return cls.proto.constructorList
            .filter { Flags.VISIBILITY.get(it.flags) == ProtoBuf.Visibility.PUBLIC }
            .mapNotNull { ctor ->
                constructorCandidateOrNull(ctor, cls, pkg, cls.relativeName, returnModel, returnBoundary, classes)
            }
    }

    private fun constructorCandidateOrNull(
        ctor: ProtoBuf.Constructor,
        cls: KnmClassRecord,
        pkg: String,
        clsSimpleName: String,
        returnModel: KotlinTypeModel,
        returnBoundary: BoundaryType,
        classes: Map<String, KnmClassRecord>,
    ): Candidate? {
        val nr = cls.nameResolver
        val tt = cls.typeTable
        val valueParams = ctor.valueParameterList
        val parameters = valueParams.map { vp ->
            val pType = vp.type(tt)
            DeclaredParameter(
                name = nr.getString(vp.name),
                type = typeModelOf(pType, nr, tt, classes) ?: return null,
                declaresDefault = Flags.DECLARES_DEFAULT_VALUE.get(vp.flags),
            )
        }
        val model = DeclarationModel(
            simpleName = clsSimpleName,
            owner = pkg,
            ownerIsClass = false,
            receiver = null,
            parameters = parameters,
            returnType = returnModel,
            parameterNamesKnown = true,
            isComposable = false,
            isSuspend = false,
            isConstructor = true,
        )
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason), isConstructor = true) }

        val annotations = ctor.getExtension(KlibMetadataProtoBuf.constructorAnnotation).map { nr.getQualifiedClassName(it.id) }
        if (DEPRECATED_ANNOTATION in annotations) {
            return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
        }
        if (PUBLISHED_API_ANNOTATION in annotations) {
            return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
        }
        if (valueParams.any { it.varargElementType(tt) != null }) {
            return declined("vararg parameter")
        }

        val functionSlots = valueParams.mapIndexed { index, vp ->
            val pType = vp.type(tt)
            functionSlotOrNull(pType, parameters[index].type, nr, tt, classes, ownerIsComposable = false)
        }
        functionSlots.firstNotNullOfOrNull { it?.declineReason }?.let { return declined(it) }

        val boundaryTypes = parameters.mapIndexed { index, parameter ->
            functionSlots[index]?.boundary
                ?: klibBoundaryTypeOf(parameter.type, classes, BoundaryDirection.PARAMETER)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        if (boundaryTypes.any { it.isReturnOnly }) {
            return declined("a parameter of a return-only boundary type")
        }

        val paramNames = parameters.map { it.name!! }
        val paramTypeNames = parameters.mapIndexed { index, parameter ->
            functionSlots[index]?.typeName ?: parameter.type.qualifiedName
        }
        val qualifiedName = returnModel.qualifiedName
        val argumentExpressions = boundaryTypes.mapIndexed { index, type -> type.read("args[$index]") }
        val allPresentCall = "$qualifiedName(${argumentExpressions.joinToString(", ")})"
        val body = returnBoundary.wrapReturn(allPresentCall)
        val arity = boundaryTypes.size
        val supertypes = if (returnBoundary.tag == OBJECT_TAG) supertypesOf(qualifiedName, classes) else emptyList()
        val declaredDefaults = parameters.map { it.declaresDefault }
        val omittable = omittableIndicesOf(paramNames, declaredDefaults, receiverIndex = 0)

        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = boundaryTypes.map { it.tag },
            returnTag = returnBoundary.tag,
            lambdaBody = if (arity == 0) "{ $body }" else "{ args -> $body }",
            paramNames = paramNames,
            paramTypeNames = paramTypeNames,
            returnTypeName = qualifiedName,
            returnSupertypes = supertypes,
            paramHasDefault = List(arity) { false },
        )
        val modelParameters = parameters.mapIndexed { index, parameter ->
            parameter.copy(boundaryTag = boundaryTypes[index].tag)
        }
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnBoundary.tag,
                returnSupertypes = supertypes,
                parameters = modelParameters,
            ),
            isConstructor = true,
            plan = if (omittable.isEmpty()) null else ArtifactScanner.DefaultOmissionPlan(
                omittable = omittable.sorted(),
                valueIndices = (0 until arity).toList(),
                declaredDefaults = declaredDefaults,
                buildBody = { allowed ->
                    val branched = klibPresenceBranchedCall(
                        paramNames = paramNames,
                        argumentExpressions = argumentExpressions,
                        receiverIndex = 0,
                        qualifiedName = qualifiedName,
                        ordered = omittable.sorted(),
                        allPresentCall = allPresentCall,
                        allowed = allowed,
                        callTarget = { args -> "$qualifiedName($args)" },
                    )
                    "{ args -> ${returnBoundary.wrapReturn(branched)} }"
                },
            ),
        )
    }

    // ------------------------------------------------------------------ objects & companions

    private fun objectCandidatesOf(
        cls: KnmClassRecord,
        ownClasses: Map<String, KnmClassRecord>,
        classes: Map<String, KnmClassRecord>,
    ): List<Candidate> {
        val kind = cls.kind
        if (kind != ProtoBuf.Class.Kind.OBJECT && kind != ProtoBuf.Class.Kind.COMPANION_OBJECT) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()

        val qualified = cls.dottedName
        val relative = cls.relativeName
        val isNested = '.' in relative
        val outerQualified = if (isNested) qualified.substringBeforeLast('.') else null
        val outerClass = outerQualified?.let { ownClasses[it] }
        if (isNested && (outerClass == null || hasUnbindableClassAncestry(outerClass, ownClasses))) return emptyList()

        val simpleName = relative.substringAfterLast('.')
        val isUnnamedCompanion = kind == ProtoBuf.Class.Kind.COMPANION_OBJECT && simpleName == "Companion"
        val boundOwner = if (isUnnamedCompanion && outerQualified != null) outerQualified else qualified
        val callOwner = qualified

        val handleCandidate = if (outerClass != null && outerClass.kind in PROPERTY_OWNER_KINDS) {
            companionHandleCandidateOrNull(outerQualified, outerClass, simpleName, cls, classes)
        } else {
            null
        }

        val properties = cls.proto.propertyList
            .filter { Flags.VISIBILITY.get(it.flags) == ProtoBuf.Visibility.PUBLIC }
            .filter { Flags.MEMBER_KIND.get(it.flags) != ProtoBuf.MemberKind.SYNTHESIZED }
            .mapNotNull { prop ->
                objectPropertyCandidateOrNull(prop, cls, boundOwner = boundOwner, callOwner = callOwner, classes = classes)
            }

        val functions = cls.proto.functionList
            .filter { Flags.VISIBILITY.get(it.flags) == ProtoBuf.Visibility.PUBLIC }
            .filter { Flags.MEMBER_KIND.get(it.flags) == ProtoBuf.MemberKind.DECLARATION }
            .filter { !isAnyMember(it, cls.nameResolver) }
            .mapNotNull { fn ->
                functionCandidateOrNull(
                    fn = fn,
                    owner = boundOwner,
                    callOwner = callOwner,
                    ownerIsClass = true,
                    nr = cls.nameResolver,
                    tt = cls.typeTable,
                    classes = classes,
                )
            }

        return listOfNotNull(handleCandidate) + properties + functions
    }

    private fun companionHandleCandidateOrNull(
        outerQualified: String,
        outerClass: KnmClassRecord,
        companionName: String,
        companionClass: KnmClassRecord,
        classes: Map<String, KnmClassRecord>,
    ): Candidate? {
        val companionQualified = "$outerQualified.$companionName"
        val ownerIsSupertype = companionClass.proto.supertypes(companionClass.typeTable).any { st ->
            st.hasClassName() && companionClass.nameResolver.getQualifiedClassName(st.className).replace('/', '.') == outerQualified
        }
        val typeName = if (ownerIsSupertype && outerClass.proto.typeParameterCount == 0) outerQualified else companionQualified
        val targetClass = classes[typeName] ?: return null
        if (targetClass.isValue || targetClass.isPublishedApi || targetClass.isDeprecated) return null
        if (targetClass.proto.typeParameterCount > 0) return null

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
                parameterNamesKnown = true,
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

    private fun objectPropertyCandidateOrNull(
        prop: ProtoBuf.Property,
        cls: KnmClassRecord,
        boundOwner: String,
        callOwner: String,
        classes: Map<String, KnmClassRecord>,
    ): Candidate? {
        val nr = cls.nameResolver
        val tt = cls.typeTable
        val propName = nr.getString(prop.name)
        if (!ArtifactScanner.isWritableParameterName(propName)) return null
        if (prop.receiverType(tt) != null || prop.typeParameterCount > 0) return null
        if (prop.hasGetterFlags() && Flags.VISIBILITY.get(prop.getterFlags) != ProtoBuf.Visibility.PUBLIC) return null

        val propAnnotations = prop.getExtension(KlibMetadataProtoBuf.propertyAnnotation).map { nr.getQualifiedClassName(it.id) }
        val getterAnnotations = prop.getExtension(KlibMetadataProtoBuf.propertyGetterAnnotation).map { nr.getQualifiedClassName(it.id) }
        val allAnnotations = propAnnotations + getterAnnotations
        if (DEPRECATED_ANNOTATION in allAnnotations || PUBLISHED_API_ANNOTATION in allAnnotations || COMPOSABLE_ANNOTATION in allAnnotations) {
            return null
        }

        val returnProto = prop.returnType(tt)
        val returnModel = typeModelOf(returnProto, nr, tt, classes) ?: return null
        val returnBoundary = klibBoundaryTypeOf(returnModel, classes, BoundaryDirection.RETURN) ?: return null

        val name = "$boundOwner.$propName"
        val call = "$callOwner.$propName"
        val supertypes = if (returnBoundary.tag == OBJECT_TAG) supertypesOf(returnModel.qualifiedName, classes) else emptyList()
        return Candidate(
            callable = ArtifactCallable(
                name = name,
                arity = 0,
                paramTags = emptyList(),
                returnTag = returnBoundary.tag,
                lambdaBody = "{ ${returnBoundary.wrapReturn(call)} }",
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
                ownerIsClass = true,
                receiver = null,
                parameters = emptyList(),
                returnType = returnModel,
                parameterNamesKnown = true,
                isComposable = false,
                isSuspend = false,
                kind = STATIC_GETTER_KIND,
                bindingName = name,
                returnBoundaryTag = returnBoundary.tag,
                returnSupertypes = supertypes,
            ),
        )
    }

    // ------------------------------------------------------------------ member properties & functions

    private val PROPERTY_OWNER_KINDS = setOf(
        ProtoBuf.Class.Kind.CLASS,
        ProtoBuf.Class.Kind.INTERFACE,
        ProtoBuf.Class.Kind.ENUM_CLASS,
    )

    private fun memberPropertyCandidatesOf(
        cls: KnmClassRecord,
        ownClasses: Map<String, KnmClassRecord>,
        classes: Map<String, KnmClassRecord>,
    ): List<Candidate> {
        if (cls.kind !in PROPERTY_OWNER_KINDS) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()
        val owner = cls.dottedName
        val ownerType = classTypeModel(owner, classes) ?: KotlinTypeModel(owner)
        val generic = cls.proto.typeParameterCount > 0
        if (!generic && handleClassOrNull(ownerType, classes) == null) return emptyList()

        val unbindable = when {
            generic -> "a member of a generic class: not bound yet"
            else -> unreachableSupertypeOf(owner, classes)?.let { missing ->
                ArtifactScanner.unreachableReceiverReason(missing, owner)
            }
        }
        val isEnum = cls.kind == ProtoBuf.Class.Kind.ENUM_CLASS
        return cls.proto.propertyList
            .filter { Flags.VISIBILITY.get(it.flags) == ProtoBuf.Visibility.PUBLIC }
            .filter { Flags.MEMBER_KIND.get(it.flags) == ProtoBuf.MemberKind.DECLARATION }
            .filter { !(isEnum && cls.nameResolver.getString(it.name) in ENUM_STATICS) }
            .flatMap { prop ->
                memberPropertyCandidatesFor(prop, cls, owner, ownerType, unbindable, classes)
            }
    }

    private fun memberPropertyCandidatesFor(
        prop: ProtoBuf.Property,
        cls: KnmClassRecord,
        owner: String,
        ownerType: KotlinTypeModel,
        unbindable: String?,
        classes: Map<String, KnmClassRecord>,
    ): List<Candidate> {
        val nr = cls.nameResolver
        val tt = cls.typeTable
        val propName = nr.getString(prop.name)
        if (!ArtifactScanner.isWritableParameterName(propName)) return emptyList()
        val returnProto = prop.returnType(tt)
        val typeModel = typeModelOf(returnProto, nr, tt, classes) ?: return emptyList()
        val getterModel = DeclarationModel(
            simpleName = propName,
            owner = owner,
            ownerIsClass = true,
            receiver = ownerType,
            receiverBoundaryTag = OBJECT_TAG,
            parameters = emptyList(),
            returnType = typeModel,
            parameterNamesKnown = true,
            kind = ArtifactScanner.GETTER_KIND,
        )
        fun declined(reason: String) = listOf(Candidate(null, getterModel.copy(declineReason = reason)))

        val propAnnotations = prop.getExtension(KlibMetadataProtoBuf.propertyAnnotation).map { nr.getQualifiedClassName(it.id) }
        val getterAnnotations = prop.getExtension(KlibMetadataProtoBuf.propertyGetterAnnotation).map { nr.getQualifiedClassName(it.id) }
        val allGetterAnnotations = propAnnotations + getterAnnotations

        when {
            unbindable != null -> return declined(unbindable)
            DEPRECATED_ANNOTATION in allGetterAnnotations ->
                return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
            PUBLISHED_API_ANNOTATION in allGetterAnnotations ->
                return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
            COMPOSABLE_ANNOTATION in allGetterAnnotations ->
                return declined("a @Composable getter: its value exists only inside a composition")
            prop.receiverType(tt) != null ->
                return declined("a member extension: it needs two receivers")
            prop.contextReceiverTypeCount > 0 || prop.contextReceiverTypeIdCount > 0 ->
                return declined("${maxOf(prop.contextReceiverTypeCount, prop.contextReceiverTypeIdCount)} context receiver(s)")
            prop.hasGetterFlags() && Flags.VISIBILITY.get(prop.getterFlags) != ProtoBuf.Visibility.PUBLIC ->
                return declined("its getter is not a public, non-synthetic method (hidden or deprecated)")
        }

        val returns = klibBoundaryTypeOf(typeModel, classes, BoundaryDirection.RETURN)
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

        val setterAnnotations = prop.getExtension(KlibMetadataProtoBuf.propertySetterAnnotation).map { nr.getQualifiedClassName(it.id) }
        val setterCandidate = if (
            Flags.IS_VAR.get(prop.flags) &&
            (!prop.hasSetterFlags() || Flags.VISIBILITY.get(prop.setterFlags) == ProtoBuf.Visibility.PUBLIC) &&
            DEPRECATED_ANNOTATION !in setterAnnotations &&
            PUBLISHED_API_ANNOTATION !in setterAnnotations &&
            COMPOSABLE_ANNOTATION !in setterAnnotations
        ) {
            val valueBoundary = klibBoundaryTypeOf(typeModel, classes, BoundaryDirection.PARAMETER)?.takeIf { !it.isReturnOnly }
            if (valueBoundary != null) {
                val setterKey = "$key="
                val paramName = if (prop.hasSetterValueParameter()) {
                    nr.getString(prop.setterValueParameter.name).takeIf { ArtifactScanner.isWritableParameterName(it) } ?: "value"
                } else {
                    "value"
                }
                Candidate(
                    callable = ArtifactCallable(
                        name = setterKey,
                        arity = 1,
                        paramTags = listOf(valueBoundary.tag),
                        returnTag = "UNIT",
                        lambdaBody = "{ args -> $readExpr = ${valueBoundary.read("args[1]")}; Unit }",
                        receiverTypeName = owner,
                        paramNames = listOf(paramName),
                        paramTypeNames = listOf(typeModel.qualifiedName),
                        returnTypeName = "kotlin.Unit",
                        paramHasDefault = listOf(false),
                        kind = ArtifactScanner.SETTER_KIND,
                    ),
                    declaration = getterModel.copy(
                        kind = ArtifactScanner.SETTER_KIND,
                        bindingName = setterKey,
                        parameters = listOf(DeclaredParameter(paramName, typeModel, declaresDefault = false, boundaryTag = valueBoundary.tag)),
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

    private fun memberCandidatesOf(
        cls: KnmClassRecord,
        ownClasses: Map<String, KnmClassRecord>,
        classes: Map<String, KnmClassRecord>,
    ): List<Candidate> {
        if (cls.kind !in PROPERTY_OWNER_KINDS) return emptyList()
        if (hasUnbindableClassAncestry(cls, ownClasses)) return emptyList()
        val owner = cls.dottedName
        val ownerType = classTypeModel(owner, classes) ?: KotlinTypeModel(owner)
        val generic = cls.proto.typeParameterCount > 0
        if (!generic && handleClassOrNull(ownerType, classes) == null) return emptyList()

        val nr = cls.nameResolver
        val propertyNames = cls.proto.propertyList.mapTo(HashSet()) { nr.getString(it.name) }
        val unbindable = unreachableSupertypeOf(owner, classes)?.let { missing ->
            ArtifactScanner.unreachableReceiverReason(missing, owner)
        }
        val isEnum = cls.kind == ProtoBuf.Class.Kind.ENUM_CLASS
        return cls.proto.functionList
            .filter { Flags.VISIBILITY.get(it.flags) == ProtoBuf.Visibility.PUBLIC }
            .filter { Flags.MEMBER_KIND.get(it.flags) == ProtoBuf.MemberKind.DECLARATION }
            .filter { fn -> !isAnyMember(fn, nr) && !(isEnum && nr.getString(fn.name) in ENUM_STATICS) }
            .mapNotNull { fn ->
                memberCandidateOrNull(fn, cls, owner, ownerType, generic, unbindable, propertyNames, classes)
            }
    }

    private fun memberCandidateOrNull(
        fn: ProtoBuf.Function,
        cls: KnmClassRecord,
        owner: String,
        ownerType: KotlinTypeModel,
        generic: Boolean,
        unbindable: String?,
        propertyNames: Set<String>,
        classes: Map<String, KnmClassRecord>,
    ): Candidate? {
        val nr = cls.nameResolver
        val tt = cls.typeTable
        val name = nr.getString(fn.name)
        val annotations = fn.getExtension(KlibMetadataProtoBuf.functionAnnotation).map { nr.getQualifiedClassName(it.id) }
        val isComposable = COMPOSABLE_ANNOTATION in annotations
        val isSuspend = Flags.IS_SUSPEND.get(fn.flags)

        // Match KlibScanner's handle rule (`noBoundDeclarationCarriesAGenericType`): type parameters
        // and functional interfaces (`Runnable(block: () -> Unit)`) are not substituted or synthesized
        // on non-composable KLIB functions unless the function is `@Composable`.
        val substitutableIds = if (isComposable) substitutableTypeParameterIds(fn.typeParameterList, nr, tt) else emptySet()
        val hasReified = fn.typeParameterList.any { it.reified }
        val hasUnsubstitutable = fn.typeParameterCount > 0 && (!isComposable || substitutableIds == null)

        val valueParams = fn.valueParameterList
        val parameters = valueParams.map { vp ->
            val rawType = vp.type(tt)
            val model = typeModelOf(rawType, nr, tt, classes, substitutableIds.orEmpty()) ?: return null
            DeclaredParameter(
                name = nr.getString(vp.name),
                type = model,
                declaresDefault = Flags.DECLARES_DEFAULT_VALUE.get(vp.flags),
            )
        }
        val returnProto = fn.returnType(tt)
        val returnModel = typeModelOf(returnProto, nr, tt, classes, substitutableIds.orEmpty()) ?: return null

        val model = DeclarationModel(
            simpleName = name,
            owner = owner,
            ownerIsClass = true,
            receiver = ownerType,
            receiverBoundaryTag = OBJECT_TAG,
            parameters = parameters.map { it.copy(declaresDefault = false) },
            returnType = returnModel,
            parameterNamesKnown = true,
            isComposable = isComposable,
            isSuspend = isSuspend,
            kind = ArtifactScanner.METHOD_KIND,
        )
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }

        when {
            isSuspend -> return declined("suspend")
            hasReified -> return declined("a reified type parameter: its type argument is read at run time, so kotlin.Any? cannot stand for it")
            hasUnsubstitutable -> return declined("a bounded or reified type parameter: kotlin.Any? cannot stand for it")
            generic -> return declined("a member of a generic class: not bound yet")
            unbindable != null -> return declined(unbindable)
            DEPRECATED_ANNOTATION in annotations ->
                return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
            PUBLISHED_API_ANNOTATION in annotations ->
                return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
            isComposable -> return declined("a @Composable member function: not bound on a proxy")
            fn.receiverType(tt) != null -> return declined("a member extension: it needs two receivers")
            fn.contextReceiverTypeCount > 0 || fn.contextReceiverTypeIdCount > 0 ->
                return declined("${maxOf(fn.contextReceiverTypeCount, fn.contextReceiverTypeIdCount)} context receiver(s)")
            name in propertyNames -> return declined("shares its name with a property of the class, which keeps the attribute")
            name == "copy" || DATA_COMPONENT.matches(name) ->
                return declined("a data-class synthetic (copy/componentN), or a member this klib ABI cannot tell from one")
            valueParams.any { it.varargElementType(tt) != null } ->
                return declined("vararg parameter")
        }

        val functionSlots = valueParams.mapIndexed { index, vp ->
            val pType = vp.type(tt)
            functionSlotOrNull(pType, parameters[index].type, nr, tt, classes, ownerIsComposable = false)
        }
        functionSlots.firstNotNullOfOrNull { it?.declineReason }?.let { return declined(it) }

        val returnType = klibBoundaryTypeOf(returnModel, classes, BoundaryDirection.RETURN)
            ?: return declined("no boundary type for return ${returnModel.render()}")
        val boundaryTypes = parameters.mapIndexed { index, parameter ->
            functionSlots[index]?.boundary
                ?: klibBoundaryTypeOf(parameter.type, classes, BoundaryDirection.PARAMETER)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        if (boundaryTypes.any { it.isReturnOnly }) return declined("a parameter of a return-only boundary type")

        val key = "$owner.$name"
        val typeArgs = if (fn.typeParameterCount == 0) "" else List(fn.typeParameterCount) { "kotlin.Any?" }.joinToString(", ", "<", ">")
        val argumentExpressions = boundaryTypes.mapIndexed { index, type -> type.read("args[${index + 1}]") }
        val body = returnType.wrapReturn("(args[0] as $owner).$name$typeArgs(${argumentExpressions.joinToString(", ")})")
        val supertypes = if (returnType.tag == OBJECT_TAG) supertypesOf(returnModel.qualifiedName, classes) else emptyList()
        val paramNames = parameters.map { it.name!! }
        val paramTypeNames = parameters.mapIndexed { index, parameter ->
            functionSlots[index]?.typeName ?: parameter.type.qualifiedName
        }

        val callable = ArtifactCallable(
            name = key,
            arity = boundaryTypes.size,
            paramTags = boundaryTypes.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = "{ args -> $body }",
            receiverTypeName = owner,
            paramNames = paramNames,
            paramTypeNames = paramTypeNames,
            returnTypeName = returnModel.qualifiedName,
            returnSupertypes = supertypes,
            paramHasDefault = List(boundaryTypes.size) { false },
            kind = ArtifactScanner.METHOD_KIND,
        )
        val modelParameters = parameters.mapIndexed { index, parameter ->
            parameter.copy(boundaryTag = boundaryTypes[index].tag, declaresDefault = false)
        }
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = key,
                returnBoundaryTag = returnType.tag,
                returnSupertypes = supertypes,
                parameters = modelParameters,
            ),
        )
    }

    // ------------------------------------------------------------------ top-level & object functions (incl. B1 @Composable)

    internal fun functionCandidateOrNull(
        fn: ProtoBuf.Function,
        owner: String,
        callOwner: String,
        ownerIsClass: Boolean,
        nr: NameResolver,
        tt: TypeTable,
        classes: Map<String, KnmClassRecord>,
    ): Candidate? {
        val name = nr.getString(fn.name)
        val annotations = fn.getExtension(KlibMetadataProtoBuf.functionAnnotation).map { nr.getQualifiedClassName(it.id) }
        val isComposable = COMPOSABLE_ANNOTATION in annotations
        val isSuspend = Flags.IS_SUSPEND.get(fn.flags)

        // Match KlibScanner's handle rule (`noBoundDeclarationCarriesAGenericType`): type parameters
        // and functional interfaces (`Runnable(block: () -> Unit)`) are not substituted or synthesized
        // on non-composable KLIB functions unless the function is `@Composable`.
        val substitutableIds = if (isComposable) substitutableTypeParameterIds(fn.typeParameterList, nr, tt) else emptySet()
        val hasReified = fn.typeParameterList.any { it.reified }
        val hasUnsubstitutable = fn.typeParameterCount > 0 && (!isComposable || substitutableIds == null)

        val receiverProto = fn.receiverType(tt)
        val receiverModel = if (receiverProto != null) {
            typeModelOf(receiverProto, nr, tt, classes, substitutableIds.orEmpty()) ?: return null
        } else {
            null
        }
        val valueParams = fn.valueParameterList
        val parameters = valueParams.map { vp ->
            val pType = vp.type(tt)
            val model = typeModelOf(pType, nr, tt, classes, substitutableIds.orEmpty()) ?: return null
            DeclaredParameter(
                name = nr.getString(vp.name),
                type = model,
                declaresDefault = Flags.DECLARES_DEFAULT_VALUE.get(vp.flags),
            )
        }
        val returnProto = fn.returnType(tt)
        val returnModel = typeModelOf(returnProto, nr, tt, classes, substitutableIds.orEmpty()) ?: return null

        val model = DeclarationModel(
            simpleName = name,
            owner = owner,
            ownerIsClass = ownerIsClass,
            receiver = receiverModel,
            parameters = parameters,
            returnType = returnModel,
            parameterNamesKnown = true,
            isComposable = isComposable,
            isSuspend = isSuspend,
            kind = "FUNCTION",
        )
        val declined = { reason: String -> Candidate(null, model.copy(declineReason = reason)) }

        if (isSuspend) return declined("suspend")
        if (hasReified) {
            return declined("a reified type parameter: its type argument is read at run time, so kotlin.Any? cannot stand for it")
        }
        if (hasUnsubstitutable) {
            return declined("a bounded or reified type parameter: kotlin.Any? cannot stand for it")
        }
        if (DEPRECATED_ANNOTATION in annotations) {
            return declined("@Deprecated: its level is not in this klib ABI, and a HIDDEN one cannot be called from source")
        }
        if (PUBLISHED_API_ANNOTATION in annotations) {
            return declined("@PublishedApi internal -- binary-visible for inlining, not part of the module's public Kotlin API")
        }
        if (ownerIsClass && receiverProto != null) {
            return declined("a member extension: it needs two receivers")
        }
        if (fn.contextReceiverTypeCount > 0 || fn.contextReceiverTypeIdCount > 0) {
            return declined("${maxOf(fn.contextReceiverTypeCount, fn.contextReceiverTypeIdCount)} context receiver(s)")
        }
        if (ownerIsClass && (name == "copy" || DATA_COMPONENT.matches(name))) {
            return declined("a data-class synthetic (copy/componentN), or a member this klib ABI cannot tell from one")
        }
        if (valueParams.any { it.varargElementType(tt) != null }) {
            return declined("vararg parameter")
        }

        val isExtension = receiverModel != null
        val receiverIndex = if (isExtension) 1 else 0
        if (isExtension) {
            val missing = unreachableSupertypeOf(receiverModel!!.qualifiedName, classes)
            if (missing != null) {
                return declined(ArtifactScanner.unreachableReceiverReason(missing, receiverModel.qualifiedName))
            }
        }

        val receiverBoundary = if (isExtension) {
            klibBoundaryTypeOf(receiverModel!!, classes, BoundaryDirection.PARAMETER)
                ?: return declined("no boundary type for parameter ${receiverModel.render()}")
        } else {
            null
        }

        val functionSlots = valueParams.mapIndexed { index, vp ->
            val pType = vp.type(tt)
            if (isComposable) {
                functionSlotOrNull(pType, parameters[index].type, nr, tt, classes, ownerIsComposable = true)
                    ?: functionalInterfaceSlotOrNull(pType, parameters[index].type, classes)
            } else {
                functionSlotOrNull(pType, parameters[index].type, nr, tt, classes, ownerIsComposable = false)
            }
        }
        functionSlots.firstNotNullOfOrNull { it?.declineReason }?.let { return declined(it) }

        val valueBoundaries = parameters.mapIndexed { index, parameter ->
            functionSlots[index]?.boundary
                ?: klibBoundaryTypeOf(parameter.type, classes, BoundaryDirection.PARAMETER)
                ?: return declined("no boundary type for parameter ${parameter.type.render()}")
        }
        val allBoundaries = listOfNotNull(receiverBoundary) + valueBoundaries
        if (allBoundaries.any { it.isReturnOnly }) {
            return declined("a parameter of a return-only boundary type")
        }

        val returnType = klibBoundaryTypeOf(returnModel, classes, BoundaryDirection.RETURN)
            ?: return declined("no boundary type for return ${returnModel.render()}")

        val allParamNames = (if (isExtension) listOf(RECEIVER_PARAMETER_NAME) else emptyList()) + parameters.map { it.name!! }
        val allParamTypeNames = (if (isExtension) listOf(receiverModel!!.qualifiedName) else emptyList()) +
            parameters.mapIndexed { index, parameter ->
                functionSlots[index]?.typeName ?: parameter.type.qualifiedName
            }
        val allDeclaredDefaults = (if (isExtension) listOf(false) else emptyList()) + parameters.map { it.declaresDefault }

        val qualifiedName = "$owner.$name"
        val alias = "artifact_ext_" + qualifiedName.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
        val imports = if (isExtension) listOf("import $callOwner.$name as $alias") else emptyList()
        val typeArgs = if (substitutableIds != null && fn.typeParameterCount > 0) {
            List(fn.typeParameterCount) { "kotlin.Any?" }.joinToString(", ", "<", ">")
        } else {
            ""
        }

        val argumentExpressions = allBoundaries.mapIndexed { index, type -> type.read("args[$index]") }
        val callTarget: (String) -> String = { args ->
            if (isExtension) {
                "${argumentExpressions.first()}.$alias$typeArgs($args)"
            } else {
                "$callOwner.$name$typeArgs($args)"
            }
        }
        val allPresentCall = callTarget(argumentExpressions.drop(receiverIndex).joinToString(", "))
        val supertypes = if (returnType.tag == OBJECT_TAG) supertypesOf(returnModel.qualifiedName, classes) else emptyList()
        val modelParameters = parameters.mapIndexed { index, parameter ->
            val modelType = functionSlots[index]?.modelType ?: parameter.type
            parameter.copy(type = modelType, boundaryTag = valueBoundaries[index].tag)
        }

        if (isComposable) {
            return composableCandidateOrNull(
                qualifiedName = qualifiedName,
                model = model,
                modelParameters = modelParameters,
                isExtension = isExtension,
                receiverIndex = receiverIndex,
                receiverModel = receiverModel,
                receiverBoundary = receiverBoundary,
                allBoundaries = allBoundaries,
                returnModel = returnModel,
                returnType = returnType,
                supertypes = supertypes,
                allParamNames = allParamNames,
                allParamTypeNames = allParamTypeNames,
                allDeclaredDefaults = allDeclaredDefaults,
                argumentExpressions = argumentExpressions,
                imports = imports,
                allPresentCall = allPresentCall,
                callTarget = callTarget,
                classes = classes,
                declined = declined,
            )
        }

        val arity = allBoundaries.size
        val body = returnType.wrapReturn(allPresentCall)
        val omittable = omittableIndicesOf(allParamNames, allDeclaredDefaults, receiverIndex)
        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = arity,
            paramTags = allBoundaries.map { it.tag },
            returnTag = returnType.tag,
            lambdaBody = if (arity == 0) "{ $body }" else "{ args -> $body }",
            imports = imports,
            receiverTypeName = receiverModel?.qualifiedName,
            paramNames = allParamNames,
            paramTypeNames = allParamTypeNames,
            returnTypeName = returnModel.qualifiedName,
            returnSupertypes = supertypes,
            paramHasDefault = List(arity) { false },
            kind = "FUNCTION",
        )
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnType.tag,
                receiverBoundaryTag = receiverBoundary?.tag,
                returnSupertypes = supertypes,
                parameters = modelParameters,
            ),
            plan = if (omittable.isEmpty()) null else ArtifactScanner.DefaultOmissionPlan(
                omittable = omittable.sorted(),
                valueIndices = (receiverIndex until arity).toList(),
                declaredDefaults = allDeclaredDefaults,
                buildBody = { allowed ->
                    val branched = klibPresenceBranchedCall(
                        paramNames = allParamNames,
                        argumentExpressions = argumentExpressions,
                        receiverIndex = receiverIndex,
                        qualifiedName = qualifiedName,
                        ordered = omittable.sorted(),
                        allPresentCall = allPresentCall,
                        allowed = allowed,
                        callTarget = callTarget,
                    )
                    "{ args -> ${returnType.wrapReturn(branched)} }"
                },
            ),
        )
    }

    /**
     * Step 3 (Blocker B1): pure-source `@Composable` binding for KLIB top-level and `object` functions
     * with at most [ArtifactScanner.MAX_OMITTABLE_PARAMETERS] (6) defaulted parameters.
     */
    private fun composableCandidateOrNull(
        qualifiedName: String,
        model: DeclarationModel,
        modelParameters: List<DeclaredParameter>,
        isExtension: Boolean,
        receiverIndex: Int,
        receiverModel: KotlinTypeModel?,
        receiverBoundary: BoundaryType?,
        allBoundaries: List<BoundaryType>,
        returnModel: KotlinTypeModel,
        returnType: BoundaryType,
        supertypes: List<String>,
        allParamNames: List<String>,
        allParamTypeNames: List<String>,
        allDeclaredDefaults: List<Boolean>,
        argumentExpressions: List<String>,
        imports: List<String>,
        allPresentCall: String,
        callTarget: (String) -> String,
        classes: Map<String, KnmClassRecord>,
        declined: (String) -> Candidate,
    ): Candidate {
        val defaultedIndices = allDeclaredDefaults.indices.filter { it >= receiverIndex && allDeclaredDefaults[it] }
        if (defaultedIndices.size > ArtifactScanner.MAX_OMITTABLE_PARAMETERS) {
            return declined("more than ${ArtifactScanner.MAX_OMITTABLE_PARAMETERS} defaulted parameters on a klib @Composable: requires an IR lowering bridge")
        }
        if (defaultedIndices.isNotEmpty() && allParamNames.drop(receiverIndex).any { !ArtifactScanner.isWritableParameterName(it) }) {
            return declined("a parameter name on a defaulted klib @Composable is not writable in Kotlin source")
        }
        val renderedReturnType = renderKotlinSourceType(returnModel, classes, depth = 0)
            ?: return declined("unspellable @Composable return type ${returnModel.render()}")

        val declaredCount = allBoundaries.size
        val changedCount = ComposableShape.changedCountFor(declaredCount)
        val defaultCount = if (defaultedIndices.isEmpty()) 0 else ComposableShape.defaultCountFor(declaredCount)
        val shape = ComposableShape(declaredCount, changedCount, defaultCount)
        val syntheticNames = shape.syntheticParameterNames()
        val syntheticTags = listOf(OBJECT_TAG) + List(changedCount + defaultCount) { "INT" }
        val syntheticTypeNames = syntheticNames.map {
            if (it == ArtifactScanner.COMPOSER_PARAMETER_NAME) "androidx.compose.runtime.Composer" else "kotlin.Int"
        }

        val fullParamNames = allParamNames + syntheticNames
        val fullParamTags = allBoundaries.map { it.tag } + syntheticTags
        val fullParamTypeNames = allParamTypeNames + syntheticTypeNames
        val fullArity = fullParamTags.size

        val composerRead = "(args[${shape.composerIndex}] as androidx.compose.runtime.Composer?)"
        val changedRead = "(args[${shape.firstChangedIndex}] as Long).toInt()"
        fun wrapComposableBlock(innerCall: String): String {
            val blockExpr = "run { val block: @androidx.compose.runtime.Composable () -> $renderedReturnType = { $innerCall }; " +
                "(block as kotlin.Function2<androidx.compose.runtime.Composer?, kotlin.Int, $renderedReturnType>).invoke($composerRead, $changedRead) }"
            return "{ args -> ${returnType.wrapReturn(blockExpr)} }"
        }

        val callable = ArtifactCallable(
            name = qualifiedName,
            arity = fullArity,
            paramTags = fullParamTags,
            returnTag = returnType.tag,
            lambdaBody = wrapComposableBlock(allPresentCall),
            imports = imports,
            receiverTypeName = receiverModel?.qualifiedName,
            paramNames = fullParamNames,
            paramTypeNames = fullParamTypeNames,
            returnTypeName = returnModel.qualifiedName,
            returnSupertypes = supertypes,
            paramHasDefault = List(fullArity) { false },
            kind = "FUNCTION",
        )
        return Candidate(
            callable = callable,
            declaration = model.copy(
                bindingName = qualifiedName,
                returnBoundaryTag = returnType.tag,
                receiverBoundaryTag = receiverBoundary?.tag,
                returnSupertypes = supertypes,
                parameters = modelParameters,
            ),
            plan = if (defaultedIndices.isEmpty()) null else ArtifactScanner.DefaultOmissionPlan(
                omittable = defaultedIndices.sorted(),
                valueIndices = (receiverIndex until declaredCount).toList(),
                declaredDefaults = allDeclaredDefaults,
                buildBody = { allowed ->
                    val branched = klibComposableBranchedCall(
                        paramNames = allParamNames,
                        argumentExpressions = argumentExpressions,
                        receiverIndex = receiverIndex,
                        firstDefaultIndex = shape.firstDefaultIndex,
                        qualifiedName = qualifiedName,
                        ordered = defaultedIndices.sorted(),
                        allPresentCall = allPresentCall,
                        allowed = allowed,
                        callTarget = callTarget,
                    )
                    wrapComposableBlock(branched)
                },
            ),
        )
    }

    // ------------------------------------------------------------------ default omission helpers

    private fun omittableIndicesOf(
        names: List<String>,
        defaults: List<Boolean>,
        receiverIndex: Int,
    ): Set<Int> {
        if (names.size != defaults.size) return emptySet()
        val indices = defaults.indices.filter { index ->
            index >= receiverIndex && defaults[index] && names[index] != RECEIVER_PARAMETER_NAME
        }
        if (indices.isEmpty() || indices.size > ArtifactScanner.MAX_OMITTABLE_PARAMETERS) return emptySet()
        if (names.drop(receiverIndex).any { !ArtifactScanner.isWritableParameterName(it) }) return emptySet()
        return indices.toSet()
    }

    private fun klibPresenceBranchedCall(
        paramNames: List<String>,
        argumentExpressions: List<String>,
        receiverIndex: Int,
        qualifiedName: String,
        ordered: List<Int>,
        allPresentCall: String,
        allowed: Set<Set<Int>>,
        callTarget: (String) -> String,
    ): String {
        val subject = ordered.mapIndexed { bit, index ->
            "(if (args[$index] == null) ${1 shl bit} else 0)"
        }.joinToString(" + ")
        return renderBranchedWhen(
            subject = subject,
            paramNames = paramNames,
            argumentExpressions = argumentExpressions,
            receiverIndex = receiverIndex,
            qualifiedName = qualifiedName,
            ordered = ordered,
            allPresentCall = allPresentCall,
            allowed = allowed,
            callTarget = callTarget,
        )
    }

    private fun klibComposableBranchedCall(
        paramNames: List<String>,
        argumentExpressions: List<String>,
        receiverIndex: Int,
        firstDefaultIndex: Int,
        qualifiedName: String,
        ordered: List<Int>,
        allPresentCall: String,
        allowed: Set<Set<Int>>,
        callTarget: (String) -> String,
    ): String {
        // `pythonx._bind` sets bit `valueIndex` (`index - receiverIndex`) in `args[firstDefaultIndex]`
        // when a `@Composable` parameter is omitted; checking `args[index] == null` as well handles
        // direct Kotlin callers in unit tests that pass `null` with `$default = 0`.
        val subject = ordered.mapIndexed { bit, index ->
            val valueBit = 1 shl (index - receiverIndex)
            "(if ((((args[$firstDefaultIndex] as Long).toInt() and $valueBit) != 0) || args[$index] == null) ${1 shl bit} else 0)"
        }.joinToString(" + ")
        return renderBranchedWhen(
            subject = subject,
            paramNames = paramNames,
            argumentExpressions = argumentExpressions,
            receiverIndex = receiverIndex,
            qualifiedName = qualifiedName,
            ordered = ordered,
            allPresentCall = allPresentCall,
            allowed = allowed,
            callTarget = callTarget,
        )
    }

    private fun renderBranchedWhen(
        subject: String,
        paramNames: List<String>,
        argumentExpressions: List<String>,
        receiverIndex: Int,
        qualifiedName: String,
        ordered: List<Int>,
        allPresentCall: String,
        allowed: Set<Set<Int>>,
        callTarget: (String) -> String,
    ): String {
        fun callOmitting(omitted: Set<Int>): String {
            val arguments = argumentExpressions.indices
                .filter { it >= receiverIndex && it !in omitted }
                .joinToString(", ") { "${ArtifactScanner.writeParameterName(paramNames[it])} = ${argumentExpressions[it]}" }
            return callTarget(arguments)
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
                        omitted.joinToString(" and ") { paramNames[it] } +
                        " is ambiguous with another overload of the same name; write ${
                            if (omitted.size == 1) "it" else "at least one of them"
                        }\")"
                }
                appendLine("    $label -> $branch")
            }
            append("}")
        }
    }

    // ------------------------------------------------------------------ function-typed & SAM slots

    private class KlibFunctionSlot(
        val typeName: String? = null,
        val boundary: BoundaryType? = null,
        val declineReason: String? = null,
        val modelType: KotlinTypeModel? = null,
    )

    private fun functionSlotOrNull(
        protoType: ProtoBuf.Type,
        modelType: KotlinTypeModel,
        nr: NameResolver,
        tt: TypeTable,
        classes: Map<String, KnmClassRecord>,
        ownerIsComposable: Boolean,
    ): KlibFunctionSlot? {
        val declared = modelType.qualifiedName
        if (!declared.startsWith(KOTLIN_FUNCTION_PREFIX)) return null
        if (!ownerIsComposable) return null
        if (Flags.SUSPEND_TYPE.get(protoType.flags)) {
            return KlibFunctionSlot(
                declineReason = "a suspend function-typed parameter ($declared): its compiled shape takes a " +
                    "Continuation and answers COROUTINE_SUSPENDED, which no Python callable can do",
            )
        }
        val declaredArity = declared.removePrefix(KOTLIN_FUNCTION_PREFIX).toIntOrNull() ?: return null
        val typeAnnotations = protoType.getExtension(KlibMetadataProtoBuf.typeAnnotation).map { nr.getQualifiedClassName(it.id) }
        val isComposableLambda = COMPOSABLE_ANNOTATION in typeAnnotations
        if (isComposableLambda && !ownerIsComposable) {
            return KlibFunctionSlot(
                declineReason = "a @Composable function-typed parameter of a declaration that is not itself " +
                    "@Composable: generated Kotlin is compiled without the Compose plugin, so it cannot " +
                    "produce the lowered function type the callee takes",
            )
        }
        val args = modelType.arguments
        if (args.size != declaredArity + 1 || args.any { it == null }) {
            return KlibFunctionSlot(
                declineReason = "a function-typed parameter this walk cannot describe ($declared): a type " +
                    "argument has no name a proxy could be built over",
            )
        }
        if (protoType.argumentList.any { it.projection != ProtoBuf.Type.Argument.Projection.INV }) {
            return KlibFunctionSlot(
                declineReason = "a function-typed parameter generated Kotlin cannot spell ($declared): some " +
                    "part of its signature is not a nameable public type",
            )
        }
        val rendered = renderKotlinFunctionType(modelType, classes, isComposableLambda = isComposableLambda)
            ?: return KlibFunctionSlot(
                declineReason = "a function-typed parameter generated Kotlin cannot spell ($declared): some " +
                    "part of its signature is not a nameable public type",
            )
        val compiledArity = if (isComposableLambda) declaredArity + 2 else declaredArity
        val mark = if (isComposableLambda) ArtifactScanner.COMPOSABLE_TYPE_MARK else ""
        val names = args.map { it!!.qualifiedName }
        val slotTypeName = KOTLIN_FUNCTION_PREFIX + compiledArity + mark +
            "(" + names.dropLast(1).joinToString(",") + ")" + ArtifactScanner.FUNCTION_RETURNS + names.last()

        val cast = if (modelType.isNullable) {
            if (isComposableLambda) "(%s as ($rendered)?)" else "(%s as $rendered?)"
        } else {
            "(%s as $rendered)"
        }
        return KlibFunctionSlot(
            typeName = slotTypeName,
            boundary = BoundaryType(OBJECT_TAG, cast, "(%s)"),
        )
    }

    private fun functionalInterfaceSlotOrNull(
        protoType: ProtoBuf.Type,
        modelType: KotlinTypeModel,
        classes: Map<String, KnmClassRecord>,
    ): KlibFunctionSlot? {
        val qualified = modelType.qualifiedName
        if (qualified.startsWith(KOTLIN_FUNCTION_PREFIX)) return null
        if (modelType.arguments.isNotEmpty() || protoType.argumentCount > 0) return null
        val cls = classes[qualified] ?: return null
        if (cls.kind != ProtoBuf.Class.Kind.INTERFACE || !cls.isFunInterface) return null
        if (cls.visibility != ProtoBuf.Visibility.PUBLIC || cls.proto.typeParameterCount > 0) return null

        val nr = cls.nameResolver
        val tt = cls.typeTable
        val abstractMethods = cls.proto.functionList.filter { fn ->
            Flags.VISIBILITY.get(fn.flags) == ProtoBuf.Visibility.PUBLIC &&
                Flags.MODALITY.get(fn.flags) == ProtoBuf.Modality.ABSTRACT &&
                !isAnyMember(fn, nr)
        }
        val method = abstractMethods.singleOrNull() ?: return null
        val returnProto = method.returnType(tt)
        val returnModel = typeModelOf(returnProto, nr, tt, classes) ?: return null
        if (returnModel.qualifiedName != ArtifactScanner.UNIT_TYPE_NAME || returnModel.isNullable) return null

        val parameterTypes = method.valueParameterList.map { vp ->
            val pProto = vp.type(tt)
            val pModel = typeModelOf(pProto, nr, tt, classes) ?: return null
            val rendered = renderKotlinSourceType(pModel, classes, depth = 0) ?: return null
            pModel.qualifiedName to rendered
        }
        val arity = parameterTypes.size
        val functionType = "kotlin.Function$arity<" + (parameterTypes.map { it.second } + ArtifactScanner.UNIT_TYPE_NAME).joinToString(", ") + ">"
        val star = "kotlin.Function$arity<" + List(arity + 1) { "*" }.joinToString(", ") + ">"
        val names = (0 until arity).map { "p$it" }
        val arrow = if (arity == 0) "" else names.joinToString(", ", postfix = " -> ")
        val convert = "v -> if (v is $star) $qualified { $arrow(v as $functionType)(${names.joinToString(", ")}) } else v as $qualified"
        val read = if (modelType.isNullable) "((%s)?.let { $convert })" else "((%s).let { $convert })"
        return KlibFunctionSlot(
            typeName = qualified + ArtifactScanner.SAM_SEPARATOR + KOTLIN_FUNCTION_PREFIX + arity +
                "(" + parameterTypes.joinToString(",") { it.first } + ")" + ArtifactScanner.FUNCTION_RETURNS + ArtifactScanner.UNIT_TYPE_NAME,
            boundary = BoundaryType(OBJECT_TAG, read, "(%s)"),
            modelType = KotlinTypeModel(
                "kotlin.Function$arity",
                isNullable = modelType.isNullable,
                arguments = parameterTypes.map { KotlinTypeModel(it.first) } + KotlinTypeModel(ArtifactScanner.UNIT_TYPE_NAME),
            ),
        )
    }

    private fun renderKotlinFunctionType(
        modelType: KotlinTypeModel,
        classes: Map<String, KnmClassRecord>,
        isComposableLambda: Boolean,
    ): String? {
        val args = modelType.arguments.map { arg ->
            arg ?: return null
            renderKotlinSourceType(arg, classes, depth = 1) ?: return null
        }
        if (isComposableLambda) {
            val paramPart = args.dropLast(1).joinToString(", ")
            val returnPart = args.last()
            return "@androidx.compose.runtime.Composable ($paramPart) -> $returnPart"
        }
        return "${modelType.qualifiedName}<${args.joinToString(", ")}>"
    }

    private fun renderKotlinSourceType(
        modelType: KotlinTypeModel,
        classes: Map<String, KnmClassRecord>,
        depth: Int,
    ): String? {
        if (depth > 4) return null
        val base = modelType.qualifiedName
        val writable = base == "kotlin.Any" ||
            base.startsWith(KOTLIN_FUNCTION_PREFIX) ||
            primitiveBoundaryTypeOf(base) != null ||
            isNameablePublicClass(base, classes)
        if (!writable) return null
        val suffix = if (modelType.isNullable) "?" else ""
        if (modelType.arguments.isEmpty()) return base + suffix
        val renderedArgs = modelType.arguments.map { arg ->
            arg ?: return null
            renderKotlinSourceType(arg, classes, depth + 1) ?: return null
        }
        return "$base<${renderedArgs.joinToString(", ")}>$suffix"
    }

    private fun isNameablePublicClass(qualifiedName: String, classes: Map<String, KnmClassRecord>): Boolean {
        val cls = classes[qualifiedName] ?: return false
        return cls.visibility == ProtoBuf.Visibility.PUBLIC && !cls.isPublishedApi && !cls.isDeprecated
    }

    // ------------------------------------------------------------------ types, value classes, handles & supertypes

    private fun substitutableTypeParameterIds(
        typeParameters: List<ProtoBuf.TypeParameter>,
        nr: NameResolver,
        tt: TypeTable,
    ): Set<Int>? {
        val ids = HashSet<Int>()
        for (tp in typeParameters) {
            if (tp.reified) return null
            val upperBounds = tp.upperBounds(tt)
            val bounded = upperBounds.any { bound ->
                if (!bound.hasClassName()) return@any true
                val name = nr.getQualifiedClassName(bound.className).replace('/', '.')
                name != "kotlin.Any" || !bound.nullable
            }
            if (bounded) return null
            ids += tp.id
        }
        return ids
    }

    private fun typeModelOf(
        protoType: ProtoBuf.Type?,
        nr: NameResolver,
        tt: TypeTable,
        classes: Map<String, KnmClassRecord>,
        substitutableTypeParamIds: Set<Int> = emptySet(),
        depth: Int = 0,
    ): KotlinTypeModel? {
        if (depth > 8) return null
        if (protoType == null) return KotlinTypeModel("kotlin.Unit")
        if (protoType.hasTypeParameter() || protoType.hasTypeParameterName()) {
            val tpId = if (protoType.hasTypeParameter()) protoType.typeParameter else return null
            if (tpId !in substitutableTypeParamIds) return null
            if ((protoType.flags and (1 shl 1)) != 0) return null
            return KotlinTypeModel("kotlin.Any", isNullable = true)
        }
        if (!protoType.hasClassName()) return null
        val qualifiedName = nr.getQualifiedClassName(protoType.className).replace('/', '.')
        val arguments = protoType.argumentList.map { arg ->
            if (arg.projection == ProtoBuf.Type.Argument.Projection.STAR) {
                null
            } else {
                val argType = arg.type(tt) ?: return null
                typeModelOf(argType, nr, tt, classes, substitutableTypeParamIds, depth + 1) ?: return null
            }
        }
        val valueClass = valueClassModelOf(qualifiedName, classes, depth + 1)
        return KotlinTypeModel(
            qualifiedName = qualifiedName,
            isNullable = protoType.nullable,
            arguments = arguments,
            valueClass = valueClass,
        )
    }

    private fun classTypeModel(qualifiedName: String, classes: Map<String, KnmClassRecord>): KotlinTypeModel? {
        val valueClass = valueClassModelOf(qualifiedName, classes, depth = 1)
        return KotlinTypeModel(qualifiedName = qualifiedName, valueClass = valueClass)
    }

    private data class KnmValueClassInfo(
        val qualifiedName: String,
        val propertyName: String,
        val underlyingModel: KotlinTypeModel,
        val constructorIsPublic: Boolean,
        val propertyIsPublic: Boolean,
    )

    private fun valueClassInfoOf(
        qualifiedName: String,
        classes: Map<String, KnmClassRecord>,
        depth: Int = 0,
    ): KnmValueClassInfo? {
        if (depth > 8) return null
        val cls = classes[qualifiedName] ?: return null
        if (!cls.isValue || cls.visibility != ProtoBuf.Visibility.PUBLIC || cls.proto.typeParameterCount > 0) return null
        if (!cls.proto.hasInlineClassUnderlyingPropertyName()) return null
        val nr = cls.nameResolver
        val tt = cls.typeTable
        val propName = nr.getString(cls.proto.inlineClassUnderlyingPropertyName)
        val propProto = cls.proto.propertyList.firstOrNull { nr.getString(it.name) == propName }
        val underlyingProto = cls.proto.inlineClassUnderlyingType(tt)
            ?: propProto?.let { it.returnType(tt) }
            ?: return null
        val underlyingModel = typeModelOf(underlyingProto, nr, tt, classes, depth = depth + 1) ?: return null
        val constructorIsPublic = cls.proto.constructorList.any {
            Flags.VISIBILITY.get(it.flags) == ProtoBuf.Visibility.PUBLIC && it.valueParameterCount == 1
        }
        val propertyIsPublic = propProto != null &&
            Flags.VISIBILITY.get(propProto.flags) == ProtoBuf.Visibility.PUBLIC &&
            (!propProto.hasGetterFlags() || Flags.VISIBILITY.get(propProto.getterFlags) == ProtoBuf.Visibility.PUBLIC)
        return KnmValueClassInfo(
            qualifiedName = qualifiedName,
            propertyName = propName,
            underlyingModel = underlyingModel,
            constructorIsPublic = constructorIsPublic,
            propertyIsPublic = propertyIsPublic,
        )
    }

    private fun valueClassModelOf(
        qualifiedName: String,
        classes: Map<String, KnmClassRecord>,
        depth: Int,
    ): ValueClassModel? {
        val info = valueClassInfoOf(qualifiedName, classes, depth) ?: return null
        return ValueClassModel(
            underlying = info.underlyingModel,
            constructorIsPublic = info.constructorIsPublic,
            propertyIsPublic = info.propertyIsPublic,
        )
    }

    private fun klibBoundaryTypeOf(
        type: KotlinTypeModel,
        classes: Map<String, KnmClassRecord>,
        direction: BoundaryDirection,
        depth: Int = 0,
    ): BoundaryType? {
        if (depth > 8) return null
        if (type.qualifiedName == "kotlin.CharSequence") {
            if (direction != BoundaryDirection.RETURN) return null
            return if (type.isNullable) {
                BoundaryType("STRING", { error("kotlin.CharSequence is return-only") }, { "($it)?.toString()" }, isReturnOnly = true)
            } else {
                BoundaryType("STRING", { error("kotlin.CharSequence is return-only") }, { "($it).toString()" }, isReturnOnly = true)
            }
        }
        if (!type.isNullable) {
            primitiveBoundaryTypeOf(type.qualifiedName)?.let { return it }
        } else {
            if (primitiveBoundaryTypeOf(type.qualifiedName) != null) return null
            if (type.qualifiedName == "kotlin.Any") {
                return null
            }
        }

        if (!type.isNullable) {
            val info = valueClassInfoOf(type.qualifiedName, classes, depth)
            if (info != null) {
                val usable = when (direction) {
                    BoundaryDirection.PARAMETER -> info.constructorIsPublic && info.propertyIsPublic
                    BoundaryDirection.RETURN -> info.propertyIsPublic
                }
                if (usable) {
                    val underlying = klibBoundaryTypeOf(info.underlyingModel, classes, direction, depth + 1)
                    if (underlying != null && !underlying.isReturnOnly) {
                        return BoundaryType(
                            tag = underlying.tag,
                            readFn = { slot -> "${info.qualifiedName}(${underlying.read(slot)})" },
                            wrapFn = { call -> underlying.wrapReturn("($call).${info.propertyName}") },
                        )
                    }
                }
            }
            if (handleClassOrNull(type, classes) != null) {
                return BoundaryType(OBJECT_TAG, "(%s as ${type.qualifiedName})", "(%s)")
            }
        } else {
            // A nullable value class boxes on Kotlin/Native and JVM alike, so it stays declined;
            // only a non-value handle class may cross as a nullable OBJECT handle.
            val handleCls = handleClassOrNull(type, classes)
            if (handleCls != null && !handleCls.isValue) {
                return BoundaryType(OBJECT_TAG, "(%s as ${type.qualifiedName}?)", "(%s)")
            }
        }
        // Issue #38: a concrete type with spellable type arguments (`SnapshotMutationPolicy<kotlin.Any?>`).
        if (type.arguments.isNotEmpty() && isSpellableParameterizedHandle(type, classes)) {
            val rendered = renderKotlinSourceType(type, classes, depth = 0) ?: return null
            return BoundaryType(OBJECT_TAG, "(%s as $rendered)", "(%s)")
        }
        return null
    }

    private fun isSpellableParameterizedHandle(
        type: KotlinTypeModel,
        classes: Map<String, KnmClassRecord>,
    ): Boolean {
        if (type.qualifiedName.substringBeforeLast('.') == "kotlin" || type.qualifiedName.startsWith("kotlin.collections.")) return false
        val cls = classes[type.qualifiedName] ?: return false
        if (cls.visibility != ProtoBuf.Visibility.PUBLIC || cls.kind !in HANDLE_CLASS_KINDS) return false
        if (cls.isValue || cls.isPublishedApi || cls.isDeprecated) return false
        return type.arguments.all { it != null && (it.qualifiedName == "kotlin.Any" && it.isNullable) }
    }

    private fun handleClassOrNull(type: KotlinTypeModel, classes: Map<String, KnmClassRecord>): KnmClassRecord? {
        if (type.arguments.isNotEmpty()) return null
        if (type.qualifiedName.substringBeforeLast('.') == "kotlin") return null
        val cls = classes[type.qualifiedName] ?: return null
        if (cls.visibility != ProtoBuf.Visibility.PUBLIC) return null
        if (cls.kind !in HANDLE_CLASS_KINDS || cls.proto.typeParameterCount > 0) return null
        if (cls.isPublishedApi || cls.isDeprecated) return null
        if (cls.isValue) {
            // A non-generic value class whose underlying value cannot be opened both ways (such as
            // `Color`, `TextUnit`, `TextUnitType`, `Duration`) crosses as an object handle, matching
            // `KotlinMetadata.resolveKotlinBoundary` on the JVM side.
            val info = valueClassInfoOf(type.qualifiedName, classes)
            val opensAsPrimitive = info != null &&
                info.constructorIsPublic &&
                info.propertyIsPublic &&
                klibBoundaryTypeOf(info.underlyingModel, classes, BoundaryDirection.PARAMETER, depth = 1)?.takeIf { it.tag != OBJECT_TAG } != null
            if (opensAsPrimitive) return null
        }
        return cls
    }

    private val HANDLE_CLASS_KINDS = setOf(
        ProtoBuf.Class.Kind.CLASS,
        ProtoBuf.Class.Kind.INTERFACE,
        ProtoBuf.Class.Kind.ENUM_CLASS,
        ProtoBuf.Class.Kind.OBJECT,
        ProtoBuf.Class.Kind.COMPANION_OBJECT,
    )

    private fun primitiveBoundaryTypeOf(qualifiedName: String): BoundaryType? = when (qualifiedName) {
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

    private fun supertypesOf(qualifiedName: String, classes: Map<String, KnmClassRecord>): List<String> {
        val ordered = LinkedHashSet<String>()
        var frontier = listOf(qualifiedName)
        repeat(16) {
            val next = frontier.flatMap { name ->
                val cls = classes[name] ?: return@flatMap emptyList()
                cls.proto.supertypes(cls.typeTable).mapNotNull { st ->
                    if (!st.hasClassName()) return@mapNotNull null
                    val parentName = cls.nameResolver.getQualifiedClassName(st.className).replace('/', '.')
                    val parentCls = classes[parentName]
                    if (parentCls != null && (parentCls.visibility != ProtoBuf.Visibility.PUBLIC || parentCls.isPublishedApi)) {
                        return@mapNotNull null
                    }
                    parentName
                }
            }.filter { it != "kotlin.Any" && it != qualifiedName && ordered.add(it) }
            if (next.isEmpty()) return ordered.toList()
            frontier = next
        }
        return ordered.toList()
    }

    private fun unreachableSupertypeOf(qualifiedName: String, classes: Map<String, KnmClassRecord>): String? {
        val start = classes[qualifiedName] ?: return null
        val visited = HashSet<String>()
        val pending = ArrayDeque<KnmClassRecord>()
        pending.addLast(start)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            for (st in current.proto.supertypes(current.typeTable)) {
                if (!st.hasClassName()) continue
                val parentName = current.nameResolver.getQualifiedClassName(st.className).replace('/', '.')
                if (!visited.add(parentName)) continue
                if (parentName == "kotlin.Any" || ALWAYS_ON_COMPILE_CLASSPATH.any { parentName.startsWith(it) }) continue
                val parentNode = classes[parentName] ?: return parentName
                pending.addLast(parentNode)
            }
        }
        return null
    }

    private fun isAnyMember(fn: ProtoBuf.Function, nr: NameResolver): Boolean = when (nr.getString(fn.name)) {
        "toString", "hashCode" -> fn.valueParameterCount == 0 && !fn.hasReceiverType() && !fn.hasReceiverTypeId()
        "equals" -> fn.valueParameterCount == 1 && !fn.hasReceiverType() && !fn.hasReceiverTypeId()
        else -> false
    }

    private fun KotlinTypeModel.render(): String = qualifiedName + if (isNullable) "?" else ""
}
