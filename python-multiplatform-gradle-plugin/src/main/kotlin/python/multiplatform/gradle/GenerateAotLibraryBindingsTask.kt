package python.multiplatform.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates the Kotlin wrappers of a TypedPython AOT library from its `<prefix>.json` description (SPEC N-17, #458).
 *
 * Provenance. That a build produces a C-ABI library that Kotlin calls without CPython is owner decision D5
 * (2026-11-16). Desktop JVM first, and generated Kotlin that is self-contained (it uses the Kotlin standard library
 * and `java.base` only, and does not depend on `python-multiplatform`), are leader decisions of 2026-11-16, not
 * owner-confirmed. This task, its inputs and what it generates are an agent proposal, not owner-confirmed.
 *
 * ### What it reads and writes
 *
 * Reads only the description file (`abi_version` must be 3). Writes, under [outputDirectory] and in the directory
 * of [packageName]: `AotFfm.kt` (the FFM helper and `TypedPythonAotException`), `<objectName>.kt` (the object with
 * `load(libraryPath)` and one function per export), `F64List.kt` and `I64List.kt`, `IntList.kt` when the description has an `int_list` entry, and one `Dict...kt` per entry of `dicts` (`DictI64I64.kt`, `DictI64F64.kt`, `DictI64Bool.kt`). See [renderAotLibraryBindings].
 * Give each library its own package: the helper and the list classes have fixed names.
 *
 * When [resourceOutputDirectory] is set, also writes `META-INF/native-image/<packageName>/<prefix>/reachability-metadata.json`
 * declaring every foreign downcall descriptor and the `java.lang.foreign` reflection surface of `AotFfmRuntime`, so a
 * consumer's GraalVM native image picks it up automatically from the classpath without running the tracing agent.
 *
 * ### Using it without the bindings plugin's CPython conventions
 *
 * The task has no dependency on [PythonBindingsPlugin]. A build script that wants only this generator declares the
 * plugin with `apply false` (which puts its classes on the script's classpath and applies nothing: no KSP, no
 * `PYTHONHOME` staging) and registers the task class itself:
 *
 * ```kotlin
 * plugins {
 *     id("org.thisisthepy.python.multiplatform.bindings") apply false
 * }
 * val generate = tasks.register<python.multiplatform.gradle.GenerateAotLibraryBindingsTask>("generateNbodyBindings") {
 *     descriptionFile.set(layout.buildDirectory.file("aot/describe/nbody.json"))
 *     packageName.set("my.app.nbody")
 *     objectName.set("Nbody")
 *     outputDirectory.set(layout.buildDirectory.dir("generated/aot/nbody"))
 *     resourceOutputDirectory.set(layout.buildDirectory.dir("generated/aot-resources/nbody"))
 * }
 * kotlin.sourceSets.named("main") {
 *     kotlin.srcDir(generate)
 *     resources.srcDir(generate.flatMap { it.resourceOutputDirectory })
 * }
 * ```
 */
@CacheableTask
abstract class GenerateAotLibraryBindingsTask : DefaultTask() {

    /** The `<prefix>.json` file `python -m typedpython aot-lib` writes beside the library. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val descriptionFile: RegularFileProperty

    /** The Kotlin package of every generated file (one package per library). */
    @get:Input
    abstract val packageName: Property<String>

    /** The name of the generated object that holds `load` and the functions. */
    @get:Input
    abstract val objectName: Property<String>

    /** Where the sources are written. Add this task to a Kotlin source set (`kotlin.srcDir(task)`). */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * Optional directory where `META-INF/native-image/<packageName>/<prefix>/reachability-metadata.json` is written.
     * Add it to a source set's resources (`resources.srcDir(task.flatMap { it.resourceOutputDirectory })`) for GraalVM
     * native-image builds without a tracing agent.
     */
    @get:Optional
    @get:OutputDirectory
    abstract val resourceOutputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val text = descriptionFile.get().asFile.readText(Charsets.UTF_8)
        val description = parseAotLibraryDescription(text)
        val files = renderAotLibraryBindings(description, packageName.get(), objectName.get())
        val root = outputDirectory.get().asFile
        // The directory belongs to this task; a file of an earlier object name must not stay and clash.
        root.deleteRecursively()
        val packageDir = packageName.get().split('.').fold(root) { dir, segment -> dir.resolve(segment) }
        packageDir.mkdirs()
        for ((name, content) in files) packageDir.resolve(name).writeText(content, Charsets.UTF_8)
        if (resourceOutputDirectory.isPresent) {
            val resourceRoot = resourceOutputDirectory.get().asFile
            resourceRoot.deleteRecursively()
            val metadataFile = resourceRoot.resolve(aotReachabilityMetadataPath(packageName.get(), description.prefix))
            metadataFile.parentFile.mkdirs()
            metadataFile.writeText(renderAotReachabilityMetadata(description), Charsets.UTF_8)
        }
        logger.info("Generated ${files.size} files into $packageDir")
    }
}
