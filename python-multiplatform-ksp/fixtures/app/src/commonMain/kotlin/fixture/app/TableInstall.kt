package fixture.app

import python.multiplatform.reflection.InstallsUpcallTable
import python.multiplatform.reflection.UpcallTable

/**
 * ROADMAP §13's first defect, reproduced here and closed here.
 *
 * The defect: KSP writes `python.multiplatform.generated.FunctionTable` into the *leaf* target's
 * compilation, so this file -- `commonMain`, which every leaf depends on -- cannot name it.
 * Written the obvious way, the fixture does not compile:
 *
 *     e: python-multiplatform-ksp/fixtures/app/src/commonMain/kotlin/fixture/app/TableInstall.kt:4:39
 *         Unresolved reference 'FunctionTable'.
 *
 * observed on `:python-multiplatform-ksp:fixtures:app:compileKotlinDesktop`. No fixture had this shape before, which
 * is why the whole defect only turned up in `sample`: both existing fixtures reached the table
 * from `desktopTest`, a *leaf* compilation, where it resolves fine.
 *
 * The seam: one `expect` here, and `python-multiplatform-ksp` emits the `actual` into every leaf
 * it generates a `FunctionTable` for. `:python-multiplatform-ksp:fixtures:app` has three leaves (`desktop`,
 * `androidNativeArm64`, `androidNativeX64`) and not one of them needs a hand-written line.
 */
@InstallsUpcallTable
expect fun installGeneratedUpcallTable()

/**
 * Shared code doing the thing that could not be written before: installing the generated table
 * and reading it back, naming nothing that KSP generated.
 */
fun installAndDescribeTable(): String {
    installGeneratedUpcallTable()
    return "${UpcallTable.callableCount} entries, ${UpcallTable.classCount} classes, " +
        "from ${UpcallTable.moduleNames.sorted().joinToString(", ")}"
}
