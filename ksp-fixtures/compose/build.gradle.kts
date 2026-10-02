import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The other end of `docs/design/pythonx-adapter-design.md` §5: a real `@Composable`, called from Python,
 * inside a real composition, drawing a real pixel.
 *
 * ### Why this is not `:ksp-fixtures:artifact`
 *
 * That module takes Compose as **plain Maven coordinates with no Compose plugin**, and its own
 * `build.gradle.kts` says why: what makes `Modifier.padding(16.dp)` reachable is the walker's gates
 * opening, not any cooperation from Compose's tooling, and a `Modifier` chain needs no composition at
 * all. A composable is the opposite case in both respects. It needs a `Composer`, which exists only
 * inside a composition, which needs the Compose compiler plugin to create -- so proving it here
 * rather than there keeps that claim intact instead of quietly weakening it.
 *
 * ### What the compiler plugin is and is not used for
 *
 * It compiles **one hand-written function**, `PythonComposition.kt`'s `PythonComposition`. The
 * generated fragment beside it calls no composable from Kotlin source and could not: a composable's
 * call site is emitted as bytecode (`ComposableThunks.kt`), because a Kotlin file facade has no
 * Kotlin name and the `$default` mask must be computed at run time. So the plugin's presence buys
 * exactly one composer and nothing else.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetpack.compose)
    alias(libs.plugins.compose.compiler)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
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
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.desktop.currentOs)
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
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    artifactConfiguration.set("desktopCompileClasspath")
    artifactSourceSet.set("desktopMain")
    // Narrow on purpose, and narrower than `:ksp-fixtures:artifact`'s: binding all of Compose would
    // make this fixture's compile time the cost of a proof it does not need. Each entry earns its
    // place.
    //
    // 1. `material3` -- `Text` is a *leaf*, reachable without ever filling a function-typed slot.
    // 2. `foundation.layout` -- `Column` is the container whose `content` declares no default, so it
    //    is the declaration that is unreachable until a Python callable can cross, which is what
    //    `ComposableRenderTest` renders.
    // 3. `ui.graphics` -- where every *value* material3 asks for is built: `Color`, and with it
    //    `ImageBitmap`, `BitmapPainter` and the `vector` and `painter` subpackages, since the match
    //    is a namespace one. `a6742a1c` pinned `Icon` and `lightColorScheme` as unreachable and this
    //    line is the whole of why: both were asking for a value from a package nothing walked. See
    //    `IconRenderTest`.
    // 4. `ui.res` -- the one thing a *constructed* value cannot be. An `ImageBitmap` with no pixels
    //    written into it is transparent, and writing them needs `Canvas.drawRect`, an **instance**
    //    method (`ArtifactScanner.kotlinCandidates` binds `ACC_STATIC` only). Loading an image is
    //    static all the way down -- `openResource` -> `loadImageBitmap`, and `painterResource` for
    //    the `Painter` overload -- so this is what makes `Icon` draw a pixel rather than merely
    //    compose.
    artifactIncludePackages.set(
        listOf(
            "androidx.compose.material3",
            "androidx.compose.foundation.layout",
            "androidx.compose.ui.graphics",
            "androidx.compose.ui.res",
            "androidx.compose.ui",
        ),
    )
    generateStubs.set(false)
}

/**
 * The same JVM `:ksp-fixtures:artifact`'s tests need: JDK 21 with the preview FFM API, and a
 * `java.library.path` for the `libpython` that `manager.loadLibPython` extracts next to the working
 * directory.
 */
tasks.named<Test>("desktopTest") {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    jvmArgs("--enable-preview", "-Djava.library.path=.")
    // Skiko renders offscreen here (`ImageComposeScene`), so no window and no display is needed --
    // but AWT still initialises, and on a CI box without one that is the difference between a
    // headless surface and a crash.
    systemProperty("java.awt.headless", "true")
}
