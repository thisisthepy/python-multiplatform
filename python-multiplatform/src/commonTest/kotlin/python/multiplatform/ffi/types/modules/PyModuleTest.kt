package python.multiplatform.ffi.types.modules

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Functional tests for [PyModule]'s metadata accessors: `name`, `dict` (the
 * module namespace), `doc` -- which must come back null for a module that has
 * none rather than throwing -- and `get`, which delegates to the attribute
 * lookup inherited from [python.multiplatform.ffi.PyObject].
 *
 * This header used to say `name`/`doc`/`file`/`dict` were new `TODO` stubs.
 * They are implemented now, so every test here is a regression test and any
 * failure is a real one.
 */
class PyModuleTest {

    @Test
    fun nameMatchesTheImportedModuleName() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        assertEquals("math", math.name)
    }

    @Test
    fun getDelegatesToAttributeLookup() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        assertEquals(math.getAttr("pi").toString(), math.get("pi").toString())
    }

    @Test
    fun dictExposesTheModuleNamespace() = PythonTestFixture.withInterpreter {
        val math = Python3.import("math")
        val piFromDict = math.dict[PythonTestFixture.eval("'pi'")]
        assertEquals(math.getAttr("pi").toString(), piFromDict?.toString())
    }

    @Test
    fun docCanBeNullForUndocumentedModules() = PythonTestFixture.withInterpreter {
        Python3.exec("import types\n_undocumented_module_for_test = types.ModuleType('_undocumented_module_for_test')")
        val module = PythonTestFixture.eval("_undocumented_module_for_test")
        val wrapped = PyModule(module.pointer, true)
        assertNull(wrapped.doc)
    }
}
