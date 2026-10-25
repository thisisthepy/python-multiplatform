package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Issue #322 (SPEC U-4, B-1): `Modifier.Companion` from the real Compose jars is a `Modifier` from
 * Python. The walker binds the companion object itself (`androidx.compose.ui.Modifier.Companion`,
 * generated Kotlin `Modifier.Companion`); the proxy class serves it as an attribute.
 *
 * Red before the change: no `androidx.compose.ui.Modifier.Companion` key is bound, so the attribute
 * read raises AttributeError.
 */
class WalkedArtifactCompanionHandleTest {

    @BeforeTest
    fun install() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(PYTHONX_RAW_VALUE_CLASSES)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    @Test
    fun theCompanionKeyIsBound() {
        assertTrue(UpcallTable.resolve("androidx.compose.ui.Modifier.Companion").isValid)
    }

    @Test
    fun modifierCompanionIsAModifierAndTwoReadsCompareEqual() {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            from fixture.artifact import isTheEmptyModifier
            _a = Modifier.Companion
            _b = Modifier.Companion
            assert isinstance(_a, Modifier), type(_a)
            assert _a == _b, 'two reads of Modifier.Companion are not =='
            assert isTheEmptyModifier(_a), 'Modifier.Companion is not Compose\'s empty Modifier'
            assert not hasattr(Modifier, 'companion'), 'a lower-case companion alias must not exist'
            """.trimIndent(),
        )
    }

    // Issue #323: an explicit `import androidx.compose.ui.Modifier` makes the import system bind the
    // module onto its parent after the loader returns; the class must stay. Each test first drops the
    // module from sys.modules so the adapter's finder path (the one that sets the parent attribute) runs.
    private val dropModule = """
        import sys
        import androidx.compose.ui as _ui
        sys.modules.pop('androidx.compose.ui.Modifier', None)
        """.trimIndent()

    @Test
    fun explicitImportOfTheSameNamedModuleKeepsTheClass() {
        Python3.exec(
            dropModule + "\n" + """
            import androidx.compose.ui.Modifier
            from androidx.compose.ui import Modifier
            assert isinstance(Modifier, type), type(Modifier)
            assert isinstance(Modifier.Companion, Modifier), type(Modifier.Companion)
            """.trimIndent(),
        )
    }

    @Test
    fun classFirstThenExplicitImportKeepsTheClass() {
        Python3.exec(
            dropModule + "\n" + """
            from androidx.compose.ui import Modifier
            import androidx.compose.ui.Modifier
            import androidx.compose.ui as _ui2
            assert _ui2.Modifier is Modifier, type(_ui2.Modifier)
            assert isinstance(Modifier.Companion, Modifier)
            """.trimIndent(),
        )
    }

    @Test
    fun alreadyImportedModuleKeepsTheClass() {
        Python3.exec(
            """
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.Modifier
            from androidx.compose.ui import Modifier
            assert isinstance(Modifier, type), type(Modifier)
            """.trimIndent(),
        )
    }

    @Test
    fun importAsBindsTheClass() {
        Python3.exec(
            dropModule + "\n" + """
            import androidx.compose.ui.Modifier as M
            from androidx.compose.ui import Modifier
            assert M is Modifier, M
            assert isinstance(M.Companion, M)
            """.trimIndent(),
        )
    }
}
