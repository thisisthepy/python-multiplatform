package python.multiplatform.ffi.upcall

import python.multiplatform.currentPlatform
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.overhead.Benchmark
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * What the *generated proxy* costs, on top of the boundary `UpcallBoundaryCostTest` already prices.
 *
 * That test answers "what does one upcall cost", and its answer is a `_pm_invoke(handle, args)`
 * called straight from Python. **Nobody writes that.** What a user writes is `demo.calc.ping()`,
 * `Counter(10).increment(5)`, `c.value`, `Counter.created`, `await g.fetch()` -- and each of those
 * goes through a layer of generated Python that the boundary figure says nothing about: a global
 * lookup, a bound-method dispatch, an argument tuple built one element longer to carry the receiver,
 * a `property` descriptor, a metaclass descriptor, or an `async def` wrapper with a `hasattr` in it.
 *
 * So this is `UpcallBoundaryCostTest`'s shape applied one level up. It follows that test's design
 * deliberately and in full, because that design has already been argued and validated:
 *
 * - **every baseline is measured in the same run**, on the same machine, in the same thermal state.
 *   A ratio against a remembered number is not a measurement.
 * - **the same warmup count as the measured loop, in the same shape**, run first. An unwarmed first
 *   row in this repository once made a subset of the work look cheaper than the whole of it.
 * - **nothing asserts a duration.** A wall-clock threshold on a shared build machine, a simulator or
 *   a Node host is a flake generator and this repository has had exactly that failure. What is
 *   asserted is structural: the proxies returned the right values, the async row really took the
 *   fast path (zero `Future`s constructed), and every figure came back positive rather than a zero
 *   from a clock with no resolution.
 *
 * ### The harness, and why every row has the same shape
 *
 * Every row is a **zero-argument Python function** called `N` times by one shared timing loop, so
 * each row pays exactly one identical loop iteration plus one identical zero-arg call. The floor row
 * ([NOOP]) is that and nothing else, so *row minus floor* is the body and only the body. Rows are
 * not comparable to each other any other way: a `lambda` here and an inlined expression there would
 * differ by a Python call frame, which on the fast targets is a fifth of the whole figure.
 *
 * The **global lookups stay inside the timed body on purpose**, because they are part of what the
 * user pays: `demo.calc.tally` really is a module attribute access, and `c.increment(5)` really does
 * resolve `increment` on the instance before it calls anything. The one thing hoisted out is the
 * receiver handle in the raw rows (`_pc_self = _pc_c._pm_handle`), because there the attribute lookup
 * is exactly the overhead being measured and leaving it in both halves would cancel it out of the
 * answer.
 *
 * ### The two floors, and why a Python-only one is needed
 *
 * A proxy property read is a descriptor *and* a boundary crossing. Priced against the raw boundary
 * alone, the descriptor and the extra Python frame are indistinguishable from each other. So a pure
 * Python class with a plain attribute and a `@property` over a constant is measured beside them:
 * that is what CPython charges for the descriptor machinery with no Kotlin anywhere, and the proxy's
 * excess over the raw boundary should land near it if the generated shape is not doing anything
 * unusual.
 *
 * ### What this test has already found
 *
 * A top-level Kotlin `val`/`var` read cost **551-587 ns more than the boundary call underneath it**
 * on desktop, while every other generated surface -- including the metaclass descriptor doing the
 * same job for a class static -- cost between 2 and 82 ns more. That was not noise and it was not
 * the boundary: [PythonProxySource] answered a module attribute with a `__getattr__` hook, which
 * CPython consults only *after* `module_getattro` has built and raised the formatted "module has no
 * attribute" `AttributeError` for the hook to catch and throw away. One raised exception per read.
 * Replacing it with a `property` on a per-module type -- the same data-descriptor shape the class
 * statics already used -- moved that row to 16-29 ns, in line with everything else.
 *
 * The measurement is what found it. Nothing about the two shapes differs in behaviour, both pass
 * `PythonProxyInstallTest` unchanged, and reading the generated source tells you nothing about the
 * cost of either.
 *
 * It has also found something that is not a cost at all. The constructor row was **unmeasurable on
 * desktop** -- its delta swung either side of zero between runs while no other row's did -- because
 * both halves of the pair rooted a Kotlin object in [HandleTable] per call and neither gave it
 * back. The run ended with **78 002 live handles**, and every row in the report had been timed
 * against a table that grew by tens of thousands of entries while it ran. Two different defects
 * wore that one number: the generated proxy class had no `__del__` at all, which is a library bug
 * ([PythonProxySource], `ProxyHandleLifetimeTest`); and this file's *raw* constructor row never
 * released the bare handle the boundary had handed it, which is the caller's to release and is a
 * bug in the harness. The `assertEquals(handlesBefore, handlesAfter)` below is what keeps either
 * from coming back silently.
 *
 * ### wasm
 *
 * This section used to read "there are **no generated proxies on wasmJs** and there structurally
 * cannot be", and the sync rows above now print real figures for that target. What changed is not
 * the export constraint -- `@WasmExport` is still honoured only in the application's own
 * compilation -- but the observation that one export was already enough: a `PyCFunction` carries a
 * `self` as well as a function pointer, so the single `pmp_invoke` backs all five entry points, told
 * apart by an op code where a handle would otherwise sit. See [publishesProxyEntryPoints] and
 * `wasmJsMain`'s `UpcallEntry`.
 *
 * ### `import asyncio` and the wasm precedent
 *
 * `AsyncUpcallPortabilityTest` records that `import asyncio` does not raise on this wasm build --
 * it **traps the instance**, killing the process. That did not change with the bootstrap, so the
 * await row below sits behind [proxyBootstrapSupportsAsyncio] instead: the sync rows run on wasm,
 * and the one row that needs an event loop returns before any `import` happens, exactly as
 * `PythonProxyInstallTest`'s own asyncio-using test does.
 */
class GeneratedProxyCostTest {

    private companion object {
        /** Calls per timed loop; the same count [UpcallBoundaryCostTest] uses. */
        const val N = 10_000

        /**
         * Same shape as the measured loop, run first -- and **19 rows are warmed before any row is
         * timed**, which is why this number is an order of magnitude smaller than the one
         * [python.native.ffi.UpcallBoundaryCostTest] needs and is still enough.
         *
         * That file's sweep established the quantity both files depend on: the host JIT needs
         * ~70 000 calls through the boundary before the figure stops falling, on desktop and on wasm
         * alike, and below that a row reports how warm the process happened to be rather than what
         * the call costs. What protects this file is [timeAll]'s shape -- every row is warmed, then
         * every row is timed -- so the *shared* `_pm_invoke` path receives 19 x WARMUP before the
         * first row is measured, while each row's own Python function only has to warm its own
         * bytecode, and the same sweep showed pure-Python bytecode is flat within the first 10 000
         * calls.
         *
         * At 3 000 that shared total was 57 000, just under the knee. 5 000 puts it at 95 000, past
         * it, and costs about 19 ms per host -- against roughly a second if this file copied the
         * other's per-row count, which it does not need. Measured at 3 000, this file's raw rows
         * already agreed to within 3-7% between a solo run and the full suite, because of the shape
         * above; the point of the change is that the agreement is now by construction rather than by
         * a margin that happened to be small.
         */
        const val WARMUP = 5_000

        /**
         * `install()` renders several hundred lines and `exec`s them, so it is milliseconds rather
         * than nanoseconds and a 10 000-iteration loop would dominate the suite. Small counts are
         * defensible here for the reason they are not elsewhere: the quantity being measured is
         * large relative to the clock, and it is a **once per process** cost, so what matters is its
         * order of magnitude against application startup and not its last digit.
         */
        const val INSTALL_N = 20
        const val INSTALL_WARMUP = 5

        const val NOOP = "empty Python function (the harness floor)"

        // Sync rows: each raw row is the boundary call the proxy row above/below it wraps.
        const val RAW_FUNCTION = "_pm_invoke(h, ())                        raw boundary, arity 0"
        const val PROXY_FUNCTION = "demo.calc.ping()                          module function proxy"
        /**
         * The only raw row that does two boundary crossings, and it has to. A CONSTRUCTOR entry
         * answers with a bare `HandleTable` integer whose root the caller owns, so "build a Kotlin
         * object and be done with it" is an invoke *and* a release here -- which is exactly what
         * the proxy row beside it does, the release happening in the `__del__` that runs when the
         * discarded `Counter` dies inside the same loop iteration.
         */
        const val RAW_CTOR = "_pm_release(_pm_invoke(h, (10,)))        raw boundary + release"
        const val PROXY_CTOR = "Counter(10)                               constructor proxy"
        const val RAW_METHOD = "_pm_invoke(h, (self, 5))                 raw boundary"
        const val PROXY_METHOD = "c.increment(5)                            instance method proxy"
        const val RAW_GETTER = "_pm_invoke(h, (self,))                   raw boundary"
        const val PROXY_GETTER = "c.value                                   property read (descriptor)"
        const val RAW_SETTER = "_pm_invoke(h, (self, 'x'))               raw boundary"
        const val PROXY_SETTER = "c.label = 'x'                             property write (descriptor)"
        /**
         * Deliberately the *same call* as [RAW_FUNCTION] -- arity zero, a different handle -- and
         * kept as a separate row for two reasons at once. It is the honest basis for the static
         * rows, which really are arity-zero calls; and because the two rows differ only in noise,
         * whatever they differ *by* is this report's own resolution. A proxy delta smaller than
         * that is not a number.
         *
         * The two labels must stay distinct: they keyed the same map entry once, which silently
         * priced every arity-0 proxy row against one of the two measurements instead of its own,
         * and made the resolution row print a constant zero. The uniqueness check in the test body
         * is the guard against that returning.
         */
        const val RAW_STATIC = "_pm_invoke(h, ())                        raw boundary, arity 0 again"
        const val RAW_STATIC_SET = "_pm_invoke(h, (12,))                     raw boundary"
        const val PROXY_STATIC_GET = "Counter.created                           static read (metaclass)"
        const val PROXY_STATIC_SET = "Counter.created = 12                      static write (metaclass)"
        const val PROXY_MODULE_GET = "demo.calc.tally                           top-level read (module type)"
        const val PROXY_MODULE_SET = "demo.calc.tally = 9                       top-level write (module type)"

        // Pure-Python floors: the descriptor machinery with no boundary under it at all.
        const val PY_ATTR = "plain.x            pure Python, no boundary"
        const val PY_PROPERTY = "plain.p            pure Python @property, no boundary"

        // Async rows.
        const val ASYNC_RAW = "_pm_invoke(h, (21,)) inside the coroutine, no await"
        const val ASYNC_PY = "await a pure-Python coroutine that never suspends"
        const val ASYNC_PROXY = "await demo.calc.doubleNow(21)   generated async def, fast path"

        // install() rows.
        const val INSTALL_RENDER = "PythonProxySource.render()      Kotlin string building only"
        const val INSTALL_FULL = "PythonProxySource.install()     render + Python3.exec"
    }

    @BeforeTest
    fun setUp() {
        UpcallTable.install(listOf(ProxyFragment))
        ProxyFragment.parked = null
        ProxyFragment.tally = 0
        ProxyFragment.created = 0
    }

    @AfterTest
    fun tearDown() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        ProxyFragment.parked = null
        if (PythonTestFixture.available) Python3.exec("_pc = None")
    }

    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    private fun pyDouble(expression: String): Double = py(expression).toDouble()

    // ------------------------------------------------------------------------------ sync surfaces

    @Test
    fun everyGeneratedSyncSurfaceIsPricedAgainstTheRawBoundaryItWraps() = withProxies {
        Python3.exec(HARNESS)
        Python3.exec(SYNC_BODIES)

        // The proxies are timed only after they have been shown to answer correctly. A row that
        // measured an AttributeError path would be a fast, meaningless number.
        Python3.exec(
            """
            _pc_check = {
                'fn': _pc_ping(),
                'ctor': _pc_Counter(10).value,
                'method': _pc_c.increment(0),
                'getter': _pc_c.value,
                'static': _pc_Counter.created,
                'module': _pc_calc.tally,
            }
            """.trimIndent(),
        )
        assertEquals("7", py("_pc_check['fn']"), "the module-function proxy does not reach Kotlin")
        assertEquals("10", py("_pc_check['ctor']"), "the constructor proxy does not reach Kotlin")
        assertEquals("1", py("_pc_check['method']"), "the instance-method proxy does not reach Kotlin")
        assertEquals("1", py("_pc_check['getter']"), "the property descriptor does not reach Kotlin")
        assertEquals("0", py("_pc_check['static']"), "the metaclass descriptor does not reach Kotlin")
        assertEquals("0", py("_pc_check['module']"), "the module descriptor does not reach Kotlin")

        // Read before the loops and again after them. The constructor pair is the only row whose
        // fixture can move under it -- both halves root a Kotlin object per call -- and until each
        // half gave its root back, 30 000 iterations left 78 002 entries behind and every figure in
        // this report was measured against a table that grew while it was being timed. Asserting
        // the two counts are equal is what stops that returning, and it is why both are printed
        // rather than only the total: a run that had stopped rooting anything at all would also end
        // with a flat count, and the first number is what tells the two apart.
        val handlesBefore = HandleTable.liveCount

        val rows = timeAll(
            NOOP to "_pc_noop",
            PY_ATTR to "_pc_py_attr",
            PY_PROPERTY to "_pc_py_prop",
            RAW_FUNCTION to "_pc_raw_fn",
            PROXY_FUNCTION to "_pc_pxy_fn",
            RAW_CTOR to "_pc_raw_ctor",
            PROXY_CTOR to "_pc_pxy_ctor",
            RAW_METHOD to "_pc_raw_method",
            PROXY_METHOD to "_pc_pxy_method",
            RAW_GETTER to "_pc_raw_getter",
            PROXY_GETTER to "_pc_pxy_getter",
            RAW_SETTER to "_pc_raw_setter",
            PROXY_SETTER to "_pc_pxy_setter",
            RAW_STATIC to "_pc_raw_static",
            RAW_STATIC_SET to "_pc_raw_static_set",
            PROXY_STATIC_GET to "_pc_pxy_static_get",
            PROXY_STATIC_SET to "_pc_pxy_static_set",
            PROXY_MODULE_GET to "_pc_pxy_module_get",
            PROXY_MODULE_SET to "_pc_pxy_module_set",
        )

        // Before the report, not after: `reportSync` keys everything by row name, so two rows
        // sharing a name would quietly price one against the other's measurement. That happened --
        // `RAW_FUNCTION` and `RAW_STATIC` were the same string -- and it is invisible in the
        // output, because both rows still print and both still look plausible.
        assertEquals(
            rows.size, rows.map { it.first }.toSet().size,
            "two rows share a name, so at least one figure below is another row's measurement",
        )
        val handlesAfter = HandleTable.liveCount
        reportSync(rows, handlesBefore, handlesAfter)

        for ((name, ns) in rows) {
            assertTrue(ns > 0.0, "$name measured ${ns}ns/call; nothing can be compared against a zero")
        }
        assertEquals(19, rows.size, "every comparison basis must come from this run, not a remembered one")
        // The row above was already rooting an object per iteration before this assertion existed;
        // what it could not do was notice. A drift here means the figures above were timed against
        // a moving fixture and are not comparable with each other.
        assertEquals(
            handlesBefore, handlesAfter,
            "the timed loops leaked ${handlesAfter - handlesBefore} HandleTable roots, so every " +
                "row after the first was measured against a table that was still growing",
        )
        // Nothing became a no-op somewhere in 18 loops of 10 000.
        assertEquals("7", py("_pc_ping()"))
        assertEquals("counter-class", py("_pc_Counter.KIND"))
    }

    // ----------------------------------------------------------------------------- the await path

    @Test
    fun theAwaitFastPathIsPricedAgainstTheRawBoundaryAndAPurePythonCoroutine() =
        withProxies(needsAsyncio = true) {
        Python3.exec(HARNESS)
        Python3.exec(ASYNC_HARNESS)

        Python3.exec("_pc['warm'] = _pc_loop.run_until_complete(_pc_bench('proxy', $WARMUP))")
        Python3.exec("_pc['warm'] = _pc_loop.run_until_complete(_pc_bench('py', $WARMUP))")
        Python3.exec("_pc['warm'] = _pc_loop.run_until_complete(_pc_bench('raw', $WARMUP))")
        // The counter is armed *after* warmup, so the assertion below is about the timed loop.
        Python3.exec("_pc['futures'] = 0")

        // Minimum of three loops per row, for the reason `_pc_time` gives: the figure of interest
        // is a difference between two rows, and one-sided noise lands entirely in a difference.
        fun bench(kind: String): Double = (1..3).minOf {
            Python3.exec("_pc['t'] = _pc_loop.run_until_complete(_pc_bench('$kind', $N))")
            pyDouble("_pc['t']")
        }

        val rows = listOf(
            ASYNC_RAW to bench("raw"),
            ASYNC_PY to bench("py"),
            ASYNC_PROXY to bench("proxy"),
        )
        Python3.exec("_pc['value'] = _pc_loop.run_until_complete(_pc_once())")
        val futures = py("_pc['futures']")

        reportAsync(rows, futures)

        Python3.exec("_pc_loop.close()")

        assertEquals("42", py("_pc['value']"), "the awaited proxy does not reach Kotlin")
        for ((name, ns) in rows) {
            assertTrue(ns > 0.0, "$name measured ${ns}ns/call; nothing can be compared against a zero")
        }
        // The contamination guard this row needs: a slow-path figure would be a different
        // measurement wearing the same label, and the only way to tell from Python is to count the
        // Futures -- the generated proxy deliberately makes the two indistinguishable to its caller.
        assertEquals(
            "0", futures,
            "the fast path built a Future, so what was timed is not the fast path",
        )
        assertTrue(ProxyFragment.parked == null, "nothing was left outstanding")
    }

    // -------------------------------------------------------------------------- install() as cost

    @Test
    fun installingTheGeneratedModuleIsPricedAsTheStartupCostItIs() = withProxies {
        val source = PythonProxySource.render(UpcallTable.entries(), ClassLookup.all())
        val lines = source.count { it == '\n' } + 1

        val renderNs = Benchmark.measure(INSTALL_WARMUP, INSTALL_N) {
            PythonProxySource.render(UpcallTable.entries(), ClassLookup.all())
        }
        // `install` is documented as safe to run more than once -- every statement it emits is an
        // assignment, a `def` or a `class` -- which is what makes it measurable in a loop at all.
        val installNs = Benchmark.measure(INSTALL_WARMUP, INSTALL_N) {
            PythonProxySource.install()
        }

        reportInstall(lines, source.length, renderNs, installNs)

        assertTrue(renderNs > 0.0, "render() measured ${renderNs}ns; the clock has no resolution here")
        assertTrue(installNs > 0.0, "install() measured ${installNs}ns; the clock has no resolution here")
        assertTrue(lines > 0, "render() produced no source at all")
        // No relative-duration assertion here, deliberately, even though `install` strictly contains
        // `render`: the policy this file follows is that nothing asserts a duration, and "cheaper
        // than" is a duration comparison. The printed rows carry the claim instead.

        // The last of the INSTALL_N reinstalls left a working module behind, so what was timed was
        // a real install and not a run that failed early.
        Python3.exec("from demo.calc import ping as _pc_after\n_pc_installed = _pc_after()")
        assertEquals("7", py("_pc_installed"))
    }

    // --------------------------------------------------------------------------------- harnesses

    /**
     * Publishes this target's raw entry points, installs the proxies and runs [block].
     *
     * On a target [publishesProxyEntryPoints] says has no proxy bootstrap -- wasmJs, and only
     * wasmJs -- [block] is not run and the *documented refusal* is asserted instead. That is not a
     * skip and it is not a measurement of zero: there is nothing on that target to measure, and
     * saying so is the honest row. If such a target ever grows a shim, or one that has one loses it,
     * one of the two branches fails.
     *
     * @param needsAsyncio for the await row. On a target where [proxyBootstrapSupportsAsyncio] is
     *   false the import does not raise, it **traps**, so there is no refusal to assert and not
     *   running it is the only available answer.
     */
    private inline fun withProxies(needsAsyncio: Boolean = false, block: () -> Unit) =
        PythonTestFixture.withInterpreter {
            assertTrue(bindUpcallOrNull("demo.calc.ping"), "the fixture table is not installed")

            if (!publishesProxyEntryPoints) {
                val refusal = assertFails { PythonProxySource.install() }
                assertTrue(
                    refusal.message?.contains("raw upcall entry points are not bound") == true,
                    "a target with no proxy bootstrap must fail the generated module's own guard, " +
                        "not something else: $refusal",
                )
                println(
                    "\n--- Generated proxy cost: ${currentPlatform.name} --- " +
                        "no proxies are installable on this target, so there is nothing to measure; " +
                        "see docs/design/upcall-async-design.md 12.4\n",
                )
                return@withInterpreter
            }

            PythonProxySource.install()
            if (needsAsyncio && !proxyBootstrapSupportsAsyncio) {
                println(
                    "\n--- Generated proxy cost, await fast path: ${currentPlatform.name} --- " +
                        "`import asyncio` traps this instance, so there is no await path to price; " +
                        "see proxyBootstrapSupportsAsyncio\n",
                )
                return@withInterpreter
            }
            block()
        }

    /** Times each named zero-arg Python callable with one shared loop, warming every one first. */
    private fun timeAll(vararg rows: Pair<String, String>): List<Pair<String, Double>> {
        // Every row is warmed before any row is timed, so a row is never measured on a colder
        // interpreter than the row it will be compared with.
        for ((_, fn) in rows) Python3.exec("_pc_time($fn, $WARMUP)")
        return rows.map { (name, fn) ->
            Python3.exec("_pc['t'] = _pc_time($fn, $N)")
            name to pyDouble("_pc['t']")
        }
    }

    private fun reportSync(rows: List<Pair<String, Double>>, handlesBefore: Int, handlesAfter: Int) {
        val by = rows.toMap()
        val floor = by[NOOP] ?: 0.0
        fun net(name: String): Double = (by[name] ?: 0.0) - floor
        fun over(proxy: String, raw: String): String {
            val p = net(proxy)
            val r = net(raw).coerceAtLeast(1.0)
            return "${fmt(p / r)}x  (+${(p - net(raw)).ns()})"
        }

        val lines = buildList {
            add("")
            add("--- Generated proxy cost: ${currentPlatform.name} ---")
            add("iterations per row: $N x 3 (best taken), warmup: $WARMUP; every row is one zero-arg Python call")
            add("")
            add("Raw figures (timed inside Python, one shared loop)")
            for ((name, ns) in rows) add("  ${name.padEnd(64)}${ns.ns()}")
            add("")
            add("Net of the harness floor (${floor.ns()}), and what the proxy layer adds")
            add("  module function   raw ${net(RAW_FUNCTION).ns().padEnd(14)} proxy ${net(PROXY_FUNCTION).ns().padEnd(14)} ${over(PROXY_FUNCTION, RAW_FUNCTION)}")
            add("  constructor       raw ${net(RAW_CTOR).ns().padEnd(14)} proxy ${net(PROXY_CTOR).ns().padEnd(14)} ${over(PROXY_CTOR, RAW_CTOR)}")
            add("  instance method   raw ${net(RAW_METHOD).ns().padEnd(14)} proxy ${net(PROXY_METHOD).ns().padEnd(14)} ${over(PROXY_METHOD, RAW_METHOD)}")
            add("  property read     raw ${net(RAW_GETTER).ns().padEnd(14)} proxy ${net(PROXY_GETTER).ns().padEnd(14)} ${over(PROXY_GETTER, RAW_GETTER)}")
            add("  property write    raw ${net(RAW_SETTER).ns().padEnd(14)} proxy ${net(PROXY_SETTER).ns().padEnd(14)} ${over(PROXY_SETTER, RAW_SETTER)}")
            add("  static read       raw ${net(RAW_STATIC).ns().padEnd(14)} proxy ${net(PROXY_STATIC_GET).ns().padEnd(14)} ${over(PROXY_STATIC_GET, RAW_STATIC)}")
            add("  static write      raw ${net(RAW_STATIC_SET).ns().padEnd(14)} proxy ${net(PROXY_STATIC_SET).ns().padEnd(14)} ${over(PROXY_STATIC_SET, RAW_STATIC_SET)}")
            add("  module read       raw ${net(RAW_STATIC).ns().padEnd(14)} proxy ${net(PROXY_MODULE_GET).ns().padEnd(14)} ${over(PROXY_MODULE_GET, RAW_STATIC)}")
            add("  module write      raw ${net(RAW_STATIC_SET).ns().padEnd(14)} proxy ${net(PROXY_MODULE_SET).ns().padEnd(14)} ${over(PROXY_MODULE_SET, RAW_STATIC_SET)}")
            add("")
            add("Pure-Python floors, net of the harness (no boundary anywhere in these)")
            add("  plain attribute read                    ${net(PY_ATTR).ns()}")
            add("  @property read                          ${net(PY_PROPERTY).ns()}")
            add("  the descriptor itself costs             ${(net(PY_PROPERTY) - net(PY_ATTR)).ns()}")
            add("")
            // RAW_FUNCTION and RAW_STATIC are the *same call* -- `_pm_invoke(h, ())`, arity zero,
            // differing only in which handle -- measured in two different rows. Whatever they
            // differ by is what this report cannot resolve, and a proxy delta smaller than it is
            // not a number. Printed rather than assumed, because it is an order of magnitude apart
            // between targets: single-digit ns on desktop, around a hundred on the simulator.
            add("Resolution: two identical raw rows differ by ${(net(RAW_STATIC) - net(RAW_FUNCTION)).ns()}")
            add("  -- a proxy delta below that is not separable from the boundary's own variation")
            add("")
            // The constructor pair is the one row whose *fixture* could move under it: both halves
            // root a Kotlin object in `HandleTable` per call, and until both halves also released
            // one, 30 000 iterations of them left 78 002 entries behind -- so every row here was
            // timed against a table that grew by tens of thousands of entries while it ran, and
            // desktop's constructor delta swung either side of zero between runs while no other
            // row's did. The proxy half now releases in the `__del__` `PythonProxySource` renders;
            // the raw half releases explicitly, because a bare handle integer is the caller's.
            // Both counts are printed because only the pair distinguishes "released everything it
            // took" from "took nothing to begin with".
            add("HandleTable roots before the timed loops: $handlesBefore, after: $handlesAfter")
            add("-".repeat(78))
            add("")
        }
        for (line in lines) println(line)
    }

    private fun reportAsync(rows: List<Pair<String, Double>>, futures: String) {
        val by = rows.toMap()
        val raw = (by[ASYNC_RAW] ?: 1.0).coerceAtLeast(1.0)
        val lines = buildList {
            add("")
            add("--- Generated proxy cost, await fast path: ${currentPlatform.name} ---")
            add("iterations per row: $N x 3 (best taken), warmup: $WARMUP; one loop turn inside one coroutine")
            add("")
            for ((name, ns) in rows) add("  ${name.padEnd(56)}${ns.ns()}")
            add("")
            add("  await proxy / raw boundary in the same loop            ${fmt((by[ASYNC_PROXY] ?: 0.0) / raw)}x")
            add("  what the async def wrapper adds                        ${((by[ASYNC_PROXY] ?: 0.0) - (by[ASYNC_RAW] ?: 0.0)).ns()}")
            add("  asyncio.Futures constructed during the timed loops     $futures")
            add("-".repeat(78))
            add("")
        }
        for (line in lines) println(line)
    }

    private fun reportInstall(lines: Int, chars: Int, renderNs: Double, installNs: Double) {
        val out = buildList {
            add("")
            add("--- Generated proxy install cost: ${currentPlatform.name} ---")
            add("iterations: $INSTALL_N, warmup: $INSTALL_WARMUP")
            add("  generated source                        $lines lines, $chars chars")
            add("  ${INSTALL_RENDER.padEnd(40)}${(renderNs / 1000.0).us()}")
            add("  ${INSTALL_FULL.padEnd(40)}${(installNs / 1000.0).us()}")
            add("  ...of which Python3.exec                 ${((installNs - renderNs) / 1000.0).us()}")
            add("-".repeat(78))
            add("")
        }
        for (line in out) println(line)
    }

    /** Sign handled separately: a net figure can legitimately come out negative on a noisy host. */
    private fun Double.ns(): String = "${if (this < 0) "-" else ""}${fmt(kotlin.math.abs(this))} ns"

    private fun Double.us(): String = "${if (this < 0) "-" else ""}${fmt(kotlin.math.abs(this))} us"

    private fun fmt(v: Double): String {
        val whole = v.toLong()
        val hundredths = ((v - whole) * 100).toLong()
        return "$whole.${hundredths.toString().padStart(2, '0')}"
    }
}

/**
 * The shared timing loop and the two pure-Python floors.
 *
 * `time.perf_counter_ns` rather than anything Kotlin-side: the whole point is to time the Python
 * expression a user writes, from inside Python, with no boundary crossing added by the measurement
 * itself. This is the same instrument `UpcallBoundaryCostTest` uses for its Python-driven rows.
 */
private val HARNESS = """
    import time

    _pc = {}


    def _pc_once_round(fn, n):
        t0 = time.perf_counter_ns()
        for _ in range(n):
            fn()
        return (time.perf_counter_ns() - t0) / n


    def _pc_time(fn, n, reps=3):
        # The **minimum** of `reps` timed loops, not the mean, and this is the one place this file
        # departs from `UpcallBoundaryCostTest`'s loop. It departs for a reason that is specific to
        # what is being measured here: the quantity of interest is a *difference* between two rows
        # (proxy minus raw), and on desktop it is tens of nanoseconds sitting on top of a boundary
        # of five hundred. Wall-clock noise from a GC pause or a descheduled thread is one-sided --
        # it can only make a loop slower, never faster -- so with a mean it lands entirely in that
        # difference and swamps it, while with a minimum it does not. Measured: with a single loop
        # per row, four consecutive runs of this file put the instance-method delta at -17, -31,
        # -60 and -71 ns, i.e. the proxy came out *cheaper than the boundary it wraps* every time,
        # which cannot be true.
        #
        # Nothing else changes: same warmup, same shape, same everything-in-one-run rule.
        best = None
        for _ in range(reps):
            r = _pc_once_round(fn, n)
            if best is None or r < best:
                best = r
        return best


    def _pc_noop():
        pass


    class _PcPlain:
        # The Python-only floor for the descriptor rows: what CPython charges for a plain
        # attribute and for a `property` over a constant, with nothing crossing anywhere. The
        # proxy's excess over its raw boundary should land near the difference between these two
        # if the generated shape is not doing something unusual.

        def __init__(self):
            self.x = 1

        @property
        def p(self):
            return 1


    _pc_plain = _PcPlain()


    def _pc_py_attr():
        return _pc_plain.x


    def _pc_py_prop():
        return _pc_plain.p
""".trimIndent()

/**
 * One zero-arg function per measured surface, and the raw `_pm_invoke` call each proxy row wraps.
 *
 * The handles come from `_pm_lookup`, which is what the generated module itself uses, so the raw
 * rows call through exactly the object the proxies call through -- on desktop a `ctypes.CFUNCTYPE`,
 * elsewhere a `PyCFunction`. Pricing a proxy against a differently-obtained callable would fold the
 * difference between the two bootstraps into the answer.
 *
 * `_pc_self` is hoisted out of the raw rows on purpose: `c._pm_handle` is an instance attribute
 * lookup, and an instance attribute lookup is part of what the *proxy* row is being measured for.
 * Leaving it in both halves would cancel it out of the very number this test exists to produce.
 */
private val SYNC_BODIES = """
    from demo.calc import ping as _pc_ping
    from proxycls import Counter as _pc_Counter
    import demo.calc as _pc_calc

    _pc_h_fn = _pm_lookup('demo.calc.ping')
    _pc_h_ctor = _pm_lookup('proxycls.Counter.<init>')
    _pc_h_method = _pm_lookup('proxycls.Counter.increment')
    _pc_h_getter = _pm_lookup('proxycls.Counter.value')
    _pc_h_setter = _pm_lookup('proxycls.Counter.label=')
    _pc_h_static = _pm_lookup('proxycls.Counter.created')
    _pc_h_static_set = _pm_lookup('proxycls.Counter.created=')

    _pc_c = _pc_Counter(1)
    _pc_self = _pc_c._pm_handle


    def _pc_raw_fn():
        return _pm_invoke(_pc_h_fn, ())


    def _pc_pxy_fn():
        return _pc_ping()


    def _pc_raw_ctor():
        # The release is *inside* the row, and it is the only row that has one. A CONSTRUCTOR entry
        # answers with a bare HandleTable integer, and an integer has nothing to hang a `__del__`
        # off, so the caller owns that root and nothing else can give it back
        # (`ProxyHandleLifetimeTest.testRawHandleIsTheCallersToRelease`). Leaving it out is what
        # made this row leak one handle per iteration -- 78 002 by the end of the file -- and
        # measure itself against a table that grew by tens of thousands of entries while it ran.
        #
        # It also makes the pair comparable for the first time: the proxy row's `Counter(10)` now
        # builds a Kotlin object *and* releases it, in `__del__`, when the discarded proxy dies
        # inside this same loop. Timing that against an invoke with no release would price the
        # release as if it were part of the proxy layer.
        return _pm_release(_pm_invoke(_pc_h_ctor, (10,)))


    def _pc_pxy_ctor():
        return _pc_Counter(10)


    def _pc_raw_method():
        return _pm_invoke(_pc_h_method, (_pc_self, 5))


    def _pc_pxy_method():
        return _pc_c.increment(5)


    def _pc_raw_getter():
        return _pm_invoke(_pc_h_getter, (_pc_self,))


    def _pc_pxy_getter():
        return _pc_c.value


    def _pc_raw_setter():
        return _pm_invoke(_pc_h_setter, (_pc_self, 'x'))


    def _pc_pxy_setter():
        _pc_c.label = 'x'


    def _pc_raw_static():
        return _pm_invoke(_pc_h_static, ())


    def _pc_raw_static_set():
        return _pm_invoke(_pc_h_static_set, (12,))


    def _pc_pxy_static_get():
        return _pc_Counter.created


    def _pc_pxy_static_set():
        _pc_Counter.created = 12


    def _pc_pxy_module_get():
        return _pc_calc.tally


    def _pc_pxy_module_set():
        _pc_calc.tally = 9
""".trimIndent()

/**
 * The await rows, and the `create_future` counter that proves which path was timed.
 *
 * All three rows are loop bodies inside **one coroutine shape**, so the coroutine frame and the
 * `run_until_complete` are paid once per row rather than once per iteration and cancel out of the
 * comparison. The branch is outside the loop, so no row pays for the dispatch.
 *
 * The counter is the contamination guard: `AsyncUpcall` returns the real value on the fast path and
 * an `asyncio.Future` on the slow one, and the generated `async def` deliberately makes the two
 * indistinguishable to its caller. Counting `create_future` is the only way left to tell, and it is
 * a stronger statement than checking the returned type -- it says no `Future` was built at all.
 */
private val ASYNC_HARNESS = """
    import asyncio
    import demo.calc as _pc_calc

    _pc_h_async = _pm_lookup('demo.calc.doubleNow')
    _pc_await = _pc_calc.doubleNow

    _pc_loop = asyncio.new_event_loop()
    _pc['futures'] = 0
    _pc_orig_create = _pc_loop.create_future


    def _pc_counted():
        _pc['futures'] += 1
        return _pc_orig_create()


    _pc_loop.create_future = _pc_counted


    async def _pc_py_coro(a0):
        return a0 * 2


    async def _pc_bench(kind, n):
        if kind == 'proxy':
            t0 = time.perf_counter_ns()
            for _ in range(n):
                await _pc_await(21)
            return (time.perf_counter_ns() - t0) / n
        if kind == 'py':
            t0 = time.perf_counter_ns()
            for _ in range(n):
                await _pc_py_coro(21)
            return (time.perf_counter_ns() - t0) / n
        t0 = time.perf_counter_ns()
        for _ in range(n):
            _pm_invoke(_pc_h_async, (21,))
        return (time.perf_counter_ns() - t0) / n


    async def _pc_once():
        return await _pc_await(21)
""".trimIndent()
