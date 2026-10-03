package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #38 over `PropertyFixtures.kt`: unbounded generic functions bind with their type parameters
 * read as `kotlin.Any?`, member properties of public classes bind as `GETTER`/`SETTER` entries, and
 * top-level extension properties bind as `GETTER`s.
 *
 * ### Red before the implementation
 *
 * Every positive assertion here fails against a walker that reads no `kmClass.properties` and no
 * `kmPackage.properties` (no `GETTER` entry exists at all -- `single { ... }` throws "Collection
 * contains no element matching") and that declines every type parameter (`identity` and `emptyBox`
 * absent). The negative assertions (`largest`, `typeNameOf`, `squared`, the private and internal
 * properties, `Registry`, `Grams`) already hold before it, and are what must *keep* holding.
 */
class PropertyBindingTest {

    private val fixtureClasses: File
        get() {
            val location = Class.forName("fixture.artifactproperty.Counter").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isDirectory, "expected a directory of .class files: $it") }
        }

    private val entries by lazy {
        ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactproperty"))
    }

    private fun entry(name: String): ArtifactCallable =
        assertNotNull(entries.singleOrNull { it.name == name }, "$name is not bound; bound: ${entries.map { it.name }}")

    // ------------------------------------------------------------------------------ generics

    @Test
    fun anUnboundedTypeParameterIsReadAsNullableAnyAndWrittenOutAtTheCall() {
        val identity = entry("fixture.artifactproperty.identity")
        assertEquals(listOf("OBJECT"), identity.paramTags)
        assertEquals(listOf("kotlin.Any"), identity.paramTypeNames)
        assertEquals("kotlin.Any", identity.returnTypeName)
        assertEquals("OBJECT", identity.returnTag)
        assertEquals(
            "{ args -> (fixture.artifactproperty.identity<kotlin.Any?>((args[0] as kotlin.Any?))) }",
            identity.lambdaBody,
        )
    }

    /** `structuralEqualityPolicy()`'s shape: nothing to infer `T` from, so the type argument is the only way it compiles. */
    @Test
    fun aTypeParameterNothingCanInferIsStillWrittenOut() {
        val emptyBox = entry("fixture.artifactproperty.emptyBox")
        assertEquals(0, emptyBox.arity)
        assertEquals("fixture.artifactproperty.Box", emptyBox.returnTypeName)
        assertEquals("{ (fixture.artifactproperty.emptyBox<kotlin.Any?>()) }", emptyBox.lambdaBody)
    }

    @Test
    fun aBoundedOrReifiedTypeParameterIsStillDeclined() {
        val names = entries.map { it.name }
        assertTrue("fixture.artifactproperty.largest" !in names, names.toString())
        assertTrue("fixture.artifactproperty.typeNameOf" !in names, names.toString())
        val declarations = ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactproperty"))
        val reified = declarations.single { it.simpleName == "typeNameOf" }
        assertEquals(null, reified.bindingName)
        assertTrue(reified.declineReason!!.contains("reified"), reified.declineReason)
    }

    // ---------------------------------------------------------------------- member properties

    @Test
    fun aMemberValAndVarBindAsGetterAndSetter() {
        val count = entry("fixture.artifactproperty.Counter.count")
        assertEquals("GETTER", count.kind)
        assertEquals(0, count.arity)
        assertEquals("fixture.artifactproperty.Counter", count.receiverTypeName)
        assertEquals("INT", count.returnTag)
        assertEquals("{ args -> ((args[0] as fixture.artifactproperty.Counter).count).toLong() }", count.lambdaBody)

        val setCount = entry("fixture.artifactproperty.Counter.count=")
        assertEquals("SETTER", setCount.kind)
        assertEquals(1, setCount.arity)
        assertEquals(listOf("INT"), setCount.paramTags)
        assertEquals(listOf("kotlin.Int"), setCount.paramTypeNames)
        assertEquals("UNIT", setCount.returnTag)
        assertEquals(
            "{ args -> (args[0] as fixture.artifactproperty.Counter).count = (args[1] as Long).toInt(); Unit }",
            setCount.lambdaBody,
        )

        assertEquals("GETTER", entry("fixture.artifactproperty.Counter.name").kind)
        assertTrue(entries.none { it.name == "fixture.artifactproperty.Counter.name=" }, "a val has no setter")
        assertEquals(listOf("STRING"), entry("fixture.artifactproperty.Counter.note=").paramTags, "a nullable String var")
    }

    @Test
    fun aPropertyNobodyOutsideMayWriteHasNoSetterAndOneNobodyMayReadIsAbsent() {
        val names = entries.map { it.name }
        assertTrue("fixture.artifactproperty.Counter.guarded" in names, names.toString())
        assertTrue("fixture.artifactproperty.Counter.guarded=" !in names, "a private setter was bound")
        assertTrue(names.none { it.endsWith(".hidden") || it.endsWith(".internalProp") }, names.toString())
    }

    /** A property is not a constant the class publishes under its name: the constructor survives it. */
    @Test
    fun aPropertyDoesNotCostItsClassItsConstructor() {
        assertEquals(2, entry("fixture.artifactproperty.Counter").arity)
    }

    /** `MutableState<T>.value`: read through `Box<*>`, written through `Box<kotlin.Any?>`. */
    @Test
    fun aGenericVarIsReadThroughAStarAndWrittenThroughAny() {
        val content = entry("fixture.artifactproperty.Box.content")
        assertEquals("kotlin.Any", content.returnTypeName)
        assertEquals("{ args -> ((args[0] as fixture.artifactproperty.Box<*>).content) }", content.lambdaBody)
        assertEquals(
            "{ args -> (args[0] as fixture.artifactproperty.Box<kotlin.Any?>).content = (args[1] as kotlin.Any?); Unit }",
            entry("fixture.artifactproperty.Box.content=").lambdaBody,
        )
        assertEquals("GETTER", entry("fixture.artifactproperty.Box.size").kind)
        assertEquals("GETTER", entry("fixture.artifactproperty.Holder.held").kind, "an interface's abstract val")
    }

    @Test
    fun aBoundedClassTypeParameterDeclinesWhatMentionsItAndEverySetter() {
        val names = entries.map { it.name }
        assertTrue("fixture.artifactproperty.Bounded.item" !in names, names.toString())
        assertTrue("fixture.artifactproperty.Bounded.plain" in names, names.toString())
        assertTrue("fixture.artifactproperty.Bounded.plain=" !in names, names.toString())
    }

    @Test
    fun anObjectsAndAnOpenedValueClasssPropertiesAreNotGetters() {
        assertEquals("STATIC_GETTER", entry("fixture.artifactproperty.Registry.size").kind)
        assertTrue(entries.none { it.name.startsWith("fixture.artifactproperty.Grams.") }, entries.map { it.name }.toString())
    }

    // ------------------------------------------------------------------- extension properties

    /** `Icons.Filled.Add`'s shape. */
    @Test
    fun anExtensionPropertyBindsAsAGetterOnItsReceiver() {
        val doubled = entry("fixture.artifactproperty.doubled")
        assertEquals("GETTER", doubled.kind)
        assertEquals(0, doubled.arity)
        assertEquals("fixture.artifactproperty.Counter", doubled.receiverTypeName)
        assertEquals(
            listOf("import fixture.artifactproperty.doubled as artifact_ext_fixture_artifactproperty_doubled"),
            doubled.imports,
        )
        assertEquals(
            "{ args -> ((args[0] as fixture.artifactproperty.Counter).artifact_ext_fixture_artifactproperty_doubled).toLong() }",
            doubled.lambdaBody,
        )
        assertEquals("GETTER", entry("fixture.artifactproperty.mirrored").kind)
        assertTrue(entries.none { it.name == "fixture.artifactproperty.mirrored=" }, "an extension var's setter was bound")
    }

    @Test
    fun anExtensionPropertyOnAPrimitiveOrGenericIsDeclined() {
        val names = entries.map { it.name }
        assertTrue("fixture.artifactproperty.squared" !in names, names.toString())
        assertTrue("fixture.artifactproperty.contentOrNull" !in names, names.toString())
        val squared = ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactproperty"))
            .single { it.simpleName == "squared" }
        assertTrue(squared.declineReason!!.contains("receiver"), squared.declineReason)
    }

    // -------------------------------------------------------------------------- the fragment

    /** A property is not an extension slot at the boundary: `isExtension = false`, receiver still named. */
    @Test
    fun aRenderedGetterIsAGetterKindWithItsReceiverNamed() {
        val source = renderArtifactFragmentSource(
            ArtifactFragment("F", "m", listOf(entry("fixture.artifactproperty.Counter.count="))),
        )
        assertTrue("kind = python.multiplatform.reflection.CallableKind.SETTER" in source, source)
        assertTrue("isExtension = false" in source, source)
        assertTrue("receiverTypeName = \"fixture.artifactproperty.Counter\"" in source, source)
    }

    /** The model and the table agree for properties too. */
    @Test
    fun everyBoundPropertyHasAModelOfItsKind() {
        val declarations = ArtifactScanner.scanDeclarations(fixtureClasses, includePrefixes = listOf("fixture.artifactproperty"))
        val byKey = declarations.filter { it.bindingName != null }.associateBy { it.bindingName!! }
        entries.filter { it.kind == "GETTER" || it.kind == "SETTER" }.forEach { callable ->
            val model = assertNotNull(byKey[callable.name], "no model for ${callable.name}")
            assertEquals(callable.kind, model.kind, callable.name)
            assertEquals(callable.receiverTypeName, model.receiver?.qualifiedName, callable.name)
        }
        assertEquals(listOf("value"), byKey.getValue("fixture.artifactproperty.Counter.count=").parameters.map { it.name })
    }
}
