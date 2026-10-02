package python.multiplatform.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility

/** What one module's worth of scanning produced, plus the files it came from -- the latter is
 * what [com.google.devtools.ksp.processing.Dependencies] needs for incremental processing. */
class ScanResult(val fragment: FragmentModel, val originatingFiles: List<KSFile>)

/** `kotlin.Enum`'s synthetics. Both return a collection, which the boundary cannot marshal. */
private val ENUM_SYNTHETIC_FUNCTIONS = setOf("values", "valueOf")
private const val ENUM_SYNTHETIC_ENTRIES_PROPERTY = "entries"

/** Where the generated body for a `suspend fun` starts its coroutine. */
private const val PENDING_CALL = "python.multiplatform.ffi.upcall.PendingCall"

/**
 * The `callable` lambda for one function entry.
 *
 * A `suspend fun` cannot be called from the ordinary `(Array<Any?>) -> Any?` body -- there is no
 * continuation there to give it -- so its call goes inside `PendingCall.start { }`, which is itself
 * a `suspend` lambda and needs nothing but `kotlin-stdlib` to build. The entry then evaluates to a
 * `PendingCall` and carries `isSuspend = true`, which is what tells the trampoline to unwrap it.
 * See `docs/design/upcall.md` §5.2 and [BindingPolicy.isSuspending].
 *
 * [resultExpr] has already been through [wrapReturnExpression], so a `suspend fun` returning `Int`
 * widens inside the coroutine and the boundary still sees the `Long` its `INT` tag promises.
 */
private fun functionBody(function: KSFunctionDeclaration, resultExpr: String): String =
    if (BindingPolicy.isSuspending(function)) "{ args -> $PENDING_CALL.start { $resultExpr } }"
    else "{ args -> $resultExpr }"

/**
 * [CallableEntryModel.paramNames] for a declared parameter list, in declaration order.
 *
 * Unlike the artefact walker's ASM path over a compiled jar -- which carries no name at all for a
 * classfile with no debug info, and says so in its own `paramNames` doc -- every parameter KSP
 * sees came from Kotlin source, where a name is mandatory syntax. `it.name` is nullable only
 * because [KSValueParameter] is shared with a lambda parameter's synthetic node; a function's own
 * declared parameter always resolves one, and the `?:` fallback exists so a producer bug here is a
 * blank string in generated source rather than a `NullPointerException` during the scan.
 */
private fun paramNamesOf(params: List<KSValueParameter>): List<String> =
    params.map { it.name?.asString() ?: "" }

/** [CallableEntryModel.paramHasDefault] for a declared parameter list, in declaration order. */
private fun paramHasDefaultOf(params: List<KSValueParameter>): List<Boolean> =
    params.map { it.hasDefault }

/**
 * Walks every file KSP knows about in this compilation and turns the exposed surface
 * (`docs/design/binding-policy.md`) into a [FragmentModel] -- the pure, KSP-independent shape
 * [renderFragmentSource] turns into text.
 *
 * ### What each Kotlin shape becomes
 *
 * | Kotlin | entries |
 * |---|---|
 * | top-level `fun` | `FUNCTION` |
 * | top-level `val` / `var` | `STATIC_GETTER` / `STATIC_SETTER` |
 * | `class` | `CONSTRUCTOR` (concrete classes only), `METHOD`, `GETTER` / `SETTER` |
 * | `companion object` member | folded into the owner: `FUNCTION`, `STATIC_GETTER` / `STATIC_SETTER` |
 * | `object` | the same, under the object's own name; no constructor |
 * | `interface` | `METHOD`, `GETTER` / `SETTER`; no constructor -- receivers arrive as handles |
 * | `enum class` | one `STATIC_GETTER` per entry, plus `name`, `ordinal`, `valueOf`, and its own members |
 * | `annotation class` | nothing -- see [BindingPolicy.isExposedClass] |
 *
 * A `var` gets its setter entry only when the *setter* is public -- `private`/`protected`/
 * `internal set` is a read-only property as far as the table is concerned. See
 * [BindingPolicy.isExposedSetter].
 *
 * A `suspend fun` gets the entry its non-suspending counterpart would -- same [CallableEntryModel.kind],
 * same receiver slot, same [CallableEntryModel.returnTag] -- with its call wrapped in
 * `PendingCall.start { }` and [CallableEntryModel.isSuspend] set. See [functionBody].
 */
class FragmentScanner(private val excludePackages: List<String>, private val logger: KSPLogger) {

    fun scan(resolver: Resolver, moduleName: String): ScanResult {
        val files = resolver.getAllFiles().toList()

        val entries = mutableListOf<CallableEntryModel>()
        val classes = mutableListOf<ClassModel>()

        files.flatMap { it.declarations }.forEach { declaration ->
            when (declaration) {
                is KSFunctionDeclaration ->
                    if (BindingPolicy.isExposedTopLevelFunction(declaration, excludePackages) &&
                        exposableOrWarn(declaration.qualifiedName!!.asString().substringBeforeLast('.'), declaration)
                    ) {
                        entries += topLevelFunctionEntry(declaration)
                    }

                is KSPropertyDeclaration ->
                    if (isExposedTopLevelProperty(declaration)) {
                        // A top-level property is a module attribute in Python: no receiver, and
                        // the call site is the package rather than a type.
                        val packageName = declaration.qualifiedName!!.asString().substringBeforeLast('.')
                        if (exposableOrWarn(packageName, declaration)) {
                            entries += staticPropertyEntries(packageName, packageName, declaration)
                        }
                    }

                is KSClassDeclaration -> scanClassTree(declaration, entries, classes)

                else -> {}
            }
        }

        return ScanResult(FragmentModel(moduleName, entries.distinctByName(), classes), files)
    }

    /**
     * The type-usage counterpart to the silence [BindingPolicy.isSuspending]'s doc records for a
     * plain shape rejection: a declaration that clears every shape check
     * ([BindingPolicy.isExposedFunctionShape]-family) but names a type
     * ([BindingPolicy.hasExposableTypes]) with no callable entry of its own is not an ordinary,
     * expected exclusion the way `private`/`internal` visibility is -- it is the exact silent
     * failure mode `docs/design/upcall.md` §5.3 measured for `suspend` function types, and
     * the whole point of this task is that it should not go unlogged a second time.
     */
    private fun warnUnexposableType(name: String, declaration: KSNode) {
        logger.warn(
            "python-multiplatform-ksp: $name has a type with no exposable classifier (a `suspend` " +
                "function type, or one nested in a generic argument) and is not exposed to Python -- " +
                "see docs/design/upcall.md §5.3.",
            declaration,
        )
    }

    /** [BindingPolicy.hasExposableTypes], warning through [warnUnexposableType] on a `false`. */
    private fun exposableOrWarn(owner: String, function: KSFunctionDeclaration): Boolean {
        val exposable = BindingPolicy.hasExposableTypes(function)
        if (!exposable) warnUnexposableType("$owner.${function.simpleName.asString()}", function)
        return exposable
    }

    /** [exposableOrWarn] for a property's own type. */
    private fun exposableOrWarn(owner: String, property: KSPropertyDeclaration): Boolean {
        val exposable = BindingPolicy.hasExposableTypes(property)
        if (!exposable) warnUnexposableType("$owner.${property.simpleName.asString()}", property)
        return exposable
    }

    private fun isExposedTopLevelProperty(property: KSPropertyDeclaration): Boolean {
        val qualifiedName = property.qualifiedName?.asString() ?: return false
        if (BindingPolicy.isExcludedByPackage(qualifiedName, excludePackages)) return false
        return BindingPolicy.isExposedProperty(property)
    }

    /**
     * One class-like declaration and everything nested inside it.
     *
     * A declaration that is not itself exposed stops the walk rather than being skipped past:
     * `@PythonInternal` on a class means its whole surface, and a nested class of a private class
     * is not reachable from generated source anyway.
     */
    private fun scanClassTree(
        classDeclaration: KSClassDeclaration,
        entries: MutableList<CallableEntryModel>,
        classes: MutableList<ClassModel>,
    ) {
        if (!BindingPolicy.isExposedClass(classDeclaration, excludePackages)) return

        val (classEntries, classModel) = classEntriesAndModel(classDeclaration)
        entries += classEntries
        classes += classModel

        classDeclaration.declarations.filterIsInstance<KSClassDeclaration>()
            .filter { !it.isCompanionObject && it.classKind != ClassKind.ENUM_ENTRY }
            .forEach { scanClassTree(it, entries, classes) }
    }

    private fun topLevelFunctionEntry(function: KSFunctionDeclaration): CallableEntryModel {
        val qualifiedName = function.qualifiedName!!.asString()
        val paramShapes = function.parameters.map { it.type.toShape() }
        val returnShape = function.returnType?.toShape() ?: TypeShape("kotlin.Unit", false)
        val argExprs = paramShapes.mapIndexed { i, shape -> castExpression(shape, "args[$i]") }
        val callExpr = "$qualifiedName(${argExprs.joinToString(", ")})"
        return CallableEntryModel(
            name = qualifiedName,
            arity = paramShapes.size,
            paramTags = paramShapes.map(::tagFor),
            returnTag = tagFor(returnShape),
            kind = "FUNCTION",
            lambdaBody = functionBody(function, wrapReturnExpression(returnShape, callExpr)),
            isSuspend = BindingPolicy.isSuspending(function),
            paramNames = paramNamesOf(function.parameters),
            paramTypeNames = paramShapes.map { it.rendered },
            returnTypeName = returnShape.rendered,
            paramHasDefault = paramHasDefaultOf(function.parameters),
        )
    }

    private fun classEntriesAndModel(classDeclaration: KSClassDeclaration): Pair<List<CallableEntryModel>, ClassModel> {
        val qualifiedName = classDeclaration.qualifiedName!!.asString()
        val isObject = classDeclaration.classKind == ClassKind.OBJECT
        val isEnum = classDeclaration.classKind == ClassKind.ENUM_CLASS
        val entries = mutableListOf<CallableEntryModel>()

        if (BindingPolicy.isConstructible(classDeclaration)) {
            classDeclaration.primaryConstructor
                ?.takeIf { it.getVisibility() == Visibility.PUBLIC && !BindingPolicy.hasPythonInternal(it) }
                ?.let { ctor ->
                    if (BindingPolicy.hasExposableTypes(ctor)) {
                        entries += constructorEntry(qualifiedName, ctor)
                    } else {
                        warnUnexposableType("$qualifiedName.<init>", ctor)
                    }
                }
        }

        if (isEnum) entries += enumEntries(classDeclaration, qualifiedName)

        classDeclaration.declarations.filterIsInstance<KSFunctionDeclaration>()
            .filter { BindingPolicy.isExposedMemberFunction(it) }
            .filter { !isEnum || it.simpleName.asString() !in ENUM_SYNTHETIC_FUNCTIONS }
            .filter { !BindingPolicy.isCompilerGeneratedDataClassMember(classDeclaration, it) }
            .filter { exposableOrWarn(qualifiedName, it) }
            .forEach { function ->
                entries += if (isObject) {
                    staticFunctionEntry(qualifiedName, qualifiedName, function)
                } else {
                    memberFunctionEntry(qualifiedName, function)
                }
            }

        val properties = classDeclaration.declarations.filterIsInstance<KSPropertyDeclaration>()
            .filter { BindingPolicy.isExposedProperty(it) }
            .filter { !isEnum || it.simpleName.asString() != ENUM_SYNTHETIC_ENTRIES_PROPERTY }
            .filter { property -> exposableOrWarn(qualifiedName, property) }
            .toList()
        properties.forEach { property ->
            entries += if (isObject) {
                staticPropertyEntries(qualifiedName, qualifiedName, property)
            } else {
                memberPropertyEntries(qualifiedName, property)
            }
        }

        entries += companionEntries(classDeclaration, qualifiedName)

        // Only a publicly readable field can be read from the generated fragment at all; a
        // private `PyObject` field is as invisible to `tp_traverse` as it is to Python, and
        // emitting a read of one produced source that did not compile.
        val pyObjectFields = properties.filter { isPyObjectType(it.type.resolve()) }
        val traverseBody = if (pyObjectFields.isEmpty()) null else traverseBody(qualifiedName, pyObjectFields)

        val deduped = entries.distinctByName()
        val model = ClassModel(
            name = qualifiedName,
            memberNames = deduped.map { it.name },
            traverseBody = traverseBody,
            kind = reflectedClassKind(classDeclaration),
            enumEntryNames = if (isEnum) enumEntryNames(classDeclaration) else emptyList(),
        )
        return deduped to model
    }

    private fun reflectedClassKind(classDeclaration: KSClassDeclaration): String =
        when (classDeclaration.classKind) {
            ClassKind.INTERFACE -> "INTERFACE"
            ClassKind.ENUM_CLASS -> "ENUM"
            ClassKind.OBJECT -> "OBJECT"
            else -> "CLASS"
        }

    private fun enumEntryNames(classDeclaration: KSClassDeclaration): List<String> =
        classDeclaration.declarations.filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toList()

    /**
     * An enum's fixed instance set, plus the two `kotlin.Enum` members Python needs to map a
     * handle back onto the mirror it built.
     *
     * `values()` and `entries` are deliberately **not** exposed: both return a collection and the
     * boundary has no collection marshalling, so Python would get an opaque handle it cannot
     * iterate. `ReflectedClass.enumEntryNames` carries the same information as data, which is
     * what an `enum.Enum` mirror is built from at import time; `valueOf` is exposed because it
     * returns one instance and is the natural lookup for a name Python did not generate from.
     *
     * An entry's own class body (`RED { override fun ... }`) needs nothing here: the override is
     * reached through the enum class's own `METHOD` entry.
     */
    private fun enumEntries(classDeclaration: KSClassDeclaration, qualifiedName: String): List<CallableEntryModel> {
        // Every one of these has a statically known Kotlin type -- an enum entry's STATIC_GETTER
        // and `valueOf` both hand back `$qualifiedName` itself, and `name`/`ordinal` are
        // `kotlin.Enum`'s own fixed shape -- so `returnTypeName` is filled here exactly as it is
        // at every other call site in this file, for the same reason: this is data the processor
        // has, not a guess. It currently has no effect on ownership for these specific entries --
        // `PythonProxySource.render` puts every `STATIC_GETTER`/`FUNCTION` an `ENUM` produces on
        // the module-attribute path (`renderModuleStatic`/`_pm_static_property`), which does not
        // consult `returnTypeName` at all (see that file's "What is still not rendered" table) --
        // but leaving it blank here would read as "KSP could not name this", which is not true.
        val entries = enumEntryNames(classDeclaration).map { entryName ->
            CallableEntryModel(
                name = "$qualifiedName.$entryName",
                arity = 0,
                paramTags = emptyList(),
                returnTag = Tag.OBJECT,
                kind = "STATIC_GETTER",
                lambdaBody = "{ $qualifiedName.$entryName }",
                returnTypeName = qualifiedName,
            )
        }
        return entries + listOf(
            CallableEntryModel(
                name = "$qualifiedName.name",
                arity = 0,
                paramTags = emptyList(),
                returnTag = Tag.STRING,
                kind = "GETTER",
                lambdaBody = "{ args -> (args[0] as $qualifiedName).name }",
                returnTypeName = "kotlin.String",
            ),
            CallableEntryModel(
                name = "$qualifiedName.ordinal",
                arity = 0,
                paramTags = emptyList(),
                returnTag = Tag.INT,
                kind = "GETTER",
                lambdaBody = "{ args -> ((args[0] as $qualifiedName).ordinal).toLong() }",
                returnTypeName = "kotlin.Int",
            ),
            CallableEntryModel(
                name = "$qualifiedName.valueOf",
                arity = 1,
                paramTags = listOf(Tag.STRING),
                returnTag = Tag.OBJECT,
                kind = "FUNCTION",
                lambdaBody = "{ args -> $qualifiedName.valueOf(args[0] as String) }",
                paramNames = listOf("value"),
                paramTypeNames = listOf("kotlin.String"),
                returnTypeName = qualifiedName,
            ),
        )
    }

    /**
     * A companion object's members, named and called through the *owner* rather than through
     * `Owner.Companion`: `docs/design/binding-policy.md` exposes them as static members of the class,
     * and Kotlin resolves `Owner.member` to the companion's member anyway, named companion or
     * not. The companion gets no [ClassModel] of its own -- there is nothing Python can do with
     * it that it cannot do through the owner.
     */
    private fun companionEntries(
        classDeclaration: KSClassDeclaration,
        ownerQualifiedName: String,
    ): List<CallableEntryModel> {
        val companion = classDeclaration.declarations.filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?.takeIf { it.getVisibility() == Visibility.PUBLIC && !BindingPolicy.hasPythonInternal(it) }
            ?: return emptyList()

        val entries = mutableListOf<CallableEntryModel>()
        companion.declarations.filterIsInstance<KSFunctionDeclaration>()
            .filter { BindingPolicy.isExposedMemberFunction(it) }
            .filter { exposableOrWarn(ownerQualifiedName, it) }
            .forEach { entries += staticFunctionEntry(ownerQualifiedName, ownerQualifiedName, it) }
        companion.declarations.filterIsInstance<KSPropertyDeclaration>()
            .filter { BindingPolicy.isExposedProperty(it) }
            .filter { exposableOrWarn(ownerQualifiedName, it) }
            .forEach { entries += staticPropertyEntries(ownerQualifiedName, ownerQualifiedName, it) }
        return entries
    }

    private fun constructorEntry(classQualifiedName: String, ctor: KSFunctionDeclaration): CallableEntryModel {
        val paramShapes = ctor.parameters.map { it.type.toShape() }
        val argExprs = paramShapes.mapIndexed { i, shape -> castExpression(shape, "args[$i]") }
        return CallableEntryModel(
            name = "$classQualifiedName.<init>",
            arity = paramShapes.size,
            paramTags = paramShapes.map(::tagFor),
            returnTag = Tag.OBJECT,
            kind = "CONSTRUCTOR",
            lambdaBody = "{ args -> $classQualifiedName(${argExprs.joinToString(", ")}) }",
            paramNames = paramNamesOf(ctor.parameters),
            paramTypeNames = paramShapes.map { it.rendered },
            // The class being built, not a `TypeShape` -- there is no return-type usage to derive
            // one from. `PythonProxySource.renderClass`'s own comment records that a CONSTRUCTOR
            // result is never wrapped by this gate anyway (`self._pm_handle` is set directly), so
            // this specific name never reaches `ownedTypeOf`; it is filled regardless for the same
            // reason every other site here is -- consistency for a `.pyi` reader, and because
            // leaving it `null` would read as "KSP could not name this", which is not true.
            returnTypeName = classQualifiedName,
            paramHasDefault = paramHasDefaultOf(ctor.parameters),
        )
    }

    private fun memberFunctionEntry(classQualifiedName: String, function: KSFunctionDeclaration): CallableEntryModel {
        val paramShapes = function.parameters.map { it.type.toShape() }
        val returnShape = function.returnType?.toShape() ?: TypeShape("kotlin.Unit", false)
        // args[0] is the receiver; declared parameters start at args[1].
        val argExprs = paramShapes.mapIndexed { i, shape -> castExpression(shape, "args[${i + 1}]") }
        val receiver = "(args[0] as $classQualifiedName)"
        val callExpr = "$receiver.${function.simpleName.asString()}(${argExprs.joinToString(", ")})"
        return CallableEntryModel(
            name = "$classQualifiedName.${function.simpleName.asString()}",
            arity = paramShapes.size,
            paramTags = paramShapes.map(::tagFor),
            returnTag = tagFor(returnShape),
            kind = "METHOD",
            lambdaBody = functionBody(function, wrapReturnExpression(returnShape, callExpr)),
            isSuspend = BindingPolicy.isSuspending(function),
            // The receiver is `args[0]` but is not one of `function.parameters` -- KSP never
            // counts it there for a METHOD, the same way `arity` does not -- so nothing here needs
            // a `<receiver>` slot the way an [python.multiplatform.reflection.ExposedCallable
            // .isExtension] entry would.
            paramNames = paramNamesOf(function.parameters),
            paramTypeNames = paramShapes.map { it.rendered },
            returnTypeName = returnShape.rendered,
            paramHasDefault = paramHasDefaultOf(function.parameters),
        )
    }

    /**
     * A function reached without an instance -- an `object`'s or a companion's. [nameOwner] is
     * what Python sees it under and [callPrefix] is what the generated source calls; they hold
     * the same value everywhere today and are separate parameters because the two roles are.
     */
    private fun staticFunctionEntry(
        nameOwner: String,
        callPrefix: String,
        function: KSFunctionDeclaration,
    ): CallableEntryModel {
        val paramShapes = function.parameters.map { it.type.toShape() }
        val returnShape = function.returnType?.toShape() ?: TypeShape("kotlin.Unit", false)
        val argExprs = paramShapes.mapIndexed { i, shape -> castExpression(shape, "args[$i]") }
        val callExpr = "$callPrefix.${function.simpleName.asString()}(${argExprs.joinToString(", ")})"
        return CallableEntryModel(
            name = "$nameOwner.${function.simpleName.asString()}",
            arity = paramShapes.size,
            paramTags = paramShapes.map(::tagFor),
            returnTag = tagFor(returnShape),
            kind = "FUNCTION",
            lambdaBody = functionBody(function, wrapReturnExpression(returnShape, callExpr)),
            isSuspend = BindingPolicy.isSuspending(function),
            paramNames = paramNamesOf(function.parameters),
            paramTypeNames = paramShapes.map { it.rendered },
            returnTypeName = returnShape.rendered,
            paramHasDefault = paramHasDefaultOf(function.parameters),
        )
    }

    private fun memberPropertyEntries(
        classQualifiedName: String,
        property: KSPropertyDeclaration,
    ): List<CallableEntryModel> {
        val propShape = property.type.toShape()
        val propTag = tagFor(propShape)
        val propName = property.simpleName.asString()
        val receiver = "(args[0] as $classQualifiedName)"
        val getter = CallableEntryModel(
            name = "$classQualifiedName.$propName",
            arity = 0,
            paramTags = emptyList(),
            returnTag = propTag,
            kind = "GETTER",
            lambdaBody = "{ args -> ${wrapReturnExpression(propShape, "$receiver.$propName")} }",
            returnTypeName = propShape.rendered,
        )
        if (!BindingPolicy.isExposedSetter(property)) return listOf(getter)
        val setter = CallableEntryModel(
            name = "$classQualifiedName.$propName=",
            arity = 1,
            paramTags = listOf(propTag),
            returnTag = Tag.UNIT,
            kind = "SETTER",
            lambdaBody = "{ args -> $receiver.$propName = ${castExpression(propShape, "args[1]")} }",
            // KSP synthesises a setter node even for a `var` with no written accessor body, and
            // its parameter's name is "value" in that case -- but a caller who wrote `set(newValue)
            // { ... }` gets to keep that name here rather than a hardcoded "value" that would be
            // wrong for exactly the property that bothered to say otherwise.
            paramNames = listOf(property.setter?.parameter?.name?.asString() ?: "value"),
            paramTypeNames = listOf(propShape.rendered),
        )
        return listOf(getter, setter)
    }

    /** [memberPropertyEntries] for a property with no instance to read it from: the value lives
     * at `callPrefix.name`, and `args[0]` is the new value rather than a receiver. */
    private fun staticPropertyEntries(
        nameOwner: String,
        callPrefix: String,
        property: KSPropertyDeclaration,
    ): List<CallableEntryModel> {
        val propShape = property.type.toShape()
        val propTag = tagFor(propShape)
        val propName = property.simpleName.asString()
        val getter = CallableEntryModel(
            name = "$nameOwner.$propName",
            arity = 0,
            paramTags = emptyList(),
            returnTag = propTag,
            kind = "STATIC_GETTER",
            lambdaBody = "{ ${wrapReturnExpression(propShape, "$callPrefix.$propName")} }",
            returnTypeName = propShape.rendered,
        )
        if (!BindingPolicy.isExposedSetter(property)) return listOf(getter)
        val setter = CallableEntryModel(
            name = "$nameOwner.$propName=",
            arity = 1,
            paramTags = listOf(propTag),
            returnTag = Tag.UNIT,
            kind = "STATIC_SETTER",
            lambdaBody = "{ args -> $callPrefix.$propName = ${castExpression(propShape, "args[0]")} }",
            paramNames = listOf(property.setter?.parameter?.name?.asString() ?: "value"),
            paramTypeNames = listOf(propShape.rendered),
        )
        return listOf(getter, setter)
    }

    private fun traverseBody(classQualifiedName: String, pyObjectFields: List<KSPropertyDeclaration>): String {
        val visits = pyObjectFields.joinToString("\n") { field ->
            val name = field.simpleName.asString()
            val receiver = "(obj as $classQualifiedName).$name"
            if (field.type.resolve().isMarkedNullable) {
                "        $receiver?.let { visit(it.pointer.toRawValue()) }"
            } else {
                "        visit($receiver.pointer.toRawValue())"
            }
        }
        return "{ obj, visit ->\n$visits\n    }"
    }
}
