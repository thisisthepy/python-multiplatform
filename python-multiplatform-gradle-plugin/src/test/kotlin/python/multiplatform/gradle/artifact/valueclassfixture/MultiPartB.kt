// Second part of the same multi-file facade as `MultiPartA.kt`. `shoutViaFacade` combines the two
// hard cases the walker's KDoc names -- a multi-file part *and* an extension receiver -- in the one
// shape `kotlin.text.trimIndent` actually has (`String.trimIndent()` lives on `StringsKt__IndentKt`,
// one of several parts behind the `StringsKt` facade).
@file:JvmName("MultiFacadeKt")
@file:JvmMultifileClass

package fixture.artifactvalueclass.multipart

fun partNumber(x: Int): Int = x * 2

fun String.shoutViaFacade(): String = uppercase() + "!!"
