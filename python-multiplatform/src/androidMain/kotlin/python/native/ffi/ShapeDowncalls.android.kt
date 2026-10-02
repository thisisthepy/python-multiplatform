package python.native.ffi
import java.util.concurrent.ConcurrentHashMap
import java.nio.ByteBuffer

/**
 * Android (ART) actuals for the shape vocabulary declared in `jvmMain/.../ShapeDowncalls.kt`.
 *
 * Each shape is one JNI native-method call into a `@CName`-exported trampoline compiled into
 * `libmultiplatform_python3.13.so` (see `nativeMain/.../EmbedAPI.native.kt`), which casts the
 * `fn` address to a typed `CFunction` pointer and invokes it directly. This is "path 1" from
 * `docs/design/downcall-design.md`: ordinary JNI -> trampoline -> indirect call. ART cannot synthesize
 * an arbitrary native call at runtime the way Kotlin/Native's cinterop can at compile time, so
 * (unlike desktop, where a single cached `MethodHandle` per shape is built directly in this
 * layer) the actual dispatch mechanism lives in the native trampoline; this file is a thin,
 * zero-logic pass-through to `bindings`. `@CriticalNative`-based ART entry-point patching
 * (path 2, replacing the JNI hop with a direct branch) is out of scope for this change.
 */

internal actual fun downcall_V(fn: Long) = bindings.downcall_V(fn)
internal actual fun downcall_I(fn: Long): Long = bindings.downcall_I(fn)
internal actual fun downcall_F(fn: Long): Double = bindings.downcall_F(fn)
internal actual fun downcallI_V(fn: Long, a0: Long) = bindings.downcallI_V(fn, a0)
internal actual fun downcallI_I(fn: Long, a0: Long): Long = bindings.downcallI_I(fn, a0)
internal actual fun downcallI_F(fn: Long, a0: Long): Double = bindings.downcallI_F(fn, a0)
internal actual fun downcallF_I(fn: Long, a0: Double): Long = bindings.downcallF_I(fn, a0)
internal actual fun downcallII_V(fn: Long, a0: Long, a1: Long) = bindings.downcallII_V(fn, a0, a1)
internal actual fun downcallII_I(fn: Long, a0: Long, a1: Long): Long = bindings.downcallII_I(fn, a0, a1)
internal actual fun downcallIII_V(fn: Long, a0: Long, a1: Long, a2: Long) = bindings.downcallIII_V(fn, a0, a1, a2)
internal actual fun downcallIII_I(fn: Long, a0: Long, a1: Long, a2: Long): Long = bindings.downcallIII_I(fn, a0, a1, a2)
internal actual fun downcallIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long =
    bindings.downcallIIII_I(fn, a0, a1, a2, a3)
internal actual fun downcallIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long): Long =
    bindings.downcallIIIII_I(fn, a0, a1, a2, a3, a4)
internal actual fun downcallIIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long, a5: Long): Long =
    bindings.downcallIIIIII_I(fn, a0, a1, a2, a3, a4, a5)

// ---- Symbol lookup ----

// Symbol names are a small fixed set (~330 CPython entry points) and `ffiSymbol` caches the
// resolved address by name, so each one is encoded at most once -- the intern cache, not scratch.
internal actual fun ffiSymbolRaw(name: String): Long = bindings.ffiSymbolRawN(internedUtf8(name))




private class InternedString(val addr: Long, val buf: ByteBuffer)
private val internCache = ConcurrentHashMap<String, InternedString>()
// We cap at 4096. Python's own standard library and likely app code has many literals,
// but 4096 string pointers is very little memory and covers virtually all attribute accesses.
private const val CACHE_MAX_ENTRIES = 4096

private const val SCRATCH_SLOT_COUNT = 4

private class ScratchBuffer {
    var buf: ByteBuffer = ByteBuffer.allocateDirect(1024)
    var addr: Long = bindings.ffiDirectBufferAddress(buf)
}

private class AndroidScratchSlots {
    val slots = Array(SCRATCH_SLOT_COUNT) { ScratchBuffer() }
    var index = 0
}

private val scratchThreadLocal = object : ThreadLocal<AndroidScratchSlots>() {
    override fun initialValue() = AndroidScratchSlots()
}

private fun encodeInto(s: String, buf: ByteBuffer) {
    buf.clear()
    var ascii = true
    for (i in 0 until s.length) {
        val c = s[i]
        if (c.code > 0x7F) {
            ascii = false
            break
        }
        buf.put(c.code.toByte())
    }
    if (ascii) {
        buf.put(0)
        return
    }
    // fallback for non-ASCII
    val bytes = s.toByteArray(Charsets.UTF_8)
    buf.clear()
    buf.put(bytes)
    buf.put(0)
}

@PublishedApi internal actual fun internedUtf8(s: String): Long {
    val cached = internCache[s]
    if (cached != null) return cached.addr

    if (internCache.size >= CACHE_MAX_ENTRIES) {
        return encodeScratchUtf8(s)
    }

    val maxLen = s.length * 3 + 1
    val buf = ByteBuffer.allocateDirect(maxLen)
    encodeInto(s, buf)
    val addr = bindings.ffiDirectBufferAddress(buf)
    val interned = InternedString(addr, buf)
    internCache.putIfAbsent(s, interned)?.let { return it.addr }
    return addr
}

@PublishedApi internal actual fun encodeScratchUtf8(s: String): Long {
    val maxLen = s.length * 3 + 1
    val state = scratchThreadLocal.get()
    val slotIndex = state.index
    state.index = (slotIndex + 1) % SCRATCH_SLOT_COUNT
    val sb = state.slots[slotIndex]
    if (sb.buf.capacity() < maxLen) {
        var newCap = sb.buf.capacity() * 2
        while (newCap < maxLen) newCap *= 2
        sb.buf = ByteBuffer.allocateDirect(newCap)
        sb.addr = bindings.ffiDirectBufferAddress(sb.buf)
    }
    encodeInto(s, sb.buf)
    return sb.addr
}

@PublishedApi internal actual fun freeUtf8(address: Long) = bindings.ffiFreeUtf8(address)

internal actual fun ffiReadUtf8(ptr: Long): String? = bindings.ffiReadUtf8(ptr)

