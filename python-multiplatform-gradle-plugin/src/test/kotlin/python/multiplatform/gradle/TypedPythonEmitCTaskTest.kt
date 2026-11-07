package python.multiplatform.gradle

import org.gradle.api.GradleException
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
import org.gradle.testfixtures.ProjectBuilder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SPEC N-10 (python-multiplatform#25 step 2): Gradle task running `python -m typedpython emit-c`
 * with declared inputs/outputs and mapping the v1 manifest (`docs/design/typedpython.md` section 4.3.2a)
 * onto pypackpack's `BundleRequest.nativeModules` (`ExtensionModuleSource`) and
 * `BundleRequest.notNativeModules` (`NotNativeModule` / `SkippedItem`).
 */
class TypedPythonEmitCTaskTest {

    private val sampleManifestJson =
        """
        {
          "format": "typedpython-emit-c",
          "version": 1,
          "compilerVersion": "abc123def456",
          "projectRoot": "/abs/proj",
          "runtimeInclude": "/abs/site-packages/typedpython/runtime",
          "nativeModules": [
            {
              "moduleName": "app.physics.nbody",
              "file": "app/physics/nbody.py",
              "sources": ["/abs/cache/nbody.c"],
              "includeDirs": ["/abs/site-packages/typedpython/runtime"],
              "defines": {"TP_FLAG": "1", "TP_BARE": null},
              "status": "rebuilt",
              "skippedItems": [
                {"name": "main", "file": "app/physics/nbody.py", "line": 30, "message": "not pure"}
              ]
            }
          ],
          "notNativeModules": [
            {
              "module": "app.util",
              "file": "app/util.py",
              "kind": "NOT_MARKED",
              "items": []
            },
            {
              "module": "app.text",
              "file": "app/text.py",
              "kind": "NOTHING_COMPILED",
              "items": [
                {"name": "f", "file": "app/text.py", "line": 2, "message": "str slice is not supported"},
                {"name": "g", "file": "app/text.py", "line": null, "message": "no line"}
              ]
            }
          ]
        }
        """.trimIndent()

    @Test
    fun parsesManifestV1AndMapsToBundleRequestShapes() {
        val manifest = parseTypedPythonEmitCManifest(sampleManifestJson)
        assertEquals(TYPEDPYTHON_EMIT_C_FORMAT, manifest.format)
        assertEquals(TYPEDPYTHON_EMIT_C_VERSION, manifest.version)
        assertEquals("abc123def456", manifest.compilerVersion)
        assertEquals(File("/abs/proj"), manifest.projectRoot)
        assertEquals(File("/abs/site-packages/typedpython/runtime"), manifest.runtimeInclude)

        assertEquals(1, manifest.nativeModules.size)
        val nativeEntry = manifest.nativeModules.single()
        assertEquals("app.physics.nbody", nativeEntry.moduleName)
        assertEquals("app/physics/nbody.py", nativeEntry.file)
        assertEquals(listOf(File("/abs/cache/nbody.c")), nativeEntry.sources)
        assertEquals(listOf(File("/abs/site-packages/typedpython/runtime")), nativeEntry.includeDirs)
        assertEquals(mapOf("TP_FLAG" to "1", "TP_BARE" to null), nativeEntry.defines)
        assertEquals("rebuilt", nativeEntry.status)
        assertEquals(
            listOf(SkippedItem("main", "app/physics/nbody.py", 30, "not pure")),
            nativeEntry.skippedItems,
        )

        val mappedNative = manifest.toExtensionModuleSources()
        assertEquals(
            listOf(
                ExtensionModuleSource(
                    moduleName = "app.physics.nbody",
                    sources = listOf(File("/abs/cache/nbody.c")),
                    includeDirs = listOf(File("/abs/site-packages/typedpython/runtime")),
                    defines = mapOf("TP_FLAG" to "1", "TP_BARE" to null),
                ),
            ),
            mappedNative,
        )

        val mappedNotNative = manifest.toNotNativeModules()
        assertEquals(2, mappedNotNative.size)
        assertEquals(
            NotNativeModule(
                module = "app.util",
                kind = NotNativeModule.Kind.NOT_MARKED,
                items = emptyList(),
            ),
            mappedNotNative[0],
        )
        assertEquals(
            NotNativeModule(
                module = "app.text",
                kind = NotNativeModule.Kind.NOTHING_COMPILED,
                items = listOf(
                    SkippedItem("f", "app/text.py", 2, "str slice is not supported"),
                    SkippedItem("g", "app/text.py", null, "no line"),
                ),
            ),
            mappedNotNative[1],
        )
        assertNull(mappedNotNative[1].items[1].line)
    }

    @Test
    fun AdditiveTopLevelOrEntryKeysDoNotBreakVersion1Parsing() {
        val withExtraKeys = sampleManifestJson.replace(
            "\"version\": 1,",
            "\"version\": 1, \"futureTopKey\": 42,",
        )
        val manifest = parseTypedPythonEmitCManifest(withExtraKeys)
        assertEquals(1, manifest.nativeModules.size)
        assertEquals(2, manifest.notNativeModules.size)
    }

    @Test
    fun rejectsUnknownFormatVersionOrKind() {
        val wrongFormat = sampleManifestJson.replace("\"typedpython-emit-c\"", "\"other-format\"")
        val formatErr = assertFailsWith<IllegalArgumentException> { parseTypedPythonEmitCManifest(wrongFormat) }
        assertTrue("typedpython-emit-c" in formatErr.message.orEmpty())

        val wrongVersion = sampleManifestJson.replace("\"version\": 1", "\"version\": 2")
        val versionErr = assertFailsWith<IllegalArgumentException> { parseTypedPythonEmitCManifest(wrongVersion) }
        assertTrue("version" in versionErr.message.orEmpty())

        val wrongKind = sampleManifestJson.replace("\"NOT_MARKED\"", "\"UNKNOWN_KIND\"")
        val kindErr = assertFailsWith<IllegalArgumentException> { parseTypedPythonEmitCManifest(wrongKind) }
        assertTrue("UNKNOWN_KIND" in kindErr.message.orEmpty())
    }

    @Test
    fun buildsDeterministicCommandLineWithSortedInputFiles() {
        val root = File("/proj/src/main")
        val cache = File("/proj/build/typedpython/c-cache")
        val manifest = File("/proj/build/typedpython/emit-c-manifest.json")
        val b = File("/proj/src/main/app/b.py")
        val a = File("/proj/src/main/app/a.py")

        val cmd = typedPythonEmitCCommand(
            pythonExecutable = "/venv/bin/python3",
            files = listOf(b, a),
            rootDir = root,
            cacheDir = cache,
            manifestFile = manifest,
        )
        assertEquals(
            listOf(
                "/venv/bin/python3",
                "-m",
                "typedpython",
                "emit-c",
                a.absolutePath,
                b.absolutePath,
                "--root",
                root.absolutePath,
                "--cache",
                cache.absolutePath,
                "--manifest",
                manifest.absolutePath,
            ),
            cmd,
        )
    }

    @Test
    fun taskDeclaresGradleInputsAndOutputsForIncrementalAndBuildCacheSupport() {
        val getters = TypedPythonEmitCTask::class.java.methods.associateBy { it.name }

        val getSources = getters.getValue("getSources")
        assertTrue(getSources.isAnnotationPresent(InputFiles::class.java))
        assertTrue(getSources.isAnnotationPresent(SkipWhenEmpty::class.java))
        assertTrue(getSources.isAnnotationPresent(IgnoreEmptyDirectories::class.java))
        assertEquals(
            PathSensitivity.RELATIVE,
            getSources.getAnnotation(PathSensitive::class.java)?.value,
        )

        assertTrue(getters.getValue("getRootDirectory").isAnnotationPresent(Internal::class.java))
        assertTrue(getters.getValue("getPythonExecutable").isAnnotationPresent(Input::class.java))

        val getPythonPath = getters.getValue("getPythonPath")
        assertTrue(getPythonPath.isAnnotationPresent(InputFiles::class.java))
        assertTrue(getPythonPath.isAnnotationPresent(Optional::class.java))
        assertEquals(
            PathSensitivity.RELATIVE,
            getPythonPath.getAnnotation(PathSensitive::class.java)?.value,
        )

        assertTrue(getters.getValue("getCacheDirectory").isAnnotationPresent(OutputDirectory::class.java))
        assertTrue(getters.getValue("getManifestFile").isAnnotationPresent(OutputFile::class.java))
    }

    @Test
    fun taskActionExecutesStubEmitCAndParsesResultingManifest() {
        val project = ProjectBuilder.builder().build()
        val pkgRoot = project.file("pkg/src/main").apply { mkdirs() }
        val pyFile = File(pkgRoot, "app/fast.py").apply {
            parentFile.mkdirs()
            writeText("@compiled\ndef inc(n: int) -> int:\n    return n + 1\n")
        }
        val fakeStubRoot = project.file("fake-pythonpath").apply { mkdirs() }
        val tpPkg = File(fakeStubRoot, "typedpython").apply { mkdirs() }
        val runtimeDir = File(tpPkg, "runtime").apply { mkdirs() }
        File(runtimeDir, "tp_runtime.h").writeText("/* header */\n")
        File(tpPkg, "__init__.py").writeText("")
        File(tpPkg, "__main__.py").writeText(
            """
            import argparse, json, sys
            from pathlib import Path
            p = argparse.ArgumentParser()
            p.add_argument("cmd")
            p.add_argument("paths", nargs="+")
            p.add_argument("--root", required=True)
            p.add_argument("--cache", required=True)
            p.add_argument("--manifest", required=True)
            a = p.parse_args()
            cache = Path(a.cache).resolve()
            cache.mkdir(parents=True, exist_ok=True)
            c_file = cache / "fast.c"
            c_file.write_text("/* generated C */\n")
            runtime = (Path(__file__).resolve().parent / "runtime").as_posix()
            doc = {
                "format": "typedpython-emit-c",
                "version": 1,
                "compilerVersion": "stub-1",
                "projectRoot": Path(a.root).resolve().as_posix(),
                "runtimeInclude": runtime,
                "nativeModules": [{
                    "moduleName": "app.fast",
                    "file": "app/fast.py",
                    "sources": [c_file.as_posix()],
                    "includeDirs": [runtime],
                    "defines": {},
                    "status": "rebuilt",
                    "skippedItems": [],
                }],
                "notNativeModules": [],
            }
            mf = Path(a.manifest)
            mf.parent.mkdir(parents=True, exist_ok=True)
            mf.write_text(json.dumps(doc))
            """.trimIndent() + "\n",
        )

        val task = project.tasks.register("emitTypedPythonC", TypedPythonEmitCTask::class.java) {
            sources.from(pyFile)
            rootDirectory.set(pkgRoot)
            pythonExecutable.set("python3")
            pythonPath.from(fakeStubRoot)
            cacheDirectory.set(project.layout.buildDirectory.dir("typedpython/c-cache"))
            manifestFile.set(project.layout.buildDirectory.file("typedpython/emit-c-manifest.json"))
        }.get()

        task.emitC()

        val parsed = task.readManifest()
        assertEquals(1, parsed.nativeModules.size)
        val mapped = parsed.toExtensionModuleSources().single()
        assertEquals("app.fast", mapped.moduleName)
        assertTrue(mapped.sources.single().isFile)
        assertEquals("fast.c", mapped.sources.single().name)
    }

    @Test
    fun taskActionFailsWithDiagnosticsOnExit1Or2() {
        val project = ProjectBuilder.builder().build()
        val pkgRoot = project.file("pkg/src/main").apply { mkdirs() }
        val pyFile = File(pkgRoot, "app/broken.py").apply {
            parentFile.mkdirs()
            writeText("def f(x): return x\n")
        }
        val fakeStubRoot = project.file("fake-failing-tp").apply { mkdirs() }
        val tpPkg = File(fakeStubRoot, "typedpython").apply { mkdirs() }
        File(tpPkg, "__init__.py").writeText("")
        File(tpPkg, "__main__.py").writeText(
            """
            import sys
            print("typedpython: broken.py does not pass the gate in compiled mode", file=sys.stderr)
            sys.exit(1)
            """.trimIndent() + "\n",
        )

        val task = project.tasks.register("emitBroken", TypedPythonEmitCTask::class.java) {
            sources.from(pyFile)
            rootDirectory.set(pkgRoot)
            pythonExecutable.set("python3")
            pythonPath.from(fakeStubRoot)
            cacheDirectory.set(project.layout.buildDirectory.dir("typedpython/c-cache"))
            manifestFile.set(project.layout.buildDirectory.file("typedpython/emit-c-manifest.json"))
        }.get()

        val err = assertFailsWith<GradleException> { task.emitC() }
        assertTrue("exit 1" in err.message.orEmpty(), err.message)
        assertTrue("broken.py does not pass the gate" in err.message.orEmpty(), err.message)
    }
}
