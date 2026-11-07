// Fixture, not production source: walked as bytecode by `InternalPackageExclusionTest` (issue #192).
@file:Suppress("unused")

package fixture.artifactinternal.internal.deep

class HiddenBelowInternal(val x: Int)

fun hiddenBelowInternalFunction(a: Int): Int = a
