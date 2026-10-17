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
// The Compose host: the `@Composable` that draws a Python-declared root. Its own module so that
// `:python-multiplatform` never depends on Compose.
include(":python-multiplatform-compose")
// Fixtures live inside the module whose output they check (issue #108), as nested Gradle projects.
// The KSP processor's consumers are `python-multiplatform-ksp/fixtures/*`. The intermediate
// `:python-multiplatform-ksp:fixtures` project that Gradle creates for the path has no build file
// and no plugins; it is only a container.
include(":python-multiplatform-ksp:fixtures:library")
include(":python-multiplatform-ksp:fixtures:app")
// The fixture that carries an Android plugin. `:library` and `:app` apply none, which is why the
// KSP/AGP minimum-version wall of ROADMAP §13 stayed invisible to them while it broke every
// Android consumer of the bindings plugin.
include(":python-multiplatform-ksp:fixtures:android")

// The Gradle plugin is an *included build* (see `pluginManagement` above), so it cannot own
// subprojects of this build. Its fixtures are therefore root-build projects whose directory is
// `python-multiplatform-gradle-plugin/fixtures/<name>` (the plugin build's own settings never
// look there). The project path is `:python-multiplatform-gradle-plugin-fixtures:<name>`: a path
// `:python-multiplatform-gradle-plugin:...` would share its name with the included build.
// The fixture for the *other* binding producer: `docs/design/ecosystem.md` §5b's artefact walker, which
// reads the jars the build resolves rather than the source it compiles. `:library` and `:app` cover
// KSP only, so nothing here exercised a third-party binary until this module existed.
include(":python-multiplatform-gradle-plugin-fixtures:artifact")
// A real, separately-compiled jar for the walker's value-class handling -- `kotlin.time.Duration`
// cannot prove a positive round trip (its constructor is `internal`), so this stands in for it. See
// its own `build.gradle.kts`.
include(":python-multiplatform-gradle-plugin-fixtures:artifact-valueclass")
// The same walker, pointed at a Kotlin/Native compile classpath instead of a JVM one --
// ROADMAP §16e's "investigated, not implemented" half. `KlibScanner` reads `.klib` metadata with
// `LibraryAbiReader` instead of ASM.
include(":python-multiplatform-gradle-plugin-fixtures:klib-artifact")
// The container project for the three above (no build file, no plugins), then each fixture.
project(":python-multiplatform-gradle-plugin-fixtures").projectDir =
    file("python-multiplatform-gradle-plugin/fixtures")
for (name in listOf("artifact", "artifact-valueclass", "klib-artifact")) {
    project(":python-multiplatform-gradle-plugin-fixtures:$name").projectDir =
        file("python-multiplatform-gradle-plugin/fixtures/$name")
}

// The Compose host's consumers: `python-multiplatform-compose/fixtures/*`.
// The composable half of the same claim, and the one module in this build that applies the Compose
// compiler plugin for a *test*: `:python-multiplatform-gradle-plugin-fixtures:artifact` deliberately
// has none (its own `build.gradle.kts` says why), and a `Composer` cannot exist without one. See its
// `PythonComposition.kt` -- one hand-written `@Composable`, for every composable in every artefact.
include(":python-multiplatform-compose:fixtures:compose")
// pythonx-compose's `UI.ipynb` scenarios end to end (issue #26): the installed pythonx-compose wheel,
// drawn by `PythonContent`. A fixture and not `sample/`, which is user-authored spec material. Its
// desktopTest needs `-PpythonxComposeWheel=<wheel>`; see its README.
include(":python-multiplatform-compose:fixtures:notebook-e2e")
