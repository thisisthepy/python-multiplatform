package python.native.ffi
import java.util.concurrent.ConcurrentHashMap



/**
 * Desktop (Panama) actuals for the shape vocabulary declared in `jvmMain/.../ShapeDowncalls.kt`.
 *
 * One unbound `MethodHandle` is built per shape (14 total, via [Panama.unboundDowncallHandle])
 * and reused for every CPython function of that shape -- this is the whole point of collapsing
 * ~330 bound, per-function handles down to 14 shared ones. Each handle's static Java method type is
 * fixed and known at this call site, which is exactly what makes `invokeExact` reachable: unlike the
 * existing 305 `MethodHandle.invoke(...) as Long` call sites (which see `Any!` back from `invoke` and
 * pay a box/unbox on every call), `invokeExact` requires the call site's inferred argument and return
 * types to match the handle's type bit-for-bit, with no `asType` conversion inserted.
 *
 * See the file-level report in the task summary for whether `invokeExact` actually links from
 * Kotlin call sites here (it does, once the handle and the call site agree on primitive types
 * exactly -- see the `Unit`-cast idiom used for the void-returning shapes below).
 */

private val hV = Panama.unboundDowncallHandle(0, 0, ReturnKind.VOID)
private val hI = Panama.unboundDowncallHandle(0, 0, ReturnKind.LONG)
private val hF = Panama.unboundDowncallHandle(0, 0, ReturnKind.DOUBLE)
private val hI_V = Panama.unboundDowncallHandle(1, 0, ReturnKind.VOID)
private val hI_I = Panama.unboundDowncallHandle(1, 0, ReturnKind.LONG)
private val hI_F = Panama.unboundDowncallHandle(1, 0, ReturnKind.DOUBLE)
private val hF_I = Panama.unboundDowncallHandle(0, 1, ReturnKind.LONG)
private val hII_V = Panama.unboundDowncallHandle(2, 0, ReturnKind.VOID)
private val hII_I = Panama.unboundDowncallHandle(2, 0, ReturnKind.LONG)
private val hIII_V = Panama.unboundDowncallHandle(3, 0, ReturnKind.VOID)
private val hIII_I = Panama.unboundDowncallHandle(3, 0, ReturnKind.LONG)
private val hIIII_I = Panama.unboundDowncallHandle(4, 0, ReturnKind.LONG)
private val hIIIII_I = Panama.unboundDowncallHandle(5, 0, ReturnKind.LONG)
private val hIIIIII_I = Panama.unboundDowncallHandle(6, 0, ReturnKind.LONG)

internal actual fun downcall_V(fn: Long) {
    hV.invokeExact(fn) as Unit
}

internal actual fun downcall_I(fn: Long): Long =
    hI.invokeExact(fn) as Long

internal actual fun downcall_F(fn: Long): Double =
    hF.invokeExact(fn) as Double

internal actual fun downcallI_V(fn: Long, a0: Long) {
    hI_V.invokeExact(fn, a0) as Unit
}

internal actual fun downcallI_I(fn: Long, a0: Long): Long =
    hI_I.invokeExact(fn, a0) as Long

internal actual fun downcallI_F(fn: Long, a0: Long): Double =
    hI_F.invokeExact(fn, a0) as Double

internal actual fun downcallF_I(fn: Long, a0: Double): Long =
    hF_I.invokeExact(fn, a0) as Long

internal actual fun downcallII_V(fn: Long, a0: Long, a1: Long) {
    hII_V.invokeExact(fn, a0, a1) as Unit
}

internal actual fun downcallII_I(fn: Long, a0: Long, a1: Long): Long =
    hII_I.invokeExact(fn, a0, a1) as Long

internal actual fun downcallIII_V(fn: Long, a0: Long, a1: Long, a2: Long) {
    hIII_V.invokeExact(fn, a0, a1, a2) as Unit
}

internal actual fun downcallIII_I(fn: Long, a0: Long, a1: Long, a2: Long): Long =
    hIII_I.invokeExact(fn, a0, a1, a2) as Long

internal actual fun downcallIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long =
    hIIII_I.invokeExact(fn, a0, a1, a2, a3) as Long

internal actual fun downcallIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long): Long =
    hIIIII_I.invokeExact(fn, a0, a1, a2, a3, a4) as Long

internal actual fun downcallIIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long, a5: Long): Long =
    hIIIIII_I.invokeExact(fn, a0, a1, a2, a3, a4, a5) as Long

// ---- Symbol lookup ----

internal actual fun ffiSymbolRaw(name: String): Long = Panama.findSymbolAddress(name)

private val internCache = ConcurrentHashMap<String, Long>()
private const val CACHE_MAX_ENTRIES = 4096

private const val SCRATCH_SLOT_COUNT = 4

private class DesktopScratchSlots {
    val slots = LongArray(SCRATCH_SLOT_COUNT)
    var index = 0
}

private val scratchThreadLocal = object : ThreadLocal<DesktopScratchSlots>() {
    override fun initialValue() = DesktopScratchSlots()
}

@PublishedApi internal actual fun internedUtf8(s: String): Long {
    val cached = internCache[s]
    if (cached != null) return cached

    if (internCache.size >= CACHE_MAX_ENTRIES) {
        return encodeScratchUtf8(s)
    }

    val addr = Panama.allocateUtf8Freeable(s)
    val existing = internCache.putIfAbsent(s, addr)
    if (existing != null) {
        Panama.freeUtf8Address(addr)
        return existing
    }
    return addr
}

@PublishedApi internal actual fun encodeScratchUtf8(s: String): Long {
    val state = scratchThreadLocal.get()
    val slot = state.index
    state.index = (slot + 1) % SCRATCH_SLOT_COUNT
    val old = state.slots[slot]
    if (old != 0L) {
        Panama.freeUtf8Address(old)
    }
    val newAddr = Panama.allocateUtf8Freeable(s)
    state.slots[slot] = newAddr
    return newAddr
}

@PublishedApi internal actual fun freeUtf8(address: Long) = Panama.freeUtf8Address(address)

internal actual fun ffiReadUtf8(ptr: Long): String? = Panama.readUtf8String(ptr)

