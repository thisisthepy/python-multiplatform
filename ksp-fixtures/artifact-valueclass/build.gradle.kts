/**
 * A real, separately-compiled jar for [ArtifactScanner]'s value-class handling to be proved against
 * from Python, not just from a plugin unit test.
 *
 * `kotlin.time.Duration` (the real-world case `ArtifactScanner`'s KDoc discusses) cannot prove a
 * *positive* round trip: its constructor is `internal`, so no generated Kotlin outside
 * `kotlin-stdlib` can ever build one. This module exists only to be walked -- like `:ksp-fixtures
 * :artifact`'s `junit` dependency, it is a plain `kotlin("jvm")` library with no bindings plugin of
 * its own applied, resolved as an ordinary dependency and never compiled by the module that walks
 * it.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}
