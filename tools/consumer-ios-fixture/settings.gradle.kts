// A consumer *outside* this repository's build (issue #90): it resolves the bindings plugin, the
// KSP processor and the library from mavenLocal() only -- nothing here names a project of the
// repository it happens to sit in. Procedure: docs/platforms/ios-app-bundle.md, "From a consumer".
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        google()
    }
}

rootProject.name = "consumer-ios-fixture"
include(":app")
