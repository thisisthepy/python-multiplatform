package python.multiplatform.gradle

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Consumer-side iOS wiring (issue #90): what an app outside this repository needs to put CPython
 * into an iOS app, provided by the published plugin instead of the library's own build scripts.
 *
 * Everything lives in the **consumer's** `build/`: the archive in `build/python-standalone/`, the
 * framework in `build/xcode-frameworks/`, the per-slice standard library in `build/python-ios-home/`
 * and the Xcode script in `build/python-multiplatform/xcode/`. No home-directory or shared cache.
 */

/** Nested `ios { }` block of `pythonBindings { }`. */
interface PythonBindingsIosExtension {
    /**
     * Whether every Kotlin/Native iOS framework binary of this module gets
     * `-framework Python -F<build/xcode-frameworks/Python.xcframework/<slice>>` and its link task
     * depends on [PREPARE_XCFRAMEWORK_TASK]. Defaults to true. Turn it off for an app that links
     * `Python.framework` some other way.
     */
    val linkPythonFramework: Property<Boolean>
}

internal const val DOWNLOAD_IOS_TASK = "downloadPython_ios"
internal const val PREPARE_XCFRAMEWORK_TASK = "preparePythonXcframework"
internal const val STAGE_IOS_HOME_TASK = "stageIosPythonHome"
internal const val STAGE_IOS_HOME_FOR_XCODE_TASK = "stageIosPythonHomeForXcode"
internal const val INSTALL_XCODE_SCRIPT_TASK = "installPythonXcodeScript"

/** The default the shipped script carries; the install task replaces it with the consumer's task path. */
internal const val SCRIPT_LIBRARY_HOME_TASK = ":python-multiplatform:stageIosPythonHomeForXcode"

/** `ios_arm64` to `ios-arm64`, as the sample does; null for a konan target that is not iOS. */
internal fun xcframeworkSliceForKonanTarget(konanName: String): String? = when (konanName) {
    "ios_arm64" -> "ios-arm64"
    "ios_simulator_arm64", "ios_x64" -> "ios-arm64_x86_64-simulator"
    else -> null
}

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** `:app:stageIosPythonHomeForXcode`, and `:stageIosPythonHomeForXcode` for the root project. */
internal fun xcodeHomeTaskPath(projectPath: String): String =
    (if (projectPath == ":") "" else projectPath) + ":" + STAGE_IOS_HOME_FOR_XCODE_TASK

/**
 * Downloads the pinned CPython iOS support archive into `build/python-standalone/` of the consumer
 * and extracts it beside it.
 *
 * ### Trust
 *
 * The pinned SHA-256 ([expectedSha256], baked into the plugin at plugin build time from the root
 * `python-checksums.properties`) is the only trust anchor here. The library build additionally
 * checks python.org's sigstore bundle; that needs a sigstore client and network services a consumer
 * build should not need, so it is deliberately absent from this path. A mismatch fails the build.
 * A cached archive that does not match is deleted and fetched once more, and the second copy has to
 * verify too.
 */
abstract class DownloadPythonIosTask : DefaultTask() {

    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val archiveName: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    /** `build/python-standalone`: holds the archive and `extracted/ios/Python.xcframework`. */
    @get:OutputDirectory
    abstract val workDirectory: DirectoryProperty

    /** `<workDirectory>/extracted/ios`. */
    @get:org.gradle.api.tasks.Internal
    val extractDirectory: File get() = File(workDirectory.get().asFile, "extracted/ios")

    @TaskAction
    fun provision() {
        val work = workDirectory.get().asFile
        work.mkdirs()
        val archive = File(work, archiveName.get())
        val expected = expectedSha256.get().lowercase()

        if (archive.isFile && sha256Hex(archive) != expected) {
            logger.lifecycle("The cached ${archive.name} does not match its pinned SHA-256; deleting it and downloading once more.")
            archive.delete()
        }
        if (!archive.isFile) {
            fetch(archiveUrl.get(), archive)
            val actual = sha256Hex(archive)
            if (actual != expected) {
                archive.delete()
                throw GradleException(
                    "SHA-256 mismatch for ${archiveUrl.get()}: expected $expected, got $actual. The downloaded file was " +
                        "deleted. The expected value is pinned in this plugin; do not work around it, report it if the " +
                        "upstream archive was replaced.",
                )
            }
        }

        val extract = extractDirectory
        val stamp = File(extract, ".archive-stamp")
        val wanted = "$expected ${archive.name}"
        if (!(stamp.isFile && stamp.readText().trim() == wanted && File(extract, "Python.xcframework").isDirectory)) {
            deleteWithoutFollowingLinks(extract)
            extract.mkdirs()
            extractTarGzMaterialisingLinks(archive, extract)
            if (!File(extract, "Python.xcframework").isDirectory) {
                throw GradleException("${archive.name} was extracted into $extract but contains no Python.xcframework/.")
            }
            stamp.writeText(wanted + "\n")
        }
    }

    private fun fetch(url: String, target: File) {
        logger.lifecycle("Downloading $url")
        val part = File(target.parentFile, target.name + ".part")
        try {
            URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        } catch (e: java.io.IOException) {
            part.delete()
            throw GradleException("could not download $url: ${e.message}", e)
        }
        if (!part.renameTo(target)) throw GradleException("could not move $part to $target")
    }
}

/**
 * Copies the Xcode build phase script out of the plugin into the consumer's build directory and
 * points its default `PYTHON_HOME_TASK` at the consumer's own task.
 */
abstract class InstallPythonXcodeScriptTask : DefaultTask() {

    /** The task path baked in as the script's default, e.g. `:app:stageIosPythonHomeForXcode`. */
    @get:Input
    abstract val homeTaskPath: Property<String>

    @get:OutputFile
    abstract val script: org.gradle.api.file.RegularFileProperty

    @TaskAction
    fun install() {
        val text = InstallPythonXcodeScriptTask::class.java.getResourceAsStream("/xcode/install-python.sh")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw GradleException("the plugin jar carries no /xcode/install-python.sh")
        if (SCRIPT_LIBRARY_HOME_TASK !in text) {
            throw GradleException("install-python.sh no longer contains its default home task '$SCRIPT_LIBRARY_HOME_TASK'; update InstallPythonXcodeScriptTask.")
        }
        val target = script.get().asFile
        target.parentFile.mkdirs()
        target.writeText(text.replace(SCRIPT_LIBRARY_HOME_TASK, homeTaskPath.get()))
        target.setExecutable(true, false)
    }
}

private fun classChain(type: Class<*>): Sequence<Class<*>> = generateSequence(type) { it.superclass }

private fun invokeNoArg(target: Any, getter: String): Any? =
    target.javaClass.methods.firstOrNull { it.name == getter && it.parameterCount == 0 }
        ?.also { it.isAccessible = true }
        ?.invoke(target)

/** (target, konan target name) for every Kotlin/Native target of [project]; empty without the KMP plugin. */
internal fun nativeTargetsOf(project: Project): List<Pair<Any, String>> {
    val kotlin = project.extensions.findByName("kotlin") ?: return emptyList()
    val targets = invokeNoArg(kotlin, "getTargets") as? Iterable<*> ?: return emptyList()
    return targets.filterNotNull().mapNotNull { target ->
        val konan = invokeNoArg(target, "getKonanTarget") ?: return@mapNotNull null
        val name = invokeNoArg(konan, "getName") as? String ?: return@mapNotNull null
        target to name
    }
}

internal fun PythonBindingsPlugin.configureIosConsumer(project: Project, extension: PythonBindingsExtension) {
    val iosTargets = nativeTargetsOf(project).filter { xcframeworkSliceForKonanTarget(it.second) != null }
    if (iosTargets.isEmpty()) return

    val build = project.layout.buildDirectory

    val download = project.tasks.register(DOWNLOAD_IOS_TASK, DownloadPythonIosTask::class.java) {
        group = "python"
        description = "Downloads and verifies the pinned CPython iOS support archive into build/python-standalone (issue #90)"
        archiveUrl.convention(DEFAULT_IOS_ARCHIVE_URL)
        archiveName.convention(DEFAULT_IOS_ARCHIVE_NAME)
        expectedSha256.convention(DEFAULT_IOS_ARCHIVE_SHA256)
        workDirectory.convention(build.dir("python-standalone"))
    }
    val xcframeworkSource = download.flatMap { task -> task.workDirectory.dir("extracted/ios/Python.xcframework") }

    val prepare = project.tasks.register(PREPARE_XCFRAMEWORK_TASK, Sync::class.java) {
        group = "python"
        description = "Stages Python.xcframework into build/xcode-frameworks for the Xcode project and the linker (issue #90)"
        from(xcframeworkSource)
        into(build.dir("xcode-frameworks/Python.xcframework"))
    }

    if (extension.ios.linkPythonFramework.getOrElse(true)) {
        for ((target, konanName) in iosTargets) {
            val slice = xcframeworkSliceForKonanTarget(konanName)!!
            val frameworkDir = File(build.get().asFile, "xcode-frameworks/Python.xcframework/$slice").absolutePath
            val binaries = invokeNoArg(target, "getBinaries") as? Iterable<*> ?: continue
            for (binary in binaries.filterNotNull()) {
                if (classChain(binary.javaClass).none { it.simpleName == "Framework" }) continue
                @Suppress("UNCHECKED_CAST")
                val opts = invokeNoArg(binary, "getLinkerOpts") as? MutableList<String> ?: continue
                opts.addAll(listOf("-framework", "Python", "-F$frameworkDir"))
            }
        }
        project.tasks.matching {
            it.name.startsWith("link") && it.name.contains("Framework") && it.name.contains("Ios")
        }.configureEach { dependsOn(prepare) }
    }

    val homeRoot = build.dir("python-ios-home")
    val slices = IosPythonHomeLayout.slices.map { slice ->
        project.tasks.register(slice.stageTaskName, StageIosPythonHomeTask::class.java) {
            group = "python"
            description = "Stages CPython's standard library for an iOS app built for ${slice.name} (issue #90)"
            xcframework.set(xcframeworkSource)
            sliceName.set(slice.name)
            pythonVersion.set(DEFAULT_PYTHON_VERSION)
            destinationDir.set(homeRoot.map { it.dir(slice.name) })
        }
    }

    project.tasks.register(STAGE_IOS_HOME_TASK) {
        group = "python"
        description = "Stages CPython's standard library for every iOS app slice (issue #90)"
        dependsOn(slices)
    }

    project.tasks.register(STAGE_IOS_HOME_FOR_XCODE_TASK) {
        group = "python"
        description = "Stages the iOS app prefix for the slice Xcode is building and prints its path (issue #90)"
        val platformName = project.providers.environmentVariable("EFFECTIVE_PLATFORM_NAME")
        val archs = project.providers.environmentVariable("ARCHS")
        val selected = project.providers.provider { IosPythonHomeLayout.sliceFor(platformName.orNull, archs.orNull) }
        dependsOn(selected.map { project.tasks.named(it.stageTaskName) })
        val root = homeRoot.map { it.asFile.absolutePath }
        doLast {
            val slice = selected.get().name
            println("PYTHON_HOME_DIR=${root.get()}/$slice/${StageIosPythonHomeTask.PREFIX_DIRECTORY}")
            println("PYTHON_DYLIB_INFO_TEMPLATE=${root.get()}/$slice/${StageIosPythonHomeTask.TEMPLATE_NAME}")
        }
    }

    project.tasks.register(INSTALL_XCODE_SCRIPT_TASK, InstallPythonXcodeScriptTask::class.java) {
        group = "python"
        description = "Copies install-python.sh to build/python-multiplatform/xcode for the Xcode Run Script phase (issue #90)"
        homeTaskPath.set(xcodeHomeTaskPath(project.path))
        script.set(build.file("python-multiplatform/xcode/install-python.sh"))
    }
}
