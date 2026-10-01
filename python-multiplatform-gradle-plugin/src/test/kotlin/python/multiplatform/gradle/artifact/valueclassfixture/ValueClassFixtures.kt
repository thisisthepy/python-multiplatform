// Fixture, not production source: compiled as part of this module's own test sourceSet so that
// `ArtifactScannerTest` can locate it on disk (via `Class.forName(...).protectionDomain.codeSource`,
// exactly as it already does for `kotlin-stdlib`) and walk it with ASM as if it were a third-party
// jar. It never cooperates with the walker -- that is the whole point of the fixture.
package fixture.artifactvalueclass

/**
 * A minimal `@JvmInline value class` with a *public* primary constructor and a public accessor.
 *
 * `kotlin.time.Duration` is the real-world case the old `-` filter was written against
 * (`getInWholeSeconds-impl`), but `Duration`'s own constructor is `internal` -- there is no Kotlin
 * syntax generated code outside the module could use to build one from a raw `Long`, so it cannot
 * prove a *positive* round trip no matter how well the walker understands its metadata. `Meters` is
 * the same shape with a constructor the walker is actually allowed to call.
 */
@JvmInline
value class Meters(val value: Double)

/** Both parameters and the return are the value class: proves unwrap-on-the-way-in and
 * wrap-on-the-way-out in the same declaration, under a name no other overload shares. */
fun sumMeters(a: Meters, b: Meters): Meters = Meters(a.value + b.value)

/**
 * Same Kotlin name as the next declaration, unrelated JVM shape: this overload has no value-class
 * parameter, so the compiler does **not** mangle its name (`addMeters(II)I`, no suffix), while the
 * one below does (`addMeters-<hash>(DD)D`). Before this walker read `@Metadata`, that meant the two
 * were invisible to each other: the `-` filter dropped the value-class overload outright, so the old
 * code saw exactly one `addMeters` candidate and bound it -- silently wrong, because Python
 * `addMeters(1, 2)` would have called an overload chosen by nothing more principled than which one
 * survived a different filter. Once both resolve to the same Kotlin name, grouping must drop both,
 * the same as `org.junit.Assert.assertEquals`.
 */
fun addMeters(a: Int, b: Int): Int = a + b

fun addMeters(a: Meters, b: Meters): Meters = Meters(a.value + b.value)

/** An extension function whose *receiver* is the value class, not just a parameter -- the shape
 * `Modifier.padding(Dp)` has by half (the receiver `Modifier` is not a value class there, but the
 * parameter is; here it is the receiver that needs unwrap-on-the-way-in and the return that needs
 * wrap-on-the-way-out). */
fun Meters.doubled(): Meters = Meters(value * 2)

/** An extension with an ordinary (non-value-class) receiver -- the same shape as
 * `kotlin.text.trimIndent`, minus the multi-file split. */
fun String.shout(): String = uppercase() + "!"

/** Never reaches the table: a `suspend` function's JVM shape takes a trailing `Continuation`
 * parameter, which [boundaryTypeOf] declines -- but this pins that the walker's *metadata* layer
 * also recognises and skips it explicitly, per AGENTS.md's "제외한 것은 조용히 빠뜨리지 마라". */
suspend fun neverBound(): Int = 1

/** `internal` is JVM-`public` (Kotlin does not always mangle a top-level `internal` name), so only
 * `@Metadata`'s own visibility -- not the JVM access flag every other filter here already checks --
 * can tell this apart from [sumMeters]. */
internal fun secretlyInternal(): Int = 42

/**
 * An ordinary public class: neither a primitive nor a value class, and therefore the shape
 * `androidx.compose.ui.Modifier` has. Nothing about it can be erased to something the boundary
 * already carries, so it can only cross as an opaque handle
 * (`python.multiplatform.reflection.HandleTable`).
 */
class Rope(val length: Double)

/** OBJECT in return position: Python receives a handle it did not previously hold. */
fun makeRope(length: Double): Rope = Rope(length)

/** OBJECT in parameter position: the handle Python holds resolves back to the same instance. */
fun ropeLength(rope: Rope): Double = rope.length

/**
 * `Modifier.padding(Dp): Modifier` reproduced with types this module controls: an extension whose
 * receiver **and** return are the same ordinary class, which is what makes chaining possible at all.
 */
fun Rope.lengthened(by: Double): Rope = Rope(length + by)

/** Two overloads whose *value* parameter lists are identical and whose receivers are not -- the
 * case `Int.times`/`Double.times` has in `androidx.compose.ui.unit`. Disambiguating on value
 * parameters alone cannot separate these, so the receiver has to join the name. */
fun Rope.tagged(): String = "rope"

fun Meters.tagged(): String = "meters"

/**
 * A function-typed parameter: the shape 44 of the 46 declined public top-level `Modifier` extensions
 * had (`ArtifactScannerTest.composeModifierExtensionsSurviveBothGates` counts them), and the one
 * `ArtifactScanner.functionSlotOrNull` opened.
 *
 * This used to be *the* declined witness: `resolveKotlinType` refused it because Kotlin's built-in
 * `Function0` has no class file to name, and `DeclarationModelTest` used it for the other half of
 * `docs/design/pyi-generation-design.md` §2.2's third property. It binds now, so the declined witness moved
 * to [withSuspendCallback] and this one records the positive: a declaration that **invokes** what it
 * is handed, which is what `:ksp-fixtures:artifact` exercises against `kotlin.system.measureTimeMillis`
 * for real.
 */
fun withCallback(action: () -> Unit): Int {
    action()
    return 1
}

/**
 * The function-typed shape that is still declined, and not for want of a *name*: its slot is
 * describable and its arity checks out.
 *
 * A `suspend` lambda's compiled type is `Function1<Continuation<Unit>, Any?>`, it answers
 * `COROUTINE_SUSPENDED` rather than a value, and Kotlin will not let source assign a `Function1` to a
 * `suspend` function type at all. `Modifier.pointerInput` is the real-world case -- the one
 * `a179b747`'s open note named beside `clickable`, and the one that turns out to be a different limit
 * from the one that note was about.
 */
fun withSuspendCallback(action: suspend () -> Unit): Int = if (action === action) 1 else 0

/**
 * `kotlin.time.Duration.getInWholeSeconds-impl(J)J`'s exact shape: a value class with a *public*
 * constructor and accessor (unlike `Duration`'s own `internal` one), but where the mangled method is
 * a true member declared inside the class body rather than a top-level extension declared elsewhere.
 * `doubled` compiles onto `Seconds` itself as `getDoubled-impl(J)J` -- one JVM parameter (the unboxed
 * receiver), zero declared Kotlin parameters -- which is what makes it decline even though nothing
 * about its *visibility* would.
 */
@JvmInline
value class Seconds(val raw: Long) {
    val doubled: Long get() = raw * 2
}

interface Alignment {
    companion object {
        val Center: Alignment = object : Alignment {}
        val TopStart: Alignment = object : Alignment {}
    }
}

object AbsoluteAlignment {
    val TopLeft: Alignment = object : Alignment {}
}
