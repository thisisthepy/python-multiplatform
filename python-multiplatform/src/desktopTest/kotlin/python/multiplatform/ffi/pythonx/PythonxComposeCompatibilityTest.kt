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
 * pythonx-compose 0.1.0a1 against the binder after issue #131.
 *
 * pythonx-compose ships its own name-conversion layer (`pythonx/compose/_reexport.py`) and will drop
 * it once the binder converts names itself. Until it does, the two run together, and this pins that
 * they do not fight:
 *
 * - its member resolver (`member_name`) is asked **before** the binder's own alias, so on a proxy it
 *   still answers every snake_case name it answered before, with the same Kotlin member;
 * - its keyword maps (`{snake: kotlinParam}`) reach the binder as Kotlin names, which the binder takes
 *   as it always did -- no double conversion, and writing both spellings is still "two values";
 * - its `_name_table(kotlin_module)` converts `dir()` by the same rule and **raises** when two entries
 *   map to one name, so the binder's `dir()` must list each declaration once, by its Kotlin name. A
 *   `dir()` that also listed `fill_max_width` would make every pythonx module fail;
 * - its `PythonicFunction` snake_cases `inspect.signature`'s names, which is a no-op on names the
 *   binder already shows snake_cased.
 *
 * The functions below are copied from pythonx-compose 0.1.0a1 (`_reexport.py`: `snake_case`,
 * `python_name`, `_method_keywords`, `member_name`, `_name_table`, `PythonicFunction`), trimmed of
 * docstrings and of the manifest, which this test does not need. They are a pin of that release's
 * behaviour, not a dependency: the file lives in another repository.
 */
class PythonxComposeCompatibilityTest {

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

    @Test
    fun theReleasedResolverStillDecidesAProxyNameAndItsKeywords() = withPythonx {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier, describeModifier
            from androidx.compose.foundation.layout import paddingValuesOf
            _pxc_calls.clear()
            _pxc = {
                'chain': describeModifier(Modifier.padding(16).fill_max_width().z_index(2)),
                'asked': sorted(set(_c[1] for _c in _pxc_calls)),
                'keyword': describeModifier(Modifier.padding(1).padding(padding_values=paddingValuesOf(4))),
                'kotlin_keyword': describeModifier(Modifier.padding(1).padding(paddingValues=paddingValuesOf(5))),
            }
            try:
                Modifier.padding(1).padding(padding_values=paddingValuesOf(4), paddingValues=paddingValuesOf(4))
                _pxc['both'] = 'accepted'
            except TypeError:
                _pxc['both'] = 'TypeError'
            """.trimIndent(),
        )
        assertEquals("padding(16.0) -> fillMaxWidth -> zIndex(2.0)", eval("_pxc['chain']"))
        val asked = eval("repr(_pxc['asked'])")
        assertTrue("'fill_max_width'" in asked && "'z_index'" in asked, "the released resolver was not asked first: $asked")
        assertEquals("padding(1.0) -> padding(pv(4.0))", eval("_pxc['keyword']"))
        assertEquals("padding(1.0) -> padding(pv(5.0))", eval("_pxc['kotlin_keyword']"))
        assertEquals("TypeError", eval("_pxc['both']"))
    }

    @Test
    fun theReleasedNameTableStillReadsTheBindersDirWithoutACollision() = withPythonx {
        Python3.exec(
            """
            import inspect as _pxc_inspect
            import androidx.compose.foundation.layout as _pxc_layout
            import androidx.compose.ui as _pxc_ui
            _pxc = {}
            try:
                _pxc_table = _pxc_name_table(_pxc_layout)
                _pxc['table'] = sorted((_k, _v) for _k, _v in _pxc_table.items() if _k != _v)
                _pxc_name_table(_pxc_ui)
                _pxc['raised'] = None
            except AttributeError as _pxc_e:
                _pxc['raised'] = str(_pxc_e)
            _pxc_fn = _PxcPythonicFunction(_pxc_layout.padding__PaddingValues, 'padding__PaddingValues')
            _pxc['signature'] = list(_pxc_inspect.signature(_pxc_fn).parameters)
            _pxc['call'] = _pxc_ui.describeModifier(
                _pxc_fn(_pxc_ui.emptyModifier(), padding_values=_pxc_layout.paddingValuesOf(6)))
            """.trimIndent(),
        )
        assertEquals("None", eval("repr(_pxc['raised'])"), "pythonx's _name_table must not see two names for one declaration")
        assertTrue("('fill_max_width', 'fillMaxWidth')" in eval("repr(_pxc['table'])"), eval("repr(_pxc['table'])"))
        assertEquals("['receiver', 'padding_values']", eval("repr(_pxc['signature'])"))
        assertEquals("padding(pv(6.0))", eval("_pxc['call']"))
    }

    /** Installs both binder layers and registers pythonx-compose 0.1.0a1's resolver, recording its questions. */
    private inline fun withPythonx(block: () -> Unit) = PythonTestFixture.withInterpreter {
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        Python3.exec(
            "import python_multiplatform.binding as _pm_binding\n" +
                "_pm_binding.register_empty('androidx.compose.ui.Modifier', '${ComposeShapedFragment.EMPTY_MODIFIER}')",
        )
        PythonProxySource.install()
        Python3.exec(RELEASED_REEXPORT)
        try {
            block()
        } finally {
            Python3.exec("_pm_binding.remove_member_resolver(_pxc_member_name)")
        }
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()

    private companion object {
        /** pythonx-compose 0.1.0a1's rule and resolver, as released; see the class KDoc. */
        val RELEASED_REEXPORT = """
            import inspect as _pxc_inspect_mod
            import re as _pxc_re
            import sys as _pxc_sys
            import python_multiplatform.binding as _pm_binding

            _PXC_LOWER_UPPER = _pxc_re.compile(r"([a-z0-9])([A-Z])")
            _PXC_ACRONYM_WORD = _pxc_re.compile(r"([A-Z]+)([A-Z][a-z])")

            def _pxc_snake_case(kotlin_name):
                return _PXC_LOWER_UPPER.sub(r"\1_\2", _PXC_ACRONYM_WORD.sub(r"\1_\2", kotlin_name)).lower()

            def _pxc_python_name(kotlin_name):
                if kotlin_name[:1].isupper():
                    return kotlin_name
                base, sep, suffix = kotlin_name.partition("__")
                return _pxc_snake_case(base) + sep + suffix

            def _pxc_method_keywords(kotlin_type_name, kotlin_member_name):
                describe_member = getattr(_pxc_sys.modules.get("python_multiplatform"), "describe_member", None)
                if describe_member is None:
                    return {}
                keywords = {}
                try:
                    declarations = describe_member(kotlin_type_name, kotlin_member_name)
                except (TypeError, AttributeError):
                    declarations = ()
                for declaration in declarations:
                    for parameter in declaration.get("parameters", ()):
                        kotlin_parameter = parameter.get("name")
                        if kotlin_parameter and _pxc_snake_case(kotlin_parameter) != kotlin_parameter:
                            keywords.setdefault(_pxc_snake_case(kotlin_parameter), kotlin_parameter)
                return keywords

            _pxc_calls = []

            def _pxc_member_name(kotlin_type_name, requested, kotlin_member_names):
                _pxc_calls.append((kotlin_type_name, requested))
                if requested in kotlin_member_names:
                    target = requested
                else:
                    matches = [name for name in kotlin_member_names if _pxc_python_name(name) == requested]
                    target = matches[0] if len(matches) == 1 else None
                if target is None:
                    return None
                keywords = _pxc_method_keywords(kotlin_type_name, target)
                return (target, keywords) if keywords else target

            def _pxc_name_table(kotlin):
                table = {}
                for kotlin_name in dir(kotlin):
                    if kotlin_name.startswith("_"):
                        continue
                    pythonic = _pxc_python_name(kotlin_name)
                    other = table.get(pythonic)
                    if other is not None and other != kotlin_name:
                        raise AttributeError(
                            repr(pythonic) + " would name both " + repr(other) + " and " + repr(kotlin_name)
                        )
                    table[pythonic] = kotlin_name
                return table

            class _PxcPythonicFunction:
                def __init__(self, kotlin_fn, name):
                    self._kotlin = kotlin_fn
                    self.__name__ = name
                    keywords = {}
                    for row in _pxc_sys.modules["python_multiplatform"].describe(kotlin_fn):
                        for parameter in row.get("parameters", ()):
                            keywords.setdefault(_pxc_snake_case(parameter["name"]), parameter["name"])
                    self._keywords = keywords

                @property
                def __signature__(self):
                    kotlin_signature = _pxc_inspect_mod.signature(self._kotlin)
                    return kotlin_signature.replace(parameters=[
                        parameter if parameter.kind in (parameter.VAR_POSITIONAL, parameter.VAR_KEYWORD)
                        else parameter.replace(name=_pxc_snake_case(parameter.name))
                        for parameter in kotlin_signature.parameters.values()
                    ])

                def __call__(self, *args, **kwargs):
                    if kwargs:
                        kwargs = {self._keywords.get(key, key): value for key, value in kwargs.items()}
                    return self._kotlin(*args, **kwargs)

            _pm_binding.add_member_resolver(_pxc_member_name)
        """.trimIndent()
    }
}
