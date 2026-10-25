// Fixture, not production source: compiled into this module's own test classes and walked as
// bytecode by `SamParameterBindingTest` (issue #216). A package of its own, so that tests which pin
// another fixture package's output as an exact list are not disturbed.
package fixture.artifactsam

/** A Kotlin `fun interface` with two parameters. */
fun interface Listener {
    fun onEvent(name: String, count: Int)
}

/** Its method returns a value, which a Python callable cannot stand in for yet (B-5). */
fun interface Producer {
    fun make(): Int
}

/** Generic: not yet. */
fun interface Sink<T> {
    fun take(item: T)
}

/** Two abstract methods: an ordinary interface, which crosses as a handle as before. */
interface NotFunctional {
    fun first()

    fun second()
}

fun runRunnable(block: Runnable) = block.run()

fun maybeRun(block: Runnable?) {
    block?.run()
}

fun notifyListener(listener: Listener, name: String) = listener.onEvent(name, name.length)

fun produce(producer: Producer): Int = producer.make()

fun drain(sink: Sink<String>) = sink.take("item")

fun keep(handle: NotFunctional) = handle.first()
