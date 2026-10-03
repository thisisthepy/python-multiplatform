package python.multiplatform.env

import python.multiplatform.ffi.Python3
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyList_Append
import python.native.ffi.PyList_GetItem
import python.native.ffi.PyList_Insert
import python.native.ffi.PyList_Size
import python.native.ffi.PySys_GetObject
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.PyUnicode_FromString
import python.native.ffi.Py_DecRef

/**
 * `sys.path`, as a Kotlin list you can add to.
 *
 * ### Why this is not `Python3.exec("import sys; sys.path.insert(...)")`
 *
 * That form has to be *parsed and compiled* on every call (`Python3.exec` goes through
 * `PyRun_String`, measured at ~11 µs for a trivial statement in `EvalCheckpointTest`), and it
 * interpolates a filesystem path into Python source — a payload directory containing a quote or a
 * backslash, which every Windows path does, either breaks the statement or changes what it does.
 * Going through the list object directly has neither problem.
 *
 * ### `PYTHONHOME` is a different mechanism and this does not touch it
 *
 * `PYTHONHOME` names the *prefix* CPython finds its standard library under, is read by
 * `getenv(3)` before `Py_Initialize()`, and is verified by [PythonHomeCheck]. This is the
 * consumer's own code, added to the path CPython has already built. Neither substitutes for the
 * other: an application with a payload and no `PYTHONHOME` cannot start at all, and one with a
 * `PYTHONHOME` and no payload starts fine and cannot import the application.
 *
 * ### Ordering
 *
 * [prepend] puts the entry at `sys.path[0]`, which is where CPython itself puts the directory of
 * the script being run. That is the right position for a payload — it *is* the application's own
 * code, and an application must be able to ship a module that overrides one it inherits. The cost
 * of that choice is that a payload module named after a standard library one shadows it, so
 * [append] exists for an embedder who wants the opposite and knows why.
 *
 * Every function here takes the GIL; none may be called before `Py_Initialize()`, because
 * `sys.path` does not exist until then (see [PythonPayload] for where that lands in start-up).
 */
object PythonPath {

    /**
     * `sys.path` as it is now.
     *
     * Non-string entries are skipped rather than rendered: `sys.path` legally holds anything the
     * import machinery's path hooks understand, and a `zipimporter`-style object has no meaningful
     * text form for the only two callers here ([contains] and diagnostics).
     */
    fun entries(): List<String> = Python3.withPython {
        val path = sysPathHoldingGIL() ?: return@withPython emptyList()
        readEntriesHoldingGIL(path)
    }

    /** Whether [directory] is already on `sys.path`, compared verbatim. */
    fun contains(directory: String): Boolean = entries().contains(directory)

    /**
     * Inserts [directory] at `sys.path[0]` unless it is already somewhere on the path.
     *
     * @return true when the entry was added, false when it was already there. Idempotent: calling
     *   it twice leaves one entry, which matters because start-up paths on some platforms can run
     *   more than once (an Android activity recreated, a host app that initialises defensively).
     */
    fun prepend(directory: String): Boolean = insert(directory, 0L)

    /** As [prepend], but at the end of `sys.path`. */
    fun append(directory: String): Boolean = insert(directory, APPEND)

    private fun insert(directory: String, index: Long): Boolean = Python3.withPython {
        val path = sysPathHoldingGIL()
            ?: throw IllegalStateException(
                "sys.path is not available. It is created by Py_Initialize(), so nothing can be " +
                    "added to it before the interpreter is up -- see PythonPayload for where " +
                    "start-up does this."
            )
        if (readEntriesHoldingGIL(path).contains(directory)) return@withPython false

        // PyUnicode_FromString: new reference, owned here.
        val entry = PyUnicode_FromString(directory)
            ?: run {
                PyErr_Clear()
                throw IllegalStateException("could not convert '$directory' to a Python string for sys.path")
            }
        try {
            // Neither PyList_Insert nor PyList_Append steals -- both take a reference of their
            // own, unlike PyList_SetItem. So the reference taken above is still ours to release,
            // and not releasing it would leak one string per call.
            val status = if (index == APPEND) PyList_Append(path, entry) else PyList_Insert(path, index, entry)
            if (status != 0) {
                PyErr_Clear()
                throw IllegalStateException("could not add '$directory' to sys.path")
            }
        } finally {
            Py_DecRef(entry)
        }
        true
    }

    /** `sys.path`, a **borrowed** reference — `PySys_GetObject` does not hand ownership over. */
    private fun sysPathHoldingGIL(): NativePointer? = PySys_GetObject("path")

    private fun readEntriesHoldingGIL(path: NativePointer): List<String> {
        val size = PyList_Size(path)
        if (size < 0L) {
            // sys.path replaced by something that is not a list. Legal Python, useless here.
            PyErr_Clear()
            return emptyList()
        }
        val entries = ArrayList<String>(size.toInt())
        for (index in 0L until size) {
            // PyList_GetItem: borrowed reference, valid while the list holds it.
            val item = PyList_GetItem(path, index)
            if (item == null) {
                PyErr_Clear()
                continue
            }
            val text = PyUnicode_AsUTF8(item)
            if (text == null) PyErr_Clear() else entries.add(text)
        }
        return entries
    }

    /** Sentinel for [insert]: append rather than insert at an index. */
    private const val APPEND: Long = -1L
}
