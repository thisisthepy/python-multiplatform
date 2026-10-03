@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package python.multiplatform.ref

import kotlin.js.JsAny

/**
 * Asks the host engine to collect, which is the only collector this target has.
 *
 * There is no Kotlin/Wasm equivalent of `GC.collect()` or `System.gc()`: WasmGC's heap belongs to
 * the engine and the stdlib exposes nothing that reaches it. `globalThis.gc` is what
 * `node --expose-gc` installs, and `build.gradle.kts` passes that flag on the wasmJs test task for
 * exactly this call. Where it is absent this degrades to a no-op rather than pretending, and the
 * caller's bounded loop gives up.
 *
 * **Calling this is necessary but not sufficient**, and that is the fact this target's lifetime
 * story turns on. A collection makes the wrapper unreachable; it does not deliver the
 * `FinalizationRegistry` callback, which the host posts as a *task*. Nothing observes a release
 * until the current job ends. Measured on Node 26 / V8 14.6: after `gc()` in the same job a
 * `WeakRef` still resolves, after the microtask queue drains it still resolves, and only after a
 * macrotask is it cleared and the registry callback delivered.
 */
actual fun forceGC() {
    js("{ if (typeof globalThis.gc === 'function') { globalThis.gc(); globalThis.gc(); } }")
}

/**
 * True since ROADMAP §10's lifetime work, and flipped only after `GCLeakTest`'s three cases were
 * observed passing on this target rather than in anticipation of it.
 *
 * A `PyObject` dropped without `close()` gives its reference back here, through a JS
 * `FinalizationRegistry` reached with a `JsReference` -- see
 * `wasmJsMain/.../PyAutoCloseable.wasmJs.kt` for what had to be measured before that was possible,
 * and `WasmFinalizationTest` for the proof against live wrappers and CPython's own refcount.
 */
actual val cleanerReleasesAutomatically: Boolean = true

/**
 * The JS promise a collector-driven test hands back.
 *
 * Spelled as a bare `external class` rather than `kotlin.js.Promise<JsAny?>` because an
 * `actual typealias` may not apply type arguments ("Aliased class cannot have type parameters with
 * declaration-site variance") and `Promise`'s parameter is `out T` -- and an `actual` for an
 * `expect class` has to be a class, so an `external interface` is refused too. The value really is
 * a `Promise`; only the Kotlin-side type is opaque. `kotlin-test`'s adapter decides whether to
 * await by testing `is Promise<*>`, which is an `instanceof` against the host's own `Promise` and
 * is unaffected by what this side calls the type.
 */
external class CollectorPromise : JsAny

/**
 * A promise, because nothing about a collection is observable from inside the job that asked for
 * it. See `commonTest`'s [CollectorTestResult].
 */
actual typealias CollectorTestResult = CollectorPromise

/**
 * The blocking loop's shape, unrolled over host turns.
 *
 * The loop body is JavaScript so that the `await` is real: a Kotlin `for` loop calling [forceGC]
 * fifty times runs all fifty inside one job, and a job that has not ended can observe neither a
 * cleared `WeakRef` nor a delivered `FinalizationRegistry` callback. That is the trap these three
 * cases were stuck in -- the mechanism was absent, then it was present and the loop still could not
 * see it.
 *
 * [attempt] and [finish] cross as JS closures. An assertion failing inside [finish] throws out of
 * the async function, which rejects the promise, which fails the test -- so the assertions keep
 * their meaning rather than disappearing into an unobserved continuation.
 */
private fun runCollectorChain(
    maxAttempts: Int,
    attempt: () -> Boolean,
    finish: () -> Unit
): CollectorPromise = js(
    """(async () => {
        const tick = () => new Promise(r => setTimeout(r, 0));
        for (let i = 0; i < maxAttempts; i++) {
            await tick();
            if (typeof globalThis.gc === 'function') { globalThis.gc(); globalThis.gc(); }
            if (attempt()) break;
        }
        finish();
        return null;
    })()"""
)

actual fun collectorTest(
    maxAttempts: Int,
    attempt: () -> Boolean,
    finish: () -> Unit
): CollectorTestResult = runCollectorChain(maxAttempts, attempt, finish)
