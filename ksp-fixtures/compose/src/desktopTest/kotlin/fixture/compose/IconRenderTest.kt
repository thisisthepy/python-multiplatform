package fixture.compose

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`Icon`, drawing pixels from Python** -- the declaration `a6742a1c` pinned as unreachable, and
 * the layer that pin was really about.
 *
 * ### What the old pin actually measured
 *
 * `a6742a1c`'s `iconHasNoReachableSourceForAnyOfItsThreeRequiredImageTypes` asserted, over
 * the walked table rather than over a guess, that nothing in it returns an `ImageBitmap`, an
 * `ImageVector` or a `Painter`. That was true, and it was true for a reason that had nothing to do
 * with `Icon`: `artifactIncludePackages` named two packages and neither was where any of the three
 * is built. The pin measured this module's `build.gradle.kts`.
 *
 * Two more packages -- `androidx.compose.ui.graphics` and `androidx.compose.ui.res` -- take the walk
 * from 230 bindings to 320, and with them two of `Icon`'s three overloads become reachable. The
 * third does not, and *why* is the useful half; see
 * [imageVectorIsUnreachableBecauseItsBuilderIsANestedClassTheWalkerNeverOpens].
 *
 * ### Why a loaded image and not a constructed one
 *
 * `ImageBitmap(width, height)` is bound and Python can call it, and it is **useless for an ink
 * proof**: the pixels it allocates are zero. `Icon` draws its image through `ColorFilter.tint`,
 * which is `BlendMode.SrcIn` -- it replaces colour and *keeps alpha* -- so a zeroed bitmap draws
 * nothing however correctly every layer below worked. Measured, both ways: `hasAlpha = false` (which
 * tags the surface `OPAQUE`) draws `0` too.
 *
 * Writing pixels into one needs `Canvas.drawRect`, and that is an **instance** method.
 * `ArtifactScanner.kotlinCandidates` filters `ownerNode.methods` on `ACC_PUBLIC and ACC_STATIC`, so
 * no instance method of any class is bound anywhere in this table -- the walker's whole surface is
 * top-level functions and extensions. Loading an image is static all the way down, which is why
 * `androidx.compose.ui.res` is in the walk and why the ink proof below goes through
 * `painterResource` rather than through the constructor.
 *
 * The `ImageBitmap` overload still gets a proof of its own, and it is positional rather than
 * inked -- exactly the shape `ModifierSeededRenderTest` uses for `Spacer`, and for the same reason.
 * See [pythonBuildsAnImageBitmapAndComposeLaysTheIconOutAtThatWidth].
 */
class IconRenderTest {

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

    /**
     * The control, and a Kotlin one on purpose: the *same* resource through the *same* two
     * declarations, with no boundary anywhere in it. Two different things produce a `0` from the
     * Python test below -- the crossing failed, or the image was invisible -- and this is what tells
     * them apart.
     */
    @Test
    fun theSameResourceDrawsThroughKotlinWithNoBoundaryInvolved() {
        val ink = inkOfScene { Icon(painterResource(RESOURCE), contentDescription = "a square") }
        println("compose render: Kotlin painterResource('$RESOURCE') through Icon -> $ink px")
        assertEquals(SQUARE, ink, "the control image is not the 24x24 opaque square this file assumes")
    }

    /**
     * **The claim.** `Icon(painter_resource('opaque-square.png'), ...)` written in Python, drawing
     * pixels through `androidx.compose.material3.Icon`.
     *
     * Four things had to be true at once and none of them was:
     *
     * 1. the walk had to reach `androidx.compose.ui.res`, which is `build.gradle.kts`;
     * 2. `painterResource` is itself a `@Composable`, so its `$composer` slot has to be filled from
     *    the same `_COMPOSER` stack the composable being *rendered* uses -- a walked composable
     *    called for its **value** rather than for its emission, which nothing had done before;
     * 3. that value has to cross back as an owned `TypeTag.OBJECT` handle, and then cross **forward
     *    again** into another walked composable's slot in the same Python statement. That round trip
     *    is what `fbab1a68`/`ce1de0c3`/`a179b747` opened and what nothing had yet driven with a
     *    walked artefact on both ends;
     * 4. `painterResource`'s declared return type is exactly `androidx.compose.ui.graphics.painter
     *    .Painter`, which is what let `_coerce` match it to `Icon`'s `Painter` slot back when only
     *    the exact name could match -- see
     *    [aBitmapPainterIsAcceptedByIconsPainterSlotNowThatTheTableCarriesItsAncestry] for the rule
     *    that no longer needs it to be exact.
     *
     * The assertion is the exact pixel count and not `> 0`: 24x24 opaque, the same number the Kotlin
     * control produces. A painter that failed to attach and left an empty box, or one stretched over
     * the whole 200x60 scene, both fail this and neither would fail an "it drew something" test.
     */
    @Test
    fun pythonLoadsAPainterAndIconDrawsIt() {
        val drawn = inkOf(
            """
            from androidx.compose.ui.res import painter_resource
            from androidx.compose.material3 import Icon
            Icon(painter_resource('$RESOURCE'), content_description='a square')
            """.trimIndent(),
        )
        val blank = inkOf("pass")
        println("compose render: Icon(painter_resource('$RESOURCE')) -> $drawn px, empty body -> $blank px")
        assertEquals(0, blank, "an empty composition must draw nothing, or the measurement is not measuring")
        assertEquals(SQUARE, drawn, "Icon did not draw the 24x24 square the resource holds")
    }

    /**
     * **`Color`, opened by the same widening -- and it is a value, not a number.**
     *
     * `Color` is a `@JvmInline value class` over `ULong`, and the going-in assumption was that it
     * would therefore cross as a packed number the way `Dp` crosses as a `Float`, needing
     * `pythonx.allow_raw_primitive` before Python could carry one anywhere. It does not, and the
     * reason is **one level further down than `Color`**: `resolveKotlinType` opens `Color` happily
     * (its constructor and its `value` accessor are both public) and then cannot open the `ULong`
     * underneath, whose own constructor and `data` property are `internal`. So the descent stops
     * there and the boundary carries a **`kotlin.ULong` instance**, under the declared name
     * `androidx.compose.ui.graphics.Color` -- `Color(255, 0, 0)` comes back as an owned handle, which
     * is a better answer than a raw number: nothing has to be trusted not to reinterpret it.
     *
     * Which class the handle really holds is not a curiosity. It is what
     * `ArtifactScanner.composableDeclaredSlot` records as the slot's *carrier* and what the generated
     * thunk `CHECKCAST`s before calling `unbox-impl` -- see
     * [aComposablesColorSlotAcceptsAPythonBuiltColorAndTintsWithIt], which is the same value going the
     * other way.
     *
     * `toArgb` is the round trip, and the assertion is the exact number rather than "it did not
     * raise": `Color(255, 0, 0)` is opaque red and `toArgb` is `0xFFFF0000`, which as a Kotlin `Int`
     * is `-65536`. A handle that reached `toArgb` as some *other* colour, or a `Color` factory that
     * packed its arguments in the wrong order, both fail this.
     */
    @Test
    fun aPythonBuiltColorRoundTripsIntoAnotherWalkedCall() {
        Python3.exec(
            """
            from androidx.compose.ui.graphics import Color__Int_Int_Int_Int, to_argb

            _red = Color__Int_Int_Int_Int(255, 0, 0)
            assert type(_red).__name__ != 'int', 'Color came back as a raw number, not an owned value'
            assert type(_red)._pythonx_type_name == 'androidx.compose.ui.graphics.Color', \
                type(_red)._pythonx_type_name
            assert to_argb(_red) == -65536, ('0xFFFF0000 as a Kotlin Int', to_argb(_red))
            """.trimIndent(),
        )
    }

    /**
     * **The wall that same value used to hit, now drawn through.**
     *
     * `ebe3365f` pinned this as a refusal: `Icon`'s `tint` was tagged `INT` while
     * `lightColorScheme`'s 36 `Color` parameters -- same type, same jar -- were tagged `OBJECT`, so a
     * `Color` handle could be carried into any ordinary declaration and into no composable. Two code
     * paths in `ArtifactScanner` with two different amounts of information: an ordinary declaration
     * typed from `@Metadata`, which knows `Color` is a value class; a composable typed from the
     * **JVM descriptor**, where the same `Color` is the letter `J`.
     *
     * The composable path reads `@Metadata` for its declared slots now
     * (`ArtifactScanner.composableDeclaredSlot`), keeping the descriptor for the
     * `$composer`/`$changed`/`$default` slots that only exist there -- so both tags below are
     * `OBJECT`, and this is a render test rather than a pin.
     *
     * ### Why the assertion is the colour and not the pixel count
     *
     * `Icon` tints through `ColorFilter.tint`, which is `BlendMode.SrcIn`: it replaces colour and
     * keeps alpha. The resource is opaque **white**, so the tint is the only thing deciding what
     * comes out, and a tint that never reached Compose draws the same 576 pixels in
     * `LocalContentColor`'s black. Counting ink would pass either way; counting *red* ink cannot.
     */
    @Test
    fun aComposablesColorSlotAcceptsAPythonBuiltColorAndTintsWithIt() {
        Python3.exec(
            """
            import pythonx

            _icon = pythonx._BY_PACKAGE['androidx.compose.material3']['Icon']
            # Read `tint` by name: material3 1.9 (CMP 1.11) added `Icon(painter, tint: ColorProducer?,
            # contentDescription, modifier)`, whose slot 3 is `modifier` and whose tint is a lambda,
            # not a Color. The Color-tinted overloads are the three this test is about.
            _tints = {
                (d.param_tags[d.param_names.index('tint')], d.param_type_names[d.param_names.index('tint')])
                for d in _icon
            }
            assert _tints == {
                ('OBJECT', 'androidx.compose.ui.graphics.Color'),
                ('OBJECT', 'androidx.compose.ui.graphics.ColorProducer'),
            }, repr(_tints)

            _scheme = pythonx._BY_PACKAGE['androidx.compose.material3']['light_color_scheme'][0]
            assert _scheme.param_tags[0] == 'OBJECT', _scheme.param_tags[0]
            assert _scheme.param_type_names[0] == 'androidx.compose.ui.graphics.Color', \
                _scheme.param_type_names[0]
            """.trimIndent(),
        )
        val tinted = redOf(
            """
            from androidx.compose.ui.graphics import Color__Int_Int_Int_Int
            from androidx.compose.ui.res import painter_resource
            from androidx.compose.material3 import Icon
            Icon(painter_resource('$RESOURCE'), content_description='a square',
                 tint=Color__Int_Int_Int_Int(255, 0, 0))
            """.trimIndent(),
        )
        // The control, and the one that makes the number mean something: the *same* call with the
        // tint left out. `tint` declares a default, so this also drives the other half of the change
        // -- an omitted OBJECT slot arrives as `None` and the thunk's unwrapper turns it into a zero
        // the callee overwrites, rather than into a NullPointerException.
        val untinted = redOf(
            """
            from androidx.compose.ui.res import painter_resource
            from androidx.compose.material3 import Icon
            Icon(painter_resource('$RESOURCE'), content_description='a square')
            """.trimIndent(),
        )
        println("compose render: Icon(tint=Color(255,0,0)) -> $tinted red px, no tint -> $untinted red px")
        assertEquals(SQUARE, tinted, "the icon did not come out opaque red")
        assertEquals(0, untinted, "the default tint is red, so the tinted count proves nothing")
    }

    /**
     * **The negative control the old pin used to be.** No `Icon` overload defaults its image, so a
     * call that writes only `contentDescription` has to be refused by all three rather than draw
     * something.
     */
    @Test
    fun iconStillCannotBeCalledWithoutAnImage() {
        Python3.exec(
            """
            from androidx.compose.material3 import Icon
            try:
                Icon(content_description='nothing')
                raise AssertionError('Icon must not be callable without an image')
            except TypeError:
                pass
            """.trimIndent(),
        )
    }

    /**
     * **The `ImageBitmap` overload, proven positionally.**
     *
     * `ImageBitmap(width, height)` crosses and `Icon` accepts it -- the dispatcher picks the
     * `ImageBitmap` overload out of three on the owned value's declared type name alone. What it
     * cannot do is draw, because nothing bound can write a pixel into it (this class's KDoc). So the
     * proof is the one `Spacer` uses: `Icon` sizes itself from its painter's intrinsic size, which is
     * the bitmap's own extent, so a wider bitmap pushes the `Text` after it further right. A `width`
     * that never reached Compose -- dropped, or replaced by the 24dp fallback `Icon` uses for a
     * painter with no intrinsic size -- leaves that edge in the same place for both.
     */
    @Test
    fun pythonBuildsAnImageBitmapAndComposeLaysTheIconOutAtThatWidth() {
        val narrow = rightmostInk(iconInARow(8))
        val wide = rightmostInk(iconInARow(64))
        println("compose render: Icon(ImageBitmap(8, 12)) -> rightmost ink x=$narrow, ImageBitmap(64, 12) -> x=$wide")
        assertTrue(narrow > 0, "nothing drew at all -- the Text beside the Icon never composed")
        assertTrue(
            wide > narrow + 50,
            "a 56px wider bitmap moved the trailing Text by less than 50px: $narrow -> $wide",
        )
    }

    /**
     * The other half of that, stated so it cannot rot into a silent assumption: the bitmap `Icon`
     * lays out draws **nothing**, and that is a property of the walker's surface rather than of the
     * boundary. The day an instance method or a `ColorPainter` becomes reachable, this fails and the
     * positional proof above can become an inked one.
     */
    @Test
    fun anImageBitmapWithNoPixelsWrittenIntoItDrawsNothingBecauseNoBoundCallCanFillOne() {
        val drawn = inkOf(
            """
            from androidx.compose.ui.graphics import ImageBitmap
            from androidx.compose.material3 import Icon
            Icon(ImageBitmap(24, 24), content_description='an empty square')
            """.trimIndent(),
        )
        println("compose render: Icon(ImageBitmap(24, 24)) -> $drawn px")
        assertEquals(
            0, drawn,
            "a constructed ImageBitmap now has pixels in it -- something bound can fill one, so the " +
                "ImageBitmap overload deserves an ink proof rather than a positional one",
        )
        Python3.exec(
            """
            import pythonx
            _all = [d for table in pythonx._BY_PACKAGE.values() for decls in table.values() for d in decls]
            # The mechanism, not the symptom: nothing the walker emits can *call* into an existing
            # object. Functions are top-level or extensions, because `kotlinCandidates` filters on
            # ACC_STATIC, and static getters read a singleton's value without taking a receiver.
            # `Canvas.drawRect` is an instance method, and so is every other way to put a pixel in a
            # bitmap. The day an instance method becomes reachable, this fails and the positional
            # proof above can become an inked one.
            _kinds = sorted({d.kind for d in _all})
            assert set(_kinds) <= {'FUNCTION', 'STATIC_GETTER'}, \
                'the walker now binds a kind that may take a receiver: ' + repr(_kinds)
            """.trimIndent(),
        )
    }

    /**
     * **The nominal type check, opened.** `ebe3365f` pinned this as a refusal: `_coerce` compared an
     * owned value's declared type name against the slot's for **equality**, and the string
     * `BitmapPainter` is not the string `Painter`. `pythonx` had no hierarchy to consult, because the
     * table carried names and nothing else.
     *
     * It carries the ancestry now, on the *value* side rather than the slot side -- a slot accepts
     * unboundedly many subtypes, a produced value has one finite chain -- and `ArtifactScanner` reads
     * it from the same class files it is already walking. See `ReturnSupertypeTest` for what that
     * costs the table.
     *
     * ### Why the proof is positional and not inked
     *
     * The same reason [pythonBuildsAnImageBitmapAndComposeLaysTheIconOutAtThatWidth] gives: nothing
     * bound can write a pixel into an `ImageBitmap`, so a `BitmapPainter` over one draws nothing
     * however well every layer beneath it worked. What it *does* have is an intrinsic size, which
     * `Icon` sizes itself from -- so a 64-wide painter pushes the trailing `Text` right and an 8-wide
     * one does not. An argument that was silently dropped, or a painter that never reached Compose,
     * leaves that edge in one place for both.
     */
    @Test
    fun aBitmapPainterIsAcceptedByIconsPainterSlotNowThatTheTableCarriesItsAncestry() {
        Python3.exec(
            """
            import pythonx
            from androidx.compose.ui.graphics import ImageBitmap
            from androidx.compose.ui.graphics.painter import BitmapPainter

            _painter = BitmapPainter(ImageBitmap(24, 24))
            # The proxy still names the type the declaration returns, not its base: the ancestry is a
            # separate fact and must not overwrite the identity.
            assert type(_painter)._pythonx_type_name == 'androidx.compose.ui.graphics.painter.BitmapPainter', \
                type(_painter)._pythonx_type_name
            assert 'androidx.compose.ui.graphics.painter.Painter' in \
                pythonx._SUPERTYPES.get('androidx.compose.ui.graphics.painter.BitmapPainter', ()), \
                repr(pythonx._SUPERTYPES.get('androidx.compose.ui.graphics.painter.BitmapPainter'))
            # And it is not a licence to accept anything: an ImageBitmap is not a Painter.
            assert 'androidx.compose.ui.graphics.painter.Painter' not in \
                pythonx._SUPERTYPES.get('androidx.compose.ui.graphics.ImageBitmap', ())
            """.trimIndent(),
        )
        val narrow = rightmostInk(bitmapPainterInARow(8))
        val wide = rightmostInk(bitmapPainterInARow(64))
        println("compose render: Icon(BitmapPainter(ImageBitmap(8, 12))) -> x=$narrow, 64 wide -> x=$wide")
        assertTrue(narrow > 0, "nothing drew at all -- the Text beside the Icon never composed")
        assertTrue(
            wide > narrow + 50,
            "a 56px wider BitmapPainter moved the trailing Text by less than 50px: $narrow -> $wide",
        )
    }

    /**
     * The other side of the same rule, so that "accepts a subtype" cannot quietly become "accepts
     * anything": an `ImageBitmap` is not a `Painter`, and the overload that takes one is selected by
     * its own type rather than by falling through. Both spellings of the refusal are covered -- a
     * value whose ancestry does not contain the slot's type, and a value with no ancestry at all.
     */
    @Test
    fun aValueWhoseAncestryDoesNotReachTheSlotIsStillRefused() {
        Python3.exec(
            """
            from androidx.compose.ui.graphics import ImageBitmap, Color__Int_Int_Int_Int
            from androidx.compose.ui.graphics.painter import BitmapPainter
            from androidx.compose.material3 import Icon

            try:
                BitmapPainter(Color__Int_Int_Int_Int(255, 0, 0))
                raise AssertionError('BitmapPainter accepted a Color for its ImageBitmap parameter')
            except TypeError:
                pass

            try:
                Icon(Color__Int_Int_Int_Int(255, 0, 0), content_description='not an image')
                raise AssertionError('Icon accepted a Color as its image')
            except TypeError as _e:
                assert 'no overload of Icon accepts these arguments' in str(_e), str(_e)
            """.trimIndent(),
        )
    }

    private fun bitmapPainterInARow(width: Int): String =
        """
        from androidx.compose.foundation.layout import Row
        from androidx.compose.ui.graphics import ImageBitmap
        from androidx.compose.ui.graphics.painter import BitmapPainter
        from androidx.compose.material3 import Icon, Text
        Row(content=lambda row: (
            Icon(BitmapPainter(ImageBitmap($width, 12)), content_description='a square'),
            Text('hi'),
        ))
        """.trimIndent()

    /**
     * **`ImageVector`, the third overload, still unreachable -- with the reason moved.**
     *
     * `a6742a1c` recorded two reasons and neither survives as stated. The package is walked now. And
     * the builder is not blocked for being *stateful*: [pythonLoadsAPainterAndIconDrawsIt] carries an
     * owned handle out of one walked call and into another already, which is the whole of what a
     * builder needs.
     *
     * It is blocked because `ImageVector.Builder` is a **nested class**. `ArtifactScanner.scan`
     * skips every class whose binary name contains `$` before it reads a single member, on the
     * grounds that a nested class's binary name is not how Kotlin spells its qualified name -- so the
     * builder's constructor and its `build()` are not declined on their merits, they are never seen.
     * Two further doors are shut behind it, and both are worth naming because either would open
     * `ImageVector` without the nested-class question being answered at all:
     *
     * - `ImageVector.Builder`'s members would still need `CallableKind.METHOD`, which the walker
     *   never emits (`ACC_STATIC` only);
     * - `loadXmlImageVector` *is* static and takes an `org.xml.sax.InputSource`, a JDK type that is
     *   not on the artefact classpath, so `resolveKotlinType` cannot find a public class for it and
     *   declines the whole declaration. That is the same reason `loadImageBitmap(InputStream)` and
     *   `openResource(String)` are absent from the walk while `painterResource(String)` is present.
     *
     * What *is* bound is `ImageVectorKt.path`, the top-level extension **on** that builder -- a
     * receiver whose only possible source is the class that was skipped. That asymmetry is what this
     * asserts, because it is the thing that changes first.
     */
    @Test
    fun imageVectorIsUnreachableBecauseItsBuilderIsANestedClassTheWalkerNeverOpens() {
        Python3.exec(
            """
            import pythonx
            _vector = 'androidx.compose.ui.graphics.vector.ImageVector'
            _builder = _vector + '.Builder'
            _all = [d for table in pythonx._BY_PACKAGE.values() for decls in table.values() for d in decls]

            _producers = [d.kotlin_name for d in _all if d.return_type_name == _vector]
            assert _producers == [], \
                'something now returns an ImageVector -- the builder is reachable: ' + repr(_producers)

            # The extension *on* the builder is bound; the type that would produce one is not. That
            # gap is the nested-class skip and nothing else.
            _on_builder = [d.kotlin_name for d in _all if d.receiver_type_name == _builder]
            assert _on_builder != [], \
                'nothing is bound on ImageVector.Builder any more, so this no longer measures what it says'
            _builds = [d.kotlin_name for d in _all if d.return_type_name == _builder and not d.is_extension]
            assert _builds == [], 'a Builder became constructible: ' + repr(_builds)

            # And the static route, declined for its parameter rather than for being nested.
            assert 'load_xml_image_vector' not in pythonx._BY_PACKAGE.get('androidx.compose.ui.res', {}), \
                'loadXmlImageVector is bound now -- org.xml.sax.InputSource became resolvable'
            """.trimIndent(),
        )
    }

    /**
     * **Who owns the values a render builds.** `a179b747`'s rule on the two walked results that are
     * now on the critical path of a render: Kotlin registers the handle and does **not** release it;
     * the Python proxy's `__del__` does.
     */
    @Test
    fun aPythonHeldImageBitmapRootsExactlyOneHandleAndGivesItBack() {
        val baseline = settledBaseline()
        Python3.exec(
            """
            from androidx.compose.ui.graphics import ImageBitmap
            _bmp = ImageBitmap(24, 24)
            assert type(_bmp).__name__ != 'int', 'a walked OBJECT result must come back owned, not as a bare handle'
            """.trimIndent(),
        )
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "ImageBitmap() must root exactly one handle while Python still holds it",
        )

        Python3.exec("import gc\n_bmp = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount, "the bitmap's handle never came back")
    }

    /**
     * The double release, stated apart from the leak, because a leak test alone passes one
     * (`agent-rules` §14): a second owned handle takes the slot the first freed, and repeating the
     * first's release must not free it.
     */
    @Test
    fun releasingABitmapTwiceDoesNotFreeTheSlotItsSuccessorTookOver() {
        val baseline = settledBaseline()

        Python3.exec("from androidx.compose.ui.graphics import ImageBitmap\n_bmp = ImageBitmap(24, 24)")
        assertEquals(baseline + 1, HandleTable.liveCount)

        Python3.exec("_bmp.__del__()")
        assertEquals(baseline, HandleTable.liveCount, "the explicit release must have done the work")

        Python3.exec("_bmp_b = ImageBitmap(8, 8)")
        assertEquals(baseline + 1, HandleTable.liveCount)

        Python3.exec("_bmp.__del__()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "the second release of an already-released handle must be a no-op, not free the new owner's slot",
        )

        Python3.exec("import gc\n_bmp = None\n_bmp_b = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount)
    }

    /**
     * The chain, which is what a builder pattern is: two owned handles alive at once and the second
     * derived from the first. `BitmapPainter(ImageBitmap(...))` is that shape with a real walked call
     * on each end, and both have to come back -- a chain that released the intermediate early would
     * hand `BitmapPainter` a freed slot, and one that never released it would leak per link.
     */
    @Test
    fun aChainOfTwoOwnedResultsRootsTwoHandlesAndGivesBothBack() {
        val baseline = settledBaseline()
        Python3.exec(
            """
            from androidx.compose.ui.graphics import ImageBitmap
            from androidx.compose.ui.graphics.painter import BitmapPainter
            _bmp = ImageBitmap(24, 24)
            _painter = BitmapPainter(_bmp)
            """.trimIndent(),
        )
        assertEquals(
            baseline + 2, HandleTable.liveCount,
            "a two-link chain must root exactly one handle per link",
        )
        Python3.exec("import gc\n_bmp = None\ngc.collect()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "dropping the intermediate must free exactly its own handle and not the painter's",
        )
        Python3.exec("import gc\n_painter = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount, "the painter's handle never came back")
    }

    /**
     * The render path's own lifetime, and the one that would be invisible in a single frame: the
     * painter is loaded *inside* the composition body, so every composition pass creates one and
     * nothing in Compose's slot table hands it back. `liveCount` has to return to where it started
     * once Python drops its name.
     */
    @Test
    fun aRenderThatLoadsItsOwnPainterLeavesNoHandleBehind() {
        val baseline = settledBaseline()
        val drawn = inkOf(
            """
            from androidx.compose.ui.res import painter_resource
            from androidx.compose.material3 import Icon
            _held = painter_resource('$RESOURCE')
            Icon(_held, content_description='a square')
            """.trimIndent(),
        )
        assertEquals(SQUARE, drawn, "the icon did not draw, so nothing can be concluded about its handles")
        Python3.exec("import gc\n_held = None\ngc.collect()")
        assertEquals(
            baseline, HandleTable.liveCount,
            "the composition kept a handle Python had already dropped",
        )
    }

    private fun iconInARow(width: Int): String =
        """
        from androidx.compose.foundation.layout import Row
        from androidx.compose.ui.graphics import ImageBitmap
        from androidx.compose.material3 import Icon, Text
        Row(content=lambda row: (
            Icon(ImageBitmap($width, 12), content_description='a square'),
            Text('hi'),
        ))
        """.trimIndent()

    /** [HandleTable.liveCount] with anything an earlier test left as garbage already collected. */
    private fun settledBaseline(): Int {
        Python3.exec(
            "import gc\n" +
                "for _n in ('_bmp', '_bmp_b', '_held', '_painter'):\n" +
                "    globals().pop(_n, None)\n" +
                "gc.collect()",
        )
        return HandleTable.liveCount
    }

    private fun inkOf(body: String): Int = inkOfScene { PythonComposition(body) }

    /** How many pixels came out **opaque red**, which is what a tint that arrived produces and what
     * one that was dropped cannot: `Icon`'s `SrcIn` filter replaces the resource's white. */
    private fun redOf(body: String): Int {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            var red = 0
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getColor(x, y) == OPAQUE_RED) red++
                }
            }
            return red
        } finally {
            scene.close()
        }
    }

    private fun rightmostInk(body: String): Int {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonComposition(body)
        }
        try {
            val bitmap = Bitmap.makeFromImage(scene.render())
            var rightmost = -1
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (bitmap.getColor(x, y) != BACKGROUND && x > rightmost) rightmost = x
                }
            }
            return rightmost
        } finally {
            scene.close()
        }
    }

    private fun inkOfScene(content: @Composable () -> Unit): Int {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f), content = content)
        try {
            return inkOfImage(scene.render())
        } finally {
            scene.close()
        }
    }

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

    private companion object {
        /** `ImageComposeScene` clears to transparent black. */
        const val BACKGROUND = 0

        /**
         * 24x24, every pixel opaque white, checked in beside this file. Opaque because `Icon` tints
         * with `SrcIn` and keeps alpha; white so that the tint is the only thing deciding the colour
         * that comes out.
         */
        const val RESOURCE = "opaque-square.png"

        /** What a 24dp square covers at density 1. */
        const val SQUARE = 24 * 24

        /** `Color(255, 0, 0)` as `Bitmap.getColor` reports it: `0xFFFF0000`. */
        const val OPAQUE_RED = 0xFFFF0000.toInt()
    }
}
