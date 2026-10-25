// Fixture, not production source: compiled into this module's own test classes and walked as
// bytecode by `MemberFunctionBindingTest` (issue #215), the way `objectfixture` is walked by
// `ObjectMemberBindingTest`. A package of its own, so that tests which pin another fixture
// package's output as an exact list are not disturbed.
package fixture.artifactmember

/** A class with a constructor, overloaded members, a default argument and the members that must stay out. */
class Counter(var count: Int) {
    fun add(step: Int): Int {
        count += step
        return count
    }

    fun add(step: Int, label: String): String = label + add(step)

    fun reset() {
        count = 0
    }

    fun scaled(by: Int = 2): Int = count * by

    suspend fun later(): Int = count

    override fun toString(): String = "Counter($count)"
}

/** The `kotlinx.coroutines.Job` shape: an interface whose members are all a caller has. */
interface Handle {
    val active: Boolean

    fun stop()

    fun follows(other: Handle): Boolean
}

fun newHandle(): Handle = object : Handle {
    override var active: Boolean = true

    override fun stop() {
        active = false
    }

    override fun follows(other: Handle): Boolean = other !== this
}

/** A property and a function of one name: Python has one attribute for both, and the property keeps it. */
class Sized {
    val size: Int = 1

    fun size(extra: Int): Int = size + extra
}

/** Generic: its members are not bound yet. */
class Box<T>(val item: T) {
    fun describe(): String = "box"
}

/** An `object`'s members are #53's and stay bound as statics under the object's name. */
object Registry {
    fun lookup(key: String): Int = key.length
}
