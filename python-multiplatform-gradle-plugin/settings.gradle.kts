// Plugins (kotlin-dsl and the Kotlin artifacts it pulls in) resolve from Maven Central first: with the
// default, the Plugin Portal alone, one CI job failed when the portal did not serve
// kotlin-stdlib:2.0.20 while the other jobs of the same run resolved it (batch run 44973431716).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

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
