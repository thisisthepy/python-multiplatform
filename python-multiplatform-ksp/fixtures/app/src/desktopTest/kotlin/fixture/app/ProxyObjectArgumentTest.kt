package fixture.app

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Which `TypeTag.OBJECT` arguments `_pm_unwrap` may unwrap, decided by the **declared** Kotlin
 * type rather than by the shape of the value.
 *
 * `99acd830` filed this and left it: the generated proxy wrapped every `TypeTag.OBJECT` argument
 * in `_pm_unwrap`, which turns any `_PmObject`-derived value into its raw `HandleTable` handle.
 * That is right for the chain shape `OwnedResultLifetimeTest` pins -- `size(padding(m, 16.0),
 * 24.0)`, where the argument really is a Kotlin object and the callee's parameter is declared as
 * one -- and wrong for a parameter declared `PyObject`, where the callee wants the Python object
 * itself. `UpcallTrampoline.toKotlinObject` reads an `int` as a handle and anything else as a
 * `PyObject`, so an unwrapped proxy reaches such a parameter as the Kotlin object behind it and
 * fails the entry's own cast.
 *
 * `fixture.library.RefHolder(var primary: PyObject?, ...)` is the shape, and `_rh.primary = _rh`
 * is the assignment: a real KSP-generated `SETTER` whose one parameter is declared `PyObject`.
 *
 * The two tests are deliberately the two sides of one decision. Fixing the first one by dropping
 * `_pm_unwrap` altogether would pass it and break [describeHolder]; fixing it by sniffing the
 * value (`isinstance`, or "does it look like a handle") would pass both and be wrong for any
 * `PyObject`-typed parameter that is handed a proxy of a *different* class.
 */
class ProxyObjectArgumentTest {

    @BeforeTest
    fun install() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        HandleTable.releaseAll()
        UpcallTable.install(FunctionTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
    }

    @AfterTest
    fun cleanup() {
        // The first test builds a genuine self-cycle through a Kotlin field, so dropping the name
        // is not enough on its own -- break the edge before collecting, or the handle survives
        // into the next test's baseline.
        Python3.exec("import gc\ntry:\n    _rh.primary = None\nexcept Exception:\n    pass\n_rh = None\ngc.collect()")
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    /**
     * `99acd830`'s report, reproduced on the shape that still shows it: a proxy that renders on the
     * plain `_PmObject` owner, handed to a parameter declared `PyObject?`. `_pm_unwrap` turns it
     * into its raw handle, the trampoline reads that `int` as a `HandleTable` entry, and the entry's
     * own `args[0] as PyObject?` cast fails on the Kotlin object it resolves to.
     */
    @Test
    fun aPlainProxyPassedToAPyObjectTypedParameterCrossesAsThePythonObject() {
        Python3.exec(
            """
            from fixture.library import Counter, echoObject
            _pm_c = Counter(0)
            _pm_e = echoObject(_pm_c)
            assert _pm_e is _pm_c, (
                'a PyObject-typed parameter must receive the proxy object itself, not the Kotlin '
                'object behind its handle; got ' + repr(_pm_e)
            )
            """.trimIndent(),
        )
    }

    /**
     * The same decision on a proxy that renders on the *GC* owner. `_PmGcObject` is deliberately not
     * an `_PmObject` (two solid bases cannot be combined), so `_pm_unwrap` already leaves it alone
     * and this passes today -- by accident of the owner it happens to have, not because anything
     * consulted the declared type. It is here so that fixing the two tests around it cannot break it.
     */
    @Test
    fun aProxyAssignedToAPyObjectTypedPropertyCrossesAsThePythonObject() {
        Python3.exec(
            """
            from fixture.library import RefHolder
            _rh = RefHolder(None, None)
            _rh.primary = _rh
            assert _rh.primary is _rh, (
                'a PyObject-typed parameter must receive the proxy object itself, not the Kotlin '
                'object behind its handle; got ' + repr(_rh.primary)
            )
            """.trimIndent(),
        )
    }

    /**
     * The other side, and the reason the decision cannot be "stop unwrapping": `describeHolder`'s
     * parameter is declared `fixture.library.RefHolder`, so the same proxy passed to it must still
     * be unwrapped to its handle and resolved back to the Kotlin object.
     */
    @Test
    fun aProxyPassedToAKotlinTypedParameterIsStillUnwrappedToItsHandle() {
        Python3.exec(
            """
            from fixture.library import RefHolder, describeHolder
            _rh = RefHolder(None, None)
            _rh.secondary = [1]
            _pm_d = describeHolder(_rh)
            assert _pm_d == 'primary=false,secondary=true', (
                'a parameter declared as a Kotlin type must still receive the Kotlin object; got '
                + repr(_pm_d)
            )
            """.trimIndent(),
        )
    }
}
