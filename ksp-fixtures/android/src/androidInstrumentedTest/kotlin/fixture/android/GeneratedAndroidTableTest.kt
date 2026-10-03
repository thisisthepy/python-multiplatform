package fixture.android

import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * On-device instrumented execution of ksp-fixtures:android upcall table tests.
 */
class GeneratedAndroidTableTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(FunctionTable.fragments)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun theAndroidModulesFragmentIsInTheAggregatedTable() {
        assertEquals(setOf("io_github_thisisthepy_ksp_fixtures_android"), UpcallTable.moduleNames)
        assertTrue(UpcallTable.resolve("fixture.android.androidDouble").isValid)
    }

    @Test
    fun topLevelFunctionsInvokeAndReturnCorrectly() {
        assertEquals(20L, UpcallTable.invoke(UpcallTable.resolve("fixture.android.androidDouble"), arrayOf(10L)))
        assertEquals(
            "Hello from Android, world!",
            UpcallTable.invoke(UpcallTable.resolve("fixture.android.androidGreet"), arrayOf("world")),
        )
        assertEquals(Unit, UpcallTable.invoke(UpcallTable.resolve("fixture.android.androidNoArgs"), arrayOf()))
    }

    @Test
    fun narrowerIntParametersAndReturnsRoundTripThroughTheBoundaryLongRepresentation() {
        val scale = UpcallTable.resolve("fixture.android.androidScale")
        val result = UpcallTable.invoke(scale, arrayOf(6L, 7L))
        assertEquals(42L, result)
        assertTrue(result is Long, "an Int-returning function must still cross as the boundary's Long")
    }

    @Test
    fun pythonInternalExcludesTheAnnotatedFunctionFromTheTable() {
        assertFalse(UpcallTable.resolve("fixture.android.androidHidden").isValid)
    }

    @Test
    fun aGeneratedClassRoundTripsConstructMethodGetterAndSetterThroughTheHandleTable() {
        val counter = UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.<init>"), arrayOf(10L))!!
        val ref = HandleTable.register(counter)

        assertEquals(
            15L,
            UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.increment"), arrayOf(HandleTable.require(ref), 5L)),
        )
        assertEquals(
            "n=15",
            UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.label"), arrayOf(HandleTable.require(ref), "n=")),
        )
        assertEquals(
            15L,
            UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.count"), arrayOf(HandleTable.require(ref))),
        )

        UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.count="), arrayOf(HandleTable.require(ref), 99L))
        assertEquals(99L, (counter as AndroidCounter).count)

        assertTrue(HandleTable.release(ref))
        assertNull(HandleTable.resolve(ref))
    }

    @Test
    fun everyGeneratedClassMemberNameResolvesInTheCallableTable() {
        val counter = assertNotNull(ClassLookup.find("fixture.android.AndroidCounter"))
        for (member in counter.memberNames) {
            assertTrue(UpcallTable.resolve(member).isValid, "unresolved generated member: $member")
        }
    }

    @Test
    fun aVarWithAPrivateSetterIsExposedReadOnlyHereToo() {
        val counter = UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.<init>"), arrayOf(3L))!!
        UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.label"), arrayOf(counter, "n="))

        assertEquals(
            "n=3",
            UpcallTable.invoke(UpcallTable.resolve("fixture.android.AndroidCounter.lastLabel"), arrayOf(counter)),
        )
        assertFalse(UpcallTable.resolve("fixture.android.AndroidCounter.lastLabel=").isValid)
        assertFalse(
            ClassLookup.require("fixture.android.AndroidCounter").memberNames
                .contains("fixture.android.AndroidCounter.lastLabel="),
        )
    }

    @Test
    fun commonCodeInstallsTheGeneratedTableThroughTheSeam() {
        UpcallTable.clear()
        assertEquals(0, UpcallTable.callableCount)

        val count = installAndCountEntries()

        assertTrue(count > 0)
        assertEquals(setOf("io_github_thisisthepy_ksp_fixtures_android"), UpcallTable.moduleNames)
        assertTrue(UpcallTable.resolve("fixture.android.androidDouble").isValid)
        assertFalse(
            UpcallTable.resolve("fixture.android.installGeneratedUpcallTable").isValid,
            "the generated actual carries @PythonInternal and must not be offered to Python",
        )
    }
}
