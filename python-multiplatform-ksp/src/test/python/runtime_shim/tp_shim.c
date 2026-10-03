/* Test extension: exposes every tp_runtime.h helper to Python so test_runtime.py can compare it
 * with CPython. Every function returns (rc, value); rc == -1 raises the Python error the helper
 * set. A helper that breaks the return convention (rc -1 with no error set, rc 0/1 with an error
 * set) raises AssertionError, so convention violations cannot hide. */
#define PY_SSIZE_T_CLEAN
#include <Python.h>
#include "tp_runtime.h"

#ifndef TP_SHIM_NAME
#define TP_SHIM_NAME tp_shim
#endif
#define TP_CAT2(a, b) a##b
#define TP_CAT(a, b) TP_CAT2(a, b)
#define TP_STR2(a) #a
#define TP_STR(a) TP_STR2(a)

static int rc_ok(int rc)
{
    if (rc < 0 && !PyErr_Occurred()) {
        PyErr_SetString(PyExc_AssertionError, "rc < 0 but no Python error is set");
        return 0;
    }
    if (rc < 0) {
        return 0;
    }
    if (PyErr_Occurred()) {
        PyErr_Format(PyExc_AssertionError, "rc == %d but a Python error is set", rc);
        return 0;
    }
    return 1;
}

static PyObject *ret_none(int rc) { return Py_BuildValue("(iO)", rc, Py_None); }

/* ---- int64 ---- */
#define I64_BIN(OP)                                                                         \
    static PyObject *s_##OP##_i64(PyObject *self, PyObject *args)                           \
    {                                                                                       \
        long long a, b;                                                                     \
        int64_t out = 0;                                                                    \
        if (!PyArg_ParseTuple(args, "LL", &a, &b)) return NULL;                             \
        int rc = tp_##OP##_i64((int64_t)a, (int64_t)b, &out);                               \
        if (!rc_ok(rc)) return NULL;                                                        \
        if (rc) return ret_none(rc);                                                        \
        return Py_BuildValue("(iL)", rc, (long long)out);                                   \
    }
I64_BIN(add)
I64_BIN(sub)
I64_BIN(mul)
I64_BIN(floordiv)
I64_BIN(mod)

static PyObject *s_neg_i64(PyObject *self, PyObject *args)
{
    long long a;
    int64_t out = 0;
    if (!PyArg_ParseTuple(args, "L", &a)) return NULL;
    int rc = tp_neg_i64((int64_t)a, &out);
    if (!rc_ok(rc)) return NULL;
    if (rc) return ret_none(rc);
    return Py_BuildValue("(iL)", rc, (long long)out);
}

static PyObject *s_truediv_i64(PyObject *self, PyObject *args)
{
    long long a, b;
    double out = 0;
    if (!PyArg_ParseTuple(args, "LL", &a, &b)) return NULL;
    int rc = tp_truediv_i64((int64_t)a, (int64_t)b, &out);
    if (!rc_ok(rc)) return NULL;
    if (rc) return ret_none(rc);
    return Py_BuildValue("(id)", rc, out);
}

static PyObject *s_i64_to_f64(PyObject *self, PyObject *args)
{
    long long a;
    if (!PyArg_ParseTuple(args, "L", &a)) return NULL;
    return PyFloat_FromDouble(tp_i64_to_f64((int64_t)a));
}

/* ---- float ---- */
#define F64_BIN(OP)                                                                         \
    static PyObject *s_##OP##_f64(PyObject *self, PyObject *args)                           \
    {                                                                                       \
        double a, b, out = 0;                                                               \
        if (!PyArg_ParseTuple(args, "dd", &a, &b)) return NULL;                             \
        int rc = tp_##OP##_f64(a, b, &out);                                                 \
        if (!rc_ok(rc)) return NULL;                                                        \
        if (rc) return ret_none(rc);                                                        \
        return Py_BuildValue("(id)", rc, out);                                              \
    }
F64_BIN(truediv)
F64_BIN(floordiv)
F64_BIN(mod)

static PyObject *s_cmp_i64_f64(PyObject *self, PyObject *args)
{
    long long a;
    double b;
    int op;
    if (!PyArg_ParseTuple(args, "Ldi", &a, &b, &op)) return NULL;
    return PyLong_FromLong(tp_cmp_i64_f64((int64_t)a, b, op));
}

static PyObject *s_cmp_f64_i64(PyObject *self, PyObject *args)
{
    double a;
    long long b;
    int op;
    if (!PyArg_ParseTuple(args, "dLi", &a, &b, &op)) return NULL;
    return PyLong_FromLong(tp_cmp_f64_i64(a, (int64_t)b, op));
}

/* ---- math ---- */
static PyObject *s_math1(PyObject *self, PyObject *args)
{
    const char *name;
    double x, out = 0;
    int rc;
    if (!PyArg_ParseTuple(args, "sd", &name, &x)) return NULL;
    if (!strcmp(name, "sqrt")) rc = tp_math_sqrt(x, &out);
    else if (!strcmp(name, "exp")) rc = tp_math_exp(x, &out);
    else if (!strcmp(name, "log")) rc = tp_math_log(x, &out);
    else if (!strcmp(name, "sin")) rc = tp_math_sin(x, &out);
    else if (!strcmp(name, "cos")) rc = tp_math_cos(x, &out);
    else if (!strcmp(name, "tan")) rc = tp_math_tan(x, &out);
    else if (!strcmp(name, "fabs")) rc = tp_math_fabs(x, &out);
    else { PyErr_SetString(PyExc_ValueError, "unknown math1"); return NULL; }
    if (!rc_ok(rc)) return NULL;
    if (rc) return ret_none(rc);
    return Py_BuildValue("(id)", rc, out);
}

static PyObject *s_math2(PyObject *self, PyObject *args)
{
    const char *name;
    double x, y, out = 0;
    int rc;
    if (!PyArg_ParseTuple(args, "sdd", &name, &x, &y)) return NULL;
    if (!strcmp(name, "atan2")) rc = tp_math_atan2(x, y, &out);
    else if (!strcmp(name, "hypot")) rc = tp_math_hypot(x, y, &out);
    else { PyErr_SetString(PyExc_ValueError, "unknown math2"); return NULL; }
    if (!rc_ok(rc)) return NULL;
    if (rc) return ret_none(rc);
    return Py_BuildValue("(id)", rc, out);
}

/* ---- boxing ---- */
static PyObject *s_box_i64(PyObject *self, PyObject *args)
{
    long long v;
    if (!PyArg_ParseTuple(args, "L", &v)) return NULL;
    return tp_box_i64((int64_t)v);
}
static PyObject *s_box_f64(PyObject *self, PyObject *args)
{
    double v;
    if (!PyArg_ParseTuple(args, "d", &v)) return NULL;
    return tp_box_f64(v);
}
static PyObject *s_box_bool(PyObject *self, PyObject *args)
{
    int v;
    if (!PyArg_ParseTuple(args, "i", &v)) return NULL;
    return tp_box_bool(v);
}
static PyObject *s_unbox_i64(PyObject *self, PyObject *o)
{
    int64_t out = 0;
    int rc = tp_unbox_i64(o, &out);
    if (!rc_ok(rc)) return NULL;
    if (rc) return ret_none(rc);
    return Py_BuildValue("(iL)", rc, (long long)out);
}
static PyObject *s_unbox_f64(PyObject *self, PyObject *o)
{
    double out = 0;
    int rc = tp_unbox_f64(o, &out);
    if (!rc_ok(rc)) return NULL;
    if (rc) return ret_none(rc);
    return Py_BuildValue("(id)", rc, out);
}
static PyObject *s_unbox_bool(PyObject *self, PyObject *o)
{
    int out = 0;
    int rc = tp_unbox_bool(o, &out);
    if (!rc_ok(rc)) return NULL;
    if (rc) return ret_none(rc);
    return Py_BuildValue("(ii)", rc, out);
}

/* ---- arrays ----
 * array_run(list, script) -> (enter_rc, results, exit_rc, exit_error)
 * script items: ("get", i) | ("set", i, v) | ("len",) | ("dirty",) | ("clear",)
 * A failing "get"/"set" slot lookup appends the exception instance. The struct is filled with
 * garbage before enter, so an enter that leaves a field unset shows up. */
#define ARRAY_RUN(SUF, STRUCT, CTYPE, FROMC, PARSEFMT, PARSET)                              \
    static PyObject *s_##SUF##_array_run(PyObject *self, PyObject *args)                    \
    {                                                                                       \
        PyObject *obj, *script;                                                             \
        if (!PyArg_ParseTuple(args, "OO", &obj, &script)) return NULL;                      \
        STRUCT a;                                                                           \
        memset(&a, 0xAB, sizeof a);                                                         \
        int erc = tp_##SUF##_array_enter(obj, &a);                                          \
        if (!rc_ok(erc)) return NULL;                                                       \
        PyObject *results = PyList_New(0);                                                  \
        if (!results) { tp_##SUF##_array_exit(&a); return NULL; }                           \
        if (erc == 0) {                                                                     \
            Py_ssize_t n = PyList_GET_SIZE(script);                                         \
            for (Py_ssize_t k = 0; k < n; k++) {                                            \
                PyObject *op = PyList_GET_ITEM(script, k);                                  \
                const char *kind = PyUnicode_AsUTF8(PyTuple_GET_ITEM(op, 0));               \
                PyObject *item = NULL;                                                      \
                if (!strcmp(kind, "get") || !strcmp(kind, "set")) {                         \
                    long long i = PyLong_AsLongLong(PyTuple_GET_ITEM(op, 1));               \
                    int store = kind[0] == 's';                                             \
                    Py_ssize_t slot = -7;                                                   \
                    int rc = tp_##SUF##_array_slot(&a, (int64_t)i, store, &slot);           \
                    if (rc < 0) {                                                           \
                        item = PyErr_GetRaisedException();                                  \
                    } else if (rc != 0 || PyErr_Occurred()) {                               \
                        PyErr_SetString(PyExc_AssertionError, "slot rc not 0 / -1");        \
                        Py_DECREF(results); tp_##SUF##_array_exit(&a); return NULL;         \
                    } else if (!store) {                                                    \
                        item = FROMC(a.data[slot]);                                         \
                    } else {                                                                \
                        PARSET v;                                                           \
                        if (!PyArg_Parse(PyTuple_GET_ITEM(op, 2), PARSEFMT, &v)) {          \
                            Py_DECREF(results); tp_##SUF##_array_exit(&a); return NULL;     \
                        }                                                                   \
                        a.data[slot] = (CTYPE)v;                                            \
                        a.dirty[slot] = 1;                                                  \
                        item = Py_NewRef(Py_None);                                          \
                    }                                                                       \
                } else if (!strcmp(kind, "len")) {                                          \
                    item = PyLong_FromSsize_t(a.len);                                       \
                } else if (!strcmp(kind, "dirty")) {                                        \
                    item = PyBytes_FromStringAndSize((const char *)a.dirty, a.len);         \
                } else if (!strcmp(kind, "clear")) {                                        \
                    if (PyList_SetSlice(a.list, 0, PyList_GET_SIZE(a.list), NULL) < 0) {    \
                        Py_DECREF(results); tp_##SUF##_array_exit(&a); return NULL;         \
                    }                                                                       \
                    item = Py_NewRef(Py_None);                                              \
                }                                                                           \
                if (!item || PyList_Append(results, item) < 0) {                            \
                    Py_XDECREF(item); Py_DECREF(results);                                   \
                    tp_##SUF##_array_exit(&a);                                              \
                    return NULL;                                                            \
                }                                                                           \
                Py_DECREF(item);                                                            \
            }                                                                               \
        }                                                                                   \
        int xrc = tp_##SUF##_array_exit(&a);                                                \
        PyObject *xerr = NULL;                                                              \
        if (xrc < 0) {                                                                      \
            if (!PyErr_Occurred()) {                                                        \
                PyErr_SetString(PyExc_AssertionError, "exit rc -1 without error");          \
                Py_DECREF(results); return NULL;                                            \
            }                                                                               \
            xerr = PyErr_GetRaisedException();                                              \
        } else if (PyErr_Occurred()) {                                                      \
            Py_DECREF(results); return NULL;                                                \
        }                                                                                   \
        /* the struct must be unusable-but-safe afterwards: a second exit is a no-op */    \
        int again = tp_##SUF##_array_exit(&a);                                              \
        PyObject *ret = Py_BuildValue("(iNiNi)", erc, results, xrc,                         \
                                      xerr ? xerr : Py_NewRef(Py_None), again);             \
        return ret;                                                                         \
    }
ARRAY_RUN(f64, tp_f64_array, double, PyFloat_FromDouble, "d", double)
ARRAY_RUN(i64, tp_i64_array, int64_t, PyLong_FromLongLong, "L", long long)

static PyObject *s_array_exit_zeroed(PyObject *self, PyObject *arg)
{
    int which = (int)PyLong_AsLong(arg);
    int rc;
    if (which == 0) {
        tp_f64_array a;
        memset(&a, 0, sizeof a);
        rc = tp_f64_array_exit(&a);
    } else {
        tp_i64_array a;
        memset(&a, 0, sizeof a);
        rc = tp_i64_array_exit(&a);
    }
    if (!rc_ok(rc)) return NULL;
    return PyLong_FromLong(rc);
}

static PyObject *s_any_same(PyObject *self, PyObject *seq)
{
    PyObject *fast = PySequence_Fast(seq, "sequence expected");
    if (!fast) return NULL;
    int r = tp_any_same(PySequence_Fast_ITEMS(fast), PySequence_Fast_GET_SIZE(fast));
    Py_DECREF(fast);
    return PyLong_FromLong(r);
}


/* ---- opaque objects ---- */
static PyObject *s_global(PyObject *self, PyObject *args)
{
    PyObject *d, *name;
    if (!PyArg_ParseTuple(args, "OO", &d, &name)) return NULL;
    return tp_global(d, name);
}
static PyObject *s_getattr(PyObject *self, PyObject *args)
{
    PyObject *o, *name;
    if (!PyArg_ParseTuple(args, "OO", &o, &name)) return NULL;
    return tp_getattr(o, name);
}
/* call(callee, args_tuple, kwnames_tuple_or_None): the trailing len(kwnames) args are keywords */
static PyObject *s_call(PyObject *self, PyObject *args)
{
    PyObject *callee, *argt, *kw;
    if (!PyArg_ParseTuple(args, "OO!O", &callee, &PyTuple_Type, &argt, &kw)) return NULL;
    if (kw != Py_None && (!PyTuple_Check(kw) || PyTuple_GET_SIZE(kw) > PyTuple_GET_SIZE(argt))) {
        PyErr_SetString(PyExc_ValueError, "shim: kwnames must be a tuple no longer than args");
        return NULL;
    }
    /* vectorcall: nargs counts the positional arguments only; the keyword values follow them */
    return tp_call(callee, &PyTuple_GET_ITEM(argt, 0),
                   (size_t)(PyTuple_GET_SIZE(argt) - (kw == Py_None ? 0 : PyTuple_GET_SIZE(kw))),
                   kw == Py_None ? NULL : kw);
}
/* call_many: n calls in a row, results dropped (for refcount stability checks) */
static PyObject *s_call_many(PyObject *self, PyObject *args)
{
    PyObject *callee, *argt;
    long n;
    if (!PyArg_ParseTuple(args, "OO!l", &callee, &PyTuple_Type, &argt, &n)) return NULL;
    long errors = 0;
    for (long i = 0; i < n; i++) {
        PyObject *r = tp_call(callee, &PyTuple_GET_ITEM(argt, 0), (size_t)PyTuple_GET_SIZE(argt), NULL);
        if (r == NULL) { errors++; PyErr_Clear(); } else { tp_release(&r); }
    }
    return PyLong_FromLong(errors);
}
static PyObject *s_getattr_many(PyObject *self, PyObject *args)
{
    PyObject *o, *name;
    long n;
    if (!PyArg_ParseTuple(args, "OOl", &o, &name, &n)) return NULL;
    long errors = 0;
    for (long i = 0; i < n; i++) {
        PyObject *r = tp_getattr(o, name);
        if (r == NULL) { errors++; PyErr_Clear(); } else { tp_release(&r); }
    }
    return PyLong_FromLong(errors);
}
static PyObject *s_binop_many(PyObject *self, PyObject *args)
{
    PyObject *a, *b;
    int op;
    long n;
    if (!PyArg_ParseTuple(args, "OOil", &a, &b, &op, &n)) return NULL;
    long errors = 0;
    for (long i = 0; i < n; i++) {
        PyObject *r = tp_binop_obj(a, b, op);
        if (r == NULL) { errors++; PyErr_Clear(); } else { tp_release(&r); }
    }
    return PyLong_FromLong(errors);
}
static PyObject *s_truth(PyObject *self, PyObject *o)
{
    int rc = tp_truth(o);
    if (rc < 0) { if (!PyErr_Occurred()) PyErr_SetString(PyExc_AssertionError, "-1 w/o error"); return NULL; }
    if (PyErr_Occurred()) return NULL;
    return PyLong_FromLong(rc);
}
static PyObject *s_compare_bool(PyObject *self, PyObject *args)
{
    PyObject *a, *b;
    int op;
    if (!PyArg_ParseTuple(args, "OOi", &a, &b, &op)) return NULL;
    int rc = tp_compare_bool(a, b, op);
    if (rc < 0) { if (!PyErr_Occurred()) PyErr_SetString(PyExc_AssertionError, "-1 w/o error"); return NULL; }
    if (PyErr_Occurred()) return NULL;
    return PyLong_FromLong(rc);
}
static PyObject *s_obj_to_f64(PyObject *self, PyObject *o)
{
    double out = 0;
    int rc = tp_obj_to_f64(o, &out);
    if (!rc_ok(rc)) return NULL;
    return PyFloat_FromDouble(out);
}
static PyObject *s_binop_obj(PyObject *self, PyObject *args)
{
    PyObject *a, *b;
    int op;
    if (!PyArg_ParseTuple(args, "OOi", &a, &b, &op)) return NULL;
    PyObject *r = tp_binop_obj(a, b, op);
    if (r == NULL && !PyErr_Occurred()) PyErr_SetString(PyExc_AssertionError, "NULL w/o error");
    return r;
}
/* release(obj) -> True if the slot was cleared; the shim owns one extra reference meanwhile */
static PyObject *s_release(PyObject *self, PyObject *o)
{
    PyObject *slot = Py_NewRef(o);
    tp_release(&slot);
    PyObject *null_slot = NULL;
    tp_release(&null_slot);  /* releasing an empty slot is a no-op */
    return PyBool_FromLong(slot == NULL && null_slot == NULL);
}

static PyObject *s_info(PyObject *self, PyObject *unused)
{
    return Py_BuildValue("{s:i,s:i}", "portable", TP_RUNTIME_PORTABLE_OVERFLOW,
                         "py_version_hex", (int)PY_VERSION_HEX);
}

#define M(N, F, FL) {N, (PyCFunction)(void (*)(void))F, FL, NULL}
static PyMethodDef methods[] = {
    M("add_i64", s_add_i64, METH_VARARGS), M("sub_i64", s_sub_i64, METH_VARARGS),
    M("mul_i64", s_mul_i64, METH_VARARGS), M("neg_i64", s_neg_i64, METH_VARARGS),
    M("floordiv_i64", s_floordiv_i64, METH_VARARGS), M("mod_i64", s_mod_i64, METH_VARARGS),
    M("truediv_i64", s_truediv_i64, METH_VARARGS), M("i64_to_f64", s_i64_to_f64, METH_VARARGS),
    M("truediv_f64", s_truediv_f64, METH_VARARGS), M("floordiv_f64", s_floordiv_f64, METH_VARARGS),
    M("mod_f64", s_mod_f64, METH_VARARGS),
    M("cmp_i64_f64", s_cmp_i64_f64, METH_VARARGS), M("cmp_f64_i64", s_cmp_f64_i64, METH_VARARGS),
    M("math1", s_math1, METH_VARARGS), M("math2", s_math2, METH_VARARGS),
    M("box_i64", s_box_i64, METH_VARARGS), M("box_f64", s_box_f64, METH_VARARGS),
    M("box_bool", s_box_bool, METH_VARARGS),
    M("unbox_i64", s_unbox_i64, METH_O), M("unbox_f64", s_unbox_f64, METH_O),
    M("unbox_bool", s_unbox_bool, METH_O),
    M("f64_array_run", s_f64_array_run, METH_VARARGS), M("i64_array_run", s_i64_array_run, METH_VARARGS),
    M("array_exit_zeroed", s_array_exit_zeroed, METH_O),
    M("global_", s_global, METH_VARARGS), M("getattr_", s_getattr, METH_VARARGS),
    M("call", s_call, METH_VARARGS), M("call_many", s_call_many, METH_VARARGS),
    M("getattr_many", s_getattr_many, METH_VARARGS), M("binop_many", s_binop_many, METH_VARARGS),
    M("truth", s_truth, METH_O), M("compare_bool", s_compare_bool, METH_VARARGS),
    M("obj_to_f64", s_obj_to_f64, METH_O), M("binop_obj", s_binop_obj, METH_VARARGS),
    M("release", s_release, METH_O),
    M("any_same", s_any_same, METH_O), M("info", s_info, METH_NOARGS),
    {NULL, NULL, 0, NULL}};

static struct PyModuleDef moddef = {PyModuleDef_HEAD_INIT, TP_STR(TP_SHIM_NAME), NULL, -1, methods,
                                    NULL, NULL, NULL, NULL};

PyMODINIT_FUNC TP_CAT(PyInit_, TP_SHIM_NAME)(void) { return PyModule_Create(&moddef); }
