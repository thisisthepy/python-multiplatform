@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.multiplatform.ref

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.wasm.unsafe.Pointer
import python.multiplatform.ffi.ProxyType
import python.multiplatform.ffi.ProxyTypeFactory
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.withGIL
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ReflectedClass
import python.native.ffi.Wasm
import python.native.ffi.toNativePointerFromRaw

/** A Kotlin object that can point back at the Python proxy holding it. Mirrors `desktopTest`'s. */
private class WasmNode(var ref: PyObject? = null) {
    var rawPtr: Long = 0L
}

/**
 * ROADMAP §7 and §10: CPython calling Kotlin, and a cross-language cycle actually being collected.
 *
 * `ProxyTypeFactory` threw on this target until the registration step existed, so this is the test
 * that says whether upcalls work here at all -- every assertion below runs Kotlin code that CPython
 * itself invoked, through `call_indirect` into a `@WasmExport`.
 *
 * The two cases are deliberately the same pair `desktopTest/CycleCollectionTest` uses, because they
 * exercise two different slots that fail in different ways: `tp_clear` on the cyclic route and
 * `tp_dealloc` on the ordinary one. A proxy that dies without a cycle -- which is almost all of
 * them -- never goes near `tp_clear`.
 */
class WasmCycleCollectionTest {

    @BeforeTest
    fun setUp() {
        if (!Python3.isInitialized) Python3.initialize(silent = true)
        // Registered unconditionally. Nesting this inside the isInitialized check makes it run only
        // when this test happens to be the one that starts the interpreter -- true in isolation,
        // false in a suite -- and then `ClassLookup.find` returns null, `tp_traverse` returns early
        // having visited nothing, and the cycle is invisible to the collector. That reads exactly
        // like "the object was never tracked".
        ClassLookup.register(
            ReflectedClass(
                name = WasmNode::class.qualifiedName!!,
                memberNames = emptyList(),
                traverse = { obj, visit ->
                    val node = obj as WasmNode
                    if (node.rawPtr != 0L) visit(node.rawPtr)
                }
            )
        )
    }

    @AfterTest
    fun tearDown() {
        HandleTable.releaseAll()
    }

    private fun refCount(objPtr: Int): Int = Pointer(objPtr.toUInt()).loadInt()

    @Test
    fun theProxyTypeIsBuiltAndItsSlotsAreRealFunctionPointers() {
        withGIL {
            val type = ProxyTypeFactory.createProxyType()
            assertTrue(type != 0L, "PyType_FromSpec produced no type")
            assertEquals(
                type, ProxyTypeFactory.createProxyType(),
                "createProxyType must be idempotent -- a second heap type would give two " +
                    "populations of proxies with separate handle slots"
            )

            // If registration had silently not happened, the slots would hold 0 and CPython would
            // simply inherit its defaults, which is the failure that looks like success.
            val typePtr = ProxyType.proxyTypePtr
            assertTrue(python.native.ffi.bindings.PyType_GetSlot(typePtr, 71) != 0, "tp_traverse slot is empty")
            assertTrue(python.native.ffi.bindings.PyType_GetSlot(typePtr, 51) != 0, "tp_clear slot is empty")
            assertTrue(python.native.ffi.bindings.PyType_GetSlot(typePtr, 52) != 0, "tp_dealloc slot is empty")
        }
    }

    @Test
    fun cpythonCallsBackIntoKotlinAndBreaksTheCycle() {
        withGIL {
            val typeRaw = ProxyTypeFactory.createProxyType()
            assertTrue(typeRaw != 0L, "proxy type creation failed")
            val typePtr = ProxyType.proxyTypePtr

            val proxy = python.native.ffi.bindings.PyObject_CallObject(typePtr, 0)
            assertTrue(proxy != 0, "could not instantiate the proxy type")

            val node = WasmNode()
            val handle = HandleTable.register(node).raw
            assertTrue(ProxyType.putHandle(proxy, handle), "the type has no handle slot")
            assertEquals(handle, ProxyType.peekHandle(proxy))

            // Close the loop: the proxy holds the Kotlin object through the handle, and the Kotlin
            // object holds the proxy through a PyObject wrapper. Neither collector can see the
            // whole thing on its own; only tp_traverse reaching through the handle can.
            node.ref = PyObject(assertNotNull(proxy.toNativePointerFromRaw()), borrowed = false)
            node.rawPtr = proxy.toUInt().toLong()

            assertNotNull(HandleTable.resolveRaw(handle), "setup failed: the handle is not rooted")

            // The return value matters. PyRun_SimpleString fails immediately if the error indicator
            // is already set, so an exception left behind by an earlier test would make
            // gc.collect() silently not run -- which looks identical to "the object was never
            // tracked", since tp_traverse only runs during a collection.
            val rc = python.native.ffi.bindings.PyRun_SimpleString(Wasm.scratchUtf8("import gc; gc.collect()"))
            assertEquals(
                0, rc,
                "gc.collect() did not run -- the error indicator was already set on entry"
            )

            assertNull(
                HandleTable.resolveRaw(handle),
                "the cycle was not collected: CPython either never called tp_traverse (so it could " +
                    "not see the loop) or never called tp_clear (so the handle stayed rooted)"
            )
        }
    }

    @Test
    fun aProxyThatDiesWithoutACycleStillReleasesItsHandle() {
        withGIL {
            assertTrue(ProxyTypeFactory.createProxyType() != 0L)
            val typePtr = ProxyType.proxyTypePtr

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = refCount(typePtr)

            val rounds = 100
            val proxies = IntArray(rounds)
            val handles = LongArray(rounds)

            repeat(rounds) { i ->
                val proxy = python.native.ffi.bindings.PyObject_CallObject(typePtr, 0)
                assertTrue(proxy != 0, "could not instantiate the proxy type at round $i")
                proxies[i] = proxy
                // rawPtr stays 0 and ref stays null: there is no cycle here, so the collector never
                // runs and tp_clear is never reached. Only tp_dealloc can release these.
                handles[i] = HandleTable.register(WasmNode()).raw
                assertTrue(ProxyType.putHandle(proxy, handles[i]))
            }

            assertEquals(
                liveBefore + rounds, HandleTable.liveCount,
                "setup failed: $rounds handles should be rooted while the proxies are alive"
            )
            val typeRefAlive = refCount(typePtr)
            assertEquals(
                typeRefBefore + rounds, typeRefAlive,
                "each live instance of a heap type holds one reference to that type, so the count " +
                    "should have risen by exactly $rounds (before: $typeRefBefore, alive: " +
                    "$typeRefAlive). If this fails, the ob_refcnt probe is not reading a refcount " +
                    "and the balance assertion below would be meaningless"
            )

            repeat(rounds) { i -> python.native.ffi.bindings.Py_DecRef(proxies[i]) }

            val stillRooted = handles.count { HandleTable.resolveRaw(it) != null }
            assertEquals(
                0, stillRooted,
                "$stillRooted of $rounds handles are still rooted after their proxies were " +
                    "deallocated -- tp_dealloc did not run, or did not reach the handle"
            )
            assertEquals(
                liveBefore, HandleTable.liveCount,
                "the table should be back to its starting size once every proxy is gone"
            )
            assertEquals(
                typeRefBefore, refCount(typePtr),
                "tp_dealloc must release the instance's reference to its heap type exactly once: " +
                    "above $typeRefBefore means it never was, and the type leaks per instance; " +
                    "below means it was released twice and the type will be freed while in use"
            )
        }
    }

    @Test
    fun pythonSubclassCycleIsCollectedThroughKotlin() {
        withGIL {
            assertTrue(ProxyTypeFactory.installGcBase(), "installGcBase failed")

            val script = """
class MyProxy(_pm_proxy_base):
    pass
instance = MyProxy()
"""
            val rc = python.native.ffi.bindings.PyRun_SimpleString(Wasm.scratchUtf8(script))
            if (rc != 0) {
                python.native.ffi.bindings.PyErr_Print()
            }
            assertEquals(0, rc, "Python script failed")

            val main = python.native.ffi.bindings.PyImport_ImportModule(Wasm.scratchUtf8("__main__"))
            val instance = python.native.ffi.bindings.PyObject_GetAttrString(main, Wasm.scratchUtf8("instance"))

            val node = WasmNode()
            val handle = HandleTable.register(node).raw

            // Assign the handle from Python
            val handleObj = python.native.ffi.bindings.PyLong_FromLongLong(handle)
            val rc1 = python.native.ffi.bindings.PyObject_SetAttrString(instance, Wasm.scratchUtf8("_pm_handle"), handleObj)
            if (rc1 != 0) {
                python.native.ffi.bindings.PyErr_Print()
            }
            python.native.ffi.bindings.Py_DecRef(handleObj)
            assertEquals(0, rc1, "failed to set _pm_handle")

            assertEquals(handle, ProxyType.peekHandle(instance), "_pm_handle did not write to the right offset")

            // Complete the cycle on Kotlin side
            node.ref = PyObject(assertNotNull(instance.toNativePointerFromRaw()), borrowed = false)
            node.rawPtr = instance.toUInt().toLong()

            assertNotNull(HandleTable.resolveRaw(handle), "setup failed")

            // Delete the instance from Python globals so it is only held in the cycle
            val rc2 = python.native.ffi.bindings.PyRun_SimpleString(Wasm.scratchUtf8("del instance\nimport gc\ngc.collect()"))
            assertEquals(0, rc2, "gc.collect() script failed")

            assertNull(
                HandleTable.resolveRaw(handle),
                "the cycle was not collected"
            )
            
            // Give back the last ref 
            node.ref?.close()
            node.ref = null
            python.native.ffi.bindings.Py_DecRef(main)
        }
    }
}
