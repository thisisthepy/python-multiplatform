// Fixture, not production source: walked as bytecode by `InternalPackageExclusionTest` (issue #192).
@file:Suppress("unused")

package fixture.artifactinternal

class Kept(val x: Int)

fun keptFunction(a: Int): Int = a
