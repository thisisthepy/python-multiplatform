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
}
