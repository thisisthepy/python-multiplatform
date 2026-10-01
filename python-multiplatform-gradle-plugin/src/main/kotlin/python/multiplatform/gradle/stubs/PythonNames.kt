package python.multiplatform.gradle.stubs

/**
 * The naming rule, forwards and backwards.
 *
 * `docs/design/pyi-generation-design.md` §3.6: types, objects and composables are PascalCase; functions,
 * methods and parameters are snake_case, which `pythonx-compose`'s `text.py` already does by hand
 * (`font_size` -> `fontSize`, `letter_spacing` -> `letterSpacing`, and 14 more).
 *
 * ### Why the inverse is in the same file and is tested
 *
 * The generator runs the rule forwards and `pythonx`'s adapter has to run it backwards: a module
 * `__getattr__` receives the Python name and has to find the Kotlin declaration. A stub name that
 * does not convert back is a stub promising an attribute the runtime raises `AttributeError` for.
 * So [kotlinNameOf] is not a convenience -- it is the other half of the same rule, and
 * `PythonNameConventionsTest` asserts the round trip over every name it emits rather than over a
 * list of examples.
 *
 * ### The escape, and why there has to be one
 *
 * `fooBar` and `foo_bar` are two different Kotlin declarations and the naive rule maps both to
 * `foo_bar`. An underscore already in the Kotlin name is therefore **doubled**, which makes the
 * inverse total: `_` before a letter means "the next letter was uppercase", `__` means "a literal
 * underscore". Nothing else in the scheme needs a special case.
 */
internal object PythonNames {

    /** `keyword.kwlist` for CPython 3.13, plus the soft keywords that are not usable as identifiers
     * in a parameter position. A parameter named like one of these cannot be spelled at all, so it
     * is suffixed -- and the suffix is what [kotlinNameOf] undoes first. */
    private val PYTHON_KEYWORDS = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
        "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield",
    )

    /**
     * A function, method or property name.
     *
     * @param isComposable a `@Composable` is PascalCase in Kotlin and stays PascalCase in Python --
     *   `Text`, not `text`. No composable is bindable today (`ArtifactScanner` declines every one
     *   for its synthetic `$composer` parameter), so this branch is the rule stated ahead of the
     *   surface that will need it.
     */
    fun functionName(kotlinName: String, isComposable: Boolean): String =
        if (isComposable) typeName(kotlinName) else snakeCase(kotlinName)

    fun parameterName(kotlinName: String): String {
        val snake = snakeCase(kotlinName)
        return if (snake in PYTHON_KEYWORDS) snake + "_" else snake
    }

    /** A type, object or composable name. Kotlin class names are already PascalCase, so this is the
     * identity for everything a jar contains; the uppercase is applied rather than assumed so that
     * the rule is stated where it is used. */
    fun typeName(kotlinName: String): String =
        if (kotlinName.isEmpty()) kotlinName else kotlinName[0].uppercaseChar() + kotlinName.substring(1)

    /**
     * The inverse of [functionName]/[parameterName]: what `pythonx`'s adapter has to compute to turn
     * an attribute name back into the Kotlin declaration it names.
     *
     * Total by construction -- see this object's KDoc on the doubling escape.
     */
    fun kotlinNameOf(pythonName: String): String {
        val unescaped = if (pythonName.endsWith("_") && pythonName.dropLast(1) in PYTHON_KEYWORDS) {
            pythonName.dropLast(1)
        } else {
            pythonName
        }
        val out = StringBuilder(unescaped.length)
        var index = 0
        while (index < unescaped.length) {
            val c = unescaped[index]
            if (c != '_') {
                out.append(c)
                index++
                continue
            }
            when {
                // `__` is a literal underscore the Kotlin name carried.
                index + 1 < unescaped.length && unescaped[index + 1] == '_' -> {
                    out.append('_')
                    index += 2
                }
                // `_x` is an uppercase letter the forward rule lowered.
                index + 1 < unescaped.length -> {
                    out.append(unescaped[index + 1].uppercaseChar())
                    index += 2
                }
                // A trailing single `_` is not something the forward rule produces; left as-is
                // rather than swallowed, so that an unexpected name round-trips to itself.
                else -> {
                    out.append('_')
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun snakeCase(kotlinName: String): String {
        val candidate = buildString {
            kotlinName.forEach { c ->
                when {
                    c == '_' -> append("__")
                    c.isUpperCase() -> append('_').append(c.lowercaseChar())
                    else -> append(c)
                }
            }
        }
        // A Kotlin name that starts with a capital would become a leading underscore, which means
        // "private" in Python and is not what the declaration says. Those are composables and go
        // through `typeName`; anything else that reaches here keeps its own spelling, which still
        // inverts (a name with no `_` and no uppercase is its own inverse).
        if (candidate.startsWith("_") && !kotlinName.startsWith("_")) return kotlinName
        return candidate
    }
}
