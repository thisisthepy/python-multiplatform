package python.multiplatform.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Variance

private const val PY_OBJECT_QUALIFIED_NAME = "python.multiplatform.ffi.PyObject"

/**
 * Reduces a resolved KSP type to the plain data [castExpression]/[wrapReturnExpression] work on.
 *
 * When [allowTopLevelSuspendFunction] is `true` (parameter positions of functions/constructors,
 * SPEC U-5a, #164 item 7), a `suspend` function type is rendered as a `suspend (...) -> ...` cast
 * target and carries `kotlin.coroutines.SuspendFunctionN(Arg1,...)->Ret` as its slot metadata name.
 */
fun KSType.toShape(allowTopLevelSuspendFunction: Boolean = false): TypeShape {
    val expanded = expandTypeAlias(this)
    if (allowTopLevelSuspendFunction && expanded.isSuspendFunctionType) {
        val arity = expanded.arguments.size - 1
        val argTypes = expanded.arguments.dropLast(1).mapNotNull { it.type?.resolve() }
        val retType = expanded.arguments.lastOrNull()?.type?.resolve()
        if (arity in 0..MAX_SUSPEND_FUNCTION_ARITY && argTypes.size == arity && retType != null) {
            val argNames = argTypes.map { expandTypeAlias(it).declaration.qualifiedName?.asString() ?: "kotlin.Any" }
            val retName = expandTypeAlias(retType).declaration.qualifiedName?.asString() ?: "kotlin.Any"
            val slotName = "kotlin.coroutines.SuspendFunction$arity(" +
                argNames.joinToString(",") + ")->" + retName
            val renderedArgs = argTypes.joinToString(", ") { t ->
                val inner = t.declaration.qualifiedName?.asString() ?: "kotlin.Any"
                t.renderWithArguments(inner) + if (t.isMarkedNullable) "?" else ""
            }
            val retInner = retType.declaration.qualifiedName?.asString() ?: "kotlin.Any"
            val renderedRet = retType.renderWithArguments(retInner) + if (retType.isMarkedNullable) "?" else ""
            val castType = "(suspend ($renderedArgs) -> $renderedRet)"
            return TypeShape(
                qualifiedName = castType,
                nullable = isMarkedNullable || expanded.isMarkedNullable,
                rendered = castType,
                slotTypeName = slotName,
            )
        }
    }
    val qualifiedName = declaration.qualifiedName?.asString() ?: "kotlin.Any"
    return TypeShape(qualifiedName, isMarkedNullable, rendered = renderWithArguments(qualifiedName))
}

private const val MAX_SUSPEND_FUNCTION_ARITY = 5

private tailrec fun expandTypeAlias(type: KSType): KSType {
    val alias = type.declaration as? KSTypeAlias ?: return type
    return expandTypeAlias(alias.type.resolve())
}

/**
 * `pkg.Type` for a plain type, `pkg.Type<pkg.Arg, *>` for a generic one -- what a cast in
 * generated source needs, since `x as kotlin.collections.List` does not compile.
 *
 * Use-site variance (`Array<out String>`) is deliberately dropped: the cast is unchecked either
 * way, and an invariant cast target still satisfies a projected parameter. A star projection
 * stays a star, because there is nothing else to write for it. A type argument that is itself a
 * type *parameter* (`T`) has no qualified name and would render as an unresolved reference;
 * [BindingPolicy] rejects every declaration that can produce one before this is reached, and the
 * fallbacks to `*` here are the belt to that pair of braces.
 */
private fun KSType.renderWithArguments(qualifiedName: String): String {
    if (arguments.isEmpty()) return qualifiedName
    val renderedArguments = arguments.joinToString(", ") { argument ->
        if (argument.variance == Variance.STAR) return@joinToString "*"
        val argumentType = argument.type?.resolve() ?: return@joinToString "*"
        if (argumentType.declaration !is KSClassDeclaration) return@joinToString "*"
        val inner = argumentType.declaration.qualifiedName?.asString() ?: return@joinToString "*"
        argumentType.renderWithArguments(inner) + if (argumentType.isMarkedNullable) "?" else ""
    }
    return "$qualifiedName<$renderedArguments>"
}

fun KSTypeReference.toShape(allowTopLevelSuspendFunction: Boolean = false): TypeShape =
    resolve().toShape(allowTopLevelSuspendFunction)

/**
 * Whether [type] can be named in generated source as a classifier Python can actually reach a
 * call or member surface through -- not merely one the Kotlin compiler happens to accept.
 *
 * A `suspend` function type (`suspend (Long) -> Long`) is a legitimate `KSType`
 * ([KSType.isSuspendFunctionType] is true) but its declaration
 * (`kotlin.coroutines.SuspendFunctionN`) is compiler-synthesized, not something this
 * compilation's KSP scan ever visits as source. It gets no `ReflectedClass`, so `invoke` has no
 * table entry -- the generated cast (`args[0] as kotlin.coroutines.SuspendFunction1<...>`)
 * compiles and the runtime checkcast passes regardless (`docs/design/upcall.md` §5.3,
 * `GeneratedSuspendTest`), so nothing downstream catches this. What would cross is a handle
 * Python can hold and hand back and nothing else.
 *
 * Recurses into type arguments so `List<suspend () -> Unit>` is caught the same way a bare
 * `suspend (Long) -> Long` is: the generated cast nests the same unusable classifier one level
 * down (`kotlin.collections.List<kotlin.coroutines.SuspendFunction0<kotlin.Unit>>`), and it
 * compiles the same way -- observed, not assumed.
 *
 * A `typealias` for a suspend function type (`typealias LongHandler = suspend (Long) -> Long`)
 * needs its own step: measured, not assumed like the case above -- [KSType.isSuspendFunctionType]
 * answers `false` for the alias-typed `KSType` itself (its [KSType.declaration] is the
 * [KSTypeAlias], and the check does not look through it), so without expanding the alias here
 * `runsHandler(handler: LongHandler)` would cast to `fixture.library.LongHandler`, a real
 * source-level classifier, and pass every check while still being uncallable underneath. Expanding
 * one level of alias (not substituting a generic typealias's own type parameters, which this
 * codebase has none of) is enough to make [KSType.isSuspendFunctionType] answer for the real
 * shape.
 */
fun isExposableType(type: KSType): Boolean {
    val aliasDeclaration = type.declaration as? KSTypeAlias
    if (aliasDeclaration != null) return isExposableType(aliasDeclaration.type)
    if (type.isSuspendFunctionType) return false
    return type.arguments.all { argument ->
        val argumentType = argument.type?.resolve() ?: return@all true
        isExposableType(argumentType)
    }
}

/**
 * [isExposableType] for a **parameter** position (SPEC U-5a; #164 item 7): allows a top-level
 * `suspend` function type of arity `0..5` (including nullable forms and `typealias`es thereof)
 * whose own argument and return types are exposable. Return types, properties, and `suspend`
 * function types nested inside generic type arguments remain rejected by [isExposableType].
 */
fun isExposableParameterType(type: KSType): Boolean {
    val expanded = expandTypeAlias(type)
    if (expanded.isSuspendFunctionType) {
        // Extension suspend function types (`suspend R.() -> T`) are excluded for now (SPEC U-5a).
        if (expanded.annotations.any { it.shortName.asString() == "ExtensionFunctionType" }) return false
        val arity = expanded.arguments.size - 1
        if (arity !in 0..MAX_SUSPEND_FUNCTION_ARITY) return false
        return expanded.arguments.all { argument ->
            val argumentType = argument.type?.resolve() ?: return@all false
            isExposableType(argumentType)
        }
    }
    return isExposableType(expanded)
}

/** [isExposableParameterType] from a parameter's [KSTypeReference]. */
fun isExposableParameterType(typeRef: KSTypeReference): Boolean = isExposableParameterType(typeRef.resolve())

/** [isExposableType] from the [KSTypeReference] a parameter, return type or property carries. */
fun isExposableType(typeRef: KSTypeReference): Boolean = isExposableType(typeRef.resolve())

/**
 * Whether [type] is [python.multiplatform.ffi.PyObject] or one of its subclasses (`PyInt`,
 * `PyList`, ... every wrapper the object model exposes). A field of this type is a Python
 * reference the Kotlin object holds, and `tp_traverse` must see it -- see
 * `docs/design/object-lifetime.md`'s "Cycle collection is part of the table's job".
 */
fun isPyObjectType(type: KSType): Boolean {
    val declaration = type.declaration
    if (declaration.qualifiedName?.asString() == PY_OBJECT_QUALIFIED_NAME) return true
    val classDeclaration = declaration as? KSClassDeclaration ?: return false
    return classDeclaration.getAllSuperTypes().any {
        it.declaration.qualifiedName?.asString() == PY_OBJECT_QUALIFIED_NAME
    }
}
