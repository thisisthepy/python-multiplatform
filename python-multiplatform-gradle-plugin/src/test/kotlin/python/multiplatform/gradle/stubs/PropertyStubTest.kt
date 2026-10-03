package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #38: a `GETTER`/`SETTER` is stubbed where the binding layer serves it -- as a `@property` of
 * its receiver's stub class -- and never as a module attribute.
 *
 * Red before the implementation: the renderer treated a property row like any other, so
 * `MutableState.value` became `def value()` in a module `androidx/compose/runtime/MutableState`, and
 * the `Kotlin property:` marker existed nowhere.
 */
class PropertyStubTest {

    private val runtime = "androidx.compose.runtime"
    private val mutableState = KotlinTypeModel("$runtime.MutableState")
    private val any = KotlinTypeModel("kotlin.Any", isNullable = true)

    private val factory = DeclarationModel(
        simpleName = "mutableStateOf",
        owner = runtime,
        ownerIsClass = false,
        receiver = null,
        parameters = listOf(DeclaredParameter("value", any, declaresDefault = false, boundaryTag = "OBJECT")),
        returnType = mutableState,
        returnBoundaryTag = "OBJECT",
        bindingName = "$runtime.mutableStateOf",
    )

    private val getter = DeclarationModel(
        simpleName = "value",
        owner = "$runtime.MutableState",
        ownerIsClass = true,
        receiver = mutableState,
        receiverBoundaryTag = "OBJECT",
        parameters = emptyList(),
        returnType = any,
        returnBoundaryTag = "OBJECT",
        bindingName = "$runtime.MutableState.value",
        kind = "GETTER",
    )

    private val setter = getter.copy(
        kind = "SETTER",
        bindingName = "$runtime.MutableState.value=",
        parameters = listOf(DeclaredParameter("value", any, declaresDefault = false, boundaryTag = "OBJECT")),
        returnType = KotlinTypeModel("kotlin.Unit"),
        returnBoundaryTag = "UNIT",
    )

    @Test
    fun aVarIsAPropertyWithASetterOnItsReceiversClass() {
        val files = renderKotlinFqnStubs(listOf(factory, getter, setter))
        assertEquals(setOf("androidx/compose/runtime/__init__.pyi"), files.keys, "a property made a module of its own")
        val stub = files.getValue("androidx/compose/runtime/__init__.pyi")
        assertTrue("class MutableState:" in stub, stub)
        assertTrue("    @property\n    def value(self) -> _t.Any | None:" in stub, stub)
        assertTrue("    @value.setter\n    def value(self, value: _t.Any | None) -> None:" in stub, stub)
        assertTrue("Kotlin property: androidx.compose.runtime.MutableState.value " in stub, stub)
        assertTrue("Kotlin property: androidx.compose.runtime.MutableState.value=" in stub, stub)
        assertFalse(Regex("^def value", RegexOption.MULTILINE).containsMatchIn(stub), "a property became a module function")
    }

    @Test
    fun aValHasNoSetter() {
        val stub = renderKotlinFqnStubs(listOf(factory, getter)).getValue("androidx/compose/runtime/__init__.pyi")
        assertTrue("@property" in stub, stub)
        assertFalse("setter" in stub, stub)
    }

    /**
     * An extension property is stubbed on its receiver's class too, not in the package that declares
     * it -- `Icons.Filled.Add` is read off `Icons.Default`, never imported from `icons.filled`.
     */
    @Test
    fun anExtensionPropertyIsStubbedOnItsReceiver() {
        val receiver = KotlinTypeModel("p.Counter")
        val doubled = DeclarationModel(
            simpleName = "doubled",
            owner = "p.ext",
            ownerIsClass = false,
            receiver = receiver,
            receiverBoundaryTag = "OBJECT",
            parameters = emptyList(),
            returnType = KotlinTypeModel("kotlin.Int"),
            returnBoundaryTag = "INT",
            bindingName = "p.ext.doubled",
            kind = "GETTER",
        )
        val files = renderKotlinFqnStubs(listOf(doubled))
        assertEquals(setOf("p/__init__.pyi"), files.keys)
        val stub = files.getValue("p/__init__.pyi")
        assertTrue("class Counter:" in stub, stub)
        assertTrue("    def doubled(self) -> int:" in stub, stub)
        assertTrue("Kotlin property: p.ext.doubled " in stub, stub)
    }

    /**
     * A receiver whose name its own module already spends on a function has no stub class to put
     * the property in; the key is still accounted for, as a comment, rather than silently absent.
     */
    @Test
    fun aPropertyWhoseReceiverHasNoStubClassIsStillAccountedFor() {
        val receiver = KotlinTypeModel("p.Counter")
        val constructorLike = DeclarationModel(
            simpleName = "Counter",
            owner = "p",
            ownerIsClass = false,
            receiver = null,
            parameters = emptyList(),
            returnType = receiver,
            returnBoundaryTag = "OBJECT",
            bindingName = "p.Counter",
        )
        val count = DeclarationModel(
            simpleName = "count",
            owner = "p.Counter",
            ownerIsClass = true,
            receiver = receiver,
            receiverBoundaryTag = "OBJECT",
            parameters = emptyList(),
            returnType = KotlinTypeModel("kotlin.Int"),
            returnBoundaryTag = "INT",
            bindingName = "p.Counter.count",
            kind = "GETTER",
        )
        val stub = renderKotlinFqnStubs(listOf(constructorLike, count)).getValue("p/__init__.pyi")
        assertTrue(Regex("^# Kotlin property: p\\.Counter\\.count is not stubbed", RegexOption.MULTILINE).containsMatchIn(stub), stub)
        assertFalse("class Counter" in stub, stub)
    }
}
