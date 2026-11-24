/*
 * _pm_upcall: upcalls from Python threads on free-threaded wasm (#175 step 2, agent proposal,
 * design in docs/platforms/free-threaded-wasm.md, "Design: upcalls from Python threads").
 *
 * Compiled into the free-threaded wasm32-emscripten CPython as a built-in module
 * (build-wasm.sh writes `_pm_upcall _pm_upcall.c` into the build tree's Modules/Setup.local).
 *
 * The problem it solves: python-multiplatform's wasm upcall entry is a Kotlin @WasmExport that
 * JS puts into CPython's function table with Table.grow/Table.set, on the main thread. Every
 * pthread Worker instantiates python.wasm with its own table, where that index holds nothing,
 * and Kotlin/Wasm runs only on the main thread. A Python thread calling a Kotlin callable
 * therefore traps.
 *
 * The route: a PyMethodDef's ml_meth is pm_upcall_dispatch instead. A function in python.wasm's
 * own element segment has the same table index in every Worker. On the main runtime thread it
 * calls the registered target directly. On any other thread it detaches its thread state, runs
 * the call on the main thread (emscripten_sync_run_in_main_runtime_thread), carries a raised
 * exception back to the calling thread, and re-attaches.
 *
 * Detaching matters on a free-threaded build: a stop-the-world pause (a GC) requested while the
 * main thread runs the call would otherwise wait for this thread forever.
 *
 * The main thread services the proxied call while it waits on a futex (thread.join(), a lock),
 * or once it returns to the event loop. A main thread busy in JavaScript does not; such a call
 * waits until it does.
 */
#include "Python.h"

#include <emscripten.h>
#include <emscripten/threading.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

typedef PyObject *(*pm_cfunc)(PyObject *, PyObject *);

/* The table index of the Kotlin entry (`PyObject *(PyObject *self, PyObject *args)`), set once
 * by the embedding Kotlin runtime on the main thread. NULL until then. */
static _Atomic(pm_cfunc) pm_target = NULL;

typedef struct {
    pm_cfunc fn;
    PyObject *self;
    PyObject *args;
    PyObject *result; /* new reference, or NULL */
    PyObject *exc;    /* the exception raised on the main thread, new reference, or NULL */
} pm_call;

/* Runs on the main runtime thread. The caller's thread is detached and blocked until this
 * returns, so `call` stays valid. The main thread attaches its own thread state for the call;
 * a Kotlin target takes its own PyGILState pair as well, which nests. */
static void
pm_run_on_main(pm_call *call)
{
    PyGILState_STATE state = PyGILState_Ensure();
    call->result = call->fn(call->self, call->args);
    if (call->result == NULL) {
        /* Raised on the main thread's thread state; moved to the caller's below. */
        call->exc = PyErr_GetRaisedException();
    }
    PyGILState_Release(state);
}

static PyObject *
pm_route(pm_cfunc fn, PyObject *self, PyObject *args)
{
    if (fn == NULL) {
        PyErr_SetString(PyExc_RuntimeError,
                        "_pm_upcall: no upcall target is registered (pm_upcall_set_target)");
        return NULL;
    }
    if (emscripten_is_main_runtime_thread()) {
        return fn(self, args);
    }
    pm_call call = {fn, self, args, NULL, NULL};
    Py_BEGIN_ALLOW_THREADS
    emscripten_sync_run_in_main_runtime_thread(EM_FUNC_SIG_VI, pm_run_on_main, &call);
    Py_END_ALLOW_THREADS
    if (call.result == NULL) {
        if (call.exc != NULL) {
            PyErr_SetRaisedException(call.exc);
        }
        else if (!PyErr_Occurred()) {
            PyErr_SetString(PyExc_SystemError,
                            "_pm_upcall: the upcall returned NULL without setting an exception");
        }
    }
    return call.result;
}

/* ml_meth of every python-multiplatform upcall PyMethodDef when this module is present. */
EMSCRIPTEN_KEEPALIVE PyObject *
pm_upcall_dispatch(PyObject *self, PyObject *args)
{
    return pm_route(atomic_load(&pm_target), self, args);
}

/* `index` is a function table index (a C function pointer on wasm32). */
EMSCRIPTEN_KEEPALIVE void
pm_upcall_set_target(int index)
{
    atomic_store(&pm_target, (pm_cfunc)(uintptr_t)index);
}

/* pm_upcall_dispatch's table index, the same in every Worker. */
EMSCRIPTEN_KEEPALIVE int
pm_upcall_dispatch_index(void)
{
    return (int)(uintptr_t)&pm_upcall_dispatch;
}

/* ---- self-test, used by smoke.py: the routing without Kotlin ---------------------------- */

/* A target that reports where it ran: (thread ident, on the main runtime thread). With "raise"
 * it raises ValueError there instead, to check the exception reaches the calling thread. */
static PyObject *
pm_selftest_target(PyObject *self, PyObject *args)
{
    const char *mode = NULL;
    if (!PyArg_ParseTuple(args, "|s:selftest", &mode)) {
        return NULL;
    }
    if (mode != NULL && strcmp(mode, "raise") == 0) {
        PyErr_SetString(PyExc_ValueError, "raised on the main thread");
        return NULL;
    }
    return Py_BuildValue("(kO)", PyThread_get_thread_ident(),
                         emscripten_is_main_runtime_thread() ? Py_True : Py_False);
}

static PyObject *
pm_selftest(PyObject *module, PyObject *args)
{
    return pm_route(pm_selftest_target, module, args);
}

static PyObject *
pm_has_target(PyObject *module, PyObject *Py_UNUSED(ignored))
{
    return PyBool_FromLong(atomic_load(&pm_target) != NULL);
}

static PyMethodDef pm_methods[] = {
    {"selftest", pm_selftest, METH_VARARGS,
     "selftest([mode]) -> (thread ident, on main thread): runs on the main runtime thread "
     "through the upcall route; mode 'raise' raises ValueError there."},
    {"has_target", pm_has_target, METH_NOARGS,
     "Whether the embedding runtime registered its upcall target."},
    {NULL, NULL, 0, NULL},
};

static PyModuleDef_Slot pm_slots[] = {
    /* Without this, importing the module re-enables the GIL on a free-threaded build. */
    {Py_mod_gil, Py_MOD_GIL_NOT_USED},
    {0, NULL},
};

static struct PyModuleDef pm_module = {
    PyModuleDef_HEAD_INIT,
    .m_name = "_pm_upcall",
    .m_doc = "python-multiplatform upcalls from Python threads on wasm (#175).",
    .m_size = 0,
    .m_methods = pm_methods,
    .m_slots = pm_slots,
};

PyMODINIT_FUNC
PyInit__pm_upcall(void)
{
    return PyModuleDef_Init(&pm_module);
}
