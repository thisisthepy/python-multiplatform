package python.multiplatform.env

import kotlin.test.Test

/**
 * The subset of [PythonHomeCheck]'s behaviour that has to hold on every target, including
 * Kotlin/Wasm, where `PYTHONHOME` names nothing a user sets -- the stdlib is preloaded into the
 * virtual filesystem at build time (see [readEnvVar]'s wasmJs actual). The platform-specific
 * diagnoses for a bad `PYTHONHOME` live in `PythonHomeCheckTest` (desktopTest): they depend on
 * real file permissions, which `pathIsAccessible`'s wasmJs actual does not implement.
 */
class PythonHomeCheckSmokeTest {

    @Test
    fun unsetOrBlankHomeIsNotDiagnosed() {
        PythonHomeCheck.verifyOrThrow(null)
        PythonHomeCheck.verifyOrThrow("")
        PythonHomeCheck.verifyOrThrow("   ")
    }

    @Test
    fun realEnvironmentPassesInThisTestBinary() {
        // Whatever this test binary's real PYTHONHOME situation is -- set to a valid prefix, or
        // unset because the target has no such concept -- Python3.initialize() already relies on
        // it not throwing here (PythonTestFixture initialises the shared interpreter every other
        // test in this suite uses). If this throws, every functional test downstream would too,
        // with a less specific message.
        PythonHomeCheck.verifyOrThrow()
    }
}
