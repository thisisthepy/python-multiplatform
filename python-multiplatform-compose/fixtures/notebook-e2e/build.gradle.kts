import java.io.ByteArrayOutputStream
import org.apache.tools.ant.util.TeeOutputStream
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Issue #26: pythonx-compose's `UI.ipynb` scenarios, run end to end against a real host.
 *
 * The host is `python-multiplatform-compose`'s `PythonAppView(module = "pythonx.compose.runtime", attribute = "app_root")`
 * (#18). The Python side is the **installed pythonx-compose wheel**, not a copy of its sources and
 * not a stand-in: every `pythonx.compose.*` import in the tests must resolve to the files the wheel
 * unpacked (the tests check `__file__`). The wheel is an input:
 *
 * | Input | Gradle property | Environment |
 * |---|---|---|
 * | a `.whl` file, or a directory holding `pythonx_compose-*.whl` | `-PpythonxComposeWheel=<path>` | `PYTHONX_COMPOSE_WHEEL` |
 * | which version to pick from that directory (or to require of the file) | `-PpythonxComposeVersion=<v>` | `PYTHONX_COMPOSE_VERSION` |
 * | deliberately not running the scenarios | `-PnotebookE2e.skip=true` | `NOTEBOOK_E2E_SKIP=true` |
 *
 * **No wheel is a failure, not a skip.** Without an input every test fails by name with the message
 * below; only the explicit opt-out disables `desktopTest`, and it says so in the build log. CI decides
 * which of the two it wants.
 *
 * "Installing" a pure-Python wheel is unpacking it onto `sys.path`, which is what
 * `pip install --target` does for one; the embedded interpreter has no `pip` of its own to run.
 * [installPythonxComposeWheel] unpacks it into `build/pythonx-compose/site-packages`.
 *
 * The rest -- the Compose compiler plugin, the walked packages, the JVM flags -- is
 * `:python-multiplatform-compose:fixtures:compose`'s configuration, kept identical where it can be so that a difference in
 * result is a difference in what the notebook asks for, not in the build.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetpack.compose)
    alias(libs.plugins.compose.compiler)
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

kotlin {
    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatformCompose)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.compose.material.icons.core.get().toString()) {
                    exclude(group = "org.jetbrains.compose.runtime")
                    exclude(group = "org.jetbrains.compose.ui")
                }
                implementation(compose.desktop.currentOs)
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
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    artifactConfiguration.set("desktopCompileClasspath")
    artifactSourceSet.set("desktopMain")
    // The same list as `:python-multiplatform-compose:fixtures:compose` (its build file says what each entry is for). Every
    // `pythonx.compose.*` module the notebook imports maps onto one of these Kotlin packages
    // (pythonx-compose's `pythonx-map.toml`); a package not walked here is a name pythonx cannot
    // resolve, which is a fixture gap, not a pythonx one.
    artifactIncludePackages.set(
        listOf(
            "androidx.compose.material3",
            "androidx.compose.foundation.layout",
            // `RoundedCornerShape` for `Button(shape=...)` / `Card(shape=...)` (issue #307).
            "androidx.compose.foundation.shape",
            "androidx.compose.ui.graphics",
            "androidx.compose.ui.res",
            "androidx.compose.ui",
            "androidx.compose.material.icons",
            "androidx.compose.runtime.SnapshotStateKt",
            "androidx.compose.runtime.State",
            "androidx.compose.runtime.MutableState",
            "androidx.compose.foundation.text.input.TextFieldState",
            "androidx.compose.foundation.text.input.TextFieldStateKt",
            "python.multiplatform.compose.RememberSaveableKt",
        ),
    )
}

// ---- The pythonx-compose wheel ---------------------------------------------------------------------

val wheelInput: String? = providers.gradleProperty("pythonxComposeWheel")
    .orElse(providers.environmentVariable("PYTHONX_COMPOSE_WHEEL")).orNull?.takeIf { it.isNotBlank() }
val wheelVersion: String? = providers.gradleProperty("pythonxComposeVersion")
    .orElse(providers.environmentVariable("PYTHONX_COMPOSE_VERSION")).orNull?.takeIf { it.isNotBlank() }
val notebookSkipped: Boolean = providers.gradleProperty("notebookE2e.skip")
    .orElse(providers.environmentVariable("NOTEBOOK_E2E_SKIP")).orNull?.toBoolean() == true

/** The wheel to install, or why there is none. Exactly one of the two is non-null. */
fun resolveWheel(): Pair<File?, String?> {
    val howTo = "Build it (python-multiplatform-compose/fixtures/notebook-e2e/README.md) and pass -PpythonxComposeWheel=<file.whl or directory> " +
        "(or PYTHONX_COMPOSE_WHEEL); to skip the notebook scenarios deliberately pass -PnotebookE2e.skip=true."
    val input = wheelInput
        ?: return null to "No pythonx-compose wheel was given, so the UI.ipynb scenarios cannot run. $howTo"
    val given = rootProject.file(input)
    if (!given.exists()) return null to "The pythonx-compose wheel input does not exist: $given. $howTo"
    val versionPart = wheelVersion?.let { "pythonx_compose-$it-" }
    fun isWheel(f: File) = f.isFile && f.name.startsWith("pythonx_compose-") && f.name.endsWith(".whl")
    if (given.isDirectory) {
        val candidates = given.listFiles().orEmpty().filter(::isWheel)
            .filter { versionPart == null || it.name.startsWith(versionPart) }
            .sortedBy { it.name }
        return when (candidates.size) {
            1 -> candidates.single() to null
            0 -> null to "No pythonx_compose-${wheelVersion ?: "*"}-*.whl in $given. $howTo"
            else -> null to "Several pythonx-compose wheels in $given (${candidates.joinToString { it.name }}); " +
                "choose one with -PpythonxComposeVersion=<version> or pass the file itself."
        }
    }
    if (!isWheel(given)) return null to "$given is not a pythonx_compose-*.whl file. $howTo"
    if (versionPart != null && !given.name.startsWith(versionPart)) {
        return null to "$given is not version $wheelVersion (-PpythonxComposeVersion)."
    }
    return given to null
}

val resolvedWheel = resolveWheel()
val pythonxComposeWheel: File? = resolvedWheel.first
val pythonxComposeWheelError: String? = resolvedWheel.second
val pythonxComposeSitePackages = layout.buildDirectory.dir("pythonx-compose/site-packages")

val installPythonxComposeWheel by tasks.registering(Sync::class) {
    group = "verification"
    description = "Unpacks the pythonx-compose wheel (-PpythonxComposeWheel) where the notebook E2E tests put it on sys.path."
    val wheel = pythonxComposeWheel
    enabled = wheel != null
    if (wheel != null) from(zipTree(wheel))
    into(pythonxComposeSitePackages)
}

if (notebookSkipped) {
    logger.lifecycle(
        "notebook-e2e: desktopTest is DISABLED by -PnotebookE2e.skip=true -- the UI.ipynb scenarios did not run.",
    )
}

/**
 * The same JVM `:python-multiplatform-compose:fixtures:compose`'s tests need: JDK 21 with the preview FFM API, a
 * `java.library.path` for the extracted `libpython`, and a headless AWT.
 */
tasks.named<Test>("desktopTest") {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    jvmArgs("--enable-preview", "-Djava.library.path=.")
    systemProperty("java.awt.headless", "true")

    // The application module the notebook imports as `main`.
    val appDir = layout.projectDirectory.dir("src/desktopTest/python")
    inputs.dir(appDir)
    systemProperty("notebookE2e.appDir", appDir.asFile.absolutePath)

    val wheel = pythonxComposeWheel
    if (wheel != null) {
        dependsOn(installPythonxComposeWheel)
        inputs.file(wheel)
        systemProperty("notebookE2e.wheel", wheel.absolutePath)
        systemProperty("notebookE2e.sitePackages", pythonxComposeSitePackages.get().asFile.absolutePath)
    } else {
        systemProperty("notebookE2e.wheelError", checkNotNull(pythonxComposeWheelError))
    }
    enabled = !notebookSkipped
}

// ---- The notebook E2E in a GraalVM native image (issue #249, #191's first milestone) ----------
//
// `NotebookE2eMain` runs the same 20 scenarios as `desktopTest`, by direct calls, so they can run in a
// native image. The scenes are offscreen (`ImageComposeScene`), so no window is involved. Built the way
// `:sample:nativeCompile` builds its image: `native-image` called directly, because the GraalVM Gradle
// plugin registers no tasks for a Kotlin Multiplatform `jvm()` target. The Compose and Skiko
// configuration a closed world needs is recorded by `notebookE2eNativeAgent` into
// `src/desktopTest/native-image` and committed; python-multiplatform's own FFM registrations come
// from its jar's `META-INF/native-image`.

val e2eTestCompilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
// `native-image-main/` registers the AWT-free `Dispatchers.Main` (`NotebookE2eMainDispatcher.kt`), first on
// the path so it is found before kotlinx-coroutines-swing's; only the agent run and the image use it.
val e2eNativeMainResources = layout.projectDirectory.dir("src/desktopTest/native-image-main")
// The fork's Compose (#191): its `ui-desktop` sends the scene's main-thread work to `Dispatchers.Main`
// when it is built with `-Dcompose.awt=false` (`ExtendedMainDispatcher`), where upstream's always uses
// skiko's `SwingDispatcher`, which starts AWT; and AWT in a native image needs the JDK's `libawt`,
// which links `libjvm`. So the image takes the modules `composeExtended.modules` names from the fork,
// at `composeExtended.version` (root gradle.properties); `desktopTest` keeps upstream Compose.
val composeExtendedVersion = providers.gradleProperty("composeExtended.version").orNull
// The fork's group prefix: `org.jetbrains.compose` (its old coordinates) or its own, e.g.
// `org.thisisthepy.compose`; a module `org.jetbrains.compose.ui:ui-desktop` is then taken as
// `<prefix>.ui:ui-desktop`.
val composeExtendedGroupPrefix = providers.gradleProperty("composeExtended.groupPrefix").orNull ?: "org.thisisthepy.compose"
val composeExtendedModules = providers.gradleProperty("composeExtended.modules").orNull
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
// Where the fork's modules come from: this machine's mavenLocal (`composeExtended.mavenLocal`), or a
// Maven repository by URL (`composeExtended.repository`, e.g. the fork's GitHub Packages registry,
// #251), read with `GITHUB_ACTOR`/`GITHUB_TOKEN` when they are set (GitHub Packages asks for a token
// even for a public package). Either way only the fork's group at the fork's version is taken there.
val composeExtendedMavenLocal = providers.gradleProperty("composeExtended.mavenLocal").orNull?.toBoolean() == true
val composeExtendedRepository = providers.gradleProperty("composeExtended.repository").orNull?.takeIf { it.isNotBlank() }
if (composeExtendedVersion != null && (composeExtendedMavenLocal || composeExtendedRepository != null)) {
    val forkOnly: MavenRepositoryContentDescriptor.() -> Unit = {
        includeVersionByRegex(Regex.escape(composeExtendedGroupPrefix) + ".*", ".*", Regex.escape(composeExtendedVersion))
    }
    // A project's own repositories replace the settings' ones, so those are repeated here.
    repositories {
        if (composeExtendedMavenLocal) mavenLocal { mavenContent(forkOnly) }
        if (composeExtendedRepository != null) {
            maven {
                url = uri(composeExtendedRepository)
                System.getenv("GITHUB_TOKEN")?.let { token ->
                    credentials {
                        username = System.getenv("GITHUB_ACTOR") ?: "token"
                        password = token
                    }
                }
                mavenContent(forkOnly)
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral {
            metadataSources {
                gradleMetadata()
                mavenPom()
            }
        }
    }
}
val e2eNativeRuntime: Configuration = configurations.getByName("desktopTestRuntimeClasspath").copyRecursive().apply {
    resolutionStrategy.eachDependency {
        if (composeExtendedVersion != null && "${requested.group}:${requested.name}" in composeExtendedModules) {
            val targetGroup = requested.group
                .replaceFirst("org.jetbrains.compose", composeExtendedGroupPrefix)
                .replaceFirst("org.thisisthepy.compose", composeExtendedGroupPrefix)
            useTarget("$targetGroup:${requested.name}:$composeExtendedVersion")
            because("compose-multiplatform-core-extended: an AWT-free main dispatcher for a native image (#249)")
        }
    }
}
// The copied configuration carries the dependencies only; the main compilation's output and the
// walker's composable thunks (generated bytecode, not a dependency) are added as they are for desktopTest.
val e2eMainCompilation = kotlin.targets.getByName("desktop").compilations.getByName("main")
val e2eClasspath = files(
    e2eNativeMainResources,
    e2eTestCompilation.output.allOutputs,
    e2eMainCompilation.output.allOutputs,
    layout.buildDirectory.dir("generated/pythonComposableThunks/desktopMain"),
    e2eNativeRuntime,
)
val e2eMainClass = "fixture.notebook.NotebookE2eMainKt"
val e2eNativeConfig = layout.projectDirectory.dir("src/desktopTest/native-image")
val e2eNativeDir = layout.buildDirectory.dir("native/notebook-e2e")
val e2eAppDir = layout.projectDirectory.dir("src/desktopTest/python")
val e2eJdk25 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

fun JavaExec.notebookE2eOnTheJvm() {
    dependsOn(e2eTestCompilation.compileTaskProvider, installPythonxComposeWheel)
    classpath = e2eClasspath
    mainClass.set(e2eMainClass)
    javaLauncher.set(e2eJdk25)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dcompose.awt=false")
    systemProperty("notebookE2e.appDir", e2eAppDir.asFile.absolutePath)
    systemProperty("notebookE2e.sitePackages", pythonxComposeSitePackages.get().asFile.absolutePath)
    pythonxComposeWheel?.let { systemProperty("notebookE2e.wheel", it.absolutePath) }
    pythonxComposeWheelError?.let { systemProperty("notebookE2e.wheelError", it) }
}

tasks.register<JavaExec>("notebookE2eJvm") {
    group = "verification"
    description = "Runs NotebookE2eMain on the JVM: the 20 scenarios outside JUnit, before a native image is built from it."
    notebookE2eOnTheJvm()
}

tasks.register<JavaExec>("notebookE2eNativeAgent") {
    group = "native"
    description = "Runs NotebookE2eMain under native-image's tracing agent and writes the configuration it records into src/desktopTest/native-image."
    notebookE2eOnTheJvm()
    doFirst { e2eNativeConfig.asFile.mkdirs() }
    jvmArgs("-agentlib:native-image-agent=config-output-dir=${e2eNativeConfig.asFile.absolutePath}")
}

pythonBindings {
    nativeImageDirectory.set(e2eNativeDir)
}

val notebookE2eNativeStage by tasks.registering(Sync::class) {
    group = "native"
    description = "Puts the pythonx-compose wheel and the notebook's app beside the native image."
    dependsOn(installPythonxComposeWheel)
    from(pythonxComposeSitePackages) { into("site-packages") }
    from(e2eAppDir) { into("app") }
    into(layout.buildDirectory.dir("native/notebook-e2e-files"))
}

val notebookE2eNativeCompile by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds the notebook E2E as a GraalVM native image (build/native/notebook-e2e/notebook-e2e)."
    dependsOn(e2eTestCompilation.compileTaskProvider, "stageNativeImagePythonHome", notebookE2eNativeStage)
    inputs.files(e2eClasspath)
    inputs.dir(e2eNativeConfig)
    outputs.file(e2eNativeDir.map { it.file("notebook-e2e") })
    val launcher = e2eJdk25
    val outDir = e2eNativeDir
    val staged = layout.buildDirectory.dir("native/notebook-e2e-files")
    doFirst { outDir.get().asFile.mkdirs() }
    executable = launcher.get().metadata.installationPath.asFile.resolve("bin/native-image").absolutePath
    args(
        "-cp", e2eClasspath.asPath,
        "--no-fallback",
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.awt.headless=true",
        // The fork's switch: `ExtendedAwt.available` is a constant false, so nothing reaches AWT.
        "-Dcompose.awt=false",
        "-H:ConfigurationFileDirectories=${e2eNativeConfig.asFile.absolutePath}",
        "-o", outDir.get().asFile.resolve("notebook-e2e").absolutePath,
        e2eMainClass,
    )
    doLast {
        project.copy { from(staged); into(outDir) }
    }
}

tasks.register<Exec>("notebookE2eNativeRun") {
    group = "verification"
    description = "Runs the native image on its own (no JAVA_HOME, no PYTHONHOME) and requires 20 of 20 scenarios to pass and no libjvm."
    dependsOn(notebookE2eNativeCompile)
    val dir = e2eNativeDir
    workingDir(dir)
    executable = dir.get().asFile.resolve("notebook-e2e").absolutePath
    environment.remove("JAVA_HOME")
    environment.remove("PYTHONHOME")
    val captured = ByteArrayOutputStream()
    standardOutput = TeeOutputStream(System.out, captured)
    isIgnoreExitValue = true
    doLast {
        val out = captured.toString()
        val summary = out.lineSequence().lastOrNull { it.startsWith("NOTEBOOK_E2E:") }
            ?: throw GradleException("the native image printed no NOTEBOOK_E2E summary")
        if (summary != "NOTEBOOK_E2E: 20 passed, 0 failed of 20") throw GradleException("notebook E2E in the native image: $summary")
        val otool = ProcessBuilder("otool", "-L", executable).redirectErrorStream(true).start()
        val linked = otool.inputStream.bufferedReader().readText().also { otool.waitFor() }
        if ("libjvm" in linked) throw GradleException("the native image links libjvm:\n$linked")
        logger.lifecycle("notebook E2E native image: $summary; no libjvm")
    }
}

tasks.register("notebookE2eNativeClasspath") {
    group = "native"
    description = "Lists the Compose artefacts the notebook E2E's native image is built from."
    val files = e2eNativeRuntime
    doLast { files.files.map { it.name }.filter { it.startsWith("ui") || it.startsWith("foundation") || it.startsWith("skiko") }.sorted().forEach { println(it) } }
}
