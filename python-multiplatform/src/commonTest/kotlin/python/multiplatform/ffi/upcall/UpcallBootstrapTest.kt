package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [UpcallBootstrap] is the public, per-platform entry point ROADMAP §16f's gap was about: before it
 * existed, a consumer outside `python-multiplatform` had no route to the four names
 * [PythonProxySource.install]'s entry-point guard requires -- only this module's own **test**
 * source (`UpcallEntryBridge.desktop.kt`) rebuilt them, by hand, from the public
 * `python.native.ffi.UpcallStub` addresses. `ksp-fixtures/artifact`'s
 * `WalkedArtifactPythonImportTest` copied that workaround because there was nothing else to call;
 * it now calls this instead.
 */
class UpcallBootstrapTest {

    /** The contract [PythonProxySource]'s `ENTRY_POINT_GUARD` (and, for cancellation, `_pm_watch`) checks for. */
    @Test
    fun publishingBindsEveryNamePythonProxySourceNeeds() = PythonTestFixture.withInterpreter {
        assertTrue(UpcallBootstrap.publishToGlobals(), "publishToGlobals() reported failure")
        for (name in listOf("_pm_resolve", "_pm_invoke", "_pm_release", "_pm_cancel")) {
            assertEquals(
                "True",
                PythonTestFixture.eval("'$name' in globals()").toString(),
                "$name was not published to __main__",
            )
        }
    }

    /**
     * Every platform's install step is `PyDict_SetItemString`, an unconditional overwrite rather
     * than a "define once" guard -- so a second call must rebind cleanly rather than refuse or
     * leave a half-updated set of names, and the entry points must still work afterwards.
     */
    @Test
    fun publishingASecondTimeStillLeavesWorkingEntryPoints() = PythonTestFixture.withInterpreter {
        assertTrue(UpcallBootstrap.publishToGlobals(), "first publishToGlobals() reported failure")
        assertTrue(UpcallBootstrap.publishToGlobals(), "second publishToGlobals() reported failure")
        // `_pm_resolve` on a name nothing claims answers -1 rather than raising -- exercised here
        // as a live call through whatever `_pm_resolve` is bound to right now, not merely a name
        // check, so a bootstrap that silently rebinds to something broken would be caught too.
        Python3.exec("_pm_h = _pm_resolve('upcall.bootstrap.test.nothing.claims.this'.encode('utf-8'))")
        assertEquals("-1", PythonTestFixture.eval("_pm_h").toString())
    }
}
