package python.multiplatform.gradle.stubs

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import python.multiplatform.gradle.artifact.ArtifactScanner
import python.multiplatform.gradle.artifact.scanKlibIsolated
import javax.inject.Inject

/**
 * `docs/design/pyi-generation-design.md`'s generator: `.pyi` stubs for the surface the bindings expose.
 *
 * ### Why this is a Gradle task at all
 *
 * The runtime binds Kotlin generically, so **nothing an editor can see is ever enumerated**. The stubs
 * are what pay that back: they carry the fully enumerated surface, under the Kotlin names, while the
 * runtime enumerates nothing. Making it Pythonic is `pythonx`'s job, not this plugin's. That
 * generation belongs to the Gradle plugin, the way PyREPL's `createKotlinMetaPackageForPython` did
 * it, is settled (`agent-rules.md` §12) and this task is it.
 *
 * ### What is kept from PyREPL and what is not
 *
 * §1.4's table. Kept: one `__init__.pyi` per package, `...` bodies, the `prepareKotlinIdeaImport`
 * hook. Rejected: `def f(self, *args, **kwargs)` (parameters are the point), the JNI-ish type map
 * (`jint` is not a Python type), dropping names containing `-` (that discards 52 of the 177 chainable
 * `Modifier` extensions), `class StringsKt:` for a file facade, and `meta_json.lock` (Gradle's own
 * up-to-date checking does this, and PyREPL's was disabled anyway).
 *
 * ### Where the output goes
 *
 * `build/generated/pythonStubs/<sourceSet>/`, not the source tree. PyREPL wrote into
 * `src/<sourceSet>/generated/meta/` and got away with it because chaquopy defines a Python
 * source-directory concept a plugin can add to; this repository has no equivalent, and **nothing
 * compiles a `.pyi`**, so there is no `srcDir(task)` to register it with (§6.2). Registering the
 * directory with an interpreter is per-environment -- a chaquopy `sourceSets.srcDirs`, a `.pth` in a
 * venv, PyCharm's interpreter path list -- and **none of that was verified**.
 *
 * ### One walk per task, two tasks
 *
 * This scans the same jars `PythonArtifactBindingsTask` does, in a separate task with its own
 * output. That is two walks of the same artefacts per build, and it is not the failure §2.2 rejects
 * ("two readers of the same jars that can disagree"): both call `ArtifactScanner`, which produces
 * the bindings and the model together and cannot answer the two differently. What is paid is time,
 * and what it buys is that a consumer who wants stubs and no bindings, or the reverse, gets one task.
 */
abstract class PythonStubsTask : DefaultTask() {

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    /** Every file the walked configuration resolved. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifacts: ConfigurableFileCollection

    /** Package or class names to stub; the same namespace matching the binder uses. */
    @get:Input
    abstract val includePrefixes: ListProperty<String>

    /**
     * The klib reader for the isolated worker, exactly as
     * [python.multiplatform.gradle.artifact.PythonArtifactBindingsTask.klibReaderClasspath] -- including
     * why it is `@Internal` rather than an input.
     */
    @get:Internal
    abstract val klibReaderClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = outputDirectory.get().asFile
        // Rebuilt from scratch, exactly as `PythonArtifactBindingsTask` is: a dependency that was
        // removed, or an include prefix that stopped matching, must take its stub with it.
        destination.deleteRecursively()
        destination.mkdirs()

        val includes = includePrefixes.get()
        val jars = artifacts.files.filter { it.isFile && it.name.endsWith(".jar") }
        val declarations = jars.sortedBy { it.name }.flatMap { jar ->
            ArtifactScanner.scanDeclarations(jar, includes, classpath = jars)
        } + artifacts.files.filter { it.isFile && it.name.endsWith(".klib") }.sortedBy { it.name }.flatMap { klib ->
            // A Kotlin/Native target resolves klibs where a JVM target resolves jars, and both
            // producers now build the same `DeclarationModel` -- so a stub is not a JVM-only product.
            // `KlibScanner`'s KDoc lists what a klib can and cannot fill; the fields it cannot are
            // exactly the ones it declines on, and a declined declaration is not stubbed.
            //
            // Not a direct `KlibScanner.scanKlibDeclarations` call: same classloader-scope collision
            // `PythonArtifactBindingsTask`'s KDoc documents, worked around the same way.
            workerExecutor.scanKlibIsolated(klib, includes, temporaryDir, klibReaderClasspath.files).declarations
        }

        val files = renderKotlinFqnStubs(declarations)
        files.forEach { (path, content) ->
            val file = destination.resolve(path)
            file.parentFile.mkdirs()
            file.writeText(content)
        }

        val bound = declarations.count { it.bindingName != null }
        logger.info(
            "python-multiplatform: ${files.size} stub file(s) for $bound bound declaration(s); " +
                "${declarations.size - bound} declined and not stubbed",
        )
    }
}
