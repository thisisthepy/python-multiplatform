# tp_runtime.h — the C runtime API generated code may call

Generated C (cgen.py) reaches memory and `PyObject`s **only** through these helpers (SPEC N-8).
Header-only, `static inline`, C99 + CPython C API, no other dependencies. Every helper implements
the CPython behaviour named in `ir.py`. Return convention unless stated: `0` ok, `1` deopt (no
Python error set, nothing to undo), `-1` Python error set (CPython's exception type and message).

## Integers (int64_t)
    int tp_add_i64(int64_t a, int64_t b, int64_t *out);      /* 0 / 1 on overflow */
    int tp_sub_i64(int64_t a, int64_t b, int64_t *out);
    int tp_mul_i64(int64_t a, int64_t b, int64_t *out);
    int tp_neg_i64(int64_t a, int64_t *out);                 /* 1 for INT64_MIN */
    int tp_floordiv_i64(int64_t a, int64_t b, int64_t *out); /* -1 ZeroDivisionError; 1 for MIN // -1 */
    int tp_mod_i64(int64_t a, int64_t b, int64_t *out);      /* -1 ZeroDivisionError; sign of b */
    int tp_truediv_i64(int64_t a, int64_t b, double *out);   /* -1 ZeroDivisionError; 1 if |a| or |b| > 2**53 */
    double tp_i64_to_f64(int64_t a);                         /* as float(int): round half to even */

## Floats (double)
    int tp_truediv_f64(double a, double b, double *out);     /* 0 / -1 ZeroDivisionError("float division by zero") */
    int tp_floordiv_f64(double a, double b, double *out);    /* CPython float_floor_div semantics */
    int tp_mod_f64(double a, double b, double *out);         /* CPython float_rem semantics */

## Comparisons
    int tp_cmp_i64_f64(int64_t a, double b, int op);         /* exact, op is Py_LT..Py_GE; returns 0/1 */
    int tp_cmp_f64_i64(double a, int64_t b, int op);

## math (stdlib math module semantics: math_1 / math_2 errno and inf/nan rules)
    int tp_math_sqrt(double x, double *out);   /* -1 ValueError("math domain error") */
    int tp_math_exp(double x, double *out);    /* -1 OverflowError("math range error") */
    int tp_math_log(double x, double *out);
    int tp_math_sin(double x, double *out);
    int tp_math_cos(double x, double *out);
    int tp_math_tan(double x, double *out);
    int tp_math_fabs(double x, double *out);
    int tp_math_atan2(double y, double x, double *out);
    int tp_math_hypot(double x, double y, double *out);

## Boxing (new references; NULL with MemoryError set on failure)
    PyObject *tp_box_i64(int64_t v);
    PyObject *tp_box_f64(double v);
    PyObject *tp_box_bool(int v);
    /* exact-type unboxing for guards: 0 ok, 1 wrong exact type or out of i64 range (deopt) */
    int tp_unbox_i64(PyObject *o, int64_t *out);   /* type(o) is int and fits */
    int tp_unbox_f64(PyObject *o, double *out);    /* type(o) is float */
    int tp_unbox_bool(PyObject *o, int *out);      /* o is Py_True / Py_False */

## Native arrays for list[float] / list[int] parameters (ir.ArrayParam)
    typedef struct { PyObject *list; Py_ssize_t len; double  *data; unsigned char *dirty; } tp_f64_array;
    typedef struct { PyObject *list; Py_ssize_t len; int64_t *data; unsigned char *dirty; } tp_i64_array;

    int  tp_f64_array_enter(PyObject *obj, tp_f64_array *a);  /* 1 unless exactly list of exactly float; -1 MemoryError */
    int  tp_i64_array_enter(PyObject *obj, tp_i64_array *a);  /* 1 unless exactly list of exactly int in i64 range */
    /* resolve a Python index (negative counts from the end) to a slot; -1 IndexError with
       "list index out of range" (load) or "list assignment index out of range" (store) */
    int  tp_f64_array_slot(const tp_f64_array *a, int64_t i, int store, Py_ssize_t *slot);
    int  tp_i64_array_slot(const tp_i64_array *a, int64_t i, int store, Py_ssize_t *slot);
    /* write dirty elements back as new float/int objects and free the buffers. Always frees, even
       when it fails (then -1 with MemoryError). Safe to call on a zeroed struct. */
    int  tp_f64_array_exit(tp_f64_array *a);
    int  tp_i64_array_exit(tp_i64_array *a);
    /* aliasing guard: 1 if any two of the n objects are the same object */
    int  tp_any_same(PyObject *const *objs, Py_ssize_t n);
