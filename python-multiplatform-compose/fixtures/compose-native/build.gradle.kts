/**
 * #191: the composable claim of `:python-multiplatform-compose:fixtures:compose`, on Kotlin/Native
 * desktop (macosArm64, linuxX64).
 *
 * The fork's Compose klibs are walked by `KlibScanner` (the walker's klib half) into the shared
 * `nativeMain`. `Text` and `Button` declare more than 6 defaulted parameters, so their bindings go
 * through the consumer compiler plugin (`docs/design/klib-composable-and-defaults.md` B3, owner
 * decision 2026-11-19 on #191), which rewrites each marked call to the lowered `$default` symbol. The
 * host test renders Python's composition with `ImageComposeScene`, so a binding the compiler plugin
 * did not reach fails at its first call (the marker throws) rather than drawing something.
 *
 * Linking and `PYTHONHOME` come from the bindings plugin (SPEC L-9c).
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetpack.compose)
    alias(libs.plugins.compose.compiler)
    id("org.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    applyDefaultHierarchyTemplate()

    macosArm64()
    linuxX64 {
        // Skiko's linuxX64 klib uses Fontconfig but declares no linker option for it (its own native
        // samples add these), so a test binary missed every Fc* symbol (batch run 44973431716). The
        // host's libfontconfig is newer than Kotlin/Native's sysroot glibc, so its own undefined
        // versioned references are left to the dynamic loader.
        binaries.all {
            linkerOpts("-L/usr/lib/x86_64-linux-gnu", "-l:libfontconfig.so.1", "--allow-shlib-undefined")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
                implementation(projects.pythonMultiplatformCompose)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

pythonBindings {
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    // One walked configuration per module (ROADMAP §16f #1): linuxX64's klibs, generated into the
    // shared `nativeMain`, which macosArm64 compiles too (the fork publishes the same API for both).
    artifactConfiguration.set("linuxX64CompileKlibraries")
    artifactSourceSet.set("nativeMain")
    artifactIncludePackages.set(
        listOf(
            "androidx.compose.material3",
            "androidx.compose.foundation.layout",
            "androidx.compose.ui.graphics",
        ),
    )
    compilerPlugin.set(project(":python-multiplatform-compiler-plugin"))
}

// A failing host test prints its whole exception, message and Python error included: Gradle's default
// short format printed only "PyException at null:-1" (batch run 44973431716).
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
