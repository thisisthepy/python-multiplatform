package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.UpcallEntry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Issue #234 on Kotlin/Native: a Kotlin object a walked klib declaration returns crosses as a handle,
 * goes back into Kotlin as that object, and follows the jar side's lifetime rules -- the common
 * trampoline's and `HandleTable`'s, which the klib boundary uses unchanged.
 *
 * - A handle Python holds is one `HandleTable` slot; passing it back into Kotlin (`Job(parent)`)
 *   neither releases it nor takes a second slot.
 * - Dropping every Python reference releases each slot exactly once: the live count returns to its
 *   baseline, the raw handle no longer resolves, and a second release is refused (no double release).
 *
 * Red before #234: `Job` is not importable, because the walker declined every `kotlinx.coroutines`
 * declaration.
 */
class WalkedKlibArtifactHandleTest {

    @BeforeTest
    fun installTheWalkersTableIntoAnInterpreter() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        val mainGlobals = Python3.import("__main__").getAttr("__dict__")
        check(UpcallEntry.publish(mainGlobals.pointer)) { "the upcall bootstrap could not be published" }
        PythonProxySource.install()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun aHandleGoesBackIntoKotlinAndIsReleasedExactlyOnce() {
        Python3.exec("import gc\ngc.collect()")
        val baseline = HandleTable.liveCount
        Python3.exec(
            """
            from kotlinx.coroutines import Job
            _kh_parent = Job(None)
            _kh_raw = _kh_parent._pm_handle
            _kh_children = [Job(_kh_parent) for _ in range(3)]
            assert _kh_parent._pm_handle == _kh_raw, 'passing the handle into Kotlin changed it'
            """.trimIndent(),
        )
        // One slot for the parent and one per child: passing the parent in took no slot of its own.
        assertEquals(baseline + 4, HandleTable.liveCount)
        val raw = Python3.import("__main__").getAttr("_kh_raw").toString().toLong()
        assertNotNull(HandleTable.resolveRaw(raw), "the parent's handle should still resolve while Python holds it")

        Python3.exec("del _kh_parent, _kh_children\nimport gc\ngc.collect()")
        assertEquals(baseline, HandleTable.liveCount, "every slot is released exactly once")
        assertNull(HandleTable.resolveRaw(raw), "a released handle must not resolve")
        assertFalse(HandleTable.release(ObjectReference(raw)), "a second release must be refused")
    }

    @Test
    fun aNullableHandleParameterTakesNone() {
        Python3.exec(
            """
            from kotlinx.coroutines import Job
            _kh_job = Job(None)
            assert _kh_job is not None
            del _kh_job
            """.trimIndent(),
        )
    }
}
