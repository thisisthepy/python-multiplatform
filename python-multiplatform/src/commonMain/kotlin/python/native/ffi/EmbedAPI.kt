package python.native.ffi

import kotlin.jvm.JvmInline


/**
 * Runs given [block] providing allocation of memory
 * which will be automatically disposed at the end of this scope.
 */
expect inline fun <R> memScoped(block: () -> R): R


interface AddressValue
@JvmInline
value class NativePointer internal constructor(val address: Any) {
    override fun toString(): String =
        "${this::class.simpleName}(self=0x${hashCode().toUInt().toString(16)}, target=0x${toRawValue().toString(16)})"
}
@HighOverheadNativeCall
expect fun NativePointer.toAddressValue(): AddressValue
expect fun NativePointer.toRawValue(): Long
@HighOverheadNativeCall
expect fun AddressValue.toNativePointer(): NativePointer
@HighOverheadNativeCall
expect fun Long.toNativePointer(): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 1
// Initialization, Finalization, and Threads
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Initialize the Python interpreter.  In an application embedding
 * Python, this should be called before using any other Python/C API
 * functions; see Before Python Initialization for the few exceptions.
 *
 * This initializes the table of loaded modules ("sys.modules"), and
 * creates the fundamental modules "builtins", "__main__" and "sys".
 * It also initializes the module search path ("sys.path"). It does
 * not set "sys.argv"; use the Python Initialization Configuration API
 * for that. This is a no-op when called for a second time (without
 * calling "Py_FinalizeEx()" first).  There is no return value; it is
 * a fatal error if the initialization fails.
 *
 * Use "Py_InitializeFromConfig()" to customize the Python
 * Initialization Configuration.
 *
 * Note:
 *
 *   On Windows, changes the console mode from "O_TEXT" to "O_BINARY",
 *   which will also affect non-Python uses of the console using the C
 *   Runtime.
 */
expect inline fun Py_Initialize()

/**
 *  *Part of the Stable ABI.*
 *
 * This function works like "Py_Initialize()" if *initsigs* is "1". If
 * *initsigs* is "0", it skips initialization registration of signal
 * handlers, which may be useful when CPython is embedded as part of a
 * larger application.
 *
 * Use "Py_InitializeFromConfig()" to customize the Python
 * Initialization Configuration.
 */
expect inline fun Py_InitializeEx(initsigs: Int)

/**
 *  *Part of the Stable ABI.*
 *
 * Return true (nonzero) when the Python interpreter has been
 * initialized, false (zero) if not.  After "Py_FinalizeEx()" is
 * called, this returns false until "Py_Initialize()" is called again.
 */
expect inline fun Py_IsInitialized(): Int

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return true (non-zero) if the main Python interpreter is *shutting
 * down*. Return false (zero) otherwise.
 *
 * Added in version 3.13.
 */
expect inline fun Py_IsFinalizing(): Int

/**
 *  *Part of the Stable ABI since version 3.6.*
 *
 * Undo all initializations made by "Py_Initialize()" and subsequent
 * use of Python/C API functions, and destroy all sub-interpreters
 * (see "Py_NewInterpreter()" below) that were created and not yet
 * destroyed since the last call to "Py_Initialize()".  Ideally, this
 * frees all memory allocated by the Python interpreter.  This is a
 * no-op when called for a second time (without calling
 * "Py_Initialize()" again first).
 *
 * Since this is the reverse of "Py_Initialize()", it should be called
 * in the same thread with the same interpreter active.  That means
 * the main thread and the main interpreter. This should never be
 * called while "Py_RunMain()" is running.
 *
 * Normally the return value is "0". If there were errors during
 * finalization (flushing buffered data), "-1" is returned.
 *
 * This function is provided for a number of reasons.  An embedding
 * application might want to restart Python without having to restart
 * the application itself. An application that has loaded the Python
 * interpreter from a dynamically loadable library (or DLL) might want
 * to free all memory allocated by Python before unloading the DLL.
 * During a hunt for memory leaks in an application a developer might
 * want to free all memory allocated by Python before exiting from the
 * application.
 *
 * **Bugs and caveats:** The destruction of modules and objects in
 * modules is done in random order; this may cause destructors
 * ("__del__()" methods) to fail when they depend on other objects
 * (even functions) or modules.  Dynamically loaded extension modules
 * loaded by Python are not unloaded.  Small amounts of memory
 * allocated by the Python interpreter may not be freed (if you find a
 * leak, please report it).  Memory tied up in circular references
 * between objects is not freed.  Some memory allocated by extension
 * modules may not be freed.  Some extensions may not work properly if
 * their initialization routine is called more than once; this can
 * happen if an application calls "Py_Initialize()" and
 * "Py_FinalizeEx()" more than once.
 *
 * Raises an auditing event "cpython._PySys_ClearAuditHooks" with no
 * arguments.
 *
 * Added in version 3.6.
 */
expect inline fun Py_FinalizeEx(): Int

/**
 *  *Part of the Stable ABI.*
 *
 * This is a backwards-compatible version of "Py_FinalizeEx()" that
 * disregards the return value.
 */
expect inline fun Py_Finalize()

// `Py_BytesMain(int argc, char **argv)` is deliberately absent from this surface.
//
// It is the one declaration from the superseded pre-migration draft that had no live counterpart,
// so deleting that draft removed the last copy of it. It is not being revived here: every other
// entry point in this file marshals scalars, pointers or a single string, and `char **argv` needs
// a per-platform array marshaller -- an `Arena`-allocated pointer array on desktop, a
// `jobjectArray` -> `char**` wrapper plus a `JNINativeMethod` registration and a call-convention
// classification on Android, `allocArray<CPointerVar<ByteVar>>` on native, and a linear-memory
// argv on wasm. That is four marshallers and a JNI table change for one function.
//
// Its only consumer is `Python3.runApp`, which already throws `NotImplementedError` naming this
// function, and `Python3Test` pins that message so the gap cannot be quietly closed or quietly
// forgotten. Reviving it is a task of its own; the native-side body written for it survives in
// git history (`EmbedAPI.native.kt`, inside the block deleted here).

/**
 * Executes the main module in a fully configured CPython runtime.
 *
 * Executes the command ("PyConfig.run_command"), the script
 * ("PyConfig.run_filename") or the module ("PyConfig.run_module")
 * specified on the command line or in the configuration. If none of
 * these values are set, runs the interactive Python prompt (REPL)
 * using the "__main__" module’s global namespace.
 *
 * If "PyConfig.inspect" is not set (the default), the return value
 * will be "0" if the interpreter exits normally (that is, without
 * raising an exception), or "1" if the interpreter exits due to an
 * exception. If an otherwise unhandled "SystemExit" is raised, the
 * function will immediately exit the process instead of returning
 * "1".
 *
 * If "PyConfig.inspect" is set (such as when the "-i" option is
 * used), rather than returning when the interpreter exits, execution
 * will instead resume in an interactive Python prompt (REPL) using
 * the "__main__" module’s global namespace. If the interpreter exited
 * with an exception, it is immediately raised in the REPL session.
 * The function return value is then determined by the way the *REPL
 * session* terminates: returning "0" if the session terminates
 * without raising an unhandled exception, exiting immediately for an
 * unhandled "SystemExit", and returning "1" for any other unhandled
 * exception.
 *
 * This function always finalizes the Python interpreter regardless of
 * whether it returns a value or immediately exits the process due to
 * an unhandled "SystemExit" exception.
 *
 * See Python Configuration for an example of a customized Python that
 * always runs in isolated mode using "Py_RunMain()".
 */
expect inline fun Py_RunMain(): Int // 수동 추가

/**
 *  *Part of the Stable ABI.*
 *
 * Return the version of this Python interpreter.  This is a string
 * that looks something like
 *
 *    "3.0a5+ (py3k:63103M, May 12 2008, 00:53:55) \n[GCC 4.2.3]"
 *
 * The first word (up to the first space character) is the current
 * Python version; the first characters are the major and minor
 * version separated by a period.  The returned string points into
 * static storage; the caller should not modify its value.  The value
 * is available to Python code as "sys.version".
 *
 * See also the "Py_Version" constant.
 */
expect inline fun Py_GetVersion(): String?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the platform identifier for the current platform.  On Unix,
 * this is formed from the “official” name of the operating system,
 * converted to lower case, followed by the major revision number;
 * e.g., for Solaris 2.x, which is also known as SunOS 5.x, the value
 * is "'sunos5'".  On macOS, it is "'darwin'".  On Windows, it is
 * "'win'".  The returned string points into static storage; the
 * caller should not modify its value.  The value is available to
 * Python code as "sys.platform".
 */
expect inline fun Py_GetPlatform(): String?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the official copyright string for the current Python
 * version, for example
 *
 * "'Copyright 1991-1995 Stichting Mathematisch Centrum, Amsterdam'"
 *
 * The returned string points into static storage; the caller should
 * not modify its value.  The value is available to Python code as
 * "sys.copyright".
 */
expect inline fun Py_GetCopyright(): String?

/**
 *  *Part of the Stable ABI.*
 *
 * Return an indication of the compiler used to build the current
 * Python version, in square brackets, for example:
 *
 *    "[GCC 2.7.2.2]"
 *
 * The returned string points into static storage; the caller should
 * not modify its value.  The value is available to Python code as
 * part of the variable "sys.version".
 */
expect inline fun Py_GetCompiler(): String?

/**
 *  *Part of the Stable ABI.*
 *
 * Return information about the sequence number and build date and
 * time  of the current Python interpreter instance, for example
 *
 *    "#67, Aug  1 1997, 22:34:28"
 *
 * The returned string points into static storage; the caller should
 * not modify its value.  The value is available to Python code as
 * part of the variable "sys.version".
 */
expect inline fun Py_GetBuildInfo(): String?

/**
 *  *Part of the Stable ABI.*
 *
 * Deprecated function which does nothing.
 *
 * In Python 3.6 and older, this function created the GIL if it didn’t
 * exist.
 *
 * Changed in version 3.9: The function now does nothing.
 *
 * Changed in version 3.7: This function is now called by
 * "Py_Initialize()", so you don’t have to call it yourself anymore.
 *
 * Changed in version 3.2: This function cannot be called before
 * "Py_Initialize()" anymore.
 *
 * Deprecated since version 3.9.
 *
 * This is no longer bound to a C symbol. `ceval.h` marks it `Py_DEPRECATED(3.9)`, which is a
 * compiler-enforced deprecation and therefore a removal schedule, and since 3.9 the C function's
 * body has been empty. A Kotlin no-op is not an approximation of it — it is the same behaviour with
 * one fewer FFI crossing — so nothing is lost by taking it out of the `expect`/`actual` surface,
 * and callers written against older embeddings still compile. `EmbedApiSurfaceTest` derives the
 * `Py_DEPRECATED` set from the bundled headers and fails if this name reappears in the surface.
 */
@Deprecated(
    "Does nothing since CPython 3.9, where it also became Py_DEPRECATED. Py_Initialize has " +
        "created the GIL by itself since 3.7; delete the call.",
    level = DeprecationLevel.WARNING,
)
fun PyEval_InitThreads() {
    // Intentionally empty: this mirrors the CPython 3.9+ implementation exactly.
}

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a dictionary in which extensions can store thread-specific
 * state information.  Each extension should use a unique key to use
 * to store state in the dictionary.  It is okay to call this function
 * when no current thread state is available. If this function returns
 * "NULL", no exception has been raised and the caller should assume
 * no current thread state is available.
 */
expect fun PyThreadState_GetDict(): NativePointer?

/**
 * Thread-state and GIL management bindings
 */
expect inline fun PyGILState_Ensure(): Int
expect inline fun PyGILState_Release(state: Int)
expect fun PyGILState_GetThisThreadState(): NativePointer?
expect fun PyEval_SaveThread(): NativePointer?
expect inline fun PyEval_RestoreThread(tstate: NativePointer)

/**
 * Runs the interpreter's pending-call queue -- and, on the main thread of the main interpreter,
 * pending signal handlers -- on the calling thread.
 *
 * Part of the Stable ABI. Note what it does *not* do. `Py_MakePendingCalls` covers only the
 * `_PY_CALLS_TO_DO_BIT` and `_PY_SIGNALS_PENDING_BIT` work items, while a full eval-loop
 * checkpoint (`_Py_HandlePending`) additionally merges the free-threaded build's biased
 * reference-counting queue, processes QSBR-deferred frees and runs a scheduled cyclic
 * collection. So this is *not* a way to reclaim memory that another thread released; see
 * [python.multiplatform.ffi.Python3.drainPendingReleases] for that.
 *
 * Returns 0 on success and -1 with the error indicator set otherwise. Called from a thread that
 * is not the main thread of the main interpreter it returns 0 having done nothing.
 */
expect fun Py_MakePendingCalls(): Int

/**
 * Runs a full cyclic garbage collection: the C equivalent of `gc.collect()`. Part of the Stable ABI.
 *
 * On a free-threaded build this stops the world and merges *every* thread's deferred
 * reference-count queue, so unlike an eval-loop checkpoint it can reclaim references that were
 * released on behalf of some thread other than the caller. It is correspondingly expensive: the
 * cost is proportional to the size of the heap, not to the amount of queued work.
 *
 * Returns the number of unreachable objects collected.
 */
expect fun PyGC_Collect(): Long


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 2
// The Very High Level Layer
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *
 * This is a simplified interface to "PyRun_SimpleStringFlags()"
 * below, leaving the "PyCompilerFlags"* argument set to "NULL".
 */
expect inline fun PyRun_SimpleString(command: String): Int // 수동 추가

/**
 *  *Return value: New reference.*
 *
 * This is a simplified interface to "PyRun_StringFlags()" below,
 * leaving *flags* set to "NULL".
 */
expect fun PyRun_String(str: String, start: Int, globals: NativePointer, locals: NativePointer): NativePointer? // 수동 추가

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is a simplified interface to "Py_CompileStringFlags()" below,
 * leaving *flags* set to "NULL".
 */
expect fun Py_CompileString(str: String, filename: String, start: Int): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is a simplified interface to "PyEval_EvalCodeEx()", with just
 * the code object, and global and local variables.  The other
 * arguments are set to "NULL".
 */
expect fun PyEval_EvalCode(co: NativePointer, globals: NativePointer, locals: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 3
// Exception Handling
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Clear the error indicator.  If the error indicator is not set,
 * there is no effect.
 */
expect inline fun PyErr_Clear()

/**
 *  *Part of the Stable ABI.*
 *
 * Print a standard traceback to "sys.stderr" and clear the error
 * indicator. **Unless** the error is a "SystemExit", in that case no
 * traceback is printed and the Python process will exit with the
 * error code specified by the "SystemExit" instance.
 *
 * Call this function **only** when the error indicator is set.
 * Otherwise it will cause a fatal error!
 *
 * If *set_sys_last_vars* is nonzero, the variable "sys.last_exc" is
 * set to the printed exception. For backwards compatibility, the
 * deprecated variables "sys.last_type", "sys.last_value" and
 * "sys.last_traceback" are also set to the type, value and traceback
 * of this exception, respectively.
 *
 * Changed in version 3.12: The setting of "sys.last_exc" was added.
 */
expect inline fun PyErr_PrintEx(set_sys_last_vars: Int)

/**
 *  *Part of the Stable ABI.*
 *
 * Alias for "PyErr_PrintEx(1)".
 */
expect inline fun PyErr_Print()

/**
 *  *Part of the Stable ABI.*
 *
 * Call "sys.unraisablehook()" using the current exception and *obj*
 * argument.
 *
 * This utility function prints a warning message to "sys.stderr" when
 * an exception has been set but it is impossible for the interpreter
 * to actually raise the exception.  It is used, for example, when an
 * exception occurs in an "__del__()" method.
 *
 * The function is called with a single argument *obj* that identifies
 * the context in which the unraisable exception occurred. If
 * possible, the repr of *obj* will be printed in the warning message.
 * If *obj* is "NULL", only the traceback is printed.
 *
 * An exception must be set when calling this function.
 *
 * Changed in version 3.4: Print a traceback. Print only traceback if
 * *obj* is "NULL".
 *
 * Changed in version 3.8: Use "sys.unraisablehook()".
 */
expect inline fun PyErr_WriteUnraisable(obj: NativePointer)

/**
 *  *Part of the Stable ABI since version 3.12.*
 *
 * Print the standard traceback display of "exc" to "sys.stderr",
 * including chained exceptions and notes.
 *
 * Added in version 3.12.
 */
expect inline fun PyErr_DisplayException(exc: NativePointer)

/**
 *  *Part of the Stable ABI.*
 *
 * This is the most common way to set the error indicator.  The first
 * argument specifies the exception type; it is normally one of the
 * standard exceptions, e.g. "PyExc_RuntimeError".  You need not
 * create a new *strong reference* to it (e.g. with "Py_INCREF()").
 * The second argument is an error message; it is decoded from
 * "'utf-8'".
 */
expect inline fun PyErr_SetString(type: NativePointer, message: String)

/**
 *  *Part of the Stable ABI.*
 *
 * This function is similar to "PyErr_SetString()" but lets you
 * specify an arbitrary Python object for the “value” of the
 * exception.
 */
expect inline fun PyErr_SetObject(type: NativePointer, value: NativePointer)

/**
 *  *Part of the Stable ABI.*
 *
 * This is a shorthand for "PyErr_SetObject(type, Py_None)".
 */
expect inline fun PyErr_SetNone(type: NativePointer)

/**
 *  *Part of the Stable ABI.*
 *
 * This is a shorthand for "PyErr_SetString(PyExc_TypeError,
 * message)", where *message* indicates that a built-in operation was
 * invoked with an illegal argument.  It is mostly for internal use.
 */
expect inline fun PyErr_BadArgument(): Int

/**
 *  *Return value: Always NULL.*
 *  *Part of the Stable ABI.*
 *
 * This is a shorthand for "PyErr_SetNone(PyExc_MemoryError)"; it
 * returns "NULL" so an object allocation function can write "return
 * PyErr_NoMemory();" when it runs out of memory.
 */
expect fun PyErr_NoMemory(): NativePointer?

/**
 *  *Return value: Always NULL.*
 *  *Part of the Stable ABI.*
 *
 * This is a convenience function to raise an exception when a C
 * library function has returned an error and set the C variable
 * "errno".  It constructs a tuple object whose first item is the
 * integer "errno" value and whose second item is the corresponding
 * error message (gotten from "strerror()"), and then calls
 * "PyErr_SetObject(type, object)".  On Unix, when the "errno" value
 * is "EINTR", indicating an interrupted system call, this calls
 * "PyErr_CheckSignals()", and if that set the error indicator, leaves
 * it set to that.  The function always returns "NULL", so a wrapper
 * function around a system call can write "return
 * PyErr_SetFromErrno(type);" when the system call returns an error.
 */
expect fun PyErr_SetFromErrno(type: NativePointer): NativePointer?

/**
 *  *Return value: Always NULL.*
 *  *Part of the Stable ABI.*
 *
 * Similar to "PyErr_SetFromErrno()", with the additional behavior
 * that if *filenameObject* is not "NULL", it is passed to the
 * constructor of *type* as a third parameter.  In the case of
 * "OSError" exception, this is used to define the "filename"
 * attribute of the exception instance.
 */
expect fun PyErr_SetFromErrnoWithFilenameObject(type: NativePointer, filenameObject: NativePointer): NativePointer?

/**
 *  *Return value: Always NULL.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Similar to "PyErr_SetFromErrnoWithFilenameObject()", but takes a
 * second filename object, for raising errors when a function that
 * takes two filenames fails.
 *
 * Added in version 3.4.
 */
expect fun PyErr_SetFromErrnoWithFilenameObjects(type: NativePointer, filenameObject: NativePointer, filenameObject2: NativePointer): NativePointer?

/**
 *  *Return value: Always NULL.*
 *  *Part of the Stable ABI.*
 *
 * Similar to "PyErr_SetFromErrnoWithFilenameObject()", but the
 * filename is given as a C string.  *filename* is decoded from the
 * *filesystem encoding and error handler*.
 */
expect fun PyErr_SetFromErrnoWithFilename(type: NativePointer, filename: String): NativePointer?

/**
 *  *Return value: Always NULL.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * This is a convenience function to raise "ImportError". *msg* will
 * be set as the exception’s message string. *name* and *path*, both
 * of which can be "NULL", will be set as the "ImportError"’s
 * respective "name" and "path" attributes.
 *
 * Added in version 3.3.
 */
expect fun PyErr_SetImportError(msg: NativePointer, name: NativePointer, path: NativePointer): NativePointer?

/**
 *  *Return value: Always NULL.*
 *  *Part of the Stable ABI since version 3.6.*
 *
 * Much like "PyErr_SetImportError()" but this function allows for
 * specifying a subclass of "ImportError" to raise.
 *
 * Added in version 3.6.
 */
expect fun PyErr_SetImportErrorSubclass(exception: NativePointer, msg: NativePointer, name: NativePointer, path: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Like "PyErr_SyntaxLocationObject()", but *filename* is a byte
 * string decoded from the *filesystem encoding and error handler*.
 *
 * Added in version 3.2.
 */
expect inline fun PyErr_SyntaxLocationEx(filename: String, lineno: Int, col_offset: Int)

/**
 *  *Part of the Stable ABI.*
 *
 * Like "PyErr_SyntaxLocationEx()", but the *col_offset* parameter is
 * omitted.
 */
expect inline fun PyErr_SyntaxLocation(filename: String, lineno: Int)

/**
 *  *Part of the Stable ABI.*
 *
 * This is a shorthand for "PyErr_SetString(PyExc_SystemError,
 * message)", where *message* indicates that an internal operation
 * (e.g. a Python/C API function) was invoked with an illegal
 * argument.  It is mostly for internal use.
 */
expect inline fun PyErr_BadInternalCall()

/**
 *  *Part of the Stable ABI.*
 *
 * Similar to "PyErr_WarnExplicitObject()" except that *message* and
 * *module* are UTF-8 encoded strings, and *filename* is decoded from
 * the *filesystem encoding and error handler*.
 */
expect inline fun PyErr_WarnExplicit(category: NativePointer, message: String, filename: String, lineno: Int, module: String, registry: NativePointer): Int

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Test whether the error indicator is set.  If set, return the
 * exception *type* (the first argument to the last call to one of the
 * "PyErr_Set*" functions or to "PyErr_Restore()").  If not set,
 * return "NULL".  You do not own a reference to the return value, so
 * you do not need to "Py_DECREF()" it.
 *
 * The caller must hold the GIL.
 *
 * Note:
 *
 *   Do not compare the return value to a specific exception; use
 *   "PyErr_ExceptionMatches()" instead, shown below.  (The comparison
 *   could easily fail since the exception may be an instance instead
 *   of a class, in the case of a class exception, or it may be a
 *   subclass of the expected exception.)
 */
expect fun PyErr_Occurred(): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Equivalent to "PyErr_GivenExceptionMatches(PyErr_Occurred(), exc)".
 * This should only be called when an exception is actually set; a
 * memory access violation will occur if no exception has been raised.
 */
expect inline fun PyErr_ExceptionMatches(exc: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Return true if the *given* exception matches the exception type in
 * *exc*.  If *exc* is a class object, this also returns true when
 * *given* is an instance of a subclass.  If *exc* is a tuple, all
 * exception types in the tuple (and recursively in subtuples) are
 * searched for a match.
 */
expect inline fun PyErr_GivenExceptionMatches(given: NativePointer, exc: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.12.*
 *
 * Return the exception currently being raised, clearing the error
 * indicator at the same time. Return "NULL" if the error indicator is
 * not set.
 *
 * This function is used by code that needs to catch exceptions, or
 * code that needs to save and restore the error indicator
 * temporarily.
 *
 * For example:
 *
 *    {
 *       PyObject *exc = PyErr_GetRaisedException();
 *
 *       /* ... code that might produce other errors ... */
 *
 *       PyErr_SetRaisedException(exc);
 *    }
 *
 * See also:
 *
 *   "PyErr_GetHandledException()", to save the exception currently
 *   being handled.
 *
 * Added in version 3.12.
 */
expect fun PyErr_GetRaisedException(): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.12.*
 *
 * Set *exc* as the exception currently being raised, clearing the
 * existing exception if one is set.
 *
 * Warning:
 *
 *   This call steals a reference to *exc*, which must be a valid
 *   exception.
 *
 * Added in version 3.12.
 */
expect inline fun PyErr_SetRaisedException(exc: NativePointer)

/**
 *  *Part of the Stable ABI.*
 *
 * Deprecated since version 3.12: Use "PyErr_SetRaisedException()"
 * instead.
 *
 * Set the error indicator from the three objects, *type*, *value*,
 * and *traceback*, clearing the existing exception if one is set. If
 * the objects are "NULL", the error indicator is cleared.  Do not
 * pass a "NULL" type and non-"NULL" value or traceback.  The
 * exception type should be a class.  Do not pass an invalid exception
 * type or value. (Violating these rules will cause subtle problems
 * later.)  This call takes away a reference to each object: you must
 * own a reference to each object before the call and after the call
 * you no longer own these references.  (If you don’t understand this,
 * don’t use this function.  I warned you.)
 *
 * Note:
 *
 *   This function is normally only used by legacy code that needs to
 *   save and restore the error indicator temporarily. Use
 *   "PyErr_Fetch()" to save the current error indicator.
 *
 * Kept in the surface rather than deleted, because [PyErr_SetRaisedException] is not an alias for
 * it: that one takes a single, already-normalized exception instance, while this takes the
 * unnormalized `(type, value, traceback)` triple and normalizes lazily. Nothing in Kotlin can
 * forward one to the other without inventing the normalization, so removing this would remove a
 * capability rather than a duplicate. CPython names no removal version for it either.
 */
@Deprecated(
    "Deprecated by CPython 3.12 in favour of PyErr_SetRaisedException, which takes one " +
        "normalized exception instead of the (type, value, traceback) triple. Not a mechanical " +
        "substitution: this call steals a reference to each of the three arguments.",
    level = DeprecationLevel.WARNING,
)
expect inline fun PyErr_Restore(type: NativePointer, value: NativePointer, traceback: NativePointer)

/**
 *  *Part of the Stable ABI since version 3.11.*
 *
 * Retrieve the active exception instance, as would be returned by
 * "sys.exception()". This refers to an exception that was *already
 * caught*, not to an exception that was freshly raised. Returns a new
 * reference to the exception or "NULL". Does not modify the
 * interpreter’s exception state.
 *
 * Note:
 *
 *   This function is not normally used by code that wants to handle
 *   exceptions. Rather, it can be used when code needs to save and
 *   restore the exception state temporarily.  Use
 *   "PyErr_SetHandledException()" to restore or clear the exception
 *   state.
 *
 * Added in version 3.11.
 */
expect fun PyErr_GetHandledException(): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.11.*
 *
 * Set the active exception, as known from "sys.exception()".  This
 * refers to an exception that was *already caught*, not to an
 * exception that was freshly raised. To clear the exception state,
 * pass "NULL".
 *
 * Note:
 *
 *   This function is not normally used by code that wants to handle
 *   exceptions. Rather, it can be used when code needs to save and
 *   restore the exception state temporarily.  Use
 *   "PyErr_GetHandledException()" to get the exception state.
 *
 * Added in version 3.11.
 */
expect inline fun PyErr_SetHandledException(exc: NativePointer)

/**
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Set the exception info, as known from "sys.exc_info()".  This
 * refers to an exception that was *already caught*, not to an
 * exception that was freshly raised.  This function steals the
 * references of the arguments. To clear the exception state, pass
 * "NULL" for all three arguments. This function is kept for backwards
 * compatibility. Prefer using "PyErr_SetHandledException()".
 *
 * Note:
 *
 *   This function is not normally used by code that wants to handle
 *   exceptions. Rather, it can be used when code needs to save and
 *   restore the exception state temporarily.  Use
 *   "PyErr_GetExcInfo()" to read the exception state.
 *
 * Added in version 3.3.
 *
 * Changed in version 3.11: The "type" and "traceback" arguments are
 * no longer used and can be NULL. The interpreter now derives them
 * from the exception instance (the "value" argument). The function
 * still steals references of all three arguments.
 */
expect inline fun PyErr_SetExcInfo(type: NativePointer, value: NativePointer, traceback: NativePointer)

/**
 *  *Part of the Stable ABI.*
 *
 * This function interacts with Python’s signal handling.
 *
 * If the function is called from the main thread and under the main
 * Python interpreter, it checks whether a signal has been sent to the
 * processes and if so, invokes the corresponding signal handler.  If
 * the "signal" module is supported, this can invoke a signal handler
 * written in Python.
 *
 * The function attempts to handle all pending signals, and then
 * returns "0". However, if a Python signal handler raises an
 * exception, the error indicator is set and the function returns "-1"
 * immediately (such that other pending signals may not have been
 * handled yet: they will be on the next "PyErr_CheckSignals()"
 * invocation).
 *
 * If the function is called from a non-main thread, or under a non-
 * main Python interpreter, it does nothing and returns "0".
 *
 * This function can be called by long-running C code that wants to be
 * interruptible by user requests (such as by pressing Ctrl-C).
 *
 * Note:
 *
 *   The default Python signal handler for "SIGINT" raises the
 *   "KeyboardInterrupt" exception.
 */
expect inline fun PyErr_CheckSignals(): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Simulate the effect of a "SIGINT" signal arriving. This is
 * equivalent to "PyErr_SetInterruptEx(SIGINT)".
 *
 * Note:
 *
 *   This function is async-signal-safe.  It can be called without the
 *   *GIL* and from a C signal handler.
 */
expect inline fun PyErr_SetInterrupt()

/**
 *  *Part of the Stable ABI since version 3.10.*
 *
 * Simulate the effect of a signal arriving. The next time
 * "PyErr_CheckSignals()" is called,  the Python signal handler for
 * the given signal number will be called.
 *
 * This function can be called by C code that sets up its own signal
 * handling and wants Python signal handlers to be invoked as expected
 * when an interruption is requested (for example when the user
 * presses Ctrl-C to interrupt an operation).
 *
 * If the given signal isn’t handled by Python (it was set to
 * "signal.SIG_DFL" or "signal.SIG_IGN"), it will be ignored.
 *
 * If *signum* is outside of the allowed range of signal numbers, "-1"
 * is returned.  Otherwise, "0" is returned.  The error indicator is
 * never changed by this function.
 *
 * Note:
 *
 *   This function is async-signal-safe.  It can be called without the
 *   *GIL* and from a C signal handler.
 *
 * Added in version 3.10.
 */
expect inline fun PyErr_SetInterruptEx(signum: Int): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This utility function creates and returns a new exception class.
 * The *name* argument must be the name of the new exception, a C
 * string of the form "module.classname".  The *base* and *dict*
 * arguments are normally "NULL". This creates a class object derived
 * from "Exception" (accessible in C as "PyExc_Exception").
 *
 * The "__module__" attribute of the new class is set to the first
 * part (up to the last dot) of the *name* argument, and the class
 * name is set to the last part (after the last dot).  The *base*
 * argument can be used to specify alternate base classes; it can
 * either be only one class or a tuple of classes. The *dict* argument
 * can be used to specify a dictionary of class variables and methods.
 */
expect fun PyErr_NewException(name: String, base: NativePointer, dict: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Same as "PyErr_NewException()", except that the new exception class
 * can easily be given a docstring: If *doc* is non-"NULL", it will be
 * used as the docstring for the exception class.
 *
 * Added in version 3.2.
 */
expect fun PyErr_NewExceptionWithDoc(name: String, doc: String, base: NativePointer, dict: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the traceback associated with the exception as a new
 * reference, as accessible from Python through the "__traceback__"
 * attribute. If there is no traceback associated, this returns
 * "NULL".
 */
expect fun PyException_GetTraceback(ex: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Set the traceback associated with the exception to *tb*.  Use
 * "Py_None" to clear it.
 */
expect inline fun PyException_SetTraceback(ex: NativePointer, tb: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the context (another exception instance during whose
 * handling *ex* was raised) associated with the exception as a new
 * reference, as accessible from Python through the "__context__"
 * attribute. If there is no context associated, this returns "NULL".
 */
expect fun PyException_GetContext(ex: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Set the context associated with the exception to *ctx*.  Use "NULL"
 * to clear it.  There is no type check to make sure that *ctx* is an
 * exception instance. This steals a reference to *ctx*.
 */
expect inline fun PyException_SetContext(ex: NativePointer, ctx: NativePointer)

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the cause (either an exception instance, or "None", set by
 * "raise ... from ...") associated with the exception as a new
 * reference, as accessible from Python through the "__cause__"
 * attribute.
 */
expect fun PyException_GetCause(ex: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Set the cause associated with the exception to *cause*.  Use "NULL"
 * to clear it.  There is no type check to make sure that *cause* is
 * either an exception instance or "None".  This steals a reference to
 * *cause*.
 *
 * The "__suppress_context__" attribute is implicitly set to "True" by
 * this function.
 */
expect inline fun PyException_SetCause(ex: NativePointer, cause: NativePointer)

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.12.*
 *
 * Return "args" of exception *ex*.
 */
expect fun PyException_GetArgs(ex: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.12.*
 *
 * Set "args" of exception *ex* to *args*.
 */
expect inline fun PyException_SetArgs(ex: NativePointer, args: NativePointer)

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the *encoding* attribute of the given exception object.
 */
expect fun PyUnicodeEncodeError_GetEncoding(exc: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the *object* attribute of the given exception object.
 */
expect fun PyUnicodeTranslateError_GetObject(exc: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the *reason* attribute of the given exception object.
 */
expect fun PyUnicodeTranslateError_GetReason(exc: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Set the *reason* attribute of the given exception object to
 * *reason*.  Return "0" on success, "-1" on failure.
 */
expect inline fun PyUnicodeTranslateError_SetReason(exc: NativePointer, reason: String): Int

/**
 *  *Part of the Stable ABI since version 3.9.*
 *
 * Marks a point where a recursive C-level call is about to be
 * performed.
 *
 * If "USE_STACKCHECK" is defined, this function checks if the OS
 * stack overflowed using "PyOS_CheckStack()".  If this is the case,
 * it sets a "MemoryError" and returns a nonzero value.
 *
 * The function then checks if the recursion limit is reached.  If
 * this is the case, a "RecursionError" is set and a nonzero value is
 * returned. Otherwise, zero is returned.
 *
 * *where* should be a UTF-8 encoded string such as "" in instance
 * check"" to be concatenated to the "RecursionError" message caused
 * by the recursion depth limit.
 *
 * Changed in version 3.9: This function is now also available in the
 * limited API.
 */
expect inline fun Py_EnterRecursiveCall(where: String): Int

/**
 *  *Part of the Stable ABI since version 3.9.*
 *
 * Ends a "Py_EnterRecursiveCall()".  Must be called once for each
 * *successful* invocation of "Py_EnterRecursiveCall()".
 *
 * Changed in version 3.9: This function is now also available in the
 * limited API.
 */
expect inline fun Py_LeaveRecursiveCall()

/**
 *  *Part of the Stable ABI.*
 *
 * Called at the beginning of the "tp_repr" implementation to detect
 * cycles.
 *
 * If the object has already been processed, the function returns a
 * positive integer.  In that case the "tp_repr" implementation should
 * return a string object indicating a cycle.  As examples, "dict"
 * objects return "{...}" and "list" objects return "[...]".
 *
 * The function will return a negative integer if the recursion limit
 * is reached.  In that case the "tp_repr" implementation should
 * typically return "NULL".
 *
 * Otherwise, the function returns zero and the "tp_repr"
 * implementation can continue normally.
 */
expect inline fun Py_ReprEnter(o: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Ends a "Py_ReprEnter()".  Must be called once for each invocation
 */
expect inline fun Py_ReprLeave(o: NativePointer)


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 4
// Reference Counting
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI since version 3.10.*
 *
 * Create a new *strong reference* to an object: call "Py_INCREF()" on
 * *o* and return the object *o*.
 *
 * When the *strong reference* is no longer needed, "Py_DECREF()"
 * should be called on it to release the reference.
 *
 * The object *o* must not be "NULL"; use "Py_XNewRef()" if *o* can be
 * "NULL".
 *
 * For example:
 *
 *    Py_INCREF(obj);
 *    self->attr = obj;
 *
 * can be written as:
 *
 *    self->attr = Py_NewRef(obj);
 *
 * See also "Py_INCREF()".
 *
 * Added in version 3.10.
 */
expect fun Py_NewRef(o: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.10.*
 *
 * Similar to "Py_NewRef()", but the object *o* can be NULL.
 *
 * If the object *o* is "NULL", the function just returns "NULL".
 *
 * Added in version 3.10.
 */
expect fun Py_XNewRef(o: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Indicate taking a new *strong reference* to object *o*. A function
 * version of "Py_XINCREF()". It can be used for runtime dynamic
 * embedding of Python.
 */
expect inline fun Py_IncRef(o: NativePointer)

/**
 *  *Part of the Stable ABI.*
 *
 * Release a *strong reference* to object *o*. A function version of
 * "Py_XDECREF()". It can be used for runtime dynamic embedding of
 */
expect inline fun Py_DecRef(o: NativePointer)


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 5
// Operating System Utilities
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.6.*
 *
 * Return the file system representation for *path*. If the object is
 * a "str" or "bytes" object, then a new *strong reference* is
 * returned. If the object implements the "os.PathLike" interface,
 * then "__fspath__()" is returned as long as it is a "str" or "bytes"
 * object. Otherwise "TypeError" is raised and "NULL" is returned.
 *
 * Added in version 3.6.
 */
expect fun PyOS_FSPath(path: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 6
// System Functions
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the object *name* from the "sys" module or "NULL" if it does
 * not exist, without setting an exception.
 */
expect fun PySys_GetObject(name: String): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Set *name* in the "sys" module to *v* unless *v* is "NULL", in
 * which case *name* is deleted from the sys module. Returns "0" on
 * success, "-1" on error.
 */
expect inline fun PySys_SetObject(name: String, v: NativePointer): Int

// `PySys_ResetWarnOptions` was bound here until CPython 3.15 removed it. It has no replacement
// in the C API: the documented migration is to configure `sys.warnoptions` through `PyConfig`
// before initialisation, or to clear `sys.warnoptions` and `warnings.filters` from Python.
// The symbol is still *exported* by the 3.15 shared library and only gone from the headers, so
// desktop (which resolves by name at run time) never noticed; cinterop and the hand-written C
// in `jni_onload.def` compile against the headers, so the native targets did. See ROADMAP §9.

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Return the current dictionary of "-X" options, similarly to
 * "sys._xoptions".  On error, "NULL" is returned and an exception is
 * set.
 *
 * Added in version 3.2.
 */
expect fun PySys_GetXOptions(): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Similar to "PySys_Audit()", but pass arguments as a Python object.
 * *args* must be a "tuple". To pass no arguments, *args* can be
 * *NULL*.
 *
 * Added in version 3.13.
 */
expect inline fun PySys_AuditTuple(event: String, args: NativePointer): Int


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 7
// Process Control
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Print a fatal error message and kill the process.  No cleanup is
 * performed. This function should only be invoked when a condition is
 * detected that would make it dangerous to continue using the Python
 * interpreter; e.g., when the object administration appears to be
 * corrupted.  On Unix, the standard C library function "abort()" is
 * called which will attempt to produce a "core" file.
 *
 * The "Py_FatalError()" function is replaced with a macro which logs
 * automatically the name of the current function, unless the
 * "Py_LIMITED_API" macro is defined.
 *
 * Changed in version 3.9: Log the function name automatically.
 */
expect inline fun Py_FatalError(message: String)

/**
 *  *Part of the Stable ABI.*
 *
 * Exit the current process.  This calls "Py_FinalizeEx()" and then
 * calls the standard C library function "exit(status)".  If
 * "Py_FinalizeEx()" indicates an error, the exit status is set to
 * 120.
 *
 * Changed in version 3.6: Errors from finalization no longer ignored.
 */
expect inline fun Py_Exit(status: Int)


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 8
// Importing Modules
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is a wrapper around "PyImport_Import()" which takes a const
 * char* as an argument instead of a PyObject*.
 */
expect fun PyImport_ImportModule(name: String): NativePointer?

/**
 *  *Return value: New reference.*
 *
 * A deprecated alias of [PyImport_ImportModule].
 *
 * Changed in version 3.3: This function used to fail immediately when
 * the import lock was held by another thread.  In Python 3.3 though,
 * the locking scheme switched to per-module locks for most purposes,
 * so this function’s special behaviour isn’t needed anymore.
 *
 * Deprecated since version 3.13, removed in version 3.15: use
 * [PyImport_ImportModule] instead.
 *
 * This is no longer bound to a C symbol. CPython 3.15 removed the entry point, and since 3.3
 * it had been an exact alias of `PyImport_ImportModule` — not a distinct code path — so
 * forwarding here is behaviour-preserving on every supported version rather than an
 * approximation. Keeping the name costs nothing at the FFI boundary and lets existing source
 * compile through the version bump; `EmbedApiLowLevelTest` pins it to the function it aliases.
 */
@Deprecated(
    "Removed in CPython 3.15; it has been an exact alias of PyImport_ImportModule since 3.3.",
    ReplaceWith("PyImport_ImportModule(name)")
)
fun PyImport_ImportModuleNoBlock(name: String): NativePointer? = PyImport_ImportModule(name)

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Import a module.  This is best described by referring to the built-
 * in Python function "__import__()", as the standard "__import__()"
 * function calls this function directly.
 *
 * The return value is a new reference to the imported module or top-
 * level package, or "NULL" with an exception set on failure.  Like
 * for "__import__()", the return value when a submodule of a package
 * was requested is normally the top-level package, unless a non-empty
 * *fromlist* was given.
 *
 * Added in version 3.3.
 */
expect fun PyImport_ImportModuleLevelObject(name: NativePointer, globals: NativePointer, locals: NativePointer, fromlist: NativePointer, level: Int): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Similar to "PyImport_ImportModuleLevelObject()", but the name is a
 * UTF-8 encoded string instead of a Unicode object.
 *
 * Changed in version 3.3: Negative values for *level* are no longer
 * accepted.
 */
expect fun PyImport_ImportModuleLevel(name: String, globals: NativePointer, locals: NativePointer, fromlist: NativePointer, level: Int): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is a higher-level interface that calls the current “import
 * hook function” (with an explicit *level* of 0, meaning absolute
 * import).  It invokes the "__import__()" function from the
 * "__builtins__" of the current globals.  This means that the import
 * is done using whatever import hooks are installed in the current
 * environment.
 *
 * This function always uses absolute imports.
 */
expect fun PyImport_Import(name: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Reload a module.  Return a new reference to the reloaded module, or
 * "NULL" with an exception set on failure (the module still exists in
 * this case).
 */
expect fun PyImport_ReloadModule(m: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return the module object corresponding to a module name.
 *
 * The *name* argument may be of the form "package.module". First
 * check the modules dictionary if there’s one there, and if not,
 * create a new one and insert it in the modules dictionary.
 *
 * Return a *strong reference* to the module on success. Return "NULL"
 * with an exception set on failure.
 *
 * The module name *name* is decoded from UTF-8.
 *
 * This function does not load or import the module; if the module
 * wasn’t already loaded, you will get an empty module object. Use
 * "PyImport_ImportModule()" or one of its variants to import a
 * module. Package structures implied by a dotted name for *name* are
 * not created if not already present.
 *
 * Added in version 3.13.
 */
expect fun PyImport_AddModuleRef(name: String): NativePointer?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Similar to "PyImport_AddModuleRef()", but return a *borrowed
 * reference* and *name* is a Python "str" object.
 *
 * Added in version 3.3.
 */
expect fun PyImport_AddModuleObject(name: NativePointer): NativePointer?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Similar to "PyImport_AddModuleRef()", but return a *borrowed
 * reference*.
 */
expect fun PyImport_AddModule(name: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Given a module name (possibly of the form "package.module") and a
 * code object read from a Python bytecode file or obtained from the
 * built-in function "compile()", load the module.  Return a new
 * reference to the module object, or "NULL" with an exception set if
 * an error occurred.  *name* is removed from "sys.modules" in error
 * cases, even if *name* was already in "sys.modules" on entry to
 * "PyImport_ExecCodeModule()".  Leaving incompletely initialized
 * modules in "sys.modules" is dangerous, as imports of such modules
 * have no way to know that the module object is an unknown (and
 * probably damaged with respect to the module author’s intents)
 * state.
 *
 * The module’s "__spec__" and "__loader__" will be set, if not set
 * already, with the appropriate values.  The spec’s loader will be
 * set to the module’s "__loader__" (if set) and to an instance of
 * "SourceFileLoader" otherwise.
 *
 * The module’s "__file__" attribute will be set to the code object’s
 * "co_filename".  If applicable, "__cached__" will also be set.
 *
 * This function will reload the module if it was already imported.
 * See "PyImport_ReloadModule()" for the intended way to reload a
 * module.
 *
 * If *name* points to a dotted name of the form "package.module", any
 * package structures not already created will still not be created.
 *
 * See also "PyImport_ExecCodeModuleEx()" and
 * "PyImport_ExecCodeModuleWithPathnames()".
 *
 * Changed in version 3.12: The setting of "__cached__" and
 * "__loader__" is deprecated. See "ModuleSpec" for alternatives.
 */
expect fun PyImport_ExecCodeModule(name: String, co: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Like "PyImport_ExecCodeModule()", but the "__file__" attribute of
 * the module object is set to *pathname* if it is non-"NULL".
 *
 * See also "PyImport_ExecCodeModuleWithPathnames()".
 */
expect fun PyImport_ExecCodeModuleEx(name: String, co: NativePointer, pathname: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Like "PyImport_ExecCodeModuleEx()", but the "__cached__" attribute
 * of the module object is set to *cpathname* if it is non-"NULL".  Of
 * the three functions, this is the preferred one to use.
 *
 * Added in version 3.3.
 *
 * Changed in version 3.12: Setting "__cached__" is deprecated. See
 * "ModuleSpec" for alternatives.
 */
expect fun PyImport_ExecCodeModuleObject(name: NativePointer, co: NativePointer, pathname: NativePointer, cpathname: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Like "PyImport_ExecCodeModuleObject()", but *name*, *pathname* and
 * *cpathname* are UTF-8 encoded strings. Attempts are also made to
 * figure out what the value for *pathname* should be from *cpathname*
 * if the former is set to "NULL".
 *
 * Added in version 3.2.
 *
 * Changed in version 3.3: Uses "imp.source_from_cache()" in
 * calculating the source path if only the bytecode path is provided.
 *
 * Changed in version 3.12: No longer uses the removed "imp" module.
 */
expect fun PyImport_ExecCodeModuleWithPathnames(name: String, co: NativePointer, pathname: String, cpathname: String): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the magic tag string for **PEP 3147** format Python bytecode
 * file names.  Keep in mind that the value at
 * "sys.implementation.cache_tag" is authoritative and should be used
 * instead of this function.
 *
 * Added in version 3.2.
 */
expect inline fun PyImport_GetMagicTag(): String?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the dictionary used for the module administration (a.k.a.
 * "sys.modules").  Note that this is a per-interpreter variable.
 */
expect fun PyImport_GetModuleDict(): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.8.*
 *
 * Return the already imported module with the given name.  If the
 * module has not been imported yet then returns "NULL" but does not
 * set an error.  Returns "NULL" and sets an error if the lookup
 * failed.
 *
 * Added in version 3.7.
 */
expect fun PyImport_GetModule(name: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a finder object for a "sys.path"/"pkg.__path__" item *path*,
 * possibly by fetching it from the "sys.path_importer_cache" dict.
 * If it wasn’t yet cached, traverse "sys.path_hooks" until a hook is
 * found that can handle the path item.  Return "None" if no hook
 * could; this tells our caller that the *path based finder* could not
 * find a finder for this path item. Cache the result in
 * "sys.path_importer_cache". Return a new reference to the finder
 * object.
 */
expect fun PyImport_GetImporter(path: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Load a frozen module named *name*.  Return "1" for success, "0" if
 * the module is not found, and "-1" with an exception set if the
 * initialization failed.  To access the imported module on a
 * successful load, use "PyImport_ImportModule()".  (Note the misnomer
 * — this function would reload the module if it was already
 * imported.)
 *
 * Added in version 3.3.
 *
 * Changed in version 3.4: The "__file__" attribute is no longer set
 * on the module.
 */
expect inline fun PyImport_ImportFrozenModuleObject(name: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Similar to "PyImport_ImportFrozenModuleObject()", but the name is a
 * UTF-8 encoded string instead of a Unicode object.
 */
expect inline fun PyImport_ImportFrozenModule(name: String): Int


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 9
// Reflection
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Deprecated since version 3.13: Use "PyEval_GetFrameBuiltins()"
 * instead.
 *
 * Return a dictionary of the builtins in the current execution frame,
 * or the interpreter of the thread state if no frame is currently
 * executing.
 *
 * Kept in the surface rather than deleted, because PyEval_GetFrameBuiltins is **not** a drop-in replacement: this
 * function returns a *borrowed* reference and PyEval_GetFrameBuiltins returns a *new* one. Substituting it without
 * also adding the matching decref moves ownership silently, and this repository has already had a
 * heap corrupted once by a borrowed pointer wrapped as owned. The migration is a real change at
 * each call site, so the declaration stays and carries this warning until those call sites move.
 */
@Deprecated(
    "Deprecated by CPython 3.13 in favour of PyEval_GetFrameBuiltins. Do NOT substitute blindly: this returns a " +
        "BORROWED reference and PyEval_GetFrameBuiltins returns a NEW one, so the replacement needs a decref " +
        "this one must not have.",
    level = DeprecationLevel.WARNING,
)
expect fun PyEval_GetBuiltins(): NativePointer?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Deprecated since version 3.13: Use either "PyEval_GetFrameLocals()"
 * to obtain the same behaviour as calling "locals()" in Python code,
 * or else call "PyFrame_GetLocals()" on the result of
 * "PyEval_GetFrame()" to access the "f_locals" attribute of the
 * currently executing frame.
 *
 * Return a mapping providing access to the local variables in the
 * current execution frame, or "NULL" if no frame is currently
 * executing.
 *
 * Refer to "locals()" for details of the mapping returned at
 * different scopes.
 *
 * As this function returns a *borrowed reference*, the dictionary
 * returned for *optimized scopes* is cached on the frame object and
 * will remain alive as long as the frame object does. Unlike
 * "PyEval_GetFrameLocals()" and "locals()", subsequent calls to this
 * function in the same frame will update the contents of the cached
 * dictionary to reflect changes in the state of the local variables
 * rather than returning a new snapshot.
 *
 * Changed in version 3.13: As part of **PEP 667**,
 * "PyFrame_GetLocals()", "locals()", and "FrameType.f_locals" no
 * longer make use of the shared cache dictionary. Refer to the What’s
 * New entry for additional details.
 *
 * Kept in the surface rather than deleted, because PyEval_GetFrameLocals is **not** a drop-in replacement: this
 * function returns a *borrowed* reference and PyEval_GetFrameLocals returns a *new* one. Substituting it without
 * also adding the matching decref moves ownership silently, and this repository has already had a
 * heap corrupted once by a borrowed pointer wrapped as owned. The migration is a real change at
 * each call site, so the declaration stays and carries this warning until those call sites move.
 */
@Deprecated(
    "Deprecated by CPython 3.13 in favour of PyEval_GetFrameLocals. Do NOT substitute blindly: this returns a " +
        "BORROWED reference and PyEval_GetFrameLocals returns a NEW one, so the replacement needs a decref " +
        "this one must not have.",
    level = DeprecationLevel.WARNING,
)
expect fun PyEval_GetLocals(): NativePointer?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Deprecated since version 3.13: Use "PyEval_GetFrameGlobals()"
 * instead.
 *
 * Return a dictionary of the global variables in the current
 * execution frame, or "NULL" if no frame is currently executing.
 *
 * Kept in the surface rather than deleted, because PyEval_GetFrameGlobals is **not** a drop-in replacement: this
 * function returns a *borrowed* reference and PyEval_GetFrameGlobals returns a *new* one. Substituting it without
 * also adding the matching decref moves ownership silently, and this repository has already had a
 * heap corrupted once by a borrowed pointer wrapped as owned. The migration is a real change at
 * each call site, so the declaration stays and carries this warning until those call sites move.
 */
@Deprecated(
    "Deprecated by CPython 3.13 in favour of PyEval_GetFrameGlobals. Do NOT substitute blindly: this returns a " +
        "BORROWED reference and PyEval_GetFrameGlobals returns a NEW one, so the replacement needs a decref " +
        "this one must not have.",
    level = DeprecationLevel.WARNING,
)
expect fun PyEval_GetGlobals(): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return a dictionary of the builtins in the current execution frame,
 * or the interpreter of the thread state if no frame is currently
 * executing.
 *
 * Added in version 3.13.
 */
expect fun PyEval_GetFrameBuiltins(): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return a dictionary of the local variables in the current execution
 * frame, or "NULL" if no frame is currently executing. Equivalent to
 * calling "locals()" in Python code.
 *
 * To access "f_locals" on the current frame without making an
 * independent snapshot in *optimized scopes*, call
 * "PyFrame_GetLocals()" on the result of "PyEval_GetFrame()".
 *
 * Added in version 3.13.
 */
expect fun PyEval_GetFrameLocals(): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return a dictionary of the global variables in the current
 * execution frame, or "NULL" if no frame is currently executing.
 * Equivalent to calling "globals()" in Python code.
 *
 * Added in version 3.13.
 */
expect fun PyEval_GetFrameGlobals(): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the name of *func* if it is a function, class or instance
 * object, else the name of *func*s type.
 */
expect inline fun PyEval_GetFuncName(func: NativePointer): String?

/**
 *  *Part of the Stable ABI.*
 *
 * Return a description string, depending on the type of *func*.
 * Return values include “()” for functions and methods, “
 * constructor”, “ instance”, and “ object”.  Concatenated with the
 * result of "PyEval_GetFuncName()", the result will be a description
 */
expect inline fun PyEval_GetFuncDesc(func: NativePointer): String?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 10
// Object Protocol
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Returns "1" if *o* has the attribute *attr_name*, and "0"
 * otherwise. This is equivalent to the Python expression "hasattr(o,
 * attr_name)". On failure, return "-1".
 *
 * Added in version 3.13.
 */
expect inline fun PyObject_HasAttrWithError(o: NativePointer, attr_name: NativePointer): Int

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * This is the same as "PyObject_HasAttrWithError()", but *attr_name*
 * is specified as a const char* UTF-8 encoded bytes string, rather
 * than a PyObject*.
 *
 * Added in version 3.13.
 */
expect inline fun PyObject_HasAttrStringWithError(o: NativePointer, attr_name: String): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Returns "1" if *o* has the attribute *attr_name*, and "0"
 * otherwise. This function always succeeds.
 *
 * Note:
 *
 *   Exceptions that occur when this calls "__getattr__()" and
 *   "__getattribute__()" methods are silently ignored. For proper
 *   error handling, use "PyObject_HasAttrWithError()",
 *   "PyObject_GetOptionalAttr()" or "PyObject_GetAttr()" instead.
 */
expect inline fun PyObject_HasAttr(o: NativePointer, attr_name: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyObject_HasAttr()", but *attr_name* is
 * specified as a const char* UTF-8 encoded bytes string, rather than
 * a PyObject*.
 *
 * Note:
 *
 *   Exceptions that occur when this calls "__getattr__()" and
 *   "__getattribute__()" methods or while creating the temporary
 *   "str" object are silently ignored. For proper error handling, use
 *   "PyObject_HasAttrStringWithError()",
 *   "PyObject_GetOptionalAttrString()" or "PyObject_GetAttrString()"
 *   instead.
 */
expect inline fun PyObject_HasAttrString(o: NativePointer, attr_name: String): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Retrieve an attribute named *attr_name* from object *o*. Returns
 * the attribute value on success, or "NULL" on failure.  This is the
 * equivalent of the Python expression "o.attr_name".
 *
 * If the missing attribute should not be treated as a failure, you
 * can use "PyObject_GetOptionalAttr()" instead.
 */
expect fun PyObject_GetAttr(o: NativePointer, attr_name: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyObject_GetAttr()", but *attr_name* is
 * specified as a const char* UTF-8 encoded bytes string, rather than
 * a PyObject*.
 *
 * If the missing attribute should not be treated as a failure, you
 * can use "PyObject_GetOptionalAttrString()" instead.
 */
expect fun PyObject_GetAttrString(o: NativePointer, attr_name: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Generic attribute getter function that is meant to be put into a
 * type object’s "tp_getattro" slot.  It looks for a descriptor in the
 * dictionary of classes in the object’s MRO as well as an attribute
 * in the object’s "__dict__" (if present).  As outlined in
 * Implementing Descriptors, data descriptors take preference over
 * instance attributes, while non-data descriptors don’t.  Otherwise,
 * an "AttributeError" is raised.
 */
expect fun PyObject_GenericGetAttr(o: NativePointer, name: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Set the value of the attribute named *attr_name*, for object *o*,
 * to the value *v*. Raise an exception and return "-1" on failure;
 * return "0" on success.  This is the equivalent of the Python
 * statement "o.attr_name = v".
 *
 * If *v* is "NULL", the attribute is deleted. This behaviour is
 * deprecated in favour of using "PyObject_DelAttr()", but there are
 * currently no plans to remove it.
 */
expect inline fun PyObject_SetAttr(o: NativePointer, attr_name: NativePointer, v: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyObject_SetAttr()", but *attr_name* is
 * specified as a const char* UTF-8 encoded bytes string, rather than
 * a PyObject*.
 *
 * If *v* is "NULL", the attribute is deleted, but this feature is
 * deprecated in favour of using "PyObject_DelAttrString()".
 *
 * The number of different attribute names passed to this function
 * should be kept small, usually by using a statically allocated
 * string as *attr_name*. For attribute names that aren’t known at
 * compile time, prefer calling "PyUnicode_FromString()" and
 * "PyObject_SetAttr()" directly. For more details, see
 * "PyUnicode_InternFromString()", which may be used internally to
 * create a key object.
 */
expect inline fun PyObject_SetAttrString(o: NativePointer, attr_name: String, v: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Generic attribute setter and deleter function that is meant to be
 * put into a type object’s "tp_setattro" slot.  It looks for a data
 * descriptor in the dictionary of classes in the object’s MRO, and if
 * found it takes preference over setting or deleting the attribute in
 * the instance dictionary. Otherwise, the attribute is set or deleted
 * in the object’s "__dict__" (if present). On success, "0" is
 * returned, otherwise an "AttributeError" is raised and "-1" is
 * returned.
 */
expect inline fun PyObject_GenericSetAttr(o: NativePointer, name: NativePointer, value: NativePointer): Int

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Delete attribute named *attr_name*, for object *o*. Returns "-1" on
 * failure. This is the equivalent of the Python statement "del
 * o.attr_name".
 */
expect inline fun PyObject_DelAttr(o: NativePointer, attr_name: NativePointer): Int

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * This is the same as "PyObject_DelAttr()", but *attr_name* is
 * specified as a const char* UTF-8 encoded bytes string, rather than
 * a PyObject*.
 *
 * The number of different attribute names passed to this function
 * should be kept small, usually by using a statically allocated
 * string as *attr_name*. For attribute names that aren’t known at
 * compile time, prefer calling "PyUnicode_FromString()" and
 * "PyObject_DelAttr()" directly. For more details, see
 * "PyUnicode_InternFromString()", which may be used internally to
 * create a key object for lookup.
 */
expect inline fun PyObject_DelAttrString(o: NativePointer, attr_name: String): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Compare the values of *o1* and *o2* using the operation specified
 * by *opid*, which must be one of "Py_LT", "Py_LE", "Py_EQ", "Py_NE",
 * "Py_GT", or "Py_GE", corresponding to "<", "<=", "==", "!=", ">",
 * or ">=" respectively. This is the equivalent of the Python
 * expression "o1 op o2", where "op" is the operator corresponding to
 * *opid*. Returns the value of the comparison on success, or "NULL"
 * on failure.
 */
expect fun PyObject_RichCompare(o1: NativePointer, o2: NativePointer, opid: Int): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Compare the values of *o1* and *o2* using the operation specified
 * by *opid*, like "PyObject_RichCompare()", but returns "-1" on
 * error, "0" if the result is false, "1" otherwise.
 */
expect inline fun PyObject_RichCompareBool(o1: NativePointer, o2: NativePointer, opid: Int): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Format *obj* using *format_spec*. This is equivalent to the Python
 * expression "format(obj, format_spec)".
 *
 * *format_spec* may be "NULL". In this case the call is equivalent to
 * "format(obj)". Returns the formatted string on success, "NULL" on
 * failure.
 */
expect fun PyObject_Format(obj: NativePointer, format_spec: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Compute a string representation of object *o*.  Returns the string
 * representation on success, "NULL" on failure.  This is the
 * equivalent of the Python expression "repr(o)".  Called by the
 * "repr()" built-in function.
 *
 * Changed in version 3.4: This function now includes a debug
 * assertion to help ensure that it does not silently discard an
 * active exception.
 */
expect fun PyObject_Repr(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * As "PyObject_Repr()", compute a string representation of object
 * *o*, but escape the non-ASCII characters in the string returned by
 * "PyObject_Repr()" with "\x", "\u" or "\U" escapes.  This generates
 * a string similar to that returned by "PyObject_Repr()" in Python 2.
 * Called by the "ascii()" built-in function.
 */
expect fun PyObject_ASCII(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Compute a string representation of object *o*.  Returns the string
 * representation on success, "NULL" on failure.  This is the
 * equivalent of the Python expression "str(o)".  Called by the
 * "str()" built-in function and, therefore, by the "print()"
 * function.
 *
 * Changed in version 3.4: This function now includes a debug
 * assertion to help ensure that it does not silently discard an
 * active exception.
 */
expect fun PyObject_Str(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Compute a bytes representation of object *o*.  "NULL" is returned
 * on failure and a bytes object on success.  This is equivalent to
 * the Python expression "bytes(o)", when *o* is not an integer.
 * Unlike "bytes(o)", a TypeError is raised when *o* is an integer
 * instead of a zero-initialized bytes object.
 */
expect fun PyObject_Bytes(o: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if the class *derived* is identical to or derived from
 * the class *cls*, otherwise return "0".  In case of an error, return
 * "-1".
 *
 * If *cls* is a tuple, the check will be done against every entry in
 * *cls*. The result will be "1" when at least one of the checks
 * returns "1", otherwise it will be "0".
 *
 * If *cls* has a "__subclasscheck__()" method, it will be called to
 * determine the subclass status as described in **PEP 3119**.
 * Otherwise, *derived* is a subclass of *cls* if it is a direct or
 * indirect subclass, i.e. contained in "cls.__mro__".
 *
 * Normally only class objects, i.e. instances of "type" or a derived
 * class, are considered classes.  However, objects can override this
 * by having a "__bases__" attribute (which must be a tuple of base
 * classes).
 */
expect inline fun PyObject_IsSubclass(derived: NativePointer, cls: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if *inst* is an instance of the class *cls* or a
 * subclass of *cls*, or "0" if not.  On error, returns "-1" and sets
 * an exception.
 *
 * If *cls* is a tuple, the check will be done against every entry in
 * *cls*. The result will be "1" when at least one of the checks
 * returns "1", otherwise it will be "0".
 *
 * If *cls* has a "__instancecheck__()" method, it will be called to
 * determine the subclass status as described in **PEP 3119**.
 * Otherwise, *inst* is an instance of *cls* if its class is a
 * subclass of *cls*.
 *
 * An instance *inst* can override what is considered its class by
 * having a "__class__" attribute.
 *
 * An object *cls* can override if it is considered a class, and what
 * its base classes are, by having a "__bases__" attribute (which must
 * be a tuple of base classes).
 */
expect inline fun PyObject_IsInstance(inst: NativePointer, cls: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Returns "1" if the object *o* is considered to be true, and "0"
 * otherwise. This is equivalent to the Python expression "not not o".
 * On failure, return "-1".
 */
expect inline fun PyObject_IsTrue(o: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Returns "0" if the object *o* is considered to be true, and "1"
 * otherwise. This is equivalent to the Python expression "not o".  On
 * failure, return "-1".
 */
expect inline fun PyObject_Not(o: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * When *o* is non-"NULL", returns a type object corresponding to the
 * object type of object *o*. On failure, raises "SystemError" and
 * returns "NULL".  This is equivalent to the Python expression
 * "type(o)". This function creates a new *strong reference* to the
 * return value. There’s really no reason to use this function instead
 * of the "Py_TYPE()" function, which returns a pointer of type
 * PyTypeObject*, except when a new *strong reference* is needed.
 */
expect fun PyObject_Type(o: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the length of object *o*.  If the object *o* provides
 * both sequence and mapping protocols, the sequence length is
 * returned. On error, "-1" is returned.  This is the equivalent
 * to the Python expression "len(o)".
 *
 * Note: replaces the `__len__` protocol bridge (`GetAttrString("__len__")`
 * + `CallNoArgs`) this project used before this binding existed --
 * one FFI crossing instead of two, with no bound-method object to
 * allocate and release.
 */
expect inline fun PyObject_Size(o: NativePointer): Long

/**
 *  *Part of the Stable ABI.*
 *
 * A synonym for "PyObject_Size()", kept for source compatibility
 * with older CPython versions that used this name instead. A
 * distinct exported symbol at the C level, but behaviourally
 * identical to "PyObject_Size()".
 */
expect inline fun PyObject_Length(o: NativePointer): Long

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return element of *o* corresponding to the object *key* or "NULL"
 * on failure. This is the equivalent of the Python expression
 * "o[key]".
 */
expect fun PyObject_GetItem(o: NativePointer, key: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Map the object *key* to the value *v*.  Raise an exception and
 * return "-1" on failure; return "0" on success.  This is the
 * equivalent of the Python statement "o[key] = v".  This function
 * *does not* steal a reference to *v*.
 */
expect inline fun PyObject_SetItem(o: NativePointer, key: NativePointer, v: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Remove the mapping for the object *key* from the object *o*.
 * Return "-1" on failure.  This is equivalent to the Python statement
 * "del o[key]".
 */
expect inline fun PyObject_DelItem(o: NativePointer, key: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is equivalent to the Python expression "dir(o)", returning a
 * (possibly empty) list of strings appropriate for the object
 * argument, or "NULL" if there was an error.  If the argument is
 * "NULL", this is like the Python "dir()", returning the names of the
 * current locals; in this case, if no execution frame is active then
 * "NULL" is returned but "PyErr_Occurred()" will return false.
 */
expect fun PyObject_Dir(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is equivalent to the Python expression "iter(o)". It returns a
 * new iterator for the object argument, or the object  itself if the
 * object is already an iterator.  Raises "TypeError" and returns
 * "NULL" if the object cannot be iterated.
 */
expect fun PyObject_GetIter(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.10.*
 *
 * This is the equivalent to the Python expression "aiter(o)". Takes
 * an "AsyncIterable" object and returns an "AsyncIterator" for it.
 * This is typically a new iterator but if the argument is an
 * "AsyncIterator", this returns itself. Raises "TypeError" and
 * returns "NULL" if the object cannot be iterated.
 *
 * Added in version 3.10.
 */
expect fun PyObject_GetAIter(o: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 11
// Call Protocol
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI since version 3.12.*
 *
 * Call *callable*’s "vectorcallfunc" with positional and keyword
 * arguments given in a tuple and dict, respectively.
 *
 * This is a specialized function, intended to be put in the "tp_call"
 * slot or be used in an implementation of "tp_call". It does not
 * check the "Py_TPFLAGS_HAVE_VECTORCALL" flag and it does not fall
 * back to "tp_call".
 *
 * Added in version 3.8.
 */
expect fun PyVectorcall_Call(callable: NativePointer, tuple: NativePointer, dict: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Call a callable Python object *callable*, with arguments given by
 * the tuple *args*, and named arguments given by the dictionary
 * *kwargs*.
 *
 * *args* must not be *NULL*; use an empty tuple if no arguments are
 * needed. If no named arguments are needed, *kwargs* can be *NULL*.
 *
 * Return the result of the call on success, or raise an exception and
 * return *NULL* on failure.
 *
 * This is the equivalent of the Python expression: "callable(*args,
 * **kwargs)".
 */
expect fun PyObject_Call(callable: NativePointer, args: NativePointer, kwargs: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.10.*
 *
 * Call a callable Python object *callable* without any arguments. It
 * is the most efficient way to call a callable Python object without
 * any argument.
 *
 * Return the result of the call on success, or raise an exception and
 * return *NULL* on failure.
 *
 * Added in version 3.9.
 */
expect fun PyObject_CallNoArgs(callable: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Call a callable Python object *callable*, with arguments given by
 * the tuple *args*.  If no arguments are needed, then *args* can be
 * *NULL*.
 *
 * Return the result of the call on success, or raise an exception and
 * return *NULL* on failure.
 *
 * This is the equivalent of the Python expression: "callable(*args)".
 */
expect fun PyObject_CallObject(callable: NativePointer, args: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Determine if the object *o* is callable.  Return "1" if the object
 */
expect inline fun PyCallable_Check(o: NativePointer): Int


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 12
// Number Protocol
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Returns "1" if the object *o* provides numeric protocols, and false
 * otherwise. This function always succeeds.
 *
 * Changed in version 3.8: Returns "1" if *o* is an index integer.
 */
expect inline fun PyNumber_Check(o: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of adding *o1* and *o2*, or "NULL" on failure.
 * This is the equivalent of the Python expression "o1 + o2".
 */
expect fun PyNumber_Add(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of subtracting *o2* from *o1*, or "NULL" on
 * failure.  This is the equivalent of the Python expression "o1 -
 * o2".
 */
expect fun PyNumber_Subtract(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of multiplying *o1* and *o2*, or "NULL" on
 * failure.  This is the equivalent of the Python expression "o1 *
 * o2".
 */
expect fun PyNumber_Multiply(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Returns the result of matrix multiplication on *o1* and *o2*, or
 * "NULL" on failure.  This is the equivalent of the Python expression
 * "o1 @ o2".
 *
 * Added in version 3.5.
 */
expect fun PyNumber_MatrixMultiply(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the floor of *o1* divided by *o2*, or "NULL" on failure.
 * This is the equivalent of the Python expression "o1 // o2".
 */
expect fun PyNumber_FloorDivide(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a reasonable approximation for the mathematical value of
 * *o1* divided by *o2*, or "NULL" on failure.  The return value is
 * “approximate” because binary floating-point numbers are
 * approximate; it is not possible to represent all real numbers in
 * base two.  This function can return a floating-point value when
 * passed two integers.  This is the equivalent of the Python
 * expression "o1 / o2".
 */
expect fun PyNumber_TrueDivide(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the remainder of dividing *o1* by *o2*, or "NULL" on
 * failure.  This is the equivalent of the Python expression "o1 %
 * o2".
 */
expect fun PyNumber_Remainder(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * See the built-in function "divmod()". Returns "NULL" on failure.
 * This is the equivalent of the Python expression "divmod(o1, o2)".
 */
expect fun PyNumber_Divmod(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * See the built-in function "pow()". Returns "NULL" on failure.  This
 * is the equivalent of the Python expression "pow(o1, o2, o3)", where
 * *o3* is optional. If *o3* is to be ignored, pass "Py_None" in its
 * place (passing "NULL" for *o3* would cause an illegal memory
 * access).
 */
expect fun PyNumber_Power(o1: NativePointer, o2: NativePointer, o3: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the negation of *o* on success, or "NULL" on failure. This
 * is the equivalent of the Python expression "-o".
 */
expect fun PyNumber_Negative(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns *o* on success, or "NULL" on failure.  This is the
 * equivalent of the Python expression "+o".
 */
expect fun PyNumber_Positive(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the absolute value of *o*, or "NULL" on failure.  This is
 * the equivalent of the Python expression "abs(o)".
 */
expect fun PyNumber_Absolute(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the bitwise negation of *o* on success, or "NULL" on
 * failure.  This is the equivalent of the Python expression "~o".
 */
expect fun PyNumber_Invert(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of left shifting *o1* by *o2* on success, or
 * "NULL" on failure.  This is the equivalent of the Python expression
 * "o1 << o2".
 */
expect fun PyNumber_Lshift(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of right shifting *o1* by *o2* on success, or
 * "NULL" on failure.  This is the equivalent of the Python expression
 * "o1 >> o2".
 */
expect fun PyNumber_Rshift(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the “bitwise and” of *o1* and *o2* on success and "NULL" on
 * failure. This is the equivalent of the Python expression "o1 & o2".
 */
expect fun PyNumber_And(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the “bitwise exclusive or” of *o1* by *o2* on success, or
 * "NULL" on failure.  This is the equivalent of the Python expression
 * "o1 ^ o2".
 */
expect fun PyNumber_Xor(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the “bitwise or” of *o1* and *o2* on success, or "NULL" on
 * failure. This is the equivalent of the Python expression "o1 | o2".
 */
expect fun PyNumber_Or(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of adding *o1* and *o2*, or "NULL" on failure.
 * The operation is done *in-place* when *o1* supports it.  This is
 * the equivalent of the Python statement "o1 += o2".
 */
expect fun PyNumber_InPlaceAdd(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of subtracting *o2* from *o1*, or "NULL" on
 * failure.  The operation is done *in-place* when *o1* supports it.
 * This is the equivalent of the Python statement "o1 -= o2".
 */
expect fun PyNumber_InPlaceSubtract(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of multiplying *o1* and *o2*, or "NULL" on
 * failure.  The operation is done *in-place* when *o1* supports it.
 * This is the equivalent of the Python statement "o1 *= o2".
 */
expect fun PyNumber_InPlaceMultiply(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Returns the result of matrix multiplication on *o1* and *o2*, or
 * "NULL" on failure.  The operation is done *in-place* when *o1*
 * supports it.  This is the equivalent of the Python statement "o1 @=
 * o2".
 *
 * Added in version 3.5.
 */
expect fun PyNumber_InPlaceMatrixMultiply(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the mathematical floor of dividing *o1* by *o2*, or "NULL"
 * on failure. The operation is done *in-place* when *o1* supports it.
 * This is the equivalent of the Python statement "o1 //= o2".
 */
expect fun PyNumber_InPlaceFloorDivide(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a reasonable approximation for the mathematical value of
 * *o1* divided by *o2*, or "NULL" on failure.  The return value is
 * “approximate” because binary floating-point numbers are
 * approximate; it is not possible to represent all real numbers in
 * base two.  This function can return a floating-point value when
 * passed two integers.  The operation is done *in-place* when *o1*
 * supports it. This is the equivalent of the Python statement "o1 /=
 * o2".
 */
expect fun PyNumber_InPlaceTrueDivide(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the remainder of dividing *o1* by *o2*, or "NULL" on
 * failure.  The operation is done *in-place* when *o1* supports it.
 * This is the equivalent of the Python statement "o1 %= o2".
 */
expect fun PyNumber_InPlaceRemainder(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * See the built-in function "pow()". Returns "NULL" on failure.  The
 * operation is done *in-place* when *o1* supports it.  This is the
 * equivalent of the Python statement "o1 **= o2" when o3 is
 * "Py_None", or an in-place variant of "pow(o1, o2, o3)" otherwise.
 * If *o3* is to be ignored, pass "Py_None" in its place (passing
 * "NULL" for *o3* would cause an illegal memory access).
 */
expect fun PyNumber_InPlacePower(o1: NativePointer, o2: NativePointer, o3: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of left shifting *o1* by *o2* on success, or
 * "NULL" on failure.  The operation is done *in-place* when *o1*
 * supports it.  This is the equivalent of the Python statement "o1
 * <<= o2".
 */
expect fun PyNumber_InPlaceLshift(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the result of right shifting *o1* by *o2* on success, or
 * "NULL" on failure.  The operation is done *in-place* when *o1*
 * supports it.  This is the equivalent of the Python statement "o1
 * >>= o2".
 */
expect fun PyNumber_InPlaceRshift(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the “bitwise and” of *o1* and *o2* on success and "NULL" on
 * failure. The operation is done *in-place* when *o1* supports it.
 * This is the equivalent of the Python statement "o1 &= o2".
 */
expect fun PyNumber_InPlaceAnd(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the “bitwise exclusive or” of *o1* by *o2* on success, or
 * "NULL" on failure.  The operation is done *in-place* when *o1*
 * supports it.  This is the equivalent of the Python statement "o1 ^=
 * o2".
 */
expect fun PyNumber_InPlaceXor(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the “bitwise or” of *o1* and *o2* on success, or "NULL" on
 * failure.  The operation is done *in-place* when *o1* supports it.
 * This is the equivalent of the Python statement "o1 |= o2".
 */
expect fun PyNumber_InPlaceOr(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the *o* converted to an integer object on success, or
 * "NULL" on failure.  This is the equivalent of the Python expression
 * "int(o)".
 */
expect fun PyNumber_Long(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the *o* converted to a float object on success, or "NULL"
 * on failure. This is the equivalent of the Python expression
 * "float(o)".
 */
expect fun PyNumber_Float(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the *o* converted to a Python int on success or "NULL" with
 * a "TypeError" exception raised on failure.
 *
 * Changed in version 3.10: The result always has exact type "int".
 * Previously, the result could have been an instance of a subclass of
 * "int".
 */
expect fun PyNumber_Index(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Returns the integer *n* converted to base *base* as a string.  The
 * *base* argument must be one of 2, 8, 10, or 16.  For base 2, 8, or
 * 16, the returned string is prefixed with a base marker of "'0b'",
 * "'0o'", or "'0x'", respectively.  If *n* is not a Python int, it is
 * converted with "PyNumber_Index()" first.
 */
expect fun PyNumber_ToBase(n: NativePointer, base: Int): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.8.*
 *
 * Returns "1" if *o* is an index integer (has the "nb_index" slot of
 * the "tp_as_number" structure filled in), and "0" otherwise. This
 */
expect inline fun PyIndex_Check(o: NativePointer): Int


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 13
// Sequence Protocol
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if the object provides the sequence protocol, and "0"
 * otherwise. Note that it returns "1" for Python classes with a
 * "__getitem__()" method, unless they are "dict" subclasses, since in
 * general it is impossible to determine what type of keys the class
 * supports.  This function always succeeds.
 */
expect inline fun PySequence_Check(o: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the concatenation of *o1* and *o2* on success, and "NULL" on
 * failure. This is the equivalent of the Python expression "o1 + o2".
 */
expect fun PySequence_Concat(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the concatenation of *o1* and *o2* on success, and "NULL" on
 * failure. The operation is done *in-place* when *o1* supports it.
 * This is the equivalent of the Python expression "o1 += o2".
 */
expect fun PySequence_InPlaceConcat(o1: NativePointer, o2: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Determine if *o* contains *value*.  If an item in *o* is equal to
 * *value*, return "1", otherwise return "0". On error, return "-1".
 * This is equivalent to the Python expression "value in o".
 */
expect inline fun PySequence_Contains(o: NativePointer, value: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a list object with the same contents as the sequence or
 * iterable *o*, or "NULL" on failure.  The returned list is
 * guaranteed to be new.  This is equivalent to the Python expression
 * "list(o)".
 */
expect fun PySequence_List(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a tuple object with the same contents as the sequence or
 * iterable *o*, or "NULL" on failure.  If *o* is a tuple, a new
 * reference will be returned, otherwise a tuple will be constructed
 * with the appropriate contents.  This is equivalent to the Python
 * expression "tuple(o)".
 */
expect fun PySequence_Tuple(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the sequence or iterable *o* as an object usable by the
 * other "PySequence_Fast*" family of functions. If the object is not
 * a sequence or iterable, raises "TypeError" with *m* as the message
 * text. Returns "NULL" on failure.
 *
 * The "PySequence_Fast*" functions are thus named because they assume
 * *o* is a "PyTupleObject" or a "PyListObject" and access the data
 * fields of *o* directly.
 *
 * As a CPython implementation detail, if *o* is already a sequence or
 * list, it will be returned.
 */
expect fun PySequence_Fast(o: NativePointer, m: String): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 14
// Mapping Protocol
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if the object provides the mapping protocol or supports
 * slicing, and "0" otherwise.  Note that it returns "1" for Python
 * classes with a "__getitem__()" method, since in general it is
 * impossible to determine what type of keys the class supports. This
 * function always succeeds.
 */
expect inline fun PyMapping_Check(o: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyObject_GetItem()", but *key* is specified as
 * a const char* UTF-8 encoded bytes string, rather than a PyObject*.
 */
expect fun PyMapping_GetItemString(o: NativePointer, key: String): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyObject_SetItem()", but *key* is specified as
 * a const char* UTF-8 encoded bytes string, rather than a PyObject*.
 */
expect inline fun PyMapping_SetItemString(o: NativePointer, key: String, v: NativePointer): Int

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return "1" if the mapping object has the key *key* and "0"
 * otherwise. This is equivalent to the Python expression "key in o".
 * On failure, return "-1".
 *
 * Added in version 3.13.
 */
expect inline fun PyMapping_HasKeyWithError(o: NativePointer, key: NativePointer): Int

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * This is the same as "PyMapping_HasKeyWithError()", but *key* is
 * specified as a const char* UTF-8 encoded bytes string, rather than
 * a PyObject*.
 *
 * Added in version 3.13.
 */
expect inline fun PyMapping_HasKeyStringWithError(o: NativePointer, key: String): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if the mapping object has the key *key* and "0"
 * otherwise. This is equivalent to the Python expression "key in o".
 * This function always succeeds.
 *
 * Note:
 *
 *   Exceptions which occur when this calls "__getitem__()" method are
 *   silently ignored. For proper error handling, use
 *   "PyMapping_HasKeyWithError()", "PyMapping_GetOptionalItem()" or
 *   "PyObject_GetItem()" instead.
 */
expect inline fun PyMapping_HasKey(o: NativePointer, key: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyMapping_HasKey()", but *key* is specified as
 * a const char* UTF-8 encoded bytes string, rather than a PyObject*.
 *
 * Note:
 *
 *   Exceptions that occur when this calls "__getitem__()" method or
 *   while creating the temporary "str" object are silently ignored.
 *   For proper error handling, use
 *   "PyMapping_HasKeyStringWithError()",
 *   "PyMapping_GetOptionalItemString()" or
 *   "PyMapping_GetItemString()" instead.
 */
expect inline fun PyMapping_HasKeyString(o: NativePointer, key: String): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * On success, return a list of the keys in object *o*.  On failure,
 * return "NULL".
 *
 * Changed in version 3.7: Previously, the function returned a list or
 * a tuple.
 */
expect fun PyMapping_Keys(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * On success, return a list of the values in object *o*.  On failure,
 * return "NULL".
 *
 * Changed in version 3.7: Previously, the function returned a list or
 * a tuple.
 */
expect fun PyMapping_Values(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * On success, return a list of the items in object *o*, where each
 * item is a tuple containing a key-value pair.  On failure, return
 * "NULL".
 *
 * Changed in version 3.7: Previously, the function returned a list or
 */
expect fun PyMapping_Items(o: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 15
// Iterator Protocol
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI since version 3.8.*
 *
 * Return non-zero if the object *o* can be safely passed to
 * "PyIter_Next()", and "0" otherwise.  This function always succeeds.
 */
expect inline fun PyIter_Check(o: NativePointer): Int

/**
 *  *Part of the Stable ABI since version 3.10.*
 *
 * Return non-zero if the object *o* provides the "AsyncIterator"
 * protocol, and "0" otherwise.  This function always succeeds.
 *
 * Added in version 3.10.
 */
expect inline fun PyAIter_Check(o: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the next value from the iterator *o*.  The object must be an
 * iterator according to "PyIter_Check()" (it is up to the caller to
 * check this). If there are no remaining values, returns "NULL" with
 * no exception set. If an error occurs while retrieving the item,
 * returns "NULL" and passes along the exception.
 */
expect fun PyIter_Next(o: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 27
// Type Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Return true if *a* is a subtype of *b*.
 *
 * This function only checks for actual subtypes, which means that
 * "__subclasscheck__()" is not called on *b*.  Call
 * "PyObject_IsSubclass()" to do the same check that "issubclass()"
 * would do.
 */
expect inline fun PyType_IsSubtype(a: NativePointer, b: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Finalize a type object.  This should be called on all type objects
 * to finish their initialization.  This function is responsible for
 * adding inherited slots from a type’s base class.  Return "0" on
 * success, or return "-1" and sets an exception on error.
 *
 * Note:
 *
 *   If some of the base classes implements the GC protocol and the
 *   provided type does not include the "Py_TPFLAGS_HAVE_GC" in its
 *   flags, then the GC protocol will be automatically implemented
 *   from its parents. On the contrary, if the type being created does
 *   include "Py_TPFLAGS_HAVE_GC" in its flags then it **must**
 *   implement the GC protocol itself by at least implementing the
 *   "tp_traverse" handle.
*/
expect inline fun PyType_Ready(type: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.11.*
 *
 * Return the type’s name. Equivalent to getting the type’s "__name__"
 * attribute.
 *
 * Added in version 3.11.
 */
expect fun PyType_GetName(type: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return the type’s fully qualified name. Equivalent to
 * "f"{type.__module__}.{type.__qualname__}"", or "type.__qualname__"
 * if "type.__module__" is not a string or is equal to ""builtins"".
 *
 * Added in version 3.13.
 */
expect fun PyType_GetFullyQualifiedName(type: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Return the type’s module name. Equivalent to getting the
 * "type.__module__" attribute.
 *
 * Added in version 3.13.
 */
expect fun PyType_GetModuleName(type: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.10.*
 *
 * Return the module object associated with the given type when the
 * type was created using "PyType_FromModuleAndSpec()".
 *
 * If no module is associated with the given type, sets "TypeError"
 * and returns "NULL".
 *
 * This function is usually used to get the module in which a method
 * is defined. Note that in such a method,
 * "PyType_GetModule(Py_TYPE(self))" may not return the intended
 * result. "Py_TYPE(self)" may be a *subclass* of the intended class,
 * and subclasses are not necessarily defined in the same module as
 * their superclass. See "PyCMethod" to get the class that defines the
 * method. See "PyType_GetModuleByDef()" for cases when "PyCMethod"
 * cannot be used.
 *
 * Added in version 3.9.
 */
expect fun PyType_GetModule(type: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 16
// Integer Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new "PyLongObject" object from a C long long, or "NULL" on
 * failure.
 */
expect fun PyLong_FromLongLong(v: Long): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new "PyLongObject" object from the integer part of *v*, or
 * "NULL" on failure.
 */
expect fun PyLong_FromDouble(v: Double): NativePointer?

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Similar to "PyLong_AsLong()", but store the result in a C int
 * instead of a C long.
 *
 * Added in version 3.13.
 */
expect inline fun PyLong_AsInt(obj: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Return a C long long representation of *obj*.  If *obj* is not an
 * instance of "PyLongObject", first call its "__index__()" method (if
 * present) to convert it to a "PyLongObject".
 *
 * Raise "OverflowError" if the value of *obj* is out of range for a
 * long long.
 *
 * Returns "-1" on error.  Use "PyErr_Occurred()" to disambiguate.
 *
 * Changed in version 3.8: Use "__index__()" if available.
 *
 * Changed in version 3.10: This function will no longer use
 * "__int__()".
 */
expect inline fun PyLong_AsLongLong(obj: NativePointer): Long

/**
 *  *Part of the Stable ABI.*
 *
 * Return a C double representation of *pylong*.  *pylong* must be an
 * instance of "PyLongObject".
 *
 * Raise "OverflowError" if the value of *pylong* is out of range for
 * a double.
 *
 * Returns "-1.0" on error.  Use "PyErr_Occurred()" to disambiguate.
 */
expect inline fun PyLong_AsDouble(pylong: NativePointer): Double

/**
 *  *Part of the Stable ABI.*
 *
 * On success, return a read only *named tuple*, that holds
 * information about Python’s internal representation of integers. See
 * "sys.int_info" for description of individual fields.
 *
 * On failure, return "NULL" with an exception set.
 *
 * Added in version 3.1.
 */
expect fun PyLong_GetInfo(): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 17
// Boolean Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return "Py_True" or "Py_False", depending on the truth value of
 */
expect fun PyBool_FromLong(v: Int): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 18
// Floating-Point Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Create a "PyFloatObject" object based on the string value in *str*,
 * or "NULL" on failure.
 */
expect fun PyFloat_FromString(str: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Create a "PyFloatObject" object from *v*, or "NULL" on failure.
 */
expect fun PyFloat_FromDouble(v: Double): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return a C double representation of the contents of *pyfloat*.  If
 * *pyfloat* is not a Python floating-point object but has a
 * "__float__()" method, this method will first be called to convert
 * *pyfloat* into a float. If "__float__()" is not defined then it
 * falls back to "__index__()". This method returns "-1.0" upon
 * failure, so one should call "PyErr_Occurred()" to check for errors.
 *
 * Changed in version 3.8: Use "__index__()" if available.
 */
expect inline fun PyFloat_AsDouble(pyfloat: NativePointer): Double

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a structseq instance which contains information about the
 * precision, minimum and maximum values of a float. It’s a thin
 * wrapper around the header file "float.h".
 */
expect fun PyFloat_GetInfo(): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the maximum representable finite float *DBL_MAX* as C
 * double.
 */
expect inline fun PyFloat_GetMax(): Double

/**
 *  *Part of the Stable ABI.*
 *
 * Return the minimum normalized positive float *DBL_MIN* as C double.
 */
expect inline fun PyFloat_GetMin(): Double


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 19
// Bytes Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new bytes object with a copy of the string *v* as value on
 * success, and "NULL" on failure.  The parameter *v* must not be
 * "NULL"; it will not be checked.
 */
expect fun PyBytes_FromString(v: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the bytes representation of object *o* that implements the
 * buffer protocol.
 */
expect fun PyBytes_FromObject(o: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return a pointer to the contents of *o*.  The pointer refers to the
 * internal buffer of *o*, which consists of "len(o) + 1" bytes.  The
 * last byte in the buffer is always null, regardless of whether there
 * are any other null bytes.  The data must not be modified in any
 * way, unless the object was just created using
 * "PyBytes_FromStringAndSize(NULL, size)". It must not be
 * deallocated.  If *o* is not a bytes object at all,
 * "PyBytes_AsString()" returns "NULL" and raises "TypeError".
 */
expect inline fun PyBytes_AsString(o: NativePointer): String?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 20
// Byte Array Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new bytearray object from any object, *o*, that implements
 * the buffer protocol.
 *
 * On failure, return "NULL" with an exception set.
 */
expect fun PyByteArray_FromObject(o: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Concat bytearrays *a* and *b* and return a new bytearray with the
 * result.
 *
 * On failure, return "NULL" with an exception set.
 */
expect fun PyByteArray_Concat(a: NativePointer, b: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the contents of *bytearray* as a char array after checking
 * for a "NULL" pointer.  The returned array always has an extra null
 * byte appended.
 */
expect inline fun PyByteArray_AsString(bytearray: NativePointer): String?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 21
// Unicode Objects and Codecs
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if the string is a valid identifier according to the
 * language definition, section Identifiers and keywords. Return "0"
 * otherwise.
 *
 * Changed in version 3.9: The function does not call
 * "Py_FatalError()" anymore if the string is not ready.
 */
expect inline fun PyUnicode_IsIdentifier(unicode: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Create a Unicode object from a UTF-8 encoded null-terminated char
 * buffer *str*.
 */
expect fun PyUnicode_FromString(str: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Copy an instance of a Unicode subtype to a new true Unicode object
 * if necessary. If *obj* is already a true Unicode object (not a
 * subtype), return a new *strong reference* to the object.
 *
 * Objects other than Unicode or its subtypes will cause a
 * "TypeError".
 */
expect fun PyUnicode_FromObject(obj: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Decode an encoded object *obj* to a Unicode object.
 *
 * "bytes", "bytearray" and other *bytes-like objects* are decoded
 * according to the given *encoding* and using the error handling
 * defined by *errors*. Both can be "NULL" to have the interface use
 * the default values (see Built-in Codecs for details).
 *
 * All other objects, including Unicode objects, cause a "TypeError"
 * to be set.
 *
 * The API returns "NULL" if there was an error.  The caller is
 * responsible for decref’ing the returned objects.
 */
expect fun PyUnicode_FromEncodedObject(obj: NativePointer, encoding: String, errors: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Similar to "PyUnicode_DecodeLocaleAndSize()", but compute the
 * string length using "strlen()".
 *
 * Added in version 3.3.
 */
expect fun PyUnicode_DecodeLocale(str: String, errors: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.7.*
 *
 * Encode a Unicode object to UTF-8 on Android and VxWorks, or to the
 * current locale encoding on other platforms. The supported error
 * handlers are ""strict"" and ""surrogateescape"" (**PEP 383**). The
 * encoder uses ""strict"" error handler if *errors* is "NULL". Return
 * a "bytes" object. *unicode* cannot contain embedded null
 * characters.
 *
 * Use "PyUnicode_EncodeFSDefault()" to encode a string to the
 * *filesystem encoding and error handler*.
 *
 * This function ignores the Python UTF-8 Mode.
 *
 * See also: The "Py_EncodeLocale()" function.
 *
 * Added in version 3.3.
 *
 * Changed in version 3.7: The function now also uses the current
 * locale encoding for the "surrogateescape" error handler, except on
 * Android. Previously, "Py_EncodeLocale()" was used for the
 * "surrogateescape", and the current locale encoding was used for
 * "strict".
 */
expect fun PyUnicode_EncodeLocale(unicode: NativePointer, errors: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Decode a null-terminated string from the *filesystem encoding and
 * error handler*.
 *
 * If the string length is known, use
 * "PyUnicode_DecodeFSDefaultAndSize()".
 *
 * Changed in version 3.6: The *filesystem error handler* is now used.
 */
expect fun PyUnicode_DecodeFSDefault(str: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object to the *filesystem encoding and error
 * handler*, and return "bytes". Note that the resulting "bytes"
 * object can contain null bytes.
 *
 * If you need to encode a string to the current locale encoding, use
 * "PyUnicode_EncodeLocale()".
 *
 * See also: The "Py_EncodeLocale()" function.
 *
 * Added in version 3.2.
 *
 * Changed in version 3.6: The *filesystem error handler* is now used.
 */
expect fun PyUnicode_EncodeFSDefault(unicode: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object and return the result as Python bytes
 * object. *encoding* and *errors* have the same meaning as the
 * parameters of the same name in the Unicode "encode()" method. The
 * codec to be used is looked up using the Python codec registry.
 * Return "NULL" if an exception was raised by the codec.
 */
expect fun PyUnicode_AsEncodedString(unicode: NativePointer, encoding: String, errors: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object using UTF-8 and return the result as Python
 * bytes object.  Error handling is “strict”.  Return "NULL" if an
 * exception was raised by the codec.
 *
 * The function fails if the string contains surrogate code points
 * ("U+D800" - "U+DFFF").
 */
expect fun PyUnicode_AsUTF8String(unicode: NativePointer): NativePointer?

/**
 * As "PyUnicode_AsUTF8AndSize()", but does not store the size.
 *
 * Added in version 3.3.
 *
 * Changed in version 3.7: The return type is now "const char *"
 * rather of "char *".
 */
expect inline fun PyUnicode_AsUTF8(unicode: NativePointer): String? // 수동 추가

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a Python byte string using the UTF-32 encoding in native
 * byte order. The string always starts with a BOM mark.  Error
 * handling is “strict”. Return "NULL" if an exception was raised by
 * the codec.
 */
expect fun PyUnicode_AsUTF32String(unicode: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a Python byte string using the UTF-16 encoding in native
 * byte order. The string always starts with a BOM mark.  Error
 * handling is “strict”. Return "NULL" if an exception was raised by
 * the codec.
 */
expect fun PyUnicode_AsUTF16String(unicode: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object using Unicode-Escape and return the result
 * as a bytes object.  Error handling is “strict”.  Return "NULL" if
 * an exception was raised by the codec.
 */
expect fun PyUnicode_AsUnicodeEscapeString(unicode: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object using Raw-Unicode-Escape and return the
 * result as a bytes object.  Error handling is “strict”.  Return
 * "NULL" if an exception was raised by the codec.
 */
expect fun PyUnicode_AsRawUnicodeEscapeString(unicode: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object using Latin-1 and return the result as
 * Python bytes object.  Error handling is “strict”.  Return "NULL" if
 * an exception was raised by the codec.
 */
expect fun PyUnicode_AsLatin1String(unicode: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object using ASCII and return the result as Python
 * bytes object.  Error handling is “strict”.  Return "NULL" if an
 * exception was raised by the codec.
 */
expect fun PyUnicode_AsASCIIString(unicode: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Encode a Unicode object using the given *mapping* object and return
 * the result as a bytes object.  Error handling is “strict”.  Return
 * "NULL" if an exception was raised by the codec.
 *
 * The *mapping* object must map Unicode ordinal integers to bytes
 * objects, integers in the range from 0 to 255 or "None".  Unmapped
 * character ordinals (ones which cause a "LookupError") as well as
 * mapped to "None" are treated as “undefined mapping” and cause an
 * error.
 */
expect fun PyUnicode_AsCharmapString(unicode: NativePointer, mapping: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Translate a string by applying a character mapping table to it and
 * return the resulting Unicode object. Return "NULL" if an exception
 * was raised by the codec.
 *
 * The mapping table must map Unicode ordinal integers to Unicode
 * ordinal integers or "None" (causing deletion of the character).
 *
 * Mapping tables need only provide the "__getitem__()" interface;
 * dictionaries and sequences work well.  Unmapped character ordinals
 * (ones which cause a "LookupError") are left untouched and are
 * copied as-is.
 *
 * *errors* has the usual meaning for codecs. It may be "NULL" which
 * indicates to use the default error handling.
 */
expect fun PyUnicode_Translate(unicode: NativePointer, table: NativePointer, errors: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Concat two strings giving a new Unicode string.
 */
expect fun PyUnicode_Concat(left: NativePointer, right: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Split a Unicode string at line breaks, returning a list of Unicode
 * strings. CRLF is considered to be one line break.  If *keepends* is
 * "0", the Line break characters are not included in the resulting
 * strings.
 */
expect fun PyUnicode_Splitlines(unicode: NativePointer, keepends: Int): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Join a sequence of strings using the given *separator* and return
 * the resulting Unicode string.
 */
expect fun PyUnicode_Join(separator: NativePointer, seq: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Compare two strings and return "-1", "0", "1" for less than, equal,
 * and greater than, respectively.
 *
 * This function returns "-1" upon failure, so one should call
 * "PyErr_Occurred()" to check for errors.
 */
expect inline fun PyUnicode_Compare(left: NativePointer, right: NativePointer): Int

/**
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Similar to "PyUnicode_EqualToUTF8AndSize()", but compute *string*
 * length using "strlen()". If the Unicode object contains null
 * characters, false ("0") is returned.
 *
 * Added in version 3.13.
 */
expect inline fun PyUnicode_EqualToUTF8(unicode: NativePointer, string: String): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Compare a Unicode object, *unicode*, with *string* and return "-1",
 * "0", "1" for less than, equal, and greater than, respectively. It
 * is best to pass only ASCII-encoded strings, but the function
 * interprets the input string as ISO-8859-1 if it contains non-ASCII
 * characters.
 *
 * This function does not raise exceptions.
 */
expect inline fun PyUnicode_CompareWithASCIIString(unicode: NativePointer, string: String): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Rich compare two Unicode strings and return one of the following:
 *
 * * "NULL" in case an exception was raised
 *
 * * "Py_True" or "Py_False" for successful comparisons
 *
 * * "Py_NotImplemented" in case the type combination is unknown
 *
 * Possible values for *op* are "Py_GT", "Py_GE", "Py_EQ", "Py_NE",
 * "Py_LT", and "Py_LE".
 */
expect fun PyUnicode_RichCompare(left: NativePointer, right: NativePointer, op: Int): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new string object from *format* and *args*; this is
 * analogous to "format % args".
 */
expect fun PyUnicode_Format(format: NativePointer, args: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Check whether *substr* is contained in *unicode* and return true or
 * false accordingly.
 *
 * *substr* has to coerce to a one element Unicode string. "-1" is
 * returned if there was an error.
 */
expect inline fun PyUnicode_Contains(unicode: NativePointer, substr: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * A combination of "PyUnicode_FromString()" and
 * "PyUnicode_InternInPlace()", meant for statically allocated
 * strings.
 *
 * Return a new (“owned”) reference to either a new Unicode string
 * object that has been interned, or an earlier interned string object
 * with the same value.
 *
 * Python may keep a reference to the result, or make it *immortal*,
 * preventing it from being garbage-collected promptly. For interning
 * an unbounded number of different strings, such as ones coming from
 * user input, prefer calling "PyUnicode_FromString()" and
 * "PyUnicode_InternInPlace()" directly.
 *
 * **CPython implementation detail:** Strings interned this way are
 */
expect fun PyUnicode_InternFromString(str: String): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 28
// Tuple Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// NOTE: section numbers are append order, not the C API documentation's chapter order.
// Sections 1-26 follow the docs; 27 (Type Objects), 28 (Tuple Objects) and 29 (Module Objects)
// were added when they were first needed and so sit after Weak Reference Objects instead of at
// their documented positions (Type before Integer Objects, Tuple before List Objects, Module
// before Iterator Objects). Renumbering is a ~370-line pure-comment move with no behavioural
// effect, and it conflicts with anything else editing this file; the target order is recorded in
// ROADMAP §12 instead. Navigate this file by function name, not by section number.
expect fun PyTuple_New(len: Long): NativePointer?
expect inline fun PyTuple_Size(p: NativePointer): Long
expect fun PyTuple_GetItem(p: NativePointer, pos: Long): NativePointer?
expect fun PyTuple_GetSlice(p: NativePointer, low: Long, high: Long): NativePointer?
expect inline fun PyTuple_SetItem(p: NativePointer, pos: Long, o: NativePointer): Int


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 22
// List Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new list of length *len* on success, or "NULL" on
 * failure.
 *
 * Note:
 *
 *   If *len* is greater than zero, the returned list object's items
 *   are set to "NULL".  Thus you cannot use abstract API functions
 *   such as "PySequence_SetItem()" or expose the object to
 *   Python code before setting all items to a real object with
 *   "PyList_SetItem()".
 */
expect fun PyList_New(len: Long): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the length of the list object in *list*; this is the
 * equivalent of "len(list)" on a list object.
 *
 * Note: a bad argument (i.e. *list* is not a list) is undefined
 * behaviour at the C level; use "PySequence_Size()"/[PyObject_Size]
 * for arbitrary, not-necessarily-list objects.
 */
expect inline fun PyList_Size(list: NativePointer): Long

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the object at position *index* in the list *list*.  The
 * position must be non-negative; indexing from the end of the list
 * is not supported.  If *index* is out of bounds, return "NULL" and
 * set an "IndexError" exception.
 */
expect fun PyList_GetItem(list: NativePointer, index: Long): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Set the item at index *index* in list *list* to *item*.  Return
 * "0" on success. If *index* is out of bounds, return "-1" and set
 * an "IndexError" exception.
 *
 * Note:
 *
 *   This function "steals" a reference to *item* and discards a
 *   reference to an item already in the list at the affected
 *   position.
 */
expect inline fun PyList_SetItem(list: NativePointer, index: Long, item: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Insert the item *item* into list *list* in front of index
 * *index*.  Return "0" if successful; return "-1" and set an
 * exception if unsuccessful.  Analogous to "list.insert(index, item)".
 *
 * Unlike "PyList_SetItem()", this function *does not* steal a
 * reference to *item*.
 */
expect inline fun PyList_Insert(list: NativePointer, index: Long, item: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Append the object *item* at the end of list *list*. Return "0" if
 * successful; return "-1" and set an exception if unsuccessful.
 * Analogous to "list.append(item)".
 */
expect inline fun PyList_Append(list: NativePointer, item: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Sort the items of *list* in place.  Return "0" on success, "-1" on
 * failure.  This is equivalent to "list.sort()".
 */
expect inline fun PyList_Sort(list: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Reverse the items of *list* in place.  Return "0" on success, "-1"
 * on failure.  This is the equivalent of "list.reverse()".
 */
expect inline fun PyList_Reverse(list: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new tuple object containing the contents of *list*;
 */
expect fun PyList_AsTuple(list: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 23
// Dictionary Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new empty dictionary, or "NULL" on failure.
 */
expect fun PyDict_New(): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return the number of items in the dictionary. This is equivalent
 * to "len(p)" on a dictionary.
 */
expect inline fun PyDict_Size(p: NativePointer): Long

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a "types.MappingProxyType" object for a mapping which
 * enforces read-only behavior.  This is normally used to create a
 * view to prevent modification of the dictionary for non-dynamic
 * class types.
 */
expect fun PyDictProxy_New(mapping: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Empty an existing dictionary of all key-value pairs.
 */
expect inline fun PyDict_Clear(p: NativePointer)

/**
 *  *Part of the Stable ABI.*
 *
 * Determine if dictionary *p* contains *key*.  If an item in *p* is
 * matches *key*, return "1", otherwise return "0".  On error, return
 * "-1". This is equivalent to the Python expression "key in p".
 */
expect inline fun PyDict_Contains(p: NativePointer, key: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new dictionary that contains the same key-value pairs as
 * *p*.
 */
expect fun PyDict_Copy(p: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Insert *val* into the dictionary *p* with a key of *key*.  *key*
 * must be *hashable*; if it isn’t, "TypeError" will be raised. Return
 * "0" on success or "-1" on failure.  This function *does not* steal
 * a reference to *val*.
 */
expect inline fun PyDict_SetItem(p: NativePointer, key: NativePointer, v: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyDict_SetItem()", but *key* is specified as a
 * const char* UTF-8 encoded bytes string, rather than a PyObject*.
 */
expect inline fun PyDict_SetItemString(p: NativePointer, key: String, v: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Remove the entry in dictionary *p* with key *key*. *key* must be
 * *hashable*; if it isn’t, "TypeError" is raised. If *key* is not in
 * the dictionary, "KeyError" is raised. Return "0" on success or "-1"
 * on failure.
 */
expect inline fun PyDict_DelItem(p: NativePointer, key: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyDict_DelItem()", but *key* is specified as a
 * const char* UTF-8 encoded bytes string, rather than a PyObject*.
 */
expect inline fun PyDict_DelItemString(p: NativePointer, key: String): Int

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a *borrowed reference* to the object from dictionary *p*
 * which has a key *key*.  Return "NULL" if the key *key* is missing
 * *without* setting an exception.
 *
 * Note:
 *
 *   Exceptions that occur while this calls "__hash__()" and
 *   "__eq__()" methods are silently ignored. Prefer the
 *   "PyDict_GetItemWithError()" function instead.
 *
 * Changed in version 3.10: Calling this API without *GIL* held had
 * been allowed for historical reason. It is no longer allowed.
 */
expect fun PyDict_GetItem(p: NativePointer, key: NativePointer): NativePointer?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Variant of "PyDict_GetItem()" that does not suppress exceptions.
 * Return "NULL" **with** an exception set if an exception occurred.
 * Return "NULL" **without** an exception set if the key wasn’t
 * present.
 */
expect fun PyDict_GetItemWithError(p: NativePointer, key: NativePointer): NativePointer?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyDict_GetItem()", but *key* is specified as a
 * const char* UTF-8 encoded bytes string, rather than a PyObject*.
 *
 * Note:
 *
 *   Exceptions that occur while this calls "__hash__()" and
 *   "__eq__()" methods or while creating the temporary "str" object
 *   are silently ignored. Prefer using the
 *   "PyDict_GetItemWithError()" function with your own
 *   "PyUnicode_FromString()" *key* instead.
 */
expect fun PyDict_GetItemString(p: NativePointer, key: String): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a "PyListObject" containing all the items from the
 * dictionary.
 */
expect fun PyDict_Items(p: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a "PyListObject" containing all the keys from the
 * dictionary.
 */
expect fun PyDict_Keys(p: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a "PyListObject" containing all the values from the
 * dictionary *p*.
 */
expect fun PyDict_Values(p: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Iterate over mapping object *b* adding key-value pairs to
 * dictionary *a*. *b* may be a dictionary, or any object supporting
 * "PyMapping_Keys()" and "PyObject_GetItem()". If *override* is true,
 * existing pairs in *a* will be replaced if a matching key is found
 * in *b*, otherwise pairs will only be added if there is not a
 * matching key in *a*. Return "0" on success or "-1" if an exception
 * was raised.
 */
expect inline fun PyDict_Merge(a: NativePointer, b: NativePointer, override: Int): Int

/**
 *  *Part of the Stable ABI.*
 *
 * This is the same as "PyDict_Merge(a, b, 1)" in C, and is similar to
 * "a.update(b)" in Python except that "PyDict_Update()" doesn’t fall
 * back to the iterating over a sequence of key value pairs if the
 * second argument has no “keys” attribute.  Return "0" on success or
 * "-1" if an exception was raised.
 */
expect inline fun PyDict_Update(a: NativePointer, b: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Update or merge into dictionary *a*, from the key-value pairs in
 * *seq2*. *seq2* must be an iterable object producing iterable
 * objects of length 2, viewed as key-value pairs.  In case of
 * duplicate keys, the last wins if *override* is true, else the first
 * wins. Return "0" on success or "-1" if an exception was raised.
 * Equivalent Python (except for the return value):
 *
 *    def PyDict_MergeFromSeq2(a, seq2, override):
 *        for key, value in seq2:
 *            if override or key not in a:
 *                a[key] = value
 */
expect inline fun PyDict_MergeFromSeq2(a: NativePointer, seq2: NativePointer, override: Int): Int


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 24
// Set Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new "set" containing objects returned by the *iterable*.
 * The *iterable* may be "NULL" to create a new empty set.  Return the
 * new set on success or "NULL" on failure.  Raise "TypeError" if
 * *iterable* is not actually iterable.  The constructor is also
 * useful for copying a set ("c=set(s)").
 */
expect fun PySet_New(iterable: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new "frozenset" containing objects returned by the
 * *iterable*. The *iterable* may be "NULL" to create a new empty
 * frozenset.  Return the new set on success or "NULL" on failure.
 * Raise "TypeError" if *iterable* is not actually iterable.
 */
expect fun PyFrozenSet_New(iterable: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if found, "0" if not found, and "-1" if an error is
 * encountered.  Unlike the Python "__contains__()" method, this
 * function does not automatically convert unhashable sets into
 * temporary frozensets.  Raise a "TypeError" if the *key* is
 * unhashable. Raise "SystemError" if *anyset* is not a "set",
 * "frozenset", or an instance of a subtype.
 */
expect inline fun PySet_Contains(anyset: NativePointer, key: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Return the length of a "set" or "frozenset" object. Equivalent to
 * "len(anyset)".  Raise a "PyExc_SystemError" if *anyset* is not a
 * "set", "frozenset", or an instance of a subtype.
 */
expect inline fun PySet_Size(anyset: NativePointer): Long

/**
 *  *Part of the Stable ABI.*
 *
 * Add *key* to a "set" instance.  Also works with "frozenset"
 * instances (like "PyTuple_SetItem()" it can be used to fill in the
 * values of brand new frozensets before they are exposed to other
 * code).  Return "0" on success or "-1" on failure. Raise a
 * "TypeError" if the *key* is unhashable. Raise a "MemoryError" if
 * there is no room to grow.  Raise a "SystemError" if *set* is not an
 * instance of "set" or its subtype.
 */
expect inline fun PySet_Add(set: NativePointer, key: NativePointer): Int

/**
 *  *Part of the Stable ABI.*
 *
 * Return "1" if found and removed, "0" if not found (no action
 * taken), and "-1" if an error is encountered.  Does not raise
 * "KeyError" for missing keys.  Raise a "TypeError" if the *key* is
 * unhashable.  Unlike the Python "discard()" method, this function
 * does not automatically convert unhashable sets into temporary
 * frozensets. Raise "SystemError" if *set* is not an instance of
 * "set" or its subtype.
 */
expect inline fun PySet_Discard(set: NativePointer, key: NativePointer): Int

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new reference to an arbitrary object in the *set*, and
 * removes the object from the *set*.  Return "NULL" on failure.
 * Raise "KeyError" if the set is empty. Raise a "SystemError" if
 * *set* is not an instance of "set" or its subtype.
 */
expect fun PySet_Pop(set: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * Empty an existing set of all elements. Return "0" on success.
 * Return "-1" and raise "SystemError" if *set* is not an instance of
 */
expect inline fun PySet_Clear(set: NativePointer): Int


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 29
// Module Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// NOTE: this section was added after 27/28; see the ordering note above. Nothing is outstanding.
/**
 *  *Part of the Stable ABI.*
 *
 * Return *module*'s "__name__" value.  This is equivalent to
 * "module.__name__" and, unlike "PyModule_GetNameObject()", returns
 * the value already decoded as a UTF-8 encoded "const char*" rather
 * than a "PyObject*" -- so, unlike most of this ABI subset's string
 * accessors, decoding it costs no separate `PyUnicode_AsUTF8()` call.
 * "NULL" on failure (e.g. if *module* is not a module object).
 */
expect inline fun PyModule_GetName(module: NativePointer): String?

/**
 *  *Return value: Borrowed reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the dictionary object that implements *module*'s namespace;
 * this object is the same as the "__dict__" attribute of the module
 * object.  Raises a "SystemError" and returns "NULL" if *module* is
 * not a module object.
 */
expect fun PyModule_GetDict(module: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return the name of the file from which *module* was loaded using
 * *module*'s "__file__" attribute, as a "PyObject*".  Raise
 * "SystemError" and return "NULL" if *module* is not a module
 * object; raise "AttributeError" and return "NULL" if the module
 * has no "__file__" attribute (typically a built-in or frozen
 * module).
 */
expect fun PyModule_GetFilenameObject(module: NativePointer): NativePointer?


//
//    // 기타
//    private val pyRunSimpleStringHandle: MethodHandle
//    private val pyEvalGetBuiltinsHandle: MethodHandle
//
//    // 모듈 및 객체 관리
//    private val pyImportImportModuleHandle: MethodHandle
//    private val pyObjectGetAttrStringHandle: MethodHandle
//    private val pyObjectHasAttrStringHandle: MethodHandle
//    private val pyObjectCallObjectHandle: MethodHandle
//    private val pyObjectCallFunctionObjArgsHandle: MethodHandle
//    private val pyIncRefHandle: MethodHandle
//    private val pyDecRefHandle: MethodHandle
//
//    // 타입 변환
//    private val pyLongFromLongHandle: MethodHandle
//    private val pyLongAsLongHandle: MethodHandle
//    private val pyFloatFromDoubleHandle: MethodHandle
//    private val pyFloatAsDoubleHandle: MethodHandle
//    private val pyUnicodeFromStringHandle: MethodHandle
//    private val pyUnicodeAsUTF8Handle: MethodHandle
//
//    // 컬렉션
//    private val pyListNewHandle: MethodHandle
//    private val pyListSizeHandle: MethodHandle
//    private val pyListGetItemHandle: MethodHandle
//    private val pyListSetItemHandle: MethodHandle
//    private val pyTupleNewHandle: MethodHandle
//    private val pyTupleSizeHandle: MethodHandle
//    private val pyTupleGetItemHandle: MethodHandle
//    private val pyTupleSetItemHandle: MethodHandle

////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 25
// Iterator Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return an iterator that works with a general sequence object,
 * *seq*.  The iteration ends when the sequence raises "IndexError"
 * for the subscripting operation.
 */
expect fun PySeqIter_New(seq: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a new iterator.  The first parameter, *callable*, can be any
 * Python callable object that can be called with no parameters; each
 * call to it should return the next item in the iteration.  When
 * *callable* returns a value equal to *sentinel*, the iteration will
 */
expect fun PyCallIter_New(callable: NativePointer, sentinel: NativePointer): NativePointer?


////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Section 26
// Weak Reference Objects
////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a weak reference object for the object *ob*.  This will
 * always return a new reference, but is not guaranteed to create a
 * new object; an existing reference object may be returned.  The
 * second parameter, *callback*, can be a callable object that
 * receives notification when *ob* is garbage collected; it should
 * accept a single parameter, which will be the weak reference object
 * itself. *callback* may also be "None" or "NULL".  If *ob* is not a
 * weakly referenceable object, or if *callback* is not callable,
 * "None", or "NULL", this will return "NULL" and raise "TypeError".
 */
expect fun PyWeakref_NewRef(ob: NativePointer, callback: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI.*
 *
 * Return a weak reference proxy object for the object *ob*.  This
 * will always return a new reference, but is not guaranteed to create
 * a new object; an existing proxy object may be returned.  The second
 * parameter, *callback*, can be a callable object that receives
 * notification when *ob* is garbage collected; it should accept a
 * single parameter, which will be the weak reference object itself.
 * *callback* may also be "None" or "NULL".  If *ob* is not a weakly
 * referenceable object, or if *callback* is not callable, "None", or
 * "NULL", this will return "NULL" and raise "TypeError".
 */
expect fun PyWeakref_NewProxy(ob: NativePointer, callback: NativePointer): NativePointer?

/**
 *  *Return value: New reference.*
 *  *Part of the Stable ABI since version 3.13.*
 *
 * Resolve the weak reference *ref* to its referent.
 *
 * Returns a **new (strong) reference** to the referent, which the caller owns and must release
 * with [Py_DecRef]. Returns `null` if the referent is no longer live, and also `null` if *ref*
 * is not a weak reference object.
 *
 * The C function distinguishes those two outcomes by its `int` return — `0` for a dead referent
 * and `-1` for an error — while writing the object through an out-parameter. An out-parameter
 * does not survive this FFI boundary (Android requires primitives only across JNI), so both
 * arrive here as `null`. No information is lost: the error case sets the error indicator and the
 * dead case does not, so [PyErr_Occurred] separates them. `EmbedApiLowLevelTest` asserts that a
 * dead referent leaves the indicator clear.
 *
 * This replaces `PyWeakref_GetObject`, which CPython removed in 3.15. The two are not
 * interchangeable: the removed function returned a *borrowed* reference and `Py_None` — not
 * `null` — for a dead referent. `PyWeakref_GetRef` exists in 3.13 and 3.14 as well, so binding
 * it does not cost support for the version this build defaults to.
 */
expect fun PyWeakref_GetRef(ref: NativePointer): NativePointer?

/**
 *  *Part of the Stable ABI.*
 *
 * This function is called by the "tp_dealloc" handler to clear weak
 * references.
 *
 * This iterates through the weak references for *object* and calls
 * callbacks for those references which have one. It returns when all
 * callbacks have been attempted.
 */
expect inline fun PyObject_ClearWeakRefs(o: NativePointer)

