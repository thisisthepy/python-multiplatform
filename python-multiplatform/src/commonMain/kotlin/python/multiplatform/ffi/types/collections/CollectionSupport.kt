package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyCompareOp
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.PyTypeChecks
import python.multiplatform.ffi.adoptingNewReference
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.types.basic.PyNone
import python.multiplatform.ffi.types.basic.readBufferAsByteArray
import python.native.ffi.HighOverheadNativeCall
import python.native.ffi.NativePointer
import python.native.ffi.PyFloat_AsDouble
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyObject_RichCompareBool
import python.native.ffi.PyObject_Type
import python.native.ffi.PySequence_Tuple
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef

/**
 * Small helpers shared by the collection wrappers in this package.
 */

/**
 * `a == b`, via the raw `PyObject_RichCompareBool` FFI call directly (opid
 * `Py_EQ` == 2, see [PyCompareOp.EQ]) rather than [PyObject.richCompare].
 *
 * The original reason for going direct was that `richCompare` was a `TODO`; it is implemented
 * now, and the direct call is kept for a different and better reason. This takes raw
 * `NativePointer`s, so the element-equality-dependent operations here (`indexOf`, `retainAll`,
 * `contains`, ...) can compare a candidate against every element without constructing a
 * [PyObject] wrapper per element -- and each wrapper is a refcount round trip plus a registered
 * cleaner. It also avoids the second crossing `richCompare` needs: `PyObject_RichCompare` returns
 * a new `PyObject*` that then has to go through `PyObject_IsTrue` and `Py_DecRef`, where
 * `PyObject_RichCompareBool` answers in one call.
 */
internal fun pyEquals(a: NativePointer, b: NativePointer): Boolean {
    val result = python.multiplatform.ffi.Python3.withPython { PyObject_RichCompareBool(a, b, PyCompareOp.EQ.opId) }
    if (result == -1) throw PyException.fromCurrentError() ?: PyException("Comparison failed")
    return result == 1
}

/**
 * Derives the [PyType] of a freshly-built, not-yet-published, exclusively
 * owned scratch instance (e.g. an empty `list()`/`dict()` built only to read
 * its `type`), then releases that scratch instance -- used by each
 * collection wrapper's `TYPE` companion property.
 */
internal fun deriveTypeAndRelease(instancePointer: NativePointer): PyType {
    // The scratch instance is released in a finally, not after the call: on the failure path it
    // used to be abandoned, and nothing had wrapped it, so it could never be reclaimed.
    try {
        val typePtr = python.multiplatform.ffi.Python3.withPython { PyObject_Type(instancePointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to get PyType of scratch instance")
        return typePtr.adoptingNewReference { PyType.getInstance(it) }
    } finally {
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(instancePointer) } // only needed to read its type
    }
}

/**
 * Snapshots an iterable Python object (typically a `set`/`frozenset`, which
 * support no indexed access) into a Kotlin `List<PyObject>`, via
 * `PySequence_Tuple` + `PyTuple_GetItem`. Used instead of driving a live
 * `PyIter_Next` loop so that mutating the *live* container afterwards (e.g.
 * `PySet`'s iterator `remove()`) never races with an in-progress raw
 * CPython iterator over the same container -- mutating a Python collection
 * while iterating it directly is undefined behaviour in CPython.
 */
internal fun snapshotElements(pointer: NativePointer): List<PyObject> {
    val tuplePtr = python.multiplatform.ffi.Python3.withPython { PySequence_Tuple(pointer) }
        ?: throw PyException.fromCurrentError() ?: PyException("Failed to snapshot elements (PySequence_Tuple failed)")
    // Nobody adopts the scratch tuple, so its release goes in a finally -- the `!!` in the loop
    // is a real throw site, and stranding the tuple there would be unrecoverable.
    try {
        val count = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(tuplePtr) }
        val result = ArrayList<PyObject>(count.toInt())
        for (i in 0 until count) {
            // Borrowed reference into tuplePtr, valid only while tuplePtr is alive -- wrap with
            // borrowed = true so each element gets its own independent, incref'd reference before
            // the scratch tuple below is released.
            val itemPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(tuplePtr, i) }!!
            result.add(PyObject(itemPtr, true))
        }
        return result
    } finally {
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(tuplePtr) } // scratch snapshot tuple
    }
}

/**
 * Recursively converts a live [PyObject] to a "fully native" Kotlin
 * projection ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]).
 *
 * `None` is checked first via the cheap, zero-FFI-call [PyNone.isNone]
 * (a pointer comparison against the already-resolved singleton). Everything
 * else is dispatched by fetching [obj]'s type *once* (`PyObject_Type`) and
 * comparing the resulting pointer against [PyTypeChecks]'s cached builtin
 * type objects -- plain Kotlin `NativePointer` equality, no further FFI
 * calls -- rather than the `PyType.name` string dispatch this used before
 * [PyTypeChecks] existed (which cost three FFI crossings, `PyObject_Type` +
 * `PyType_GetName` + `PyUnicode_AsUTF8`, and a UTF-8 decode, just to read a
 * name well enough to compare). This intentionally mirrors `PyLong_Check`'s
 * exact-type dispatch semantics, not `isinstance`'s subclass-inclusive ones:
 * a value of a *user-defined* subclass of e.g. `list` still falls through to
 * the `else` branch below, exactly as the old name-based dispatch did (its
 * exact `PyType.name` would have been the subclass's own name, not `"list"`).
 * Falls back to `str(obj)` (via [PyObject.toString]) for any type not
 * explicitly handled -- see the note on that branch below for why it is
 * still the fallback now that
 * [python.multiplatform.ffi.conversion.PyContext]'s `autoConvert`/
 * `proxyConvert` exist (ROADMAP §7b closed the eager path).
 */
@OptIn(HighOverheadNativeCall::class)
internal fun pyObjectToNative(obj: PyObject): Any? {
    if (PyNone.isNone(obj)) return null

    val typePtr = python.multiplatform.ffi.Python3.withPython { PyObject_Type(obj.pointer) }
        ?: throw PyException.fromCurrentError() ?: PyException("Failed to get the type of this object")
    try {
        return when (typePtr) {
            // bool is a subtype of int at the C level; PyLong_AsLongLong works on it directly.
            PyTypeChecks.boolType -> python.multiplatform.ffi.Python3.withPython { PyLong_AsLongLong(obj.pointer) } != 0L
            PyTypeChecks.intType -> python.multiplatform.ffi.Python3.withPython { PyLong_AsLongLong(obj.pointer) }
            PyTypeChecks.floatType -> python.multiplatform.ffi.Python3.withPython { PyFloat_AsDouble(obj.pointer) }
            PyTypeChecks.strType -> python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(obj.pointer) } ?: ""
            // A ByteArray is a copy, so it is cacheable under the same rule the scalars are --
            // see PyBytes's class doc for why the read goes through `hex()` and what it costs.
            // No wrapper is built on this path: readBufferAsByteArray takes the raw pointer.
            PyTypeChecks.bytesType, PyTypeChecks.bytearrayType -> readBufferAsByteArray(obj.pointer)
            PyTypeChecks.listType -> PyList(obj.pointer, true).toNativeList()
            PyTypeChecks.tupleType -> PyTuple(obj.pointer, true).toNativeList()
            PyTypeChecks.dictType -> PyDict(obj.pointer, true).toNativeMap()
            PyTypeChecks.setType -> PySet(obj.pointer, true).toNativeSet()
            PyTypeChecks.frozensetType -> PyFrozenSet(obj.pointer, true).toNativeSet()
            // TODO(open design question): what should NATIVE conversion of a user-defined object
            // produce? `str(obj)` is lossy and one-way -- `toNativeMap()`/`toNativeList()` on a
            // container holding one silently turns it into its repr, which no caller can convert
            // back. Deferring to PyContext is *not* the answer that was once assumed here: it is
            // implemented now (ROADMAP §7b), and `proxyConvert` returns a `PyValue` wrapping a
            // live PyObject, i.e. exactly what NATIVE exists to avoid. Calling it here would also
            // invert the layering -- CollectionSupport is below `conversion/`, which calls *into*
            // this function for ConversionStrategy.NATIVE.
            //
            // The question to answer first is which of these the boundary should carry, because
            // each has a different lifetime rule (see docs/design/object-lifetime.md):
            //   (a) a `Map<String, Any?>` built from the object's `__dict__`, recursively -- a
            //       real native projection, but it drops behaviour, cannot represent cycles, and
            //       needs a visited-set;
            //   (b) an upcall-table handle (§7), which is native in the sense that matters for
            //       Kotlin but is a reference, not a value;
            //   (c) keep `str(obj)` and document NATIVE as builtins-only, making the lossiness
            //       part of the contract rather than a gap.
            // Measure before choosing: (a)'s cost is one `PyObject_GetAttrString` plus a full
            // dict walk per object, against `str()`'s single call, on a container of N objects.
            else -> obj.toString()
        }
    } finally {
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(typePtr) } // PyObject_Type: new reference, only needed for the dispatch above
    }
}
