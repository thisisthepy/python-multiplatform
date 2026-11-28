// Fixture, not production source: compiled into this module's own test classes and walked as
// bytecode by `RestrictsSuspensionBindingTest`. A package of its own so that tests which pin another
// fixture package's output are not disturbed.
package fixture.artifactrestricted

import kotlin.coroutines.RestrictsSuspension

/** The `AwaitPointerEventScope` shape: its suspend members are callable only inside its own scope. */
@RestrictsSuspension
interface Scope {
    suspend fun await(): Int

    fun plain(): Int
}

/** A public class that inherits the restriction from its supertype. */
class ScopeImpl : Scope {
    override suspend fun await(): Int = 1

    override fun plain(): Int = 2
}

/** An unrestricted class: its suspend member stays bound. */
class Free {
    suspend fun later(): Int = 3
}
