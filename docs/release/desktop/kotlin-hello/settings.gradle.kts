// The release's Maven repository zip, unpacked beside this project (see ../README.md).
val releaseRepo = file(providers.gradleProperty("pmpRepo").getOrElse("../maven-repo"))

pluginManagement {
    repositories {
        maven { url = uri(file(providers.gradleProperty("pmpRepo").getOrElse("../maven-repo"))) }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri(releaseRepo) }
        mavenCentral()
        google()
    }
}

rootProject.name = "kotlin-hello"
