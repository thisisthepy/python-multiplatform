@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.multiplatform.ffi

import kotlin.wasm.unsafe.Pointer
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.native.ffi.Wasm

/**
 * Slot ids from CPython's `typeslots.h`. They are ABI, not header-version dependent.
 *
 * `Py_tp_dealloc` is 52. It is written out here because it has been got wrong before: 50 is
 * `Py_tp_call`, and the desktop factory carried that mistake in a comment for a while. Filling the
 * wrong slot does not fail -- the type simply inherits `subtype_dealloc`, which frees the object
 * correctly and knows nothing about the handle it was carrying, so every proxy that dies outside a
 * cycle leaks its `HandleTable` entry silently.
 */
private const val PY_TP_CLEAR = 51
private const val PY_TP_DEALLOC = 52
private const val PY_TP_TRAVERSE = 71
private const val PY_TP_MEMBERS = 72
private const val PY_TP_FREE = 74

/** `Py_TPFLAGS_BASETYPE` and `Py_TPFLAGS_HAVE_GC`. */
private const val PY_TPFLAGS = (1 shl 10) or (1 shl 14)

private const val PY_T_LONGLONG = 17
private const val PY_RELATIVE_OFFSET = 8

/**
 * `PyType_Spec` and `PyType_Slot` are 32-bit structures here, and that is the whole reason this
 * file cannot be a transliteration of the desktop one.
 *
 *     struct PyType_Slot { int slot; void *pfunc; }              8 bytes on wasm32, 16 on LP64
 *     struct PyType_Spec { const char *name; int basicsize;
 *                          int itemsize; unsigned flags;
 *                          PyType_Slot *slots; }                 20 bytes on wasm32, 32 on LP64
 *
 * Getting an offset wrong here is not a `LinkError` -- `PyType_FromSpec` would read a slot id out of
 * a pointer and either build a nonsense type or crash inside CPython.
 */
private const val SLOT_SIZE = 8
private const val SPEC_SIZE = 20

/**
 * The three type slots, as Kotlin functions CPython calls directly.
 *
 * ### How CPython reaches these
 *
 * `@WasmExport` puts a function in the *wasm* export section with no type adapter, so what the host
 * receives is an exported wasm function rather than a JS closure. `WebAssembly.Table.prototype.set`
 * accepts it into CPython's own `__indirect_function_table` -- a funcref is a funcref whatever
 * instance produced it -- and the index it lands at **is** the C function pointer. CPython then
 * reaches Kotlin through `call_indirect`: 3.1 ns, and no JavaScript frame in the call at all.
 *
 * Registration is the one JS step, because a table index cannot be obtained from inside Kotlin. It
 * happens in [ProxyTypeFactory.createProxyType] through `pmpRegisterUpcall`.
 *
 * ### The constraint that is a correctness requirement rather than a compile error
 *
 * `call_indirect` is statically typed and does not coerce. A `(i32) -> i32` export invoked through
 * a `(i32, i32, i32) -> i32` slot raises `RuntimeError: null function or function signature
 * mismatch` **at runtime**. So each of these must have exactly the arity and result of the C
 * signature it is filling:
 *
 *     int  tp_traverse(PyObject *self, visitproc visit, void *arg)   (i32,i32,i32) -> i32
 *     int  tp_clear   (PyObject *self)                               (i32)         -> i32
 *     void tp_dealloc (PyObject *self)                               (i32)         -> ()
 *
 * ### The GIL
 *
 * CPython holds it when it calls any of these. Nothing below may take it or release it -- that is
 * the same rule the desktop and Native factories carry, and the reason none of these bodies is
 * wrapped in [withGIL].
 */
object ProxyType {

    /** The heap type, or 0 before [ProxyTypeFactory.createProxyType] has run. */
    var proxyTypePtr: Int = 0

    /**
     * Takes the [HandleTable] root out of [selfPtr]'s handle slot and drops it, returning what it
     * found (or 0).
     *
     * Zeroing the slot *before* releasing is what makes [tp_clear] and [tp_dealloc] safe to run one
     * after the other, which is exactly what happens to a proxy that was in a cycle: `tp_clear`
     * breaks the loop, the refcount then falls to zero, and `tp_dealloc` follows. The second call
     * finds 0 and does nothing.
     *
     * [HandleTable.release] is independently idempotent -- it bumps the slot generation -- which is
     * the backstop rather than the mechanism, and the reason a double release cannot hijack a slot
     * that has since been reissued.
     */
    private fun takeHandle(selfPtr: Int): Long {
        val slot = python.native.ffi.bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        if (slot == 0) return 0L
        val handle = Pointer(slot.toUInt()).loadLong()
        if (handle == 0L) return 0L
        Pointer(slot.toUInt()).storeLong(0L)
        HandleTable.release(ObjectReference(handle))
        return handle
    }

    /** Writes [handle] into [selfPtr]'s handle slot. Returns false if the type data is not there. */
    fun putHandle(selfPtr: Int, handle: Long): Boolean {
        val slot = python.native.ffi.bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        if (slot == 0) return false
        Pointer(slot.toUInt()).storeLong(handle)
        return true
    }

    /** Reads [selfPtr]'s handle slot without disturbing it. For assertions. */
    fun peekHandle(selfPtr: Int): Long {
        val slot = python.native.ffi.bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        if (slot == 0) return 0L
        return Pointer(slot.toUInt()).loadLong()
    }

    fun traverse(selfPtr: Int, visitPtr: Int, argPtr: Int): Int {
        val slot = python.native.ffi.bindings.PyObject_GetTypeData(selfPtr, proxyTypePtr)
        if (slot == 0) return 0
        val handle = Pointer(slot.toUInt()).loadLong()

        // These early returns are silent by design -- an unregistered class simply has nothing to
        // traverse. The desktop factory records that this silence once cost an afternoon: a test
        // registered its class only on the path where it also initialised the interpreter, so in a
        // full suite run traverse returned here having visited nothing, which reads identically to
        // "the object was never GC-tracked". If a cycle is not collected, check registration first.
        val obj = HandleTable.resolveRaw(handle) ?: return 0
        val cls = ClassLookup.find(obj::class.qualifiedName ?: "") ?: return 0
        if (!cls.hasTraverse) return 0

        var err = 0
        cls.traverse(obj) { pyObjRaw ->
            if (err == 0 && pyObjRaw != 0L) {
                val res = python.native.ffi.bindings.pmpCallVisit(visitPtr, pyObjRaw.toInt(), argPtr)
                if (res != 0) err = res
            }
        }
        return err
    }

    fun clear(selfPtr: Int): Int {
        // Only the Kotlin-side link is dropped. Decrementing another object's refcount from here
        // -- calling `close()` on the wrapper, say -- risks a double free, because CPython's cyclic
        // collector is already in the middle of taking those references apart itself.
        takeHandle(selfPtr)
        return 0
    }

    /**
     * `void tp_dealloc(PyObject *self)` -- the slot [clear] is not a substitute for.
     *
     * `tp_clear` runs only when the cyclic collector decides to break a loop. A proxy whose
     * refcount simply reaches zero never goes near it, and that is the ordinary case. Without this
     * slot every such proxy leaves its [HandleTable] entry rooted forever.
     *
     * Overriding `tp_dealloc` replaces `subtype_dealloc` outright, so everything that function does
     * for a GC'd heap type has to be done here:
     *
     *  - **untrack first**, because the type carries `Py_TPFLAGS_HAVE_GC` and freeing a still-linked
     *    instance leaves the collector walking released memory;
     *  - **free through the type's own `tp_free`**, taken from `Py_TYPE(self)` rather than from
     *    [proxyTypePtr]: the type is `Py_TPFLAGS_BASETYPE`, so Python code may subclass it and
     *    inherit this very function;
     *  - **release the instance's reference to its type**, which every instance of a heap type has
     *    held since 3.8. Forgetting leaks the type once per instance; doing it twice frees the type
     *    while it is still in use.
     *
     * `Py_TYPE` is a macro, so the type comes from `PyObject_Type`, which hands back a *new*
     * reference -- hence two decrements, one for that temporary and one for the instance's.
     */
    fun dealloc(selfPtr: Int) {
        if (selfPtr == 0) return
        val type = python.native.ffi.bindings.PyObject_Type(selfPtr)

        // The release is the only part of this that runs Kotlin logic capable of throwing, and an
        // exception escaping a wasm export lands in whatever CPython frame called it. Guarded so
        // that the free below still happens; there is nowhere to report it, because the only
        // channel out of a deallocation is not returning.
        try {
            python.native.ffi.bindings.PyObject_GC_UnTrack(selfPtr)
            takeHandle(selfPtr)
        } catch (t: Throwable) {
        }

        if (type != 0) {
            val tpFree = python.native.ffi.bindings.PyType_GetSlot(type, PY_TP_FREE)
            if (tpFree != 0) {
                python.native.ffi.bindings.pmpCallFree(tpFree, selfPtr)
                python.native.ffi.bindings.Py_DecRef(type) // the reference the instance held on its heap type
            }
            python.native.ffi.bindings.Py_DecRef(type) // the reference PyObject_Type just handed us
        }
    }
}

/**
 * The three export names [ProxyTypeFactory] looks for, and **the one thing this library cannot
 * declare for itself**.
 *
 * `@WasmExport` is honoured only in the compilation that produces the `.wasm` binary. Measured, not
 * inferred: with these three declared here, in `wasmJsMain`, the test binary's export section came
 * out holding `startUnitTests` and nothing else; the identical annotation on the identical function
 * exports from `wasmJsTest`. A library is compiled to a klib and linked in, and its `@WasmExport`s
 * do not reach the export section of whatever links it.
 *
 * So an application embedding this library has to declare the trampolines itself, in its own
 * executable module, exactly like this -- three lines, delegating straight to [ProxyType]:
 *
 * ```kotlin
 * @file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class)
 *
 * @kotlin.wasm.WasmExport("pmp_tp_traverse")
 * fun pmpTpTraverse(self: Int, visit: Int, arg: Int): Int = ProxyType.traverse(self, visit, arg)
 *
 * @kotlin.wasm.WasmExport("pmp_tp_clear")
 * fun pmpTpClear(self: Int): Int = ProxyType.clear(self)
 *
 * @kotlin.wasm.WasmExport("pmp_tp_dealloc")
 * fun pmpTpDealloc(self: Int) = ProxyType.dealloc(self)
 * ```
 *
 * `wasmJsTest/.../ProxyTypeExports.kt` is exactly that file, which is how the suite exercises the
 * path an application would take rather than a private shortcut. Generating it from the Gradle
 * plugin is the obvious next step and is not done.
 *
 * The arities are not a style choice -- see [ProxyType] on `call_indirect` not coercing.
 */
object ProxyTypeExportNames {
    const val TRAVERSE = "pmp_tp_traverse"
    const val CLEAR = "pmp_tp_clear"
    const val DEALLOC = "pmp_tp_dealloc"
}

actual object ProxyTypeFactory {

    /**
     * Builds the proxy heap type, installing the three Kotlin slots into CPython's function table
     * on the way. Idempotent: the type is built once and cached.
     *
     * Returns the type as a `Long` because that is what the `expect` says; on this target it is a
     * 32-bit address widened through `UInt`, for the same reason every other pointer here is.
     */
    actual fun createProxyType(): Long {
        if (ProxyType.proxyTypePtr != 0) return ProxyType.proxyTypePtr.toUInt().toLong()

        val traverseFp = registerUpcall(ProxyTypeExportNames.TRAVERSE)
        val clearFp = registerUpcall(ProxyTypeExportNames.CLEAR)
        val deallocFp = registerUpcall(ProxyTypeExportNames.DEALLOC)

        // Five slots: traverse, clear, dealloc, members, sentinel. Allocated through CPython's malloc and
        // never freed -- `PyType_FromSpec` copies the slots but keeps `spec->name`, so the name at
        // least has to outlive the call, and a single type per process makes the rest moot.
        val slots = python.native.ffi.bindings.malloc(SLOT_SIZE * 5)
        if (slots == 0) throw OutOfMemoryError("malloc for PyType_Slot[] failed")
        writeSlot(slots, 0, PY_TP_TRAVERSE, traverseFp)
        writeSlot(slots, 1, PY_TP_CLEAR, clearFp)
        writeSlot(slots, 2, PY_TP_DEALLOC, deallocFp)

        // Slot 3: Py_tp_members (72) -- the handle slot, exposed to Python itself.
        // `PyMemberDef` on wasm32:
        // struct PyMemberDef { const char *name; int type; Py_ssize_t offset; int flags; const char *doc; };
        // Wasm32: 20 bytes total (4 bytes each).
        val membersAddr = python.native.ffi.bindings.malloc(20 * 2)
        if (membersAddr == 0) throw OutOfMemoryError("malloc for PyMemberDef[] failed")
        storeInt(membersAddr, Wasm.allocUtf8("_pm_handle")) // name
        storeInt(membersAddr + 4, PY_T_LONGLONG)            // type
        storeInt(membersAddr + 8, 0)                        // offset
        storeInt(membersAddr + 12, PY_RELATIVE_OFFSET)      // flags
        storeInt(membersAddr + 16, 0)                       // doc
        // sentinel
        storeInt(membersAddr + 20, 0)
        storeInt(membersAddr + 24, 0)
        storeInt(membersAddr + 28, 0)
        storeInt(membersAddr + 32, 0)
        storeInt(membersAddr + 36, 0)

        writeSlot(slots, 3, PY_TP_MEMBERS, membersAddr)
        writeSlot(slots, 4, 0, 0)

        val spec = python.native.ffi.bindings.malloc(SPEC_SIZE)
        if (spec == 0) throw OutOfMemoryError("malloc for PyType_Spec failed")
        storeInt(spec + 0, Wasm.allocUtf8("KotlinProxy"))
        // A negative basicsize appends to the base object rather than replacing it: 8 bytes for the
        // handle, which is a Kotlin `Long`, sitting after `PyObject`.
        storeInt(spec + 4, -8)
        storeInt(spec + 8, 0)
        storeInt(spec + 12, PY_TPFLAGS)
        storeInt(spec + 16, slots)

        val type = withGIL { python.native.ffi.bindings.PyType_FromSpec(spec) }
        if (type == 0) {
            throw IllegalStateException(
                "PyType_FromSpec returned NULL while building the Kotlin proxy type. The slot " +
                    "function pointers were traverse=$traverseFp clear=$clearFp dealloc=$deallocFp."
            )
        }
        ProxyType.proxyTypePtr = type
        return type.toUInt().toLong()
    }

    actual fun installGcBase(): Boolean {
        val type = createProxyType()
        if (type == 0L) return false
        return python.multiplatform.ffi.withGIL {
            val main = python.native.ffi.bindings.PyImport_AddModule(python.native.ffi.Wasm.internedUtf8("__main__"))
            if (main == 0) return@withGIL false
            python.native.ffi.bindings.PyObject_SetAttrString(main, python.native.ffi.Wasm.internedUtf8("_pm_proxy_base"), type.toInt()) == 0
        }
    }

    /**
     * Puts the Kotlin `@WasmExport` called [name] into CPython's `__indirect_function_table` and
     * returns the index, which is the C function pointer.
     *
     * The failure this reports at length is a build-wiring failure rather than a code one: the
     * Kotlin instance's exports have to be handed to `cpython.mjs` after instantiation, and there
     * is no way for the glue to fetch them itself (it is imported *by* Kotlin's import object, so
     * reaching back would be an ES cycle across a top-level await). If that step is missing,
     * everything else here works and the type is simply never built.
     */
    private fun registerUpcall(name: String): Int {
        val fp = python.native.ffi.bindings.pmpRegisterUpcall(Wasm.internedUtf8(name))
        if (fp >= 0) return fp
        throw IllegalStateException(
            when (fp) {
                -1 -> "cpython.mjs has not been given the Kotlin module's wasm exports. The " +
                    "generated entry module must call `pmpSetKotlinExports(exports)` after " +
                    "instantiation -- build.gradle.kts does this for the test bundle; an " +
                    "application embedding this library has to do the same."
                -2 -> "the Kotlin module does not export '$name'. @WasmExport is honoured only " +
                    "in the compilation that produces the .wasm, so this library cannot declare " +
                    "it; the executable module has to. See ProxyTypeExportNames for the three " +
                    "lines it needs."
                -4 -> "python.wasm exports no __indirect_function_table, so there is nowhere to " +
                    "put '$name'. Emscripten exports it under -sMAIN_MODULE; a plain build does not."
                else -> "CPython's __indirect_function_table refused to grow, so '$name' has no " +
                    "function pointer. That table is growable only because CPython is linked " +
                    "-sMAIN_MODULE."
            }
        )
    }

    private fun writeSlot(base: Int, index: Int, slotId: Int, pfunc: Int) {
        storeInt(base + index * SLOT_SIZE, slotId)
        storeInt(base + index * SLOT_SIZE + 4, pfunc)
    }

    private fun storeInt(address: Int, value: Int) {
        Pointer(address.toUInt()).storeInt(value)
    }
}
