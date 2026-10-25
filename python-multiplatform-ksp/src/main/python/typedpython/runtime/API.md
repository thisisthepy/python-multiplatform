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
    /* Proven operations (ir.BinOp.proven, #148): the verifier showed the result fits in i64, so the
       generated code calls these (function-like macros; no check, no status). With
       -DTP_CHECK_PROVEN they are the checked helpers above and a wrong proof is a SystemError. A
       proven // or % by a power of two is emitted inline as `>>` / `&`, not through a helper. */
    tp_proven(add|sub|mul, a, b, t);                         /* t = a op b, no overflow check */
    tp_proven_neg(a, t);                                     /* t = -a */
    int64_t tp_padd_i64(int64_t a, int64_t b);               /* the unchecked forms behind them */
    int64_t tp_psub_i64(int64_t a, int64_t b);
    int64_t tp_pmul_i64(int64_t a, int64_t b);
    int64_t tp_pneg_i64(int64_t a);
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

    /* Free-threaded CPython (#159): the wrapper holds the lists' critical section from before the
       first enter to after the last exit, on every path (ok, error, deopt). tp_cs is zero-initialised
       (`tp_cs c = {0};`). b == NULL locks one list, else both (PyCriticalSection2: ordered by
       address, so two threads locking (a, b) and (b, a) cannot deadlock). More than two list
       parameters are not compiled (frontend.Skip, verify/structure, CGenError): CPython has no
       three-object section and a nested third would suspend the others. With a GIL both are no-ops. */
    typedef struct { int n; /* + a PyCriticalSection2 on free-threaded builds */ } tp_cs;
    void tp_cs_begin(tp_cs *c, PyObject *a, PyObject *b);
    void tp_cs_end(tp_cs *c);      /* idempotent: a no-op when nothing is held */
`tp_*_array_enter` / `_exit` read and write the list through borrowed references (`PyList_GET_ITEM`,
`PyList_SetItem`) and REQUIRE the caller to hold that critical section. Between enter and exit no
other thread can change the list, so the whole call is one atomic step as far as lists go (an
interleaving CPython can produce: each list operation takes the same lock). A long compiled array
loop does not poll (below), so it delays other threads' stop-the-world requests (the free-threaded
GC) until it returns: a liveness delay, results unaffected. Polls inside array-holding functions
need the re-validation decided in issue #152 and are not added.

## Buffer arrays (issue #149; `ArrayParam.buffer` = "array")
An exact `array.array` argument (`array.array[float]` / `array.array[int]`; the names must resolve
through the module's own top-level imports) is not copied.
    typedef struct { Py_buffer view; Py_ssize_t len; void *data; int held; } tp_buf;
    int  tp_buf_enter(PyObject *obj, PyObject *exact, const char *fmt1, const char *fmt2, int writable, tp_buf *b);
    void tp_buf_exit(tp_buf *b);          /* PyBuffer_Release if held; zeroes b; idempotent */
    int  tp_buf_slot(const tp_buf *b, int64_t i, int store, Py_ssize_t *slot);   /* 0 / -1, array.array's IndexError text */
    double  tp_buf_ld_f64(const tp_buf *b, Py_ssize_t i);   void tp_buf_st_f64(const tp_buf *b, Py_ssize_t i, double v);
    int64_t tp_buf_ld_i64(const tp_buf *b, Py_ssize_t i);   void tp_buf_st_i64(const tp_buf *b, Py_ssize_t i, int64_t v);
    int  tp_import_attr(const char *mod, const char *attr, PyObject **out);   /* exec slot: new ref or NULL (cleared); -1 MemoryError */
`enter` returns 0 (held), 1 (deopt: b zeroed, nothing held, no exception) or -1 (MemoryError). It
deopts unless: `exact` is non-NULL and `Py_IS_TYPE(obj, exact)` (a subclass may override
`__getitem__`); `PyObject_GetBuffer(C_CONTIGUOUS | FORMAT [| WRITABLE when the function stores])`
succeeds; ndim 1, itemsize 8, no suboffsets, `shape[0] * 8 == len`; the format string is exactly
`d` (floats) or `q` / `l` (ints; a byte-order prefix, `Q`, `i` ... fail); the data is 8-byte aligned.
The wrapper calls `exit` on every path (ok, error, deopt), so the export lives exactly as long as the
compiled call. Element access is one relaxed atomic 8-byte load or store (`__atomic_load_n` /
`__atomic_store_n` on `uint64_t`, bit-cast with `memcpy`); `-DTP_BUFFER_PLAIN` replaces them with
plain loads and stores, for measuring what the atomics cost only. `len` is `shape[0]`, fixed for the
call by the export.

Rules that go with it (verifier / front end):
* Closed (#203) like a list: a function with a buffer runs no user code (`verify/array-open`).
* No limit on the number of buffer parameters (no critical section); lists keep the limit of two.
  A buffer and a list cannot alias (different types). Two buffers may share memory (the same array
  passed twice): per-element access makes reads see earlier writes in program order, as in
  the interpreter, which a copy-in could not.
* `y = x[:]` of a buffer is not a native copy: the function stays interpreted.
* numpy: not supported yet (#220). An `NDArray[...]` / `np.ndarray` parameter is an ordinary object
  parameter. Reasons: (1) arithmetic on numpy elements produces `np.float64`, `np.int64` and
  `np.bool` in the interpreter (a loop `s += v` returns `np.float64`), compiled code returns `float`;
  (2) numpy scalar operations emit `RuntimeWarning`s ("overflow encountered in scalar multiply",
  "invalid value encountered in scalar add") and divide by zero warns instead of raising, which
  compiled double arithmetic does neither, and warnings are observable; (3) emulating this needs taint
  tracking of numpy-derived values, numpy-scalar boxing and FP-exception-to-warning emulation.
* OPEN POINT (decision pending in #149), deliberately not decided here and not tested: holding the
  export makes another thread's `append` / `resize` / `extend` on the same `array.array` raise
  `BufferError` for the duration of the call; what a concurrent resize should observe instead is not
  settled.
* Free-threaded: no critical section; every element access is an atomic, so a concurrent writer of the
  same element yields one whole value, never a torn one. A function holding a buffer does not poll the
  eval breaker (same liveness note as lists, #152).

## Opaque objects (Kotlin interop and any Python object; ir.Global/GetAttr/CallObject/Truth/CompareObj/ObjToFloat)
All return new references or NULL with CPython's exception set; none deopts.
    PyObject *tp_global(PyObject *module_dict, PyObject *name);   /* globals, then builtins; NameError("name 'x' is not defined") */
    PyObject *tp_getattr(PyObject *obj, PyObject *name);          /* PyObject_GetAttr */
    PyObject *tp_call(PyObject *callee, PyObject *const *args, size_t nargs, PyObject *kwnames); /* PyObject_Vectorcall: nargs = positional count; keyword values follow at args[nargs..] */
    /* tp_binop_obj op codes: TP_BINOP_ADD..TP_BINOP_MOD = 0..5 in ir.BinOpKind declaration order;
       TP_BINOP_IADD..TP_BINOP_IMOD = 6..11 are the augmented forms (PyNumber_InPlace*, ir.BinOp.inplace);
       TP_BINOP_LSHIFT, TP_BINOP_RSHIFT = 12, 13 and TP_BINOP_ILSHIFT, TP_BINOP_IRSHIFT = 14, 15 */
    int       tp_truth(PyObject *obj);                            /* 0/1, -1 error (PyObject_IsTrue) */
    int       tp_compare_bool(PyObject *a, PyObject *b, int op);  /* 0/1, -1 error: PyObject_RichCompare then PyObject_IsTrue (no identity shortcut, as `if a == b`) */
    int       tp_obj_to_f64(PyObject *obj, double *out);          /* 0 / -1 (PyNumber_Float, then exact double) */
    PyObject *tp_binop_obj(PyObject *a, PyObject *b, int op);     /* PyNumber_Add/... for ir.BinOpKind on OBJ */
    void      tp_release(PyObject **slot);                        /* Py_CLEAR: the only way generated code drops a reference */

## Native ints (ir.Type.INT; SPEC N-13, issue #232)
A `tp_int` is `{int64_t v; PyObject *big;}`: native when `big == NULL`, else `big` owns a reference to an
exact int outside i64 (`v` unused). Every temporary and local of this type owns `big`; the exit block
releases them all. Each operation is the native one when both operands are native and the result fits
i64, else CPython's `PyNumber_*` on the two ints (exact ints, no user code), with the result narrowed
back to native when it fits. Nothing deopts. Operations return 0, or -1 with the exception set (a zero
divisor, a negative shift count, OverflowError, MemoryError), leaving `*out` untouched on failure.
The overflow checks are the macros `TP_INT_ADD_OVF`, `TP_INT_SUB_OVF`, `TP_INT_MUL_OVF`, the single
mutation points the tests replace by wrapping arithmetic and must see fail.
    void      tp_int_release(tp_int *a);                          /* drop big (idempotent) */
    void      tp_int_copy(tp_int *dst, const tp_int *src);        /* dst = src, a new reference to big */
    void      tp_int_assign(tp_int *dst, tp_int *src);            /* dst takes src (left empty), releasing what dst held */
    PyObject *tp_int_box(const tp_int *a);                        /* a new reference to the int (PyLong_FromLongLong when native) */
    int       tp_int_take(tp_int *out, PyObject *o);              /* o (a new reference, stolen) -> out, narrowed; NULL or a non-exact-int -> -1 */
    int       tp_int_of_exact(tp_int *out, PyObject *o);          /* o borrowed: 0 ok, 1 not an exact int (nothing set), -1 error */
    int       tp_int_add(const tp_int *a, const tp_int *b, tp_int *out);      /* also tp_int_sub, tp_int_mul */
    int       tp_int_floordiv(const tp_int *a, const tp_int *b, tp_int *out); /* also tp_int_mod: floor semantics */
    int       tp_int_lshift(const tp_int *a, const tp_int *b, tp_int *out);   /* also tp_int_rshift */
    int       tp_int_neg(const tp_int *a, tp_int *out);
    int       tp_int_cmp(const tp_int *a, const tp_int *b, int op);           /* op = Py_EQ..Py_GE; 0 / 1, -1 error */

## Global cache (ir.Global; SPEC N-13)
`tp_gslot` is one cached module-global or builtin read: a BORROWED pointer and the epoch it was read at,
so the cache extends no object's lifetime (a rebound function, type or module is freed, and its `__del__`s
run, when CPython would). A read is served from the slot while its `epoch` equals `tp_globals_epoch`;
otherwise `tp_gcache_fill` looks the name up inside the critical sections of the module dict and the
builtins dict captured at exec and remembers the object. The watcher moves the epoch on a change of any
watched name (the names the module reads, the compiled class names and `__builtins__`), a clear or a
dealloc; a rebinding of `__builtins__` also sets `tp_gcache_off`, after which every read does the full
lookup. A hit takes its own reference: on the GIL build `Py_NewRef` (nothing runs between the check and
the incref, and the epoch moves before the dict drops its reference); free-threaded, the fill calls
`PyUnstable_EnableTryIncRef`, a hit uses `PyUnstable_TryIncRef` and then re-reads the epoch, falling back
to the fill when either fails. Free-threaded needs CPython 3.14+ (3.13t does not cache).
`TP_GCACHE_FRESH(slot_epoch, epoch)` is the single mutation point the tests replace by "any nonzero
epoch" and must see fail. Without a watcher (`watched` 0) every read is `tp_global`.
    PyObject *tp_gcache_global(tp_gslot *slot, PyObject *module_dict, PyObject *builtins, PyObject *name, int watched); /* a new reference, or NULL with NameError */
    PyObject *tp_gcache_fill(tp_gslot *slot, PyObject *module_dict, PyObject *builtins, PyObject *name);
    void      tp_gcache_clear(tp_gslot *slot);                     /* module clear: forget the pointer */

## Objects: subscript, in, f-strings, iteration (issue #150; ir.GetItem / StoreItem / Contains / NewList / NewDict / Format / BuildString / ForObj / a str Const)
Every helper makes the C API call the interpreter's bytecode makes, so results, exceptions (type,
message, args) and the user `__hash__` / `__eq__` / `__index__` / `__getitem__` / `__format__` /
`__iter__` calls, and their order, are CPython's. New references or NULL / -1 with the exception set;
none deopts. A fast path replaces a generic call only under an exact-type guard, and only with the C
function the generic call ends in for that exact type: the two guards are the macros
`TP_FASTPATH_GUARD_DICT(o)` (`PyDict_CheckExact`) and `TP_FASTPATH_GUARD_STR(o)`
(`PyUnicode_CheckExact`), the single mutation points the tests widen to `PyDict_Check` /
`PyUnicode_Check` and must see fail. A `dict` or `str` subclass (which can override
`__getitem__`, `__setitem__`, `__add__`, ...) always takes the generic call.
    PyObject *tp_getitem(PyObject *obj, PyObject *key);           /* BINARY_OP []: PyObject_GetItem; exact dict: PyDict_GetItemRef + KeyError(key) as dict_subscript raises it */
    int       tp_setitem(PyObject *obj, PyObject *key, PyObject *value); /* STORE_SUBSCR: PyObject_SetItem; exact dict: PyDict_SetItem. 0 / -1 */
    int       tp_contains(PyObject *container, PyObject *needle); /* CONTAINS_OP: PySequence_Contains; 1 / 0 / -1 */
    PyObject *tp_new_list(void);                                  /* PyList_New(0) */
    PyObject *tp_new_dict(void);                                  /* PyDict_New() */
    PyObject *tp_format(PyObject *value, PyObject *spec, int conversion); /* one f-string field: conversion -1 / 's' / 'r' / 'a' (PyObject_Str / Repr / ASCII), then spec != NULL: PyObject_Format(v, spec); else an exact str as it is, else PyObject_Format(v, NULL) */
    PyObject *tp_build_string(PyObject *const *parts, Py_ssize_t n); /* BUILD_STRING: PyUnicode_Join("", parts); parts are borrowed */
    PyObject *tp_get_iter(PyObject *obj);                         /* GET_ITER: PyObject_GetIter */
    PyObject *tp_iter_next(PyObject *iter);                       /* FOR_ITER: PyIter_Next; NULL without an error at the end */
    int       tp_unpack(PyObject *v, Py_ssize_t n, PyObject **out); /* UNPACK_SEQUENCE: an exact tuple of length n directly, else through its iterator with the interpreter's "cannot unpack non-iterable T object" / "not enough values to unpack (expected n, got k)" / "too many values to unpack (expected n[, got k])"; 0 with n new references in out, or -1 with none */
`str + str` (both exactly str) in `tp_binop_obj` is `PyUnicode_Concat`, which `PyNumber_Add` ends in for two strs.
Free-threaded: containers are read with the strong-reference API (`PyDict_GetItemRef`); an exact
tuple's items may be borrowed because a tuple is immutable; a list or any other object is read
through its iterator, so no borrowed reference into a container outlives the call that returned it.
A str constant is an interned object held in the module state (`names`, immutable after init).

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
    int  tp_enter_call(PyThreadState *ts, tp_tls *t);  /* 0 ok; -1 RecursionError set, nothing counted (the caller must NOT call leave) */
    void tp_leave_call(tp_tls *t);   /* undoes one successful tp_enter_call; exactly once per success, on ok, error and deopt exits alike */
Thread state (#125): the wrapper calls `PyThreadState_GetUnchecked()` once and passes it as `tp_ts` to the
impl, which passes it to `tp_enter_call`, `tp_depth_room`, `tp_traced` and to its direct compiled callees,
so a compiled call and its C-to-C callees do one TLS lookup of the thread state per call. Likewise the per-thread state (call depth, poll countdown, snapshot depth) is one `__thread` struct
`tp_tls`: the wrapper takes `tp_tls_get()` once and passes `tp_t` down. `Py_EnterRecursiveCall`,
`Py_LeaveRecursiveCall` and `Py_GetRecursionLimit` have no public tstate-taking variant and are kept.

Thread state (#125): the wrapper calls `PyThreadState_GetUnchecked()` once and passes it as `tp_ts` to the
impl, which passes it to `tp_enter_call`, `tp_depth_room`, `tp_traced` and to its direct compiled callees,
so a compiled call and its C-to-C callees do no further thread-state lookup. Likewise the per-thread state (call depth, poll countdown, snapshot depth) is one `__thread` struct
`tp_tls`: the wrapper takes `tp_tls_get()` once and passes `tp_t` down. `Py_EnterRecursiveCall`,
`Py_LeaveRecursiveCall` and `Py_GetRecursionLimit` have no public tstate-taking variant and are kept.

`tp_enter_call` makes compiled recursion fail where interpreted recursion fails. It counts, per thread,
the compiled frames in flight (they have no Python frame), adds the current Python frame depth
(`PyThreadState_GetFrame` / `PyFrame_GetBack`, public API only; cached while the top frame is the same
object, so a pure compiled recursion pays O(1) per call) and compares the total with
`Py_GetRecursionLimit()`. Over it: `RecursionError("maximum recursion depth exceeded")`, the text
CPython's own Python-frame check uses. It then calls `Py_EnterRecursiveCall(" in compiled code")`, the
C-stack guard: a raised recursion limit can never let compiled C overflow the stack. Counters are
per module (the header is `static`) and per thread (`TP_THREAD_LOCAL`): frames of a *different*
compiled module that sit between two frames of this one are not counted, and another thread's
frames never are.

## Fast entry (bounded functions; issue #147)
    int  tp_depth_room(PyThreadState *ts, tp_tls *t, Py_ssize_t k);   /* 1 iff tp_python_depth(ts) + count + k <= Py_GetRecursionLimit() */
    void tp_trace_fast_hit(void);       /* counts a fast entry; a no-op unless -DTP_TRACE_FAST */
    void tp_depth_py_begin(tp_tls *t, int fd);     /* count += fd, around code a fast tree may run (redo, poll) */
    void tp_depth_py_end(tp_tls *t, int fd);       /* count -= fd; drops the cached frame at count 0 */
    void tp_depth_done(tp_tls *t);           /* after a fast tree: drop the cached top frame when count is 0 */
A function is *bounded* when it is in no call cycle, every function it Calls is bounded, and its body
has no node that can run Python code (CallObject, GetAttr, Truth, CompareObj, ObjToFloat, FieldGet,
FieldSet, New, any OBJ-typed expression except Box of a scalar). A `redo` Call to a bounded callee is allowed. Its k is `1 + max(k(callee))` (1 with no
callees). cgen emits, per bounded `f`, `static inline int tp_fimpl_f(...)` (the impl body without
tp_enter_call / tp_leave_call and without the entry poll, Calls to the callees' `tp_fimpl_*`) and, at
the top of `tp_impl_f` after the Param.cls guards, `if (tp_depth_room(tp_ts, tp_t, k)) { r = tp_fimpl_f(...);
tp_depth_done(); return r; }`. No room: the counted path runs and raises RecursionError exactly where
it raised before. `tp_enter_call` compares `depth + count + 1`; the fast tree has at most k frames, so
`depth + count + k` is the same comparison at its deepest frame. Py_EnterRecursiveCall is skipped on
the fast path: the tree is bounded, so it adds at most k small C frames. A twin takes `int tp_fd`
(the tree's active frames, itself included; the precheck passes 1, a Call passes `tp_fd + 1`). The
two points where a fast tree can run Python code, a `redo` Call and the loop poll's `tp_poll_slow`,
are wrapped in `tp_depth_py_begin(tp_t, tp_fd)` / `tp_depth_py_end(tp_t, tp_fd)`, so everything run from there
(interpreted frames, signal handlers, compiled calls through their precheck) sees the exact count.
`-DTP_TRACE_FAST`
(tests only) adds `__tp_trace_fast_hits__()` to the module: the number of entries that took the fast path.

## Eval breaker (top of every loop iteration and impl entry; issue #141)
    int  tp_poll(tp_tls *t, PyObject *breaker);        /* 0 continue; -1 exception set (a signal handler raised) */
    int  tp_poll_slow(tp_tls *t, PyObject *breaker);   /* every TP_POLL_INTERVAL polls: call the breaker */
    int  tp_pc_tick(int *pc);               /* loop countdown: 1 (and reset) when the slow path is due */
    tp_tls *tp_tls_get(void);               /* address of the one THREAD-LOCAL struct: depth, poll_countdown, snapshot_depth (++ at entry, -- at tp_exit of a snapshot-holding impl) */
`breaker` is the module state's `lambda: None`. Calling it runs CPython's own eval-breaker handling
in its RESUME: signals, pending calls, stop-the-world requests of other threads (the free-threaded
GC), async exceptions and scheduled collections, exactly as between two bytecodes. A function that holds an array copy-in
(ir.ArrayParam) or an entry-globals snapshot never polls, and while one is active the slow path does
nothing: the event stays pending and is handled after it returns.

## Tracing, profiling and monitoring (entry of every function; issue #142, SPEC N-14)
    int  tp_traced(PyObject *module, PyThreadState *ts, PyCodeObject *code);       /* 1: run the interpreted original */
    PyObject *tp_state.orig_code[];  /* GENERATED, module state: STRONG ref to the original's code, per interned name */
`tp_traced` is true when the calling thread has a `sys.settrace` / `sys.setprofile` function
(`PyThreadState.c_tracefunc` / `c_profilefunc`), when any `sys.monitoring` tool has a global event
(`PyMonitoring_EnterScope` version, then `sys.monitoring.get_events` only when it moved), or when a tool
has a local event on `code`, the original function's code object (a strong reference in the module state, filled at module exec after `__typedpython_interpreted__` is built, traversed and released with the state; NULL for a name that is no function) (`code->_co_monitoring`, whose first
16 bytes are `local_monitors`; mirrored for 3.14 and 3.15 only, any other version is a named `#error`). The
wrapper calls it before anything else and takes the deopt path; an impure caller calls it before a
direct call of a compiled callee.

## Fixed-layout classes (ir.ClassDecl / FieldGet / FieldSet / New / IsExact / CheckExact; SPEC N-11)
Guard state, one per ClassDecl in the module state (zeroed = not compiled):

    typedef struct {
        unsigned int tag;       /* ATOMIC. tp_version_tag at which the slots were proved; never 0 while compiled */
        unsigned int bad_tag;   /* ATOMIC. a tag at which tp_class_refresh failed (0: none); not retried */
        uint64_t gepoch;        /* ATOMIC. tp_globals_epoch at which module_dict[name] was last seen to be the class */
        PyObject *init;         /* IMMUTABLE after capture. strong: the class's own __init__ at capture, or NULL */
        Py_ssize_t slow;        /* ATOMIC. failed per-access checks (introspection) */
        Py_ssize_t refreshed;   /* ATOMIC. new tags adopted by tp_class_refresh (introspection) */
        unsigned int mtag;      /* ATOMIC. tp_version_tag at which every installed method was the very object in the class dict (#155); 0 until tp_class_retag */
        unsigned int mbad_tag;  /* ATOMIC. a tag at which tp_methods_refresh failed (0: none); not retried */
    } tp_class_rt;
    static uint64_t tp_globals_epoch;   /* ATOMIC. moves when a watched module dict changes a class-name key */
    /* atomic accessors (relaxed loads, acq_rel adds); every read of cls->tp_version_tag goes through one */
    unsigned int tp_type_tag(const PyTypeObject *cls);
    void         tp_class_note_slow(tp_class_rt *rt);
    unsigned int tp_class_rt_tag(const tp_class_rt *rt);
    Py_ssize_t   tp_class_rt_slow(const tp_class_rt *rt);
    Py_ssize_t   tp_class_rt_refreshed(const tp_class_rt *rt);

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
       module global), -1 error. watched: a 1 is cached in rt->gepoch until tp_globals_epoch moves.
       The slow path reads the epoch and looks the name up inside the module dict's critical section
       (the dict watcher fires before a change is stored and under that same lock), so a binding
       changed by another thread is never remembered as current. */
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
    /* PyDict_Watch(id1 - 1, builtins); 0, or -1 when it cannot be watched (no exception left) */
    int  tp_globals_watch_builtins(PyObject *builtins, int id1);
    /* PyDict_Unwatch(id1 - 1, builtins); keeps a pending exception */
    void tp_globals_unwatch_builtins(PyObject *builtins, int id1);
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

### Methods (ir.Function.owner / ir.CheckMethod; SPEC N-11, issue #155)

A plain instance method of a compiled class is an ir.Function named `C.m` with `owner = "C"`. The module
init (after the class guards are captured) replaces the class dict's function by a descriptor object
of a type the extension defines, stores the original function in `__typedpython_interpreted__["C.m"]`
and adopts the class's new version tag:

    typedef PyObject *(*tp_method_fn)(PyObject *module, PyObject *const *args, Py_ssize_t nargs, PyObject *kwnames);
    typedef struct { PyObject_HEAD; vectorcallfunc vectorcall; PyObject *module; PyObject *func; tp_method_fn fn; } tp_method_obj;
    /* generated: tp_method_install(type, module, cls, name, qname, fn, table, &out): the class's OWN entry
       must be a plain function (PyFunction_Check): 0 installed, 1 not (class not compiled), -1 error */
    int  tp_class_retag(PyTypeObject *cls, tp_class_rt *rt);        /* init only: adopt the current tag as tag and mtag */
    /* CheckMethod: 1 = type(obj) is cls and every installed method name in `names` is, in cls's own dict,
       the very descriptor in `descrs` (mtag fast path, else tp_methods_refresh); 0 = deopt; -1 error */
    int  tp_methods_current(PyObject *obj, PyObject *cls, tp_class_rt *rt, const char *const *names,
                            Py_ssize_t n, PyObject *const *descrs);
    int  tp_methods_refresh(PyTypeObject *cls, tp_class_rt *rt, const char *const *names, Py_ssize_t n,
                            PyObject *const *descrs);              /* also re-proves tp_class_shape_ok */
    #define TP_SELF_EXACT(o, cls) tp_is_exact(o, cls)             /* the one guard of a method's `self` */

What the descriptor does (the type is `typedpython.compiled_function`, immutable, not instantiable,
GC-tracked, `Py_TPFLAGS_METHOD_DESCRIPTOR | Py_TPFLAGS_HAVE_VECTORCALL`):

  * `C.m` is the descriptor itself, `obj.m` is a `types.MethodType` over it (`tp_descr_get` is
    `func_descr_get`'s rule); a call goes `vectorcall -> tp_wrap_C.m`, the same wrapper a module-level
    function has: arity or any keyword, a `self` that is not exactly `C` (a subclass instance, `C.m(5)`),
    a failed guard, or a deopt of the impl runs the ORIGINAL function with the same arguments, so
    CPython's own result or error comes out. The deopt counter counts it.
  * `self` is `Param(OBJ, cls=C)`; its guard is `TP_SELF_EXACT` in the wrapper and at the top of the impl.
  * A call `obj.m(args)` in a PURE compiled function whose receiver is a local of class C and whose
    callee is a pure compiled method is `Call("C.m", (CheckMethod(obj, C, m), *args))`: a direct C call
    guarded by `tp_methods_current` (one type compare, one tag compare). If someone rebound any
    compiled method of C, added `__getattribute__` ..., or the receiver is not exactly C, the pure
    caller deopts and CPython's own lookup runs in the redo. Impure callers, calls with keywords,
    callees with entry globals and argument type mismatches use `CallObject(GetAttr(obj, m))`, which finds
    the descriptor through `tp_getattr`.
  * Rebinding or deleting `C.m` replaces or removes the descriptor (CPython semantics); other
    descriptors of the class are untouched.

Observable differences from a function (recorded; each is tested):

  * `type(C.__dict__["m"])` is `typedpython.compiled_function` (not `function`); `isinstance(C.m,
    types.FunctionType)` is False; `inspect.isfunction` is False, `callable` is True.
  * `repr(C.m)` is `<compiled function C.m at 0x...>`; `repr(obj.m)` is the bound-method repr
    (`<bound method C.m of ...>`), `obj.m.__func__ is C.m`.
  * Forwarded from the original function on every access: `__name__`, `__qualname__`, `__doc__`,
    `__module__`. `__wrapped__` is the original function (so `inspect.signature` and `inspect.unwrap`
    see it). NOT forwarded (AttributeError): `__code__`, `__globals__`, `__defaults__`, `__kwdefaults__`,
    `__annotations__`, `__closure__`, `__dict__`, `__type_params__`. Nothing can be assigned to it.
  * A module-level compiled function is a builtin function (`PyCFunction`) with `__name__` and `__module__`
    only: no `__wrapped__`, no forwarded `__doc__`. The two differ on purpose; a method needs the
    forwarding for `help()`, `inspect` and pickling a bound method (`__reduce__` reads `__name__`).
  * A subclass is a different layout case: a subclass instance never takes the compiled path, even
    through an inherited method (guard fails, original runs).

Version tags (CPython 3.14 Objects/typeobject.c): PyType_Modified sets tp_version_tag to 0 for the
type and its subclasses; assign_version_tag gives a heap type `NEXT_VERSION_TAG(interp)++`, a
per-interpreter counter that only grows and, once it wraps to 0, assigns nothing again; a class
gets at most MAX_VERSIONS_PER_CLASS (1000) tags. So a tag never repeats within an interpreter and a
class whose tags are exhausted keeps 0 (never current). Py_TPFLAGS_VALID_VERSION_TAG is "Unused.
Legacy flag" in 3.14 (never set) and is not consulted.

## Free-threaded CPython (3.15t; issue #159)
Generated modules declare `{Py_mod_gil, Py_MOD_GIL_NOT_USED}` (under `#ifdef Py_GIL_DISABLED`), so
importing one keeps the GIL off. Every piece of shared mutable state, classified (the audit; a new
static or module-state field must be added here):

| State | Where | Class | How |
|---|---|---|---|
| `tp_depth` (compiled frames in flight, cached frame) | tp_runtime.h static | thread-local | `TP_THREAD_LOCAL` |
| `tp_tls_v.poll_countdown` | tp_runtime.h static | thread-local | `TP_THREAD_LOCAL` struct `tp_tls` |
| `tp_tls_v.snapshot_depth` | tp_runtime.h static | thread-local | `TP_THREAD_LOCAL`: one thread's array function never silences another thread's polls |
| `tp_pc` (loop countdown) | local of each impl | per call | a C local |
| `tp_globals_epoch` | tp_runtime.h static | atomic | relaxed loads, acq_rel add in the watcher callback (any thread); the epoch + dict lookup that remember a binding run in the module dict's critical section |
| `cls_rt[k].tag`, `.bad_tag`, `.gepoch` | module state | atomic | relaxed `tp_atomic_*`; a stale tag is never reused (tags only grow), so a lost or reordered refresh costs one more refresh |
| `cls_rt[k].mtag`, `.mbad_tag` | module state | atomic | relaxed `tp_atomic_*`, as `tag`; `tp_class_retag` writes `mtag` plainly at init only |
| `mtype` (descriptor type), `mdescrs[]` | module state | immutable after init | written by `tp_exec` (and `tp_clear`); the type is `Py_TPFLAGS_IMMUTABLETYPE` |
| each `tp_method_obj` (`module`, `func`, `fn`, `vectorcall`) | heap object in a class dict | immutable | written once by `tp_method_install` before it is published in the class dict; `tp_method_get` / call read only |
| `tp_mnames_*`, `tp_method_spec`, `tp_method_slots`, `tp_method_members` | generated statics | immutable | never written after import |
| `cls_rt[k].slow`, `.refreshed` | module state | atomic | `tp_atomic_add_ssize` (introspection only) |
| `cls->tp_version_tag` | CPython type | atomic read | `tp_type_tag` (CPython clears and reassigns it from any thread) |
| `cls_rt[k].init`, `names`, `kwnames`, `classes`, `cls_ok`, `slots`, `descrs`, `breaker`, `watch_id1` | module state | immutable after init | written by `tp_exec` (and `tp_clear` at teardown, when no call can run); the `PyMemberDef`s live in the classes the state holds |
| `tp_name_strings`, `tp_fields_*`, `tp_class_names`, `tp_source*`, `tp_methods`, `tp_slots`, `tp_moduledef` | generated statics | immutable | never written after import |
| `__typedpython_interpreted__` | module dict entry | immutable after init | the dict is filled by `tp_exec` only; read with `PyDict_GetItemRef` |
| `__typedpython_deopts__` | module dict entry | critical section | the read-add-write of `tp_cg_redo` runs in `Py_BEGIN_CRITICAL_SECTION(module dict)`: concurrent deopts lose no count |
| `buf_types` (array.array) | module state | immutable after init | written by `tp_exec` (and `tp_clear`); read-only type pointers compared by identity |
| buffer arguments of array parameters | caller's array.array memory | atomic | relaxed 8-byte `__atomic_load_n` / `__atomic_store_n` per element; the Py_buffer export is held for the call (open point on concurrent resize, #149) |
| list arguments of array parameters | caller's lists | critical section | `tp_cs_begin` before the copy-in, `tp_cs_end` after the write-back, on every path |
| module globals (class names, entry globals) | module dict | strong reads | `tp_global` and `tp_class_global_ok` use `PyDict_GetItemRef` |
| a compiled class's instances | other threads' objects | CPython | `PyMember_GetOne` / `SetOne` are what the interpreter's member descriptors call (they take the object's lock on free-threaded builds); `tp_new_fixed` writes only its own fresh object |
| `errno` (math helpers) | C library | thread-local | per thread by POSIX |
| `tp_par_cap_v`, `tp_par_regions_v`, `tp_par_started_v`, `tp_par_failed_v` | static in the module | atomic | relaxed loads and stores; the cap is computed identically by any thread that finds it unset and never changes after |
| a parallel region's arrays (`ForRange.par`) | caller's copied-in arrays | disjoint ranges | each worker writes `data[slot]` and `dirty[slot]` only for its own iterations (verifier: store index is the loop variable); the caller holds the list's critical section and joins every worker before the write-back |

Borrowed references are never held across anything another thread can free. Replaced in this change:
`tp_global`: `PyDict_GetItemWithError` (module globals, builtins) and `PyDict_GetItemString`
(`__builtins__`) became `PyDict_GetItemRef` / `PyDict_GetItemStringRef`; the builtins dict taken from
a module is kept alive by an own reference while it is read; `PyEval_GetBuiltins` became
`PyEval_GetFrameBuiltins` (a new reference). `tp_class_global_ok`: `PyDict_GetItemWithError` became
`PyDict_GetItemRef` inside the dict's critical section. `tp_cg_redo`: the counter update is under the
module dict's critical section. `PyList_GET_ITEM` in `tp_*_array_enter` stays a borrow: it is read
under the list's critical section the caller holds. Borrows that cannot race: `PyModule_GetDict` (the
dict lives as long as the module, which the running function keeps alive), `PyTuple_GET_SIZE` of a
kwnames tuple, `PyTuple` items.

Memory orders: relaxed loads and stores for the tags and the epoch (the synchronisation that
publishes a class or global change to another thread, a lock or an event, orders them), acquire-release
for the epoch's increment. `tp_runtime.h` needs the GCC/Clang `__atomic` builtins.

## Parallel regions (SPEC N-15, issues #151, #277)
A `ForRange` the verifier marked `par` (`ir.ParInfo`; marked function, `a[i] = F64 expression` stores
only) runs its iterations on per-region C threads above a work threshold. Workers touch no Python
object and no Python API, are not Python threads and never outlive the call.
    #define TP_PAR_MIN_WORK 65536          /* PLACEHOLDER, to be measured on #151: weighted iterations (trip count * ParInfo.weight) */
    #define TP_PAR_MAX_THREADS 8           /* default cap; TYPEDPYTHON_THREADS overrides (1 disables), hard cap 64 */
    #define TP_PAR_HARD_CAP 64
    #define TP_PAR_MIN_CHUNK 1024u         /* fewest iterations worth a thread (placeholder) */
    #define TP_PAR_ALIGN 8u                /* chunk starts are multiples of 8 iterations (one cache line of doubles) */
    #define TP_PAR_HAVE_THREADS            /* defined with pthreads; -DTP_NO_THREADS compiles the threads out */
    #define TP_PAR_TEST_FAIL_CREATE        /* test hook: every pthread_create reports failure */
    typedef void (*tp_par_fn)(const void *ctx, uint64_t lo, uint64_t hi);
    int  tp_par_threads(void);                                   /* the cap, read once from TYPEDPYTHON_THREADS, else min(cores, 8) */
    int  tp_par_run(tp_par_fn fn, const void *ctx, uint64_t n);  /* runs [0, n) in contiguous chunks, joined before return; returns workers started */
    void tp_par_stats(int64_t out[3]);                           /* regions with workers, workers started, failed creations */
`tp_par_run` always completes the whole range. The calling thread runs the first chunk; a chunk whose
`pthread_create` failed runs on the calling thread after it (fewer workers, down to none: the sequential
loop, no exception, not a semantic event). Workers start with all signals blocked, so Python signal
handlers run on Python threads only. The chunk function must be safe for any chunk on any thread;
generated code passes a context struct of raw array pointers and scalar copies. A module with a
`par` loop also exports `__typedpython_parallel__()` returning `(regions, workers_started,
failed_creations)`, so tests can prove the parallel path ran. Free-threaded state: the four counters and
the thread cap are atomics (relaxed); a region's chunk bounds, jobs and thread handles live on the
caller's stack. The caller does not poll the eval breaker inside a region (array functions do not poll
anyway, #152).

## Development-mode import gate (Python API, not C; SPEC N-7b)
    typedpython.importgate.install(source_roots)   # meta path finder; checks project modules before they run
    typedpython.importgate.uninstall()
    typedpython.importgate.install_from_env()      # installs when TYPEDPYTHON_IMPORT_GATE=<roots, os.pathsep-separated>
A type error raises `ImportGateError` (an `ImportError`, `.diagnostics` holds the gate's errors) and the module's code does not run. Only files under the roots are checked; results are cached by content hash. Do not call it in release builds.
