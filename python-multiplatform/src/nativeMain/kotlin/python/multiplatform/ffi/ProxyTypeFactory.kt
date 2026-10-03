@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package python.multiplatform.ffi

import kotlinx.cinterop.*
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.native.ffi.bindings.PyObject as CPyObject
import python.native.ffi.bindings.PyMemberDef
import python.native.ffi.bindings.PyTypeObject
import python.native.ffi.bindings.PyType_FromSpec
import python.native.ffi.bindings.PyType_Slot
import python.native.ffi.bindings.PyType_Spec
import python.native.ffi.bindings.PyObject_GetTypeData
import python.native.ffi.bindings.PyObject_GC_UnTrack
import python.native.ffi.bindings.PyObject_Type
import python.native.ffi.bindings.PyType_GetSlot
import python.native.ffi.bindings.Py_DecRef
import python.native.ffi.bindings.visitproc
import python.native.ffi.PyImport_AddModule
import python.native.ffi.PyObject_SetAttrString
import python.native.ffi.toNativePointer

/** `Py_tp_free` from CPython's `typeslots.h`. Slot ids are ABI, not header-version-dependent. */
private const val PY_TP_FREE = 74

/** `Py_tp_members` from CPython's `typeslots.h`. */
private const val PY_TP_MEMBERS = 72

/** `Py_T_LONG` from `descrobject.h` -- an 8-byte signed integer member, the same width as the
 *  handle slot [handleSlot] already reads and writes by hand. */
private const val PY_T_LONG = 2

/** `Py_RELATIVE_OFFSET` from `descrobject.h` (CPython 3.12+): the member's `offset` is relative to
 *  the type's own extra storage -- the same base [PyObject_GetTypeData] already computes for
 *  [handleSlot] -- rather than to byte 0 of the object, which is what keeps it correct under a
 *  Python subclass that adds fields of its own ahead of it in memory (`Py_TPFLAGS_BASETYPE`, and
 *  precisely the multiple/deep-inheritance case this type exists to allow). */
private const val PY_RELATIVE_OFFSET = 8

/**
 * The proxy type, as a raw address, or 0 before [ProxyTypeFactory.createProxyType] has run.
 *
 * A top-level `var` rather than a member of the `object`, because [proxyTraverse] and
 * [proxyClear] reach it from inside a `staticCFunction` and a top-level property is the cheapest
 * thing to reach from there. Under Kotlin/Native's current memory model a top-level `var` is a
 * single global shared by every thread (it is not `@ThreadLocal`), which is what this needs: the
 * type is created once and read from whichever thread CPython's collector happens to run on.
 *
 * It is written exactly once, before any instance of the type exists, and read-only afterwards,
 * so the absence of synchronisation here is not a race. Every read also happens under the GIL --
 * the collector holds it when it calls the slots -- which is the same rule the rest of the
 * boundary lives under.
 */
private var proxyTypeAddress: Long = 0L

/**
 * The 8 bytes of relative type data on [proxyTypeAddress], reinterpreted as the handle slot.
 *
 * `PyObject_GetTypeData` is the abi3-safe way to reach data appended by a negative
 * `basicsize`: it does the offset arithmetic from the type, so nothing here has to know the
 * layout of `PyObject` or of the base type.
 */
private fun handleSlot(self: CPointer<CPyObject>?): CPointer<LongVar>? {
    val obj = self ?: return null
    val type = proxyTypeAddress.toCPointer<PyTypeObject>() ?: return null
    return PyObject_GetTypeData(obj, type)?.reinterpret()
}

/**
 * A NUL-terminated copy of [s] on [nativeHeap], never freed.
 *
 * `String.cstr.getPointer` wants an [kotlinx.cinterop.AutofreeScope], which frees on scope exit --
 * exactly the wrong lifetime here, since `PyType_FromSpec` keeps the pointer.
 *
 * `internal` rather than private because `PyMethodDef.ml_name` has the same requirement and the
 * same non-lifetime; see `python.native.ffi.UpcallEntry`.
 */
internal fun allocPermanentCString(s: String): CPointer<ByteVar> {
    val bytes = s.encodeToByteArray()
    val buffer = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
    for (i in bytes.indices) buffer[i] = bytes[i]
    buffer[bytes.size] = 0
    return buffer
}

/**
 * A two-entry `PyMemberDef` array on [nativeHeap], never freed -- `PyType_FromSpec` keeps the
 * `Py_tp_members` slot's pointer rather than copying it, the same reason [allocPermanentCString]
 * is permanent.
 *
 * The first entry is the handle slot itself: `_pm_handle`, [PY_T_LONG] (the same 8-byte width
 * [handleSlot] already reads and writes), `offset = 0` with [PY_RELATIVE_OFFSET] set -- the same
 * base [PyObject_GetTypeData] computes for [handleSlot] and [proxyTraverse] -- so a Python-level
 * `self._pm_handle = h` on an instance of this type, or of a further Python subclass, writes
 * exactly the 8 bytes those two already touch by hand. No `doc`, and no `READONLY` flag: the
 * generated `__init__` this exists for assigns the attribute, so the descriptor must accept a
 * write, not merely expose a read.
 *
 * The second entry is `PyMemberDef`'s own required all-zero terminator -- the same contract the
 * `PyType_Slot` array in [createProxyType] and the `PyMethodDef` arrays in `UpcallEntry` already
 * carry.
 */
private fun allocHandleMemberDefs(): CPointer<PyMemberDef> {
    val members = nativeHeap.allocArray<PyMemberDef>(2)
    members[0].name = allocPermanentCString("_pm_handle")
    members[0].type = PY_T_LONG
    members[0].offset = 0L
    members[0].flags = PY_RELATIVE_OFFSET
    members[0].doc = null
    members[1].name = null
    members[1].type = 0
    members[1].offset = 0L
    members[1].flags = 0
    members[1].doc = null
    return members
}

/**
 * `int tp_traverse(PyObject *self, visitproc visit, void *arg)`.
 *
 * CPython already holds the GIL when it calls this, so there is no [withGIL] here and nothing in
 * this function may release it.
 *
 * The early returns are silent by design -- an unregistered class simply has nothing to traverse.
 * That silence is worth knowing about: a test that registers its class only on the path where it
 * also initialises the interpreter registers nothing in a full-suite run, traverse returns here,
 * and the cycle stays invisible. That reads identically to "the object was never GC-tracked". If
 * a cycle is not collected, check registration before suspecting the slots.
 *
 * Note this does *not* visit `Py_TYPE(self)`, which CPython's documentation asks heap types to do.
 * The desktop implementation does not either, and the type here is a process-wide singleton held
 * by [proxyTypeAddress] -- a reference the collector cannot see -- so it is never a collection
 * candidate. It would have to be visited if proxy types ever became per-class and disposable.
 */
private fun proxyTraverse(self: CPointer<CPyObject>?, visit: visitproc?, arg: COpaquePointer?): Int {
    // An exception crossing back into C terminates the process on Kotlin/Native, so the whole
    // body is guarded. There is no error indicator to set from here that CPython would read
    // usefully during a collection; a non-zero return is the only channel, and it aborts the
    // traversal rather than reporting anything.
    try {
        val slot = handleSlot(self) ?: return 0
        val handle = slot.pointed.value
        val obj = HandleTable.resolveRaw(handle) ?: return 0
        val cls = ClassLookup.find(obj::class.qualifiedName ?: "") ?: return 0
        if (!cls.hasTraverse) return 0

        var err = 0
        cls.traverse(obj) { rawPtr ->
            if (err == 0 && rawPtr != 0L) {
                val target = rawPtr.toCPointer<CPyObject>()
                if (target != null && visit != null) {
                    val rc = visit(target, arg)
                    if (rc != 0) err = rc
                }
            }
        }
        return err
    } catch (t: Throwable) {
        return 0
    }
}

/**
 * `int tp_clear(PyObject *self)`.
 *
 * Also called with the GIL held. This releases the [HandleTable] entry and nothing else: that
 * entry is the Kotlin-side half of the cycle, and dropping it is what breaks the loop.
 *
 * Calling `PyObject.close()` on the Kotlin object's fields here would decrement references the
 * collector is in the middle of accounting for, which double-frees. The collector reclaims the
 * Python side on its own once the cycle is broken.
 */
private fun proxyClear(self: CPointer<CPyObject>?): Int {
    try {
        takeHandle(self)
    } catch (t: Throwable) {
        // Swallowed for the same reason as in proxyTraverse.
    }
    return 0
}

/**
 * Takes the [HandleTable] root out of [self]'s handle slot and drops it.
 *
 * The slot is zeroed *before* the release, which is what lets [proxyClear] and [proxyDealloc]
 * run one after the other without double-releasing. An object that dies in a cycle takes both
 * paths: `tp_clear` breaks the loop, the refcount then falls to zero and `tp_dealloc` follows.
 * The second call finds 0 and does nothing.
 *
 * [HandleTable.release] is independently idempotent -- it bumps the slot's generation, so a
 * repeat of the same handle no longer matches -- which is the backstop rather than the mechanism,
 * and is what stops a double release from freeing a slot already reissued to someone else.
 */
private fun takeHandle(self: CPointer<CPyObject>?) {
    val slot = handleSlot(self) ?: return
    val handle = slot.pointed.value
    if (handle == 0L) return
    slot.pointed.value = 0L
    HandleTable.release(ObjectReference(handle))
}

/**
 * `void tp_dealloc(PyObject *self)` -- the slot [proxyClear] is not a substitute for.
 *
 * `tp_clear` runs only when the cyclic collector decides to break a loop. A proxy whose refcount
 * simply reaches zero never goes near it, and that is the ordinary case: cycles are the
 * exception. Without this slot every such proxy left its [HandleTable] entry rooted for the life
 * of the interpreter, holding a Kotlin object nothing could reach again.
 *
 * Unlike `tp_clear`, this one owns the object's memory. Installing it replaces CPython's
 * `subtype_dealloc` outright, so everything that function does for a GC'd heap type has to happen
 * here:
 *
 *  - **untrack first.** The type carries `Py_TPFLAGS_HAVE_GC`, so the instance is on the
 *    collector's list; freeing it while still linked leaves the collector walking released memory.
 *  - **free through the type's own `tp_free`.** `PyType_GetSlot` is the only abi3 route to it,
 *    and the type must come from the *instance* rather than from [proxyTypeAddress]: the type is
 *    declared `Py_TPFLAGS_BASETYPE`, so Python code may subclass it and inherit this function.
 *  - **release the instance's reference to its type.** Since 3.8 an instance of a heap type holds
 *    one and `subtype_dealloc` gives it back. Forgetting leaks the type once per instance; doing
 *    it twice frees the type while it is still in use.
 *
 * `Py_TYPE` is a macro, so the type is fetched with `PyObject_Type`, a real stable-ABI function
 * that returns a *new* reference. Hence the two decrements: one for that temporary, one for the
 * instance's own.
 *
 * CPython holds the GIL here as it does for the other two slots; nothing here may take or
 * release it.
 */
private fun proxyDealloc(self: CPointer<CPyObject>?) {
    val obj = self ?: return

    // Fetched before anything else is torn down, and kept alive across the free by the reference
    // PyObject_Type just handed over.
    val type = PyObject_Type(obj)

    // An exception crossing back into C terminates the process on Kotlin/Native, and it would do
    // so having untracked the object without freeing it. Only the handle release runs Kotlin
    // logic that can throw, so only that part is guarded; the free below still happens.
    try {
        PyObject_GC_UnTrack(obj)
        takeHandle(obj)
    } catch (t: Throwable) {
        // Nowhere to report it: the only channel out of a deallocation is not returning.
    }

    if (type != null) {
        val tpFree = PyType_GetSlot(type.reinterpret<PyTypeObject>(), PY_TP_FREE)
        if (tpFree != null) {
            tpFree.reinterpret<CFunction<(COpaquePointer?) -> Unit>>()(obj)
            Py_DecRef(type) // the reference the instance held on its heap type
        }
        Py_DecRef(type) // the reference PyObject_Type just handed us
    }
}

/**
 * The Kotlin/Native half of the cycle-collecting proxy type.
 *
 * Lives in `nativeMain` rather than `iosMain` because `nativeMain` is shared with androidNative;
 * an actual under `iosMain` alone leaves the androidNative compilation without one, and the
 * failure reads as "expect declaration has no actual in module <commonMain> for Native" from a
 * target nobody was building.
 *
 * ### How this differs from desktop
 *
 * Desktop builds the two C function pointers with Panama upcall stubs, which can close over a
 * `MethodHandle` and therefore over arbitrary state. Kotlin/Native's equivalent,
 * [staticCFunction], deliberately cannot: it takes only a non-capturing function, so the type
 * pointer the slots need to call `PyObject_GetTypeData` cannot be captured and has to be reached
 * as a global ([proxyTypeAddress]).
 *
 * Everything else is easier here than on desktop. `PyType_Spec` and `PyType_Slot` come out of
 * cinterop as real structs, so the spec is filled in by field name instead of at hand-computed
 * offsets in a byte image that desktop copies out through `java.lang.foreign`.
 *
 * ### Threads CPython created
 *
 * The slots used to be reached only from a thread that already runs Kotlin -- the one that called
 * `gc.collect()` -- and a [staticCFunction] entered from a thread the Kotlin/Native runtime has
 * never seen is a different situation. Nothing here handles it explicitly, and it turns out
 * nothing has to: `CycleCollectionTest.testCycleCollectedOnAThreadCPythonCreated` and
 * `testDeallocOnAThreadCPythonCreated` run the collection, and the last-reference drop, on a
 * `threading.Thread` -- a bare pthread the runtime never created -- and all three slots work
 * there. The tests do not infer the thread from the effect: the `traverse` lambda they register
 * records `pthread_self()` and they assert it differs from the test thread's.
 *
 * Verified on `iosSimulatorArm64Test`, 195 tests, 0 failed. The androidNative build shares this
 * file but has no test run of its own, so read the result as "the new memory model attaches a
 * runtime to a foreign thread on entry", not as a per-target measurement.
 *
 * ### `tp_dealloc`, measured
 *
 * `CycleCollectionTest.testHandleReleasedWhenProxyDiesWithoutCycle` drops 100 cycle-free proxies
 * and checks both that their handles are released and that [proxyDealloc]'s two `Py_DecRef`s
 * balance -- it reads the type's `ob_refcnt` directly, so a forgotten release (type leaks per
 * instance) and a doubled one (type freed while in use) are told apart rather than lumped
 * together.
 */
actual object ProxyTypeFactory {

    /**
     * Creates the proxy type once and returns its address; subsequent calls return the same one.
     *
     * The spec, its slot array and the type name are allocated on [nativeHeap] and never freed.
     * That is deliberate rather than sloppy: `PyType_FromSpec` stores `spec->name` directly as
     * `tp_name` instead of copying it, so freeing the name would leave the type pointing at
     * released memory. The type itself lives for the process, so the spec allocation next to it
     * is bounded at a few dozen bytes total.
     */
    actual fun createProxyType(): Long {
        if (proxyTypeAddress != 0L) return proxyTypeAddress

        val slots = nativeHeap.allocArray<PyType_Slot>(5)
        // Slot ids from CPython's typeslots.h; they are ABI, not header-version-dependent.
        slots[0].slot = 71 // Py_tp_traverse
        slots[0].pfunc = staticCFunction(::proxyTraverse)
        slots[1].slot = 51 // Py_tp_clear
        slots[1].pfunc = staticCFunction(::proxyClear)
        slots[2].slot = 52 // Py_tp_dealloc
        slots[2].pfunc = staticCFunction(::proxyDealloc)
        slots[3].slot = PY_TP_MEMBERS
        slots[3].pfunc = allocHandleMemberDefs()
        slots[4].slot = 0 // sentinel
        slots[4].pfunc = null

        val spec = nativeHeap.alloc<PyType_Spec>()
        spec.name = allocPermanentCString("KotlinProxy")
        // Negative basicsize: -8 bytes appended after the base type's layout, reachable only
        // through PyObject_GetTypeData. Nothing here reads inside PyObject, so the abi3
        // assumption the rest of the FFI layer makes still holds.
        spec.basicsize = -8
        spec.itemsize = 0
        spec.flags = ((1 shl 10) or (1 shl 14)).toUInt() // Py_TPFLAGS_BASETYPE | Py_TPFLAGS_HAVE_GC
        spec.slots = slots

        val type = withGIL { PyType_FromSpec(spec.ptr) }
        proxyTypeAddress = type?.toLong() ?: 0L
        return proxyTypeAddress
    }

    /**
     * Publishes [createProxyType]'s type into `__main__` as `_pm_proxy_base`, the same route
     * `desktopMain`'s `installGcBase` uses: the type's own address is a valid `PyObject*`, so
     * `PyObject_SetAttrString` needs no `ctypes` round trip, and the type is never freed (its spec
     * and slots are permanent [nativeHeap] allocations, held for the process) so nothing here needs
     * an extra incref to keep it alive under `__main__`.
     */
    actual fun installGcBase(): Boolean {
        val type = createProxyType()
        if (type == 0L) return false
        val typePointer = type.toNativePointer() ?: return false
        return withGIL {
            val main = PyImport_AddModule("__main__") ?: return@withGIL false
            PyObject_SetAttrString(main, "_pm_proxy_base", typePointer) == 0
        }
    }
}
