package python.multiplatform.ffi.pythonx

import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.TypeTag

/**
 * A table built to make the Pythonic naming rule (issue #131) **collide**, backed by Kotlin this
 * repository owns.
 *
 * Every entry is here to say one thing about the alias policy in `KotlinSurface.kt`
 * (`pythonic_aliases`): an alias is served only when it is unambiguous in its namespace.
 *
 * | entry | namespace | what it pins |
 * |---|---|---|
 * | `toURL` / `toUrl` | module | two Kotlin names, one alias (`to_url`): no alias at all, both Kotlin names work |
 * | `fooBar` / `foo_bar` | module | the alias of one is the Kotlin name of the other: `foo_bar` is the Kotlin `foo_bar`, never `fooBar` |
 * | `rememberTextFieldState` | module | the ordinary case: `remember_text_field_state`, keywords both ways, a Kotlin default |
 * | `clicked(onClick, on_click)` | keywords | `onClick`'s alias is the other parameter's Kotlin name: `on_click=` is that parameter |
 * | `pair(xURL, xUrl)` | keywords | two parameters, one alias (`x_url`): neither gets it |
 * | `box` / [BOX] | proxy | a receiver type for the member namespace |
 * | `ext.labelURL` / `ext.labelUrl` on [BOX] | proxy | two members, one alias (`label_url`): none |
 * | `ext.fillMax` on [BOX] | proxy | the ordinary member case: `fill_max` |
 * | `Box.selectedIndex` (`var`) | proxy | a property read and written through its alias `selected_index` |
 *
 * [ProxyOnly] is a second fragment for the proxy layer on its own (`PythonProxySource`), with no
 * binding layer: module aliases and keyword aliases have to work there too.
 */
object PythonicNameShapedFragment : FunctionTableFragment {

    override val moduleName: String = "test_pythonic_names"

    const val PACKAGE = "pythonic.names"

    const val BOX = "$PACKAGE.Box"

    private const val STRING = "kotlin.String"

    private const val INT = "kotlin.Int"

    override fun entries(): List<ExposedCallable> = listOf(
        stringFunction("toURL") { "toURL:" + it },
        stringFunction("toUrl") { "toUrl:" + it },
        ExposedCallable(
            name = "$PACKAGE.fooBar", arity = 0, paramTypes = emptyList(), returnType = TypeTag.STRING,
            paramNames = emptyList(), paramTypeNames = emptyList(), returnTypeName = STRING,
        ) { "fooBar" },
        ExposedCallable(
            name = "$PACKAGE.foo_bar", arity = 0, paramTypes = emptyList(), returnType = TypeTag.STRING,
            paramNames = emptyList(), paramTypeNames = emptyList(), returnTypeName = STRING,
        ) { "foo_bar" },
        rememberTextFieldState("$PACKAGE.rememberTextFieldState"),
        ExposedCallable(
            name = "$PACKAGE.clicked", arity = 2,
            paramTypes = listOf(TypeTag.INT, TypeTag.INT), returnType = TypeTag.STRING,
            paramNames = listOf("onClick", "on_click"), paramTypeNames = listOf(INT, INT), returnTypeName = STRING,
        ) { args -> "onClick=${args[0]}, on_click=${args[1]}" },
        ExposedCallable(
            name = "$PACKAGE.pair", arity = 2,
            paramTypes = listOf(TypeTag.INT, TypeTag.INT), returnType = TypeTag.STRING,
            paramNames = listOf("xURL", "xUrl"), paramTypeNames = listOf(INT, INT), returnTypeName = STRING,
        ) { args -> "xURL=${args[0]}, xUrl=${args[1]}" },
        ExposedCallable(
            name = "$PACKAGE.box", arity = 1,
            paramTypes = listOf(TypeTag.STRING), returnType = TypeTag.OBJECT,
            paramNames = listOf("label"), paramTypeNames = listOf(STRING), returnTypeName = BOX,
        ) { args -> PythonicBox(args[0] as String) },
        ExposedCallable(
            name = "$PACKAGE.describeBox", arity = 1,
            paramTypes = listOf(TypeTag.OBJECT), returnType = TypeTag.STRING,
            paramNames = listOf("box"), paramTypeNames = listOf(BOX), returnTypeName = STRING,
        ) { args -> (args[0] as PythonicBox).describe() },
        boxExtension("labelURL") { box, _ -> box.plus("labelURL") },
        boxExtension("labelUrl") { box, _ -> box.plus("labelUrl") },
        ExposedCallable(
            name = "$PACKAGE.ext.fillMax", arity = 2,
            paramTypes = listOf(TypeTag.OBJECT, TypeTag.FLOAT), returnType = TypeTag.OBJECT,
            paramNames = listOf("<receiver>", "maxFraction"), paramTypeNames = listOf(BOX, "kotlin.Float"),
            returnTypeName = BOX, isExtension = true, receiverTypeName = BOX,
            paramHasDefault = listOf(false, true),
        ) { args ->
            (args[0] as PythonicBox).plus(if (args[1] == null) "fillMax()" else "fillMax(${args[1]})")
        },
        ExposedCallable(
            name = "$BOX.selectedIndex", arity = 0, paramTypes = emptyList(), returnType = TypeTag.INT,
            kind = CallableKind.GETTER, paramNames = emptyList(), paramTypeNames = emptyList(),
            returnTypeName = INT, receiverTypeName = BOX,
        ) { args -> (args[0] as PythonicBox).selectedIndex },
        ExposedCallable(
            name = "$BOX.selectedIndex=", arity = 1, paramTypes = listOf(TypeTag.INT), returnType = TypeTag.UNIT,
            kind = CallableKind.SETTER, paramNames = listOf("value"), paramTypeNames = listOf(INT),
            returnTypeName = "kotlin.Unit", receiverTypeName = BOX, paramHasDefault = listOf(false),
        ) { args ->
            (args[0] as PythonicBox).selectedIndex = (args[1] as Number).toInt()
            Unit
        },
    )

    /** The proxy layer on its own: one package, rendered by `PythonProxySource` with no binding layer. */
    object ProxyOnly : FunctionTableFragment {

        const val PACKAGE = "pythonic.proxyonly"

        override val moduleName: String = "test_pythonic_names_proxy_only"

        override fun entries(): List<ExposedCallable> = listOf(
            rememberTextFieldState("$PACKAGE.rememberTextFieldState"),
            ExposedCallable(
                name = "$PACKAGE.toURL", arity = 1, paramTypes = listOf(TypeTag.STRING), returnType = TypeTag.STRING,
                paramNames = listOf("s"), paramTypeNames = listOf(STRING), returnTypeName = STRING,
            ) { args -> "toURL:" + args[0] },
            ExposedCallable(
                name = "$PACKAGE.toUrl", arity = 1, paramTypes = listOf(TypeTag.STRING), returnType = TypeTag.STRING,
                paramNames = listOf("s"), paramTypeNames = listOf(STRING), returnTypeName = STRING,
            ) { args -> "toUrl:" + args[0] },
        )
    }

    /**
     * `rememberTextFieldState(initialText: String, maxLength: Int = <default>)`. The body is written
     * the way a walked defaulted body is (`ArtifactScanner.presenceBranchedCall`): `null` in the
     * defaulted slot means "not written", and the label says so.
     */
    private fun rememberTextFieldState(name: String) = ExposedCallable(
        name = name, arity = 2,
        paramTypes = listOf(TypeTag.STRING, TypeTag.INT), returnType = TypeTag.STRING,
        paramNames = listOf("initialText", "maxLength"), paramTypeNames = listOf(STRING, INT),
        returnTypeName = STRING, paramHasDefault = listOf(false, true),
    ) { args -> "state(${args[0]}" + (if (args[1] == null) "" else ", max=${args[1]}") + ")" }

    private fun stringFunction(leaf: String, body: (String) -> String) = ExposedCallable(
        name = "$PACKAGE.$leaf", arity = 1, paramTypes = listOf(TypeTag.STRING), returnType = TypeTag.STRING,
        paramNames = listOf("s"), paramTypeNames = listOf(STRING), returnTypeName = STRING,
    ) { args -> body(args[0] as String) }

    private fun boxExtension(leaf: String, body: (PythonicBox, List<Any?>) -> PythonicBox) = ExposedCallable(
        name = "$PACKAGE.ext.$leaf", arity = 1, paramTypes = listOf(TypeTag.OBJECT), returnType = TypeTag.OBJECT,
        paramNames = listOf("<receiver>"), paramTypeNames = listOf(BOX), returnTypeName = BOX,
        isExtension = true, receiverTypeName = BOX, paramHasDefault = listOf(false),
    ) { args -> body(args[0] as PythonicBox, args.drop(1)) }
}

/** What a `pythonic.names.Box` is for [PythonicNameShapedFragment]: a label, a trail, and one `var`. */
class PythonicBox(private val label: String, private val trail: List<String> = emptyList()) {

    var selectedIndex: Int = 0

    fun plus(step: String): PythonicBox = PythonicBox(label, trail + step).also { it.selectedIndex = selectedIndex }

    fun describe(): String = (listOf(label) + trail).joinToString(" -> ") + " [selected=$selectedIndex]"
}
