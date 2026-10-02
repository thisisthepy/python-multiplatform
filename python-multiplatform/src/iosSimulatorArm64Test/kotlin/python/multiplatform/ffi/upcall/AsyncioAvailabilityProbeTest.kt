package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `docs/design/upcall-async-design.md` §10.6: whether `asyncio` exists on the iOS distribution has never
 * been measured, only assumed absent by analogy with `_ctypes` (`docs/design/upcall-design.md` §"Can
 * Python call an address at all?"). That analogy does not hold on inspection -- the BeeWare
 * archive `extractIosSimulatorStdlib` unpacks *does* carry `lib-dynload/_asyncio*.so`,
 * `lib-dynload/_socket*.so` and `lib-dynload/select*.so`, unlike this project's `Python.xcframework`
 * itself, which ships no `lib-dynload` at all. Those are two different things: the xcframework
 * binary the app links against, and the separately-unpacked stdlib prefix `PYTHONHOME` points at.
 *
 * This is deliberately its own file and its own tiny probe rather than folded into a bigger test,
 * so a trap here (the wasmJs shape `AsyncUpcallPortabilityTest` documents: `import asyncio`
 * killing the whole process rather than raising) is isolated to one file and easy to bisect.
 *
 * Lives in `iosSimulatorArm64Test` specifically, not `nativeTest`, so it never reaches
 * androidNative or a real iOS device -- neither has been asked for here, and `nativeTest` would
 * put it on both without evidence either needs it.
 */
class AsyncioAvailabilityProbeTest {

    @Test
    fun importingAsyncioSucceedsAndAMinimalLoopRuns() = PythonTestFixture.withInterpreter {
        // A raise here is a real Python exception (ImportError, e.g.) and fails the test with its
        // message; a trap (the wasmJs shape) does not raise at all -- it kills the process, which
        // would show up as this test never reporting a result rather than failing normally.
        Python3.exec(
            """
            import asyncio

            async def _pmp_probe():
                return 41 + 1

            _pmp_result = asyncio.new_event_loop().run_until_complete(_pmp_probe())
            """.trimIndent(),
        )
        assertEquals("42", PythonTestFixture.eval("_pmp_result").toString())
    }
}
