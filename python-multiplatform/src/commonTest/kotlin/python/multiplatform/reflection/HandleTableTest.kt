package python.multiplatform.reflection

import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class Holder(val tag: String)

/**
 * The Python -> Kotlin half of lifetime: Python cannot hold a Kotlin object reference at all
 * (invisible to the JVM GC, a raw pointer on Kotlin/Native, unstorable in linear memory on
 * WASM), so it holds an integer and [HandleTable] holds the object. See
 * `docs/design/object-lifetime.md`.
 *
 * The table is therefore a GC root and leaks by construction until something releases. These
 * tests pin the release path, which is the only thing standing between this design and a leak,
 * and the staleness rule, which is the only thing standing between slot reuse and resolving a
 * handle to the wrong object.
 *
 * Red-phase note: before `HandleTable.kt` existed these failed to *compile*, which is a
 * different signal from a regression -- a regression fails an assertion with the table present.
 */
class HandleTableTest {

    @BeforeTest
    fun clearTable() = HandleTable.releaseAll()

    @AfterTest
    fun dropRoots() = HandleTable.releaseAll()

    @Test
    fun registerHandsOutAHandleThatResolvesBack() {
        val obj = Holder("a")
        val ref = HandleTable.register(obj)

        assertTrue(ref.isValid, "a freshly registered handle must be valid")
        assertSame(obj, HandleTable.resolve(ref), "the table must hand back the identical object")
        assertEquals(1, HandleTable.liveCount)
    }

    @Test
    fun theNullHandleIsZeroAndNeverIssued() {
        // 0 crosses the FFI boundary as "no object". Issuing it would make a live handle
        // indistinguishable from a missing one on the Python side.
        assertFalse(ObjectReference.NONE.isValid)
        assertEquals(0L, ObjectReference.NONE.raw)
        assertNull(HandleTable.resolveRaw(0L))

        repeat(32) {
            val ref = HandleTable.register(Holder("h$it"))
            assertNotEquals(0L, ref.raw, "handle 0 is reserved for null")
        }
    }

    @Test
    fun distinctObjectsGetDistinctHandles() {
        val a = HandleTable.register(Holder("a"))
        val b = HandleTable.register(Holder("b"))

        assertNotEquals(a, b)
        assertEquals("a", (HandleTable.resolve(a) as Holder).tag)
        assertEquals("b", (HandleTable.resolve(b) as Holder).tag)
        assertEquals(2, HandleTable.liveCount)
    }

    @Test
    fun registeringOneObjectTwiceYieldsTwoIndependentlyReleasableHandles() {
        // Registration is not interning. Two Python proxies over the same Kotlin object each
        // own a reference and each must be able to die without killing the other.
        val obj = Holder("shared")
        val first = HandleTable.register(obj)
        val second = HandleTable.register(obj)

        assertNotEquals(first, second)
        assertTrue(HandleTable.release(first))
        assertNull(HandleTable.resolve(first))
        assertSame(obj, HandleTable.resolve(second), "releasing one handle must not affect the other")
    }

    @Test
    fun releaseDropsTheEntry() {
        val ref = HandleTable.register(Holder("a"))
        assertEquals(1, HandleTable.liveCount)

        assertTrue(HandleTable.release(ref), "the first release owns the entry")

        assertNull(HandleTable.resolve(ref))
        assertEquals(0, HandleTable.liveCount, "a released handle must not be counted as a root")
    }

    @Test
    fun doubleReleaseIsANoOpAndSaysSo() {
        // Python's tp_dealloc can only be trusted to run once, but a buggy binding, an
        // interpreter shutdown, or an explicit close() can all reach here twice. The second
        // one must not free a slot that has since been handed to someone else.
        val ref = HandleTable.register(Holder("a"))

        assertTrue(HandleTable.release(ref))
        assertFalse(HandleTable.release(ref), "the second release must report that it did nothing")
        assertFalse(HandleTable.release(ref))
        assertEquals(0, HandleTable.liveCount)
    }

    @Test
    fun aReleasedHandleNeverResolvesToTheObjectThatTookItsSlot() {
        // The failure this exists to prevent: slot reuse silently aliasing a stale handle onto
        // a fresh object. Python would then call methods on the wrong Kotlin instance, and
        // nothing would report an error.
        val stale = HandleTable.register(Holder("old"))
        HandleTable.release(stale)

        val fresh = HandleTable.register(Holder("new"))

        assertNotEquals(stale, fresh, "the reused slot must be handed out under a new handle value")
        assertNull(HandleTable.resolve(stale), "the stale handle must resolve to nothing")
        assertEquals("new", (HandleTable.resolve(fresh) as Holder).tag)
        assertFalse(HandleTable.release(stale), "releasing a stale handle must not free the live entry")
        assertEquals("new", (HandleTable.resolve(fresh) as Holder).tag)
    }

    @Test
    fun releasedSlotsAreReusedSoTheTableDoesNotGrowWithoutBound() {
        // Lookup is meant to be an array index; that only stays true if the backing array is
        // bounded by the number of *live* handles rather than by the number ever issued.
        // Measured as growth, not as an absolute. `releaseAll()` in @BeforeTest returns slots to
        // the free list but cannot shrink the backing array -- never shrinking is the point of a
        // slot table -- so `slotCount` still carries the high-water mark of every earlier test in
        // the run. Asserting `slotCount == 1` passed alone and failed at 32 in the suite.
        val slotsBefore = HandleTable.slotCount

        repeat(100) { HandleTable.release(HandleTable.register(Holder("n$it"))) }

        assertEquals(0, HandleTable.liveCount)
        assertTrue(
            HandleTable.slotCount <= slotsBefore + 1,
            "100 sequential register/release pairs must reuse a slot rather than allocate 100 " +
                "(slots before: $slotsBefore, after: ${HandleTable.slotCount})"
        )
    }

    @Test
    fun aHandleTheTableNeverIssuedResolvesToNothing() {
        assertNull(HandleTable.resolveRaw(0x7FFF_FFFFL))
        assertNull(HandleTable.resolveRaw(-1L))
        assertNull(HandleTable.resolveRaw(Long.MAX_VALUE))
        assertFalse(HandleTable.release(ObjectReference(0x1234_5678L)))
    }

    @Test
    fun requireThrowsWhereResolveReturnsNull() {
        // The trampolines want the loud version: an unresolvable handle there means the
        // Python side outlived its own object, and continuing would corrupt something later.
        val ref = HandleTable.register(Holder("a"))
        HandleTable.release(ref)

        assertFailsWith<IllegalStateException> { HandleTable.require(ref) }
        assertFailsWith<IllegalStateException> { HandleTable.require(ObjectReference.NONE) }
    }

    @Test
    fun releaseAllDropsEveryRoot() {
        val refs = (0 until 16).map { HandleTable.register(Holder("n$it")) }
        assertEquals(16, HandleTable.liveCount)

        HandleTable.releaseAll()

        assertEquals(0, HandleTable.liveCount)
        refs.forEach { assertNull(HandleTable.resolve(it)) }
    }

    @Test
    fun rawRoundTripsThroughTheBoundaryRepresentation() {
        // Only the Long crosses; ObjectReference is a value class that must survive the trip.
        val obj = Holder("a")
        val ref = HandleTable.register(obj)

        assertSame(obj, HandleTable.resolveRaw(ref.raw))
        assertEquals(ref, ObjectReference(ref.raw))
    }
}
