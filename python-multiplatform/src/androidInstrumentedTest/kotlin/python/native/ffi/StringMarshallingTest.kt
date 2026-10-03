package python.native.ffi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class StringMarshallingTest {

    @Test
    fun testInterning() {
        val s1 = "intern_me"
        val addr1 = internedUtf8(s1)
        val addr2 = internedUtf8(s1)
        assertEquals("Repeated interning of equal strings must return the same address", addr1, addr2)

        val s2 = "another_string"
        val addr3 = internedUtf8(s2)
        assertNotEquals("Different strings must return different addresses", addr1, addr3)

        val read1 = ffiReadUtf8(addr1)
        assertEquals("Round trip must produce same string", s1, read1)
    }

    @Test
    fun testScratchEncoding() {
        val s = "scratch_me"
        val addr = encodeScratchUtf8(s)
        val read = ffiReadUtf8(addr)
        assertEquals("Scratch buffer round trip must produce same string", s, read)
    }

    @Test
    fun testNonAsciiFallback() {
        val s = "non_ascii_🔥_test_ç"
        val addr1 = internedUtf8(s)
        assertEquals("Non-ASCII interned string round trip failed", s, ffiReadUtf8(addr1))
        
        val addr2 = encodeScratchUtf8(s)
        assertEquals("Non-ASCII scratch string round trip failed", s, ffiReadUtf8(addr2))
    }
}
