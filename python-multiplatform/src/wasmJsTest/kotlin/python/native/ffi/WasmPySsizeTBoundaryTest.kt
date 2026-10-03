package python.native.ffi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.PythonTestFixture

/**
 * `Py_ssize_t` across the `expect`/`actual` boundary, which on this target is 64 bits on one side
 * and 32 on the other.
 *
 * There are two halves to getting it right and they fail in completely different ways:
 *
 *  * the **declaration** in `bindings.kt`. Wrong there and the module raises a `LinkError` at
 *    instantiation and no test runs at all. That half is checked against `python.wasm`'s own type
 *    section by the Gradle task `verifyWasmAbiSignatures`, before this suite starts.
 *  * the **conversion** in `EmbedAPI.wasmJs.kt`. Wrong there and everything links, every test
 *    passes, and one value in a million is silently wrong. That half is this file.
 *
 * The two conversions have opposite hazards, so both are asserted here rather than one being taken
 * as evidence for the other: widening must sign-extend (because -1 is the error return) and
 * narrowing must not truncate quietly.
 */
class WasmPySsizeTBoundaryTest {

    private fun <T> py(block: () -> T): T = Python3.withPython(block)

    @Test
    fun sizesComeBackAsThemselves() {
        PythonTestFixture.withInterpreter {
            py {
                val list = assertNotNull(PyList_New(0L), "PyList_New(0)")
                try {
                    assertEquals(0L, PyList_Size(list))
                    repeat(5) { i ->
                        val item = assertNotNull(PyLong_FromLongLong(i.toLong()))
                        assertEquals(0, PyList_Append(list, item))
                        Py_DecRef(item)
                    }
                    assertEquals(5L, PyList_Size(list))
                    assertEquals(5L, PyObject_Size(list))
                    assertEquals(5L, PyObject_Length(list))

                    // A borrowed item read back by index -- the argument side of the same type.
                    val third = assertNotNull(PyList_GetItem(list, 2L))
                    assertEquals(2L, PyLong_AsLongLong(third))
                } finally {
                    Py_DecRef(list)
                }

                val tuple = assertNotNull(PyTuple_New(3L), "PyTuple_New(3)")
                try {
                    repeat(3) { i ->
                        val item = assertNotNull(PyLong_FromLongLong((i * 10).toLong()))
                        assertEquals(0, PyTuple_SetItem(tuple, i.toLong(), item)) // steals the reference
                    }
                    assertEquals(3L, PyTuple_Size(tuple))
                    assertEquals(20L, PyLong_AsLongLong(assertNotNull(PyTuple_GetItem(tuple, 2L))))
                    val slice = assertNotNull(PyTuple_GetSlice(tuple, 1L, 3L))
                    assertEquals(2L, PyTuple_Size(slice))
                    Py_DecRef(slice)
                } finally {
                    Py_DecRef(tuple)
                }

                val dict = assertNotNull(PyDict_New())
                try {
                    assertEquals(0L, PyDict_Size(dict))
                } finally {
                    Py_DecRef(dict)
                }

                val set = assertNotNull(PySet_New(NativePointer(0)))
                try {
                    assertEquals(0L, PySet_Size(set))
                } finally {
                    Py_DecRef(set)
                }
            }
        }
    }

    @Test
    fun theErrorReturnIsMinusOneAndNotFourBillion() {
        // The failure this exists for: widening the `i32` result through `toUInt().toLong()` -- the
        // rule that is *correct* for pointers on this target, and wrong here -- turns CPython's -1
        // into 4294967295. That passes `if (n < 0)` and is then used as a length.
        PythonTestFixture.withInterpreter {
            py {
                val notASequence = assertNotNull(PyLong_FromLongLong(42L))
                try {
                    val size = PyObject_Size(notASequence)
                    assertEquals(-1L, size, "an unsized object must report -1, not an unsigned widening")
                    assertTrue(PyErr_Occurred() != null, "PyObject_Size should have set the error indicator")
                    PyErr_Clear()

                    val length = PyObject_Length(notASequence)
                    assertEquals(-1L, length)
                    PyErr_Clear()
                } finally {
                    Py_DecRef(notASequence)
                }
            }
        }
    }

    @Test
    fun anArgumentTooBigForTheAbiIsRejectedRatherThanTruncated() {
        // 0x1_0000_0000 truncates to 0. Left unchecked, `PyList_New(4294967296)` would quietly hand
        // back an empty list -- a wrong answer rather than a failure, and one that surfaces
        // somewhere else entirely.
        PythonTestFixture.withInterpreter {
            assertFailsWith<IllegalArgumentException> { py { PyList_New(0x1_0000_0000L) } }
            assertFailsWith<IllegalArgumentException> { py { PyTuple_New(0x1_0000_0000L) } }
            assertFailsWith<IllegalArgumentException> {
                py {
                    val list = assertNotNull(PyList_New(0L))
                    try {
                        PyList_GetItem(list, Long.MAX_VALUE)
                    } finally {
                        Py_DecRef(list)
                    }
                }
            }
        }
    }

    @Test
    fun theWholeInt32RangeStillGoesThrough() {
        // The check must not reject values that do fit, including the negative ones CPython uses
        // for relative indices and for its own error returns.
        assertEquals(-1, (-1L).toPySsize())
        assertEquals(0, 0L.toPySsize())
        assertEquals(Int.MAX_VALUE, Int.MAX_VALUE.toLong().toPySsize())
        assertEquals(Int.MIN_VALUE, Int.MIN_VALUE.toLong().toPySsize())
        assertFailsWith<IllegalArgumentException> { (Int.MAX_VALUE.toLong() + 1).toPySsize() }
        assertFailsWith<IllegalArgumentException> { (Int.MIN_VALUE.toLong() - 1).toPySsize() }

        assertEquals(-1L, (-1).pySsizeToLong())
        assertEquals(Int.MIN_VALUE.toLong(), Int.MIN_VALUE.pySsizeToLong())
    }
}
