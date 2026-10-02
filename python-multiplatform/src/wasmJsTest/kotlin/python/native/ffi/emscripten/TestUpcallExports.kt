@file:OptIn(kotlin.wasm.ExperimentalWasmInterop::class, kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi.emscripten

import kotlin.wasm.unsafe.Pointer
import python.native.ffi.Wasm

/**
 * Test-only `@WasmExport`s that CPython reaches as ordinary `PyCFunction`s.
 *
 * They live in the test compilation for the reason `UpcallExports.kt` gives: `@WasmExport` is
 * honoured only in the compilation that produces the `.wasm`. Neither is part of the library's
 * calling convention -- `pmp_invoke` is (`UpcallEntry`). These exist to observe the *runtime* that
 * convention sits on: what the crossing costs ([pmpTestBare]) and which of CPython's two call
 * trampolines is live ([pmpTestArity4]).
 */
object TestUpcalls {
    /** How many times either export below has been entered. */
    var entered = 0

    /** The four raw arguments [pmpTestArity4] last received. */
    val lastArity4 = IntArray(4)

    /** `METH_VARARGS` from `methodobject.h`. */
    const val METH_VARARGS = 0x0001

    /**
     * Binds a `PyCFunction` named [name] over table index [functionPointer] into `__main__`, the
     * way any C extension's `PyMethodDef` would be. Caller holds the GIL.
     *
     *     PyMethodDef { const char *ml_name; PyCFunction ml_meth; int ml_flags; const char *ml_doc; }
     *
     * Four 4-byte fields on wasm32. Deliberately never freed: a `PyCFunction` keeps the pointer to
     * its `PyMethodDef` (and so to `ml_name`) for its whole life.
     *
     * @return 0 on success.
     */
    fun install(name: String, functionPointer: Int, flags: Int = METH_VARARGS): Int {
        val namePtr = Wasm.allocUtf8(name)
        val def = python.native.ffi.bindings.malloc(16)
        val p = Pointer(def.toUInt())
        p.storeInt(namePtr)
        (p + 4).storeInt(functionPointer)
        (p + 8).storeInt(flags)
        (p + 12).storeInt(0)
        val callable = python.native.ffi.bindings.PyCFunction_NewEx(def, 0, 0)
        if (callable == 0) return -1
        val module = python.native.ffi.bindings.PyImport_AddModule(Wasm.internedUtf8("__main__"))
        val dict = python.native.ffi.bindings.PyModule_GetDict(module)
        val rc = python.native.ffi.bindings.PyDict_SetItemString(dict, namePtr, callable)
        python.native.ffi.bindings.Py_DecRef(callable)
        return rc
    }

    /** Puts the Kotlin export [exportName] into CPython's function table; the index or a code < 0. */
    fun register(exportName: String): Int =
        python.native.ffi.bindings.pmpRegisterUpcall(Wasm.internedUtf8(exportName))
}

/**
 * `PyObject *(PyObject *self, PyObject *args)` doing the least an upcall can: count, and return a
 * new `int`. A downcall from inside the upcall, as every real one has.
 */
@kotlin.wasm.WasmExport("pmp_test_bare")
fun pmpTestBare(self: Int, args: Int): Int {
    TestUpcalls.entered++
    return python.native.ffi.bindings.PyLong_FromLongLong(0L)
}

/**
 * Four parameters: a shape matching none of the four CPython's wasm trampoline
 * (`Python/emscripten_trampoline_inner.c`) tests for with `ref.test`. A discriminator, not a feature
 * -- see `WasmCallTrampolineTest`.
 */
@kotlin.wasm.WasmExport("pmp_test_arity4")
fun pmpTestArity4(a: Int, b: Int, c: Int, d: Int): Int {
    TestUpcalls.entered++
    TestUpcalls.lastArity4[0] = a
    TestUpcalls.lastArity4[1] = b
    TestUpcalls.lastArity4[2] = c
    TestUpcalls.lastArity4[3] = d
    return python.native.ffi.bindings.PyLong_FromLongLong(4L)
}
