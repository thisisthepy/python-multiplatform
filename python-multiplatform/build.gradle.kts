import com.codingfeline.buildkonfig.compiler.FieldSpec
import java.net.URL
// Imported rather than written fully-qualified at the use site: inside a Gradle build script
// `java` resolves to the JavaPluginExtension, so `java.net.URLClassLoader` fails to compile with
// "Unresolved reference: net". Same for every other `java.*` name used below.
import java.net.URLClassLoader
import java.nio.charset.Charset
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Properties
import java.io.File
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget.*
import org.jetbrains.kotlin.konan.target.linker
import python.multiplatform.gradle.CPythonFlavour
import python.multiplatform.gradle.CPythonIncludeDirectories
import python.multiplatform.gradle.CPythonIncludeLayout
import python.multiplatform.gradle.EmbeddedPythonVersion
import python.multiplatform.gradle.GenerateWasmProxyExportsTask


plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    id("io.github.thisisthepy.python.multiplatform.bindings") apply false
    id("com.codingfeline.buildkonfig").version("0.15.2")
    //id("org.jetbrains.dokka")
    id("maven-publish")
    id("signing")
}


val configuredPythonVersion = project.findProperty("pythonVersion")?.toString() ?: project.rootProject.version.toString()
val pythonFreeThreaded = project.findProperty("pythonFreeThreaded")?.toString()?.toBoolean() ?: false
val pbsRelease = project.findProperty("pythonBuildStandaloneRelease")?.toString() ?: "20260807"
val pythonAppleSupportBuild = project.findProperty("pythonAppleSupportBuild")?.toString() ?: "b11"

val pythonVersion = configuredPythonVersion
val libraryVersion = "$pythonVersion-alpha01"
version = libraryVersion

buildkonfig {
    packageName = project.name.lowercase().replace("-", ".")
    objectName = "BuildConfig"

    defaultConfigs {
        buildConfigField(FieldSpec.Type.STRING, "pythonVersion", pythonVersion)
        buildConfigField(FieldSpec.Type.STRING, "libraryVersion", libraryVersion)
        // The free-threaded build is not merely a differently-compiled binary: it renames every
        // artefact the runtime looks up by name. The shared library is `libpython3.14t.dylib`, not
        // `libpython3.14.dylib`, and the stdlib sits in `lib/python3.14t/`. Without this flag
        // reaching Kotlin, `manager.loadLibPython` asks for a file that a free-threaded install
        // does not contain, and the failure looks like a missing download rather than a flavour
        // mismatch. See `Versions.abiFlags`.
        buildConfigField(FieldSpec.Type.BOOLEAN, "pythonFreeThreaded", pythonFreeThreaded.toString())
    }
}

val libVersion = pythonVersion.split('.').subList(0, 2).joinToString(".")

/**
 * The `ftp/python/<dir>/` directory holding a release, which drops any pre-release suffix:
 * `3.15.0rc1` is published under `3.15.0`. Identical to the version itself for a final release,
 * so it is always the right thing to build a python.org URL from.
 *
 * Declared here, above every consumer, because both the Android and the iOS download tasks need
 * it. It used to sit below the Android tasks, which therefore built their URLs from the raw
 * version and 404'd on any pre-release -- `ftp/python/3.15.0rc1/` does not exist. That stayed
 * invisible because the archives were already sitting in the download directory, so the fetch was
 * skipped; adding the `.sigstore` fetch is what surfaced it.
 */
val pythonOrgReleaseDir = pythonVersion.split(".").let { parts ->
    val patch = parts.getOrNull(2)?.takeWhile { it.isDigit() } ?: "0"
    "${parts[0]}.${parts[1]}.$patch"
}

println("----------------------------------------------------------------------------------------")
println("                   Build Configuration for Python version $libVersion                   ")
println("----------------------------------------------------------------------------------------")
println()

val licensePath = "src/nativeInterop/cinterop/license"
val libPath = "src/nativeInterop/cinterop/lib"
val libPathForDesktop = "$libPath/desktop"

val downloadDir = layout.buildDirectory.dir("python-standalone").get().asFile

/**
 * Where archives are unpacked, keyed by the CPython version they contain.
 *
 * Every extraction below is guarded by "is the destination directory empty?", which is what makes
 * repeated builds cheap. The guard is only sound if a directory can hold exactly one thing. It
 * used to be a flat `extracted/<platform>`, so changing `-PpythonVersion` downloaded and
 * checksummed the new archive and then *threw it away*: the destination was not empty, so the
 * previous version's tree stayed, and the build compiled against it while every log line said
 * otherwise. Same for [desktopFlavourSuffix]. Keying the path by what is inside it removes the
 * whole class of error -- and switching back to a version already unpacked is still free.
 */
val extractedDir = file("$downloadDir/extracted/$configuredPythonVersion")

/**
 * Suffix that keeps the two desktop flavours apart within [extractedDir].
 *
 * The GIL and free-threaded builds of the *same* version are different trees containing
 * differently-named libraries (`libpython3.14.dylib` vs `libpython3.14t.dylib`), so they need
 * separating for the same reason the version does.
 */
val desktopFlavourSuffix = if (pythonFreeThreaded) "-freethreaded" else ""

/**
 * Fails the build when the extracted CPython's own headers report a version other than
 * `pythonVersion` (issue #47). The archive name and the lockfile say what was meant to be
 * downloaded; `PY_VERSION` in `patchlevel.h` says what it is. iOS once shipped 3.14.6 (BeeWare
 * `3.14-b10`) under `pythonVersion=3.14.7` and nothing noticed.
 */
fun requireHeaderVersion(label: String, extracted: File) {
    val headers = extracted.walkTopDown().filter { it.name == "patchlevel.h" }.toList()
    if (headers.isEmpty()) throw GradleException("$label: no patchlevel.h under $extracted, cannot verify the CPython version")
    val re = Regex("""^\s*#\s*define\s+PY_VERSION\s+"([^"]+)"""", RegexOption.MULTILINE)
    for (h in headers) {
        val actual = re.find(h.readText())?.groupValues?.get(1)
            ?: throw GradleException("$label: no PY_VERSION in $h")
        if (actual != configuredPythonVersion) {
            throw GradleException("$label: $h says PY_VERSION $actual but pythonVersion=$configuredPythonVersion. " +
                "Pin an archive that ships $configuredPythonVersion (see docs/platforms/python-version-acquisition.md).")
        }
    }
}

val checksumsFile = rootProject.file("python-checksums.properties")
val pythonArchiveKeys = mutableMapOf<String, File>()

fun verifyChecksum(key: String, archive: File) {
    if (!checksumsFile.exists()) {
        throw GradleException("Checksum lockfile python-checksums.properties not found! Run updatePythonChecksums to generate it.")
    }
    val props = Properties()
    checksumsFile.inputStream().use { props.load(it) }
    
    val expectedHash = props.getProperty(key)
    if (expectedHash == null) {
        throw GradleException("Missing checksum for $key in python-checksums.properties. Run updatePythonChecksums task to accept the current downloaded archive as trusted.")
    }
        
    val digest = MessageDigest.getInstance("SHA-256")
    archive.inputStream().use {
        val buffer = ByteArray(8192)
        var bytesRead = it.read(buffer)
        while (bytesRead != -1) {
            digest.update(buffer, 0, bytesRead)
            bytesRead = it.read(buffer)
        }
    }
    val actualHash = digest.digest().joinToString("") { String.format("%02x", it) }
        
    if (expectedHash != actualHash) {
        archive.delete()
        throw GradleException(
            "Checksum mismatch for $archive (key: $key) in python-checksums.properties.\n" +
                "Expected: $expectedHash\n" +
                "Actual:   $actualHash"
        )
    }
}

// =================================================================================================
// Sigstore verification of the python.org archives (ROADMAP §12, "Download integrity").
//
// This is a SECOND gate, not a replacement for `verifyChecksum` above. The two prove different
// things and neither implies the other:
//
//   the lockfile  "these are the exact bytes this repository reviewed and pinned"
//   Sigstore      "these are the bytes the CPython release manager actually signed"
//
// The lockfile is also the only one of the two that works offline, and the only one that covers
// every source. So it stays unconditional and Sigstore is layered on top of it.
//
// ### Why this is opt-in
//
// `sigstore-java` drags in grpc-netty-shaded, protobuf, bouncycastle and guava -- tens of
// megabytes that nothing else in this build needs -- and `sigstorePublicDefaults()` fetches a TUF
// trust root over the network on first use, which would turn an offline build from "works" into
// "fails". Declaring the dependency costs nothing because Gradle resolves a configuration lazily;
// it is only fetched if `verifySigstore` is actually true and a download task runs. Enable with:
//
//     ./gradlew <task> -PverifyPythonSignatures=true
//
// ### What is covered, and what is not
//
//   python.org Android aarch64/x86_64   COVERED -- sibling `<archive>.sigstore` bundle
//   python.org iOS XCframework (3.15+)  COVERED -- same
//   astral-sh/python-build-standalone   NOT covered. It publishes no sibling signature at all:
//                                       853 release assets, and the only non-archive among them
//                                       is `SHA256SUMS` (checked directly against the release).
//                                       Its provenance lives in GitHub's attestations API, keyed
//                                       by artifact *digest* rather than filename, and that API
//                                       is rate-limited to 60 requests/hour unauthenticated. That
//                                       is a different mechanism, not this one. The desktop path
//                                       already verifies against the release's own SHA256SUMS in
//                                       addition to the lockfile.
//   beeware/Python-Apple-support        NOT coverable. The release publishes five tar.gz assets
//                                       and nothing else -- no checksums, no signatures -- and it
//                                       has no attestations either. The SHA-256 pin is the only
//                                       honest instrument available for iOS <= 3.14.
val verifySigstore = project.findProperty("verifyPythonSignatures")?.toString()?.toBoolean() ?: false

val sigstoreVerifierClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isVisible = false
}

dependencies {
    add("sigstoreVerifierClasspath", "dev.sigstore:sigstore-java:2.2.0")
}

/** The Fulcio certificate identity a python.org bundle must carry. */
data class SigstoreIdentity(val subjectAlternativeName: String, val issuer: String)

/**
 * Which release manager signed a given CPython version, per https://www.python.org/download/sigstore/
 *
 * This is a map rather than a constant on purpose. The signing identity is a property of the
 * *release manager*, and it changes between release series: 3.13 was Thomas Wouters signing via
 * Google, 3.14 and 3.15 are Hugo van Kemenade signing via GitHub. A hardcoded identity would keep
 * verifying happily on the version it was written for and then either fail confusingly or -- far
 * worse -- be loosened by the next person to "just make it pass" on an older one.
 *
 * An unknown series is a hard failure rather than a skip. A verification step that silently
 * declines to verify is the thing this whole section exists to avoid.
 */
fun pythonOrgSigstoreIdentity(version: String): SigstoreIdentity {
    val series = version.split(".").take(2).joinToString(".")
    return when (series) {
        "3.14", "3.15" -> SigstoreIdentity("hugo@python.org", "https://github.com/login/oauth")
        "3.12", "3.13" -> SigstoreIdentity("thomas@python.org", "https://accounts.google.com")
        else -> throw GradleException(
            "No Sigstore signing identity is recorded for CPython $series. Add it from " +
                "https://www.python.org/download/sigstore/ rather than disabling verification."
        )
    }
}

/**
 * Verifies `archive` against its sibling `.sigstore` bundle, pinning the signer identity.
 *
 * Reflection, and a classloader whose parent is the *platform* loader rather than Gradle's, is
 * what keeps this opt-in: nothing here is on the buildscript classpath, so a build that does not
 * set `-PverifyPythonSignatures=true` never resolves or downloads any of it. The platform parent
 * also isolates sigstore-java's guava/protobuf from the versions Gradle itself runs on.
 *
 * Pinning the identity is the whole point. `verify()` without certificate matchers proves only
 * that *somebody* with a Sigstore certificate signed these bytes, which is a check anyone on the
 * internet can pass.
 */
fun verifySigstoreBundle(archive: File, bundleUrl: String?, bundleFile: File, identity: SigstoreIdentity) {
    if (!bundleFile.exists()) {
        if (bundleUrl == null) {
            throw GradleException("Sigstore bundle $bundleFile does not exist and no URL provided")
        }
        println("Downloading $bundleUrl")
        bundleFile.parentFile.mkdirs()
        URL(bundleUrl).openStream().use { input ->
            FileOutputStream(bundleFile).use { output -> input.copyTo(output) }
        }
    }

    val loader = URLClassLoader(
        sigstoreVerifierClasspath.files.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader()
    )

    fun load(name: String): Class<*> = Class.forName(name, true, loader)

    // sigstore-java ships its TUF trust root as a resource and reaches it through Guava's
    // `Resources.getResource`, which asks the *thread context* classloader -- not the loader that
    // loaded the calling class. Without this swap the verifier builds and then dies with
    // "resource dev/sigstore/tuf/sigstore-tuf-root/root.json not found", which reads like a
    // packaging bug rather than a classloader one. Observed, not anticipated.
    val previousContextLoader = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = loader

    try {
        val stringMatcher = load("dev.sigstore.strings.StringMatcher")
        val matchString = stringMatcher.getMethod("string", String::class.java)

        val certificateMatcher = load("dev.sigstore.VerificationOptions\$CertificateMatcher")
        val fulcioBuilder = certificateMatcher.getMethod("fulcio").invoke(null)
        fulcioBuilder.javaClass.getMethod("subjectAlternativeName", stringMatcher)
            .invoke(fulcioBuilder, matchString.invoke(null, identity.subjectAlternativeName))
        fulcioBuilder.javaClass.getMethod("issuer", stringMatcher)
            .invoke(fulcioBuilder, matchString.invoke(null, identity.issuer))
        val matcher = fulcioBuilder.javaClass.getMethod("build").invoke(fulcioBuilder)

        val verificationOptions = load("dev.sigstore.VerificationOptions")
        val optionsBuilder = verificationOptions.getMethod("builder").invoke(null)
        optionsBuilder.javaClass.getMethod("addCertificateMatchers", certificateMatcher)
            .invoke(optionsBuilder, matcher)
        val options = optionsBuilder.javaClass.getMethod("build").invoke(optionsBuilder)

        val bundleClass = load("dev.sigstore.bundle.Bundle")
        val bundle = bundleClass
            .getMethod("from", Path::class.java, Charset::class.java)
            .invoke(null, bundleFile.toPath(), Charsets.UTF_8)

        val verifierClass = load("dev.sigstore.KeylessVerifier")
        val verifierBuilder = verifierClass.getMethod("builder").invoke(null)
        verifierBuilder.javaClass.getMethod("sigstorePublicDefaults").invoke(verifierBuilder)
        val verifier = verifierBuilder.javaClass.getMethod("build").invoke(verifierBuilder)

        verifierClass
            .getMethod("verify", Path::class.java, bundleClass, verificationOptions)
            .invoke(verifier, archive.toPath(), bundle, options)

        println("Sigstore OK: ${archive.name} signed by ${identity.subjectAlternativeName} via ${identity.issuer}")
    } catch (e: InvocationTargetException) {
        // Unwrap: the reflective frame is noise, the cause is the verification failure.
        val cause = e.targetException ?: e
        throw GradleException(
            "Sigstore verification FAILED for ${archive.name}\n" +
                "  bundle:   ${bundleFile.name}\n" +
                "  expected: SAN=${identity.subjectAlternativeName} issuer=${identity.issuer}\n" +
                "  cause:    ${cause::class.java.name}: ${cause.message}",
            cause
        )
    } finally {
        Thread.currentThread().contextClassLoader = previousContextLoader
        loader.close()
    }
}

/** Verifies a python.org archive if `-PverifyPythonSignatures=true`, otherwise says it skipped. */
fun maybeVerifySigstore(archive: File, archiveUrl: String) {
    if (!verifySigstore) return
    verifySigstoreBundle(
        archive = archive,
        bundleUrl = "$archiveUrl.sigstore",
        bundleFile = file("$downloadDir/${archive.name}.sigstore"),
        identity = pythonOrgSigstoreIdentity(configuredPythonVersion)
    )
}

/** Verifies a python-build-standalone archive using GitHub Attestations API if `-PverifyPythonSignatures=true`. */
fun maybeVerifyPbsSigstore(archive: File, actualHash: String) {
    if (!verifySigstore) return
    val bundleFile = file("$downloadDir/${archive.name}.sigstore")
    if (!bundleFile.exists()) {
        val apiUrl = "https://api.github.com/repos/astral-sh/python-build-standalone/attestations/sha256:$actualHash"
        println("Downloading attestation from $apiUrl")
        val conn = URL(apiUrl).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "Gradle-Python-Multiplatform")
        
        if (conn.responseCode != 200) {
            throw GradleException("Failed to fetch attestation for ${archive.name} (HTTP ${conn.responseCode}): ${conn.errorStream?.bufferedReader()?.readText()}")
        }
        
        val response = conn.inputStream.bufferedReader().readText()
        val parsed = groovy.json.JsonSlurper().parseText(response) as Map<*, *>
        val attestations = parsed["attestations"] as List<*>
        val bundle = (attestations[0] as Map<*, *>)["bundle"]
        val bundleJson = groovy.json.JsonOutput.toJson(bundle)
        
        bundleFile.parentFile.mkdirs()
        bundleFile.writeText(bundleJson)
    }
    
    verifySigstoreBundle(
        archive = archive,
        bundleUrl = null,
        bundleFile = bundleFile,
        identity = SigstoreIdentity(
            "https://github.com/astral-sh/python-build-standalone/.github/workflows/release.yml@refs/heads/main",
            "https://token.actions.githubusercontent.com"
        )
    )
}

tasks.register("updatePythonChecksums") {
    doLast {
        println("Updating python-checksums.properties. This accepts currently downloaded archives as trusted.")
        val props = Properties()
        if (checksumsFile.exists()) {
            checksumsFile.inputStream().use { props.load(it) }
        }
        var updated = false
        val digest = MessageDigest.getInstance("SHA-256")
        
        pythonArchiveKeys.forEach { (key, archive) ->
            if (archive.exists()) {
                digest.reset()
                archive.inputStream().use {
                    val buffer = ByteArray(8192)
                    var bytesRead = it.read(buffer)
                    while (bytesRead != -1) {
                        digest.update(buffer, 0, bytesRead)
                        bytesRead = it.read(buffer)
                    }
                }
                val hash = digest.digest().joinToString("") { String.format("%02x", it) }
                props.setProperty(key, hash)
                updated = true
                println("Computed $key = $hash")
            } else {
                println("Skipping $key: archive not downloaded yet at ${archive.name}")
            }
        }
        
        if (updated) {
            checksumsFile.bufferedWriter().use { writer ->
                // These were `"\\n"` -- a literal backslash and an `n`, not a newline. The task
                // therefore emitted the entire lockfile as one physical line starting with `#`,
                // which `Properties` reads as a single comment: running it once deleted every
                // checksum, and the next build failed with "Missing checksum" for all of them.
                // The committed file survived only because nobody had run the task since.
                writer.write("# Python Multiplatform Checksums\n")
                props.stringPropertyNames().sorted().forEach { k ->
                    writer.write("$k=${props.getProperty(k)}\n")
                }
            }
        }
    }
}

val desktopTargets = mapOf(
    "macos-aarch64" to "aarch64-apple-darwin",
    "macos-x86_64" to "x86_64-apple-darwin",
    "linux-x86_64" to "x86_64-unknown-linux-gnu",
    "windows-x86_64" to "x86_64-pc-windows-msvc"
)

val downloadTasks = desktopTargets.map { (platform, pbsTarget) ->
    val flavour = if (pythonFreeThreaded) "freethreaded-install_only" else "install_only"
    val assetName = "cpython-$configuredPythonVersion+$pbsRelease-$pbsTarget-$flavour.tar.gz"
    val url = "https://github.com/astral-sh/python-build-standalone/releases/download/$pbsRelease/$assetName"
    val archive = file("$downloadDir/$assetName")
    val extractDir = file("$extractedDir/$platform$desktopFlavourSuffix")
    val lockKey = "$platform-$configuredPythonVersion-$pbsRelease$desktopFlavourSuffix"
    pythonArchiveKeys[lockKey] = archive

    val taskName = "downloadPython_${platform.replace("-", "_")}"
    tasks.register(taskName) {
        inputs.property("url", url)
        outputs.dir(extractDir)
        
        doLast {
            if (!archive.exists()) {
                println("Downloading $url")
                archive.parentFile.mkdirs()
                URL(url).openStream().use { input ->
                    FileOutputStream(archive).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val sha256Url = "https://github.com/astral-sh/python-build-standalone/releases/download/$pbsRelease/SHA256SUMS"
            val shaFile = file("$downloadDir/SHA256SUMS-$pbsRelease")
            if (!shaFile.exists()) {
                URL(sha256Url).openStream().use { input ->
                    FileOutputStream(shaFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            val sha256sums = shaFile.readText()
            val astralExpectedHash = sha256sums.lines().find { it.endsWith(assetName) }?.substringBefore(" ")
                ?: throw GradleException("Checksum for $assetName not found in SHA256SUMS")
            
            val digest = MessageDigest.getInstance("SHA-256")
            archive.inputStream().use {
                val buffer = ByteArray(8192)
                var bytesRead = it.read(buffer)
                while (bytesRead != -1) {
                    digest.update(buffer, 0, bytesRead)
                    bytesRead = it.read(buffer)
                }
            }
            val astralActualHash = digest.digest().joinToString("") { String.format("%02x", it) }
                
            if (astralExpectedHash != astralActualHash) {
                archive.delete()
                throw GradleException("Checksum mismatch for $assetName. Expected $astralExpectedHash, got $astralActualHash")
            }
            
            verifyChecksum(lockKey, archive)
            maybeVerifyPbsSigstore(archive, astralActualHash)
            
            val isEmpty = extractDir.list()?.isEmpty() ?: true
            if (isEmpty) {
                println("Extracting $archive to $extractDir")
                copy {
                    from(tarTree(resources.gzip(archive)))
                    into(extractDir)
                }
            }
            requireHeaderVersion(platform, extractDir)
        }
    }
}

val androidTargets = mapOf(
    "android-aarch64" to "aarch64",
    "android-x86_64" to "x86_64"
)

val androidDownloadTasks = androidTargets.map { (platform, arch) ->
    val url = "https://www.python.org/ftp/python/$pythonOrgReleaseDir/python-$configuredPythonVersion-$arch-linux-android.tar.gz"
    val archive = file("$downloadDir/python-$configuredPythonVersion-$arch-linux-android.tar.gz")
    val extractDir = file("$extractedDir/$platform")
    val lockKey = "$platform-$configuredPythonVersion"
    pythonArchiveKeys[lockKey] = archive
    
    val taskName = "downloadPython_${platform.replace("-", "_")}"
    tasks.register(taskName) {
        inputs.property("url", url)
        outputs.dir(extractDir)
        
        doLast {
            if (!archive.exists()) {
                println("Downloading $url")
                archive.parentFile.mkdirs()
                URL(url).openStream().use { input ->
                    FileOutputStream(archive).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            
            // python.org publishes no SHA256SUMS, so the lockfile is what pins these bytes. It also
            // publishes a sibling `<archive>.sigstore` bundle, which `maybeVerifySigstore` checks
            // against the release manager's pinned identity when `-PverifyPythonSignatures=true`.
            // (An earlier comment here claimed Sigstore verification was "unreasonable in pure
            // Gradle". It is not -- `dev.sigstore:sigstore-java` does it in-process; see the
            // section above `updatePythonChecksums` for why it is opt-in rather than always on.)
            verifyChecksum(lockKey, archive)
            maybeVerifySigstore(archive, url)
            
            val isEmpty = extractDir.list()?.isEmpty() ?: true
            if (isEmpty) {
                println("Extracting $archive to $extractDir")
                copy {
                    from(tarTree(resources.gzip(archive)))
                    into(extractDir)
                }
            }
            requireHeaderVersion(platform, extractDir)
        }
    }
}

/**
 * Which project publishes the iOS `Python.xcframework` for the configured version.
 *
 * python.org began publishing an official iOS XCframework with 3.15 (the first entries in
 * `ftp/python/3.15.0/` are the 3.15.0b1 betas). BeeWare's Python-Apple-support, which was the
 * only source before that, stops at `3.14-b11` (3.14.7) and has no 3.15 tag. The two do not overlap:
 * 3.14 and earlier can only come from BeeWare, 3.15 and later only from python.org. So this is a
 * hard switch on the version, not a preference.
 *
 * Swapping is otherwise cheap, because the trees are layout-compatible everywhere this build
 * reaches into them -- `Python.xcframework/<abi>/Python.framework/Headers`, `-F .../<abi>`,
 * `Python.xcframework/lib/pythonX.Y` and `.../<abi>/lib-arm64/pythonX.Y` all exist in both.
 * The differences are in parts nothing here reads: BeeWare adds a `platform-config/` directory
 * (cross-compilation sysconfig data for building wheels) and a `VERSIONS` file, and its headers
 * still carry `module.modulemap`, `lock.h`, `monitoring.h` and `typeslots.h` where 3.15 has
 * `pyabi.h`, `slots.h` and `slots_generated.h` instead -- a 3.14-vs-3.15 difference, not a
 * packaging one. Both ship a GIL-enabled build; neither publishes a free-threaded iOS variant.
 */
// Name, URL and lockfile key come from the Gradle plugin's `IosSupportArchive` (issue #90), which a
// consumer's `acquireIosPythonSupport` uses too, so the two cannot pin different archives. The
// SHA-256 itself stays in `python-checksums.properties`; the plugin build generates its table from
// that same file.
val iosPin = python.multiplatform.gradle.IosSupportArchive.forVersion(pythonVersion, pythonAppleSupportBuild)
val iosFromPythonOrg = iosPin.fromPythonOrg
val iosArchiveName = iosPin.archiveName
val iosUrl = iosPin.url
val iosArchive = file("$downloadDir/$iosArchiveName")
val iosExtractDir = file("$extractedDir/ios")
val iosLockKey = iosPin.lockKey
pythonArchiveKeys[iosLockKey] = iosArchive

val downloadPython_ios = tasks.register("downloadPython_ios") {
    inputs.property("url", iosUrl)
    outputs.dir(iosExtractDir)
    
    doLast {
        if (!iosArchive.exists()) {
            println("Downloading $iosUrl")
            iosArchive.parentFile.mkdirs()
            URL(iosUrl).openStream().use { input ->
                FileOutputStream(iosArchive).use { output ->
                    input.copyTo(output)
                }
            }
        }
        
        // The two iOS sources are not equally verifiable, and the difference is worth stating.
        //
        // BeeWare (<= 3.14) publishes five tar.gz assets and nothing else -- no checksums, no
        // signatures, no GitHub attestations. The lockfile pin is the only honest instrument
        // there, and no amount of build wiring changes that.
        //
        // python.org (3.15+) publishes a sibling `<archive>.sigstore` bundle, which IS verifiable
        // here. An earlier comment claimed otherwise; it was wrong.
        //
        // The lockfile applies to both regardless, because it is the only check that works
        // offline and the only one that covers every source.
        verifyChecksum(iosLockKey, iosArchive)
        if (iosFromPythonOrg) maybeVerifySigstore(iosArchive, iosUrl)

        // The iOS extract dir is keyed by pythonVersion, not by archive, so bumping the pinned
        // archive (b10 -> b11) would otherwise keep the old tree: "not empty, skip". A stamp of the
        // archive's lock key + digest tells a stale tree from a current one (issue #47).
        val iosStamp = file("$iosExtractDir/.archive-stamp")
        val iosWanted = "$iosLockKey ${iosArchive.name}"
        if (iosExtractDir.exists() && (!iosStamp.exists() || iosStamp.readText().trim() != iosWanted)) {
            println("Discarding stale iOS extraction in $iosExtractDir")
            iosExtractDir.deleteRecursively()
        }
        val isEmpty = iosExtractDir.list()?.isEmpty() ?: true
        if (isEmpty) {
            println("Extracting $iosArchive to $iosExtractDir")
            copy {
                from(tarTree(resources.gzip(iosArchive)))
                into(iosExtractDir)
            }
            iosStamp.writeText(iosWanted + "\n")
        }
        requireHeaderVersion("ios", iosExtractDir)
    }
}

val downloadAllPythonBuilds by tasks.registering {
    dependsOn(downloadTasks)
    dependsOn(androidDownloadTasks)
    dependsOn(downloadPython_ios)
}

/**
 * Public build output (issue #46): the include directory of the CPython this build acquires, per
 * target and flavour, each carrying its extraction task. Consumers (pypackpack's compile slot,
 * TypedPython's C build) read it with
 * `project(":python-multiplatform").extensions.getByType<CPythonIncludeDirectories>().includeDir("macos-aarch64")`
 * after `evaluationDependsOn(":python-multiplatform")`. Only the desktop flavour selected with
 * `-PpythonFreeThreaded` has a task; Android and iOS are always GIL. See
 * `docs/platforms/python-version-acquisition.md` §7.
 */
extensions.add(
    "cpythonIncludeDirectories",
    CPythonIncludeDirectories(
        layout.projectDirectory.dir(extractedDir.absolutePath),
        configuredPythonVersion,
    ) { target, flavour ->
        val desktop = target in CPythonIncludeLayout.desktopTargets
        val extractedFlavour = if (desktop && pythonFreeThreaded) CPythonFlavour.FREE_THREADED else CPythonFlavour.GIL
        if (flavour == extractedFlavour) tasks.named(CPythonIncludeLayout.downloadTaskName(target)) else null
    },
)

/**
 * Public build output (issue #61): the CPython version this build embeds, as the `pythonMultiplatform`
 * extension, as Gradle attributes on the published variants, and as a classpath resource
 * (`META-INF/python-multiplatform/python.properties`) in the jar and the AAR. See
 * `docs/platforms/python-version-acquisition.md` "Published version".
 */
val embeddedPythonVersion = EmbeddedPythonVersion(configuredPythonVersion, pythonFreeThreaded)
embeddedPythonVersion.register(project)

val generatePythonProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/python-properties")
    val content = embeddedPythonVersion.renderProperties()
    inputs.property("content", content)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().asFile.resolve(EmbeddedPythonVersion.RESOURCE_PATH)
        file.parentFile.mkdirs()
        file.writeText(content)
    }
}

val androidBuildDir = "$projectDir/build/android"

// =================================================================================================
// GraalVM reachability metadata for the desktop (Panama/FFM) surface.
//
// This file used to be a checked-in static resource. Under `native-image`'s closed world every
// `FunctionDescriptor` that `Linker.downcallHandle`/`Linker.upcallStub` will ever see has to be
// declared up front; anything missing does not fail the build, it fails at *runtime* with
// `MissingForeignRegistrationError`. A checked-in list therefore rots silently: adding one
// `find("PyX", Integer.TYPE, P, P, P)` line to `bindings.kt` with a shape nothing else uses
// produces a binary that builds clean and dies on first call. It had already rotted: the
// checked-in list carried one upcall descriptor while `Panama` builds three, so `ProxyTypeFactory`
// would have died in any image that reached it. See ROADMAP §7.
//
// So the shapes are derived from the sources that define them:
//
//   * `bindings.kt`               -- one `find(symbol, returnType, params...)` per CPython entry
//                                    point; `Panama.findSymbol` turns each into a descriptor.
//   * `ShapeDowncalls.desktop.kt` -- the 14-entry shared shape vocabulary, declared as
//                                    `Panama.unboundDowncallHandle(intArgs, floatArgs, kind)`.
//   * `Panama.kt`                 -- `findSym("malloc"/"free", ...)` plus the upcall stub
//                                    builders, whose descriptors are spelled out in
//                                    `// @UpcallShape(...)` markers next to each builder.
//
// Every one of those is a *source-of-truth* read, and each parse is bounded by a count assertion
// (see `check(...)` calls below): a declaration form the parser does not recognise fails the
// build rather than silently dropping a descriptor. `ReachabilityMetadataTest` then re-checks the
// generated file against the `MethodHandle.type()`s the code actually produces at runtime, which
// is a verification path that shares no code with this parser.
// =================================================================================================

abstract class GenerateReachabilityMetadata : DefaultTask() {

    @get:InputFile
    abstract val bindingsSource: RegularFileProperty

    @get:InputFile
    abstract val shapeVocabularySource: RegularFileProperty

    @get:InputFile
    abstract val panamaSource: RegularFileProperty

    /** Hand-authored entries (resources, reflection) that no source file can imply. */
    @get:InputFile
    abstract val baseMetadata: RegularFileProperty

    @get:Input
    abstract val relativePath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /** A descriptor: FFM carrier names, e.g. `long (long, int)`. */
    private data class Descriptor(val returnType: String, val parameterTypes: List<String>)

    @TaskAction
    fun generate() {
        val downcalls = LinkedHashSet<Descriptor>()
        downcalls += parseBindings(bindingsSource.get().asFile.readText())
        downcalls += parseShapeVocabulary(shapeVocabularySource.get().asFile.readText())

        val panamaText = panamaSource.get().asFile.readText()
        downcalls += parsePanamaDowncalls(panamaText)
        val upcalls = parseUpcallShapes(panamaText)

        val json = buildString {
            append("{\n")
            append("  \"foreign\": {\n")
            append("    \"downcalls\": [\n")
            append(renderDescriptors(downcalls.sortedWith(descriptorOrder)))
            append("    ],\n")
            append("    \"upcalls\": [\n")
            append(renderDescriptors(upcalls.sortedWith(descriptorOrder)))
            append("    ]\n")
            append("  }")
            for ((key, value) in readBaseEntries()) {
                append(",\n  \"").append(key).append("\": ")
                append(indentContinuation(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(value))))
            }
            append("\n}\n")
        }

        val target = outputDirectory.get().asFile.resolve(relativePath.get())
        target.parentFile.mkdirs()
        target.writeText(json)
        logger.lifecycle(
            "Reachability metadata: ${downcalls.size} downcall + ${upcalls.size} upcall descriptors -> $target"
        )
    }

    private val descriptorOrder =
        compareBy<Descriptor>({ it.parameterTypes.size }, { it.returnType }, { it.parameterTypes.joinToString(",") })

    private fun renderDescriptors(items: List<Descriptor>): String = items.joinToString(",\n", postfix = "\n") {
        val params = it.parameterTypes.joinToString(", ") { p -> "\"$p\"" }
        "      { \"returnType\": \"${it.returnType}\", \"parameterTypes\": [$params] }"
    }

    /** JsonOutput indents by 4; the rest of this document uses 2, nested one level under the root. */
    private fun indentContinuation(pretty: String): String = pretty.lines()
        .joinToString("\n") { line ->
            if (line.isEmpty()) line
            else " ".repeat(2 + line.takeWhile { it == ' ' }.length / 2) + line.trimStart()
        }
        .trimStart()

    @Suppress("UNCHECKED_CAST")
    private fun readBaseEntries(): Map<String, Any?> {
        val parsed = groovy.json.JsonSlurper().parse(baseMetadata.get().asFile) as Map<String, Any?>
        // `_`-prefixed keys are documentation for the humans editing the base file; native-image
        // validates the document against a schema, so they must not reach the generated output.
        return parsed.filterKeys { !it.startsWith("_") }
    }

    // ---- bindings.kt -----------------------------------------------------------------------

    /**
     * `P` is `Panama.POINTER_TYPE` (`Long.TYPE`), and `LongLong`/`Double` are the `java.lang`
     * aliases `bindings.kt` imports. A token outside this table is a new carrier type whose FFM
     * layout this task cannot guess -- failing is the point.
     */
    private val javaTypeTokens = mapOf(
        "P" to "long",
        "Void.TYPE" to "void",
        "Integer.TYPE" to "int",
        "LongLong.TYPE" to "long",
        "Long.TYPE" to "long",
        "Double.TYPE" to "double",
        "Float.TYPE" to "float",
        "Short.TYPE" to "short",
        "Byte.TYPE" to "byte",
        "Character.TYPE" to "char",
        "Boolean.TYPE" to "boolean",
        "POINTER_TYPE" to "long",
    )

    private fun carrier(token: String, where: String): String = javaTypeTokens[token]
        ?: throw GradleException(
            "Unknown carrier type `$token` in $where. Add it to GenerateReachabilityMetadata's " +
                "javaTypeTokens table (with the FFM layout name native-image expects) before using it."
        )

    private fun parseBindings(text: String): Set<Descriptor> {
        val declarations = Regex("""val\s+\w+Handle\s*:\s*MethodHandle""").findAll(text).count()
        val callSites = Regex("""=\s*find\(""").findAll(text).count()
        val matches = Regex("""=\s*find\("(\w+)"((?:\s*,\s*[\w.]+)*)\s*\)""").findAll(text).toList()

        check(declarations == callSites) {
            "bindings.kt declares $declarations MethodHandle fields but has $callSites `= find(` " +
                "assignments; every declared handle must be initialised by a `find(...)` call for " +
                "its descriptor to be derivable."
        }
        check(matches.size == callSites) {
            "bindings.kt has $callSites `= find(` assignments but only ${matches.size} of them " +
                "match the expected `find(\"symbol\", ReturnType, ParamType...)` form. The " +
                "unmatched ones would be missing from the reachability metadata."
        }

        return matches.mapTo(LinkedHashSet()) { match ->
            val symbol = match.groupValues[1]
            val tokens = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val types = tokens.map { carrier(it, "bindings.kt find(\"$symbol\", ...)") }
            Descriptor(types.first(), types.drop(1))
        }
    }

    // ---- ShapeDowncalls.desktop.kt ---------------------------------------------------------

    private fun parseShapeVocabulary(text: String): Set<Descriptor> {
        val occurrences = Regex("""Panama\.unboundDowncallHandle\(""").findAll(text).count()
        val matches = Regex(
            """Panama\.unboundDowncallHandle\(\s*(\d+)\s*,\s*(\d+)\s*,\s*ReturnKind\.(\w+)\s*\)"""
        ).findAll(text).toList()

        check(matches.size == occurrences) {
            "ShapeDowncalls.desktop.kt has $occurrences `Panama.unboundDowncallHandle(` calls but " +
                "only ${matches.size} match the literal `(intArgs, floatArgs, ReturnKind.X)` form."
        }

        return matches.mapTo(LinkedHashSet()) { match ->
            val intArgs = match.groupValues[1].toInt()
            val floatArgs = match.groupValues[2].toInt()
            val returnType = when (val kind = match.groupValues[3]) {
                "VOID" -> "void"
                "LONG" -> "long"
                "DOUBLE" -> "double"
                else -> throw GradleException("Unknown ReturnKind.$kind in ShapeDowncalls.desktop.kt")
            }
            // `buildShape` lays out intArgs JAVA_LONGs followed by floatArgs JAVA_DOUBLEs. The
            // leading target-address argument is not part of the descriptor: it is bound by
            // `filterArguments`, so it exists on the MethodHandle but not on the FunctionDescriptor.
            Descriptor(returnType, List(intArgs) { "long" } + List(floatArgs) { "double" })
        }
    }

    // ---- Panama.kt -------------------------------------------------------------------------

    private fun parsePanamaDowncalls(text: String): Set<Descriptor> =
        Regex("""findSym\("(\w+)"\s*,\s*([\w.]+)\s*,\s*arrayOf\(([^)]*)\)\s*\)""")
            .findAll(text)
            .mapTo(LinkedHashSet()) { match ->
                val symbol = match.groupValues[1]
                val returnType = carrier(match.groupValues[2], "Panama.kt findSym(\"$symbol\", ...)")
                val params = match.groupValues[3].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    .map { carrier(it, "Panama.kt findSym(\"$symbol\", ...)") }
                Descriptor(returnType, params)
            }

    /**
     * Upcall descriptors are assembled reflectively inside the stub builders, so there is no
     * literal to read. Each builder therefore carries a `// @UpcallShape(...)` marker, and a
     * builder without one fails the build -- that is what stops a fourth stub shape from being
     * added without reaching the metadata.
     */
    private fun parseUpcallShapes(text: String): Set<Descriptor> {
        val lines = text.lines()
        val markerRegex = Regex(
            """//\s*@UpcallShape\(\s*returnType\s*=\s*"(\w+)"\s*,\s*parameterTypes\s*=\s*\[([^\]]*)]\s*\)"""
        )
        val builderRegex = Regex("""val\s+buildUpcallStub\w*\s*:""")

        val result = LinkedHashSet<Descriptor>()
        lines.forEachIndexed { index, line ->
            if (!builderRegex.containsMatchIn(line)) return@forEachIndexed
            val marker = (maxOf(0, index - 4) until index)
                .reversed()
                .firstNotNullOfOrNull { markerRegex.find(lines[it]) }
                ?: throw GradleException(
                    "Panama.kt:${index + 1} declares an upcall stub builder with no " +
                        "`// @UpcallShape(returnType = \"...\", parameterTypes = [...])` marker " +
                        "above it. Without the marker its FunctionDescriptor cannot reach the " +
                        "reachability metadata, and native-image would fail at runtime with " +
                        "MissingForeignRegistrationError."
                )
            val params = marker.groupValues[2].split(',')
                .map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            result += Descriptor(marker.groupValues[1], params)
        }
        check(result.isNotEmpty()) { "Panama.kt declares no upcall stub builders -- parser is out of date." }
        return result
    }
}

val nativeImageMetadataPath =
    "META-INF/native-image/org.thisisthepy/python-multiplatform/reachability-metadata.json"

val generateDesktopReachabilityMetadata by tasks.registering(GenerateReachabilityMetadata::class) {
    group = "native"
    description = "Derives the GraalVM foreign (FFM) reachability metadata from the desktop FFI sources"
    bindingsSource.set(layout.projectDirectory.file("src/desktopMain/kotlin/python/native/ffi/bindings.kt"))
    shapeVocabularySource.set(
        layout.projectDirectory.file("src/desktopMain/kotlin/python/native/ffi/ShapeDowncalls.desktop.kt")
    )
    panamaSource.set(layout.projectDirectory.file("src/desktopMain/kotlin/python/native/ffi/Panama.kt"))
    baseMetadata.set(layout.projectDirectory.file("native-image/reachability-metadata.base.json"))
    relativePath.set(nativeImageMetadataPath)
    outputDirectory.set(layout.buildDirectory.dir("generated/native-image-metadata/desktop"))
}
val generateWasmProxyExports = tasks.register<GenerateWasmProxyExportsTask>("generateWasmProxyExports") {
    packageName.set("python.multiplatform.generated.wasm")
    outputDirectory.set(layout.buildDirectory.dir("generated/wasmProxyExports/test"))
}

kotlin {
    // ROADMAP §10. The target is a leaf directly under `commonMain` -- deliberately not under an
    // intermediate source set, because `EmbedAPI.kt`'s `expect inline fun`s crash the compiler when
    // combined with an intermediate `expect`/`actual` (see docs/design/architecture.md).
    //
    // `nodejs()` carries the suite: the tests have to drive a real CPython Emscripten build, and
    // Node can load `python.wasm` off the filesystem with no webpack step to fight.
    //
    // `browser()` carries a *different* claim, and it is there because three of the four defects
    // ROADMAP §10 records were invisible to Node -- `cpython.mjs` failing to resolve in a webpack
    // context, `node:fs`/NODEFS having no browser equivalent, and the generated entry module's
    // shape. Each was found by hand-driving a served bundle, which is evidence that expires. The
    // browser test task is a small, deliberately chosen subset (see `wasmJsBrowserTest` below); the
    // point is not to run the suite twice but to make the browser route fail a build when it breaks.
    //
    // What makes this target able to reach CPython at all is that Kotlin 2.4.20-Beta2 *imports* its
    // linear memory (`intrinsics.memory`) instead of defining one. Emscripten's memory is handed in
    // there, so a `PyObject*` is an address Kotlin can dereference directly. See docs/platforms/wasm-design.md
    // and src/wasmJsMain/README.md.
    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
    }

    androidTarget {
        // ROADMAP §15d: without this line the Android target gets no Maven publication at all --
        // Kotlin Multiplatform only creates one when a variant is named here -- so an Android app
        // consumer could not resolve this library by coordinates no matter where it was published.
        // Every other target had a `publish<Target>PublicationToMavenLocal`; `androidTarget` had
        // none, and `./gradlew tasks --all` was the only place that showed it.
        //
        // Release only. A debug variant would double the artefact for a build type nobody consumes
        // by coordinates, and the AAR's payload (see `copyAndroidPythonAssets` below) is the
        // expensive part, not the classes.
        publishLibraryVariants("release")

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        afterEvaluate {
            val abiList = listOf("arm64-v8a", "x86_64")
            val copyAndroidPythonBinaries by tasks.creating(Copy::class) {
                dependsOn(
                    tasks.named("linkAndroidNativeArm64"),
                    tasks.named("linkAndroidNativeX64"),
                    downloadAllPythonBuilds
                )
                into("$androidBuildDir/jniLibs/")
                abiList.forEach { abi ->
                    val arch = if (abi == "arm64-v8a") "aarch64" else "x86_64"
                    from("$extractedDir/android-$arch/prefix/lib") {
                        include("libpython*.so")
                        include("lib*_python.so")
                        into(abi)
                    }
                }
            }
            // Everything staged here lands in the consumer's APK, so what it excludes is a
            // packaging decision, not a build-tree tidy-up. Measured on the release AAR before any
            // exclusion (40.1 MB on disk, 137.9 MB of entries):
            //
            //   assets/<abi>/lib/**/test          7.11 MB compressed  32.30 MB raw   x2 ABIs
            //   jni/<abi>                         5.31 / 5.09 MB      15.0 / 15.3 MB
            //   assets/<abi>/lib/** (rest)        3.16 MB             11.91 MB       x2
            //   assets/<abi>/lib/**/lib-dynload   2.46 / 2.37 MB       7.6 / 7.3 MB
            //   assets/<abi>/include              0.45 MB              1.91 MB       x2
            //   classes/manifest/etc              0.49 MB              0.52 MB
            //
            // The library itself is 0.49 MB of that. Two of the entries above are payload nothing
            // on a device ever reads, and both are dropped here:
            //
            // - `include/python$libVersion` is CPython's C headers. cinterop reads them from the
            //   extraction tree (`targetIncludePath`), never from assets, and no Kotlin source in
            //   `androidMain`, `artMain`, `androidInstrumentedTest` or `sample` opens an asset
            //   under `include/`. They were being shipped to every device for nothing.
            // - `test` is CPython's own regression suite -- 32 MB raw per ABI, 38% of the AAR --
            //   and nothing in this repository imports it.
            //
            // What is deliberately *not* excluded, so the reasoning survives: `lib-dynload` is the
            // compiled extension modules and is the only genuinely per-ABI part of the tree (the
            // pure-Python half is byte-identical between arm64-v8a and x86_64 apart from five
            // sysconfig files, verified with `diff -rq`), and `idlelib`/`ensurepip`/`tkinter` are
            // dead weight for most embedders but are ordinary stdlib that a consumer may import.
            // "CPython's own test suite" and "C headers" are provable; "nobody wants tkinter" is
            // not.
            val copyAndroidPythonAssets by tasks.creating(Copy::class) {
                dependsOn(downloadAllPythonBuilds)
                into("$androidBuildDir/assets/")
                // A `Copy` never removes what it stopped copying, and this directory is an AGP
                // asset source root -- so a tree staged by an older revision of this task keeps
                // shipping in the AAR and the APK forever. That is exactly how `include/` and
                // `test/` would have survived the exclusions above on any machine that had built
                // once before them.
                doFirst { delete("$androidBuildDir/assets/") }
                abiList.forEach { abi ->
                    val arch = if (abi == "arm64-v8a") "aarch64" else "x86_64"
                    from("$extractedDir/android-$arch/prefix/lib/python$libVersion") {
                        exclude("config-$libVersion-$arch-linux-android/")
                        exclude("test/")
                        into("$abi/lib/python$libVersion")
                    }
                }
            }
            tasks.configureEach {
                if (name.startsWith("merge") && (name.endsWith("JniLibFolders") || name.endsWith("NativeLibs"))) {
                    dependsOn(copyAndroidPythonBinaries)
                }
                if (name != "copyAndroidPythonAssets" && name.endsWith("Assets")) {
                    dependsOn(copyAndroidPythonAssets)
                }
            }

        }
    }

    jvm("desktop") {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        tasks.withType<AbstractCopyTask> {
            duplicatesStrategy = DuplicatesStrategy.WARN
        }
        val hostPlatform = when {
            System.getProperty("os.name").contains("Mac") ->
                if (System.getProperty("os.arch") == "aarch64") "macos-aarch64" else "macos-x86_64"
            System.getProperty("os.name").contains("Windows") -> "windows-x86_64"
            else -> "linux-x86_64"
        }
        tasks.withType<ProcessResources> {
            duplicatesStrategy = DuplicatesStrategy.WARN
            inputs.property("hostPlatform", hostPlatform)
            // The metadata carries no `${hostPlatform}` placeholder today: the CPython library is
            // no longer registered as an image resource (see native-image/reachability-metadata.
            // base.json for why, and manager.kt for the filesystem sidecar that replaced it). The
            // substitution is kept because any platform-scoped `resources` glob added back needs
            // it -- a native image bundles one platform, and the jar carries all four.
            filesMatching("**/reachability-metadata.json") {
                filter { line ->
                    line.replace("\${hostPlatform}", hostPlatform)
                }
            }
        }
        // `tasks` here is the *project's* container, not the desktop target's, so this block sees
        // every `Jar` in the module -- `allMetadataJar` included. That is fine for the licence and
        // was not fine for the CPython libraries, hence the name guard below.
        //
        // `allMetadataJar` is the artifact published at the root coordinate
        // `io.github.thisisthepy:python-multiplatform`, which is what every Kotlin Multiplatform
        // consumer resolves in order to compile against `commonMain`. Measured on the mavenLocal
        // publication while enabling the Android one (§15d): **87.4 MB, of which 83.0 MB was four
        // host platforms' libpython** (`lib/linux-x86_64` alone 64.7 MB compressed, 240 MB raw)
        // against 0.3 MB of actual metadata. Nothing reads `lib/` out of a metadata jar --
        // `manager.loadLibPython` reads it off the desktop *runtime* classpath, which is
        // `desktopJar` and stays unchanged.
        //
        // The sources jars never picked the libraries up in the first place (`desktopSourcesJar`
        // and `androidReleaseSourcesJar` both measure 212-218 KB with no `lib/` entry), so this
        // narrowing changes exactly one artifact.
        tasks.withType<Jar> {
            duplicatesStrategy = DuplicatesStrategy.WARN
            from(licensePath) {
                into("META-INF/LICENSE")
            }
        }
        // Issue #74. python-build-standalone ships `libpython3.14.so -> libpython3.14.so.1.0` as a
        // symlink. Gradle's `tarTree` extracts a symlink as an empty regular file, so the jar used
        // to carry a 0-byte `libpython3.14.so` (the name the loader asks for) beside the real
        // 252 MB `libpython3.14.so.1.0` that nothing asked for. Staging here:
        //   1. drops every zero-length library (a symlink stub is never a library),
        //   2. renames `libX.so.1.0` to `libX.so`, so the jar carries the real file under the name
        //      `System.mapLibraryName` produces,
        //   3. strips debug info from linux libraries, best-effort (see below).
        val stageDesktopLibraries = tasks.register<Sync>("stageDesktopLibraries") {
            dependsOn(downloadAllPythonBuilds)
            val stageDir = layout.buildDirectory.dir("desktop-libs")
            into(stageDir)
            from("$extractedDir") {
                include("macos-*$desktopFlavourSuffix/python/lib/libpython*.dylib")
                include("linux-*$desktopFlavourSuffix/python/lib/libpython*.so*")
                include("windows-*$desktopFlavourSuffix/python/python*.dll")
                include("windows-*$desktopFlavourSuffix/python/vcruntime*.dll")
                // `macos-*` matches `macos-aarch64-freethreaded` too.
                if (!pythonFreeThreaded) exclude("*-freethreaded/**")
                eachFile {
                    if (file.length() == 0L) { exclude(); return@eachFile }
                    val platform = relativePath.segments[0].removeSuffix(desktopFlavourSuffix)
                    val filename = name.replace(Regex("""(\.so)(\.\d+)+$"""), "$1")
                    path = "lib/$platform/$filename"
                }
                includeEmptyDirs = false
            }
            doLast {
                // Best-effort: GNU strip / llvm-strip are not on every build host (Apple's strip
                // cannot read ELF). When none works the unstripped library ships, and says so.
                val candidates = listOf("llvm-strip", "x86_64-linux-gnu-strip", "strip")
                stageDir.get().asFile.walkTopDown()
                    .filter { it.isFile && it.parentFile.name.startsWith("linux-") && it.name.endsWith(".so") }
                    .forEach { lib ->
                        val before = lib.length()
                        val done = candidates.any { tool ->
                            try {
                                val proc = ProcessBuilder(tool, "--strip-debug", lib.absolutePath)
                                    .redirectErrorStream(true).start()
                                proc.inputStream.readBytes()
                                proc.waitFor() == 0
                            } catch (_: java.io.IOException) { false }
                        }
                        logger.lifecycle(
                            if (done) "stripped ${lib.name}: $before -> ${lib.length()} bytes"
                            else "no ELF-capable strip found; ${lib.name} ships unstripped ($before bytes)"
                        )
                    }
            }
        }
        tasks.withType<Jar>().matching { it.name == "desktopJar" }.configureEach {
            if (configuredPythonVersion == "3.13.0" && !pythonFreeThreaded) {
                from(libPathForDesktop) {
                    include("windows-*/*")
                    include("linux-*/*")
                    include("macos-*/*")
                    into("lib")
                }
            } else {
                // Issue #74: the libraries are packed from the staged tree, not straight from the
                // extraction, so that symlinks are resolved and the linux library is stripped.
                dependsOn(stageDesktopLibraries)
                from(stageDesktopLibraries.map { it.destinationDir }) {
                    include("lib/**")
                }
            }
        }
    }

    /* Supported platforms
     * https://github.com/JetBrains/intellij-community/blob/master/plugins/kotlin/native/src/org/jetbrains/kotlin/ide/konan/NativeDefinitions.flex
     */
    listOf(
        iosArm64(), iosSimulatorArm64(), iosX64(),
        androidNativeArm64(), androidNativeX64()
    ).forEach { nativeTarget ->
        nativeTarget.apply {
            val targetABI = when(konanTarget) {
                ANDROID_ARM64 -> "arm64-v8a"
                ANDROID_X64 -> "x86_64"
                IOS_ARM64 -> "ios-arm64"
                IOS_X64 -> "ios-arm64_x86_64-simulator"
                IOS_SIMULATOR_ARM64 -> "ios-arm64_x86_64-simulator"
                else -> throw RuntimeException("Unsupported ABI: $konanTarget")
            }
            val targetExtractDir = when(konanTarget.family) {
                Family.ANDROID -> "$extractedDir/android-${if (targetABI == "arm64-v8a") "aarch64" else "x86_64"}/prefix"
                Family.IOS -> "$extractedDir/ios/Python.xcframework"
                else -> throw RuntimeException("Unsupported target family: ${konanTarget.family}")
            }
            val targetIncludePath = when(konanTarget.family) {
                Family.ANDROID -> "$targetExtractDir/include/python$libVersion"
                Family.IOS -> "$targetExtractDir/$targetABI/Python.framework/Headers"
                else -> throw RuntimeException("Unsupported target family: ${konanTarget.family}")
            }

            compilations.getByName("main").cinterops.create("python") {
                headers("$targetIncludePath/Python.h")
                packageName("python.native.ffi.bindings")
                includeDirs(targetIncludePath)
                if (konanTarget.family == Family.IOS) {
                    compilerOpts("-framework", "Python", "-F$targetExtractDir/$targetABI", "-fno-common", "-fvisibility=hidden")
                }
            }

            if (konanTarget.family == Family.ANDROID) {
                compilations.getByName("main").cinterops.create("jni_onload") {
                    defFile("src/artMain/cinterop/jni_onload.def")
                }
            }

            binaries {
                if (konanTarget.family == Family.ANDROID) {
                    sharedLib("multiplatform_python$libVersion") {
                        linkerOpts.addAll(listOf("-L$targetExtractDir/lib/", "-lpython$libVersion", "-u", "JNI_OnLoad"))

                        linkTaskProvider.configure {
                            val type = if (buildType == NativeBuildType.DEBUG) "debug" else "release"
                            val dest = file("$androidBuildDir/$type/jniLibs/$targetABI/")
                            // doLast, not the configure block itself: a bare copy {} here runs at
                            // CONFIGURATION time, so it stages whatever the previous build left in
                            // outputFile. That is why a changed .def or source produced an APK with
                            // a stale library and an UnsatisfiedLinkError that looked like a code
                            // bug -- the fix had to be built twice for the second run to pick it up.
                            doLast {
                                copy {
                                    from(outputFile)
                                    into(dest)
                                }
                            }
                        }
                        afterEvaluate {
                            val preBuild by tasks.getting
                            preBuild.dependsOn(linkTaskProvider)
                        }
                    }
                    getTest(NativeBuildType.DEBUG).linkerOpts.addAll(listOf(
                        "-L$targetExtractDir/lib/", 
                        "-lpython$libVersion",
                        "-Wl,--allow-shlib-undefined",
                        "-u", "JNI_OnLoad"
                    ))
                } else if (konanTarget.family == Family.IOS) {
                    framework {
                        baseName = "PythonMultiplatform"

                        linkerOpts.addAll(listOf(
                            "-framework", "Python", "-F$targetExtractDir/$targetABI", "-Objc"
                        ))
                    }

                    // The test executable is a separate binary from the framework above,
                    // so it needs its own linker flags to resolve the embedded CPython symbols.
                    // Python.framework has an @rpath-relative install name and is not copied
                    // next to the test binary, so an explicit -rpath is required for dyld to
                    // find it when the test runs on the simulator.
                    getTest(NativeBuildType.DEBUG).linkerOpts.addAll(listOf(
                        "-framework", "Python", "-F$targetExtractDir/$targetABI",
                        "-rpath", "$targetExtractDir/$targetABI"
                    ))
                }
            }
        }
    }
    
    sourceSets {
        val jvmMain by creating
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
        val jvmTest by creating
        jvmTest.dependsOn(commonTest)
        val desktopMain by getting {
            resources.srcDirs("src/desktopMain/resources")
            resources.srcDir(generatePythonProperties)
            // `reachability-metadata.json` is generated, not checked in -- see
            // GenerateReachabilityMetadata above. Wiring it as a resource srcDir (through the
            // task's own output provider, so the dependency is inferred) is what puts it on the
            // native-image classpath at META-INF/native-image/...
            resources.srcDir(generateDesktopReachabilityMetadata.flatMap { it.outputDirectory })
        }
        val desktopTest by getting
        desktopTest.dependsOn(jvmTest)
        val androidMain by getting
        jvmMain.dependsOn(commonMain)
        desktopMain.dependsOn(jvmMain)
        androidMain.dependsOn(jvmMain)

        // `commonTest` holds the entire object-model suite, and it used to run only on desktop and
        // the iOS simulator -- yet Android is the platform where that model was most recently
        // found broken. It runs here as an *instrumented* test: `androidUnitTest` executes on a
        // host JVM that cannot load the arm64/x86_64 `.so`, so the interpreter is unreachable
        // there. `jvmTest` (not `commonTest` directly) keeps the test hierarchy the mirror of the
        // main one -- desktopTest -> jvmTest -> commonTest, androidInstrumentedTest -> jvmTest.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.junit)
                implementation("androidx.test:runner:1.6.2")
                // On the JVM, `kotlin.test.Test` and the `assert*` functions are `expect`
                // declarations; kotlin-test-junit supplies the actuals, mapping `kotlin.test.Test`
                // onto `org.junit.Test` by typealias. Without it commonTest does not compile here,
                // and -- because AndroidJUnitRunner discovers tests by scanning the dex for
                // `org.junit.Test` -- it is also what makes the runner see these classes at all.
                implementation(libs.kotlin.test)
                implementation(libs.kotlin.test.junit)
            }
        }
        // KGP warns here ("Source Set groups can't depend on 'jvmTest' together as they belong to
        // different Kotlin Source Set Trees") because androidInstrumentedTest lives in the
        // `instrumentedTest` tree while desktopTest lives in `test`. There is no way to share
        // commonTest with an instrumented compilation without crossing that line, and the warning
        // is exactly that: both compilations build the shared sources independently, which is what
        // we want. It is why `forceGC()` needs an `actual` in androidInstrumentedTest as well as
        // in androidUnitTest -- they are two separate compilations of the same target.
        androidInstrumentedTest.dependsOn(jvmTest)

        val nativeMain by creating
        nativeMain.dependsOn(commonMain)
        try {  // when not on macOS
            val iosMain by creating  // This will fail on macOS
            val iosX64Main by getting
            val iosArm64Main by getting
            val iosSimulatorArm64Main by getting
            iosX64Main.dependsOn(iosMain)
            iosArm64Main.dependsOn(iosMain)
            iosSimulatorArm64Main.dependsOn(iosMain)
        } finally {
            val iosMain by getting
            iosMain.dependsOn(nativeMain)
        }

        // wasmJs sits directly under commonMain, as a sibling of jvmMain and nativeMain. It shares
        // nothing with either: there is no JNI, no Panama and no cinterop here, only `@WasmImport`
        // against CPython's own wasm exports.
        val wasmJsMain by getting
        wasmJsMain.dependsOn(commonMain)
        val wasmJsTest by getting {
            kotlin.srcDir(generateWasmProxyExports.map { it.outputDirectory })
        }
        wasmJsTest.dependsOn(commonTest)

        val artMain by creating {
            kotlin.srcDir("src/artMain/kotlin")
        }
        val androidNativeX64Main by getting
        val androidNativeArm64Main by getting
        artMain.dependsOn(nativeMain)
        androidNativeX64Main.dependsOn(artMain)
        androidNativeArm64Main.dependsOn(artMain)

        // `src/nativeTest` existed as a directory long before this line, and nothing pointed at
        // it -- so its `GCLeakTest.native.kt` was dead source, and the same file had been copied
        // byte-for-byte into all five target test source sets to compensate. This is the mirror of
        // the main hierarchy (nativeMain -> iosMain/artMain), which is what a test for anything in
        // `nativeMain` needs: a test placed in one target's source set is compiled for that target
        // only, and androidNative is exactly the target that goes unbuilt when that happens.
        val nativeTest by creating
        nativeTest.dependsOn(commonTest)
        listOf(
            "iosX64Test", "iosArm64Test", "iosSimulatorArm64Test",
            "androidNativeX64Test", "androidNativeArm64Test",
        ).forEach { getByName(it).dependsOn(nativeTest) }

        // ...and `artTest` completes the mirror for the other half: things true of androidNative
        // but not of iOS. Without it such a test has nowhere to live except duplicated into both
        // androidNative target source sets, which is the arrangement the comment above describes
        // as the problem.
        val artTest by creating {
            kotlin.srcDir("src/artTest/kotlin")
        }
        artTest.dependsOn(nativeTest)
        listOf("androidNativeX64Test", "androidNativeArm64Test")
            .forEach { getByName(it).dependsOn(artTest) }
    }
}

/**
 * The iOS `Python.framework` ships only the interpreter binary and headers — it carries no
 * standard library. `Py_Initialize()` therefore aborts the process with
 * "Fatal Python error: Failed to import encodings module" unless PYTHONHOME points at a
 * prefix containing `lib/python3.13`.
 *
 * The simulator distribution archive does contain that stdlib, so unpack it into the build
 * directory and hand its location to the test binary.
 */
val extractIosSimulatorStdlib by tasks.registering(Copy::class) {
    dependsOn(downloadAllPythonBuilds)
    from("$extractedDir/ios/Python.xcframework/lib/python$libVersion") {
        into("lib/python$libVersion")
    }
    from("$extractedDir/ios/Python.xcframework/ios-arm64_x86_64-simulator/lib-arm64/python$libVersion") {
        into("lib/python$libVersion")
    }
    into(layout.buildDirectory.dir("python-stdlib/ios-simulator"))
    includeEmptyDirs = false
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    dependsOn(extractIosSimulatorStdlib)
    val pythonHome = layout.buildDirectory.dir("python-stdlib/ios-simulator").get().asFile.absolutePath
    // simctl only forwards variables into the spawned process when they carry this prefix.
    environment("SIMCTL_CHILD_PYTHONHOME", pythonHome)
    environment("PYTHONHOME", pythonHome)
}

// =================================================================================================
// The standard library inside an iOS *app* -- SPEC L-10, issue #59.
//
// `extractIosSimulatorStdlib` above serves the simulator *test* binary, which `simctl` points at a
// workspace path through SIMCTL_CHILD_PYTHONHOME. An installed app has no such variable, and a path on
// this workspace's external volume parks the sandboxed app at 0% CPU (iosMain/README.md). So an app
// carries its own prefix inside its bundle, staged here per slice and copied in by the Xcode Run
// Script phase `tools/xcode/install-python.sh`; `IosPythonHome` (iosMain) finds it at run time.
// The selection rules are `IosPythonHomeLayout` in the Gradle plugin, where they are unit-tested.
// =================================================================================================

// `stageIosPythonHome_<slice>`, `stageIosPythonHome` and `stageIosPythonHomeForXcode` (prints
// PYTHON_HOME_DIR= and PYTHON_DYLIB_INFO_TEMPLATE= for the slice Xcode builds) are registered by the
// Gradle plugin's `IosPythonHomeTasks`, the same registration a consumer that applies the plugin gets
// (issue #90); only the xcframework differs -- this build's own extraction here, the plugin's
// Gradle-user-home cache there.
python.multiplatform.gradle.IosPythonHomeTasks.register(
    project = project,
    xcframework = layout.dir(provider { file("$extractedDir/ios/Python.xcframework") }),
    root = layout.buildDirectory.dir(python.multiplatform.gradle.IosPythonHomeTasks.STAGING_DIRECTORY),
    pythonVersion = provider { configuredPythonVersion },
    after = downloadPython_ios,
)

// =================================================================================================
// androidNative -- giving the target a test *run*, not just a test *link*.
//
// Every other target in this build has a task that executes its tests. androidNative had
// `androidNativeArm64TestBinaries` and nothing that ran the binary it produced, because KGP
// registers an execution task only where it knows how to reach a host: `KotlinNativeTest` for the
// build machine, `KotlinNativeSimulatorTest` for simctl. An Android device is neither, so the
// whole of `commonTest` compiled for this target on every build and had never once been executed.
//
// That gap is visible in docs/design/upcall.md: the five-platform upcall table has an empty
// androidNative row, and `537c1a0b` says it was left empty rather than estimated. It is also the
// exact situation ROADMAP §11b was in for Android/ART, where attaching the suite to a target that
// had only ever compiled it surfaced two real defects in the first twelve tests.
//
// What the run needs, and why each piece is here:
//
//   1. **The binary itself.** `test.kexe` is a normal ELF executable; `adb push` + `chmod 755` +
//      exec from `/data/local/tmp` is enough. No APK, no instrumentation, no JVM.
//   2. **libpython.** The test binary is linked `-lpython3.14` (see `getTest(DEBUG).linkerOpts`
//      above), so `libpython3.14.so` and the extension modules' shared objects have to sit
//      somewhere the dynamic loader looks -- hence `LD_LIBRARY_PATH`.
//   3. **A standard library.** `Py_Initialize()` does not fail without one, it *aborts the
//      process* ("Failed to import encodings module"), which would be reported as a run that
//      produced no tests. Same reason `extractIosSimulatorStdlib` exists above and the same reason
//      `PythonInstrumentationRunner` unpacks assets on Android/ART; here the prefix is pushed to
//      the device and `PYTHONHOME` points at it.
//   4. **Results in the same shape as every other target.** The Kotlin/Native runner's TeamCity
//      logger is the only machine-readable output it has, so its service messages are parsed back
//      into JUnit XML under `build/test-results/androidNative<Abi>Test/`. That is the directory
//      AGENTS.md says to count, and counting it is only trustworthy if a *crashed* run is
//      distinguishable from a clean one -- so a `testStarted` with no `testFinished` is written
//      out as a failure naming the exit code, rather than silently dropped. A native suite that
//      dies takes the rest of the run with it, and the difference between "212 passed" and "212
//      passed then the process died" is the whole point of running this at all.
// =================================================================================================

/** Where the payload is staged on the device. One directory per ABI; ABIs never share a run. */
fun androidNativeTestDeviceDir(abi: String) = "/data/local/tmp/pmp-nativetest-$abi"

/** Runs a command, returning its exit code and combined output. */
fun runCommand(command: List<String>): Pair<Int, String> {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    process.outputStream.close()
    val text = process.inputStream.bufferedReader().readText()
    return process.waitFor() to text
}

/**
 * Undoes TeamCity's escaping: `|n` `|r` `|'` `|[` `|]` `||` and the `|0xNNNN` form.
 * Anything else after `|` stands for itself.
 */
fun teamCityUnescape(value: String): String {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '|' || i == value.length - 1) {
            out.append(c); i++; continue
        }
        when (val e = value[i + 1]) {
            'n' -> { out.append('\n'); i += 2 }
            'r' -> { out.append('\r'); i += 2 }
            // The three Unicode line separators the Kotlin/Native logger escapes by name.
            'x' -> { out.append('\u0085'); i += 2 }
            'l' -> { out.append('\u2028'); i += 2 }
            'p' -> { out.append('\u2029'); i += 2 }
            '0' -> {
                // |0xNNNN -- a single UTF-16 unit written as hex.
                if (i + 6 <= value.length && value[i + 2] == 'x') {
                    out.append(value.substring(i + 3, minOf(i + 7, value.length)).toInt(16).toChar()); i += 7
                } else { out.append(e); i += 2 }
            }
            else -> { out.append(e); i += 2 }
        }
    }
    return out.toString()
}

/**
 * Splits a service message body into its `key='value'` attributes.
 *
 * Values are scanned character by character rather than matched with a regex: `'` is a legal
 * character inside a value (escaped as `|'`), and a value ending in `||` puts a literal `|`
 * immediately before the closing quote, so "closing quote is the first `'` not preceded by `|`"
 * is wrong on exactly the messages that carry an assertion message.
 */
fun parseServiceMessageAttributes(body: String): Map<String, String> {
    val attributes = LinkedHashMap<String, String>()
    var i = 0
    while (i < body.length) {
        while (i < body.length && body[i] != '=') {
            i++
        }
        if (i >= body.length) break
        val keyEnd = i
        var keyStart = keyEnd
        while (keyStart > 0 && !body[keyStart - 1].isWhitespace()) keyStart--
        i++ // '='
        if (i >= body.length || body[i] != '\'') continue
        i++ // opening quote
        val raw = StringBuilder()
        while (i < body.length && body[i] != '\'') {
            if (body[i] == '|' && i + 1 < body.length) {
                raw.append(body[i]).append(body[i + 1]); i += 2
            } else {
                raw.append(body[i]); i++
            }
        }
        i++ // closing quote
        attributes[body.substring(keyStart, keyEnd)] = teamCityUnescape(raw.toString())
    }
    return attributes
}

/** XML text/attribute escaping. `<`, `&` and `"` are the only ones that can appear here. */
fun xmlEscape(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")
    // The runner prints raw bytes from Python; a stray control character makes the XML unparseable.
    .map { if (it.code < 0x20 && it != '\n' && it != '\r' && it != '\t') ' ' else it }
    .joinToString("")

/**
 * Turns one run's TeamCity output into JUnit XML files under [resultsDir], and returns
 * `Triple(total, failed, ignored)`.
 *
 * [exitCode] matters: a native test binary that aborts leaves a `testStarted` with no
 * `testFinished`, and that has to become a *failure* rather than a missing row -- see the header
 * comment.
 */
fun writeNativeTestResults(
    output: String,
    resultsDir: File,
    exitCode: Int,
    label: String,
): Triple<Int, Int, Int> {
    class Case(val suite: String, val name: String) {
        var durationMs: Long = 0
        var failureMessage: String? = null
        var failureDetails: String? = null
        var ignored = false
        var finished = false
        val output = StringBuilder()
    }

    val suites = LinkedHashMap<String, MutableList<Case>>()
    var currentSuite: String? = null
    var currentCase: Case? = null

    for (line in output.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("##teamcity[") && trimmed.endsWith("]")) {
            val body = trimmed.removePrefix("##teamcity[").removeSuffix("]")
            val kind = body.substringBefore(' ')
            val attributes = parseServiceMessageAttributes(body)
            val name = attributes["name"] ?: ""
            when (kind) {
                "testSuiteStarted" -> {
                    currentSuite = name
                    suites.getOrPut(name) { mutableListOf() }
                }
                "testSuiteFinished" -> currentSuite = null
                "testStarted" -> currentCase = Case(currentSuite ?: "unknown", name)
                    .also { suites.getOrPut(it.suite) { mutableListOf() }.add(it) }
                "testFailed" -> currentCase?.apply {
                    failureMessage = attributes["message"] ?: "failed"
                    failureDetails = attributes["details"] ?: ""
                }
                "testIgnored" -> {
                    val case = currentCase ?: Case(currentSuite ?: "unknown", name)
                        .also { suites.getOrPut(it.suite) { mutableListOf() }.add(it) }
                    case.ignored = true
                    case.finished = true
                    currentCase = null
                }
                "testFinished" -> currentCase?.apply {
                    durationMs = attributes["duration"]?.toLongOrNull() ?: 0
                    finished = true
                }.also { currentCase = null }
            }
        } else {
            // Not a service message: the test's own stdout. `UpcallBoundaryCostTest` reports
            // through `println`, so this is not decoration -- it is the measurement.
            // Anything printed outside a test is kept only in `run-output.txt`; attaching it to a
            // testcase it did not come from would misattribute it.
            currentCase?.output?.append(line)?.append('\n')
        }
    }

    // A case still open at the end of the stream is a case the process died inside of.
    for (case in suites.values.flatten()) {
        if (!case.finished && case.failureMessage == null) {
            case.failureMessage =
                "the test process exited (code $exitCode) during this test and never reported a result"
            case.failureDetails = buildString {
                appendLine("androidNative test binary died mid-suite on $label.")
                appendLine("Everything after this test in the run order was never executed.")
                appendLine()
                appendLine("--- tail of the run's output ---")
                append(output.takeLast(4000))
            }
        }
    }

    resultsDir.deleteRecursively()
    resultsDir.mkdirs()

    var total = 0
    var failed = 0
    var ignored = 0
    for ((suite, cases) in suites) {
        if (cases.isEmpty()) continue
        val suiteFailures = cases.count { it.failureMessage != null }
        val suiteIgnored = cases.count { it.ignored }
        total += cases.size
        failed += suiteFailures
        ignored += suiteIgnored
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            append("<testsuite name=\"${xmlEscape(suite)}\" tests=\"${cases.size}\"")
            append(" skipped=\"$suiteIgnored\" failures=\"$suiteFailures\" errors=\"0\"")
            appendLine(" time=\"${cases.sumOf { it.durationMs } / 1000.0}\">")
            for (case in cases) {
                append("  <testcase name=\"${xmlEscape(case.name)}\"")
                append(" classname=\"${xmlEscape(case.suite)}\"")
                append(" time=\"${case.durationMs / 1000.0}\"")
                val failure = case.failureMessage
                if (failure == null && !case.ignored && case.output.isEmpty()) {
                    appendLine("/>")
                    continue
                }
                appendLine(">")
                if (case.ignored) appendLine("    <skipped/>")
                if (failure != null) {
                    appendLine("    <failure message=\"${xmlEscape(failure)}\" type=\"kotlin.AssertionError\">")
                    appendLine(xmlEscape(case.failureDetails ?: ""))
                    appendLine("    </failure>")
                }
                if (case.output.isNotEmpty()) {
                    appendLine("    <system-out>${xmlEscape(case.output.toString())}</system-out>")
                }
                appendLine("  </testcase>")
            }
            appendLine("</testsuite>")
        }
        File(resultsDir, "TEST-$suite.xml").writeText(xml)
    }
    return Triple(total, failed, ignored)
}

listOf("Arm64" to "arm64-v8a", "X64" to "x86_64").forEach { (targetSuffix, abi) ->
    val arch = if (abi == "arm64-v8a") "aarch64" else "x86_64"
    val stagingDir = layout.buildDirectory.dir("androidNativeTest/$abi")

    // The interpreter half of the payload: the shared objects the test binary is linked against,
    // and the standard library `Py_Initialize()` refuses to start without. `config-*` is excluded
    // for the same reason `copyAndroidPythonAssets` excludes it -- it is build machinery for
    // compiling extensions, not runtime.
    val stagePayload = tasks.register<Sync>("stageAndroidNative${targetSuffix}TestPayload") {
        dependsOn(downloadAllPythonBuilds)
        from("$extractedDir/android-$arch/prefix/lib") {
            include("*.so")
            include("*.so.*")
            into("lib")
        }
        from("$extractedDir/android-$arch/prefix/lib/python$libVersion") {
            exclude("config-$libVersion-*/")
            into("lib/python$libVersion")
        }
        into(stagingDir)
        includeEmptyDirs = false
    }

    tasks.register("androidNative${targetSuffix}Test") {
        group = "verification"
        description = "Runs the androidNative $abi test binary on a connected device or emulator."
        dependsOn("linkDebugTestAndroidNative$targetSuffix", stagePayload)
        // The device is not an input Gradle can fingerprint, and a green run says nothing about
        // the next one on a different device.
        outputs.upToDateWhen { false }

        val testBinary = (kotlin.targets.getByName("androidNative$targetSuffix")
            as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget)
            .binaries.getTest(NativeBuildType.DEBUG).outputFile
        val resultsRoot = layout.buildDirectory.dir("test-results/androidNative${targetSuffix}Test")
        val staged = stagingDir
        val requestedSerial = (project.findProperty("androidNativeTestSerial")?.toString()
            ?: System.getenv("ANDROID_SERIAL"))
        val testFilter = project.findProperty("androidNativeTestFilter")?.toString()
        val sdkDir = android.sdkDirectory

        doLast {
            val adb = File(sdkDir, "platform-tools/adb")
            if (!adb.isFile) {
                throw GradleException(
                    "adb not found at $adb. Set ANDROID_HOME (or sdk.dir in local.properties) to an " +
                        "SDK that has platform-tools installed."
                )
            }

            // ---- pick the device(s) ----------------------------------------------------------
            val (listCode, listOutput) = runCommand(listOf(adb.absolutePath, "devices"))
            if (listCode != 0) throw GradleException("`adb devices` failed:\n$listOutput")
            val online = listOutput.lineSequence()
                .drop(1)
                .mapNotNull { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 2 && parts[1] == "device") parts[0] else null
                }
                .toList()
            if (online.isEmpty()) {
                throw GradleException(
                    "No device or emulator is connected. `androidNative${targetSuffix}Test` runs the " +
                        "test binary on a device -- there is no host to fall back to for this target."
                )
            }

            // An x86_64 emulator cannot run the arm64 binary and vice versa, and the failure if it
            // is tried is a bare "not executable" from the shell, so the ABI is checked up front.
            val candidates = (if (requestedSerial != null) listOf(requestedSerial) else online).filter { serial ->
                val (_, abiList) = runCommand(
                    listOf(adb.absolutePath, "-s", serial, "shell", "getprop", "ro.product.cpu.abilist")
                )
                val supported = abiList.trim().split(",").map { it.trim() }
                val matches = abi in supported
                if (!matches) {
                    logger.lifecycle("androidNative$targetSuffix: skipping $serial (supports ${abiList.trim()}, needs $abi)")
                }
                matches
            }
            if (candidates.isEmpty()) {
                throw GradleException(
                    "No connected device supports $abi (connected: ${online.joinToString()}). " +
                        "Use -PandroidNativeTestSerial=<serial> to name one explicitly."
                )
            }

            val binary = testBinary
            if (!binary.isFile) throw GradleException("Test binary not found at $binary")

            // AGENTS.md: results are counted out of this directory, and a crashed run that leaves
            // the previous run's XML behind gets counted as the previous run.
            resultsRoot.get().asFile.deleteRecursively()

            var totalFailures = 0
            val summaries = mutableListOf<String>()
            for (serial in candidates) {
                val remote = androidNativeTestDeviceDir(abi)

                // Read from outside the process what the process is supposed to discover about
                // itself. `AndroidNativePlatform` used to be three hardcoded placeholders, and a
                // hardcoded value is only distinguishable from a real read by comparing against a
                // source the binary does not control -- these three properties are that source, and
                // they differ per device, so the same binary is held to a different expectation on
                // each emulator it is pushed to. See `AndroidNativeDeviceIdentityTest`.
                fun deviceProperty(name: String): String {
                    val (code, value) = runCommand(
                        listOf(adb.absolutePath, "-s", serial, "shell", "getprop", name)
                    )
                    val trimmed = value.trim()
                    if (code != 0 || trimmed.isEmpty()) {
                        throw GradleException(
                            "`adb -s $serial shell getprop $name` returned nothing (exit $code). " +
                                "The androidNative test binary is checked against this value, so " +
                                "the run cannot proceed without it."
                        )
                    }
                    return trimmed
                }
                val deviceSdk = deviceProperty("ro.build.version.sdk")
                val deviceRelease = deviceProperty("ro.build.version.release")
                val deviceAbi = deviceProperty("ro.product.cpu.abi")
                val label = "$serial (API $deviceSdk, $abi)"

                logger.lifecycle("androidNative$targetSuffix: staging to $label")
                runCommand(listOf(adb.absolutePath, "-s", serial, "shell", "mkdir", "-p", remote))
                // `--sync` is what makes this usable in a loop: the 60 MB standard library is
                // pushed once and then compared rather than re-sent (2506 files skipped in <0.1s).
                for (arguments in listOf(
                    listOf("push", "--sync", File(staged.get().asFile, "lib").absolutePath, "$remote/"),
                    listOf("push", "--sync", binary.absolutePath, "$remote/test.kexe"),
                )) {
                    val (code, text) = runCommand(listOf(adb.absolutePath, "-s", serial) + arguments)
                    if (code != 0) throw GradleException("adb ${arguments.first()} to $label failed:\n$text")
                }
                runCommand(listOf(adb.absolutePath, "-s", serial, "shell", "chmod", "755", "$remote/test.kexe"))

                val filterArgument = testFilter?.let { " --ktest_gradle_filter='$it'" } ?: ""
                val command = "cd $remote && LD_LIBRARY_PATH=$remote/lib PYTHONHOME=$remote " +
                    "PMP_DEVICE_API_LEVEL='$deviceSdk' PMP_DEVICE_RELEASE='$deviceRelease' " +
                    "PMP_DEVICE_ABI='$deviceAbi' " +
                    "./test.kexe --ktest_logger=TEAMCITY$filterArgument"
                logger.lifecycle("androidNative$targetSuffix: running on $label")
                val (exitCode, runOutput) = runCommand(listOf(adb.absolutePath, "-s", serial, "shell", command))

                // One subdirectory per device when there is more than one, so two emulators do not
                // overwrite each other's XML and the run cannot be miscounted as a single pass.
                val resultsDir = if (candidates.size > 1) File(resultsRoot.get().asFile, serial)
                    else resultsRoot.get().asFile
                val (total, failed, ignored) = writeNativeTestResults(runOutput, resultsDir, exitCode, label)
                if (total == 0) {
                    throw GradleException(
                        "The androidNative $abi test binary reported no tests on $label (exit $exitCode). " +
                            "That is what a process that aborts before the first test looks like:\n" +
                            runOutput.takeLast(4000)
                    )
                }
                totalFailures += failed
                summaries += "$label: $total tests, $failed failed, $ignored ignored (exit $exitCode)"
                File(resultsDir, "run-output.txt").writeText(runOutput)
            }

            summaries.forEach { logger.lifecycle("androidNative$targetSuffix: $it") }
            if (totalFailures > 0) {
                throw GradleException(
                    "androidNative $abi tests failed: $totalFailures failure(s). " +
                        "See ${resultsRoot.get().asFile}"
                )
            }
        }
    }
}

// =================================================================================================
// ROADMAP §10 -- staging CPython next to the wasmJs test bundle.
//
// Three things have to be true before a `wasmJs` test can reach the interpreter, and none of them
// is something the Kotlin/Wasm toolchain does on its own:
//
//   1. `python.mjs`/`python.wasm` must sit next to the compiled Kotlin, because `cpython.mjs`
//      (src/wasmJsMain/resources) imports the glue by relative specifier.
//   2. `cpython.mjs` needs the *real* build directory, not the staging copy: Emscripten derives
//      `sys.prefix` from `thisProgram` and mounts the host filesystem through NODEFS, so the stdlib
//      is found at its original path. That path is handed over in a generated `cpython-config.mjs`
//      rather than an environment variable, because `KotlinJsTest` does not forward one.
//   3. The generated import object must hand Kotlin's `intrinsics.memory` import Emscripten's
//      memory instead of the placeholder the compiler emits.
//
// (3) is the whole integration, and it is one expression. Kotlin 2.4.20-Beta2 *imports* its linear
// memory declared `min=0, max=none`; a wasm memory import accepts any memory whose limits sit
// inside its own, so an unbounded import accepts Emscripten's bounded one. Emscripten is built
// exactly as it would be alone -- no `-sIMPORTED_MEMORY`, no binary patching. Kotlin adapts.
// Ordering comes free from ES modules: the import-object module imports `cpython.mjs`, which has a
// top-level `await`, so Emscripten is fully instantiated before the import object is built.
//
// The CPython build itself is produced by `tools/wasm/build-cpython.sh` (also reachable as the
// `buildWasmPython` task below) -- CPython 3.14.2 matched to `pyemscripten_2026_0` (PEP 783),
// relinked with `wasmExports,wasmMemory` added to `-sEXPORTED_RUNTIME_METHODS`. Without those two the
// 8287 wasm exports are present in the binary but unreachable from JS, so there is nothing to hand
// `@WasmImport`; neither appears in PEP 783's ABI-sensitive list. Unlike every other platform's
// CPython this is a build, not a download: no distributor ships a python.wasm with those exports.
//
// The script stages `python.wasm`, `python.mjs` and the stdlib zip into `<repo>/.caches/wasm-runtime`
// -- the same three files an unpacked `python-multiplatform-wasm-runtime` zip holds -- and that is
// the default here. It used to be an absolute path outside the repository; when that directory was
// moved, every wasm task on every checkout silently skipped.
// =================================================================================================

val wasmPythonDir: String = (project.findProperty("wasmPythonDir")?.toString()
    ?: System.getenv("PMP_PYTHON_DIR")
    ?: rootProject.layout.projectDirectory.dir(".caches/wasm-runtime").asFile.absolutePath)

/**
 * Builds the CPython Emscripten runtime into `.caches/` and stages it at the default
 * [wasmPythonDir]. Long (a full CPython cross build) and needs network the first time (emsdk 5.0.3
 * and the CPython 3.14.2 checkout), so nothing depends on it: the wasm tasks keep their
 * skip-with-a-message contract when it has not been run.
 */
val buildWasmPython by tasks.registering(Exec::class) {
    group = "python"
    description = "Builds CPython 3.14.2 for wasm32-emscripten (pyemscripten_2026_0) into .caches/"
    val script = rootProject.layout.projectDirectory.file("tools/wasm/build-cpython.sh").asFile
    commandLine("bash", script.absolutePath, "all")
}

/**
 * Where `tools/wasm/build-cpython.sh wheels` puts the pinned compiled wheels the wasm suite loads
 * (`wasmJsTest/.../WasmCompiledWheelTest`). Not checked in: they are downloaded by URL and accepted
 * only against the sha256 the script pins.
 */
val wasmWheelsDir: String = (project.findProperty("wasmWheelsDir")?.toString()
    ?: System.getenv("PMP_WASM_WHEELS_DIR")
    ?: rootProject.layout.projectDirectory.dir(".caches/wasm-wheels").asFile.absolutePath)

/** Downloads the pinned wheels into [wasmWheelsDir]. Network, a few MB; nothing depends on it. */
val fetchWasmWheels by tasks.registering(Exec::class) {
    group = "python"
    description = "Downloads the pinned pyemscripten_2026_0 wheels the wasm suite loads into .caches/"
    val script = rootProject.layout.projectDirectory.file("tools/wasm/build-cpython.sh").asFile
    environment("WASM_WHEELS_DIR", wasmWheelsDir)
    commandLine("bash", script.absolutePath, "wheels")
}

/**
 * The test-only tail of the test bundle's `cpython-config.mjs`: where the wheels were extracted, and
 * whether their absence is a failure. Appended here, never by [cpythonConfigModule], because a
 * consumer's bundle has no business with either. All three are always written -- the test reads a
 * missing knob as "the build did not stage anything", which is a failure rather than a skip.
 */
fun testWheelConfig(sitePackages: File?, wheelsDir: File, required: Boolean): String =
    "\n// Test-only, appended by the wasmJs KotlinJsTest doFirst. See WasmCompiledWheelTest.\n" +
        "export const PMP_TEST_SITE_PACKAGES = ${groovy.json.JsonOutput.toJson(sitePackages?.absolutePath)};\n" +
        "export const PMP_TEST_WHEELS_DIR = ${groovy.json.JsonOutput.toJson(wheelsDir.absolutePath)};\n" +
        "export const PMP_TEST_WHEELS_REQUIRED = $required;\n"

/**
 * The generated `cpython-config.mjs` that `cpython.mjs` imports.
 *
 * Both constants are generated unconditionally: a named export that is missing fails the *whole* ES
 * import, not the branch that reads it, so the module's import list has to be the same in both
 * bundles.
 *
 * @param stdlibZipUrl where the standard library zip sits relative to the importing module -- a URL
 *   a browser `fetch`es and a path Node reads with `fs`. `null` only when no zip was staged at all,
 *   which leaves the interpreter dependent on a CPython source checkout being present; see
 *   `cpython.mjs`'s `nodeSettings`.
 */
fun cpythonConfigModule(pythonDir: File, stdlibZipUrl: String?): String =
    "// Generated by python-multiplatform/build.gradle.kts. Do not edit.\n" +
        "//\n" +
        "// PYTHON_DIR is a *Node* fact: NODEFS mounts the real filesystem and the interpreter chdirs\n" +
        "// here. It is not where the standard library comes from -- that is STDLIB_ZIP_URL on both\n" +
        "// hosts -- so it may be an unpacked runtime artefact rather than a CPython build directory.\n" +
        "export const PYTHON_DIR = ${groovy.json.JsonOutput.toJson(pythonDir.absolutePath)};\n" +
        "\n" +
        "// STDLIB_ZIP_URL is read by both hosts: neither MEMFS nor a browser has a stdlib until this\n" +
        "// zip is installed as `/lib/python3<minor>.zip` during preRun.\n" +
        "export const STDLIB_ZIP_URL = ${groovy.json.JsonOutput.toJson(stdlibZipUrl)};\n"

// =================================================================================================
// ROADMAP §10 -- why a *missing* interpreter can be made a build failure on demand.
//
// Every wasm task below skips when there is no CPython Emscripten build at `wasmPythonDir`, so that
// a checkout without one still builds every other target. That contract is right for a laptop and
// wrong for CI: `.github/workflows/wasm.yml` asked a runner for `wasmJsNodeTest`, the runner had no
// such build, the task skipped, and the job reported **green having run zero tests**. A green tick
// that measures nothing is worse than a red one, because nobody goes looking.
//
// `-PrequireWasmRuntime=true` turns every one of those skips into a failure that names the path it looked
// for. It is opt-in rather than the default precisely because the default has to keep a checkout
// without an Emscripten build working.
// =================================================================================================

val requireWasmRuntime: Boolean =
    project.findProperty("requireWasmRuntime")?.toString()?.toBoolean() ?: false

/**
 * `true` when [marker] is present; otherwise skips, or fails under `-PrequireWasmRuntime=true`.
 *
 * The decision is made inside `onlyIf` on purpose -- that is the same predicate that produces the
 * skip, so there is no second place for the two answers to drift apart.
 *
 * The message is *logged* before it is thrown, and that is not belt-and-braces. Gradle wraps an
 * exception raised from an `onlyIf` predicate and reports only `Could not evaluate spec for 'Task
 * satisfies onlyIf spec'` -- checked, not assumed: the first version of this printed exactly that
 * and nothing else, which would have replaced a green tick that says nothing with a red one that
 * says nothing.
 */
fun Task.wasmRuntimePresent(marker: File): Boolean {
    if (marker.isFile) return true
    val remedy = "Unpack the published runtime " +
        "(io.github.thisisthepy:python-multiplatform-wasm-runtime:$libraryVersion) and point " +
        "-PwasmPythonDir / PMP_PYTHON_DIR at it, or build one into the default location with " +
        "./gradlew :python-multiplatform:buildWasmPython (tools/wasm/build-cpython.sh)."
    val what = "$name: no CPython Emscripten build at ${marker.parentFile} " +
        "(looked for ${marker.name})."
    if (requireWasmRuntime) {
        val message = "$what -PrequireWasmRuntime was passed, so this is a failure rather than a " +
            "skip: the caller asked to run the wasm suite, and skipping would report success " +
            "having run nothing. $remedy"
        logger.error("FAILING $message")
        throw GradleException(message)
    }
    logger.lifecycle("SKIPPING $what $remedy Pass -PrequireWasmRuntime=true to make this a failure.")
    return false
}

// =================================================================================================
// ROADMAP §10 -- the same staging, for a *consumer's* browser bundle.
//
// The `KotlinJsTest` block further down stages CPython next to this module's own test bundle. That
// covers `wasmJsNodeTest` and nothing else, and the gap is not the sample's: any project that
// depends on this library and builds a `wasmJs` browser bundle fails webpack outright with
//
//     Module not found: Error: Can't resolve './cpython.mjs' in
//       '<root>/build/wasm/packages/<consumer>/kotlin'
//
// because `bindings.kt`'s `@WasmImport(MODULE, ...)` makes the *generated import object* carry
// `import * as ... from './cpython.mjs'`, and nothing puts that file into a consumer's webpack
// context. Verified rather than assumed: this library's wasmJs klib
// (`build/classes/kotlin/wasmJs/main/default/resources`) carries no `cpython.mjs`, so there is no
// artefact a consumer could unpack it from either. It is a library defect on the measure that
// matters -- an external consumer meets it identically, and cannot fix it without knowing three
// internals (the glue file, the `intrinsics.memory` placeholder, and the `@WasmExport` handoff).
//
// This task produces the directory a consumer adds to its own `wasmJsMain` resources. Everything in
// it is either this library's glue or the interpreter it was built against:
//
//   cpython.mjs         this module's resource, verbatim
//   cpython-config.mjs  generated; in a browser bundle only STDLIB_ZIP_URL is read
//   python.mjs          Emscripten's glue, loaded by cpython.mjs through a webpackIgnore import
//   python.wasm         the interpreter
//   python3.<minor>.zip the standard library, fetched into MEMFS during preRun
//
// It is *not* wired into the plugin (`python-multiplatform-gradle-plugin`), which is where a
// consumer outside this repository would have to receive it. That needs the five files above to be
// a published artifact first, and the wasm CPython build is not published anywhere yet -- it is a
// local directory named by `-PwasmPythonDir`. Recorded in ROADMAP §10.
// =================================================================================================

val stageWasmBrowserRuntime by tasks.registering {
    group = "python"
    description = "Assembles CPython's Emscripten build plus this library's glue for a consumer's " +
        "wasmJs browser bundle"

    val pythonDir = file(wasmPythonDir)
    val glue = layout.projectDirectory.file("src/wasmJsMain/resources/cpython.mjs").asFile
    val outputDir = layout.buildDirectory.dir("wasm-browser-runtime")

    // Declared so that a changed glue file or a rebuilt interpreter re-stages, and an unchanged one
    // does not re-copy 13 MB on every build.
    inputs.file(glue)
    inputs.property("wasmPythonDir", wasmPythonDir)
    inputs.files(
        providers.provider {
            if (pythonDir.resolve("python.wasm").isFile) files(
                pythonDir.resolve("python.mjs"),
                pythonDir.resolve("python.wasm"),
            ) else files()
        }
    )
    outputs.dir(outputDir)

    // Same skip-with-a-message contract the wasm test tasks have: a checkout without an Emscripten
    // CPython build must still be able to build every other target.
    onlyIf { wasmRuntimePresent(pythonDir.resolve("python.wasm")) }

    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()

        // The stdlib zip is named after the version the interpreter reports, and the build directory
        // ships exactly one. Located rather than derived from a version string: the two would be a
        // second place to keep in step, and `cpython.mjs` already reads the version out of the
        // running interpreter to decide where in MEMFS it goes.
        val stdlibZip = pythonDir.listFiles()
            ?.firstOrNull { it.name.matches(Regex("""python\d+\.\d+\.zip""")) }
            ?: throw GradleException(
                "no python<major>.<minor>.zip in $pythonDir. A browser has no NODEFS, so the " +
                    "standard library has to travel as the zip CPython's own web example fetches; " +
                    "the Emscripten build produces it next to python.wasm."
            )

        copy {
            from(pythonDir) { include("python.mjs", "python.wasm", stdlibZip.name) }
            from(glue)
            into(dir)
        }
        dir.resolve("cpython-config.mjs").writeText(
            cpythonConfigModule(pythonDir, stdlibZipUrl = "./${stdlibZip.name}")
        )
        logger.lifecycle("Staged CPython's wasm runtime for a browser bundle into $dir")
    }
}

// -------------------------------------------------------------------------------------------------
// ROADMAP §10 -- the other half of "the wiring is not in python-multiplatform-gradle-plugin". The
// plugin cannot hand a consumer a directory that only exists on this machine; it can only resolve a
// Maven coordinate. So `stageWasmBrowserRuntime`'s output is zipped and published under its own
// artifact ID, at this library's own version, so `implementation("io.github.thisisthepy:
// python-multiplatform:$version")` and the runtime it needs stay paired by construction -- there is
// no second version string for a consumer to get out of step.
//
// This does not need `-PwasmPythonDir` to be published anywhere itself: the zip is built from
// whatever this machine already staged, exactly the same bytes `wasmJsNodeTest` already runs
// against. Publishing is `./gradlew :python-multiplatform:publishWasmRuntimePublicationToMavenLocal`
// (or the aggregate `publishToMavenLocal`), same as every other target this module publishes.
// -------------------------------------------------------------------------------------------------

val wasmBrowserRuntimeZip by tasks.registering(Zip::class) {
    group = "python"
    description = "Zips stageWasmBrowserRuntime's output for the wasmRuntime Maven publication"
    dependsOn(stageWasmBrowserRuntime)
    from(layout.buildDirectory.dir("wasm-browser-runtime"))
    archiveBaseName.set("python-multiplatform-wasm-runtime")
    destinationDirectory.set(layout.buildDirectory.dir("wasm-runtime-artifact"))

    // Same skip as the task it zips: a checkout without a local Emscripten CPython build has
    // nothing to zip, and must still be able to run every other publishing task.
    onlyIf { wasmRuntimePresent(file(wasmPythonDir).resolve("python.wasm")) }
}

/**
 * Rewrites a consumer's generated Kotlin/Wasm output so that it can reach the interpreter.
 *
 * Byte-identical in intent to the two substitutions the `KotlinJsTest` block below performs on this
 * module's own test bundle, and it exists as a function because a *consumer* has to perform them
 * too — on its own webpack context, against its own module name. Neither can be done from inside
 * Kotlin: one is the compiler's `intrinsics.memory` placeholder, the other needs a value
 * (`wasmInstance.exports`) that exists only in the generated entry module's scope.
 *
 * @param dir the compile-sync output webpack reads, `build/wasm/packages/<name>/kotlin`.
 * @param modulePrefix the generated module basename, which is `<root project>-<project>`.
 */
fun patchKotlinWasmOutputForCPython(dir: File, modulePrefix: String, logger: org.gradle.api.logging.Logger) {
    val importObject = dir.listFiles()?.firstOrNull { it.name.endsWith(".import-object.mjs") }
        ?: throw GradleException("No *.import-object.mjs in $dir -- the Kotlin/Wasm output layout changed.")
    val text = importObject.readText()

    val ns = Regex("""import \* as (\w+) from ['"]\./cpython\.mjs['"];""").find(text)?.groupValues?.get(1)
        ?: throw GradleException(
            "${importObject.name} does not import ./cpython.mjs. That import is emitted because " +
                "bindings.kt declares @WasmImport against it; if it is gone, the binding module changed."
        )
    val placeholder = Regex("""memory:\s*new WebAssembly\.Memory\(\{[^}]*}\)""")
    if (!placeholder.containsMatchIn(text)) {
        if (!text.contains("memory: $ns.wasmMemory")) {
            throw GradleException(
                "${importObject.name} has no `intrinsics.memory` placeholder to replace. Kotlin used " +
                    "to emit `new WebAssembly.Memory({ initial: 0 })` there; if that changed, " +
                    "docs/platforms/wasm-design.md's integration step needs revisiting."
            )
        }
    } else {
        importObject.writeText(placeholder.replace(text, "memory: $ns.wasmMemory"))
        logger.lifecycle("Pointed ${importObject.name}'s intrinsics.memory at Emscripten's wasmMemory")
    }

    val entry = dir.listFiles()
        ?.firstOrNull {
            it.name.endsWith(".mjs") && !it.name.contains("import-object") &&
                !it.name.contains("js-builtins") && it.name.startsWith(modulePrefix)
        }
        ?: throw GradleException("No Kotlin/Wasm entry module in $dir -- the output layout changed.")
    val entryText = entry.readText()
    val handoff = "pmpSetKotlinExports"
    if (!entryText.contains(handoff)) {
        if (!entryText.contains("const exports = wasmInstance.exports")) {
            throw GradleException(
                "${entry.name} has no `const exports = wasmInstance.exports` to hand to cpython.mjs. " +
                    "Upcalls need the Kotlin instance's raw exports; if the generated entry module " +
                    "changed shape, ROADMAP §10's upcall wiring needs revisiting rather than deleting."
            )
        }
        entry.writeText(handedOffEntryModule(entryText, handoff))
        logger.lifecycle("Handed ${entry.name}'s wasm exports to cpython.mjs for upcall registration")
    }
}

/**
 * The entry module with `pmpSetKotlinExports(exports)` inserted, and the *position* is the point.
 *
 * This wiring was written against a test bundle, whose entry module ends at
 * `setWasmExports(wasmExports)` — the runner calls `startUnitTests` later, from outside — so
 * appending the handoff was enough. An **executable** bundle does not end there:
 * `binaries.executable()` makes the generated entry module finish with `exports._start()`, which is
 * Kotlin `main()`. Appended text therefore ran *after* the whole application had already executed,
 * and every upcall in it failed with `pmpRegisterUpcall returned -1` — the code for "`kotlinExports`
 * is still null".
 *
 * Observed in a browser rather than reasoned about: the sample printed sections 1-4 correctly and
 * then `could not register wasm export 'pmp_invoke'`, with the handoff sitting two lines below the
 * call that needed it.
 *
 * The `import` goes at the top rather than next to the call because ES imports are hoisted and
 * evaluated before the module body either way; putting it first is what makes that obvious.
 */
fun handedOffEntryModule(entryText: String, handoff: String): String {
    val header = "// Added by python-multiplatform's build: upcall registration needs the raw wasm\n" +
        "// exports, and this is the only scope that has them. See ROADMAP §7/§10.\n" +
        "import { $handoff } from './cpython.mjs';\n\n"
    val startCall = "exports._start();"
    val body = if (entryText.contains(startCall)) {
        // Before `_start()`, not after: `_start()` is Kotlin `main()`.
        entryText.replace(startCall, "$handoff(exports);\n\n$startCall")
    } else {
        entryText.trimEnd() + "\n\n$handoff(exports);\n"
    }
    val patched = header + body

    // The postcondition, and it is here because **no test can stand in for it**. `wasmJsBrowserTest`
    // runs the library's own test bundle, whose entry module has no `_start()` at all -- checked,
    // not assumed: reverting this function to the plain append produced a byte-identical file and
    // all ten browser tests stayed green. The bug is only expressible in an *executable* bundle, so
    // the only thing that can catch it is a check on the text at the moment it is written.
    if (patched.contains(startCall) && patched.indexOf("$handoff(exports)") > patched.indexOf(startCall)) {
        throw GradleException(
            "the upcall handoff was placed after `$startCall` in the generated entry module. " +
                "`_start()` is Kotlin `main()`, so every upcall the application makes would run with " +
                "`kotlinExports` still null and `pmpRegisterUpcall` would return -1. See ROADMAP §10."
        )
    }
    return patched
}

// -------------------------------------------------------------------------------------------------
// ROADMAP §10 -- the LinkError guard.
//
// Wasm import types are checked *exactly*, and they are checked at instantiation rather than at the
// call. So one wrong parameter type in `wasmJsMain/.../bindings.kt` does not fail to compile and
// does not fail one test: it raises a `LinkError` that takes out the whole module, and the run
// reports zero tests with a message naming neither the function nor the type.
//
// The declarations were originally read off `python.wasm`'s own type section by hand, precisely
// because they cannot be transliterated from `EmbedAPI.kt` -- `Py_ssize_t` is `Long` there and `i32`
// here, on 14 functions. A hand-read fact rots the way a checked-in metadata file does (ROADMAP §7),
// so this reads it again from the artefact on every run and fails with the offending function named.
//
// Same shape as `generateDesktopReachabilityMetadata`: derive from the thing that decides, never
// from a copy of the answer.
// -------------------------------------------------------------------------------------------------

/**
 * Reads `python.wasm`'s type, import, function and export sections and returns
 * `export name -> (parameter valtypes, result valtypes)` for every exported function.
 *
 * Only the four sections that matter are decoded; the rest are skipped by their declared size.
 */
fun readWasmExportedFunctionTypes(file: File): Map<String, Pair<List<String>, List<String>>> {
    val bytes = file.readBytes()
    var p = 0

    fun u8(): Int = bytes[p++].toInt() and 0xFF
    fun leb(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = u8()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
    fun name(): String {
        val n = leb()
        val s = String(bytes, p, n, Charsets.UTF_8)
        p += n
        return s
    }
    fun valtype(): String = when (val v = u8()) {
        0x7F -> "i32"; 0x7E -> "i64"; 0x7D -> "f32"; 0x7C -> "f64"; 0x7B -> "v128"
        0x70 -> "funcref"; 0x6F -> "externref"
        else -> "valtype:0x%02x".format(v)
    }

    require(
        bytes.size > 8 && bytes[0] == 0x00.toByte() && bytes[1] == 0x61.toByte() &&
            bytes[2] == 0x73.toByte() && bytes[3] == 0x6D.toByte()
    ) { "$file is not a wasm module" }
    p = 8

    val types = mutableListOf<Pair<List<String>, List<String>>>()
    val funcTypeIndex = mutableListOf<Int>()   // funcidx space: imported functions occupy the low end
    val exports = mutableListOf<Pair<String, Int>>()

    while (p < bytes.size) {
        val id = u8()
        val size = leb()
        val end = p + size
        when (id) {
            1 -> repeat(leb()) {
                val form = u8()
                check(form == 0x60) {
                    ("$file: type section entry 0x%02x is not a plain functype. The GC rec-group " +
                        "encodings are not decoded here; if CPython's toolchain started emitting " +
                        "them this parser needs extending rather than deleting.").format(form)
                }
                val params = List(leb()) { valtype() }
                val results = List(leb()) { valtype() }
                types += params to results
            }
            2 -> repeat(leb()) {
                name(); name()
                when (u8()) {
                    0x00 -> funcTypeIndex += leb()
                    0x01 -> { valtype(); val f = u8(); leb(); if (f == 1) leb() }
                    0x02 -> { val f = u8(); leb(); if (f == 1) leb() }
                    0x03 -> { valtype(); u8() }
                    0x04 -> { u8(); leb() }
                    else -> error("$file: unknown import descriptor")
                }
            }
            3 -> repeat(leb()) { funcTypeIndex += leb() }
            7 -> repeat(leb()) {
                val n = name()
                val kind = u8()
                val idx = leb()
                if (kind == 0x00) exports += n to idx
            }
        }
        p = end
    }

    return exports.mapNotNull { (n, idx) ->
        funcTypeIndex.getOrNull(idx)?.let { t -> types.getOrNull(t)?.let { n to it } }
    }.toMap()
}

/**
 * The `@WasmImport` declarations of `bindings.kt`, keyed by the imported C name.
 *
 * The return type is optional in the pattern because Kotlin lets a `Unit`-returning declaration
 * omit it, and `free`, `PyObject_GC_UnTrack` and `pmpCallFree` all do. Requiring it made this
 * function skip them silently -- three declarations checked by nothing, in a check whose entire
 * purpose is to notice a wrong type. The caller asserts that every `external fun` in the file was
 * matched, so a future shape change fails loudly instead of shrinking the covered set.
 */
fun readWasmImportDeclarations(file: File): Map<String, Triple<String, List<String>, String>> {
    val decl = Regex(
        """@WasmImport\(MODULE,\s*"([^"]+)"\)\s*\r?\n\s*external\s+fun\s+(\w+)\s*\(([^)]*)\)(\s*:\s*\w+)?"""
    )
    return decl.findAll(file.readText()).associate { m ->
        val (cName, ktName, params, ret) = m.destructured
        val paramTypes = params.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { it.substringAfter(':').trim() }
        cName to Triple(ktName, paramTypes, ret.substringAfter(':').trim().ifEmpty { "Unit" })
    }
}

/** Kotlin type -> wasm valtypes, over the primitive subset a `@WasmImport` may use. */
fun kotlinTypeToValtypes(type: String): List<String>? = when (type) {
    "Int" -> listOf("i32")
    "Long" -> listOf("i64")
    "Float" -> listOf("f32")
    "Double" -> listOf("f64")
    "Unit" -> emptyList()
    else -> null
}

val verifyWasmAbiSignatures by tasks.registering {
    group = "verification"
    description = "Checks wasmJs @WasmImport declarations against python.wasm's own type section " +
        "-- a mismatch is a LinkError at instantiation, not a compile error."

    val bindingsFile = layout.projectDirectory
        .file("src/wasmJsMain/kotlin/python/native/ffi/bindings.kt").asFile
    val glueFile = layout.projectDirectory.file("src/wasmJsMain/resources/cpython.mjs").asFile
    val wasmFile = file(wasmPythonDir).resolve("python.wasm")

    inputs.file(bindingsFile)
    inputs.file(glueFile)
    outputs.upToDateWhen { false }

    onlyIf { wasmRuntimePresent(wasmFile) }

    doLast {
        val actualTypes = readWasmExportedFunctionTypes(wasmFile)
        val declared = readWasmImportDeclarations(bindingsFile)
        val glueText = glueFile.readText()
        val reExported = Regex("""export const \w+ = bind\("([^"]+)"\)""")
            .findAll(glueText).map { it.groupValues[1] }.toSet()
        // The handful of imports that are JavaScript rather than CPython -- upcall registration and
        // the two `call_indirect` stand-ins. They have no entry in python.wasm's type section, so
        // all that can be checked is that the glue really defines them.
        val glueFunctions = Regex("""export function (\w+)\s*\(""")
            .findAll(glueText).map { it.groupValues[1] }.toSet()

        // Without this a change to the declaration shape would make the whole check pass on a
        // subset, which is the failure mode ROADMAP §2 records for `AssembledApiTest`: a green
        // acceptance test measuring its own scope. It has already happened here once -- requiring
        // an explicit return type quietly excluded the three `Unit`-returning declarations.
        val externalFunCount = Regex("""^\s*external\s+fun\s""", RegexOption.MULTILINE)
            .findAll(bindingsFile.readText()).count()
        check(declared.size == externalFunCount) {
            "${bindingsFile.name} has $externalFunCount `external fun` declarations but only " +
                "${declared.size} were parsed. The unparsed ones are checked by nothing."
        }
        check(declared.size > 250) {
            "only ${declared.size} @WasmImport declarations in ${bindingsFile.name} -- that is far " +
                "below the surface this target is supposed to bind"
        }
        check(actualTypes.size > 5000) {
            "only ${actualTypes.size} exported functions decoded out of ${wasmFile.name} -- " +
                "the parser is not reading the module it thinks it is"
        }

        val problems = mutableListOf<String>()
        var glueChecked = 0
        for ((cName, d) in declared.entries.sortedBy { it.key }) {
            val (ktName, paramTypes, retType) = d
            if (cName in glueFunctions) {
                glueChecked++
                continue
            }
            if (cName !in reExported) {
                problems += "$cName: declared @WasmImport, but cpython.mjs neither re-exports it " +
                    "from python.wasm nor defines it as glue, so the binding resolves to a thrower"
            }
            val real = actualTypes[cName]
            if (real == null) {
                problems += "$cName: not an exported function of ${wasmFile.name}"
                continue
            }
            val wantParams = paramTypes.flatMap {
                kotlinTypeToValtypes(it) ?: listOf("?").also { _ ->
                    problems += "$ktName: parameter type '$it' is not a wasm primitive"
                }
            }
            val wantResults = kotlinTypeToValtypes(retType) ?: listOf("?").also {
                problems += "$ktName: return type '$retType' is not a wasm primitive"
            }
            if (wantParams != real.first || wantResults != real.second) {
                problems += "$cName: bindings.kt declares (${wantParams.joinToString()}) -> " +
                    "(${wantResults.joinToString()}), python.wasm has " +
                    "(${real.first.joinToString()}) -> (${real.second.joinToString()})"
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "wasmJs ABI mismatch -- ${problems.size} problem(s). Each is a LinkError at " +
                    "instantiation, which kills the whole module rather than one call:\n  " +
                    problems.joinToString("\n  ")
            )
        }
        logger.lifecycle(
            "verifyWasmAbiSignatures: ${declared.size - glueChecked} @WasmImport declarations " +
                "agree with ${wasmFile.name}'s type section, plus $glueChecked glue functions " +
                "defined in ${glueFile.name}"
        )
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (!name.startsWith("wasmJs")) return@configureEach
    dependsOn(verifyWasmAbiSignatures)

    // ROADMAP §10 -- lifetimes. `GCLeakTest` and `WasmFinalizationTest` need to *ask* for a
    // collection, and on this target the collector is the host engine's. `--expose-gc` is the only
    // way to reach it; there is no library-callable equivalent, which is why `forceGC()` degrades to
    // a no-op when it is absent rather than pretending. Node accepts it on the command line only --
    // `NODE_OPTIONS` rejects V8 flags -- so it goes here.
    nodeJsArgs.add("--expose-gc")

    val pythonDir = file(wasmPythonDir)
    // The npm project the Kotlin/Wasm node runner actually executes out of, which is under the
    // ROOT build directory rather than this module's.
    val stagingDir = rootProject.layout.buildDirectory
        .dir("wasm/packages/${rootProject.name}-${project.name}-test/kotlin")

    onlyIf { wasmRuntimePresent(pythonDir.resolve("python.mjs")) }

    // Fetching the wheels changes what WasmCompiledWheelTest can prove, so it re-runs the suite.
    inputs.files(fileTree(wasmWheelsDir) { include("*.whl") })
        .withPropertyName("wasmWheels").optional()

    doFirst {
        val dir = stagingDir.get().asFile
        // The stdlib zip is what *both* runners get their standard library from -- see
        // `cpython.mjs`'s `installStdlibZip`. Node used to reach it through NODEFS instead, which
        // silently made every wasm test depend on a CPython **source checkout** sitting next to the
        // build directory; staging it here is what lets `wasmPythonDir` be an unpacked
        // `python-multiplatform-wasm-runtime` artefact, which is the only form CI can obtain.
        val stdlibZip = pythonDir.listFiles()?.firstOrNull { it.name.matches(Regex("""python\d+\.\d+\.zip""")) }
        copy {
            from(pythonDir) { include("python.mjs", "python.wasm") }
            if (stdlibZip != null) from(stdlibZip)
            into(dir)
        }
        dir.resolve("cpython-config.mjs").writeText(
            cpythonConfigModule(pythonDir, stdlibZipUrl = stdlibZip?.let { "./${it.name}" })
        )

        // The compiled wheels WasmCompiledWheelTest imports. Extracted rather than put on
        // `sys.path` as zips: the extension is an Emscripten side module that `dlopen` reads off
        // the filesystem, which zipimport cannot serve. Under Node the interpreter reaches this
        // host path through the NODEFS mounts in `cpython.mjs`.
        val wheels = file(wasmWheelsDir).listFiles { f -> f.name.endsWith(".whl") }?.sortedBy { it.name }.orEmpty()
        val sitePackages = dir.resolve("wasm-site-packages")
        delete(sitePackages)
        if (wheels.isNotEmpty()) {
            copy {
                wheels.forEach { from(zipTree(it)) }
                into(sitePackages)
            }
            logger.lifecycle("Extracted ${wheels.size} wheel(s) from $wasmWheelsDir for WasmCompiledWheelTest")
        } else {
            logger.lifecycle(
                "No wheels in $wasmWheelsDir -- WasmCompiledWheelTest will " +
                    (if (requireWasmRuntime) "FAIL (-PrequireWasmRuntime)" else "skip") +
                    ". Fetch them with tools/wasm/build-cpython.sh wheels (or :python-multiplatform:fetchWasmWheels)."
            )
        }
        dir.resolve("cpython-config.mjs").appendText(
            testWheelConfig(sitePackages.takeIf { wheels.isNotEmpty() }, file(wasmWheelsDir), requireWasmRuntime)
        )

        // The same two substitutions a consumer's browser bundle needs, and the reason they are a
        // shared function: see `patchKotlinWasmOutputForCPython`.
        patchKotlinWasmOutputForCPython(dir, modulePrefix = rootProject.name, logger = logger)
    }
}

// -------------------------------------------------------------------------------------------------
// ROADMAP §10 -- `wasmJsBrowserTest`, and why it runs ten tests rather than 344.
//
// Three of the four defects §10 records were invisible to Node by construction: `cpython.mjs`
// failing to resolve in a webpack context, `node:fs`/NODEFS having no browser equivalent, and the
// generated entry module's shape. All three were established by hand-driving a served distribution
// in Chromium, which is evidence with no expiry date attached -- nothing failed if they came back.
//
// So this task exists to make the browser route fail a build, not to run the suite twice. The
// filter admits `python.multiplatform.browser.*` (WasmBrowserRuntimeTest, which asserts exactly the
// things a browser decides) and `WasmSelectorsImportTest` (the JSPI intrinsic deletion, whose whole
// subject is a *host* decision, and whose regression mode is `abort()` rather than a red test).
// Everything else already runs under Node against the same Kotlin and the same interpreter.
//
// **The browser is a machine fact, so a missing one skips rather than fails**, exactly as a missing
// CPython Emscripten build already does above. `resolveChromiumFamilyBrowser` below makes that one
// decision and hands the answer to karma-chrome-launcher as `CHROME_BIN`.
//
// What the *bundle* needs -- the glue beside it and the stdlib zip where a document-relative fetch
// will look -- is in `karma.config.d/cpython.js`, because both are facts about how karma serves
// files rather than about Gradle.
// -------------------------------------------------------------------------------------------------

// The browser package is compiled into the one test bundle *both* runners load, so Node would run it
// too. `WasmBrowserRuntimeTest.theHostIsABrowserAndNotNode` exists precisely to make that loud, and
// it did -- without this line the Node run reports 350 tests, 2 failed. Excluding is the right
// direction: the alternative, teaching those tests to skip under Node, is how a browser-only
// assertion quietly stops being one.
tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("wasmJsNodeTest") {
    filter.excludeTestsMatching("python.multiplatform.browser.*")
}

tasks.named<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>("wasmJsBrowserTest") {
    filter.isFailOnNoMatchingTests = true
    filter.includeTestsMatching("python.multiplatform.browser.*")
    filter.includeTestsMatching("python.multiplatform.ffi.WasmSelectorsImportTest.*")

    // Resolved once, here, rather than probed again inside `karma.config.d/cpython.js`: the launcher
    // reads `CHROME_BIN` out of its own process environment, so handing it over is both the skip
    // decision and the configuration, from one list.
    val chromeBinary = resolveChromiumFamilyBrowser()
    if (chromeBinary != null) environment("CHROME_BIN", chromeBinary)
    onlyIf {
        // The browser is the second thing this task can be missing, and it skips for exactly the
        // same reason the interpreter does -- so `-PrequireWasmRuntime=true` has to cover it too, or CI
        // would report green having run zero browser tests by the *other* route.
        if (chromeBinary == null && requireWasmRuntime) {
            throw GradleException(
                "$name: no Chromium-family browser found, and -PrequireWasmRuntime was passed. " +
                    "Set CHROME_BIN to one (karma's ChromeHeadless launcher reads it), or install " +
                    "Chrome/Chromium."
            )
        }
        if (chromeBinary == null) {
            logger.lifecycle(
                "SKIPPING $name -- no Chromium-family browser found. Set CHROME_BIN to one " +
                    "(karma's ChromeHeadless launcher reads it), or install Chrome/Chromium."
            )
        } else {
            logger.lifecycle("$name drives $chromeBinary")
        }
        chromeBinary != null
    }
}

/**
 * The browser karma will launch, or `null`.
 *
 * `CHROME_BIN` first, because that is the knob karma-chrome-launcher itself reads and a caller who
 * sets it means it. The probe list after it is ordered by how close each browser is to the thing
 * being tested rather than by preference: all of them are Chromium, and what this suite needs from
 * one is JSPI (Chromium 137+) and Wasm GC. Whale is in the list because it is what this machine
 * has -- it reports `HeadlessChrome/150` -- and leaving it out would have meant the browser tests
 * could not run at all here.
 *
 * Deliberately not Firefox: `WasmBrowserRuntimeTest` asserts that `WebAssembly.promising` was
 * deleted, and on an engine that never had it that assertion would pass while proving nothing.
 */
fun resolveChromiumFamilyBrowser(): String? {
    System.getenv("CHROME_BIN")?.takeIf { File(it).canExecute() }?.let { return it }
    val candidates = listOf(
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "${System.getProperty("user.home")}/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Chromium.app/Contents/MacOS/Chromium",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        "/Applications/Whale.app/Contents/MacOS/Whale",
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
    )
    return candidates.firstOrNull { File(it).canExecute() }
}

android {
    namespace = "python.multiplatform"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].assets.srcDirs("src/androidMain/assets", "$androidBuildDir/assets")
    // KMP androidMain resources do not reach the AAR's classes.jar; AGP's main resources do.
    sourceSets["main"].resources.srcDir(generatePythonProperties)
    // AGP's legacy source-set API resolves that provider to files and drops the task dependency
    // (issue #72: `processReleaseJavaRes` read the output with no declared dependency), so it is
    // declared on every AGP java-resources task explicitly.
    tasks.matching { Regex("""process\w*JavaRes""").matches(it.name) }
        .configureEach { dependsOn(generatePythonProperties) }
    sourceSets["androidTest"].assets.srcDirs("$androidBuildDir/assets")
    sourceSets["debug"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/debug/jniLibs")
    sourceSets["release"].jniLibs.srcDirs("src/androidMain/jniLibs",
        "$androidBuildDir/jniLibs", "$androidBuildDir/release/jniLibs")

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }
        // Instrumented tests are the only way to exercise the Android JNI path at all: the JVM
        // unit-test JVM cannot load the arm64 .so, and every other verification target only
        // compiles rather than runs.
        //
        // The runner is a subclass rather than the stock one because `commonTest` knows nothing
        // about Android: its fixture just calls `Python3.initialize()`, which aborts the *process*
        // ("Failed to import encodings module") unless the stdlib has been unpacked out of the
        // APK assets and PYTHONHOME points at it first. The subclass does that once, before any
        // test class is loaded.
        testInstrumentationRunner = "python.multiplatform.PythonInstrumentationRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

publishing {
    publications {
        // ROADMAP §10 -- a separate artifact ID, not a classifier on the wasmJs publication: a
        // plugin resolving it needs a Maven coordinate it can name without first knowing what
        // classifiers the Kotlin Multiplatform plugin happens to attach to a KMP publication this
        // version, and a consumer who never builds a browser bundle should not need to resolve it
        // as a side effect of resolving the library at all.
        create<MavenPublication>("wasmRuntime") {
            groupId = "io.github.thisisthepy"
            artifactId = "python-multiplatform-wasm-runtime"
            version = libraryVersion
            artifact(wasmBrowserRuntimeZip)
        }
    }
    publications.withType<MavenPublication>().configureEach {
        //artifact(javadocJar)
        groupId = groupId
        artifactId = artifactId
        version = version

        pom {
            name.set("python-multiplatform")
            description.set("A multiplatform solution to use Python with Kotlin interoperably.")
            url.set("https://github.com/thisisthepy/python-multiplatform-mobile")
            licenses {
                license {
                    //name.set("The Apache License, Version 2.0")
                    //url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("thisisthepy")
                    name.set("thisisthepy")
                    email.set("thisisthepy@gmail.com")
                }
            }
            scm {
                connection.set("scm:git:github.com/thisisthepy/python-multiplatform-mobile.git")
                developerConnection.set("scm:git:ssh://github.com/thisisthepy/python-multiplatform-mobile.git")
                url.set("https://github.com/thisisthepy/python-multiplatform-mobile")
            }
        }
    }
}

//signing {
//    useInMemoryPgpKeys(
//        rootProject.extra["signing_key_id"] as String,
//        rootProject.extra["signing_secret_key"] as String,
//        rootProject.extra["signing_password"] as String
//    )
//    sign(publishing.publications)
//}


fun downloadPythonBuilds() {
    // Moved to task `downloadAllPythonBuilds`
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.CInteropProcess>().configureEach {
    dependsOn(downloadAllPythonBuilds)
}

val copyDesktopPythonBinariesForTests by tasks.registering(Copy::class) {
    // Same resolved/stripped tree the desktop jar packs (issue #74).
    val stage = tasks.named<Sync>("stageDesktopLibraries")
    dependsOn(stage)
    from(stage.map { it.destinationDir })
    into(layout.buildDirectory.dir("desktop-test-binaries"))
}

tasks.named<Test>("desktopTest") {
    dependsOn(copyDesktopPythonBinariesForTests)
    // DesktopJarLibrariesTest opens the produced jar.
    dependsOn("desktopJar")
    systemProperty("pm.desktopJar", tasks.named<Jar>("desktopJar").get().archiveFile.get().asFile.absolutePath)
    classpath += files(layout.buildDirectory.dir("desktop-test-binaries"))
    
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    )
    jvmArgs("--enable-preview", "-Djava.library.path=.")
    
    val platform = if (System.getProperty("os.name").contains("Mac")) {
        if (System.getProperty("os.arch") == "aarch64") "macos-aarch64" else "macos-x86_64"
    } else if (System.getProperty("os.name").contains("Windows")) {
        "windows-x86_64"
    } else {
        "linux-x86_64"
    }
    
    val pythonHome = File(extractedDir, "$platform$desktopFlavourSuffix/python").absolutePath
    environment("PYTHONHOME", pythonHome)
}
