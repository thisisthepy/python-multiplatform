@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi

import kotlin.wasm.unsafe.Pointer
import python.native.ffi.bindings.free
import python.native.ffi.bindings.malloc

/**
 * The linear-memory side of the boundary. `Panama` is desktop's equivalent; this is smaller because
 * there is no boundary to cross for data.
 *
 * Kotlin's `intrinsics.memory` import is pointed at Emscripten's `Module.wasmMemory`, so CPython's
 * heap and Kotlin's linear memory are one `WebAssembly.Memory`. A `char*` CPython hands back is an
 * address `Pointer(addr)` reads directly -- no copy, no JS frame, and it keeps working after the
 * memory grows (a JS `TypedArray` view would detach; a wasm memory import does not).
 *
 * ### The rule that shapes every function here
 *
 * **`withScopedMemoryAllocator` must never be called on this target.** It is the only allocator
 * Kotlin/Wasm offers and it allocates from address 0 upward -- measured handing back `0x0` after
 * growing the memory by two pages -- which on a shared memory is directly on top of Emscripten's
 * static data. So CPython allocates and Kotlin only dereferences. [allocUtf8] goes through
 * CPython's own `malloc` for that reason.
 *
 * See docs/platforms/wasm-design.md and `wasm-experiment/README.md` for the measurements.
 */
object Wasm {

    /**
     * Stages [value] as a NUL-terminated UTF-8 string in CPython's heap and returns its address.
     * The caller owns it and must pass it to [freeUtf8].
     *
     * `malloc`, not `PyMem_Malloc`: the latter requires an attached thread state, and a C string
     * often has to be staged before the call that attaches one.
     *
     * **This is not the route an `actual` should take.** A C API argument goes through
     * [internedUtf8] or [scratchUtf8]; this one is for a buffer whose lifetime the caller owns and
     * outlives the call -- `ProxyTypeFactory`'s type name, and the intern cache's own entries.
     */
    fun allocUtf8(value: String): Int {
        val size = utf8Length(value) + 1
        val address = malloc(size)
        if (address == 0) throw OutOfMemoryError("malloc($size) failed in CPython's heap")
        writeUtf8(value, address)
        return address
    }

    fun freeUtf8(address: Int) {
        if (address != 0) free(address)
    }

    // ---------------------------------------------------------------------------------------
    // The two routes an `actual` takes, and how the choice is made
    // ---------------------------------------------------------------------------------------
    //
    // Every `actual` that takes a `String` used to call `allocUtf8` and `freeUtf8` around the call,
    // which is a `malloc`, an encode, a copy and a `free` per invocation. Measured against the real
    // interpreter that put `PyObject_GetAttrString` at 377 ns while a bare crossing
    // (`Py_IncRef`/`Py_DecRef`) was 44 ns.
    //
    // The judgement is per *argument*, not per function, and it is the same one desktop and Android
    // make (`ShapeDowncalls.desktop.kt`, `ShapeDowncalls.android.kt`):
    //
    //     internedUtf8   a repeated identifier -- attribute, method, module, type, dict key
    //     scratchUtf8    arbitrary content -- source text, messages, docs, paths, user data
    //
    // What is *different* here is why each is cheap, and it is worth writing down because the
    // obvious reading of "shared memory" is wrong. Kotlin/Wasm compiles with
    // `builtins: ['js-string']`, so a Kotlin `String` is a JS string and reading it is not free --
    // the encode is real work, not a copy that shared memory makes disappear. What shared memory
    // removes is the *other* half: the destination is CPython's own heap, so there is no staging
    // buffer and no crossing. So:
    //
    //   * interning removes the encode, the malloc and the free -- everything but a hash lookup;
    //   * scratch removes the malloc and the free but keeps the encode, which is why it is the
    //     fallback rather than the default.
    //
    // Both write straight into linear memory instead of going through `encodeToByteArray()`, which
    // would allocate a WasmGC `ByteArray` per call and then copy it byte by byte.

    /** Bound on [internedUtf8]'s cache. Callers can pass arbitrary strings, so it needs one. */
    const val INTERN_MAX_ENTRIES: Int = 4096

    private val intern = Utf8Intern(INTERN_MAX_ENTRIES)

    /** How many strings the shared cache is currently holding. For assertions, not for logic. */
    val internedEntryCount: Int get() = intern.size

    /**
     * The address of [value] as a C string that stays valid for the life of the interpreter.
     *
     * For arguments that repeat: attribute and method names, module names, type names, dict keys.
     * A hit costs one hash lookup and nothing else -- no encode, no allocation, no free.
     *
     * Past [INTERN_MAX_ENTRIES] this falls back to [scratchUtf8], so the return value must still be
     * treated as valid only until the next few marshalling calls. Every caller on this surface uses
     * it inside a single C call, which satisfies that.
     */
    fun internedUtf8(value: String): Int = intern.addressOf(value)

    /**
     * The address of [value] in one of a small ring of reusable buffers.
     *
     * For arguments whose content is arbitrary, where caching would be a leak. There is no `free`
     * and no `malloc` on the common path: a slot keeps whatever capacity it grew to.
     *
     * **The address is valid only until the slot comes round again**, which is
     * [SCRATCH_SLOT_COUNT] further marshalling calls. Four is chosen against the surface: the most
     * any C API function here stages at once is three (`PyErr_WarnExplicit`,
     * `PyImport_ExecCodeModuleWithPathnames`). A re-entrant call -- an upcall that marshals four
     * more strings while these are live -- would overwrite them, exactly as on desktop.
     */
    fun scratchUtf8(value: String): Int {
        val slot = scratchIndex
        scratchIndex = (slot + 1) % SCRATCH_SLOT_COUNT

        // 3 bytes per UTF-16 unit is the true upper bound: a surrogate pair is two units and
        // becomes four bytes, so the worst case per unit is the three-byte BMP case. Sizing from
        // this rather than measuring first keeps the encode to a single pass over the string.
        val needed = value.length * 3 + 1
        if (scratchCapacity[slot] < needed) {
            if (scratchAddress[slot] != 0) free(scratchAddress[slot])
            val capacity = if (needed < 64) 64 else needed
            val address = malloc(capacity)
            if (address == 0) throw OutOfMemoryError("malloc($capacity) failed in CPython's heap")
            scratchAddress[slot] = address
            scratchCapacity[slot] = capacity
        }
        writeUtf8(value, scratchAddress[slot])
        return scratchAddress[slot]
    }

    private const val SCRATCH_SLOT_COUNT = 4
    private val scratchAddress = IntArray(SCRATCH_SLOT_COUNT)
    private val scratchCapacity = IntArray(SCRATCH_SLOT_COUNT)
    private var scratchIndex = 0

    /**
     * UTF-8 byte length of [value], not counting the terminator.
     *
     * Only [allocUtf8] needs this -- it sizes exactly because what it returns is permanent.
     * [scratchUtf8] sizes from the upper bound instead and skips this pass.
     */
    private fun utf8Length(value: String): Int {
        var n = 0
        var i = 0
        while (i < value.length) {
            val c = value[i].code
            when {
                c < 0x80 -> { n += 1; i++ }
                c < 0x800 -> { n += 2; i++ }
                c in 0xD800..0xDBFF && i + 1 < value.length && value[i + 1].code in 0xDC00..0xDFFF -> {
                    n += 4; i += 2
                }
                else -> { n += 3; i++ }
            }
        }
        return n
    }

    /**
     * Encodes [value] as NUL-terminated UTF-8 at [address] and returns the byte count written,
     * excluding the terminator. The caller guarantees the room.
     *
     * Hand-rolled rather than `value.encodeToByteArray()` followed by a copy, for the same reason
     * [readUtf8String] does not use `decodeToString()`: the intermediate array is a WasmGC
     * allocation on a path that runs once per C API call. Reading a Kotlin `String` character by
     * character is a `js-string` builtin, not a JS call.
     *
     * An unpaired surrogate is written as U+FFFD. It cannot be represented in UTF-8, and the
     * alternative -- throwing from inside argument marshalling -- would turn a bad string into a
     * failure at a call site that has nothing to do with it.
     */
    private fun writeUtf8(value: String, address: Int): Int {
        var p = address
        var i = 0
        val n = value.length
        while (i < n) {
            val c = value[i].code
            if (c < 0x80) {
                Pointer(p.toUInt()).storeByte(c.toByte()); p += 1; i++
            } else if (c < 0x800) {
                Pointer(p.toUInt()).storeByte((0xC0 or (c shr 6)).toByte())
                Pointer((p + 1).toUInt()).storeByte((0x80 or (c and 0x3F)).toByte())
                p += 2; i++
            } else if (c in 0xD800..0xDBFF && i + 1 < n && value[i + 1].code in 0xDC00..0xDFFF) {
                val cp = 0x10000 + ((c - 0xD800) shl 10) + (value[i + 1].code - 0xDC00)
                Pointer(p.toUInt()).storeByte((0xF0 or (cp shr 18)).toByte())
                Pointer((p + 1).toUInt()).storeByte((0x80 or ((cp shr 12) and 0x3F)).toByte())
                Pointer((p + 2).toUInt()).storeByte((0x80 or ((cp shr 6) and 0x3F)).toByte())
                Pointer((p + 3).toUInt()).storeByte((0x80 or (cp and 0x3F)).toByte())
                p += 4; i += 2
            } else {
                val cc = if (c in 0xD800..0xDFFF) 0xFFFD else c
                Pointer(p.toUInt()).storeByte((0xE0 or (cc shr 12)).toByte())
                Pointer((p + 1).toUInt()).storeByte((0x80 or ((cc shr 6) and 0x3F)).toByte())
                Pointer((p + 2).toUInt()).storeByte((0x80 or (cc and 0x3F)).toByte())
                p += 3; i++
            }
        }
        Pointer(p.toUInt()).storeByte(0)
        return p - address
    }

    /** Length of the NUL-terminated string at [address], in bytes. */
    fun strlen(address: Int): Int {
        var n = 0
        while (Pointer((address + n).toUInt()).loadByte().toInt() != 0) n++
        return n
    }

    /**
     * Reads the NUL-terminated UTF-8 string at [address], or null if [address] is 0 -- which is how
     * every `String?`-returning C API function reports failure.
     *
     * Two routes, and which one is taken matters more than it looks. Measured over 27- and
     * 4000-byte strings:
     *
     * | ns per read | 27 B | 4000 B |
     * |---|---|---|
     * | `CharArray` + `concatToString()` | **103** | 5 875 |
     * | `ByteArray` + `decodeToString()` | 405 | **58 271** |
     *
     * `decodeToString()` costs about 13 ns/byte on long input against 0.2 ns/byte on short, so it
     * is never used here. The ASCII scan below is one pass that also decides the fast path, and the
     * multi-byte path decodes into the same `CharArray` rather than falling back to it.
     */
    fun readUtf8String(address: Int): String? {
        if (address == 0) return null
        var length = 0
        var ascii = true
        while (true) {
            val b = Pointer((address + length).toUInt()).loadByte().toInt() and 0xFF
            if (b == 0) break
            if (b >= 0x80) ascii = false
            length++
        }
        if (length == 0) return ""

        val chars = CharArray(length)
        if (ascii) {
            for (i in 0 until length) {
                chars[i] = (Pointer((address + i).toUInt()).loadByte().toInt() and 0xFF).toChar()
            }
            return chars.concatToString()
        }
        return decodeUtf8(address, length, chars)
    }

    /** Reads [length] bytes at [address]. The `PyBytes`/`PyByteArray` case: no decode involved. */
    fun readBytes(address: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        for (i in 0 until length) out[i] = Pointer((address + i).toUInt()).loadByte()
        return out
    }

    /**
     * UTF-8 decode straight out of linear memory into [out].
     *
     * Hand-rolled rather than `ByteArray.decodeToString()` for the cost reason above, and rather
     * than JS `UTF8ToString` because that would put a JS frame back in the data path. Malformed
     * input is replaced with U+FFFD rather than throwing: a `char*` that fails to decode is
     * CPython handing back something unexpected, and losing the rest of an error message to an
     * exception is worse than showing a replacement character.
     */
    private fun decodeUtf8(address: Int, length: Int, out: CharArray): String {
        fun byteAt(i: Int) = Pointer((address + i).toUInt()).loadByte().toInt() and 0xFF

        var i = 0
        var o = 0
        while (i < length) {
            val b0 = byteAt(i)
            var cp: Int
            val size: Int
            when {
                b0 < 0x80 -> { cp = b0; size = 1 }
                b0 and 0xE0 == 0xC0 -> { cp = b0 and 0x1F; size = 2 }
                b0 and 0xF0 == 0xE0 -> { cp = b0 and 0x0F; size = 3 }
                b0 and 0xF8 == 0xF0 -> { cp = b0 and 0x07; size = 4 }
                else -> { cp = -1; size = 1 }
            }
            if (cp < 0 || i + size > length) {
                out[o++] = '�'
                i += 1
                continue
            }
            var valid = true
            for (k in 1 until size) {
                val bk = byteAt(i + k)
                if (bk and 0xC0 != 0x80) { valid = false; break }
                cp = (cp shl 6) or (bk and 0x3F)
            }
            if (!valid) {
                out[o++] = '�'
                i += 1
                continue
            }
            i += size
            if (cp <= 0xFFFF) {
                out[o++] = cp.toChar()
            } else {
                // Astral plane: one code point becomes two UTF-16 units. `out` was sized from the
                // byte count, and a 4-byte sequence yields 2 chars, so it can never overflow.
                val v = cp - 0x10000
                out[o++] = (0xD800 or (v shr 10)).toChar()
                out[o++] = (0xDC00 or (v and 0x3FF)).toChar()
            }
        }
        return out.concatToString(0, o)
    }
}

/**
 * A bounded `String -> C string address` cache. [Wasm.internedUtf8] holds the one the library uses.
 *
 * Separate from [Wasm] so that the bound can be exercised against a small instance. Filling the
 * shared cache in a test would leave every later call in the same binary on the fallback path,
 * which is an order dependence that would quietly delete the optimisation being tested.
 *
 * Entries are never evicted: the address is handed to CPython and this side cannot know when the
 * last use ends. That is what makes the bound necessary rather than nice to have.
 */
internal class Utf8Intern(private val maxEntries: Int) {
    private val entries = HashMap<String, Int>()

    val size: Int get() = entries.size

    fun addressOf(value: String): Int {
        val cached = entries[value]
        if (cached != null) return cached
        if (entries.size >= maxEntries) return Wasm.scratchUtf8(value)
        val address = Wasm.allocUtf8(value)
        entries[value] = address
        return address
    }
}
