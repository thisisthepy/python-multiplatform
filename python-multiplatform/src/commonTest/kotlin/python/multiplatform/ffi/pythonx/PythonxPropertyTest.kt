package python.multiplatform.ffi.pythonx

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.upcall.publishesProxyEntryPoints
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.UpcallTable
import python.native.ffi.bindUpcallOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Issue #38's binding-layer half, and issue #54: a Kotlin **property** is an attribute of the proxy of
 * the type it is read on, and `python_multiplatform.describe_member` describes what a proxy serves
 * under a name without serving it.
 *
 * The table is [PropertyShapedFragment] (beside [ComposeShapedFragment], for `Modifier.padding`).
 *
 * ### Red before the implementation, and what each red says
 *
 * Every test here is red against a binding layer that has no `_PROPERTIES`: `state.value` is an
 * `AttributeError` ("no Kotlin extension named value"), `Icons.Default.Add` likewise, and
 * `describe_member` does not exist (`AttributeError: module 'python_multiplatform' has no attribute
 * 'describe_member'`). A *regression* reads differently: a value that comes back wrong, or a Kotlin
 * call where the test counts none.
 */
class PythonxPropertyTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ComposeShapedFragment, PropertyShapedFragment))
        ComposeShapedFragment.calls.clear()
        PropertyShapedFragment.calls.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    /**
     * The completion statement of #38 in the binding layer's terms: Python creates a state, reads its
     * `value`, writes a Python function into it, and Kotlin holds that very function.
     */
    @Test
    fun aMemberVarIsReadAndWrittenAsAnAttribute() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.runtime import mutableStateOf, describeState
            _pp_state = mutableStateOf(None)
            def _pp_root():
                return 'root'
            _pp = {
                'type': type(_pp_state)._kotlin_type_name,
                'initial': _pp_state.value,
                'held_initially': describeState(_pp_state),
            }
            _pp_state.value = _pp_root
            _pp['read_back_is_same'] = _pp_state.value is _pp_root
            _pp['held'] = describeState(_pp_state)
            _pp_state.value = None
            _pp['cleared'] = _pp_state.value
            """.trimIndent(),
        )
        assertEquals("androidx.compose.runtime.MutableState", eval("_pp['type']"))
        assertEquals("None", eval("repr(_pp['initial'])"))
        assertEquals("null", eval("_pp['held_initially']"))
        assertEquals("True", eval("repr(_pp['read_back_is_same'])"), "the Python object did not round-trip through Kotlin")
        assertEquals("python", eval("_pp['held']"), "Kotlin does not hold the Python function itself")
        assertEquals("None", eval("repr(_pp['cleared'])"))
        // The nearest declaration serves: `MutableState.value`, not the supertype's `State.value`.
        assertEquals(
            listOf("mutableStateOf", "MutableState.value", "MutableState.value=", "MutableState.value", "MutableState.value=", "MutableState.value"),
            PropertyShapedFragment.calls,
        )
    }

    /** A Kotlin object written into an `Any?` goes as its handle, and Kotlin holds the object. */
    @Test
    fun aKotlinValueWrittenIntoAnAnySlotIsTheKotlinObject() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.runtime import mutableStateOf, describeState
            from propshape import makeDerived
            _pp_state = mutableStateOf(makeDerived('d'))
            _pp = {'held': describeState(_pp_state)}
            """.trimIndent(),
        )
        assertEquals("kotlin:d", eval("_pp['held']"))
    }

    /**
     * An `int` would cross an `OBJECT` slot as a handle and name some other Kotlin object; refused
     * with a reason rather than stored.
     */
    @Test
    fun anIntIsRefusedForAnAnySlot() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.runtime import mutableStateOf
            _pp_state = mutableStateOf(None)
            _pp = {}
            for _pp_label, _pp_call in (('call', lambda: mutableStateOf(7)), ('write', lambda: setattr(_pp_state, 'value', 7))):
                try:
                    _pp_call()
                    _pp[_pp_label] = 'accepted'
                except TypeError as _pp_e:
                    _pp[_pp_label] = str(_pp_e)
            """.trimIndent(),
        )
        assertTrue("int" in eval("_pp['call']"), eval("_pp['call']"))
        assertTrue("int" in eval("_pp['write']"), eval("_pp['write']"))
    }

    /** A `val` has no setter: writing it is Python's own refusal, and nothing reaches Kotlin. */
    @Test
    fun aValIsReadOnly() = withAdapter {
        Python3.exec(
            """
            from propshape import makeDerived
            _pp_d = makeDerived('d')
            _pp = {'label': _pp_d.label}
            try:
                _pp_d.label = 'x'
                _pp['write'] = 'accepted'
            except AttributeError:
                _pp['write'] = 'AttributeError'
            """.trimIndent(),
        )
        assertEquals("d", eval("_pp['label']"))
        assertEquals("AttributeError", eval("_pp['write']"))
        assertEquals(listOf("Base.label"), PropertyShapedFragment.calls)
    }

    /**
     * Declared on the supertype only: `Derived` has neither `label` nor `boost` of its own, and the
     * table's ancestry for it (`Derived<:Base`, carried on `makeDerived`'s return) is what serves both.
     */
    @Test
    fun aSupertypesPropertyAndExtensionAreServedOnTheSubtype() = withAdapter {
        Python3.exec(
            """
            from propshape import makeDerived
            _pp_d = makeDerived('ab')
            _pp = {'type': type(_pp_d)._kotlin_type_name, 'label': _pp_d.label, 'boost': _pp_d.boost(2)}
            """.trimIndent(),
        )
        assertEquals(PropertyShapedFragment.DERIVED, eval("_pp['type']"))
        assertEquals("ab", eval("_pp['label']"))
        assertEquals("abab", eval("_pp['boost']"))
    }

    /**
     * `Icons.Default.Add`: an object's constant, then an **extension property** read off it -- the
     * material-icons shape (`AddKt.getAdd(Icons$Filled)`).
     */
    @Test
    fun anExtensionPropertyIsAValueOfItsReceiver() = withAdapter {
        Python3.exec(
            """
            import androidx
            from androidx.compose.ui.graphics.vector import describeVector
            _pp_add = androidx.compose.material.icons.Icons.Default.Add
            _pp = {'type': type(_pp_add)._kotlin_type_name, 'name': describeVector(_pp_add)}
            """.trimIndent(),
        )
        assertEquals("androidx.compose.ui.graphics.vector.ImageVector", eval("_pp['type']"))
        assertEquals("Filled.Add", eval("_pp['name']"))
    }

    /**
     * A property is served on a value, never from a package: `MutableState` is not a module because
     * `MutableState.value` is bound, and `icons.filled` is not one because `Add` is.
     */
    @Test
    fun aPropertyMakesNoPackage() = withAdapter {
        Python3.exec(
            """
            import importlib
            _pp = {}
            for _pp_name in ('androidx.compose.runtime.MutableState', 'androidx.compose.material.icons.filled'):
                try:
                    importlib.import_module(_pp_name)
                    _pp[_pp_name] = 'imported'
                except ImportError:
                    _pp[_pp_name] = 'ImportError'
            """.trimIndent(),
        )
        assertEquals("ImportError", eval("_pp['androidx.compose.runtime.MutableState']"))
        assertEquals("ImportError", eval("_pp['androidx.compose.material.icons.filled']"))
    }

    // ------------------------------------------------------------------- describe_member (#54)

    /** Issue #54's completion statement: the overloads of an extension, with their Kotlin parameter names. */
    @Test
    fun describeMemberListsAnExtensionsOverloadsByTheReceiverType() = withAdapter {
        Python3.exec(
            """
            import python_multiplatform as _pp_pm
            _pp_all = _pp_pm.describe_member('androidx.compose.ui.Modifier', 'padding')
            _pp_one = _pp_pm.describe_member('androidx.compose.ui.Modifier', 'padding__Dp_Dp')
            _pp = {
                'all': sorted((_d['name'], tuple(_p['name'] for _p in _d['parameters'])) for _d in _pp_all),
                'one': [(_d['name'], _d['receiver']) for _d in _pp_one],
            }
            """.trimIndent(),
        )
        assertEquals(
            "[('androidx.compose.foundation.layout.padding__Dp', ('all',)), " +
                "('androidx.compose.foundation.layout.padding__Dp_Dp', ('horizontal', 'vertical')), " +
                "('androidx.compose.foundation.layout.padding__Dp_Dp_Dp_Dp', ('start', 'top', 'end', 'bottom')), " +
                "('androidx.compose.foundation.layout.padding__PaddingValues', ('paddingValues',))]",
            eval("repr(_pp['all'])"),
        )
        assertEquals(
            "[('androidx.compose.foundation.layout.padding__Dp_Dp', 'androidx.compose.ui.Modifier')]",
            eval("repr(_pp['one'])"),
        )
        assertEquals(emptyList(), ComposeShapedFragment.calls, "describing invoked Kotlin")
    }

    /** A property answers its getter and, for a `var`, its setter -- and reads nothing. */
    @Test
    fun describeMemberDescribesAPropertyWithoutReadingIt() = withAdapter {
        Python3.exec(
            """
            import python_multiplatform as _pp_pm
            def _pp_shape(rows):
                return [
                    (_d['name'], _d['kind'], _d['receiver'], _d['returns'],
                     tuple((_p['name'], _p['type']) for _p in _d['parameters']))
                    for _d in rows
                ]
            _pp = {
                'var': _pp_shape(_pp_pm.describe_member('androidx.compose.runtime.MutableState', 'value')),
                'inherited': _pp_shape(_pp_pm.describe_member('propshape.Derived', 'label')),
                'extension_property': _pp_shape(_pp_pm.describe_member('androidx.compose.material.icons.Icons.Filled', 'Add')),
            }
            """.trimIndent(),
        )
        assertEquals(
            "[('androidx.compose.runtime.MutableState.value', 'GETTER', 'androidx.compose.runtime.MutableState', 'kotlin.Any', ()), " +
                "('androidx.compose.runtime.MutableState.value=', 'SETTER', 'androidx.compose.runtime.MutableState', 'kotlin.Unit', " +
                "(('value', 'kotlin.Any'),))]",
            eval("repr(_pp['var'])"),
        )
        assertEquals(
            "[('propshape.Base.label', 'GETTER', 'propshape.Base', 'kotlin.String', ())]",
            eval("repr(_pp['inherited'])"),
        )
        assertEquals(
            "[('androidx.compose.material.icons.filled.Add', 'GETTER', 'androidx.compose.material.icons.Icons.Filled', " +
                "'androidx.compose.ui.graphics.vector.ImageVector', ())]",
            eval("repr(_pp['extension_property'])"),
        )
        assertEquals(emptyList(), PropertyShapedFragment.calls, "describing a property read it")
    }

    /** A name the type is not served under -- on itself or any supertype -- is an `AttributeError`. */
    @Test
    fun describeMemberRefusesANameTheTypeDoesNotHave() = withAdapter {
        Python3.exec(
            """
            import python_multiplatform as _pp_pm
            _pp = {}
            for _pp_type, _pp_name in (
                ('androidx.compose.ui.Modifier', 'nonesuch'),
                ('androidx.compose.runtime.State', 'setValue'),
                ('no.such.Type', 'padding'),
            ):
                try:
                    _pp_pm.describe_member(_pp_type, _pp_name)
                    _pp[_pp_name] = 'described'
                except AttributeError:
                    _pp[_pp_name] = 'AttributeError'
            """.trimIndent(),
        )
        assertEquals("AttributeError", eval("_pp['nonesuch']"))
        assertEquals("AttributeError", eval("_pp['setValue']"))
        assertEquals("AttributeError", eval("_pp['padding']"))
    }

    // ------------------------------------------------------------------------------- helpers

    private inline fun withAdapter(block: () -> Unit) = PythonTestFixture.withInterpreter {
        assertTrue(bindUpcallOrNull(ComposeShapedFragment.EMPTY_MODIFIER), "the fixture table is not installed")
        if (!publishesProxyEntryPoints) {
            val refusal = assertFails { PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES) }
            assertTrue(
                refusal.message?.contains("raw upcall entry points are not bound") == true,
                "a target with no proxy bootstrap must fail the adapter's own guard: $refusal",
            )
            return@withInterpreter
        }
        PythonxAdapter.install(COMPOSE_SHAPED_RAW_VALUE_CLASSES)
        Python3.exec(
            "import python_multiplatform.binding as _pm_binding\n" +
                "_pm_binding.register_empty('androidx.compose.ui.Modifier', " +
                "'${ComposeShapedFragment.EMPTY_MODIFIER}')",
        )
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
