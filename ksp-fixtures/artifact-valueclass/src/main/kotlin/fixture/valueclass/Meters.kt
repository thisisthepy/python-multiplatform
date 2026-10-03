// Never compiled by `:ksp-fixtures:artifact`, which only ever sees this module's resolved jar --
// see the `build.gradle.kts` doc comment on why this module exists at all.
package fixture.valueclass

/** A public `@JvmInline value class`, the shape `kotlin.time.Duration` has except for its
 * constructor's visibility. */
@JvmInline
value class Meters(val value: Double)

/** Both parameters and the return are the value class: proves `ArtifactScanner` unwraps a
 * value-class *parameter* on the way in and wraps a value-class *return* on the way out, in a
 * declaration reachable from a real, separately-resolved jar. */
fun sumMeters(a: Meters, b: Meters): Meters = Meters(a.value + b.value)
