package fixture.compose

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import python.multiplatform.ffi.upcall.UpcallBootstrap
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Issue #17 on the real thing: a `Modifier` chain built from Compose's own jars, with the second link
 * reached under a name only a registered member resolver knows.
 *
 * Compose's structural equality is the judge, so `fill_max_width` can only match if the resolver
 * served Compose's real `fillMaxWidth`. The controls: no resolver means `AttributeError`, and the same
 * members in the other order do not match.
 */
class MemberResolverComposeTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun aResolvedSnakeNameRunsComposesFillMaxWidthInARealChain() {
        Python3.exec(
            """
            import python_multiplatform.binding as _b
            from androidx.compose.foundation.layout import padding__Dp
            from fixture.compose import emptyModifier
            from fixture.compose import equalsPaddingThenFillMaxWidth, equalsFillMaxWidthThenPadding

            _start = padding__Dp(emptyModifier(), 16.0)
            try:
                _start.fill_max_width
                raise AssertionError('fill_max_width resolved with no resolver registered')
            except AttributeError:
                pass

            def _snake_to_camel(type_name, requested, kotlin_names):
                head, *rest = requested.split('_')
                camel = head + ''.join(p.capitalize() for p in rest)
                return camel if camel in kotlin_names else None

            _b.add_member_resolver(_snake_to_camel)
            try:
                _chain = _start.fill_max_width()
                assert equalsPaddingThenFillMaxWidth(_chain._pm_handle, 16.0), 'chain is not padding(16.dp).fillMaxWidth()'
                assert not equalsPaddingThenFillMaxWidth(_chain._pm_handle, 17.0), '16dp matched 17dp'
                assert not equalsFillMaxWidthThenPadding(_chain._pm_handle, 16.0), 'the chain order is not observed'
                assert 'fill_max_width' not in dir(_chain), 'the alias leaked into dir()'
                assert 'fill_max_width' not in vars(type(_chain)), 'the alias was written onto the proxy class'
            finally:
                _b.remove_member_resolver(_snake_to_camel)
            try:
                _start.fill_max_width
                raise AssertionError('the alias survived removing the resolver')
            except AttributeError:
                pass
            """.trimIndent(),
        )
    }
}
