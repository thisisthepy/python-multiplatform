package fixture.artifact

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonCallableScope
import python.multiplatform.ffi.pythonx.PythonCallables
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
 * **A Python lambda in a walked, non-`@Composable` declaration** -- `Modifier.clickable(onClick=…)`
 * out of the real `foundation-desktop` jar, and a stdlib declaration that actually *runs* the
 * callable it is handed.
 *
 * ### What this is the end of
 *
 * `a179b747` made a function-typed slot describable and fillable, and both halves are producer-
 * independent -- `ArtifactScanner.functionSlotTypeName` writes the signature, `pythonx._make_function`
 * builds a `FunctionN` out of it. Only composables could use them, because only composables reach
 * their callee through generated **bytecode**: a `CHECKCAST` to `kotlin/jvm/functions/FunctionN`
 * needs no Kotlin name, while generated **source** has to spell the type it casts to, and
 * `resolveKotlinType` correctly refuses to spell a classifier with no class file anywhere.
 * `ArtifactScannerTest` counted the price: 44 of the 46 declined public top-level `Modifier`
 * extensions declined for exactly that.
 *
 * ### Why `measureTimeMillis` is here beside `clickable`
 *
 * Because **no Compose callback runs outside a composition**, and a test that only ever asserts "the
 * call returned a Modifier" cannot tell a wrapper Compose would invoke from one it would not.
 * `clickable` is `composed { }`, `drawBehind` is a node, `semantics` is an element -- every one of
 * them *stores* the lambda for a composition to call later. `kotlin.system.measureTimeMillis(block:
 * () -> Unit)` has the identical slot (`kotlin.Function0()->kotlin.Unit`, same walker, same
 * fragment, same `PythonFunction` wrapper) and invokes it at the moment it is called, so it is what
 * makes "the callback is called" a measurement rather than an inference. Rendering a real click is
 * `:ksp-fixtures:compose`'s job and needs the Compose compiler plugin this module deliberately does
 * not apply.
 *
 * ### Lifetime
 *
 * A Python callable crossing into Kotlin needs a [PythonCallableScope] open -- Compose keeps calling
 * a `content` long after the statement that wrote it dropped Python's last reference, so something on
 * the Kotlin side has to hold one, and the scope is the only candidate that closes at the right time
 * (`PythonCallables`' KDoc has the other three and why they fail). Outside a composition that scope
 * is explicit, which makes it something this test can count: [scope] holds exactly one wrapper per
 * callable that crossed, gives it back on `close`, and answers `0` for every later `close`.
 *
 * `agent-rules` §14 is why the leak and the double release are **separate** assertions with a second
 * scope in between: a root released twice does not fail where it happens, it fails when the slot it
 * freed has since been handed to somebody else.
 */
class WalkedArtifactCallbackTest {

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
        UpcallTable.clear()
    }

    /**
     * The measurement: a Python `lambda` handed to a walked Kotlin declaration **runs**.
     *
     * `_ran` is appended to from inside the lambda, so the assertion is about the callable having
     * been invoked and not about the call having returned. `_never` is the control for exactly that
     * -- an identically shaped lambda that was never passed anywhere, which must stay empty, so a
     * mutation that appended on *construction* rather than on invocation fails here.
     */
    @Test
    fun aPythonCallableHandedToAWalkedDeclarationIsInvoked() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                from kotlin.system import measureTimeMillis

                _ran = []
                _never = []
                _control = lambda: _never.append('should not run')

                _elapsed = measureTimeMillis(lambda: _ran.append('ran'))

                assert _ran == ['ran'], 'the Python callable was not invoked: ' + repr(_ran)
                assert _never == [], 'a callable nobody passed anywhere ran: ' + repr(_never)
                assert isinstance(_elapsed, int), (
                    'measureTimeMillis returns a Long, which crosses as INT; got ' + repr(_elapsed)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * The arity rule, from the Python side, which is what makes the test above non-vacuous in the
     * other direction.
     *
     * `() -> Unit` invokes with nothing, so a callable that *wants* an argument is refused at the
     * crossing rather than at the invocation -- which for a stored Compose callback would otherwise
     * be a failure on some later recomposition with no statement to blame. A callable that takes
     * fewer than the slot supplies is the opposite case and is allowed (`Column { Text(…) }`); there
     * is nothing to drop here, so this pins only the refusal.
     */
    @Test
    fun aCallableThatWantsMoreThanTheSlotSuppliesIsRefusedAtTheCrossing() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                from kotlin.system import measureTimeMillis
                try:
                    measureTimeMillis(lambda wanted: None)
                    raise AssertionError('a one-argument callable filled a zero-argument slot')
                except AssertionError:
                    raise
                except Exception as e:
                    assert '0 argument' in str(e), 'unexpected refusal: ' + repr(e)
                """.trimIndent(),
            )
        }
    }

    /**
     * The acceptance criterion: **`Modifier.clickable` called from Python, against the real Compose
     * jar**, with its `onClick` written as a Python lambda.
     *
     * Everything but the receiver and `onClick` is left to Kotlin, which is the shape a caller would
     * actually write and which exercises the presence branching and the new slot in the same call:
     * `enabled`, `onClickLabel` and `role` all declare defaults, and `onClickLabel: String?` is why
     * `clickable` stayed declined even after its lambda became bindable (a nullable `String` was
     * refused by a rule whose stated justification -- numeric narrowing -- does not apply to a
     * reference; see `nullablePrimitiveBoundaryTypeOf`).
     *
     * The controls are Compose's own structural equality and its own traversal, so neither can pass
     * because the boundary did nothing: an unclicked `Modifier` is `Modifier` itself and folds to
     * zero elements.
     *
     * The overload is `(enabled, onClickLabel, role, interactionSource, onClick)`: Compose 1.6's
     * `(enabled, onClickLabel, role, onClick)` is binary-only (hidden) by Compose Multiplatform 1.11,
     * and its source-visible successor adds a defaulted `interactionSource`, left to Kotlin here too.
     */
    @Test
    fun clickableIsBuiltInPythonFromTheRealComposeJar() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                from androidx.compose.foundation import clickable__Boolean_String_Role_MutableInteractionSource_Unit as clickable
                from fixture.artifact import emptyModifier, modifierElementCount, isTheEmptyModifier
                from fixture.artifact import describeModifier

                _empty = emptyModifier()
                assert isTheEmptyModifier(_empty), 'the seed is not Modifier itself'
                assert modifierElementCount(_empty) == 0

                _clicks = []
                _tappable = clickable(_empty, onClick=lambda: _clicks.append('tap'))

                assert not isTheEmptyModifier(_tappable._pm_handle), (
                    'clickable returned the receiver unchanged'
                )
                assert modifierElementCount(_tappable._pm_handle) >= 1, (
                    'clickable produced no element: ' + describeModifier(_tappable._pm_handle)
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * The other half of the slot: something that is **not** callable is refused with the signature,
     * rather than falling through to "expected a handle".
     *
     * Kotlin never hands a `FunctionN` back out for Python to pass in again, so a handle is not the
     * answer for this slot and saying so would send the caller looking for one.
     */
    @Test
    fun aNonCallableInTheOnClickSlotIsRefusedWithTheSignature() {
        PythonCallables.withScope(PythonCallables.newScope()) {
            Python3.exec(
                """
                from androidx.compose.foundation import clickable__Boolean_String_Role_MutableInteractionSource_Unit as clickable
                from fixture.artifact import emptyModifier

                try:
                    clickable(emptyModifier(), onClick='not a lambda')
                    raise AssertionError('a string filled a () -> Unit slot')
                except AssertionError:
                    raise
                except Exception as e:
                    assert 'callable' in str(e), 'unexpected refusal: ' + repr(e)
                """.trimIndent(),
            )
        }
    }

    /**
     * The lifetime contract, counted rather than assumed, and with the double release pinned apart
     * from the leak.
     *
     * The wrapper is a `HandleTable` root for as long as its scope is open -- that is the whole
     * reason the scope exists -- so `liveCount` must rise by exactly one while the callable is live
     * and come back to where it started when the scope closes. `measureTimeMillis` is used rather
     * than `clickable` because its result is an `INT` and roots nothing of its own, so the one handle
     * counted here is unambiguously the callable's.
     *
     * The second `close` is asserted **with another scope's root outstanding**: a double release that
     * freed a slot would free *that* one, and a test that closed twice over an empty table could not
     * tell the difference. `agent-rules` §14, one level up from the reference counts.
     */
    @Test
    fun theScopeHoldsOneRootPerCallableAndASecondCloseFreesNothing() {
        val baseline = settledBaseline()

        val first = PythonCallables.newScope()
        PythonCallables.withScope(first) {
            Python3.exec(
                """
                from kotlin.system import measureTimeMillis
                measureTimeMillis(lambda: None)
                """.trimIndent(),
            )
            assertEquals(
                baseline + 1, HandleTable.liveCount,
                "the wrapper must be rooted while its scope is open -- Compose calls a stored " +
                    "callback long after the statement that passed it returned",
            )
        }
        assertEquals(1, first.liveCount, "the scope is what holds the Python reference")

        assertEquals(1, first.close(), "close reports what it released")
        assertEquals(baseline, HandleTable.liveCount, "and the root came back")

        // A second scope takes the slot the release above just freed, so the double release below
        // has something to be wrong about.
        val second = PythonCallables.newScope()
        PythonCallables.withScope(second) {
            Python3.exec("measureTimeMillis(lambda: None)")
        }
        assertEquals(baseline + 1, HandleTable.liveCount)

        assertEquals(0, first.close(), "a second close must release nothing")
        assertEquals(
            baseline + 1, HandleTable.liveCount,
            "the second close of an already-closed scope must not free the new owner's root",
        )

        assertEquals(1, second.close())
        assertEquals(baseline, HandleTable.liveCount)
    }

    /**
     * A callable cannot cross with no scope open, and the reference it was given is not leaked when
     * it is refused.
     *
     * The refusal is the honest answer rather than an inconvenience: a wrapper with no holder would
     * be invoked by Compose after the `PyObject` behind it had been collected, which on a target with
     * no cleaner is never and on one with a cleaner is whenever.
     */
    @Test
    fun aCallableWithNoScopeOpenIsRefusedRatherThanRooted() {
        val baseline = settledBaseline()
        assertEquals(0, PythonCallables.openScopeCount, "no scope may be left open by an earlier test")

        Python3.exec(
            """
            from kotlin.system import measureTimeMillis
            try:
                measureTimeMillis(lambda: None)
                raise AssertionError('a callable crossed with nowhere to live')
            except AssertionError:
                raise
            except Exception as e:
                assert 'scope' in str(e), 'unexpected refusal: ' + repr(e)
            """.trimIndent(),
        )
        assertTrue(
            HandleTable.liveCount <= baseline,
            "a refused crossing must root nothing: ${HandleTable.liveCount} against $baseline",
        )
    }

    /** [HandleTable.liveCount] with anything an earlier test left as garbage already collected. */
    private fun settledBaseline(): Int {
        Python3.exec("import gc\ngc.collect()")
        return HandleTable.liveCount
    }
}
