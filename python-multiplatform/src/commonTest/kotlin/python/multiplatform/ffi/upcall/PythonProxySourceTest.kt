package python.multiplatform.ffi.upcall

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.ReflectedClass
import python.multiplatform.reflection.ReflectedClassKind
import python.multiplatform.reflection.TypeTag
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The generated Python proxy module, pinned as *text*.
 *
 * This is in `commonTest` and touches no interpreter, which is the payoff of making
 * [PythonProxySource.render] a pure function of the table: the thing that decides what Python sees
 * is checkable on all five targets, and `PythonProxyInstallTest` (desktopTest) is then only
 * responsible for showing that the text does what it says when a real CPython runs it.
 *
 * The assertions are about the two decisions that are easy to get silently wrong -- whether a
 * suspending entry gets an `async def` with the awaitable check, and whether a one-argument call
 * gets a tuple rather than a bare value -- rather than about exact formatting, which would make
 * every comment edit a test failure.
 */
class PythonProxySourceTest {

    private fun entry(
        name: String,
        arity: Int = 0,
        kind: CallableKind = CallableKind.FUNCTION,
        isSuspend: Boolean = false,
    ) = ExposedCallable(
        name = name,
        arity = arity,
        paramTypes = List(arity) { TypeTag.INT },
        returnType = TypeTag.INT,
        kind = kind,
        isSuspend = isSuspend,
    ) { 0L }

    /**
     * The walker's shape: a [CallableKind.FUNCTION] whose result is a Kotlin object and whose
     * producer said which one. [returnTypeName] `null` is the KSP shape, where nothing said.
     */
    private fun objectEntry(
        name: String,
        arity: Int = 0,
        paramTypes: List<TypeTag> = List(arity) { TypeTag.OBJECT },
        paramTypeNames: List<String> = emptyList(),
        returnTypeName: String? = "p.Link",
        kind: CallableKind = CallableKind.FUNCTION,
        isSuspend: Boolean = false,
    ) = ExposedCallable(
        name = name,
        arity = arity,
        paramTypes = paramTypes,
        returnType = TypeTag.OBJECT,
        paramTypeNames = paramTypeNames,
        returnTypeName = returnTypeName,
        kind = kind,
        isSuspend = isSuspend,
    ) { null }

    @Test
    fun aSuspendingEntryBecomesAnAsyncDefThatAwaitsOnlyWhatIsAwaitable() {
        val source = PythonProxySource.render(listOf(entry("fixture.library.fetchLater", arity = 1, isSuspend = true)))

        assertContains(source, "async def _pm_f_0(a0):")
        assertContains(source, "if hasattr(_pm_r, '__await__'):")
        assertContains(source, "return await _pm_r")
        // The whole point of the conditional await: an already-complete call must reach the caller
        // as its value, not wrapped in anything. `docs/design/upcall.md` §5.2.
        assertContains(source, "return _pm_r")
    }

    @Test
    fun aNonSuspendingEntryStaysAPlainDefWithNoAwaitMachineryAtAll() {
        val source = PythonProxySource.render(listOf(entry("fixture.library.greet", arity = 1)))

        assertContains(source, "def _pm_f_0(a0):")
        assertFalse(source.contains("async def _pm_f_0"), "a synchronous entry must not become a coroutine function")
        assertFalse(source.contains("__await__"), "nothing about a synchronous entry involves awaiting")
    }

    @Test
    fun aSingleArgumentCallIsPassedAsAOneTupleAndNotAsABareValue() {
        // `(a0)` is `a0`. The trampoline would then call PyTuple_Size on whatever was passed and
        // reject it -- or worse, succeed on something that happens to have a size.
        val source = PythonProxySource.render(listOf(entry("p.one", arity = 1)))
        assertContains(source, "_pm_invoke(_pm_h_0, (a0,))")

        val zero = PythonProxySource.render(listOf(entry("p.none")))
        assertContains(zero, "_pm_invoke(_pm_h_0, ())")

        val two = PythonProxySource.render(listOf(entry("p.two", arity = 2)))
        assertContains(two, "_pm_invoke(_pm_h_0, (a0, a1))")
    }

    @Test
    fun anEntryIsPublishedUnderItsKotlinPackageSoTheDottedNameIsImportable() {
        val source = PythonProxySource.render(listOf(entry("fixture.library.greet")))

        assertContains(source, "_pm_h_0 = _pm_lookup('fixture.library.greet')")
        assertContains(source, "setattr(_pm_module('fixture.library'), 'greet', _pm_f_0)")
        assertContains(source, "_pm_f_0.__name__ = 'greet'")
    }

    @Test
    fun aNameWithNoPackageOfItsOwnLandsInTheRootModule() {
        // Kotlin's default package produces such names and they have nowhere else to go.
        val source = PythonProxySource.render(listOf(entry("standalone")))
        assertContains(source, "setattr(_pm_module('kotlin'), 'standalone', _pm_f_0)")

        val custom = PythonProxySource.render(listOf(entry("standalone")), rootModule = "myapp")
        assertContains(custom, "setattr(_pm_module('myapp'), 'standalone', _pm_f_0)")
    }

    @Test
    fun kindsThatNeedAReceiverAreLeftUnrenderedWithNoClassDescriptor() {
        // Rendering a METHOD as a module function would put the receiver handle in the caller's
        // hands as a bare int. A METHOD/GETTER/SETTER only gets a Python-visible surface when its
        // owning ReflectedClass is passed too (see the `classes` tests below). A STATIC_GETTER
        // needs no receiver and does get a surface -- as an attribute, never as a callable, which
        // is what the `static` tests below pin.
        val source = PythonProxySource.render(
            listOf(
                entry("p.Thing.method", arity = 1, kind = CallableKind.METHOD),
                entry("p.Thing.prop", kind = CallableKind.GETTER),
                entry("p.Thing", kind = CallableKind.CONSTRUCTOR),
                entry("p.plain"),
            ),
        )

        assertEquals(1, Regex("^_pm_h_\\d+ = _pm_lookup", RegexOption.MULTILINE).findAll(source).count())
        assertContains(source, "_pm_lookup('p.plain')")
        assertFalse(source.contains("p.Thing.method"), "a method must not be rendered as a module function")
        // Not `contains("class ")`: the support half builds a `ModuleType` subclass of its own,
        // so the question is whether a *proxy* class was rendered, not whether the keyword appears
        // at all.
        assertFalse(source.contains("class Thing"), "no ReflectedClass was passed, so no class can be rendered")
        assertFalse(source.contains("_pm_module('p.Thing')"), "and nothing may be published under its name either")
    }

    // -------------------------------------------------------------------------- static properties

    @Test
    fun aTopLevelStaticPropertyBecomesALiveModuleAttributeRatherThanACallOrASnapshot() {
        // The two wrong answers this pins out: `mutableCounter()` would make the Python surface
        // disagree with the Kotlin declaration, and a plain `setattr(module, name, value)` at
        // install time would freeze a `var` at whatever it held when the table was installed.
        val source = PythonProxySource.render(
            listOf(
                entry("fixture.library.mutableCounter", kind = CallableKind.STATIC_GETTER),
                entry("fixture.library.mutableCounter=", arity = 1, kind = CallableKind.STATIC_SETTER),
            ),
        )

        assertContains(source, "_pm_h_0 = _pm_lookup('fixture.library.mutableCounter')")
        assertContains(source, "_pm_h_1 = _pm_lookup('fixture.library.mutableCounter=')")
        assertContains(
            source,
            "_pm_static_property(_pm_module('fixture.library'), 'mutableCounter', _pm_h_0, _pm_h_1)",
        )
        assertFalse(source.contains("def _pm_f_"), "a property must not be rendered as a callable")
    }

    @Test
    fun aReadOnlyTopLevelStaticPropertyRegistersNoSetterHandle() {
        val source = PythonProxySource.render(
            listOf(entry("fixture.library.libraryVersion", kind = CallableKind.STATIC_GETTER)),
        )

        assertContains(
            source,
            "_pm_static_property(_pm_module('fixture.library'), 'libraryVersion', _pm_h_0, None)",
        )
        assertEquals(1, Regex("^_pm_h_\\d+ = _pm_lookup", RegexOption.MULTILINE).findAll(source).count())
    }

    @Test
    fun aStaticPropertyWithNoPackageOfItsOwnLandsInTheRootModule() {
        val source = PythonProxySource.render(listOf(entry("standalone", kind = CallableKind.STATIC_GETTER)))
        assertContains(source, "_pm_static_property(_pm_module('kotlin'), 'standalone', _pm_h_0, None)")
    }

    @Test
    fun aStaticPropertyOwnedByARenderedClassGoesOnAMetaclassSoTheClassAttributeItselfIsLive() {
        // A `property` in the class body answers `instance.x`, not `Foo.x` -- for the class object
        // to be the thing that reads and writes, the descriptor has to live on the class's type.
        // That is what a metaclass is, and Kotlin's own rule agrees: a companion member is reached
        // through the class, never through an instance.
        val getter = entry("p.Foo.count", kind = CallableKind.STATIC_GETTER)
        val setter = entry("p.Foo.count=", arity = 1, kind = CallableKind.STATIC_SETTER)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(getter.name, setter.name))

        val source = PythonProxySource.render(listOf(getter, setter), listOf(cls))

        val metaclass = Regex("class (_pm_t_\\d+)\\(type\\):").find(source)?.groupValues?.get(1)
        assertTrue(metaclass != null, "a class with static members needs a metaclass to hold them:\n$source")
        assertContains(source, "class Foo(metaclass=$metaclass):")
        assertContains(source, "    def count(cls):")
        assertContains(source, "    @count.setter")
        assertContains(source, "    def count(cls, a0):")
        // No receiver: a STATIC_SETTER's args[0] is the new value, not `cls`.
        assertContains(source, "_pm_invoke(_pm_h_1, (a0,))")
        assertFalse(source.contains("(cls._pm_handle"), "a static member has no receiver to pass")
        assertFalse(
            source.contains("_pm_static_property(_pm_module('p.Foo')"),
            "a class's static member must not also be published as an attribute of a module named after the class",
        )
    }

    @Test
    fun aReadOnlyClassStaticGetsAMetaclassPropertyWithNoSetter() {
        val getter = entry("p.Foo.TAG", kind = CallableKind.STATIC_GETTER)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(getter.name))

        val source = PythonProxySource.render(listOf(getter), listOf(cls))

        assertContains(source, "    def TAG(cls):")
        assertFalse(source.contains(".setter"), "no STATIC_SETTER entry means no setter to render")
    }

    @Test
    fun aClassWithNoStaticMembersGetsNoMetaclassAtAll() {
        // The metaclass is a cost -- an extra type object per class, and a shape a reader has to
        // account for -- so it is only paid where there is something to put on it.
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(ctor), listOf(cls))

        assertContains(source, "class Foo(_PmObject):")
        assertFalse(source.contains("(type):"), "nothing static to hold, so no metaclass")
    }

    @Test
    fun aStaticPropertyOfAKotlinObjectStaysOnTheModuleTheObjectsFunctionsAlreadyUse() {
        // A `ReflectedClassKind.OBJECT` is not rendered as a Python class -- its functions already
        // go through the ordinary function path into a module named after the object -- so its
        // properties have to land in the same place or `Registry.ping()` and `Registry.size` would
        // resolve against two different objects.
        val getter = entry("p.Registry.size", kind = CallableKind.STATIC_GETTER)
        val setter = entry("p.Registry.size=", arity = 1, kind = CallableKind.STATIC_SETTER)
        val cls = ReflectedClass(
            name = "p.Registry",
            memberNames = listOf(getter.name, setter.name),
            kind = ReflectedClassKind.OBJECT,
        )

        val source = PythonProxySource.render(listOf(getter, setter), listOf(cls))

        assertContains(source, "_pm_static_property(_pm_module('p.Registry'), 'size', _pm_h_0, _pm_h_1)")
        assertFalse(source.contains("(type):"), "an object is not rendered as a class, so it has no metaclass")
    }

    // -------------------------------------------------------------------------- static functions

    @Test
    fun aCompanionFunctionOfARenderedClassGoesOnTheMetaclassInsteadOfAModuleTheClassThenOverwrites() {
        // The defect this pins out: a companion function is a `CallableKind.FUNCTION` named
        // `pkg.Owner.fn`, so the module path published it into a module called `pkg.Owner` -- and
        // the class rendering then bound `pkg.Owner` on the *parent* module to the class, leaving
        // the function reachable only through a `sys.modules` entry nothing points at any more.
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val create = entry("p.Foo.create", arity = 1)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(ctor.name, create.name))

        val source = PythonProxySource.render(listOf(ctor, create), listOf(cls))

        val metaclass = Regex("class (_pm_t_\\d+)\\(type\\):").find(source)?.groupValues?.get(1)
        assertTrue(metaclass != null, "a class with a static function needs a metaclass to hold it:\n$source")
        assertContains(source, "class Foo(metaclass=$metaclass):")
        assertContains(source, "    def create(cls, a0):")
        // No receiver: a companion function's args start at args[0], exactly like a STATIC_SETTER's.
        assertFalse(source.contains("(cls._pm_handle"), "a static function has no receiver to pass")
        assertFalse(
            source.contains("_pm_module('p.Foo')"),
            "publishing it on a module named after the class puts it where the class rendering " +
                "then overwrites:\n$source",
        )
        assertFalse(
            source.contains("def _pm_f_"),
            "a claimed companion function must not also be rendered as a module function",
        )
    }

    @Test
    fun aStaticPropertyAndAStaticFunctionOnTheSameClassBothSurvive() {
        // The real test of the fix: the two halves of one companion are rendered by two different
        // loops onto one metaclass, and either loop could have clobbered the other's name space or
        // its handle numbering.
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val create = entry("p.Foo.create", arity = 1)
        val getter = entry("p.Foo.count", kind = CallableKind.STATIC_GETTER)
        val setter = entry("p.Foo.count=", arity = 1, kind = CallableKind.STATIC_SETTER)
        val cls = ReflectedClass(
            name = "p.Foo",
            memberNames = listOf(ctor.name, create.name, getter.name, setter.name),
        )

        val source = PythonProxySource.render(listOf(ctor, create, getter, setter), listOf(cls))

        val metaclassNames = Regex("^class (_pm_t_\\d+)\\(type\\):", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(1, metaclassNames.size, "one companion, so one metaclass carries both halves")
        assertContains(source, "class Foo(metaclass=${metaclassNames[0]}):")
        assertContains(source, "    def create(cls, a0):")
        assertContains(source, "    def count(cls):")
        assertContains(source, "    @count.setter")

        val handleNames = Regex("^(_pm_h_\\d+) = _pm_lookup", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(handleNames.distinct(), handleNames, "every bound handle name must be unique")
        assertEquals(4, handleNames.size, "one handle per entry, and every entry is reachable")
    }

    @Test
    fun aSuspendingCompanionFunctionGetsTheSameConditionalAwaitAsEveryOtherSuspendingSurface() {
        val fetch = entry("p.Foo.fetchLater", arity = 1, isSuspend = true)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(fetch.name))

        val source = PythonProxySource.render(listOf(fetch), listOf(cls))

        assertContains(source, "    async def fetchLater(cls, a0):")
        assertContains(source, "if hasattr(_pm_r, '__await__'):")
        assertContains(source, "return await _pm_r")
    }

    @Test
    fun aKotlinObjectsFunctionStaysOnTheModuleBecauseTheObjectIsNotRenderedAsAClass() {
        // The counterpart of the companion case, and the reason the fix cannot simply be "a
        // FUNCTION whose name is a class member goes on the class": an `object` is *not* rendered
        // as a Python class, so there is nothing to overwrite its module and nothing to put the
        // function on. `Registry.ping()` and `Registry.size` must keep resolving against the same
        // module object.
        val ping = entry("p.Registry.ping")
        val size = entry("p.Registry.size", kind = CallableKind.STATIC_GETTER)
        val cls = ReflectedClass(
            name = "p.Registry",
            memberNames = listOf(ping.name, size.name),
            kind = ReflectedClassKind.OBJECT,
        )

        val source = PythonProxySource.render(listOf(ping, size), listOf(cls))

        assertContains(source, "setattr(_pm_module('p.Registry'), 'ping', _pm_f_0)")
        assertContains(source, "_pm_static_property(_pm_module('p.Registry'), 'size', _pm_h_1, None)")
        assertFalse(source.contains("(type):"), "an object is not rendered as a class, so it has no metaclass")
    }

    @Test
    fun aTableHoldingNothingButStaticPropertiesStillRendersThem() {
        // Before they were rendered this table produced the "nothing to render" comment, which is
        // now a wrong answer rather than an honest one.
        val source = PythonProxySource.render(listOf(entry("p.version", kind = CallableKind.STATIC_GETTER)))
        assertFalse(source.contains("# no CallableKind.FUNCTION entries"))
        assertContains(source, "_pm_lookup('p.version')")
    }

    @Test
    fun theSupportHalfCarriesThePerModuleTypeThatMakesAModuleAttributeLiveInBothDirections() {
        // Attribute hooks are looked up on the type and never on the instance, so a module whose
        // attributes reach Kotlin has to be reclassed. The type is built **per module** because
        // what goes on it is a descriptor named after the Kotlin declaration, and one shared type
        // would answer that name on every other proxy module too.
        assertContains(PythonProxySource.support, "def _pm_module_type(_mod):")
        assertContains(PythonProxySource.support, "(_pm_types.ModuleType,),")
        assertContains(PythonProxySource.support, "def _pm_static_property(_mod, _name, _get, _set):")
        assertContains(PythonProxySource.support, "setattr(_t, _name, property(_pm_fget, _pm_fset))")
    }

    @Test
    fun aModuleAttributeIsADataDescriptorRatherThanAFallbackHook() {
        // Not a style preference, and the reason it is not is measured: `__getattr__` runs only
        // after `module_getattro` has built and raised the formatted "module has no attribute"
        // AttributeError for it to catch and discard, which `GeneratedProxyCostTest` priced at
        // 551-587 ns per read on desktop against 6-14 ns for the descriptor that replaced it. A
        // hook reintroduced here would be a fifty-fold regression on every top-level `val` read,
        // and it would be invisible -- both shapes are correct.
        //
        // Issue #131 adds one hook, and it is the exception that keeps the rule: the PEP 562
        // `__getattr__` of `KotlinSurface.module_alias_getattr` runs only for a name the module does
        // NOT have (a Pythonic alias), so a real Kotlin name is still found by the descriptor first
        // and never reaches it. It lives in the delivered `KotlinSurface` half; the proxy half, which
        // renders every real attribute, must define no hook of its own.
        val proxyHalf = PythonProxySource.support.removePrefix(python.multiplatform.ffi.upcall.KotlinSurface.DELIVERY)
        assertFalse(
            proxyHalf.contains("def __getattr__("),
            "a module attribute must be answered by a data descriptor, not by the fallback hook",
        )
        assertEquals(
            1,
            Regex("def __getattr__\\(").findAll(python.multiplatform.ffi.upcall.KotlinSurface.DELIVERY).count(),
            "the only fallback hook is the alias one in module_alias_getattr",
        )
        assertTrue(
            python.multiplatform.ffi.upcall.KotlinSurface.DELIVERY.contains("def module_alias_getattr(module):"),
            "the single hook must be the alias hook",
        )
        assertFalse(
            PythonProxySource.support.contains("def __setattr__("),
            "a data descriptor answers the write half too; a __setattr__ hook means one is missing",
        )
    }

    @Test
    fun theSupportHalfDefinesNoNameThatAPerPlatformBootstrapAlreadyOwns() {
        // The defect this pins out: the name -> handle helper below used to be called `_pm_bind`,
        // which is what every `PyMethodDef` bootstrap calls its *handle -> callable* entry point.
        // `exec`ing this source therefore destroyed the host's `_pm_bind` on the way past. It was
        // silent because desktop -- the only target that ever ran this file -- publishes no
        // `_pm_bind` at all, so there was nothing there to overwrite.
        assertContains(PythonProxySource.support, "def _pm_lookup(_name):")
        for (owned in listOf("_pm_bind", "_pm_resolve", "_pm_invoke", "_pm_release", "_pm_cancel")) {
            assertFalse(
                PythonProxySource.support.contains("def $owned("),
                "'$owned' belongs to the per-platform bootstrap; the generated module may call it, never define it",
            )
        }
    }

    @Test
    fun handlesStayUniqueWhenFunctionsClassStaticsAndModuleStaticsAreRenderedTogether() {
        val fn = entry("p.greet", arity = 1)
        val moduleStatic = entry("p.version", kind = CallableKind.STATIC_GETTER)
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val classGetter = entry("p.Foo.count", kind = CallableKind.STATIC_GETTER)
        val classSetter = entry("p.Foo.count=", arity = 1, kind = CallableKind.STATIC_SETTER)
        val instanceGetter = entry("p.Foo.value", kind = CallableKind.GETTER)
        val cls = ReflectedClass(
            name = "p.Foo",
            memberNames = listOf(ctor.name, classGetter.name, classSetter.name, instanceGetter.name),
        )

        val source = PythonProxySource.render(
            listOf(fn, moduleStatic, ctor, classGetter, classSetter, instanceGetter),
            listOf(cls),
        )

        val handleNames = Regex("^(_pm_h_\\d+) = _pm_lookup", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(handleNames.distinct(), handleNames, "every bound handle name must be unique")
        assertEquals(6, handleNames.size, "one handle per entry, and every entry is reachable")

        val metaclassNames = Regex("^class (_pm_t_\\d+)\\(type\\):", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(1, metaclassNames.size, "one class with statics, so exactly one metaclass")
        assertContains(source, "class Foo(metaclass=${metaclassNames[0]}):")
    }

    // ------------------------------------------------------------------------------- classes

    @Test
    fun aConstructorAndAnInstanceMethodBecomeAPythonClassThatDispatchesThroughTheHandle() {
        val ctor = entry("fixture.library.Counter.<init>", arity = 1, kind = CallableKind.CONSTRUCTOR)
        val increment = entry("fixture.library.Counter.increment", arity = 1, kind = CallableKind.METHOD)
        val cls = ReflectedClass(
            name = "fixture.library.Counter",
            memberNames = listOf(ctor.name, increment.name),
        )

        val source = PythonProxySource.render(listOf(ctor, increment), listOf(cls))

        assertContains(source, "class Counter(_PmObject):")
        assertContains(source, "def __init__(self, a0):")
        assertContains(source, "self._pm_handle = _pm_invoke(")
        assertContains(source, "def increment(self, a0):")
        assertContains(source, "return _pm_invoke(")
        assertContains(source, "(self._pm_handle, a0)")
        assertContains(source, "setattr(_pm_module('fixture.library'), 'Counter', Counter)")
    }

    @Test
    fun aClassThatTakesAHandleInItsConstructorAlsoRendersTheReleaseThatGivesItBack() {
        val ctor = entry("fixture.library.Counter.<init>", arity = 1, kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(ctor), listOf(cls))

        // The half that was missing for as long as the generator has existed: `__init__` took a
        // HandleTable root and nothing anywhere gave it back, so every object Python constructed
        // stayed rooted in Kotlin for the life of the process. `ProxyHandleLifetimeTest` is the
        // behavioural half; this is the same claim made about the text, where it is readable.
        //
        // The `__del__` is **inherited** rather than rendered per class now -- the rendered class
        // derives from `_PmObject`, which is the same owner an ordinary OBJECT result gets -- so
        // what this asserts is that the class is one of those and that the owner has the release.
        // One implementation rather than one per class; the behaviour is what it was.
        assertContains(source, "class Counter(_PmObject):")
        assertContains(source, "def __del__(self, _pm_r=_pm_releaser()):")
        assertContains(source, "_pm_h = getattr(self, '_pm_handle', None)")
        assertContains(source, "_pm_r(_pm_h)")
        // Resolved once at class-definition time rather than read out of globals per call: during
        // interpreter finalisation a module's globals are None and `__del__` still runs.
        assertContains(PythonProxySource.support, "def _pm_releaser():")
        assertContains(PythonProxySource.support, "return globals().get('_pm_release', _pm_no_release)")
    }

    @Test
    fun aClassWithNoConstructorHoldsNoHandleAndTheInheritedReleaseFindsNothingToGiveBack() {
        // This used to assert that no `__del__` was rendered at all, which stopped being the
        // question when the release moved onto `_PmObject`: a class with no constructor still
        // *inherits* one, because it is still an owner -- something else may hand it a handle
        // later, and a factory that does is the next step. What has to stay true is the property
        // the old assertion was really about: nothing releases a root the class never took. The
        // owner's `__del__` reads its handle with `getattr(..., None)` and does nothing when the
        // slot was never assigned, which is the same guard that covers a Kotlin constructor that
        // threw before `__init__` finished.
        val getter = entry("fixture.library.Counter.value", kind = CallableKind.GETTER)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(getter.name))

        val source = PythonProxySource.render(listOf(getter), listOf(cls))

        assertEquals(
            1, Regex("def __del__").findAll(source).count(),
            "the release lives on the owner and nowhere else; a second one is a second policy",
        )
        assertContains(source, "_pm_h = getattr(self, '_pm_handle', None)")
        assertContains(source, "if _pm_h is not None:")
    }

    @Test
    fun aPropertyBecomesAPythonPropertyRatherThanAMethod() {
        val getter = entry("fixture.library.Counter.value", kind = CallableKind.GETTER)
        val setter = entry("fixture.library.Counter.value=", arity = 1, kind = CallableKind.SETTER)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(getter.name, setter.name))

        val source = PythonProxySource.render(listOf(getter, setter), listOf(cls))

        assertContains(source, "@property")
        assertContains(source, "def value(self):")
        assertContains(source, "(self._pm_handle,)")
        assertContains(source, "@value.setter")
        assertContains(source, "def value(self, a0):")
        assertFalse(source.contains("value()"), "a property must not be called like a method")
    }

    @Test
    fun aReadOnlyPropertyGetsNoSetter() {
        val getter = entry("fixture.library.Counter.value", kind = CallableKind.GETTER)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(getter.name))

        val source = PythonProxySource.render(listOf(getter), listOf(cls))

        assertContains(source, "@property")
        assertFalse(source.contains(".setter"), "no SETTER entry means no setter to render")
    }

    @Test
    fun aSuspendingInstanceMethodBecomesAnAwaitableTheSameWayASuspendingFunctionDoes() {
        val method = entry("fixture.library.Worker.fetch", arity = 1, kind = CallableKind.METHOD, isSuspend = true)
        val cls = ReflectedClass(name = "fixture.library.Worker", memberNames = listOf(method.name))

        val source = PythonProxySource.render(listOf(method), listOf(cls))

        assertContains(source, "async def fetch(self, a0):")
        assertContains(source, "if hasattr(_pm_r, '__await__'):")
        assertContains(source, "return await _pm_r")
        assertFalse(source.contains("def fetch(self, a0):\n        return _pm_invoke"), "a suspending method must not be a plain def")
    }

    @Test
    fun aClassWithNoPackageOfItsOwnLandsInTheRootModule() {
        val ctor = entry("Standalone.<init>", kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "Standalone", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(ctor), listOf(cls))
        assertContains(source, "setattr(_pm_module('kotlin'), 'Standalone', Standalone)")
    }

    @Test
    fun functionsAndClassesRenderTogetherWithoutHandleNameCollisions() {
        val fn = entry("fixture.library.greet", arity = 1)
        val ctor = entry("fixture.library.Counter.<init>", arity = 1, kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "fixture.library.Counter", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(fn, ctor), listOf(cls))

        val handleNames = Regex("^(_pm_h_\\d+) = _pm_lookup", RegexOption.MULTILINE)
            .findAll(source).map { it.groupValues[1] }.toList()
        assertEquals(handleNames.distinct(), handleNames, "every bound handle name must be unique")
        assertEquals(2, handleNames.size)
    }

    @Test
    fun anEmptyTableRendersSomethingRunnableRatherThanNothing() {
        val source = PythonProxySource.render(emptyList())
        assertContains(source, "def _pm_settle(")
        assertContains(source, "# no CallableKind.FUNCTION entries or proxy classes to render")
        assertFalse(source.contains("_pm_lookup('"), "there is nothing to bind")
    }

    @Test
    fun theSupportHalfCarriesTheGuardThatMakesADeliveryToACancelledFutureSafe() {
        // `AsyncUpcall` schedules this function rather than `Future.set_result` itself, and the
        // `done()` check inside it is the only part of that path that is a guarantee -- the check
        // Kotlin makes before scheduling is separated from the callback by a thread hand-off.
        assertContains(PythonProxySource.support, "def _pm_settle(_fut, _ok, _payload):")
        assertContains(PythonProxySource.support, "if _fut.done():")
        assertContains(PythonProxySource.support, "return")
        assertTrue(
            PythonProxySource.support.indexOf("if _fut.done():") <
                PythonProxySource.support.indexOf("_fut.set_result(_payload)"),
            "the guard has to come before the set, or it is not a guard",
        )
    }

    // ----------------------------------------------------------------------- owned object results

    @Test
    fun anObjectResultWhoseTypeTheProducerNamedIsHandedToPythonInsideSomethingThatOwnsIt() {
        // The leak this closes: a `TypeTag.OBJECT` result crosses as a `HandleTable` integer, and an
        // integer has nothing to hang a finaliser off, so every one of them was the caller's to
        // release by hand. `docs/design/kotlin-extensions-in-python.md` §6 records the consequence for the
        // Compose chain -- one handle per intermediate link -- and `OwnedResultLifetimeTest` counts
        // it. The owner is a Python object, which is the only thing a `__del__` can live on.
        val source = PythonProxySource.render(listOf(objectEntry("p.seed")))

        assertContains(source, "return _pm_own(_pm_invoke(_pm_h_0, ()), 'p.Link')")
    }

    @Test
    fun anObjectArgumentIsUnwrappedBackToItsHandleSoAChainKeepsWorking() {
        // `size(padding(m, 16.0), 24.0)`: the result of one call is the first argument of the next,
        // so wrapping the result is only half of it. What crosses is still the handle.
        val source = PythonProxySource.render(
            listOf(objectEntry("p.link", arity = 2, paramTypes = listOf(TypeTag.OBJECT, TypeTag.FLOAT))),
        )

        assertContains(source, "_pm_invoke(_pm_h_0, (_pm_unwrap(a0), a1))")
        assertFalse(
            source.contains("_pm_unwrap(a1)"),
            "only an OBJECT-tagged parameter can be carrying a handle; unwrapping a float is cost " +
                "for nothing",
        )
    }

    @Test
    fun aParameterDeclaredAsAPyObjectIsNotUnwrapped() {
        // `99acd830`'s open defect. `TypeTag.OBJECT` is two things at once in the *argument*
        // direction too: `UpcallTrampoline.toKotlinObject` reads an `int` as a `HandleTable` handle
        // and anything else as the Python object itself. So unwrapping a proxy handed to a parameter
        // the producer declared as `PyObject` sends the Kotlin object behind the handle, and the
        // entry's own `args[i] as PyObject` cast fails -- `fixture.app.ProxyObjectArgumentTest`
        // observes exactly that. The declared type is the only thing that can decide this; the value
        // cannot, because a proxy is a legitimate argument to either kind of parameter.
        val source = PythonProxySource.render(
            listOf(
                objectEntry(
                    "p.echo",
                    arity = 1,
                    paramTypeNames = listOf("python.multiplatform.ffi.PyObject"),
                ),
            ),
        )

        assertContains(source, "_pm_invoke(_pm_h_0, (a0,))")
        assertFalse(
            source.contains("_pm_unwrap(a0)"),
            "a PyObject-typed parameter wants the Python object, so there is nothing to unwrap",
        )
    }

    @Test
    fun aParameterDeclaredAsAKotlinTypeIsStillUnwrapped() {
        // The other side of the same decision, and the reason it cannot be "stop unwrapping":
        // `chain.link`'s parameter is declared `chain.ChainLink`, and `OwnedResultLifetimeTest`
        // counts the handles that route roots and releases.
        val source = PythonProxySource.render(
            listOf(objectEntry("p.link", arity = 1, paramTypeNames = listOf("p.Link"))),
        )

        assertContains(source, "_pm_invoke(_pm_h_0, (_pm_unwrap(a0),))")
    }

    @Test
    fun aParameterWhoseProducerNamedNoTypeKeepsBeingUnwrapped() {
        // Every hand-written fragment in this repository and every producer not yet taught to fill
        // `paramTypeNames` is this shape. Silence is not "it is a PyObject" -- it is "nothing said",
        // and the contract that existed before anything said is the unwrapping one.
        val source = PythonProxySource.render(listOf(objectEntry("p.opaque", arity = 1)))

        assertContains(source, "_pm_invoke(_pm_h_0, (_pm_unwrap(a0),))")
    }

    @Test
    fun bothOwnersAreUnwrappableSoTheDeclaredTypeIsTheOnlyThingThatDecides() {
        // `_PmGcObject` is deliberately *not* an `_PmObject` -- two solid bases cannot be combined --
        // so an `isinstance(v, _PmObject)` test silently answered "leave it alone" for every proxy of
        // a class with a `PyObject` field, whatever its parameter was declared as. That is the same
        // defect from the other end: `fixture.app.ProxyObjectArgumentTest` observes a `RefHolder`
        // proxy reaching a `RefHolder`-typed parameter as a `PyObject`. Now that the decision is made
        // when the call is rendered, `_pm_unwrap` is only reached where unwrapping is wanted, and it
        // has to be able to do it for either owner.
        val source = PythonProxySource.render(listOf(objectEntry("p.link", arity = 1)))

        assertContains(source, "isinstance(_pm_v, _pm_owners)")
        assertContains(source, "_pm_owners=(_PmObject, _PmGcObject)")
        assertTrue(
            source.indexOf("_PmGcObject") < source.indexOf("def _pm_unwrap("),
            "the owners tuple is bound as a default argument, so both names must already exist",
        )
    }

    @Test
    fun anEntryWhoseProducerDidNotNameItsReturnKeepsTheBareHandleContract() {
        // Every KSP-generated entry is this shape. `TypeTag.OBJECT` is two things at once in the
        // result direction -- `UpcallTrampoline.fromKotlinObject` answers with a handle for a Kotlin
        // object and with the Python object itself for a `PyObject` -- and Python cannot tell them
        // apart when the second happens to be an `int`. Owning one of those would make `__del__`
        // release a handle nobody issued, so the name is the gate.
        val source = PythonProxySource.render(listOf(objectEntry("p.opaque", returnTypeName = null)))

        assertContains(source, "return _pm_invoke(_pm_h_0, ())")
        assertFalse(source.contains("return _pm_own("), "an unnamed return must stay the bare handle")
    }

    @Test
    fun aReturnDeclaredAsAPyObjectIsNotOwnedEvenThoughItIsNamed() {
        // The named case of the same ambiguity: a Kotlin declaration returning `PyObject` hands
        // Python back a Python object, never a handle, so there is nothing to own and the value may
        // legitimately *be* an `int`.
        val source = PythonProxySource.render(
            listOf(objectEntry("p.echo", returnTypeName = "python.multiplatform.ffi.PyObject")),
        )

        assertFalse(source.contains("return _pm_own("), "a PyObject result is not a Kotlin handle")

        val any = PythonProxySource.render(listOf(objectEntry("p.anything", returnTypeName = "kotlin.Any")))
        assertFalse(any.contains("return _pm_own("), "an `Any` return may be carrying a PyObject")
    }

    @Test
    fun aSuspendingObjectResultIsOwnedAfterItIsAwaitedRatherThanBeforeIt() {
        // `AsyncUpcall.deliver` marshals a completion with the same tag a synchronous return uses,
        // so a slow-path OBJECT result is a handle too -- but it arrives inside the Future. Owning
        // the Future rather than its value would release nothing and leak everything.
        val source = PythonProxySource.render(listOf(objectEntry("p.later", arity = 1, isSuspend = true)))

        assertContains(source, "async def _pm_f_0(a0):")
        assertContains(source, "if hasattr(_pm_r, '__await__'):")
        assertContains(source, "_pm_r = await _pm_r")
        assertContains(source, "return _pm_own(_pm_r, 'p.Link')")
    }

    @Test
    fun theOwnerIsDefinedOnceSoReinstallingTheTableDoesNotOrphanTheObjectsPythonAlreadyHolds() {
        // `install()` is documented as safe to run more than once, and `GeneratedProxyCostTest`
        // really does run it twenty-five times. A bare `class _PmObject:` would build a *new* class
        // each time, and every object handed out before that point would stop being an instance of
        // the one `_pm_unwrap` tests against -- so it would cross as a `PyObject` and fail the cast,
        // silently and only for the objects that predate the reinstall.
        val source = PythonProxySource.render(listOf(objectEntry("p.seed")))

        assertContains(source, "if '_pm_own' not in globals():")
        assertContains(source, "class _PmObject:")
        assertContains(source, "def __del__(self, _pm_r=_pm_releaser()):")
        assertTrue(
            source.indexOf("if '_pm_own' not in globals():") < source.indexOf("class _PmObject:"),
            "the guard has to come before the definition, or it is not a guard",
        )
    }

    @Test
    fun theOwnerClearsItsHandleBeforeReleasingItSoASecondReleaseCannotReachTheSlotsNewOwner() {
        // Half of the double-release defence; `HandleTable`'s generation tag is the other half and
        // catches the case where somebody *else* released it first. This half catches the case where
        // the same owner is asked twice -- a resurrected object, or an explicit `__del__()`.
        val source = PythonProxySource.render(listOf(objectEntry("p.seed")))

        assertContains(source, "self._pm_handle = None")
        assertTrue(
            source.indexOf("self._pm_handle = None") < source.indexOf("_pm_r(_pm_h)"),
            "clearing after the release leaves a window in which a second one is still possible",
        )
    }

    @Test
    fun aRenderedProxyClassIsTheSameKindOfOwnerSoAnInstanceCanBePassedBackAsAnObject() {
        // One concept, not two. A rendered class already holds a handle and already releases it;
        // making it a `_PmObject` is what lets `_pm_unwrap` accept an instance of it as an OBJECT
        // argument, which it could not do before -- a `Counter` passed to a function taking one
        // crossed as a `PyObject` and failed the cast.
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(ctor.name))

        val source = PythonProxySource.render(listOf(ctor), listOf(cls))

        assertContains(source, "class Foo(_PmObject):")
        assertTrue(
            source.indexOf("class _PmObject:") < source.indexOf("class Foo(_PmObject):"),
            "the base has to be defined before the class that inherits it",
        )
    }

    @Test
    fun everyPerEntrySurfaceThatCanReturnAKotlinObjectOwnsItAndNotJustTheModuleFunction() {
        // A result is a result wherever it is read from. These are the four surfaces rendered *per
        // entry*, so each one has the tag and the type name in hand at the point it emits the call;
        // the module-attribute path is the one that does not, and the class KDoc says why it is
        // left alone rather than made to pay a `_pm_own` on every top-level `val` read.
        val method = objectEntry("p.Foo.spawn", arity = 1, kind = CallableKind.METHOD)
        val getter = objectEntry("p.Foo.child", kind = CallableKind.GETTER)
        val companion = objectEntry("p.Foo.make", arity = 1, kind = CallableKind.FUNCTION)
        val static = objectEntry("p.Foo.DEFAULT", kind = CallableKind.STATIC_GETTER)
        val cls = ReflectedClass(
            name = "p.Foo",
            memberNames = listOf(method.name, getter.name, companion.name, static.name),
        )

        val source = PythonProxySource.render(listOf(method, getter, companion, static), listOf(cls))

        assertEquals(
            4, Regex("return _pm_own\\(").findAll(source).count(),
            "one owned result per surface, and the receiver's own handle is not one of them:\n$source",
        )
        // The receiver is already a handle -- this proxy's own -- so it is passed as it stands and
        // is not unwrapped; only the declared OBJECT parameter beside it is.
        assertContains(source, "(self._pm_handle, _pm_unwrap(a0))")
    }

    @Test
    fun aRenderedProxyClassWithStaticsKeepsItsMetaclassAlongsideTheOwnerBase() {
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val getter = entry("p.Foo.count", kind = CallableKind.STATIC_GETTER)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(ctor.name, getter.name))

        val source = PythonProxySource.render(listOf(ctor, getter), listOf(cls))

        val metaclass = Regex("class (_pm_t_\\d+)\\(type\\):").find(source)?.groupValues?.get(1)
        assertTrue(metaclass != null, "a class with static members needs a metaclass:\n$source")
        assertContains(source, "class Foo(metaclass=$metaclass):")
        // Still an owner, and this is the only place that says how: the metaclass puts the base on.
        assertContains(source, "    __new__ = _pm_owned_new")
    }

    @Test
    fun aClassThatHasAMetaclassGetsTheOwnerBaseFromItRatherThanFromItsOwnBaseList() {
        // A rendered class must be a `_PmObject` (so `_pm_unwrap` accepts an instance of one) *and*
        // its statics must sit on a metaclass. Written the obvious way those two read as
        // `class Foo(_PmObject, metaclass=_pm_t_1):`, and that spelling is what broke
        // `ksp-fixtures:app`: see [theCompanionShapeAConsumerMatchesWithALazyRegexStaysAdjacent].
        //
        // Python has no syntax for putting a base *after* a keyword, so the base has to come from
        // somewhere other than the base list, and the metaclass is the only thing left holding the
        // class before it exists. `_pm_owned_new` is that: a `__new__` on the metaclass that adds
        // the owner to `bases` unless something in there is already one -- so a Python subclass of a
        // rendered class does not get a second copy, and an ordinary `class Foo(_PmObject):` with no
        // statics keeps saying so in the source where a reader can see it.
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val getter = entry("p.Foo.count", kind = CallableKind.STATIC_GETTER)
        val cls = ReflectedClass(name = "p.Foo", memberNames = listOf(ctor.name, getter.name))

        val source = PythonProxySource.render(listOf(ctor, getter), listOf(cls))

        val metaclass = Regex("class (_pm_t_\\d+)\\(type\\):").find(source)?.groupValues?.get(1)
        assertTrue(metaclass != null, "a class with static members needs a metaclass:\n$source")
        assertFalse(
            source.contains("class Foo(_PmObject, metaclass="),
            "the owner must not be in the base list of a class that has a metaclass:\n$source",
        )
        assertContains(source, "def _pm_owned_new(")
        assertTrue(
            source.indexOf("def _pm_owned_new(") < source.indexOf("__new__ = _pm_owned_new"),
            "the injector has to be defined before a metaclass names it",
        )
        assertTrue(
            source.indexOf("class $metaclass(type):") < source.indexOf("class Foo(metaclass="),
            "the metaclass has to exist before the class that is built by it",
        )
    }

    @Test
    fun theCompanionShapeAConsumerMatchesWithALazyRegexStaysAdjacent() {
        // Pinned here because the consumer that reads it cannot pin it for itself, and because the
        // way it failed hid what had happened. `ksp-fixtures:app`'s
        // `GeneratedStaticPropertyProxyTest` locates a companion's metaclass with
        //
        //     class (_pm_t_\d+)\(type\):\n(?:.|\n)*?class Foo\(metaclass=\1\):
        //
        // When the owner base moved into that base list the terminator stopped existing anywhere in
        // the module, and `java.util.regex`'s lazy loop recurses once per character it consumes:
        // a pattern that can never complete scans from the first metaclass to the end of a 30 KB
        // module and dies with **StackOverflowError**, not with a clean "no match". Both fixture
        // tests that read a metaclass failed that way, and neither said anything about rendering.
        //
        // So the assertion is not "the regex is satisfiable" -- it is that the class statement
        // follows its own metaclass with nothing but that metaclass's body in between, which is the
        // shape the section above chose and the only one a consumer can match cheaply.
        val ctor = entry("p.Foo.<init>", kind = CallableKind.CONSTRUCTOR)
        val create = entry("p.Foo.create", arity = 1)
        val getter = entry("p.Foo.TAG", kind = CallableKind.STATIC_GETTER)
        val cls = ReflectedClass(
            name = "p.Foo",
            memberNames = listOf(ctor.name, create.name, getter.name),
        )

        val source = PythonProxySource.render(listOf(ctor, create, getter), listOf(cls))

        val consumer = Regex("class (_pm_t_\\d+)\\(type\\):\\n(?:.|\\n)*?class Foo\\(metaclass=\\1\\):")
        val match = consumer.find(source)
        assertTrue(match != null, "a consumer's metaclass regex has to find the pair:\n$source")
        // and everything the companion owns is inside the span it matched, which is what makes the
        // consumer's follow-up `assertContains(match.value, ...)` meaningful.
        assertContains(match.value, "    def create(cls, a0):")
        assertContains(match.value, "    def TAG(cls):")
    }

    @Test
    fun theGeneratedModuleRefusesToInstallWithoutTheRawEntryPointsBound() {
        // Defining proxies over an unbound `_pm_invoke` would turn one missing bootstrap step into
        // a NameError at each individual call site, arbitrarily later.
        val source = PythonProxySource.render(listOf(entry("p.plain")))
        assertContains(source, "if '_pm_resolve' not in globals() or '_pm_invoke' not in globals():")
        assertTrue(
            source.indexOf("not in globals()") < source.indexOf("_pm_lookup('p.plain')"),
            "the guard has to run before the first bind",
        )
    }
}
