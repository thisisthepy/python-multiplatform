package python.multiplatform.ref

import android.os.Build
import android.os.Build.VERSION.SDK_INT
import python.native.ffi.NativePointer
import java.lang.ref.Cleaner

actual interface PlatformCleaner : AutoCloseable {
    actual override fun close()
}

/**
 * The shared `Cleaner`, or `null` on a device that has none.
 *
 * `java.lang.ref.Cleaner` arrives in **API 33** (Tiramisu). Below that the release goes through
 * [PhantomCleanerRegistry], which is the same mechanism written by hand.
 *
 * This nullable value, not `SDK_INT`, is what the code below branches on. The two are equivalent
 * only as long as nothing else can make the `Cleaner` absent — and the previous version of this
 * file branched on `SDK_INT` and then called `sharedCleaner?.register(...)`, so a null here on
 * API 33+ would have produced a wrapper with **no release registered at all**, silently, with the
 * `?.` reading as caution. Branching on the object removes the case rather than tolerating it.
 */
private val sharedCleaner: Cleaner? =
    if (SDK_INT >= Build.VERSION_CODES.TIRAMISU) Cleaner.create() else null

actual fun registerCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
): PlatformCleaner {
    return AndroidCleaner(pointer, closeAction)
}

/**
 * One release, run by whichever of `close()` and the collector gets there first — and never by
 * both. Both branches guarantee that: `Cleaner.Cleanable.clean()` is documented to run its action
 * at most once, and [PhantomCleanerRegistry.Cleanable] holds a compare-and-set flag for it. It has
 * to be a guarantee rather than a convention, because a second run is a second `Py_DecRef` on a
 * reference this wrapper no longer owns — the double free of ROADMAP §1 and §4.
 */
private class AndroidCleaner(
    pointer: NativePointer,
    closeAction: (NativePointer) -> Unit
) : PlatformCleaner {
    private val cleanable: Cleaner.Cleanable?
    private val fallback: PhantomCleanerRegistry.Cleanable?

    init {
        // `action` holds the pointer value and a top-level function, and nothing else. It must not
        // be able to reach `this`: both mechanisms hold the action strongly while watching `this`
        // weakly, so an action that reached back would keep the wrapper alive forever and the
        // release would never happen. That is why `CleanupAction` is a class taking the two values
        // rather than a lambda written inline here.
        val action = CleanupAction(pointer, closeAction)
        val cleaner = sharedCleaner
        if (cleaner != null) {
            cleanable = cleaner.register(this, action)
            fallback = null
        } else {
            cleanable = null
            fallback = PhantomCleanerRegistry.register(this, action)
        }
    }

    override fun close() {
        cleanable?.clean()
        fallback?.clean()
    }
}

private class CleanupAction(
    private val pointer: NativePointer,
    private val action: (NativePointer) -> Unit
) : Runnable {
    override fun run() {
        action(pointer)
    }
}
