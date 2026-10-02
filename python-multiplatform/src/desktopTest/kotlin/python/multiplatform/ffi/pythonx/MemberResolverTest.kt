package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `python_multiplatform.binding.add_member_resolver` (issue #17): the one public point where a
 * Pythonic package built on the binder (pythonx-compose) can say what a Kotlin proxy's member is
 * called, without the binder renaming anything itself.
 *
 * Contract under test:
 *
 * - With no resolver registered, an unknown name on a proxy is an `AttributeError`, exactly as before.
 * - `fn(kotlin_type_name, requested_name, kotlin_member_names) -> kotlin_name | None` is asked only
 *   when no Kotlin member of the requested name exists; the Kotlin member it names is served.
 * - The binder's proxy classes are not written to: `dir()` and the class `__dict__` show Kotlin names only.
 */
class MemberResolverTest {

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
    fun withNoResolverASnakeNameIsAnAttributeError() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            _mr = {}
            for _label, _target in (('instance', Modifier.padding(16)), ('class', Modifier)):
                try:
                    _target.fill_max_width
                    _mr[_label] = 'resolved'
                except AttributeError:
                    _mr[_label] = 'AttributeError'
            """.trimIndent(),
        )
        assertEquals("AttributeError", eval("_mr['instance']"))
        assertEquals("AttributeError", eval("_mr['class']"))
    }

    @Test
    fun aResolvedNameIsReachableThroughAChainAndRunsTheKotlinMember() = withAdapter {
        withResolver {
            Python3.exec(
                """
                from androidx.compose.ui import Modifier, describeModifier
                _mr = {
                    'chain': describeModifier(Modifier.padding(16).fill_max_width().size(24)),
                    'from_class': describeModifier(Modifier.fill_max_width().z_index(2)),
                    'kotlin': describeModifier(Modifier.padding(16).fillMaxWidth()),
                }
                """.trimIndent(),
            )
            assertEquals("padding(16.0) -> fillMaxWidth -> size(24.0)", eval("_mr['chain']"))
            assertEquals("fillMaxWidth -> zIndex(2.0)", eval("_mr['from_class']"))
            assertEquals("padding(16.0) -> fillMaxWidth", eval("_mr['kotlin']"))
            assertEquals(
                listOf("padding__Dp", "fillMaxWidth", "size__Dp", "fillMaxWidth", "zIndex", "padding__Dp", "fillMaxWidth"),
                ComposeShapedFragment.calls,
            )
        }
    }

    @Test
    fun theResolverIsAskedWithTheKotlinTypeTheRequestedNameAndTheKotlinMemberNames() = withAdapter {
        withResolver(recording = true) {
            Python3.exec(
                """
                from androidx.compose.ui import Modifier
                Modifier.padding(1).fill_max_width()
                _call = _mr_calls[0]
                _mr = {
                    'type': _call[0],
                    'requested': _call[1],
                    'has_kotlin_names': 'fillMaxWidth' in _call[2] and 'padding' in _call[2],
                    'no_snake': 'fill_max_width' not in _call[2],
                }
                """.trimIndent(),
            )
            assertEquals("androidx.compose.ui.Modifier", eval("_mr['type']"))
            assertEquals("fill_max_width", eval("_mr['requested']"))
            assertEquals("True", eval("_mr['has_kotlin_names']"))
            assertEquals("True", eval("_mr['no_snake']"))
        }
    }

    @Test
    fun theResolverIsNotAskedForANameKotlinHasAndNoneMeansAttributeError() = withAdapter {
        withResolver(recording = true) {
            Python3.exec(
                """
                from androidx.compose.ui import Modifier
                Modifier.padding(1).fillMaxWidth()
                _asked_for_kotlin = [c[1] for c in _mr_calls]
                try:
                    Modifier.padding(1).no_such_thing
                    _outcome = 'resolved'
                except AttributeError:
                    _outcome = 'AttributeError'
                _mr = {'asked': _asked_for_kotlin, 'outcome': _outcome}
                """.trimIndent(),
            )
            assertEquals("[]", eval("repr(_mr['asked'])"), "a Kotlin name must never reach the resolver")
            assertEquals("AttributeError", eval("_mr['outcome']"))
        }
    }

    @Test
    fun aResolverNamingAMemberTheTypeDoesNotHaveServesNothing() = withAdapter {
        Python3.exec(
            """
            import python_multiplatform.binding as _b
            def _liar(type_name, requested, kotlin_names):
                return 'notAKotlinMember'
            _b.add_member_resolver(_liar)
            try:
                from androidx.compose.ui import Modifier
                try:
                    Modifier.padding(1).anything
                    _mr = {'outcome': 'resolved'}
                except AttributeError:
                    _mr = {'outcome': 'AttributeError'}
            finally:
                _b.remove_member_resolver(_liar)
            """.trimIndent(),
        )
        assertEquals("AttributeError", eval("_mr['outcome']"))
    }

    @Test
    fun dirAndTheProxyClassStayKotlinOnlyAndAResolverOnlyAffectsItsOwnRegistry() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            _before_instance = dir(Modifier.padding(1))
            _before_class = sorted(vars(type(Modifier.padding(1))))
            """.trimIndent(),
        )
        withResolver {
            Python3.exec(
                """
                _m = Modifier.padding(1).fill_max_width()
                _mr = {
                    'dir_same': dir(Modifier.padding(1)) == _before_instance or
                        sorted(set(dir(Modifier.padding(1))) - set(_before_instance)) == ['fillMaxWidth'],
                    'no_alias_in_dir': 'fill_max_width' not in dir(_m),
                    'no_alias_in_class': 'fill_max_width' not in vars(type(_m)),
                    'class_dict_same': sorted(set(vars(type(_m))) - set(_before_class)) == ['fillMaxWidth'],
                }
                """.trimIndent(),
            )
            assertEquals("True", eval("_mr['no_alias_in_dir']"))
            assertEquals("True", eval("_mr['no_alias_in_class']"))
            assertEquals("True", eval("_mr['class_dict_same']"))
            assertEquals("True", eval("_mr['dir_same']"))
        }
        // Removing the only resolver takes the alias with it: nothing was left on the class.
        Python3.exec(
            """
            try:
                Modifier.padding(1).fill_max_width
                _mr = {'after': 'still resolved'}
            except AttributeError:
                _mr = {'after': 'AttributeError'}
            """.trimIndent(),
        )
        assertEquals("AttributeError", eval("_mr['after']"))
    }

    // ------------------------------------------------------------------------------- helpers

    /** `fill_max_width` -> `fillMaxWidth`, and nothing else: the consumer's rule, not the binder's. */
    private inline fun withResolver(recording: Boolean = false, block: () -> Unit) {
        Python3.exec(
            """
            import python_multiplatform.binding as _b
            _mr_calls = []
            def _snake_to_camel(type_name, requested, kotlin_names):
                if ${if (recording) "True" else "False"}:
                    _mr_calls.append((type_name, requested, tuple(kotlin_names)))
                head, *rest = requested.split('_')
                camel = head + ''.join(p.capitalize() for p in rest)
                return camel if camel in kotlin_names else None
            _b.add_member_resolver(_snake_to_camel)
            """.trimIndent(),
        )
        try {
            block()
        } finally {
            Python3.exec("_b.remove_member_resolver(_snake_to_camel)")
        }
    }

    private inline fun withAdapter(block: () -> Unit) = PythonTestFixture.withInterpreter {
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        Python3.exec(
            "import python_multiplatform.binding as _pm_binding\n" +
                "_pm_binding.register_empty('androidx.compose.ui.Modifier', " +
                "'${ComposeShapedFragment.EMPTY_MODIFIER}')",
        )
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
