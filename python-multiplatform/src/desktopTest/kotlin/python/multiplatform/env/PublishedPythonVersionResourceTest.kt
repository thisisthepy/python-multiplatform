package python.multiplatform.env

import python.multiplatform.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Issue #61: the published jar carries the CPython version it embeds, agreeing with BuildConfig.
 *
 * Issue #158: the desktop jar embeds the free-threaded 3.15t, so its resource says
 * `freeThreaded=true`. The AAR carries its own resource (`freeThreaded=false`, GIL until #328);
 * the two are generated separately in `python-multiplatform/build.gradle.kts`.
 *
 * Red before #158: one resource served the jar and the AAR, rendered from
 * `-PpythonFreeThreaded` (default false), so the desktop jar said `freeThreaded=false`.
 */
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
        assertEquals("true", props["freeThreaded"], "the desktop jar embeds the free-threaded CPython (#158)")
    }
}
