@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi

import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.wasm.unsafe.Pointer
import python.multiplatform.overhead.Benchmark

/**
 * What a string argument costs on this target, broken into the parts a route can remove.
 *
 * The point is not to assert a number -- these run on whatever machine the suite runs on -- but to
 * keep the *ordering* honest, because the ordering is what the design rests on. The rules for
 * `internedUtf8` vs `scratchUtf8` were carried over from desktop and Android, and the reason they
 * apply here is different (see the note in `Wasm.kt`). If interning ever stopped being the cheapest
 * route, that reasoning would be wrong and this would say so.
 *
 * The numbers this produced when it was written, against CPython 3.14.2 on `wasm32-emscripten`, are
 * in `src/wasmJsMain/README.md`.
 */
class WasmMarshallingOverheadTest {

    @AfterTest
    fun report() {
        Benchmark.printReport()
    }

    /** The old route, spelled out so the benchmark measures it rather than a memory of it. */
    private fun allocFreeRoundTrip(s: String): Int {
        val bytes = s.encodeToByteArray()
        val address = python.native.ffi.bindings.malloc(bytes.size + 1)
        var p = Pointer(address.toUInt())
        for (b in bytes) {
            p.storeByte(b)
            p += 1
        }
        p.storeByte(0)
        python.native.ffi.bindings.free(address)
        return address
    }

    @Test
    fun stringArgumentRoutesRankAsTheDesignAssumes() {
        val name = "version"   // the shape of an interned argument: a short repeated identifier

        var sink = 0
        Benchmark.run("marshal: malloc+encodeToByteArray+copy+free (old)", iterations = 200_000) {
            sink = sink xor allocFreeRoundTrip(name)
        }
        Benchmark.run("marshal: malloc+free only", iterations = 200_000) {
            val a = python.native.ffi.bindings.malloc(8)
            python.native.ffi.bindings.free(a)
            sink = sink xor a
        }
        Benchmark.run("marshal: encodeToByteArray only", iterations = 200_000) {
            sink = sink xor name.encodeToByteArray().size
        }
        Benchmark.run("marshal: Wasm.scratchUtf8", iterations = 200_000) {
            sink = sink xor Wasm.scratchUtf8(name)
        }
        Benchmark.run("marshal: Wasm.internedUtf8 (hit)", iterations = 200_000) {
            sink = sink xor Wasm.internedUtf8(name)
        }
        // A long identifier, because the two routes scale differently and the short case alone
        // would not say which way. Encoding is O(n) in the string; so is hashing it.
        val longName = "some.deeply.qualified.module.name.with_a_long_attribute"
        Benchmark.run("marshal 54-char name: Wasm.scratchUtf8", iterations = 200_000) {
            sink = sink xor Wasm.scratchUtf8(longName)
        }
        Benchmark.run("marshal 54-char name: Wasm.internedUtf8 (hit)", iterations = 200_000) {
            sink = sink xor Wasm.internedUtf8(longName)
        }
        assertTrue(sink != Int.MIN_VALUE, "keep the loops from being optimised away")

        // Correctness of the routes is WasmStringMarshallingTest's job; this only checks that the
        // benchmark ran against real addresses rather than zeros.
        assertEquals("version", Wasm.readUtf8String(Wasm.internedUtf8(name)))
        assertEquals("version", Wasm.readUtf8String(Wasm.scratchUtf8(name)))
    }

    /**
     * The same question end to end, and in one run rather than across two.
     *
     * Comparing a `PyObject_GetAttrString` number from before this change against one from after it
     * would be comparing two machine states; both rows here run back to back against the same
     * interpreter, so the difference between them is the marshalling and nothing else.
     */
    @Test
    fun attributeAccessPaysTheDifferenceBetweenTheTwoRoutes() {
        python.multiplatform.ffi.PythonTestFixture.withInterpreter {
            val sys = python.multiplatform.ffi.Python3.withPython {
                PyImport_ImportModule("sys")
            } ?: error("could not import sys")
            val sysPtr = sys.toPlatformPointer()

            // Both rows are otherwise identical -- same C function, same GIL scope, same DecRef --
            // so that the only thing varying is how the `char*` was produced.
            var sink = 0
            Benchmark.run("getattr: C string allocated per call (old)", iterations = 50_000) {
                val attr = python.multiplatform.ffi.Python3.withPython {
                    val addr = Wasm.allocUtf8("version")
                    try {
                        python.native.ffi.bindings.PyObject_GetAttrString(sysPtr, addr)
                    } finally {
                        Wasm.freeUtf8(addr)
                    }
                }
                sink = sink xor attr
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.bindings.Py_DecRef(attr) }
            }
            Benchmark.run("getattr: interned C string (new)", iterations = 50_000) {
                val attr = python.multiplatform.ffi.Python3.withPython {
                    python.native.ffi.bindings.PyObject_GetAttrString(sysPtr, Wasm.internedUtf8("version"))
                }
                sink = sink xor attr
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.bindings.Py_DecRef(attr) }
            }
            // The same pair once more at the level `BenchmarkTest.testAttributeAccess` measures --
            // through the `actual`, so the `NativePointer?` return is included. That figure did
            // *not* move across the two suite runs this change spans (376.6 -> 379.4 ns), and the
            // rows below are how much of that is marshalling and how much is everything else.
            Benchmark.run("getattr via actual: C string per call (old)", iterations = 50_000) {
                val attr = python.multiplatform.ffi.Python3.withPython {
                    val addr = Wasm.allocUtf8("version")
                    try {
                        python.native.ffi.bindings.PyObject_GetAttrString(sysPtr, addr)
                            .toNativePointerFromRaw()
                    } finally {
                        Wasm.freeUtf8(addr)
                    }
                }
                if (attr != null) {
                    sink = sink xor attr.toPlatformPointer()
                    python.multiplatform.ffi.Python3.withPython { Py_DecRef(attr) }
                }
            }
            Benchmark.run("getattr via actual: interned (new)", iterations = 50_000) {
                val attr = python.multiplatform.ffi.Python3.withPython {
                    PyObject_GetAttrString(sys, "version")
                }
                if (attr != null) {
                    sink = sink xor attr.toPlatformPointer()
                    python.multiplatform.ffi.Python3.withPython { Py_DecRef(attr) }
                }
            }
            Benchmark.run("getattr: scratch C string", iterations = 50_000) {
                val attr = python.multiplatform.ffi.Python3.withPython {
                    python.native.ffi.bindings.PyObject_GetAttrString(sysPtr, Wasm.scratchUtf8("version"))
                }
                sink = sink xor attr
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.bindings.Py_DecRef(attr) }
            }
            assertTrue(sink != Int.MIN_VALUE)
            python.multiplatform.ffi.Python3.withPython { Py_DecRef(sys) }
        }
    }

    @Test
    fun longStringsGoThroughScratchWithoutReallocating() {
        val long = "x".repeat(4000)
        var sink = 0
        Benchmark.run("marshal 4000 B: malloc+encode+copy+free (old)", iterations = 20_000) {
            sink = sink xor allocFreeRoundTrip(long)
        }
        Benchmark.run("marshal 4000 B: Wasm.scratchUtf8", iterations = 20_000) {
            sink = sink xor Wasm.scratchUtf8(long)
        }
        assertTrue(sink != Int.MIN_VALUE)
        assertEquals(long, Wasm.readUtf8String(Wasm.scratchUtf8(long)))
    }
}
