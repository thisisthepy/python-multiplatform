package python.native.ffi

import python.native.ffi.manager.loadLibPython

typealias JNIPointer = Long


object bindings {
    init {
        loadLibPython()
    }

    /**
    external fun Py_Initialize()
    external fun Py_InitializeEx(sigint: Int)
    //external fun Py_InitializeFromConfig()
    external fun Py_IsInitialized(): Int
    external fun Py_Finalize()
    external fun Py_FinalizeEx(): Int
    external fun Py_IsFinalizing(): Int
    external fun Py_BytesMain(args: Array<String>): Int
    external fun Py_RunMain(): Int
    external fun PyRun_SimpleString(command: String): Int
    // external fun PyRun_String(str: String, start: Int, globals: JNIPointer, locals: JNIPointer): JNIPointer?
    external fun Py_GetVersion(): String?
    external fun Py_GetPlatform(): String?
    external fun Py_GetCopyright(): String?
    external fun Py_GetCompiler(): String?
    external fun Py_GetBuildInfo(): String?



    external fun PyErr_Occurred(): JNIPointer?


    external fun PyLong_FromLongLong(v: Long): JNIPointer?
    external fun PyLong_AsLongLong(p: JNIPointer): Long
    external fun PyLong_AsInt(p: JNIPointer): Int

    // external fun PyUnicode_FromString(str: String): JNIPointer?
    // external fun PyUnicode_AsUTF8(unicode: JNIPointer): String?
    */


    //**************************************************
    // Section 1
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_Initialize()
    @JvmStatic external fun Py_InitializeExN(initsigs: Int)
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echo0(x: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echo1(x: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echo2(x: Long): Long

    // Same C body as echo0, bound under the other two conventions so a benchmark can
    // isolate the transition cost. See jni_onload.def.
    @JvmStatic
    external fun echoNormal(x: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.FastNative
    external fun echoFast(x: Long): Long

    // @CriticalNative reached by name-based linking, NOT via RegisterNatives -- see
    // JNIOnLoadExporter.echoCriticalNamed. Isolates the binding method from the convention.
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun echoCriticalNamed(x: Long): Long

    // The same real CPython function (PyList_Size) under the other two conventions, so the
    // comparison can be made on a call that does actual work rather than on an empty echo.
    @JvmStatic
    @dalvik.annotation.optimization.FastNative
    external fun PyList_SizeFast(list: Long): Long
    @JvmStatic
    external fun PyList_SizeNormal(list: Long): Long

    // ---- @FastNative twins of the migrated functions ----
    //
    // ART fast-paths @CriticalNative up to API 33 and @FastNative from 34 onward; each is
    // roughly 20x the other on the wrong side of that line (docs/design/downcall-design.md). Both
    // are declared so EmbedAPI.android.kt can select per device. @FastNative still receives
    // JNIEnv and jclass, hence the separate C wrappers in jni_onload.def.
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_InitializeF()
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_IsInitializedF(): Int
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_FinalizeF()
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyErr_ClearF()
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyLong_FromLongLongF(v: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyList_SizeF(list: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyRun_SimpleStringF(command: Long): Int
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun Py_GetVersionF(): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyImport_ImportModuleF(name: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyObject_GetAttrStringF(o: Long, name: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyErr_OccurredF(): Long

    // ---- Ordinary JNI, for calls that must NOT block GC or forbid JVM re-entry ----
    //
    // @CriticalNative and @FastNative both stop the collector for the duration of the call,
    // and @CriticalNative additionally has no JNIEnv, so nothing under it can re-enter the
    // runtime. These functions can execute arbitrary Python -- module top-level code, a
    // __getattr__, a __del__ reached by dropping the last reference, interpreter start-up and
    // shutdown -- which is unbounded in time and, once Kotlin callables are exposed to
    // Python, re-enters the JVM. They stay on the ordinary path at every API level.
    @JvmStatic external fun Py_InitializeN()
    @JvmStatic external fun Py_FinalizeN()
    @JvmStatic external fun PyErr_ClearN()
    @JvmStatic external fun PyRun_SimpleStringN(command: Long): Int
    @JvmStatic external fun PyImport_ImportModuleN(name: Long): Long
    @JvmStatic external fun PyImport_ImportN(name: Long): Long
    @JvmStatic external fun PyObject_GetAttrStringN(o: Long, name: Long): Long

    // ---- Composition probes: the whole binder operation in one crossing ----
    // asmGetAttr replaces ffiAllocUtf8 + PyObject_GetAttrString + PyErr_Clear + ffiFreeUtf8.
    // asmListToArray replaces PyList_Size + N x PyList_GetItem, filling the caller's array
    // and returning how many elements it wrote.
    // Borrowed reference, leaf: pure index into the list's item array.
    //
    // Both conventions, selected by `preferFastNative` like every other pair here. This one was
    // @CriticalNative-only until the audit follow-up, which made it the single declaration that
    // ignored the device axis -- and it is the per-element call of bulk list iteration, so on API
    // 34+ it was paying 24-44 ns per element where @FastNative pays 2-4. See docs/design/downcall-design.md
    // and docs/investigations/jni-call-convention-audit.md.
    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyList_GetItemRaw(list: Long, i: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyList_GetItemRawF(list: Long, i: Long): Long
    // Python3.exec composed into one crossing -- see jni_onload.def.
    // Address of a DirectByteBuffer's off-heap storage. Called once per buffer, not per
    // string -- see jni_onload.def.
    @JvmStatic external fun ffiDirectBufferAddress(buf: java.nio.ByteBuffer): Long
    @JvmStatic external fun asmExec(code: String): Int
    @JvmStatic external fun asmGetAttr(obj: Long, name: String): Long
    @JvmStatic external fun asmListToArray(list: Long, out: LongArray): Int

    @JvmStatic external fun testUpcallPrimitive(x: Long): Long
    @JvmStatic external fun testUpcallString(s: String): Long
    @JvmStatic external fun testUpcallUnattached(x: Long): Long
    @JvmStatic external fun testThreadCreateFloor(x: Long): Long

    /**
     * Attaches a bare pthread as a daemon, reads back the `java.lang.Thread` id it was given, and
     * lets the pthread exit **without** detaching. Returns that id, or -1.
     *
     * The evidence `pmp_attach` rests on when it keeps an attachment rather than dropping it per
     * call: whether ART aborts for this is the fact the whole choice turns on, and it is observed
     * here rather than read off `Thread::ThreadExitCallback`. See
     * `UpcallThreadAttachTest.aThreadThatExitsWithoutDetachingLeaksItsPeerRatherThanAbortingArt`.
     */
    @JvmStatic external fun testAttachWithoutDetach(x: Long): Long

    /**
     * Which JNI calling convention this device fast-paths.
     *
     * Measured, not guessed: @CriticalNative is at or below the timing floor from API 26
     * through 33 and costs ~24ns on 34 and ~44ns on API 36 hardware, while @FastNative is
     * ~24-39ns through API 31 and ~2ns from 33 on. Both are cheap at 33, so the threshold is
     * placed where being off by one costs the least. See docs/design/downcall-design.md.
     *
     * Read once into a static final so the JIT folds the branch at each call site.
     */
    @JvmStatic
    val preferFastNative: Boolean =
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_IsInitialized(): Int
    @JvmStatic external fun Py_IsFinalizingN(): Int
    @JvmStatic external fun Py_FinalizeExN(): Int
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_Finalize()
    // external fun Py_BytesMain(args: Array<String>): Int // 수동 추가
    @JvmStatic external fun Py_RunMainN(): Int
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun Py_GetVersion(): Long
    @JvmStatic external fun Py_GetPlatformN(): Long
    @JvmStatic external fun Py_GetCopyrightN(): Long
    @JvmStatic external fun Py_GetCompilerN(): Long
    @JvmStatic external fun Py_GetBuildInfoN(): Long
    // external fun PyEval_InitThreads()
    // external fun PyThreadState_GetDict(): JNIPointer?

    // external fun PyGILState_Ensure(): Int
    // external fun PyGILState_Release(state: Int)
    // external fun PyGILState_GetThisThreadState(): JNIPointer?
    // external fun PyEval_SaveThread(): JNIPointer?
    // external fun PyEval_RestoreThread(tstate: JNIPointer)


    // Section 2
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyRun_SimpleString(command: Long): Int // 수동 추가
    // external fun PyRun_String(str: String, start: Int, globals: JNIPointer, locals: JNIPointer): JNIPointer? // 수동 추가
    // `str` is arbitrary source text and `filename` a repeated identifier, so the two arguments
    // take different marshalling paths on the Kotlin side -- see EmbedAPI.android.kt.
    @JvmStatic external fun Py_CompileStringN(str: Long, filename: Long, start: Int): Long
    @JvmStatic external fun PyEval_EvalCodeN(co: Long, globals: Long, locals: Long): Long


    // Section 3
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyErr_Clear()
    @JvmStatic external fun PyErr_PrintExN(set_sys_last_vars: Int)
    @JvmStatic external fun PyErr_PrintN()
    @JvmStatic external fun PyErr_WriteUnraisableN(obj: Long)
    @JvmStatic external fun PyErr_DisplayExceptionN(exc: Long)
    // PyErr_SetString is bound as PyErr_SetStringN below; the String-taking declaration is gone
    // deliberately, because reaching it would fall back to the name-linked @CName export (ROADMAP §2).
    @JvmStatic external fun PyErr_SetObjectN(type: Long, value: Long)
    @JvmStatic external fun PyErr_SetNoneN(type: Long)
    @JvmStatic external fun PyErr_BadArgumentN(): Int
    @JvmStatic external fun PyErr_NoMemoryN(): Long
    @JvmStatic external fun PyErr_SetFromErrnoN(type: Long): Long
    @JvmStatic external fun PyErr_SetFromErrnoWithFilenameObjectN(type: Long, filenameObject: Long): Long
    @JvmStatic external fun PyErr_SetFromErrnoWithFilenameObjectsN(type: Long, filenameObject: Long, filenameObject2: Long): Long
    @JvmStatic external fun PyErr_SetFromErrnoWithFilenameN(type: Long, filename: Long): Long
    @JvmStatic external fun PyErr_SetImportErrorN(msg: Long, name: Long, path: Long): Long
    @JvmStatic external fun PyErr_SetImportErrorSubclassN(exception: Long, msg: Long, name: Long, path: Long): Long
    @JvmStatic external fun PyErr_SyntaxLocationExN(filename: Long, lineno: Int, col_offset: Int)
    @JvmStatic external fun PyErr_SyntaxLocationN(filename: Long, lineno: Int)
    @JvmStatic external fun PyErr_BadInternalCallN()
    @JvmStatic external fun PyErr_WarnExplicitN(category: Long, message: Long, filename: Long, lineno: Int, module: Long, registry: Long): Int
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyErr_Occurred(): Long
    @JvmStatic external fun PyErr_ExceptionMatchesN(exc: Long): Int
    @JvmStatic external fun PyErr_GivenExceptionMatchesN(given: Long, exc: Long): Int
    @JvmStatic external fun PyErr_GetRaisedExceptionN(): Long
    @JvmStatic external fun PyErr_SetRaisedExceptionN(exc: Long)
    @JvmStatic external fun PyErr_RestoreN(type: Long, value: Long, traceback: Long)
    @JvmStatic external fun PyErr_GetHandledExceptionN(): Long
    @JvmStatic external fun PyErr_SetHandledExceptionN(exc: Long)
    @JvmStatic external fun PyErr_SetExcInfoN(type: Long, value: Long, traceback: Long)
    @JvmStatic external fun PyErr_CheckSignalsN(): Int
    @JvmStatic external fun PyErr_SetInterruptN()
    @JvmStatic external fun PyErr_SetInterruptExN(signum: Int): Int
    @JvmStatic external fun PyErr_NewExceptionN(name: Long, base: Long, dict: Long): Long
    @JvmStatic external fun PyErr_NewExceptionWithDocN(name: Long, doc: Long, base: Long, dict: Long): Long
    @JvmStatic external fun PyException_GetTracebackN(ex: Long): Long
    @JvmStatic external fun PyException_SetTracebackN(ex: Long, tb: Long): Int
    @JvmStatic external fun PyException_GetContextN(ex: Long): Long
    @JvmStatic external fun PyException_SetContextN(ex: Long, ctx: Long)
    @JvmStatic external fun PyException_GetCauseN(ex: Long): Long
    @JvmStatic external fun PyException_SetCauseN(ex: Long, cause: Long)
    @JvmStatic external fun PyException_GetArgsN(ex: Long): Long
    @JvmStatic external fun PyException_SetArgsN(ex: Long, args: Long)
    @JvmStatic external fun PyUnicodeEncodeError_GetEncodingN(exc: Long): Long
    @JvmStatic external fun PyUnicodeTranslateError_GetObjectN(exc: Long): Long
    @JvmStatic external fun PyUnicodeTranslateError_GetReasonN(exc: Long): Long
    @JvmStatic external fun PyUnicodeTranslateError_SetReasonN(exc: Long, reason: Long): Int
    @JvmStatic external fun Py_EnterRecursiveCallN(where: Long): Int
    @JvmStatic external fun Py_LeaveRecursiveCallN()
    @JvmStatic external fun Py_ReprEnterN(o: Long): Int
    @JvmStatic external fun Py_ReprLeaveN(o: Long)


    // Section 4
    // external fun Py_NewRef(o: JNIPointer): JNIPointer?
    // external fun Py_XNewRef(o: JNIPointer): JNIPointer?
    // external fun Py_IncRef(o: JNIPointer)
    // external fun Py_DecRef(o: JNIPointer)


    // Section 5
    @JvmStatic external fun PyOS_FSPathN(path: Long): Long


    // Section 6
    @JvmStatic external fun PySys_GetObjectN(name: Long): Long
    @JvmStatic external fun PySys_SetObjectN(name: Long, v: Long): Int
    @JvmStatic external fun PySys_GetXOptionsN(): Long
    @JvmStatic external fun PySys_AuditTupleN(event: Long, args: Long): Int


    // Section 7
    @JvmStatic external fun Py_FatalErrorN(message: Long)
    @JvmStatic external fun Py_ExitN(status: Int)


    // Section 8
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyImport_ImportModule(name: Long): Long
    @JvmStatic external fun PyImport_ImportModuleLevelObjectN(name: Long, globals: Long, locals: Long, fromlist: Long, level: Int): Long
    @JvmStatic external fun PyImport_ImportModuleLevelN(name: Long, globals: Long, locals: Long, fromlist: Long, level: Int): Long
    // external fun PyImport_Import(name: JNIPointer): JNIPointer?
    @JvmStatic external fun PyImport_ReloadModuleN(m: Long): Long
    // external fun PyImport_AddModuleRef(name: String): JNIPointer?
    @JvmStatic external fun PyImport_AddModuleObjectN(name: Long): Long
    // PyImport_AddModule is bound as PyImport_AddModuleN below.
    @JvmStatic external fun PyImport_ExecCodeModuleN(name: Long, co: Long): Long
    @JvmStatic external fun PyImport_ExecCodeModuleExN(name: Long, co: Long, pathname: Long): Long
    @JvmStatic external fun PyImport_ExecCodeModuleObjectN(name: Long, co: Long, pathname: Long, cpathname: Long): Long
    @JvmStatic external fun PyImport_ExecCodeModuleWithPathnamesN(name: Long, co: Long, pathname: Long, cpathname: Long): Long
    // CPython-owned const char*; the JVM side reads it with ffiReadUtf8 and must not free it.
    @JvmStatic external fun PyImport_GetMagicTagN(): Long
    @JvmStatic external fun PyImport_GetModuleDictN(): Long
    @JvmStatic external fun PyImport_GetModuleN(name: Long): Long
    @JvmStatic external fun PyImport_GetImporterN(path: Long): Long
    @JvmStatic external fun PyImport_ImportFrozenModuleObjectN(name: Long): Int
    @JvmStatic external fun PyImport_ImportFrozenModuleN(name: Long): Int


    // Section 9
    @JvmStatic external fun PyEval_GetBuiltinsN(): Long
    @JvmStatic external fun PyEval_GetLocalsN(): Long
    @JvmStatic external fun PyEval_GetGlobalsN(): Long
    @JvmStatic external fun PyEval_GetFrameBuiltinsN(): Long
    @JvmStatic external fun PyEval_GetFrameLocalsN(): Long
    @JvmStatic external fun PyEval_GetFrameGlobalsN(): Long
    // Both return a CPython-owned const char*; read with ffiReadUtf8, never freed here.
    @JvmStatic external fun PyEval_GetFuncNameN(func: Long): Long
    @JvmStatic external fun PyEval_GetFuncDescN(func: Long): Long


    // Section 10
    @JvmStatic external fun PyObject_HasAttrWithErrorN(o: Long, attr_name: Long): Int
    @JvmStatic external fun PyObject_HasAttrStringWithErrorN(o: Long, attr_name: Long): Int
    @JvmStatic external fun PyObject_HasAttrN(o: Long, attr_name: Long): Int
    @JvmStatic external fun PyObject_HasAttrStringN(o: Long, attr_name: Long): Int
    @JvmStatic external fun PyObject_GetAttrN(o: Long, attr_name: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyObject_GetAttrString(o: Long, attr_name: Long): Long
    @JvmStatic external fun PyObject_GenericGetAttrN(o: Long, name: Long): Long
    @JvmStatic external fun PyObject_SetAttrN(o: Long, attr_name: Long, v: Long): Int
    // PyObject_SetAttrString / PyObject_DelAttrString are bound as the *N variants below.
    @JvmStatic external fun PyObject_GenericSetAttrN(o: Long, name: Long, value: Long): Int
    @JvmStatic external fun PyObject_DelAttrN(o: Long, attr_name: Long): Int
    @JvmStatic external fun PyObject_RichCompareN(o1: Long, o2: Long, opid: Int): Long
    @JvmStatic external fun PyObject_RichCompareBoolN(o1: Long, o2: Long, opid: Int): Int
    @JvmStatic external fun PyObject_FormatN(obj: Long, format_spec: Long): Long
    @JvmStatic external fun PyObject_ReprN(o: Long): Long
    @JvmStatic external fun PyObject_ASCIIN(o: Long): Long
    // external fun PyObject_Str(o: JNIPointer): JNIPointer?
    @JvmStatic external fun PyObject_BytesN(o: Long): Long
    @JvmStatic external fun PyObject_IsSubclassN(derived: Long, cls: Long): Int
    @JvmStatic external fun PyObject_IsInstanceN(inst: Long, cls: Long): Int
    // external fun PyObject_IsTrue(o: JNIPointer): Int
    @JvmStatic external fun PyObject_NotN(o: Long): Int
    @JvmStatic external fun PyObject_TypeN(o: Long): Long
    @JvmStatic external fun PyObject_SizeN(o: Long): Long
    @JvmStatic external fun PyObject_LengthN(o: Long): Long
    @JvmStatic external fun PyObject_GetItemN(o: Long, key: Long): Long
    @JvmStatic external fun PyObject_SetItemN(o: Long, key: Long, v: Long): Int
    @JvmStatic external fun PyObject_DelItemN(o: Long, key: Long): Int
    @JvmStatic external fun PyObject_DirN(o: Long): Long
    @JvmStatic external fun PyObject_GetIterN(o: Long): Long
    @JvmStatic external fun PyObject_GetAIterN(o: Long): Long


    // Section 11
    @JvmStatic external fun PyVectorcall_CallN(callable: Long, tuple: Long, dict: Long): Long
    // A second, name-linked `PyObject_Call` declaration used to sit next to PyObject_CallN with no
    // call site. Removed rather than registered: an unregistered declaration is the landmine, and
    // a third binding for a function that already has one buys nothing.
    @JvmStatic external fun PyObject_CallN(callable: Long, args: Long, kwargs: Long): Long
    // external fun PyObject_CallNoArgs(callable: JNIPointer): JNIPointer?
    // external fun PyObject_CallObject(callable: JNIPointer, args: JNIPointer): JNIPointer?
    @JvmStatic external fun PyCallable_CheckN(o: Long): Int


    // Section 12
    @JvmStatic external fun PyNumber_CheckN(o: Long): Int
    @JvmStatic external fun PyNumber_AddN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_SubtractN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_MultiplyN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_MatrixMultiplyN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_FloorDivideN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_TrueDivideN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_RemainderN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_DivmodN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_PowerN(o1: Long, o2: Long, o3: Long): Long
    @JvmStatic external fun PyNumber_NegativeN(o: Long): Long
    @JvmStatic external fun PyNumber_PositiveN(o: Long): Long
    @JvmStatic external fun PyNumber_AbsoluteN(o: Long): Long
    @JvmStatic external fun PyNumber_InvertN(o: Long): Long
    @JvmStatic external fun PyNumber_LshiftN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_RshiftN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_AndN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_XorN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_OrN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceAddN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceSubtractN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceMultiplyN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceMatrixMultiplyN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceFloorDivideN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceTrueDivideN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceRemainderN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlacePowerN(o1: Long, o2: Long, o3: Long): Long
    @JvmStatic external fun PyNumber_InPlaceLshiftN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceRshiftN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceAndN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceXorN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_InPlaceOrN(o1: Long, o2: Long): Long
    @JvmStatic external fun PyNumber_LongN(o: Long): Long
    @JvmStatic external fun PyNumber_FloatN(o: Long): Long
    @JvmStatic external fun PyNumber_IndexN(o: Long): Long
    @JvmStatic external fun PyNumber_ToBaseN(n: Long, base: Int): Long
    @JvmStatic external fun PyIndex_CheckN(o: Long): Int


    // Section 13
    @JvmStatic external fun PySequence_CheckN(o: Long): Int
    @JvmStatic external fun PySequence_ConcatN(o1: Long, o2: Long): Long
    @JvmStatic external fun PySequence_InPlaceConcatN(o1: Long, o2: Long): Long
    @JvmStatic external fun PySequence_ContainsN(o: Long, value: Long): Int
    @JvmStatic external fun PySequence_ListN(o: Long): Long
    @JvmStatic external fun PySequence_TupleN(o: Long): Long
    @JvmStatic external fun PySequence_FastN(o: Long, m: Long): Long


    // Section 14
    @JvmStatic external fun PyMapping_CheckN(o: Long): Int
    @JvmStatic external fun PyMapping_GetItemStringN(o: Long, key: Long): Long
    @JvmStatic external fun PyMapping_SetItemStringN(o: Long, key: Long, v: Long): Int
    @JvmStatic external fun PyMapping_HasKeyWithErrorN(o: Long, key: Long): Int
    @JvmStatic external fun PyMapping_HasKeyStringWithErrorN(o: Long, key: Long): Int
    @JvmStatic external fun PyMapping_HasKeyN(o: Long, key: Long): Int
    @JvmStatic external fun PyMapping_HasKeyStringN(o: Long, key: Long): Int
    @JvmStatic external fun PyMapping_KeysN(o: Long): Long
    @JvmStatic external fun PyMapping_ValuesN(o: Long): Long
    @JvmStatic external fun PyMapping_ItemsN(o: Long): Long


    // Section 15
    @JvmStatic external fun PyIter_CheckN(o: Long): Int
    @JvmStatic external fun PyAIter_CheckN(o: Long): Int
    @JvmStatic external fun PyIter_NextN(o: Long): Long


    // Section 16
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyLong_FromLongLong(v: Long): Long
    @JvmStatic external fun PyLong_FromDoubleN(v: Double): Long
    @JvmStatic external fun PyLong_AsIntN(obj: Long): Int
    @JvmStatic external fun PyLong_AsLongLongN(obj: Long): Long
    @JvmStatic external fun PyLong_AsDoubleN(pylong: Long): Double
    @JvmStatic external fun PyLong_GetInfoN(): Long


    // Section 17
    @JvmStatic external fun PyBool_FromLongN(v: Long): Long


    // Section 18
    @JvmStatic external fun PyFloat_FromStringN(str: Long): Long
    @JvmStatic external fun PyFloat_FromDoubleN(v: Double): Long
    @JvmStatic external fun PyFloat_AsDoubleN(pyfloat: Long): Double
    @JvmStatic external fun PyFloat_GetInfoN(): Long
    @JvmStatic external fun PyFloat_GetMaxN(): Double
    @JvmStatic external fun PyFloat_GetMinN(): Double


    // Section 19
    @JvmStatic external fun PyBytes_FromStringN(v: Long): Long
    @JvmStatic external fun PyBytes_FromObjectN(o: Long): Long
    // char* into the bytes object's own buffer -- valid while `o` lives, never freed here.
    @JvmStatic external fun PyBytes_AsStringN(o: Long): Long


    // Section 20
    @JvmStatic external fun PyByteArray_FromObjectN(o: Long): Long
    @JvmStatic external fun PyByteArray_ConcatN(a: Long, b: Long): Long
    @JvmStatic external fun PyByteArray_AsStringN(bytearray: Long): Long


    // Section 21
    @JvmStatic external fun PyUnicode_IsIdentifierN(unicode: Long): Int
    // external fun PyUnicode_FromString(str: String): JNIPointer?
    @JvmStatic external fun PyUnicode_FromObjectN(obj: Long): Long
    @JvmStatic external fun PyUnicode_FromEncodedObjectN(obj: Long, encoding: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_DecodeLocaleN(str: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_EncodeLocaleN(unicode: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_DecodeFSDefaultN(str: Long): Long
    @JvmStatic external fun PyUnicode_EncodeFSDefaultN(unicode: Long): Long
    @JvmStatic external fun PyUnicode_AsEncodedStringN(unicode: Long, encoding: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_AsUTF8StringN(unicode: Long): Long
    // external fun PyUnicode_AsUTF8(unicode: JNIPointer): String? // 수동 추가
    @JvmStatic external fun PyUnicode_AsUTF32StringN(unicode: Long): Long
    @JvmStatic external fun PyUnicode_AsUTF16StringN(unicode: Long): Long
    @JvmStatic external fun PyUnicode_AsUnicodeEscapeStringN(unicode: Long): Long
    @JvmStatic external fun PyUnicode_AsRawUnicodeEscapeStringN(unicode: Long): Long
    @JvmStatic external fun PyUnicode_AsLatin1StringN(unicode: Long): Long
    @JvmStatic external fun PyUnicode_AsASCIIStringN(unicode: Long): Long
    @JvmStatic external fun PyUnicode_AsCharmapStringN(unicode: Long, mapping: Long): Long
    @JvmStatic external fun PyUnicode_TranslateN(unicode: Long, table: Long, errors: Long): Long
    @JvmStatic external fun PyUnicode_ConcatN(left: Long, right: Long): Long
    @JvmStatic external fun PyUnicode_SplitlinesN(unicode: Long, keepends: Int): Long
    @JvmStatic external fun PyUnicode_JoinN(separator: Long, seq: Long): Long
    @JvmStatic external fun PyUnicode_CompareN(left: Long, right: Long): Int
    @JvmStatic external fun PyUnicode_EqualToUTF8N(unicode: Long, string: Long): Int
    @JvmStatic external fun PyUnicode_CompareWithASCIIStringN(unicode: Long, string: Long): Int
    @JvmStatic external fun PyUnicode_RichCompareN(left: Long, right: Long, op: Int): Long
    @JvmStatic external fun PyUnicode_FormatN(format: Long, args: Long): Long
    @JvmStatic external fun PyUnicode_ContainsN(unicode: Long, substr: Long): Int
    @JvmStatic external fun PyUnicode_InternFromStringN(str: Long): Long


    // Section 22
    @JvmStatic external fun PyList_NewN(len: Long): Long
    @JvmStatic
    @dalvik.annotation.optimization.CriticalNative
    external fun PyList_Size(list: Long): Long
    // PyList_GetItem is bound as the PyList_GetItemRaw / PyList_GetItemRawF pair above; the
    // name-linked `PyList_GetItem` declaration that used to sit here had no call site.
    @JvmStatic external fun PyList_SetItemN(list: Long, index: Long, item: Long): Int
    @JvmStatic external fun PyList_InsertN(list: Long, index: Long, item: Long): Int
    @JvmStatic external fun PyList_AppendN(list: Long, item: Long): Int
    @JvmStatic external fun PyList_SortN(list: Long): Int
    @JvmStatic external fun PyList_ReverseN(list: Long): Int
    @JvmStatic external fun PyList_AsTupleN(list: Long): Long


    // Section 23
    // external fun PyDict_New(): JNIPointer?
    @JvmStatic external fun PyDict_SizeN(p: Long): Long
    @JvmStatic external fun PyDictProxy_NewN(mapping: Long): Long
    @JvmStatic external fun PyDict_ClearN(p: Long)
    @JvmStatic external fun PyDict_ContainsN(p: Long, key: Long): Int
    @JvmStatic external fun PyDict_CopyN(p: Long): Long
    // external fun PyDict_SetItem(p: JNIPointer, key: JNIPointer, v: JNIPointer): Int
    // external fun PyDict_SetItemString(p: JNIPointer, key: String, v: JNIPointer): Int
    @JvmStatic external fun PyDict_DelItemN(p: Long, key: Long): Int
    @JvmStatic external fun PyDict_DelItemStringN(p: Long, key: Long): Int
    @JvmStatic external fun PyDict_GetItemN(p: Long, key: Long): Long
    @JvmStatic external fun PyDict_GetItemWithErrorN(p: Long, key: Long): Long
    // external fun PyDict_GetItemString(p: JNIPointer, key: String): JNIPointer?
    @JvmStatic external fun PyDict_ItemsN(p: Long): Long
    @JvmStatic external fun PyDict_KeysN(p: Long): Long
    @JvmStatic external fun PyDict_ValuesN(p: Long): Long
    @JvmStatic external fun PyDict_MergeN(a: Long, b: Long, override: Int): Int
    @JvmStatic external fun PyDict_UpdateN(a: Long, b: Long): Int
    @JvmStatic external fun PyDict_MergeFromSeq2N(a: Long, seq2: Long, override: Int): Int


    // Section 24
    @JvmStatic external fun PySet_NewN(iterable: Long): Long
    @JvmStatic external fun PyFrozenSet_NewN(iterable: Long): Long
    @JvmStatic external fun PySet_ContainsN(anyset: Long, key: Long): Int
    @JvmStatic external fun PySet_SizeN(anyset: Long): Long
    @JvmStatic external fun PySet_AddN(set: Long, key: Long): Int
    @JvmStatic external fun PySet_DiscardN(set: Long, key: Long): Int
    @JvmStatic external fun PySet_PopN(set: Long): Long
    @JvmStatic external fun PySet_ClearN(set: Long): Int


    // Section 25
    @JvmStatic external fun PySeqIter_NewN(seq: Long): Long
    @JvmStatic external fun PyCallIter_NewN(callable: Long, sentinel: Long): Long


    // Section 26
    @JvmStatic external fun PyWeakref_NewRefN(ob: Long, callback: Long): Long
    @JvmStatic external fun PyWeakref_NewProxyN(ob: Long, callback: Long): Long
    @JvmStatic external fun PyWeakref_GetRefN(ref: Long): Long
    @JvmStatic external fun PyObject_ClearWeakRefsN(o: Long)


    // Section 27
    @JvmStatic external fun PyType_IsSubtypeN(a: Long, b: Long): Int
    @JvmStatic external fun PyType_ReadyN(type: Long): Int
    @JvmStatic external fun PyType_GetNameN(type: Long): Long
    @JvmStatic external fun PyType_GetFullyQualifiedNameN(type: Long): Long
    @JvmStatic external fun PyType_GetModuleNameN(type: Long): Long
    @JvmStatic external fun PyType_GetModuleN(type: Long): Long


    // Section 28
    // external fun PyTuple_New(len: Long): JNIPointer?
    @JvmStatic external fun PyTuple_SizeN(p: Long): Long
    @JvmStatic external fun PyTuple_GetItemN(p: Long, pos: Long): Long
    @JvmStatic external fun PyTuple_GetSliceN(p: Long, low: Long, high: Long): Long
    // external fun PyTuple_SetItem(p: JNIPointer, pos: Long, o: JNIPointer): Int


    // Section 29
    @JvmStatic external fun PyModule_GetNameN(module: Long): Long
    @JvmStatic external fun PyModule_GetDictN(module: Long): Long
    @JvmStatic external fun PyModule_GetFilenameObjectN(module: Long): Long


    //**************************************************
    // Shape vocabulary (see docs/design/downcall-design.md and jvmMain/.../ShapeDowncalls.kt).
    //
    // Unlike every other declaration in this file (one native method per CPython function),
    // these 14 are reused across all ~330 CPython functions of a given shape -- the target
    // function's address travels as the leading `fn` argument.
    //
    // They used to reach the `@CName` trampolines in `nativeMain/.../EmbedAPI.native.kt` by name.
    // That is the broken path, and it is worse here than anywhere else: the shifted argument
    // register would be the *function pointer*, so the failure mode is a wild call rather than a
    // bad PyObject*. The indirect call happens in `jni_onload.def` now, which removes the
    // fallback and the hop through Kotlin/Native at the same time.
    @JvmStatic external fun downcall_V(fn: Long)
    @JvmStatic external fun downcall_I(fn: Long): Long
    @JvmStatic external fun downcall_F(fn: Long): Double
    @JvmStatic external fun downcallI_V(fn: Long, a0: Long)
    @JvmStatic external fun downcallI_I(fn: Long, a0: Long): Long
    @JvmStatic external fun downcallI_F(fn: Long, a0: Long): Double
    @JvmStatic external fun downcallF_I(fn: Long, a0: Double): Long
    @JvmStatic external fun downcallII_V(fn: Long, a0: Long, a1: Long)
    @JvmStatic external fun downcallII_I(fn: Long, a0: Long, a1: Long): Long
    @JvmStatic external fun downcallIII_V(fn: Long, a0: Long, a1: Long, a2: Long)
    @JvmStatic external fun downcallIII_I(fn: Long, a0: Long, a1: Long, a2: Long): Long
    @JvmStatic external fun downcallIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long
    @JvmStatic external fun downcallIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long): Long
    @JvmStatic external fun downcallIIIIII_I(fn: Long, a0: Long, a1: Long, a2: Long, a3: Long, a4: Long, a5: Long): Long

    // Symbol lookup (uncached primitive -- caching happens on the jvmMain side, see ffiSymbol).
    // `name` is an already-encoded C string address, not a jstring: ffiSymbol keys its cache by
    // name, so each symbol name is encoded once and interning it costs nothing extra.
    @JvmStatic external fun ffiSymbolRawN(name: Long): Long

    // UTF-8 string marshalling. See the lifetime discussion in jvmMain/.../ShapeDowncalls.kt:
    // ffiAllocUtf8's result must be released with ffiFreeUtf8 by the caller; ffiReadUtf8 never
    // frees its input.
    //
    // These three are the only declarations left in this file that ART resolves by name, and they
    // are safe: they are hand-written in `artMain/.../JNIOnLoadExporter.kt` with the correct
    // `JNIEnv*`/`jclass` prologue and a real `jstring`, so the convention matches. They cannot
    // move into `jni_onload.def` -- the buffers they allocate and read belong to Kotlin/Native.
    external fun ffiAllocUtf8(str: String): Long
    external fun ffiFreeUtf8(ptr: Long)
    external fun ffiReadUtf8(ptr: Long): String?

    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyUnicode_AsUTF8(unicode: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyUnicode_AsUTF8F(unicode: Long): Long
    @JvmStatic @dalvik.annotation.optimization.CriticalNative external fun PyUnicode_FromString(str: Long): Long
    @JvmStatic @dalvik.annotation.optimization.FastNative external fun PyUnicode_FromStringF(str: Long): Long

    @JvmStatic external fun PyImport_AddModuleRefN(name: Long): Long
    // Registered after the name-linked fallback crashed the object model on Android: the second
    // commonTest case died with SIGSEGV at a truncated pointer inside PyImport_AddModule.
    @JvmStatic external fun PyImport_AddModuleN(name: Long): Long
    @JvmStatic external fun PyErr_SetStringN(type: Long, message: Long)
    @JvmStatic external fun PyObject_SetAttrStringN(o: Long, attr_name: Long, v: Long): Int
    @JvmStatic external fun PyObject_DelAttrStringN(o: Long, attr_name: Long): Int
    @JvmStatic external fun PyRun_StringN(str: Long, start: Int, globals: Long, locals: Long): Long
    @JvmStatic external fun PyObject_StrN(o: Long): Long
    @JvmStatic external fun PyObject_IsTrueN(o: Long): Int
    @JvmStatic external fun PyObject_CallNoArgsN(callable: Long): Long
    @JvmStatic external fun PyObject_CallObjectN(callable: Long, args: Long): Long


    @JvmStatic external fun Py_DecRefN(o: Long)
    @JvmStatic external fun Py_IncRefN(o: Long)
    @JvmStatic external fun Py_NewRefN(o: Long): Long
    @JvmStatic external fun Py_XNewRefN(o: Long): Long
    @JvmStatic external fun PyGILState_EnsureN(): Int
    @JvmStatic external fun PyGILState_ReleaseN(state: Int)
    @JvmStatic external fun PyGILState_GetThisThreadStateN(): Long
    @JvmStatic external fun PyEval_SaveThreadN(): Long
    @JvmStatic external fun PyEval_RestoreThreadN(tstate: Long)
    @JvmStatic external fun Py_MakePendingCallsN(): Int
    @JvmStatic external fun PyGC_CollectN(): Long
    @JvmStatic external fun PyEval_InitThreadsN()
    @JvmStatic external fun PyThreadState_GetDictN(): Long
    @JvmStatic external fun PyTuple_NewN(len: Long): Long
    @JvmStatic external fun PyTuple_SetItemN(p: Long, pos: Long, o: Long): Int
    @JvmStatic external fun PyDict_NewN(): Long
    @JvmStatic external fun PyDict_SetItemStringN(p: Long, key: Long, v: Long): Int
    @JvmStatic external fun PyDict_SetItemN(p: Long, key: Long, v: Long): Int
    @JvmStatic external fun PyDict_GetItemStringN(p: Long, key: Long): Long

    //**************************************************
    // Cycle-collecting proxy type.
    //
    // Composed on the native side (jni_onload.def), not one-per-C-API-call like the rest of this
    // file. The type's tp_traverse and tp_clear have to be C function pointers, and Kotlin/JVM
    // has no way to make one -- so C owns the type, and androidMain only asks for it and reads
    // and writes the handle inside an instance. The traffic in the other direction (C calling
    // Kotlin during a collection) goes to python.multiplatform.ffi.ProxyCallbacks.
    //
    // Ordinary JNI on all three: proxyCreateType runs type creation, and the accessors are cold.
    @JvmStatic external fun proxyCreateType(): Long
    @JvmStatic external fun proxyGetHandle(proxy: Long): Long
    @JvmStatic external fun proxySetHandle(proxy: Long, handle: Long)

    /**
     * `ob_refcnt` of the object at [obj], read straight out of the header.
     *
     * `Py_REFCNT` is a macro and `sys.getrefcount` adds its argument's own temporary to the
     * answer, so neither can say whether `tp_dealloc` released the instance's reference to its
     * heap type exactly once. `ob_refcnt` is the first field of `PyObject` and its offset is
     * fixed by the stable ABI, which is the one layout fact this file is allowed to know.
     *
     * Composed on the C side for the same reason [proxySetHandle] is: reading 8 bytes at a raw
     * address from Kotlin/JVM would need `sun.misc.Unsafe`, a restricted non-SDK interface here.
     *
     * A probe, not a production call -- nothing outside the cycle-collection tests uses it, and
     * a caller that passes a non-`PyObject` address gets whatever is at that address.
     */
    @JvmStatic external fun obRefCnt(obj: Long): Long

    //**************************************************
    // Upcalls: Python -> Kotlin/JVM.
    //
    // Composed on the native side for the same reason the proxy type is: a `PyMethodDef`'s
    // `ml_meth` has to be a C function pointer and Kotlin/JVM cannot make one. So C owns the
    // entry points and androidMain only asks for them to be installed; the traffic in the other
    // direction (C calling Kotlin on every upcall) goes to [UpcallCallbacks].
    //
    // Ordinary JNI. It builds function objects and stores them in a dict, so it allocates
    // GC-tracked containers and can reach a Python-level hook -- step 2 of the promotion
    // checklist in this source set's README rules it out on its own -- and it runs once per
    // interpreter, so a promotion would buy nothing even if it were safe. The caller holds the
    // GIL; see [UpcallEntry.publish].
    @JvmStatic external fun upcallPublish(namespace: Long): Int

}
