package python.multiplatform.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.inject.Inject

/**
 * Unpacking `python-multiplatform`'s published wasm browser runtime for a consumer -- ROADMAP §10.
 *
 * ### The gap this closes
 *
 * `python-multiplatform`'s own `stageWasmBrowserRuntime` assembles CPython's Emscripten build plus
 * this library's `cpython.mjs` into a directory a `wasmJsMain.resources.srcDir` can point at, and
 * `:sample` proved that assembling it is enough to make a *browser* bundle come up (not just the
 * node test runner). But that task reads a **local directory** named by `-PwasmPythonDir`, which
 * exists only inside this repository's checkout. An external consumer has no such directory and no
 * way to get one -- the CPython build it names is produced by hand-patching CPython's own source
 * (`Lib/sysconfig`, the generated `Makefile`) against a specific Emscripten SDK version, which is
 * not something any upstream publishes yet. See `tools/wasm/build-cpython.sh`'s own header for why: PEP
 * 783's `pyemscripten_2026_0` tag is real and accepted, but nothing ships a `python.wasm` whose
 * `EXPORTED_RUNTIME_METHODS` include `wasmExports`/`wasmMemory` -- that addition exists only so this
 * library's `@WasmImport` declarations have something to bind to, and no distributor has a reason to
 * carry it.
 *
 * So the *runtime itself* had to become something a consumer's build can resolve, not just
 * something this repository can assemble. `python-multiplatform/build.gradle.kts` zips
 * `stageWasmBrowserRuntime`'s output and publishes it under its own artifact ID
 * (`python-multiplatform-wasm-runtime`), at the library's own version -- so resolving the library
 * and resolving its runtime never drift apart the way two independently-versioned coordinates could.
 * This task is the other half: unpack that zip for whichever build actually needs it.
 *
 * ### Why a detached configuration rather than a task dependency
 *
 * A consumer who never adds a `wasmJs()` target must not need this artifact to exist in `mavenLocal`
 * (or wherever it eventually publishes) at all -- most won't build a browser bundle, and this
 * artifact is ~7 MB compressed. [PythonBindingsPlugin.apply] therefore always *registers* this task
 * (registration is free), but the detached configuration behind [runtimeArtifact] is resolved only
 * when this task actually executes, and it only executes when something depends on it -- which
 * [PythonBindingsPlugin] wires up by matching `wasmJs*` task names, so it only happens for a project
 * that actually has the target.
 */
abstract class StageWasmBrowserRuntimeTask : DefaultTask() {

    /** The resolved `python-multiplatform-wasm-runtime` zip; a detached configuration's files. */
    @get:Classpath
    abstract val runtimeArtifact: ConfigurableFileCollection

    /** Where the zip is unpacked to -- what a consumer's `wasmJsMain.resources.srcDir` names. */
    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    @get:Inject
    protected abstract val archives: ArchiveOperations

    @get:Inject
    protected abstract val fs: FileSystemOperations

    @TaskAction
    fun stage() {
        val zip = runtimeArtifact.singleFile
        val dir = destinationDir.get().asFile
        // Deleted and rewritten whole rather than merged: a stale `python.wasm` sitting next to a
        // fresh `cpython.mjs` from a different library version is exactly the ABI-drift bug
        // `verifyWasmAbiSignatures` exists to catch upstream, and unpacking into a mixed directory
        // here would hide it instead.
        fs.delete { delete(dir) }
        fs.copy {
            from(archives.zipTree(zip))
            into(dir)
        }
        logger.lifecycle("Staged CPython's wasm browser runtime (from $zip) into $dir")
    }
}

/**
 * Rewrites a consumer's generated Kotlin/Wasm output so that it can reach the interpreter.
 *
 * Byte-identical in intent to `patchKotlinWasmOutputForCPython` in
 * `python-multiplatform/build.gradle.kts`, and to `:sample`'s own copy of it -- ROADMAP §10 records
 * why there were already two: a Gradle build script's functions are not visible to another
 * project's build script, so every consumer had to carry this by hand. This is the third copy and
 * the one meant to end the duplication -- it lives where [PythonBindingsPlugin] can reach it, so a
 * consumer who applies the plugin does not write it at all.
 *
 * Deliberately free of any Kotlin Gradle Plugin type: it edits generated `.mjs` files with `File` and
 * `Regex`, the same way the two prior copies do, so this plugin's `kotlin-dsl` classpath (Gradle's
 * embedded Kotlin, not this repository's) never needs to see Kotlin/Wasm's task types.
 *
 * @param dir the compile-sync output webpack reads, `build/wasm/packages/<name>/kotlin`.
 * @param modulePrefix the generated module basename, which is `<root project>-<project>`.
 */
internal fun patchWasmOutputForCPython(dir: File, modulePrefix: String, logger: Logger) {
    val importObject = dir.listFiles()?.firstOrNull { it.name.endsWith(".import-object.mjs") }
        ?: throw GradleException("No *.import-object.mjs in $dir -- the Kotlin/Wasm output layout changed.")
    val text = importObject.readText()

    // Unlike `:sample` and the library's own wasmJs test bundle -- which always call into the FFI,
    // so the import is guaranteed present -- a generic external consumer may build a wasmJs target
    // whose reachable code never calls `python-multiplatform` at all (observed against
    // `consumer-plugin-android`'s KSP-generated `FunctionTable`, which wraps plain Kotlin functions
    // with no path into `bindings.kt`). Kotlin/Wasm's dead-code elimination then removes every
    // `@WasmImport` declaration along with the `./cpython.mjs` import that names them, and there is
    // nothing to patch -- which is correct, not an error to fail the build over.
    val ns = Regex("""import \* as (\w+) from ['"]\./cpython\.mjs['"];""").find(text)?.groupValues?.get(1)
    if (ns == null) {
        logger.info(
            "${importObject.name} does not import ./cpython.mjs -- nothing in this module's " +
                "reachable wasmJs code calls into python-multiplatform, so there is nothing to patch."
        )
        return
    }
    val placeholder = Regex("""memory:\s*new WebAssembly\.Memory\(\{[^}]*}\)""")
    if (placeholder.containsMatchIn(text)) {
        importObject.writeText(placeholder.replace(text, "memory: $ns.wasmMemory"))
        logger.lifecycle("Pointed ${importObject.name}'s intrinsics.memory at Emscripten's wasmMemory")
    } else if (!text.contains("memory: $ns.wasmMemory")) {
        throw GradleException(
            "${importObject.name} has no `intrinsics.memory` placeholder to replace and is not " +
                "already patched. See docs/platforms/wasm-design.md's integration step."
        )
    }

    val entry = dir.listFiles()
        ?.firstOrNull {
            it.name.endsWith(".mjs") && !it.name.contains("import-object") &&
                !it.name.contains("js-builtins") && it.name.startsWith(modulePrefix)
        }
        ?: throw GradleException("No Kotlin/Wasm entry module in $dir -- the output layout changed.")
    val handoff = "pmpSetKotlinExports"
    val entryText = entry.readText()
    if (!entryText.contains(handoff)) {
        if (!entryText.contains("const exports = wasmInstance.exports")) {
            throw GradleException(
                "${entry.name} has no `const exports = wasmInstance.exports` to hand to cpython.mjs."
            )
        }
        // Before `exports._start()`, not appended after it -- `_start()` is Kotlin `main()` in an
        // executable bundle, and appending ran the handoff one line too late. See ROADMAP §10.
        val startCall = "exports._start();"
        val note = "// Added by python-multiplatform-gradle-plugin: upcall registration needs the\n" +
            "// raw wasm exports, and this is the only scope that has them. See ROADMAP §7/§10.\n"
        val body = if (entryText.contains(startCall)) {
            entryText.replace(startCall, "$handoff(exports);\n\n$startCall")
        } else {
            entryText.trimEnd() + "\n\n$handoff(exports);\n"
        }
        entry.writeText(note + "import { $handoff } from './cpython.mjs';\n\n" + body)
        logger.lifecycle("Handed ${entry.name}'s wasm exports to cpython.mjs for upcall registration")
    }
}
