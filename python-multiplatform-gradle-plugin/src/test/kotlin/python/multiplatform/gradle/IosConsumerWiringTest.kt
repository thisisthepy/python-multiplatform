package python.multiplatform.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stand-ins for the Kotlin Gradle Plugin types the plugin reaches by reflection (see `TEST_WORD`). */
object IosDummies {
    class KonanTarget(private val n: String) {
        fun getName(): String = n
    }

    class Framework {
        private val opts = mutableListOf<String>()
        fun getLinkerOpts(): MutableList<String> = opts
    }

    class Executable {
        private val opts = mutableListOf<String>()
        fun getLinkerOpts(): MutableList<String> = opts
    }

    class Target(private val konan: String, private val bins: List<Any>) {
        fun getKonanTarget(): KonanTarget = KonanTarget(konan)
        fun getBinaries(): Iterable<Any> = bins
    }

    class Kotlin(private val targets: List<Any>) {
        fun getTargets(): Iterable<Any> = targets
    }
}

class IosConsumerWiringTest {

    private val arm64 = IosDummies.Framework()
    private val sim = IosDummies.Framework()
    private val x64 = IosDummies.Framework()
    private val exe = IosDummies.Executable()

    private fun project(withIos: Boolean = true): Project {
        val project = ProjectBuilder.builder().build()
        val targets = if (withIos) {
            listOf(
                IosDummies.Target("ios_arm64", listOf(arm64, exe)),
                IosDummies.Target("ios_simulator_arm64", listOf(sim)),
                IosDummies.Target("ios_x64", listOf(x64)),
                IosDummies.Target("linux_x64", listOf(IosDummies.Framework())),
            )
        } else {
            listOf(IosDummies.Target("linux_x64", emptyList()))
        }
        project.extensions.add("kotlin", IosDummies.Kotlin(targets))
        return project
    }

    private fun extension(project: Project) =
        project.extensions.create("pythonBindingsUnderTest", PythonBindingsExtension::class.java)

    private fun wire(project: Project, configure: PythonBindingsExtension.() -> Unit = {}): PythonBindingsExtension {
        val ext = extension(project)
        ext.configure()
        PythonBindingsPlugin().configureIosConsumer(project, ext)
        return ext
    }

    @Test
    fun noIosTargetMeansNoTask() {
        val project = project(withIos = false)
        wire(project)
        val iosTasks = project.tasks.names.filter {
            it == DOWNLOAD_IOS_TASK || it == PREPARE_XCFRAMEWORK_TASK || it.startsWith("stageIosPythonHome") ||
                it == INSTALL_XCODE_SCRIPT_TASK
        }
        assertEquals(emptyList(), iosTasks, "a module without iOS targets must get none: ${project.tasks.names}")
    }

    @Test
    fun iosTargetsRegisterEveryTask() {
        val project = project()
        wire(project)
        val names = project.tasks.names
        for (n in listOf(DOWNLOAD_IOS_TASK, PREPARE_XCFRAMEWORK_TASK, STAGE_IOS_HOME_TASK, STAGE_IOS_HOME_FOR_XCODE_TASK, INSTALL_XCODE_SCRIPT_TASK)) {
            assertTrue(n in names, "$n missing: $names")
        }
        for (slice in IosPythonHomeLayout.slices) assertTrue(slice.stageTaskName in names, "${slice.stageTaskName} missing")
    }

    @Test
    fun linkerOptsAreAddedPerSliceToFrameworksOnly() {
        val project = project()
        wire(project)
        val base = File(project.layout.buildDirectory.get().asFile, "xcode-frameworks/Python.xcframework")
        assertEquals(listOf("-framework", "Python", "-F${File(base, "ios-arm64").absolutePath}"), arm64.getLinkerOpts())
        assertEquals(listOf("-framework", "Python", "-F${File(base, "ios-arm64_x86_64-simulator").absolutePath}"), sim.getLinkerOpts())
        assertEquals(sim.getLinkerOpts(), x64.getLinkerOpts())
        assertEquals(emptyList(), exe.getLinkerOpts(), "only framework binaries are touched")
    }

    @Test
    fun linkPythonFrameworkFalseAddsNothingAndNoDependency() {
        val project = project()
        project.tasks.register("linkDebugFrameworkIosArm64")
        wire(project) { ios.linkPythonFramework.set(false) }
        assertEquals(emptyList(), arm64.getLinkerOpts())
        assertEquals(emptyList(), sim.getLinkerOpts())
        val link = project.tasks.getByName("linkDebugFrameworkIosArm64")
        assertFalse(link.taskDependencies.getDependencies(link).any { it.name == PREPARE_XCFRAMEWORK_TASK })
    }

    /** The documented spelling, `ios { linkPythonFramework.set(false) }`, goes through the block function. */
    @Test
    fun theIosBlockFunctionConfiguresTheSameSetting() {
        val project = project()
        wire(project) { ios { linkPythonFramework.set(false) } }
        assertEquals(emptyList(), arm64.getLinkerOpts())
        assertEquals(emptyList(), sim.getLinkerOpts())
    }

    @Test
    fun frameworkLinkTasksDependOnPrepare() {
        val project = project()
        project.tasks.register("linkDebugFrameworkIosSimulatorArm64")
        wire(project)
        project.tasks.register("linkReleaseFrameworkIosArm64")
        for (n in listOf("linkDebugFrameworkIosSimulatorArm64", "linkReleaseFrameworkIosArm64")) {
            val t = project.tasks.getByName(n)
            assertTrue(t.taskDependencies.getDependencies(t).any { it.name == PREPARE_XCFRAMEWORK_TASK }, n)
        }
    }

    @Test
    fun downloadsGoUnderTheProjectsBuildDirectory() {
        val project = project()
        wire(project)
        val task = project.tasks.getByName(DOWNLOAD_IOS_TASK) as DownloadPythonIosTask
        val build = project.layout.buildDirectory.get().asFile
        assertEquals(File(build, "python-standalone"), task.workDirectory.get().asFile)
        assertTrue(task.extractDirectory.startsWith(File(build, "python-standalone")))
        assertEquals(DEFAULT_IOS_ARCHIVE_SHA256, task.expectedSha256.get())
        assertEquals(64, DEFAULT_IOS_ARCHIVE_SHA256.length)
    }

    @Test
    fun theStageTasksWriteUnderTheProjectsBuildDirectory() {
        val project = project()
        wire(project)
        val build = project.layout.buildDirectory.get().asFile
        val stage = project.tasks.getByName(IosPythonHomeLayout.slices[0].stageTaskName) as StageIosPythonHomeTask
        assertEquals(File(build, "python-ios-home/${IosPythonHomeLayout.slices[0].name}"), stage.destinationDir.get().asFile)
    }

    // ---- download task ------------------------------------------------------------------------

    private fun tarGz(entries: Map<String, String>): ByteArray {
        val tar = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val data = content.toByteArray()
            val h = ByteArray(512)
            fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
            put(0, name)
            put(100, "0000644\u0000")
            put(108, "0000000\u0000")
            put(116, "0000000\u0000")
            put(124, "%011o\u0000".format(data.size))
            put(136, "00000000000\u0000")
            put(148, "        ")
            h[156] = '0'.code.toByte()
            put(257, "ustar\u000000")
            put(148, "%06o\u0000 ".format(h.sumOf { it.toInt() and 0xff }))
            tar.write(h)
            tar.write(data)
            tar.write(ByteArray((512 - data.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tar.toByteArray()) }
        return out.toByteArray()
    }

    private fun downloadTask(archiveBytes: ByteArray, sha: String): Pair<DownloadPythonIosTask, File> {
        val project = project()
        wire(project)
        val src = Files.createTempFile(project.layout.buildDirectory.get().asFile.also { it.mkdirs() }.toPath(), "src", ".tar.gz").toFile()
        src.writeBytes(archiveBytes)
        val task = project.tasks.getByName(DOWNLOAD_IOS_TASK) as DownloadPythonIosTask
        task.archiveUrl.set(src.toURI().toString())
        task.archiveName.set("fake-ios.tar.gz")
        task.expectedSha256.set(sha)
        return task to src
    }

    @Test
    fun aMatchingArchiveIsExtractedUnderTheBuildDirectory() {
        val bytes = tarGz(mapOf("Python.xcframework/Info.plist" to "plist"))
        val src = Files.createTempFile("ios", ".tar.gz").toFile().also { it.writeBytes(bytes) }
        val (task, _) = downloadTask(bytes, sha256Hex(src))
        task.provision()
        assertEquals("plist", File(task.extractDirectory, "Python.xcframework/Info.plist").readText())
        assertTrue(File(task.workDirectory.get().asFile, "fake-ios.tar.gz").isFile)
        src.delete()
    }

    @Test
    fun aChecksumMismatchFailsAndLeavesNoArchive() {
        val bytes = tarGz(mapOf("Python.xcframework/Info.plist" to "plist"))
        val (task, _) = downloadTask(bytes, "0".repeat(64))
        val e = assertFailsWith<GradleException> { task.provision() }
        assertTrue("SHA-256 mismatch" in e.message.orEmpty(), e.message)
        assertFalse(File(task.workDirectory.get().asFile, "fake-ios.tar.gz").exists())
        assertFalse(File(task.extractDirectory, "Python.xcframework").exists())
    }

    // ---- script -------------------------------------------------------------------------------

    @Test
    fun theCopiedScriptIsExecutableAndBakesTheConsumersTask() {
        val project = project()
        wire(project)
        val task = project.tasks.getByName(INSTALL_XCODE_SCRIPT_TASK) as InstallPythonXcodeScriptTask
        task.install()
        val script = task.script.get().asFile
        assertEquals(File(project.layout.buildDirectory.get().asFile, "python-multiplatform/xcode/install-python.sh"), script)
        assertTrue(script.canExecute())
        val text = script.readText()
        assertTrue("\${PYTHON_HOME_TASK:-:stageIosPythonHomeForXcode}" in text, "root project path: ${text.lines().filter { "PYTHON_HOME_TASK" in it }}")
        assertFalse(SCRIPT_LIBRARY_HOME_TASK in text)
    }

    @Test
    fun theBakedTaskPathNamesTheProject() {
        assertEquals(":app:stageIosPythonHomeForXcode", xcodeHomeTaskPath(":app"))
        assertEquals(":stageIosPythonHomeForXcode", xcodeHomeTaskPath(":"))
    }

    @Test
    fun theSliceMapping() {
        assertEquals("ios-arm64", xcframeworkSliceForKonanTarget("ios_arm64"))
        assertEquals("ios-arm64_x86_64-simulator", xcframeworkSliceForKonanTarget("ios_simulator_arm64"))
        assertEquals("ios-arm64_x86_64-simulator", xcframeworkSliceForKonanTarget("ios_x64"))
        assertNull(xcframeworkSliceForKonanTarget("linux_x64"))
        assertNotNull(DEFAULT_IOS_ARCHIVE_URL)
    }
}
