// See `ValueClassFixtures.kt` for why this lives in the walker's own test sourceSet rather than a
// dependency of it.
//
// `@JvmMultifileClass` + a shared `@JvmName` across this file and `MultiPartB.kt` reproduces
// `kotlin.text`'s own shape: `kotlin.text.trimIndent` compiles onto the package-private
// `StringsKt__IndentKt`, behind the public facade `StringsKt`. Here, `partGreeting` compiles onto
// this file's own package-private part class, behind the public facade `MultiFacadeKt` -- and is
// called, from Python, under the Kotlin name `fixture.artifactvalueclass.multipart.partGreeting`,
// never under either JVM class name.
@file:JvmName("MultiFacadeKt")
@file:JvmMultifileClass

package fixture.artifactvalueclass.multipart

fun partGreeting(): String = "hello from part A"
