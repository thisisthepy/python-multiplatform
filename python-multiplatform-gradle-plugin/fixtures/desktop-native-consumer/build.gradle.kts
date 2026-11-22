/**
 * SPEC L-9c (#191): a Kotlin/Native desktop app (`macosArm64`, `linuxX64`) built only from the
 * published plugin and the library, from outside `:python-multiplatform`.
 *
 * This module declares no CPython download, no linker option and no PYTHONHOME. All of it comes
 * from `org.thisisthepy.python.multiplatform.bindings`: `stagePythonHome<Target>`, `-lpython3.15t`
 * with the run-time search path beside the executable, `PYTHONHOME` for `<target>Test`, and
 * `python-multiplatform-home/` copied beside each linked executable. CI runs the test task and then
 * the executable with no `PYTHONHOME`, so the packaged prefix is the only one it can find.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("org.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    applyDefaultHierarchyTemplate()

    listOf(macosArm64(), linuxX64()).forEach { target ->
        target.binaries.executable {
            entryPoint = "fixture.desktopnative.main"
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
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
}

// A failing host test prints its whole exception: Gradle's default short format hid the probe line
// the assertion compared (batch run 44945907475).
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
