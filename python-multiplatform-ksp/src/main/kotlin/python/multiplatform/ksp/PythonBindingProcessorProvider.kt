package python.multiplatform.ksp

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import java.io.OutputStreamWriter

/**
 * The single entry point KSP loads (`META-INF/services/...SymbolProcessorProvider`).
 * `docs/design/upcall.md` §2.5: one processor module, two roles selected by
 * [OPTION_ROLE] -- a library emits a fragment, an app emits a fragment for itself and then
 * aggregates every fragment it can see.
 */
class PythonBindingProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        val moduleName = sanitiseModuleName(
            environment.options[OPTION_MODULE_NAME]
                ?: error("$OPTION_MODULE_NAME is required (see docs/design/upcall.md §2.5)"),
        )
        val excludePackages = environment.options[OPTION_EXCLUDE_PACKAGES]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val scanner = FragmentScanner(excludePackages, environment.logger)

        return when (environment.options[OPTION_ROLE]) {
            ROLE_LIBRARY -> LibraryProcessor(environment, scanner, moduleName)
            ROLE_APP, null -> AppProcessor(environment, scanner, moduleName, PackageScanFragmentDiscovery())
            else -> error("$OPTION_ROLE must be '$ROLE_LIBRARY' or '$ROLE_APP'")
        }
    }
}

private fun writeFile(
    environment: SymbolProcessorEnvironment,
    packageName: String,
    fileName: String,
    files: List<com.google.devtools.ksp.symbol.KSFile>,
    aggregating: Boolean,
    content: String,
) {
    // `Dependencies(true)` with zero explicit files is a foot-gun: KSP treats it as depending on
    // nothing rather than on everything, so a fragment appearing anywhere would never trigger
    // re-aggregation. `Dependencies.ALL_FILES` is the named constant for "recompute this file on
    // any change" -- the correct one for the aggregator, which reads the whole classpath.
    val dependencies = if (aggregating && files.isEmpty()) Dependencies.ALL_FILES else Dependencies(aggregating, *files.toTypedArray())
    environment.codeGenerator.createNewFile(dependencies, packageName, fileName).use { stream ->
        OutputStreamWriter(stream).use { it.write(content) }
    }
}

/**
 * Emits the `actual` for every `@InstallsUpcallTable expect fun` this compilation carries.
 *
 * Called from the app role only, and from round 1: the generated file references `FunctionTable`,
 * which round 2 has not written yet, and that is fine -- KSP rounds resolve symbols
 * independently, and the Kotlin compilation that follows sees both files at once. It is the same
 * forward reference the aggregator makes to fragments it discovers.
 */
private fun writeInstallSeams(
    environment: SymbolProcessorEnvironment,
    resolver: Resolver,
) {
    val seams = findInstallSeams(resolver, environment.logger)
    if (seams.isEmpty()) return
    val originating = installSeamOriginatingFiles(resolver)
    for (seam in seams) {
        environment.logger.info(
            "python-multiplatform-ksp: install seam ${seam.packageName}.${seam.simpleName}",
        )
        writeFile(
            environment,
            seam.packageName,
            installSeamFileName(seam.simpleName),
            originating,
            aggregating = true,
            content = renderInstallSeamSource(seam),
        )
    }
}

/** `python.multiplatform.role = library`: scan once, emit one fragment. */
class LibraryProcessor(
    private val environment: SymbolProcessorEnvironment,
    private val scanner: FragmentScanner,
    private val moduleName: String,
) : SymbolProcessor {
    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        // A library emits a fragment and no aggregator, so there is no `FunctionTable` here for a
        // generated `actual` to call. Saying so is worth a build failure: the alternative is an
        // `expect` with no `actual`, reported against the user's commonMain with nothing pointing
        // at the role that decided it.
        for (seam in findInstallSeams(resolver, environment.logger)) {
            environment.logger.error(
                "@$INSTALL_SEAM_ANNOTATION on ${seam.packageName}.${seam.simpleName}: only an app-role module " +
                    "aggregates a FunctionTable. Move it to the module that produces the binary, or set " +
                    "`pythonBindings { role.set(\"app\") }` on this one.",
            )
        }

        val result = scanner.scan(resolver, moduleName)
        if (result.fragment.entries.isEmpty() && result.fragment.classes.isEmpty()) return emptyList()

        writeFile(
            environment,
            FRAGMENTS_PACKAGE,
            fragmentObjectName(moduleName),
            result.originatingFiles,
            aggregating = true,
            content = renderFragmentSource(result.fragment),
        )
        return emptyList()
    }
}

/**
 * `python.multiplatform.role = app`: round 1 does what [LibraryProcessor] does for the app's own
 * sources; round 2 discovers every `Fragment_*` visible on the classpath (its own, just
 * generated, plus every dependency's) and emits the aggregator. See
 * `docs/design/upcall.md` §2.3/§6 for why this needs two rounds -- round 1's own fragment
 * has to exist as a compiled/generated symbol before [discovery] can see it alongside the
 * others.
 *
 * Round 1 also emits the `actual` for every `@InstallsUpcallTable expect fun`
 * ([writeInstallSeams]), which is how shared code reaches a `FunctionTable` it cannot name.
 */
class AppProcessor(
    private val environment: SymbolProcessorEnvironment,
    private val scanner: FragmentScanner,
    private val moduleName: String,
    private val discovery: FragmentDiscovery,
) : SymbolProcessor {
    private var round = 0

    override fun process(resolver: Resolver): List<KSAnnotated> {
        round++

        if (round == 1) {
            writeInstallSeams(environment, resolver)
            val result = scanner.scan(resolver, moduleName)
            if (result.fragment.entries.isNotEmpty() || result.fragment.classes.isNotEmpty()) {
                writeFile(
                    environment,
                    FRAGMENTS_PACKAGE,
                    fragmentObjectName(moduleName),
                    result.originatingFiles,
                    aggregating = true,
                    content = renderFragmentSource(result.fragment),
                )
            }
            return emptyList()
        }

        if (round == 2) {
            val fragments = discovery.discoverFragments(resolver)
            environment.logger.info("python-multiplatform-ksp: aggregating ${fragments.size} fragment(s): $fragments")
            // Not `Dependencies(false)`: the doc's open question #5 flags this as unresolved --
            // tracking individual fragment files would let a source change outside this module
            // skip re-aggregation, but discovery runs over the whole classpath, not a fixed file
            // set, so there is nothing narrower to hand Dependencies short of `isAllSources`.
            writeFile(
                environment,
                AGGREGATOR_PACKAGE,
                "FunctionTable",
                emptyList(),
                aggregating = true,
                content = renderAggregatorSource(fragments),
            )
        }

        return emptyList()
    }
}
