package fixture.compose

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion, end to end: **`Text('hi')` written in Python draws pixels through
 * `androidx.compose.material3.Text`.**
 *
 * Every layer is the real one. `material3-desktop.jar` is walked by `ArtifactScanner`; the composable
 * it finds is bound with its `$composer`, `$changed` and `$default` slots exposed; its call site is a
 * `.class` `ComposableThunks` emitted at build time, because a Kotlin file facade has no Kotlin name;
 * `pythonx` computes the `$default` mask from which arguments the Python call wrote; the composer
 * comes from [PythonComposition], the one hand-written `@Composable`; and `ImageComposeScene` runs a
 * real composition and rasterises it.
 *
 * ### How "it drew" is decided
 *
 * By counting pixels that are not the background. Two scenes are rendered from the same code path
 * -- one whose Python body calls `Text`, one whose body does nothing -- and the assertion is that the
 * first has ink and the second has none. That is deliberately not an image comparison against a
 * checked-in reference: this test has to survive a Compose version bump changing a font metric,
 * while still failing if nothing composes.
 *
 * A second scene renders `Text('hi')` and `Text('hi hi hi hi')` and asserts the second has strictly
 * more ink, which is what stops "any non-empty frame" from passing -- the text that was written has
 * to be the text that was drawn.
 */
class ComposableRenderTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        // No `FunctionTable`: this module declares nothing KSP binds (its one public declaration
        // is the `@Composable` entry point, which `BindingPolicy` declines), so the walked artefacts
        // are the whole table.
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /** The claim, in one line of Python. */
    @Test
    fun pythonDrawsARealMaterial3Text() {
        val drawn = inkOf("from androidx.compose.material3 import Text\nText('hi')")
        val blank = inkOf("pass")

        println("compose render: Text('hi') -> $drawn non-background pixels, empty body -> $blank")
        assertEquals(0, blank, "an empty composition must draw nothing, or the measurement is not measuring")
        assertTrue(drawn > 0, "Text('hi') drew nothing")
    }

    /**
     * The argument crossed, and not merely *an* argument: more text is more ink.
     *
     * Without this, a test that only asserted "something was drawn" would pass if the string never
     * left Python and Compose rendered a default.
     */
    @Test
    fun theStringPythonWroteIsTheStringComposeDrew() {
        val short = inkOf("from androidx.compose.material3 import Text\nText('hi')")
        val long = inkOf("from androidx.compose.material3 import Text\nText('hi hi hi hi hi')")

        println("compose render: 'hi' -> $short px, 'hi hi hi hi hi' -> $long px")
        assertTrue(long > short, "expected more ink for more text: $short vs $long")
    }

    /**
     * That the mask is *right* and not merely present, argued from what a wrong one would do.
     *
     * `Text` declares 15 or 16 defaulted parameters and `Text('hi')` writes none of them. Slot 16
     * (`style`) is a `TextStyle` and arrives as `null`; the only thing that stops that `null` from
     * reaching `Text`'s body is `$default` bit 16 telling the callee to assign
     * `LocalTextStyle.current` over it first. So a mask that was shifted, truncated or zero would not
     * draw the wrong thing -- it would raise inside Compose. The rendering tests above are therefore
     * already the assertion; this one states the shape the table has, so that a regression that
     * *stops* binding composables fails here with a readable message rather than as a blank frame.
     */
    @Test
    fun theWalkedTableCarriesTextWithItsSyntheticSlots() {
        // Asserted inside Python rather than marshalled back out, the way
        // `WalkedArtifactPythonImportTest` does it: `Python3.exec` raises a Kotlin `PyException`
        // carrying the Python message, so a failure arrives intact and the check costs no boundary
        // crossing of its own that could be the thing that actually worked.
        Python3.exec(
            """
            import python_multiplatform.binding as _pm_binding
            # By the *base* name, not the fully-qualified one: material3 declares four `Text`s, so
            # the walker gave each an overload suffix and `_pm_binding._TABLE` has no bare
            # `androidx.compose.material3.Text` key at all. `from ... import Text` still works --
            # that is `_Overloads` dispatching -- which is what the rendering tests above use.
            _decls = _pm_binding._BY_PACKAGE['androidx.compose.material3']['Text']
            _decl = [_d for _d in _decls if _d.param_type_names[0] == 'kotlin.String'][0]
            assert len(_decls) >= 2, 'expected Text to be an overload set: ' + repr([_d.leaf for _d in _decls])
            assert tuple(_decl.param_names[-4:]) == ('${'$'}composer', '${'$'}changed', '${'$'}changed1', '${'$'}default'), \
                repr(_decl.param_names)
            assert _decl.arity == _decl.declared_arity() + 4, (_decl.arity, _decl.declared_arity())
            assert not any(_decl.param_has_default[_decl.declared_arity():]), 'a synthetic slot is never omittable'
            assert _decl.param_has_default[0] is False, 'text has no default'
            assert sum(1 for _f in _decl.param_has_default if _f) >= 14, _decl.param_has_default
            """.trimIndent(),
        )
    }

    /**
     * **The container.** `Column(content=lambda: Text('hi'))` -- a Python callable filling a Kotlin
     * `Function3`.
     *
     * ### Why this is a different claim from `Text('hi')`
     *
     * `Text` is a leaf: every one of its sixteen parameters is a value, so it was reachable the
     * moment the `$default` mask was. `Column` is not, and not because it is harder -- because
     * `content` is the one parameter it does **not** default. Until a Python callable could become a
     * `Function3`, `Column` had no reachable call at all, and neither did `Row`, `Box` or `Button`.
     * A UI is containers; that is why this and not another leaf.
     *
     * ### How "the content drew" is decided
     *
     * The same way [pythonDrawsARealMaterial3Text] decides it, and with the same guard against the
     * weaker claim: a `Column` whose content draws is compared against a `Column` whose content is
     * `pass`. So "Compose composed a Column" cannot pass this -- an empty `Column` composes exactly
     * as much and draws nothing. The text is offset by a padding-free `Column`, so the ink is the
     * text's own.
     */
    @Test
    fun pythonFillsAContainersContentSlotWithALambda() {
        val drawn = inkOf(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text
            Column(content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.foundation.layout import Column
            Column(content=lambda: None)
            """.trimIndent(),
        )

        println("compose render: Column(content=lambda: Text('hi')) -> $drawn px, empty content -> $empty px")
        assertEquals(0, empty, "a Column whose content draws nothing must draw nothing")
        assertTrue(drawn > 0, "the content lambda never reached Compose")
    }

    /**
     * The content lambda is a real composition position, not a string that got concatenated
     * somewhere: **two** `Text`s inside one `Column` stack, and stacking is more ink than either.
     *
     * Without this, a `content` that was invoked once with the wrong composer -- or a body that was
     * `exec`ed at the outer position and merely happened to draw -- would be indistinguishable from
     * one that composed inside the container.
     */
    @Test
    fun theContentComposesInsideTheContainerAndNotBesideIt() {
        val one = inkOf(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text
            Column(content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val two = inkOf(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text

            def _body():
                Text('hi')
                Text('hi')

            Column(content=_body)
            """.trimIndent(),
        )

        println("compose render: one Text in a Column -> $one px, two -> $two px")
        assertTrue(two > one, "a second child added no ink: $one vs $two")
    }

    /**
     * **The scope receiver, from a real `Column`.** `Column(content=lambda col: Text('hi'))`.
     *
     * ### Why this is a different claim again
     *
     * [pythonFillsAContainersContentSlotWithALambda] proves a Python callable can *be* a
     * `Function3`. It says nothing about the first of the three arguments Compose invokes it with,
     * which was dropped: `content` is `@Composable ColumnScope.() -> Unit`, and `ColumnScope` is
     * where `Modifier.weight` lives. A container whose scope never reaches Python is a container
     * whose children cannot be laid out relative to each other.
     *
     * ### What is asserted, and why the type name and not merely the value
     *
     * The ink first, so this is still a rendering claim and not only a marshalling one. Then the
     * receiver's **Python type name**, because that is the whole of what makes the value useful:
     * `pythonx` keys `_BY_RECEIVER` on the Kotlin type name, so a proxy built under the wrong name
     * would carry the right `ColumnScope` and have none of its extensions on it. `ColumnScope` is
     * an `androidx` type this test never names on the Kotlin side -- it comes out of the walked
     * jar's metadata, through the slot's type name, into a Python class.
     */
    @Test
    fun aRealColumnHandsItsContentTheColumnScope() {
        val drawn = inkOf(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text
            _scopes = []

            def _body(scope):
                _scopes.append(type(scope).__name__)
                Text('hi')

            Column(content=_body)
            """.trimIndent(),
        )

        println("compose render: Column(content=lambda scope: Text('hi')) -> $drawn px, scope type ${pyStr("_scopes[0]")}")
        assertTrue(drawn > 0, "a content that declares its receiver never reached Compose")
        assertEquals("1", pyStr("len(_scopes)"), "the content ran without being handed a receiver")
        assertEquals(
            "ColumnScope",
            pyStr("_scopes[0]"),
            "the receiver did not arrive as a ColumnScope, so the scope's extensions cannot attach",
        )
    }

    /**
     * **An argument crossing out of real Compose into Python**, on the one slot a static render
     * actually fires: `Text`'s `onTextLayout`, which is `(TextLayoutResult) -> Unit`.
     *
     * Every other value callback in `material3` -- `Slider.onValueChange`, `Checkbox.onCheckedChange`
     * -- is an *event* handler, and a rendered frame delivers no events, so none of them can be shown
     * to work by rasterising anything. `onTextLayout` is called by the text layout itself, which
     * makes it the only one whose argument can be observed without driving input.
     *
     * The assertion is on the **object Compose passed**, not on the fact that something was called:
     * `TextLayoutResult` is a type this module never names, it reaches Python as a proxy over a
     * handle, and its `size` is a real measurement of the string that was drawn. A wrapper that
     * dropped its argument would still fire this callback.
     */
    @Test
    fun aRealTextHandsItsLayoutCallbackTheResult() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import Text
            _laid_out = []
            Text('hi', onTextLayout=lambda result: _laid_out.append(type(result).__name__))
            """.trimIndent(),
        )

        println("compose render: Text with onTextLayout -> $drawn px, callback saw ${pyStr("_laid_out")}")
        assertTrue(drawn > 0, "the Text did not draw, so nothing can be concluded about its callback")
        assertEquals("1", pyStr("len(_laid_out)"), "onTextLayout never reached Python")
        assertEquals(
            "TextLayoutResult",
            pyStr("_laid_out[0]"),
            "the layout result did not arrive as its declared type",
        )
    }

    /**
     * **An extension composable, called as a method on the scope that was forwarded to Python.**
     *
     * `Row(content=lambda row: row.NavigationBarItem(...))` -- `RowScope.NavigationBarItem` is one of
     * seven `@Composable` extensions three Compose jars declare, and it was declined outright by
     * `ArtifactScanner.composableCandidate` on the grounds that nobody had measured what a receiver
     * does to the two numberings. It does different things to them:
     *
     * | | receiver |
     * |---|---|
     * | `$changed` | slot 0; value parameter *i* is slot *i* + 1 |
     * | `$default` | **not numbered**; bit *i* is value parameter *i*, and the receiver gets bit 31 |
     *
     * Both read out of `javap -c androidx/compose/material3/NavigationBarKt` and stated on
     * `ArtifactScanner.composableCandidate`; only the second matters, because `pythonx` passes
     * `$changed` as `0` unconditionally.
     *
     * ### Why the frame drawing at all is the assertion
     *
     * The same argument [theWalkedTableCarriesTextWithItsSyntheticSlots] makes about `Text`, and
     * sharper here. This call omits six of the nine value parameters, and every one of them arrives
     * as a zero or a `null` that only the mask tells the callee to overwrite. Shifting every bit by
     * one -- which is exactly what the uncorrected slot-index arithmetic did, since the receiver
     * occupies slot 0 and no `$default` bit -- was tried, and the frame does not come out slightly
     * wrong: it fails with
     *
     *     Parameter specified as non-null is null: method
     *     androidx.compose.foundation.selection.SelectableKt.selectable-O2vRcR0,
     *     parameter ${'$'}this${'$'}selectable
     *
     * because `modifier` keeps the `null` its bit no longer claims. A mask off by a single bit
     * cannot render a slightly wrong picture; it cannot render one at all, which is why this is a
     * rendering test rather than a table one.
     *
     * The second scene is the guard against the weaker claim: a selected `NavigationBarItem` draws
     * its own indicator, so "something was drawn" would pass with an icon that never ran. The icon is
     * a nested Python callable inside a forwarded receiver, and its text is what the ink is compared
     * on.
     */
    @Test
    fun aScopeForwardedToPythonCanCallTheExtensionComposableDeclaredOnIt() {
        val withIcon = inkOf(
            """
            from androidx.compose.foundation.layout import Row
            from androidx.compose.material3 import Text
            Row(content=lambda row: row.NavigationBarItem(
                selected=True, onClick=lambda: None, icon=lambda: Text('hi hi hi')))
            """.trimIndent(),
        )
        val withoutIcon = inkOf(
            """
            from androidx.compose.foundation.layout import Row
            Row(content=lambda row: row.NavigationBarItem(
                selected=True, onClick=lambda: None, icon=lambda: None))
            """.trimIndent(),
        )

        println("compose render: RowScope.NavigationBarItem -> $withIcon px with an icon, $withoutIcon without")
        assertTrue(withoutIcon > 0, "the extension composable did not compose at all")
        assertTrue(
            withIcon > withoutIcon,
            "the icon lambda never ran inside the extension composable: $withoutIcon vs $withIcon",
        )
    }

    /**
     * **`docs/archive/pythonx-adapter-design.md` §6 item 1, executed:** when Compose drops the slot, the
     * Python callable comes back.
     *
     * `sys.getrefcount` is the measurement and the callable is held in a Python global, so the only
     * thing that can move the count is the Kotlin side taking a reference and giving it back.
     * `RememberObserver.onForgotten` is the only hook that reports the drop, which is why
     * [PythonCallableArena] is a remembered value and why this test closes the scene rather than
     * merely letting it go out of scope.
     *
     * Three assertions, because a leak test alone would pass a double release (`agent-rules` §14):
     * the count goes **up** while the composition is alive, comes **back** when it is disposed, and
     * the arena reports releasing **exactly one** callable rather than releasing something twice.
     */
    @Test
    fun aDisposedCompositionGivesEveryPythonCallableBack() {
        Python3.exec(
            """
            import sys
            from androidx.compose.material3 import Text

            def _content():
                Text('hi')

            _base = sys.getrefcount(_content)
            """.trimIndent(),
        )

        CountingCallableArena.resetCounters()
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(
                """
                from androidx.compose.foundation.layout import Column
                Column(content=_content)
                """.trimIndent(),
            )
        }
        try {
            scene.render()
            Python3.exec("_held = sys.getrefcount(_content)")
        } finally {
            scene.close()
        }

        Python3.exec("_after = sys.getrefcount(_content)")
        val base = pyInt("_base")
        val held = pyInt("_held")
        val after = pyInt("_after")

        println(
            "callable lifetime: refcount base=$base held=$held disposed=$after; " +
                "arenas created=${CountingCallableArena.created} forgotten=${CountingCallableArena.forgotten} " +
                "released=${CountingCallableArena.released}",
        )
        assertEquals(1, CountingCallableArena.created, "the arena was never remembered")
        assertTrue(held > base, "nothing held a reference for the composition: $base -> $held")
        assertEquals(1, CountingCallableArena.forgotten, "onForgotten never fired, so nothing could release")
        // Not "at least one": `close` reports `0` for every call after the first, so a total above
        // the number of callables that crossed is the double-release this cannot be allowed to pass.
        assertEquals(1, CountingCallableArena.released, "callables released, expected exactly the one that crossed")
        assertEquals(base, after, "the composition did not give the Python reference back")
    }

    /**
     * **The holder outlives a composition pass**, which is the property the whole arrangement rests
     * on and the one a single render cannot show.
     *
     * A second pass is forced by changing the source `PythonComposition` is given, so the Python body
     * runs again and a second callable crosses. Two things are then true and they are different
     * claims: the arena is **the same one** -- `remember` returned it again, so `created` is still 1
     * after two passes -- and the callable from the *first* pass is still held, because nothing but
     * `onForgotten` releases and that has not fired. Both are released at disposal, and both
     * reference counts come back.
     *
     * That the first pass's callable is still held is a **cost** as much as a correctness property,
     * and this test is deliberately the case interning cannot help: `_first` and `_second` are two
     * different callables, so two wrappers is the right answer and the count is 2 either way. The
     * case where it is *one* callable passed again -- which is what a recomposing UI does -- is
     * `RecompositionAccumulationTest`, and it measured twelve wrappers for twelve passes before the
     * scope started keying them.
     *
     * What is still unsolved is a hook that reports Compose dropping *one* slot, which is what would
     * let a callable that genuinely changed be released between passes rather than at disposal.
     * `RememberObserver` is not one -- a `content` is a parameter, not a remembered value.
     */
    @Test
    fun theHolderSurvivesARecompositionAndReleasesBothCallablesAtDisposal() {
        Python3.exec(
            """
            import sys
            from androidx.compose.material3 import Text

            def _first():
                Text('hi')

            def _second():
                Text('hi hi hi hi hi')

            _base_first = sys.getrefcount(_first)
            _base_second = sys.getrefcount(_second)
            """.trimIndent(),
        )

        CountingCallableArena.resetCounters()
        val body = mutableStateOf(columnCalling("_first"))
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body.value)
        }
        val firstInk: Int
        val secondInk: Int
        try {
            firstInk = inkOfImage(scene.render())
            body.value = columnCalling("_second")
            Snapshot.sendApplyNotifications()
            secondInk = inkOfImage(scene.render())
            Python3.exec("_held_first = sys.getrefcount(_first)")
            assertEquals(1, CountingCallableArena.created, "the arena was rebuilt instead of remembered")
            assertEquals(0, CountingCallableArena.released, "something released a callable before disposal")
        } finally {
            scene.close()
        }
        Python3.exec("_after_first = sys.getrefcount(_first)\n_after_second = sys.getrefcount(_second)")

        println(
            "recomposition: ink $firstInk -> $secondInk; _first refcount " +
                "${pyInt("_base_first")} held=${pyInt("_held_first")} disposed=${pyInt("_after_first")}; " +
                "released=${CountingCallableArena.released}",
        )
        assertTrue(secondInk > firstInk, "the second pass drew the first pass's content: $firstInk vs $secondInk")
        assertTrue(
            pyInt("_held_first") > pyInt("_base_first"),
            "the first pass's callable was not still held during the second",
        )
        assertEquals(2, CountingCallableArena.released, "expected both passes' callables to be released once each")
        assertEquals(pyInt("_base_first"), pyInt("_after_first"), "the first pass's callable never came back")
        assertEquals(pyInt("_base_second"), pyInt("_after_second"), "the second pass's callable never came back")
    }


    /**
     * **`Button`.** Both of its non-defaulted slots at once: `onClick` (`Function0`, no receiver)
     * and `content` (`Function3@Composable(RowScope)`), the same two shapes `ce1de0c3` and
     * `a179b747` proved separately, now on the declaration the design doc singles out as "the
     * closest": `ComposableBindingTest` had already confirmed `Button.onClick` is
     * `kotlin.Function0` and `Button.content` is `kotlin.Function3@Composable` against the real
     * `material3-desktop.jar`, but only as a synthetic-fixture claim. This renders the real
     * declaration.
     */
    @Test
    fun buttonComposesItsClickHandlerAndItsRowScopedContent() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import Button, Text
            Button(on_click=lambda: None, content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.material3 import Button
            Button(on_click=lambda: None, content=lambda: None)
            """.trimIndent(),
        )
        println("compose render: Button(content=Text('hi')) -> $drawn px, empty content -> $empty px")
        assertTrue(empty > 0, "a Button with no content must still draw its own surface")
        assertTrue(drawn > empty, "the content lambda never reached the button's surface: $empty vs $drawn")
    }

    /**
     * **`Card`, and the overload dispatcher choosing correctly.** `material3` declares two `Card`s:
     * one taking only `content` (all the rest defaulted) and one also requiring `onClick`. Calling
     * `Card(content=...)` alone has to resolve to the *first* -- the second's `onClick` has no
     * default, so a caller supplying only `content` cannot satisfy it. This is a claim about
     * `pythonx`'s `_Overloads` dispatch as much as about `Card`: picking the wrong member would
     * either raise (the clickable overload demanding `onClick`) or -- if the dispatcher instead
     * arbitrated rather than tried each candidate -- silently ignore `content`.
     */
    @Test
    fun cardResolvesToTheNonClickableOverloadAndDrawsItsContent() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import Card, Text
            Card(content=lambda scope: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.material3 import Card
            Card(content=lambda scope: None)
            """.trimIndent(),
        )
        println("compose render: Card(content=Text('hi')) -> $drawn px, empty content -> $empty px")
        assertEquals(0, empty, "an empty Card must draw nothing, or the measurement is not measuring")
        assertTrue(drawn > 0, "the content lambda never reached Card")
    }

    /**
     * **`MaterialTheme`, and a `content` with no scope at all.** Every container proven so far --
     * `Column`, `Row`, `Box` -- hands its content a receiver (`ColumnScope`, `RowScope`, `BoxScope`).
     * `MaterialTheme.content` is `@Composable () -> Unit`: `kotlin.Function2`, no receiver, arity
     * zero. Nothing before this exercised that shape, so this is not decoration -- it is the one
     * case `functionSlotTypeName`'s `Function2` branch had never been asked to bind through render.
     */
    @Test
    fun materialThemeComposesAZeroArgumentContentLambda() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import MaterialTheme, Text
            MaterialTheme(content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.material3 import MaterialTheme
            MaterialTheme(content=lambda: None)
            """.trimIndent(),
        )
        println("compose render: MaterialTheme(content=Text('hi')) -> $drawn px, empty content -> $empty px")
        assertEquals(0, empty, "an empty MaterialTheme must draw nothing")
        assertTrue(drawn > 0, "the content lambda never reached MaterialTheme")
    }

    /**
     * **`ListItem`.** `headlineContent` is the one parameter with no default (also `Function2`,
     * arity zero) and it is written under its **snake_cased** keyword, `headline_content` -- so
     * this also pins that a keyword argument reaches a real multi-word parameter by its Pythonic name
     * (issue #131), not just the single-word ones (`content`, `modifier`) every other test here
     * happens to use. The Kotlin spelling `headlineContent=` reaches the same parameter; the empty
     * control below is written that way, so both spellings render here.
     *
     * The empty control is not zero, unlike `Column`'s or `Row`'s: `ListItem`, like `Button`, always
     * paints its own container surface (`colors.containerColor` at `tonalElevation`) whether or not
     * `headlineContent` draws anything, so "more ink with real content" is the same shape of claim
     * [buttonComposesItsClickHandlerAndItsRowScopedContent] makes, not the empty-means-zero shape
     * `Column`'s does.
     */
    @Test
    fun listItemComposesItsHeadlineContentUnderItsSnakeCasedName() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import ListItem, Text
            ListItem(headline_content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import ListItem
            ListItem(headlineContent=lambda: None)
            """.trimIndent(),
        )
        // Ink *count* cannot tell these apart: `ListItem`'s own container surface already covers
        // essentially the whole 200x60 scene (measured: 11200 non-background pixels either way), so
        // text drawn on top changes which pixels are non-background, not how many are. The distinct
        // *colors* present do differ -- the glyph color is not the container fill color -- which is
        // the same argument [lightColorSchemeIsCallableNowThatItsColoursCanBeBuilt] makes for
        // `colorScheme`, applied here to whether `headlineContent` painted anything at all.
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: ListItem(headline_content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "a ListItem with no headline must still draw its own container surface")
        assertTrue(
            drawnColors != emptyColors,
            "headlineContent added no new color over the bare container: $emptyColors vs $drawnColors",
        )
    }

    /**
     * **`Badge`, the one declaration here with *no* required parameter at all.** Every other
     * container in this file needs at least a `content` or an `onClick`; `Badge`'s every slot --
     * including `content` -- defaults, so `Badge()` alone has to draw the small dot Compose gives a
     * badge with nothing in it. That is the leaf claim, on the same footing as `Text('hi')`. The
     * second half is the container claim `Badge(content=...)` adds on top: more ink than the bare
     * dot, the same "the content is not just present but composed" argument
     * [pythonFillsAContainersContentSlotWithALambda] makes for `Column`.
     */
    @Test
    fun badgeDrawsItsLeafFormWithNoArgumentsAndMoreWithContent() {
        val bare = inkOf(
            """
            from androidx.compose.material3 import Badge
            Badge()
            """.trimIndent(),
        )
        val withContent = inkOf(
            """
            from androidx.compose.material3 import Badge, Text
            Badge(content=lambda: Text('hi'))
            """.trimIndent(),
        )
        println("compose render: Badge() -> $bare px, Badge(content=Text('hi')) -> $withContent px")
        assertTrue(bare > 0, "Badge() with every parameter defaulted must still draw its own dot")
        assertTrue(withContent > bare, "the content lambda added no ink over the bare badge: $bare vs $withContent")
    }

    /**
     * **`BadgedBox`, two required `BoxScope` slots on one declaration.** `badge` and `content` both
     * default to nothing -- both must be supplied, both are `Function3@Composable(BoxScope)`, and
     * both have to compose: the control swaps a real `Badge()` for `None` in the badge slot and
     * shows less ink, so "something in the badge slot ran" cannot be satisfied by a slot that was
     * silently skipped.
     */
    @Test
    fun badgedBoxComposesBothItsBadgeAndItsContentScopes() {
        val withBadge = inkOf(
            """
            from androidx.compose.material3 import BadgedBox, Badge, Text
            BadgedBox(badge=lambda scope: Badge(content=lambda: Text('9')), content=lambda scope: Text('hi'))
            """.trimIndent(),
        )
        val withoutBadge = inkOf(
            """
            from androidx.compose.material3 import BadgedBox, Text
            BadgedBox(badge=lambda scope: None, content=lambda scope: Text('hi'))
            """.trimIndent(),
        )
        println("compose render: BadgedBox with a badge -> $withBadge px, without -> $withoutBadge px")
        assertTrue(withoutBadge > 0, "the content scope never composed")
        assertTrue(withBadge > withoutBadge, "the badge scope never composed: $withoutBadge vs $withBadge")
    }

    /**
     * **`IconButton`.** `onClick` (`Function0`) and `content` (`Function2`, zero-argument, unlike
     * `Button`'s `RowScope`-receiving one) both required, neither defaulted.
     */
    @Test
    fun iconButtonComposesItsClickHandlerAndItsContent() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import IconButton, Text
            IconButton(onClick=lambda: None, content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.material3 import IconButton
            IconButton(onClick=lambda: None, content=lambda: None)
            """.trimIndent(),
        )
        println("compose render: IconButton(content=Text('hi')) -> $drawn px, empty content -> $empty px")
        assertEquals(0, empty, "an IconButton with empty content must draw nothing")
        assertTrue(drawn > 0, "the content lambda never reached IconButton")
    }

    /**
     * **`Row`, on its own** -- not riding along inside another test's `NavigationBarItem` scene.
     * `aScopeForwardedToPythonCanCallTheExtensionComposableDeclaredOnIt` already renders a `Row`,
     * but every pixel in it comes from the extension composable in its content; this is the direct
     * counterpart to [pythonFillsAContainersContentSlotWithALambda], so `Row` has the same minimal
     * proof `Column` does rather than only a proof borrowed from a harder test.
     */
    @Test
    fun rowFillsItsContentSlotWithALambdaJustAsColumnDoes() {
        val drawn = inkOf(
            """
            from androidx.compose.foundation.layout import Row
            from androidx.compose.material3 import Text
            Row(content=lambda row: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.foundation.layout import Row
            Row(content=lambda row: None)
            """.trimIndent(),
        )
        println("compose render: Row(content=Text('hi')) -> $drawn px, empty content -> $empty px")
        assertEquals(0, empty, "a Row whose content draws nothing must draw nothing")
        assertTrue(drawn > 0, "the content lambda never reached Row")
    }

    /**
     * **`Box`, and the overload dispatcher again.** `foundation.layout` declares two `Box`es: a
     * content-free one whose only parameter is a *non-defaulted* `Modifier` (`Box__Modifier` --
     * see [ModifierSeededRenderTest] for why that one is unreachable without a seed), and the one
     * this test wants, whose `content` is the only non-defaulted slot. Calling `Box(content=...)`
     * has to resolve to the second: the first has no `content` parameter to accept the keyword
     * argument at all.
     */
    @Test
    fun boxResolvesToTheContentOverloadAndFillsItsBoxScopedSlot() {
        val drawn = inkOf(
            """
            from androidx.compose.foundation.layout import Box
            from androidx.compose.material3 import Text
            Box(content=lambda scope: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.foundation.layout import Box
            Box(content=lambda scope: None)
            """.trimIndent(),
        )
        println("compose render: Box(content=Text('hi')) -> $drawn px, empty content -> $empty px")
        assertEquals(0, empty, "a Box whose content draws nothing must draw nothing")
        assertTrue(drawn > 0, "the content lambda never reached Box")
    }

    /**
     * **`lightColorScheme`, which `a6742a1c` pinned as having no reachable call at all -- and now
     * has one.**
     *
     * That pin had two halves and only one of them was about `lightColorScheme`. The half that
     * stands: `ArtifactScanner.MAX_OMITTABLE_PARAMETERS` caps default-omission at 6 defaulted
     * parameters per declaration, `applyDefaultOmission` gives up on the *whole* declaration when a
     * candidate exceeds it -- not "some of the 48 became omittable", none of them did -- so every one
     * of the 48 `Color` arguments must still be written. That is asserted below, unchanged. (36 through
     * Compose Multiplatform 1.6's material3; material3 1.9, which CMP 1.11 resolves, added 12 more.)
     *
     * The half that does not: *"there is nothing this module's included packages can put in a `Color`
     * slot"*. That was a statement about `artifactIncludePackages`, not about the boundary, and
     * `androidx.compose.ui.graphics` is in the walk now. `Color(255, 0, 0)` builds one --
     * `IconRenderTest.aPythonBuiltColorRoundTripsIntoAnotherWalkedCall` pins the value round trip --
     * and `lightColorScheme`'s slots are `OBJECT`, so the handles go straight in.
     *
     * ### Why 48 written arguments is a *render* test and not a table one
     *
     * Writing 48 arguments proves nothing on its own: they could all be dropped. So the scheme is
     * built with every colour black except `primary`, handed to `MaterialTheme`, and a `Button`
     * inside it paints its container from `colorScheme.primary` -- so the colour that comes out of
     * Skia is the colour Python put in that one named slot out of 48. A scheme whose arguments were
     * shifted by one, or whose keyword mapping went wrong, would paint a different slot's black.
     */
    @Test
    fun lightColorSchemeIsCallableNowThatItsColoursCanBeBuilt() {
        Python3.exec(
            """
            import python_multiplatform.binding as _pm_binding
            _decl = _pm_binding._BY_PACKAGE['androidx.compose.material3']['lightColorScheme'][0]
            # Every one of the 48 Color parameters declares a default in Kotlin, and none of them is
            # omittable here. The cap, unchanged by anything in this commit.
            assert _decl.declared_arity() == 48, _decl.declared_arity()
            assert all(_decl.param_has_default[i] is False for i in range(_decl.declared_arity())), (
                "lightColorScheme became partly omittable -- ArtifactScanner.MAX_OMITTABLE_PARAMETERS "
                "(6) must have grown past 48, or the omission plan changed: " + repr(_decl.param_has_default)
            )
            from androidx.compose.material3 import lightColorScheme
            try:
                lightColorScheme()
                raise AssertionError('lightColorScheme() must not be callable with zero arguments')
            except TypeError as _e:
                assert 'no value for primary' in str(_e), str(_e)
            """.trimIndent(),
        )

        val red = distinctColorsOf(themedButton(255, 0, 0))
        val green = distinctColorsOf(themedButton(0, 255, 0))
        println("compose render: MaterialTheme(lightColorScheme(primary=red)) -> ${red.size} colors, green -> ${green.size}")
        assertTrue(0xFFFF0000.toInt() in red, "the red primary never reached the Button's container: $red")
        assertTrue(0xFF00FF00.toInt() in green, "the green primary never reached the Button's container: $green")
        assertTrue(0xFFFF0000.toInt() !in green, "a green scheme painted red, so the argument was not read: $green")
    }

    /**
     * **`Icon`, which the same commit pinned as unreachable -- two of its three overloads are not.**
     *
     * The pin was right about the table and wrong about what that measured. Nothing in the walk
     * returned an `ImageBitmap`, an `ImageVector` or a `Painter`, and the reason was
     * `artifactIncludePackages`: all three are built in packages this module did not walk. Two more
     * packages later, `ImageBitmap` and `Painter` both have producers and `IconRenderTest` renders
     * pixels through the `Painter` overload.
     *
     * `ImageVector` still has none, and this is where that fact is checked over the table rather than
     * inferred from a failed call -- the shape the original pin got right.
     * `IconRenderTest.imageVectorIsUnreachableBecauseItsBuilderIsANestedClassTheWalkerNeverOpens`
     * carries the reason.
     */
    @Test
    fun twoOfIconsThreeImageTypesNowHaveProducersInTheWalkAndImageVectorDoesNot() {
        Python3.exec(
            """
            import python_multiplatform.binding as _pm_binding
            _icon_image_types = {
                'androidx.compose.ui.graphics.ImageBitmap',
                'androidx.compose.ui.graphics.vector.ImageVector',
                'androidx.compose.ui.graphics.painter.Painter',
            }
            _decls = _pm_binding._BY_PACKAGE['androidx.compose.material3']['Icon']
            _icon_param_types = {_d.param_type_names[0] for _d in _decls}
            assert _icon_param_types == _icon_image_types, \
                "Icon's overloads no longer match what this test recorded: " + repr(_icon_param_types)

            # `materialIcon` (material-icons-core, walked since issue #37) returns an ImageVector,
            # but only out of a `(ImageVector.Builder) -> ImageVector.Builder` lambda, which Python
            # cannot write (`IconRenderTest.imageVectorIsUnreachable...` checks that refusal). So it is
            # not a producer a Python caller can use, and it is the only one.
            _produced = {
                d.return_type_name
                for table in _pm_binding._BY_PACKAGE.values() for decls in table.values() for d in decls
                if d.return_type_name in _icon_image_types and d.kotlin_name != 'androidx.compose.material.icons.materialIcon'
            }
            assert _produced == {
                'androidx.compose.ui.graphics.ImageBitmap',
                'androidx.compose.ui.graphics.painter.Painter',
            }, repr(_produced)
            """.trimIndent(),
        )
    }

    /**
     * `lightColorScheme` with every slot black except `primary`, inside a `MaterialTheme`, around a
     * `Button` whose container reads `colorScheme.primary`.
     *
     * The keyword names are read off the walked declaration rather than typed out, so the test says
     * "all 48, whatever they are called" instead of pinning a Compose version's parameter list.
     */
    private fun themedButton(red: Int, green: Int, blue: Int): String =
        """
        import python_multiplatform.binding as _pm_binding
        from androidx.compose.ui.graphics import Color__Int_Int_Int_Int as _Color
        from androidx.compose.material3 import lightColorScheme, MaterialTheme, Button

        _decl = _pm_binding._BY_PACKAGE['androidx.compose.material3']['lightColorScheme'][0]
        _slots = list(_decl.param_names)
        _black = _Color(0, 0, 0)
        _written = dict((_slot, _black) for _slot in _slots)
        _written['primary'] = _Color($red, $green, $blue)
        _scheme = lightColorScheme(**_written)

        MaterialTheme(
            colorScheme=_scheme,
            content=lambda: Button(onClick=lambda: None, content=lambda: None),
        )
        """.trimIndent()

    private fun distinctColorsOf(body: String): Set<Int> =
        pixelsOf(body).filter { it != BACKGROUND }.toSet()

    private fun columnCalling(name: String): String =
        "from androidx.compose.foundation.layout import Column\nColumn(content=$name)"

    private fun inkOfImage(image: Image): Int {
        val bitmap = Bitmap.makeFromImage(image)
        var ink = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap.getColor(x, y) != BACKGROUND) ink++
            }
        }
        return ink
    }

    /** One Python `int` global, read into Kotlin. `PythonTestFixture` is `commonTest` of another
     * module, so the two C API calls it wraps are repeated here rather than depended on. */
    private fun pyInt(name: String): Int = pyStr(name).toInt()

    /** [pyInt] without the conversion, for an expression whose answer is not a number. */
    private fun pyStr(expression: String): String = Python3.import("__main__").getAttr("__dict__").let { globals ->
        Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
    }

    /**
     * How many pixels of a 200x60 scene differ from the background after running [body] inside a
     * composition.
     */
    private fun inkOf(body: String): Int = pixelsOf(body).count { it != BACKGROUND }

    private fun pixelsOf(body: String): IntArray {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val image: Image = scene.render()
            val bitmap = Bitmap.makeFromImage(image)
            val pixels = IntArray(bitmap.width * bitmap.height)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    pixels[y * bitmap.width + x] = bitmap.getColor(x, y)
                }
            }
            return pixels
        } finally {
            scene.close()
        }
    }

    private companion object {
        /** `ImageComposeScene` clears to transparent black. */
        const val BACKGROUND = 0

        /** CPython's `Py_eval_input`. */
        const val PY_EVAL_INPUT = 258
    }
}
