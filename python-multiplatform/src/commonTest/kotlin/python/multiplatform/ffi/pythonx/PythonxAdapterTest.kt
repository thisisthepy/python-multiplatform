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
 * `pythonx` -- the hand-written adaptation layer -- doing the four things
 * `docs/design/pythonx-adapter-design.md` asks of it, against a table shaped like the walked Compose one.
 *
 * The four are, in the order the design puts them:
 *
 * 1. **A module exists before any attribute is touched** (§2.3). `import androidx.compose.foundation
 *    .layout` fails at the import statement, not at an attribute, so a module `__getattr__` can
 *    never be the whole answer. A `sys.meta_path` finder is.
 * 2. **A name is adapted once and then lives in the module dict** (§4.1). The 551--587 ns
 *    `__getattr__` figure in `PythonProxySource`'s KDoc prices a hook that answers *every* read of a
 *    live property; this one answers the first read of a name and is never consulted for it again.
 * 3. **A Kotlin name and a Python name are converted by rule, forwards** (§3), with the index the
 *    forward conversion builds standing in for the "map of exceptions" §3 asks for -- which is what
 *    makes `toURLString` reachable at all.
 * 4. **Overloads are dispatched in Python** (`docs/design/kotlin-extensions-in-python.md` §3.1: "this
 *    layer's job is to make that choice *possible*, not to make it").
 *
 * ### Why the assertions read the Kotlin side
 *
 * Which overload ran is invisible from Python -- that is the point of a dispatcher -- so
 * [ComposeShapedFragment.calls] is what the tests assert on. A test that only checked the returned
 * string could not tell `padding__Dp_Dp` from `padding__Dp` called twice.
 */
class PythonxAdapterTest {

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
     * The premise every other test here rests on: this fixture is the walked shape and not a
     * convenient simplification of it.
     *
     * Every field `WalkedArtifactComposeModifierTest.aWalkedEntryCarriesItsDeclarationAndNotOnlyIts
     * Tags` asserts about the real `androidx.compose.foundation.layout.padding__Dp`, asserted about
     * this one. If the walker's shape changes, this fails here rather than the adapter quietly
     * being tested against something that no longer arrives.
     */
    @Test
    fun shapeMatchesTheWalkedEntries() {
        val padding = UpcallTable.callable(
            UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp"),
        )
        assertEquals(true, padding.isExtension)
        assertEquals("androidx.compose.ui.Modifier", padding.receiverTypeName)
        assertEquals(listOf("<receiver>", "all"), padding.paramNames)
        assertEquals(
            listOf("androidx.compose.ui.Modifier", "androidx.compose.ui.unit.Dp"),
            padding.paramTypeNames,
        )
        assertEquals("androidx.compose.ui.Modifier", padding.returnTypeName)
        assertEquals(listOf(false, false), padding.paramHasDefault)
        val symmetric = UpcallTable.callable(
            UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp_Dp"),
        )
        assertEquals(listOf("<receiver>", "horizontal", "vertical"), symmetric.paramNames)
        assertEquals(listOf(false, true, true), symmetric.paramHasDefault)
        // The bare name of an overload set is not bound -- the walker refuses to arbitrate, which
        // is why there is a dispatcher in Python at all.
        assertEquals(
            false,
            UpcallTable.resolve("androidx.compose.foundation.layout.padding").isValid,
            "an overload set must not be arbitrated down to one entry on the Kotlin side",
        )
    }

    /**
     * §2.3's first row: the finder, and the reason a module `__getattr__` cannot replace it.
     *
     * A package with nothing bound under it is **not** made to exist. A finder that answered every
     * `pythonx.*` name would turn a typo into an empty module and an `AttributeError` several lines
     * later; `pythonx` is a facade over what is bound, so what is not bound is not importable.
     */
    @Test
    fun onlyAPackageSomethingIsBoundUnderIsImportable() = withAdapter {
        Python3.exec(
            """
            import androidx.compose.foundation.layout as _layout
            import androidx.compose.ui as _ui
            _px = {'layout': _layout.__name__, 'ui': _ui.__name__}
            try:
                import pythonx.compose.nothing.here
                _px['bogus'] = 'imported'
            except ModuleNotFoundError:
                _px['bogus'] = 'ModuleNotFoundError'
            """.trimIndent(),
        )

        assertEquals("androidx.compose.foundation.layout", eval("_px['layout']"))
        assertEquals("androidx.compose.ui", eval("_px['ui']"))
        assertEquals("ModuleNotFoundError", eval("_px['bogus']"))
    }

    /**
     * §4.1: resolved once, then an ordinary dict hit.
     *
     * `vars(module)` before and after the first read is the whole statement -- the name is not in
     * the module dict until something asks for it, and it is afterwards, so the hook cannot run
     * again for it.
     *
     * The `before` half also pins the other side of that cache, and it found a real defect: this
     * interpreter is shared by the whole suite, so `sys.modules['androidx.compose.foundation.layout']`
     * outlives a test and so did everything adapted into it. `CallableHandle` packs the table epoch
     * exactly so a handle cached across a reinstall is caught, and it caught this -- six tests
     * failed with *invalid or stale callable handle* before `_register_table` learned to invalidate
     * what it had already adapted.
     */
    @Test
    fun anAttributeIsAdaptedOnceAndThenLivesInTheModuleDict() = withAdapter {
        Python3.exec(
            """
            import androidx.compose.foundation.layout as _layout
            _px = {'before': 'padding' in vars(_layout)}
            _first = _layout.padding
            _px['after'] = 'padding' in vars(_layout)
            _px['same'] = _layout.padding is _first
            """.trimIndent(),
        )

        assertEquals("False", eval("_px['before']"))
        assertEquals("True", eval("_px['after']"))
        assertEquals("True", eval("_px['same']"))
    }

    /** §3's rule, both directions, on the names the fixture carries. */
    @Test
    fun theNameRuleConvertsKotlinToPythonAndBack() = withAdapter {
        Python3.exec(
            """
            import pythonx
            _px = {
                'fill': pythonx.to_python_name('fillMaxWidth'),
                'z': pythonx.to_python_name('zIndex'),
                'overload': pythonx.to_python_name('padding__Dp_Dp'),
                'type': pythonx.to_python_name('Modifier'),
                'acronym': pythonx.to_python_name('toURLString'),
                'back_fill': pythonx.to_kotlin_name('fill_max_width'),
                'back_z': pythonx.to_kotlin_name('z_index'),
                'back_overload': pythonx.to_kotlin_name('padding__Dp_Dp'),
                'back_type': pythonx.to_kotlin_name('Modifier'),
                'back_acronym': pythonx.to_kotlin_name('to_url_string'),
            }
            """.trimIndent(),
        )

        assertEquals("fill_max_width", eval("_px['fill']"))
        assertEquals("z_index", eval("_px['z']"))
        // The overload suffix is a list of Kotlin type names and stays PascalCase; only the base
        // name is a function name.
        assertEquals("padding__Dp_Dp", eval("_px['overload']"))
        assertEquals("Modifier", eval("_px['type']"))
        assertEquals("to_url_string", eval("_px['acronym']"))

        assertEquals("fillMaxWidth", eval("_px['back_fill']"))
        assertEquals("zIndex", eval("_px['back_z']"))
        assertEquals("padding__Dp_Dp", eval("_px['back_overload']"))
        assertEquals("Modifier", eval("_px['back_type']"))
        // The reverse rule is **wrong** here, and pinning that is the point: snake -> camel is not
        // injective, so a rule-based reverse alone would lose `toURLString`.
        assertEquals("toUrlString", eval("_px['back_acronym']"))
    }

    /**
     * §3's invariant, stated as the design states it: *every name the `.pyi` generator emits must
     * resolve through the adapter.*
     *
     * The generator's name is `to_python_name(kotlin)`, so this converts every entry in the table
     * forwards and requires the adapter to answer with the same Kotlin declaration -- including
     * `toURLString`, which the reverse rule provably cannot produce (asserted above). The index
     * built by the forward conversion is what carries it, and this is the test that says a stub
     * cannot promise an API the runtime does not have.
     */
    @Test
    fun everyBoundNameSurvivesTheRoundTripThroughTheAdapter() = withAdapter {
        Python3.exec(
            """
            import pythonx
            _px = {'checked': 0, 'bad': []}
            for _kotlin in pythonx.bound_names():
                _package, _, _leaf = _kotlin.rpartition('.')
                _python = pythonx.to_python_name(_leaf)
                _back = pythonx.kotlin_name_for(_package, _python)
                _px['checked'] += 1
                if _back != _kotlin:
                    _px['bad'].append(_kotlin + ' -> ' + _python + ' -> ' + str(_back))
            _px['bad'] = ', '.join(_px['bad'])
            """.trimIndent(),
        )

        assertEquals("", eval("_px['bad']"), "a stub name that the adapter cannot resolve")
        assertEquals(
            UpcallTable.entries().size.toString(),
            eval("_px['checked']"),
            "every entry has to be checked, or the empty failure list means nothing",
        )
    }

    /**
     * The overload dispatcher, on argument **count**. One argument is `padding(all:)`, four is
     * `padding(start:, top:, end:, bottom:)`, and the caller wrote `padding` both times.
     */
    @Test
    fun anOverloadSetDispatchesOnArgumentCount() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding
            from androidx.compose.ui import empty_modifier, describe_modifier
            _px = {
                'one': describe_modifier(padding(empty_modifier(), 16)),
                'four': describe_modifier(padding(empty_modifier(), 1, 2, 3, 4)),
            }
            """.trimIndent(),
        )

        assertEquals("padding(16.0)", eval("_px['one']"))
        assertEquals("padding(s=1.0, t=2.0, e=3.0, b=4.0)", eval("_px['four']"))
        assertEquals(
            listOf("padding__Dp", "padding__Dp_Dp_Dp_Dp"),
            ComposeShapedFragment.calls,
            "the two calls have to reach two different Kotlin declarations",
        )
    }

    /**
     * The dispatcher on keyword **names**, which is the half that needs `ExposedCallable.paramNames`
     * -- `docs/design/pythonx-adapter-design.md` §2.4 called the absence of those names "arithmetic", and
     * this is the arithmetic working.
     *
     * `horizontal=`/`vertical=` selects the two-`Dp` overload even though `padding(m, 8, 4)` would
     * have selected it too: the point is that the *names* are what chose, and the Python spelling is
     * the snake_case one (§3), not Kotlin's.
     */
    @Test
    fun anOverloadSetDispatchesOnKeywordNames() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding, padding_values_of
            from androidx.compose.ui import empty_modifier, describe_modifier
            _px = {
                'kw': describe_modifier(padding(empty_modifier(), horizontal=8, vertical=4)),
                # the same arity, chosen by argument *type* rather than by count
                'pv': describe_modifier(padding(empty_modifier(), padding_values_of(2))),
            }
            """.trimIndent(),
        )

        assertEquals("padding(h=8.0, v=4.0)", eval("_px['kw']"))
        assertEquals("padding(pv(2.0))", eval("_px['pv']"))
        assertEquals(
            listOf("padding__Dp_Dp", "padding__PaddingValues"),
            ComposeShapedFragment.calls,
        )
    }

    /**
     * What the dispatcher does when it cannot decide, and what it does when the caller has already
     * decided.
     *
     * Nothing arbitrates: an argument list no overload accepts raises and **names the candidates**,
     * rather than picking the first that binds. The explicit `padding__Dp` spelling is always
     * available, which is the escape hatch that makes refusing safe.
     *
     * The unmatched call used to be `padding(m, 1, 2, 3)`, and it is not unmatched any more. That is
     * the point of `PythonxDefaultsTest`: `Modifier.padding(1.dp, 2.dp, 3.dp)` compiles in Kotlin,
     * leaving `bottom` to its default, so a dispatcher that refused it was refusing a call the
     * language accepts. What is still unmatched is an argument of the wrong *type* -- a `str` fits no
     * `Dp` slot and carries no handle for the `PaddingValues` one -- and defaults cannot rescue it,
     * because omitting a parameter removes a slot rather than widening what one accepts.
     */
    @Test
    fun anUnmatchedOverloadCallNamesTheCandidatesAndTheExplicitSpellingStillWorks() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding, padding__Dp
            from androidx.compose.ui import empty_modifier, describe_modifier
            _px = {}
            try:
                padding(empty_modifier(), 'sixteen')
                _px['miss'] = 'call succeeded'
            except TypeError as e:
                _px['miss'] = str(e)
            _px['explicit'] = describe_modifier(padding__Dp(empty_modifier(), 16))
            """.trimIndent(),
        )

        val message = eval("_px['miss']")
        assertTrue(
            message.contains("padding__Dp_Dp_Dp_Dp") && message.contains("padding__PaddingValues"),
            "the refusal has to name what it could not choose between: $message",
        )
        assertEquals("padding(16.0)", eval("_px['explicit']"))
        assertEquals(listOf("padding__Dp"), ComposeShapedFragment.calls)
    }

    /**
     * `docs/design/kotlin-extensions-in-python.md` §4.1, running: an extension is a method on its
     * receiver's proxy, and because every one of them returns the receiver type, the chain is
     * ordinary Python method chaining with no combinator machinery.
     *
     * Both spellings of `Modifier` are exercised. The class object works through the hybrid
     * descriptor `docs/design/pyi-generation-design.md` §4.3 measured (a metaclass `def` loses to the
     * class's own MRO); the instance spelling is the ordinary one.
     */
    @Test
    fun anExtensionIsAMethodOnItsReceiverAndTheChainComposes() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier, describe_modifier
            _chained = Modifier.padding(16).size(24)
            _from_instance = Modifier.empty().fill_max_width().z_index(2)
            _px = {
                'chain': describe_modifier(_chained),
                'instance': describe_modifier(_from_instance),
                'is_modifier': isinstance(_chained, Modifier),
            }
            """.trimIndent(),
        )

        assertEquals("padding(16.0) -> size(24.0)", eval("_px['chain']"))
        assertEquals("fillMaxWidth -> zIndex(2.0)", eval("_px['instance']"))
        assertEquals("True", eval("_px['is_modifier']"))
        assertEquals(
            listOf("padding__Dp", "size__Dp", "fillMaxWidth", "zIndex"),
            ComposeShapedFragment.calls,
        )
    }

    /**
     * The negative half of the test above, and the reason to trust it.
     *
     * `WalkedArtifactComposeModifierTest` earned this shape: a test that only ever asserts an
     * equality cannot tell "the chain ran with 16" from "nothing ran". This drives the same chain
     * and requires the wrong value **not** to match.
     */
    @Test
    fun theSameChainComparedAgainstADifferentPaddingDoesNotMatch() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier, describe_modifier
            _px = {'described': describe_modifier(Modifier.padding(16).size(24))}
            _px['wrong_padding'] = _px['described'] == 'padding(17.0) -> size(24.0)'
            _px['wrong_order'] = _px['described'] == 'size(24.0) -> padding(16.0)'
            """.trimIndent(),
        )

        assertEquals("False", eval("_px['wrong_padding']"), "a 16dp chain matched 17dp")
        assertEquals("False", eval("_px['wrong_order']"), "the chain order is not observed")
    }

    /**
     * `docs/design/kotlin-extensions-in-python.md` §4.4's asymmetry, deliberately kept.
     *
     * `Dp` is on the allowlist so `padding(16)` is fine. `TextUnit` is not, and it is not an
     * ergonomic preference: a raw `16` reaching a `TextUnit` decodes as `Unspecified` and renders
     * *nothing*, without raising. Python cannot see that a parameter is a value class from its
     * `TypeTag` alone -- but it can see that the tag is `FLOAT` while the declared type is neither
     * `kotlin.Float` nor `kotlin.Double`, which is exactly "a value class over a primitive".
     *
     * `zIndex` is the control: same `FLOAT` tag, declared `kotlin.Float`, so a raw number is not a
     * value-class question at all and passes without consulting the allowlist.
     */
    @Test
    fun aRawNumberIsAcceptedForDpAndRefusedForAPackedValueClass() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding, padding_from_baseline__TextUnit
            from androidx.compose.ui import Modifier, describe_modifier
            _px = {'dp': describe_modifier(padding(Modifier.empty(), 16))}
            try:
                padding_from_baseline__TextUnit(Modifier.empty(), 16)
                _px['packed'] = 'call succeeded'
            except TypeError as e:
                _px['packed'] = str(e)
            _px['plain_float'] = describe_modifier(Modifier.z_index(2))
            """.trimIndent(),
        )

        assertEquals("padding(16.0)", eval("_px['dp']"))
        val refusal = eval("_px['packed']")
        assertTrue(
            refusal.contains("TextUnit"),
            "the refusal has to name the type that would have decoded wrongly: $refusal",
        )
        assertEquals("zIndex(2.0)", eval("_px['plain_float']"))
    }

    /**
     * §4.1's facade property: a name `pythonx` does not adapt raises rather than being invented,
     * and the Kotlin FQN import is always there as the escape hatch.
     *
     * This is what keeps `pythonx` from growing 37 files -- it never has to enumerate anything to
     * decide what it does *not* have.
     */
    @Test
    fun aNameNothingIsBoundUnderFallsThroughToAttributeError() = withAdapter {
        Python3.exec(
            """
            import androidx.compose.foundation.layout as _layout
            try:
                _layout.no_such_modifier
                _px = 'resolved'
            except AttributeError as e:
                _px = str(e)
            """.trimIndent(),
        )

        assertTrue(
            eval("_px").contains("no_such_modifier"),
            "the AttributeError has to name what was asked for: ${eval("_px")}",
        )
    }

    /**
     * `kind == STATIC_GETTER`: `Arrangement.Start` is a value, read as an attribute, and read
     * **fresh every time** rather than cached the way every other adapted name is.
     *
     * `Arrangement` itself is a submodule here, not a proxy class -- `_PACKAGES_SEEN` picks up
     * `androidx.compose.foundation.layout.Arrangement` from the getter's own package, and `_Finder`
     * resolves `from ... import Arrangement` to it before `androidx.compose.foundation.layout`'s
     * `__getattr__` is ever consulted. `.Start` is then that submodule's own attribute read.
     *
     * The no-cache claim is checked the same way `anAttributeIsAdaptedOnceAndThenLivesInTheModuleDict`
     * checks the opposite one, except through `ComposeShapedFragment.calls`, because the returned
     * value has no Python-visible identity to compare -- `_wrap` builds a fresh proxy instance every
     * call regardless of whether the underlying Kotlin object is one singleton or two. Two Python
     * reads of `Arrangement.Start` have to leave two entries in `calls`; a cache would leave one.
     */
    @Test
    fun aStaticGetterIsReadAsAnAttributeAndReadFreshEveryTime() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import Arrangement, describe_horizontal
            _px = {}
            _first = Arrangement.Start
            _second = Arrangement.Start
            _px['first'] = describe_horizontal(_first)
            _px['second'] = describe_horizontal(_second)
            _px['end'] = describe_horizontal(Arrangement.End)
            """.trimIndent(),
        )

        assertEquals("Start", eval("_px['first']"))
        assertEquals("Start", eval("_px['second']"))
        assertEquals("End", eval("_px['end']"))
        assertEquals(
            listOf("Arrangement.Start", "Arrangement.Start", "Arrangement.End"),
            ComposeShapedFragment.calls,
            "a static getter is read, not cached: each Python-level read must re-enter Kotlin",
        )
    }

    /**
     * The regression this whole change is about: `Arrangement.Start()`, the spelling
     * `ObjectConstantRenderTest` needed before `kind` was branched on, must no longer work -- a
     * static getter's value is not a callable, and a caller that still writes the parentheses
     * should see a clear `TypeError` rather than a silently wrong answer.
     */
    @Test
    fun aStaticGetterIsNotCallable() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import Arrangement
            try:
                Arrangement.Start()
                _px = 'called'
            except TypeError as e:
                _px = str(e)
            """.trimIndent(),
        )

        assertTrue(
            eval("_px") != "called",
            "a static getter's value must refuse to be called",
        )
    }

    /**
     * Publishes this target's raw entry points, installs `pythonx`, and runs [block].
     *
     * `PythonProxySource.install()` is deliberately **not** called: `pythonx` reaches the boundary
     * through `_pm_resolve`/`_pm_invoke` directly, so the eager whole-table proxy render is not a
     * prerequisite for it. That is §2.3's laziness claim as an executable statement rather than as
     * a plan.
     *
     * The refusal branch is [PythonProxyInstallTest]'s, for the same reason: on a target with no
     * proxy bootstrap the documented failure is asserted rather than skipped, so a target that
     * gains or loses a shim fails one branch or the other.
     */
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
