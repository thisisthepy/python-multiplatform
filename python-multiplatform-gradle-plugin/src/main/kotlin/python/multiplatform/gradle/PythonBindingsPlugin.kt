package python.multiplatform.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import python.multiplatform.gradle.artifact.PythonArtifactBindingsTask
import python.multiplatform.gradle.stubs.PythonStubsTask

/**
 * KSP option names. Copied rather than imported: these are the processor's
 * `python.multiplatform.ksp.Constants`, and this plugin ships as a separate build so that a
 * consumer resolves it from the plugin portal without also resolving the processor's compile
 * classpath. Changing one without the other shows up in the `fixtures/` modules as an unwired module.
 */
private const val OPTION_ROLE = "python.multiplatform.role"
private const val OPTION_MODULE_NAME = "python.multiplatform.moduleName"
private const val OPTION_EXCLUDE_PACKAGES = "python.multiplatform.excludePackages"

private const val ROLE_LIBRARY = "library"
private const val ROLE_APP = "app"

/** Created by toolchain's plugin (toolchain#23); referenced by name only. */
private const val TYPEDPYTHON_STUBS_CONFIGURATION = "typedpythonStubs"

/**
 * Which KSP configurations the processor belongs on.
 *
 * KSP creates one configuration per *compilation*, plus several of its own internal ones. The
 * processor belongs on each target's main compilation and nowhere else:
 *
 * - a `...Test` compilation would scan the test sources and emit a *second* `Fragment_<module>`
 *   under the same name, in the same package, in the same module;
 * - `kspCommonMainMetadata` would do the same for `commonMain`, which every platform target
 *   already compiles;
 * - `kspPluginClasspath*` and `...ProcessorClasspath` are KSP's own plumbing;
 * - the bare `ksp` configuration is the single-platform one. Adding to it from a multiplatform
 *   project fails outright -- "The 'ksp' configuration is deprecated in Kotlin Multiplatform
 *   projects. Please use target-specific configurations like 'kspJvm' instead" -- so
 *   [isMultiplatform] decides whether it counts.
 *
 * **A test compilation is not always a name that ends in `Test`.** On a module carrying an Android
 * plugin, KSP names its configurations after AGP's source sets, and AGP puts the build type last:
 *
 *     main          kspAndroid              kspAndroidDebug              kspAndroidRelease
 *     unit test     kspAndroidTest          kspAndroidTestDebug          kspAndroidTestRelease
 *     instrumented  kspAndroidAndroidTest   kspAndroidAndroidTestDebug   kspAndroidAndroidTestRelease
 *     testFixtures  kspAndroidTestFixtures  kspAndroidTestFixturesDebug  kspAndroidTestFixturesRelease
 *
 * so `endsWith("Test")` caught only the variant-less three of those nine. The processor landed on
 * `kspAndroidTestDebug`, scanned the *test* sources, and emitted a second `Fragment_<module>` --
 * and a second `FunctionTable` -- into the test compilation, where they shadowed the real ones
 * from `main`, because a compilation's own generated sources win over its classpath. The symptom
 * is a table holding the test classes and nothing the module actually exposes.
 * `python-multiplatform-ksp/fixtures/android` is what surfaced this; no module without an Android plugin can.
 *
 * `Test` is therefore matched as a camel-case *word* rather than as a suffix: it must start a word
 * and end one. A target or product flavour genuinely named `testing` is not a test compilation and
 * must keep the processor.
 *
 * Matching on names rather than walking the Kotlin extension's targets keeps this plugin free of
 * a Kotlin Gradle plugin dependency, and so free of its version. The observed name set is pinned
 * in `WiringTest`.
 */
private val TEST_WORD = Regex("(?:^|[a-z0-9])Test(?:[A-Z]|$)")

internal fun isBindingKspConfiguration(name: String, isMultiplatform: Boolean): Boolean {
    if (!name.startsWith("ksp")) return false
    if (name == "ksp") return !isMultiplatform
    if (name == "kspCommonMainMetadata") return false
    if (name.startsWith("kspPluginClasspath")) return false
    if (name.endsWith("ProcessorClasspath")) return false
    if (TEST_WORD.containsMatchIn(name)) return false
    return true
}

/**
 * The module name the generated fragment object is named after.
 *
 * Every fragment in every artifact lands in one package (`docs/design/upcall.md` §2.1), so
 * the name has to be unique across artifacts, not just within a build -- two independent
 * libraries both called `:core` would otherwise emit the same object into the same package. The
 * Maven group is what already carries that uniqueness, so it goes in front.
 */
internal fun deriveModuleName(group: String, path: String, name: String): String {
    val projectPart = path.removePrefix(":").ifBlank { name }
    val raw = if (group.isBlank()) projectPart else "$group.$projectPart"
    return raw.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}

/**
 * `app` for the module that produces the final binary, `library` for everything else.
 *
 * Only one module in a build may aggregate: the aggregator emits `FunctionTable` into a fixed
 * package, so two of them collide. Gradle has no general notion of "the module that ships", and
 * a Kotlin Multiplatform module that builds a framework or a native executable carries no plugin
 * that distinguishes it -- hence the explicit `role` override on the extension, which is what
 * `python-multiplatform-ksp/fixtures/app` uses.
 */
internal fun inferRole(hasApplicationPlugin: Boolean, hasAndroidApplicationPlugin: Boolean): String =
    if (hasApplicationPlugin || hasAndroidApplicationPlugin) ROLE_APP else ROLE_LIBRARY

/** Everything a consumer may want to override; every one of them has a working default. */
interface PythonBindingsExtension {
    /** `"library"` or `"app"`. Defaults to [inferRole]. */
    val role: Property<String>

    /** Defaults to [deriveModuleName] over the project's group and path. */
    val moduleName: Property<String>

    /** Package prefixes to keep out of the table, on top of the library's own. */
    val excludePackages: ListProperty<String>

    /**
     * The processor artifact. Defaults to this plugin's own group/version coordinates; an
     * in-repo consumer sets it to the project instead:
     * `pythonBindings { processor.set(projects.pythonMultiplatformKsp) }`.
     */
    val processor: Property<Any>

    /**
     * Whether to stage a CPython prefix for this machine and point `PYTHONHOME` at it on every
     * `JavaExec` and `Test` task. Defaults to true, see [PythonHomeStaging] for why the library
     * cannot supply one at runtime, and ROADMAP §15e item 4 for the gap it closes.
     *
     * It is already a no-op whenever `PYTHONHOME` is set in the environment, so setting this to
     * false is for the case where a build must not reach the network at all: the first build on a
     * machine downloads ~25 MB from python-build-standalone. Every later build, and every other
     * project on the same machine, reuses it.
     */
    val stagePythonHome: Property<Boolean>

    /**
     * Whether a packaged desktop application carries the staged CPython prefix -- SPEC L-9, issue
     * #60. Defaults to true.
     *
     * When on, every Compose Desktop `prepare*AppResources` task (what `createDistributable` and
     * the `package*` tasks take their resources from) depends on `stagePythonHome` and copies the
     * prefix's stdlib and shared library into `python-multiplatform-home/`; the library finds it
     * there at run time through Compose's `compose.application.resources.dir`. Independent of
     * [stagePythonHome]: an app must ship the prefix this library was built against even on a
     * machine whose developer runs with their own `PYTHONHOME`. Turn it off for an app that
     * provides a prefix some other way (and names it with `-Dpython.multiplatform.home=...`).
     */
    val packagePythonHome: Property<Boolean>

    /** CPython version to stage. Defaults to the one this library was built against. */
    val pythonVersion: Property<String>

    /** python-build-standalone release tag to stage from. Defaults to the one this library used. */
    val pythonBuildStandaloneRelease: Property<String>

    /** Whether to stage a free-threaded build. Defaults to what this library was built against. */
    val pythonFreeThreaded: Property<Boolean>

    /**
     * Whether to unpack `python-multiplatform`'s published wasm browser runtime for any `wasmJs*`
     * task this consumer's build declares. Defaults to true, and is a no-op unless a `wasmJs()`
     * target actually exists -- see [StageWasmBrowserRuntimeTask]'s kdoc for why registering it is
     * always safe. See ROADMAP §10.
     */
    val stageWasmBrowserRuntime: Property<Boolean>

    /** Maven coordinates of the runtime zip. Defaults to the one this library published. */
    val wasmRuntimeCoordinates: Property<String>

    /**
     * Package or class names to bind out of the artefacts this build **resolves**, rather than out
     * of the source it compiles -- `docs/design/ecosystem.md` §5b's second producer.
     *
     * KSP only ever sees declarations being compiled, so a third-party binary can never carry a
     * fragment. Setting this (together with [artifactConfiguration] and [artifactSourceSet])
     * registers `generatePythonArtifactBindings`, which walks the resolved jars with ASM and emits
     * `python.multiplatform.generated.artifacts.ArtifactTable` beside KSP's `FunctionTable`.
     *
     * Each name is matched as a *namespace*: `junit.runner` covers `junit.runner.Version`, and
     * `junit.run` covers nothing. Empty (the default) leaves the walker unregistered entirely --
     * binding a whole compile classpath by accident is not a useful default.
     */
    val artifactIncludePackages: ListProperty<String>

    /**
     * The resolvable configuration to walk, e.g. `desktopCompileClasspath`.
     *
     * Named explicitly rather than derived, because this plugin carries no Kotlin Gradle Plugin
     * types (see [TEST_WORD]) and so cannot ask a target what its compile classpath is called. One
     * configuration and one source set is also the honest scope of what has been verified: see
     * `python-multiplatform-gradle-plugin/fixtures/artifact`, and ROADMAP §16 for the generalisation.
     */
    val artifactConfiguration: Property<String>

    /** The Kotlin source set the generated fragments are compiled into, e.g. `desktopMain`. */
    val artifactSourceSet: Property<String>

    /**
     * More than one target's worth of the same thing: resolvable configuration name -> Kotlin
     * source set name, e.g. `"desktopCompileClasspath" to "desktopMain"`.
     *
     * [artifactConfiguration]/[artifactSourceSet] name exactly one pair and stay supported -- a
     * build that sets them keeps its task named `generatePythonArtifactBindings`, unsuffixed, so
     * nothing that already invokes that task by name breaks. Every *additional* pair gets a task
     * named after its source set, because two tasks cannot share a name and the source set is what
     * distinguishes their output directories already.
     *
     * Still named by hand rather than derived, for the reason [artifactConfiguration] gives: this
     * plugin carries no Kotlin Gradle Plugin types, so it cannot ask a target what its compile
     * classpath is called. What changes here is only that the answer no longer has to be singular.
     * The naming convention a caller can rely on is `<target>CompileClasspath` -> `<target>Main`
     * for a plain Kotlin target; Android's variant-aware configurations do not follow it, which is
     * exactly why this stays a map the caller writes rather than something derived.
     */
    val artifactTargets: MapProperty<String, String>

    /**
     * Whether to emit `.pyi` stubs for the walked artefacts -- `docs/design/pyi-generation-design.md`.
     *
     * Defaults to true wherever the walker itself is registered, and is a no-op everywhere else:
     * nothing is enumerable to stub until there is something to stub *from*. The output is
     * `build/generated/pythonStubs/<sourceSet>/` and **nothing compiles it** -- see
     * [python.multiplatform.gradle.stubs.PythonStubsTask] for why it is not written into the source
     * tree the way PyREPL's generator was, and for what registering it with an interpreter still
     * needs per environment.
     */
    val generateStubs: Property<Boolean>
}

/**
 * ROADMAP §7's last open wiring item: what used to be `add("kspDesktop", ...)`,
 * `add("kspAndroidNativeArm64", ...)` and two `ksp { arg(...) }` lines per module, once per
 * target, is now one plugin.
 *
 *     plugins {
 *         id("io.github.thisisthepy.python.multiplatform.bindings")
 *     }
 *
 * A module that produces the final binary adds `pythonBindings { role.set("app") }` unless it
 * applies `application` or `com.android.application`, which are inferred.
 *
 * The consumer's own `org.jetbrains.kotlin.multiplatform` (or `.jvm`/`.android`) plugin version
 * has to satisfy two independent floors, verified against a real external consumer outside this
 * repository (ROADMAP §15f):
 *
 * - **API-compatible with the pinned KSP Gradle plugin** (`libs.versions.ksp` in this build --
 *   currently 2.3.11). Too old a Kotlin Gradle Plugin and `kspKotlin<Target>` fails at
 *   configuration time with a raw `NoSuchMethodError`-shaped message
 *   (`KotlinJvmCompilerOptions.getJvmDefault()` on Kotlin 2.1.0) -- KSP's plugin calls an API the
 *   older Kotlin Gradle Plugin does not have.
 * - **New enough to read the published library's metadata version.** `python-multiplatform` is
 *   built with this repo's own Kotlin version (currently 2.4.20-Beta2, `libs.versions.kotlin`).
 *   A consumer's Kotlin *compiler* one or more feature releases behind that fails
 *   `compileKotlin<Target>` with "was compiled with an incompatible version of Kotlin ... can
 *   read versions up to X.Y" once it reaches classes from the dependency -- this happens even
 *   when the KSP-vs-KGP floor above is already satisfied (observed with Kotlin 2.2.20).
 *
 * The version this repo itself uses is the only combination exercised; there is no published
 * compatibility matrix beyond it.
 */
class PythonBindingsPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("pythonBindings", PythonBindingsExtension::class.java)

        project.pluginManager.apply("com.google.devtools.ksp")

        // `matching` is live, so targets declared after this plugin is applied -- which is the
        // normal case, since `kotlin { ... }` comes after `plugins { ... }` -- are still covered.
        // The multiplatform check is inside the predicate, not hoisted: `matching` evaluates it
        // when a configuration is realised, so this holds even if the Kotlin plugin is applied
        // after this one.
        project.configurations.matching {
            isBindingKspConfiguration(it.name, project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform"))
        }.configureEach {
            dependencies.addLater(
                project.provider {
                    project.dependencies.create(extension.processor.getOrElse(DEFAULT_PROCESSOR_COORDINATES))
                },
            )
        }

        configurePythonHomeStaging(project, extension)
        configureWasmBrowserRuntimeStaging(project, extension)

        project.afterEvaluate {
            setKspArg(
                OPTION_ROLE,
                extension.role.getOrElse(
                    inferRole(
                        hasApplicationPlugin = plugins.hasPlugin("application"),
                        hasAndroidApplicationPlugin = plugins.hasPlugin("com.android.application"),
                    ),
                ),
            )
            setKspArg(
                OPTION_MODULE_NAME,
                extension.moduleName.getOrElse(deriveModuleName(group.toString(), path, name)),
            )
            val excluded = extension.excludePackages.getOrElse(emptyList())
            if (excluded.isNotEmpty()) setKspArg(OPTION_EXCLUDE_PACKAGES, excluded.joinToString(","))

            // After evaluation, because it names a configuration and a Kotlin source set that the
            // consumer's own `kotlin { ... }` block creates -- both of which are declared after
            // `plugins { ... }` and so do not exist while this plugin is being applied.
            configureArtifactBindings(this, extension)
            configureWasmProxyExports(this)
        }
    }

    /**
     * Registers the artefact walker -- `docs/design/ecosystem.md` §5b's second producer -- when a consumer
     * has asked for one.
     *
     * A no-op unless all three of [PythonBindingsExtension.artifactIncludePackages],
     * [PythonBindingsExtension.artifactConfiguration] and [PythonBindingsExtension.artifactSourceSet]
     * are set, so a build that only wants KSP pays nothing: no task, no configuration resolution and
     * no generated source directory.
     */
    internal fun configureArtifactBindings(project: Project, extension: PythonBindingsExtension) {
        val includes = extension.artifactIncludePackages.getOrElse(emptyList())
        if (includes.isEmpty()) return
        
        val singleConfig = extension.artifactConfiguration.orNull
        val singleSourceSet = extension.artifactSourceSet.orNull
        val targets = extension.artifactTargets.getOrElse(emptyMap()).toMutableMap()
        if (singleConfig != null && singleSourceSet != null) {
            targets[singleConfig] = singleSourceSet
        }

        if (targets.isEmpty()) {
            error(
                "pythonBindings.artifactIncludePackages is set but no targets are configured: " +
                    "set artifactConfiguration/artifactSourceSet or artifactTargets."
            )
        }

        val klibReader = klibReaderClasspath(project)

        for ((configurationName, sourceSetName) in targets) {
            val configuration = project.configurations.findByName(configurationName)
                ?: error("no configuration named '$configurationName' in ${project.path}")
            
            // `incoming.artifacts` rather than the configuration's own file collection: it is the only
            // route that carries each file's *coordinates* alongside it, and a fragment has to be named
            // after the artefact rather than after whatever the cache called the file.
            val resolved = configuration.incoming.artifacts

            val taskSuffix = sourceSetName.replaceFirstChar { it.uppercaseChar() }
            val isLegacyName = targets.size == 1 && configurationName == singleConfig

            val bindingsTaskName = if (isLegacyName) "generatePythonArtifactBindings" else "generatePythonArtifactBindings$taskSuffix"

            val task = project.tasks.register(
                bindingsTaskName,
                PythonArtifactBindingsTask::class.java,
            ) {
                group = "python"
                description = "Walks resolved artefacts and emits Python bindings for their declarations."
                artifacts.from(resolved.artifactFiles)
                coordinatesByFileName.set(
                    resolved.resolvedArtifacts.map { set ->
                        set.associate { it.file.name to it.id.componentIdentifier.displayName }
                    },
                )
                includePrefixes.set(includes)
                this.klibReaderClasspath.from(klibReader)
                outputDirectory.set(
                    project.layout.buildDirectory.dir("generated/pythonArtifactBindings/$sourceSetName"),
                )
                thunkDirectory.set(
                    project.layout.buildDirectory.dir("generated/pythonComposableThunks/$sourceSetName"),
                )
            }

            addKotlinSourceDirectory(project, sourceSetName, task)
            addThunkClasspath(project, sourceSetName, task)
            configureStubGeneration(project, extension, resolved, includes, sourceSetName, klibReader, isLegacyName, taskSuffix)
        }
    }

    /**
     * Everything `KlibScanWorkAction`'s isolated worker classloader needs beside this plugin's own
     * jar, resolved **in the consumer's project** rather than taken from this plugin's classpath.
     *
     * ### Why it cannot be a plugin dependency
     *
     * `kotlin-compiler-embeddable` on this plugin's runtime classpath becomes `runtime` scope in its
     * published POM, which puts it on the plugin classloader of every build that applies the plugin id
     * -- beside the Kotlin Gradle Plugin's own copy of the same classes. KGP then fails to create its
     * own tasks:
     *
     *     Could not create task of type 'KotlinCompile'.
     *       > Could not generate a decorated class for type KotlinCompile.
     *         > class org.jetbrains.kotlin.build.report.metrics.BuildTimeMetric has interface
     *           org.jetbrains.kotlin.build.report.metrics.BuildPerformanceMetric as super class
     *
     * `./gradlew tasks` failed on that in all three external consumer projects, whether or not they
     * asked for any artefact walking at all. Gradle warns about this jar being on a build classpath
     * ("unpredictable and inconsistent behavior"); in this combination it is not unpredictable, it is a
     * hard failure every time. The worker classloader isolation `KlibScanWorkAction` already has does
     * not help -- that isolates *execution*, and the breakage is the jar merely being *present*.
     *
     * ### Why the whole worker classpath, not only the reader
     *
     * The worker classloader cannot borrow anything from the plugin's own: in an external consumer,
     * every plugin in the same `plugins { }` block shares one classloader, so this plugin's `.urLs`
     * out there include the Kotlin Gradle Plugin's `kotlin-util-klib` -- the exact jar the isolation
     * exists to escape. `KlibScanWorkAction.pluginCodeSource`'s KDoc has the printed list. So the
     * worker's classpath is this configuration plus this plugin's own jar, and nothing else, which is
     * why `kotlin-metadata-jvm` and ASM are named here too even though the plugin already depends on
     * them for the *jar* walker.
     *
     * ### Why detached, and why it costs a consumer nothing
     *
     * `detachedConfiguration` keeps it out of the consumer's own configuration container, so no
     * `configurations.all { }` rule, dependency substitution or platform constraint of theirs can move
     * the versions away from the ones `KlibScanner` was compiled against -- the same reason
     * `configureWasmBrowserRuntimeStaging` uses one. It is only ever resolved from
     * `PythonArtifactBindingsTask`/`PythonStubsTask`'s task action, and only in the klib branch: a
     * consumer walking jars only never downloads it, and a consumer who registers no walker never
     * creates the tasks at all.
     *
     * `Usage=java-runtime` because a bare detached configuration carries no attributes, and these
     * modules publish Gradle module metadata with both an api and a runtime variant to choose between.
     */
    private fun klibReaderClasspath(project: Project) =
        project.configurations.detachedConfiguration(
            *DEFAULT_KLIB_WORKER_COORDINATES.map { project.dependencies.create(it) }.toTypedArray(),
        ).apply {
            attributes.attribute(
                org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
                project.objects.named(org.gradle.api.attributes.Usage::class.java, org.gradle.api.attributes.Usage.JAVA_RUNTIME),
            )
        }

    /**
     * Puts the generated composable thunks on the compilation's classpath.
     *
     * ### Why not a dependency
     *
     * The obvious spelling -- `dependencies { desktopCompileOnly(files(thunkDir)) }` -- is a
     * **circular task dependency**, and not a subtle one: the walker's own input is
     * `desktopCompileClasspath` (whatever [PythonBindingsExtension.artifactConfiguration] names),
     * both `compileOnly` and `implementation` feed that configuration, so the task would be waiting
     * for its own output. A compile task's `libraries` is the one place a classpath entry can be
     * added *past* the configuration the walker reads.
     *
     * The runtime half has no such problem -- a runtime classpath is not a compile classpath -- so it
     * goes on `<target>RuntimeOnly` the ordinary way, and on the test one beside it, since a `Test`
     * task resolves a classpath of its own.
     *
     * ### Reflection, for the same reason [addKotlinSourceDirectory] uses it
     *
     * This plugin never names a Kotlin Gradle Plugin type at compile time. `getLibraries()` on a
     * Kotlin JVM compile task returns Gradle's own [org.gradle.api.file.ConfigurableFileCollection],
     * so only the lookup is reflective and nothing about the value is.
     *
     * A source set called `<target>Main` compiles in `compileKotlin<Target>`, which is KGP's own
     * convention for a target's main compilation. A name this cannot map is not an error: a build
     * that binds no composable emits no thunk and never reads this classpath entry.
     */
    private fun addThunkClasspath(
        project: Project,
        sourceSetName: String,
        task: org.gradle.api.tasks.TaskProvider<PythonArtifactBindingsTask>,
    ) {
        val thunks = project.files(task.map { it.thunkDirectory })
        val targetName = sourceSetName.removeSuffix("Main")
        val compileTaskName = "compileKotlin" + targetName.replaceFirstChar { it.uppercaseChar() }
        project.tasks.matching { it.name == compileTaskName }.configureEach {
            val libraries = javaClass.methods
                .firstOrNull { it.name == "getLibraries" && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
                ?.invoke(this) as? org.gradle.api.file.ConfigurableFileCollection
                ?: return@configureEach
            libraries.from(thunks)
        }
        listOf("${targetName}RuntimeOnly", "${targetName}TestRuntimeOnly").forEach { name ->
            if (project.configurations.findByName(name) != null) project.dependencies.add(name, thunks)
        }
    }

    /**
     * Registers `generatePythonStubs` -- `docs/design/pyi-generation-design.md`'s generator.
     *
     * Hooked onto `prepareKotlinIdeaImport` the way PyREPL hooked its own generator (§1.4 keeps that
     * row): it is the one step that makes the stubs exist before the IDE indexes, and it costs one
     * line. `tasks.matching` rather than `getByName`, because the task exists only when the Kotlin
     * Gradle Plugin creates it -- a plain `./gradlew build` has no IDE import step.
     *
     * Nothing else depends on this task. **Nothing compiles a `.pyi`** (§6.2), so unlike the binding
     * fragments there is no `srcDir(task)` that would pull it into a build; a consumer runs it, or
     * their IDE import does.
     */
    private fun configureStubGeneration(
        project: Project,
        extension: PythonBindingsExtension,
        resolved: org.gradle.api.artifacts.ArtifactCollection,
        includes: List<String>,
        sourceSetName: String,
        klibReader: org.gradle.api.artifacts.Configuration,
        isLegacyName: Boolean,
        taskSuffix: String,
    ) {
        if (!extension.generateStubs.getOrElse(true)) return
        val stubTaskName = if (isLegacyName) "generatePythonStubs" else "generatePythonStubs$taskSuffix"
        val task = project.tasks.register(stubTaskName, PythonStubsTask::class.java) {
            group = "python"
            description = "Emits .pyi stubs for the declarations the artefact walker bound."
            artifacts.from(resolved.artifactFiles)
            includePrefixes.set(includes)
            klibReaderClasspath.from(klibReader)
            outputDirectory.set(project.layout.buildDirectory.dir("generated/pythonStubs/$sourceSetName"))
        }
        project.tasks.matching { it.name == "prepareKotlinIdeaImport" }.configureEach { dependsOn(task) }
        feedTypedPythonStubs(project, task)
    }

    /**
     * Adds [task]'s output to toolchain's `typedpythonStubs` configuration, by name only -- there is
     * no compile-time dependency on toolchain. `matching` + `configureEach` covers a configuration
     * that already exists and one created later, so plugin application order does not matter; if it
     * never appears, nothing happens. The provider is `flatMap`ped from the task, so resolving the
     * configuration runs the task.
     */
    private fun feedTypedPythonStubs(project: Project, task: org.gradle.api.tasks.TaskProvider<PythonStubsTask>) {
        project.configurations.matching { it.name == TYPEDPYTHON_STUBS_CONFIGURATION }.configureEach {
            project.dependencies.add(name, project.files(task.flatMap { it.outputDirectory }))
        }
    }

    /**
     * `kotlin { sourceSets.getByName(name).kotlin.srcDir(task) }` without a compile-time reference
     * to any Kotlin Gradle Plugin type -- the same constraint, and the same reflective answer, as
     * [setKspArg].
     *
     * Only the last hop needs reflection to *read*: `KotlinSourceSet.getKotlin()` returns Gradle's
     * own [org.gradle.api.file.SourceDirectorySet], and `sourceSets` is a Gradle
     * [org.gradle.api.NamedDomainObjectContainer], so both ends of the chain are types this plugin
     * can name. Passing the task provider rather than a directory is what makes the compilation --
     * and KSP's own scan of the same source set -- depend on the walker having run.
     */
    internal fun addKotlinSourceDirectory(
        project: Project,
        sourceSetName: String,
        task: org.gradle.api.tasks.TaskProvider<*>,
    ) {
        val kotlin = project.extensions.findByName("kotlin")
            ?: error("the kotlin extension is missing: pythonBindings.artifactIncludePackages needs a Kotlin project")
        val sourceSets = kotlin.javaClass.methods
            .firstOrNull { it.name == "getSourceSets" && it.parameterCount == 0 }
            ?.also { it.isAccessible = true }
            ?.invoke(kotlin) as? org.gradle.api.NamedDomainObjectContainer<*>
            ?: error("the kotlin extension has no sourceSets container; the Kotlin Gradle Plugin API changed")
        val sourceSet = sourceSets.findByName(sourceSetName)
            ?: error(
                "no Kotlin source set named '$sourceSetName' in ${project.path}; " +
                    "pythonBindings.artifactSourceSet must name one that exists",
            )
        val directories = sourceSet.javaClass.methods
            .firstOrNull { it.name == "getKotlin" && it.parameterCount == 0 }
            ?.also { it.isAccessible = true }
            ?.invoke(sourceSet) as? org.gradle.api.file.SourceDirectorySet
            ?: error("Kotlin source set '$sourceSetName' has no kotlin SourceDirectorySet; the API changed")
        directories.srcDir(task)
    }

    /**
     * Registers `stagePythonHome`, hands its result to every task that launches a JVM, and puts it
     * into a packaged desktop application.
     *
     * The prefix path is computed here rather than carried as a `Provider`, and it can be: it is a
     * pure function of the version, the release, the host and the Gradle user home, none of which
     * need the task to have run. That keeps `environment(...)` a plain string set at configuration
     * time, the value is in the child process's environment before its JVM starts, which is the
     * only way CPython's `getenv(3)` and [python.multiplatform.env.PythonHomeCheck]'s
     * `System.getenv` can be guaranteed to read the same thing.
     *
     * The `environment(...)` wiring is skipped when `PYTHONHOME` is already set, so a consumer with
     * their own prefix keeps it. The task itself is registered regardless -- registration is lazy,
     * and it runs only when something depends on it -- because packaging (SPEC L-9) must ship the
     * prefix this library was built against whatever the developer's own shell points at. Nothing
     * at all happens on a host that is not a platform this library ships for; that warns rather
     * than fails, because a project may well be building only its Android or iOS targets there.
     */
    internal fun configurePythonHomeStaging(project: Project, extension: PythonBindingsExtension) {
        val enabled = extension.stagePythonHome.getOrElse(true)

        val platform = hostDesktopPlatform(
            System.getProperty("os.name").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
        )
        if (platform == null) {
            project.logger.info(
                "python-multiplatform: not staging a CPython prefix -- no python-build-standalone " +
                    "distribution for ${System.getProperty("os.name")}/${System.getProperty("os.arch")}.",
            )
            return
        }

        val version = extension.pythonVersion.getOrElse(DEFAULT_PYTHON_VERSION)
        val release = extension.pythonBuildStandaloneRelease.getOrElse(DEFAULT_PBS_RELEASE)
        val freeThreaded = extension.pythonFreeThreaded.getOrElse(DEFAULT_PYTHON_FREE_THREADED)

        // Shared across every project on the machine: the prefix is 24 MB of files that are
        // identical for every consumer of a given release, and a per-project copy would pay for it
        // once per checkout.
        val cacheRoot = java.io.File(project.gradle.gradleUserHomeDir, PythonHomeStaging.CACHE_DIRECTORY)
        val flavour = if (freeThreaded) "-freethreaded" else ""
        val destination = java.io.File(cacheRoot, "$version+$release$flavour/$platform")
        val prefix = java.io.File(destination, "python")

        val stage = project.tasks.register("stagePythonHome", StagePythonHomeTask::class.java) {
            group = "python"
            description = "Downloads and unpacks a CPython prefix for PYTHONHOME to point at."
            pythonVersion.set(version)
            pbsRelease.set(release)
            this.platform.set(platform)
            this.freeThreaded.set(freeThreaded)
            destinationDir.set(destination)
            downloadDir.set(java.io.File(cacheRoot, "archives"))
        }

        // `providers.environmentVariable`, not `System.getenv`: the latter reads the *daemon's*
        // environment, and a daemon is reused across invocations that do not share one. A user
        // running `PYTHONHOME=/their/prefix ./gradlew run` against a daemon that was started
        // without it would otherwise be told, silently, that they had set nothing -- and have
        // their prefix replaced by a staged one on the very task they were configuring.
        if (shouldStagePythonHome(project.providers.environmentVariable("PYTHONHOME").orNull, enabled)) {
            val home = prefix.absolutePath
            project.tasks.withType(org.gradle.api.tasks.JavaExec::class.java).configureEach {
                dependsOn(stage)
                environment("PYTHONHOME", home)
            }
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach {
                dependsOn(stage)
                environment("PYTHONHOME", home)
            }
        }

        configurePackagedPythonHome(
            project = project,
            extension = extension,
            stage = stage,
            includes = packagedPrefixIncludes(version, freeThreaded, windows = platform.startsWith("windows")),
        )
    }

    /**
     * SPEC L-9: copies the staged prefix's stdlib and shared library into every Compose Desktop
     * `prepare*AppResources` task under [PACKAGED_HOME_DIRECTORY], so that `createDistributable`
     * puts it at `$APPDIR/resources/python-multiplatform-home` -- where the library looks, through
     * the `compose.application.resources.dir` property Compose's launcher always sets.
     *
     * Into Compose's own resources task rather than through `nativeDistributions.appResourcesRootDir`:
     * that is a single directory property the consumer may already be using for their own files,
     * and setting it here would replace theirs. Adding a `from` to the task that assembles it
     * composes with whatever the consumer configured. One copy, too: the prefix goes straight from
     * the Gradle-user-home cache into Compose's staging directory, and `Sync` is incremental.
     *
     * [PythonBindingsExtension.packagePythonHome] is read when the task is *executed* and its
     * dependencies computed, not now -- this runs while the plugin is being applied, before the
     * consumer's `pythonBindings { }` block has.
     */
    private fun configurePackagedPythonHome(
        project: Project,
        extension: PythonBindingsExtension,
        stage: org.gradle.api.tasks.TaskProvider<StagePythonHomeTask>,
        includes: List<String>,
    ) {
        val packaging = project.provider { extension.packagePythonHome.getOrElse(true) }
        val stagedPrefix = stage.flatMap { it.destinationDir.dir("python") }
        project.tasks.withType(org.gradle.api.tasks.Sync::class.java)
            .matching { isComposeAppResourcesTask(it.name) }
            .configureEach {
                dependsOn(java.util.concurrent.Callable { if (packaging.get()) listOf(stage) else emptyList() })
                from(java.util.concurrent.Callable { if (packaging.get()) stagedPrefix else emptyList<Any>() }) {
                    include(includes)
                    exclude(PACKAGED_PREFIX_EXCLUDES)
                    into(PACKAGED_HOME_DIRECTORY)
                }
                doLast {
                    requireNoEmptyLibraries(destinationDir.resolve(PACKAGED_HOME_DIRECTORY))
                }
            }
    }

    /**
     * Registers unpacking of the wasm browser runtime, and wires it into whichever `wasmJs*` tasks
     * this consumer's build happens to declare -- ROADMAP §10.
     *
     * `stage` is always registered; [StageWasmBrowserRuntimeTask]'s detached configuration is not
     * resolved until the task executes, and nothing makes it execute unless a `wasmJsProcessResources`
     * or `wasmJs*Webpack` task exists to depend on it. So a consumer with no `wasmJs()` target pays
     * nothing -- no network access, no `mavenLocal`/repository lookup -- for this being registered.
     *
     * `tasks.matching` is a live view: it fires for tasks declared *after* this runs too, which is
     * the normal case since `kotlin { wasmJs() }` is declared after `plugins { ... }`. Matched by
     * name rather than by Kotlin Gradle Plugin task type for the same reason `TEST_WORD` is -- this
     * plugin's `kotlin-dsl` classpath does not carry the Kotlin Multiplatform Gradle plugin, so it
     * has no compile-time reference to `KotlinWebpack` or `ProcessResources`'s Kotlin/JS subclass to
     * match against. `org.gradle.language.jvm.tasks.ProcessResources` is Gradle's own class, not the
     * Kotlin Gradle Plugin's, so casting `wasmJsProcessResources` to it does not add that dependency.
     */
    private fun configureWasmBrowserRuntimeStaging(project: Project, extension: PythonBindingsExtension) {
        if (!extension.stageWasmBrowserRuntime.getOrElse(true)) return

        val coordinates = extension.wasmRuntimeCoordinates.getOrElse(DEFAULT_WASM_RUNTIME_COORDINATES)
        val configuration = project.configurations.detachedConfiguration(
            project.dependencies.create(coordinates),
        ).apply { isTransitive = false }

        val destination = project.layout.buildDirectory.dir("wasm-browser-runtime")
        val stage = project.tasks.register("stageWasmBrowserRuntime", StageWasmBrowserRuntimeTask::class.java) {
            group = "python"
            description = "Unpacks python-multiplatform's published CPython wasm browser runtime."
            runtimeArtifact.from(configuration)
            destinationDir.set(destination)
        }

        // The *main* resources task, not `wasmJsTestProcessResources` -- test runs go through the
        // node runner, which this plugin does not wire (ROADMAP §10 scopes this to browser bundles).
        project.tasks.matching { it.name == "wasmJsProcessResources" }.configureEach {
            dependsOn(stage)
            (this as org.gradle.language.jvm.tasks.ProcessResources).from(destination)
        }

        // Every webpack task reads the compile-sync output, so the patch has to land between that
        // sync and webpack's own run -- `doFirst` on the webpack task is the only point that is
        // after one and before the other, exactly as `:sample`'s own copy of this does.
        //
        // The generated module name is `"${rootProject.name}-${project.name}"` only for a
        // *subproject* -- `:sample` is one, which is why its own copy of this patch could use that
        // formula unconditionally. A single-module build (`consumer-plugin-android`, ROADMAP §10's
        // verification) has the `wasmJs()` target on the *root* project, and there Kotlin does not
        // double the name: the generated files are `consumer-plugin-android.*`, not
        // `consumer-plugin-android-consumer-plugin-android.*`. Checked against the actual compile
        // -sync output rather than assumed -- the first version of this patch named a directory
        // that never existed and failed with "No *.import-object.mjs in ...".
        val modulePrefix = if (project == project.rootProject) {
            project.name
        } else {
            "${project.rootProject.name}-${project.path.removePrefix(":").replace(":", "-")}"
        }
        project.tasks.matching { it.name.startsWith("wasmJs") && it.name.endsWith("Webpack") }.configureEach {
            dependsOn(stage)
            val syncedDir = project.rootProject.layout.buildDirectory.dir("wasm/packages/$modulePrefix/kotlin")
            val log = project.logger
            doFirst {
                patchWasmOutputForCPython(syncedDir.get().asFile, modulePrefix, log)
            }
        }
    }

    /**
     * `ksp { arg(key, value) }` without a compile-time reference to `KspExtension` -- see the
     * `runtimeOnly` comment in this build's `build.gradle.kts` for why there cannot be one.
     * A missing method here means KSP changed its extension API, which is worth a loud failure at
     * configuration time rather than a silently unconfigured processor.
     */
    private fun Project.setKspArg(key: String, value: String) {
        val ksp = extensions.findByName("ksp")
            ?: error("the KSP extension is missing: com.google.devtools.ksp was not applied")
        val arg = ksp.javaClass.methods.firstOrNull { method ->
            method.name == "arg" &&
                method.parameterCount == 2 &&
                method.parameterTypes.all { it == String::class.java }
        } ?: error("KspExtension has no arg(String, String); python-multiplatform-gradle-plugin needs updating")
        arg.invoke(ksp, key, value)
    }

    internal fun configureWasmProxyExports(project: Project) {
        val kotlin = project.extensions.findByName("kotlin") ?: return
        val sourceSets = kotlin.javaClass.methods
            .firstOrNull { it.name == "getSourceSets" && it.parameterCount == 0 }
            ?.also { it.isAccessible = true }
            ?.invoke(kotlin) as? org.gradle.api.NamedDomainObjectContainer<*>
            ?: return
        if (sourceSets.findByName("wasmJsMain") == null) return

        val task = project.tasks.register("generateWasmProxyExports", GenerateWasmProxyExportsTask::class.java) {
            group = "python"
            description = "Generates the @WasmExport trampoline functions for CPython proxy type slots."
            packageName.set("python.multiplatform.generated.wasm")
            outputDirectory.set(project.layout.buildDirectory.dir("generated/wasmProxyExports/main"))
        }
        addKotlinSourceDirectory(project, "wasmJsMain", task)
    }
}
