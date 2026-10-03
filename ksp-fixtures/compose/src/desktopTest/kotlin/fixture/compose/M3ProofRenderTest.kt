package fixture.compose

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
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
 * **Proof pass for the 27 zero-byte material3 stubs.**
 *
 * The adaptation layer's artefact table produces declarations from the walked jar; these tests
 * establish whether each component actually renders pixels when called from Python, using the same
 * evidence model as [ComposableRenderTest]:
 *
 * - A scene whose Python body calls the component has ink (non-background pixels > 0).
 * - A scene whose Python body is `pass` or an empty equivalent has none (or fewer).
 * - Where the component fills the entire scene with its own surface (FAB, TopAppBar, Scaffold,
 *   TabRow), distinct-colour counting is used instead of ink counting — the same approach
 *   [ComposableRenderTest.listItemComposesItsHeadlineContentUnderItsKotlinName] uses for ListItem.
 *
 * Components judged here (10 of 27):
 * 1. HorizontalDivider — leaf, no required param
 * 2. RadioButton — selected: Boolean, onClick: Function0
 * 3. LinearProgressIndicator — progress: () -> Float (determinate; the indeterminate overload
 *    animates forever and hangs the scene, see that test)
 * 4. CircularProgressIndicator — progress: () -> Float, same reason
 * 5. Surface — content: @Composable () -> Unit
 * 6. Scaffold — content: @Composable (PaddingValues) -> Unit (fills scene — colour-set comparison)
 * 7. Slider — value: Float, onValueChange: (Float) -> Unit (pixel-set comparison)
 * 8. TopAppBar — title: @Composable () -> Unit (fills scene — colour-set comparison)
 * 9. FloatingActionButton — onClick: Function0, content: @Composable () -> Unit (colour-set)
 * 10. Tab + TabRow — selected/onClick/text inside TabRow (colour-set)
 */
class M3ProofRenderTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    // ── 1. HorizontalDivider ──────────────────────────────────────────────────

    /**
     * HorizontalDivider — leaf, every parameter defaulted. Must draw a visible line on a 200x10
     * scene; pass must draw nothing.
     */
    @Test
    fun horizontalDividerDrawsALineWithNoArguments() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import HorizontalDivider
            HorizontalDivider()
            """.trimIndent(),
            width = 200, height = 10,
        )
        val blank = inkOf("pass", width = 200, height = 10)
        println("compose render: HorizontalDivider() -> $drawn non-background pixels, pass -> $blank")
        assertEquals(0, blank, "empty composition must draw nothing")
        assertTrue(drawn > 0, "HorizontalDivider() drew nothing")
    }

    // ── 2. RadioButton ───────────────────────────────────────────────────────

    /**
     * RadioButton — selected=True paints a filled ring, selected=False paints only a ring.
     * Both must draw; and the filled variant (selected) must have more ink than the hollow one.
     */
    @Test
    fun radioButtonRendersItsSelectedAndDeselectedStates() {
        val selected = inkOf(
            """
            from androidx.compose.material3 import RadioButton
            RadioButton(selected=True, onClick=lambda: None)
            """.trimIndent(),
        )
        val deselected = inkOf(
            """
            from androidx.compose.material3 import RadioButton
            RadioButton(selected=False, onClick=lambda: None)
            """.trimIndent(),
        )
        println("compose render: RadioButton selected=$selected px, deselected=$deselected px")
        assertTrue(selected > 0, "RadioButton(selected=True) drew nothing")
        assertTrue(deselected > 0, "RadioButton(selected=False) drew nothing")
        assertTrue(
            selected > deselected,
            "selected RadioButton should have more ink (filled centre dot) than deselected: $deselected vs $selected",
        )
    }

    // ── 3. LinearProgressIndicator ───────────────────────────────────────────

    /**
     * `LinearProgressIndicator(progress=...)` -- the *determinate* overload, and the argument-taking
     * one, which is why it is the one worth binding.
     *
     * The zero-argument overload cannot be tested through this harness at all, and that is a
     * property of the component rather than of the binding: an indeterminate indicator runs an
     * infinite transition, so the composition never goes idle and [ImageComposeScene.render] never
     * returns. Found by thread dump after a suite hung for thirty-seven minutes with seven seconds
     * of CPU -- blocked, not slow. Anything else that animates forever will do the same here.
     *
     * The determinate form is reached with a plain float, not a lambda, and that is itself a
     * finding: the walked table has three overloads, and the current one takes `progress` as
     * `() -> Float`. Passing a Python callable for it is rejected by the overload dispatcher --
     * *"no overload of LinearProgressIndicator accepts these arguments"* -- even though a callable
     * is accepted for `onClick`, which is `Function0<Unit>`. So a value-returning function slot
     * does not accept a Python callable today, while a Unit-returning one does. Pinned by
     * [aValueReturningFunctionSlotDoesNotYetAcceptAPythonCallable] rather than left as a note.
     *
     * The float overload this uses is the deprecated one. It is what the binding can reach, and
     * reaching it still proves the component renders what it is given.
     */
    @Test
    fun linearProgressIndicatorDrawsAtItsGivenProgress() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import LinearProgressIndicator
            LinearProgressIndicator(progress=0.75)
            """.trimIndent(),
            width = 200, height = 8,
        )
        val blank = inkOf("pass", width = 200, height = 8)
        println("compose render: LinearProgressIndicator(progress=0.75) -> $drawn px, pass -> $blank")
        assertEquals(0, blank, "empty composition must draw nothing")
        assertTrue(drawn > 0, "LinearProgressIndicator(progress=0.75) drew nothing")
    }

    // ── 4. CircularProgressIndicator ─────────────────────────────────────────

    /**
     * `CircularProgressIndicator(progress=...)`, determinate for the same reason the linear one is:
     * the indeterminate overload never lets the composition go idle. See that test for what that
     * costs anyone who tries it.
     */
    @Test
    fun circularProgressIndicatorDrawsAtItsGivenProgress() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import CircularProgressIndicator
            CircularProgressIndicator(progress=0.75)
            """.trimIndent(),
        )
        val blank = inkOf("pass")
        println("compose render: CircularProgressIndicator(progress=0.75) -> $drawn px, pass -> $blank")
        assertEquals(0, blank, "empty composition must draw nothing")
        assertTrue(drawn > 0, "CircularProgressIndicator(progress=0.75) drew nothing")
    }

    /**
     * The gap [linearProgressIndicatorDrawsAtItsGivenProgress] found, pinned so it cannot be fixed
     * silently: a slot typed `() -> Float` refuses a Python callable, and the dispatcher says so by
     * listing every candidate. When this test starts failing, the dispatcher has learned to coerce
     * a callable into a value-returning function slot -- and the two progress tests above should
     * move to the non-deprecated overload on the same commit.
     */
    @Test
    fun aValueReturningFunctionSlotDoesNotYetAcceptAPythonCallable() {
        val error = try {
            inkOf(
                """
                from androidx.compose.material3 import LinearProgressIndicator
                LinearProgressIndicator(progress=lambda: 0.75)
                """.trimIndent(),
                width = 200, height = 8,
            )
            null
        } catch (e: Throwable) {
            e.message ?: ""
        }
        println("compose render: LinearProgressIndicator(progress=lambda) -> ${error?.take(80)}")
        assertTrue(error != null, "a callable is now accepted for `() -> Float` -- see this test's doc")
        assertTrue(
            error.contains("no overload of LinearProgressIndicator accepts these arguments"),
            "expected the dispatcher's own rejection, got: $error",
        )
    }

    // ── 5. Surface ───────────────────────────────────────────────────────────

    /**
     * Surface — content: @Composable () -> Unit (Function2, arity 0, same shape as MaterialTheme).
     * Surface's own container background does not fill the full 200x60 scene at its default size,
     * so ink-counting works: with Text inside vs empty content gives different non-zero ink.
     */
    @Test
    fun surfaceComposesItsContentLambda() {
        val drawn = inkOf(
            """
            from androidx.compose.material3 import Surface, Text
            Surface(content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val empty = inkOf(
            """
            from androidx.compose.material3 import Surface
            Surface(content=lambda: None)
            """.trimIndent(),
        )
        println("compose render: Surface(content=Text('hi')) -> $drawn px, empty -> $empty px")
        assertTrue(drawn > 0, "Surface with Text('hi') drew nothing")
        assertTrue(drawn > empty, "Surface content lambda never reached Compose: $empty vs $drawn")
    }

    // ── 6. Scaffold ──────────────────────────────────────────────────────────

    /**
     * Scaffold — content: @Composable (PaddingValues) -> Unit. Scaffold fills the scene with its
     * own surface, so ink-counting cannot distinguish content from no content (both max out at
     * scene pixels). Distinct-colour counting (same approach as ListItem) is the right tool: the
     * glyph colour is not the container fill, so Text inside adds a new colour to the set.
     */
    @Test
    fun scaffoldComposesItsContentWithPaddingValues() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import Scaffold, Text
            Scaffold(content=lambda padding: Text('hi'))
            """.trimIndent(),
            width = 200, height = 120,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import Scaffold
            Scaffold(content=lambda padding: None)
            """.trimIndent(),
            width = 200, height = 120,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: Scaffold(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "Scaffold with empty content must still draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "Scaffold content lambda added no new colour: $emptyColors vs $drawnColors",
        )
    }

    // ── 7. Slider ────────────────────────────────────────────────────────────

    /**
     * Slider — value: Float, onValueChange: (Float) -> Unit, both required.
     * Slider(0.0) places the thumb at the left end; Slider(1.0) at the right. The thumb is the
     * only element that moves, so the two pixel sets must differ — same argument
     * [ComposableRenderTest.theStringPythonWroteIsTheStringComposeDrew] uses for Text length.
     */
    @Test
    fun sliderDrawsAtItsGivenValue() {
        val left = pixelsOf(
            """
            from androidx.compose.material3 import Slider
            Slider(0.0, onValueChange=lambda v: None)
            """.trimIndent(),
            width = 200, height = 48,
        )
        val right = pixelsOf(
            """
            from androidx.compose.material3 import Slider
            Slider(1.0, onValueChange=lambda v: None)
            """.trimIndent(),
            width = 200, height = 48,
        )
        val ink = left.count { it != BACKGROUND }
        println("compose render: Slider(0.0) -> $ink px; pixel sets differ: ${!left.contentEquals(right)}")
        assertTrue(ink > 0, "Slider drew nothing at value=0.0")
        assertTrue(
            !left.contentEquals(right),
            "Slider(0.0) and Slider(1.0) produced identical pixels — the value argument was not read",
        )
    }

    // ── 8. TopAppBar ─────────────────────────────────────────────────────────

    /**
     * TopAppBar — title: @Composable () -> Unit (Function2, arity 0), required.
     * TopAppBar fills the full scene with its own container, so ink counting gives the same
     * result with or without a title. Distinct-colour counting finds the glyph colour.
     */
    @Test
    fun topAppBarComposesItsTitleLambda() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import TopAppBar, Text
            TopAppBar(title=lambda: Text('hi'))
            """.trimIndent(),
            width = 200, height = 64,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import TopAppBar
            TopAppBar(title=lambda: None)
            """.trimIndent(),
            width = 200, height = 64,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: TopAppBar(title=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "TopAppBar with empty title must still draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "TopAppBar title lambda added no new colour: $emptyColors vs $drawnColors",
        )
    }

    // ── 9. FloatingActionButton ───────────────────────────────────────────────

    /**
     * FloatingActionButton — onClick: Function0, content: @Composable () -> Unit, both required.
     * The FAB always draws its own container surface, which in a 200x60 scene fills nearly the
     * full height. Distinct-colour counting finds the glyph colour added by Text inside.
     */
    @Test
    fun floatingActionButtonComposesItsClickHandlerAndContent() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import FloatingActionButton, Text
            FloatingActionButton(onClick=lambda: None, content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import FloatingActionButton
            FloatingActionButton(onClick=lambda: None, content=lambda: None)
            """.trimIndent(),
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: FAB(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "FloatingActionButton must draw its container even with no content")
        assertTrue(
            drawnColors != emptyColors,
            "FAB content lambda added no new colour over the bare container: $emptyColors vs $drawnColors",
        )
    }

    // ── 10. Tab + TabRow ──────────────────────────────────────────────────────

    /**
     * TabRow — selectedTabIndex: Int, tabs: @Composable () -> Unit.
     * Tab — selected: Boolean, onClick: Function0, text: @Composable () -> Unit.
     *
     * TabRow fills the scene with its indicator and background, so distinct-colour counting is
     * again the right tool. Tab's text slot carries the glyph colour.
     */
    @Test
    fun tabRowComposesItsTabsAndTabRendersItsTextSlot() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import TabRow, Tab, Text
            TabRow(
                selectedTabIndex=0,
                tabs=lambda: Tab(
                    selected=True,
                    onClick=lambda: None,
                    text=lambda: Text('Hi'),
                ),
            )
            """.trimIndent(),
            width = 200, height = 48,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import TabRow, Tab
            TabRow(
                selectedTabIndex=0,
                tabs=lambda: Tab(
                    selected=True,
                    onClick=lambda: None,
                    text=lambda: None,
                ),
            )
            """.trimIndent(),
            width = 200, height = 48,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: TabRow(Tab(text=Text('Hi'))) -> ${drawnColors.size} distinct colors, " +
                "empty text -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "TabRow with empty Tab must still draw its indicator and container")
        assertTrue(
            drawnColors != emptyColors,
            "Tab text lambda added no new colour over the bare tab/row: $emptyColors vs $drawnColors",
        )
    }

    // ── 11. Checkbox ──────────────────────────────────────────────────────────

    @Test
    fun checkboxRendersItsCheckedAndUncheckedStates() {
        val checked = inkOf(
            """
            from androidx.compose.material3 import Checkbox
            Checkbox(checked=True, onCheckedChange=lambda v: None)
            """.trimIndent(),
        )
        val unchecked = inkOf(
            """
            from androidx.compose.material3 import Checkbox
            Checkbox(checked=False, onCheckedChange=lambda v: None)
            """.trimIndent(),
        )
        println("compose render: Checkbox checked=$checked px, unchecked=$unchecked px")
        assertTrue(checked > 0, "Checkbox(checked=True) drew nothing")
        assertTrue(unchecked > 0, "Checkbox(checked=False) drew nothing")
        assertTrue(
            checked > unchecked,
            "checked Checkbox should have more ink than unchecked: $unchecked vs $checked",
        )
    }

    // ── 12. Switch ────────────────────────────────────────────────────────────

    /**
     * Compared pixel by pixel, not by ink count. Through material3 1.2 the two states happened to
     * cover different areas; material3 1.9 (CMP 1.11) paints the unchecked track filled and outlined
     * over exactly the checked track's footprint, so both states ink the same number of pixels and a
     * count cannot tell them apart. Which pixels, and in what colour, still can.
     */
    @Test
    fun switchRendersItsCheckedAndUncheckedStates() {
        val checked = pixelsOf(
            """
            from androidx.compose.material3 import Switch
            Switch(checked=True, onCheckedChange=lambda v: None)
            """.trimIndent(),
        )
        val unchecked = pixelsOf(
            """
            from androidx.compose.material3 import Switch
            Switch(checked=False, onCheckedChange=lambda v: None)
            """.trimIndent(),
        )
        val checkedInk = checked.count { it != BACKGROUND }
        val uncheckedInk = unchecked.count { it != BACKGROUND }
        val differing = checked.indices.count { checked[it] != unchecked[it] }
        println("compose render: Switch checked=$checkedInk px, unchecked=$uncheckedInk px, $differing px differ")
        assertTrue(checkedInk > 0, "Switch(checked=True) drew nothing")
        assertTrue(uncheckedInk > 0, "Switch(checked=False) drew nothing")
        assertTrue(differing > 0, "checked Switch rendered pixel-identical to unchecked")
    }

    // ── 13. BottomAppBar ──────────────────────────────────────────────────────

    @Test
    fun bottomAppBarComposesItsContentLambda() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import BottomAppBar, Text
            BottomAppBar(content=lambda *args: Text('hi'))
            """.trimIndent(),
            width = 200, height = 64,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import BottomAppBar
            BottomAppBar(content=lambda *args: None)
            """.trimIndent(),
            width = 200, height = 64,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: BottomAppBar(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "BottomAppBar with empty content must still draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "BottomAppBar content lambda added no new colour: $emptyColors vs $drawnColors",
        )
    }

    // ── 14. NavigationBar ──────────────────────────────────────────────────────

    @Test
    fun navigationBarComposesItsContentLambda() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import NavigationBar, Text
            NavigationBar(content=lambda *args: Text('hi'))
            """.trimIndent(),
            width = 200, height = 64,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import NavigationBar
            NavigationBar(content=lambda *args: None)
            """.trimIndent(),
            width = 200, height = 64,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: NavigationBar(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "NavigationBar with empty content must still draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "NavigationBar content lambda added no new colour: $emptyColors vs $drawnColors",
        )
    }

    // ── 15. NavigationRail ────────────────────────────────────────────────────

    @Test
    fun navigationRailComposesItsContentLambda() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import NavigationRail, Text
            NavigationRail(content=lambda *args: Text('hi'))
            """.trimIndent(),
            width = 80, height = 200,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import NavigationRail
            NavigationRail(content=lambda *args: None)
            """.trimIndent(),
            width = 80, height = 200,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: NavigationRail(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "NavigationRail with empty content must still draw")
        assertTrue(
            drawnColors != emptyColors,
            "NavigationRail content lambda added no new colour",
        )
    }

    // ── 16. ExtendedFloatingActionButton ───────────────────────────────────────

    @Test
    fun extendedFloatingActionButtonComposesItsContent() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import ExtendedFloatingActionButton, Text
            ExtendedFloatingActionButton(onClick=lambda: None, text=lambda: Text('hi'), icon=lambda: None)
            """.trimIndent(),
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import ExtendedFloatingActionButton
            ExtendedFloatingActionButton(onClick=lambda: None, text=lambda: None, icon=lambda: None)
            """.trimIndent(),
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: ExtendedFAB(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "ExtendedFAB must draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "ExtendedFAB content lambda added no new colour",
        )
    }

    // ── 17. Snackbar ──────────────────────────────────────────────────────────

    @Test
    fun snackbarComposesItsContent() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import Snackbar, Text
            Snackbar(content=lambda: Text('hi'))
            """.trimIndent(),
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import Snackbar, Text
            Snackbar(content=lambda: Text(''))
            """.trimIndent(),
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: Snackbar(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "Snackbar must draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "Snackbar content lambda added no new colour",
        )
    }

    // ── 18. AlertDialog ───────────────────────────────────────────────────────

    @Test
    fun alertDialogComposesItsButtonsAndTitle() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import AlertDialog, Text
            AlertDialog(onDismissRequest=lambda: None, confirmButton=lambda: Text('OK'), title=lambda: Text('hi'))
            """.trimIndent(),
            width = 200, height = 200,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import AlertDialog
            AlertDialog(onDismissRequest=lambda: None, confirmButton=lambda: None, title=lambda: None)
            """.trimIndent(),
            width = 200, height = 200,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: AlertDialog(content) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "AlertDialog must draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "AlertDialog content added no new colour",
        )
    }

    // ── 19. NavigationDrawerItem ──────────────────────────────────────────────

    @Test
    fun navigationDrawerItemComposesItsLabel() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import NavigationDrawerItem, Text
            NavigationDrawerItem(label=lambda: Text('hi'), selected=True, onClick=lambda: None)
            """.trimIndent(),
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import NavigationDrawerItem
            NavigationDrawerItem(label=lambda: None, selected=True, onClick=lambda: None)
            """.trimIndent(),
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: NavigationDrawerItem(label=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "NavigationDrawerItem must draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "NavigationDrawerItem label added no new colour",
        )
    }

    // ── 21. SearchBar ─────────────────────────────────────────────────────────

    @Test
    fun searchBarComposesItsContent() {
        val drawnPixels = pixelsOf(
            """
            from androidx.compose.material3 import SearchBar, Text
            SearchBar(query="hi", onQueryChange=lambda _: None, onSearch=lambda _: None, active=False, onActiveChange=lambda _: None, content=lambda *args: Text('hi'))
            """.trimIndent(),
            width = 200, height = 200,
        )
        val emptyPixels = pixelsOf(
            """
            from androidx.compose.material3 import SearchBar
            SearchBar(query="", onQueryChange=lambda _: None, onSearch=lambda _: None, active=False, onActiveChange=lambda _: None, content=lambda *args: None)
            """.trimIndent(),
            width = 200, height = 200,
        )
        val drawnColors = drawnPixels.filter { it != BACKGROUND }.toSet()
        val emptyColors = emptyPixels.filter { it != BACKGROUND }.toSet()
        println(
            "compose render: SearchBar(content=Text('hi')) -> ${drawnColors.size} distinct colors, " +
                "empty -> ${emptyColors.size} distinct colors",
        )
        assertTrue(emptyColors.isNotEmpty(), "SearchBar must draw its container")
        assertTrue(
            drawnColors != emptyColors,
            "SearchBar content added no new colour",
        )
    }

    // ── 22. Typography and Shapes ─────────────────────────────────────────────

    @Test
    fun stateObjectsArePassedToComposables() {
        // 2. DatePicker
        val datePickerInk = inkOf(
            """
            from androidx.compose.material3 import DatePicker, rememberDatePickerState
            DatePicker(state=rememberDatePickerState())
            """.trimIndent(),
            width = 400, height = 400,
        )
        assertTrue(datePickerInk > 0, "DatePicker should render")
        
        // 3. TimePicker
        val timePickerInk = inkOf(
            """
            from androidx.compose.material3 import TimePicker, rememberTimePickerState
            TimePicker(state=rememberTimePickerState())
            """.trimIndent(),
            width = 400, height = 400,
        )
        assertTrue(timePickerInk > 0, "TimePicker should render")

        val swipeInk = inkOf(
            """
            from androidx.compose.material3 import SwipeToDismissBox, rememberSwipeToDismissBoxState, Text
            SwipeToDismissBox(
                state=rememberSwipeToDismissBoxState(),
                backgroundContent=lambda: Text("bg"),
                content=lambda: Text("fg")
            )
            """.trimIndent(),
            width = 200, height = 100,
        )
        assertTrue(swipeInk > 0, "SwipeToDismissBox should render")
    }

    @Test
    fun typographyChangesFontRender() {
        val baseInk = pixelsOf(
            """
            from androidx.compose.material3 import MaterialTheme, Text
            MaterialTheme(content=lambda: Text("A"))
            """.trimIndent(),
            width = 50, height = 50
        )
        val customInk = pixelsOf(
            """
            from androidx.compose.material3 import MaterialTheme, Text, Typography
            from androidx.compose.ui.text import TextStyle
            
            ts = TextStyle.Default
            typo = Typography(ts, ts, ts, ts, ts, ts, ts, ts, ts, ts, ts, ts, ts, ts, ts)
            
            MaterialTheme(typography=typo, content=lambda: Text("A"))
            """.trimIndent(),
            width = 50, height = 50
        )
        
        val baseColors = baseInk.filter { it != BACKGROUND }.toSet()
        val customColors = customInk.filter { it != BACKGROUND }.toSet()
        
        assertTrue(baseColors.isNotEmpty(), "Base text must render")
        assertTrue(customColors.isNotEmpty(), "Custom text must render")
        assertTrue(baseColors != customColors, "MaterialTheme with custom Typography must change the text color/style")
    }

    @Test
    fun popupLayersAreNotCapturedByImageComposeScene() {
        // DropdownMenu creates a Popup which ImageComposeScene does not capture.
        val popupInk = inkOf(
            """
            from androidx.compose.material3 import DropdownMenu, Text
            DropdownMenu(
                expanded=True,
                onDismissRequest=lambda: None,
                content=lambda: Text("Menu Item")
            )
            """.trimIndent(),
            width = 200, height = 200,
        )
        println("compose render: DropdownMenu -> $popupInk pixels")
        // If it's not captured, popupInk will be 0
        assertEquals(0, popupInk, "DropdownMenu should not be captured by ImageComposeScene")
    }

    /**
     * `ColorScheme` resolves to its **constructor**, and calling it with nothing fails as a call of that
     * constructor -- not as a proxy type asking for a handle.
     *
     * This pinned the other answer until issue #73, and said why it might change: "Pinned as observed
     * rather than as expected. When this message changes, something about how classes bind changed with
     * it." What changed is exactly that. `ColorScheme`'s public constructors take `Color`, a value class,
     * so `kotlinc` compiles each to a private `<init>` behind a synthetic `DefaultConstructorMarker`
     * bridge, and the walker used to refuse every synthetic `<init>`. It now refuses only a
     * `@Deprecated(level = HIDDEN)` one, so `ColorScheme(...)` is bound -- the same way `Typography(...)`
     * already was ([typographyChangesFontRender] calls it by its class name).
     *
     * Why the constructor and not the type: in Kotlin, `ColorScheme(...)` *is* the constructor call, and
     * the binder carries Kotlin's surface (U-8). The proxy type answers "build me from a handle", which no
     * Python caller can use -- this test was the evidence -- and stays reachable as `type(value)` of
     * anything Kotlin returns. `ColorScheme()` with no arguments still fails, because no constructor
     * declares a default for its colours; the message now names the constructor's overloads.
     */
    @Test
    fun colorSchemeResolvesToItsConstructorAndABareCallNamesItsOverloads() {
        val error = try {
            Python3.exec("from androidx.compose.material3 import ColorScheme; ColorScheme()")
            null
        } catch (e: Throwable) {
            e.message ?: ""
        }
        println("compose render: ColorScheme() -> ${error?.take(120)}")
        assertTrue(error != null, "ColorScheme() succeeded, but every ColorScheme constructor requires its colours")
        assertTrue(
            "ColorScheme__" in error,
            "expected the bare call to be refused by ColorScheme's constructor overloads, got: $error",
        )
        assertTrue("handle" !in error, "ColorScheme still resolves to the proxy type: $error")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun inkOf(body: String, width: Int = 200, height: Int = 60): Int =
        pixelsOf(body, width, height).count { it != BACKGROUND }

    private fun pixelsOf(body: String, width: Int = 200, height: Int = 60): IntArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            return IntArray(width * height) { bitmap.getColor(it % width, it / width) }
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val BACKGROUND = 0
    }
}
