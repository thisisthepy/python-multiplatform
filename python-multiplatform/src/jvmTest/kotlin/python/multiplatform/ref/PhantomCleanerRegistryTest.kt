package python.multiplatform.ref

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `PhantomReference` release path — ROADMAP §4's one uncovered corner.
 *
 * `java.lang.ref.Cleaner` arrived in **Android API 33**. Every device below that runs a different
 * mechanism, and §4 has said "Android below API 33 uses the `PhantomReference` path and is not
 * covered yet" for as long as the section has existed. It stayed uncovered for a structural reason
 * rather than an oversight: the code lived in `androidMain`, so the only way to run it was on a
 * device, and the emulators in this project are API 36 — which takes the `Cleaner` branch and never
 * touches the fallback at all. The fallback could have been arbitrarily broken and every test in
 * the repository would still have passed.
 *
 * [PhantomCleanerRegistry] therefore lives in `jvmMain`, where `desktopTest` can reach it. Nothing
 * in it is Android-specific — `java.lang.ref.PhantomReference` and `ReferenceQueue` are Java 1.2 —
 * so running it on a desktop JVM exercises the same class an API 26 device would.
 *
 * **It runs on both runtimes, and that immediately paid for itself.** This class sits in `jvmTest`,
 * which `desktopTest` and `androidInstrumentedTest` both depend on, so every case here executes on
 * HotSpot *and* on ART. The first six-target run after it landed was green on desktop and red on
 * both emulators — API 36 with two failures, API 26 with three — for two distinct reasons. One is
 * the test's own (`System.gc()` is not a collection on ART; see [awaitCount]) and one is real
 * (`Reference.reachabilityFence` is API 28+ and the fallback's whole audience is below API 33; see
 * [keptAlive]). Neither is visible from a desktop-only run, and the second is a genuine API-level
 * defect in a test written *for* old devices. That is the argument for not splitting this class per
 * runtime: the shared assertions are what caught it, and only the primitives underneath them are
 * allowed to differ.
 *
 * **Both failure directions are here on purpose.** A test that only checks "the release eventually
 * runs" passes a mechanism that runs it twice, and running a CPython decref twice frees an object
 * that is still in use; this repository has already paid for that once, as a segfault inside
 * `_PyObject_ClearFreeLists` in a test that had nothing to do with the code at fault. So every
 * case below pins an exact count, never a lower bound.
 */
class PhantomCleanerRegistryTest {

    /** Stands in for the wrapper whose collection is supposed to trigger a release. */
    private class Owner

    /**
     * Registers a release against an owner that is unreachable the moment this returns.
     *
     * The owner is deliberately created *and* dropped inside a separate frame: leaving it in the
     * test method's own frame lets a local slot keep it alive for the rest of the method on some
     * JVMs, and the test would then be waiting for a collection that is not allowed to happen.
     *
     * The action closes over [counter] and nothing else. An action that could reach its own owner
     * would keep that owner strongly reachable through the registry's entry table, and the release
     * would never run — which is the single way to write this class that disables it silently.
     */
    private fun registerAndDrop(counter: AtomicInteger, throwing: Boolean = false): WeakReference<Any> {
        val owner = Owner()
        PhantomCleanerRegistry.register(owner, Runnable {
            counter.incrementAndGet()
            if (throwing) throw RuntimeException("this release action fails on purpose")
        })
        // Weak, so it cannot itself keep the owner alive. It is what lets a failure here say which
        // of the two mechanisms broke; see [diagnose].
        return WeakReference<Any>(owner)
    }

    /**
     * What to print when a release did not run, and the only thing that separates the two causes.
     *
     * A missing release has two disjoint explanations and the counter alone cannot tell them apart:
     * the collector never reclaimed the owner, or it did and the registry failed to hand the
     * release back. The first is a fault in the test's own GC-forcing; the second is a fault in the
     * class under test, on the very platform this fallback exists for. Reporting the owners'
     * reachability and the drain thread's state turns that from a guess into a reading.
     */
    private fun diagnose(vararg owners: WeakReference<Any>): String {
        val collected = owners.count { it.get() == null }
        return "[owners collected: $collected/${owners.size}; " +
            "drainThread alive: ${PhantomCleanerRegistry.draining}; " +
            "registry drained: ${PhantomCleanerRegistry.drained}, failed: ${PhantomCleanerRegistry.failed}, " +
            "outstanding: ${PhantomCleanerRegistry.outstanding}] " +
            if (collected == 0) {
                "No owner was collected at all, so the collector never gave the registry anything to " +
                    "do: this is the test's GC-forcing, not the registry."
            } else {
                "An owner WAS collected and its release still did not run: this is the registry."
            }
    }

    /** Registers, releases by hand, and drops the owner — all inside one frame, as above. */
    private fun registerCleanAndDrop(counter: AtomicInteger, cleanTimes: Int = 1) {
        val owner = Owner()
        val cleanable = PhantomCleanerRegistry.register(owner, Runnable { counter.incrementAndGet() })
        repeat(cleanTimes) { cleanable.clean() }
    }

    /**
     * Bounded wait for the collector plus the drain thread; both are asynchronous.
     *
     * **[forceGC], not `System.gc()`, and that is not a stylistic preference.** This class runs on
     * two runtimes -- `desktopTest` on HotSpot and `androidInstrumentedTest` on ART -- and
     * `System.gc()` does not mean the same thing on both. libcore's `System.gc()` does not collect
     * when you call it: it records a request and defers the collection to the next
     * `System.runFinalization()`. A loop of bare `System.gc()` calls therefore runs no collection at
     * all on ART, and the cases here that wait for one failed with `count: 0` on both emulators
     * while passing on desktop.
     *
     * **[diagnose] is what settled it, and the reading is reproducible.** Putting the old
     * `System.gc()` body back and running this class alone on API 36 reproduced it directly:
     * `owners collected: 0/2; drainThread alive: true; registry drained: 1, failed: 1` for
     * [twoDroppedOwnersRunTwoReleases], and `owners collected: 0/1` for the other two waiting cases.
     * Twenty seconds of `System.gc()` had reclaimed *nothing* — while the drain thread was alive the
     * whole time, and every entry the collector genuinely did hand over (`drained: 1`, plus the
     * throwing one at `failed: 1`) was delivered correctly. The registry was never the fault; the
     * waiting was.
     *
     * That also explains why the failure is not the same set of cases every run. ART still collects
     * on allocation pressure, so whether an incidental collection lands inside a given case's window
     * is luck: the same emulator produced two failures in the full suite and three when this class
     * ran alone. `System.gc()` contributes nothing either way.
     *
     * [forceGC] is `commonTest`'s `expect fun`, so each runtime supplies the escalation its own
     * collector needs (`Runtime.getRuntime().gc()` + `System.runFinalization()` on ART) and each
     * confirms with a weak canary that a collection actually happened before returning. The
     * assertions above it are then identical on both runtimes and mean the same thing on both,
     * which is the point of keeping this class in `jvmTest` rather than splitting it.
     */
    private fun awaitCount(counter: AtomicInteger, target: Int, rounds: Int = 50): Boolean {
        repeat(rounds) {
            if (counter.get() >= target) return true
            forceGC()
        }
        return counter.get() >= target
    }

    /**
     * Gives the collector every chance to run something it must not run.
     *
     * Fewer rounds than the `System.gc()` version this replaces, and strictly stronger: each round
     * is a collection [forceGC] verified with a canary, where the old 25 rounds were 25 requests
     * that ART was free to -- and did -- ignore entirely.
     */
    private fun insistOnCollection(rounds: Int = 10) {
        repeat(rounds) { forceGC() }
    }

    @Test
    fun aDroppedOwnerHasItsReleaseRunExactlyOnce() {
        val counter = AtomicInteger()
        val owner = registerAndDrop(counter)

        assertTrue(
            awaitCount(counter, 1),
            "the release for a dropped owner never ran (count: ${counter.get()}). On a device below " +
                "API 33 this is every reference the collector reclaims, leaked. ${diagnose(owner)}"
        )
        // The drain thread has already handed this entry back; nothing may hand it back again.
        insistOnCollection()
        assertEquals(
            1, counter.get(),
            "the release ran more than once. As a CPython decref that frees an object still in use."
        )
    }

    @Test
    fun twoDroppedOwnersRunTwoReleases() {
        // Positive control for the case above. `assertEquals(1, ...)` after an explicit clean()
        // proves nothing unless a second release would actually have been counted -- a counter
        // nobody increments passes that assertion trivially, and the "not covered yet" state this
        // test closes is exactly the state where such a mistake survives. Two owners, two releases.
        val counter = AtomicInteger()
        val first = registerAndDrop(counter)
        val second = registerAndDrop(counter)

        assertTrue(
            awaitCount(counter, 2),
            "two dropped owners should produce two releases, got ${counter.get()}. " +
                diagnose(first, second)
        )
        assertEquals(2, counter.get(), "and no more than two")
    }

    @Test
    fun anExplicitCleanIsNotRunAgainByTheCollector() {
        val counter = AtomicInteger()
        registerCleanAndDrop(counter)

        assertEquals(1, counter.get(), "clean() should release immediately, not eventually")

        insistOnCollection()
        assertEquals(
            1, counter.get(),
            "the collector released a reference that close() had already given back. This is the " +
                "double free of ROADMAP §1: two owners for one reference, CPython's free lists " +
                "corrupted, and the crash landing in an unrelated test."
        )
    }

    @Test
    fun cleanIsIdempotent() {
        val counter = AtomicInteger()
        registerCleanAndDrop(counter, cleanTimes = 3)

        assertEquals(
            1, counter.get(),
            "close() must be idempotent; `RefCountTest.closingTwiceReleasesOnlyOnce` asserts the " +
                "same thing one layer up, against a real refcount"
        )
    }

    @Test
    fun aReleaseThatThrowsDoesNotStopLaterReleases() {
        // The drain loop used to catch InterruptedException and nothing else. A release action that
        // threw therefore propagated out of the loop, out of the Runnable, and killed the thread --
        // after which every later release on the whole process silently leaked, with no exception
        // anywhere to say so. `java.lang.ref.Cleaner`'s own thread swallows Throwable for this
        // reason, so the Cleaner path (API 33+) never had the problem and the fallback did.
        val failing = AtomicInteger()
        val throwingOwner = registerAndDrop(failing, throwing = true)
        assertTrue(
            awaitCount(failing, 1),
            "the throwing release should still have been reached (count: ${failing.get()}). " +
                diagnose(throwingOwner)
        )

        val later = AtomicInteger()
        val laterOwner = registerAndDrop(later)
        assertTrue(
            awaitCount(later, 1),
            "a release that threw took the drain thread with it: the next release never ran " +
                "(count: ${later.get()}). Everything registered from here on leaks, on every " +
                "device below API 33. " + diagnose(laterOwner)
        )
        assertTrue(
            PhantomCleanerRegistry.draining,
            "the drain loop is no longer running, so nothing registered from here on will ever be " +
                "released"
        )
        assertTrue(
            PhantomCleanerRegistry.failed >= 1,
            "the failure should have been counted, not swallowed without trace " +
                "(failed: ${PhantomCleanerRegistry.failed})"
        )
    }

    @Test
    fun anOwnerHeldAliveIsNotReleased() {
        // The other half of the contract, and the one that a leak-only test cannot state: a live
        // wrapper must keep its reference. If the entry table held the owner weakly in the wrong
        // place -- or if `register` enqueued eagerly -- this would release a pointer still in use.
        val counter = AtomicInteger()
        val owner = Owner()
        PhantomCleanerRegistry.register(owner, Runnable { counter.incrementAndGet() })
        // Reachability is the premise of this case, so it is established by a real strong reference
        // rather than left to the optimiser -- see [keptAlive]. Without something here the JIT is
        // entitled to treat `owner` as dead from its last use (the `register` call above) and let it
        // be collected during `insistOnCollection()`, which would turn this case into one that
        // passes or fails on the optimiser's mood rather than on the registry's behaviour.
        keptAlive.set(owner)
        val stillLive = WeakReference<Any>(owner)

        insistOnCollection()

        assertEquals(
            0, counter.get(),
            "a reachable owner's reference was released while it was still alive"
        )
        // And the premise is checked, not assumed. `assertEquals(0, ...)` above is vacuously true if
        // the owner was never reachable in the first place -- a weak reference that is still intact
        // is the evidence that it was. This is strictly more than `reachabilityFence` gave: the
        // fence asks the optimiser for a guarantee, this observes whether the guarantee held.
        assertNotNull(
            stillLive.get(),
            "the owner was collected even though the test holds it, so the assertion above proved " +
                "nothing about a live owner"
        )
        assertNotNull(keptAlive.getAndSet(null), "the keep-alive slot was cleared by someone else")
    }

    /**
     * Holds an owner that a case needs to stay reachable, for as long as that case needs it.
     *
     * `java.lang.ref.Reference.reachabilityFence` is the idiomatic way to say this and is what
     * [anOwnerHeldAliveIsNotReleased] used to call. It is **Android API 28+**: on API 26 the call
     * resolved at runtime to `NoSuchMethodError: No static method
     * reachabilityFence(Ljava/lang/Object;)V in class Ljava/lang/ref/Reference;`, which failed the
     * case on the oldest platform in the matrix -- the one this whole fallback exists to serve.
     *
     * A field on the live test instance says the same thing in Java 1.0 vocabulary. The runner holds
     * the test instance for the duration of the method, so an owner stored here is strongly
     * reachable by a path the collector must honour, and the `getAndSet` that reads it back after
     * the assertion is a volatile side effect that nothing may reorder before them.
     */
    private val keptAlive = AtomicReference<Any?>(null)
}
