import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import python.multiplatform.gradle.GenerateAotLibraryBindingsTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // `apply false`: the plugin's classes (GenerateAotLibraryBindingsTask) are on this script's classpath and
    // nothing is applied. Applying the bindings plugin would apply KSP and stage PYTHONHOME on every Test task,
    // which is the CPython this fixture must not have (see the task's KDoc). Leader decision 2026-11-16 (not
    // owner-confirmed): the generated Kotlin does not depend on python-multiplatform.
    id("org.thisisthepy.python.multiplatform.bindings") apply false
}

// ---------------------------------------------------------------------------------------------
// TypedPython AOT libraries called from Kotlin with no CPython (SPEC N-17, #458)
// ---------------------------------------------------------------------------------------------
//
// Task graph, per library `<p>` (nbody, errors, bigints):
//
//   describe<P>Library   python describe_library.py: the front end and the generator only, writes <p>.json. No C
//        |               compiler runs. Needs the host Python (pyrefly).
//   generate<P>Bindings  GenerateAotLibraryBindingsTask: <p>.json -> Kotlin in build/generated/aot/<p>, added to
//        |               the desktopTest source set.
//   compileKotlinDesktop / compileTestKotlinDesktop   depend on the generate tasks, never on a C build.
//
//   build<P>Library      python -m typedpython aot-lib: links the shared library with the C compiler. A dependency
//                        of desktopTest only, so compiling the Kotlin does not run the C compiler.
//
// There is no dependency on :python-multiplatform or on any artefact that embeds CPython. The host Python runs
// the compiler at build time; the test JVM has no libpython (the test asserts it).
//
// Without a host Python (-PtypedpythonPython=<interpreter>, else python-multiplatform-ksp/.venv/bin/python) the real
// tests cannot be compiled, because the Kotlin they call is generated from the host Python's output. The module then
// compiles `src/skippedTest` instead, whose one test reports itself skipped with the reason. Locally that is allowed;
// CI turns a skip into a failure.

val typedPythonHost: File? = (findProperty("typedpythonPython") as String?)?.let { File(it) }
    ?: rootDir.resolve("python-multiplatform-ksp/.venv/bin/python").takeIf { it.isFile }

val skipReason: String? = when {
    typedPythonHost == null ->
        "no host Python with pyrefly 1.3.2: pass -PtypedpythonPython=<interpreter> or create " +
            "python-multiplatform-ksp/.venv (pyproject.toml there pins pyrefly)"
    typedPythonHost.isFile != true -> "-PtypedpythonPython=$typedPythonHost does not exist"
    else -> null
}

class AotLibrarySpec(val prefix: String, val objectName: String, val source: File, val exports: String)

val aotLibraries = listOf(
    AotLibrarySpec(
        prefix = "nbody",
        objectName = "Nbody",
        source = rootDir.resolve("benchmarks/typedpython/py/nbody.py"),
        exports = "make_x,make_y,make_z,make_vx,make_vy,make_vz,make_mass,offset_momentum,advance,energy",
    ),
    AotLibrarySpec(
        prefix = "errors",
        objectName = "Errors",
        source = file("src/desktopTest/aot/errors.py"),
        exports = "rem,at,triple,flip,largest,same_list,count_up,set_first,keywords,alias_effect",
    ),
    AotLibrarySpec(
        prefix = "bigints",
        objectName = "Bigints",
        source = file("src/desktopTest/aot/bigints.py"),
        exports = "fill_factorials,make,total,total_of_factorials,raw_sum",
    ),
)

val compilerDir = rootDir.resolve("python-multiplatform-ksp/src/main/python")
val describeScript = file("describe_library.py")
val aotTmpDir = layout.buildDirectory.dir("aot/tmp")

val buildTasks = mutableListOf<TaskProvider<Exec>>()
val generateTasks = mutableListOf<TaskProvider<GenerateAotLibraryBindingsTask>>()

for (lib in aotLibraries) {
    val describeDir = layout.buildDirectory.dir("aot/describe/${lib.prefix}")
    val libraryDir = layout.buildDirectory.dir("aot/${lib.prefix}")

    val describe = tasks.register<Exec>("describe${lib.objectName}Library") {
        group = "verification"
        description = "Writes ${lib.prefix}.json for ${lib.source.name} (front end and generator only, no C compiler)."
        onlyIf("AOT library: ${skipReason ?: "enabled"}") { skipReason == null }
        inputs.files(fileTree(compilerDir) { exclude("**/__pycache__/**") }).withPropertyName("compiler")
        inputs.file(lib.source).withPropertyName("module")
        inputs.file(describeScript).withPropertyName("script")
        inputs.property("exports", lib.exports)
        inputs.property("host", typedPythonHost?.absolutePath ?: "")
        outputs.dir(describeDir)
        executable = typedPythonHost?.absolutePath ?: "python3"
        // Pyrefly writes temporary files; keep them inside this build directory (AGENTS.md section 2).
        environment("TMPDIR", aotTmpDir.get().asFile.absolutePath)
        environment("PYTHONDONTWRITEBYTECODE", "1")
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                describeScript.absolutePath, compilerDir.absolutePath, lib.source.absolutePath,
                describeDir.get().asFile.absolutePath, lib.prefix, lib.exports,
            )
        })
        doFirst { aotTmpDir.get().asFile.mkdirs() }
    }

    generateTasks += tasks.register<GenerateAotLibraryBindingsTask>("generate${lib.objectName}Bindings") {
        group = "verification"
        description = "Kotlin wrappers of the ${lib.prefix} library."
        dependsOn(describe)
        descriptionFile.set(describeDir.map { it.file("${lib.prefix}.json") })
        packageName.set("fixture.aot.${lib.prefix}")
        objectName.set(lib.objectName)
        outputDirectory.set(layout.buildDirectory.dir("generated/aot/${lib.prefix}"))
    }

    buildTasks += tasks.register<Exec>("build${lib.objectName}Library") {
        group = "verification"
        description = "Links lib${lib.prefix} from ${lib.source.name} with python -m typedpython aot-lib (runs the C compiler)."
        onlyIf("AOT library: ${skipReason ?: "enabled"}") { skipReason == null }
        inputs.files(fileTree(compilerDir) { exclude("**/__pycache__/**") }).withPropertyName("compiler")
        inputs.file(lib.source).withPropertyName("module")
        inputs.property("exports", lib.exports)
        inputs.property("host", typedPythonHost?.absolutePath ?: "")
        outputs.dir(libraryDir)
        executable = typedPythonHost?.absolutePath ?: "python3"
        environment("PYTHONPATH", compilerDir.absolutePath)
        environment("TMPDIR", aotTmpDir.get().asFile.absolutePath)
        environment("PYTHONDONTWRITEBYTECODE", "1")
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-m", "typedpython", "aot-lib", lib.source.absolutePath,
                "--export", lib.exports, "--prefix", lib.prefix, "-o", libraryDir.get().asFile.absolutePath,
            )
        })
        doFirst { aotTmpDir.get().asFile.mkdirs() }
    }
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
            if (skipReason == null) {
                kotlin.srcDir("src/aotTest/kotlin")
                generateTasks.forEach { kotlin.srcDir(it) }
            } else {
                kotlin.srcDir("src/skippedTest/kotlin")
            }
        }
    }
}

tasks.named<Test>("desktopTest") {
    // The same JVM the desktop FFM tests of :python-multiplatform and the compose fixture use: JDK 21 with the
    // preview java.lang.foreign API (python-multiplatform/build.gradle.kts, desktopTest). The generated helper
    // also works on JDK 22 and later, where --enable-preview is harmless.
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    jvmArgs("--enable-preview", "-Djava.library.path=.")
    environment("PYTHONDONTWRITEBYTECODE", "1")
    val reason = skipReason
    if (reason == null) {
        // The library builds link C, so they are a dependency of the test task and of nothing that compiles.
        buildTasks.forEach { dependsOn(it) }
        for (lib in aotLibraries) {
            val dir = layout.buildDirectory.dir("aot/${lib.prefix}")
            inputs.dir(dir).withPropertyName("${lib.prefix}Library")
            // The description `aot-lib` wrote beside the library; the test reads the library's file name from it.
            systemProperty("aot.${lib.prefix}.description", dir.get().file("${lib.prefix}.json").asFile.absolutePath)
        }
        // A pure-CPython reference is not needed at run time: the expected values are embedded in the test.
    } else {
        systemProperty("aot.skip", reason)
        doFirst { logger.warn("AOT library tests will be SKIPPED: $reason") }
    }
}
