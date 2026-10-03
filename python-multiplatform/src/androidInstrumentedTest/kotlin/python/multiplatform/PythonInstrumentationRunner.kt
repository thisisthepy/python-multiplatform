package python.multiplatform

import androidx.test.runner.AndroidJUnitRunner
import python.native.ffi.PythonOnDevice

/**
 * Brings up CPython before any test class is loaded.
 *
 * `commonTest` is platform-agnostic by construction: its fixture reaches straight for
 * `Python3.initialize()`. On desktop and the iOS simulator that is enough, because the build
 * hands those runs a `PYTHONHOME` with a real standard library behind it. On Android the stdlib
 * lives inside the APK as assets and has to be unpacked to app-private storage first — without
 * that, `Py_Initialize()` does not fail, it *aborts the process* with "Failed to import encodings
 * module", taking every other test on the device with it.
 *
 * Doing it in the runner rather than in a `@Before` keeps `commonTest` free of Android knowledge,
 * which it has to be — it also compiles for iOS and desktop.
 *
 * [PythonOnDevice.ensureInitialised] is idempotent, so the instrumented tests that call it
 * themselves are unaffected.
 */
class PythonInstrumentationRunner : AndroidJUnitRunner() {

    /**
     * `onStart` runs on the instrumentation thread — the same thread that then executes the
     * tests.
     *
     * This used to say that mattered because `Py_Initialize()` leaves the GIL held by its caller,
     * so initialising here "keeps the GIL on the thread that is about to use it". That was the
     * pre-§1 contract and it is now exactly backwards: the GIL must **not** stay held, or no
     * cleaner thread can ever attach to release a reference. `PythonOnDevice.ensureInitialised`
     * parks the thread state, and every entry into Python — including the ones on this thread —
     * goes through `withGIL`.
     *
     * What still matters about the thread is only that the staging and the first C API call
     * happen off the main thread, before any test class is loaded.
     */
    override fun onStart() {
        PythonOnDevice.ensureInitialised()
        super.onStart()
    }
}
