// Fixture, not production source: walked as bytecode by `InternalPackageExclusionTest` (issue #192).
@file:Suppress("unused")

package fixture.artifactinternal.excluded

class ExcludedByOption(val x: Int)

fun excludedByOptionFunction(a: Int): Int = a
