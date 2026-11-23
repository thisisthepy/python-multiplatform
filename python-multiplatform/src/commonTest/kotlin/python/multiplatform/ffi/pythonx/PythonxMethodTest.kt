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
 * Issue #215's binding-layer half: a `METHOD` row the walker emits is a method of the proxy of the
 * type it is declared on, reached on that type and its subtypes, ahead of an extension of the same
 * name, and never a module attribute.
 *
 * The table is [MethodShapedFragment].
 *
 * ### Red before the implementation, and what each red says
 *
 * Against a binding layer that indexes every non-property row by package, `counter.add(3)` is an
 * `AttributeError` ("no Kotlin extension named add"), `counter.reset()` calls the extension, and
 * `methshape.Counter` is importable as a package of its own. A *regression* reads differently: a
 * value that comes back wrong, or the wrong Kotlin call recorded.
 */
class PythonxMethodTest {

    @BeforeTest
    fun install() {
        UpcallTable.install(listOf(ComposeShapedFragment, MethodShapedFragment))
        MethodShapedFragment.calls.clear()
    }

    @AfterTest
    fun cleanup() {
        UpcallTable.clear()
        HandleTable.releaseAll()
    }

    @Test
    fun aMemberIsCalledOnTheProxyWithItsReceiver() = withAdapter {
        Python3.exec(
            """
            from methshape import newCounter
            _pm_c = newCounter()
            _pm = {'first': _pm_c.add(3), 'keyword': _pm_c.add(step=2), 'count': _pm_c.count}
            """.trimIndent(),
        )
        assertEquals("3", eval("_pm['first']"))
        assertEquals("5", eval("_pm['keyword']"))
        assertEquals("5", eval("_pm['count']"), "the member did not run on the Kotlin object the proxy holds")
        assertEquals(listOf("Counter.add(Int)", "Counter.add(Int)"), MethodShapedFragment.calls)
    }

    @Test
    fun anOverloadedMemberIsChosenByItsArgumentsAndByItsExplicitName() = withAdapter {
        Python3.exec(
            """
            from methshape import newCounter
            _pm_c = newCounter()
            _pm = {'two': _pm_c.add(1, 'n='), 'explicit': _pm_c.add__Int(4)}
            """.trimIndent(),
        )
        assertEquals("n=1", eval("_pm['two']"))
        assertEquals("5", eval("_pm['explicit']"))
        assertEquals(listOf("Counter.add(Int, String)", "Counter.add(Int)"), MethodShapedFragment.calls)
    }

    @Test
    fun aMemberWinsOverAnExtensionOfTheSameName() = withAdapter {
        Python3.exec(
            """
            from methshape import newCounter
            _pm_c = newCounter()
            _pm_c.add(7)
            _pm_c.reset()
            _pm = {'count': _pm_c.count}
            """.trimIndent(),
        )
        assertEquals("0", eval("_pm['count']"))
        assertEquals(listOf("Counter.add(Int)", "Counter.reset"), MethodShapedFragment.calls)
    }

    @Test
    fun aSupertypesMemberIsReachedOnTheSubtype() = withAdapter {
        Python3.exec("from methshape import newCounter\n_pm = {'label': newCounter().label()}")
        assertEquals("base", eval("_pm['label']"))
        assertEquals(listOf("Base.label"), MethodShapedFragment.calls)
    }

    @Test
    fun aMembersPythonicNameReachesIt() = withAdapter {
        Python3.exec("from methshape import newCounter\n_pm = {'snake': newCounter().stop_now(), 'kotlin': newCounter().stopNow()}")
        assertEquals("stopped", eval("_pm['snake']"))
        assertEquals("stopped", eval("_pm['kotlin']"))
    }

    @Test
    fun aMemberIsNotAModuleAttribute() = withAdapter {
        Python3.exec(
            """
            import importlib, importlib.util, methshape
            _pm = {
                'add_in_package': hasattr(methshape, 'add__Int'),
                'counter_is_a_package': importlib.util.find_spec('methshape.Counter') is not None,
            }
            """.trimIndent(),
        )
        assertEquals("False", eval("_pm['add_in_package']"))
        assertEquals("False", eval("_pm['counter_is_a_package']"))
    }

    /**
     * SPEC U-9: `dir()` of a module lists what reading an attribute serves, and nothing else. A type
     * whose only rows are members (`methshape.Base`: one `METHOD`, no extension, no package of its own)
     * is reached through a value's proxy and is not an attribute of its package, so it is not listed.
     * pythonx-compose turns `dir()` into its name table, and a listed name it cannot read hid
     * `Alignment.Horizontal.End` (the notebook's grouped spelling) on Kotlin/Native, where the klib
     * walker binds `Alignment.Horizontal.align` as a member (#191, NotebookPracticeTest cell 36).
     * Red before: `['Base']`, `dir()` listing a name `getattr` refuses.
     */
    @Test
    fun aModuleListsOnlyTheNamesItServes() = withAdapter {
        Python3.exec(
            """
            import methshape
            _pm = {
                'unreadable': [_n for _n in dir(methshape) if not _n.startswith('_') and not hasattr(methshape, _n)],
                'counter': 'Counter' in dir(methshape) and hasattr(methshape, 'Counter'),
            }
            """.trimIndent(),
        )
        assertEquals("[]", eval("repr(_pm['unreadable'])"), "dir() lists a name the module does not serve")
        assertEquals("True", eval("_pm['counter']"), "a type with an extension is still listed and served")
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
        block()
    }

    private fun eval(expression: String): String = PythonTestFixture.eval(expression).toString()
}
