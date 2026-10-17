package python.multiplatform.gradle.artifact

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The artefact walker against two real jars: `junit:junit:4.13.2` (Java) and `kotlin-stdlib`
 * (Kotlin).
 *
 * `docs/design/ecosystem.md` §5b names two producers of bindings, split by *what they look at* -- KSP sees
 * the consumer's own source, the artefact walker sees everything the build resolves. This is the
 * second one, and a jar the test never compiled is the only honest input for it.
 *
 * Neither jar is on this test's classpath. The JUnit 4 one arrives as a path in a system property
 * (see this build's `walkerFixtureJar` configuration); `kotlin-stdlib` is located through its own
 * `CodeSource`, since it necessarily *is* on the classpath of anything written in Kotlin.
 */
class ArtifactScannerTest {

    private val junitJar: File
        get() {
            val path = System.getProperty("python.multiplatform.walkerFixtureJar")
            assertNotNull(path, "the walkerFixtureJar system property is not set; see build.gradle.kts")
            return File(path).also { assertTrue(it.isFile, "not a file: $it") }
        }

    private val kotlinStdlibJar: File
        get() {
            val location = Class.forName("kotlin.text.Regex").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isFile, "not a file: $it") }
        }

    /**
     * `ValueClassFixtures.kt` and its `multipart` siblings, compiled as part of this module's own
     * test sourceSet -- located the same way [kotlinStdlibJar] is (`protectionDomain.codeSource`),
     * except a test run leaves this one a directory of `.class` files rather than a jar, which
     * [ArtifactScanner.scanJar] now accepts directly. They are never compiled *by* the test, only
     * looked up after the fact: the walker still meets them purely as bytecode plus `@Metadata`.
     */
    private val fixtureClasses: File
        get() {
            val location = Class.forName("fixture.artifactvalueclass.Meters").protectionDomain.codeSource.location
            return File(location.toURI()).also { assertTrue(it.isDirectory, "expected a directory of .class files: $it") }
        }

    /**
     * The fixed text: every declaration the walker takes out of the whole JUnit 4 jar under a name
     * the jar itself uses, with no include filter at all.
     *
     * Seven entries out of a 380-class jar, and the smallness is the point -- [JvmDescriptorsTest]
     * is the filter that produces it. `junit.runner.Version.id` is the one the end-to-end fixture
     * calls from Python, because its answer (`"4.13.2"`) is the jar's own version and so cannot be
     * produced by anything except the jar's own code.
     *
     * These seven are exactly the names that carry **no** overload. Everything else JUnit exposes is
     * an overload set and now reaches the table under a suffixed name instead; see
     * [overloadsAreKeptUnderNamesDerivedFromTheirParameterTypes] for the rule and
     * [overloadedNamesAreNeverArbitrated] for what is still refused. Splitting the assertion this way
     * rather than pinning one 90-entry list keeps the *policy* visible: a bare Kotlin name means one
     * declaration, always.
     */
    @Test
    fun theWholeJUnitJarYieldsExactlyTheseUnsuffixedDeclarations() {
        val entries = ArtifactScanner.scanJar(junitJar, includePrefixes = emptyList())
        assertEquals(
            listOf(
                "junit.framework.Assert.failSame",
                "junit.framework.TestCase.failSame",
                "junit.runner.BaseTestRunner.getFilteredTrace",
                "junit.runner.BaseTestRunner.savePreferences",
                "junit.runner.BaseTestRunner.setPreference",
                "junit.runner.BaseTestRunner.truncate",
                "junit.runner.Version.id",
            ),
            entries.map { it.name }.filterNot { "__" in it },
        )
    }

    @Test
    fun anEntryCarriesTheTagsAndTheCallItsDeclarationImplies() {
        val entries = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.runner.Version"))
        assertEquals(1, entries.size)
        val id = entries.single()
        assertEquals("junit.runner.Version.id", id.name)
        assertEquals(0, id.arity)
        assertEquals(emptyList(), id.paramTags)
        assertEquals("STRING", id.returnTag)
        assertEquals("{ (junit.runner.Version.id()) }", id.lambdaBody)

        val setPreference = ArtifactScanner
            .scanJar(junitJar, includePrefixes = listOf("junit.runner.BaseTestRunner"))
            .single { it.name.endsWith(".setPreference") }
        assertEquals(2, setPreference.arity)
        assertEquals(listOf("STRING", "STRING"), setPreference.paramTags)
        assertEquals("UNIT", setPreference.returnTag)
        assertEquals(
            "{ args -> (junit.runner.BaseTestRunner.setPreference((args[0] as String), (args[1] as String))) }",
            setPreference.lambdaBody,
        )
    }

    @Test
    fun anIncludePrefixMatchesAPackageOrAClassButNotAPrefixOfEither() {
        val byPackage = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.runner"))
        assertEquals(
            listOf(
                "junit.runner.BaseTestRunner.getFilteredTrace",
                "junit.runner.BaseTestRunner.getPreference__String",
                "junit.runner.BaseTestRunner.getPreference__String_Int",
                "junit.runner.BaseTestRunner.savePreferences",
                "junit.runner.BaseTestRunner.setPreference",
                "junit.runner.BaseTestRunner.truncate",
                "junit.runner.Version.id",
            ),
            byPackage.map { it.name },
        )

        // `junit.run` is a prefix of the string `junit.runner` and of nothing else: a package
        // filter that matched it would be matching text rather than a namespace.
        assertEquals(emptyList(), ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.run")).map { it.name })
    }

    /**
     * The one place this deliberately parts company with `PyREPL`'s generator.
     *
     * PyREPL groups a class's methods by name and emits one `def name(self, *args, **kwargs): ...`
     * per group, which is exactly right for a `.pyi` stub: a stub describes a name, and Python has
     * no overloading to describe. A *binding* has to pick a body, and `org.junit.Assert.assertEquals`
     * has eight the walker can bind. Picking one -- even deterministically -- means
     * `assertEquals(3, 3)` from Python silently calls whichever the sort order happened to put
     * first, which for that method is the deprecated `(double, double)` that always fails.
     *
     * `FragmentScanner.distinctByName` keeps the first for Kotlin *source*, and that is not the same
     * decision: the collisions it resolves are duplicate views of one declaration (an instance and a
     * companion property of the same name, an `expect`/`actual` pair seen twice), not eight
     * different functions.
     *
     * Ambiguity is counted over what the walker *would bind*, not over what the class declares:
     * `getFilteredTrace` has two overloads, one taking `Throwable`, and only one of them survives
     * [JvmDescriptorsTest]'s type filter -- so there is nothing to disambiguate and it keeps the bare
     * name.
     */
    @Test
    fun overloadedNamesAreNeverArbitrated() {
        val names = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("org.junit")).map { it.name }
        assertTrue(
            names.none { "__" !in it },
            "org.junit.Assert and org.junit.Assume are overloads all the way down, so no bare name may survive: $names",
        )
        // The exact case the old drop-rule was written against, and the reason nothing may be
        // arbitrated: `assertEquals(double, double)` is deprecated and always fails, and it is the
        // one a sort order picked. It is reachable now -- but only from a caller who spelled out
        // which overload they meant.
        assertTrue("org.junit.Assert.assertEquals__Long_Long" in names, names.toString())
        assertTrue("org.junit.Assert.assertEquals__Double_Double" in names, names.toString())
        assertTrue("org.junit.Assert.assertEquals" !in names, names.toString())

        val runner = ArtifactScanner.scanJar(junitJar, includePrefixes = listOf("junit.runner.BaseTestRunner"))
            .map { it.name.substringAfterLast('.') }
        assertTrue("getFilteredTrace" in runner, "one bindable overload is not an ambiguity")
        assertTrue("getPreference" !in runner, "two bindable overloads is")
        assertTrue("getPreference__String" in runner, runner.toString())
    }

    /**
     * What a Kotlin jar's `@Metadata` says, read straight off the real `kotlin-stdlib`.
     *
     * This is the fact that decides how far ASM alone can go, so it is pinned against the actual
     * artefact rather than against a class this test synthesised. See
     * [kotlinFileFacadesAreSkippedBecauseKotlinCannotNameThem].
     */
    @Test
    fun kotlinMetadataKindsAreReadableWithAsmAlone() {
        val kinds = ArtifactScanner.metadataKinds(
            kotlinStdlibJar,
            listOf("kotlin/text/StringsKt", "kotlin/text/StringsKt__IndentKt", "kotlin/text/Regex"),
        )
        assertEquals(4, kinds["kotlin/text/StringsKt"], "a multi-file class facade")
        assertEquals(5, kinds["kotlin/text/StringsKt__IndentKt"], "a multi-file class part")
        assertEquals(1, kinds["kotlin/text/Regex"], "an ordinary class")

        val noMetadata = ArtifactScanner.metadataKinds(junitJar, listOf("junit/runner/Version"))
        assertNull(noMetadata["junit/runner/Version"], "a Java class carries no kotlin.Metadata")
    }

    /**
     * `kotlin.text.trimIndent` used to be unreachable: it compiles to a public static on
     * `kotlin/text/StringsKt__IndentKt`, which is **package-private** (generated Kotlin in another
     * package cannot call it), behind the public facade `kotlin/text/StringsKt`, which declares no
     * methods of its own and which Kotlin cannot name at all -- there is no `StringsKt` in the
     * Kotlin namespace, only `kotlin.text.trimIndent`. Recovering that name, and knowing that its
     * first JVM parameter is an extension *receiver* rather than an ordinary argument, needs
     * `@Metadata`'s `d1`/`d2` payload, which `KotlinMetadata.kt` now decodes.
     *
     * The facade and part class names themselves must still never appear anywhere a caller could
     * see them -- neither has a Kotlin spelling, so a bound entry naming one would generate source
     * that cannot compile.
     */
    @Test
    fun aTopLevelExtensionBehindAMultiFileFacadeIsNowReachable() {
        val entries = ArtifactScanner.scanJar(kotlinStdlibJar, includePrefixes = listOf("kotlin.text"))
        val names = entries.map { it.name }
        assertTrue(names.none { it.startsWith("kotlin.text.StringsKt") }, "nothing may be attributed to a facade or a part: $names")

        val trimIndent = entries.single { it.name == "kotlin.text.trimIndent" }
        assertEquals(1, trimIndent.arity, "the receiver is the one argument this declaration takes")
        assertEquals(listOf("STRING"), trimIndent.paramTags)
        assertEquals("STRING", trimIndent.returnTag)
        assertEquals(listOf("import kotlin.text.trimIndent as artifact_ext_kotlin_text_trimIndent"), trimIndent.imports)
        assertEquals(
            "{ args -> ((args[0] as String).artifact_ext_kotlin_text_trimIndent()) }",
            trimIndent.lambdaBody,
            "Kotlin has no fully-qualified call syntax for an extension -- the alias from imports is load-bearing",
        )
    }

    /**
     * `getInWholeSeconds-impl` (`kotlin.time.Duration`) was the case the old `-` filter was written
     * against, and it is *still* declined -- but now because `Duration`'s constructor is `internal`,
     * a fact read from `@Metadata`, not because its JVM name contains a hyphen. `Meters` is the same
     * shape (`@JvmInline value class` wrapping a single primitive) with a public constructor, which
     * is what makes the difference observable: replacing the name filter with a blanket "value class
     * implies declined" rule would still decline this and prove nothing.
     */
    @Test
    fun aPublicValueClassRoundTripsThroughItsUnderlyingPrimitive() {
        val entries = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
        val sumMeters = entries.single { it.name == "fixture.artifactvalueclass.sumMeters" }
        assertEquals(2, sumMeters.arity)
        assertEquals(listOf("FLOAT", "FLOAT"), sumMeters.paramTags)
        assertEquals("FLOAT", sumMeters.returnTag)
        assertEquals(emptyList(), sumMeters.imports, "not an extension -- no import needed to call an ordinary top-level function")
        assertEquals(
            "{ args -> ((fixture.artifactvalueclass.sumMeters(" +
                "fixture.artifactvalueclass.Meters((args[0] as Double)), " +
                "fixture.artifactvalueclass.Meters((args[1] as Double)))).value) }",
            sumMeters.lambdaBody,
        )
    }

    /** `kotlin.time.Duration.getInWholeSeconds-impl(J)J`'s exact shape, reproduced with a
     * constructor the walker *is* allowed to call: a JVM parameter (the unboxed receiver) with no
     * declared Kotlin parameter behind it. Declined by the arity check in `ArtifactScanner
     * .kotlinCandidates`, not by [aPublicValueClassRoundTripsThroughItsUnderlyingPrimitive]'s
     * constructor-visibility check -- both have to hold for a value class to be usable at all.
     *
     * `Seconds` has a public constructor and a public accessor (`raw`), so its constructor is now
     * bound under the name `fixture.artifactvalueclass.Seconds` -- exactly as `Meters` is. The one
     * thing that stays absent is `doubled`: a value-class own member whose implicit unboxed receiver
     * has no declared Kotlin parameter, declined by the arity check regardless of visibility. */
    @Test
    fun aValueClasssOwnMemberIsDeclinedForItsImplicitReceiverNotItsVisibility() {
        val entries = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass.Seconds"))
        val names = entries.map { it.name }
        // The constructor binding is present -- Seconds has a public constructor + public accessor.
        assertEquals(
            listOf("fixture.artifactvalueclass.Seconds"), names,
            "Seconds constructor must be bound; Seconds.doubled must not (implicit receiver, arity mismatch)",
        )
        val seconds = entries.single()
        assertEquals(1, seconds.arity)
        assertEquals(listOf("INT"), seconds.paramTags, "Seconds wraps a Long")
        assertEquals("INT", seconds.returnTag, "round-trips through its underlying Long")
        assertEquals(
            "{ args -> ((fixture.artifactvalueclass.Seconds((args[0] as Long))).raw) }",
            seconds.lambdaBody,
        )
    }

    /** Same Kotlin name, unrelated JVM shape (one mangled by a value-class parameter, one not):
     * grouping has to key off the *resolved* Kotlin name for this to be caught at all -- keying off
     * the raw JVM/ASM name would never have seen these two as the same declaration, and would have
     * left `addMeters` bound to whichever survived a different filter. */
    @Test
    fun overloadsThatCollideOnlyAfterResolvingTheKotlinNameAreStillCaught() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue("fixture.artifactvalueclass.addMeters" !in names, "the bare name must not be arbitrated: $names")
        assertTrue("fixture.artifactvalueclass.addMeters__Meters_Meters" in names, names.toString())
    }

    /** An extension whose receiver is itself the value class -- unwrap-on-the-way-in for the
     * receiver, wrap-on-the-way-out for the return, in the one declaration. */
    @Test
    fun anExtensionOnAValueClassUnwrapsTheReceiverAndWrapsTheReturn() {
        val doubled = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
            .single { it.name == "fixture.artifactvalueclass.doubled" }
        assertEquals(1, doubled.arity)
        assertEquals(listOf("FLOAT"), doubled.paramTags)
        assertEquals("FLOAT", doubled.returnTag)
        assertEquals(
            listOf("import fixture.artifactvalueclass.doubled as artifact_ext_fixture_artifactvalueclass_doubled"),
            doubled.imports,
        )
        assertEquals(
            "{ args -> ((fixture.artifactvalueclass.Meters((args[0] as Double))" +
                ".artifact_ext_fixture_artifactvalueclass_doubled()).value) }",
            doubled.lambdaBody,
        )
    }

    /** `suspend` is declined explicitly, from `@Metadata`, per AGENTS.md's "제외한 것을 조용히
     * 빠뜨리지 마라" -- not merely absent as a side effect of [boundaryTypeOf] rejecting a
     * `Continuation` parameter it never even has to see. */
    @Test
    fun aSuspendFunctionIsExplicitlyDeclined() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue("fixture.artifactvalueclass.neverBound" !in names, "suspend must never reach the table: $names")
    }

    /**
     * `@JvmMultifileClass` split across two files, reproducing `kotlin.text.trimIndent`'s own shape
     * with a fixture the test controls: a plain top-level function in one part, and -- combining the
     * two hard cases at once -- an extension function in the other.
     */
    @Test
    fun aMultiFileFacadesPartsAreAllReachableUnderThePackageName() {
        val entries = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass.multipart"))
        val names = entries.map { it.name }
        assertTrue(names.none { "MultiFacadeKt" in it }, "the facade's own JVM name has no Kotlin spelling: $names")

        val greeting = entries.single { it.name == "fixture.artifactvalueclass.multipart.partGreeting" }
        assertEquals("{ (fixture.artifactvalueclass.multipart.partGreeting()) }", greeting.lambdaBody)

        val shout = entries.single { it.name == "fixture.artifactvalueclass.multipart.shoutViaFacade" }
        assertEquals(
            listOf("import fixture.artifactvalueclass.multipart.shoutViaFacade as artifact_ext_fixture_artifactvalueclass_multipart_shoutViaFacade"),
            shout.imports,
        )
    }

    /** `internal` is JVM-public but not a name generated Kotlin may call; filtered from `@Metadata`'s
     * own visibility, not from the JVM `ACC_PUBLIC` bit every one of these methods also carries. */
    @Test
    fun anInternalTopLevelFunctionIsDeclinedRegardlessOfItsJvmVisibility() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue("fixture.artifactvalueclass.secretlyInternal" !in names, "internal must not reach the table: $names")
    }

    // ------------------------------------------------------------------ object handles (OBJECT)

    /**
     * An ordinary class -- not a primitive, not a value class -- in return position.
     *
     * `docs/design/kotlin-extensions-in-python.md` §6 lists this as the second of the two gates that
     * independently zero Compose: "Binding a parameter typed `Shape`, `Brush` or `PaddingValues`
     * needs an object-handle boundary type that `boundaryTypeOf` does not have." It does not need a
     * *marshaller*: `python.multiplatform.reflection.TypeTag.OBJECT` and
     * `python.multiplatform.reflection.HandleTable` have carried arbitrary Kotlin objects across as
     * opaque integers since the upcall trampoline was written, and KSP's own `tagFor` has emitted
     * `OBJECT` for every non-primitive since it existed. What was missing is only the walker's half:
     * a Kotlin *type name* to cast to, which `@Metadata` now supplies and a JVM descriptor never
     * could.
     *
     * So the generated body is an ordinary cast, and the value never leaves Kotlin -- see
     * `ArtifactScanner`'s KDoc for what owns the handle on the Python side.
     */
    @Test
    fun anOrdinaryClassCrossesAsAnObjectHandle() {
        val entries = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))

        val makeRope = entries.single { it.name == "fixture.artifactvalueclass.makeRope" }
        assertEquals(listOf("FLOAT"), makeRope.paramTags)
        assertEquals("OBJECT", makeRope.returnTag)
        assertEquals(
            "{ args -> (fixture.artifactvalueclass.makeRope((args[0] as Double))) }",
            makeRope.lambdaBody,
        )

        val ropeLength = entries.single { it.name == "fixture.artifactvalueclass.ropeLength" }
        assertEquals(listOf("OBJECT"), ropeLength.paramTags)
        assertEquals("FLOAT", ropeLength.returnTag)
        assertEquals(
            "{ args -> (fixture.artifactvalueclass.ropeLength((args[0] as fixture.artifactvalueclass.Rope))) }",
            ropeLength.lambdaBody,
        )
        // The declared type survives beside the tag: `docs/archive/pythonx-adapter-design.md` §2.4 row 4.
        assertEquals(listOf("fixture.artifactvalueclass.Rope"), ropeLength.paramTypeNames)
        assertEquals(listOf("rope"), ropeLength.paramNames)
        assertEquals(listOf(false), ropeLength.paramHasDefault)
    }

    /**
     * `Modifier.padding(Dp): Modifier`'s exact shape with types this module compiles: an extension
     * whose receiver and return are the same ordinary class. This is the one that makes chaining
     * work -- `docs/design/kotlin-extensions-in-python.md` §4.1 -- because the handle that comes back is
     * the same kind of thing the next call takes.
     */
    @Test
    fun anExtensionOnAnOrdinaryClassChainsThroughObjectHandles() {
        val lengthened = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
            .single { it.name == "fixture.artifactvalueclass.lengthened" }
        assertEquals(listOf("OBJECT", "FLOAT"), lengthened.paramTags)
        assertEquals("OBJECT", lengthened.returnTag)
        assertEquals("fixture.artifactvalueclass.Rope", lengthened.receiverTypeName)
        assertEquals(
            listOf("import fixture.artifactvalueclass.lengthened as artifact_ext_fixture_artifactvalueclass_lengthened"),
            lengthened.imports,
        )
        assertEquals(
            "{ args -> ((args[0] as fixture.artifactvalueclass.Rope)" +
                ".artifact_ext_fixture_artifactvalueclass_lengthened((args[1] as Double))) }",
            lengthened.lambdaBody,
        )
    }

    // ---------------------------------------------------------------------------- constructors

    /**
     * A class's own public primary constructor, bound the same way a top-level function is:
     * `Rope(Double): Rope`'s shape, and [Rope]'s own KDoc records why this used to be entirely
     * absent -- `ArtifactScanner.kotlinCandidates`'s `ACC_STATIC` filter drops `<init>` along with
     * every instance method, and nothing else ever picked it back up. `docs/archive/pythonx-adapter-design.md`
     * §10 names `Typography`/`Shapes` as the real-world casualty: not declined, simply never scanned.
     */
    @Test
    fun aClasssOwnPublicConstructorIsBoundUnderTheClassName() {
        val rope = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
            .single { it.name == "fixture.artifactvalueclass.Meters" }
        assertEquals(1, rope.arity)
        assertEquals(listOf("FLOAT"), rope.paramTags)
        assertEquals("FLOAT", rope.returnTag, "Meters wraps a Double and unwraps back to one")
        assertNull(rope.receiverTypeName, "a constructor is not an extension")
        assertEquals(
            "{ args -> ((fixture.artifactvalueclass.Meters((args[0] as Double))).value) }",
            rope.lambdaBody,
            "a fully-qualified constructor call is valid Kotlin, exactly like a fully-qualified function call",
        )
    }

    /** A value class's own constructor round-trips through its underlying primitive on the way back
     * out, the same as any other declaration returning [Meters] -- `Meters` is not special-cased,
     * it goes through [ArtifactScanner.candidateFromFunction] unchanged. */
    @Test
    fun aValueClasssOwnConstructorRoundTripsThroughItsUnderlyingPrimitive() {
        val meters = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
            .single { it.name == "fixture.artifactvalueclass.Meters" }
        assertEquals(1, meters.arity)
        assertEquals(listOf("FLOAT"), meters.paramTags)
        assertEquals("FLOAT", meters.returnTag, "Meters wraps a Double and unwraps back to one")
        assertEquals(
            "{ args -> ((fixture.artifactvalueclass.Meters((args[0] as Double))).value) }",
            meters.lambdaBody,
        )
    }

    /** `<init>` never reaches [ArtifactScanner.kotlinCandidates]'s own binder -- that path's
     * `ACC_STATIC` filter excludes every constructor, so nothing about [constructorCandidates] is a
     * duplicate of it. Pinned by absence: no `fixture.artifactvalueclass.Rope.<init>`-shaped name of
     * any kind reaches the table through the ordinary function path. */
    @Test
    fun aConstructorIsNeverAlsoBoundAsAnOrdinaryStaticMember() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue(names.none { "<init>" in it }, names.toString())
        assertEquals(1, names.count { it == "fixture.artifactvalueclass.Meters" }, "exactly one binding for Meters's one public constructor")
    }

    // ---------------------------------------------------------------------------- properties

    @Test
    fun objectPropertiesAreBoundAsStaticGetters() {
        val candidates = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
        val topLeft = candidates.singleOrNull { it.name == "fixture.artifactvalueclass.AbsoluteAlignment.TopLeft" }
        assertNotNull(topLeft, "TopLeft not found!")
        assertEquals(0, topLeft!!.arity)
        assertEquals("STATIC_GETTER", topLeft.kind)
        assertEquals(emptyList<String>(), topLeft.paramTags)
        assertEquals("OBJECT", topLeft.returnTag)
        assertEquals(
            "{ (fixture.artifactvalueclass.AbsoluteAlignment.TopLeft) }",
            topLeft.lambdaBody
        )
    }

    @Test
    fun interfaceCompanionPropertiesAreBoundWithoutCompanionInName() {
        val candidates = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass"))
        val center = candidates.singleOrNull { it.name == "fixture.artifactvalueclass.Alignment.Center" }
        assertNotNull(center, "Center not found!")
        assertEquals(0, center!!.arity)
        assertEquals("STATIC_GETTER", center.kind)
        assertEquals(emptyList<String>(), center.paramTags)
        assertEquals("OBJECT", center.returnTag)
        assertEquals(
            "{ (fixture.artifactvalueclass.Alignment.Companion.Center) }",
            center.lambdaBody
        )
    }

    // ------------------------------------------------------------------------------- overloads

    /**
     * Overloads are **kept, under names that say which one they are** -- the rule
     * `docs/design/kotlin-extensions-in-python.md` §3.1 and §6 left open.
     *
     * The old rule dropped a name outright when more than one binding would carry it, on the
     * reasoning that a sort order picking `org.junit.Assert.assertEquals(double, double)` is a wrong
     * call that never reports itself. That reasoning is about *arbitration*, and it is still right:
     * nothing here picks. What changed is that `@Metadata` now supplies the Kotlin parameter types,
     * so the overloads can be told apart by name instead of arbitrated between -- and the price of
     * not doing so is 33 of `Modifier`'s 130 names, including `padding`, `size`, `background`,
     * `border` and `clickable`.
     *
     * The plain name stays unbound for a group of more than one. A Python caller who writes
     * `padding` gets an `AttributeError` naming a declaration that does not exist, not a silent call
     * to whichever overload sorted first.
     */
    @Test
    fun overloadsAreKeptUnderNamesDerivedFromTheirParameterTypes() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }

        assertTrue("fixture.artifactvalueclass.addMeters" !in names, "the bare name must not be arbitrated: $names")
        assertTrue("fixture.artifactvalueclass.addMeters__Int_Int" in names, names.toString())
        assertTrue("fixture.artifactvalueclass.addMeters__Meters_Meters" in names, names.toString())
    }

    /** Two overloads that differ only in their *receiver* are not separated by their value
     * parameters, so the receiver joins the name -- `Int.times` and `Double.times` in
     * `androidx.compose.ui.unit` are the real instance of this. Paid for only where it is needed:
     * [overloadsAreKeptUnderNamesDerivedFromTheirParameterTypes]'s `addMeters` keeps the shorter
     * form. */
    @Test
    fun overloadsThatDifferOnlyInTheirReceiverAreSeparatedByIt() {
        val names = ArtifactScanner.scanJar(fixtureClasses, includePrefixes = listOf("fixture.artifactvalueclass")).map { it.name }
        assertTrue("fixture.artifactvalueclass.tagged__Rope" in names, names.toString())
        assertTrue("fixture.artifactvalueclass.tagged__Meters" in names, names.toString())
    }

    /**
     * The scorecard, run against real, locally-cached Compose Multiplatform desktop jars. Skipped
     * (not failed) when this machine's Gradle cache does not have them, since the path is
     * local-machine state, not something a clean checkout can be expected to have -- see
     * `ArtifactScannerTest`'s own KDoc for why the jars this test *does* control (junit,
     * kotlin-stdlib) are found the same way rather than pinned as a path.
     *
     * **The baseline this replaces was zero**, and it was zero for two independent reasons
     * (`docs/design/kotlin-extensions-in-python.md` §3): the metadata-kind gate, closed by `15fc5a62`, and
     * the type gate, closed here. `Modifier.padding(Dp): Modifier` has `Modifier` as both its
     * receiver and its return, and `Modifier` is an ordinary interface -- neither a primitive nor a
     * value class -- so before an object-handle boundary type existed there was nothing for
     * `resolveKotlinType` to resolve it to.
     *
     * The floors below are deliberately floors and not equalities: the exact number is a property of
     * Compose 1.6.11's own API surface, and a Compose bump that adds a `Modifier` extension must not
     * fail this. What the floors pin is that the two gates are open, which is the fact under test.
     *
     * `docs/design/ecosystem.md` §5b's own target (calling a composable) is out of scope here on purpose --
     * this counts declarations, and `:python-multiplatform-gradle-plugin-fixtures:artifact` is where a call is actually made.
     */
    @Test
    fun composeModifierExtensionsSurviveBothGates() {
        val gradleCaches = listOf(
            File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
            File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
        ).firstOrNull { it.isDirectory }
        if (gradleCaches == null) return // no local Gradle cache found at either known location

        fun jarUnder(group: String, artifact: String): File? = gradleCaches.resolve(group).resolve(artifact)
            .walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

        val layoutJar = jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop") ?: return
        val unitJar = jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop") ?: return
        val walked = listOfNotNull(
            layoutJar,
            unitJar,
            jarUnder("org.jetbrains.compose.foundation", "foundation-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
            jarUnder("org.jetbrains.compose.material", "material-desktop"),
            jarUnder("org.jetbrains.compose.material3", "material3-desktop"),
        )
        val classpath = walked + listOfNotNull(
            jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-text-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
            jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
            jarUnder("org.jetbrains.compose.runtime", "runtime-saveable-desktop"),
            jarUnder("org.jetbrains.compose.animation", "animation-core-desktop"),
            jarUnder("org.jetbrains.compose.collection-internal", "collection-desktop"),
            jarUnder("org.jetbrains.compose.annotation-internal", "annotation-desktop"),
            kotlinStdlibJar,
        )

        val all = walked.flatMap { ArtifactScanner.scanJar(it, includePrefixes = listOf("androidx.compose"), classpath = classpath) }
        val modifierExtensions = all.filter { it.receiverTypeName == "androidx.compose.ui.Modifier" }

        // What did *not* survive, and why -- counted rather than asserted about, because "the rest
        // take lambdas" is exactly the kind of sentence this repository has been burned by.
        val declined = declinedModifierExtensions(walked, classpath, modifierExtensions.map { it.name.substringAfterLast('.').substringBefore("__") }.toSet())

        // Printed as well as asserted: this test's whole reason to exist is the number, and a
        // passing floor assertion does not report what the number actually was.
        println("compose: ${all.size} declarations bound, ${modifierExtensions.size} of them Modifier extensions")
        println("compose: ${declined.size} public top-level Modifier extensions declined, ${declined.count { it.second }} of them for a function-typed parameter")

        assertTrue(
            modifierExtensions.size >= 100,
            "expected the Modifier chain to be reachable; got ${modifierExtensions.size}: " +
                modifierExtensions.map { it.name },
        )

        // The names `docs/design/kotlin-extensions-in-python.md` §3.1 lists as the casualties of the old
        // drop-ambiguous-overloads rule, spelled the way the new rule spells them.
        val layoutNames = ArtifactScanner
            .scanJar(layoutJar, includePrefixes = listOf("androidx.compose.foundation.layout"), classpath = classpath)
            .map { it.name }
        assertTrue("androidx.compose.foundation.layout.padding__Dp" in layoutNames, layoutNames.toString())
        assertTrue("androidx.compose.foundation.layout.padding__Dp_Dp" in layoutNames, layoutNames.toString())
        assertTrue("androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp" in layoutNames, layoutNames.toString())
        assertTrue("androidx.compose.foundation.layout.padding__PaddingValues" in layoutNames, layoutNames.toString())
        assertTrue("androidx.compose.foundation.layout.size__Dp" in layoutNames, layoutNames.toString())

        val unitEntries = ArtifactScanner.scanJar(unitJar, includePrefixes = listOf("androidx.compose.ui.unit"), classpath = classpath)
        assertTrue(unitEntries.isNotEmpty(), "expected at least one Dp-only declaration in androidx.compose.ui.unit to be reachable")
    }

    /**
     * Every public top-level `Modifier` extension the walked jars *declare* and this walk did not
     * bind, paired with whether it has a function-typed (lambda) parameter.
     *
     * Reads the same `@Metadata` `ArtifactScanner` does, deliberately without going through it: the
     * point is to compare "declared" against "bound", so deriving the first from the second would
     * make the comparison vacuous. Composables are excluded the same way the scanner excludes them
     * -- a JVM parameter count that exceeds the Kotlin one means synthetic parameters
     * (`$composer`/`$changed`/`$default`) that generated Kotlin source cannot supply.
     *
     * @return (Kotlin name, has a `kotlin.Function*` parameter).
     */
    private fun declinedModifierExtensions(
        walked: List<File>,
        classpath: List<File>,
        boundNames: Set<String>,
    ): List<Pair<String, Boolean>> {
        val artifactClasspath = ArtifactClasspath(classpath)
        val declined = mutableListOf<Pair<String, Boolean>>()
        walked.forEach { jar ->
            java.util.jar.JarFile(jar).use { file ->
                file.entries().asSequence().filter { it.name.endsWith(".class") && '$' !in it.name }.forEach { entry ->
                    val node = file.getInputStream(entry).use { readClassNodeOrNull(it.readBytes()) } ?: return@forEach
                    val container = when (val metadata = kotlinClassMetadataOf(node)) {
                        is kotlin.metadata.jvm.KotlinClassMetadata.FileFacade -> listOf(metadata.kmPackage to node)
                        is kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassFacade -> metadata.partClassNames.mapNotNull { part ->
                            val partNode = artifactClasspath.classNode(part) ?: return@mapNotNull null
                            val partMetadata = kotlinClassMetadataOf(partNode) as? kotlin.metadata.jvm.KotlinClassMetadata.MultiFileClassPart
                                ?: return@mapNotNull null
                            partMetadata.kmPackage to partNode
                        }
                        else -> return@forEach
                    }
                    container.forEach { (kmPackage, owner) ->
                        val jvmArity = owner.methods.associate { it.name to splitMethodDescriptor(it.desc).first.size }
                        functionsOf(kmPackage).forEach { function ->
                            if (function.receiverType?.let { kotlinClassifierNameOf(it) } != "androidx.compose.ui.Modifier") return@forEach
                            if (jvmArity[function.jvmSignature.name] != function.allParameterTypes.size) return@forEach
                            if (function.kotlinName in boundNames) return@forEach
                            declined += function.kotlinName to function.allParameterTypes.any {
                                kotlinClassifierNameOf(it)?.startsWith("kotlin.Function") == true
                            }
                        }
                    }
                }
            }
        }
        return declined.distinctBy { it.first }
    }

    /**
     * `docs/archive/pythonx-adapter-design.md` §10's `Typography`/`Shapes` casualty, closed, and pinned
     * against the structural rule that closed it rather than against the two hard-coded class names
     * an earlier attempt used.
     *
     * `Typography` and `Shapes` are ordinary (non-`value`) classes with 15 and 5 constructor
     * parameters respectively, and [ArtifactScanner.constructorCandidates] used to bind only
     * `value`-class constructors. Widening it to every public class opened them both.
     *
     * `TextStyle` was the measured reason it could not stay unconditional: `TextStyle` also
     * publishes a constant under a dotted continuation of its own name (`TextStyle.Default`, an
     * `object`/companion property [ArtifactScanner.objectConstantCandidates] binds independently),
     * and a constructor bound to the bare name `TextStyle` would claim it as a callable, making
     * `TextStyle.Default` unreachable. `Typography` and `Shapes` publish no such constant.
     * [ArtifactScanner.dropCollidingConstructors] is where that fact is now decided, in place of a
     * name list that would have needed one more line per class someone next wants opened --
     * [aFocusRequesterConstructorIsDroppedBecauseItWouldShadowFocusRequesterDefault] pins that rule
     * directly. `TextStyle` itself is pinned here only by outcome (no bound constructor, `Default`
     * still reachable). Every public `TextStyle` constructor takes a value class, so each one is a
     * JVM-`private` `<init>` behind a synthetic `DefaultConstructorMarker` bridge; `javap -v` on
     * ui-text-desktop 1.11.1 shows four of those bridges carrying `kotlin.Deprecated(level = HIDDEN)`
     * and two that do not. Until issue #73 the bytecode cross-check in
     * [ArtifactScanner.constructorCandidates] refused every synthetic `<init>`, so all six were gone
     * before [ArtifactScanner.dropCollidingConstructors] saw them; since #73 it refuses only the hidden
     * four, and the other two reach -- and are dropped by -- the constant-collision rule.
     */
    @Test
    fun aTypographyOrShapesConstructorBindsButATextStyleConstructorDoesNot() {
        val gradleCaches = listOf(
            File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
            File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
        ).firstOrNull { it.isDirectory }
        if (gradleCaches == null) return // no local Gradle cache found at either known location

        fun jarUnder(group: String, artifact: String): File? = gradleCaches.resolve(group).resolve(artifact)
            .walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

        val material3Jar = jarUnder("org.jetbrains.compose.material3", "material3-desktop") ?: return
        val uiTextJar = jarUnder("org.jetbrains.compose.ui", "ui-text-desktop") ?: return
        val classpath = listOfNotNull(
            material3Jar,
            uiTextJar,
            jarUnder("org.jetbrains.compose.ui", "ui-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
            jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-desktop"),
            jarUnder("org.jetbrains.compose.foundation", "foundation-layout-desktop"),
            jarUnder("org.jetbrains.compose.material", "material-desktop"),
            jarUnder("org.jetbrains.compose.collection-internal", "collection-desktop"),
            jarUnder("org.jetbrains.compose.annotation-internal", "annotation-desktop"),
        )

        val material3Names = ArtifactScanner.scanJar(material3Jar, classpath = classpath).map { it.name }
        assertTrue(
            "androidx.compose.material3.Typography" in material3Names,
            "Typography's own constructor should bind: ${material3Names.filter { "Typography" in it }}",
        )
        assertTrue(
            "androidx.compose.material3.Shapes" in material3Names,
            "Shapes' own constructor should bind: ${material3Names.filter { "Shapes" in it }}",
        )

        val textStyleCallables = ArtifactScanner.scanJar(uiTextJar, classpath = classpath)
            .filter { it.name.startsWith("androidx.compose.ui.text.TextStyle") }
        assertTrue(
            textStyleCallables.none { it.name == "androidx.compose.ui.text.TextStyle" },
            "TextStyle's own constructor must stay declined, or it would shadow TextStyle.Default: $textStyleCallables",
        )
        assertTrue(
            textStyleCallables.any { it.name == "androidx.compose.ui.text.TextStyle.Default" && it.kind == "STATIC_GETTER" },
            "TextStyle.Default is the constant a bound TextStyle constructor would make unreachable: $textStyleCallables",
        )
    }

    /**
     * [ArtifactScanner.dropCollidingConstructors]'s constant-collision rule, exercised end to end
     * against a real class -- unlike `TextStyle` above, `FocusRequester` has exactly one public
     * constructor (`FocusRequester()`, no parameters, not deprecated), so this is the rule actually
     * doing the work rather than the bytecode cross-check pre-empting it.
     */
    @Test
    fun aFocusRequesterConstructorIsDroppedBecauseItWouldShadowFocusRequesterDefault() {
        val gradleCaches = listOf(
            File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1"),
            File("/Volumes/macMini/caches/.gradle/caches/modules-2/files-2.1"),
        ).firstOrNull { it.isDirectory }
        if (gradleCaches == null) return // no local Gradle cache found at either known location

        fun jarUnder(group: String, artifact: String): File? = gradleCaches.resolve(group).resolve(artifact)
            .walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".jar") && "sources" !in it.name }

        val uiJar = jarUnder("org.jetbrains.compose.ui", "ui-desktop") ?: return
        val classpath = listOfNotNull(
            uiJar,
            jarUnder("org.jetbrains.compose.ui", "ui-geometry-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-graphics-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-unit-desktop"),
            jarUnder("org.jetbrains.compose.ui", "ui-util-desktop"),
            jarUnder("org.jetbrains.compose.runtime", "runtime-desktop"),
        )

        val bound = ArtifactScanner.scanJar(uiJar, classpath = classpath)
            .filter { it.name.startsWith("androidx.compose.ui.focus.FocusRequester") }
        assertTrue(
            bound.none { it.name == "androidx.compose.ui.focus.FocusRequester" },
            "FocusRequester's own constructor must stay declined: $bound",
        )
        assertTrue(
            bound.any { it.name == "androidx.compose.ui.focus.FocusRequester.Default" && it.kind == "STATIC_GETTER" },
            "FocusRequester.Default is the constant a bound constructor would shadow: $bound",
        )

        val declined = ArtifactScanner.scanDeclarations(uiJar, classpath = classpath)
            .filter { it.simpleName == "FocusRequester" && it.owner == "androidx.compose.ui.focus" }
        assertTrue(declined.isNotEmpty(), "expected FocusRequester's own constructor to have been scanned")
        assertTrue(declined.all { it.bindingName == null }, declined.toString())
        assertTrue(
            declined.all { it.declineReason == "constructor name collides with a constant this class publishes under its own name" },
            declined.map { it.declineReason }.toString(),
        )
    }
}
