// Fixture, not production source: compiled with this module's tests so `PropertyBindingTest` can walk
// it as bytecode plus `@Metadata`, the same way `ValueClassFixtures.kt` is walked (issue #38).
@file:Suppress("unused", "UNCHECKED_CAST", "UNUSED_PARAMETER")

package fixture.artifactproperty

/** An ordinary class: a `val`, a `var`, a nullable `var`, and two properties nobody outside may name. */
class Counter(var count: Int, val name: String) {
    var note: String? = null
    private var hidden: Int = 0
    internal val internalProp: Int = 1

    /** A `var` whose setter is not public: the getter is bound, the setter is not. */
    var guarded: Int = 0
        private set
}

/** `MutableState<T>`'s shape: a generic `var` of the class's own unbounded type parameter. */
class Box<T>(var content: T) {
    val size: Int get() = 1
}

/** A bounded class type parameter: `Owner<Any?>` is not within bounds, so a property mentioning `T`
 * is declined, and no setter is bound for anything (it would write through `Owner<Any?>`). */
class Bounded<T : Number>(var item: T) {
    var plain: Int = 0
}

/** `State<T>`'s shape: an interface with an abstract `val` of its unbounded type parameter. */
interface Holder<out T> {
    val held: T
}

/** An `object`'s properties are `STATIC_GETTER`s already, never `GETTER`s. */
object Registry {
    val size: Int = 3
}

/** A value class the boundary opens: it reaches Python as its raw primitive, so no proxy of it exists. */
@JvmInline
value class Grams(val amount: Double)

// ------------------------------------------------------------------------------------- generics

/** Unbounded: bound, with `T` read as `kotlin.Any?` and written out at the call. */
fun <T> identity(value: T): T = value

/** Nothing to infer `T` from: only an explicit type argument makes the generated call compile. */
fun <T> emptyBox(): Box<T> = Box(null as T)

/** Bounded: `Any?` is not within `Comparable<T>`, so this stays declined. */
fun <T : Comparable<T>> largest(a: T, b: T): T = if (a > b) a else b

/** Reified: the type argument is read at run time, so substituting it would change the body. */
inline fun <reified T> typeNameOf(): String = T::class.simpleName ?: "?"

// -------------------------------------------------------------------------- extension properties

/** `Icons.Filled.Add`'s shape: a top-level extension property on an object-handle receiver. */
val Counter.doubled: Int get() = count * 2

/** A receiver that crosses as a primitive: there is no proxy of an `Int` to read it on. */
val Int.squared: Int get() = this * this

/** An extension `var`: its getter is bound, its setter is not (getters only, as decided). */
var Counter.mirrored: Int
    get() = count
    set(value) {
        count = value
    }

/** Generic: property syntax has no place to write a type argument. */
val <T> Box<T>.contentOrNull: T? get() = content

// ------------------------------------------------------------- a receiver the classpath cannot see

/**
 * `androidx.compose.ui.platform.DefaultArchitectureComponentsOwner`'s shape: a public class with a
 * supertype (`org.objectweb.asm.Opcodes`, standing in for lifecycle's `ViewModelStoreOwner`) that the
 * walk's classpath -- this fixture directory alone -- does not carry. Generated Kotlin reading
 * `level` off it would not compile ("Cannot access ... which is a supertype of ..."), so its
 * properties are declined; given a classpath that carries the supertype, they bind.
 */
class Detached : org.objectweb.asm.Opcodes {
    val level: Int = 1
}

/** The same receiver, read through an extension property. */
val Detached.reach: Int get() = level

/** The same receiver, called through an extension function (#66). */
fun Detached.probe(): Int = level
