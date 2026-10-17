package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.types.basic.PyBool
import python.multiplatform.ffi.types.basic.PyFloat
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.types.basic.PyString
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.multiplatform.reflection.TypeTag

/**
 * The other direction: **a Python callable arriving at a Kotlin function-typed parameter.**
 *
 * `docs/archive/pythonx-adapter-design.md` §6 opens by saying the mechanism is half present already --
 * `UpcallTrampoline.toKotlinObject` wraps anything Python sends that is not an integer handle as a
 * `PyObject`, so a `lambda:` does reach Kotlin with a reference of its own. What it reaches is a
 * `PyObject`, and every container composable's `content` slot wants a `kotlin.jvm.functions.FunctionN`.
 * That gap is the whole of why `Column`, `Row`, `Box` and `Button` were reachable only with `content`
 * defaulted -- and `Column`'s `content` declares no default at all, so it was not reachable.
 *
 * ### Why the wrapper can be built without Compose on the classpath
 *
 * A lowered `@Composable ColumnScope.() -> Unit` is, in the compiled form the thunk calls,
 * `Function3<ColumnScope, Composer, Integer, Unit>` -- and generics erase. The thunk's `CHECKCAST` is
 * to `kotlin/jvm/functions/Function3`, and Compose's own call is
 * `Function3.invoke(Object, Object, Object)`. So a Kotlin lambda of type `(Any?, Any?, Any?) -> Unit`
 * written in `commonMain` **is** an admissible `content`, and nothing here has to name `Composer`,
 * `ColumnScope` or any other Compose type. That is what keeps this file in `commonMain`, where the
 * `pythonx` layer it serves already lives, rather than in a Compose-aware module.
 *
 * ### What separates a `Function0` from a composable lambda, and how it is known
 *
 * Not by guessing at the arity. `ArtifactScanner.functionSlotTypeName` compares the two arities the
 * walker already has -- the declared `kotlin.FunctionN` from `@Metadata` against the
 * `Lkotlin/jvm/functions/FunctionM;` in the JVM descriptor -- and a composable lambda is exactly the
 * case `M == N + 2`, because the Compose plugin appends a `Composer` and one `$changed` to the
 * *function type* just as it does to a function. `Button`'s `onClick` is `Function0` in both, so it
 * is plain; `Column`'s `content` is `Function1` declared and `Function3` compiled, so it is lowered.
 * The walker writes the answer into the type name it already carries, and `pythonx` reads it there.
 *
 * The consequence for a caller is the one that matters: a **composable** lambda is invoked with the
 * composer its container established, which is threaded back into `pythonx`'s composer stack for the
 * duration of the call, so a `content` may itself call composables. A **plain** one is not, and
 * receives nothing.
 *
 * ### Lifetime: the scope is the holder, and it is the only one that can be
 *
 * Compose stores a `content` in the slot table and calls it again on later recompositions, so the
 * Python callable has to outlive the call that passed it -- `Column(content=lambda: ...)` drops
 * Python's last reference at the end of the statement. Something on the Kotlin side must hold one.
 *
 * Four candidates, and three of them do not work:
 *
 * | holder | why not |
 * |---|---|
 * | the `PyObject`'s own cleaner | it fires when the *Kotlin* wrapper is collected, which is whenever the collector gets round to it and never on a target without one. `docs/design/object-lifetime.md`'s point exactly |
 * | `HandleTable` | a strong root nothing gives back: the handle reaches Kotlin as a slot value and is never handed to Python, so there is no `__del__` to hang the release off (`HandleTable`'s own KDoc: "this table leaks by construction") |
 * | the composition **pass** | ends long before Compose stops calling the content |
 * | **[PythonCallableScope]** | lives exactly as long as the composition, because a Compose-side `RememberObserver` closes it in `onForgotten` |
 *
 * So this file owns the first three quarters of that -- creating, holding and releasing -- and the
 * one Compose type involved, `RememberObserver`, stays in the module that has Compose
 * (`:python-multiplatform-compose:fixtures:compose`'s `PythonComposition`). `docs/archive/pythonx-adapter-design.md` §6 item 1 names
 * `onForgotten` as "the one that fails silently and should be tested first"; the seam is here and the
 * test is there.
 *
 * ### Interning, and what the holder costs without it
 *
 * The scope releases everything at once, which settles *whether* a callable comes back and says
 * nothing about how much is held meanwhile. `RecompositionAccumulationTest` measured that: one
 * `content=` composed twelve times held **twelve** wrappers, twelve `HandleTable` roots and twelve
 * Python references, all of them until disposal. Linear in frames, and a UI recomposes several times
 * a second.
 *
 * So a crossing now asks the scope for a wrapper it already made before making one. What counts as
 * "already made" is `PythonxAdapter._intern_key`, and it is neither `id()` nor `==`: a `lambda:` is a
 * new object every pass *and* `PythonComposition` `exec`s a source string, so it is a newly compiled
 * one. The key is what the callable is made of -- its code by value, its captures and defaults by
 * address, and the slot it is going into. [PythonCallableScope.reuseCount] reports how many crossings
 * that answered, so "the count stopped growing" cannot be confused with "nothing crossed".
 *
 * Two callables that are genuinely different still get two wrappers, and one closing over a value
 * that changes between passes is genuinely different -- sharing there would leave Compose calling
 * the first pass's capture forever. That case still accumulates, and it is the one a release point
 * would have to solve; `RecompositionAccumulationTest` states its number rather than leaving it to be
 * discovered.
 *
 * ### The release point, and why nothing releases early yet
 *
 * `RememberObserver` is not it and cannot be: a `content` is a *parameter*, not a remembered value,
 * so nothing reports the slot being dropped. The candidate that remains is a sweep at the end of each
 * composition pass -- give back every wrapper the pass neither built nor reused -- and its
 * precondition was measured rather than assumed:
 * `RecompositionAccumulationTest.everyLiveWrapperIsResuppliedOnEveryPassEvenWhenNested` shows a
 * nested `content` re-supplying **both** its wrappers on every pass, so nothing Compose holds goes
 * untouched. That test is the guard a sweep would be built on.
 *
 * It is not built, and the reason was a shape nothing had measured: a slot Compose *retains across a
 * re-supply*. `LaunchedEffect(key) { block }` keeps the block it has when `key` is unchanged, so a
 * pass supplying a different wrapper would leave the retained one untouched and still live -- and a
 * wrapper released while Compose still calls it is `agent-rules` §14's failure, arriving somewhere
 * else. Interning bounds the case that actually recurs; the sweep needed that measurement first.
 *
 * **That measurement now exists, and the verdict is that a same-pass sweep is unsafe.**
 * `:python-multiplatform-compose:fixtures:compose`'s `RetainedSlotSweepPreconditionTest
 * .aRetainingSlotKeepsPassZerosWrapperWhileLaterPassesCrossAndGoUntouched` drives a real
 * `remember(key)` (the mechanism `LaunchedEffect(key)` is built on) with a constant key across six
 * recompositions of one composition, each supplying a callable with a distinct capture -- so each
 * pass crosses a genuinely different wrapper, exactly `aCapturedValueThatChangesIsADifferentCallable
 * AndIsNotShared`'s shape. Measured over those six passes: `remember(key)` returns pass 0's wrapper
 * on every one of them (confirmed against the real Compose runtime, not assumed from its docs), while
 * [PythonCallableScope.liveCount] grows by exactly one new wrapper per pass and
 * [PythonCallableScope.reuseCount] stays at zero throughout -- meaning pass 0's wrapper is *never
 * touched again* (neither rebuilt nor reused) from pass 1 on, which is precisely the criterion a
 * same-pass "untouched" sweep would release it under. Pass 0's underlying Python callable's own
 * refcount is confirmed unmoved across all six passes, so the reference the scope is holding for it
 * is real, single, and still exactly what `remember(key)` is still hands out on the last pass -- not
 * already gone, which is why releasing it would be worse than the current leak rather than merely
 * redundant.
 *
 * So: **a sweep keyed on "built or reused this pass" must not be built as the only criterion.** It
 * would release a wrapper a retaining slot is still returning, the first time a composition contains
 * one. Closing the leak this file otherwise documents needs the sweep to also recognise (or otherwise
 * exempt) a retained slot's wrapper -- which this measurement did not attempt, since implementing the
 * sweep was not its task.
 *
 * ### Double release
 *
 * [PythonCallableScope.close] reports how many wrappers it released and answers `0` for every call
 * after the first, which is what `PythonxCallableTest` asserts against rather than only asserting
 * that the count came back. `agent-rules` §14 is the reason the two are separate assertions: a
 * reference released twice does not fail where it happens.
 *
 * A wrapper invoked after its scope closed **refuses**. Calling through a released `PyObject` would
 * not raise -- it would decrement whatever now occupies that address.
 */
object PythonCallables {

    /** The scopes currently open, innermost last. Not synchronised, for [HandleTable]'s reason:
     * every mutation happens on a thread that holds the GIL. */
    private val open = ArrayList<PythonCallableScope>()

    /** The scope a callable crossing right now belongs to, or `null` if none is open. */
    val current: PythonCallableScope? get() = open.lastOrNull()

    /** How many scopes are open. A composition that leaked one leaves this non-zero. */
    val openScopeCount: Int get() = open.size

    /** A new, empty scope. Nothing is ambient until it is passed to [withScope]. */
    fun newScope(): PythonCallableScope = PythonCallableScope()

    /**
     * Makes [scope] the ambient one for [block].
     *
     * Nested rather than replaced, because a `content` that is itself a container establishes an
     * inner composition-shaped region while an outer one is still running. Innermost wins, which is
     * the same rule `pythonx`'s composer stack uses and for the same reason.
     */
    inline fun <T> withScope(scope: PythonCallableScope, block: () -> T): T {
        push(scope)
        try {
            return block()
        } finally {
            pop(scope)
        }
    }

    /** @suppress internal to [withScope], which is the only supported way to nest a scope. */
    @PublishedApi
    internal fun push(scope: PythonCallableScope) {
        open.add(scope)
    }

    /** @suppress see [push]. */
    @PublishedApi
    internal fun pop(scope: PythonCallableScope) {
        val last = open.removeAt(open.size - 1)
        check(last === scope) { "pythonx callable scopes were closed out of order" }
    }

    /**
     * What `pythonx._make_function` calls: wrap [body] as a Kotlin `FunctionN` of [jvmArity].
     *
     * @param body the Python callable, already holding a reference of its own -- the trampoline's
     *   `PyObject(value, borrowed = true)`. The scope takes ownership of that reference and is what
     *   gives it back.
     * @param jvmArity how many arguments the *compiled* function type takes: 0 for `() -> Unit`, 1
     *   for `(Float) -> Unit`, 3 for a lowered `@Composable ColumnScope.() -> Unit`.
     * @param composable whether the last two of those are `$composer` and `$changed`.
     * @param argTags one [TypeTag] name per **forwarded** argument, comma-separated and possibly
     *   empty -- see [marshalArgument] for why this is passed rather than derived here.
     * @return the raw [ObjectReference] the wrapper is rooted under, which Python puts straight into
     *   the OBJECT slot as a bare handle.
     */
    /**
     * The cached wrapper for [internKey] in the open scope, or `0` if there is none.
     *
     * Asked **before** the thunk is built, which is the whole point of it being a separate entry: on
     * a hit the Python side skips `inspect.signature` and the closure allocation entirely, and a
     * recomposing composition takes that path every time after the first. Routing the question
     * through [newFunction] instead would mean paying for both on every pass and then throwing the
     * result away.
     *
     * `0` rather than an exception for "no scope is open": a lookup that cannot find a scope has not
     * found a wrapper either, and the caller's next step is [newFunction], which raises the message
     * that explains what a scope is. Two places saying it would let them drift.
     */
    internal fun findFunction(internKey: String): Long {
        if (internKey.isEmpty()) return ObjectReference.NONE_RAW
        return current?.reuse(internKey) ?: ObjectReference.NONE_RAW
    }

    internal fun newFunction(
        body: PyObject,
        jvmArity: Int,
        composable: Boolean,
        argTags: String,
        internKey: String,
    ): Long {
        val scope = current ?: run {
            // Closed here rather than leaked: the trampoline already took a reference for this
            // wrapper and nothing downstream exists to give it back.
            body.close()
            throw IllegalStateException(
                "no pythonx callable scope is open, so a Python callable has nowhere to live: a " +
                    "callable can only cross into Kotlin from inside a composition (or an explicit " +
                    "PythonCallables.withScope), because Compose keeps calling it after the call " +
                    "that passed it has returned",
            )
        }
        // Asked again even though `findFunction` was asked first: that call is an optimisation the
        // Python side may skip (it does, for a callable with no structure to key on), and a second
        // entry under one key would be a wrapper the scope holds and nothing can ever reach.
        val cached = scope.reuse(internKey)
        if (cached != ObjectReference.NONE_RAW) {
            body.close()
            return cached
        }
        if (composable && jvmArity < 2) {
            body.close()
            throw IllegalArgumentException(
                "a lowered composable lambda takes at least a \$composer and a \$changed, so arity " +
                    "$jvmArity cannot be one",
            )
        }
        val tags = if (argTags.isEmpty()) emptyList() else argTags.split(',')
        val forwarded = jvmArity - if (composable) COMPOSABLE_LOWERED_SLOTS else 0
        if (tags.size != forwarded) {
            body.close()
            // Not a diagnostic: the two sides marshal from the *same* list, and this is the check
            // that they are in fact the same list. A short one would silently pair a `Float` with an
            // OBJECT rule and hand Python a handle to a boxed number.
            throw IllegalArgumentException(
                "a Kotlin function type of compiled arity $jvmArity forwards $forwarded argument(s), " +
                    "but ${tags.size} marshalling tag(s) were given: '$argTags'",
            )
        }
        val function = PythonFunction(scope, body, composable, tags)
        val erased = try {
            function.erasedAs(jvmArity)
        } catch (t: Throwable) {
            // Nothing has taken ownership yet, so the reference the trampoline took for this
            // wrapper is this frame's to give back.
            body.close()
            throw t
        }
        return scope.add(body, erased, internKey)
    }

    /** How many of a lowered composable lambda's compiled arguments are the compiler's rather than
     * the declaration's: the `$composer` and the one `$changed`. Mirrors
     * `ArtifactScanner.COMPOSABLE_LOWERED_SLOTS`, and the two are checked against each other by the
     * arity/tag-count agreement above rather than by being shared, since they are in different
     * modules. */
    private const val COMPOSABLE_LOWERED_SLOTS = 2

    /**
     * The table entry [newFunction] is reached through.
     *
     * A fragment rather than a new `_pm_*` entry point on five bootstraps: this is one ordinary
     * Kotlin function called from Python, which is precisely what the upcall table is, and adding a
     * fourth C shape to `UpcallStub`, `bindings.kt` and three native bootstraps to carry it would be
     * platform work for no new capability. [PythonxAdapter.install] registers it, so the layer that
     * needs the service is the layer that declares it.
     *
     * Each entry is named by its **true Kotlin fully-qualified name**
     * (`python.multiplatform.ffi.pythonx.PythonCallables.newFunction`). It used to be
     * `pythonx.runtime.newFunction`, which made the proxy layer create a `pythonx` module in
     * `sys.modules` -- a binder-made name squatting on the real pythonx package -- and exported a
     * Kotlin declaration under a name that was not its own. Neither is allowed (`AGENTS.md` §12.1,
     * §12.2).
     */
    internal object Fragment : FunctionTableFragment {

        override val moduleName: String = "python_multiplatform_runtime"

        override fun entries(): List<ExposedCallable> = listOf(
            ExposedCallable(
                name = NEW_FUNCTION,
                arity = 5,
                paramTypes = listOf(TypeTag.OBJECT, TypeTag.INT, TypeTag.BOOLEAN, TypeTag.STRING, TypeTag.STRING),
                returnType = TypeTag.INT,
                paramNames = listOf("body", "jvmArity", "composable", "argTags", "internKey"),
                // `body` is declared as the Python object type here, not `kotlin.Any` -- the
                // callable below does `args[0] as PyObject`, and `PythonProxySource.argValues`
                // (`PY_OBJECT`) unwraps every OBJECT slot to its raw handle *except* one declared
                // exactly this name. `kotlin.Any` would make a proxy passed as `body` through a
                // rendered call site get unwrapped to the Kotlin object behind it, and fail this
                // cast -- `PythonCallablesProxyArgumentTest` pins the reproduction and the fix.
                paramTypeNames = listOf(
                    "python.multiplatform.ffi.PyObject", "kotlin.Int", "kotlin.Boolean", "kotlin.String",
                    "kotlin.String",
                ),
                paramHasDefault = listOf(false, false, false, false, false),
                callable = { args ->
                    newFunction(
                        args[0] as PyObject,
                        (args[1] as Long).toInt(),
                        args[2] as Boolean,
                        args[3] as String,
                        args[4] as String,
                    )
                },
            ),
            ExposedCallable(
                name = FIND_FUNCTION,
                arity = 1,
                paramTypes = listOf(TypeTag.STRING),
                returnType = TypeTag.INT,
                paramNames = listOf("internKey"),
                paramTypeNames = listOf("kotlin.String"),
                paramHasDefault = listOf(false),
                callable = { args -> findFunction(args[0] as String) },
            ),
            // Issue #69: a Python scalar written into a `kotlin.Any` slot, boxed as the Kotlin type
            // `PythonxAdapter._box_scalar` chose. Each takes the primitive tag that already carries
            // that value exactly, and answers with the handle of the box (see [boxScalar]).
            boxEntry(BOX_INT, TypeTag.INT, "kotlin.Int") { (it as Long).toInt() },
            boxEntry(BOX_LONG, TypeTag.INT, "kotlin.Long") { it as Long },
            boxEntry(BOX_DOUBLE, TypeTag.FLOAT, "kotlin.Double") { it as Double },
            boxEntry(BOX_BOOLEAN, TypeTag.BOOLEAN, "kotlin.Boolean") { it as Boolean },
            boxEntry(BOX_STRING, TypeTag.STRING, "kotlin.String") { it as String },
            ExposedCallable(
                name = UNBOX_SCALAR,
                arity = 1,
                // INT, not OBJECT: this needs the handle *number*, to release it, and an OBJECT
                // slot would hand over only the object it resolves to.
                paramTypes = listOf(TypeTag.INT),
                returnType = TypeTag.OBJECT,
                paramNames = listOf("handle"),
                paramTypeNames = listOf("kotlin.Long"),
                // No return type name, like the two entries above: the result is a Python scalar or
                // `None`, never a handle, so no producer may try to own it.
                paramHasDefault = listOf(false),
                callable = { args -> unboxScalar(args[0] as Long) },
            ),
        )

        private fun boxEntry(name: String, tag: TypeTag, typeName: String, box: (Any?) -> Any): ExposedCallable =
            ExposedCallable(
                name = name,
                arity = 1,
                paramTypes = listOf(tag),
                returnType = TypeTag.INT,
                paramNames = listOf("value"),
                paramTypeNames = listOf(typeName),
                paramHasDefault = listOf(false),
                callable = { args -> boxScalar(box(args[0])) },
            )
    }

    /**
     * Roots [value] -- a Kotlin box the binding layer asked for -- and returns its raw handle.
     *
     * ### Why a box crosses as a handle at all
     *
     * The value is on its way into an `OBJECT` slot, and the only thing an `OBJECT` slot reads a
     * Python `int` as is a [HandleTable] handle (`UpcallTrampoline.toKotlinObject`). So the box is
     * made here, rooted, and its handle is what Python then puts in the slot; the slot resolves it to
     * this very object, and whatever Kotlin stores it in holds it from then on by an ordinary
     * reference.
     *
     * ### Who gives the root back
     *
     * Python, as soon as the argument tuple the handle sits in is gone: `PythonxAdapter
     * ._BoxedArgument` is an `int` whose `__del__` releases it, so the root lives exactly as long as
     * the one call it was made for. Releasing here instead would hand the slot a stale handle.
     */
    internal fun boxScalar(value: Any): Long = HandleTable.register(value).raw

    /**
     * What a handle Python read out of a `kotlin.Any` slot means, if it is a boxed scalar.
     *
     * @return a [python.multiplatform.ffi.upcall.ScalarResult] -- which the trampoline marshals as
     *   the Python scalar, as a new reference -- **after releasing [raw]**, because the Python side
     *   keeps no proxy over a scalar and the handle has no other owner; or `null` (`None`) for any
     *   other object, whose handle is left rooted for the proxy Python then builds over it. A stale
     *   [raw] is `null` too: there is nothing to release, and the proxy built over it refuses on use.
     */
    internal fun unboxScalar(raw: Long): Any? {
        val held = HandleTable.resolveRaw(raw) ?: return null
        when (held) {
            is Boolean, is Int, is Long, is Short, is Byte, is Double, is Float, is String, is Char -> Unit
            else -> return null
        }
        HandleTable.release(ObjectReference(raw))
        return python.multiplatform.ffi.upcall.ScalarResult(held)
    }

    /** The name the binding layer resolves. Shared so the Python source and the entry cannot drift. */
    internal const val NEW_FUNCTION: String = "python.multiplatform.ffi.pythonx.PythonCallables.newFunction"

    /** @see findFunction */
    internal const val FIND_FUNCTION: String = "python.multiplatform.ffi.pythonx.PythonCallables.findFunction"

    /** @see boxScalar */
    internal const val BOX_INT: String = "python.multiplatform.ffi.pythonx.PythonCallables.boxInt"

    /** @see boxScalar */
    internal const val BOX_LONG: String = "python.multiplatform.ffi.pythonx.PythonCallables.boxLong"

    /** @see boxScalar */
    internal const val BOX_DOUBLE: String = "python.multiplatform.ffi.pythonx.PythonCallables.boxDouble"

    /** @see boxScalar */
    internal const val BOX_BOOLEAN: String = "python.multiplatform.ffi.pythonx.PythonCallables.boxBoolean"

    /** @see boxScalar */
    internal const val BOX_STRING: String = "python.multiplatform.ffi.pythonx.PythonCallables.boxString"

    /** @see unboxScalar */
    internal const val UNBOX_SCALAR: String = "python.multiplatform.ffi.pythonx.PythonCallables.unboxScalar"
}

/**
 * Everything one composition handed to Kotlin, and the single point that gives it all back.
 *
 * Created by [PythonCallables.newScope], made ambient by [PythonCallables.withScope], and closed by
 * whoever owns the composition -- on Compose that is a `RememberObserver`, whose `onForgotten` is the
 * only hook that reports a slot being dropped.
 */
class PythonCallableScope internal constructor() {

    private val bodies = ArrayList<PyObject>()
    private val roots = ArrayList<ObjectReference>()

    /**
     * The wrappers this scope can hand out again, by the key `PythonxAdapter._intern_key` computed
     * for the callable and its slot.
     *
     * ### Why the table is here and not in Python
     *
     * The value is a `HandleTable` root, and a root outlives its scope by exactly nothing -- [close]
     * releases it. A Python-side cache would have to be told when that happened, which is the same
     * "who says it is over" problem the wrapper itself has, one level up. Here the answer is free:
     * the map dies with the thing it describes.
     *
     * ### Why the key can contain `id()`s and still be sound
     *
     * `_intern_key` is built out of the addresses of a callable's code object, its captured values
     * and its defaults, and an address is only unambiguous while the object at it is alive. It is:
     * every entry in this map is a root over a wrapper that holds the `PyObject` for the callable,
     * which holds all of them. So no key in this table can name a freed object, and the id reuse
     * that would make two different callables collide cannot happen while the entry exists.
     * `HandleTable.register`'s own KDoc says registration is *not* interning and that two proxies
     * over one object need not agree -- that stays true, and this is the layer above it that does
     * intern, for the one case where the object crossing is a callable Compose will call again.
     */
    private val interned = HashMap<String, Long>()

    private var closed = false
    private var reused = 0

    /** How many callables this scope is currently holding a Python reference for. */
    val liveCount: Int get() = bodies.size

    /**
     * How many crossings were answered out of [interned] instead of building a wrapper.
     *
     * Reported so that "the count stopped growing" and "nothing crossed at all" are distinguishable:
     * a `content=` that stopped reaching Kotlin would leave [liveCount] at 1 as well, and only this
     * says the later passes were served rather than skipped.
     */
    val reuseCount: Int get() = reused

    /** Whether [close] has already run. A wrapper checks this before touching its `PyObject`. */
    val isClosed: Boolean get() = closed

    /** The handle already issued for [internKey], or [ObjectReference.NONE_RAW]. */
    internal fun reuse(internKey: String): Long {
        if (internKey.isEmpty() || closed) return ObjectReference.NONE_RAW
        val found = interned[internKey] ?: return ObjectReference.NONE_RAW
        reused++
        return found
    }

    internal fun add(body: PyObject, erased: Any, internKey: String): Long {
        val root = HandleTable.register(erased)
        bodies.add(body)
        roots.add(root)
        // An empty key is "this callable has no structure to key on" -- a C builtin, say. It gets a
        // wrapper per crossing, which is what everything got before this table existed.
        if (internKey.isNotEmpty()) interned[internKey] = root.raw
        return root.raw
    }

    /**
     * Gives every Python reference back and unroots every wrapper.
     *
     * @return how many callables this call actually released -- `0` for every call after the first.
     *   A count rather than a `Boolean` so that a test can assert both "the scope held what it said
     *   it held" and "the second close released nothing", which are different failures.
     */
    fun close(): Int {
        if (closed) return 0
        closed = true
        val released = bodies.size
        for (root in roots) HandleTable.release(root)
        // `PyObject.close()` runs the registered cleanup action, which every platform's cleaner
        // performs at most once -- so the decref here cannot be doubled by a later collection of the
        // same wrapper.
        for (body in bodies) body.close()
        roots.clear()
        bodies.clear()
        // Cleared last and unconditionally: a key left behind would name a released root, and
        // `reuse` would hand it out. The `closed` guard in `reuse` covers the same case twice on
        // purpose -- one of them is a policy and the other is the thing that makes it true.
        interned.clear()
        return released
    }
}

/**
 * One Python callable, and the invocation convention its slot implied.
 *
 * Kept separate from the erased lambda [erasedAs] produces so that the state -- the scope, the
 * `PyObject`, whether a composer is threaded -- exists once regardless of arity, and the per-arity
 * part is a lambda with nothing in it but a call.
 */
internal class PythonFunction(
    private val scope: PythonCallableScope,
    internal val body: PyObject,
    private val composable: Boolean,
    /** One [TypeTag] name per forwarded argument, in order. See [marshalArgument]. */
    private val argTags: List<String>,
) {

    /**
     * This callable as a `kotlin.FunctionN` of [jvmArity].
     *
     * A `when` over the arities rather than one generic shape, because there is no generic shape: a
     * JVM `Function3` is a different interface from a `Function2`, and the thunk's `CHECKCAST` names
     * one of them. The ceiling is where measurement stops rather than where the technique does --
     * `androidx.compose.foundation.layout` and `material3` declare no lowered content lambda wider
     * than this, and a wider one refuses by name instead of being cast to the wrong interface.
     */
    fun erasedAs(jvmArity: Int): Any {
        // One `return` per arity rather than a `when` expression: the branches of a `when` are
        // unified against each other, and `(Any?) -> Unit` and `(Any?, Any?) -> Unit` are unrelated
        // types. Each `return` is checked against `Any` on its own instead.
        when (jvmArity) {
            0 -> return fun() { invoke(emptyArray()) }
            1 -> return fun(a: Any?) { invoke(arrayOf(a)) }
            2 -> return fun(a: Any?, b: Any?) { invoke(arrayOf(a, b)) }
            3 -> return fun(a: Any?, b: Any?, c: Any?) { invoke(arrayOf(a, b, c)) }
            4 -> return fun(a: Any?, b: Any?, c: Any?, d: Any?) { invoke(arrayOf(a, b, c, d)) }
            5 -> return fun(a: Any?, b: Any?, c: Any?, d: Any?, e: Any?) { invoke(arrayOf(a, b, c, d, e)) }
        }
        throw IllegalArgumentException(
            "a Kotlin function type of arity $jvmArity is not bound: nothing measured declares one, " +
                "and guessing at the interface would be a ClassCastException at the call site",
        )
    }

    private fun invoke(args: Array<Any?>) {
        check(!scope.isClosed) {
            "this Python callable was released with its composition and cannot be invoked: the " +
                "scope that held it is closed"
        }
        PythonCallables.withScope(scope) {
            // The composer Compose established for *this* invocation, which is not necessarily the
            // one that was ambient when the lambda crossed. `$changed` -- the last argument -- is
            // the caller's claim about argument staticness and is not Python's to read. Everything
            // before those two is the declaration's own, and is Python's.
            val composerRoot = if (!composable) null else HandleTable.register(
                args[args.size - 2]
                    ?: throw IllegalStateException("a composable lambda was invoked with a null \$composer"),
            )
            try {
                // Built before the call and closed after it, all of them, so that a failure part way
                // through the marshalling does not leave the earlier ones to a collector that may
                // not exist on this target.
                val marshalled = ArrayList<PyObject>(argTags.size + 1)
                try {
                    // The push and the pop are Python's, in `pythonx._callable_thunk`: doing it here
                    // would mean two more boundary crossings per invocation to reach `push_composer`
                    // and `pop_composer`, and the `finally` that guarantees the pop belongs next to
                    // the call it guards.
                    if (composerRoot != null) marshalled += PyInt.from(composerRoot.raw)
                    for (i in argTags.indices) marshalled += marshalAn(argTags[i], args[i])
                    body(*marshalled.toTypedArray()).close()
                } finally {
                    for (value in marshalled) value.close()
                }
            } finally {
                if (composerRoot != null) HandleTable.release(composerRoot)
            }
        }
    }

    /**
     * One Kotlin argument on its way into the Python callable.
     *
     * ### Why the tag comes from the walker rather than from the value
     *
     * A `when (value) { is Float -> ...; is String -> ... }` would need no tag at all and is wrong,
     * because **the Python side has to make the same decision independently** -- an OBJECT arrives as
     * a handle integer and has to be given a proxy of the declared type, and a `kotlin.Int` arrives
     * as an integer that must stay one. Deciding from the runtime type here and from the declared
     * name there is two rules that can disagree, and the case where they do is silent: a slot
     * declared `kotlin.Number` holding an `Int` would be marshalled as a number and then wrapped as
     * a proxy over the "handle" 5. Both sides therefore read the *same* list, which
     * [PythonCallables.newFunction] checks the length of.
     *
     * Nullability is not in the tag and does not need to be: `null` is `None` whatever the slot
     * said, which is the rule `UpcallTrampoline.toKotlin` already applies in the other direction.
     */
    private fun marshalAn(tag: String, value: Any?): PyObject {
        // A reference of this frame's own rather than `PyNone.get()` itself: that is a cached
        // singleton *wrapper*, and the caller closes everything it is given -- closing it would
        // release the one every later `get()` hands back. `borrowed = true` is what makes the
        // constructor take a reference instead of adopting one it was not given.
        if (value == null) return PyObject(PyNone.get().pointer, borrowed = true)
        return when (tag) {
            "INT" -> PyInt.from((value as Number).toLong())
            "FLOAT" -> PyFloat.from((value as Number).toDouble())
            "BOOLEAN" -> PyBool.from(value as Boolean)
            "STRING" -> PyString.from(value as String)
            // Rooted, and **not** released here. The proxy `pythonx._adapt_arguments` builds over
            // this handle is what gives it back, through the `__del__` every `_proxy_type` carries
            // -- which is `HandleTable`'s stated contract for a handle that reaches Python, and the
            // reason the thunk wraps every forwarded argument before it drops the ones the callable
            // did not ask for. Releasing it here as well would be the double release `agent-rules`
            // §14 is about; releasing it here *instead* would hand Python a stale handle for the
            // duration of a callback that is allowed to keep it.
            "OBJECT" -> PyInt.from(HandleTable.register(value).raw)
            else -> throw IllegalArgumentException(
                "a Kotlin function type argument tagged '$tag' has no marshalling rule",
            )
        }
    }
}
