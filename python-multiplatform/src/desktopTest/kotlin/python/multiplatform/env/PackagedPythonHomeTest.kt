package python.multiplatform.env

import python.native.ffi.manager
import java.io.File
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SPEC L-9 (issue #60), the decisions: which prefix a packaged desktop application uses, how it is
 * spelled for `Py_SetPythonHome`, and where `libpython` is found in it.
 *
 * The end-to-end half -- a JVM with no `PYTHONHOME` actually starting CPython from such a prefix --
 * is [PackagedPythonHomeLaunchTest]. It needs a child process because this test JVM's interpreter
 * was started from `PYTHONHOME` long before any test runs.
 */
class PackagedPythonHomeTest {

    private val scratchDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        scratchDirs.forEach { it.deleteRecursively() }
        scratchDirs.clear()
    }

    /** Under `build/`, not the system temporary directory -- AGENTS.md rule 2. */
    private fun newScratchDir(): File =
        File("build/tmp/packagedPythonHomeTest/${java.util.UUID.randomUUID()}").absoluteFile
            .also { it.mkdirs(); scratchDirs += it }

    private fun properties(vararg pairs: Pair<String, String>): (String) -> String? {
        val map = mapOf(*pairs)
        return { map[it] }
    }

    // ---- resolution order ----

    // ---- beside a native-image executable (issue #246) ----

    private val inNativeImage = PackagedPythonHome.NATIVE_IMAGE_CODE_PROPERTY to "runtime"

    @Test
    fun aNativeImageFindsThePrefixBesideItsExecutable() {
        // A native image has no java.home, no Compose resources directory and no launcher to set
        // PYTHONHOME; the prefix ships beside the executable, as an iOS app's ships in its bundle.
        val resolved = PackagedPythonHome.resolve(
            environmentHome = null,
            property = properties(inNativeImage),
            isDirectory = { it == "/apps/demo/python-multiplatform-home" },
            executablePath = { "/apps/demo/demo" },
        )
        assertEquals("/apps/demo/python-multiplatform-home", resolved?.path)
    }

    @Test
    fun aJvmIgnoresADirectoryBesideItsExecutableAndNeverAsksForIt() {
        // On a JVM the executable is `java`: a directory beside it belongs to the JDK, not to the app.
        // And it is not even asked for: `ProcessHandle` does not exist on older Android.
        var asked = false
        val resolved = PackagedPythonHome.resolve(
            environmentHome = null,
            property = properties(),
            isDirectory = { true },
            executablePath = { asked = true; "/jdk/bin/java" },
        )
        assertNull(resolved)
        assertEquals(false, asked, "the executable must not be looked up outside a native image")
    }

    @Test
    fun aNativeImageWithNoPrefixBesideItResolvesToNothing() {
        val resolved = PackagedPythonHome.resolve(
            environmentHome = null,
            property = properties(inNativeImage),
            isDirectory = { false },
            executablePath = { "/apps/demo/demo" },
        )
        assertNull(resolved)
    }

    @Test
    fun anEnvironmentPythonHomeStillWinsInANativeImage() {
        val resolved = PackagedPythonHome.resolve(
            environmentHome = "/from/env",
            property = properties(inNativeImage),
            isDirectory = { true },
            executablePath = { "/apps/demo/demo" },
        )
        assertNull(resolved)
    }

    @Test
    fun anEnvironmentPythonHomeWinsAndNothingElseIsDone() {
        // CPython reads PYTHONHOME itself, and L-3 checks it; a second source would only be a
        // second opinion that could disagree with the one CPython acts on.
        val resolved = PackagedPythonHome.resolve(
            environmentHome = "/from/env",
            property = properties(PackagedPythonHome.HOME_PROPERTY to "/from/property"),
            isDirectory = { true },
        )
        assertNull(resolved)
    }

    @Test
    fun aBlankEnvironmentPythonHomeCountsAsUnset() {
        // CPython ignores an empty PYTHONHOME too, so treating "" as "set" would start the
        // interpreter with no home at all.
        val resolved = PackagedPythonHome.resolve(
            environmentHome = "",
            property = properties(PackagedPythonHome.HOME_PROPERTY to "/from/property"),
            isDirectory = { true },
        )
        assertEquals("/from/property", resolved?.path)
    }

    @Test
    fun theExplicitPropertyIsUsedEvenWhenItDoesNotExist() {
        // So that a wrong `-Dpython.multiplatform.home` reaches PythonHomeCheck and is reported
        // with its path, rather than silently falling through to "no home" and a Py_FatalError.
        val resolved = PackagedPythonHome.resolve(
            environmentHome = null,
            property = properties(PackagedPythonHome.HOME_PROPERTY to "/does/not/exist"),
            isDirectory = { false },
        )
        assertEquals("/does/not/exist", resolved?.path)
        assertEquals("system property ${PackagedPythonHome.HOME_PROPERTY}", resolved?.source)
    }

    @Test
    fun theExplicitPropertyWinsOverComposeResources() {
        val resolved = PackagedPythonHome.resolve(
            environmentHome = null,
            property = properties(
                PackagedPythonHome.HOME_PROPERTY to "/explicit",
                PackagedPythonHome.COMPOSE_RESOURCES_PROPERTY to "/app/resources",
            ),
            isDirectory = { true },
        )
        assertEquals("/explicit", resolved?.path)
    }

    @Test
    fun composeResourcesAreUsedWhenTheyHoldTheDirectory() {
        val resources = newScratchDir()
        val home = File(resources, PackagedPythonHome.DIRECTORY_NAME).apply { mkdirs() }
        val resolved = PackagedPythonHome.resolve(
            environmentHome = null,
            property = properties(PackagedPythonHome.COMPOSE_RESOURCES_PROPERTY to resources.path),
            isDirectory = { File(it).isDirectory },
        )
        assertEquals(home.path, resolved?.path)
    }

    @Test
    fun composeResourcesWithoutTheDirectoryAreNotAHome() {
        // Every Compose Desktop app has `compose.application.resources.dir` set, including one
        // whose build turned packaging off. That app must behave exactly as before: no home of
        // ours, CPython's own default search, and L-3 unchanged.
        val resources = newScratchDir()
        val resolved = PackagedPythonHome.resolve(
            environmentHome = null,
            property = properties(PackagedPythonHome.COMPOSE_RESOURCES_PROPERTY to resources.path),
            isDirectory = { File(it).isDirectory },
        )
        assertNull(resolved)
    }

    @Test
    fun nothingConfiguredIsNoHome() {
        assertNull(PackagedPythonHome.resolve(environmentHome = null, property = { null }, isDirectory = { true }))
    }

    @Test
    fun theDirectoryNameIsTheOneThePluginPackages() {
        // `PACKAGED_HOME_DIRECTORY` in python-multiplatform-gradle-plugin; the builds share no code.
        assertEquals("python-multiplatform-home", PackagedPythonHome.DIRECTORY_NAME)
    }

    // ---- wchar_t for Py_SetPythonHome ----

    @Test
    fun unixWideStringsAreUtf32WithAFourByteTerminator() {
        // macOS and Linux have a 4-byte wchar_t. A non-BMP character is one code unit there, not
        // a surrogate pair -- encoding through UTF-16 would hand CPython two invalid code points.
        val encoded = encodeWideString("aé😀", windows = false, order = ByteOrder.LITTLE_ENDIAN)
        assertContentEquals(
            byteArrayOf(
                0x61, 0, 0, 0,
                0xE9.toByte(), 0, 0, 0,
                0x00, 0xF6.toByte(), 0x01, 0,
                0, 0, 0, 0,
            ),
            encoded,
        )
    }

    @Test
    fun windowsWideStringsAreUtf16WithATwoByteTerminator() {
        val encoded = encodeWideString("a😀", windows = true, order = ByteOrder.LITTLE_ENDIAN)
        assertContentEquals(
            byteArrayOf(0x61, 0, 0x3D, 0xD8.toByte(), 0x00, 0xDE.toByte(), 0, 0),
            encoded,
        )
    }

    @Test
    fun bigEndianOrderIsHonoured() {
        assertContentEquals(
            byteArrayOf(0, 0, 0, 0x61, 0, 0, 0, 0),
            encodeWideString("a", windows = false, order = ByteOrder.BIG_ENDIAN),
        )
    }

    // ---- libpython inside a packaged prefix ----

    @Test
    fun theSharedLibraryIsFoundUnderLibOrAtThePrefixRoot() {
        // `lib/` on macOS and Linux, the prefix root on Windows -- the same two shapes
        // `resolveSidecarLibrary` probes under PYTHONHOME.
        val unixPrefix = newScratchDir()
        val name = "python3.14"
        val unixLibrary = File(unixPrefix, "lib/${System.mapLibraryName(name)}").apply { parentFile.mkdirs(); writeText("x") }
        assertEquals(unixLibrary, manager.libraryUnder(unixPrefix.path, name))

        val windowsPrefix = newScratchDir()
        val windowsLibrary = File(windowsPrefix, System.mapLibraryName(name)).apply { writeText("x") }
        assertEquals(windowsLibrary, manager.libraryUnder(windowsPrefix.path, name))

        assertNull(manager.libraryUnder(newScratchDir().path, name))
    }

    @Test
    fun theRealStagedPrefixHoldsTheLibraryThisBuildLoads() {
        // The prefix the plugin packages is a subset of this one, so if `libraryUnder` cannot find
        // the interpreter here, a packaged app falls back to the classpath copy -- which it extracts
        // into the working directory, a directory a launched app does not own.
        val home = assertNotNull(System.getenv("PYTHONHOME"), "PYTHONHOME is unset for desktopTest")
        val tagged = python.multiplatform.Versions.currentVersion.taggedVersionString
        val name = if (python.multiplatform.currentPlatform.os == python.multiplatform.OSType.Windows) {
            "python" + tagged.replace(".", "")
        } else {
            "python$tagged"
        }
        assertNotNull(manager.libraryUnder(home, name), "no ${System.mapLibraryName(name)} under $home")
    }
}
