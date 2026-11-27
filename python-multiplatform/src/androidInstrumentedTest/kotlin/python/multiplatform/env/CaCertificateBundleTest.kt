package python.multiplatform.env

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC L-5b (#548): which trust store the CA bundle is built from, and what goes into it.
 *
 * These run against scratch directories, not the device's store, so that the APEX-wins rule is
 * checked on every emulator image, including ones without an APEX store.
 */
class CaCertificateBundleTest {

    private val root = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "ca-bundle-test",
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun store(name: String, vararg certificates: String): File =
        File(root, name).apply {
            mkdirs()
            certificates.forEachIndexed { index, body ->
                // Android's store files hold the PEM block followed by `openssl x509 -text` output.
                File(this, "%08x.0".format(index)).writeText(pem(body) + "Certificate:\n    Data: $body\n")
            }
        }

    private fun pem(body: String) = "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"

    @Test
    fun theApexStoreWinsAndTheStoresAreNotMerged() {
        val apex = store("apex", "QVBFWA==")
        val system = store("system", "U1lTVEVN", "UkVNT1ZFRA==")
        val target = File(root, "bundle.pem")

        assertEquals(1, PythonBootstrap.writeCaCertificateBundle(listOf(apex, system), target))
        assertEquals(pem("QVBFWA=="), target.readText())
    }

    @Test
    fun anEmptyOrMissingStoreFallsThroughToTheNext() {
        val emptyApex = File(root, "apex").apply { mkdirs() }
        val missing = File(root, "missing")
        val system = store("system", "U1lTVEVN", "U0VDT05E")
        val target = File(root, "bundle.pem")

        assertEquals(2, PythonBootstrap.writeCaCertificateBundle(listOf(missing, emptyApex, system), target))
        assertEquals(pem("U1lTVEVN") + pem("U0VDT05E"), target.readText())
    }

    @Test
    fun noStoreWritesNothing() {
        val target = File(root, "bundle.pem")
        assertNull(PythonBootstrap.writeCaCertificateBundle(listOf(File(root, "missing")), target))
        assertTrue(!target.exists(), "no bundle should be written without a store")
    }
}
