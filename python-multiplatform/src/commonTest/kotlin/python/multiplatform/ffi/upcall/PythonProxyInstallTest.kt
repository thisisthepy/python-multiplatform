package python.multiplatform.ffi.upcall

import python.multiplatform.currentPlatform
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The generated Python proxy actually running -- `docs/design/upcall-async-design.md` §8.6's second gap.
 *
 * `PythonProxySourceTest` pins what [PythonProxySource.render] emits, as text, with no interpreter.
 * This is the other half: CPython executes that text and the proxies do what the text says.
 *
 * ### Why this is in `commonTest` and not in `desktopTest`
 *
 * It was in `desktopTest`, and that is why [PythonProxySource] and `UpcallEntry.publish` were free
 * to disagree about the bootstrap for as long as both have existed. Desktop's `ctypes` shim
 * happened to bind exactly the names and argument types the generator was written against, so on
 * the one target that ever called [PythonProxySource.install] everything lined up, and on the four
 * that did not, the generated module could not install at all -- observed on the iOS simulator as
 * `RuntimeError: the raw upcall entry points are not bound`, because `publish` installed
 * `_pm_resolve`/`_pm_bind`/`_pm_release`/`_pm_cancel` and deliberately no `_pm_invoke`.
 *
 * A test that only ever ran on the platform whose shim it was written against could not have found
 * that. So the contract lives here, where every target that has a boundary shim runs it, and
 * [publishesProxyEntryPoints] carries the one that does not -- as a per-target constant rather than
 * as something sniffed out of `globals()`, so a target that *stops* publishing fails rather than
 * quietly taking the other branch.
 *
 * It has already earned that once more: running here is what found the second half of the ART gap,
 * a `_pm_resolve` that read `str` but not the `bytes` [PythonProxySource] sends. Nothing that could
 * be read off the source said so -- see [publishesProxyEntryPoints].
 *
 * ### What stayed behind
 *
 * The three delivery tests that need a second thread to resume a parked Kotlin continuation:
 * `PythonProxyDeliveryTest` (desktopTest, `java.lang.Thread`) and `PythonProxyNativeDeliveryTest`
 * (nativeTest, `pthread_create`). Both drive [ProxyFragment], which is here, for the reason
 * `AsyncUpcallNativeDeliveryTest` gives for its own split: the thread primitive is the only part of
 * that story that is not common.
 *
 * ### How the two async paths are told apart
 *
 * The generated proxy hides what the boundary handed back -- that is the entire point of it -- so
 * `AsyncUpcallDeliveryTest`'s trick of asserting on `type(r).__name__` is not available here. The
 * loop's `create_future` is counted instead, which is a **stronger** statement about the same
 * thing: it says no `Future` was constructed at all on the fast path, not merely that one was not
 * returned.
 */
class PythonProxyInstallTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ProxyFragment))
        ProxyFragment.parked = null
        // `ProxyFragment` is an `object`, so the static-property fixtures outlive a single test.
        ProxyFragment.tally = 0
        ProxyFragment.created = 0
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
        ProxyFragment.parked = null
    }

    @Test
    fun aGeneratedProxyIsPublishedUnderItsKotlinPackageAndCallableAsAnOrdinaryFunction() =
        withProxies {
            // `from demo.calc import shout` -- the sys.modules injection is what makes the dotted
            // Kotlin name an importable Python name with no import hook anywhere.
            Python3.exec(
                """
                from demo.calc import shout, ping
                _p_sync = {'shout': shout('world'), 'ping': ping(), 'name': shout.__name__}
                """.trimIndent(),
            )

            assertEquals("hi world", PythonTestFixture.eval("_p_sync['shout']").toString())
            assertEquals("7", PythonTestFixture.eval("_p_sync['ping']").toString())
            assertEquals("shout", PythonTestFixture.eval("_p_sync['name']").toString())
        }

    @Test
    fun creatingAKotlinObjectFromPythonAndCallingAnInstanceMethodDispatchesThroughTheReceiverHandle() =
        withProxies {
            Python3.exec(
                """
                from proxycls import Counter
                c = Counter(10)
                _p_method = {'after': c.increment(5), 'again': c.increment(1)}
                """.trimIndent(),
            )

            assertEquals("15", PythonTestFixture.eval("_p_method['after']").toString())
            assertEquals("16", PythonTestFixture.eval("_p_method['again']").toString())
        }

    @Test
    fun readingAndWritingAPropertyGoesThroughTheGetterAndSetterEntries() =
        withProxies {
            Python3.exec(
                """
                from proxycls import Counter
                c = Counter(1)
                _p_prop = {'before': c.value, 'label_before': c.label}
                c.label = 'renamed'
                _p_prop['label_after'] = c.label
                """.trimIndent(),
            )

            assertEquals("1", PythonTestFixture.eval("_p_prop['before']").toString())
            assertEquals("counter", PythonTestFixture.eval("_p_prop['label_before']").toString())
            assertEquals("renamed", PythonTestFixture.eval("_p_prop['label_after']").toString())
        }

    @Test
    fun aStaticPropertyIsReadAndWrittenThroughTheClassObjectItselfRatherThanThroughAnInstance() =
        withProxies {
            // *After* install: a value the generator could not have baked in. A snapshot rendering
            // would report whatever `created` held while the source was being built.
            ProxyFragment.created = 5

            Python3.exec(
                """
                from proxycls import Counter
                _p_static = {'before': Counter.created, 'kind': Counter.KIND}
                Counter.created = 12
                _p_static['after'] = Counter.created
                try:
                    _p_static['on_instance'] = Counter(1).created
                except AttributeError:
                    _p_static['on_instance'] = 'AttributeError'
                """.trimIndent(),
            )

            assertEquals("5", PythonTestFixture.eval("_p_static['before']").toString())
            assertEquals("counter-class", PythonTestFixture.eval("_p_static['kind']").toString())
            assertEquals("12", PythonTestFixture.eval("_p_static['after']").toString())
            assertEquals(12L, ProxyFragment.created, "the assignment from Python has to reach Kotlin's setter")
            // Kotlin reaches a companion member through the class and never through an instance;
            // putting the descriptor on the metaclass reproduces that rather than inventing a
            // Python-only shape.
            assertEquals("AttributeError", PythonTestFixture.eval("_p_static['on_instance']").toString())
        }

    @Test
    fun aStaticPropertyWithNoExposedSetterRefusesAssignmentInsteadOfShadowingTheKotlinVal() =
        withProxies {
            Python3.exec(
                """
                from proxycls import Counter
                try:
                    Counter.KIND = 'overwritten'
                    _p_ro = 'assignment succeeded'
                except AttributeError:
                    _p_ro = 'AttributeError'
                _p_ro_after = Counter.KIND
                """.trimIndent(),
            )

            // Letting the assignment through would bind a plain class attribute that shadows the
            // Kotlin `val` for every later read -- silently, and only in Python.
            assertEquals("AttributeError", PythonTestFixture.eval("_p_ro").toString())
            assertEquals("counter-class", PythonTestFixture.eval("_p_ro_after").toString())
        }

    @Test
    fun aCompanionFunctionIsCallableThroughTheClassObjectAlongsideItsStaticProperties() =
        withProxies {
            Python3.exec(
                """
                from proxycls import Counter
                _p_sf = {'made': Counter.make(3), 'created_after': Counter.created}
                # the same class object still carries its instance surface
                _p_sf['instance'] = Counter(2).increment(1)
                try:
                    _p_sf['on_instance'] = Counter(1).make(3)
                except AttributeError:
                    _p_sf['on_instance'] = 'AttributeError'
                """.trimIndent(),
            )

            assertEquals("300", PythonTestFixture.eval("_p_sf['made']").toString())
            // The point of the coexistence check: the function and the property are two renderings
            // on one class object, and they have to be looking at the same Kotlin state.
            assertEquals("1", PythonTestFixture.eval("_p_sf['created_after']").toString())
            assertEquals(1L, ProxyFragment.created, "the call from Python has to reach Kotlin")
            assertEquals("3", PythonTestFixture.eval("_p_sf['instance']").toString())
            // Kotlin reaches a companion member through the class and never through an instance;
            // the metaclass reproduces that for functions the same way it does for properties.
            assertEquals("AttributeError", PythonTestFixture.eval("_p_sf['on_instance']").toString())
        }

    @Test
    fun aClassWhoseOnlyMemberIsACompanionFunctionStillRendersRunnablePython() =
        withProxies {
            // Everything this class has lives on the metaclass, so its own body is empty -- which
            // is only legal Python because the renderer emits `pass`. `exec` failing on the whole
            // generated module is how a missing one would show up, so this is checked by a call.
            Python3.exec("from proxycls import Factory\n_p_factory = Factory.spawn(41)")

            assertEquals("42", PythonTestFixture.eval("_p_factory").toString())
        }

    @Test
    fun aRenderedClassThatHasAMetaclassIsStillAnOwnerAtRunTimeAndNotJustInTheSource() =
        withProxies {
            // The base does not appear in the class statement for a class with statics -- Python has
            // no syntax for a base after a keyword, so `_pm_owned_new` on the metaclass puts it on
            // instead. That is a run-time step, and nothing about the generated text proves it ran.
            // Both classes this fixture renders have a metaclass (`Counter` has a companion,
            // `Factory` is nothing but one), so both go through it; `demo.calc`'s plain functions
            // do not, which is what makes the `_PmObject` question specific to these.
            Python3.exec(
                """
                from proxycls import Counter, Factory
                c = Counter(7)
                _p_owner = {
                    'instance': isinstance(c, _PmObject),
                    'class': issubclass(Counter, _PmObject),
                    'empty_body_class': issubclass(Factory, _PmObject),
                    # the metaclass is still the metaclass: injecting a base must not have cost the
                    # thing the base was moved out of the way for
                    'meta': type(Counter).__name__.startswith('_pm_t_'),
                    # and an instance crosses back as its handle rather than as a PyObject, which is
                    # the reason a rendered class had to become an owner in the first place
                    'unwraps': _pm_unwrap(c) == c._pm_handle,
                    # exactly one copy of the owner in the MRO, so a subclass of a rendered class
                    # cannot collect a duplicate base from the inherited metaclass
                    'once': [b.__name__ for b in Counter.__mro__].count('_PmObject'),
                }
                class _PSub(Counter):
                    pass
                _p_owner['subclass_once'] = [b.__name__ for b in _PSub.__mro__].count('_PmObject')
                del c
                """.trimIndent(),
            )

            assertEquals("true", PythonTestFixture.eval("_p_owner['instance']").toString().lowercase())
            assertEquals("true", PythonTestFixture.eval("_p_owner['class']").toString().lowercase())
            assertEquals("true", PythonTestFixture.eval("_p_owner['empty_body_class']").toString().lowercase())
            assertEquals("true", PythonTestFixture.eval("_p_owner['meta']").toString().lowercase())
            assertEquals("true", PythonTestFixture.eval("_p_owner['unwraps']").toString().lowercase())
            assertEquals("1", PythonTestFixture.eval("_p_owner['once']").toString())
            assertEquals("1", PythonTestFixture.eval("_p_owner['subclass_once']").toString())
        }

    @Test
    fun aRenderedClassIsNotShadowedByAModuleNamedAfterItsCompanionFunctions() =
        withProxies {
            // Before the fix `proxycls.Counter` was published twice: once as a module (by the
            // function path, for `make`) and once as the class. Whichever came last won, so this
            // asserts which of the two the name resolves to at all.
            Python3.exec(
                """
                import proxycls, sys
                _p_shadow = {
                    'is_module': isinstance(proxycls.Counter, type(sys)),
                    'is_class': isinstance(proxycls.Counter, type),
                    'module_left_behind': 'proxycls.Counter' in sys.modules,
                    'has_make': hasattr(proxycls.Counter, 'make'),
                }
                """.trimIndent(),
            )

            assertEquals("False", PythonTestFixture.eval("_p_shadow['is_module']").toString())
            assertEquals("True", PythonTestFixture.eval("_p_shadow['is_class']").toString())
            assertEquals("True", PythonTestFixture.eval("_p_shadow['has_make']").toString())
            // and no half-populated module is left in sys.modules for `from proxycls.Counter
            // import make` to find, which would be a second, contradictory answer for one name.
            assertEquals("False", PythonTestFixture.eval("_p_shadow['module_left_behind']").toString())
        }

    @Test
    fun aTopLevelStaticPropertyIsReadAndWrittenAsAnOrdinaryModuleAttribute() =
        withProxies {
            ProxyFragment.tally = 3

            Python3.exec(
                """
                import demo.calc
                _p_mod = {'before': demo.calc.tally, 'origin': demo.calc.origin}
                demo.calc.tally = 9
                _p_mod['after'] = demo.calc.tally
                # the module's ordinary function attributes must still resolve after the module
                # object was reclassed to carry the dynamic ones
                _p_mod['ping'] = demo.calc.ping()
                # and a name with no Kotlin declaration behind it must stay an ordinary attribute:
                # __setattr__ intercepts only what was registered, everything else falls through
                demo.calc.marker = 'plain'
                _p_mod['marker'] = demo.calc.marker
                """.trimIndent(),
            )

            assertEquals("3", PythonTestFixture.eval("_p_mod['before']").toString())
            assertEquals("kotlin", PythonTestFixture.eval("_p_mod['origin']").toString())
            assertEquals("9", PythonTestFixture.eval("_p_mod['after']").toString())
            assertEquals("7", PythonTestFixture.eval("_p_mod['ping']").toString())
            assertEquals("plain", PythonTestFixture.eval("_p_mod['marker']").toString())
            assertEquals(9L, ProxyFragment.tally, "the assignment from Python has to reach Kotlin's setter")
        }

    @Test
    fun aReadOnlyTopLevelStaticPropertyRefusesAssignmentToo() =
        withProxies {
            Python3.exec(
                """
                import demo.calc
                try:
                    demo.calc.origin = 'overwritten'
                    _p_mod_ro = 'assignment succeeded'
                except AttributeError:
                    _p_mod_ro = 'AttributeError'
                _p_mod_ro_after = demo.calc.origin
                """.trimIndent(),
            )

            assertEquals("AttributeError", PythonTestFixture.eval("_p_mod_ro").toString())
            assertEquals("kotlin", PythonTestFixture.eval("_p_mod_ro_after").toString())
        }

    @Test
    fun theSameGeneratedProxyBuildsNoFutureWhenTheKotlinBodyNeverSuspends() =
        withProxies(needsAsyncio = true) {
            ProxyLoopHarness.install()

            // Identical call site, identical `await`, and the difference is invisible from Python --
            // which is what the conditional await in the generated `async def` is for.
            Python3.exec("_drive(demo.calc.doubleNow(21))")

            assertEquals("None", PythonTestFixture.eval("_p['error']").toString())
            assertEquals("42", PythonTestFixture.eval("_p['value']").toString())
            assertEquals(
                "0",
                PythonTestFixture.eval("_p['created']").toString(),
                "an already-complete call must not be routed through asyncio at all",
            )
            assertNull(ProxyFragment.parked, "nothing was ever left outstanding")
        }

    /**
     * Publishes this target's bootstrap, installs the proxies and runs [block].
     *
     * On a target [publishesProxyEntryPoints] says has no proxy bootstrap, [block] is not run and
     * the *documented refusal* is asserted instead. That is not a skip: the generated module's own
     * guard is what has to fire, and if such a target ever grows a shim -- or if a target that has
     * one loses it -- one of the two branches fails.
     *
     * @param needsAsyncio for the one test that builds an event loop. There is no refusal to assert
     *   for that one on a target where [proxyBootstrapSupportsAsyncio] is false: `import asyncio`
     *   **traps the wasm instance** rather than raising, so reaching it at all takes the suite with
     *   it. Not running it is the only available answer, and the constant is where that is written
     *   down.
     */
    private inline fun withProxies(needsAsyncio: Boolean = false, block: () -> Unit) =
        PythonTestFixture.withInterpreter {
            // Binds this platform's raw entry points into `__main__`. The generated module needs
            // them and refuses to install without them; which name is bound is irrelevant, only
            // the bootstrap is.
            assertTrue(bindUpcallOrNull("demo.calc.ping"), "the fixture table is not installed")

            if (!publishesProxyEntryPoints) {
                val refusal = assertFails { PythonProxySource.install() }
                assertTrue(
                    refusal.message?.contains("raw upcall entry points are not bound") == true,
                    "a target with no proxy bootstrap must fail the generated module's own guard, " +
                        "not something else: $refusal",
                )
                return@withInterpreter
            }

            PythonProxySource.install()
            if (needsAsyncio && !proxyBootstrapSupportsAsyncio) {
                // Everything up to here ran: the proxies really are installed on this target, and
                // that much is asserted rather than skipped. What cannot follow is the event loop.
                println(
                    "\n--- ${currentPlatform.name}: `import asyncio` traps this instance, so the " +
                        "await fast path is not exercised here; see proxyBootstrapSupportsAsyncio\n",
                )
                return@withInterpreter
            }
            block()
        }
}

/**
 * A loop whose `create_future` is counted.
 *
 * The wrapper is an instance attribute on a pure-Python selector loop, so it shadows the class
 * method for this loop only and `AsyncUpcall`'s `loop.create_future()` goes through it -- there is
 * no other way to observe from Python whether the boundary took the fast path, because the
 * generated proxy deliberately makes the two indistinguishable to its caller.
 *
 * Shared with the two delivery tests, which are the ones that need the count to be **1**.
 */
object ProxyLoopHarness {

    fun install() {
        Python3.exec(
            """
            import asyncio
            # The proxies live in modules injected into sys.modules; injecting them does not bind
            # `demo` as a name here, so the ordinary import statement still has to run. That it
            # works at all is the point of the injection -- there is no finder that could locate
            # `demo.calc` on disk.
            import demo.calc
            _p = {}
            _p['loop'] = asyncio.new_event_loop()
            _p['created'] = 0

            def _count_futures(_loop=_p['loop'], _orig=_p['loop'].create_future):
                def _wrapped():
                    _p['created'] += 1
                    return _orig()
                _loop.create_future = _wrapped

            _count_futures()

            def _drive(coro):
                _p['value'] = None
                _p['error'] = None
                try:
                    _p['value'] = _p['loop'].run_until_complete(coro)
                except BaseException as e:
                    _p['error'] = type(e).__name__ + ': ' + str(e)
            """.trimIndent(),
        )
    }
}

/**
 * Entries shaped the way KSP emits them, under names whose Kotlin package maps to a Python module
 * that is a legal identifier -- unlike `AsyncTrampolineFragment`'s `async.*`, which `sys.modules`
 * accepts but no `import` statement can spell.
 */
object ProxyFragment : FunctionTableFragment {

    override val moduleName: String = "test_python_proxy"

    /**
     * The resumption a suspended fixture parked, handed to whichever thread the delivery tests
     * start.
     *
     * A single slot rather than a queue because only one call is ever outstanding, and
     * `@Volatile` rather than a `java.util.concurrent` type because this fixture is `commonTest`
     * now -- the same exchange `AsyncUpcallNativeDeliveryTest` makes with an `AtomicReference`.
     */
    @Volatile
    var parked: (() -> Unit)? = null

    /**
     * The Kotlin state behind the `STATIC_GETTER`/`STATIC_SETTER` entries: a top-level `var` in
     * `demo.calc` and a companion-style `var` on `proxycls.Counter`. Both are read and written from
     * the Kotlin side of the assertions, which is how "the Python assignment reached Kotlin" is
     * observed rather than assumed.
     */
    var tally: Long = 0

    var created: Long = 0

    private suspend fun doubleLater(x: Long): Long = suspendCoroutine { c -> parked = { c.resume(x * 2) } }

    private suspend fun doubleNow(x: Long): Long = x * 2

    private suspend fun failLater(): Long =
        suspendCoroutine { c -> parked = { c.resumeWithException(IllegalStateException("proxy boom")) } }

    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "demo.calc.doubleLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleLater(args[0] as Long) } },
        ExposedCallable(
            name = "demo.calc.doubleNow",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { args -> PendingCall.start { doubleNow(args[0] as Long) } },
        ExposedCallable(
            name = "demo.calc.failLater",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            isSuspend = true,
        ) { PendingCall.start { failLater() } },
        ExposedCallable(
            name = "demo.calc.shout",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING,
        ) { args -> "hi ${args[0] as String}" },
        ExposedCallable(
            name = "demo.calc.ping",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
        ) { 7L },
        // A top-level Kotlin `val`: readable as a module attribute, and not assignable.
        ExposedCallable(
            name = "demo.calc.origin",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.STATIC_GETTER,
        ) { "kotlin" },
        // A top-level Kotlin `var`: the same, plus a setter entry.
        ExposedCallable(
            name = "demo.calc.tally",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.STATIC_GETTER,
        ) { tally },
        ExposedCallable(
            name = "demo.calc.tally=",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.STATIC_SETTER,
        ) { args -> tally = args[0] as Long },
    ) + counterEntries()

    override fun classes(): List<ReflectedClass> = listOf(
        // A class whose *only* member is a companion function: the metaclass carries everything and
        // the class body is empty, which is the one rendered shape that has to fall back to `pass`
        // to be legal Python at all.
        ReflectedClass(name = FACTORY, memberNames = listOf("$FACTORY.spawn")),
        ReflectedClass(
            name = COUNTER,
            memberNames = listOf(
                "$COUNTER.<init>",
                "$COUNTER.increment",
                "$COUNTER.value",
                "$COUNTER.label",
                "$COUNTER.label=",
                "$COUNTER.fetchLater",
                "$COUNTER.KIND",
                "$COUNTER.created",
                "$COUNTER.created=",
                "$COUNTER.make",
            ),
        ),
    )

    private const val COUNTER = "proxycls.Counter"

    private const val FACTORY = "proxycls.Factory"

    private fun counterEntries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "$FACTORY.spawn",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.FUNCTION,
        ) { args -> (args[0] as Long) + 1 },
        ExposedCallable(
            name = "$COUNTER.<init>",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.OBJECT,
            kind = CallableKind.CONSTRUCTOR,
        ) { args -> ProxyCounter(args[0] as Long) },
        ExposedCallable(
            name = "$COUNTER.increment",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.METHOD,
        ) { args -> (args[0] as ProxyCounter).increment(args[1] as Long) },
        ExposedCallable(
            name = "$COUNTER.value",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.GETTER,
        ) { args -> (args[0] as ProxyCounter).value },
        ExposedCallable(
            name = "$COUNTER.label",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.GETTER,
        ) { args -> (args[0] as ProxyCounter).label },
        ExposedCallable(
            name = "$COUNTER.label=",
            arity = 1,
            paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.UNIT,
            kind = CallableKind.SETTER,
        ) { args -> (args[0] as ProxyCounter).label = args[1] as String },
        ExposedCallable(
            name = "$COUNTER.fetchLater",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.METHOD,
            isSuspend = true,
        ) { args -> PendingCall.start { (args[0] as ProxyCounter).fetchLater(args[1] as Long) } },
        // The companion-object shape: no receiver in `args`, and the class object itself is what
        // Python reads and writes. `KIND` is the `val` half, `created` the `var` half.
        ExposedCallable(
            name = "$COUNTER.KIND",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.STRING,
            kind = CallableKind.STATIC_GETTER,
        ) { "counter-class" },
        ExposedCallable(
            name = "$COUNTER.created",
            arity = 0,
            paramTypes = emptyList(),
            returnType = TypeTag.INT,
            kind = CallableKind.STATIC_GETTER,
        ) { created },
        ExposedCallable(
            name = "$COUNTER.created=",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.UNIT,
            kind = CallableKind.STATIC_SETTER,
        ) { args -> created = args[0] as Long },
        // The other half of the companion shape, and the one the class rendering used to bury: a
        // companion *function* is a `CallableKind.FUNCTION` whose name is `Owner.fn`, exactly what
        // `FragmentScanner.companionEntries` emits for `WithCompanion.create`. It bumps `created`
        // so that the static property and the static function can be observed to be looking at the
        // same Kotlin state.
        ExposedCallable(
            name = "$COUNTER.make",
            arity = 1,
            paramTypes = listOf(TypeTag.INT),
            returnType = TypeTag.INT,
            kind = CallableKind.FUNCTION,
        ) { args ->
            created += 1
            (args[0] as Long) * 100
        },
    )
}

/**
 * The instance fixture for [PythonProxyInstallTest]'s class-rendering tests: a receiver that
 * arrives in `args[0]` the way `CallableKind.METHOD`/`GETTER`/`SETTER` require, resolved from the
 * `self._pm_handle` integer the generated `Counter.__init__` stashes. Named differently from
 * [ProxyFragment]'s Python-visible `Counter` so a grep for the Kotlin declaration is unambiguous.
 */
class ProxyCounter(private var total: Long) {
    fun increment(by: Long): Long {
        total += by
        return total
    }

    val value: Long get() = total

    var label: String = "counter"

    suspend fun fetchLater(x: Long): Long =
        suspendCoroutine { c -> ProxyFragment.parked = { c.resume(total + x) } }
}
