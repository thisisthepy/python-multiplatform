package python.multiplatform.gradle.stubs

/**
 * `docs/design/pyi-generation-design.md` §5.3's manifest: which `pythonx` module wraps which Kotlin
 * package, and which value classes may be written as their raw underlying primitive.
 *
 * **The manifest belongs to the Python package, not to this plugin.** §5.2 is the measurement that
 * settles it -- `pythonx.compose.layout` wraps `androidx.compose.foundation.layout`, dropping
 * `foundation.`, and nothing in any artefact says so. Which Kotlin package a `pythonx` module wraps
 * is `pythonx-compose`'s design decision, and hard-coding Compose's renames into a general-purpose
 * Gradle plugin would make every other library's mapping unreachable.
 *
 * §5.3 also gives `docs/design/kotlin-extensions-in-python.md` §6's open question ("whether the allowlist
 * should be data or code, and where a downstream consumer adds to it") its answer: the same file,
 * in the same package, next to the code whose surface it describes.
 *
 * **Not verified:** no such manifest exists in `pythonx-compose` today and this shape has not been
 * agreed with that repository. What is implemented is the reader.
 */
internal data class StubManifest(
    /** Python module name to Kotlin package name. */
    val modules: Map<String, String> = emptyMap(),
    /** §3.4's allowlist: value classes whose raw underlying primitive may be written directly,
     * because the public constructor *is* the identity on it. `Dp` is on it; `TextUnit`, `Color` and
     * every `packedValue` class are not, and the reasons are measured in that section. */
    val rawPrimitiveValueClasses: Set<String> = emptySet(),
    /** Where `py.typed` goes -- the top-level **regular** package of the distribution. `null` means
     * "derive from [modules]"; see [distributionRoot]. */
    val declaredDistributionRoot: String? = null,
) {

    private val kotlinToPython: Map<String, String> = modules.entries.associate { (python, kotlin) -> kotlin to python }

    /**
     * The Pythonic module for a Kotlin package: the manifest first, then §5.3's default rule.
     *
     * `null` for a package with no counterpart at all -- `junit.runner` is not something `pythonx`
     * wraps -- and `null` for **everything** when the manifest is empty, which is §5.3's "with no
     * manifest it emits only the Kotlin-FQN stubs".
     */
    fun pythonModuleFor(kotlinPackage: String): String? {
        if (this == EMPTY) return null
        kotlinToPython[kotlinPackage]?.let { return it }
        if (kotlinPackage.startsWith("androidx.")) return "pythonx." + kotlinPackage.removePrefix("androidx.")
        return null
    }

    /**
     * `docs/design/pyi-generation-design.md` §6.1 measurement 2: deleting only `py.typed` made every
     * revealed type `Any`. The marker goes in the distribution's top-level *regular* package, which
     * for `pythonx-compose` is `pythonx/compose/` -- `pythonx` itself is a namespace package (§5.1)
     * and a marker there would be in the wrong directory.
     *
     * Derived as the longest common package prefix of the declared modules when the manifest does not
     * say, which gives `pythonx.compose` for §5.3's own example.
     */
    val distributionRoot: String?
        get() {
            declaredDistributionRoot?.let { return it }
            if (modules.isEmpty()) return null
            val parts = modules.keys.map { it.split('.') }
            val first = parts.first()
            var common = 0
            while (common < first.size && parts.all { it.size > common && it[common] == first[common] }) common++
            // A single component is the namespace package itself (`pythonx`), which cannot carry the
            // marker; two or more is a regular package.
            return if (common >= 2) first.take(common).joinToString(".") else null
        }

    companion object {
        val EMPTY = StubManifest()
    }
}

/**
 * The subset of TOML §5.3's example uses: `[section]` headers, `key = "string"`, and
 * `key = ["a", "b"]`. Comments start with `#`.
 *
 * Hand-parsed rather than pulling a TOML library onto the plugin's classpath, for the same reason
 * `ArtifactScanner` reads bytecode with ASM rather than reflection: this runs at build time in
 * somebody else's build, and every dependency added here is one that build has to resolve. The
 * grammar accepted is exactly what the format needs and a line that does not fit it is ignored
 * rather than fatal -- a manifest is written by hand in another repository, and failing a consumer's
 * build over a stray line in a data file it does not own would be the wrong trade.
 */
internal fun parseStubManifest(text: String): StubManifest {
    val modules = LinkedHashMap<String, String>()
    val valueClasses = LinkedHashSet<String>()
    var root: String? = null
    var section = ""

    text.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.removeSurrounding("[", "]").trim()
            return@forEach
        }
        val key = line.substringBefore('=').trim().trim('"')
        val value = line.substringAfter('=', missingDelimiterValue = "").trim()
        if (value.isEmpty()) return@forEach
        when {
            section == "modules" -> modules[key] = value.trim('"')
            section == "value-classes" && key == "raw-primitive-allowed" -> valueClasses += parseStringList(value)
            section == "distribution" && key == "root" -> root = value.trim('"')
        }
    }
    return StubManifest(modules, valueClasses, root)
}

private fun parseStringList(value: String): List<String> = value
    .removeSurrounding("[", "]")
    .split(',')
    .map { it.trim().trim('"') }
    .filter { it.isNotEmpty() }
