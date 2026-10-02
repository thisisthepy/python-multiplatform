package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Modifier.padding(16.dp).size(24.dp)`, built from Python, out of the real Compose jars.
 *
 * ### What this is the end of
 *
 * `docs/design/kotlin-extensions-in-python.md` §3 measured the artefact walker binding **zero**
 * declarations from all 19 Compose desktop jars, and named two gates that each produced that zero on
 * their own: the metadata-kind gate (a Kotlin top-level function hides behind a file facade whose
 * JVM name Kotlin cannot spell) and the type gate (`Modifier` is an ordinary interface, and
 * `boundaryTypeOf` had no representation for one). A third rule, dropping any name carried by more
 * than one overload, took `padding`, `size`, `background`, `border` and 29 other `Modifier` names
 * even once those were open.
 *
 * All three are gone, and this is what that buys: a chain assembled in Python, out of Compose's own
 * `androidx.compose.foundation.layout` functions, under Compose's own names.
 *
 * ### Why no `Composer` appears anywhere
 *
 * A `Modifier` extension is not a `@Composable` -- §2.6 counted exactly one function that is both,
 * out of 500 -- so `padding` is an ordinary function returning an ordinary object and needs no
 * composition to run. Composables are a separate problem with a separate blocker
 * (`docs/design/pythonx-adapter-design.md` §5.3) and nothing here touches them. This module does not even
 * apply the Compose compiler plugin.
 *
 * ### What Python actually holds
 *
 * An integer. A `Modifier` crosses as a `HandleTable` handle (`TypeTag.OBJECT`), so each link of the
 * chain is a fresh handle and **each one is a strong root until something releases it**.
 *
 * This used to leak three per run, then two once the two walked links came back wrapped so Python
 * drops their handles when it drops the chain -- **one still leaked**: `emptyModifier()` is a KSP
 * entry, and KSP emitted no `returnTypeName`, which is the gate, because `TypeTag.OBJECT` also
 * covers a `PyObject` that may itself be an `int`, and owning one of those would release a handle
 * nobody issued.
 *
 * That gap is closed: `FragmentScanner` now fills `returnTypeName` (and `paramNames`/
 * `paramTypeNames`/`paramHasDefault`) at every call site that produces a [CallableEntryModel][
 * python.multiplatform.ksp.CallableEntryModel], so a KSP-generated `TypeTag.OBJECT` result is
 * owned exactly like a walked one is. [emptyModifierGivesItsHandleBackWhenPythonDropsIt] is the
 * measured claim -- a [HandleTable.liveCount] that returns to baseline once Python drops the
 * result, not "it did not crash" -- and
 * [emptyModifierDoesNotFreeANewOwnersSlotOnADoubleRelease] pins the other half that turning
 * ownership on puts at risk: a double release must not free a slot a new owner has since taken.
 *
 * Wrapping each return in the class rendered for its own type is still `§4.1`'s proxy and still
 * does not exist: the walker emits no `ReflectedClass`, so no rendered class has ever shared a name
 * with a walked return type.
 *
 * ### The overload names
 *
 * `padding__Dp` and `size__Dp` are not decoration. `androidx.compose.foundation.layout.padding` has
 * four overloads -- `PaddingValues`, one `Dp`, two `Dp`s, four `Dp`s -- and the walker refuses to
 * pick between them (see `ArtifactScanner.disambiguateOverloads`). The suffix is the caller saying
 * which one, and `padding__Dp` is the one a reader means by "16dp of padding".
 */
class WalkedArtifactComposeModifierTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * The whole claim in one Python block.
     *
     * `emptyModifier` is this module's own (KSP); `padding__Dp` and `size__Dp` are
     * `foundation-layout-desktop-1.6.11.jar`'s, walked at build time. The assertion is Compose's own
     * structural equality against a chain `ComposeSeed.kt` builds independently, so it cannot pass
     * unless Compose's `padding` ran with `16.0` and Compose's `size` ran with `24.0`, in that
     * order, on the object Python passed in.
     */
    @Test
    fun aModifierChainIsAssembledInPythonFromTheComposeJars() {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding__Dp, size__Dp
            from fixture.artifact import emptyModifier, describeModifier, modifierElementCount
            from fixture.artifact import equalsPaddingThenSize, isTheEmptyModifier

            _empty = emptyModifier()
            assert isTheEmptyModifier(_empty), 'the handle did not resolve to Modifier itself'
            assert modifierElementCount(_empty) == 0, 'the empty modifier has elements'

            _padded = padding__Dp(_empty, 16.0)
            assert not isTheEmptyModifier(_padded), 'padding returned the receiver unchanged'
            assert modifierElementCount(_padded) == 1, (
                'padding produced ' + str(modifierElementCount(_padded)) + ' elements'
            )

            _chained = size__Dp(_padded, 24.0)
            assert modifierElementCount(_chained) == 2, (
                'the chain has ' + str(modifierElementCount(_chained)) + ' elements: ' +
                describeModifier(_chained)
            )
            assert equalsPaddingThenSize(_chained, 16.0, 24.0), (
                'the chain is not Modifier.padding(16.dp).size(24.dp): ' + describeModifier(_chained)
            )
            """.trimIndent(),
        )
    }

    /**
     * The negative half of the one above, and the reason to trust it.
     *
     * A test that only ever asserts a `True` cannot tell "Compose ran with 16dp" from "the assertion
     * never executed". This drives the same chain with a value it was not built with and requires
     * the comparison to answer `False` -- so the `assert` in the test above is a statement about the
     * argument that crossed, not about the boundary having done anything at all.
     */
    @Test
    fun theSameChainComparedAgainstADifferentPaddingDoesNotMatch() {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import padding__Dp, size__Dp
            from fixture.artifact import emptyModifier, equalsPaddingThenSize

            _chained = size__Dp(padding__Dp(emptyModifier(), 16.0), 24.0)
            assert not equalsPaddingThenSize(_chained, 15.0, 24.0), 'a 16dp chain matched 15dp'
            assert not equalsPaddingThenSize(_chained, 16.0, 25.0), 'a 24dp chain matched 25dp'
            """.trimIndent(),
        )
    }

    /**
     * The overload rule, from the Python side.
     *
     * The bare name is absent by design: four `padding` overloads would otherwise have to be
     * arbitrated between, and the one a sort order picks for `padding` is the `PaddingValues`
     * overload -- the only unmangled one, and the one a Python caller is least likely to want
     * (`docs/design/kotlin-extensions-in-python.md` §3). An `AttributeError` naming a declaration that does
     * not exist is the honest answer; a silent call to the wrong overload is not.
     */
    @Test
    fun theBareNameOfAnOverloadSetIsNotBoundAndItsMembersAre() {
        assertEquals(
            false,
            UpcallTable.resolve("androidx.compose.foundation.layout.padding").isValid,
            "four overloads must not be arbitrated down to one",
        )
        assertTrue(UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp").isValid)
        assertTrue(UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp_Dp").isValid)
        assertTrue(UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp").isValid)
        assertTrue(UpcallTable.resolve("androidx.compose.foundation.layout.padding__PaddingValues").isValid)
    }

    /**
     * The entry carries what a Python adapter needs to build a keyword-argument surface over it --
     * `docs/design/pythonx-adapter-design.md` §2.4's table, which recorded every one of these as missing.
     *
     * `padding__Dp` is a good witness for all of them at once: it is an extension (so slot 0 is a
     * receiver, not a first parameter), its declared parameter type is `Dp` while its `TypeTag` is
     * `FLOAT` (so the tag alone cannot describe it), and its `all` parameter is the name a keyword
     * call would use.
     */
    @Test
    fun aWalkedEntryCarriesItsDeclarationAndNotOnlyItsTags() {
        val padding = UpcallTable.callable(UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp"))
        assertEquals(true, padding.isExtension)
        assertEquals("androidx.compose.ui.Modifier", padding.receiverTypeName)
        assertEquals(listOf("<receiver>", "all"), padding.paramNames)
        assertEquals(listOf("androidx.compose.ui.Modifier", "androidx.compose.ui.unit.Dp"), padding.paramTypeNames)
        assertEquals("androidx.compose.ui.Modifier", padding.returnTypeName)
        // Defaults are carried and nothing acts on them: every generated body passes every
        // argument. `padding(all:)` declares none; `padding(horizontal:, vertical:)` declares two,
        // which is what makes this pair worth asserting together.
        assertEquals(listOf(false, false), padding.paramHasDefault)
        val symmetric = UpcallTable.callable(UpcallTable.resolve("androidx.compose.foundation.layout.padding__Dp_Dp"))
        assertEquals(listOf("<receiver>", "horizontal", "vertical"), symmetric.paramNames)
        assertEquals(listOf(false, true, true), symmetric.paramHasDefault)
    }

    /**
     * The measured claim behind "one still leaks" above, and the one this fix is for: a
     * [python.multiplatform.reflection.HandleTable] root count, not "it did not crash". `emptyModifier`
     * is a top-level [python.multiplatform.reflection.CallableKind.FUNCTION] returning `TypeTag.OBJECT`
     * with no receiver, so it is a KSP entry the same way [aWalkedEntryCarriesItsDeclarationAndNotOnlyItsTags]'s
     * `padding__Dp` is a walked one -- and it is what should now carry `returnTypeName`
     * (`FragmentScanner.topLevelFunctionEntry`), the field `PythonProxySource.ownedTypeOf` gates
     * ownership on.
     *
     * Both ends are checked, per `OwnedResultLifetimeTest`'s own rule: `baseline + 1` while Python still
     * holds the object is what tells "a root was taken" apart from "nothing was ever registered", and
     * only `baseline` again after `gc.collect()` is the actual leak claim.
     */
    @Test
    fun emptyModifierGivesItsHandleBackWhenPythonDropsIt() {
        // The specific field this whole change is about: `FragmentScanner.topLevelFunctionEntry`
        // filling `returnTypeName` from the declared Kotlin return type is what
        // `PythonProxySource.ownedTypeOf` gates ownership on.
        val entry = UpcallTable.callable(UpcallTable.resolve("fixture.artifact.emptyModifier"))
        assertEquals("androidx.compose.ui.Modifier", entry.returnTypeName)

        val baseline = settledBaseline()

        Python3.exec(
            """
            from fixture.artifact import emptyModifier
            _wm = emptyModifier()
            assert type(_wm).__name__ != 'int', (
                'a KSP FUNCTION returning TypeTag.OBJECT with a returnTypeName must come back '
                'wrapped, not as a bare handle int'
            )
            """.trimIndent(),
        )
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "emptyModifier() must root exactly one handle while Python still holds it",
        )

        Python3.exec("import gc\n_wm = None\ngc.collect()")
        assertEquals(
            baseline, HandleTable.liveCount,
            "the KSP entry's handle must come back once Python has dropped it -- this is the leak " +
                "this class's own KDoc records; a producer that supplies returnTypeName is the fix",
        )
    }

    /**
     * The other half of turning ownership on: a double release must not free a slot that has
     * already been handed to a new owner. [OwnedResultLifetimeTest.releasingAnOwnedResultTwiceDoesNotFreeTheSlotsNewOwner]
     * pins the general mechanism (`_PmObject.__del__` clears its own handle before releasing, and
     * `HandleTable`'s generation tag is the second line of defence); this pins it end to end through
     * the KSP-generated proxy specifically, since that is the path this change turns ownership on for.
     */
    @Test
    fun emptyModifierDoesNotFreeANewOwnersSlotOnADoubleRelease() {
        val baseline = settledBaseline()

        Python3.exec(
            """
            from fixture.artifact import emptyModifier
            _wm = emptyModifier()
            """.trimIndent(),
        )
        assertEquals(baseline + 1, HandleTable.liveCount)

        Python3.exec("_wm.__del__()")
        assertEquals(baseline, HandleTable.liveCount, "the explicit release must have done the work")

        // A fresh handle takes the slot the release above just freed. An unguarded double release
        // would free *this* object instead, silently handing one slot to two owners --
        // `agent-rules.md`'s §14 failure mode, one level up from the reference counts.
        Python3.exec("_wm_b = emptyModifier()")
        assertEquals(baseline + 1, HandleTable.liveCount)

        Python3.exec("_wm.__del__()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "the second release of the already-released handle must be a no-op, not free the new " +
                "owner's slot",
        )

        Python3.exec("import gc\n_wm = None\n_wm_b = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount)
    }

    /** [HandleTable.liveCount] with anything an earlier test left as garbage already collected. */
    private fun settledBaseline(): Int {
        Python3.exec("import gc\n_wm = None\n_wm_b = None\ngc.collect()")
        return HandleTable.liveCount
    }
}
