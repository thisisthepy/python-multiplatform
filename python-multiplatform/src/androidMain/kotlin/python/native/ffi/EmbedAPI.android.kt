package python.native.ffi


actual inline fun <R> memScoped(block: () -> R): R = block()


@JvmInline
internal value class NativeAddressValue(val ptr: JNIPointer): AddressValue {
    override fun toString(): String = "JNIPointer(raw=0x${ptr.toString(16)})"
}
@HighOverheadNativeCall
actual fun NativePointer.toAddressValue(): AddressValue = NativeAddressValue(toPlatformPointer())
@HighOverheadNativeCall
actual fun AddressValue.toNativePointer(): NativePointer = NativePointer((this as NativeAddressValue).ptr)
/**
 * Deliberately does not delegate to the `JNIPointer?` overload below.
 *
 * `JNIPointer` is a typealias for `Long`, so that overload's receiver is `Long?` and this one's is
 * `Long`. Overload resolution prefers the more specific, non-null receiver, that is, this function
 * itself, so writing `toNativePointer()` here recurses forever and blows the stack. On Android the
 * platform pointer *is* the `Long`, so the conversion is written out instead.
 */
@HighOverheadNativeCall
actual fun Long.toNativePointer(): NativePointer? = if (this > 0) NativePointer(this) else null
internal inline fun JNIPointer?.toNativePointer(): NativePointer? = this?.let { if (it > 0) NativePointer(it) else null }


// Section 1
// Runs site.py and other start-up Python; unbounded, so never on a GC-blocking path.
actual inline fun Py_Initialize() = python.native.ffi.bindings.Py_InitializeN()
actual inline fun Py_InitializeEx(initsigs: Int) = python.native.ffi.bindings.Py_InitializeExN(initsigs)
actual inline fun Py_IsInitialized(): Int = if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.Py_IsInitializedF() else python.native.ffi.bindings.Py_IsInitialized()
actual inline fun Py_IsFinalizing(): Int = python.native.ffi.bindings.Py_IsFinalizingN()
actual inline fun Py_FinalizeEx(): Int = python.native.ffi.bindings.Py_FinalizeExN()
// Runs atexit handlers and __del__ during teardown.
actual inline fun Py_Finalize() = python.native.ffi.bindings.Py_FinalizeN()
actual inline fun Py_RunMain(): Int = python.native.ffi.bindings.Py_RunMainN() // 수동 추가
actual inline fun Py_GetVersion(): String? {
    // CPython-owned const char* return value. JVM side must NOT free it.
    val ptr = if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.Py_GetVersionF() else python.native.ffi.bindings.Py_GetVersion()
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
actual inline fun Py_GetPlatform(): String? {
    val ptr = python.native.ffi.bindings.Py_GetPlatformN()
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
actual inline fun Py_GetCopyright(): String? {
    val ptr = python.native.ffi.bindings.Py_GetCopyrightN()
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
actual inline fun Py_GetCompiler(): String? {
    val ptr = python.native.ffi.bindings.Py_GetCompilerN()
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
actual inline fun Py_GetBuildInfo(): String? {
    val ptr = python.native.ffi.bindings.Py_GetBuildInfoN()
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
// PyEval_InitThreads has no actual: it is a @Deprecated Kotlin no-op in EmbedAPI.kt, matching the empty CPython 3.9+ body.
actual fun PyThreadState_GetDict(): NativePointer? = python.native.ffi.bindings.PyThreadState_GetDictN().toNativePointer()

actual inline fun PyGILState_Ensure(): Int = python.native.ffi.bindings.PyGILState_EnsureN()
actual inline fun PyGILState_Release(state: Int) = python.native.ffi.bindings.PyGILState_ReleaseN(state)
actual fun PyGILState_GetThisThreadState(): NativePointer? = python.native.ffi.bindings.PyGILState_GetThisThreadStateN().toNativePointer()
actual fun PyEval_SaveThread(): NativePointer? = python.native.ffi.bindings.PyEval_SaveThreadN().toNativePointer()
actual inline fun PyEval_RestoreThread(tstate: NativePointer) = python.native.ffi.bindings.PyEval_RestoreThreadN(tstate.toPlatformPointer())
actual fun Py_MakePendingCalls(): Int = python.native.ffi.bindings.Py_MakePendingCallsN()
actual fun PyGC_Collect(): Long = python.native.ffi.bindings.PyGC_CollectN()


// Section 2
actual inline fun PyRun_SimpleString(command: String): Int { // 수동 추가
    val ptr = encodeScratchUtf8(command)
    // Executes arbitrary Python.
    return python.native.ffi.bindings.PyRun_SimpleStringN(ptr)
}
actual fun PyRun_String(str: String, start: Int, globals: NativePointer, locals: NativePointer): NativePointer? {
    val ptr = encodeScratchUtf8(str)
    return python.native.ffi.bindings.PyRun_StringN(ptr, start, globals.toPlatformPointer(), locals.toPlatformPointer()).toNativePointer()
}
// Two string arguments, two different judgements: `str` is the source text being compiled and is
// arbitrary content, while `filename` is the repeated label it is compiled under.
actual fun Py_CompileString(str: String, filename: String, start: Int): NativePointer? {
    val _str = encodeScratchUtf8(str)
    val _filename = internedUtf8(filename)
    return python.native.ffi.bindings.Py_CompileStringN(_str, _filename, start).toNativePointer()
}
actual fun PyEval_EvalCode(co: NativePointer, globals: NativePointer, locals: NativePointer): NativePointer? = python.native.ffi.bindings.PyEval_EvalCodeN(co.toPlatformPointer(), globals.toPlatformPointer(), locals.toPlatformPointer()).toNativePointer()


// Section 3
// Drops the last reference to the exception, which can run a Python __del__.
actual inline fun PyErr_Clear() = python.native.ffi.bindings.PyErr_ClearN()
actual inline fun PyErr_PrintEx(set_sys_last_vars: Int) = python.native.ffi.bindings.PyErr_PrintExN(set_sys_last_vars)
actual inline fun PyErr_Print() = python.native.ffi.bindings.PyErr_PrintN()
actual inline fun PyErr_WriteUnraisable(obj: NativePointer) = python.native.ffi.bindings.PyErr_WriteUnraisableN(obj.toPlatformPointer())
actual inline fun PyErr_DisplayException(exc: NativePointer) = python.native.ffi.bindings.PyErr_DisplayExceptionN(exc.toPlatformPointer())
// Exception messages are arbitrary content, not repeated identifiers, so they take the
// thread-local scratch rather than the intern cache -- see docs/design/marshalling-design.md.
actual inline fun PyErr_SetString(type: NativePointer, message: String) =
    python.native.ffi.bindings.PyErr_SetStringN(type.toPlatformPointer(), encodeScratchUtf8(message))
actual inline fun PyErr_SetObject(type: NativePointer, value: NativePointer) = python.native.ffi.bindings.PyErr_SetObjectN(type.toPlatformPointer(), value.toPlatformPointer())
actual inline fun PyErr_SetNone(type: NativePointer) = python.native.ffi.bindings.PyErr_SetNoneN(type.toPlatformPointer())
actual inline fun PyErr_BadArgument(): Int = python.native.ffi.bindings.PyErr_BadArgumentN()
actual fun PyErr_NoMemory(): NativePointer? = python.native.ffi.bindings.PyErr_NoMemoryN().toNativePointer()
actual fun PyErr_SetFromErrno(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetFromErrnoN(type.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetFromErrnoWithFilenameObject(type: NativePointer, filenameObject: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetFromErrnoWithFilenameObjectN(type.toPlatformPointer(), filenameObject.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetFromErrnoWithFilenameObjects(type: NativePointer, filenameObject: NativePointer, filenameObject2: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetFromErrnoWithFilenameObjectsN(type.toPlatformPointer(), filenameObject.toPlatformPointer(), filenameObject2.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetFromErrnoWithFilename(type: NativePointer, filename: String): NativePointer? =
    python.native.ffi.bindings.PyErr_SetFromErrnoWithFilenameN(type.toPlatformPointer(), encodeScratchUtf8(filename)).toNativePointer()
actual fun PyErr_SetImportError(msg: NativePointer, name: NativePointer, path: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetImportErrorN(msg.toPlatformPointer(), name.toPlatformPointer(), path.toPlatformPointer()).toNativePointer()
actual fun PyErr_SetImportErrorSubclass(exception: NativePointer, msg: NativePointer, name: NativePointer, path: NativePointer): NativePointer? = python.native.ffi.bindings.PyErr_SetImportErrorSubclassN(exception.toPlatformPointer(), msg.toPlatformPointer(), name.toPlatformPointer(), path.toPlatformPointer()).toNativePointer()
// A syntax-error location names the file the source came from, which for a caller reporting an
// error is a one-off path rather than a repeated identifier.
actual inline fun PyErr_SyntaxLocationEx(filename: String, lineno: Int, col_offset: Int) =
    python.native.ffi.bindings.PyErr_SyntaxLocationExN(encodeScratchUtf8(filename), lineno, col_offset)
actual inline fun PyErr_SyntaxLocation(filename: String, lineno: Int) =
    python.native.ffi.bindings.PyErr_SyntaxLocationN(encodeScratchUtf8(filename), lineno)
actual inline fun PyErr_BadInternalCall() = python.native.ffi.bindings.PyErr_BadInternalCallN()
// `message` is the warning text (arbitrary), while `filename` and `module` are the repeated
// registry keys CPython uses to decide whether the warning has already been shown.
// Three encodes, one of them scratch -- inside the 4-slot budget.
actual inline fun PyErr_WarnExplicit(category: NativePointer, message: String, filename: String, lineno: Int, module: String, registry: NativePointer): Int {
    val _message = encodeScratchUtf8(message)
    val _filename = internedUtf8(filename)
    val _module = internedUtf8(module)
    return python.native.ffi.bindings.PyErr_WarnExplicitN(category.toPlatformPointer(), _message, _filename, lineno, _module, registry.toPlatformPointer())
}
actual fun PyErr_Occurred(): NativePointer? =
    (if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyErr_OccurredF() else python.native.ffi.bindings.PyErr_Occurred()).toNativePointer()
actual inline fun PyErr_ExceptionMatches(exc: NativePointer): Int = python.native.ffi.bindings.PyErr_ExceptionMatchesN(exc.toPlatformPointer())
actual inline fun PyErr_GivenExceptionMatches(given: NativePointer, exc: NativePointer): Int = python.native.ffi.bindings.PyErr_GivenExceptionMatchesN(given.toPlatformPointer(), exc.toPlatformPointer())
actual fun PyErr_GetRaisedException(): NativePointer? = python.native.ffi.bindings.PyErr_GetRaisedExceptionN().toNativePointer()
actual inline fun PyErr_SetRaisedException(exc: NativePointer) = python.native.ffi.bindings.PyErr_SetRaisedExceptionN(exc.toPlatformPointer())
actual inline fun PyErr_Restore(type: NativePointer, value: NativePointer, traceback: NativePointer) = python.native.ffi.bindings.PyErr_RestoreN(type.toPlatformPointer(), value.toPlatformPointer(), traceback.toPlatformPointer())
actual fun PyErr_GetHandledException(): NativePointer? = python.native.ffi.bindings.PyErr_GetHandledExceptionN().toNativePointer()
actual inline fun PyErr_SetHandledException(exc: NativePointer) = python.native.ffi.bindings.PyErr_SetHandledExceptionN(exc.toPlatformPointer())
actual inline fun PyErr_SetExcInfo(type: NativePointer, value: NativePointer, traceback: NativePointer) = python.native.ffi.bindings.PyErr_SetExcInfoN(type.toPlatformPointer(), value.toPlatformPointer(), traceback.toPlatformPointer())
actual inline fun PyErr_CheckSignals(): Int = python.native.ffi.bindings.PyErr_CheckSignalsN()
actual inline fun PyErr_SetInterrupt() = python.native.ffi.bindings.PyErr_SetInterruptN()
actual inline fun PyErr_SetInterruptEx(signum: Int): Int = python.native.ffi.bindings.PyErr_SetInterruptExN(signum)
// `name` is the dotted exception type name -- an identifier. `doc` is the docstring, arbitrary prose.
actual fun PyErr_NewException(name: String, base: NativePointer, dict: NativePointer): NativePointer? =
    python.native.ffi.bindings.PyErr_NewExceptionN(internedUtf8(name), base.toPlatformPointer(), dict.toPlatformPointer()).toNativePointer()
actual fun PyErr_NewExceptionWithDoc(name: String, doc: String, base: NativePointer, dict: NativePointer): NativePointer? {
    val _name = internedUtf8(name)
    val _doc = encodeScratchUtf8(doc)
    return python.native.ffi.bindings.PyErr_NewExceptionWithDocN(_name, _doc, base.toPlatformPointer(), dict.toPlatformPointer()).toNativePointer()
}
actual fun PyException_GetTraceback(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetTracebackN(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetTraceback(ex: NativePointer, tb: NativePointer): Int = python.native.ffi.bindings.PyException_SetTracebackN(ex.toPlatformPointer(), tb.toPlatformPointer())
actual fun PyException_GetContext(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetContextN(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetContext(ex: NativePointer, ctx: NativePointer) = python.native.ffi.bindings.PyException_SetContextN(ex.toPlatformPointer(), ctx.toPlatformPointer())
actual fun PyException_GetCause(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetCauseN(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetCause(ex: NativePointer, cause: NativePointer) = python.native.ffi.bindings.PyException_SetCauseN(ex.toPlatformPointer(), cause.toPlatformPointer())
actual fun PyException_GetArgs(ex: NativePointer): NativePointer? = python.native.ffi.bindings.PyException_GetArgsN(ex.toPlatformPointer()).toNativePointer()
actual inline fun PyException_SetArgs(ex: NativePointer, args: NativePointer) = python.native.ffi.bindings.PyException_SetArgsN(ex.toPlatformPointer(), args.toPlatformPointer())
actual fun PyUnicodeEncodeError_GetEncoding(exc: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicodeEncodeError_GetEncodingN(exc.toPlatformPointer()).toNativePointer()
actual fun PyUnicodeTranslateError_GetObject(exc: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicodeTranslateError_GetObjectN(exc.toPlatformPointer()).toNativePointer()
actual fun PyUnicodeTranslateError_GetReason(exc: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicodeTranslateError_GetReasonN(exc.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicodeTranslateError_SetReason(exc: NativePointer, reason: String): Int =
    python.native.ffi.bindings.PyUnicodeTranslateError_SetReasonN(exc.toPlatformPointer(), encodeScratchUtf8(reason))
actual inline fun Py_EnterRecursiveCall(where: String): Int =
    python.native.ffi.bindings.Py_EnterRecursiveCallN(encodeScratchUtf8(where))
actual inline fun Py_LeaveRecursiveCall() = python.native.ffi.bindings.Py_LeaveRecursiveCallN()
actual inline fun Py_ReprEnter(o: NativePointer): Int = python.native.ffi.bindings.Py_ReprEnterN(o.toPlatformPointer())
actual inline fun Py_ReprLeave(o: NativePointer) = python.native.ffi.bindings.Py_ReprLeaveN(o.toPlatformPointer())


// Section 4
actual fun Py_NewRef(o: NativePointer): NativePointer? = python.native.ffi.bindings.Py_NewRefN(o.toPlatformPointer()).toNativePointer()
actual fun Py_XNewRef(o: NativePointer): NativePointer? = python.native.ffi.bindings.Py_XNewRefN(o.toPlatformPointer()).toNativePointer()
actual inline fun Py_IncRef(o: NativePointer) = python.native.ffi.bindings.Py_IncRefN(o.toPlatformPointer())
actual inline fun Py_DecRef(o: NativePointer) = python.native.ffi.bindings.Py_DecRefN(o.toPlatformPointer())


// Section 5
actual fun PyOS_FSPath(path: NativePointer): NativePointer? = python.native.ffi.bindings.PyOS_FSPathN(path.toPlatformPointer()).toNativePointer()


// Section 6
// sys attribute names ("path", "modules", "stdout") and audit event names are fixed identifiers.
actual fun PySys_GetObject(name: String): NativePointer? =
    python.native.ffi.bindings.PySys_GetObjectN(internedUtf8(name)).toNativePointer()
actual inline fun PySys_SetObject(name: String, v: NativePointer): Int =
    python.native.ffi.bindings.PySys_SetObjectN(internedUtf8(name), v.toPlatformPointer())
actual fun PySys_GetXOptions(): NativePointer? = python.native.ffi.bindings.PySys_GetXOptionsN().toNativePointer()
actual inline fun PySys_AuditTuple(event: String, args: NativePointer): Int =
    python.native.ffi.bindings.PySys_AuditTupleN(internedUtf8(event), args.toPlatformPointer())


// Section 7
// The message is arbitrary and this call never returns, so caching it would be pure waste.
actual inline fun Py_FatalError(message: String) = python.native.ffi.bindings.Py_FatalErrorN(encodeScratchUtf8(message))
actual inline fun Py_Exit(status: Int) = python.native.ffi.bindings.Py_ExitN(status)


// Section 8
actual fun PyImport_ImportModule(name: String): NativePointer? {
    val ptr = internedUtf8(name)
    // Runs the module's top-level code.
    return python.native.ffi.bindings.PyImport_ImportModuleN(ptr).toNativePointer()
}
actual fun PyImport_ImportModuleLevelObject(name: NativePointer, globals: NativePointer, locals: NativePointer, fromlist: NativePointer, level: Int): NativePointer? = python.native.ffi.bindings.PyImport_ImportModuleLevelObjectN(name.toPlatformPointer(), globals.toPlatformPointer(), locals.toPlatformPointer(), fromlist.toPlatformPointer(), level).toNativePointer()
actual fun PyImport_ImportModuleLevel(name: String, globals: NativePointer, locals: NativePointer, fromlist: NativePointer, level: Int): NativePointer? =
    python.native.ffi.bindings.PyImport_ImportModuleLevelN(internedUtf8(name), globals.toPlatformPointer(), locals.toPlatformPointer(), fromlist.toPlatformPointer(), level).toNativePointer()
actual inline fun PyImport_Import(name: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_ImportN(name.toPlatformPointer()).toNativePointer()
actual fun PyImport_ReloadModule(m: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_ReloadModuleN(m.toPlatformPointer()).toNativePointer()
actual fun PyImport_AddModuleRef(name: String): NativePointer? {
    val ptr = internedUtf8(name)
    return python.native.ffi.bindings.PyImport_AddModuleRefN(ptr).toNativePointer()
}
actual fun PyImport_AddModuleObject(name: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_AddModuleObjectN(name.toPlatformPointer()).toNativePointer()
// Borrowed reference, unlike PyImport_AddModuleRef above -- the caller must not release it.
// Module names are repeated literals, so they intern.
actual fun PyImport_AddModule(name: String): NativePointer? {
    val ptr = internedUtf8(name)
    return python.native.ffi.bindings.PyImport_AddModuleN(ptr).toNativePointer()
}
// Module names intern; the file paths they were loaded from do not repeat the same way.
actual fun PyImport_ExecCodeModule(name: String, co: NativePointer): NativePointer? =
    python.native.ffi.bindings.PyImport_ExecCodeModuleN(internedUtf8(name), co.toPlatformPointer()).toNativePointer()
actual fun PyImport_ExecCodeModuleEx(name: String, co: NativePointer, pathname: String): NativePointer? {
    val _name = internedUtf8(name)
    val _pathname = encodeScratchUtf8(pathname)
    return python.native.ffi.bindings.PyImport_ExecCodeModuleExN(_name, co.toPlatformPointer(), _pathname).toNativePointer()
}
actual fun PyImport_ExecCodeModuleObject(name: NativePointer, co: NativePointer, pathname: NativePointer, cpathname: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_ExecCodeModuleObjectN(name.toPlatformPointer(), co.toPlatformPointer(), pathname.toPlatformPointer(), cpathname.toPlatformPointer()).toNativePointer()
// Two scratch encodes live at once here; the per-thread pool holds four, so both stay valid.
actual fun PyImport_ExecCodeModuleWithPathnames(name: String, co: NativePointer, pathname: String, cpathname: String): NativePointer? {
    val _name = internedUtf8(name)
    val _pathname = encodeScratchUtf8(pathname)
    val _cpathname = encodeScratchUtf8(cpathname)
    return python.native.ffi.bindings.PyImport_ExecCodeModuleWithPathnamesN(_name, co.toPlatformPointer(), _pathname, _cpathname).toNativePointer()
}
actual inline fun PyImport_GetMagicTag(): String? {
    // CPython-owned const char*. JVM side must NOT free it.
    val ptr = python.native.ffi.bindings.PyImport_GetMagicTagN()
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
actual fun PyImport_GetModuleDict(): NativePointer? = python.native.ffi.bindings.PyImport_GetModuleDictN().toNativePointer()
actual fun PyImport_GetModule(name: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_GetModuleN(name.toPlatformPointer()).toNativePointer()
actual fun PyImport_GetImporter(path: NativePointer): NativePointer? = python.native.ffi.bindings.PyImport_GetImporterN(path.toPlatformPointer()).toNativePointer()
actual inline fun PyImport_ImportFrozenModuleObject(name: NativePointer): Int = python.native.ffi.bindings.PyImport_ImportFrozenModuleObjectN(name.toPlatformPointer())
actual inline fun PyImport_ImportFrozenModule(name: String): Int =
    python.native.ffi.bindings.PyImport_ImportFrozenModuleN(internedUtf8(name))


// Section 9
actual fun PyEval_GetBuiltins(): NativePointer? = python.native.ffi.bindings.PyEval_GetBuiltinsN().toNativePointer()
actual fun PyEval_GetLocals(): NativePointer? = python.native.ffi.bindings.PyEval_GetLocalsN().toNativePointer()
actual fun PyEval_GetGlobals(): NativePointer? = python.native.ffi.bindings.PyEval_GetGlobalsN().toNativePointer()
actual fun PyEval_GetFrameBuiltins(): NativePointer? = python.native.ffi.bindings.PyEval_GetFrameBuiltinsN().toNativePointer()
actual fun PyEval_GetFrameLocals(): NativePointer? = python.native.ffi.bindings.PyEval_GetFrameLocalsN().toNativePointer()
actual fun PyEval_GetFrameGlobals(): NativePointer? = python.native.ffi.bindings.PyEval_GetFrameGlobalsN().toNativePointer()
actual inline fun PyEval_GetFuncName(func: NativePointer): String? {
    val ptr = python.native.ffi.bindings.PyEval_GetFuncNameN(func.toPlatformPointer())
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
actual inline fun PyEval_GetFuncDesc(func: NativePointer): String? {
    val ptr = python.native.ffi.bindings.PyEval_GetFuncDescN(func.toPlatformPointer())
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}


// Section 10
actual inline fun PyObject_HasAttrWithError(o: NativePointer, attr_name: NativePointer): Int = python.native.ffi.bindings.PyObject_HasAttrWithErrorN(o.toPlatformPointer(), attr_name.toPlatformPointer())
actual inline fun PyObject_HasAttrStringWithError(o: NativePointer, attr_name: String): Int =
    python.native.ffi.bindings.PyObject_HasAttrStringWithErrorN(o.toPlatformPointer(), internedUtf8(attr_name))
actual inline fun PyObject_HasAttr(o: NativePointer, attr_name: NativePointer): Int = python.native.ffi.bindings.PyObject_HasAttrN(o.toPlatformPointer(), attr_name.toPlatformPointer())
actual inline fun PyObject_HasAttrString(o: NativePointer, attr_name: String): Int =
    python.native.ffi.bindings.PyObject_HasAttrStringN(o.toPlatformPointer(), internedUtf8(attr_name))
actual fun PyObject_GetAttr(o: NativePointer, attr_name: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetAttrN(o.toPlatformPointer(), attr_name.toPlatformPointer()).toNativePointer()
actual fun PyObject_GetAttrString(o: NativePointer, attr_name: String): NativePointer? {
    val ptr = internedUtf8(attr_name)
    // Can dispatch to a Python __getattr__ / descriptor.
    return python.native.ffi.bindings.PyObject_GetAttrStringN(o.toPlatformPointer(), ptr).toNativePointer()
}
actual fun PyObject_GenericGetAttr(o: NativePointer, name: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GenericGetAttrN(o.toPlatformPointer(), name.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_SetAttr(o: NativePointer, attr_name: NativePointer, v: NativePointer): Int = python.native.ffi.bindings.PyObject_SetAttrN(o.toPlatformPointer(), attr_name.toPlatformPointer(), v.toPlatformPointer())
actual inline fun PyObject_SetAttrString(o: NativePointer, attr_name: String, v: NativePointer): Int =
    python.native.ffi.bindings.PyObject_SetAttrStringN(o.toPlatformPointer(), internedUtf8(attr_name), v.toPlatformPointer())
actual inline fun PyObject_GenericSetAttr(o: NativePointer, name: NativePointer, value: NativePointer): Int = python.native.ffi.bindings.PyObject_GenericSetAttrN(o.toPlatformPointer(), name.toPlatformPointer(), value.toPlatformPointer())
actual inline fun PyObject_DelAttr(o: NativePointer, attr_name: NativePointer): Int = python.native.ffi.bindings.PyObject_DelAttrN(o.toPlatformPointer(), attr_name.toPlatformPointer())
actual inline fun PyObject_DelAttrString(o: NativePointer, attr_name: String): Int =
    python.native.ffi.bindings.PyObject_DelAttrStringN(o.toPlatformPointer(), internedUtf8(attr_name))
actual fun PyObject_RichCompare(o1: NativePointer, o2: NativePointer, opid: Int): NativePointer? = python.native.ffi.bindings.PyObject_RichCompareN(o1.toPlatformPointer(), o2.toPlatformPointer(), opid).toNativePointer()
actual inline fun PyObject_RichCompareBool(o1: NativePointer, o2: NativePointer, opid: Int): Int = python.native.ffi.bindings.PyObject_RichCompareBoolN(o1.toPlatformPointer(), o2.toPlatformPointer(), opid)
actual fun PyObject_Format(obj: NativePointer, format_spec: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_FormatN(obj.toPlatformPointer(), format_spec.toPlatformPointer()).toNativePointer()
actual fun PyObject_Repr(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_ReprN(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_ASCII(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_ASCIIN(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_Str(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_StrN(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_Bytes(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_BytesN(o.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_IsSubclass(derived: NativePointer, cls: NativePointer): Int = python.native.ffi.bindings.PyObject_IsSubclassN(derived.toPlatformPointer(), cls.toPlatformPointer())
actual inline fun PyObject_IsInstance(inst: NativePointer, cls: NativePointer): Int = python.native.ffi.bindings.PyObject_IsInstanceN(inst.toPlatformPointer(), cls.toPlatformPointer())
actual inline fun PyObject_IsTrue(o: NativePointer): Int = python.native.ffi.bindings.PyObject_IsTrueN(o.toPlatformPointer())
actual inline fun PyObject_Not(o: NativePointer): Int = python.native.ffi.bindings.PyObject_NotN(o.toPlatformPointer())
actual fun PyObject_Type(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_TypeN(o.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_Size(o: NativePointer): Long = python.native.ffi.bindings.PyObject_SizeN(o.toPlatformPointer())
actual inline fun PyObject_Length(o: NativePointer): Long = python.native.ffi.bindings.PyObject_LengthN(o.toPlatformPointer())
actual fun PyObject_GetItem(o: NativePointer, key: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetItemN(o.toPlatformPointer(), key.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_SetItem(o: NativePointer, key: NativePointer, v: NativePointer): Int = python.native.ffi.bindings.PyObject_SetItemN(o.toPlatformPointer(), key.toPlatformPointer(), v.toPlatformPointer())
actual inline fun PyObject_DelItem(o: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyObject_DelItemN(o.toPlatformPointer(), key.toPlatformPointer())
actual fun PyObject_Dir(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_DirN(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_GetIter(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetIterN(o.toPlatformPointer()).toNativePointer()
actual fun PyObject_GetAIter(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_GetAIterN(o.toPlatformPointer()).toNativePointer()


// Section 11
actual fun PyVectorcall_Call(callable: NativePointer, tuple: NativePointer, dict: NativePointer): NativePointer? = python.native.ffi.bindings.PyVectorcall_CallN(callable.toPlatformPointer(), tuple.toPlatformPointer(), dict.toPlatformPointer()).toNativePointer()
actual fun PyObject_Call(callable: NativePointer, args: NativePointer, kwargs: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_CallN(callable.toPlatformPointer(), args.toPlatformPointer(), kwargs.toPlatformPointer()).toNativePointer()
actual fun PyObject_CallNoArgs(callable: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_CallNoArgsN(callable.toPlatformPointer()).toNativePointer()
actual fun PyObject_CallObject(callable: NativePointer, args: NativePointer): NativePointer? = python.native.ffi.bindings.PyObject_CallObjectN(callable.toPlatformPointer(), args.toPlatformPointer()).toNativePointer()
actual inline fun PyCallable_Check(o: NativePointer): Int = python.native.ffi.bindings.PyCallable_CheckN(o.toPlatformPointer())


// Section 12
actual inline fun PyNumber_Check(o: NativePointer): Int = python.native.ffi.bindings.PyNumber_CheckN(o.toPlatformPointer())
actual fun PyNumber_Add(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_AddN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Subtract(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_SubtractN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Multiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_MultiplyN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_MatrixMultiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_MatrixMultiplyN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_FloorDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_FloorDivideN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_TrueDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_TrueDivideN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Remainder(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_RemainderN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Divmod(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_DivmodN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Power(o1: NativePointer, o2: NativePointer, o3: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_PowerN(o1.toPlatformPointer(), o2.toPlatformPointer(), o3.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Negative(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_NegativeN(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Positive(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_PositiveN(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Absolute(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_AbsoluteN(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Invert(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InvertN(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Lshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_LshiftN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Rshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_RshiftN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_And(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_AndN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Xor(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_XorN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Or(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_OrN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceAdd(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceAddN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceSubtract(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceSubtractN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceMultiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceMultiplyN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceMatrixMultiply(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceMatrixMultiplyN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceFloorDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceFloorDivideN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceTrueDivide(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceTrueDivideN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceRemainder(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceRemainderN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlacePower(o1: NativePointer, o2: NativePointer, o3: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlacePowerN(o1.toPlatformPointer(), o2.toPlatformPointer(), o3.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceLshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceLshiftN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceRshift(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceRshiftN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceAnd(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceAndN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceXor(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceXorN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_InPlaceOr(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_InPlaceOrN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Long(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_LongN(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Float(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_FloatN(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_Index(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyNumber_IndexN(o.toPlatformPointer()).toNativePointer()
actual fun PyNumber_ToBase(n: NativePointer, base: Int): NativePointer? = python.native.ffi.bindings.PyNumber_ToBaseN(n.toPlatformPointer(), base).toNativePointer()
actual inline fun PyIndex_Check(o: NativePointer): Int = python.native.ffi.bindings.PyIndex_CheckN(o.toPlatformPointer())


// Section 13
actual inline fun PySequence_Check(o: NativePointer): Int = python.native.ffi.bindings.PySequence_CheckN(o.toPlatformPointer())
actual fun PySequence_Concat(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_ConcatN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual fun PySequence_InPlaceConcat(o1: NativePointer, o2: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_InPlaceConcatN(o1.toPlatformPointer(), o2.toPlatformPointer()).toNativePointer()
actual inline fun PySequence_Contains(o: NativePointer, value: NativePointer): Int = python.native.ffi.bindings.PySequence_ContainsN(o.toPlatformPointer(), value.toPlatformPointer())
actual fun PySequence_List(o: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_ListN(o.toPlatformPointer()).toNativePointer()
actual fun PySequence_Tuple(o: NativePointer): NativePointer? = python.native.ffi.bindings.PySequence_TupleN(o.toPlatformPointer()).toNativePointer()
// `m` is the message raised when `o` is not a sequence -- arbitrary text, not an identifier.
actual fun PySequence_Fast(o: NativePointer, m: String): NativePointer? =
    python.native.ffi.bindings.PySequence_FastN(o.toPlatformPointer(), encodeScratchUtf8(m)).toNativePointer()


// Section 14
actual inline fun PyMapping_Check(o: NativePointer): Int = python.native.ffi.bindings.PyMapping_CheckN(o.toPlatformPointer())
// Mapping keys reached through the *String entry points are the same literals as dict keys and
// attribute names, so they intern -- matching PyDict_*ItemString below.
actual fun PyMapping_GetItemString(o: NativePointer, key: String): NativePointer? =
    python.native.ffi.bindings.PyMapping_GetItemStringN(o.toPlatformPointer(), internedUtf8(key)).toNativePointer()
actual inline fun PyMapping_SetItemString(o: NativePointer, key: String, v: NativePointer): Int =
    python.native.ffi.bindings.PyMapping_SetItemStringN(o.toPlatformPointer(), internedUtf8(key), v.toPlatformPointer())
actual inline fun PyMapping_HasKeyWithError(o: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyMapping_HasKeyWithErrorN(o.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PyMapping_HasKeyStringWithError(o: NativePointer, key: String): Int =
    python.native.ffi.bindings.PyMapping_HasKeyStringWithErrorN(o.toPlatformPointer(), internedUtf8(key))
actual inline fun PyMapping_HasKey(o: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyMapping_HasKeyN(o.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PyMapping_HasKeyString(o: NativePointer, key: String): Int =
    python.native.ffi.bindings.PyMapping_HasKeyStringN(o.toPlatformPointer(), internedUtf8(key))
actual fun PyMapping_Keys(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyMapping_KeysN(o.toPlatformPointer()).toNativePointer()
actual fun PyMapping_Values(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyMapping_ValuesN(o.toPlatformPointer()).toNativePointer()
actual fun PyMapping_Items(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyMapping_ItemsN(o.toPlatformPointer()).toNativePointer()


// Section 15
actual inline fun PyIter_Check(o: NativePointer): Int = python.native.ffi.bindings.PyIter_CheckN(o.toPlatformPointer())
actual inline fun PyAIter_Check(o: NativePointer): Int = python.native.ffi.bindings.PyAIter_CheckN(o.toPlatformPointer())
actual fun PyIter_Next(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyIter_NextN(o.toPlatformPointer()).toNativePointer()


// Section 16
actual fun PyLong_FromLongLong(v: Long): NativePointer? =
    (if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyLong_FromLongLongF(v) else python.native.ffi.bindings.PyLong_FromLongLong(v)).toNativePointer()
actual fun PyLong_FromDouble(v: Double): NativePointer? = python.native.ffi.bindings.PyLong_FromDoubleN(v).toNativePointer()
actual inline fun PyLong_AsInt(obj: NativePointer): Int = python.native.ffi.bindings.PyLong_AsIntN(obj.toPlatformPointer())
actual inline fun PyLong_AsLongLong(obj: NativePointer): Long = python.native.ffi.bindings.PyLong_AsLongLongN(obj.toPlatformPointer())
actual inline fun PyLong_AsDouble(pylong: NativePointer): Double = python.native.ffi.bindings.PyLong_AsDoubleN(pylong.toPlatformPointer())
actual fun PyLong_GetInfo(): NativePointer? = python.native.ffi.bindings.PyLong_GetInfoN().toNativePointer()


// Section 17
actual fun PyBool_FromLong(v: Int): NativePointer? = python.native.ffi.bindings.PyBool_FromLongN(v.toLong()).toNativePointer()


// Section 18
actual fun PyFloat_FromString(str: NativePointer): NativePointer? = python.native.ffi.bindings.PyFloat_FromStringN(str.toPlatformPointer()).toNativePointer()
actual fun PyFloat_FromDouble(v: Double): NativePointer? = python.native.ffi.bindings.PyFloat_FromDoubleN(v).toNativePointer()
actual inline fun PyFloat_AsDouble(pyfloat: NativePointer): Double = python.native.ffi.bindings.PyFloat_AsDoubleN(pyfloat.toPlatformPointer())
actual fun PyFloat_GetInfo(): NativePointer? = python.native.ffi.bindings.PyFloat_GetInfoN().toNativePointer()
actual inline fun PyFloat_GetMax(): Double = python.native.ffi.bindings.PyFloat_GetMaxN()
actual inline fun PyFloat_GetMin(): Double = python.native.ffi.bindings.PyFloat_GetMinN()


// Section 19
// Payload being turned into a bytes object -- user data, never a repeated identifier.
actual fun PyBytes_FromString(v: String): NativePointer? =
    python.native.ffi.bindings.PyBytes_FromStringN(encodeScratchUtf8(v)).toNativePointer()
actual fun PyBytes_FromObject(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyBytes_FromObjectN(o.toPlatformPointer()).toNativePointer()
actual inline fun PyBytes_AsString(o: NativePointer): String? {
    // Points into the bytes object's own buffer; owned by CPython, not freed here.
    val ptr = python.native.ffi.bindings.PyBytes_AsStringN(o.toPlatformPointer())
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}


// Section 20
actual fun PyByteArray_FromObject(o: NativePointer): NativePointer? = python.native.ffi.bindings.PyByteArray_FromObjectN(o.toPlatformPointer()).toNativePointer()
actual fun PyByteArray_Concat(a: NativePointer, b: NativePointer): NativePointer? = python.native.ffi.bindings.PyByteArray_ConcatN(a.toPlatformPointer(), b.toPlatformPointer()).toNativePointer()
actual inline fun PyByteArray_AsString(bytearray: NativePointer): String? {
    val ptr = python.native.ffi.bindings.PyByteArray_AsStringN(bytearray.toPlatformPointer())
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}


// Section 21
actual inline fun PyUnicode_IsIdentifier(unicode: NativePointer): Int = python.native.ffi.bindings.PyUnicode_IsIdentifierN(unicode.toPlatformPointer())
actual fun PyUnicode_FromString(str: String): NativePointer? {
    val ptr = encodeScratchUtf8(str)
    return (if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyUnicode_FromStringF(ptr) else python.native.ffi.bindings.PyUnicode_FromString(ptr)).toNativePointer()
}
actual fun PyUnicode_FromObject(obj: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_FromObjectN(obj.toPlatformPointer()).toNativePointer()
// `encoding` is a codec name from a small fixed set ("utf-8", "latin-1"), so it interns. `errors`
// comes from a fixed set too, but CPython compares it by pointer-free strcmp on every call and it
// is short enough that the scratch path costs nothing -- desktop makes the same split.
actual fun PyUnicode_FromEncodedObject(obj: NativePointer, encoding: String, errors: String): NativePointer? {
    val _encoding = internedUtf8(encoding)
    val _errors = encodeScratchUtf8(errors)
    return python.native.ffi.bindings.PyUnicode_FromEncodedObjectN(obj.toPlatformPointer(), _encoding, _errors).toNativePointer()
}
actual fun PyUnicode_DecodeLocale(str: String, errors: String): NativePointer? {
    val _str = encodeScratchUtf8(str)
    val _errors = encodeScratchUtf8(errors)
    return python.native.ffi.bindings.PyUnicode_DecodeLocaleN(_str, _errors).toNativePointer()
}
actual fun PyUnicode_EncodeLocale(unicode: NativePointer, errors: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_EncodeLocaleN(unicode.toPlatformPointer(), encodeScratchUtf8(errors)).toNativePointer()
actual fun PyUnicode_DecodeFSDefault(str: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_DecodeFSDefaultN(encodeScratchUtf8(str)).toNativePointer()
actual fun PyUnicode_EncodeFSDefault(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_EncodeFSDefaultN(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsEncodedString(unicode: NativePointer, encoding: String, errors: String): NativePointer? {
    val _encoding = internedUtf8(encoding)
    val _errors = encodeScratchUtf8(errors)
    return python.native.ffi.bindings.PyUnicode_AsEncodedStringN(unicode.toPlatformPointer(), _encoding, _errors).toNativePointer()
}
actual fun PyUnicode_AsUTF8String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUTF8StringN(unicode.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? {
    val ptr = if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyUnicode_AsUTF8F(unicode.toPlatformPointer()) else python.native.ffi.bindings.PyUnicode_AsUTF8(unicode.toPlatformPointer())
    return if (ptr != 0L) python.native.ffi.bindings.ffiReadUtf8(ptr) else null
}
actual fun PyUnicode_AsUTF32String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUTF32StringN(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsUTF16String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUTF16StringN(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsUnicodeEscapeString(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsUnicodeEscapeStringN(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsRawUnicodeEscapeString(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsRawUnicodeEscapeStringN(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsLatin1String(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsLatin1StringN(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsASCIIString(unicode: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsASCIIStringN(unicode.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_AsCharmapString(unicode: NativePointer, mapping: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_AsCharmapStringN(unicode.toPlatformPointer(), mapping.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_Translate(unicode: NativePointer, table: NativePointer, errors: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_TranslateN(unicode.toPlatformPointer(), table.toPlatformPointer(), encodeScratchUtf8(errors)).toNativePointer()
actual fun PyUnicode_Concat(left: NativePointer, right: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_ConcatN(left.toPlatformPointer(), right.toPlatformPointer()).toNativePointer()
actual fun PyUnicode_Splitlines(unicode: NativePointer, keepends: Int): NativePointer? = python.native.ffi.bindings.PyUnicode_SplitlinesN(unicode.toPlatformPointer(), keepends).toNativePointer()
actual fun PyUnicode_Join(separator: NativePointer, seq: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_JoinN(separator.toPlatformPointer(), seq.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicode_Compare(left: NativePointer, right: NativePointer): Int = python.native.ffi.bindings.PyUnicode_CompareN(left.toPlatformPointer(), right.toPlatformPointer())
// The comparand is a value being tested, not a key that will be reused; caching it would fill the
// intern table with one-shot entries.
actual inline fun PyUnicode_EqualToUTF8(unicode: NativePointer, string: String): Int =
    python.native.ffi.bindings.PyUnicode_EqualToUTF8N(unicode.toPlatformPointer(), encodeScratchUtf8(string))
actual inline fun PyUnicode_CompareWithASCIIString(unicode: NativePointer, string: String): Int =
    python.native.ffi.bindings.PyUnicode_CompareWithASCIIStringN(unicode.toPlatformPointer(), encodeScratchUtf8(string))
actual fun PyUnicode_RichCompare(left: NativePointer, right: NativePointer, op: Int): NativePointer? = python.native.ffi.bindings.PyUnicode_RichCompareN(left.toPlatformPointer(), right.toPlatformPointer(), op).toNativePointer()
actual fun PyUnicode_Format(format: NativePointer, args: NativePointer): NativePointer? = python.native.ffi.bindings.PyUnicode_FormatN(format.toPlatformPointer(), args.toPlatformPointer()).toNativePointer()
actual inline fun PyUnicode_Contains(unicode: NativePointer, substr: NativePointer): Int = python.native.ffi.bindings.PyUnicode_ContainsN(unicode.toPlatformPointer(), substr.toPlatformPointer())
// The caller is asking CPython to intern this string, which is a statement that it repeats.
actual fun PyUnicode_InternFromString(str: String): NativePointer? =
    python.native.ffi.bindings.PyUnicode_InternFromStringN(internedUtf8(str)).toNativePointer()


// Section 22
actual fun PyList_New(len: Long): NativePointer? = python.native.ffi.bindings.PyList_NewN(len).toNativePointer()
actual inline fun PyList_Size(list: NativePointer): Long =
    if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyList_SizeF(list.toPlatformPointer())
    else python.native.ffi.bindings.PyList_Size(list.toPlatformPointer())
// Leaf on the success path, but the per-element call of bulk iteration, so the convention is the
// whole cost. Follows the same device axis as PyList_Size above rather than pinning @CriticalNative.
actual fun PyList_GetItem(list: NativePointer, index: Long): NativePointer? =
    (if (python.native.ffi.bindings.preferFastNative) python.native.ffi.bindings.PyList_GetItemRawF(list.toPlatformPointer(), index)
     else python.native.ffi.bindings.PyList_GetItemRaw(list.toPlatformPointer(), index)).toNativePointer()
actual inline fun PyList_SetItem(list: NativePointer, index: Long, item: NativePointer): Int = python.native.ffi.bindings.PyList_SetItemN(list.toPlatformPointer(), index, item.toPlatformPointer())
actual inline fun PyList_Insert(list: NativePointer, index: Long, item: NativePointer): Int = python.native.ffi.bindings.PyList_InsertN(list.toPlatformPointer(), index, item.toPlatformPointer())
actual inline fun PyList_Append(list: NativePointer, item: NativePointer): Int = python.native.ffi.bindings.PyList_AppendN(list.toPlatformPointer(), item.toPlatformPointer())
actual inline fun PyList_Sort(list: NativePointer): Int = python.native.ffi.bindings.PyList_SortN(list.toPlatformPointer())
actual inline fun PyList_Reverse(list: NativePointer): Int = python.native.ffi.bindings.PyList_ReverseN(list.toPlatformPointer())
actual fun PyList_AsTuple(list: NativePointer): NativePointer? = python.native.ffi.bindings.PyList_AsTupleN(list.toPlatformPointer()).toNativePointer()


// Section 23
actual fun PyDict_New(): NativePointer? = python.native.ffi.bindings.PyDict_NewN().toNativePointer()
actual inline fun PyDict_Size(p: NativePointer): Long = python.native.ffi.bindings.PyDict_SizeN(p.toPlatformPointer())
actual fun PyDictProxy_New(mapping: NativePointer): NativePointer? = python.native.ffi.bindings.PyDictProxy_NewN(mapping.toPlatformPointer()).toNativePointer()
actual inline fun PyDict_Clear(p: NativePointer) = python.native.ffi.bindings.PyDict_ClearN(p.toPlatformPointer())
actual inline fun PyDict_Contains(p: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyDict_ContainsN(p.toPlatformPointer(), key.toPlatformPointer())
actual fun PyDict_Copy(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_CopyN(p.toPlatformPointer()).toNativePointer()
actual inline fun PyDict_SetItem(p: NativePointer, key: NativePointer, v: NativePointer): Int = python.native.ffi.bindings.PyDict_SetItemN(p.toPlatformPointer(), key.toPlatformPointer(), v.toPlatformPointer())
actual inline fun PyDict_SetItemString(p: NativePointer, key: String, v: NativePointer): Int { val ptr = internedUtf8(key); return python.native.ffi.bindings.PyDict_SetItemStringN(p.toPlatformPointer(), ptr, v.toPlatformPointer()) }
actual inline fun PyDict_DelItem(p: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PyDict_DelItemN(p.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PyDict_DelItemString(p: NativePointer, key: String): Int =
    python.native.ffi.bindings.PyDict_DelItemStringN(p.toPlatformPointer(), internedUtf8(key))
actual fun PyDict_GetItem(p: NativePointer, key: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_GetItemN(p.toPlatformPointer(), key.toPlatformPointer()).toNativePointer()
actual fun PyDict_GetItemWithError(p: NativePointer, key: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_GetItemWithErrorN(p.toPlatformPointer(), key.toPlatformPointer()).toNativePointer()
actual fun PyDict_GetItemString(p: NativePointer, key: String): NativePointer? {
    val ptr = internedUtf8(key)
    return python.native.ffi.bindings.PyDict_GetItemStringN(p.toPlatformPointer(), ptr).toNativePointer()
}
actual fun PyDict_Items(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_ItemsN(p.toPlatformPointer()).toNativePointer()
actual fun PyDict_Keys(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_KeysN(p.toPlatformPointer()).toNativePointer()
actual fun PyDict_Values(p: NativePointer): NativePointer? = python.native.ffi.bindings.PyDict_ValuesN(p.toPlatformPointer()).toNativePointer()
actual inline fun PyDict_Merge(a: NativePointer, b: NativePointer, override: Int): Int = python.native.ffi.bindings.PyDict_MergeN(a.toPlatformPointer(), b.toPlatformPointer(), override)
actual inline fun PyDict_Update(a: NativePointer, b: NativePointer): Int = python.native.ffi.bindings.PyDict_UpdateN(a.toPlatformPointer(), b.toPlatformPointer())
actual inline fun PyDict_MergeFromSeq2(a: NativePointer, seq2: NativePointer, override: Int): Int = python.native.ffi.bindings.PyDict_MergeFromSeq2N(a.toPlatformPointer(), seq2.toPlatformPointer(), override)


// Section 24
actual fun PySet_New(iterable: NativePointer): NativePointer? = python.native.ffi.bindings.PySet_NewN(iterable.toPlatformPointer()).toNativePointer()
actual fun PyFrozenSet_New(iterable: NativePointer): NativePointer? = python.native.ffi.bindings.PyFrozenSet_NewN(iterable.toPlatformPointer()).toNativePointer()
actual inline fun PySet_Contains(anyset: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PySet_ContainsN(anyset.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PySet_Size(anyset: NativePointer): Long = python.native.ffi.bindings.PySet_SizeN(anyset.toPlatformPointer())
actual inline fun PySet_Add(set: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PySet_AddN(set.toPlatformPointer(), key.toPlatformPointer())
actual inline fun PySet_Discard(set: NativePointer, key: NativePointer): Int = python.native.ffi.bindings.PySet_DiscardN(set.toPlatformPointer(), key.toPlatformPointer())
actual fun PySet_Pop(set: NativePointer): NativePointer? = python.native.ffi.bindings.PySet_PopN(set.toPlatformPointer()).toNativePointer()
actual inline fun PySet_Clear(set: NativePointer): Int = python.native.ffi.bindings.PySet_ClearN(set.toPlatformPointer())


// Section 25
actual fun PySeqIter_New(seq: NativePointer): NativePointer? = python.native.ffi.bindings.PySeqIter_NewN(seq.toPlatformPointer()).toNativePointer()
actual fun PyCallIter_New(callable: NativePointer, sentinel: NativePointer): NativePointer? = python.native.ffi.bindings.PyCallIter_NewN(callable.toPlatformPointer(), sentinel.toPlatformPointer()).toNativePointer()


// Section 26
actual fun PyWeakref_NewRef(ob: NativePointer, callback: NativePointer): NativePointer? = python.native.ffi.bindings.PyWeakref_NewRefN(ob.toPlatformPointer(), callback.toPlatformPointer()).toNativePointer()
actual fun PyWeakref_NewProxy(ob: NativePointer, callback: NativePointer): NativePointer? = python.native.ffi.bindings.PyWeakref_NewProxyN(ob.toPlatformPointer(), callback.toPlatformPointer()).toNativePointer()
actual fun PyWeakref_GetRef(ref: NativePointer): NativePointer? = python.native.ffi.bindings.PyWeakref_GetRefN(ref.toPlatformPointer()).toNativePointer()
actual inline fun PyObject_ClearWeakRefs(o: NativePointer) = python.native.ffi.bindings.PyObject_ClearWeakRefsN(o.toPlatformPointer())


// Section 27
actual inline fun PyType_IsSubtype(a: NativePointer, b: NativePointer): Int = python.native.ffi.bindings.PyType_IsSubtypeN(a.toPlatformPointer(), b.toPlatformPointer())
actual inline fun PyType_Ready(type: NativePointer): Int = python.native.ffi.bindings.PyType_ReadyN(type.toPlatformPointer())
actual fun PyType_GetName(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetNameN(type.toPlatformPointer()).toNativePointer()
actual fun PyType_GetFullyQualifiedName(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetFullyQualifiedNameN(type.toPlatformPointer()).toNativePointer()
actual fun PyType_GetModuleName(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetModuleNameN(type.toPlatformPointer()).toNativePointer()
actual fun PyType_GetModule(type: NativePointer): NativePointer? = python.native.ffi.bindings.PyType_GetModuleN(type.toPlatformPointer()).toNativePointer()


// Section 28
actual fun PyTuple_New(len: Long): NativePointer? = python.native.ffi.bindings.PyTuple_NewN(len).toNativePointer()
actual inline fun PyTuple_Size(p: NativePointer): Long = python.native.ffi.bindings.PyTuple_SizeN(p.toPlatformPointer())
actual fun PyTuple_GetItem(p: NativePointer, pos: Long): NativePointer? = python.native.ffi.bindings.PyTuple_GetItemN(p.toPlatformPointer(), pos).toNativePointer()
actual fun PyTuple_GetSlice(p: NativePointer, low: Long, high: Long): NativePointer? = python.native.ffi.bindings.PyTuple_GetSliceN(p.toPlatformPointer(), low, high).toNativePointer()
actual inline fun PyTuple_SetItem(p: NativePointer, pos: Long, o: NativePointer): Int = python.native.ffi.bindings.PyTuple_SetItemN(p.toPlatformPointer(), pos, o.toPlatformPointer())


// Section 29
actual inline fun PyModule_GetName(module: NativePointer): String? = python.native.ffi.bindings.ffiReadUtf8(python.native.ffi.bindings.PyModule_GetNameN(module.toPlatformPointer()))
actual fun PyModule_GetDict(module: NativePointer): NativePointer? = python.native.ffi.bindings.PyModule_GetDictN(module.toPlatformPointer()).toNativePointer()
actual fun PyModule_GetFilenameObject(module: NativePointer): NativePointer? = python.native.ffi.bindings.PyModule_GetFilenameObjectN(module.toPlatformPointer()).toNativePointer()
