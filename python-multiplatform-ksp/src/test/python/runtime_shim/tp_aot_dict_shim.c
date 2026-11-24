/* Test-only shim of tp_aot_dict.h for test_aot_dict_runtime.py (tier S2b; agent proposal, not owner-confirmed).
 *
 * Built as a shared library (`aot/cc.py compile_library`, so with -DTPA_LIBRARY: atomic counts) and called through
 * ctypes. `shim_dict_run` interprets a script of operations on two dicts `d` and `b` and one iterator over `d`, and
 * writes one line per operation; test_aot_dict_runtime.py runs the same script on CPython's dict and compares the
 * lines. What a script can see of the layout (the iteration order, what a live iterator visits after a mutation and
 * where it raises) is what the runtime must reproduce exactly.
 *
 * Keys are int (kind I64; op SETB stores a bool key) or str (kind STR: the key k is the string "k<k>", with a
 * non-ASCII character for odd k); values are int64. Every `tpa_str` made here is released here; a leaked or doubly
 * released reference is what the sanitizer job (ASan, LSan) reports. */
#include "tp_aot.h"
#include "tp_aot_int.h"
#include "tp_aot_str.h"
#include "tp_aot_dict.h"

#ifndef TPA_EXPORT
#if defined(_WIN32) || defined(__CYGWIN__)
#define TPA_EXPORT __declspec(dllexport)
#else
#define TPA_EXPORT __attribute__((visibility("default")))
#endif
#endif

enum {
    OP_SET, OP_GET, OP_DEL, OP_POP, OP_POPD, OP_SETDEFAULT, OP_POPITEM, OP_CLEAR, OP_LEN, OP_CONTAINS, OP_REPR,
    OP_COPY, OP_ITER, OP_NEXT, OP_B_SET, OP_UPDATE, OP_EQ, OP_B_CLEAR, OP_COPY_TO_B, OP_B_DEL, OP_SETB, OP_GETD,
    OP_ITEMS, OP_DISPLAY
};

typedef struct { char *p; size_t n, cap; int full; } out_t;

static void put(out_t *o, const char *s)
{
    size_t n = strlen(s);
    if (o->full || o->n + n + 2 > o->cap) {
        o->full = 1;
        return;
    }
    memcpy(o->p + o->n, s, n);
    o->n += n;
    o->p[o->n++] = '\n';
    o->p[o->n] = '\0';
}

static void put_str(out_t *o, const char *prefix, tpa_str *s)
{
    char line[4096];
    size_t n = strlen(prefix);
    int64_t i;
    memcpy(line, prefix, n);
    for (i = 0; i < s->len && n < sizeof line - 8; i++) {
        n += tpa_int__utf8_put(tpa_str_at(s, i), line + n);
    }
    line[n] = '\0';
    put(o, line);
}

static void put_error(out_t *o)
{
    char line[1200];
    snprintf(line, sizeof line, "%s: %s", tpa_exc_name(tpa_exc.kind), tpa_exc.msg);
    tpa_exc.kind = 0;
    tpa_exc.msg[0] = '\0';
    put(o, line);
}

static int str_keys;

/* The key word of k: the int itself, or a new str "k<k>", with U+00E9 appended for an odd k not divisible by 3 (a
 * Latin-1 string) and U+03B1 for an odd k divisible by 3 (a 2-byte string). */
static tpa_dw key_of(int64_t k)
{
    if (!str_keys) {
        return (tpa_dw)k;
    }
    {
        uint32_t cps[32];
        char tmp[24];
        size_t n = 0, i, m = (size_t)snprintf(tmp, sizeof tmp, "k%lld", (long long)k);
        tpa_str *s = NULL;
        for (i = 0; i < m; i++) {
            cps[n++] = (uint32_t)(unsigned char)tmp[i];
        }
        if (k & 1) {
            cps[n++] = k % 3 == 0 ? 0x3b1u : 0xe9u;      /* a 2-byte kind for some, Latin-1 for others */
        }
        if (tpa_str_from_cps(cps, n, &s)) {
            return 0;
        }
        return (tpa_dw)(uintptr_t)s;
    }
}

static void key_done(tpa_dw k)
{
    if (str_keys) {
        tpa_str_release((tpa_str *)(uintptr_t)k);
    }
}

static void put_key(out_t *o, const char *prefix, tpa_dw k, int kbool)
{
    tpa_str *r = NULL;
    if (tpa_dict__repr_word(1, str_keys ? TPA_DK_STR : TPA_DK_I64, kbool, k, &r)) {
        put_error(o);
        return;
    }
    put_str(o, prefix, r);
    tpa_str_release(r);
}

static void put_repr(out_t *o, const char *prefix, tpa_dict *d)
{
    tpa_str *r = NULL;
    if (tpa_dict_repr(d, &r)) {
        put_error(o);
        return;
    }
    put_str(o, prefix, r);
    tpa_str_release(r);
}

TPA_EXPORT int shim_dict_run(int keys_are_str, const int64_t *ops, int64_t n, char *buf, size_t cap)
{
    out_t o = {buf, 0, cap, 0};
    tpa_dict *d = NULL, *b = NULL;
    tpa_dict_iter it = {NULL, 0, 0, 0};
    int started = 0;
    int kk = keys_are_str ? TPA_DK_STR : TPA_DK_I64;
    int64_t i;
    char line[256];
    str_keys = keys_are_str;
    buf[0] = '\0';
    if (tpa_dict_new(kk, TPA_DV_I64, 0, &d) || tpa_dict_new(kk, TPA_DV_I64, 0, &b)) {
        return -1;
    }
    for (i = 0; i < n; i++) {
        int64_t op = ops[3 * i], a = ops[3 * i + 1], v = ops[3 * i + 2];
        tpa_dw k = key_of(a), out = 0;
        int kb = 0, rc;
        switch (op) {
        case OP_SET:
            if (tpa_dict_setitem(d, k, 0, (tpa_dw)v)) put_error(&o); else put(&o, "ok");
            break;
        case OP_SETB:          /* d[bool(a & 1)] = v (int keys only) */
            if (tpa_dict_setitem(d, (tpa_dw)(a & 1), 1, (tpa_dw)v)) put_error(&o); else put(&o, "ok");
            break;
        case OP_GET:
            if (tpa_dict_getitem(d, k, 0, &out)) put_error(&o);
            else { snprintf(line, sizeof line, "%lld", (long long)(int64_t)out); put(&o, line); }
            break;
        case OP_GETD:
            tpa_dict_get(d, k, (tpa_dw)v, &out);
            snprintf(line, sizeof line, "%lld", (long long)(int64_t)out);
            put(&o, line);
            break;
        case OP_DEL:
            if (tpa_dict_delitem(d, k, 0)) put_error(&o); else put(&o, "ok");
            break;
        case OP_POP:
            if (tpa_dict_pop(d, k, 0, 0, 0, &out)) put_error(&o);
            else { snprintf(line, sizeof line, "%lld", (long long)(int64_t)out); put(&o, line); }
            break;
        case OP_POPD:
            if (tpa_dict_pop(d, k, 0, 1, (tpa_dw)v, &out)) put_error(&o);
            else { snprintf(line, sizeof line, "%lld", (long long)(int64_t)out); put(&o, line); }
            break;
        case OP_SETDEFAULT:
            if (tpa_dict_setdefault(d, k, 0, (tpa_dw)v, &out)) put_error(&o);
            else { snprintf(line, sizeof line, "%lld", (long long)(int64_t)out); put(&o, line); }
            break;
        case OP_POPITEM: {
            tpa_dw pk = 0, pv = 0;
            if (tpa_dict_popitem(d, &pk, &kb, &pv)) { put_error(&o); break; }
            snprintf(line, sizeof line, " %lld", (long long)(int64_t)pv);
            put_key(&o, "", pk, kb);
            put(&o, line);
            key_done(pk);
            break;
        }
        case OP_CLEAR:
            tpa_dict_clear(d);
            put(&o, "ok");
            break;
        case OP_B_CLEAR:
            tpa_dict_clear(b);
            put(&o, "ok");
            break;
        case OP_LEN:
            snprintf(line, sizeof line, "%lld", (long long)tpa_dict_len(d));
            put(&o, line);
            break;
        case OP_CONTAINS:
            put(&o, tpa_dict_contains(d, k) ? "True" : "False");
            break;
        case OP_REPR:
            put_repr(&o, "", d);
            break;
        case OP_ITEMS: {          /* list(d.items()) through a fresh iterator */
            tpa_dict_iter j;
            tpa_dw ik = 0, iv = 0;
            tpa_dict_iter_start(&j, d);
            while ((rc = tpa_dict_iter_next(&j, &ik, &kb, &iv)) == 1) {
                snprintf(line, sizeof line, " -> %lld", (long long)(int64_t)iv);
                put_key(&o, "item ", ik, kb);
                put(&o, line);
                key_done(ik);
            }
            if (rc < 0) put_error(&o);
            tpa_dict_iter_end(&j);
            put(&o, "end");
            break;
        }
        case OP_COPY: {
            tpa_dict *c = NULL;
            if (tpa_dict_copy(d, &c)) { put_error(&o); break; }
            tpa_dict_set(&d, c);   /* (a live iterator keeps the old dict) */
            tpa_dict_release(c);
            put_repr(&o, "", d);
            break;
        }
        case OP_COPY_TO_B: {
            tpa_dict *c = NULL;
            if (tpa_dict_copy(d, &c)) { put_error(&o); break; }
            tpa_dict_set(&b, c);
            tpa_dict_release(c);
            put(&o, "ok");
            break;
        }
        case OP_ITER:
            tpa_dict_iter_end(&it);
            tpa_dict_iter_start(&it, d);
            started = 1;
            put(&o, "ok");
            break;
        case OP_NEXT:
            if (!started) {
                put(&o, "noiter");
                break;
            }
            rc = tpa_dict_iter_next(&it, &out, &kb, NULL);
            if (rc < 0) put_error(&o);
            else if (rc == 0) put(&o, "stop");
            else { put_key(&o, "", out, kb); key_done(out); }
            break;
        case OP_B_SET:
            if (tpa_dict_setitem(b, k, 0, (tpa_dw)v)) put_error(&o); else put(&o, "ok");
            break;
        case OP_B_DEL:
            if (tpa_dict_delitem(b, k, 0)) put_error(&o); else put(&o, "ok");
            break;
        case OP_UPDATE:
            if (tpa_dict_update(d, b)) put_error(&o); else put(&o, "ok");
            break;
        case OP_EQ:
            put(&o, tpa_dict_eq(d, b) ? "True" : "False");
            break;
        case OP_DISPLAY: {      /* d = {key(0): 0, ..., key(m-1): m-1} with m = a % 16: a presized display */
            tpa_dict *c = NULL;
            int64_t j, m = a % 16;
            if (tpa_dict_new(kk, TPA_DV_I64, m, &c)) { put_error(&o); break; }
            for (j = 0; j < m; j++) {
                tpa_dw kj = key_of(j);
                rc = tpa_dict_setitem(c, kj, 0, (tpa_dw)j);
                key_done(kj);
                if (rc) { put_error(&o); break; }
            }
            tpa_dict_set(&d, c);
            tpa_dict_release(c);
            put(&o, "ok");
            break;
        }
        default:
            put(&o, "bad op");
        }
        key_done(k);
    }
    tpa_dict_iter_end(&it);
    tpa_dict_release(d);
    tpa_dict_release(b);
    return o.full ? -2 : 0;
}
