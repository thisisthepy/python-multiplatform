package python.multiplatform.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction
import java.io.File

/** `docs/design/typedpython.md` section 4.3.2a (`emit-c` manifest format). */
const val TYPEDPYTHON_EMIT_C_FORMAT = "typedpython-emit-c"

/** `docs/design/typedpython.md` section 4.3.2a (`emit-c` manifest version). */
const val TYPEDPYTHON_EMIT_C_VERSION = 1

/** Task name registered by [PythonBindingsPlugin] when [PythonBindingsExtension.typedPythonSourceDir] is set. */
const val EMIT_TYPEDPYTHON_C_TASK = "emitTypedPythonC"

/**
 * One extension module's generated C and header/define metadata for pypackpack's compile slot
 * (`pypackpack` `ExtensionCompiler.kt`, `BundleRequest.nativeModules`).
 */
data class ExtensionModuleSource(
    val moduleName: String,
    val sources: List<File>,
    val includeDirs: List<File> = emptyList(),
    val defines: Map<String, String?> = emptyMap(),
)

/**
 * One function or class that stayed interpreted, and why (`pypackpack` `docs/design/compile-slot.md` section 2).
 *
 * @param file path relative to `projectRoot`, `/`-separated.
 * @param line 1-based line number, or `null` when unavailable.
 */
data class SkippedItem(
    val name: String,
    val file: String,
    val line: Int?,
    val message: String,
)

/**
 * One developer module for which TypedPython produced no C, and why (`pypackpack` `BundleRequest.notNativeModules`).
 */
data class NotNativeModule(
    val module: String,
    val kind: Kind,
    val items: List<SkippedItem> = emptyList(),
) {
    enum class Kind {
        NOT_MARKED,
        NOTHING_COMPILED,
    }
}

/**
 * One entry of `nativeModules` in the `emit-c` manifest (`docs/design/typedpython.md` section 4.3.2a),
 * carrying both the compile-slot fields ([toExtensionModuleSource]) and the reporting fields
 * ([file], [status], [skippedItems]).
 */
data class TypedPythonNativeModuleEntry(
    val moduleName: String,
    val file: String,
    val sources: List<File>,
    val includeDirs: List<File> = emptyList(),
    val defines: Map<String, String?> = emptyMap(),
    val status: String,
    val skippedItems: List<SkippedItem> = emptyList(),
) {
    fun toExtensionModuleSource(): ExtensionModuleSource =
        ExtensionModuleSource(
            moduleName = moduleName,
            sources = sources,
            includeDirs = includeDirs,
            defines = defines,
        )
}

/**
 * Parsed `typedpython-emit-c` v1 manifest (`docs/design/typedpython.md` section 4.3.2a, SPEC N-10).
 */
data class TypedPythonEmitCManifest(
    val format: String,
    val version: Int,
    val compilerVersion: String,
    val projectRoot: File,
    val runtimeInclude: File,
    val nativeModules: List<TypedPythonNativeModuleEntry>,
    val notNativeModules: List<NotNativeModule>,
) {
    /** Maps [nativeModules] onto pypackpack's `BundleRequest.nativeModules`. */
    fun toExtensionModuleSources(): List<ExtensionModuleSource> =
        nativeModules.map { it.toExtensionModuleSource() }

    /** Maps [notNativeModules] onto pypackpack's `BundleRequest.notNativeModules`. */
    fun toNotNativeModules(): List<NotNativeModule> = notNativeModules
}

/**
 * Parses a `typedpython-emit-c` v1 JSON manifest (`docs/design/typedpython.md` section 4.3.2a)
 * into [TypedPythonEmitCManifest].
 *
 * Unknown top-level or entry keys are ignored as specified in section 4.3.2a ("adding keys does not
 * bump the version"). Unknown `format`, `version` or `notNativeModules[i].kind` fails with
 * [IllegalArgumentException].
 */
fun parseTypedPythonEmitCManifest(json: String): TypedPythonEmitCManifest {
    val root = try {
        JsonSlurper().parseText(json)
    } catch (e: Exception) {
        throw IllegalArgumentException("typedpython emit-c manifest is not valid JSON: ${e.message}", e)
    } as? Map<*, *> ?: throw IllegalArgumentException("typedpython emit-c manifest is not a JSON object.")

    val format = root["format"] as? String
    require(format == TYPEDPYTHON_EMIT_C_FORMAT) {
        "typedpython emit-c manifest has format '$format'; expected '$TYPEDPYTHON_EMIT_C_FORMAT'."
    }
    val version = (root["version"] as? Number)?.toInt()
    require(version == TYPEDPYTHON_EMIT_C_VERSION) {
        "typedpython emit-c manifest has version '${root["version"]}'; expected version $TYPEDPYTHON_EMIT_C_VERSION."
    }

    fun requireString(map: Map<*, *>, key: String, owner: String): String =
        (map[key] as? String)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner.$key' is missing or empty.")

    fun parseSkippedItems(rawList: Any?, owner: String): List<SkippedItem> {
        val list = rawList as? List<*>
            ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner' must be a list.")
        return list.mapIndexed { idx, elem ->
            val item = elem as? Map<*, *>
                ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner[$idx]' is not an object.")
            val lineVal = item["line"]
            val line = when (lineVal) {
                null -> null
                is Number -> lineVal.toInt()
                else -> throw IllegalArgumentException(
                    "typedpython emit-c manifest: '$owner[$idx].line' must be an integer or null, got '$lineVal'.",
                )
            }
            SkippedItem(
                name = requireString(item, "name", "$owner[$idx]"),
                file = requireString(item, "file", "$owner[$idx]"),
                line = line,
                message = (item["message"] as? String)
                    ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner[$idx].message' is missing."),
            )
        }
    }

    val rawNative = root["nativeModules"] as? List<*>
        ?: throw IllegalArgumentException("typedpython emit-c manifest: 'nativeModules' is missing or not a list.")
    val nativeModules = rawNative.mapIndexed { idx, elem ->
        val owner = "nativeModules[$idx]"
        val map = elem as? Map<*, *>
            ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner' is not an object.")
        val sourcesRaw = map["sources"] as? List<*>
            ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner.sources' is missing or not a list.")
        val sources = sourcesRaw.map { path ->
            val str = (path as? String)?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner.sources' contains a non-string entry.")
            File(str)
        }
        val includeDirsRaw = map["includeDirs"] as? List<*>
            ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner.includeDirs' is missing or not a list.")
        val includeDirs = includeDirsRaw.map { path ->
            val str = (path as? String)?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner.includeDirs' contains a non-string entry.")
            File(str)
        }
        val definesRaw = map["defines"] as? Map<*, *>
            ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner.defines' is missing or not an object.")
        val defines = definesRaw.entries.associate { (k, v) ->
            val key = (k as? String)?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner.defines' has a blank key.")
            val value = when (v) {
                null -> null
                is String -> v
                else -> v.toString()
            }
            key to value
        }
        TypedPythonNativeModuleEntry(
            moduleName = requireString(map, "moduleName", owner),
            file = requireString(map, "file", owner),
            sources = sources,
            includeDirs = includeDirs,
            defines = defines,
            status = requireString(map, "status", owner),
            skippedItems = parseSkippedItems(map["skippedItems"] ?: emptyList<Any>(), "$owner.skippedItems"),
        )
    }

    val rawNotNative = root["notNativeModules"] as? List<*>
        ?: throw IllegalArgumentException("typedpython emit-c manifest: 'notNativeModules' is missing or not a list.")
    val notNativeModules = rawNotNative.mapIndexed { idx, elem ->
        val owner = "notNativeModules[$idx]"
        val map = elem as? Map<*, *>
            ?: throw IllegalArgumentException("typedpython emit-c manifest: '$owner' is not an object.")
        val kindRaw = requireString(map, "kind", owner)
        val kind = try {
            NotNativeModule.Kind.valueOf(kindRaw)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "typedpython emit-c manifest: '$owner.kind' has unknown kind '$kindRaw'; " +
                    "expected one of ${NotNativeModule.Kind.values().joinToString()}.",
                e,
            )
        }
        NotNativeModule(
            module = requireString(map, "module", owner),
            kind = kind,
            items = parseSkippedItems(map["items"] ?: emptyList<Any>(), "$owner.items"),
        )
    }

    return TypedPythonEmitCManifest(
        format = format,
        version = version,
        compilerVersion = requireString(root, "compilerVersion", "root"),
        projectRoot = File(requireString(root, "projectRoot", "root")),
        runtimeInclude = File(requireString(root, "runtimeInclude", "root")),
        nativeModules = nativeModules,
        notNativeModules = notNativeModules,
    )
}

/**
 * Builds the `python -m typedpython emit-c` command line (`docs/design/typedpython.md` section 4.3.2a).
 * Input files are sorted by their path relative to [rootDir] so the manifest module order is deterministic.
 */
fun typedPythonEmitCCommand(
    pythonExecutable: String,
    files: List<File>,
    rootDir: File,
    cacheDir: File,
    manifestFile: File,
): List<String> {
    val rootAbs = rootDir.absoluteFile
    val sorted = files.map { it.absoluteFile }.sortedBy { file ->
        file.relativeToOrSelf(rootAbs).invariantSeparatorsPath
    }
    return buildList {
        add(pythonExecutable)
        add("-m")
        add("typedpython")
        add("emit-c")
        sorted.forEach { add(it.absolutePath) }
        add("--root")
        add(rootAbs.absolutePath)
        add("--cache")
        add(cacheDir.absoluteFile.absolutePath)
        add("--manifest")
        add(manifestFile.absoluteFile.absolutePath)
    }
}

/**
 * SPEC N-10 (`python-multiplatform#25` step 2): runs `python -m typedpython emit-c` over [sources]
 * and writes the per-module C cache ([cacheDirectory]) and the v1 JSON manifest ([manifestFile])
 * for pypackpack's native compile slot.
 */
abstract class TypedPythonEmitCTask : DefaultTask() {

    /** The developer `.py` files to compile to C. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /**
     * The directory module names are derived relative to (`--root`, the ppp package's `python/`
     * or `src/main` root). Fingerprinted through [sources]'s `PathSensitivity.RELATIVE`.
     */
    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    /** The Python interpreter executable that runs `-m typedpython emit-c` (defaults to `"python3"`). */
    @get:Input
    abstract val pythonExecutable: Property<String>

    /** Optional `PYTHONPATH` entries for locating the `typedpython` package when not installed in the interpreter. */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val pythonPath: ConfigurableFileCollection

    /** `generate_project`'s incremental source-to-C cache directory (`--cache`). */
    @get:OutputDirectory
    abstract val cacheDirectory: DirectoryProperty

    /** The JSON manifest written by `emit-c` (`--manifest`). */
    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    init {
        pythonExecutable.convention("python3")
    }

    /** Parses the manifest written at [manifestFile] into [TypedPythonEmitCManifest]. */
    fun readManifest(): TypedPythonEmitCManifest =
        parseTypedPythonEmitCManifest(manifestFile.get().asFile.readText())

    @TaskAction
    fun emitC() {
        val pyFiles = sources.files.filter { it.isFile && it.extension == "py" }
        val root = rootDirectory.get().asFile
        val cache = cacheDirectory.get().asFile
        val manifest = manifestFile.get().asFile
        val executable = pythonExecutable.getOrElse("python3")

        val command = typedPythonEmitCCommand(
            pythonExecutable = executable,
            files = pyFiles,
            rootDir = root,
            cacheDir = cache,
            manifestFile = manifest,
        )
        logger.info("typedPythonEmitC: ${command.joinToString(" ")}")

        val work = temporaryDir.apply { mkdirs() }
        val tmp = File(work, "tmp").apply { mkdirs() }
        val stdoutFile = File(work, "emit-c.stdout")
        val stderrFile = File(work, "emit-c.stderr")

        val pb = ProcessBuilder(command)
            .directory(project.projectDir)
            .redirectOutput(stdoutFile)
            .redirectError(stderrFile)
        pb.environment()["TMPDIR"] = tmp.absolutePath
        val extraPythonPath = pythonPath.files.map { it.absolutePath }.filter { it.isNotEmpty() }
        if (extraPythonPath.isNotEmpty()) {
            val existing = pb.environment()["PYTHONPATH"].orEmpty()
            val joined = extraPythonPath.joinToString(File.pathSeparator)
            pb.environment()["PYTHONPATH"] = if (existing.isEmpty()) joined else "$joined${File.pathSeparator}$existing"
        }

        val exitCode = pb.start().waitFor()
        if (exitCode != 0) {
            val details = listOf(stderrFile.readText().trim(), stdoutFile.readText().trim())
                .filter { it.isNotEmpty() }
                .joinToString("\n")
                .ifEmpty { "(no output)" }
            throw GradleException("typedpython emit-c failed (exit $exitCode):\n$details")
        }
    }
}
