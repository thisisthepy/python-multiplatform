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
        // #313: the iOS archive has headers only inside the framework.
        assertEquals("ios/Python.xcframework/ios-arm64/Python.framework/Headers", l.relativePath("ios-arm64", CPythonFlavour.FREE_THREADED, version))
        assertEquals(
            "ios/Python.xcframework/ios-arm64_x86_64-simulator/Python.framework/Headers",
            l.relativePath("ios-arm64_x86_64-simulator", CPythonFlavour.FREE_THREADED, version),
        )
        // #328: the 3.15t Android archive (`prefix/include/python3.15t`), unpacked beside a `-freethreaded` suffix.
        assertEquals("android-aarch64-freethreaded/prefix/include/python3.14t", l.relativePath("android-aarch64", CPythonFlavour.FREE_THREADED, version))
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

    /**
     * Issue #328: Android and iOS embed only the project's own 3.15t builds; there is no GIL mobile
     * build to lay out. Red before: `supports` allowed only GIL off desktop, so the free-threaded
     * assertions below failed, and the GIL ones passed the other way round.
     */
    @Test
    fun mobileHasOnlyTheFreeThreadedBuild() {
        for (t in CPythonIncludeLayout.androidTargets + CPythonIncludeLayout.iosTargets) {
            assertTrue(CPythonIncludeLayout.supports(t, CPythonFlavour.FREE_THREADED), t)
            assertFalse(CPythonIncludeLayout.supports(t, CPythonFlavour.GIL), t)
        }
        assertFailsWith<IllegalArgumentException> {
            CPythonIncludeLayout.relativePath("ios-arm64", CPythonFlavour.GIL, version)
        }
        assertFailsWith<IllegalArgumentException> {
            CPythonIncludeLayout.relativePath("android-x86_64", CPythonFlavour.GIL, version)
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
            // Android and iOS are always free-threaded (#328); desktop follows the configured flavour.
            val flavour = if (target in CPythonIncludeLayout.desktopTargets) configured else CPythonFlavour.FREE_THREADED
            project.tasks.register("fake_${target.replace('-', '_')}") {
                doLast {
                    root.dir(CPythonIncludeLayout.relativePath(target, flavour, version)).asFile
                        .also { it.mkdirs() }.resolve("Python.h").writeText("/* $target */")
                }
            }
        }
        val dirs = CPythonIncludeDirectories(root, version) { target, flavour ->
            val extracted = if (target in CPythonIncludeLayout.desktopTargets) configured else CPythonFlavour.FREE_THREADED
            if (flavour == extracted) tasks[target] else null
        }
        return project to dirs
    }

    @Test
    fun everyTargetResolvesToADistinctDirectoryHoldingPythonH() {
        // FREE_THREADED is the desktop flavour the library build extracts (#158).
        val (project, dirs) = fixture(CPythonFlavour.FREE_THREADED)
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
    fun theOtherFlavourFailsLoudlyNamingTheDecision() {
        val (_, dirs) = fixture(CPythonFlavour.FREE_THREADED)
        assertFalse(dirs.isAvailable("macos-aarch64", CPythonFlavour.GIL))
        val e = assertFailsWith<GradleException> { dirs.includeDir("macos-aarch64", CPythonFlavour.GIL) }
        assertTrue("#158" in e.message.orEmpty(), e.message)
        val ios = assertFailsWith<GradleException> { dirs.includeDir("ios-arm64", CPythonFlavour.GIL) }
        assertTrue("#328" in ios.message.orEmpty(), ios.message)
    }

    /**
     * Issues #158 and #328: every target embeds only the free-threaded build, so the default
     * flavour of every accessor is free-threaded. Red before #328: `embeddedFlavour` was GIL for
     * Android and iOS, so the second loop and the Android path below failed.
     */
    @Test
    fun theDefaultFlavourIsTheOneTheLibraryEmbeds() {
        for (t in CPythonIncludeLayout.allTargets) {
            assertEquals(CPythonFlavour.FREE_THREADED, CPythonIncludeLayout.embeddedFlavour(t), t)
        }
        val (_, dirs) = fixture(CPythonFlavour.FREE_THREADED)
        assertTrue(dirs.isAvailable("macos-aarch64"))
        val root = dirs.includeDir("macos-aarch64").get().asFile.parentFile.parentFile.parentFile.parentFile
        assertEquals(File(root, "macos-aarch64-freethreaded/python/include/python3.14t"), dirs.includeDir("macos-aarch64").get().asFile)
        assertEquals(File(root, "android-aarch64-freethreaded/prefix/include/python3.14t"), dirs.includeDir("android-aarch64").get().asFile)
        assertEquals("python314t.lib", dirs.libraryFile("windows-x86_64").get().asFile.name)
        assertEquals("libpython3.14t.so", dirs.libraryFile("android-x86_64").get().asFile.name)
    }

    @Test
    fun configuredFreeThreadedExposesFreeThreadedKey() {
        val (_, dirs) = fixture(CPythonFlavour.FREE_THREADED)
        assertTrue(dirs.isAvailable("linux-x86_64", CPythonFlavour.FREE_THREADED))
        assertTrue(dirs.includeDir("linux-x86_64", CPythonFlavour.FREE_THREADED).get().asFile.path.endsWith("python3.14t"))
    }

    // ---- Issue #56: link-library directory ----

    @Test
    fun linkLibraryLayoutMatchesTheObservedTree() {
        val l = CPythonIncludeLayout
        assertEquals("android-aarch64-freethreaded/prefix/lib", l.linkLibraryDirRelativePath("android-aarch64", CPythonFlavour.FREE_THREADED))
        assertEquals("android-x86_64-freethreaded/prefix/lib", l.linkLibraryDirRelativePath("android-x86_64", CPythonFlavour.FREE_THREADED))
        assertEquals("windows-x86_64/python/libs", l.linkLibraryDirRelativePath("windows-x86_64", CPythonFlavour.GIL))
        assertEquals("windows-x86_64-freethreaded/python/libs", l.linkLibraryDirRelativePath("windows-x86_64", CPythonFlavour.FREE_THREADED))
        assertEquals("libpython3.14t.so", l.linkLibraryFileName("android-aarch64", CPythonFlavour.FREE_THREADED, version))
        assertEquals("python314.lib", l.linkLibraryFileName("windows-x86_64", CPythonFlavour.GIL, version))
        assertEquals("python314t.lib", l.linkLibraryFileName("windows-x86_64", CPythonFlavour.FREE_THREADED, version))
    }

    @Test
    fun linkLibraryIsRequiredOnlyOnAndroidAndWindows() {
        val l = CPythonIncludeLayout
        for (t in listOf("android-aarch64", "android-x86_64", "windows-x86_64")) assertTrue(l.isLinkRequired(t), t)
        for (t in listOf("macos-aarch64", "macos-x86_64", "linux-x86_64", "ios-arm64", "ios-arm64_x86_64-simulator")) {
            assertFalse(l.isLinkRequired(t), t)
        }
    }

    @Test
    fun linkLibraryAndroidIsFreeThreadedOnly() {
        val l = CPythonIncludeLayout
        assertTrue(l.supportsLinkLibrary("windows-x86_64", CPythonFlavour.FREE_THREADED))
        assertTrue(l.supportsLinkLibrary("windows-x86_64", CPythonFlavour.GIL))
        // #328: Android links `libpython3.15t.so`; there is no GIL Android build.
        assertTrue(l.supportsLinkLibrary("android-aarch64", CPythonFlavour.FREE_THREADED))
        assertFalse(l.supportsLinkLibrary("android-x86_64", CPythonFlavour.GIL))
        assertFalse(l.supportsLinkLibrary("macos-aarch64", CPythonFlavour.GIL))
        assertFailsWith<IllegalArgumentException> { l.linkLibraryDirRelativePath("macos-aarch64", CPythonFlavour.GIL) }
    }

    @Test
    fun linkLibraryProvidersCarryTheExtractionTaskAndResolve() {
        val (project, dirs) = fixture(CPythonFlavour.FREE_THREADED)
        val root = project.layout.projectDirectory.dir("extracted/$version")
        for (t in dirs.targets.filter { dirs.isLinkRequired(it) }) {
            val producer = project.tasks.getByName("fake_${t.replace('-', '_')}")
            val dirProvider = dirs.libraryDir(t)
            val fileProvider = dirs.libraryFile(t)
            val consumer = project.tasks.register("link_${t.replace('-', '_')}") { inputs.dir(dirProvider) }.get()
            assertTrue(producer in consumer.taskDependencies.getDependencies(consumer), "$t: libraryDir lacks task")
            val fileConsumer = project.tasks.register("linkf_${t.replace('-', '_')}") { inputs.file(fileProvider) }.get()
            assertTrue(producer in fileConsumer.taskDependencies.getDependencies(fileConsumer), "$t: libraryFile lacks task")
            // Simulate the extraction creating the library.
            val flavour = CPythonIncludeLayout.embeddedFlavour(t)
            val expected = root.dir(CPythonIncludeLayout.linkLibraryDirRelativePath(t, flavour)).asFile
            expected.mkdirs()
            File(expected, CPythonIncludeLayout.linkLibraryFileName(t, flavour, version)).writeText("lib")
            assertEquals(expected, dirProvider.get().asFile)
            assertEquals(expected.resolve(CPythonIncludeLayout.linkLibraryFileName(t, flavour, version)), fileProvider.get().asFile)
        }
    }

    @Test
    fun linkLibraryFailsLoudlyWhenNotRequiredOrWrongFlavour() {
        val (_, dirs) = fixture(CPythonFlavour.FREE_THREADED)
        assertFalse(dirs.isLinkRequired("macos-aarch64"))
        assertFailsWith<GradleException> { dirs.libraryDir("macos-aarch64") }
        assertFailsWith<GradleException> { dirs.libraryFile("ios-arm64") }
        assertFalse(dirs.isLinkAvailable("windows-x86_64", CPythonFlavour.GIL))
        val e = assertFailsWith<GradleException> { dirs.libraryDir("windows-x86_64", CPythonFlavour.GIL) }
        assertTrue("#158" in e.message.orEmpty(), e.message)
        assertFailsWith<GradleException> { dirs.libraryDir("android-aarch64", CPythonFlavour.GIL) }
    }

    @Test
    fun linkLibraryWindowsFreeThreadedWhenConfigured() {
        val (_, dirs) = fixture(CPythonFlavour.FREE_THREADED)
        assertTrue(dirs.isLinkAvailable("windows-x86_64", CPythonFlavour.FREE_THREADED))
        assertTrue(dirs.libraryFile("windows-x86_64", CPythonFlavour.FREE_THREADED).get().asFile.name == "python314t.lib")
    }
}
