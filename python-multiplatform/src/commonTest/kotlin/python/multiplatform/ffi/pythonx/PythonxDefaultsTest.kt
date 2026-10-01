package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.publishesProxyEntryPoints
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * `docs/design/pythonx-adapter-design.md` §4.5 from the Python end: **`f(x)`, not `f(x, None, None, ...)`.**
 *
 * ### What the two halves are
 *
 * The Kotlin half is `ArtifactScanner.presenceBranchedCall`: a generated body carries one call
 * expression per subset of its defaulted parameters and picks between them on `args[i] == null`, so
 * an omitted argument reaches Kotlin as *a call that does not mention the parameter* and the
 * compiler supplies the default. That is the only place a default value exists at all -- metadata
 * carries the flag and never the expression.
 *
 * The Python half is here, and it is three lines of `_bind`: a missing argument in a slot
 * `param_has_default` marks is filled with `None` instead of refusing. The boundary already carried
 * `None` for every tag (`UpcallTrampoline.toKotlin` maps it to `null` *before* it looks at the tag),
 * so nothing about marshalling, `ExposedCallable` or any of the five bootstraps changes.
 *
 * ### The one thing that had to change meaning
 *
 * Dispatch. Once `padding(horizontal =, vertical =)` accepts one argument, `padding(m, 16)` matches
 * it *and* `padding(all =)` *and* `padding(start =, top =, end =, bottom =)`, so a rule that refuses
 * whenever more than one candidate binds would have refused every call this fixture used to make.
 * The tie-break is Kotlin's own -- **a candidate that fills no default beats one that does** -- and
 * [theOverloadThatNeedsNoDefaultWins] is what pins it. Where that still leaves a tie, the dispatcher
 * refuses and names the candidates exactly as before.
 *
 * ### Why the assertions read [ComposeShapedFragment.calls] and the label
 *
 * "The default was used" and "0.0 was passed" are the same number at the boundary, so a test that
 * only read the result could not tell them apart -- and passing something equal to the default is
 * precisely the bug this replaces. The fixture's bodies therefore record *which arguments were
 * written*, the way a walked body's `when` branch does, and the assertions are on that.
 */
class PythonxDefaultsTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ComposeShapedFragment))
        ComposeShapedFragment.calls.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    /**
     * The judgement, in the smallest shape that can state it: a declaration whose only value
     * parameter declares a default, called with **no** value parameter.
     *
     * `fill_max_height()` is one declaration, so no dispatcher is involved -- this is `_bind`
     * filling a slot and the Kotlin body taking the branch that writes nothing.
     */
    @Test
    fun aDefaultedParameterMayBeLeftOutEntirely() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier, describe_modifier
            _px = {
                'omitted': describe_modifier(Modifier.fill_max_height()),
                'given': describe_modifier(Modifier.fill_max_height(0.5)),
            }
            """.trimIndent(),
        )

        assertEquals("fillMaxHeight()", eval("_px['omitted']"))
        assertEquals("fillMaxHeight(f=0.5)", eval("_px['given']"))
        assertEquals(listOf("fillMaxHeight", "fillMaxHeight"), ComposeShapedFragment.calls)
    }

    /**
     * The property `docs/design/pythonx-adapter-design.md` §4.5 says an arity-prefix scheme cannot have:
     * the argument that is left out is **not** the last one.
     *
     * `padding__Dp_Dp_Dp_Dp(bottom=4)` writes one of four parameters and skips three, one of which
     * is followed by a written one in `padding__Dp_Dp(vertical=4)`. Both are addressed by the
     * explicit overload name, so nothing here depends on the dispatcher.
     */
    @Test
    fun anOmittedArgumentNeedNotBeATrailingOne() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding__Dp_Dp, padding__Dp_Dp_Dp_Dp
            from androidx.compose.ui import Modifier, describe_modifier
            _px = {
                'last': describe_modifier(Modifier.padding__Dp_Dp_Dp_Dp(bottom=4)),
                'middle': describe_modifier(Modifier.padding__Dp_Dp(vertical=4)),
                'first': describe_modifier(Modifier.padding__Dp_Dp(horizontal=8)),
                'edges': describe_modifier(Modifier.padding__Dp_Dp_Dp_Dp(start=1, end=3)),
            }
            """.trimIndent(),
        )

        assertEquals("padding(b=4.0)", eval("_px['last']"))
        assertEquals("padding(v=4.0)", eval("_px['middle']"))
        assertEquals("padding(h=8.0)", eval("_px['first']"))
        assertEquals("padding(s=1.0, e=3.0)", eval("_px['edges']"))
    }

    /**
     * The one omission the generator refuses, and why it refuses it in the *body* rather than by
     * leaving the branch out.
     *
     * `Modifier.padding()` with nothing written is ambiguous **in Kotlin** between the two-`Dp` and
     * the four-`Dp` overload, so `ArtifactScanner.applyDefaultOmission` generates no call for it --
     * measured, not anticipated: emitting it failed to compile `foundation-layout` in six places.
     * The table cannot express the restriction, though (`paramHasDefault` is per slot), so Python
     * still forms the call and the generated body throws with a message that says what to write.
     *
     * `fill_max_height()` is the contrast in the same test: no sibling overload, so the same
     * omission is generated and runs.
     */
    @Test
    fun omittingEveryArgumentOfAnOverloadedNameIsRefusedByTheBody() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier, describe_modifier
            _px = {}
            try:
                Modifier.padding__Dp_Dp()
                _px['all'] = 'call succeeded'
            except Exception as e:
                _px['all'] = str(e)
            _px['unshadowed'] = describe_modifier(Modifier.fill_max_height())
            """.trimIndent(),
        )

        val message = eval("_px['all']")
        assertTrue(
            "ambiguous with another overload" in message,
            "the refusal has to say why and what to write instead: $message",
        )
        assertEquals("fillMaxHeight()", eval("_px['unshadowed']"))
    }

    /**
     * Kotlin's own resolution rule, which the dispatcher now has to reproduce because defaults made
     * three of the four `padding` overloads applicable to `padding(m, 16)`.
     *
     * `padding__Dp` fills no default; `padding__Dp_Dp` fills one; `padding__Dp_Dp_Dp_Dp` fills
     * three. The first wins, which is also what `kotlinc` does with `Modifier.padding(16.dp)`.
     */
    @Test
    fun theOverloadThatNeedsNoDefaultWins() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding
            from androidx.compose.ui import Modifier, describe_modifier
            _px = {
                'one': describe_modifier(Modifier.padding(16)),
                'two': describe_modifier(Modifier.padding(8, 4)),
                'four': describe_modifier(Modifier.padding(1, 2, 3, 4)),
                'three': describe_modifier(Modifier.padding(1, 2, 3)),
            }
            """.trimIndent(),
        )

        assertEquals("padding(16.0)", eval("_px['one']"))
        assertEquals("padding(h=8.0, v=4.0)", eval("_px['two']"))
        assertEquals("padding(s=1.0, t=2.0, e=3.0, b=4.0)", eval("_px['four']"))
        // Three positional arguments fits only the four-`Dp` overload, which now *does* accept it by
        // leaving `bottom` out -- a legal Kotlin call (`Modifier.padding(1.dp, 2.dp, 3.dp)`) that
        // the pre-defaults dispatcher had to refuse.
        assertEquals("padding(s=1.0, t=2.0, e=3.0)", eval("_px['three']"))
        assertEquals(
            listOf("padding__Dp", "padding__Dp_Dp", "padding__Dp_Dp_Dp_Dp", "padding__Dp_Dp_Dp_Dp"),
            ComposeShapedFragment.calls,
            "the fewest-defaults rule has to reach four different decisions, not one",
        )
    }

    /**
     * A parameter with **no** default is still required, and the refusal still names it.
     *
     * The negative half of [aDefaultedParameterMayBeLeftOutEntirely]: `_bind` fills a slot only
     * where `param_has_default` says the *binding* accepts it, so a declaration the walker refused
     * to make omittable (an unnameable parameter, or one past
     * `ArtifactScanner.MAX_OMITTABLE_PARAMETERS`) keeps the old behaviour rather than silently
     * passing `None` into a slot whose Kotlin body would cast it.
     */
    @Test
    fun aParameterWithNoDefaultIsStillRequired() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding__Dp
            from androidx.compose.ui import Modifier
            _px = {}
            try:
                Modifier.padding__Dp()
                _px['missing'] = 'call succeeded'
            except TypeError as e:
                _px['missing'] = str(e)
            """.trimIndent(),
        )

        val message = eval("_px['missing']")
        assertTrue("no value for all" in message, "the refusal has to name the parameter: $message")
        assertEquals(emptyList(), ComposeShapedFragment.calls, "nothing may have reached Kotlin")
    }

    /**
     * What the table now says, and the reason it is worth asserting from Python rather than only
     * from Kotlin: `param_has_default` stopped being decoration.
     *
     * `ExposedCallable.paramHasDefault` was carried and read by nothing
     * (`WalkedArtifactComposeModifierTest` asserted exactly that). It is now the contract `_bind`
     * consults, so the row reaching Python and the row the walker emitted have to be the same row.
     */
    @Test
    fun theTableRowCarriesTheOmittableSlotsIntoPython() = withAdapter {
        Python3.exec(
            """
            import pythonx as _px_mod
            _decl = _px_mod._TABLE['androidx.compose.foundation.layout.padding__Dp_Dp']
            _plain = _px_mod._TABLE['androidx.compose.foundation.layout.padding__Dp']
            _px = {
                'defaulted': repr(tuple(_decl.param_has_default)),
                'required': repr(tuple(_plain.param_has_default)),
            }
            """.trimIndent(),
        )

        assertEquals("(False, True, True)", eval("_px['defaulted']"))
        assertEquals("(False, False)", eval("_px['required']"))
    }

    private inline fun withAdapter(block: () -> Unit) = PythonTestFixture.withInterpreter {
        assertTrue(
            bindUpcallOrNull(ComposeShapedFragment.EMPTY_MODIFIER),
            "the fixture table is not installed",
        )

        if (!publishesProxyEntryPoints) {
            val refusal = assertFails { PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES) }
            assertTrue(
                refusal.message?.contains("raw upcall entry points are not bound") == true,
                "a target with no proxy bootstrap must fail the adapter's own guard: $refusal",
            )
            return@withInterpreter
        }

        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        Python3.exec(
            "import pythonx\n" +
                "pythonx.register_empty('androidx.compose.ui.Modifier', " +
                "'${ComposeShapedFragment.EMPTY_MODIFIER}')",
        )
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
