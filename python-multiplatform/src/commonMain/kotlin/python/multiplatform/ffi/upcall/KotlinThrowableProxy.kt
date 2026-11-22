package python.multiplatform.ffi.upcall

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.exceptions.PyException
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.reflection.HandleTable
import python.multiplatform.reflection.ObjectReference
import python.native.ffi.NativePointer
import python.native.ffi.PyErr_Clear
import python.native.ffi.PyImport_AddModuleRef
import python.native.ffi.PyLong_AsLongLong
import python.native.ffi.PyLong_FromLongLong
import python.native.ffi.PyObject_CallObject
import python.native.ffi.PyObject_GetAttrString
import python.native.ffi.PyObject_IsInstance
import python.native.ffi.PyTuple_GetItem
import python.native.ffi.PyTuple_New
import python.native.ffi.PyTuple_SetItem
import python.native.ffi.PyTuple_Size
import python.native.ffi.PyUnicode_FromString
import python.native.ffi.Py_DecRef
import python.native.ffi.Py_IncRef
import python.native.ffi.toNativePointer
import python.native.ffi.toRawValue

/**
 * A Kotlin [Throwable] seen from Python: a Python exception that **is** the proxy of the original
 * object (SPEC U-2, #164 item 6).
 *
 * Source: owner decision 2026-11-22, relayed by the leader: "a Kotlin Throwable seen from Python is a
 * Python exception class that IS the proxy of the original Throwable object (no copied
 * kotlin_class/kotlin_message/kotlin_stack strings). Its message/cause/stackTrace are read as ordinary
 * members of the original Throwable through the normal proxy; returning it to Kotlin yields the same
 * Throwable instance (identity)." It mirrors `PyException` in `python_for_kotlin_binding.mermaid`,
 * which implements Kotlin `Throwable` and extends `PyObject`.
 *
 * ### Shape (agent proposal for the parts the decision does not name)
 *
 * `python_multiplatform.KotlinThrowable(RuntimeError)`, defined once per interpreter in `__main__` as
 * `_PmThrowable`. An instance owns one [HandleTable] root over the throwable in `_pm_handle`, the
 * attribute every owner keeps its handle in, so `_pm_unwrap` and the binding layer send the handle
 * when the exception is passed to a Kotlin parameter and Kotlin resolves the same instance. `__del__`
 * releases the root.
 *
 * Its members do not go through [python.multiplatform.reflection.UpcallTable]. A table row would have
 * to be registered when the first throwable is raised, on whatever thread that happens, and the table
 * is not synchronised; tests also replace the table wholesale. Instead `_pm_invoke` is called with the
 * reserved callable handle [OPERATION_HANDLE] and `(operation, objectHandle)`, which
 * [UpcallTrampoline.invoke] hands to [dispatch] before any table lookup. No entry point is added on
 * any platform, and nothing depends on the table's epoch.
 *
 * ### Reference conventions
 *
 * [pointerFor] and [dispatch] return **new** references. [throwableOf] borrows its argument.
 * Every function here requires the caller to be attached to the interpreter.
 */
internal object KotlinThrowableProxy {

    /**
     * The callable handle `_pm_invoke` is given for a throwable operation. Negative, so it can never
     * be a table handle ([python.multiplatform.reflection.CallableHandle.isValid] is `raw >= 0`), and
     * not `-1`, which is "no such name".
     */
    const val OPERATION_HANDLE: Long = -2L

    /** The name the class is bound to in `__main__`. */
    const val CLASS_GLOBAL: String = "_PmThrowable"

    private const val OP_SUMMARY = 0L
    private const val OP_MESSAGE = 1L
    private const val OP_CAUSE = 2L
    private const val OP_STACK = 3L
    private const val OP_CLASS_NAME = 4L
    private const val OP_RETAIN = 5L

    /**
     * Defines [CLASS_GLOBAL] when the upcall entry points are published and it is not defined yet.
     *
     * Safe to `exec` any number of times: a second definition would build a second class, and an
     * exception raised before it would stop being an instance of the one `_pm_unwrap` and
     * [throwableOf] test against. Emitted by [PythonProxySource] ahead of `_pm_unwrap` too, so the
     * class exists before the owner tuple that names it.
     *
     * `_pm_invoke` and `_pm_release` are captured as default arguments, for the reason
     * `_pm_releaser` gives: a module's globals are `None` during interpreter finalisation and
     * `__del__` still runs then.
     */
    val SOURCE: String = """
        if '$CLASS_GLOBAL' not in globals() and '_pm_invoke' in globals() and '_pm_release' in globals():

            def _pm_make_throwable(_pm_i=_pm_invoke, _pm_r=_pm_release):
                import sys as _pm_sys

                class KotlinThrowable(RuntimeError):
                    # A Kotlin Throwable: this exception is the proxy of the original object. It
                    # owns one HandleTable root in `_pm_handle`; nothing of the throwable is copied.
                    __module__ = '${KotlinSurface.MODULE_NAME}'

                    def __init__(self, _pm_h):
                        RuntimeError.__init__(self)
                        self._pm_handle = _pm_h

                    def __del__(self, _pm_r=_pm_r):
                        # Cleared before the release, as `_PmObject.__del__` does: a second call, or a
                        # resurrection, cannot hand the same handle back twice.
                        _pm_d = self.__dict__
                        _pm_h = _pm_d.get('_pm_handle')
                        if _pm_h is not None:
                            _pm_d['_pm_handle'] = None
                            _pm_d.pop('_pm_proxy', None)
                            _pm_r(_pm_h)

                    def _pm_ask(self, _pm_op, _pm_i=_pm_i):
                        _pm_h = self.__dict__.get('_pm_handle')
                        if not _pm_h:
                            raise ValueError('this Kotlin throwable was already released')
                        return _pm_i($OPERATION_HANDLE, (_pm_op, _pm_h))

                    def __str__(self):
                        # "<KotlinSimpleClassName>: <message>", asked when it is printed.
                        try:
                            return self._pm_ask($OP_SUMMARY)
                        except Exception:
                            return '<Kotlin throwable>'

                    @property
                    def message(self):
                        return self._pm_ask($OP_MESSAGE)

                    @property
                    def cause(self):
                        return self._pm_ask($OP_CAUSE)

                    def stackTraceToString(self):
                        return self._pm_ask($OP_STACK)

                    stack_trace_to_string = stackTraceToString

                    def __getattr__(self, _pm_n):
                        # Only reached for a name this class does not have. With the binding layer
                        # installed, the ordinary proxy of the throwable's runtime class answers, over
                        # the same Kotlin object (a root of its own, released by that proxy).
                        if _pm_n.startswith('_'):
                            raise AttributeError(_pm_n)
                        _pm_b = _pm_sys.modules.get('${PythonxAdapter.MODULE_NAME}')
                        if _pm_b is None:
                            raise AttributeError(
                                'a Kotlin throwable has message, cause and stackTraceToString(); ' +
                                repr(_pm_n) + ' needs the binding layer'
                            )
                        _pm_p = self.__dict__.get('_pm_proxy')
                        if _pm_p is None:
                            _pm_p = _pm_b._wrap(self._pm_ask($OP_RETAIN), self._pm_ask($OP_CLASS_NAME))
                            self.__dict__['_pm_proxy'] = _pm_p
                        return getattr(_pm_p, _pm_n)

                return KotlinThrowable

            $CLASS_GLOBAL = _pm_make_throwable()
            del _pm_make_throwable
    """.trimIndent()

    /**
     * A **new** reference to the Python exception that stands for [t], or `null` when none can be
     * built (the entry points are not published, or Python failed; the error indicator is left
     * clear either way, and no root is left behind).
     *
     * A [PyException] with a live value is that original Python exception, not a proxy of the
     * Kotlin wrapper around it.
     */
    fun pointerFor(t: Throwable): NativePointer? {
        if (t is PyException) {
            val live = t.value
            if (live != null) {
                Py_IncRef(live.pointer)
                return live.pointer
            }
        }
        val cls = classPointer() ?: return null
        val root = HandleTable.register(t)
        val instance = try {
            construct(cls, root.raw)
        } finally {
            Py_DecRef(cls)
        }
        if (instance == null) {
            // Nobody owns the root: no instance exists whose `__del__` would release it.
            HandleTable.release(root)
        }
        return instance
    }

    /**
     * The Kotlin throwable [exception] (borrowed) is the proxy of, the same instance, or `null` when
     * it is any other Python object or its root was already released.
     */
    fun throwableOf(exception: NativePointer): Throwable? {
        val cls = existingClassPointer() ?: return null
        try {
            val isProxy = PyObject_IsInstance(exception, cls)
            if (isProxy != 1) {
                if (isProxy < 0) PyErr_Clear()
                return null
            }
        } finally {
            Py_DecRef(cls)
        }
        val handle = PyObject_GetAttrString(exception, "_pm_handle") ?: run {
            PyErr_Clear()
            return null
        }
        try {
            if (python.multiplatform.ffi.PyLong_Check(handle) == 0) return null
            val raw = PyLong_AsLongLong(handle)
            if (raw == -1L && python.native.ffi.PyErr_Occurred() != null) {
                PyErr_Clear()
                return null
            }
            return HandleTable.resolveRaw(raw) as? Throwable
        } finally {
            Py_DecRef(handle)
        }
    }

    /**
     * One operation on the throwable behind an object handle: what `_pm_invoke($OPERATION_HANDLE,
     * (operation, objectHandle))` reaches. Returns a **new** reference; throws for a malformed call or
     * a stale handle, which [UpcallTrampoline.invoke] turns into a Python error.
     */
    fun dispatch(argsTuple: Long): Long {
        val tuple = argsTuple.toNativePointer()
            ?: throw IllegalArgumentException("a throwable operation takes (operation, handle)")
        if (PyTuple_Size(tuple) != 2L) {
            PyErr_Clear()
            throw IllegalArgumentException("a throwable operation takes (operation, handle)")
        }
        val operation = longItem(tuple, 0)
        val handle = longItem(tuple, 1)
        val throwable = HandleTable.resolveRaw(handle) as? Throwable
            ?: throw IllegalArgumentException("stale or unknown Kotlin throwable handle: $handle")
        return when (operation) {
            OP_SUMMARY -> string(summaryOf(throwable))
            OP_MESSAGE -> throwable.message?.let(::string) ?: none()
            OP_CAUSE -> {
                val cause = throwable.cause
                if (cause == null || cause === throwable) none()
                else (pointerFor(cause) ?: throw IllegalStateException("could not build the proxy of the cause")).toRawValue()
            }
            OP_STACK -> string(throwable.stackTraceToString())
            OP_CLASS_NAME -> string(classNameOf(throwable))
            // A second root over the same object, for the binding layer's own proxy to own.
            OP_RETAIN -> PyLong_FromLongLong(HandleTable.register(throwable).raw)?.toRawValue()
                ?: throw IllegalStateException("could not build the handle")
            else -> throw IllegalArgumentException("unknown throwable operation: $operation")
        }
    }

    /** `"<KotlinSimpleClassName>: <message>"`, or the class name alone when there is no message. */
    fun summaryOf(t: Throwable): String {
        val className = t::class.simpleName ?: "Throwable"
        val message = t.message
        return if (message.isNullOrEmpty()) className else "$className: $message"
    }

    private fun classNameOf(t: Throwable): String =
        try {
            t::class.qualifiedName
        } catch (_: Throwable) {
            null
        } ?: t::class.simpleName ?: "kotlin.Throwable"

    /** A new reference to the class, defining it first when it does not exist yet; `null` when it cannot exist. */
    private fun classPointer(): NativePointer? {
        existingClassPointer()?.let { return it }
        try {
            Python3.exec(SOURCE)
        } catch (_: Throwable) {
            PyErr_Clear()
            return null
        }
        return existingClassPointer()
    }

    private fun existingClassPointer(): NativePointer? {
        val main = PyImport_AddModuleRef("__main__") ?: run {
            PyErr_Clear()
            return null
        }
        try {
            return PyObject_GetAttrString(main, CLASS_GLOBAL) ?: run {
                PyErr_Clear()
                null
            }
        } finally {
            Py_DecRef(main)
        }
    }

    private fun construct(cls: NativePointer, handle: Long): NativePointer? {
        val handlePointer = PyLong_FromLongLong(handle) ?: run {
            PyErr_Clear()
            return null
        }
        val tuple = PyTuple_New(1) ?: run {
            Py_DecRef(handlePointer)
            PyErr_Clear()
            return null
        }
        // PyTuple_SetItem steals handlePointer.
        PyTuple_SetItem(tuple, 0, handlePointer)
        val instance = try {
            PyObject_CallObject(cls, tuple)
        } finally {
            Py_DecRef(tuple)
        }
        if (instance == null) PyErr_Clear()
        return instance
    }

    /** `PyTuple_GetItem` borrows. */
    private fun longItem(tuple: NativePointer, index: Long): Long {
        val item = PyTuple_GetItem(tuple, index) ?: run {
            PyErr_Clear()
            throw IllegalArgumentException("a throwable operation takes (operation, handle)")
        }
        val value = PyLong_AsLongLong(item)
        if (value == -1L && python.native.ffi.PyErr_Occurred() != null) {
            PyErr_Clear()
            throw IllegalArgumentException("a throwable operation takes two ints")
        }
        return value
    }

    private fun string(value: String): Long =
        PyUnicode_FromString(value)?.toRawValue() ?: throw IllegalStateException("could not build the str result")

    private fun none(): Long {
        val none = python.multiplatform.ffi.types.basic.PyNone.get().pointer
        Py_IncRef(none)
        return none.toRawValue()
    }
}
