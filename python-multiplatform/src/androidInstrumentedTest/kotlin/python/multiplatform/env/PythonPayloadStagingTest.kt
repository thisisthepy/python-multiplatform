package python.multiplatform.env

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What can be checked about the Android payload path **on this APK**, and an explicit record of
 * what cannot.
 *
 * `assets/python/` is a *consumer's* asset: `toolchain`'s `stagePythonBundleAndroid` registers a
 * staging root into the consuming application's AGP asset sources. This library's own instrumented
 * APK is not such a consumer and carries no payload, so the copy loop in
 * [PythonBootstrap.stagePayload] is unreachable from here — there is no fixture to drive it with,
 * and manufacturing one means adding an asset source directory to `build.gradle.kts`, which is a
 * build-structure change and not this test's to make.
 *
 * So what is pinned here is the half that *is* reachable: the no-payload case (which must be
 * silence, not failure, because it is the normal state of an app with no Python package), and the
 * registry that carries a staged directory from `PythonBootstrap` to `Python3.initialize`. The copy
 * loop itself is `copyAssetTree`, shared byte-for-byte with `stageStdlib`, which
 * `PythonBootstrapTest` exercises on 804 files on every API level this suite runs on.
 */
class PythonPayloadStagingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratches = mutableListOf<File>()

    private fun scratch(name: String): File =
        File(context.cacheDir, "pmp-payload-test/$name").also {
            it.deleteRecursively()
            it.mkdirs()
            scratches += it
        }

    @AfterTest
    fun cleanUp() {
        scratches.forEach { it.deleteRecursively() }
    }

    @Test
    fun anApkWithNoPayloadStagesNothingAndDoesNotFail() {
        assertTrue(
            context.assets.list(PythonPayload.PAYLOAD_ROOT).isNullOrEmpty(),
            "this library's own APK is not a consumer and should carry no assets/python -- if it " +
                "does, this test is measuring the wrong thing",
        )

        val prefix = scratch("absent")
        assertNull(
            PythonBootstrap.stagePayload(context, prefix),
            "no assets/python is the normal state of an app with no Python package; it must be " +
                "silence rather than an exception at start-up",
        )
        assertFalse(
            PythonBootstrap.payloadStampFile(prefix).exists(),
            "nothing was staged, so nothing may claim it was",
        )
        assertFalse(File(prefix, PythonPayload.PAYLOAD_ROOT).exists())
    }

    @Test
    fun aRegisteredRootIsWhatDiscoveryReturns() {
        val staged = scratch("registered")
        AndroidPayloadRoots.register(staged)

        assertContains(
            discoverStagedPayloadRoots(),
            staged.absolutePath,
            "the registry is the whole of Android's discovery: reading an APK's assets needs a " +
                "Context and Python3.initialize has none, so PythonBootstrap stages and registers " +
                "while initialize installs",
        )
    }

    @Test
    fun registrationIsIdempotent() {
        val staged = scratch("twice")
        AndroidPayloadRoots.register(staged)
        AndroidPayloadRoots.register(staged)

        assertEquals(
            1,
            discoverStagedPayloadRoots().count { it == staged.absolutePath },
            "an activity recreated, or a host app that initialises defensively, calls this twice",
        )
    }
}
