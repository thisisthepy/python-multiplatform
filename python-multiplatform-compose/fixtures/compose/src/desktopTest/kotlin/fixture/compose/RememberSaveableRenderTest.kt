package fixture.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import python.multiplatform.compose.PythonWidget
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **`rememberSaveableWrapper` from Python** (issue #174): the original pycomposeui's
 * `rememberSaveableWrapper(init, type)`, as `python.multiplatform.compose.rememberSaveableWrapper(initial)`,
 * reached through the walk (`RememberSaveableKt`) and called inside a Python root.
 *
 * The root asks for one state per supported type (Int, Long, Double, Boolean, String), draws them, and
 * keeps them in `_rs_states` so the test can write `.value` from Python. The scene is hosted inside
 * `CompositionLocalProvider(LocalSaveableStateRegistry provides registry)`; the test writes, saves the
 * registry, closes the scene, builds a new registry from the saved map and a new scene over the same
 * root, and reads the values the new states hold.
 *
 * ### Red before the implementation
 *
 * `from python.multiplatform.compose import rememberSaveableWrapper` is an `ImportError` raised by the
 * Python root when the first frame composes, so each test fails there and not at an assertion on a
 * value, which is what tells that red from a regression.
 */
class RememberSaveableRenderTest {

    @BeforeTest
    fun installProducers() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
        Python3.exec(ROOTS)
    }

    @AfterTest
    fun cleanup() {
        Python3.exec(
            """
            for _name in [n for n in globals() if n.startswith('_rs_')]:
                del globals()[_name]
            """.trimIndent(),
        )
        UpcallTable.clear()
    }

    /** Each supported type: initial value, written value, saved, restored into a new scene. */
    @Test
    fun everySupportedTypeSurvivesASaveAndARestoreThroughTheRegistry() {
        val saved = firstScene(canBeSaved = true)
        println("rememberSaveableWrapper: saved ${saved.size} entries")
        assertTrue(saved.isNotEmpty(), "nothing was saved, so the states are not registered with the registry")

        runScene(SaveableStateRegistry(restoredValues = saved, canBeSaved = { true }))
        assertEquals(WRITTEN, snapshotOfStates(), "the restored states do not hold the values saved")
        assertEquals(
            listOf("int", "int", "float", "bool", "str"),
            pyStr("[type(_rs_states[k].value).__name__ for k in 'ilfbs']").let { parseList(it) },
        )
    }

    /**
     * The check that can fail: the same flow hosted without a registry (so nothing can be saved) shows
     * the initial values again.
     */
    @Test
    fun withoutASaveableRegistryTheRecreatedSceneShowsTheInitialValues() {
        val saved = firstScene(canBeSaved = null)
        assertTrue(saved.isEmpty(), "a scene without a registry still saved $saved")

        runScene(null)
        assertEquals(INITIAL, snapshotOfStates(), "values came back although nothing was saved")
    }

    /** A value of any other type is an error that names the type. */
    @Test
    fun anUnsupportedTypeRaisesAnErrorNamingTheType() {
        val scene = ImageComposeScene(width = 200, height = 60, density = Density(1f)) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides SaveableStateRegistry(null) { true }) {
                PythonWidget("_rs_root_bad", moduleName = "__main__")
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        val message = pyStr("_rs_error[0]")
        println("rememberSaveableWrapper unsupported: $message")
        assertTrue("list" in message, "the error does not name the Python list type: $message")
    }

    private fun firstScene(canBeSaved: Boolean?): Map<String, List<Any?>> {
        val registry = canBeSaved?.let { SaveableStateRegistry(restoredValues = null, canBeSaved = { _ -> it }) }
        val scene = host(registry)
        try {
            scene.render()
            assertEquals(INITIAL, snapshotOfStates(), "the states did not start at the values Python passed")
            Python3.exec("_rs_states['i'].value = 8; _rs_states['l'].value = 6000000000; _rs_states['f'].value = 2.5; _rs_states['b'].value = False; _rs_states['s'].value = 'xyz'")
            Snapshot.sendApplyNotifications()
            scene.render()
            assertEquals(WRITTEN, snapshotOfStates(), "the write from Python was not read back")
            return registry?.performSave() ?: emptyMap()
        } finally {
            scene.close()
        }
    }

    private fun runScene(registry: SaveableStateRegistry?) {
        Python3.exec("_rs_states.clear()")
        val scene = host(registry)
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }

    private fun host(registry: SaveableStateRegistry?) =
        ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            if (registry == null) {
                PythonWidget("_rs_root", moduleName = "__main__")
            } else {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    PythonWidget("_rs_root", moduleName = "__main__")
                }
            }
        }

    private fun snapshotOfStates(): String = pyStr("[_rs_states[k].value for k in 'ilfbs']")

    private fun parseList(text: String): List<String> =
        text.removePrefix("[").removeSuffix("]").split(", ").map { it.trim('\'') }

    private fun pyStr(expression: String): String = Python3.import("__main__").getAttr("__dict__").let { globals ->
        Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
    }

    private companion object {
        /** `Py_eval_input`. */
        const val PY_EVAL_INPUT = 258
        const val INITIAL = "[7, 5000000000, 1.5, True, 'abc']"
        const val WRITTEN = "[8, 6000000000, 2.5, False, 'xyz']"

        val ROOTS = """
            _rs_states = {}
            _rs_error = []

            def _rs_root():
                from python.multiplatform.compose import rememberSaveableWrapper
                from androidx.compose.foundation.layout import Column
                from androidx.compose.material3 import Text
                _rs_states['i'] = rememberSaveableWrapper(7)
                _rs_states['l'] = rememberSaveableWrapper(5000000000)
                _rs_states['f'] = rememberSaveableWrapper(1.5)
                _rs_states['b'] = rememberSaveableWrapper(True)
                _rs_states['s'] = rememberSaveableWrapper('abc')
                def _rs_body():
                    for _k in 'ilfbs':
                        Text(str(_rs_states[_k].value))
                Column(content=_rs_body)

            def _rs_root_bad():
                from python.multiplatform.compose import rememberSaveableWrapper
                try:
                    rememberSaveableWrapper([1, 2])
                except BaseException as _e:
                    _rs_error.append(type(_e).__name__ + ': ' + str(_e))
        """.trimIndent()
    }
}
