package python.multiplatform.reflection

import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Registration, lookup and invocation for the upcall table -- the runtime half of ROADMAP §7.
 *
 * Runtime reflection is not available (Kotlin/Native has none, GraalVM's closed world forbids
 * it), so everything here has to come from registered data. The fragments in `TestFragments.kt`
 * stand in for KSP output; if these pass against hand-written fragments they will pass against
 * generated ones, because the generator has no other way to call in.
 *
 * Red-phase note: before the implementation existed these failed to compile. A regression looks
 * different -- an assertion failure with the table present.
 */
class UpcallTableTest {

    @BeforeTest
    fun freshTable() {
        UpcallTable.clear()
    }

    @AfterTest
    fun emptyTable() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    // ---------------------------------------------------------------- registration and lookup

    @Test
    fun installMakesEveryFragmentEntryReachableByName() {
        // What the generated aggregator does: one call, every fragment, explicit references.
        UpcallTable.install(listOf(TestLibraryFragment, TestAppFragment))

        assertTrue(UpcallTable.resolve("test.lib.add").isValid)
        assertTrue(UpcallTable.resolve("test.app.Counter.increment").isValid)
        assertEquals(
            TestLibraryFragment.entries().size + TestAppFragment.entries().size,
            UpcallTable.callableCount,
        )
        assertEquals(setOf("test_library", "test_app"), UpcallTable.moduleNames)
    }

    @Test
    fun registerAddsOneFragmentAtATime() {
        // What a fragment object itself can call, for the platforms and tests that would
        // rather not go through an aggregator.
        UpcallTable.register(TestLibraryFragment)
        assertFalse(UpcallTable.resolve("test.app.Counter.increment").isValid)

        UpcallTable.register(TestAppFragment)
        assertTrue(UpcallTable.resolve("test.app.Counter.increment").isValid)
    }

    @Test
    fun registeringTheSameFragmentTwiceIsANoOp() {
        // Two aggregators, or an aggregator plus a manual call, must not double the table or
        // trip the duplicate-name check against the fragment's own entries.
        UpcallTable.register(TestLibraryFragment)
        val countAfterFirst = UpcallTable.callableCount

        UpcallTable.register(TestLibraryFragment)

        assertEquals(countAfterFirst, UpcallTable.callableCount)
        assertEquals(setOf("test_library"), UpcallTable.moduleNames)
    }

    @Test
    fun lookupOfSomethingNeverRegisteredIsNotValidRatherThanAThrow() {
        UpcallTable.install(listOf(TestLibraryFragment))

        val missing = UpcallTable.resolve("test.lib.doesNotExist")

        assertFalse(missing.isValid)
        assertEquals(CallableHandle.NONE, missing)
        // The Python side gets the raw integer back; -1 is the "no such name" value it tests
        // against, and raising AttributeError is its job, not the table's.
        assertEquals(-1L, missing.raw)
    }

    @Test
    fun lookupOnAnEmptyTableIsNotValid() {
        assertFalse(UpcallTable.resolve("anything.at.all").isValid)
        assertEquals(0, UpcallTable.callableCount)
    }

    @Test
    fun resolvingTheSameNameTwiceGivesTheSameHandle() {
        // The design's whole claim: the name is resolved once and the handle is what the proxy
        // caches. A handle that moved between calls would make caching wrong, not just slow.
        UpcallTable.install(listOf(TestLibraryFragment, TestAppFragment))

        val first = UpcallTable.resolve("test.lib.greet")
        val second = UpcallTable.resolve("test.lib.greet")

        assertEquals(first, second)
        assertSame(UpcallTable.callable(first), UpcallTable.callable(second))
        assertEquals("test.lib.greet", UpcallTable.callable(first).name)
    }

    @Test
    fun twoFragmentsClaimingOneNameIsAnError() {
        // Silently letting the last registration win would shadow a function that the user can
        // still see in their source. Fail where the cause is visible.
        UpcallTable.register(TestLibraryFragment)

        val failure = assertFailsWith<IllegalStateException> { UpcallTable.register(CollidingFragment) }

        assertTrue(
            failure.message!!.contains("test.lib.add"),
            "the message must name the colliding entry, got: ${failure.message}",
        )
    }

    @Test
    fun clearRemovesEverythingIncludingTheFragmentRecord() {
        UpcallTable.install(listOf(TestLibraryFragment, TestAppFragment))
        UpcallTable.clear()

        assertEquals(0, UpcallTable.callableCount)
        assertEquals(0, UpcallTable.classCount)
        assertTrue(UpcallTable.moduleNames.isEmpty())
        assertFalse(UpcallTable.resolve("test.lib.add").isValid)

        // and the table is reusable afterwards, which is what makes install() idempotent
        UpcallTable.install(listOf(TestLibraryFragment))
        assertTrue(UpcallTable.resolve("test.lib.add").isValid)
    }

    @Test
    fun installReplacesRatherThanAccumulates() {
        UpcallTable.install(listOf(TestLibraryFragment, TestAppFragment))
        UpcallTable.install(listOf(TestLibraryFragment))

        assertTrue(UpcallTable.resolve("test.lib.add").isValid)
        assertFalse(UpcallTable.resolve("test.app.Counter.increment").isValid)
    }

    // --------------------------------------------------------------------------- invocation

    @Test
    fun invokeThroughACachedHandleReachesTheTarget() {
        UpcallTable.install(listOf(TestLibraryFragment))
        val add = UpcallTable.resolve("test.lib.add")

        assertEquals(7L, UpcallTable.invoke(add, arrayOf(3L, 4L)))
        assertEquals(9L, UpcallTable.invoke(add, arrayOf(4L, 5L)))
    }

    @Test
    fun invokeByNameIsTheSlowPathAndAgrees() {
        UpcallTable.install(listOf(TestLibraryFragment))

        assertEquals(7L, UpcallTable.invokeByName("test.lib.add", arrayOf(3L, 4L)))
        assertEquals("Hello, world!", UpcallTable.invokeByName("test.lib.greet", arrayOf("world")))
    }

    @Test
    fun invokeByNameOnAMissingNameThrows() {
        UpcallTable.install(listOf(TestLibraryFragment))

        assertFailsWith<IllegalArgumentException> {
            UpcallTable.invokeByName("test.lib.nope", arrayOf<Any?>())
        }
    }

    @Test
    fun invokeWithAHandleTheTableNeverIssuedThrowsRatherThanIndexingOutOfBounds() {
        // The handle arrives from Python as a plain Int. A bad one must not become an array
        // read past the end of the table.
        UpcallTable.install(listOf(TestLibraryFragment))

        assertFailsWith<IllegalArgumentException> {
            UpcallTable.invoke(CallableHandle.NONE, arrayOf<Any?>())
        }
        assertFailsWith<IllegalArgumentException> {
            UpcallTable.invoke(CallableHandle(9999L), arrayOf<Any?>())
        }
        assertFailsWith<IllegalArgumentException> {
            UpcallTable.invoke(CallableHandle(-7L), arrayOf<Any?>())
        }
    }

    @Test
    fun handlesIssuedBeforeAClearDoNotResolveAfterOne() {
        UpcallTable.install(listOf(TestLibraryFragment, TestAppFragment))
        val stale = UpcallTable.resolve("test.app.Counter.increment")

        UpcallTable.install(listOf(TestLibraryFragment))

        // Index 1 exists in the new table too; without the generation check the stale handle
        // would call test.lib.greet with a Counter receiver.
        assertFailsWith<IllegalArgumentException> { UpcallTable.invoke(stale, arrayOf<Any?>(1L)) }
    }

    @Test
    fun entryMetadataSurvivesRegistration() {
        // The trampoline marshals arguments from the recorded shape; losing it silently would
        // hand Python the wrong number of arguments.
        UpcallTable.install(listOf(TestLibraryFragment, TestAppFragment))

        val add = UpcallTable.callable(UpcallTable.resolve("test.lib.add"))
        assertEquals(2, add.arity)
        assertEquals(listOf(TypeTag.INT, TypeTag.INT), add.paramTypes)
        assertEquals(TypeTag.INT, add.returnType)
        assertEquals(CallableKind.FUNCTION, add.kind)
        assertEquals(2, add.expectedArgCount, "a top-level function takes no receiver slot")

        val increment = UpcallTable.callable(UpcallTable.resolve("test.app.Counter.increment"))
        assertEquals(1, increment.arity)
        assertEquals(CallableKind.METHOD, increment.kind)
        assertTrue(increment.kind.hasReceiver)
        assertEquals(2, increment.expectedArgCount, "a method takes its receiver in args[0]")
    }

    // ------------------------------------------------------- classes, and the two halves met

    @Test
    fun classesRegisterAndResolveByName() {
        UpcallTable.install(listOf(TestAppFragment))

        val counter = assertNotNull(ClassLookup.find("test.app.Counter"))

        assertEquals("test.app.Counter", counter.name)
        assertEquals(2, UpcallTable.classCount)
    }

    @Test
    fun lookingUpAClassThatWasNeverRegisteredIsNull() {
        UpcallTable.install(listOf(TestAppFragment))

        assertNull(ClassLookup.find("test.app.NotExposed"))
        assertFailsWith<IllegalArgumentException> { ClassLookup.require("test.app.NotExposed") }
    }

    @Test
    fun everyClassMemberNameResolvesInTheCallableTable() {
        // A class descriptor listing a member the table cannot resolve would fail at the first
        // Python attribute access rather than at registration.
        UpcallTable.install(listOf(TestAppFragment))

        val counter = ClassLookup.require("test.app.Counter")
        for (member in counter.memberNames) {
            assertTrue(UpcallTable.resolve(member).isValid, "unresolved member: $member")
        }
        assertEquals(
            CallableKind.CONSTRUCTOR,
            UpcallTable.callable(UpcallTable.resolve("test.app.Counter.<init>")).kind,
        )
    }

    @Test
    fun aClassWithoutPythonFieldsReportsNoTraverse() {
        // The proxy type only installs a tp_traverse slot for classes that can hold Python
        // references; the rest must be able to say so cheaply.
        UpcallTable.install(listOf(TestAppFragment))

        assertFalse(ClassLookup.require("test.app.Counter").hasTraverse)
        assertTrue(ClassLookup.require("test.app.RefHolder").hasTraverse)
    }

    @Test
    fun traverseEnumeratesThePythonReferencesHeldByTheKotlinObject() {
        // Step 1 of CPython's cycle algorithm: tp_traverse must see through the handle into
        // the Kotlin object's PyObject-typed fields, or the cycle never collects. See
        // docs/design/object-lifetime.md.
        UpcallTable.install(listOf(TestAppFragment))
        val holder = RefHolder(first = 0xAAAA, second = 0xBBBB)
        val ref = HandleTable.register(holder)

        val visited = mutableListOf<Long>()
        ClassLookup.require("test.app.RefHolder").traverse(HandleTable.require(ref)) { visited.add(it) }

        assertEquals(listOf(0xAAAAL, 0xBBBBL), visited)

        // and a field that has been cleared is not visited -- tp_clear's effect must be visible
        holder.first = null
        visited.clear()
        ClassLookup.require("test.app.RefHolder").traverse(holder) { visited.add(it) }
        assertEquals(listOf(0xBBBBL), visited)
    }

    @Test
    fun aMethodCallGoesHandleTableThenCallableTable() {
        // End to end in the shape the trampoline uses: Python holds an object handle and a
        // callable handle, and neither one is a string.
        UpcallTable.install(listOf(TestAppFragment))

        val construct = UpcallTable.resolve("test.app.Counter.<init>")
        val counter = UpcallTable.invoke(construct, arrayOf(10L))!!
        val ref = HandleTable.register(counter)

        val increment = UpcallTable.resolve("test.app.Counter.increment")
        val result = UpcallTable.invoke(increment, arrayOf(HandleTable.require(ref), 5L))

        assertEquals(15L, result)
        assertEquals(15L, (HandleTable.require(ref) as Counter).count)

        val getter = UpcallTable.resolve("test.app.Counter.count")
        assertEquals(15L, UpcallTable.invoke(getter, arrayOf(HandleTable.require(ref))))

        val setter = UpcallTable.resolve("test.app.Counter.count=")
        UpcallTable.invoke(setter, arrayOf(HandleTable.require(ref), 99L))
        assertEquals(99L, (counter as Counter).count)

        // and when Python drops the proxy, the table stops rooting the object
        assertTrue(HandleTable.release(ref))
        assertNull(HandleTable.resolve(ref))
    }

    // ----------------------------------------- declaration kinds whose members have no receiver

    @Test
    fun staticAccessorsCarryNoReceiverSlot() {
        // A companion/object property and an enum entry are read without an instance, so unlike
        // GETTER/SETTER their args array has no receiver in slot 0. Getting that wrong shifts
        // every argument by one, which is exactly the failure the arity metadata exists to stop.
        UpcallTable.install(listOf(TestStaticsFragment))

        val getter = UpcallTable.callable(UpcallTable.resolve("test.statics.Registry.size"))
        assertEquals(CallableKind.STATIC_GETTER, getter.kind)
        assertFalse(getter.kind.hasReceiver)
        assertEquals(0, getter.expectedArgCount)

        val setter = UpcallTable.callable(UpcallTable.resolve("test.statics.Registry.size="))
        assertEquals(CallableKind.STATIC_SETTER, setter.kind)
        assertFalse(setter.kind.hasReceiver)
        assertEquals(1, setter.expectedArgCount)

        UpcallTable.invoke(UpcallTable.resolve("test.statics.Registry.size="), arrayOf(7L))
        assertEquals(7L, UpcallTable.invoke(UpcallTable.resolve("test.statics.Registry.size"), arrayOf()))
        assertEquals("pong", UpcallTable.invoke(UpcallTable.resolve("test.statics.Registry.ping"), arrayOf()))
    }

    @Test
    fun anEnumEntryResolvesToTheOneInstanceAndItsMethodsTakeItAsReceiver() {
        // The Python side builds an `enum.Enum` mirror from the entry names and reaches each
        // member through its accessor; identity has to hold or `Color.RED is Color.RED` fails.
        UpcallTable.install(listOf(TestStaticsFragment))

        val red = UpcallTable.invoke(UpcallTable.resolve("test.statics.Color.RED"), arrayOf())
        assertSame(TestColor.RED, red)
        assertSame(red, UpcallTable.invoke(UpcallTable.resolve("test.statics.Color.RED"), arrayOf()))

        val describe = UpcallTable.resolve("test.statics.Color.describe")
        assertEquals("RED/1", UpcallTable.invoke(describe, arrayOf(red)))
    }

    @Test
    fun aClassDescriptorRecordsWhichKotlinShapeItCameFrom() {
        // An interface has no constructor, an object has exactly one instance and an enum has a
        // fixed set of them: the Python side cannot pick a proxy shape from member names alone.
        UpcallTable.install(listOf(TestAppFragment, TestStaticsFragment))

        val counter = ClassLookup.require("test.app.Counter")
        assertEquals(ReflectedClassKind.CLASS, counter.kind)
        assertEquals(emptyList(), counter.enumEntryNames)

        assertEquals(ReflectedClassKind.OBJECT, ClassLookup.require("test.statics.Registry").kind)

        val color = ClassLookup.require("test.statics.Color")
        assertEquals(ReflectedClassKind.ENUM, color.kind)
        assertEquals(listOf("RED", "GREEN"), color.enumEntryNames)
    }
}
