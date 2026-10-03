rootProject.name = "python-multiplatform-gradle-plugin"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            // One source of truth for the KSP version: this plugin applies the KSP plugin and
            // pulls in the processor, and a version skew between the two is exactly the class of
            // wiring bug the plugin exists to remove.
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
