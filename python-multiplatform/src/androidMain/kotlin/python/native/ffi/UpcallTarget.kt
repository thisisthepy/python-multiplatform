package python.native.ffi

import java.lang.ref.WeakReference

/**
 * The Kotlin side of the JNI upcall probes in `artMain/cinterop/jni_onload.def`. Test support
 * that has to live in `androidMain` because `JNI_OnLoad` resolves it by name at load time.
 */
object UpcallTarget {
    @JvmStatic
    fun upcallPrimitive(x: Long): Long {
        return x
    }

    @JvmStatic
    fun upcallString(s: String): Long {
        return s.length.toLong()
    }

    /**
     * Weakly held `java.lang.Thread` peers, one per [trackCurrentThread] call.
     *
     * Weak, because whether the peer is still reachable *is* the measurement. ART holds an
     * attached thread's peer as a GC root (`tlsPtr_.opeer`) and drops it in `Thread::Destroy`, so
     * a reference that survives a collection means the attachment does too. That readout works on
     * every API level, which `ThreadGroup.enumerate` does not: on API 26 it does not report the
     * peer of an attached native thread even while that thread is running.
     */
    private val tracked = mutableListOf<WeakReference<Thread>>()

    /** Records the calling thread's peer weakly and returns its id. Called from JNI. */
    @JvmStatic
    fun trackCurrentThread(): Long {
        val self = Thread.currentThread()
        synchronized(tracked) { tracked.add(WeakReference(self)) }
        return self.id
    }

    /** How many tracked peers a collection has *not* been able to reclaim. */
    @JvmStatic
    fun liveTrackedCount(): Int = synchronized(tracked) { tracked.count { it.get() != null } }

    @JvmStatic
    fun clearTracked(): Unit = synchronized(tracked) { tracked.clear() }
}
