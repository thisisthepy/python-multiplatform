package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.publishesProxyEntryPoints
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * `docs/archive/pythonx-adapter-design.md` §5 from the Python end: **the `$default` mask is arithmetic
 * `pythonx` does, not a branch the generator emits.**
 *
 * ### Why the mask has to live here and not in the walker
 *
 * §4.5 measured the alternative and it does not fit: presence branching costs one generated call
 * expression per subset of the defaulted parameters, and `androidx.compose.material3.Text` defaults
 * fifteen. `ArtifactScanner.MAX_OMITTABLE_PARAMETERS` caps that at six for exactly this reason, so a
 * composable would never have been offered a single omission.
 *
 * What makes composables different is that `$default` is a **declared trailing parameter** of the
 * JVM method (§5.2), so it does not have to be a compile-time constant. Python knows which arguments
 * the caller wrote; that is one integer, computed once per call, for a declaration of any width.
 *
 * ### The encoding, and where it was read from
 *
 * Bit *i* means parameter *i* was omitted. Taken out of the callee's own prologue rather than
 * assumed -- `javap -c androidx/compose/material3/TextKt` shows `$default & 2` guarding
 * `modifier = Modifier.Companion`, `& 4` guarding `color = Color.Unspecified`, `& 8` the next one,
 * with a bit reserved for every parameter including the ones that default nothing. The same reading
 * is what [theMaskBitIsTheParameterIndex] pins from Python.
 *
 * ### What this test is not
 *
 * It is not a proof that Compose draws anything. The fragment behind it is Kotlin this repository
 * owns ([ComposableShapedFragment]), because `commonTest` runs on Kotlin/Native where there is no
 * Compose runtime and no generated thunk class. `:ksp-fixtures:compose`'s `ComposableRenderTest` is
 * the other half, and it renders a real `Text` through a real `Composer`.
 */
class PythonxComposableTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ComposableShapedFragment))
        ComposableShapedFragment.calls.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    /**
     * `Text('hi')` -- one argument written out of four -- and every slot the boundary then received.
     *
     * The assertion is on the *arguments Kotlin got*, not on a return value, because a composable
     * returns nothing and because the whole question is what went into the slots the caller did not
     * write.
     */
    @Test
    fun aComposableIsCalledWithOnlyItsRequiredArgumentAndAComputedMask() = withComposer {
        Python3.exec(
            """
            from androidx.compose.material3 import Text
            Text('hi')
            """.trimIndent(),
        )

        val call = ComposableShapedFragment.calls.single()
        assertEquals("hi", call[0])
        // Every slot the mask claims is overwritten by the callee still has to survive the boundary
        // and the thunk's unboxing: `null` for a reference, a zero of the right shape for a
        // primitive. A `None` in slot 2 would reach `Number.intValue()` and raise.
        assertEquals(null, call[1], "an omitted reference slot is null")
        assertEquals(0L, call[2], "an omitted INT slot is 0, not null")
        assertEquals(0L, call[3], "an omitted INT slot is 0, not null")
        assertEquals(ComposableShapedFragment.StubComposer, call[4], "the composer round-tripped as a handle")
        assertEquals(0L, call[5], "\$changed is 0: this caller claims to know nothing about staticness")
        // modifier, color and fontSize omitted -> bits 1, 2 and 3 -> 0b1110.
        assertEquals(14L, call[6], "\$default")
    }

    /**
     * The bit index **is** the parameter index, one written argument at a time.
     *
     * Four calls, each writing exactly one of the three defaulted parameters, so each expected mask
     * differs from its neighbours in one bit. A test that only checked the all-omitted mask would
     * pass just as well if the bits were reversed or shifted by one.
     */
    @Test
    fun theMaskBitIsTheParameterIndex() = withComposer {
        Python3.exec(
            """
            from androidx.compose.material3 import Text
            Text('hi')
            Text('hi', modifier=None)
            Text('hi', color=3)
            Text('hi', fontSize=4)
            Text('hi', color=3, fontSize=4)
            """.trimIndent(),
        )

        val masks = ComposableShapedFragment.calls.map { it[6] }
        assertEquals(
            listOf(
                0b1110L, // nothing written: modifier, color, fontSize all defaulted
                0b1110L, // an explicit `None` is an omission, the same as not writing it
                0b1010L, // color written -> bit 2 clear
                0b0110L, // fontSize written -> bit 3 clear
                0b0010L, // only modifier defaulted
            ),
            masks,
        )
        // And the `color` slot itself, which is the reason the mask has to exist: an omitted INT
        // slot carries `0`, which is indistinguishable from a written `0`. Only the bit says which.
        assertEquals(listOf(0L, 0L, 3L, 0L, 3L), ComposableShapedFragment.calls.map { it[2] })
    }

    /**
     * **An extension composable's mask counts value parameters, not slots**, on every target.
     *
     * `RowScope.NavigationBarItem(selected, onClick, modifier = …, enabled = …)` reached as a method
     * on the scope's proxy, which is the only spelling there is -- a receiver is positional in Kotlin
     * and `<receiver>` is deliberately not a Python identifier, so nothing can name it.
     *
     * The receiver occupies slot 0 of the binding and **no** `$default` bit: bit 0 is `selected`, so
     * `modifier` is bit 2 and `enabled` is bit 3. Under the arithmetic this replaced -- bit index =
     * slot index -- they would be bits 3 and 4, and the two masks differ in every bit, which is what
     * makes this test not vacuous. The numbering is read out of the real `NavigationBarKt`'s
     * bytecode by `ComposableBindingTest`; this is where it is checked on the four targets that
     * cannot open a jar, and `ComposableRenderTest` is where a wrong bit is shown to raise inside
     * Compose rather than to draw something slightly wrong.
     */
    @Test
    fun anExtensionComposableNumbersItsMaskOverValueParametersOnly() = withComposer {
        // `onClick` declares no default, so the call cannot avoid handing Kotlin a callable -- which
        // needs somewhere to live. The scope is the composition's; here it is this block's.
        val scope = PythonCallables.newScope()
        try {
            PythonCallables.withScope(scope) {
                Python3.exec(
                    """
                    from androidx.compose.foundation.layout import stubRowScope
                    _row = stubRowScope()
                    _row.NavigationBarItem(selected=True, onClick=lambda: None)
                    _row.NavigationBarItem(selected=True, onClick=lambda: None, enabled=False)
                    """.trimIndent(),
                )
            }
        } finally {
            scope.close()
        }

        val calls = ComposableShapedFragment.calls
        assertEquals(2, calls.size, "the extension composable was not reachable as a method on its scope")
        assertEquals(ComposableShapedFragment.StubRowScope, calls[0][0], "slot 0 is the receiver")
        assertEquals(true, calls[0][1], "slot 1 is `selected`")
        assertEquals(
            listOf(0b1100L, 0b0100L),
            calls.map { it[7] },
            "\$default bit i must be value parameter i: modifier is bit 2 and enabled bit 3, " +
                "not bits 3 and 4",
        )
        // The omitted slots still have to survive the boundary as the callee's prologue expects.
        assertEquals(null, calls[0][3], "an omitted reference slot is null")
        assertEquals(false, calls[0][4], "an omitted BOOLEAN slot is False, not None")
        assertEquals(false, calls[1][4], "`enabled=False` is a written value, not an omission")
    }

    /**
     * A required parameter is still required: the mask reaches defaults, and `text` has none.
     *
     * This is what stops the mask from becoming "every argument is optional". `paramHasDefault` is
     * the declaration's own answer for a composable -- unlike every other producer, where it is a
     * statement about a generated branch -- so slot 0 is `False` and refusing is the only option.
     */
    @Test
    fun aParameterWithNoDefaultCannotBeOmittedFromAComposable() = withComposer {
        Python3.exec(
            """
            from androidx.compose.material3 import Text
            _px = {}
            try:
                Text()
                _px['refusal'] = 'the call succeeded'
            except TypeError as e:
                _px['refusal'] = str(e)
            """.trimIndent(),
        )

        val refusal = eval("_px['refusal']")
        assertTrue("no value for text" in refusal, refusal)
        assertTrue(ComposableShapedFragment.calls.isEmpty(), "nothing should have reached Kotlin")
    }

    /**
     * Outside a composition there is no composer, and no arithmetic can invent one.
     *
     * This is the whole reason a hand-written `@Composable` entry point exists: `$composer` is not a
     * value, it is a *position*. The refusal names the entry point rather than reporting an arity
     * problem, because an arity message would send the caller looking for an argument to pass.
     */
    @Test
    fun callingAComposableOutsideACompositionRefusesAndSaysWhy() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.material3 import Text
            _px = {}
            try:
                Text('hi')
                _px['refusal'] = 'the call succeeded'
            except RuntimeError as e:
                _px['refusal'] = str(e)
            """.trimIndent(),
        )

        val refusal = eval("_px['refusal']")
        assertTrue("no composer is in scope" in refusal, refusal)
        assertTrue(ComposableShapedFragment.calls.isEmpty(), "nothing should have reached Kotlin")
    }

    /** The synthetic slots are not the caller's, and the refusal says so by name rather than by
     * silently accepting a keyword that would then be overwritten. */
    @Test
    fun theSyntheticSlotsAreNotAddressableFromPython() = withComposer {
        Python3.exec(
            """
            from androidx.compose.material3 import Text
            _px = {}
            for _name in ('composer', 'changed', 'default'):
                try:
                    Text('hi', **{_name: 1})
                    _px[_name] = 'accepted'
                except TypeError as e:
                    _px[_name] = str(e)
            """.trimIndent(),
        )

        listOf("composer", "changed", "default").forEach { name ->
            val refusal = eval("_px['$name']")
            assertTrue("has no parameter named $name" in refusal, "$name: $refusal")
        }
        assertTrue(ComposableShapedFragment.calls.isEmpty(), "nothing should have reached Kotlin")
    }

    /** The signature a refusal prints stops at the last declared parameter. A caller told to supply
     * `$composer` would have nothing to supply. */
    @Test
    fun aComposablePrintsOnlyItsDeclaredParametersInARefusal() = withComposer {
        Python3.exec(
            """
            import python_multiplatform.binding as _pm_binding
            _px = {'signature': _pm_binding._TABLE['androidx.compose.material3.Text'].signature()}
            """.trimIndent(),
        )

        assertEquals("Text(text: String, modifier: Modifier = ..., color: Int = ..., fontSize: Int = ...)", eval("_px['signature']"))
    }

    /** [withAdapter] plus a composer pushed around the block, which is what the hand-written Kotlin
     * entry point does around a real composition. */
    private inline fun withComposer(block: () -> Unit) = withAdapter {
        Python3.exec(
            """
            import python_multiplatform.binding as _pm_binding
            from androidx.compose.runtime import stubComposer
            # Held in a global on purpose. An OBJECT result reaches Python as a proxy that owns its
            # handle and releases it in `__del__`, so `push_composer(stubComposer())` alone would
            # push a handle and then drop the last reference to it -- the next call through the
            # boundary then fails with "stale or unknown Kotlin object handle". The real caller is
            # Kotlin, which holds the composer for the composition's lifetime by construction; a
            # Python caller has to say so.
            _px_composer = stubComposer()
            _pm_binding.push_composer(_px_composer)
            """.trimIndent(),
        )
        try {
            block()
        } finally {
            Python3.exec("import python_multiplatform.binding as _pm_binding\n_pm_binding.pop_composer()\ndel _px_composer")
        }
    }

    private inline fun withAdapter(block: () -> Unit) = PythonTestFixture.withInterpreter {
        // Binds `_pm_resolve`/`_pm_invoke` into `__main__`, which is per-platform bootstrap the
        // adapter needs and does not do -- the same first line `PythonxDefaultsTest.withAdapter` has.
        assertTrue(
            bindUpcallOrNull("androidx.compose.runtime.stubComposer"),
            "the fixture table is not installed",
        )
        if (!publishesProxyEntryPoints) {
            val refusal = assertFails { PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES) }
            assertTrue(
                refusal.message?.contains("raw upcall entry points are not bound") == true,
                "a target with no proxy bootstrap must fail the adapter's own guard: $refusal",
            )
            return@withInterpreter
        }
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
