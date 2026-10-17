package fixture.app

import fixture.library.Counter
import fixture.library.RefHolder
import python.multiplatform.ffi.Python3
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.toRawValue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * This is the whole point of ROADMAP §7's generator half: everything
 * `python.multiplatform.reflection.UpcallTableTest` proves against hand-written fragments in
 * `TestFragments.kt`, proved here against a `FunctionTable` that KSP actually generated from
 * `python-multiplatform-ksp/fixtures/library`'s and this module's own sources -- no hand-written fragment anywhere in
 * this file.
 */
class GeneratedTableTest {

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
    fun everyModuleParticipatesInTheAggregatedTable() {
        // Proves the two-round aggregation: the app's own fragment (round 1) and the library's
        // (found on the compiled classpath in round 2) both land in one FunctionTable.
        //
        // The names are what `python-multiplatform-gradle-plugin` derives from each module's
        // group and path -- neither fixture states a module name any more, and the group is in
        // there because every fragment object in every artifact shares one package.
        assertEquals(
            setOf("io_github_thisisthepy_python_multiplatform_ksp_fixtures_library", "io_github_thisisthepy_python_multiplatform_ksp_fixtures_app"),
            UpcallTable.moduleNames,
        )
        assertTrue(UpcallTable.resolve("fixture.library.add").isValid)
        assertTrue(UpcallTable.resolve("fixture.app.double").isValid)
        assertTrue(UpcallTable.resolve("fixture.app.triple").isValid, "every file in a module feeds its one fragment")
    }

    @Test
    fun topLevelFunctionsInvokeAndReturnCorrectly() {
        val add = UpcallTable.resolve("fixture.library.add")
        assertEquals(7L, UpcallTable.invoke(add, arrayOf(3L, 4L)))

        val greet = UpcallTable.resolve("fixture.library.greet")
        assertEquals("Hello, world!", UpcallTable.invoke(greet, arrayOf("world")))

        val double = UpcallTable.resolve("fixture.app.double")
        assertEquals(20L, UpcallTable.invoke(double, arrayOf(10L)))
    }

    @Test
    fun narrowerIntParametersAndReturnsRoundTripThroughTheBoundaryLongRepresentation() {
        // fun scale(value: Int, by: Int): Int -- the generator must narrow the Long the boundary
        // hands it down to Int for the call, then widen the Int result back to Long.
        val scale = UpcallTable.resolve("fixture.library.scale")
        assertTrue(scale.isValid)

        val result = UpcallTable.invoke(scale, arrayOf(6L, 7L))

        assertEquals(42L, result)
        assertTrue(result is Long, "an Int-returning function must still cross as the boundary's Long")
    }

    @Test
    fun unitReturningFunctionCrossesAsUnit() {
        val noArgs = UpcallTable.resolve("fixture.library.noArgs")
        assertEquals(Unit, UpcallTable.invoke(noArgs, arrayOf()))
    }

    @Test
    fun pythonInternalExcludesTheAnnotatedFunctionFromTheTable() {
        assertFalse(UpcallTable.resolve("fixture.library.hiddenFromPython").isValid)
    }

    @Test
    fun pythonInternalOnAWholeClassExcludesItAndItsMembers() {
        assertFalse(UpcallTable.resolve("fixture.library.HiddenClass.<init>").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.HiddenClass.shouldNeverBeReachable").isValid)
        assertNull(ClassLookup.find("fixture.library.HiddenClass"))
    }

    @Test
    fun pythonInternalOnOneMethodExcludesOnlyThatMethod() {
        assertFalse(UpcallTable.resolve("fixture.library.Counter.hiddenMethod").isValid)
        assertTrue(UpcallTable.resolve("fixture.library.Counter.increment").isValid)
    }

    @Test
    fun aGeneratedClassRoundTripsConstructMethodGetterAndSetterThroughTheHandleTable() {
        // The exact end-to-end shape UpcallTableTest.aMethodCallGoesHandleTableThenCallableTable
        // pins against the hand-written TestAppFragment -- here it is the generated one.
        val construct = UpcallTable.resolve("fixture.library.Counter.<init>")
        val counter = UpcallTable.invoke(construct, arrayOf(10L))!!
        val ref = HandleTable.register(counter)

        val increment = UpcallTable.resolve("fixture.library.Counter.increment")
        val incremented = UpcallTable.invoke(increment, arrayOf(HandleTable.require(ref), 5L))
        assertEquals(15L, incremented)

        val label = UpcallTable.resolve("fixture.library.Counter.label")
        assertEquals("n=15", UpcallTable.invoke(label, arrayOf(HandleTable.require(ref), "n=")))

        val getter = UpcallTable.resolve("fixture.library.Counter.count")
        assertEquals(15L, UpcallTable.invoke(getter, arrayOf(HandleTable.require(ref))))

        val setter = UpcallTable.resolve("fixture.library.Counter.count=")
        UpcallTable.invoke(setter, arrayOf(HandleTable.require(ref), 99L))
        assertEquals(99L, (counter as Counter).count)

        assertTrue(HandleTable.release(ref))
        assertNull(HandleTable.resolve(ref))
    }

    @Test
    fun everyGeneratedClassMemberNameResolvesInTheCallableTable() {
        val counter = assertNotNull(ClassLookup.find("fixture.library.Counter"))
        for (member in counter.memberNames) {
            assertTrue(UpcallTable.resolve(member).isValid, "unresolved generated member: $member")
        }
    }

    @Test
    fun aClassWithoutPyObjectFieldsReportsNoTraverse() {
        assertFalse(ClassLookup.require("fixture.library.Counter").hasTraverse)
    }

    @Test
    fun aClassWithPyObjectFieldsGetsAGeneratedTraverseThatVisitsTheirRawPointers() {
        // docs/design/object-lifetime.md's "Cycle collection is part of the table's job": the generator
        // must find PyObject-typed fields on RefHolder and emit a traverse function that visits
        // their pointer value, with no reflection at runtime.
        assertTrue(ClassLookup.require("fixture.library.RefHolder").hasTraverse)

        // Real objects, not made-up addresses. A `PyObject` registers a cleaner that calls
        // `Py_DecRef(pointer)` once the JVM collects it, whatever the test meant by it. This used to
        // wrap 0xAAAA and 0xBBBB with `borrowed = false`; the test passed, and the first collection
        // after it in the same JVM -- any test that ran 10,000 upcalls or called System.gc() --
        // died in `Py_XDECREF` on the Cleaner thread with si_addr 0xaaaa (issue #98).
        //
        // `PyObject_CallNoArgs` returns a new reference, and `invoke()` builds the wrapper with
        // `borrowed = false`, so each wrapper owns exactly the reference its cleaner gives back.
        Python3.initialize(silent = true)
        val objectType = Python3.import("builtins").getAttr("object")
        val primary = objectType.invoke()
        val secondary = objectType.invoke()
        val primaryAddress = primary.pointer.toRawValue()
        val secondaryAddress = secondary.pointer.toRawValue()
        val holder = RefHolder(primary, secondary)

        val visited = mutableListOf<Long>()
        ClassLookup.require("fixture.library.RefHolder").traverse(holder) { visited.add(it) }

        assertEquals(listOf(primaryAddress, secondaryAddress), visited)

        holder.primary = null
        visited.clear()
        ClassLookup.require("fixture.library.RefHolder").traverse(holder) { visited.add(it) }
        assertEquals(listOf(secondaryAddress), visited)
    }
}
