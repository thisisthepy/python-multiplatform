/* tp_aot.h: the runtime of the TypedPython AOT mode (SPEC N-17, #448, docs/design/typedpython-aot.md).
 *
 * Everything here is an agent proposal, not owner-confirmed. The header includes no interpreter header
 * and names no interpreter API: a program built with it contains and loads no interpreter. It is a sibling of
 * tp_runtime.h (the default mode's runtime), which it does not include. Where an operation has to give
 * CPython's result, the helper copies the semantics of the matching tp_runtime.h helper (the lines are
 * cited) and the message strings are those of CPython 3.14 and 3.15 (SPEC T-1: the baseline is 3.15).
 *
 * Contents: the current-exception record, checked-free i64 helpers (floor division and modulo with
 * CPython's sign rules), float helpers, exact int/float comparison, the nine native `math` functions
 * with CPython's error rules, `repr(float)`, native lists of floats and ints (counted, non-atomic: the
 * single-threaded tier; atomic in a library build), the program argument count and the `print`
 * pieces including `format(x, '.Nf')`.
 *
 * A compiled function returns 0 (ok) or -1 (an exception is recorded in `tpa_exc`) and writes its
 * result through an out pointer. Nothing here unwinds: every function leaves through one cleanup
 * point that releases its lists, so an exception that reaches `main` leaves nothing allocated.
 *
 * Library build (`-DTPA_LIBRARY`, `typedpython aot-lib`; owner decision D5 (2026-11-16) for the existence of
 * the library output, leader decision 2026-11-16, not owner-confirmed, for the shape below): the same
 * source, with (1) ATOMIC list counts (through TPA_THREADS: any thread of the host may hold a list),
 * (2) no program-argument state (a library has no `main`), (3) the export macro and the pieces the exported
 * wrappers use (the thread's error record, the null-handle error). An executable build does not define
 * TPA_LIBRARY and gets exactly the code it had before: non-atomic counts, `tpa_argc` and `tpa_argv`.
 */
#ifndef TP_AOT_H
#define TP_AOT_H

#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Issue #464: an AOT build must compute the floats CPython computes, even when a caller compiles the
 * generated C (--emit-c, or a packaging step outside aot/cc.py) without our compiler flags. Contraction
 * (replacing a * b + c with a single-rounding fma) is turned off in the source for every compiler family:
 *   Clang: #pragma STDC FP_CONTRACT OFF (standard C99 pragma, honoured by Clang on every target including arm64).
 *   GCC: ignores #pragma STDC FP_CONTRACT, so `#pragma GCC optimize("fp-contract=off")` turns contraction off
 *     for every function defined after this header, even if the command line omitted -ffp-contract=off.
 *   MSVC (cl.exe): `#pragma float_control(precise, on)` and `#pragma fp_contract(off)` turn precise semantics on
 *     and contraction off even if the command line omitted /fp:precise.
 * Fast-math (-ffast-math on GCC/Clang, /fp:fast on MSVC) breaks NaN and signed-zero rules and is rejected
 * at compile time via __FAST_MATH__ (GCC/Clang) and _M_FP_FAST (MSVC). */
#if defined(__FAST_MATH__) || defined(_M_FP_FAST)
#error "tp_aot.h requires precise IEEE 754 floating-point semantics: do not compile with -ffast-math or /fp:fast"
#endif
#if defined(__clang__)
#pragma STDC FP_CONTRACT OFF
#elif defined(__GNUC__)
#pragma STDC FP_CONTRACT OFF
#pragma GCC optimize("fp-contract=off")
#elif defined(_MSC_VER)
#pragma float_control(precise, on)
#pragma fp_contract(off)
#else
#pragma STDC FP_CONTRACT OFF
#endif

#if defined(__GNUC__) || defined(__clang__)
#define TPA_UNUSED __attribute__((unused))
#else
#define TPA_UNUSED
#endif

/* tp_runtime.h line 110: thread-local storage */
#if defined(_MSC_VER)
#define TPA_THREAD_LOCAL __declspec(thread)
#else
#define TPA_THREAD_LOCAL __thread
#endif

/* TPA_THREADS: more than one thread may run this code, so list counts are atomic. This block is the one
 * place that decides it (leader, 2026-11-16: the condition is "threads may exist", not "is a library"):
 * a library build always (any thread of the host may call it); an executable build is single-threaded
 * today and defines TPA_THREADS itself from the day it can start threads (a parallel loop). */
#if defined(TPA_LIBRARY) && !defined(TPA_THREADS)
#define TPA_THREADS 1
#endif

/* With TPA_THREADS the list count is atomic; without it the plain 64-bit count stays.
 *   GCC, Clang (also clang-cl): C11 <stdatomic.h>, a relaxed add to retain and an acquire-release subtract
 *     to release (the release that sees 1 frees, exactly once however many threads release together).
 *   cl.exe: _InterlockedIncrement64 and _InterlockedDecrement64 on the same 64-bit field (each is a full
 *     barrier, so it is at least acquire-release). tp_runtime.h lines 40 to 82 take <intrin.h> and the
 *     _InterlockedExchangeAdd64 intrinsics for its own atomic helpers in the same way.
 * TPA_EXPORT marks an exported symbol; the library is built with -fvisibility=hidden (GCC, Clang), so
 * nothing else is visible. */
#if defined(TPA_THREADS)
#if !defined(_WIN32)
#include <sched.h>             /* sched_yield, for a thread that waits on a list's lock (tp_aot_int.h) */
#endif
#if defined(_MSC_VER) && !defined(__clang__)
#include <intrin.h>
typedef int64_t tpa_rc_t;
#define TPA_RC_INC(l) ((void)_InterlockedIncrement64((volatile __int64 *)&(l)->rc))
#define TPA_RC_DEC_IS_ZERO(l) (_InterlockedDecrement64((volatile __int64 *)&(l)->rc) == 0)
#else
#include <stdatomic.h>
typedef _Atomic int64_t tpa_rc_t;
#define TPA_RC_INC(l) ((void)atomic_fetch_add_explicit(&(l)->rc, 1, memory_order_relaxed))
#define TPA_RC_DEC_IS_ZERO(l) (atomic_fetch_sub_explicit(&(l)->rc, 1, memory_order_acq_rel) == 1)
#endif
#else
typedef int64_t tpa_rc_t;
#define TPA_RC_INC(l) ((l)->rc += 1)
#define TPA_RC_DEC_IS_ZERO(l) (--(l)->rc == 0)
#endif

#if defined(TPA_LIBRARY)
#if defined(_WIN32) || defined(__CYGWIN__)
#define TPA_EXPORT __declspec(dllexport)
#else
#define TPA_EXPORT __attribute__((visibility("default")))
#endif
#endif

/* ------------------------------------------------------------------------------------------
 * The current exception: a class of the built-ins this runtime raises and a message.
 * ------------------------------------------------------------------------------------------ */
#define TPA_ZeroDivisionError 1
#define TPA_ValueError 2
#define TPA_OverflowError 3
#define TPA_IndexError 4
#define TPA_MemoryError 5
#define TPA_TypeError 6          /* library build: a NULL list handle (an ABI-level check, no counterpart in the source language) */

typedef struct {
    int kind;
    char msg[1024];          /* 1024: the invalid-literal message of int() keeps up to 200 characters of a repr, 4 bytes each */
} tpa_exc_t;

static TPA_THREAD_LOCAL tpa_exc_t tpa_exc;

static inline const char *tpa_exc_name(int kind)
{
    switch (kind) {
    case TPA_ZeroDivisionError: return "ZeroDivisionError";
    case TPA_ValueError: return "ValueError";
    case TPA_OverflowError: return "OverflowError";
    case TPA_IndexError: return "IndexError";
    case TPA_MemoryError: return "MemoryError";
    case TPA_TypeError: return "TypeError";
    }
    return "SystemError";
}

/* Records the exception and returns -1, so `return tpa_raise(...)` and `if (...) return -1;` read alike. */
static inline int tpa_raise(int kind, const char *msg)
{
    size_t n = strlen(msg);
    if (n >= sizeof tpa_exc.msg) {
        n = sizeof tpa_exc.msg - 1;
    }
    tpa_exc.kind = kind;
    memcpy(tpa_exc.msg, msg, n);
    tpa_exc.msg[n] = '\0';
    return -1;
}

/* An uncaught exception at the entry (owner decision D4: no traceback): flush standard output, write
 * `ClassName: message` and a newline to standard error. Returns the process exit status, 1. */
static inline int tpa_report_uncaught(void)
{
    fflush(stdout);
    if (tpa_exc.msg[0] != '\0') {
        fprintf(stderr, "%s: %s\n", tpa_exc_name(tpa_exc.kind), tpa_exc.msg);
    }
    else {
        fprintf(stderr, "%s\n", tpa_exc_name(tpa_exc.kind));
    }
    fflush(stderr);
    return 1;
}

#if defined(TPA_LIBRARY)
/* The exported wrappers' share of the thread's record (`tpa_exc` is thread-local, so no thread sees another's).
 * kind 0 is "no failure": a successful exported call clears the record, a failing one leaves the class and
 * message `tpa_raise` wrote. The strings `last_error` hands out are this thread's own storage (the class name
 * is a literal), valid until that thread's next call that records or clears. */
static inline void tpa_lib_clear_error(void)
{
    tpa_exc.kind = 0;
    tpa_exc.msg[0] = '\0';
}

/* A NULL list handle passed to an exported function: an ABI-level check with no counterpart in the source
 * language, reported as a TypeError that names the argument. */
static inline int tpa_lib_null_handle(const char *arg)
{
    char m[256];
    snprintf(m, sizeof m, "argument '%s' is a null list handle", arg);
    return tpa_raise(TPA_TypeError, m);
}

/* 0 and both strings set when this thread's last exported call failed, 1 when it succeeded or none was made. */
static inline int tpa_lib_last_error(const char **class_name, const char **message)
{
    if (tpa_exc.kind == 0) {
        return 1;
    }
    if (class_name != NULL) {
        *class_name = tpa_exc_name(tpa_exc.kind);
    }
    if (message != NULL) {
        *message = tpa_exc.msg;
    }
    return 0;
}
#endif

/* CPython 3.14 and 3.15 (tp_runtime.h lines 167 to 171, 179): every division by zero says this. */
#define TPA_MSG_DIVISION_ZERO "division by zero"

/* ------------------------------------------------------------------------------------------
 * Integers (int64_t). A proven operation is plain machine arithmetic through unsigned wrap-around
 * (tp_runtime.h lines 322 to 326: tp_padd_i64 and friends); the compiler emits one only where the
 * verifier proved that the result stays inside i64.
 * ------------------------------------------------------------------------------------------ */
#if defined(TP_CHECK_PROVEN) && (defined(__GNUC__) || defined(__clang__))
/* tp_runtime.h under -DTP_CHECK_PROVEN: the sanitizer job builds with it, and a proven operation that
 * does overflow (a wrong proof) stops the program instead of wrapping silently. */
static inline void tpa__proof_failed(const char *op)
{
    fflush(stdout);
    fprintf(stderr, "SystemError: a proven int operation (%s) overflowed 64 bits\n", op);
    abort();
}
static inline int64_t tpa_padd_i64(int64_t a, int64_t b) { int64_t r; if (__builtin_add_overflow(a, b, &r)) tpa__proof_failed("+"); return r; }
static inline int64_t tpa_psub_i64(int64_t a, int64_t b) { int64_t r; if (__builtin_sub_overflow(a, b, &r)) tpa__proof_failed("-"); return r; }
static inline int64_t tpa_pmul_i64(int64_t a, int64_t b) { int64_t r; if (__builtin_mul_overflow(a, b, &r)) tpa__proof_failed("*"); return r; }
static inline int64_t tpa_pneg_i64(int64_t a) { int64_t r; if (__builtin_sub_overflow((int64_t)0, a, &r)) tpa__proof_failed("-x"); return r; }
#else
static inline int64_t tpa_padd_i64(int64_t a, int64_t b) { return (int64_t)((uint64_t)a + (uint64_t)b); }
static inline int64_t tpa_psub_i64(int64_t a, int64_t b) { return (int64_t)((uint64_t)a - (uint64_t)b); }
static inline int64_t tpa_pmul_i64(int64_t a, int64_t b) { return (int64_t)((uint64_t)a * (uint64_t)b); }
static inline int64_t tpa_pneg_i64(int64_t a) { return (int64_t)(0 - (uint64_t)a); }
#endif

/* CPython floor division (tp_runtime.h lines 350 to 370, tp_floordiv_i64). The compiler emits it only
 * for a divisor that is a constant other than 0 and -1, or for a proven operation (divisor a positive
 * power of two), so INT64_MIN // -1, whose result 2**63 does not fit, never reaches it; a divisor of 0
 * raises. */
static inline int tpa_floordiv_i64(int64_t a, int64_t b, int64_t *out)
{
    int64_t q, r;
    if (b == 0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    if (b == -1) {
        *out = (int64_t)(0 - (uint64_t)a);   /* only reached for a != INT64_MIN, see above */
        return 0;
    }
    q = a / b;
    r = a % b;
    if (r != 0 && ((r < 0) != (b < 0))) {
        q -= 1;
    }
    *out = q;
    return 0;
}

/* CPython modulo: the result has the sign of the divisor (tp_runtime.h lines 373 to 389, tp_mod_i64).
 * The result lies between 0 and the divisor, so it always fits; INT64_MIN % -1 is 0 and does not trap. */
static inline int tpa_mod_i64(int64_t a, int64_t b, int64_t *out)
{
    int64_t r;
    if (b == 0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    if (b == -1) {
        *out = 0;
        return 0;
    }
    r = a % b;
    if (r != 0 && ((r < 0) != (b < 0))) {
        r += b;
    }
    *out = r;
    return 0;
}

/* int / int (tp_runtime.h lines 391 to 405, tp_truediv_i64). The compiler emits it only when both
 * operands are constants inside plus or minus 2**53, where both convert exactly and the C division is
 * the correctly rounded quotient CPython returns. */
static inline int tpa_truediv_small_i64(int64_t a, int64_t b, double *out)
{
    if (b == 0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    *out = (double)a / (double)b;
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Floats (double): IEEE, no fast-math, no contraction (enforced above in source and by build flags).
 * ------------------------------------------------------------------------------------------ */

/* tp_runtime.h lines 413 to 421, tp_truediv_f64 */
static inline int tpa_truediv_f64(double a, double b, double *out)
{
    if (b == 0.0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    *out = a / b;
    return 0;
}

/* floatobject.c _float_div_mod (tp_runtime.h lines 424 to 450, tp__float_div_mod) */
static inline void tpa__float_div_mod(double vx, double wx, double *floordiv, double *mod)
{
    double div;
    *mod = fmod(vx, wx);
    div = (vx - *mod) / wx;
    if (*mod != 0.0) {
        if ((wx < 0) != (*mod < 0)) {
            *mod += wx;
            div -= 1.0;
        }
    }
    else {
        *mod = copysign(0.0, wx);
    }
    if (div != 0.0) {
        *floordiv = floor(div);
        if (div - *floordiv > 0.5) {
            *floordiv += 1.0;
        }
    }
    else {
        *floordiv = copysign(0.0, vx / wx);
    }
}

/* tp_runtime.h lines 453 to 463, tp_floordiv_f64 */
static inline int tpa_floordiv_f64(double a, double b, double *out)
{
    double mod, floordiv;
    if (b == 0.0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    tpa__float_div_mod(a, b, &floordiv, &mod);
    *out = floordiv;
    return 0;
}

/* floatobject.c float_rem (tp_runtime.h lines 465 to 484, tp_mod_f64) */
static inline int tpa_mod_f64(double a, double b, double *out)
{
    double mod;
    if (b == 0.0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    mod = fmod(a, b);
    if (mod != 0.0) {
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
 * Comparisons of an int and a float are exact, as CPython's float_richcompare, never through
 * (double)int (tp_runtime.h lines 677 to 725). Operator codes: 0 <, 1 <=, 2 ==, 3 !=, 4 >, 5 >=.
 * ------------------------------------------------------------------------------------------ */
#define TPA_LT 0
#define TPA_LE 1
#define TPA_EQ 2
#define TPA_NE 3
#define TPA_GT 4
#define TPA_GE 5

static inline int tpa__apply_cmp(int c, int op)
{
    switch (op) {
    case TPA_LT: return c < 0;
    case TPA_LE: return c <= 0;
    case TPA_EQ: return c == 0;
    case TPA_NE: return c != 0;
    case TPA_GT: return c > 0;
    case TPA_GE: return c >= 0;
    }
    return 0;
}

/* three-way compare of an int64 with a non-NaN double: -1, 0, 1 (tp__cmp3_i64_f64) */
static inline int tpa__cmp3_i64_f64(int64_t a, double b)
{
    int64_t bi;
    double frac;
    if (b >= 9223372036854775808.0) return -1;  /* b >= 2**63 > any int64 (also +inf) */
    if (b < -9223372036854775808.0) return 1;   /* b < -2**63 <= any int64 (also -inf) */
    bi = (int64_t)b;                            /* truncation toward zero, b in range */
    if (a < bi) return -1;
    if (a > bi) return 1;
    frac = b - (double)bi;                      /* exact: bi is b with the fraction dropped */
    if (frac > 0.0) return -1;                  /* b = a + frac */
    if (frac < 0.0) return 1;
    return 0;
}

static inline int tpa_cmp_i64_f64(int64_t a, double b, int op)
{
    if (b != b) {
        return op == TPA_NE;
    }
    return tpa__apply_cmp(tpa__cmp3_i64_f64(a, b), op);
}

static inline int tpa_cmp_f64_i64(double a, int64_t b, int op)
{
    if (a != a) {
        return op == TPA_NE;
    }
    return tpa__apply_cmp(-tpa__cmp3_i64_f64(b, a), op);
}

/* ------------------------------------------------------------------------------------------
 * repr(float): the shortest decimal string that parses back to the same double, laid out as
 * CPython's float_repr (format_float_short with mode 'r'): exponent notation when the decimal
 * exponent of the first digit is below -4 or at least 16, else positional with `.0` when there is no
 * fractional part. nan, inf and -inf as CPython writes them.
 *
 * The digits: the smallest precision p in 1..17 for which the correctly rounded p-digit decimal
 * (`%.*e`) parses back to x. Where x is a power of two the interval that rounds to x is asymmetric
 * (half as wide below as above), so the nearest p-digit decimal can fall outside it while the next one
 * up is inside; CPython's David Gay conversion finds that one, so the two neighbours of the nearest
 * decimal are tried too. (A scripted port of exactly this procedure matched repr() on 600000 random
 * doubles and on every power-of-two neighbourhood; the corpus test compares this C with CPython.)
 * ------------------------------------------------------------------------------------------ */

/* p digits of x > 0 into digs (no point), the decimal exponent of the first digit in *e */
static inline void tpa__digits(double x, int p, char *digs, int *e)
{
    char buf[48];
    const char *s = buf;
    int n = 0;
    snprintf(buf, sizeof buf, "%.*e", p - 1, x);
    digs[n++] = *s++;
    if (*s == '.') {
        s++;
        while (*s >= '0' && *s <= '9') {
            digs[n++] = *s++;
        }
    }
    *e = atoi(s + 1);                /* s is at the 'e'; atoi reads the sign and the digits */
    digs[n] = '\0';
}

static inline int tpa__roundtrips(const char *digs, int p, int e, double x)
{
    char buf[64];
    int n = 0, i;
    buf[n++] = digs[0];
    if (p > 1) {
        buf[n++] = '.';
        for (i = 1; i < p; i++) {
            buf[n++] = digs[i];
        }
    }
    snprintf(buf + n, sizeof buf - (size_t)n, "e%d", e);
    return strtod(buf, NULL) == x;
}

/* the p-digit decimal one unit in the last place above (d > 0) or below (d < 0) digs; 0 if there is none */
static inline int tpa__bump(char *digs, int p, int *e, int d)
{
    int i;
    if (d > 0) {
        for (i = p - 1; i >= 0; i--) {
            if (digs[i] == '9') {
                digs[i] = '0';
            }
            else {
                digs[i]++;
                return 1;
            }
        }
        digs[0] = '1';               /* 99..9 + 1 = 100..0, one exponent up */
        (*e)++;
        return 1;
    }
    for (i = p - 1; i >= 0; i--) {
        if (digs[i] == '0') {
            digs[i] = '9';
        }
        else {
            digs[i]--;
            if (i == 0 && digs[0] == '0') {
                int j;               /* 100..0 - 1 = 99..9 with p-1 digits: p digits are 99..90, one exponent down */
                if (p == 1) {
                    return 0;
                }
                for (j = 0; j < p - 1; j++) {
                    digs[j] = '9';
                }
                digs[p - 1] = '0';
                (*e)--;
            }
            return 1;
        }
    }
    return 0;
}

/* writes repr(x) into buf (at least 40 bytes) and returns its length */
static inline int tpa_repr_f64(double x, char *buf)
{
    char digs[24], alt[24];
    int p, e = 0, n = 0, i, ndig, found = 0, ae, d;
    if (x != x) {
        memcpy(buf, "nan", 4);
        return 3;
    }
    if (x == HUGE_VAL) {
        memcpy(buf, "inf", 4);
        return 3;
    }
    if (x == -HUGE_VAL) {
        memcpy(buf, "-inf", 5);
        return 4;
    }
    if (signbit(x)) {
        buf[n++] = '-';
        x = -x;
    }
    if (x == 0.0) {
        memcpy(buf + n, "0.0", 4);
        return n + 3;
    }
    for (p = 1; p <= 17 && !found; p++) {
        tpa__digits(x, p, digs, &e);
        if (tpa__roundtrips(digs, p, e, x)) {
            found = 1;
            break;
        }
        for (d = 1; d >= -1; d -= 2) {
            int e2 = e;
            memcpy(alt, digs, (size_t)p + 1);
            if (tpa__bump(alt, p, &e2, d) && tpa__roundtrips(alt, p, e2, x)) {
                memcpy(digs, alt, (size_t)p + 1);
                e = e2;
                found = 1;
                break;
            }
        }
        if (found) {
            break;
        }
    }
    if (!found) {
        tpa__digits(x, 17, digs, &e);   /* cannot happen: 17 digits always round-trip */
        p = 17;
    }
    ndig = p;
    while (ndig > 1 && digs[ndig - 1] == '0') {
        ndig--;
    }
    if (e < -4 || e >= 16) {
        buf[n++] = digs[0];
        if (ndig > 1) {
            buf[n++] = '.';
            for (i = 1; i < ndig; i++) {
                buf[n++] = digs[i];
            }
        }
        buf[n++] = 'e';
        buf[n++] = e < 0 ? '-' : '+';
        ae = e < 0 ? -e : e;
        if (ae >= 100) {
            buf[n++] = (char)('0' + ae / 100);
        }
        buf[n++] = (char)('0' + (ae / 10) % 10);
        buf[n++] = (char)('0' + ae % 10);
    }
    else if (e >= 0) {
        for (i = 0; i <= e; i++) {
            buf[n++] = i < ndig ? digs[i] : '0';
        }
        buf[n++] = '.';
        if (ndig > e + 1) {
            for (i = e + 1; i < ndig; i++) {
                buf[n++] = digs[i];
            }
        }
        else {
            buf[n++] = '0';
        }
    }
    else {
        buf[n++] = '0';
        buf[n++] = '.';
        for (i = 0; i < -e - 1; i++) {
            buf[n++] = '0';
        }
        for (i = 0; i < ndig; i++) {
            buf[n++] = digs[i];
        }
    }
    buf[n] = '\0';
    return n;
}

/* ------------------------------------------------------------------------------------------
 * math (mathmodule.c math_1 / math_2 / is_error rules; tp_runtime.h lines 727 to 925)
 * ------------------------------------------------------------------------------------------ */
#define TPA_MATH_MSG_NONNEG "expected a nonnegative input, got "
#define TPA_MATH_MSG_FINITE "expected a finite input, got "
#define TPA_MATH_MSG_POSITIVE "expected a positive input, got "

/* 3.14 and later: the domain error of a one-argument function names the argument as repr(x) */
static inline void tpa__math_domain_error(double x, const char *prefix)
{
    if (prefix) {
        char r[48], m[160];
        tpa_repr_f64(x, r);
        snprintf(m, sizeof m, "%s%s", prefix, r);
        tpa_raise(TPA_ValueError, m);
        return;
    }
    tpa_raise(TPA_ValueError, "math domain error");
}

/* mathmodule.c is_error: 1 if an exception was set, 0 if the errno is to be ignored. An errno that is
 * neither EDOM nor ERANGE does not occur with a C library's math functions; CPython would raise
 * ValueError(errno, strerror) and so does this. */
static inline int tpa__math_is_error(double x)
{
    int result = 1;
    if (errno == EDOM) {
        tpa_raise(TPA_ValueError, "math domain error");
    }
    else if (errno == ERANGE) {
        if (fabs(x) < 1.5) {
            result = 0;
        }
        else {
            tpa_raise(TPA_OverflowError, "math range error");
        }
    }
    else {
        char m[160];
        snprintf(m, sizeof m, "(%d, '%s')", errno, strerror(errno));
        tpa_raise(TPA_ValueError, m);
    }
    return result;
}

/* math_1: x is the argument, r the libm result computed with errno cleared just before */
static inline int tpa__math_1_finish(double x, double r, int can_overflow, const char *prefix, double *out)
{
    if (isnan(r) && !isnan(x)) {
        tpa__math_domain_error(x, prefix);
        return -1;
    }
    if (isinf(r) && isfinite(x)) {
        if (can_overflow) {
            tpa_raise(TPA_OverflowError, "math range error");
        }
        else {
            tpa__math_domain_error(x, prefix);
        }
        return -1;
    }
    if (isfinite(r) && errno && tpa__math_is_error(r)) {
        return -1;
    }
    *out = r;
    return 0;
}

/* mathmodule.c m_log */
static inline double tpa__m_log(double x)
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

#define TPA__DEF_MATH1(NAME, CALL, CAN_OVERFLOW, MSG)                                         \
    static inline int tpa_math_##NAME(double x, double *out)                                   \
    {                                                                                          \
        double r;                                                                              \
        errno = 0;                                                                             \
        r = CALL;                                                                              \
        return tpa__math_1_finish(x, r, CAN_OVERFLOW, MSG, out);                               \
    }
TPA__DEF_MATH1(sqrt, sqrt(x), 0, TPA_MATH_MSG_NONNEG)
TPA__DEF_MATH1(exp, exp(x), 1, NULL)
TPA__DEF_MATH1(log, tpa__m_log(x), 0, TPA_MATH_MSG_POSITIVE)
TPA__DEF_MATH1(sin, sin(x), 0, TPA_MATH_MSG_FINITE)
TPA__DEF_MATH1(cos, cos(x), 0, TPA_MATH_MSG_FINITE)
TPA__DEF_MATH1(tan, tan(x), 0, TPA_MATH_MSG_FINITE)
TPA__DEF_MATH1(fabs, fabs(x), 0, NULL)
#undef TPA__DEF_MATH1

/* mathmodule.c m_atan2: the special values are defined here, the rest is libm */
static inline double tpa__m_atan2(double y, double x)
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
static inline int tpa_math_atan2(double y, double x, double *out)
{
    double r;
    errno = 0;
    r = tpa__m_atan2(y, x);
    if (isnan(r)) {
        errno = (!isnan(x) && !isnan(y)) ? EDOM : 0;
    }
    else if (isinf(r)) {
        errno = (isfinite(x) && isfinite(y)) ? ERANGE : 0;
    }
    if (errno && tpa__math_is_error(r)) {
        return -1;
    }
    *out = r;
    return 0;
}

#define TPA__DBL_MIN 2.2250738585072014e-308 /* DBL_MIN, without <float.h> */

/* mathmodule.c vector_norm: the correctly rounded hypot (tp_runtime.h lines 862 to 925) */
typedef struct { double hi; double lo; } tpa__dl;

static inline tpa__dl tpa__dl_fast_sum(double a, double b)
{
    double x = a + b;
    double y = (a - x) + b;
    tpa__dl r;
    r.hi = x;
    r.lo = y;
    return r;
}

static inline tpa__dl tpa__dl_mul(double x, double y)
{
    double z = x * y;
    double zz = fma(x, y, -z);
    tpa__dl r;
    r.hi = z;
    r.lo = zz;
    return r;
}

static inline double tpa__vector_norm(int n, double *vec, double max, int found_nan)
{
    double x, h, scale, csum = 1.0, frac1 = 0.0, frac2 = 0.0;
    tpa__dl pr, sm;
    int max_e, i;

    if (isinf(max)) return max;
    if (found_nan) return NAN;
    if (max == 0.0 || n <= 1) return max;
    frexp(max, &max_e);
    if (max_e < -1023) {
        for (i = 0; i < n; i++) {
            vec[i] /= TPA__DBL_MIN;
        }
        return TPA__DBL_MIN * tpa__vector_norm(n, vec, max / TPA__DBL_MIN, found_nan);
    }
    scale = ldexp(1.0, -max_e);
    for (i = 0; i < n; i++) {
        x = vec[i];
        x *= scale;
        pr = tpa__dl_mul(x, x);
        sm = tpa__dl_fast_sum(csum, pr.hi);
        csum = sm.hi;
        frac1 += pr.lo;
        frac2 += sm.lo;
    }
    h = sqrt(csum - 1.0 + (frac1 + frac2));
    pr = tpa__dl_mul(-h, h);
    sm = tpa__dl_fast_sum(csum, pr.hi);
    csum = sm.hi;
    frac1 += pr.lo;
    frac2 += sm.lo;
    x = csum - 1.0 + (frac1 + frac2);
    h += x / (2.0 * h);
    return h / scale;
}

static inline int tpa_math_hypot(double x, double y, double *out)
{
    double vec[2];
    double max = 0.0;
    int found_nan;
    vec[0] = fabs(x);
    vec[1] = fabs(y);
    found_nan = isnan(vec[0]) | isnan(vec[1]);
    if (vec[0] > max) max = vec[0];
    if (vec[1] > max) max = vec[1];
    *out = tpa__vector_norm(2, vec, max, found_nan);
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Native lists (design note 4.1, 6.2, 6.3; agent proposal, not owner-confirmed). A `list[float]` /
 * `list[int]` is a counted heap object: the count, the length, the elements. It never changes length,
 * so an element access needs one bounds test and nothing else. The count is NON-ATOMIC: this is the
 * single-threaded tier (design 6.3 and 7; a list shared by threads needs atomic counts): a library build
 * (which defines TPA_THREADS) has atomic counts, an executable build keeps this plain one.
 * A fresh list has count 1. `retain` adds an owner, `release` removes one and frees at 0 (a NULL
 * list is a no-op, which is what lets a function release every local at its one exit). A function
 * parameter is borrowed: the caller keeps it alive for the call, and two parameters may be the same
 * list, as two names of one list are in the source language.
 * ------------------------------------------------------------------------------------------ */
typedef struct tpa_f64_list { tpa_rc_t rc; int64_t len; double d[]; } tpa_f64_list;
typedef struct tpa_i64_list { tpa_rc_t rc; int64_t len; int64_t d[]; } tpa_i64_list;

/* tp_runtime.h lines 1583 to 1599, tp__local_alloc: a negative length is an empty list (CPython's
 * `[x] * -1 == []`), and a size that overflows is a MemoryError, never a wrapped small allocation. */
static inline void *tpa__list_alloc(int64_t n, size_t header, size_t elem, int64_t *len)
{
    void *p;
    if (n < 0) {
        n = 0;
    }
    if ((uint64_t)n > (uint64_t)(SIZE_MAX - header) / elem) {
        tpa_raise(TPA_MemoryError, "");
        return NULL;
    }
    p = malloc(header + (size_t)n * elem);
    if (p == NULL) {
        tpa_raise(TPA_MemoryError, "");
        return NULL;
    }
    *len = n;
    return p;
}

/* tp_runtime.h lines 1093 to 1108, tp__array_slot: a negative index counts from the end; out of range
 * raises IndexError with CPython's text, which differs for a store (line 1107). */
static inline int tpa_slot(int64_t len, int64_t i, int store, int64_t *slot)
{
    if (i < 0) {
        if (i < -len) {
            goto out_of_range;
        }
        i += len;
    }
    else if (i >= len) {
        goto out_of_range;
    }
    *slot = i;
    return 0;
out_of_range:
    return tpa_raise(TPA_IndexError, store ? "list assignment index out of range" : "list index out of range");
}

#define TPA__DEF_LIST(NAME, CT)                                                                   \
    static inline int tpa_##NAME##_alloc(int64_t n, tpa_##NAME##_list **out)                      \
    {                                                                                             \
        int64_t len = 0;                                                                          \
        tpa_##NAME##_list *l = (tpa_##NAME##_list *)tpa__list_alloc(n, sizeof(tpa_##NAME##_list), \
                                                                    sizeof(CT), &len);            \
        if (l == NULL) {                                                                          \
            return -1;                                                                            \
        }                                                                                         \
        l->rc = 1;                                                                                \
        l->len = len;                                                                             \
        *out = l;                                                                                 \
        return 0;                                                                                 \
    }                                                                                             \
    static inline int tpa_##NAME##_fill(int64_t n, CT v, tpa_##NAME##_list **out)                 \
    {                                                                                             \
        int64_t i;                                                                                \
        if (tpa_##NAME##_alloc(n, out)) {                                                         \
            return -1;                                                                            \
        }                                                                                         \
        for (i = 0; i < (*out)->len; i++) {                                                       \
            (*out)->d[i] = v;                                                                     \
        }                                                                                         \
        return 0;                                                                                 \
    }                                                                                             \
    static inline int tpa_##NAME##_copy(const tpa_##NAME##_list *src, tpa_##NAME##_list **out)    \
    {                                                                                             \
        if (tpa_##NAME##_alloc(src->len, out)) {                                                  \
            return -1;                                                                            \
        }                                                                                         \
        if (src->len > 0) {                                                                       \
            memcpy((*out)->d, src->d, (size_t)src->len * sizeof(CT));                             \
        }                                                                                         \
        return 0;                                                                                 \
    }                                                                                             \
    static inline void tpa_##NAME##_retain(tpa_##NAME##_list *l)                                  \
    {                                                                                             \
        if (l != NULL) {                                                                          \
            TPA_RC_INC(l);                                                                        \
        }                                                                                         \
    }                                                                                             \
    static inline void tpa_##NAME##_release(tpa_##NAME##_list *l)                                 \
    {                                                                                             \
        if (l != NULL && TPA_RC_DEC_IS_ZERO(l)) {                                                 \
            free(l);                                                                              \
        }                                                                                         \
    }
TPA__DEF_LIST(f64, double)
TPA__DEF_LIST(i64, int64_t)
#undef TPA__DEF_LIST

/* `list(range(n))`: 0 .. n-1 (an empty list for n <= 0) */
static inline int tpa_i64_iota(int64_t n, tpa_i64_list **out)
{
    int64_t i;
    if (tpa_i64_alloc(n, out)) {
        return -1;
    }
    for (i = 0; i < (*out)->len; i++) {
        (*out)->d[i] = i;
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * The program arguments: `len(sys.argv)` and `int(sys.argv[i])` are compiled (a string has no other use yet;
 * `int()` of one needs the big-integer runtime, tp_aot_int.h, which reads `tpa_argv`; on Windows the generated
 * `wmain` sets `tpa_wargv` too, so the arguments keep every character).
 * ------------------------------------------------------------------------------------------ */
#if !defined(TPA_LIBRARY)       /* a library has no `main` and no program arguments: no static mutable data */
static int tpa_argc = 0;
static char **tpa_argv = NULL;
#if defined(_WIN32)
static wchar_t **tpa_wargv TPA_UNUSED = NULL;
#endif
#endif

/* ------------------------------------------------------------------------------------------
 * print: positional arguments only, separated by one space, ended by a newline, written to standard
 * output (buffered; the entry flushes it at exit and before anything is written to standard error).
 * The caller writes the separator before every argument but the first.
 * ------------------------------------------------------------------------------------------ */
static inline void tpa_print_i64(int64_t v)
{
    char buf[32];
    int n = snprintf(buf, sizeof buf, "%lld", (long long)v);
    fwrite(buf, 1, (size_t)n, stdout);
}

static inline void tpa_print_f64(double v)
{
    char buf[48];
    int n = tpa_repr_f64(v, buf);
    fwrite(buf, 1, (size_t)n, stdout);
}

static inline void tpa_print_bool(int v)
{
    if (v) {
        fwrite("True", 1, 4, stdout);
    }
    else {
        fwrite("False", 1, 5, stdout);
    }
}

/* a string literal: its UTF-8 bytes */
static inline void tpa_print_str(const char *s, size_t n)
{
    fwrite(s, 1, n, stdout);
}

/* format(x, '.<prec>f') of a float: nan and the infinities as CPython writes them (never `-nan`), the
 * rest through printf("%.*f"), whose result is the correctly rounded decimal expansion on glibc, musl, the
 * Apple C library and UCRT (the same digits as CPython's own conversion; the dependence on the C
 * library's exact rounding is issue #450, which the differential tests exercise on every target). */
static inline void tpa_print_f64_fixed(double v, int prec)
{
    char buf[400];
    int n;
    if (v != v) {
        fwrite("nan", 1, 3, stdout);
        return;
    }
    if (v == HUGE_VAL || v == -HUGE_VAL) {
        if (v > 0) {
            fwrite("inf", 1, 3, stdout);
        }
        else {
            fwrite("-inf", 1, 4, stdout);
        }
        return;
    }
    n = snprintf(buf, sizeof buf, "%.*f", prec, v);
    fwrite(buf, 1, (size_t)n, stdout);
}

static inline void tpa_print_sep(void) { fputc(' ', stdout); }
static inline void tpa_print_end(void) { fputc('\n', stdout); }

#endif
