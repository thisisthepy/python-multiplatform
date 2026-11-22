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

#ifndef TPA_SLICE_START                 /* (also defined by tp_aot.h for the list slices of tier S2a) */
#define TPA_SLICE_START 1
#define TPA_SLICE_STOP 2
#define TPA_SLICE_STEP 4
#endif

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

/* format(v, '.<prec>f') of a float (an f-string field `{v:.Nf}`), as tp_aot.h's print of it. */
static TPA_UNUSED int tpa_str_format_f64_fixed(double v, int prec, tpa_str **out)
{
    char buf[400];
    int n = tpa_format_f64_fixed(v, prec, buf);
    return tpa_str_from_ascii(buf, (size_t)n, out);
}

/* The concatenation of `n` strings at once (an f-string's parts): one allocation of the widest kind. */
static TPA_UNUSED int tpa_str_join(tpa_str *const *parts, size_t n, tpa_str **out)
{
    int64_t len = 0, kind = 1, at = 0;
    size_t i;
    tpa_str *r;
    for (i = 0; i < n; i++) {
        if (parts[i]->len > INT64_MAX - len) {
            return tpa_raise(TPA_OverflowError, "join() result is too long for a Python string");
        }
        len += parts[i]->len;
        kind = parts[i]->kind > kind ? parts[i]->kind : kind;
    }
    if (len == 0) {
        *out = &tpa_str__empty;
        return 0;
    }
    if (tpa_str__alloc(len, kind, &r)) {
        return -1;
    }
    for (i = 0; i < n; i++) {
        tpa_str__copy(r, at, parts[i], 0, parts[i]->len);
        at += parts[i]->len;
    }
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
#if defined(_WIN32)
/* Tier S2a (design 4.3.2, Windows console): when standard output or error is a console, CPython writes it through
 * _WindowsConsoleIO, as UTF-16 (WriteConsoleW) decoded from the UTF-8 the text encoded to, so a raw surrogateescape
 * byte shows as U+FFFD. A pipe or a file gets the UTF-8 bytes. Whether a stream is a console is asked once. stdio is
 * flushed first, so the numbers and text written through it keep their order with this text. 1: written here. */
static int tpa_str__console_write(FILE *f, const char *p, size_t n)
{
    static int is_console[2] = {-1, -1};
    int which = f == stderr ? 1 : 0;
    HANDLE h;
    DWORD mode, done;
    wchar_t w[4096];
    int units;
    if (f != stdout && f != stderr) {
        return 0;
    }
    h = GetStdHandle(which ? STD_ERROR_HANDLE : STD_OUTPUT_HANDLE);
    if (is_console[which] < 0) {
        is_console[which] = (h != NULL && h != INVALID_HANDLE_VALUE && GetConsoleMode(h, &mode)) ? 1 : 0;
    }
    if (!is_console[which]) {
        return 0;
    }
    fflush(f);
    if (n == 0) {
        return 1;
    }
    units = MultiByteToWideChar(CP_UTF8, 0, p, (int)n, w, (int)(sizeof w / sizeof w[0]));
    if (units > 0) {
        WriteConsoleW(h, w, (DWORD)units, &done, NULL);
    }
    return 1;
}
#endif

static void tpa_str__flush_text(FILE *f, const char *p, size_t n)
{
#if defined(_WIN32)
    if (tpa_str__console_write(f, p, n)) {
        return;
    }
#endif
    fwrite(p, 1, n, f);
}

static void tpa_str__write(tpa_str *s, FILE *f, int escape)
{
    static const char hex[] = "0123456789abcdef";
    char buf[4096];
    size_t n = 0;
    int64_t i;
    for (i = 0; i < s->len; i++) {
        uint32_t c = tpa_str_at(s, i);
        if (n > sizeof buf - 8) {
            tpa_str__flush_text(f, buf, n);
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
    tpa_str__flush_text(f, buf, n);
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


/* Is c one of the code points of `set`? (strip's `chars`) */
static TPA_UNUSED int tpa_str__has(tpa_str *set, uint32_t c)
{
    int64_t i;
    for (i = 0; i < set->len; i++) {
        if (tpa_str_at(set, i) == c) {
            return 1;
        }
    }
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Tier S1b: the minimal list of str (what `split` and `join` need: a display, indexing, len, iteration, repr). A
 * counted object that owns one reference to each element; it never changes length in S1b (growth is tier S2,
 * which generalises this type). Same header order as tpa_i64_list: the count, then the length.
 * ------------------------------------------------------------------------------------------ */
#if defined(TPA_GROW_STR)
/* Tier S2a: the growable layout of a list of str (tp_aot.h, "Native lists"), for a program that changes the length
 * of one; a NULL-free block of owned references in [0, len). */
typedef struct tpa_str_list {
    tpa_rc_t rc;
    int64_t len;
    int64_t cap;
    tpa_str **d;
} tpa_str_list;
#else
typedef struct tpa_str_list {
    tpa_rc_t rc;
    int64_t len;
    tpa_str *d[];
} tpa_str_list;
#endif

static TPA_UNUSED int tpa_str_list__alloc(int64_t n, tpa_str_list **out)
{
    tpa_str_list *l;
    if (n < 0 || n > (INT64_MAX - (int64_t)sizeof(tpa_str_list)) / (int64_t)sizeof(tpa_str *)) {
        return tpa_raise(TPA_MemoryError, "");
    }
#if defined(TPA_GROW_STR)
    l = (tpa_str_list *)calloc(1, sizeof(tpa_str_list));
    if (l == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    if (n > 0) {
        l->d = (tpa_str **)calloc((size_t)n, sizeof(tpa_str *));
        if (l->d == NULL) {
            free(l);
            return tpa_raise(TPA_MemoryError, "");
        }
    }
    l->cap = n;
#else
    l = (tpa_str_list *)calloc(1, sizeof(tpa_str_list) + (size_t)n * sizeof(tpa_str *));
    if (l == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
#endif
    l->rc = 1;
    l->len = n;
    *out = l;
    return 0;
}

static inline void tpa_str_list_retain(tpa_str_list *l)
{
    if (l != NULL) {
        TPA_RC_INC(l);
    }
}

static TPA_UNUSED void tpa_str_list_release(tpa_str_list *l)
{
    int64_t i;
    if (l != NULL && TPA_RC_DEC_IS_ZERO(l)) {
        for (i = 0; i < l->len; i++) {
            tpa_str_release(l->d[i]);
        }
#if defined(TPA_GROW_STR)
        free(l->d);
#endif
        free(l);
    }
}

static inline void tpa_str_list_set(tpa_str_list **slot, tpa_str_list *v)
{
    tpa_str_list_retain(v);
    tpa_str_list_release(*slot);
    *slot = v;
}

static inline void tpa_str_list_clear(tpa_str_list **slot)
{
    tpa_str_list_release(*slot);
    *slot = NULL;
}

/* [parts[0], ..., parts[n-1]] (borrowed parts: each is retained). */
static TPA_UNUSED int tpa_str_list_from(tpa_str *const *parts, int64_t n, tpa_str_list **out)
{
    int64_t i;
    if (tpa_str_list__alloc(n, out)) {
        return -1;
    }
    for (i = 0; i < n; i++) {
        tpa_str_retain(parts[i]);
        (*out)->d[i] = parts[i];
    }
    return 0;
}

/* l[i]: a new reference (list semantics: negative from the end, IndexError("list index out of range")). */
static TPA_UNUSED int tpa_str_list_get(tpa_str_list *l, int64_t i, tpa_str **out)
{
    int64_t slot;
    if (tpa_slot(l->len, i, 0, &slot)) {
        return -1;
    }
    tpa_str_retain(l->d[slot]);
    *out = l->d[slot];
    return 0;
}

/* Tier S2a: searching a list of str (index / count / in), equality as str ==; the value is borrowed. index formats the
 * value: ValueError("list.index(x): x not in list") as CPython 3.14 and later. */
static TPA_UNUSED int tpa_str_list_contains(const tpa_str_list *l, tpa_str *v)
{
    int64_t i;
    for (i = 0; i < l->len; i++) {
        if (tpa_str_eq(l->d[i], v)) {
            return 1;
        }
    }
    return 0;
}

static TPA_UNUSED int64_t tpa_str_list_count(const tpa_str_list *l, tpa_str *v)
{
    int64_t i, k = 0;
    for (i = 0; i < l->len; i++) {
        k += tpa_str_eq(l->d[i], v) ? 1 : 0;
    }
    return k;
}

static TPA_UNUSED int tpa_str_list_index(const tpa_str_list *l, tpa_str *v, int64_t *out)
{
    int64_t i;
    for (i = 0; i < l->len; i++) {
        if (tpa_str_eq(l->d[i], v)) {
            *out = i;
            return 0;
        }
    }
    return tpa_raise(TPA_ValueError, "list.index(x): x not in list");
}

#if defined(TPA_GROW_STR)
static int tpa_str_list__resize(tpa_str_list *l, int64_t n)
{
    uint64_t want;
    tpa_str **d;
    if (TPA__RESIZE_IN_PLACE && l->cap >= n && n >= (l->cap >> 1)) {
        l->len = n;
        return 0;
    }
    want = ((uint64_t)n + ((uint64_t)n >> 3) + 6u) & ~(uint64_t)3;
    if ((uint64_t)(n - l->len) > want - (uint64_t)n) {
        want = ((uint64_t)n + 3u) & ~(uint64_t)3;
    }
    if (n == 0) {
        want = 0;
    }
    if (want > (uint64_t)(SIZE_MAX / sizeof(tpa_str *)) || want > (uint64_t)INT64_MAX) {
        return tpa_raise(TPA_MemoryError, "");
    }
    if (want == 0) {
        free(l->d);
        d = NULL;
    }
    else {
        d = (tpa_str **)tpa__list_realloc(l->d, (size_t)want * sizeof(tpa_str *),
                                          (size_t)(l->len < n ? l->len : n) * sizeof(tpa_str *));
        if (d == NULL) {
            return tpa_raise(TPA_MemoryError, "");
        }
    }
    l->d = d;
    l->cap = (int64_t)want;
    l->len = n;
    return 0;
}

static TPA_UNUSED int tpa_str_list_append(tpa_str_list *l, tpa_str *v)
{
    if (tpa_str_list__resize(l, l->len + 1)) {
        return -1;
    }
    tpa_str_retain(v);
    l->d[l->len - 1] = v;
    return 0;
}

static TPA_UNUSED int tpa_str_list_insert(tpa_str_list *l, int64_t i, tpa_str *v)
{
    int64_t n = l->len;
    if (i < 0) {
        i += n;
        if (i < 0) {
            i = 0;
        }
    }
    if (i > n) {
        i = n;
    }
    if (tpa_str_list__resize(l, n + 1)) {
        return -1;
    }
    memmove(l->d + i + 1, l->d + i, (size_t)(n - i) * sizeof(tpa_str *));
    tpa_str_retain(v);
    l->d[i] = v;
    return 0;
}

static TPA_UNUSED int tpa_str_list_extend(tpa_str_list *l, const tpa_str_list *o)
{
    int64_t n = l->len, m = o->len, k;
    if (m == 0) {
        return 0;
    }
    if (tpa_str_list__resize(l, n + m)) {
        return -1;
    }
    for (k = 0; k < m; k++) {
        tpa_str_retain(o->d[k]);
        l->d[n + k] = o->d[k];
    }
    return 0;
}

/* list.clear() (tpa_str_list_clear is the slot helper above) */
static TPA_UNUSED void tpa_str_list_lclear(tpa_str_list *l)
{
    int64_t i, n = l->len;
    tpa_str **d = l->d;
    l->d = NULL;
    l->len = 0;
    l->cap = 0;
    for (i = 0; i < n; i++) {
        tpa_str_release(d[i]);
    }
    free(d);
}

static TPA_UNUSED int tpa_str_list_pop(tpa_str_list *l, int has_index, int64_t i, tpa_str **out)
{
    int64_t n = l->len;
    tpa_str *v;
    if (n == 0) {
        return tpa_raise(TPA_IndexError, "pop from empty list");
    }
    if (!has_index) {
        i = n - 1;
    }
    if (i < 0) {
        i += n;
    }
    if (i < 0 || i >= n) {
        return tpa_raise(TPA_IndexError, "pop index out of range");
    }
    v = l->d[i];
    memmove(l->d + i, l->d + i + 1, (size_t)(n - i - 1) * sizeof(tpa_str *));
    if (tpa_str_list__resize(l, n - 1)) {
        l->len = n - 1;
    }
    tpa_str_release(*out);
    *out = v;                               /* the reference moves out of the list */
    return 0;
}

#define TPA__STR_RET(x) tpa_str_retain(x)
#define TPA__STR_REL(x) tpa_str_release(x)
TPA__DEF_LIST_SPLICE(tpa_str_list_, tpa_str_list, tpa_str *, TPA__STR_RET, TPA__STR_REL, tpa_str_list__resize)

static TPA_UNUSED int tpa_str_list_remove(tpa_str_list *l, tpa_str *v)
{
    int64_t i;
    tpa_str *got = NULL;
    for (i = 0; i < l->len; i++) {
        if (tpa_str_eq(l->d[i], v)) {
            tpa_str_list_pop(l, 1, i, &got);
            tpa_str_release(got);
            return 0;
        }
    }
    return tpa_raise(TPA_ValueError, "list.remove(x): x not in list");
}
#endif

#define TPA__OWN_STR(slot, v) ((slot) = (v))
/* Tier S2a: a + b, a * n and a[start:stop:step] of lists of str: new lists, each item retained (the slots of a fresh list
 * hold NULL until written, so a failure releases what was written). */
static TPA_UNUSED int tpa_str_list_concat(const tpa_str_list *a, const tpa_str_list *b, tpa_str_list **out)
{
    int64_t i;
    if (a->len > INT64_MAX - b->len) {
        return tpa_raise(TPA_MemoryError, "");
    }
    if (tpa_str_list__alloc(a->len + b->len, out)) {
        return -1;
    }
    for (i = 0; i < a->len; i++) {
        tpa_str_retain(a->d[i]);
        TPA__OWN_STR((*out)->d[i], a->d[i]);
    }
    for (i = 0; i < b->len; i++) {
        tpa_str_retain(b->d[i]);
        TPA__OWN_STR((*out)->d[a->len + i], b->d[i]);
    }
    return 0;
}

static TPA_UNUSED int tpa_str_list_repeat(const tpa_str_list *a, int64_t n, tpa_str_list **out)
{
    int64_t total, k;
    if (tpa__repeat_size(a->len, n, &total) || tpa_str_list__alloc(total, out)) {
        return -1;
    }
    for (k = 0; k < total; k++) {
        tpa_str_retain(a->d[k % a->len]);
        TPA__OWN_STR((*out)->d[k], a->d[k % a->len]);
    }
    return 0;
}

static TPA_UNUSED int tpa_str_list_slice(const tpa_str_list *a, int64_t start, int64_t stop, int64_t step, int has, tpa_str_list **out)
{
    int64_t first, by, n, k;
    if (tpa__slice(a->len, start, stop, step, has, &first, &by, &n) || tpa_str_list__alloc(n, out)) {
        return -1;
    }
    for (k = 0; k < n; k++) {
        tpa_str_retain(a->d[first + k * by]);
        TPA__OWN_STR((*out)->d[k], a->d[first + k * by]);
    }
    return 0;
}

/* Tier S2a: a op b of two lists of str (as tpa_i64_cmp; items by str == and code-point order) */
static TPA_UNUSED int tpa_str_list_cmp(const tpa_str_list *a, const tpa_str_list *b, int op)
{
    int64_t i, n = a->len < b->len ? a->len : b->len;
    for (i = 0; i < n; i++) {
        if (!tpa_str_eq(a->d[i], b->d[i])) {
            if (op <= 1) {
                return op == 1;
            }
            return tpa__cmp_outcome(op, tpa_str_cmp(a->d[i], b->d[i]));
        }
    }
    return tpa__cmp_outcome(op, a->len < b->len ? -1 : a->len > b->len ? 1 : 0);
}

static TPA_UNUSED void tpa_str_list_reverse(tpa_str_list *l)
{
    int64_t i, j;
    for (i = 0, j = l->len - 1; i < j; i++, j--) {
        tpa_str *t = l->d[i];
        l->d[i] = l->d[j];
        l->d[j] = t;
    }
}

/* A growing list while split builds its result (a private buffer, not a value). */
typedef struct {
    tpa_str **d;
    int64_t n, cap;
} tpa_str__acc;

static int tpa_str__acc_push(tpa_str__acc *a, tpa_str *s)       /* takes the reference s */
{
    if (a->n == a->cap) {
        int64_t cap = a->cap ? a->cap * 2 : 8;
        tpa_str **d = (tpa_str **)realloc(a->d, (size_t)cap * sizeof(tpa_str *));
        if (d == NULL) {
            tpa_str_release(s);
            return tpa_raise(TPA_MemoryError, "");
        }
        a->d = d;
        a->cap = cap;
    }
    a->d[a->n++] = s;
    return 0;
}

static int tpa_str__acc_slice(tpa_str__acc *a, tpa_str *s, int64_t lo, int64_t hi)
{
    tpa_str *piece = NULL;
    if (tpa_str_slice(s, lo, hi, 1, TPA_SLICE_START | TPA_SLICE_STOP, &piece)) {
        return -1;
    }
    return tpa_str__acc_push(a, piece);
}

static int tpa_str__acc_finish(tpa_str__acc *a, int rc, tpa_str_list **out)
{
    int64_t i;
    if (rc == 0 && tpa_str_list__alloc(a->n, out) == 0) {
        for (i = 0; i < a->n; i++) {
            (*out)->d[i] = a->d[i];             /* the list takes each reference */
        }
        free(a->d);
        return 0;
    }
    for (i = 0; i < a->n; i++) {
        tpa_str_release(a->d[i]);
    }
    free(a->d);
    return -1;
}

/* repr(l): "['a', 'b']", each element's repr. */
static TPA_UNUSED int tpa_str_list_repr(tpa_str_list *l, tpa_str **out)
{
    int64_t i, k = 0, n = l->len == 0 ? 1 : 2 * l->len + 1;
    tpa_str **parts = (tpa_str **)calloc((size_t)n + 1, sizeof(tpa_str *));
    int rc = 0;
    if (parts == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    parts[k++] = &tpa_str__latin1['['].h;
    for (i = 0; i < l->len && rc == 0; i++) {
        if (i > 0) {
            rc = tpa_str_from_ascii(", ", 2, &parts[k++]);
        }
        if (rc == 0) {
            rc = tpa_str_repr(l->d[i], &parts[k++]);
        }
    }
    parts[k++] = &tpa_str__latin1[']'].h;
    if (rc == 0) {
        rc = tpa_str_join(parts, (size_t)k, out);
    }
    for (i = 0; i < k; i++) {
        tpa_str_release(parts[i]);
    }
    free(parts);
    return rc;
}

/* Tier S2a: repr(l) of a list of float / int: "[1.5, -0.0, 3]", each item's repr (all ASCII), built in one byte
 * buffer. An int past the digit limit raises CPython's ValueError, as repr of the list does. */
typedef struct tpa__abuf { char *p; size_t n, cap; } tpa__abuf;

static int tpa__abuf_put(tpa__abuf *b, const char *s, size_t n)
{
    if (b->n + n + 1 > b->cap) {
        size_t cap = b->cap ? b->cap : 64;
        char *p;
        while (cap < b->n + n + 1) {
            if (cap > SIZE_MAX / 2) {
                return tpa_raise(TPA_MemoryError, "");
            }
            cap *= 2;
        }
        p = (char *)realloc(b->p, cap);
        if (p == NULL) {
            return tpa_raise(TPA_MemoryError, "");
        }
        b->p = p;
        b->cap = cap;
    }
    memcpy(b->p + b->n, s, n);
    b->n += n;
    return 0;
}

static int tpa__abuf_done(tpa__abuf *b, int rc, tpa_str **out)
{
    if (rc == 0) {
        rc = tpa__abuf_put(b, "]", 1);
    }
    if (rc == 0) {
        rc = tpa_str_from_ascii(b->p, b->n, out);
    }
    free(b->p);
    return rc;
}

static TPA_UNUSED int tpa_f64_list_repr(const tpa_f64_list *l, tpa_str **out)
{
    tpa__abuf b = {NULL, 0, 0};
    char buf[48];
    int64_t i;
    int rc = tpa__abuf_put(&b, "[", 1);
    for (i = 0; i < l->len && rc == 0; i++) {
        int n = tpa_repr_f64(l->d[i], buf);
        rc = (i > 0 ? tpa__abuf_put(&b, ", ", 2) : 0);
        if (rc == 0) {
            rc = tpa__abuf_put(&b, buf, (size_t)n);
        }
    }
    return tpa__abuf_done(&b, rc, out);
}

static TPA_UNUSED int tpa_i64_list_repr(const tpa_i64_list *l, tpa_str **out)
{
    tpa__abuf b = {NULL, 0, 0};
    char buf[32];
    int64_t i;
    int rc = tpa__abuf_put(&b, "[", 1);
    for (i = 0; i < l->len && rc == 0; i++) {
        int n = snprintf(buf, sizeof buf, "%lld", (long long)l->d[i]);
        rc = (i > 0 ? tpa__abuf_put(&b, ", ", 2) : 0);
        if (rc == 0) {
            rc = tpa__abuf_put(&b, buf, (size_t)n);
        }
    }
    return tpa__abuf_done(&b, rc, out);
}

static TPA_UNUSED int tpa_int_list_repr(const tpa_int_list *l, tpa_str **out)
{
    tpa__abuf b = {NULL, 0, 0};
    int64_t i;
    int rc = tpa__abuf_put(&b, "[", 1);
    for (i = 0; i < l->len && rc == 0; i++) {
        char *digits = NULL;
        size_t n = 0;
        rc = (i > 0 ? tpa__abuf_put(&b, ", ", 2) : 0);
        if (rc == 0) {
            rc = tpa_int_to_str(l->d[i], &digits, &n);
        }
        if (rc == 0) {
            rc = tpa__abuf_put(&b, digits, n);
            free(digits);
        }
    }
    return tpa__abuf_done(&b, rc, out);
}

/* sep.join(l). */
static TPA_UNUSED int tpa_str_join_list(tpa_str *sep, tpa_str_list *l, tpa_str **out)
{
    int64_t i, k = 0;
    int rc;
    tpa_str **parts;
    if (l->len == 0) {
        *out = &tpa_str__empty;
        return 0;
    }
    if (l->len == 1) {
        tpa_str_retain(l->d[0]);
        *out = l->d[0];
        return 0;
    }
    parts = (tpa_str **)malloc((size_t)(2 * l->len) * sizeof(tpa_str *));
    if (parts == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    for (i = 0; i < l->len; i++) {
        if (i > 0) {
            parts[k++] = sep;
        }
        parts[k++] = l->d[i];
    }
    rc = tpa_str_join(parts, (size_t)k, out);
    free(parts);
    return rc;
}

/* s.split(sep[, maxsplit]) with a str separator (stringlib split): ValueError("empty separator"). */
static TPA_UNUSED int tpa_str_split_sep(tpa_str *s, tpa_str *sep, int64_t maxsplit, tpa_str_list **out)
{
    tpa_str__acc a = {NULL, 0, 0};
    int64_t i = 0, rc = 0;
    if (sep->len == 0) {
        return tpa_raise(TPA_ValueError, "empty separator");
    }
    if (maxsplit < 0) {
        maxsplit = INT64_MAX;
    }
    while (maxsplit-- > 0 && rc == 0) {
        int64_t pos = -1, j, m;
        for (j = i; j + sep->len <= s->len; j++) {
            for (m = 0; m < sep->len && tpa_str_at(s, j + m) == tpa_str_at(sep, m); m++) {
            }
            if (m == sep->len) {
                pos = j;
                break;
            }
        }
        if (pos < 0) {
            break;
        }
        rc = tpa_str__acc_slice(&a, s, i, pos);
        i = pos + sep->len;
    }
    if (rc == 0) {
        rc = tpa_str__acc_slice(&a, s, i, s->len);
    }
    return tpa_str__acc_finish(&a, (int)rc, out);
}

#if defined(TPA_UCD_SPACE)
static inline int tpa_str__space(uint32_t c) { return tpa_ucd_isspace(c); }

/* s.split() / s.split(None, maxsplit) (stringlib split_whitespace). */
static TPA_UNUSED int tpa_str_split_ws(tpa_str *s, int64_t maxsplit, tpa_str_list **out)
{
    tpa_str__acc a = {NULL, 0, 0};
    int64_t i = 0, j, len = s->len;
    int rc = 0;
    if (maxsplit < 0) {
        maxsplit = INT64_MAX;
    }
    while (maxsplit-- > 0 && rc == 0) {
        while (i < len && tpa_str__space(tpa_str_at(s, i))) {
            i++;
        }
        if (i == len) {
            break;
        }
        j = i;
        i++;
        while (i < len && !tpa_str__space(tpa_str_at(s, i))) {
            i++;
        }
        rc = tpa_str__acc_slice(&a, s, j, i);
    }
    if (rc == 0 && i < len) {
        while (i < len && tpa_str__space(tpa_str_at(s, i))) {
            i++;
        }
        if (i != len) {
            rc = tpa_str__acc_slice(&a, s, i, len);
        }
    }
    return tpa_str__acc_finish(&a, rc, out);
}
#endif

/* strip / lstrip / rstrip of the code points of `chars` (`how`: 1 left, 2 right, 3 both). */
static TPA_UNUSED int tpa_str_strip(tpa_str *s, tpa_str *chars, int how, tpa_str **out)
{
    int64_t lo = 0, hi = s->len;
#define TPA__STRIPPED(c) tpa_str__has(chars, (c))
    if (how & 1) {
        while (lo < hi && TPA__STRIPPED(tpa_str_at(s, lo))) {
            lo++;
        }
    }
    if (how & 2) {
        while (hi > lo && TPA__STRIPPED(tpa_str_at(s, hi - 1))) {
            hi--;
        }
    }
#undef TPA__STRIPPED
    return tpa_str_slice(s, lo, hi, 1, TPA_SLICE_START | TPA_SLICE_STOP, out);
}

#if defined(TPA_UCD_SPACE)
/* strip() / lstrip() / rstrip() (and with chars None): the whitespace of str.isspace. */
static TPA_UNUSED int tpa_str_strip_ws(tpa_str *s, int how, tpa_str **out)
{
    int64_t lo = 0, hi = s->len;
    if (how & 1) {
        while (lo < hi && tpa_ucd_isspace(tpa_str_at(s, lo))) {
            lo++;
        }
    }
    if (how & 2) {
        while (hi > lo && tpa_ucd_isspace(tpa_str_at(s, hi - 1))) {
            hi--;
        }
    }
    return tpa_str_slice(s, lo, hi, 1, TPA_SLICE_START | TPA_SLICE_STOP, out);
}
#endif

static TPA_UNUSED int tpa_str_startswith(tpa_str *s, tpa_str *p)
{
    int64_t i;
    if (p->len > s->len) {
        return 0;
    }
    for (i = 0; i < p->len; i++) {
        if (tpa_str_at(s, i) != tpa_str_at(p, i)) {
            return 0;
        }
    }
    return 1;
}

static TPA_UNUSED int tpa_str_endswith(tpa_str *s, tpa_str *p)
{
    int64_t i, off = s->len - p->len;
    if (off < 0) {
        return 0;
    }
    for (i = 0; i < p->len; i++) {
        if (tpa_str_at(s, off + i) != tpa_str_at(p, i)) {
            return 0;
        }
    }
    return 1;
}

/* A code point buffer while a result is built (replace, lower, upper); tpa_str_from_cps normalises the kind. */
typedef struct {
    uint32_t *d;
    int64_t n, cap;
} tpa_str__cps;

static int tpa_str__cps_put(tpa_str__cps *b, uint32_t c)
{
    if (b->n == b->cap) {
        int64_t cap = b->cap ? b->cap * 2 : 64;
        uint32_t *d = (uint32_t *)realloc(b->d, (size_t)cap * sizeof(uint32_t));
        if (d == NULL) {
            return tpa_raise(TPA_MemoryError, "");
        }
        b->d = d;
        b->cap = cap;
    }
    b->d[b->n++] = c;
    return 0;
}

static int tpa_str__cps_str(tpa_str__cps *b, tpa_str *s, int64_t lo, int64_t hi)
{
    int64_t i;
    for (i = lo; i < hi; i++) {
        if (tpa_str__cps_put(b, tpa_str_at(s, i))) {
            return -1;
        }
    }
    return 0;
}

static int tpa_str__cps_done(tpa_str__cps *b, int rc, tpa_str **out)
{
    if (rc == 0) {
        rc = tpa_str_from_cps(b->d, (size_t)b->n, out);
    }
    free(b->d);
    return rc;
}

/* s.replace(old, new[, count]) (CPython's replace: an empty `old` inserts `new` before every code point and at the
 * end, up to `count` times; a negative count is no limit; a count of 0 is s). */
static TPA_UNUSED int tpa_str_replace(tpa_str *s, tpa_str *old, tpa_str *new_, int64_t count, tpa_str **out)
{
    tpa_str__cps b = {NULL, 0, 0};
    int64_t i = 0, done = 0;
    int rc = 0;
    if (count < 0) {
        count = INT64_MAX;
    }
    if (count == 0) {
        tpa_str_retain(s);
        *out = s;
        return 0;
    }
    if (old->len == 0) {
        for (i = 0; i <= s->len && rc == 0; i++) {
            if (done < count) {
                rc = tpa_str__cps_str(&b, new_, 0, new_->len);
                done++;
            }
            if (rc == 0 && i < s->len) {
                rc = tpa_str__cps_put(&b, tpa_str_at(s, i));
            }
        }
        return tpa_str__cps_done(&b, rc, out);
    }
    while (i < s->len && rc == 0) {
        int64_t m = 0;
        if (done < count && i + old->len <= s->len) {
            for (m = 0; m < old->len && tpa_str_at(s, i + m) == tpa_str_at(old, m); m++) {
            }
        }
        if (done < count && m == old->len && old->len > 0 && i + old->len <= s->len) {
            rc = tpa_str__cps_str(&b, new_, 0, new_->len);
            i += old->len;
            done++;
        }
        else {
            rc = tpa_str__cps_put(&b, tpa_str_at(s, i));
            i++;
        }
    }
    if (done == 0 && rc == 0) {
        free(b.d);
        tpa_str_retain(s);
        *out = s;
        return 0;
    }
    return tpa_str__cps_done(&b, rc, out);
}

#if defined(TPA_UCD_ALPHA)
static TPA_UNUSED int tpa_str_isalpha(tpa_str *s)
{
    int64_t i;
    for (i = 0; i < s->len; i++) {
        if (!tpa_ucd_isalpha(tpa_str_at(s, i))) {
            return 0;
        }
    }
    return s->len > 0;
}
#endif

#if defined(TPA_UCD_DIGIT)
static TPA_UNUSED int tpa_str_isdigit(tpa_str *s)
{
    int64_t i;
    for (i = 0; i < s->len; i++) {
        if (!tpa_ucd_isdigit(tpa_str_at(s, i))) {
            return 0;
        }
    }
    return s->len > 0;
}
#endif

#if defined(TPA_UCD_UPPER)
static TPA_UNUSED int tpa_str_upper(tpa_str *s, tpa_str **out)
{
    tpa_str__cps b = {NULL, 0, 0};
    uint32_t m[3];
    int64_t i;
    int k, n, rc = 0;
    for (i = 0; i < s->len && rc == 0; i++) {
        n = tpa_ucd_upper(tpa_str_at(s, i), m);
        for (k = 0; k < n && rc == 0; k++) {
            rc = tpa_str__cps_put(&b, m[k]);
        }
    }
    return tpa_str__cps_done(&b, rc, out);
}
#endif

#if defined(TPA_UCD_LOWER)
/* CPython's handle_capital_sigma: U+03A3 lowers to the final sigma U+03C2 when a cased code point precedes it
 * (case-ignorable ones skipped) and none follows it (the same), else to U+03C3. */
static uint32_t tpa_str__sigma(tpa_str *s, int64_t i)
{
    int64_t j;
    uint32_t c = 0;
    int final_sigma;
    for (j = i - 1; j >= 0; j--) {
        c = tpa_str_at(s, j);
        if (!tpa_ucd_isignorable(c)) {
            break;
        }
    }
    final_sigma = j >= 0 && tpa_ucd_iscased(c);
    if (final_sigma && i + 1 < s->len) {
        for (j = i + 1; j < s->len; j++) {
            c = tpa_str_at(s, j);
            if (!tpa_ucd_isignorable(c)) {
                break;
            }
        }
        final_sigma = j == s->len || !tpa_ucd_iscased(c);
    }
    return final_sigma ? 0x3C2u : 0x3C3u;
}

static TPA_UNUSED int tpa_str_lower(tpa_str *s, tpa_str **out)
{
    tpa_str__cps b = {NULL, 0, 0};
    uint32_t m[3];
    int64_t i;
    int k, n, rc = 0;
    for (i = 0; i < s->len && rc == 0; i++) {
        uint32_t c = tpa_str_at(s, i);
        if (c == 0x3A3u) {
            rc = tpa_str__cps_put(&b, tpa_str__sigma(s, i));
            continue;
        }
        n = tpa_ucd_lower(c, m);
        for (k = 0; k < n && rc == 0; k++) {
            rc = tpa_str__cps_put(&b, m[k]);
        }
    }
    return tpa_str__cps_done(&b, rc, out);
}
#endif


/* An int argument that CPython converts to Py_ssize_t (split's maxsplit, replace's count). */
/* (tpa_int_to_ssize: tp_aot_int.h) */

/* CPython's ADJUST_INDICES of find / startswith / endswith (`has`: TPA_SLICE_START | TPA_SLICE_STOP for given bounds;
 * a bound outside 64 bits is clamped first). start is never clamped upward. */
static void tpa_str__adjust(int64_t len, int64_t *start, int64_t *end, int has)
{
    if (!(has & TPA_SLICE_START)) {
        *start = 0;
    }
    if (!(has & TPA_SLICE_STOP)) {
        *end = INT64_MAX;
    }
    if (*end > len) {
        *end = len;
    }
    else if (*end < 0) {
        *end += len;
        if (*end < 0) {
            *end = 0;
        }
    }
    if (*start < 0) {
        *start += len;
        if (*start < 0) {
            *start = 0;
        }
    }
}

/* s.find(sub, start, end). */
static TPA_UNUSED int64_t tpa_str_find_range(tpa_str *s, tpa_str *sub, int64_t start, int64_t end, int has)
{
    int64_t i, m;
    tpa_str__adjust(s->len, &start, &end, has);
    if (end - start < sub->len) {
        return -1;
    }
    if (sub->len == 0) {
        return start;
    }
    for (i = start; i + sub->len <= end; i++) {
        for (m = 0; m < sub->len && tpa_str_at(s, i + m) == tpa_str_at(sub, m); m++) {
        }
        if (m == sub->len) {
            return i;
        }
    }
    return -1;
}

/* s.startswith(p, start, end) / (`suffix`) s.endswith(p, start, end): CPython's tailmatch. */
static TPA_UNUSED int tpa_str_tailmatch(tpa_str *s, tpa_str *p, int64_t start, int64_t end, int has, int suffix)
{
    int64_t off, i;
    tpa_str__adjust(s->len, &start, &end, has);
    end -= p->len;
    if (end < start) {
        return 0;
    }
    if (p->len == 0) {
        return 1;
    }
    off = suffix ? end : start;
    for (i = 0; i < p->len; i++) {
        if (tpa_str_at(s, off + i) != tpa_str_at(p, i)) {
            return 0;
        }
    }
    return 1;
}


/* ------------------------------------------------------------------------------------------
 * Tier S2a: `str | None`, a tpa_str * that is NULL for None.
 * ------------------------------------------------------------------------------------------ */
#if defined(TP_CHECK_PROVEN)
static TPA_UNUSED void tpa_str__narrow_failed(void)
{
    fflush(stdout);
    fprintf(stderr, "SystemError: a `str | None` value proved not None is None\n");
    abort();
}
#define TPA_STR_NARROW(x) ((x) != NULL ? (x) : (tpa_str__narrow_failed(), (x)))
#else
#define TPA_STR_NARROW(x) (x)
#endif

/* == of two `str | None` values: None equals only None. */
static TPA_UNUSED int tpa_str_eq_opt(tpa_str *a, tpa_str *b)
{
    if (a == NULL || b == NULL) {
        return a == b;
    }
    return tpa_str_eq(a, b);
}

/* str(x) / repr(x) of a `str | None`: "None", or the string / its repr. */
static TPA_UNUSED int tpa_str_of_opt(tpa_str *s, int repr, tpa_str **out)
{
    if (s == NULL) {
        return tpa_str_from_ascii("None", 4, out);
    }
    if (repr) {
        return tpa_str_repr(s, out);
    }
    tpa_str_retain(s);
    *out = s;
    return 0;
}

#endif /* TP_AOT_STR_H */
