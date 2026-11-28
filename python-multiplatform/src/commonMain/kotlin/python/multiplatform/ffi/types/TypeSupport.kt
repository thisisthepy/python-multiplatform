package python.multiplatform.ffi.types

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pyErrorOrGeneric
import python.multiplatform.ffi.types.basic.PyNone
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_IsInstance
import python.native.ffi.Py_DecRef

/**
 * Shared plumbing for the #524 wrapper types (generator, coroutine, async generator, cell, code,
 * frame, weak reference, descriptors, property, package).
 *
 * None of those types has a Stable ABI accessor family (`PyGen_*`, `PyCode_*`, `PyFrame_*` and
 * `PyCell_*` are all outside the limited API), so like `PyFunction` and `PyRange` they read their
 * documented Python attributes (`gi_frame`, `co_name`, `cell_contents`, ...) and call their Python
 * methods (`send`, `__get__`, ...).
 */

/** `isinstance(this, <module>.<typeName>)`. */
internal fun PyObject.isInstanceOf(module: String, typeName: String): Boolean {
    val type = Python3.import(module).use { it.getAttr(typeName) }
    return type.use {
        val result = Python3.withPython { PyObject_IsInstance(pointer, it.pointer) }
        if (result < 0) throw pyErrorOrGeneric("isinstance(..., $module.$typeName) failed")
        result == 1
    }
}

/**
 * A new wrapper of [T] over the object [obj] holds, taking its own reference (`borrowed = true`), after
 * checking that it is an instance of `<module>.<typeName>`. [what] names the kind in the error.
 */
internal inline fun <T : PyObject> adoptChecked(
    obj: PyObject,
    module: String,
    typeName: String,
    what: String,
    wrap: (NativePointer) -> T,
): T {
    require(obj.isInstanceOf(module, typeName)) {
        "expected $what ($module.$typeName), got ${obj.Type.name}"
    }
    return wrap(obj.pointer)
}

/**
 * `self.<attr>` as a new [PyObject], or `null` when the attribute is absent or is `None`. An absent
 * attribute clears the error indicator, as the other `OrNull` readers do.
 */
internal fun PyObject.attrObjectOrNull(attr: String): PyObject? {
    val attrPointer = Python3.withPython { PyObject_GetAttrString(pointer, attr) } ?: run {
        Python3.withPython { PyErr_Clear() }
        return null
    }
    if (attrPointer == PyNone.get().pointer) {
        Python3.withPython { Py_DecRef(attrPointer) }
        return null
    }
    return PyObject(attrPointer, false)
}

/** `self.<attr>` wrapped by [wrap] (which takes its own reference), or `null` for `None` or absent. */
internal inline fun <T : PyObject> PyObject.attrWrappedOrNull(attr: String, wrap: (NativePointer) -> T): T? =
    attrObjectOrNull(attr)?.use { wrap(it.pointer) }

/** `self.<attr>` as a Kotlin `Boolean` (`bool(self.<attr>)`). */
internal fun PyObject.attrAsBoolean(attr: String): Boolean = getAttr(attr).use { it.truthy() }

/** `bool(self)`. */
internal fun PyObject.truthy(): Boolean {
    val result = Python3.withPython { python.native.ffi.PyObject_IsTrue(pointer) }
    if (result < 0) throw pyErrorOrGeneric("bool() failed")
    return result == 1
}

/** `self.<attr>`, a tuple or list of `str`, as Kotlin strings. */
internal fun PyObject.attrAsStringList(attr: String): List<String> = getAttr(attr).use { sequence ->
    val list = Python3.import("builtins").use { it.getAttr("list") }.use { it(sequence) }
    list.use {
        val size = Python3.withPython { python.native.ffi.PyObject_Length(it.pointer) }
        if (size < 0) throw pyErrorOrGeneric("len() failed")
        (0 until size.toInt()).map { index ->
            python.multiplatform.ffi.types.basic.PyInt.from(index.toLong()).use { key ->
                it.getAttr("__getitem__").use { getItem -> getItem(key) }.use { item -> item.toString() }
            }
        }
    }
}

/** [value], or `None` when it is `null`, as an argument for a Python call. */
internal fun noneIfNull(value: PyObject?): PyObject = value ?: PyNone.get()
