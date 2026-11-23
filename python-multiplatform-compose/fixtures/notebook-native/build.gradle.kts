import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeHostTest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * #191: pythonx-compose's `UI.ipynb` scenarios end to end on Kotlin/Native desktop, the counterpart of
 * `:python-multiplatform-compose:fixtures:notebook-e2e` (JVM) on the K/N path.
 *
 * The same scenarios, the same application module (`notebook-e2e/src/desktopTest/python/main.py`) and
 * the same pythonx-compose **wheel** input, run in a Kotlin/Native test executable:
 *
 * - the fork's Compose klibs are walked by `KlibScanner` into `macosArm64Main`, and every composable
 *   past B1's 6 defaults (`Text`, `Button`, `Card`, `Icon`, `TextField`) is the B3 compiler plugin's
 *   rewrite (`docs/design/klib-composable-and-defaults.md`);
 * - the screens are drawn offscreen (`ImageComposeScene`) and compared pixel for pixel with the same
 *   composables drawn from Kotlin;
 * - linking libpython 3.15t and `PYTHONHOME` for the test task come from the bindings plugin (SPEC L-9c).
 *
 * | Input | Gradle property | Environment |
 * |---|---|---|
 * | a `.whl` file, or a directory holding `pythonx_compose-*.whl` | `-PpythonxComposeWheel=<path>` | `PYTHONX_COMPOSE_WHEEL` |
 * | which version to pick from that directory | `-PpythonxComposeVersion=<v>` | `PYTHONX_COMPOSE_VERSION` |
 * | deliberately not running the scenarios | `-PnotebookE2e.skip=true` | `NOTEBOOK_E2E_SKIP=true` |
 *
 * No wheel is a failure, not a skip, as in notebook-e2e.
 *
 * macosArm64 only for now. linuxX64 is blocked: every scenario runs cell 5, which imports
 * `DefaultIcons` (`Icons.Default`), and `material-icons-core` has no linuxX64 klib (JetBrains' last one,
 * 1.7.3, stops at macOS / iOS / JS / wasm, and the fork publishes none). It is added here once the fork
 * publishes `material-icons-core` for linuxX64.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.jetpack.compose)
    alias(libs.plugins.compose.compiler)
    id("org.thisisthepy.python.multiplatform.bindings")
}

/** JetBrains' material-icons-core klib for macosArm64, exactly as published. */
val publishedIconsKlib: Configuration = configurations.detachedConfiguration(
    libs.compose.material.icons.core.get().let { icons ->
        dependencies.create("${icons.module.group}:${icons.module.name}-macosarm64:${icons.version}")
    },
).apply {
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, "kotlin-api"))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
        attribute(KotlinNativeTarget.konanTargetAttribute, "macos_arm64")
    }
}

/** [publishedIconsKlib] with its manifest naming the fork's Compose klibs (see the note further down). */
val renameIconsKlibDependencies by tasks.registering {
    val source = publishedIconsKlib
    val renamed = layout.buildDirectory.file("material-icons-core/material-icons-core.klib")
    inputs.files(source)
    outputs.file(renamed)
    doLast {
        val target = renamed.get().asFile.apply { parentFile.mkdirs() }
        ZipFile(source.singleFile).use { zip ->
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                for (entry in zip.entries()) {
                    out.putNextEntry(ZipEntry(entry.name))
                    if (!entry.isDirectory) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        if (entry.name.endsWith("/manifest")) {
                            val text = bytes.toString(Charsets.UTF_8)
                                .replace("org.jetbrains.compose.runtime\\:", "org.thisisthepy.compose.runtime\\:")
                                .replace("org.jetbrains.compose.ui\\:", "org.thisisthepy.compose.ui\\:")
                            out.write(text.toByteArray(Charsets.UTF_8))
                        } else {
                            out.write(bytes)
                        }
                    }
                    out.closeEntry()
                }
            }
        }
    }
}

kotlin {
    applyDefaultHierarchyTemplate()

    macosArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(projects.pythonMultiplatform)
                implementation(projects.pythonMultiplatformCompose)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.designsystem)
                implementation(libs.designsystem.material3)
                implementation(libs.designsystem.breeze)
                implementation(libs.designsystem.cupertino)
                implementation(libs.designsystem.deepin)
                implementation(libs.designsystem.fluent)
                implementation(libs.designsystem.gnome)
                implementation(libs.designsystem.liquid.glass)
            }
        }
        val macosArm64Main by getting {
            dependencies {
                // As in notebook-e2e: the icons were built against upstream Compose 1.7.3, whose ui
                // modules the fork's replace. Taken as the klib `renameIconsKlibDependencies` writes
                // (below), not as the published module.
                implementation(files(renameIconsKlibDependencies))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

pythonBindings {
    role.set("app")
    processor.set(projects.pythonMultiplatformKsp)

    artifactConfiguration.set("macosArm64CompileKlibraries")
    artifactSourceSet.set("macosArm64Main")
    // notebook-e2e's list (its build file says what each entry is for), with one difference: a klib has
    // no JVM file facade, so the walker matches a top-level function by its own dotted name
    // (KlibKnmScanner.matchesInclude). notebook-e2e's `SnapshotStateKt`, `TextFieldStateKt` and
    // `RememberSaveableKt` match nothing here; the functions each facade carries on the JVM (the
    // top-level functions of its source files, as notebook-e2e's generated table lists them) are named
    // instead. With the facade names `pythonx.compose.runtime` found no `mutableStateOf` and every
    // scenario failed (batch run 45027744672); the walker also had to learn to bind a non-composable
    // generic function and to walk the project klib `rememberSaveableWrapper` lives in (SPEC B-2).
    // Naming only `rememberTextFieldState` left out `setTextAndPlaceCursorAtEnd`, which the notebook's
    // TextField cell calls (NotebookTextFieldTest cell32to33, local run at 99af48ce).
    artifactIncludePackages.set(
        listOf(
            "androidx.compose.material3",
            "androidx.compose.foundation.layout",
            "androidx.compose.foundation.shape",
            "androidx.compose.ui.graphics",
            "androidx.compose.ui.res",
            "androidx.compose.ui",
            "androidx.compose.material.icons",
            "androidx.compose.runtime.collectAsState",
            "androidx.compose.runtime.derivedStateOf",
            "androidx.compose.runtime.getValue",
            "androidx.compose.runtime.mutableStateListOf",
            "androidx.compose.runtime.mutableStateMapOf",
            "androidx.compose.runtime.mutableStateOf",
            "androidx.compose.runtime.mutableStateSetOf",
            "androidx.compose.runtime.neverEqualPolicy",
            "androidx.compose.runtime.produceState",
            "androidx.compose.runtime.referentialEqualityPolicy",
            "androidx.compose.runtime.rememberUpdatedState",
            "androidx.compose.runtime.setValue",
            "androidx.compose.runtime.snapshotFlow",
            "androidx.compose.runtime.structuralEqualityPolicy",
            "androidx.compose.runtime.State",
            "androidx.compose.runtime.MutableState",
            "androidx.compose.foundation.text.input.TextFieldState",
            "androidx.compose.foundation.text.input.clearText",
            "androidx.compose.foundation.text.input.rememberTextFieldState",
            "androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd",
            "androidx.compose.foundation.text.input.setTextAndSelectAll",
            "androidx.compose.foundation.text.input.toTextFieldBuffer",
            "python.multiplatform.compose.rememberSaveableWrapper",
            "org.thisisthepy.compose.designsystem",
            "org.thisisthepy.compose.material3",
            "org.thisisthepy.compose.breeze",
            "org.thisisthepy.compose.cupertino",
            "org.thisisthepy.compose.deepin",
            "org.thisisthepy.compose.fluent",
            "org.thisisthepy.compose.gnome",
            "org.thisisthepy.compose.liquidglass",
        ),
    )
    compilerPlugin.set(project(":python-multiplatform-compiler-plugin"))
}

// ---- material-icons-core against the fork's klibs ------------------------------------------------
//
// The only material-icons-core klib is JetBrains' 1.7.3. Its manifest names the libraries it was built
// against by their unique names (`depends=... org.jetbrains.compose.ui\:ui ...`), and the fork's klibs
// that replace them are named `org.thisisthepy.compose.ui:ui` and so on. Compiling reads the klibs
// given on the command line, so it works; linking resolves each library's `depends` by unique name,
// cannot place the icons library, and leaves every icon getter undefined (batch run 44989836640,
// `linkDebugTestMacosArm64`). The same klib links on iOS in `sample/`, which uses upstream Compose.
//
// `renameIconsKlibDependencies` rewrites only those names in the icons klib's manifest; its IR and
// metadata are left as they are, and the module takes that file instead of the published klib. A
// task and a file dependency rather than an artifact transform: the bindings plugin resolves
// `macosArm64CompileKlibraries` while configuring, and a transform keyed on every klib also received
// the project klibs, which do not exist yet on a clean checkout (batch run 45012671392). It goes away
// when the fork publishes material-icons-core itself.

// ---- The pythonx-compose wheel (notebook-e2e's rules) -------------------------------------------

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
    description = "Unpacks the pythonx-compose wheel (-PpythonxComposeWheel) where the K/N notebook tests put it on sys.path."
    val wheel = pythonxComposeWheel
    enabled = wheel != null
    if (wheel != null) from(zipTree(wheel))
    into(pythonxComposeSitePackages)
}

if (notebookSkipped) {
    logger.lifecycle("notebook-native: the test task is DISABLED by -PnotebookE2e.skip=true -- the UI.ipynb scenarios did not run.")
}

// The application module the notebook imports as `main`: notebook-e2e's, so the two hosts run one app.
val notebookAppDir = layout.projectDirectory.dir("../notebook-e2e/src/desktopTest/python")

tasks.withType<KotlinNativeHostTest>().configureEach {
    inputs.dir(notebookAppDir)
    environment("NOTEBOOK_E2E_APP_DIR", notebookAppDir.asFile.absolutePath)
    val wheel = pythonxComposeWheel
    if (wheel != null) {
        dependsOn(installPythonxComposeWheel)
        inputs.file(wheel)
        environment("NOTEBOOK_E2E_WHEEL", wheel.absolutePath)
        environment("NOTEBOOK_E2E_SITE_PACKAGES", pythonxComposeSitePackages.get().asFile.absolutePath)
    } else {
        environment("NOTEBOOK_E2E_WHEEL_ERROR", checkNotNull(pythonxComposeWheelError))
    }
    enabled = !notebookSkipped
}

// A failing scenario prints its whole exception, message and Python error included (Gradle's default
// short format prints only the exception class).
tasks.withType<AbstractTestTask>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
