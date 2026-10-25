package python.multiplatform.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isConstructor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility

private const val PYTHON_INTERNAL_ANNOTATION = "python.multiplatform.reflection.PythonInternal"

/** `docs/design/binding-policy.md`: what the generator is allowed to expose, decided from the
 * declaration alone -- no classpath resolution needed beyond what KSP already gives a symbol. */
object BindingPolicy {

    fun isExcludedByPackage(qualifiedName: String, excludePackages: List<String>): Boolean =
        (ALWAYS_EXCLUDED_PACKAGE_PREFIXES + excludePackages).any {
            qualifiedName == it || qualifiedName.startsWith("$it.")
        }

    fun hasPythonInternal(annotated: KSAnnotated): Boolean =
        annotated.annotations.any { annotation ->
            val shortName = annotation.shortName.asString()
            // Cheap check first (`shortName`, no resolution); only resolve the annotation type
            // when the name plausibly matches, since resolving every annotation on every
            // declaration would be the expensive path `getSymbolsWithAnnotation` was rejected
            // for avoiding (docs/design/upcall.md §2.3, "Discovery key").
            shortName == "PythonInternal" &&
                annotation.annotationType.resolve().declaration.qualifiedName?.asString() == PYTHON_INTERNAL_ANNOTATION
        }

    private fun isPublic(declaration: KSDeclaration): Boolean = declaration.getVisibility() == Visibility.PUBLIC

    /** Top-level functions only; member functions go through [isExposedMemberFunction]. */
    fun isExposedTopLevelFunction(function: KSFunctionDeclaration, excludePackages: List<String>): Boolean {
        val qualifiedName = function.qualifiedName?.asString() ?: return false
        if (isExcludedByPackage(qualifiedName, excludePackages)) return false
        if (!isExposedFunctionShape(function)) return false
        val params = function.parameters
        val isEntryPoint = function.simpleName.asString() == "main" &&
            (params.isEmpty() || (params.size == 1 && params[0].type.toShape().qualifiedName == "kotlin.Array"))
        if (isEntryPoint) return false
        return true
    }

    /**
     * The class-like declarations that get a `ReflectedClass`: classes, interfaces, enum classes
     * and objects. What is deliberately *not* here:
     *
     * - **`ANNOTATION_CLASS`** -- reading an annotation off a declaration needs runtime
     *   reflection, which is the one thing this design cannot have (Kotlin/Native has none,
     *   GraalVM's closed world forbids it), and an annotation instance constructed from Python
     *   could not be attached to anything. There is nothing usable on the other end of the call.
     * - **`ENUM_ENTRY`** -- not a type of its own to Python, but one value of its enum; exposed
     *   as a `STATIC_GETTER` on the enum instead.
     * - **companion objects** -- folded into their owner's entries under the owner's name
     *   (`docs/design/binding-policy.md`: "companion 객체 자체를 따로 노출할 필요는 없다").
     * - **generic declarations** -- see [hasRenderableSignature].
     */
    fun isExposedClass(classDeclaration: KSClassDeclaration, excludePackages: List<String>): Boolean {
        val qualifiedName = classDeclaration.qualifiedName?.asString() ?: return false
        if (isExcludedByPackage(qualifiedName, excludePackages)) return false
        if (!isPublic(classDeclaration)) return false
        if (hasPythonInternal(classDeclaration)) return false
        if (Modifier.EXPECT in classDeclaration.modifiers) return false
        if (classDeclaration.isCompanionObject) return false
        if (!hasRenderableSignature(classDeclaration)) return false
        return classDeclaration.classKind in EXPOSED_CLASS_KINDS
    }

    /**
     * Whether Python may build an instance: only an ordinary, concrete, non-`inner` class.
     *
     * An abstract or sealed class cannot be constructed at all -- "Cannot create an instance of
     * an abstract class", observed as a compile failure of the *generated* fragment, because the
     * generator emitted a constructor entry for any class with a public primary constructor. An
     * `inner` class needs an outer instance the boundary has nowhere to put. Interfaces, objects
     * and enums have no Python-callable constructor by construction.
     */
    fun isConstructible(classDeclaration: KSClassDeclaration): Boolean {
        if (classDeclaration.classKind != ClassKind.CLASS) return false
        if (Modifier.ABSTRACT in classDeclaration.modifiers) return false
        if (Modifier.SEALED in classDeclaration.modifiers) return false
        if (Modifier.INNER in classDeclaration.modifiers) return false
        return true
    }

    /** Member function of an already-[isExposedClass] class. Constructors are handled
     * separately via [KSClassDeclaration.primaryConstructor]. */
    fun isExposedMemberFunction(function: KSFunctionDeclaration): Boolean {
        if (function.isConstructor()) return false
        return isExposedFunctionShape(function)
    }

    /**
     * `copy` and `componentN` on a `data class`.
     *
     * `docs/design/binding-policy.md` lists compiler-generated members as not exposed, and this is the
     * subset KSP actually reports -- observed, not assumed: a generated fragment for
     * `data class Point(val x: Long, val y: Long)` carried `Point.copy`, `Point.component1` and
     * `Point.component2`, but no `equals`, `hashCode` or `toString`. `componentN` has no meaning
     * on the Python side (the properties are already exposed by name) and `copy` loses the
     * default arguments that are its whole point, so both are noise in the table.
     */
    fun isCompilerGeneratedDataClassMember(owner: KSClassDeclaration, function: KSFunctionDeclaration): Boolean {
        if (Modifier.DATA !in owner.modifiers) return false
        val name = function.simpleName.asString()
        return name == "copy" || COMPONENT_N.matches(name)
    }

    private val COMPONENT_N = Regex("component\\d+")

    fun isExposedProperty(property: KSPropertyDeclaration): Boolean {
        if (!isPublic(property)) return false
        if (hasPythonInternal(property)) return false
        if (Modifier.EXPECT in property.modifiers) return false
        if (property.extensionReceiver != null) return false
        return true
    }

    /**
     * Whether an already-[isExposedProperty] property gets a `SETTER`/`STATIC_SETTER` entry too.
     *
     * `isMutable` alone is not the question, which is ROADMAP §13's second defect: a `var` with a
     * `private set` is mutable and its setter is not something generated code may name. The
     * generated fragment then failed to compile --
     *
     *     Cannot access 'privateSet': it is private in 'fixture.library.RestrictedSetters'.
     *     Cannot access 'topLevelPrivateSet': it is private in file.
     *
     * -- observed on `python-multiplatform-ksp/fixtures/library`'s `RestrictedSetters` before this check existed.
     *
     * `internal set` is excluded for a different reason, and the difference is worth stating
     * because it does *not* announce itself: `internal` is enforced per Kotlin module and the
     * fragment is generated into the same compilation as the sources it scans, so the assignment
     * compiles. It is still not exposable. What the table describes is a module's public API --
     * the aggregator that reads this fragment lives in another module, and a Python caller has no
     * notion of which Kotlin module it is "inside". Checking only for the shape that broke the
     * build would have left the quiet one behind.
     *
     * A `var` KSP reports with no setter node at all is an ordinary public one: there is no
     * accessor there to carry a visibility modifier.
     */
    fun isExposedSetter(property: KSPropertyDeclaration): Boolean {
        if (!property.isMutable) return false
        val setter = property.setter ?: return true
        val declared = setter.modifiers.intersect(VISIBILITY_MODIFIERS)
        return declared.isEmpty() || declared == setOf(Modifier.PUBLIC)
    }

    private val VISIBILITY_MODIFIERS =
        setOf(Modifier.PUBLIC, Modifier.PRIVATE, Modifier.PROTECTED, Modifier.INTERNAL)

    /**
     * Whether this declaration needs the asynchronous calling convention
     * (`docs/design/upcall.md`).
     *
     * `suspend` used to be a rejection here, and silently: a C callback slot has to hand back a
     * `PyObject *` before it returns and a suspension has nothing to hand back, so until there was
     * somewhere to put the answer there was nothing correct to generate. There is now --
     * `python.multiplatform.ffi.upcall.PendingCall` starts the coroutine inside the frame and the
     * boundary returns either the value (it never suspended) or an `asyncio.Future` (it did) -- so
     * the modifier selects a *body shape* rather than excluding the declaration.
     *
     * This reads the modifier on the **declaration**. A `suspend` function *type*
     * (`val h: suspend (Long) -> Long`) carries no such modifier and is not this question; it is
     * still exposed as an opaque `OBJECT` handle, which `GeneratedSuspendTest` pins.
     */
    fun isSuspending(function: KSFunctionDeclaration): Boolean = Modifier.SUSPEND in function.modifiers

    private fun isExposedFunctionShape(function: KSFunctionDeclaration): Boolean {
        if (!isPublic(function)) return false
        if (hasPythonInternal(function)) return false
        if (Modifier.EXPECT in function.modifiers) return false
        if (function.extensionReceiver != null) return false
        if (isComposable(function)) return false
        if (!hasRenderableSignature(function)) return false
        return true
    }

    /**
     * A `@Composable` in the consumer's **own source**, which KSP cannot bind and must not try to.
     *
     * `docs/design/ecosystem.md` §4 item 1 states the reason: a generated entry is a lambda over
     * `Array<Any?>`, and a `@Composable` may only be invoked from a `@Composable` context, so the
     * generated call does not compile. That is not hypothetical -- putting one hand-written
     * composable in a module carrying this processor produced exactly
     *
     *     e: Fragment_....kt:20:50 @Composable invocations can only happen from the context of
     *        a @Composable function
     *
     * and the module could not be built at all. Nothing had noticed because no fixture had ever put
     * a composable in a processed source set.
     *
     * **Deliberately not the answer the artefact walker gives.** `ArtifactScanner` *does* bind a
     * composable, by emitting its call site as bytecode (`ComposableThunks.kt`) with the
     * `$composer`/`$changed`/`$default` parameters exposed as ordinary slots. That route needs the
     * callee's compiled JVM signature, which for a declaration in the source set being compiled
     * right now does not exist yet -- so it is genuinely unavailable here rather than merely
     * unimplemented. Declining is the honest state, and it leaves the module compiling.
     *
     * Matched by simple name so that no dependency on the Compose runtime is introduced: a processor
     * that had to resolve `androidx.compose.runtime.Composable` would need Compose on the classpath
     * of every consumer, which is most of them do not have.
     */
    private fun isComposable(function: KSFunctionDeclaration): Boolean =
        function.annotations.any { it.shortName.asString() == "Composable" }

    /**
     * Generic declarations are not exposed, whatever their visibility.
     *
     * A generated entry is a lambda over `Array<Any?>`, so every parameter and every receiver
     * needs a cast to a type spelled out in source. A type parameter has no such spelling:
     * `args[0] as T` is an unresolved reference from the fragment's file, and a cast to the raw
     * `Box` is "One type argument expected". Both were observed as compile failures of generated
     * code, not as anything the processor itself could detect.
     *
     * This is wider than `docs/design/binding-policy.md` records -- that excluded only
     * `inline` + `reified`, the subset where the type is erased. Erasing to `Box<*>` instead was
     * rejected: the receiver would still not satisfy a member declared over `T`.
     */
    private fun hasRenderableSignature(declaration: KSDeclaration): Boolean = declaration.typeParameters.isEmpty()

    /**
     * The type-usage counterpart to [hasRenderableSignature]: every parameter and the return type
     * of [function] must be an [isExposableType], not just spelled without a bare type parameter.
     *
     * Kept as a separate predicate rather than folded into [isExposedFunctionShape] so
     * [FragmentScanner] can tell "not exposed because of shape" (private, `@PythonInternal`, ...)
     * apart from "not exposed because a type in the signature has no callable entry" and warn only
     * on the latter -- the former is ordinary and silent by design, the latter is the failure mode
     * `docs/design/upcall.md` §5.3 measured as a classifier that compiles and checkcasts but
     * has nothing behind it.
     */
    fun hasExposableTypes(function: KSFunctionDeclaration): Boolean =
        function.parameters.all { isExposableType(it.type) } &&
            (function.returnType?.let { isExposableType(it) } ?: true)

    /** [hasExposableTypes] for a property's own type. */
    fun hasExposableTypes(property: KSPropertyDeclaration): Boolean = isExposableType(property.type)

    private val EXPOSED_CLASS_KINDS =
        setOf(ClassKind.CLASS, ClassKind.INTERFACE, ClassKind.ENUM_CLASS, ClassKind.OBJECT)
}
