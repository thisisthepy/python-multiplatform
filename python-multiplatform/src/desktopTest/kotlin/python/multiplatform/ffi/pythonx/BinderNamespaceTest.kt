package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a Kotlin-named module offers to something that **discovers** it rather than knowing its
 * names in advance -- a completion list, an IDE, and pythonx-compose building its grouping rules.
 *
 * - Issue #35: a module's `dir()` lists its direct child packages and objects (Kotlin names), and
 *   reading one as an attribute imports it, so `androidx.compose.foundation.layout.Arrangement.End`
 *   works after nothing more than `import androidx`.
 * - Issue #36: `python_multiplatform.describe(module, name)` describes any bound name, a
 *   `STATIC_GETTER` included, **without reading it** -- reading a constant runs Kotlin, and a
 *   description must not.
 *
 * `ComposeShapedFragment` puts `Arrangement.Start`/`.End` under the package
 * `androidx.compose.foundation.layout.Arrangement`, which is exactly the shape the walker gives
 * `Alignment.End`: an object is a package whose members are `STATIC_GETTER`s.
 */
class BinderNamespaceTest {

    @BeforeTest
    fun install() {
        check(PythonTestFixture.available) {
            "CPython could not be initialized (${PythonTestFixture.failureReason})"
        }
        UpcallTable.install(listOf(ComposeShapedFragment))
        ComposeShapedFragment.calls.clear()
        assertTrue(bindUpcallOrNull(ComposeShapedFragment.EMPTY_MODIFIER), "the fixture table is not installed")
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    // ------------------------------------------------------------------------------- #35

    @Test
    fun dirOfAModuleListsItsDirectChildPackagesAndObjects() = withAdapterOnly {
        Python3.exec(
            """
            import androidx
            import androidx.compose.foundation.layout as _bn_layout
            import androidx.compose.ui as _bn_ui
            _bn = {
                'root': 'compose' in dir(androidx),
                'object': 'Arrangement' in dir(_bn_layout),
                'still_declarations': 'padding' in dir(_bn_layout) and 'fillMaxWidth' in dir(_bn_layout),
                'ui_children': sorted(_n for _n in ('draw', 'util') if _n in dir(_bn_ui)),
                'only_direct': 'layout' not in dir(androidx) and 'Arrangement' not in dir(androidx.compose),
                'no_snake': [_n for _n in dir(_bn_layout) if '_' in _n.strip('_') and '__' not in _n],
            }
            """.trimIndent(),
        )
        assertEquals("True", eval("_bn['root']"), "dir(androidx) does not list 'compose'")
        assertEquals("True", eval("_bn['object']"), "dir(androidx.compose.foundation.layout) does not list 'Arrangement'")
        assertEquals("True", eval("_bn['still_declarations']"))
        assertEquals("['draw', 'util']", eval("repr(_bn['ui_children'])"))
        assertEquals("True", eval("_bn['only_direct']"), "dir() must list direct children only")
        assertEquals("[]", eval("repr(_bn['no_snake'])"), "dir() must show Kotlin names only")
    }

    @Test
    fun aChildPackageOrObjectIsReachableAsAnAttributeWithoutAPriorImport() = withAdapterOnly {
        Python3.exec(
            """
            import androidx
            _bn_end = androidx.compose.foundation.layout.Arrangement.End
            from androidx.compose.foundation.layout import describeHorizontal
            _bn = {
                'end': describeHorizontal(_bn_end),
                'module': androidx.compose.foundation.layout.Arrangement.__name__,
            }
            try:
                androidx.compose.noSuchPackage
                _bn['unknown'] = 'resolved'
            except AttributeError:
                _bn['unknown'] = 'AttributeError'
            """.trimIndent(),
        )
        assertEquals("End", eval("_bn['end']"))
        assertEquals("androidx.compose.foundation.layout.Arrangement", eval("_bn['module']"))
        assertEquals("AttributeError", eval("_bn['unknown']"), "a name nothing is bound under must stay an AttributeError")
    }

    // ------------------------------------------------------------------------------- #36

    /** The issue's completion statement, in every install order, and with the getter never run. */
    @Test
    fun describeOfANamedConstantGivesItsDeclarationWithoutReadingIt() {
        for (order in listOf("adapter-only", "proxy-only", "adapter-then-proxy", "proxy-then-adapter")) {
            PythonTestFixture.withInterpreter {
                when (order) {
                    "adapter-only" -> { dropAndroidxModules(); installAdapter() }
                    "proxy-only" -> { dropAndroidxModules(); PythonProxySource.install() }
                    "adapter-then-proxy" -> { installAdapter(); PythonProxySource.install() }
                    "proxy-then-adapter" -> { PythonProxySource.install(); installAdapter() }
                }
                ComposeShapedFragment.calls.clear()
                val run = {
                    Python3.exec(
                        """
                        import importlib as _bn_importlib
                        import python_multiplatform as _bn_pm
                        _bn_arr = _bn_importlib.import_module('androidx.compose.foundation.layout.Arrangement')
                        _bn_d = _bn_pm.describe(_bn_arr, 'End')
                        _bn = {
                            'kind': _bn_d['kind'],
                            'returns': _bn_d['returns'],
                            'name': _bn_d['name'],
                            'parameters': _bn_d['parameters'],
                        }
                        """.trimIndent(),
                    )
                }
                if (order == "proxy-only") withoutBindingLayer(run) else run()
                assertEquals(emptyList(), ComposeShapedFragment.calls, "$order: describe() invoked the getter")
                assertEquals("STATIC_GETTER", eval("_bn['kind']"), order)
                assertEquals("androidx.compose.foundation.layout.Arrangement.Horizontal", eval("_bn['returns']"), order)
                assertEquals("androidx.compose.foundation.layout.Arrangement.End", eval("_bn['name']"), order)
                assertEquals("()", eval("repr(_bn['parameters'])"), order)
            }
        }
    }

    /** Any bound name, not only constants: one declaration is one dict, an overload set a tuple. */
    @Test
    fun describeByNameCoversFunctionsAndOverloadSetsAndLeavesTheOneArgumentFormAlone() = withAdapterOnly {
        Python3.exec(
            """
            import python_multiplatform as _bn_pm
            import androidx.compose.foundation.layout as _bn_layout
            _bn_one = _bn_pm.describe(_bn_layout, 'padding__Dp')
            _bn_set = _bn_pm.describe(_bn_layout, 'padding')
            _bn = {
                'one': (_bn_one['name'], _bn_one['kind'], [_p['name'] for _p in _bn_one['parameters']]),
                'set': sorted(_d['name'] for _d in _bn_set),
                'set_type': type(_bn_set).__name__,
                'same_as_fn': _bn_pm.describe(_bn_layout.padding__Dp) == (_bn_one,),
            }
            for _bn_bad in ('noSuchThing', 'Arrangement'):
                try:
                    _bn_pm.describe(_bn_layout, _bn_bad)
                    _bn[_bn_bad] = 'described'
                except AttributeError as _bn_e:
                    _bn[_bn_bad] = 'AttributeError'
            try:
                _bn_pm.describe(42)
                _bn['callable_rule'] = 'described'
            except TypeError:
                _bn['callable_rule'] = 'TypeError'
            """.trimIndent(),
        )
        assertEquals(
            "('androidx.compose.foundation.layout.padding__Dp', 'FUNCTION', ['all'])",
            eval("repr(_bn['one'])"),
        )
        assertEquals("tuple", eval("_bn['set_type']"))
        assertEquals(
            "['androidx.compose.foundation.layout.padding__Dp', 'androidx.compose.foundation.layout.padding__Dp_Dp', " +
                "'androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp', 'androidx.compose.foundation.layout.padding__PaddingValues']",
            eval("repr(_bn['set'])"),
        )
        assertEquals("True", eval("_bn['same_as_fn']"), "the one-argument form must answer the same rows")
        assertEquals("AttributeError", eval("_bn['noSuchThing']"))
        assertEquals("AttributeError", eval("_bn['Arrangement']"), "a child package is not a declaration")
        assertEquals("TypeError", eval("_bn['callable_rule']"), "the one-argument form still takes binder callables only")
    }

    // ------------------------------------------------------------------------------- helpers

    private fun installAdapter() {
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        Python3.exec(
            "import python_multiplatform.binding as _bn_b\n" +
                "_bn_b.register_empty('androidx.compose.ui.Modifier', '${ComposeShapedFragment.EMPTY_MODIFIER}')",
        )
    }

    /**
     * The binding layer on modules nobody has imported yet: an earlier test in this interpreter may
     * have imported (and so attached as attributes) the very children these tests must reach
     * without an import.
     */
    private inline fun withAdapterOnly(block: () -> Unit) = PythonTestFixture.withInterpreter {
        dropAndroidxModules()
        installAdapter()
        block()
    }

    private fun dropAndroidxModules() = Python3.exec(
        """
        import sys as _bn_sys
        for _bn_n in [_n for _n in _bn_sys.modules if _n == 'androidx' or _n.startswith('androidx.')]:
            del _bn_sys.modules[_bn_n]
        """.trimIndent(),
    )

    private inline fun withoutBindingLayer(block: () -> Unit) {
        Python3.exec(
            "import sys as _bn_sys\n" +
                "_bn_saved = _bn_sys.modules.pop('python_multiplatform.binding', None)",
        )
        try {
            block()
        } finally {
            Python3.exec(
                "if _bn_saved is not None:\n" +
                    "    _bn_sys.modules['python_multiplatform.binding'] = _bn_saved",
            )
        }
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
