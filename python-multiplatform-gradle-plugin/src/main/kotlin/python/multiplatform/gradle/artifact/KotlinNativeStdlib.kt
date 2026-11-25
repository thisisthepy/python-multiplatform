package python.multiplatform.gradle.artifact

import java.io.File

/**
 * The Kotlin/Native stdlib klib (`<distribution>/klib/common/stdlib`), which the klib walker reads for
 * **types only** (#191): a consumer's resolved klibs never include it, because it ships inside the
 * Kotlin/Native distribution, so without it every stdlib type in a signature (`CoroutineContext`,
 * `KProperty<*>`, `Throwable`) is unknown on Kotlin/Native while the jar walker reads it from the JDK
 * classpath. It is added to the walk's classpath, never walked for bindings itself.
 *
 * [nativeHome] (`kotlin.native.home`) wins when set. Otherwise the distribution of [kotlinVersion]
 * under [konanDataDir] (`kotlin-native-prebuilt-<host>-<version>`, the name the Kotlin Gradle Plugin
 * gives it). `null` when neither has a stdlib, and the walk proceeds without it, as before.
 */
internal fun kotlinNativeStdlibKlib(nativeHome: File?, konanDataDir: File, kotlinVersion: String?): File? {
    fun stdlibOf(distribution: File): File? =
        File(distribution, "klib/common/stdlib").takeIf { isKlibArtifact(it) }
    if (nativeHome != null) return stdlibOf(nativeHome)
    if (kotlinVersion.isNullOrBlank()) return null
    val candidates = konanDataDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("kotlin-native-prebuilt-") && file.name.endsWith("-$kotlinVersion")
    } ?: return null
    return candidates.sortedBy { it.name }.firstNotNullOfOrNull(::stdlibOf)
}

/**
 * [kotlinNativeStdlibKlib] for [project], resolved lazily (at execution, when the Kotlin Gradle Plugin
 * has put its distribution in place): `kotlin.native.home`, else `konan.data.dir` / `KONAN_DATA_DIR` /
 * `~/.konan` with the applied Kotlin Gradle Plugin's version. The version is read by reflection
 * (`getKotlinPluginVersion(Project)`), as this plugin reaches the Kotlin Gradle Plugin everywhere else,
 * so it has no compile-time dependency on it.
 */
internal fun kotlinNativeStdlibFiles(project: org.gradle.api.Project): org.gradle.api.provider.Provider<List<File>> {
    val nativeHome = project.providers.gradleProperty("kotlin.native.home")
    val dataDir = project.providers.gradleProperty("konan.data.dir")
        .orElse(project.providers.environmentVariable("KONAN_DATA_DIR"))
        .orElse(System.getProperty("user.home") + "/.konan")
    val version = appliedKotlinPluginVersion(project)
    return project.provider {
        listOfNotNull(kotlinNativeStdlibKlib(nativeHome.orNull?.let(::File), File(dataDir.get()), version))
    }
}

private fun appliedKotlinPluginVersion(project: org.gradle.api.Project): String? {
    val kotlinExtension = project.extensions.findByName("kotlin") ?: return null
    return runCatching {
        Class.forName("org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt", false, kotlinExtension.javaClass.classLoader)
            .getMethod("getKotlinPluginVersion", org.gradle.api.Project::class.java)
            .invoke(null, project) as? String
    }.getOrNull()
}
