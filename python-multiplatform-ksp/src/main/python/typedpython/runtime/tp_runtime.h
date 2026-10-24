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
#include <fenv.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>

#if PY_VERSION_HEX < 0x030C0000
#error "tp_runtime.h needs CPython 3.12 or newer (PyErr_GetRaisedException)"
#endif

/* ------------------------------------------------------------------------------------------
 * Free-threaded CPython (3.15t is the only supported build; issue #159, runtime/API.md
 * "Free-threaded CPython"). Without a GIL every static, every module-state field and every
 * object another thread can reach is shared. Each piece of shared mutable state is one of:
 *   thread-local   TP_THREAD_LOCAL (tp_depth, tp_poll_countdown, tp_snapshot_depth)
 *   atomic         tp_atomic_* below (tp_globals_epoch, tp_class_rt tag / bad_tag / gepoch /
 *                  counters, tp_version_tag reads)
 *   immutable      written only by the module's exec slot (or by tp_clear, when no call can run)
 *   locked         a critical section on the object (array parameters, the module dict, the
 *                  deopt counter)
 * and a borrowed reference is never held across anything another thread could free: containers
 * are read through the strong-reference API (PyDict_GetItemRef ...) or under a critical section.
 * ------------------------------------------------------------------------------------------ */
#if defined(__GNUC__) || defined(__clang__)
static inline unsigned int tp_atomic_load_u32(const unsigned int *p)
{ return __atomic_load_n(p, __ATOMIC_RELAXED); }
static inline void tp_atomic_store_u32(unsigned int *p, unsigned int v)
{ __atomic_store_n(p, v, __ATOMIC_RELAXED); }
static inline uint64_t tp_atomic_load_u64(const uint64_t *p)
{ return __atomic_load_n(p, __ATOMIC_RELAXED); }
static inline void tp_atomic_store_u64(uint64_t *p, uint64_t v)
{ __atomic_store_n(p, v, __ATOMIC_RELAXED); }
static inline void tp_atomic_add_u64(uint64_t *p, uint64_t v)
{ (void)__atomic_fetch_add(p, v, __ATOMIC_ACQ_REL); }
static inline Py_ssize_t tp_atomic_load_ssize(const Py_ssize_t *p)
{ return __atomic_load_n(p, __ATOMIC_RELAXED); }
static inline void tp_atomic_add_ssize(Py_ssize_t *p, Py_ssize_t v)
{ (void)__atomic_fetch_add(p, v, __ATOMIC_RELAXED); }
#else
#error "tp_runtime.h needs the GCC/Clang __atomic builtins (the build uses cc, see cbuild.py)"
#endif

/* cls->tp_version_tag: CPython clears and reassigns it from any thread (PyType_Modified, under the
 * type's lock); every read here is an atomic load, as CPython's own (FT_ATOMIC_LOAD_UINT_RELAXED). */
static inline unsigned int tp_type_tag(const PyTypeObject *cls)
{
    return tp_atomic_load_u32(&cls->tp_version_tag);
}

#if defined(_MSC_VER)
#define TP_THREAD_LOCAL __declspec(thread)
#else
#define TP_THREAD_LOCAL __thread
#endif

/* Critical sections for array parameters (tp_cs_*): from before the copy-in to after the
 * write-back, on every exit path. One list or two (CPython offers one- and two-object sections
 * only; a function with more list parameters stays interpreted). With a GIL they are no-ops. */
typedef struct {
    int n;                       /* 0 = not held, 1 or 2 objects locked */
#ifdef Py_GIL_DISABLED
    PyCriticalSection2 cs;
#endif
} tp_cs;

static inline void tp_cs_begin(tp_cs *c, PyObject *a, PyObject *b)
{
#ifdef Py_GIL_DISABLED
    if (b == NULL) {
        PyCriticalSection_Begin((PyCriticalSection *)&c->cs, a);
        c->n = 1;
    }
    else {
        PyCriticalSection2_Begin(&c->cs, a, b);
        c->n = 2;
    }
#else
    (void)a;
    c->n = b == NULL ? 1 : 2;
#endif
}

/* idempotent: a no-op when nothing is held */
static inline void tp_cs_end(tp_cs *c)
{
#ifdef Py_GIL_DISABLED
    if (c->n == 1) {
        PyCriticalSection_End((PyCriticalSection *)&c->cs);
    }
    else if (c->n == 2) {
        PyCriticalSection2_End(&c->cs);
    }
#endif
    c->n = 0;
}

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

/* ir.BinOpKind (names, not positions), for tp_binop_obj */
#define TP_BINOP_ADD 0
#define TP_BINOP_SUB 1
#define TP_BINOP_MUL 2
#define TP_BINOP_TRUEDIV 3
#define TP_BINOP_FLOORDIV 4
#define TP_BINOP_MOD 5
/* the augmented forms `a op= b` (PyNumber_InPlace*): ir.BinOp.inplace */
#define TP_BINOP_IADD 6
#define TP_BINOP_ISUB 7
#define TP_BINOP_IMUL 8
#define TP_BINOP_ITRUEDIV 9
#define TP_BINOP_IFLOORDIV 10
#define TP_BINOP_IMOD 11
#define TP_BINOP_LSHIFT 12
#define TP_BINOP_RSHIFT 13
#define TP_BINOP_ILSHIFT 14
#define TP_BINOP_IRSHIFT 15

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

/* Proven i64 operations (ir.BinOp.proven / UnaryOp.proven): the verifier showed the result fits in
 * i64, so the generated code does the arithmetic with no check (unsigned wrap-around, which is plain
 * machine arithmetic and has no undefined behaviour). Built with -DTP_CHECK_PROVEN, each of them is
 * the checked helper again and a wrong proof is a SystemError, never a deopt: that is for the tests
 * and for debugging a verifier change. The macros use the generated function's `tp_s`, `tp_rc` and
 * `tp_exit`. */
static inline int64_t tp_padd_i64(int64_t a, int64_t b) { return (int64_t)((uint64_t)a + (uint64_t)b); }
static inline int64_t tp_psub_i64(int64_t a, int64_t b) { return (int64_t)((uint64_t)a - (uint64_t)b); }
static inline int64_t tp_pmul_i64(int64_t a, int64_t b) { return (int64_t)((uint64_t)a * (uint64_t)b); }
static inline int64_t tp_pneg_i64(int64_t a) { return (int64_t)(0 - (uint64_t)a); }
#ifdef TP_CHECK_PROVEN
#define tp_proven_fail()                                                                          \
    do {                                                                                          \
        PyErr_SetString(PyExc_SystemError, "typedpython: proven operation overflowed");           \
        tp_rc = -1;                                                                               \
        goto tp_exit;                                                                             \
    } while (0)
#define tp_proven(op, a, b, t)                                                                    \
    do {                                                                                          \
        tp_s = tp_##op##_i64((a), (b), &(t));                                                     \
        if (tp_s != 0) tp_proven_fail();                                                          \
    } while (0)
#define tp_proven_neg(a, t)                                                                       \
    do {                                                                                          \
        tp_s = tp_neg_i64((a), &(t));                                                             \
        if (tp_s != 0) tp_proven_fail();                                                          \
    } while (0)
#else
#define tp_proven(op, a, b, t) ((t) = tp_p##op##_i64((a), (b)))
#define tp_proven_neg(a, t) ((t) = tp_pneg_i64(a))
#endif

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
 * numpy scalar arithmetic (#220, step 2). An operation on a value derived from a numpy element is
 * numpy's, not CPython's: no ZeroDivisionError, inf / nan results, int64 division by zero, and a
 * RuntimeWarning per floating-point exception. tp_np_f64 returns the exceptions that numpy reports
 * (TP_NP_* bits). A pure function deopts on any bit (the interpreter then warns); an impure one calls
 * tp_np_warn. The algorithms are numpy's (npymath: npy_divmod / npy_floor_divide / npy_remainder).
 * ------------------------------------------------------------------------------------------ */

#define TP_NP_DIVZERO 1
#define TP_NP_OVERFLOW 2
#define TP_NP_INVALID 4
enum { TP_NP_ADD, TP_NP_SUB, TP_NP_MUL, TP_NP_DIV, TP_NP_FLOORDIV, TP_NP_MOD };

#if defined(__GNUC__) || defined(__clang__)
#define TP_NOINLINE __attribute__((noinline, unused))
#else
#define TP_NOINLINE
#endif

static inline double tp_np_divmod(double a, double b, double *modulus)
{
    double div, mod, floordiv;
    mod = fmod(a, b);
    if (!b) {
        *modulus = mod;
        return a / b;
    }
    div = (a - mod) / b;
    if (mod) {
        if ((b < 0) != (mod < 0)) {
            mod += b;
            div -= 1.0;
        }
    }
    else {
        mod = copysign(0.0, b);
    }
    if (div) {
        floordiv = floor(div);
        if (div - floordiv > 0.5) {
            floordiv += 1.0;
        }
    }
    else {
        floordiv = copysign(0.0, a / b);
    }
    *modulus = mod;
    return floordiv;
}

/* The volatile operands and result keep the compiler from moving, folding or sharing the operation
 * across the flag clear and test (the function is also noinline). */
static TP_NOINLINE int tp_np_f64(int op, double a, double b, double *out)
{
    volatile double va = a, vb = b, vr = 0.0, vd = 0.0;
    double mod;
    int flags, force = 0, clear = 0;
    feclearexcept(FE_ALL_EXCEPT);
    switch (op) {
    case TP_NP_ADD: vr = va + vb; break;
    case TP_NP_SUB: vr = va - vb; break;
    case TP_NP_MUL: vr = va * vb; break;
    case TP_NP_DIV: vr = va / vb; break;
    case TP_NP_FLOORDIV:
        if (!vb) {
            feclearexcept(FE_ALL_EXCEPT);
            vr = va / vb;
            if (!va) {
                force = TP_NP_INVALID;
            }
        }
        else {
            vr = tp_np_divmod(va, vb, &mod);
        }
        break;
    default:
        if (!vb) {
            vr = fmod(va, vb);
            clear = 1;
        }
        else {
            vd = tp_np_divmod(va, vb, &mod);     /* numpy computes the quotient too: its flags count */
            (void)vd;
            vr = mod;
        }
        break;
    }
    flags = 0;
    if (!clear) {
        int f = fetestexcept(FE_DIVBYZERO | FE_OVERFLOW | FE_INVALID);
        flags = ((f & FE_DIVBYZERO) ? TP_NP_DIVZERO : 0) | ((f & FE_OVERFLOW) ? TP_NP_OVERFLOW : 0)
                | ((f & FE_INVALID) ? TP_NP_INVALID : 0);
    }
    *out = vr;
    return flags | force;
}

/* numpy's RuntimeWarning for each bit of `flags`, in numpy's order, attributed to `file`:`line` (the
 * operation's own source position, as the interpreter's frame would give). 0, or -1 with the
 * exception set (a warning turned into an error). `op` is numpy's scalar name ("add", "divide"...). */
static TP_NOINLINE int tp_np_warn(PyObject *tp_module, int flags, const char *op, PyObject *file, int line)
{
    PyObject *dict = PyModule_GetDict(tp_module);          /* borrowed */
    PyObject *reg = NULL, *modname = NULL;
    static const struct { int bit; const char *text; } kinds[3] = {
        {TP_NP_DIVZERO, "divide by zero"}, {TP_NP_OVERFLOW, "overflow"}, {TP_NP_INVALID, "invalid value"}};
    int k, rc = 0;
    if (PyDict_GetItemStringRef(dict, "__warningregistry__", &reg) < 0) {
        return -1;
    }
    if (reg == NULL) {
        PyObject *fresh = PyDict_New(), *key = PyUnicode_FromString("__warningregistry__"), *got = NULL;
        int rr = -1;
        if (fresh != NULL && key != NULL) {
            rr = PyDict_SetDefaultRef(dict, key, fresh, &got);
        }
        Py_XDECREF(fresh);
        Py_XDECREF(key);
        if (rr < 0) {
            return -1;
        }
        reg = got;
    }
    if (PyDict_GetItemStringRef(dict, "__name__", &modname) < 0) {
        Py_DECREF(reg);
        return -1;
    }
    for (k = 0; k < 3 && rc == 0; k++) {
        if (flags & kinds[k].bit) {
            PyObject *msg = PyUnicode_FromFormat("%s encountered in scalar %s", kinds[k].text, op);
            if (msg == NULL) {
                rc = -1;
                break;
            }
            rc = PyErr_WarnExplicitObject(PyExc_RuntimeWarning, msg, file, line, modname, reg);
            Py_DECREF(msg);
        }
    }
    Py_XDECREF(modname);
    Py_DECREF(reg);
    return rc;
}

/* int64 on the numpy path (pure functions): 0 ok, 1 deopt (the interpreter warns). */
static inline int tp_np_truediv_i64(int64_t a, int64_t b, double *out)
{
    if (b == 0) {
        return 1;
    }
    *out = (double)a / (double)b;          /* numpy converts both to double; CPython rounds exactly */
    return 0;
}

static inline int tp_np_floordiv_i64(int64_t a, int64_t b, int64_t *out)
{
    if (b == 0) {
        return 1;
    }
    return tp_floordiv_i64(a, b, out);     /* INT64_MIN // -1 is 1 (numpy's overflow warning) */
}

static inline int tp_np_mod_i64(int64_t a, int64_t b, int64_t *out)
{
    if (b == 0) {
        return 1;
    }
    return tp_mod_i64(a, b, out);
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
/* The numpy scalar of type `t` (np.float64 / np.int64 / np.bool_) holding the Python value `o` (a new
 * reference, consumed); NULL with an exception set. Box.np (#220). */
static inline PyObject *tp_box_np(PyObject *t, PyObject *o)
{
    PyObject *r;
    if (o == NULL) {
        return NULL;
    }
    if (t == NULL) {
        Py_DECREF(o);
        PyErr_SetString(PyExc_SystemError, "typedpython: numpy scalar type is unavailable");
        return NULL;
    }
    r = PyObject_CallOneArg(t, o);
    Py_DECREF(o);
    return r;
}

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
 *
 * Free-threaded (#159): the CALLER holds the list's critical section (tp_cs_begin) from before
 * enter to after exit, on every exit path. enter and exit read and write the list's items through
 * borrowed references (PyList_GET_ITEM) and rely on that lock: no other thread can change the list
 * between the copy-in and the write-back, so the whole call is one atomic step as far as lists are
 * concerned (CPython can produce that interleaving: every list operation takes the same lock).
 * Liveness note (issue #152): a function holding an array does not poll the eval breaker, so a long
 * compiled array loop delays other threads' stop-the-world requests (the free-threaded GC) until it
 * returns; results are unaffected. Polls inside such functions need the re-validation decided in
 * #152 (after a poll the critical section is re-taken and the list may have changed).
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
 * Buffer arrays (issue #149; ir.ArrayParam.buffer): an exact array.array argument
 * is NOT copied. tp_buf_enter takes one Py_buffer export and tp_buf_exit releases it; the wrapper
 * calls exit on every path (ok, error, deopt), so the export, which forbids a resize of the
 * array.array, is held exactly as long as the compiled call.
 *
 * enter(obj, exact, fmt1, fmt2, writable, b): rc 0 = held (b->data, b->len set); rc 1 = deopt, b
 * is zeroed and nothing is held, nothing changed, no exception left; rc -1 = MemoryError set.
 * Deopt (never memory access) unless ALL hold: `exact` is non-NULL and Py_IS_TYPE(obj, exact)
 * (a subclass could override __getitem__), PyObject_GetBuffer succeeds with C_CONTIGUOUS | FORMAT
 * (| WRITABLE when the function stores), ndim == 1, itemsize == 8, no suboffsets, shape[0] * 8 ==
 * view.len, the format is exactly fmt1 or fmt2 ("d"; "q" or "l": the 8-byte signed ints, a byte
 * order prefix or an unsigned/other format fails), and the data is 8-byte aligned (the access is
 * an 8-byte atomic). An empty buffer is held too (len 0, no element is ever touched).
 *
 * Element access (tp_buf_ld_* / tp_buf_st_*): one relaxed atomic 8-byte load or store of the bit
 * pattern on the buffer memory, bit-cast with memcpy. Another thread may write the same element concurrently: that is a data race in the program, and
 * the atomic makes it a torn-value-free one (an element is read or written whole), the closest a
 * compiled loop gets to what the interpreter's own element read/write does. -DTP_BUFFER_PLAIN
 * replaces the atomics with plain loads and stores; it exists only to measure what the atomics
 * cost, it is not a supported configuration for threads. len is the export's shape[0], fixed for
 * the call (the export forbids resizing).
 * ------------------------------------------------------------------------------------------ */
typedef struct { Py_buffer view; Py_ssize_t len; void *data; int held; int np; } tp_buf;

/* `mod.attr` as a new reference in *out; not importable (or no such attribute): *out stays NULL
 * and the error is cleared, so every guard against it deopts. -1 only for MemoryError. */
static inline int tp_import_attr(const char *mod, const char *attr, PyObject **out)
{
    PyObject *m = PyImport_ImportModule(mod);
    *out = NULL;
    if (m != NULL) {
        *out = PyObject_GetAttrString(m, attr);
        Py_DECREF(m);
    }
    if (*out == NULL) {
        if (PyErr_ExceptionMatches(PyExc_MemoryError)) {
            return -1;
        }
        PyErr_Clear();
    }
    return 0;
}

static inline void tp_buf_exit(tp_buf *b)
{
    if (b->held) {
        PyBuffer_Release(&b->view);
    }
    memset(b, 0, sizeof(*b));
}

static inline int tp__buf_enter(PyObject *obj, PyObject *exact, const char *fmt1, const char *fmt2,
                                int writable, Py_ssize_t isz, tp_buf *b)
{
    int flags = PyBUF_C_CONTIGUOUS | PyBUF_FORMAT;
    memset(b, 0, sizeof(*b));
    if (exact == NULL || !Py_IS_TYPE(obj, (PyTypeObject *)exact)) {
        return 1;
    }
    if (writable) {
        flags |= PyBUF_WRITABLE;
    }
    if (PyObject_GetBuffer(obj, &b->view, flags) != 0) {
        if (PyErr_ExceptionMatches(PyExc_MemoryError)) {
            return -1;
        }
        PyErr_Clear();                       /* read-only, not contiguous, ...: the object path */
        memset(b, 0, sizeof(*b));
        return 1;
    }
    b->held = 1;
    if (b->view.ndim != 1 || b->view.itemsize != isz || b->view.shape == NULL
            || b->view.suboffsets != NULL || b->view.shape[0] < 0
            || b->view.shape[0] * isz != b->view.len || b->view.format == NULL
            || !(strcmp(b->view.format, fmt1) == 0 || (fmt2 != NULL && strcmp(b->view.format, fmt2) == 0))
            || (writable && b->view.readonly)
            || (b->view.shape[0] > 0 && (b->view.buf == NULL || ((uintptr_t)b->view.buf & (uintptr_t)(isz - 1)) != 0))) {
        tp_buf_exit(b);
        return 1;
    }
    b->len = b->view.shape[0];
    b->data = b->view.buf;
    return 0;
}

/* The item size follows the format: "?" (numpy bool_, #261) has 1-byte items, every other format 8. */
static inline int tp_buf_enter(PyObject *obj, PyObject *exact, const char *fmt1, const char *fmt2,
                               int writable, tp_buf *b)
{
    return tp__buf_enter(obj, exact, fmt1, fmt2, writable, strcmp(fmt1, "?") == 0 ? 1 : 8, b);
}

/* A numpy array (#220): the same guard on the exact `numpy.ndarray`; `np` selects numpy's IndexError
 * text. The format check already refuses a byte-order prefix and every dtype but float64 / int64. */
static inline int tp_buf_enter_np(PyObject *obj, PyObject *exact, const char *fmt1, const char *fmt2,
                                  int writable, tp_buf *b)
{
    int rc = tp_buf_enter(obj, exact, fmt1, fmt2, writable, b);
    if (rc == 0) {
        b->np = 1;
    }
    return rc;
}

/* index: a negative index counts from the end; out of range -> IndexError with the text CPython's
 * own array.array uses ("array index out of range" / "array assignment index out of range") */
static inline int tp_buf_slot(const tp_buf *b, int64_t i, int store, Py_ssize_t *slot)
{
    int64_t orig = i;
    if (i < 0) {
        i += (int64_t)b->len;
        if (i < 0) goto out_of_range;
    }
    else if (i >= (int64_t)b->len) {
        goto out_of_range;
    }
    *slot = (Py_ssize_t)i;
    return 0;
out_of_range:
    if (b->np) {
        PyErr_Format(PyExc_IndexError, "index %lld is out of bounds for axis 0 with size %zd",
                     (long long)orig, b->len);
        return -1;
    }
    PyErr_SetString(PyExc_IndexError,
                    store ? "array assignment index out of range" : "array index out of range");
    return -1;
}

#ifdef TP_BUFFER_PLAIN
static inline double tp_buf_ld_f64(const tp_buf *b, Py_ssize_t i) { return ((const double *)b->data)[i]; }
static inline int64_t tp_buf_ld_i64(const tp_buf *b, Py_ssize_t i) { return ((const int64_t *)b->data)[i]; }
static inline void tp_buf_st_f64(const tp_buf *b, Py_ssize_t i, double v) { ((double *)b->data)[i] = v; }
static inline void tp_buf_st_i64(const tp_buf *b, Py_ssize_t i, int64_t v) { ((int64_t *)b->data)[i] = v; }
static inline int tp_buf_ld_bool(const tp_buf *b, Py_ssize_t i) { return ((const uint8_t *)b->data)[i] != 0; }
static inline void tp_buf_st_bool(const tp_buf *b, Py_ssize_t i, int v) { ((uint8_t *)b->data)[i] = v != 0; }
#else
static inline int tp_buf_ld_bool(const tp_buf *b, Py_ssize_t i)
{
    return __atomic_load_n((const uint8_t *)b->data + i, __ATOMIC_RELAXED) != 0;
}

static inline void tp_buf_st_bool(const tp_buf *b, Py_ssize_t i, int v)
{
    __atomic_store_n((uint8_t *)b->data + i, (uint8_t)(v != 0), __ATOMIC_RELAXED);
}

static inline double tp_buf_ld_f64(const tp_buf *b, Py_ssize_t i)
{
    uint64_t u = __atomic_load_n((const uint64_t *)b->data + i, __ATOMIC_RELAXED);
    double d;
    memcpy(&d, &u, sizeof d);
    return d;
}

static inline int64_t tp_buf_ld_i64(const tp_buf *b, Py_ssize_t i)
{
    uint64_t u = __atomic_load_n((const uint64_t *)b->data + i, __ATOMIC_RELAXED);
    int64_t v;
    memcpy(&v, &u, sizeof v);
    return v;
}

static inline void tp_buf_st_f64(const tp_buf *b, Py_ssize_t i, double v)
{
    uint64_t u;
    memcpy(&u, &v, sizeof u);
    __atomic_store_n((uint64_t *)b->data + i, u, __ATOMIC_RELAXED);
}

static inline void tp_buf_st_i64(const tp_buf *b, Py_ssize_t i, int64_t v)
{
    uint64_t u;
    memcpy(&u, &v, sizeof u);
    __atomic_store_n((uint64_t *)b->data + i, u, __ATOMIC_RELAXED);
}
#endif

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
    PyObject *v = NULL, *builtins = NULL;
    int rc;
    /* strong references only (PyDict_GetItemRef): another thread may rebind or delete the name,
     * or replace __builtins__, at any moment */
    rc = PyDict_GetItemRef(module_dict, name, &v);
    if (rc > 0) {
        return v;
    }
    if (rc < 0) {
        return NULL;
    }
    rc = PyDict_GetItemStringRef(module_dict, "__builtins__", &builtins);   /* errors ignored */
    if (rc < 0) {
        PyErr_Clear();
        builtins = NULL;
    }
    if (builtins != NULL && PyModule_Check(builtins)) {
        PyObject *d = PyModule_GetDict(builtins);          /* borrowed: owned by `builtins`, which we hold */
        Py_XINCREF(d);
        Py_DECREF(builtins);
        builtins = d;
    }
    if (builtins == NULL || !PyDict_Check(builtins)) {
        Py_XDECREF(builtins);
        builtins = PyEval_GetFrameBuiltins();              /* new reference */
        if (builtins == NULL) {
            PyErr_Clear();
        }
    }
    if (builtins != NULL) {
        rc = PyDict_GetItemRef(builtins, name, &v);
        Py_DECREF(builtins);
        if (rc > 0) {
            return v;
        }
        if (rc < 0) {
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

/* Fast-path guards (issue #150). A fast path may replace a generic C API call only when the
 * operand is exactly the type for which the fast path is what the generic call ends up in:
 * subclasses (which can override __getitem__, __setitem__, __add__, ...) always take the generic
 * call. These two macros are the only places where "exactly" is decided; the mutation tests in
 * test_str_dict.py widen each to PyDict_Check / PyUnicode_Check and must fail. */
#define TP_FASTPATH_GUARD_DICT(o) PyDict_CheckExact(o)
#define TP_FASTPATH_GUARD_STR(o) PyUnicode_CheckExact(o)

/* op is an ir.BinOpKind: TP_BINOP_ADD .. TP_BINOP_MOD (declaration order), or its in-place form
 * TP_BINOP_IADD .. TP_BINOP_IMOD. `str + str` (both exactly str) is PyUnicode_Concat, which is what
 * PyNumber_Add ends in for two strs (str has no nb_add, its sq_concat is PyUnicode_Concat), and
 * what the interpreter's BINARY_OP_ADD_UNICODE calls. */
static inline PyObject *tp_binop_obj(PyObject *a, PyObject *b, int op)
{
    switch (op) {
    case TP_BINOP_ADD:
        if (TP_FASTPATH_GUARD_STR(a) && TP_FASTPATH_GUARD_STR(b)) {
            return PyUnicode_Concat(a, b);
        }
        return PyNumber_Add(a, b);
    case TP_BINOP_SUB: return PyNumber_Subtract(a, b);
    case TP_BINOP_MUL: return PyNumber_Multiply(a, b);
    case TP_BINOP_TRUEDIV: return PyNumber_TrueDivide(a, b);
    case TP_BINOP_FLOORDIV: return PyNumber_FloorDivide(a, b);
    case TP_BINOP_MOD: return PyNumber_Remainder(a, b);
    case TP_BINOP_IADD: return PyNumber_InPlaceAdd(a, b);
    case TP_BINOP_ISUB: return PyNumber_InPlaceSubtract(a, b);
    case TP_BINOP_IMUL: return PyNumber_InPlaceMultiply(a, b);
    case TP_BINOP_ITRUEDIV: return PyNumber_InPlaceTrueDivide(a, b);
    case TP_BINOP_IFLOORDIV: return PyNumber_InPlaceFloorDivide(a, b);
    case TP_BINOP_IMOD: return PyNumber_InPlaceRemainder(a, b);
    case TP_BINOP_LSHIFT: return PyNumber_Lshift(a, b);
    case TP_BINOP_RSHIFT: return PyNumber_Rshift(a, b);
    case TP_BINOP_ILSHIFT: return PyNumber_InPlaceLshift(a, b);
    case TP_BINOP_IRSHIFT: return PyNumber_InPlaceRshift(a, b);
    }
    PyErr_SetString(PyExc_SystemError, "tp_binop_obj: unknown operator");
    return NULL;
}

/* ------------------------------------------------------------------------------------------
 * str and dict code (issue #150; API.md "Objects: subscript, in, f-strings, iteration"). Each
 * helper makes the C API call the interpreter's bytecode makes; the fast paths are guarded by the
 * exact type (TP_FASTPATH_GUARD_*). All return new references or NULL / -1 with CPython's exception.
 * ------------------------------------------------------------------------------------------ */

/* BINARY_OP []: PyObject_GetItem. For an object that is exactly a dict: PyDict_GetItemRef (a strong
 * reference, free-threaded safe) and KeyError(key) when absent, which is what dict_subscript does
 * for an exact dict (BINARY_OP_SUBSCR_DICT in the interpreter). */
static inline PyObject *tp_getitem(PyObject *obj, PyObject *key)
{
    if (TP_FASTPATH_GUARD_DICT(obj)) {
        PyObject *v = NULL;
        int rc = PyDict_GetItemRef(obj, key, &v);
        if (rc > 0) {
            return v;
        }
        if (rc == 0) {
            PyObject *exc = PyObject_CallOneArg(PyExc_KeyError, key);   /* _PyErr_SetKeyError */
            if (exc != NULL) {
                PyErr_SetObject((PyObject *)Py_TYPE(exc), exc);
                Py_DECREF(exc);
            }
        }
        return NULL;
    }
    return PyObject_GetItem(obj, key);
}

/* STORE_SUBSCR: PyObject_SetItem; an exact dict's mp_ass_subscript is PyDict_SetItem. 0 / -1. */
static inline int tp_setitem(PyObject *obj, PyObject *key, PyObject *value)
{
    if (TP_FASTPATH_GUARD_DICT(obj)) {
        return PyDict_SetItem(obj, key, value);
    }
    return PyObject_SetItem(obj, key, value);
}

/* CONTAINS_OP: PySequence_Contains(container, needle). 1 / 0 / -1. */
static inline int tp_contains(PyObject *container, PyObject *needle)
{
    return PySequence_Contains(container, needle);
}

static inline PyObject *tp_new_list(void)
{
    return PyList_New(0);
}

static inline PyObject *tp_new_dict(void)
{
    return PyDict_New();
}

/* One f-string field (CONVERT_VALUE, then FORMAT_SIMPLE or FORMAT_WITH_SPEC): `conversion` is -1,
 * 's', 'r' or 'a'; `spec` is NULL or the format spec str. */
static inline PyObject *tp_format(PyObject *value, PyObject *spec, int conversion)
{
    PyObject *converted, *result;
    switch (conversion) {
    case -1: converted = Py_NewRef(value); break;
    case 's': converted = PyObject_Str(value); break;
    case 'r': converted = PyObject_Repr(value); break;
    case 'a': converted = PyObject_ASCII(value); break;
    default:
        PyErr_SetString(PyExc_SystemError, "tp_format: unknown conversion");
        return NULL;
    }
    if (converted == NULL) {
        return NULL;
    }
    if (spec == NULL && PyUnicode_CheckExact(converted)) {
        return converted;                      /* FORMAT_SIMPLE: an exact str is its own format */
    }
    result = PyObject_Format(converted, spec);
    Py_DECREF(converted);
    return result;
}

/* BUILD_STRING: the join of n strs with the empty separator. */
static inline PyObject *tp_build_string(PyObject *const *parts, Py_ssize_t n)
{
    PyObject *seq = tp_tuple(parts, n);
    PyObject *empty, *result;
    if (seq == NULL) {
        return NULL;
    }
    empty = PyUnicode_New(0, 0);               /* the empty-string singleton */
    if (empty == NULL) {
        Py_DECREF(seq);
        return NULL;
    }
    result = PyUnicode_Join(empty, seq);
    Py_DECREF(empty);
    Py_DECREF(seq);
    return result;
}

/* GET_ITER: PyObject_GetIter. */
static inline PyObject *tp_get_iter(PyObject *obj)
{
    return PyObject_GetIter(obj);
}

/* FOR_ITER: the next item (a new reference), or NULL with no error set at the end (a StopIteration
 * is the end and is cleared), or NULL with the exception set. */
static inline PyObject *tp_iter_next(PyObject *iter)
{
    return PyIter_Next(iter);
}

/* UNPACK_SEQUENCE (no star) of `v` into the n slots `out`, each a new reference. An exact tuple of
 * length n is taken directly (immutable, so the items cannot change under us); anything else goes
 * through its iterator, with the interpreter's unpack_iterable errors. 0 ok; -1 error, with no
 * reference left in `out`. */
static inline int tp_unpack(PyObject *v, Py_ssize_t n, PyObject **out)
{
    PyObject *it, *w;
    Py_ssize_t i;
    if (PyTuple_CheckExact(v) && PyTuple_GET_SIZE(v) == n) {
        for (i = 0; i < n; i++) {
            out[i] = Py_NewRef(PyTuple_GET_ITEM(v, i));
        }
        return 0;
    }
    it = PyObject_GetIter(v);
    if (it == NULL) {
        if (PyErr_ExceptionMatches(PyExc_TypeError) && Py_TYPE(v)->tp_iter == NULL
                && !PySequence_Check(v)) {
            PyErr_Format(PyExc_TypeError, "cannot unpack non-iterable %.200s object",
                         Py_TYPE(v)->tp_name);
        }
        return -1;
    }
    for (i = 0; i < n; i++) {
        w = PyIter_Next(it);
        if (w == NULL) {
            if (!PyErr_Occurred()) {
                PyErr_Format(PyExc_ValueError,
                             "not enough values to unpack (expected %zd, got %zd)", n, i);
            }
            goto fail;
        }
        out[i] = w;
    }
    w = PyIter_Next(it);
    if (w == NULL) {
        if (PyErr_Occurred()) {
            goto fail;
        }
        Py_DECREF(it);
        return 0;
    }
    Py_DECREF(w);
    if (PyList_CheckExact(v) || PyTuple_CheckExact(v) || PyDict_CheckExact(v)) {
        Py_ssize_t ll = PyDict_CheckExact(v) ? PyDict_Size(v) : Py_SIZE(v);
        PyErr_Format(PyExc_ValueError, "too many values to unpack (expected %zd, got %zd)", n, ll);
    }
    else {
        PyErr_Format(PyExc_ValueError, "too many values to unpack (expected %zd)", n);
    }
fail:
    while (i > 0) {
        Py_CLEAR(out[--i]);
    }
    Py_DECREF(it);
    return -1;
}

/* Py_CLEAR: the only way generated code drops a reference */
static inline void tp_release(PyObject **slot)
{
    Py_CLEAR(*slot);
}

/* ------------------------------------------------------------------------------------------
 * Native ints in impure functions (SPEC N-13, ir.Type.INT; API.md "Native ints").
 * A tp_int is a Python int that is a native i64 (`big == NULL`) or, after an operation left the
 * i64 range, a PyLong (`big` an owned reference to an exact int, `v` unused). Every operation is
 * the native one when both operands are native and the result fits, else CPython's PyNumber_* on
 * the two ints (exact ints, so no user code runs) with the result narrowed back to native when it
 * fits. Nothing deopts. The generated code owns each tp_int temporary and local, releases `big` on
 * every exit and moves a temporary into a local with tp_int_assign.
 * ------------------------------------------------------------------------------------------ */
typedef struct {
    int64_t v;
    PyObject *big;
} tp_int;

/* The single mutation points of the overflow checks (the mutation tests in test_native_int_impure.py
 * replace each by a wrapping operation and must fail). */
#define TP_INT_ADD_OVF(a, b, r) __builtin_add_overflow((a), (b), (r))
#define TP_INT_SUB_OVF(a, b, r) __builtin_sub_overflow((a), (b), (r))
#define TP_INT_MUL_OVF(a, b, r) __builtin_mul_overflow((a), (b), (r))

static inline void tp_int_release(tp_int *a)
{
    if (a->big != NULL) {
        Py_CLEAR(a->big);
    }
}

static inline void tp_int_copy(tp_int *dst, const tp_int *src)
{
    dst->v = src->v;
    dst->big = src->big != NULL ? Py_NewRef(src->big) : NULL;
}

/* *dst takes the value of *src (src is left empty); the reference *dst held is released */
static inline void tp_int_assign(tp_int *dst, tp_int *src)
{
    tp_int old = *dst;
    *dst = *src;
    src->v = 0;
    src->big = NULL;
    tp_int_release(&old);
}

/* a new reference to the int */
static inline PyObject *tp_int_box(const tp_int *a)
{
    return a->big != NULL ? Py_NewRef(a->big) : PyLong_FromLongLong((long long)a->v);
}

/* *out = the int `o` (a new reference, stolen): native when it fits i64, else kept as the PyLong.
 * NULL (the operation failed) or anything but an exact int -> -1 with the exception set. */
static inline int tp_int_take(tp_int *out, PyObject *o)
{
    int overflow = 0;
    long long v;
    if (o == NULL) {
        return -1;
    }
    if (!PyLong_CheckExact(o)) {
        Py_DECREF(o);
        PyErr_SetString(PyExc_SystemError, "typedpython: an int operation did not produce an int");
        return -1;
    }
    v = PyLong_AsLongLongAndOverflow(o, &overflow);
    if (overflow == 0) {
        if (v == -1 && PyErr_Occurred()) {
            Py_DECREF(o);
            return -1;
        }
        out->v = (int64_t)v;
        out->big = NULL;
        Py_DECREF(o);
        return 0;
    }
    out->v = 0;
    out->big = o;
    return 0;
}

/* *out = the exact int `o` (borrowed; a new reference is taken when it does not fit i64):
 * 0 ok, 1 not an exact int (nothing set, no exception) */
static inline int tp_int_of_exact(tp_int *out, PyObject *o)
{
    if (!PyLong_CheckExact(o)) {
        return 1;
    }
    return tp_int_take(out, Py_NewRef(o)) == 0 ? 0 : -1;
}

/* the general case: a op b with CPython's own operator on the two ints */
static int tp_int_binop_slow(int op, const tp_int *a, const tp_int *b, tp_int *out)
{
    PyObject *x = tp_int_box(a), *y, *r;
    if (x == NULL) {
        return -1;
    }
    y = tp_int_box(b);
    if (y == NULL) {
        Py_DECREF(x);
        return -1;
    }
    r = tp_binop_obj(x, y, op);
    Py_DECREF(x);
    Py_DECREF(y);
    return tp_int_take(out, r);
}

#define TP_INT_NATIVE2(a, b) ((a)->big == NULL && (b)->big == NULL)

static inline int tp_int_add(const tp_int *a, const tp_int *b, tp_int *out)
{
    int64_t r;
    if (TP_INT_NATIVE2(a, b) && !TP_INT_ADD_OVF(a->v, b->v, &r)) {
        out->v = r;
        out->big = NULL;
        return 0;
    }
    return tp_int_binop_slow(TP_BINOP_ADD, a, b, out);
}

static inline int tp_int_sub(const tp_int *a, const tp_int *b, tp_int *out)
{
    int64_t r;
    if (TP_INT_NATIVE2(a, b) && !TP_INT_SUB_OVF(a->v, b->v, &r)) {
        out->v = r;
        out->big = NULL;
        return 0;
    }
    return tp_int_binop_slow(TP_BINOP_SUB, a, b, out);
}

static inline int tp_int_mul(const tp_int *a, const tp_int *b, tp_int *out)
{
    int64_t r;
    if (TP_INT_NATIVE2(a, b) && !TP_INT_MUL_OVF(a->v, b->v, &r)) {
        out->v = r;
        out->big = NULL;
        return 0;
    }
    return tp_int_binop_slow(TP_BINOP_MUL, a, b, out);
}

/* floor semantics; a zero divisor and -2**63 // -1 take the general path (CPython's own error / the
 * PyLong 2**63) */
static inline int tp_int_floordiv(const tp_int *a, const tp_int *b, tp_int *out)
{
    int64_t r;
    if (TP_INT_NATIVE2(a, b) && b->v != 0 && !(b->v == -1 && a->v == INT64_MIN)
        && tp_floordiv_i64(a->v, b->v, &r) == 0) {
        out->v = r;
        out->big = NULL;
        return 0;
    }
    return tp_int_binop_slow(TP_BINOP_FLOORDIV, a, b, out);
}

static inline int tp_int_mod(const tp_int *a, const tp_int *b, tp_int *out)
{
    int64_t r;
    if (TP_INT_NATIVE2(a, b) && b->v != 0 && tp_mod_i64(a->v, b->v, &r) == 0) {
        out->v = r;
        out->big = NULL;
        return 0;
    }
    return tp_int_binop_slow(TP_BINOP_MOD, a, b, out);
}

/* a << b natively for 0 <= b <= 63 when no bit is lost (shifting back gives a); anything else,
 * including a negative count (ValueError) and a huge one, is CPython's */
static inline int tp_int_lshift(const tp_int *a, const tp_int *b, tp_int *out)
{
    if (TP_INT_NATIVE2(a, b) && b->v >= 0 && b->v <= 63) {
        int64_t r = (int64_t)((uint64_t)a->v << b->v);
        if ((r >> b->v) == a->v) {
            out->v = r;
            out->big = NULL;
            return 0;
        }
    }
    return tp_int_binop_slow(TP_BINOP_LSHIFT, a, b, out);
}

static inline int tp_int_rshift(const tp_int *a, const tp_int *b, tp_int *out)
{
    if (TP_INT_NATIVE2(a, b) && b->v >= 0) {
        out->v = b->v >= 64 ? (a->v < 0 ? -1 : 0) : (a->v >> b->v);
        out->big = NULL;
        return 0;
    }
    return tp_int_binop_slow(TP_BINOP_RSHIFT, a, b, out);
}

static inline int tp_int_neg(const tp_int *a, tp_int *out)
{
    PyObject *x, *r;
    if (a->big == NULL && a->v != INT64_MIN) {
        out->v = -a->v;
        out->big = NULL;
        return 0;
    }
    x = tp_int_box(a);
    if (x == NULL) {
        return -1;
    }
    r = PyNumber_Negative(x);
    Py_DECREF(x);
    return tp_int_take(out, r);
}

/* a <op> b as a condition: 0 / 1, -1 error. `op` is Py_EQ .. Py_GE. */
static inline int tp_int_cmp(const tp_int *a, const tp_int *b, int op)
{
    PyObject *x, *y;
    int r;
    if (TP_INT_NATIVE2(a, b)) {
        switch (op) {
        case Py_LT: return a->v < b->v;
        case Py_LE: return a->v <= b->v;
        case Py_EQ: return a->v == b->v;
        case Py_NE: return a->v != b->v;
        case Py_GT: return a->v > b->v;
        default: return a->v >= b->v;
        }
    }
    x = tp_int_box(a);
    if (x == NULL) {
        return -1;
    }
    y = tp_int_box(b);
    if (y == NULL) {
        Py_DECREF(x);
        return -1;
    }
    r = tp_compare_bool(x, y, op);
    Py_DECREF(x);
    Py_DECREF(y);
    return r;
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
    unsigned int tag;       /* tp_version_tag at which the slots were proved; never 0 while compiled.
                               ATOMIC (tp_atomic_*): any thread reads and refreshes it */
    unsigned int bad_tag;   /* a tag at which tp_class_refresh failed (0: none); not retried. ATOMIC */
    uint64_t gepoch;        /* tp_globals_epoch at which module_dict[name] was last seen to be cls.
                               ATOMIC */
    PyObject *init;         /* strong: the class's own __init__ at capture, or NULL if it had none.
                               IMMUTABLE after tp_class_capture */
    Py_ssize_t slow;        /* per-access checks that failed (introspection only). ATOMIC */
    Py_ssize_t refreshed;   /* new tags adopted by tp_class_refresh (introspection only). ATOMIC */
    unsigned int mtag;      /* tp_version_tag at which every compiled method installed in the class was
                               the very object in the class's own dict (tp_methods_refresh); 0 until
                               tp_class_retag. ATOMIC */
    unsigned int mbad_tag;  /* a tag at which tp_methods_refresh failed (0: none); not retried. ATOMIC */
} tp_class_rt;

/* Moves whenever a watched module dict changes a key that is (or may be) a compiled class name, is
 * cleared, cloned or deallocated (tp_globals_watch_event). Static: one counter per extension, shared
 * by every instance of the module in the process; an instance re-proves its own globals after any
 * move. ATOMIC (tp_atomic_*): the watcher callback runs on whichever thread changes the dict, and
 * the module declares Py_mod_gil = Py_MOD_GIL_NOT_USED. The module declares no
 * Py_mod_multiple_interpreters slot, so CPython loads it only into interpreters sharing the main
 * one. */
static uint64_t tp_globals_epoch = 1;


/* ------------------------------------------------------------------------------------------
 * Cached global and builtin reads (SPEC N-13; API.md "Global cache"). LOAD_GLOBAL of a name an
 * impure function reads: the object is remembered per name in a tp_gslot and reused while
 * tp_globals_epoch has not moved. The epoch moves when the watcher (tp_globals_watch_event) sees
 * a change of any watched name in the module dict or the builtins dict, a clear, or a rebinding of
 * `__builtins__` (which also turns the cache off for good, tp_gcache_off).
 * The slot holds a BORROWED pointer: it extends no lifetime, so a rebound function, type or module
 * is freed (and its `__del__`s run) exactly when CPython would free it. A hit must therefore take
 * its own reference safely:
 *   GIL build:       nothing runs between the epoch check and the incref, and the epoch moves
 *                    before the dict drops its reference, so an unchanged epoch means the dict
 *                    still holds the object.
 *   free-threaded:   the fill calls PyUnstable_EnableTryIncRef(obj), which delays the object's
 *                    memory past a quiescent state, so PyUnstable_TryIncRef on a just-freed object
 *                    fails safely; after a successful incref the epoch is read again, and an
 *                    unchanged epoch (the watcher bumps it BEFORE the dict store) means the dict
 *                    still held the object when the reference was taken.
 * Needs CPython 3.14+ when free-threaded (the TryIncRef API); 3.13t does not cache.
 * ------------------------------------------------------------------------------------------ */
#define TP_GCACHE_FRESH(slot_epoch, epoch) ((slot_epoch) == (epoch))
#if !defined(Py_GIL_DISABLED) || PY_VERSION_HEX >= 0x030E0000
#define TP_GCACHE_AVAILABLE 1
#else
#define TP_GCACHE_AVAILABLE 0
#endif

typedef struct {
    PyObject *obj;                          /* BORROWED; ATOMIC, written under the module dict's lock */
    uint64_t epoch;                         /* tp_globals_epoch at which obj was read; 0 = empty. ATOMIC */
} tp_gslot;

static unsigned int tp_gcache_off = 0;      /* ATOMIC: 1 after `__builtins__` was rebound */

static inline void tp_gcache_clear(tp_gslot *s)
{
    s->epoch = 0;
    s->obj = NULL;
}

/* the slow path: look the name up (module dict, then the builtins dict captured at exec) inside both
 * dicts' critical sections, so the epoch read and the lookup cannot straddle a change, and remember
 * the (borrowed) object. A new reference, or NULL with the exception (NameError from tp_global when
 * the name is unbound). */
static PyObject *tp_gcache_fill(tp_gslot *slot, PyObject *module_dict, PyObject *builtins,
                                PyObject *name)
{
    PyObject *v = NULL;
    uint64_t epoch = 0;
    int rc;
    if (!TP_GCACHE_AVAILABLE || builtins == NULL || tp_atomic_load_u32(&tp_gcache_off)) {
        return tp_global(module_dict, name);
    }
    Py_BEGIN_CRITICAL_SECTION2(module_dict, builtins);
    epoch = tp_atomic_load_u64(&tp_globals_epoch);
    rc = PyDict_GetItemRef(module_dict, name, &v);
    if (rc == 0) {
        rc = PyDict_GetItemRef(builtins, name, &v);
    }
    if (rc > 0) {
#if defined(Py_GIL_DISABLED) && PY_VERSION_HEX >= 0x030E0000
        PyUnstable_EnableTryIncRef(v);
#endif
        __atomic_store_n(&slot->obj, v, __ATOMIC_RELEASE);      /* borrowed: v stays ours to return */
        __atomic_store_n(&slot->epoch, epoch, __ATOMIC_RELEASE);
    }
    Py_END_CRITICAL_SECTION2();
    if (rc < 0) {
        return NULL;
    }
    if (rc == 0) {
        return tp_global(module_dict, name);
    }
    return v;
}

/* LOAD_GLOBAL through the cache. `watched`: a dict watcher is installed (without one no change would
 * be seen, and every read looks up). */
static inline PyObject *tp_gcache_global(tp_gslot *slot, PyObject *module_dict, PyObject *builtins,
                                         PyObject *name, int watched)
{
    if (watched && TP_GCACHE_AVAILABLE) {
        uint64_t e = __atomic_load_n(&tp_globals_epoch, __ATOMIC_ACQUIRE);
        if (TP_GCACHE_FRESH(__atomic_load_n(&slot->epoch, __ATOMIC_ACQUIRE), e)) {
            PyObject *p = __atomic_load_n(&slot->obj, __ATOMIC_ACQUIRE);
#if defined(Py_GIL_DISABLED) && PY_VERSION_HEX >= 0x030E0000
            if (p != NULL && PyUnstable_TryIncRef(p)) {
                if (__atomic_load_n(&tp_globals_epoch, __ATOMIC_ACQUIRE) == e) {
                    return p;
                }
                Py_DECREF(p);
            }
#else
            if (p != NULL) {
                return Py_NewRef(p);
            }
#endif
        }
    }
    if (!watched) {
        return tp_global(module_dict, name);
    }
    return tp_gcache_fill(slot, module_dict, builtins, name);
}

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
    rt->mtag = 0;
    rt->mbad_tag = 0;
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
    if (!PyUnstable_Type_AssignVersionTag(cls) || tp_type_tag(cls) == 0) {
        tp_class_rt_clear(rt, descrs, n);
        return 0;
    }
    /* init time: the class has not been handed to compiled code yet, plain stores are enough (the
     * import completing publishes them) */
    rt->tag = tp_type_tag(cls);
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

    if (!PyUnstable_Type_AssignVersionTag(cls) || tp_type_tag(cls) == 0) {
        return 0;                                /* tags exhausted for this class */
    }
    /* The tag is read BEFORE the dict is examined: a change after the read gives the class a new
     * tag, so a tag adopted below is stale at once and the next access refreshes again. Two
     * threads refreshing at the same time adopt the same or an older tag; both are safe. */
    tag = tp_type_tag(cls);
    if (tag == tp_atomic_load_u32(&rt->tag)) {
        return 1;
    }
    if (tag == tp_atomic_load_u32(&rt->bad_tag)) {
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
    tp_atomic_store_u32(&rt->tag, tag);
    tp_atomic_add_ssize(&rt->refreshed, 1);
    return 1;
bad:
    tp_atomic_store_u32(&rt->bad_tag, tag);
    return 0;
}

/* A failed per-access check was taken (introspection counter). */
static inline void tp_class_note_slow(tp_class_rt *rt)
{
    tp_atomic_add_ssize(&rt->slow, 1);
}

/* introspection reads (__typedpython_class_info__) */
static inline unsigned int tp_class_rt_tag(const tp_class_rt *rt) { return tp_atomic_load_u32(&rt->tag); }
static inline Py_ssize_t tp_class_rt_slow(const tp_class_rt *rt) { return tp_atomic_load_ssize(&rt->slow); }
static inline Py_ssize_t tp_class_rt_refreshed(const tp_class_rt *rt)
{
    return tp_atomic_load_ssize(&rt->refreshed);
}

/* cls's current tp_version_tag, 0 for NULL (introspection: __typedpython_class_info__). */
static inline unsigned int tp_class_tag(PyObject *cls)
{
    return cls != NULL ? tp_type_tag((PyTypeObject *)cls) : 0u;
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
    if (tp_type_tag(cls) == tp_atomic_load_u32(&rt->tag)) {   /* rt->tag != 0 while cls != NULL */
        return 1;
    }
    return tp_class_refresh(cls, rt, fields, n, descrs);
}

/* Methods (SPEC N-11, #155). At module init, after the module installed its descriptors in the class
 * dict (which moved the class's version tag): adopt the new tag as the proved tag of the slots (the
 * fields and __init__ were not touched) and of the methods. Init time, plain stores. 1 = done, 0 = the
 * class cannot get a tag (the caller marks it not compiled). */
static inline int tp_class_retag(PyTypeObject *cls, tp_class_rt *rt)
{
    if (!PyUnstable_Type_AssignVersionTag(cls) || tp_type_tag(cls) == 0) {
        return 0;
    }
    rt->tag = tp_type_tag(cls);
    rt->bad_tag = 0;
    rt->mtag = rt->tag;
    rt->mbad_tag = 0;
    return 1;
}

/* The tag moved (or is 0). Re-prove that `obj.m` still finds the compiled method for every installed
 * method: the shape still holds (no __getattribute__ / __setattr__ ... added since: they would run
 * instead of the plain lookup), and the class's own dict entry of each name is the very descriptor
 * object the module installed (identity, descrs[i] keeps it alive). No user code runs. The tag is
 * read BEFORE the dict is examined, as in tp_class_refresh. 1 = current (tag adopted), 0 = not,
 * -1 = error (a failed dict lookup). */
static inline int tp_methods_refresh(PyTypeObject *cls, tp_class_rt *rt, const char *const *names,
                                     Py_ssize_t n, PyObject *const *descrs)
{
    unsigned int tag;
    Py_ssize_t i;
    PyObject *d;
    int found;

    if (!PyUnstable_Type_AssignVersionTag(cls) || tp_type_tag(cls) == 0) {
        return 0;
    }
    tag = tp_type_tag(cls);
    if (tag == tp_atomic_load_u32(&rt->mtag)) {
        return 1;
    }
    if (tag == tp_atomic_load_u32(&rt->mbad_tag)) {
        return 0;
    }
    if (!tp_class_shape_ok(cls)) {
        goto bad;
    }
    for (i = 0; i < n; i++) {
        found = tp_own_attr(cls, names[i], &d);
        if (found < 0) {
            return -1;
        }
        Py_XDECREF(d);
        if (d != descrs[i]) {
            goto bad;
        }
    }
    tp_atomic_store_u32(&rt->mtag, tag);
    return 1;
bad:
    tp_atomic_store_u32(&rt->mbad_tag, tag);
    return 0;
}

/* CheckMethod: `type(obj) is cls` and `obj.m` finds the compiled method installed for it, for every
 * name in `names`. 1 = yes, 0 = no (deopt), -1 = error. Fast case: one type compare, one tag compare. */
static inline int tp_methods_current(PyObject *obj, PyObject *cls_obj, tp_class_rt *rt,
                                     const char *const *names, Py_ssize_t n, PyObject *const *descrs)
{
    PyTypeObject *cls = (PyTypeObject *)cls_obj;
    unsigned int mtag;
    if (cls == NULL || !Py_IS_TYPE(obj, cls)) {
        return 0;
    }
    mtag = tp_atomic_load_u32(&rt->mtag);
    if (mtag != 0 && tp_type_tag(cls) == mtag) {               /* mtag is 0 until tp_class_retag */
        return 1;
    }
    return tp_methods_refresh(cls, rt, names, n, descrs);
}

/* LOAD_GLOBAL of a class name, as an identity test: 1 = module_dict[name] is cls, 0 = it is not (or
 * the name is not a module global: the slow path then asks tp_global), -1 = error. With a dict
 * watcher on module_dict (`watched`), a 1 is remembered in rt->gepoch until tp_globals_epoch moves,
 * so the steady state is one compare; without one (no watcher id was free) every call looks up. */
static inline int tp_class_global_ok(PyObject *module_dict, PyObject *name, PyObject *cls,
                                     tp_class_rt *rt, int watched)
{
    PyObject *v = NULL;
    uint64_t epoch;
    int rc;
    if (cls == NULL) {
        return 0;
    }
    if (watched && tp_atomic_load_u64(&rt->gepoch) == tp_atomic_load_u64(&tp_globals_epoch)) {
        return 1;
    }
    /* The epoch is read and the dict looked up inside the dict's critical section. The watcher
     * runs BEFORE a change is stored and under the same lock, so either the change finished (the
     * lookup sees it) or it has not begun (its epoch move comes later and invalidates what is
     * remembered below). Without the lock a thread could read the epoch after the move but look up
     * before the store, and remember a stale binding as current for good. */
    Py_BEGIN_CRITICAL_SECTION(module_dict);
    epoch = tp_atomic_load_u64(&tp_globals_epoch);
    rc = PyDict_GetItemRef(module_dict, name, &v);     /* strong */
    Py_END_CRITICAL_SECTION();
    if (rc <= 0) {
        return rc;                                     /* 0 absent, -1 error */
    }
    rc = (v == cls);
    Py_DECREF(v);
    if (rc && watched) {
        tp_atomic_store_u64(&rt->gepoch, epoch);
    }
    return rc;
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
                if (PyUnicode_EqualToUTF8(key, "__builtins__")) {
                    tp_atomic_store_u32(&tp_gcache_off, 1);     /* the builtins dict we watch may be replaced */
                }
                tp_atomic_add_u64(&tp_globals_epoch, 1);
                return 0;
            }
        }
        return 0;
#else
        (void)i;
        (void)names;
#endif
    }
    tp_atomic_add_u64(&tp_globals_epoch, 1);
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

/* Watch the builtins dict with the module's watcher (cached builtin reads, SPEC N-13). Returns 0, or -1
 * when it cannot be watched (no exception left): the caller then drops the watcher, so nothing is cached. */
static inline int tp_globals_watch_builtins(PyObject *builtins, int id1)
{
    if (id1 <= 0 || builtins == NULL) {
        return 0;
    }
    if (PyDict_Watch(id1 - 1, builtins) < 0) {
        PyErr_Clear();
        return -1;
    }
    return 0;
}

static inline void tp_globals_unwatch_builtins(PyObject *builtins, int id1)
{
    PyObject *exc;
    if (id1 <= 0 || builtins == NULL) {
        return;
    }
    exc = PyErr_GetRaisedException();
    if (PyDict_Unwatch(id1 - 1, builtins) < 0) {
        PyErr_Clear();
    }
    PyErr_SetRaisedException(exc);
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

/* The exact-type guard of the `self` of a compiled method (wrapper and impl). A single definition so
 * a test can show that it matters (a header where it is always true is caught). */
#define TP_SELF_EXACT(o, cls) tp_is_exact(o, cls)

/* ------------------------------------------------------------------------------------------
 * Call depth (issue #57): compiled frames have no Python frame, so a recursion made of them would
 * neither raise RecursionError nor stop before the C stack ends. tp_enter_call / tp_leave_call
 * bracket every compiled impl function. See API.md "Call depth".
 * ------------------------------------------------------------------------------------------ */
typedef struct {
    Py_ssize_t count;       /* compiled frames in flight on this thread (this module) */
    PyObject *frame;        /* strong ref: the top Python frame when `pydepth` was measured */
    Py_ssize_t pydepth;     /* Python frames below and including `frame` */
} tp_depth_state;

#ifndef TP_POLL_INTERVAL
#define TP_POLL_INTERVAL 4096
#endif

/* All per-thread state of the compiled code in ONE thread-local, so a compiled call looks its address
 * up once (tp_tls_get, in the wrapper) and passes the pointer down (#125). */
typedef struct {
    tp_depth_state depth;           /* compiled frames in flight (see above) */
    int poll_countdown;             /* eval-breaker poll countdown (#141) */
    Py_ssize_t snapshot_depth;      /* snapshot-holding functions in flight (#141) */
} tp_tls;

static TP_THREAD_LOCAL tp_tls tp_tls_v = {{0, NULL, 0}, TP_POLL_INTERVAL, 0};

static inline tp_tls *tp_tls_get(void)
{
    return &tp_tls_v;
}

/* Python frame depth of the running thread: public API only. The walk is O(depth), so it is
 * cached by the identity of the top frame (kept alive while compiled frames are in flight). */
static inline Py_ssize_t tp_python_depth(PyThreadState *ts, tp_tls *t)
{
    PyFrameObject *top = PyThreadState_GetFrame(ts);    /* new ref or NULL */
    if (top == NULL) {
        return 0;
    }
    if (t->depth.frame == (PyObject *)top) {
        Py_DECREF(top);
        return t->depth.pydepth;
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
    PyObject *old = t->depth.frame;
    t->depth.frame = (PyObject *)top;       /* takes the reference from GetFrame */
    t->depth.pydepth = depth;
    Py_XDECREF(old);
    return depth;
}

static inline int tp_enter_call(PyThreadState *ts, tp_tls *t)
{
    if (tp_python_depth(ts, t) + t->depth.count + 1 > Py_GetRecursionLimit()) {
        PyErr_SetString(PyExc_RecursionError, "maximum recursion depth exceeded");
        return -1;
    }
    if (Py_EnterRecursiveCall(" in compiled code")) {
        return -1;
    }
    t->depth.count++;
    return 0;
}

static inline void tp_leave_call(tp_tls *t)
{
    Py_LeaveRecursiveCall();
    if (--t->depth.count == 0) {
        Py_CLEAR(t->depth.frame);
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
 * small compiled frames (no recursion, no Python callbacks except at the two points below), so they
 * use a bounded amount of C stack on top of the entry's.
 *
 * The frames of a fast tree are not in `tp_depth.count`. A bounded tree runs Python code at exactly
 * two points: a `redo` Call (the interpreted original) and the eval-breaker poll in a loop (signal
 * handler, finalizer, another compiled call). Around each, the twin brackets the code with
 * tp_depth_py_begin(tp_fd) / tp_depth_py_end(tp_fd), `tp_fd` being the tree's active frames (itself
 * included), so the count is exactly what the counted path would have there: Python frames and
 * compiled calls started from it see the true depth. */
static inline int tp_depth_room(PyThreadState *ts, tp_tls *t, Py_ssize_t k)
{
    return tp_python_depth(ts, t) + t->depth.count + k <= Py_GetRecursionLimit();
}

/* After a fast tree: tp_python_depth may have cached the top frame (a strong reference) and, with
 * no counted frame in flight, nothing else would drop it, as tp_leave_call does at count 0. */
static inline void tp_depth_done(tp_tls *t)
{
    if (t->depth.count == 0) {
        Py_CLEAR(t->depth.frame);
    }
}

/* Around a point of a fast tree that can run Python code: the count includes the tree's `fd` active
 * frames. begin/end are balanced. end also drops the cached top frame when no compiled frame is
 * counted any more (the code in between may have cached another frame, as tp_leave_call would). */
static inline void tp_depth_py_begin(tp_tls *t, int fd)
{
    t->depth.count += fd;
}

static inline void tp_depth_py_end(tp_tls *t, int fd)
{
    t->depth.count -= fd;
    tp_depth_done(t);
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

/* Per thread (#159): a shared counter would make threads poll for each other and, worse, let one
 * thread's snapshot-holding function suppress another thread's polls. */

/* Kept out of line and marked cold: inlined into every loop body it bloated short inner loops
 * (fannkuch paid ~11% with it inline). */
#if defined(__GNUC__) || defined(__clang__)
#define TP_COLD __attribute__((noinline, cold, unused))
#define TP_UNLIKELY(x) __builtin_expect(!!(x), 0)
#else
#define TP_COLD
#define TP_UNLIKELY(x) (x)
#endif

static TP_COLD int tp_poll_slow(tp_tls *t, PyObject *breaker)
{
    PyObject *r;
    t->poll_countdown = TP_POLL_INTERVAL;
    if (t->snapshot_depth > 0 || breaker == NULL) {
        return 0;
    }
    r = PyObject_CallNoArgs(breaker);
    if (r == NULL) {
        return -1;
    }
    Py_DECREF(r);
    return 0;
}

/* A loop's poll countdown (a local of the impl, kept in a register): 1 when the slow path is due,
 * which also resets the countdown. Inline, with the rare case marked unlikely. */
static inline int tp_pc_tick(int *pc)
{
    if (TP_UNLIKELY(--*pc == 0)) {
        *pc = TP_POLL_INTERVAL;
        return 1;
    }
    return 0;
}

/* 0 = continue, -1 = exception set (e.g. KeyboardInterrupt from a signal handler). */
static inline int tp_poll(tp_tls *t, PyObject *breaker)
{
    if (!TP_UNLIKELY(--t->poll_countdown <= 0)) {
        return 0;
    }
    return tp_poll_slow(t, breaker);
}

/* Tracing, profiling and monitoring (issue #142, SPEC N-14). A compiled function has no Python
 * frame, so it emits no settrace / setprofile / sys.monitoring events. At entry (and before a
 * direct call of a compiled callee from an impure caller) `tp_traced` says whether any of them is
 * active, and then the interpreted original runs instead, so the events are CPython's own.
 *
 * "Active" = this thread has a trace or profile function (public PyThreadState members), or any
 * sys.monitoring tool (ids 0..5) has a global event. Global monitoring events are read with the
 * public PyMonitoring_EnterScope, which updates a version number that changes whenever the global
 * events change and returns at once when it did not; the answer is recomputed in Python
 * (`sys.monitoring.get_events`) only when the version moved. The entry check is thus one call that
 * compares two integers plus a few loads and a branch. Local events (set_local_events, used by pdb
 * 3.14+ and debugpy for breakpoints) do not move that version, so they are read from the ORIGINAL
 * function's code object: PyCodeObject._co_monitoring (a public member in cpython/code.h) points to
 * a _PyCoMonitoringData (internal header), whose first member `local_monitors` is 16 bytes, one
 * tool mask per event. Any non-zero byte means a tool listens on that code object. That layout is
 * mirrored below for CPython 3.14 and 3.15 only; any other version stops the build with a named #error. A
 * tracer enabled by another thread during an entry is a benign race.
 * A monitoring tool turned on while a compiled function runs sees that function from its next call
 * on (SPEC N-14, known difference). */
static uint64_t tp_mon_ver = 0;
static int tp_mon_on = 0;
static PyMonitoringState tp_mon_state[1];
static const uint8_t tp_mon_event[1] = {PY_MONITORING_EVENT_PY_START};

#if PY_VERSION_HEX >= 0x030F0000 && PY_VERSION_HEX < 0x03100000
#define TP_LOCAL_EVENTS 16      /* 3.15: _PY_MONITORING_UNGROUPED_EVENTS */
#elif PY_VERSION_HEX >= 0x030E0000 && PY_VERSION_HEX < 0x030F0000
#define TP_LOCAL_EVENTS 11      /* 3.14: _PY_MONITORING_LOCAL_EVENTS */
#else
#error "typedpython: tp_traced reads the CPython 3.14/3.15 _co_monitoring layout"
#endif
/* The head of _PyCoMonitoringData (Include/internal/pycore_instruments.h): the local_monitors table,
 * one tool mask per local event. 3.14 and 3.15 only; the build target is 3.15t, 3.14 is the embedded
 * runtime that test_cgen compiles against. */
typedef struct { uint8_t tools[TP_LOCAL_EVENTS]; } tp_co_local_monitors;

static TP_COLD int tp_mon_scan(PyObject *module)
{
    int on = 1;     /* anything unexpected: the interpreter, which is always correct */
    PyObject *exc = PyErr_GetRaisedException();
    PyObject *sys = PyImport_ImportModule("sys");
    PyObject *mon = sys ? PyObject_GetAttrString(sys, "monitoring") : NULL;
    (void)module;
    if (mon != NULL) {
        on = 0;
        for (int tool = 0; tool < 6 && !on; tool++) {
            PyObject *r = PyObject_CallMethod(mon, "get_events", "i", tool);
            if (r == NULL || PyLong_AsLong(r) != 0) {
                on = 1;
            }
            Py_XDECREF(r);
        }
    }
    Py_XDECREF(mon);
    Py_XDECREF(sys);
    PyErr_Clear();
    PyErr_SetRaisedException(exc);
    return on;
}

static inline int tp_traced(PyObject *module, PyThreadState *ts, PyCodeObject *code)
{
    uint64_t v = tp_mon_ver;
    /* sys.settrace / sys.setprofile (and PyEval_SetTrace / SetProfile): per thread, as in CPython.
     * c_tracefunc / c_profilefunc are public members of PyThreadState (cpython/pystate.h; coverage's
     * C tracer and Cython read them), the only internal-looking read here. */
    if (TP_UNLIKELY(ts->c_tracefunc != NULL || ts->c_profilefunc != NULL)) {
        return 1;
    }
    PyMonitoring_EnterScope(tp_mon_state, &v, tp_mon_event, 1);
    if (TP_UNLIKELY(v != tp_mon_ver)) {
        tp_mon_on = tp_mon_scan(module);
        tp_mon_ver = v;
    }
    if (TP_UNLIKELY(tp_mon_on || code == NULL)) {
        return 1;
    }
    {
        const tp_co_local_monitors *lm = (const tp_co_local_monitors *)code->_co_monitoring;
        uint8_t any = 0;
        if (lm == NULL) {
            return 0;
        }
        for (int i = 0; i < TP_LOCAL_EVENTS; i++) {
            any |= lm->tools[i];
        }
        return TP_UNLIKELY(any != 0);
    }
}

#endif /* TP_RUNTIME_H */
