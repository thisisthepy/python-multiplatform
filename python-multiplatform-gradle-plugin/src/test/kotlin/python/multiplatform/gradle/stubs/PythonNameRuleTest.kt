package python.multiplatform.gradle.stubs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The stub generator's copy of the naming rule (issue #131) gives the answers the runtime's gives.
 *
 * The runtime rule is `python_multiplatform.python_name` (`KotlinSurface.kt`), itself
 * pythonx-compose 0.1.0a1's; the expected values below are what that Python function returns, and
 * `PythonxAdapterTest.theNameRuleConvertsKotlinToPythonAndEitherSpellingReachesOneDeclaration` pins the
 * same cases on the runtime side.
 */
class PythonNameRuleTest {

    @Test
    fun theForwardRuleMatchesTheRuntime() {
        val cases = mapOf(
            "fillMaxWidth" to "fill_max_width",
            "zIndex" to "z_index",
            "toURLString" to "to_url_string",
            "getURL" to "get_url",
            "size2Dp" to "size2_dp",
            "padding__Dp_Dp" to "padding__Dp_Dp",
            "paddingFromBaseline__TextUnit" to "padding_from_baseline__TextUnit",
            "rememberTextFieldState" to "remember_text_field_state",
            "Modifier" to "Modifier",
            "URLHandler" to "URLHandler",
            "padding" to "padding",
            "foo_bar" to "foo_bar",
        )
        cases.forEach { (kotlin, python) -> assertEquals(python, pythonName(kotlin), kotlin) }
        assertEquals("on_checked_change", snakeCase("onCheckedChange"))
        assertEquals("x_url", snakeCase("xURL"))
    }

    @Test
    fun anAliasIsServedOnlyWhenItIsUnambiguous() {
        assertEquals(
            mapOf("fill_max_width" to "fillMaxWidth"),
            pythonicAliases(listOf("toURL", "toUrl", "fooBar", "foo_bar", "fillMaxWidth", "Modifier", "padding")),
        )
        assertEquals(emptyMap(), pythonicAliases(listOf("xURL", "xUrl"), ::snakeCase))
        assertEquals(emptyMap(), pythonicAliases(listOf("onClick", "on_click"), ::snakeCase))
    }
}
