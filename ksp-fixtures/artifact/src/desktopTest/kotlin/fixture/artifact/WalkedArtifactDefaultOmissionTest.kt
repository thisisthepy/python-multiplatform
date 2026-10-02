package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/design/pythonx-adapter-design.md` §4.5, closed against the real jars: **Compose's own functions,
 * called from Python with their required arguments and nothing else.**
 *
 * ### What was wrong
 *
 * `WalkedArtifactComposeModifierTest` proves the walker reaches Compose, and every call in it passes
 * every argument -- because that was all a generated body could do. §4.5 measures what that costs:
 * across the `Modifier` surface **254 of 435 parameters (58%) declare a default**, and the values a
 * caller would have had to supply instead are objects (`Alignment`, `TextStyle`) the type gate does
 * not bind, so Python could not have supplied them at any price. The bound surface was reachable and
 * unusable at the same time.
 *
 * ### What closes it
 *
 * A walked body now carries one Kotlin call expression per subset of its defaulted parameters and
 * selects between them on `args[i] == null` (`ArtifactScanner.presenceBranchedCall`), and `pythonx`
 * fills a missing argument with that sentinel. The default value itself is carried nowhere:
 * `@Metadata` records only the *flag*, so the only thing that can produce `Alignment.Center` is
 * `kotlinc` compiling a call that does not mention `align` -- which is exactly what the branch is.
 *
 * ### Why these functions
 *
 * All of them are ordinary functions, not composables -- no composable is in the table at all, since
 * the arity check in `ArtifactScanner` declines every one for the synthetic `$composer`/`$changed`
 * parameters its JVM signature carries -- and each has a default that **cannot be reproduced from
 * the Python side**, so an assertion cannot pass for the wrong reason. `ComposeSeed.kt`'s KDoc has
 * the table; the shortest form is `wrapContentSize`, whose default is an `Alignment` no bound
 * declaration produces.
 *
 * Every claim is paired with a control that must answer `False`, for the reason
 * `WalkedArtifactComposeModifierTest.theSameChainComparedAgainstADifferentPaddingDoesNotMatch`
 * gives: a test that only ever sees a `True` cannot tell "Compose ran with the default" from "the
 * comparison never executed".
 *
 * Asserted inside Python for this module's usual reason: `Python3.exec` raises a Kotlin
 * `PyException` carrying the Python error, so a failed `assert` fails the test with its own message.
 */
class WalkedArtifactDefaultOmissionTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        // Both, for different names. `PythonProxySource` publishes this module's own KSP entries
        // under their Kotlin packages (`fixture.artifact`), which is where the comparison functions
        // live; `pythonx` is the adaptation layer and the only one of the two that fills defaults.
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * The judgement, in Compose's own code: two calls that write **no** optional argument.
     *
     * `fill_max_width(m)` and `wrap_content_size(m)` are called with the receiver alone --
     * `f(x)`, not `f(x, None, None)` -- and the receiver is the only required parameter either of
     * them declares.
     */
    @Test
    fun composeFunctionsAreCalledWithTheirRequiredArgumentsAlone() {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import fill_max_width, wrap_content_size
            from fixture.artifact import emptyModifier
            from fixture.artifact import equalsFillMaxWidth, equalsFillMaxWidthFraction
            from fixture.artifact import equalsWrapContentSize, equalsWrapContentSizeTopStart

            _filled = fill_max_width(emptyModifier())
            assert equalsFillMaxWidth(_filled._pm_handle), (
                'fill_max_width() did not produce Modifier.fillMaxWidth()'
            )
            # The controls. `fraction` defaults to 1f, so a body that had passed the sentinel through
            # as a number would have produced one of these instead.
            assert not equalsFillMaxWidthFraction(_filled._pm_handle, 0.5), 'a default of 1f matched 0.5'
            assert not equalsFillMaxWidthFraction(_filled._pm_handle, 0.0), 'a default of 1f matched 0.0'

            _wrapped = wrap_content_size(emptyModifier())
            assert equalsWrapContentSize(_wrapped._pm_handle), (
                'wrap_content_size() did not produce Modifier.wrapContentSize()'
            )
            assert not equalsWrapContentSizeTopStart(_wrapped._pm_handle), (
                'Alignment.Center matched Alignment.TopStart'
            )
            """.trimIndent(),
        )
    }

    /**
     * The half an arity-prefix scheme cannot reach (§4.5's first candidate): one argument written in
     * the **middle** of a defaulted list.
     *
     * `padding(vertical = 8.dp)` leaves `start`, `top` and `end` to Compose. The control writes the
     * same number into `horizontal` instead, which is a different modifier -- so the claim is about
     * *which* parameter the keyword selected, not merely that a call happened.
     */
    @Test
    fun oneKeywordArgumentSelectsItsParameterAndLeavesTheRestToKotlin() {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding__Dp_Dp
            from fixture.artifact import emptyModifier, equalsVerticalPadding, equalsHorizontalPadding

            _padded = padding__Dp_Dp(emptyModifier(), vertical=8.0)
            assert equalsVerticalPadding(_padded._pm_handle, 8.0), (
                'vertical=8 did not produce Modifier.padding(vertical = 8.dp)'
            )
            assert not equalsHorizontalPadding(_padded._pm_handle, 8.0), 'the keyword chose the wrong slot'
            assert not equalsVerticalPadding(_padded._pm_handle, 9.0), '8dp matched 9dp'
            """.trimIndent(),
        )
    }

    /**
     * The table's side of it, read out of the real jar rather than out of a fixture.
     *
     * `paramHasDefault` was carried and read by nothing --
     * `WalkedArtifactComposeModifierTest.aWalkedEntryCarriesItsDeclarationAndNotOnlyItsTags` asserted
     * exactly that. It is now the walker's statement about which slots its generated body can leave
     * out, and it is what `pythonx._bind` reads. `wrapContentSize` is the witness because its two
     * defaults are of different kinds -- an OBJECT and a BOOLEAN -- so a body that only handled
     * primitives would show up here.
     */
    @Test
    fun theWalkedEntrySaysWhichSlotsItCanOmit() {
        val wrap = UpcallTable.callable(
            UpcallTable.resolve("androidx.compose.foundation.layout.wrapContentSize"),
        )
        assertEquals(listOf("<receiver>", "align", "unbounded"), wrap.paramNames)
        assertEquals(listOf(false, true, true), wrap.paramHasDefault)
        assertEquals(
            listOf("androidx.compose.ui.Modifier", "androidx.compose.ui.Alignment", "kotlin.Boolean"),
            wrap.paramTypeNames,
        )
        val fill = UpcallTable.callable(
            UpcallTable.resolve("androidx.compose.foundation.layout.fillMaxWidth"),
        )
        assertEquals(listOf(false, true), fill.paramHasDefault)
        // And a declaration that declares none is unchanged, which is 43 of this jar's 70 entries.
        val paddingAll = UpcallTable.callable(
            UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp"),
        )
        assertEquals(listOf(false, false), paddingAll.paramHasDefault)
    }

    /**
     * The raw boundary is unchanged and still exact, which is what makes this design cheap.
     *
     * `pythonx` fills the omitted slots *before* it calls, so `_pm_invoke` still receives a tuple of
     * exactly `arity` items and `UpcallTrampoline.unmarshalArguments` still refuses anything else.
     * Nothing was added to the marshalling boundary, to `ExposedCallable`, or to any of the five
     * bootstraps -- `None` already crossed as `null` for every tag, which is the whole reason a
     * sentinel was available at all.
     */
    @Test
    fun theRawBoundaryStillRequiresEveryArgument() {
        Python3.exec(
            """
            _handle = _pm_resolve(b'androidx.compose.foundation.layout.fillMaxWidth')
            assert _handle != -1, 'fillMaxWidth is not in the table'
            try:
                _pm_invoke(_handle, (None,))
                raise AssertionError('the trampoline accepted one argument for a two-slot entry')
            except AssertionError:
                raise
            except Exception as e:
                assert 'takes 2 arguments, 1 given' in str(e), 'unexpected refusal: ' + repr(e)
            """.trimIndent(),
        )
    }
}
