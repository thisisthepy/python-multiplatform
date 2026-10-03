package fixture.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Density
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`Modifier.composed` admits a real composer**, settling `docs/archive/pythonx-adapter-design.md` §9.2/§9.4's
 * open question: "unverified whether `composed`'s own contract... admits a composer at all."
 * `fixture.compose.pythonComposed` (`PythonComposed.kt`) is the hand-written wrapper; this proves two
 * independent things about it, the way every other §9 render test in this module proves both a
 * positive claim and a control rather than one alone.
 *
 * 1. [aFactoryProducedModifierIsAppliedAndItsSizeCrosses] -- the `Modifier` [factory] returns actually
 *    reaches the node Compose measures, and the *value* Python chose crosses (not a stub that always
 *    applies the same fixed size, and not a no-op that leaves the node at its intrinsic size).
 * 2. [theSameCompositionReusesTheRememberedSlotAcrossRecompositions] -- the composer `factory` runs
 *    with is wired into a real, positionally-stable slot table, not a placeholder: `remember` inside
 *    `pythonComposed`'s `composed { }` lambda allocates once and is *reused* across recompositions of
 *    the same node, even though `PythonComposition` re-`exec`s the Python source (and so calls
 *    `pythonComposed` itself afresh, building a brand new `ComposedModifier` Kotlin object) on every
 *    pass. That persistence is `Modifier.composed`'s whole reason to exist (it is what lets
 *    `Modifier.clickable`'s internal `remember(interactionSource) { ... }` survive a modifier chain
 *    rebuilt from scratch every recomposition) and a fake/inert composer could not produce it.
 */
class ComposedRenderTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
        ComposedInvocationSlot.reset()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * Positive: `factory` resizes the box to 70dp, and a `Layout` wrapping [PythonComposition] (the
     * same read-back [LayoutIdRenderTest] uses) measures a 70px-wide placeable at density 1.
     *
     * Control: `factory` returns its receiver unchanged (no resize at all) -- the placeable then
     * measures at `Text("x")`'s own intrinsic width, which this asserts is neither 70 nor the other
     * positive case's 33, ruling out a `pythonComposed` that ignores [factory]'s result and a
     * `factory` result that is applied regardless of what it actually says.
     */
    @Test
    fun aFactoryProducedModifierIsAppliedAndItsSizeCrosses() {
        val resized70 = measuredWidth(sizingBody(70.0))
        val resized33 = measuredWidth(sizingBody(33.0))
        val identity = measuredWidth(IDENTITY_BODY)

        println(
            "compose composed: factory size 70 -> ${resized70}px, size 33 -> ${resized33}px, " +
                "identity factory -> ${identity}px",
        )

        assertEquals(70, resized70, "the 70dp factory result was not applied to the measured node")
        assertEquals(33, resized33, "the 33dp factory result was not applied to the measured node")
        assertTrue(
            identity != 70 && identity != 33,
            "an identity factory (no resize) measured the same as a real resize -- " +
                "the factory's return value is not what is being measured: ${identity}px",
        )
    }

    /**
     * Forces [PASSES] recompositions of one composition (the same `mutableStateOf` + textual-comment
     * lever `RecompositionAccumulationTest.measure` uses, since `PythonComposition(source: String)`
     * only recomposes when its `String` argument actually changes), and checks two counters
     * [ComposedInvocationSlot] keeps:
     *
     * - [ComposedInvocationSlot.remembersAllocated] -- how many times `remember`'s factory itself ran.
     *   `1`, not [PASSES], is the claim: the slot table remembered across every later pass.
     * - the surviving slot's own [ComposedInvocationSlot.invocations] -- how many times the
     *   `composed { }` lambda body ran at all. Equal to [PASSES]: `pythonComposed` (and so `factory`)
     *   really did get re-invoked, and re-materialized, on every recomposition rather than being
     *   skipped or cached wholesale.
     *
     * Both together are what a placeholder composer could not produce: either `remember` would not be
     * usable at all (no slot table to remember into), or it would allocate fresh every pass (no
     * position to remember *at*).
     */
    @Test
    fun theSameCompositionReusesTheRememberedSlotAcrossRecompositions() {
        val body = mutableStateOf(sizingBody(70.0, pass = 0))
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            PythonComposition(body.value)
        }
        try {
            scene.render()
            for (pass in 1 until PASSES) {
                body.value = sizingBody(70.0, pass = pass)
                Snapshot.sendApplyNotifications()
                scene.render()
            }
            val slot = ComposedInvocationSlot.lastCreated
                ?: error("pythonComposed's composed{} lambda never ran")
            println(
                "compose composed: $PASSES recompositions -> " +
                    "remembersAllocated=${ComposedInvocationSlot.remembersAllocated} " +
                    "invocations=${slot.invocations}",
            )
            assertEquals(
                1, ComposedInvocationSlot.remembersAllocated,
                "remember inside composed{} allocated more than once across recompositions of the " +
                    "same node -- the slot table is not being reused, so the composer is not real",
            )
            assertEquals(
                PASSES, slot.invocations,
                "the composed{} lambda did not run once per recomposition: ${slot.invocations} " +
                    "of $PASSES",
            )
        } finally {
            scene.close()
        }
    }

    private fun measuredWidth(body: String): Int {
        var width = -1
        val scene = ImageComposeScene(width = SCENE, height = SCENE, density = Density(1f)) {
            Layout(content = { PythonComposition(body) }) { measurables, constraints ->
                val placeable = measurables.first().measure(constraints)
                width = placeable.width
                layout(SCENE, SCENE) { placeable.placeRelative(0, 0) }
            }
        }
        try {
            scene.render()
            return width
        } finally {
            scene.close()
        }
    }

    private fun sizingBody(dp: Double, pass: Int = 0) = """
        # pass $pass
        from fixture.compose import emptyModifier, pythonComposed
        from androidx.compose.foundation.layout import size__Dp
        from androidx.compose.material3 import Text

        def _factory(receiver):
            return size__Dp(receiver, $dp)

        _m = pythonComposed(emptyModifier(), _factory)
        Text("x", modifier=_m)
    """.trimIndent()

    private companion object {
        const val SCENE = 200
        const val PASSES = 6

        val IDENTITY_BODY = """
            from fixture.compose import emptyModifier, pythonComposed
            from androidx.compose.material3 import Text

            def _factory(receiver):
                return receiver

            _m = pythonComposed(emptyModifier(), _factory)
            Text("x", modifier=_m)
        """.trimIndent()
    }
}
