package fixture.app

import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.generated.FunctionTable
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ClassLookup
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `CallableKind.STATIC_GETTER`/`STATIC_SETTER` from the table KSP actually generated, all the way
 * to the Python text that will be `exec`'d for them.
 *
 * `PythonProxySourceTest` (commonTest) pins the renderer against hand-built entries and
 * `PythonProxyInstallTest` (python-multiplatform desktopTest) runs the result in a real CPython.
 * Neither says anything about whether the *generator* produces entries of the shape the renderer
 * expects. This does, and it needs no interpreter to do it: [PythonProxySource.render] is a pure
 * function, and this module has the generated `FunctionTable` but no CPython wiring.
 *
 * The four owner shapes are asserted separately because the renderer treats them differently, and
 * the difference is the whole design decision (see [PythonProxySource]'s "Static surfaces"):
 * a member of a class that is itself rendered as a Python `class` goes on that class's metaclass;
 * everything else becomes a live attribute of a module.
 */
class GeneratedStaticPropertyProxyTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(FunctionTable.fragments)
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    private fun render(): String = PythonProxySource.render(UpcallTable.entries(), ClassLookup.all())

    private fun assertRegisters(source: String, module: String, name: String, writable: Boolean) {
        val setter = if (writable) "_pm_h_\\d+" else "None"
        val pattern = Regex(
            "_pm_static_property\\(_pm_module\\('${Regex.escape(module)}'\\), " +
                "'${Regex.escape(name)}', _pm_h_\\d+, $setter\\)",
        )
        assertTrue(
            pattern.containsMatchIn(source),
            "no ${if (writable) "read/write" else "read-only"} registration for $module.$name in:\n$source",
        )
    }

    @Test
    fun theGeneratorEmitsStaticEntriesAtAllForEveryOwnerShapeTheRendererDistinguishes() {
        // Before asserting where they land, assert they exist: a renderer that handles a kind the
        // generator never emits proves nothing.
        val kinds = UpcallTable.entries().associate { it.name to it.kind }
        assertEquals(CallableKind.STATIC_GETTER, kinds["fixture.library.libraryVersion"], "top-level val")
        assertEquals(CallableKind.STATIC_GETTER, kinds["fixture.library.mutableCounter"], "top-level var")
        assertEquals(CallableKind.STATIC_SETTER, kinds["fixture.library.mutableCounter="], "top-level var setter")
        assertEquals(CallableKind.STATIC_GETTER, kinds["fixture.library.WithCompanion.TAG"], "companion const val")
        assertEquals(CallableKind.STATIC_GETTER, kinds["fixture.library.WithCompanion.created"], "companion var")
        assertEquals(CallableKind.STATIC_SETTER, kinds["fixture.library.WithCompanion.created="], "companion var setter")
        assertEquals(CallableKind.STATIC_GETTER, kinds["fixture.library.Registry.size"], "object var")
        assertEquals(CallableKind.STATIC_SETTER, kinds["fixture.library.Registry.size="], "object var setter")
        assertEquals(CallableKind.STATIC_GETTER, kinds["fixture.library.Color.RED"], "enum entry")
    }

    @Test
    fun aTopLevelPropertyBecomesALiveAttributeOfItsPackageModule() {
        val source = render()

        assertContains(source, "_pm_lookup('fixture.library.mutableCounter')")
        assertContains(source, "_pm_lookup('fixture.library.mutableCounter=')")
        assertRegisters(source, "fixture.library", "mutableCounter", writable = true)
        assertRegisters(source, "fixture.library", "libraryVersion", writable = false)
    }

    @Test
    fun aCompanionPropertyOfARenderedClassGoesOnThatClassesMetaclass() {
        val source = render()

        val metaclass = Regex("class (_pm_t_\\d+)\\(type\\):\\n(?:.|\\n)*?class WithCompanion\\(metaclass=\\1\\):")
            .find(source)
        assertTrue(metaclass != null, "WithCompanion's statics need a metaclass on the class itself:\n$source")

        assertContains(source, "    def TAG(cls):")
        assertContains(source, "    def created(cls):")
        assertContains(source, "    @created.setter")
        assertContains(source, "    def created(cls, a0):")
        assertFalse(
            source.contains("_pm_static_property(_pm_module('fixture.library.WithCompanion')"),
            "a rendered class owns its statics; publishing them on a module of the same name would " +
                "put them somewhere the class rendering then overwrites",
        )
    }

    @Test
    fun anObjectsPropertyStaysOnTheModuleItsOwnFunctionsAlreadyUse() {
        val source = render()

        // `Registry.ping` is a FUNCTION entry named `fixture.library.Registry.ping`, so it has
        // always been published into a module named after the object. `Registry.size` has to
        // follow it there or the two would resolve against different Python objects.
        assertContains(source, "setattr(_pm_module('fixture.library.Registry'), 'ping', ")
        assertRegisters(source, "fixture.library.Registry", "size", writable = true)
        assertRegisters(source, "fixture.library.Registry", "label", writable = false)
    }

    @Test
    fun anEnumEntryLandsAsAModuleAttributeBecauseAnEnumIsNotRenderedAsAClass() {
        val source = render()

        assertRegisters(source, "fixture.library.Color", "RED", writable = false)
        assertRegisters(source, "fixture.library.Color", "BLUE", writable = false)
    }

    @Test
    fun aCompanionFunctionOfARenderedClassGoesOnTheSameMetaclassItsCompanionPropertiesDo() {
        // The other half of a companion, and the one that used to be lost: KSP emits
        // `WithCompanion.create` as a `CallableKind.FUNCTION`, which is by name indistinguishable
        // from `Registry.ping` -- an `object`'s function, which must stay on its module. Only the
        // `ReflectedClassKind` tells them apart, so both are asserted here against the real table.
        val kinds = UpcallTable.entries().associate { it.name to it.kind }
        assertEquals(CallableKind.FUNCTION, kinds["fixture.library.WithCompanion.create"], "companion fun")
        assertEquals(CallableKind.FUNCTION, kinds["fixture.library.Greeter.polite"], "interface companion fun")

        val source = render()

        assertContains(source, "    def create(cls, a0):")
        assertContains(source, "    def polite(cls):")
        assertFalse(
            source.contains("_pm_module('fixture.library.WithCompanion')"),
            "a module of that name is exactly what the class rendering overwrites:\n$source",
        )
        assertFalse(source.contains("_pm_module('fixture.library.Greeter')"))
        // and the same metaclass carries the companion's properties: one companion, one type object
        val metaclass = Regex("class (_pm_t_\\d+)\\(type\\):\\n(?:.|\\n)*?class WithCompanion\\(metaclass=\\1\\):")
            .find(source)
        assertTrue(metaclass != null, "WithCompanion needs one metaclass for both halves:\n$source")
        assertContains(metaclass.value, "    def create(cls, a0):")
        assertContains(metaclass.value, "    def TAG(cls):")
    }

    @Test
    fun aVarWhoseSetterIsNotPublicApiRendersReadOnlyRatherThanGainingOneBack() {
        // ROADMAP §13's rule, carried through to the last stage: the table has no STATIC_SETTER
        // entry for these, and this stage must not invent one. What it emits instead is a
        // registration whose setter slot is `None`, which raises on assignment.
        val source = render()

        assertFalse(UpcallTable.resolve("fixture.library.topLevelPrivateSet=").isValid, "premise")
        assertRegisters(source, "fixture.library", "topLevelPrivateSet", writable = false)
        assertRegisters(source, "fixture.library", "topLevelOpenSet", writable = true)

        assertFalse(UpcallTable.resolve("fixture.library.RestrictedRegistry.counted=").isValid, "premise")
        assertRegisters(source, "fixture.library.RestrictedRegistry", "counted", writable = false)
    }
}
