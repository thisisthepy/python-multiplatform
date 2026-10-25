package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #215: a `METHOD` is stubbed where the binding layer serves it -- as a method of its
 * receiver's stub class -- and never as a module attribute, the rule #38 gave properties.
 *
 * Red before the implementation: the renderer treated a `METHOD` row like a function, so
 * `fixture.Counter.reset` became `def reset()` in a module `fixture/Counter`, which also takes the
 * name `Counter` away from the class.
 */
class MemberFunctionStubTest {

    private val pkg = "fixture"
    private val counter = KotlinTypeModel("$pkg.Counter")
    private val int = KotlinTypeModel("kotlin.Int")
    private val string = KotlinTypeModel("kotlin.String")
    private val unit = KotlinTypeModel("kotlin.Unit")

    private val factory = DeclarationModel(
        simpleName = "newCounter",
        owner = pkg,
        ownerIsClass = false,
        receiver = null,
        parameters = emptyList(),
        returnType = counter,
        returnBoundaryTag = "OBJECT",
        bindingName = "$pkg.newCounter",
    )

    private fun method(binding: String, returns: KotlinTypeModel, tag: String, vararg params: DeclaredParameter) = DeclarationModel(
        simpleName = binding.substringAfterLast('.').substringBefore("__"),
        owner = "$pkg.Counter",
        ownerIsClass = true,
        receiver = counter,
        receiverBoundaryTag = "OBJECT",
        parameters = params.toList(),
        returnType = returns,
        returnBoundaryTag = tag,
        bindingName = binding,
        kind = "METHOD",
    )

    private val step = DeclaredParameter("step", int, declaresDefault = false, boundaryTag = "INT")
    private val label = DeclaredParameter("label", string, declaresDefault = false, boundaryTag = "STRING")

    private val reset = method("$pkg.Counter.reset", unit, "UNIT")
    private val stopNow = method("$pkg.Counter.stopNow", unit, "UNIT")
    private val addInt = method("$pkg.Counter.add__Int", int, "INT", step)
    private val addIntString = method("$pkg.Counter.add__Int_String", string, "STRING", step, label)

    private fun stub(vararg declarations: DeclarationModel): String {
        val files = renderKotlinFqnStubs(listOf(factory) + declarations)
        assertEquals(setOf("fixture/__init__.pyi"), files.keys, "a member made a module of its own")
        return files.getValue("fixture/__init__.pyi")
    }

    @Test
    fun aMemberIsAMethodOfItsReceiversClass() {
        val stub = stub(reset)
        assertTrue("class Counter:" in stub, stub)
        assertTrue("    def reset(self) -> None:" in stub, stub)
        assertFalse(Regex("^def reset", RegexOption.MULTILINE).containsMatchIn(stub), "a member became a module function")
        assertTrue("def newCounter() -> Counter:" in stub, stub)
    }

    @Test
    fun anOverloadedMemberHasItsExplicitNamesAndAnOverloadedBaseName() {
        val stub = stub(addInt, addIntString)
        assertTrue("    def add__Int(self, step: int) -> int:" in stub, stub)
        assertTrue("    def add__Int_String(self, step: int, label: str) -> str:" in stub, stub)
        assertTrue("    @_t.overload\n    def add(self, step: int) -> int:" in stub, stub)
        assertTrue("    @_t.overload\n    def add(self, step: int, label: str) -> str:" in stub, stub)
    }

    @Test
    fun aMembersPythonicNameIsStubbedBesideItsKotlinName() {
        // U-12: the binding layer serves `stop_now` beside `stopNow` on the proxy.
        val stub = stub(stopNow)
        assertTrue("    def stopNow(self) -> None:" in stub, stub)
        assertTrue("    def stop_now(self) -> None:" in stub, stub)
    }
}
