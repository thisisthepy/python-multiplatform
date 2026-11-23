package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #524: [PyPackage], an empty class at the baseline and until this change. Red before: none of these
 * members existed, so this file did not compile.
 */
class PyPackageTest {

    @Test
    fun aPackageHasAPathAndImportsItsSubmodules() = PythonTestFixture.withInterpreter {
        PyPackage.import("json").use { json ->
            assertEquals("json", json.name)
            assertTrue(json.path.isNotEmpty(), "a package has a __path__")
            assertEquals("json.decoder", json.submodule("decoder").use { it.name })
        }
        PyPackage.import("email").use { email ->
            assertEquals("email.mime", email.subpackage("mime").use { it.name })
        }
    }

    @Test
    fun aPlainModuleIsNotAPackage() = PythonTestFixture.withInterpreter {
        Python3.import("math").use { math -> assertFailsWith<IllegalArgumentException> { PyPackage.from(math) } }
        assertFailsWith<IllegalArgumentException> { PyPackage.import("math") }
    }
}
