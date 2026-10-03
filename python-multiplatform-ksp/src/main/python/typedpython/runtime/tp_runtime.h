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

/* ------------------------------------------------------------------------------------------
 * Local arrays (ir.NewArray / CopyArray): owned by the function, no list object behind them.
 * Same structs as above with list == NULL and dirty == NULL; the slot helpers work on them
 * unchanged. rc 0 ok, -1 MemoryError (the struct is then zeroed, so _free is still safe).
 * n < 0 gives an empty array, like CPython's [x] * -1 == []. A size that overflows
 * n * sizeof(element) is a MemoryError, not a wrapped small allocation. _free never writes
 * anything back, zeroes the struct and is idempotent. _exit on a local array is defined to
 * behave exactly like _free (nothing to write back since list == NULL, rc 0), so a stray _exit
 * is harmless; local arrays are still released with _free.
 * ------------------------------------------------------------------------------------------ */
static inline void *tp__local_alloc(int64_t n, size_t elem, Py_ssize_t *len)
{
    void *p;
    if (n < 0) {
        n = 0;
    }
    if (n > (int64_t)PY_SSIZE_T_MAX || (uint64_t)n > (uint64_t)(SIZE_MAX / elem)) {
        PyErr_NoMemory();
        return NULL;
    }
    p = malloc(n ? (size_t)n * elem : elem);
    if (p == NULL) {
        PyErr_NoMemory();
        return NULL;
    }
    *len = (Py_ssize_t)n;
    return p;
}

static inline int tp_i64_array_new(tp_i64_array *a, int64_t n, int64_t fill)
{
    Py_ssize_t len = 0, i;
    int64_t *p;
    a->list = NULL; a->len = 0; a->data = NULL; a->dirty = NULL;
    p = (int64_t *)tp__local_alloc(n, sizeof(int64_t), &len);
    if (p == NULL) {
        return -1;
    }
    for (i = 0; i < len; i++) {
        p[i] = fill;
    }
    a->data = p;
    a->len = len;
    return 0;
}

static inline int tp_i64_array_iota(tp_i64_array *a, int64_t n)
{
    Py_ssize_t len = 0, i;
    int64_t *p;
    a->list = NULL; a->len = 0; a->data = NULL; a->dirty = NULL;
    p = (int64_t *)tp__local_alloc(n, sizeof(int64_t), &len);
    if (p == NULL) {
        return -1;
    }
    for (i = 0; i < len; i++) {
        p[i] = (int64_t)i;
    }
    a->data = p;
    a->len = len;
    return 0;
}

static inline int tp_f64_array_new(tp_f64_array *a, int64_t n, double fill)
{
    Py_ssize_t len = 0, i;
    double *p;
    a->list = NULL; a->len = 0; a->data = NULL; a->dirty = NULL;
    p = (double *)tp__local_alloc(n, sizeof(double), &len);
    if (p == NULL) {
        return -1;
    }
    for (i = 0; i < len; i++) {
        p[i] = fill;
    }
    a->data = p;
    a->len = len;
    return 0;
}

/* dst is overwritten (not freed); src may be a parameter array or a local one; dst is local */
static inline int tp_i64_array_copy(tp_i64_array *dst, const tp_i64_array *src)
{
    Py_ssize_t len = 0;
    int64_t *p = (int64_t *)tp__local_alloc((int64_t)src->len, sizeof(int64_t), &len);
    dst->list = NULL; dst->len = 0; dst->data = NULL; dst->dirty = NULL;
    if (p == NULL) {
        return -1;
    }
    if (len > 0) {
        memcpy(p, src->data, (size_t)len * sizeof(int64_t));
    }
    dst->data = p;
    dst->len = len;
    return 0;
}

static inline int tp_f64_array_copy(tp_f64_array *dst, const tp_f64_array *src)
{
    Py_ssize_t len = 0;
    double *p = (double *)tp__local_alloc((int64_t)src->len, sizeof(double), &len);
    dst->list = NULL; dst->len = 0; dst->data = NULL; dst->dirty = NULL;
    if (p == NULL) {
        return -1;
    }
    if (len > 0) {
        memcpy(p, src->data, (size_t)len * sizeof(double));
    }
    dst->data = p;
    dst->len = len;
    return 0;
}

static inline void tp_i64_array_free(tp_i64_array *a)
{
    free(a->data);
    free(a->dirty);
    a->list = NULL; a->len = 0; a->data = NULL; a->dirty = NULL;
}

static inline void tp_f64_array_free(tp_f64_array *a)
{
    free(a->data);
    free(a->dirty);
    a->list = NULL; a->len = 0; a->data = NULL; a->dirty = NULL;
}

/* a new tuple of the n items; the items are borrowed (each gets its own new reference) */
static inline PyObject *tp_tuple(PyObject *const *items, Py_ssize_t n)
{
    Py_ssize_t i;
    PyObject *t = PyTuple_New(n);
    if (t == NULL) {
        return NULL;
    }
    for (i = 0; i < n; i++) {
        Py_INCREF(items[i]);
        PyTuple_SET_ITEM(t, i, items[i]);
    }
    return t;
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

/* ------------------------------------------------------------------------------------------
 * Fixed-layout classes (SPEC N-11, ir.ClassDecl; API.md "Fixed-layout classes"). A compiled class
 * is a plain heap type whose instances keep every field in an object slot at a fixed offset; the
 * slots are reached through the PyMemberDef of the member descriptor captured at module init.
 * Nothing here runs user code: PyMember_GetOne/SetOne touch the slot only.
 *
 * A class can change after init (a property replaces a field, __init__ is replaced, the module
 * global is rebound, an instance's __class__ is assigned). Compiled code therefore re-proves its
 * preconditions at EVERY FieldGet / FieldSet / New:
 *   - the class is unchanged: cls->tp_version_tag equals the tag captured (or re-proved) for it.
 *     CPython clears the tag to 0 on every change of the type (PyType_Modified /
 *     type_modified_unlocked) and a later lookup assigns a NEW one from a per-interpreter counter
 *     that only grows and stops at 0 when exhausted (typeobject.c assign_version_tag), so an old
 *     tag never comes back and a tag of 0 never matches (the captured tag is never 0).
 *     (Py_TPFLAGS_VALID_VERSION_TAG is "Unused. Legacy flag" in 3.14's object.h and is never set:
 *     it cannot be part of the test.)
 *   - FieldGet / FieldSet: Py_IS_TYPE(obj, cls) (covers `obj.__class__ = Other`).
 *   - New: the module global still is the class (tp_class_global_ok).
 * When a check fails the generated code takes a slow path with CPython's own semantics (or, in a
 * pure function, deopts); see cgen.py "Fixed-layout classes".
 * ------------------------------------------------------------------------------------------ */

/* Per-class guard state, one per ClassDecl in the module state (zeroed = not compiled). */
typedef struct {
    unsigned int tag;       /* tp_version_tag at which the slots were proved; never 0 while compiled */
    unsigned int bad_tag;   /* a tag at which tp_class_refresh failed (0: none); not retried */
    uint64_t gepoch;        /* tp_globals_epoch at which module_dict[name] was last seen to be cls */
    PyObject *init;         /* strong: the class's own __init__ at capture, or NULL if it had none */
    Py_ssize_t slow;        /* per-access checks that failed (introspection only) */
    Py_ssize_t refreshed;   /* new tags adopted by tp_class_refresh (introspection only) */
} tp_class_rt;

/* Moves whenever a watched module dict changes a key that is (or may be) a compiled class name, is
 * cleared, cloned or deallocated (tp_globals_watch_event). Static: one counter per extension, shared
 * by every instance of the module in the process; an instance re-proves its own globals after any
 * move. The module declares no Py_mod_multiple_interpreters / Py_mod_gil slot, so CPython loads it
 * only into interpreters sharing the main GIL and keeps the GIL on in a free-threaded build: plain
 * reads and writes are serialised. */
static uint64_t tp_globals_epoch = 1;

/* `name` in cls's OWN dict (not through the MRO). 1 found (*out a new reference), 0 absent, -1 error. */
static inline int tp_own_attr(PyTypeObject *cls, const char *name, PyObject **out)
{
    PyObject *dict = PyType_GetDict(cls);
    int found;

    *out = NULL;
    if (dict == NULL) {
        return -1;
    }
    found = PyDict_GetItemStringRef(dict, name, out);
    Py_DECREF(dict);
    return found;
}

/* Look up `field` in cls's OWN dict (not through the MRO: a subclass or an instance attribute must
 * not be consulted) and capture its PyMemberDef. It must be a member descriptor made for exactly
 * this class (d_type == cls), of an object slot (Py_T_OBJECT_EX), writable, of that name, whose
 * offset lies inside the instance. 0 = captured (*out set, *descr a new reference to the descriptor),
 * 1 = not a plain slot (the class is not compiled), -1 = error. The PyMemberDef lives in
 * cls->tp_members: the caller must keep `cls` alive for as long as the pointer is used (the
 * generated module holds a strong reference). */
static inline int tp_slot_capture(PyTypeObject *cls, const char *field, PyMemberDef **out,
                                  PyObject **descr_out)
{
    PyObject *descr = NULL;
    int found, rc = 1;
    PyMemberDescrObject *md;
    PyMemberDef *m;

    *descr_out = NULL;
    found = tp_own_attr(cls, field, &descr);
    if (found < 0) {
        return -1;
    }
    if (found == 0) {
        return 1;
    }
    if (Py_IS_TYPE(descr, &PyMemberDescr_Type)) {
        md = (PyMemberDescrObject *)descr;
        m = md->d_member;
        if (md->d_common.d_type == cls && m != NULL && m->name != NULL
            && strcmp(m->name, field) == 0 && m->type == Py_T_OBJECT_EX
            && (m->flags & Py_READONLY) == 0 && m->offset >= (Py_ssize_t)sizeof(PyObject)
            && m->offset + (Py_ssize_t)sizeof(PyObject *) <= cls->tp_basicsize) {
            *out = m;
            *descr_out = descr;          /* the reference moves to the caller */
            return 0;
        }
    }
    Py_DECREF(descr);
    return rc;
}

/* The type-level conditions of ClassDecl: a heap type made by `type` (no metaclass), base `object`,
 * no instance __dict__, no variable size, object's own tp_new and tp_alloc (so tp_new_fixed is what
 * `C(...)` does for a trivial __init__), and the generic attribute protocol (no __getattribute__,
 * __getattr__, __setattr__ or __delattr__: those would run instead of, or after, the slot access). */
static inline int tp_class_shape_ok(PyTypeObject *cls)
{
    return (cls->tp_flags & Py_TPFLAGS_HEAPTYPE) && Py_TYPE(cls) == &PyType_Type
        && cls->tp_base == &PyBaseObject_Type && cls->tp_dictoffset == 0
        && cls->tp_itemsize == 0 && cls->tp_new == PyBaseObject_Type.tp_new
        && cls->tp_alloc == PyType_GenericAlloc && !(cls->tp_flags & Py_TPFLAGS_IS_ABSTRACT)
        && cls->tp_getattro == PyObject_GenericGetAttr
        && cls->tp_setattro == PyObject_GenericSetAttr;
}

static inline void tp_class_rt_clear(tp_class_rt *rt, PyObject **descrs, Py_ssize_t n)
{
    Py_ssize_t i;
    for (i = 0; i < n; i++) {
        Py_CLEAR(descrs[i]);
    }
    Py_CLEAR(rt->init);
    rt->tag = 0;
    rt->bad_tag = 0;
    rt->gepoch = 0;
}

/* At module init: the class object must be what ClassDecl promises (tp_class_shape_ok) and every
 * field a plain slot. slots[i] receives the PyMemberDef of fields[i], descrs[i] a strong reference
 * to its member descriptor, rt->init a strong reference to the class's own __init__ (or NULL), and
 * rt->tag the class's version tag (PyUnstable_Type_AssignVersionTag assigns one if it has none; a
 * class that cannot get a tag is not compiled). 1 = compiled, 0 = not (nothing kept), -1 = error
 * (nothing kept). */
static inline int tp_class_capture(PyObject *cls_obj, const char *const *fields, Py_ssize_t n,
                                   PyMemberDef **slots, PyObject **descrs, tp_class_rt *rt)
{
    PyTypeObject *cls;
    Py_ssize_t i;
    int rc;

    if (!PyType_Check(cls_obj)) {
        return 0;
    }
    cls = (PyTypeObject *)cls_obj;
    if (!tp_class_shape_ok(cls)) {
        return 0;
    }
    for (i = 0; i < n; i++) {
        rc = tp_slot_capture(cls, fields[i], &slots[i], &descrs[i]);
        if (rc != 0) {
            tp_class_rt_clear(rt, descrs, i);
            return rc < 0 ? -1 : 0;
        }
    }
    if (tp_own_attr(cls, "__init__", &rt->init) < 0) {
        tp_class_rt_clear(rt, descrs, n);
        return -1;
    }
    if (!PyUnstable_Type_AssignVersionTag(cls) || cls->tp_version_tag == 0) {
        tp_class_rt_clear(rt, descrs, n);
        return 0;
    }
    rt->tag = cls->tp_version_tag;
    rt->bad_tag = 0;
    rt->gepoch = 0;
    rt->slow = 0;
    rt->refreshed = 0;
    return 1;
}

/* The class's tag moved (or is 0). Re-prove what the captured slots rely on, without running user
 * code: get a tag (none left: 0), the shape still holds, every field's own-dict entry is the very
 * member descriptor object captured at init, and __init__ is the very object captured at init. Then
 * adopt the new tag (1). Otherwise remember the tag as bad (0), so the next access with that tag
 * does not search again. -1 = error (a failed dict lookup). */
static inline int tp_class_refresh(PyTypeObject *cls, tp_class_rt *rt, const char *const *fields,
                                   Py_ssize_t n, PyObject *const *descrs)
{
    unsigned int tag;
    Py_ssize_t i;
    PyObject *d;
    int found;

    if (!PyUnstable_Type_AssignVersionTag(cls) || cls->tp_version_tag == 0) {
        return 0;                                /* tags exhausted for this class */
    }
    tag = cls->tp_version_tag;
    if (tag == rt->tag) {
        return 1;
    }
    if (tag == rt->bad_tag) {
        return 0;
    }
    if (!tp_class_shape_ok(cls)) {
        goto bad;
    }
    for (i = 0; i < n; i++) {
        found = tp_own_attr(cls, fields[i], &d);
        if (found < 0) {
            return -1;
        }
        Py_XDECREF(d);                           /* identity only; descrs[i] keeps it alive */
        if (d != descrs[i]) {
            goto bad;
        }
    }
    found = tp_own_attr(cls, "__init__", &d);
    if (found < 0) {
        return -1;
    }
    Py_XDECREF(d);
    if (d != rt->init) {
        goto bad;
    }
    rt->tag = tag;
    rt->refreshed++;
    return 1;
bad:
    rt->bad_tag = tag;
    return 0;
}

/* cls's current tp_version_tag, 0 for NULL (introspection: __typedpython_class_info__). */
static inline unsigned int tp_class_tag(PyObject *cls)
{
    return cls != NULL ? ((PyTypeObject *)cls)->tp_version_tag : 0u;
}

/* The per-access check of FieldGet / FieldSet (obj != NULL) and New (obj == NULL): 1 = the captured
 * slots are what `obj.f` / `C(...)` reach now (obj is exactly cls, and cls is unchanged since the
 * slots were proved, or re-proved by tp_class_refresh), 0 = take the slow path, -1 = error. The
 * fast case is one type compare and one tag compare. */
static inline int tp_class_current(PyObject *obj, PyObject *cls_obj, tp_class_rt *rt,
                                   const char *const *fields, Py_ssize_t n, PyObject *const *descrs)
{
    PyTypeObject *cls = (PyTypeObject *)cls_obj;
    if (cls == NULL || (obj != NULL && !Py_IS_TYPE(obj, cls))) {
        return 0;
    }
    if (cls->tp_version_tag == rt->tag) {        /* rt->tag != 0 while cls != NULL */
        return 1;
    }
    return tp_class_refresh(cls, rt, fields, n, descrs);
}

/* LOAD_GLOBAL of a class name, as an identity test: 1 = module_dict[name] is cls, 0 = it is not (or
 * the name is not a module global: the slow path then asks tp_global), -1 = error. With a dict
 * watcher on module_dict (`watched`), a 1 is remembered in rt->gepoch until tp_globals_epoch moves,
 * so the steady state is one compare; without one (no watcher id was free) every call looks up. */
static inline int tp_class_global_ok(PyObject *module_dict, PyObject *name, PyObject *cls,
                                     tp_class_rt *rt, int watched)
{
    PyObject *v;
    if (cls == NULL) {
        return 0;
    }
    if (watched && rt->gepoch == tp_globals_epoch) {
        return 1;
    }
    v = PyDict_GetItemWithError(module_dict, name);   /* borrowed */
    if (v == NULL) {
        return PyErr_Occurred() ? -1 : 0;
    }
    if (v != cls) {
        return 0;
    }
    if (watched) {
        rt->gepoch = tp_globals_epoch;
    }
    return 1;
}

/* The body of a module's PyDict_WatchCallback: move tp_globals_epoch when `key` is one of the
 * NULL-terminated class `names` (an exact str compared by content), may equal one (any other key
 * type, a str subclass), or is NULL (cleared / cloned / deallocated). Never raises; returns 0. */
static inline int tp_globals_watch_event(PyDict_WatchEvent event, PyObject *key,
                                         const char *const *names)
{
    Py_ssize_t i;
    (void)event;
    if (key != NULL && PyUnicode_CheckExact(key)) {
#if PY_VERSION_HEX >= 0x030D0000
        for (i = 0; names[i] != NULL; i++) {
            if (PyUnicode_EqualToUTF8(key, names[i])) {
                tp_globals_epoch++;
                return 0;
            }
        }
        return 0;
#else
        (void)i;
        (void)names;
#endif
    }
    tp_globals_epoch++;
    return 0;
}

/* Watch module_dict with `cb` (a function calling tp_globals_watch_event). *id1 = watcher id + 1, or
 * 0 when no dict-watcher id is free (8 per interpreter, 2 reserved by CPython, shared with every
 * other extension) or PyDict_Watch failed: the caller then takes the conservative path (a dict
 * lookup per New). Never leaves an error set. */
static inline void tp_globals_watch(PyObject *module_dict, PyDict_WatchCallback cb, int *id1)
{
    int id;
    *id1 = 0;
    id = PyDict_AddWatcher(cb);
    if (id < 0) {
        PyErr_Clear();
        return;
    }
    if (PyDict_Watch(id, module_dict) < 0) {
        PyErr_Clear();
        if (PyDict_ClearWatcher(id) < 0) {
            PyErr_Clear();
        }
        return;
    }
    *id1 = id + 1;
}

/* Undo tp_globals_watch (module clear / free): unwatch module_dict (if still there) and release the
 * watcher id. Idempotent; keeps any exception that was already set. */
static inline void tp_globals_unwatch(PyObject *module_dict, int *id1)
{
    PyObject *exc;
    if (*id1 <= 0) {
        return;
    }
    exc = PyErr_GetRaisedException();
    if (module_dict != NULL && PyDict_Unwatch(*id1 - 1, module_dict) < 0) {
        PyErr_Clear();
    }
    if (PyDict_ClearWatcher(*id1 - 1) < 0) {
        PyErr_Clear();
    }
    *id1 = 0;
    PyErr_SetRaisedException(exc);
}

/* obj.field of a proved-exact instance: a new reference, or CPython's AttributeError for an unset
 * slot ("'C' object has no attribute 'f'"). */
static inline PyObject *tp_field_get(PyObject *obj, PyMemberDef *m)
{
    return PyMember_GetOne((const char *)obj, m);
}

/* obj.field = v: the slot takes its own reference to v and releases the old one. 0 or -1. */
static inline int tp_field_set(PyObject *obj, PyMemberDef *m, PyObject *v)
{
    return PyMember_SetOne((char *)obj, m, v);
}

/* The slow path of FieldSet: STORE_ATTR (PyObject_SetAttr). 0 or -1. */
static inline int tp_setattr(PyObject *obj, PyObject *name, PyObject *v)
{
    return PyObject_SetAttr(obj, name, v);
}

/* The observable result of a trivial __init__: allocate with cls's tp_alloc and set slots[i] to
 * values[i] (new references; the caller keeps its own). NULL with the error set on failure; the
 * half-built object is released (its unset slots are NULL, which dealloc handles). */
static inline PyObject *tp_new_fixed(PyTypeObject *cls, PyMemberDef *const *slots,
                                     PyObject *const *values, Py_ssize_t n)
{
    Py_ssize_t i;
    PyObject *obj = cls->tp_alloc(cls, 0);

    if (obj == NULL) {
        return NULL;
    }
    for (i = 0; i < n; i++) {
        if (PyMember_SetOne((char *)obj, slots[i], values[i]) < 0) {
            Py_DECREF(obj);
            return NULL;
        }
    }
    return obj;
}

/* type(obj) is cls */
static inline int tp_is_exact(PyObject *obj, PyTypeObject *cls)
{
    return Py_IS_TYPE(obj, cls);
}

/* ------------------------------------------------------------------------------------------
 * Call depth (issue #57): compiled frames have no Python frame, so a recursion made of them would
 * neither raise RecursionError nor stop before the C stack ends. tp_enter_call / tp_leave_call
 * bracket every compiled impl function. See API.md "Call depth".
 * ------------------------------------------------------------------------------------------ */
#if defined(_MSC_VER)
#define TP_THREAD_LOCAL __declspec(thread)
#else
#define TP_THREAD_LOCAL __thread
#endif

typedef struct {
    Py_ssize_t count;       /* compiled frames in flight on this thread (this module) */
    PyObject *frame;        /* strong ref: the top Python frame when `pydepth` was measured */
    Py_ssize_t pydepth;     /* Python frames below and including `frame` */
} tp_depth_state;

static TP_THREAD_LOCAL tp_depth_state tp_depth = {0, NULL, 0};

/* Python frame depth of the running thread: public API only. The walk is O(depth), so it is
 * cached by the identity of the top frame (kept alive while compiled frames are in flight). */
static inline Py_ssize_t tp_python_depth(void)
{
    PyFrameObject *top = PyThreadState_GetFrame(PyThreadState_Get());    /* new ref or NULL */
    if (top == NULL) {
        return 0;
    }
    if (tp_depth.frame == (PyObject *)top) {
        Py_DECREF(top);
        return tp_depth.pydepth;
    }
    Py_ssize_t depth = 0;
    PyFrameObject *cur = top;
    Py_INCREF(cur);
    while (cur != NULL) {
        depth++;
        PyFrameObject *back = PyFrame_GetBack(cur);
        Py_DECREF(cur);
        cur = back;
    }
    PyObject *old = tp_depth.frame;
    tp_depth.frame = (PyObject *)top;       /* takes the reference from GetFrame */
    tp_depth.pydepth = depth;
    Py_XDECREF(old);
    return depth;
}

static inline int tp_enter_call(void)
{
    if (tp_python_depth() + tp_depth.count + 1 > Py_GetRecursionLimit()) {
        PyErr_SetString(PyExc_RecursionError, "maximum recursion depth exceeded");
        return -1;
    }
    if (Py_EnterRecursiveCall(" in compiled code")) {
        return -1;
    }
    tp_depth.count++;
    return 0;
}

static inline void tp_leave_call(void)
{
    Py_LeaveRecursiveCall();
    if (--tp_depth.count == 0) {
        Py_CLEAR(tp_depth.frame);
    }
}

/* Fast entry (issue #147). A *bounded* compiled function (cgen: no call cycle, bounded callees, no
 * node that can run Python code) has a call tree of at most k compiled frames, k known at build time.
 * `tp_depth_room(k)` is true exactly when tp_enter_call would accept all k nested frames, the same
 * comparison with the deepest frame's count (depth + count + k <= limit); then the whole tree runs
 * through its uncounted twins (`tp_fimpl_*`: no tp_enter_call, no tp_leave_call), which the C
 * compiler can inline. When it is false the caller takes the counted path, so RecursionError is raised
 * at exactly the call where it is raised without the fast entry, never earlier.
 *
 * Skipping Py_EnterRecursiveCall on the fast path is safe because the tree is bounded: at most k
 * small compiled frames (no recursion, no Python callbacks except the eval-breaker poll, which is
 * the interpreter's own call), so they use a bounded amount of C stack on top of the entry's.
 *
 * Python frames created by user code under a fast tree cannot exist (a bounded function runs no
 * Python code) except through the eval-breaker poll (signal handlers, finalizers): those frames
 * see a compiled count that does not include the fast tree's frames (at most k). */
static inline int tp_depth_room(Py_ssize_t k)
{
    return tp_python_depth() + tp_depth.count + k <= Py_GetRecursionLimit();
}

/* After a fast tree: tp_python_depth may have cached the top frame (a strong reference) and, with
 * no counted frame in flight, nothing else would drop it, as tp_leave_call does at count 0. */
static inline void tp_depth_done(void)
{
    if (tp_depth.count == 0) {
        Py_CLEAR(tp_depth.frame);
    }
}

#ifdef TP_TRACE_FAST
/* Test builds only (-DTP_TRACE_FAST): how many entries took the fast path. */
static Py_ssize_t tp_trace_fast_hits_count = 0;
static inline void tp_trace_fast_hit(void) { tp_trace_fast_hits_count++; }
static PyObject *tp_trace_fast_hits(PyObject *tp_module, PyObject *tp_unused)
{
    (void)tp_module; (void)tp_unused;
    return PyLong_FromSsize_t(tp_trace_fast_hits_count);
}
#else
static inline void tp_trace_fast_hit(void) {}
#endif

/* ------------------------------------------------------------------------------------------
 * Eval breaker (issue #141). The interpreter checks between bytecodes for signals, pending calls,
 * GIL drop requests, async exceptions and scheduled collections; compiled code has no bytecodes.
 * So at the top of every loop iteration and at every impl entry `tp_poll` counts down a module
 * counter (one decrement under the GIL), and every TP_POLL_INTERVAL polls `tp_poll_slow` calls a
 * Python no-op (`lambda: None`, made by the module's exec slot). Its RESUME runs CPython's own
 * eval-breaker handling, so everything the interpreter would do there happens, the same way
 * (forced GIL switching to a waiting thread, KeyboardInterrupt, gc). Public API only.
 *
 * Signal handlers, pending calls and other threads are user code that may change lists and
 * globals. A function holding an array copy-in or an entry-globals snapshot runs no user code by
 * contract (ir.ArrayParam, ir.Function.entry_globals), so such a function does not poll, and while
 * one is active (`tp_snapshot_depth > 0`, counted at its entry and exit) the slow path does nothing:
 * the event stays pending and is handled at the next poll or bytecode after it returns, which is the
 * CPython execution in which it arrived late.
 * ------------------------------------------------------------------------------------------ */
#ifndef TP_POLL_INTERVAL
#define TP_POLL_INTERVAL 4096
#endif

static int tp_poll_countdown = TP_POLL_INTERVAL;
static Py_ssize_t tp_snapshot_depth = 0;

static inline int tp_poll_slow(PyObject *breaker)
{
    PyObject *r;
    tp_poll_countdown = TP_POLL_INTERVAL;
    if (tp_snapshot_depth > 0 || breaker == NULL) {
        return 0;
    }
    r = PyObject_CallNoArgs(breaker);
    if (r == NULL) {
        return -1;
    }
    Py_DECREF(r);
    return 0;
}

/* 0 = continue, -1 = exception set (e.g. KeyboardInterrupt from a signal handler). */
static inline int tp_poll(PyObject *breaker)
{
    if (--tp_poll_countdown > 0) {
        return 0;
    }
    return tp_poll_slow(breaker);
}

#endif /* TP_RUNTIME_H */
