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
                // As in notebook-e2e: the icons were built against upstream Compose 1.7.3, whose ui
                // modules the fork's replace.
                implementation(libs.compose.material.icons.core.get().toString()) {
                    exclude(group = "org.jetbrains.compose.runtime")
                    exclude(group = "org.jetbrains.compose.ui")
                }
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
    // notebook-e2e's list (its build file says what each entry is for).
    artifactIncludePackages.set(
        listOf(
            "androidx.compose.material3",
            "androidx.compose.foundation.layout",
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
// This transform rewrites only those names in the icons klib's manifest; its IR and metadata are left
// as they are. Every other klib passes through unchanged. It goes away when the fork publishes
// material-icons-core itself.

/** The klib's manifest names the fork's Compose libraries instead of JetBrains'. */
val forkUniqueNames: Attribute<Boolean> = Attribute.of("pmp.klib.forkUniqueNames", Boolean::class.javaObjectType)

abstract class RenameComposeDependencies : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    abstract val input: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val klib = input.get().asFile
        if (!klib.isFile || !klib.name.startsWith("material-icons-core")) {
            if (klib.isDirectory) outputs.dir(klib) else outputs.file(klib)
            return
        }
        val renamed = outputs.file(klib.name)
        ZipFile(klib).use { zip ->
            ZipOutputStream(renamed.outputStream().buffered()).use { out ->
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

dependencies {
    attributesSchema { attribute(forkUniqueNames) }
    artifactTypes.maybeCreate("klib").attributes.attribute(forkUniqueNames, false)
    registerTransform(RenameComposeDependencies::class) {
        from.attribute(forkUniqueNames, false)
        to.attribute(forkUniqueNames, true)
    }
}

configurations.matching { it.isCanBeResolved && it.name.startsWith("macosArm64") }.configureEach {
    attributes.attribute(forkUniqueNames, true)
}

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
