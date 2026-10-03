package python.multiplatform.ref

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.ProxyTypeFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ClassLookup
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import python.native.ffi.bindings
import python.native.ffi.Panama
import python.native.ffi.toNativePointer
import python.multiplatform.BuildConfig

class Node(var ref: PyObject? = null) {
    var rawPtr: Long = 0L
    fun close() {
        ref?.close()
        ref = null
    }
}

/**
 * Same shape as [Node], but deliberately never registered with [ClassLookup]. It is the negative
 * control for [CycleCollectionTest.testCycleCollectionByGC]: without a registration `tp_traverse`
 * reports nothing, so the collector cannot see the loop and the handle survives. Named to match
 * `nativeTest`'s `UnregisteredCycleNode` / `androidInstrumentedTest`'s `UnregisteredAndroidCycleNode`.
 */
class UnregisteredNode {
    var ref: PyObject? = null
    var rawPtr: Long = 0L
}

/**
 * Which thread the last `tp_traverse` upcall arrived on.
 *
 * Test-owned, and that is the point: the lambda [CycleCollectionTest.setUp] registers with
 * [ClassLookup] runs *inside* [ProxyType.tp_traverse][python.multiplatform.ffi.ProxyType], itself
 * called from whichever thread CPython's collector is running on. Recording the thread there
 * therefore needs no hook in production code.
 *
 * Without it, "the handle was released after a `threading.Thread` ran `gc.collect()`" would only
 * show that the release happened -- not that the Panama upcall stub survived being entered from a
 * thread the JVM has never seen, which is the case this repository's `commonTest/README.md`
 * records as untested for this platform. Mirrors `androidInstrumentedTest`'s `TraverseThreadProbe`
 * (`Thread.currentThread()`, not a `pthread_t`) since both are JVM-hosted.
 */
object TraverseThreadProbe {
    @Volatile
    var lastThreadId: Long = -1L

    @Volatile
    var lastThreadName: String? = null

    fun record() {
        val t = Thread.currentThread()
        lastThreadId = t.id
        lastThreadName = t.name
    }

    fun reset() {
        lastThreadId = -1L
        lastThreadName = null
    }
}

/**
 * Desktop's copy of the cycle-collection suite. The mechanism differs from the other two -- the
 * slots are Panama upcall stubs here, `staticCFunction` on Kotlin/Native, JNI in
 * `artMain/cinterop/jni_onload.def` on ART -- so the three files are independent implementations
 * of the same claim rather than copies. See `commonTest/README.md`.
 *
 * ### `testDeallocOnAThreadCPythonCreated` and `testCycleCollectedOnAThreadCPythonCreated`
 *
 * `commonTest/README.md`'s comparison table recorded these as missing here, and unlike
 * [testHandleSurvivesWhenTraverseReportsNothing] (a same-mechanism placement gap) it called them
 * "a genuinely different question": whether a Panama upcall stub entered from a thread the JVM has
 * never seen -- a bare pthread `threading.Thread` spawns -- behaves the same as one entered from an
 * attached thread, which is not the same claim `AttachCurrentThreadAsDaemon` (ART) or an unattached
 * Kotlin/Native callback cover. Nothing in `ProxyTypeFactory.kt` or `Panama.kt` special-cases the
 * calling thread -- no `AttachCurrentThread` analogue is called anywhere in this backend -- so
 * whether it works was, before this file was extended, simply unverified. It works: both tests
 * below pass, which is the empirical answer `commonTest/README.md` left open.
 *
 * Adding them also brings the settle step the other two copies needed and this file's KDoc used to
 * explain the *absence* of, [settleJvmFinalisation]. `_t.start()`/`_t.join()` in both new tests
 * release the GIL, which is the one window a pending [java.lang.ref.Cleaner] release -- blocked in
 * `PyGILState_Ensure` with a `Py_DecRef` owed for some earlier test's leftover proxy -- can land in.
 * Landing there removes a reference from the proxy type between a test's two readings, and the test
 * then reports a `tp_dealloc` imbalance that does not exist, exactly as `643eaed9` (`nativeTest`)
 * and the `androidInstrumentedTest` copy both hit. [disposeProxy] and [settleJvmFinalisation] are
 * this file's copies of that fix, ported rather than newly designed.
 */
class CycleCollectionTest {

    @BeforeTest
    fun setUp() {
        if (!Python3.isInitialized) Python3.initialize()
        // Registered unconditionally. Nesting this inside the isInitialized check meant it only
        // ran when this test happened to be the one that started the interpreter -- true in
        // isolation, false in the suite. Without it ClassLookup.find returns null and tp_traverse
        // returns early having visited nothing, so the cycle is invisible to CPython's collector
        // and the failure looks like "the object was never tracked".
        run {
            ClassLookup.register(python.multiplatform.reflection.ReflectedClass(
                name = Node::class.qualifiedName!!,
                memberNames = emptyList(),
                traverse = { obj, visit ->
                    TraverseThreadProbe.record()
                    val node = obj as Node
                    if (node.rawPtr != 0L) {
                        visit(node.rawPtr)
                    }
                }
            ))
        }
    }

    @AfterTest
    fun tearDown() {
        HandleTable.releaseAll()
    }

    // ---- Support for the three tests below, kept separate from
    // testHandleReleasedWhenProxyDiesWithoutCycle's own local Unsafe accessors so this addition
    // does not touch that test's body. ----

    private val unsafeClass = Class.forName("sun.misc.Unsafe")
    private val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private val getLongMethod = unsafeClass.getMethod("getLong", Long::class.javaPrimitiveType)
    private val getIntMethod = unsafeClass.getMethod("getInt", Long::class.javaPrimitiveType)
    private val putLongMethod =
        unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)

    /** Writes [value] (a [HandleTable] handle) into the proxy's relative type data at [addr]. */
    private fun pokeLong(addr: Long, value: Long) { putLongMethod.invoke(theUnsafe, addr, value) }

    /**
     * `Py_REFCNT` at [addr], for whichever object header this build actually has -- see the KDoc on
     * [testHandleReleasedWhenProxyDiesWithoutCycle] for why offset 0 is only `ob_refcnt` on a build
     * with the global lock, and why the free-threaded branch is not just an offset fix.
     */
    private fun typeRefCount(addr: Long): Long =
        if (!BuildConfig.pythonFreeThreaded) {
            getLongMethod.invoke(theUnsafe, addr) as Long
        } else {
            val local = (getIntMethod.invoke(theUnsafe, addr + 12) as Int).toLong() and 0xFFFF_FFFFL
            val shared = getLongMethod.invoke(theUnsafe, addr + 16) as Long
            local + (shared shr 2)
        }

    /**
     * Drops the last reference to a proxy *now*, instead of leaving it to `java.lang.ref.Cleaner`.
     *
     * Ported from `nativeTest`/`androidInstrumentedTest`'s `disposeProxy`. Every test below that
     * closes a cycle ends with the proxy held by a [Node] and by nothing else. Once [tearDown] drops
     * the handle table's root, that node is JVM garbage, and the `Py_DecRef` its [PyObject] owes is
     * scheduled by the shared [java.lang.ref.Cleaner] (`PyAutoCloseable.desktop.kt`) and run on its
     * own thread -- at a moment no test chooses, and only once that thread can take the GIL. That is
     * a live grenade for [testDeallocOnAThreadCPythonCreated], the one test in this file that gives
     * the GIL up mid-measurement.
     */
    private fun disposeProxy(node: Node) {
        node.close()
    }

    /**
     * Blocks until a JVM collection stops changing the reference count at [typeAddr].
     *
     * The backstop for [disposeProxy]: it makes a test's opening reading independent of whatever any
     * earlier test happened to leave owing. **Must not be called while this thread holds the GIL** --
     * the Cleaner thread can only take it if this one has let go, exactly as in `nativeTest`'s
     * `settleKotlinFinalisation` and `androidInstrumentedTest`'s `settleJvmFinalisation`.
     *
     * @return how far the count moved while settling -- 0 once every test disposes of its own proxy.
     */
    private fun settleJvmFinalisation(typeAddr: Long): Long {
        val started = typeRefCount(typeAddr)
        var last = started
        var quiet = 0
        var turns = 0
        while (quiet < 3 && turns < 50) {
            System.gc()
            Thread.sleep(10)
            val now = typeRefCount(typeAddr)
            if (now == last) quiet++ else { quiet = 0; last = now }
            turns++
        }
        return started - last
    }

    @Test
    fun testCycleCollectionByGC() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")
            
            // 1. Create a Python object of proxy type
            val pyObjPtr = bindings.PyObject_CallObject(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")
            
            // 2. Create a Kotlin Node and register it
            val node = Node()
            val handle = HandleTable.register(node).raw
            
            // 3. Set the handle inside the Python object
            val typeDataPtr = bindings.PyObject_GetTypeData(pyObjPtr, proxyTypeAddr)
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val putLong = unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            putLong.invoke(theUnsafe, typeDataPtr, handle)
            
            // 4. Create a cycle
            val method = Class.forName("python.native.ffi.EmbedAPI_desktopKt").getMethod("toNativePointerFromRaw", Long::class.javaPrimitiveType)
            val nativePointer = method.invoke(null, pyObjPtr)
            val ref = PyObject::class.java.getDeclaredConstructors().first { it.parameterCount == 2 }.apply { isAccessible = true }.newInstance(nativePointer, false) as PyObject
            node.ref = ref
            node.rawPtr = pyObjPtr
            
            // Verify it's in HandleTable
            assertNotNull(HandleTable.resolveRaw(handle))
            
            println("Triggering GC, pyObjPtr is $pyObjPtr")

            // 5. Trigger GC
            //
            // The return value matters. PyRun_SimpleString fails immediately if the error
            // indicator is already set, so an exception left behind by an earlier test would make
            // gc.collect() silently not run -- which looks exactly like "the object was not
            // tracked", since tp_traverse is only called during a collection.
            val gcRc = bindings.PyRun_SimpleString("import gc; gc.collect()")
            println("gc.collect() rc = $gcRc, errorIndicatorSet = ${bindings.PyErr_Occurred() != 0L}")
            assertEquals(0, gcRc, "gc.collect() did not run -- the error indicator was already set on entry")
            
            println("After GC")
            
            // 6. Verify collection
            // If tp_clear was called, node.ref should be null, and HandleTable should not have the handle.
            val collectedObj = HandleTable.resolveRaw(handle)
            assertTrue(collectedObj == null, "Node was not collected by Python GC!")

            // tp_clear broke the loop but did not free the proxy -- `node` still holds the last
            // reference, and `Node.close()` existed for this and was never called. Left alone, that
            // reference is given back by the JVM cleaner at a moment no test chooses, and only once
            // that thread can take the GIL. Nothing in this file measures across a GIL release, so
            // it lands harmlessly here today (see the class KDoc); the other two copies have a test
            // that does, and on ART that leftover is a reproducible failure. Give it back under the
            // GIL this test is already holding instead.
            node.close()
            node.rawPtr = 0L
        }
    }

    /**
     * The negative control for [testCycleCollectionByGC].
     *
     * Built identically, except the Kotlin class is not in [ClassLookup], so `tp_traverse` returns
     * without reporting the reference. The collector then sees a proxy whose one reference is
     * external, decides it is reachable, and never calls `tp_clear` -- so the handle stays live.
     *
     * Without this, [testCycleCollectionByGC] could pass for the wrong reason: `resolveRaw`
     * returning null proves the handle was released, not that *the collector* released it. This is
     * the exact failure the registration comment in [setUp] is about -- an unregistered class
     * produces a live handle that looks like an untracked object. Ported from `nativeTest`'s test
     * of the same name; `commonTest/README.md`'s comparison table recorded this file as the one
     * copy missing it.
     */
    @Test
    fun testHandleSurvivesWhenTraverseReportsNothing() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val pyObjPtr = bindings.PyObject_CallObject(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")

            val node = UnregisteredNode()
            assertNull(
                ClassLookup.find(UnregisteredNode::class.qualifiedName!!),
                "this control only means anything while the class stays unregistered",
            )
            val handle = HandleTable.register(node).raw

            pokeLong(bindings.PyObject_GetTypeData(pyObjPtr, proxyTypeAddr), handle)

            node.ref = PyObject(assertNotNull(pyObjPtr.toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr

            val gcRc = bindings.PyRun_SimpleString("import gc; gc.collect()")
            assertEquals(0, gcRc, "gc.collect() did not run")

            assertNotNull(
                HandleTable.resolveRaw(handle),
                "the handle was released even though tp_traverse reported no reference -- " +
                    "something other than the cycle collector is clearing handles",
            )
            // Keeps `node` (and with it the Python object) alive across the collection, so the
            // assertion above is about the collector's decision and not about the JVM's GC.
            assertEquals(pyObjPtr, node.rawPtr)

            // The proxy survived on purpose, so this test is the one that owes its release. Not
            // disposeProxy, which takes a Node -- this node deliberately is not one.
            node.ref?.close()
            node.ref = null
        }
    }

    /**
     * The leak `tp_clear` cannot reach: a proxy that dies **without** being in a cycle.
     *
     * `tp_clear` only runs when the cyclic collector decides to break a loop, which is the
     * exceptional case. The ordinary one is a refcount reaching zero, and that goes straight to
     * `tp_dealloc` -- so with no `tp_dealloc` slot the [HandleTable] entry stays rooted for the
     * life of the interpreter, holding its Kotlin object with it. Almost every proxy dies this
     * way, so almost every proxy leaks.
     *
     * The [Node]s here deliberately reference nothing: `rawPtr` stays 0 and `ref` stays null, so
     * there is no cycle for the collector to find and `gc.collect()` is never called. The only
     * thing that can release these handles is `tp_dealloc`.
     *
     * ### The type refcount assertions
     *
     * `tp_dealloc` on a heap type has a second obligation beyond freeing the object: since 3.8
     * every instance holds a strong reference to its type, and the default `subtype_dealloc`
     * releases it. A hand-written `tp_dealloc` that forgets to leaks one type reference per
     * instance; one that releases it twice eventually frees the type out from under the process.
     * Both are checked here by reading the type's reference count out of its object header
     * directly. Reading it rather than calling `sys.getrefcount` keeps the argument's own
     * temporary reference out of the number.
     *
     * ### Two headers, not one
     *
     * `ob_refcnt` sits at offset 0 only on a build with the global lock. Free-threaded CPython
     * lays `PyObject` out differently (`Include/object.h`): offset 0 is `ob_tid`, the owning
     * thread's id, and the count is split between a thread-local `uint32 ob_ref_local` at +12 and
     * an atomic `Py_ssize_t ob_ref_shared` at +16 whose value is shifted left by
     * `_Py_REF_SHARED_SHIFT` (2). `Py_REFCNT` is the sum, and [typeRefCount] reproduces it.
     *
     * An earlier version of this probe read offset 0 unconditionally and therefore reported a
     * thread id on the free-threaded build -- the same ten-digit number for "before" and for
     * "alive", which is what the guard assertion below caught.
     *
     * ### Deferred references have to be materialised before the count means anything
     *
     * Fixing the offset is necessary and not sufficient. A heap type on the free-threaded build
     * carries `_PyGC_BITS_DEFERRED`, and its `ob_ref_shared` is initialised to the deferred
     * sentinel -- measured here on 3.14.7: `ob_gc_bits == 0x41` (`TRACKED | DEFERRED`) and
     * `ob_ref_shared == 0x3ffffffffffffffd`, i.e. a shared count of `PY_SSIZE_T_MAX / 8`. While
     * that bit is set, `PyType_GenericAlloc`'s `_Py_INCREF_TYPE` is a no-op for the owning thread,
     * so creating instances does not move the count at all. Measured: 100 instantiations left all
     * six header words bit-identical, and an eval-loop checkpoint
     * ([Python3.drainPendingReleases]) left them bit-identical too -- there is nothing queued to
     * merge, because the increments were never made.
     *
     * A collection is the one thing that converts deferred references into real ones. After
     * [PyGC_Collect] the same 100 instances show up as exactly 100 on the count, and `tp_dealloc`
     * then gives back exactly 100. So the invariant this test asserts does hold on both builds;
     * free-threaded it is simply not *observable* until a collection has run, which is why one is
     * taken on either side of the instantiation loop below. See
     * `docs/investigations/gc-scheduling-investigation.md`.
     *
     * The "rises while alive" assertion is not decoration: it is what proves the probe reads a
     * real refcount, so that the "returns afterwards" assertion cannot pass vacuously on a
     * garbage address -- and it is what caught both of the above.
     */
    @Test
    fun testHandleReleasedWhenProxyDiesWithoutCycle() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val putLong = unsafeClass.getMethod("putLong", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val getLong = unsafeClass.getMethod("getLong", Long::class.javaPrimitiveType)
            val getInt = unsafeClass.getMethod("getInt", Long::class.javaPrimitiveType)

            // `Py_REFCNT`, for whichever object header this build actually has. See the KDoc above:
            // offset 0 is `ob_refcnt` only on a build with the global lock.
            fun typeRefCount(): Long =
                if (!BuildConfig.pythonFreeThreaded) {
                    getLong.invoke(theUnsafe, proxyTypeAddr) as Long
                } else {
                    val local = (getInt.invoke(theUnsafe, proxyTypeAddr + 12) as Int).toLong() and 0xFFFF_FFFFL
                    val shared = getLong.invoke(theUnsafe, proxyTypeAddr + 16) as Long
                    local + (shared shr 2)
                }

            // Free-threaded only: materialise whatever deferred references this type already
            // carries, so that `before` and `alive` are read in the same state and the difference
            // between them is the instantiation loop and nothing else. On a build with the global
            // lock the count is always accurate and this would only cost a heap walk.
            if (BuildConfig.pythonFreeThreaded) python.native.ffi.PyGC_Collect()

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = typeRefCount()

            val rounds = 100
            val proxies = LongArray(rounds)
            val handles = LongArray(rounds)

            repeat(rounds) { i ->
                val pyObjPtr = bindings.PyObject_CallObject(proxyTypeAddr, 0L)
                assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type at round $i")
                proxies[i] = pyObjPtr

                val node = Node() // rawPtr stays 0 and ref stays null: nothing to make a cycle out of
                val handle = HandleTable.register(node).raw
                handles[i] = handle
                putLong.invoke(theUnsafe, bindings.PyObject_GetTypeData(pyObjPtr, proxyTypeAddr), handle)
            }

            assertEquals(
                liveBefore + rounds, HandleTable.liveCount,
                "setup failed: $rounds handles should be rooted while the proxies are alive"
            )
            // The instantiations above were deferred on a free-threaded build; this is what turns
            // them into countable references. Without it `alive` reads exactly `before`.
            if (BuildConfig.pythonFreeThreaded) python.native.ffi.PyGC_Collect()

            val typeRefAlive = typeRefCount()
            assertEquals(
                typeRefBefore + rounds, typeRefAlive,
                "each live instance of a heap type holds one reference to that type, so the count " +
                    "should have risen by exactly $rounds (before: $typeRefBefore, alive: $typeRefAlive). " +
                    "If this fails the ob_refcnt probe below is not reading a refcount and the " +
                    "balance assertion after it would be meaningless"
            )

            // Drop the only reference to each proxy. This takes the refcount to zero, which is
            // tp_dealloc's path and *not* tp_clear's -- the cyclic collector never runs here.
            repeat(rounds) { i -> bindings.Py_DecRef(proxies[i]) }

            val stillRooted = handles.count { HandleTable.resolveRaw(it) != null }
            assertEquals(
                0, stillRooted,
                "$stillRooted of $rounds handles are still rooted after their proxies were " +
                    "deallocated. A proxy that dies without a cycle never runs tp_clear, so " +
                    "without a tp_dealloc slot its HandleTable entry -- and the Kotlin object it " +
                    "holds -- leaks for the life of the interpreter"
            )
            assertEquals(
                liveBefore, HandleTable.liveCount,
                "the table should be back to its starting size once every proxy is gone"
            )
            assertEquals(
                typeRefBefore, typeRefCount(),
                "tp_dealloc must release the instance's reference to its heap type exactly once: " +
                    "a count above $typeRefBefore means it was never released and the type leaks " +
                    "per instance, below means it was released twice and the type will be freed " +
                    "while still in use"
            )
        }
    }

    /**
     * `tp_dealloc` reached from a thread CPython created, which this Panama upcall stub has never
     * been entered from before.
     *
     * Every other test here runs the slots on the thread that has been running the suite all
     * along, so the JVM was already there for it and the callback is an ordinary one. This
     * repository's `commonTest/README.md` records the gap: whether a Panama upcall stub survives
     * being entered from a thread the JVM never created is a materially different question from
     * `AttachCurrentThreadAsDaemon` (ART) or an unattached Kotlin/Native callback, and was
     * unverified. A `threading.Thread` is a bare pthread, so handing the proxy's last reference to
     * one and letting that thread drop it is how this runs the slot there.
     *
     * The proxy is parked in `__main__` so that Python owns the only reference; the Kotlin side
     * keeps none. Rebinding the name on the worker thread takes the refcount to zero there, which
     * is `tp_dealloc` on a thread the JVM has never seen.
     */
    @Test
    fun testDeallocOnAThreadCPythonCreated() {
        // Sampled below: the proxy type's reference count, before and after a window in which this
        // thread *gives up the GIL* -- `_t.start()` and `_t.join()` both release it, which is the
        // whole point of the test. That makes this the one test here whose measurement a pending
        // Cleaner release can walk into: the Cleaner thread wants the GIL, this is the only place
        // it is offered, and a leftover proxy deallocated there takes one reference off the type
        // between the two readings. The failure reads as "tp_dealloc did not balance the
        // instance's reference to its heap type" while tp_dealloc has balanced it exactly.
        //
        // So nothing may still be owed when the first reading is taken. Called outside withGIL --
        // see settleJvmFinalisation for what happens if it is not.
        val stillOwed = settleJvmFinalisation(ProxyTypeFactory.createProxyType())
        assertEquals(
            0L, stillOwed,
            "$stillOwed proxy deallocation(s) were still owed to the JVM's Cleaner when this " +
                "test began, which means some test above stopped releasing its own proxy. Left " +
                "alone they land during the GIL-release window below and this test fails " +
                "claiming tp_dealloc did not balance the heap type -- see disposeProxy",
        )

        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val liveBefore = HandleTable.liveCount
            val typeRefBefore = typeRefCount(proxyTypeAddr)

            val pyObjPtr = bindings.PyObject_CallObject(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")

            val handle = HandleTable.register(Node()).raw
            pokeLong(bindings.PyObject_GetTypeData(pyObjPtr, proxyTypeAddr), handle)

            // Hand the reference to __main__, then drop ours: Python now owns the only one.
            val main = bindings.PyImport_ImportModule("__main__")
            assertTrue(main != 0L, "could not import __main__")
            val globals = bindings.PyObject_GetAttrString(main, "__dict__")
            assertTrue(globals != 0L, "could not read __main__.__dict__")
            assertEquals(
                0, bindings.PyDict_SetItemString(globals, "_pmp_thread_proxy", pyObjPtr),
                "could not park the proxy in __main__",
            )
            bindings.Py_DecRef(pyObjPtr)
            bindings.Py_DecRef(globals)
            bindings.Py_DecRef(main)

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before the drop")

            // The gc.collect() in the worker is not what drops the proxy -- __main__ still holds
            // it at that point, so it is reachable. It is there to make the worker thread
            // *identify itself*: collecting calls tp_traverse on every tracked object in the
            // generation, this proxy included, and the traverse lambda in setUp records the
            // calling thread. Without it the release below would be observable but the thread it
            // happened on would not.
            //
            // t.join() releases the GIL, so the worker really does run both statements.
            TraverseThreadProbe.reset()
            val here = Thread.currentThread().id
            val script = """
                import gc, threading
                def _pmp_drop():
                    global _pmp_thread_proxy
                    gc.collect()
                    _pmp_thread_proxy = None
                _t = threading.Thread(target=_pmp_drop)
                _t.start()
                _t.join()
            """.trimIndent()
            val rc = bindings.PyRun_SimpleString(script)
            assertEquals(0, rc, "the worker thread script did not run")

            val seen = TraverseThreadProbe.lastThreadId
            assertTrue(
                seen != -1L,
                "no slot callback reached Kotlin at all while the worker ran, so nothing here " +
                    "says which thread the dealloc below happened on",
            )
            assertTrue(
                seen != here,
                "the slots ran on the thread that has been running this suite all along " +
                    "(id $here, ${TraverseThreadProbe.lastThreadName}), so CPython's worker did " +
                    "not carry the callback and the unattached-thread case is still untested",
            )

            assertNull(
                HandleTable.resolveRaw(handle),
                "the proxy's last reference was dropped on a thread CPython created, and the " +
                    "handle is still rooted -- tp_dealloc's Panama upcall stub did not reach " +
                    "Kotlin from that thread",
            )
            assertEquals(liveBefore, HandleTable.liveCount, "the table did not return to its starting size")
            assertEquals(
                typeRefBefore, typeRefCount(proxyTypeAddr),
                "tp_dealloc ran on a CPython-created thread but did not balance the instance's " +
                    "reference to its heap type",
            )
        }
    }

    /**
     * The collector itself running on a thread CPython created -- `tp_traverse` and `tp_clear`,
     * where [testDeallocOnAThreadCPythonCreated] covers `tp_dealloc`.
     *
     * Same cycle as [testCycleCollectionByGC], except `gc.collect()` is called from a
     * `threading.Thread` instead of from the thread that has been running the suite all along.
     */
    @Test
    fun testCycleCollectedOnAThreadCPythonCreated() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            val pyObjPtr = bindings.PyObject_CallObject(proxyTypeAddr, 0L)
            assertTrue(pyObjPtr != 0L, "Failed to instantiate proxy type")

            val node = Node()
            val handle = HandleTable.register(node).raw
            pokeLong(bindings.PyObject_GetTypeData(pyObjPtr, proxyTypeAddr), handle)

            node.ref = PyObject(assertNotNull(pyObjPtr.toNativePointer()), borrowed = false)
            node.rawPtr = pyObjPtr

            assertNotNull(HandleTable.resolveRaw(handle), "handle did not resolve before collection")

            TraverseThreadProbe.reset()
            val here = Thread.currentThread().id
            val script = """
                import gc, threading
                _t = threading.Thread(target=gc.collect)
                _t.start()
                _t.join()
            """.trimIndent()
            val rc = bindings.PyRun_SimpleString(script)
            assertEquals(0, rc, "the worker thread script did not run")

            val seen = TraverseThreadProbe.lastThreadId
            assertTrue(
                seen != -1L,
                "tp_traverse never reached Kotlin during the worker's collection, so the cycle " +
                    "was invisible to it",
            )
            assertTrue(
                seen != here,
                "tp_traverse ran on the thread that has been running this suite all along " +
                    "(id $here, ${TraverseThreadProbe.lastThreadName}) rather than on CPython's " +
                    "worker, so the unattached-thread case is still untested",
            )

            assertNull(
                HandleTable.resolveRaw(handle),
                "the cycle was not collected when gc.collect() ran on a CPython-created thread, " +
                    "though it is on the thread that has been running the suite -- tp_traverse " +
                    "or tp_clear did not reach Kotlin from a thread with no attached runtime",
            )

            // As in testCycleCollectionByGC: tp_clear broke the loop, `node` still holds the
            // proxy's last reference, and this test gives it back rather than owing it.
            disposeProxy(node)
        }
    }

    /**
     * A Python subclass of the proxy heap type is deallocated through CPython's own
     * `subtype_dealloc`, which untracks the instance *before* delegating to the base's
     * `tp_dealloc` -- and [ProxyTypeFactory.tp_dealloc] untracks again, unconditionally. Whether
     * that second untrack is safe decides whether the generated proxy classes can ever subclass
     * this type, which is what `docs/roadmap/ROADMAP.md` §7 records as the open question blocking real cycle
     * collection.
     *
     * The answer is yes, and CPython says so in the header this repository vendors:
     * `internal/pycore_gc.h` -- *"See also the public PyObject_GC_UnTrack() which accept an object
     * which is not tracked."* The internal variant asserts on it; the public one, which this code
     * calls, is defined to tolerate it.
     *
     * A documented guarantee is not a run, so this exercises the path. The tracking assertion is
     * the part that keeps the test from being vacuous: if the instance were never GC-tracked, no
     * second untrack would happen and the test would pass without testing anything. It fails
     * loudly instead.
     */
    @Test
    fun aPythonSubclassOfTheProxyTypeSurvivesBeingUntrackedTwice() {
        python.multiplatform.ffi.withGIL {
            val proxyTypeAddr = ProxyTypeFactory.createProxyType()
            assertTrue(proxyTypeAddr != 0L, "Proxy type creation failed")

            // The type object reaches Python as an address, then back to an object through ctypes:
            // there is no other route from a raw `PyTypeObject*` to a usable base class in a script.
            val sys = bindings.PyImport_ImportModule("sys")
            val typeObj = bindings.PyLong_FromLongLong(proxyTypeAddr)
            bindings.PyObject_SetAttrString(sys, "_test_proxy_type_addr", typeObj)
            bindings.Py_DecRef(typeObj)
            bindings.Py_DecRef(sys)

            val script = """
import sys
import ctypes
import gc

proxy_type = ctypes.cast(sys._test_proxy_type_addr, ctypes.py_object).value

class MyProxy(proxy_type):
    pass

instance = MyProxy()

# Without this the test proves nothing: an untracked instance is never untracked twice.
if not gc.is_tracked(instance):
    raise AssertionError('instance is not GC-tracked, so the double untrack never happens')

del instance
gc.collect()
"""
            val rc = bindings.PyRun_SimpleString(script)
            assertEquals(0, rc, "the subclass did not survive collection -- see stderr for the Python side")
        }
    }
}
