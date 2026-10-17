/**
 * The artefact walker's klib half, end to end: a real third-party `.klib` the build resolves,
 * walked at build time by `KlibScanner`, installed into the same `UpcallTable` KSP's own fragments
 * go into.
 *
 * `:python-multiplatform-gradle-plugin-fixtures:artifact` is `docs/design/ecosystem.md` §5b's second producer over a **jar** -- this is
 * the same producer over a **klib**. Kotlin/Native targets rather than `desktop`: a jar is what a JVM
 * target resolves, a klib is what a Kotlin/Native target resolves, and `PythonArtifactBindingsTask
 * .artifactConfiguration`/`.artifactSourceSet` are single-valued (ROADMAP §16f #1) -- one module can
 * wire one walked configuration, so this fixture is the klib producer's own module rather than a
 * second target bolted onto `:artifact`. Since issue #234 it builds `androidNativeArm64` and
 * `iosSimulatorArm64`: the androidNativeArm64 klib is walked, the table is generated into the shared
 * `nativeMain`, and the suite in `nativeTest` runs through Gradle on the simulator.
 *
 * `kotlinx-coroutines-core` is the klib walked, for the same reason JUnit is `:artifact`'s: a real,
 * independently-versioned third-party artefact nothing here controls or cooperates with.
 *
 * ### On androidNativeArm64 the suite is linked by the build and run by hand (`iosSimulatorArm64Test` runs it)
 *
 * KGP gives `androidNativeArm64` a `linkDebugTestAndroidNativeArm64` and **no run task** -- it
 * registers one only where it knows how to reach a host, and an Android device is neither the build
 * machine nor `simctl`. `:python-multiplatform` solved that for itself with a hand-written
 * `androidNativeArm64Test` task (device selection, `adb push --sync` of a 60 MB stdlib, a
 * TeamCity-to-JUnit-XML parser) that is project-local. Until that is generalised, this module's
 * suite runs like this, reusing the payload that task already stages on the device:
 *
 *     ./gradlew :python-multiplatform:androidNativeArm64Test   # once: stages lib/ and the stdlib
 *     ./gradlew :python-multiplatform-gradle-plugin-fixtures:klib-artifact:linkDebugTestAndroidNativeArm64
 *     D=/data/local/tmp/pmp-nativetest-arm64-v8a
 *     adb push python-multiplatform-gradle-plugin/fixtures/klib-artifact/build/bin/androidNativeArm64/debugTest/test.kexe \
 *       $D/klibtest.kexe
 *     adb shell "chmod 755 $D/klibtest.kexe && cd $D && \
 *       LD_LIBRARY_PATH=$D/lib PYTHONHOME=$D ./klibtest.kexe --ktest_logger=TEAMCITY"
 *
 * **A green ordinary build therefore says these tests compile and link, not that they pass.**
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

val pythonDownloadDir = project(":python-multiplatform").layout.buildDirectory.dir("python-standalone").get().asFile
val fixturePythonVersion = project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString()
val fixturePythonLibVersion = fixturePythonVersion.split('.').subList(0, 2).joinToString(".")

kotlin {
    // Applied now rather than after evaluation, so `nativeMain`/`nativeTest` exist for the blocks below
    // and for `pythonBindings.artifactSourceSet` (issue #234).
    applyDefaultHierarchyTemplate()

    androidNativeArm64 {
        // Mirrors `:python-multiplatform-ksp:fixtures:app`'s own androidNativeArm64 linker config: the test binary needs
        // the same CPython symbols resolved at final link time, since `python-multiplatform`'s klib
        // only declares them via cinterop and does not itself embed a static libpython.
        val targetExtractDir = "$pythonDownloadDir/extracted/$fixturePythonVersion/android-aarch64/prefix"
        binaries.getTest(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG).linkerOpts.addAll(
            listOf("-L$targetExtractDir/lib/", "-lpython$fixturePythonLibVersion", "-Wl,--allow-shlib-undefined"),
        )
    }
    // Issue #234: the simulator target, so the suite runs through Gradle (`iosSimulatorArm64Test`)
    // rather than only being linked. Linked the way `:python-multiplatform`'s own simulator test
    // binary is: against the xcframework's simulator slice, with an explicit `-rpath`, because
    // `Python.framework` has an @rpath-relative install name and is not copied beside the binary.
    iosSimulatorArm64 {
        val slice = "$pythonDownloadDir/extracted/$fixturePythonVersion/ios/Python.xcframework/ios-arm64_x86_64-simulator"
        binaries.getTest(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG).linkerOpts.addAll(
            listOf("-framework", "Python", "-F$slice", "-rpath", slice),
        )
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
        // Both native targets share the walked table and the suite (issue #234): the table is
        // generated into `nativeMain`, and the tests live in `nativeTest`.
        val nativeMain by getting {
            dependencies {
                // A real, separately-published klib. Nothing about it knows it is going to be
                // walked -- KlibScanner reads its ABI the same way it would read anyone else's.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val nativeTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// The iOS `Python.framework` carries no standard library; `:python-multiplatform` unpacks the
// simulator's, and the test binary is pointed at it the way that module points its own.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    dependsOn(":python-multiplatform:extractIosSimulatorStdlib")
    val pythonHome = project(":python-multiplatform").layout.buildDirectory.dir("python-stdlib/ios-simulator").get().asFile.absolutePath
    // simctl only forwards variables into the spawned process when they carry this prefix.
    environment("SIMCTL_CHILD_PYTHONHOME", pythonHome)
    environment("PYTHONHOME", pythonHome)
}

pythonBindings {
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    // The androidNativeArm64 klib is walked (what `KlibScannerTest` walks too); the table lands in
    // `nativeMain`, so the simulator target compiles the same calls against its own klib.
    artifactConfiguration.set("androidNativeArm64CompileKlibraries")
    artifactSourceSet.set("nativeMain")
    // Broad on purpose, unlike `:artifact`'s narrow `junit.runner`/`junit.framework`: coroutines'
    // public surface is almost entirely `CoroutineScope`/`Job`/`Flow`-typed extensions, plus a couple
    // of `@PublishedApi internal` declarations that are binary-visible but not source-visible outside
    // the library that owns them. Those stay declined; since issue #234 the factories whose types are
    // the klib's own classes bind (`Job`, `Mutex`, ...), and `WalkedKlibArtifactTableTest` pins the list.
    // An earlier version bound the `@PublishedApi internal` one and failed to compile the first time
    // its output was actually built rather than only unit-tested -- `KlibScanWorkAction`'s KDoc has
    // the story. See that file's own KDoc for what the walker declines and why.
    artifactIncludePackages.set(listOf("kotlinx.coroutines"))
}
