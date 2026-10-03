package python.multiplatform.gradle.stubs

/**
 * The binder's Pythonic naming rule (issue #131), mirrored for the stub generator.
 *
 * The runtime rule lives in `python_multiplatform` (`KotlinSurface.kt`: `snake_case`, `python_name`,
 * `pythonic_aliases`), and it is pythonx-compose 0.1.0a1's (`_reexport.py`) character for character.
 * This file restates it in Kotlin because the stubs are rendered at build time, with no interpreter;
 * `PythonNameRuleTest` pins the two spellings against the same cases.
 *
 * - [snakeCase]: `fillMaxWidth` -> `fill_max_width`, `toURLString` -> `to_url_string`.
 * - [pythonName]: upper-case first is unchanged (types, objects, composables); an explicit overload
 *   key converts its base and keeps its `__Types` suffix.
 * - [pythonicAliases]: an alias is served only when it is not already a Kotlin name of the namespace
 *   and exactly one Kotlin name maps to it. A namespace (a Kotlin package path) is never converted.
 */

private val ACRONYM_WORD = Regex("([A-Z]+)([A-Z][a-z])")
private val LOWER_UPPER = Regex("([a-z0-9])([A-Z])")

/** Python's `_LOWER_UPPER.sub(r'\1_\2', _ACRONYM_WORD.sub(r'\1_\2', name)).lower()`. */
internal fun snakeCase(kotlinName: String): String =
    LOWER_UPPER.replace(ACRONYM_WORD.replace(kotlinName, "$1_$2"), "$1_$2").lowercase()

/** The one Pythonic spelling of a Kotlin declaration or member name. */
internal fun pythonName(kotlinName: String): String {
    if (kotlinName.isEmpty() || kotlinName[0].isUpperCase()) return kotlinName
    val separator = kotlinName.indexOf("__")
    if (separator < 0) return snakeCase(kotlinName)
    return snakeCase(kotlinName.substring(0, separator)) + kotlinName.substring(separator)
}

/** Python's `str.isidentifier()` for the ASCII names a Kotlin declaration can carry here. */
private fun isPythonIdentifier(name: String): Boolean =
    name.isNotEmpty() && (name[0] == '_' || name[0].isLetter()) && name.all { it == '_' || it.isLetterOrDigit() }

/**
 * `alias -> kotlin name` for the names of one namespace, ambiguity refused: the runtime's
 * `pythonic_aliases`. Independent of the order of [kotlinNames].
 */
internal fun pythonicAliases(kotlinNames: Collection<String>, rule: (String) -> String = ::pythonName): Map<String, String> {
    val names = kotlinNames.toSet()
    val found = sortedMapOf<String, String>()
    val clashed = mutableSetOf<String>()
    for (kotlin in names) {
        val alias = rule(kotlin)
        if (alias == kotlin || alias in names || !isPythonIdentifier(alias)) continue
        val other = found[alias]
        if (other == null) found[alias] = kotlin else if (other != kotlin) clashed += alias
    }
    clashed.forEach { found.remove(it) }
    return found
}
