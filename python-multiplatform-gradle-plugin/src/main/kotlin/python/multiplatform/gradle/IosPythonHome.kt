package python.multiplatform.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What an iOS app bundle carries of CPython's standard library, and where it comes from -- SPEC L-10,
 * issue #59.
 *
 * `Python.xcframework` ships the interpreter and headers and nothing else (`iosMain/README.md`); the
 * standard library sits *beside* the framework slices in the same archive:
 *
 * ```
 * Python.xcframework/
 *   lib/python3.14/                              pure-Python stdlib, shared by every slice
 *   ios-arm64/lib-arm64/python3.14/              lib-dynload/*.so, _sysconfigdata (device)
 *   ios-arm64_x86_64-simulator/lib-arm64/...     the same for the arm64 simulator
 *   ios-arm64_x86_64-simulator/lib-x86_64/...    ...and the x86_64 simulator
 *   build/iOS-dylib-Info-template.plist          Info.plist for an extension-module framework
 *   build/utils.sh                               upstream's own Xcode helper (install_python)
 * ```
 *
 * `build/utils.sh`'s `install_stdlib` merges the shared tree with one `lib-$ARCHS` tree into the app;
 * this does the same merge at Gradle time, into a directory per slice, so the Xcode phase
 * (`tools/xcode/install-python.sh`) copies a finished prefix and needs no path into the archive.
 * Pure functions are separated out so the layout is pinned by tests without downloading anything.
 */
object IosPythonHomeLayout {

    /**
     * The directory inside the app bundle the prefix is copied to, and that `IosPythonHome` in the
     * library's `iosMain` looks for under `NSBundle.mainBundle.resourcePath`. The same name desktop
     * packages under ([PACKAGED_HOME_DIRECTORY]); deliberately *not* upstream's `python/`, because
     * `python/` is the consumer's payload root (`PythonPayload.PAYLOAD_ROOT`, SPEC L-6) and the
     * payload phase `rsync --delete`s it.
     */
    const val BUNDLE_DIRECTORY: String = PACKAGED_HOME_DIRECTORY

    /** The Info.plist template for an extension-module framework, relative to the xcframework. */
    const val DYLIB_INFO_TEMPLATE: String = "build/iOS-dylib-Info-template.plist"

    /**
     * Top-level stdlib packages an iOS app never ships.
     *
     * Measured on 3.14 (`Python.xcframework/lib/python3.14`, 51 MB, 2,407 files): `test` alone is
     * 36 MB and 1,641 files -- CPython's own regression suite. `tkinter`, `idlelib` and `turtledemo`
     * need `_tkinter`, which no iOS slice builds (no `lib-dynload/_tkinter*`). `ensurepip` installs
     * pip, which cannot run inside an app sandbox. Everything else is kept, as on desktop (L-9).
     */
    val EXCLUDED_TOP_LEVEL_PACKAGES: Set<String> = setOf("test", "idlelib", "tkinter", "turtledemo", "ensurepip")

    /** One xcframework slice + architecture: what Xcode builds for in one Run Script invocation. */
    data class Slice(
        /** Xcode's platform name without the dash: `iphoneos`, `iphonesimulator`. */
        val sdk: String,
        /** One `ARCHS` value: `arm64`, `x86_64`. */
        val arch: String,
        /** The xcframework slice directory holding `lib-<arch>/`. */
        val xcframeworkSlice: String,
    ) {
        /** `iphonesimulator-arm64`: the staging directory name. */
        val name: String get() = "$sdk-$arch"

        /** `stageIosPythonHome_iphonesimulator_arm64`: the library build's task for this slice. */
        val stageTaskName: String get() = "stageIosPythonHome_" + name.replace('-', '_')
    }

    /**
     * Every slice this library stages. `iosArm64` is a device; `iosSimulatorArm64` and `iosX64`
     * both live in the fat simulator slice, with separate `lib-<arch>` trees -- which is why `iosX64`
     * needs its own entry rather than the hard-coded `lib-arm64` ROADMAP §13.4 records for the test
     * task.
     */
    val slices: List<Slice> = listOf(
        Slice("iphoneos", "arm64", "ios-arm64"),
        Slice("iphonesimulator", "arm64", "ios-arm64_x86_64-simulator"),
        Slice("iphonesimulator", "x86_64", "ios-arm64_x86_64-simulator"),
    )

    /**
     * The slice an Xcode build phase is building for, from the two settings Xcode exports to it.
     *
     * Exactly one architecture: `ARCHS="arm64 x86_64"` (a universal simulator build, which Xcode
     * does for Release unless `ONLY_ACTIVE_ARCH=YES`) would need two `lib-dynload` trees in one
     * prefix, and CPython looks in one. Upstream's `install_stdlib` (`lib-$ARCHS`) has the same
     * limit, silently; this says so.
     */
    fun sliceFor(effectivePlatformName: String?, archs: String?): Slice {
        val platform = effectivePlatformName?.trim()?.removePrefix("-")
        require(!platform.isNullOrEmpty()) {
            "EFFECTIVE_PLATFORM_NAME is not set. This task picks the iOS slice from the settings Xcode " +
                "exports to a Run Script phase; run it from one (tools/xcode/install-python.sh does)."
        }
        val archList = archs?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        require(archList.isNotEmpty()) { "ARCHS is not set; run this from an Xcode Run Script phase." }
        require(archList.size == 1) {
            "ARCHS='${archs!!.trim()}' names ${archList.size} architectures, and one app prefix can hold " +
                "one lib-dynload. Build one architecture at a time (ONLY_ACTIVE_ARCH=YES, or ARCHS=arm64)."
        }
        val arch = archList.single()
        return slices.firstOrNull { it.sdk == platform && it.arch == arch }
            ?: throw IllegalArgumentException(
                "no CPython iOS slice for platform '$platform' and architecture '$arch'; this library " +
                    "stages ${slices.joinToString { it.name }}."
            )
    }

    /** `3.14` from `3.14.7`. */
    fun stdlibTag(pythonVersion: String): String {
        val parts = pythonVersion.split('.')
        require(parts.size >= 2) { "python version '$pythonVersion' has no major.minor" }
        return "${parts[0]}.${parts[1]}"
    }

    /**
     * The trees merged into `lib/python<X.Y>/` for [slice], relative to the xcframework, shared tree
     * first: the slice tree only adds files (`lib-dynload/`, `_sysconfigdata__ios_*`), but if it ever
     * overlapped, the slice-specific file is the one that must win, as in `install_stdlib`.
     */
    fun sourceDirectories(slice: Slice, pythonVersion: String): List<String> {
        val tag = stdlibTag(pythonVersion)
        return listOf("lib/python$tag", "${slice.xcframeworkSlice}/lib-${slice.arch}/python$tag")
    }

    /**
     * Whether a file or directory at [stdlibRelativePath] -- relative to `lib/python<X.Y>/`, `/`
     * separated -- goes into the app.
     *
     * Not copied: any `__pycache__` (bytecode from whatever ran against the extracted tree, so the
     * app would depend on the build machine's history), the [EXCLUDED_TOP_LEVEL_PACKAGES], and any
     * `libpython*.dylib` (upstream excludes it too: the interpreter arrives as `Python.framework`,
     * and a loose dylib in an app bundle is rejected by App Store validation).
     */
    fun isStdlibPathStaged(stdlibRelativePath: String): Boolean {
        val segments = stdlibRelativePath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true
        if (segments.first() in EXCLUDED_TOP_LEVEL_PACKAGES) return false
        if (segments.any { it == "__pycache__" }) return false
        val name = segments.last()
        if (name.startsWith("libpython") && name.endsWith(".dylib")) return false
        return true
    }

    /**
     * Every file the prefix for [slice] contains, as destination path (relative to the prefix) to
     * source file. A later source overrides an earlier one at the same destination
     * ([sourceDirectories]' order). Fails when a source tree is missing, rather than staging half a
     * prefix that would abort `Py_Initialize()` on the device.
     */
    fun plan(xcframework: File, slice: Slice, pythonVersion: String): Map<String, File> {
        val tag = stdlibTag(pythonVersion)
        val result = sortedMapOf<String, File>()
        for (relative in sourceDirectories(slice, pythonVersion)) {
            val root = File(xcframework, relative)
            if (!root.isDirectory) {
                throw GradleException(
                    "$root does not exist, so the ${slice.name} prefix would have no " +
                        (if (relative.startsWith("lib/")) "standard library" else "lib-dynload") +
                        ". The Python.xcframework layout may have changed; see IosPythonHomeLayout's KDoc."
                )
            }
            root.walkTopDown()
                .onEnter { dir -> dir == root || isStdlibPathStaged(dir.relativeTo(root).invariantSeparatorsPath) }
                .filter { it.isFile }
                .forEach { file ->
                    val inStdlib = file.relativeTo(root).invariantSeparatorsPath
                    if (isStdlibPathStaged(inStdlib)) result["lib/python$tag/$inStdlib"] = file
                }
        }
        return result
    }
}

/**
 * Stages one slice's prefix for an iOS app bundle (SPEC L-10): `<destination>/home/lib/python<X.Y>/`
 * as [IosPythonHomeLayout.plan] lists it, plus `<destination>/dylib-Info-template.plist` *beside*
 * the prefix -- it is an input to the Xcode phase, not part of what the app carries.
 *
 * A plain copy rather than a `Sync` so that what [IosPythonHomeLayout.plan] returns -- which the
 * tests pin -- is exactly what is copied, with no second notion of "excluded" in a copy spec.
 */
abstract class StageIosPythonHomeTask : DefaultTask() {

    /** `.../extracted/<version>/ios/Python.xcframework`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val xcframework: DirectoryProperty

    /** One of [IosPythonHomeLayout.slices]' names, e.g. `iphonesimulator-arm64`. */
    @get:Input
    abstract val sliceName: Property<String>

    @get:Input
    abstract val pythonVersion: Property<String>

    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    @TaskAction
    fun stage() {
        val slice = IosPythonHomeLayout.slices.firstOrNull { it.name == sliceName.get() }
            ?: throw GradleException("unknown iOS slice '${sliceName.get()}'")
        val framework = xcframework.get().asFile
        val version = pythonVersion.get()
        val plan = IosPythonHomeLayout.plan(framework, slice, version)

        val root = destinationDir.get().asFile
        val home = File(root, PREFIX_DIRECTORY)
        // Whole directory replaced: a file dropped from the plan (an exclusion added, an upstream
        // module removed) must not survive from the previous run into the next app.
        home.deleteRecursively()
        for ((relative, source) in plan) {
            val target = File(home, relative)
            target.parentFile.mkdirs()
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }

        val template = File(framework, IosPythonHomeLayout.DYLIB_INFO_TEMPLATE)
        if (!template.isFile) {
            throw GradleException("$template is missing; the Xcode phase needs it to wrap extension modules as frameworks.")
        }
        Files.copy(template.toPath(), File(root, TEMPLATE_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING)

        // The marker PythonHomeCheck probes on the device, checked here where it is cheap to fail.
        val marker = File(home, "lib/python${IosPythonHomeLayout.stdlibTag(version)}/os.py")
        if (!marker.isFile) {
            throw GradleException("staged ${slice.name} into $home but $marker is missing; the app would abort at Py_Initialize().")
        }
        logger.info("Staged {} files of CPython {} for {} into {}", plan.size, version, slice.name, home)
    }

    companion object {
        /** The prefix inside [destinationDir]; what the Xcode phase copies into the app. */
        const val PREFIX_DIRECTORY: String = "home"

        /** The framework Info.plist template inside [destinationDir]. */
        const val TEMPLATE_NAME: String = "dylib-Info-template.plist"
    }
}
