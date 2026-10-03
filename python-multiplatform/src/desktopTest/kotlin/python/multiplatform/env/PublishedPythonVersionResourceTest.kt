package python.multiplatform.env

import python.multiplatform.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Issue #61: the published jar carries the CPython version it embeds, agreeing with BuildConfig. */
class PublishedPythonVersionResourceTest {
    // Same path as resourcePath; that class lives in the build plugin and is
    // not on the test classpath.
    private val resourcePath = "META-INF/python-multiplatform/python.properties"

    @Test
    fun resourceMatchesBuildConfig() {
        val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
        assertNotNull(stream, "${resourcePath} is not on the classpath")
        val props = stream.use { it.readBytes().decodeToString() }.lines()
            .filter { '=' in it }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        assertEquals(BuildConfig.pythonVersion, props["pythonVersion"])
        assertEquals(BuildConfig.pythonFreeThreaded, props["freeThreaded"].toBoolean())
    }
}
