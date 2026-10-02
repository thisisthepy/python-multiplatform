package fixture.compose

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The signature metadata a Pythonic layer (pythonx-compose) reads off the binder, on real Compose.
 *
 * pythonx-compose re-exposes Kotlin-named modules Pythonically by **one rule** applied to every
 * module it lists, with no per-widget wrapper. That rule needs, for each function: its Kotlin
 * parameter names, which have defaults, the overload set behind a base name, which parameters are
 * value classes, and which one is the trailing `@Composable` content lambda. This pins that the
 * binder publishes exactly that -- through `inspect.signature` and `python_multiplatform.describe`
 * (`KotlinSurface`'s KDoc is the contract) -- for a real composable (`Checkbox`) and a real
 * overload set (`padding`), whichever installer put the name there.
 */
class KotlinSignatureMetadataTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /** `Checkbox(checked, onCheckedChange, modifier = Modifier, ...)`, as Kotlin declares it. */
    @Test
    fun checkboxCarriesItsKotlinParameterNamesAndMarksItsDefaults() {
        for (order in listOf("adapter", "adapter-then-proxy", "proxy-then-adapter")) {
            when (order) {
                "adapter" -> PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
                "adapter-then-proxy" -> { PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES); PythonProxySource.install() }
                "proxy-then-adapter" -> { PythonProxySource.install(); PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES) }
            }
            Python3.exec(
                """
                import inspect as _ksm_inspect
                import python_multiplatform as _ksm_pm
                from androidx.compose.material3 import Checkbox as _ksm_checkbox
                _ksm_params = list(_ksm_inspect.signature(_ksm_checkbox).parameters.values())
                _ksm_d = _ksm_pm.describe(_ksm_checkbox)
                _ksm = {
                    'first': [
                        (_p.name, _p.default is _ksm_pm.KOTLIN_DEFAULT, _p.annotation)
                        for _p in _ksm_params[:3]
                    ],
                    'synthetic': [_p.name for _p in _ksm_params if not _p.name.isidentifier()],
                    'count': len(_ksm_d),
                    'name': _ksm_d[0]['name'],
                    'composable': _ksm_d[0]['composable'],
                    'callback': _ksm_d[0]['parameters'][1]['type'].startswith('kotlin.Function1'),
                    'described': [_q['name'] for _q in _ksm_d[0]['parameters']] ==
                                 [_p.name for _p in _ksm_params],
                }
                """.trimIndent(),
            )
            assertEquals(
                "[('checked', False, 'kotlin.Boolean'), ('onCheckedChange', False, " +
                    "${eval("repr(_ksm_d[0]['parameters'][1]['type'])")}), " +
                    "('modifier', True, 'androidx.compose.ui.Modifier')]",
                eval("repr(_ksm['first'])"),
                order,
            )
            assertEquals("[]", eval("repr(_ksm['synthetic'])"), "$order: \$composer/\$changed/\$default must not appear")
            assertEquals("1", eval("_ksm['count']"), order)
            assertEquals("androidx.compose.material3.Checkbox", eval("_ksm['name']"), order)
            assertEquals("True", eval("_ksm['composable']"), order)
            assertEquals("True", eval("_ksm['callback']"), order)
            assertEquals("True", eval("_ksm['described']"), "$order: describe and inspect.signature must agree")
        }
    }

    /** The overload set behind `padding`, with each candidate's value-class parameters marked. */
    @Test
    fun paddingDescribesItsWholeOverloadSet() {
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
        Python3.exec(
            """
            import inspect as _ksm_inspect
            import python_multiplatform as _ksm_pm
            import androidx.compose.foundation.layout as _ksm_layout
            _ksm_d = _ksm_pm.describe(_ksm_layout.padding)
            _ksm = {
                'names': sorted(_d['name'].rpartition('.')[2] for _d in _ksm_d),
                'receivers': sorted(set(_d['receiver'] for _d in _ksm_d)),
                'dp_value_class': all(
                    _q['value_class'] for _d in _ksm_d for _q in _d['parameters']
                    if _q['type'] == 'androidx.compose.ui.unit.Dp'
                ),
                'generic': str(_ksm_inspect.signature(_ksm_layout.padding)),
                'one': str(_ksm_inspect.signature(_ksm_layout.padding__Dp_Dp)),
            }
            """.trimIndent(),
        )
        val names = eval("repr(_ksm['names'])")
        assertTrue("'padding__Dp'" in names && "'padding__Dp_Dp'" in names && "'padding__Dp_Dp_Dp_Dp'" in names, names)
        assertEquals("['androidx.compose.ui.Modifier']", eval("repr(_ksm['receivers'])"))
        assertEquals("True", eval("_ksm['dp_value_class']"))
        assertEquals("(*args, **kwargs)", eval("_ksm['generic']"), "an overload set has no single signature")
        assertEquals(
            "(receiver: 'androidx.compose.ui.Modifier', /, " +
                "horizontal: 'androidx.compose.ui.unit.Dp' = <Kotlin default>, " +
                "vertical: 'androidx.compose.ui.unit.Dp' = <Kotlin default>) -> 'androidx.compose.ui.Modifier'",
            eval("_ksm['one']"),
        )
    }

    private fun eval(expression: String): String =
        Python3.import("__main__").getAttr("__dict__").let { globals ->
            Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
        }

    private companion object {
        /** CPython's `Py_eval_input`, as `RetainedSlotSweepPreconditionTest` spells it. */
        const val PY_EVAL_INPUT = 258
    }
}
