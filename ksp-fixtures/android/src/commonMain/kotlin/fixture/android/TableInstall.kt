package fixture.android

import python.multiplatform.reflection.InstallsUpcallTable
import python.multiplatform.reflection.UpcallTable

/**
 * The seam under AGP.
 *
 * ROADMAP §13 recorded that `androidMain` is the one source set that *can* name `FunctionTable`,
 * because it is the Android target's own source set rather than an intermediate one. That makes
 * it the case most likely to behave differently: KSP runs once per *variant* here, so the
 * `actual` is generated into the debug and release compilations separately, and each one carries
 * exactly one.
 *
 * Nothing shared code writes should have to know any of that, which is what this file asserts by
 * being identical in shape to `ksp-fixtures/app`'s.
 */
@InstallsUpcallTable
expect fun installGeneratedUpcallTable()

/** Common code reaching the generated table without naming it. */
fun installAndCountEntries(): Int {
    installGeneratedUpcallTable()
    return UpcallTable.callableCount
}
