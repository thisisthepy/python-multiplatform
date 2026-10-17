// Fixture, not production source: walked as bytecode by `InternalPackageExclusionTest` (issue #192).
@file:Suppress("unused")

package fixture.artifactinternal.internal

class HiddenByInternal(val x: Int)

fun hiddenByInternalFunction(a: Int): Int = a
