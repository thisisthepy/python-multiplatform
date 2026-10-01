/**
 * The artefact walker's klib half, end to end: a real third-party `.klib` the build resolves,
 * walked at build time by `KlibScanner`, installed into the same `UpcallTable` KSP's own fragments
 * go into.
 *
 * `:ksp-fixtures:artifact` is `docs/design/ecosystem.md` §5b's second producer over a **jar** -- this is
 * the same producer over a **klib**, ROADMAP §16e's "investigated, not implemented" half. One target
 * only, and it is `androidNativeArm64` rather than `desktop`: a jar is what a JVM target resolves, a
 * klib is what a Kotlin/Native target resolves, and `PythonArtifactBindingsTask
 * .artifactConfiguration`/`.artifactSourceSet` are single-valued (ROADMAP §16f #1) -- one module can
 * wire one walked configuration, so this fixture is the klib producer's own module rather than a
 * second target bolted onto `:artifact`.
 *
 * `kotlinx-coroutines-core` is the klib walked, for the same reason JUnit is `:artifact`'s: a real,
 * independently-versioned third-party artefact nothing here controls or cooperates with.
 *
 * ### The suite is linked by the build and run by hand
 *
 * KGP gives `androidNativeArm64` a `linkDebugTestAndroidNativeArm64` and **no run task** -- it
 * registers one only where it knows how to reach a host, and an Android device is neither the build
 * machine nor `simctl`. `:python-multiplatform` solved that for itself with a hand-written
 * `androidNativeArm64Test` task (device selection, `adb push --sync` of a 60 MB stdlib, a
 * TeamCity-to-JUnit-XML parser) that is project-local. Until that is generalised, this module's
 * suite runs like this, reusing the payload that task already stages on the device:
 *
 *     ./gradlew :python-multiplatform:androidNativeArm64Test   # once: stages lib/ and the stdlib
 *     ./gradlew :ksp-fixtures:klib-artifact:linkDebugTestAndroidNativeArm64
 *     D=/data/local/tmp/pmp-nativetest-arm64-v8a
 *     adb push ksp-fixtures/klib-artifact/build/bin/androidNativeArm64/debugTest/test.kexe \
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

kotlin {
    androidNativeArm64 {
        // Mirrors `:ksp-fixtures:app`'s own androidNativeArm64 linker config: the test binary needs
        // the same CPython symbols resolved at final link time, since `python-multiplatform`'s klib
        // only declares them via cinterop and does not itself embed a static libpython.
        val downloadDir = project(":python-multiplatform").layout.buildDirectory.dir("python-standalone").get().asFile
        val pyVersion = project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString()
        val libVersion = pyVersion.split('.').subList(0, 2).joinToString(".")
        val targetExtractDir = "$downloadDir/extracted/$pyVersion/android-aarch64/prefix"
        binaries.getTest(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG).linkerOpts.addAll(
            listOf("-L$targetExtractDir/lib/", "-lpython$libVersion", "-Wl,--allow-shlib-undefined"),
        )
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
        val androidNativeArm64Main by getting {
            dependencies {
                // A real, separately-published klib. Nothing about it knows it is going to be
                // walked -- KlibScanner reads its ABI the same way it would read anyone else's.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            }
        }
        val androidNativeArm64Test by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

pythonBindings {
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    artifactConfiguration.set("androidNativeArm64CompileKlibraries")
    artifactSourceSet.set("androidNativeArm64Main")
    // Broad on purpose, unlike `:artifact`'s narrow `junit.runner`/`junit.framework`: coroutines'
    // public surface is almost entirely `CoroutineScope`/`Job`/`Flow`-typed extensions, plus a couple
    // of `@PublishedApi internal` declarations that are binary-visible but not source-visible outside
    // the library that owns them -- none of those are bindable today, so the whole namespace yields
    // **zero** declarations. `WalkedKlibArtifactTableTest` pins that as the correct number, not a gap:
    // an earlier version bound the `@PublishedApi internal` one and failed to compile the first time
    // its output was actually built rather than only unit-tested -- `KlibScanWorkAction`'s KDoc has
    // the story. See that file's own KDoc for what the walker declines and why.
    artifactIncludePackages.set(listOf("kotlinx.coroutines"))
}
