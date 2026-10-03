// Only what a consumer writes: the Kotlin plugin, the published bindings plugin, the published
// library. No linkerOpts, no Python.xcframework copy, no stdlib staging -- the plugin provides them
// (issue #90): stageIosPythonXcframework, the -framework Python -F... flags on the framework below,
// stageIosPythonHomeForXcode and writeIosInstallPythonScript.
plugins {
    kotlin("multiplatform") version "2.4.20-Beta2"
    id("io.github.thisisthepy.python.multiplatform.bindings") version "3.13.0"
}

kotlin {
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "ConsumerApp"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.thisisthepy:python-multiplatform:3.14.7-alpha01")
        }
    }
}
