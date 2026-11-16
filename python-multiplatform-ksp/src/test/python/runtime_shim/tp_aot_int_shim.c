/* Test-only shim of tp_aot_int.h for test_aot_int.py (#460; agent proposal, not owner-confirmed).
 *
 * It is built as a shared library (`aot/cc.py compile_library`, so with -DTPA_LIBRARY: atomic counts) and called
 * through ctypes. Every function takes and returns decimal strings, doubles and int64s, or opaque handles (a
 * handle is a `tpa_int` word that the test never looks into), so no test depends on the internal layout.
 *
 * A status is 0 or more for success and -1 for "the runtime raised" (read the class and message with
 * `shim_last_error`). Where the result is a value, a success status says how it is represented: bit 0 set
 * when the result is small, so the tests can check the canonical form. `shim_from_str` returns
 * TPA_INT_NOT_HANDLED (1 with no error) for an input the runtime declines.
 *
 * The header is compiled with -DTPA_INT_COUNT_LIVE here and only here: `shim_live()` is the number of big
 * objects allocated and not yet freed, which is how the tests observe a leak without a leak checker.
 * The header and this file include no interpreter header and name no interpreter API. */
#define TPA_INT_COUNT_LIVE 1
#include "tp_aot.h"
#include "tp_aot_int.h"

#ifndef TPA_EXPORT
#if defined(_WIN32) || defined(__CYGWIN__)
#define TPA_EXPORT __declspec(dllexport)
#else
#define TPA_EXPORT __attribute__((visibility("default")))
#endif
#endif

static void clear_error(void)
{
    tpa_exc.kind = 0;
    tpa_exc.msg[0] = '\0';
}

TPA_EXPORT int shim_last_error(const char **cls, const char **msg)
{
    if (tpa_exc.kind == 0) {
        return 1;
    }
    *cls = tpa_exc_name(tpa_exc.kind);
    *msg = tpa_exc.msg;
    return 0;
}

TPA_EXPORT void shim_free(char *p) { free(p); }
TPA_EXPORT int64_t shim_live(void) { return tpa_int_live(); }
TPA_EXPORT int shim_threads(void)
{
#if defined(TPA_THREADS)
    return 1;
#else
    return 0;
#endif
}

/* ---- handles ------------------------------------------------------------------------------------ */

/* Parses a NUL-terminated decimal string with tpa_int_from_str. */
TPA_EXPORT int shim_h_parse(const char *s, uintptr_t *h)
{
    tpa_int v;
    int rc;
    clear_error();
    rc = tpa_int_from_str(s, strlen(s), &v);
    if (rc == 0) {
        *h = v;
        return tpa_int_is_small(v);
    }
    return rc;
}

TPA_EXPORT int shim_h_from_i64(int64_t v, uintptr_t *h)
{
    tpa_int r;
    clear_error();
    if (tpa_int_from_i64(v, &r) != 0) {
        return -1;
    }
    *h = r;
    return tpa_int_is_small(r);
}

TPA_EXPORT int shim_h_is_small(uintptr_t h) { return tpa_int_is_small(h); }
TPA_EXPORT void shim_h_retain(uintptr_t h) { tpa_int_retain(h); }
TPA_EXPORT void shim_h_release(uintptr_t h) { tpa_int_release(h); }

TPA_EXPORT int shim_h_str(uintptr_t h, char **out)
{
    size_t len;
    clear_error();
    return tpa_int_to_str(h, out, &len) == 0 ? 0 : -1;
}

TPA_EXPORT int shim_h_cmp(uintptr_t a, uintptr_t b) { return tpa_int_cmp(a, b); }
TPA_EXPORT int shim_h_eq(uintptr_t a, uintptr_t b) { return tpa_int_eq(a, b); }

/* op: 0 add, 1 sub, 2 mul, 3 floordiv, 4 mod. */
TPA_EXPORT int shim_h_binop(int op, uintptr_t a, uintptr_t b, uintptr_t *h)
{
    tpa_int r;
    int rc;
    clear_error();
    switch (op) {
    case 0: rc = tpa_int_add(a, b, &r); break;
    case 1: rc = tpa_int_sub(a, b, &r); break;
    case 2: rc = tpa_int_mul(a, b, &r); break;
    case 3: rc = tpa_int_floordiv(a, b, &r); break;
    case 4: rc = tpa_int_mod(a, b, &r); break;
    default: return -2;
    }
    if (rc != 0) {
        return -1;
    }
    *h = r;
    return tpa_int_is_small(r);
}

/* ---- strings in, strings out ------------------------------------------------------------------------ */

/* Frees the two operands and turns the result handle into a string. */
static int finish_str(uintptr_t r, char **out)
{
    size_t len;
    int rc = tpa_int_to_str(r, out, &len);
    int small = tpa_int_is_small(r);
    tpa_int_release(r);
    return rc == 0 ? small : -1;
}

/* op: 0 add, 1 sub, 2 mul, 3 floordiv, 4 mod, 5 cmp ("-1", "0", "1"), 6 eq ("0" or "1"). Returns -1 when the
 * operation raised, -3 when an operand could not be parsed (the error is the parser's), else 0 or 1 (the
 * result is small). */
TPA_EXPORT int shim_binop(int op, const char *a, const char *b, char **out)
{
    uintptr_t x, y, r;
    int rc;
    clear_error();
    if (tpa_int_from_str(a, strlen(a), &x) != 0) {
        return -3;
    }
    if (tpa_int_from_str(b, strlen(b), &y) != 0) {
        tpa_int_release(x);
        return -3;
    }
    if (op == 5 || op == 6) {
        int v = op == 5 ? tpa_int_cmp(x, y) : tpa_int_eq(x, y);
        char *s = (char *)malloc(4);
        tpa_int_release(x);
        tpa_int_release(y);
        if (s == NULL) {
            return -1;
        }
        snprintf(s, 4, "%d", v);
        *out = s;
        return 1;
    }
    rc = shim_h_binop(op, x, y, &r);
    tpa_int_release(x);
    tpa_int_release(y);
    if (rc < 0) {
        return rc;
    }
    return finish_str(r, out);
}

/* divmod: *q and *r are strings; returns -1, -3 or bit 0 (q small) | bit 1 (r small). */
TPA_EXPORT int shim_divmod(const char *a, const char *b, char **q, char **r)
{
    tpa_int x, y, qq, rr;
    int small;
    size_t ql, rl;
    clear_error();
    if (tpa_int_from_str(a, strlen(a), &x) != 0) {
        return -3;
    }
    if (tpa_int_from_str(b, strlen(b), &y) != 0) {
        tpa_int_release(x);
        return -3;
    }
    if (tpa_int_divmod(x, y, &qq, &rr) != 0) {
        tpa_int_release(x);
        tpa_int_release(y);
        return -1;
    }
    tpa_int_release(x);
    tpa_int_release(y);
    small = tpa_int_is_small(qq) | (tpa_int_is_small(rr) << 1);
    if (tpa_int_to_str(qq, q, &ql) != 0) {
        tpa_int_release(qq);
        tpa_int_release(rr);
        return -1;
    }
    if (tpa_int_to_str(rr, r, &rl) != 0) {
        free(*q);
        tpa_int_release(qq);
        tpa_int_release(rr);
        return -1;
    }
    tpa_int_release(qq);
    tpa_int_release(rr);
    return small;
}

/* op: 0 neg, 1 abs, 2 identity (parse then print). */
TPA_EXPORT int shim_unop(int op, const char *a, char **out)
{
    tpa_int x, r;
    int rc;
    clear_error();
    if (tpa_int_from_str(a, strlen(a), &x) != 0) {
        return -3;
    }
    if (op == 0) {
        rc = tpa_int_neg(x, &r);
    }
    else if (op == 1) {
        rc = tpa_int_abs(x, &r);
    }
    else {
        tpa_int_retain(x);
        r = x;
        rc = 0;
    }
    tpa_int_release(x);
    if (rc != 0) {
        return -1;
    }
    return finish_str(r, out);
}

TPA_EXPORT int shim_to_double(const char *a, double *out)
{
    tpa_int x;
    int rc;
    clear_error();
    if (tpa_int_from_str(a, strlen(a), &x) != 0) {
        return -3;
    }
    rc = tpa_int_to_f64(x, out);
    tpa_int_release(x);
    return rc == 0 ? 0 : -1;
}

TPA_EXPORT int shim_truediv(const char *a, const char *b, double *out)
{
    tpa_int x, y;
    int rc;
    clear_error();
    if (tpa_int_from_str(a, strlen(a), &x) != 0) {
        return -3;
    }
    if (tpa_int_from_str(b, strlen(b), &y) != 0) {
        tpa_int_release(x);
        return -3;
    }
    rc = tpa_int_truediv(x, y, out);
    tpa_int_release(x);
    tpa_int_release(y);
    return rc == 0 ? 0 : -1;
}

/* *out: -1, 0, 1 or 2 (unordered). */
TPA_EXPORT int shim_cmp_double(const char *a, double x, int *out)
{
    tpa_int v;
    clear_error();
    if (tpa_int_from_str(a, strlen(a), &v) != 0) {
        return -3;
    }
    *out = tpa_int_cmp_f64(v, x);
    tpa_int_release(v);
    return 0;
}

/* int(s) for the bytes [bytes, bytes + n): 0 or 1 (value written, 1 = small), -1 raised, TPA_INT_NOT_HANDLED
 * is returned as 100 (the plain 1 would collide with "small"). */
TPA_EXPORT int shim_from_str(const char *bytes, size_t n, char **out)
{
    tpa_int v;
    int rc;
    clear_error();
    rc = tpa_int_from_str(bytes, n, &v);
    if (rc == TPA_INT_NOT_HANDLED) {
        return 100;
    }
    if (rc != 0) {
        return -1;
    }
    return finish_str(v, out);
}

TPA_EXPORT int shim_i64_roundtrip(int64_t v, char **out, int *fits, int64_t *back)
{
    tpa_int h;
    int rc;
    clear_error();
    if (tpa_int_from_i64(v, &h) != 0) {
        return -1;
    }
    *fits = tpa_int_fits_i64(h);
    *back = 0;
    rc = tpa_int_to_i64(h, back);
    if (rc != 0) {
        *back = 0;
    }
    return finish_str(h, out);
}

/* The conversion of a value to int64_t: 0 and *out, or 1 when it does not fit (no error), -3 parse error. */
TPA_EXPORT int shim_to_i64(const char *a, int64_t *out, int *fits)
{
    tpa_int x;
    int rc;
    clear_error();
    if (tpa_int_from_str(a, strlen(a), &x) != 0) {
        return -3;
    }
    *fits = tpa_int_fits_i64(x);
    rc = tpa_int_to_i64(x, out);
    tpa_int_release(x);
    return rc;
}
