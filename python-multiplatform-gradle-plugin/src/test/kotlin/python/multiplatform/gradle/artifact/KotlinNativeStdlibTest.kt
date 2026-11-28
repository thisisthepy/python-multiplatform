package python.multiplatform.gradle.artifact

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #191: the klib walker reads the Kotlin/Native stdlib for types only. A consumer's resolved klibs never
 * include it (it ships in the Kotlin/Native distribution), so before this every stdlib type in a
 * signature was unknown on Kotlin/Native while the jar walker reads it from the JDK classpath:
 * `collectAsState(context: CoroutineContext)`, `getValue(thisObj, property: KProperty<*>)`,
 * `CoroutineExceptionHandler(handler: (CoroutineContext, Throwable) -> Unit)` were declined.
 *
 * [kotlinNativeStdlibKlib] finds it: `kotlin.native.home` when set, otherwise the distribution of the
 * applied Kotlin version under the konan data directory; `null` when neither has it, and the walk
 * proceeds as before.
 */
class KotlinNativeStdlibTest {

    private fun distribution(root: File, name: String): File {
        val stdlib = File(root, "$name/klib/common/stdlib")
        File(stdlib, "default").mkdirs()
        File(stdlib, "default/manifest").writeText("unique_name=stdlib\n")
        return stdlib
    }

    private fun tempDir(): File = Files.createTempDirectory("kn-stdlib").toFile().also { it.deleteOnExit() }

    @Test
    fun theDistributionOfTheAppliedKotlinVersionIsFoundUnderTheKonanDataDirectory() {
        val konan = tempDir()
        distribution(konan, "kotlin-native-prebuilt-macos-aarch64-2.1.0")
        val wanted = distribution(konan, "kotlin-native-prebuilt-macos-aarch64-2.2.20")
        distribution(konan, "kotlin-native-prebuilt-macos-aarch64-2.2.2")
        assertEquals(wanted, kotlinNativeStdlibKlib(nativeHome = null, konanDataDir = konan, kotlinVersion = "2.2.20"))
    }

    @Test
    fun kotlinNativeHomeWinsOverTheKonanDataDirectory() {
        val konan = tempDir()
        distribution(konan, "kotlin-native-prebuilt-macos-aarch64-2.2.20")
        val home = tempDir()
        val wanted = distribution(home, "dist")
        assertEquals(wanted, kotlinNativeStdlibKlib(File(home, "dist"), konan, "2.2.20"))
    }

    @Test
    fun nothingIsFoundWhenNoDistributionHasTheVersion() {
        val konan = tempDir()
        distribution(konan, "kotlin-native-prebuilt-macos-aarch64-2.1.0")
        File(konan, "kotlin-native-prebuilt-macos-aarch64-2.2.20").mkdirs() // no stdlib inside
        assertNull(kotlinNativeStdlibKlib(null, konan, "2.2.20"))
        assertNull(kotlinNativeStdlibKlib(null, konan, null))
        assertNull(kotlinNativeStdlibKlib(null, File(konan, "absent"), "2.1.0"))
    }
}
