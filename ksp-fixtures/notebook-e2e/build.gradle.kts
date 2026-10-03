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
 * `:ksp-fixtures:compose`'s configuration, kept identical where it can be so that a difference in
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
                implementation(libs.compose.material.icons.core)
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
    // The same list as `:ksp-fixtures:compose` (its build file says what each entry is for). Every
    // `pythonx.compose.*` module the notebook imports maps onto one of these Kotlin packages
    // (pythonx-compose's `pythonx-map.toml`); a package not walked here is a name pythonx cannot
    // resolve, which is a fixture gap, not a pythonx one.
    artifactIncludePackages.set(
        listOf(
            "androidx.compose.material3",
            "androidx.compose.foundation.layout",
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
    val howTo = "Build it (ksp-fixtures/notebook-e2e/README.md) and pass -PpythonxComposeWheel=<file.whl or directory> " +
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
 * The same JVM `:ksp-fixtures:compose`'s tests need: JDK 21 with the preview FFM API, a
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
