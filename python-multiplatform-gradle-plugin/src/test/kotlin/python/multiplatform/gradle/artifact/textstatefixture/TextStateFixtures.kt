// Fixture, not production source: compiled with this module's tests so `TextStateBindingTest` can walk
// it as bytecode plus `@Metadata`, the same way `PropertyFixtures.kt` is walked (issue #73).
@file:Suppress("unused", "UNUSED_PARAMETER")

package fixture.artifacttextstate

/**
 * `androidx.compose.ui.text.TextRange`'s shape: a value class whose constructor is `internal` and whose
 * wrapped value is private, so it can neither be built from a raw number nor unwrapped and crosses as
 * an object handle.
 */
@JvmInline
value class Span internal constructor(private val packed: Long)

/** `TextRange(index: Int)`'s shape: the one way outside code makes a [Span]. */
fun spanAt(index: Int): Span = Span(index.toLong())

/**
 * `androidx.compose.foundation.text.input.TextFieldState`'s shape: a public constructor whose second
 * parameter is a value class and both of whose parameters declare a default, and a `text` read as
 * `CharSequence`.
 *
 * The value-class parameter is the point: `kotlinc` compiles this constructor to a JVM-`private`
 * `<init>(String, long)` behind a public **synthetic** `<init>(String, long, DefaultConstructorMarker)`,
 * and the bridge is the descriptor `@Metadata` records.
 */
class Editable(initialText: String = "", initialSelection: Span = Span(initialText.length.toLong())) {
    internal val buffer = StringBuilder(initialText)

    val text: CharSequence get() = buffer

    /** A nullable `CharSequence`: `null` while empty. */
    val draft: CharSequence? get() = if (buffer.isEmpty()) null else buffer
}

/** `TextFieldState.setTextAndPlaceCursorAtEnd(String)`'s shape. */
fun Editable.replaceAll(text: String) {
    buffer.setLength(0)
    buffer.append(text)
}

/** `TextFieldState.clearText()`'s shape. */
fun Editable.clear() {
    buffer.setLength(0)
}

/** A `CharSequence` returned by a function rather than read off a property. */
fun Editable.snapshot(): CharSequence = text

/** A `CharSequence` *parameter* stays declined: only a declared result is converted. */
fun lengthOf(text: CharSequence): Int = text.length

/**
 * `PointerInputChange`'s and `TextStyle`'s shape: two constructors with a value-class parameter, one of
 * them `@Deprecated(level = HIDDEN)`. Both compile to a synthetic `DefaultConstructorMarker` bridge;
 * only the hidden one's carries `kotlin.Deprecated`, and Kotlin source cannot call it at all.
 */
class Legacy(val span: Span) {
    @Deprecated("gone", level = DeprecationLevel.HIDDEN)
    constructor(span: Span, flag: Boolean) : this(span)
}

/** An ordinary hidden constructor with no value-class parameter: a plain synthetic `<init>`, no bridge. */
class Plain(val n: Int) {
    @Deprecated("gone", level = DeprecationLevel.HIDDEN)
    constructor(n: Int, flag: Boolean) : this(n)
}
