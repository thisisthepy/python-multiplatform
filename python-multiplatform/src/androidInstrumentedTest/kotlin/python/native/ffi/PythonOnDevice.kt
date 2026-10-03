package python.native.ffi

import androidx.test.platform.app.InstrumentationRegistry
import python.multiplatform.env.PythonBootstrap
import python.multiplatform.ffi.Python3

/**
 * Gets a usable CPython interpreter running inside the instrumentation process.
 *
 * `Py_Initialize` aborts the whole process with "Failed to import encodings module" unless the
 * standard library is present on disk and PYTHONHOME points at it. The library ships that
 * stdlib as APK assets, so it has to be unpacked to app-private storage first.
 *
 * That unpacking used to be written out here, in a near-copy of what `sample`'s `MainActivity` and
 * the external consumer app each wrote for themselves. It is [PythonBootstrap] now -- shipped in
 * `androidMain`, so a host app gets it from the AAR -- and this fixture is one of its callers
 * rather than a fourth implementation. Keeping the fixture on the same code path as host apps is
 * the point: the 340-odd instrumented tests in this process are then also a test of the helper.
 *
 * Idempotent on purpose: JUnit does not guarantee test order, and one test finalises the
 * interpreter when it is done. Every test that needs Python calls [ensureInitialised] and gets
 * a live interpreter whether or not one was already running.
 */
object PythonOnDevice {

    /**
     * Stages the stdlib, then brings the interpreter up **through [Python3.initialize]** rather
     * than through a bare `Py_Initialize()`.
     *
     * The difference is the GIL, and it was worth three GCLeakTest failures on both API levels.
     * `Py_Initialize()` returns with the GIL held by its caller. [Python3.initialize] parks that
     * thread state with `PyEval_SaveThread()` immediately afterwards, which is what lets a cleaner
     * thread attach through `PyGILState_Ensure` and call `Py_DecRef` (ROADMAP §1).
     *
     * Calling the C function directly here skipped the parking, and then made the omission
     * permanent: `Python3.isInitialized` is seeded from `Py_IsInitialized()` the first time the
     * object is touched, so it latched to `true` and [Python3.initialize] returned early for the
     * rest of the process. The instrumentation thread — which is also the thread every test body
     * runs on — held the GIL for the entire run, and every cleaner blocked on the first
     * `PyGILState_Ensure` it reached.
     *
     * That state is invisible while nothing releases the GIL, which is why it survived: the old
     * `forceGC()` called `PyEval_SaveThread()`/`PyEval_RestoreThread` around its sleep, and that
     * accidental 200 ms window was the only thing letting cleaners through at all.
     */
    fun ensureInitialised() {
        // Stages the stdlib, sets PYTHONHOME, and calls Python3.initialize -- in that order, which
        // is the order the parking above depends on.
        PythonBootstrap.initialize(
            InstrumentationRegistry.getInstrumentation().targetContext,
            silent = true,
        )
        check(Py_IsInitialized() != 0) { "Py_Initialize() did not take effect" }
    }

    /**
     * Attaches the calling thread for the body of a test that reaches [bindings] directly.
     *
     * The object model takes the GIL for itself on every call, so tests written against `Python3`
     * and `PyObject` need nothing. Tests that call the raw JNI surface bypass that, and since
     * [ensureInitialised] now parks the main thread state they would otherwise run the C API with
     * no thread state attached, which is undefined behaviour rather than a clean failure.
     */
    fun attach(): Int = PyGILState_Ensure()

    fun detach(state: Int) = PyGILState_Release(state)

    /** Allocates a C string the caller must release with [freeUtf8]. */
    fun utf8(s: String): Long = bindings.ffiAllocUtf8(s)

    fun freeUtf8(ptr: Long) = bindings.ffiFreeUtf8(ptr)

    /** Runs [block] with a temporary C string, releasing it even if [block] throws. */
    inline fun <T> withUtf8(s: String, block: (Long) -> T): T {
        val ptr = utf8(s)
        try {
            return block(ptr)
        } finally {
            freeUtf8(ptr)
        }
    }
}
