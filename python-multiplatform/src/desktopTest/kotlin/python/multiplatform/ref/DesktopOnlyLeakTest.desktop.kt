package python.multiplatform.ref

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopOnlyLeakTest {
    
    @Test
    fun testCleanerRuns() {
        var actionRun = false
        var obj: Any? = Any()
        
        val cleaner = java.lang.ref.Cleaner.create()
        cleaner.register(obj!!, Runnable { actionRun = true })
        
        obj = null
        var attempts = 0
        while (!actionRun && attempts < 50) {
            System.gc()
            Thread.sleep(100)
            attempts++
        }
        
        assertTrue(actionRun, "Cleaner action should have run")
    }
}
