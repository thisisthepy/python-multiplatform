package python.multiplatform.ffi.types.collections

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.adoptingNewReference
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyDict_Clear
import python.native.ffi.PyDict_Contains
import python.native.ffi.PyDict_DelItem
import python.native.ffi.PyDict_GetItem
import python.native.ffi.PyDict_Items
import python.native.ffi.PyDict_New
import python.native.ffi.PyDict_SetItem
import python.native.ffi.PyDict_Size
import python.native.ffi.PyDict_Values
import python.native.ffi.PyList_AsTuple
import python.native.ffi.PySequence_Contains
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.Py_DecRef

/**
 * Wrapper around a Python `dict` object as a Kotlin `MutableMap` (the owner's mermaid:
 * `PyObject <|-- PyDict`, `MutableMap <|.. PyDict`, with `get(key Any) Any`, `set(key Any, value Any)`,
 * `remove(key Any) Any`; issue #524 item 4). Keys and values are `Any?` on the way in, converted as
 * [PyMutableIterable] describes, and [PyObject]s on the way out.
 *
 * Backed by `PyDict_New`/`PyDict_GetItem`/`PyDict_SetItem`/`PyDict_DelItem`/
 * `PyDict_Contains`/`PyDict_Keys`/`PyDict_Values`/`PyDict_Items`/`PyDict_Clear`/
 * `PyDict_Size` (the last backs [size] directly).
 *
 * Note: the previous stub declared `PyProxy<Boolean>`, which was almost
 * certainly copy-pasted from [python.multiplatform.ffi.types.basic.PyBool]
 * and never updated -- corrected here to `PyProxy<Map<Any?, Any?>>`, the
 * natural "fully native" projection of a dict.
 */
open class PyDict(pointer: NativePointer, borrowed: Boolean) :
    PyObject(pointer, borrowed), PyProxy<Map<Any?, Any?>>, MutableMap<Any?, Any?> {

    companion object {
        /** The `PyType` for `dict` (`builtins.dict`). */
        val TYPE: PyType by lazy {
            val emptyDict = python.multiplatform.ffi.Python3.withPython { PyDict_New() }
                ?: throw PyException.fromCurrentError() ?: PyException("Failed to build dict() to derive its type")
            deriveTypeAndRelease(emptyDict)
        }

        /**
         * Builds a new Python `dict` from [map] (the mermaid's `+fromMap(map Map) PyDict$`); a [PyObject]
         * key or value is stored as itself, any other value is converted as [PyMutableIterable] describes.
         */
        fun fromMap(map: Map<*, *>): PyDict {
            val dictPtr = python.multiplatform.ffi.Python3.withPython { PyDict_New() } ?: throw PyException.fromCurrentError() ?: PyException("Failed to allocate dict")
            // The dict is a bare pointer until the wrapper below adopts it, and populating it is
            // fallible -- an unhashable key is enough. Dropping it there would leak the dict and,
            // with it, every key and value it had already taken a reference to, with no wrapper
            // for a cleaner to hang off. See ROADMAP §4.
            return dictPtr.adoptingNewReference {
                for ((k, v) in map) {
                    // PyDict_SetItem does not steal references to either key or value.
                    val rc = withPythonElement(k) { key ->
                        withPythonElement(v) { value -> python.multiplatform.ffi.Python3.withPython { PyDict_SetItem(it, key, value) } }
                    }
                    if (rc != 0) {
                        throw PyException.fromCurrentError() ?: PyException("Failed to populate dict")
                    }
                }
                PyDict(it, false)
            }
        }
    }

    override var cachedNativeValue: Map<Any?, Any?>? = null
    override var cachedPyObjectValue: PyObject? = null

    /**
     * A single `(key, value)` snapshot pair. Backs [entries]/[keys]/[values]
     * (which are computed, disconnected-from-the-live-dict-order snapshots
     * that write back through to `this` [PyDict] on mutation) as well as
     * [toMap].
     */
    private class DictEntry(
        override val key: PyObject,
        initialValue: PyObject,
        private val owner: PyDict,
    ) : MutableMap.MutableEntry<Any?, Any?> {
        override var value: PyObject = initialValue
            private set

        override fun setValue(newValue: Any?): PyObject {
            val old = value
            owner.put(key, newValue)
            value = owner.get(key) ?: throw IllegalStateException("the key vanished while its value was set")
            return old
        }
    }

    /** One-shot, consistent `(key, value)` snapshot via `PyDict_Items` (avoids separate `Keys`/`Values` calls drifting out of sync). */
    private fun snapshotEntries(): List<DictEntry> {
        val itemsPtr = python.multiplatform.ffi.Python3.withPython { PyDict_Items(pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("PyDict_Items failed")
        val itemsTuplePtr = python.multiplatform.ffi.Python3.withPython { PyList_AsTuple(itemsPtr) }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(itemsPtr) } // list of (k, v) 2-tuples, new reference, no longer needed once copied
        if (itemsTuplePtr == null) throw PyException.fromCurrentError() ?: PyException("Failed to snapshot dict items")
        // Nothing adopts the snapshot tuple, so its release belongs in a finally: the loop below
        // dereferences three nullable results and would otherwise strand it on any of them.
        try {
            val count = python.multiplatform.ffi.Python3.withPython { PyTuple_Size(itemsTuplePtr) }
            val result = ArrayList<DictEntry>(count.toInt())
            for (i in 0 until count) {
                val pairPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(itemsTuplePtr, i) }!! // borrowed, valid while itemsTuplePtr is alive
                val keyPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(pairPtr, 0) }!! // borrowed
                val valPtr = python.multiplatform.ffi.Python3.withPython { PyTuple_GetItem(pairPtr, 1) }!! // borrowed
                result.add(DictEntry(PyObject(keyPtr, true), PyObject(valPtr, true), this))
            }
            return result
        } finally {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(itemsTuplePtr) }
        }
    }

    /**
     * Snapshot conversion to a plain Kotlin map, recursively converting keys/values
     * ([python.multiplatform.ffi.conversion.ConversionStrategy.NATIVE]): the mermaid's `toMap()`
     * (on `PyType`, `+toMap() Map`; issue #524 item 4).
     */
    fun toMap(): Map<Any?, Any?> {
        val result = LinkedHashMap<Any?, Any?>()
        for (e in snapshotEntries()) {
            result[pyObjectToNative(e.key)] = pyObjectToNative(e.value)
        }
        return result
    }

    private inner class KeySnapshotSet(private val snapshot: List<PyObject>) : AbstractMutableSet<Any?>() {
        override val size: Int get() = snapshot.size
        override fun iterator(): MutableIterator<Any?> = object : MutableIterator<Any?> {
            var idx = 0
            override fun hasNext() = idx < snapshot.size
            override fun next(): PyObject = snapshot[idx++]
            override fun remove() {
                check(idx > 0) { "next() has not been called, or remove() already called" }
                this@PyDict.remove(snapshot[idx - 1])
            }
        }
        override fun add(element: Any?): Boolean = throw UnsupportedOperationException("Cannot add a key without a value")
    }

    private inner class ValueSnapshotCollection(
        private val values: List<PyObject>,
        private val keys: List<PyObject>,
    ) : AbstractMutableCollection<Any?>() {
        override val size: Int get() = values.size
        override fun iterator(): MutableIterator<Any?> = object : MutableIterator<Any?> {
            var idx = 0
            override fun hasNext() = idx < values.size
            override fun next(): PyObject = values[idx++]
            override fun remove() {
                check(idx > 0) { "next() has not been called, or remove() already called" }
                this@PyDict.remove(keys[idx - 1])
            }
        }
        override fun add(element: Any?): Boolean = throw UnsupportedOperationException("Cannot add to a dict's value view")
    }

    private inner class EntrySnapshotSet(
        private val snapshot: List<MutableMap.MutableEntry<Any?, Any?>>,
    ) : AbstractMutableSet<MutableMap.MutableEntry<Any?, Any?>>() {
        override val size: Int get() = snapshot.size
        override fun iterator(): MutableIterator<MutableMap.MutableEntry<Any?, Any?>> =
            object : MutableIterator<MutableMap.MutableEntry<Any?, Any?>> {
                var idx = 0
                override fun hasNext() = idx < snapshot.size
                override fun next(): MutableMap.MutableEntry<Any?, Any?> = snapshot[idx++]
                override fun remove() {
                    check(idx > 0) { "next() has not been called, or remove() already called" }
                    this@PyDict.remove(snapshot[idx - 1].key)
                }
            }
        override fun add(element: MutableMap.MutableEntry<Any?, Any?>): Boolean =
            throw UnsupportedOperationException("Cannot add a raw entry; use PyDict.put")
    }

    override val entries: MutableSet<MutableMap.MutableEntry<Any?, Any?>>
        get() = EntrySnapshotSet(snapshotEntries())
    override val keys: MutableSet<Any?>
        get() = KeySnapshotSet(snapshotEntries().map { it.key })
    override val values: MutableCollection<Any?>
        get() {
            val es = snapshotEntries()
            return ValueSnapshotCollection(es.map { it.value }, es.map { it.key })
        }

    override val size: Int
        get() = python.multiplatform.ffi.Python3.withPython { PyDict_Size(pointer) }.toInt()

    override fun clear() {
        python.multiplatform.ffi.Python3.withPython { PyDict_Clear(pointer) }
    }

    override fun containsKey(key: Any?): Boolean {
        val result = withPythonElement(key) { k -> python.multiplatform.ffi.Python3.withPython { PyDict_Contains(pointer, k) } }
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check dict key membership")
        return result == 1
    }

    override fun containsValue(value: Any?): Boolean {
        val valuesPtr = python.multiplatform.ffi.Python3.withPython { PyDict_Values(pointer) }
            ?: throw PyException.fromCurrentError() ?: PyException("PyDict_Values failed")
        val result = try {
            withPythonElement(value) { v -> python.multiplatform.ffi.Python3.withPython { PySequence_Contains(valuesPtr, v) } }
        } catch (e: Throwable) {
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(valuesPtr) }
            throw e
        }
        python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(valuesPtr) } // new reference to a list, no longer needed
        if (result == -1) throw PyException.fromCurrentError() ?: PyException("Failed to check dict value membership")
        return result == 1
    }

    override fun get(key: Any?): PyObject? = withPythonElement(key) { k ->
        // PyDict_GetItem returns a borrowed reference (and, unusually, suppresses errors --
        // null unambiguously means "missing key" here, not "error"). Wrapped (incref) before the
        // temporary key, if any, is released.
        val item = python.multiplatform.ffi.Python3.withPython { PyDict_GetItem(pointer, k) } ?: return@withPythonElement null
        PyObject(item, true)
    }

    override fun isEmpty(): Boolean = size == 0

    override fun put(key: Any?, value: Any?): PyObject? {
        val previous = get(key)
        // PyDict_SetItem does not steal references to either key or value.
        val rc = withPythonElement(key) { k ->
            withPythonElement(value) { v -> python.multiplatform.ffi.Python3.withPython { PyDict_SetItem(pointer, k, v) } }
        }
        if (rc != 0) throw PyException.fromCurrentError() ?: PyException("Failed to set dict item")
        return previous
    }

    /** `d[key] = value` (the mermaid's `+set(key Any, value Any) Any`): [put], answering the previous value. */
    operator fun set(key: Any?, value: Any?): PyObject? = put(key, value)

    override fun putAll(from: Map<out Any?, Any?>) {
        for ((k, v) in from) put(k, v)
    }

    override fun remove(key: Any?): PyObject? {
        val previous = get(key) ?: return null
        val rc = withPythonElement(key) { k -> python.multiplatform.ffi.Python3.withPython { PyDict_DelItem(pointer, k) } }
        if (rc != 0) throw PyException.fromCurrentError() ?: PyException("Failed to delete dict item")
        return previous
    }
}
