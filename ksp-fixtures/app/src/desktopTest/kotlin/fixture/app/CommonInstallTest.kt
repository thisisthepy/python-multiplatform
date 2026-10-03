package fixture.app

import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ROADMAP §13's first defect, closed: shared code installs the generated table.
 *
 * Every other test in this module reaches `python.multiplatform.generated.FunctionTable` by name
 * from `desktopTest`, which is a *leaf* compilation and can. Nothing here names it -- the call
 * goes through `fixture.app.installAndDescribeTable`, which lives in `commonMain` and gets the
 * same table.
 */
class CommonInstallTest {

    @BeforeTest
    fun startFromAnEmptyTable() {
        UpcallTable.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun commonCodeInstallsTheGeneratedTableWithoutNamingIt() {
        assertEquals(0, UpcallTable.callableCount, "the table must start empty or this proves nothing")

        val summary = installAndDescribeTable()

        assertTrue(UpcallTable.callableCount > 0, "commonMain installed nothing: $summary")
        assertEquals(
            setOf("io_github_thisisthepy_ksp_fixtures_library", "io_github_thisisthepy_ksp_fixtures_app"),
            UpcallTable.moduleNames,
            "the seam must install the same aggregate FunctionTable.fragments does",
        )
        assertTrue(UpcallTable.resolve("fixture.library.add").isValid)
        assertTrue(UpcallTable.resolve("fixture.app.double").isValid)
        assertEquals(7L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.add"), arrayOf(3L, 4L)))
    }

    @Test
    fun theSeamInstallsExactlyWhatTheGeneratedAggregatorHolds() {
        // `installInto()` is `UpcallTable.install(fragments)`, so the two routes must agree
        // entry for entry -- if they ever diverge, shared code is looking at a different table
        // from the one every leaf-side test asserts against.
        installGeneratedUpcallTable()
        val viaSeam = UpcallTable.callableCount to UpcallTable.classCount

        UpcallTable.clear()
        UpcallTable.install(python.multiplatform.generated.FunctionTable.fragments)
        val viaDirectReference = UpcallTable.callableCount to UpcallTable.classCount

        assertEquals(viaDirectReference, viaSeam)
    }

    @Test
    fun installingThroughTheSeamTwiceDoesNotDoubleTheTable() {
        installGeneratedUpcallTable()
        val once = UpcallTable.callableCount
        installGeneratedUpcallTable()
        assertEquals(once, UpcallTable.callableCount)
    }

    @Test
    fun theGeneratedActualIsNotItselfOfferedToPython() {
        // The `actual` is a public top-level function in the user's own package, so an
        // incremental round that fed the scanner its own previous output would put it in the
        // table -- a Python-callable that reinstalls the table underneath its caller. The
        // generator marks it `@PythonInternal` for exactly that reason.
        installGeneratedUpcallTable()
        assertFalse(UpcallTable.resolve("fixture.app.installGeneratedUpcallTable").isValid)
        assertTrue(
            UpcallTable.resolve("fixture.app.installAndDescribeTable").isValid,
            "the ordinary commonMain function beside it is still exposed -- the exclusion is the annotation, not the file",
        )
    }
}
