package python.multiplatform.gradle

import org.gradle.api.DomainObjectSet
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework_Decorated
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #90: an app outside this repository that applies the plugin gets Python.xcframework, the
 * Kotlin framework's linker flags, the iOS stdlib staging and the Xcode script -- SPEC L-11.
 *
 * Expected red before the implementation: this file does not compile (`IosSupportArchive`,
 * `IosPythonXcframework`, `AcquireIosPythonSupportTask`, `IosPythonHomeTasks`,
 * `WriteIosInstallPythonScriptTask`, `configureIosPython`, `PINNED_IOS_SUPPORT_SHA256` and
 * `extractTarGzMaterialisingLinks`' `include` parameter do not exist) -- a compile failure of this
 * test source set, not an assertion failure, so it cannot be mistaken for a regression of something
 * that used to pass.
 */
class IosConsumerWiringTest {

    // Under this module's own build directory (the test task's working directory): AGENTS.md rule 2.
    private val scratch: File = File(
        System.getProperty("user.dir"),
        "build/tmp/iosConsumerWiringTest/${java.util.UUID.randomUUID()}",
    ).apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private fun project(): Project = ProjectBuilder.builder()
        .withProjectDir(File(scratch, "consumer").apply { mkdirs() })
        .withGradleUserHomeDir(File(scratch, "gradle-home"))
        .build()

    /** The root lockfile, read directly: what the generated table must equal. */
    private fun lockfileIosEntries(): Map<String, String> {
        val file = File("../python-checksums.properties")
        assertTrue(file.isFile, "the test runs from the plugin module, beside ../python-checksums.properties")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return props.stringPropertyNames().filter { it.startsWith("ios-") }.associateWith { props.getProperty(it).trim() }
    }

    // ---- one source for the pins ----

    @Test
    fun thePinnedIosChecksumsAreExactlyTheRootLockfilesIosEntries() {
        val expected = lockfileIosEntries()
        assertTrue(expected.isNotEmpty(), "python-checksums.properties has no ios-* entry")
        assertEquals(expected, PINNED_IOS_SUPPORT_SHA256)
    }

    @Test
    fun theArchiveForEachVersionIsTheOneTheLibraryBuildDownloads() {
        assertEquals(
            IosSupportArchive.Pin(
                archiveName = "Python-3.14-iOS-support.b11.tar.gz",
                url = "https://github.com/beeware/Python-Apple-support/releases/download/3.14-b11/Python-3.14-iOS-support.b11.tar.gz",
                lockKey = "ios-3.14-b11",
                fromPythonOrg = false,
            ),
            IosSupportArchive.forVersion("3.14.7", "b11"),
        )
        // 3.15+: python.org, filed under the final version's directory, build tag ignored.
        assertEquals(
            IosSupportArchive.Pin(
                archiveName = "python-3.15.0rc1-iOS-XCframework.tar.gz",
                url = "https://www.python.org/ftp/python/3.15.0/python-3.15.0rc1-iOS-XCframework.tar.gz",
                lockKey = "ios-3.15.0rc1-pythonorg",
                fromPythonOrg = true,
            ),
            IosSupportArchive.forVersion("3.15.0rc1", "b11"),
        )
    }

    @Test
    fun theDefaultVersionHasAPinnedArchive() {
        val pin = IosSupportArchive.forVersion(DEFAULT_PYTHON_VERSION, DEFAULT_PYTHON_APPLE_SUPPORT_BUILD)
        assertEquals(lockfileIosEntries()[pin.lockKey], IosSupportArchive.pinnedSha256(pin.lockKey))
        assertNotNull(IosSupportArchive.pinnedSha256(pin.lockKey), "no pin for ${pin.lockKey}")
    }

    @Test
    fun onlyTheXcframeworkIsExtractedFromTheArchive() {
        assertTrue(IosSupportArchive.isXcframeworkEntry("Python.xcframework"))
        assertTrue(IosSupportArchive.isXcframeworkEntry("Python.xcframework/Info.plist"))
        assertTrue(IosSupportArchive.isXcframeworkEntry("./Python.xcframework/ios-arm64/Python.framework/Python"))
        assertFalse(IosSupportArchive.isXcframeworkEntry("testbed/Python.xcframework"))
        assertFalse(IosSupportArchive.isXcframeworkEntry("Python.xcframework-other/x"))
        assertFalse(IosSupportArchive.isXcframeworkEntry("VERSIONS"))
    }

    // ---- the tasks a consumer gets ----

    @Test
    fun aConsumerGetsEveryIosTaskWiredToTheGradleUserHomeCache() {
        val project = project()
        val extension = project.extensions.create("pythonBindings", PythonBindingsExtension::class.java)
        PythonBindingsPlugin().configureIosPython(project, extension)

        val pin = IosSupportArchive.forVersion(DEFAULT_PYTHON_VERSION, DEFAULT_PYTHON_APPLE_SUPPORT_BUILD)
        val cache = File(scratch, "gradle-home/${IosSupportArchive.CACHE_DIRECTORY}/${pin.lockKey}")

        val acquire = project.tasks.getByName(ACQUIRE_IOS_SUPPORT_TASK) as AcquireIosPythonSupportTask
        assertEquals(pin.archiveName, acquire.archiveName.get())
        assertEquals(pin.url, acquire.url.get())
        assertEquals(pin.lockKey, acquire.lockKey.get())
        assertEquals(lockfileIosEntries().getValue(pin.lockKey), acquire.sha256.get())
        assertEquals(DEFAULT_PYTHON_VERSION, acquire.pythonVersion.get())
        assertEquals(cache.canonicalFile, acquire.destinationDir.get().asFile.canonicalFile)
        assertEquals(
            File(scratch, "gradle-home/${IosSupportArchive.CACHE_DIRECTORY}/archives").canonicalFile,
            acquire.downloadDir.get().asFile.canonicalFile,
        )

        val stage = project.tasks.getByName(STAGE_IOS_XCFRAMEWORK_TASK) as Sync
        assertEquals(
            File(project.layout.buildDirectory.get().asFile, "xcode-frameworks/Python.xcframework").canonicalFile,
            stage.destinationDir.canonicalFile,
        )
        assertTrue(ACQUIRE_IOS_SUPPORT_TASK in dependencyNames(project, STAGE_IOS_XCFRAMEWORK_TASK))

        for (slice in IosPythonHomeLayout.slices) {
            val task = project.tasks.getByName(slice.stageTaskName) as StageIosPythonHomeTask
            assertEquals(File(cache, "Python.xcframework").canonicalFile, task.xcframework.get().asFile.canonicalFile)
            assertEquals(slice.name, task.sliceName.get())
            assertEquals(DEFAULT_PYTHON_VERSION, task.pythonVersion.get())
            assertEquals(
                File(project.layout.buildDirectory.get().asFile, "python-ios-home/${slice.name}").canonicalFile,
                task.destinationDir.get().asFile.canonicalFile,
            )
            assertTrue(ACQUIRE_IOS_SUPPORT_TASK in dependencyNames(project, slice.stageTaskName))
        }
        assertEquals(
            IosPythonHomeLayout.slices.map { it.stageTaskName }.toSet(),
            dependencyNames(project, IosPythonHomeTasks.ALL_SLICES_TASK),
        )
        assertNotNull(project.tasks.findByName(IosPythonHomeTasks.FOR_XCODE_TASK))

        val write = project.tasks.getByName(WriteIosInstallPythonScriptTask.NAME) as WriteIosInstallPythonScriptTask
        assertEquals(":stageIosPythonHomeForXcode", write.homeTaskPath.get())
        assertEquals(
            File(project.layout.buildDirectory.get().asFile, "python-multiplatform/xcode/install-python.sh").canonicalFile,
            write.scriptFile.get().asFile.canonicalFile,
        )
    }

    @Test
    fun theExtensionIsReadLazilyAndAnUnpinnedBuildIsCarriedAsEmpty() {
        val project = project()
        val extension = project.extensions.create("pythonBindings", PythonBindingsExtension::class.java)
        PythonBindingsPlugin().configureIosPython(project, extension)
        // After the plugin ran, as a `pythonBindings { }` block below `plugins { }` would.
        extension.pythonAppleSupportBuild.set("b99")

        val acquire = project.tasks.getByName(ACQUIRE_IOS_SUPPORT_TASK) as AcquireIosPythonSupportTask
        val tag = IosPythonHomeLayout.stdlibTag(DEFAULT_PYTHON_VERSION)
        if (IosSupportArchive.isFromPythonOrg(DEFAULT_PYTHON_VERSION)) return // the build tag does not apply
        assertEquals("ios-$tag-b99", acquire.lockKey.get())
        assertEquals("", acquire.sha256.get())
    }

    @Test
    fun aSubprojectsScriptDefaultsToItsOwnStagingTask() {
        val root = project()
        val app = ProjectBuilder.builder().withName("app").withParent(root)
            .withProjectDir(File(scratch, "consumer/app").apply { mkdirs() }).build()
        val extension = app.extensions.create("pythonBindings", PythonBindingsExtension::class.java)
        PythonBindingsPlugin().configureIosPython(app, extension)

        val write = app.tasks.getByName(WriteIosInstallPythonScriptTask.NAME) as WriteIosInstallPythonScriptTask
        assertEquals(":app:stageIosPythonHomeForXcode", write.homeTaskPath.get())
        write.write()

        val script = write.scriptFile.get().asFile
        val text = script.readText()
        assertTrue("home_task=\"\${PYTHON_HOME_TASK:-:app:stageIosPythonHomeForXcode}\"" in text, text.take(400))
        assertFalse("\${PYTHON_HOME_TASK:-:python-multiplatform:" in text)
        // The payload half of the contract is the repository script's, untouched.
        assertTrue("\${PYTHON_PAYLOAD_DIR:-}" in text)
        assertTrue("PYTHON_PAYLOAD_TASK" in text)
        assertTrue(script.canExecute())
    }

    @Test
    fun theScriptInThePluginJarIsTheRepositoryScript() {
        assertEquals(File("../tools/xcode/install-python.sh").readText(), bundledInstallPythonScript())
    }

    @Test
    fun renderingFailsWhenTheDefaultItReplacesIsGone() {
        assertFailsWith<GradleException> { renderInstallPythonScript("#!/bin/sh\necho hi\n", ":stageIosPythonHomeForXcode") }
    }

    @Test
    fun theXcodeOutputIsTheContractInstallPythonParses() {
        val root = File(scratch, "python-ios-home")
        val slice = IosPythonHomeLayout.sliceFor("-iphonesimulator", "arm64")
        assertEquals(
            listOf(
                "PYTHON_HOME_DIR=" + File(root, "iphonesimulator-arm64/home").absolutePath,
                "PYTHON_DYLIB_INFO_TEMPLATE=" + File(root, "iphonesimulator-arm64/dylib-Info-template.plist").absolutePath,
            ),
            IosPythonHomeTasks.xcodeOutputLines(root, slice),
        )
    }

    // ---- linkerOpts on Kotlin/Native iOS frameworks ----

    @Test
    fun everyIosFrameworkBinaryLinksPythonFromItsSliceAndItsLinkWaitsForTheStaging() {
        val project = project()
        project.tasks.register(STAGE_IOS_XCFRAMEWORK_TASK)
        project.tasks.register(WriteIosInstallPythonScriptTask.NAME)
        val staged = File(project.layout.buildDirectory.get().asFile, "xcode-frameworks/Python.xcframework")

        val targets = project.objects.domainObjectSet(Any::class.java)
        val simulatorBinaries = project.objects.domainObjectSet(Any::class.java)
        targets.add(FakeNativeTarget(FakeKonanTarget("ios_simulator_arm64"), simulatorBinaries))

        IosPythonXcframework.wireFrameworks(
            project = project,
            kotlin = FakeKotlinExtension(targets),
            stagedXcframework = staged,
            linkDependencies = listOf(STAGE_IOS_XCFRAMEWORK_TASK, WriteIosInstallPythonScriptTask.NAME),
        )

        // Declared after the wiring, as `kotlin { }` below `plugins { }` declares them.
        val deviceBinaries = project.objects.domainObjectSet(Any::class.java)
        val androidBinaries = project.objects.domainObjectSet(Any::class.java)
        targets.add(FakeNativeTarget(FakeKonanTarget("ios_arm64"), deviceBinaries))
        targets.add(FakeNativeTarget(FakeKonanTarget("android_arm64"), androidBinaries))
        targets.add(FakeJvmTarget())

        val simulatorFramework = Framework_Decorated("linkDebugFrameworkIosSimulatorArm64")
        val simulatorTest = TestExecutable("linkDebugTestIosSimulatorArm64")
        val deviceFramework = Framework("linkReleaseFrameworkIosArm64")
        val androidLibrary = Framework("linkDebugSharedAndroidNativeArm64")
        simulatorBinaries.add(simulatorFramework)
        simulatorBinaries.add(simulatorTest)
        deviceBinaries.add(deviceFramework)
        androidBinaries.add(androidLibrary)

        assertEquals(
            listOf("-framework", "Python", "-F" + File(staged, "ios-arm64_x86_64-simulator").absolutePath),
            simulatorFramework.linkerOpts,
        )
        assertEquals(listOf("-framework", "Python", "-F" + File(staged, "ios-arm64").absolutePath), deviceFramework.linkerOpts)
        assertTrue(simulatorTest.linkerOpts.isEmpty(), "a test executable is not a framework: ${simulatorTest.linkerOpts}")
        assertTrue(androidLibrary.linkerOpts.isEmpty(), "androidNative is not iOS: ${androidLibrary.linkerOpts}")

        listOf("linkDebugFrameworkIosSimulatorArm64", "linkReleaseFrameworkIosArm64", "linkDebugTestIosSimulatorArm64")
            .forEach { project.tasks.register(it) }
        val wanted = setOf(STAGE_IOS_XCFRAMEWORK_TASK, WriteIosInstallPythonScriptTask.NAME)
        assertEquals(wanted, dependencyNames(project, "linkDebugFrameworkIosSimulatorArm64"))
        assertEquals(wanted, dependencyNames(project, "linkReleaseFrameworkIosArm64"))
        assertEquals(emptySet(), dependencyNames(project, "linkDebugTestIosSimulatorArm64"))
    }

    @Test
    fun konanTargetsMapToTheirXcframeworkSlices() {
        assertEquals("ios-arm64", IosPythonXcframework.sliceForKonanTarget("ios_arm64"))
        assertEquals("ios-arm64_x86_64-simulator", IosPythonXcframework.sliceForKonanTarget("ios_simulator_arm64"))
        assertEquals("ios-arm64_x86_64-simulator", IosPythonXcframework.sliceForKonanTarget("ios_x64"))
        assertEquals(null, IosPythonXcframework.sliceForKonanTarget("macos_arm64"))
        assertEquals(null, IosPythonXcframework.sliceForKonanTarget("android_arm64"))
    }

    // ---- acquisition, against a fake archive (no network) ----

    @Test
    fun acquisitionVerifiesExtractsOnlyTheXcframeworkMaterialisesLinksAndStamps() {
        val binary = ByteArray(3000) { (it % 251).toByte() }
        val archive = fakeIosArchive(binary, pyVersion = "3.14.7")
        val task = acquireTask(archive, sha256 = sha256(archive))

        task.acquire()

        val root = task.destinationDir.get().asFile
        assertTrue(File(root, IosSupportArchive.STAMP_NAME).isFile)
        assertTrue(File(root, "Python.xcframework/Info.plist").isFile)
        assertFalse(File(root, "testbed").exists(), "testbed/ must not be extracted")
        val link = File(root, "Python.xcframework/ios-arm64/lib/libpython3.14.dylib")
        assertFalse(Files.isSymbolicLink(link.toPath()))
        assertContentEquals(binary, link.readBytes())
        val downloads = task.downloadDir.get().asFile
        assertTrue(File(downloads, "fake-ios.tar.gz").isFile)
        assertFalse(File(downloads, "fake-ios.tar.gz.part").exists())

        // A matching stamp short-circuits: no download is attempted (the source is gone).
        archive.delete()
        File(downloads, "fake-ios.tar.gz").delete()
        task.acquire()
        assertTrue(File(root, "Python.xcframework/Info.plist").isFile)
    }

    @Test
    fun aChecksumMismatchFailsAndLeavesNothingBehind() {
        val archive = fakeIosArchive(ByteArray(10), pyVersion = "3.14.7")
        val task = acquireTask(archive, sha256 = "0".repeat(64))
        val e = assertFailsWith<GradleException> { task.acquire() }
        assertTrue("checksum mismatch" in e.message.orEmpty(), e.message)
        val downloads = task.downloadDir.get().asFile
        assertFalse(File(downloads, "fake-ios.tar.gz").exists())
        assertFalse(File(downloads, "fake-ios.tar.gz.part").exists())
        assertFalse(File(task.destinationDir.get().asFile, "Python.xcframework").exists())
    }

    @Test
    fun anArchiveCarryingAnotherCPythonFails() {
        val archive = fakeIosArchive(ByteArray(10), pyVersion = "3.14.6")
        val task = acquireTask(archive, sha256 = sha256(archive))
        val e = assertFailsWith<GradleException> { task.acquire() }
        assertTrue("PY_VERSION 3.14.6" in e.message.orEmpty(), e.message)
        assertFalse(File(task.destinationDir.get().asFile, IosSupportArchive.STAMP_NAME).exists())
    }

    @Test
    fun anUnpinnedArchiveIsRefusedBeforeAnyDownload() {
        val task = acquireTask(File(scratch, "does-not-exist.tar.gz"), sha256 = "")
        val e = assertFailsWith<GradleException> { task.acquire() }
        assertTrue("ios-test" in e.message.orEmpty(), e.message)
        assertFalse(task.downloadDir.get().asFile.resolve("fake-ios.tar.gz").exists())
    }

    // ---- helpers ----

    private fun dependencyNames(project: Project, taskName: String): Set<String> {
        val task = project.tasks.getByName(taskName)
        return task.taskDependencies.getDependencies(task).map { it.name }.toSet()
    }

    private fun acquireTask(archive: File, sha256: String): AcquireIosPythonSupportTask {
        val task = project().tasks.create("acquire", AcquireIosPythonSupportTask::class.java)
        task.pythonVersion.set("3.14.7")
        task.archiveName.set("fake-ios.tar.gz")
        task.url.set(archive.toURI().toString())
        task.lockKey.set("ios-test")
        task.sha256.set(sha256)
        task.destinationDir.set(File(scratch, "cache/ios-test"))
        task.downloadDir.set(File(scratch, "cache/archives"))
        return task
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun entry(out: ByteArrayOutputStream, name: String, type: Char, data: ByteArray = ByteArray(0), link: String = "") {
        val h = ByteArray(512)
        fun put(off: Int, s: String) = s.toByteArray().copyInto(h, off)
        put(0, name)
        put(100, "0000755\u0000")
        put(124, String.format("%011o\u0000", data.size))
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar\u000000")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        put(148, String.format("%06o\u0000 ", h.sumOf { it.toInt() and 0xff }))
        out.write(h)
        out.write(data)
        out.write(ByteArray((512 - data.size % 512) % 512))
    }

    /** The observed BeeWare 3.14-b11 shape, a few entries deep, including its three links. */
    private fun fakeIosArchive(binary: ByteArray, pyVersion: String): File {
        val patchlevel = "#define PY_VERSION \"$pyVersion\"\n".toByteArray()
        val tar = ByteArrayOutputStream()
        entry(tar, "Python.xcframework/", '5')
        entry(tar, "Python.xcframework/Info.plist", '0', "<plist/>".toByteArray())
        entry(tar, "Python.xcframework/ios-arm64/Python.framework/Python", '0', binary)
        entry(tar, "Python.xcframework/ios-arm64/Python.framework/Headers/patchlevel.h", '0', patchlevel)
        entry(tar, "Python.xcframework/ios-arm64/lib/libpython3.14.dylib", '2', link = "../Python.framework/Python")
        entry(tar, "Python.xcframework/lib/python3.14/os.py", '0', "# os\n".toByteArray())
        entry(tar, "testbed/", '5')
        entry(tar, "testbed/__main__.py", '0', "# testbed\n".toByteArray())
        entry(tar, "testbed/Python.xcframework", '2', link = "../Python.xcframework")
        tar.write(ByteArray(1024))
        val f = File(scratch, "source-$pyVersion-${binary.size}.tar.gz")
        GZIPOutputStream(f.outputStream()).use { it.write(tar.toByteArray()) }
        return f
    }
}

/** Test doubles of the KGP shapes `IosPythonXcframework.wireFrameworks` reads reflectively. */
class FakeKonanTarget(val name: String)

class FakeNativeTarget(val konanTarget: FakeKonanTarget, val binaries: DomainObjectSet<Any>)

/** A JVM target: no `getKonanTarget()`. */
class FakeJvmTarget

class FakeKotlinExtension(val targets: DomainObjectSet<Any>)
