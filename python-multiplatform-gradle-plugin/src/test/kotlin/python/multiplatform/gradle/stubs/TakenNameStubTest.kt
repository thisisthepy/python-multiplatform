package python.multiplatform.gradle.stubs

import python.multiplatform.gradle.model.DeclarationModel
import python.multiplatform.gradle.model.DeclaredParameter
import python.multiplatform.gradle.model.KotlinTypeModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #217: a type whose name a same-named factory function takes keeps a stub class.
 *
 * `kotlinx.coroutines.Job` is an interface and `fun Job(parent: Job? = null): CompletableJob` a
 * function, and at run time `kotlinx.coroutines.Job` is the function. The stub used to give the type
 * no class at all: the factory returned `Any`, and its properties became "is not stubbed" comments.
 * The class is now `_Job`, a name only the stub uses, so `Job` stays the function and the type keeps
 * its members.
 *
 * Red before the implementation: `def Handle() -> _t.Any`, no `class _Handle`, and a `Kotlin property:
 * ... is not stubbed` comment.
 */
class TakenNameStubTest {

    private val pkg = "fixture"
    private val handle = KotlinTypeModel("$pkg.Handle")
    private val bool = KotlinTypeModel("kotlin.Boolean")

    private val factory = DeclarationModel(
        simpleName = "Handle",
        owner = pkg,
        ownerIsClass = false,
        receiver = null,
        parameters = emptyList(),
        returnType = handle,
        returnBoundaryTag = "OBJECT",
        bindingName = "$pkg.Handle",
    )

    private val use = DeclarationModel(
        simpleName = "use",
        owner = pkg,
        ownerIsClass = false,
        receiver = null,
        parameters = listOf(DeclaredParameter("handle", handle, declaresDefault = false, boundaryTag = "OBJECT")),
        returnType = KotlinTypeModel("kotlin.Unit"),
        returnBoundaryTag = "UNIT",
        bindingName = "$pkg.use",
    )

    private val active = DeclarationModel(
        simpleName = "active",
        owner = "$pkg.Handle",
        ownerIsClass = true,
        receiver = handle,
        receiverBoundaryTag = "OBJECT",
        parameters = emptyList(),
        returnType = bool,
        returnBoundaryTag = "BOOLEAN",
        bindingName = "$pkg.Handle.active",
        kind = "GETTER",
    )

    private val stop = DeclarationModel(
        simpleName = "stop",
        owner = "$pkg.Handle",
        ownerIsClass = true,
        receiver = handle,
        receiverBoundaryTag = "OBJECT",
        parameters = emptyList(),
        returnType = KotlinTypeModel("kotlin.Unit"),
        returnBoundaryTag = "UNIT",
        bindingName = "$pkg.Handle.stop",
        kind = "METHOD",
    )

    private fun stub() = renderKotlinFqnStubs(listOf(factory, use, active, stop)).getValue("fixture/__init__.pyi")

    @Test
    fun theFunctionKeepsTheNameAndReturnsTheStubPrivateClass() {
        val stub = stub()
        assertTrue("def Handle() -> _Handle:" in stub, stub)
        assertFalse(Regex("^class Handle\\b", RegexOption.MULTILINE).containsMatchIn(stub), "a class took the function's name")
    }

    @Test
    fun theTypeKeepsItsPropertiesAndMembers() {
        val stub = stub()
        assertTrue("class _Handle:" in stub, stub)
        assertTrue("    def active(self) -> bool:" in stub, stub)
        assertTrue("    def stop(self) -> None:" in stub, stub)
        assertFalse("is not stubbed" in stub, stub)
    }

    @Test
    fun everyUseOfTheTypeIsTheStubPrivateClass() {
        assertTrue("def use(handle: _Handle) -> None:" in stub(), stub())
    }
}
