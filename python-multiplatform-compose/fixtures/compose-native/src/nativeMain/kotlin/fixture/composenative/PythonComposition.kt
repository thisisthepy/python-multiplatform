package fixture.composenative

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import python.multiplatform.compose.PythonCallableArena
import python.multiplatform.compose.withPythonComposer
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallables

/**
 * Runs [source] as Python inside this composition, with the composer and a callable scope the
 * composition owns: the same entry point `:python-multiplatform-compose:fixtures:compose` uses on the
 * JVM (its `PythonComposition.kt`), here in common native code.
 */
@Composable
fun PythonComposition(source: String) {
    val arena = remember { PythonCallableArena() }
    withPythonComposer {
        PythonCallables.withScope(arena.scope) {
            Python3.exec(source)
        }
    }
}
