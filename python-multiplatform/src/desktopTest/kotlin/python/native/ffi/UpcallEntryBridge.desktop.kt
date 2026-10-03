package python.native.ffi

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture

/**
 * Desktop's half of `UpcallEntryTest`'s (`commonTest`) contract: a `ctypes` shim over the same
 * Panama upcall stubs [UpcallStub] builds for a real proxy method -- `restype=py_object` makes the
 * result readable from Python, and `CFUNCTYPE` (not `PYFUNCTYPE`) releases the GIL around the call,
 * which is the harder case and the one that segfaulted every C API call in the trampoline until it
 * stopped trusting the thread's nesting depth. The stub shape used for invocation, `(long, long)
 * -> long`, is deliberately the same one a `PyCFunction` slot needs (`PyObject
 * *(PyObject *self, PyObject *args)`): when the generated proxy type lands, `self` takes the
 * handle's place and no new stub shape is needed.
 *
 * Bootstrapping the three `ctypes.CFUNCTYPE`s is cheap enough to redo on every [bindUpcallOrNull]
 * call, which keeps this actual as thin as the others rather than adding its own install/cleanup
 * lifecycle.
 */
actual fun bindUpcallOrNull(name: String): Boolean {
    Python3.exec(
        """
        import ctypes

        _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${UpcallStub.resolveHandleStubAddr})
        _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
            ${UpcallStub.invokeWithArgsStubAddr}
        )
        # What a proxy's tp_dealloc would call. Reuses the (long) -> int shape Panama already
        # builds for tp_clear, so it costs no stub of its own.
        _pm_release = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_long)(
            ${UpcallStub.releaseObjectStubAddr}
        )
        # What a cancelled asyncio.Future's done callback calls, so a suspended Kotlin coroutine
        # hears about the cancellation at its next `ensureActive()` rather than at completion.
        # Same (long) -> int shape as _pm_release, so it is one more binding and no new stub shape.
        _pm_cancel = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_long)(
            ${UpcallStub.cancelCallStubAddr}
        )
        """.trimIndent(),
    )
    Python3.exec("_pm_h = _pm_resolve('$name'.encode('utf-8'))")
    val resolved = PythonTestFixture.eval("_pm_h").toString() != "-1"
    Python3.exec(if (resolved) "_pm_bound = lambda *a: _pm_invoke(_pm_h, a)" else "_pm_bound = None")
    return resolved
}

/** Releases through the ctypes-wrapped `_pm_release`, over the same stub a proxy's `tp_dealloc` would call. */
actual fun releaseUpcallHandle(handle: Long): Int =
    PythonTestFixture.eval("_pm_release($handle)").toString().toInt()
