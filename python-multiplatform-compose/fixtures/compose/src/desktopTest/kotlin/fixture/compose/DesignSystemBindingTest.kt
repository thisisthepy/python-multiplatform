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
 * SPEC B-12 (issue #408): the fork's design systems (`org.thisisthepy.compose.designsystem:*`) are
 * walked, each under its own Kotlin package, so pythonx-compose's `pythonx.compose.material3` (the
 * design system in front of `androidx.compose.material3`) and `pythonx.compose.<name>` resolve.
 *
 * Each package must answer with the declaration that names the system; `materialDesignSystem` and
 * `MaterialButton` are checked parameter by parameter, by their Kotlin names, because pythonx-compose
 * calls them. Without the dependency or the walked package, the import fails.
 */
class DesignSystemBindingTest {

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
    fun everyDesignSystemPackageIsBoundUnderItsKotlinName() {
        Python3.exec(
            """
            import importlib as _ds_il
            _ds_expected = {
                'org.thisisthepy.compose.designsystem': 'LocalDesignSystem',
                'org.thisisthepy.compose.material3': 'Material3DesignSystem',
                'org.thisisthepy.compose.breeze': 'BreezeDesignSystem',
                'org.thisisthepy.compose.cupertino': 'CupertinoDesignSystem',
                'org.thisisthepy.compose.deepin': 'DeepinDesignSystem',
                'org.thisisthepy.compose.fluent': 'FluentDesignSystem',
                'org.thisisthepy.compose.gnome': 'GnomeDesignSystem',
                'org.thisisthepy.compose.liquidglass': 'LiquidGlass',
            }
            _ds_missing = {}
            for _ds_package, _ds_name in _ds_expected.items():
                _ds_module = _ds_il.import_module(_ds_package)
                if _ds_name not in dir(_ds_module):
                    _ds_missing[_ds_package] = sorted(n for n in dir(_ds_module) if not n.startswith('_'))[:20]
            assert not _ds_missing, _ds_missing
            """.trimIndent(),
        )
    }

    @Test
    fun theMaterialDesignSystemFunctionsCarryTheirKotlinParameters() {
        Python3.exec(
            """
            import python_multiplatform as _ds_pm
            import org.thisisthepy.compose.material3 as _ds_m3

            _ds_d = _ds_pm.describe(_ds_m3, 'materialDesignSystem')
            assert [(_p['name'], _p['type']) for _p in _ds_d['parameters']] == \
                [('isDark', 'kotlin.Boolean')], _ds_d

            _ds_b = _ds_pm.describe(_ds_m3, 'MaterialButton')
            assert [_p['name'] for _p in _ds_b['parameters']] == \
                ['variant', 'onClick', 'modifier', 'enabled', 'content'], _ds_b
            assert _ds_b['parameters'][0]['type'] == 'org.thisisthepy.compose.designsystem.ButtonVariant', _ds_b
            """.trimIndent(),
        )
    }
}
