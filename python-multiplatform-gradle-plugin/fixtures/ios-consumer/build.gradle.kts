/**
 * Issue #90: an iOS app built **only** from the published plugin and artifacts, from outside
 * `:python-multiplatform`.
 *
 * This module declares no `downloadPython_ios`, no linker option and no Xcode script of its own. All
 * of it comes from `io.github.thisisthepy.python.multiplatform.bindings`:
 *
 *  - `downloadPython_ios` and `preparePythonXcframework` (into this module's `build/`),
 *  - `-framework Python -F...` on the two `Probe` framework binaries below,
 *  - `stageIosPythonHome*` and `installPythonXcodeScript`.
 *
 * It does depend on `:python-multiplatform` for the Kotlin API, as any consumer depends on the
 * library. Build and run steps: `README.md`.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    applyDefaultHierarchyTemplate()

    listOf(iosSimulatorArm64(), iosArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Probe"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
    }
}

pythonBindings {
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)
    // ios { linkPythonFramework.set(false) } would switch the automatic -framework Python off.
}
