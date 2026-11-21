package python.multiplatform.gradle

import java.io.ByteArrayOutputStream
import java.io.File

/**
 * SPEC T-7 (#175 step 1): makes a linked Kotlin/Wasm module's `intrinsics.memory` import **shared**, so
 * it can import the shared memory of the free-threaded, pthreads CPython 3.15t.
 *
 * Source: owner decision 2026-11-21, via the leader (team report `pmp-binder.md`, "## Owner decision
 * (2026-11-21, via the leader): #175 is back in 1.0"): "빌드 뒤에 .wasm을 고치기 >> 이걸로 가자".
 *
 * ### Why a binary rewrite
 *
 * Kotlin/Wasm emits `(import "intrinsics" "memory" (memory 0))`: non-shared, no maximum, hardcoded in
 * `WasmCompiledModuleFragment.createAndExportMemory` with no compiler option (checked 2026-11-14 up to
 * `master` e12dc36e). A shared memory cannot satisfy that import (`LinkError`; #411's probe), and a
 * shared memory import must declare a maximum. Kotlin never touches its linear memory
 * (`docs/platforms/wasm-design.md`), so only the import's type has to change.
 *
 * ### What it touches
 *
 * The import section only: that one entry's limits go from `00 00` (min 0) to `03 00 80 80 04` (shared,
 * min 0, max 65536 pages). Every other byte of the section is copied, and every other section is copied
 * unchanged. The section's size is re-encoded in its original LEB128 width when the new size fits, so
 * nothing after it moves; otherwise every later byte offset moves by [Rewritten.shift], and
 * [shiftSourceMap] moves the source map's offsets with it.
 *
 * ### The shape check, and removing this
 *
 * [rewrite] refuses anything but one memory, imported as `intrinsics.memory`, in Kotlin's shape or in
 * the rewritten one. A Kotlin upgrade that changes the import (a new option, a defined memory, other
 * limits) fails the build with a message instead of being mis-patched. Once Kotlin can emit the shared
 * import itself, this object and its two calls (`patchWasmOutputForCPython` here, and
 * `patchKotlinWasmOutputForCPython` in `python-multiplatform/build.gradle.kts`) are deleted.
 *
 * Public only so `python-multiplatform/build.gradle.kts`, which has this plugin on its classpath, runs
 * the same tested code; it is not a consumer API.
 */
object WasmSharedMemoryImport {

    /** The rewritten import's maximum: 65536 pages (4 GiB), the wasm32 ceiling, so every runtime maximum fits. */
    const val MAX_PAGES: Int = 65536

    private const val IMPORT_SECTION = 2
    private const val MEMORY_SECTION = 5
    private const val KIND_FUNC = 0
    private const val KIND_TABLE = 1
    private const val KIND_MEMORY = 2
    private const val KIND_GLOBAL = 3
    private const val KIND_TAG = 4

    /** Kotlin's shape: non-shared, min 0, no max. */
    private val KOTLIN_LIMITS = byteArrayOf(0x00, 0x00)

    /** The rewritten shape: flags 0x03 (shared, has max), min 0, max [MAX_PAGES] (LEB128 `80 80 04`). */
    private val SHARED_LIMITS = byteArrayOf(0x03, 0x00, 0x80.toByte(), 0x80.toByte(), 0x04)

    /** A module that is not the shape this rewrite was written for. */
    class ShapeException internal constructor(message: String) : IllegalStateException(message)

    private fun shape(what: String) = ShapeException(
        "$what. SPEC T-7: the rewrite of Kotlin/Wasm's memory import expects exactly one memory, " +
            "imported as intrinsics.memory, non-shared with minimum 0 and no maximum. If Kotlin now emits " +
            "a shared import itself, delete WasmSharedMemoryImport and its calls; otherwise update it " +
            "for the new shape",
    )

    sealed interface Result

    /** The import was in Kotlin's shape and is now shared. [shift] is how far every later byte moved. */
    class Rewritten(val module: ByteArray, val shift: Int, val importSectionEnd: Int) : Result

    /** The import was already in the rewritten shape (a re-run over a patched file); nothing changed. */
    object AlreadyShared : Result

    /**
     * Checks [module] and, when [makeShared], rewrites its memory import. With `makeShared = false` it
     * only checks and answers [AlreadyShared] or `null` (Kotlin's shape, left alone).
     */
    fun rewrite(module: ByteArray, makeShared: Boolean = true): Result? {
        val r = Reader(module)
        if (module.size < 8 || !module.copyOfRange(0, 8).contentEquals(HEADER)) {
            throw shape("not a wasm module (no \\0asm version 1 header)")
        }
        r.pos = 8
        var found: MemoryImport? = null
        var memories = 0
        while (r.pos < module.size) {
            val sectionStart = r.pos
            val id = r.byte()
            val sizeStart = r.pos
            val size = r.u32()
            val sizeWidth = r.pos - sizeStart
            val contentStart = r.pos
            val end = contentStart + size
            if (end > module.size) throw shape("section $id runs past the end of the module")
            when (id) {
                IMPORT_SECTION -> {
                    val imports = readImports(module, contentStart, end)
                    memories += imports.size
                    if (imports.size == 1) {
                        found = imports.single().copy(sectionStart = sectionStart, sizeWidth = sizeWidth, contentStart = contentStart, contentEnd = end)
                    }
                }
                MEMORY_SECTION -> {
                    r.pos = contentStart
                    memories += r.u32()
                }
            }
            r.pos = end
        }
        if (memories != 1 || found == null) {
            throw shape("the module has $memories memories${if (found == null) ", none of them an import" else ""}")
        }
        val m = found
        if (m.module != "intrinsics" || m.field != "memory") {
            throw shape("the memory is imported as ${m.module}.${m.field}")
        }
        val limits = module.copyOfRange(m.limitsStart, m.limitsEnd)
        return when {
            limits.contentEquals(SHARED_LIMITS) -> AlreadyShared
            !limits.contentEquals(KOTLIN_LIMITS) -> throw shape(
                "the memory import's limits are ${limits.joinToString(" ") { "%02x".format(it) }}",
            )
            !makeShared -> null
            else -> splice(module, m)
        }
    }

    /**
     * The flags byte of [module]'s memory (imported or defined), or `null` if it has none. CPython's
     * Emscripten build with pthreads has a shared memory: flag bit 0x02.
     */
    fun hasSharedMemory(module: ByteArray): Boolean? {
        val r = Reader(module)
        r.pos = 8
        while (r.pos < module.size) {
            val id = r.byte()
            val size = r.u32()
            val start = r.pos
            when (id) {
                IMPORT_SECTION -> readImports(module, start, start + size).firstOrNull()?.let {
                    return (module[it.limitsStart].toInt() and 0x02) != 0
                }
                MEMORY_SECTION -> {
                    r.pos = start
                    if (r.u32() > 0) return (r.byte() and 0x02) != 0
                }
            }
            r.pos = start + size
        }
        return null
    }

    private fun splice(module: ByteArray, m: MemoryImport): Rewritten {
        val content = ByteArrayOutputStream()
        content.write(module, m.contentStart, m.limitsStart - m.contentStart)
        content.write(SHARED_LIMITS)
        content.write(module, m.limitsEnd, m.contentEnd - m.limitsEnd)
        val newContent = content.toByteArray()
        val minimal = leb(newContent.size)
        val sizeBytes = if (minimal.size <= m.sizeWidth) paddedLeb(newContent.size, m.sizeWidth) else minimal
        val out = ByteArrayOutputStream(module.size + 8)
        out.write(module, 0, m.sectionStart + 1)
        out.write(sizeBytes)
        out.write(newContent)
        out.write(module, m.contentEnd, module.size - m.contentEnd)
        val bytes = out.toByteArray()
        val newEnd = m.sectionStart + 1 + sizeBytes.size + newContent.size
        return Rewritten(bytes, bytes.size - module.size, newEnd)
    }

    /**
     * Moves every generated offset in a wasm source map's `mappings` by [shift]. A wasm source map's
     * generated column is the byte offset in the module, delta-encoded after the first segment of each
     * line, so adding [shift] to each line's first column moves them all. The mappings all lie in the
     * code section, after the import section, so all of them move.
     */
    fun shiftSourceMap(json: String, shift: Int): String {
        if (shift == 0) return json
        val match = Regex("\"mappings\"\\s*:\\s*\"([^\"]*)\"").find(json)
            ?: throw shape("the source map has no mappings")
        val lines = match.groupValues[1].split(';').map { line ->
            if (line.isEmpty()) return@map line
            val (first, rest) = decodeVlq(line)
            encodeVlq(first + shift) + line.substring(rest)
        }
        val group = match.groups[1]!!
        return json.substring(0, group.range.first) + lines.joinToString(";") + json.substring(group.range.last + 1)
    }

    /**
     * The file form: checks [wasm], rewrites it in place when [runtimeHasSharedMemory], and moves the
     * offsets of `<wasm>.map` beside it if the rewrite moved any. Answers a line for the build log.
     */
    fun applyTo(wasm: File, runtimeHasSharedMemory: Boolean): String {
        val result = try {
            rewrite(wasm.readBytes(), makeShared = runtimeHasSharedMemory)
        } catch (e: ShapeException) {
            throw ShapeException("${wasm.name}: ${e.message}")
        }
        return when (result) {
            null -> "${wasm.name}: memory import left non-shared (the CPython runtime's memory is not shared)"
            AlreadyShared -> "${wasm.name}: memory import already shared"
            is Rewritten -> {
                wasm.writeBytes(result.module)
                val map = File(wasm.parentFile, wasm.name + ".map")
                if (result.shift != 0 && map.isFile) map.writeText(shiftSourceMap(map.readText(), result.shift))
                "${wasm.name}: memory import rewritten to shared, max $MAX_PAGES pages" +
                    if (result.shift != 0) " (later offsets moved by ${result.shift} bytes)" else ""
            }
        }
    }

    private data class MemoryImport(
        val module: String,
        val field: String,
        val limitsStart: Int,
        val limitsEnd: Int,
        val sectionStart: Int = 0,
        val sizeWidth: Int = 0,
        val contentStart: Int = 0,
        val contentEnd: Int = 0,
    )

    /** Every memory import in an import section's content. Parses every entry, so a kind it does not know fails. */
    private fun readImports(module: ByteArray, start: Int, end: Int): List<MemoryImport> {
        val r = Reader(module)
        r.pos = start
        val memories = ArrayList<MemoryImport>()
        repeat(r.u32()) {
            val mod = r.name()
            val field = r.name()
            when (val kind = r.byte()) {
                KIND_FUNC -> r.u32()
                KIND_TABLE -> {
                    r.refType()
                    r.limits()
                }
                KIND_MEMORY -> {
                    val s = r.pos
                    r.limits()
                    memories += MemoryImport(mod, field, s, r.pos)
                }
                KIND_GLOBAL -> {
                    r.valType()
                    r.byte()
                }
                KIND_TAG -> {
                    r.byte()
                    r.u32()
                }
                else -> throw shape("import $mod.$field has unknown kind $kind")
            }
        }
        if (r.pos != end) throw shape("the import section does not parse to its declared size")
        return memories
    }

    private class Reader(val b: ByteArray) {
        var pos = 0

        fun byte(): Int {
            if (pos >= b.size) throw shape("unexpected end of module")
            return b[pos++].toInt() and 0xFF
        }

        fun u32(): Int {
            var result = 0L
            var shift = 0
            while (true) {
                val x = byte()
                result = result or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
                if (shift > 28) throw shape("a LEB128 u32 is too long")
            }
            return result.toInt()
        }

        fun s33() {
            var shift = 0
            while (byte() and 0x80 != 0) {
                shift += 7
                if (shift > 35) throw shape("a LEB128 s33 is too long")
            }
        }

        fun name(): String {
            val n = u32()
            if (pos + n > b.size) throw shape("a name runs past the end of the module")
            return String(b, pos, n, Charsets.UTF_8).also { pos += n }
        }

        fun limits() {
            val flags = byte()
            if (flags > 0x07) throw shape("limits flags $flags")
            if (flags and 0x04 != 0) throw shape("a 64-bit memory or table")
            u32()
            if (flags and 0x01 != 0) u32()
        }

        /** A value type: one byte, or `ref` / `ref null` (0x64 / 0x63) followed by a heap type (s33). */
        fun valType() {
            when (byte()) {
                0x63, 0x64 -> s33()
                else -> Unit
            }
        }

        fun refType() = valType()
    }

    private val HEADER = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    internal fun leb(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        do {
            var byte = v and 0x7F
            v = v ushr 7
            if (v != 0) byte = byte or 0x80
            out.write(byte)
        } while (v != 0)
        return out.toByteArray()
    }

    internal fun paddedLeb(value: Int, width: Int): ByteArray {
        val out = ByteArray(width)
        var v = value
        for (i in 0 until width) {
            var byte = v and 0x7F
            v = v ushr 7
            if (i < width - 1) byte = byte or 0x80
            out[i] = byte.toByte()
        }
        check(v == 0) { "$value does not fit in $width LEB128 bytes" }
        return out
    }

    private const val VLQ_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Decodes the first base64 VLQ value of [s]; answers it and the index after it. */
    private fun decodeVlq(s: String): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var i = 0
        while (true) {
            val digit = VLQ_CHARS.indexOf(s[i++])
            if (digit < 0) throw shape("the source map's mappings are not base64 VLQ")
            result = result or ((digit and 0x1F) shl shift)
            if (digit and 0x20 == 0) break
            shift += 5
        }
        val negative = result and 1 != 0
        val value = result ushr 1
        return (if (negative) -value else value) to i
    }

    private fun encodeVlq(value: Int): String {
        var v = if (value < 0) ((-value) shl 1) or 1 else value shl 1
        val sb = StringBuilder()
        do {
            var digit = v and 0x1F
            v = v ushr 5
            if (v != 0) digit = digit or 0x20
            sb.append(VLQ_CHARS[digit])
        } while (v != 0)
        return sb.toString()
    }
}
