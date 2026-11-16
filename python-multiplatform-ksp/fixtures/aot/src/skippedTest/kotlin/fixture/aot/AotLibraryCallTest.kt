package fixture.aot

import org.junit.Assume.assumeTrue
import kotlin.test.Test

/**
 * What compiles instead of the real `AotLibraryCallTest` (in `src/aotTest`) when the build has no host Python: the
 * Kotlin the real test calls is generated from the host Python's output, so it cannot be compiled without one.
 * The one test reports itself skipped with the reason, as the app fixture's TypedPython tests do; CI turns a skip
 * into a failure.
 */
class AotLibraryCallTest {
    @Test
    fun theAotLibraryTestsNeedAHostPython() {
        val reason = System.getProperty("aot.skip") ?: "no host Python (aot.skip is not set either)"
        assumeTrue("AOT library tests not run: $reason", false)
    }
}
