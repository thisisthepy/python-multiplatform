package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.ProxyTypeFactory
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.ReflectedClassKind
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable

/**
 * The Python half of `docs/upcall-async-design.md` §8.6's second gap: the `async def` proxy that
 * makes `await kotlin_fn(x)` read the same whether the Kotlin body suspended or not.
 *
 * Until this existed, `AsyncUpcallDeliveryTest` wrote that proxy by hand (`_await_kotlin`) and the
 * design doc said so. The shape is not optional and not a convenience: `AsyncUpcall.deliver` returns
 * **two different Python types for one Kotlin declaration** -- the real value when the coroutine had
 * already finished, an `asyncio.Future` when it had not -- because the fast path must not be made to
 * pay for an event loop it does not need. Something has to reconcile that, and an `async def` that
 * awaits its result only when the result is awaitable is the only place it can be done without
 * giving the fast path a `Future` too.
 *
 * ### Where this is generated, and why not at build time
 *
 * The two candidates were a `.py` file emitted by KSP and shipped as a resource, and Python source
 * rendered at run time and `exec`'d. This is the second, for three reasons:
 *
 * 1. **There is no resource path to put a `.py` on that works everywhere.** Kotlin/Native has no
 *    `getResourceAsStream`, and the interpreter's `sys.path` on iOS, androidNative and wasm points
 *    at the per-platform CPython trees under `src/nativeInterop/cinterop/lib/...`. `Python3.exec`
 *    already works on all five.
 *
 *    This reason used to end "...is a per-platform packaging problem this repository has not solved
 *    for anything", and that half is no longer true.
 *    [python.multiplatform.env.PythonPayload] puts a directory on `sys.path` before the first
 *    import, and desktop and Android have a real packaging step feeding it -- a jar resource that
 *    is extracted, an APK asset that is unpacked. What still holds is the part this reason rests
 *    on: there is no such step on **iOS** (it needs an Xcode build phase nobody has written),
 *    androidNative or wasm, so a file shipped that way would exist on two platforms out of five.
 *    Reason 2 below would hold even if all five had one.
 * 2. **KSP does not know what will be installed.** A fragment is per Kotlin module; the table is
 *    assembled at run time by `UpcallTable.install`, and an application may install a subset (every
 *    test in this repository does). Build-time Python would describe a table that may never exist,
 *    and would have to be re-aggregated across klibs at run time anyway -- which is this, with less
 *    information.
 * 3. **`ExposedCallable` already carries everything.** [ExposedCallable.isSuspend],
 *    [ExposedCallable.arity] and [ExposedCallable.kind] are exactly the inputs, and they are on the
 *    entry KSP already emits. Emitting Python from KSP as well would duplicate that knowledge into a
 *    second generator that can disagree with the first.
 *
 * What is kept from the build-time option is the part that actually mattered: [render] is a **pure
 * function from entries to source text**, so it is pinned by `PythonProxySourceTest` in `commonTest`
 * with no interpreter anywhere, and the generated module is a readable artefact rather than a
 * behaviour. [install] is the only part that needs CPython, and it does nothing but `exec` what
 * [render] returned.
 *
 * ### GraalVM native image
 *
 * `CLAUDE.md` requires upcalls to survive a closed-world native image, which is why §7 chose
 * build-time tables over run-time reflection. Nothing here reintroduces that risk: this generates a
 * *Python* string from data that is already in a statically-emitted Kotlin table. There is no
 * reflection, no class loading and no dynamic Kotlin code -- the closed world stays closed.
 * (Unverified here: no native image was built for this change.)
 *
 * ### Instance surfaces: [CallableKind.METHOD], [CallableKind.GETTER], [CallableKind.SETTER]
 *
 * These need a receiver, which a bare module function has nowhere to put. [render] takes the
 * matching [ReflectedClass] descriptors and, for each one, emits a plain Python `class` whose
 * `__init__` (if a `CONSTRUCTOR` entry exists) calls through to Kotlin and stashes the resulting
 * handle in `self._pm_handle`, and whose methods and properties pass that handle as `args[0]` --
 * exactly the shape `pm_invoke` already expects for a receiver-carrying entry.
 *
 * #### Who gives the handle back
 *
 * A [python.multiplatform.reflection.HandleTable] entry is a **strong Kotlin reference that the
 * Python side owns**, and that table's own class doc names the counterpart precisely: *"the proxy's
 * `tp_dealloc` calling `release` is the entire lifetime contract."* Until [renderClass] emitted a
 * `__del__`, nothing kept it. `GeneratedProxyCostTest` measured the consequence without looking for
 * it -- its constructor row builds ten thousand `Counter(10)`s and discards every one, and the run
 * ended with **78 002 live handles**, i.e. every Kotlin object Python had finished with was still
 * rooted. `ProxyHandleLifetimeTest` is the direct statement of it, and it fails on desktop *and* on
 * the iOS simulator identically (64 registered, 64 still live after `gc.collect()`): this was never
 * a per-platform problem, because the generated Python is `commonMain`'s.
 *
 * `__del__` is `tp_finalize`, which `tp_dealloc` runs -- so it is literally the mechanism that doc
 * names, not a stand-in for it. The objection to it is historical: before PEP 442 (CPython 3.4) an
 * object with `__del__` in a reference cycle was never finalised at all and went to `gc.garbage`.
 * That has not been true for a decade, and [ProxyHandleLifetimeTest] pins it rather than trusting
 * it -- it builds a cycle through the proxy on purpose, drops it, collects, and requires the
 * handles back.
 *
 * The alternatives are all *more* robust in one specific way and cost between two and nine times
 * as much, measured on CPython 3.13 as construct-plus-destruct, net of a plain object of the same
 * shape:
 *
 * | alternative | net | why not |
 * |---|---|---|
 * | `__del__` (**this**) | **+66 ns** | -- |
 * | a per-instance holder object whose own `__del__` releases | +158 ns | immune to a subclass shadowing `__del__`, at the price of a second allocation per proxy and a second name in the instance dict |
 * | `weakref.ref(self, cb)` in a module-level registry | +260 ns | the same immunity, plus a registry entry to insert and remove per proxy |
 * | `weakref.finalize(self, _pm_release, h)` | +555 ns | the same again, and it costs about as much as the entire boundary crossing it is protecting -- the constructor row is ~610 ns net, so this alone would roughly double it |
 *
 * The one thing `__del__` does not survive is a **subclass that defines its own `__del__` and does
 * not call `super().__del__()`** -- measured, not assumed: the handle is silently never released.
 * That is accepted because a subclass that overrides `__init__` without calling `super().__init__()`
 * already breaks the same object more thoroughly (there is no `_pm_handle` at all), so the generated
 * class already depends on a subclass cooperating with it, and paying 2.4x on every construction to
 * harden one half of that dependency buys little.
 *
 * The release is looked up **once, at class-definition time**, and carried as a default argument;
 * see `_pm_releaser` for why reading it out of globals inside `__del__` is not the same thing.
 *
 * This deliberately does **not** go through [python.multiplatform.ffi.ProxyTypeFactory]'s
 * `PyType_FromSpec` type. That type exists for one thing: giving a Kotlin-held Python reference a
 * `tp_traverse`/`tp_clear`/`tp_dealloc` slot so CPython's cyclic collector can see through it
 * (`CycleCollectionTest`). It carries no `tp_call`, `tp_methods` or `tp_getset` -- nothing that
 * would let Python *call into* Kotlin -- and nothing in this codebase wires a `TypeTag.OBJECT`
 * return into an instance of it; [UpcallTrampoline.marshalResult] hands back a bare
 * [python.multiplatform.reflection.HandleTable] integer today. A receiver in this design is
 * therefore just that integer, and a plain Python class holding one in an attribute is sufficient
 * -- no C type, no new slot, no new platform code.
 *
 * ### Static surfaces: [CallableKind.STATIC_GETTER], [CallableKind.STATIC_SETTER], and a
 * companion's [CallableKind.FUNCTION]
 *
 * A static property is an **attribute**, not a callable: rendering it as `libraryVersion()` would
 * make the Python surface disagree with the Kotlin declaration, and rendering it as a plain
 * assignment at install time would freeze a `var` at whatever it held then. So it has to be a
 * descriptor, and the only question is what object carries it. There are two answers because there
 * are two owners:
 *
 * - **A static member of a class that gets a Python `class`** (a `companion object` member, which
 *   `FragmentScanner` folds into the owner's name) goes on a **metaclass** rendered beside the
 *   class. A `property` in the class body would answer `instance.x`, not `Foo.x`; a descriptor
 *   answers for the object whose *type* carries it, so for the class object itself to read and
 *   write, the descriptor must live on the class's type. This also reproduces Kotlin's own rule
 *   for free -- a companion member is reached through the class and **not** through an instance,
 *   and a metaclass attribute is invisible to instances for exactly the same reason.
 * - **Everything else static** -- a top-level property, and the members of a `ReflectedClassKind`
 *   this stage does not render as a class ([ReflectedClassKind.OBJECT], [ReflectedClassKind.ENUM])
 *   -- becomes a live attribute of the module its name points at. That is not a second design so
 *   much as the same one applied to the module: the module object is reclassed onto a `ModuleType`
 *   subclass generated **for that module alone**, and the descriptor goes on it, exactly as a
 *   class's static goes on that class's metaclass. Per module rather than one shared subclass
 *   because the descriptor is named after the Kotlin declaration, and a shared type would answer
 *   `demo.calc.tally` on every other proxy module too.
 *
 *   This was a `__getattr__`/`__setattr__` pair on one shared `_PmModule` subclass first, which is
 *   correct and is roughly fifty times more expensive per read. `__getattr__` is the *fallback*
 *   hook: it runs only once normal lookup has failed, and on a module "failed" means
 *   `module_getattro` has already built and raised the formatted "module has no attribute"
 *   `AttributeError` for the hook to catch and discard. `GeneratedProxyCostTest` priced that at
 *   551-587 ns per read on desktop, net of the boundary call underneath it -- against 6-14 ns for
 *   the descriptor, and against 8-28 ns for the metaclass path doing the same job for a class.
 *   A data descriptor is consulted ahead of the instance dict in both directions, so it also
 *   removes the need for a `__setattr__` hook: a `property` with no usable `fset` refuses the
 *   assignment on its own.
 *
 * Putting an `object`'s properties on the module rather than on a class of its own is what keeps
 * `Registry.ping()` and `Registry.size` resolving against the same Python object: `ping` is a
 * [CallableKind.FUNCTION] entry named `..Registry.ping` and has always gone through the module
 * path, so its sibling property has to follow it there.
 *
 * #### A companion **function** goes on the metaclass too
 *
 * `FragmentScanner.companionEntries` emits a companion function as a [CallableKind.FUNCTION] named
 * `pkg.Owner.fn` -- the same shape an `object`'s function has, and indistinguishable from it by
 * name alone. Sent down the module path it landed in a module `pkg.Owner`, which [renderClass] then
 * overwrote on the parent module with the class of that name. **Observed, in a real interpreter:**
 * `Counter.make(3)` raised `type object 'Counter' has no attribute 'make'` while a
 * `sys.modules['proxycls.Counter']` that still held `make` was left behind -- one Kotlin name with
 * two contradictory Python answers, only one of which anything pointed at.
 *
 * The judgement is that **one Python name cannot be both a module and a class**, so one of the two
 * has to move, and it is the function: the class is the thing an instance surface needs. That puts
 * it on the metaclass beside the static properties, which also gets Kotlin's own rule for free.
 * Rejected:
 *
 * | alternative | why not |
 * |---|---|
 * | a `staticmethod`/`classmethod` in the **class body** | reachable through an *instance* (`Counter(1).make(3)` resolves), which is a shape Kotlin does not have -- and it would contradict the sibling static *property*, which is on the metaclass precisely so instances cannot see it. The two halves of one companion would then disagree about what a companion is |
 * | merging the module into the class (the class absorbs the module's attributes) | the collision is only ever between a class and a module named after that class, i.e. between a class and its own companion. Building a general module/class merge for one case buys nothing the metaclass does not, and gives `pkg.Owner` a `__getattr__` that has to arbitrate between two namespaces |
 * | reversing the publication order so the module wins | the same bug pointed the other way: `Counter(1)` would stop working, and an instance surface is what the class rendering exists for. It also leaves the loser in `sys.modules`/on the parent module rather than removing it |
 *
 * An `object`'s function is deliberately **not** moved: a [ReflectedClassKind.OBJECT] is not
 * rendered as a Python class, so nothing overwrites its module and `Registry.ping()` and
 * `Registry.size` keep resolving against the same module object.
 *
 * #### What this cost, and the alternatives it was chosen over
 *
 * The price is one extra type object per class that has static members, and one more shape in the
 * generated source for a reader to account for. It is paid only where there is something to put on
 * it -- a class with no statics still renders as a bare `class Foo:`. The alternatives:
 *
 * | alternative | why not |
 * |---|---|
 * | accessor pair (`Foo.get_count()` / `Foo.set_count(v)`) | no metaclass, but it is the same disagreement between the Python surface and the Kotlin declaration that ruled out rendering the property as a callable in the first place. Rejecting it for a `val` and accepting it for a companion `val` would be arbitrary |
 * | module-level accessors (`fixture.library.WithCompanion_count`) | mangles the name, and `WithCompanion` is already taken as a module by the companion's *functions*, so the two halves of one companion would live in different places |
 * | a C type with `tp_getset` via [python.multiplatform.ffi.ProxyTypeFactory] | new platform C on five targets, for something a pure-Python descriptor already does. The same argument the instance section above makes against that type |
 *
 * The setter half follows `docs/binding-policy.md` rather than restating it: a `val`, or a `var`
 * whose setter is `private`/`protected`/`internal`, simply has no `STATIC_SETTER` entry, so this
 * renders a `property` with no `fset` on a class, and on a module one whose `fset` raises with the
 * Kotlin declaration's name in the message. The assignment fails with `AttributeError` instead of
 * silently binding a plain attribute that would shadow the Kotlin declaration for every later read.
 *
 * ### A `TypeTag.OBJECT` **result**, and who owns it
 *
 * This used to be the whole of the section below: a `TypeTag.OBJECT` value returned from an
 * ordinary [CallableKind.FUNCTION] crossed as the bare handle integer, and *"that int is the
 * caller's to release"*, because an integer has nothing to hang a `__del__` off. The path that made
 * the cost of it visible is `docs/kotlin-extensions-in-python.md` §3.2's Compose chain --
 * `Modifier.padding(16.dp).size(24.dp)`, assembled from Python out of Compose's own jars, where
 * every link is exactly that shape. §6 records the consequence: **one leaked root per intermediate
 * link**, and Compose modifiers are written long, so the leak grows with how idiomatic the calling
 * code is. `WalkedArtifactComposeModifierTest` leaked three per run and said so in its own KDoc.
 *
 * What that needed is a *Python object* to hand back instead, since that is the only thing a
 * finaliser can live on. [OWNED_HANDLE] renders it: `_PmObject`, an owner carrying the handle in a
 * slot and giving it back in `__del__`. `_pm_own` puts a result into one and `_pm_unwrap` takes it
 * back out on the way in, so `size(padding(m, 16.0), 24.0)` still passes a handle to Kotlin and
 * chaining costs one `isinstance` per OBJECT argument. Every rendered class derives from the same
 * owner, so there is one notion of "a Python object holding a Kotlin root" rather than two.
 *
 * #### Where a class with a metaclass gets that base from
 *
 * A class with static members already has a metaclass, and the two requirements do not both fit in
 * a base list: Python has no syntax for a base *after* a keyword, so the only spelling available is
 * `class Foo(_PmObject, metaclass=_pm_t_1):` -- which separates the class from the metaclass a
 * reader is pairing it with, and did more than that. `ksp-fixtures:app` locates a companion by
 * matching `class (_pm_t_\d+)\(type\):` lazily through to `class Foo\(metaclass=\1\):`, and once
 * that terminator no longer existed the match could not complete: `java.util.regex`'s lazy loop
 * recurses once per character, so it scanned the rest of a 30 KB module and raised
 * **StackOverflowError** instead of returning no match. Two fixture tests failed with a stack
 * overflow that said nothing about rendering.
 *
 * So the base comes from the metaclass instead -- `__new__ = _pm_owned_new`, one line at the top of
 * the metaclass body, appending `_PmObject` to `bases` unless something there already is one. The
 * class statement stays adjacent to its metaclass and says only what is specific to it, a class
 * with no statics still names its base outright (`class Counter(_PmObject):`), and a Python
 * subclass of a rendered class inherits the metaclass without collecting a duplicate base.
 *
 * **It is gated on the producer having named the return type**, and [ownedTypeOf] is where the
 * reason is written out: `OBJECT` means two different things in the result direction and the tag
 * alone cannot separate them. The walker names its return types; KSP does not, so the KSP path
 * keeps the bare-handle contract exactly as [ProxyHandleLifetimeTest] pins it.
 *
 * ### What is still not rendered
 *
 * | kind | why not |
 * |---|---|
 * | a `TypeTag.OBJECT` result wrapped in the **class rendered for its type** | an owned result is a generic `_PmObject`, not a `Counter`. Nothing would be gained today: the walker emits no `ReflectedClass` at all (`ArtifactScanner` records constructors as needing one "which is the next step"), so no rendered class has ever shared a name with a walked return type. `docs/kotlin-extensions-in-python.md` §4.1's per-receiver proxy is where that belongs, and `_pm_type` carries the Kotlin type name so it has something to key on |
 * | a `TypeTag.OBJECT` result read through a **module attribute** (a top-level or `object` property) | `_pm_static_property` is one shared descriptor for every module attribute and does not see the entry, so owning there means either a `_pm_own` call on the read path of *every* top-level `val` -- a row `GeneratedProxyCostTest` measures at 6-14 ns, which this would multiply -- or a second copy of the descriptor. Neither is worth building for a case no producer reaches: KSP emits no return type name, and the walker emits no properties |
 *
 * These are skipped silently *here* because the skip is a property of this stage, not a policy
 * decision -- `docs/binding-policy.md` already decided they are exposed, and they remain reachable
 * through the raw boundary. What is missing is only the sugar.
 */
object PythonProxySource {

    /** The name [AsyncUpcall] resolves out of `__main__` to settle a `Future` safely. */
    internal const val SETTLE_FUNCTION = "_pm_settle"

    /** The name [AsyncUpcall] resolves out of `__main__` to arm a `Future`'s cancellation notice. */
    internal const val WATCH_FUNCTION = "_pm_watch"

    /** The `(long) -> int` entry point `_pm_watch` routes a cancellation to; per-platform. */
    internal const val CANCEL_ENTRY_POINT = "_pm_cancel"

    /** The `(long) -> int` entry point that gives a [python.multiplatform.reflection.HandleTable]
     * handle back; already bound by every target's bootstrap, and reused here rather than
     * duplicated -- the handle `_pm_watch` carries is an ordinary object handle. */
    internal const val RELEASE_ENTRY_POINT = "_pm_release"

    /** Where names with no package of their own land; `docs/upcall-table-design.md` §Runtime. */
    const val DEFAULT_ROOT_MODULE: String = "kotlin"

    /**
     * The part that does not depend on any table: the guarded settle, the module-tree builder, and
     * name resolution.
     *
     * Installed on its own by [ensureSupportInstalled] because [AsyncUpcall] needs `_pm_settle`
     * whether or not anybody ever generated a proxy -- the delivery path exists independently of the
     * sugar on top of it.
     */
    val support: String = """
        # GENERATED by python-multiplatform. Do not edit.
        import sys as _pm_sys
        import types as _pm_types


        def _pm_settle(_fut, _ok, _payload):
            # Why this exists rather than scheduling `fut.set_result` directly: the Future can be
            # cancelled at any point, including between the moment Kotlin checks it and the moment
            # this callback runs, and `set_result` on a settled Future raises InvalidStateError.
            # Raised inside a loop callback that goes to `call_exception_handler`, which logs a
            # failure the application never asked for and cannot act on -- the value it describes
            # was abandoned on purpose. Measured before this guard existed:
            # AsyncUpcallCancellationTest saw exactly one 'InvalidStateError: invalid state'.
            if _fut.done():
                return
            if _ok:
                _fut.set_result(_payload)
            else:
                _fut.set_exception(_payload)


        def _pm_watch(_fut, _handle):
            # Early cancellation notice, and the only scheduled release of the handle that carries
            # it. Both halves are one callback because `add_done_callback` fires on *every* way a
            # Future can settle -- cancelled, resolved, or rejected -- which is exactly the set of
            # moments at which Kotlin either needs to be told something or no longer needs the
            # handle.
            #
            # Without this, the only point at which Kotlin could observe a cancellation was when its
            # coroutine finished and the delivery found the Future already settled: correct, and far
            # too late for a body that was still checking `ensureActive()` in a loop.
            #
            # Scheduled, not immediate: `Future.cancel()` settles the Future synchronously but hands
            # its callbacks to `call_soon`, so the notice arrives on the loop's next turn. That is
            # still arbitrarily earlier than completion, which is the whole claim.
            def _pm_done(_f, _h=_handle):
                try:
                    if _f.cancelled():
                        _pm_cancel(_h)
                finally:
                    # Unconditional, and safe to be beaten to it: a handle table release is
                    # generational, so a second one -- from Kotlin's own completion path -- is a
                    # no-op rather than a slot handed to somebody else.
                    _pm_release(_h)
            _fut.add_done_callback(_pm_done)


        def _pm_module(_name):
            # Exposed names are Kotlin fully-qualified names, so `fixture.library.greet` should be
            # reachable as `from fixture.library import greet`. Injecting into sys.modules is what
            # makes that work with no import hook: CPython's import machinery returns a sys.modules
            # hit for the full dotted name before it looks at any finder.
            _m = _pm_sys.modules.get(_name)
            if _m is None:
                _m = _pm_types.ModuleType(_name)
                _pm_sys.modules[_name] = _m
                if '.' in _name:
                    _parent, _, _leaf = _name.rpartition('.')
                    setattr(_pm_module(_parent), _leaf, _m)
            return _m


        def _pm_lookup(_name):
            # NOT `_pm_bind`, which is what this used to be called. Three of the five bootstraps
            # publish a `_pm_bind` of their own meaning handle -> callable, and defining this over
            # the top of it destroyed the host's on the way past -- silently, because desktop is
            # the only target that had ever run this file and desktop publishes no `_pm_bind`.
            #
            # `bytes`, not `str`: desktop reaches its resolver through
            # `ctypes.CFUNCTYPE(c_long, c_char_p)` and `c_char_p` refuses a `str` outright, so this
            # is the only spelling that can work everywhere. Every other bootstrap accepts both.
            _h = _pm_resolve(_name.encode('utf-8'))
            if _h == -1:
                raise AttributeError('no exposed Kotlin declaration named ' + _name)
            return _h


        def _pm_no_release(_handle):
            # What a host that bound no `_pm_release` gets: the behaviour that existed before a
            # proxy released anything at all -- a leak -- rather than a NameError raised inside
            # `__del__`, which CPython prints to stderr as "Exception ignored" and which nothing
            # can act on. Same policy as `_pm_watch`'s: a missing per-platform binding costs the
            # feature it enables and nothing else.
            return 0


        def _pm_releaser():
            # The host's `(long) -> int` release, resolved **once**, at class-definition time, and
            # carried into `__del__` as a default argument. Two reasons, and it is the second that
            # forces it:
            #
            # 1. A default argument is a LOAD_FAST, not a LOAD_GLOBAL, on a path that runs once per
            #    proxy that dies.
            # 2. A module's globals are set to None during interpreter finalisation, and `__del__`
            #    still runs after that for everything still alive. A `_pm_release` read out of
            #    globals at that moment is None; the call raises TypeError, and CPython prints
            #    "Exception ignored in: <function __del__>" once per surviving proxy, to stderr,
            #    where nobody asked for it. A captured reference cannot be unbound underneath it.
            return globals().get('_pm_release', _pm_no_release)


        def _pm_module_type(_mod):
            # The type a module's Kotlin-backed attributes hang off, created once per module.
            #
            # A Kotlin top-level `val`/`var` is a module *attribute* in Python, and it has to stay
            # live in both directions: a read has to call Kotlin's getter (a plain assignment at
            # install time would freeze a `var` at whatever it held then), and an assignment has to
            # reach Kotlin's setter rather than rebinding the name in the module dict. Attribute
            # hooks are looked up on the type and never on the instance, so whatever answers has to
            # live on a type and the module object has to be reclassed onto it. Assigning
            # `__class__` is the documented way to do that to a module and is what PEP 562's own
            # rationale describes.
            #
            # Per module, not one shared subclass, because what gets put on it below is a
            # descriptor *named after the Kotlin declaration* -- on a shared type, `demo.calc.tally`
            # would answer on every other proxy module too. This is the same price the metaclass
            # already pays for a class's static members (one extra type object, and only where
            # there is something to put on it), and it buys the same thing.
            _t = type(_mod)
            if getattr(_t, '_pm_owned', False):
                return _t
            _t = type(
                '_PmModule_' + _mod.__name__.replace('.', '_'),
                (_pm_types.ModuleType,),
                {'_pm_owned': True},
            )
            _mod.__class__ = _t
            return _t


        def _pm_static_property(_mod, _name, _get, _set):
            # A `property` -- a *data* descriptor -- rather than a `__getattr__`/`__setattr__` pair
            # on a shared subclass, which is what this used to be. Both are correct; they are not
            # close on cost, because of where CPython looks first.
            #
            # `__getattr__` is the *fallback* hook: it is consulted only after normal attribute
            # lookup has already failed, and on a module "already failed" means `module_getattro`
            # has run to completion and raised -- building the fully formatted "module 'x' has no
            # attribute 'y'" AttributeError, `__spec__` inspection and all -- for the hook to catch
            # and discard. Every single read pays for one raised-and-thrown-away exception. A data
            # descriptor is consulted *first* instead, ahead of the instance dict, in both
            # directions, so nothing is ever raised and no hook is ever entered.
            #
            # Measured, on this repository's `GeneratedProxyCostTest` (desktop, net of the raw
            # boundary the read wraps): 551-587 ns per read through the `__getattr__` hook against
            # 6-14 ns through the descriptor. PEP 562's module-dict `__getattr__` was measured too
            # and is a middle answer, not this one -- it skips the exception but still runs the
            # generic-lookup miss and a Python-level dispatch (214 ns against 569 ns and 53 ns in a
            # standalone probe of the three shapes).
            #
            # Everything that is *not* a Kotlin declaration -- the rendered functions and classes,
            # and anything the application assigns later -- is untouched by this: it has no
            # descriptor of its own, so it goes through the ordinary instance-dict path exactly as
            # it did on a plain module.
            _t = _pm_module_type(_mod)

            def _pm_fget(_self, _h=_get):
                return _pm_invoke(_h, ())

            if _set is None:
                # `docs/binding-policy.md`: a `val`, or a `var` whose setter is not public API, has
                # no setter entry. The assignment has to fail -- letting it through would bind a
                # plain module attribute that shadows the Kotlin declaration for every later read,
                # silently and only in Python. A `property` with no `fset` already raises
                # AttributeError, but with a message about a Python property; this says which
                # Kotlin declaration it is, which is the whole of what the reader needs.
                def _pm_no_setter(_self, _v, _n=_name):
                    raise AttributeError(
                        'the Kotlin declaration behind ' + _self.__name__ + '.' + _n +
                        ' has no exposed setter'
                    )

                setattr(_t, _name, property(_pm_fget, _pm_no_setter))
                return

            def _pm_fset(_self, _v, _h=_set):
                _pm_invoke(_h, (_v,))

            setattr(_t, _name, property(_pm_fget, _pm_fset))
    """.trimIndent()

    /**
     * Renders the proxy module for [entries] and [classes].
     *
     * @param entries what the table holds; [UpcallTable.entries] is the usual source.
     * @param classes the class descriptors whose [CallableKind.METHOD]/[CallableKind.GETTER]/
     *   [CallableKind.SETTER]/[CallableKind.CONSTRUCTOR] entries in [entries] should get a Python
     *   class rather than being left unreachable; [ClassLookup.all] is the usual source. Only
     *   [ReflectedClassKind.CLASS] and [ReflectedClassKind.INTERFACE] are rendered -- an `object`'s
     *   members are already [CallableKind.FUNCTION]/[CallableKind.STATIC_GETTER] under the
     *   object's name (`FragmentScanner`'s `staticFunctionEntry`), so they go through the ordinary
     *   function and module-attribute paths with no receiver, and an `ENUM`'s instances come from
     *   [CallableKind.STATIC_GETTER] entries that land as module attributes for the same reason.
     *   Passing a class here also *claims* its [ReflectedClass.memberNames]: a static member of a
     *   rendered class goes on that class's metaclass instead of onto a module.
     * @param rootModule where a name with no dot in it goes. Kotlin's default package produces
     *   such names, and they have nowhere else to live.
     * @return Python source. Deterministic, and safe to `exec` more than once -- every statement is
     *   an assignment, a `def`, or a `class`.
     */
    fun render(
        entries: List<ExposedCallable>,
        classes: List<ReflectedClass> = emptyList(),
        rootModule: String = DEFAULT_ROOT_MODULE,
    ): String {
        val byName = entries.associateBy { it.name }
        val renderableClasses = classes.filter {
            it.kind == ReflectedClassKind.CLASS || it.kind == ReflectedClassKind.INTERFACE
        }
        // A static member of a class that gets a Python `class` of its own belongs on that class's
        // metaclass, not on a module named after the class -- the two would collide, since
        // `renderClass` publishes the class under exactly that name. Everything else static (a
        // top-level property or function, and an `object`'s or an `enum`'s, neither of which is
        // rendered as a Python class) is a module attribute, which is where its siblings went.
        val claimedByClasses = renderableClasses.flatMapTo(HashSet()) { it.memberNames }
        // A companion function is a FUNCTION named `pkg.Owner.fn`, so the module path below would
        // publish it into a *module* `pkg.Owner` -- which `renderClass` then overwrites with the
        // class of that name. Observed, not inferred: `Counter.make` raised
        // "type object 'Counter' has no attribute 'make'" while a half-populated
        // `sys.modules['proxycls.Counter']` held it, an answer nothing pointed at any more.
        val functions = entries.filter {
            it.kind == CallableKind.FUNCTION && it.name !in claimedByClasses
        }
        val moduleStatics = entries.filter {
            it.kind == CallableKind.STATIC_GETTER && it.name !in claimedByClasses
        }

        return buildString {
            appendLine(support)
            appendLine()
            if (functions.isEmpty() && renderableClasses.isEmpty() && moduleStatics.isEmpty()) {
                // Not an error: a table can legitimately hold nothing this stage can render (only
                // instance members with no class descriptor, say). Saying so in the generated
                // source beats emitting an empty file that reads like a generator failure.
                appendLine("# no CallableKind.FUNCTION entries or proxy classes to render")
                return@buildString
            }
            appendLine(ENTRY_POINT_GUARD)
            appendLine()
            appendLine(OWNED_HANDLE)
            appendLine()
            var index = 0
            functions.forEach { entry ->
                appendLine(renderOne(index, entry, rootModule))
                index++
            }
            moduleStatics.forEach { getter ->
                val (source, nextIndex) = renderModuleStatic(index, getter, byName, rootModule)
                appendLine(source)
                index = nextIndex
            }
            renderableClasses.forEach { cls ->
                val (source, nextIndex) = renderClass(index, cls, byName, rootModule)
                appendLine(source)
                index = nextIndex
            }
        }
    }

    /**
     * Renders the proxies for whatever [UpcallTable] and [ClassLookup] currently hold and `exec`s
     * them.
     *
     * ### What the caller owes, exactly
     *
     * Two names in `__main__`, and nothing else:
     *
     * | name | shape |
     * |---|---|
     * | `_pm_resolve` | `(name: bytes) -> handle`, `-1` for a name nothing claims |
     * | `_pm_invoke` | `(handle, args: tuple) -> result` |
     *
     * `bytes` rather than `str` because desktop reaches its resolver through
     * `ctypes.CFUNCTYPE(c_long, c_char_p)` and `c_char_p` refuses a `str` outright, so it is the
     * only spelling that can be written once and work everywhere; the `PyMethodDef` bootstraps
     * accept either. How the two names get there is per-platform and is the one part of this path
     * that is not `commonMain` (desktop through `ctypes` over a Panama upcall stub; iOS and
     * androidNative through `UpcallEntry.publish`'s `PyMethodDef`s; Android/ART through the same
     * with C shims behind `ml_meth`; wasmJs through five `PyCFunction`s over one `@WasmExport`,
     * told apart by an op code in `self`). The generated source checks for them and raises rather
     * than defining proxies that would fail one by one at call time.
     *
     * Everything the generated module defines for its own use is prefixed `_pm_` too, which used
     * to include a `_pm_bind` that meant *name -> handle* -- landing straight on top of the
     * host's `_pm_bind`, which means *handle -> callable*. It is `_pm_lookup` now; nothing this
     * renders writes to a name a bootstrap owns.
     *
     * @return the source that was executed, so a caller can log or inspect exactly what ran.
     */
    fun install(rootModule: String = DEFAULT_ROOT_MODULE): String {
        // Before the source that reads `_pm_proxy_base` out of `globals()` -- see [OWNED_HANDLE].
        // A no-op false on every platform that has not wired a handle slot yet; see
        // [ProxyTypeFactory.installGcBase]'s own doc for which one that is today.
        ProxyTypeFactory.installGcBase()
        val source = render(UpcallTable.entries(), ClassLookup.all(), rootModule)
        Python3.exec(source)
        return source
    }

    /**
     * Makes sure `_pm_settle` exists and hands it back.
     *
     * Looked up rather than cached in a Kotlin field on purpose: a `PyObject` held across an
     * interpreter that gets finalized and re-initialised is a dangling pointer, and this
     * repository's test fixture finalizes between tests. The lookup is a `sys.modules` hit plus one
     * attribute read, on the slow path only -- a call that genuinely suspended is not counting
     * attribute lookups, which is the same argument [AsyncUpcall.runningLoop] makes for itself.
     */
    internal fun settleFunction(): PyObject {
        val main = Python3.import("__main__")
        main.getAttrOrNull(SETTLE_FUNCTION)?.let { return it }
        Python3.exec(support)
        return main.getAttr(SETTLE_FUNCTION)
    }

    /**
     * Makes sure `_pm_watch` exists and hands it back, or `null` if arming it would not work.
     *
     * `null` is not a failure. `_pm_watch` calls [CANCEL_ENTRY_POINT] and [RELEASE_ENTRY_POINT],
     * which are per-platform bindings a host installs alongside `_pm_resolve`/`_pm_invoke`; a host
     * that has not installed them gets the behaviour that existed before early notice was possible
     * -- cancellation observed at completion -- rather than a `NameError` raised inside a loop
     * callback, where nobody would see it and nobody could act on it.
     *
     * Both names are checked here rather than inside the Python callback for a second reason: a
     * `_pm_watch` that could not be armed must not be paid for. [AsyncUpcall] registers the handle
     * only when this returns non-null, so a target without the bindings roots nothing and leaks
     * nothing.
     *
     * Looked up rather than cached for the reason [settleFunction] gives: a `PyObject` held across
     * an interpreter that gets finalized and re-initialised is a dangling pointer.
     */
    internal fun watchFunctionOrNull(): PyObject? {
        val main = Python3.import("__main__")
        if (main.getAttrOrNull(CANCEL_ENTRY_POINT) == null) return null
        if (main.getAttrOrNull(RELEASE_ENTRY_POINT) == null) return null
        main.getAttrOrNull(WATCH_FUNCTION)?.let { return it }
        Python3.exec(support)
        return main.getAttrOrNull(WATCH_FUNCTION)
    }

    // ------------------------------------------------------------------------------- rendering

    private val ENTRY_POINT_GUARD = """
        if '_pm_resolve' not in globals() or '_pm_invoke' not in globals():
            raise RuntimeError(
                'the raw upcall entry points are not bound: define _pm_resolve(name_bytes) -> handle '
                'and _pm_invoke(handle, args_tuple) -> result before installing proxies'
            )
    """.trimIndent()

    /**
     * The owner of a [python.multiplatform.reflection.HandleTable] root, and the two functions that
     * put a value into one and take it back out.
     *
     * **Emitted here rather than in [support], and defined at most once.** Here because
     * `_PmObject.__del__` resolves the host's release at class-definition time (see `_pm_releaser`)
     * and [support] is `exec`'d on its own by [settleFunction] on a path that has no bootstrap
     * requirement at all -- a class defined there could capture `_pm_no_release` permanently. After
     * [ENTRY_POINT_GUARD] the bootstrap is known to be present, and every target that publishes
     * `_pm_invoke` publishes `_pm_release` with it.
     *
     * At most once because `install()` is safe to run again and `GeneratedProxyCostTest` really does
     * run it twenty-five times. A bare `class _PmObject:` would build a **new class** on each pass,
     * and every object handed to Python before that point would stop being an instance of the one
     * `_pm_unwrap` tests against: it would cross as a `PyObject`, fail the Kotlin cast, and do so
     * only for the objects that predate the reinstall. The guard is what makes "safe to exec more
     * than once" true of this block as well as of the assignments around it.
     */
    private val OWNED_HANDLE = """
        if '_pm_own' not in globals():

            class _PmObject:
                # The Python object that owns a Kotlin object handle.
                #
                # A `TypeTag.OBJECT` result crosses as a `HandleTable` integer -- Python cannot hold
                # a Kotlin reference on any target -- and an integer has nothing to hang a finaliser
                # off, so every one of them was the caller's to release by hand and nothing ever
                # did. `docs/kotlin-extensions-in-python.md` 6 records what that cost on the path
                # that made it visible: `Modifier.padding(16.dp).size(24.dp)` leaks one root per
                # intermediate link, and Compose modifiers are written long.
                #
                # This is the smallest thing that can own one: an object whose `__del__` gives it
                # back. `__slots__` because there may be one per link of every chain, and because
                # an owner with no `__dict__` cannot itself be part of a reference cycle;
                # `__weakref__` because a caller that wants to observe one dying should be able to.
                __slots__ = ('_pm_handle', '_pm_type', '__weakref__')

                def __init__(self, _pm_h, _pm_t=None):
                    self._pm_handle = _pm_h
                    self._pm_type = _pm_t

                def __repr__(self):
                    return (
                        '<kotlin ' + (getattr(self, '_pm_type', None) or 'object') +
                        ' handle=' + repr(getattr(self, '_pm_handle', None)) + '>'
                    )

                def __del__(self, _pm_r=_pm_releaser()):
                    # getattr, not self._pm_handle: __init__ can raise before the assignment (a
                    # Kotlin constructor that threw), and __del__ runs on the half-built instance
                    # regardless. Cleared *before* the release, so a second __del__ -- an explicit
                    # call, or a resurrection -- cannot hand the same handle back twice. That is
                    # half of the double-release defence; HandleTable's generation tag is the other
                    # half, and it covers the case where somebody else released it first.
                    _pm_h = getattr(self, '_pm_handle', None)
                    if _pm_h is not None:
                        self._pm_handle = None
                        _pm_r(_pm_h)

            def _pm_own(_pm_h, _pm_t=None):
                # `None` is how a nullable Kotlin return arrives -- `marshalResult` answers a null
                # with Python's `None` whatever the tag says -- and there is nothing to own.
                if _pm_h is None:
                    return None
                return _PmObject(_pm_h, _pm_t)

            def _pm_owned_new(_pm_m, _pm_n, _pm_b, _pm_ns, **_pm_kw):
                # How a rendered class that has a **metaclass** becomes an owner. A rendered class
                # is one either way, but Python has no syntax for a base after a keyword, so the
                # only spelling available puts the owner ahead of `metaclass=` in the base list --
                # which separates the class statement from the metaclass a reader, and a consumer's
                # regex, is pairing it with. So the base comes from the only thing that holds the
                # class before it exists: its own metaclass, one line of which says so.
                #
                # Guarded rather than unconditional because the metaclass is inherited: a Python
                # subclass of a rendered class is built through this too, and its bases already
                # carry the owner. Appending a second copy would be a duplicate base and `type`
                # refuses those outright.
                for _pm_x in _pm_b:
                    if isinstance(_pm_x, type) and issubclass(_pm_x, _PmObject):
                        return type.__new__(_pm_m, _pm_n, _pm_b, _pm_ns, **_pm_kw)
                return type.__new__(_pm_m, _pm_n, _pm_b + (_PmObject,), _pm_ns, **_pm_kw)

            # `_pm_proxy_base`, if a bootstrap has set it (see
            # `python.multiplatform.ffi.ProxyTypeFactory.installGcBase`), is a real
            # `Py_TPFLAGS_HAVE_GC` heap type whose `tp_traverse` can reach through a proxy's handle
            # into the Kotlin object's own `PyObject`-typed fields -- which `_PmObject` above cannot
            # do, being a plain Python class with no such slot. `_PmGcObject` is the base
            # [renderClass] uses instead, for exactly the classes [ReflectedClass.hasTraverse] marks
            # and only where nothing else about the class needs a metaclass (see that function).
            #
            # Deliberately NOT `class _PmGcObject(_pm_gc_base, _PmObject):` -- both bases add real
            # instance storage of their own (`_PmObject.__slots__` is non-empty, and the C type
            # appends its own handle slot), and CPython refuses two solid bases at once with
            # "multiple bases have instance lay-out conflict". So a `_PmGcObject` instance is never
            # an `_PmObject`, which is why `_pm_unwrap` below names both: testing only the one meant
            # a proxy of a class with a `PyObject` field could not be passed back to Kotlin as an
            # *owned-result* argument at all, whatever its parameter was declared as.
            #
            # No `__del__`: the base's own `tp_dealloc` already releases the handle (the same one
            # `_PmObject.__del__` calls by hand), and CPython runs it unconditionally for a subclass
            # that defines no finalizer of its own.
            _pm_gc_base = globals().get('_pm_proxy_base')
            if _pm_gc_base is not None:
                class _PmGcObject(_pm_gc_base):
                    __slots__ = ('_pm_type', '__weakref__')

                    def __repr__(self):
                        return (
                            '<kotlin ' + (getattr(self, '_pm_type', None) or 'object') +
                            ' handle=' + repr(getattr(self, '_pm_handle', None)) + '>'
                        )
            else:
                # No bootstrap wired this platform's handle slot yet -- today, every platform but
                # desktop. Falling back to `_PmObject` is exactly what every rendered class already
                # did before this existed, so a class with `hasTraverse` set costs nothing extra here
                # and simply keeps not collecting a Kotlin-side cycle, same as before.
                _PmGcObject = _PmObject

            def _pm_unwrap(_pm_v, _pm_owners=(_PmObject, _PmGcObject)):
                # What an owner is worth on the wire: the handle inside it. Everything else is
                # passed through untouched, which is what keeps a bare handle working for every
                # caller written against the raw contract, and what lets an ordinary Python object
                # reach a `PyObject` parameter.
                #
                # **Whether a call site reaches this at all is decided when it is rendered**, from
                # the parameter's declared Kotlin type, not from the shape of the value: a proxy is
                # a legitimate argument to a `PyObject` parameter *and* to a parameter declared as
                # the Kotlin class it stands for, and only the declaration says which one this is.
                # See `PythonProxySource.argValues`.
                #
                # Both owners, because the decision is no longer made here. `_PmGcObject` is not an
                # `_PmObject` -- CPython refuses two solid bases -- so testing the one name meant
                # every proxy of a class with a `PyObject` field went through untouched whatever its
                # parameter said, which is the same defect from the other end.
                #
                # `isinstance` rather than a `getattr(v, '_pm_handle', v)` probe: the miss is the
                # ordinary Python object, and a missing attribute is a raised-and-discarded
                # AttributeError -- the same shape of cost the module `__getattr__` hook was
                # replaced for, at 551-587 ns a time. The owners are a default argument for the same
                # reason `__del__` binds its releaser that way: one less global lookup per argument.
                if isinstance(_pm_v, _pm_owners):
                    _pm_h = _pm_v._pm_handle
                    if not _pm_h:
                        raise ValueError(
                            'this Kotlin object was already released; its handle cannot be sent '
                            'again, because the slot behind it may belong to something else now'
                        )
                    return _pm_h
                return _pm_v

    """.trimIndent()

    /**
     * A parenthesised Python tuple literal for [elements], with the one-element trailing comma
     * that turns `(a0)` (just `a0`) into an actual tuple -- the trampoline calls `PyTuple_Size` on
     * whatever it is handed, and a bare non-tuple argument fails that call rather than being
     * politely rejected.
     */
    private fun tupleOf(elements: List<String>): String = when (elements.size) {
        0 -> "()"
        1 -> "(${elements[0]},)"
        else -> "(${elements.joinToString(", ")})"
    }

    private fun params(arity: Int): List<String> = (0 until arity).map { "a$it" }

    /**
     * [params], with every [TypeTag.OBJECT] slot that is *carrying a handle* unwrapped back to it.
     *
     * The other half of ownership: wrapping a result is pointless if the wrapper cannot be passed
     * back in, and `size(padding(m, 16.0), 24.0)` does exactly that. Only OBJECT slots are
     * considered -- no other tag can be carrying an owner, and `_pm_unwrap` on a float would be a
     * Python call per argument for nothing.
     *
     * ### Why the tag alone is not enough
     *
     * [TypeTag.OBJECT] is two things at once in the argument direction, exactly as [ownedTypeOf]
     * describes it in the result direction: [UpcallTrampoline]'s `toKotlinObject` reads an `int` as
     * a [python.multiplatform.reflection.HandleTable] handle and reads anything else as the Python
     * object itself, on its way to a parameter declared [python.multiplatform.ffi.PyObject]. So
     * unwrapping *every* OBJECT argument sends the Kotlin object behind a proxy to a parameter that
     * asked for the proxy, and the entry's own `args[i] as PyObject` cast fails on it --
     * `fixture.app.ProxyObjectArgumentTest` observes both halves of that, and `99acd830` filed it.
     *
     * The value cannot decide this, because a proxy is a legitimate argument to either kind of
     * parameter: `_rh.primary = _rh` and `describeHolder(_rh)` pass the same object and want
     * opposite treatment. [ExposedCallable.paramTypeNames] is the declaration, and it is the only
     * thing that distinguishes them.
     *
     * An empty [ExposedCallable.paramTypeNames] is "the producer did not say", not "it is a
     * PyObject": every hand-written fragment in this repository is that shape, and so is any
     * producer not yet taught to fill it. Silence keeps the unwrapping contract that predates the
     * field, which is what `OwnedResultLifetimeTest`'s chain counts.
     *
     * `kotlin.Any` is deliberately **not** treated like `PyObject` here, though [ownedTypeOf]
     * refuses to own a result named that. The two questions differ: a *result* named `Any` is a
     * runtime value that may or may not be a handle and cannot be told apart, whereas an *argument*
     * slot declared `Any` accepts the Kotlin object a proxy stands for, so unwrapping it is well
     * defined rather than a guess.
     */
    private fun argValues(entry: ExposedCallable): List<String> =
        params(entry.arity).mapIndexed { i, name ->
            val declared = entry.paramTypeNames.getOrNull(i)
            if (entry.paramTypes[i] == TypeTag.OBJECT && declared != PY_OBJECT) "_pm_unwrap($name)" else name
        }

    /** The one declared parameter type that means "hand this call the Python object itself". */
    private const val PY_OBJECT = "python.multiplatform.ffi.PyObject"

    /**
     * The Kotlin type name a result should be owned as, or `null` if it must reach Python exactly
     * as the boundary produced it.
     *
     * [TypeTag.OBJECT] is **two things at once** in the result direction, the same way it is in the
     * argument direction: [UpcallTrampoline]'s `fromKotlinObject` answers with a
     * [python.multiplatform.reflection.HandleTable] integer for a Kotlin object and with the Python
     * object itself for a [PyObject]. Python cannot tell those apart when the second one happens to
     * be an `int`, and owning one of those would make `__del__` release a handle nobody issued --
     * which, if it collided with a live slot, is the silent cross-object corruption the generation
     * tag exists to make impossible from the other direction.
     *
     * So the gate is [ExposedCallable.returnTypeName]: the producer naming the Kotlin type is the
     * only signal that says "this really is a handle". The artefact walker supplies it
     * (`WalkedArtifactComposeModifierTest` asserts `androidx.compose.ui.Modifier` on `padding__Dp`);
     * the KSP processor supplies none at all, so every KSP entry keeps the bare-handle contract
     * `ProxyHandleLifetimeTest.testRawHandleIsTheCallersToRelease` pins and nothing on that path
     * moves. Teaching KSP to emit the name is what would extend this to it, and that is the
     * processor's change, not this one's.
     */
    private fun ownedTypeOf(entry: ExposedCallable): String? =
        entry.returnTypeName?.takeIf { entry.returnType == TypeTag.OBJECT && it !in NOT_A_HANDLE }

    /**
     * Return types that are named and still are not handles.
     *
     * A declaration returning [PyObject] hands Python back a Python object, and one returning `Any`
     * may be carrying one. Both are legitimately `int`-valued, which is exactly the collision
     * [ownedTypeOf] refuses to guess about.
     */
    private val NOT_A_HANDLE = setOf(
        PY_OBJECT,
        "kotlin.Any",
    )

    /** Wraps [expression] in the owner for [entry], or leaves it alone where there is none. */
    private fun owned(entry: ExposedCallable, expression: String): String {
        val type = ownedTypeOf(entry) ?: return expression
        return "_pm_own($expression, ${type.quoted()})"
    }

    private fun renderOne(index: Int, entry: ExposedCallable, rootModule: String): String {
        val handle = "_pm_h_$index"
        val function = "_pm_f_$index"
        val paramList = params(entry.arity).joinToString(", ")
        val argsTuple = tupleOf(argValues(entry))
        val dot = entry.name.lastIndexOf('.')
        val module = if (dot < 0) rootModule else entry.name.substring(0, dot)
        val leaf = if (dot < 0) entry.name else entry.name.substring(dot + 1)

        val body = if (entry.isSuspend) {
            // The awaited value is what gets owned, never the Future: `AsyncUpcall.deliver`
            // marshals a completion with the same tag a synchronous return uses, so a slow-path
            // OBJECT result is a handle too -- but it arrives *inside* the Future, and owning the
            // Future would release nothing and leak everything.
            val tail = if (ownedTypeOf(entry) == null) {
                """
                |    if hasattr(_pm_r, '__await__'):
                |        return await _pm_r
                |    return _pm_r
                """.trimMargin()
            } else {
                """
                |    if hasattr(_pm_r, '__await__'):
                |        _pm_r = await _pm_r
                |    return ${owned(entry, "_pm_r")}
                """.trimMargin()
            }
            """
            |async def $function($paramList):
            |    _pm_r = _pm_invoke($handle, $argsTuple)
            |    # Two return types for one declaration: the real value when the Kotlin body never
            |    # reached a suspension point, an asyncio.Future when it did. Only the second costs
            |    # an await, which is what keeps the fast path free of the event loop.
            |$tail
            """.trimMargin()
        } else {
            """
            |def $function($paramList):
            |    return ${owned(entry, "_pm_invoke($handle, $argsTuple)")}
            """.trimMargin()
        }

        return """
            |$handle = _pm_lookup(${entry.name.quoted()})
            |
            |
            |$body
            |
            |
            |$function.__name__ = ${leaf.quoted()}
            |$function.__qualname__ = ${entry.name.quoted()}
            |setattr(_pm_module(${module.quoted()}), ${leaf.quoted()}, $function)
        """.trimMargin()
    }

    /**
     * The setter entry paired with [getter], if the table has one.
     *
     * `null` is the answer for a Kotlin `val` **and** for a `var` whose setter is not public API
     * (`private`/`protected`/`internal set`) -- `FragmentScanner` emits no setter entry for either,
     * and this stage must not invent one. What it renders instead is a read-only attribute that
     * *raises* on assignment; see `_PmModule.__setattr__` and, for the class case, the
     * setter-less `property` on the metaclass.
     */
    private fun setterFor(
        getter: ExposedCallable,
        byName: Map<String, ExposedCallable>,
        kind: CallableKind,
    ): ExposedCallable? = byName["${getter.name}="]?.takeIf { it.kind == kind }

    /**
     * Renders one static property as a live attribute of the module its name points at.
     *
     * @return the source, and the next unused index -- one handle for the getter, and one more
     *   for the setter when there is one.
     */
    private fun renderModuleStatic(
        startIndex: Int,
        getter: ExposedCallable,
        byName: Map<String, ExposedCallable>,
        rootModule: String,
    ): Pair<String, Int> {
        var index = startIndex
        val getterHandle = "_pm_h_${index++}"
        val dot = getter.name.lastIndexOf('.')
        val module = if (dot < 0) rootModule else getter.name.substring(0, dot)
        val leaf = if (dot < 0) getter.name else getter.name.substring(dot + 1)

        val source = buildString {
            appendLine("$getterHandle = _pm_lookup(${getter.name.quoted()})")
            val setter = setterFor(getter, byName, CallableKind.STATIC_SETTER)
            val setterHandle = if (setter == null) "None" else "_pm_h_${index++}"
            if (setter != null) appendLine("$setterHandle = _pm_lookup(${setter.name.quoted()})")
            append(
                "_pm_static_property(_pm_module(${module.quoted()}), ${leaf.quoted()}, " +
                    "$getterHandle, $setterHandle)",
            )
        }
        return source to index
    }

    /**
     * Renders one Python class for [cls], plus the `_pm_lookup` calls its members need.
     *
     * @param startIndex the first unused `_pm_h_N` / `_pm_f_N` suffix; shared with [render]'s
     *   function loop so a class's handles never collide with a module function's.
     * @return the source, and the next unused index -- the same threading [render] does across
     *   its function loop, extended across classes too.
     */
    private fun renderClass(
        startIndex: Int,
        cls: ReflectedClass,
        byName: Map<String, ExposedCallable>,
        rootModule: String,
    ): Pair<String, Int> {
        var index = startIndex
        fun bindHandle(): String {
            val handle = "_pm_h_$index"
            index++
            return handle
        }

        val binds = StringBuilder()
        val body = StringBuilder()

        val ctor = byName["${cls.name}.<init>"]
        if (ctor != null) {
            val handle = bindHandle()
            binds.appendLine("$handle = _pm_lookup(${ctor.name.quoted()})")
            val paramList = params(ctor.arity).joinToString(", ")
            val callParams = if (paramList.isEmpty()) "self" else "self, $paramList"
            val argsTuple = tupleOf(argValues(ctor))
            body.appendLine("    def __init__($callParams):")
            // A CONSTRUCTOR result is the one OBJECT result that is *never* wrapped: the instance
            // being built is the owner, so the handle goes straight into its slot. Wrapping it here
            // would put an owner inside an owner and release the same root twice.
            body.appendLine("        self._pm_handle = _pm_invoke($handle, $argsTuple)")
            body.appendLine()
            // The other half of the handle's lifetime is inherited rather than rendered: `_PmObject`
            // -- which every rendered class now derives from -- carries the `__del__` that gives the
            // root back. `HandleTable`'s own class doc names that method as the whole of the
            // contract ("the proxy's tp_dealloc calling release is the entire lifetime contract"),
            // and for as long as neither existed, `ProxyHandleLifetimeTest` and
            // `GeneratedProxyCostTest` both measured every handle a constructor ever issued still
            // rooted at the end of the run. One implementation rather than one per class, because
            // the base's also clears the handle before releasing it, which is what makes a second
            // release a no-op without consulting the table at all.
        }

        // Preserves [ReflectedClass.memberNames] order (declaration order) for methods, but
        // collects property names into a set first: a GETTER and its SETTER are two entries with
        // one Python name between them, and rendering the getter as soon as its name is seen would
        // split `@property`/`@x.setter` across wherever each entry happened to fall in the member
        // list instead of keeping them adjacent, which is what a reader expects of one property.
        val propertyNames = LinkedHashSet<String>()
        val staticNames = LinkedHashSet<String>()
        val staticFunctionNames = LinkedHashSet<String>()
        for (memberName in cls.memberNames) {
            val entry = byName[memberName] ?: continue
            when (entry.kind) {
                CallableKind.METHOD -> {
                    val handle = bindHandle()
                    binds.appendLine("$handle = _pm_lookup(${entry.name.quoted()})")
                    body.append(renderMethodBody(entry.name.substringAfterLast('.'), handle, entry))
                    body.appendLine()
                }
                CallableKind.GETTER -> propertyNames += entry.name.substringAfterLast('.')
                CallableKind.STATIC_GETTER -> staticNames += entry.name.substringAfterLast('.')
                // A companion's function. `render` no longer publishes it as a module function --
                // the module it would land in is the one this class overwrites -- so the metaclass
                // below is now the only place it exists.
                CallableKind.FUNCTION -> staticFunctionNames += entry.name.substringAfterLast('.')
                // SETTER and STATIC_SETTER are picked up alongside their getters below;
                // CONSTRUCTOR was handled above.
                else -> {}
            }
        }

        for (propName in propertyNames) {
            val getter = byName["${cls.name}.$propName"] ?: continue
            val getterHandle = bindHandle()
            binds.appendLine("$getterHandle = _pm_lookup(${getter.name.quoted()})")
            body.appendLine("    @property")
            body.appendLine("    def $propName(self):")
            body.appendLine("        return ${owned(getter, "_pm_invoke($getterHandle, (self._pm_handle,))")}")
            body.appendLine()

            val setter = setterFor(getter, byName, CallableKind.SETTER)
            if (setter != null) {
                val setterHandle = bindHandle()
                val value = argValues(setter).single()
                binds.appendLine("$setterHandle = _pm_lookup(${setter.name.quoted()})")
                body.appendLine("    @$propName.setter")
                body.appendLine("    def $propName(self, a0):")
                body.appendLine("        _pm_invoke($setterHandle, (self._pm_handle, $value))")
                body.appendLine()
            }
        }

        // The metaclass body. A `property` in the class body answers `instance.x`; a static member
        // has no instance, and Kotlin reaches a companion member through the *class* and never
        // through an instance. A descriptor answers for the object whose *type* carries it, so for
        // the class object itself to be the reader and writer, the descriptor has to live on the
        // class's type -- which is what a metaclass is. See the KDoc's "Static surfaces" section
        // for the alternatives this was chosen over.
        val metaBody = StringBuilder()
        // A companion *function* goes on the same metaclass its companion *properties* do, for the
        // same two reasons: one Python name cannot be both a module and a class, and Kotlin reaches
        // a companion member through the class and never through an instance. A `staticmethod` in
        // the class body would answer the first but not the second -- Python resolves a
        // `staticmethod` through an instance quite happily, which is a shape Kotlin does not have.
        for (staticFunctionName in staticFunctionNames) {
            val entry = byName["${cls.name}.$staticFunctionName"] ?: continue
            val handle = bindHandle()
            binds.appendLine("$handle = _pm_lookup(${entry.name.quoted()})")
            metaBody.append(renderStaticFunctionBody(staticFunctionName, handle, entry))
            metaBody.appendLine()
            metaBody.appendLine()
        }
        for (staticName in staticNames) {
            val getter = byName["${cls.name}.$staticName"] ?: continue
            val getterHandle = bindHandle()
            binds.appendLine("$getterHandle = _pm_lookup(${getter.name.quoted()})")
            metaBody.appendLine("    @property")
            metaBody.appendLine("    def $staticName(cls):")
            // No receiver: a STATIC_GETTER's args are empty and a STATIC_SETTER's args[0] is the
            // new value, so `cls` is a Python-side formality and never crosses the boundary.
            metaBody.appendLine("        return ${owned(getter, "_pm_invoke($getterHandle, ())")}")
            metaBody.appendLine()

            val setter = setterFor(getter, byName, CallableKind.STATIC_SETTER)
            if (setter != null) {
                val setterHandle = bindHandle()
                val value = argValues(setter).single()
                binds.appendLine("$setterHandle = _pm_lookup(${setter.name.quoted()})")
                metaBody.appendLine("    @$staticName.setter")
                metaBody.appendLine("    def $staticName(cls, a0):")
                metaBody.appendLine("        _pm_invoke($setterHandle, ($value,))")
                metaBody.appendLine()
            }
        }
        // A class with nothing static pays nothing: no extra type object, and no shape a reader of
        // the generated source has to account for.
        val metaclassName = if (metaBody.isEmpty()) null else "_pm_t_${index++}"

        val dot = cls.name.lastIndexOf('.')
        val module = if (dot < 0) rootModule else cls.name.substring(0, dot)
        val className = if (dot < 0) cls.name else cls.name.substring(dot + 1)

        val source = buildString {
            append(binds)
            appendLine()
            if (metaclassName != null) {
                appendLine("class $metaclassName(type):")
                appendLine()
                // The owner base, put on from here rather than written into the class statement
                // below; `_pm_owned_new`'s own comment has the reason and [renderClass]'s KDoc has
                // what it cost. First line of the body so a reader meets it before the descriptors.
                appendLine("    __new__ = _pm_owned_new")
                appendLine()
                append(metaBody)
                appendLine()
            }
            // `_PmObject` is the base for the same reason the constructor already stashed a handle
            // and the `__del__` already gave it back: a rendered class *is* an owner. Sharing the
            // type is what lets `_pm_unwrap` accept an instance of one as an OBJECT argument, which
            // it could not do before -- a `Counter` passed to a function taking one crossed as a
            // `PyObject` and failed the Kotlin cast. A class that has a metaclass gets the same base
            // from `_pm_owned_new` above instead of naming it here.
            //
            // `_PmGcObject` instead, when [cls] carries a generated `tp_traverse`
            // ([ReflectedClass.hasTraverse]) *and* has no metaclass: those are the classes whose
            // Kotlin-side fields can form a cycle CPython's collector needs to see through, and
            // `_PmGcObject`'s own doc (in [OWNED_HANDLE]) is why the metaclass case is excluded --
            // `_pm_owned_new` only ever adds `_PmObject`, and the two owners cannot be combined on
            // one class. A class with statics but no traversable fields is unaffected either way.
            val gcEligible = metaclassName == null && cls.hasTraverse
            appendLine(
                when {
                    gcEligible -> "class $className(_PmGcObject):"
                    metaclassName == null -> "class $className(_PmObject):"
                    else -> "class $className(metaclass=$metaclassName):"
                },
            )
            if (body.isEmpty()) appendLine("    pass") else append(body)
            appendLine()
            appendLine("$className.__qualname__ = ${cls.name.quoted()}")
            appendLine("setattr(_pm_module(${module.quoted()}), ${className.quoted()}, $className)")
        }
        return source to index
    }

    /** The method half of [renderClass]: like [renderOne]'s body, but `self._pm_handle` is
     * always the first element of the args tuple. */
    private fun renderMethodBody(name: String, handle: String, entry: ExposedCallable): String {
        val paramList = params(entry.arity).joinToString(", ")
        val callParams = if (paramList.isEmpty()) "self" else "self, $paramList"
        // The receiver is already a handle -- this proxy's own -- so it is passed as it stands.
        val argsTuple = tupleOf(listOf("self._pm_handle") + argValues(entry))

        return if (entry.isSuspend) {
            val tail = if (ownedTypeOf(entry) == null) {
                """
                |        if hasattr(_pm_r, '__await__'):
                |            return await _pm_r
                |        return _pm_r
                """.trimMargin()
            } else {
                """
                |        if hasattr(_pm_r, '__await__'):
                |            _pm_r = await _pm_r
                |        return ${owned(entry, "_pm_r")}
                """.trimMargin()
            }
            """
            |    async def $name($callParams):
            |        _pm_r = _pm_invoke($handle, $argsTuple)
            |$tail
            """.trimMargin()
        } else {
            """
            |    def $name($callParams):
            |        return ${owned(entry, "_pm_invoke($handle, $argsTuple)")}
            """.trimMargin()
        }
    }

    /**
     * The companion-function half of [renderClass]'s metaclass: like [renderMethodBody], but the
     * first parameter is the class object and it is **not** passed across the boundary.
     *
     * A `CallableKind.FUNCTION` emitted for a companion member takes no receiver -- its arguments
     * start at `args[0]`, exactly as a top-level function's do -- so `cls` is a Python-side
     * formality, the same one [renderClass]'s static properties already have.
     */
    private fun renderStaticFunctionBody(name: String, handle: String, entry: ExposedCallable): String {
        val paramList = params(entry.arity).joinToString(", ")
        val callParams = if (paramList.isEmpty()) "cls" else "cls, $paramList"
        val argsTuple = tupleOf(argValues(entry))

        return if (entry.isSuspend) {
            val tail = if (ownedTypeOf(entry) == null) {
                """
                |        if hasattr(_pm_r, '__await__'):
                |            return await _pm_r
                |        return _pm_r
                """.trimMargin()
            } else {
                """
                |        if hasattr(_pm_r, '__await__'):
                |            _pm_r = await _pm_r
                |        return ${owned(entry, "_pm_r")}
                """.trimMargin()
            }
            """
            |    async def $name($callParams):
            |        _pm_r = _pm_invoke($handle, $argsTuple)
            |$tail
            """.trimMargin()
        } else {
            """
            |    def $name($callParams):
            |        return ${owned(entry, "_pm_invoke($handle, $argsTuple)")}
            """.trimMargin()
        }
    }

    private fun String.quoted(): String =
        "'" + replace("\\", "\\\\").replace("'", "\\'") + "'"
}
