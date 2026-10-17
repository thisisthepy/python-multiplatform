package fixture.app

import org.junit.Assume.assumeTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import java.io.File
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #45: compiled TypedPython code calls Kotlin through the binder, inside the embedded
 * runtime, and behaves exactly as the same code interpreted.
 *
 * `src/desktopTest/typedpython/tp_kotlin_bridge.py` is loaded twice into this JVM's interpreter:
 * as source (`_tpi`, interpreted) and as the extension `buildTypedPythonBridge` compiled from it
 * against the embedded CPython's headers and extension suffix (`_tpc`, compiled). Its functions call
 * the Kotlin in `commonMain/.../TypedPythonBridge.kt` through the KSP-generated binder -- the Kotlin
 * constructor (a typed, owned `TpSample` proxy), a method on that proxy, the proxy passed back to a
 * Kotlin function, a Python object passed to a `PyObject` parameter, and a Kotlin throw.
 *
 * The four done criteria, one test each, plus a premise test that the comparison is not vacuous
 * (the compiled module really is compiled, for this interpreter):
 *
 * | criterion | test |
 * |---|---|
 * | equal results | [compiledAndInterpretedRunsReturnTheSameFloatAndKotlinSeesTheSameCalls] |
 * | equal Kotlin-side call counts | the same test, through [TpBridgeLedger] |
 * | a Kotlin exception has the same Python type and message | [aKotlinExceptionReachesTheCompiledCallerAsTheSamePythonException] |
 * | no reference leaks across 10,000 calls | [tenThousandCompiledCallsLeakNoReferenceAndNoKotlinHandle] |
 *
 * **Skipped, not failed,** when the build could not compile the extension: no host Python with
 * Pyrefly 1.3.2 (`-PtypedpythonPython=<interpreter>`, default `python-multiplatform-ksp/.venv/bin/python`)
 * or a host other than macOS. The skip reason names which. When the build *did* try and failed, the
 * build fails -- `buildTypedPythonBridge` is a dependency of `desktopTest`.
 */
class TypedPythonKotlinCallTest {

    private lateinit var bridge: Properties

    @BeforeTest
    fun install() {
        val skip = System.getProperty("typedpython.bridge.skip")
        assumeTrue("TypedPython bridge extension not built: $skip", skip == null)
        val propertiesPath = System.getProperty("typedpython.bridge.properties")
        assumeTrue(
            "TypedPython bridge extension not built: system property typedpython.bridge.properties is unset " +
                "(run through :python-multiplatform-ksp:fixtures:app:desktopTest)",
            propertiesPath != null,
        )
        val file = File(propertiesPath!!)
        // Present-but-missing is a build defect, not a skip: the task ran and wrote nothing.
        assertTrue(file.isFile, "buildTypedPythonBridge ran but $file does not exist")
        bridge = Properties().apply { file.inputStream().use { load(it) } }

        Python3.initialize(silent = true)
        UpcallTable.clear()
        HandleTable.releaseAll()
        UpcallTable.install(FunctionTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        TpBridgeLedger.reset()

        // Both copies are loaded after the binder is installed: the module body runs
        // `from fixture.app import ...`, which must find the proxies this install just rendered.
        // A fresh copy per test, because `UpcallTable.clear()` above invalidates the handles the
        // previous test's proxies were rendered with.
        Python3.exec(
            """
            import importlib.machinery as _tp_machinery
            import importlib.util as _tp_util

            def _tp_load_pair(source, extension, name):
                spec = _tp_util.spec_from_file_location(name + '_interpreted', source)
                interpreted = _tp_util.module_from_spec(spec)
                spec.loader.exec_module(interpreted)
                # The extension's PyInit_ is named after the source stem, so it is loaded under it.
                loader = _tp_machinery.ExtensionFileLoader(name, extension)
                spec = _tp_util.spec_from_file_location(name, extension, loader=loader)
                compiled = _tp_util.module_from_spec(spec)
                loader.exec_module(compiled)
                return interpreted, compiled

            _tpi, _tpc = _tp_load_pair(${py(prop("source"))}, ${py(prop("extension"))}, ${py(prop("module"))})
            del _tp_load_pair, _tp_machinery, _tp_util
            """.trimIndent(),
        )
    }

    @AfterTest
    fun cleanup() {
        if (!::bridge.isInitialized) return
        Python3.exec(
            "import gc\n" +
                "for _n in ('_tpi', '_tpc', '_tp_token', '_tp_r_i', '_tp_r_c', '_tp_e_i', '_tp_e_c'):\n" +
                "    globals().pop(_n, None)\n" +
                "gc.collect()",
        )
        UpcallTable.clear()
        HandleTable.releaseAll()
        TpBridgeLedger.reset()
    }

    /**
     * Premise for everything below. If any line here fails, the other tests would be comparing the
     * interpreter with itself, or loading an extension built for a different CPython.
     */
    @Test
    fun theCompiledModuleIsCompiledForTheInterpreterItRunsIn() {
        Python3.exec(
            """
            import importlib.machinery, sys, sysconfig, types
            _v = '%d.%d.%d' % sys.version_info[:3]
            assert _v == ${py(prop("pythonVersion"))}, (
                'built against headers of ' + ${py(prop("pythonVersion"))} + ' but running in ' + _v)
            assert ${py(prop("extSuffix"))} in importlib.machinery.EXTENSION_SUFFIXES, (
                'extension suffix ' + ${py(prop("extSuffix"))} + ' is not one this interpreter imports: '
                + repr(importlib.machinery.EXTENSION_SUFFIXES))
            _gil_disabled = bool(sysconfig.get_config_var('Py_GIL_DISABLED') or 0)
            assert _gil_disabled == ${pyBool(prop("gilDisabled"))}, (
                'flavour mismatch: extension built with Py_GIL_DISABLED=' + ${py(prop("gilDisabled"))}
                + ', interpreter has ' + repr(_gil_disabled))
            _names = ${py(prop("compiledFunctions"))}.split(',')
            assert sorted(_names) == ['checked', 'drive'], _names
            for _n in _names:
                assert type(getattr(_tpc, _n)) is types.BuiltinFunctionType, (_n, type(getattr(_tpc, _n)))
                assert type(getattr(_tpi, _n)) is types.FunctionType, (_n, type(getattr(_tpi, _n)))
            assert sorted(_tpc.__typedpython_interpreted__) == ['checked', 'drive'], _tpc.__typedpython_interpreted__
            assert _tpc.__typedpython_deopts__ == 0
            # Calling the Kotlin class gives its typed proxy -- with the method the module calls --
            # not a bare handle integer or a generic owner.
            from fixture.app import TpSample
            _s = TpSample(3, 0.5)
            assert type(_s) is TpSample, type(_s)
            assert _s.index == 3 and _s.weight == 0.5 and _s.weigh(2.0) == 4.0
            del _s, _n, _names, _v, _gil_disabled, TpSample
            """.trimIndent(),
        )
    }

    @Test
    fun compiledAndInterpretedRunsReturnTheSameFloatAndKotlinSeesTheSameCalls() {
        Python3.exec("_tp_token = object()\n_tp_r_i = _tpi.drive(200, 0.25, _tp_token)")
        val interpreted = TpBridgeLedger.snapshot()
        // Not vacuous: every Kotlin entry point was reached, once per step.
        assertEquals(200L, TpBridgeLedger.samples, interpreted)
        assertEquals(200L, TpBridgeLedger.weighs, interpreted)
        assertEquals(200L, TpBridgeLedger.accumulates, interpreted)
        assertEquals(200L, TpBridgeLedger.touches, interpreted)
        assertEquals((0L until 200L).sum(), TpBridgeLedger.indexSum, interpreted)

        TpBridgeLedger.reset()
        Python3.exec("_tp_r_c = _tpc.drive(200, 0.25, _tp_token)")
        val compiled = TpBridgeLedger.snapshot()

        assertEquals(interpreted, compiled, "Kotlin saw different calls or values from the compiled run")
        Python3.exec(
            """
            assert type(_tp_r_c) is float and type(_tp_r_i) is float, (type(_tp_r_i), type(_tp_r_c))
            assert _tp_r_c.hex() == _tp_r_i.hex(), (
                'compiled ' + _tp_r_c.hex() + ' != interpreted ' + _tp_r_i.hex())
            assert _tpc.__typedpython_deopts__ == 0, _tpc.__typedpython_deopts__
            """.trimIndent(),
        )
    }

    @Test
    fun aKotlinExceptionReachesTheCompiledCallerAsTheSamePythonException() {
        Python3.exec(
            """
            def _tp_catch(f):
                try:
                    f(100, 40.0)
                except BaseException as e:
                    return type(e), str(e)
                raise AssertionError('tpCheck never threw: the limit is never passed')
            _tp_e_i = _tp_catch(_tpi.checked)
            """.trimIndent(),
        )
        val interpreted = TpBridgeLedger.snapshot()
        assertTrue(TpBridgeLedger.checks > 1, "the throw must come after some successful calls: $interpreted")

        TpBridgeLedger.reset()
        Python3.exec("_tp_e_c = _tp_catch(_tpc.checked)\ndel _tp_catch")
        val compiled = TpBridgeLedger.snapshot()

        assertEquals(interpreted, compiled, "the compiled caller stopped at a different call than the interpreted one")
        Python3.exec(
            """
            assert _tp_e_i[0] is RuntimeError, _tp_e_i
            assert _tp_e_i[1].startswith('tpCheck: '), _tp_e_i
            assert _tp_e_c == _tp_e_i, 'compiled raised ' + repr(_tp_e_c) + ', interpreted raised ' + repr(_tp_e_i)
            # And the path that does not throw agrees too.
            assert _tpc.checked(5, 1e9).hex() == _tpi.checked(5, 1e9).hex()
            assert _tpc.__typedpython_deopts__ == 0, _tpc.__typedpython_deopts__
            """.trimIndent(),
        )
    }

    /**
     * 10,000 calls of each compiled function -- `drive` (3 Kotlin objects created and released per
     * call) and `checked` on its throwing path -- must leave every reference count and the Kotlin
     * handle table where they were.
     *
     * Watched: the token passed to Kotlin on every step, the binder proxies the compiled code reads
     * as globals on every step (a missed release of a `Global` read shows here), and
     * [HandleTable.liveCount] (a proxy never deallocated keeps its Kotlin object rooted). The same
     * loop runs interpreted first as a control, so a leak in the binder itself is reported as such and
     * not blamed on the compiled code.
     *
     * **The token's count is read only after the Kotlin side has given its references back.** The
     * binder wraps the token in a `PyObject` that takes a reference of its own, and that reference
     * returns only when the JVM collects the wrapper and its cleaner runs (`tpTouch` cannot close it:
     * #98). Read straight after the loop, the interpreted control showed `{'token': (10, 17350)}`.
     * [settledCounts] therefore drives `System.gc()` / `System.runFinalization()` between reads --
     * with the GIL released, so the cleaner thread can take it -- until the counts reach the baseline
     * or stop moving, within a bounded time. Same idea as `CycleCollectionTest.settleJvmFinalisation`;
     * the library's own `ReleaseCounter` is `internal` and not reachable from this module.
     *
     * Free-threaded CPython defers reference counting of functions and classes, so `sys.getrefcount`
     * on those says nothing there; on that flavour only the token and the handle table are compared.
     */
    @Test
    fun tenThousandCompiledCallsLeakNoReferenceAndNoKotlinHandle() {
        Python3.exec(
            """
            import gc, sys, sysconfig
            import fixture.app as _tp_app
            _tp_token = object()
            _tp_watched = {'token': _tp_token}
            if not (sysconfig.get_config_var('Py_GIL_DISABLED') or 0):
                for _n in ('TpSample', 'tpAccumulate', 'tpCheck', 'tpTouch'):
                    _tp_watched[_n] = getattr(_tp_app, _n)
                _tp_watched['drive'] = _tpc.drive
                _tp_watched['checked'] = _tpc.checked
            del _tp_app

            def _tp_counts():
                gc.collect()
                return repr(sorted((k, sys.getrefcount(v)) for k, v in _tp_watched.items()))

            def _tp_loop(module, n):
                for _ in range(n):
                    module.drive(3, 0.25, _tp_token)
                    try:
                        module.checked(100, 40.0)
                    except RuntimeError:
                        pass

            _tp_loop(_tpi, 1)
            _tp_loop(_tpc, 1)
            """.trimIndent(),
        )
        // The baseline has no target to reach, so it is "stopped moving" -- with a floor of rounds so
        // the warm-up's pending wrappers are collected before it is taken.
        val before = settledCounts(target = null)
        val baseline = HandleTable.liveCount
        TpBridgeLedger.reset()

        Python3.exec("_tp_loop(_tpi, 10000)")
        assertEquals(
            before, settledCounts(target = before),
            "control: the INTERPRETED loop leaks references even after the Kotlin side has released, " +
                "so the binder does (sorted (name, sys.getrefcount) pairs)",
        )
        assertEquals(baseline, HandleTable.liveCount, "control: the interpreted loop left Kotlin objects rooted")
        val interpreted = TpBridgeLedger.snapshot()
        assertEquals(30_000L, TpBridgeLedger.samples, interpreted)
        assertEquals(10_000L * 3, TpBridgeLedger.touches, interpreted)

        TpBridgeLedger.reset()
        Python3.exec("_tp_loop(_tpc, 10000)\nassert _tpc.__typedpython_deopts__ == 0, _tpc.__typedpython_deopts__")
        assertEquals(
            before, settledCounts(target = before),
            "the COMPILED loop leaks references (sorted (name, sys.getrefcount) pairs; the interpreted " +
                "control above settled back to the baseline)",
        )
        assertEquals(baseline, HandleTable.liveCount, "the compiled loop left Kotlin objects rooted")
        assertEquals(interpreted, TpBridgeLedger.snapshot(), "Kotlin saw different calls from the compiled loop")

        Python3.exec(
            "for _n in ('_tp_watched', '_tp_counts', '_tp_loop', '_n'):\n" +
                "    globals().pop(_n, None)",
        )
    }

    /**
     * The watched reference counts once the JVM has collected the `PyObject` wrappers the binder made
     * and their cleaners have handed the references back.
     *
     * Called with this thread **not** holding the GIL (between `Python3.exec` calls), so the cleaner
     * thread can take it. Each round: `System.gc()`, `System.runFinalization()`, a short sleep, then one
     * read. Returns as soon as the reading equals [target]; with no target, once it has not moved for
     * [STABLE_ROUNDS] reads after at least [MIN_ROUNDS]. Gives up after [SETTLE_TIMEOUT_MS] and returns
     * the last reading, so the caller's assertion shows where it stopped.
     */
    private fun settledCounts(target: String?): String {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var last = readCounts()
        var stable = 0
        var rounds = 0
        while (System.currentTimeMillis() < deadline) {
            if (target != null && last == target) return last
            System.gc()
            System.runFinalization()
            Thread.sleep(20)
            val now = readCounts()
            rounds++
            if (now == last) stable++ else stable = 0
            last = now
            if (target == null && rounds >= MIN_ROUNDS && stable >= STABLE_ROUNDS) return last
        }
        return last
    }

    private fun readCounts(): String =
        Python3.import("__main__").getAttr("__dict__").let { globals ->
            Python3.eval("_tp_counts()", PY_EVAL_INPUT, globals, globals).toString()
        }

    private fun prop(key: String): String =
        bridge.getProperty(key) ?: error("bridge.properties has no '$key': $bridge")

    /** A Python string literal for [s]. */
    private fun py(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    private fun pyBool(s: String): String = when (s) {
        "true" -> "True"
        "false" -> "False"
        else -> error("not a boolean: $s")
    }

    private companion object {
        /** `Py_eval_input`: [Python3.eval]'s start symbol for a single expression. */
        const val PY_EVAL_INPUT = 258
        const val SETTLE_TIMEOUT_MS = 30_000L
        const val MIN_ROUNDS = 10
        const val STABLE_ROUNDS = 5
    }
}
