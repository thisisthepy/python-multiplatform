package python.multiplatform.ffi

import python.multiplatform.ffi.exceptions.PyException
import python.native.ffi.NativePointer
import python.native.ffi.PyDict_GetItemString
import python.native.ffi.PyEval_GetBuiltins
import python.native.ffi.PyImport_AddModule
import python.native.ffi.PyObject_IsInstance
import python.native.ffi.PyObject_Type

/**
 * Fast, cross-platform substitute for CPython's `PyLong_Check`/`PyFloat_Check`/
 * `PyBool_Check`/`PyUnicode_Check`/`PyList_Check`/`PyTuple_Check`/`PyDict_Check`/
 * `PySet_Check`/`PyFrozenSet_Check`/`PyType_Check`/`PyModule_Check` macro
 * family.
 *
 * ### Why none of these are `expect`/`actual` bindings
 *
 * None of the macros above are real, dynamically-linkable ABI symbols.
 * `#define python.multiplatform.ffi.Python3.withPython { PyLong_Check(op) } PyType_FastSubclass(Py_TYPE(op),
 * Py_TPFLAGS_LONG_SUBCLASS)` and its siblings for `list`/`tuple`/`dict`/
 * `set`/`frozenset`/`str` are preprocessor macros; `PyType_Check` and
 * `PyModule_Check`'s underlying `PyObject_TypeCheck` are `static inline` C
 * functions instead, but those are *also* not exported symbols (`nm` on the
 * compiled `libpython3.13`/`Python.xcframework` binaries this project ships
 * confirms none of them appear in the dynamic symbol table, unlike e.g.
 * `PyList_Size` or `PyModule_GetName`, which do). There is therefore no
 * `dlsym`/cinterop-visible entry point for any of them on *any* of the four
 * platforms this library targets (iOS/androidNative via cinterop, Android via
 * JNI, desktop via Panama) -- adding an `expect` declaration for one would
 * compile on commonMain but leave every platform's `actual` with nothing to
 * bind against, breaking the whole build identically everywhere. So instead,
 * each check below is plain common Kotlin -- no `expect`/`actual` split at
 * all -- built only out of primitives this ABI subset *does* expose as real
 * functions, which by construction behaves identically on all four platforms.
 *
 * ### The chosen primitive: `PyObject_IsInstance`
 *
 * `python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(obj, type) }` is a genuine, exported `PyAPI_FUNC`
 * (`abstract.h`), already bound as `python.native.ffi.PyObject_IsInstance`.
 * It matches the macros' own subclass-inclusive semantics: `PyLong_Check`
 * is true for `bool` (since `bool` subclasses `int` and therefore carries
 * `Py_TPFLAGS_LONG_SUBCLASS`), and `isinstance(x, int)` agrees for the same
 * reason; the same correspondence holds for every other check here,
 * including `PyType_Check` (`isinstance(op, type)`, i.e. "is `op` itself a
 * type/metaclass instance") and `PyModule_Check`. It is one FFI crossing,
 * with no new reference returned to release afterwards.
 *
 * ### The cached type-object side
 *
 * `PyObject_IsInstance`'s second argument needs an actual type object to
 * compare against, and the `python.multiplatform.ffi.Python3.withPython { PyAPI_DATA(PyTypeObject) } PyLong_Type` (etc.)
 * globals this would naturally read are, like `Py_None` (see
 * [python.multiplatform.ffi.types.basic.PyNone]'s class doc), *data*
 * symbols, not functions -- pulling them in directly would mean adding
 * raw-symbol-address plumbing per platform (a `dlsym`-alike for Android
 * JNI, a `SymbolLookup` for Panama, alongside cinterop's own mechanism)
 * instead of one portable helper, and would still need `expect`/`actual`
 * despite the checks themselves not needing it. This resolves the same
 * type objects Python itself uses, indirectly, the same way [PyNone]
 * resolves the `None` singleton: via `python.multiplatform.ffi.Python3.withPython { PyEval_GetBuiltins() }` +
 * `python.multiplatform.ffi.Python3.withPython { PyDict_GetItemString() }` for the ones that are builtins (`int`, `bool`,
 * `float`, `str`, `list`, `tuple`, `dict`, `set`, `frozenset`, `type`), and
 * via `python.multiplatform.ffi.Python3.withPython { PyObject_Type() }` of the `builtins` module itself for `module`
 * (`types.ModuleType` is not a builtin name, but the `builtins` module
 * object -- obtained via the already-bound `python.multiplatform.ffi.Python3.withPython { PyImport_AddModule("builtins") }`
 * -- is always resolvable once the interpreter is up, and is itself an
 * instance of exactly the type this needs). All of it reuses functions
 * this ABI subset already exposes; each cache entry resolves once, lazily,
 * and is retained for the process's lifetime (CPython treats built-in
 * types as immortal, so this is harmless -- see the same reasoning in
 * [PyType.getInstance]'s handling of already-cached entries).
 *
 * ### Why this is genuinely cheaper than the string-dispatch workaround
 *
 * Each check here is exactly one FFI crossing (`PyObject_IsInstance`,
 * after the type cache has warmed up). The workaround it replaces
 * ([python.multiplatform.ffi.types.collections.pyObjectToNative]/
 * `PyContext.typedWrap`'s `PyType.name` dispatch, before this file existed)
 * cost *three* FFI crossings just to read a type's name well enough to
 * compare it as a string (`PyObject_Type` + `PyType_GetName` +
 * `PyUnicode_AsUTF8`), plus a UTF-8 string allocation/decode on every
 * single dispatch, before any comparison could even start.
 */
internal object PyTypeChecks {
    private val builtins: NativePointer by lazy {
        python.multiplatform.ffi.Python3.withPython { PyEval_GetBuiltins() } ?: throw PyException.fromCurrentError() ?: PyException("Failed to get the builtins dict")
    }

    private fun builtin(name: String): NativePointer =
        python.multiplatform.ffi.Python3.withPython { PyDict_GetItemString(builtins, name) }
            ?: throw PyException.fromCurrentError() ?: PyException("Builtin '$name' not found")

    val intType: NativePointer by lazy { builtin("int") }
    val boolType: NativePointer by lazy { builtin("bool") }
    val floatType: NativePointer by lazy { builtin("float") }
    val strType: NativePointer by lazy { builtin("str") }
    val bytesType: NativePointer by lazy { builtin("bytes") }
    val bytearrayType: NativePointer by lazy { builtin("bytearray") }
    val listType: NativePointer by lazy { builtin("list") }
    val tupleType: NativePointer by lazy { builtin("tuple") }
    val dictType: NativePointer by lazy { builtin("dict") }
    val setType: NativePointer by lazy { builtin("set") }
    val frozensetType: NativePointer by lazy { builtin("frozenset") }
    val typeType: NativePointer by lazy { builtin("type") }

    /** `types.ModuleType` -- not a builtin name, so derived from the `builtins` module's own [PyObject_Type] instead (see class doc). */
    val moduleType: NativePointer by lazy {
        val builtinsModule = python.multiplatform.ffi.Python3.withPython { PyImport_AddModule("builtins") } // borrowed reference; always resolvable once the interpreter is up
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to resolve the 'builtins' module")
        python.multiplatform.ffi.Python3.withPython { PyObject_Type(builtinsModule) } // new reference, intentionally never released -- immortal builtin type, see class doc
            ?: throw PyException.fromCurrentError() ?: PyException("Failed to resolve the 'module' type")
    }
}

/** `python.multiplatform.ffi.Python3.withPython { PyLong_Check(o) }`: is [o] a Python `int` (or subclass, e.g. `bool`)? See [PyTypeChecks]'s class doc for the approach. */
fun PyLong_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.intType) }

/** `python.multiplatform.ffi.Python3.withPython { PyBool_Check(o) }`: is [o] a Python `bool`? See [PyTypeChecks]'s class doc for the approach. */
fun PyBool_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.boolType) }

/** `python.multiplatform.ffi.Python3.withPython { PyFloat_Check(o) }`: is [o] a Python `float` (or subclass)? See [PyTypeChecks]'s class doc for the approach. */
fun PyFloat_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.floatType) }

/** `python.multiplatform.ffi.Python3.withPython { PyUnicode_Check(o) }`: is [o] a Python `str` (or subclass)? See [PyTypeChecks]'s class doc for the approach. */
fun PyUnicode_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.strType) }

/** `python.multiplatform.ffi.Python3.withPython { PyList_Check(o) }`: is [o] a Python `list` (or subclass)? See [PyTypeChecks]'s class doc for the approach. */
fun PyList_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.listType) }

/** `python.multiplatform.ffi.Python3.withPython { PyTuple_Check(o) }`: is [o] a Python `tuple` (or subclass)? See [PyTypeChecks]'s class doc for the approach. */
fun PyTuple_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.tupleType) }

/** `python.multiplatform.ffi.Python3.withPython { PyDict_Check(o) }`: is [o] a Python `dict` (or subclass)? See [PyTypeChecks]'s class doc for the approach. */
fun PyDict_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.dictType) }

/** `python.multiplatform.ffi.Python3.withPython { PySet_Check(o) }`: is [o] a Python `set` (or subclass)? See [PyTypeChecks]'s class doc for the approach. */
fun PySet_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.setType) }

/** `python.multiplatform.ffi.Python3.withPython { PyFrozenSet_Check(o) }`: is [o] a Python `frozenset` (or subclass)? See [PyTypeChecks]'s class doc for the approach. */
fun PyFrozenSet_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.frozensetType) }

/** `python.multiplatform.ffi.Python3.withPython { PyType_Check(o) }`: is [o] itself a type object (or a metaclass instance)? See [PyTypeChecks]'s class doc for the approach. */
fun PyType_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.typeType) }

/** `python.multiplatform.ffi.Python3.withPython { PyModule_Check(o) }`: is [o] a Python module object? See [PyTypeChecks]'s class doc for the approach. */
fun PyModule_Check(o: NativePointer): Int = python.multiplatform.ffi.Python3.withPython { PyObject_IsInstance(o, PyTypeChecks.moduleType) }
