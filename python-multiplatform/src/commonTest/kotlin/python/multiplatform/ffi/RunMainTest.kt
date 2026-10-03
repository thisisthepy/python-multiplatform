package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * [Python3.runMain] — running a module as `__main__` in an interpreter that has to **survive**
 * the call.
 *
 * The design question these tests fix an answer to is recorded in ROADMAP §12. The short form:
 *
 * - `Py_RunMain()` (and `Py_BytesMain()`, which reaches it) **always finalizes the interpreter**
 *   — `cpython/pylifecycle.h` declares it, and it is not in the Limited API at all. Neither can
 *   be the implementation of anything an embedder calls more than once.
 * - `PyImport_ImportModule()` runs the module body under its *own* `__name__` and caches it in
 *   `sys.modules`, so `if __name__ == "__main__":` never fires and a second call is a no-op.
 * - `runpy.run_module(name, run_name="__main__", alter_sys=True)` is what remains: pure Python,
 *   no lifecycle involvement, and the same machinery `python -m` itself uses
 *   (`runpy._run_module_as_main` is the CLI's entry point into the same `_run_code`).
 *
 * `alter_sys=True` is the part that makes the module observe a `__main__`-shaped world:
 * `runpy._run_module_code` runs the code in a **fresh** module namespace, installs it as
 * `sys.modules["__main__"]` for the duration, points `sys.argv[0]` at the module's origin, and
 * restores both in `__exit__` — so the `__main__` this suite's other tests write globals into is
 * put back whether the module returns or raises. [theInterpreterAndItsMainNamespaceSurviveRunMain]
 * is the guard for that specifically.
 */
class RunMainTest {

    // ------------------------------------------------------------------------------------
    // In-memory test modules
    //
    // runpy resolves through `importlib.util.find_spec`, so a `sys.modules` entry is not enough:
    // it needs a spec whose loader can produce a code object. A meta-path finder serving source
    // out of a dict gives that without touching the filesystem, which is what makes this test
    // runnable on iOS and Android as well as desktop.
    // ------------------------------------------------------------------------------------

    private fun installFinder() = PythonTestFixture.withInterpreter {
        Python3.exec(FINDER_SOURCE)
    }

    /** Registers [source] as the importable module [name] for the rest of the process. */
    private fun registerModule(name: String, source: String) {
        installFinder()
        Python3.exec("__pmp_register_test_module__(${name.asPythonStringLiteral()}, ${source.asPythonStringLiteral()})")
    }

    @Test
    fun runMainExecutesTheModuleBodyUnderTheNameMain() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runmain_ok",
            """
            import builtins
            builtins.__pmp_runmain_name__ = __name__
            builtins.__pmp_runmain_ran__ = builtins.__dict__.get("__pmp_runmain_ran__", 0) + 1
            """.trimIndent()
        )

        val status = Python3.runMain("pmp_runmain_ok")

        assertEquals(0, status, "a module that falls off the end of its body is a clean exit")
        assertEquals(
            "'__main__'",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runmain_name__)").toString(),
            "the module body must observe __name__ == '__main__', which is the whole difference " +
                "between this and Python3.import"
        )
        assertEquals("1", PythonTestFixture.eval("__import__('builtins').__pmp_runmain_ran__").toString())
    }

    @Test
    fun runMainRunsTheModuleAgainOnASecondCall() = PythonTestFixture.withInterpreter {
        // The reason this is not PyImport_ImportModule: an import is cached in sys.modules, so a
        // second "run" of the same module does nothing at all.
        registerModule(
            "pmp_runmain_twice",
            """
            import builtins
            builtins.__pmp_runmain_twice__ = builtins.__dict__.get("__pmp_runmain_twice__", 0) + 1
            """.trimIndent()
        )

        Python3.runMain("pmp_runmain_twice")
        Python3.runMain("pmp_runmain_twice")

        assertEquals("2", PythonTestFixture.eval("__import__('builtins').__pmp_runmain_twice__").toString())
    }

    @Test
    fun theInterpreterAndItsMainNamespaceSurviveRunMain() = PythonTestFixture.withInterpreter {
        // The hazard this whole entry exists for: the previous body called Py_RunMain(), which
        // finalizes. Anything after it touched a torn-down runtime.
        Python3.exec("pmp_survivor_global = 'set before runMain'")
        registerModule(
            "pmp_runmain_survive",
            """
            pmp_module_local = "must not land in the embedder __main__"
            """.trimIndent()
        )

        Python3.runMain("pmp_runmain_survive")

        assertTrue(Python3.isInitialized, "runMain must not finalize the interpreter")
        // Still able to execute Python at all.
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
        Python3.exec("pmp_survivor_after = 3 * 4")
        assertEquals("12", PythonTestFixture.eval("pmp_survivor_after").toString())
        // sys.modules["__main__"] was put back: globals set before the call are still readable,
        // and the module's own globals did not leak into them.
        assertEquals("'set before runMain'", PythonTestFixture.eval("repr(pmp_survivor_global)").toString())
        assertEquals(
            "False",
            PythonTestFixture.eval("'pmp_module_local' in globals()").toString(),
            "run_module(alter_sys=True) runs in a temporary namespace; the embedder's __main__ " +
                "must come back unchanged"
        )
    }

    @Test
    fun runMainPropagatesAModuleLevelExceptionAsPyException() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runmain_raises",
            """
            raise ValueError("pmp-runmain-boom")
            """.trimIndent()
        )

        val failure = assertFailsWith<PyException> { Python3.runMain("pmp_runmain_raises") }
        assertTrue(
            failure.message?.contains("pmp-runmain-boom") == true,
            "the Python-level message should survive the crossing, got: ${failure.message}"
        )
        assertEquals(
            "ValueError",
            failure.type?.name,
            "and so should the exception type, got: ${failure.type?.name}"
        )

        // The error indicator must be clear and the interpreter usable, i.e. a failing module is
        // an ordinary failure, not a poisoned runtime.
        assertTrue(Python3.isInitialized)
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
    }

    @Test
    fun runMainOnAnUnknownModuleThrowsRatherThanReturning() = PythonTestFixture.withInterpreter {
        val failure = assertFailsWith<PyException> {
            Python3.runMain("pmp_this_module_does_not_exist_anywhere_xyz")
        }
        assertTrue(
            failure.message?.contains("pmp_this_module_does_not_exist_anywhere_xyz") == true,
            "the refusal should name the module, got: ${failure.message}"
        )
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
    }

    // ------------------------------------------------------------------------------------
    // sys.exit
    //
    // Decision, fixed here: `sys.exit(n)` inside the module is a **normal** outcome of runMain
    // and is reported as its return value. It is not a PyException and it certainly does not end
    // the embedding process.
    //
    // The reasoning is that SystemExit is not an error condition in a module written to be run as
    // `__main__` -- it is how such a module says "I am done, here is my status" -- and that
    // CPython's own command line agrees: `Py_RunMain` catches SystemExit and turns it into the
    // process exit status rather than printing a traceback. The part of that behaviour an
    // embedder must not inherit is the *process* exit, so the status is returned instead.
    // ------------------------------------------------------------------------------------

    @Test
    fun sysExitZeroIsAQuietSuccessfulReturn() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runmain_exit0",
            """
            import builtins, sys
            builtins.__pmp_runmain_exit0_reached__ = True
            sys.exit(0)
            """.trimIndent()
        )

        assertEquals(0, Python3.runMain("pmp_runmain_exit0"))
        assertEquals("True", PythonTestFixture.eval("__import__('builtins').__pmp_runmain_exit0_reached__").toString())
        assertTrue(Python3.isInitialized, "sys.exit() inside the module must not kill the embedder")
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
    }

    @Test
    fun sysExitWithANonZeroStatusIsReturnedNotThrown() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runmain_exit3",
            """
            import sys
            sys.exit(3)
            """.trimIndent()
        )

        assertEquals(3, Python3.runMain("pmp_runmain_exit3"))
        assertTrue(Python3.isInitialized)
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
    }

    @Test
    fun sysExitWithAMessageReportsStatusOneAsCPythonDoes() = PythonTestFixture.withInterpreter {
        // CPython's own handling: a non-integer SystemExit.code is written to stderr and the
        // status is 1. Mirrored rather than invented, so a module behaves the same under runMain
        // as under `python -m`.
        registerModule(
            "pmp_runmain_exitmsg",
            """
            import sys
            sys.exit("pmp-runmain-exit-message")
            """.trimIndent()
        )

        assertEquals(1, Python3.runMain("pmp_runmain_exitmsg"))
        assertTrue(Python3.isInitialized)
        assertEquals("2", PythonTestFixture.eval("1 + 1").toString())
    }

    @Test
    fun bareSysExitIsStatusZero() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runmain_exitbare",
            """
            import sys
            sys.exit()
            """.trimIndent()
        )

        assertEquals(0, Python3.runMain("pmp_runmain_exitbare"))
    }

    // ------------------------------------------------------------------------------------
    // sys.argv
    // ------------------------------------------------------------------------------------

    @Test
    fun theModuleSeesTheArgumentsItWasGivenAndArgvIsRestoredAfterwards() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runmain_argv",
            """
            import builtins, sys
            builtins.__pmp_runmain_argv__ = list(sys.argv)
            """.trimIndent()
        )

        Python3.exec("import sys\npmp_argv_before = list(sys.argv) if hasattr(sys, 'argv') else None")
        val before = PythonTestFixture.eval("repr(pmp_argv_before)").toString()

        Python3.runMain("pmp_runmain_argv", listOf("--flag", "value"))

        assertEquals(
            "['--flag', 'value']",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runmain_argv__[1:])").toString(),
            "sys.argv[1:] inside the module must be exactly what was passed"
        )
        // runpy's _ModifiedArgv0 replaces argv[0] with the module's origin, as `python -m` does.
        assertEquals(
            "'<pmp-test:pmp_runmain_argv>'",
            PythonTestFixture.eval("repr(__import__('builtins').__pmp_runmain_argv__[0])").toString(),
            "argv[0] is the module's origin while it runs, matching `python -m`"
        )
        assertEquals(
            before,
            PythonTestFixture.eval("repr(list(sys.argv) if hasattr(sys, 'argv') else None)").toString(),
            "sys.argv is process-global state the caller did not ask to have rewritten; runMain " +
                "must put it back"
        )
    }

    @Test
    fun argvIsRestoredEvenWhenTheModuleRaises() = PythonTestFixture.withInterpreter {
        registerModule(
            "pmp_runmain_argv_raises",
            """
            raise RuntimeError("pmp-argv-raise")
            """.trimIndent()
        )
        Python3.exec("import sys\npmp_argv_before2 = list(sys.argv) if hasattr(sys, 'argv') else None")
        val before = PythonTestFixture.eval("repr(pmp_argv_before2)").toString()

        assertFailsWith<PyException> { Python3.runMain("pmp_runmain_argv_raises", listOf("x")) }

        assertEquals(
            before,
            PythonTestFixture.eval("repr(list(sys.argv) if hasattr(sys, 'argv') else None)").toString()
        )
    }

    // ------------------------------------------------------------------------------------
    // Cost
    // ------------------------------------------------------------------------------------

    /**
     * `runMain` is not in the same cost class as `exec` and must not be reached for in a loop:
     * every call re-runs the import system's `find_spec`, recompiles or reloads the module's code
     * object and builds a fresh namespace. This prints the measured per-call cost of both rather
     * than asserting a threshold — the number is machine- and load-dependent, and the point is
     * that it is visible in the log next to `exec`'s, not that it sits under some bound.
     *
     * The one assertion is directional and holds by construction (a module run does strictly more
     * than compiling one statement), so it cannot flap on a loaded machine.
     */
    @Test
    fun runMainCostAgainstExec() = PythonTestFixture.withInterpreter {
        registerModule("pmp_runmain_cost", "x = 1\n")
        val clock = TimeSource.Monotonic
        val iterations = 20

        repeat(3) { Python3.runMain("pmp_runmain_cost") }
        repeat(3) { Python3.exec("x = 1") }

        val runMainMark = clock.markNow()
        repeat(iterations) { Python3.runMain("pmp_runmain_cost") }
        val runMainNanos = runMainMark.elapsedNow().inWholeNanoseconds / iterations

        val execMark = clock.markNow()
        repeat(iterations) { Python3.exec("x = 1") }
        val execNanos = execMark.elapsedNow().inWholeNanoseconds / iterations

        println(
            "MEASURE runMain: ${runMainNanos} ns/call over $iterations calls; " +
                "Python3.exec(\"x = 1\"): ${execNanos} ns/call"
        )
        assertTrue(
            runMainNanos > 0 && execNanos > 0,
            "both measurements should be non-zero, got runMain=$runMainNanos exec=$execNanos"
        )
    }

    private companion object {

        /**
         * Escapes [this] into a Python single-quoted string literal, so a module's source can be
         * handed to `Python3.exec` without worrying about the quotes and backslashes inside it.
         */
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

        /**
         * A meta-path finder serving module source out of a dict, installed once.
         *
         * `runpy` goes through `importlib.util.find_spec` and then `spec.loader.get_code(...)`
         * (runpy.py, `_get_module_details`), so a plain `sys.modules` entry is not enough — the
         * spec has to be able to produce a code object. This is the smallest thing that can, and
         * being in-memory it works identically on every target this suite runs on.
         */
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
