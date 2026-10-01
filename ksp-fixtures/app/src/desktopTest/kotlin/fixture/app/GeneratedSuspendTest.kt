package fixture.app

import fixture.library.SuspendingService
import fixture.library.ValueHandler
import python.multiplatform.ffi.upcall.PendingCall
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the generator does with `suspend`, pinned against the table KSP actually produced from
 * `ksp-fixtures/library/.../Suspending.kt`.
 *
 * The answer used to be **silently skipped** -- `BindingPolicy.isExposedFunctionShape` dropped any
 * declaration carrying `Modifier.SUSPEND`, so nothing was emitted and nothing was logged, and a
 * user exposing a `suspend fun` got an `AttributeError` from Python with no indication that the
 * function had been seen and rejected. `docs/design/upcall-async-design.md` §6 recorded that as
 * deliberate: the silence was held in place so that the day a convention landed, the change would
 * show up as these tests failing rather than as one silence quietly becoming another.
 *
 * It has landed. A `suspend fun` now gets an ordinary entry whose body is
 * `PendingCall.start { ... }` and which carries [ExposedCallable.isSuspend]; the trampoline reads
 * that flag and either hands back the real value (the body never suspended) or an `asyncio.Future`
 * (it did). So what every assertion below checks is the *presence* of a shape rather than the
 * absence of one -- and the controls that used to guard against "the whole scope was dropped" are
 * still here, now guarding the opposite mistake: that suspending and non-suspending declarations
 * are not being given the same treatment.
 *
 * Nothing here touches CPython. The entry's shape and its `PendingCall` are checkable in plain
 * Kotlin; whether `await` works is `AsyncUpcallDeliveryTest` in `python-multiplatform`.
 *
 * A `suspend` *type* (`val h: suspend (Long) -> Long`) is a different question from a `suspend
 * fun` -- `BindingPolicy.isSuspending` reads `Modifier.SUSPEND` on a *declaration*, and a type
 * usage carries no such modifier -- and it used to have a worse answer than the one above: not
 * silently skipped, but silently *let through* as an `OBJECT` handle whose generated cast named a
 * classifier (`kotlin.coroutines.SuspendFunction1<...>`) with no source declaration anywhere in
 * the compilation and no entry of its own in the table. It compiled and the runtime checkcast
 * passed, so nothing caught it -- worse than the `suspend fun` silence, because that one at least
 * surfaced as a KSP-detectable shape rather than a classifier that happened to satisfy the
 * compiler. That question also has a landed answer now: `BindingPolicy.hasExposableTypes`
 * (`KSType.isSuspendFunctionType`, recursing through generic arguments and one level of
 * `typealias`) rejects it at every point a type enters the table, and the tests from
 * `aSuspendingFunctionTypeHasNoEntryAsATopLevelPropertyParameterOrReturn` onward pin the
 * *absence* this time, the same way the tests above pin the `suspend fun` entry's *presence*.
 */
class GeneratedSuspendTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(FunctionTable.fragments)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    private fun entry(name: String): ExposedCallable {
        val handle = UpcallTable.resolve(name)
        assertTrue(handle.isValid, "$name is not in the table")
        return UpcallTable.callable(handle)
    }

    /** Calls through the table the way the trampoline does, and asserts the shape it gets back. */
    private fun startCall(name: String, vararg args: Any?): PendingCall {
        val result = UpcallTable.invoke(UpcallTable.resolve(name), arrayOf(*args))
        assertNotNull(result, "$name returned nothing")
        assertTrue(result is PendingCall, "a suspending entry must hand back a PendingCall, got $result")
        return result
    }

    // ------------------------------------------------------------ the entry, shape by shape

    @Test
    fun aSuspendingTopLevelFunctionIsInTheTableAlongsideItsControl() {
        val suspending = entry("fixture.library.suspendingTopLevel")
        assertTrue(suspending.isSuspend, "the trampoline branches on this and nothing else")
        assertEquals(CallableKind.FUNCTION, suspending.kind)
        assertEquals(1, suspending.arity)
        assertEquals(listOf(TypeTag.INT), suspending.paramTypes)
        // The *declared* return type, not the PendingCall that actually comes back: the fast path
        // marshals the real value with this tag, and so does the Future's set_result.
        assertEquals(TypeTag.INT, suspending.returnType)

        val control = entry("fixture.library.blockingTopLevel")
        assertFalse(control.isSuspend, "a non-suspending function must not be given the async path")
        assertEquals(4L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.blockingTopLevel"), arrayOf(2L)))
    }

    @Test
    fun aSuspendingMemberKeepsItsReceiverSlotAndItsClassStaysConstructible() {
        val service = UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingService.<init>"), arrayOf(7L))
        assertEquals(7L, (service as SuspendingService).id)

        val fetch = entry("fixture.library.SuspendingService.fetch")
        assertTrue(fetch.isSuspend)
        // `suspend` and "has a receiver" are orthogonal, which is why the flag is not a
        // CallableKind: this is still a METHOD, and args[0] is still the instance.
        assertEquals(CallableKind.METHOD, fetch.kind)
        assertEquals(2, fetch.expectedArgCount)
        assertEquals(TypeTag.STRING, fetch.returnType)

        assertEquals(
            "sync:k",
            UpcallTable.invoke(
                UpcallTable.resolve("fixture.library.SuspendingService.fetchBlocking"),
                arrayOf(service, "k"),
            ),
        )
    }

    @Test
    fun aSuspendingCompanionMemberJoinsTheOwnersStaticSurface() {
        val create = entry("fixture.library.SuspendingService.create")
        assertTrue(create.isSuspend)
        assertEquals(CallableKind.FUNCTION, create.kind)
        assertEquals(TypeTag.OBJECT, create.returnType)

        val made = UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingService.createBlocking"), arrayOf(3L))
        assertEquals(3L, (made as SuspendingService).id)
    }

    @Test
    fun aSuspendingInterfaceMemberIsExposedLikeAnyOtherMethod() {
        assertNotNull(ClassLookup.find("fixture.library.SuspendingSource"))
        val load = entry("fixture.library.SuspendingSource.load")
        assertTrue(load.isSuspend)
        assertEquals(CallableKind.METHOD, load.kind)
        assertEquals(1, load.expectedArgCount, "no declared parameters, but the receiver is still a slot")

        assertEquals(CallableKind.METHOD, entry("fixture.library.SuspendingSource.describe").kind)
        assertFalse(entry("fixture.library.SuspendingSource.describe").isSuspend)
    }

    @Test
    fun aSuspendingObjectMemberJoinsTheSingletonsSurface() {
        val ping = entry("fixture.library.SuspendingRegistry.ping")
        assertTrue(ping.isSuspend)
        assertEquals(CallableKind.FUNCTION, ping.kind)
        assertEquals(0, ping.expectedArgCount)

        assertEquals("pong", UpcallTable.invoke(UpcallTable.resolve("fixture.library.SuspendingRegistry.pingBlocking"), arrayOf()))
    }

    @Test
    fun everySuspendingMemberIsAdvertisedOnItsClassAndResolves() {
        // `ReflectedClass.memberNames` is what a Python mirror is built from. A member missing
        // from it is invisible to Python however well the table entry is formed -- which is the
        // shape the old skip took, and the thing that had to change with it.
        for ((className, suspending, control) in listOf(
            Triple("fixture.library.SuspendingService", "fetch", "fetchBlocking"),
            Triple("fixture.library.SuspendingSource", "load", "describe"),
            Triple("fixture.library.SuspendingRegistry", "ping", "pingBlocking"),
        )) {
            val members = ClassLookup.require(className).memberNames
            assertTrue(members.contains("$className.$suspending"), "$className.$suspending must be advertised")
            assertTrue(members.contains("$className.$control"), "$className.$control must stay advertised")
            for (member in members) {
                assertTrue(UpcallTable.resolve(member).isValid, "unresolved generated member: $member")
            }
        }
    }

    // ------------------------------------------------- the generated body, run without an interpreter

    @Test
    fun theGeneratedBodyStartsTheCoroutineAndParksItsOutcomeInAPendingCall() {
        // `PendingCall.start { suspendingTopLevel(args[0] as Long) }` is what the generator emits.
        // Nothing in that fixture reaches a suspension point, so `docs/design/upcall-async-design.md` §5's
        // fast path applies: the call is already complete before `start` returned, and the boundary
        // can hand Python a real `int` with no Future and no event loop.
        val call = startCall("fixture.library.suspendingTopLevel", 21L)

        assertTrue(call.isDone, "a body with no suspension point completes on the calling thread")
        assertEquals(42L, call.value)
        assertNull(call.failure)
    }

    @Test
    fun aSuspendingMethodsGeneratedBodyReadsItsReceiverFromArgsZero() {
        val service = UpcallTable.invoke(
            UpcallTable.resolve("fixture.library.SuspendingService.<init>"),
            arrayOf(1L),
        ) as SuspendingService

        val call = startCall("fixture.library.SuspendingService.fetch", service, "k")

        assertTrue(call.isDone)
        assertEquals("async:k", call.value)
    }

    @Test
    fun aSuspendingCompanionMemberReturnsARealKotlinObjectThroughThePendingCall() {
        val call = startCall("fixture.library.SuspendingService.create", 9L)

        assertTrue(call.isDone)
        assertEquals(9L, (call.value as SuspendingService).id)
    }

    // -------------------------------------------------- a suspending *type* is a different answer
    //
    // This whole section used to pin the opposite: a `suspend` function *type* used as a
    // property, parameter or return type carries no `Modifier.SUSPEND` (that lives on a
    // *declaration*), so `BindingPolicy.isSuspending` never saw it, and the generated cast
    // (`args[0] as kotlin.coroutines.SuspendFunction1<...>`) compiled and checkcast cleanly
    // anyway -- a classifier with no source declaration in this compilation and no `invoke` entry
    // in the table, so what crossed was a handle Python could hold and hand back and nothing
    // else. `docs/design/upcall-async-design.md` §2.1 measured that as deliberately left open.
    //
    // It is closed now: `BindingPolicy.hasExposableTypes` rejects any property, parameter or
    // return type where `KSType.isSuspendFunctionType` is true (recursing into type arguments and
    // through one level of `typealias`), and `FragmentScanner` applies it at every point a type
    // can enter the table -- top-level and member functions, the primary constructor, and
    // properties at every scope (top-level, member, companion). Every test below asserts a
    // *rejection*: no table entry, and a KSP warning was observed for it
    // (`grep "has a type with no exposable classifier" build output`) rather than the old silence.

    @Test
    fun aSuspendingFunctionTypeHasNoEntryAsATopLevelPropertyParameterOrReturn() {
        assertFalse(UpcallTable.resolve("fixture.library.suspendingHandler").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.runsHandler").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.makeHandler").isValid)
    }

    @Test
    fun aSuspendingFunctionTypeNestedInAGenericTypeArgumentHasNoEntry() {
        // `List<suspend () -> Unit>` renders its cast as
        //     args[0] as kotlin.collections.List<kotlin.coroutines.SuspendFunction0<kotlin.Unit>>
        // -- the same unusable classifier one level down. `isExposableType` has to recurse into
        // type arguments to catch it; the outer `List` on its own would be a renderable (if inert)
        // `OBJECT` handle, a separate and older limitation this task does not touch.
        assertFalse(UpcallTable.resolve("fixture.library.handlerList").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.runsHandlerList").isValid)
    }

    @Test
    fun aNullableSuspendingFunctionTypeHasNoEntry() {
        assertFalse(UpcallTable.resolve("fixture.library.nullableHandler").isValid)
    }

    @Test
    fun aTypealiasForASuspendingFunctionTypeHasNoEntryEitherSideOfTheAlias() {
        // Measured, not assumed: `KSType.isSuspendFunctionType` answers `false` for the
        // alias-typed `KSType` itself (`KSType.declaration` is the `KSTypeAlias`, not the
        // expansion), so `isExposableType` has to expand one level of `typealias` before asking.
        // Without that expansion, `runsAliasedHandler`'s cast would target
        // `fixture.library.LongHandler` -- a real, source-declared classifier this time, since the
        // `typealias` is itself a declaration in this compilation -- and it would have passed
        // every check while remaining exactly as uncallable underneath.
        assertFalse(UpcallTable.resolve("fixture.library.aliasedHandler").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.runsAliasedHandler").isValid)
    }

    @Test
    fun aSuspendingFunctionTypeHasNoEntryAsAMemberPropertyParameterOrReturn() {
        assertFalse(UpcallTable.resolve("fixture.library.SuspendingService.handler").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.SuspendingService.runsHandlerMember").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.SuspendingService.makesHandlerMember").isValid)
    }

    @Test
    fun aSuspendingFunctionTypeHasNoEntryAsACompanionProperty() {
        // A companion member is folded onto the owner's static surface through
        // `FragmentScanner.companionEntries`, a separate code path from the member-property one
        // the test above exercises -- both needed their own wiring to `hasExposableTypes`.
        assertFalse(UpcallTable.resolve("fixture.library.SuspendingService.companionHandler").isValid)
    }

    @Test
    fun aSuspendingFunctionTypeHasNoEntryAsAPrimaryConstructorParameter() {
        // The primary constructor does not route through
        // `BindingPolicy.isExposedMemberFunction`/`isExposedTopLevelFunction` the way every other
        // function entry does -- `FragmentScanner` selects it directly -- so this is the one
        // location that needed its own, separate call to `hasExposableTypes` rather than
        // inheriting the check through a shared gate.
        assertFalse(UpcallTable.resolve("fixture.library.ValueHandler.<init>").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.ValueHandler.f").isValid)
    }

    @Test
    fun aClassWhoseOnlyMembersAreUnexposableStaysExposedWithAnEmptySurface() {
        // `ValueHandler`'s constructor and its one property are both this shape, so nothing of it
        // is left in the table -- but the class declaration itself is unaffected: it is still a
        // renderable classifier (`runsValueHandler` casts a parameter to it below), the same way a
        // class with only `suspend fun` members stayed exposed before this task
        // (`aSuspendingMemberKeepsItsReceiverSlotAndItsClassStaysConstructible`).
        val cls = ClassLookup.require("fixture.library.ValueHandler")
        assertTrue(cls.memberNames.isEmpty())
    }

    @Test
    fun everyMemberOfSuspendingServiceThatIsThisShapeIsMissingFromItsAdvertisedSurface() {
        // The negative counterpart to `everySuspendingMemberIsAdvertisedOnItsClassAndResolves`:
        // an excluded member must not leak into `ReflectedClass.memberNames` either, or a Python
        // mirror built from that list would still advertise a name with no entry behind it.
        val members = ClassLookup.require("fixture.library.SuspendingService").memberNames
        assertFalse(members.contains("fixture.library.SuspendingService.handler"))
        assertFalse(members.contains("fixture.library.SuspendingService.runsHandlerMember"))
        assertFalse(members.contains("fixture.library.SuspendingService.makesHandlerMember"))
        assertFalse(members.contains("fixture.library.SuspendingService.companionHandler"))
        // and the members that survive are still exactly the ones earlier tests in this file
        // already pin the shape of -- this only asserts they are still present alongside the gap.
        assertTrue(members.contains("fixture.library.SuspendingService.fetch"))
        assertTrue(members.contains("fixture.library.SuspendingService.create"))
    }

    @Test
    fun anOrdinaryNonSuspendingFunctionTypeIsUnaffectedAndStaysAnObjectHandle() {
        // The control: only `suspend` function types are unrenderable. An ordinary
        // `(Long) -> Long` has a real, source-declared `kotlin.Function1` behind it and is exactly
        // as exposable as it was before this task -- an opaque but legitimate `OBJECT` handle.
        val getter = UpcallTable.resolve("fixture.library.plainHandler")
        assertTrue(getter.isValid, "a non-suspending function type must not be caught by this check")
        assertEquals(TypeTag.OBJECT, UpcallTable.callable(getter).returnType)
        assertEquals(CallableKind.STATIC_GETTER, UpcallTable.callable(getter).kind)
    }

    @Test
    fun aValueClassWrappingAnUnexposableTypeIsStillAnOrdinaryParameterElsewhere() {
        // The control for the constructor case: what is unexposable lives *inside* `ValueHandler`,
        // not in the classifier itself. Casting a parameter *to* `ValueHandler` -- as any other
        // already-exposed class would be -- is unaffected.
        val consumer = UpcallTable.resolve("fixture.library.runsValueHandler")
        assertTrue(consumer.isValid)
        assertEquals(listOf(TypeTag.OBJECT), UpcallTable.callable(consumer).paramTypes)

        val handle = ValueHandler { it + 1 }
        val described = UpcallTable.invoke(consumer, arrayOf(handle))
        assertTrue((described as String).isNotEmpty())
    }

    @Test
    fun theRejectedClassifierHasNoInvokeEntryEitherWayConsistentWithTheOldMeasurement() {
        // Even where a suspending-type handle *did* used to cross (the old `suspendingHandler`
        // path this file no longer exercises), nothing ever exposed `SuspendFunction1.invoke` --
        // `docs/design/upcall-async-design.md` §2.1's point stands regardless of which side of this fix
        // it is read from.
        assertFalse(UpcallTable.resolve("kotlin.coroutines.SuspendFunction1.invoke").isValid)
    }
}
