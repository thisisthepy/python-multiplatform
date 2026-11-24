/*
 * TypedPython AOT mode, tier S step 2b (PR-S2b): `dict[K, V]` (docs/design/typedpython-aot.md 4.3.2, "dict and set";
 * agent proposal, not owner-confirmed; tier S is in 1.0, owner 2026-11-21 21:55).
 *
 * Included after tp_aot.h, tp_aot_int.h and tp_aot_str.h, only by a program that has a dict: a program without one
 * generates the C it generated before.
 *
 * Layout: CPython's compact dict (Objects/dictobject.c of the 3.15 reference interpreter): an index table of
 * 2**log2 slots (-1 empty, -2 a deleted slot, else an entry number) and an entry array in insertion order. A delete
 * leaves its entry empty in place; an insert appends. What a program can observe of the layout is the order of the
 * entries and the moments they are compacted (a resize), which decide what a loop that changes the dict visits and
 * where it raises; so every size rule is CPython's, cited where it is used:
 *   PyDict_MINSIZE 8, USABLE_FRACTION(n) = 2n/3, GROWTH_RATE = used * 3, calculate_log2_keysize,
 *   estimate_log2_keysize, dict_new_presized (displays), insert_to_emptydict, insertion_resize, dictresize
 *   (compacts), delitem_common, dict_popitem_impl (truncates the entry count), clear_lock_held (the empty table),
 *   copy_lock_held (the fast copy keeps the holes), dict_dict_merge (update), dictiter_iternext_threadsafe.
 * The hash function is not CPython's (CPython seeds str hashing per process, so no program can depend on slot
 * positions); equality is CPython's: an int key equals a bool key of the same value (`1 == True`), and the stored key
 * keeps the type it was first inserted with (the per-entry `kbool`), as CPython keeps the first key object.
 *
 * Keys and values are 64-bit words whose meaning the dict's kinds give (`tpa_dw`): an int64, a double's bits, a
 * tagged `tpa_int`, a `tpa_str *`. Owned kinds (TAGGED int, str) hold one reference per stored word. Every function
 * BORROWS the words it is given; a word it hands out (`get`, `pop`, an iterator step) is a NEW reference the caller
 * owns. Status codes as the rest of the runtime: 0 success, -1 with the exception recorded in `tpa_exc`.
 *
 * A value may be a dict (TPA_DV_DICT, a `tpa_dict *`, owned): its type is a part of the outer dict's type, so it
 * cannot hold the outer dict back, and reference counts free it (no cycle collector).
 *
 * Not here yet (later in S2b, each its own named test): class instances and lists as values (they need the cycle
 * collector), set, a dict crossing a library boundary.
 */
#ifndef TP_AOT_DICT_H
#define TP_AOT_DICT_H

#ifndef TP_AOT_STR_H
#error "tp_aot_dict.h must be included after tp_aot_str.h"
#endif

/* Key kinds. */
#define TPA_DK_I64 0     /* an int64 of a RAW int group; a bool key is 0 or 1 with the entry's kbool set */
#define TPA_DK_INT 1     /* a TAGGED tpa_int, owned */
#define TPA_DK_STR 2     /* a tpa_str *, owned */
/* Value kinds. */
#define TPA_DV_I64 0
#define TPA_DV_F64 1
#define TPA_DV_BOOL 2
#define TPA_DV_INT 3     /* a TAGGED tpa_int, owned */
#define TPA_DV_STR 4     /* a tpa_str *, owned; NULL is None (a `str | None` value) */
#define TPA_DV_DICT 5    /* a tpa_dict *, owned (a dict held as a value) */

typedef uint64_t tpa_dw;

#define TPA_DICT_MINSIZE 8
#define TPA_DICT_LOG_MINSIZE 3
#define TPA_DICT_USABLE(n) (((n) << 1) / 3)
#define TPA_DICT_EMPTY (-1)
#define TPA_DICT_DUMMY (-2)
#define TPA_DICT_PERTURB_SHIFT 5

typedef struct tpa_dict_entry {
    int64_t hash;
    tpa_dw key;
    tpa_dw value;
    int32_t live;        /* 0: a deleted entry (CPython's me_value == NULL) */
    int32_t kbool;       /* the key was a bool when it was inserted (printed True / False) */
} tpa_dict_entry;

typedef struct tpa_dict {
    tpa_rc_t rc;
    int64_t used;        /* live entries (ma_used) */
    int64_t usable;      /* dk_usable */
    int64_t nentries;    /* dk_nentries */
    int32_t log2;        /* 0: CPython's empty keys (no table, `index` and `e` NULL) */
    int16_t kk, vk;
    int64_t *index;      /* 2**log2 slots */
    tpa_dict_entry *e;   /* USABLE_FRACTION(2**log2) entries */
} tpa_dict;

static TPA_UNUSED void tpa_dict_release(struct tpa_dict *d);
static TPA_UNUSED int tpa_dict_eq(const struct tpa_dict *a, const struct tpa_dict *b);
static TPA_UNUSED int tpa_dict_repr(const struct tpa_dict *d, tpa_str **out);

/* ------------------------------------------------------------------------------------------
 * Words: hashing, equality, ownership, repr
 * ------------------------------------------------------------------------------------------ */

static inline uint64_t tpa_dict__mix(uint64_t x)
{
    x ^= x >> 33;
    x *= 0xff51afd7ed558ccdULL;
    x ^= x >> 33;
    x *= 0xc4ceb9fe1a85ec53ULL;
    x ^= x >> 33;
    return x;
}

static int64_t tpa_dict__hash_str(tpa_str *s)
{
    uint64_t h = 1469598103934665603ULL;
    int64_t i;
    if (s->hash != -1) {
        return s->hash;
    }
    for (i = 0; i < s->len; i++) {
        uint32_t c = s->kind == 1 ? TPA_STR_DATA(s)[i]
                   : s->kind == 2 ? ((uint16_t *)TPA_STR_DATA(s))[i] : ((uint32_t *)TPA_STR_DATA(s))[i];
        h = (h ^ c) * 1099511628211ULL;
    }
    h = tpa_dict__mix(h) & 0x3fffffffffffffffULL;      /* never -1 */
    if (!tpa_str__immortal(s)) {
        s->hash = (int64_t)h;                            /* (a literal is shared and never written) */
    }
    return (int64_t)h;
}

/* Equal ints hash equal, whatever their representation (a TAGGED small and an int64 of the same value never meet in
 * one dict, but a small and a big never hold one value either: the tagged form is canonical). */
static int64_t tpa_dict__hash(const tpa_dict *d, tpa_dw k)
{
    if (d->kk == TPA_DK_STR) {
        return tpa_dict__hash_str((tpa_str *)(uintptr_t)k);
    }
    if (d->kk == TPA_DK_INT && !((tpa_int)k & 1u)) {
        tpa_big *b = tpa_int__big((tpa_int)k);
        uint64_t h = b->neg ? 0x9e3779b97f4a7c15ULL : 0;
        uint32_t i;
        for (i = 0; i < b->n; i++) {
            h = tpa_dict__mix(h ^ b->d[i]);
        }
        return (int64_t)(h & 0x3fffffffffffffffULL);
    }
    {
        int64_t v = d->kk == TPA_DK_INT ? tpa_int_small_value((tpa_int)k) : (int64_t)k;
        return (int64_t)(tpa_dict__mix((uint64_t)v) & 0x3fffffffffffffffULL);
    }
}

static int tpa_dict__key_eq(const tpa_dict *d, tpa_dw a, tpa_dw b)
{
    if (d->kk == TPA_DK_STR) {
        return tpa_str_eq((tpa_str *)(uintptr_t)a, (tpa_str *)(uintptr_t)b);
    }
    if (d->kk == TPA_DK_INT) {
        return tpa_int_eq((tpa_int)a, (tpa_int)b);
    }
    return a == b;
}

static void tpa_dict__key_retain(const tpa_dict *d, tpa_dw k)
{
    if (d->kk == TPA_DK_STR) {
        tpa_str_retain((tpa_str *)(uintptr_t)k);
    }
    else if (d->kk == TPA_DK_INT) {
        tpa_int_retain((tpa_int)k);
    }
}

static void tpa_dict__key_release(const tpa_dict *d, tpa_dw k)
{
    if (d->kk == TPA_DK_STR) {
        tpa_str_release((tpa_str *)(uintptr_t)k);
    }
    else if (d->kk == TPA_DK_INT) {
        tpa_int_release((tpa_int)k);
    }
}

static void tpa_dict__value_retain(const tpa_dict *d, tpa_dw v)
{
    if (d->vk == TPA_DV_STR) {
        tpa_str_retain((tpa_str *)(uintptr_t)v);
    }
    else if (d->vk == TPA_DV_INT) {
        tpa_int_retain((tpa_int)v);
    }
    else if (d->vk == TPA_DV_DICT) {
        TPA_RC_INC((tpa_dict *)(uintptr_t)v);
    }
}

static void tpa_dict__value_release(const tpa_dict *d, tpa_dw v)
{
    if (d->vk == TPA_DV_STR) {
        tpa_str_release((tpa_str *)(uintptr_t)v);
    }
    else if (d->vk == TPA_DV_INT) {
        tpa_int_release((tpa_int)v);
    }
    else if (d->vk == TPA_DV_DICT) {
        tpa_dict_release((tpa_dict *)(uintptr_t)v);
    }
}

/* repr of a key (`kbool`: the key is a bool) or of a value, as a new str. */
static int tpa_dict__repr_word(int is_key, int kind, int kbool, tpa_dw w, tpa_str **out)
{
    if (is_key ? kbool : kind == TPA_DV_BOOL) {
        /* (a bool looking up a dict of TAGGED ints is the small int 0 or 1, whose word is not 0 or 1) */
        int64_t b = is_key && kind == TPA_DK_INT ? tpa_int_small_value((tpa_int)w) : (int64_t)w;
        return b ? tpa_str_from_ascii("True", 4, out) : tpa_str_from_ascii("False", 5, out);
    }
    if (is_key ? kind == TPA_DK_STR : kind == TPA_DV_STR) {
        tpa_str *s = (tpa_str *)(uintptr_t)w;
        return s == NULL ? tpa_str_from_ascii("None", 4, out) : tpa_str_repr(s, out);
    }
    if (is_key ? kind == TPA_DK_INT : kind == TPA_DV_INT) {
        return tpa_str_from_int((tpa_int)w, out);
    }
    if (!is_key && kind == TPA_DV_DICT) {
        return tpa_dict_repr((const tpa_dict *)(uintptr_t)w, out);
    }
    if (!is_key && kind == TPA_DV_F64) {
        double x;
        char buf[40];
        int n;
        memcpy(&x, &w, sizeof x);
        n = tpa_repr_f64(x, buf);
        return tpa_str_from_ascii(buf, (size_t)n, out);
    }
    return tpa_str_from_i64((int64_t)w, out);
}

/* KeyError(key): the message is the key's repr, as `str(KeyError(k))` is, in UTF-8 (a repr holds no surrogate: it
 * escapes them). The exception record keeps up to 1023 bytes of it (tp_aot.h); a longer repr is cut there. */
static int tpa_dict__key_error(const tpa_dict *d, tpa_dw k, int kbool)
{
    tpa_str *r = NULL;
    char m[sizeof tpa_exc.msg];
    size_t n = 0;
    int64_t i;
    if (tpa_dict__repr_word(1, d->kk, kbool, k, &r)) {
        return -1;
    }
    for (i = 0; i < r->len; i++) {
        char b4[4];
        size_t w = tpa_int__utf8_put(tpa_str_at(r, i), b4);
        if (n + w >= sizeof m) {
            break;
        }
        memcpy(m + n, b4, w);
        n += w;
    }
    m[n] = '\0';
    tpa_str_release(r);
    return tpa_raise(TPA_KeyError, m);
}

/* ------------------------------------------------------------------------------------------
 * The table
 * ------------------------------------------------------------------------------------------ */

static int tpa_dict__bit_length(uint64_t x)
{
    int n = 0;
    while (x) {
        n++;
        x >>= 1;
    }
    return n;
}

/* calculate_log2_keysize (CPython 3.14 and later; 3.13 rounded `minsize | 8` and gave 16 slots for 5): the smallest
 * table of at least `minsize` slots, at least 8. */
static int tpa_dict__log2_for(int64_t minsize)
{
    if (minsize < TPA_DICT_MINSIZE) {
        minsize = TPA_DICT_MINSIZE;
    }
    return tpa_dict__bit_length((uint64_t)minsize - 1);
}

/* estimate_log2_keysize: room for `n` entries without a resize. */
static int tpa_dict__log2_estimate(int64_t n)
{
    return tpa_dict__log2_for((n * 3 + 1) / 2);
}

/* new_keys_object: an empty table of 2**log2 slots, its usable count and no entries. */
static int tpa_dict__alloc_table(int log2, int64_t **index, tpa_dict_entry **e, int64_t *usable)
{
    int64_t size, n, i;
    if (log2 >= 62) {
        return tpa_raise(TPA_MemoryError, "");
    }
    size = (int64_t)1 << log2;
    n = TPA_DICT_USABLE(size);
    if ((uint64_t)size > SIZE_MAX / sizeof(int64_t) || (uint64_t)n > SIZE_MAX / sizeof(tpa_dict_entry)) {
        return tpa_raise(TPA_MemoryError, "");
    }
    *index = (int64_t *)malloc((size_t)size * sizeof(int64_t));
    *e = (tpa_dict_entry *)calloc((size_t)n, sizeof(tpa_dict_entry));
    if (*index == NULL || *e == NULL) {
        free(*index);
        free(*e);
        *index = NULL;
        *e = NULL;
        return tpa_raise(TPA_MemoryError, "");
    }
    for (i = 0; i < size; i++) {
        (*index)[i] = TPA_DICT_EMPTY;
    }
    *usable = n;
    return 0;
}

/* The slot of the index table holding entry `ix` (lookdict_index). */
static int64_t tpa_dict__slot_of(const tpa_dict *d, int64_t hash, int64_t ix)
{
    uint64_t mask = ((uint64_t)1 << d->log2) - 1, perturb = (uint64_t)hash, i = (uint64_t)hash & mask;
    for (;;) {
        if (d->index[i] == ix) {
            return (int64_t)i;
        }
        perturb >>= TPA_DICT_PERTURB_SHIFT;
        i = (i * 5 + perturb + 1) & mask;
    }
}

/* find_empty_slot: the first slot on the probe sequence that holds no entry (empty or deleted). */
static int64_t tpa_dict__free_slot(int64_t *index, int log2, int64_t hash)
{
    uint64_t mask = ((uint64_t)1 << log2) - 1, perturb = (uint64_t)hash, i = (uint64_t)hash & mask;
    while (index[i] >= 0) {
        perturb >>= TPA_DICT_PERTURB_SHIFT;
        i = (i * 5 + perturb + 1) & mask;
    }
    return (int64_t)i;
}

/* The entry number of `k`, or -1. */
static int64_t tpa_dict__lookup(const tpa_dict *d, tpa_dw k, int64_t hash)
{
    uint64_t mask, perturb, i;
    if (d->log2 == 0) {
        return -1;
    }
    mask = ((uint64_t)1 << d->log2) - 1;
    perturb = (uint64_t)hash;
    i = (uint64_t)hash & mask;
    for (;;) {
        int64_t ix = d->index[i];
        if (ix == TPA_DICT_EMPTY) {
            return -1;
        }
        if (ix >= 0 && d->e[ix].hash == hash && tpa_dict__key_eq(d, d->e[ix].key, k)) {
            return ix;
        }
        perturb >>= TPA_DICT_PERTURB_SHIFT;
        i = (i * 5 + perturb + 1) & mask;
    }
}

/* dictresize: a new table of 2**log2 slots holding the live entries in order, compacted. */
static int tpa_dict__resize(tpa_dict *d, int log2)
{
    int64_t *index = NULL, usable = 0, i, j = 0;
    tpa_dict_entry *e = NULL;
    if (tpa_dict__alloc_table(log2, &index, &e, &usable)) {
        return -1;
    }
    for (i = 0; i < d->nentries; i++) {
        if (d->e[i].live) {
            e[j] = d->e[i];
            index[tpa_dict__free_slot(index, log2, e[j].hash)] = j;
            j++;
        }
    }
    free(d->index);
    free(d->e);
    d->index = index;
    d->e = e;
    d->log2 = log2;
    d->usable = usable - j;
    d->nentries = j;
    return 0;
}

/* Appends a new entry (the key is not in the dict); takes the references the caller passes. insert_to_emptydict on
 * the empty table, insertion_resize when no entry is usable, then insert_combined_dict. */
static int tpa_dict__append(tpa_dict *d, tpa_dw k, int kbool, int64_t hash, tpa_dw v)
{
    tpa_dict_entry *ep;
    if (d->log2 == 0) {
        if (tpa_dict__alloc_table(TPA_DICT_LOG_MINSIZE, &d->index, &d->e, &d->usable)) {
            return -1;
        }
        d->log2 = TPA_DICT_LOG_MINSIZE;
        d->nentries = 0;
    }
    else if (d->usable <= 0 && tpa_dict__resize(d, tpa_dict__log2_for(d->used * 3))) {
        return -1;
    }
    d->index[tpa_dict__free_slot(d->index, d->log2, hash)] = d->nentries;
    ep = &d->e[d->nentries];
    ep->hash = hash;
    ep->key = k;
    ep->value = v;
    ep->live = 1;
    ep->kbool = kbool;
    d->usable--;
    d->nentries++;
    d->used++;
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Objects
 * ------------------------------------------------------------------------------------------ */

/* A new dict: `{}` (presize 0) or a display of `presize` pairs (dict_new_presized: up to USABLE_FRACTION(8) pairs
 * start from the empty table, more from a table that holds them all, at most 2**17 slots). */
static TPA_UNUSED int tpa_dict_new(int kk, int vk, int64_t presize, tpa_dict **out)
{
    tpa_dict *d = (tpa_dict *)calloc(1, sizeof(tpa_dict));
    if (d == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    d->rc = 1;
    d->kk = (int16_t)kk;
    d->vk = (int16_t)vk;
    if (presize > TPA_DICT_USABLE(TPA_DICT_MINSIZE)) {
        int log2 = presize > TPA_DICT_USABLE((int64_t)1 << 17) ? 17 : tpa_dict__log2_estimate(presize);
        if (tpa_dict__alloc_table(log2, &d->index, &d->e, &d->usable)) {
            free(d);
            return -1;
        }
        d->log2 = log2;
    }
    *out = d;
    return 0;
}

/* The entries' references, in entry order (free_keys_object: each key, then its value). */
static void tpa_dict__drop_entries(const tpa_dict *d, tpa_dict_entry *e, int64_t n)
{
    int64_t i;
    for (i = 0; i < n; i++) {
        if (e[i].live) {
            tpa_dict__key_release(d, e[i].key);
            tpa_dict__value_release(d, e[i].value);
        }
    }
}

static inline void tpa_dict_retain(tpa_dict *d)
{
    if (d != NULL) {
        TPA_RC_INC(d);
    }
}

static TPA_UNUSED void tpa_dict_release(tpa_dict *d)
{
    if (d != NULL && TPA_RC_DEC_IS_ZERO(d)) {
        tpa_dict__drop_entries(d, d->e, d->nentries);
        free(d->index);
        free(d->e);
        free(d);
    }
}

/* A float value's word and back (its bits). */
static inline tpa_dw tpa_dict_f64(double x)
{
    tpa_dw w;
    memcpy(&w, &x, sizeof w);
    return w;
}

static inline double tpa_dict_to_f64(tpa_dw w)
{
    double x;
    memcpy(&x, &w, sizeof x);
    return x;
}

/* `name = value` of a dict name (borrows v, as tpa_str_list_set): the new reference is held before the old one is
 * released. */
static inline void tpa_dict_set(tpa_dict **slot, tpa_dict *v)
{
    tpa_dict *old = *slot;
    tpa_dict_retain(v);
    *slot = v;
    tpa_dict_release(old);
}

/* Empties a dict slot (its reference released). */
static inline void tpa_dict_drop(tpa_dict **slot)
{
    tpa_dict *old = *slot;
    *slot = NULL;
    tpa_dict_release(old);
}

static inline int64_t tpa_dict_len(const tpa_dict *d) { return d->used; }

/* d[k] = v (borrows k and v). A new key is appended; an existing one keeps its key word (and its kbool) and gets the
 * new value, the old value released after the new one is stored (insertdict). */
static TPA_UNUSED int tpa_dict_setitem(tpa_dict *d, tpa_dw k, int kbool, tpa_dw v)
{
    int64_t hash = tpa_dict__hash(d, k), ix = tpa_dict__lookup(d, k, hash);
    if (ix >= 0) {
        tpa_dw old = d->e[ix].value;
        tpa_dict__value_retain(d, v);
        d->e[ix].value = v;
        tpa_dict__value_release(d, old);
        return 0;
    }
    tpa_dict__key_retain(d, k);
    tpa_dict__value_retain(d, v);
    if (tpa_dict__append(d, k, kbool, hash, v)) {
        tpa_dict__key_release(d, k);
        tpa_dict__value_release(d, v);
        return -1;
    }
    return 0;
}

/* d[k] (a new reference in *out), KeyError(repr(k)) when absent. */
static TPA_UNUSED int tpa_dict_getitem(const tpa_dict *d, tpa_dw k, int kbool, tpa_dw *out)
{
    int64_t ix = tpa_dict__lookup(d, k, tpa_dict__hash(d, k));
    if (ix < 0) {
        return tpa_dict__key_error(d, k, kbool);
    }
    tpa_dict__value_retain(d, d->e[ix].value);
    *out = d->e[ix].value;
    return 0;
}

/* k in d. */
static TPA_UNUSED int tpa_dict_contains(const tpa_dict *d, tpa_dw k)
{
    return tpa_dict__lookup(d, k, tpa_dict__hash(d, k)) >= 0;
}

/* d.get(k, dflt): the value or dflt, a new reference. */
static TPA_UNUSED void tpa_dict_get(const tpa_dict *d, tpa_dw k, tpa_dw dflt, tpa_dw *out)
{
    int64_t ix = tpa_dict__lookup(d, k, tpa_dict__hash(d, k));
    tpa_dw v = ix >= 0 ? d->e[ix].value : dflt;
    tpa_dict__value_retain(d, v);
    *out = v;
}

/* Removes entry ix (delitem_common): the slot becomes a deleted one, the entry empty in place, the key released,
 * then the value unless the caller takes it (`take`). */
static void tpa_dict__remove(tpa_dict *d, int64_t ix, tpa_dw *take)
{
    tpa_dict_entry *ep = &d->e[ix];
    tpa_dw k = ep->key, v = ep->value;
    d->index[tpa_dict__slot_of(d, ep->hash, ix)] = TPA_DICT_DUMMY;
    d->used--;
    ep->live = 0;
    ep->key = 0;
    ep->value = 0;
    ep->hash = 0;
    tpa_dict__key_release(d, k);
    if (take != NULL) {
        *take = v;
    }
    else {
        tpa_dict__value_release(d, v);
    }
}

/* del d[k]: KeyError(repr(k)) when absent. */
static TPA_UNUSED int tpa_dict_delitem(tpa_dict *d, tpa_dw k, int kbool)
{
    int64_t ix = tpa_dict__lookup(d, k, tpa_dict__hash(d, k));
    if (ix < 0) {
        return tpa_dict__key_error(d, k, kbool);
    }
    tpa_dict__remove(d, ix, NULL);
    return 0;
}

/* d.pop(k) (has_default 0: KeyError(repr(k)) when absent) or d.pop(k, dflt): the value, a new reference. */
static TPA_UNUSED int tpa_dict_pop(tpa_dict *d, tpa_dw k, int kbool, int has_default, tpa_dw dflt, tpa_dw *out)
{
    int64_t ix;
    if (d->used == 0) {
        ix = -1;                                     /* (_PyDict_Pop: an empty dict is not searched) */
    }
    else {
        ix = tpa_dict__lookup(d, k, tpa_dict__hash(d, k));
    }
    if (ix < 0) {
        if (!has_default) {
            return tpa_dict__key_error(d, k, kbool);
        }
        tpa_dict__value_retain(d, dflt);
        *out = dflt;
        return 0;
    }
    tpa_dict__remove(d, ix, out);
    return 0;
}

/* d.setdefault(k, dflt): the value now stored under k, a new reference (dflt inserted when absent). */
static TPA_UNUSED int tpa_dict_setdefault(tpa_dict *d, tpa_dw k, int kbool, tpa_dw dflt, tpa_dw *out)
{
    int64_t hash = tpa_dict__hash(d, k), ix = tpa_dict__lookup(d, k, hash);
    if (ix < 0) {
        tpa_dict__key_retain(d, k);
        tpa_dict__value_retain(d, dflt);
        if (tpa_dict__append(d, k, kbool, hash, dflt)) {
            tpa_dict__key_release(d, k);
            tpa_dict__value_release(d, dflt);
            return -1;
        }
        ix = d->nentries - 1;
    }
    tpa_dict__value_retain(d, d->e[ix].value);
    *out = d->e[ix].value;
    return 0;
}

/* d.popitem(): the last live entry, its key and value as new references (the caller owns both); the entry count
 * is truncated to it and nothing becomes usable (dict_popitem_impl). */
static TPA_UNUSED int tpa_dict_popitem(tpa_dict *d, tpa_dw *k, int *kbool, tpa_dw *v)
{
    int64_t i;
    tpa_dict_entry *ep;
    if (d->used == 0) {
        return tpa_raise(TPA_KeyError, "'popitem(): dictionary is empty'");
    }
    i = d->nentries - 1;
    while (i >= 0 && !d->e[i].live) {
        i--;
    }
    ep = &d->e[i];
    d->index[tpa_dict__slot_of(d, ep->hash, i)] = TPA_DICT_DUMMY;
    *k = ep->key;
    *kbool = ep->kbool;
    *v = ep->value;
    ep->live = 0;
    ep->key = 0;
    ep->value = 0;
    d->nentries = i;
    d->used--;
    return 0;
}

/* d.clear(): the empty table, then the old entries' references released in entry order (clear_lock_held). */
static TPA_UNUSED void tpa_dict_clear(tpa_dict *d)
{
    int64_t *index = d->index, n = d->nentries;
    tpa_dict_entry *e = d->e;
    if (d->log2 == 0) {
        return;
    }
    d->index = NULL;
    d->e = NULL;
    d->log2 = 0;
    d->used = 0;
    d->usable = 0;
    d->nentries = 0;
    tpa_dict__drop_entries(d, e, n);
    free(index);
    free(e);
}

/* A copy of the table of `src` with every live word retained (clone_combined_dict_keys: holes kept). */
static int tpa_dict__clone_into(tpa_dict *dst, const tpa_dict *src)
{
    int64_t size = (int64_t)1 << src->log2, n = TPA_DICT_USABLE(size), i;
    int64_t *index = (int64_t *)malloc((size_t)size * sizeof(int64_t));
    tpa_dict_entry *e = (tpa_dict_entry *)calloc((size_t)n, sizeof(tpa_dict_entry));
    if (index == NULL || e == NULL) {
        free(index);
        free(e);
        return tpa_raise(TPA_MemoryError, "");
    }
    memcpy(index, src->index, (size_t)size * sizeof(int64_t));
    memcpy(e, src->e, (size_t)src->nentries * sizeof(tpa_dict_entry));
    for (i = 0; i < src->nentries; i++) {
        if (e[i].live) {
            tpa_dict__key_retain(src, e[i].key);
            tpa_dict__value_retain(src, e[i].value);
        }
    }
    dst->index = index;
    dst->e = e;
    dst->log2 = src->log2;
    dst->usable = src->usable;
    dst->nentries = src->nentries;
    dst->used = src->used;
    return 0;
}

/* d.update(o) (dict_dict_merge with override 1): nothing for an empty o or o itself; into an empty d a clean, small
 * or dense o is cloned; else one resize first when o could not fit, then each of o's entries set in order. */
static TPA_UNUSED int tpa_dict_update(tpa_dict *d, const tpa_dict *o)
{
    int64_t i, size;
    if (o == d || o->used == 0) {
        return 0;
    }
    if (d->used == 0 && o->used == o->nentries
            && (o->log2 == TPA_DICT_LOG_MINSIZE || TPA_DICT_USABLE(((int64_t)1 << o->log2) / 2) < o->used)) {
        tpa_dict tmp;
        memset(&tmp, 0, sizeof tmp);
        if (tpa_dict__clone_into(&tmp, o)) {
            return -1;
        }
        tpa_dict_clear(d);                           /* (the empty table may still hold deleted entries) */
        d->index = tmp.index;
        d->e = tmp.e;
        d->log2 = tmp.log2;
        d->usable = tmp.usable;
        d->nentries = tmp.nentries;
        d->used = tmp.used;
        return 0;
    }
    size = d->log2 == 0 ? 1 : (int64_t)1 << d->log2;   /* (the empty table has one slot in CPython) */
    if (TPA_DICT_USABLE(size) < o->used && tpa_dict__resize(d, tpa_dict__log2_estimate(d->used + o->used))) {
        return -1;
    }
    for (i = 0; i < o->nentries; i++) {
        if (o->e[i].live && tpa_dict_setitem(d, o->e[i].key, o->e[i].kbool, o->e[i].value)) {
            return -1;
        }
    }
    return 0;
}

/* d.copy() / dict(d) (copy_lock_held): an empty dict for an empty d; the fast copy (the table as it is, holes kept)
 * when at most a third of the entries are deleted; else an empty dict updated from d. */
static TPA_UNUSED int tpa_dict_copy(const tpa_dict *d, tpa_dict **out)
{
    tpa_dict *c = NULL;
    if (tpa_dict_new(d->kk, d->vk, 0, &c)) {
        return -1;
    }
    if (d->used != 0) {
        int rc = d->used >= (d->nentries * 2) / 3 ? tpa_dict__clone_into(c, d) : tpa_dict_update(c, d);
        if (rc) {
            tpa_dict_release(c);
            return -1;
        }
    }
    *out = c;
    return 0;
}

/* ------------------------------------------------------------------------------------------
 * Iteration (dictiter_iternext_threadsafe; one thread here): the iterator holds the dict, the used count it saw
 * at its creation, the next entry number and how many entries it may still produce.
 * ------------------------------------------------------------------------------------------ */
typedef struct tpa_dict_iter {
    tpa_dict *d;          /* held by the iterator; NULL once it is exhausted */
    int64_t used;
    int64_t pos;
    int64_t len;
} tpa_dict_iter;

static TPA_UNUSED void tpa_dict_iter_start(tpa_dict_iter *it, tpa_dict *d)
{
    tpa_dict_retain(d);
    it->d = d;
    it->used = d->used;
    it->pos = 0;
    it->len = d->used;
}

static TPA_UNUSED void tpa_dict_iter_end(tpa_dict_iter *it)
{
    tpa_dict_release(it->d);
    it->d = NULL;
}

/* 1 and the entry's key, kbool and value as new references (each out pointer may be NULL: not wanted); 0 at the
 * end; -1 with CPython's RuntimeError when the dict changed size since the iterator was made (sticky) or produced
 * more entries than it held. */
static TPA_UNUSED int tpa_dict_iter_next(tpa_dict_iter *it, tpa_dw *k, int *kbool, tpa_dw *v)
{
    tpa_dict *d = it->d;
    int64_t i;
    if (d == NULL) {
        return 0;
    }
    if (it->used != d->used) {
        it->used = -1;
        return tpa_raise(TPA_RuntimeError, "dictionary changed size during iteration");
    }
    i = it->pos;
    while (i < d->nentries && !d->e[i].live) {
        i++;
    }
    if (i >= d->nentries) {
        tpa_dict_iter_end(it);
        return 0;
    }
    if (it->len == 0) {
        tpa_dict_iter_end(it);
        return tpa_raise(TPA_RuntimeError, "dictionary keys changed during iteration");
    }
    it->pos = i + 1;
    it->len--;
    if (k != NULL) {
        tpa_dict__key_retain(d, d->e[i].key);
        *k = d->e[i].key;
    }
    if (kbool != NULL) {
        *kbool = d->e[i].kbool;
    }
    if (v != NULL) {
        tpa_dict__value_retain(d, d->e[i].value);
        *v = d->e[i].value;
    }
    return 1;
}

/* ------------------------------------------------------------------------------------------
 * Comparison and repr
 * ------------------------------------------------------------------------------------------ */

static int tpa_dict__value_eq(int vk, tpa_dw a, tpa_dw b)
{
    if (vk == TPA_DV_STR) {
        tpa_str *x = (tpa_str *)(uintptr_t)a, *y = (tpa_str *)(uintptr_t)b;
        return x == y || (x != NULL && y != NULL && tpa_str_eq(x, y));
    }
    if (vk == TPA_DV_INT) {
        return tpa_int_eq((tpa_int)a, (tpa_int)b);
    }
    if (vk == TPA_DV_DICT) {             /* (RichCompareBool: the same dict is equal, else dict_equal) */
        return tpa_dict_eq((const tpa_dict *)(uintptr_t)a, (const tpa_dict *)(uintptr_t)b);
    }
    return a == b;           /* (float values: the front end refuses `==`, CPython tests identity first; aot/identity) */
}

/* a == b of two dicts of the same kinds (dict_equal): same length, then every key of a found in b with an equal
 * value. */
static TPA_UNUSED int tpa_dict_eq(const tpa_dict *a, const tpa_dict *b)
{
    int64_t i;
    if (a == b) {
        return 1;
    }
    if (a->used != b->used) {
        return 0;
    }
    for (i = 0; i < a->nentries; i++) {
        if (a->e[i].live) {
            int64_t j = tpa_dict__lookup(b, a->e[i].key, a->e[i].hash);
            if (j < 0 || !tpa_dict__value_eq(a->vk, a->e[i].value, b->e[j].value)) {
                return 0;
            }
        }
    }
    return 1;
}

/* repr(d): "{1: 'a', 'b': 2.5}", "{}". */
static TPA_UNUSED int tpa_dict_repr(const tpa_dict *d, tpa_str **out)
{
    int64_t i, k = 0, n = d->used == 0 ? 2 : 4 * d->used + 1;
    tpa_str **parts = (tpa_str **)calloc((size_t)n + 1, sizeof(tpa_str *));
    int rc = 0;
    if (parts == NULL) {
        return tpa_raise(TPA_MemoryError, "");
    }
    parts[k++] = &tpa_str__latin1['{'].h;
    for (i = 0; i < d->nentries && rc == 0; i++) {
        if (!d->e[i].live) {
            continue;
        }
        if (k > 1) {
            rc = tpa_str_from_ascii(", ", 2, &parts[k++]);
        }
        if (rc == 0) {
            rc = tpa_dict__repr_word(1, d->kk, d->e[i].kbool, d->e[i].key, &parts[k++]);
        }
        if (rc == 0) {
            rc = tpa_str_from_ascii(": ", 2, &parts[k++]);
        }
        if (rc == 0) {
            rc = tpa_dict__repr_word(0, d->vk, 0, d->e[i].value, &parts[k++]);
        }
    }
    parts[k++] = &tpa_str__latin1['}'].h;
    if (rc == 0) {
        rc = tpa_str_join(parts, (size_t)k, out);
    }
    for (i = 0; i < k; i++) {
        tpa_str_release(parts[i]);
    }
    free(parts);
    return rc;
}

/* ------------------------------------------------------------------------------------------
 * A list of dicts (`list[dict[K, V]]`, ir DICT_LIST): a tpa_obj_list (tp_aot.h) whose items are dicts, each an owned
 * reference, never NULL. A dict holds no list, so a list of dicts is acyclic: reference counts free it.
 * ------------------------------------------------------------------------------------------ */

static TPA_UNUSED void tpa_dict__vret(void *d)
{
    tpa_dict_retain((tpa_dict *)d);
}

static TPA_UNUSED void tpa_dict__vrel(void *d)
{
    tpa_dict_release((tpa_dict *)d);
}

/* repr(l) of a list of dicts: "[{'a': 1}, {}]", "[]" (list_repr). */
static TPA_UNUSED int tpa_dict_list_repr(const tpa_obj_list *l, tpa_str **out)
{
    int64_t i, k = 0, n = l->len == 0 ? 2 : 2 * l->len + 1;
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
            rc = tpa_dict_repr((const tpa_dict *)l->d[i], &parts[k++]);
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

#endif /* TP_AOT_DICT_H */
