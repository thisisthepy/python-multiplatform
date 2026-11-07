package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.UpcallEntry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Issue #237 on Kotlin/Native: member functions of the walked klib's interfaces, called from Python on
 * the handles its factories return, through the generated table and the binding layer -- #215's path,
 * from a klib.
 *
 * - `Job(None)` is a `CompletableJob`: `complete()` is `True` the first time and `False` after, and
 *   `start()`, declared on its supertype `Job`, is reached through the ancestry the table carries.
 * - A `Semaphore` of two permits gives two and refuses a third until one is released.
 *
 * Red before #237: `complete` and `try_acquire` are `AttributeError`s, because no member was bound.
 */
class WalkedKlibArtifactMemberFunctionTest {

    @BeforeTest
    fun installBothLayers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        val mainGlobals = Python3.import("__main__").getAttr("__dict__")
        check(UpcallEntry.publish(mainGlobals.pointer)) { "the upcall bootstrap could not be published" }
        PythonProxySource.install()
        PythonxAdapter.install(emptySet())
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun aJobsMembersRunOnTheKotlinJob() {
        Python3.exec(
            """
            from kotlinx.coroutines import Job
            _km_job = Job(None)
            assert _km_job.complete() is True, 'complete() did not complete the job'
            assert _km_job.complete() is False, 'a completed job completed twice'
            _km_lazy = Job(None)
            assert _km_lazy.start() is False, 'Job() is already active, so start() is False'
            del _km_job, _km_lazy
            """.trimIndent(),
        )
    }

    @Test
    fun aSemaphoresPermitsAreCountedByKotlin() {
        Python3.exec(
            """
            from kotlinx.coroutines.sync import Semaphore
            _km_s = Semaphore(2, 0)
            assert _km_s.try_acquire() is True
            assert _km_s.try_acquire() is True
            assert _km_s.try_acquire() is False, 'a third permit out of two'
            _km_s.release()
            assert _km_s.try_acquire() is True, 'a released permit was not given back'
            del _km_s
            """.trimIndent(),
        )
    }
}
