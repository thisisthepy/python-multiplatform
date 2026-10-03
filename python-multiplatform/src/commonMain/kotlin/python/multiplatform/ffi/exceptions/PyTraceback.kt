package python.multiplatform.ffi.exceptions

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.pyErrorOrGeneric
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyLong_AsInt
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.Py_DecRef

/**
 * Wraps a Python traceback object (`PyTracebackObject`, i.e. what
 * `PyException_GetTraceback` returns). Tracebacks form a singly linked list
 * from the outermost frame to the one where the exception was raised.
 *
 * `PyFrame` is explicitly out of scope for this pass (see task notes), so
 * [frame] is intentionally omitted; only the bits needed to render a
 * human-readable trace are exposed. There is no dedicated traceback/frame/code
 * FFI surface in this project (no `tb_lineno`/`tb_next`/`tb_frame` bindings),
 * so these read the well-known Python-level attributes directly
 * (`tb_lineno`, `tb_next`, `tb_frame.f_code.co_filename`) via the generic
 * `PyObject_GetAttrString`, each hop releasing its own new reference once the
 * value has been extracted.
 */
class PyTraceback(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed) {

    /** The line number that was executing in this frame when the exception propagated through it. */
    val lineNumber: Int
        get() {
            val linenoPointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "tb_lineno") }
                ?: throw pyErrorOrGeneric("Failed to read tb_lineno")
            val value = python.multiplatform.ffi.Python3.withPython { PyLong_AsInt(linenoPointer) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(linenoPointer) }
            return value
        }

    /** Name of the source file for this frame, if known. */
    val fileName: String?
        get() {
            // This getter reports "unknown" as `null` rather than throwing,
            // so every early-return below must also clear whatever error
            // indicator the failed lookup left set (see the *OrNull helpers
            // on PyObject for the same "don't call back into the C API with
            // a pending exception" contract).
            val framePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "tb_frame") } ?: run {
                python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                return null
            }
            val codePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(framePointer, "f_code") }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(framePointer) }
            if (codePointer == null) {
                python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                return null
            }

            val filenamePointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(codePointer, "co_filename") }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(codePointer) }
            if (filenamePointer == null) {
                python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                return null
            }

            val name = python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(filenamePointer) }
            python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(filenamePointer) }
            return name
        }

    /** The next traceback entry (one frame further down the call stack), or `null` at the end of the chain. */
    val next: PyTraceback?
        get() {
            // tb_next is either another traceback object or None at the end
            // of the chain; PyObject_GetAttrString hands back a new reference
            // either way, which the returned PyTraceback (borrowed = false)
            // then owns.
            val nextPointer = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "tb_next") } ?: run {
                python.multiplatform.ffi.Python3.withPython { PyErr_Clear() }
                return null
            }
            if (nextPointer.isNoneObject()) {
                python.multiplatform.ffi.Python3.withPython { python.native.ffi.Py_DecRef(nextPointer) }
                return null
            }
            return PyTraceback(nextPointer, false)
        }

    /** Renders this traceback (and the chain reachable via [next]) the way Python's `traceback` module would. */
    fun format(): String {
        val builder = StringBuilder("Traceback (most recent call last):\n")
        var current: PyTraceback? = this
        while (current != null) {
            val location = current.fileName ?: "<unknown>"
            builder.append("  File \"").append(location).append("\", line ").append(current.lineNumber).append('\n')
            current = current.next
        }
        return builder.toString()
    }
}
