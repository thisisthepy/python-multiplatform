import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The artefact walker end to end: a real third-party jar the build resolves, walked at build time,
 * installed into the same `UpcallTable` KSP's own fragments go into, and imported from Python.
 *
 * `docs/design/ecosystem.md` §5b names two producers of bindings and splits them by *what they look at* --
 * KSP the consumer's own source, the walker everything the build resolves. `:ksp-fixtures:app`
 * covers the first. Nothing covered the second, which is why `androidx.compose.material3` was
 * believed to be unreachable.
 *
 * Deliberately a module of its own rather than another target in `:ksp-fixtures:app`: that module's
 * `CommonInstallTest` asserts `UpcallTable.moduleNames` **exactly**, and a walked artefact appearing
 * there would be a change to what `FunctionTable` means rather than an addition beside it. Keeping
 * the two apart in the build is the same judgement `ArtifactTableRenderingTest` records for the
 * generated code.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    // One target. The walker reads jars, and jars are what a JVM target resolves; whether the same
    // walk is possible over a `.klib` is `docs/design/ecosystem.md` §5b's open question and is not
    // answered by adding a Native target that would silently bind nothing.
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
        val desktopMain by getting {
            dependencies {
                // A real dependency, declared the ordinary way. Nothing about it knows it is going
                // to be walked, which is the whole claim: KSP cannot see inside it, and the walker
                // does not need it to cooperate.
                implementation(libs.junit)
                // See its own `build.gradle.kts`: a real jar for the value-class round trip
                // `kotlin.time.Duration` cannot prove.
                implementation(projects.kspFixtures.artifactValueclass)

                // Compose Multiplatform, as plain Maven coordinates and **without** the Compose
                // Gradle or compiler plugin. That is deliberate and is the whole shape of the claim:
                // `docs/design/kotlin-extensions-in-python.md` §3 measured zero declarations bound from
                // these exact jars, and what makes `Modifier.padding(16.dp)` reachable is the
                // walker's own two gates opening, not any cooperation from Compose's tooling.
                //
                // A `Modifier` chain is also the one part of Compose that needs no composition at
                // all: `padding` is an ordinary function returning an ordinary object, so
                // `WalkedArtifactComposeModifierTest` can call it in a plain JVM test with no
                // `Composer` anywhere. Composables are a different problem
                // (`docs/archive/pythonx-adapter-design.md` §5) and are not touched here.
                //
                // Version pinned to the catalog's `compose-plugin`, which is what the rest of this
                // build resolves, so no second Compose version enters the cache.
                val composeVersion = libs.versions.compose.plugin.get()
                implementation("org.jetbrains.compose.ui:ui-desktop:$composeVersion")
                implementation("org.jetbrains.compose.ui:ui-unit-desktop:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation-layout-desktop:$composeVersion")
                // `Modifier.clickable` lives here rather than in `foundation-layout`, and it is the
                // acceptance criterion for a **non-composable** function-typed slot: an ordinary
                // function taking `onClick: () -> Unit`, so it needs no composition to call and yet
                // was unreachable for as long as a `kotlin.FunctionN` parameter was declined one
                // layer before the slot grammar was consulted (`ArtifactScanner.functionSlotOrNull`).
                implementation("org.jetbrains.compose.foundation:foundation-desktop:$composeVersion")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

pythonBindings {
    // Neither `application` nor `com.android.application` is applied, so the aggregating role has to
    // be stated -- this module needs a `FunctionTable` to install *beside* `ArtifactTable`.
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    artifactConfiguration.set("desktopCompileClasspath")
    artifactSourceSet.set("desktopMain")
    // Narrow on purpose. `junit.runner.Version.id()` answers `"4.13.2"`, which is the artefact's own
    // version and therefore cannot be produced by anything except the artefact's own code -- the
    // end-to-end test reads exactly that string back out of Python.
    //
    // `kotlin.text` is here for a different proof: it needs no dependency of its own (`kotlin-stdlib`
    // is already on every Kotlin module's classpath), and `kotlin.text.trimIndent` is the exact case
    // `ArtifactScanner`'s KDoc names as unreachable before it read `@Metadata` -- a top-level
    // extension function behind a `MULTI_FILE_CLASS_FACADE`. `WalkedArtifactPythonImportTest` calls
    // it from real Python.
    // `fixture.valueclass` is `:ksp-fixtures:artifact-valueclass`'s `Meters`/`sumMeters` -- see that
    // module's `build.gradle.kts` for why `kotlin.time.Duration` cannot stand in for it.
    //
    // `androidx.compose.foundation.layout` is the scorecard of
    // `docs/design/kotlin-extensions-in-python.md`: the package `Modifier.padding` and `Modifier.size` live
    // in, measured at **zero bound declarations** before the metadata-kind gate and the type gate
    // were opened. Narrow on purpose -- binding all of Compose here would make this fixture's
    // compile time the cost of a proof it does not need.
    //
    // The last two are for the **non-composable function-typed slot**, and both are named as single
    // *classes* rather than packages, which `artifactIncludePackages` allows and which keeps this
    // list's own "narrow on purpose" rule:
    //
    // - `androidx.compose.foundation.ClickableKt` is `Modifier.clickable` and `combinedClickable`.
    //   Asking for `androidx.compose.foundation` would sweep in `gestures`, `lazy`, `text` and
    //   everything else under it, which is compile time spent on a proof this does not need.
    // - `kotlin.system.TimingKt` is `measureTimeMillis(block: () -> Unit)`, which has the **same**
    //   `kotlin.Function0()->kotlin.Unit` slot as `clickable.onClick` and, unlike every Compose
    //   callback, *invokes* it at the moment it is called. Compose stores its callbacks -- `clickable`
    //   is `composed { }`, `drawBehind` is a node -- so nothing in those jars can show a Python
    //   callable actually running outside a composition, and `WalkedArtifactCallbackTest` needs a
    //   declaration that runs one.
    artifactIncludePackages.set(
        listOf(
            "junit.runner", "junit.framework", "kotlin.text", "fixture.valueclass",
            "androidx.compose.foundation.layout",
            "androidx.compose.foundation.ClickableKt",
            "kotlin.system.TimingKt",
        ),
    )
}

/**
 * The same JVM the library's own `desktopTest` needs: JDK 21 with the preview FFM API, and a
 * `java.library.path` for the `libpython` that `manager.loadLibPython` extracts out of
 * `python-multiplatform`'s desktop jar next to the working directory.
 *
 * `PYTHONHOME` is *not* set here -- the bindings plugin's `stagePythonHome` already points every
 * `Test` task at a staged CPython prefix, and this is the first fixture that actually exercises it.
 */
tasks.named<Test>("desktopTest") {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    jvmArgs("--enable-preview", "-Djava.library.path=.")

    // `WalkedArtifactStubTest` reads the stubs the *build* generated -- not a fixture checked in
    // beside it. Nothing compiles a `.pyi` (`docs/design/pyi-generation-design.md` §6.2), so unlike the
    // generated Kotlin fragments there is no source-set registration that would make this task
    // depend on the generator; the dependency has to be stated.
    val stubs = tasks.named("generatePythonStubs")
    dependsOn(stubs)
    systemProperty(
        "python.multiplatform.stubDir",
        layout.buildDirectory.dir("generated/pythonStubs/desktopMain").get().asFile.absolutePath,
    )
}
