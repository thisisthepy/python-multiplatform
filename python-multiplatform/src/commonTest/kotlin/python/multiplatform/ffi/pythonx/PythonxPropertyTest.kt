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
            # Read back through the same `Any?` (#69): a Kotlin object that is not a scalar is still a
            # proxy over its handle, and writing that proxy on is the same Kotlin object again.
            _pp_back = _pp_state.value
            _pp['back_is_proxy'] = getattr(_pp_back, '_pm_handle', None) is not None
            _pp['passed_on'] = describeState(mutableStateOf(_pp_back))
            """.trimIndent(),
        )
        assertEquals("kotlin:d", eval("_pp['held']"))
        assertEquals("True", eval("repr(_pp['back_is_proxy'])"), "a Kotlin object read from Any? is not a proxy")
        assertEquals("kotlin:d", eval("_pp['passed_on']"))
    }

    // ------------------------------------------------------------- scalars in an Any? slot (#69)
    //
    // Red before #69, and what each red says: every write below raised `TypeError: ... an int cannot
    // be stored in a Kotlin Any?: it would cross as an object handle` (a `bool` is an `int` there
    // too), and a Kotlin-held scalar read back came out as a `kotlin.Any` proxy over a handle rather
    // than as the Python value. A *regression* reads differently: the right Python value with the
    // wrong Kotlin class behind it (`Long:7` where `Int:7` is expected), or a handle count that
    // does not come back to its baseline.

    /**
     * Each scalar written through an `Any?` **function** slot (`mutableStateOf(value)`) is the Kotlin
     * box the issue names, and reads back as the Python value and type it was.
     */
    @Test
    fun aScalarPassedToAnAnySlotIsAKotlinBoxAndReadsBackAsItself() = withAdapter {
        Python3.exec(SCALARS + "\n" +
            """
            from androidx.compose.runtime import mutableStateOf, describeState
            _pp = {}
            for _pp_label, _pp_value in _pp_scalars:
                _pp_state = mutableStateOf(_pp_value)
                _pp_back = _pp_state.value
                _pp[_pp_label] = (describeState(_pp_state), type(_pp_back).__name__, _pp_back == _pp_value)
            """.trimIndent(),
        )
        assertScalarRoundTrips()
    }

    /** The same rules through a `var` **property** write (`state.value = x`), starting from `null`. */
    @Test
    fun aScalarWrittenToAnAnyPropertyIsAKotlinBoxAndReadsBackAsItself() = withAdapter {
        Python3.exec(SCALARS + "\n" +
            """
            from androidx.compose.runtime import mutableStateOf, describeState
            _pp = {}
            _pp_state = mutableStateOf(None)
            for _pp_label, _pp_value in _pp_scalars:
                _pp_state.value = _pp_value
                _pp_back = _pp_state.value
                _pp[_pp_label] = (describeState(_pp_state), type(_pp_back).__name__, _pp_back == _pp_value)
            """.trimIndent(),
        )
        assertScalarRoundTrips()
    }

    /** An `int` no Kotlin integer holds is refused with a reason, through both spellings. */
    @Test
    fun anIntOutside64BitsIsRefusedForAnAnySlot() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.runtime import mutableStateOf
            _pp_state = mutableStateOf(None)
            _pp = {}
            for _pp_label, _pp_value in (('above', 2**63), ('below', -2**63 - 1)):
                for _pp_how, _pp_call in (
                    ('call', lambda: mutableStateOf(_pp_value)),
                    ('write', lambda: setattr(_pp_state, 'value', _pp_value)),
                ):
                    try:
                        _pp_call()
                        _pp[_pp_label + '_' + _pp_how] = 'accepted'
                    except TypeError as _pp_e:
                        _pp[_pp_label + '_' + _pp_how] = str(_pp_e)
            _pp['still_empty'] = _pp_state.value is None
            """.trimIndent(),
        )
        for (key in listOf("above_call", "above_write", "below_call", "below_write")) {
            val message = eval("_pp['$key']")
            assertTrue("64 bits" in message, "$key: $message")
        }
        assertEquals("True", eval("repr(_pp['still_empty'])"), "a refused write reached Kotlin")
    }

    /**
     * The read-back direction on values **Kotlin** put there: every boxed type the issue lists comes
     * out as the Python scalar, including the ones Python never writes (`Short`, `Byte`, `Float`, `Char`).
     */
    @Test
    fun aKotlinScalarReadFromAnAnySlotIsThePythonScalar() = withAdapter {
        Python3.exec(
            """
            from propshape import stateHolding
            _pp = {}
            for _pp_kind in ('Int', 'Long', 'Short', 'Byte', 'Double', 'Float', 'Boolean', 'String', 'Char'):
                _pp_back = stateHolding(_pp_kind).value
                _pp[_pp_kind] = (type(_pp_back).__name__, repr(_pp_back))
            """.trimIndent(),
        )
        assertEquals("('int', '7')", eval("repr(_pp['Int'])"))
        assertEquals("('int', '1099511627776')", eval("repr(_pp['Long'])"))
        assertEquals("('int', '300')", eval("repr(_pp['Short'])"))
        assertEquals("('int', '-5')", eval("repr(_pp['Byte'])"))
        assertEquals("('float', '2.5')", eval("repr(_pp['Double'])"))
        assertEquals("('float', '1.5')", eval("repr(_pp['Float'])"))
        assertEquals("('bool', 'True')", eval("repr(_pp['Boolean'])"))
        assertEquals("('str', \"'text'\")", eval("repr(_pp['String'])"))
        assertEquals("('str', \"'c'\")", eval("repr(_pp['Char'])"))
    }

    /**
     * Boxing and unboxing root nothing that outlives the statement: the box a write takes, and the
     * handle a scalar read crosses as, are both given back. Counted at both ends -- the state's own
     * proxy holds one root, and that one must still be there.
     */
    @Test
    fun boxingAScalarLeavesNoHandleRooted() = withAdapter {
        Python3.exec(
            """
            from androidx.compose.runtime import mutableStateOf
            _pp_state = mutableStateOf(None)
            """.trimIndent(),
        )
        val baseline = HandleTable.liveCount
        Python3.exec(
            """
            for _pp_i in range(20):
                _pp_state.value = _pp_i
                _pp_state.value = _pp_state.value + 2**40
                _pp_state.value = float(_pp_i)
                _pp_state.value = (_pp_i % 2 == 0)
                _pp_state.value = str(_pp_i)
                _pp_last = _pp_state.value
            _pp_other = mutableStateOf(_pp_i)
            _pp_seen = _pp_other.value
            del _pp_other
            """.trimIndent(),
        )
        assertEquals("'19'", eval("repr(_pp_last)"))
        assertEquals("19", eval("repr(_pp_seen)"))
        assertEquals(baseline, HandleTable.liveCount, "a box or an unboxed read left a handle rooted")
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

    /** What [SCALARS] must read back as: the Kotlin box Kotlin holds, the Python type, and equality. */
    private fun assertScalarRoundTrips() {
        val expected = listOf(
            "true_" to "('Boolean:true', 'bool', True)",
            "false_" to "('Boolean:false', 'bool', True)",
            "int" to "('Int:7', 'int', True)",
            "zero" to "('Int:0', 'int', True)",
            "negative" to "('Int:-3', 'int', True)",
            "int_max" to "('Int:2147483647', 'int', True)",
            "int_min" to "('Int:-2147483648', 'int', True)",
            "long" to "('Long:2147483648', 'int', True)",
            "long_negative" to "('Long:-2147483649', 'int', True)",
            "long_max" to "('Long:9223372036854775807', 'int', True)",
            "long_min" to "('Long:-9223372036854775808', 'int', True)",
            "float" to "('Double:2.5', 'float', True)",
            "str" to "('String:hi', 'str', True)",
        )
        for ((label, shape) in expected) {
            assertEquals(shape, eval("repr(_pp['$label'])"), label)
        }
    }

    private companion object {
        /** Every scalar #69 names, at each edge of the `Int`/`Long` choice. */
        val SCALARS = """
            _pp_scalars = (
                ('true_', True), ('false_', False),
                ('int', 7), ('zero', 0), ('negative', -3), ('int_max', 2**31 - 1), ('int_min', -2**31),
                ('long', 2**31), ('long_negative', -2**31 - 1), ('long_max', 2**63 - 1), ('long_min', -2**63),
                ('float', 2.5), ('str', 'hi'),
            )
        """.trimIndent()
    }
}
