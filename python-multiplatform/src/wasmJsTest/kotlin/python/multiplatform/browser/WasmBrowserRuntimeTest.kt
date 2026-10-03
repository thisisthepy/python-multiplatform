package python.multiplatform.browser

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.withGIL
import python.native.ffi.UpcallEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The claims that are only true in a browser, and that `wasmJsNodeTest` therefore cannot make.
 *
 * ROADMAP §10 lists four defects found by hand-driving a served bundle in Chromium. Three of them
 * were invisible to Node by construction:
 *
 *   - `cpython.mjs` has to survive **webpack**, not just an ES module loader. The generated import
 *     object imports it by relative specifier, and the `intrinsics.memory` substitution has to
 *     survive bundling too.
 *   - There is **no NODEFS**. Node reaches the standard library by mounting the interpreter's real
 *     build directory; a browser has to fetch `python3<minor>.zip` into MEMFS, and `node:fs` in a
 *     web bundle is a webpack resolution failure rather than a branch that is never taken.
 *   - The **entry module** hands its raw wasm exports to the glue, and nothing else can: upcalls
 *     are `call_indirect` through CPython's table, which needs a JS-side registration.
 *
 * Each was established once, by hand, against a distribution served over HTTP -- evidence that
 * expires the moment anything moves. This class is the same evidence expressed as a build failure.
 *
 * This is deliberately **not** a second run of the suite. `wasmJsBrowserTest`'s filter (see
 * `build.gradle.kts`) admits this package and `WasmSelectorsImportTest`, and nothing else; the
 * ~344 cases that already run under Node would only cost time.
 */
class WasmBrowserRuntimeTest {

    /**
     * The guard that stops every other test in this class from passing vacuously.
     *
     * If the filter ever admits this class to `wasmJsNodeTest`, or the karma bundle stops being a
     * browser bundle, the rest of these assertions would still hold under Node -- and would then
     * be asserting nothing about the thing they are named after. So the host is asserted first.
     */
    @Test
    fun theHostIsABrowserAndNotNode() {
        assertEquals("object", typeofDocument(), "no `document`: this is not a browser bundle")
        assertEquals(
            "undefined", typeofProcess(),
            "`process` is defined, so this is Node -- every other test in this class would be " +
                "asserting the Node route while claiming to assert the browser one"
        )
        // Named in the failure message rather than asserted on: which browser is a machine fact,
        // and pinning it here would fail on the next one rather than on a regression.
        assertTrue(userAgent().isNotEmpty(), "no navigator.userAgent")
    }

    /**
     * The glue loads, and the interpreter comes up, out of a **webpack-bundled** import object.
     *
     * The failure this replaces is `Module not found: Error: Can't resolve './cpython.mjs'`, which
     * happens at bundling time and takes out the whole module rather than one test.
     */
    @Test
    fun cpythonBootsFromAWebpackBundle() {
        PythonTestFixture.withInterpreter {
            val version = Python3.version
            assertTrue(
                version.startsWith("3."),
                "Py_GetVersion returned something unexpected: $version"
            )
            withGIL {
                Python3.exec(
                    """
                    import sys
                    assert sys.platform == 'emscripten', sys.platform
                    """.trimIndent()
                )
            }
        }
    }

    /**
     * The standard library is the fetched zip in MEMFS, and not a host directory.
     *
     * Two halves, and the second is the one that would rot silently. `sys.prefix` is `/` **because**
     * `browserSettings()` sets no `thisProgram`; if the Node settings were ever taken in a browser
     * the prefix would be the interpreter's build directory, and the modules would come from a
     * NODEFS mount that cannot exist here. Asserting where `json` was actually loaded from fixes
     * that the zip route ran, rather than that some route ran.
     */
    @Test
    fun theStandardLibraryComesFromTheZipFetchedIntoMemfs() {
        PythonTestFixture.withInterpreter {
            withGIL {
                Python3.exec(
                    """
                    import sys, json, os
                    assert sys.prefix == '/', 'sys.prefix is ' + sys.prefix
                    zip_name = '/lib/python%d%d.zip' % sys.version_info[:2]
                    assert os.path.exists(zip_name), zip_name + ' is not in the filesystem'
                    assert json.__file__.startswith(zip_name), json.__file__
                    """.trimIndent()
                )
            }
        }
    }

    /**
     * `pmpSetKotlinExports(exports)` ran, and it ran **before** anything asked for an upcall.
     *
     * `UpcallEntry.publish` puts five `PyCFunction`s into a namespace, and each one needs a C
     * function pointer that only `pmpRegisterUpcall` can mint -- from the Kotlin instance's raw
     * exports, which exist in exactly one scope: the generated entry module. When the handoff is
     * absent or runs too late, `pmpRegisterUpcall` returns `-1` and this throws
     * `IllegalStateException`, which is precisely what the sample printed in the browser before
     * §10's entry-module fix.
     */
    @Test
    fun theEntryModuleHandedItsWasmExportsToTheGlue() {
        PythonTestFixture.withInterpreter {
            val globals = PythonTestFixture.mainGlobals()
            assertTrue(
                UpcallEntry.publish(globals.pointer),
                "UpcallEntry.publish returned false in a browser bundle"
            )
            withGIL {
                Python3.exec(
                    """
                    assert callable(_pm_resolve), 'the upcall bootstrap is not callable'
                    assert callable(_pm_release)
                    """.trimIndent()
                )
            }
        }
    }

    /**
     * Kotlin's linear-memory import really is Emscripten's memory, **after** webpack.
     *
     * The substitution `patchKotlinWasmOutputForCPython` performs is a text edit on a generated
     * `.import-object.mjs`, and webpack then inlines that file into a bundle. A round trip of a
     * real `PyObject` through the object model is the cheapest statement that the edit survived:
     * every pointer here is an address Kotlin dereferences directly out of that memory. If the
     * placeholder memory were still in place the module would not instantiate at all.
     */
    @Test
    fun theObjectModelReadsEmscriptensMemoryThroughTheBundledImportObject() {
        PythonTestFixture.withInterpreter {
            withGIL {
                val result: PyObject = PythonTestFixture.eval("sum([1, 2, 3, 4]) * 2")
                assertEquals("20", result.toString())
            }
        }
    }

    /**
     * The JSPI intrinsics are gone page-wide, and the blocking syscalls are synchronous again.
     *
     * `wasmJsMain/README.md` warns that deleting `WebAssembly.promising`/`Suspending` mutates a
     * host intrinsic globally. Chromium ships both, so the browser is the host where the delete is
     * observable rather than vacuous -- measured in §10 as `function` on a page without the app
     * and `undefined` on the app's page. The second assertion is the reason the delete exists:
     * without it `select.poll().poll(0)` becomes a suspending import with no promising frame on
     * the stack, and the *process* aborts rather than the test failing.
     */
    @Test
    fun theJspiIntrinsicsAreDeletedAndBlockingSyscallsStillReturn() {
        assertEquals(
            "undefined", typeofPromising(),
            "WebAssembly.promising is still defined -- cpython.mjs's JSPI suppression did not run"
        )
        assertEquals("undefined", typeofSuspending(), "WebAssembly.Suspending is still defined")
        PythonTestFixture.withInterpreter {
            withGIL {
                Python3.exec(
                    """
                    import select
                    assert select.poll().poll(0) == []
                    """.trimIndent()
                )
            }
        }
    }
}

private fun typeofDocument(): String = js("typeof document")

private fun typeofProcess(): String = js("typeof process")

private fun userAgent(): String = js("navigator.userAgent")

private fun typeofPromising(): String = js("typeof WebAssembly.promising")

private fun typeofSuspending(): String = js("typeof WebAssembly.Suspending")
