package fixture.compose

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import python.multiplatform.ffi.PyObject

/**
 * The Python side of `PythonContentRenderTest`: a Compose [MutableState] that Python creates, holds
 * and writes, standing in for what a Pythonic package (pythonx-compose's `@app`) does with its own.
 *
 * Bound by KSP as plain top-level functions (`fixture.compose.rootState`, `fixture.compose.writeRoot`)
 * because the artefact walker binds static functions only, and `MutableState.value`'s setter is an
 * instance method. What the entry point under test reads is the [MutableState] itself; how a package
 * writes into it is that package's business, so this is the smallest writer there is.
 *
 * `root` is declared [PyObject] -- not `Any` -- so a Python function crosses as itself rather than
 * being unwrapped as a Kotlin handle (`PythonProxySource.argValues`'s one exception).
 */
fun rootState(): MutableState<PyObject?> = mutableStateOf(null)

/** `state.value = root`, called from Python. An ordinary snapshot write; nothing else. */
fun writeRoot(state: MutableState<PyObject?>, root: PyObject) {
    state.value = root
}
