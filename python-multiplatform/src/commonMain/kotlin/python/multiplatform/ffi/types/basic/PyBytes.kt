package python.multiplatform.ffi.types.basic

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.PyType
import python.multiplatform.ffi.PyTypeChecks
import python.multiplatform.ffi.conversion.PyProxy
import python.multiplatform.ffi.pyErrorOrGeneric
import python.native.ffi.HighOverheadNativeCall
import python.native.ffi.NativePointer
import python.native.ffi.PyByteArray_FromObject
import python.native.ffi.PyObject_CallNoArgs
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.PyUnicode_AsUTF8
import python.native.ffi.PyUnicode_FromString
import python.native.ffi.Py_DecRef

/**
 * Wrapper around a Python `bytes` object, projecting onto a Kotlin [ByteArray].
 *
 * ## Why the projection is a copy, and why that is the whole point
 *
 * ROADMAP §7b refused `bytes`, `bytearray` and `memoryview` together, because
 * the obvious conversion (`PyBytes_AsStringAndSize`, or the buffer protocol)
 * hands out a pointer *into the object's own storage*: caching that would
 * outlive what it points at, and a `bytearray`'s buffer additionally moves when
 * it is resized. That reason is about the pointer, not about the type. A Kotlin
 * [ByteArray] is a copy by construction, so it satisfies
 * [python.multiplatform.ffi.conversion.isIndependentOfPythonMemory] exactly the
 * way the decoded `String` of a `str` does -- which is what §7b itself recorded
 * as the open item, and why that predicate already carried a `ByteArray -> true`
 * branch before anything produced one.
 *
 * `memoryview` stays refused, on a reason the copy does not dispose of: it
 * carries a format, a shape and strides, so there is no single flat
 * [ByteArray] that is its value, and a non-contiguous view cannot produce one
 * at all.
 *
 * ## How the bytes are actually read, and what it costs
 *
 * Through `bytes.hex()`, not through a buffer pointer. The Stable ABI subset
 * this library binds exposes `PyBytes_AsString`, whose `expect` returns a
 * `String?` decoded at the platform boundary from a NUL-terminated
 * `const char*` -- which truncates at the first `0x00` and mangles every byte
 * that is not valid UTF-8, i.e. it cannot represent arbitrary `bytes` at all.
 * There is no `PyBytes_AsStringAndSize` binding, and adding one would mean a
 * new non-primitive value crossing four platform boundaries (Panama, JNI, and
 * two cinterop targets), against `androidMain`'s rule that the JNI boundary
 * carries primitives only.
 *
 * `hex()` is exact for every byte, is pure ASCII (so nothing about the string
 * path can corrupt it), and costs **three FFI crossings regardless of length**
 * -- `PyObject_GetAttrString` + `PyObject_CallNoArgs` + `PyUnicode_AsUTF8` --
 * rather than the one crossing per byte a `PySequence_Tuple` walk would need.
 * What it does cost is four passes over the data: CPython's hex encode, the
 * UTF-8 encode/decode at the platform boundary, and [decodeHex] here, against
 * the single `memcpy` a direct buffer binding would do. That ratio is measured
 * against `str` of the same length (which *does* have a direct binding) in
 * `PyValueBytesConversionTest`.
 *
 * The conversion is therefore paid once per value: [cachedNativeValue] is a
 * real field, filled by [PyProxy]'s walk, not a recomputing accessor like
 * [PyInt]'s or [PyString]'s -- those cost one cheap FFI call, this does not.
 */
open class PyBytes(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<ByteArray> {
    companion object {
        /** The `PyType` for `bytes` (`builtins.bytes`). */
        val TYPE: PyType by lazy { val obj = from(ByteArray(0)); val t = obj.Type; obj.close(); t }

        /**
         * Builds a new Python `bytes` from [value], via `bytes.fromhex`.
         *
         * The mirror of the read path, and for the same reason: `PyBytes_FromString`
         * takes a Kotlin `String`, so it cannot express an embedded NUL or a
         * non-UTF-8 byte. `fromhex` is exact, and costs a fixed number of
         * crossings rather than one per byte.
         */
        fun from(value: ByteArray): PyBytes = PyBytes(buildPythonBytes(value), borrowed = false)
    }

    /**
     * Filled by [PyProxy.toKotlinOrNull]'s walk (which routes through
     * [python.multiplatform.ffi.types.collections.pyObjectToNative]), then kept.
     * `bytes` is immutable, so unlike a `bytearray` the snapshot can never go
     * stale.
     */
    override var cachedNativeValue: ByteArray? = null
    override var cachedPyObjectValue: PyObject? = null
}

/**
 * Wrapper around a Python `bytearray` object, projecting onto a Kotlin [ByteArray].
 *
 * Identical to [PyBytes] in every respect but mutability, which is why it is a
 * separate type rather than a shared one: `bytearray` is mutable, so its cached
 * projection is a **snapshot** in exactly the sense a converted `list` is, and
 * [PyProxy.invalidateNativeCache] is the stated way to re-read a `bytearray`
 * that has since been mutated.
 */
open class PyByteArray(pointer: NativePointer, borrowed: Boolean) : PyObject(pointer, borrowed), PyProxy<ByteArray> {
    companion object {
        /** The `PyType` for `bytearray` (`builtins.bytearray`). */
        val TYPE: PyType by lazy {
            // Derived from a scratch instance, like every other wrapper's TYPE. Reading
            // `PyTypeChecks.bytearrayType`'s own `Type` would answer `type`, not `bytearray`.
            val empty = PyBytes.from(ByteArray(0))
            val ptr = try {
                python.multiplatform.ffi.Python3.withPython { PyByteArray_FromObject(empty.pointer) }
                    ?: throw pyErrorOrGeneric("Could not build a scratch bytearray")
            } finally {
                empty.close()
            }
            val obj = PyByteArray(ptr, borrowed = false)
            try { obj.Type } finally { obj.close() }
        }
    }

    override var cachedNativeValue: ByteArray? = null
    override var cachedPyObjectValue: PyObject? = null
}

/** `bytes(b'...')` and `bytearray(b'...')` as a Kotlin [ByteArray]; see [PyBytes] for why this goes through `hex()`. */
fun ByteArray.asPyObject(): PyBytes = PyBytes.from(this)

/**
 * Reads the bytes of a `bytes`/`bytearray` object at [pointer] out into an
 * independent Kotlin [ByteArray], via `hex()`.
 *
 * Marked [HighOverheadNativeCall] not for its crossing count -- three, fixed --
 * but for the four passes over the payload it makes where a direct
 * `PyBytes_AsStringAndSize` binding would make one. See [PyBytes]'s class doc
 * for why that binding does not exist.
 *
 * Takes a raw [NativePointer] rather than a [PyObject] deliberately: no wrapper
 * is constructed anywhere on this path, so the conversion costs no refcount
 * round trip and registers no cleaner. Nothing here adopts [pointer]; the
 * caller keeps owning it.
 */
@HighOverheadNativeCall
internal fun readBufferAsByteArray(pointer: NativePointer): ByteArray {
    // `hex` is a new reference.
    val hexMethod = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(pointer, "hex") }
        ?: throw pyErrorOrGeneric("Could not resolve .hex() on this buffer object")
    val hexStrPtr = try {
        python.multiplatform.ffi.Python3.withPython { PyObject_CallNoArgs(hexMethod) }
            ?: throw pyErrorOrGeneric("Calling .hex() on this buffer object failed")
    } finally {
        python.multiplatform.ffi.Python3.withPython { Py_DecRef(hexMethod) }
    }
    val hex = try {
        // Pure ASCII by construction, so the boundary's UTF-8 decode is lossless and
        // NUL-termination cannot truncate it -- which is the whole reason for the detour.
        python.multiplatform.ffi.Python3.withPython { PyUnicode_AsUTF8(hexStrPtr) }
            ?: throw pyErrorOrGeneric("Could not read the result of .hex()")
    } finally {
        python.multiplatform.ffi.Python3.withPython { Py_DecRef(hexStrPtr) }
    }
    return decodeHex(hex)
}

/** Builds a new Python `bytes` (a new reference) holding exactly [value]; see [PyBytes.from]. */
private fun buildPythonBytes(value: ByteArray): NativePointer {
    // `bytesType` is a borrowed reference to an immortal builtin; `fromhex` is a new one.
    val fromHex = python.multiplatform.ffi.Python3.withPython { PyObject_GetAttrString(PyTypeChecks.bytesType, "fromhex") }
        ?: throw pyErrorOrGeneric("Could not resolve bytes.fromhex")
    try {
        val arg = python.multiplatform.ffi.Python3.withPython { PyUnicode_FromString(encodeHex(value)) }
            ?: throw pyErrorOrGeneric("Could not build the hex argument for bytes.fromhex")
        val args = python.multiplatform.ffi.Python3.withPython { PyTuple_New(1) }
        if (args == null) {
            // The tuple would have stolen this reference; nothing else holds it.
            python.multiplatform.ffi.Python3.withPython { Py_DecRef(arg) }
            throw pyErrorOrGeneric("Could not build the argument tuple for bytes.fromhex")
        }
        try {
            // PyTuple_SetItem *steals* `arg`, which is exactly right here: it is a fresh
            // reference this function owns and hands over, so there is no incref to pair with
            // it (contrast PyComplex.from, whose arguments are still owned by live wrappers).
            python.multiplatform.ffi.Python3.withPython { PyTuple_SetItem(args, 0, arg) }
            return python.multiplatform.ffi.Python3.withPython { PyObject_CallObject(fromHex, args) }
                ?: throw pyErrorOrGeneric("bytes.fromhex() failed")
        } finally {
            python.multiplatform.ffi.Python3.withPython { Py_DecRef(args) } // releases `arg` with it
        }
    } finally {
        python.multiplatform.ffi.Python3.withPython { Py_DecRef(fromHex) }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun encodeHex(value: ByteArray): String {
    val out = StringBuilder(value.size * 2)
    for (b in value) {
        val v = b.toInt() and 0xff
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0f])
    }
    return out.toString()
}

private fun decodeHex(hex: String): ByteArray {
    // `hex()` always emits two digits per byte; an odd length would mean the string was
    // truncated on the way here, which is precisely the failure mode this path exists to avoid.
    check(hex.length % 2 == 0) { "hex() returned an odd-length string (${hex.length}); the buffer did not survive the boundary" }
    val out = ByteArray(hex.length / 2)
    var i = 0
    while (i < out.size) {
        out[i] = (((hexDigit(hex[i * 2]) shl 4) or hexDigit(hex[i * 2 + 1])).toByte())
        i++
    }
    return out
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalStateException("hex() returned a non-hex character '$c'")
}
