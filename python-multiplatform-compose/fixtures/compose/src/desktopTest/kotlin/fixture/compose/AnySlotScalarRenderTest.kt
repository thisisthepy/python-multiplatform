package fixture.compose

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import python.multiplatform.compose.PythonWidget
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #69 against the real Compose runtime: a counter declared from Python as
 * `mutableStateOf(0)` -- the walked generic function, its `T` read as `kotlin.Any?` -- holds a Kotlin
 * `Int`, and incrementing it from Python (`counter.value = counter.value + 1`, the walked
 * `MutableState.value` getter and setter) recomposes the root that read it.
 *
 * Modelled on [PythonAppViewRenderTest]: `ImageComposeScene` has no frame clock, so the test is the
 * clock (`Snapshot.sendApplyNotifications()` and a render), and the root counts its own invocations so
 * a frame can be attributed.
 *
 * ### Red before #69, and what that red says
 *
 * The first statement, `mutableStateOf(0)`, raised `TypeError: mutableStateOf(value: Any): an int
 * cannot be stored in a Kotlin Any?: it would cross as an object handle`. A *regression* reads
 * differently: a root that is not composed again after the write (the state was not a snapshot
 * state, or the read was not observed), or a Kotlin value that is a `Long` rather than an `Int`.
 */
class AnySlotScalarRenderTest {

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
        Python3.exec(
            """
            for _asr_name in ('_asr_counter', '_asr_root', '_asr_seen'):
                globals().pop(_asr_name, None)
            """.trimIndent(),
        )
        UpcallTable.clear()
    }

    @Test
    fun aCounterDeclaredAsMutableStateOfZeroAndIncrementedFromPythonRecomposes() {
        Python3.exec(
            """
            from androidx.compose.runtime import mutableStateOf
            from androidx.compose.material3 import Text

            _asr_counter = mutableStateOf(0)
            _asr_seen = []

            def _asr_root():
                _asr_n = _asr_counter.value
                _asr_seen.append(_asr_n)
                Text('count ' + str(_asr_n))
            """.trimIndent(),
        )
        assertEquals("0", pyStr("repr(_asr_counter.value)"))
        assertEquals("int", pyStr("type(_asr_counter.value).__name__"))
        assertEquals(0, kotlinValue(), "Kotlin does not hold the Int 0")

        val root = Python3.import("__main__").getAttr("_asr_root")
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            PythonWidget(root = root)
        }
        try {
            scene.render()
            assertEquals("[0]", pyStr("repr(_asr_seen)"), "the root was not composed once with the initial count")

            // An idle frame is not a reason to compose again: nothing here polls.
            scene.render()
            assertEquals("[0]", pyStr("repr(_asr_seen)"), "an idle frame re-ran the root")

            Python3.exec("_asr_counter.value = _asr_counter.value + 1")
            assertEquals(1, kotlinValue(), "the increment is not a Kotlin Int 1")
            nextFrame(scene)
            assertEquals("[0, 1]", pyStr("repr(_asr_seen)"), "the increment did not recompose the root")

            Python3.exec("_asr_counter.value = _asr_counter.value + 1")
            nextFrame(scene)
            assertEquals("[0, 1, 2]", pyStr("repr(_asr_seen)"), "the second increment did not recompose the root")
            assertTrue(
                pyStr("all(type(_asr_x) is int for _asr_x in _asr_seen)") == "True",
                "a count read during composition was not a Python int: ${pyStr("repr([type(_asr_x) for _asr_x in _asr_seen])")}",
            )
        } finally {
            scene.close()
            root.close()
        }
        assertEquals(2, kotlinValue())
    }

    /**
     * What the Kotlin `MutableState` behind the Python proxy holds, read on the Kotlin side: the
     * issue's rule is `kotlin.Int` for a value that fits in 32 bits, and an `Int` is not equal to a
     * `Long` of the same value, so this tells the two apart.
     */
    private fun kotlinValue(): Any? {
        val raw = pyStr("_asr_counter._pm_handle").toLong()
        val state = HandleTable.resolveRaw(raw) as MutableState<*>
        return state.value
    }

    private fun nextFrame(scene: ImageComposeScene) {
        Snapshot.sendApplyNotifications()
        scene.render()
    }

    private fun pyStr(expression: String): String = Python3.import("__main__").getAttr("__dict__").let { globals ->
        Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
    }

    private companion object {
        /** `Py_eval_input`. */
        const val PY_EVAL_INPUT = 258
    }
}
