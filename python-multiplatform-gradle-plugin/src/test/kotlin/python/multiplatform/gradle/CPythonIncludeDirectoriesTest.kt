package python.multiplatform.gradle

import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Issue #46: per-target CPython include directories as public providers. */
class CPythonIncludeDirectoriesTest {

    private val version = "3.14.7"

    @Test
    fun layoutMatchesTheObservedAcquisitionTree() {
        val l = CPythonIncludeLayout
        assertEquals("macos-aarch64/python/include/python3.14", l.relativePath("macos-aarch64", CPythonFlavour.GIL, version))
        assertEquals("linux-x86_64-freethreaded/python/include/python3.14t", l.relativePath("linux-x86_64", CPythonFlavour.FREE_THREADED, version))
        assertEquals("windows-x86_64/python/include", l.relativePath("windows-x86_64", CPythonFlavour.GIL, version))
        assertEquals("windows-x86_64-freethreaded/python/include", l.relativePath("windows-x86_64", CPythonFlavour.FREE_THREADED, version))
        assertEquals("android-aarch64/prefix/include/python3.14", l.relativePath("android-aarch64", CPythonFlavour.GIL, version))
        assertEquals("ios/Python.xcframework/ios-arm64/include/python3.14", l.relativePath("ios-arm64", CPythonFlavour.GIL, version))
    }

    @Test
    fun freeThreadedIsDistinctFromDefaultOnEveryDesktopTarget() {
        for (t in CPythonIncludeLayout.desktopTargets) {
            assertNotEquals(
                CPythonIncludeLayout.relativePath(t, CPythonFlavour.GIL, version),
                CPythonIncludeLayout.relativePath(t, CPythonFlavour.FREE_THREADED, version),
            )
        }
    }

    @Test
    fun freeThreadedDoesNotExistOffDesktop() {
        assertFalse(CPythonIncludeLayout.supports("android-aarch64", CPythonFlavour.FREE_THREADED))
        assertFalse(CPythonIncludeLayout.supports("ios-arm64", CPythonFlavour.FREE_THREADED))
        assertFailsWith<IllegalArgumentException> {
            CPythonIncludeLayout.relativePath("ios-arm64", CPythonFlavour.FREE_THREADED, version)
        }
    }

    @Test
    fun downloadTaskNamesMatchTheRootBuild() {
        assertEquals("downloadPython_macos_aarch64", CPythonIncludeLayout.downloadTaskName("macos-aarch64"))
        assertEquals("downloadPython_android_x86_64", CPythonIncludeLayout.downloadTaskName("android-x86_64"))
        assertEquals("downloadPython_ios", CPythonIncludeLayout.downloadTaskName("ios-arm64_x86_64-simulator"))
    }

    /** Registry over fake extraction tasks that really create `Python.h`; only [configured] desktop flavour is extracted. */
    private fun fixture(configured: CPythonFlavour): Pair<org.gradle.api.Project, CPythonIncludeDirectories> {
        val project = ProjectBuilder.builder().build()
        val root = project.layout.projectDirectory.dir("extracted/$version")
        val tasks = CPythonIncludeLayout.allTargets.associateWith { target ->
            // Android and iOS are always GIL; desktop follows the configured flavour.
            val flavour = if (target in CPythonIncludeLayout.desktopTargets) configured else CPythonFlavour.GIL
            project.tasks.register("fake_${target.replace('-', '_')}") {
                doLast {
                    root.dir(CPythonIncludeLayout.relativePath(target, flavour, version)).asFile
                        .also { it.mkdirs() }.resolve("Python.h").writeText("/* $target */")
                }
            }
        }
        val dirs = CPythonIncludeDirectories(root, version) { target, flavour ->
            val extracted = if (target in CPythonIncludeLayout.desktopTargets) configured else CPythonFlavour.GIL
            if (flavour == extracted) tasks[target] else null
        }
        return project to dirs
    }

    @Test
    fun everyTargetResolvesToADistinctDirectoryHoldingPythonH() {
        val (project, dirs) = fixture(CPythonFlavour.GIL)
        val seen = mutableSetOf<File>()
        for (t in dirs.targets) {
            val producer = project.tasks.getByName("fake_${t.replace('-', '_')}")
            val provider = dirs.includeDir(t)
            // A consumer that takes the provider as an input must depend on the extraction task.
            val consumer = project.tasks.register("use_${t.replace('-', '_')}") { inputs.dir(provider) }.get()
            assertTrue(
                producer in consumer.taskDependencies.getDependencies(consumer),
                "$t: provider does not carry its extraction task",
            )
            producer.actions.forEach { it.execute(producer) }
            val dir = provider.get().asFile
            assertTrue(File(dir, "Python.h").isFile, "$t: Python.h missing in $dir")
            assertTrue(seen.add(dir), "$t: duplicate directory $dir")
        }
    }

    @Test
    fun theOtherFlavourFailsLoudlyNamingTheProperty() {
        val (_, dirs) = fixture(CPythonFlavour.GIL)
        assertFalse(dirs.isAvailable("macos-aarch64", CPythonFlavour.FREE_THREADED))
        val e = assertFailsWith<GradleException> { dirs.includeDir("macos-aarch64", CPythonFlavour.FREE_THREADED) }
        assertTrue("pythonFreeThreaded" in e.message.orEmpty())
        assertFailsWith<GradleException> { dirs.includeDir("ios-arm64", CPythonFlavour.FREE_THREADED) }
    }

    @Test
    fun configuredFreeThreadedExposesFreeThreadedKey() {
        val (_, dirs) = fixture(CPythonFlavour.FREE_THREADED)
        assertTrue(dirs.isAvailable("linux-x86_64", CPythonFlavour.FREE_THREADED))
        assertTrue(dirs.includeDir("linux-x86_64", CPythonFlavour.FREE_THREADED).get().asFile.path.endsWith("python3.14t"))
    }
}
