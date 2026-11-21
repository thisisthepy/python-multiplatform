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

/** Generic `fun interface` whose single abstract method is `suspend` (SPEC U-5c, like `FlowCollector<in T>`). */
fun interface AsyncCollector<in T> {
    suspend fun emit(value: T)
}

fun acceptCollector(collector: AsyncCollector<Any?>) = collector.hashCode()

/** A generic, nameable public class: the walker may write it into generated source (a Kotlin collection it may not). */
class Batch<T>(val size: Int)

/** The interface's type parameter nested in the method's parameter type: substituted by id at any depth. */
fun interface BatchCollector<T> {
    suspend fun take(batch: Batch<T>)
}

fun acceptBatches(collector: BatchCollector<String>) = collector.hashCode()

/**
 * #164 item 4: a parameter whose type is a `java.*` class, spelled through a Kotlin typealias the way
 * `kotlinx.coroutines.Job.cancel(cause: CancellationException? = null)` spells it. The JDK class is in
 * no walked jar; it must still be nameable.
 */
typealias JdkCancellation = java.util.concurrent.CancellationException

fun cancelWith(cause: JdkCancellation? = null): String = cause?.message ?: "none"

/** A `javax.*` class stays outside what the walker names (only `java/` is read from the JDK). */
fun takeJavaxName(name: javax.naming.Name): Int = name.size()
