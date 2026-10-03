/* tp_runtime.h -- the C runtime API generated TypedPython code may call (SPEC N-8, API.md).
 *
 * Header-only, `static inline`, C99 + the CPython C API (3.12+; exception messages follow CPython
 * 3.13 and 3.14, which differ -- see TP_MSG_* below). Every helper implements the CPython
 * behaviour named in ir.py; the differential tests in src/test/python/test_runtime.py compare each
 * helper with CPython itself, bit for bit and message for message.
 *
 * Return convention unless stated: 0 ok, 1 deopt (no Python error set, nothing to undo),
 * -1 Python error set.
 */
#ifndef TP_RUNTIME_H
#define TP_RUNTIME_H

#include <Python.h>
#include <stdint.h>
#include <math.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>

#if PY_VERSION_HEX < 0x030C0000
#error "tp_runtime.h needs CPython 3.12 or newer (PyErr_GetRaisedException)"
#endif

/* ------------------------------------------------------------------------------------------
 * Exception messages that CPython changed between 3.13 and 3.14.
 *   3.13: "integer division or modulo by zero" (int //), "integer modulo by zero" (int %),
 *         "float division by zero" / "float floor division by zero" / "float modulo by zero",
 *         "math domain error" for every math-domain ValueError.
 *   3.14: "division by zero" for all of them; math_1 domain errors name the argument
 *         ("expected a nonnegative input, got -1.0", "expected a finite input, got inf",
 *          "expected a positive input, got 0.0").
 * ------------------------------------------------------------------------------------------ */
#if PY_VERSION_HEX >= 0x030E0000
#define TP_MSG_INT_FLOORDIV_ZERO "division by zero"
#define TP_MSG_INT_MOD_ZERO "division by zero"
#define TP_MSG_FLOAT_DIV_ZERO "division by zero"
#define TP_MSG_FLOAT_FLOORDIV_ZERO "division by zero"
#define TP_MSG_FLOAT_MOD_ZERO "division by zero"
#else
#define TP_MSG_INT_FLOORDIV_ZERO "integer division or modulo by zero"
#define TP_MSG_INT_MOD_ZERO "integer modulo by zero"
#define TP_MSG_FLOAT_DIV_ZERO "float division by zero"
#define TP_MSG_FLOAT_FLOORDIV_ZERO "float floor division by zero"
#define TP_MSG_FLOAT_MOD_ZERO "float modulo by zero"
#endif
#define TP_MSG_INT_TRUEDIV_ZERO "division by zero" /* long_true_divide: same in 3.13 and 3.14 */

#define TP_MATH_MSG_NONNEG "expected a nonnegative input, got %s"
#define TP_MATH_MSG_FINITE "expected a finite input, got %s"
#define TP_MATH_MSG_POSITIVE "expected a positive input, got %s"

/* ir.BinOpKind order, for tp_binop_obj */
#define TP_BINOP_ADD 0
#define TP_BINOP_SUB 1
#define TP_BINOP_MUL 2
#define TP_BINOP_TRUEDIV 3
#define TP_BINOP_FLOORDIV 4
#define TP_BINOP_MOD 5

/* ------------------------------------------------------------------------------------------
 * Integers (int64_t)
 * ------------------------------------------------------------------------------------------ */
#if (defined(__GNUC__) || defined(__clang__)) && !defined(TP_RUNTIME_FORCE_PORTABLE_OVERFLOW)
#define TP_RUNTIME_PORTABLE_OVERFLOW 0
#else
#define TP_RUNTIME_PORTABLE_OVERFLOW 1
#endif

static inline int tp_add_i64(int64_t a, int64_t b, int64_t *out)
{
#if !TP_RUNTIME_PORTABLE_OVERFLOW
    return __builtin_add_overflow(a, b, out) ? 1 : 0;
#else
    if ((b > 0 && a > INT64_MAX - b) || (b < 0 && a < INT64_MIN - b)) {
        return 1;
    }
    *out = a + b;
    return 0;
#endif
}

static inline int tp_sub_i64(int64_t a, int64_t b, int64_t *out)
{
#if !TP_RUNTIME_PORTABLE_OVERFLOW
    return __builtin_sub_overflow(a, b, out) ? 1 : 0;
#else
    if ((b < 0 && a > INT64_MAX + b) || (b > 0 && a < INT64_MIN + b)) {
        return 1;
    }
    *out = a - b;
    return 0;
#endif
}

static inline int tp_mul_i64(int64_t a, int64_t b, int64_t *out)
{
#if !TP_RUNTIME_PORTABLE_OVERFLOW
    return __builtin_mul_overflow(a, b, out) ? 1 : 0;
#else
    if (a == 0 || b == 0) {
        *out = 0;
        return 0;
    }
    if (a == -1) {
        if (b == INT64_MIN) return 1;
        *out = -b;
        return 0;
    }
    if (b == -1) {
        if (a == INT64_MIN) return 1;
        *out = -a;
        return 0;
    }
    if (a > 0) {
        if (b > 0 ? a > INT64_MAX / b : b < INT64_MIN / a) return 1;
    }
    else {
        if (b > 0 ? a < INT64_MIN / b : a < INT64_MAX / b) return 1;
    }
    *out = a * b;
    return 0;
#endif
}

static inline int tp_neg_i64(int64_t a, int64_t *out)
{
    if (a == INT64_MIN) {
        return 1;
    }
    *out = -a;
    return 0;
}

/* Python floor division: the quotient rounds toward negative infinity. */
static inline int tp_floordiv_i64(int64_t a, int64_t b, int64_t *out)
{
    if (b == 0) {
        PyErr_SetString(PyExc_ZeroDivisionError, TP_MSG_INT_FLOORDIV_ZERO);
        return -1;
    }
    if (b == -1) {
        if (a == INT64_MIN) {
            return 1; /* 2**63 does not fit */
        }
        *out = -a;
        return 0;
    }
    int64_t q = a / b;
    int64_t r = a % b;
    if (r != 0 && ((r < 0) != (b < 0))) {
        q -= 1;
    }
    *out = q;
    return 0;
}

/* Python modulo: the result has the sign of the divisor. */
static inline int tp_mod_i64(int64_t a, int64_t b, int64_t *out)
{
    if (b == 0) {
        PyErr_SetString(PyExc_ZeroDivisionError, TP_MSG_INT_MOD_ZERO);
        return -1;
    }
    if (b == -1) {
        *out = 0; /* also INT64_MIN % -1, which traps in C */
        return 0;
    }
    int64_t r = a % b;
    if (r != 0 && ((r < 0) != (b < 0))) {
        r += b;
    }
    *out = r;
    return 0;
}

/* CPython rounds int/int correctly; the C double division is the correctly rounded quotient when
 * both operands are exactly representable, i.e. |x| <= 2**53. Otherwise: deopt. A zero divisor is
 * an error whatever the dividend. */
static inline int tp_truediv_i64(int64_t a, int64_t b, double *out)
{
    const int64_t lim = (int64_t)1 << 53;
    if (b == 0) {
        PyErr_SetString(PyExc_ZeroDivisionError, TP_MSG_INT_TRUEDIV_ZERO);
        return -1;
    }
    if (a > lim || a < -lim || b > lim || b < -lim) {
        return 1;
    }
    *out = (double)a / (double)b;
    return 0;
}

/* float(int): the C conversion rounds to nearest, ties to even in the default rounding mode. */
static inline double tp_i64_to_f64(int64_t a)
{
    return (double)a;
}

/* ------------------------------------------------------------------------------------------
 * Floats (double)
 * ------------------------------------------------------------------------------------------ */
static inline int tp_truediv_f64(double a, double b, double *out)
{
    if (b == 0.0) {
        PyErr_SetString(PyExc_ZeroDivisionError, TP_MSG_FLOAT_DIV_ZERO);
        return -1;
    }
    *out = a / b;
    return 0;
}

/* floatobject.c: _float_div_mod */
static inline void tp__float_div_mod(double vx, double wx, double *floordiv, double *mod)
{
    double div;
    *mod = fmod(vx, wx);
    div = (vx - *mod) / wx;
    if (*mod) {
        if ((wx < 0) != (*mod < 0)) {
            *mod += wx;
            div -= 1.0;
        }
    }
    else {
        *mod = copysign(0.0, wx);
    }
    if (div) {
        *floordiv = floor(div);
        if (div - *floordiv > 0.5) {
            *floordiv += 1.0;
        }
    }
    else {
        *floordiv = copysign(0.0, vx / wx);
    }
}

static inline int tp_floordiv_f64(double a, double b, double *out)
{
    double mod, floordiv;
    if (b == 0.0) {
        PyErr_SetString(PyExc_ZeroDivisionError, TP_MSG_FLOAT_FLOORDIV_ZERO);
        return -1;
    }
    tp__float_div_mod(a, b, &floordiv, &mod);
    *out = floordiv;
    return 0;
}

/* floatobject.c: float_rem */
static inline int tp_mod_f64(double a, double b, double *out)
{
    double mod;
    if (b == 0.0) {
        PyErr_SetString(PyExc_ZeroDivisionError, TP_MSG_FLOAT_MOD_ZERO);
        return -1;
    }
    mod = fmod(a, b);
    if (mod) {
        if ((b < 0) != (mod < 0)) {
            mod += b;
        }
    }
    else {
        mod = copysign(0.0, b);
    }
    *out = mod;
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Comparisons: exact int/float comparison (float_richcompare), never via (double)int.
 * ------------------------------------------------------------------------------------------ */
static inline int tp__apply_cmp(int c, int op)
{
    switch (op) {
    case Py_LT: return c < 0;
    case Py_LE: return c <= 0;
    case Py_EQ: return c == 0;
    case Py_NE: return c != 0;
    case Py_GT: return c > 0;
    case Py_GE: return c >= 0;
    }
    return 0;
}

/* three-way compare of an int64 with a non-NaN double: -1, 0, 1 */
static inline int tp__cmp3_i64_f64(int64_t a, double b)
{
    if (b >= 9223372036854775808.0) return -1;  /* b >= 2**63 > any int64 (also +inf) */
    if (b < -9223372036854775808.0) return 1;   /* b < -2**63 <= any int64 (also -inf) */
    int64_t bi = (int64_t)b;                    /* truncation toward zero, b in range */
    if (a < bi) return -1;
    if (a > bi) return 1;
    double frac = b - (double)bi;               /* exact: bi is b with the fraction dropped */
    if (frac > 0.0) return -1;                  /* b = a + frac */
    if (frac < 0.0) return 1;
    return 0;
}

static inline int tp_cmp_i64_f64(int64_t a, double b, int op)
{
    if (b != b) {
        return op == Py_NE;
    }
    return tp__apply_cmp(tp__cmp3_i64_f64(a, b), op);
}

static inline int tp_cmp_f64_i64(double a, int64_t b, int op)
{
    if (a != a) {
        return op == Py_NE;
    }
    return tp__apply_cmp(-tp__cmp3_i64_f64(b, a), op);
}

/* ------------------------------------------------------------------------------------------
 * math (mathmodule.c: math_1 / math_2 / is_error rules)
 * ------------------------------------------------------------------------------------------ */
static inline void tp__math_domain_error(double x, const char *msg314)
{
#if PY_VERSION_HEX >= 0x030E0000
    if (msg314) {
        char *buf = PyOS_double_to_string(x, 'r', 0, Py_DTSF_ADD_DOT_0, NULL);
        if (buf) {
            PyErr_Format(PyExc_ValueError, msg314, buf);
            PyMem_Free(buf);
        }
        return;
    }
#else
    (void)x;
    (void)msg314;
#endif
    PyErr_SetString(PyExc_ValueError, "math domain error");
}

/* mathmodule.c is_error: 1 if an exception was set, 0 if the errno is to be ignored */
static inline int tp__math_is_error(double x)
{
    int result = 1;
    if (errno == EDOM) {
        PyErr_SetString(PyExc_ValueError, "math domain error");
    }
    else if (errno == ERANGE) {
        if (fabs(x) < 1.5) {
            result = 0;
        }
        else {
            PyErr_SetString(PyExc_OverflowError, "math range error");
        }
    }
    else {
        PyErr_SetFromErrno(PyExc_ValueError);
    }
    return result;
}

/* math_1: x is the argument, r the libm result computed with errno cleared just before */
static inline int tp__math_1_finish(double x, double r, int can_overflow, const char *msg314,
                                    double *out)
{
    if (isnan(r) && !isnan(x)) {
        tp__math_domain_error(x, msg314);
        return -1;
    }
    if (isinf(r) && isfinite(x)) {
        if (can_overflow) {
            PyErr_SetString(PyExc_OverflowError, "math range error");
        }
        else {
            tp__math_domain_error(x, msg314);
        }
        return -1;
    }
    if (isfinite(r) && errno && tp__math_is_error(r)) {
        return -1;
    }
    *out = r;
    return 0;
}

/* mathmodule.c m_log */
static inline double tp__m_log(double x)
{
    if (isfinite(x)) {
        if (x > 0.0) return log(x);
        errno = EDOM;
        if (x == 0.0) return -HUGE_VAL;
        return NAN;
    }
    else if (isnan(x)) {
        return x;
    }
    else if (x > 0.0) {
        return x;
    }
    errno = EDOM;
    return NAN;
}

#define TP__DEF_MATH1(NAME, CALL, CAN_OVERFLOW, MSG)                                          \
    static inline int tp_math_##NAME(double x, double *out)                                    \
    {                                                                                          \
        double r;                                                                              \
        errno = 0;                                                                             \
        r = CALL;                                                                              \
        return tp__math_1_finish(x, r, CAN_OVERFLOW, MSG, out);                                \
    }
TP__DEF_MATH1(sqrt, sqrt(x), 0, TP_MATH_MSG_NONNEG)
TP__DEF_MATH1(exp, exp(x), 1, NULL)
TP__DEF_MATH1(log, tp__m_log(x), 0, TP_MATH_MSG_POSITIVE)
TP__DEF_MATH1(sin, sin(x), 0, TP_MATH_MSG_FINITE)
TP__DEF_MATH1(cos, cos(x), 0, TP_MATH_MSG_FINITE)
TP__DEF_MATH1(tan, tan(x), 0, TP_MATH_MSG_FINITE)
TP__DEF_MATH1(fabs, fabs(x), 0, NULL)
#undef TP__DEF_MATH1

/* mathmodule.c m_atan2: the special values are defined here, the rest is libm */
static inline double tp__m_atan2(double y, double x)
{
    const double pi = 3.14159265358979323846;
    if (isnan(x) || isnan(y)) return NAN;
    if (isinf(y)) {
        if (isinf(x)) {
            if (copysign(1., x) == 1.) return copysign(0.25 * pi, y);
            return copysign(0.75 * pi, y);
        }
        return copysign(0.5 * pi, y);
    }
    if (isinf(x) || y == 0.) {
        if (copysign(1., x) == 1.) return copysign(0., y);
        return copysign(pi, y);
    }
    return atan2(y, x);
}

/* math_2 rules for atan2 */
static inline int tp_math_atan2(double y, double x, double *out)
{
    double r;
    errno = 0;
    r = tp__m_atan2(y, x);
    if (isnan(r)) {
        errno = (!isnan(x) && !isnan(y)) ? EDOM : 0;
    }
    else if (isinf(r)) {
        errno = (isfinite(x) && isfinite(y)) ? ERANGE : 0;
    }
    if (errno && tp__math_is_error(r)) {
        return -1;
    }
    *out = r;
    return 0;
}

#define TP__DBL_MIN 2.2250738585072014e-308 /* DBL_MIN, without <float.h> */

/* mathmodule.c vector_norm (3.13 and 3.14 are identical): the correctly rounded hypot */
typedef struct { double hi; double lo; } tp__dl;

static inline tp__dl tp__dl_fast_sum(double a, double b)
{
    double x = a + b;
    double y = (a - x) + b;
    tp__dl r = {x, y};
    return r;
}

static inline tp__dl tp__dl_mul(double x, double y)
{
    double z = x * y;
    double zz = fma(x, y, -z);
    tp__dl r = {z, zz};
    return r;
}

static inline double tp__vector_norm(Py_ssize_t n, double *vec, double max, int found_nan)
{
    double x, h, scale, csum = 1.0, frac1 = 0.0, frac2 = 0.0;
    tp__dl pr, sm;
    int max_e;
    Py_ssize_t i;

    if (isinf(max)) return max;
    if (found_nan) return NAN;
    if (max == 0.0 || n <= 1) return max;
    frexp(max, &max_e);
    if (max_e < -1023) {
        for (i = 0; i < n; i++) {
            vec[i] /= TP__DBL_MIN;
        }
        return TP__DBL_MIN * tp__vector_norm(n, vec, max / TP__DBL_MIN, found_nan);
    }
    scale = ldexp(1.0, -max_e);
    for (i = 0; i < n; i++) {
        x = vec[i];
        x *= scale;
        pr = tp__dl_mul(x, x);
        sm = tp__dl_fast_sum(csum, pr.hi);
        csum = sm.hi;
        frac1 += pr.lo;
        frac2 += sm.lo;
    }
    h = sqrt(csum - 1.0 + (frac1 + frac2));
    pr = tp__dl_mul(-h, h);
    sm = tp__dl_fast_sum(csum, pr.hi);
    csum = sm.hi;
    frac1 += pr.lo;
    frac2 += sm.lo;
    x = csum - 1.0 + (frac1 + frac2);
    h += x / (2.0 * h);
    return h / scale;
}

static inline int tp_math_hypot(double x, double y, double *out)
{
    double vec[2];
    double max = 0.0;
    int found_nan;
    vec[0] = fabs(x);
    vec[1] = fabs(y);
    found_nan = isnan(vec[0]) | isnan(vec[1]);
    if (vec[0] > max) max = vec[0];
    if (vec[1] > max) max = vec[1];
    *out = tp__vector_norm(2, vec, max, found_nan);
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Boxing
 * ------------------------------------------------------------------------------------------ */
static inline PyObject *tp_box_i64(int64_t v) { return PyLong_FromLongLong((long long)v); }
static inline PyObject *tp_box_f64(double v) { return PyFloat_FromDouble(v); }
static inline PyObject *tp_box_bool(int v) { return PyBool_FromLong(v ? 1 : 0); }

static inline int tp_unbox_i64(PyObject *o, int64_t *out)
{
    int overflow = 0;
    long long v;
    if (!PyLong_CheckExact(o)) {
        return 1;
    }
    v = PyLong_AsLongLongAndOverflow(o, &overflow);
    if (overflow) {
        return 1;
    }
    if (v == -1 && PyErr_Occurred()) {
        PyErr_Clear(); /* cannot happen for an exact int; deopt rather than leak an error */
        return 1;
    }
    *out = (int64_t)v;
    return 0;
}

static inline int tp_unbox_f64(PyObject *o, double *out)
{
    if (!PyFloat_CheckExact(o)) {
        return 1;
    }
    *out = PyFloat_AS_DOUBLE(o);
    return 0;
}

static inline int tp_unbox_bool(PyObject *o, int *out)
{
    if (o == Py_True) {
        *out = 1;
        return 0;
    }
    if (o == Py_False) {
        *out = 0;
        return 0;
    }
    return 1;
}

/* ------------------------------------------------------------------------------------------
 * Native arrays for list[float] / list[int] parameters (ir.ArrayParam)
 *
 * enter: rc 0 -> the struct holds the copy; rc 1 or -1 -> the struct is zeroed (so exit is a safe
 * no-op) and nothing was changed. `list` is borrowed: the caller keeps the argument alive.
 * exit: writes the dirty slots back as new objects (PyList_SetItem steals the new reference and, on
 * failure, drops it), always frees, and zeroes the struct. On a write-back failure it stops,
 * frees, and returns -1 with that error set (MemoryError, or IndexError if the list shrank).
 * ------------------------------------------------------------------------------------------ */
typedef struct { PyObject *list; Py_ssize_t len; double *data; unsigned char *dirty; } tp_f64_array;
typedef struct { PyObject *list; Py_ssize_t len; int64_t *data; unsigned char *dirty; } tp_i64_array;

static inline int tp_f64_array_enter(PyObject *obj, tp_f64_array *a)
{
    Py_ssize_t n, i;
    a->list = NULL;
    a->len = 0;
    a->data = NULL;
    a->dirty = NULL;
    if (!PyList_CheckExact(obj)) {
        return 1;
    }
    n = PyList_GET_SIZE(obj);
    for (i = 0; i < n; i++) {
        if (!PyFloat_CheckExact(PyList_GET_ITEM(obj, i))) {
            return 1;
        }
    }
    a->data = (double *)malloc(((size_t)n ? (size_t)n : 1) * sizeof(double));
    a->dirty = (unsigned char *)calloc((size_t)n ? (size_t)n : 1, 1);
    if (a->data == NULL || a->dirty == NULL) {
        free(a->data);
        free(a->dirty);
        a->data = NULL;
        a->dirty = NULL;
        PyErr_NoMemory();
        return -1;
    }
    for (i = 0; i < n; i++) {
        a->data[i] = PyFloat_AS_DOUBLE(PyList_GET_ITEM(obj, i));
    }
    a->list = obj;
    a->len = n;
    return 0;
}

static inline int tp_i64_array_enter(PyObject *obj, tp_i64_array *a)
{
    Py_ssize_t n, i;
    a->list = NULL;
    a->len = 0;
    a->data = NULL;
    a->dirty = NULL;
    if (!PyList_CheckExact(obj)) {
        return 1;
    }
    n = PyList_GET_SIZE(obj);
    a->data = (int64_t *)malloc(((size_t)n ? (size_t)n : 1) * sizeof(int64_t));
    a->dirty = (unsigned char *)calloc((size_t)n ? (size_t)n : 1, 1);
    if (a->data == NULL || a->dirty == NULL) {
        free(a->data);
        free(a->dirty);
        a->data = NULL;
        a->dirty = NULL;
        PyErr_NoMemory();
        return -1;
    }
    for (i = 0; i < n; i++) {
        if (tp_unbox_i64(PyList_GET_ITEM(obj, i), &a->data[i]) != 0) {
            free(a->data);
            free(a->dirty);
            a->data = NULL;
            a->dirty = NULL;
            return 1;
        }
    }
    a->list = obj;
    a->len = n;
    return 0;
}

/* list indexing: a negative index counts from the end; out of range -> IndexError */
static inline int tp__array_slot(Py_ssize_t len, int64_t i, int store, Py_ssize_t *slot)
{
    if (i < 0) {
        if (i < -(int64_t)len) goto out_of_range;
        i += (int64_t)len;
    }
    else if (i >= (int64_t)len) {
        goto out_of_range;
    }
    *slot = (Py_ssize_t)i;
    return 0;
out_of_range:
    PyErr_SetString(PyExc_IndexError,
                    store ? "list assignment index out of range" : "list index out of range");
    return -1;
}

static inline int tp_f64_array_slot(const tp_f64_array *a, int64_t i, int store, Py_ssize_t *slot)
{
    return tp__array_slot(a->len, i, store, slot);
}

static inline int tp_i64_array_slot(const tp_i64_array *a, int64_t i, int store, Py_ssize_t *slot)
{
    return tp__array_slot(a->len, i, store, slot);
}

static inline int tp_f64_array_exit(tp_f64_array *a)
{
    int rc = 0;
    if (a->data != NULL && a->dirty != NULL && a->list != NULL) {
        Py_ssize_t i;
        for (i = 0; i < a->len; i++) {
            PyObject *v;
            if (!a->dirty[i]) {
                continue;
            }
            v = PyFloat_FromDouble(a->data[i]);
            if (v == NULL || PyList_SetItem(a->list, i, v) < 0) {
                rc = -1; /* SetItem consumed v on failure; the error is set */
                break;
            }
        }
    }
    free(a->data);
    free(a->dirty);
    a->list = NULL;
    a->len = 0;
    a->data = NULL;
    a->dirty = NULL;
    return rc;
}

static inline int tp_i64_array_exit(tp_i64_array *a)
{
    int rc = 0;
    if (a->data != NULL && a->dirty != NULL && a->list != NULL) {
        Py_ssize_t i;
        for (i = 0; i < a->len; i++) {
            PyObject *v;
            if (!a->dirty[i]) {
                continue;
            }
            v = PyLong_FromLongLong((long long)a->data[i]);
            if (v == NULL || PyList_SetItem(a->list, i, v) < 0) {
                rc = -1;
                break;
            }
        }
    }
    free(a->data);
    free(a->dirty);
    a->list = NULL;
    a->len = 0;
    a->data = NULL;
    a->dirty = NULL;
    return rc;
}

/* aliasing guard: 1 if any two of the n objects are the same object */
static inline int tp_any_same(PyObject *const *objs, Py_ssize_t n)
{
    Py_ssize_t i, j;
    for (i = 0; i < n; i++) {
        for (j = i + 1; j < n; j++) {
            if (objs[i] == objs[j]) {
                return 1;
            }
        }
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Opaque objects (Kotlin interop and any Python object). All return new references or NULL with
 * CPython's exception set; none deopts.
 * ------------------------------------------------------------------------------------------ */

/* LOAD_GLOBAL: module globals, then builtins, looked up now; NameError("name 'x' is not defined")
 * with .name set, as ceval's format_exc_check_arg does. `module_dict` is the module's __dict__;
 * its "__builtins__" (a module or a dict) is searched next, the interpreter's builtins if absent. */
static inline PyObject *tp_global(PyObject *module_dict, PyObject *name)
{
    PyObject *v, *builtins;
    v = PyDict_GetItemWithError(module_dict, name);
    if (v != NULL) {
        Py_INCREF(v);
        return v;
    }
    if (PyErr_Occurred()) {
        return NULL;
    }
    builtins = PyDict_GetItemString(module_dict, "__builtins__"); /* borrowed, errors ignored */
    if (builtins != NULL && PyModule_Check(builtins)) {
        builtins = PyModule_GetDict(builtins);
    }
    if (builtins == NULL || !PyDict_Check(builtins)) {
        builtins = PyEval_GetBuiltins();
    }
    if (builtins != NULL) {
        v = PyDict_GetItemWithError(builtins, name);
        if (v != NULL) {
            Py_INCREF(v);
            return v;
        }
        if (PyErr_Occurred()) {
            return NULL;
        }
    }
    {
        PyObject *exc;
        PyErr_Format(PyExc_NameError, "name '%.200U' is not defined", name);
        exc = PyErr_GetRaisedException();
        if (exc != NULL) {
            if (PyObject_SetAttrString(exc, "name", name) < 0) {
                PyErr_Clear();
            }
            PyErr_SetRaisedException(exc);
        }
    }
    return NULL;
}

static inline PyObject *tp_getattr(PyObject *obj, PyObject *name)
{
    return PyObject_GetAttr(obj, name);
}

/* vectorcall: the last len(kwnames) of the nargs values are keyword values */
static inline PyObject *tp_call(PyObject *callee, PyObject *const *args, size_t nargs,
                                PyObject *kwnames)
{
    return PyObject_Vectorcall(callee, args, nargs, kwnames);
}

static inline int tp_truth(PyObject *obj)
{
    return PyObject_IsTrue(obj);
}

/* A comparison in a condition, as CPython's `if a < b:` runs it: the rich comparison, then the truth
 * value of its result. NOT PyObject_RichCompareBool: that one short-circuits `a is b` for == and !=
 * (so a NaN would equal itself), which `if x == x:` does not. */
static inline int tp_compare_bool(PyObject *a, PyObject *b, int op)
{
    int r;
    PyObject *res = PyObject_RichCompare(a, b, op);
    if (res == NULL) {
        return -1;
    }
    r = PyObject_IsTrue(res);
    Py_DECREF(res);
    return r;
}

/* float(obj): PyNumber_Float, then the double of the result */
static inline int tp_obj_to_f64(PyObject *obj, double *out)
{
    PyObject *f = PyNumber_Float(obj);
    if (f == NULL) {
        return -1;
    }
    *out = PyFloat_AS_DOUBLE(f);
    Py_DECREF(f);
    return 0;
}

/* op is an ir.BinOpKind: TP_BINOP_ADD .. TP_BINOP_MOD (declaration order) */
static inline PyObject *tp_binop_obj(PyObject *a, PyObject *b, int op)
{
    switch (op) {
    case TP_BINOP_ADD: return PyNumber_Add(a, b);
    case TP_BINOP_SUB: return PyNumber_Subtract(a, b);
    case TP_BINOP_MUL: return PyNumber_Multiply(a, b);
    case TP_BINOP_TRUEDIV: return PyNumber_TrueDivide(a, b);
    case TP_BINOP_FLOORDIV: return PyNumber_FloorDivide(a, b);
    case TP_BINOP_MOD: return PyNumber_Remainder(a, b);
    }
    PyErr_SetString(PyExc_SystemError, "tp_binop_obj: unknown operator");
    return NULL;
}

/* Py_CLEAR: the only way generated code drops a reference */
static inline void tp_release(PyObject **slot)
{
    Py_CLEAR(*slot);
}

#endif /* TP_RUNTIME_H */
