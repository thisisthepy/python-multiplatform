package fixture.library

import python.multiplatform.reflection.PythonInternal

/**
 * The declaration kinds ROADMAP §7 recorded as not exposed -- companion members, interfaces,
 * enums, annotation classes -- plus the shapes that made the *generated* file fail to compile
 * before the generator learned about them (generics, abstract classes).
 *
 * Everything here exists to be asserted against in
 * `ksp-fixtures/app/src/desktopTest/.../GeneratedDeclarationKindsTest.kt`, against the table KSP
 * actually generated rather than a hand-written fragment.
 */

// ------------------------------------------------------------------ top level, beyond functions

val libraryVersion: String = "1.0"

var mutableCounter: Long = 0

/** A parameter whose type has type arguments. `args[0] as kotlin.collections.List` is not valid
 * Kotlin, so this compiles only if the generator renders the arguments too. */
fun listSize(items: List<String>): Long = items.size.toLong()

/** Not exposable: `args[0] as T` has no type to name in the generated fragment. */
fun <T> identity(value: T): T = value

// ------------------------------------------------------------------------------ companion, object

class WithCompanion(val id: Long) {
    companion object {
        const val TAG: String = "with-companion"

        var created: Long = 0

        fun create(id: Long): WithCompanion {
            created += 1
            return WithCompanion(id)
        }
    }
}

/** A Kotlin singleton: one instance, so every member is reached without a receiver. */
object Registry {
    val label: String = "registry"

    var size: Long = 0

    fun ping(): String = "pong"
}

// ---------------------------------------------------------------------------------- interfaces

interface Greeter {
    val salutation: String

    fun greet(name: String): String = "$salutation, $name!"

    companion object {
        fun polite(): Greeter = PoliteGreeter("Good day")
    }
}

/** Implements [Greeter] without redeclaring `greet`: the only way Python can call it on this
 * instance is through the interface's own entry. */
class PoliteGreeter(override val salutation: String) : Greeter

// --------------------------------------------------------------------------------------- enums

enum class Color {
    RED,
    GREEN,
    BLUE,
}

enum class Level(val weight: Long) {
    LOW(1),
    HIGH(10),
    ;

    fun describe(): String = "$name/$weight"
}

@PythonInternal
enum class HiddenEnum {
    A,
}

// ------------------------------------------------------------- annotation classes: not exposed

annotation class Marker(val value: String)

// ------------------------------------------------------- nesting, abstraction, generic classes

class Outer {
    enum class State {
        ON,
        OFF,
    }

    object Nested {
        fun hello(): String = "nested"
    }
}

abstract class AbstractBase(val tag: String) {
    fun describe(): String = "base:$tag"
}

class ConcreteChild : AbstractBase("child")

/** Not exposable: neither `as Box` nor `as Box<*>` gives the generated code a receiver its
 * members type-check against. */
class Box<T>(val value: T)

/** Observation fixture for what KSP reports for a `data class`'s compiler-generated members. */
data class Point(val x: Long, val y: Long)

// -------------------------------------------------------- a `var` whose setter is not public API

/**
 * ROADMAP §13's second defect: `FragmentScanner` decided whether to emit a setter entry from
 * `property.isMutable` alone, and `isMutable` is `true` for all four properties below. Only
 * [openSet] has a setter the generated fragment may use.
 *
 * The three restricted ones fail differently, which is why one of them would not have been
 * enough:
 *
 * - `private set` -- the generated assignment does not compile at all
 *   ("Cannot assign to 'privateSet': the setter is private in ...").
 * - `protected set` -- the same, from outside the class.
 * - `internal set` -- **compiles**, because the fragment is generated into this module's own
 *   compilation and `internal` is enforced per Kotlin module. It is still wrong: what the table
 *   describes is the public API, and the same shape in a module that only *consumed* this one
 *   would not compile. Fixing only the loud failure would have left this one in the table.
 *
 * `open` rather than `class` so that `protected` has a meaning here.
 */
open class RestrictedSetters {
    var privateSet: Long = 1
        private set

    var protectedSet: Long = 2
        protected set

    var internalSet: Long = 3
        internal set

    /** The control: an ordinary `var`, whose setter entry must survive. */
    var openSet: Long = 4

    /** Lets a test move the read-only values without there being a setter entry. */
    fun bumpAll() {
        privateSet += 1
        protectedSet += 1
        internalSet += 1
    }
}

/** [RestrictedSetters] for the `STATIC_SETTER` path: a top-level `var` goes through
 * `FragmentScanner.staticPropertyEntries`, which carried the same `isMutable`-only branch. */
var topLevelPrivateSet: Long = 7
    private set

/** The control for [topLevelPrivateSet]. */
var topLevelOpenSet: Long = 8

/** Moves [topLevelPrivateSet] without exposing a setter. */
fun bumpTopLevelPrivateSet() {
    topLevelPrivateSet += 1
}

/** The `object` form of the same shape; `Registry.size` above is the mutable control. */
object RestrictedRegistry {
    var counted: Long = 0
        internal set

    fun count() {
        counted += 1
    }
}
