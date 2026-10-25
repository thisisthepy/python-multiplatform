// Fixture, not production source: compiled into this module's own test classes and walked as
// bytecode by `CompanionHandleBindingTest`. A package of its own so no other test's exact list moves.
package fixture.artifactcompanion

/** The `Modifier` shape: the companion is itself a `Modifier`. */
interface Marker {
    companion object : Marker
}

/** A named companion that is not a `Marker`; the owner is also constructible. */
class Widget(val size: Int) {
    companion object Factory {
        fun make(): Widget = Widget(1)
    }
}

/** A companion of its own type with a constant, so the owner publishes more than the handle. */
class Holder(val size: Int) {
    companion object {
        val Default: Holder = Holder(0)
    }
}

/** A generic owner whose companion is a subtype of it. */
interface Box<T> {
    companion object : Box<Any?>
}

/** A class whose companion is not public. */
class Hidden {
    private companion object {
        val value: Int = 1
    }
}

/** A class with no companion. */
class Plain
