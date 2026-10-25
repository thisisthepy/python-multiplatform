plugins {
    kotlin("jvm") version "2.4.20-Beta2"
    id("com.google.devtools.ksp") version "2.3.11"
    id("org.thisisthepy.python.multiplatform.bindings") version "@VERSION@"
    application
}

dependencies {
    implementation("org.thisisthepy:python-multiplatform:@VERSION@")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("MainKt")
    // python-multiplatform calls CPython through the JDK's Foreign Function API
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
