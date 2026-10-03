package python.multiplatform.ref

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The release path for JVMs without `java.lang.ref.Cleaner`.
 *
 * `Cleaner` is Java 9, and on Android it arrives with **API 33** (Tiramisu). Every device below
 * that needs the mechanism spelled out by hand: a [PhantomReference] per wrapper, a
 * [ReferenceQueue] the collector enqueues them on, and a thread that drains it.
 *
 * **Why this is in `jvmMain` rather than `androidMain`.** It is the fallback for an Android version,
 * but nothing in it is Android-specific — `PhantomReference` and `ReferenceQueue` are Java 1.2. It
 * used to live in `androidMain`, and the consequence was that ROADMAP §4 could only say "Android
 * below API 33 uses the `PhantomReference` path and is not covered yet": the only way to run it was
 * on a device below API 33, and the emulators this project has are API 36, which take the `Cleaner`
 * branch and never reach here. Sitting in `jvmMain` it is reachable from `desktopTest`, and
 * `PhantomCleanerRegistryTest` runs it on every desktop build. `desktopMain` does not use it — it
 * has a real `Cleaner` — so this is a test seam, not a change of behaviour for that target.
 *
 * **The action must not be able to reach its owner.** The registry holds every live entry strongly,
 * and each entry holds its action strongly; an action that could reach the owner would therefore
 * keep the owner reachable, the collector would never enqueue it, and the release would never run.
 * Nothing would report this. `PyObject` passes a top-level function reference for the same reason.
 */
internal object PhantomCleanerRegistry {

    /** A registered release. [clean] runs it at most once, whoever calls it and however often. */
    interface Cleanable {
        fun clean()
    }

    private val queue = ReferenceQueue<Any>()

    /**
     * Every entry that has not been released yet, held strongly.
     *
     * A `PhantomReference` nobody holds is itself collectable, and a collected reference is never
     * enqueued — so this table is not bookkeeping, it is what makes the mechanism work at all.
     */
    private val active = ConcurrentHashMap<Entry, Boolean>()

    private val drainedCount = AtomicLong()
    private val failedCount = AtomicLong()

    /** Releases run because the collector reached their owner. Test-visible. */
    val drained: Long get() = drainedCount.get()

    /** Releases that threw. Test-visible: the drain loop has to survive them. */
    val failed: Long get() = failedCount.get()

    /** Registered releases that have not run yet. Test-visible. */
    val outstanding: Int get() = active.size

    /**
     * Whether the drain loop is still running.
     *
     * Worth exposing because the way this mechanism fails is silent: once the loop stops, every
     * later release is simply never made, and nothing anywhere raises so much as a warning.
     */
    val draining: Boolean get() = drainThread.isAlive

    private class Entry(
        owner: Any,
        private val action: Runnable
    ) : PhantomReference<Any>(owner, queue) {
        private val done = AtomicBoolean(false)

        /** Runs the action if it has not run, and says whether it did. */
        fun run(): Boolean {
            if (!done.compareAndSet(false, true)) return false
            action.run()
            return true
        }
    }

    /**
     * Drains the queue for the life of the process.
     *
     * **The `Throwable` catch is the whole point of this loop's shape**, and it was measured before
     * it was written: with only the `InterruptedException` catch this loop used to have, one
     * release action that threw propagated out of the loop, out of the `Runnable`, and ended the
     * thread — after which *every* later release on the whole process was silently never made.
     * `PhantomCleanerRegistryTest.aReleaseThatThrowsDoesNotStopLaterReleases` failed with
     * `count: 0`, and so did the unrelated case that happened to run after it. `java.lang.ref
     * .Cleaner`'s own thread swallows `Throwable` for exactly this reason, which is why the API 33+
     * branch never had the fault and the fallback did.
     *
     * The entry is removed from [active] *before* it runs, so an action that throws cannot be
     * retried and cannot be left behind either.
     */
    private val drainThread: Thread = Thread({
        while (true) {
            try {
                val entry = queue.remove()
                if (entry is Entry) {
                    active.remove(entry)
                    if (entry.run()) drainedCount.incrementAndGet()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                // Counted rather than printed: this runs on a daemon thread with no test or user
                // frame above it, so there is nowhere to propagate to, and a release that fails is
                // a fact a test needs to be able to assert about.
                failedCount.incrementAndGet()
            }
        }
    }, "PyAutoCloseable-PhantomCleaner").apply {
        isDaemon = true
        start()
    }

    /**
     * Arranges for [action] to run once [owner] is unreachable, or once [Cleanable.clean] is
     * called — whichever happens first, and never both.
     */
    fun register(owner: Any, action: Runnable): Cleanable {
        val entry = Entry(owner, action)
        active[entry] = true
        return EntryCleanable(entry)
    }

    /**
     * Holds only the entry. The owner reaches its `Cleanable`, so a `Cleanable` that reached back
     * to the owner would be the same strong cycle described on this object.
     */
    private class EntryCleanable(private val entry: Entry) : Cleanable {
        override fun clean() {
            active.remove(entry)
            entry.run()
        }
    }
}
