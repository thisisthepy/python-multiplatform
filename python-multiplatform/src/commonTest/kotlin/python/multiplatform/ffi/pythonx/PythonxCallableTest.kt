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
 * The direction the composable work left open: **a Python callable reaching a Kotlin function-typed
 * parameter.**
 *
 * `docs/archive/pythonx-adapter-design.md` §6 states the starting position exactly -- "a Python callable can
 * already cross into Kotlin", as a `PyObject`, because `UpcallTrampoline.toKotlinObject` wraps
 * anything that is not an integer handle. What it *cannot* do is arrive as a `Function0` or a
 * `Function3`, which is what every container composable's `content` slot is after the Compose plugin
 * has lowered it. So `Column`, `Row`, `Box` and `Button` were reachable only with `content` left
 * defaulted, which for `Column` is not reachable at all -- `content` declares no default.
 *
 * ### The three questions, and which one this file answers
 *
 * | | where |
 * |---|---|
 * | does a Python callable become a Kotlin `FunctionN`, with the composer threaded through | here, on all five targets, against [ComposableShapedFragment] |
 * | does a real `androidx.compose.foundation.layout.Column` then draw its content | `:python-multiplatform-compose:fixtures:compose`'s `ComposableRenderTest`, desktop only |
 * | does Compose give the callable back when it drops the slot | `ComposableRenderTest.aDisposedCompositionGivesEveryPythonCallableBack`, because `RememberObserver` is a Compose type |
 *
 * The split is [ComposableShapedFragment]'s: `commonTest` runs where there is no Compose runtime, no
 * `Composer` and no generated thunk class, so what is checked here is the mechanism -- which object
 * arrives, what it does when invoked, and who holds the Python reference -- and not the pixels.
 *
 * ### Lifetime is the risk, and it is asserted from both sides
 *
 * A test that only counted leaks would pass a double release, which is the failure this repository
 * has actually been bitten by (`agent-rules` §14: two wrappers decrementing one pointer corrupted
 * the heap and surfaced in an unrelated test). So [theScopeHoldsThePythonCallableAndGivesItBackOnce]
 * asserts the reference count **goes up** while the scope is open, **comes back** when it closes,
 * and **does not go below the baseline** when close is called again.
 */
class PythonxCallableTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ComposableShapedFragment))
        ComposableShapedFragment.calls.clear()
        ComposableShapedFragment.clicks.clear()
        ComposableShapedFragment.valueChanges.clear()
        ComposableShapedFragment.contents.clear()
        ComposableShapedFragment.contentInvocations = 0
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    /**
     * The claim, in one line of Python: `Column(content=lambda: Text('hi'))`.
     *
     * Three things have to be true for this to pass and they fail in distinguishable ways. The
     * object in `Column`'s slot 1 has to be a `Function3` -- [ComposableShapedFragment] casts it
     * unchecked, so a `PyObject` there is a `ClassCastException` naming the type it got. Invoking it
     * has to run the Python body -- `contentInvocations` counts the invocation and `calls` counts
     * what the body did, so a content that was received but never called is distinguishable from one
     * that was called and did nothing. And the body has to reach `Text` through the ordinary
     * composable path, mask and all.
     */
    @Test
    fun aPythonLambdaCrossesAsAKotlinFunctionAndRunsInsideItsContainer() = withComposer {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text
            Column(content=lambda: Text('hi'))
            """.trimIndent(),
        )

        assertEquals(1, ComposableShapedFragment.contentInvocations, "the container never called its content")
        assertEquals(2, ComposableShapedFragment.calls.size, "expected the Column and the Text it contains")
        val (column, text) = ComposableShapedFragment.calls
        // `Column(content=...)` writes one of two declared slots, so bit 0 (`modifier`) is set and
        // bit 1 (`content`) is not: the mask arithmetic is unchanged by the callable arriving.
        assertEquals(1L, column[4], "\$default: modifier omitted, content supplied")
        assertEquals("hi", text[0], "the Python body did not run inside the content lambda")
    }

    /**
     * **The composer the content sees is the one its container handed it**, not the one that was
     * ambient when the lambda was created.
     *
     * A wrapper that ignored its own `$composer` argument and let `pythonx.current_composer()` keep
     * answering with the outer one would pass every test that only used a single composer -- and
     * would be wrong in exactly the case Compose creates, where a container establishes a new
     * position before invoking its content. [ComposableShapedFragment] passes a distinct
     * `InnerComposer` for that reason.
     */
    @Test
    fun theContentSeesTheComposerItsContainerHandedIt() = withComposer {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text
            Column(content=lambda: Text('hi'))
            """.trimIndent(),
        )

        val text = ComposableShapedFragment.calls[1]
        assertEquals(
            ComposableShapedFragment.InnerComposer,
            text[4],
            "the content ran against the ambient composer rather than the one it was passed",
        )
    }

    /**
     * Who holds the Python reference, and that they give it back exactly once.
     *
     * `sys.getrefcount` is the measurement: the callable is held in a Python global, so the count is
     * stable and the only thing that can move it is the Kotlin side taking and dropping a reference
     * of its own. The scope is the holder -- not `Column`, which has no way to be told the slot was
     * dropped, and not the composition pass, which ends long before Compose stops calling the
     * content.
     */
    @Test
    fun theScopeHoldsThePythonCallableAndGivesItBackOnce() = withAdapter {
        val scope = PythonCallables.newScope()
        Python3.exec(
            """
            import sys
            import python_multiplatform.binding as _pm_binding
            from androidx.compose.material3 import Text

            def _px_body():
                Text('hi')

            _px_base = sys.getrefcount(_px_body)
            """.trimIndent(),
        )
        val base = eval("_px_base").toInt()

        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    from androidx.compose.foundation.layout import Column
                    Column(content=_px_body)
                    _px_held = sys.getrefcount(_px_body)
                    """.trimIndent(),
                )
            } finally {
                popComposer()
            }
        }
        val held = eval("_px_held").toInt()
        assertTrue(held > base, "nothing on the Kotlin side took a reference: $base -> $held")
        assertEquals(1, scope.liveCount, "the scope did not record the callable it created")

        assertEquals(1, scope.close(), "closing the scope released nothing")
        Python3.exec("_px_released = sys.getrefcount(_px_body)")
        assertEquals(base, eval("_px_released").toInt(), "the scope did not give the reference back")

        // The half a leak test does not cover. A second close must be a no-op, not a second decref:
        // one reference over-released here is a use-after-free somewhere unrelated later.
        assertEquals(0, scope.close(), "closing twice released something twice")
        Python3.exec("_px_twice = sys.getrefcount(_px_body)")
        assertEquals(base, eval("_px_twice").toInt(), "a second close dropped the reference again")
    }

    /**
     * A callable with nowhere to live is refused at the boundary, with a message that says so.
     *
     * The alternative -- rooting it in `HandleTable` and hoping something releases it -- is what
     * `HandleTable`'s own KDoc calls a leak by construction, and there is nothing on the Python side
     * to hang a finaliser off because the handle reaches Kotlin as a bare slot value and is never
     * given back to Python at all.
     */
    @Test
    fun aCallableWithNoScopeIsRefusedRatherThanLeaked() = withAdapter {
        // Deliberately no `PythonCallables.withScope`: a composer is in scope, so everything else
        // about the call is well formed and the scope is the only thing missing.
        pushComposer()
        val refusal = try {
            assertFails {
                Python3.exec(
                    """
                    from androidx.compose.foundation.layout import Column
                    from androidx.compose.material3 import Text
                    Column(content=lambda: Text('hi'))
                    """.trimIndent(),
                )
            }
        } finally {
            popComposer()
        }
        assertTrue(
            refusal.message?.contains("no pythonx callable scope") == true,
            "expected the refusal to name the missing scope, got: ${refusal.message}",
        )
        assertEquals(0, ComposableShapedFragment.contentInvocations)
    }

    /**
     * A `FunctionN` invoked after its scope closed **refuses**; it does not call through a released
     * `PyObject`.
     *
     * This is the shape the plain (non-lowered) case makes reachable: `Button`'s `onClick` is kept by
     * Kotlin and fired later, by which time the composition that created it may be gone. Reading a
     * freed `PyObject` would not raise -- it would decrement whatever now occupies that memory, which
     * is the failure mode `agent-rules` §14 describes and the reason this is a check and not a
     * comment.
     */
    @Test
    fun aFunctionInvokedAfterItsScopeClosedRefuses() = withAdapter {
        val scope = PythonCallables.newScope()
        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    import python_multiplatform.binding as _pm_binding
                    from androidx.compose.material3 import Button
                    _px_clicked = []
                    Button(onClick=lambda: _px_clicked.append(1))
                    """.trimIndent(),
                )
            } finally {
                popComposer()
            }
        }
        val onClick = ComposableShapedFragment.clicks.single()
        onClick()
        assertEquals("1", eval("len(_px_clicked)"), "the click did not reach Python while the scope was open")

        scope.close()
        val refusal = assertFails { onClick() }
        assertTrue(
            refusal.message?.contains("released") == true,
            "expected a refusal naming the released scope, got: ${refusal.message}",
        )
        assertEquals("1", eval("len(_px_clicked)"), "a released callable still ran")
    }

    /**
     * What a crossing **allocates**, which is the part of its cost this can state without a clock.
     *
     * This used to assert the opposite, and the opposite was true: one wrapper per crossing, nothing
     * interned, so *n* crossings cost *n* Python references and *n* `HandleTable` roots held until
     * the scope closed. `:python-multiplatform-compose:fixtures:compose`'s `RecompositionAccumulationTest` measured what that
     * came to for a real composition -- **twelve** wrappers for one `content=` composed twelve times,
     * and twenty-four for a `Button` -- and `PythonCallableScope` now keys them. The number here is 1.
     *
     * Three assertions, because there are three different ways to arrive at 1 and only one of them
     * is the intended one:
     *
     * | assertion | what it rules out |
     * |---|---|
     * | [ComposableShapedFragment.contentInvocations] is [REPETITIONS] | the loop stopped calling `Column` |
     * | `scope.reuseCount` is `REPETITIONS - 1` | the crossings stopped happening rather than being answered |
     * | `scope.liveCount` is 1 | the table is consulted and never hits |
     *
     * The `lambda:` is written **inside a loop**, so it is a different function object on every
     * iteration -- which is precisely why the key cannot be `id()`. All [REPETITIONS] of them share
     * one code object, because this body is compiled once.
     *
     * **No wall-clock figure is claimed.** `agent-rules` §11 forbids fixing one on a loaded machine,
     * and the run that produced this had a load average of 3.5--8 on eight cores. Nanoseconds per
     * crossing are unmeasured.
     */
    @Test
    fun crossingsOfOneCallableShareOneWrapperAndTheScopeHoldsIt() = withAdapter {
        val scope = PythonCallables.newScope()
        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    import python_multiplatform.binding as _pm_binding
                    from androidx.compose.foundation.layout import Column
                    from androidx.compose.material3 import Text
                    for _ in range(REPS):
                        Column(content=lambda: Text('hi'))
                    """.trimIndent().replace("REPS", REPETITIONS.toString()),
                )
            } finally {
                popComposer()
            }
            assertEquals(REPETITIONS, ComposableShapedFragment.contentInvocations)
            assertEquals(
                REPETITIONS - 1, scope.reuseCount,
                "expected every crossing after the first to be answered from the scope's table",
            )
            assertEquals(1, scope.liveCount, "one wrapper for $REPETITIONS crossings of one callable")
        }
        assertEquals(1, scope.close(), "the scope released something it did not build")
    }

    /**
     * The other side of the key: **two callables that differ only in what they captured are two
     * wrappers, and each is invoked as itself.**
     *
     * Without this, the assertion above is satisfiable by a cache that keys on nothing and hands the
     * first wrapper to every caller -- which is not a leak but a *wrong frame*, and a wrong frame is
     * invisible to every reference count in this file. So each crossing's content appends **its own**
     * captured number and what is asserted is the list Python ends up with.
     */
    @Test
    fun twoCallablesThatCapturedDifferentValuesDoNotShareAWrapper() = withAdapter {
        val scope = PythonCallables.newScope()
        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    from androidx.compose.foundation.layout import Column
                    _px_captured = []
                    def _px_make(n):
                        return lambda: _px_captured.append(n)
                    for _px_i in range(REPS):
                        Column(content=_px_make(_px_i % 3))
                    """.trimIndent().replace("REPS", REPETITIONS.toString()),
                )
            } finally {
                popComposer()
            }
            assertEquals(3, scope.liveCount, "expected one wrapper per distinct capture, not per crossing")
            assertEquals(
                REPETITIONS - 3, scope.reuseCount,
                "the crossings after the first three were not answered from the table",
            )
        }
        assertEquals(
            (0 until REPETITIONS).joinToString(", ", "[", "]") { (it % 3).toString() },
            eval("_px_captured"),
            "a reused wrapper invoked another capture's callable",
        )
        assertEquals(3, scope.close())
    }

    /**
     * **①: a value reaches the Python callable.** `Slider(onValueChange=lambda v: ...)`.
     *
     * The claim is deliberately not "the callback fired" -- a wrapper that dropped its argument and
     * called `fn()` would fire too, and a slider that never moves is exactly what that renders as.
     * So the fixture's `Slider` invokes its callback with a value no default produces, and the
     * assertion is on **what Python was told**, recorded in a Python list rather than marshalled back
     * out so that the check costs no crossing of its own that could be the thing that worked.
     *
     * A `float` and not an `int`: `TypeTag.FLOAT` carries a `Double` and the slot declares a Kotlin
     * `Float`, so a rule that widened through the integral tag would arrive as `0` here.
     */
    @Test
    fun aValueCallbackIsToldWhatKotlinPassedIt() = withComposer {
        Python3.exec(
            """
            from androidx.compose.material3 import Slider
            _px_seen = []
            Slider(on_value_change=lambda v: _px_seen.append(v))
            """.trimIndent(),
        )

        assertEquals("1", eval("len(_px_seen)"), "the callback never ran")
        assertEquals("0.25", eval("round(_px_seen[0], 6)"), "the callback was not told the value Kotlin passed")
        assertEquals("<class 'float'>", eval("type(_px_seen[0])"), "a Float slot must not arrive as an int")
    }

    /**
     * **②: the scope receiver reaches the callable, as its own Kotlin type.**
     *
     * `Column`'s `content` is `@Composable ColumnScope.() -> Unit`, so Compose invokes it with the
     * scope, a composer and a `$changed`. The scope was dropped before this; forwarding it is the
     * *same* marshalling problem as the `Float` above and not a different one -- the only thing that
     * differs is which side of `TypeTag.OBJECT` the declared type falls on.
     *
     * Two assertions, because arriving is not enough. The value has to be a proxy over the scope
     * Kotlin actually passed -- `_pm_handle` resolves to it -- and its **type name** has to be
     * `ColumnScope`, because that name is the key `_BY_RECEIVER` hangs the scope's extensions off. A
     * proxy of the wrong type would carry the right object and still have no `weight` on it.
     */
    @Test
    fun aScopedContentIsHandedItsReceiverAsAProxyOfTheDeclaredType() = withComposer {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text
            _px_scope = []

            def _px_content(scope):
                _px_scope.append(scope)
                Text('hi')

            Column(content=_px_content)
            """.trimIndent(),
        )

        assertEquals(1, ComposableShapedFragment.contentInvocations, "the container never called its content")
        assertEquals("1", eval("len(_px_scope)"), "the content ran without being handed its scope")
        assertEquals(
            "ColumnScope",
            eval("type(_px_scope[0]).__name__"),
            "the receiver arrived under some other type, so ColumnScope's extensions cannot attach",
        )
        val handle = eval("_px_scope[0]._pm_handle").toLong()
        assertEquals(
            ComposableShapedFragment.StubScope,
            HandleTable.resolveRaw(handle),
            "the proxy is over some other object than the scope the container passed",
        )
    }

    /**
     * A callable that declares no parameter for the receiver still works, and that is a decision
     * rather than an accident.
     *
     * Kotlin writes `Column { Text("hi") }` far more often than `Column { scope -> ... }`, and a
     * Python author writing `lambda: Text('hi')` is saying the same thing -- it was also the *only*
     * spelling that existed before this slot forwarded anything, so every test above and the whole of
     * `ComposableRenderTest` is written that way. Dropping what the callable did not ask for is
     * therefore both the compatible answer and the idiomatic one.
     *
     * The other direction is **not** symmetric: a callable that wants more than the slot supplies is
     * refused where it crosses, because there is no value to give it and the alternative is a
     * `TypeError` raised inside Compose on some later recomposition.
     */
    @Test
    fun aCallableThatDeclaresNoReceiverStillRunsAndOneThatWantsTooManyIsRefused() = withComposer {
        Python3.exec(
            """
            from androidx.compose.foundation.layout import Column
            from androidx.compose.material3 import Text
            Column(content=lambda: Text('hi'))
            """.trimIndent(),
        )
        assertEquals(1, ComposableShapedFragment.contentInvocations, "a zero-argument content did not run")
        assertEquals(2, ComposableShapedFragment.calls.size, "expected the Column and the Text it contains")

        val refusal = assertFails {
            Python3.exec(
                """
                from androidx.compose.foundation.layout import Column
                Column(content=lambda a, b, c: None)
                """.trimIndent(),
            )
        }
        assertTrue(
            refusal.message?.contains("does not accept") == true,
            "expected a refusal naming the arity mismatch, got: ${refusal.message}",
        )
    }

    /**
     * A forwarded object is rooted by Kotlin and released by **Python**, exactly once, including the
     * one the callable never asked for.
     *
     * This is the lifetime question ① newly creates: the argument crosses too, so somebody owns it.
     * `HandleTable`'s contract says a handle that reaches Python is given back by the proxy's
     * `__del__`, and that is what happens here -- Kotlin does not release it, because a callback is
     * allowed to keep what it was handed.
     *
     * The **dropped** case is the one that fails silently and is asserted separately: a thunk that
     * truncated the argument list before wrapping would never build an owner for the receiver, and
     * the handle would leak once per invocation. Two hundred invocations of a content that ignores
     * its scope therefore has to end with the table exactly where it started, and a wrapper that
     * released it *as well* would show as a `liveCount` that went below the baseline.
     */
    @Test
    fun aForwardedObjectIsReleasedOnceByThePythonProxyEvenWhenTheCallableDropsIt() = withAdapter {
        val scope = PythonCallables.newScope()
        val baseline = HandleTable.liveCount
        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    from androidx.compose.foundation.layout import Column
                    _px_kept = []
                    for _ in range(REPS):
                        Column(content=lambda: None)
                    Column(content=lambda s: _px_kept.append(s))
                    """.trimIndent().replace("REPS", REPETITIONS.toString()),
                )
            } finally {
                popComposer()
            }
        }
        // The wrappers themselves are roots too -- two of them, because the loop's `lambda: None` is
        // one callable interned across all $REPETITIONS crossings and the keeping content is another
        // (`crossingsOfOneCallableShareOneWrapperAndTheScopeHoldsIt`). Closing first leaves only what
        // this test is about: what the *invocations* rooted, of which there are still $REPETITIONS
        // + 1, because interning shares a wrapper and does not skip a call.
        assertEquals(2, scope.close(), "expected one wrapper per distinct callable, not per crossing")

        // One live handle above the baseline: the receiver the last content kept. Everything the
        // dropping contents were handed has been given back, which a thunk that truncated before
        // wrapping could not do -- it would leave $REPETITIONS of them rooted with no Python object
        // anywhere that could release one.
        assertEquals(
            baseline + 1,
            HandleTable.liveCount,
            "expected exactly the kept receiver to still be rooted after $REPETITIONS dropped ones",
        )
        assertEquals(
            ComposableShapedFragment.StubScope,
            HandleTable.resolveRaw(eval("_px_kept[0]._pm_handle").toLong()),
            "the kept proxy no longer resolves, so something released it early",
        )

        Python3.exec("del _px_kept")
        assertEquals(baseline, HandleTable.liveCount, "the kept receiver was never given back")
    }

    /**
     * **What one *invocation* costs**, as distinct from what one crossing costs, which is the part
     * of it a clock is not needed for.
     *
     * `crossingsOfOneCallableShareOneWrapperAndTheScopeHoldsIt` measures the crossing: one wrapper
     * per distinct callable, held until the scope closes. This measures the other axis, which
     * forwarding an argument newly created -- **one wrapper, invoked many times**, the shape a
     * recomposing composition really has, and the axis interning does *not* touch.
     *
     * The claim is that a forwarded object's root does not accumulate. `HandleTable`'s array is
     * bounded by peak live entries rather than by total issued, so a root registered and released
     * inside one invocation returns its slot; if the release were missing, [HandleTable.slotCount]
     * would grow once per invocation and this would say so at $REPETITIONS.
     *
     * What is **not** free is stated rather than asserted: each invocation pays one
     * `HandleTable.register`/`release` pair, one `PyInt` for the handle, and one Python proxy object
     * whose `__del__` runs at the end of the call. Nothing is interned, so a `content` invoked on
     * every recomposition pays all three every time. No wall-clock figure is claimed
     * (`agent-rules` §11).
     */
    @Test
    fun invokingOneWrapperManyTimesDoesNotAccumulateRootsForItsArguments() = withAdapter {
        val scope = PythonCallables.newScope()
        val baseline = HandleTable.liveCount
        try {
            PythonCallables.withScope(scope) {
                pushComposer()
                try {
                    Python3.exec(
                        """
                        from androidx.compose.foundation.layout import Column
                        _px_last = []
                        Column(content=lambda s: _px_last.append(type(s).__name__))
                        """.trimIndent(),
                    )
                } finally {
                    popComposer()
                }
            }
            val content = ComposableShapedFragment.contents.single()
            val slotsAfterCrossing = HandleTable.slotCount
            repeat(REPETITIONS) {
                content(ComposableShapedFragment.StubScope, ComposableShapedFragment.InnerComposer, 0L)
            }

            println(
                "forwarded-argument churn: ${REPETITIONS + 1} invocations of one wrapper, " +
                    "HandleTable slots $slotsAfterCrossing -> ${HandleTable.slotCount}, " +
                    "live ${HandleTable.liveCount} against a baseline of $baseline",
            )
            assertEquals("${REPETITIONS + 1}", eval("len(_px_last)"), "not every invocation reached Python")
            assertEquals("ColumnScope", eval("_px_last[-1]"), "a later invocation stopped forwarding its receiver")
            assertEquals(
                slotsAfterCrossing,
                HandleTable.slotCount,
                "a forwarded argument's root must be released within its invocation, not accumulated",
            )
        } finally {
            scope.close()
        }
        assertEquals(baseline, HandleTable.liveCount, "closing the scope did not return the table to where it was")
    }

    /**
     * A lambda that has to **give something back** is refused, and refused at the call.
     *
     * `kotlin.Function1` is the compiled spelling of both `(Float) -> Unit` and `(Float) -> Boolean`,
     * so without the return type in the slot's name the two are indistinguishable -- and the second
     * cannot be bound, because `TypeTag` carries one `INT` for `Byte` through `Long` and one `FLOAT`
     * for both floating widths, so nothing on either side can say which boxed type Compose will cast
     * the answer to. 18 of the 560 function-typed slots three Compose jars declare are this shape.
     *
     * The alternative is not a wrong picture; it is a `ClassCastException` raised inside Compose,
     * from a frame that names neither the slot nor the Python callable.
     */
    @Test
    fun aLambdaThatHasToReturnAValueIsRefusedRatherThanCoerced() = withComposer {
        val refusal = assertFails {
            Python3.exec(
                """
                from androidx.compose.material3 import remember_sheet_state
                remember_sheet_state(confirm_value_change=lambda v: True)
                """.trimIndent(),
            )
        }
        assertTrue(
            refusal.message?.contains("returning Boolean") == true,
            "expected the refusal to name the return type, got: ${refusal.message}",
        )
        assertEquals(0, ComposableShapedFragment.calls.size, "the refused call still reached Kotlin")
    }

    /**
     * The plain-with-arguments case has the same lifetime rule as the plain zero-argument one: a
     * callback fired after its scope closed **refuses** rather than calling through a released
     * `PyObject`.
     *
     * Stated separately from [aFunctionInvokedAfterItsScopeClosedRefuses] because the argument path
     * is new code between the check and the call -- a wrapper that marshalled first and checked after
     * would have already registered a `HandleTable` root and built Python objects against a freed
     * callable by the time it refused.
     */
    @Test
    fun aValueCallbackInvokedAfterItsScopeClosedRefuses() = withAdapter {
        val scope = PythonCallables.newScope()
        PythonCallables.withScope(scope) {
            pushComposer()
            try {
                Python3.exec(
                    """
                    from androidx.compose.material3 import Slider
                    _px_moves = []
                    Slider(onValueChange=lambda v: _px_moves.append(v))
                    """.trimIndent(),
                )
            } finally {
                popComposer()
            }
        }
        assertEquals("1", eval("len(_px_moves)"), "the fixture never fired the callback")
        val onValueChange = ComposableShapedFragment.valueChanges.single()

        scope.close()
        val refusal = assertFails { onValueChange(1.0f) }
        assertTrue(
            refusal.message?.contains("released") == true,
            "expected a refusal naming the released scope, got: ${refusal.message}",
        )
        assertEquals("1", eval("len(_px_moves)"), "a released callback still ran")
    }

    private fun pushComposer() = Python3.exec(
        """
        import python_multiplatform.binding as _pm_binding
        from androidx.compose.runtime import stubComposer
        _px_composer = stubComposer()
        _pm_binding.push_composer(_px_composer)
        """.trimIndent(),
    )

    private fun popComposer() = Python3.exec("import python_multiplatform.binding as _pm_binding\n_pm_binding.pop_composer()\ndel _px_composer")

    /** [withAdapter], a composer, and a scope -- the three things a real composition supplies. */
    private inline fun withComposer(crossinline block: () -> Unit) = withAdapter {
        val scope = PythonCallables.newScope()
        try {
            PythonCallables.withScope(scope) {
                pushComposer()
                try {
                    block()
                } finally {
                    popComposer()
                }
            }
        } finally {
            scope.close()
        }
    }

    private inline fun withAdapter(block: () -> Unit) = PythonTestFixture.withInterpreter {
        assertTrue(
            bindUpcallOrNull("androidx.compose.runtime.stubComposer"),
            "the fixture table is not installed",
        )
        if (!publishesProxyEntryPoints) return@withInterpreter
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()

    private companion object {
        const val REPETITIONS = 200
    }
}
