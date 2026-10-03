package python.native.ffi

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what composing a binder operation on the native side is worth.
 *
 * Every other benchmark here optimises the cost of ONE crossing. This one changes how many
 * crossings happen. That was the shape the Android path was originally built around --
 * `artMain` assembling a whole operation and `androidMain` calling the assembly once -- and
 * it is the only lever left now that the per-crossing cost has bottomed out at ~2-7ns.
 *
 * Two cases, chosen because they bracket the range:
 *
 *   getAttr        a fixed 4 crossings composed into 1 (alloc, call, error check, free)
 *   list -> array  N+1 crossings composed into 1, so the gap grows with N
 */
@RunWith(AndroidJUnit4::class)
class CompositionBenchmark {

    private companion object {
        const val TAG = "CompositionBenchmark"
        const val WARMUP = 20_000
        const val ITERS = 200_000
        const val ROUNDS = 5
        const val LIST_SIZE = 1000
    }

    private var sink = 0L

    /**
     * Every measurement here calls [bindings] directly, so nothing takes the GIL on its behalf
     * the way the object model does. The interpreter's main thread state is parked (see
     * [PythonOnDevice.ensureInitialised]), so the thread has to attach explicitly or these run
     * the C API with no thread state at all.
     *
     * Once per test rather than once per call: an attach inside the timed loop would be the
     * thing being measured.
     */
    private var gilState = 0

    @Before
    fun attachToInterpreter() {
        PythonOnDevice.ensureInitialised()
        gilState = PythonOnDevice.attach()
    }

    @After
    fun detachFromInterpreter() {
        PythonOnDevice.detach(gilState)
    }

    private inline fun best(iters: Int, body: () -> Long): Double {
        var b = Double.MAX_VALUE
        repeat(ROUNDS) {
            var local = 0L
            val t0 = System.nanoTime()
            for (i in 0 until iters) local += body()
            val ns = (System.nanoTime() - t0).toDouble() / iters
            sink += local
            if (ns < b) b = ns
        }
        return b
    }

    @Test
    fun composedVersusPerCallCrossings() {
        PythonOnDevice.ensureInitialised()

        val main = PythonOnDevice.withUtf8("__main__") { bindings.PyImport_ImportModuleN(it) }
        assertTrue("could not import __main__", main != 0L)

        val setup = "_bench_list = list(range($LIST_SIZE))"
        assertEquals(0, PythonOnDevice.withUtf8(setup) { bindings.PyRun_SimpleStringN(it).toInt() })

        val list = PythonOnDevice.withUtf8("_bench_list") { bindings.PyObject_GetAttrStringN(main, it) }
        assertTrue("could not read _bench_list", list != 0L)
        assertEquals(LIST_SIZE.toLong(), bindings.PyList_SizeNormal(list))

        // --- getAttr: 4 crossings vs 1 ---
        val perCallAttr = best(ITERS) {
            val p = bindings.ffiAllocUtf8("_bench_list")
            try {
                val r = bindings.PyObject_GetAttrStringN(main, p)
                if (r == 0L) bindings.PyErr_ClearN()
                r
            } finally {
                bindings.ffiFreeUtf8(p)
            }
        }
        val composedAttr = best(ITERS) { bindings.asmGetAttr(main, "_bench_list") }

        assertTrue("composed getAttr returned 0", bindings.asmGetAttr(main, "_bench_list") != 0L)

        // --- list -> array: N+1 crossings vs 1 ---
        val out = LongArray(LIST_SIZE)
        val small = 200
        val perCallList = best(small) {
            val n = bindings.PyList_SizeNormal(list).toInt()
            var acc = 0L
            for (i in 0 until n) acc += bindings.PyList_GetItemRaw(list, i.toLong())
            acc
        }
        val composedList = best(small) { bindings.asmListToArray(list, out).toLong() }

        assertEquals("composed copy wrote the wrong count", LIST_SIZE, bindings.asmListToArray(list, out))
        assertTrue("composed copy left the array empty", out[LIST_SIZE - 1] != 0L)

        Log.i(TAG, "=== API ${Build.VERSION.SDK_INT} / ${Build.MODEL} ===")
        Log.i(TAG, String.format("getAttr   per-call (4 crossings) %9.2f ns   composed %9.2f ns   %.1fx",
            perCallAttr, composedAttr, perCallAttr / composedAttr))
        Log.i(TAG, String.format("list->arr per-call (%d crossings) %9.2f ns   composed %9.2f ns   %.1fx",
            LIST_SIZE + 1, perCallList, composedList, perCallList / composedList))
        Log.i(TAG, "sink=$sink")
    }


    /**
     * The same comparison on a call that does real Python work.
     *
     * The probes above wrap trivial callees, which shows the boundary at its most flattering.
     * `Python3.exec` is a realistic binder operation: it crosses about eleven times -- three C
     * strings each needing an alloc and a free, plus AddModuleRef, GetAttrString, RunString and
     * three DecRefs -- but it also parses, compiles and executes Python, and that work appears
     * in both numbers. Whatever ratio survives here is what composing is actually worth on a
     * call people make.
     */
    @Test
    fun composedVersusPerCallOnRealWork() {
        PythonOnDevice.ensureInitialised()

        val code = "x = 1 + 1"
        val iters = 20_000

        // Per-call: the sequence Python3.exec runs, one crossing at a time.
        val perCall = best(iters) {
            var rc = -1L
            val mainName = bindings.ffiAllocUtf8("__main__")
            try {
                val mod = bindings.PyImport_ImportModuleN(mainName)
                if (mod != 0L) {
                    val dictName = bindings.ffiAllocUtf8("__dict__")
                    try {
                        val globals = bindings.PyObject_GetAttrStringN(mod, dictName)
                        if (globals != 0L) {
                            val src = bindings.ffiAllocUtf8(code)
                            try {
                                rc = bindings.PyRun_SimpleStringN(src).toLong()
                            } finally {
                                bindings.ffiFreeUtf8(src)
                            }
                        }
                    } finally {
                        bindings.ffiFreeUtf8(dictName)
                    }
                }
            } finally {
                bindings.ffiFreeUtf8(mainName)
            }
            rc
        }

        val composed = best(iters) { bindings.asmExec(code).toLong() }

        assertEquals("composed exec reported failure", 0, bindings.asmExec(code))

        Log.i(TAG, "--- Python3.exec(\"$code\"), real Python work in both ---")
        Log.i(TAG, String.format("per-call  %9.2f ns   composed %9.2f ns   %.2fx", perCall, composed, perCall / composed))
        Log.i(TAG, "sink=$sink")
    }


    /**
     * Whether the composition win can be had without composing.
     *
     * Every composed-versus-per-call result so far turned out to be about string marshalling
     * rather than crossing count: the per-call figures were identical on API 26 and API 36
     * despite a 20x difference in per-crossing cost. Desktop pays ~200 ns per string where
     * Android pays ~2500 ns, and the difference is that Panama allocates off-heap inside the
     * JVM while `ffiAllocUtf8` makes a JNI round trip to malloc and copy.
     *
     * A DirectByteBuffer allocates off-heap from Java too. Take its address once, and every
     * call afterwards is encode-and-copy inside the JVM followed by passing a long. If that
     * lands near the composed number, the marshalling is the whole story and composition is
     * only needed where crossing count genuinely dominates -- bulk iteration.
     */
    @Test
    fun directBufferMarshallingVersusComposition() {
        PythonOnDevice.ensureInitialised()

        val main = PythonOnDevice.withUtf8("__main__") { bindings.PyImport_ImportModuleN(it) }
        assertTrue("could not import __main__", main != 0L)
        PythonOnDevice.withUtf8("_bench_list = list(range(8))") { bindings.PyRun_SimpleStringN(it) }

        val name = "_bench_list"

        val perCall = best(ITERS) {
            val p = bindings.ffiAllocUtf8(name)
            try { bindings.PyObject_GetAttrStringN(main, p) } finally { bindings.ffiFreeUtf8(p) }
        }

        val composed = best(ITERS) { bindings.asmGetAttr(main, name) }

        // One buffer, one address lookup. Everything per-call after this stays inside the JVM.
        val scratch = java.nio.ByteBuffer.allocateDirect(256)
        val scratchAddr = bindings.ffiDirectBufferAddress(scratch)
        assertTrue("GetDirectBufferAddress returned 0", scratchAddr != 0L)

        val direct = best(ITERS) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            scratch.clear()
            scratch.put(bytes)
            scratch.put(0)
            bindings.PyObject_GetAttrStringN(main, scratchAddr)
        }

        assertTrue("direct-buffer path returned 0", run {
            val bytes = name.toByteArray(Charsets.UTF_8)
            scratch.clear(); scratch.put(bytes); scratch.put(0)
            bindings.PyObject_GetAttrStringN(main, scratchAddr) != 0L
        })

        Log.i(TAG, "--- getAttr: can marshalling alone close the gap? (API ${Build.VERSION.SDK_INT}) ---")
        Log.i(TAG, String.format("per-call (ffiAllocUtf8) %9.2f ns", perCall))
        Log.i(TAG, String.format("composed (asmGetAttr)   %9.2f ns   %.2fx vs per-call", composed, perCall / composed))
        Log.i(TAG, String.format("direct buffer, no comp  %9.2f ns   %.2fx vs per-call", direct, perCall / direct))
        Log.i(TAG, "sink=$sink")
    }


    /**
     * Two ways to close the gap that remains on old ART.
     *
     * The direct-buffer path beats composition on API 36 but loses to it on API 26, and the
     * reason is not crossings -- both make exactly one ordinary-JNI call there. It is where the
     * string gets encoded: composition hands the jstring to `GetStringUTFChars` and encodes in
     * native code, while the direct-buffer path encodes in Java, and API 26's ART is much worse
     * at that.
     *
     * So two candidates, both attacking the encode rather than the crossing:
     *
     *  - **ASCII fast path**: write the chars straight into the buffer instead of going through
     *    `toByteArray`, which allocates a byte[] per call. Python identifiers are ASCII in
     *    practice; anything else would fall back.
     *  - **Interning**: attribute and module names are repeated literals, so encode once and
     *    cache the C string. Per-call cost becomes a map lookup. A real implementation would
     *    need a bound on the cache, since callers can pass arbitrary strings.
     */
    @Test
    fun cheaperMarshallingForOldArt() {
        PythonOnDevice.ensureInitialised()

        val main = PythonOnDevice.withUtf8("__main__") { bindings.PyImport_ImportModuleN(it) }
        assertTrue("could not import __main__", main != 0L)
        PythonOnDevice.withUtf8("_bench_list = list(range(8))") { bindings.PyRun_SimpleStringN(it) }

        val name = "_bench_list"
        val scratch = java.nio.ByteBuffer.allocateDirect(256)
        val scratchAddr = bindings.ffiDirectBufferAddress(scratch)

        val composed = best(ITERS) { bindings.asmGetAttr(main, name) }

        val viaToByteArray = best(ITERS) {
            scratch.clear()
            scratch.put(name.toByteArray(Charsets.UTF_8))
            scratch.put(0)
            bindings.PyObject_GetAttrStringN(main, scratchAddr)
        }

        val viaAsciiFastPath = best(ITERS) {
            scratch.clear()
            for (i in name.indices) scratch.put(name[i].code.toByte())
            scratch.put(0)
            bindings.PyObject_GetAttrStringN(main, scratchAddr)
        }

        // Interning: the C string is encoded once, outside the loop.
        val interned = bindings.ffiAllocUtf8(name)
        val cache = HashMap<String, Long>().apply { put(name, interned) }
        val viaInterning = try {
            best(ITERS) {
                val addr = cache[name]!!
                bindings.PyObject_GetAttrStringN(main, addr)
            }
        } finally {
            bindings.ffiFreeUtf8(interned)
        }

        Log.i(TAG, "--- marshalling variants (API ${Build.VERSION.SDK_INT}) ---")
        Log.i(TAG, String.format("composed              %9.2f ns", composed))
        Log.i(TAG, String.format("direct + toByteArray  %9.2f ns", viaToByteArray))
        Log.i(TAG, String.format("direct + ascii path   %9.2f ns", viaAsciiFastPath))
        Log.i(TAG, String.format("interned C string     %9.2f ns", viaInterning))
        Log.i(TAG, "sink=$sink")
    }
}
