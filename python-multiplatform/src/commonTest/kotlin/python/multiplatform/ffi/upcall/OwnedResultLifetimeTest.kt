package python.multiplatform.ffi.upcall

import python.multiplatform.currentPlatform
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Who owns the [HandleTable] root behind a `TypeTag.OBJECT` **result**, when the entry that
 * produced it is a plain [CallableKind.FUNCTION].
 *
 * [ProxyHandleLifetimeTest] already pins the two contracts that existed: a *constructor* proxy owns
 * its handle and gives it back in `__del__`, and a handle taken over the *raw* boundary is the
 * caller's to release by hand. This file is about the third case, which had no owner at all.
 *
 * ### What was leaking, measured rather than argued
 *
 * `docs/design/kotlin-extensions-in-python.md` §3.2 assembles `Modifier.padding(16.dp).size(24.dp)` from
 * Python out of Compose's own jars. Every link of that chain is a `CallableKind.FUNCTION` returning
 * `TypeTag.OBJECT`, so [UpcallTrampoline.marshalResult] registered a root and handed Python a bare
 * integer -- and an integer has nothing to hang a finaliser off. §6's "Handle ownership" records the
 * consequence: *"A chained `Modifier.padding(...).size(...)` leaks one handle per intermediate link
 * until §4.1's proxy exists to own them."* `WalkedArtifactComposeModifierTest` says the same thing
 * about itself: three handles per run, deliberately.
 *
 * A chain is not a synthetic shape. Compose modifiers are written long, so the leak is proportional
 * to how idiomatic the calling code is.
 *
 * ### Why the count, and not "it did not crash"
 *
 * Every assertion here reads [HandleTable.liveCount] and checks **both ends**: `baseline + N` while
 * Python still holds the objects, and `baseline` once it has dropped them. Only the second is the
 * leak; checking only the second would pass just as well if the boundary had stopped rooting
 * anything at all, which is the opposite defect wearing the same green tick. That is the same rule
 * [ProxyHandleLifetimeTest] states and it is not restated here so much as inherited.
 *
 * ### The three things that could go wrong instead, and the test for each
 *
 * | | test |
 * |---|---|
 * | the root is never taken, so nothing is owned and `resolve` fails later | the `baseline + N` half of every assertion |
 * | the root is taken twice or given back twice -- a double release, which hands one slot to two owners | [releasingAnOwnedResultTwiceDoesNotFreeTheSlotsNewOwner] |
 * | an entry whose producer did **not** name its return type silently starts being owned | [anEntryWhoseProducerDidNotNameItsReturnKeepsTheBareHandleContract] |
 *
 * The last one is the reason the ownership is gated on [ExposedCallable.returnTypeName] rather than
 * on [TypeTag.OBJECT] alone. `OBJECT` is two things at once in the *result* direction, exactly as it
 * is in the argument direction: [UpcallTrampoline] answers with a `HandleTable` integer for a Kotlin
 * object and with the Python object itself for a `PyObject`, and Python cannot tell those apart when
 * the second one happens to be an `int`. Wrapping such a value would make `__del__` release a handle
 * nobody issued. The walker supplies a return type name (`WalkedArtifactComposeModifierTest`
 * asserts `androidx.compose.ui.Modifier`); the KSP processor does not supply one at all, so every
 * KSP entry keeps the bare-handle contract it has today and nothing about that path moves.
 */
class OwnedResultLifetimeTest {

    private companion object {
        /** Small enough that a failure message is readable; the claim is "every one", not "many". */
        const val N = 32

        /** Calls per timed row in [theCostOfOwningAndUnwrappingAResultIsReported]. */
        const val COST_N = 2_000
        const val COST_WARMUP = 1_000
    }

    @BeforeTest
    fun setUp() {
        UpcallTable.install(listOf(ChainFragment))
    }

    @AfterTest
    fun tearDown() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        if (PythonTestFixture.available) Python3.exec("_oc = None\n_oc_a = None\n_oc_b = None")
    }

    // --------------------------------------------------------------------------------- the leak

    @Test
    fun everyLinkOfAChainGivesItsHandleBackWhenPythonDropsTheChain() = withChain {
        val baseline = settledBaseline()

        // The shape §3.2 built out of Compose: each call takes the previous link and returns a new
        // one. Three objects, held all at once.
        Python3.exec("_oc = [seed()]\nfor _oc_i in range(2):\n    _oc.append(link(_oc[-1]))")
        assertEquals(
            baseline + 3, HandleTable.liveCount,
            "three links must root three Kotlin objects; a lower count means the result was never " +
                "registered, which would make the release assertion below meaningless",
        )
        // The chain really was assembled in Kotlin, and it really is the object Python passed in
        // that each call extended -- otherwise the count above could be three unrelated seeds.
        assertEquals("2", py("depth(_oc[-1])"), "the chain did not reach Kotlin")

        Python3.exec("import gc\n_oc = None\ngc.collect()")
        assertEquals(
            baseline, HandleTable.liveCount,
            "every link Python has finished with must give its handle back",
        )
    }

    @Test
    fun theIntermediateLinksOfAChainAreReleasedAsSoonAsNothingPointsAtThem() = withChain {
        val baseline = settledBaseline()

        // `size(padding(m, 16.0), 24.0)` written as one expression: the seed and the first link are
        // unreferenced the moment the outer call returns. This is the exact statement of the leak
        // -- one handle per *intermediate* link -- and the reason it grows with chain length.
        Python3.exec("import gc\n_oc = link(link(seed()))\ngc.collect()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "only the tail of the chain is still reachable from Python, so only its handle may " +
                "still be rooted",
        )
        assertEquals("2", py("depth(_oc)"), "the tail must still resolve after its predecessors died")

        Python3.exec("_oc = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount)
    }

    @Test
    fun anOwnedResultRootsItsObjectForExactlyAsLongAsPythonHoldsIt() = withChain {
        val baseline = settledBaseline()

        Python3.exec("_oc = [seed() for _oc_i in range($N)]")
        assertEquals(baseline + N, HandleTable.liveCount, "$N results must root $N Kotlin objects")

        // A collection while everything is still reachable must not release anything: a root
        // dropped early is the opposite failure and is just as silent.
        Python3.exec("import gc\ngc.collect()")
        assertEquals(baseline + N, HandleTable.liveCount)
        assertEquals("$N", py("len(_oc)"))
        assertEquals("0", py("depth(_oc[0])"), "a result that is still held must still resolve")

        Python3.exec("_oc = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount)
    }

    @Test
    fun anOwnedResultInsideAReferenceCycleStillComesBackWhenTheCyclicCollectorRuns() = withChain {
        val baseline = settledBaseline()

        // The owner has `__slots__` and no `__dict__`, so it cannot hold the cycle itself; the cycle
        // is built around it out of two lists. That is the honest version of this case -- a proxy
        // reachable only through a cycle is collected by the cyclic collector rather than by
        // refcounting, and `__del__` is the hook that was historically not run for it at all
        // (uncollectable before PEP 442).
        Python3.exec(
            """
            import gc
            _oc = []
            for _oc_i in range($N):
                _oc_a = [seed()]
                _oc_b = [_oc_a]
                _oc_a.append(_oc_b)
                _oc.append(_oc_a)
            _oc_a = None
            _oc_b = None
            """.trimIndent(),
        )
        assertEquals(baseline + N, HandleTable.liveCount, "$N results in cycles must root $N objects")

        Python3.exec("_oc = None\ngc.collect()")
        assertEquals(
            baseline, HandleTable.liveCount,
            "a result reclaimed by the cyclic collector must release its handle like any other",
        )
    }

    // ------------------------------------------------------------------------- the double release

    @Test
    fun releasingAnOwnedResultTwiceDoesNotFreeTheSlotsNewOwner() = withChain {
        val baseline = settledBaseline()

        // `__del__` is an ordinary method and Python lets anything call it, which is the cheapest
        // way to make the double release deterministic rather than dependent on a collector.
        Python3.exec("import gc\n_oc = seed()")
        assertEquals(baseline + 1, HandleTable.liveCount, "the result must have rooted its object")

        Python3.exec("_oc.__del__()")
        assertEquals(baseline, HandleTable.liveCount, "the explicit release must have done the work")

        // A fresh registration takes the slot that release just freed. If the second release were
        // not guarded it would free *this* object, and nothing anywhere would report it -- the
        // failure `agent-rules.md` §14 describes, one level up from the reference counts.
        Python3.exec("_oc_b = seed()")
        assertEquals(baseline + 1, HandleTable.liveCount)

        Python3.exec("_oc.__del__()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "the second release must be a no-op rather than freeing the slot's new owner",
        )
        Python3.exec("_oc = None\ngc.collect()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "and the finaliser that runs later must not free it either",
        )
        assertEquals("0", py("depth(_oc_b)"), "the slot's new owner must still resolve")

        Python3.exec("_oc_b = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount)
    }

    @Test
    fun aReleasedResultRefusesToCrossTheBoundaryAgainRatherThanResolvingToSomebodyElse() = withChain {
        val baseline = settledBaseline()

        Python3.exec("import gc\n_oc = seed()\n_oc.__del__()")
        assertEquals(baseline, HandleTable.liveCount)

        // Passing it on would send a stale handle, which the table's generation tag would reject
        // anyway -- but only after the slot's *current* generation had been consulted. Refusing in
        // Python says which object the caller got wrong.
        val failure = assertFails { Python3.exec("_oc_b = link(_oc)") }
        assertTrue(
            failure.message?.contains("already released") == true,
            "a released owner must name itself in the failure: $failure",
        )
        assertEquals(baseline, HandleTable.liveCount, "the refused call must not have rooted anything")
    }

    // -------------------------------------------------------------- the contract that did not move

    @Test
    fun anEntryWhoseProducerDidNotNameItsReturnKeepsTheBareHandleContract() = withChain {
        val baseline = settledBaseline()

        // Every KSP-generated entry is this shape: `TypeTag.OBJECT` and no `returnTypeName`, because
        // the processor does not emit one. The tag alone cannot say whether the integer is a
        // `HandleTable` handle or a Python `int` the Kotlin side returned as a `PyObject`, and
        // owning the second would release a handle nobody issued. So this stays exactly as it was:
        // a bare integer that the caller owns.
        Python3.exec("import gc\n_oc = unnamed()")
        assertEquals("int", py("type(_oc).__name__"), "an unnamed return must not be wrapped")
        assertEquals(baseline + 1, HandleTable.liveCount)

        Python3.exec("gc.collect()")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "nothing but the caller can release a bare handle, so a collection must not",
        )

        assertEquals("1", py("_pm_release(_oc)"), "the caller's release is the only route back")
        assertEquals(baseline, HandleTable.liveCount)
    }

    @Test
    fun anOwnedResultStillCrossesAsItsHandleWhenItIsPassedBackAsAnArgument() = withChain {
        val baseline = settledBaseline()

        // The chain only works if an owned result unwraps back to the handle on the way in. Passing
        // the *raw* handle has to keep working too: that is what every caller written against the
        // bare-handle contract does today, including `WalkedArtifactComposeModifierTest`.
        Python3.exec("import gc\n_oc = seed()\n_oc_b = link(_oc._pm_handle)")
        assertEquals("1", py("depth(_oc_b)"), "a bare handle must still be accepted as an argument")
        assertEquals(baseline + 2, HandleTable.liveCount)

        Python3.exec("_oc = None\n_oc_b = None\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount)
    }

    // ------------------------------------------------------------------------------- what it costs

    @Test
    fun theCostOfOwningAndUnwrappingAResultIsReported() = withChain {
        Python3.exec(COST_HARNESS)

        val handlesBefore = HandleTable.liveCount
        val rows = listOf(
            "empty Python function (the harness floor)" to time("_oc_noop"),
            "_pm_release(_pm_invoke(h, ()))            raw boundary + release" to time("_oc_raw_seed"),
            "seed()                                    owned result" to time("_oc_own_seed"),
            "_pm_release(_pm_invoke(h, (raw,)))        raw boundary + release" to time("_oc_raw_link"),
            "link(x)                                   owned result, owned argument" to time("_oc_own_link"),
        )
        // `_oc_x` is deliberately *not* dropped: it is the receiver the two `link` rows pass, it was
        // built before `handlesBefore` was read, and dropping it here would make the pair differ by
        // one for a reason that has nothing to do with the rows.
        Python3.exec("import gc\ngc.collect()")
        val handlesAfter = HandleTable.liveCount

        val floor = rows[0].second
        val lines = buildList {
            add("")
            add("--- Owned object result cost: ${currentPlatform.name} ---")
            add("iterations per row: $COST_N x 3 (best taken), warmup: $COST_WARMUP")
            add("")
            for ((name, ns) in rows) add("  ${name.padEnd(62)}${fmt(ns)} ns")
            add("")
            add("Net of the harness floor (${fmt(floor)} ns)")
            add("  arity 0, no argument to unwrap        raw ${fmt(rows[1].second - floor)} -> owned ${fmt(rows[2].second - floor)}")
            add("    what the owner costs                ${fmt(rows[2].second - rows[1].second)} ns")
            add("  arity 1, one OBJECT argument          raw ${fmt(rows[3].second - floor)} -> owned ${fmt(rows[4].second - floor)}")
            add("    what the owner plus the unwrap cost ${fmt(rows[4].second - rows[3].second)} ns")
            add("")
            add("HandleTable roots before the timed loops: $handlesBefore, after: $handlesAfter")
            add("-".repeat(78))
            add("")
        }
        for (line in lines) println(line)

        for ((name, ns) in rows) {
            assertTrue(ns > 0.0, "$name measured ${ns}ns/call; nothing can be compared against a zero")
        }
        // No duration is asserted -- this repository's measurement policy, and a wall-clock
        // threshold on a shared machine is a flake generator. What *is* asserted is that the timed
        // loops did not move the fixture underneath themselves: 4 x COST_N results were built and
        // every one of them had to come back, the owned rows through `__del__` and the raw rows
        // through the explicit release inside the row.
        assertEquals(
            handlesBefore, handlesAfter,
            "the timed loops leaked ${handlesAfter - handlesBefore} HandleTable roots, so every row " +
                "after the first was measured against a table that was still growing",
        )
    }

    // ---------------------------------------------------------------------------------- harness

    /** [HandleTable.liveCount] with anything an earlier test left as garbage already collected. */
    private fun settledBaseline(): Int {
        Python3.exec("import gc\n_oc = None\n_oc_a = None\n_oc_b = None\ngc.collect()")
        return HandleTable.liveCount
    }

    private fun py(expression: String): String = PythonTestFixture.eval(expression).toString()

    private fun time(function: String): Double {
        Python3.exec("_oc_t = _oc_time($function, $COST_N, $COST_WARMUP)")
        return py("_oc_t").toDouble()
    }

    private fun fmt(v: Double): String {
        val whole = v.toLong()
        val hundredths = ((if (v < 0) -(v - whole) else v - whole) * 100).toLong()
        return "$whole.${hundredths.toString().padStart(2, '0')}"
    }

    private inline fun withChain(block: () -> Unit) = PythonTestFixture.withInterpreter {
        assertTrue(bindUpcallOrNull("chain.seed"), "the fixture table is not installed")

        if (!publishesProxyEntryPoints) {
            val refusal = assertFails { PythonProxySource.install() }
            assertTrue(
                refusal.message?.contains("raw upcall entry points are not bound") == true,
                "a target with no proxy bootstrap must fail the generated module's own guard, " +
                    "not something else: $refusal",
            )
            println(
                "\n--- Owned object results: ${currentPlatform.name} --- " +
                    "no proxies are installable on this target, so nothing owns a result here; " +
                    "see docs/design/upcall.md §3.7\n",
            )
            return@withInterpreter
        }

        PythonProxySource.install()
        Python3.exec("from chain import seed, link, depth, unnamed")
        block()
    }
}

/**
 * The timing loop, and the four rows.
 *
 * The same instrument [GeneratedProxyCostTest] uses and for the same reason: the quantity is a
 * Python expression a user writes, so it is timed from inside Python with no boundary crossing added
 * by the measurement. The minimum of three loops rather than the mean, because the figure of
 * interest is a *difference* of tens of nanoseconds sitting on a boundary of several hundred, and
 * wall-clock noise is one-sided.
 *
 * Each raw row releases inside the row. Without that it would be timed against a growing table and
 * would also be pricing "build an object" against "build an object and reclaim it", which is not the
 * same work.
 */
private val COST_HARNESS = """
    import time

    _oc_h_seed = _pm_lookup('chain.seed')
    _oc_h_link = _pm_lookup('chain.link')
    _oc_x = seed()
    _oc_x_raw = _oc_x._pm_handle


    def _oc_once(fn, n):
        t0 = time.perf_counter_ns()
        for _ in range(n):
            fn()
        return (time.perf_counter_ns() - t0) / n


    def _oc_time(fn, n, warmup, reps=3):
        _oc_once(fn, warmup)
        best = None
        for _ in range(reps):
            r = _oc_once(fn, n)
            if best is None or r < best:
                best = r
        return best


    def _oc_noop():
        pass


    def _oc_raw_seed():
        return _pm_release(_pm_invoke(_oc_h_seed, ()))


    def _oc_own_seed():
        return seed()


    def _oc_raw_link():
        return _pm_release(_pm_invoke(_oc_h_link, (_oc_x_raw,)))


    def _oc_own_link():
        return link(_oc_x)
""".trimIndent()

/** One link of a chain: what a `Modifier` is to this test, with a depth instead of an element list. */
class ChainLink(val depth: Long)

/**
 * The artefact walker's shape, hand-written.
 *
 * Every entry is a [CallableKind.FUNCTION] whose result is a [TypeTag.OBJECT] -- which is what
 * `androidx.compose.foundation.layout.padding__Dp` is after `ArtifactScanner` has bound it -- and
 * `link` additionally takes one, so a call can be nested inside a call. `chain.unnamed` is the KSP
 * shape beside it: the same tag with no [ExposedCallable.returnTypeName], which is the one signal
 * that tells a Kotlin object handle apart from a `PyObject` that happens to be an `int`.
 */
object ChainFragment : FunctionTableFragment {

    /** The declared Kotlin type of a link. Any name will do; it has to be *a* name. */
    private const val LINK = "chain.ChainLink"

    override val moduleName: String = "test_owned_chain"

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "chain.seed",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
            returnTypeName = LINK,
        ) { ChainLink(0) },
        ExposedCallable(
            name = "chain.link",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.OBJECT,
            paramNames = listOf("<receiver>"),
            paramTypeNames = listOf(LINK),
            returnTypeName = LINK,
            isExtension = true,
            receiverTypeName = LINK,
        ) { args -> ChainLink((args[0] as ChainLink).depth + 1) },
        ExposedCallable(
            name = "chain.depth",
            arity = 1,
            paramTypes = listOf(TypeTag.OBJECT),
            returnType = TypeTag.INT,
            paramTypeNames = listOf(LINK),
        ) { args -> (args[0] as ChainLink).depth },
        ExposedCallable(
            name = "chain.unnamed",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.OBJECT,
        ) { ChainLink(0) },
    )
}
