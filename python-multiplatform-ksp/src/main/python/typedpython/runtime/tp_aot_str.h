/*
 * TypedPython AOT mode, tier S step 1 (PR-S1a): the `str` value (docs/design/typedpython-aot.md 4.3.1; agent
 * proposal, not owner-confirmed; tier S is in 1.0, owner 2026-11-21 21:55).
 *
 * Included after tp_aot.h and tp_aot_int.h, only by a program that has a `str` value: a program without one
 * generates the C it generated before.
 *
 * Representation: CPython's PEP 393 layout without the object header. `kind` is 1, 2 or 4 bytes per code point and
 * is always the narrowest kind that holds the largest code point (every constructor here normalises), so two equal
 * strings have equal kinds and equal bytes. A lone surrogate (from surrogateescape, or chr(0xDC80)) is an ordinary
 * 2-byte code point, as in CPython. The code points follow the header (`TPA_STR_DATA`).
 *
 * Ownership, as the TAGGED ints of tp_aot_int.h: a function's str names and temporaries each own one reference;
 * an operation writes its result into an emptied slot only on success (0) and returns -1 with the exception
 * recorded otherwise. Literals and the constants below are static objects with an immortal count: retain and
 * release never write them, so threads may share them.
 */
#ifndef TP_AOT_STR_H
#define TP_AOT_STR_H

typedef struct tpa_str {
    tpa_rc_t rc;
    int64_t len;            /* code points */
    int64_t hash;           /* -1: not computed; reserved for S2's dict and set, nothing reads it in S1 */
    int64_t kind;           /* 1, 2 or 4 */
} tpa_str;                  /* 32 bytes, so the code points that follow are aligned for every kind */

#define TPA_STR_IMMORTAL ((int64_t)1 << 62)
#define TPA_STR_DATA(s) ((unsigned char *)(s) + sizeof(tpa_str))
#define TPA_STR_HEAD(n, kind) { TPA_STR_IMMORTAL, (n), -1, (kind) }

/* The generated literals: `static struct { tpa_str h; uint8_t d[3]; } tps_0 = {TPA_STR_HEAD(3, 1), {...}};` and the
 * value `&tps_0.h`. The header is 32 bytes, so `d` starts right after it for every element type. */

static TPA_UNUSED tpa_str tpa_str__empty = TPA_STR_HEAD(0, 1);

/* One-character strings of U+0000..U+00FF, as CPython caches them: indexing and iteration of a Latin-1 string
 * allocate nothing. */
typedef struct { tpa_str h; uint8_t d[8]; } tpa_str__ch;
#define TPA_STR__L1(c) { TPA_STR_HEAD(1, 1), { (uint8_t)(c) } }
#define TPA_STR__L4(c) TPA_STR__L1(c), TPA_STR__L1((c) + 1), TPA_STR__L1((c) + 2), TPA_STR__L1((c) + 3)
#define TPA_STR__L16(c) TPA_STR__L4(c), TPA_STR__L4((c) + 4), TPA_STR__L4((c) + 8), TPA_STR__L4((c) + 12)
#define TPA_STR__L64(c) TPA_STR__L16(c), TPA_STR__L16((c) + 16), TPA_STR__L16((c) + 32), TPA_STR__L16((c) + 48)
static TPA_UNUSED tpa_str__ch tpa_str__latin1[256] = {
    TPA_STR__L64(0), TPA_STR__L64(64), TPA_STR__L64(128), TPA_STR__L64(192)
};

static inline int tpa_str__immortal(tpa_str *s) { return TPA_RC_LOAD(s) >= TPA_STR_IMMORTAL; }

static inline void tpa_str_retain(tpa_str *s)
{
    if (s != NULL && !tpa_str__immortal(s)) {
        TPA_RC_INC(s);
    }
}

static inline void tpa_str_release(tpa_str *s)
{
    if (s != NULL && !tpa_str__immortal(s) && TPA_RC_DEC_IS_ZERO(s)) {
        free(s);
    }
}

/* A name takes the borrowed `v`: the new reference is held before the old one is given up (`s = s`). */
static inline void tpa_str_set(tpa_str **slot, tpa_str *v)
{
    tpa_str_retain(v);
    tpa_str_release(*slot);
    *slot = v;
}

static inline void tpa_str_clear(tpa_str **slot)
{
    tpa_str_release(*slot);
    *slot = NULL;
}

static inline uint32_t tpa_str_at(tpa_str *s, int64_t i)
{
    const unsigned char *d = TPA_STR_DATA(s);
    switch (s->kind) {
    case 1: return d[i];
    case 2: return ((const uint16_t *)d)[i];
    default: return ((const uint32_t *)d)[i];
    }
}

static inline void tpa_str__put(tpa_str *s, int64_t i, uint32_t c)
{
    unsigned char *d = TPA_STR_DATA(s);
    switch (s->kind) {
    case 1: d[i] = (unsigned char)c; break;
    case 2: ((uint16_t *)d)[i] = (uint16_t)c; break;
    default: ((uint32_t *)d)[i] = c; break;
    }
}

static inline int64_t tpa_str__kind_of(uint32_t maxc)
{
    return maxc < 0x100u ? 1 : (maxc < 0x10000u ? 2 : 4);
}

/* A fresh string of `len` code points of `kind`, count 1, contents unset. A size past the address space is a
 * MemoryError, never a wrapped small allocation. */
static int tpa_str__alloc(int64_t len, int64_t kind, tpa_str **out)
{
    tpa_str *s;
    if (len < 0 || len > (INT64_MAX - (int64_t)sizeof(tpa_str)) / kind) {
        return tpa_raise(TPA_MemoryError, "");
    }
    s = (tpa_str *)malloc(sizeof(tpa_str) + (size_t)(len * kind));
    if (s == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    s->rc = 1;
    s->len = len;
    s->hash = -1;
    s->kind = kind;
    *out = s;
    return 0;
}

/* `n` code points of `src` from `soff` into `dst` at `doff` (dst's kind is at least src's). */
static void tpa_str__copy(tpa_str *dst, int64_t doff, tpa_str *src, int64_t soff, int64_t n)
{
    int64_t i;
    if (n <= 0) {
        return;
    }
    if (dst->kind == src->kind) {
        memcpy(TPA_STR_DATA(dst) + doff * dst->kind, TPA_STR_DATA(src) + soff * src->kind, (size_t)(n * src->kind));
        return;
    }
    for (i = 0; i < n; i++) {
        tpa_str__put(dst, doff + i, tpa_str_at(src, soff + i));
    }
}

/* The one-character string of `c` (0 <= c <= 0x10FFFF). */
static int tpa_str__char(uint32_t c, tpa_str **out)
{
    tpa_str *s;
    if (c < 0x100u) {
        *out = &tpa_str__latin1[c].h;
        return 0;
    }
    if (tpa_str__alloc(1, tpa_str__kind_of(c), &s)) {
        return -1;
    }
    tpa_str__put(s, 0, c);
    *out = s;
    return 0;
}

static int tpa_str_from_ascii(const char *p, size_t n, tpa_str **out)
{
    tpa_str *s;
    if (n == 0) {
        *out = &tpa_str__empty;
        return 0;
    }
    if (tpa_str__alloc((int64_t)n, 1, &s)) {
        return -1;
    }
    memcpy(TPA_STR_DATA(s), p, n);
    *out = s;
    return 0;
}

static TPA_UNUSED int tpa_str_from_cps(const uint32_t *cp, size_t n, tpa_str **out)
{
    uint32_t maxc = 0;
    size_t i;
    tpa_str *s;
    if (n == 0) {
        *out = &tpa_str__empty;
        return 0;
    }
    if (n == 1) {
        return tpa_str__char(cp[0], out);
    }
    for (i = 0; i < n; i++) {
        maxc = cp[i] > maxc ? cp[i] : maxc;
    }
    if (tpa_str__alloc((int64_t)n, tpa_str__kind_of(maxc), &s)) {
        return -1;
    }
    for (i = 0; i < n; i++) {
        tpa_str__put(s, (int64_t)i, cp[i]);
    }
    *out = s;
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Operators
 * ------------------------------------------------------------------------------------------ */

/* a + b. (CPython hands back an operand when the other is empty; identity is not observable: `is` between two str
 * operands is the compile error aot/str-identity.) */
static TPA_UNUSED int tpa_str_concat(tpa_str *a, tpa_str *b, tpa_str **out)
{
    tpa_str *s;
    if (b->len == 0 || a->len == 0) {
        s = b->len == 0 ? a : b;
        tpa_str_retain(s);
        *out = s;
        return 0;
    }
    if (a->len > INT64_MAX - b->len) {
        return tpa_raise(TPA_OverflowError, "strings are too large to concat");
    }
    if (tpa_str__alloc(a->len + b->len, a->kind > b->kind ? a->kind : b->kind, &s)) {
        return -1;
    }
    tpa_str__copy(s, 0, a, 0, a->len);
    tpa_str__copy(s, a->len, b, 0, b->len);
    *out = s;
    return 0;
}

/* `s += b` for a name that owns its reference (the compiler calls it for nothing else): a count of 1 means nothing
 * else holds the string, so it grows in place, as CPython's unicode_concatenate does; otherwise a new string.
 * In place keeps the kind normalised: b's code points fit in s's kind, and s's kind was already the narrowest. */
static TPA_UNUSED int tpa_str_iadd(tpa_str **slot, tpa_str *b)
{
    tpa_str *a = *slot, *r = NULL;
    if (a != NULL && a != b && b->len > 0 && b->kind <= a->kind && !tpa_str__immortal(a)
            && TPA_RC_LOAD(a) == 1) {
        if (a->len > INT64_MAX - b->len
                || a->len + b->len > (INT64_MAX - (int64_t)sizeof(tpa_str)) / a->kind) {
            return a->len > INT64_MAX - b->len ? tpa_raise(TPA_OverflowError, "strings are too large to concat")
                                               : tpa_raise(TPA_MemoryError, "");
        }
        r = (tpa_str *)realloc(a, sizeof(tpa_str) + (size_t)((a->len + b->len) * a->kind));
        if (r == NULL) {
            return tpa_raise(TPA_MemoryError, "");
        }
        tpa_str__copy(r, r->len, b, 0, b->len);
        r->len += b->len;
        *slot = r;
        return 0;
    }
    if (tpa_str_concat(a, b, &r)) {
        return -1;
    }
    tpa_str_release(a);
    *slot = r;
    return 0;
}

/* s * n for an int64 count (the caller turns an int outside 64 bits into CPython's OverflowError first). */
static TPA_UNUSED int tpa_str_repeat(tpa_str *s, int64_t n, tpa_str **out)
{
    tpa_str *r;
    int64_t total, done;
    if (n <= 0 || s->len == 0) {
        *out = &tpa_str__empty;
        return 0;
    }
    if (n == 1) {
        tpa_str_retain(s);
        *out = s;
        return 0;
    }
    if (s->len > INT64_MAX / n) {
        return tpa_raise(TPA_OverflowError, "repeated string is too long");
    }
    total = s->len * n;
    if (tpa_str__alloc(total, s->kind, &r)) {
        return -1;
    }
    memcpy(TPA_STR_DATA(r), TPA_STR_DATA(s), (size_t)(s->len * s->kind));
    done = s->len;
    while (done < total) {
        int64_t k = done <= total - done ? done : total - done;
        memcpy(TPA_STR_DATA(r) + done * s->kind, TPA_STR_DATA(r), (size_t)(k * s->kind));
        done += k;
    }
    *out = r;
    return 0;
}

static TPA_UNUSED int tpa_str_eq(tpa_str *a, tpa_str *b)
{
    if (a == b) {
        return 1;
    }
    if (a->len != b->len || a->kind != b->kind) {
        return 0;
    }
    return memcmp(TPA_STR_DATA(a), TPA_STR_DATA(b), (size_t)(a->len * a->kind)) == 0;
}

/* Negative, zero or positive as a is before, equal to or after b in code point order (CPython's order). */
static TPA_UNUSED int tpa_str_cmp(tpa_str *a, tpa_str *b)
{
    int64_t n = a->len < b->len ? a->len : b->len, i;
    if (a->kind == 1 && b->kind == 1) {
        int c = n > 0 ? memcmp(TPA_STR_DATA(a), TPA_STR_DATA(b), (size_t)n) : 0;
        if (c != 0) {
            return c < 0 ? -1 : 1;
        }
    }
    else {
        for (i = 0; i < n; i++) {
            uint32_t x = tpa_str_at(a, i), y = tpa_str_at(b, i);
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
    }
    return a->len < b->len ? -1 : (a->len > b->len ? 1 : 0);
}

/* s[i] for an int64 index (an int outside 64 bits is CPython's IndexError, tpa_int_to_index, first). */
static TPA_UNUSED int tpa_str_index(tpa_str *s, int64_t i, tpa_str **out)
{
    if (i < 0) {
        i += s->len;
    }
    if (i < 0 || i >= s->len) {
        return tpa_raise(TPA_IndexError, "string index out of range");
    }
    return tpa_str__char(tpa_str_at(s, i), out);
}

/* A slice bound or step that is an int outside 64 bits: CPython clamps it (_PyEval_SliceIndex). */
static TPA_UNUSED int64_t tpa_int_clamp_index(tpa_int a)
{
    int64_t v;
    if (tpa_int_to_i64(a, &v) == 0) {
        return v;
    }
    return tpa_int__big(a)->neg ? INT64_MIN : INT64_MAX;
}

#define TPA_SLICE_START 1
#define TPA_SLICE_STOP 2
#define TPA_SLICE_STEP 4

/* s[start:stop:step] with the bounds `has` names (TPA_SLICE_*), as PySlice_Unpack and PySlice_AdjustIndices. */
static TPA_UNUSED int tpa_str_slice(tpa_str *s, int64_t start, int64_t stop, int64_t step, int has, tpa_str **out)
{
    int64_t len = s->len, n = 0, i, k;
    uint32_t maxc = 0;
    tpa_str *r;
    if (!(has & TPA_SLICE_STEP)) {
        step = 1;
    }
    if (step == 0) {
        return tpa_raise(TPA_ValueError, "slice step cannot be zero");
    }
    if (step < -INT64_MAX) {
        step = -INT64_MAX;
    }
    if (!(has & TPA_SLICE_START)) {
        start = step < 0 ? INT64_MAX : 0;
    }
    if (!(has & TPA_SLICE_STOP)) {
        stop = step < 0 ? INT64_MIN : INT64_MAX;
    }
    if (start < 0) {
        start += len;
        if (start < 0) {
            start = step < 0 ? -1 : 0;
        }
    }
    else if (start >= len) {
        start = step < 0 ? len - 1 : len;
    }
    if (stop < 0) {
        stop += len;
        if (stop < 0) {
            stop = step < 0 ? -1 : 0;
        }
    }
    else if (stop >= len) {
        stop = step < 0 ? len - 1 : len;
    }
    if (step < 0) {
        if (stop < start) {
            n = (start - stop - 1) / (0 - step) + 1;
        }
    }
    else if (start < stop) {
        n = (stop - start - 1) / step + 1;
    }
    if (n <= 0) {
        *out = &tpa_str__empty;
        return 0;
    }
    if (step == 1 && n == len) {
        tpa_str_retain(s);
        *out = s;
        return 0;
    }
    if (n == 1) {
        return tpa_str__char(tpa_str_at(s, start), out);
    }
    for (i = 0, k = start; i < n; i++, k += step) {
        uint32_t c = tpa_str_at(s, k);
        maxc = c > maxc ? c : maxc;
    }
    if (tpa_str__alloc(n, tpa_str__kind_of(maxc), &r)) {
        return -1;
    }
    if (step == 1 && r->kind == s->kind) {
        memcpy(TPA_STR_DATA(r), TPA_STR_DATA(s) + start * s->kind, (size_t)(n * s->kind));
    }
    else {
        for (i = 0, k = start; i < n; i++, k += step) {
            tpa_str__put(r, i, tpa_str_at(s, k));
        }
    }
    *out = r;
    return 0;
}

/* The first index of `needle` in `hay`, or -1. A needle of a wider kind holds a code point above every one of hay's,
 * so it is never found. */
static TPA_UNUSED int64_t tpa_str_find(tpa_str *hay, tpa_str *needle)
{
    int64_t i, j, last;
    if (needle->len == 0) {
        return 0;
    }
    if (needle->kind > hay->kind || needle->len > hay->len) {
        return -1;
    }
    last = hay->len - needle->len;
    if (hay->kind == needle->kind) {
        const unsigned char *h = TPA_STR_DATA(hay), *nd = TPA_STR_DATA(needle);
        size_t w = (size_t)needle->kind, nb = (size_t)needle->len * w;
        for (i = 0; i <= last; i++) {
            if (memcmp(h + (size_t)i * w, nd, nb) == 0) {
                return i;
            }
        }
        return -1;
    }
    for (i = 0; i <= last; i++) {
        for (j = 0; j < needle->len && tpa_str_at(hay, i + j) == tpa_str_at(needle, j); j++) {
        }
        if (j == needle->len) {
            return i;
        }
    }
    return -1;
}

static inline int tpa_str_contains(tpa_str *hay, tpa_str *needle) { return tpa_str_find(hay, needle) >= 0; }

/* ------------------------------------------------------------------------------------------
 * Producers: str() and repr()
 * ------------------------------------------------------------------------------------------ */

static TPA_UNUSED int tpa_str_from_i64(int64_t v, tpa_str **out)
{
    char buf[32];
    int n = snprintf(buf, sizeof buf, "%lld", (long long)v);
    return tpa_str_from_ascii(buf, (size_t)n, out);
}

static TPA_UNUSED int tpa_str_from_int(tpa_int a, tpa_str **out)
{
    char *text = NULL;
    size_t n = 0;
    int rc;
    if (tpa_int_to_str(a, &text, &n)) {
        return -1;
    }
    rc = tpa_str_from_ascii(text, n, out);
    free(text);
    return rc;
}

static TPA_UNUSED int tpa_str_from_f64(double v, tpa_str **out)
{
    char buf[48];
    int n = tpa_repr_f64(v, buf);
    return tpa_str_from_ascii(buf, (size_t)n, out);
}

static TPA_UNUSED int tpa_str_from_bool(int v, tpa_str **out)
{
    return v ? tpa_str_from_ascii("True", 4, out) : tpa_str_from_ascii("False", 5, out);
}

/* The length of the escape of `c` in a repr quoted with `quote`, 1 when it stands as itself. */
static int tpa_str__repr_width(uint32_t c, uint32_t quote)
{
    if (c == quote || c == '\\' || c == '\t' || c == '\n' || c == '\r') {
        return 2;
    }
    if (tpa_int__printable(c)) {
        return 1;
    }
    return c <= 0xFFu ? 4 : (c <= 0xFFFFu ? 6 : 10);
}

/* repr(s): single quotes unless s holds ' and no "; escapes for \, the quote, \t \n \r and every code point that is
 * not printable (str.isprintable: \xNN, \uNNNN, \UNNNNNNNN). */
static TPA_UNUSED int tpa_str_repr(tpa_str *s, tpa_str **out)
{
    static const char hex[] = "0123456789abcdef";
    int has_single = 0, has_double = 0;
    uint32_t quote, maxc = '\'';
    int64_t i, n = 2, k = 0;
    tpa_str *r;
    for (i = 0; i < s->len; i++) {
        uint32_t c = tpa_str_at(s, i);
        has_single |= c == '\'';
        has_double |= c == '"';
    }
    quote = (has_single && !has_double) ? (uint32_t)'"' : (uint32_t)'\'';
    for (i = 0; i < s->len; i++) {
        uint32_t c = tpa_str_at(s, i);
        int w = tpa_str__repr_width(c, quote);
        if (n > INT64_MAX - 10) {
            return tpa_raise(TPA_OverflowError, "string is too large to make repr");
        }
        n += w;
        if (w == 1 && c > maxc) {
            maxc = c;
        }
    }
    if (tpa_str__alloc(n, tpa_str__kind_of(maxc), &r)) {
        return -1;
    }
    tpa_str__put(r, k++, quote);
    for (i = 0; i < s->len; i++) {
        uint32_t c = tpa_str_at(s, i);
        int w = tpa_str__repr_width(c, quote), d;
        if (w == 1) {
            tpa_str__put(r, k++, c);
            continue;
        }
        tpa_str__put(r, k++, '\\');
        if (w == 2) {
            tpa_str__put(r, k++, c == '\t' ? 't' : (c == '\n' ? 'n' : (c == '\r' ? 'r' : c)));
            continue;
        }
        tpa_str__put(r, k++, w == 4 ? 'x' : (w == 6 ? 'u' : 'U'));
        for (d = w - 2; d-- > 0;) {
            tpa_str__put(r, k++, (uint32_t)hex[(c >> (4 * d)) & 15u]);
        }
    }
    tpa_str__put(r, k, quote);
    *out = r;
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Consumers: ord, chr, int(s), sys.argv[i]
 * ------------------------------------------------------------------------------------------ */

static TPA_UNUSED int tpa_str_ord(tpa_str *s, int64_t *out)
{
    char m[128];
    if (s->len != 1) {
        snprintf(m, sizeof m, "ord() expected a character, but string of length %lld found", (long long)s->len);
        return tpa_raise(TPA_TypeError, m);
    }
    *out = (int64_t)tpa_str_at(s, 0);
    return 0;
}

#define TPA_MSG_CHR_RANGE "chr() arg not in range(0x110000)"

static TPA_UNUSED int tpa_str_chr_i64(int64_t v, tpa_str **out)
{
    if (v < 0 || v > 0x10FFFF) {
        return tpa_raise(TPA_ValueError, TPA_MSG_CHR_RANGE);
    }
    return tpa_str__char((uint32_t)v, out);
}

/* chr() of an int that may be outside 64 bits: the same ValueError (observed for chr(2**70) on 3.15.0rc1t). */
static TPA_UNUSED int tpa_str_chr_int(tpa_int a, tpa_str **out)
{
    int64_t v;
    if (tpa_int_to_i64(a, &v) != 0) {
        return tpa_raise(TPA_ValueError, TPA_MSG_CHR_RANGE);
    }
    return tpa_str_chr_i64(v, out);
}

/* int(s): the parser of int(sys.argv[i]), with the reference's message for every string (tpa_int_from_cps). */
static TPA_UNUSED int tpa_str_to_int(tpa_str *s, tpa_int *out)
{
    uint32_t *cps;
    int64_t i;
    int rc;
    cps = (uint32_t *)malloc((size_t)(s->len + 1) * sizeof(uint32_t));
    if (cps == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    for (i = 0; i < s->len; i++) {
        cps[i] = tpa_str_at(s, i);
    }
    rc = tpa_int_from_cps(cps, (size_t)s->len, out);
    free(cps);
    return rc;
}

#if !defined(TPA_LIBRARY)
/* sys.argv[i] for an int64 index, decoded as the reference decodes it (UTF-8 with surrogateescape on POSIX, the
 * UTF-16 `wmain` arguments on Windows); out of range is IndexError("list index out of range"). */
static TPA_UNUSED int tpa_str_argv(int64_t i, tpa_str **out)
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
    rc = tpa_str_from_cps(cps, m, out);
    free(cps);
    return rc;
}
#endif

/* ------------------------------------------------------------------------------------------
 * Output. CPython 3.15 runs in UTF-8 mode (observed on 3.15.0rc1t: utf8_mode 1, stdout errors surrogateescape,
 * stderr errors backslashreplace). A write encodes its whole text before any byte of it is written.
 * ------------------------------------------------------------------------------------------ */

static inline int tpa_str__is_surrogate(uint32_t c) { return c >= 0xD800u && c <= 0xDFFFu; }

/* 0 when s encodes to UTF-8 with surrogateescape; otherwise CPython's UnicodeEncodeError. The encoder takes a run of
 * consecutive surrogates at once: the escapable ones (U+DC80..U+DCFF) at its start become bytes, and the error
 * covers the run from the first one that is not escapable to the run's end (stringlib/codecs.h, surrogateescape). */
static int tpa_str__check_utf8(tpa_str *s)
{
    int64_t i = 0, end, k;
    char m[160];
    if (s->kind == 1) {
        return 0;
    }
    while (i < s->len) {
        if (!tpa_str__is_surrogate(tpa_str_at(s, i))) {
            i += 1;
            continue;
        }
        end = i + 1;
        while (end < s->len && tpa_str__is_surrogate(tpa_str_at(s, end))) {
            end += 1;
        }
        for (k = i; k < end && tpa_str_at(s, k) >= 0xDC80u && tpa_str_at(s, k) <= 0xDCFFu; k++) {
        }
        if (k < end) {
            if (end - k == 1) {
                snprintf(m, sizeof m, "'utf-8' codec can't encode character '\\u%04x' in position %lld: "
                         "surrogates not allowed", (unsigned)tpa_str_at(s, k), (long long)k);
            }
            else {
                snprintf(m, sizeof m, "'utf-8' codec can't encode characters in position %lld-%lld: "
                         "surrogates not allowed", (long long)k, (long long)(end - 1));
            }
            return tpa_raise(TPA_UnicodeEncodeError, m);
        }
        i = end;
    }
    return 0;
}

/* Writes s to `f` as UTF-8; a surrogate becomes its raw byte (`escape`, surrogateescape: only U+DC80..U+DCFF reach
 * here, tpa_str__check_utf8 ran first) or its backslash escape (backslashreplace). */
static void tpa_str__write(tpa_str *s, FILE *f, int escape)
{
    static const char hex[] = "0123456789abcdef";
    char buf[4096];
    size_t n = 0;
    int64_t i;
    for (i = 0; i < s->len; i++) {
        uint32_t c = tpa_str_at(s, i);
        if (n > sizeof buf - 8) {
            fwrite(buf, 1, n, f);
            n = 0;
        }
        if (!tpa_str__is_surrogate(c)) {
            n += tpa_int__utf8_put(c, buf + n);
        }
        else if (escape) {
            buf[n++] = (char)(c & 0xFFu);
        }
        else {
            buf[n++] = '\\';
            buf[n++] = 'u';
            buf[n++] = hex[(c >> 12) & 15u];
            buf[n++] = hex[(c >> 8) & 15u];
            buf[n++] = hex[(c >> 4) & 15u];
            buf[n++] = hex[c & 15u];
        }
    }
    fwrite(buf, 1, n, f);
}

/* print's share of a str argument, and sys.stdout.write(s) (whose value is len(s)). */
static TPA_UNUSED int tpa_str_print(tpa_str *s)
{
    if (tpa_str__check_utf8(s)) {
        return -1;
    }
    tpa_str__write(s, stdout, 1);
    return 0;
}

/* sys.stderr.write(s): backslashreplace, nothing to refuse. */
static TPA_UNUSED void tpa_str_write_err(tpa_str *s)
{
    tpa_str__write(s, stderr, 0);
}

#endif /* TP_AOT_STR_H */
