import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    androidNativeArm64 {
        // Mirrors :python-multiplatform's own androidNativeArm64 test linker config
        // (build.gradle.kts) -- this module's test binary needs the same CPython symbols
        // resolved at final link time, since python-multiplatform's klib only declares them via
        // cinterop and does not itself embed a static libpython. This is a throwaway measurement
        // wiring: only linkDebugTestAndroidNativeArm64 needs it (docs/design/upcall.md
        // §11.1's tree-shaking measurement), not the ordinary JVM-only fixture test path.
        val downloadDir = project(":python-multiplatform").layout.buildDirectory.dir("python-standalone").get().asFile
        val pyVersion = project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString()
        val libVersion = pyVersion.split('.').subList(0, 2).joinToString(".")
        // Version-keyed, matching python-multiplatform's `extractedDir`.
        val targetExtractDir = "$downloadDir/extracted/$pyVersion/android-aarch64/prefix"
        binaries.getTest(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.DEBUG).linkerOpts.addAll(
            listOf("-L$targetExtractDir/lib/", "-lpython$libVersion", "-Wl,--allow-shlib-undefined"),
        )
    }
    // The *second* Native leaf, and it is here for one reason: two of them make the default
    // hierarchy give this module an `androidNativeMain` intermediate source set. That is the
    // `iosMain` shape from ROADMAP §13 -- a source set the generating compilations depend on,
    // which therefore can neither name `FunctionTable` nor host an `actual` for it -- reproduced
    // without needing Xcode. Compile-only: no test binary is linked for it.
    androidNativeX64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
                implementation(projects.kspFixtures.library)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

pythonBindings {
    // This module applies neither `application` nor `com.android.application`, so the role that
    // makes it aggregate has to be stated. Everything else is inferred.
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)
}

// ---------------------------------------------------------------------------------------------
// TypedPython: compiled code calling Kotlin through the binder (issue #45, `TypedPythonKotlinCallTest`)
// ---------------------------------------------------------------------------------------------
//
// `buildTypedPythonBridge` compiles `src/desktopTest/typedpython/tp_kotlin_bridge.py` with the
// TypedPython pipeline (`python-multiplatform-ksp/src/main/python/typedpython`) into an extension
// for the CPython this fixture *embeds*, not the host's:
//
// - headers: `python-multiplatform`'s `cpythonIncludeDirectories` provider
//   (`docs/platforms/python-version-acquisition.md` §7), for this host's macOS target and the
//   flavour `-PpythonFreeThreaded` selects. The provider carries the `downloadPython_*` task.
// - extension suffix: `typedpython/build_bridge.py` reads `EXT_SUFFIX` from the `_sysconfigdata_*.py`
//   of that same extracted tree and cross-checks `Py_GIL_DISABLED` against its `pyconfig.h`.
//
// The pipeline itself runs on a *host* Python that has Pyrefly 1.3.2 (the gate needs it):
// `-PtypedpythonPython=<interpreter>`, else `python-multiplatform-ksp/.venv/bin/python` when that
// exists. Without one -- or on a host other than macOS, where the JVM does not load libpython with
// global symbols and a `dynamic_lookup` extension could not resolve the C API -- the task is skipped
// and the test reports itself skipped with the reason. When the task does run, any failure fails the
// build: a gate error, a function left interpreted, a C compiler error.
//
// The provider is reached by name and reflection, not `getByType<CPythonIncludeDirectories>()`: this
// project and `:python-multiplatform` each apply the bindings plugin in their own `plugins {}` block,
// so each build script may see its own copy of the plugin's classes, and a typed lookup across the
// two can fail with "extension of type ... does not exist" even though it is registered.
evaluationDependsOn(":python-multiplatform")

val typedPythonHost: File? = (findProperty("typedpythonPython") as String?)?.let { File(it) }
    ?: rootDir.resolve("python-multiplatform-ksp/.venv/bin/python").takeIf { it.isFile }

val typedPythonMacTarget: String? =
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) null
    else when (System.getProperty("os.arch")) {
        "aarch64", "arm64" -> "macos-aarch64"
        "x86_64", "amd64" -> "macos-x86_64"
        else -> null
    }

val typedPythonSkipReason: String? = when {
    typedPythonMacTarget == null ->
        "host ${System.getProperty("os.name")}/${System.getProperty("os.arch")} is not a macOS desktop target"
    typedPythonHost == null ->
        "no host Python with pyrefly 1.3.2: pass -PtypedpythonPython=<interpreter> or create " +
            "python-multiplatform-ksp/.venv (pyproject.toml there pins pyrefly)"
    typedPythonHost?.isFile != true -> "-PtypedpythonPython=$typedPythonHost does not exist"
    else -> null
}

@Suppress("UNCHECKED_CAST")
val typedPythonIncludeDir: Provider<Directory>? = typedPythonMacTarget?.let { target ->
    val includes = project(":python-multiplatform").extensions.getByName("cpythonIncludeDirectories")
    val flavourClass = includes.javaClass.classLoader.loadClass("python.multiplatform.gradle.CPythonFlavour")
    val freeThreaded = (findProperty("pythonFreeThreaded") as String?)?.toBoolean() ?: false
    val flavour = flavourClass.enumConstants.single { (it as Enum<*>).name == if (freeThreaded) "FREE_THREADED" else "GIL" }
    includes.javaClass.getMethod("includeDir", String::class.java, flavourClass)
        .invoke(includes, target, flavour) as Provider<Directory>
}

val typedPythonBridgeDir = layout.buildDirectory.dir("typedpython/bridge")

val buildTypedPythonBridge by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles tp_kotlin_bridge.py with TypedPython against the embedded CPython's headers (#45)."

    val compilerDir = rootDir.resolve("python-multiplatform-ksp/src/main/python")
    val sourceDir = file("src/desktopTest/typedpython")
    val script = file("typedpython/build_bridge.py")
    val outDir = typedPythonBridgeDir
    val tmpDir = layout.buildDirectory.dir("typedpython/tmp")
    val include = typedPythonIncludeDir
    val skip = typedPythonSkipReason

    onlyIf("TypedPython bridge: ${skip ?: "enabled"}") { skip == null }
    inputs.files(fileTree(compilerDir) { exclude("**/__pycache__/**") }).withPropertyName("compiler")
    inputs.files(fileTree(sourceDir) { exclude("**/__pycache__/**") }).withPropertyName("sources")
    inputs.file(script).withPropertyName("script")
    if (include != null && skip == null) inputs.dir(include).withPropertyName("cpythonInclude")
    inputs.property("host", typedPythonHost?.absolutePath ?: "")
    outputs.dir(outDir)

    executable = typedPythonHost?.absolutePath ?: "python3"
    // Pyrefly and the gate write temporary files; keep them inside this build directory (AGENTS.md §2).
    environment("TMPDIR", tmpDir.get().asFile.absolutePath)
    environment("PYTHONDONTWRITEBYTECODE", "1")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            script.absolutePath,
            compilerDir.absolutePath,
            File(sourceDir, "tp_kotlin_bridge.py").absolutePath,
            include!!.get().asFile.absolutePath,
            outDir.get().asFile.absolutePath,
        )
    })
    doFirst { tmpDir.get().asFile.mkdirs() }
}

tasks.named<Test>("desktopTest") {
    dependsOn(buildTypedPythonBridge)
    if (typedPythonSkipReason == null) {
        inputs.dir(typedPythonBridgeDir).withPropertyName("typedPythonBridge")
        systemProperty(
            "typedpython.bridge.properties",
            typedPythonBridgeDir.get().file("bridge.properties").asFile.absolutePath,
        )
    } else {
        systemProperty("typedpython.bridge.skip", typedPythonSkipReason)
    }
}
