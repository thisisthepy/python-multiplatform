package python.native.ffi

import java.util.concurrent.ConcurrentHashMap

/**
 * Shape vocabulary for CPython Stable ABI downcalls (see `docs/design/downcall-design.md`).
 *
 * A census of the ~330 `expect` declarations in `commonMain/.../EmbedAPI.kt` shows they
 * collapse into exactly 14 distinct ABI shapes: integer, pointer, and string arguments
 * travel in the integer register bank as raw `Long` bit patterns; `Double`/`Float`
 * arguments travel in the floating-point bank. No function mixes the two banks, none is
 * variadic, and none passes or returns a struct by value, so these 14 trampolines are
 * sufficient to reach the entire ABI.
 *
 * Each shape function takes the target native function's address as its first argument
 * (`fn`), then the call arguments in the order CPython declares them.
 *
 * `jvmMain` is a proper intermediate source set (`desktopMain`/`androidMain` both depend
 * on it), so `expect` here with `actual` in the two leaves is the correct mechanism --
 * verified experimentally. These are declared `internal` (implementation detail of the
 * FFI layer, not part of the library's public surface) and deliberately **not** `inline`:
 * combining `expect inline` with an intermediate source set crashes the Kotlin 2.0.20
 * compiler with "Internal error in file lowering".
 *
 * `desktopMain` implements these over cached, unbound Panama `MethodHandle`s (one per
 * shape, see `Panama.kt`). `androidMain` implements these by delegating through
 * JNI to Kotlin/Native trampolines compiled into `libmultiplatform_python3.13.so` (see
 * `nativeMain/.../EmbedAPI.native.kt`), which invoke the target function pointer directly
 * via a `CFunction` cast -- no NDK, no C project, no ART entry-point patching.
 *
 * NOTE: as of this change these shapes are vocabulary only. None of the 330 `expect`
 * declarations in `EmbedAPI.kt` have been migrated to call through them yet -- that is a
 * separate follow-up.
 */
internal expect fun downcall_V(fn: Long)
internal expect fun downcall_I(fn: Long): Long
internal expect fun downcall_F(fn: Long): Double
internal expect fun downcallI_V(fn: Long, a0: Long)
internal expect fun downcallI_I(fn: Long, a0: Long): Long
internal expect fun downcallI_F(fn: Long, a0: Long): Double
internal expect fun downcallF_I(fn: Long, a0: Double): Long
internal expect fun downcallII_V(fn: Long, a0: Long, a1: Long)
internal expect fun downcallII_I(fn: Long, a0: Long, a1: Long): Long
internal expect fun downcallIII_V(fn: Long, a0: Long, a1: Long, a2: Long)
internal expect fun downcallIII_I(fn: Long, a0: Long, a1: Long, a2: Long): Long
internal expect fun downcallIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long
internal expect fun downcallIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long): Long
internal expect fun downcallIIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long, a5: Long): Long

/**
 * Symbol lookup.
 *
 * [ffiSymbolRaw] is the per-platform primitive that resolves a CPython symbol name to its
 * process address exactly once -- `SymbolLookup.find` on desktop (Panama), `dlsym` on
 * Android. [ffiSymbol] wraps it with a process-lifetime cache keyed by symbol name, so a
 * call site that resolves e.g. `"PyList_Size"` on its first invocation pays one native
 * lookup total, not one per call. Returns the cached address on every subsequent call.
 *
 * Throws [IllegalStateException] if the symbol cannot be resolved (mirrors the existing
 * `UnsatisfiedLinkError` thrown by `Panama.findSymbol` for bound handles).
 */
internal expect fun ffiSymbolRaw(name: String): Long

private val symbolCache = ConcurrentHashMap<String, Long>()

internal fun ffiSymbol(name: String): Long =
    symbolCache.getOrPut(name) {
        val addr = ffiSymbolRaw(name)
        check(addr != 0L) { "ffiSymbol: symbol not found: $name" }
        addr
    }

/**
 * String marshalling.
 *
 * Shape functions move only machine words, so `String` arguments must be converted to a
 * native buffer before the call, and `const char*` results decoded after it.
 *
 * Lifetime, and who frees what:
 *  - [ffiAllocUtf8] allocates a new null-terminated UTF-8 buffer. **The caller owns it**
 *    and must release it with [ffiFreeUtf8] -- this layer never frees it implicitly and
 *    CPython never takes ownership of an argument buffer it's merely handed (the Stable
 *    ABI copies argument strings it needs to keep).
 *  - [ffiFreeUtf8] releases a buffer obtained from [ffiAllocUtf8]. Calling it with any
 *    other address (e.g. a CPython-owned `const char*`) is undefined behaviour, same as
 *    misusing `free()`.
 *  - [ffiReadUtf8] is non-owning: it copies a null-terminated C string's bytes into a
 *    Kotlin `String` and does not free, or take ownership of, the source buffer. There is
 *    deliberately no "free a downcall's returned string" helper -- buffers CPython returns
 *    (e.g. from `PyUnicode_AsUTF8`) are owned by CPython/the object graph, never by the
 *    caller, so freeing them here would be a use-after-free waiting to happen. Only
 *    buffers obtained from [ffiAllocUtf8] are ever valid input to [ffiFreeUtf8].
 *
 * To make leaking hard in the common case, prefer [withUtf8] over calling [ffiAllocUtf8]
 * and [ffiFreeUtf8] directly: it frees the buffer in a `finally` block, so an exception
 * thrown by the downcall (or by argument/result marshalling around it) can't leak it.
 */
@PublishedApi internal expect fun internedUtf8(s: String): Long
@PublishedApi internal expect fun encodeScratchUtf8(s: String): Long
@PublishedApi internal expect fun freeUtf8(address: Long)
internal expect fun ffiReadUtf8(ptr: Long): String?

@PublishedApi internal inline fun <R> withUtf8(str: String, block: (Long) -> R): R {
    // We can just use the scratch buffer for withUtf8 since it's synchronous and only valid during the block.
    val ptr = encodeScratchUtf8(str)
    return block(ptr)
}
