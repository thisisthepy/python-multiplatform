package python.multiplatform.ksp

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility

/** `python.multiplatform.reflection.InstallsUpcallTable`, matched by qualified name. */
const val INSTALL_SEAM_ANNOTATION = "python.multiplatform.reflection.InstallsUpcallTable"

private const val INSTALL_SEAM_SIMPLE_NAME = "InstallsUpcallTable"

/** The generated file's name, per seam; the package it lands in is the seam's own. */
fun installSeamFileName(simpleName: String): String = "InstallUpcallTable_$simpleName"

/**
 * One `@InstallsUpcallTable expect fun` the compilation carries -- everything
 * [renderInstallSeamSource] needs, with no KSP type in it, for the same reason
 * [CallableEntryModel] carries none.
 */
data class InstallSeamModel(
    val packageName: String,
    val simpleName: String,
    /** `internal` seams get an `internal actual`; an `expect`/`actual` pair must agree. */
    val isInternal: Boolean,
)

/**
 * Finds every install seam in this compilation, rejecting the shapes whose generated `actual`
 * would not match its `expect`.
 *
 * A leaf compilation sees its whole source-set closure -- `commonMain` included, which is what
 * lets the fragment scanner pick up common declarations in the first place -- so the `expect`
 * written in `commonMain` is visible from here even though the `actual` this produces is not
 * visible from there.
 *
 * Every rejection is [KSPLogger.error] rather than a silent skip: skipping leaves an `expect`
 * with no `actual`, which surfaces later as a Kotlin error pointing at the user's `commonMain`
 * with no hint that a processor decided against it.
 */
fun findInstallSeams(resolver: Resolver, logger: KSPLogger): List<InstallSeamModel> =
    resolver.getAllFiles()
        .flatMap { it.declarations }
        .filterIsInstance<KSFunctionDeclaration>()
        .filter { it.hasInstallSeamAnnotation() }
        .mapNotNull { it.toSeamModel(logger) }
        .toList()

/** The files the seams were declared in, for `Dependencies`. */
fun installSeamOriginatingFiles(resolver: Resolver): List<KSFile> =
    resolver.getAllFiles()
        .flatMap { it.declarations }
        .filterIsInstance<KSFunctionDeclaration>()
        .filter { it.hasInstallSeamAnnotation() }
        .mapNotNull { it.containingFile }
        .distinct()
        .toList()

private fun KSFunctionDeclaration.hasInstallSeamAnnotation(): Boolean =
    annotations.any { annotation ->
        // Same two-step as BindingPolicy.hasPythonInternal: the cheap name check first, and only
        // then the resolution that would otherwise run over every annotation on every symbol.
        annotation.shortName.asString() == INSTALL_SEAM_SIMPLE_NAME &&
            annotation.annotationType.resolve().declaration.qualifiedName?.asString() == INSTALL_SEAM_ANNOTATION
    }

private fun KSFunctionDeclaration.toSeamModel(logger: KSPLogger): InstallSeamModel? {
    val name = simpleName.asString()

    if (parentDeclaration != null) {
        logger.error("@$INSTALL_SEAM_SIMPLE_NAME must be on a top-level function; '$name' is a member", this)
        return null
    }
    if (Modifier.EXPECT !in modifiers) {
        logger.error(
            "@$INSTALL_SEAM_SIMPLE_NAME must be on an `expect fun`: the `actual` is generated into each " +
                "target's own compilation, which is the only place that can name FunctionTable. '$name' is not expect.",
            this,
        )
        return null
    }
    if (parameters.isNotEmpty() || extensionReceiver != null) {
        logger.error("@$INSTALL_SEAM_SIMPLE_NAME function '$name' must take no parameters and no receiver", this)
        return null
    }
    val returnsUnit = returnType?.resolve()?.declaration?.qualifiedName?.asString() ?: "kotlin.Unit"
    if (returnsUnit != "kotlin.Unit") {
        logger.error("@$INSTALL_SEAM_SIMPLE_NAME function '$name' must return Unit, not $returnsUnit", this)
        return null
    }
    val visibility = getVisibility()
    if (visibility != Visibility.PUBLIC && visibility != Visibility.INTERNAL) {
        logger.error("@$INSTALL_SEAM_SIMPLE_NAME function '$name' must be public or internal, not $visibility", this)
        return null
    }

    return InstallSeamModel(
        packageName = packageName.asString(),
        simpleName = name,
        isInternal = visibility == Visibility.INTERNAL,
    )
}

/**
 * The generated `actual`. One statement, and the point of the whole seam: this file is emitted
 * into the leaf compilation, which is the only source set that can name `FunctionTable`.
 *
 * `@PythonInternal` on it is not decoration. The function is an ordinary public top-level
 * declaration in the *user's* package, so an incremental round that hands the scanner its own
 * previous output would otherwise put `<pkg>.<name>` in the table as a callable Python could
 * invoke to reinstall the table underneath itself.
 */
fun renderInstallSeamSource(seam: InstallSeamModel): String = buildString {
    appendLine("// GENERATED by python-multiplatform-ksp. Do not edit.")
    appendLine("package ${seam.packageName}")
    appendLine()
    appendLine("/**")
    appendLine(" * The `actual` for `@$INSTALL_SEAM_SIMPLE_NAME ${seam.simpleName}`.")
    appendLine(" *")
    appendLine(" * `$AGGREGATOR_PACKAGE.FunctionTable` is generated into this target's own")
    appendLine(" * compilation and is unresolvable from any source set that compilation depends on,")
    appendLine(" * so this one line cannot be written anywhere shared code could reach it.")
    appendLine(" */")
    appendLine("@python.multiplatform.reflection.PythonInternal")
    append(if (seam.isInternal) "internal actual fun " else "actual fun ")
    appendLine("${seam.simpleName}() {")
    appendLine("    $AGGREGATOR_PACKAGE.FunctionTable.installInto()")
    appendLine("}")
}
