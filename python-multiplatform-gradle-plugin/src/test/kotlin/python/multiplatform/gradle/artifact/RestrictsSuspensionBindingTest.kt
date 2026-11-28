package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A suspend member of a `@kotlin.coroutines.RestrictsSuspension` type (or of a type with such a
 * supertype) is declined: it may be called only inside its own scope, so the generated
 * `PendingCall.start { receiver.member() }` cannot compile.
 */
class RestrictsSuspensionBindingTest {

    private val fixtureClasses: File
        get() = File(Class.forName("fixture.artifactrestricted.Free").protectionDomain.codeSource.location.toURI())

    private fun declarations() =
        ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactrestricted"))

    private val reason = "a suspend member of a @RestrictsSuspension type: callable only inside its own scope"

    @Test
    fun aSuspendMemberOfARestrictedInterfaceIsDeclined() {
        val await = declarations().single { it.owner == "fixture.artifactrestricted.Scope" && it.simpleName == "await" }
        assertNull(await.bindingName)
        assertEquals(reason, await.declineReason)
    }

    @Test
    fun aSuspendMemberOfAClassWithARestrictedSupertypeIsDeclined() {
        val await = declarations().single { it.owner == "fixture.artifactrestricted.ScopeImpl" && it.simpleName == "await" }
        assertNull(await.bindingName)
        assertEquals(reason, await.declineReason)
    }

    @Test
    fun aNonSuspendMemberOfARestrictedTypeStaysBound() {
        val plain = declarations().single { it.owner == "fixture.artifactrestricted.Scope" && it.simpleName == "plain" }
        assertNotNull(plain.bindingName)
    }

    @Test
    fun aSuspendMemberOfAnUnrestrictedClassStaysBound() {
        val later = declarations().single { it.owner == "fixture.artifactrestricted.Free" && it.simpleName == "later" }
        assertNotNull(later.bindingName)
    }
}
