package fixture.compose

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Compose's packed-ARGB `Color` factories, `Color(color: Long)` and `Color(color: Int)`, called from
 * Python under their bound overload spellings. `toArgb` is the judge: `0xFFFF0000` is opaque red,
 * `-65536` as a Kotlin `Int`, whichever factory built it.
 */
class ColorFactoryTest {

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

    @Test
    fun theLongAndIntColorFactoriesAreBoundAndBuildTheSameColor() {
        Python3.exec(
            """
            import python_multiplatform as _cf_pm
            import androidx.compose.ui.graphics as _cf_g
            from androidx.compose.ui.graphics import Color__Long, Color__Int, toArgb

            assert [(_p['name'], _p['type']) for _p in _cf_pm.describe(_cf_g, 'Color__Long')['parameters']] == \
                [('color', 'kotlin.Long')], _cf_pm.describe(_cf_g, 'Color__Long')
            assert [(_p['name'], _p['type']) for _p in _cf_pm.describe(_cf_g, 'Color__Int')['parameters']] == \
                [('color', 'kotlin.Int')], _cf_pm.describe(_cf_g, 'Color__Int')

            _cf_long = Color__Long(0xFFFF0000)
            _cf_int = Color__Int(-65536)
            assert type(_cf_long)._kotlin_type_name == 'androidx.compose.ui.graphics.Color', type(_cf_long)
            assert toArgb(_cf_long) == -65536, toArgb(_cf_long)
            assert toArgb(_cf_int) == -65536, toArgb(_cf_int)
            assert toArgb(Color__Long(0xFF00FF00)) == -16711936, 'green through the Long factory'
            # Keyword by the Kotlin parameter name.
            assert toArgb(Color__Long(color=0x800000FF)) == -2147483393
            """.trimIndent(),
        )
    }
}
