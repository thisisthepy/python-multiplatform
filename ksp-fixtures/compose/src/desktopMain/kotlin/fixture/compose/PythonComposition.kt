package fixture.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import python.multiplatform.compose.PythonCallableArena
import python.multiplatform.compose.withPythonComposer
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables

/**
 * **The one hand-written `@Composable` in the whole design**, and the only thing Python cannot do
 * for itself.
 *
 * ### What moved out of here
 *
 * The composer plumbing and the callable holder are no longer this fixture's: they are
 * `python-multiplatform-compose`'s `withPythonComposer` and `PythonCallableArena`, which the host
 * entry point `PythonContent` is built on too. What stays is executing a *source string* per pass,
 * which is a test harness rather than an entry point -- a host draws a Python-declared root with
 * `PythonContent` (`PythonContentRenderTest`).
 *
 * ### Why exactly one, and not one per component
 *
 * The 2024 `pythonx-compose` wrote a Kotlin wrapper per widget -- 37 files, 28 of them empty --
 * and `docs/design/pythonx-adapter-design.md` §1 measures what that cost: `padding()` composed nothing and
 * `fillMaxSize()` returned `self`, because a per-declaration wrapper is written once and then
 * never again. This is O(1) in the number of composables and stays O(1) by construction: it names no
 * composable, takes no composable-specific parameter, and knows nothing about the declaration Python
 * is about to call. Every `@Composable` in every artefact the walker binds goes through this one
 * function, because the only thing it supplies is the composer -- and there is exactly one composer,
 * not one per widget.
 *
 * ### What it actually supplies
 *
 * `$composer` is not a value, it is a *position*: `currentComposer` is an intrinsic the Compose
 * compiler plugin resolves to the composer parameter of the enclosing composable, so the only way to
 * obtain one is to be inside a composition. Everything else about calling a composable -- which
 * arguments were written, what `$default` mask that implies, what `$changed` should be -- is
 * arithmetic `pythonx` does in Python (`PythonxAdapter`'s `_bind_composable`), which is why it is
 * *not* here and why this stayed one function.
 *
 * The composer crosses as an ordinary `TypeTag.OBJECT` handle, which is `docs/design/ecosystem.md` §5b's
 * "the Python wrapper passes the composer as a value" and the same shape the 2024
 * `RuntimeKt.composableWrapper` used. The handle is registered here and released here, so the
 * lifetime is exactly the composition's -- `pythonx.push_composer` retains nothing and says so.
 *
 * ### What is deliberately not solved
 *
 * Recomposition. [source] is `exec`ed on every composition pass, so a Python body that is expensive
 * pays for it every frame, and a `@Composable` reached this way can never be *skipped* the way one
 * with stable parameters is. `docs/design/pythonx-adapter-design.md` §5.4 item 4 names this as a property to
 * measure before the shape is adopted for anything but a proof, and nothing here has measured it.
 */
@Composable
fun PythonComposition(source: String) {
    // `remember`, and this is the only reason the entry point needs one. A Python `content=lambda:`
    // has to outlive the call that passed it -- Compose stores it in the slot table -- so something
    // must hold a Python reference for it, and the only thing whose lifetime *is* the composition's
    // is a remembered value. See `python.multiplatform.compose.PythonCallableArena`.
    val arena = remember { CountingCallableArena() }
    withPythonComposer {
        PythonCallables.withScope(arena.scope) {
            Python3.exec(source)
        }
    }
}

/**
 * The library's [PythonCallableArena] (`python-multiplatform-compose`), counted.
 *
 * The holder itself -- why the composition, why `onForgotten`, why `onAbandoned` too -- is
 * documented where it now lives. What stays here is test-visible counters, because "the arena never
 * held anything" and "the arena held it and gave it back" produce the same reference count and must
 * not produce the same verdict.
 *
 * [released] accumulates what `PythonCallableScope.close` *reported* rather than counting calls to
 * it, which is what makes a double release visible: a second close answers `0`, so a total higher
 * than the number of callables that crossed can only come from releasing something twice.
 */
class CountingCallableArena : PythonCallableArena() {

    override fun onRemembered() {
        super.onRemembered()
        created++
        latest = this
    }

    override fun onForgotten() {
        forgotten++
        val before = super.released
        super.onForgotten()
        Companion.released += super.released - before
    }

    override fun onAbandoned() {
        abandoned++
        val before = super.released
        super.onAbandoned()
        Companion.released += super.released - before
    }

    companion object {
        var created: Int = 0
        var forgotten: Int = 0
        var abandoned: Int = 0
        var released: Int = 0

        /**
         * The most recently remembered arena, so a test can read `PythonCallableScope.liveCount`
         * **while the composition is still alive** -- `RecompositionAccumulationTest` is the whole
         * reason this exists. Set in [onRemembered] rather than in the constructor because an arena
         * that was built and then abandoned by a discarded composition never becomes the one a render
         * is measuring.
         */
        var latest: CountingCallableArena? = null

        fun resetCounters() {
            created = 0
            forgotten = 0
            abandoned = 0
            released = 0
            latest = null
        }
    }
}

/**
 * The one seed `ComposableRenderTest`'s render-proof tests need that neither the artefact walker
 * nor `pythonx` can supply on their own: a `Modifier` chain has to start somewhere, and the thing it
 * starts from is `Modifier` the *expression* -- `androidx.compose.ui.Modifier.Companion`, an object
 * instance the walker cannot hand out because it only binds functions.
 * `ksp-fixtures/artifact`'s `ComposeSeed.kt` solved the exact same gap the same way, for the
 * non-composable half of Compose's surface; this is that solution, in this module, so `Spacer` --
 * whose one parameter has no default and is therefore not omittable -- has something to be given.
 *
 * Bound by KSP, not the artefact walker: a plain top-level function in this module's own source is
 * an ordinary `FunctionTable` entry, reached from Python through `PythonProxySource` under this
 * module's own Kotlin package name (`fixture.compose.emptyModifier`), the same route
 * `WalkedArtifactComposeModifierTest` uses for `fixture.artifact.emptyModifier`. `pythonx`'s
 * `_BY_PACKAGE` dispatch -- built over `ArtifactTable` -- never sees it, and does not need to: the
 * `Modifier` handle this returns is an ordinary `TypeTag.OBJECT` value, indistinguishable at the
 * boundary from one a walked declaration produced, so it can be threaded into a `pythonx`-bound
 * composable's `modifier` slot once built.
 */
fun emptyModifier(): Modifier = Modifier
