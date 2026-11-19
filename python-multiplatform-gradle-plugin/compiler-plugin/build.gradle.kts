/**
 * The consumer-side Kotlin compiler plugin of `docs/design/klib-composable-and-defaults.md` B3
 * (owner decision 2026-11-19, python-multiplatform#191).
 *
 * It lives under the Gradle plugin's directory because the Gradle plugin is what applies it, but it
 * is a project of the **root** build (`settings.gradle.kts`), not of the included plugin build: the
 * plugin build compiles on Gradle's embedded Kotlin (2.0.20), and an `IrGenerationExtension` has to
 * be compiled against the compiler that loads it, the consumer's (`libs.versions.kotlin`).
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Provided by the compiler process that loads the plugin; never shipped with it.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

apply(from = rootProject.file("gradle/central-publishing.gradle.kts"))
