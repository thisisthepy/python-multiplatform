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
     * since #237 the members of its interfaces and final classes, and since SPEC U-4a and B-1 (#164) the
     * members of its generic classes and interfaces (read with `kotlin.Any?` for the type parameter) and
     * its `suspend` members (`Job.join`, `SendChannel.send`, `Semaphore.acquire`, ...), and since #191
     * the top-level functions with unbounded type parameters (`MutableStateFlow`, `flowOf`, `stateIn`)
     * the members taking a `kotlin.Any?` (`Mutex.lock(owner)`) and the declarations on a star-projected
     * type (`CancellableContinuation<*>.disposeOnCancellation`) and the constructors taking a `String?`
     * (`ClosedReceiveChannelException(message)`), as the jar walker binds them (B-1).
     * `KlibScannerTest.aRealThirdPartyKlibIsScannedWithoutCrashingAndBindsItsHandleFactoriesAndMembers` pins the
     * same list walking the klib directly; if the two disagree, the wiring between `KlibScanner` and
     * this generated table is what broke.
     */
    @Test
    fun theWalkerBindsExactlyTheKlibsHandleFactoriesAndMembers() {
        ArtifactTable.registerInto()
        assertEquals(
            listOf(
                "kotlinx.coroutines.AbstractCoroutine.isActive",
                "kotlinx.coroutines.CancellableContinuation.initCancellability",
                "kotlinx.coroutines.CancellableContinuation.isActive",
                "kotlinx.coroutines.CancellableContinuation.isCancelled",
                "kotlinx.coroutines.CancellableContinuation.isCompleted",
                "kotlinx.coroutines.CancellableContinuation.tryResume",
                "kotlinx.coroutines.CloseableCoroutineDispatcher.close",
                "kotlinx.coroutines.CompletableDeferred.complete",
                "kotlinx.coroutines.CompletableDeferred__Any",
                "kotlinx.coroutines.CompletableDeferred__Job",
                "kotlinx.coroutines.CompletableJob.complete",
                "kotlinx.coroutines.CoroutineDispatcher.Key",
                "kotlinx.coroutines.CoroutineDispatcher.limitedParallelism",
                "kotlinx.coroutines.CoroutineExceptionHandler.Key",
                "kotlinx.coroutines.CoroutineName",
                "kotlinx.coroutines.CoroutineName.Key",
                "kotlinx.coroutines.CoroutineName.name",
                "kotlinx.coroutines.CoroutineStart.isLazy",
                "kotlinx.coroutines.Deferred.await",
                "kotlinx.coroutines.Deferred.getCompleted",
                "kotlinx.coroutines.Dispatchers.Default",
                "kotlinx.coroutines.Dispatchers.Main",
                "kotlinx.coroutines.Dispatchers.Unconfined",
                "kotlinx.coroutines.DisposableHandle.dispose",
                "kotlinx.coroutines.IO",
                "kotlinx.coroutines.Job",
                "kotlinx.coroutines.Job.Key",
                "kotlinx.coroutines.Job.isActive",
                "kotlinx.coroutines.Job.isCancelled",
                "kotlinx.coroutines.Job.isCompleted",
                "kotlinx.coroutines.Job.join",
                "kotlinx.coroutines.Job.onJoin",
                "kotlinx.coroutines.Job.parent",
                "kotlinx.coroutines.Job.start",
                "kotlinx.coroutines.MainCoroutineDispatcher.immediate",
                "kotlinx.coroutines.MainCoroutineDispatcher.limitedParallelism",
                "kotlinx.coroutines.MainScope",
                "kotlinx.coroutines.NonDisposableHandle.dispose",
                "kotlinx.coroutines.NonDisposableHandle.parent",
                "kotlinx.coroutines.Runnable.run",
                "kotlinx.coroutines.SupervisorJob",
                "kotlinx.coroutines.TimeoutCancellationException.createCopy",
                "kotlinx.coroutines.channels.Channel.Factory",
                "kotlinx.coroutines.channels.Channel.Factory.BUFFERED",
                "kotlinx.coroutines.channels.Channel.Factory.CONFLATED",
                "kotlinx.coroutines.channels.Channel.Factory.DEFAULT_BUFFER_PROPERTY_NAME",
                "kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS",
                "kotlinx.coroutines.channels.Channel.Factory.UNLIMITED",
                "kotlinx.coroutines.channels.ChannelIterator.hasNext",
                "kotlinx.coroutines.channels.ChannelIterator.next",
                "kotlinx.coroutines.channels.ChannelResult.Companion",
                "kotlinx.coroutines.channels.ClosedReceiveChannelException",
                "kotlinx.coroutines.channels.ClosedSendChannelException",
                "kotlinx.coroutines.channels.ReceiveChannel.isClosedForReceive",
                "kotlinx.coroutines.channels.ReceiveChannel.isEmpty",
                "kotlinx.coroutines.channels.ReceiveChannel.receive",
                "kotlinx.coroutines.channels.SendChannel.isClosedForSend",
                "kotlinx.coroutines.channels.SendChannel.send",
                "kotlinx.coroutines.disposeOnCancellation",
                "kotlinx.coroutines.ensureActive__CoroutineScope",
                "kotlinx.coroutines.ensureActive__Job",
                "kotlinx.coroutines.flow.DEFAULT_CONCURRENCY",
                "kotlinx.coroutines.flow.DEFAULT_CONCURRENCY_PROPERTY_NAME",
                "kotlinx.coroutines.flow.FlowCollector.emit",
                "kotlinx.coroutines.flow.MutableSharedFlow",
                "kotlinx.coroutines.flow.MutableSharedFlow.emit",
                "kotlinx.coroutines.flow.MutableSharedFlow.resetReplayCache",
                "kotlinx.coroutines.flow.MutableSharedFlow.tryEmit",
                "kotlinx.coroutines.flow.MutableStateFlow",
                "kotlinx.coroutines.flow.MutableStateFlow.compareAndSet",
                "kotlinx.coroutines.flow.MutableStateFlow.value",
                "kotlinx.coroutines.flow.MutableStateFlow.value=",
                "kotlinx.coroutines.flow.SharingStarted.Companion",
                "kotlinx.coroutines.flow.SharingStarted.Eagerly",
                "kotlinx.coroutines.flow.SharingStarted.Lazily",
                "kotlinx.coroutines.flow.SharingStarted.WhileSubscribed",
                "kotlinx.coroutines.flow.StateFlow.value",
                "kotlinx.coroutines.flow.asSharedFlow",
                "kotlinx.coroutines.flow.asStateFlow",
                "kotlinx.coroutines.flow.buffer",
                "kotlinx.coroutines.flow.cancellable",
                "kotlinx.coroutines.flow.conflate",
                "kotlinx.coroutines.flow.consumeAsFlow",
                "kotlinx.coroutines.flow.debounce",
                "kotlinx.coroutines.flow.distinctUntilChanged",
                "kotlinx.coroutines.flow.drop",
                "kotlinx.coroutines.flow.emptyFlow",
                "kotlinx.coroutines.flow.flowOf",
                "kotlinx.coroutines.flow.launchIn",
                "kotlinx.coroutines.flow.produceIn",
                "kotlinx.coroutines.flow.receiveAsFlow",
                "kotlinx.coroutines.flow.sample",
                "kotlinx.coroutines.flow.shareIn",
                "kotlinx.coroutines.flow.stateIn",
                "kotlinx.coroutines.flow.take",
                "kotlinx.coroutines.isActive",
                "kotlinx.coroutines.newFixedThreadPoolContext",
                "kotlinx.coroutines.newSingleThreadContext",
                "kotlinx.coroutines.selects.SelectInstance.disposeOnCompletion",
                "kotlinx.coroutines.selects.SelectInstance.selectInRegistrationPhase",
                "kotlinx.coroutines.sync.Mutex",
                "kotlinx.coroutines.sync.Mutex.isLocked",
                "kotlinx.coroutines.sync.Mutex.lock",
                "kotlinx.coroutines.sync.Mutex.tryLock",
                "kotlinx.coroutines.sync.Mutex.unlock",
                "kotlinx.coroutines.sync.Semaphore",
                "kotlinx.coroutines.sync.Semaphore.acquire",
                "kotlinx.coroutines.sync.Semaphore.availablePermits",
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
