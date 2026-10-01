package python.multiplatform.ksp

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration

/**
 * How the app-role processor finds every `Fragment_*` object -- its own dependencies' and its
 * own. `docs/design/upcall-table-design.md` §3: the only working implementation today goes through
 * `Resolver.getDeclarationsFromPackage`, which is `@KspExperimental`. This interface is the
 * seam the doc asks for: if that API changes, only [PackageScanFragmentDiscovery] below needs
 * to change, not [python.multiplatform.ksp.AppProcessor] or anything downstream of it.
 */
interface FragmentDiscovery {
    /** Qualified names of every `Fragment_*` object visible from this compilation, in a stable
     * (sorted) order so the generated `FunctionTable` is reproducible across builds. */
    fun discoverFragments(resolver: Resolver): List<String>
}

/**
 * The only implementation today. Queries the well-known package on the classpath (JAR on JVM,
 * `.klib` metadata on Kotlin/Native -- `docs/design/upcall-table-design.md` §2's "Platform uniformity"
 * table), which is why this discovers fragments compiled by other modules without those modules
 * publishing anything beyond their ordinary build artifact.
 */
class PackageScanFragmentDiscovery : FragmentDiscovery {
    @OptIn(KspExperimental::class)
    override fun discoverFragments(resolver: Resolver): List<String> =
        resolver.getDeclarationsFromPackage(FRAGMENTS_PACKAGE)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.simpleName.asString().startsWith(FRAGMENT_PREFIX) }
            .mapNotNull { it.qualifiedName?.asString() }
            .distinct()
            .sorted()
            .toList()
}
