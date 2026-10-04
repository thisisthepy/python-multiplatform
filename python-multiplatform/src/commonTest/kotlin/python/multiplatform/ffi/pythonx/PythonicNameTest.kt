package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.publishesProxyEntryPoints
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Issue #131: on a Kotlin-named module, a declaration, a proxy member and a keyword parameter are
 * reachable by their Kotlin names **and** by their Pythonic (snake_case) aliases -- and an alias is
 * served only when it is unambiguous ([PythonicNameShapedFragment] is built to collide).
 *
 * The policy under test (`KotlinSurface.kt`, `pythonic_aliases`):
 *
 * - an alias that is itself a Kotlin name of the namespace belongs to that Kotlin name;
 * - an alias two Kotlin names map to is not served, and both Kotlin names still work;
 * - `dir()` and a proxy's class dict list Kotlin names only;
 * - `inspect.signature` shows the Pythonic keyword, `describe` both spellings.
 *
 * Red before #131: every snake_case spelling below is an `AttributeError` or an "unexpected keyword"
 * `TypeError`, because `238119b7` removed the conversion.
 */
class PythonicNameTest {

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    /** `remember_text_field_state` is `rememberTextFieldState`; `to_url` is nobody's; `foo_bar` is `foo_bar`'s. */
    @Test
    fun aModuleAliasIsServedOnlyWhenItIsUnambiguous() = withBinding {
        Python3.exec(
            """
            import python_multiplatform as _pn_pm
            import pythonic.names as _pn
            _pn_r = {
                'same': _pn.remember_text_field_state is _pn.rememberTextFieldState,
                'snake_call': _pn.remember_text_field_state('a', max_length=3),
                'kotlin_call': _pn.rememberTextFieldState('c', maxLength=4),
                'mixed_call': _pn.rememberTextFieldState(initial_text='b'),
                'to_url': hasattr(_pn, 'to_url'),
                'to_urls': [_pn.toURL('x'), _pn.toUrl('x')],
                'foo_bar': _pn.foo_bar(),
                'fooBar': _pn.fooBar(),
                'dir_snake': [_n for _n in dir(_pn) if _n in ('remember_text_field_state', 'describe_box', 'to_url')],
                'described': _pn_pm.describe(_pn, 'remember_text_field_state')['name'],
            }
            """.trimIndent(),
        )
        assertEquals("True", eval("_pn_r['same']"), "an alias must be the Kotlin name's own object")
        assertEquals("state(a, max=3)", eval("_pn_r['snake_call']"))
        assertEquals("state(c, max=4)", eval("_pn_r['kotlin_call']"))
        assertEquals("state(b)", eval("_pn_r['mixed_call']"), "an omitted Kotlin default must stay omitted")
        assertEquals("False", eval("_pn_r['to_url']"), "two Kotlin names map to to_url: neither may own it")
        assertEquals("['toURL:x', 'toUrl:x']", eval("repr(_pn_r['to_urls'])"))
        assertEquals("foo_bar", eval("_pn_r['foo_bar']"), "the Kotlin name foo_bar owns its spelling")
        assertEquals("fooBar", eval("_pn_r['fooBar']"))
        assertEquals("[]", eval("repr(_pn_r['dir_snake'])"), "dir() lists Kotlin names only")
        assertEquals("pythonic.names.rememberTextFieldState", eval("_pn_r['described']"))
    }

    /** `on_click=` is the parameter Kotlin calls `on_click`; `x_url=` names neither of two parameters. */
    @Test
    fun aKeywordAliasIsServedOnlyWhenItIsUnambiguous() = withBinding {
        Python3.exec(
            """
            import inspect as _pn_inspect
            import python_multiplatform as _pn_pm
            import pythonic.names as _pn
            _pn_r = {
                'clicked': _pn.clicked(onClick=1, on_click=2),
                'clicked_pos': _pn.clicked(1, on_click=2),
                'clicked_sig': list(_pn_inspect.signature(_pn.clicked).parameters),
                'pair': _pn.pair(xURL=1, xUrl=2),
                'pair_sig': list(_pn_inspect.signature(_pn.pair).parameters),
                'remember_sig': [
                    (_p.name, _p.default is _pn_pm.KOTLIN_DEFAULT)
                    for _p in _pn_inspect.signature(_pn.rememberTextFieldState).parameters.values()
                ],
                'remember_described': [
                    (_q['name'], _q['python_name'])
                    for _q in _pn_pm.describe(_pn.rememberTextFieldState)[0]['parameters']
                ],
            }
            for _pn_label, _pn_call in (
                ('x_url', lambda: _pn.pair(1, x_url=2)),
                ('both', lambda: _pn.rememberTextFieldState('a', maxLength=1, max_length=2)),
            ):
                try:
                    _pn_call()
                    _pn_r[_pn_label] = 'accepted'
                except TypeError as _pn_e:
                    _pn_r[_pn_label] = 'TypeError'
            """.trimIndent(),
        )
        assertEquals("onClick=1, on_click=2", eval("_pn_r['clicked']"))
        assertEquals("onClick=1, on_click=2", eval("_pn_r['clicked_pos']"))
        assertEquals("['onClick', 'on_click']", eval("repr(_pn_r['clicked_sig'])"), "no alias may shadow a Kotlin name")
        assertEquals("xURL=1, xUrl=2", eval("_pn_r['pair']"))
        assertEquals("['xURL', 'xUrl']", eval("repr(_pn_r['pair_sig'])"))
        assertEquals("TypeError", eval("_pn_r['x_url']"), "x_url would name two parameters")
        assertEquals("TypeError", eval("_pn_r['both']"), "two spellings of one parameter are two values")
        assertEquals(
            "[('initial_text', False), ('max_length', True)]",
            eval("repr(_pn_r['remember_sig'])"),
            "inspect.signature shows the Pythonic keyword and keeps the Kotlin default",
        )
        assertEquals(
            "[('initialText', 'initial_text'), ('maxLength', 'max_length')]",
            eval("repr(_pn_r['remember_described'])"),
        )
    }

    /** `fill_max` is `fillMax`; `label_url` is nobody's; `selected_index` reads and writes the `var`. */
    @Test
    fun aMemberAliasIsServedOnlyWhenItIsUnambiguous() = withBinding {
        Python3.exec(
            """
            import python_multiplatform as _pn_pm
            from pythonic.names import box, describe_box
            _pn_box = box('b')
            _pn_r = {
                'fill': describe_box(_pn_box.fill_max(max_fraction=0.5).fillMax()),
                'label_url': hasattr(_pn_box, 'label_url'),
                'labels': describe_box(_pn_box.labelURL().labelUrl()),
                'member_described': [_d['name'] for _d in _pn_pm.describe_member('pythonic.names.Box', 'fill_max')],
            }
            _pn_box.selected_index = 4
            _pn_r['read_alias'] = _pn_box.selected_index
            _pn_r['read_kotlin'] = _pn_box.selectedIndex
            _pn_r['kotlin_side'] = describe_box(_pn_box)
            _pn_r['class_dict'] = [_n for _n in ('fill_max', 'selected_index') if _n in vars(type(_pn_box))]
            _pn_r['instance_dict'] = [_n for _n in ('selected_index',) if _n in _pn_box.__dict__]
            """.trimIndent(),
        )
        assertEquals("b -> fillMax(0.5) -> fillMax() [selected=0]", eval("_pn_r['fill']"))
        assertEquals("False", eval("_pn_r['label_url']"), "two members map to label_url: neither may own it")
        assertEquals("b -> labelURL -> labelUrl [selected=0]", eval("_pn_r['labels']"))
        assertEquals("['pythonic.names.ext.fillMax']", eval("repr(_pn_r['member_described'])"))
        assertEquals("4", eval("_pn_r['read_alias']"))
        assertEquals("4", eval("_pn_r['read_kotlin']"))
        assertEquals("b [selected=4]", eval("_pn_r['kotlin_side']"), "the alias write has to reach Kotlin's setter")
        assertEquals("[]", eval("repr(_pn_r['class_dict'])"), "an alias is never written onto the proxy class")
        assertEquals("[]", eval("repr(_pn_r['instance_dict'])"), "an alias write must not land in the instance dict")
    }

    /**
     * The proxy layer on its own (`PythonProxySource`, no binding layer): module aliases through the
     * PEP 562 `__getattr__` it gives the modules it creates, and keyword aliases through
     * `python_multiplatform.kotlin_function`. The collision policy is the same.
     */
    @Test
    fun theProxyLayerAloneServesTheSameAliases() = PythonTestFixture.withInterpreter {
        UpcallTable.install(listOf(PythonicNameShapedFragment.ProxyOnly))
        assertTrue(bindUpcallOrNull("pythonic.proxyonly.toURL"), "the fixture table is not installed")
        if (!publishesProxyEntryPoints) {
            assertFails { PythonProxySource.install() }
            return@withInterpreter
        }
        Python3.exec(
            """
            import sys as _pn_sys
            _pn_saved = _pn_sys.modules.pop('python_multiplatform.binding', None)
            for _pn_n in [_n for _n in _pn_sys.modules if _n == 'pythonic.proxyonly' or _n.startswith('pythonic.proxyonly.')]:
                del _pn_sys.modules[_pn_n]
            """.trimIndent(),
        )
        try {
            PythonProxySource.install()
            Python3.exec(
                """
                import pythonic.proxyonly as _pn
                _pn_r = {
                    'same': _pn.remember_text_field_state is _pn.rememberTextFieldState,
                    'snake_call': _pn.remember_text_field_state('a', max_length=3),
                    'kotlin_kw': _pn.rememberTextFieldState(initialText='b'),
                    'to_url': hasattr(_pn, 'to_url'),
                    'cached': 'remember_text_field_state' in vars(_pn),
                }
                """.trimIndent(),
            )
        } finally {
            Python3.exec(
                "if _pn_saved is not None:\n" +
                    "    _pn_sys.modules['python_multiplatform.binding'] = _pn_saved",
            )
        }
        assertEquals("True", eval("_pn_r['same']"))
        assertEquals("state(a, max=3)", eval("_pn_r['snake_call']"))
        assertEquals("state(b)", eval("_pn_r['kotlin_kw']"))
        assertEquals("False", eval("_pn_r['to_url']"))
        assertEquals("False", eval("_pn_r['cached']"), "the proxy layer reads an alias through the Kotlin name every time")
    }

    /** Installs [PythonicNameShapedFragment] and the binding layer, as `PythonxAdapterTest.withAdapter` does. */
    private inline fun withBinding(block: () -> Unit) = PythonTestFixture.withInterpreter {
        UpcallTable.install(listOf(PythonicNameShapedFragment))
        assertTrue(bindUpcallOrNull("${PythonicNameShapedFragment.PACKAGE}.box"), "the fixture table is not installed")
        if (!publishesProxyEntryPoints) {
            val refusal = assertFails { PythonxAdapter.install() }
            assertTrue(
                refusal.message?.contains("raw upcall entry points are not bound") == true,
                "a target with no proxy bootstrap must fail the adapter's own guard: $refusal",
            )
            return@withInterpreter
        }
        PythonxAdapter.install()
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
