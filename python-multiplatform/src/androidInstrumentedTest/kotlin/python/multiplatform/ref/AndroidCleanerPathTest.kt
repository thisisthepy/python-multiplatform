package python.multiplatform.ref

import android.os.Build
import python.native.ffi.toNativePointer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCleanerPathTest {

    @Test
    fun testPathSelectionMatchesApiLevel() {
        val initialOutstanding = PhantomCleanerRegistry.outstanding
        val dummyPointer = 0x12345678L.toNativePointer()!!
        val cleaner = registerCleaner(dummyPointer) { }

        val isApi33OrAbove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        val cleanerClass = cleaner.javaClass
        assertEquals("python.multiplatform.ref.AndroidCleaner", cleanerClass.name)

        var cleanableValue: Any? = null
        var cleanableReflectError: Throwable? = null
        try {
            val cleanableField = cleanerClass.getDeclaredField("cleanable").apply { isAccessible = true }
            cleanableValue = cleanableField.get(cleaner)
        } catch (t: Throwable) {
            cleanableReflectError = t
        }

        val fallbackField = cleanerClass.getDeclaredField("fallback").apply { isAccessible = true }
        val fallbackValue = fallbackField.get(cleaner)

        if (isApi33OrAbove) {
            assertNull(cleanableReflectError, "API 33+ must successfully reflect on cleanable field")
            assertNotNull(cleanableValue, "API 33+ must populate java.lang.ref.Cleaner.Cleanable")
            assertNull(fallbackValue, "API 33+ must NOT populate PhantomCleanerRegistry.Cleanable")
            assertEquals(
                initialOutstanding,
                PhantomCleanerRegistry.outstanding,
                "API 33+ must NOT register entries in PhantomCleanerRegistry"
            )
        } else {
            // On API < 33, java.lang.ref.Cleaner is missing from Android SDK/runtime.
            // Reflecting on field type java.lang.ref.Cleaner$Cleanable throws NoClassDefFoundError or cleanableValue is null.
            if (cleanableReflectError != null) {
                assertTrue(
                    cleanableReflectError is NoClassDefFoundError || cleanableReflectError is ClassNotFoundException,
                    "API < 33 reflection error must be NoClassDefFoundError/ClassNotFoundException due to missing Cleaner class: $cleanableReflectError"
                )
            } else {
                assertNull(cleanableValue, "API < 33 must NOT populate java.lang.ref.Cleaner.Cleanable")
            }
            assertNotNull(fallbackValue, "API < 33 must populate PhantomCleanerRegistry.Cleanable")
            assertEquals(
                initialOutstanding + 1,
                PhantomCleanerRegistry.outstanding,
                "API < 33 must register entries in PhantomCleanerRegistry"
            )
        }

        cleaner.close()
    }

    @Test
    fun testDrainMechanismReclaimsObjectOnGC() {
        val isApi33OrAbove = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val actionExecuted = AtomicBoolean(false)
        val initialDrained = PhantomCleanerRegistry.drained

        val weakCleaner = allocateCleanerAndDrop(actionExecuted)

        var attempts = 0
        while (!actionExecuted.get() && attempts < 20) {
            forceGC()
            attempts++
        }

        assertTrue(
            actionExecuted.get(),
            "Cleaner action must execute upon GC (weakCleaner collected: ${weakCleaner.get() == null}, attempts: $attempts)"
        )

        if (isApi33OrAbove) {
            assertEquals(
                initialDrained,
                PhantomCleanerRegistry.drained,
                "API 33+ uses java.lang.ref.Cleaner, so PhantomCleanerRegistry.drained must NOT increment"
            )
        } else {
            assertTrue(
                PhantomCleanerRegistry.draining,
                "API < 33 must have active PhantomCleanerRegistry drain thread running"
            )
            assertTrue(
                PhantomCleanerRegistry.drained > initialDrained,
                "API < 33 must increment PhantomCleanerRegistry.drained when garbage collected"
            )
        }
    }

    private fun allocateCleanerAndDrop(actionExecuted: AtomicBoolean): java.lang.ref.WeakReference<PlatformCleaner> {
        val dummyPointer = 0x9999L.toNativePointer()!!
        val cleaner = registerCleaner(dummyPointer) {
            actionExecuted.set(true)
        }
        return java.lang.ref.WeakReference(cleaner)
    }
}
