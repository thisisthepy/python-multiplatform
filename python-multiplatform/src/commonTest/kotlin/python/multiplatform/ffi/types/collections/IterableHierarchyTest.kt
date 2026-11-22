package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture
import python.multiplatform.ffi.exceptions.errors.PyTypeError
import python.multiplatform.ffi.types.iteration.PyIterable
import python.multiplatform.ffi.types.iteration.PyIterator
import python.multiplatform.ffi.types.iteration.PyIteratorObject
import python.multiplatform.ffi.types.utilities.PyRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A Kotlin value with no Python counterpart. */
private class NoPythonCounterpart

/**
 * Issue #524 items 3 and 4: the collection hierarchy, element types and names of the owner's
 * `python_for_kotlin_binding.mermaid` (SPEC O-5).
 *
 * Red before: the file does not compile. `PyMutableIterable` does not exist, `PyList` is a
 * `MutableList<PyObject>` (so `add(1L)` and `contains("x")` do not type-check), `PyIterator` is a class
 * with no `remove()`, and the native projections are `toNativeList()` / `toNativeMap()` / `toNativeSet()`.
 */
class IterableHierarchyTest {

    private fun pyList(source: String): PyList = PyList(PythonTestFixture.eval(source).pointer, true)

    @Test
    fun theHierarchyIsTheMermaids() = PythonTestFixture.withInterpreter {
        val list: Any = pyList("[1]")
        assertIs<PyObject>(list)
        assertIs<PyMutableIterable>(list)
        assertIs<PyIterable>(list)
        assertIs<MutableList<*>>(list)
        val set: Any = PySet.fromSet(setOf(1L))
        assertIs<PyMutableIterable>(set)
        assertIs<MutableSet<*>>(set)
        val tuple: Any = PyTuple.fromList(listOf(1L))
        assertIs<PyIterable>(tuple)
        assertFalse(tuple is PyMutableIterable, "a tuple is immutable")
        assertIs<PyIterable>(PyRange.of(0, 3))
        assertIs<PyIterable>(PyFrozenSet.fromSet(setOf(1L)))
    }

    @Test
    fun aListTakesKotlinValuesAndAnswersPythonObjects() = PythonTestFixture.withInterpreter {
        val list = pyList("[]")
        list.add(1L)
        list.add("x")
        list.add(null)
        list.add(listOf(2, 3))
        list.add(true)
        list.add(1.5)
        assertEquals("[1, 'x', None, [2, 3], True, 1.5]", list.toString())
        assertTrue(list.contains("x"))
        assertTrue(1L in list)
        assertEquals(1, list.indexOf("x"))
        assertEquals(2, list.indexOf(null))
        val first: PyObject = list[0]
        assertEquals("1", first.toString())
        list[0] = "replaced"
        assertEquals("replaced", list[0].toString())
        assertTrue(list.remove("x"))
        assertEquals(listOf("replaced", null, listOf(2L, 3L), true, 1.5), list.toList())
    }

    @Test
    fun aPythonObjectElementIsStoredAsItself() = PythonTestFixture.withInterpreter {
        Python3.exec("_ih_probe = object()")
        val probe = PythonTestFixture.eval("_ih_probe")
        val list = PyList.fromList(listOf(probe))
        list.add(probe)
        // `object()` compares by identity, so `is` proves the list holds the object itself.
        PyDict(PythonTestFixture.mainGlobals().pointer, true)["_ih_list"] = list
        try {
            assertEquals("True", PythonTestFixture.eval("_ih_list[0] is _ih_probe and _ih_list[1] is _ih_probe").toString())
        } finally {
            Python3.exec("del _ih_list, _ih_probe")
        }
    }

    @Test
    fun fromListConvertsAndToListProjects() = PythonTestFixture.withInterpreter {
        val list = PyList.fromList(listOf(1, "a", null, mapOf("k" to 2)))
        assertEquals("[1, 'a', None, {'k': 2}]", list.toString())
        assertEquals(listOf(1L, "a", null, mapOf("k" to 2L)), list.toList())
        val tuple = PyTuple.fromList(listOf(1L, "b"))
        assertEquals("(1, 'b')", tuple.toString())
        assertEquals(listOf(1L, "b"), tuple.toList())
        assertTrue(tuple.contains("b"))
    }

    @Test
    fun aDictTakesKotlinKeysAndValues() = PythonTestFixture.withInterpreter {
        val dict = PyDict.fromMap(mapOf("a" to 1))
        dict.put("b", listOf(1, 2))
        dict["c"] = null
        assertTrue(dict.containsKey("b"))
        assertTrue(dict.containsValue(1L))
        val read: PyObject? = dict["a"]
        assertEquals("1", read.toString())
        assertEquals("[1, 2]", dict.get("b").toString())
        assertEquals<Map<Any?, Any?>>(mapOf("a" to 1L, "b" to listOf(1L, 2L), "c" to null), dict.toMap())
        assertEquals("1", dict.remove("a").toString())
        assertFalse(dict.containsKey("a"))
    }

    @Test
    fun aSetTakesKotlinValuesAndItsIteratorRemovesFromIt() = PythonTestFixture.withInterpreter {
        val set = PySet.fromSet(setOf(1L, 2L))
        assertTrue(set.add(3L))
        assertFalse(set.add(3L), "adding an element already present answers false")
        assertTrue(set.contains(2L))
        assertEquals(setOf(1L, 2L, 3L), set.toSet())
        val iterator: PyIterator = set.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().toString() == "2") iterator.remove()
        }
        assertEquals(setOf(1L, 3L), set.toSet())
        assertEquals(setOf(1L), PyFrozenSet.fromSet(setOf(1L)).toSet())
    }

    @Test
    fun aListIteratorIsAPyIteratorThatRemovesFromTheList() = PythonTestFixture.withInterpreter {
        val list = pyList("[1, 2, 3]")
        val iterator: PyIterator = list.iterator()
        while (iterator.hasNext()) {
            val element: PyObject = iterator.next()
            if (element.toString() == "2") iterator.remove()
        }
        assertEquals(listOf(1L, 3L), list.toList())
    }

    @Test
    fun anImmutableIterableOrAPythonIteratorCannotRemove() = PythonTestFixture.withInterpreter {
        val tupleIterator = PyTuple.fromList(listOf(1L)).iterator()
        assertIs<PyIteratorObject>(tupleIterator)
        tupleIterator.next()
        assertFailsWith<UnsupportedOperationException> { tupleIterator.remove() }
        val frozenIterator = PyFrozenSet.fromSet(setOf(1L)).iterator()
        frozenIterator.next()
        assertFailsWith<UnsupportedOperationException> { frozenIterator.remove() }
    }

    @Test
    fun aRangeIsAReadOnlyCollection() = PythonTestFixture.withInterpreter {
        val range = PyRange.of(0, 5, 2)
        assertEquals(3, range.size)
        assertTrue(range.contains(4L))
        assertFalse(range.contains(3L))
        assertEquals(listOf("0", "2", "4"), range.map { it.toString() })
    }

    @Test
    fun aKotlinValueWithNoPythonCounterpartIsRefused() = PythonTestFixture.withInterpreter {
        val list = pyList("[]")
        assertFailsWith<PyTypeError> { list.add(NoPythonCounterpart()) }
        assertTrue(list.isEmpty(), "a refused element must leave the list as it was")
    }
}
