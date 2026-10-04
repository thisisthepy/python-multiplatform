package python.native.ffi

import java.lang.invoke.MethodHandle
import java.lang.Long as LongLong
import java.lang.Double as Double


object bindings {


    //**************************************************
    // Section 1
    val Py_InitializeHandle: MethodHandle
    inline fun Py_Initialize() = Py_InitializeHandle.invokeExact() as Unit
    val Py_InitializeExHandle: MethodHandle
    inline fun Py_InitializeEx(initsigs: Int) = Py_InitializeExHandle.invokeExact(initsigs) as Unit
    /**
     * `void Py_SetPythonHome(const wchar_t *home)` -- desktop only, not in `EmbedAPI.kt`.
     *
     * How a packaged desktop app, whose launcher cannot set `PYTHONHOME`, names its prefix before
     * `Py_Initialize()` (SPEC L-9, `applyPackagedPythonHome`). Deprecated in 3.11 in favour of
     * `PyConfig.home`, but `PyConfig` is a struct whose layout the Stable ABI does not promise (see
     * `Python3.initialize`), and this function is part of the Stable ABI, so its symbol stays
     * exported. Takes a `wchar_t *`, not UTF-8: see `encodeWideString`.
     */
    val Py_SetPythonHomeHandle: MethodHandle
    inline fun Py_SetPythonHome(home: Long) = Py_SetPythonHomeHandle.invokeExact(home) as Unit
    val Py_IsInitializedHandle: MethodHandle
    inline fun Py_IsInitialized(): Int = Py_IsInitializedHandle.invokeExact() as Int
    val Py_IsFinalizingHandle: MethodHandle
    inline fun Py_IsFinalizing(): Int = Py_IsFinalizingHandle.invokeExact() as Int
    val Py_FinalizeExHandle: MethodHandle
    inline fun Py_FinalizeEx(): Int = Py_FinalizeExHandle.invokeExact() as Int
    val Py_FinalizeHandle: MethodHandle
    inline fun Py_Finalize() = Py_FinalizeHandle.invokeExact() as Unit
    val Py_RunMainHandle: MethodHandle
    inline fun Py_RunMain(): Int = Py_RunMainHandle.invokeExact() as Int
    val Py_GetVersionHandle: MethodHandle
    inline fun Py_GetVersion(): String? = Panama.readUtf8String(Py_GetVersionHandle.invokeExact() as Long)
    val Py_GetPlatformHandle: MethodHandle
    inline fun Py_GetPlatform(): String? = Panama.readUtf8String(Py_GetPlatformHandle.invokeExact() as Long)
    val Py_GetCopyrightHandle: MethodHandle
    inline fun Py_GetCopyright(): String? = Panama.readUtf8String(Py_GetCopyrightHandle.invokeExact() as Long)
    val Py_GetCompilerHandle: MethodHandle
    inline fun Py_GetCompiler(): String? = Panama.readUtf8String(Py_GetCompilerHandle.invokeExact() as Long)
    val Py_GetBuildInfoHandle: MethodHandle
    inline fun Py_GetBuildInfo(): String? = Panama.readUtf8String(Py_GetBuildInfoHandle.invokeExact() as Long)
    val PyEval_InitThreadsHandle: MethodHandle
    inline fun PyEval_InitThreads() = PyEval_InitThreadsHandle.invokeExact() as Unit
    val PyThreadState_GetDictHandle: MethodHandle
    inline fun PyThreadState_GetDict(): Long = PyThreadState_GetDictHandle.invokeExact() as Long
    val PyGILState_EnsureHandle: MethodHandle
    inline fun PyGILState_Ensure(): Int = PyGILState_EnsureHandle.invokeExact() as Int
    val PyGILState_ReleaseHandle: MethodHandle
    inline fun PyGILState_Release(state: Int) = PyGILState_ReleaseHandle.invokeExact(state) as Unit
    val PyGILState_GetThisThreadStateHandle: MethodHandle
    inline fun PyGILState_GetThisThreadState(): Long = PyGILState_GetThisThreadStateHandle.invokeExact() as Long
    val PyEval_SaveThreadHandle: MethodHandle
    inline fun PyEval_SaveThread(): Long = PyEval_SaveThreadHandle.invokeExact() as Long
    val PyEval_RestoreThreadHandle: MethodHandle
    inline fun PyEval_RestoreThread(tstate: Long) = PyEval_RestoreThreadHandle.invokeExact(tstate) as Unit
    val Py_MakePendingCallsHandle: MethodHandle
    inline fun Py_MakePendingCalls(): Int = Py_MakePendingCallsHandle.invokeExact() as Int
    val PyGC_CollectHandle: MethodHandle
    inline fun PyGC_Collect(): Long = PyGC_CollectHandle.invokeExact() as Long


    // Section 2
    val PyRun_SimpleStringHandle: MethodHandle
inline fun PyRun_SimpleString(command: String): Int {
    val _command = encodeScratchUtf8(command)
    return PyRun_SimpleStringHandle.invokeExact(_command) as Int
}
    val PyRun_StringHandle: MethodHandle
inline fun PyRun_String(str: String, start: Int, globals: Long, locals: Long): Long {
    val _str = encodeScratchUtf8(str)
    return PyRun_StringHandle.invokeExact(_str, start, globals, locals) as Long
}
    val Py_CompileStringHandle: MethodHandle
inline fun Py_CompileString(str: String, filename: String, start: Int): Long {
    val _str = encodeScratchUtf8(str)
    val _filename = internedUtf8(filename)
    return Py_CompileStringHandle.invokeExact(_str, _filename, start) as Long
}
    val PyEval_EvalCodeHandle: MethodHandle
    inline fun PyEval_EvalCode(co: Long, globals: Long, locals: Long): Long = PyEval_EvalCodeHandle.invokeExact(co, globals, locals) as Long


    // Section 3
    val PyErr_ClearHandle: MethodHandle
    inline fun PyErr_Clear() = PyErr_ClearHandle.invokeExact() as Unit
    val PyErr_PrintExHandle: MethodHandle
    inline fun PyErr_PrintEx(set_sys_last_vars: Int) = PyErr_PrintExHandle.invokeExact(set_sys_last_vars) as Unit
    val PyErr_PrintHandle: MethodHandle
    inline fun PyErr_Print() = PyErr_PrintHandle.invokeExact() as Unit
    val PyErr_WriteUnraisableHandle: MethodHandle
    inline fun PyErr_WriteUnraisable(obj: Long) = PyErr_WriteUnraisableHandle.invokeExact(obj) as Unit
    val PyErr_DisplayExceptionHandle: MethodHandle
    inline fun PyErr_DisplayException(exc: Long) = PyErr_DisplayExceptionHandle.invokeExact(exc) as Unit
    val PyErr_SetStringHandle: MethodHandle
inline fun PyErr_SetString(type: Long, message: String) {
    val _message = encodeScratchUtf8(message)
    return PyErr_SetStringHandle.invokeExact(type, _message) as Unit
}
    val PyErr_SetObjectHandle: MethodHandle
    inline fun PyErr_SetObject(type: Long, value: Long) = PyErr_SetObjectHandle.invokeExact(type, value) as Unit
    val PyErr_SetNoneHandle: MethodHandle
    inline fun PyErr_SetNone(type: Long) = PyErr_SetNoneHandle.invokeExact(type) as Unit
    val PyErr_BadArgumentHandle: MethodHandle
    inline fun PyErr_BadArgument(): Int = PyErr_BadArgumentHandle.invokeExact() as Int
    val PyErr_NoMemoryHandle: MethodHandle
    inline fun PyErr_NoMemory(): Long = PyErr_NoMemoryHandle.invokeExact() as Long
    val PyErr_SetFromErrnoHandle: MethodHandle
    inline fun PyErr_SetFromErrno(type: Long): Long = PyErr_SetFromErrnoHandle.invokeExact(type) as Long
    val PyErr_SetFromErrnoWithFilenameObjectHandle: MethodHandle
    inline fun PyErr_SetFromErrnoWithFilenameObject(type: Long, filenameObject: Long): Long = PyErr_SetFromErrnoWithFilenameObjectHandle.invokeExact(type, filenameObject) as Long
    val PyErr_SetFromErrnoWithFilenameObjectsHandle: MethodHandle
    inline fun PyErr_SetFromErrnoWithFilenameObjects(type: Long, filenameObject: Long, filenameObject2: Long): Long = PyErr_SetFromErrnoWithFilenameObjectsHandle.invokeExact(type, filenameObject, filenameObject2) as Long
    val PyErr_SetFromErrnoWithFilenameHandle: MethodHandle
inline fun PyErr_SetFromErrnoWithFilename(type: Long, filename: String): Long {
    val _filename = encodeScratchUtf8(filename)
    return PyErr_SetFromErrnoWithFilenameHandle.invokeExact(type, _filename) as Long
}
    val PyErr_SetImportErrorHandle: MethodHandle
    inline fun PyErr_SetImportError(msg: Long, name: Long, path: Long): Long = PyErr_SetImportErrorHandle.invokeExact(msg, name, path) as Long
    val PyErr_SetImportErrorSubclassHandle: MethodHandle
    inline fun PyErr_SetImportErrorSubclass(exception: Long, msg: Long, name: Long, path: Long): Long = PyErr_SetImportErrorSubclassHandle.invokeExact(exception, msg, name, path) as Long
    val PyErr_SyntaxLocationExHandle: MethodHandle
inline fun PyErr_SyntaxLocationEx(filename: String, lineno: Int, col_offset: Int) {
    val _filename = encodeScratchUtf8(filename)
    return PyErr_SyntaxLocationExHandle.invokeExact(_filename, lineno, col_offset) as Unit
}
    val PyErr_SyntaxLocationHandle: MethodHandle
inline fun PyErr_SyntaxLocation(filename: String, lineno: Int) {
    val _filename = encodeScratchUtf8(filename)
    return PyErr_SyntaxLocationHandle.invokeExact(_filename, lineno) as Unit
}
    val PyErr_BadInternalCallHandle: MethodHandle
    inline fun PyErr_BadInternalCall() = PyErr_BadInternalCallHandle.invokeExact() as Unit
    val PyErr_WarnExplicitHandle: MethodHandle
inline fun PyErr_WarnExplicit(category: Long, message: String, filename: String, lineno: Int, module: String, registry: Long): Int {
    val _message = encodeScratchUtf8(message)
    val _filename = internedUtf8(filename)
    val _module = internedUtf8(module)
    return PyErr_WarnExplicitHandle.invokeExact(category, _message, _filename, lineno, _module, registry) as Int
}
    val PyErr_OccurredHandle: MethodHandle
    inline fun PyErr_Occurred(): Long = PyErr_OccurredHandle.invokeExact() as Long
    val PyErr_ExceptionMatchesHandle: MethodHandle
    inline fun PyErr_ExceptionMatches(exc: Long): Int = PyErr_ExceptionMatchesHandle.invokeExact(exc) as Int
    val PyErr_GivenExceptionMatchesHandle: MethodHandle
    inline fun PyErr_GivenExceptionMatches(given: Long, exc: Long): Int = PyErr_GivenExceptionMatchesHandle.invokeExact(given, exc) as Int
    val PyErr_GetRaisedExceptionHandle: MethodHandle
    inline fun PyErr_GetRaisedException(): Long = PyErr_GetRaisedExceptionHandle.invokeExact() as Long
    val PyErr_SetRaisedExceptionHandle: MethodHandle
    inline fun PyErr_SetRaisedException(exc: Long) = PyErr_SetRaisedExceptionHandle.invokeExact(exc) as Unit
    val PyErr_RestoreHandle: MethodHandle
    inline fun PyErr_Restore(type: Long, value: Long, traceback: Long) = PyErr_RestoreHandle.invokeExact(type, value, traceback) as Unit
    val PyErr_GetHandledExceptionHandle: MethodHandle
    inline fun PyErr_GetHandledException(): Long = PyErr_GetHandledExceptionHandle.invokeExact() as Long
    val PyErr_SetHandledExceptionHandle: MethodHandle
    inline fun PyErr_SetHandledException(exc: Long) = PyErr_SetHandledExceptionHandle.invokeExact(exc) as Unit
    val PyErr_SetExcInfoHandle: MethodHandle
    inline fun PyErr_SetExcInfo(type: Long, value: Long, traceback: Long) = PyErr_SetExcInfoHandle.invokeExact(type, value, traceback) as Unit
    val PyErr_CheckSignalsHandle: MethodHandle
    inline fun PyErr_CheckSignals(): Int = PyErr_CheckSignalsHandle.invokeExact() as Int
    val PyErr_SetInterruptHandle: MethodHandle
    inline fun PyErr_SetInterrupt() = PyErr_SetInterruptHandle.invokeExact() as Unit
    val PyErr_SetInterruptExHandle: MethodHandle
    inline fun PyErr_SetInterruptEx(signum: Int): Int = PyErr_SetInterruptExHandle.invokeExact(signum) as Int
    val PyErr_NewExceptionHandle: MethodHandle
inline fun PyErr_NewException(name: String, base: Long, dict: Long): Long {
    val _name = internedUtf8(name)
    return PyErr_NewExceptionHandle.invokeExact(_name, base, dict) as Long
}
    val PyErr_NewExceptionWithDocHandle: MethodHandle
inline fun PyErr_NewExceptionWithDoc(name: String, doc: String, base: Long, dict: Long): Long {
    val _name = internedUtf8(name)
    val _doc = encodeScratchUtf8(doc)
    return PyErr_NewExceptionWithDocHandle.invokeExact(_name, _doc, base, dict) as Long
}
    val PyException_GetTracebackHandle: MethodHandle
    inline fun PyException_GetTraceback(ex: Long): Long = PyException_GetTracebackHandle.invokeExact(ex) as Long
    val PyException_SetTracebackHandle: MethodHandle
    inline fun PyException_SetTraceback(ex: Long, tb: Long): Int = PyException_SetTracebackHandle.invokeExact(ex, tb) as Int
    val PyException_GetContextHandle: MethodHandle
    inline fun PyException_GetContext(ex: Long): Long = PyException_GetContextHandle.invokeExact(ex) as Long
    val PyException_SetContextHandle: MethodHandle
    inline fun PyException_SetContext(ex: Long, ctx: Long) = PyException_SetContextHandle.invokeExact(ex, ctx) as Unit
    val PyException_GetCauseHandle: MethodHandle
    inline fun PyException_GetCause(ex: Long): Long = PyException_GetCauseHandle.invokeExact(ex) as Long
    val PyException_SetCauseHandle: MethodHandle
    inline fun PyException_SetCause(ex: Long, cause: Long) = PyException_SetCauseHandle.invokeExact(ex, cause) as Unit
    val PyException_GetArgsHandle: MethodHandle
    inline fun PyException_GetArgs(ex: Long): Long = PyException_GetArgsHandle.invokeExact(ex) as Long
    val PyException_SetArgsHandle: MethodHandle
    inline fun PyException_SetArgs(ex: Long, args: Long) = PyException_SetArgsHandle.invokeExact(ex, args) as Unit
    val PyUnicodeEncodeError_GetEncodingHandle: MethodHandle
    inline fun PyUnicodeEncodeError_GetEncoding(exc: Long): Long = PyUnicodeEncodeError_GetEncodingHandle.invokeExact(exc) as Long
    val PyUnicodeTranslateError_GetObjectHandle: MethodHandle
    inline fun PyUnicodeTranslateError_GetObject(exc: Long): Long = PyUnicodeTranslateError_GetObjectHandle.invokeExact(exc) as Long
    val PyUnicodeTranslateError_GetReasonHandle: MethodHandle
    inline fun PyUnicodeTranslateError_GetReason(exc: Long): Long = PyUnicodeTranslateError_GetReasonHandle.invokeExact(exc) as Long
    val PyUnicodeTranslateError_SetReasonHandle: MethodHandle
inline fun PyUnicodeTranslateError_SetReason(exc: Long, reason: String): Int {
    val _reason = encodeScratchUtf8(reason)
    return PyUnicodeTranslateError_SetReasonHandle.invokeExact(exc, _reason) as Int
}
    val Py_EnterRecursiveCallHandle: MethodHandle
inline fun Py_EnterRecursiveCall(where: String): Int {
    val _where = encodeScratchUtf8(where)
    return Py_EnterRecursiveCallHandle.invokeExact(_where) as Int
}
    val Py_LeaveRecursiveCallHandle: MethodHandle
    inline fun Py_LeaveRecursiveCall() = Py_LeaveRecursiveCallHandle.invokeExact() as Unit
    val Py_ReprEnterHandle: MethodHandle
    inline fun Py_ReprEnter(o: Long): Int = Py_ReprEnterHandle.invokeExact(o) as Int
    val Py_ReprLeaveHandle: MethodHandle
    inline fun Py_ReprLeave(o: Long) = Py_ReprLeaveHandle.invokeExact(o) as Unit


    // Section 4
    val Py_NewRefHandle: MethodHandle
    inline fun Py_NewRef(o: Long): Long = Py_NewRefHandle.invokeExact(o) as Long
    val Py_XNewRefHandle: MethodHandle
    inline fun Py_XNewRef(o: Long): Long = Py_XNewRefHandle.invokeExact(o) as Long
    val Py_IncRefHandle: MethodHandle
    inline fun Py_IncRef(o: Long) = Py_IncRefHandle.invokeExact(o) as Unit
    val Py_DecRefHandle: MethodHandle
    inline fun Py_DecRef(o: Long) = Py_DecRefHandle.invokeExact(o) as Unit


    // Section 5
    val PyOS_FSPathHandle: MethodHandle
    inline fun PyOS_FSPath(path: Long): Long = PyOS_FSPathHandle.invokeExact(path) as Long


    // Section 6
    val PySys_GetObjectHandle: MethodHandle
inline fun PySys_GetObject(name: String): Long {
    val _name = internedUtf8(name)
    return PySys_GetObjectHandle.invokeExact(_name) as Long
}
    val PySys_SetObjectHandle: MethodHandle
inline fun PySys_SetObject(name: String, v: Long): Int {
    val _name = internedUtf8(name)
    return PySys_SetObjectHandle.invokeExact(_name, v) as Int
}
    val PySys_GetXOptionsHandle: MethodHandle
    inline fun PySys_GetXOptions(): Long = PySys_GetXOptionsHandle.invokeExact() as Long
    val PySys_AuditTupleHandle: MethodHandle
inline fun PySys_AuditTuple(event: String, args: Long): Int {
    val _event = internedUtf8(event)
    return PySys_AuditTupleHandle.invokeExact(_event, args) as Int
}


    // Section 7
    val Py_FatalErrorHandle: MethodHandle
inline fun Py_FatalError(message: String) {
    val _message = encodeScratchUtf8(message)
    return Py_FatalErrorHandle.invokeExact(_message) as Unit
}
    val Py_ExitHandle: MethodHandle
    inline fun Py_Exit(status: Int) = Py_ExitHandle.invokeExact(status) as Unit


    // Section 8
    val PyImport_ImportModuleHandle: MethodHandle
inline fun PyImport_ImportModule(name: String): Long {
    val _name = internedUtf8(name)
    return PyImport_ImportModuleHandle.invokeExact(_name) as Long
}
    val PyImport_ImportModuleLevelObjectHandle: MethodHandle
    inline fun PyImport_ImportModuleLevelObject(name: Long, globals: Long, locals: Long, fromlist: Long, level: Int): Long = PyImport_ImportModuleLevelObjectHandle.invokeExact(name, globals, locals, fromlist, level) as Long
    val PyImport_ImportModuleLevelHandle: MethodHandle
inline fun PyImport_ImportModuleLevel(name: String, globals: Long, locals: Long, fromlist: Long, level: Int): Long {
    val _name = internedUtf8(name)
    return PyImport_ImportModuleLevelHandle.invokeExact(_name, globals, locals, fromlist, level) as Long
}
    val PyImport_ImportHandle: MethodHandle
    inline fun PyImport_Import(name: Long): Long = PyImport_ImportHandle.invokeExact(name) as Long
    val PyImport_ReloadModuleHandle: MethodHandle
    inline fun PyImport_ReloadModule(m: Long): Long = PyImport_ReloadModuleHandle.invokeExact(m) as Long
    val PyImport_AddModuleRefHandle: MethodHandle
inline fun PyImport_AddModuleRef(name: String): Long {
    val _name = internedUtf8(name)
    return PyImport_AddModuleRefHandle.invokeExact(_name) as Long
}
    val PyImport_AddModuleObjectHandle: MethodHandle
    inline fun PyImport_AddModuleObject(name: Long): Long = PyImport_AddModuleObjectHandle.invokeExact(name) as Long
    val PyImport_AddModuleHandle: MethodHandle
inline fun PyImport_AddModule(name: String): Long {
    val _name = internedUtf8(name)
    return PyImport_AddModuleHandle.invokeExact(_name) as Long
}
    val PyImport_ExecCodeModuleHandle: MethodHandle
inline fun PyImport_ExecCodeModule(name: String, co: Long): Long {
    val _name = internedUtf8(name)
    return PyImport_ExecCodeModuleHandle.invokeExact(_name, co) as Long
}
    val PyImport_ExecCodeModuleExHandle: MethodHandle
inline fun PyImport_ExecCodeModuleEx(name: String, co: Long, pathname: String): Long {
    val _name = internedUtf8(name)
    val _pathname = encodeScratchUtf8(pathname)
    return PyImport_ExecCodeModuleExHandle.invokeExact(_name, co, _pathname) as Long
}
    val PyImport_ExecCodeModuleObjectHandle: MethodHandle
    inline fun PyImport_ExecCodeModuleObject(name: Long, co: Long, pathname: Long, cpathname: Long): Long = PyImport_ExecCodeModuleObjectHandle.invokeExact(name, co, pathname, cpathname) as Long
    val PyImport_ExecCodeModuleWithPathnamesHandle: MethodHandle
inline fun PyImport_ExecCodeModuleWithPathnames(name: String, co: Long, pathname: String, cpathname: String): Long {
    val _name = internedUtf8(name)
    val _pathname = encodeScratchUtf8(pathname)
    val _cpathname = encodeScratchUtf8(cpathname)
    return PyImport_ExecCodeModuleWithPathnamesHandle.invokeExact(_name, co, _pathname, _cpathname) as Long
}
    val PyImport_GetMagicTagHandle: MethodHandle
    inline fun PyImport_GetMagicTag(): String? = Panama.readUtf8String(PyImport_GetMagicTagHandle.invokeExact() as Long)
    val PyImport_GetModuleDictHandle: MethodHandle
    inline fun PyImport_GetModuleDict(): Long = PyImport_GetModuleDictHandle.invokeExact() as Long
    val PyImport_GetModuleHandle: MethodHandle
    inline fun PyImport_GetModule(name: Long): Long = PyImport_GetModuleHandle.invokeExact(name) as Long
    val PyImport_GetImporterHandle: MethodHandle
    inline fun PyImport_GetImporter(path: Long): Long = PyImport_GetImporterHandle.invokeExact(path) as Long
    val PyImport_ImportFrozenModuleObjectHandle: MethodHandle
    inline fun PyImport_ImportFrozenModuleObject(name: Long): Int = PyImport_ImportFrozenModuleObjectHandle.invokeExact(name) as Int
    val PyImport_ImportFrozenModuleHandle: MethodHandle
inline fun PyImport_ImportFrozenModule(name: String): Int {
    val _name = internedUtf8(name)
    return PyImport_ImportFrozenModuleHandle.invokeExact(_name) as Int
}


    // Section 9
    val PyEval_GetBuiltinsHandle: MethodHandle
    inline fun PyEval_GetBuiltins(): Long = PyEval_GetBuiltinsHandle.invokeExact() as Long
    val PyEval_GetLocalsHandle: MethodHandle
    inline fun PyEval_GetLocals(): Long = PyEval_GetLocalsHandle.invokeExact() as Long
    val PyEval_GetGlobalsHandle: MethodHandle
    inline fun PyEval_GetGlobals(): Long = PyEval_GetGlobalsHandle.invokeExact() as Long
    val PyEval_GetFrameBuiltinsHandle: MethodHandle
    inline fun PyEval_GetFrameBuiltins(): Long = PyEval_GetFrameBuiltinsHandle.invokeExact() as Long
    val PyEval_GetFrameLocalsHandle: MethodHandle
    inline fun PyEval_GetFrameLocals(): Long = PyEval_GetFrameLocalsHandle.invokeExact() as Long
    val PyEval_GetFrameGlobalsHandle: MethodHandle
    inline fun PyEval_GetFrameGlobals(): Long = PyEval_GetFrameGlobalsHandle.invokeExact() as Long
    val PyEval_GetFuncNameHandle: MethodHandle
    inline fun PyEval_GetFuncName(func: Long): String? = Panama.readUtf8String(PyEval_GetFuncNameHandle.invokeExact(func) as Long)
    val PyEval_GetFuncDescHandle: MethodHandle
    inline fun PyEval_GetFuncDesc(func: Long): String? = Panama.readUtf8String(PyEval_GetFuncDescHandle.invokeExact(func) as Long)


    // Section 10
    val PyObject_HasAttrWithErrorHandle: MethodHandle
    inline fun PyObject_HasAttrWithError(o: Long, attr_name: Long): Int = PyObject_HasAttrWithErrorHandle.invokeExact(o, attr_name) as Int
    val PyObject_HasAttrStringWithErrorHandle: MethodHandle
inline fun PyObject_HasAttrStringWithError(o: Long, attr_name: String): Int {
    val _attr_name = internedUtf8(attr_name)
    return PyObject_HasAttrStringWithErrorHandle.invokeExact(o, _attr_name) as Int
}
    val PyObject_HasAttrHandle: MethodHandle
    inline fun PyObject_HasAttr(o: Long, attr_name: Long): Int = PyObject_HasAttrHandle.invokeExact(o, attr_name) as Int
    val PyObject_HasAttrStringHandle: MethodHandle
inline fun PyObject_HasAttrString(o: Long, attr_name: String): Int {
    val _attr_name = internedUtf8(attr_name)
    return PyObject_HasAttrStringHandle.invokeExact(o, _attr_name) as Int
}
    val PyObject_GetAttrHandle: MethodHandle
    inline fun PyObject_GetAttr(o: Long, attr_name: Long): Long = PyObject_GetAttrHandle.invokeExact(o, attr_name) as Long
    val PyObject_GetAttrStringHandle: MethodHandle
inline fun PyObject_GetAttrString(o: Long, attr_name: String): Long {
    val _attr_name = internedUtf8(attr_name)
    return PyObject_GetAttrStringHandle.invokeExact(o, _attr_name) as Long
}
    val PyObject_GenericGetAttrHandle: MethodHandle
    inline fun PyObject_GenericGetAttr(o: Long, name: Long): Long = PyObject_GenericGetAttrHandle.invokeExact(o, name) as Long
    val PyObject_SetAttrHandle: MethodHandle
    inline fun PyObject_SetAttr(o: Long, attr_name: Long, v: Long): Int = PyObject_SetAttrHandle.invokeExact(o, attr_name, v) as Int
    val PyObject_SetAttrStringHandle: MethodHandle
inline fun PyObject_SetAttrString(o: Long, attr_name: String, v: Long): Int {
    val _attr_name = internedUtf8(attr_name)
    return PyObject_SetAttrStringHandle.invokeExact(o, _attr_name, v) as Int
}
    val PyObject_GenericSetAttrHandle: MethodHandle
    inline fun PyObject_GenericSetAttr(o: Long, name: Long, value: Long): Int = PyObject_GenericSetAttrHandle.invokeExact(o, name, value) as Int
    val PyObject_DelAttrHandle: MethodHandle
    inline fun PyObject_DelAttr(o: Long, attr_name: Long): Int = PyObject_DelAttrHandle.invokeExact(o, attr_name) as Int
    val PyObject_DelAttrStringHandle: MethodHandle
inline fun PyObject_DelAttrString(o: Long, attr_name: String): Int {
    val _attr_name = internedUtf8(attr_name)
    return PyObject_DelAttrStringHandle.invokeExact(o, _attr_name) as Int
}
    val PyObject_RichCompareHandle: MethodHandle
    inline fun PyObject_RichCompare(o1: Long, o2: Long, opid: Int): Long = PyObject_RichCompareHandle.invokeExact(o1, o2, opid) as Long
    val PyObject_RichCompareBoolHandle: MethodHandle
    inline fun PyObject_RichCompareBool(o1: Long, o2: Long, opid: Int): Int = PyObject_RichCompareBoolHandle.invokeExact(o1, o2, opid) as Int
    val PyObject_FormatHandle: MethodHandle
    inline fun PyObject_Format(obj: Long, format_spec: Long): Long = PyObject_FormatHandle.invokeExact(obj, format_spec) as Long
    val PyObject_ReprHandle: MethodHandle
    inline fun PyObject_Repr(o: Long): Long = PyObject_ReprHandle.invokeExact(o) as Long
    val PyObject_ASCIIHandle: MethodHandle
    inline fun PyObject_ASCII(o: Long): Long = PyObject_ASCIIHandle.invokeExact(o) as Long
    val PyObject_StrHandle: MethodHandle
    inline fun PyObject_Str(o: Long): Long = PyObject_StrHandle.invokeExact(o) as Long
    val PyObject_BytesHandle: MethodHandle
    inline fun PyObject_Bytes(o: Long): Long = PyObject_BytesHandle.invokeExact(o) as Long
    val PyObject_IsSubclassHandle: MethodHandle
    inline fun PyObject_IsSubclass(derived: Long, cls: Long): Int = PyObject_IsSubclassHandle.invokeExact(derived, cls) as Int
    val PyObject_IsInstanceHandle: MethodHandle
    inline fun PyObject_IsInstance(inst: Long, cls: Long): Int = PyObject_IsInstanceHandle.invokeExact(inst, cls) as Int
    val PyObject_IsTrueHandle: MethodHandle
    inline fun PyObject_IsTrue(o: Long): Int = PyObject_IsTrueHandle.invokeExact(o) as Int
    val PyObject_NotHandle: MethodHandle
    inline fun PyObject_Not(o: Long): Int = PyObject_NotHandle.invokeExact(o) as Int
    val PyObject_TypeHandle: MethodHandle
    inline fun PyObject_Type(o: Long): Long = PyObject_TypeHandle.invokeExact(o) as Long
    val PyObject_SizeHandle: MethodHandle
    inline fun PyObject_Size(o: Long): Long = PyObject_SizeHandle.invokeExact(o) as Long
    val PyObject_LengthHandle: MethodHandle
    inline fun PyObject_Length(o: Long): Long = PyObject_LengthHandle.invokeExact(o) as Long
    val PyObject_GetItemHandle: MethodHandle
    inline fun PyObject_GetItem(o: Long, key: Long): Long = PyObject_GetItemHandle.invokeExact(o, key) as Long
    val PyObject_SetItemHandle: MethodHandle
    inline fun PyObject_SetItem(o: Long, key: Long, v: Long): Int = PyObject_SetItemHandle.invokeExact(o, key, v) as Int
    val PyObject_DelItemHandle: MethodHandle
    inline fun PyObject_DelItem(o: Long, key: Long): Int = PyObject_DelItemHandle.invokeExact(o, key) as Int
    val PyObject_DirHandle: MethodHandle
    inline fun PyObject_Dir(o: Long): Long = PyObject_DirHandle.invokeExact(o) as Long
    val PyObject_GetIterHandle: MethodHandle
    inline fun PyObject_GetIter(o: Long): Long = PyObject_GetIterHandle.invokeExact(o) as Long
    val PyObject_GetAIterHandle: MethodHandle
    inline fun PyObject_GetAIter(o: Long): Long = PyObject_GetAIterHandle.invokeExact(o) as Long


    // Section 11
    val PyVectorcall_CallHandle: MethodHandle
    inline fun PyVectorcall_Call(callable: Long, tuple: Long, dict: Long): Long = PyVectorcall_CallHandle.invokeExact(callable, tuple, dict) as Long
    val PyObject_CallHandle: MethodHandle
    inline fun PyObject_Call(callable: Long, args: Long, kwargs: Long): Long = PyObject_CallHandle.invokeExact(callable, args, kwargs) as Long
    val PyObject_CallNoArgsHandle: MethodHandle
    inline fun PyObject_CallNoArgs(callable: Long): Long = PyObject_CallNoArgsHandle.invokeExact(callable) as Long
    val PyObject_CallObjectHandle: MethodHandle
    inline fun PyObject_CallObject(callable: Long, args: Long): Long = PyObject_CallObjectHandle.invokeExact(callable, args) as Long
    val PyCallable_CheckHandle: MethodHandle
    inline fun PyCallable_Check(o: Long): Int = PyCallable_CheckHandle.invokeExact(o) as Int


    // Section 12
    val PyNumber_CheckHandle: MethodHandle
    inline fun PyNumber_Check(o: Long): Int = PyNumber_CheckHandle.invokeExact(o) as Int
    val PyNumber_AddHandle: MethodHandle
    inline fun PyNumber_Add(o1: Long, o2: Long): Long = PyNumber_AddHandle.invokeExact(o1, o2) as Long
    val PyNumber_SubtractHandle: MethodHandle
    inline fun PyNumber_Subtract(o1: Long, o2: Long): Long = PyNumber_SubtractHandle.invokeExact(o1, o2) as Long
    val PyNumber_MultiplyHandle: MethodHandle
    inline fun PyNumber_Multiply(o1: Long, o2: Long): Long = PyNumber_MultiplyHandle.invokeExact(o1, o2) as Long
    val PyNumber_MatrixMultiplyHandle: MethodHandle
    inline fun PyNumber_MatrixMultiply(o1: Long, o2: Long): Long = PyNumber_MatrixMultiplyHandle.invokeExact(o1, o2) as Long
    val PyNumber_FloorDivideHandle: MethodHandle
    inline fun PyNumber_FloorDivide(o1: Long, o2: Long): Long = PyNumber_FloorDivideHandle.invokeExact(o1, o2) as Long
    val PyNumber_TrueDivideHandle: MethodHandle
    inline fun PyNumber_TrueDivide(o1: Long, o2: Long): Long = PyNumber_TrueDivideHandle.invokeExact(o1, o2) as Long
    val PyNumber_RemainderHandle: MethodHandle
    inline fun PyNumber_Remainder(o1: Long, o2: Long): Long = PyNumber_RemainderHandle.invokeExact(o1, o2) as Long
    val PyNumber_DivmodHandle: MethodHandle
    inline fun PyNumber_Divmod(o1: Long, o2: Long): Long = PyNumber_DivmodHandle.invokeExact(o1, o2) as Long
    val PyNumber_PowerHandle: MethodHandle
    inline fun PyNumber_Power(o1: Long, o2: Long, o3: Long): Long = PyNumber_PowerHandle.invokeExact(o1, o2, o3) as Long
    val PyNumber_NegativeHandle: MethodHandle
    inline fun PyNumber_Negative(o: Long): Long = PyNumber_NegativeHandle.invokeExact(o) as Long
    val PyNumber_PositiveHandle: MethodHandle
    inline fun PyNumber_Positive(o: Long): Long = PyNumber_PositiveHandle.invokeExact(o) as Long
    val PyNumber_AbsoluteHandle: MethodHandle
    inline fun PyNumber_Absolute(o: Long): Long = PyNumber_AbsoluteHandle.invokeExact(o) as Long
    val PyNumber_InvertHandle: MethodHandle
    inline fun PyNumber_Invert(o: Long): Long = PyNumber_InvertHandle.invokeExact(o) as Long
    val PyNumber_LshiftHandle: MethodHandle
    inline fun PyNumber_Lshift(o1: Long, o2: Long): Long = PyNumber_LshiftHandle.invokeExact(o1, o2) as Long
    val PyNumber_RshiftHandle: MethodHandle
    inline fun PyNumber_Rshift(o1: Long, o2: Long): Long = PyNumber_RshiftHandle.invokeExact(o1, o2) as Long
    val PyNumber_AndHandle: MethodHandle
    inline fun PyNumber_And(o1: Long, o2: Long): Long = PyNumber_AndHandle.invokeExact(o1, o2) as Long
    val PyNumber_XorHandle: MethodHandle
    inline fun PyNumber_Xor(o1: Long, o2: Long): Long = PyNumber_XorHandle.invokeExact(o1, o2) as Long
    val PyNumber_OrHandle: MethodHandle
    inline fun PyNumber_Or(o1: Long, o2: Long): Long = PyNumber_OrHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceAddHandle: MethodHandle
    inline fun PyNumber_InPlaceAdd(o1: Long, o2: Long): Long = PyNumber_InPlaceAddHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceSubtractHandle: MethodHandle
    inline fun PyNumber_InPlaceSubtract(o1: Long, o2: Long): Long = PyNumber_InPlaceSubtractHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceMultiplyHandle: MethodHandle
    inline fun PyNumber_InPlaceMultiply(o1: Long, o2: Long): Long = PyNumber_InPlaceMultiplyHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceMatrixMultiplyHandle: MethodHandle
    inline fun PyNumber_InPlaceMatrixMultiply(o1: Long, o2: Long): Long = PyNumber_InPlaceMatrixMultiplyHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceFloorDivideHandle: MethodHandle
    inline fun PyNumber_InPlaceFloorDivide(o1: Long, o2: Long): Long = PyNumber_InPlaceFloorDivideHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceTrueDivideHandle: MethodHandle
    inline fun PyNumber_InPlaceTrueDivide(o1: Long, o2: Long): Long = PyNumber_InPlaceTrueDivideHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceRemainderHandle: MethodHandle
    inline fun PyNumber_InPlaceRemainder(o1: Long, o2: Long): Long = PyNumber_InPlaceRemainderHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlacePowerHandle: MethodHandle
    inline fun PyNumber_InPlacePower(o1: Long, o2: Long, o3: Long): Long = PyNumber_InPlacePowerHandle.invokeExact(o1, o2, o3) as Long
    val PyNumber_InPlaceLshiftHandle: MethodHandle
    inline fun PyNumber_InPlaceLshift(o1: Long, o2: Long): Long = PyNumber_InPlaceLshiftHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceRshiftHandle: MethodHandle
    inline fun PyNumber_InPlaceRshift(o1: Long, o2: Long): Long = PyNumber_InPlaceRshiftHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceAndHandle: MethodHandle
    inline fun PyNumber_InPlaceAnd(o1: Long, o2: Long): Long = PyNumber_InPlaceAndHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceXorHandle: MethodHandle
    inline fun PyNumber_InPlaceXor(o1: Long, o2: Long): Long = PyNumber_InPlaceXorHandle.invokeExact(o1, o2) as Long
    val PyNumber_InPlaceOrHandle: MethodHandle
    inline fun PyNumber_InPlaceOr(o1: Long, o2: Long): Long = PyNumber_InPlaceOrHandle.invokeExact(o1, o2) as Long
    val PyNumber_LongHandle: MethodHandle
    inline fun PyNumber_Long(o: Long): Long = PyNumber_LongHandle.invokeExact(o) as Long
    val PyNumber_FloatHandle: MethodHandle
    inline fun PyNumber_Float(o: Long): Long = PyNumber_FloatHandle.invokeExact(o) as Long
    val PyNumber_IndexHandle: MethodHandle
    inline fun PyNumber_Index(o: Long): Long = PyNumber_IndexHandle.invokeExact(o) as Long
    val PyNumber_ToBaseHandle: MethodHandle
    inline fun PyNumber_ToBase(n: Long, base: Int): Long = PyNumber_ToBaseHandle.invokeExact(n, base) as Long
    val PyIndex_CheckHandle: MethodHandle
    inline fun PyIndex_Check(o: Long): Int = PyIndex_CheckHandle.invokeExact(o) as Int


    // Section 13
    val PySequence_CheckHandle: MethodHandle
    inline fun PySequence_Check(o: Long): Int = PySequence_CheckHandle.invokeExact(o) as Int
    val PySequence_ConcatHandle: MethodHandle
    inline fun PySequence_Concat(o1: Long, o2: Long): Long = PySequence_ConcatHandle.invokeExact(o1, o2) as Long
    val PySequence_InPlaceConcatHandle: MethodHandle
    inline fun PySequence_InPlaceConcat(o1: Long, o2: Long): Long = PySequence_InPlaceConcatHandle.invokeExact(o1, o2) as Long
    val PySequence_ContainsHandle: MethodHandle
    inline fun PySequence_Contains(o: Long, value: Long): Int = PySequence_ContainsHandle.invokeExact(o, value) as Int
    val PySequence_ListHandle: MethodHandle
    inline fun PySequence_List(o: Long): Long = PySequence_ListHandle.invokeExact(o) as Long
    val PySequence_TupleHandle: MethodHandle
    inline fun PySequence_Tuple(o: Long): Long = PySequence_TupleHandle.invokeExact(o) as Long
    val PySequence_FastHandle: MethodHandle
inline fun PySequence_Fast(o: Long, m: String): Long {
    val _m = encodeScratchUtf8(m)
    return PySequence_FastHandle.invokeExact(o, _m) as Long
}


    // Section 14
    val PyMapping_CheckHandle: MethodHandle
    inline fun PyMapping_Check(o: Long): Int = PyMapping_CheckHandle.invokeExact(o) as Int
    val PyMapping_GetItemStringHandle: MethodHandle
inline fun PyMapping_GetItemString(o: Long, key: String): Long {
    val _key = internedUtf8(key)
    return PyMapping_GetItemStringHandle.invokeExact(o, _key) as Long
}
    val PyMapping_SetItemStringHandle: MethodHandle
inline fun PyMapping_SetItemString(o: Long, key: String, v: Long): Int {
    val _key = internedUtf8(key)
    return PyMapping_SetItemStringHandle.invokeExact(o, _key, v) as Int
}
    val PyMapping_HasKeyWithErrorHandle: MethodHandle
    inline fun PyMapping_HasKeyWithError(o: Long, key: Long): Int = PyMapping_HasKeyWithErrorHandle.invokeExact(o, key) as Int
    val PyMapping_HasKeyStringWithErrorHandle: MethodHandle
inline fun PyMapping_HasKeyStringWithError(o: Long, key: String): Int {
    val _key = internedUtf8(key)
    return PyMapping_HasKeyStringWithErrorHandle.invokeExact(o, _key) as Int
}
    val PyMapping_HasKeyHandle: MethodHandle
    inline fun PyMapping_HasKey(o: Long, key: Long): Int = PyMapping_HasKeyHandle.invokeExact(o, key) as Int
    val PyMapping_HasKeyStringHandle: MethodHandle
inline fun PyMapping_HasKeyString(o: Long, key: String): Int {
    val _key = internedUtf8(key)
    return PyMapping_HasKeyStringHandle.invokeExact(o, _key) as Int
}
    val PyMapping_KeysHandle: MethodHandle
    inline fun PyMapping_Keys(o: Long): Long = PyMapping_KeysHandle.invokeExact(o) as Long
    val PyMapping_ValuesHandle: MethodHandle
    inline fun PyMapping_Values(o: Long): Long = PyMapping_ValuesHandle.invokeExact(o) as Long
    val PyMapping_ItemsHandle: MethodHandle
    inline fun PyMapping_Items(o: Long): Long = PyMapping_ItemsHandle.invokeExact(o) as Long


    // Section 15
    val PyIter_CheckHandle: MethodHandle
    inline fun PyIter_Check(o: Long): Int = PyIter_CheckHandle.invokeExact(o) as Int
    val PyAIter_CheckHandle: MethodHandle
    inline fun PyAIter_Check(o: Long): Int = PyAIter_CheckHandle.invokeExact(o) as Int
    val PyIter_NextHandle: MethodHandle
    inline fun PyIter_Next(o: Long): Long = PyIter_NextHandle.invokeExact(o) as Long


    // Section 16
    val PyLong_FromLongLongHandle: MethodHandle
    inline fun PyLong_FromLongLong(v: Long): Long = PyLong_FromLongLongHandle.invokeExact(v) as Long
    val PyLong_FromDoubleHandle: MethodHandle
    inline fun PyLong_FromDouble(v: kotlin.Double): Long = PyLong_FromDoubleHandle.invokeExact(v) as Long
    val PyLong_AsIntHandle: MethodHandle
    inline fun PyLong_AsInt(obj: Long): Int = PyLong_AsIntHandle.invokeExact(obj) as Int
    val PyLong_AsLongLongHandle: MethodHandle
    inline fun PyLong_AsLongLong(obj: Long): Long = PyLong_AsLongLongHandle.invokeExact(obj) as Long
    val PyLong_AsDoubleHandle: MethodHandle
    inline fun PyLong_AsDouble(pylong: Long): kotlin.Double = PyLong_AsDoubleHandle.invokeExact(pylong) as kotlin.Double
    val PyLong_GetInfoHandle: MethodHandle
    inline fun PyLong_GetInfo(): Long = PyLong_GetInfoHandle.invokeExact() as Long


    // Section 17
    val PyBool_FromLongHandle: MethodHandle
    inline fun PyBool_FromLong(v: Long): Long = PyBool_FromLongHandle.invokeExact(v) as Long


    // Section 18
    val PyFloat_FromStringHandle: MethodHandle
    inline fun PyFloat_FromString(str: Long): Long = PyFloat_FromStringHandle.invokeExact(str) as Long
    val PyFloat_FromDoubleHandle: MethodHandle
    inline fun PyFloat_FromDouble(v: kotlin.Double): Long = PyFloat_FromDoubleHandle.invokeExact(v) as Long
    val PyFloat_AsDoubleHandle: MethodHandle
    inline fun PyFloat_AsDouble(pyfloat: Long): kotlin.Double = PyFloat_AsDoubleHandle.invokeExact(pyfloat) as kotlin.Double
    val PyFloat_GetInfoHandle: MethodHandle
    inline fun PyFloat_GetInfo(): Long = PyFloat_GetInfoHandle.invokeExact() as Long
    val PyFloat_GetMaxHandle: MethodHandle
    inline fun PyFloat_GetMax(): kotlin.Double = PyFloat_GetMaxHandle.invokeExact() as kotlin.Double
    val PyFloat_GetMinHandle: MethodHandle
    inline fun PyFloat_GetMin(): kotlin.Double = PyFloat_GetMinHandle.invokeExact() as kotlin.Double


    // Section 19
    val PyBytes_FromStringHandle: MethodHandle
inline fun PyBytes_FromString(v: String): Long {
    val _v = encodeScratchUtf8(v)
    return PyBytes_FromStringHandle.invokeExact(_v) as Long
}
    val PyBytes_FromObjectHandle: MethodHandle
    inline fun PyBytes_FromObject(o: Long): Long = PyBytes_FromObjectHandle.invokeExact(o) as Long
    val PyBytes_AsStringHandle: MethodHandle
    inline fun PyBytes_AsString(o: Long): String? = Panama.readUtf8String(PyBytes_AsStringHandle.invokeExact(o) as Long)


    // Section 20
    val PyByteArray_FromObjectHandle: MethodHandle
    inline fun PyByteArray_FromObject(o: Long): Long = PyByteArray_FromObjectHandle.invokeExact(o) as Long
    val PyByteArray_ConcatHandle: MethodHandle
    inline fun PyByteArray_Concat(a: Long, b: Long): Long = PyByteArray_ConcatHandle.invokeExact(a, b) as Long
    val PyByteArray_AsStringHandle: MethodHandle
    inline fun PyByteArray_AsString(bytearray: Long): String? = Panama.readUtf8String(PyByteArray_AsStringHandle.invokeExact(bytearray) as Long)


    // Section 21
    val PyUnicode_IsIdentifierHandle: MethodHandle
    inline fun PyUnicode_IsIdentifier(unicode: Long): Int = PyUnicode_IsIdentifierHandle.invokeExact(unicode) as Int
    val PyUnicode_FromStringHandle: MethodHandle
inline fun PyUnicode_FromString(str: String): Long {
    val _str = encodeScratchUtf8(str)
    return PyUnicode_FromStringHandle.invokeExact(_str) as Long
}
    val PyUnicode_FromObjectHandle: MethodHandle
    inline fun PyUnicode_FromObject(obj: Long): Long = PyUnicode_FromObjectHandle.invokeExact(obj) as Long
    val PyUnicode_FromEncodedObjectHandle: MethodHandle
inline fun PyUnicode_FromEncodedObject(obj: Long, encoding: String, errors: String): Long {
    val _encoding = internedUtf8(encoding)
    val _errors = encodeScratchUtf8(errors)
    return PyUnicode_FromEncodedObjectHandle.invokeExact(obj, _encoding, _errors) as Long
}
    val PyUnicode_DecodeLocaleHandle: MethodHandle
inline fun PyUnicode_DecodeLocale(str: String, errors: String): Long {
    val _str = encodeScratchUtf8(str)
    val _errors = encodeScratchUtf8(errors)
    return PyUnicode_DecodeLocaleHandle.invokeExact(_str, _errors) as Long
}
    val PyUnicode_EncodeLocaleHandle: MethodHandle
inline fun PyUnicode_EncodeLocale(unicode: Long, errors: String): Long {
    val _errors = encodeScratchUtf8(errors)
    return PyUnicode_EncodeLocaleHandle.invokeExact(unicode, _errors) as Long
}
    val PyUnicode_DecodeFSDefaultHandle: MethodHandle
inline fun PyUnicode_DecodeFSDefault(str: String): Long {
    val _str = encodeScratchUtf8(str)
    return PyUnicode_DecodeFSDefaultHandle.invokeExact(_str) as Long
}
    val PyUnicode_EncodeFSDefaultHandle: MethodHandle
    inline fun PyUnicode_EncodeFSDefault(unicode: Long): Long = PyUnicode_EncodeFSDefaultHandle.invokeExact(unicode) as Long
    val PyUnicode_AsEncodedStringHandle: MethodHandle
inline fun PyUnicode_AsEncodedString(unicode: Long, encoding: String, errors: String): Long {
    val _encoding = internedUtf8(encoding)
    val _errors = encodeScratchUtf8(errors)
    return PyUnicode_AsEncodedStringHandle.invokeExact(unicode, _encoding, _errors) as Long
}
    val PyUnicode_AsUTF8StringHandle: MethodHandle
    inline fun PyUnicode_AsUTF8String(unicode: Long): Long = PyUnicode_AsUTF8StringHandle.invokeExact(unicode) as Long
    val PyUnicode_AsUTF8Handle: MethodHandle
    inline fun PyUnicode_AsUTF8(unicode: Long): String? = Panama.readUtf8String(PyUnicode_AsUTF8Handle.invokeExact(unicode) as Long)
    val PyUnicode_AsUTF32StringHandle: MethodHandle
    inline fun PyUnicode_AsUTF32String(unicode: Long): Long = PyUnicode_AsUTF32StringHandle.invokeExact(unicode) as Long
    val PyUnicode_AsUTF16StringHandle: MethodHandle
    inline fun PyUnicode_AsUTF16String(unicode: Long): Long = PyUnicode_AsUTF16StringHandle.invokeExact(unicode) as Long
    val PyUnicode_AsUnicodeEscapeStringHandle: MethodHandle
    inline fun PyUnicode_AsUnicodeEscapeString(unicode: Long): Long = PyUnicode_AsUnicodeEscapeStringHandle.invokeExact(unicode) as Long
    val PyUnicode_AsRawUnicodeEscapeStringHandle: MethodHandle
    inline fun PyUnicode_AsRawUnicodeEscapeString(unicode: Long): Long = PyUnicode_AsRawUnicodeEscapeStringHandle.invokeExact(unicode) as Long
    val PyUnicode_AsLatin1StringHandle: MethodHandle
    inline fun PyUnicode_AsLatin1String(unicode: Long): Long = PyUnicode_AsLatin1StringHandle.invokeExact(unicode) as Long
    val PyUnicode_AsASCIIStringHandle: MethodHandle
    inline fun PyUnicode_AsASCIIString(unicode: Long): Long = PyUnicode_AsASCIIStringHandle.invokeExact(unicode) as Long
    val PyUnicode_AsCharmapStringHandle: MethodHandle
    inline fun PyUnicode_AsCharmapString(unicode: Long, mapping: Long): Long = PyUnicode_AsCharmapStringHandle.invokeExact(unicode, mapping) as Long
    val PyUnicode_TranslateHandle: MethodHandle
inline fun PyUnicode_Translate(unicode: Long, table: Long, errors: String): Long {
    val _errors = encodeScratchUtf8(errors)
    return PyUnicode_TranslateHandle.invokeExact(unicode, table, _errors) as Long
}
    val PyUnicode_ConcatHandle: MethodHandle
    inline fun PyUnicode_Concat(left: Long, right: Long): Long = PyUnicode_ConcatHandle.invokeExact(left, right) as Long
    val PyUnicode_SplitlinesHandle: MethodHandle
    inline fun PyUnicode_Splitlines(unicode: Long, keepends: Int): Long = PyUnicode_SplitlinesHandle.invokeExact(unicode, keepends) as Long
    val PyUnicode_JoinHandle: MethodHandle
    inline fun PyUnicode_Join(separator: Long, seq: Long): Long = PyUnicode_JoinHandle.invokeExact(separator, seq) as Long
    val PyUnicode_CompareHandle: MethodHandle
    inline fun PyUnicode_Compare(left: Long, right: Long): Int = PyUnicode_CompareHandle.invokeExact(left, right) as Int
    val PyUnicode_EqualToUTF8Handle: MethodHandle
inline fun PyUnicode_EqualToUTF8(unicode: Long, string: String): Int {
    val _string = encodeScratchUtf8(string)
    return PyUnicode_EqualToUTF8Handle.invokeExact(unicode, _string) as Int
}
    val PyUnicode_CompareWithASCIIStringHandle: MethodHandle
inline fun PyUnicode_CompareWithASCIIString(unicode: Long, string: String): Int {
    val _string = encodeScratchUtf8(string)
    return PyUnicode_CompareWithASCIIStringHandle.invokeExact(unicode, _string) as Int
}
    val PyUnicode_RichCompareHandle: MethodHandle
    inline fun PyUnicode_RichCompare(left: Long, right: Long, op: Int): Long = PyUnicode_RichCompareHandle.invokeExact(left, right, op) as Long
    val PyUnicode_FormatHandle: MethodHandle
    inline fun PyUnicode_Format(format: Long, args: Long): Long = PyUnicode_FormatHandle.invokeExact(format, args) as Long
    val PyUnicode_ContainsHandle: MethodHandle
    inline fun PyUnicode_Contains(unicode: Long, substr: Long): Int = PyUnicode_ContainsHandle.invokeExact(unicode, substr) as Int
    val PyUnicode_InternFromStringHandle: MethodHandle
inline fun PyUnicode_InternFromString(str: String): Long {
    val _str = internedUtf8(str)
    return PyUnicode_InternFromStringHandle.invokeExact(_str) as Long
}


    // Section 22
    val PyList_NewHandle: MethodHandle
    fun PyList_New(len: Long): Long = PyList_NewHandle.invokeExact(len) as Long
    val PyList_SizeHandle: MethodHandle
    inline fun PyList_Size(list: Long): Long = PyList_SizeHandle.invokeExact(list) as Long
    val PyList_GetItemHandle: MethodHandle
    fun PyList_GetItem(list: Long, index: Long): Long = PyList_GetItemHandle.invokeExact(list, index) as Long
    val PyList_SetItemHandle: MethodHandle
    inline fun PyList_SetItem(list: Long, index: Long, item: Long): Int = PyList_SetItemHandle.invokeExact(list, index, item) as Int
    val PyList_InsertHandle: MethodHandle
    inline fun PyList_Insert(list: Long, index: Long, item: Long): Int = PyList_InsertHandle.invokeExact(list, index, item) as Int
    val PyList_AppendHandle: MethodHandle
    inline fun PyList_Append(list: Long, item: Long): Int = PyList_AppendHandle.invokeExact(list, item) as Int
    val PyList_SortHandle: MethodHandle
    inline fun PyList_Sort(list: Long): Int = PyList_SortHandle.invokeExact(list) as Int
    val PyList_ReverseHandle: MethodHandle
    inline fun PyList_Reverse(list: Long): Int = PyList_ReverseHandle.invokeExact(list) as Int
    val PyList_AsTupleHandle: MethodHandle
    inline fun PyList_AsTuple(list: Long): Long = PyList_AsTupleHandle.invokeExact(list) as Long


    // Section 23
    val PyDict_NewHandle: MethodHandle
    inline fun PyDict_New(): Long = PyDict_NewHandle.invokeExact() as Long
    val PyDict_SizeHandle: MethodHandle
    inline fun PyDict_Size(p: Long): Long = PyDict_SizeHandle.invokeExact(p) as Long
    val PyDictProxy_NewHandle: MethodHandle
    inline fun PyDictProxy_New(mapping: Long): Long = PyDictProxy_NewHandle.invokeExact(mapping) as Long
    val PyDict_ClearHandle: MethodHandle
    inline fun PyDict_Clear(p: Long) = PyDict_ClearHandle.invokeExact(p) as Unit
    val PyDict_ContainsHandle: MethodHandle
    inline fun PyDict_Contains(p: Long, key: Long): Int = PyDict_ContainsHandle.invokeExact(p, key) as Int
    val PyDict_CopyHandle: MethodHandle
    inline fun PyDict_Copy(p: Long): Long = PyDict_CopyHandle.invokeExact(p) as Long
    val PyDict_SetItemHandle: MethodHandle
    inline fun PyDict_SetItem(p: Long, key: Long, v: Long): Int = PyDict_SetItemHandle.invokeExact(p, key, v) as Int
    val PyDict_SetItemStringHandle: MethodHandle
inline fun PyDict_SetItemString(p: Long, key: String, v: Long): Int {
    val _key = internedUtf8(key)
    return PyDict_SetItemStringHandle.invokeExact(p, _key, v) as Int
}
    val PyDict_DelItemHandle: MethodHandle
    inline fun PyDict_DelItem(p: Long, key: Long): Int = PyDict_DelItemHandle.invokeExact(p, key) as Int
    val PyDict_DelItemStringHandle: MethodHandle
inline fun PyDict_DelItemString(p: Long, key: String): Int {
    val _key = internedUtf8(key)
    return PyDict_DelItemStringHandle.invokeExact(p, _key) as Int
}
    val PyDict_GetItemHandle: MethodHandle
    inline fun PyDict_GetItem(p: Long, key: Long): Long = PyDict_GetItemHandle.invokeExact(p, key) as Long
    val PyDict_GetItemWithErrorHandle: MethodHandle
    inline fun PyDict_GetItemWithError(p: Long, key: Long): Long = PyDict_GetItemWithErrorHandle.invokeExact(p, key) as Long
    val PyDict_GetItemStringHandle: MethodHandle
inline fun PyDict_GetItemString(p: Long, key: String): Long {
    val _key = internedUtf8(key)
    return PyDict_GetItemStringHandle.invokeExact(p, _key) as Long
}
    val PyDict_ItemsHandle: MethodHandle
    inline fun PyDict_Items(p: Long): Long = PyDict_ItemsHandle.invokeExact(p) as Long
    val PyDict_KeysHandle: MethodHandle
    inline fun PyDict_Keys(p: Long): Long = PyDict_KeysHandle.invokeExact(p) as Long
    val PyDict_ValuesHandle: MethodHandle
    inline fun PyDict_Values(p: Long): Long = PyDict_ValuesHandle.invokeExact(p) as Long
    val PyDict_MergeHandle: MethodHandle
    inline fun PyDict_Merge(a: Long, b: Long, override: Int): Int = PyDict_MergeHandle.invokeExact(a, b, override) as Int
    val PyDict_UpdateHandle: MethodHandle
    inline fun PyDict_Update(a: Long, b: Long): Int = PyDict_UpdateHandle.invokeExact(a, b) as Int
    val PyDict_MergeFromSeq2Handle: MethodHandle
    inline fun PyDict_MergeFromSeq2(a: Long, seq2: Long, override: Int): Int = PyDict_MergeFromSeq2Handle.invokeExact(a, seq2, override) as Int


    // Section 24
    val PySet_NewHandle: MethodHandle
    inline fun PySet_New(iterable: Long): Long = PySet_NewHandle.invokeExact(iterable) as Long
    val PyFrozenSet_NewHandle: MethodHandle
    inline fun PyFrozenSet_New(iterable: Long): Long = PyFrozenSet_NewHandle.invokeExact(iterable) as Long
    val PySet_ContainsHandle: MethodHandle
    inline fun PySet_Contains(anyset: Long, key: Long): Int = PySet_ContainsHandle.invokeExact(anyset, key) as Int
    val PySet_SizeHandle: MethodHandle
    inline fun PySet_Size(anyset: Long): Long = PySet_SizeHandle.invokeExact(anyset) as Long
    val PySet_AddHandle: MethodHandle
    inline fun PySet_Add(set: Long, key: Long): Int = PySet_AddHandle.invokeExact(set, key) as Int
    val PySet_DiscardHandle: MethodHandle
    inline fun PySet_Discard(set: Long, key: Long): Int = PySet_DiscardHandle.invokeExact(set, key) as Int
    val PySet_PopHandle: MethodHandle
    inline fun PySet_Pop(set: Long): Long = PySet_PopHandle.invokeExact(set) as Long
    val PySet_ClearHandle: MethodHandle
    inline fun PySet_Clear(set: Long): Int = PySet_ClearHandle.invokeExact(set) as Int


    // Section 25
    val PySeqIter_NewHandle: MethodHandle
    inline fun PySeqIter_New(seq: Long): Long = PySeqIter_NewHandle.invokeExact(seq) as Long
    val PyCallIter_NewHandle: MethodHandle
    inline fun PyCallIter_New(callable: Long, sentinel: Long): Long = PyCallIter_NewHandle.invokeExact(callable, sentinel) as Long


    // Section 26
    val PyWeakref_NewRefHandle: MethodHandle
    inline fun PyWeakref_NewRef(ob: Long, callback: Long): Long = PyWeakref_NewRefHandle.invokeExact(ob, callback) as Long
    val PyWeakref_NewProxyHandle: MethodHandle
    inline fun PyWeakref_NewProxy(ob: Long, callback: Long): Long = PyWeakref_NewProxyHandle.invokeExact(ob, callback) as Long
    val PyWeakref_GetRefHandle: MethodHandle
    /**
     * `int PyWeakref_GetRef(PyObject *ref, PyObject **pobj)`, collapsed to the referent pointer.
     *
     * The C function reports through two channels: an `int` status and an out-parameter. The slot
     * for the out-parameter has to be real native memory, so it is allocated, passed and read
     * here rather than at the call site -- `EmbedAPI.desktop.kt` sees a plain `Long`.
     *
     * Returns the new strong reference on success and `0` otherwise. `0` covers both a dead
     * referent (status `0`) and an error (status `-1`); the caller separates them with
     * `PyErr_Occurred`, exactly as the `expect` documents. `finally` matters here: the status is
     * read before the slot is freed and the slot is freed even if `invokeExact` throws.
     */
    inline fun PyWeakref_GetRef(ref: Long): Long {
        val slot = Panama.allocatePointerSlot()
        try {
            val status = PyWeakref_GetRefHandle.invokeExact(ref, slot) as Int
            return if (status == 1) Panama.readPointerSlot(slot) else 0L
        } finally {
            Panama.freePointerSlot(slot)
        }
    }
    val PyObject_ClearWeakRefsHandle: MethodHandle
    inline fun PyObject_ClearWeakRefs(o: Long) = PyObject_ClearWeakRefsHandle.invokeExact(o) as Unit


    // Section 27
    val PyType_IsSubtypeHandle: MethodHandle
    inline fun PyType_IsSubtype(a: Long, b: Long): Int = PyType_IsSubtypeHandle.invokeExact(a, b) as Int
    val PyType_ReadyHandle: MethodHandle
    inline fun PyType_Ready(type: Long): Int = PyType_ReadyHandle.invokeExact(type) as Int
    val PyType_GetNameHandle: MethodHandle
    inline fun PyType_GetName(type: Long): Long = PyType_GetNameHandle.invokeExact(type) as Long
    val PyType_GetFullyQualifiedNameHandle: MethodHandle
    inline fun PyType_GetFullyQualifiedName(type: Long): Long = PyType_GetFullyQualifiedNameHandle.invokeExact(type) as Long
    val PyType_GetModuleNameHandle: MethodHandle
    inline fun PyType_GetModuleName(type: Long): Long = PyType_GetModuleNameHandle.invokeExact(type) as Long
    val PyType_GetModuleHandle: MethodHandle
    inline fun PyType_GetModule(type: Long): Long = PyType_GetModuleHandle.invokeExact(type) as Long
    
    
    // Section 28
    val PyTuple_NewHandle: MethodHandle
    fun PyTuple_New(len: Long): Long = PyTuple_NewHandle.invokeExact(len) as Long
    val PyTuple_SizeHandle: MethodHandle
    inline fun PyTuple_Size(p: Long): Long = PyTuple_SizeHandle.invokeExact(p) as Long
    val PyTuple_GetItemHandle: MethodHandle
    fun PyTuple_GetItem(p: Long, pos: Long): Long = PyTuple_GetItemHandle.invokeExact(p, pos) as Long
    val PyTuple_GetSliceHandle: MethodHandle
    fun PyTuple_GetSlice(p: Long, low: Long, high: Long): Long = PyTuple_GetSliceHandle.invokeExact(p, low, high) as Long
    val PyTuple_SetItemHandle: MethodHandle
    inline fun PyTuple_SetItem(p: Long, pos: Long, o: Long): Int = PyTuple_SetItemHandle.invokeExact(p, pos, o) as Int


    // Section 29
    val PyModule_GetNameHandle: MethodHandle
    inline fun PyModule_GetName(module: Long): String? = Panama.readUtf8String(PyModule_GetNameHandle.invokeExact(module) as Long)
    val PyModule_GetDictHandle: MethodHandle
    fun PyModule_GetDict(module: Long): Long = PyModule_GetDictHandle.invokeExact(module) as Long
    val PyModule_GetFilenameObjectHandle: MethodHandle
    fun PyModule_GetFilenameObject(module: Long): Long = PyModule_GetFilenameObjectHandle.invokeExact(module) as Long

    val PyType_FromSpecHandle: MethodHandle
    fun PyType_FromSpec(spec: Long): Long = PyType_FromSpecHandle.invokeExact(spec) as Long
    
    val PyObject_GetTypeDataHandle: MethodHandle
    fun PyObject_GetTypeData(obj: Long, type: Long): Long = PyObject_GetTypeDataHandle.invokeExact(obj, type) as Long

    /**
     * `void *PyType_GetSlot(PyTypeObject *type, int slot)`.
     *
     * The abi3 way to reach a slot function of a type whose struct is opaque under the limited
     * API. `tp_dealloc` needs it for `Py_tp_free` (74): a hand-written dealloc must free the
     * object itself, and the allocator that matches the type is only reachable through here.
     */
    val PyType_GetSlotHandle: MethodHandle
    fun PyType_GetSlot(type: Long, slot: Int): Long = PyType_GetSlotHandle.invokeExact(type, slot) as Long

    /**
     * `void PyObject_GC_UnTrack(void *op)`.
     *
     * Removes a GC-tracked object from the collector's list. A `tp_dealloc` on a
     * `Py_TPFLAGS_HAVE_GC` type must call this before freeing the object, or the collector walks
     * a list that contains released memory.
     */
    val PyObject_GC_UnTrackHandle: MethodHandle
    fun PyObject_GC_UnTrack(op: Long) = PyObject_GC_UnTrackHandle.invokeExact(op) as Unit

    init {
        val P = Panama.POINTER_TYPE  // Long.TYPE, represents a native pointer

        // Load the native library first
        manager.loadLibPython()

        // Helper to look up a symbol and create a downcall handle
        fun find(symbol: String, returnType: Class<*>, vararg params: Class<*>): MethodHandle =
            Panama.findSymbol(symbol, returnType, arrayOf(*params))


        //**************************************************
        // Section 1
        Py_InitializeHandle = find("Py_Initialize", Void.TYPE)
        Py_InitializeExHandle = find("Py_InitializeEx", Void.TYPE, Integer.TYPE)
        Py_SetPythonHomeHandle = find("Py_SetPythonHome", Void.TYPE, P)
        Py_IsInitializedHandle = find("Py_IsInitialized", Integer.TYPE)
        Py_IsFinalizingHandle = find("Py_IsFinalizing", Integer.TYPE)
        Py_FinalizeExHandle = find("Py_FinalizeEx", Integer.TYPE)
        Py_FinalizeHandle = find("Py_Finalize", Void.TYPE)
        Py_RunMainHandle = find("Py_RunMain", Integer.TYPE)
        Py_GetVersionHandle = find("Py_GetVersion", P)
        Py_GetPlatformHandle = find("Py_GetPlatform", P)
        Py_GetCopyrightHandle = find("Py_GetCopyright", P)
        Py_GetCompilerHandle = find("Py_GetCompiler", P)
        Py_GetBuildInfoHandle = find("Py_GetBuildInfo", P)
        PyEval_InitThreadsHandle = find("PyEval_InitThreads", Void.TYPE)
        PyThreadState_GetDictHandle = find("PyThreadState_GetDict", P)
        PyGILState_EnsureHandle = find("PyGILState_Ensure", Integer.TYPE)
        PyGILState_ReleaseHandle = find("PyGILState_Release", Void.TYPE, Integer.TYPE)
        PyGILState_GetThisThreadStateHandle = find("PyGILState_GetThisThreadState", P)
        PyEval_SaveThreadHandle = find("PyEval_SaveThread", P)
        PyEval_RestoreThreadHandle = find("PyEval_RestoreThread", Void.TYPE, P)
        Py_MakePendingCallsHandle = find("Py_MakePendingCalls", Integer.TYPE)
        PyGC_CollectHandle = find("PyGC_Collect", LongLong.TYPE)


        // Section 2
        PyRun_SimpleStringHandle = find("PyRun_SimpleString", Integer.TYPE, P)
        PyRun_StringHandle = find("PyRun_String", P, P, Integer.TYPE, P, P)
        Py_CompileStringHandle = find("Py_CompileString", P, P, P, Integer.TYPE)
        PyEval_EvalCodeHandle = find("PyEval_EvalCode", P, P, P, P)


        // Section 3
        PyErr_ClearHandle = find("PyErr_Clear", Void.TYPE)
        PyErr_PrintExHandle = find("PyErr_PrintEx", Void.TYPE, Integer.TYPE)
        PyErr_PrintHandle = find("PyErr_Print", Void.TYPE)
        PyErr_WriteUnraisableHandle = find("PyErr_WriteUnraisable", Void.TYPE, P)
        PyErr_DisplayExceptionHandle = find("PyErr_DisplayException", Void.TYPE, P)
        PyErr_SetStringHandle = find("PyErr_SetString", Void.TYPE, P, P)
        PyErr_SetObjectHandle = find("PyErr_SetObject", Void.TYPE, P, P)
        PyErr_SetNoneHandle = find("PyErr_SetNone", Void.TYPE, P)
        PyErr_BadArgumentHandle = find("PyErr_BadArgument", Integer.TYPE)
        PyErr_NoMemoryHandle = find("PyErr_NoMemory", P)
        PyErr_SetFromErrnoHandle = find("PyErr_SetFromErrno", P, P)
        PyErr_SetFromErrnoWithFilenameObjectHandle = find("PyErr_SetFromErrnoWithFilenameObject", P, P, P)
        PyErr_SetFromErrnoWithFilenameObjectsHandle = find("PyErr_SetFromErrnoWithFilenameObjects", P, P, P, P)
        PyErr_SetFromErrnoWithFilenameHandle = find("PyErr_SetFromErrnoWithFilename", P, P, P)
        PyErr_SetImportErrorHandle = find("PyErr_SetImportError", P, P, P, P)
        PyErr_SetImportErrorSubclassHandle = find("PyErr_SetImportErrorSubclass", P, P, P, P, P)
        PyErr_SyntaxLocationExHandle = find("PyErr_SyntaxLocationEx", Void.TYPE, P, Integer.TYPE, Integer.TYPE)
        PyErr_SyntaxLocationHandle = find("PyErr_SyntaxLocation", Void.TYPE, P, Integer.TYPE)
        PyErr_BadInternalCallHandle = find("PyErr_BadInternalCall", Void.TYPE)
        PyErr_WarnExplicitHandle = find("PyErr_WarnExplicit", Integer.TYPE, P, P, P, Integer.TYPE, P, P)
        PyErr_OccurredHandle = find("PyErr_Occurred", P)
        PyErr_ExceptionMatchesHandle = find("PyErr_ExceptionMatches", Integer.TYPE, P)
        PyErr_GivenExceptionMatchesHandle = find("PyErr_GivenExceptionMatches", Integer.TYPE, P, P)
        PyErr_GetRaisedExceptionHandle = find("PyErr_GetRaisedException", P)
        PyErr_SetRaisedExceptionHandle = find("PyErr_SetRaisedException", Void.TYPE, P)
        PyErr_RestoreHandle = find("PyErr_Restore", Void.TYPE, P, P, P)
        PyErr_GetHandledExceptionHandle = find("PyErr_GetHandledException", P)
        PyErr_SetHandledExceptionHandle = find("PyErr_SetHandledException", Void.TYPE, P)
        PyErr_SetExcInfoHandle = find("PyErr_SetExcInfo", Void.TYPE, P, P, P)
        PyErr_CheckSignalsHandle = find("PyErr_CheckSignals", Integer.TYPE)
        PyErr_SetInterruptHandle = find("PyErr_SetInterrupt", Void.TYPE)
        PyErr_SetInterruptExHandle = find("PyErr_SetInterruptEx", Integer.TYPE, Integer.TYPE)
        PyErr_NewExceptionHandle = find("PyErr_NewException", P, P, P, P)
        PyErr_NewExceptionWithDocHandle = find("PyErr_NewExceptionWithDoc", P, P, P, P, P)
        PyException_GetTracebackHandle = find("PyException_GetTraceback", P, P)
        PyException_SetTracebackHandle = find("PyException_SetTraceback", Integer.TYPE, P, P)
        PyException_GetContextHandle = find("PyException_GetContext", P, P)
        PyException_SetContextHandle = find("PyException_SetContext", Void.TYPE, P, P)
        PyException_GetCauseHandle = find("PyException_GetCause", P, P)
        PyException_SetCauseHandle = find("PyException_SetCause", Void.TYPE, P, P)
        PyException_GetArgsHandle = find("PyException_GetArgs", P, P)
        PyException_SetArgsHandle = find("PyException_SetArgs", Void.TYPE, P, P)
        PyUnicodeEncodeError_GetEncodingHandle = find("PyUnicodeEncodeError_GetEncoding", P, P)
        PyUnicodeTranslateError_GetObjectHandle = find("PyUnicodeTranslateError_GetObject", P, P)
        PyUnicodeTranslateError_GetReasonHandle = find("PyUnicodeTranslateError_GetReason", P, P)
        PyUnicodeTranslateError_SetReasonHandle = find("PyUnicodeTranslateError_SetReason", Integer.TYPE, P, P)
        Py_EnterRecursiveCallHandle = find("Py_EnterRecursiveCall", Integer.TYPE, P)
        Py_LeaveRecursiveCallHandle = find("Py_LeaveRecursiveCall", Void.TYPE)
        Py_ReprEnterHandle = find("Py_ReprEnter", Integer.TYPE, P)
        Py_ReprLeaveHandle = find("Py_ReprLeave", Void.TYPE, P)


        // Section 4
        Py_NewRefHandle = find("Py_NewRef", P, P)
        Py_XNewRefHandle = find("Py_XNewRef", P, P)
        Py_IncRefHandle = find("Py_IncRef", Void.TYPE, P)
        Py_DecRefHandle = find("Py_DecRef", Void.TYPE, P)


        // Section 5
        PyOS_FSPathHandle = find("PyOS_FSPath", P, P)


        // Section 6
        PySys_GetObjectHandle = find("PySys_GetObject", P, P)
        PySys_SetObjectHandle = find("PySys_SetObject", Integer.TYPE, P, P)
        PySys_GetXOptionsHandle = find("PySys_GetXOptions", P)
        PySys_AuditTupleHandle = find("PySys_AuditTuple", Integer.TYPE, P, P)


        // Section 7
        Py_FatalErrorHandle = find("Py_FatalError", Void.TYPE, P)
        Py_ExitHandle = find("Py_Exit", Void.TYPE, Integer.TYPE)


        // Section 8
        PyImport_ImportModuleHandle = find("PyImport_ImportModule", P, P)
        PyImport_ImportModuleLevelObjectHandle = find("PyImport_ImportModuleLevelObject", P, P, P, P, P, Integer.TYPE)
        PyImport_ImportModuleLevelHandle = find("PyImport_ImportModuleLevel", P, P, P, P, P, Integer.TYPE)
        PyImport_ImportHandle = find("PyImport_Import", P, P)
        PyImport_ReloadModuleHandle = find("PyImport_ReloadModule", P, P)
        PyImport_AddModuleRefHandle = find("PyImport_AddModuleRef", P, P)
        PyImport_AddModuleObjectHandle = find("PyImport_AddModuleObject", P, P)
        PyImport_AddModuleHandle = find("PyImport_AddModule", P, P)
        PyImport_ExecCodeModuleHandle = find("PyImport_ExecCodeModule", P, P, P)
        PyImport_ExecCodeModuleExHandle = find("PyImport_ExecCodeModuleEx", P, P, P, P)
        PyImport_ExecCodeModuleObjectHandle = find("PyImport_ExecCodeModuleObject", P, P, P, P, P)
        PyImport_ExecCodeModuleWithPathnamesHandle = find("PyImport_ExecCodeModuleWithPathnames", P, P, P, P, P)
        PyImport_GetMagicTagHandle = find("PyImport_GetMagicTag", P)
        PyImport_GetModuleDictHandle = find("PyImport_GetModuleDict", P)
        PyImport_GetModuleHandle = find("PyImport_GetModule", P, P)
        PyImport_GetImporterHandle = find("PyImport_GetImporter", P, P)
        PyImport_ImportFrozenModuleObjectHandle = find("PyImport_ImportFrozenModuleObject", Integer.TYPE, P)
        PyImport_ImportFrozenModuleHandle = find("PyImport_ImportFrozenModule", Integer.TYPE, P)


        // Section 9
        PyEval_GetBuiltinsHandle = find("PyEval_GetBuiltins", P)
        PyEval_GetLocalsHandle = find("PyEval_GetLocals", P)
        PyEval_GetGlobalsHandle = find("PyEval_GetGlobals", P)
        PyEval_GetFrameBuiltinsHandle = find("PyEval_GetFrameBuiltins", P)
        PyEval_GetFrameLocalsHandle = find("PyEval_GetFrameLocals", P)
        PyEval_GetFrameGlobalsHandle = find("PyEval_GetFrameGlobals", P)
        PyEval_GetFuncNameHandle = find("PyEval_GetFuncName", P, P)
        PyEval_GetFuncDescHandle = find("PyEval_GetFuncDesc", P, P)


        // Section 10
        PyObject_HasAttrWithErrorHandle = find("PyObject_HasAttrWithError", Integer.TYPE, P, P)
        PyObject_HasAttrStringWithErrorHandle = find("PyObject_HasAttrStringWithError", Integer.TYPE, P, P)
        PyObject_HasAttrHandle = find("PyObject_HasAttr", Integer.TYPE, P, P)
        PyObject_HasAttrStringHandle = find("PyObject_HasAttrString", Integer.TYPE, P, P)
        PyObject_GetAttrHandle = find("PyObject_GetAttr", P, P, P)
        PyObject_GetAttrStringHandle = find("PyObject_GetAttrString", P, P, P)
        PyObject_GenericGetAttrHandle = find("PyObject_GenericGetAttr", P, P, P)
        PyObject_SetAttrHandle = find("PyObject_SetAttr", Integer.TYPE, P, P, P)
        PyObject_SetAttrStringHandle = find("PyObject_SetAttrString", Integer.TYPE, P, P, P)
        PyObject_GenericSetAttrHandle = find("PyObject_GenericSetAttr", Integer.TYPE, P, P, P)
        PyObject_DelAttrHandle = find("PyObject_DelAttr", Integer.TYPE, P, P)
        PyObject_DelAttrStringHandle = find("PyObject_DelAttrString", Integer.TYPE, P, P)
        PyObject_RichCompareHandle = find("PyObject_RichCompare", P, P, P, Integer.TYPE)
        PyObject_RichCompareBoolHandle = find("PyObject_RichCompareBool", Integer.TYPE, P, P, Integer.TYPE)
        PyObject_FormatHandle = find("PyObject_Format", P, P, P)
        PyObject_ReprHandle = find("PyObject_Repr", P, P)
        PyObject_ASCIIHandle = find("PyObject_ASCII", P, P)
        PyObject_StrHandle = find("PyObject_Str", P, P)
        PyObject_BytesHandle = find("PyObject_Bytes", P, P)
        PyObject_IsSubclassHandle = find("PyObject_IsSubclass", Integer.TYPE, P, P)
        PyObject_IsInstanceHandle = find("PyObject_IsInstance", Integer.TYPE, P, P)
        PyObject_IsTrueHandle = find("PyObject_IsTrue", Integer.TYPE, P)
        PyObject_NotHandle = find("PyObject_Not", Integer.TYPE, P)
        PyObject_TypeHandle = find("PyObject_Type", P, P)
        PyObject_SizeHandle = find("PyObject_Size", LongLong.TYPE, P)
        PyObject_LengthHandle = find("PyObject_Length", LongLong.TYPE, P)
        PyObject_GetItemHandle = find("PyObject_GetItem", P, P, P)
        PyObject_SetItemHandle = find("PyObject_SetItem", Integer.TYPE, P, P, P)
        PyObject_DelItemHandle = find("PyObject_DelItem", Integer.TYPE, P, P)
        PyObject_DirHandle = find("PyObject_Dir", P, P)
        PyObject_GetIterHandle = find("PyObject_GetIter", P, P)
        PyObject_GetAIterHandle = find("PyObject_GetAIter", P, P)


        // Section 11
        PyVectorcall_CallHandle = find("PyVectorcall_Call", P, P, P, P)
        PyObject_CallHandle = find("PyObject_Call", P, P, P, P)
        PyObject_CallNoArgsHandle = find("PyObject_CallNoArgs", P, P)
        PyObject_CallObjectHandle = find("PyObject_CallObject", P, P, P)
        PyCallable_CheckHandle = find("PyCallable_Check", Integer.TYPE, P)


        // Section 12
        PyNumber_CheckHandle = find("PyNumber_Check", Integer.TYPE, P)
        PyNumber_AddHandle = find("PyNumber_Add", P, P, P)
        PyNumber_SubtractHandle = find("PyNumber_Subtract", P, P, P)
        PyNumber_MultiplyHandle = find("PyNumber_Multiply", P, P, P)
        PyNumber_MatrixMultiplyHandle = find("PyNumber_MatrixMultiply", P, P, P)
        PyNumber_FloorDivideHandle = find("PyNumber_FloorDivide", P, P, P)
        PyNumber_TrueDivideHandle = find("PyNumber_TrueDivide", P, P, P)
        PyNumber_RemainderHandle = find("PyNumber_Remainder", P, P, P)
        PyNumber_DivmodHandle = find("PyNumber_Divmod", P, P, P)
        PyNumber_PowerHandle = find("PyNumber_Power", P, P, P, P)
        PyNumber_NegativeHandle = find("PyNumber_Negative", P, P)
        PyNumber_PositiveHandle = find("PyNumber_Positive", P, P)
        PyNumber_AbsoluteHandle = find("PyNumber_Absolute", P, P)
        PyNumber_InvertHandle = find("PyNumber_Invert", P, P)
        PyNumber_LshiftHandle = find("PyNumber_Lshift", P, P, P)
        PyNumber_RshiftHandle = find("PyNumber_Rshift", P, P, P)
        PyNumber_AndHandle = find("PyNumber_And", P, P, P)
        PyNumber_XorHandle = find("PyNumber_Xor", P, P, P)
        PyNumber_OrHandle = find("PyNumber_Or", P, P, P)
        PyNumber_InPlaceAddHandle = find("PyNumber_InPlaceAdd", P, P, P)
        PyNumber_InPlaceSubtractHandle = find("PyNumber_InPlaceSubtract", P, P, P)
        PyNumber_InPlaceMultiplyHandle = find("PyNumber_InPlaceMultiply", P, P, P)
        PyNumber_InPlaceMatrixMultiplyHandle = find("PyNumber_InPlaceMatrixMultiply", P, P, P)
        PyNumber_InPlaceFloorDivideHandle = find("PyNumber_InPlaceFloorDivide", P, P, P)
        PyNumber_InPlaceTrueDivideHandle = find("PyNumber_InPlaceTrueDivide", P, P, P)
        PyNumber_InPlaceRemainderHandle = find("PyNumber_InPlaceRemainder", P, P, P)
        PyNumber_InPlacePowerHandle = find("PyNumber_InPlacePower", P, P, P, P)
        PyNumber_InPlaceLshiftHandle = find("PyNumber_InPlaceLshift", P, P, P)
        PyNumber_InPlaceRshiftHandle = find("PyNumber_InPlaceRshift", P, P, P)
        PyNumber_InPlaceAndHandle = find("PyNumber_InPlaceAnd", P, P, P)
        PyNumber_InPlaceXorHandle = find("PyNumber_InPlaceXor", P, P, P)
        PyNumber_InPlaceOrHandle = find("PyNumber_InPlaceOr", P, P, P)
        PyNumber_LongHandle = find("PyNumber_Long", P, P)
        PyNumber_FloatHandle = find("PyNumber_Float", P, P)
        PyNumber_IndexHandle = find("PyNumber_Index", P, P)
        PyNumber_ToBaseHandle = find("PyNumber_ToBase", P, P, Integer.TYPE)
        PyIndex_CheckHandle = find("PyIndex_Check", Integer.TYPE, P)


        // Section 13
        PySequence_CheckHandle = find("PySequence_Check", Integer.TYPE, P)
        PySequence_ConcatHandle = find("PySequence_Concat", P, P, P)
        PySequence_InPlaceConcatHandle = find("PySequence_InPlaceConcat", P, P, P)
        PySequence_ContainsHandle = find("PySequence_Contains", Integer.TYPE, P, P)
        PySequence_ListHandle = find("PySequence_List", P, P)
        PySequence_TupleHandle = find("PySequence_Tuple", P, P)
        PySequence_FastHandle = find("PySequence_Fast", P, P, P)


        // Section 14
        PyMapping_CheckHandle = find("PyMapping_Check", Integer.TYPE, P)
        PyMapping_GetItemStringHandle = find("PyMapping_GetItemString", P, P, P)
        PyMapping_SetItemStringHandle = find("PyMapping_SetItemString", Integer.TYPE, P, P, P)
        PyMapping_HasKeyWithErrorHandle = find("PyMapping_HasKeyWithError", Integer.TYPE, P, P)
        PyMapping_HasKeyStringWithErrorHandle = find("PyMapping_HasKeyStringWithError", Integer.TYPE, P, P)
        PyMapping_HasKeyHandle = find("PyMapping_HasKey", Integer.TYPE, P, P)
        PyMapping_HasKeyStringHandle = find("PyMapping_HasKeyString", Integer.TYPE, P, P)
        PyMapping_KeysHandle = find("PyMapping_Keys", P, P)
        PyMapping_ValuesHandle = find("PyMapping_Values", P, P)
        PyMapping_ItemsHandle = find("PyMapping_Items", P, P)


        // Section 15
        PyIter_CheckHandle = find("PyIter_Check", Integer.TYPE, P)
        PyAIter_CheckHandle = find("PyAIter_Check", Integer.TYPE, P)
        PyIter_NextHandle = find("PyIter_Next", P, P)


        // Section 16
        PyLong_FromLongLongHandle = find("PyLong_FromLongLong", P, LongLong.TYPE)
        PyLong_FromDoubleHandle = find("PyLong_FromDouble", P, Double.TYPE)
        PyLong_AsIntHandle = find("PyLong_AsInt", Integer.TYPE, P)
        PyLong_AsLongLongHandle = find("PyLong_AsLongLong", LongLong.TYPE, P)
        PyLong_AsDoubleHandle = find("PyLong_AsDouble", Double.TYPE, P)
        PyLong_GetInfoHandle = find("PyLong_GetInfo", P)


        // Section 17
        PyBool_FromLongHandle = find("PyBool_FromLong", P, LongLong.TYPE)


        // Section 18
        PyFloat_FromStringHandle = find("PyFloat_FromString", P, P)
        PyFloat_FromDoubleHandle = find("PyFloat_FromDouble", P, Double.TYPE)
        PyFloat_AsDoubleHandle = find("PyFloat_AsDouble", Double.TYPE, P)
        PyFloat_GetInfoHandle = find("PyFloat_GetInfo", P)
        PyFloat_GetMaxHandle = find("PyFloat_GetMax", Double.TYPE)
        PyFloat_GetMinHandle = find("PyFloat_GetMin", Double.TYPE)


        // Section 19
        PyBytes_FromStringHandle = find("PyBytes_FromString", P, P)
        PyBytes_FromObjectHandle = find("PyBytes_FromObject", P, P)
        PyBytes_AsStringHandle = find("PyBytes_AsString", P, P)


        // Section 20
        PyByteArray_FromObjectHandle = find("PyByteArray_FromObject", P, P)
        PyByteArray_ConcatHandle = find("PyByteArray_Concat", P, P, P)
        PyByteArray_AsStringHandle = find("PyByteArray_AsString", P, P)


        // Section 21
        PyUnicode_IsIdentifierHandle = find("PyUnicode_IsIdentifier", Integer.TYPE, P)
        PyUnicode_FromStringHandle = find("PyUnicode_FromString", P, P)
        PyUnicode_FromObjectHandle = find("PyUnicode_FromObject", P, P)
        PyUnicode_FromEncodedObjectHandle = find("PyUnicode_FromEncodedObject", P, P, P, P)
        PyUnicode_DecodeLocaleHandle = find("PyUnicode_DecodeLocale", P, P, P)
        PyUnicode_EncodeLocaleHandle = find("PyUnicode_EncodeLocale", P, P, P)
        PyUnicode_DecodeFSDefaultHandle = find("PyUnicode_DecodeFSDefault", P, P)
        PyUnicode_EncodeFSDefaultHandle = find("PyUnicode_EncodeFSDefault", P, P)
        PyUnicode_AsEncodedStringHandle = find("PyUnicode_AsEncodedString", P, P, P, P)
        PyUnicode_AsUTF8StringHandle = find("PyUnicode_AsUTF8String", P, P)
        PyUnicode_AsUTF8Handle = find("PyUnicode_AsUTF8", P, P)
        PyUnicode_AsUTF32StringHandle = find("PyUnicode_AsUTF32String", P, P)
        PyUnicode_AsUTF16StringHandle = find("PyUnicode_AsUTF16String", P, P)
        PyUnicode_AsUnicodeEscapeStringHandle = find("PyUnicode_AsUnicodeEscapeString", P, P)
        PyUnicode_AsRawUnicodeEscapeStringHandle = find("PyUnicode_AsRawUnicodeEscapeString", P, P)
        PyUnicode_AsLatin1StringHandle = find("PyUnicode_AsLatin1String", P, P)
        PyUnicode_AsASCIIStringHandle = find("PyUnicode_AsASCIIString", P, P)
        PyUnicode_AsCharmapStringHandle = find("PyUnicode_AsCharmapString", P, P, P)
        PyUnicode_TranslateHandle = find("PyUnicode_Translate", P, P, P, P)
        PyUnicode_ConcatHandle = find("PyUnicode_Concat", P, P, P)
        PyUnicode_SplitlinesHandle = find("PyUnicode_Splitlines", P, P, Integer.TYPE)
        PyUnicode_JoinHandle = find("PyUnicode_Join", P, P, P)
        PyUnicode_CompareHandle = find("PyUnicode_Compare", Integer.TYPE, P, P)
        PyUnicode_EqualToUTF8Handle = find("PyUnicode_EqualToUTF8", Integer.TYPE, P, P)
        PyUnicode_CompareWithASCIIStringHandle = find("PyUnicode_CompareWithASCIIString", Integer.TYPE, P, P)
        PyUnicode_RichCompareHandle = find("PyUnicode_RichCompare", P, P, P, Integer.TYPE)
        PyUnicode_FormatHandle = find("PyUnicode_Format", P, P, P)
        PyUnicode_ContainsHandle = find("PyUnicode_Contains", Integer.TYPE, P, P)
        PyUnicode_InternFromStringHandle = find("PyUnicode_InternFromString", P, P)


        // Section 22
        PyList_NewHandle = find("PyList_New", P, LongLong.TYPE)
        PyList_SizeHandle = find("PyList_Size", LongLong.TYPE, P)
        PyList_GetItemHandle = find("PyList_GetItem", P, P, LongLong.TYPE)
        PyList_SetItemHandle = find("PyList_SetItem", Integer.TYPE, P, LongLong.TYPE, P)
        PyList_InsertHandle = find("PyList_Insert", Integer.TYPE, P, LongLong.TYPE, P)
        PyList_AppendHandle = find("PyList_Append", Integer.TYPE, P, P)
        PyList_SortHandle = find("PyList_Sort", Integer.TYPE, P)
        PyList_ReverseHandle = find("PyList_Reverse", Integer.TYPE, P)
        PyList_AsTupleHandle = find("PyList_AsTuple", P, P)


        // Section 23
        PyDict_NewHandle = find("PyDict_New", P)
        PyDict_SizeHandle = find("PyDict_Size", LongLong.TYPE, P)
        PyDictProxy_NewHandle = find("PyDictProxy_New", P, P)
        PyDict_ClearHandle = find("PyDict_Clear", Void.TYPE, P)
        PyDict_ContainsHandle = find("PyDict_Contains", Integer.TYPE, P, P)
        PyDict_CopyHandle = find("PyDict_Copy", P, P)
        PyDict_SetItemHandle = find("PyDict_SetItem", Integer.TYPE, P, P, P)
        PyDict_SetItemStringHandle = find("PyDict_SetItemString", Integer.TYPE, P, P, P)
        PyDict_DelItemHandle = find("PyDict_DelItem", Integer.TYPE, P, P)
        PyDict_DelItemStringHandle = find("PyDict_DelItemString", Integer.TYPE, P, P)
        PyDict_GetItemHandle = find("PyDict_GetItem", P, P, P)
        PyDict_GetItemWithErrorHandle = find("PyDict_GetItemWithError", P, P, P)
        PyDict_GetItemStringHandle = find("PyDict_GetItemString", P, P, P)
        PyDict_ItemsHandle = find("PyDict_Items", P, P)
        PyDict_KeysHandle = find("PyDict_Keys", P, P)
        PyDict_ValuesHandle = find("PyDict_Values", P, P)
        PyDict_MergeHandle = find("PyDict_Merge", Integer.TYPE, P, P, Integer.TYPE)
        PyDict_UpdateHandle = find("PyDict_Update", Integer.TYPE, P, P)
        PyDict_MergeFromSeq2Handle = find("PyDict_MergeFromSeq2", Integer.TYPE, P, P, Integer.TYPE)


        // Section 24
        PySet_NewHandle = find("PySet_New", P, P)
        PyFrozenSet_NewHandle = find("PyFrozenSet_New", P, P)
        PySet_ContainsHandle = find("PySet_Contains", Integer.TYPE, P, P)
        PySet_SizeHandle = find("PySet_Size", LongLong.TYPE, P)
        PySet_AddHandle = find("PySet_Add", Integer.TYPE, P, P)
        PySet_DiscardHandle = find("PySet_Discard", Integer.TYPE, P, P)
        PySet_PopHandle = find("PySet_Pop", P, P)
        PySet_ClearHandle = find("PySet_Clear", Integer.TYPE, P)


        // Section 25
        PySeqIter_NewHandle = find("PySeqIter_New", P, P)
        PyCallIter_NewHandle = find("PyCallIter_New", P, P, P)


        // Section 26
        PyWeakref_NewRefHandle = find("PyWeakref_NewRef", P, P, P)
        PyWeakref_NewProxyHandle = find("PyWeakref_NewProxy", P, P, P)
        PyWeakref_GetRefHandle = find("PyWeakref_GetRef", Integer.TYPE, P, P)
        PyObject_ClearWeakRefsHandle = find("PyObject_ClearWeakRefs", Void.TYPE, P)


        // Section 27
        PyType_IsSubtypeHandle = find("PyType_IsSubtype", Integer.TYPE, P, P)
        PyType_ReadyHandle = find("PyType_Ready", Integer.TYPE, P)
        PyType_GetNameHandle = find("PyType_GetName", P, P)
        PyType_GetFullyQualifiedNameHandle = find("PyType_GetFullyQualifiedName", P, P)
        PyType_GetModuleNameHandle = find("PyType_GetModuleName", P, P)
        PyType_GetModuleHandle = find("PyType_GetModule", P, P)


        // Section 28
        PyTuple_NewHandle = find("PyTuple_New", P, LongLong.TYPE)
        PyTuple_SizeHandle = find("PyTuple_Size", LongLong.TYPE, P)
        PyTuple_GetItemHandle = find("PyTuple_GetItem", P, P, LongLong.TYPE)
        PyTuple_GetSliceHandle = find("PyTuple_GetSlice", P, P, LongLong.TYPE, LongLong.TYPE)
        PyTuple_SetItemHandle = find("PyTuple_SetItem", Integer.TYPE, P, LongLong.TYPE, P)


        // Section 29
        PyModule_GetNameHandle = find("PyModule_GetName", P, P)
        PyModule_GetDictHandle = find("PyModule_GetDict", P, P)
        PyModule_GetFilenameObjectHandle = find("PyModule_GetFilenameObject", P, P)
        PyType_FromSpecHandle = find("PyType_FromSpec", P, P)
        PyObject_GetTypeDataHandle = find("PyObject_GetTypeData", P, P, P)
        PyType_GetSlotHandle = find("PyType_GetSlot", P, P, Integer.TYPE)
        PyObject_GC_UnTrackHandle = find("PyObject_GC_UnTrack", Void.TYPE, P)
    }
}
