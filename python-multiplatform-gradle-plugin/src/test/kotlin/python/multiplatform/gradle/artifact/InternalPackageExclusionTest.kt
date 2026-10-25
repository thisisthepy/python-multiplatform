package python.multiplatform.gradle.artifact

import python.multiplatform.gradle.stubs.renderKotlinFqnStubs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #192 over `internalpackagefixture/`: the walker keeps public declarations of an `internal`
 * package out of the bindings and the stubs, and honours `excludePackages` like the KSP side (SPEC B-11).
 *
 * ### Red before the implementation
 *
 * Every test here that passes `excludePackages =` fails to compile before the change (the parameter does
 * not exist), which is the expected red. Once it compiles, [aPublicDeclarationInAnInternalPackageIsNotBound]
 * and [theStubsOfAnInternalPackageAreNotEmitted] fail with the `.internal` names present, and the guards
 * ([aNormalPackageStaysBound], [aPackageThatOnlyLooksInternalStaysBound], [withoutTheOptionAnExcludedPackageIsBound])
 * stay green.
 */
class InternalPackageExclusionTest {

    private val pkg = "fixture.artifactinternal"

    private val fixtureClasses: File
        get() {
            val location = Class.forName("$pkg.Kept").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isDirectory, "expected a directory of .class files: $it") }
        }

    private fun names(exclude: List<String> = emptyList()): Set<String> =
        ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf(pkg), excludePackages = exclude)
            .map { it.name }.toSet()

    @Test
    fun aNormalPackageStaysBound() {
        val bound = names()
        assertTrue("$pkg.Kept" in bound, "bound: $bound")
        assertTrue("$pkg.keptFunction" in bound, "bound: $bound")
    }

    @Test
    fun aPublicDeclarationInAnInternalPackageIsNotBound() {
        val bound = names()
        assertEquals(emptyList(), bound.filter { ".internal." in it || it.endsWith(".internal") }.sorted(), "bound: $bound")
        // Below an `internal` segment as well: `*.internal.*`.
        assertEquals(emptyList(), bound.filter { "HiddenBelowInternal" in it || "hiddenBelowInternal" in it }, "bound: $bound")
    }

    @Test
    fun aPackageThatOnlyLooksInternalStaysBound() {
        val bound = names()
        assertTrue("$pkg.internals.LooksInternal" in bound, "bound: $bound")
        assertTrue("$pkg.internals.looksInternalFunction" in bound, "bound: $bound")
    }

    @Test
    fun withoutTheOptionAnExcludedPackageIsBound() {
        val bound = names()
        assertTrue("$pkg.excluded.ExcludedByOption" in bound, "bound: $bound")
        assertTrue("$pkg.excluded.excludedByOptionFunction" in bound, "bound: $bound")
    }

    @Test
    fun excludePackagesKeepsAPackageAndItsSubpackagesOut() {
        val bound = names(exclude = listOf("$pkg.excluded"))
        assertEquals(emptyList(), bound.filter { ".excluded." in it }, "bound: $bound")
        assertTrue("$pkg.Kept" in bound, "the control must still bind; bound: $bound")
        assertTrue("$pkg.internals.LooksInternal" in bound, "bound: $bound")
    }

    @Test
    fun excludePackagesMatchesANamespaceNotAPrefixOfText() {
        // `fixture.artifactinternal.intern` is a text prefix of `...internals` and no package.
        val bound = names(exclude = listOf("$pkg.intern"))
        assertTrue("$pkg.internals.LooksInternal" in bound, "bound: $bound")
        // An entry that names the package itself excludes it.
        assertEquals(emptyList(), names(exclude = listOf("$pkg.internals")).filter { ".internals." in it })
    }

    @Test
    fun noDeclarationModelIsKeptForAnInternalOrExcludedPackage() {
        val owners = ArtifactScanner.scanDeclarations(
            fixtureClasses, includePrefixes = listOf(pkg), excludePackages = listOf("$pkg.excluded"),
        ).map { it.owner }.toSet()
        assertEquals(emptyList(), owners.filter { owner -> owner.split('.').any { it == "internal" || it == "excluded" } }, "owners: $owners")
        assertTrue(owners.any { it == pkg || it.startsWith("$pkg.Kept") }, "the control must still be modelled; owners: $owners")
    }

    @Test
    fun theStubsOfAnInternalPackageAreNotEmitted() {
        val files = renderKotlinFqnStubs(
            ArtifactScanner.scanDeclarations(
                fixtureClasses, includePrefixes = listOf(pkg), excludePackages = listOf("$pkg.excluded"),
            ),
        )
        val paths = files.keys
        assertTrue(paths.any { it.startsWith("fixture/artifactinternal/") }, "the control must be stubbed; paths: $paths")
        assertEquals(emptyList(), paths.filter { "/internal/" in it || "/excluded/" in it }, "paths: $paths")
        val text = files.values.joinToString("\n")
        assertTrue("HiddenByInternal" !in text && "HiddenBelowInternal" !in text && "ExcludedByOption" !in text, "stub text leaks a skipped declaration")
        assertTrue(paths.any { "/internals/" in it }, "paths: $paths")
    }
}
