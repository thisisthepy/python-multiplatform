# tp_runtime.h, the C runtime API generated code may call

Generated C (cgen.py) reaches memory and `PyObject`s **only** through these helpers (SPEC N-9).
Header-only, `static inline`, C99 + CPython C API, no other dependencies. Every helper implements
the CPython behaviour named in `ir.py`. Return convention unless stated: `0` ok, `1` deopt (no
Python error set, nothing to undo), `-1` Python error set (CPython's exception type and message, **of the running interpreter**: 3.14 changed several ZeroDivisionError and math-domain messages, and the runtime selects them by PY_VERSION_HEX).

## Integers (int64_t)
    int tp_add_i64(int64_t a, int64_t b, int64_t *out);      /* 0 / 1 on overflow */
    int tp_sub_i64(int64_t a, int64_t b, int64_t *out);
    int tp_mul_i64(int64_t a, int64_t b, int64_t *out);
    int tp_neg_i64(int64_t a, int64_t *out);                 /* 1 for INT64_MIN */
    int tp_floordiv_i64(int64_t a, int64_t b, int64_t *out); /* -1 ZeroDivisionError; 1 for MIN // -1 */
    int tp_mod_i64(int64_t a, int64_t b, int64_t *out);      /* -1 ZeroDivisionError; sign of b */
    int tp_truediv_i64(int64_t a, int64_t b, double *out);   /* -1 ZeroDivisionError; 1 if |a| or |b| > 2**53 */
    double tp_i64_to_f64(int64_t a);                         /* as float(int): round half to even */

## Floats (double)
    int tp_truediv_f64(double a, double b, double *out);     /* 0 / -1 ZeroDivisionError("float division by zero") */
    int tp_floordiv_f64(double a, double b, double *out);    /* CPython float_floor_div semantics */
    int tp_mod_f64(double a, double b, double *out);         /* CPython float_rem semantics */

## Comparisons
    int tp_cmp_i64_f64(int64_t a, double b, int op);         /* exact, op is Py_LT..Py_GE; returns 0/1 */
    int tp_cmp_f64_i64(double a, int64_t b, int op);

## math (stdlib math module semantics: math_1 / math_2 errno and inf/nan rules)
    int tp_math_sqrt(double x, double *out);   /* -1 ValueError("math domain error") */
    int tp_math_exp(double x, double *out);    /* -1 OverflowError("math range error") */
    int tp_math_log(double x, double *out);
    int tp_math_sin(double x, double *out);
    int tp_math_cos(double x, double *out);
    int tp_math_tan(double x, double *out);
    int tp_math_fabs(double x, double *out);
    int tp_math_atan2(double y, double x, double *out);
    int tp_math_hypot(double x, double y, double *out);

## Boxing (new references; NULL with MemoryError set on failure)
    PyObject *tp_box_i64(int64_t v);
    PyObject *tp_box_f64(double v);
    PyObject *tp_box_bool(int v);
    /* exact-type unboxing for guards: 0 ok, 1 wrong exact type or out of i64 range (deopt) */
    int tp_unbox_i64(PyObject *o, int64_t *out);   /* type(o) is int and fits */
    int tp_unbox_f64(PyObject *o, double *out);    /* type(o) is float */
    int tp_unbox_bool(PyObject *o, int *out);      /* o is Py_True / Py_False */

## Native arrays for list[float] / list[int] parameters (ir.ArrayParam)
    typedef struct { PyObject *list; Py_ssize_t len; double  *data; unsigned char *dirty; } tp_f64_array;
    typedef struct { PyObject *list; Py_ssize_t len; int64_t *data; unsigned char *dirty; } tp_i64_array;

    int  tp_f64_array_enter(PyObject *obj, tp_f64_array *a);  /* 1 unless exactly list of exactly float; -1 MemoryError */
    int  tp_i64_array_enter(PyObject *obj, tp_i64_array *a);  /* 1 unless exactly list of exactly int in i64 range */
    /* resolve a Python index (negative counts from the end) to a slot; -1 IndexError with
       "list index out of range" (load) or "list assignment index out of range" (store) */
    int  tp_f64_array_slot(const tp_f64_array *a, int64_t i, int store, Py_ssize_t *slot);
    int  tp_i64_array_slot(const tp_i64_array *a, int64_t i, int store, Py_ssize_t *slot);
    /* write dirty elements back as new float/int objects and free the buffers. Always frees, even
       when it fails (then -1 with MemoryError). Safe to call on a zeroed struct. */
    int  tp_f64_array_exit(tp_f64_array *a);
    int  tp_i64_array_exit(tp_i64_array *a);
    /* aliasing guard: 1 if any two of the n objects are the same object */
    int  tp_any_same(PyObject *const *objs, Py_ssize_t n);

## Opaque objects (Kotlin interop and any Python object; ir.Global/GetAttr/CallObject/Truth/CompareObj/ObjToFloat)
All return new references or NULL with CPython's exception set; none deopts.
    PyObject *tp_global(PyObject *module_dict, PyObject *name);   /* globals, then builtins; NameError("name 'x' is not defined") */
    PyObject *tp_getattr(PyObject *obj, PyObject *name);          /* PyObject_GetAttr */
    PyObject *tp_call(PyObject *callee, PyObject *const *args, size_t nargs, PyObject *kwnames); /* PyObject_Vectorcall: nargs = positional count; keyword values follow at args[nargs..] */
    /* tp_binop_obj op codes: TP_BINOP_ADD..TP_BINOP_MOD = 0..5 in ir.BinOpKind declaration order */
    int       tp_truth(PyObject *obj);                            /* 0/1, -1 error (PyObject_IsTrue) */
    int       tp_compare_bool(PyObject *a, PyObject *b, int op);  /* 0/1, -1 error: PyObject_RichCompare then PyObject_IsTrue (no identity shortcut, as `if a == b`) */
    int       tp_obj_to_f64(PyObject *obj, double *out);          /* 0 / -1 (PyNumber_Float, then exact double) */
    PyObject *tp_binop_obj(PyObject *a, PyObject *b, int op);     /* PyNumber_Add/... for ir.BinOpKind on OBJ */
    void      tp_release(PyObject **slot);                        /* Py_CLEAR: the only way generated code drops a reference */

## Local arrays (ir.NewArray / CopyArray; owned by the function, no list object)
Same structs as above with `list == NULL` and `dirty == NULL`. 0 ok, -1 MemoryError.
    int  tp_i64_array_new(tp_i64_array *a, int64_t n, int64_t fill);   /* n < 0 → empty */
    int  tp_i64_array_iota(tp_i64_array *a, int64_t n);                 /* 0..n-1; n < 0 → empty */
    int  tp_f64_array_new(tp_f64_array *a, int64_t n, double fill);
    int  tp_i64_array_copy(tp_i64_array *dst, const tp_i64_array *src);
    int  tp_f64_array_copy(tp_f64_array *dst, const tp_f64_array *src);
    void tp_i64_array_free(tp_i64_array *a);                            /* no write-back; zeroes; idempotent */
    void tp_f64_array_free(tp_f64_array *a);
    /* tp_*_array_slot works unchanged on local arrays (same IndexError messages as list). */
    PyObject *tp_tuple(PyObject *const *items, Py_ssize_t n);           /* new tuple; steals nothing */

## Call depth (every compiled impl function: enter at entry, leave at every exit; issue #57)
    int  tp_enter_call(void);   /* 0 ok; -1 RecursionError set, nothing counted (the caller must NOT call leave) */
    void tp_leave_call(void);   /* undoes one successful tp_enter_call; exactly once per success, on ok, error and deopt exits alike */
`tp_enter_call` makes compiled recursion fail where interpreted recursion fails. It counts, per thread,
the compiled frames in flight (they have no Python frame), adds the current Python frame depth
(`PyThreadState_GetFrame` / `PyFrame_GetBack`, public API only; cached while the top frame is the same
object, so a pure compiled recursion pays O(1) per call) and compares the total with
`Py_GetRecursionLimit()`. Over it: `RecursionError("maximum recursion depth exceeded")`, the text
CPython's own Python-frame check uses. It then calls `Py_EnterRecursiveCall(" in compiled code")`, the
C-stack guard: a raised recursion limit can never let compiled C overflow the stack. Counters are
per module (the header is `static`): frames of a *different* compiled module that sit between two
frames of this one are not counted.

## Fast entry (bounded functions; issue #147)
    int  tp_depth_room(Py_ssize_t k);   /* 1 iff tp_python_depth() + count + k <= Py_GetRecursionLimit() */
    void tp_trace_fast_hit(void);       /* counts a fast entry; a no-op unless -DTP_TRACE_FAST */
    void tp_depth_py_begin(int fd);     /* count += fd, around code a fast tree may run (redo, poll) */
    void tp_depth_py_end(int fd);       /* count -= fd; drops the cached frame at count 0 */
    void tp_depth_done(void);           /* after a fast tree: drop the cached top frame when count is 0 */
A function is *bounded* when it is in no call cycle, every function it Calls is bounded, and its body
has no node that can run Python code (CallObject, GetAttr, Truth, CompareObj, ObjToFloat, FieldGet,
FieldSet, New, any OBJ-typed expression except Box of a scalar). A `redo` Call to a bounded callee is allowed. Its k is `1 + max(k(callee))` (1 with no
callees). cgen emits, per bounded `f`, `static inline int tp_fimpl_f(...)` (the impl body without
tp_enter_call / tp_leave_call and without the entry poll, Calls to the callees' `tp_fimpl_*`) and, at
the top of `tp_impl_f` after the Param.cls guards, `if (tp_depth_room(k)) { r = tp_fimpl_f(...);
tp_depth_done(); return r; }`. No room: the counted path runs and raises RecursionError exactly where
it raised before. `tp_enter_call` compares `depth + count + 1`; the fast tree has at most k frames, so
`depth + count + k` is the same comparison at its deepest frame. Py_EnterRecursiveCall is skipped on
the fast path: the tree is bounded, so it adds at most k small C frames. A twin takes `int tp_fd`
(the tree's active frames, itself included; the precheck passes 1, a Call passes `tp_fd + 1`). The
two points where a fast tree can run Python code, a `redo` Call and the loop poll's `tp_poll_slow`,
are wrapped in `tp_depth_py_begin(tp_fd)` / `tp_depth_py_end(tp_fd)`, so everything run from there
(interpreted frames, signal handlers, compiled calls through their precheck) sees the exact count.
`-DTP_TRACE_FAST`
(tests only) adds `__tp_trace_fast_hits__()` to the module: the number of entries that took the fast path.

## Eval breaker (top of every loop iteration and impl entry; issue #141)
    int  tp_poll(PyObject *breaker);        /* 0 continue; -1 exception set (a signal handler raised) */
    int  tp_poll_slow(PyObject *breaker);   /* every TP_POLL_INTERVAL polls: call the breaker */
    Py_ssize_t tp_snapshot_depth;           /* ++ at entry, -- at tp_exit of a snapshot-holding impl */
`breaker` is the module state's `lambda: None`. Calling it runs CPython's own eval-breaker handling
in its RESUME: signals, pending calls, forced GIL switching to a waiting thread, async exceptions and
scheduled collections, exactly as between two bytecodes. A function that holds an array copy-in
(ir.ArrayParam) or an entry-globals snapshot never polls, and while one is active the slow path does
nothing: the event stays pending and is handled after it returns.

## Fixed-layout classes (ir.ClassDecl / FieldGet / FieldSet / New / IsExact / CheckExact; SPEC N-11)
Guard state, one per ClassDecl in the module state (zeroed = not compiled):

    typedef struct {
        unsigned int tag;       /* tp_version_tag at which the slots were proved; never 0 while compiled */
        unsigned int bad_tag;   /* a tag at which tp_class_refresh failed (0: none); not retried */
        uint64_t gepoch;        /* tp_globals_epoch at which module_dict[name] was last seen to be the class */
        PyObject *init;         /* strong: the class's own __init__ at capture, or NULL */
        Py_ssize_t slow;        /* failed per-access checks (introspection) */
        Py_ssize_t refreshed;   /* new tags adopted by tp_class_refresh (introspection) */
    } tp_class_rt;
    static uint64_t tp_globals_epoch;   /* moves when a watched module dict changes a class-name key */

At module init (none runs user code):

    /* `name` in cls's OWN dict (no MRO): 1 found (*out new ref), 0 absent, -1 error */
    int  tp_own_attr(PyTypeObject *cls, const char *name, PyObject **out);
    /* `field` in cls's own dict must be a member descriptor made for cls, of a writable
       Py_T_OBJECT_EX slot of that name inside the instance: *out = its PyMemberDef, *descr = a new
       reference to the descriptor. 0 ok, 1 not a plain slot (class not compiled), -1 error */
    int  tp_slot_capture(PyTypeObject *cls, const char *field, PyMemberDef **out, PyObject **descr);
    /* heap type made by `type`, base object, no __dict__, fixed size, object's tp_new/tp_alloc,
       generic getattro/setattro (no __getattribute__/__getattr__/__setattr__/__delattr__), not abstract */
    int  tp_class_shape_ok(PyTypeObject *cls);
    /* the whole ClassDecl check: shape, every field (slots[i], descrs[i]), rt->init, and a version
       tag (PyUnstable_Type_AssignVersionTag; none → not compiled). 1 compiled, 0 not, -1 error;
       on 0/-1 nothing is kept */
    int  tp_class_capture(PyObject *cls, const char *const *fields, Py_ssize_t n,
                          PyMemberDef **slots, PyObject **descrs, tp_class_rt *rt);
    void tp_class_rt_clear(tp_class_rt *rt, PyObject **descrs, Py_ssize_t n);  /* drop refs, zero tags */

At every access (the class may have changed since init; cgen.py "Classes changed after init"):

    /* FieldGet/FieldSet (obj) or New (obj == NULL): 1 = type(obj) is cls and cls's tp_version_tag is
       rt->tag (or tp_class_refresh re-proved it), 0 = take the slow path / deopt, -1 = error.
       Fast case: one type compare, one tag compare. */
    int  tp_class_current(PyObject *obj, PyObject *cls, tp_class_rt *rt, const char *const *fields,
                          Py_ssize_t n, PyObject *const *descrs);
    /* the tag moved: get a tag (exhausted → 0), shape still ok, every field still the very
       descriptor object and __init__ the very object captured → adopt the tag (1); else remember
       it in bad_tag (0). -1 error. Runs no user code. */
    int  tp_class_refresh(PyTypeObject *cls, tp_class_rt *rt, const char *const *fields,
                          Py_ssize_t n, PyObject *const *descrs);
    /* LOAD_GLOBAL of the class name as an identity test: 1 module_dict[name] is cls, 0 not (or not a
       module global), -1 error. watched: a 1 is cached in rt->gepoch until tp_globals_epoch moves */
    int  tp_class_global_ok(PyObject *module_dict, PyObject *name, PyObject *cls, tp_class_rt *rt,
                            int watched);
    unsigned int tp_class_tag(PyObject *cls);                     /* cls->tp_version_tag; 0 for NULL */

Module-dict watcher (one id per module instance; ids 2..7 of 8 per interpreter are free for all
extensions together):

    /* body of the module's PyDict_WatchCallback: move tp_globals_epoch when key is an exact str
       equal to one of `names` (NULL-terminated), any other key type or str subclass, or NULL
       (cleared/cloned/deallocated). Never raises; returns 0 */
    int  tp_globals_watch_event(PyDict_WatchEvent event, PyObject *key, const char *const *names);
    /* PyDict_AddWatcher + PyDict_Watch: *id1 = id + 1, or 0 when no id is free / Watch failed
       (error cleared: the caller uses the conservative path, a dict lookup per New) */
    void tp_globals_watch(PyObject *module_dict, PyDict_WatchCallback cb, int *id1);
    /* PyDict_Unwatch (if module_dict) + PyDict_ClearWatcher; idempotent; keeps a pending exception */
    void tp_globals_unwatch(PyObject *module_dict, int *id1);

Slot access and the slow paths:

    PyObject *tp_field_get(PyObject *obj, PyMemberDef *m);           /* PyMember_GetOne: new ref or AttributeError */
    int       tp_field_set(PyObject *obj, PyMemberDef *m, PyObject *v); /* PyMember_SetOne: 0 / -1 */
    int       tp_setattr(PyObject *obj, PyObject *name, PyObject *v);   /* STORE_ATTR (PyObject_SetAttr): 0 / -1 */
    PyObject *tp_new_fixed(PyTypeObject *cls, PyMemberDef *const *slots, PyObject *const *values, Py_ssize_t n);
              /* cls->tp_alloc(cls, 0) then set each slot (new references); NULL + MemoryError */
    int       tp_is_exact(PyObject *obj, PyTypeObject *cls);         /* Py_IS_TYPE */
FieldGet's slow path is `tp_getattr`, New's is `tp_global` + `tp_call` (the binding loaded before
the arguments), IsExact's compares with `tp_global`; in a pure function every slow path is a deopt.

Version tags (CPython 3.14 Objects/typeobject.c): PyType_Modified sets tp_version_tag to 0 for the
type and its subclasses; assign_version_tag gives a heap type `NEXT_VERSION_TAG(interp)++`, a
per-interpreter counter that only grows and, once it wraps to 0, assigns nothing again; a class
gets at most MAX_VERSIONS_PER_CLASS (1000) tags. So a tag never repeats within an interpreter and a
class whose tags are exhausted keeps 0 (never current). Py_TPFLAGS_VALID_VERSION_TAG is "Unused.
Legacy flag" in 3.14 (never set) and is not consulted.
