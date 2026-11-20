/* tp_aot_gc.h: the small cycle collector of the TypedPython AOT mode (SPEC N-17, tier O step 3, PR-O2c;
 * docs/design/typedpython-aot.md 6.3.2 item 3, "Step 3 as implemented"; leader decision D2 for a small
 * collector, the shape here an agent proposal, not owner-confirmed).
 *
 * Included by a generated program only when it has a cyclic-capable class (a class of a strongly connected
 * component of the type graph that has a cycle and a member that is not immutable-after-init); every other
 * program generates the C it generated before. Only the structs of cyclic-capable classes start with the
 * header `tpa_gc_head` (before `rc`), so the collector reads a tracked object's count through `tpa_gc_object`,
 * the common prefix of every tracked struct.
 *
 * The algorithm is CPython's trial deletion over one list of every tracked object alive (`tpa_gc_collect`):
 *   1. `gc_refs = rc` for every tracked object.
 *   2. every tracked object's generated `traverse` visits its fields of cyclic-capable classes and
 *      decrements each referent's `gc_refs`: what remains counts the references from OUTSIDE the tracked
 *      set (locals, temporaries, borrowed parameters whose callers hold them, fields of untracked objects,
 *      of region objects and of virtual objects; all of them counted, see the safepoint invariant below).
 *   3. an object with `gc_refs > 0` is a root; what it reaches is reachable; the rest is garbage.
 *   4. each garbage object is held by one extra count while its generated `clear` empties and releases
 *      every owned field (tracked and untracked objects, lists, big ints), then let go: it is freed at once
 *      when nothing else held it, or when the garbage that still held it is cleared in turn.
 * No user code runs during a collection (AOT compiles no `__del__`), so a collection cannot re-enter; a flag
 * guards it anyway.
 *
 * The safepoint: `tpa_gc_safepoint()` is called by `tpa_cls_<C>_new` of a cyclic-capable class before the
 * allocation, when every constructor argument is held by the caller and `__init__` has not started, and once
 * by `main` after `__main__` returned (`tpa_gc_exit`), when every frame has released its locals and only cycles
 * can remain. The invariant the generated code keeps for it: at a safepoint no tracked object is kept alive
 * solely by an uncounted reference (the elision rules of PR-O1 are off for every field of a cyclic-capable
 * class, `aot/classes.py` `field_elidable`).
 *
 * Trigger: `count`, tracked allocations net of tracked frees since the last collection, reaching
 * `threshold = max(TPA_GC_THRESHOLD, live tracked objects after the last collection)`: CPython's
 * first-generation threshold (2000) by default, and the memory garbage can hold is bounded by one more live
 * set while the amortised cost per allocation stays constant. `-DTPA_GC_STRESS` collects at every safepoint
 * at which a tracked object is alive; `-DTPA_GC_STATS` prints the counts to stderr at exit. Both are for the
 * tests; without them a program's output is exactly what it was.
 *
 * Threads: under TPA_THREADS the collector's state is thread-local and the count read is an atomic load. A
 * tracked object is touched only by the thread that created it today (`aot/library-mutable-class` keeps every
 * mutable class, transitively, out of the library boundary, and AOT has no mutable globals), so one thread's
 * collection never sees another thread's objects. This is not a stop-the-world collector: tier T must add the
 * shared-object locking of design section 7 before a tracked object may be shared between threads.
 */
#ifndef TP_AOT_GC_H
#define TP_AOT_GC_H

#include "tp_aot.h"

#ifndef TPA_GC_THRESHOLD
#define TPA_GC_THRESHOLD 2000
#endif

typedef struct tpa_gc_head tpa_gc_head;
typedef void (*tpa_gc_visit)(tpa_gc_head *referent, void *arg);

/* The generated functions of one cyclic-capable class: `traverse` calls `visit` on every field that holds a
 * tracked object (NULL fields skipped); `clear` empties every owned field and releases what it held. */
typedef struct tpa_gc_type {
    void (*traverse)(tpa_gc_head *self, tpa_gc_visit visit, void *arg);
    void (*clear)(tpa_gc_head *self);
} tpa_gc_type;

struct tpa_gc_head {
    tpa_gc_head *gc_prev;
    tpa_gc_head *gc_next;
    int64_t gc_refs;
    const tpa_gc_type *gc_type;
};

/* The common prefix of every tracked struct (`tpa_gc_head gc; tpa_rc_t rc; ...`): the collector's view of a
 * tracked object's count. */
typedef struct tpa_gc_object {
    tpa_gc_head gc;
    tpa_rc_t rc;
} tpa_gc_object;

#define TPA_GC_OBJ(h) ((tpa_gc_object *)(h))
#define TPA_GC_TENTATIVELY_UNREACHABLE ((int64_t)-1)

typedef struct tpa_gc_state {
    tpa_gc_head tracked;        /* the sentinel of the circular list of every tracked object alive */
    int initialised;
    int collecting;
    int64_t count;              /* tracked allocations net of tracked frees since the last collection */
    int64_t threshold;          /* a collection runs when count reaches it */
    int64_t live;               /* tracked objects alive */
    int64_t collections;        /* statistics (TPA_GC_STATS): collections at safepoints ... */
    int64_t freed;              /* ... and the objects they freed */
} tpa_gc_state;

#if defined(TPA_THREADS)
static TPA_THREAD_LOCAL tpa_gc_state tpa_gc;
#else
static tpa_gc_state tpa_gc;
#endif

static inline tpa_gc_state *tpa_gc__state(void)
{
    tpa_gc_state *g = &tpa_gc;
    if (!g->initialised) {
        g->tracked.gc_prev = &g->tracked;
        g->tracked.gc_next = &g->tracked;
        g->threshold = TPA_GC_THRESHOLD;
        g->initialised = 1;
    }
    return g;
}

static inline void tpa_gc__unlink(tpa_gc_head *h)
{
    h->gc_prev->gc_next = h->gc_next;
    h->gc_next->gc_prev = h->gc_prev;
    h->gc_prev = NULL;
    h->gc_next = NULL;
}

static inline void tpa_gc__append(tpa_gc_head *list, tpa_gc_head *h)
{
    h->gc_prev = list->gc_prev;
    h->gc_next = list;
    list->gc_prev->gc_next = h;
    list->gc_prev = h;
}

/* A fresh object of a cyclic-capable class (its count already 1) joins the tracked list. */
static inline void tpa_gc_track(tpa_gc_head *h, const tpa_gc_type *type)
{
    tpa_gc_state *g = tpa_gc__state();
    h->gc_type = type;
    h->gc_refs = 0;
    tpa_gc__append(&g->tracked, h);
    g->live += 1;
    g->count += 1;
}

/* A tracked object leaves its list: the generated release calls it when the count reaches zero, before the
 * fields are released, on whichever list the object is on (the tracked list, or the collector's garbage list). */
static inline void tpa_gc_untrack(tpa_gc_head *h)
{
    tpa_gc_state *g = tpa_gc__state();
    tpa_gc__unlink(h);
    g->live -= 1;
    if (g->count > 0) {
        g->count -= 1;
    }
}

#if defined(TP_CHECK_PROVEN)
static inline void tpa_gc__fatal(const char *what)
{
    fflush(stdout);
    fprintf(stderr, "SystemError: the cycle collector's invariant failed (%s)\n", what);
    fflush(stderr);
    abort();
}
#endif

static void tpa_gc__visit_decref(tpa_gc_head *q, void *arg)
{
    (void)arg;
#if defined(TP_CHECK_PROVEN)
    if (q->gc_refs <= 0) {
        tpa_gc__fatal("a tracked object is referenced more often than it is counted");
    }
#endif
    q->gc_refs -= 1;
}

typedef struct tpa_gc__lists {
    tpa_gc_head *tracked;
    tpa_gc_head *unreachable;
} tpa_gc__lists;

static void tpa_gc__visit_reachable(tpa_gc_head *q, void *arg)
{
    tpa_gc__lists *lists = (tpa_gc__lists *)arg;
    if (q->gc_refs == 0) {
        /* not yet scanned: it will be, as reachable, when the walk reaches it */
        q->gc_refs = 1;
    } else if (q->gc_refs == TPA_GC_TENTATIVELY_UNREACHABLE) {
        /* moved to the garbage list before this root was scanned: back to the end of the tracked list, where
         * the walk reaches it again and follows its own references */
        tpa_gc__unlink(q);
        tpa_gc__append(lists->tracked, q);
        q->gc_refs = 1;
    }
}

/* One collection: every tracked object in a cycle that nothing outside the tracked set reaches is freed. */
static void tpa_gc_collect(void)
{
    tpa_gc_state *g = tpa_gc__state();
    tpa_gc_head unreachable;
    tpa_gc__lists lists;
    tpa_gc_head *h;
    tpa_gc_head *next;
    int64_t freed = 0;
    if (g->collecting) {
        return;
    }
    g->collecting = 1;
    unreachable.gc_prev = &unreachable;
    unreachable.gc_next = &unreachable;
    unreachable.gc_refs = 0;
    unreachable.gc_type = NULL;
    lists.tracked = &g->tracked;
    lists.unreachable = &unreachable;
    /* 1. the counts */
    for (h = g->tracked.gc_next; h != &g->tracked; h = h->gc_next) {
        h->gc_refs = (int64_t)TPA_RC_LOAD(TPA_GC_OBJ(h));
    }
    /* 2. less the references between tracked objects */
    for (h = g->tracked.gc_next; h != &g->tracked; h = h->gc_next) {
        h->gc_type->traverse(h, tpa_gc__visit_decref, NULL);
    }
    /* 3. roots keep what they reach; the rest moves to `unreachable` */
    h = g->tracked.gc_next;
    while (h != &g->tracked) {
        if (h->gc_refs > 0) {
            h->gc_type->traverse(h, tpa_gc__visit_reachable, &lists);
            next = h->gc_next;              /* read after the traverse: it may have appended to this list */
        } else {
            next = h->gc_next;
            tpa_gc__unlink(h);
            tpa_gc__append(&unreachable, h);
            h->gc_refs = TPA_GC_TENTATIVELY_UNREACHABLE;
        }
        h = next;
    }
    /* 4. the garbage: clear each object while holding it, then let it go */
    while (unreachable.gc_next != &unreachable) {
        h = unreachable.gc_next;
        TPA_RC_INC(TPA_GC_OBJ(h));
        h->gc_type->clear(h);
        if (TPA_RC_DEC_IS_ZERO(TPA_GC_OBJ(h))) {
            tpa_gc_untrack(h);              /* its fields are empty: nothing else to release */
            free(h);
            freed += 1;
        } else if (unreachable.gc_next == h) {
            /* still held by garbage not yet cleared: it is freed when that garbage is cleared */
            tpa_gc__unlink(h);
            tpa_gc__append(&g->tracked, h);
            h->gc_refs = 0;
        }
    }
    g->collections += 1;
    g->freed += freed;
    g->count = 0;
    g->threshold = g->live > TPA_GC_THRESHOLD ? g->live : TPA_GC_THRESHOLD;
    g->collecting = 0;
}

/* The safepoint: called by the generated `tpa_cls_<C>_new` of a cyclic-capable class before it allocates. */
static inline void tpa_gc_safepoint(void)
{
    tpa_gc_state *g = tpa_gc__state();
#if defined(TPA_GC_STRESS)
    if (g->live > 0) {
        tpa_gc_collect();
    }
#else
    if (g->count >= g->threshold) {
        tpa_gc_collect();
    }
#endif
}

#if !defined(TPA_LIBRARY)
/* After `__main__` returned: every frame has released its locals, so what the tracked list still holds is
 * cycles; one more collection frees them (CPython collects at shutdown too, and a leak checker stays quiet). */
static inline void tpa_gc_exit(void)
{
    tpa_gc_state *g = tpa_gc__state();
    int64_t before = g->freed;
    int64_t collections = g->collections;
    tpa_gc_collect();
#if defined(TPA_GC_STATS)
    fflush(stdout);
    fprintf(stderr, "tpa_gc: collections=%lld freed=%lld exit_freed=%lld remaining=%lld\n",
            (long long)collections, (long long)before, (long long)(g->freed - before), (long long)g->live);
    fflush(stderr);
#else
    (void)before;
    (void)collections;
#endif
}
#endif

#endif
