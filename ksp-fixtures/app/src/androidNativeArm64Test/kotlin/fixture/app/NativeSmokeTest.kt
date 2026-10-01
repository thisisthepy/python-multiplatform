package fixture.app

import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compile+link-only smoke test on a real Kotlin/Native target (docs/design/upcall-table-design.md §8's
 * "Verify .klib discovery" open question). Not run here -- androidNativeArm64 targets a
 * device/emulator this workspace does not execute tests against -- but linking this binary is
 * what `docs/design/upcall-table-design.md` §11.1's tree-shaking measurement diffs the size of.
 */
class NativeSmokeTest {
    @Test
    fun theAggregatedTableInstallsOnNative() {
        UpcallTable.install(FunctionTable.fragments)
        assertTrue(UpcallTable.resolve("fixture.library.add").isValid)
        UpcallTable.clear()
    }
}
