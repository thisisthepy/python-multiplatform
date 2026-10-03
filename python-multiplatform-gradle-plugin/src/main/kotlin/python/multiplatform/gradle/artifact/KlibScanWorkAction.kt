package python.multiplatform.gradle.artifact

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import python.multiplatform.gradle.DEFAULT_KLIB_WORKER_COORDINATES
import python.multiplatform.gradle.model.DeclarationModel
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable

/**
 * Runs [KlibScanner] behind a `WorkerExecutor` classloader boundary, because running it in this
 * plugin's own classloader does not work in a real consumer build.
 *
 * ### Why a worker at all
 *
 * `KlibScanner`'s KDoc and `python-multiplatform-gradle-plugin/build.gradle.kts`'s
 * `kotlin-compiler-embeddable` dependency comment record the measurement: `LibraryAbiReader` (the
 * pinned `2.0.20` classes this plugin is compiled against) and `KotlinLibraryImplKt` (the Kotlin
 * Gradle Plugin's `kotlin-util-klib`, a *different* version, reachable from this plugin's own
 * classloader in every consumer build) both answer to `org.jetbrains.kotlin.library.*`, and delegation
 * serves each class from whichever source resolves it first. The two do not agree on
 * `KotlinLibraryImpl`'s constructor, hence `NoSuchMethodError`.
 *
 * A `WorkerExecutor` submitted with `classLoaderIsolation` builds its action's classloader from an
 * explicit classpath with Gradle's own minimal worker infrastructure as its parent -- **not** this
 * plugin's classloader and **not** the buildscript scopes around it. `org.jetbrains.kotlin.library.*`
 * then has exactly one source inside the worker, provided the explicit classpath itself is clean: see
 * [pluginCodeSource] for how a classpath *derived from the plugin classloader* smuggled the collision
 * back in, and what it is built from now.
 *
 * ### What crosses the boundary, and how
 *
 * Gradle's `WorkParameters` marshal managed property types (`RegularFileProperty`,
 * `ListProperty<String>`) across the isolation, but a `WorkAction` has no return value -- there is no
 * property Gradle marshals back out. [KlibScanResult] is therefore written to [Params.resultFile] with
 * plain `ObjectOutputStream`/`ObjectInputStream` rather than handed back through the worker API: both
 * ends load `DeclarationModel`/`ArtifactCallable` from the *same* class file bytes (this plugin's own
 * jar, present on both the coordinator's classloader and the worker's classpath via
 * [pluginCodeSource]), so
 * Java serialization's structural check (`serialVersionUID`, computed from the members when none is
 * declared) matches even though the two ends loaded the class through different `ClassLoader`
 * instances -- serialization was never identity-based to begin with.
 */
internal abstract class KlibScanWorkAction : WorkAction<KlibScanWorkAction.Params> {

    internal interface Params : WorkParameters {
        val klibFile: RegularFileProperty
        val includePrefixes: ListProperty<String>
        val resultFile: RegularFileProperty
    }

    override fun execute() {
        val klib = parameters.klibFile.get().asFile
        val includes = parameters.includePrefixes.get()
        val result = KlibScanResult(
            callables = KlibScanner.scanKlib(klib, includes),
            declarations = KlibScanner.scanKlibDeclarations(klib, includes),
        )
        ObjectOutputStream(parameters.resultFile.get().asFile.outputStream()).use { it.writeObject(result) }
    }
}

/** Both of [KlibScanner]'s results for one klib, carried across [KlibScanWorkAction]'s isolation
 * boundary in one round trip rather than two -- the same "one walk" the JVM producer's own
 * [Candidate] already keeps to inside a single process. */
internal data class KlibScanResult(
    val callables: List<ArtifactCallable>,
    val declarations: List<DeclarationModel>,
) : Serializable

/**
 * This plugin's own jar (or classes directory), and nothing else -- the only entry of the worker
 * classpath that cannot be resolved from a repository, because it is what carries [KlibScanner],
 * [KlibScanWorkAction] and the [python.multiplatform.gradle.model.DeclarationModel] both ends
 * deserialize.
 *
 * ### This used to be the plugin classloader's `.urLs`, and that never isolated anything
 *
 * The previous version handed the worker `(KlibScanner::class.java.classLoader as URLClassLoader).urLs`,
 * reasoning that a `URLClassLoader`'s own URLs are the child scope only, never the parent's. The
 * reasoning is right about `URLClassLoader` and wrong about *which* classloader this is. Printed from
 * inside a real external consumer (`plugins { kotlin("multiplatform"); id("...bindings") }`), those 28
 * URLs are:
 *
 *     python-multiplatform-gradle-plugin-3.13.0.jar   kotlin-metadata-jvm-2.0.20.jar
 *     symbol-processing-gradle-plugin-2.3.11.jar      asm-9.7.1.jar, asm-tree-9.7.1.jar
 *     kotlin-gradle-plugin-2.4.20-Beta2.jar           kotlin-util-klib-2.4.20-Beta2.jar
 *     kotlin-util-io-2.4.20-Beta2.jar                 kotlin-native-utils-2.4.20-Beta2.jar
 *     ... and the rest of the Kotlin Gradle Plugin
 *
 * Gradle gives every plugin resolved into the *same* `plugins { }` block one classloader, so KGP's own
 * jars are not a parent scope out there -- they are on this very list. `kotlin-util-klib` is precisely
 * the jar whose `KotlinLibraryImplKt` does not fit this plugin's `LibraryAbiReader`, so copying that
 * list into the worker copied the collision in with it, and
 * `:generatePythonArtifactBindings` failed with the same
 * `NoSuchMethodError: KotlinLibraryImplKt.createKotlinLibrary$default` the isolation was introduced to
 * fix. In this repository it happened to work: `ksp-fixtures` resolves the plugin from an *included
 * build*, which does get a scope of its own, so no in-repo fixture can see this.
 *
 * The worker classpath is therefore built from an explicit coordinate list
 * (`DEFAULT_KLIB_WORKER_COORDINATES`, resolved by the consumer's project) plus this one file. Nothing
 * a consumer's build happens to put on a classloader can reach it.
 */
private fun pluginCodeSource(): File {
    val location = KlibScanWorkAction::class.java.protectionDomain?.codeSource?.location
        ?: error(
            "python-multiplatform-gradle-plugin: KlibScanWorkAction has no code source location, so " +
                "the isolated worker cannot be given the plugin's own classes. See KlibScanWorkAction.kt.",
        )
    return File(location.toURI())
}

/**
 * Runs [KlibScanner] over [klib] inside an isolated worker classloader and returns both of its
 * results -- see [KlibScanWorkAction]'s KDoc for why this indirection exists at all.
 *
 * [readerClasspath] is `DEFAULT_KLIB_WORKER_COORDINATES` resolved by the consumer's own project: the
 * klib reader (`kotlin-compiler-embeddable`), `kotlin-metadata-jvm` and ASM, which
 * [python.multiplatform.gradle.artifact.ArtifactScanner] needs since [KlibScanner] calls into it, plus
 * `kotlin-stdlib` behind them. Together with [pluginCodeSource] that is the *whole* worker classpath --
 * see [pluginCodeSource]'s KDoc for the measurement that shows why nothing may be taken from the
 * plugin's classloader, and this build's `compileOnly` comment for why the reader is not a dependency
 * of the plugin in the first place.
 *
 * Passed in rather than resolved here: a `Configuration` is a project-scoped object and this runs
 * inside a task action, so the resolution has to have been declared at configuration time. The task's
 * `klibReaderClasspath` property is that declaration.
 *
 * [scratchDirectory] holds the round-trip file only; the caller owns cleanup the same way it owns
 * `Task.getTemporaryDir()`; this function does not delete anything, so two calls in the same build
 * (jars and stubs) don't race each other's cleanup.
 */
internal fun WorkerExecutor.scanKlibIsolated(
    klib: File,
    includes: List<String>,
    scratchDirectory: File,
    readerClasspath: Collection<File>,
): KlibScanResult {
    require(readerClasspath.isNotEmpty()) {
        "python-multiplatform-gradle-plugin: no klib reader on the worker classpath. " +
            "`pythonBindings` resolves ${DEFAULT_KLIB_WORKER_COORDINATES.joinToString()} for this; an empty " +
            "classpath would fail inside the worker with NoClassDefFoundError: " +
            "org/jetbrains/kotlin/library/abi/LibraryAbiReader."
    }
    scratchDirectory.mkdirs()
    val resultFile = File.createTempFile("klib-scan-", ".ser", scratchDirectory)
    val queue = classLoaderIsolation { classpath.from(listOf(pluginCodeSource()) + readerClasspath) }
    queue.submit(KlibScanWorkAction::class.java) {
        klibFile.set(klib)
        includePrefixes.set(includes)
        this.resultFile.set(resultFile)
    }
    queue.await()
    return ObjectInputStream(resultFile.inputStream()).use { it.readObject() as KlibScanResult }
}
