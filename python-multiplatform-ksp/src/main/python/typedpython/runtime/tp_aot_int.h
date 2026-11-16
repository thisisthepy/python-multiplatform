/* tp_aot_int.h: the big-integer runtime of the TypedPython AOT mode (SPEC N-17, #460,
 * docs/design/typedpython-aot.md sections 5 and 10).
 *
 * Design decision D1 (a), leader 2026-11-16, not owner-confirmed: an int that cannot be proved inside 64
 * bits is a small-int / big-int value of our own, linked only when needed. Everything else in this file
 * (the layout, the names, the algorithms, the status codes) is an agent proposal, not owner-confirmed.
 * The AOT back end does not include this header yet (#460 adds the runtime alone); it is tested through
 * a test-only shim (src/test/python/runtime_shim/tp_aot_int_shim.c) against the reference interpreter.
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

#endif /* TP_AOT_INT_H */
