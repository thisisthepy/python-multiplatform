package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.UpcallEntry
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The whole klib path in one interpreter, on a **Kotlin/Native** target: a `.klib` the build
 * resolved -> `KlibScanner` -> an (empty) `ArtifactTable` -> `UpcallTable` -> `PythonProxySource`.
 *
 * `:ksp-fixtures:artifact`'s `WalkedArtifactPythonImportTest` made a *positive* version of this claim
 * for a **jar** on **desktop**: a bound declaration reaches Python and is callable. This class makes
 * the negative half instead -- see `WalkedKlibArtifactTableTest`'s KDoc for why `kotlinx-coroutines
 * -core`'s klib half of the shared namespace is correctly *empty*, not merely small. Both matter: a
 * walker that silently produced no bindings would look identical to one that correctly declined
 * everything it saw, and only running the interpreter and asking Python whether the name exists tells
 * the two apart from a bound declaration's failure mode (an `AttributeError`/`ModuleNotFoundError`
 * either way looks the same from the Kotlin side, since nothing there ever calls into the klib).
 *
 * The bootstrap is [UpcallEntry.publish] -- five real `PyMethodDef`s installed into `__main__`, which
 * is what `python-multiplatform`'s own `nativeTest` uses and the only route a consumer module has on
 * this target (no Panama, no `ctypes.CFUNCTYPE`; see `WalkedArtifactPythonImportTest`'s KDoc for what
 * desktop uses instead).
 *
 * ### Running it
 *
 * There is no Gradle task; see `WalkedKlibArtifactTableTest`'s KDoc for why, and this module's
 * `build.gradle.kts` for the `adb` command that runs the linked binary on a device.
 */
class WalkedKlibArtifactPythonImportTest {

    @BeforeTest
    fun installTheWalkersTableIntoAnInterpreter() {
        Python3.initialize(silent = true)
        UpcallTable.clear()
        // Both producers, one table -- KSP's fragment for this module's own source and the walker's
        // (empty) contribution for the klib. `ArtifactTable.fragments` is empty, which is exactly
        // this class's point: installing it must be a no-op, not an error.
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        // The five names `PythonProxySource`'s guard requires. On desktop these are `ctypes`
        // callbacks over Panama stubs; on Kotlin/Native they are ordinary `PyMethodDef`s, because
        // Python and Kotlin are the same binary here and there is no boundary to bridge.
        val mainGlobals = Python3.import("__main__").getAttr("__dict__")
        check(UpcallEntry.publish(mainGlobals.pointer)) { "the upcall bootstrap could not be published" }
        PythonProxySource.install()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
    }

    /**
     * KSP's half of the shared namespace still reaches Python, exactly as `WalkedArtifactPythonImportTest`
     * proves for the JVM producer's own declarations.
     *
     * `fixture.artifact` is this module's Kotlin package, reached through KSP's `FunctionTable`.
     * `OwnDeclarations.kt`'s KDoc records why this file exists at all: an aggregator module with no
     * source declarations of its own would emit an empty `FunctionTable` too, and this is what proves
     * the emptiness this class's other test pins is specific to the klib walker, not a bootstrap
     * failure that would have hidden both producers equally.
     */
    @Test
    fun kspsHalfOfTheSharedNamespaceIsImportableFromPython() {
        Python3.exec(
            """
            from fixture.artifact import whichSideAmIFrom

            assert whichSideAmIFrom() == 'ksp'
            """.trimIndent(),
        )
    }

    /**
     * The klib walker's decline is real all the way through the interpreter, not only a Kotlin-side
     * fact this suite takes on faith.
     *
     * `checkIndexOverflow` is `@PublishedApi internal` (`WalkedKlibArtifactTableTest`'s KDoc), so
     * `ArtifactTable.fragments` never creates a `kotlinx` module at all -- there is no partial
     * registration to find a stale attribute on, only the ordinary Python import machinery failing to
     * locate a package that was never created. `assertFailsWith`-style would only prove *something*
     * raised; this reads the exception's own type from inside Python so a *different* failure (an
     * `AttributeError` from a half-built module, say) would not pass for the right reason.
     */
    @Test
    fun theDeclinedKlibDeclarationNeverReachesPython() {
        Python3.exec(
            """
            try:
                from kotlinx.coroutines.flow.internal import checkIndexOverflow
            except ModuleNotFoundError as e:
                pass
            else:
                raise AssertionError('kotlinx.coroutines.flow.internal imported; the decline did not reach Python')
            """.trimIndent(),
        )
    }
}
