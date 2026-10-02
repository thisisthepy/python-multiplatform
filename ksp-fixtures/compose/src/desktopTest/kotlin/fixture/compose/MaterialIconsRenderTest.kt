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
import kotlin.test.assertTrue

/**
 * Issue #37: `material-icons-core` in the walk, so `Icon` has an `ImageVector` to draw -- **partly**:
 * `Icons.Default` is bound, the icons themselves are not (see
 * [iconsDefaultAddIsUnreachableBecauseTheWalkerBindsNoExtensionProperty]).
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
     * **Not reached yet -- pinned, with the reason.** Issue #37's completion statement is
     * `Icon(Icons.Default.Add, contentDescription=None)` from Python, and adding the artifact is not
     * enough for it: `Icons.Default` is bound (above), but `Add` is a top-level **extension property**,
     * `val Icons.Filled.Add: ImageVector` (`AddKt.getAdd(Icons$Filled)` in the jar), and the walker
     * binds a file facade's *functions* only (`ArtifactScanner.scanClassNode` reads
     * `kmPackage.functions`, never `kmPackage.properties`). So no binding has `Icons.Filled` as its
     * receiver and the attribute is an `AttributeError`.
     *
     * The day the walker binds extension properties, this fails, and it should become the render
     * proof: `Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription=None)`
     * drawing the same ink as [theAddIconDrawsFromKotlin].
     */
    @Test
    fun iconsDefaultAddIsUnreachableBecauseTheWalkerBindsNoExtensionProperty() {
        Python3.exec(
            """
            import androidx
            import python_multiplatform.binding as _mi_b
            _mi_filled = 'androidx.compose.material.icons.Icons.Filled'
            _mi_default = androidx.compose.material.icons.Icons.Default
            assert type(_mi_default)._kotlin_type_name == _mi_filled, type(_mi_default)
            try:
                _mi_default.Add
                raise AssertionError('Icons.Default.Add is reachable now -- turn this pin into the render proof')
            except AttributeError:
                pass
            _mi_on_filled = [d.kotlin_name for d in _mi_b._TABLE.values() if d.receiver_type_name == _mi_filled]
            assert _mi_on_filled == [], repr(_mi_on_filled)
            assert 'androidx.compose.material.icons.filled' not in _mi_b._BY_PACKAGE, \
                sorted(_mi_b._BY_PACKAGE['androidx.compose.material.icons.filled'])
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
