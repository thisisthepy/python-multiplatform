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
 * The binder's surface on a Kotlin-named module is **Kotlin's own surface**, and it does not occupy
 * any name a Pythonic package owns.
 *
 * `docs/INTENT.md` §2.2 and §2.3, as executable statements:
 *
 * - A Kotlin declaration is reached under its Kotlin name, takes keyword arguments by its **Kotlin
 *   parameter names** and leaves an omitted defaulted parameter to Kotlin's own default. Nothing is
 *   snake_cased: `fillMaxWidth` is `fillMaxWidth`, and `fill_max_width` does not exist.
 * - `pythonx` is a real package in pythonx-compose. The binder neither creates a `pythonx` module
 *   nor prevents one on disk from loading.
 * - Every function the binder puts on a Kotlin-named module carries its signature as public metadata
 *   (`inspect.signature`, and `python_multiplatform.describe`) so a Pythonic layer can build on it
 *   by rule rather than by a wrapper per declaration.
 *
 * The same answers are required whichever of the two installers ran, and in either order: one owner
 * per name, no order dependence.
 *
 * Desktop-only because the on-disk package test writes a directory, and the test working directory
 * is the module directory only here (`build/tmp/...` keeps it inside the repository).
 */
class KotlinNamedSurfaceTest {

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

    /**
     * Both installers, then `import pythonx`: the package on disk is what loads.
     *
     * Before this, the adapter put a `ModuleType('pythonx')` with `__path__ = []` into `sys.modules`,
     * and CPython answers an import from `sys.modules` before it looks at `sys.path` -- so the real
     * package's `__init__.py` could never run.
     */
    @Test
    fun aRealPythonxPackageOnDiskIsWhatImportPythonxLoads() = PythonTestFixture.withInterpreter {
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        PythonProxySource.install()
        Python3.exec(
            """
            import os as _kns_os, sys as _kns_sys
            _kns = {
                'occupied': sorted(
                    _n for _n in _kns_sys.modules if _n == 'pythonx' or _n.startswith('pythonx.')
                ),
            }
            _kns_root = _kns_os.path.abspath(
                _kns_os.path.join('build', 'tmp', 'kotlin-named-surface', str(_kns_os.getpid()))
            )
            _kns_os.makedirs(_kns_os.path.join(_kns_root, 'pythonx'), exist_ok=True)
            with open(_kns_os.path.join(_kns_root, 'pythonx', '__init__.py'), 'w') as _kns_f:
                _kns_f.write("MARK = 'on-disk'\n")
            _kns_sys.path.insert(0, _kns_root)
            try:
                import pythonx as _kns_px
                _kns['mark'] = getattr(_kns_px, 'MARK', None)
                _kns['file'] = getattr(_kns_px, '__file__', None) or ''
                _kns['from_disk'] = _kns['file'].startswith(_kns_root)
            finally:
                _kns_sys.path.remove(_kns_root)
                if getattr(_kns_sys.modules.get('pythonx'), 'MARK', None) == 'on-disk':
                    del _kns_sys.modules['pythonx']
                import shutil as _kns_shutil
                _kns_shutil.rmtree(_kns_root, ignore_errors=True)
            """.trimIndent(),
        )
        assertEquals("[]", eval("repr(_kns['occupied'])"), "the binder must not create any pythonx module")
        assertEquals("on-disk", eval("_kns['mark']"), "import pythonx must load the package on disk")
        assertEquals("True", eval("_kns['from_disk']"), eval("_kns['file']"))
    }

    /** Kotlin parameter names as keywords, Kotlin defaults for what is omitted -- proxy installed alone. */
    @Test
    fun theProxyRenderedFunctionTakesKotlinKeywordsAndDefaults() = PythonTestFixture.withInterpreter {
        PythonProxySource.install()
        // The adaptation layer may be left in `sys.modules` by an earlier test in this interpreter;
        // set aside so this measures the proxy-rendered function on its own.
        withoutBindingLayer {
            Python3.exec(
                """
                from androidx.compose.foundation.layout import padding__Dp_Dp, padding__Dp_Dp_Dp_Dp
                from androidx.compose.ui import emptyModifier, describeModifier
                _kns = {
                    'vertical': describeModifier(padding__Dp_Dp(emptyModifier(), vertical=4.0)),
                    'edges': describeModifier(padding__Dp_Dp_Dp_Dp(emptyModifier(), start=1.0, end=3.0)),
                }
                try:
                    padding__Dp_Dp(emptyModifier(), horizontal_padding=1.0)
                    _kns['unknown'] = 'accepted'
                except TypeError as _kns_e:
                    _kns['unknown'] = str(_kns_e)
                """.trimIndent(),
            )
        }
        assertEquals("padding(v=4.0)", eval("_kns['vertical']"))
        assertEquals("padding(s=1.0, e=3.0)", eval("_kns['edges']"))
        assertTrue("horizontal_padding" in eval("_kns['unknown']"), eval("_kns['unknown']"))
    }

    /** No snake_case anywhere: not as a member, not as a keyword. */
    @Test
    fun aKotlinNameIsNotSnakeCased() = withBothInstalled {
        Python3.exec(
            """
            import androidx.compose.foundation.layout as _kns_layout
            from androidx.compose.ui import emptyModifier, describeModifier
            _kns = {'kotlin': describeModifier(_kns_layout.fillMaxWidth(emptyModifier()))}
            try:
                _kns_layout.fill_max_width
                _kns['snake_member'] = 'resolved'
            except AttributeError:
                _kns['snake_member'] = 'AttributeError'
            try:
                _kns_layout.padding__Dp_Dp(emptyModifier(), vertical=1.0)
                _kns['kotlin_keyword'] = 'accepted'
            except TypeError as _kns_e:
                _kns['kotlin_keyword'] = str(_kns_e)
            try:
                _kns_layout.padding__PaddingValues(emptyModifier(), padding_values=None)
                _kns['snake_keyword'] = 'accepted'
            except TypeError as _kns_e:
                _kns['snake_keyword'] = 'TypeError'
            import python_multiplatform.binding as _kns_binding
            _kns['renamers'] = [
                _n for _n in ('to_python_name', 'to_kotlin_name') if hasattr(_kns_binding, _n)
            ]
            """.trimIndent(),
        )
        assertEquals("fillMaxWidth", eval("_kns['kotlin']"))
        assertEquals("AttributeError", eval("_kns['snake_member']"))
        assertEquals("accepted", eval("_kns['kotlin_keyword']"))
        assertEquals("TypeError", eval("_kns['snake_keyword']"))
        assertEquals("[]", eval("repr(_kns['renamers'])"))
    }

    /**
     * The public metadata contract a Pythonic layer builds on: `inspect.signature` shows the Kotlin
     * parameter names with defaults marked, and `python_multiplatform.describe` gives the overload
     * set with per-parameter Kotlin types.
     *
     * Asserted for the proxy-rendered function and for the binding layer's own, in both install
     * orders, so the answer cannot depend on who got there first.
     */
    @Test
    fun aFunctionCarriesItsKotlinSignatureWhoeverInstalledIt() {
        for (order in listOf("proxy-only", "adapter-then-proxy", "proxy-then-adapter", "adapter-only")) {
            PythonTestFixture.withInterpreter {
                when (order) {
                    "proxy-only" -> PythonProxySource.install()
                    "adapter-then-proxy" -> { installAdapter(); PythonProxySource.install() }
                    "proxy-then-adapter" -> { PythonProxySource.install(); installAdapter() }
                    "adapter-only" -> { dropRenderedLayout(); installAdapter() }
                }
                val run = {
                    Python3.exec(
                        """
                        import inspect as _kns_inspect
                        import python_multiplatform as _kns_pm
                        import androidx.compose.foundation.layout as _kns_layout
                        _kns_sig = _kns_inspect.signature(_kns_layout.padding__Dp_Dp)
                        _kns = {
                            'params': [
                                (_p.name, _p.kind.name, _p.default is _kns_pm.KOTLIN_DEFAULT)
                                for _p in _kns_sig.parameters.values()
                            ],
                            'annotation': _kns_sig.parameters['vertical'].annotation,
                            'one': [
                                (_d['name'], [(_q['name'], _q['type'], _q['has_default']) for _q in _d['parameters']], _d['receiver'])
                                for _d in _kns_pm.describe(_kns_layout.padding__Dp_Dp)
                            ],
                        }
                        """.trimIndent(),
                    )
                }
                if (order == "proxy-only") withoutBindingLayer(run) else run()
                assertEquals(
                    "[('receiver', 'POSITIONAL_ONLY', False), ('horizontal', 'POSITIONAL_OR_KEYWORD', True), " +
                        "('vertical', 'POSITIONAL_OR_KEYWORD', True)]",
                    eval("repr(_kns['params'])"),
                    order,
                )
                assertEquals("androidx.compose.ui.unit.Dp", eval("_kns['annotation']"), order)
                assertEquals(
                    "[('androidx.compose.foundation.layout.padding__Dp_Dp', " +
                        "[('horizontal', 'androidx.compose.ui.unit.Dp', True), ('vertical', 'androidx.compose.ui.unit.Dp', True)], " +
                        "'androidx.compose.ui.Modifier')]",
                    eval("repr(_kns['one'])"),
                    order,
                )
            }
        }
    }

    /** The overload set a base name stands for, from the binding layer that dispatches it. */
    @Test
    fun anOverloadSetDescribesEveryCandidate() = withBothInstalled {
        Python3.exec(
            """
            import python_multiplatform as _kns_pm
            import androidx.compose.foundation.layout as _kns_layout
            _kns = {'names': sorted(_d['name'] for _d in _kns_pm.describe(_kns_layout.padding))}
            """.trimIndent(),
        )
        assertEquals(
            "['androidx.compose.foundation.layout.padding__Dp', " +
                "'androidx.compose.foundation.layout.padding__Dp_Dp', " +
                "'androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp', " +
                "'androidx.compose.foundation.layout.padding__PaddingValues']",
            eval("repr(_kns['names'])"),
        )
    }

    // ------------------------------------------------------------------------------- helpers

    private fun installAdapter() {
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        Python3.exec(
            "import python_multiplatform.binding as _kns_b\n" +
                "_kns_b.register_empty('androidx.compose.ui.Modifier', '${ComposeShapedFragment.EMPTY_MODIFIER}')",
        )
    }

    private inline fun withBothInstalled(block: () -> Unit) = PythonTestFixture.withInterpreter {
        installAdapter()
        PythonProxySource.install()
        block()
    }

    /** Forget what an earlier `PythonProxySource.install()` wrote into the layout module. */
    private fun dropRenderedLayout() = Python3.exec(
        """
        import sys as _kns_sys
        for _kns_n in [_n for _n in _kns_sys.modules if _n.startswith('androidx.')]:
            del _kns_sys.modules[_kns_n]
        _kns_sys.modules.pop('androidx', None)
        """.trimIndent(),
    )

    private inline fun withoutBindingLayer(block: () -> Unit) {
        Python3.exec(
            "import sys as _kns_sys\n" +
                "_kns_saved = _kns_sys.modules.pop('python_multiplatform.binding', None)",
        )
        try {
            block()
        } finally {
            Python3.exec(
                "if _kns_saved is not None:\n" +
                    "    _kns_sys.modules['python_multiplatform.binding'] = _kns_saved",
            )
        }
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
