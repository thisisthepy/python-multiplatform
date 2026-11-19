/* tp_aot_int.h: the big-integer runtime of the TypedPython AOT mode (SPEC N-17, #460,
 * docs/design/typedpython-aot.md sections 5 and 10).
 *
 * Design decision D1 (a), leader 2026-11-16, not owner-confirmed: an int that cannot be proved inside 64
 * bits is a small-int / big-int value of our own, linked only when needed. Everything else in this file
 * (the layout, the names, the algorithms, the status codes) is an agent proposal, not owner-confirmed.
 * The AOT back end includes this header only in a program that has a TAGGED int (#462, aot/rep.py): a program
 * whose ints are all proved inside 64 bits does not include it ("linked only when needed"). It is tested through
 * a test-only shim (src/test/python/runtime_shim/tp_aot_int_shim.c) against the reference interpreter, and
 * through the programs test_aot_build.py builds. The last section holds what the compiler needs on top of the
 * functions above (mixed comparisons, index conversions, `sys.argv` and the repr table of the error message).
 *
 * Include it AFTER tp_aot.h (it uses the exception record `tpa_raise`, the thread-local macro and the
 * atomic count macros of the TPA_THREADS block). It includes no interpreter header and names no
 * interpreter API. The governing rule (owner): every result equals CPython 3.15's, values, exception
 * classes and messages. The messages below are copied from the 3.15 reference interpreter of this
 * repository, each cited where it is used.
 *
 * ---- The value ----------------------------------------------------------------------------------
 * `tpa_int` is one machine word (`uintptr_t`; a plain integer type rather than a one-member struct
 * because a word travels in registers on every target and a compiled function takes and returns it
 * like an `int64_t`; the price is that the compiler does not stop a program from mixing it with a plain
 * integer, which the generated code never does). The mode supports 64-bit targets only (checked below).
 *   small: the low bit is 1; the upper 63 bits hold a two's-complement value in [-2**62, 2**62 - 1].
 *   big:   the low bit is 0; the word is a pointer to an immutable `tpa_big` {count, limb count, sign,
 *          limbs[]} (malloc'd, so at least 8-byte aligned) holding a value OUTSIDE the small range.
 *          Limbs are 32-bit, little endian, magnitude only, normalised (the top limb is not zero).
 * Invariants (every public function leaves them true, every public function may assume them):
 *   I1 canonical form: a value inside the small range is ALWAYS small. Equal values have one
 *      representation, so two smalls compare equal exactly when their words are equal, and the
 *      comparison of two smalls is a signed comparison of the words.
 *   I2 a big has count >= 1, limb count >= 2 (a magnitude of 2**62 or more needs 63 bits), a top limb
 *      that is not zero, and sign 0 or 1 (never negative zero: zero is small).
 *   I3 a big is never modified after it is created. Sharing is by reference counting only.
 *   I4 the word 0 is not a value (a null pointer): callers may use it for "unset".
 * Ownership: every operation BORROWS its arguments and returns a NEW reference (a big has its count
 * raised by one, or a fresh one with count 1; a small needs nothing). `tpa_int_retain` and
 * `tpa_int_release` raise and lower the count (no-ops for a small); the release that brings it to zero
 * frees the object. Under TPA_THREADS the count is atomic (the same macros as the list counts in
 * tp_aot.h): any number of threads may retain, release and read one big at once, because nothing ever
 * writes to it. Without TPA_THREADS the count is plain.
 *
 * ---- Status codes ---------------------------------------------------------------------------------
 * `int tpa_int_<op>(..., tpa_int *out)` returns 0 (the result is written) or -1 (an exception is
 * recorded in `tpa_exc`, nothing is written, nothing leaks). Allocation failure records MemoryError.
 * Two functions return a third, positive status that records NO exception, so a caller can tell "the
 * runtime cannot answer" from "the program raised": `tpa_int_to_i64` returns 1 when the value does not
 * fit 64 bits; `tpa_int_from_str` returns TPA_INT_NOT_HANDLED (1) for the inputs described there.
 *
 * ---- Algorithms (agent proposal, not owner-confirmed) ---------------------------------------------
 * Multiplication is schoolbook (no Karatsuba in this step). Division is Knuth's algorithm D on 32-bit
 * limbs in uint64_t arithmetic. The decimal conversions work in chunks of 10**9 and are quadratic in
 * the limb count (acceptable now: the digit limit of 4300 bounds the conversions a program can do).
 * The 64x64 to 128 bit product that a small-by-small multiply needs is done with 32-bit limbs: no
 * 128-bit integer type and no compiler intrinsic, so GCC, Clang and MSVC compile the same code.
 *
 * ---- Test-only live counter -----------------------------------------------------------------------
 * With -DTPA_INT_COUNT_LIVE (the test shim, never a normal build) every big object that is allocated
 * raises, and every one that is freed lowers, an atomic counter that `tpa_int_live()` reads, so a test
 * can observe a leak without a leak checker. Without the macro neither the counter nor the function
 * exists.
 */
#ifndef TP_AOT_INT_H
#define TP_AOT_INT_H

#include <math.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#ifndef TP_AOT_H
#error "tp_aot_int.h must be included after tp_aot.h"
#endif

typedef uintptr_t tpa_int;

/* The mode supports 64-bit targets only: a pointer and the word are both 64 bits. */
typedef char tpa_int__assert_64bit[(sizeof(void *) == 8 && sizeof(uintptr_t) == 8 && sizeof(uint64_t) == 8) ? 1 : -1];

#define TPA_INT_SMALL_MAX ((int64_t)4611686018427387903)          /* 2**62 - 1 */
#define TPA_INT_SMALL_MIN (-TPA_INT_SMALL_MAX - 1)                 /* -2**62 */
#define TPA_INT_MAX_STR_DIGITS 4300                               /* sys.int_info.default_max_str_digits */
#define TPA_INT_NOT_HANDLED 1                                     /* see tpa_int_from_str */
#define TPA_INT_UNORDERED 2                                       /* see tpa_int_cmp_f64 */
#define TPA_INT__MAX_LIMBS ((size_t)0x1FFFFFFF)                   /* a bigger request is a MemoryError */

/* Messages of the 3.15 reference interpreter (printed by the commands in the report of #460):
 *   1 // 0, 1 % 0, divmod(1, 0), 1 / 0, (10**400) / 0   ZeroDivisionError: division by zero (TPA_MSG_DIVISION_ZERO)
 *   float(10**400)                                      OverflowError: int too large to convert to float
 *   (2**1024) / 1                                       OverflowError: integer division result too large for a float
 *   str(10**4300)                                       ValueError: (TO_STR below)
 *   int("1" * 4301)                                     ValueError: (FROM_STR below, "%llu" is the digit count)
 *   int("x")                                            ValueError: invalid literal for int() with base 10: 'x' */
#define TPA_MSG_INT_TO_FLOAT "int too large to convert to float"
#define TPA_MSG_INT_TRUEDIV_OVERFLOW "integer division result too large for a float"
#define TPA_MSG_INT_TO_STR_LIMIT "Exceeds the limit (4300 digits) for integer string conversion; use sys.set_int_max_str_digits() to increase the limit"
#define TPA_MSG_INT_FROM_STR_LIMIT "Exceeds the limit (4300 digits) for integer string conversion: value has %llu digits; use sys.set_int_max_str_digits() to increase the limit"
#define TPA_MSG_INT_INVALID_LITERAL "invalid literal for int() with base 10: "

/* ------------------------------------------------------------------------------------------
 * The big object and the test-only live counter
 * ------------------------------------------------------------------------------------------ */
typedef struct tpa_big {
    tpa_rc_t rc;
    uint32_t n;          /* limb count, >= 2 for a value held here (I2) */
    uint32_t neg;        /* 1 for a negative value */
    uint32_t d[];        /* magnitude, little endian, top limb not zero */
} tpa_big;

#if defined(TPA_INT_COUNT_LIVE)
typedef struct { tpa_rc_t rc; } tpa_int_live_t;
static tpa_int_live_t tpa_int_live_counter;
#define TPA_INT__LIVE_INC() TPA_RC_INC(&tpa_int_live_counter)
#define TPA_INT__LIVE_DEC() ((void)TPA_RC_DEC_IS_ZERO(&tpa_int_live_counter))
/* The number of big objects allocated and not yet freed (test-only). */
static inline int64_t tpa_int_live(void)
{
#if defined(TPA_THREADS)
#if defined(_MSC_VER) && !defined(__clang__)
    return (int64_t)_InterlockedCompareExchange64((volatile __int64 *)&tpa_int_live_counter.rc, 0, 0);
#else
    return atomic_load_explicit(&tpa_int_live_counter.rc, memory_order_acquire);
#endif
#else
    return tpa_int_live_counter.rc;
#endif
}
#else
#define TPA_INT__LIVE_INC() ((void)0)
#define TPA_INT__LIVE_DEC() ((void)0)
#endif

/* ------------------------------------------------------------------------------------------
 * The small value
 * ------------------------------------------------------------------------------------------ */
static inline int tpa_int_is_small(tpa_int a) { return (int)(a & 1u); }

/* v must be in [TPA_INT_SMALL_MIN, TPA_INT_SMALL_MAX]. */
static inline tpa_int tpa_int_small(int64_t v) { return (tpa_int)(((uint64_t)v << 1) | 1u); }

/* The value of a small, by sign extension from bit 62 (no right shift of a negative value). */
static inline int64_t tpa_int_small_value(tpa_int a)
{
    uint64_t u = (uint64_t)a >> 1;
    return (int64_t)((u ^ 0x4000000000000000ULL) - 0x4000000000000000ULL);
}

static inline int tpa_int__in_small_range(int64_t v) { return v >= TPA_INT_SMALL_MIN && v <= TPA_INT_SMALL_MAX; }

static inline tpa_big *tpa_int__big(tpa_int a) { return (tpa_big *)(uintptr_t)a; }
static inline tpa_int tpa_int__word(tpa_big *b) { return (tpa_int)(uintptr_t)b; }

static inline void tpa_int_retain(tpa_int a)
{
    if (!(a & 1u)) {
        tpa_big *b = tpa_int__big(a);
        TPA_RC_INC(b);
    }
}

static inline void tpa_int_release(tpa_int a)
{
    if (!(a & 1u)) {
        tpa_big *b = tpa_int__big(a);
        if (TPA_RC_DEC_IS_ZERO(b)) {
            TPA_INT__LIVE_DEC();
            free(b);
        }
    }
}

/* ------------------------------------------------------------------------------------------
 * Allocation and the canonical form (invariants I1 and I2)
 * ------------------------------------------------------------------------------------------ */

/* A new big with room for n limbs and count 1, or NULL with MemoryError recorded. The limbs are not
 * initialised and the object is not yet a value: `tpa_int__finish` makes it one. */
static TPA_UNUSED tpa_big *tpa_int__alloc(size_t n)
{
    tpa_big *b;
    if (n > TPA_INT__MAX_LIMBS) {
        tpa_raise(TPA_MemoryError, "");
        return NULL;
    }
    b = (tpa_big *)malloc(offsetof(tpa_big, d) + n * sizeof(uint32_t));
    if (b == NULL) {
        tpa_raise(TPA_MemoryError, "");
        return NULL;
    }
    b->rc = 1;
    b->n = (uint32_t)n;
    b->neg = 0;
    TPA_INT__LIVE_INC();
    return b;
}

static inline void tpa_int__free_unshared(tpa_big *b)
{
    TPA_INT__LIVE_DEC();
    free(b);
}

/* Zero-extending a magnitude of at most two limbs. */
static inline uint64_t tpa_int__mag_u64(const uint32_t *d, size_t n)
{
    uint64_t m = 0;
    if (n > 0) {
        m = d[0];
    }
    if (n > 1) {
        m |= (uint64_t)d[1] << 32;
    }
    return m;
}

/* The word for the magnitude `mag` (it fits 64 bits) with sign `neg`: a small if it fits, else a new big. */
static TPA_UNUSED int tpa_int__from_mag64(uint64_t mag, int neg, tpa_int *out)
{
    tpa_big *b;
    if (neg ? mag <= 0x4000000000000000ULL : mag <= 0x3FFFFFFFFFFFFFFFULL) {
        *out = tpa_int_small((int64_t)(neg ? 0 - mag : mag));
        return 0;
    }
    b = tpa_int__alloc(2);
    if (b == NULL) {
        return -1;
    }
    b->d[0] = (uint32_t)mag;
    b->d[1] = (uint32_t)(mag >> 32);
    b->neg = neg ? 1u : 0u;
    *out = tpa_int__word(b);
    return 0;
}

/* Turns the freshly built big `b` (its limbs written, count 1) into a value: strips the zero limbs from
 * the top, and returns a small (freeing `b`) when the value fits, else `b` itself with sign `neg`. */
static TPA_UNUSED tpa_int tpa_int__finish(tpa_big *b, int neg)
{
    size_t n = b->n;
    while (n > 0 && b->d[n - 1] == 0) {
        n -= 1;
    }
    if (n <= 2) {
        uint64_t mag = tpa_int__mag_u64(b->d, n);
        if (neg ? mag <= 0x4000000000000000ULL : mag <= 0x3FFFFFFFFFFFFFFFULL) {
            tpa_int__free_unshared(b);
            return tpa_int_small((int64_t)(neg ? 0 - mag : mag));
        }
    }
    b->n = (uint32_t)n;
    b->neg = neg ? 1u : 0u;
    return tpa_int__word(b);
}

static TPA_UNUSED int tpa_int_from_i64(int64_t v, tpa_int *out)
{
    if (tpa_int__in_small_range(v)) {
        *out = tpa_int_small(v);
        return 0;
    }
    return tpa_int__from_mag64(v < 0 ? 0 - (uint64_t)v : (uint64_t)v, v < 0, out);
}

/* 1 when the value fits an int64_t. A big holds a magnitude of 2**62 or more: it fits when it has two
 * limbs and the magnitude is at most 2**63 - 1, or 2**63 for a negative value. */
static inline int tpa_int_fits_i64(tpa_int a)
{
    if (a & 1u) {
        return 1;
    }
    {
        const tpa_big *b = tpa_int__big(a);
        uint64_t mag;
        if (b->n > 2) {
            return 0;
        }
        mag = tpa_int__mag_u64(b->d, b->n);
        return b->neg ? mag <= 0x8000000000000000ULL : mag <= 0x7FFFFFFFFFFFFFFFULL;
    }
}

/* 0 and the value in *out, or 1 (no exception recorded) when it does not fit an int64_t. */
static inline int tpa_int_to_i64(tpa_int a, int64_t *out)
{
    if (a & 1u) {
        *out = tpa_int_small_value(a);
        return 0;
    }
    if (!tpa_int_fits_i64(a)) {
        return 1;
    }
    {
        const tpa_big *b = tpa_int__big(a);
        uint64_t mag = tpa_int__mag_u64(b->d, b->n);
        *out = (int64_t)(b->neg ? 0 - mag : mag);
        return 0;
    }
}

/* ------------------------------------------------------------------------------------------
 * Views and magnitude arithmetic on 32-bit limbs (normalised: no zero limb at the top; zero has no limbs)
 * ------------------------------------------------------------------------------------------ */

/* The sign and magnitude of either kind of value, so one routine serves both. A small is expanded into
 * the view's own two limbs. Use it through a pointer (`d` may point into `buf`). */
typedef struct {
    const uint32_t *d;
    size_t n;
    int neg;
    uint32_t buf[2];
} tpa_int__view;

static TPA_UNUSED void tpa_int__load(tpa_int a, tpa_int__view *v)
{
    if (a & 1u) {
        int64_t s = tpa_int_small_value(a);
        uint64_t m = s < 0 ? 0 - (uint64_t)s : (uint64_t)s;
        v->buf[0] = (uint32_t)m;
        v->buf[1] = (uint32_t)(m >> 32);
        v->n = v->buf[1] != 0 ? 2u : (v->buf[0] != 0 ? 1u : 0u);
        v->neg = s < 0;
        v->d = v->buf;
    }
    else {
        const tpa_big *b = tpa_int__big(a);
        v->d = b->d;
        v->n = b->n;
        v->neg = (int)b->neg;
    }
}

static TPA_UNUSED int tpa_int__mag_cmp(const uint32_t *a, size_t na, const uint32_t *b, size_t nb)
{
    size_t i;
    if (na != nb) {
        return na < nb ? -1 : 1;
    }
    for (i = na; i-- > 0;) {
        if (a[i] != b[i]) {
            return a[i] < b[i] ? -1 : 1;
        }
    }
    return 0;
}

/* r = a + b, na >= nb, r has room for na + 1 limbs; returns the limb count written (not normalised). */
static TPA_UNUSED size_t tpa_int__mag_add(uint32_t *r, const uint32_t *a, size_t na, const uint32_t *b, size_t nb)
{
    uint64_t carry = 0;
    size_t i;
    for (i = 0; i < na; i++) {
        uint64_t t = (uint64_t)a[i] + (i < nb ? b[i] : 0u) + carry;
        r[i] = (uint32_t)t;
        carry = t >> 32;
    }
    r[na] = (uint32_t)carry;
    return na + 1;
}

/* r = a - b, a >= b as magnitudes (na >= nb), r has room for na limbs; r may alias b. */
static TPA_UNUSED void tpa_int__mag_sub(uint32_t *r, const uint32_t *a, size_t na, const uint32_t *b, size_t nb)
{
    uint64_t borrow = 0;
    size_t i;
    for (i = 0; i < na; i++) {
        uint64_t t = (uint64_t)a[i] - (i < nb ? b[i] : 0u) - borrow;
        r[i] = (uint32_t)t;
        borrow = (t >> 63) & 1u;      /* the true difference is at least -(2**32), so a wrap sets bit 63 */
    }
}

/* r = a * b, schoolbook; r has room for na + nb limbs and is fully written. Every partial sum
 * a[i] * b[j] + r[i + j] + carry is at most (2**32 - 1)**2 + 2 * (2**32 - 1) = 2**64 - 1: no overflow. */
static TPA_UNUSED void tpa_int__mag_mul(uint32_t *r, const uint32_t *a, size_t na, const uint32_t *b, size_t nb)
{
    size_t i, j;
    for (i = 0; i < na + nb; i++) {
        r[i] = 0;
    }
    for (i = 0; i < na; i++) {
        uint64_t carry = 0;
        for (j = 0; j < nb; j++) {
            uint64_t t = (uint64_t)a[i] * b[j] + r[i + j] + carry;
            r[i + j] = (uint32_t)t;
            carry = t >> 32;
        }
        r[i + nb] = (uint32_t)carry;
    }
}

static TPA_UNUSED unsigned tpa_int__nlz32(uint32_t x)
{
    unsigned n = 0;
    while (n < 32 && !((x >> (31 - n)) & 1u)) {
        n += 1;
    }
    return n;
}

/* Knuth's algorithm D (Hacker's Delight divmnu, with unsigned borrows). Requires m >= n >= 1, v[n-1] != 0.
 * q has room for m - n + 1 limbs, r for n limbs, both fully written (not normalised). Returns 0, or -1
 * with MemoryError (the normalised copies of the operands live in one scratch allocation). */
static TPA_UNUSED int tpa_int__mag_divmod(const uint32_t *u, size_t m, const uint32_t *v, size_t n, uint32_t *q, uint32_t *r)
{
    uint32_t *vn, *un;
    unsigned s;
    size_t i, j;
    if (n == 1) {
        uint64_t k = 0;
        for (j = m; j-- > 0;) {
            uint64_t t = (k << 32) | u[j];
            q[j] = (uint32_t)(t / v[0]);
            k = t % v[0];
        }
        r[0] = (uint32_t)k;
        return 0;
    }
    if (m + n + 1 > TPA_INT__MAX_LIMBS) {
        return tpa_raise(TPA_MemoryError, "");
    }
    vn = (uint32_t *)malloc((n + m + 1) * sizeof(uint32_t));
    if (vn == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    un = vn + n;
    s = tpa_int__nlz32(v[n - 1]);
    for (i = n - 1; i > 0; i--) {
        vn[i] = (uint32_t)(((uint64_t)v[i] << s) | ((uint64_t)v[i - 1] >> (32 - s)));
    }
    vn[0] = (uint32_t)((uint64_t)v[0] << s);
    un[m] = (uint32_t)((uint64_t)u[m - 1] >> (32 - s));
    for (i = m - 1; i > 0; i--) {
        un[i] = (uint32_t)(((uint64_t)u[i] << s) | ((uint64_t)u[i - 1] >> (32 - s)));
    }
    un[0] = (uint32_t)((uint64_t)u[0] << s);
    for (j = m - n + 1; j-- > 0;) {
        uint64_t num = ((uint64_t)un[j + n] << 32) | un[j + n - 1];
        uint64_t qhat = num / vn[n - 1];
        uint64_t rhat = num - qhat * vn[n - 1];
        uint64_t borrow = 0, carry = 0;
        /* The first test keeps qhat below 2**32, so qhat * vn[n-2] cannot overflow; rhat stays below 2**32
         * inside the loop, so (rhat << 32) cannot either. */
        while (qhat >= ((uint64_t)1 << 32) || qhat * vn[n - 2] > ((rhat << 32) | un[j + n - 2])) {
            qhat -= 1;
            rhat += vn[n - 1];
            if (rhat >= ((uint64_t)1 << 32)) {
                break;
            }
        }
        for (i = 0; i < n; i++) {
            uint64_t p = qhat * vn[i] + carry;
            uint64_t t = (uint64_t)un[i + j] - (p & 0xFFFFFFFFu) - borrow;
            carry = p >> 32;
            un[i + j] = (uint32_t)t;
            borrow = t >> 63;
        }
        {
            uint64_t t = (uint64_t)un[j + n] - carry - borrow;
            un[j + n] = (uint32_t)t;
            if ((t >> 63) != 0) {            /* qhat was one too large: add the divisor back */
                carry = 0;
                qhat -= 1;
                for (i = 0; i < n; i++) {
                    uint64_t a = (uint64_t)un[i + j] + vn[i] + carry;
                    un[i + j] = (uint32_t)a;
                    carry = a >> 32;
                }
                un[j + n] = (uint32_t)((uint64_t)un[j + n] + carry);
            }
        }
        q[j] = (uint32_t)qhat;
    }
    for (i = 0; i + 1 < n; i++) {
        r[i] = (uint32_t)(((uint64_t)un[i] >> s) | ((uint64_t)un[i + 1] << (32 - s)));
    }
    r[n - 1] = (uint32_t)((uint64_t)un[n - 1] >> s);
    free(vn);
    return 0;
}

/* dst = src << sh (src has n limbs, dst has room for n + sh / 32 + 1); returns the normalised limb count. */
static TPA_UNUSED size_t tpa_int__mag_shl(uint32_t *dst, const uint32_t *src, size_t n, uint64_t sh)
{
    size_t q = (size_t)(sh / 32), i;
    unsigned r = (unsigned)(sh % 32);
    uint64_t carry = 0;
    size_t len;
    for (i = 0; i < q; i++) {
        dst[i] = 0;
    }
    for (i = 0; i < n; i++) {
        uint64_t t = ((uint64_t)src[i] << r) | carry;
        dst[i + q] = (uint32_t)t;
        carry = t >> 32;
    }
    dst[n + q] = (uint32_t)carry;
    len = n + q + 1;
    while (len > 0 && dst[len - 1] == 0) {
        len -= 1;
    }
    return len;
}

/* The number of bits of a normalised magnitude (0 for zero). */
static TPA_UNUSED uint64_t tpa_int__mag_bitlen(const uint32_t *d, size_t n)
{
    if (n == 0) {
        return 0;
    }
    return (uint64_t)(n - 1) * 32 + (32 - tpa_int__nlz32(d[n - 1]));
}

/* (magnitude >> sh) as a uint64_t: the caller guarantees the result is below 2**64 (here: below 2**56). */
static TPA_UNUSED uint64_t tpa_int__mag_shr_u64(const uint32_t *d, size_t n, uint64_t sh)
{
    size_t q = (size_t)(sh / 32);
    unsigned r = (unsigned)(sh % 32);
    uint64_t l0 = q < n ? d[q] : 0u, l1 = q + 1 < n ? d[q + 1] : 0u, l2 = q + 2 < n ? d[q + 2] : 0u;
    uint64_t res = (l0 | (l1 << 32)) >> r;
    if (r != 0) {
        res |= l2 << (64 - r);
    }
    return res;
}

/* 1 when any of the low `bits` bits of the magnitude is set. */
static TPA_UNUSED int tpa_int__mag_low_nonzero(const uint32_t *d, size_t n, uint64_t bits)
{
    size_t whole = (size_t)(bits / 32), i;
    unsigned part = (unsigned)(bits % 32);
    for (i = 0; i < whole && i < n; i++) {
        if (d[i] != 0) {
            return 1;
        }
    }
    if (part != 0 && whole < n) {
        return (d[whole] & (((uint32_t)1 << part) - 1u)) != 0;
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Addition, subtraction, negation, absolute value, multiplication
 * ------------------------------------------------------------------------------------------ */

/* a + b, or a - b with `sub` set; the general case (sign and magnitude). */
static TPA_UNUSED int tpa_int__addsub(tpa_int a, tpa_int b, int sub, tpa_int *out)
{
    tpa_int__view x, y;
    tpa_big *r;
    int yneg, neg;
    tpa_int__load(a, &x);
    tpa_int__load(b, &y);
    yneg = y.n == 0 ? 0 : (y.neg ^ sub);
    if (x.neg == yneg) {
        const tpa_int__view *hi = x.n >= y.n ? &x : &y, *lo = x.n >= y.n ? &y : &x;
        r = tpa_int__alloc(hi->n + 1);
        if (r == NULL) {
            return -1;
        }
        (void)tpa_int__mag_add(r->d, hi->d, hi->n, lo->d, lo->n);
        neg = x.neg;
    }
    else {
        int c = tpa_int__mag_cmp(x.d, x.n, y.d, y.n);
        if (c == 0) {
            *out = tpa_int_small(0);
            return 0;
        }
        if (c > 0) {
            r = tpa_int__alloc(x.n);
            if (r == NULL) {
                return -1;
            }
            tpa_int__mag_sub(r->d, x.d, x.n, y.d, y.n);
            neg = x.neg;
        }
        else {
            r = tpa_int__alloc(y.n);
            if (r == NULL) {
                return -1;
            }
            tpa_int__mag_sub(r->d, y.d, y.n, x.d, x.n);
            neg = yneg;
        }
    }
    *out = tpa_int__finish(r, neg);
    return 0;
}

/* Two smalls: the sum of two values in [-2**62, 2**62 - 1] lies in [-2**63, 2**63 - 2], so it cannot
 * overflow an int64_t; it is small when it is back inside the small range. */
static inline int tpa_int_add(tpa_int a, tpa_int b, tpa_int *out)
{
    if (a & b & 1u) {
        int64_t s = (int64_t)((uint64_t)tpa_int_small_value(a) + (uint64_t)tpa_int_small_value(b));
        if (tpa_int__in_small_range(s)) {
            *out = tpa_int_small(s);
            return 0;
        }
    }
    return tpa_int__addsub(a, b, 0, out);
}

static inline int tpa_int_sub(tpa_int a, tpa_int b, tpa_int *out)
{
    if (a & b & 1u) {
        int64_t s = (int64_t)((uint64_t)tpa_int_small_value(a) - (uint64_t)tpa_int_small_value(b));
        if (tpa_int__in_small_range(s)) {
            *out = tpa_int_small(s);
            return 0;
        }
    }
    return tpa_int__addsub(a, b, 1, out);
}

static TPA_UNUSED int tpa_int__mul_slow(tpa_int a, tpa_int b, tpa_int *out)
{
    tpa_int__view x, y;
    tpa_big *r;
    tpa_int__load(a, &x);
    tpa_int__load(b, &y);
    if (x.n == 0 || y.n == 0) {
        *out = tpa_int_small(0);
        return 0;
    }
    if (x.n + y.n <= 2) {
        /* both fit 32 bits: the product fits 64 and needs no allocation of scratch */
        return tpa_int__from_mag64((uint64_t)x.d[0] * y.d[0], x.neg != y.neg, out);
    }
    r = tpa_int__alloc(x.n + y.n);
    if (r == NULL) {
        return -1;
    }
    tpa_int__mag_mul(r->d, x.d, x.n, y.d, y.n);
    *out = tpa_int__finish(r, x.neg != y.neg);
    return 0;
}

/* Two smalls whose values both lie in [-2**31, 2**31 - 1]: the product has magnitude at most 2**62, so
 * the int64_t product cannot overflow; every other pair takes the general path. */
static inline int tpa_int_mul(tpa_int a, tpa_int b, tpa_int *out)
{
    if (a & b & 1u) {
        int64_t x = tpa_int_small_value(a), y = tpa_int_small_value(b);
        if ((uint64_t)(x + 2147483648LL) <= 0xFFFFFFFFu && (uint64_t)(y + 2147483648LL) <= 0xFFFFFFFFu) {
            int64_t p = (int64_t)((uint64_t)x * (uint64_t)y);
            if (tpa_int__in_small_range(p)) {
                *out = tpa_int_small(p);
                return 0;
            }
            return tpa_int_from_i64(p, out);
        }
    }
    return tpa_int__mul_slow(a, b, out);
}

static TPA_UNUSED int tpa_int__copy_signed(tpa_int a, int neg, tpa_int *out)
{
    const tpa_big *s = tpa_int__big(a);
    tpa_big *r = tpa_int__alloc(s->n);
    if (r == NULL) {
        return -1;
    }
    memcpy(r->d, s->d, (size_t)s->n * sizeof(uint32_t));
    *out = tpa_int__finish(r, neg);
    return 0;
}

/* -a. Negating -2**62 gives 2**62 (a big); negating a big 2**62 gives -2**62 (a small). */
static inline int tpa_int_neg(tpa_int a, tpa_int *out)
{
    if (a & 1u) {
        return tpa_int_from_i64((int64_t)(0 - (uint64_t)tpa_int_small_value(a)), out);
    }
    return tpa_int__copy_signed(a, !tpa_int__big(a)->neg, out);
}

/* |a|: a non-negative value is returned as it is (a new reference), a negative big is copied with the sign
 * cleared (|-2**62| = 2**62 is a big, |-(2**62 + 1)| a big), and |small| goes through the int64 path. */
static inline int tpa_int_abs(tpa_int a, tpa_int *out)
{
    if (a & 1u) {
        int64_t v = tpa_int_small_value(a);
        if (v >= 0) {
            *out = a;
            return 0;
        }
        return tpa_int_from_i64((int64_t)(0 - (uint64_t)v), out);
    }
    if (!tpa_int__big(a)->neg) {
        tpa_int_retain(a);
        *out = a;
        return 0;
    }
    return tpa_int__copy_signed(a, 0, out);
}

/* ------------------------------------------------------------------------------------------
 * Floor division and modulo (CPython: the remainder has the sign of the divisor)
 * ------------------------------------------------------------------------------------------ */

/* q and r each may be NULL (not wanted). Truncated division of the magnitudes, then the floor correction:
 * when the signs differ and the remainder is not zero, q = -(|q| + 1) and r = |b| - |r| with b's sign. */
static TPA_UNUSED int tpa_int__divmod(tpa_int a, tpa_int b, tpa_int *q, tpa_int *r)
{
    tpa_int__view x, y;
    tpa_big *qb, *rb;
    size_t qn, i;
    int qneg, rzero;
    tpa_int__load(a, &x);
    tpa_int__load(b, &y);
    if (y.n == 0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    if (x.n == 0) {
        if (q != NULL) {
            *q = tpa_int_small(0);
        }
        if (r != NULL) {
            *r = tpa_int_small(0);
        }
        return 0;
    }
    qn = x.n >= y.n ? x.n - y.n + 2 : 2;           /* one spare limb for the +1 of the floor correction */
    qb = tpa_int__alloc(qn);
    if (qb == NULL) {
        return -1;
    }
    rb = tpa_int__alloc(y.n);
    if (rb == NULL) {
        tpa_int__free_unshared(qb);
        return -1;
    }
    for (i = 0; i < qn; i++) {
        qb->d[i] = 0;
    }
    if (tpa_int__mag_cmp(x.d, x.n, y.d, y.n) < 0) {
        for (i = 0; i < y.n; i++) {
            rb->d[i] = i < x.n ? x.d[i] : 0u;
        }
    }
    else if (tpa_int__mag_divmod(x.d, x.n, y.d, y.n, qb->d, rb->d) != 0) {
        tpa_int__free_unshared(qb);
        tpa_int__free_unshared(rb);
        return -1;
    }
    rzero = 1;
    for (i = 0; i < y.n; i++) {
        if (rb->d[i] != 0) {
            rzero = 0;
            break;
        }
    }
    qneg = x.neg != y.neg;
    if (qneg && !rzero) {
        uint64_t carry = 1;
        for (i = 0; i < qn && carry != 0; i++) {
            uint64_t t = (uint64_t)qb->d[i] + carry;
            qb->d[i] = (uint32_t)t;
            carry = t >> 32;
        }
        tpa_int__mag_sub(rb->d, y.d, y.n, rb->d, y.n);
    }
    if (q != NULL) {
        *q = tpa_int__finish(qb, qneg);
    }
    else {
        tpa_int__free_unshared(qb);
    }
    if (r != NULL) {
        *r = tpa_int__finish(rb, y.neg);
    }
    else {
        tpa_int__free_unshared(rb);
    }
    return 0;
}

/* Two smalls: a // b and a % b in int64_t (|a| <= 2**62, so no INT64_MIN / -1); only -2**62 // -1 = 2**62
 * is not small. `ok` is 0 when the general path has to answer (b is zero). */
static inline int tpa_int__small_divmod(tpa_int a, tpa_int b, int64_t *q, int64_t *r)
{
    int64_t x = tpa_int_small_value(a), y = tpa_int_small_value(b), qq, rr;
    if (y == 0) {
        return 0;
    }
    qq = x / y;
    rr = x % y;
    if (rr != 0 && ((rr < 0) != (y < 0))) {
        qq -= 1;
        rr += y;
    }
    *q = qq;
    *r = rr;
    return 1;
}

static inline int tpa_int_floordiv(tpa_int a, tpa_int b, tpa_int *out)
{
    int64_t q, r;
    if ((a & b & 1u) && tpa_int__small_divmod(a, b, &q, &r)) {
        return tpa_int_from_i64(q, out);
    }
    return tpa_int__divmod(a, b, out, NULL);
}

static inline int tpa_int_mod(tpa_int a, tpa_int b, tpa_int *out)
{
    int64_t q, r;
    if ((a & b & 1u) && tpa_int__small_divmod(a, b, &q, &r)) {
        *out = tpa_int_small(r);
        return 0;
    }
    return tpa_int__divmod(a, b, NULL, out);
}

/* divmod(a, b): *q and *r both written on 0, neither on -1. */
static inline int tpa_int_divmod(tpa_int a, tpa_int b, tpa_int *q, tpa_int *r)
{
    int64_t qq, rr;
    if ((a & b & 1u) && tpa_int__small_divmod(a, b, &qq, &rr)) {
        tpa_int qw;
        if (tpa_int_from_i64(qq, &qw) != 0) {
            return -1;
        }
        *q = qw;
        *r = tpa_int_small(rr);
        return 0;
    }
    return tpa_int__divmod(a, b, q, r);
}

/* ------------------------------------------------------------------------------------------
 * Comparison
 * ------------------------------------------------------------------------------------------ */
static TPA_UNUSED int tpa_int__cmp_slow(tpa_int a, tpa_int b)
{
    tpa_int__view x, y;
    int c;
    tpa_int__load(a, &x);
    tpa_int__load(b, &y);
    if (x.neg != y.neg) {
        return x.neg ? -1 : 1;
    }
    c = tpa_int__mag_cmp(x.d, x.n, y.d, y.n);
    return x.neg ? -c : c;
}

/* -1, 0 or 1. Two smalls: the signed comparison of the values. A small and a big: the big is outside the
 * small range, so its sign decides (the general path compares sign, then magnitude). */
static inline int tpa_int_cmp(tpa_int a, tpa_int b)
{
    if (a & b & 1u) {
        int64_t x = tpa_int_small_value(a), y = tpa_int_small_value(b);
        return x < y ? -1 : (x > y ? 1 : 0);
    }
    return tpa_int__cmp_slow(a, b);
}

/* Equal values have one representation (I1): equal when the words are equal, unless both are bigs. */
static inline int tpa_int_eq(tpa_int a, tpa_int b)
{
    if ((a | b) & 1u) {
        return a == b;
    }
    return tpa_int__cmp_slow(a, b) == 0;
}

/* Exact comparison of an int with a double: -1 (a < x), 0, 1, or TPA_INT_UNORDERED (x is NaN: every
 * ordering test of CPython is false then, `==` is false and `!=` is true). An infinity is above or below
 * every int; -0.0 equals 0. A big is never converted to double: its sign and bit length are compared with
 * the double's exponent, and when they agree the magnitudes are compared as integers (the double is then
 * an integer of at least 63 bits, built from its 53-bit significand). */
static TPA_UNUSED int tpa_int_cmp_f64(tpa_int a, double x)
{
    int aneg, e;
    double ax, m;
    uint64_t n;
    int res;
    if (x != x) {
        return TPA_INT_UNORDERED;
    }
    if (isinf(x)) {
        return x > 0 ? -1 : 1;
    }
    if (a & 1u) {
        int64_t v = tpa_int_small_value(a), t;
        double frac;
        if (x >= 9223372036854775808.0) {
            return -1;
        }
        if (x <= -9223372036854775808.0) {
            return 1;
        }
        t = (int64_t)x;                 /* truncation toward zero, in range, exact */
        frac = x - (double)t;           /* exact */
        if (v < t) {
            return -1;
        }
        if (v > t) {
            return 1;
        }
        return frac > 0 ? -1 : (frac < 0 ? 1 : 0);
    }
    aneg = (int)tpa_int__big(a)->neg;
    if (x == 0.0 || (x < 0) != (aneg != 0)) {
        return aneg ? -1 : 1;
    }
    ax = fabs(x);
    n = tpa_int__mag_bitlen(tpa_int__big(a)->d, tpa_int__big(a)->n);
    if (ax < 1.0) {
        res = 1;
    }
    else {
        m = frexp(ax, &e);              /* ax = m * 2**e, m in [0.5, 1): the integer part of ax has e bits */
        if (n != (uint64_t)e) {
            res = n > (uint64_t)e ? 1 : -1;
        }
        else {
            /* e == n >= 63, so ax = M * 2**(e - 53) is an integer: place the 53-bit M at that bit offset */
            uint32_t xl[34];
            const tpa_big *b = tpa_int__big(a);
            uint64_t mm = (uint64_t)ldexp(m, 53);
            size_t p = (size_t)(e - 53), q = p / 32, i;
            unsigned r = (unsigned)(p % 32);
            uint64_t v0 = (mm & 0xFFFFFFFFu) << r, v1 = (mm >> 32) << r, t1;
            for (i = 0; i < 34; i++) {
                xl[i] = 0;
            }
            xl[q] = (uint32_t)v0;
            t1 = v1 + (v0 >> 32);
            xl[q + 1] = (uint32_t)t1;
            xl[q + 2] = (uint32_t)(t1 >> 32);
            res = tpa_int__mag_cmp(b->d, b->n, xl, (size_t)b->n);   /* (xl has the same number of significant limbs) */
        }
    }
    return aneg ? -res : res;
}

/* ------------------------------------------------------------------------------------------
 * Conversion to double and true division (both correctly rounded, ties to even, as CPython's are)
 * ------------------------------------------------------------------------------------------ */

/* float(a): the 53 most significant bits are kept and the rest decides the rounding (above half: up; exactly
 * half: up when the kept part is odd; below half: down). A carry out of the 53 bits makes the significand
 * 2**52 with the exponent one higher. The result is the significand times a power of two (exact in ldexp);
 * it is finite when that power is at most 971 (the largest finite double is (2**53 - 1) * 2**971). A larger
 * value raises OverflowError (also the value exactly halfway between the largest double and 2**1024, which
 * rounds to even, that is up). Modelled first in a script that uses only integer operations, against float(int) (#460). */
static TPA_UNUSED int tpa_int__to_f64_slow(tpa_int a, double *out)
{
    tpa_int__view x;
    uint64_t nb, shift, mant;
    double r;
    tpa_int__load(a, &x);
    nb = tpa_int__mag_bitlen(x.d, x.n);
    if (nb <= 53) {
        r = (double)tpa_int__mag_u64(x.d, x.n);          /* below 2**53: exact */
    }
    else {
        int half;
        shift = nb - 53;
        mant = tpa_int__mag_shr_u64(x.d, x.n, shift);
        half = (int)(tpa_int__mag_shr_u64(x.d, x.n, shift - 1) & 1u);
        if (half && (tpa_int__mag_low_nonzero(x.d, x.n, shift - 1) || (mant & 1u))) {
            mant += 1;
        }
        if (mant == ((uint64_t)1 << 53)) {
            mant >>= 1;
            shift += 1;
        }
        if (shift > 971) {
            return tpa_raise(TPA_OverflowError, TPA_MSG_INT_TO_FLOAT);
        }
        r = ldexp((double)mant, (int)shift);
    }
    *out = x.neg ? -r : r;
    return 0;
}

static inline int tpa_int_to_f64(tpa_int a, double *out)
{
    if (a & 1u) {
        int64_t v = tpa_int_small_value(a);
        if (v >= -9007199254740992LL && v <= 9007199254740992LL) {       /* +-2**53: exact */
            *out = (double)v;
            return 0;
        }
    }
    return tpa_int__to_f64_slow(a, out);
}

/* a / b, the correctly rounded quotient for every input (CPython does not convert the operands to double
 * first). With A = |a| of eA bits and B = |b| of eB bits, s = 55 + eB - eA: Q = floor(A * 2**s / B) (or
 * floor(A / (B * 2**-s)) for a negative s) has 55 or 56 bits, and A * 2**s / B = (Q + f) / 2**s ... with
 * f in [0, 1), f > 0 exactly when the division left a remainder (the sticky bit). The result keeps d bits
 * fewer than Q has, where d is 55 or 56 minus 53, or more when the result is subnormal (the least kept bit
 * must weigh at least 2**-1074, that is d >= s - 1074). The rounding is then the same as in to_f64. A quotient
 * whose rounded value is 2**1024 or more raises OverflowError; division by zero raises ZeroDivisionError.
 * Modelled first in a script that uses only integer operations, against a / b (#460). */
static TPA_UNUSED int tpa_int__truediv(tpa_int a, tpa_int b, double *out)
{
    tpa_int__view x, y;
    int neg;
    int64_t s, d, e;
    uint64_t eA, eB, Q, mant, nb, up;
    const uint32_t *num, *den;
    uint32_t *shifted, *qr, *q, *rem;
    size_t m, n, cap, qlen, i;
    int sticky, half, rest;
    double r;
    tpa_int__load(a, &x);
    tpa_int__load(b, &y);
    if (y.n == 0) {
        return tpa_raise(TPA_ZeroDivisionError, TPA_MSG_DIVISION_ZERO);
    }
    neg = x.neg != y.neg;
    if (x.n == 0) {
        *out = copysign(0.0, neg ? -1.0 : 1.0);
        return 0;
    }
    eA = tpa_int__mag_bitlen(x.d, x.n);
    eB = tpa_int__mag_bitlen(y.d, y.n);
    s = 55 + (int64_t)eB - (int64_t)eA;
    if (s >= 0) {
        cap = (size_t)x.n + (size_t)((uint64_t)s / 32) + 1;
    }
    else {
        cap = (size_t)y.n + (size_t)((uint64_t)(-s) / 32) + 1;
    }
    if (cap > TPA_INT__MAX_LIMBS) {
        return tpa_raise(TPA_MemoryError, "");
    }
    shifted = (uint32_t *)malloc(cap * sizeof(uint32_t));
    if (shifted == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    if (s >= 0) {
        m = tpa_int__mag_shl(shifted, x.d, x.n, (uint64_t)s);
        num = shifted;
        den = y.d;
        n = y.n;
    }
    else {
        n = tpa_int__mag_shl(shifted, y.d, y.n, (uint64_t)(-s));
        num = x.d;
        den = shifted;
        m = x.n;
    }
    /* the numerator has the bit length 55 + eB (s >= 0) or eA, the divisor eB or eA - 55: m >= n */
    qr = (uint32_t *)malloc((m + 1) * sizeof(uint32_t));
    if (qr == NULL) {
        free(shifted);
        return tpa_raise(TPA_MemoryError, "");
    }
    q = qr;
    rem = qr + (m - n + 1);
    if (tpa_int__mag_divmod(num, m, den, n, q, rem) != 0) {
        free(qr);
        free(shifted);
        return -1;
    }
    qlen = m - n + 1;
    Q = q[0];
    if (qlen > 1) {
        Q |= (uint64_t)q[1] << 32;       /* Q < 2**56: the limbs above are zero */
    }
    sticky = 0;
    for (i = 0; i < n; i++) {
        if (rem[i] != 0) {
            sticky = 1;
            break;
        }
    }
    free(qr);
    free(shifted);
    nb = 0;
    while (nb < 64 && (Q >> nb) != 0) {
        nb += 1;
    }
    d = (int64_t)nb - 53;
    if (s - 1074 > d) {
        d = s - 1074;
    }
    if (d > (int64_t)nb) {
        mant = 0;
        up = 0;
    }
    else {
        mant = Q >> d;
        half = (int)((Q >> (d - 1)) & 1u);
        rest = (Q & (((uint64_t)1 << (d - 1)) - 1u)) != 0 || sticky;
        up = (half && (rest || (mant & 1u))) ? 1u : 0u;
    }
    mant += up;
    e = d - s;
    if (e > 2300) {
        e = 2300;
    }
    if (e < -2300) {
        e = -2300;
    }
    r = ldexp((double)mant, (int)e);     /* exact: mant <= 2**53 and the result is a multiple of 2**-1074 */
    if (isinf(r)) {
        return tpa_raise(TPA_OverflowError, TPA_MSG_INT_TRUEDIV_OVERFLOW);
    }
    *out = neg ? -r : r;
    return 0;
}

/* a / b of two ints as a double (true division). Two smalls inside +-2**53 convert exactly, and the C
 * division of two exact doubles is the correctly rounded quotient. */
static inline int tpa_int_truediv(tpa_int a, tpa_int b, double *out)
{
    if (a & b & 1u) {
        int64_t x = tpa_int_small_value(a), y = tpa_int_small_value(b);
        if (y != 0 && x >= -9007199254740992LL && x <= 9007199254740992LL
            && y >= -9007199254740992LL && y <= 9007199254740992LL) {
            *out = (double)x / (double)y;
            return 0;
        }
    }
    return tpa_int__truediv(a, b, out);
}

/* ------------------------------------------------------------------------------------------
 * Decimal strings
 * ------------------------------------------------------------------------------------------ */

/* str(a) as a malloc'd NUL-terminated string (the caller frees it with free) and its length. CPython's
 * limit: more than 4300 decimal digits (the sign is not counted) raises ValueError. CPython refuses a value
 * of 481 or more 30-bit digits at once and counts the digits of a smaller one; both end in the same message
 * (the 3.15 reference gives one message for this direction), so the rule is "more than 4300 digits". A value
 * of more than 14300 bits has more than 4300 digits (bit length of 10**4300 is 14285), which spares the
 * conversion of a huge value that is going to be refused. */
static TPA_UNUSED int tpa_int_to_str(tpa_int a, char **out, size_t *len)
{
    char *buf;
    size_t total, pos;
    if (a & 1u) {
        int64_t v = tpa_int_small_value(a);
        uint64_t mag = v < 0 ? 0 - (uint64_t)v : (uint64_t)v;
        char tmp[24];
        size_t k = 0;
        do {
            tmp[k++] = (char)('0' + (int)(mag % 10u));
            mag /= 10u;
        } while (mag != 0);
        total = k + (v < 0 ? 1u : 0u);
        buf = (char *)malloc(total + 1);
        if (buf == NULL) {
            return tpa_raise(TPA_MemoryError, "");
        }
        pos = 0;
        if (v < 0) {
            buf[pos++] = '-';
        }
        while (k > 0) {
            buf[pos++] = tmp[--k];
        }
        buf[pos] = '\0';
        *out = buf;
        *len = total;
        return 0;
    }
    else {
        const tpa_big *b = tpa_int__big(a);
        size_t n = b->n, cap, wn, nch = 0, i, top;
        uint32_t *w, *ch;
        uint64_t digits;
        if (tpa_int__mag_bitlen(b->d, b->n) > 14300) {
            return tpa_raise(TPA_ValueError, TPA_MSG_INT_TO_STR_LIMIT);
        }
        cap = n + n / 8 + 2;            /* chunks of 10**9 (about 29.9 bits each) */
        w = (uint32_t *)malloc((n + cap) * sizeof(uint32_t));
        if (w == NULL) {
            return tpa_raise(TPA_MemoryError, "");
        }
        ch = w + n;
        memcpy(w, b->d, n * sizeof(uint32_t));
        wn = n;
        while (wn > 0) {
            uint64_t rem = 0;
            for (i = wn; i-- > 0;) {
                uint64_t cur = (rem << 32) | w[i];
                w[i] = (uint32_t)(cur / 1000000000u);
                rem = cur % 1000000000u;
            }
            while (wn > 0 && w[wn - 1] == 0) {
                wn -= 1;
            }
            ch[nch++] = (uint32_t)rem;
        }
        top = 1;
        for (i = 10; i <= 1000000000u && (uint64_t)ch[nch - 1] >= i; i *= 10) {
            top += 1;
        }
        digits = (uint64_t)(nch - 1) * 9 + top;
        if (digits > TPA_INT_MAX_STR_DIGITS) {
            free(w);
            return tpa_raise(TPA_ValueError, TPA_MSG_INT_TO_STR_LIMIT);
        }
        total = (size_t)digits + (b->neg ? 1u : 0u);
        buf = (char *)malloc(total + 1);
        if (buf == NULL) {
            free(w);
            return tpa_raise(TPA_MemoryError, "");
        }
        pos = 0;
        if (b->neg) {
            buf[pos++] = '-';
        }
        for (i = nch; i-- > 0;) {
            uint32_t c = ch[i];
            size_t width = i == nch - 1 ? top : 9, k;
            for (k = width; k-- > 0;) {
                buf[pos + k] = (char)('0' + (int)(c % 10u));
                c /= 10u;
            }
            pos += width;
        }
        buf[pos] = '\0';
        free(w);
        *out = buf;
        *len = total;
        return 0;
    }
}

/* Unicode decimal digits (category Nd) of Unicode 17.0 (the data of the 3.15 reference interpreter): the
 * code point of the digit zero of each run of ten. A newer or older Unicode version may differ: an
 * agent proposal, not owner-confirmed, with the test that pins the version (test_aot_int.py). */
static const uint32_t tpa_int__nd_zero[77] = {
    0x30, 0x660, 0x6F0, 0x7C0, 0x966, 0x9E6, 0xA66, 0xAE6, 0xB66, 0xBE6, 0xC66, 0xCE6, 0xD66, 0xDE6, 0xE50, 0xED0,
    0xF20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80, 0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620,
    0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0, 0xFF10, 0x104A0, 0x10D30, 0x10D40, 0x11066, 0x110F0, 0x11136,
    0x111D0, 0x112F0, 0x11450, 0x114D0, 0x11650, 0x116C0, 0x116D0, 0x116DA, 0x11730, 0x118E0, 0x11950, 0x11BF0,
    0x11C50, 0x11D50, 0x11DA0, 0x11DE0, 0x11F50, 0x16130, 0x16A60, 0x16AC0, 0x16B50, 0x16D70, 0x1CCF0, 0x1D7CE,
    0x1D7D8, 0x1D7E2, 0x1D7EC, 0x1D7F6, 0x1E140, 0x1E2F0, 0x1E4F0, 0x1E5F1, 0x1E950, 0x1FBF0
};

/* The next code point of strictly valid UTF-8, or -1 for a malformed sequence (truncated, overlong, a
 * surrogate, above U+10FFFF, a stray continuation byte). */
static TPA_UNUSED int64_t tpa_int__utf8_next(const unsigned char *s, size_t n, size_t *i)
{
    unsigned c = s[*i];
    size_t need, k;
    uint32_t cp, lo = 0x80, hi = 0xBF;
    if (c < 0x80) {
        *i += 1;
        return (int64_t)c;
    }
    if (c >= 0xC2 && c <= 0xDF) {
        need = 1;
        cp = c & 0x1Fu;
    }
    else if (c >= 0xE0 && c <= 0xEF) {
        need = 2;
        cp = c & 0x0Fu;
        if (c == 0xE0) {
            lo = 0xA0;
        }
        if (c == 0xED) {
            hi = 0x9F;
        }
    }
    else if (c >= 0xF0 && c <= 0xF4) {
        need = 3;
        cp = c & 0x07u;
        if (c == 0xF0) {
            lo = 0x90;
        }
        if (c == 0xF4) {
            hi = 0x8F;
        }
    }
    else {
        return -1;
    }
    if (need > n - *i - 1) {              /* truncated: fewer than `need` bytes follow the lead byte */
        return -1;
    }
    for (k = 1; k <= need; k++) {
        unsigned cc = s[*i + k];
        if (cc < (k == 1 ? lo : 0x80u) || cc > (k == 1 ? hi : 0xBFu)) {
            return -1;
        }
        cp = (cp << 6) | (cc & 0x3Fu);
    }
    *i += need + 1;
    return (int64_t)cp;
}

/* int(s) for a str given as UTF-8 bytes (base 10 only), exactly what the 3.15 reference does for the
 * inputs it handles:
 *   - surrounding whitespace is stripped: ASCII 9 to 13 and 32, and U+0085, U+00A0, U+1680, U+2000 to
 *     U+200A, U+2028, U+2029, U+202F, U+205F, U+3000 (the set the reference accepts, found by trying every
 *     code point; ASCII 0x1C to 0x1F are NOT whitespace for int());
 *   - then an optional + or -, then digits with single underscores between digits; leading zeros allowed;
 *     Unicode decimal digits (Nd) count as the digits they are;
 *   - nothing else: an embedded NUL, a sign followed by whitespace, "1__0", "_1", "1_", "0x10", "" ...
 *     raise ValueError "invalid literal for int() with base 10: " followed by the first 200 characters of the
 *     reference's repr(s);
 *   - more than 4300 digits (underscores and the sign not counted), in an otherwise valid string, raise the
 *     reference's ValueError of this direction (it names the digit count). An invalid string with more
 *     than 4300 digits is an invalid literal, not a limit error (the reference checks the grammar first).
 * Returns 0 (*out is a new reference), -1 (an exception is recorded) or TPA_INT_NOT_HANDLED, with no
 * exception recorded, for the inputs this function cannot decide exactly:
 *   - bytes that are not strictly valid UTF-8 (a str cannot hold them);
 *   - a string that is INVALID (not an int literal) and contains a non-ASCII character: the message needs
 *     the reference's repr, which shows a non-ASCII character as it is when it is printable and escaped
 *     otherwise, and that needs the Unicode printable table, which is not embedded here.
 * A string that holds non-ASCII characters and IS valid (whitespace, digits) is handled exactly. */
static TPA_UNUSED int tpa_int_from_str(const char *bytes, size_t n, tpa_int *out)
{
    const unsigned char *s = (const unsigned char *)bytes;
    unsigned char *t;
    size_t i = 0, m = 0, p, start, dend;
    int ascii_only = 1, neg = 0;
    uint64_t ndig = 0;
    t = (unsigned char *)malloc(n + 1);
    if (t == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    while (i < n) {
        int64_t cp = tpa_int__utf8_next(s, n, &i);
        unsigned char c = '?';
        if (cp < 0) {
            free(t);
            return TPA_INT_NOT_HANDLED;
        }
        if (cp < 128) {
            if ((cp >= 9 && cp <= 13) || cp == 32) {
                c = ' ';
            }
            else if ((cp >= '0' && cp <= '9') || cp == '_' || cp == '+' || cp == '-') {
                c = (unsigned char)cp;
            }
        }
        else {
            size_t k;
            ascii_only = 0;
            if (cp == 0x85 || cp == 0xA0 || cp == 0x1680 || (cp >= 0x2000 && cp <= 0x200A) || cp == 0x2028
                || cp == 0x2029 || cp == 0x202F || cp == 0x205F || cp == 0x3000) {
                c = ' ';
            }
            else {
                for (k = 1; k < sizeof tpa_int__nd_zero / sizeof tpa_int__nd_zero[0]; k++) {
                    if (cp >= (int64_t)tpa_int__nd_zero[k] && cp <= (int64_t)tpa_int__nd_zero[k] + 9) {
                        c = (unsigned char)('0' + (cp - (int64_t)tpa_int__nd_zero[k]));
                        break;
                    }
                }
            }
        }
        t[m++] = c;
    }
    /* grammar: ws* [+-]? digit ((_)? digit)* ws* */
    p = 0;
    while (p < m && t[p] == ' ') {
        p += 1;
    }
    if (p < m && (t[p] == '+' || t[p] == '-')) {
        neg = t[p] == '-';
        p += 1;
    }
    start = p;
    if (p < m && t[p] >= '0' && t[p] <= '9') {
        for (;;) {
            if (p < m && t[p] >= '0' && t[p] <= '9') {
                ndig += 1;
                p += 1;
            }
            else if (p + 1 < m && t[p] == '_' && t[p + 1] >= '0' && t[p + 1] <= '9') {
                p += 1;
            }
            else {
                break;
            }
        }
        dend = p;
        while (p < m && t[p] == ' ') {
            p += 1;
        }
        if (p != m) {
            ndig = 0;                   /* trailing garbage: invalid */
        }
    }
    else {
        dend = start;
    }
    if (ndig == 0) {
        free(t);
        if (!ascii_only) {
            return TPA_INT_NOT_HANDLED;
        }
        {
            char msg[512];
            char rep[208];
            size_t k = 0, plen = sizeof TPA_MSG_INT_INVALID_LITERAL - 1, j;
            int has_single = 0, has_double = 0;
            char quote;
            static const char hex[] = "0123456789abcdef";
            for (j = 0; j < n; j++) {
                has_single |= s[j] == '\'';
                has_double |= s[j] == '"';
            }
            quote = (has_single && !has_double) ? '"' : '\'';
#define TPA_INT__PUSH(ch) do { if (k < 200) { rep[k++] = (char)(ch); } } while (0)
            TPA_INT__PUSH(quote);
            for (j = 0; j < n; j++) {
                unsigned c = s[j];
                if (c == (unsigned)quote || c == '\\') {
                    TPA_INT__PUSH('\\');
                    TPA_INT__PUSH(c);
                }
                else if (c == '\t') {
                    TPA_INT__PUSH('\\');
                    TPA_INT__PUSH('t');
                }
                else if (c == '\n') {
                    TPA_INT__PUSH('\\');
                    TPA_INT__PUSH('n');
                }
                else if (c == '\r') {
                    TPA_INT__PUSH('\\');
                    TPA_INT__PUSH('r');
                }
                else if (c < 32 || c == 127) {
                    TPA_INT__PUSH('\\');
                    TPA_INT__PUSH('x');
                    TPA_INT__PUSH(hex[c >> 4]);
                    TPA_INT__PUSH(hex[c & 15u]);
                }
                else {
                    TPA_INT__PUSH(c);
                }
            }
            TPA_INT__PUSH(quote);
#undef TPA_INT__PUSH
            memcpy(msg, TPA_MSG_INT_INVALID_LITERAL, plen);
            memcpy(msg + plen, rep, k);
            msg[plen + k] = '\0';
            return tpa_raise(TPA_ValueError, msg);
        }
    }
    if (ndig > TPA_INT_MAX_STR_DIGITS) {
        char msg[256];
        free(t);
        snprintf(msg, sizeof msg, TPA_MSG_INT_FROM_STR_LIMIT, (unsigned long long)ndig);
        return tpa_raise(TPA_ValueError, msg);
    }
    if (ndig <= 18) {
        uint64_t mag = 0;
        for (p = start; p < dend; p++) {
            if (t[p] != '_') {
                mag = mag * 10u + (uint64_t)(t[p] - '0');
            }
        }
        free(t);
        return tpa_int__from_mag64(mag, neg, out);
    }
    else {
        tpa_big *b = tpa_int__alloc((size_t)(ndig / 9 + 3));
        size_t used = 0, l;
        if (b == NULL) {
            free(t);
            return -1;
        }
        p = start;
        while (p < dend) {
            uint32_t chunk = 0, mul = 1, k = 0;
            uint64_t carry;
            while (p < dend && k < 9) {
                if (t[p] != '_') {
                    chunk = chunk * 10u + (uint32_t)(t[p] - '0');
                    mul *= 10u;
                    k += 1;
                }
                p += 1;
            }
            carry = chunk;
            for (l = 0; l < used; l++) {
                uint64_t v = (uint64_t)b->d[l] * mul + carry;
                b->d[l] = (uint32_t)v;
                carry = v >> 32;
            }
            if (carry != 0) {
                b->d[used++] = (uint32_t)carry;
            }
        }
        free(t);
        b->n = (uint32_t)used;
        *out = tpa_int__finish(b, neg);
        return 0;
    }
}

/* ------------------------------------------------------------------------------------------
 * What the compiler needs on top of the functions above (#462; agent proposal, not owner-confirmed; the
 * representation these serve was approved by the leader on 2026-11-16, not owner-confirmed). Nothing above
 * changed meaning: test_aot_int.py pins it.
 * ------------------------------------------------------------------------------------------ */

/* The small int 0: the value a generated function gives every TAGGED slot before it holds anything, so that
 * releasing a slot that was never written is a no-op (the word 0 is not a value, I4). */
#define TPA_INT_ZERO ((tpa_int)1)

/* Release what the slot holds and leave it empty (a generated operation writes only on success, so a slot is
 * cleared before the operation: a failing one leaves nothing dangling for the cleanup point to release twice). */
static inline void tpa_int_clear(tpa_int *p)
{
    tpa_int_release(*p);
    *p = TPA_INT_ZERO;
}

/* The slot takes a new reference to `src` and gives up the one it held (the order keeps `src == *dst` safe). */
static inline void tpa_int_set(tpa_int *dst, tpa_int src)
{
    tpa_int_retain(src);
    tpa_int_release(*dst);
    *dst = src;
}

/* -1, 0 or 1: a against a plain int64. A value that does not fit int64 is beyond every int64, so its sign decides. */
static inline int tpa_int_cmp_i64(tpa_int a, int64_t v)
{
    int64_t x;
    if (tpa_int_to_i64(a, &x) == 0) {
        return x < v ? -1 : (x > v ? 1 : 0);
    }
    return tpa_int__big(a)->neg ? -1 : 1;
}

/* a <op> b for an int and a float (exact, never through a conversion of the int; a NaN makes every comparison
 * false but !=), as CPython's float_richcompare. Operator codes of tp_aot.h (TPA_LT ... TPA_GE). */
static inline int tpa_cmp_int_f64(tpa_int a, double b, int op)
{
    int c = tpa_int_cmp_f64(a, b);
    if (c == TPA_INT_UNORDERED) {
        return op == TPA_NE;
    }
    return tpa__apply_cmp(c, op);
}

static inline int tpa_cmp_f64_int(double a, tpa_int b, int op)
{
    int c = tpa_int_cmp_f64(b, a);
    if (c == TPA_INT_UNORDERED) {
        return op == TPA_NE;
    }
    return tpa__apply_cmp(-c, op);
}

/* int / int for two plain int64 operands: the C division where both lie within plus or minus 2**53 (both convert
 * exactly, so the C quotient is the correctly rounded one), else the exact big-integer division. */
static TPA_UNUSED int tpa_truediv_i64(int64_t a, int64_t b, double *out)
{
    tpa_int x, y;
    int rc;
    if (b != 0 && a >= -9007199254740992LL && a <= 9007199254740992LL
        && b >= -9007199254740992LL && b <= 9007199254740992LL) {
        *out = (double)a / (double)b;
        return 0;
    }
    if (tpa_int_from_i64(a, &x) != 0) {
        return -1;
    }
    if (tpa_int_from_i64(b, &y) != 0) {
        tpa_int_release(x);
        return -1;
    }
    rc = tpa_int_truediv(x, y, out);
    tpa_int_release(x);
    tpa_int_release(y);
    return rc;
}

/* Conversions of a TAGGED int to the index of a list, with the exceptions of the reference interpreter
 * (printed by the commands in the report of #462):
 *   [1.0][10**30], [1.0][-10**30] and the same stores       IndexError: cannot fit 'int' into an index-sized integer
 *   sys.argv[10**30]                                         the same
 *   [1.0] * 10**30, 10**30 * [1.0], [1.0] * -10**30          OverflowError: cannot fit 'int' into an index-sized integer
 *   list(range(10**30)), list(range(2**63))                  OverflowError: TPA_MSG_INT_TO_SSIZE below
 *   list(range(-10**30))                                     [] (a negative length is an empty list) */
#define TPA_MSG_INT_TO_INDEX "cannot fit 'int' into an index-sized integer"
#define TPA_MSG_INT_TO_SSIZE "Python int too large to convert to C ssize_t"

static inline int tpa_int_to_index(tpa_int a, int64_t *out)
{
    if (tpa_int_to_i64(a, out) == 0) {
        return 0;
    }
    return tpa_raise(TPA_IndexError, TPA_MSG_INT_TO_INDEX);
}

static inline int tpa_int_to_count(tpa_int a, int64_t *out)
{
    if (tpa_int_to_i64(a, out) == 0) {
        return 0;
    }
    return tpa_raise(TPA_OverflowError, TPA_MSG_INT_TO_INDEX);
}

static inline int tpa_int_to_range_len(tpa_int a, int64_t *out)
{
    if (tpa_int_to_i64(a, out) == 0) {
        return 0;
    }
    if (tpa_int__big(a)->neg) {
        *out = 0;
        return 0;
    }
    return tpa_raise(TPA_OverflowError, TPA_MSG_INT_TO_SSIZE);
}

/* tpa_slot (tp_aot.h) for a TAGGED index. */
static inline int tpa_slot_int(int64_t len, tpa_int i, int store, int64_t *slot)
{
    int64_t k;
    if (tpa_int_to_index(i, &k) != 0) {
        return -1;
    }
    return tpa_slot(len, k, store, slot);
}

/* print(n): the decimal digits of any int, with the digit limit's ValueError (nothing is written then). */
static TPA_UNUSED int tpa_print_int(tpa_int a)
{
    char *s;
    size_t n;
    if (a & 1u) {
        tpa_print_i64(tpa_int_small_value(a));
        return 0;
    }
    if (tpa_int_to_str(a, &s, &n) != 0) {
        return -1;
    }
    fwrite(s, 1, n, stdout);
    free(s);
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * repr(str) for the message of int(): the reference interpreter's rules (unicode_repr) over code points.
 * A character is shown as it is when it is printable (ASCII 0x20 to 0x7E, and from U+0080 up every character
 * that is in none of the ranges of the table) and escaped otherwise. The quote is ' unless the string holds
 * a ' and no ", then ". The quote and the backslash are escaped; \t \n \r are written so; any other character
 * below U+0020, and U+007F, is \xNN; a non-printable one is \xNN (up to U+00FF), \uNNNN (up to U+FFFF) or
 * \UNNNNNNNN, in lower-case hex. The message keeps the first 200 characters of the repr (the reference's
 * "%.200R"), counted after the escapes are written, so an escape can be cut in the middle and the closing
 * quote is missing from a long one: both found with the reference.
 * The table below is generated: python-multiplatform-ksp/tools/gen_aot_nonprintable.py rewrites it.
 * ------------------------------------------------------------------------------------------ */
/* BEGIN GENERATED: tpa_int__nonprint (python-multiplatform-ksp/tools/gen_aot_nonprintable.py) */
#define TPA_INT_UNICODE_VERSION "17.0.0"      /* the Unicode version of the table */
#define TPA_INT__NONPRINT_N 741                              /* the number of (first, last) pairs */
static const uint32_t tpa_int__nonprint[2 * TPA_INT__NONPRINT_N] = {
    0x80, 0xA0, 0xAD, 0xAD, 0x378, 0x379, 0x380, 0x383,
    0x38B, 0x38B, 0x38D, 0x38D, 0x3A2, 0x3A2, 0x530, 0x530,
    0x557, 0x558, 0x58B, 0x58C, 0x590, 0x590, 0x5C8, 0x5CF,
    0x5EB, 0x5EE, 0x5F5, 0x605, 0x61C, 0x61C, 0x6DD, 0x6DD,
    0x70E, 0x70F, 0x74B, 0x74C, 0x7B2, 0x7BF, 0x7FB, 0x7FC,
    0x82E, 0x82F, 0x83F, 0x83F, 0x85C, 0x85D, 0x85F, 0x85F,
    0x86B, 0x86F, 0x890, 0x896, 0x8E2, 0x8E2, 0x984, 0x984,
    0x98D, 0x98E, 0x991, 0x992, 0x9A9, 0x9A9, 0x9B1, 0x9B1,
    0x9B3, 0x9B5, 0x9BA, 0x9BB, 0x9C5, 0x9C6, 0x9C9, 0x9CA,
    0x9CF, 0x9D6, 0x9D8, 0x9DB, 0x9DE, 0x9DE, 0x9E4, 0x9E5,
    0x9FF, 0xA00, 0xA04, 0xA04, 0xA0B, 0xA0E, 0xA11, 0xA12,
    0xA29, 0xA29, 0xA31, 0xA31, 0xA34, 0xA34, 0xA37, 0xA37,
    0xA3A, 0xA3B, 0xA3D, 0xA3D, 0xA43, 0xA46, 0xA49, 0xA4A,
    0xA4E, 0xA50, 0xA52, 0xA58, 0xA5D, 0xA5D, 0xA5F, 0xA65,
    0xA77, 0xA80, 0xA84, 0xA84, 0xA8E, 0xA8E, 0xA92, 0xA92,
    0xAA9, 0xAA9, 0xAB1, 0xAB1, 0xAB4, 0xAB4, 0xABA, 0xABB,
    0xAC6, 0xAC6, 0xACA, 0xACA, 0xACE, 0xACF, 0xAD1, 0xADF,
    0xAE4, 0xAE5, 0xAF2, 0xAF8, 0xB00, 0xB00, 0xB04, 0xB04,
    0xB0D, 0xB0E, 0xB11, 0xB12, 0xB29, 0xB29, 0xB31, 0xB31,
    0xB34, 0xB34, 0xB3A, 0xB3B, 0xB45, 0xB46, 0xB49, 0xB4A,
    0xB4E, 0xB54, 0xB58, 0xB5B, 0xB5E, 0xB5E, 0xB64, 0xB65,
    0xB78, 0xB81, 0xB84, 0xB84, 0xB8B, 0xB8D, 0xB91, 0xB91,
    0xB96, 0xB98, 0xB9B, 0xB9B, 0xB9D, 0xB9D, 0xBA0, 0xBA2,
    0xBA5, 0xBA7, 0xBAB, 0xBAD, 0xBBA, 0xBBD, 0xBC3, 0xBC5,
    0xBC9, 0xBC9, 0xBCE, 0xBCF, 0xBD1, 0xBD6, 0xBD8, 0xBE5,
    0xBFB, 0xBFF, 0xC0D, 0xC0D, 0xC11, 0xC11, 0xC29, 0xC29,
    0xC3A, 0xC3B, 0xC45, 0xC45, 0xC49, 0xC49, 0xC4E, 0xC54,
    0xC57, 0xC57, 0xC5B, 0xC5B, 0xC5E, 0xC5F, 0xC64, 0xC65,
    0xC70, 0xC76, 0xC8D, 0xC8D, 0xC91, 0xC91, 0xCA9, 0xCA9,
    0xCB4, 0xCB4, 0xCBA, 0xCBB, 0xCC5, 0xCC5, 0xCC9, 0xCC9,
    0xCCE, 0xCD4, 0xCD7, 0xCDB, 0xCDF, 0xCDF, 0xCE4, 0xCE5,
    0xCF0, 0xCF0, 0xCF4, 0xCFF, 0xD0D, 0xD0D, 0xD11, 0xD11,
    0xD45, 0xD45, 0xD49, 0xD49, 0xD50, 0xD53, 0xD64, 0xD65,
    0xD80, 0xD80, 0xD84, 0xD84, 0xD97, 0xD99, 0xDB2, 0xDB2,
    0xDBC, 0xDBC, 0xDBE, 0xDBF, 0xDC7, 0xDC9, 0xDCB, 0xDCE,
    0xDD5, 0xDD5, 0xDD7, 0xDD7, 0xDE0, 0xDE5, 0xDF0, 0xDF1,
    0xDF5, 0xE00, 0xE3B, 0xE3E, 0xE5C, 0xE80, 0xE83, 0xE83,
    0xE85, 0xE85, 0xE8B, 0xE8B, 0xEA4, 0xEA4, 0xEA6, 0xEA6,
    0xEBE, 0xEBF, 0xEC5, 0xEC5, 0xEC7, 0xEC7, 0xECF, 0xECF,
    0xEDA, 0xEDB, 0xEE0, 0xEFF, 0xF48, 0xF48, 0xF6D, 0xF70,
    0xF98, 0xF98, 0xFBD, 0xFBD, 0xFCD, 0xFCD, 0xFDB, 0xFFF,
    0x10C6, 0x10C6, 0x10C8, 0x10CC, 0x10CE, 0x10CF, 0x1249, 0x1249,
    0x124E, 0x124F, 0x1257, 0x1257, 0x1259, 0x1259, 0x125E, 0x125F,
    0x1289, 0x1289, 0x128E, 0x128F, 0x12B1, 0x12B1, 0x12B6, 0x12B7,
    0x12BF, 0x12BF, 0x12C1, 0x12C1, 0x12C6, 0x12C7, 0x12D7, 0x12D7,
    0x1311, 0x1311, 0x1316, 0x1317, 0x135B, 0x135C, 0x137D, 0x137F,
    0x139A, 0x139F, 0x13F6, 0x13F7, 0x13FE, 0x13FF, 0x1680, 0x1680,
    0x169D, 0x169F, 0x16F9, 0x16FF, 0x1716, 0x171E, 0x1737, 0x173F,
    0x1754, 0x175F, 0x176D, 0x176D, 0x1771, 0x1771, 0x1774, 0x177F,
    0x17DE, 0x17DF, 0x17EA, 0x17EF, 0x17FA, 0x17FF, 0x180E, 0x180E,
    0x181A, 0x181F, 0x1879, 0x187F, 0x18AB, 0x18AF, 0x18F6, 0x18FF,
    0x191F, 0x191F, 0x192C, 0x192F, 0x193C, 0x193F, 0x1941, 0x1943,
    0x196E, 0x196F, 0x1975, 0x197F, 0x19AC, 0x19AF, 0x19CA, 0x19CF,
    0x19DB, 0x19DD, 0x1A1C, 0x1A1D, 0x1A5F, 0x1A5F, 0x1A7D, 0x1A7E,
    0x1A8A, 0x1A8F, 0x1A9A, 0x1A9F, 0x1AAE, 0x1AAF, 0x1ADE, 0x1ADF,
    0x1AEC, 0x1AFF, 0x1B4D, 0x1B4D, 0x1BF4, 0x1BFB, 0x1C38, 0x1C3A,
    0x1C4A, 0x1C4C, 0x1C8B, 0x1C8F, 0x1CBB, 0x1CBC, 0x1CC8, 0x1CCF,
    0x1CFB, 0x1CFF, 0x1F16, 0x1F17, 0x1F1E, 0x1F1F, 0x1F46, 0x1F47,
    0x1F4E, 0x1F4F, 0x1F58, 0x1F58, 0x1F5A, 0x1F5A, 0x1F5C, 0x1F5C,
    0x1F5E, 0x1F5E, 0x1F7E, 0x1F7F, 0x1FB5, 0x1FB5, 0x1FC5, 0x1FC5,
    0x1FD4, 0x1FD5, 0x1FDC, 0x1FDC, 0x1FF0, 0x1FF1, 0x1FF5, 0x1FF5,
    0x1FFF, 0x200F, 0x2028, 0x202F, 0x205F, 0x206F, 0x2072, 0x2073,
    0x208F, 0x208F, 0x209D, 0x209F, 0x20C2, 0x20CF, 0x20F1, 0x20FF,
    0x218C, 0x218F, 0x242A, 0x243F, 0x244B, 0x245F, 0x2B74, 0x2B75,
    0x2CF4, 0x2CF8, 0x2D26, 0x2D26, 0x2D28, 0x2D2C, 0x2D2E, 0x2D2F,
    0x2D68, 0x2D6E, 0x2D71, 0x2D7E, 0x2D97, 0x2D9F, 0x2DA7, 0x2DA7,
    0x2DAF, 0x2DAF, 0x2DB7, 0x2DB7, 0x2DBF, 0x2DBF, 0x2DC7, 0x2DC7,
    0x2DCF, 0x2DCF, 0x2DD7, 0x2DD7, 0x2DDF, 0x2DDF, 0x2E5E, 0x2E7F,
    0x2E9A, 0x2E9A, 0x2EF4, 0x2EFF, 0x2FD6, 0x2FEF, 0x3000, 0x3000,
    0x3040, 0x3040, 0x3097, 0x3098, 0x3100, 0x3104, 0x3130, 0x3130,
    0x318F, 0x318F, 0x31E6, 0x31EE, 0x321F, 0x321F, 0xA48D, 0xA48F,
    0xA4C7, 0xA4CF, 0xA62C, 0xA63F, 0xA6F8, 0xA6FF, 0xA7DD, 0xA7F0,
    0xA82D, 0xA82F, 0xA83A, 0xA83F, 0xA878, 0xA87F, 0xA8C6, 0xA8CD,
    0xA8DA, 0xA8DF, 0xA954, 0xA95E, 0xA97D, 0xA97F, 0xA9CE, 0xA9CE,
    0xA9DA, 0xA9DD, 0xA9FF, 0xA9FF, 0xAA37, 0xAA3F, 0xAA4E, 0xAA4F,
    0xAA5A, 0xAA5B, 0xAAC3, 0xAADA, 0xAAF7, 0xAB00, 0xAB07, 0xAB08,
    0xAB0F, 0xAB10, 0xAB17, 0xAB1F, 0xAB27, 0xAB27, 0xAB2F, 0xAB2F,
    0xAB6C, 0xAB6F, 0xABEE, 0xABEF, 0xABFA, 0xABFF, 0xD7A4, 0xD7AF,
    0xD7C7, 0xD7CA, 0xD7FC, 0xF8FF, 0xFA6E, 0xFA6F, 0xFADA, 0xFAFF,
    0xFB07, 0xFB12, 0xFB18, 0xFB1C, 0xFB37, 0xFB37, 0xFB3D, 0xFB3D,
    0xFB3F, 0xFB3F, 0xFB42, 0xFB42, 0xFB45, 0xFB45, 0xFDD0, 0xFDEF,
    0xFE1A, 0xFE1F, 0xFE53, 0xFE53, 0xFE67, 0xFE67, 0xFE6C, 0xFE6F,
    0xFE75, 0xFE75, 0xFEFD, 0xFF00, 0xFFBF, 0xFFC1, 0xFFC8, 0xFFC9,
    0xFFD0, 0xFFD1, 0xFFD8, 0xFFD9, 0xFFDD, 0xFFDF, 0xFFE7, 0xFFE7,
    0xFFEF, 0xFFFB, 0xFFFE, 0xFFFF, 0x1000C, 0x1000C, 0x10027, 0x10027,
    0x1003B, 0x1003B, 0x1003E, 0x1003E, 0x1004E, 0x1004F, 0x1005E, 0x1007F,
    0x100FB, 0x100FF, 0x10103, 0x10106, 0x10134, 0x10136, 0x1018F, 0x1018F,
    0x1019D, 0x1019F, 0x101A1, 0x101CF, 0x101FE, 0x1027F, 0x1029D, 0x1029F,
    0x102D1, 0x102DF, 0x102FC, 0x102FF, 0x10324, 0x1032C, 0x1034B, 0x1034F,
    0x1037B, 0x1037F, 0x1039E, 0x1039E, 0x103C4, 0x103C7, 0x103D6, 0x103FF,
    0x1049E, 0x1049F, 0x104AA, 0x104AF, 0x104D4, 0x104D7, 0x104FC, 0x104FF,
    0x10528, 0x1052F, 0x10564, 0x1056E, 0x1057B, 0x1057B, 0x1058B, 0x1058B,
    0x10593, 0x10593, 0x10596, 0x10596, 0x105A2, 0x105A2, 0x105B2, 0x105B2,
    0x105BA, 0x105BA, 0x105BD, 0x105BF, 0x105F4, 0x105FF, 0x10737, 0x1073F,
    0x10756, 0x1075F, 0x10768, 0x1077F, 0x10786, 0x10786, 0x107B1, 0x107B1,
    0x107BB, 0x107FF, 0x10806, 0x10807, 0x10809, 0x10809, 0x10836, 0x10836,
    0x10839, 0x1083B, 0x1083D, 0x1083E, 0x10856, 0x10856, 0x1089F, 0x108A6,
    0x108B0, 0x108DF, 0x108F3, 0x108F3, 0x108F6, 0x108FA, 0x1091C, 0x1091E,
    0x1093A, 0x1093E, 0x1095A, 0x1097F, 0x109B8, 0x109BB, 0x109D0, 0x109D1,
    0x10A04, 0x10A04, 0x10A07, 0x10A0B, 0x10A14, 0x10A14, 0x10A18, 0x10A18,
    0x10A36, 0x10A37, 0x10A3B, 0x10A3E, 0x10A49, 0x10A4F, 0x10A59, 0x10A5F,
    0x10AA0, 0x10ABF, 0x10AE7, 0x10AEA, 0x10AF7, 0x10AFF, 0x10B36, 0x10B38,
    0x10B56, 0x10B57, 0x10B73, 0x10B77, 0x10B92, 0x10B98, 0x10B9D, 0x10BA8,
    0x10BB0, 0x10BFF, 0x10C49, 0x10C7F, 0x10CB3, 0x10CBF, 0x10CF3, 0x10CF9,
    0x10D28, 0x10D2F, 0x10D3A, 0x10D3F, 0x10D66, 0x10D68, 0x10D86, 0x10D8D,
    0x10D90, 0x10E5F, 0x10E7F, 0x10E7F, 0x10EAA, 0x10EAA, 0x10EAE, 0x10EAF,
    0x10EB2, 0x10EC1, 0x10EC8, 0x10ECF, 0x10ED9, 0x10EF9, 0x10F28, 0x10F2F,
    0x10F5A, 0x10F6F, 0x10F8A, 0x10FAF, 0x10FCC, 0x10FDF, 0x10FF7, 0x10FFF,
    0x1104E, 0x11051, 0x11076, 0x1107E, 0x110BD, 0x110BD, 0x110C3, 0x110CF,
    0x110E9, 0x110EF, 0x110FA, 0x110FF, 0x11135, 0x11135, 0x11148, 0x1114F,
    0x11177, 0x1117F, 0x111E0, 0x111E0, 0x111F5, 0x111FF, 0x11212, 0x11212,
    0x11242, 0x1127F, 0x11287, 0x11287, 0x11289, 0x11289, 0x1128E, 0x1128E,
    0x1129E, 0x1129E, 0x112AA, 0x112AF, 0x112EB, 0x112EF, 0x112FA, 0x112FF,
    0x11304, 0x11304, 0x1130D, 0x1130E, 0x11311, 0x11312, 0x11329, 0x11329,
    0x11331, 0x11331, 0x11334, 0x11334, 0x1133A, 0x1133A, 0x11345, 0x11346,
    0x11349, 0x1134A, 0x1134E, 0x1134F, 0x11351, 0x11356, 0x11358, 0x1135C,
    0x11364, 0x11365, 0x1136D, 0x1136F, 0x11375, 0x1137F, 0x1138A, 0x1138A,
    0x1138C, 0x1138D, 0x1138F, 0x1138F, 0x113B6, 0x113B6, 0x113C1, 0x113C1,
    0x113C3, 0x113C4, 0x113C6, 0x113C6, 0x113CB, 0x113CB, 0x113D6, 0x113D6,
    0x113D9, 0x113E0, 0x113E3, 0x113FF, 0x1145C, 0x1145C, 0x11462, 0x1147F,
    0x114C8, 0x114CF, 0x114DA, 0x1157F, 0x115B6, 0x115B7, 0x115DE, 0x115FF,
    0x11645, 0x1164F, 0x1165A, 0x1165F, 0x1166D, 0x1167F, 0x116BA, 0x116BF,
    0x116CA, 0x116CF, 0x116E4, 0x116FF, 0x1171B, 0x1171C, 0x1172C, 0x1172F,
    0x11747, 0x117FF, 0x1183C, 0x1189F, 0x118F3, 0x118FE, 0x11907, 0x11908,
    0x1190A, 0x1190B, 0x11914, 0x11914, 0x11917, 0x11917, 0x11936, 0x11936,
    0x11939, 0x1193A, 0x11947, 0x1194F, 0x1195A, 0x1199F, 0x119A8, 0x119A9,
    0x119D8, 0x119D9, 0x119E5, 0x119FF, 0x11A48, 0x11A4F, 0x11AA3, 0x11AAF,
    0x11AF9, 0x11AFF, 0x11B0A, 0x11B5F, 0x11B68, 0x11BBF, 0x11BE2, 0x11BEF,
    0x11BFA, 0x11BFF, 0x11C09, 0x11C09, 0x11C37, 0x11C37, 0x11C46, 0x11C4F,
    0x11C6D, 0x11C6F, 0x11C90, 0x11C91, 0x11CA8, 0x11CA8, 0x11CB7, 0x11CFF,
    0x11D07, 0x11D07, 0x11D0A, 0x11D0A, 0x11D37, 0x11D39, 0x11D3B, 0x11D3B,
    0x11D3E, 0x11D3E, 0x11D48, 0x11D4F, 0x11D5A, 0x11D5F, 0x11D66, 0x11D66,
    0x11D69, 0x11D69, 0x11D8F, 0x11D8F, 0x11D92, 0x11D92, 0x11D99, 0x11D9F,
    0x11DAA, 0x11DAF, 0x11DDC, 0x11DDF, 0x11DEA, 0x11EDF, 0x11EF9, 0x11EFF,
    0x11F11, 0x11F11, 0x11F3B, 0x11F3D, 0x11F5B, 0x11FAF, 0x11FB1, 0x11FBF,
    0x11FF2, 0x11FFE, 0x1239A, 0x123FF, 0x1246F, 0x1246F, 0x12475, 0x1247F,
    0x12544, 0x12F8F, 0x12FF3, 0x12FFF, 0x13430, 0x1343F, 0x13456, 0x1345F,
    0x143FB, 0x143FF, 0x14647, 0x160FF, 0x1613A, 0x167FF, 0x16A39, 0x16A3F,
    0x16A5F, 0x16A5F, 0x16A6A, 0x16A6D, 0x16ABF, 0x16ABF, 0x16ACA, 0x16ACF,
    0x16AEE, 0x16AEF, 0x16AF6, 0x16AFF, 0x16B46, 0x16B4F, 0x16B5A, 0x16B5A,
    0x16B62, 0x16B62, 0x16B78, 0x16B7C, 0x16B90, 0x16D3F, 0x16D7A, 0x16E3F,
    0x16E9B, 0x16E9F, 0x16EB9, 0x16EBA, 0x16ED4, 0x16EFF, 0x16F4B, 0x16F4E,
    0x16F88, 0x16F8E, 0x16FA0, 0x16FDF, 0x16FE5, 0x16FEF, 0x16FF7, 0x16FFF,
    0x18CD6, 0x18CFE, 0x18D1F, 0x18D7F, 0x18DF3, 0x1AFEF, 0x1AFF4, 0x1AFF4,
    0x1AFFC, 0x1AFFC, 0x1AFFF, 0x1AFFF, 0x1B123, 0x1B131, 0x1B133, 0x1B14F,
    0x1B153, 0x1B154, 0x1B156, 0x1B163, 0x1B168, 0x1B16F, 0x1B2FC, 0x1BBFF,
    0x1BC6B, 0x1BC6F, 0x1BC7D, 0x1BC7F, 0x1BC89, 0x1BC8F, 0x1BC9A, 0x1BC9B,
    0x1BCA0, 0x1CBFF, 0x1CCFD, 0x1CCFF, 0x1CEB4, 0x1CEB9, 0x1CED1, 0x1CEDF,
    0x1CEF1, 0x1CEFF, 0x1CF2E, 0x1CF2F, 0x1CF47, 0x1CF4F, 0x1CFC4, 0x1CFFF,
    0x1D0F6, 0x1D0FF, 0x1D127, 0x1D128, 0x1D173, 0x1D17A, 0x1D1EB, 0x1D1FF,
    0x1D246, 0x1D2BF, 0x1D2D4, 0x1D2DF, 0x1D2F4, 0x1D2FF, 0x1D357, 0x1D35F,
    0x1D379, 0x1D3FF, 0x1D455, 0x1D455, 0x1D49D, 0x1D49D, 0x1D4A0, 0x1D4A1,
    0x1D4A3, 0x1D4A4, 0x1D4A7, 0x1D4A8, 0x1D4AD, 0x1D4AD, 0x1D4BA, 0x1D4BA,
    0x1D4BC, 0x1D4BC, 0x1D4C4, 0x1D4C4, 0x1D506, 0x1D506, 0x1D50B, 0x1D50C,
    0x1D515, 0x1D515, 0x1D51D, 0x1D51D, 0x1D53A, 0x1D53A, 0x1D53F, 0x1D53F,
    0x1D545, 0x1D545, 0x1D547, 0x1D549, 0x1D551, 0x1D551, 0x1D6A6, 0x1D6A7,
    0x1D7CC, 0x1D7CD, 0x1DA8C, 0x1DA9A, 0x1DAA0, 0x1DAA0, 0x1DAB0, 0x1DEFF,
    0x1DF1F, 0x1DF24, 0x1DF2B, 0x1DFFF, 0x1E007, 0x1E007, 0x1E019, 0x1E01A,
    0x1E022, 0x1E022, 0x1E025, 0x1E025, 0x1E02B, 0x1E02F, 0x1E06E, 0x1E08E,
    0x1E090, 0x1E0FF, 0x1E12D, 0x1E12F, 0x1E13E, 0x1E13F, 0x1E14A, 0x1E14D,
    0x1E150, 0x1E28F, 0x1E2AF, 0x1E2BF, 0x1E2FA, 0x1E2FE, 0x1E300, 0x1E4CF,
    0x1E4FA, 0x1E5CF, 0x1E5FB, 0x1E5FE, 0x1E600, 0x1E6BF, 0x1E6DF, 0x1E6DF,
    0x1E6F6, 0x1E6FD, 0x1E700, 0x1E7DF, 0x1E7E7, 0x1E7E7, 0x1E7EC, 0x1E7EC,
    0x1E7EF, 0x1E7EF, 0x1E7FF, 0x1E7FF, 0x1E8C5, 0x1E8C6, 0x1E8D7, 0x1E8FF,
    0x1E94C, 0x1E94F, 0x1E95A, 0x1E95D, 0x1E960, 0x1EC70, 0x1ECB5, 0x1ED00,
    0x1ED3E, 0x1EDFF, 0x1EE04, 0x1EE04, 0x1EE20, 0x1EE20, 0x1EE23, 0x1EE23,
    0x1EE25, 0x1EE26, 0x1EE28, 0x1EE28, 0x1EE33, 0x1EE33, 0x1EE38, 0x1EE38,
    0x1EE3A, 0x1EE3A, 0x1EE3C, 0x1EE41, 0x1EE43, 0x1EE46, 0x1EE48, 0x1EE48,
    0x1EE4A, 0x1EE4A, 0x1EE4C, 0x1EE4C, 0x1EE50, 0x1EE50, 0x1EE53, 0x1EE53,
    0x1EE55, 0x1EE56, 0x1EE58, 0x1EE58, 0x1EE5A, 0x1EE5A, 0x1EE5C, 0x1EE5C,
    0x1EE5E, 0x1EE5E, 0x1EE60, 0x1EE60, 0x1EE63, 0x1EE63, 0x1EE65, 0x1EE66,
    0x1EE6B, 0x1EE6B, 0x1EE73, 0x1EE73, 0x1EE78, 0x1EE78, 0x1EE7D, 0x1EE7D,
    0x1EE7F, 0x1EE7F, 0x1EE8A, 0x1EE8A, 0x1EE9C, 0x1EEA0, 0x1EEA4, 0x1EEA4,
    0x1EEAA, 0x1EEAA, 0x1EEBC, 0x1EEEF, 0x1EEF2, 0x1EFFF, 0x1F02C, 0x1F02F,
    0x1F094, 0x1F09F, 0x1F0AF, 0x1F0B0, 0x1F0C0, 0x1F0C0, 0x1F0D0, 0x1F0D0,
    0x1F0F6, 0x1F0FF, 0x1F1AE, 0x1F1E5, 0x1F203, 0x1F20F, 0x1F23C, 0x1F23F,
    0x1F249, 0x1F24F, 0x1F252, 0x1F25F, 0x1F266, 0x1F2FF, 0x1F6D9, 0x1F6DB,
    0x1F6ED, 0x1F6EF, 0x1F6FD, 0x1F6FF, 0x1F7DA, 0x1F7DF, 0x1F7EC, 0x1F7EF,
    0x1F7F1, 0x1F7FF, 0x1F80C, 0x1F80F, 0x1F848, 0x1F84F, 0x1F85A, 0x1F85F,
    0x1F888, 0x1F88F, 0x1F8AE, 0x1F8AF, 0x1F8BC, 0x1F8BF, 0x1F8C2, 0x1F8CF,
    0x1F8D9, 0x1F8FF, 0x1FA58, 0x1FA5F, 0x1FA6E, 0x1FA6F, 0x1FA7D, 0x1FA7F,
    0x1FA8B, 0x1FA8D, 0x1FAC7, 0x1FAC7, 0x1FAC9, 0x1FACC, 0x1FADD, 0x1FADE,
    0x1FAEB, 0x1FAEE, 0x1FAF9, 0x1FAFF, 0x1FB93, 0x1FB93, 0x1FBFB, 0x1FFFF,
    0x2A6E0, 0x2A6FF, 0x2B81E, 0x2B81F, 0x2CEAE, 0x2CEAF, 0x2EBE1, 0x2EBEF,
    0x2EE5E, 0x2F7FF, 0x2FA1E, 0x2FFFF, 0x3134B, 0x3134F, 0x3347A, 0xE00FF,
    0xE01F0, 0x10FFFF
};
/* END GENERATED: tpa_int__nonprint */

static TPA_UNUSED int tpa_int__printable(uint32_t c)
{
    size_t lo = 0, hi = TPA_INT__NONPRINT_N;
    if (c < 0x80) {
        return c >= 0x20 && c < 0x7F;
    }
    while (lo < hi) {
        size_t mid = lo + (hi - lo) / 2;
        if (c < tpa_int__nonprint[2 * mid]) {
            hi = mid;
        }
        else if (c > tpa_int__nonprint[2 * mid + 1]) {
            lo = mid + 1;
        }
        else {
            return 0;
        }
    }
    return 1;
}

/* The bytes of the UTF-8 encoding of a code point (not a surrogate) into `buf`; returns how many (1 to 4). */
static TPA_UNUSED size_t tpa_int__utf8_put(uint32_t c, char *buf)
{
    if (c < 0x80) {
        buf[0] = (char)c;
        return 1;
    }
    if (c < 0x800) {
        buf[0] = (char)(0xC0 | (c >> 6));
        buf[1] = (char)(0x80 | (c & 0x3F));
        return 2;
    }
    if (c < 0x10000) {
        buf[0] = (char)(0xE0 | (c >> 12));
        buf[1] = (char)(0x80 | ((c >> 6) & 0x3F));
        buf[2] = (char)(0x80 | (c & 0x3F));
        return 3;
    }
    buf[0] = (char)(0xF0 | (c >> 18));
    buf[1] = (char)(0x80 | ((c >> 12) & 0x3F));
    buf[2] = (char)(0x80 | ((c >> 6) & 0x3F));
    buf[3] = (char)(0x80 | (c & 0x3F));
    return 4;
}

/* The repr being written: `n` bytes in `buf`, `chars` characters of the repr so far (at most 200 are kept). */
typedef struct {
    char *buf;
    size_t n;
    size_t chars;
} tpa_int__rbuf;

static TPA_UNUSED void tpa_int__rput(tpa_int__rbuf *r, uint32_t c)
{
    if (r->chars >= 200) {
        return;
    }
    r->chars += 1;
    r->n += tpa_int__utf8_put(c, r->buf + r->n);
}

static TPA_UNUSED void tpa_int__rhex(tpa_int__rbuf *r, uint32_t c, unsigned digits)
{
    static const char hex[] = "0123456789abcdef";
    unsigned d;
    for (d = digits; d-- > 0;) {
        tpa_int__rput(r, (uint32_t)hex[(c >> (4 * d)) & 15u]);
    }
}

/* Writes repr(s) of the string given as code points (surrogates allowed: the lone surrogates of the arguments) into
 * `buf` (room for 4 * 200 bytes), returns the byte count. The first 200 characters only, as the message keeps. */
static TPA_UNUSED size_t tpa_int__repr(const uint32_t *cp, size_t m, char *buf)
{
    tpa_int__rbuf r;
    size_t i;
    int has_single = 0, has_double = 0;
    uint32_t quote;
    r.buf = buf;
    r.n = 0;
    r.chars = 0;
    for (i = 0; i < m; i++) {
        has_single |= cp[i] == '\'';
        has_double |= cp[i] == '"';
    }
    quote = (has_single && !has_double) ? (uint32_t)'"' : (uint32_t)'\'';
    tpa_int__rput(&r, quote);
    for (i = 0; i < m && r.chars < 200; i++) {
        uint32_t c = cp[i];
        if (c == quote || c == '\\') {
            tpa_int__rput(&r, '\\');
            tpa_int__rput(&r, c);
        }
        else if (c == '\t') {
            tpa_int__rput(&r, '\\');
            tpa_int__rput(&r, 't');
        }
        else if (c == '\n') {
            tpa_int__rput(&r, '\\');
            tpa_int__rput(&r, 'n');
        }
        else if (c == '\r') {
            tpa_int__rput(&r, '\\');
            tpa_int__rput(&r, 'r');
        }
        else if (tpa_int__printable(c)) {
            tpa_int__rput(&r, c);
        }
        else if (c <= 0xFF) {
            tpa_int__rput(&r, '\\');
            tpa_int__rput(&r, 'x');
            tpa_int__rhex(&r, c, 2);
        }
        else if (c <= 0xFFFF) {
            tpa_int__rput(&r, '\\');
            tpa_int__rput(&r, 'u');
            tpa_int__rhex(&r, c, 4);
        }
        else {
            tpa_int__rput(&r, '\\');
            tpa_int__rput(&r, 'U');
            tpa_int__rhex(&r, c, 8);
        }
    }
    if (i == m) {
        tpa_int__rput(&r, quote);           /* the closing quote, unless the 200 characters are used up before it */
    }
    return r.n;
}

/* ValueError: invalid literal for int() with base 10: <repr> */
static TPA_UNUSED int tpa_int__raise_invalid(const uint32_t *cp, size_t m)
{
    char msg[1024];
    size_t plen = sizeof TPA_MSG_INT_INVALID_LITERAL - 1;
    memcpy(msg, TPA_MSG_INT_INVALID_LITERAL, plen);
    plen += tpa_int__repr(cp, m, msg + plen);
    msg[plen] = '\0';
    return tpa_raise(TPA_ValueError, msg);
}

/* int(s) for a str given as code points (a lone surrogate is a code point: the undecodable bytes of an argument).
 * A string with a surrogate is never an int literal; any other goes through tpa_int_from_str, and what that
 * declines (an invalid string with a non-ASCII character) gets the message built from the code points here, so
 * the result is the reference's for EVERY string. Returns 0 (*out a new reference) or -1 (an exception). */
static TPA_UNUSED int tpa_int_from_cps(const uint32_t *cp, size_t m, tpa_int *out)
{
    size_t i, nb = 0, k = 0;
    char *u8;
    int rc;
    for (i = 0; i < m; i++) {
        if (cp[i] >= 0xD800 && cp[i] <= 0xDFFF) {
            return tpa_int__raise_invalid(cp, m);
        }
        nb += cp[i] < 0x80 ? 1u : (cp[i] < 0x800 ? 2u : (cp[i] < 0x10000 ? 3u : 4u));
    }
    u8 = (char *)malloc(nb + 1);
    if (u8 == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    for (i = 0; i < m; i++) {
        k += tpa_int__utf8_put(cp[i], u8 + k);
    }
    rc = tpa_int_from_str(u8, nb, out);
    free(u8);
    if (rc == TPA_INT_NOT_HANDLED) {
        return tpa_int__raise_invalid(cp, m);
    }
    return rc;
}

/* The program arguments as the reference decodes them. POSIX: the bytes of an argument are UTF-8, and each byte
 * that is not part of a strictly valid sequence becomes the lone surrogate U+DC00 + byte (the surrogateescape
 * error handler of the file system encoding: 3.15 runs in UTF-8 mode, found by running it with raw-byte
 * arguments in the report of #462). cps has room for n code points. */
static TPA_UNUSED size_t tpa_int__argv_decode(const char *bytes, size_t n, uint32_t *cps)
{
    const unsigned char *s = (const unsigned char *)bytes;
    size_t i = 0, k = 0;
    while (i < n) {
        int64_t cp = tpa_int__utf8_next(s, n, &i);
        if (cp < 0) {
            cp = 0xDC00 + (int64_t)s[i];
            i += 1;
        }
        cps[k++] = (uint32_t)cp;
    }
    return k;
}

#if defined(_WIN32) && !defined(TPA_LIBRARY)
/* Windows: the program arguments arrive as UTF-16 (the generated `wmain`); a valid surrogate pair is one code point
 * and an unpaired surrogate stays as it is (what the reference does with the command line, REASONED from its
 * documentation and source, not observed on this machine: CI compares). */
static TPA_UNUSED size_t tpa_int__wide_decode(const wchar_t *w, uint32_t *cps)
{
    size_t i = 0, k = 0;
    while (w[i] != 0) {
        uint32_t c = (uint32_t)(unsigned short)w[i];
        if (c >= 0xD800 && c <= 0xDBFF && w[i + 1] != 0) {
            uint32_t d = (uint32_t)(unsigned short)w[i + 1];
            if (d >= 0xDC00 && d <= 0xDFFF) {
                c = 0x10000u + ((c - 0xD800u) << 10) + (d - 0xDC00u);
                i += 1;
            }
        }
        cps[k++] = c;
        i += 1;
    }
    return k;
}
#endif

/* int(sys.argv[i]) for an int64 index: negative indices count from the end, an index out of range is
 * IndexError("list index out of range"). Not in a library (it has no program arguments). */
#if !defined(TPA_LIBRARY)
static TPA_UNUSED int tpa_argv_int(int64_t i, tpa_int *out)
{
    int64_t slot;
    size_t n = 0, m;
    uint32_t *cps;
    int rc;
    if (tpa_slot((int64_t)tpa_argc, i, 0, &slot) != 0) {
        return -1;
    }
#if defined(_WIN32)
    while (tpa_wargv[slot][n] != 0) {
        n += 1;
    }
#else
    n = strlen(tpa_argv[slot]);
#endif
    cps = (uint32_t *)malloc((n + 1) * sizeof(uint32_t));
    if (cps == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
#if defined(_WIN32)
    m = tpa_int__wide_decode(tpa_wargv[slot], cps);
#else
    m = tpa_int__argv_decode(tpa_argv[slot], n, cps);
#endif
    rc = tpa_int_from_cps(cps, m, out);
    free(cps);
    return rc;
}
#endif

/* ------------------------------------------------------------------------------------------
 * The big-capable list of ints (#465; static classification: leader decision 2026-11-16, not owner-confirmed; the layout
 * and the names are agent proposals, not owner-confirmed).
 *
 * A `list[int]` whose group may hold a value outside 64 bits (aot/rep.py "Lists of ints") is a counted heap object of
 * the same header as `tpa_i64_list` (the count first, then the length, so `len` and the bounds test read the same
 * fields) whose elements are `tpa_int`, one word each. The list owns one reference to each of its elements:
 *   alloc / fill / copy / from_i64 / iota   a fresh list with count 1; an element that is a big has its count raised
 *                                           for each place it is held (`fill` raises it once per element, a small
 *                                           needs nothing); a failed creation leaves nothing allocated
 *   retain / release                        as for the other lists; the release that frees the list releases every
 *                                           element first
 *   element read (generated code)           `tpa_int_list_load`: a new reference into the caller's slot
 *   element store (generated code)          `tpa_int_list_store`: the new reference is taken, then the old one
 *                                           released (the order keeps `v` being the old element safe)
 * A fresh element is the small int 0 (`TPA_INT_ZERO`), never a null word, so releasing a list that is half built
 * releases nothing dangling.
 *
 * Threads (agent proposal, not owner-confirmed; the rule it serves is design note section 7, per-container locking for
 * shared containers, and the leader's decision of 2026-11-16 that there is no library-wide lock). Under TPA_THREADS
 * the list count is atomic (the macros of tp_aot.h) and the elements are immutable values with atomic counts, but
 * the SLOT of an element is read ("load the word, then retain it") and written ("put the new word, release the old
 * one") by several steps, and a reader that retains an object a writer has just freed, or two writers that release the
 * same old element, corrupt memory. So under TPA_THREADS each big-capable list carries a small lock and every access
 * to its elements goes through the helpers below; generated code never touches `l->d[i]` of this kind itself:
 *   tpa_int_list_load(l, slot, &out)    under the lock: load the word and retain it; then unlock; `out` takes the new
 *                                       reference and gives up the one it held (as `tpa_int_set` does)
 *   tpa_int_list_store(l, slot, v)      the new reference to `v` is taken before the lock; under the lock the slot's
 *                                       word is exchanged; the OLD word is released after the unlock (a release may
 *                                       free memory, which is not done under the lock)
 *   tpa_int_list_init(l, slot, v)       for a list that no other thread can see yet (a display being built): plain
 * The result of a race is well defined: a reader sees the old or the new element, nothing is freed twice or used
 * after free. Two writers to the same slot still race on which value wins; that is the caller's business.
 * The lock (`tpa_int_list__lock`) is a spin lock on one flag in the list: an atomic exchange to acquire (acquire
 * order), an atomic store of 0 to release (release order); a waiting thread pauses the CPU, and after 64 failed tries
 * yields its time slice where the platform has a portable yield (not on Windows, where it only pauses). It cannot
 * deadlock: the lock is per list; no operation holds two (a copy locks only its source); and the code between lock and
 * unlock is a load, a count increment, or an exchange of one word, with no call that can block, wait for another
 * thread or take a lock again (no allocation, no free, no callback, no error record). Every holder therefore leaves
 * the critical section in a bounded number of steps and the waiter always gets in. The lock field is in the struct only
 * under TPA_THREADS, so an executable build (no threads) has the layout and the code it had: the helpers are the plain
 * statements.
 * ------------------------------------------------------------------------------------------ */
#if defined(TPA_THREADS)
#if defined(_MSC_VER) && !defined(__clang__)
typedef volatile long tpa_int_list_lock_t;
#define TPA_INT_LIST__LOCK_TRY(f) (_InterlockedExchange((f), 1) == 0)
#define TPA_INT_LIST__LOCK_CLEAR(f) ((void)_InterlockedExchange((f), 0))
#if defined(_M_ARM64)
#define TPA_INT_LIST__RELAX() __yield()
#else
#define TPA_INT_LIST__RELAX() _mm_pause()
#endif
#else
typedef _Atomic int tpa_int_list_lock_t;
#define TPA_INT_LIST__LOCK_TRY(f) (atomic_exchange_explicit((f), 1, memory_order_acquire) == 0)
#define TPA_INT_LIST__LOCK_CLEAR(f) atomic_store_explicit((f), 0, memory_order_release)
#if defined(__x86_64__) || defined(__i386__)
#define TPA_INT_LIST__RELAX() __asm__ volatile("pause")
#elif defined(__aarch64__)
#define TPA_INT_LIST__RELAX() __asm__ volatile("yield" ::: "memory")
#else
#define TPA_INT_LIST__RELAX() ((void)0)
#endif
#endif
#if !defined(_WIN32)                    /* <sched.h> comes from tp_aot.h, with the other thread headers */
#define TPA_INT_LIST__YIELD() ((void)sched_yield())
#else
#define TPA_INT_LIST__YIELD() ((void)0)
#endif
#endif

typedef struct tpa_int_list {
    tpa_rc_t rc;
    int64_t len;
#if defined(TPA_THREADS)
    tpa_int_list_lock_t lock;               /* 0 free, 1 held; see the Threads paragraph above */
#endif
    tpa_int d[];
} tpa_int_list;

#if defined(TPA_THREADS)
static inline void tpa_int_list__lock(const tpa_int_list *l)
{
    tpa_int_list_lock_t *f = &((tpa_int_list *)l)->lock;      /* the lock is not part of the list's value */
    unsigned spins = 0;
    while (!TPA_INT_LIST__LOCK_TRY(f)) {
        TPA_INT_LIST__RELAX();
        if (++spins >= 64u) {
            TPA_INT_LIST__YIELD();
            spins = 0;
        }
    }
}

static inline void tpa_int_list__unlock(const tpa_int_list *l)
{
    TPA_INT_LIST__LOCK_CLEAR(&((tpa_int_list *)l)->lock);
}
#endif

/* The element of `slot` (already range checked) as a new reference in `*out`; `*out` gives up the one it held. */
static inline void tpa_int_list_load(const tpa_int_list *l, int64_t slot, tpa_int *out)
{
#if defined(TPA_THREADS)
    tpa_int w;
    tpa_int_list__lock(l);
    w = l->d[slot];
    tpa_int_retain(w);
    tpa_int_list__unlock(l);
    tpa_int_release(*out);
    *out = w;
#else
    tpa_int_set(out, l->d[slot]);
#endif
}

/* `l[slot] = v` (already range checked) for a `v` the caller owns a reference to and keeps: the list takes a new one
 * and gives up the one the slot held. */
static inline void tpa_int_list_store(tpa_int_list *l, int64_t slot, tpa_int v)
{
#if defined(TPA_THREADS)
    tpa_int old;
    tpa_int_retain(v);
    tpa_int_list__lock(l);
    old = l->d[slot];
    l->d[slot] = v;
    tpa_int_list__unlock(l);
    tpa_int_release(old);
#else
    tpa_int_set(&l->d[slot], v);
#endif
}

/* The same for a `v` whose reference the caller hands over (no retain: the list owns it from here). */
static inline void tpa_int_list__store_owned(tpa_int_list *l, int64_t slot, tpa_int v)
{
#if defined(TPA_THREADS)
    tpa_int old;
    tpa_int_list__lock(l);
    old = l->d[slot];
    l->d[slot] = v;
    tpa_int_list__unlock(l);
    tpa_int_release(old);
#else
    tpa_int_release(l->d[slot]);
    l->d[slot] = v;
#endif
}

/* An element of a list no other thread can see yet (a display being built): the list takes a new reference to `v`. */
static inline void tpa_int_list_init(tpa_int_list *l, int64_t slot, tpa_int v)
{
    tpa_int_set(&l->d[slot], v);
}

static inline void tpa_int_list_retain(tpa_int_list *l)
{
    if (l != NULL) {
        TPA_RC_INC(l);
    }
}

static inline void tpa_int_list_release(tpa_int_list *l)
{
    if (l != NULL && TPA_RC_DEC_IS_ZERO(l)) {
        int64_t i;
        for (i = 0; i < l->len; i++) {
            tpa_int_release(l->d[i]);
        }
        free(l);
    }
}

/* n elements, each the small int 0 (an empty list for n <= 0); MemoryError on failure. */
static inline int tpa_int_list_alloc(int64_t n, tpa_int_list **out)
{
    int64_t len = 0, i;
    tpa_int_list *l = (tpa_int_list *)tpa__list_alloc(n, sizeof(tpa_int_list), sizeof(tpa_int), &len);
    if (l == NULL) {
        return -1;
    }
    l->rc = 1;
    l->len = len;
#if defined(TPA_THREADS)
    l->lock = 0;
#endif
    for (i = 0; i < len; i++) {
        l->d[i] = TPA_INT_ZERO;
    }
    *out = l;
    return 0;
}

/* `[v] * n`: v is borrowed; each element is a new reference to it. */
static inline int tpa_int_list_fill(int64_t n, tpa_int v, tpa_int_list **out)
{
    int64_t i;
    if (tpa_int_list_alloc(n, out)) {
        return -1;
    }
    for (i = 0; i < (*out)->len; i++) {
        tpa_int_retain(v);
        (*out)->d[i] = v;
    }
    return 0;
}

/* `a[:]` of a big-capable list: every element retained. The new list is private to this call; the source may be
 * written by another thread, so its lock is held for the loop (only loads and count increments: the length never
 * changes, so the allocation above needs none). */
static inline int tpa_int_list_copy(const tpa_int_list *src, tpa_int_list **out)
{
    int64_t i;
    if (tpa_int_list_alloc(src->len, out)) {
        return -1;
    }
#if defined(TPA_THREADS)
    tpa_int_list__lock(src);
#endif
    for (i = 0; i < src->len; i++) {
        tpa_int_retain(src->d[i]);
        (*out)->d[i] = src->d[i];
    }
#if defined(TPA_THREADS)
    tpa_int_list__unlock(src);
#endif
    return 0;
}

/* `a[:]` of a raw list into a big-capable one: every int64 converted (a conversion allocates only for a value outside
 * the small range, and fails only with MemoryError, which releases what was converted). */
static inline int tpa_int_list_from_i64(const tpa_i64_list *src, tpa_int_list **out)
{
    int64_t i;
    if (tpa_int_list_alloc(src->len, out)) {
        return -1;
    }
    for (i = 0; i < src->len; i++) {
        if (tpa_int_from_i64(src->d[i], &(*out)->d[i]) != 0) {
            tpa_int_list_release(*out);
            *out = NULL;
            return -1;
        }
    }
    return 0;
}

/* `list(range(n))` as a big-capable list: 0 .. n-1 (an empty list for n <= 0). */
static inline int tpa_int_list_iota(int64_t n, tpa_int_list **out)
{
    int64_t i;
    if (tpa_int_list_alloc(n, out)) {
        return -1;
    }
    for (i = 0; i < (*out)->len; i++) {
        if (tpa_int_from_i64(i, &(*out)->d[i]) != 0) {
            tpa_int_list_release(*out);
            *out = NULL;
            return -1;
        }
    }
    return 0;
}

/* The library boundary's element access (aot/library.py, ABI version 2): the element `i` of `l` as an int64, with the
 * list's own IndexError when `i` is out of range (negative counts from the end) and, for an element outside 64 bits,
 * an ABI-level OverflowError that has no counterpart in the source language (TPA_MSG_LIST_ELEMENT_RANGE). */
#define TPA_MSG_LIST_ELEMENT_RANGE "the element of the list does not fit in 64 bits"

static inline int tpa_int_list_get_i64(const tpa_int_list *l, int64_t i, int64_t *out)
{
    int64_t k, v;
    tpa_int w = TPA_INT_ZERO;
    int rc;
    if (tpa_slot(l->len, i, 0, &k) != 0) {
        return -1;
    }
    tpa_int_list_load(l, k, &w);                    /* a reference of our own: a writer may replace the slot at once */
    rc = tpa_int_to_i64(w, &v);
    tpa_int_release(w);
    if (rc != 0) {
        return tpa_raise(TPA_OverflowError, TPA_MSG_LIST_ELEMENT_RANGE);
    }
    *out = v;
    return 0;
}

/* `l[i] = v` for an int64 `v`: IndexError("list assignment index out of range"), or MemoryError when the value needs
 * a big object and there is none to be had (the list is then unchanged). */
static inline int tpa_int_list_set_i64(tpa_int_list *l, int64_t i, int64_t v)
{
    int64_t k;
    tpa_int nv = TPA_INT_ZERO;
    if (tpa_slot(l->len, i, 1, &k) != 0) {
        return -1;
    }
    if (tpa_int_from_i64(v, &nv) != 0) {
        return -1;
    }
    tpa_int_list__store_owned(l, k, nv);            /* the list owns the fresh `nv`; the old word is released after the unlock */
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Bitwise, shift, power, float-to-int, math.floor/ceil/trunc, string literal int (Tier K PR5)
 * ------------------------------------------------------------------------------------------ */

static inline int tpa_int_invert(tpa_int a, tpa_int *out)
{
    tpa_int tmp = TPA_INT_ZERO;
    if (tpa_int_is_small(a)) {
        *out = tpa_int_small(~tpa_int_small_value(a));
        return 0;
    }
    if (tpa_int_add(a, tpa_int_small(1), &tmp) != 0) {
        return -1;
    }
    if (tpa_int_neg(tmp, out) != 0) {
        tpa_int_release(tmp);
        return -1;
    }
    tpa_int_release(tmp);
    return 0;
}

/* Extract magnitude limbs and sign for any tpa_int into a caller-supplied 2-word scratch if small. */
static inline const uint32_t *tpa_int__limbs(tpa_int a, uint32_t scratch[2], size_t *n_out, int *neg_out)
{
    if (tpa_int_is_small(a)) {
        int64_t v = tpa_int_small_value(a);
        uint64_t m;
        if (v == 0) {
            *n_out = 0;
            *neg_out = 0;
            return scratch;
        }
        *neg_out = v < 0 ? 1 : 0;
        m = v < 0 ? 0 - (uint64_t)v : (uint64_t)v;
        scratch[0] = (uint32_t)m;
        scratch[1] = (uint32_t)(m >> 32);
        *n_out = scratch[1] != 0 ? 2 : 1;
        return scratch;
    }
    else {
        tpa_big *b = tpa_int__big(a);
        *n_out = b->n;
        *neg_out = b->neg ? 1 : 0;
        return b->d;
    }
}

static TPA_UNUSED int tpa_int_lshift(tpa_int a, tpa_int b, tpa_int *out)
{
    uint32_t ascr[2];
    size_t an, word_shift, new_n, i;
    int aneg;
    const uint32_t *ad;
    int64_t shift_i64;
    uint64_t shift;
    unsigned bit_shift;
    tpa_big *res;

    if (tpa_int_cmp_i64(b, 0) < 0) {
        return tpa_raise(TPA_ValueError, "negative shift count");
    }
    if (a == TPA_INT_ZERO) {
        *out = TPA_INT_ZERO;
        return 0;
    }
    if (b == TPA_INT_ZERO) {
        tpa_int_retain(a);
        *out = a;
        return 0;
    }
    if (!tpa_int_to_i64(b, &shift_i64) || shift_i64 < 0) {
        return tpa_raise(TPA_OverflowError, "Python int too large to convert to C ssize_t");
    }
    shift = (uint64_t)shift_i64;
    if (tpa_int_is_small(a) && shift < 62) {
        int64_t va = tpa_int_small_value(a);
        uint64_t mag = va < 0 ? 0 - (uint64_t)va : (uint64_t)va;
        if (mag <= (0x3FFFFFFFFFFFFFFFULL >> shift)) {
            uint64_t sm = mag << shift;
            *out = tpa_int_small(va < 0 ? (int64_t)(0 - sm) : (int64_t)sm);
            return 0;
        }
    }
    ad = tpa_int__limbs(a, ascr, &an, &aneg);
    if (shift / 32 > TPA_INT__MAX_LIMBS) {
        return tpa_raise(TPA_MemoryError, "");
    }
    word_shift = (size_t)(shift / 32);
    bit_shift = (unsigned)(shift % 32);
    if (an > TPA_INT__MAX_LIMBS - word_shift - (bit_shift ? 1u : 0u)) {
        return tpa_raise(TPA_MemoryError, "");
    }
    new_n = an + word_shift + (bit_shift ? 1u : 0u);
    res = tpa_int__alloc(new_n);
    if (res == NULL) {
        return -1;
    }
    for (i = 0; i < word_shift; i++) {
        res->d[i] = 0;
    }
    if (bit_shift == 0) {
        for (i = 0; i < an; i++) {
            res->d[word_shift + i] = ad[i];
        }
    }
    else {
        uint32_t carry = 0;
        for (i = 0; i < an; i++) {
            uint64_t w = ((uint64_t)ad[i] << bit_shift) | carry;
            res->d[word_shift + i] = (uint32_t)w;
            carry = (uint32_t)(w >> 32);
        }
        res->d[word_shift + an] = carry;
    }
    *out = tpa_int__finish(res, aneg);
    return 0;
}

static TPA_UNUSED int tpa_int_rshift(tpa_int a, tpa_int b, tpa_int *out)
{
    uint32_t ascr[2];
    size_t an, word_shift, new_n, i;
    int aneg;
    const uint32_t *ad;
    int64_t shift_i64;
    uint64_t shift;
    unsigned bit_shift;
    tpa_big *res;

    if (tpa_int_cmp_i64(b, 0) < 0) {
        return tpa_raise(TPA_ValueError, "negative shift count");
    }
    if (a == TPA_INT_ZERO) {
        *out = TPA_INT_ZERO;
        return 0;
    }
    if (b == TPA_INT_ZERO) {
        tpa_int_retain(a);
        *out = a;
        return 0;
    }
    if (!tpa_int_to_i64(b, &shift_i64) || shift_i64 < 0) {
        *out = tpa_int_small(tpa_int_cmp_i64(a, 0) < 0 ? -1 : 0);
        return 0;
    }
    shift = (uint64_t)shift_i64;
    if (tpa_int_is_small(a)) {
        int64_t va = tpa_int_small_value(a);
        if (shift >= 64) {
            *out = tpa_int_small(va < 0 ? -1 : 0);
        }
        else if (va >= 0) {
            *out = tpa_int_small((int64_t)((uint64_t)va >> (unsigned)shift));
        }
        else {
            *out = tpa_int_small((int64_t)(~((~(uint64_t)va) >> (unsigned)shift)));
        }
        return 0;
    }
    /* Negative big int: a >> b == ~((~a) >> b) */
    if (tpa_int__big(a)->neg) {
        tpa_int na = TPA_INT_ZERO, sr = TPA_INT_ZERO;
        if (tpa_int_invert(a, &na) != 0) {
            return -1;
        }
        if (tpa_int_rshift(na, b, &sr) != 0) {
            tpa_int_release(na);
            return -1;
        }
        tpa_int_release(na);
        if (tpa_int_invert(sr, out) != 0) {
            tpa_int_release(sr);
            return -1;
        }
        tpa_int_release(sr);
        return 0;
    }
    ad = tpa_int__limbs(a, ascr, &an, &aneg);
    if (shift / 32 >= an) {
        *out = TPA_INT_ZERO;
        return 0;
    }
    word_shift = (size_t)(shift / 32);
    bit_shift = (unsigned)(shift % 32);
    new_n = an - word_shift;
    res = tpa_int__alloc(new_n);
    if (res == NULL) {
        return -1;
    }
    if (bit_shift == 0) {
        for (i = 0; i < new_n; i++) {
            res->d[i] = ad[word_shift + i];
        }
    }
    else {
        for (i = 0; i < new_n; i++) {
            uint32_t lo = ad[word_shift + i] >> bit_shift;
            uint32_t hi = (word_shift + i + 1 < an) ? (ad[word_shift + i + 1] << (32 - bit_shift)) : 0;
            res->d[i] = lo | hi;
        }
    }
    *out = tpa_int__finish(res, 0);
    return 0;
}

/* op: 0 = &, 1 = |, 2 = ^ */
static TPA_UNUSED int tpa_int__bitwise(tpa_int a, tpa_int b, int op, tpa_int *out)
{
    uint32_t ascr[2], bscr[2];
    size_t an, bn, n, i;
    int aneg, bneg, rneg;
    const uint32_t *ad, *bd;
    uint32_t acarry, bcarry, rcarry;
    tpa_big *res;

    if (tpa_int_is_small(a) && tpa_int_is_small(b)) {
        int64_t va = tpa_int_small_value(a);
        int64_t vb = tpa_int_small_value(b);
        int64_t vr = (op == 0) ? (va & vb) : (op == 1 ? (va | vb) : (va ^ vb));
        *out = tpa_int_small(vr);
        return 0;
    }
    ad = tpa_int__limbs(a, ascr, &an, &aneg);
    bd = tpa_int__limbs(b, bscr, &bn, &bneg);
    n = an > bn ? an : bn;
    rneg = (op == 0) ? (aneg & bneg) : (op == 1 ? (aneg | bneg) : (aneg ^ bneg));
    res = tpa_int__alloc(n + 1);
    if (res == NULL) {
        return -1;
    }
    acarry = aneg ? 1u : 0u;
    bcarry = bneg ? 1u : 0u;
    rcarry = rneg ? 1u : 0u;
    for (i = 0; i < n; i++) {
        uint32_t aw = i < an ? ad[i] : 0u;
        uint32_t bw = i < bn ? bd[i] : 0u;
        uint32_t rw;
        if (aneg) {
            uint64_t t = (uint64_t)(~aw) + acarry;
            aw = (uint32_t)t;
            acarry = (uint32_t)(t >> 32);
        }
        if (bneg) {
            uint64_t t = (uint64_t)(~bw) + bcarry;
            bw = (uint32_t)t;
            bcarry = (uint32_t)(t >> 32);
        }
        rw = (op == 0) ? (aw & bw) : (op == 1 ? (aw | bw) : (aw ^ bw));
        if (rneg) {
            uint64_t t = (uint64_t)(~rw) + rcarry;
            rw = (uint32_t)t;
            rcarry = (uint32_t)(t >> 32);
        }
        res->d[i] = rw;
    }
    res->d[n] = rneg ? rcarry : 0u;
    *out = tpa_int__finish(res, rneg);
    return 0;
}

static inline int tpa_int_and(tpa_int a, tpa_int b, tpa_int *out) { return tpa_int__bitwise(a, b, 0, out); }
static inline int tpa_int_or(tpa_int a, tpa_int b, tpa_int *out) { return tpa_int__bitwise(a, b, 1, out); }
static inline int tpa_int_xor(tpa_int a, tpa_int b, tpa_int *out) { return tpa_int__bitwise(a, b, 2, out); }

static TPA_UNUSED int tpa_int_pow(tpa_int a, tpa_int b, tpa_int *out)
{
    int64_t exp_i64;
    uint64_t e;
    tpa_int base = TPA_INT_ZERO, acc = TPA_INT_ZERO;

    if (tpa_int_cmp_i64(b, 0) < 0) {
        if (a == TPA_INT_ZERO) {
            return tpa_raise(TPA_ZeroDivisionError, "0.0 cannot be raised to a negative power");
        }
        return tpa_raise(TPA_ValueError, "negative exponent in integer power");
    }
    if (b == TPA_INT_ZERO || tpa_int_cmp_i64(a, 1) == 0) {
        *out = tpa_int_small(1);
        return 0;
    }
    if (a == TPA_INT_ZERO) {
        *out = TPA_INT_ZERO;
        return 0;
    }
    if (tpa_int_cmp_i64(a, -1) == 0) {
        uint32_t bscr[2];
        size_t bn;
        int bneg;
        const uint32_t *bd = tpa_int__limbs(b, bscr, &bn, &bneg);
        *out = tpa_int_small((bn > 0 && (bd[0] & 1u)) ? -1 : 1);
        return 0;
    }
    if (!tpa_int_to_i64(b, &exp_i64) || exp_i64 < 0) {
        return tpa_raise(TPA_MemoryError, "");
    }
    e = (uint64_t)exp_i64;
    tpa_int_retain(a);
    base = a;
    acc = tpa_int_small(1);
    while (e > 0) {
        if (e & 1u) {
            tpa_int next = TPA_INT_ZERO;
            if (tpa_int_mul(acc, base, &next) != 0) {
                tpa_int_release(acc);
                tpa_int_release(base);
                return -1;
            }
            tpa_int_release(acc);
            acc = next;
        }
        e >>= 1;
        if (e > 0) {
            tpa_int sq = TPA_INT_ZERO;
            if (tpa_int_mul(base, base, &sq) != 0) {
                tpa_int_release(acc);
                tpa_int_release(base);
                return -1;
            }
            tpa_int_release(base);
            base = sq;
        }
    }
    tpa_int_release(base);
    *out = acc;
    return 0;
}

/* Exact IEEE-754 double to tpa_int (truncates toward zero, matching CPython PyLong_FromDouble). */
static TPA_UNUSED int tpa_int_from_f64(double x, tpa_int *out)
{
    double t, m;
    int exp, neg;
    uint64_t sig;
    tpa_int s = TPA_INT_ZERO, sh = TPA_INT_ZERO;

    if (isnan(x)) {
        return tpa_raise(TPA_ValueError, "cannot convert float NaN to integer");
    }
    if (isinf(x)) {
        return tpa_raise(TPA_OverflowError, "cannot convert float infinity to integer");
    }
    t = trunc(x);
    if (t >= -4611686018427387904.0 && t < 4611686018427387904.0) {
        *out = tpa_int_small((int64_t)t);
        return 0;
    }
    neg = t < 0.0;
    m = frexp(fabs(t), &exp);
    sig = (uint64_t)ldexp(m, 53);
    if (tpa_int__from_mag64(sig, neg, &s) != 0) {
        return -1;
    }
    if (exp <= 53) {
        *out = s;
        return 0;
    }
    sh = tpa_int_small((int64_t)(exp - 53));
    if (tpa_int_lshift(s, sh, out) != 0) {
        tpa_int_release(s);
        return -1;
    }
    tpa_int_release(s);
    return 0;
}

static inline int tpa_math_floor(double x, tpa_int *out) { return tpa_int_from_f64(floor(x), out); }
static inline int tpa_math_ceil(double x, tpa_int *out) { return tpa_int_from_f64(ceil(x), out); }
static inline int tpa_math_trunc(double x, tpa_int *out) { return tpa_int_from_f64(trunc(x), out); }

/* Parse a UTF-8 string literal as a Python base-10 int (matching int("...")). */
static TPA_UNUSED int tpa_int_parse_literal(const char *bytes, size_t n, tpa_int *out)
{
    int rc = tpa_int_from_str(bytes, n, out);
    if (rc == TPA_INT_NOT_HANDLED) {
        uint32_t *cps = (uint32_t *)malloc((n + 1) * sizeof(uint32_t));
        size_t m;
        if (cps == NULL) {
            return tpa_raise(TPA_MemoryError, "");
        }
        m = tpa_int__argv_decode(bytes, n, cps);
        rc = tpa_int__raise_invalid(cps, m);
        free(cps);
        return rc;
    }
    return rc;
}

#if !defined(TPA_LIBRARY)
static inline void tpa_exit_int(tpa_int code)
{
    int64_t v = 0;
    fflush(stdout);
    fflush(stderr);
    if (tpa_int_to_i64(code, &v)) {
        exit((int)(v & 0xff));
    }
    exit(255);
}
#endif

#endif /* TP_AOT_INT_H */
