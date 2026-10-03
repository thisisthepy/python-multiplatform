// Fixture, not production source: compiled into this module's own test classes and walked as
// bytecode by `ObjectMemberBindingTest`, the way `defaultsfixture` is walked by `DefaultOmissionTest`.
//
// A package of its own, so that the tests which pin another fixture package's output as an exact
// list are not disturbed.
package fixture.artifactobject

class Gap(val size: Int)

/** The `Arrangement` shape: a constant, an overloaded function, and a function taking the object's own
 * kind of value -- all *instance* methods of a singleton, which JVM bytecode cannot call statically. */
object Spacing {
    val Tight: Gap = Gap(1)

    fun spacedBy(count: Int): Gap = Gap(count)

    fun spacedBy(count: Int, label: String): Gap = Gap(count + label.length)

    fun describe(gap: Gap): String = "gap " + gap.size

    override fun toString(): String = "Spacing"
}

/** `@JvmStatic` members are already static; they must not be bound twice. */
object StaticSpacing {
    @JvmStatic
    fun twice(count: Int): Int = count * 2
}

/** Not an object: an ordinary class's instance methods need an instance Python cannot make here. */
class Plain {
    fun instanceMethod(count: Int): Int = count
}

/** JVM-public, Kotlin-internal: generated source outside this module cannot name it. */
internal object HiddenSpacing {
    fun hiddenFunction(count: Int): Int = count
}
