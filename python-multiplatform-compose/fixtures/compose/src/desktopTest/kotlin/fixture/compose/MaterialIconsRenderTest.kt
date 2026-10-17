package fixture.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #37: `material-icons-core` in the walk, so `Icon` has an `ImageVector` to draw. `Icons.Default`
 * is an object constant, and the icons themselves are extension properties on `Icons.Filled`, bound
 * as getters since issue #38 ([iconsDefaultAddDrawsFromPythonExactlyAsFromKotlin]).
 *
 * The artifact is `org.jetbrains.compose.material:material-icons-core:1.7.3` -- the newest JetBrains
 * published (Maven Central), and the one Compose Multiplatform 1.11.x points to; its `ui`
 * dependencies resolve up to 1.11.1.
 */
class MaterialIconsRenderTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /** The control: the same icon drawn from Kotlin, so a 0 below means "the crossing failed". */
    @Test
    fun theAddIconDrawsFromKotlin() {
        val ink = inkOfScene { Icon(Icons.Default.Add, contentDescription = null) }
        println("compose render: Kotlin Icon(Icons.Default.Add) -> $ink px")
        assertTrue(ink > 0, "the control icon drew nothing")
    }

    /** `Icons.Default` is bound, and `describe()` says what it is without reading it. */
    @Test
    fun iconsDefaultIsBoundAndDescribed() {
        Python3.exec(
            """
            import python_multiplatform as _mi_pm
            import androidx.compose.material.icons as _mi_icons
            assert 'Icons' in dir(_mi_icons), dir(_mi_icons)
            _mi_d = _mi_pm.describe(_mi_icons.Icons, 'Default')
            assert _mi_d['kind'] == 'STATIC_GETTER', _mi_d
            assert _mi_d['returns'] == 'androidx.compose.material.icons.Icons.Filled', _mi_d
            assert 'Default' in dir(_mi_icons.Icons), dir(_mi_icons.Icons)
            """.trimIndent(),
        )
    }

    /**
     * **The render proof issue #37 asked for, closed by #38's extension-property getters.** `Add` is a
     * top-level extension property, `val Icons.Filled.Add: ImageVector` (`AddKt.getAdd(Icons$Filled)`
     * in the jar); the walker now binds it as a `GETTER` whose receiver is `Icons.Filled`, so
     * `Icons.Default.Add` is an attribute read on the `Icons.Default` value, and `Icon` draws it.
     *
     * Exact ink, not `> 0`: the same `Icon(Icons.Default.Add, contentDescription = null)` drawn from
     * Kotlin is the control ([theAddIconDrawsFromKotlin]), so a different vector, a different size or
     * an empty box all fail this. `contentDescription=None` is a written `null` for a slot with no
     * default, which the binding layer now passes rather than refusing as "no value".
     *
     * Red before #38: `Icons.Default.Add` raised `AttributeError` (no binding had `Icons.Filled` as its
     * receiver), so the composition body failed before `Icon` was called.
     */
    @Test
    fun iconsDefaultAddDrawsFromPythonExactlyAsFromKotlin() {
        val kotlin = inkOfScene { Icon(Icons.Default.Add, contentDescription = null) }
        val python = inkOfScene {
            PythonComposition(
                """
                import androidx
                from androidx.compose.material3 import Icon
                Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription=None)
                """.trimIndent(),
            )
        }
        val blank = inkOfScene { PythonComposition("pass") }
        println("compose render: Icon(Icons.Default.Add) Kotlin -> $kotlin px, Python -> $python px, empty -> $blank px")
        assertEquals(0, blank, "an empty composition must draw nothing, or the measurement is not measuring")
        assertTrue(kotlin > 0, "the control icon drew nothing")
        assertEquals(kotlin, python, "Python's Icon(Icons.Default.Add) did not draw what Kotlin's does")
    }

    /** What the walker bound for `Add`, described without being read: a `GETTER` on `Icons.Filled`. */
    @Test
    fun iconsFilledAddIsAGetterOnItsReceiver() {
        Python3.exec(
            """
            import python_multiplatform as _mi_pm
            _mi_d = _mi_pm.describe_member('androidx.compose.material.icons.Icons.Filled', 'Add')
            assert len(_mi_d) == 1, _mi_d
            assert _mi_d[0]['kind'] == 'GETTER', _mi_d
            assert _mi_d[0]['name'] == 'androidx.compose.material.icons.filled.Add', _mi_d
            assert _mi_d[0]['returns'] == 'androidx.compose.ui.graphics.vector.ImageVector', _mi_d
            assert _mi_d[0]['receiver'] == 'androidx.compose.material.icons.Icons.Filled', _mi_d
            import androidx
            _mi_add = androidx.compose.material.icons.Icons.Default.Add
            assert type(_mi_add)._kotlin_type_name == 'androidx.compose.ui.graphics.vector.ImageVector', type(_mi_add)
            """.trimIndent(),
        )
    }

    private fun inkOfScene(content: @Composable () -> Unit): Int {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f), content = content)
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            var ink = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getColor(x, y) != 0) ink++
                }
            }
            return ink
        } finally {
            scene.close()
        }
    }
}
