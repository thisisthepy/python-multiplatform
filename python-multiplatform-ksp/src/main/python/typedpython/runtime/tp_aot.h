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
#include <time.h>
#if defined(_WIN32)
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#endif

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
#define TPA_RC_LOAD(l) ((int64_t)_InterlockedOr64((volatile __int64 *)&(l)->rc, 0))
#else
#include <stdatomic.h>
typedef _Atomic int64_t tpa_rc_t;
#define TPA_RC_INC(l) ((void)atomic_fetch_add_explicit(&(l)->rc, 1, memory_order_relaxed))
#define TPA_RC_DEC_IS_ZERO(l) (atomic_fetch_sub_explicit(&(l)->rc, 1, memory_order_acq_rel) == 1)
#define TPA_RC_LOAD(l) (atomic_load_explicit(&(l)->rc, memory_order_relaxed))
#endif
#else
typedef int64_t tpa_rc_t;
#define TPA_RC_INC(l) ((l)->rc += 1)
#define TPA_RC_DEC_IS_ZERO(l) (--(l)->rc == 0)
#define TPA_RC_LOAD(l) ((l)->rc)
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
#define TPA_TypeError 6          /* library build: a NULL list handle; tier S1: ord() of a string that is not one character (an ABI-level check, no counterpart in the source language) */
#define TPA_UnicodeEncodeError 7 /* tier S1: a lone surrogate written to standard output (tp_aot_str.h) */

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
    case TPA_UnicodeEncodeError: return "UnicodeEncodeError";
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
 * Exact binary64 decimal formatting (issue #450): no dependence on the C library's snprintf or
 * strtod rounding. Both `tpa_repr_f64` (shortest round-trip, Burger-Dybvig / Dragon4 with
 * CPython's round-half-to-even tie-breaking) and `tpa_format_f64_fixed` (exact fixed-point
 * rounding `format(v, '.<prec>f')`) operate directly on IEEE-754 bits using fixed-capacity
 * 32-bit little-endian unsigned big integers (pure C99, uint64_t intermediates, no 128-bit integer).
 *
 * Limb bound: in `tpa_repr_f64`, |k + 1| <= 324, so 5^|k+1| <= 5^324 (< 753 bits) and the
 * scaled values stay within 780 bits (25 limbs). In `tpa_format_f64_fixed`, exact integer values
 * m * 2^e2 fit in 1024 bits (32 limbs), and for e2 < 0 with prec <= 20, m * 5^prec fits in 100
 * bits (4 limbs). 36 limbs (1152 bits) give comfortable headroom for both.
 * ------------------------------------------------------------------------------------------ */
#define TPA__F64_MAX_LIMBS 36

typedef struct {
    int n;
    uint32_t d[TPA__F64_MAX_LIMBS];
} tpa__f64_bu;

static inline void tpa__f64_bu_trim(tpa__f64_bu *a)
{
    while (a->n > 0 && a->d[a->n - 1] == 0) {
        a->n--;
    }
}

static inline void tpa__f64_bu_from_u64(tpa__f64_bu *a, uint64_t v)
{
    if (v == 0) {
        a->n = 0;
        return;
    }
    a->d[0] = (uint32_t)v;
    if ((v >> 32) != 0) {
        a->d[1] = (uint32_t)(v >> 32);
        a->n = 2;
    }
    else {
        a->n = 1;
    }
}

static inline int tpa__f64_u64_bitlen(uint64_t v)
{
    int n = 0;
    while (v != 0) {
        n++;
        v >>= 1;
    }
    return n;
}

static inline int tpa__f64_bu_bitlen(const tpa__f64_bu *a)
{
    int bl;
    uint32_t w;
    if (a->n == 0) {
        return 0;
    }
    bl = (a->n - 1) * 32;
    w = a->d[a->n - 1];
    while (w != 0) {
        bl++;
        w >>= 1;
    }
    return bl;
}

static inline int tpa__f64_bu_cmp(const tpa__f64_bu *a, const tpa__f64_bu *b)
{
    int i;
    if (a->n != b->n) {
        return a->n < b->n ? -1 : 1;
    }
    for (i = a->n - 1; i >= 0; i--) {
        if (a->d[i] != b->d[i]) {
            return a->d[i] < b->d[i] ? -1 : 1;
        }
    }
    return 0;
}

static inline void tpa__f64_bu_add(tpa__f64_bu *dst, const tpa__f64_bu *a, const tpa__f64_bu *b)
{
    int n = a->n > b->n ? a->n : b->n;
    uint64_t carry = 0;
    int i;
    for (i = 0; i < n; i++) {
        uint64_t s = carry + (i < a->n ? (uint64_t)a->d[i] : 0) + (i < b->n ? (uint64_t)b->d[i] : 0);
        dst->d[i] = (uint32_t)s;
        carry = s >> 32;
    }
    if (carry != 0 && n < TPA__F64_MAX_LIMBS) {
        dst->d[n++] = (uint32_t)carry;
    }
    dst->n = n;
    tpa__f64_bu_trim(dst);
}

static inline void tpa__f64_bu_sub(tpa__f64_bu *a, const tpa__f64_bu *b)
{
    uint64_t borrow = 0;
    int i;
    for (i = 0; i < a->n; i++) {
        uint64_t ai = (uint64_t)a->d[i];
        uint64_t bi = (i < b->n ? (uint64_t)b->d[i] : 0) + borrow;
        if (ai < bi) {
            a->d[i] = (uint32_t)((ai + (UINT64_C(1) << 32)) - bi);
            borrow = 1;
        }
        else {
            a->d[i] = (uint32_t)(ai - bi);
            borrow = 0;
        }
    }
    tpa__f64_bu_trim(a);
}

static inline void tpa__f64_bu_mul_u32(tpa__f64_bu *a, uint32_t k)
{
    uint64_t carry = 0;
    int i;
    if (a->n == 0 || k == 0) {
        a->n = 0;
        return;
    }
    for (i = 0; i < a->n; i++) {
        uint64_t p = (uint64_t)a->d[i] * (uint64_t)k + carry;
        a->d[i] = (uint32_t)p;
        carry = p >> 32;
    }
    if (carry != 0 && a->n < TPA__F64_MAX_LIMBS) {
        a->d[a->n++] = (uint32_t)carry;
    }
}

static inline void tpa__f64_bu_mul_pow5(tpa__f64_bu *a, int n)
{
    static const uint32_t p5[14] = {
        1U, 5U, 25U, 125U, 625U, 3125U, 15625U, 78125U,
        390625U, 1953125U, 9765625U, 48828125U, 244140625U, 1220703125U
    };
    while (n >= 13) {
        tpa__f64_bu_mul_u32(a, p5[13]);
        n -= 13;
    }
    if (n > 0) {
        tpa__f64_bu_mul_u32(a, p5[n]);
    }
}

static inline void tpa__f64_bu_shl(tpa__f64_bu *a, int shift)
{
    int ws, bs, i;
    if (a->n == 0 || shift <= 0) {
        return;
    }
    ws = shift >> 5;
    bs = shift & 31;
    if (bs == 0) {
        for (i = a->n - 1; i >= 0; i--) {
            if (i + ws < TPA__F64_MAX_LIMBS) {
                a->d[i + ws] = a->d[i];
            }
        }
        for (i = 0; i < ws && i < TPA__F64_MAX_LIMBS; i++) {
            a->d[i] = 0;
        }
        a->n = (a->n + ws <= TPA__F64_MAX_LIMBS) ? a->n + ws : TPA__F64_MAX_LIMBS;
    }
    else {
        uint32_t top = a->d[a->n - 1] >> (32 - bs);
        if (top != 0 && a->n + ws < TPA__F64_MAX_LIMBS) {
            a->d[a->n + ws] = top;
        }
        for (i = a->n - 1; i >= 1; i--) {
            if (i + ws < TPA__F64_MAX_LIMBS) {
                a->d[i + ws] = (a->d[i] << bs) | (a->d[i - 1] >> (32 - bs));
            }
        }
        if (ws < TPA__F64_MAX_LIMBS) {
            a->d[ws] = a->d[0] << bs;
        }
        for (i = 0; i < ws && i < TPA__F64_MAX_LIMBS; i++) {
            a->d[i] = 0;
        }
        a->n += ws + (top != 0 ? 1 : 0);
        if (a->n > TPA__F64_MAX_LIMBS) {
            a->n = TPA__F64_MAX_LIMBS;
        }
    }
    tpa__f64_bu_trim(a);
}

/* round_half_to_even(a / 2^shift) for shift > 0 */
static inline void tpa__f64_bu_shr_round_even(tpa__f64_bu *a, int shift)
{
    int bl = tpa__f64_bu_bitlen(a);
    int hw, hb, half_set, sticky = 0, ws, bs, new_n, i;
    uint32_t mask;
    if (bl < shift) {
        a->n = 0;
        return;
    }
    hw = (shift - 1) >> 5;
    hb = (shift - 1) & 31;
    mask = (UINT32_C(1) << hb) - 1U;
    if (bl == shift) {
        sticky = (a->d[hw] & mask) != 0;
        for (i = 0; i < hw && !sticky; i++) {
            if (a->d[i] != 0) {
                sticky = 1;
            }
        }
        if (sticky) {
            a->n = 1;
            a->d[0] = 1U;
        }
        else {
            a->n = 0;
        }
        return;
    }
    half_set = ((a->d[hw] >> hb) & 1U) != 0;
    if (half_set) {
        sticky = (a->d[hw] & mask) != 0;
        for (i = 0; i < hw && !sticky; i++) {
            if (a->d[i] != 0) {
                sticky = 1;
            }
        }
    }
    ws = shift >> 5;
    bs = shift & 31;
    new_n = a->n - ws;
    if (bs == 0) {
        for (i = 0; i < new_n; i++) {
            a->d[i] = a->d[i + ws];
        }
    }
    else {
        for (i = 0; i < new_n - 1; i++) {
            a->d[i] = (a->d[i + ws] >> bs) | (a->d[i + ws + 1] << (32 - bs));
        }
        a->d[new_n - 1] = a->d[a->n - 1] >> bs;
    }
    a->n = new_n;
    tpa__f64_bu_trim(a);
    if (half_set && (sticky || (a->n > 0 && (a->d[0] & 1U) != 0))) {
        uint64_t carry = 1;
        for (i = 0; i < a->n && carry != 0; i++) {
            uint64_t s = (uint64_t)a->d[i] + carry;
            a->d[i] = (uint32_t)s;
            carry = s >> 32;
        }
        if (carry != 0 && a->n < TPA__F64_MAX_LIMBS) {
            a->d[a->n++] = (uint32_t)carry;
        }
    }
}

static inline uint32_t tpa__f64_bu_divmod_u32(tpa__f64_bu *a, uint32_t k)
{
    uint64_t rem = 0;
    int i;
    for (i = a->n - 1; i >= 0; i--) {
        uint64_t cur = (rem << 32) | (uint64_t)a->d[i];
        a->d[i] = (uint32_t)(cur / k);
        rem = cur % k;
    }
    tpa__f64_bu_trim(a);
    return (uint32_t)rem;
}

static inline int tpa__f64_bu_to_dec(tpa__f64_bu *a, char *digs)
{
    uint32_t chunks[40];
    int nchunks = 0, n = 0, i, j;
    if (a->n == 0) {
        digs[0] = '0';
        return 1;
    }
    while (a->n > 0) {
        chunks[nchunks++] = tpa__f64_bu_divmod_u32(a, 1000000000U);
    }
    {
        uint32_t top = chunks[nchunks - 1];
        char tmp[10];
        int m = 0;
        while (top > 0) {
            tmp[m++] = (char)('0' + (top % 10U));
            top /= 10U;
        }
        for (j = m - 1; j >= 0; j--) {
            digs[n++] = tmp[j];
        }
    }
    for (i = nchunks - 2; i >= 0; i--) {
        uint32_t c = chunks[i];
        for (j = 8; j >= 0; j--) {
            digs[n + j] = (char)('0' + (c % 10U));
            c /= 10U;
        }
        n += 9;
    }
    return n;
}

/* ------------------------------------------------------------------------------------------
 * repr(float): the shortest decimal string that parses back to the same double, laid out as
 * CPython's float_repr (format_float_short with mode 'r'): exponent notation when the decimal
 * exponent of the first digit is below -4 or at least 16, else positional with `.0` when there is no
 * fractional part. nan, inf and -inf as CPython writes them.
 * ------------------------------------------------------------------------------------------ */

/* writes repr(x) into buf (at least 40 bytes) and returns its length */
static inline int tpa_repr_f64(double x, char *buf)
{
    uint64_t bits, mant, m, c;
    int sign, bexp, e2, even, q, m_minus, m_plus, log2_floor, e, p5, e2_rel;
    int32_t prod;
    tpa__f64_bu r, s, mm, mp, tmp;
    char digs[24];
    int p = 0, n = 0, i, ndig, ae;

    memcpy(&bits, &x, sizeof bits);
    sign = (int)(bits >> 63);
    bexp = (int)((bits >> 52) & 0x7FFU);
    mant = bits & ((UINT64_C(1) << 52) - 1U);

    if (bexp == 0x7FF) {
        if (mant != 0) {
            memcpy(buf, "nan", 4);
            return 3;
        }
        if (sign) {
            memcpy(buf, "-inf", 5);
            return 4;
        }
        memcpy(buf, "inf", 4);
        return 3;
    }
    if (sign) {
        buf[n++] = '-';
    }
    if (bexp == 0 && mant == 0) {
        memcpy(buf + n, "0.0", 4);
        return n + 3;
    }

    if (bexp > 0) {
        m = (UINT64_C(1) << 52) | mant;
        e2 = bexp - 1075;
    }
    else {
        m = mant;
        e2 = -1074;
    }
    even = (m & 1U) == 0;

    if (mant == 0 && bexp > 1) {
        c = m << 2;
        m_minus = 1;
        m_plus = 2;
        q = e2 - 2;
    }
    else {
        c = m << 1;
        m_minus = 1;
        m_plus = 1;
        q = e2 - 1;
    }

    log2_floor = (tpa__f64_u64_bitlen(c) - 1) + q;
    prod = (int32_t)log2_floor * (int32_t)315653;
    if (prod >= 0) {
        e = (int)((uint32_t)prod >> 20);
    }
    else {
        e = -(int)(((uint32_t)(-prod) + 0xFFFFFU) >> 20);
    }

    p5 = e + 1;
    e2_rel = q - p5;

    if (p5 >= 0) {
        tpa__f64_bu_from_u64(&r, c);
        tpa__f64_bu_from_u64(&mm, (uint64_t)m_minus);
        tpa__f64_bu_from_u64(&mp, (uint64_t)m_plus);
        tpa__f64_bu_from_u64(&s, 1U);
        tpa__f64_bu_mul_pow5(&s, p5);
    }
    else {
        tpa__f64_bu_from_u64(&r, c);
        tpa__f64_bu_mul_pow5(&r, -p5);
        tpa__f64_bu_from_u64(&mm, (uint64_t)m_minus);
        tpa__f64_bu_mul_pow5(&mm, -p5);
        tpa__f64_bu_from_u64(&mp, (uint64_t)m_plus);
        tpa__f64_bu_mul_pow5(&mp, -p5);
        tpa__f64_bu_from_u64(&s, 1U);
    }

    if (e2_rel >= 0) {
        tpa__f64_bu_shl(&r, e2_rel);
        tpa__f64_bu_shl(&mm, e2_rel);
        tpa__f64_bu_shl(&mp, e2_rel);
    }
    else {
        tpa__f64_bu_shl(&s, -e2_rel);
    }

    for (;;) {
        tmp = r;
        tpa__f64_bu_mul_u32(&tmp, 10U);
        if (tpa__f64_bu_cmp(&tmp, &s) >= 0) {
            break;
        }
        r = tmp;
        tpa__f64_bu_mul_u32(&mm, 10U);
        tpa__f64_bu_mul_u32(&mp, 10U);
        e--;
    }

    for (;;) {
        int cmp;
        tpa__f64_bu_add(&tmp, &r, &mp);
        cmp = tpa__f64_bu_cmp(&tmp, &s);
        if (cmp > 0 || (even && cmp == 0)) {
            tpa__f64_bu_mul_u32(&s, 10U);
            e++;
        }
        else {
            break;
        }
    }

    for (;;) {
        int d = 0, cmp_low, cmp_high, low, high;
        tpa__f64_bu_mul_u32(&r, 10U);
        while (tpa__f64_bu_cmp(&r, &s) >= 0) {
            tpa__f64_bu_sub(&r, &s);
            d++;
        }
        tpa__f64_bu_mul_u32(&mm, 10U);
        tpa__f64_bu_mul_u32(&mp, 10U);

        cmp_low = tpa__f64_bu_cmp(&r, &mm);
        tpa__f64_bu_add(&tmp, &r, &mp);
        cmp_high = tpa__f64_bu_cmp(&tmp, &s);
        if (even) {
            low = cmp_low <= 0;
            high = cmp_high >= 0;
        }
        else {
            low = cmp_low < 0;
            high = cmp_high > 0;
        }

        if (!low && !high) {
            digs[p++] = (char)('0' + d);
        }
        else if (low && !high) {
            digs[p++] = (char)('0' + d);
            break;
        }
        else if (high && !low) {
            digs[p++] = (char)('0' + d + 1);
            break;
        }
        else {
            int cmp2;
            tmp = r;
            tpa__f64_bu_shl(&tmp, 1);
            cmp2 = tpa__f64_bu_cmp(&tmp, &s);
            if (cmp2 < 0) {
                digs[p++] = (char)('0' + d);
            }
            else if (cmp2 > 0) {
                digs[p++] = (char)('0' + d + 1);
            }
            else {
                digs[p++] = (char)('0' + ((d & 1) == 0 ? d : d + 1));
            }
            break;
        }
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
/* Tier S2a (design 4.3.2): a program that changes the length of a list of some kind defines TPA_GROW_<KIND> before
 * this header, and that kind takes the growable layout: the items in their own block `d`, which append / insert /
 * extend / pop / clear may move while the list object (every name of it) stays. The field names are the same, so an
 * element access `l->d[i]` and a length read `l->len` are the same C text in both layouts. */
#if defined(TPA_GROW_F64)
typedef struct tpa_f64_list { tpa_rc_t rc; int64_t len; int64_t cap; double *d; } tpa_f64_list;
#else
typedef struct tpa_f64_list { tpa_rc_t rc; int64_t len; double d[]; } tpa_f64_list;
#endif
#if defined(TPA_GROW_I64)
typedef struct tpa_i64_list { tpa_rc_t rc; int64_t len; int64_t cap; int64_t *d; } tpa_i64_list;
#else
typedef struct tpa_i64_list { tpa_rc_t rc; int64_t len; int64_t d[]; } tpa_i64_list;
#endif

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

/* Tier S2a, the test hook of the S2 review (B1): under TP_CHECK_PROVEN (the sanitizer job) a resize never keeps or
 * extends a block in place: it allocates a new one, copies the `keep` live bytes and frees the old one, so a stale
 * pointer to the items is a use after free AddressSanitizer reports every time, not only when realloc moves. */
#if defined(TP_CHECK_PROVEN)
#define TPA__RESIZE_IN_PLACE 0
static TPA_UNUSED void *tpa__list_realloc(void *old, size_t size, size_t keep)
{
    void *p = malloc(size);
    if (p == NULL) {
        return NULL;                        /* the old block stays, as realloc leaves it */
    }
    if (keep > 0) {
        memcpy(p, old, keep < size ? keep : size);
    }
    free(old);
    return p;
}
#else
#define TPA__RESIZE_IN_PLACE 1
#define tpa__list_realloc(old, size, keep) realloc((old), (size))
#endif

/* The fixed layout: one block, the items after the header. */
#define TPA__DEF_LIST_ALLOC_FIXED(NAME, CT)                                                       \
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
    static inline void tpa_##NAME##__free(tpa_##NAME##_list *l)                                   \
    {                                                                                             \
        free(l);                                                                                  \
    }

/* Tier S2a, the growable layout: the header, and the items in a block of `cap` slots (NULL while cap is 0). The
 * growth policy is CPython's list_resize (Objects/listobject.c, 3.15): keep the block while the new length is
 * within [cap/2, cap], else allocate (n + n/8 + 6) & ~3 slots, or (n + 3) & ~3 when the jump is larger than that
 * over-allocation, and nothing for an empty list. The policy is not observable (sys.getsizeof is not exposed); it
 * sets the amortised cost of append. A failed resize leaves the list as it was. */
#define TPA__DEF_LIST_ALLOC_GROW(NAME, CT)                                                        \
    static inline int tpa_##NAME##__resize(tpa_##NAME##_list *l, int64_t n)                       \
    {                                                                                             \
        uint64_t want;                                                                            \
        CT *d;                                                                                    \
        if (TPA__RESIZE_IN_PLACE && l->cap >= n && n >= (l->cap >> 1)) {                         \
            l->len = n;                                                                           \
            return 0;                                                                             \
        }                                                                                         \
        want = ((uint64_t)n + ((uint64_t)n >> 3) + 6u) & ~(uint64_t)3;                            \
        if ((uint64_t)(n - l->len) > want - (uint64_t)n) {                                        \
            want = ((uint64_t)n + 3u) & ~(uint64_t)3;                                             \
        }                                                                                         \
        if (n == 0) {                                                                             \
            want = 0;                                                                             \
        }                                                                                         \
        if (want > (uint64_t)(SIZE_MAX / sizeof(CT)) || want > (uint64_t)INT64_MAX) {             \
            return tpa_raise(TPA_MemoryError, "");                                                \
        }                                                                                         \
        if (want == 0) {                                                                          \
            free(l->d);                                                                           \
            d = NULL;                                                                             \
        }                                                                                         \
        else {                                                                                    \
            d = (CT *)tpa__list_realloc(l->d, (size_t)want * sizeof(CT),                          \
                                        (size_t)(l->len < n ? l->len : n) * sizeof(CT));          \
            if (d == NULL) {                                                                      \
                return tpa_raise(TPA_MemoryError, "");                                            \
            }                                                                                     \
        }                                                                                         \
        l->d = d;                                                                                 \
        l->cap = (int64_t)want;                                                                   \
        l->len = n;                                                                               \
        return 0;                                                                                 \
    }                                                                                             \
    static inline int tpa_##NAME##_alloc(int64_t n, tpa_##NAME##_list **out)                      \
    {                                                                                             \
        tpa_##NAME##_list *l = (tpa_##NAME##_list *)calloc(1, sizeof(tpa_##NAME##_list));         \
        if (l == NULL) {                                                                          \
            return tpa_raise(TPA_MemoryError, "");                                                \
        }                                                                                         \
        if (n < 0) {                                                                              \
            n = 0;                      /* `[x] * -1 == []` */                                    \
        }                                                                                         \
        if (n > 0) {                                                                              \
            if ((uint64_t)n > (uint64_t)(SIZE_MAX / sizeof(CT))) {                                \
                free(l);                                                                          \
                return tpa_raise(TPA_MemoryError, "");                                            \
            }                                                                                     \
            l->d = (CT *)malloc((size_t)n * sizeof(CT));                                          \
            if (l->d == NULL) {                                                                   \
                free(l);                                                                          \
                return tpa_raise(TPA_MemoryError, "");                                            \
            }                                                                                     \
        }                                                                                         \
        l->rc = 1;                                                                                \
        l->len = n;                                                                               \
        l->cap = n;                                                                               \
        *out = l;                                                                                 \
        return 0;                                                                                 \
    }                                                                                             \
    static inline void tpa_##NAME##__free(tpa_##NAME##_list *l)                                   \
    {                                                                                             \
        free(l->d);                                                                               \
        free(l);                                                                                  \
    }                                                                                             \
    static inline int tpa_##NAME##_append(tpa_##NAME##_list *l, CT v)                             \
    {                                                                                             \
        if (tpa_##NAME##__resize(l, l->len + 1)) {                                                \
            return -1;                                                                            \
        }                                                                                         \
        l->d[l->len - 1] = v;                                                                     \
        return 0;                                                                                 \
    }                                                                                             \
    /* list.insert: CPython's clamping (ins1: a negative index counts from the end, then 0 .. len) */ \
    static inline int tpa_##NAME##_insert(tpa_##NAME##_list *l, int64_t i, CT v)                  \
    {                                                                                             \
        int64_t n = l->len;                                                                       \
        if (i < 0) {                                                                              \
            i += n;                                                                               \
            if (i < 0) {                                                                          \
                i = 0;                                                                            \
            }                                                                                     \
        }                                                                                         \
        if (i > n) {                                                                              \
            i = n;                                                                                \
        }                                                                                         \
        if (tpa_##NAME##__resize(l, n + 1)) {                                                     \
            return -1;                                                                            \
        }                                                                                         \
        memmove(l->d + i + 1, l->d + i, (size_t)(n - i) * sizeof(CT));                            \
        l->d[i] = v;                                                                              \
        return 0;                                                                                 \
    }                                                                                             \
    /* list.extend / `+=` of a list of the same kind, possibly `l` itself (the list doubles) */  \
    static inline int tpa_##NAME##_extend(tpa_##NAME##_list *l, const tpa_##NAME##_list *o)       \
    {                                                                                             \
        int64_t n = l->len, m = o->len;                                                           \
        if (m == 0) {                                                                             \
            return 0;                                                                             \
        }                                                                                         \
        if (tpa_##NAME##__resize(l, n + m)) {                                                     \
            return -1;                                                                            \
        }                                                                                         \
        memcpy(l->d + n, o->d, (size_t)m * sizeof(CT));   /* (o == l: o->d is the moved block) */ \
        return 0;                                                                                 \
    }                                                                                             \
    static inline void tpa_##NAME##_clear(tpa_##NAME##_list *l)                                   \
    {                                                                                             \
        free(l->d);                                                                               \
        l->d = NULL;                                                                              \
        l->len = 0;                                                                               \
        l->cap = 0;                                                                               \
    }                                                                                             \
    /* list.pop(): Objects/listobject.c list_pop_impl, the empty test first, then the index */    \
    static inline int tpa_##NAME##_pop(tpa_##NAME##_list *l, int has_index, int64_t i, CT *out)   \
    {                                                                                             \
        int64_t n = l->len;                                                                       \
        CT v;                                                                                     \
        if (n == 0) {                                                                             \
            return tpa_raise(TPA_IndexError, "pop from empty list");                              \
        }                                                                                         \
        if (!has_index) {                                                                         \
            i = n - 1;                                                                            \
        }                                                                                         \
        if (i < 0) {                                                                              \
            i += n;                                                                               \
        }                                                                                         \
        if (i < 0 || i >= n) {                                                                    \
            return tpa_raise(TPA_IndexError, "pop index out of range");                           \
        }                                                                                         \
        v = l->d[i];                                                                              \
        memmove(l->d + i, l->d + i + 1, (size_t)(n - i - 1) * sizeof(CT));                        \
        if (tpa_##NAME##__resize(l, n - 1)) {   /* a shrink only fails to give memory back: the */ \
            l->len = n - 1;                     /* list is one shorter all the same (the record   */ \
        }                                       /* of the failure is never read: no -1 follows)   */ \
        *out = v;                                                                                 \
        return 0;                                                                                 \
    }

#define TPA__DEF_LIST(NAME, CT)                                                                   \
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
            tpa_##NAME##__free(l);                                                                \
        }                                                                                         \
    }                                                                                             \
    /* list.reverse() */                                                                          \
    static inline TPA_UNUSED void tpa_##NAME##_reverse(tpa_##NAME##_list *l)                      \
    {                                                                                             \
        int64_t i, j;                                                                             \
        for (i = 0, j = l->len - 1; i < j; i++, j--) {                                            \
            CT t = l->d[i];                                                                       \
            l->d[i] = l->d[j];                                                                    \
            l->d[j] = t;                                                                          \
        }                                                                                         \
    }
#if defined(TPA_GROW_F64)
TPA__DEF_LIST_ALLOC_GROW(f64, double)
#else
TPA__DEF_LIST_ALLOC_FIXED(f64, double)
#endif
#if defined(TPA_GROW_I64)
TPA__DEF_LIST_ALLOC_GROW(i64, int64_t)
#else
TPA__DEF_LIST_ALLOC_FIXED(i64, int64_t)
#endif
TPA__DEF_LIST(f64, double)
TPA__DEF_LIST(i64, int64_t)
#undef TPA__DEF_LIST

/* Tier S2a: searching a list[int] of the RAW kind (a list of float is never searched: NaN's identity, design 4.3.2).
 * list.index(v): the first position, else ValueError("<v> is not in list") (Objects/listobject.c list_index_impl
 * formats the value with %R, the decimal digits of an int); list.count(v); `v in l`. */
static inline TPA_UNUSED int tpa_i64_index(const tpa_i64_list *l, int64_t v, int64_t *out)
{
    int64_t i;
    char m[48];
    for (i = 0; i < l->len; i++) {
        if (l->d[i] == v) {
            *out = i;
            return 0;
        }
    }
    snprintf(m, sizeof m, "%lld is not in list", (long long)v);
    return tpa_raise(TPA_ValueError, m);
}

static inline TPA_UNUSED int64_t tpa_i64_count(const tpa_i64_list *l, int64_t v)
{
    int64_t i, n = 0;
    for (i = 0; i < l->len; i++) {
        n += l->d[i] == v;
    }
    return n;
}

static inline TPA_UNUSED int tpa_i64_contains(const tpa_i64_list *l, int64_t v)
{
    int64_t i;
    for (i = 0; i < l->len; i++) {
        if (l->d[i] == v) {
            return 1;
        }
    }
    return 0;
}

/* Tier S2a: the outcome of a list comparison (Objects/listobject.c list_richcompare) once the first differing pair,
 * or the lengths, give `c` (<0, 0, >0); `op` 0..5 is == != < <= > >=. */
static inline int tpa__cmp_outcome(int op, int c)
{
    switch (op) {
    case 0: return c == 0;
    case 1: return c != 0;
    case 2: return c < 0;
    case 3: return c <= 0;
    case 4: return c > 0;
    default: return c >= 0;
    }
}

/* a op b of two raw int lists: the first index where the items differ decides; with none, the lengths. */
static inline TPA_UNUSED int tpa_i64_cmp(const tpa_i64_list *a, const tpa_i64_list *b, int op)
{
    int64_t i, n = a->len < b->len ? a->len : b->len;
    for (i = 0; i < n; i++) {
        if (a->d[i] != b->d[i]) {
            if (op <= 1) {
                return op == 1;
            }
            return tpa__cmp_outcome(op, a->d[i] < b->d[i] ? -1 : 1);
        }
    }
    return tpa__cmp_outcome(op, a->len < b->len ? -1 : a->len > b->len ? 1 : 0);
}

#if defined(TPA_GROW_I64)
/* list.remove(v): the first equal item, else ValueError("list.remove(x): x not in list") */
static inline TPA_UNUSED int tpa_i64_remove(tpa_i64_list *l, int64_t v)
{
    int64_t i, dummy;
    for (i = 0; i < l->len; i++) {
        if (l->d[i] == v) {
            return tpa_i64_pop(l, 1, i, &dummy);
        }
    }
    return tpa_raise(TPA_ValueError, "list.remove(x): x not in list");
}
#endif

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

/* format(x, '.<prec>f') of a float (issue #450): exact round-half-to-even from the IEEE-754 bits
 * with no dependence on the C library's printf rounding. `buf` must hold at least 320 + prec bytes
 * (valid for 0 <= prec <= 300; the AOT compiler caps prec at AOT_MAX_PRECISION = 20). */
static inline int tpa_format_f64_fixed(double v, int prec, char *buf)
{
    uint64_t bits, mant, m;
    int sign, bexp, e2, n = 0;
    if (prec < 0) {
        prec = 0;
    }
    memcpy(&bits, &v, sizeof bits);
    sign = (int)(bits >> 63);
    bexp = (int)((bits >> 52) & 0x7FFU);
    mant = bits & ((UINT64_C(1) << 52) - 1U);

    if (bexp == 0x7FF) {
        if (mant != 0) {
            memcpy(buf, "nan", 4);
            return 3;
        }
        if (sign) {
            memcpy(buf, "-inf", 5);
            return 4;
        }
        memcpy(buf, "inf", 4);
        return 3;
    }
    if (sign) {
        buf[n++] = '-';
    }
    if (bexp == 0 && mant == 0) {
        buf[n++] = '0';
        if (prec > 0) {
            buf[n++] = '.';
            memset(buf + n, '0', (size_t)prec);
            n += prec;
        }
        buf[n] = '\0';
        return n;
    }
    if (bexp > 0) {
        m = (UINT64_C(1) << 52) | mant;
        e2 = bexp - 1075;
    }
    else {
        m = mant;
        e2 = -1074;
    }
    if (e2 >= 0) {
        tpa__f64_bu val;
        tpa__f64_bu_from_u64(&val, m);
        tpa__f64_bu_shl(&val, e2);
        n += tpa__f64_bu_to_dec(&val, buf + n);
        if (prec > 0) {
            buf[n++] = '.';
            memset(buf + n, '0', (size_t)prec);
            n += prec;
        }
        buf[n] = '\0';
        return n;
    }
    {
        int s_exp = e2 + prec;
        tpa__f64_bu val;
        char digs[360];
        int ndig;
        if (s_exp >= 0) {
            tpa__f64_bu_from_u64(&val, m);
            tpa__f64_bu_mul_pow5(&val, prec);
            tpa__f64_bu_shl(&val, s_exp);
        }
        else {
            int shift = -s_exp;
            if (shift > 54 + 3 * prec) {
                val.n = 0;
            }
            else {
                tpa__f64_bu_from_u64(&val, m);
                tpa__f64_bu_mul_pow5(&val, prec);
                tpa__f64_bu_shr_round_even(&val, shift);
            }
        }
        ndig = tpa__f64_bu_to_dec(&val, digs);
        if (prec == 0) {
            memcpy(buf + n, digs, (size_t)ndig);
            n += ndig;
        }
        else if (ndig <= prec) {
            buf[n++] = '0';
            buf[n++] = '.';
            memset(buf + n, '0', (size_t)(prec - ndig));
            n += prec - ndig;
            memcpy(buf + n, digs, (size_t)ndig);
            n += ndig;
        }
        else {
            int ip = ndig - prec;
            memcpy(buf + n, digs, (size_t)ip);
            n += ip;
            buf[n++] = '.';
            memcpy(buf + n, digs + ip, (size_t)prec);
            n += prec;
        }
        buf[n] = '\0';
        return n;
    }
}

static inline void tpa_print_f64_fixed(double v, int prec)
{
    char stack_buf[400];
    char *buf = stack_buf;
    int n;
    if (prec < 0) {
        prec = 0;
    }
    if (prec > 60) {
        buf = (char *)malloc((size_t)prec + 320U);
        if (buf == NULL) {
            return;
        }
    }
    n = tpa_format_f64_fixed(v, prec, buf);
    fwrite(buf, 1, (size_t)n, stdout);
    if (buf != stack_buf) {
        free(buf);
    }
}

static inline void tpa_print_sep(void) { fputc(' ', stdout); }
static inline void tpa_print_end(void) { fputc('\n', stdout); }

/* ------------------------------------------------------------------------------------------
 * Integer shifts (RAW int64_t), float power, additional math, time, and sys.exit (Tier K PR5)
 * ------------------------------------------------------------------------------------------ */

static inline int tpa_rshift_i64(int64_t a, int64_t b, int64_t *out)
{
    if (b < 0) {
        tpa_raise(TPA_ValueError, "negative shift count");
        return -1;
    }
    if (b >= 64) {
        *out = (a < 0) ? -1 : 0;
        return 0;
    }
    if (a >= 0) {
        *out = (int64_t)((uint64_t)a >> (unsigned)b);
    }
    else {
        *out = (int64_t)(~((~(uint64_t)a) >> (unsigned)b));
    }
    return 0;
}

static inline int tpa_plshift_i64(int64_t a, int64_t b, int64_t *out)
{
    if (b < 0) {
        tpa_raise(TPA_ValueError, "negative shift count");
        return -1;
    }
    if (b >= 64) {
        *out = 0;
        return 0;
    }
    *out = (int64_t)((uint64_t)a << (unsigned)b);
    return 0;
}

static inline int tpa__f64_is_odd_integer(double x)
{
    return isfinite(x) && floor(x) == x && fmod(fabs(x), 2.0) == 1.0;
}

/* CPython Objects/floatobject.c float_pow semantics (float ** float). */
static inline int tpa_pow_f64(double iv, double iw, double *out)
{
    double r;
    int negate_result = 0;
    if (iw == 0.0) {
        *out = 1.0;
        return 0;
    }
    if (isnan(iv) || isnan(iw)) {
        *out = (iv == 1.0) ? 1.0 : NAN;
        return 0;
    }
    if (isinf(iw)) {
        iv = fabs(iv);
        if (iv == 1.0) {
            *out = 1.0;
            return 0;
        }
        else if ((iw > 0.0) == (iv > 1.0)) {
            *out = fabs(iw);
            return 0;
        }
        else {
            *out = 0.0;
            return 0;
        }
    }
    if (isinf(iv)) {
        int odd = tpa__f64_is_odd_integer(iw);
        if (iw > 0.0) {
            *out = odd ? iv : fabs(iv);
        }
        else {
            *out = odd ? copysign(0.0, iv) : 0.0;
        }
        return 0;
    }
    if (iv == 0.0) {
        if (iw < 0.0) {
            tpa_raise(TPA_ZeroDivisionError, "zero to a negative power");
            return -1;
        }
        *out = tpa__f64_is_odd_integer(iw) ? iv : 0.0;
        return 0;
    }
    if (iv < 0.0) {
        if (iw != floor(iw)) {
            tpa_raise(TPA_ValueError, "negative number cannot be raised to a fractional power");
            return -1;
        }
        iv = -iv;
        negate_result = tpa__f64_is_odd_integer(iw);
    }
    errno = 0;
    r = pow(iv, iw);
    if (errno == ERANGE && isinf(r)) {
        tpa_raise(TPA_OverflowError, "(34, 'Numerical result out of range')");
        return -1;
    }
    if (isinf(r)) {
        tpa_raise(TPA_OverflowError, "(34, 'Numerical result out of range')");
        return -1;
    }
    *out = negate_result ? -r : r;
    return 0;
}

#define TPA_MATH_MSG_UNIT "expected a number in range from -1 up to 1, got "

static inline double tpa__m_log2(double x)
{
    if (isfinite(x)) {
        if (x > 0.0) return log2(x);
        errno = EDOM;
        if (x == 0.0) return -HUGE_VAL;
        return NAN;
    }
    else if (isnan(x)) return x;
    else if (x > 0.0) return x;
    errno = EDOM;
    return NAN;
}

static inline double tpa__m_log10(double x)
{
    if (isfinite(x)) {
        if (x > 0.0) return log10(x);
        errno = EDOM;
        if (x == 0.0) return -HUGE_VAL;
        return NAN;
    }
    else if (isnan(x)) return x;
    else if (x > 0.0) return x;
    errno = EDOM;
    return NAN;
}

#define TPA__DEF_MATH1_K5(NAME, CALL, CAN_OVERFLOW, MSG)                                       \
    static inline int tpa_math_##NAME(double x, double *out)                                   \
    {                                                                                          \
        double r;                                                                              \
        errno = 0;                                                                             \
        r = CALL;                                                                              \
        return tpa__math_1_finish(x, r, CAN_OVERFLOW, MSG, out);                               \
    }
TPA__DEF_MATH1_K5(log2, tpa__m_log2(x), 0, TPA_MATH_MSG_POSITIVE)
TPA__DEF_MATH1_K5(log10, tpa__m_log10(x), 0, TPA_MATH_MSG_POSITIVE)
TPA__DEF_MATH1_K5(asin, asin(x), 0, TPA_MATH_MSG_UNIT)
TPA__DEF_MATH1_K5(acos, acos(x), 0, TPA_MATH_MSG_UNIT)
TPA__DEF_MATH1_K5(atan, atan(x), 0, NULL)
TPA__DEF_MATH1_K5(sinh, sinh(x), 1, NULL)
TPA__DEF_MATH1_K5(cosh, cosh(x), 1, NULL)
TPA__DEF_MATH1_K5(tanh, tanh(x), 0, NULL)
#undef TPA__DEF_MATH1_K5

static inline int tpa_math_pow(double x, double y, double *out)
{
    double r;
    if (y == 0.0 || x == 1.0) {
        *out = 1.0;
        return 0;
    }
    if (isnan(x) || isnan(y)) {
        *out = NAN;
        return 0;
    }
    if (x == 0.0 && y < 0.0) {
        tpa_raise(TPA_ValueError, "math domain error");
        return -1;
    }
    if (isfinite(x) && x < 0.0 && isfinite(y) && floor(y) != y) {
        tpa_raise(TPA_ValueError, "math domain error");
        return -1;
    }
    errno = 0;
    r = pow(x, y);
    if (isnan(r) && !isnan(x) && !isnan(y)) {
        tpa_raise(TPA_ValueError, "math domain error");
        return -1;
    }
    if (isinf(r) && isfinite(x) && isfinite(y)) {
        tpa_raise(TPA_OverflowError, "math range error");
        return -1;
    }
    if (isfinite(r) && errno && tpa__math_is_error(r)) {
        return -1;
    }
    *out = r;
    return 0;
}

static inline int tpa_math_copysign(double x, double y, double *out)
{
    *out = copysign(x, y);
    return 0;
}

static inline int tpa_math_fmod(double x, double y, double *out)
{
    double r;
    if (isnan(x) || isnan(y)) {
        *out = NAN;
        return 0;
    }
    if (y == 0.0 || isinf(x)) {
        tpa_raise(TPA_ValueError, "math domain error");
        return -1;
    }
    if (isinf(y)) {
        *out = x;
        return 0;
    }
    errno = 0;
    r = fmod(x, y);
    if (isnan(r)) {
        tpa_raise(TPA_ValueError, "math domain error");
        return -1;
    }
    *out = r;
    return 0;
}

static inline int tpa_math_isnan(double x) { return isnan(x) ? 1 : 0; }
static inline int tpa_math_isinf(double x) { return isinf(x) ? 1 : 0; }
static inline int tpa_math_isfinite(double x) { return isfinite(x) ? 1 : 0; }

/* Portable time helpers */
static inline double tpa_time_perf_counter(void)
{
#if defined(_WIN32)
    LARGE_INTEGER freq, counter;
    QueryPerformanceFrequency(&freq);
    QueryPerformanceCounter(&counter);
    return (double)counter.QuadPart / (double)freq.QuadPart;
#elif defined(CLOCK_MONOTONIC)
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
#else
    return (double)clock() / (double)CLOCKS_PER_SEC;
#endif
}

static inline double tpa_time_monotonic(void)
{
#if defined(_WIN32)
    return (double)GetTickCount64() * 1e-3;
#elif defined(CLOCK_MONOTONIC)
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
#else
    return (double)clock() / (double)CLOCKS_PER_SEC;
#endif
}

static inline double tpa_time_time(void)
{
#if defined(_WIN32)
    FILETIME ft;
    ULARGE_INTEGER uli;
    GetSystemTimeAsFileTime(&ft);
    uli.LowPart = ft.dwLowDateTime;
    uli.HighPart = ft.dwHighDateTime;
    return (double)(uli.QuadPart - 116444736000000000ULL) * 1e-7;
#elif defined(CLOCK_REALTIME)
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
#else
    return (double)time(NULL);
#endif
}

static inline int tpa_time_sleep(double secs)
{
    if (isnan(secs) || secs < 0.0) {
        tpa_raise(TPA_ValueError, "sleep length must be non-negative");
        return -1;
    }
    if (secs == 0.0) {
        return 0;
    }
#if defined(_WIN32)
    {
        double ms = secs * 1000.0;
        DWORD d = (ms >= (double)0xFFFFFFFEu) ? (DWORD)0xFFFFFFFEu : (DWORD)ms;
        Sleep(d);
    }
#else
    {
        struct timespec req, rem;
        req.tv_sec = (time_t)secs;
        req.tv_nsec = (long)((secs - (double)req.tv_sec) * 1e9);
        if (req.tv_nsec < 0) req.tv_nsec = 0;
        if (req.tv_nsec >= 1000000000L) {
            req.tv_sec += 1;
            req.tv_nsec -= 1000000000L;
        }
        while (nanosleep(&req, &rem) == -1 && errno == EINTR) {
            req = rem;
        }
    }
#endif
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Scope regions (SPEC N-17, N-12b/c step 2, design 6.3.2 item 2; agent proposal, not owner-confirmed).
 * A region is a list of bump-allocated chunks owned by one C local of a generated function: the
 * function's body, or one iteration of one of its loops, owns it. The objects in it (`struct
 * tpa_reg_<F>__<C>`, no reference count) are freed together, at the function's one cleanup point, or
 * released and reset at the top of every iteration. One region holds objects of ONE type (the generated
 * code gives each (function, class) its own region), so the generated per-class walk that releases the
 * owned heap references the objects hold (lists, big ints, heap-rc objects) steps through a chunk in
 * units of that type's size: `used` is an exact multiple of it. A chunk's data starts 16-byte aligned
 * (the header is 32 bytes on a 64-bit target and malloc's result is at least 16-byte aligned), and
 * sizeof(T) is a multiple of alignof(T), so every object is aligned. An allocation is zeroed, so a field
 * the generated constructor did not reach (its argument conversion failed) releases as nothing: a NULL
 * list or object; big-int fields are set to TPA_INT_ZERO by the constructor before anything can fail
 * (the word 0 is not a tpa_int value). A failed chunk allocation raises MemoryError and returns NULL;
 * the generated site leaves through `tp_cleanup:`, which frees the region.
 * ------------------------------------------------------------------------------------------ */
#define TPA_REGION_FIRST_CHUNK ((size_t)4096)          /* bytes of objects in the first chunk */
#define TPA_REGION_MAX_CHUNK ((size_t)1 << 20)         /* each later chunk doubles, up to this */

typedef struct tpa_region_chunk {
    struct tpa_region_chunk *next;      /* the previous chunk (the head is the newest) */
    size_t cap;
    size_t used;
    size_t pad_;                        /* keeps the header at 32 bytes: the objects start 16-byte aligned */
} tpa_region_chunk;

typedef struct tpa_region {
    tpa_region_chunk *head;
} tpa_region;

#define TPA_REGION_INIT { NULL }

static inline void *tpa_region_alloc(tpa_region *r, size_t size)
{
    tpa_region_chunk *c = r->head;
    char *p;
    if (c == NULL || c->cap - c->used < size) {
        size_t cap = c == NULL ? TPA_REGION_FIRST_CHUNK : (c->cap < TPA_REGION_MAX_CHUNK ? c->cap * 2 : c->cap);
        tpa_region_chunk *n;
        if (cap < size) {
            cap = size;
        }
        n = (tpa_region_chunk *)malloc(sizeof(tpa_region_chunk) + cap);
        if (n == NULL) {
            tpa_raise(TPA_MemoryError, "");
            return NULL;
        }
        n->next = c;
        n->cap = cap;
        n->used = 0;
        n->pad_ = 0;
        r->head = n;
        c = n;
    }
    p = (char *)(c + 1) + c->used;
    c->used += size;
    memset(p, 0, size);
    return p;
}

/* Every chunk freed; the region is empty again (a function frees each of its regions once, at its cleanup point). */
static inline void tpa_region_free(tpa_region *r)
{
    tpa_region_chunk *c = r->head;
    while (c != NULL) {
        tpa_region_chunk *next = c->next;
        free(c);
        c = next;
    }
    r->head = NULL;
}

/* The start of an iteration: the newest (largest) chunk is kept and emptied, the older ones are freed, so a
 * loop whose iterations allocate the same amount reuses one chunk and calls malloc no more. */
static inline void tpa_region_reset(tpa_region *r)
{
    tpa_region_chunk *c = r->head;
    if (c == NULL) {
        return;
    }
    tpa_region_free(&(tpa_region){ c->next });
    c->next = NULL;
    c->used = 0;
}

/* Deferred frees of the generated class releases (review of #508, F1; CPython's trashcan has the same job). A
 * release that frees an object releases its fields, and a field of a class type is released by a call: a chain of
 * n objects through a field that is not the release loop's tail field recursed n deep and overflowed the C stack
 * (a 500k-node chain). Every release of a class that has a field of a class type now counts its nesting depth:
 * below TPA_TRASH_LIMIT it frees at once, exactly as before; at the limit it parks the object (its count already
 * zero, already untracked) with the class's drop function on a thread-local list, and the outermost release
 * drains the list when its depth returns to zero, each drop starting again from depth 1. So no release nests more
 * than TPA_TRASH_LIMIT deep, whatever the shape of the chain or the classes it alternates between. Only the order
 * of frees changes, beyond the limit; AOT compiles no `__del__` and a freed object is unreachable, so the order is
 * not observable. When the list cannot grow (out of memory) the object is freed at once, as before. */
#ifndef TPA_TRASH_LIMIT
#define TPA_TRASH_LIMIT 1000
#endif

typedef void (*tpa_trash_fn)(void *obj);

typedef struct tpa_trash_item {
    void *obj;
    tpa_trash_fn drop;
} tpa_trash_item;

typedef struct tpa_trash_state {
    int64_t depth;                  /* releases of trash-aware classes in flight on this thread */
    tpa_trash_item *items;          /* parked objects, LIFO */
    size_t len;
    size_t cap;
#if defined(TPA_TRASH_STATS)
    int64_t max_depth;              /* tests only: the deepest nesting reached, and the objects parked */
    int64_t parked;
    int registered;
#endif
} tpa_trash_state;

#if defined(TPA_THREADS)
static TPA_THREAD_LOCAL tpa_trash_state tpa_trash;
#else
static tpa_trash_state tpa_trash;
#endif

#if defined(TPA_TRASH_STATS)
/* Tests only (-DTPA_TRASH_STATS): the nesting the bound allowed, printed at exit, so a test checks the depth itself
 * instead of relying on a stack overflow, whose size differs between platforms (review of #513's first CI run). */
static void tpa_trash__report(void)
{
    fflush(stdout);
    fprintf(stderr, "tpa_trash: max_depth=%lld parked=%lld\n", (long long)tpa_trash.max_depth,
            (long long)tpa_trash.parked);
    fflush(stderr);
}
#endif

/* Called by a release once the count reached zero: 1 to free now (the caller frees the fields and the object,
 * then calls tpa_trash_leave), 0 when the object was parked (the caller returns at once). */
static inline int tpa_trash_enter(void *obj, tpa_trash_fn drop) TPA_UNUSED;
static inline int tpa_trash_enter(void *obj, tpa_trash_fn drop)
{
    tpa_trash_state *t = &tpa_trash;
#if defined(TPA_TRASH_STATS)
    if (!t->registered) {
        t->registered = 1;
        atexit(tpa_trash__report);
    }
#endif
    if (t->depth >= TPA_TRASH_LIMIT) {
        if (t->len == t->cap) {
            size_t cap = t->cap != 0 ? t->cap * 2 : 64;
            tpa_trash_item *items = (tpa_trash_item *)realloc(t->items, cap * sizeof *items);
            if (items != NULL) {
                t->items = items;
                t->cap = cap;
            }
        }
        if (t->len < t->cap) {
            t->items[t->len].obj = obj;
            t->items[t->len].drop = drop;
            t->len += 1;
#if defined(TPA_TRASH_STATS)
            t->parked += 1;
#endif
            return 0;
        }
    }
    t->depth += 1;
#if defined(TPA_TRASH_STATS)
    if (t->depth > t->max_depth) {
        t->max_depth = t->depth;
    }
#endif
    return 1;
}

static void tpa_trash__drain(tpa_trash_state *t) TPA_UNUSED;
static void tpa_trash__drain(tpa_trash_state *t)
{
    t->depth = 1;                   /* a drop's own releases nest from here; none of them drains again */
    while (t->len > 0) {
        tpa_trash_item it = t->items[--t->len];
        it.drop(it.obj);
    }
    t->depth = 0;
    free(t->items);
    t->items = NULL;
    t->cap = 0;
}

static inline void tpa_trash_leave(void) TPA_UNUSED;
static inline void tpa_trash_leave(void)
{
    tpa_trash_state *t = &tpa_trash;
    t->depth -= 1;
    if (t->depth == 0 && t->len > 0) {
        tpa_trash__drain(t);
    }
}

/* The objects of a region of one type: `TPA_REGION_FOREACH(T, obj, region) { ... }` runs the statement for every
 * object the region holds, in allocation order inside a chunk (the chunks newest first). The generated
 * `tpa_reg_<F>__<C>_drop` uses it to release the owned heap references of every object before a free or a reset. */
#define TPA_REGION_FOREACH(T, obj, region)                                                       \
    for (tpa_region_chunk *tpa__c = (region)->head; tpa__c != NULL; tpa__c = tpa__c->next)        \
        for (size_t tpa__off = 0; tpa__off + sizeof(T) <= tpa__c->used; tpa__off += sizeof(T))   \
            for (T *obj = (T *)((char *)(tpa__c + 1) + tpa__off); obj != NULL; obj = NULL)

#if !defined(TPA_LIBRARY)
/* Run once by `sys.exit` before `exit()` (REVIEW-503 note 2): the generated `main` sets it to `tpa_gc_exit` when
 * the program links the cycle collector, so the cycles alive at `sys.exit` are collected as they are when
 * `__main__` returns (CPython collects at shutdown on both paths). NULL in every other program. Read and
 * cleared without synchronisation: `sys.exit` runs only on the main thread today (AOT has no parallel loops and
 * the library build refuses `sys.exit`; review of #508, F4). */
static void (*tpa_exit_hook)(void);

static inline void tpa_exit_status(int status)
{
    void (*hook)(void) = tpa_exit_hook;
    tpa_exit_hook = NULL;
    if (hook != NULL) {
        hook();
    }
    fflush(stdout);
    fflush(stderr);
    exit(status);
}

static inline void tpa_exit_i64(int64_t code)
{
    tpa_exit_status((int)(code & 0xff));
}
#endif

#endif
