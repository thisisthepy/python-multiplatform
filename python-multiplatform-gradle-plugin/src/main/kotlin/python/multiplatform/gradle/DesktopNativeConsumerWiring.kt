package python.multiplatform.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * SPEC L-9c (#191): a consumer's Kotlin/Native desktop targets (`macosArm64`, `linuxX64`, `mingwX64`) link the
 * free-threaded `libpython` and ship it, with the standard library, beside the executable.
 *
 * Source: leader decision 2026-10-25 on #191, item 3: "K/N CPython: libpython is shipped beside the
 * executable (dynamic)". The run-time half, finding `python-multiplatform-home` beside the executable,
 * is `DesktopNativePythonHome` in the library's `desktopNativeMain`.
 *
 * Reaches KGP by reflection, like [configureIosConsumer]: this plugin compiles against Gradle's
 * embedded Kotlin and carries no KGP types.
 */

/** python-build-standalone's platform for a Kotlin/Native desktop konan target, or `null` for any other target. */
internal fun desktopNativePlatformFor(konanName: String): String? = when (konanName) {
    "macos_arm64" -> "macos-aarch64"
    "linux_x64" -> "linux-x86_64"
    "mingw_x64" -> "windows-x86_64"
    else -> null
}

/** The `-l` name of the interpreter library: `python3.15t` for the free-threaded build. */
internal fun desktopNativeLibPythonName(pythonVersion: String, freeThreaded: Boolean): String {
    val parts = pythonVersion.split('.')
    require(parts.size >= 2) { "python version '$pythonVersion' has no major.minor" }
    return "python${parts[0]}.${parts[1]}" + if (freeThreaded) "t" else ""
}

/**
 * The linker options for one binary: link the staged `libpython`, and search for it at run time in
 * `python-multiplatform-home/lib` beside the executable. A debug or test binary also searches the
 * staged prefix itself, so it runs from the build directory; a release binary never carries that
 * build path.
 */
internal fun desktopNativeLinkerOpts(konanName: String, prefix: String, libName: String, debug: Boolean): List<String> {
    // Windows (SPEC P-1a): the MSVC import library, which lld links in MinGW mode. There is no
    // run-time search path to set: the loader looks beside the executable, where packaging puts the DLL.
    if (konanName.startsWith("mingw")) return listOf("$prefix/libs/${libName.replace(".", "")}.lib")
    val origin = if (konanName.startsWith("macos")) "@executable_path" else "\$ORIGIN"
    return buildList {
        add("-L$prefix/lib")
        add("-l$libName")
        add("-Wl,-rpath,$origin/$PACKAGED_HOME_DIRECTORY/lib")
        if (debug) add("-Wl,-rpath,$prefix/lib")
    }
}

/** Whether a binary of this KGP class (simple name) is linked into something that loads libpython. */
internal fun isLinkedDesktopNativeBinary(binaryClassName: String): Boolean =
    binaryClassName in setOf("Executable", "TestExecutable", "SharedLibrary")

/** What of the prefix goes beside the executable itself: on Windows the DLLs (the loader's own search), elsewhere nothing. */
internal fun desktopNativeExecutableDirIncludes(windows: Boolean): List<String> =
    if (windows) listOf("*.dll") else emptyList()

/** What of the prefix goes into `python-multiplatform-home`: L-9's set, without Windows' DLLs (they sit beside the executable). */
internal fun desktopNativeHomeIncludes(pythonVersion: String, freeThreaded: Boolean, windows: Boolean): List<String> =
    if (windows) listOf("Lib/**", "DLLs/**") else packagedPrefixIncludes(pythonVersion, freeThreaded, windows = false)

internal fun desktopNativeStageTaskName(targetName: String): String =
    "stagePythonHome" + targetName.replaceFirstChar { it.uppercase() }

internal fun PythonBindingsPlugin.configureDesktopNativeConsumer(project: Project, extension: PythonBindingsExtension) {
    val targets = nativeTargetsOf(project).mapNotNull { (target, konan) ->
        desktopNativePlatformFor(konan)?.let { Triple(target, konan, it) }
    }
    if (targets.isEmpty()) return

    val version = extension.pythonVersion.getOrElse(DEFAULT_PYTHON_VERSION)
    val release = extension.pythonBuildStandaloneRelease.getOrElse(DEFAULT_PBS_RELEASE)
    val freeThreaded = extension.pythonFreeThreaded.getOrElse(DEFAULT_PYTHON_FREE_THREADED)
    val libName = desktopNativeLibPythonName(version, freeThreaded)
    val cacheRoot = File(project.gradle.gradleUserHomeDir, PythonHomeStaging.CACHE_DIRECTORY)
    val flavour = if (freeThreaded) "-freethreaded" else ""
    val environmentHome = project.providers.environmentVariable("PYTHONHOME").orNull
    val packaging = project.provider { extension.packagePythonHome.getOrElse(true) }

    for ((target, konan, platform) in targets) {
        val windows = platform.startsWith("windows")
        val targetName = invokeNoArg(target, "getName") as? String ?: continue
        val destination = File(cacheRoot, "$version+$release$flavour/$platform")
        val prefix = File(destination, "python")
        val stage: TaskProvider<StagePythonHomeTask> =
            project.tasks.register(desktopNativeStageTaskName(targetName), StagePythonHomeTask::class.java) {
                group = "python"
                description = "Downloads and unpacks the CPython prefix $targetName binaries link and ship (SPEC L-9c)."
                pythonVersion.set(version)
                pbsRelease.set(release)
                this.platform.set(platform)
                this.freeThreaded.set(freeThreaded)
                destinationDir.set(destination)
                downloadDir.set(File(cacheRoot, "archives"))
            }

        val binaries = invokeNoArg(target, "getBinaries") as? Iterable<*> ?: continue
        for (binary in binaries.filterNotNull()) {
            val kind = classChain(binary.javaClass).map { it.simpleName }.firstOrNull { isLinkedDesktopNativeBinary(it) }
                ?: continue
            val buildType = invokeNoArg(binary, "getBuildType")?.toString()
            val debug = kind == "TestExecutable" || buildType == "DEBUG"
            @Suppress("UNCHECKED_CAST")
            val opts = invokeNoArg(binary, "getLinkerOpts") as? MutableList<String> ?: continue
            opts.addAll(desktopNativeLinkerOpts(konan, prefix.absolutePath, libName, debug))

            @Suppress("UNCHECKED_CAST")
            val link = invokeNoArg(binary, "getLinkTaskProvider") as? TaskProvider<Task> ?: continue
            link.configure {
                dependsOn(stage)
                if (kind == "Executable") {
                    val outputDir = invokeNoArg(binary, "getOutputDirectory") as? File
                    if (outputDir != null) {
                        doLast {
                            if (!packaging.get()) return@doLast
                            val home = File(outputDir, PACKAGED_HOME_DIRECTORY)
                            project.delete(home)
                            project.copy {
                                from(prefix) {
                                    include(desktopNativeHomeIncludes(version, freeThreaded, windows))
                                    exclude(PACKAGED_PREFIX_EXCLUDES)
                                }
                                into(home)
                            }
                            val besideExecutable = desktopNativeExecutableDirIncludes(windows)
                            if (besideExecutable.isNotEmpty()) {
                                project.copy {
                                    from(prefix) { include(besideExecutable) }
                                    into(outputDir)
                                }
                            }
                            requireNoEmptyLibraries(home)
                        }
                    }
                }
            }
        }

        // The host test task (`macosArm64Test`, `linuxX64Test`) runs the test executable in place.
        if (shouldStagePythonHome(environmentHome, extension.stagePythonHome.getOrElse(true))) {
            project.tasks.matching { it.name == "${targetName}Test" }.configureEach {
                dependsOn(stage)
                val environment = javaClass.methods.firstOrNull {
                    it.name == "environment" && it.parameterCount == 2
                } ?: return@configureEach
                environment.invoke(this, "PYTHONHOME", prefix.absolutePath)
                // Windows finds the DLL a test executable imports on PATH (the executable sits in the build tree).
                if (windows) {
                    val path = project.providers.environmentVariable("PATH").orNull.orEmpty()
                    environment.invoke(this, "PATH", prefix.absolutePath + File.pathSeparator + path)
                }
            }
        }
    }
}
