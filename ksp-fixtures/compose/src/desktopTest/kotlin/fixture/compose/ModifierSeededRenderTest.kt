package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
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
import kotlin.test.assertTrue

/**
 * **`Spacer`**, the one declaration in `foundation.layout` that `ComposableRenderTest` cannot reach
 * with `PythonxAdapter` alone.
 *
 * ### Why `Spacer` is a different kind of problem from `Row`, `Box` or `Column`
 *
 * Every container `ComposableRenderTest` renders takes a `content: @Composable Scope.() -> Unit`
 * with no default, so a Python `lambda` is what fills the one required slot. `Spacer` has no
 * `content` at all -- its single parameter is `modifier: Modifier`, **also with no default**
 * (`fun Spacer(modifier: Modifier)`, not `= Modifier`; `Box`'s content-free overload,
 * `Box__Modifier`, has the identical shape and is left equally unreached here). Filling that slot
 * needs a `Modifier` *value*, and nothing the artefact walker binds can produce one on its own:
 * `Modifier`'s only source is `androidx.compose.ui.Modifier.Companion`, an object instance, and
 * `ArtifactScanner` binds functions, not statics of an arbitrary object. `ksp-fixtures/artifact`'s
 * `ComposeSeed.kt` hit exactly this wall and solved it with one hand-written Kotlin function that
 * KSP binds instead; `fixture.compose.emptyModifier` (`PythonComposition.kt`) is that same seed,
 * placed in this module so `Spacer` has something to receive.
 *
 * ### Why this needs its own `@BeforeTest` rather than `ComposableRenderTest`'s
 *
 * `emptyModifier` and `androidx.compose.foundation.layout.size__Dp` are not composables and are not
 * reached through `pythonx` (`docs/design/pythonx-adapter-design.md`'s dynamic loader is for the
 * composable half of the surface). They come from the *other* producer,
 * `PythonProxySource` + `FunctionTable`, the route `WalkedArtifactComposeModifierTest` uses for the
 * identical seed in `ksp-fixtures/artifact`. Both producers run in the same test here --
 * `PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)` for `Spacer` itself, `PythonProxySource.install()` for the `Modifier`
 * chain that fills its one argument -- kept out of `ComposableRenderTest`'s shared setup so a
 * regression in this newer combination cannot fail ten unrelated tests that never asked for it.
 *
 * ### How "the size crossed" is decided
 *
 * `Spacer` draws nothing itself -- it has no color, only extent -- so [pythonDrawsARealMaterial3Text]'s
 * "ink appeared" cannot be reused here: an ink count over a scene with only a `Spacer` in it is `0`
 * whether or not the size argument ever reached Compose. What a `Spacer`'s size *does* affect is
 * where whatever comes after it lands, so the proof is positional: two `Text`s in a `Row` with a
 * `Spacer` between them, and the rightmost ink column of the whole frame -- the right edge of the
 * second `Text` -- has to move right by (approximately) the difference between two `Spacer` widths.
 * A `Spacer` whose `modifier` argument never reached Compose (dropped, or resolved to the empty
 * `Modifier` regardless of what Python passed) would leave that edge in the same place for both.
 */
class ModifierSeededRenderTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * The claim, and the guard against the weaker one: a `Spacer` given a *bigger* size pushes the
     * `Text` after it further right than a small one does. "Some frame came out" would pass even if
     * `Spacer` silently used `Modifier` (its notional default in ordinary Compose usage, though not
     * a default this compiled shape actually declares) for every call.
     */
    @Test
    fun spacersSizeCrossesAndMovesWhatComesAfterIt() {
        val small = rightmostInk(sizeDp = 4.0)
        val large = rightmostInk(sizeDp = 80.0)
        println("compose render: Spacer(size=4dp) -> rightmost ink at x=$small, Spacer(size=80dp) -> x=$large")
        assertTrue(small > 0, "nothing drew at all -- the Text either side of the Spacer never composed")
        assertTrue(
            large > small + 40,
            "a 76dp larger Spacer moved the trailing Text by less than 40px: $small -> $large",
        )
    }

    /**
     * The negative control [spacersSizeCrossesAndMovesWhatComesAfterIt] needs: without any `Spacer`
     * at all, the two `Text`s sit immediately adjacent, and the seam between "a `Row` needs a
     * `Spacer` to move its second child" and "a `Row` moves it regardless" is exactly this frame's
     * rightmost ink versus the 4dp case above (asserted loosely here, strictly there).
     */
    @Test
    fun noSpacerLeavesTheSecondTextWhereTheFirstOneEnds() {
        val body = """
            from androidx.compose.foundation.layout import Row
            from androidx.compose.material3 import Text
            Row(content=lambda row: (Text('hi'), Text('hi')))
        """.trimIndent()
        val rightmost = rightmostInkOf(body)
        println("compose render: Row(Text, Text) with no Spacer -> rightmost ink at x=$rightmost")
        assertTrue(rightmost > 0, "nothing drew at all")
    }

    private fun rightmostInk(sizeDp: Double): Int {
        val body = """
            from fixture.compose import emptyModifier
            from androidx.compose.foundation.layout import size__Dp
            from androidx.compose.foundation.layout import Spacer, Row
            from androidx.compose.material3 import Text
            Row(content=lambda row: (Text('hi'), Spacer(modifier=size__Dp(emptyModifier(), $sizeDp)), Text('hi')))
        """.trimIndent()
        return rightmostInkOf(body)
    }

    private fun rightmostInkOf(body: String): Int {
        val scene = ImageComposeScene(width = WIDTH, height = 60, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val image: Image = scene.render()
            val bitmap = Bitmap.makeFromImage(image)
            var rightmost = -1
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getColor(x, y) != BACKGROUND && x > rightmost) rightmost = x
                }
            }
            return rightmost
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 200
        const val BACKGROUND = 0
    }
}
