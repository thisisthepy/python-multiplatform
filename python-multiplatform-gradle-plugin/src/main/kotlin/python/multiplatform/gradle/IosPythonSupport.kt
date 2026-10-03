package python.multiplatform.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.DomainObjectCollection
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Which iOS support archive carries `Python.xcframework` for a CPython version, where it comes from,
 * and which lockfile entry pins it -- issue #90.
 *
 * The library build (`python-multiplatform/build.gradle.kts`'s `downloadPython_ios`) and a consumer's
 * [AcquireIosPythonSupportTask] both use this, so the two cannot name different archives. The pinned
 * SHA-256 itself is not restated here: [pinnedSha256] reads `PINNED_IOS_SUPPORT_SHA256`, which this
 * plugin's build generates from the root `python-checksums.properties` -- the same file the library
 * build verifies against.
 *
 * python.org publishes an iOS XCframework from 3.15 on; BeeWare's Python-Apple-support stops at
 * `3.14-b11`. The two do not overlap, so this is a switch on the version, not a preference (see the
 * library build's KDoc on `iosFromPythonOrg`).
 */
object IosSupportArchive {

    /** Where every project on this machine shares one extracted archive, under the Gradle user home. */
    const val CACHE_DIRECTORY: String = "python-multiplatform/ios-support"

    /** Written after the last extracted byte; see [stamp]. */
    const val STAMP_NAME: String = ".python-multiplatform-ios-support"

    /** One archive: what to download, from where, and the lockfile key its SHA-256 is pinned under. */
    data class Pin(
        val archiveName: String,
        val url: String,
        val lockKey: String,
        val fromPythonOrg: Boolean,
    )

    fun isFromPythonOrg(pythonVersion: String): Boolean {
        val parts = pythonVersion.split('.')
        require(parts.size >= 2) { "python version '$pythonVersion' has no major.minor" }
        val major = parts[0].toInt()
        val minor = parts[1].takeWhile { it.isDigit() }.toInt()
        return major > 3 || (major == 3 && minor >= 15)
    }

    /** `3.15.0` for `3.15.0rc1`: python.org files a pre-release under its final version's directory. */
    fun pythonOrgReleaseDir(pythonVersion: String): String {
        val parts = pythonVersion.split('.')
        val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.ifEmpty { null } ?: "0"
        return "${parts[0]}.${parts[1]}.$patch"
    }

    fun forVersion(pythonVersion: String, appleSupportBuild: String): Pin {
        val tag = IosPythonHomeLayout.stdlibTag(pythonVersion)
        return if (isFromPythonOrg(pythonVersion)) {
            val name = "python-$pythonVersion-iOS-XCframework.tar.gz"
            Pin(
                archiveName = name,
                url = "https://www.python.org/ftp/python/${pythonOrgReleaseDir(pythonVersion)}/$name",
                lockKey = "ios-$pythonVersion-pythonorg",
                fromPythonOrg = true,
            )
        } else {
            val name = "Python-$tag-iOS-support.$appleSupportBuild.tar.gz"
            Pin(
                archiveName = name,
                url = "https://github.com/beeware/Python-Apple-support/releases/download/$tag-$appleSupportBuild/$name",
                lockKey = "ios-$tag-$appleSupportBuild",
                fromPythonOrg = false,
            )
        }
    }

    /** The SHA-256 the root lockfile pins for [lockKey], or null when it pins none. */
    fun pinnedSha256(lockKey: String): String? = PINNED_IOS_SUPPORT_SHA256[lockKey]

    /** Every pinned `ios-*` lock key; for diagnostics. */
    val pinnedLockKeys: Set<String> get() = PINNED_IOS_SUPPORT_SHA256.keys

    /**
     * Identifies exactly which archive an extracted tree came from. Written last and deleted first,
     * as `stagingStamp` is for desktop, so it is never true of a partial tree; it carries the digest
     * so a re-pinned archive under the same name is extracted again.
     */
    fun stamp(pin: Pin, sha256: String): String =
        "python-multiplatform ios ${pin.lockKey} ${pin.archiveName} $sha256 links-materialised"

    /**
     * Only `Python.xcframework/` is extracted. BeeWare's archive also carries `testbed/` -- CPython's
     * own Xcode test project -- whose `testbed/Python.xcframework` is a link back to the framework and
     * would be materialised as a second full copy.
     */
    fun isXcframeworkEntry(archivePath: String): Boolean {
        val path = archivePath.removePrefix("./")
        return path == IosPythonXcframework.NAME || path.startsWith(IosPythonXcframework.NAME + "/")
    }

    /**
     * Each `patchlevel.h` under [xcframework] whose `PY_VERSION` is not [pythonVersion], described
     * (issue #47: iOS once shipped 3.14.6 under a 3.14.7 pin). Empty when every one matches; a tree
     * with no `patchlevel.h` at all is reported too, because then nothing was checked.
     */
    fun headerVersionMismatches(xcframework: File, pythonVersion: String): List<String> {
        val headers = xcframework.walkTopDown().filter { it.isFile && it.name == "patchlevel.h" }.toList()
        if (headers.isEmpty()) return listOf("no patchlevel.h under $xcframework, so the CPython version cannot be checked")
        return headers.mapNotNull { header ->
            val actual = AcquiredHeaderVersion.parse(header.readText())
            if (actual == pythonVersion) null else "$header says PY_VERSION $actual, expected $pythonVersion"
        }
    }
}

/**
 * Where a consumer's build keeps `Python.xcframework` and how its Kotlin/Native iOS frameworks link
 * against it -- issue #90.
 *
 * `build/xcode-frameworks/Python.xcframework` is the path `:sample`'s Xcode project already
 * references, and the directory KGP's `embedAndSignAppleFrameworkForXcode` syncs the Kotlin framework
 * into (`build/xcode-frameworks/<configuration>/<sdk>`); a separate subdirectory, so neither sync
 * deletes the other's output.
 */
object IosPythonXcframework {

    const val NAME: String = "Python.xcframework"

    /** Relative to the consumer project's build directory. */
    const val STAGED_DIRECTORY: String = "xcode-frameworks"

    /**
     * The xcframework slice a Kotlin/Native target links against, from `KonanTarget.name`, or null
     * for a target that is not iOS. `iosX64` and `iosSimulatorArm64` share the fat simulator slice.
     */
    fun sliceForKonanTarget(konanTargetName: String): String? = when (konanTargetName) {
        "ios_arm64" -> "ios-arm64"
        "ios_simulator_arm64", "ios_x64" -> "ios-arm64_x86_64-simulator"
        else -> null
    }

    /** What every Kotlin/Native iOS framework binary of a consumer gets appended to `linkerOpts`. */
    fun linkerOpts(stagedXcframework: File, slice: String): List<String> =
        listOf("-framework", "Python", "-F" + File(stagedXcframework, slice).absolutePath)

    /** `org.jetbrains.kotlin.gradle.plugin.mpp.Framework`, matched by name: this plugin carries no KGP types. */
    internal const val KOTLIN_FRAMEWORK_CLASS: String = "org.jetbrains.kotlin.gradle.plugin.mpp.Framework"

    /** Whether [binary] is (a Gradle-decorated subclass of) KGP's `Framework`. */
    internal fun isKotlinFramework(binary: Any): Boolean {
        var type: Class<*>? = binary.javaClass
        while (type != null) {
            if (type.name == KOTLIN_FRAMEWORK_CLASS) return true
            type = type.superclass
        }
        return false
    }

    /**
     * Appends [linkerOpts] to every Kotlin/Native iOS `Framework` binary declared on [kotlin] (the
     * `kotlin` extension), now or later, and makes its link task depend on [linkDependencies].
     *
     * Reflective for the reason `addKotlinSourceDirectory` is: this plugin compiles without the Kotlin
     * Gradle Plugin. The chain is `getTargets()` (a `DomainObjectCollection`), each target's
     * `getKonanTarget().getName()`, `getBinaries()` (a `DomainObjectSet<NativeBinary>`), and on each
     * binary `getLinkerOpts()` (a `MutableList<String>`) and `getLinkTaskName()`. Both collections are
     * walked with `configureEach`, so targets and binaries declared after this runs are covered. A
     * chain that breaks on an iOS target warns -- the link would then fail with "framework 'Python'
     * not found", and the warning says why.
     */
    internal fun wireFrameworks(
        project: Project,
        kotlin: Any,
        stagedXcframework: File,
        linkDependencies: List<Any>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val targets = getter(kotlin, "getTargets") as? DomainObjectCollection<Any>
        if (targets == null) {
            project.logger.warn("python-multiplatform: the kotlin extension has no targets collection; Python.xcframework is not linked")
            return
        }
        targets.configureEach { wireTarget(project, this, stagedXcframework, linkDependencies) }
    }

    private fun wireTarget(project: Project, target: Any, stagedXcframework: File, linkDependencies: List<Any>) {
        val konanTarget = getter(target, "getKonanTarget") ?: return // not a Kotlin/Native target
        val konanName = getter(konanTarget, "getName") as? String ?: return
        val slice = sliceForKonanTarget(konanName) ?: return
        @Suppress("UNCHECKED_CAST")
        val binaries = getter(target, "getBinaries") as? DomainObjectCollection<Any>
        if (binaries == null) {
            project.logger.warn("python-multiplatform: iOS target $konanName has no binaries collection; Python.xcframework is not linked")
            return
        }
        val options = linkerOpts(stagedXcframework, slice)
        binaries.configureEach {
            if (!isKotlinFramework(this)) return@configureEach
            @Suppress("UNCHECKED_CAST")
            val opts = getter(this, "getLinkerOpts") as? MutableList<String>
            if (opts == null) {
                project.logger.warn("python-multiplatform: a $konanName framework has no linkerOpts list; Python.xcframework is not linked")
                return@configureEach
            }
            opts.addAll(options)
            val linkTask = getter(this, "getLinkTaskName") as? String
            if (linkTask != null) {
                project.tasks.matching { it.name == linkTask }.configureEach { dependsOn(linkDependencies) }
            }
        }
    }

    private fun getter(receiver: Any, name: String): Any? =
        receiver.javaClass.methods
            .firstOrNull { it.name == name && it.parameterCount == 0 }
            ?.also { it.isAccessible = true }
            ?.invoke(receiver)
}

/**
 * Downloads, verifies against the pinned SHA-256 and extracts the iOS support archive's
 * `Python.xcframework` into a per-machine cache under the Gradle user home, then stamps it -- the iOS
 * counterpart of [StagePythonHomeTask], issue #90.
 *
 * The archive is only ever accepted when its digest equals the root lockfile's pin
 * ([IosSupportArchive.pinnedSha256]); BeeWare publishes no checksums or signatures, so that pin is the
 * only instrument there is (the library build's `downloadPython_ios` comment). A version with no pin
 * fails before anything is downloaded.
 */
abstract class AcquireIosPythonSupportTask : DefaultTask() {

    @get:Input
    abstract val pythonVersion: Property<String>

    @get:Input
    abstract val archiveName: Property<String>

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val lockKey: Property<String>

    /** Empty when the lockfile pins nothing for [lockKey]; the action then fails with the key. */
    @get:Input
    abstract val sha256: Property<String>

    /** Holds `Python.xcframework/` and the stamp. */
    @get:OutputDirectory
    abstract val destinationDir: DirectoryProperty

    @get:Internal
    abstract val downloadDir: DirectoryProperty

    @TaskAction
    fun acquire() {
        val pin = IosSupportArchive.Pin(archiveName.get(), url.get(), lockKey.get(), fromPythonOrg = false)
        val expectedSha = sha256.get().ifEmpty {
            throw GradleException(
                "python-checksums.properties pins no iOS support archive under '${pin.lockKey}' " +
                    "(pinned: ${IosSupportArchive.pinnedLockKeys.sorted().joinToString()}). This plugin only stages " +
                    "an archive whose SHA-256 the library build pinned; use the pythonVersion/pythonAppleSupportBuild " +
                    "this plugin was published with.",
            )
        }
        val version = pythonVersion.get()
        val root = destinationDir.get().asFile
        val stamp = File(root, IosSupportArchive.STAMP_NAME)
        val expected = IosSupportArchive.stamp(pin, expectedSha)
        val xcframework = File(root, IosPythonXcframework.NAME)
        val marker = File(xcframework, "Info.plist")

        if (stamp.isFile && stamp.readText() == expected && marker.isFile) {
            didWork = false
            logger.info("Python.xcframework ({}) already staged at {}", pin.lockKey, xcframework)
            return
        }

        val archive = File(downloadDir.get().asFile, pin.archiveName)
        obtain(pin, archive, expectedSha)

        stamp.delete()
        root.deleteRecursively()
        if (!root.mkdirs() && !root.isDirectory) throw GradleException("could not create $root")

        logger.lifecycle("Extracting ${IosPythonXcframework.NAME} from ${pin.archiveName} into $root")
        extractTarGzMaterialisingLinks(archive, root, IosSupportArchive::isXcframeworkEntry)

        if (!marker.isFile) {
            throw GradleException("extracted ${pin.archiveName} into $root but $marker is missing; the archive layout may have changed upstream.")
        }
        val mismatches = IosSupportArchive.headerVersionMismatches(xcframework, version)
        if (mismatches.isNotEmpty()) {
            throw GradleException("${pin.archiveName} does not carry CPython $version:\n  " + mismatches.joinToString("\n  "))
        }
        stamp.writeText(expected)
    }

    private fun obtain(pin: IosSupportArchive.Pin, archive: File, expectedSha: String) {
        if (archive.isFile) {
            if (sha256Hex(archive) == expectedSha) return
            logger.lifecycle("Cached ${archive.name} does not match its pinned checksum; downloading again")
            archive.delete()
        }
        archive.parentFile.mkdirs()
        val partial = File(archive.parentFile, "${archive.name}.part")
        partial.delete()
        logger.lifecycle("Downloading ${pin.url}")
        URI(pin.url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = sha256Hex(partial)
        if (actual != expectedSha) {
            partial.delete()
            throw GradleException(
                "checksum mismatch for ${pin.archiveName} (lock key ${pin.lockKey}): expected $expectedSha, got $actual",
            )
        }
        // Renamed only once verified, so an interrupted or tampered download never looks complete.
        if (!partial.renameTo(archive)) throw GradleException("could not move $partial into place at $archive")
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * The tasks that stage an iOS app's CPython prefix (SPEC L-11), registered the same way on the
 * library build and on a consumer that applies this plugin -- issue #90. Only where the
 * `Python.xcframework` comes from differs: the library's own extraction, or a consumer's
 * [AcquireIosPythonSupportTask] cache.
 */
object IosPythonHomeTasks {

    const val ALL_SLICES_TASK: String = "stageIosPythonHome"
    const val FOR_XCODE_TASK: String = "stageIosPythonHomeForXcode"

    /** Relative to the project's build directory. */
    const val STAGING_DIRECTORY: String = "python-ios-home"

    /**
     * The two lines `stageIosPythonHomeForXcode` prints for [slice] under [root] -- the contract
     * `tools/xcode/install-python.sh` parses.
     */
    fun xcodeOutputLines(root: File, slice: IosPythonHomeLayout.Slice): List<String> {
        val dir = File(root, slice.name)
        return listOf(
            "PYTHON_HOME_DIR=" + File(dir, StageIosPythonHomeTask.PREFIX_DIRECTORY).absolutePath,
            "PYTHON_DYLIB_INFO_TEMPLATE=" + File(dir, StageIosPythonHomeTask.TEMPLATE_NAME).absolutePath,
        )
    }

    /**
     * Registers `stageIosPythonHome_<slice>` per [IosPythonHomeLayout.slices], `stageIosPythonHome`
     * over all of them, and `stageIosPythonHomeForXcode`, which picks one slice from Xcode's
     * `EFFECTIVE_PLATFORM_NAME` and `ARCHS`, stages it and prints [xcodeOutputLines]. Run outside Xcode
     * or for a universal `ARCHS`, it fails while the task graph is built, with the reason.
     *
     * @param after what must run before any slice is staged (the task that produces [xcframework]).
     */
    fun register(
        project: Project,
        xcframework: Provider<Directory>,
        root: Provider<Directory>,
        pythonVersion: Provider<String>,
        after: Any,
    ): TaskProvider<Task> {
        val sliceTasks = IosPythonHomeLayout.slices.map { slice ->
            project.tasks.register(slice.stageTaskName, StageIosPythonHomeTask::class.java) {
                group = "python"
                description = "Stages CPython's standard library for an iOS app built for ${slice.name} (SPEC L-11)"
                dependsOn(after)
                this.xcframework.set(xcframework)
                sliceName.set(slice.name)
                this.pythonVersion.set(pythonVersion)
                destinationDir.set(root.map { it.dir(slice.name) })
            }
        }
        project.tasks.register(ALL_SLICES_TASK) {
            group = "python"
            description = "Stages CPython's standard library for every iOS app slice (SPEC L-11)"
            dependsOn(sliceTasks)
        }
        return project.tasks.register(FOR_XCODE_TASK) {
            group = "python"
            description = "Stages the iOS app prefix for the slice Xcode is building and prints its path (SPEC L-11)"
            val platformName = project.providers.environmentVariable("EFFECTIVE_PLATFORM_NAME")
            val archs = project.providers.environmentVariable("ARCHS")
            val selected = project.providers.provider { IosPythonHomeLayout.sliceFor(platformName.orNull, archs.orNull) }
            // Only the slice being built: staging all three costs three lib-dynload trees per build.
            dependsOn(selected.map { project.tasks.named(it.stageTaskName) })
            val rootDir = root.map { it.asFile }
            doLast {
                xcodeOutputLines(rootDir.get(), selected.get()).forEach { println(it) }
            }
        }
    }
}

/** The task path a consumer's copy of `install-python.sh` runs by default. */
fun consumerIosHomeTaskPath(projectPath: String): String =
    if (projectPath == ":") ":${IosPythonHomeTasks.FOR_XCODE_TASK}" else "$projectPath:${IosPythonHomeTasks.FOR_XCODE_TASK}"

/** The default `PYTHON_HOME_TASK` in this repository's `tools/xcode/install-python.sh`. */
internal const val REPOSITORY_IOS_HOME_TASK: String = ":python-multiplatform:stageIosPythonHomeForXcode"

/** Where the plugin jar carries `tools/xcode/install-python.sh` (copied in by the plugin build). */
internal const val INSTALL_PYTHON_SCRIPT_RESOURCE: String = "/python/multiplatform/gradle/xcode/install-python.sh"

/** The script as this plugin was built with it. */
internal fun bundledInstallPythonScript(): String =
    WriteIosInstallPythonScriptTask::class.java.getResourceAsStream(INSTALL_PYTHON_SCRIPT_RESOURCE)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: throw GradleException("$INSTALL_PYTHON_SCRIPT_RESOURCE is missing from the python-multiplatform Gradle plugin jar")

/**
 * [template] with its default `PYTHON_HOME_TASK` replaced by [homeTaskPath]; everything else, the
 * `PYTHON_PAYLOAD_DIR`/`PYTHON_PAYLOAD_TASK` handling included, is the repository script unchanged.
 * Fails when the default is not found, rather than writing a script that would run this repository's
 * `:python-multiplatform` task in a consumer's build.
 */
internal fun renderInstallPythonScript(template: String, homeTaskPath: String): String {
    val default = "\${PYTHON_HOME_TASK:-$REPOSITORY_IOS_HOME_TASK}"
    if (default !in template) {
        throw GradleException("install-python.sh no longer contains '$default'; WriteIosInstallPythonScriptTask needs updating")
    }
    return template.replace(default, "\${PYTHON_HOME_TASK:-$homeTaskPath}")
}

/**
 * Writes `tools/xcode/install-python.sh` into the consumer's build directory, defaulting to that
 * consumer's own `stageIosPythonHomeForXcode` -- issue #90's answer to "how does a consumer get the
 * script": from the plugin it already applies, so script and Gradle tasks always come from one
 * version. An Xcode Run Script phase runs it from the Gradle root:
 *
 *     cd "$SRCROOT/.."
 *     ./gradlew -q :app:writeIosInstallPythonScript
 *     /bin/bash app/build/python-multiplatform/xcode/install-python.sh
 */
abstract class WriteIosInstallPythonScriptTask : DefaultTask() {

    @get:Input
    abstract val homeTaskPath: Property<String>

    /** The script text the plugin carries, so a plugin upgrade rewrites the file. */
    @get:Input
    abstract val template: Property<String>

    @get:OutputFile
    abstract val scriptFile: RegularFileProperty

    @TaskAction
    fun write() {
        val file = scriptFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(renderInstallPythonScript(template.get(), homeTaskPath.get()))
        file.setExecutable(true, false)
    }

    companion object {
        const val NAME: String = "writeIosInstallPythonScript"

        /** Relative to the project's build directory. */
        const val SCRIPT_PATH: String = "python-multiplatform/xcode/install-python.sh"
    }
}
