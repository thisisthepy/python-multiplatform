// Walked by `:python-multiplatform-gradle-plugin-fixtures:artifact` (its `fixture.valueclass` include covers this
// package): the member functions issue #215 binds, on a class and on an interface.
package fixture.valueclass.member

/** A class with a constructor, overloaded members and a member that returns another handle. */
class Tally(var total: Int) {
    fun add(step: Int): Int {
        total += step
        return total
    }

    fun add(step: Int, label: String): String = label + add(step)

    fun reset() {
        total = 0
    }

    fun twin(): Tally = Tally(total)
}

/** The `kotlinx.coroutines.Job` shape: only its members say anything about it. */
interface Switch {
    val on: Boolean

    fun turnOff()
}

fun newSwitch(): Switch = object : Switch {
    override var on: Boolean = true

    override fun turnOff() {
        on = false
    }
}

/** Kotlin's factory idiom (`kotlinx.coroutines.Job` and `fun Job()`): an interface and a function of one name (issue #217). */
interface Gauge {
    val level: Int

    fun bump()
}

fun Gauge(): Gauge = object : Gauge {
    override var level: Int = 0

    override fun bump() {
        level += 1
    }
}
