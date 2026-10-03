package python.multiplatform.ffi.utils

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Functional tests for [PyImport]: `sys.modules` inspection and module reloading. */
class PyImportTest {

    /** A name no real module can have, used to pin the negative cases. */
    private val absentModule = "_pmp_module_that_is_never_imported"

    @Test
    fun alreadyImportedModuleIsReportedAsImported() = PythonTestFixture.withInterpreter {
        Python3.import("json").close()
        assertTrue(PyImport.isImported("json"), "json was just imported, so it is in sys.modules")
    }

    @Test
    fun neverImportedModuleIsNotReportedAsImported() = PythonTestFixture.withInterpreter {
        assertFalse(PyImport.isImported(absentModule))
    }

    @Test
    fun lookingUpAnImportedModuleReturnsIt() = PythonTestFixture.withInterpreter {
        Python3.import("json").close()
        val module = PyImport.getModuleOrNull("json")
        assertNotNull(module, "json is in sys.modules, so the lookup must find it")
        try {
            assertEquals("json", module.name)
        } finally {
            module.close()
        }
    }

    @Test
    fun lookingUpAnAbsentModuleReturnsNullWithoutImportingIt() = PythonTestFixture.withInterpreter {
        assertNull(PyImport.getModuleOrNull(absentModule))
        // The lookup must not have imported anything as a side effect.
        assertFalse(PyImport.isImported(absentModule))
    }

    @Test
    fun importingAModuleIsWhatMakesItAppearInSysModules() = PythonTestFixture.withInterpreter {
        // `colorsys` is a real, dependency-free stdlib module that nothing else in this suite
        // touches, so the absent -> present transition is observable rather than assumed.
        val probe = "colorsys"
        if (PyImport.isImported(probe)) {
            // Some other ordering got there first; the transition can no longer be observed, so
            // assert the weaker property that is still genuinely checkable rather than nothing.
            assertNotNull(PyImport.getModuleOrNull(probe))
            return@withInterpreter
        }
        assertNull(PyImport.getModuleOrNull(probe))
        assertFalse(PyImport.isImported(probe), "a sys.modules lookup must not itself import")

        Python3.import(probe).close()
        assertTrue(PyImport.isImported(probe), "importing it must make it visible in sys.modules")
        val found = PyImport.getModuleOrNull(probe)
        assertNotNull(found)
        try {
            assertEquals(probe, found.name)
        } finally {
            found.close()
        }
    }

    @Test
    fun reloadReturnsTheSameModuleObject() = PythonTestFixture.withInterpreter {
        val module = Python3.import("string")
        try {
            val reloaded = PyImport.reload(module)
            try {
                assertEquals("string", reloaded.name)
                // importlib.reload() re-executes the module *in place*: same object, same identity.
                assertEquals(module, reloaded, "reload() must hand back the very module it reloaded")
            } finally {
                reloaded.close()
            }
        } finally {
            module.close()
        }
    }
}
