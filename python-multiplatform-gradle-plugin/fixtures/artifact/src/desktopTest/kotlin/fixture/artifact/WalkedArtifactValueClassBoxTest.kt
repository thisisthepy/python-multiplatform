package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import python.multiplatform.ffi.pythonx.PythonxAdapter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Issue #168: a value class parameter takes the boxed value of its own class, which is what Kotlin
 * itself would pass, and still refuses a raw number unless the type is on the raw-primitive allowlist.
 *
 * `TextUnitType` is a value class over `Long` with an internal property, so `TextUnitType.Sp` reaches
 * Python only as a boxed handle. `TextUnit(30, TextUnitType.Sp)` used to fail with
 * `expected a number for TextUnitType`.
 */
class WalkedArtifactValueClassBoxTest {

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
    fun aBoxedValueClassFillsItsOwnParameter() {
        Python3.exec(
            """
            from androidx.compose.ui.unit import TextUnit, TextUnitType
            from fixture.artifact import equalsSp, describeValue

            _sp = TextUnit(30, TextUnitType.Sp)
            assert equalsSp(_sp._pm_handle, 30.0), 'TextUnit(30, TextUnitType.Sp) is not 30.sp: ' + describeValue(_sp._pm_handle) + ' / ' + describeValue(TextUnitType.Sp._pm_handle)
            assert not equalsSp(_sp._pm_handle, 31.0), '30.sp matched 31.sp'
            """.trimIndent(),
        )
    }

    @Test
    fun aRawNumberStillDoesNotFillAValueClassOffTheAllowlist() {
        Python3.exec(
            """
            from androidx.compose.ui.unit import TextUnit

            # The slot is an object slot now, so a bare number is read as a handle and Kotlin refuses it
            # (no live handle, or a handle that is not a TextUnitType): it is never reinterpreted as
            # the packed Long, which would be a TextUnitType of the wrong unit and no error.
            try:
                _result = TextUnit(30, 4294967296)
            except Exception:
                pass
            else:
                raise AssertionError('a raw number was accepted for TextUnitType: ' + repr(_result))
            """.trimIndent(),
        )
    }
}
