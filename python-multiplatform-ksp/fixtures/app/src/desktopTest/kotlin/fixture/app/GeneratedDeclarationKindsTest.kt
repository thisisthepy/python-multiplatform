package fixture.app

import fixture.library.Color
import fixture.library.Level
import fixture.library.Registry
import fixture.library.RestrictedSetters
import fixture.library.WithCompanion
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ReflectedClassKind
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ROADMAP §7's "companion-object members, interfaces, enums and annotation classes are not
 * exposed", closed against the table KSP generated from `python-multiplatform-ksp/fixtures/library` -- not against a
 * hand-written fragment.
 *
 * Two of these tests assert an *absence* (annotation classes, generics). They are not
 * vacuous: each names a declaration that exists in the fixture sources and that the generator
 * decided against, and the reason is in [python.multiplatform.ksp.BindingPolicy].
 */
class GeneratedDeclarationKindsTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(FunctionTable.fragments)
        Registry.size = 0
        WithCompanion.created = 0
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    private fun kindOf(name: String): CallableKind = UpcallTable.callable(UpcallTable.resolve(name)).kind

    // ------------------------------------------------------------------------ companion objects

    @Test
    fun companionMembersAreReachableUnderTheOwningClassNameWithNoReceiver() {
        // docs/design/binding-policy.md: "companion object 멤버 -> 클래스의 정적 메서드처럼 노출".
        // Python calls WithCompanion.create(7), not WithCompanion.Companion.create(7).
        val create = UpcallTable.resolve("fixture.library.WithCompanion.create")
        assertEquals(CallableKind.FUNCTION, kindOf("fixture.library.WithCompanion.create"))

        val made = UpcallTable.invoke(create, arrayOf(7L))
        assertEquals(7L, (made as WithCompanion).id)
        assertEquals(1L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.WithCompanion.created"), arrayOf()))

        // and the companion itself is not a class of its own
        assertNull(ClassLookup.find("fixture.library.WithCompanion.Companion"))
        assertTrue(ClassLookup.require("fixture.library.WithCompanion").memberNames.contains("fixture.library.WithCompanion.create"))
    }

    @Test
    fun aCompanionPropertyIsAStaticAccessorAndAConstIsReadOnly() {
        assertEquals(CallableKind.STATIC_GETTER, kindOf("fixture.library.WithCompanion.TAG"))
        assertEquals("with-companion", UpcallTable.invoke(UpcallTable.resolve("fixture.library.WithCompanion.TAG"), arrayOf()))
        assertFalse(UpcallTable.resolve("fixture.library.WithCompanion.TAG=").isValid, "a const val has no setter")

        val setter = UpcallTable.resolve("fixture.library.WithCompanion.created=")
        assertEquals(CallableKind.STATIC_SETTER, kindOf("fixture.library.WithCompanion.created="))
        // args[0] is the value, not a receiver -- the whole reason STATIC_SETTER exists
        UpcallTable.invoke(setter, arrayOf(42L))
        assertEquals(42L, WithCompanion.created)
    }

    @Test
    fun anInstanceMemberAndACompanionMemberDoNotCollideBecauseTheInstanceOneWins() {
        // WithCompanion has an `id` property and a companion; nothing in the fixture collides
        // today, but the table must hold exactly one entry per name whatever the sources do.
        val names = ClassLookup.require("fixture.library.WithCompanion").memberNames
        assertEquals(names.distinct(), names)
    }

    // -------------------------------------------------------------------------------- objects

    @Test
    fun aKotlinObjectExposesItsMembersWithoutAReceiver() {
        assertEquals(ReflectedClassKind.OBJECT, ClassLookup.require("fixture.library.Registry").kind)
        assertEquals(CallableKind.FUNCTION, kindOf("fixture.library.Registry.ping"))
        assertEquals("pong", UpcallTable.invoke(UpcallTable.resolve("fixture.library.Registry.ping"), arrayOf()))

        UpcallTable.invoke(UpcallTable.resolve("fixture.library.Registry.size="), arrayOf(5L))
        assertEquals(5L, Registry.size)
        assertEquals(5L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.Registry.size"), arrayOf()))

        assertFalse(UpcallTable.resolve("fixture.library.Registry.<init>").isValid, "a singleton is not constructible")
    }

    // ----------------------------------------------------------------------------- interfaces

    @Test
    fun anInterfaceExposesItsMembersAndIsNotConstructible() {
        // An interface has no instances of its own; its entries exist so a Kotlin object handed
        // to Python can be called through the interface even when its concrete class does not
        // redeclare the member. PoliteGreeter never declares `greet`.
        val greeter = ClassLookup.require("fixture.library.Greeter")
        assertEquals(ReflectedClassKind.INTERFACE, greeter.kind)
        assertFalse(UpcallTable.resolve("fixture.library.Greeter.<init>").isValid)

        val polite = UpcallTable.invoke(UpcallTable.resolve("fixture.library.PoliteGreeter.<init>"), arrayOf("Hi"))!!
        val ref = HandleTable.register(polite)

        assertEquals(CallableKind.METHOD, kindOf("fixture.library.Greeter.greet"))
        assertEquals(
            "Hi, world!",
            UpcallTable.invoke(UpcallTable.resolve("fixture.library.Greeter.greet"), arrayOf(HandleTable.require(ref), "world")),
        )
        assertEquals(
            "Hi",
            UpcallTable.invoke(UpcallTable.resolve("fixture.library.Greeter.salutation"), arrayOf(HandleTable.require(ref))),
        )

        assertFalse(
            UpcallTable.resolve("fixture.library.PoliteGreeter.greet").isValid,
            "an inherited default implementation is reached through the interface, not duplicated",
        )
    }

    @Test
    fun anInterfaceCompanionIsFoldedIntoTheInterfaceLikeAClassCompanion() {
        val polite = UpcallTable.invoke(UpcallTable.resolve("fixture.library.Greeter.polite"), arrayOf())
        assertEquals(CallableKind.FUNCTION, kindOf("fixture.library.Greeter.polite"))
        assertEquals("Good day", (polite as fixture.library.Greeter).salutation)
    }

    // ---------------------------------------------------------------------------------- enums

    @Test
    fun everyEnumEntryIsAStaticAccessorForTheOneInstanceAndTheNamesAreCarriedAsData() {
        // The Python side builds an `enum.Enum` mirror at import time from `enumEntryNames` and
        // reaches each member through its accessor. `values()`/`entries` are deliberately absent:
        // they return collections and the boundary has no collection marshalling.
        val color = ClassLookup.require("fixture.library.Color")
        assertEquals(ReflectedClassKind.ENUM, color.kind)
        assertEquals(listOf("RED", "GREEN", "BLUE"), color.enumEntryNames)

        assertEquals(CallableKind.STATIC_GETTER, kindOf("fixture.library.Color.RED"))
        val red = UpcallTable.invoke(UpcallTable.resolve("fixture.library.Color.RED"), arrayOf())
        assertSame(Color.RED, red)
        assertSame(red, UpcallTable.invoke(UpcallTable.resolve("fixture.library.Color.RED"), arrayOf()))

        assertFalse(UpcallTable.resolve("fixture.library.Color.values").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.Color.entries").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.Color.<init>").isValid)
    }

    @Test
    fun anEnumCarriesNameOrdinalAndValueOfSoPythonCanMapAHandleBackOntoItsMirror() {
        val green = UpcallTable.invoke(UpcallTable.resolve("fixture.library.Color.GREEN"), arrayOf())!!

        assertEquals("GREEN", UpcallTable.invoke(UpcallTable.resolve("fixture.library.Color.name"), arrayOf(green)))
        assertEquals(1L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.Color.ordinal"), arrayOf(green)))
        assertSame(green, UpcallTable.invoke(UpcallTable.resolve("fixture.library.Color.valueOf"), arrayOf("GREEN")))
        assertEquals(CallableKind.FUNCTION, kindOf("fixture.library.Color.valueOf"))
    }

    @Test
    fun anEnumsOwnConstructorParametersAndMethodsAreInstanceMembers() {
        val high = UpcallTable.invoke(UpcallTable.resolve("fixture.library.Level.HIGH"), arrayOf())!!

        assertEquals(CallableKind.GETTER, kindOf("fixture.library.Level.weight"))
        assertEquals(10L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.Level.weight"), arrayOf(high)))

        assertEquals(CallableKind.METHOD, kindOf("fixture.library.Level.describe"))
        assertEquals("HIGH/10", UpcallTable.invoke(UpcallTable.resolve("fixture.library.Level.describe"), arrayOf(high)))
        assertSame(Level.HIGH, high)
    }

    @Test
    fun pythonInternalExcludesAWholeEnum() {
        assertNull(ClassLookup.find("fixture.library.HiddenEnum"))
        assertFalse(UpcallTable.resolve("fixture.library.HiddenEnum.A").isValid)
    }

    // ---------------------------------------------------------- what is deliberately not there

    @Test
    fun annotationClassesAreNotExposedAtAll() {
        // Applying an annotation is a compile-time act and reading one back needs runtime
        // reflection, which this design cannot have. An instance Python could build would have
        // nothing to be attached to.
        assertNull(ClassLookup.find("fixture.library.Marker"))
        assertFalse(UpcallTable.resolve("fixture.library.Marker.<init>").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.Marker.value").isValid)
    }

    @Test
    fun genericDeclarationsAreNotExposedBecauseTheGeneratedCastHasNoTypeToName() {
        assertNull(ClassLookup.find("fixture.library.Box"))
        assertFalse(UpcallTable.resolve("fixture.library.Box.<init>").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.Box.value").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.identity").isValid)
    }

    @Test
    fun compilerGeneratedDataClassMembersAreNotExposed() {
        // KSP does report `copy` and `componentN` for a data class (it does not report `equals`,
        // `hashCode` or `toString`); docs/design/binding-policy.md excludes compiler-generated members.
        assertNotNull(ClassLookup.find("fixture.library.Point"))
        assertTrue(UpcallTable.resolve("fixture.library.Point.x").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.Point.copy").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.Point.component1").isValid)
    }

    // ------------------------------------------------- shapes that used to break generated code

    @Test
    fun anAbstractClassExposesItsMembersButNoConstructor() {
        assertFalse(
            UpcallTable.resolve("fixture.library.AbstractBase.<init>").isValid,
            "'Cannot create an instance of an abstract class' -- the generated fragment used to carry one",
        )
        val child = UpcallTable.invoke(UpcallTable.resolve("fixture.library.ConcreteChild.<init>"), arrayOf())!!
        assertEquals("base:child", UpcallTable.invoke(UpcallTable.resolve("fixture.library.AbstractBase.describe"), arrayOf(child)))
    }

    @Test
    fun aParameterTypeWithTypeArgumentsKeepsThemSoTheFragmentCompiles() {
        // `args[0] as kotlin.collections.List` is "One type argument expected" -- this fixture
        // failing to compile is what the test is really pinning.
        assertEquals(2L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.listSize"), arrayOf(listOf("a", "b"))))
    }

    @Test
    fun nestedDeclarationsAreExposedUnderTheirOuterName() {
        assertEquals(ReflectedClassKind.ENUM, ClassLookup.require("fixture.library.Outer.State").kind)
        assertEquals(listOf("ON", "OFF"), ClassLookup.require("fixture.library.Outer.State").enumEntryNames)
        assertEquals("nested", UpcallTable.invoke(UpcallTable.resolve("fixture.library.Outer.Nested.hello"), arrayOf()))
    }

    @Test
    fun topLevelPropertiesAreExposedAsModuleAttributes() {
        assertEquals(CallableKind.STATIC_GETTER, kindOf("fixture.library.libraryVersion"))
        assertEquals("1.0", UpcallTable.invoke(UpcallTable.resolve("fixture.library.libraryVersion"), arrayOf()))

        UpcallTable.invoke(UpcallTable.resolve("fixture.library.mutableCounter="), arrayOf(3L))
        assertEquals(3L, UpcallTable.invoke(UpcallTable.resolve("fixture.library.mutableCounter"), arrayOf()))
    }

    // --------------------------------------------------- a `var` whose setter is not public API

    @Test
    fun aVarWithARestrictedSetterIsExposedReadOnly() {
        // ROADMAP §13: the scanner branched on `isMutable` alone. All three of these are mutable,
        // and none of their setters may appear in the table -- `private`/`protected` because the
        // generated assignment does not compile, `internal` because it compiles *only* by the
        // accident of the fragment sharing this module's compilation.
        for (name in listOf("privateSet", "protectedSet", "internalSet")) {
            val getter = "fixture.library.RestrictedSetters.$name"
            assertTrue(UpcallTable.resolve(getter).isValid, "the read side must stay exposed: $getter")
            assertEquals(CallableKind.GETTER, kindOf(getter))
            assertFalse(UpcallTable.resolve("$getter=").isValid, "a restricted setter must not be in the table: $getter=")
        }

        // The control, in the same class: an ordinary `var` still round-trips.
        val holder = UpcallTable.invoke(UpcallTable.resolve("fixture.library.RestrictedSetters.<init>"), arrayOf())!!
        val ref = HandleTable.register(holder)
        assertEquals(CallableKind.SETTER, kindOf("fixture.library.RestrictedSetters.openSet="))
        UpcallTable.invoke(
            UpcallTable.resolve("fixture.library.RestrictedSetters.openSet="),
            arrayOf(HandleTable.require(ref), 40L),
        )
        assertEquals(40L, (holder as RestrictedSetters).openSet)

        // and the read side really reads: a value moved by Kotlin is visible through the getter.
        val readPrivate = UpcallTable.resolve("fixture.library.RestrictedSetters.privateSet")
        assertEquals(1L, UpcallTable.invoke(readPrivate, arrayOf(holder)))
        holder.bumpAll()
        assertEquals(2L, UpcallTable.invoke(readPrivate, arrayOf(holder)))
    }

    @Test
    fun aRestrictedSetterIsAbsentFromTheOwningClassesMemberNamesToo() {
        // `ReflectedClass.memberNames` is what a Python mirror is built from, so an entry dropped
        // from the callable table has to be dropped here as well, or attribute access on the
        // mirror resolves to nothing.
        val members = ClassLookup.require("fixture.library.RestrictedSetters").memberNames
        assertFalse(members.contains("fixture.library.RestrictedSetters.privateSet="))
        assertFalse(members.contains("fixture.library.RestrictedSetters.protectedSet="))
        assertFalse(members.contains("fixture.library.RestrictedSetters.internalSet="))
        assertTrue(members.contains("fixture.library.RestrictedSetters.openSet="))
        for (member in members) {
            assertTrue(UpcallTable.resolve(member).isValid, "unresolved generated member: $member")
        }
    }

    @Test
    fun theStaticSetterPathHonoursSetterVisibilityAsWell() {
        // Top-level and `object` properties go through `staticPropertyEntries`, which carried the
        // same `isMutable`-only branch. "Cannot access 'topLevelPrivateSet': it is private in
        // file." was one of the three compile errors this fixture produced before the fix.
        assertTrue(UpcallTable.resolve("fixture.library.topLevelPrivateSet").isValid)
        assertEquals(CallableKind.STATIC_GETTER, kindOf("fixture.library.topLevelPrivateSet"))
        assertFalse(UpcallTable.resolve("fixture.library.topLevelPrivateSet=").isValid)

        assertTrue(UpcallTable.resolve("fixture.library.topLevelOpenSet=").isValid, "the control keeps its setter")

        assertTrue(UpcallTable.resolve("fixture.library.RestrictedRegistry.counted").isValid)
        assertFalse(UpcallTable.resolve("fixture.library.RestrictedRegistry.counted=").isValid)
        assertFalse(
            ClassLookup.require("fixture.library.RestrictedRegistry").memberNames
                .contains("fixture.library.RestrictedRegistry.counted="),
        )
    }

    @Test
    fun everyGeneratedMemberNameOfEveryGeneratedClassResolves() {
        // One walk over everything the generator emitted: a member name a class advertises but
        // the table cannot resolve would fail at the first Python attribute access instead.
        for (className in listOf(
            "fixture.library.WithCompanion",
            "fixture.library.Registry",
            "fixture.library.Greeter",
            "fixture.library.Color",
            "fixture.library.Level",
            "fixture.library.Outer.State",
            "fixture.library.Outer.Nested",
            "fixture.library.AbstractBase",
            "fixture.library.Point",
        )) {
            val reflected = assertNotNull(ClassLookup.find(className), "missing generated class: $className")
            for (member in reflected.memberNames) {
                assertTrue(UpcallTable.resolve(member).isValid, "unresolved generated member: $member")
            }
        }
    }
}
