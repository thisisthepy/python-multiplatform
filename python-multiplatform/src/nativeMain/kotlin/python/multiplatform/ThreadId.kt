package python.multiplatform

/**
 * The calling thread's identity as a plain integer.
 *
 * Hides a genuine POSIX ABI split: `platform.posix.pthread_self()` returns
 * `CPointer<pthread_t>?` on Darwin (`pthread_t` is `struct _opaque_pthread_t *`), but on
 * Bionic (androidNative) `pthread_t` is a plain unsigned integral typedef with no `.rawValue`
 * to call. The same cinterop call site cannot compile for both, so each `actual` reads it in
 * whatever shape its platform gives it and normalizes to `Long`.
 *
 * Internal on purpose: this exists so `nativeTest` can tell "the thread CPython created" apart
 * from "the thread the test is driving from" (`CycleCollectionTest`'s
 * `testDeallocOnAThreadCPythonCreated` and `testCycleCollectedOnAThreadCPythonCreated`), not
 * because the library's own API needs to expose thread identity.
 */
internal expect fun currentThreadId(): Long
