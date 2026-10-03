import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The fixture that carries an Android plugin.
 *
 * ROADMAP §13: `id("io.github.thisisthepy.python.multiplatform.bindings")` applies
 * `com.google.devtools.ksp`, and KSP declares `MINIMUM_SUPPORTED_AGP_VERSION`. Below that AGP the
 * plugin dies at configuration time with
 *
 *   java.lang.NoSuchMethodError: 'void com.android.build.api.variant
 *       .AndroidComponentsExtension.addKspConfigurations(boolean)'
 *
 * in every module carrying an Android plugin. Neither `:ksp-fixtures:library` nor
 * `:ksp-fixtures:app` applies one, so both stayed green while every Android consumer of the
 * plugin was broken. **This module exists to fail in that situation**: if the AGP/KSP pair ever
 * drifts back apart, `gradlew :ksp-fixtures:android:testDebugUnitTest` stops configuring at all.
 *
 * It paid for itself on its first run by failing for a *second* reason: AGP names its KSP
 * configurations with the build type last (`kspAndroidTestDebug`), so the plugin's
 * `endsWith("Test")` filter put the processor on the unit-test compilation, which emitted a
 * duplicate fragment that shadowed `main`'s. See `PythonBindingsPlugin.isBindingKspConfiguration`.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        // On `commonMain` rather than `androidMain`: the `@InstallsUpcallTable expect fun` lives
        // in `src/commonMain` and needs the annotation on its own compile classpath. An `expect`
        // has to sit in a source set the target's source set depends on, which is the whole
        // reason the seam exists.
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
                implementation(libs.androidx.test.junit)
                implementation("androidx.test:runner:1.6.2")
            }
        }
    }
}

android {
    namespace = "fixture.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

pythonBindings {
    // `com.android.library` is not `com.android.application`, so the aggregating role is not
    // inferred -- stated for the same reason `:ksp-fixtures:app` states it. Aggregating is what
    // puts a `FunctionTable` in front of the test below; a `library` role would only emit a
    // fragment, and the test would be asserting on the generator rather than on the table.
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)
}
