package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StringMarshallingTest {

    @Test
    fun testInterning() {
        val s1 = "intern_me"
        val addr1 = internedUtf8(s1)
        val addr2 = internedUtf8(s1)
        assertEquals(addr1, addr2, "Repeated interning of equal strings must return the same address")

        val s2 = "another_string"
        val addr3 = internedUtf8(s2)
        assertNotEquals(addr1, addr3, "Different strings must return different addresses")

        val read1 = ffiReadUtf8(addr1)
        assertEquals(s1, read1, "Round trip must produce same string")
    }

    @Test
    fun testScratchEncoding() {
        val s = "scratch_me"
        val addr = encodeScratchUtf8(s)
        val read = ffiReadUtf8(addr)
        assertEquals(s, read, "Scratch buffer round trip must produce same string")
    }

    @Test
    fun testNonAsciiFallback() {
        val s = "non_ascii_🔥_test_ç"
        val addr1 = internedUtf8(s)
        assertEquals(s, ffiReadUtf8(addr1), "Non-ASCII interned string round trip failed")
        
        val addr2 = encodeScratchUtf8(s)
        assertEquals(s, ffiReadUtf8(addr2), "Non-ASCII scratch string round trip failed")
    }

    @Test
    fun testMultiSlotScratchReuseLimit() {
        // Multi-slot scratch supports up to N (4) concurrent slots per thread.
        // Consecutive encodings up to N=4 must all remain valid simultaneously.
        val s0 = "scratch_slot_0"
        val s1 = "scratch_slot_1"
        val s2 = "scratch_slot_2"
        val s3 = "scratch_slot_3"

        val addr0 = encodeScratchUtf8(s0)
        val addr1 = encodeScratchUtf8(s1)
        val addr2 = encodeScratchUtf8(s2)
        val addr3 = encodeScratchUtf8(s3)

        // All 4 slots are concurrently valid
        assertEquals(s0, ffiReadUtf8(addr0), "Slot 0 must remain valid while slots 0..3 are active")
        assertEquals(s1, ffiReadUtf8(addr1), "Slot 1 must remain valid while slots 0..3 are active")
        assertEquals(s2, ffiReadUtf8(addr2), "Slot 2 must remain valid while slots 0..3 are active")
        assertEquals(s3, ffiReadUtf8(addr3), "Slot 3 must remain valid while slots 0..3 are active")

        // Encoding the 5th string (N+1) reuses slot 0, invalidating s0 at addr0.
        val s4 = "scratch_slot_4_wraparound"
        val addr4 = encodeScratchUtf8(s4)

        // addr4 holds s4
        assertEquals(s4, ffiReadUtf8(addr4), "5th encoding must return valid string for slot 0")

        // addr0 no longer holds s0 (reused/freed)
        assertNotEquals(s0, ffiReadUtf8(addr0), "Slot 0 must be invalidated after N+1 (5th) encoding")

        // Slots 1, 2, 3 must still remain valid
        assertEquals(s1, ffiReadUtf8(addr1), "Slot 1 must remain valid after 5th encoding")
        assertEquals(s2, ffiReadUtf8(addr2), "Slot 2 must remain valid after 5th encoding")
        assertEquals(s3, ffiReadUtf8(addr3), "Slot 3 must remain valid after 5th encoding")
    }
}
