package python.multiplatform

import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #546: the AAR carries every stdlib directory, including the ones whose name starts with `_`.
 *
 * AGP's default `ignoreAssetsPattern` contains `<dir>_*`, which dropped `zipfile/_path`, `_pyrepl`,
 * `__phello__`, `compression/_common` and every other `_` directory from the AAR's assets, although
 * `copyAndroidPythonAssets` staged them. `importlib.metadata` imports `zipfile._path`, so it failed on
 * the device with "No module named 'zipfile._path'" (torchnative, Flip4, 2026-11-27).
 *
 * Red before the fix: each lookup returns `False`, because the directory is not in the APK's assets
 * and so is not in the unpacked stdlib.
 */
class AndroidStdlibAssetsTest {

    private fun hasModule(name: String): String = PythonTestFixture.eval(
        "__import__('importlib.util').util.find_spec('$name') is not None",
    ).toString()

    @Test
    fun underscoreStdlibPackagesAreUnpacked() = PythonTestFixture.withInterpreter {
        for (name in listOf("zipfile._path", "_pyrepl", "compression._common", "__phello__")) {
            assertEquals("True", hasModule(name), "the stdlib package $name is missing from the AAR's assets (#546)")
        }
    }

    @Test
    fun importlibMetadataImports() = PythonTestFixture.withInterpreter {
        assertEquals(
            "True",
            PythonTestFixture.eval("__import__('importlib.metadata').metadata is not None").toString(),
            "importlib.metadata needs zipfile._path (#546)",
        )
    }
}
