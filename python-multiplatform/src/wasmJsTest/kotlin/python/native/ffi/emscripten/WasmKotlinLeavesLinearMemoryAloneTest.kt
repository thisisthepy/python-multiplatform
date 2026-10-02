@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi.emscripten

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.wasm.unsafe.Pointer
import python.multiplatform.ffi.PythonTestFixture

/**
 * Kotlin/Wasm code that never calls CPython leaves CPython's linear memory exactly as it found it --
 * neither growing it nor writing into it.
 *
 * This is the premise that makes handing Kotlin's `intrinsics.memory` import Emscripten's memory
 * safe at all (`src/wasmJsMain/README.md`, "The memory is shared because Kotlin *imports* it"):
 * Kotlin/Wasm keeps its objects on the WasmGC heap, its data segments are passive, and the only
 * thing that would put Kotlin bytes in linear memory is `withScopedMemoryAllocator` -- which assumes
 * it owns the memory from address 0, i.e. writes over Emscripten's static data. If a stdlib
 * operation ever started using linear memory behind the scenes, it would do the same, and the
 * symptom would be CPython corrupting somewhere unrelated, much later.
 *
 * The former `wasm-experiment/` checked this by printing a page count after each operation
 * (`Main.kt`'s linear-memory probe) when Kotlin owned a memory of its own. The memory is CPython's
 * now, so the page count alone would not catch a write that fits inside the existing pages; this
 * also compares the low region -- the static data and the bottom of the heap, where a
 * from-address-0 allocator lands -- byte for byte.
 */
class WasmKotlinLeavesLinearMemoryAloneTest {

    @Test
    fun kotlinOnlyWorkNeitherGrowsNorWritesTheSharedMemory() {
        PythonTestFixture.withInterpreter {
            val bytesBefore = sharedMemoryBytes()
            val lowBefore = snapshotLowMemory()

            // The workload Main.kt ran, minus the coroutine (no kotlinx.coroutines here) -- each line
            // a different part of the Kotlin/Wasm runtime: JS string interop, collections,
            // exceptions, a large primitive array, a large object graph, and the ArrayBuffer bridge.
            val text = buildString { repeat(500) { append("attribute_name_$it ") } }
            val viaJs = jsStringLength(text.toJsString())
            val joined = (1..5000).map { it.toString() }.joinToString(",").length
            val caught = try {
                throw IllegalStateException("probe")
            } catch (e: IllegalStateException) {
                e.message
            }
            val bytes = ByteArray(10 * 1024 * 1024).also { it[it.size - 1] = 1 }
            val graph = Array(100_000) { it.toString() }
            val jsArray = newUint8Array(10)

            assertEquals(text.length, viaJs)
            assertTrue(joined > 0 && caught == "probe" && bytes.last() == 1.toByte() && graph.size == 100_000)
            assertTrue(jsArray.toString().isNotEmpty())

            assertEquals(
                bytesBefore, sharedMemoryBytes(),
                "Kotlin-only work grew the linear memory it shares with CPython",
            )
            val lowAfter = snapshotLowMemory()
            val firstDiff = lowBefore.indices.firstOrNull { lowBefore[it] != lowAfter[it] }
            assertEquals(
                null, firstDiff,
                "Kotlin-only work wrote into CPython's linear memory, first at 0x" +
                    "${((firstDiff ?: 0) * 4).toString(16)} -- something on the Kotlin side is " +
                    "allocating from address 0, over Emscripten's static data",
            )
        }
    }

    private fun snapshotLowMemory(): IntArray {
        val words = LOW_REGION_BYTES / 4
        val out = IntArray(words)
        var p = Pointer(0u)
        for (i in 0 until words) {
            out[i] = p.loadInt()
            p += 4
        }
        return out
    }

    private companion object {
        /**
         * 4 MiB: the null page, Emscripten's static data (where `withScopedMemoryAllocator` was
         * measured to start) and the bottom of the heap, which `Py_Initialize` has long since
         * populated. Nothing that runs between the two snapshots touches CPython.
         */
        const val LOW_REGION_BYTES = 4 * 1024 * 1024
    }
}

private fun jsStringLength(s: JsString): Int = js("s.length")

private fun newUint8Array(n: Int): JsAny = js("new Uint8Array(n)")
