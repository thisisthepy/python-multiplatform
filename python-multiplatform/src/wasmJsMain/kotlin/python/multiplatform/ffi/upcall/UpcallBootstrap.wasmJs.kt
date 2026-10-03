package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.native.ffi.UpcallEntry

actual object UpcallBootstrap {
    actual fun publishToGlobals(): Boolean {
        val globals = Python3.import("__main__").dict
        return UpcallEntry.publish(globals.pointer)
    }
}
