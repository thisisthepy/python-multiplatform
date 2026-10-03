import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    androidNativeArm64 {
        // Mirrors :python-multiplatform's own androidNativeArm64 test linker config
        // (build.gradle.kts) -- this module's test binary needs the same CPython symbols
        // resolved at final link time, since python-multiplatform's klib only declares them via
        // cinterop and does not itself embed a static libpython. This is a throwaway measurement
        // wiring: only linkDebugTestAndroidNativeArm64 needs it (docs/design/upcall.md
        // §11.1's tree-shaking measurement), not the ordinary JVM-only fixture test path.
        val downloadDir = project(":python-multiplatform").layout.buildDirectory.dir("python-standalone").get().asFile
        val pyVersion = project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString()
        val libVersion = pyVersion.split('.').subList(0, 2).joinToString(".")
        // Version-keyed, matching python-multiplatform's `extractedDir`.
        val targetExtractDir = "$downloadDir/extracted/$pyVersion/android-aarch64/prefix"
        binaries.getTest(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG).linkerOpts.addAll(
            listOf("-L$targetExtractDir/lib/", "-lpython$libVersion", "-Wl,--allow-shlib-undefined"),
        )
    }
    // The *second* Native leaf, and it is here for one reason: two of them make the default
    // hierarchy give this module an `androidNativeMain` intermediate source set. That is the
    // `iosMain` shape from ROADMAP §13 -- a source set the generating compilations depend on,
    // which therefore can neither name `FunctionTable` nor host an `actual` for it -- reproduced
    // without needing Xcode. Compile-only: no test binary is linked for it.
    androidNativeX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
                implementation(projects.kspFixtures.library)
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
    // This module applies neither `application` nor `com.android.application`, so the role that
    // makes it aggregate has to be stated. Everything else is inferred.
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)
}
