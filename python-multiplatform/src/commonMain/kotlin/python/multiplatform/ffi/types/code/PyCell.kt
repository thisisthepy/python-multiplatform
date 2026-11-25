package python.multiplatform.ffi.types.code

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.types.adoptChecked
import python.native.ffi.NativePointer

/**
 * Wrapper around a Python cell, the box a closure keeps a captured variable in (`f.__closure__[i]`)
 * (#524, the baseline's `types/code/PyCell.kt`).
 *
 * `PyCell_Get` / `PyCell_Set` are not in the Stable ABI; `cell_contents` is the documented attribute
 * that reads and writes the same slot, and an empty cell (a variable not assigned yet) is one whose
 * `cell_contents` raises `ValueError`.
 */
open class PyCell(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {
    companion object {
        /** [obj] as a cell (a new wrapper with its own reference); throws if it is not one. */
        fun from(obj: PyObject): PyCell =
            adoptChecked(obj, "types", "CellType", "a cell") { PyCell(it, borrowed = true) }

        /** A new cell holding [value] (`types.CellType(value)`), or an empty one when `null`. */
        fun of(value: PyObject? = null): PyCell {
            val cellType = Python3.import("types").use { it.getAttr("CellType") }
            val cell = cellType.use { if (value == null) it() else it(value) }
            return cell.use { PyCell(it.pointer, borrowed = true) }
        }
    }

    /** Whether the cell holds no value. */
    val isEmpty: Boolean get() = contentsOrNull() == null

    /**
     * The captured value: `cell.cell_contents`. Reading an empty cell throws
     * [python.multiplatform.ffi.exceptions.errors.PyValueError], as in Python; setting stores the value
     * the closure sees.
     */
    var contents: PyObject
        get() = getAttr("cell_contents")
        set(value) = setAttr("cell_contents", value)

    /** [contents], or `null` for an empty cell. */
    fun contentsOrNull(): PyObject? = getAttrOrNull("cell_contents")

    /** Empties the cell (`del cell.cell_contents`). */
    fun clear() = delAttr("cell_contents")
}
