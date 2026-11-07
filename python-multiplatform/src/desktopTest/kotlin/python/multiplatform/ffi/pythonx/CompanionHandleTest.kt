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

/**
 * Issue #322 (SPEC U-4): a public companion object is a value, `Owner.Companion`, read as an attribute
 * of the owner's proxy class. When the companion is a subtype of the owner the value is an instance of
 * the owner's proxy. Two reads are two handles to one Kotlin object: they compare `==` (reference
 * identity, never Kotlin `equals`) and `is` is not promised.
 *
 * Red before the change: `Modifier.Companion` raises AttributeError on the proxy class (the table key
 * lives in the module of the same name, which the class never consulted), and two proxies never
 * compare equal.
 */
class CompanionHandleTest {

    private object TheCompanion

    private object Fragment : FunctionTableFragment {
        override val moduleName: String = "test_companion_handle"

        private const val UI = "androidx.compose.ui"
        private const val MODIFIER = "$UI.Modifier"

        override fun entries(): List<ExposedCallable> = listOf(
            ExposedCallable(
                name = "$MODIFIER.Companion",
                arity = 0,
                paramTypes = emptyList(),
                returnType = TypeTag.OBJECT,
                kind = CallableKind.STATIC_GETTER,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                returnTypeName = MODIFIER,
            ) { TheCompanion },
            // A class-level constant through the proxy class, beside the companion handle.
            ExposedCallable(
                name = "$MODIFIER.Label",
                arity = 0,
                paramTypes = emptyList(),
                returnType = TypeTag.STRING,
                kind = CallableKind.STATIC_GETTER,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                returnTypeName = "kotlin.String",
            ) { "label" },
            ExposedCallable(
                name = "$UI.emptyModifier",
                arity = 0,
                paramTypes = emptyList(),
                returnType = TypeTag.OBJECT,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                returnTypeName = MODIFIER,
            ) { TheCompanion },
            // Two distinct objects that Kotlin `equals` calls equal: `==` must still be False.
            ExposedCallable(
                name = "$MODIFIER.FreshA",
                arity = 0,
                paramTypes = emptyList(),
                returnType = TypeTag.OBJECT,
                kind = CallableKind.STATIC_GETTER,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                returnTypeName = MODIFIER,
            ) { ArrayList(listOf(1)) },
            ExposedCallable(
                name = "$MODIFIER.FreshB",
                arity = 0,
                paramTypes = emptyList(),
                returnType = TypeTag.OBJECT,
                kind = CallableKind.STATIC_GETTER,
                paramNames = emptyList(),
                paramTypeNames = emptyList(),
                returnTypeName = MODIFIER,
            ) { ArrayList(listOf(1)) },
            // An extension makes Modifier a receiver type, so `Modifier` is a proxy class.
            ExposedCallable(
                name = "$UI.tag",
                arity = 1,
                paramTypes = listOf(TypeTag.OBJECT),
                returnType = TypeTag.STRING,
                paramNames = listOf("<receiver>"),
                paramTypeNames = listOf(MODIFIER),
                returnTypeName = "kotlin.String",
                isExtension = true,
                receiverTypeName = MODIFIER,
            ) { args -> if (args[0] === TheCompanion) "companion" else "other" },
        )
    }

    @BeforeTest
    fun install() {
        check(PythonTestFixture.available) {
            "CPython could not be initialized (${PythonTestFixture.failureReason})"
        }
        UpcallTable.install(listOf(Fragment))
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

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()

    @Test
    fun theCompanionIsAnInstanceOfTheOwnersProxy() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            _cf = isinstance(Modifier.Companion, Modifier)
            _cf_tag = Modifier.Companion.tag()
            """.trimIndent(),
        )
        assertEquals("True", eval("_cf"))
        assertEquals("companion", eval("_cf_tag"))
    }

    @Test
    fun twoReadsCompareEqualAndAreNotPromisedIdentical() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            _a = Modifier.Companion
            _b = Modifier.Companion
            _cf = (_a == _b, hash(_a) == hash(_b), _a != 3)
            """.trimIndent(),
        )
        assertEquals("(True, True, True)", eval("_cf"))
    }

    @Test
    fun twoDistinctObjectsThatKotlinEqualsCallEqualAreNotEqual() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            _a = Modifier.FreshA
            _b = Modifier.FreshB
            _cf = (_a == _b, _a != _b, _a == _a)
            """.trimIndent(),
        )
        assertEquals("(False, True, True)", eval("_cf"))
    }

    @Test
    fun thereIsNoLowerCaseCompanionAlias() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            _cf = hasattr(Modifier, 'companion')
            """.trimIndent(),
        )
        assertEquals("False", eval("_cf"))
    }

    @Test
    fun aClassLevelConstantIsReadThroughTheProxyClass() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            _cf = Modifier.Label
            """.trimIndent(),
        )
        assertEquals("label", eval("_cf"))
    }

    @Test
    fun anUnknownNameOnTheClassStillRaisesAttributeError() = fresh {
        Python3.exec(
            """
            from androidx.compose.ui import Modifier
            try:
                Modifier.Nothing
                _cf = 'no error'
            except AttributeError:
                _cf = 'AttributeError'
            """.trimIndent(),
        )
        assertEquals("AttributeError", eval("_cf"))
    }

    @Test
    fun aPythonCallableCanBeTheEmptyFactory() = fresh {
        Python3.exec(
            """
            import python_multiplatform.binding as _pm_binding
            from androidx.compose.ui import Modifier
            _pm_binding.register_empty(Modifier, lambda: Modifier.Companion)
            _cf = Modifier.empty() == Modifier.Companion
            """.trimIndent(),
        )
        assertEquals("True", eval("_cf"))
    }

    @Test
    fun theStringFactoryFormStillWorksWithATypeNameAndWithTheClass() = fresh {
        Python3.exec(
            """
            import python_multiplatform.binding as _pm_binding
            from androidx.compose.ui import Modifier
            _pm_binding.register_empty('androidx.compose.ui.Modifier', 'androidx.compose.ui.emptyModifier')
            _one = Modifier.empty() == Modifier.Companion
            _pm_binding.register_empty(Modifier, 'androidx.compose.ui.emptyModifier')
            _cf = (_one, Modifier.empty() == Modifier.Companion)
            """.trimIndent(),
        )
        assertEquals("(True, True)", eval("_cf"))
    }
}
