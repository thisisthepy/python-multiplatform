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
 * - A Kotlin package is a module under its Kotlin name and nothing else: no namespace is renamed.
 * - Inside it, a Kotlin declaration is reached under its Kotlin name **and** its Pythonic alias
 *   (`fillMaxWidth` / `fill_max_width`, issue #131), takes keyword arguments by either spelling of a
 *   parameter, and leaves an omitted defaulted parameter to Kotlin's own default.
 * - `pythonx` is a real package in pythonx-compose. The binder neither creates a `pythonx` module
 *   nor prevents one on disk from loading -- and such a package can build on the aliases.
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

    /**
     * Issue #131: a Kotlin name and its Pythonic alias are one declaration, and a keyword is accepted
     * by either spelling -- whichever installer ran. (`238119b7` had made the snake_case member an
     * `AttributeError` and the snake_case keyword a `TypeError`; this is the reversal of exactly
     * those two assertions.)
     */
    @Test
    fun aKotlinNameAndItsPythonicAliasAreOneDeclaration() = withBothInstalled {
        Python3.exec(
            """
            import python_multiplatform as _kns_pm
            import androidx.compose.foundation.layout as _kns_layout
            from androidx.compose.ui import emptyModifier, describeModifier, empty_modifier, describe_modifier
            _kns = {
                'kotlin': describeModifier(_kns_layout.fillMaxWidth(emptyModifier())),
                'snake': describe_modifier(_kns_layout.fill_max_width(empty_modifier())),
                'same': _kns_layout.fill_max_width is _kns_layout.fillMaxWidth,
                'kotlin_keyword': describeModifier(_kns_layout.padding__Dp_Dp(emptyModifier(), vertical=1.0)),
                'snake_keyword': describeModifier(_kns_layout.padding__PaddingValues(
                    emptyModifier(), padding_values=_kns_layout.padding_values_of(2))),
                'kotlin_keyword_pv': describeModifier(_kns_layout.padding__PaddingValues(
                    emptyModifier(), paddingValues=_kns_layout.paddingValuesOf(3))),
                'rule': _kns_pm.python_name('rememberTextFieldState'),
                'dir': [_n for _n in dir(_kns_layout) if _n in ('fill_max_width', 'padding_values_of')],
            }
            """.trimIndent(),
        )
        assertEquals("fillMaxWidth", eval("_kns['kotlin']"))
        assertEquals("fillMaxWidth", eval("_kns['snake']"))
        assertEquals("True", eval("_kns['same']"))
        assertEquals("padding(v=1.0)", eval("_kns['kotlin_keyword']"))
        assertEquals("padding(pv(2.0))", eval("_kns['snake_keyword']"))
        assertEquals("padding(pv(3.0))", eval("_kns['kotlin_keyword_pv']"))
        assertEquals("remember_text_field_state", eval("_kns['rule']"))
        assertEquals("[]", eval("repr(_kns['dir'])"), "dir() lists each declaration once, by its Kotlin name")
    }

    /**
     * AGENTS.md §12.2 with the aliases in place: a real `pythonx` package on disk loads, and its own
     * code reaches the Kotlin-named module by the Pythonic names -- the namespace it imports is
     * `androidx.*`, untouched, and the binder has made no `pythonx` module of its own.
     */
    @Test
    fun aRealPythonxPackageOnDiskBuildsOnThePythonicNames() = PythonTestFixture.withInterpreter {
        installAdapter()
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
                _kns_os.path.join('build', 'tmp', 'kotlin-named-surface-pythonic', str(_kns_os.getpid()))
            )
            _kns_os.makedirs(_kns_os.path.join(_kns_root, 'pythonx'), exist_ok=True)
            with open(_kns_os.path.join(_kns_root, 'pythonx', '__init__.py'), 'w') as _kns_f:
                _kns_f.write(
                    "from androidx.compose.foundation.layout import fill_max_width\n"
                    "from androidx.compose.ui import empty_modifier, describe_modifier\n"
                    "MARK = 'on-disk'\n"
                    "def full_width():\n"
                    "    return describe_modifier(fill_max_width(empty_modifier()))\n"
                )
            _kns_sys.path.insert(0, _kns_root)
            try:
                import pythonx as _kns_px
                _kns['mark'] = getattr(_kns_px, 'MARK', None)
                _kns['from_disk'] = (getattr(_kns_px, '__file__', None) or '').startswith(_kns_root)
                _kns['works'] = _kns_px.full_width()
            finally:
                _kns_sys.path.remove(_kns_root)
                if getattr(_kns_sys.modules.get('pythonx'), 'MARK', None) == 'on-disk':
                    del _kns_sys.modules['pythonx']
                import shutil as _kns_shutil
                _kns_shutil.rmtree(_kns_root, ignore_errors=True)
            """.trimIndent(),
        )
        assertEquals("[]", eval("repr(_kns['occupied'])"), "the binder must not create any pythonx module")
        assertEquals("on-disk", eval("_kns['mark']"))
        assertEquals("True", eval("_kns['from_disk']"))
        assertEquals("fillMaxWidth", eval("_kns['works']"))
    }

    /**
     * The public metadata contract a Pythonic layer builds on: `inspect.signature` shows each
     * parameter's Pythonic keyword (the Kotlin name where it has no alias) with defaults marked, and
     * `python_multiplatform.describe` gives the overload set with per-parameter Kotlin names, Pythonic
     * names and Kotlin types.
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
                            'pythonic': list(_kns_inspect.signature(_kns_layout.padding__PaddingValues).parameters),
                            'both_names': [
                                (_q['name'], _q['python_name'])
                                for _q in _kns_pm.describe(_kns_layout.padding__PaddingValues)[0]['parameters']
                            ],
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
                // Issue #131: the signature shows the Pythonic keyword, `describe` both spellings.
                assertEquals("['receiver', 'padding_values']", eval("repr(_kns['pythonic'])"), order)
                assertEquals("[('paddingValues', 'padding_values')]", eval("repr(_kns['both_names'])"), order)
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
