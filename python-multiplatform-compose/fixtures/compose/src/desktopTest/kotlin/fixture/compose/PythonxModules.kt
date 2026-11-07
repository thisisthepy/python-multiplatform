package fixture.compose

/**
 * The value classes these tests may write as a raw primitive.
 *
 * There is no module map beside this any more. The binder does not rename Kotlin namespaces at
 * all -- a Kotlin package is importable under its own name and nothing else -- so there is nothing
 * to map. What a `pythonx.*` module is called, and what it restructures, is the business of the
 * real distribution that ships those modules on disk.
 *
 * This set survives because it is a different kind of fact: whether a value class may be written
 * as the primitive it wraps is a property of the library that declares it, and no bytecode
 * witnesses it. `Dp`'s public constructor is the identity on its float, so `padding(16)` and
 * `padding(Dp(16f))` mean the same thing; `Color` and `TextUnit` pack several fields into one
 * value and a raw number would decode as something else.
 */
internal val PYTHONX_RAW_VALUE_CLASSES: Set<String> = setOf("androidx.compose.ui.unit.Dp")
