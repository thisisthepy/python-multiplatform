package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.reflection.CallableKind
import python.multiplatform.reflection.ExposedCallable
import python.multiplatform.reflection.FunctionTableFragment
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.TypeTag
import python.multiplatform.reflection.UpcallTable
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #78: a name that is both a function and a module.
 *
 * Kotlin has `TextRange(2)` (a top-level factory, bound as the overload set `TextRange__Int`,
 * `TextRange__Int_Int`) **and** `TextRange.Zero` (a constant of the class's companion, bound under the
 * package `...text.TextRange`). Python had only the second: the child module was bound onto the parent
 * as the attribute `TextRange`, so `from ...text import TextRange` found the module and
 * `TextRange(2)` failed with "'_PmModule_..._TextRange' object is not callable". SPEC U-8: the module is
 * callable and dispatches to the function or overload set of that name, and its attributes stay the
 * companion's.
 *
 * Red before the fix: every test here that calls the module raises TypeError (not callable).
 */
class CompanionFactoryTest {

    private object Fragment : FunctionTableFragment {
        override val moduleName: String = "test_companion_factory"
        val calls = mutableListOf<String>()

        private const val PKG = "androidx.compose.ui.text"
        private const val RANGE = "$PKG.TextRange"

        override fun entries(): List<ExposedCallable> = listOf(
            ExposedCallable(
                name = "$RANGE.Zero",
                arity = 0,
                paramTypes = emptyList(),
                returnType = TypeTag.OBJECT,
                kind = CallableKind.STATIC_GETTER,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                returnTypeName = RANGE,
            ) { calls += "Zero"; "range(0,0)" },
            ExposedCallable(
                name = "${RANGE}__Int",
                arity = 1,
                paramTypes = listOf(TypeTag.INT),
                returnType = TypeTag.OBJECT,
                paramNames = listOf("index"),
                paramTypeNames = listOf("kotlin.Int"),
                returnTypeName = RANGE,
            ) { args -> calls += "Int"; "range(${args[0]},${args[0]})" },
            ExposedCallable(
                name = "${RANGE}__Int_Int",
                arity = 2,
                paramTypes = listOf(TypeTag.INT, TypeTag.INT),
                returnType = TypeTag.OBJECT,
                paramNames = listOf("start", "end"),
                paramTypeNames = listOf("kotlin.Int", "kotlin.Int"),
                returnTypeName = RANGE,
            ) { args -> calls += "Int_Int"; "range(${args[0]},${args[1]})" },
            ExposedCallable(
                name = "$PKG.describeRange",
                arity = 1,
                paramTypes = listOf(TypeTag.OBJECT),
                returnType = TypeTag.STRING,
                paramNames = listOf("range"),
                paramTypeNames = listOf(RANGE),
                returnTypeName = "kotlin.String",
            ) { args -> args[0] as String },
        )
    }

    @BeforeTest
    fun install() {
        check(PythonTestFixture.available) {
            "CPython could not be initialized (${PythonTestFixture.failureReason})"
        }
        UpcallTable.install(listOf(Fragment))
        Fragment.calls.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    private fun fresh(block: () -> Unit) = PythonTestFixture.withInterpreter {
        Python3.exec(
            """
            import sys as _cf_sys
            for _cf_n in [_n for _n in _cf_sys.modules if _n == 'androidx' or _n.startswith('androidx.')]:
                del _cf_sys.modules[_cf_n]
            """.trimIndent(),
        )
        PythonxAdapter.install()
        block()
    }

    @Test
    fun theNameIsCallableAndItsAttributesAreTheCompanionsConstants() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui.text import TextRange, describeRange
            _cf = {
                'one': describeRange(TextRange(2)),
                'two': describeRange(TextRange(1, 3)),
                'kw': describeRange(TextRange(start=4, end=5)),
                'zero': describeRange(TextRange.Zero),
                'callable': callable(TextRange),
            }
            """.trimIndent(),
        )
        assertEquals("range(2,2)", eval("_cf['one']"))
        assertEquals("range(1,3)", eval("_cf['two']"))
        assertEquals("range(4,5)", eval("_cf['kw']"))
        assertEquals("range(0,0)", eval("_cf['zero']"))
        assertEquals("True", eval("_cf['callable']"))
    }

    @Test
    fun theExplicitTableKeySpellingStillWorks() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui.text import TextRange__Int, describeRange
            _cf = describeRange(TextRange__Int(5))
            """.trimIndent(),
        )
        assertEquals("range(5,5)", eval("_cf"))
    }

    @Test
    fun importingTheModuleByItsFullNameGivesTheSameCallableObject() = fresh {
        Python3.exec(
            """
            import importlib as _cf_il
            from androidx.compose.ui.text import TextRange, describeRange
            _cf_mod = _cf_il.import_module('androidx.compose.ui.text.TextRange')
            _cf = {'same': _cf_mod is TextRange, 'call': describeRange(_cf_mod(9)), 'zero': describeRange(_cf_mod.Zero)}
            """.trimIndent(),
        )
        assertEquals("True", eval("_cf['same']"))
        assertEquals("range(9,9)", eval("_cf['call']"))
        assertEquals("range(0,0)", eval("_cf['zero']"))
    }

    @Test
    fun aModuleWithNoFunctionOfItsNameIsNotCallable() = fresh {
        Python3.exec(
            """
            import androidx.compose.ui as _cf_ui
            _cf = callable(_cf_ui)
            """.trimIndent(),
        )
        assertEquals("False", eval("_cf"), "only a name that is also a function becomes callable")
    }

    @Test
    fun aNoMatchingOverloadIsATypeErrorNamingTheCandidates() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui.text import TextRange
            try:
                TextRange('a', 'b', 'c')
                _cf = 'no error'
            except TypeError as _e:
                _cf = str(_e)
            """.trimIndent(),
        )
        assertTrue("TextRange" in eval("_cf"), eval("_cf"))
        assertTrue(eval("_cf") != "no error")
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
