import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The Compose host for python-multiplatform: the `@Composable` entry point that draws a Python-declared
 * root (`PythonAppView`, `PythonWidget`, `PythonLauncher`) and the composer/callable plumbing it shares with every Python call into a
 * composable.
 *
 * A separate module so that `:python-multiplatform` itself never depends on Compose (same naming as
 * `-ksp` and `-gradle-plugin`). JVM targets first -- desktop and Android share the code, which is all
 * in `commonMain`; iOS and wasm come later.
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

    sourceSets {
        commonMain.dependencies {
            api(projects.pythonMultiplatform)
            api(compose.runtime)
            api(compose.ui)
            api(compose.runtimeSaveable)
        }
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
