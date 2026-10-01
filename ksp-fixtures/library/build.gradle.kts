import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // One line in place of the KSP plugin, two per-target `add("ksp<Target>", ...)` lines and a
    // `ksp { arg(...) }` block. The role defaults to `library`; the module name is derived from
    // the project's group and path.
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    // The Native target that actually verifies `.klib` fragment discovery
    // (docs/design/upcall-table-design.md §8's open question). Compile-only here: androidNativeArm64
    // targets a device/emulator this workspace does not run tests against.
    androidNativeArm64()
    // `:ksp-fixtures:app` grew a second Native leaf so that it would have an intermediate
    // `androidNativeMain` -- ROADMAP §13's `iosMain` shape, without Xcode. A consumer's targets
    // have to be a subset of its dependency's, so this one follows or the app module fails
    // dependency resolution ("Unresolved platforms: [androidNativeX64]") before compiling.
    androidNativeX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
    }
}

pythonBindings {
    // In-repo consumer: the processor is a project here, not a published artifact. Outside this
    // repo the default coordinates apply and this line is not needed.
    processor.set(projects.pythonMultiplatformKsp)
}
