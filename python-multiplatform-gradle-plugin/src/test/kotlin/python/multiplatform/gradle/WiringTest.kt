package python.multiplatform.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three decisions the plugin makes for the user, as pure functions.
 *
 * The plugin's real proof is `ksp-fixtures`, which applies it and runs its generated table; these
 * pin the parts that would otherwise only be observable as a missing fragment or a duplicate one.
 */
class WiringTest {

    // Observed on `:ksp-fixtures:app` (`gradlew dependencies`), Kotlin 2.4.20-Beta2 + KSP 2.3.11.
    private val observedConfigurations = listOf(
        "ksp",
        "kspAndroidNativeArm64",
        "kspAndroidNativeArm64Test",
        "kspCommonMainKotlinMetadataProcessorClasspath",
        "kspCommonMainMetadata",
        "kspDesktop",
        "kspDesktopTest",
        "kspKotlinAndroidNativeArm64ProcessorClasspath",
        "kspKotlinDesktopProcessorClasspath",
        "kspPluginClasspath",
        "kspPluginClasspathNonEmbeddable",
        "kspTestKotlinAndroidNativeArm64ProcessorClasspath",
        "kspTestKotlinDesktopProcessorClasspath",
    )

    @Test
    fun theProcessorGoesOnEveryTargetsMainCompilationAndNowhereElse() {
        assertEquals(
            listOf("kspAndroidNativeArm64", "kspDesktop"),
            observedConfigurations.filter { isBindingKspConfiguration(it, isMultiplatform = true) },
        )
    }

    @Test
    fun theBareKspConfigurationIsForSinglePlatformProjectsOnly() {
        // Adding to it from a multiplatform project is a hard failure: "The 'ksp' configuration
        // is deprecated in Kotlin Multiplatform projects" -- observed against the fixtures.
        assertFalse(isBindingKspConfiguration("ksp", isMultiplatform = true))
        assertTrue(isBindingKspConfiguration("ksp", isMultiplatform = false))
    }

    // Observed on `:ksp-fixtures:android` (`gradlew :ksp-fixtures:android:dependencies`), the
    // fixture that carries `com.android.library`. AGP names its source sets with the build type
    // *last*, so the test configurations here do not end in `Test` the way the Kotlin-target ones
    // above do. Every one of the nine `...Test...` names below was receiving the processor before
    // ROADMAP §13.
    private val observedAndroidConfigurations = listOf(
        "ksp",
        "kspAndroid",
        "kspAndroidAndroidTest",
        "kspAndroidAndroidTestDebug",
        "kspAndroidAndroidTestRelease",
        "kspAndroidDebug",
        "kspAndroidRelease",
        "kspAndroidTest",
        "kspAndroidTestDebug",
        "kspAndroidTestFixtures",
        "kspAndroidTestFixturesDebug",
        "kspAndroidTestFixturesRelease",
        "kspAndroidTestRelease",
        "kspCommonMainMetadata",
        "kspDebug",
        "kspDebugAndroidTestKotlinAndroidProcessorClasspath",
        "kspDebugKotlinAndroidProcessorClasspath",
        "kspDebugUnitTestKotlinAndroidProcessorClasspath",
        "kspPluginClasspath",
        "kspPluginClasspathNonEmbeddable",
        "kspRelease",
        "kspReleaseKotlinAndroidProcessorClasspath",
        "kspReleaseUnitTestKotlinAndroidProcessorClasspath",
        "kspTest",
        "kspTestDebug",
        "kspTestFixtures",
        "kspTestFixturesDebug",
        "kspTestFixturesRelease",
        "kspTestRelease",
    )

    @Test
    fun testCompilationsAreExcludedOrTheModuleWouldEmitASecondFragmentUnderTheSameName() {
        assertFalse(isBindingKspConfiguration("kspDesktopTest", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspAndroidNativeArm64Test", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspTest", isMultiplatform = false))
    }

    @Test
    fun anAndroidModulesTestConfigurationsCarryTheBuildTypeAfterTheWordTest() {
        // The ROADMAP §13 defect: `kspAndroidTestDebug` does not end in `Test`, so the processor
        // ran over the unit-test sources and emitted a `Fragment_<module>`/`FunctionTable` pair
        // that shadowed `main`'s inside the test compilation. Observed on
        // `:ksp-fixtures:android` before this filter was widened.
        for (name in listOf(
            "kspAndroidTestDebug",
            "kspAndroidTestRelease",
            "kspAndroidAndroidTestDebug",
            "kspAndroidAndroidTestRelease",
            "kspTestDebug",
            "kspTestRelease",
            "kspAndroidTestFixtures",
            "kspAndroidTestFixturesDebug",
            "kspTestFixturesRelease",
        )) {
            assertFalse(isBindingKspConfiguration(name, isMultiplatform = true), name)
        }
    }

    @Test
    fun onlyTheAndroidMainSourceSetsConfigurationsKeepTheProcessor() {
        assertEquals(
            listOf("kspAndroid", "kspAndroidDebug", "kspAndroidRelease", "kspDebug", "kspRelease"),
            observedAndroidConfigurations.filter { isBindingKspConfiguration(it, isMultiplatform = true) },
        )
    }

    @Test
    fun aFlavourOrTargetMerelyContainingTheLettersTestKeepsTheProcessor() {
        // `Test` is matched as a camel-case word, not as a substring: `testing` is an ordinary
        // name a product flavour or a Kotlin target may carry, and excluding it would silently
        // leave that variant without a fragment.
        assertTrue(isBindingKspConfiguration("kspAndroidTestingDebug", isMultiplatform = true))
        assertTrue(isBindingKspConfiguration("kspLatestDebug", isMultiplatform = true))
    }

    @Test
    fun theMetadataCompilationIsExcludedForTheSameReason() {
        // commonMain is compiled again by every platform target, so processing it separately
        // would produce a duplicate `Fragment_<module>` for one module.
        assertFalse(isBindingKspConfiguration("kspCommonMainMetadata", isMultiplatform = true))
    }

    @Test
    fun kspsOwnInternalClasspathConfigurationsAreNotDependencyTargets() {
        assertFalse(isBindingKspConfiguration("kspPluginClasspath", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspKotlinDesktopProcessorClasspath", isMultiplatform = true))
        assertFalse(isBindingKspConfiguration("kspTestKotlinDesktopProcessorClasspath", isMultiplatform = true))
    }

    @Test
    fun moduleNameCarriesTheGroupBecauseFragmentObjectsShareOnePackageAcrossAllArtifacts() {
        // Two independent libraries both called `:core` would otherwise both emit
        // `python.multiplatform.generated.fragments.Fragment_core` and collide on one classpath.
        assertEquals(
            "io_github_thisisthepy_ksp_fixtures_app",
            deriveModuleName(group = "io.github.thisisthepy", path = ":ksp-fixtures:app", name = "app"),
        )
        assertEquals(
            "com_example_core",
            deriveModuleName(group = "com.example", path = ":core", name = "core"),
        )
    }

    @Test
    fun moduleNameFallsBackToTheProjectNameForARootProjectOrAGrouplessBuild() {
        assertEquals("my_app", deriveModuleName(group = "", path = ":", name = "my-app"))
        assertEquals("com_example_my_app", deriveModuleName(group = "com.example", path = ":", name = "my-app"))
    }

    @Test
    fun moduleNameIsAlwaysAValidKotlinIdentifierSuffix() {
        val derived = deriveModuleName(group = "com.example-corp", path = ":some module", name = "some module")
        assertTrue(derived.all { it.isLetterOrDigit() || it == '_' }, derived)
    }

    @Test
    fun roleIsInferredFromWhetherTheModuleProducesTheFinalBinary() {
        // Only the module that links the binary may aggregate: two aggregators on one classpath
        // means two `FunctionTable` objects in one package.
        assertEquals("app", inferRole(hasApplicationPlugin = true, hasAndroidApplicationPlugin = false))
        assertEquals("app", inferRole(hasApplicationPlugin = false, hasAndroidApplicationPlugin = true))
        assertEquals("library", inferRole(hasApplicationPlugin = false, hasAndroidApplicationPlugin = false))
    }

    @Test
    fun wasmProxyExportsTaskIsConfiguredWhenWasmJsMainSourceSetExists() {
        val project = org.gradle.testfixtures.ProjectBuilder.builder().build()
        val container = project.container(DummySourceSet::class.java) { name -> DummySourceSet(name, project) }
        container.create("wasmJsMain")
        project.extensions.add("kotlin", DummyKotlinExt(container))

        PythonBindingsPlugin().configureWasmProxyExports(project)

        val task = project.tasks.findByName("generateWasmProxyExports")
        kotlin.test.assertNotNull(task, "generateWasmProxyExports task should be registered when wasmJsMain exists")
        kotlin.test.assertTrue(task is GenerateWasmProxyExportsTask)
    }
}

class DummySourceSet(private val name: String, private val project: org.gradle.api.Project) : org.gradle.api.Named {
    override fun getName(): String = name
    fun getKotlin(): org.gradle.api.file.SourceDirectorySet =
        project.objects.sourceDirectorySet(name, "$name Kotlin sources")
}

class DummyKotlinExt(val sourceSets: org.gradle.api.NamedDomainObjectContainer<DummySourceSet>)
