package python.multiplatform.gradle

import org.gradle.api.tasks.Sync
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC L-9 (issue #60): what a packaged desktop application carries of the staged CPython prefix,
 * and which tasks carry it.
 *
 * The run-time half -- the library finding that directory and handing it to CPython -- is
 * `python-multiplatform/src/desktopTest/.../env/PackagedPythonHome*Test.kt`. Neither half can see
 * the other: this file pins the directory name the library looks for as a literal for that reason.
 */
class PackagedPythonHomeTest {

    // ---- what is copied ----

    @Test
    fun theDirectoryNameIsTheOneTheLibraryLooksFor() {
        // `PackagedPythonHome.DIRECTORY_NAME` in `python-multiplatform/src/jvmMain`. The two builds
        // share no code, so a rename on one side only would package a prefix nothing finds -- and
        // the app would still start whenever the developer's shell happened to have PYTHONHOME.
        assertEquals("python-multiplatform-home", PACKAGED_HOME_DIRECTORY)
    }

    @Test
    fun aUnixPrefixShipsTheStdlibAndTheSharedLibraryOnly() {
        val includes = packagedPrefixIncludes("3.14.7", freeThreaded = false, windows = false)
        assertEquals(listOf("lib/python3.14/**", "lib/libpython3.14.*"), includes)
    }

    @Test
    fun aFreeThreadedPrefixUsesTheTaggedDirectoryAndLibrary() {
        // A 3.14t install has `lib/python3.14t/` and `libpython3.14t.dylib` and nothing untagged,
        // so the untagged patterns would package an empty tree.
        val includes = packagedPrefixIncludes("3.14.7", freeThreaded = true, windows = false)
        assertEquals(listOf("lib/python3.14t/**", "lib/libpython3.14t.*"), includes)
    }

    @Test
    fun aWindowsPrefixShipsLibDllsAndTheRootDlls() {
        // python-build-standalone's Windows layout: `Lib/` (pure Python), `DLLs/` (extension
        // modules), and `python3*.dll` plus the MSVC runtime at the prefix root, which is where
        // `manager.libraryUnder` looks for the interpreter on that layout.
        val includes = packagedPrefixIncludes("3.14.7", freeThreaded = false, windows = true)
        assertEquals(listOf("Lib/**", "DLLs/**", "*.dll"), includes)
    }

    @Test
    fun bytecodeCachesAreNeverPackaged() {
        // The staged prefix is shared by every project on the machine and accumulates
        // `__pycache__` from whatever ran against it; copying that would make the app's contents
        // depend on the developer's history.
        assertTrue("**/__pycache__/**" in PACKAGED_PREFIX_EXCLUDES)
    }

    // ---- which tasks ----

    @Test
    fun composeAppResourcesTasksAreRecognisedForEveryBuildType() {
        assertTrue(isComposeAppResourcesTask("prepareAppResources"))
        assertTrue(isComposeAppResourcesTask("prepareReleaseAppResources"))
        assertFalse(isComposeAppResourcesTask("processResources"))
        assertFalse(isComposeAppResourcesTask("desktopProcessResources"))
        assertFalse(isComposeAppResourcesTask("prepareAppResourcesFoo"))
    }

    // ---- wiring, against a fake staged prefix ----

    @Test
    fun theComposeResourcesTaskCopiesTheStagedPrefixSubsetAndDependsOnStaging() {
        val fixture = Fixture()
        val sync = fixture.syncTask()

        val dependencies = sync.taskDependencies.getDependencies(sync).map { it.name }
        assertTrue("stagePythonHome" in dependencies, "prepareAppResources must run stagePythonHome first; got $dependencies")

        val copied = sync.source.files.map { it.relativeTo(fixture.prefix).invariantSeparatorsPath }.toSet()
        assertTrue(fixture.stdlibFile in copied, "the stdlib must be packaged; got $copied")
        assertTrue(fixture.libraryFile in copied, "the shared library must be packaged; got $copied")
        assertFalse(fixture.headerFile in copied, "headers are build-time only; got $copied")
        assertFalse(fixture.binaryFile in copied, "bin/ is not used by an embedding app; got $copied")
        assertFalse(fixture.tclFile in copied, "Tcl/Tk is not packaged; got $copied")
        assertFalse(fixture.bytecodeFile in copied, "__pycache__ is not packaged; got $copied")
    }

    @Test
    fun turningPackagingOffLeavesTheComposeTaskAlone() {
        val fixture = Fixture()
        fixture.extension.packagePythonHome.set(false)
        val sync = fixture.syncTask()

        val dependencies = sync.taskDependencies.getDependencies(sync).map { it.name }
        assertFalse("stagePythonHome" in dependencies, "disabled packaging must not stage; got $dependencies")
        assertTrue(sync.source.files.none { it.startsWith(fixture.prefix) }, "disabled packaging must copy nothing")
    }

    /**
     * A project whose Gradle user home already holds a "staged" prefix, at exactly the path
     * `configurePythonHomeStaging` computes, so the wiring can be read without a download.
     */
    private class Fixture {
        // Under this module's own build directory (the test task's working directory), not the
        // system temporary directory: AGENTS.md rule 2.
        val root: File = File(System.getProperty("user.dir"), "build/tmp/packagedPythonHomeTest/${java.util.UUID.randomUUID()}")
            .apply { mkdirs() }
        val gradleUserHome = File(root, "gradle-home")
        val project = ProjectBuilder.builder()
            .withProjectDir(File(root, "project").apply { mkdirs() })
            .withGradleUserHomeDir(gradleUserHome)
            .build()
        val extension: PythonBindingsExtension =
            project.extensions.create("pythonBindings", PythonBindingsExtension::class.java)

        private val platform = assertNotNull(
            hostDesktopPlatform(System.getProperty("os.name"), System.getProperty("os.arch")),
            "this test needs a host python-build-standalone publishes for",
        )
        private val windows = platform.startsWith("windows")
        private val flavour = if (DEFAULT_PYTHON_FREE_THREADED) "-freethreaded" else ""
        private val tag = DEFAULT_PYTHON_VERSION.split('.').take(2).joinToString(".") +
            if (DEFAULT_PYTHON_FREE_THREADED) "t" else ""

        val prefix = File(
            gradleUserHome,
            "${PythonHomeStaging.CACHE_DIRECTORY}/$DEFAULT_PYTHON_VERSION+$DEFAULT_PBS_RELEASE$flavour/$platform/python",
        )

        val stdlibFile = if (windows) "Lib/os.py" else "lib/python$tag/os.py"
        val libraryFile = if (windows) "python${tag.replace(".", "").removeSuffix("t")}.dll" else "lib/libpython$tag.dylib"
        val headerFile = "include/python$tag/Python.h"
        val binaryFile = if (windows) "python.exe" else "bin/python3"
        val tclFile = if (windows) "tcl/tcl9.0/init.tcl" else "lib/libtcl9.0.dylib"
        val bytecodeFile = if (windows) "Lib/__pycache__/os.cpython-314.pyc" else "lib/python$tag/__pycache__/os.cpython-314.pyc"

        init {
            listOf(stdlibFile, libraryFile, headerFile, binaryFile, tclFile, bytecodeFile).forEach {
                File(prefix, it).apply { parentFile.mkdirs(); writeText("x") }
            }
            project.tasks.register("prepareAppResources", Sync::class.java) {
                into(project.layout.buildDirectory.dir("compose/tmp/prepareAppResources"))
            }
            PythonBindingsPlugin().configurePythonHomeStaging(project, extension)
        }

        fun syncTask(): Sync = project.tasks.getByName("prepareAppResources") as Sync
    }
}
