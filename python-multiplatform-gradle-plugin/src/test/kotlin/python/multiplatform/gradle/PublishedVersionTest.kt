package python.multiplatform.gradle

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #70: the library, the KSP processor and this plugin publish under one version,
 * "$pythonVersion-$libraryVersionSuffix" from the root `gradle.properties`. The plugin hands a
 * consumer the processor's and the wasm runtime's coordinates at its own version, so both must carry
 * exactly the library's version: a plugin left at an older number (it was 3.13.0 beside a 3.14.7
 * library) would resolve a processor nobody published with that library.
 */
class PublishedVersionTest {

    private val expected: String = Properties().run {
        File("../gradle.properties").inputStream().use { load(it) }
        "${getProperty("pythonVersion")}-${getProperty("libraryVersionSuffix")}"
    }

    @Test
    fun theProcessorCoordinateCarriesTheLibraryVersion() {
        assertEquals(
            "org.thisisthepy:python-multiplatform-ksp:$expected",
            DEFAULT_PROCESSOR_COORDINATES,
        )
    }

    @Test
    fun theWasmRuntimeCoordinateCarriesTheLibraryVersion() {
        assertEquals(
            "org.thisisthepy:python-multiplatform-wasm-runtime:$expected",
            DEFAULT_WASM_RUNTIME_COORDINATES,
        )
    }
}
