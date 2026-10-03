import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The Compose host for python-multiplatform: the `@Composable` entry point that draws a Python-declared
 * root (`PythonContent`) and the composer/callable plumbing it shares with every Python call into a
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
}

kotlin {
    androidTarget {
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
