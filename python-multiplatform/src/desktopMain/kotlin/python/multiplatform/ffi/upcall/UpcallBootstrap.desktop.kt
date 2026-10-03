package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.native.ffi.UpcallStub

actual object UpcallBootstrap {
    actual fun publishToGlobals(): Boolean {
        Python3.exec(
            """
            import ctypes

            _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)(${UpcallStub.resolveHandleStubAddr})
            _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
                ${UpcallStub.invokeWithArgsStubAddr}
            )
            _pm_release = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_long)(
                ${UpcallStub.releaseObjectStubAddr}
            )
            _pm_cancel = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_long)(
                ${UpcallStub.cancelCallStubAddr}
            )
            """.trimIndent()
        )
        return true
    }
}
