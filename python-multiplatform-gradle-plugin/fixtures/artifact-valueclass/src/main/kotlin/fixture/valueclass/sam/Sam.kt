// Walked by `:python-multiplatform-gradle-plugin-fixtures:artifact` (its `fixture.valueclass` include covers this
// package): functional-interface parameters issue #216 fills with a Python callable.
package fixture.valueclass.sam

/** A Kotlin `fun interface` that is handed two values. */
fun interface Listener {
    fun onEvent(name: String, count: Int)
}

/** The `CoroutineDispatcher.dispatch(context, block: Runnable)` shape, which runs the block at once. */
fun runRunnable(block: Runnable) = block.run()

/** Hands the listener a name and its length. */
fun notifyListener(listener: Listener, name: String) = listener.onEvent(name, name.length)

private var heard = ""

/** A listener Kotlin made, so the same slot is shown taking a Kotlin handle as well as a callable. */
fun loggingListener(): Listener = Listener { name, count -> heard = "$name:$count" }

/** What [loggingListener]'s listener heard last. */
fun lastHeard(): String = heard
