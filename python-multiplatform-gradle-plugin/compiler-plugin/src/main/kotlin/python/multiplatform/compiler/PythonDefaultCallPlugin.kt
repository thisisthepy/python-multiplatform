@file:OptIn(org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class)

package python.multiplatform.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.reportLog
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.InlineClassRepresentation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.defaultValueForType
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * The names the artefact walker writes into a generated `ArtifactFragment_<pkg>.kt` for a klib
 * `@Composable` with more defaulted parameters than pure source can branch over
 * (`KlibKnmScanner.composableCandidateOrNull`). They are private functions of the generated file,
 * matched here by name, and both throw when called: a build without this plugin fails loudly at
 * the first such call instead of silently passing wrong defaults.
 *
 * Kept in step by hand with `ArtifactRendering.kt` (`DEFAULT_CALL_MARKER`, `OMITTED_MARKER`) in the
 * Gradle plugin, which cannot share a constant with this module: the two are compiled by different
 * Kotlin compilers (see this module's `build.gradle.kts`).
 */
internal const val DEFAULT_CALL_MARKER = "__pympDefaultCall"
internal const val OMITTED_MARKER = "__pympOmitted"

/** The name Compose's `ComposerParamTransformer` gives the first default bitmask; later ones are `$default1`, ... */
private const val DEFAULT_PARAMETER_PREFIX = "\$default"

class PythonDefaultCallRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String get() = "org.thisisthepy.python.multiplatform.defaults"
    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        IrGenerationExtension.registerExtension(PythonDefaultCallExtension { configuration.reportLog(it) })
    }
}

/**
 * Rewrites `__pympDefaultCall(f(a0, a1, ...), mask0, mask1, ...)` into the call of `f` itself with
 * its Compose `$default` bitmasks replaced by `mask0`, `mask1`, ..., and every `__pympOmitted<T>()`
 * among its arguments replaced by the zero value of `T`.
 *
 * It must run **after** the Compose compiler plugin: only then does `f`'s IR declaration carry the
 * `$default` parameters (Compose's `ComposerParamTransformer` adds them to the klib's declarations
 * inside the consumer's own compilation). The compiler gives plugins no ordering API, so this
 * extension checks instead of assuming: a marker whose callee has no `$default` parameter is a
 * compilation error naming the cause.
 */
internal class PythonDefaultCallExtension(
    private val log: (String) -> Unit = {},
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val rewriter = DefaultCallRewriter()
        moduleFragment.transformChildrenVoid(rewriter)
        // Evidence in a build log (`--info`) that this extension ran in a compilation and what it did:
        // a clean compile alone cannot tell "rewritten" from "never invoked".
        if (rewriter.rewritten > 0) {
            log("python-multiplatform defaults: rewrote ${rewriter.rewritten} $DEFAULT_CALL_MARKER call(s) in ${moduleFragment.name}")
        }
    }
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
private class DefaultCallRewriter : IrElementTransformerVoid() {

    /** How many marker calls this pass replaced. */
    var rewritten: Int = 0

    override fun visitCall(expression: IrCall): IrExpression {
        val name = expression.symbol.owner.name.asString()
        if (name == OMITTED_MARKER) return zeroValueOf(expression.type, expression.startOffset, expression.endOffset)
        if (name != DEFAULT_CALL_MARKER) return super.visitCall(expression)

        val wrapped = expression.arguments.getOrNull(0)
            ?: error("$DEFAULT_CALL_MARKER without its call argument")
        val masks = expression.arguments.drop(1)
        val target = findDefaultedCall(wrapped)
            ?: error(
                "$DEFAULT_CALL_MARKER wraps no call that has a `$DEFAULT_PARAMETER_PREFIX` parameter. " +
                    "The python-multiplatform compiler plugin ran before the Compose compiler plugin, " +
                    "or the wrapped function is not a @Composable with default arguments.",
            )
        var maskIndex = 0
        target.symbol.owner.parameters.forEachIndexed { index, parameter ->
            if (parameter.kind != IrParameterKind.Regular) return@forEachIndexed
            if (!parameter.name.asString().startsWith(DEFAULT_PARAMETER_PREFIX)) return@forEachIndexed
            target.arguments[index] = masks.getOrNull(maskIndex)
                ?: IrConstImpl.int(expression.startOffset, expression.endOffset, parameter.type, 0)
            maskIndex += 1
        }
        rewritten += 1
        // The marker is gone; what it wrapped stands in its place. Visited afterwards so the
        // `__pympOmitted()` arguments inside it are replaced too.
        return wrapped.transform(this, null)
    }

    /** The first call under [root], [root] included, whose callee has a `$default` parameter. */
    private fun findDefaultedCall(root: IrExpression): IrCall? {
        var found: IrCall? = null
        val finder = object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (found == null) element.acceptChildrenVoid(this)
            }

            override fun visitCall(expression: IrCall) {
                if (found != null) return
                val hasDefault = expression.symbol.owner.parameters.any {
                    it.kind == IrParameterKind.Regular && it.name.asString().startsWith(DEFAULT_PARAMETER_PREFIX)
                }
                if (hasDefault) found = expression else expression.acceptChildrenVoid(this)
            }
        }
        root.accept(finder, null)
        return found
    }

    /**
     * What Compose itself passes for an argument the `$default` mask says to ignore: `null` for a
     * nullable type, the zero constant for a primitive, and for a non-JVM value class its primary
     * constructor over the zero value of the underlying type.
     */
    private fun zeroValueOf(type: IrType, startOffset: Int, endOffset: Int): IrExpression {
        if (type !is IrSimpleType || type.isMarkedNullable()) return IrConstImpl.constNull(startOffset, endOffset, type)
        val klass = type.classOrNull?.owner
        val underlying = (klass?.valueClassRepresentation as? InlineClassRepresentation<IrSimpleType>)?.underlyingType
        val constructor = klass?.primaryConstructor
        if (klass == null || underlying == null || constructor == null) {
            return IrConstImpl.defaultValueForType(startOffset, endOffset, type)
        }
        return IrConstructorCallImpl.fromSymbolOwner(startOffset, endOffset, type, constructor.symbol).also {
            it.arguments[0] = zeroValueOf(underlying, startOffset, endOffset)
        }
    }
}
