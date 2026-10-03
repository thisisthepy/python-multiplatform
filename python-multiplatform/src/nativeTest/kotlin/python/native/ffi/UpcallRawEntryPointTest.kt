@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package python.native.ffi

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.cstr
// The operator that makes a CPointer<CFunction<...>> callable is a top-level declaration, not a
// member, so it has to be imported by name for `resolve(...)` below to mean a C call at all.
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.TrampolineFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Kotlin/Native (iOS + androidNative) half of `UpcallEntryTest`'s (`commonTest`) contract:
 * resolving a name to a callable and installing it as `_pm_bound`, through the real `PyMethodDef`
 * bootstrap [UpcallEntry.publish] installs. See that file's docstring for why this is a
 * `PyMethodDef` and not `ctypes` on this target.
 */
actual fun bindUpcallOrNull(name: String): Boolean {
    val globals: PyObject = PythonTestFixture.mainGlobals()
    check(UpcallEntry.publish(globals.pointer)) { "the upcall bootstrap could not be published" }
    Python3.exec("_pm_h = _pm_resolve('$name')")
    val resolved = PythonTestFixture.eval("_pm_h").toString() != "-1"
    Python3.exec(if (resolved) "_pm_bound = _pm_bind(_pm_h)" else "_pm_bound = None")
    return resolved
}

/** Releases through `_pm_release`, the same `PyMethodDef` a proxy's `tp_dealloc` would call. */
actual fun releaseUpcallHandle(handle: Long): Int =
    PythonTestFixture.eval("_pm_release($handle)").toString().toInt()

/**
 * The raw `pm_upcall_*` entry points, called **through their published addresses** with the C
 * ABI rather than as Kotlin functions.
 *
 * This is the whole of what a foreign caller does: `ctypes` on Android, or a C host anywhere.
 * Calling `pmUpcallInvoke` directly from Kotlin would prove nothing about the shape -- the
 * compiler would pick the Kotlin calling convention and the `staticCFunction` wrapper, which
 * is where a mismatched signature actually shows up, would never run.
 *
 * `dlsym` is deliberately not how the addresses are obtained; see [UpcallEntry] for the
 * measurement that settled why.
 *
 * There is no comparable raw address on desktop, Android/ART or wasmJs, so unlike the rest of
 * this contract this test has no `commonTest` counterpart; it stays here as the one thing only
 * this target can check.
 */
class UpcallRawEntryPointTest {

    @Test
    fun theRawEntryPointsAreCallableCFunctionsOfTheDocumentedShape() =
        PythonTestFixture.withInterpreter {
            val resolve = UpcallEntry.resolveAddress
                .toCPointer<CFunction<(CPointer<ByteVar>?) -> Long>>()
            val invoke = UpcallEntry.invokeAddress
                .toCPointer<CFunction<(Long, COpaquePointer?) -> COpaquePointer?>>()
            val release = UpcallEntry.releaseObjectAddress
                .toCPointer<CFunction<(Long) -> Int>>()
            assertNotNull(resolve)
            assertNotNull(invoke)
            assertNotNull(release)

            UpcallTable.install(listOf(TrampolineFragment))
            try {
                val handle = memScoped { resolve("trampoline.Target.<init>".cstr.ptr) }
                assertTrue(handle >= 0, "pm_upcall_resolve did not find a name the table holds")
                assertEquals(-1L, memScoped { resolve("trampoline.nothingIsCalledThis".cstr.ptr) })

                // A tuple built by the interpreter, passed as a borrowed PyObject* exactly as
                // CPython would pass it. `keepAlive` is not optional: nothing else names this
                // tuple, and a cleaner firing between these two lines would free what the entry
                // point is walking.
                val keepAlive = PythonTestFixture.eval("('kotlin',)")
                val rootedBefore = HandleTable.liveCount
                val raw = invoke(handle, keepAlive.pointer.toPlatformPointer())
                assertNotNull(raw, "pm_upcall_invoke returned NULL through its published address")

                // The result is a new reference and the wrapper adopts it rather than taking a
                // second.
                val objectHandle = PyObject(raw.toLong().toNativePointer()!!, false).toString().toLong()
                assertEquals(rootedBefore + 1, HandleTable.liveCount)
                assertEquals(1, release(objectHandle))
                assertEquals(rootedBefore, HandleTable.liveCount)
                assertEquals(0, release(objectHandle), "a double release must be a no-op")
            } finally {
                UpcallTable.clear()
                HandleTable.releaseAll()
            }
        }
}
