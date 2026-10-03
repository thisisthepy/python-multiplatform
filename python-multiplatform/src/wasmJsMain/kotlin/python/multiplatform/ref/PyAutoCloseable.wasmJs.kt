@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package python.multiplatform.ref

import kotlin.js.JsAny
import kotlin.js.JsReference
import kotlin.js.get
import kotlin.js.toJsReference
import python.native.ffi.NativePointer

actual interface PlatformCleaner : AutoCloseable {
    actual override fun close()
}

/**
 * The decref, and the flag that makes it happen once.
 *
 * Held **strongly** by the registry as the entry's held value, so it must not reach back to the
 * [WasmPlatformCleaner] that is the entry's weakly-observed target -- a held value that can reach
 * its own target keeps the target alive forever, which is the one way to write this class that
 * silently disables the whole mechanism. [action] is `topLevelDecRefAction`, a top-level function
 * with no captures; [pointer] is an `Int` in a value class.
 */
private class CleanupState(
    private val pointer: NativePointer,
    private val action: (NativePointer) -> Unit
) {
    private var done = false

    /**
     * Runs the decref if it has not run, and reports whether it did -- which is what lets the
     * finalisation callback count only the releases it is actually responsible for, rather than
     * every entry it is handed back after an explicit `close()`.
     *
     * No atomics: `-pthread` is prohibited by `pyemscripten_2026_0`, so this target is
     * single-threaded and a plain flag is what an `AtomicInt` would be everywhere else. The
     * finalisation callback arrives on a JS *task*, never inside a Kotlin frame, so it cannot
     * interleave with an explicit `close()` mid-update.
     */
    fun run(): Boolean {
        if (done) return false
        done = true
        WasmCleanerStats.released++
        action(pointer)
        return true
    }
}

/**
 * Counters for what the collector actually gave back. Deltas, not absolutes: the process shares
 * one of these across every test, so an absolute reading says nothing (`GCLeakTest` records what
 * that mistake cost).
 *
 * These are the *observability* half of ROADMAP §10 and they stay useful even where the collector
 * is not being driven: `registered - released` is the number of wrappers this target is still
 * holding a CPython reference for, so a program that drops wrappers faster than the host engine
 * collects them can see that happening instead of inferring it from memory growth.
 */
object WasmCleanerStats {
    /** Cleaners created, i.e. `PyAutoCloseable`s constructed. */
    var registered: Int = 0
        internal set

    /** Cleaners whose decref has run, whether from `close()` or from the collector. */
    var released: Int = 0
        internal set

    /** Cleaners whose decref ran because the host engine collected the wrapper. */
    var finalized: Int = 0
        internal set

    /** Wrappers still holding a CPython reference. */
    val outstanding: Int get() = registered - released

    /**
     * Whether automatic reclamation is wired up at all. False on a host without
     * `FinalizationRegistry`, where `close()` is the only route and [outstanding] is a leak count
     * rather than a live-object count.
     */
    val automatic: Boolean get() = REGISTRY != null
}

// -------------------------------------------------------------------------------------------------
// ROADMAP §10 -- the finalisation hook, and why it is JS interop rather than @WasmImport.
//
// Every other declaration this target makes against the host is a `@WasmImport` (see
// `bindings.kt`), which is a wasm-to-wasm call with no JavaScript frame in it. That is not
// available here and cannot be: `@WasmImport` carries primitives only, and what has to cross is a
// *reference* to a Kotlin object -- the thing whose reachability is the question. So this file uses
// `js(...)`, which compiles to an `externref` import in the `js_code` table, and it is the only
// place in `wasmJsMain` that does.
//
// The cost is confined to construction and destruction. Nothing on the C API call path goes through
// here, so the 2.9 ns crossing this target measures elsewhere is unaffected.
//
// What was measured before writing it (`WasmFinalizationTest`, and the raw-Node runs in
// ROADMAP §10):
//
//   * `toJsReference()` is **not** a strong reference. 200 Kotlin objects handed to JS that way,
//     with only a `WeakRef` and a `FinalizationRegistry` entry left holding them, were all
//     collected -- `alive 0 / 200`. Had the handle pinned them, this design would be impossible and
//     §10 would still be owing a leak.
//   * `FinalizationRegistry` fires for WasmGC objects: 200 / 200 callbacks.
//   * The callback arrives on a **task**, and a `WeakRef` is not cleared until the job that created
//     it has ended. Both were measured against Node 26 / V8 14.6: same turn ALIVE, one microtask
//     ALIVE, two microtasks ALIVE, one macrotask COLLECTED. `FinalizationRegistry.cleanupSome()`,
//     which would have made it synchronous, was removed from V8 -- `--harmony-weak-refs-with-
//     cleanup-some` is rejected as an unrecognised flag. Nothing on this platform can observe a
//     collection without yielding to the host, which is why `GCLeakTest` is driven asynchronously.
// -------------------------------------------------------------------------------------------------

private fun finalizationRegistryAvailable(): Boolean = js("typeof FinalizationRegistry === 'function'")

private fun newFinalizationRegistry(onDead: (JsReference<CleanupState>) -> Unit): JsAny =
    js("new FinalizationRegistry(onDead)")

private fun registryRegister(registry: JsAny, target: JsAny, held: JsAny) {
    js("registry.register(target, held)")
}

/**
 * One registry for the whole program, mirroring `desktopMain`'s single `Cleaner`.
 *
 * Null where the host has no `FinalizationRegistry` -- an old engine, or a wasm host that is not a
 * browser or Node. There the behaviour degrades to what this file used to do unconditionally:
 * explicit `close()` works, a dropped wrapper leaks, and [WasmCleanerStats.automatic] says so.
 */
private val REGISTRY: JsAny? =
    if (finalizationRegistryAvailable()) {
        newFinalizationRegistry { held ->
            if (held.get().run()) WasmCleanerStats.finalized++
        }
    } else {
        null
    }

/**
 * Registers a decref that runs when [pointer]'s wrapper is collected, or when `close()` is called,
 * whichever happens first.
 *
 * The weakly-observed target is the returned cleaner itself, exactly as on `desktopMain`, where
 * `Cleaner.register(this, …)` does the same thing: `PyAutoCloseable` holds the cleaner and nothing
 * else does, so the cleaner becomes unreachable precisely when the wrapper does.
 *
 * The entry is never unregistered. `close()` sets the flag instead, and the callback that arrives
 * later finds it set and does nothing. Unregistering would need the cleaner to hold its own
 * `JsReference` -- an object graph that crosses the wasm/JS boundary and comes back -- to have a
 * token to pass, and the entry's held value is three fields; keeping it until the collector reaches
 * the cleaner anyway is the cheaper end of that trade.
 *
 * **The callback cannot arrive inside a Python call.** JS tasks run only once the stack has
 * unwound, and every call from Kotlin into CPython here is synchronous, so `topLevelDecRefAction`
 * never re-enters the interpreter from within it. That is the hazard ROADMAP §1 spent a section on
 * with real cleaner threads, and this target does not have it.
 */
actual fun registerCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
): PlatformCleaner = WasmPlatformCleaner(pointer, closeAction)

private class WasmPlatformCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
) : PlatformCleaner {
    private val state = CleanupState(pointer, closeAction)

    init {
        WasmCleanerStats.registered++
        val registry = REGISTRY
        if (registry != null) registryRegister(registry, this.toJsReference(), state.toJsReference())
    }

    override fun close() {
        state.run()
    }
}
