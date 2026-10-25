package fixture.artifact

import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The klib walker end to end, on the one Kotlin/Native target this fixture builds for: a `.klib`
 * the build resolved -> `KlibScanner` -> a generated (empty) `ArtifactTable`, compiled and linked
 * against the real klib.
 *
 * ### Why `ArtifactTable.fragments` is empty, and that is what this suite pins
 *
 * `kotlinx-coroutines-core`'s public surface is almost entirely extension functions on
 * `CoroutineScope`/`Job`/`Flow`, none of which are bindable boundary types today, plus a small number
 * of `@PublishedApi internal` declarations (`kotlinx.coroutines.flow.internal.checkIndexOverflow`
 * among them) that `KlibScanner` **used to** bind before this suite ever ran on a device: it is
 * binary-visible so an inline call site inside `kotlinx-coroutines-core` itself can resolve it, but
 * not source-visible from a generated fragment in a *different* module, and
 * `:python-multiplatform-gradle-plugin-fixtures:klib-artifact:compileKotlinAndroidNativeArm64` failing with "it is internal in file"
 * -- the first time this walker's generated Kotlin was ever actually compiled rather than only
 * unit-tested -- is what caught it. `KlibScanWorkAction`'s KDoc has the full story; `KlibScannerTest
 * .aPublishedApiInternalDeclarationIsDeclinedWithItsOwnReason` pins the fix on the JVM side, and this
 * class pins that the fix reaches all the way through a real Kotlin/Native compile, link and run.
 *
 * `WalkedKlibArtifactPythonImportTest`, beside this file, carries the same path *through Python* --
 * both classes exist to demonstrate that an *empty* table is still wired correctly end to end, not
 * merely absent.
 *
 * ### This suite has no Gradle task that runs it
 *
 * `androidNativeArm64` gets `linkDebugTestAndroidNativeArm64` from KGP and nothing that *executes*
 * the binary -- KGP registers a run task only where it knows how to reach a host (the build machine,
 * or `simctl`), and an Android device is neither. `:python-multiplatform` solves that for itself with
 * a hand-written `androidNativeArm64Test` task (`adb push` + `PYTHONHOME` + a TeamCity-to-JUnit-XML
 * parser, ~200 lines of its own `build.gradle.kts`) and that machinery is project-local. So this
 * fixture's suite is **compiled and linked by the ordinary build, and run by pushing `test.kexe` to a
 * device by hand** -- see this module's `build.gradle.kts` for the exact command. Until that is
 * wired, a green ordinary build says these tests *compile*, not that they *pass*.
 */
class WalkedKlibArtifactTableTest {

    @BeforeTest
    fun startFromAnEmptyTable() {
        UpcallTable.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * The fixed text: everything `KlibScanner` bound out of the whole `kotlinx-coroutines-core` klib
     * under the `kotlinx.coroutines` namespace. Zero before issue #234, when no Kotlin object could
     * cross the klib boundary; since then the factories whose types are the klib's own classes, and
     * since #237 the members of its interfaces and final classes.
     * `KlibScannerTest.aRealThirdPartyKlibIsScannedWithoutCrashingAndBindsItsHandleFactoriesAndMembers` pins the
     * same list walking the klib directly; if the two disagree, the wiring between `KlibScanner` and
     * this generated table is what broke.
     */
    @Test
    fun theWalkerBindsExactlyTheKlibsHandleFactoriesAndMembers() {
        ArtifactTable.registerInto()
        assertEquals(
            listOf(
                "kotlinx.coroutines.ChildJob.parentCancelled",
                "kotlinx.coroutines.CompletableJob.complete",
                "kotlinx.coroutines.DisposableHandle.dispose",
                "kotlinx.coroutines.Job",
                "kotlinx.coroutines.Job.attachChild",
                "kotlinx.coroutines.Job.start",
                "kotlinx.coroutines.MainScope",
                "kotlinx.coroutines.Runnable.run",
                "kotlinx.coroutines.SupervisorJob",
                "kotlinx.coroutines.TimeoutCancellationException.createCopy",
                "kotlinx.coroutines.newFixedThreadPoolContext",
                "kotlinx.coroutines.newSingleThreadContext",
                "kotlinx.coroutines.sync.Mutex",
                "kotlinx.coroutines.sync.Semaphore",
                "kotlinx.coroutines.sync.Semaphore.release",
                "kotlinx.coroutines.sync.Semaphore.tryAcquire",
            ),
            UpcallTable.entries().map { it.name }.filter { it.startsWith("kotlinx.") }.sorted(),
        )
    }

    /** `registerInto` must not resolve a name that was never bound: `checkIndexOverflow` stays declined. */
    @Test
    fun aDeclinedNameStillResolvesToNothing() {
        ArtifactTable.registerInto()
        val entry = UpcallTable.resolve("kotlinx.coroutines.flow.internal.checkIndexOverflow")
        assertTrue(!entry.isValid, "checkIndexOverflow must not resolve: it is @PublishedApi internal and must stay declined")
    }

    /** `registerInto` adds; a second call must not double the table -- the same guard
     * `WalkedArtifactTableTest.registeringTwiceIsANoOp` pins for the JVM producer. */
    @Test
    fun registeringTwiceIsANoOp() {
        ArtifactTable.registerInto()
        val once = UpcallTable.callableCount
        ArtifactTable.registerInto()
        assertEquals(once, UpcallTable.callableCount)
    }
}
