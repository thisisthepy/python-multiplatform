package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [Python3.runApp] — the decided, deliberately reduced subset of CPython's command-line parser
 * that an *embedder* can drive without calling `Py_BytesMain`/`Py_RunMain` (which finalize the
 * interpreter; see ROADMAP §12/§14b item 10 and [RunMainTest]'s header for why those cannot be the
 * implementation of anything called more than once).
 *
 * The decided scope, fixed by these tests:
 * - `-c <cmd>` runs the string in the embedder's shared `__main__`, the same namespace
 *   [Python3.exec] writes into.
 * - `-m <module>` is a straight delegation to [Python3.runMain].
 * - a bare script path runs the file's contents in the same shared `__main__`.
 * - `-` (stdin) and any option that only means something before `Py_Initialize()` (a `PyConfig`
 *   field) are refused by name rather than silently ignored.
 * - `--` forces the next token to be treated as a script path even if it looks like an option,
 *   matching CPython's observed behaviour (`python -- -c` runs a file literally named `-c`).
 * - `sys.exit(n)` is a return value, never a process exit -- mirrors [Python3.runMain].
 */
class RunAppTest {

    private fun installFinder() = PythonTestFixture.withInterpreter {
        Python3.exec(FINDER_SOURCE)
    }

    private fun registerModule(name: String, source: String) {
        installFinder()
        Python3.exec("__pmp_register_test_module__(${name.asPythonStringLiteral()}, ${source.asPythonStringLiteral()})")
    }

    /**
     * Writes [source] to a fresh temp file using Python's own `tempfile`/`open` and returns its
     * path. Done through Python rather than a Kotlin file API so this test compiles and runs
     * identically on every target this suite runs on -- exactly the reasoning [RunAppTest] borrows
     * from [RunMainTest]'s in-memory module finder, applied to the filesystem instead of the import
     * system. It is also, not incidentally, exactly how [Python3.runApp] itself reads a script: via
     * Python's own `open()`, not a new per-platform Kotlin file-reading path.
     */
    private fun writeTempScript(source: String): String = PythonTestFixture.withInterpreter {
        Python3.exec(
            "import tempfile as _pmp_tempfile, os as _pmp_os\n" +
                "_pmp_fd, __pmp_runapp_tmp_path__ = _pmp_tempfile.mkstemp(suffix='.py')\n" +
                "_pmp_os.close(_pmp_fd)\n" +
                "with open(__pmp_runapp_tmp_path__, 'w') as __pmp_runapp_tmp_f__:\n" +
                "    __pmp_runapp_tmp_f__.write(${source.asPythonStringLiteral()})\n"
        )
    }.let { PythonTestFixture.eval("__pmp_runapp_tmp_path__").toString() }

    // ------------------------------------------------------------------------------------
    // -c
    // ------------------------------------------------------------------------------------

    @Test
    fun dashCReturnsSysExitStatusAndTheInterpreterSurvives() = PythonTestFixture.withInterpreter {
        val status = Python3.runApp(listOf("-c", "import sys; sys.exit(3)"))

        assertEquals(3, status)
        // The property Py_RunMain cannot offer: proof the interpreter is still usable.
        assertTrue(Python3.isInitialized)
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
        Python3.exec("pmp_runapp_after_dashc = 5 * 5")
        assertEquals("25", PythonTestFixture.eval("pmp_runapp_after_dashc").toString())
    }

    @Test
    fun dashCWithNoSysExitReturnsZeroAndRunsInSharedMain() = PythonTestFixture.withInterpreter {
        val status = Python3.runApp(listOf("-c", "pmp_runapp_dashc_wrote_main = 'yes'"))

        assertEquals(0, status)
        assertEquals(
            "'yes'",
            PythonTestFixture.eval("repr(pmp_runapp_dashc_wrote_main)").toString(),
            "-c must run in the embedder's own __main__, the same one Python3.exec writes into"
        )
    }

    @Test
    fun dashCBareSysExitIsStatusZero() = PythonTestFixture.withInterpreter {
        assertEquals(0, Python3.runApp(listOf("-c", "import sys\nsys.exit()")))
    }

    @Test
    fun dashCNonIntegerSysExitReportsStatusOneAsCPythonDoes() = PythonTestFixture.withInterpreter {
        assertEquals(1, Python3.runApp(listOf("-c", "import sys\nsys.exit('pmp-runapp-message')")))
        assertTrue(Python3.isInitialized)
    }

    @Test
    fun dashCPropagatesAnOrdinaryExceptionAsPyException() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<PyException> {
            Python3.runApp(listOf("-c", "raise ValueError('pmp-runapp-dashc-boom')"))
        }
        assertTrue(failure.message?.contains("pmp-runapp-dashc-boom") == true)
        assertTrue(Python3.isInitialized)
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
    }

    @Test
    fun dashCSetsArgvZeroToDashCAndRestoresArgvAfterwards() = PythonTestFixture.withInterpreter {
        Python3.exec("import sys\npmp_runapp_argv_before = list(sys.argv) if hasattr(sys, 'argv') else None")
        val before = PythonTestFixture.eval("repr(pmp_runapp_argv_before)").toString()

        Python3.runApp(listOf("-c", "import sys, builtins; builtins.__pmp_runapp_dashc_argv__ = list(sys.argv)", "a", "b"))

        assertEquals(
            "['-c', 'a', 'b']",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runapp_dashc_argv__)").toString(),
            "sys.argv[0] must be '-c', matching CPython, and the rest verbatim"
        )
        assertEquals(
            before,
            PythonTestFixture.eval("repr(list(sys.argv) if hasattr(sys, 'argv') else None)").toString(),
            "sys.argv is process-global state runApp did not own; it must be put back"
        )
    }

    @Test
    fun dashCArgvIsRestoredEvenWhenTheCommandRaises() = PythonTestFixture.withInterpreter {
        Python3.exec("import sys\npmp_runapp_argv_before2 = list(sys.argv) if hasattr(sys, 'argv') else None")
        val before = PythonTestFixture.eval("repr(pmp_runapp_argv_before2)").toString()

        assertFailsWith<PyException> { Python3.runApp(listOf("-c", "raise RuntimeError('pmp-runapp-argv-raise')", "x")) }

        assertEquals(
            before,
            PythonTestFixture.eval("repr(list(sys.argv) if hasattr(sys, 'argv') else None)").toString()
        )
    }

    @Test
    fun dashCWithNoCommandRefusesRatherThanMisreadingTheNextArgument() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<IllegalArgumentException> { Python3.runApp(listOf("-c")) }
        assertTrue(failure.message?.contains("-c") == true, "got: ${failure.message}")
    }

    // ------------------------------------------------------------------------------------
    // -m
    // ------------------------------------------------------------------------------------

    @Test
    fun dashMDelegatesToRunMain() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runapp_module_ok",
            """
            import builtins, sys
            builtins.__pmp_runapp_module_name__ = __name__
            builtins.__pmp_runapp_module_argv__ = list(sys.argv)
            """.trimIndent()
        )

        val status = Python3.runApp(listOf("-m", "pmp_runapp_module_ok", "x", "y"))

        assertEquals(0, status)
        assertEquals(
            "'__main__'",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runapp_module_name__)").toString(),
            "runApp -m must run the module the same way Python3.runMain does: as __main__"
        )
        assertEquals(
            "['x', 'y']",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runapp_module_argv__[1:])").toString()
        )
    }

    @Test
    fun dashMSysExitIsReturnedNotThrown() = PythonTestFixture.withInterpreter {
        registerModule("pmp_runapp_module_exit", "import sys\nsys.exit(7)\n")

        assertEquals(7, Python3.runApp(listOf("-m", "pmp_runapp_module_exit")))
        assertTrue(Python3.isInitialized)
    }

    @Test
    fun dashMWithNoModuleRefuses() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<IllegalArgumentException> { Python3.runApp(listOf("-m")) }
        assertTrue(failure.message?.contains("-m") == true, "got: ${failure.message}")
    }

    // ------------------------------------------------------------------------------------
    // script path
    // ------------------------------------------------------------------------------------

    @Test
    fun scriptPathRunsInSharedMainAndSetsArgv() = PythonTestFixture.withInterpreter {
        Python3.exec("pmp_runapp_script_sees_this = 'set-before-script'")
        val path = writeTempScript(
            "import sys, builtins\n" +
                "builtins.__pmp_runapp_script_argv__ = list(sys.argv)\n" +
                "builtins.__pmp_runapp_script_saw_shared_global__ = pmp_runapp_script_sees_this\n"
        )

        val status = Python3.runApp(listOf(path, "arg1", "arg2"))

        assertEquals(0, status)
        assertEquals(
            "['$path', 'arg1', 'arg2']",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runapp_script_argv__)").toString(),
            "sys.argv[0] must be the script path exactly as given, matching CPython"
        )
        assertEquals(
            "'set-before-script'",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runapp_script_saw_shared_global__)").toString(),
            "a script path runs 'in the __main__ namespace' per the decided scope -- the same " +
                "shared namespace as -c and Python3.exec, not a fresh one like -m"
        )
        Python3.exec("import os; os.remove(${path.asPythonStringLiteral()})")
    }

    @Test
    fun scriptPathSysExitIsReturnedNotThrown() = PythonTestFixture.withInterpreter {
        val path = writeTempScript("import sys\nsys.exit(9)\n")

        assertEquals(9, Python3.runApp(listOf(path)))
        assertTrue(Python3.isInitialized)
        Python3.exec("import os; os.remove(${path.asPythonStringLiteral()})")
    }

    // ------------------------------------------------------------------------------------
    // -- <script>
    // ------------------------------------------------------------------------------------

    @Test
    fun dashDashForcesTheNextTokenToBeTreatedAsAScriptPath() = PythonTestFixture.withInterpreter {
        val path = writeTempScript("import sys, builtins\nbuiltins.__pmp_runapp_dashdash_argv__ = list(sys.argv)\n")

        val status = Python3.runApp(listOf("--", path, "z"))

        assertEquals(0, status)
        assertEquals(
            "['$path', 'z']",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runapp_dashdash_argv__)").toString()
        )
        Python3.exec("import os; os.remove(${path.asPythonStringLiteral()})")
    }

    @Test
    fun dashDashWithNothingAfterItRefuses() = PythonTestFixture.withInterpreter {
        assertFailsWith<UnsupportedOperationException> { Python3.runApp(listOf("--")) }
    }

    // ------------------------------------------------------------------------------------
    // refusals: stdin and pre-init-only options
    // ------------------------------------------------------------------------------------

    @Test
    fun emptyArgsRefusesRatherThanReadingStdin() = PythonTestFixture.withInterpreter {
        assertFailsWith<UnsupportedOperationException> { Python3.runApp(emptyList()) }
    }

    @Test
    fun dashRefusesRatherThanReadingStdin() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<UnsupportedOperationException> { Python3.runApp(listOf("-")) }
        assertTrue(failure.message?.contains("stdin") == true, "got: ${failure.message}")
    }

    /**
     * Every option named in the task's decided scope as "only meaningful before `Py_Initialize()`"
     * must be refused **by name**, not silently ignored -- silently ignoring `-I` (isolate mode) or
     * `-E` (ignore `PYTHON*` env vars) would run the caller's code under a security posture it did
     * not actually get, which is a worse failure mode than a loud refusal.
     */
    @Test
    fun preInitOnlyOptionsAreRefusedByName() = PythonTestFixture.withInterpreter {
        val preInitOnlyOptions = listOf("-E", "-I", "-S", "-s", "-B", "-O", "-OO", "-X", "-W", "-u", "-P")
        for (option in preInitOnlyOptions) {
            val failure = assertFailsWith<UnsupportedOperationException>("expected '$option' to be refused") {
                Python3.runApp(listOf(option, "-c", "pass"))
            }
            assertTrue(
                failure.message?.contains(option) == true,
                "refusal for '$option' should name it, got: ${failure.message}"
            )
        }
    }

    @Test
    fun anUnrecognizedOptionIsRefusedByNameRatherThanMisreadAsAScript() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<UnsupportedOperationException> { Python3.runApp(listOf("-Q", "pass")) }
        assertTrue(failure.message?.contains("-Q") == true, "got: ${failure.message}")
    }

    private companion object {
        fun String.asPythonStringLiteral(): String = buildString {
            append('"')
            for (c in this@asPythonStringLiteral) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
            append('"')
        }

        val FINDER_SOURCE = """
            import sys, importlib.machinery

            if "__pmp_register_test_module__" not in dir(sys.modules["__main__"]):
                class _PMPTestLoader:
                    def __init__(self, name, src):
                        self._name = name
                        self._src = src
                    def create_module(self, spec):
                        return None
                    def exec_module(self, module):
                        exec(self.get_code(self._name), module.__dict__)
                    def get_code(self, fullname):
                        return compile(self._src, "<pmp-test:%s>" % fullname, "exec")
                    def get_source(self, fullname):
                        return self._src
                    def is_package(self, fullname):
                        return False

                class _PMPTestFinder:
                    sources = {}
                    def find_spec(self, fullname, path=None, target=None):
                        src = _PMPTestFinder.sources.get(fullname)
                        if src is None:
                            return None
                        return importlib.machinery.ModuleSpec(
                            fullname,
                            _PMPTestLoader(fullname, src),
                            origin="<pmp-test:%s>" % fullname,
                        )

                def __pmp_register_test_module__(name, src):
                    _PMPTestFinder.sources[name] = src
                    for finder in sys.meta_path:
                        if isinstance(finder, _PMPTestFinder):
                            return
                    sys.meta_path.insert(0, _PMPTestFinder())
        """.trimIndent()
    }
}
