package python.multiplatform.ksp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRenderingTest {

    @Test
    fun fragmentSourceImplementsTheRuntimeInterfaceAndCarriesTheModuleName() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = listOf(
                CallableEntryModel(
                    name = "my.lib.greet",
                    arity = 1,
                    paramTags = listOf(Tag.STRING),
                    returnTag = Tag.STRING,
                    kind = "FUNCTION",
                    lambdaBody = "{ args -> greet(args[0] as String) }",
                ),
            ),
            classes = emptyList(),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("package python.multiplatform.generated.fragments"))
        assertTrue(src.contains("object Fragment_my_lib : python.multiplatform.reflection.FunctionTableFragment"))
        assertTrue(src.contains("override val moduleName: String = \"my_lib\""))
        assertTrue(src.contains("name = \"my.lib.greet\""))
        assertTrue(src.contains("{ args -> greet(args[0] as String) }"))
        // no fragment is public API for a user to call directly; but it must not be `internal`,
        // or the app module's generated aggregator cannot reference it across the module
        // boundary. See renderFragmentSource's doc comment.
        assertTrue(!src.contains("internal object Fragment_my_lib"))
    }

    @Test
    fun fragmentSourceOmitsTraverseArgumentForClassesWithoutPythonFields() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = emptyList(),
            classes = listOf(
                ClassModel(name = "my.lib.Counter", memberNames = listOf("my.lib.Counter.<init>"), traverseBody = null),
            ),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("name = \"my.lib.Counter\""))
        assertTrue(!src.contains("traverse ="))
    }

    @Test
    fun fragmentSourceIncludesTraverseArgumentForClassesWithPythonFields() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = emptyList(),
            classes = listOf(
                ClassModel(
                    name = "my.lib.RefHolder",
                    memberNames = listOf("my.lib.RefHolder.<init>"),
                    traverseBody = "{ obj, visit -> (obj as RefHolder).field?.let { visit(it.pointer.toRawValue()) } }",
                ),
            ),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("traverse = { obj, visit ->"))
    }

    @Test
    fun aggregatorSourceReferencesEveryDiscoveredFragmentExplicitly() {
        val src = renderAggregatorSource(
            listOf(
                "python.multiplatform.generated.fragments.Fragment_library_module",
                "python.multiplatform.generated.fragments.Fragment_app_module",
            ),
        )

        assertTrue(src.contains("package python.multiplatform.generated"))
        assertTrue(src.contains("object FunctionTable"))
        assertTrue(src.contains("python.multiplatform.generated.fragments.Fragment_library_module,"))
        assertTrue(src.contains("python.multiplatform.generated.fragments.Fragment_app_module,"))
        assertTrue(src.contains("UpcallTable.install(fragments)"))
    }

    @Test
    fun aggregatorSourceWithNoFragmentsStillProducesValidSyntaxShape() {
        val src = renderAggregatorSource(emptyList())

        assertTrue(src.contains("val fragments: List<python.multiplatform.reflection.FunctionTableFragment> = listOf(\n    )"))
    }

    @Test
    fun fragmentSourceSuppressesUncheckedCastsBecauseEveryArgumentReadIsOne() {
        // Reading `args[0] as List<String>` out of an `Array<Any?>` is unchecked by
        // construction; without the file-level suppression every generated fragment with a
        // generic parameter buries the build in warnings.
        val src = renderFragmentSource(FragmentModel("my_lib", emptyList(), emptyList()))

        assertTrue(src.startsWith("@file:Suppress(\"UNCHECKED_CAST\")"))
    }

    @Test
    fun classSourceCarriesTheKotlinShapeAndAnEnumsEntryNames() {
        // An interface has no constructor and an enum has a fixed instance set: the Python side
        // picks a different proxy shape for each, and cannot infer it from member names.
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = emptyList(),
            classes = listOf(
                ClassModel(
                    name = "my.lib.Greeter",
                    memberNames = listOf("my.lib.Greeter.greet"),
                    traverseBody = null,
                    kind = "INTERFACE",
                ),
                ClassModel(
                    name = "my.lib.Color",
                    memberNames = listOf("my.lib.Color.RED"),
                    traverseBody = null,
                    kind = "ENUM",
                    enumEntryNames = listOf("RED", "GREEN"),
                ),
            ),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("kind = python.multiplatform.reflection.ReflectedClassKind.INTERFACE"))
        assertTrue(src.contains("kind = python.multiplatform.reflection.ReflectedClassKind.ENUM"))
        assertTrue(src.contains("enumEntryNames = listOf(\"RED\", \"GREEN\")"))
    }

    @Test
    fun anOrdinaryClassRendersTheDefaultKindAndNoEntryNames() {
        val src = renderFragmentSource(
            FragmentModel(
                moduleName = "my_lib",
                entries = emptyList(),
                classes = listOf(ClassModel("my.lib.Counter", listOf("my.lib.Counter.<init>"), null)),
            ),
        )

        assertTrue(src.contains("kind = python.multiplatform.reflection.ReflectedClassKind.CLASS"))
        assertTrue(!src.contains("enumEntryNames"))
    }

    // -------------------------------------------------------------- the declaration metadata
    //
    // `fbab1a68` put `returnTypeName` on `ExposedCallable` as the gate that lets
    // `PythonProxySource` own a `TypeTag.OBJECT` result: `OBJECT` also covers a `PyObject` that
    // happens to be an `int`, and owning one of those would release a handle nobody issued, so
    // ownership is granted only when the producer names the Kotlin type. The walker supplies the
    // name; at that commit KSP did not, which is why `WalkedArtifactComposeModifierTest`'s
    // `emptyModifier()` -- a KSP entry -- leaked one handle per call while the walked links beside
    // it did not. These tests pin the renderer's half of the fix: given a model that carries the
    // metadata, the generated source must say so.

    @Test
    fun aReturnTypeNameOnTheModelIsRenderedOnTheEntry() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = listOf(
                CallableEntryModel(
                    name = "my.lib.Counter.<init>",
                    arity = 1,
                    paramTags = listOf(Tag.INT),
                    returnTag = Tag.OBJECT,
                    kind = "CONSTRUCTOR",
                    lambdaBody = "{ args -> Counter((args[0] as Long)) }",
                    returnTypeName = "my.lib.Counter",
                ),
            ),
            classes = emptyList(),
        )

        val src = renderFragmentSource(model)

        assertTrue(
            src.contains("returnTypeName = \"my.lib.Counter\","),
            "the gate PythonProxySource reads must be rendered, or a KSP-generated constructor " +
                "result stays a bare, leaking handle:\n$src",
        )
    }

    @Test
    fun aNullReturnTypeNameIsNotRenderedAtAll() {
        // Every entry in every fragment would otherwise carry `returnTypeName = null,`, which
        // means nothing beyond what the runtime default already says.
        val src = renderFragmentSource(
            FragmentModel(
                moduleName = "my_lib",
                entries = listOf(
                    CallableEntryModel("my.lib.greet", 0, emptyList(), Tag.STRING, "FUNCTION", "{ greet() }"),
                ),
                classes = emptyList(),
            ),
        )

        assertTrue(!src.contains("returnTypeName"))
    }

    @Test
    fun paramNamesAndParamTypeNamesOnTheModelAreRenderedOnTheEntry() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = listOf(
                CallableEntryModel(
                    name = "my.lib.scale",
                    arity = 2,
                    paramTags = listOf(Tag.INT, Tag.INT),
                    returnTag = Tag.INT,
                    kind = "FUNCTION",
                    lambdaBody = "{ args -> scale((args[0] as Long).toInt(), (args[1] as Long).toInt()).toLong() }",
                    paramNames = listOf("value", "by"),
                    paramTypeNames = listOf("kotlin.Int", "kotlin.Int"),
                ),
            ),
            classes = emptyList(),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("paramNames = listOf(\"value\", \"by\"),"))
        assertTrue(src.contains("paramTypeNames = listOf(\"kotlin.Int\", \"kotlin.Int\"),"))
    }

    @Test
    fun emptyParamNamesAndParamTypeNamesAreNotRenderedAtAll() {
        val src = renderFragmentSource(
            FragmentModel(
                moduleName = "my_lib",
                entries = listOf(
                    CallableEntryModel("my.lib.greet", 0, emptyList(), Tag.STRING, "FUNCTION", "{ greet() }"),
                ),
                classes = emptyList(),
            ),
        )

        assertTrue(!src.contains("paramNames"))
        assertTrue(!src.contains("paramTypeNames"))
    }

    @Test
    fun paramHasDefaultOnTheModelIsRenderedOnTheEntry() {
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = listOf(
                CallableEntryModel(
                    name = "my.lib.pad",
                    arity = 2,
                    paramTags = listOf(Tag.INT, Tag.INT),
                    returnTag = Tag.INT,
                    kind = "FUNCTION",
                    lambdaBody = "{ args -> pad((args[0] as Long), (args[1] as Long)) }",
                    paramHasDefault = listOf(false, true),
                ),
            ),
            classes = emptyList(),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("paramHasDefault = listOf(false, true),"))
    }

    @Test
    fun emptyParamHasDefaultIsNotRenderedAtAll() {
        val src = renderFragmentSource(
            FragmentModel(
                moduleName = "my_lib",
                entries = listOf(
                    CallableEntryModel("my.lib.greet", 0, emptyList(), Tag.STRING, "FUNCTION", "{ greet() }"),
                ),
                classes = emptyList(),
            ),
        )

        assertTrue(!src.contains("paramHasDefault"))
    }

    // ------------------------------------------------------------------------ the suspend flag

    @Test
    fun aSuspendingEntryCarriesTheFlagTheTrampolineBranchesOn() {
        // `docs/design/upcall-async-design.md` §5: the trampoline has to know, before it looks at the
        // result, whether what came back is the value or a PendingCall. The flag is separate from
        // `kind` because the two are orthogonal -- a suspending *method* still has its receiver in
        // args[0], and doubling CallableKind would have made every hasReceiver decision restate it.
        val model = FragmentModel(
            moduleName = "my_lib",
            entries = listOf(
                CallableEntryModel(
                    name = "my.lib.fetch",
                    arity = 1,
                    paramTags = listOf(Tag.STRING),
                    returnTag = Tag.STRING,
                    kind = "FUNCTION",
                    lambdaBody =
                        "{ args -> python.multiplatform.ffi.upcall.PendingCall.start { fetch(args[0] as String) } }",
                    isSuspend = true,
                ),
            ),
            classes = emptyList(),
        )

        val src = renderFragmentSource(model)

        assertTrue(src.contains("isSuspend = true"))
        // The declared return type, not the PendingCall: the fast path marshals the real value
        // with this tag, and the Future's set_result uses the same one.
        assertTrue(src.contains("returnType = python.multiplatform.reflection.TypeTag.STRING"))
        assertTrue(src.contains("python.multiplatform.ffi.upcall.PendingCall.start {"))
    }

    @Test
    fun anOrdinaryEntryDoesNotMentionTheSuspendFlagAtAll() {
        // Every entry in every fragment would otherwise carry `isSuspend = false`; the default on
        // the runtime class keeps generated source the size it was.
        val src = renderFragmentSource(
            FragmentModel(
                moduleName = "my_lib",
                entries = listOf(
                    CallableEntryModel("my.lib.greet", 0, emptyList(), Tag.STRING, "FUNCTION", "{ greet() }"),
                ),
                classes = emptyList(),
            ),
        )

        assertTrue(!src.contains("isSuspend"))
    }

    @Test
    fun duplicateEntryNamesAreDroppedBeforeTheyReachTheTable() {
        // Legal Kotlin: `class Foo { val x = 1; companion object { val x = 2 } }` yields two
        // entries called `pkg.Foo.x` once companion members are folded into the owner. Two
        // entries under one name make UpcallTable.resolve return whichever won the race; the
        // instance member is the one that keeps its receiver contract, so it wins deliberately.
        val instance = CallableEntryModel("p.Foo.x", 0, emptyList(), Tag.INT, "GETTER", "{ args -> 1L }")
        val static = CallableEntryModel("p.Foo.x", 0, emptyList(), Tag.INT, "STATIC_GETTER", "{ 2L }")
        val other = CallableEntryModel("p.Foo.y", 0, emptyList(), Tag.INT, "GETTER", "{ args -> 3L }")

        val deduped = listOf(instance, static, other).distinctByName()

        assertEquals(listOf("p.Foo.x", "p.Foo.y"), deduped.map { it.name })
        assertEquals("GETTER", deduped[0].kind)
    }

    // ------------------------------------------------------------------- the install-table seam

    @Test
    fun theInstallSeamActualLandsInTheDeclarationsOwnPackageAndCallsTheAggregator() {
        // ROADMAP §13: `FunctionTable` is nameable only from the compilation that generated it,
        // so this file is the one place the call can be written -- and it has to land in the
        // `expect`'s own package or it does not match it.
        val src = renderInstallSeamSource(
            InstallSeamModel(packageName = "com.example.app", simpleName = "installTable", isInternal = false),
        )

        assertTrue(src.contains("package com.example.app"))
        assertTrue(src.contains("actual fun installTable() {"))
        assertTrue(src.contains("python.multiplatform.generated.FunctionTable.installInto()"))
        assertTrue(!src.contains("internal actual"))
    }

    @Test
    fun theInstallSeamActualIsMarkedPythonInternal() {
        // It is a public top-level function in the user's own package. Without the annotation, an
        // incremental round that hands the scanner its own previous output would expose a
        // Python-callable that reinstalls the table underneath its caller.
        val src = renderInstallSeamSource(
            InstallSeamModel(packageName = "com.example.app", simpleName = "installTable", isInternal = false),
        )

        assertTrue(src.contains("@python.multiplatform.reflection.PythonInternal"))
    }

    @Test
    fun anInternalSeamGetsAnInternalActualBecauseExpectAndActualMustAgree() {
        val src = renderInstallSeamSource(
            InstallSeamModel(packageName = "com.example.app", simpleName = "installTable", isInternal = true),
        )

        assertTrue(src.contains("internal actual fun installTable() {"))
    }

    @Test
    fun seamFileNamesAreDerivedFromTheDeclarationSoTwoSeamsDoNotCollide() {
        assertEquals("InstallUpcallTable_installTable", installSeamFileName("installTable"))
        assertTrue(installSeamFileName("a") != installSeamFileName("b"))
    }
}
