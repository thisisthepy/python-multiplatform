package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.pythonx.PythonCallables
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "latent" half of `886a8e8f`'s report, exercised deliberately by a caller that does not exist
 * anywhere else in this repository.
 *
 * `PythonCallables.Fragment`'s `pythonx.runtime.newFunction` entry declared its `body` slot as
 * `paramTypeNames[0] = "kotlin.Any"`, but the lambda that backs it does `args[0] as PyObject`
 * (`PythonCallables.kt`). `PythonProxySource.argValues` only skips `_pm_unwrap` for a slot declared
 * exactly `python.multiplatform.ffi.PyObject` (`PY_OBJECT`); `kotlin.Any` is deliberately *not*
 * grouped with it (see that file's doc on why `Any` differs between the argument and result
 * directions). So a proxy handed to `newFunction`'s `body` parameter through a rendered
 * `PythonProxySource` call site was unwrapped to its raw handle, `toKotlinObject` resolved that
 * handle back to the Kotlin object behind it, and the entry's own cast failed with a
 * `ClassCastException` -- the same defect `ProxyObjectArgumentTest` pins for `echoObject`, seen
 * from the other type name.
 *
 * Confirmed by running this test (as a plain `assertFails`, before the fix below existed) against
 * the unfixed `paramTypeNames`: the call raised
 * `RuntimeError: class fixture...ProxyCounter cannot be cast to class ...PyObject` (`UpcallTrampoline`
 * turns an uncaught Kotlin exception on this path into a Python `RuntimeError` carrying its
 * message). Reverting `PY_OBJECT` below to `"kotlin.Any"` reproduces that failure again.
 *
 * Nothing production reaches this path today: `PythonxAdapter._make_function` is the only caller of
 * `pythonx.runtime.newFunction`, and it calls `_boundary()['invoke']` directly with a
 * `_callable_thunk(...)`-built closure -- never through a `PythonProxySource`-rendered wrapper, and
 * never with a proxy in the `body` slot. This test builds the caller that did not exist: it puts
 * `PythonCallables.Fragment` into the same `UpcallTable` as a real proxy class (`ProxyFragment`'s
 * `proxycls.Counter`), asks `PythonProxySource` to render *all* of it -- which includes
 * `pythonx.runtime.newFunction` itself, an ordinary `CallableKind.FUNCTION` entry -- and calls the
 * rendered `pythonx.runtime.newFunction(...)` from Python with a `Counter` proxy as `body`.
 */
class PythonCallablesProxyArgumentTest {

    @BeforeTest
    fun install() {
        // `bindUpcallOrNull` execs Python source to bind `_pm_resolve`/`_pm_invoke`, so the
        // interpreter has to be up first -- unlike a suite run, where an earlier test already
        // forced `PythonTestFixture.available`'s lazy init, running this class alone hits
        // "Python is not initialized" here without this.
        check(PythonTestFixture.available) {
            "CPython could not be initialized (${PythonTestFixture.failureReason})"
        }
        UpcallTable.install(listOf(ProxyFragment))
        UpcallTable.register(PythonCallables.Fragment)
        assertTrue(bindUpcallOrNull("demo.calc.ping"), "the fixture table is not installed")
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun aProxyPassedToNewFunctionsBodySlotCrossesAsThePythonObjectItself() =
        PythonTestFixture.withInterpreter {
            PythonProxySource.install()
            val scope = PythonCallables.newScope()
            try {
                PythonCallables.withScope(scope) {
                    // No exception: `body` must reach `PythonCallables.newFunction` as the `Counter`
                    // proxy's own `PyObject`, not be unwrapped to the `ProxyCounter` behind it.
                    Python3.exec(
                        """
                        from proxycls import Counter
                        from python.multiplatform.ffi.pythonx.PythonCallables import newFunction as _pcpat_new_function
                        _pcpat_c = Counter(3)
                        _pcpat_handle = _pcpat_new_function(_pcpat_c, 0, False, '', '')
                        assert _pcpat_handle != 0, 'expected a real wrapper handle back'
                        """.trimIndent(),
                    )
                    // And the scope actually took ownership of a reference for it -- proof the call
                    // ran `PythonCallables.newFunction` to completion rather than failing silently.
                    assertEquals(1, scope.liveCount, "the proxy's body never reached PythonCallables.newFunction")
                }
            } finally {
                scope.close()
            }
        }
}
