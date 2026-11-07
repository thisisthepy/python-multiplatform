package fixture.app

import python.multiplatform.generated.FunctionTable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #319: `RootPackageDeclarations.kt` has no package. Before the fix the processor emitted an
 * unqualified `rootPackageProbe()` call inside `python.multiplatform.generated.fragments`, and this
 * module did not compile. Now the root-package declarations are skipped (with a KSP warning), the
 * module compiles, and neither appears in the generated table.
 */
class RootPackageSkipTest {

    @Test
    fun rootPackageDeclarationsAreNotInTheTable() {
        val names = FunctionTable.fragments.flatMap { fragment -> fragment.entries().map { it.name } + fragment.classes().map { it.toString() } }
        assertTrue(names.isNotEmpty(), "the table is empty, so this asserts nothing")
        val leaked = names.filter { it == "rootPackageProbe" || it.startsWith("RootPackageThing") || it.endsWith(".rootPackageProbe") }
        assertTrue(leaked.isEmpty(), "root-package declarations reached the table: $leaked")
    }
}
