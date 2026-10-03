package python.multiplatform.overhead

import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
import kotlin.test.assertTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PyObject
import python.native.ffi.*

class BenchmarkTest {

    companion object {
        /**
         * Warmup for every row below, replacing the mix of `100` and [Benchmark]'s `1000` default
         * this file used to carry.
         *
         * ### Why the old numbers were wrong
         *
         * `7e9c6b8c` established, and `ec53b6e5` confirmed by sweeping six hosts, that any row
         * crossing from Kotlin into host or C code is still climbing the host JIT's tiers well
         * past 3 000 iterations: hosts *with* a JIT all fell when the warmup was raised and hosts
         * *without* one did not move at all, which is what identifies the mechanism as tier-up
         * rather than CPython-side state. At 100 -- and at 1 000 -- these rows were being read
         * several tiers early, so what they reported was a warm-up state and not a cost.
         *
         * This file is the one place where that matters most, because it runs on **all six**
         * targets (desktop, iOS, wasmJs, androidNative x2, ART x2) and its rows are quoted
         * against each other. Rows warmed differently cannot be compared, so this is applied to
         * every row rather than only to the two that named a warmup explicitly -- with one
         * documented exception, [WARMUP_BULK], which is the only place in this file where two rows
         * are warmed differently from the rest and is why that constant carries its own accuracy
         * caveat.
         *
         * ### Why 100 000
         *
         * It is the largest requirement across the hosts measured, not a round number: `ec53b6e5`
         * put convergence at ~40 000 cold and ~70 000 warm on desktop, ~70 000 on wasm, and
         * ~90 000-100 000 on ART api36, which is the tightest. 100 000 covers the worst of them.
         *
         * Swept here on desktop, in full suites rather than filtered runs, this file's own rows
         * agree. Every row falls monotonically and then flattens, at 40 000-70 000:
         *
         * ```
         * warmup                 100    1 000   10 000   40 000   70 000  100 000
         * PyUnicode_AsUTF8 (8)    674      665      556      557       78       78
         * PyObject_GetAttrString  505      519      314      339      187      192
         * PyLong_FromLongLong     225      221      175      124      122      126
         * ```
         *
         * `PyUnicode_AsUTF8 (8 chars)` is the row that fixes the number: still 557 ns at a 40 000
         * warmup and 78 ns from 70 000 on, a 7x step inside one interval. Reading it at the old
         * `100` overstated it by **8.6x**. Nothing below 70 000 is defensible for it, and 100 000
         * is 70 000 with the margin ART turns out to need.
         *
         * @see WARMUP_BULK for the two rows this number is *not* applied to, and why.
         */
        const val WARMUP = 100_000

        /**
         * Warmup for the 8192-character string rows, which [WARMUP] is deliberately not applied to.
         *
         * ### The cost that forces a separate number
         *
         * These two rows are the only ones whose per-call cost is a length-proportional copy rather
         * than a call, and on a slow target that cost is enormous: on the iOS simulator
         * `PyUnicode_FromString (8192 chars)` runs at ~894 us per call. At [WARMUP] that single
         * warmup loop is ~90 seconds. Measured rather than projected -- with 100 000 applied
         * uniformly, iOS's `testStringMarshalling` went from **11.05 s to 115.60 s** and the whole
         * `iosSimulatorArm64Test` suite from **19.38 s to 126.87 s**, a 6.5x suite for a change that
         * costs desktop 15%.
         *
         * ### Why capping these two is sound and not just cheaper
         *
         * Tier-up is a property of the code path, and these rows do not have one of their own: the
         * `len = 8` and `len = 256` iterations of the same loop run first and execute the identical
         * `Python3.withPython { PyUnicode_FromString(str) }`, so by the time the 8192 row starts,
         * that path has already been through ~220 000 executions. What is left for a longer warmup
         * to reach is CPython-side state for 8 KB objects -- allocator free lists -- not compilation.
         *
         * ### The residual, measured and quoted rather than assumed away
         *
         * Capping is **not** free, and the honest figure is here rather than in a commit message.
         * On desktop, `PyUnicode_AsUTF8 (8192 chars)` reads 5624 and 5881 ns at this warmup against
         * 5154 and 5262 ns at a uniform 100 000 -- about **9% high**, with both readings of each
         * pair on the same side of the other, so it is a real offset and not noise.
         *
         * That 9% is accepted deliberately. Against the full sweep the row reads 6384-6554 ns at the
         * old `100` and 5098-5262 at 100 000, so 10 000 captures roughly two thirds of the available
         * correction for a hundredth of the wall clock. **Treat these two rows as accurate to about
         * 10%, and do not read a 10% difference between them and anything else as a result.** The
         * other nine rows carry [WARMUP] and have no such caveat.
         */
        const val WARMUP_BULK = 10_000

        var initialized = false
        var interpreterAvailable = false

        fun setupPython() {
            if (initialized) return
            initialized = true
            // Deliberately asks whether an interpreter is already up rather than bringing one up:
            // a benchmark should not be the thing that owns interpreter lifecycle for the suite.
            //
            // This used to be justified by "Py_Initialize() causes a fatal crash (abort) due to
            // missing 'encodings' module inside the test binary environment", which is no longer
            // true of the environments this runs in. It is a real failure mode, but it belongs to
            // a platform whose stdlib has not been staged: desktop and the iOS simulator get a
            // PYTHONHOME from the build (`InterpreterAvailabilityTest` initialises successfully),
            // and on Android `PythonInstrumentationRunner` unpacks the stdlib before any test
            // class loads.
            //
            // The consequence of only asking is that these benchmarks measure nothing unless some
            // earlier test has already initialised. On desktop they do run.
            interpreterAvailable = python.multiplatform.ffi.Python3.isInitialized
            if (!interpreterAvailable) {
                println("SKIPPED: Python interpreter unavailable (cannot initialize safely). Tests requiring the interpreter will be skipped.")
            }
        }
    }

    @BeforeTest
    fun setUp() {
        setupPython()
    }

    @AfterTest
    fun tearDown() {
        Benchmark.printReport()
    }

    @Test
    fun testPointerBoxing() {
        val p = 12345L.toNativePointer()
        if (p == null) {
            println("SKIPPED: Cannot create NativePointer from Long on this platform")
            return
        }

        Benchmark.run("NativePointer.toRawValue()", warmupIterations = WARMUP, iterations = 1_000_000) {
            p.toRawValue()
        }

        Benchmark.run("NativePointer.toAddressValue()", warmupIterations = WARMUP, iterations = 1_000_000) {
            p.toAddressValue()
        }

        val addr = p.toAddressValue()
        Benchmark.run("AddressValue.toNativePointer()", warmupIterations = WARMUP, iterations = 1_000_000) {
            addr.toNativePointer()
        }
    }

    @Test
    fun testRawFfiCost() {
        if (!interpreterAvailable) return

        val obj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(42L) }
        if (obj == null) {
            println("SKIPPED testRawFfiCost: PyLong_FromLongLong returned null")
            return
        }

        Benchmark.run("Py_IncRef/Py_DecRef", warmupIterations = WARMUP, iterations = 1_000_000) {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(obj) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
        }
        
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
    }

    @Test
    fun testStringMarshalling() {
        if (!interpreterAvailable) return

        val lengths = listOf(8, 256, 8192)
        for (len in lengths) {
            val str = "A".repeat(len)
            // The warmup is capped in *work* for the bulk row: its per-call cost is a copy that
            // scales with `len`, so a fixed iteration count costs a hundred times more here than
            // on the two short lengths that precede it and warm the identical code path.
            val warm = if (len >= 4096) WARMUP_BULK else WARMUP

            Benchmark.run("PyUnicode_FromString ($len chars)", warmupIterations = warm, iterations = 10_000) {
                val pyStr = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString(str) }
                if (pyStr != null) {
                    python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(pyStr) }
                }
            }

            val pyStr = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString(str) }
            if (pyStr != null) {
                Benchmark.run("PyUnicode_AsUTF8 ($len chars)", warmupIterations = warm, iterations = 10_000) {
                    python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(pyStr) }
                }
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(pyStr) }
            }
        }
    }

    @Test
    fun testIntegerMarshalling() {
        if (!interpreterAvailable) return

        Benchmark.run("PyLong_FromLongLong", warmupIterations = WARMUP, iterations = 100_000) {
            val obj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(123456789L) }
            if (obj != null) {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
            }
        }

        val obj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(123456789L) }
        if (obj != null) {
            Benchmark.run("PyLong_AsLongLong", warmupIterations = WARMUP, iterations = 100_000) {
                python.multiplatform.ffi.Python3.withPython { PyLong_AsLongLong(obj) }
            }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(obj) }
        }
    }

    @Test
    fun testReferenceCountingChurn() {
        if (!interpreterAvailable) return

        // Without machinery (just pointer)
        val rawObj = python.multiplatform.ffi.Python3.withPython { PyLong_FromLongLong(42L) }
        if (rawObj != null) {
            Benchmark.run("Manual IncRef/DecRef", warmupIterations = WARMUP, iterations = 100_000) {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_IncRef(rawObj) }
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(rawObj) }
            }
            
            Benchmark.run("PyObject wrapper creation", warmupIterations = WARMUP, iterations = 100_000) {
                val obj = PyObject(rawObj, borrowed = true)
                obj.close()
            }
            
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(rawObj) }
        }
    }

    @Test
    fun testAttributeAccess() {
        if (!interpreterAvailable) return
        
        // Try to get a module, e.g. sys
        val moduleName = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString("sys") }
        if (moduleName == null) return
        
        // Every C API call needs the GIL, this one included. It was outside a scope and
        // segfaulted the moment initialize() started parking the main thread state.
        val sysModule = python.multiplatform.ffi.Python3.withPython { python.native.ffi.PyImport_Import(moduleName) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(moduleName) }
        
        if (sysModule == null) {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.PyErr_Clear() }
            println("SKIPPED testAttributeAccess: Could not import sys")
            return
        }

        Benchmark.run("PyObject_GetAttrString", warmupIterations = WARMUP, iterations = 10_000) {
            val attr = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(sysModule, "version") }
            if (attr != null) {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(attr) }
            }
        }
        
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(sysModule) }
    }
}
