enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    // The convenience plugin of ROADMAP §7 lives in its own build so that an outside consumer
    // resolves it by id like any other plugin. Including it here is what makes
    // `id("io.github.thisisthepy.python.multiplatform.bindings")` resolve inside this repo.
    includeBuild("python-multiplatform-gradle-plugin")

    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()

        maven {
            setUrl("https://jitpack.io")
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "PythonMultiplatformMobile"

include(":sample")
include(":python-multiplatform")
include(":python-multiplatform-ksp")
include(":ksp-fixtures:library")
include(":ksp-fixtures:app")
// The fixture that carries an Android plugin. `:library` and `:app` apply none, which is why the
// KSP/AGP minimum-version wall of ROADMAP §13 stayed invisible to them while it broke every
// Android consumer of the bindings plugin.
include(":ksp-fixtures:android")
// The fixture for the *other* binding producer: `docs/design/ecosystem.md` §5b's artefact walker, which
// reads the jars the build resolves rather than the source it compiles. `:library` and `:app` cover
// KSP only, so nothing here exercised a third-party binary until this module existed.
include(":ksp-fixtures:artifact")
// The composable half of the same claim, and the one module in this build that applies the Compose
// compiler plugin for a *test*: `:ksp-fixtures:artifact` deliberately has none (its own
// `build.gradle.kts` says why), and a `Composer` cannot exist without one. See its
// `PythonComposition.kt` -- one hand-written `@Composable`, for every composable in every artefact.
include(":ksp-fixtures:compose")
// A real, separately-compiled jar for the walker's value-class handling -- `kotlin.time.Duration`
// cannot prove a positive round trip (its constructor is `internal`), so this stands in for it. See
// its own `build.gradle.kts`.
include(":ksp-fixtures:artifact-valueclass")
// The same walker, pointed at a Kotlin/Native compile classpath instead of a JVM one --
// ROADMAP §16e's "investigated, not implemented" half. `KlibScanner` reads `.klib` metadata with
// `LibraryAbiReader` instead of ASM.
include(":ksp-fixtures:klib-artifact")
