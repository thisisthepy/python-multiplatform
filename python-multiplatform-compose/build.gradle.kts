import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The Compose host for python-multiplatform: the `@Composable` entry point that draws a Python-declared
 * root (`PythonAppView`, `PythonWidget`, `PythonLauncher`) and the composer/callable plumbing it shares with every Python call into a
 * composable.
 *
 * A separate module so that `:python-multiplatform` itself never depends on Compose (same naming as
 * `-ksp` and `-gradle-plugin`). Desktop JVM, Android and Kotlin/Native desktop (macosArm64, linuxX64)
 * share the code, which is all in `commonMain`; iOS and wasm come later.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetpack.compose)
    alias(libs.plugins.compose.compiler)
    id("maven-publish")
}

kotlin {
    androidTarget {
        // Without a named variant KMP creates no Android publication (ROADMAP 15d, same as the library).
        publishLibraryVariants("release")

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Kotlin/Native desktop (#191 Phase B, #386), the targets `:python-multiplatform` has since #370.
    // Upstream JetBrains Compose publishes no `ui` klib for linuxX64; the fork this repository builds
    // on publishes `runtime`, `runtime-saveable` and `ui` for both targets.
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(projects.pythonMultiplatform)
            api(compose.runtime)
            api(compose.ui)
            api(compose.runtimeSaveable)
        }
        // Same shape as the library's: commonMain -> desktopNativeMain -> {macosArm64Main, linuxX64Main}.
        // Empty today (the host is all commonMain); it is where K/N-desktop-only host code goes.
        val desktopNativeMain by creating {
            dependsOn(commonMain.get())
        }
        getByName("macosArm64Main").dependsOn(desktopNativeMain)
        getByName("linuxX64Main").dependsOn(desktopNativeMain)
    }
}

android {
    namespace = "python.multiplatform.compose"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Published to Maven Central beside the library as org.thisisthepy:python-multiplatform-compose, at the
// root's one version (#70): POM fields, sources/javadoc jars, centralStaging repository and in-memory
// signing come from the shared script (ROADMAP 15.1).
apply(from = rootProject.file("gradle/central-publishing.gradle.kts"))
