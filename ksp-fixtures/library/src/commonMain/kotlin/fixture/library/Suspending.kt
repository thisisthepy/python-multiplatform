package fixture.library

import kotlin.jvm.JvmInline

/**
 * Every shape a `suspend` function can take in a scanned module, so that what the generator does
 * with it is a *measured* fact rather than a reading of [python.multiplatform.ksp.BindingPolicy].
 *
 * `docs/design/upcall-async-design.md` explains why none of these can be in the table as they stand: a
 * CPython callback slot is a C frame that must hand back a `PyObject *` before it returns, and a
 * suspension has nothing to hand back. The fixtures exist so that the day an async convention
 * lands, the change in behaviour shows up as these tests failing rather than as silence.
 *
 * Each suspending declaration is paired with a non-suspending control in the same scope, so a
 * test asserting the suspending one is absent cannot pass by the whole scope having been dropped.
 */

// ------------------------------------------------------------------------------------ top level

/** Nothing about the body suspends; the modifier alone is the subject. */
suspend fun suspendingTopLevel(x: Long): Long = x * 2

/** The control for [suspendingTopLevel], in the same file and package. */
fun blockingTopLevel(x: Long): Long = x * 2

// -------------------------------------------------------------------------------------- members

class SuspendingService(val id: Long) {

    suspend fun fetch(key: String): String = "async:$key"

    /** The control: the class must stay exposed and constructible with a suspending member. */
    fun fetchBlocking(key: String): String = "sync:$key"

    /**
     * A `val` whose *type* is suspending, as opposed to a suspending accessor -- the member-scope
     * sibling of top-level [suspendingHandler]. `BindingPolicy.isSuspending` only ever sees this
     * as a `GETTER`-shaped declaration with no `suspend` modifier of its own, so the property-type
     * check ([python.multiplatform.ksp.BindingPolicy.hasExposableTypes]) is what has to catch it.
     */
    val handler: suspend (Long) -> Long = { it + 1 }

    /** The member-function-parameter sibling of top-level [runsHandler]. */
    fun runsHandlerMember(handler: suspend (Long) -> Long): String =
        "member:" + handler.toString().substringBefore('@')

    /** The member-function-return sibling of top-level [makeHandler]. */
    fun makesHandlerMember(): suspend (Long) -> Long = { it + 1 }

    companion object {
        suspend fun create(id: Long): SuspendingService = SuspendingService(id)

        /** The control for [create]. */
        fun createBlocking(id: Long): SuspendingService = SuspendingService(id)

        /** The companion-scope sibling of top-level [suspendingHandler]: folded onto the owner's
         * static surface by [python.multiplatform.ksp.FragmentScanner.companionEntries], a
         * separate code path from the member-property one [handler] exercises. */
        val companionHandler: suspend (Long) -> Long = { it + 1 }
    }
}

interface SuspendingSource {
    suspend fun load(): String

    /** The control: an interface with a suspending member is still exposed for the rest. */
    fun describe(): String = "source"
}

object SuspendingRegistry {
    suspend fun ping(): String = "async-pong"

    /** The control. */
    fun pingBlocking(): String = "pong"
}

// ------------------------------------------------------- suspending *types*, not suspending funs
//
// None of the declarations below carry `Modifier.SUSPEND` -- `BindingPolicy.isSuspending` reads
// that modifier on a *declaration*, and a `suspend` function *type* used as a property, parameter
// or return type carries no such modifier. What makes each one unexposable is
// `KSType.isSuspendFunctionType` on the *type*, which is why the check lives beside
// `hasRenderableSignature` in `BindingPolicy` as its own predicate
// (`BindingPolicy.hasExposableTypes`) rather than folded into the declaration-shape checks.
//
// `docs/design/upcall-async-design.md` §2.1 measured the base case: the generated cast
// (`args[0] as kotlin.coroutines.SuspendFunction1<...>`) compiles and the runtime checkcast
// passes, because that classifier is real to the Kotlin compiler even though nothing in this
// compilation ever declares it in source -- there is no `invoke` entry for Python to call through,
// so what would cross is a handle Python can hold and hand back and nothing else. Everything from
// [handlerList] onward is a variation on where that same unusable classifier can hide: a generic
// type argument, a nullable wrapper, and a `typealias`.

/**
 * A top-level property whose type is a suspending function type.
 *
 * This is a different question from a `suspend fun`: [python.multiplatform.ksp.BindingPolicy]
 * reads the *modifier* on a declaration, and there is no modifier here -- the suspension is in
 * the type. Whether the generated cast for it compiles is the thing being pinned.
 */
val suspendingHandler: suspend (Long) -> Long = { it + 1 }

/** The same question on the parameter side. */
fun runsHandler(handler: suspend (Long) -> Long): String = handler.toString().substringBefore('@')

/** And on the return side. */
fun makeHandler(): suspend (Long) -> Long = suspendingHandler

/**
 * A `List<T>` whose element type is a suspending function type.
 *
 * The outer type ([kotlin.collections.List]) is an ordinary, renderable classifier -- the boundary
 * already has no collection marshalling for it (a separate, older limitation), so on its own a
 * `List` property would just be an inert `OBJECT` handle, the same as any other unmarshalled
 * class. What makes this fixture about *this* task rather than that one is the element type: the
 * generated cast for a *parameter* of this shape ([runsHandlerList]) nests the unusable classifier
 * one level down (`kotlin.collections.List<kotlin.coroutines.SuspendFunction0<kotlin.Unit>>`), so
 * `isExposableType` has to recurse into type arguments rather than only look at the outermost one.
 */
val handlerList: List<suspend () -> Unit> = emptyList()

/** The parameter-position sibling of [handlerList], where the nested cast is actually written. */
fun runsHandlerList(handlers: List<suspend () -> Unit>): Int = handlers.size

/** A nullable suspending function type: [isExposableType] must not be fooled by the `?`. */
val nullableHandler: (suspend (Long) -> Long)? = null

/**
 * `typealias` for a suspending function type.
 *
 * Measured, not assumed to behave like [suspendingHandler]: `KSType.isSuspendFunctionType`
 * answers `false` for the alias-typed `KSType` itself (`KSType.declaration` is the `KSTypeAlias`,
 * and the check does not look through it on its own). Without expanding one level of alias in
 * `isExposableType`, [runsAliasedHandler] would generate a cast to `fixture.library.LongHandler`
 * -- a real, source-declared classifier this time, since the `typealias` itself is a declaration
 * in this compilation -- and pass every check while remaining exactly as uncallable underneath.
 */
typealias LongHandler = suspend (Long) -> Long

/** The property-position sibling of [runsAliasedHandler]. */
val aliasedHandler: LongHandler = { it + 1 }

/** The parameter-position case a `typealias` has to be expanded to catch. */
fun runsAliasedHandler(handler: LongHandler): String = handler.toString().substringBefore('@')

/**
 * A `value class` whose single property is a suspending function type.
 *
 * The class itself is not the problem -- [ValueHandler] is an ordinary, constructible,
 * source-declared classifier, and [runsValueHandler] casting a parameter to it is exactly as fine
 * as casting to any other exposed class. What is unexposable is *inside* it: the primary
 * constructor's parameter type and the property's own type are both `suspend (Long) -> Long`, so
 * [ValueHandler.<init>][ValueHandler] and [ValueHandler.f] must each be individually excluded
 * while the class itself stays exposed with whatever surface remains (here, nothing -- both of
 * its two members are this shape, so the generated `ReflectedClass` for it carries an empty
 * `memberNames`). This is also the fixture that exercises the constructor code path specifically:
 * `FragmentScanner`'s primary-constructor selection does not route through
 * `BindingPolicy.isExposedMemberFunction`/`isExposedTopLevelFunction` the way every other function
 * entry does, so it needed its own, separate wiring to the same [isExposableType] check.
 */
@JvmInline
value class ValueHandler(val f: suspend (Long) -> Long)

/** The control: an *ordinary* (non-suspending) function type stays exposed as an `OBJECT`
 * handle exactly as it did before this task -- only `suspend` ones are unrenderable. */
val plainHandler: (Long) -> Long = { it + 1 }

/** The control for [ValueHandler]: passing an already-exposed class as a parameter is unaffected
 * by anything unexposable living inside that class. */
fun runsValueHandler(v: ValueHandler): String = v.toString()
