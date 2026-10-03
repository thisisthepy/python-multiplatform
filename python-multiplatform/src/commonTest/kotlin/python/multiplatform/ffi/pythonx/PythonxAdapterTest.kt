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
 * `docs/archive/pythonx-adapter-design.md` asks of it, against a table shaped like the walked Compose one.
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
                import androidx.compose.nothing.here
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

    /**
     * §3's rule, forwards, on the names the fixture carries -- restored by issue #131 after
     * `238119b7` removed it.
     *
     * The rule is pythonx-compose 0.1.0a1's: a lower-case-first name is snake_case with a run of
     * capitals as one word, an upper-case-first name is unchanged, and an explicit overload key keeps
     * its suffix. There is still no reverse rule: `toURLString` is reachable as `to_url_string` because
     * the forward conversion of the module's own Kotlin names is what is looked up, and a reverse rule
     * would have produced `toUrlString`, a declaration nobody wrote.
     */
    @Test
    fun theNameRuleConvertsKotlinToPythonAndEitherSpellingReachesOneDeclaration() = withAdapter {
        Python3.exec(
            """
            import python_multiplatform as _pm
            import python_multiplatform.binding as _pm_binding
            import androidx.compose.foundation.layout as _layout
            import androidx.compose.ui.draw as _draw
            import androidx.compose.ui.util as _util
            _px = {
                'fill': _pm.python_name('fillMaxWidth'),
                'z': _pm.python_name('zIndex'),
                'overload': _pm.python_name('padding__Dp_Dp'),
                'suffixed': _pm.python_name('paddingFromBaseline__TextUnit'),
                'type': _pm.python_name('Modifier'),
                'acronym': _pm.python_name('toURLString'),
                'keyword': _pm.snake_case('paddingValues'),
                'same': [
                    _k for _m, _k, _s in (
                        (_layout, 'fillMaxWidth', 'fill_max_width'), (_draw, 'zIndex', 'z_index'),
                        (_util, 'toURLString', 'to_url_string'),
                        (_layout, 'paddingFromBaseline__TextUnit', 'padding_from_baseline__TextUnit'),
                        (_layout, 'paddingValuesOf', 'padding_values_of'),
                    ) if getattr(_m, _s) is getattr(_m, _k)
                ],
                'url': _util.to_url_string('x'),
                'no_reverse': hasattr(_util, 'toUrlString'),
                'rule_in_binding': [_n for _n in ('to_python_name', 'to_kotlin_name') if hasattr(_pm_binding, _n)],
            }
            """.trimIndent(),
        )

        assertEquals("fill_max_width", eval("_px['fill']"))
        assertEquals("z_index", eval("_px['z']"))
        // The overload suffix is a list of Kotlin type names and stays PascalCase; only the base
        // name is a function name.
        assertEquals("padding__Dp_Dp", eval("_px['overload']"))
        assertEquals("padding_from_baseline__TextUnit", eval("_px['suffixed']"))
        assertEquals("Modifier", eval("_px['type']"))
        assertEquals("to_url_string", eval("_px['acronym']"))
        assertEquals("padding_values", eval("_px['keyword']"))
        assertEquals(
            "['fillMaxWidth', 'zIndex', 'toURLString', 'paddingFromBaseline__TextUnit', 'paddingValuesOf']",
            eval("repr(_px['same'])"),
            "an alias must be the very object its Kotlin name is",
        )
        assertEquals("url:x", eval("_px['url']"))
        assertEquals("False", eval("_px['no_reverse']"), "a reverse rule would invent toUrlString")
        // The rule is the root module's, once; the binding layer carries no second copy of it.
        assertEquals("[]", eval("repr(_px['rule_in_binding'])"))
    }

    /**
     * §3's invariant, stated as the design states it -- *every name the `.pyi` generator emits must
     * resolve through the adapter* -- restored by issue #131.
     *
     * The generator writes `python_name(kotlin)` beside every Kotlin name, so this converts every
     * entry in the table forwards and requires the module to answer with the very object the Kotlin
     * name gives -- including `toURLString`, which a reverse rule provably cannot produce (above).
     * Counted, so an empty failure list means something.
     */
    @Test
    fun everyBoundNameSurvivesTheRoundTripThroughItsPythonicName() = withAdapter {
        Python3.exec(
            """
            import importlib as _importlib
            import python_multiplatform as _pm
            import python_multiplatform.binding as _pm_binding
            _px = {'checked': 0, 'aliased': 0, 'bad': []}
            for _kotlin in _pm_binding.bound_names():
                _package, _, _leaf = _kotlin.rpartition('.')
                _python = _pm.python_name(_leaf)
                _px['checked'] += 1
                _module = _importlib.import_module(_package)
                try:
                    _by_kotlin = getattr(_module, _leaf)
                    _by_python = getattr(_module, _python)
                except Exception as _e:
                    _px['bad'].append(_kotlin + ' -> ' + _python + ': ' + type(_e).__name__ + ': ' + str(_e))
                    continue
                if _python != _leaf:
                    _px['aliased'] += 1
                    # A constant is read afresh on every access, so two reads are two proxies.
                    if callable(_by_kotlin) and _by_python is not _by_kotlin:
                        _px['bad'].append(_kotlin + ' -> ' + _python + ': a different object')
            _px['bad'] = ', '.join(_px['bad'])
            """.trimIndent(),
        )

        assertEquals("", eval("_px['bad']"), "a stub name that the adapter cannot resolve")
        assertEquals(
            UpcallTable.entries().size.toString(),
            eval("_px['checked']"),
            "every entry has to be checked, or the empty failure list means nothing",
        )
        assertTrue(eval("_px['aliased']").toInt() >= 8, "too few entries had an alias to check: ${eval("_px['aliased']")}")
    }

    /**
     * Every name in the table resolves under its own Kotlin name, in its own Kotlin package.
     *
     * This used to be the round trip through the snake_case rule and its inverse; with no rule,
     * the invariant left is the one that matters to a reader of the `.pyi` stubs: every bound name
     * is an attribute of the module its Kotlin package names.
     */
    @Test
    fun everyBoundNameResolvesUnderItsOwnKotlinName() = withAdapter {
        Python3.exec(
            """
            import importlib as _importlib
            import python_multiplatform.binding as _pm_binding
            _px = {'checked': 0, 'bad': []}
            for _kotlin in _pm_binding.bound_names():
                _package, _, _leaf = _kotlin.rpartition('.')
                _px['checked'] += 1
                try:
                    getattr(_importlib.import_module(_package), _leaf)
                except Exception as _e:
                    _px['bad'].append(_kotlin + ': ' + type(_e).__name__ + ': ' + str(_e))
            _px['bad'] = ', '.join(_px['bad'])
            """.trimIndent(),
        )

        assertEquals("", eval("_px['bad']"), "a bound name that does not resolve under its Kotlin name")
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
            from androidx.compose.ui import emptyModifier, describeModifier
            _px = {
                'one': describeModifier(padding(emptyModifier(), 16)),
                'four': describeModifier(padding(emptyModifier(), 1, 2, 3, 4)),
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
     * -- `docs/archive/pythonx-adapter-design.md` §2.4 called the absence of those names "arithmetic", and
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
            from androidx.compose.foundation.layout import padding, paddingValuesOf
            from androidx.compose.ui import emptyModifier, describeModifier
            _px = {
                'kw': describeModifier(padding(emptyModifier(), horizontal=8, vertical=4)),
                # the same arity, chosen by argument *type* rather than by count
                'pv': describeModifier(padding(emptyModifier(), paddingValuesOf(2))),
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
     * The same dispatch with the **Pythonic** keyword (issue #131, the spelling `238119b7` removed):
     * `padding_values=` names only `padding__PaddingValues`'s `paddingValues`, so it is the keyword,
     * not the type, that selects -- and the Kotlin keyword `paddingValues=` selects the same overload.
     * Writing both spellings of one parameter is "two values", not a silent pick.
     */
    @Test
    fun anOverloadSetDispatchesOnPythonicKeywordNames() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding, padding_values_of
            from androidx.compose.ui import empty_modifier, describe_modifier
            _px = {
                'snake': describe_modifier(padding(empty_modifier(), padding_values=padding_values_of(2))),
                'kotlin': describe_modifier(padding(empty_modifier(), paddingValues=padding_values_of(3))),
            }
            try:
                padding(empty_modifier(), paddingValues=padding_values_of(1), padding_values=padding_values_of(1))
                _px['both'] = 'call succeeded'
            except TypeError as e:
                _px['both'] = str(e)
            """.trimIndent(),
        )

        assertEquals("padding(pv(2.0))", eval("_px['snake']"))
        assertEquals("padding(pv(3.0))", eval("_px['kotlin']"))
        assertTrue(eval("_px['both']") != "call succeeded", "two spellings of one parameter were both accepted")
        assertEquals(
            listOf("padding__PaddingValues", "padding__PaddingValues"),
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
            from androidx.compose.ui import emptyModifier, describeModifier
            _px = {}
            try:
                padding(emptyModifier(), 'sixteen')
                _px['miss'] = 'call succeeded'
            except TypeError as e:
                _px['miss'] = str(e)
            _px['explicit'] = describeModifier(padding__Dp(emptyModifier(), 16))
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
     * descriptor `docs/archive/pyi-generation-pythonic-stubs.md` §4.3 measured (a metaclass `def` loses to the
     * class's own MRO); the instance spelling is the ordinary one.
     */
    @Test
    fun anExtensionIsAMethodOnItsReceiverAndTheChainComposes() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier, describeModifier
            _chained = Modifier.padding(16).size(24)
            _from_instance = Modifier.empty().fillMaxWidth().zIndex(2)
            _px = {
                'chain': describeModifier(_chained),
                'instance': describeModifier(_from_instance),
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
     * The same chain under the Pythonic method names (issue #131): `fill_max_width` and `z_index`
     * are the Kotlin members `fillMaxWidth` and `zIndex`, from the class object and from an instance,
     * with a snake_case keyword (`z_index=` is `zIndex`'s own parameter `zIndex`). The proxy class
     * keeps Kotlin names only: an alias is resolved on each read and never written onto it.
     */
    @Test
    fun anExtensionIsReachedOnItsReceiverByItsPythonicNameToo() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier, describe_modifier
            _from_class = Modifier.fill_max_width().z_index(2)
            _from_instance = Modifier.empty().padding(16).fill_max_width().z_index(z_index=3)
            _px = {
                'class': describe_modifier(_from_class),
                'instance': describe_modifier(_from_instance),
                'alias_in_class_dict': [_n for _n in ('fill_max_width', 'z_index') if _n in vars(type(_from_class))],
                'alias_in_dir': [_n for _n in ('fill_max_width', 'z_index') if _n in dir(_from_class)],
            }
            """.trimIndent(),
        )

        assertEquals("fillMaxWidth -> zIndex(2.0)", eval("_px['class']"))
        assertEquals("padding(16.0) -> fillMaxWidth -> zIndex(3.0)", eval("_px['instance']"))
        assertEquals("[]", eval("repr(_px['alias_in_class_dict'])"))
        assertEquals("[]", eval("repr(_px['alias_in_dir'])"))
        assertEquals(
            listOf("fillMaxWidth", "zIndex", "padding__Dp", "fillMaxWidth", "zIndex"),
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
            from androidx.compose.ui import Modifier, describeModifier
            _px = {'described': describeModifier(Modifier.padding(16).size(24))}
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
            from androidx.compose.foundation.layout import padding, paddingFromBaseline__TextUnit
            from androidx.compose.ui import Modifier, describeModifier
            _px = {'dp': describeModifier(padding(Modifier.empty(), 16))}
            try:
                paddingFromBaseline__TextUnit(Modifier.empty(), 16)
                _px['packed'] = 'call succeeded'
            except TypeError as e:
                _px['packed'] = str(e)
            _px['plain_float'] = describeModifier(Modifier.zIndex(2))
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
            from androidx.compose.foundation.layout import Arrangement, describeHorizontal
            _px = {}
            _first = Arrangement.Start
            _second = Arrangement.Start
            _px['first'] = describeHorizontal(_first)
            _px['second'] = describeHorizontal(_second)
            _px['end'] = describeHorizontal(Arrangement.End)
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
            "import python_multiplatform.binding as _pm_binding\n" +
                "_pm_binding.register_empty('androidx.compose.ui.Modifier', " +
                "'${ComposeShapedFragment.EMPTY_MODIFIER}')",
        )
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
