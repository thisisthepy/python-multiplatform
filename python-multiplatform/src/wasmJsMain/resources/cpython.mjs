// Boots the CPython 3.14.2 Emscripten build and re-exports its raw wasm functions as ES module
// bindings, which is the shape `@WasmImport(module, name)` resolves against.
//
// Two settings in CPython's link line make this possible, neither of them in PEP 783's
// ABI-sensitive list:  -sEXPORTED_RUNTIME_METHODS must include `wasmExports` (otherwise the 8000+
// wasm exports are in the binary but unreachable from JS, so there is nothing to hand to
// @WasmImport) and `wasmMemory` (which is what Kotlin's `intrinsics.memory` import is pointed at;
// see cpython-import-object.patch and the Gradle wiring).
//
// Anything CPython does not export is bound to a thrower rather than left undefined: an ES module
// with a missing named export fails the whole import, which would take out every call rather than
// the one that is actually absent.

import { PYTHON_DIR, STDLIB_ZIP_URL } from "./cpython-config.mjs";

// Two hosts, and the difference is the *filesystem*, not the call path. Under Node the stdlib is
// reached through NODEFS against the real build directory; in a browser there is no such thing, so
// the stdlib arrives as `python3<minor>.zip` fetched into MEMFS -- which is what CPython's own
// `Tools/wasm/emscripten/web_example/python.worker.mjs` does, and the only route that does not
// need a second CPython build.
const IS_NODE = typeof process !== "undefined" && process.versions != null && process.versions.node != null;

// `import(/* webpackIgnore: true */ ...)` rather than a static import, and both halves of that
// matter for the browser:
//
//   - **webpackIgnore.** `python.mjs` is 567 KB of Emscripten glue that branches on `require`,
//     `node:fs`, `node:path` and `import.meta.url` at *runtime*. Letting webpack bundle it means
//     webpack resolving those statically, which it cannot do for a web target. Left alone, the
//     browser loads it as a plain ES module next to the page and Emscripten's own environment
//     detection does the branching it was written to do.
//   - **dynamic.** `node:fs` must not be imported at all in a browser bundle -- a static import is
//     a resolution failure at build time, not a branch that is never taken.
//
// Under Node both specifiers resolve relative to this module, exactly as the static imports they
// replace did. In the browser, `demo.js` is a classic script, so `"./python.mjs"` resolves against
// the document base URL -- i.e. next to `index.html`, which is where the staging puts it.
const EmscriptenModule = (await import(/* webpackIgnore: true */ "./python.mjs")).default;

// -------------------------------------------------------------------------------------------------
// Suppress JSPI before the Emscripten factory runs. This is what makes `import selectors` -- and
// therefore `import asyncio` -- possible on this target.
//
// Emscripten decides whether to use JavaScript Promise Integration by *runtime* feature detection,
// not at build time (CPython's link line has no -sJSPI). Its glue does, verbatim:
//
//     function __block_for_int(p) { return p }
//     if (WebAssembly.Suspending) { __block_for_int = new WebAssembly.Suspending(__block_for_int) }
//     function __maybe_poll_async(...) { if (!WebAssembly.promising) { return null } ... }
//
// and it installs the matching `WebAssembly.promising` wrapper on exactly one export: `main`.
//
// We never call `main` -- deliberately, see the note on Py_InitializeEx below; bring-up is
// `Python3.initialize()` issuing `Py_Initialize` as a direct wasm call. So on a runtime where JSPI
// exists (Node >= 24; the Gradle runner is on 26) the blocking syscalls become suspending imports
// with no promising frame anywhere on the stack, and the first one to run tears the process down:
//
//     select.poll().poll(0)   ->  SuspendError: trying to suspend without WebAssembly.promising
//
// `selectors.py` calls exactly that at import time -- `_can_use('poll')` (line 582) from the
// DefaultSelector choice (line 600) -- which is why `import select` was fine but `import selectors`
// was not, and why `asyncio` died only by way of `selectors`. The SuspendError unwinds CPython's C
// frames without running Py_END_ALLOW_THREADS, so the outer withGIL{}'s PyGILState_Release then
// hits `Py_FatalError: thread state ... must be current when releasing` -> abort() -> the wasm
// `unreachable` opcode. That last hop is the `RuntimeError: unreachable` this was reported as; it
// is the third-order symptom, not the cause.
//
// Measured, same wasm binary, `select.poll().poll(0)`: Node 22 (no JSPI) returns []; Node 26 (JSPI)
// raises SuspendError. Hiding the two properties here puts Node 26 back on Node 22's synchronous
// path. Nothing in this library wants the async one: it needs blocking syscalls to *block*, because
// every call arrives from Kotlin as a plain synchronous wasm call.
//
// Deleting rather than shimming the stdlib is the point. `selectors` stays the real module and
// still resolves DefaultSelector to the real `PollSelector`; what changes is our own Emscripten
// boot, which is this file's job.
//
// This must run before the factory call, not merely before this module's body -- ES imports hoist,
// so `python.mjs` has already been evaluated by now. That is fine: the JSPI wrapping happens inside
// the factory, when it is invoked.
delete WebAssembly.promising;
delete WebAssembly.Suspending;

/**
 * Writes the standard library zip into MEMFS at the one path `getpath` looks for.
 *
 * `/lib/python<major><minor>.zip` -- no dot -- is where CPython puts the zip on `sys.path` when
 * `sys.prefix` is `/`, and the build ships it as `python<major>.<minor>.zip`, so the rename is not
 * cosmetic. The version is read out of the running interpreter rather than hardcoded, so a version
 * bump cannot leave the zip under a name `getpath` does not look for; same expression CPython's own
 * `Tools/wasm/emscripten/web_example` uses.
 *
 * `/lib` is MEMFS on both hosts. Under Node it is one of the three directories the NODEFS loop below
 * deliberately does not mount, which is what leaves this path free to write.
 */
function installStdlibZip(Module, bytes) {
    const versionInt = Module.HEAPU32[Module._Py_Version >>> 2];
    const major = (versionInt >>> 24) & 0xff;
    const minor = (versionInt >>> 16) & 0xff;
    // Without this, getpath complains that it cannot find exec-prefix. It is a marker directory
    // only; this build has no dynamically loaded stdlib extensions.
    Module.FS.mkdirTree(`/lib/python${major}.${minor}/lib-dynload/`);
    Module.FS.writeFile(`/lib/python${major}${minor}.zip`, new Uint8Array(bytes), { canOwn: true });
}

// The Node settings. NODEFS still mounts the real filesystem -- tests reach host paths through it --
// but the **standard library** arrives the same way it does in a browser: as the zip staged next to
// `python.wasm`.
//
// That last part is the difference between a test run that needs this machine and one that needs
// only the five staged files. Measured rather than assumed: with the zip absent, `sys.path` under
// Node reads
//
//     ['/lib/python314.zip', '<checkout>/Lib', '<builddir>/build/lib.emscripten-...']
//
// -- entry 0 is a file nothing had ever created, and the stdlib was really coming from entry 1, the
// **CPython source checkout**, which is neither in the build directory nor in the published runtime
// artefact. Point `PMP_PYTHON_DIR` at an unpacked `python-multiplatform-wasm-runtime` zip and the
// interpreter dies at `Py_Initialize` with `Fatal Python error: Failed to import encodings module`.
// Populating entry 0 is what makes the artefact self-sufficient, and it costs a `readFileSync` of
// 3.7 MB at start-up.
//
// Read synchronously, unlike the browser's `fetch`: there is no run dependency to juggle, so the
// zip is simply in place before `preRun` returns.
//
async function nodeSettings() {
    const fs = (await import(/* webpackIgnore: true */ "node:fs")).default;
    return {
        noInitialRun: true,
        thisProgram: PYTHON_DIR + "/python.sh",
        arguments: [],
        preRun(Module) {
            globalThis.Module = Module;
            for (const dir of fs.readdirSync("/")
                                .filter((d) => !["dev", "lib", "proc"].includes(d))
                                .map((d) => "/" + d)) {
                Module.FS.mkdirTree(dir);
                Module.FS.mount(Module.FS.filesystems.NODEFS, { root: dir }, dir);
            }
            if (STDLIB_ZIP_URL !== null) {
                // Resolved against this module rather than the process working directory: the
                // runner executes out of a staging directory whose name it chooses, and the zip is
                // beside this file there. Node's `fs` accepts a `file:` URL directly.
                installStdlibZip(Module, fs.readFileSync(new URL(STDLIB_ZIP_URL, import.meta.url)));
            }
            Module.FS.chdir(PYTHON_DIR);
            Object.assign(Module.ENV, process.env);
            delete Module.ENV.PATH;
            // Set last, and unconditionally, for two separate reasons.
            //
            // It is the *answer* to the question getpath asks: with the stdlib installed at
            // `/lib/python<major><minor>.zip` the prefix really is `/`, and saying so is what stops
            // `Could not find platform independent libraries <prefix>` being printed to stderr on
            // every run whose `PYTHON_DIR` is an unpacked runtime artefact rather than a build
            // directory (there is no `python.sh` there for `thisProgram` to name).
            //
            // And it overrides whatever the host shell exported, which is not hypothetical for
            // this library: a shell set up to run the *desktop* target has `PYTHONHOME` pointing at
            // a native CPython tree. Inherited here it would aim the wasm interpreter at a tree
            // with the wrong ABI, and whether the suite passed would depend on which shell launched
            // it. Same reasoning as `PATH` above.
            Module.ENV.PYTHONHOME = "/";
        },
    };
}

// The browser settings. **No `thisProgram`**: it would make `sys.prefix` a host path that does not
// exist in MEMFS, and getpath would then find no stdlib at all. Left unset, the prefix is `/` and
// CPython looks for `/lib/python3<minor>.zip` -- which is exactly the artefact the build ships next
// to `python.wasm`.
//
// `addRunDependency` is what makes the fetch part of start-up rather than a race: the factory's
// promise does not resolve until the matching `removeRunDependency`, so by the time Kotlin issues
// `Py_Initialize` the zip is already in the filesystem.
function browserSettings() {
    return {
        noInitialRun: true,
        arguments: [],
        async preRun(Module) {
            globalThis.Module = Module;
            Module.addRunDependency("install-stdlib");
            const response = await fetch(STDLIB_ZIP_URL);
            if (!response.ok) {
                throw new Error(
                    `could not fetch the Python standard library from ${STDLIB_ZIP_URL} ` +
                    `(HTTP ${response.status}). It is staged next to python.wasm by ` +
                    `python-multiplatform's stageWasmBrowserRuntime task.`
                );
            }
            installStdlibZip(Module, await response.arrayBuffer());
            Module.removeRunDependency("install-stdlib");
        },
    };
}

const M = await EmscriptenModule(IS_NODE ? await nodeSettings() : browserSettings());

// Deliberately NOT calling Py_InitializeEx here. The former `wasm-experiment/` brought the interpreter up
// from JS because it was testing the call and memory path rather than who types the first call;
// this has to test the library's own bring-up, so `Python3.initialize()` on the Kotlin side issues
// `Py_Initialize` as a direct wasm call like any other.
const E = M.wasmExports;

export const wasmMemory = M.wasmMemory;
export const wasmExports = E;
export const mod = M;

const missing = (n) => () => { throw new Error("python.wasm does not export " + n); };
const bind = (n) => (typeof E[n] === "function" ? E[n] : missing(n));

export const Py_Initialize = bind("Py_Initialize");
export const Py_InitializeEx = bind("Py_InitializeEx");
export const Py_IsInitialized = bind("Py_IsInitialized");
export const Py_IsFinalizing = bind("Py_IsFinalizing");
export const Py_FinalizeEx = bind("Py_FinalizeEx");
export const Py_Finalize = bind("Py_Finalize");
export const Py_RunMain = bind("Py_RunMain");
export const Py_GetVersion = bind("Py_GetVersion");
export const Py_GetPlatform = bind("Py_GetPlatform");
export const Py_GetCopyright = bind("Py_GetCopyright");
export const Py_GetCompiler = bind("Py_GetCompiler");
export const Py_GetBuildInfo = bind("Py_GetBuildInfo");
export const PyEval_InitThreads = bind("PyEval_InitThreads");
export const PyThreadState_GetDict = bind("PyThreadState_GetDict");
export const PyGILState_Ensure = bind("PyGILState_Ensure");
export const PyGILState_Release = bind("PyGILState_Release");
export const PyGILState_GetThisThreadState = bind("PyGILState_GetThisThreadState");
export const PyEval_SaveThread = bind("PyEval_SaveThread");
export const PyEval_RestoreThread = bind("PyEval_RestoreThread");
export const PyRun_SimpleString = bind("PyRun_SimpleString");
export const PyRun_String = bind("PyRun_String");
export const Py_CompileString = bind("Py_CompileString");
export const PyEval_EvalCode = bind("PyEval_EvalCode");
export const PyErr_Clear = bind("PyErr_Clear");
export const PyErr_PrintEx = bind("PyErr_PrintEx");
export const PyErr_Print = bind("PyErr_Print");
export const PyErr_WriteUnraisable = bind("PyErr_WriteUnraisable");
export const PyErr_DisplayException = bind("PyErr_DisplayException");
export const PyErr_SetString = bind("PyErr_SetString");
export const PyErr_SetObject = bind("PyErr_SetObject");
export const PyErr_SetNone = bind("PyErr_SetNone");
export const PyErr_BadArgument = bind("PyErr_BadArgument");
export const PyErr_NoMemory = bind("PyErr_NoMemory");
export const PyErr_SetFromErrno = bind("PyErr_SetFromErrno");
export const PyErr_SetFromErrnoWithFilenameObject = bind("PyErr_SetFromErrnoWithFilenameObject");
export const PyErr_SetFromErrnoWithFilenameObjects = bind("PyErr_SetFromErrnoWithFilenameObjects");
export const PyErr_SetFromErrnoWithFilename = bind("PyErr_SetFromErrnoWithFilename");
export const PyErr_SetImportError = bind("PyErr_SetImportError");
export const PyErr_SetImportErrorSubclass = bind("PyErr_SetImportErrorSubclass");
export const PyErr_SyntaxLocationEx = bind("PyErr_SyntaxLocationEx");
export const PyErr_SyntaxLocation = bind("PyErr_SyntaxLocation");
export const PyErr_BadInternalCall = bind("PyErr_BadInternalCall");
export const PyErr_WarnExplicit = bind("PyErr_WarnExplicit");
export const PyErr_Occurred = bind("PyErr_Occurred");
// Added with the free-threading eval-loop checkpoint. Py_MakePendingCalls does not merge the
// biased-refcount queue -- measured -- but PyGC_Collect does, by stopping the world; both are
// stable ABI and bound on every platform, so wasm has to expose them too or the module dies at
// instantiation rather than at the call.
export const PyGC_Collect = bind("PyGC_Collect");
export const Py_MakePendingCalls = bind("Py_MakePendingCalls");
export const PyErr_ExceptionMatches = bind("PyErr_ExceptionMatches");
export const PyErr_GivenExceptionMatches = bind("PyErr_GivenExceptionMatches");
export const PyErr_GetRaisedException = bind("PyErr_GetRaisedException");
export const PyErr_SetRaisedException = bind("PyErr_SetRaisedException");
export const PyErr_Restore = bind("PyErr_Restore");
export const PyErr_GetHandledException = bind("PyErr_GetHandledException");
export const PyErr_SetHandledException = bind("PyErr_SetHandledException");
export const PyErr_SetExcInfo = bind("PyErr_SetExcInfo");
export const PyErr_CheckSignals = bind("PyErr_CheckSignals");
export const PyErr_SetInterrupt = bind("PyErr_SetInterrupt");
export const PyErr_SetInterruptEx = bind("PyErr_SetInterruptEx");
export const PyErr_NewException = bind("PyErr_NewException");
export const PyErr_NewExceptionWithDoc = bind("PyErr_NewExceptionWithDoc");
export const PyException_GetTraceback = bind("PyException_GetTraceback");
export const PyException_SetTraceback = bind("PyException_SetTraceback");
export const PyException_GetContext = bind("PyException_GetContext");
export const PyException_SetContext = bind("PyException_SetContext");
export const PyException_GetCause = bind("PyException_GetCause");
export const PyException_SetCause = bind("PyException_SetCause");
export const PyException_GetArgs = bind("PyException_GetArgs");
export const PyException_SetArgs = bind("PyException_SetArgs");
export const PyUnicodeEncodeError_GetEncoding = bind("PyUnicodeEncodeError_GetEncoding");
export const PyUnicodeTranslateError_GetObject = bind("PyUnicodeTranslateError_GetObject");
export const PyUnicodeTranslateError_GetReason = bind("PyUnicodeTranslateError_GetReason");
export const PyUnicodeTranslateError_SetReason = bind("PyUnicodeTranslateError_SetReason");
export const Py_EnterRecursiveCall = bind("Py_EnterRecursiveCall");
export const Py_LeaveRecursiveCall = bind("Py_LeaveRecursiveCall");
export const Py_ReprEnter = bind("Py_ReprEnter");
export const Py_ReprLeave = bind("Py_ReprLeave");
export const Py_NewRef = bind("Py_NewRef");
export const Py_XNewRef = bind("Py_XNewRef");
export const Py_IncRef = bind("Py_IncRef");
export const Py_DecRef = bind("Py_DecRef");
export const PyOS_FSPath = bind("PyOS_FSPath");
export const PySys_GetObject = bind("PySys_GetObject");
export const PySys_SetObject = bind("PySys_SetObject");
export const PySys_GetXOptions = bind("PySys_GetXOptions");
export const PySys_AuditTuple = bind("PySys_AuditTuple");
export const Py_FatalError = bind("Py_FatalError");
export const Py_Exit = bind("Py_Exit");
export const PyImport_ImportModule = bind("PyImport_ImportModule");
export const PyImport_ImportModuleLevelObject = bind("PyImport_ImportModuleLevelObject");
export const PyImport_ImportModuleLevel = bind("PyImport_ImportModuleLevel");
export const PyImport_Import = bind("PyImport_Import");
export const PyImport_ReloadModule = bind("PyImport_ReloadModule");
export const PyImport_AddModuleRef = bind("PyImport_AddModuleRef");
export const PyImport_AddModuleObject = bind("PyImport_AddModuleObject");
export const PyImport_AddModule = bind("PyImport_AddModule");
export const PyImport_ExecCodeModule = bind("PyImport_ExecCodeModule");
export const PyImport_ExecCodeModuleEx = bind("PyImport_ExecCodeModuleEx");
export const PyImport_ExecCodeModuleObject = bind("PyImport_ExecCodeModuleObject");
export const PyImport_ExecCodeModuleWithPathnames = bind("PyImport_ExecCodeModuleWithPathnames");
export const PyImport_GetMagicTag = bind("PyImport_GetMagicTag");
export const PyImport_GetModuleDict = bind("PyImport_GetModuleDict");
export const PyImport_GetModule = bind("PyImport_GetModule");
export const PyImport_GetImporter = bind("PyImport_GetImporter");
export const PyImport_ImportFrozenModuleObject = bind("PyImport_ImportFrozenModuleObject");
export const PyImport_ImportFrozenModule = bind("PyImport_ImportFrozenModule");
export const PyEval_GetBuiltins = bind("PyEval_GetBuiltins");
export const PyEval_GetLocals = bind("PyEval_GetLocals");
export const PyEval_GetGlobals = bind("PyEval_GetGlobals");
export const PyEval_GetFrameBuiltins = bind("PyEval_GetFrameBuiltins");
export const PyEval_GetFrameLocals = bind("PyEval_GetFrameLocals");
export const PyEval_GetFrameGlobals = bind("PyEval_GetFrameGlobals");
export const PyEval_GetFuncName = bind("PyEval_GetFuncName");
export const PyEval_GetFuncDesc = bind("PyEval_GetFuncDesc");
export const PyObject_HasAttrWithError = bind("PyObject_HasAttrWithError");
export const PyObject_HasAttrStringWithError = bind("PyObject_HasAttrStringWithError");
export const PyObject_HasAttr = bind("PyObject_HasAttr");
export const PyObject_HasAttrString = bind("PyObject_HasAttrString");
export const PyObject_GetAttr = bind("PyObject_GetAttr");
export const PyObject_GetAttrString = bind("PyObject_GetAttrString");
export const PyObject_GenericGetAttr = bind("PyObject_GenericGetAttr");
export const PyObject_SetAttr = bind("PyObject_SetAttr");
export const PyObject_SetAttrString = bind("PyObject_SetAttrString");
export const PyObject_GenericSetAttr = bind("PyObject_GenericSetAttr");
export const PyObject_DelAttr = bind("PyObject_DelAttr");
export const PyObject_DelAttrString = bind("PyObject_DelAttrString");
export const PyObject_RichCompare = bind("PyObject_RichCompare");
export const PyObject_RichCompareBool = bind("PyObject_RichCompareBool");
export const PyObject_Format = bind("PyObject_Format");
export const PyObject_Repr = bind("PyObject_Repr");
export const PyObject_ASCII = bind("PyObject_ASCII");
export const PyObject_Str = bind("PyObject_Str");
export const PyObject_Bytes = bind("PyObject_Bytes");
export const PyObject_IsSubclass = bind("PyObject_IsSubclass");
export const PyObject_IsInstance = bind("PyObject_IsInstance");
export const PyObject_IsTrue = bind("PyObject_IsTrue");
export const PyObject_Not = bind("PyObject_Not");
export const PyObject_Type = bind("PyObject_Type");
export const PyObject_Size = bind("PyObject_Size");
export const PyObject_Length = bind("PyObject_Length");
export const PyObject_GetItem = bind("PyObject_GetItem");
export const PyObject_SetItem = bind("PyObject_SetItem");
export const PyObject_DelItem = bind("PyObject_DelItem");
export const PyObject_Dir = bind("PyObject_Dir");
export const PyObject_GetIter = bind("PyObject_GetIter");
export const PyObject_GetAIter = bind("PyObject_GetAIter");
export const PyVectorcall_Call = bind("PyVectorcall_Call");
export const PyObject_Call = bind("PyObject_Call");
export const PyObject_CallNoArgs = bind("PyObject_CallNoArgs");
export const PyObject_CallObject = bind("PyObject_CallObject");
export const PyCallable_Check = bind("PyCallable_Check");
export const PyNumber_Check = bind("PyNumber_Check");
export const PyNumber_Add = bind("PyNumber_Add");
export const PyNumber_Subtract = bind("PyNumber_Subtract");
export const PyNumber_Multiply = bind("PyNumber_Multiply");
export const PyNumber_MatrixMultiply = bind("PyNumber_MatrixMultiply");
export const PyNumber_FloorDivide = bind("PyNumber_FloorDivide");
export const PyNumber_TrueDivide = bind("PyNumber_TrueDivide");
export const PyNumber_Remainder = bind("PyNumber_Remainder");
export const PyNumber_Divmod = bind("PyNumber_Divmod");
export const PyNumber_Power = bind("PyNumber_Power");
export const PyNumber_Negative = bind("PyNumber_Negative");
export const PyNumber_Positive = bind("PyNumber_Positive");
export const PyNumber_Absolute = bind("PyNumber_Absolute");
export const PyNumber_Invert = bind("PyNumber_Invert");
export const PyNumber_Lshift = bind("PyNumber_Lshift");
export const PyNumber_Rshift = bind("PyNumber_Rshift");
export const PyNumber_And = bind("PyNumber_And");
export const PyNumber_Xor = bind("PyNumber_Xor");
export const PyNumber_Or = bind("PyNumber_Or");
export const PyNumber_InPlaceAdd = bind("PyNumber_InPlaceAdd");
export const PyNumber_InPlaceSubtract = bind("PyNumber_InPlaceSubtract");
export const PyNumber_InPlaceMultiply = bind("PyNumber_InPlaceMultiply");
export const PyNumber_InPlaceMatrixMultiply = bind("PyNumber_InPlaceMatrixMultiply");
export const PyNumber_InPlaceFloorDivide = bind("PyNumber_InPlaceFloorDivide");
export const PyNumber_InPlaceTrueDivide = bind("PyNumber_InPlaceTrueDivide");
export const PyNumber_InPlaceRemainder = bind("PyNumber_InPlaceRemainder");
export const PyNumber_InPlacePower = bind("PyNumber_InPlacePower");
export const PyNumber_InPlaceLshift = bind("PyNumber_InPlaceLshift");
export const PyNumber_InPlaceRshift = bind("PyNumber_InPlaceRshift");
export const PyNumber_InPlaceAnd = bind("PyNumber_InPlaceAnd");
export const PyNumber_InPlaceXor = bind("PyNumber_InPlaceXor");
export const PyNumber_InPlaceOr = bind("PyNumber_InPlaceOr");
export const PyNumber_Long = bind("PyNumber_Long");
export const PyNumber_Float = bind("PyNumber_Float");
export const PyNumber_Index = bind("PyNumber_Index");
export const PyNumber_ToBase = bind("PyNumber_ToBase");
export const PyIndex_Check = bind("PyIndex_Check");
export const PySequence_Check = bind("PySequence_Check");
export const PySequence_Concat = bind("PySequence_Concat");
export const PySequence_InPlaceConcat = bind("PySequence_InPlaceConcat");
export const PySequence_Contains = bind("PySequence_Contains");
export const PySequence_List = bind("PySequence_List");
export const PySequence_Tuple = bind("PySequence_Tuple");
export const PySequence_Fast = bind("PySequence_Fast");
export const PyMapping_Check = bind("PyMapping_Check");
export const PyMapping_GetItemString = bind("PyMapping_GetItemString");
export const PyMapping_SetItemString = bind("PyMapping_SetItemString");
export const PyMapping_HasKeyWithError = bind("PyMapping_HasKeyWithError");
export const PyMapping_HasKeyStringWithError = bind("PyMapping_HasKeyStringWithError");
export const PyMapping_HasKey = bind("PyMapping_HasKey");
export const PyMapping_HasKeyString = bind("PyMapping_HasKeyString");
export const PyMapping_Keys = bind("PyMapping_Keys");
export const PyMapping_Values = bind("PyMapping_Values");
export const PyMapping_Items = bind("PyMapping_Items");
export const PyIter_Check = bind("PyIter_Check");
export const PyAIter_Check = bind("PyAIter_Check");
export const PyIter_Next = bind("PyIter_Next");
export const PyLong_FromLongLong = bind("PyLong_FromLongLong");
export const PyLong_FromDouble = bind("PyLong_FromDouble");
export const PyLong_AsInt = bind("PyLong_AsInt");
export const PyLong_AsLongLong = bind("PyLong_AsLongLong");
export const PyLong_AsDouble = bind("PyLong_AsDouble");
export const PyLong_GetInfo = bind("PyLong_GetInfo");
export const PyBool_FromLong = bind("PyBool_FromLong");
export const PyFloat_FromString = bind("PyFloat_FromString");
export const PyFloat_FromDouble = bind("PyFloat_FromDouble");
export const PyFloat_AsDouble = bind("PyFloat_AsDouble");
export const PyFloat_GetInfo = bind("PyFloat_GetInfo");
export const PyFloat_GetMax = bind("PyFloat_GetMax");
export const PyFloat_GetMin = bind("PyFloat_GetMin");
export const PyBytes_FromString = bind("PyBytes_FromString");
export const PyBytes_FromObject = bind("PyBytes_FromObject");
export const PyBytes_AsString = bind("PyBytes_AsString");
export const PyByteArray_FromObject = bind("PyByteArray_FromObject");
export const PyByteArray_Concat = bind("PyByteArray_Concat");
export const PyByteArray_AsString = bind("PyByteArray_AsString");
export const PyUnicode_IsIdentifier = bind("PyUnicode_IsIdentifier");
export const PyUnicode_FromString = bind("PyUnicode_FromString");
export const PyUnicode_FromObject = bind("PyUnicode_FromObject");
export const PyUnicode_FromEncodedObject = bind("PyUnicode_FromEncodedObject");
export const PyUnicode_DecodeLocale = bind("PyUnicode_DecodeLocale");
export const PyUnicode_EncodeLocale = bind("PyUnicode_EncodeLocale");
export const PyUnicode_DecodeFSDefault = bind("PyUnicode_DecodeFSDefault");
export const PyUnicode_EncodeFSDefault = bind("PyUnicode_EncodeFSDefault");
export const PyUnicode_AsEncodedString = bind("PyUnicode_AsEncodedString");
export const PyUnicode_AsUTF8String = bind("PyUnicode_AsUTF8String");
export const PyUnicode_AsUTF8 = bind("PyUnicode_AsUTF8");
export const PyUnicode_AsUTF32String = bind("PyUnicode_AsUTF32String");
export const PyUnicode_AsUTF16String = bind("PyUnicode_AsUTF16String");
export const PyUnicode_AsUnicodeEscapeString = bind("PyUnicode_AsUnicodeEscapeString");
export const PyUnicode_AsRawUnicodeEscapeString = bind("PyUnicode_AsRawUnicodeEscapeString");
export const PyUnicode_AsLatin1String = bind("PyUnicode_AsLatin1String");
export const PyUnicode_AsASCIIString = bind("PyUnicode_AsASCIIString");
export const PyUnicode_AsCharmapString = bind("PyUnicode_AsCharmapString");
export const PyUnicode_Translate = bind("PyUnicode_Translate");
export const PyUnicode_Concat = bind("PyUnicode_Concat");
export const PyUnicode_Splitlines = bind("PyUnicode_Splitlines");
export const PyUnicode_Join = bind("PyUnicode_Join");
export const PyUnicode_Compare = bind("PyUnicode_Compare");
export const PyUnicode_EqualToUTF8 = bind("PyUnicode_EqualToUTF8");
export const PyUnicode_CompareWithASCIIString = bind("PyUnicode_CompareWithASCIIString");
export const PyUnicode_RichCompare = bind("PyUnicode_RichCompare");
export const PyUnicode_Format = bind("PyUnicode_Format");
export const PyUnicode_Contains = bind("PyUnicode_Contains");
export const PyUnicode_InternFromString = bind("PyUnicode_InternFromString");
export const PyList_New = bind("PyList_New");
export const PyList_Size = bind("PyList_Size");
export const PyList_GetItem = bind("PyList_GetItem");
export const PyList_SetItem = bind("PyList_SetItem");
export const PyList_Insert = bind("PyList_Insert");
export const PyList_Append = bind("PyList_Append");
export const PyList_Sort = bind("PyList_Sort");
export const PyList_Reverse = bind("PyList_Reverse");
export const PyList_AsTuple = bind("PyList_AsTuple");
export const PyDict_New = bind("PyDict_New");
export const PyDict_Size = bind("PyDict_Size");
export const PyDictProxy_New = bind("PyDictProxy_New");
export const PyDict_Clear = bind("PyDict_Clear");
export const PyDict_Contains = bind("PyDict_Contains");
export const PyDict_Copy = bind("PyDict_Copy");
export const PyDict_SetItem = bind("PyDict_SetItem");
export const PyDict_SetItemString = bind("PyDict_SetItemString");
export const PyDict_DelItem = bind("PyDict_DelItem");
export const PyDict_DelItemString = bind("PyDict_DelItemString");
export const PyDict_GetItem = bind("PyDict_GetItem");
export const PyDict_GetItemWithError = bind("PyDict_GetItemWithError");
export const PyDict_GetItemString = bind("PyDict_GetItemString");
export const PyDict_Items = bind("PyDict_Items");
export const PyDict_Keys = bind("PyDict_Keys");
export const PyDict_Values = bind("PyDict_Values");
export const PyDict_Merge = bind("PyDict_Merge");
export const PyDict_Update = bind("PyDict_Update");
export const PyDict_MergeFromSeq2 = bind("PyDict_MergeFromSeq2");
export const PySet_New = bind("PySet_New");
export const PyFrozenSet_New = bind("PyFrozenSet_New");
export const PySet_Contains = bind("PySet_Contains");
export const PySet_Size = bind("PySet_Size");
export const PySet_Add = bind("PySet_Add");
export const PySet_Discard = bind("PySet_Discard");
export const PySet_Pop = bind("PySet_Pop");
export const PySet_Clear = bind("PySet_Clear");
export const PySeqIter_New = bind("PySeqIter_New");
export const PyCallIter_New = bind("PyCallIter_New");
export const PyWeakref_NewRef = bind("PyWeakref_NewRef");
export const PyWeakref_NewProxy = bind("PyWeakref_NewProxy");
export const PyWeakref_GetRef = bind("PyWeakref_GetRef");
export const PyObject_ClearWeakRefs = bind("PyObject_ClearWeakRefs");
export const PyType_IsSubtype = bind("PyType_IsSubtype");
export const PyType_Ready = bind("PyType_Ready");
export const PyType_GetName = bind("PyType_GetName");
export const PyType_GetFullyQualifiedName = bind("PyType_GetFullyQualifiedName");
export const PyType_GetModuleName = bind("PyType_GetModuleName");
export const PyType_GetModule = bind("PyType_GetModule");
export const PyTuple_New = bind("PyTuple_New");
export const PyTuple_Size = bind("PyTuple_Size");
export const PyTuple_GetItem = bind("PyTuple_GetItem");
export const PyTuple_GetSlice = bind("PyTuple_GetSlice");
export const PyTuple_SetItem = bind("PyTuple_SetItem");
export const PyModule_GetName = bind("PyModule_GetName");
export const PyModule_GetDict = bind("PyModule_GetDict");
export const PyModule_GetFilenameObject = bind("PyModule_GetFilenameObject");
export const malloc = bind("malloc");
export const free = bind("free");

export const PyType_FromSpec = bind("PyType_FromSpec");
export const PyObject_GetTypeData = bind("PyObject_GetTypeData");
export const PyType_GetSlot = bind("PyType_GetSlot");
export const PyObject_GC_UnTrack = bind("PyObject_GC_UnTrack");
export const PyCFunction_NewEx = bind("PyCFunction_NewEx");

// =================================================================================================
// Upcalls -- ROADMAP §7 on this target.
//
// Everything above is a raw CPython export re-exported under its own name. The three functions
// below are the opposite: JavaScript, imported *by* Kotlin. They exist for two limitations of
// Kotlin/Wasm, not because a JS hop was wanted anywhere.
//
//   1. **A table index cannot be obtained from inside Kotlin.** A `@WasmExport` is a funcref in the
//      Kotlin instance's export section; turning it into a C function pointer means putting it in
//      CPython's `__indirect_function_table`, and `WebAssembly.Table.prototype.set` is reachable
//      only from the host. So registration is a JS step, run once per slot at startup.
//
//   2. **Kotlin/Wasm has no `call_indirect`.** `tp_traverse` is handed a `visitproc` and
//      `tp_dealloc` has to reach its type's `tp_free`; both are function *pointers*, i.e. table
//      indices, and calling one means `table.get(i)(...)`.
//
// Neither is on a hot path. After registration, CPython calls Kotlin through `call_indirect` with
// no JS frame at all -- 3.1 ns, measured in the former `wasm-experiment/` (Tests E and F).
//
// A funcref is a funcref whatever instance produced it, which is why (1) works across two
// independently instantiated modules.
// =================================================================================================

// The Kotlin instance's raw wasm exports. Set once, from the generated entry module, by the same
// `doFirst` in build.gradle.kts that points `intrinsics.memory` at `wasmMemory`. It cannot be
// imported from here: this module is loaded *by* Kotlin's import object, so reaching back would be
// an ES cycle across a top-level await.
let kotlinExports = null;

export function pmpSetKotlinExports(exports) {
    kotlinExports = exports;
}

const upcallTable = E.__indirect_function_table ?? M.wasmTable;

// Not `M.UTF8ToString`. That is an Emscripten *runtime method*, and this build's
// -sEXPORTED_RUNTIME_METHODS lists only `wasmExports` and `wasmMemory` -- deliberately, because
// both are outside PEP 783's ABI-sensitive set and nothing else should be needed. Reaching for a
// helper that is not exported fails as an ordinary `undefined is not a function` from inside a
// wasm import, which surfaces in Kotlin as a bare JsException with no clue in it.
//
// A fresh view each call: the memory grows underneath us and an ArrayBuffer view detaches when it
// does. This is a startup path, so the allocation does not matter.
const decoder = new TextDecoder();
function readCString(ptr) {
    const heap = new Uint8Array(M.wasmMemory.buffer);
    let end = ptr;
    while (heap[end] !== 0) end++;
    return decoder.decode(heap.subarray(ptr, end));
}

export function pmpRegisterUpcall(namePtr) {
    // Negative results rather than throws: these cross back into Kotlin as an i32, and
    // `ProxyTypeFactory` turns each code into a message naming the build step that is missing.
    // A JS stack from inside a wasm import arrives in Kotlin as a bare JsException.
    if (kotlinExports === null) return -1;
    if (!upcallTable) return -4;
    const fn = kotlinExports[readCString(namePtr)];
    if (typeof fn !== "function") return -2;
    try {
        const index = upcallTable.grow(1);
        upcallTable.set(index, fn);
        return index;
    } catch (e) {
        return -3;
    }
}

export function pmpCallVisit(fp, obj, arg) {
    return upcallTable.get(fp)(obj, arg);
}

export function pmpCallFree(fp, obj) {
    upcallTable.get(fp)(obj);
}

export default E;
