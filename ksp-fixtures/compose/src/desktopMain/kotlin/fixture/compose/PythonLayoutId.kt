package fixture.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId

/**
 * `Modifier.layoutId`, declined for a reason unrelated to suspend
 * (`docs/design/pythonx-adapter-design.md` §9's table): its one non-`Modifier` parameter is `kotlin.Any`, and
 * `resolveKotlinType` has no [python.multiplatform.reflection.TypeTag] for "whatever tag the caller
 * meant" -- `layoutId` is real-world called with a bare `String` or `Int`, not a Kotlin object handle.
 * One wrapper per concrete tag fixes it, the same way every other concretely-typed hand-written
 * wrapper in this module does; this is the `String` one, since that is what `LayoutIdRenderTest`
 * exercises.
 *
 * ### Why there is no `pythonLayoutIdProbe` alongside it
 *
 * A first version of this fix paired it with a hand-written `@Composable fun pythonLayoutIdProbe(...)`
 * meant to make the tagged value observable, bound as a plain top-level function the same way
 * [pythonPointerInput] is. It does not bind: `BindingPolicy.isComposable` excludes **every**
 * `@Composable` top-level function from `FragmentScanner`'s plain-function path on purpose (its own
 * KDoc: "a `@Composable` in the consumer's own source, which KSP cannot bind and must not try to" --
 * calling it from the generated, non-composable fragment source would not compile). `PythonComposition`
 * is not a counterexample: Python never calls it, Kotlin does, from inside `ImageComposeScene`'s own
 * composable content lambda. `LayoutIdRenderTest` reads `layoutId` back the same way -- a `Layout`
 * built in the *Kotlin test*, wrapping `PythonComposition`, rather than a hand-written composable
 * Python calls.
 */
fun pythonLayoutIdString(modifier: Modifier, id: String): Modifier = modifier.layoutId(id)

fun pythonLayoutIdInt(modifier: Modifier, id: Int): Modifier = modifier.layoutId(id)
