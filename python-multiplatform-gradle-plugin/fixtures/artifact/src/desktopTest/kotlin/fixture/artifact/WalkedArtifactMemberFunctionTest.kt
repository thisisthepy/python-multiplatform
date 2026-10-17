package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Issue #215, end to end: member functions of a walked class and interface
 * (`fixture.valueclass.member`, from the `artifact-valueclass` jar) are called from Python on the
 * handles Kotlin returned, through the generated table and the binding layer.
 *
 * Red before #215: `tally.add(3)` and `switch.turn_off()` are `AttributeError`s, because the walker
 * bound only the constructor and the properties.
 */
class WalkedArtifactMemberFunctionTest {

    @BeforeTest
    fun installBothProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun aClassesMembersRunOnTheKotlinObject() {
        Python3.exec(
            """
            from fixture.valueclass.member import Tally
            _mf_t = Tally(1)
            assert _mf_t.add(3) == 4, 'add(Int) did not run on the Kotlin object'
            assert _mf_t.add(1, 'n=') == 'n=5', 'add(Int, String) was not chosen by its arguments'
            assert _mf_t.add__Int(10) == 15, 'the explicit overload name did not reach add(Int)'
            assert _mf_t.total == 15, 'the members did not change the object the proxy holds'
            _mf_twin = _mf_t.twin()
            _mf_t.reset()
            assert _mf_t.total == 0, 'reset() did not run'
            assert _mf_twin.total == 15, "a member's handle result is not a separate Kotlin object"
            assert type(_mf_twin)._kotlin_type_name == 'fixture.valueclass.member.Tally', type(_mf_twin)._kotlin_type_name
            """.trimIndent(),
        )
    }

    @Test
    fun anInterfacesMemberRunsOnTheObjectBehindIt() {
        Python3.exec(
            """
            from fixture.valueclass.member import newSwitch
            _mf_s = newSwitch()
            assert _mf_s.on is True
            _mf_s.turn_off()
            assert _mf_s.on is False, 'turn_off() did not run on the object behind the interface'
            """.trimIndent(),
        )
    }

    /** Issue #217: at run time the factory owns the name, and the value it makes keeps its members. */
    @Test
    fun aValueOfATypeWhoseNameAFactoryTakesKeepsItsMembers() {
        Python3.exec(
            """
            from fixture.valueclass.member import Gauge
            _mf_g = Gauge()
            assert _mf_g.level == 0, repr(_mf_g.level)
            _mf_g.bump()
            assert _mf_g.level == 1, 'the member did not run on the value the factory made'
            """.trimIndent(),
        )
    }
}
