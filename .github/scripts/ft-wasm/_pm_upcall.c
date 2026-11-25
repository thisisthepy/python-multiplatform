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
 *
 * The proxy type's slots (tp_traverse, tp_clear, tp_dealloc, ProxyTypeFactory) are Kotlin
 * exports placed the same way, so they get the same kind of dispatcher, one per C signature
 * (pm_tp_*). tp_clear and tp_dealloc run attached and route like a method. tp_traverse is
 * different: a free-threaded collection calls it with the world stopped, on the collecting
 * thread, which cannot detach. The Kotlin traverse never attaches a thread state (it reads the
 * proxy's handle slot and calls `visit`), so the collector stays attached and waits while the
 * main thread, parked for the pause on a futex, runs it. Outside a pause (gc.get_referents) the
 * caller detaches first, as a method call does, so a pause the main thread asks for meanwhile
 * does not wait on it. smoke.py checks both with the self-test node type below.
 */
/* Built into the interpreter (Setup.local *static*), so the internal headers are available; they
 * are needed for one thing, whether the world is stopped (pm_tp_traverse). */
#ifndef Py_BUILD_CORE_BUILTIN
#  define Py_BUILD_CORE_BUILTIN 1
#endif
#include "Python.h"
#include "pycore_interp.h"    /* PyInterpreterState.stoptheworld */
#include "pycore_pystate.h"   /* _PyInterpreterState_GET */
#include "pycore_runtime.h"   /* _PyRuntime.stoptheworld */
#include "structmember.h"     /* PyMemberDef for the self-test node */

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

/* ---- the proxy type's slots (ProxyTypeFactory) -------------------------------------------- */

enum { PM_SLOT_TRAVERSE = 0, PM_SLOT_CLEAR = 1, PM_SLOT_DEALLOC = 2, PM_SLOT_COUNT = 3 };

typedef int (*pm_traverse_fn)(PyObject *, visitproc, void *);
typedef int (*pm_clear_fn)(PyObject *);
typedef void (*pm_dealloc_fn)(PyObject *);

/* The table indices of the Kotlin slot functions, set once on the main thread; 0 until then. */
static _Atomic(uintptr_t) pm_slot_target[PM_SLOT_COUNT];

typedef struct {
    int slot;
    uintptr_t fn;
    PyObject *self;
    visitproc visit;
    void *arg;
    int result;
} pm_slot_call;

/* tp_clear and tp_dealloc on the main thread, attached as CPython calls them. Whatever exception
 * the main thread's own state holds is put back afterwards: a slot must not disturb it. */
static void
pm_slot_on_main_attached(pm_slot_call *call)
{
    PyGILState_STATE state = PyGILState_Ensure();
    PyObject *pending = PyErr_GetRaisedException();
    if (call->slot == PM_SLOT_CLEAR) {
        call->result = ((pm_clear_fn)call->fn)(call->self);
    }
    else {
        ((pm_dealloc_fn)call->fn)(call->self);
    }
    PyErr_SetRaisedException(pending);
    PyGILState_Release(state);
}

/* tp_traverse on the main thread, with no thread state attached: see the header comment. */
static void
pm_traverse_on_main(pm_slot_call *call)
{
    call->result = ((pm_traverse_fn)call->fn)(call->self, call->visit, call->arg);
}

/* Whether this (attached) thread is inside a stop-the-world pause, which then is its own: every
 * other thread is parked, so an attached one is the requester. */
static int
pm_in_stopped_world(void)
{
    PyInterpreterState *interp = _PyInterpreterState_GET();
    return interp->stoptheworld.world_stopped || _PyRuntime.stoptheworld.world_stopped;
}

static int
pm_route_traverse(uintptr_t fn, PyObject *self, visitproc visit, void *arg)
{
    if (fn == 0) {
        return 0;
    }
    if (emscripten_is_main_runtime_thread()) {
        return ((pm_traverse_fn)fn)(self, visit, arg);
    }
    pm_slot_call call = {PM_SLOT_TRAVERSE, fn, self, visit, arg, 0};
    if (pm_in_stopped_world()) {
        emscripten_sync_run_in_main_runtime_thread(EM_FUNC_SIG_VI, pm_traverse_on_main, &call);
    }
    else {
        Py_BEGIN_ALLOW_THREADS
        emscripten_sync_run_in_main_runtime_thread(EM_FUNC_SIG_VI, pm_traverse_on_main, &call);
        Py_END_ALLOW_THREADS
    }
    return call.result;
}

static int
pm_route_attached(int slot, uintptr_t fn, PyObject *self)
{
    if (emscripten_is_main_runtime_thread()) {
        if (slot == PM_SLOT_CLEAR) {
            return ((pm_clear_fn)fn)(self);
        }
        ((pm_dealloc_fn)fn)(self);
        return 0;
    }
    pm_slot_call call = {slot, fn, self, NULL, NULL, 0};
    Py_BEGIN_ALLOW_THREADS
    emscripten_sync_run_in_main_runtime_thread(EM_FUNC_SIG_VI, pm_slot_on_main_attached, &call);
    Py_END_ALLOW_THREADS
    return call.result;
}

/* The proxy type's slot functions when this module is present. With no target registered they
 * do what an empty slot would: traverse and clear visit nothing; dealloc cannot be empty, and a
 * proxy type is only built after the targets are set, so it is not reached. */
EMSCRIPTEN_KEEPALIVE int
pm_tp_traverse(PyObject *self, visitproc visit, void *arg)
{
    return pm_route_traverse(atomic_load(&pm_slot_target[PM_SLOT_TRAVERSE]), self, visit, arg);
}

EMSCRIPTEN_KEEPALIVE int
pm_tp_clear(PyObject *self)
{
    uintptr_t fn = atomic_load(&pm_slot_target[PM_SLOT_CLEAR]);
    return fn == 0 ? 0 : pm_route_attached(PM_SLOT_CLEAR, fn, self);
}

EMSCRIPTEN_KEEPALIVE void
pm_tp_dealloc(PyObject *self)
{
    uintptr_t fn = atomic_load(&pm_slot_target[PM_SLOT_DEALLOC]);
    if (fn != 0) {
        pm_route_attached(PM_SLOT_DEALLOC, fn, self);
    }
}

/* The dispatcher for `slot` (0 traverse, 1 clear, 2 dealloc): its table index, the same in every
 * Worker, or -1 for an unknown slot. */
EMSCRIPTEN_KEEPALIVE int
pm_proxy_slot_index(int slot)
{
    switch (slot) {
    case PM_SLOT_TRAVERSE: return (int)(uintptr_t)&pm_tp_traverse;
    case PM_SLOT_CLEAR: return (int)(uintptr_t)&pm_tp_clear;
    case PM_SLOT_DEALLOC: return (int)(uintptr_t)&pm_tp_dealloc;
    default: return -1;
    }
}

/* Makes the function at table index `index` the target of `slot`'s dispatcher. */
EMSCRIPTEN_KEEPALIVE void
pm_proxy_set_slot_target(int slot, int index)
{
    if (slot >= 0 && slot < PM_SLOT_COUNT) {
        atomic_store(&pm_slot_target[slot], (uintptr_t)index);
    }
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

/* The slot routes without Kotlin: `Node`, a GC type with one `ref` attribute, whose three slots
 * go through pm_route_traverse / pm_route_attached exactly as the proxy type's dispatchers do,
 * with C targets that count where they ran. A cycle of nodes collected from a Python thread
 * shows whether the traverse ran on the main thread during the pause, and whether clear and
 * dealloc ran there too, without deadlocking. */
typedef struct {
    PyObject_HEAD
    PyObject *ref;
} pm_node;

/* [slot][1 if it ran on the main runtime thread, else 0] */
static atomic_int pm_node_runs[PM_SLOT_COUNT][2];

static void
pm_node_ran(int slot)
{
    atomic_fetch_add(&pm_node_runs[slot][emscripten_is_main_runtime_thread() ? 1 : 0], 1);
}

static int
pm_node_traverse_target(PyObject *self, visitproc visit, void *arg)
{
    pm_node_ran(PM_SLOT_TRAVERSE);
    Py_VISIT(Py_TYPE(self));
    Py_VISIT(((pm_node *)self)->ref);
    return 0;
}

static int
pm_node_clear_target(PyObject *self)
{
    pm_node_ran(PM_SLOT_CLEAR);
    Py_CLEAR(((pm_node *)self)->ref);
    return 0;
}

static void
pm_node_dealloc_target(PyObject *self)
{
    pm_node_ran(PM_SLOT_DEALLOC);
    PyTypeObject *type = Py_TYPE(self);
    PyObject_GC_UnTrack(self);
    Py_CLEAR(((pm_node *)self)->ref);
    type->tp_free(self);
    Py_DECREF(type);
}

static int
pm_node_traverse(PyObject *self, visitproc visit, void *arg)
{
    return pm_route_traverse((uintptr_t)&pm_node_traverse_target, self, visit, arg);
}

static int
pm_node_clear(PyObject *self)
{
    return pm_route_attached(PM_SLOT_CLEAR, (uintptr_t)&pm_node_clear_target, self);
}

static void
pm_node_dealloc(PyObject *self)
{
    pm_route_attached(PM_SLOT_DEALLOC, (uintptr_t)&pm_node_dealloc_target, self);
}

static PyMemberDef pm_node_members[] = {
    {"ref", Py_T_OBJECT_EX, offsetof(pm_node, ref), 0, "Any object; set two nodes to each other for a cycle."},
    {NULL},
};

static PyType_Slot pm_node_slots[] = {
    {Py_tp_new, PyType_GenericNew},
    {Py_tp_traverse, pm_node_traverse},
    {Py_tp_clear, pm_node_clear},
    {Py_tp_dealloc, pm_node_dealloc},
    {Py_tp_members, pm_node_members},
    {0, NULL},
};

static PyType_Spec pm_node_spec = {
    .name = "_pm_upcall.Node",
    .basicsize = sizeof(pm_node),
    .flags = Py_TPFLAGS_DEFAULT | Py_TPFLAGS_HAVE_GC,
    .slots = pm_node_slots,
};

static PyObject *
pm_slot_stats(PyObject *module, PyObject *Py_UNUSED(ignored))
{
    static const char *names[PM_SLOT_COUNT] = {"traverse", "clear", "dealloc"};
    PyObject *stats = PyDict_New();
    if (stats == NULL) {
        return NULL;
    }
    for (int slot = 0; slot < PM_SLOT_COUNT; slot++) {
        PyObject *runs = Py_BuildValue("{s:i,s:i}", "main", atomic_load(&pm_node_runs[slot][1]),
                                       "other", atomic_load(&pm_node_runs[slot][0]));
        if (runs == NULL || PyDict_SetItemString(stats, names[slot], runs) < 0) {
            Py_XDECREF(runs);
            Py_DECREF(stats);
            return NULL;
        }
        Py_DECREF(runs);
    }
    return stats;
}

static PyObject *
pm_reset_slot_stats(PyObject *module, PyObject *Py_UNUSED(ignored))
{
    for (int slot = 0; slot < PM_SLOT_COUNT; slot++) {
        atomic_store(&pm_node_runs[slot][0], 0);
        atomic_store(&pm_node_runs[slot][1], 0);
    }
    Py_RETURN_NONE;
}

static PyMethodDef pm_methods[] = {
    {"selftest", pm_selftest, METH_VARARGS,
     "selftest([mode]) -> (thread ident, on main thread): runs on the main runtime thread "
     "through the upcall route; mode 'raise' raises ValueError there."},
    {"has_target", pm_has_target, METH_NOARGS,
     "Whether the embedding runtime registered its upcall target."},
    {"slot_stats", pm_slot_stats, METH_NOARGS,
     "slot_stats() -> {'traverse'|'clear'|'dealloc': {'main': n, 'other': n}}: where Node's slot "
     "targets ran since the last reset_slot_stats()."},
    {"reset_slot_stats", pm_reset_slot_stats, METH_NOARGS, "Zeroes slot_stats()."},
    {NULL, NULL, 0, NULL},
};

static int
pm_exec(PyObject *module)
{
    PyObject *node = PyType_FromSpec(&pm_node_spec);
    if (node == NULL) {
        return -1;
    }
    int rc = PyModule_AddObjectRef(module, "Node", node);
    Py_DECREF(node);
    return rc;
}

static PyModuleDef_Slot pm_slots[] = {
    {Py_mod_exec, pm_exec},
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
