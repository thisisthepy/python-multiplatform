@file:OptIn(kotlin.wasm.unsafe.UnsafeWasmMemoryApi::class)

package python.native.ffi.emscripten

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.wasm.unsafe.Pointer
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.overhead.Benchmark
import python.native.ffi.Wasm

/**
 * The downcall and data-path measurements `src/wasmJsMain/README.md` stands on, taken against the
 * real interpreter on every run instead of once in the standalone `wasm-experiment/` (Tests A, C
 * and D, against a 4 KB `emcc` toy for A and C).
 *
 * Each test reports every row and asserts only an *ordering* the design rests on and that held by
 * a wide margin when measured (3x or more). No absolute figure is asserted -- these run on whatever
 * machine runs the suite, under whatever else it is doing.
 *
 * | the rule in the README | the row that would contradict it |
 * |---|---|
 * | the call path has no JS frame, and that matters | a JS trampoline as cheap as `@WasmImport` |
 * | no composed C shim: pure-Kotlin hoisting does the work | hoisted not cheaper than naive |
 * | never `decodeToString()` on a large buffer | `Wasm.readUtf8String` not cheaper on 4000 B |
 * | `char*` and arrays: shared memory, not JS | a JS `HEAPU32` read as cheap as `Pointer.loadInt` |
 */
class WasmCrossingOverheadTest {

    @AfterTest
    fun report() {
        Benchmark.printReport()
    }

    /**
     * Test A's control path. The same export, `PyErr_Occurred`, reached two ways: `@WasmImport`
     * (wasm to wasm, no frame in between) and a Kotlin `js(...)` function that calls it off the
     * export object -- the route a JS bridge would take for every call. Measured 5.0 against
     * 13.6 - 16.9 ns on the toy.
     */
    @Test
    fun aDirectWasmImportIsCheaperThanAJsTrampolineToTheSameExport() {
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                var sink = 0
                val direct = Benchmark.run("downcall: PyErr_Occurred, direct @WasmImport", 10_000, 2_000_000) {
                    sink += python.native.ffi.bindings.PyErr_Occurred()
                }
                val trampoline = Benchmark.run("downcall: PyErr_Occurred, through a JS frame", 10_000, 2_000_000) {
                    sink += pyErrOccurredViaJs(wasmExports)
                }
                assertEquals(0, sink, "no error was pending, so every call must have returned NULL")
                assertTrue(
                    direct < trampoline,
                    "a direct @WasmImport ($direct ns) is not cheaper than a JS frame around the same " +
                        "export ($trampoline ns). The wasmJs design is that the call path has no JS in it; " +
                        "if that no longer buys anything, re-read docs/platforms/wasm-design.md.",
                )
            }
        }
    }

    /**
     * Reading one global out of `__main__`, each row adding one pure-Kotlin optimisation. Measured
     * against CPython 3.14.2: 259.5 / 185.4 / 65.2 ns, and one crossing 2.9 ns -- the figures behind
     * the README's "no composed C shim" verdict. A `pmp_getattr` shim could merge the two calls the
     * hoisted row still makes, and save about one crossing.
     */
    @Test
    fun readingAGlobalGetsCheapAsMarshallingAndLookupsAreHoistedOutInKotlin() {
        PythonTestFixture.withInterpreter {
            Python3.withPython {
                assertEquals(0, runInMain("_pmp_answer = 6 * 7"))
                try {
                    val n = 200_000
                    var sum = 0L
                    val naive = Benchmark.run("global read: naive (C strings per call)", 2_000, n) {
                        val mainName = Wasm.allocUtf8("__main__")
                        val key = Wasm.allocUtf8("_pmp_answer")
                        try {
                            val module = python.native.ffi.bindings.PyImport_AddModule(mainName)
                            val dict = python.native.ffi.bindings.PyModule_GetDict(module)
                            sum += python.native.ffi.bindings.PyLong_AsLongLong(
                                python.native.ffi.bindings.PyDict_GetItemString(dict, key),
                            )
                        } finally {
                            Wasm.freeUtf8(mainName)
                            Wasm.freeUtf8(key)
                        }
                    }
                    val interned = Benchmark.run("global read: + interned C strings", 2_000, n) {
                        val module = python.native.ffi.bindings.PyImport_AddModule(Wasm.internedUtf8("__main__"))
                        val dict = python.native.ffi.bindings.PyModule_GetDict(module)
                        sum += python.native.ffi.bindings.PyLong_AsLongLong(
                            python.native.ffi.bindings.PyDict_GetItemString(dict, Wasm.internedUtf8("_pmp_answer")),
                        )
                    }
                    val dict = python.native.ffi.bindings.PyModule_GetDict(
                        python.native.ffi.bindings.PyImport_AddModule(Wasm.internedUtf8("__main__")),
                    )
                    val key = Wasm.internedUtf8("_pmp_answer")
                    val hoisted = Benchmark.run("global read: + module/dict hoisted", 2_000, n) {
                        sum += python.native.ffi.bindings.PyLong_AsLongLong(
                            python.native.ffi.bindings.PyDict_GetItemString(dict, key),
                        )
                    }
                    var errs = 0
                    val crossing = Benchmark.run("global read: one crossing (PyErr_Occurred)", 2_000, n) {
                        errs += python.native.ffi.bindings.PyErr_Occurred()
                    }
                    assertEquals(42L * 3 * (n + 2_000), sum, "every read must have returned 42")
                    assertEquals(0, errs)
                    println(
                        "global read: interned removes ${pct(naive, interned)}, hoisting " +
                            "${pct(naive, hoisted)} of the naive cost",
                    )
                    assertTrue(
                        hoisted < naive,
                        "hoisting the module and dict lookups out of the loop ($hoisted ns) is not " +
                            "cheaper than the naive read ($naive ns); the no-shim verdict assumed it was",
                    )
                    assertTrue(
                        crossing < hoisted,
                        "one bare crossing ($crossing ns) is not cheaper than two CPython calls " +
                            "doing real work ($hoisted ns)",
                    )
                } finally {
                    runInMain("del _pmp_answer")
                }
            }
        }
    }

    /**
     * `char*` -> `String`, all three routes ending with a Kotlin `String` built from the same address
     * in shared memory. Measured on the toy: 103 / 405 / 125 ns at 27 bytes and 5 875 / 58 271 /
     * 3 827 ns at 4000. The 4000-byte `decodeToString()` row is the one the README forbids, and the
     * one asserted against.
     *
     * The JS row decodes with `TextDecoder` over a copy of the bytes, which is what `UTF8ToString`
     * does internally; `UTF8ToString` itself is not exported by this build (see `cpython.mjs`).
     */
    @Test
    fun readingACStringNeverGoesThroughDecodeToStringOnALargeBuffer() {
        val short = "hello-from-the-cpython-side"
        val long = "x".repeat(4000)
        val shortAddr = Wasm.allocUtf8(short)
        val longAddr = Wasm.allocUtf8(long)
        try {
            var total = 0
            for ((label, addr, iterations) in listOf(
                Triple("27 B", shortAddr, 200_000),
                Triple("4000 B", longAddr, 5_000),
            )) {
                val library = Benchmark.run("C string $label: Wasm.readUtf8String", 100, iterations) {
                    total += Wasm.readUtf8String(addr)!!.length
                }
                val decode = Benchmark.run("C string $label: ByteArray + decodeToString()", 100, iterations) {
                    var len = 0
                    while (Pointer((addr + len).toUInt()).loadByte().toInt() != 0) len++
                    total += Wasm.readBytes(addr, len).decodeToString().length
                }
                Benchmark.run("C string $label: copied through JS (TextDecoder)", 100, iterations) {
                    total += utf8ViaJs(wasmMemory, addr).length
                }
                if (label == "4000 B") {
                    assertTrue(
                        library < decodeToStringRatioFloor * decode,
                        "Wasm.readUtf8String ($library ns) is not well under ByteArray.decodeToString() " +
                            "($decode ns) on 4000 bytes; the README's rule against decodeToString() " +
                            "rests on that gap",
                    )
                }
            }
            assertTrue(total > 0)
            assertEquals(short, Wasm.readUtf8String(shortAddr))
            assertEquals(short, utf8ViaJs(wasmMemory, shortAddr), "the JS route must read the same bytes")
        } finally {
            Wasm.freeUtf8(shortAddr)
            Wasm.freeUtf8(longAddr)
        }
    }

    /**
     * 1000 `i32` out of a C-owned array: a plain `i32.load` loop against an element-wise read
     * through Emscripten's `HEAPU32` view. Measured 0.7 against 7.1 ns per element on the toy --
     * the case a composed shim was last argued to be needed for.
     */
    @Test
    fun aBulkReadOfCMemoryIsCheaperInSharedMemoryThanThroughJs() {
        val count = 1000
        val array = python.native.ffi.bindings.malloc(count * 4)
        try {
            for (i in 0 until count) Pointer((array + i * 4).toUInt()).storeInt(i)
            val expected = count * (count - 1) / 2
            var shared = 0
            var viaJs = 0
            val sharedNs = Benchmark.run("bulk 1000 x i32: Pointer.loadInt (shared memory)", 100, 20_000) {
                var s = 0
                var p = Pointer(array.toUInt())
                for (i in 0 until count) {
                    s += p.loadInt()
                    p += 4
                }
                shared = s
            }
            val jsNs = Benchmark.run("bulk 1000 x i32: HEAPU32[i] through JS", 100, 2_000) {
                var s = 0
                for (i in 0 until count) s += heapU32At(mod, array + i * 4)
                viaJs = s
            }
            assertEquals(expected, shared)
            assertEquals(expected, viaJs, "the JS route must read the same memory")
            println("bulk: ${sharedNs / count} vs ${jsNs / count} ns per element")
            assertTrue(
                sharedNs < jsNs,
                "a shared-memory bulk read ($sharedNs ns / 1000) is not cheaper than reading the same " +
                    "elements through JS ($jsNs ns / 1000)",
            )
        } finally {
            python.native.ffi.bindings.free(array)
        }
    }

    private fun pct(base: Double, now: Double): String = "${((1 - now / base) * 100).toInt()}%"

    private companion object {
        /**
         * Measured 10x apart; asserting half that leaves room for a loaded machine without letting
         * the two routes converge unnoticed.
         */
        const val decodeToStringRatioFloor = 0.5
    }
}

private fun pyErrOccurredViaJs(exports: JsAny): Int = js("exports.PyErr_Occurred()")

private fun utf8ViaJs(memory: JsAny, address: Int): String =
    js("(() => { const b = new Uint8Array(memory.buffer); let e = address; while (b[e] !== 0) e++; return new TextDecoder().decode(b.slice(address, e)); })()")

private fun heapU32At(module: JsAny, address: Int): Int = js("module.HEAPU32[address >>> 2]")
