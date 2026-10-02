package python.multiplatform.ffi.upcall

import python.multiplatform.currentPlatform
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
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
 * Who gives a [HandleTable] root back, and when.
 *
 * [HandleTable]'s own class doc states the contract and then says nobody was keeping it: *"This
 * table leaks by construction. Every entry is a strong reference the Kotlin GC can see and Python
 * cannot; nothing here can tell that the Python proxy has died. The proxy's `tp_dealloc` calling
 * `release` is the entire lifetime contract."* [PythonProxySource] renders the proxy that was
 * supposed to do the calling, and until this test existed it rendered an `__init__` that took a
 * handle and no path at all that gave one back.
 *
 * **This was observed before it was fixed, not inferred.** `GeneratedProxyCostTest`'s constructor
 * row calls `Counter(10)` ten thousand times and throws every result away; the run ended with
 * **78 002 live handles** on desktop, i.e. every single object Python had finished with was still
 * rooted in Kotlin. The measurement noticed because its own fixture moved underneath it -- the two
 * constructor rows were each timed against a table that had grown by tens of thousands of entries
 * while they ran, and desktop's constructor delta swung either side of zero between runs.
 *
 * ### Both ends of the count, always
 *
 * Every assertion here checks `baseline + N` **and** `baseline`. Checking only the second would
 * pass just as well if the constructor had stopped registering anything at all, which is a
 * different bug wearing the same green tick -- `docs/design/object-lifetime.md` and this repository's
 * early-cancellation work both record that lesson already. The `baseline + N` assertion is what
 * says the root was really taken, and it is the reason a regression that silently stopped rooting
 * receivers would fail here rather than look like an improvement.
 *
 * ### Cycles
 *
 * A proxy that is part of a reference cycle is collected by CPython's cyclic collector rather than
 * by refcounting, and `__del__` is the historically unreliable hook for that case (uncollectable
 * before PEP 442, and silently overridable by a subclass at any time). [testProxyInACycle] is what
 * pins the choice of `weakref.finalize` instead: it builds a cycle deliberately, drops the only
 * external reference, runs `gc.collect()`, and requires the handles back.
 *
 * ### The raw boundary is a different contract, and is not tested here as if it were the same one
 *
 * `_pm_invoke(ctor_handle, ...)` hands back a bare integer. There is no Python object for a
 * finaliser to hang off, so the **caller** owns that handle and must pass it to `_pm_release`; that
 * is what [testRawHandleIsTheCallersToRelease] pins. `GeneratedProxyCostTest`'s raw constructor row
 * was not doing it, which is the harness half of the same 78 002.
 *
 * ### wasmJs
 *
 * This said "no proxies are installable there at all, so there is no proxy whose lifetime could be
 * asserted", and it is now the fifth target that runs every assertion below -- see
 * [publishesProxyEntryPoints]. That matters more here than the sync contracts do: `__del__` reaches
 * Kotlin through the published `_pm_release`, which on wasm is a `PyCFunction` over the *same*
 * exported pointer as `_pm_invoke` with a different op code in its `self`. A dispatcher that routed
 * a release to the invoke path would leak every handle silently, and this file is what says it does
 * not.
 */
class ProxyHandleLifetimeTest {

    private companion object {
        /**
         * Small on purpose. The claim is "every one of them came back", not "many of them did", and
         * a count that fits in one Python list comprehension keeps the failure message readable.
         */
        const val N = 64
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
        if (PythonTestFixture.available) Python3.exec("_hl = None\n_hl_c = None\n_hl_h = None")
    }

    // ------------------------------------------------------------------------------ the proxy

    @Test
    fun aProxyRootsItsKotlinObjectForExactlyAsLongAsPythonHoldsTheProxy() = withProxies {
        val baseline = settledBaseline()

        Python3.exec("_hl = [Counter(i) for i in range($N)]")
        assertEquals(
            baseline + N, HandleTable.liveCount,
            "$N proxies must root $N Kotlin objects; a lower count here means the receiver was " +
                "never registered, which would make the release assertion below meaningless",
        )

        // Alive, and still answering: a root that is dropped while Python can still reach the
        // proxy is the opposite failure and is just as silent.
        Python3.exec("import gc\ngc.collect()")
        assertEquals(
            baseline + N, HandleTable.liveCount,
            "a collection while every proxy is still reachable must not release anything",
        )
        assertEquals("$N", py("len(_hl)"))
        assertEquals("7", py("_hl[7].value"))

        Python3.exec("_hl = None\ngc.collect()")
        assertEquals(
            baseline, HandleTable.liveCount,
            "every proxy Python has finished with must give its handle back",
        )
    }

    @Test
    fun aProxyInsideAReferenceCycleStillGivesItsHandleBackWhenTheCyclicCollectorRuns() = withProxies {
        val baseline = settledBaseline()

        // `c.box -> box -> c` is a cycle, so the refcount of every proxy stays above zero when the
        // list is dropped and only the cyclic collector can reclaim them. This is the case a
        // `__del__` was historically not called for at all.
        Python3.exec(
            """
            import gc
            _hl = []
            for _hl_i in range($N):
                _hl_c = Counter(_hl_i)
                _hl_box = [_hl_c]
                _hl_c.box = _hl_box
                _hl.append(_hl_c)
            _hl_c = None
            _hl_box = None
            """.trimIndent(),
        )
        assertEquals(
            baseline + N, HandleTable.liveCount,
            "$N proxies in cycles must still root $N Kotlin objects",
        )

        Python3.exec("_hl = None\ngc.collect()")
        assertEquals(
            baseline, HandleTable.liveCount,
            "a proxy reclaimed by the cyclic collector must release its handle like any other",
        )
    }

    @Test
    fun aProxyWhoseHandleWasAlreadyReleasedByHandDoesNotFreeSomebodyElsesSlotWhenItDies() = withProxies {
        val baseline = settledBaseline()

        Python3.exec("import gc\n_hl_c = Counter(1)\n_hl_h = _hl_c._pm_handle")
        assertEquals(baseline + 1, HandleTable.liveCount, "the proxy must have rooted its object")

        // The double-release case the generational table exists for: something releases the handle
        // out from under a proxy that is still alive, then the proxy dies and releases it again.
        assertEquals("1", py("_pm_release(_hl_h)"), "the explicit release must have done the work")
        assertEquals(baseline, HandleTable.liveCount)

        // A fresh registration takes the slot the release just freed. If the dying proxy's second
        // release were not generational it would free *this* handle, and nothing would report it.
        Python3.exec("_hl = [Counter(9)]")
        assertEquals(baseline + 1, HandleTable.liveCount)

        Python3.exec("_hl_c = None\ngc.collect()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "the second release must be a no-op rather than freeing the slot's new owner",
        )
        assertEquals("9", py("_hl[0].value"), "the slot's new owner must still resolve")
    }

    // ------------------------------------------------------------------------- the raw boundary

    @Test
    fun testRawHandleIsTheCallersToRelease() = withProxies {
        val baseline = settledBaseline()

        // No Python object to hang a finaliser off: `_pm_invoke` on a CONSTRUCTOR entry answers
        // with the handle integer itself, so ownership is the caller's and nothing else can give
        // it back. This is the contract `GeneratedProxyCostTest`'s raw constructor row was not
        // keeping.
        Python3.exec("_hl_h = _pm_invoke(_pm_lookup('proxycls.Counter.<init>'), (3,))")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "a TypeTag.OBJECT result must root its Kotlin object",
        )

        Python3.exec("import gc\ngc.collect()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "nothing but the caller can release a bare handle, so a collection must not",
        )

        assertEquals("1", py("_pm_release(_hl_h)"))
        assertEquals(baseline, HandleTable.liveCount)
        assertEquals("0", py("_pm_release(_hl_h)"), "a second release must report that it did nothing")
    }

    // ---------------------------------------------------------------------------------- harness

    /**
     * [HandleTable.liveCount] with anything another test in this binary left behind already
     * collected.
     *
     * The interpreter is process-wide and `__main__` outlives a single test, so proxies from
     * earlier tests can still be sitting in unreferenced garbage. Collecting *before* the baseline
     * is read means a later `gc.collect()` cannot drop the count below it and turn somebody else's
     * leftovers into this test's evidence.
     */
    private fun settledBaseline(): Int {
        Python3.exec("import gc\n_hl = None\n_hl_c = None\ngc.collect()")
        return HandleTable.liveCount
    }

    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    private inline fun withProxies(block: () -> Unit) = PythonTestFixture.withInterpreter {
        assertTrue(bindUpcallOrNull("demo.calc.ping"), "the fixture table is not installed")

        if (!publishesProxyEntryPoints) {
            val refusal = assertFails { PythonProxySource.install() }
            assertTrue(
                refusal.message?.contains("raw upcall entry points are not bound") == true,
                "a target with no proxy bootstrap must fail the generated module's own guard, " +
                    "not something else: $refusal",
            )
            println(
                "\n--- Proxy handle lifetime: ${currentPlatform.name} --- " +
                    "no proxies are installable on this target, so no proxy lifetime exists to " +
                    "assert; see docs/design/upcall.md §3.7\n",
            )
            return@withInterpreter
        }

        PythonProxySource.install()
        Python3.exec("from proxycls import Counter")
        block()
    }
}
