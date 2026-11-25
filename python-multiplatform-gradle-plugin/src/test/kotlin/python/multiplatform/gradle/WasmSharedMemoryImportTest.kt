package python.multiplatform.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC T-7 (#175 step 1): the rewrite of Kotlin/Wasm's `intrinsics.memory` import, on hand-assembled
 * modules shaped like a Kotlin/Wasm link (the import kinds Kotlin emits around the memory: functions
 * from `cpython.mjs`, a global with a WasmGC reference type, a tag) and like CPython's pthreads build
 * (`env.memory`, shared).
 *
 * The fixtures are built here byte by byte rather than read from a linked Kotlin module, because no
 * Kotlin link runs in this suite; the real link is checked by the same code on every
 * `wasmJsNodeTest` / webpack run (the shape check fails the build).
 */
class WasmSharedMemoryImportTest {

    private val kotlinLimits = bytes(0x00, 0x00)
    private val sharedLimits = bytes(0x03, 0x00, 0x80, 0x80, 0x04)

    @Test
    fun kotlinsMemoryImportIsRewrittenToSharedAndNothingElseChanges() {
        val original = kotlinModule(kotlinLimits)
        val result = assertIs<WasmSharedMemoryImport.Rewritten>(WasmSharedMemoryImport.rewrite(original))
        val expected = kotlinModule(sharedLimits)
        assertContentEquals(expected, result.module, "only the limits and the section size may change")
        assertEquals(expected.size - original.size, result.shift)
        assertEquals(true, WasmSharedMemoryImport.hasSharedMemory(result.module))
        assertEquals(false, WasmSharedMemoryImport.hasSharedMemory(original))
    }

    @Test
    fun aRewrittenModuleIsRecognisedAndLeftAlone() {
        val once = (WasmSharedMemoryImport.rewrite(kotlinModule(kotlinLimits)) as WasmSharedMemoryImport.Rewritten).module
        assertEquals(WasmSharedMemoryImport.AlreadyShared, WasmSharedMemoryImport.rewrite(once))
    }

    @Test
    fun aPaddedSectionSizeKeepsItsWidthSoLaterOffsetsMoveOnlyByTheLimits() {
        // The limits grow by 3 bytes whatever the size's width, so later offsets always move by at
        // least 3; a padded size has room for the new size, so they move by exactly 3, not 3 + 1.
        val original = kotlinModule(kotlinLimits, sizeWidth = 5)
        val result = assertIs<WasmSharedMemoryImport.Rewritten>(WasmSharedMemoryImport.rewrite(original))
        assertEquals(3, result.shift, "only the 3 added limit bytes may move later offsets")
        assertEquals(original.size + 3, result.module.size)
        assertContentEquals(kotlinModule(sharedLimits, sizeWidth = 5), result.module)
    }

    @Test
    fun aSizeThatCrossesALeb128WidthMovesLaterOffsetsByFour() {
        // Content of 125 bytes takes one size byte; +3 makes 128, which takes two: 3 + 1.
        val original = kotlinModule(kotlinLimits, padTo = 125)
        val result = assertIs<WasmSharedMemoryImport.Rewritten>(WasmSharedMemoryImport.rewrite(original))
        assertEquals(4, result.shift)
        assertContentEquals(kotlinModule(sharedLimits, padTo = 125), result.module)
    }

    @Test
    fun checkingWithoutRewritingLeavesKotlinsShapeAlone() {
        assertNull(WasmSharedMemoryImport.rewrite(kotlinModule(kotlinLimits), makeShared = false))
    }

    @Test
    fun anyOtherShapeFailsTheBuildByName() {
        val cases = mapOf(
            "a defined memory" to module(section(5, vec(1, bytes(0x00, 0x01)))),
            "two memory imports" to module(section(2, vec(2, memoryImport("intrinsics", "memory", kotlinLimits), memoryImport("intrinsics", "memory2", kotlinLimits)))),
            "an import plus a defined memory" to module(
                section(2, vec(1, memoryImport("intrinsics", "memory", kotlinLimits))),
                section(5, vec(1, bytes(0x00, 0x01))),
            ),
            "another name" to module(section(2, vec(1, memoryImport("env", "memory", kotlinLimits)))),
            "other limits" to module(section(2, vec(1, memoryImport("intrinsics", "memory", bytes(0x01, 0x00, 0x10))))),
            "a 64-bit memory" to module(section(2, vec(1, memoryImport("intrinsics", "memory", bytes(0x04, 0x00))))),
            "an unknown import kind" to module(section(2, vec(1, name("m") + name("x") + bytes(0x07, 0x00)))),
            "a section size that lies" to module(section(2, vec(1, memoryImport("intrinsics", "memory", kotlinLimits)) + bytes(0x00))),
            "no memory" to module(section(2, vec(1, funcImport("cpython.mjs", "Py_IncRef")))),
            "not wasm" to bytes(0x7F, 0x45, 0x4C, 0x46, 0x01, 0x00, 0x00, 0x00),
        )
        for ((what, bytes) in cases) {
            val e = assertFailsWith<WasmSharedMemoryImport.ShapeException>(what) { WasmSharedMemoryImport.rewrite(bytes) }
            assertTrue(e.message!!.contains("SPEC T-7"), "$what: the failure must point at SPEC T-7, got ${e.message}")
        }
    }

    @Test
    fun cpythonsPthreadsMemoryIsSeenAsShared() {
        val emscripten = module(section(2, vec(2, funcImport("env", "emscripten_get_now"), memoryImport("env", "memory", bytes(0x03, 0x80, 0x02, 0x80, 0x80, 0x02)))))
        assertEquals(true, WasmSharedMemoryImport.hasSharedMemory(emscripten))
        val gilBuild = module(section(5, vec(1, bytes(0x00, 0x80, 0x02))))
        assertEquals(false, WasmSharedMemoryImport.hasSharedMemory(gilBuild))
        assertNull(WasmSharedMemoryImport.hasSharedMemory(module(section(2, vec(1, funcImport("env", "f"))))))
    }

    @Test
    fun theSourceMapsOffsetsMoveWithTheModule() {
        // A=0, C=1, G=3, M=6: the first column of each line is absolute, the rest are deltas.
        val map = """{"version":3,"sources":["a.kt"],"mappings":"AAAA,CAAC;GAAG","names":[]}"""
        assertEquals(
            """{"version":3,"sources":["a.kt"],"mappings":"GAAA,CAAC;MAAG","names":[]}""",
            WasmSharedMemoryImport.shiftSourceMap(map, 3),
        )
        assertEquals(map, WasmSharedMemoryImport.shiftSourceMap(map, 0))
        // A large offset takes several VLQ digits: 100000 + 4.
        val big = """{"mappings":"${vlq(100000)}AAA"}"""
        assertEquals("""{"mappings":"${vlq(100004)}AAA"}""", WasmSharedMemoryImport.shiftSourceMap(big, 4))
    }

    @Test
    fun theFileFormRewritesTheModuleAndItsMapOnlyWhenTheRuntimeIsShared() {
        val dir = File("build/tmp/wasm-shared-memory-test").apply { deleteRecursively(); mkdirs() }
        try {
            val wasm = File(dir, "app.wasm")
            val map = File(dir, "app.wasm.map")
            val mapText = """{"mappings":"AAAA"}"""

            wasm.writeBytes(kotlinModule(kotlinLimits))
            map.writeText(mapText)
            val left = WasmSharedMemoryImport.applyTo(wasm, runtimeHasSharedMemory = false)
            assertTrue(left.contains("left non-shared"), left)
            assertContentEquals(kotlinModule(kotlinLimits), wasm.readBytes())
            assertEquals(mapText, map.readText())

            val rewritten = WasmSharedMemoryImport.applyTo(wasm, runtimeHasSharedMemory = true)
            assertTrue(rewritten.contains("rewritten to shared"), rewritten)
            assertContentEquals(kotlinModule(sharedLimits), wasm.readBytes())
            assertEquals("""{"mappings":"GAAA"}""", map.readText(), "the map must move by the module's 3-byte shift")

            val again = WasmSharedMemoryImport.applyTo(wasm, runtimeHasSharedMemory = true)
            assertTrue(again.contains("already shared"), again)
            assertEquals("""{"mappings":"GAAA"}""", map.readText(), "a second run must not move the map again")

            wasm.writeBytes(module(section(5, vec(1, bytes(0x00, 0x01)))))
            val e = assertFailsWith<WasmSharedMemoryImport.ShapeException> { WasmSharedMemoryImport.applyTo(wasm, true) }
            assertTrue(e.message!!.startsWith("app.wasm: "), "the failure must name the file: ${e.message}")
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- module assembly ----

    /**
     * A module shaped like a Kotlin/Wasm link: a type section, an import section (two functions from
     * `cpython.mjs`, a global of type `(ref null func)`, the memory with [limits], a tag), and a custom
     * section after it standing for everything that must be copied unchanged. [padTo] grows the first
     * import's name so the section content has that many bytes before the rewrite.
     */
    private fun kotlinModule(limits: ByteArray, sizeWidth: Int = 0, padTo: Int = 0): ByteArray {
        fun imports(fieldName: String) = vec(
            5,
            funcImport("./cpython.mjs", fieldName),
            funcImport("./cpython.mjs", "PyLong_FromLong"),
            name("intrinsics") + name("tag") + bytes(0x03, 0x63, 0x70, 0x00),
            memoryImport("intrinsics", "memory", limits),
            name("intrinsics") + name("exc") + bytes(0x04, 0x00, 0x00),
        )
        var field = "Py_IncRef"
        if (padTo > 0) {
            // The padding is measured on Kotlin's shape, so both shapes get the same name.
            val base = imports(field).size - (if (limits.size == sharedLimits.size) 3 else 0)
            field += "x".repeat(padTo - base)
        }
        val content = imports(field)
        return module(
            section(1, vec(1, bytes(0x60, 0x00, 0x00))),
            section(2, content, sizeWidth),
            section(0, name("name") + bytes(0x01, 0x02, 0x03)),
        )
    }

    private fun module(vararg sections: ByteArray): ByteArray =
        bytes(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00) + sections.fold(ByteArray(0)) { a, b -> a + b }

    private fun section(id: Int, content: ByteArray, sizeWidth: Int = 0): ByteArray {
        val size = if (sizeWidth > 0) WasmSharedMemoryImport.paddedLeb(content.size, sizeWidth) else WasmSharedMemoryImport.leb(content.size)
        return bytes(id) + size + content
    }

    private fun vec(count: Int, vararg items: ByteArray): ByteArray =
        WasmSharedMemoryImport.leb(count) + items.fold(ByteArray(0)) { a, b -> a + b }

    private fun name(s: String): ByteArray = s.toByteArray().let { WasmSharedMemoryImport.leb(it.size) + it }

    private fun funcImport(module: String, field: String): ByteArray = name(module) + name(field) + bytes(0x00, 0x00)

    private fun memoryImport(module: String, field: String, limits: ByteArray): ByteArray =
        name(module) + name(field) + bytes(0x02) + limits

    private fun bytes(vararg b: Int): ByteArray = ByteArray(b.size) { b[it].toByte() }

    private fun vlq(value: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        var v = value shl 1
        val out = ByteArrayOutputStream()
        do {
            var d = v and 0x1F
            v = v ushr 5
            if (v != 0) d = d or 0x20
            out.write(chars[d].code)
        } while (v != 0)
        return out.toString()
    }
}
