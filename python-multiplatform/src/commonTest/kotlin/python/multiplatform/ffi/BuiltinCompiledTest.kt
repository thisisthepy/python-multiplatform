package python.multiplatform.ffi

import kotlin.test.Test
import kotlin.test.assertEquals

/** Issue #42 / SPEC N-7: `compiled` is a builtin installed at interpreter initialisation. */
class BuiltinCompiledTest {

    private fun evalStr(expr: String): String = PythonTestFixture.eval(expr).toString()

    @Test
    fun compiledResolvesWithoutImportAndIsIdentity() = PythonTestFixture.withInterpreter {
        assertEquals("True", evalStr("compiled(len) is len"))
        Python3.exec("def _bc_f(): return 1\n_bc_r = compiled(_bc_f) is _bc_f")
        assertEquals("True", evalStr("_bc_r"))
        // It must live in builtins, not leak into __main__.
        assertEquals("False", evalStr("'compiled' in globals()"))
    }

    @Test
    fun existingBuiltinCompiledIsNotOverwritten() = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            import builtins as _b
            _bc_orig = _b.compiled
            _bc_sentinel = lambda f: f
            _b.compiled = _bc_sentinel
            """.trimIndent()
        )
        try {
            Python3.installBuiltinCompiled()
            assertEquals("True", evalStr("_b.compiled is _bc_sentinel"))
        } finally {
            Python3.exec("_b.compiled = _bc_orig")
        }
        assertEquals("True", evalStr("_b.compiled is _bc_orig"))
    }
}
