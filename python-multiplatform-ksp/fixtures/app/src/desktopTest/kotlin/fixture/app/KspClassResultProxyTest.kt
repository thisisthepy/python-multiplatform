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
 * #94 (SPEC U-11): a KSP function or method returning a Kotlin class gives that class's rendered
 * proxy, not a generic `_PmObject`. Ownership is not what is under test here.
 *
 * Red before the fix: `isinstance(_c, Counter)` is False and `_c.increment` raises
 * `AttributeError: '_PmObject' object has no attribute 'increment'`.
 */
class KspClassResultProxyTest {

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
        Python3.exec("_c = None\n_t = None\nimport gc\ngc.collect()")
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun aTopLevelFunctionReturningAClassGivesTheClassProxy() {
        Python3.exec(
            """
            from fixture.library import Counter, makeCounter
            _c = makeCounter(5)
            assert isinstance(_c, Counter), 'expected a Counter proxy, got ' + repr(type(_c))
            assert _c.increment(2) == 7, 'method call on the result failed'
            assert _c.count == 7, 'property read on the result failed; got ' + repr(_c.count)
            assert _c.label('n=') == 'n=7'
            """.trimIndent(),
        )
    }

    @Test
    fun aMethodReturningAClassGivesTheClassProxy() {
        Python3.exec(
            """
            from fixture.library import Counter
            _c = Counter(4)
            _t = _c.twin()
            assert isinstance(_t, Counter), 'expected a Counter proxy, got ' + repr(type(_t))
            assert _t is not _c
            assert _t.increment(1) == 5
            assert _c.count == 4, 'the twin must be a separate Kotlin object'
            """.trimIndent(),
        )
    }

    /** A result is itself a proxy, so a method on it can return another. */
    @Test
    fun aReturnedProxyCanBeChained() {
        Python3.exec(
            """
            from fixture.library import makeCounter
            _c = makeCounter(1)
            _c2 = _c.twin()
            assert _c2.count == 1
            """.trimIndent(),
        )
    }
}
