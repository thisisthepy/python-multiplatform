package python.multiplatform.gradle.artifact

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SPEC B-2 at the jar walker's level (B-1, issue #38) for what the notebook needs on Kotlin/Native
 * (#191, batch run 45027744672, where every `macosArm64` notebook scenario failed because
 * `androidx.compose.runtime.mutableStateOf` was not bound):
 *
 * 1. A generic function whose type parameters are unbounded and not reified binds with each one read as
 *    `kotlin.Any?` and written out at the call, `@Composable` or not. Before, the klib walker substituted
 *    only on `@Composable` functions and declined `mutableStateOf` ("a bounded or reified type parameter").
 *    `kotlinx.coroutines.flow.MutableStateFlow(value: T)` has `mutableStateOf`'s shape.
 * 2. A `kotlin.Any?` slot crosses as an object handle, as the jar walker's `NULLABLE_ANY_BOUNDARY`
 *    does; the substituted `value: T` is one.
 * 3. A project dependency resolves to an *unpacked* klib directory (`<name>/default/manifest`), not a
 *    `.klib` file, and is walked like one. Before, `PythonArtifactBindingsTask` skipped it, so
 *    `python.multiplatform.compose.rememberSaveableWrapper` was never bound on Kotlin/Native while the
 *    JVM fixture bound it from the project's jar.
 * 4. A nullable `String` (and `ByteArray`) parameter crosses as the jar walker's
 *    `nullablePrimitiveBoundaryTypeOf` carries it: a plain `as String?` cast, Python `None` arriving as
 *    `null`. Before, the klib walker declined every nullable primitive, which declined all four
 *    `androidx.compose.material3.Icon` overloads (`contentDescription: String?`) on Kotlin/Native
 *    (batch run 45066724639: `cannot import name 'Icon'`). `Int?`/`Boolean?` stay declined, as on jars.
 */
class KlibGenericFunctionTest {

    private val coroutinesKlib: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureKlib")
            assertNotNull(path, "the walkerFixtureKlib system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    private val stateFlow = "kotlinx.coroutines.flow.MutableStateFlow"

    @Test
    fun anUnboundedGenericFunctionBindsWithItsTypeArgumentWrittenOut() {
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines.flow"))
        val factory = entries.singleOrNull { it.name == stateFlow }
        val declined = KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines.flow"))
            .filter { "${it.owner}.${it.simpleName}" == stateFlow }
            .map { it.declineReason }
        assertNotNull(factory, "$stateFlow is not bound; declined: $declined")
        assertEquals(listOf("value"), factory.paramNames)
        assertEquals(listOf("OBJECT"), factory.paramTags)
        assertEquals("OBJECT", factory.returnTag)
        assertEquals(stateFlow, factory.returnTypeName)
        assertTrue("MutableStateFlow<kotlin.Any?>(" in factory.lambdaBody, factory.lambdaBody)
        assertTrue("as kotlin.Any?)" in factory.lambdaBody, factory.lambdaBody)
    }

    @Test
    fun anUnpackedKlibDirectoryIsWalkedLikeTheKlibFile() {
        val unpacked = File("build/tmp/klib-directory-test/kotlinx-coroutines-core")
        unpacked.deleteRecursively()
        ZipFile(coroutinesKlib).use { zip ->
            for (entry in zip.entries()) {
                val target = File(unpacked, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        try {
            assertTrue(isKlibArtifact(unpacked), "an unpacked klib directory is not recognised: $unpacked")
            assertTrue(isKlibArtifact(coroutinesKlib))
            assertTrue(!isKlibArtifact(unpacked.parentFile), "a directory without default/manifest is not a klib")

            val fromFile = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines")).map { it.name }
            val fromDirectory = KlibScanner.scanKlib(unpacked, includePrefixes = listOf("kotlinx.coroutines")).map { it.name }
            assertTrue(fromFile.isNotEmpty())
            assertEquals(fromFile, fromDirectory)
        } finally {
            unpacked.parentFile.deleteRecursively()
        }
    }

    @Test
    fun aNullableStringParameterCrossesAsAString() {
        val closed = "kotlinx.coroutines.channels.ClosedReceiveChannelException"
        val entries = KlibScanner.scanKlib(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines.channels"))
        val declined = KlibScanner.scanKlibDeclarations(coroutinesKlib, includePrefixes = listOf("kotlinx.coroutines.channels"))
            .filter { "${it.owner}.${it.simpleName}" == closed || it.simpleName == closed }
            .map { it.declineReason }
        val constructor = entries.singleOrNull { it.name == closed }
        assertNotNull(constructor, "$closed(message: String?) is not bound; declined: $declined")
        assertEquals(listOf("STRING"), constructor.paramTags)
        assertTrue("as String?)" in constructor.lambdaBody, constructor.lambdaBody)
    }
}
