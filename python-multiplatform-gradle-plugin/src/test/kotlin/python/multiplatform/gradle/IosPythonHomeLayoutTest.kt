package python.multiplatform.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SPEC L-10 (issue #59): which slice an Xcode build stages, which files of `Python.xcframework` go
 * into the app's prefix, and what the staging task leaves on disk.
 *
 * The run-time half -- the library finding `<resourcePath>/python-multiplatform-home` and handing it
 * to CPython -- is `python-multiplatform/src/iosSimulatorArm64Test/.../env/IosPythonHomeTest.kt`.
 * The two share no code, so each pins the directory name as a literal.
 *
 * What this cannot find: whether CPython on a device really loads the result. The fake tree below
 * mirrors the observed 3.14 archive's *shape*; the installed-app run in
 * `docs/platforms/ios-app-bundle.md` is the check for the rest.
 */
class IosPythonHomeLayoutTest {

    private val version = "3.14.7"
    // Under this module's build directory (the test task's working directory), not the system
    // temporary directory: AGENTS.md rule 2.
    private val scratch: File =
        File(System.getProperty("user.dir"), "build/tmp/iosPythonHomeLayoutTest/${java.util.UUID.randomUUID()}")
            .apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    // ---- names ----

    @Test
    fun theBundleDirectoryIsTheOneTheLibraryLooksFor() {
        // `IosPythonHome.DIRECTORY_NAME` in python-multiplatform/src/iosMain.
        assertEquals("python-multiplatform-home", IosPythonHomeLayout.BUNDLE_DIRECTORY)
    }

    @Test
    fun theBundleDirectoryIsNotThePayloadRoot() {
        // `PythonPayload.PAYLOAD_ROOT` is "python", and the payload copy rsyncs it with --delete.
        assertTrue(IosPythonHomeLayout.BUNDLE_DIRECTORY != "python")
    }

    // ---- slice choice ----

    @Test
    fun xcodeSettingsSelectTheSlice() {
        val l = IosPythonHomeLayout
        assertEquals("iphonesimulator-arm64", l.sliceFor("-iphonesimulator", "arm64").name)
        assertEquals("iphonesimulator-x86_64", l.sliceFor("-iphonesimulator", "x86_64").name)
        assertEquals("iphoneos-arm64", l.sliceFor("-iphoneos", "arm64").name)
        assertEquals("ios-arm64", l.sliceFor("-iphoneos", "arm64").xcframeworkSlice)
        assertEquals("ios-arm64_x86_64-simulator", l.sliceFor("-iphonesimulator", "x86_64").xcframeworkSlice)
    }

    @Test
    fun aUniversalBuildIsRefusedWithTheFix() {
        val e = assertFailsWith<IllegalArgumentException> { IosPythonHomeLayout.sliceFor("-iphonesimulator", "arm64 x86_64") }
        assertTrue("ONLY_ACTIVE_ARCH" in e.message.orEmpty(), e.message)
    }

    @Test
    fun outsideXcodeOrOnAnotherPlatformIsRefused() {
        assertFailsWith<IllegalArgumentException> { IosPythonHomeLayout.sliceFor(null, "arm64") }
        assertFailsWith<IllegalArgumentException> { IosPythonHomeLayout.sliceFor("-iphonesimulator", "") }
        assertFailsWith<IllegalArgumentException> { IosPythonHomeLayout.sliceFor("-appletvos", "arm64") }
        assertFailsWith<IllegalArgumentException> { IosPythonHomeLayout.sliceFor("-iphoneos", "x86_64") }
    }

    @Test
    fun everySliceHasItsOwnTask() {
        assertEquals(
            listOf(
                "stageIosPythonHome_iphoneos_arm64",
                "stageIosPythonHome_iphonesimulator_arm64",
                "stageIosPythonHome_iphonesimulator_x86_64",
            ),
            IosPythonHomeLayout.slices.map { it.stageTaskName },
        )
    }

    // ---- what is copied ----

    @Test
    fun theSharedStdlibComesFirstAndTheSliceTreeSecond() {
        val sim = IosPythonHomeLayout.sliceFor("-iphonesimulator", "x86_64")
        assertEquals(
            listOf("lib/python3.14", "ios-arm64_x86_64-simulator/lib-x86_64/python3.14"),
            IosPythonHomeLayout.sourceDirectories(sim, version),
        )
    }

    @Test
    fun fileSelection() {
        val staged = IosPythonHomeLayout::isStdlibPathStaged
        assertTrue(staged("os.py"))
        assertTrue(staged("encodings/__init__.py"))
        assertTrue(staged("json/decoder.py"))
        assertTrue(staged("lib-dynload/_json.cpython-314-iphonesimulator.so"))
        assertTrue(staged("_sysconfigdata__ios_arm64-iphonesimulator.py"))
        assertTrue(staged("site-packages/README.txt"))
        // A package *named like* an excluded one deeper down is not excluded.
        assertTrue(staged("email/test/__init__.py"))

        assertFalse(staged("test/test_os.py"))
        assertFalse(staged("test"))
        assertFalse(staged("tkinter/__init__.py"))
        assertFalse(staged("idlelib/idle.py"))
        assertFalse(staged("turtledemo/__main__.py"))
        assertFalse(staged("ensurepip/_bundled/pip.whl"))
        assertFalse(staged("encodings/__pycache__/aliases.cpython-314.pyc"))
        assertFalse(staged("__pycache__"))
        assertFalse(staged("libpython3.14.dylib"))
    }

    @Test
    fun aPlanMergesTheTwoTreesAndDropsTheExcludedFiles() {
        val xcframework = fakeXcframework()
        val slice = IosPythonHomeLayout.sliceFor("-iphonesimulator", "arm64")
        val plan = IosPythonHomeLayout.plan(xcframework, slice, version)
        assertEquals(
            listOf(
                "lib/python3.14/_sysconfigdata__ios_arm64-iphonesimulator.py",
                "lib/python3.14/encodings/__init__.py",
                "lib/python3.14/lib-dynload/_json.cpython-314-iphonesimulator.so",
                "lib/python3.14/os.py",
            ),
            plan.keys.toList(),
        )
        // The x86_64 tree of the same fat slice is not mixed in.
        assertFalse(plan.values.any { "lib-x86_64" in it.path })
    }

    @Test
    fun aMissingSliceTreeFailsInsteadOfStagingHalfAPrefix() {
        val xcframework = fakeXcframework()
        File(xcframework, "ios-arm64").deleteRecursively()
        assertFailsWith<GradleException> {
            IosPythonHomeLayout.plan(xcframework, IosPythonHomeLayout.sliceFor("-iphoneos", "arm64"), version)
        }
    }

    // ---- the task, against the fake tree ----

    @Test
    fun theTaskStagesThePrefixAndTheTemplateBesideIt() {
        val xcframework = fakeXcframework()
        val project = ProjectBuilder.builder()
            .withProjectDir(File(scratch, "project").apply { mkdirs() })
            .withGradleUserHomeDir(File(scratch, "gradle-home"))
            .build()
        val out = File(scratch, "out")
        // Left over from a previous run with a wider selection; must not survive.
        File(out, "home/lib/python3.14/test/stale.py").apply { parentFile.mkdirs(); writeText("") }

        val task = project.tasks.create("stage", StageIosPythonHomeTask::class.java)
        task.xcframework.set(xcframework)
        task.sliceName.set("iphonesimulator-arm64")
        task.pythonVersion.set(version)
        task.destinationDir.set(out)
        task.stage()

        val staged = File(out, "home").walkTopDown().filter { it.isFile }
            .map { it.relativeTo(File(out, "home")).invariantSeparatorsPath }.sorted().toList()
        assertEquals(
            listOf(
                "lib/python3.14/_sysconfigdata__ios_arm64-iphonesimulator.py",
                "lib/python3.14/encodings/__init__.py",
                "lib/python3.14/lib-dynload/_json.cpython-314-iphonesimulator.so",
                "lib/python3.14/os.py",
            ),
            staged,
        )
        assertTrue(File(out, StageIosPythonHomeTask.TEMPLATE_NAME).isFile)
        assertFalse(File(out, "home/${StageIosPythonHomeTask.TEMPLATE_NAME}").exists())
    }

    /** The observed 3.14 archive's shape, a few files deep. */
    private fun fakeXcframework(): File {
        val root = File(scratch, "Python.xcframework")
        fun file(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText(path) }
        file("lib/python3.14/os.py")
        file("lib/python3.14/encodings/__init__.py")
        file("lib/python3.14/encodings/__pycache__/__init__.cpython-314.pyc")
        file("lib/python3.14/test/test_os.py")
        file("lib/python3.14/tkinter/__init__.py")
        file("ios-arm64/lib-arm64/python3.14/lib-dynload/_json.cpython-314-iphoneos.so")
        file("ios-arm64/lib-arm64/python3.14/_sysconfigdata__ios_arm64-iphoneos.py")
        file("ios-arm64/lib/libpython3.14.dylib")
        file("ios-arm64_x86_64-simulator/lib-arm64/python3.14/lib-dynload/_json.cpython-314-iphonesimulator.so")
        file("ios-arm64_x86_64-simulator/lib-arm64/python3.14/_sysconfigdata__ios_arm64-iphonesimulator.py")
        file("ios-arm64_x86_64-simulator/lib-x86_64/python3.14/lib-dynload/_json.cpython-314-iphonesimulator.so")
        file("ios-arm64_x86_64-simulator/lib/libpython3.14.dylib")
        file("build/iOS-dylib-Info-template.plist")
        return root
    }
}
