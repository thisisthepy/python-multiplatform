package python.multiplatform.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Variance

private const val PY_OBJECT_QUALIFIED_NAME = "python.multiplatform.ffi.PyObject"

/** Reduces a resolved KSP type to the plain data [castExpression]/[wrapReturnExpression] work on. */
fun KSType.toShape(): TypeShape {
    val qualifiedName = declaration.qualifiedName?.asString() ?: "kotlin.Any"
    return TypeShape(qualifiedName, isMarkedNullable, rendered = renderWithArguments(qualifiedName))
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

fun KSTypeReference.toShape(): TypeShape = resolve().toShape()

/**
 * Whether [type] can be named in generated source as a classifier Python can actually reach a
 * call or member surface through -- not merely one the Kotlin compiler happens to accept.
 *
 * A `suspend` function type (`suspend (Long) -> Long`) is a legitimate `KSType`
 * ([KSType.isSuspendFunctionType] is true) but its declaration
 * (`kotlin.coroutines.SuspendFunctionN`) is compiler-synthesized, not something this
 * compilation's KSP scan ever visits as source. It gets no `ReflectedClass`, so `invoke` has no
 * table entry -- the generated cast (`args[0] as kotlin.coroutines.SuspendFunction1<...>`)
 * compiles and the runtime checkcast passes regardless (`docs/design/upcall-async-design.md` §2.1,
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
