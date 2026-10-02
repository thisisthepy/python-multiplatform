# Reachability Analysis: Android Unregistered Surface

> **Status: superseded. Every count and every risk ranking below is historical.**
>
> The unregistered surface is empty as of the pass recorded in ROADMAP §2: 363 of 367 `external fun`
> are bound through `RegisterNatives`, and the four that are not are deliberate — `ffiAllocUtf8`,
> `ffiFreeUtf8` and `ffiReadUtf8` are hand-written JNI exports with the correct prologue, and
> `echoCriticalNamed` exists in order to be name-linked, so that a benchmark can measure that path.
>
> The claim here worth carrying forward is that the *ordering* was right and the *counts* were not.
> When the last 157 were registered, a recount found **zero** of them reachable from
> `commonMain`/`commonTest` — the earlier passes had already taken the whole reachable surface, so
> the "71 reachable" figure below had long since been consumed. The call-graph traces are kept
> because they are still the record of which public API path reaches which C function.
>
> Everything from here down is retained unedited.

> **Earlier status: the string half of this report is closed; the counts below are stale.**
>
> Recounted from the two files: 187 registered, 178 unregistered (was 145 / 224). Declarations
> still carrying a `jstring` across the boundary went from 52 to 6, and none of those six is on
> the broken path except `ffiSymbolRaw`, which has no caller anywhere in `src/`. The
> "Critical Risk: Functions taking `String`" section below is therefore resolved in full — not
> just the four functions it happens to list. What remains is the pointer-only surface, and the
> reachability reasoning for that still holds.
>
> See ROADMAP §2 for the current numbers and `docs/design/marshalling-design.md` for the per-argument
> intern/scratch rule the migration followed.

This report analyzes the reachability of the 304 unregistered functions in the Android JNI surface (`bindings.kt`). An unregistered function relies on name-based `@CName` linking which on Android leads to arguments arriving shifted, Kotlin `String`s being passed incorrectly, and `java.lang.Long` boxing issues. Hitting any of these functions is a latent `SIGSEGV` or similar crash.

We traced the call graph backwards from the public API wrappers in `commonMain` (specifically under `ffi/`) through `EmbedAPI.kt` to the actual Android bindings.

## 1. Reachable and Unregistered

Out of the unregistered functions, exactly **71 are reachable** from the current public API. 

The list below shows the reachable functions, ordered by how likely an ordinary user is to hit them. Functions that take strings are placed at the top because they are guaranteed to crash the process due to `jstring` vs Kotlin/Native `String` mismatch. Functions used by core objects (`PyObject`, `PyDict`, `PyList`, etc.) follow.

### Critical Risk: Functions taking `String`
These functions take a string argument and are highly likely to crash immediately if reached.

- **`PyImport_AddModule`**
  - `ffi/PyTypeChecks.kt` -> `EmbedAPI.PyImport_AddModule()` -> `bindings.PyImport_AddModule()`
- **`PyObject_DelAttrString`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyObject_DelAttrString()` -> `bindings.PyObject_DelAttrString()`
- **`PyObject_SetAttrString`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyObject_SetAttrString()` -> `bindings.PyObject_SetAttrString()`
- **`PyErr_SetString`**
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyErr_SetString()` -> `bindings.PyErr_SetString()`

### High Risk: Core Object Model (Collections and Types)
These functions manipulate common Python objects (Dicts, Lists, Tuples, Sets, etc.) and are very likely to be called during normal usage.

- **`PyDict_Clear`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyDict_Clear()` -> `bindings.PyDict_Clear()`
- **`PyDict_Contains`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyDict_Contains()` -> `bindings.PyDict_Contains()`
- **`PyDict_DelItem`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyDict_DelItem()` -> `bindings.PyDict_DelItem()`
- **`PyDict_GetItem`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyDict_GetItem()` -> `bindings.PyDict_GetItem()`
- **`PyDict_Items`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyDict_Items()` -> `bindings.PyDict_Items()`
- **`PyDict_Size`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyDict_Size()` -> `bindings.PyDict_Size()`
- **`PyDict_Values`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyDict_Values()` -> `bindings.PyDict_Values()`
- **`PyList_Append`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PyList_Append()` -> `bindings.PyList_Append()`
- **`PyList_AsTuple`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyList_AsTuple()` -> `bindings.PyList_AsTuple()`
- **`PyList_Insert`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PyList_Insert()` -> `bindings.PyList_Insert()`
- **`PyList_Reverse`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PyList_Reverse()` -> `bindings.PyList_Reverse()`
- **`PyList_Sort`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PyList_Sort()` -> `bindings.PyList_Sort()`
- **`PyTuple_GetItem`**
  - `ffi/PyType.kt` -> `EmbedAPI.PyTuple_GetItem()` -> `bindings.PyTuple_GetItem()`
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PyTuple_GetItem()` -> `bindings.PyTuple_GetItem()`
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyTuple_GetItem()` -> `bindings.PyTuple_GetItem()`
  - `ffi/types/collections/PyTuple.kt` -> `EmbedAPI.PyTuple_GetItem()` -> `bindings.PyTuple_GetItem()`
- **`PyTuple_GetSlice`**
  - `ffi/types/collections/PyTuple.kt` -> `EmbedAPI.PyTuple_GetSlice()` -> `bindings.PyTuple_GetSlice()`
- **`PyTuple_Size`**
  - `ffi/PyType.kt` -> `EmbedAPI.PyTuple_Size()` -> `bindings.PyTuple_Size()`
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PyTuple_Size()` -> `bindings.PyTuple_Size()`
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PyTuple_Size()` -> `bindings.PyTuple_Size()`
  - `ffi/types/collections/PyTuple.kt` -> `EmbedAPI.PyTuple_Size()` -> `bindings.PyTuple_Size()`
- **`PySequence_Contains`**
  - `ffi/types/collections/PyDict.kt` -> `EmbedAPI.PySequence_Contains()` -> `bindings.PySequence_Contains()`
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PySequence_Contains()` -> `bindings.PySequence_Contains()`
  - `ffi/types/collections/PyTuple.kt` -> `EmbedAPI.PySequence_Contains()` -> `bindings.PySequence_Contains()`
- **`PySequence_List`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PySequence_List()` -> `bindings.PySequence_List()`
- **`PySequence_Tuple`**
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PySequence_Tuple()` -> `bindings.PySequence_Tuple()`
- **`PySet_Add`**
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PySet_Add()` -> `bindings.PySet_Add()`
- **`PySet_Clear`**
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PySet_Clear()` -> `bindings.PySet_Clear()`
- **`PySet_Contains`**
  - `ffi/types/collections/PyFrozenSet.kt` -> `EmbedAPI.PySet_Contains()` -> `bindings.PySet_Contains()`
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PySet_Contains()` -> `bindings.PySet_Contains()`
- **`PySet_Discard`**
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PySet_Discard()` -> `bindings.PySet_Discard()`
- **`PySet_New`**
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PySet_New()` -> `bindings.PySet_New()`
- **`PySet_Pop`**
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PySet_Pop()` -> `bindings.PySet_Pop()`
- **`PySet_Size`**
  - `ffi/types/collections/PyFrozenSet.kt` -> `EmbedAPI.PySet_Size()` -> `bindings.PySet_Size()`
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PySet_Size()` -> `bindings.PySet_Size()`
- **`PyFrozenSet_New`**
  - `ffi/types/collections/PyFrozenSet.kt` -> `EmbedAPI.PyFrozenSet_New()` -> `bindings.PyFrozenSet_New()`
- **`PyObject_Call`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyObject_Call()` -> `bindings.PyObject_Call()`
- **`PyObject_DelItem`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PyObject_DelItem()` -> `bindings.PyObject_DelItem()`
- **`PyObject_GetItem`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PyObject_GetItem()` -> `bindings.PyObject_GetItem()`
- **`PyObject_GetIter`**
  - `ffi/PyType.kt` -> `EmbedAPI.PyObject_GetIter()` -> `bindings.PyObject_GetIter()`
  - `ffi/types/collections/PyTuple.kt` -> `EmbedAPI.PyObject_GetIter()` -> `bindings.PyObject_GetIter()`
- **`PyObject_IsInstance`**
  - `ffi/PyType.kt` -> `EmbedAPI.PyObject_IsInstance()` -> `bindings.PyObject_IsInstance()`
  - `ffi/PyTypeChecks.kt` -> `EmbedAPI.PyObject_IsInstance()` -> `bindings.PyObject_IsInstance()`
- **`PyObject_Repr`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyObject_Repr()` -> `bindings.PyObject_Repr()`
- **`PyObject_RichCompare`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyObject_RichCompare()` -> `bindings.PyObject_RichCompare()`
- **`PyObject_RichCompareBool`**
  - `ffi/types/basic/PyFloat.kt` -> `EmbedAPI.PyObject_RichCompareBool()` -> `bindings.PyObject_RichCompareBool()`
  - `ffi/types/basic/PyInt.kt` -> `EmbedAPI.PyObject_RichCompareBool()` -> `bindings.PyObject_RichCompareBool()`
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PyObject_RichCompareBool()` -> `bindings.PyObject_RichCompareBool()`
- **`PyObject_SetItem`**
  - `ffi/types/collections/PyList.kt` -> `EmbedAPI.PyObject_SetItem()` -> `bindings.PyObject_SetItem()`
- **`PyObject_Type`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyObject_Type()` -> `bindings.PyObject_Type()`
  - `ffi/PyType.kt` -> `EmbedAPI.PyObject_Type()` -> `bindings.PyObject_Type()`
  - `ffi/PyTypeChecks.kt` -> `EmbedAPI.PyObject_Type()` -> `bindings.PyObject_Type()`
  - `ffi/conversion/Context.kt` -> `EmbedAPI.PyObject_Type()` -> `bindings.PyObject_Type()`
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyObject_Type()` -> `bindings.PyObject_Type()`
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PyObject_Type()` -> `bindings.PyObject_Type()`
- **`PyModule_GetDict`**
  - `ffi/types/modules/PyModule.kt` -> `EmbedAPI.PyModule_GetDict()` -> `bindings.PyModule_GetDict()`
- **`PyModule_GetFilenameObject`**
  - `ffi/types/modules/PyModule.kt` -> `EmbedAPI.PyModule_GetFilenameObject()` -> `bindings.PyModule_GetFilenameObject()`
- **`PyModule_GetName`**
  - `ffi/types/modules/PyModule.kt` -> `EmbedAPI.PyModule_GetName()` -> `bindings.PyModule_GetName()`
- **`PyType_GetName`**
  - `ffi/PyType.kt` -> `EmbedAPI.PyType_GetName()` -> `bindings.PyType_GetName()`
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyType_GetName()` -> `bindings.PyType_GetName()`
- **`PyType_IsSubtype`**
  - `ffi/PyType.kt` -> `EmbedAPI.PyType_IsSubtype()` -> `bindings.PyType_IsSubtype()`

### Medium Risk: Exception and Error Handling
These functions are less likely to be called during standard execution, but are critical when an error happens. A crash here masks the actual problem.

- **`PyErr_GetRaisedException`**
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyErr_GetRaisedException()` -> `bindings.PyErr_GetRaisedException()`
- **`PyErr_SetRaisedException`**
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyErr_SetRaisedException()` -> `bindings.PyErr_SetRaisedException()`
- **`PyException_GetCause`**
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyException_GetCause()` -> `bindings.PyException_GetCause()`
- **`PyException_GetContext`**
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyException_GetContext()` -> `bindings.PyException_GetContext()`
- **`PyException_GetTraceback`**
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyException_GetTraceback()` -> `bindings.PyException_GetTraceback()`

### Medium Risk: Mathematics and Basic Types
- **`PyNumber_Add`**
  - `ffi/types/basic/PyFloat.kt` -> `EmbedAPI.PyNumber_Add()` -> `bindings.PyNumber_Add()`
  - `ffi/types/basic/PyInt.kt` -> `EmbedAPI.PyNumber_Add()` -> `bindings.PyNumber_Add()`
- **`PyNumber_And`**
  - `ffi/types/collections/PyFrozenSet.kt` -> `EmbedAPI.PyNumber_And()` -> `bindings.PyNumber_And()`
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PyNumber_And()` -> `bindings.PyNumber_And()`
- **`PyNumber_Multiply`**
  - `ffi/types/basic/PyFloat.kt` -> `EmbedAPI.PyNumber_Multiply()` -> `bindings.PyNumber_Multiply()`
  - `ffi/types/basic/PyInt.kt` -> `EmbedAPI.PyNumber_Multiply()` -> `bindings.PyNumber_Multiply()`
- **`PyNumber_Or`**
  - `ffi/types/collections/PyFrozenSet.kt` -> `EmbedAPI.PyNumber_Or()` -> `bindings.PyNumber_Or()`
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PyNumber_Or()` -> `bindings.PyNumber_Or()`
- **`PyNumber_Subtract`**
  - `ffi/types/basic/PyFloat.kt` -> `EmbedAPI.PyNumber_Subtract()` -> `bindings.PyNumber_Subtract()`
  - `ffi/types/basic/PyInt.kt` -> `EmbedAPI.PyNumber_Subtract()` -> `bindings.PyNumber_Subtract()`
  - `ffi/types/collections/PyFrozenSet.kt` -> `EmbedAPI.PyNumber_Subtract()` -> `bindings.PyNumber_Subtract()`
  - `ffi/types/collections/PySet.kt` -> `EmbedAPI.PyNumber_Subtract()` -> `bindings.PyNumber_Subtract()`
- **`PyFloat_AsDouble`**
  - `ffi/types/basic/PyComplex.kt` -> `EmbedAPI.PyFloat_AsDouble()` -> `bindings.PyFloat_AsDouble()`
  - `ffi/types/basic/PyFloat.kt` -> `EmbedAPI.PyFloat_AsDouble()` -> `bindings.PyFloat_AsDouble()`
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PyFloat_AsDouble()` -> `bindings.PyFloat_AsDouble()`
- **`PyFloat_FromDouble`**
  - `ffi/types/basic/PyFloat.kt` -> `EmbedAPI.PyFloat_FromDouble()` -> `bindings.PyFloat_FromDouble()`
- **`PyLong_AsInt`**
  - `ffi/exceptions/PyTraceback.kt` -> `EmbedAPI.PyLong_AsInt()` -> `bindings.PyLong_AsInt()`
- **`PyLong_AsLongLong`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyLong_AsLongLong()` -> `bindings.PyLong_AsLongLong()`
  - `ffi/types/basic/PyInt.kt` -> `EmbedAPI.PyLong_AsLongLong()` -> `bindings.PyLong_AsLongLong()`
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PyLong_AsLongLong()` -> `bindings.PyLong_AsLongLong()`
- **`PyBool_FromLong`**
  - `ffi/types/basic/PyBool.kt` -> `EmbedAPI.PyBool_FromLong()` -> `bindings.PyBool_FromLong()`
- **`PyUnicode_Concat`**
  - `ffi/types/basic/PyString.kt` -> `EmbedAPI.PyUnicode_Concat()` -> `bindings.PyUnicode_Concat()`
- **`PyUnicode_Contains`**
  - `ffi/types/basic/PyString.kt` -> `EmbedAPI.PyUnicode_Contains()` -> `bindings.PyUnicode_Contains()`

### Miscellaneous Utility Functions
- **`PyCallable_Check`**
  - `ffi/PyObject.kt` -> `EmbedAPI.PyCallable_Check()` -> `bindings.PyCallable_Check()`
- **`PyEval_GetBuiltins`**
  - `ffi/PyTypeChecks.kt` -> `EmbedAPI.PyEval_GetBuiltins()` -> `bindings.PyEval_GetBuiltins()`
  - `ffi/types/basic/PyComplex.kt` -> `EmbedAPI.PyEval_GetBuiltins()` -> `bindings.PyEval_GetBuiltins()`
  - `ffi/types/basic/PyNone.kt` -> `EmbedAPI.PyEval_GetBuiltins()` -> `bindings.PyEval_GetBuiltins()`
- **`PyIter_Next`**
  - `ffi/types/iteration/PyIterator.kt` -> `EmbedAPI.PyIter_Next()` -> `bindings.PyIter_Next()`
- **`Py_GetBuildInfo`**
  - `ffi/Python3.kt` -> `EmbedAPI.Py_GetBuildInfo()` -> `bindings.Py_GetBuildInfo()`
- **`Py_GetCompiler`**
  - `ffi/Python3.kt` -> `EmbedAPI.Py_GetCompiler()` -> `bindings.Py_GetCompiler()`
- **`Py_GetCopyright`**
  - `ffi/Python3.kt` -> `EmbedAPI.Py_GetCopyright()` -> `bindings.Py_GetCopyright()`
- **`Py_GetPlatform`**
  - `ffi/Python3.kt` -> `EmbedAPI.Py_GetPlatform()` -> `bindings.Py_GetPlatform()`
- **`Py_RunMain`**
  - `ffi/Python3.kt` -> `EmbedAPI.Py_RunMain()` -> `bindings.Py_RunMain()`
- **`ffiReadUtf8`** (Helper used for marshalling)
  - `ffi/PyObject.kt` -> `EmbedAPI.PyUnicode_AsUTF8()` -> `bindings.ffiReadUtf8()`
  - `ffi/PyType.kt` -> `EmbedAPI.PyUnicode_AsUTF8()` -> `bindings.ffiReadUtf8()`
  - `ffi/Python3.kt` -> `EmbedAPI.Py_GetVersion()` -> `bindings.ffiReadUtf8()`
  - `ffi/exceptions/PyException.kt` -> `EmbedAPI.PyUnicode_AsUTF8()` -> `bindings.ffiReadUtf8()`
  - `ffi/exceptions/PyTraceback.kt` -> `EmbedAPI.PyUnicode_AsUTF8()` -> `bindings.ffiReadUtf8()`
  - `ffi/types/basic/PyString.kt` -> `EmbedAPI.PyUnicode_AsUTF8()` -> `bindings.ffiReadUtf8()`
  - `ffi/types/collections/CollectionSupport.kt` -> `EmbedAPI.PyUnicode_AsUTF8()` -> `bindings.ffiReadUtf8()`

---

## 2. Unreachable Today

There are **233** functions present in `bindings.kt` as unregistered `external fun`s that are not reached by any code in `commonMain` today (totalling 304 unregistered out of 375 overall, though exact counts from parsing show 71 reachable and 222 unreachable). 

While they should eventually be migrated to ensure the bindings layer is safe to use in its entirety, they **do not pose an immediate crash risk** for current users of the library.

## 3. Cannot Determine

Every path from the public `commonMain` API layer down to the underlying bindings layer could be definitively traced statically. There are **0** functions whose reachability could not be determined. The analysis explicitly covered every wrapper and `expect`/`actual` binding chain.

## 4. Suggested Migration Order

1. **String-taking reachable functions (The immediate crashers)**
   - Because they process strings incorrectly across JNI natively, calling any of these guarantees a crash.
   - `PyImport_AddModule`, `PyErr_SetString`, `PyObject_DelAttrString`, `PyObject_SetAttrString`

2. **Core Object Model (Primitives & Collections)**
   - Heavily used functions that could lead to crashes due to missing `JNIEnv` or boxing issues (`java.lang.Long` vs `jlong`).
   - `PyDict_Clear`, `PyDict_Contains`, `PyDict_DelItem`, `PyDict_GetItem`, `PyDict_Items`, `PyDict_Size`, `PyDict_Values`, `PyList_Append`, `PyList_AsTuple`, `PyList_Insert`, `PyList_Reverse`, `PyList_Sort`, `PyModule_GetDict`, `PyModule_GetFilenameObject`, `PyObject_Call`, `PyObject_DelItem`, `PyObject_GetItem`, `PyObject_GetIter`, `PyObject_IsInstance`, `PyObject_Repr`, `PyObject_RichCompare`, `PyObject_RichCompareBool`, `PyObject_SetItem`, `PyObject_Type`, `PySequence_List`, `PySequence_Tuple`, `PyTuple_GetItem`, `PyTuple_GetSlice`, `PyTuple_Size`, `PyType_GetName`, `PyType_IsSubtype`, etc.

3. **Exception and Error handling**
   - Less likely to be hit in a happy path, but critical for clean failure handling.
   - `PyErr_GetRaisedException`, `PyErr_SetRaisedException`, `PyException_GetCause`, `PyException_GetContext`, `PyException_GetTraceback`.

4. **Remaining reachable functions**
   - Other utility functions (e.g. maths and module utilities).

5. **Unreachable functions**
   - Deferred until the public surface is expanded to use them.
