package fixture.desktopnative

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC L-9c through the host test task: the test executable links the staged `libpython3.15t` and
 * `<target>Test` sets `PYTHONHOME` to the staged prefix. Red before: the plugin added no linker
 * option, so the test binary failed to link (`undefined symbol: Py_Initialize` and the rest).
 */
class ProbeTest {
    @Test
    fun theStagedFreeThreadedInterpreterRuns() {
        assertEquals("DESKTOP_NATIVE_PROBE stdlib=ok ext=ok ft=ok", probeLine())
    }
}
