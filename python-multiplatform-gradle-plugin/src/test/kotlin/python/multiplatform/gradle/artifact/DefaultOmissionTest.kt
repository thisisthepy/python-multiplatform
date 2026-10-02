package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/archive/pythonx-adapter-design.md` §4.5 closed for everything the walker actually binds: a Kotlin
 * default value is reached by **not writing the argument**, in generated Kotlin *source*.
 *
 * ### What the problem was
 *
 * §4.5 measures it: `material3.Text` declares 16--18 parameters of which **one** is required,
 * `Button` 10 of which two are, and across the `Modifier` surface **254 of 435 parameters (58%)
 * declare a default**. Every generated body passed every argument, so a Python caller had to supply
 * all of them -- and the values it would have had to supply (`TextStyle.Default`, `Alignment.Center`)
 * are objects the type gate does not bind, so it could not have supplied them at all. "Pass
 * everything" is not merely inconvenient; for most of the bound surface it is impossible.
 *
 * ### Why the answer is in the generated Kotlin and not in the table
 *
 * §4.5 lists four candidates. Three are closed:
 *
 * | candidate | what closes it |
 * |---|---|
 * | one entry per **arity prefix** | cannot express "skip the middle one", which is how Compose is written -- and it multiplies table keys, each of which `PythonProxySource` publishes by leaf name |
 * | the compiler's **`$default` synthetic** | `ACC_SYNTHETIC`, so Kotlin source cannot name it; and `javap -p` over `ButtonKt` shows no `Button$default` exists at all |
 * | a **generated wrapper restating the defaults** | metadata carries the *flag*, never the value |
 *
 * The fourth -- **presence branching in the generated lambda** -- is the one that survives, and its
 * stated objection ("2^15 branches for `Text`") is an objection to an *unbounded* version of it.
 * `Text` is a `@Composable` and no composable is in the table at all (the arity check in
 * [ArtifactScanner] declines every one of them for its synthetic `$composer`/`$changed`
 * parameters). Measured over everything the walker does bind today -- all of
 * `foundation-layout-desktop-1.6.11`, `kotlin-stdlib`'s `kotlin.text` and JUnit 4 --
 * **the widest declaration declares four defaults**, so the exponent is 4 and the branch count is
 * 16. [MAX_OMITTABLE_PARAMETERS] is what keeps it that way for an artefact nobody has measured yet.
 *
 * ### The sentinel, and why one exists at all
 *
 * A branch tests `args[i] == null`. That works because the boundary already carries `null` for every
 * tag: `UpcallTrampoline.toKotlin` opens with *"`None` is how Python spells a null argument, whatever
 * the tag says it should have been"*, before it ever looks at the tag. So no new marshalling
 * category, no change to `ExposedCallable`, and no change to any of the five bootstraps.
 *
 * It also cannot collide with a real value in the primitive cases, which is the thing worth pinning:
 * `resolveKotlinType` **declines a nullable primitive and a nullable value class outright** (a `null`
 * arriving for an `Int?` would throw inside `(args[0] as Long)`), so a `FLOAT`/`INT`/`BOOLEAN` slot
 * can never legitimately be `null`. The one case where the sentinel is not free is a **nullable
 * object** parameter that declares a default: `None` there means "omitted" and can no longer mean
 * "null". That is stated in [ArtifactScanner]'s KDoc rather than worked around.
 */
class DefaultOmissionTest {

    /** `DefaultFixtures.kt`, compiled into this module's own test classes and then met as bytecode.
     * The same lookup [ArtifactScannerTest] uses for its value-class fixture. */
    private val fixtureClasses: File
        get() {
            val location = Class.forName("fixture.artifactdefaults.Bag").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isDirectory, "expected a directory of .class files: $it") }
        }

    private fun walk(): List<ArtifactCallable> =
        ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactdefaults"))

    private fun entry(name: String): ArtifactCallable =
        walk().single { it.name == "fixture.artifactdefaults.$name" }

    /** One space, no line breaks: the assertions are about the *calls* generated, not about how the
     * renderer lays them out. */
    private fun String.flattened(): String = replace(Regex("\\s+"), " ").trim()

    /**
     * The judgement, at the level the generator can be asked about it: a body that can be called
     * with the required argument alone.
     *
     * `greet(name, punctuation = "!")` produces two call expressions -- one passing both, one
     * passing only `name` -- and the second is the one a Python `greet("hi")` reaches. Note the
     * *named* argument: an omission that is not a trailing one could not be positional, so every
     * partial branch names what it passes and only the all-present branch stays positional.
     */
    @Test
    fun aDefaultedParameterGetsACallThatDoesNotPassIt() {
        val body = entry("greet").lambdaBody.flattened()
        assertTrue(
            body.contains("fixture.artifactdefaults.greet((args[0] as String), (args[1] as String))"),
            "the all-present call must survive unchanged: $body",
        )
        assertTrue(
            body.contains("fixture.artifactdefaults.greet(name = (args[0] as String))"),
            "there must be a call that omits the defaulted parameter: $body",
        )
        assertTrue(body.contains("args[1] == null"), "the branch must key off the null sentinel: $body")
    }

    /**
     * The property an arity-prefix scheme cannot have (§4.5, first row): the *middle* parameter is
     * omitted while a later one is passed.
     *
     * `label(bag, prefix = , suffix = )` has two defaulted parameters, so there are four subsets and
     * therefore four call expressions -- including `label(bag = , suffix = )`, which skips `prefix`.
     */
    @Test
    fun everySubsetOfTheDefaultedParametersGetsItsOwnCall() {
        val body = entry("label").lambdaBody.flattened()
        val bag = "(args[0] as fixture.artifactdefaults.Bag)"
        val prefix = "(args[1] as String)"
        val suffix = "(args[2] as String)"
        assertTrue(body.contains("fixture.artifactdefaults.label($bag, $prefix, $suffix)"), body)
        assertTrue(body.contains("fixture.artifactdefaults.label(bag = $bag, suffix = $suffix)"), body)
        assertTrue(body.contains("fixture.artifactdefaults.label(bag = $bag, prefix = $prefix)"), body)
        assertTrue(body.contains("fixture.artifactdefaults.label(bag = $bag)"), body)
        assertEquals(
            4,
            Regex("fixture\\.artifactdefaults\\.label\\(").findAll(body).count(),
            "two defaulted parameters means exactly four call expressions: $body",
        )
    }

    /**
     * An extension, which is where Compose lives: the receiver is never omittable (it is positional
     * in Kotlin and `<receiver>` is deliberately not a Python identifier), and the call still goes
     * through the `import ... as ...` alias because Kotlin has no fully-qualified extension call.
     */
    @Test
    fun anExtensionOmitsItsValueParametersAndNeverItsReceiver() {
        val padded = entry("padded")
        val body = padded.lambdaBody.flattened()
        val alias = "artifact_ext_fixture_artifactdefaults_padded"
        assertTrue(body.contains("(args[0] as fixture.artifactdefaults.Bag).$alias()"), body)
        assertTrue(body.contains(".$alias(horizontal = (args[1] as Double))"), body)
        assertTrue(body.contains(".$alias(vertical = (args[2] as Double))"), body)
        assertEquals(listOf(false, true, true), padded.paramHasDefault)
    }

    /**
     * `paramHasDefault` stops being carried-and-unused and becomes the *contract*: it marks exactly
     * the slots this body will accept `null` in.
     *
     * That is a slightly narrower claim than "the Kotlin declaration declares a default", and the
     * narrower one is the honest one -- it is what `pythonx._bind` reads to decide whether a missing
     * argument may be filled with the sentinel, and [aDeclarationWiderThanTheCapKeepsEveryParameterRequired]
     * is the case where the two differ.
     */
    @Test
    fun paramHasDefaultMarksExactlyTheSlotsTheBodyCanOmit() {
        assertEquals(listOf(false, true), entry("greet").paramHasDefault)
        assertEquals(listOf(false, true, true), entry("label").paramHasDefault)
        assertEquals(listOf(false, false), entry("plain").paramHasDefault)
    }

    /** A declaration with no default at all must generate exactly what it generated before this
     * existed -- one call, no `when`, no sentinel test. 43 of `foundation-layout`'s 70 entries are
     * in this class and none of them should change. */
    @Test
    fun aDeclarationWithNoDefaultsIsUntouched() {
        val body = entry("plain").lambdaBody.flattened()
        assertEquals(
            "{ args -> (fixture.artifactdefaults.plain((args[0] as Long).toInt(), (args[1] as Long).toInt())).toLong() }",
            body,
        )
    }

    /**
     * The cap firing, which is the only place the binding is deliberately narrower than the
     * declaration.
     *
     * Seven defaulted parameters would be 128 generated call expressions. Rather than pay that, the
     * walker keeps the pre-existing all-present body **and says so in the table**: `paramHasDefault`
     * is all `false`, so `pythonx` requires every argument and the `.pyi` writes no `= ...`. The
     * declaration is still bound and still callable; what it loses is omission.
     */
    @Test
    fun aDeclarationWiderThanTheCapKeepsEveryParameterRequired() {
        val wide = entry("wide")
        assertEquals(List(7) { false }, wide.paramHasDefault)
        assertFalse("== null" in wide.lambdaBody, "no sentinel branching past the cap: ${wide.lambdaBody}")
        // And the parameter that is one narrower does branch, so the boundary is pinned from both
        // sides rather than only from the far one.
        val six = entry("sixWide")
        assertEquals(List(6) { true }, six.paramHasDefault)
        assertEquals(
            64,
            Regex("fixture\\.artifactdefaults\\.sixWide\\(").findAll(six.lambdaBody).count(),
            "six defaulted parameters is 2^6 calls, which is the most this generator will emit",
        )
    }

    /**
     * The limit the Kotlin compiler imposed, and the only reason the omission sets are decided over
     * the whole walk instead of where the call is built.
     *
     * Emitting every subset unconditionally did not compile: `foundation-layout-desktop-1.6.11`
     * failed in six places, every one of them the branch that writes **no** argument, every one of
     * them *"Overload resolution ambiguity between candidates"* -- `WindowInsets(Dp×4)` against
     * `WindowInsets(Int×4)`, `paddingFromBaseline(Dp,Dp)` against its `TextUnit` twin,
     * `paddingFrom(AlignmentLine,Dp,Dp)` against its. `insets` is that shape in types this fixture
     * owns.
     *
     * What survives is everything else: writing *one* argument settles the overload by its type, so
     * only the empty branch is refused, and it is refused by throwing rather than by being absent --
     * `pythonx` reads `paramHasDefault` per slot and cannot express "these two but not both at once".
     */
    @Test
    fun theOmissionThatWouldBeAmbiguousIsRefusedAndTheRestSurvive() {
        val doubles = entry("insets__Double_Double")
        val ints = entry("insets__Int_Int")
        for (candidate in listOf(doubles, ints)) {
            val body = candidate.lambdaBody.flattened()
            assertEquals(
                1,
                Regex("is ambiguous with another overload").findAll(body).count(),
                "only the write-nothing branch is ambiguous: $body",
            )
            assertTrue("leaving out left and right is ambiguous" in body, body)
            // Both parameters stay omittable: each appears in an omission set that *is* generated.
            assertEquals(listOf(true, true), candidate.paramHasDefault)
        }
        assertTrue(
            "insets(left = (args[0] as Double))" in doubles.lambdaBody.flattened(),
            "one written argument settles the overload by its type: ${doubles.lambdaBody}",
        )
        assertTrue(
            "insets(right = (args[1] as Long).toInt())" in ints.lambdaBody.flattened(),
            ints.lambdaBody,
        )
    }

    /** The other side of it: a defaulted name with no sibling keeps every subset, the empty one
     * included. `sixWide` is 64 calls and no refusal. */
    @Test
    fun aNameWithNoOverloadSetKeepsEveryOmissionIncludingTheEmptyOne() {
        val body = entry("sixWide").lambdaBody
        assertFalse("ambiguous" in body, "nothing to be ambiguous with")
        assertTrue("fixture.artifactdefaults.sixWide()" in body.flattened(), "the empty call is missing")
    }

    /**
     * A named argument is source, so a parameter whose name is a Kotlin hard keyword has to be
     * written back with the backticks metadata does not carry. Without this the generated fragment
     * fails to *compile*, in a consumer's build, over a declaration they only asked to have bound.
     */
    @Test
    fun aParameterNamedWithAKeywordIsEscapedInTheNamedCall() {
        val body = entry("keyworded").lambdaBody.flattened()
        assertTrue(
            body.contains("fixture.artifactdefaults.keyworded(`object` = (args[0] as String))"),
            "a hard keyword has to be written back with its backticks: $body",
        )
    }
}
