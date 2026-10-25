@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi.emscripten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.wasm.unsafe.Pointer
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.native.ffi.Wasm

/**
 * The shared linear memory survives CPython growing it underneath Kotlin.
 *
 * CPython links `-sALLOW_MEMORY_GROWTH -sINITIAL_MEMORY=20971520`, so growth is not hypothetical:
 * it happens the first time anything allocates past the initial 20 MiB. A JS `TypedArray` view
 * detaches when the buffer grows; Kotlin holds the memory as a wasm *import* and does not. The
 * whole data path of this target, every `char*` read and every C string Kotlin writes, rests on
 * that, and until this test it was proved only by the standalone `wasm-experiment/` (Tests C and
 * D, now in git history), which no build ran.
 *
 * Each assertion is one half of the claim:
 *  * the memory really did grow during the test (otherwise everything below passes vacuously);
 *  * an address CPython handed out *before* the growth still reads correctly *after* it;
 *  * Kotlin can write *past the pre-growth end*, and CPython sees the write.
 */
class WasmSharedMemoryGrowthTest {

    @Test
    fun pointersHandedOutBeforeAGrowthStillReadAndWriteAfterIt() {
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                val marker = "allocated-before-the-memory-grew-é中"
                assertEquals(0, runInMain("__pmp_grow_marker = ${pyStr(marker)}"))
                val markerObj = mainGlobal("__pmp_grow_marker")
                assertNotEquals(0, markerObj, "__pmp_grow_marker missing from __main__")
                val markerUtf8 = python.native.ffi.bindings.PyUnicode_AsUTF8(markerObj)
                assertNotEquals(0, markerUtf8, "PyUnicode_AsUTF8 failed")

                val before = sharedMemoryBytes()
                // Larger than the whole memory as it stands, so no free block can satisfy it and
                // the allocator has to grow -- whatever earlier tests left behind.
                val size = before.toLong() + 16L * 1024 * 1024
                try {
                    assertEquals(0, runInMain("__pmp_grow_buf = bytearray($size)"))
                    val after = sharedMemoryBytes()
                    assertTrue(
                        after > before,
                        "the memory did not grow ($before -> $after bytes), so nothing below tests growth",
                    )

                    assertEquals(
                        marker, Wasm.readUtf8String(markerUtf8),
                        "a char* CPython returned before the growth must read the same bytes after it",
                    )

                    val buf = mainGlobal("__pmp_grow_buf")
                    assertNotEquals(0, buf)
                    val data = python.native.ffi.bindings.PyByteArray_AsString(buf)
                    assertNotEquals(0, data)
                    val last = data.toUInt().toLong() + size - 1
                    assertTrue(
                        last >= before.toLong(),
                        "the buffer's last byte ($last) should sit past the pre-growth end ($before)",
                    )
                    Pointer(last.toUInt()).storeByte(0x5A)
                    assertEquals(
                        "90", evalToString("__pmp_grow_buf[-1]"),
                        "CPython must see a byte Kotlin wrote past the old end of the memory",
                    )
                } finally {
                    runInMain("__pmp_grow_buf = None\ndel __pmp_grow_buf, __pmp_grow_marker")
                }
            }
        }
    }

    private fun pyStr(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
