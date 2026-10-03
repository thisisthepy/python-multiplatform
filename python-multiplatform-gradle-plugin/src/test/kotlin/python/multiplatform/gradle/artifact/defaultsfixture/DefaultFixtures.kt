// Fixture, not production source: compiled as part of this module's own test sourceSet so that
// `DefaultOmissionTest` can locate it on disk (via `Class.forName(...).protectionDomain.codeSource`,
// exactly as `ArtifactScannerTest` already does for `kotlin-stdlib` and for the value-class fixture)
// and walk it with ASM as if it were a third-party jar.
//
// A package of its own rather than more declarations in `fixture.artifactvalueclass`: several of
// `ArtifactScannerTest`'s and `DeclarationModelTest`'s assertions pin that package's *whole* output
// as an exact list, and adding a declaration there would fail them for no reason connected to
// defaults.
package fixture.artifactdefaults

/** An ordinary class, so that a declaration here can have an OBJECT receiver the way
 * `androidx.compose.ui.Modifier` does. */
class Bag(val text: String)

/**
 * One required parameter and one defaulted one -- the shape the judgement rests on: a Python caller
 * must be able to write `greet("hi")` and reach the Kotlin default for `punctuation`.
 */
fun greet(name: String, punctuation: String = "!"): String = name + punctuation

/**
 * Two defaulted parameters, so that the *middle* of the list can be the one omitted.
 * `docs/archive/pythonx-adapter-design.md` §4.5 names exactly this as what an arity-prefix scheme cannot
 * express: "pass `text` and `font_size`, skip `modifier` and `color`".
 */
fun label(bag: Bag, prefix: String = "[", suffix: String = "]"): String = prefix + bag.text + suffix

/** The `Modifier.padding(horizontal =, vertical =)` shape: an extension whose receiver and return
 * are the same ordinary class, with every value parameter defaulted. */
fun Bag.padded(horizontal: Double = 1.0, vertical: Double = 2.0): Bag =
    Bag(text + "|h=" + horizontal + ",v=" + vertical)

/**
 * Seven defaulted parameters: one past `ArtifactScanner.MAX_OMITTABLE_PARAMETERS`.
 *
 * The cap exists because presence branching costs one generated call expression per subset of the
 * defaulted parameters, and 2^n is a build-time cost paid on every build. Nothing in the measured
 * corpus reaches it (the widest bound declaration anywhere in `foundation-layout`, `kotlin-stdlib`
 * and JUnit 4 declares four defaults), so the cap needs a fixture of its own to fire at all.
 */
@Suppress("LongParameterList")
fun wide(
    a: Int = 1,
    b: Int = 2,
    c: Int = 3,
    d: Int = 4,
    e: Int = 5,
    f: Int = 6,
    g: Int = 7,
): Int = a + b + c + d + e + f + g

/** Exactly at the cap, so that the boundary is pinned from both sides. */
@Suppress("LongParameterList")
fun sixWide(
    a: Int = 1,
    b: Int = 2,
    c: Int = 3,
    d: Int = 4,
    e: Int = 5,
    f: Int = 6,
): Int = a + b + c + d + e + f

/**
 * A **required** parameter whose name is a Kotlin hard keyword, beside a defaulted one.
 *
 * Metadata records the name unescaped, and the partial branch has to name every argument it passes
 * -- so it has to write `object = ...`, which does not parse. The generator puts the backticks back.
 * The keyword parameter is the required one on purpose: if it were the defaulted one, the only
 * branch that named it would be the one that omits it, and nothing would ever be written.
 */
fun keyworded(`object`: String, value: Int = 0): String = `object` + value

/**
 * Two overloads of one Kotlin name with every parameter defaulted on both, which is the shape that
 * made `foundation-layout` fail to compile before [ArtifactScanner.applyDefaultOmission] existed.
 *
 * `WindowInsets(Dp, Dp, Dp, Dp)` and `WindowInsets(Int, Int, Int, Int)` are the real instance; this
 * is the same thing in two types this module owns. A generated branch that writes **no** argument is
 * `insets()`, which Kotlin reports as `Overload resolution ambiguity` -- there is nothing left to
 * type-annotate, which is precisely why the tie cannot be broken. A branch that writes *one* is
 * unambiguous, because the argument's type picks the overload.
 */
fun insets(left: Double = 0.0, right: Double = 0.0): String = "d:" + left + "," + right

fun insets(left: Int = 0, right: Int = 0): String = "i:" + left + "," + right

/** No default anywhere: its generated body must stay byte-identical to what the walker emitted
 * before defaults existed, which is what keeps this change off the 43 of 70 `foundation-layout`
 * entries that declare none. */
fun plain(a: Int, b: Int): Int = a + b
