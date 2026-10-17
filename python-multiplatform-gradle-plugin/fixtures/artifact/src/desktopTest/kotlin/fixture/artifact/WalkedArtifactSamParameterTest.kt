package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables
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
 * Issue #216, end to end: a Python callable fills a `java.lang.Runnable` and a Kotlin `fun interface`
 * parameter of a walked declaration (`fixture.valueclass.sam`), and Kotlin runs it.
 *
 * Red before #216: `run_runnable` and `notify_listener` do not exist, because the walker declined both.
 */
class WalkedArtifactSamParameterTest {

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
    fun aPythonCallableRunsAsARunnable() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                from fixture.valueclass.sam import run_runnable
                _sam_ran = []
                run_runnable(lambda: _sam_ran.append('ran'))
                assert _sam_ran == ['ran'], 'the Runnable did not run the Python callable: ' + repr(_sam_ran)
                """.trimIndent(),
            )
        }
    }

    @Test
    fun aPythonCallableRunsAsAFunInterfaceWithItsArguments() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                from fixture.valueclass.sam import notify_listener
                _sam_events = []
                notify_listener(lambda name, count: _sam_events.append((name, count)), 'hello')
                assert _sam_events == [('hello', 5)], repr(_sam_events)
                """.trimIndent(),
            )
        }
    }

    @Test
    fun aKotlinImplementationStillFillsTheSameSlot() {
        Python3.exec(
            """
            from fixture.valueclass.sam import notify_listener, logging_listener, last_heard
            notify_listener(logging_listener(), 'abc')
            assert last_heard() == 'abc:3', 'a Kotlin Listener handle did not reach the slot: ' + repr(last_heard())
            """.trimIndent(),
        )
    }
}
