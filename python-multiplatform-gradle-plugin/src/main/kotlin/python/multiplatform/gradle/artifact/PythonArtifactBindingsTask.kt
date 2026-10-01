package python.multiplatform.gradle.artifact

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Walks the jars a configuration resolves and writes one `FunctionTableFragment` per artefact that
 * contributed something, plus the `ArtifactTable` that lists them.
 *
 * `docs/design/ecosystem.md` §5b: KSP reads the consumer's own source, this reads everything the build
 * resolves. The two emit the same shape into the same `UpcallTable`; see [renderArtifactTableSource]
 * for why they keep separate aggregators.
 *
 * ### Only jars and klibs
 *
 * A resolved artefact that is not a `.jar` or `.klib` -- an `aar`, a project's classes directory -- is
 * skipped silently.
 *
 * ### The klib branch runs behind a worker classloader boundary
 *
 * Calling [KlibScanner] directly, in this task's own classloader, throws `NoSuchMethodError` in a real
 * consumer build -- a classloader-scope collision between this plugin's `kotlin-compiler-embeddable`
 * and the Kotlin Gradle Plugin's `kotlin-util-klib` on the root buildscript scope, which is a *parent*
 * of this classloader. See [KlibScanWorkAction]'s KDoc for the measurement and why
 * [WorkerExecutor.scanKlibIsolated] fixes it: an isolated worker classloader's parent is Gradle's own
 * minimal worker infrastructure, not that root buildscript scope, so nothing this plugin didn't put on
 * [KlibScanWorkAction]'s explicit classpath is visible to answer for `org.jetbrains.kotlin.library.*`.
 */
abstract class PythonArtifactBindingsTask : DefaultTask() {

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    /** Every file the walked configuration resolved. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifacts: ConfigurableFileCollection

    /**
     * File name to the artefact's coordinates (`junit:junit:4.13.2`, or `project :lib`).
     *
     * Keyed by file name rather than by absolute path so that the task input does not carry a
     * Gradle cache location, which differs per machine and would defeat any build cache.
     */
    @get:Input
    abstract val coordinatesByFileName: MapProperty<String, String>

    /** Package or class names to bind; empty binds every class in every resolved jar. */
    @get:Input
    abstract val includePrefixes: ListProperty<String>

    /**
     * The whole classpath of the isolated worker that runs [KlibScanner], bar this plugin's own jar:
     * the klib reader plus what [ArtifactScanner] needs, resolved by the consumer's project. See
     * [scanKlibIsolated] and `KlibScanWorkAction.pluginCodeSource`.
     *
     * `@Internal` rather than `@Classpath` on purpose. It is a lazily-resolved configuration holding one
     * pinned coordinate, and marking it an input would make Gradle resolve it during this task's
     * up-to-date check -- a 55 MiB download for every consumer that walks *jars only* and never reaches
     * the klib branch. Nothing is lost by not tracking it: the coordinate is generated into this plugin
     * (`DEFAULT_KLIB_WORKER_COORDINATES`), so it cannot change without the plugin jar changing, and the
     * plugin jar is already part of the task implementation Gradle hashes.
     */
    @get:Internal
    abstract val klibReaderClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * Where the `.class` files go, as opposed to the `.kt` files [outputDirectory] gets.
     *
     * A separate output because it is a separate *kind* of thing: [outputDirectory] is registered as
     * a Kotlin source directory and compiled, this is put on the compilation's classpath as if it
     * were a library. Only `@Composable` bindings need one -- see `ComposableThunks.kt` for the two
     * measurements that show a composable's call site cannot be Kotlin source -- so a build that
     * binds none leaves this directory empty rather than absent, because a Gradle output directory
     * that does not exist is a task that never ran.
     */
    @get:OutputDirectory
    abstract val thunkDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.get().asFile.resolve(ARTIFACTS_PACKAGE.replace('.', '/'))
        // Rebuilt from scratch: a dependency that was removed, or an include prefix that stopped
        // matching, must take its fragment file with it or the next compilation still sees it.
        outputDirectory.get().asFile.deleteRecursively()
        destination.mkdirs()
        val thunkRoot = thunkDirectory.get().asFile
        thunkRoot.deleteRecursively()
        val thunkDestination = thunkRoot.resolve(THUNK_PACKAGE.replace('.', '/'))
        thunkDestination.mkdirs()

        val includes = includePrefixes.get()
        val coordinates = coordinatesByFileName.get()
        val objectNames = mutableListOf<String>()

        // Every resolved jar, not just the one being enumerated: a value-class parameter or an
        // extension receiver declared in one artefact routinely lives in another --
        // `androidx.compose.foundation.layout`'s `Modifier.padding(Dp)` needs `Dp`, which is
        // `androidx.compose.ui.unit`'s. See `ArtifactScanner.scanJar`'s `classpath` parameter.
        val resolvedJars = artifacts.files.filter { it.isFile && it.name.endsWith(".jar") }

        artifacts.files
            .filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".klib")) }
            .sortedBy { it.name }
            .forEach { file ->
                val entries = if (file.name.endsWith(".jar")) {
                    ArtifactScanner.scanJar(file, includes, classpath = resolvedJars)
                } else {
                    // The klib path takes no classpath yet, and whether it needs one is open
                    // rather than overlooked: the jar path needs other artefacts because an
                    // erased descriptor cannot name the class it erased, and a klib records
                    // Kotlin types directly. Settle it before trusting a cross-artefact type here.
                    //
                    // Not a direct `KlibScanner.scanKlib` call: see this class's KDoc, "The klib
                    // branch runs behind a worker classloader boundary".
                    workerExecutor.scanKlibIsolated(file, includes, temporaryDir, klibReaderClasspath.files).callables
                }
                // An artefact that contributed nothing gets no fragment: a fragment with no entries
                // would still take a `moduleName` in `UpcallTable`'s installed set, which is the one
                // thing that set is read for.
                if (entries.isEmpty()) return@forEach
                val coordinate = coordinates[file.name] ?: file.name.substringBeforeLast('.')
                val objectName = artifactFragmentObjectName(coordinate)
                destination.resolve("$objectName.kt").writeText(
                    renderArtifactFragmentSource(ArtifactFragment(objectName, "artifact:$coordinate", entries)),
                )
                // The `.class` half of the same fragment, and emitted from the same `entries` list
                // for the same reason `docs/design/pyi-generation-design.md` §2.2 gives for the declaration
                // models: two walks that can disagree are worse than one walk with two outputs.
                val thunks = thunkSpecsOf(entries)
                if (thunks.isNotEmpty()) {
                    thunkDestination.resolve("${objectName}Thunks.class")
                        .writeBytes(generateThunkClass(objectName, thunks))
                    logger.info("python-multiplatform: emitted ${thunks.size} composable thunk(s) for $coordinate")
                }
                objectNames += objectName
                logger.info("python-multiplatform: bound ${entries.size} declaration(s) from $coordinate")
            }

        destination.resolve("ArtifactTable.kt").writeText(renderArtifactTableSource(objectNames))
    }
}
