"""TypedPython C back end: `ir.Module` -> one C source file (a CPython extension, PEP 489).

The contract is `ir.py`; this file implements each node exactly as its docstring says, and reaches
anything with Python semantics only through the runtime helpers of `runtime/API.md`
(`tp_runtime.h`). What the generated file contains:

Module shape (ir.py "Module shape"). Multi-phase init. The exec slot runs the module's ORIGINAL
source (read at build time and embedded as a C string literal, compiled with its own path as the
filename) in the extension module's own dict, so every name, class and global is the interpreted
one. It then stores each compiled function's interpreted original in `__typedpython_interpreted__`
(a dict), sets `__typedpython_deopts__ = 0`, and replaces the global `f` with a C function
(METH_FASTCALL | METH_KEYWORDS) bound to the module.

Per function `f`:
  `static int tp_impl_f(PyObject *tp_module, <params>, <ret> *tp_out)` returns 0 ok, 1 deopt,
  -1 error. Scalars are passed by value, OBJ params borrowed (the impl takes its own reference),
  array params as `tp_*_array *`.
  `tp_wrap_f` is the Python-visible function: wrong arity or any keyword -> the interpreted
  function (CPython raises its own TypeError); entry guards (exact scalar types through
  tp_unbox_*, the array aliasing guard tp_any_same, then tp_*_array_enter); the impl; then
  tp_*_array_exit on every path (ok, error, deopt) before returning or redoing. A deopt (failed
  guard, or the impl's 1 in a pure function) increments `__typedpython_deopts__` and calls
  `__typedpython_interpreted__[f]` with the original arguments.

Free-threaded CPython 3.15t (issue #159; runtime/API.md "Free-threaded CPython" holds the table of
every piece of shared state). The module slots declare `{Py_mod_gil, Py_MOD_GIL_NOT_USED}` (under
`#ifdef Py_GIL_DISABLED`), so importing it keeps the GIL off. In `tp_wrap_f`, a function with list
parameters holds the lists' critical section (`tp_cs_begin`, one list or two) from before the first
copy-in to after the last write-back: `tp_cs_end` is part of the same `exits` that run the write-back
on every path (ok, error, deopt, the last before the interpreted redo), and is a no-op on a path
that left before locking. More than two list parameters are refused here (`CGenError`), as in the
front end and the verifier: CPython has no three-object critical section. A function that holds an
array does not poll the eval breaker (#141, open decision #152), so a long compiled array loop
delays other threads' stop-the-world requests until it returns: a liveness delay, results
unaffected. The `__typedpython_deopts__` read-add-write of `tp_cg_redo` runs in the module dict's
critical section. Module state is written only by the exec slot; the class guards are atomic
(tp_runtime.h).

Buffer arrays (issue #149, identity-safe part). A parameter annotated `array.array[float]`,
`array.array[int]` is an `ArrayParam` with `buffer` "array"; `NDArray[np.float64]` / `NDArray[np.int64]`
(#220) is one with `buffer` "numpy" (exact `numpy.ndarray`, an out-of-range index raises numpy's
IndexError text). A numpy element is an np.float64 / np.int64 in the interpreter: `Box.np` boxes
the matching numpy scalar when its run-time flag is true, and `BinOp.np` deopts a non-finite float
`+ - *` result so the interpreter emits numpy's RuntimeWarning (taint.py). `tp_wrap_f` does not copy it: after the scalar guards, `tp_buf_enter`
checks the exact type (captured in `tp_state.buf_types` by the exec slot), takes one `Py_buffer`
export (C-contiguous, 1-D, 8-byte items, format `d` / `q` / `l`, aligned, writable when the function
stores) and any mismatch deopts before an effect. The export is released by `tp_buf_exit`, part of the
same `exits` as the list write-back, so every path (ok, error, deopt before the interpreted redo)
releases it. Index/StoreIndex are `tp_buf_ld_*` / `tp_buf_st_*`: one relaxed atomic 8-byte access on
the buffer memory (`-DTP_BUFFER_PLAIN` makes them plain, for measuring the atomics only), so there is
no copy-in, no write-back, no critical section, no aliasing guard and no limit on their number; two
buffers that share memory see each other's writes in program order. Closedness (#203) is required as
for lists. OPEN POINT (decision pending in #149): holding the export makes another thread's `append`
/ `resize` of the same array.array raise BufferError during the call; what a concurrent resize
should observe is not decided here and nothing relies on it. A function holding a buffer does not poll
the eval breaker (it counts as a snapshot holder, conservatively).

Depth (issue #57): every impl function calls `tp_enter_call()` before it owns anything (a refusal
returns -1 directly: RecursionError set, nothing counted) and `tp_leave_call()` in the one exit
block, so ok, error and deopt exits all leave exactly once.

Fast entry (issue #147). A *bounded* function (`_bounded_functions`: no call cycle, every callee
bounded, no node that can run Python code) has a call tree of at most k compiled frames. Its
`tp_impl_f` first tests `tp_depth_room(k)`: with room for all k frames it runs `tp_fimpl_f`, the
twin with the same body and no tp_enter_call / tp_leave_call / entry poll, whose Calls go to the
callees' twins (a `static inline` function in the same unit, so the C compiler can inline it);
without room the counted path runs and raises RecursionError exactly where it always did. Frames
of a fast tree are not in `tp_depth.count`, so every twin takes `int tp_fd`, the number of the
tree's frames active including itself (1 from the precheck, `tp_fd + 1` into a callee twin), and
the two points where a fast tree can run Python code, a `redo` Call and the loop poll's
`tp_poll_slow` (a signal handler, a finalizer, another compiled call), are wrapped in
`tp_depth_py_begin(tp_fd)` / `tp_depth_py_end(tp_fd)`, which add and remove `tp_fd` from the count:
whatever runs there sees the count the counted path would have had.

Ownership rule (ir.py "Safety"), implemented by the `_own_*` emit helpers below and nowhere else:
  1. every OBJ local, including each OBJ parameter, which the impl copies with a new reference at
     entry, owns one strong reference, or is NULL;
  2. every OBJ-typed expression evaluates into a fresh temporary that owns a new reference;
     temporaries are NULL at every statement boundary;
  3. a temporary is consumed exactly once: moved into a local (the local's old reference is
     released), moved into `*tp_out` by Return, or released (`tp_release`) right after the helper
     that borrowed it returns;
  4. every exit of the impl, return, error, deopt, goes through one exit block that releases
     every OBJ local and temporary.
  The generated code never decrements a reference except through `tp_release` (API.md); the only
  increment is `Py_NewRef` when an OBJ local is read or an OBJ parameter is adopted.

Local arrays (ir.NewArray / CopyArray, `Function` docstring "Local arrays"). A local of an array type
is a `tp_i64_array` / `tp_f64_array` struct held by value in the impl, zero-initialised at entry
(`{0}`), with `list == NULL` and `dirty == NULL`. It is owned by the function:
  1. `Assign(local, NewArray | CopyArray)` builds the new array in a zeroed temporary struct
     (`tp_*_array_new` / `_iota` / `_copy`), and only when that succeeded frees the old array
     (`tp_*_array_free`) and moves the temporary into the slot, so a failed allocation leaves the
     old binding intact, as `a = [0] * n` does, and `a = a[:]` copies before it frees;
  2. every exit of the impl, return, error, deopt, frees every local array and every array
     temporary in the same exit block that releases the OBJ locals (a deopt of a pure function
     frees them before the wrapper redoes the call interpreted);
  3. local arrays never go through `tp_*_array_enter/exit` (there is no list to write back to) and
     a store into one sets no dirty bit.
`Index` / `StoreIndex` / `Len` use the same slot helper and `proven` handling as array params.
`Tuple` evaluates each element into an owned temporary, builds the tuple with `tp_tuple` (which
steals nothing), then releases the temporaries.

Fixed-layout classes (SPEC N-11, ir.ClassDecl). The module state holds, per ClassDecl, a strong
reference to the class object, a `cls_ok` flag and one captured `PyMemberDef *` per field. The exec
slot fills them AFTER the original source ran (`tp_class_capture`: a heap type made by `type`, base
object, no __dict__, every field a writable object slot made for that very class); a class that fails
any check is "not compiled" (`cls_ok = 0`) and is never touched through memory:
  1. every wrapper first tests `cls_ok` of every class its function uses *or reaches through C to C
     Calls* (`_ModGen.uses`), else it takes the interpreted path (a static property of the module, so
     callees need no check of their own);
  2. a `Param.cls` guard (`type(x) is C`, or `x is None` when optional) runs in the wrapper AND at the
     top of the impl, before tp_enter_call and before anything is owned: the wrapper's copy serves
     impure functions (whose impl may never return 1), the impl's copy serves C to C Calls, where a
     failure is a deopt, so a Call of a function with a `Param.cls` parameter counts as a node that
     can deopt (`_can_deopt`), exactly like a callee with entry_globals;
  3. FieldGet / FieldSet / New go through `tp_field_get` / `tp_field_set` / `tp_new_fixed` with the
     captured descriptors; CheckExact deopts (return 1) on mismatch and is legal only in a pure
     function; Is is a plain BOOL.

Classes changed after init (the class, the module global or an instance's __class__). The entry
guards above only select the path; correctness never depends on them, because user code can change
a class between calls or, in an impure function, between entry and the access. Every access
re-proves what it relies on, right where CPython would look:
  4. FieldGet / FieldSet: `tp_class_current(obj, ...)`, `type(obj) is C` and C's tp_version_tag is
     the tag captured at init (or re-proved by `tp_class_refresh`, which adopts a new tag when every
     field is still the very member descriptor and __init__ the very function captured at init:
     `Node.counter = 1` costs one refresh, not a slow path forever). One type compare and one tag
     compare when nothing changed.
  5. New: `tp_class_global_ok` BEFORE the arguments are evaluated (CPython's LOAD_GLOBAL comes first:
     `Node(cb(), b)` builds the class bound when Node was loaded), then `tp_class_current(NULL, ...)`
     after them (type.__call__ sees __init__ as it is at the call). The global test is one compare
     while a dict watcher on the module dict (`tp_globals_watch`, its id in the module state) has not
     reported a change of a class-name key (`tp_globals_epoch`); without a free watcher id it is a
     dict lookup per New (`watch_id1 == 0`, the conservative path).
  6. IsExact: `type(obj) is C` with C looked up as a global (`tp_class_global_ok`, then the current
     binding on the slow path).
  When a check fails: in an impure function, the slow path with CPython's semantics and no deopt
  (FieldGet -> tp_getattr, FieldSet -> tp_setattr, New -> call what the global held when it was
  loaded, IsExact -> compare with the current global); in a pure function, a deopt (return 1),
  running the property or __init__ there could repeat its effect if a later node deopted and the
  call were redone. A pure function may thus return 1 even when `may_deopt` is False, so:
  7. an impure caller of a pure callee that uses classes, in a Call without `redo`, redoes that
     callee interpreted on a 1 (`_redo_call`, any return type), the callee deopted before running
     any user code, so the redo is unobservable; the impure caller itself never deopts;
  8. an impure function with entry globals (closed: it runs no user code, ir.Function.entry_globals)
     checks at entry, in the wrapper, that every class it reaches is current and every class global
     it reads is still bound: then no check inside can fail, so no slow path can run user code that
     rebinds an entry global after its snapshot. A failure there is a deopt before any effect.
  `__typedpython_class_info__()` reports, per class, the captured and current tags, the slow-path
  and refresh counters and whether the module dict is watched (introspection for tests).

Methods (SPEC N-11, issue #155). An `ir.Function` with an `owner` is a method of that compiled class.
It has an impl and a wrapper like any function (its `self` is `Param(OBJ, cls=owner)`, guarded through
`TP_SELF_EXACT`, so a `self` that is not exactly the class runs the original), but the module dict is
not touched. The exec slot, after the class captures and for each class that is compiled
(`cls_ok`): takes the class's OWN dict entry (it must be a plain Python function, else the class is
marked not compiled), stores it in `__typedpython_interpreted__["C.m"]`, creates a `tp_method_obj`
(`_TEMPLATE_METHOD_TYPE`: an immutable heap type, vectorcall, `tp_descr_get` like a function,
`__name__`/`__qualname__`/`__doc__`/`__module__` forwarded, `__wrapped__` the original), replaces the
dict entry with it, and finally `tp_class_retag` adopts the class's new version tag as the proved tag
(the fields and `__init__` were not touched). `ir.CheckMethod` (`ev_CheckMethod`) is how a pure caller
reaches the impl directly: `tp_methods_current` = `type(obj) is C` and (one tag compare against
`cls_rt[k].mtag`, else `tp_methods_refresh`) every installed method still is, in C's own dict, the very
descriptor installed (`tp_st->mdescrs`), and the class shape is unchanged; a failure is a deopt (pure
functions only). Module-state fields `mtype` and `mdescrs[]` are written by the exec slot only.

Generated arithmetic is in separate C statements and the file sets `#pragma STDC FP_CONTRACT OFF`
(cbuild also passes -ffp-contract=off): a fused multiply-add would round differently from CPython.
"""
from __future__ import annotations

import re
from pathlib import Path

from typedpython import ir
from typedpython.ir import Type


class CGenError(Exception):
    """The IR cannot be lowered as specified (a contract violation by the producer of the IR)."""


_CT = {Type.I64: "int64_t", Type.F64: "double", Type.BOOL: "int", Type.OBJ: "PyObject *",
       Type.INT: "tp_int"}
_ARRAY_CT = {Type.F64_ARRAY: "tp_f64_array", Type.I64_ARRAY: "tp_i64_array"}
# Buffer arrays (#149): `ArrayParam.buffer` -> the exact type the guard compares against (the index
# into the module state's `buf_types`), and the format strings the export must carry.
_BUFFER_KINDS = {None: None, "array": 0, "numpy": 1}
# buf_types slots 2..4: the numpy scalar types a Box.np makes (np.float64, np.int64, np.bool_)
_NP_SLOT = {Type.F64: 2, Type.I64: 3, Type.BOOL: 4}
_BUFFER_FMT = {Type.F64_ARRAY: ('"d"', "NULL"), Type.I64_ARRAY: ('"q"', '"l"'), Type.BOOL_ARRAY: ('"?"', "NULL")}
_BUFFER_SFX = {Type.F64_ARRAY: "f64", Type.I64_ARRAY: "i64", Type.BOOL_ARRAY: "bool"}
_CT_ARRAY_REV = {"tp_f64_array": Type.F64_ARRAY, "tp_i64_array": Type.I64_ARRAY}
_ARRAY_ELEM = {Type.F64_ARRAY: Type.F64, Type.I64_ARRAY: Type.I64, Type.BOOL_ARRAY: Type.BOOL}
_ARRAY_PFX = {Type.F64_ARRAY: "tp_f64_array", Type.I64_ARRAY: "tp_i64_array"}
_UNBOX = {Type.I64: "tp_unbox_i64", Type.F64: "tp_unbox_f64", Type.BOOL: "tp_unbox_bool"}
_BOX = {Type.I64: "tp_box_i64", Type.F64: "tp_box_f64", Type.BOOL: "tp_box_bool"}
_PYCMP = {ir.CompareKind.EQ: "Py_EQ", ir.CompareKind.NE: "Py_NE", ir.CompareKind.LT: "Py_LT",
          ir.CompareKind.LE: "Py_LE", ir.CompareKind.GT: "Py_GT", ir.CompareKind.GE: "Py_GE"}
_I64_OP = {ir.BinOpKind.ADD: "tp_add_i64", ir.BinOpKind.SUB: "tp_sub_i64",
           ir.BinOpKind.MUL: "tp_mul_i64", ir.BinOpKind.FLOORDIV: "tp_floordiv_i64",
           ir.BinOpKind.MOD: "tp_mod_i64", ir.BinOpKind.TRUEDIV: "tp_truediv_i64"}
_F64_OP = {ir.BinOpKind.TRUEDIV: "tp_truediv_f64", ir.BinOpKind.FLOORDIV: "tp_floordiv_f64",
           ir.BinOpKind.MOD: "tp_mod_f64"}
_NP_I64_OP = {ir.BinOpKind.TRUEDIV: "tp_np_truediv_i64", ir.BinOpKind.FLOORDIV: "tp_np_floordiv_i64",
              ir.BinOpKind.MOD: "tp_np_mod_i64"}
_NP_F64_CODE = {k: f"TP_NP_{k.name}" for k in (
    ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL, ir.BinOpKind.TRUEDIV,
    ir.BinOpKind.FLOORDIV, ir.BinOpKind.MOD)}
_NP_F64_CODE[ir.BinOpKind.TRUEDIV] = "TP_NP_DIV"
_NP_NAME = {ir.BinOpKind.ADD: "add", ir.BinOpKind.SUB: "subtract", ir.BinOpKind.MUL: "multiply",
            ir.BinOpKind.TRUEDIV: "divide", ir.BinOpKind.FLOORDIV: "floor_divide",
            ir.BinOpKind.MOD: "remainder"}
_F64_INLINE = {ir.BinOpKind.ADD: "+", ir.BinOpKind.SUB: "-", ir.BinOpKind.MUL: "*"}
_OBJ_OP = {k: f"TP_BINOP_{k.name}" for k in ir.BinOpKind}
# SPEC N-13: the INT operations (tp_runtime.h "Native ints"); TRUEDIV of ints is an OBJ operation
_INT_OP = {ir.BinOpKind.ADD: "tp_int_add", ir.BinOpKind.SUB: "tp_int_sub",
           ir.BinOpKind.MUL: "tp_int_mul", ir.BinOpKind.FLOORDIV: "tp_int_floordiv",
           ir.BinOpKind.MOD: "tp_int_mod", ir.BinOpKind.LSHIFT: "tp_int_lshift",
           ir.BinOpKind.RSHIFT: "tp_int_rshift"}
_MATH = {ir.MathFunc.SQRT: 1, ir.MathFunc.EXP: 1, ir.MathFunc.LOG: 1, ir.MathFunc.SIN: 1,
         ir.MathFunc.COS: 1, ir.MathFunc.TAN: 1, ir.MathFunc.FABS: 1, ir.MathFunc.ATAN2: 2,
         ir.MathFunc.HYPOT: 2}
# I64 operations whose helper can return 1 (API.md): MOD never overflows (MIN % -1 == 0).
_I64_DEOPT = {ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL, ir.BinOpKind.FLOORDIV,
              ir.BinOpKind.TRUEDIV}
_INT64_MIN, _INT64_MAX = -(2 ** 63), 2 ** 63 - 1
# `proven` on an I64 op (ir.BinOp.proven): ADD/SUB/MUL cannot overflow; FLOORDIV/MOD by a Const
# power of two of a value >= 0 become a shift / mask.
_SHIFT_OPS = {ir.BinOpKind.FLOORDIV, ir.BinOpKind.MOD}
_PROVEN_OPS = {ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL} | _SHIFT_OPS


def _pow2_shift(divisor: ir.Expr) -> int | None:
    """k when `divisor` is the Const 2**k (k >= 0), else None."""
    v = divisor.value if isinstance(divisor, ir.Const) and divisor.type is Type.I64 else None
    if type(v) is int and v >= 1 and v & (v - 1) == 0:
        return v.bit_length() - 1
    return None


# --- C literals and identifiers ------------------------------------------------------------------

def c_string(data: str | bytes) -> str:
    """A C string literal for `data` (UTF-8). Octal escapes only (hex escapes are greedy), `?`
    escaped against trigraphs; split after each newline so long sources stay readable."""
    raw = data.encode("utf-8") if isinstance(data, str) else data
    out, pieces = [], []
    for b in raw:
        ch = chr(b)
        if ch in '\\"?':
            out.append("\\" + ch)
        elif 0x20 <= b < 0x7F:
            out.append(ch)
        else:
            out.append("\\%03o" % b)
        if b == 0x0A:
            pieces.append('"' + "".join(out) + '"')
            out = []
    if out or not pieces:
        pieces.append('"' + "".join(out) + '"')
    return "\n    ".join(pieces)


def _ident(prefix: str, name: str) -> str:
    """A C identifier for a Python name: ASCII names keep their spelling, others are hex-encoded
    under a distinct prefix so the two forms cannot collide."""
    if name.isascii() and name.isidentifier():
        return f"{prefix}_{name}"
    return f"{prefix}x_{name.encode('utf-8').hex()}"


def _vfield(local: str, k: int) -> str:
    """The name of field `k` of the virtual object held by `local` (not a Python identifier)."""
    return f"{local}\x00v{k}"


def _assigns(body):
    for s in body:
        if isinstance(s, ir.Assign):
            yield s
        for nested in (getattr(s, "then", ()), getattr(s, "orelse", ()), getattr(s, "body", ())):
            yield from _assigns(nested)


def _i64_lit(v: int) -> str:
    if isinstance(v, bool) or not isinstance(v, int) or not (_INT64_MIN <= v <= _INT64_MAX):
        raise CGenError(f"I64 constant out of range or not an int: {v!r}")
    if v == _INT64_MIN:
        return "(-INT64_C(9223372036854775807) - 1)"
    return f"INT64_C({v})"


def _f64_lit(v: float) -> str:
    v = float(v)
    if v != v:
        return "(-Py_NAN)" if _sign_bit(v) else "Py_NAN"
    if v in (float("inf"), float("-inf")):
        return "Py_HUGE_VAL" if v > 0 else "(-Py_HUGE_VAL)"
    return f"({v.hex()})"


def _sign_bit(v: float) -> bool:
    import struct
    return struct.pack(">d", v)[0] >> 7 == 1


# --- IR walks ------------------------------------------------------------------------------------

def _exprs_of_stmt(s: ir.Stmt):
    if isinstance(s, ir.Assign):
        yield s.value
    elif isinstance(s, ir.StoreIndex):
        yield s.value
        yield s.index
    elif isinstance(s, ir.FieldSet):
        yield s.value
        yield s.obj
    elif isinstance(s, ir.StoreItem):
        yield s.value
        yield s.obj
        yield s.index
    elif isinstance(s, ir.ForObj):
        yield s.iter
    elif isinstance(s, ir.ExprStmt):
        yield s.value
    elif isinstance(s, ir.If):
        yield s.cond
    elif isinstance(s, ir.While):
        yield s.cond
    elif isinstance(s, ir.ForRange):
        yield s.start
        yield s.stop
    elif isinstance(s, ir.Return) and s.value is not None:
        yield s.value


def _children(s: ir.Stmt):
    if isinstance(s, ir.If):
        return s.then + s.orelse
    if isinstance(s, (ir.While, ir.ForRange, ir.ForObj)):
        return s.body
    return ()


def _walk_stmts(body):
    for s in body:
        yield s
        yield from _walk_stmts(_children(s))


def _sub_exprs(e: ir.Expr):
    for name in ("left", "right", "operand", "index", "obj", "callee", "length", "fill"):
        v = getattr(e, name, None)
        if isinstance(v, ir.Expr):
            yield v
    for v in getattr(e, "args", ()) or ():
        yield v
    for v in getattr(e, "elements", ()) or ():
        yield v


def _walk_exprs(e: ir.Expr):
    yield e
    for c in _sub_exprs(e):
        yield from _walk_exprs(c)


def _all_exprs(f: ir.Function):
    for s in _walk_stmts(f.body):
        for e in _exprs_of_stmt(s):
            yield from _walk_exprs(e)


def _traced_call(k: int) -> str:
    """tp_traced for the function whose interpreted original is `names[k]` (SPEC N-14). The code
    object is a strong reference in the module state, filled at module exec."""
    return (f"tp_traced(tp_module, tp_ts, (PyCodeObject *)((tp_state *)PyModule_GetState(tp_module))->orig_code[{k}])")


def _can_deopt(e: ir.Expr, functions: dict[str, ir.Function]) -> bool:
    if isinstance(e, ir.BinOp) and e.left.type is Type.I64 and e.op in _I64_DEOPT:
        return not e.proven              # a proven op cannot deopt (no overflow, or a shift / mask)
    if isinstance(e, ir.UnaryOp) and e.op is ir.UnaryOpKind.NEG and e.operand.type is Type.I64:
        return not e.proven
    if isinstance(e, (ir.Unbox, ir.CheckExact, ir.CheckMethod)):
        return True
    if isinstance(e, ir.Call):
        if e.redo:
            return False                 # the callee's deopt is absorbed by its interpreted redo
        callee = functions.get(e.function)
        # a callee's entry-global guard runs at the call (`_FnGen.ev_Call`) and can fail there
        return callee is not None and (callee.may_deopt or bool(callee.entry_globals)
                                       or _has_cls_param(callee))
    return False


def _has_cls_param(f: ir.Function) -> bool:
    """A Param.cls guard runs at every call of `f`, C to C included, and a failure is a deopt."""
    return any(isinstance(p, ir.Param) and p.cls is not None for p in f.params)


def _direct_classes(f: ir.Function) -> set[str]:
    out = {p.cls for p in f.params if isinstance(p, ir.Param) and p.cls is not None}
    for e in _all_exprs(f):
        if isinstance(e, (ir.IsExact, ir.CheckExact, ir.CheckMethod, ir.FieldGet, ir.New)):
            out.add(e.cls)
    for s in _walk_stmts(f.body):
        if isinstance(s, ir.FieldSet):
            out.add(s.cls)
    return out


def _param_cls_fail(obj_c: str, k: int, optional: bool, method_self: bool = False) -> str:
    """The C condition under which a `Param.cls` guard FAILS for the borrowed argument `obj_c`
    (class slot `k` of the module state): not exactly the class, and not None when optional.
    Emitted both in the wrapper and at the top of the impl (module docstring). `method_self`: the
    `self` of a compiled method, guarded through the single mutation point TP_SELF_EXACT."""
    test = "TP_SELF_EXACT" if method_self else "tp_is_exact"
    exact = f"{test}({obj_c}, (PyTypeObject *)tp_st->classes[{k}])"
    return f"!({exact} || {obj_c} == Py_None)" if optional else f"!{exact}"


def _class_current_expr(obj_c: str, k: int, fields_c: str, n: int, descrs_c: str) -> str:
    """The per-access check of FieldGet / FieldSet (`obj_c` a C object) and New (`obj_c` "NULL"):
    1 current, 0 slow path, -1 error (module docstring, rules 4 and 5)."""
    return (f"tp_class_current({obj_c}, tp_st->classes[{k}], &tp_st->cls_rt[{k}], "
            f"{fields_c}, {n}, {descrs_c})")


def _watch_event_stmt() -> str:
    """The body of the module's dict-watcher callback (module docstring, rule 5)."""
    return "return tp_globals_watch_event(event, key, tp_watch_names);"


def _is_effect(e: ir.Expr) -> bool:
    if isinstance(e, (ir.GetAttr, ir.CallObject, ir.Truth, ir.CompareObj, ir.ObjToFloat,
                      ir.GetItem, ir.Contains, ir.Format)):
        return True
    return isinstance(e, ir.BinOp) and e.type is Type.OBJ



# --- bounded functions (fast entry, issue #147) ----------------------------------------------------

# Nodes that can run Python code (and so create Python frames the compiled frame count must see):
# their slow paths call getattr, the class, __bool__, __float__, a Python callable, a descriptor.
_RUNS_PYTHON = (ir.CallObject, ir.GetAttr, ir.Truth, ir.CompareObj, ir.ObjToFloat, ir.FieldGet,
                ir.FieldSet, ir.New, ir.GetItem, ir.Contains, ir.Format, ir.StoreItem, ir.ForObj)


def _runs_no_python(f: ir.Function) -> bool:
    """Conservative: False when `f` has any node that can run Python code, or touches an object
    other than a fresh exact int, float or bool made by Box of a scalar (an OBJ parameter, local,
    result or any other OBJ expression: releasing one can run a finalizer). A `redo` Call is
    allowed (its callee is bounded, checked by the caller): the redo is one of the two points where
    a fast tree runs Python code, and `_redo_call` counts the tree's frames around it."""
    if f.returns is Type.OBJ or f.entry_globals and any(g.type is Type.OBJ for g in f.entry_globals):
        return False
    if any(isinstance(p, ir.Param) and p.type is Type.OBJ for p in f.params):
        return False
    if any(t in (Type.OBJ, Type.INT) for t in f.locals.values()):
        return False
    for s in _walk_stmts(f.body):
        if isinstance(s, (ir.FieldSet, ir.StoreItem, ir.ForObj)):
            return False
    for e in _all_exprs(f):
        if isinstance(e, _RUNS_PYTHON):
            return False
        if e.type is Type.INT or (e.type is Type.OBJ and not (isinstance(e, ir.Box)
                                                              and e.operand.type in ir.SCALARS)):
            return False
    return True


def _bounded_functions(functions: dict[str, ir.Function]) -> dict[str, int]:
    """name -> k for every bounded function (module docstring, "Fast entry"): in no call cycle,
    every callee bounded, `_runs_no_python`. k(f) = 1 + max(k(callee)), 1 without callees: the most
    compiled frames a call of `f` can have in flight at once."""
    callees: dict[str, set[str]] = {}
    for name, f in functions.items():
        callees[name] = {e.function for e in _all_exprs(f) if isinstance(e, ir.Call)}
    k: dict[str, int | None] = {}                 # None: not bounded (also while on the DFS stack)

    def visit(name: str) -> int | None:
        if name in k:
            return k[name]
        k[name] = None                            # on the stack: reaching it again is a cycle
        f = functions[name]
        if not _runs_no_python(f):
            return None
        deepest = 0
        for c in callees[name]:
            if c not in functions:
                return None
            kc = visit(c)
            if kc is None:
                return None
            deepest = max(deepest, kc)
        k[name] = deepest + 1
        return k[name]

    for name in functions:
        visit(name)
    return {n: v for n, v in k.items() if v is not None}


# --- module --------------------------------------------------------------------------------------

class _ModGen:
    def __init__(self, module: ir.Module):
        self.module = module
        self.display_path = ""
        self.functions = {f.name: f for f in module.functions}
        # SPEC N-15: any ForRange.par in the module (the module then exports __typedpython_parallel__)
        self.has_par = any(isinstance(st, ir.ForRange) and st.par is not None
                           for f in module.functions for st in _walk_stmts(f.body))
        if len(self.functions) != len(module.functions):
            raise CGenError("duplicate compiled function names")
        self.names: list[str] = []
        self.kwtuples: list[tuple[str, ...]] = []
        self.classes = {cd.name: cd for cd in module.classes}
        if len(self.classes) != len(module.classes):
            raise CGenError("duplicate ClassDecl names")
        self.class_index = {cd.name: i for i, cd in enumerate(module.classes)}
        self.slot_base: dict[str, int] = {}
        total = 0
        for cd in module.classes:
            if len(set(cd.fields)) != len(cd.fields):
                raise CGenError(f"class {cd.name}: duplicate field names")
            self.slot_base[cd.name] = total
            total += len(cd.fields)
        self.n_slots = total
        # Methods (module docstring, "Methods"): compiled functions with an `owner`. They are installed
        # in their class's dict, not in the module dict, and have no PyMethodDef.
        self.plain = [f for f in module.functions if f.owner is None]
        self.methods_of: dict[str, list[ir.Function]] = {cd.name: [] for cd in module.classes}
        for f in module.functions:
            if f.owner is not None:
                if f.owner not in self.classes:
                    raise CGenError(f"{f.name}: owner {f.owner!r} has no ClassDecl in the module")
                if f.name != f"{f.owner}.{f.name[len(f.owner) + 1:]}" or not f.name[len(f.owner) + 1:].isidentifier():
                    raise CGenError(f"{f.name}: a method of {f.owner} is named {f.owner}.<identifier>")
                p0 = f.params[0] if f.params else None
                if not (isinstance(p0, ir.Param) and p0.type is Type.OBJ and p0.cls == f.owner
                        and not p0.optional):
                    raise CGenError(f"{f.name}: the first parameter of a method must be self guarded {f.owner}")
                self.methods_of[f.owner].append(f)
        self.method_base: dict[str, int] = {}
        total_methods = 0
        for cd in module.classes:
            self.method_base[cd.name] = total_methods
            total_methods += len(self.methods_of[cd.name])
        self.n_methods = total_methods
        self.int_consts: list[int] = []          # module-state boxed int constants (SPEC N-13)
        self.gnames: list[str] = []              # cached global/builtin reads, one tp_gslot each
        self._uses: dict[str, set[str]] = {}
        self.bounded = _bounded_functions(self.functions)     # name -> k (module docstring, "Fast entry")

    def cls(self, fname: str, name: str) -> ir.ClassDecl:
        cd = self.classes.get(name)
        if cd is None:
            raise CGenError(f"{fname}: class {name!r} has no ClassDecl in the module")
        return cd

    def slot(self, fname: str, cls: str, field: str) -> str:
        cd = self.cls(fname, cls)
        if field not in cd.fields:
            raise CGenError(f"{fname}: class {cls} has no field {field!r}")
        return f"tp_st->slots[{self.slot_base[cls] + cd.fields.index(field)}]"

    def uses(self, f: ir.Function) -> list[str]:
        """Every class `f` uses or reaches through C to C Calls (module docstring, rule 1)."""
        if not self._uses:
            for g in self.module.functions:
                self._uses[g.name] = _direct_classes(g)
            changed = True
            while changed:
                changed = False
                for g in self.module.functions:
                    for e in _all_exprs(g):
                        if isinstance(e, ir.Call) and e.function in self._uses:
                            extra = self._uses[e.function] - self._uses[g.name]
                            if extra:
                                self._uses[g.name] |= extra
                                changed = True
        return sorted(self._uses[f.name], key=lambda n: self.class_index.get(n, -1))

    def fields_c(self, cls: str) -> tuple[str, int, str]:
        """(C fields array, field count, C descriptor-array pointer) of class `cls`."""
        cd = self.classes[cls]
        k, base = self.class_index[cls], self.slot_base[cls]
        return f"tp_fields_{k}", len(cd.fields), f"&tp_st->descrs[{base}]"

    def globals_read(self, f: ir.Function) -> list[str]:
        """Classes whose module global `f` reads (New, IsExact) itself or through C to C Calls."""
        out: set[str] = set()
        seen: set[str] = set()
        todo = [f.name]
        while todo:
            g = self.functions[todo.pop()]
            if g.name in seen:
                continue
            seen.add(g.name)
            for e in _all_exprs(g):
                if isinstance(e, (ir.New, ir.IsExact)):
                    out.add(e.cls)
                if isinstance(e, ir.Call) and e.function in self.functions:
                    todo.append(e.function)
        return sorted(out, key=lambda n: self.class_index.get(n, -1))

    def method(self, fname: str, cls: str, method: str) -> ir.Function:
        f = self.functions.get(f"{cls}.{method}")
        if f is None or f.owner != cls:
            raise CGenError(f"{fname}: CheckMethod names {cls}.{method}, which is not a compiled method")
        return f

    def methods_c(self, cls: str) -> tuple[str, int, str]:
        """(C array of the installed method names, their count, C descriptor-array pointer) of `cls`."""
        k = self.class_index[cls]
        return f"tp_mnames_{k}", len(self.methods_of[cls]), f"&tp_st->mdescrs[{self.method_base[cls]}]"

    def watches_globals(self) -> bool:
        return any(isinstance(e, (ir.New, ir.IsExact))
                   for f in self.module.functions for e in _all_exprs(f))

    def needs_watch(self) -> bool:
        """A dict watcher on the module dict (and the builtins dict): class guards or cached reads."""
        return self.watches_globals() or bool(self.gnames)

    def int_index(self, v: int) -> int:
        if v not in self.int_consts:
            self.int_consts.append(v)
        return self.int_consts.index(v)

    def gslot_index(self, name: str) -> int:
        if name not in self.gnames:
            self.gnames.append(name)
        return self.gnames.index(name)

    def name_index(self, s: str) -> int:
        if s not in self.names:
            self.names.append(s)
        return self.names.index(s)

    def kw_index(self, kw: tuple[str, ...]) -> int:
        for k in kw:
            self.name_index(k)
        if kw not in self.kwtuples:
            self.kwtuples.append(kw)
        return self.kwtuples.index(kw)


def generate(module: ir.Module, source_path: Path, display_path: str | None = None) -> str:
    """One C file implementing `module`; `source_path` is the original Python source, embedded.

    `display_path` is the path embedded as the code object's filename (tracebacks of the interpreted
    fallback): the module's path relative to the project root, posix separators (#112). It never
    defaults to the absolute host path, so the C does not depend on where the project was built;
    left out, it is the file name.
    """
    source_path = Path(source_path)
    display_path = source_path.name if display_path is None else display_path
    source = source_path.read_text(encoding="utf-8")
    mg = _ModGen(module)
    mg.display_path = display_path       # the file name of a compiled numpy RuntimeWarning (#220)
    for f in module.functions:
        _check_function(f, mg.functions)
    for f in module.functions:
        mg.name_index(f.name)
    bodies = [_FnGen(mg, f).generate() for f in module.functions]
    init_name = module.name.rpartition(".")[2]
    if not (init_name.isascii() and init_name.isidentifier()):
        raise CGenError(f"module name {module.name!r} cannot name a PyInit_ function")

    out: list[str] = []
    w = out.append
    w(f"/* Generated by typedpython.cgen from {source_path.name}, do not edit. */")
    w("#define PY_SSIZE_T_CLEAN")
    w("#include <Python.h>")
    w("#include <stddef.h>")
    w("#include <stdint.h>")
    w('#include "tp_runtime.h"')
    w("")
    w("#if PY_VERSION_HEX < 0x030D0000")
    w('#error "typedpython extensions need CPython 3.13 or newer (PyDict_GetItemRef)"')
    w("#endif")
    w("#pragma STDC FP_CONTRACT OFF")
    w("")
    w(f"static const char tp_source[] =\n    {c_string(source)};")
    w(f"static const char tp_source_path[] = {c_string(display_path)};")
    w("")
    n_names, n_kw = len(mg.names), len(mg.kwtuples)
    w("/* Module state: interned names (globals, attributes, keywords, function names) and the")
    w("   kwnames tuples of keyword calls. Owned by the module; released in tp_clear. */")
    w("typedef struct {")
    w(f"    PyObject *names[{n_names + 1}];")
    w(f"    PyObject *orig_code[{n_names + 1}];   /* strong: code of __typedpython_interpreted__[names[k]] (SPEC N-14) */")
    w(f"    PyObject *kwnames[{n_kw + 1}];")
    w(f"    PyObject *classes[{len(module.classes) + 1}];      /* strong refs; NULL when not compiled */")
    w(f"    int cls_ok[{len(module.classes) + 1}];")
    w(f"    PyMemberDef *slots[{mg.n_slots + 1}];     /* owned by the classes above */")
    w(f"    PyObject *descrs[{mg.n_slots + 1}];       /* strong: the member descriptor of each slot */")
    w(f"    tp_class_rt cls_rt[{len(module.classes) + 1}];  /* version-tag / global guard state */")
    w("    int watch_id1;                  /* dict watcher id + 1 on the module dict; 0 = none */")
    w("    PyObject *breaker;              /* `lambda: None`; calling it runs the eval breaker (#141) */")
    w(f"    PyObject *ints[{len(mg.int_consts) + 1}];             /* strong: the int constants boxed once (SPEC N-13) */")
    w(f"    tp_gslot gc[{len(mg.gnames) + 1}];             /* cached global/builtin reads (SPEC N-13) */")
    w("    PyObject *builtins;             /* strong: the builtins dict the module source ran with; NULL: none */")
    w("    PyObject *buf_types[5];         /* strong: array.array, numpy.ndarray, np.float64, np.int64, np.bool_ (buffer arrays #149, #220); NULL: not importable */")
    w("    PyObject *mtype;                /* strong: the descriptor type of compiled methods; NULL: none */")
    w(f"    PyObject *mdescrs[{mg.n_methods + 1}];        /* strong: the installed descriptor of each method */")
    w("} tp_state;")
    w("")
    w("static const char *const tp_name_strings[] = {")
    for s in mg.names:
        w(f"    {c_string(s)},")
    w("    NULL")
    w("};")
    w("")
    for cd in module.classes:
        items = "".join(f"{c_string(x)}, " for x in cd.fields)
        w(f"static const char *const tp_fields_{mg.class_index[cd.name]}[] = {{{items}NULL}};")
    for cd in module.classes:
        items = "".join(f"{c_string(f.name[len(cd.name) + 1:])}, " for f in mg.methods_of[cd.name])
        w(f"static const char *const tp_mnames_{mg.class_index[cd.name]}[] = {{{items}NULL}};")
    names = "".join(f"{c_string(cd.name)}, " for cd in module.classes)
    w(f"static const char *const tp_class_names[] = {{{names}NULL}};")
    watch = [cd.name for cd in module.classes] + list(mg.gnames) + (["__builtins__"] if mg.gnames else [])
    w("static const char *const tp_watch_names[] = {" + "".join(f"{c_string(x)}, " for x in dict.fromkeys(watch)) + "NULL};")
    w("")
    if mg.n_methods:
        w(_TEMPLATE_METHOD_TYPE)
    if mg.needs_watch():
        w("/* Dict watcher of the module dict and its builtins dict (tp_globals_watch): a class name or a cached")
        w("   global changed. */")
        w("static int tp_watch_cb(PyDict_WatchEvent event, PyObject *dict, PyObject *key, PyObject *value)")
        w("{")
        w("    (void)dict; (void)value;")
        w(f"    {_watch_event_stmt()}")
        w("}")
        w("")
    for f in module.functions:
        w(_impl_signature(f) + ";")
        if f.name in mg.bounded:
            w(_impl_signature(f, fast=True) + ";")
    w("")
    w(_TEMPLATE_REDO)
    for b in bodies:
        w(b)
    # method table
    w("static PyMethodDef tp_methods[] = {")
    for f in mg.plain:
        w(f"    {{{c_string(f.name)}, (PyCFunction)(void (*)(void)){_ident('tp_wrap', f.name)}, "
          f"METH_FASTCALL | METH_KEYWORDS, NULL}},")
    w("#ifdef TP_TRACE_FAST")
    w('    {"__tp_trace_fast_hits__", (PyCFunction)tp_trace_fast_hits, METH_NOARGS, NULL},')
    w("#endif")
    w("    {NULL, NULL, 0, NULL}")
    w("};")
    w("")
    if module.classes:
        w(_class_info_function(mg))
    if mg.has_par:
        w(_par_stats_function())
    w(_exec_function(mg))
    w(_state_functions(mg))
    w("static PyModuleDef_Slot tp_slots[] = {")
    w("    {Py_mod_exec, (void *)tp_exec},")
    w("#ifdef Py_GIL_DISABLED")
    w("    {Py_mod_gil, Py_MOD_GIL_NOT_USED},  /* free-threaded safe: runtime/API.md \"Free-threaded CPython\" */")
    w("#endif")
    w("    {0, NULL}")
    w("};")
    w("")
    w("static struct PyModuleDef tp_moduledef = {")
    w(f"    PyModuleDef_HEAD_INIT, {c_string(module.name)}, NULL, sizeof(tp_state), NULL, tp_slots,")
    w("    tp_traverse, tp_clear, tp_free")
    w("};")
    w("")
    w(f"PyMODINIT_FUNC PyInit_{init_name}(void) {{ return PyModuleDef_Init(&tp_moduledef); }}")
    return "\n".join(out) + "\n"


def _check_function(f: ir.Function, functions: dict[str, ir.Function]) -> None:
    deopting = [e for e in _all_exprs(f) if _can_deopt(e, functions)]
    if deopting and not f.pure:
        raise CGenError(f"{f.name}: a node that can deopt ({type(deopting[0]).__name__}) in an "
                        "impure function (ir.py: only a pure function may deopt after entry)")
    if deopting and not f.may_deopt:
        raise CGenError(f"{f.name}: may_deopt is False but a node can deopt "
                        f"({type(deopting[0]).__name__})")
    effects = [e for e in _all_exprs(f) if _is_effect(e)]
    # a store into a local array is not an effect (ir.py "Local arrays"); into a parameter it is
    stores = [s for s in _walk_stmts(f.body)
              if (isinstance(s, ir.StoreIndex) and f.locals.get(s.array) not in ir.ARRAYS)
              or isinstance(s, (ir.FieldSet, ir.StoreItem, ir.ForObj))]
    if f.pure and (effects or stores):
        what = type(effects[0]).__name__ if effects else type(stores[0]).__name__
        raise CGenError(f"{f.name}: marked pure but contains an effect ({what})")
    for e in _all_exprs(f):
        if isinstance(e, (ir.BinOp, ir.UnaryOp)) and e.proven:
            checked = (e.op in _PROVEN_OPS if isinstance(e, ir.BinOp)
                       else e.op is ir.UnaryOpKind.NEG)
            operand = e.left if isinstance(e, ir.BinOp) else e.operand
            if not checked or operand.type is not Type.I64:
                raise CGenError(f"{f.name}: proven is set on {e.op.name} of {operand.type} "
                                "(ir.py: I64 ADD/SUB/MUL, FLOORDIV/MOD by a power of two, and NEG "
                                "only)")
            if isinstance(e, ir.BinOp) and e.op in _SHIFT_OPS and _pow2_shift(e.right) is None:
                raise CGenError(f"{f.name}: proven {e.op.name} needs a Const power-of-two divisor "
                                f"(ir.py), got {e.right!r}")
        if isinstance(e, ir.Call):
            callee = functions.get(e.function)
            if callee is None:
                raise CGenError(f"{f.name}: Call of {e.function!r}, which is not compiled here")
            if any(isinstance(p, ir.ArrayParam) for p in callee.params):
                raise CGenError(f"{f.name}: Call of {e.function!r}, which takes array params")
            if e.redo:
                if callee.returns not in (Type.F64, Type.BOOL, Type.NONE) and not (
                        callee.returns is Type.I64 and e.type is Type.INT):
                    raise CGenError(f"{f.name}: redo Call of {e.function!r}, which returns "
                                    f"{callee.returns} (ir.py: F64, BOOL or NONE, or I64 as an INT)")
                if not callee.pure:
                    raise CGenError(f"{f.name}: redo Call of impure {e.function!r} "
                                    "(redoing it could repeat an effect)")
            elif (callee.may_deopt or callee.entry_globals or _has_cls_param(callee)) and not f.pure:
                raise CGenError(f"{f.name}: Call of deopting {e.function!r} from an impure caller "
                                "without redo")
    if f.entry_globals:
        _check_entry_globals(f, functions)
    n_arrays = sum(isinstance(p, ir.ArrayParam) and p.buffer is None for p in f.params)
    if n_arrays > 2:
        raise CGenError(f"{f.name}: {n_arrays} array parameters; at most two (CPython offers one- and "
                        "two-object critical sections only, and a third lock could suspend the others)")
    for p in f.params:
        if isinstance(p, ir.ArrayParam):
            if p.type not in ir.ARRAYS:
                raise CGenError(f"{f.name}: array param {p.name} has type {p.type}")
            if p.buffer not in _BUFFER_KINDS:
                raise CGenError(f"{f.name}: array param {p.name} has buffer kind {p.buffer!r}")
            if p.stored and f.pure:
                raise CGenError(f"{f.name}: array param {p.name} is stored into in a pure function")
        elif p.type not in _CT or p.type is Type.INT:
            raise CGenError(f"{f.name}: param {p.name} has type {p.type}")
        elif p.cls is not None and p.type is not Type.OBJ:
            raise CGenError(f"{f.name}: param {p.name} has a class guard but type {p.type}")
        elif p.optional and p.cls is None:
            raise CGenError(f"{f.name}: param {p.name} is optional without a class")


def _is_closed(f: ir.Function, functions: dict[str, ir.Function], seen=None) -> bool:
    """ir.Function.entry_globals: runs no user code, no effect node, Calls only to closed
    functions, so nothing can rebind a module global while it runs."""
    seen = set() if seen is None else seen
    if f.name in seen:
        return True
    seen.add(f.name)
    if any(isinstance(s, (ir.StoreItem, ir.ForObj)) for s in _walk_stmts(f.body)):
        return False
    for e in _all_exprs(f):
        if _is_effect(e):
            return False
        if isinstance(e, ir.Call):
            callee = functions.get(e.function)
            if callee is None or not _is_closed(callee, functions, seen):
                return False
    return True


def _check_entry_globals(f: ir.Function, functions: dict[str, ir.Function]) -> None:
    names = [g.name for g in f.entry_globals]
    if len(set(names)) != len(names):
        raise CGenError(f"{f.name}: entry_globals lists a name twice")
    taken = {p.name for p in f.params} | set(f.locals)
    for g in f.entry_globals:
        if not isinstance(g, ir.Param) or g.type not in ir.SCALARS:
            raise CGenError(f"{f.name}: entry global {g.name} must be a scalar Param, not {g.type}")
        if g.name in taken:
            raise CGenError(f"{f.name}: entry global {g.name} is also a parameter or local")
    for s in _walk_stmts(f.body):
        if isinstance(s, ir.Assign) and s.target in names:
            raise CGenError(f"{f.name}: assignment to entry global {s.target}")
        if isinstance(s, ir.ForRange) and s.var in names:
            raise CGenError(f"{f.name}: entry global {s.var} used as a range variable")
    if not _is_closed(f, functions):
        raise CGenError(f"{f.name}: entry_globals in a function that is not closed (it can run "
                        "user code, which could rebind the global between entry and use)")


def _impl_signature(f: ir.Function, fast: bool = False) -> str:
    params = ["PyObject *tp_module"]
    if fast:
        params.append("int tp_fd")              # compiled frames of this fast tree, itself included
    else:
        params.append("PyThreadState *tp_ts")   # the caller's one thread-state lookup (#125)
    params.append("tp_tls *tp_t")               # its one TLS-address lookup (#125)
    for p in f.params:
        if isinstance(p, ir.ArrayParam):
            params.append(f"{'tp_buf' if p.buffer else _ARRAY_CT[p.type]} *{_ident('l', p.name)}")
        elif p.type is Type.OBJ:
            params.append(f"PyObject *{_ident('p', p.name)}")
        else:
            params.append(f"{_CT[p.type]} {_ident('l', p.name)}")
    for g in f.entry_globals:                  # read and unboxed by the caller (wrapper or Call)
        params.append(f"{_CT[g.type]} {_ident('l', g.name)}")
    if f.returns is not Type.NONE:
        ct = _CT[f.returns]
        params.append(f"{ct}{'' if ct.endswith('*') else ' '}*tp_out")
    if fast:
        return f"static inline int {_ident('tp_fimpl', f.name)}({', '.join(params)})"
    return f"static int {_ident('tp_impl', f.name)}({', '.join(params)})"


def _impl_call_args(f: ir.Function, fd: str | None = None) -> str:
    """The arguments that forward an impl's own parameters to another impl of the same signature
    (`fd`: the value of a fast twin's `tp_fd`)."""
    args = ["tp_module"] + ([fd] if fd is not None else []) + ["tp_t"]
    for p in f.params:
        args.append(_ident("l" if isinstance(p, ir.ArrayParam) or p.type is not Type.OBJ else "p", p.name))
    args += [_ident("l", g.name) for g in f.entry_globals]
    if f.returns is not Type.NONE:
        args.append("tp_out")
    return ", ".join(args)


# --- function bodies -----------------------------------------------------------------------------

class _FnGen:
    def __init__(self, mg: _ModGen, f: ir.Function, fast: bool = False):
        self.mg, self.f = mg, f
        self.fast = fast                            # the uncounted twin of a bounded function
        self.lines: list[str] = []
        self.depth = 1
        self.temps: list[tuple[str, str]] = []      # (C type, name)
        self.arrays = {p.name: p for p in f.params if isinstance(p, ir.ArrayParam)}
        self.par_defs: list[str] = []               # outlined region bodies (SPEC N-15), emitted before the impl
        self.local_arrays: dict[str, Type] = {}       # name -> F64_ARRAY / I64_ARRAY
        self.brk: list = []                    # per open loop: goto label for `break`, or None
        self.label_n = 0
        self.used_labels: set = set()
        self.types: dict[str, Type] = {}
        for p in f.params:
            if not isinstance(p, ir.ArrayParam):
                self.types[p.name] = p.type
        for g in f.entry_globals:              # the body reads each as Local(name)
            self.types[g.name] = g.type
        for name, t in f.locals.items():
            if name in self.arrays or (name in self.types and self.types[name] is not t):
                raise CGenError(f"{f.name}: local {name} conflicts with a parameter")
            if t in ir.ARRAYS:
                if name in self.types:
                    raise CGenError(f"{f.name}: local array {name} conflicts with a parameter")
                self.local_arrays[name] = t
                continue
            if t not in _CT:
                raise CGenError(f"{f.name}: local {name} has type {t}")
            self.types[name] = t
        # SPEC N-12a: a virtual object lives as one OBJ C local per field (`_vfield`), no object
        self.virtual: dict[str, str] = {}
        for x in f.virtual:
            classes = {e.value.cls for e in _assigns(f.body) if e.target == x
                       and isinstance(e.value, ir.New)}
            if len(classes) != 1 or self.types.get(x) is not Type.OBJ:
                raise CGenError(f"{f.name}: virtual local {x} is not assigned one New class")
            self.virtual[x] = next(iter(classes))
            for k in range(len(mg.cls(f.name, self.virtual[x]).fields)):
                self.types[_vfield(x, k)] = Type.OBJ

    # emit primitives

    def emit(self, line: str) -> None:
        self.lines.append("    " * self.depth + line)

    def tmp(self, ctype: str) -> str:
        name = f"tp_t{len(self.temps)}"
        self.temps.append((ctype, name))
        return name

    def check(self, call: str) -> None:
        """A helper with the 0 / 1 deopt / -1 error convention."""
        self.emit(f"tp_s = {call};")
        self.emit("if (tp_s != 0) { tp_rc = tp_s; goto tp_exit; }")

    def fail(self) -> None:
        self.emit("{ tp_rc = -1; goto tp_exit; }")

    # ownership helpers, the only places that create or drop references (see module docstring)

    def _own_new(self, call: str) -> str:
        """A fresh temporary owning the new reference `call` returns (NULL -> error exit)."""
        t = self.tmp("PyObject *")
        self.emit(f"{t} = {call};")
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def _own_copy(self, local: str) -> str:
        """A fresh temporary owning a new reference to what an OBJ local holds."""
        t = self.tmp("PyObject *")
        self.emit(f"{t} = Py_NewRef({local});")
        return t

    def _own_move(self, dst: str, t: str) -> None:
        """Move temporary `t` into local `dst`, releasing the reference `dst` held."""
        self.emit(f"{{ PyObject *tp_old = {dst}; {dst} = {t}; {t} = NULL; tp_release(&tp_old); }}")

    def _own_move_out(self, t: str) -> None:
        """Move temporary `t` into the caller's `*tp_out` (Return of OBJ)."""
        self.emit(f"*tp_out = {t}; {t} = NULL;")

    def _own_release(self, *ts: str) -> None:
        for t in ts:
            self.emit(f"tp_release(&{t});")

    def _own_release_all(self) -> list[str]:
        objs = [_ident("l", n) for n, t in self.types.items() if t is Type.OBJ]
        objs += [n for ct, n in self.temps if ct == "PyObject *"]
        out = [f"    tp_release(&{n});" for n in objs]
        ints = [_ident("l", n) for n, t in self.types.items() if t is Type.INT]
        ints += [n for ct, n in self.temps if ct == "tp_int"]
        out += [f"    tp_int_release(&{n});" for n in ints]
        # local arrays and array temporaries: freed on every exit (ok, error, deopt); idempotent
        arrs = [(_ident("l", n), t) for n, t in self.local_arrays.items()]
        arrs += [(n, _CT_ARRAY_REV[ct]) for ct, n in self.temps if ct in _CT_ARRAY_REV]
        out += [f"    {_ARRAY_PFX[t]}_free(&{n});" for n, t in arrs]
        return out

    # expressions: return a C expression naming the value (a literal, a scalar local, or a temp);
    # OBJ results are always owned temporaries.

    def ev(self, e: ir.Expr) -> str:
        m = getattr(self, "ev_" + type(e).__name__, None)
        if m is None:
            raise CGenError(f"{self.f.name}: no lowering for {type(e).__name__}")
        return m(e)

    def ev_Const(self, e: ir.Const) -> str:
        if e.type is Type.BOOL:
            return "1" if e.value else "0"
        if e.type is Type.I64:
            return _i64_lit(e.value)
        if e.type is Type.F64:
            return _f64_lit(e.value)
        if e.type is Type.OBJ:
            if e.value is None:
                return self._own_copy("Py_None")
            if isinstance(e.value, bool):
                return self._own_copy("Py_True" if e.value else "Py_False")
            if type(e.value) is str and "\0" not in e.value:
                # a str constant: the interned object the module state holds (names table)
                return self._own_copy(f"tp_st->names[{self.mg.name_index(e.value)}]")
            raise CGenError(f"{self.f.name}: OBJ constant {e.value!r} (use Box of a scalar Const)")
        raise CGenError(f"{self.f.name}: constant of type {e.type}")

    def ev_Local(self, e: ir.Local) -> str:
        t = self.types.get(e.name)
        if t is None:
            raise CGenError(f"{self.f.name}: unknown local {e.name}")
        if t is not e.type:
            raise CGenError(f"{self.f.name}: local {e.name} is {t}, read as {e.type}")
        if t is Type.OBJ:
            return self._own_copy(_ident("l", e.name))
        if t is Type.INT:                 # an owned copy, like an OBJ local (the native case is two loads)
            c = self.tmp("tp_int")
            self.emit(f"tp_int_copy(&{c}, &{_ident('l', e.name)});")
            return c
        return _ident("l", e.name)

    def ev_BinOp(self, e: ir.BinOp) -> str:
        lt, rt = e.left.type, e.right.type
        if lt is not rt:
            raise CGenError(f"{self.f.name}: BinOp operands {lt} and {rt}")
        a, b = self.ev(e.left), self.ev(e.right)
        if lt is Type.INT:
            if e.op not in _INT_OP or e.proven or e.type is not Type.INT:
                raise CGenError(f"{self.f.name}: BinOp {e.op.name} on INT operands")
            t = self.tmp("tp_int")
            self.emit(f"tp_s = {_INT_OP[e.op]}(&{a}, &{b}, &{t});")
            self.emit(f"tp_int_release(&{a}); tp_int_release(&{b});")
            self.emit("if (tp_s != 0) { tp_rc = -1; goto tp_exit; }")
            return t
        if lt in (Type.I64, Type.F64) and e.op in (ir.BinOpKind.LSHIFT, ir.BinOpKind.RSHIFT):
            raise CGenError(f"{self.f.name}: {e.op.value} on {lt} operands (only INT and OBJ shift)")
        if lt is Type.I64:
            t = self.tmp("double" if e.op is ir.BinOpKind.TRUEDIV else "int64_t")
            if e.proven and e.op in _SHIFT_OPS:
                # a >= 0 and the divisor is 2**k (verifier): Python's // and % equal >> and &
                k = _pow2_shift(e.right)
                if e.op is ir.BinOpKind.FLOORDIV:
                    self.emit(f"{t} = {a} >> {k};")
                else:
                    self.emit(f"{t} = {a} & {_i64_lit((1 << k) - 1)};")
            elif e.proven:
                self.emit(f"tp_proven({e.op.name.lower()}, {a}, {b}, {t});")
            elif e.np and e.op in _NP_I64_OP:
                # #220: numpy's int64 / // % (division by zero, INT64_MIN // -1): a deopt, the
                # interpreter then warns (the verifier allows it in a pure function only)
                self.check(f"{_NP_I64_OP[e.op]}({a}, {b}, &{t})")
            else:
                self.check(f"{_I64_OP[e.op]}({a}, {b}, &{t})")
            return t
        if lt is Type.F64 and e.np:
            t = self.tmp("double")
            self.emit(f"tp_s = tp_np_f64({_NP_F64_CODE[e.op]}, {a}, {b}, &{t});")
            if self.f.pure:
                # #220: numpy would warn: the interpreter redoes the call and emits it
                self.emit("if (tp_s != 0) { tp_rc = 1; goto tp_exit; }")
            else:
                fi = self.mg.name_index(self.mg.display_path)
                self.emit(f"if (tp_s != 0 && tp_np_warn(tp_module, tp_s, \"{_NP_NAME[e.op]}\", "
                          f"tp_st->names[{fi}], {e.line}) != 0) {{ tp_rc = -1; goto tp_exit; }}")
            return t
        if lt is Type.F64:
            t = self.tmp("double")
            if e.op in _F64_INLINE:
                self.emit(f"{t} = {a} {_F64_INLINE[e.op]} {b};")
            else:
                self.check(f"{_F64_OP[e.op]}({a}, {b}, &{t})")
            return t
        if lt is Type.OBJ:
            t = self.tmp("PyObject *")
            op = _OBJ_OP[e.op] if not e.inplace else f"TP_BINOP_I{e.op.name}"
            self.emit(f"{t} = tp_binop_obj({a}, {b}, {op});")
            self._own_release(a, b)
            self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
            return t
        raise CGenError(f"{self.f.name}: BinOp on {lt}")

    def ev_UnaryOp(self, e: ir.UnaryOp) -> str:
        ot = e.operand.type
        v = self.ev(e.operand)
        if ot is Type.INT and e.op in (ir.UnaryOpKind.NEG, ir.UnaryOpKind.POS):
            if e.proven or e.type is not Type.INT:
                raise CGenError(f"{self.f.name}: {e.op} of INT")
            if e.op is ir.UnaryOpKind.POS:
                return v                    # the operand's temporary is the result
            t = self.tmp("tp_int")
            self.emit(f"tp_s = tp_int_neg(&{v}, &{t});")
            self.emit(f"tp_int_release(&{v});")
            self.emit("if (tp_s != 0) { tp_rc = -1; goto tp_exit; }")
            return t
        if e.op is ir.UnaryOpKind.NOT:
            if ot is not Type.BOOL:
                raise CGenError(f"{self.f.name}: NOT of {ot}")
            t = self.tmp("int")
            self.emit(f"{t} = !({v});")
            return t
        if ot is Type.I64 and e.op is ir.UnaryOpKind.NEG:
            t = self.tmp("int64_t")
            if e.proven:
                self.emit(f"tp_proven_neg({v}, {t});")
            else:
                self.check(f"tp_neg_i64({v}, &{t})")
            return t
        if ot is Type.F64 and e.op is ir.UnaryOpKind.NEG:
            t = self.tmp("double")
            self.emit(f"{t} = -({v});")
            return t
        if ot in (Type.I64, Type.F64) and e.op is ir.UnaryOpKind.POS:
            t = self.tmp(_CT[ot])
            self.emit(f"{t} = {v};")
            return t
        raise CGenError(f"{self.f.name}: {e.op} of {ot}")

    def ev_Compare(self, e: ir.Compare) -> str:
        lt, rt = e.left.type, e.right.type
        a, b = self.ev(e.left), self.ev(e.right)
        t = self.tmp("int")
        if lt is Type.INT and rt is Type.INT:
            self.emit(f"{t} = tp_int_cmp(&{a}, &{b}, {_PYCMP[e.op]});")
            self.emit(f"tp_int_release(&{a}); tp_int_release(&{b});")
            self.emit(f"if ({t} < 0) {{ tp_rc = -1; goto tp_exit; }}")
        elif lt is rt and lt in ir.SCALARS:
            self.emit(f"{t} = ({a}) {e.op.value} ({b});")
        elif (lt, rt) == (Type.I64, Type.F64):
            self.emit(f"{t} = tp_cmp_i64_f64({a}, {b}, {_PYCMP[e.op]});")
        elif (lt, rt) == (Type.F64, Type.I64):
            self.emit(f"{t} = tp_cmp_f64_i64({a}, {b}, {_PYCMP[e.op]});")
        else:
            raise CGenError(f"{self.f.name}: Compare of {lt} and {rt} (OBJ uses CompareObj)")
        return t

    def ev_CompareObj(self, e: ir.CompareObj) -> str:
        if e.left.type is not Type.OBJ or e.right.type is not Type.OBJ:
            raise CGenError(f"{self.f.name}: CompareObj operands must be OBJ (Box scalars)")
        a, b = self.ev(e.left), self.ev(e.right)
        t = self.tmp("int")
        self.emit(f"{t} = tp_compare_bool({a}, {b}, {_PYCMP[e.op]});")
        self._own_release(a, b)
        self.emit(f"if ({t} < 0) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def _short_circuit(self, e, take_right_if: str) -> str:
        if e.left.type is not Type.BOOL or e.right.type is not Type.BOOL:
            raise CGenError(f"{self.f.name}: And/Or on non-BOOL operands")
        t = self.tmp("int")
        a = self.ev(e.left)
        self.emit(f"{t} = {a};")
        self.emit(f"if ({take_right_if}{t}) {{")
        self.depth += 1
        b = self.ev(e.right)
        self.emit(f"{t} = {b};")
        self.depth -= 1
        self.emit("}")
        return t

    def ev_And(self, e: ir.And) -> str:
        return self._short_circuit(e, "")

    def ev_Or(self, e: ir.Or) -> str:
        return self._short_circuit(e, "!")

    def ev_Truth(self, e: ir.Truth) -> str:
        if e.operand.type is not Type.OBJ:
            raise CGenError(f"{self.f.name}: Truth of {e.operand.type}")
        o = self.ev(e.operand)
        t = self.tmp("int")
        self.emit(f"{t} = tp_truth({o});")
        self._own_release(o)
        self.emit(f"if ({t} < 0) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def ev_ToFloat(self, e: ir.ToFloat) -> str:
        if e.explicit and e.operand.type is Type.F64:
            return self.ev(e.operand)
        if e.operand.type is not Type.I64:
            raise CGenError(f"{self.f.name}: ToFloat of {e.operand.type}")
        v = self.ev(e.operand)
        t = self.tmp("double")
        self.emit(f"{t} = tp_i64_to_f64({v});")
        return t

    def ev_ToInt(self, e: ir.ToInt) -> str:
        if e.operand.type is not Type.I64 or e.type is not Type.INT:
            raise CGenError(f"{self.f.name}: ToInt of {e.operand.type}")
        v = self.ev(e.operand)
        t = self.tmp("tp_int")
        self.emit(f"{t}.v = {v}; {t}.big = NULL;")
        return t

    def ev_ObjToFloat(self, e: ir.ObjToFloat) -> str:
        if e.operand.type is not Type.OBJ:
            raise CGenError(f"{self.f.name}: ObjToFloat of {e.operand.type}")
        o = self.ev(e.operand)
        t = self.tmp("double")
        self.emit(f"tp_s = tp_obj_to_f64({o}, &{t});")
        self._own_release(o)
        self.emit("if (tp_s != 0) { tp_rc = -1; goto tp_exit; }")
        return t

    def ev_Box(self, e: ir.Box) -> str:
        ot = e.operand.type
        k = e.operand.operand if isinstance(e.operand, ir.ToInt) else e.operand
        if isinstance(k, ir.Const) and k.type is Type.I64 and ot in (Type.I64, Type.INT):
            # a constant is boxed once, at module exec (SPEC N-13), and read from module state
            return self._own_copy(f"tp_st->ints[{self.mg.int_index(k.value)}]")
        if ot is Type.INT:
            v = self.ev(e.operand)
            t = self._own_new(f"tp_int_box(&{v})")
            self.emit(f"tp_int_release(&{v});")
            return t
        if ot not in _BOX:
            raise CGenError(f"{self.f.name}: Box of {ot}")
        v = self.ev(e.operand)
        if e.np is not None:
            if e.np.type is not Type.BOOL:
                raise CGenError(f"{self.f.name}: Box np flag of {e.np.type}")
            flag = self.ev(e.np)
            return self._own_new(f"({flag} ? tp_box_np(tp_st->buf_types[{_NP_SLOT[ot]}], "
                                 f"{_BOX[ot]}({v})) : {_BOX[ot]}({v}))")
        return self._own_new(f"{_BOX[ot]}({v})")

    def ev_Unbox(self, e: ir.Unbox) -> str:
        if e.operand.type is not Type.OBJ or e.type not in _UNBOX:
            raise CGenError(f"{self.f.name}: Unbox {e.operand.type} -> {e.type}")
        o = self.ev(e.operand)
        t = self.tmp(_CT[e.type])
        self.emit(f"tp_s = {_UNBOX[e.type]}({o}, &{t});")
        self._own_release(o)
        self.emit("if (tp_s != 0) { tp_rc = tp_s; goto tp_exit; }")
        return t

    def ev_MathCall(self, e: ir.MathCall) -> str:
        if len(e.args) != _MATH[e.func] or any(a.type is not Type.F64 for a in e.args):
            raise CGenError(f"{self.f.name}: math.{e.func.value} arguments")
        args = [self.ev(a) for a in e.args]
        t = self.tmp("double")
        self.check(f"tp_math_{e.func.value}({', '.join(args)}, &{t})")
        return t

    def ev_Call(self, e: ir.Call) -> str | None:
        callee = self.mg.functions[e.function]
        if len(e.args) != len(callee.params):
            raise CGenError(f"{self.f.name}: Call of {e.function} with {len(e.args)} args")
        args = []
        owned = []
        values = []                             # the evaluated arguments, for a redo
        int_args = []                           # INT arguments of an i64 parameter (redo only)
        for p, a in zip(callee.params, e.args):
            int_arg = a.type is Type.INT and p.type is Type.I64 and e.redo
            if a.type is not p.type and not int_arg:
                raise CGenError(f"{self.f.name}: Call {e.function} arg {p.name}: {a.type} for {p.type}")
            v = self.ev(a)
            values.append(v)
            if int_arg:
                args.append(f"{v}.v")
                int_args.append(v)
            else:
                args.append(v)
            if a.type is Type.OBJ:
                owned.append(v)
        t = ti = None
        self.emit("tp_s = 0;")
        if not self.f.pure and not self.fast:
            # user code ran in this impure caller and may have started tracing since our own entry
            # (SPEC N-14): the callee runs interpreted, through the redo below
            self.emit(f"if ({_traced_call(self.mg.name_index(callee.name))}) tp_s = 1;")
        for v in int_args:
            # a promoted INT does not fit the callee's i64 parameter: the callee's deopt, redone below
            self.emit(f"if ({v}.big != NULL) tp_s = 1;")
        # The callee's entry globals (ir.Function.entry_globals) are its entry guards: read them
        # here, at the callee's entry. A mismatch is the callee's deopt (tp_s = 1), handled below
        # exactly as a deopt inside the callee.
        for g in callee.entry_globals:
            args.append(self._read_entry_global(g, "tp_s = 1;"))
        if callee.returns is not Type.NONE:
            t = self.tmp(_CT[callee.returns])
            args.append(f"&{t}")
        if e.type is Type.INT:
            if callee.returns is not Type.I64 or not e.redo:
                raise CGenError(f"{self.f.name}: Call {e.function} has type INT but is not a redo "
                                "Call of an i64 function")
            ti = self.tmp("tp_int")
        self.emit("if (tp_s == 0) {")
        self.depth += 1
        # Depth is counted by the callee itself (tp_enter_call at its entry, issue #57).
        if self.fast:
            # the fast twin's callees are bounded too (_bounded_functions): their fast twins, uncounted
            self.emit(f"tp_s = {_ident('tp_fimpl', e.function)}(tp_module, tp_fd + 1, tp_t{''.join(', ' + x for x in args)});")
        else:
            self.emit(f"tp_s = {_ident('tp_impl', e.function)}(tp_module, tp_ts, tp_t{''.join(', ' + x for x in args)});")
        self.depth -= 1
        self.emit("}")
        if ti is not None:
            self.emit(f"if (tp_s == 0) {{ {ti}.v = {t}; {ti}.big = NULL; }}")
        if e.redo:
            self._redo_call(callee, e.args, values, t, ti)
        elif not self.f.pure:
            # a pure callee using classes returns 1 when a class check fails (module docstring,
            # rule 7): redo it interpreted; this impure caller must not deopt
            self._redo_call(callee, e.args, values, t)
        self._own_release(*owned)
        for v in int_args:
            self.emit(f"tp_int_release(&{v});")
        self.emit("if (tp_s != 0) { tp_rc = tp_s; goto tp_exit; }")
        return ti if ti is not None else t

    def _read_entry_global(self, g: ir.Param, on_mismatch: str, on_error: str = "tp_rc = -1; goto tp_exit;",
                           guard: str = "tp_s == 0") -> str:
        """Emit the entry read of module global `g` (tp_global, exact-type unbox, release) guarded
        by `tp_s == 0`; on a wrong type or an unbound name run `on_mismatch` (a deopt: the
        interpreted function then sees the same binding and raises CPython's own NameError where
        CPython would). Returns the C scalar holding the value."""
        k = self.mg.name_index(g.name)
        gv, go = self.tmp(_CT[g.type]), self.tmp("PyObject *")
        self.emit(f"if ({guard}) {{")
        self.emit(f"    {go} = tp_global(tp_dict, tp_st->names[{k}]);")
        self.emit(f"    if ({go} == NULL) {{")
        self.emit(f"        if (!PyErr_ExceptionMatches(PyExc_NameError)) {{ {on_error} }}")
        self.emit(f"        PyErr_Clear(); {on_mismatch}")
        self.emit("    } else {")
        self.emit(f"        tp_s = {_UNBOX[g.type]}({go}, &{gv});")
        self.emit(f"        tp_release(&{go});")
        self.emit(f"        if (tp_s < 0) {{ {on_error} }}")
        self.emit(f"        if (tp_s != 0) {{ {on_mismatch} }}")
        self.emit("    }")
        self.emit("}")
        return gv

    def _redo_call(self, callee: ir.Function, arg_exprs, values: list[str], t: str | None,
                   ti: str | None = None) -> None:
        """ir.Call.redo: on the callee's deopt (tp_s == 1) run `__typedpython_interpreted__[callee]`
        on the already-evaluated arguments (boxed), count the deopt, convert the result back, and
        continue in the caller. The callee is pure, so the redo is unobservable."""
        k = self.mg.name_index(callee.name)
        self.emit("if (tp_s == 1) {")
        self.depth += 1
        boxes = []
        av = []
        for a, v in zip(arg_exprs, values):
            if a.type is Type.OBJ:
                av.append(v)                    # still owned by the caller; released after
            elif a.type is Type.INT:
                b = self._own_new(f"tp_int_box(&{v})")
                boxes.append(b)
                av.append(b)
            else:
                b = self._own_new(f"{_BOX[a.type]}({v})")
                boxes.append(b)
                av.append(b)
        rr = self.tmp("PyObject *")
        # In a fast twin the interpreted callee (a Python frame) and whatever it calls must see the
        # frames of this fast tree: count them for exactly the span that can run Python code, from
        # the call to the release of its result (module docstring, "Fast entry").
        end = "tp_depth_py_end(tp_t, tp_fd); " if self.fast else ""
        if self.fast:
            self.emit("tp_depth_py_begin(tp_t, tp_fd);")
        if av:
            self.emit(f"{{ PyObject *tp_av[{len(av)}] = {{{', '.join(av)}}};")
            self.emit(f"  {rr} = tp_cg_redo(tp_module, {k}, tp_av, {len(av)}, NULL); }}")
        else:
            self.emit(f"{rr} = tp_cg_redo(tp_module, {k}, NULL, 0, NULL);")
        self._own_release(*boxes)
        self.emit(f"if ({rr} == NULL) {{ {end}tp_rc = -1; goto tp_exit; }}")
        rt = callee.returns
        if rt is Type.NONE:
            self.emit(f"tp_s = ({rr} == Py_None) ? 0 : 1;")
            what = "None"
        elif rt is Type.OBJ:
            self.emit(f"{t} = {rr}; {rr} = NULL; tp_s = 0;")   # moved: the temp owns it now
            what = "an object"
        elif ti is not None:
            # SPEC N-13: the redone value is any int: native, or kept as the PyLong it is
            self.emit(f"tp_s = tp_int_of_exact(&{ti}, {rr});")
            what = "an int"
        else:
            self.emit(f"tp_s = {_UNBOX[rt]}({rr}, &{t});")
            what = {Type.F64: "a float", Type.BOOL: "True or False", Type.I64: "an int in i64"}[rt]
        self._own_release(rr)
        if self.fast:
            self.emit("tp_depth_py_end(tp_t, tp_fd);")
        msg = c_string(f"typedpython: the interpreted redo of {callee.name} did not return {what}")
        self.emit(f"if (tp_s != 0) {{ PyErr_SetString(PyExc_SystemError, {msg}); tp_rc = -1; goto tp_exit; }}")
        self.depth -= 1
        self.emit("}")

    def _obj_operand(self, e: ir.Expr, what: str) -> str:
        if e.type is not Type.OBJ:
            raise CGenError(f"{self.f.name}: {what} on {e.type}")
        return self.ev(e)

    def ev_Is(self, e: ir.Is) -> str:
        a = self._obj_operand(e.left, "Is")
        b = self._obj_operand(e.right, "Is")
        t = self.tmp("int")
        self.emit(f"{t} = ({a} {'!=' if e.negate else '=='} {b});")
        self._own_release(a, b)
        return t

    def _current(self, obj_c: str, cls: str) -> str:
        k = self._class(cls)
        fields_c, n, descrs_c = self.mg.fields_c(cls)
        return _class_current_expr(obj_c, k, fields_c, n, descrs_c)

    def _global_ok(self, cls: str) -> str:
        k = self._class(cls)
        nk = self.mg.name_index(cls)
        return (f"tp_class_global_ok(tp_dict, tp_st->names[{nk}], tp_st->classes[{k}], "
                f"&tp_st->cls_rt[{k}], tp_st->watch_id1 > 0)")

    def _slow(self, cls: str) -> None:
        """Count a failed per-access check; in a pure function it is a deopt (module docstring)."""
        self.emit(f"tp_class_note_slow(&tp_st->cls_rt[{self._class(cls)}]);")
        if self.f.pure:
            self.emit("tp_rc = 1; goto tp_exit;")

    def ev_IsExact(self, e: ir.IsExact) -> str:
        k = self._class(e.cls)
        o = self._obj_operand(e.obj, "IsExact")
        t = self.tmp("int")
        self.emit(f"tp_s = {self._global_ok(e.cls)};")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit("if (tp_s) {")
        self.emit(f"    {t} = tp_is_exact({o}, (PyTypeObject *)tp_st->classes[{k}]);")
        self.emit("} else {")
        self.depth += 1
        self._slow(e.cls)
        if not self.f.pure:
            g = self._own_new(f"tp_global(tp_dict, tp_st->names[{self.mg.name_index(e.cls)}])")
            self.emit(f"{t} = tp_is_exact({o}, (PyTypeObject *){g});")
            self._own_release(g)
        self.depth -= 1
        self.emit("}")
        self._own_release(o)
        return t

    def ev_CheckExact(self, e: ir.CheckExact) -> str:
        k = self._class(e.cls)
        o = self._obj_operand(e.obj, "CheckExact")
        # a deopt: legal only in a pure function (_check_function); the exit block releases `o`
        self.emit(f"if (!tp_is_exact({o}, (PyTypeObject *)tp_st->classes[{k}])) {{ tp_rc = 1; goto tp_exit; }}")
        return o

    def ev_CheckMethod(self, e: ir.CheckMethod) -> str:
        """`obj.method` is still the compiled method: `type(obj) is C` and every installed method of C
        is still the object in C's own dict (tp_methods_current); else a deopt (legal in a pure
        function only, _check_function). The receiver is checked here, before the call's other
        operands are evaluated, where LOAD_ATTR is."""
        k = self._class(e.cls)
        self.mg.method(self.f.name, e.cls, e.method)
        o = self._obj_operand(e.obj, "CheckMethod")
        names_c, n, descrs_c = self.mg.methods_c(e.cls)
        self.emit(f"tp_s = tp_methods_current({o}, tp_st->classes[{k}], &tp_st->cls_rt[{k}], "
                  f"{names_c}, {n}, {descrs_c});")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit(f"if (!tp_s) {{ tp_class_note_slow(&tp_st->cls_rt[{k}]); tp_rc = 1; goto tp_exit; }}")
        return o

    def ev_FieldGet(self, e: ir.FieldGet) -> str:
        if isinstance(e.obj, ir.Local) and e.obj.name in self.virtual:
            cd = self.mg.cls(self.f.name, e.cls)
            if e.cls != self.virtual[e.obj.name] or e.field not in cd.fields:
                raise CGenError(f"{self.f.name}: FieldGet {e.cls}.{e.field} on virtual {e.obj.name}")
            return self._own_copy(_ident("l", _vfield(e.obj.name, cd.fields.index(e.field))))
        slot = self.mg.slot(self.f.name, e.cls, e.field)
        o = self._obj_operand(e.obj, "FieldGet")
        t = self.tmp("PyObject *")
        self.emit(f"tp_s = {self._current(o, e.cls)};")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit("if (tp_s) {")
        self.emit(f"    {t} = tp_field_get({o}, {slot});")
        self.emit("} else {")
        self.depth += 1
        self._slow(e.cls)
        if not self.f.pure:                                 # LOAD_ATTR, whatever the class is now
            self.emit(f"{t} = tp_getattr({o}, tp_st->names[{self.mg.name_index(e.field)}]);")
        self.depth -= 1
        self.emit("}")
        self._own_release(o)
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def _assign_virtual(self, s: ir.Assign) -> None:
        """`x = New(C, args)` of a virtual local: the same guards and argument order as `ev_New`
        (LOAD_GLOBAL C first, then the arguments, then the class check; a failure is a deopt, the
        function is pure), but no object: each argument moves into the field local of `x`."""
        e = s.value
        cd = self.mg.cls(self.f.name, e.cls)
        if not isinstance(e, ir.New) or len(e.args) != len(cd.fields) or not self.f.pure:
            raise CGenError(f"{self.f.name}: virtual {s.target} needs a New of {len(cd.fields)} "
                            f"fields in a pure function")
        k = self._class(e.cls)
        self.emit(f"tp_s = {self._global_ok(e.cls)};")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit("if (!tp_s) { tp_class_note_slow(&tp_st->cls_rt[%d]); tp_rc = 1; goto tp_exit; }" % k)
        items = [self._obj_operand(a, "New argument") for a in e.args]
        self.emit(f"tp_s = {self._current('NULL', e.cls)};")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit("if (!tp_s) { tp_class_note_slow(&tp_st->cls_rt[%d]); tp_rc = 1; goto tp_exit; }" % k)
        for i, t in enumerate(items):
            self._own_move(_ident("l", _vfield(s.target, i)), t)

    def ev_New(self, e: ir.New) -> str:
        cd = self.mg.cls(self.f.name, e.cls)
        if not cd.trivial_init:
            raise CGenError(f"{self.f.name}: New of class {e.cls}, whose __init__ is not trivial")
        if len(e.args) != len(cd.fields):
            raise CGenError(f"{self.f.name}: New {e.cls} with {len(e.args)} args for "
                            f"{len(cd.fields)} fields")
        k = self._class(e.cls)
        # LOAD_GLOBAL C comes before the arguments (module docstring, rule 5)
        gk, g = self.tmp("int"), None
        self.emit(f"tp_s = {self._global_ok(e.cls)};")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit(f"{gk} = tp_s;")
        self.emit(f"if (!{gk}) {{")
        self.depth += 1
        self._slow(e.cls)
        if not self.f.pure:
            g = self.tmp("PyObject *")
            self.emit(f"{g} = tp_global(tp_dict, tp_st->names[{self.mg.name_index(e.cls)}]);")
            self.emit(f"if ({g} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        self.depth -= 1
        self.emit("}")
        items = [self._obj_operand(a, "New argument") for a in e.args]
        t = self.tmp("PyObject *")
        base = self.mg.slot_base[e.cls]
        av = f"{{ PyObject *tp_av[{len(items)}] = {{{', '.join(items)}}};" if items else "{ PyObject **tp_av = NULL;"
        self.emit(f"tp_s = {gk} ? {self._current('NULL', e.cls)} : 0;")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit(av)
        self.emit("  if (tp_s) {")
        self.emit(f"      {t} = tp_new_fixed((PyTypeObject *)tp_st->classes[{k}], "
                  f"{f'&tp_st->slots[{base}]' if items else 'NULL'}, tp_av, {len(items)});")
        self.emit("  } else {")
        self.depth += 1
        if self.f.pure:
            self.emit(f"if ({gk}) {{ tp_class_note_slow(&tp_st->cls_rt[{k}]); }}")
            self.emit("tp_rc = 1; goto tp_exit;")          # (g is NULL here: pure never loads it)
        else:
            self.emit(f"if ({gk}) tp_class_note_slow(&tp_st->cls_rt[{k}]);")
            # call what the global held when it was loaded: the class itself, or the rebinding
            self.emit(f"  {t} = tp_call({gk} ? tp_st->classes[{k}] : {g}, tp_av, {len(items)}, NULL);")
        self.depth -= 1
        self.emit("  } }")
        self._own_release(*items)                           # the slots took their own references
        if g is not None:
            self._own_release(g)
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def _class(self, name: str) -> int:
        self.mg.cls(self.f.name, name)
        return self.mg.class_index[name]

    def ev_Global(self, e: ir.Global) -> str:
        # LOAD_GLOBAL through the per-name cache, revalidated by the dict-watcher epoch (SPEC N-13)
        k = self.mg.name_index(e.name)
        g = self.mg.gslot_index(e.name)
        return self._own_new(f"tp_gcache_global(&tp_st->gc[{g}], tp_dict, tp_st->builtins, "
                             f"tp_st->names[{k}], tp_st->watch_id1 > 0)")

    def ev_GetAttr(self, e: ir.GetAttr) -> str:
        if e.obj.type is not Type.OBJ:
            raise CGenError(f"{self.f.name}: GetAttr on {e.obj.type}")
        o = self.ev(e.obj)
        k = self.mg.name_index(e.name)
        t = self.tmp("PyObject *")
        self.emit(f"{t} = tp_getattr({o}, tp_st->names[{k}]);")
        self._own_release(o)
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def ev_CallObject(self, e: ir.CallObject) -> str:
        if e.callee.type is not Type.OBJ or any(a.type is not Type.OBJ for a in e.args):
            raise CGenError(f"{self.f.name}: CallObject operands must be OBJ")
        if len(e.kwnames) > len(e.args) or len(set(e.kwnames)) != len(e.kwnames):
            raise CGenError(f"{self.f.name}: CallObject kwnames {e.kwnames}")
        callee = self.ev(e.callee)                      # CPython evaluates the callee first
        args = [self.ev(a) for a in e.args]
        npos = len(e.args) - len(e.kwnames)
        kw = f"tp_st->kwnames[{self.mg.kw_index(tuple(e.kwnames))}]" if e.kwnames else "NULL"
        t = self.tmp("PyObject *")
        if args:
            self.emit(f"{{ PyObject *tp_av[{len(args)}] = {{{', '.join(args)}}};")
            self.emit(f"  {t} = tp_call({callee}, tp_av, {npos}, {kw}); }}")
        else:
            self.emit(f"{t} = tp_call({callee}, NULL, 0, NULL);")
        self._own_release(callee, *args)
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def _array(self, name: str) -> tuple[Type, str]:
        """(array type, C pointer expression) of an array parameter (already a pointer) or a local
        array (a struct held by value, so its address)."""
        a = self.arrays.get(name)
        if a is not None:
            return a.type, _ident("l", name)
        t = self.local_arrays.get(name)
        if t is None:
            raise CGenError(f"{self.f.name}: {name} is not an array parameter or local array")
        return t, f"(&{_ident('l', name)})"

    def ev_Len(self, e: ir.Len) -> str:
        _, ptr = self._array(e.array)
        t = self.tmp("int64_t")
        self.emit(f"{t} = (int64_t){ptr}->len;")
        return t

    def _buffer(self, name: str) -> str | None:
        """`ArrayParam.buffer` of an array parameter ("array"), None for a list or local."""
        a = self.arrays.get(name)
        return a.buffer if a is not None else None

    def _slot(self, at: Type, ptr: str, index: ir.Expr, proven: bool, store: int,
              buffer: str | None = None) -> str:
        if index.type is not Type.I64:
            raise CGenError(f"{self.f.name}: index of type {index.type}")
        i = self.ev(index)
        if proven:
            return f"(Py_ssize_t)({i})"
        slot = self.tmp("Py_ssize_t")
        if buffer:
            self.check(f"tp_buf_slot({ptr}, {i}, {store}, &{slot})")
        else:
            self.check(f"{_ARRAY_PFX[at]}_slot({ptr}, {i}, {store}, &{slot})")
        return slot

    def ev_Index(self, e: ir.Index) -> str:
        at, ptr = self._array(e.array)
        if e.type is not _ARRAY_ELEM[at]:
            raise CGenError(f"{self.f.name}: Index of {at} typed {e.type}")
        buffer = self._buffer(e.array)
        slot = self._slot(at, ptr, e.index, e.proven, 0, buffer)
        t = self.tmp(_CT[e.type])
        if buffer:
            self.emit(f"{t} = tp_buf_ld_{_BUFFER_SFX[at]}({ptr}, {slot});")
        else:
            self.emit(f"{t} = {ptr}->data[{slot}];")
        return t

    def ev_NewArray(self, e: ir.NewArray) -> str:
        raise CGenError(f"{self.f.name}: NewArray is legal only as the value of an Assign to a "
                        "local array (ir.py)")

    def ev_CopyArray(self, e: ir.CopyArray) -> str:
        raise CGenError(f"{self.f.name}: CopyArray is legal only as the value of an Assign to a "
                        "local array (ir.py)")

    def ev_Tuple(self, e: ir.Tuple) -> str:
        if e.type is not Type.OBJ or any(x.type is not Type.OBJ for x in e.elements):
            raise CGenError(f"{self.f.name}: Tuple elements must be OBJ (Box scalars)")
        items = [self.ev(x) for x in e.elements]            # owned temporaries, evaluated in order
        t = self.tmp("PyObject *")
        if items:
            self.emit(f"{{ PyObject *tp_av[{len(items)}] = {{{', '.join(items)}}};")
            self.emit(f"  {t} = tp_tuple(tp_av, {len(items)}); }}")
        else:
            self.emit(f"{t} = tp_tuple(NULL, 0);")
        self._own_release(*items)                           # tp_tuple stole nothing
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def ev_GetItem(self, e: ir.GetItem) -> str:
        o = self._obj_operand(e.obj, "GetItem")                    # the container, then the index
        k = self._obj_operand(e.index, "GetItem index")
        t = self.tmp("PyObject *")
        self.emit(f"{t} = tp_getitem({o}, {k});")
        self._own_release(o, k)
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def ev_Contains(self, e: ir.Contains) -> str:
        a = self._obj_operand(e.left, "Contains")                  # CONTAINS_OP: left, then right
        b = self._obj_operand(e.right, "Contains")
        t = self.tmp("int")
        self.emit(f"{t} = tp_contains({b}, {a});")
        self._own_release(a, b)
        self.emit(f"if ({t} < 0) {{ tp_rc = -1; goto tp_exit; }}")
        if e.negate:
            self.emit(f"{t} = !{t};")
        return t

    def ev_NewList(self, e: ir.NewList) -> str:
        return self._own_new("tp_new_list()")

    def ev_NewDict(self, e: ir.NewDict) -> str:
        return self._own_new("tp_new_dict()")

    def ev_Format(self, e: ir.Format) -> str:
        if e.conversion not in (-1, 97, 114, 115) or (e.spec is not None and "\0" in e.spec):
            raise CGenError(f"{self.f.name}: Format conversion {e.conversion!r} / spec {e.spec!r}")
        o = self._obj_operand(e.operand, "Format")
        spec = "NULL" if e.spec is None else f"tp_st->names[{self.mg.name_index(e.spec)}]"
        t = self.tmp("PyObject *")
        self.emit(f"{t} = tp_format({o}, {spec}, {e.conversion});")
        self._own_release(o)
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def ev_BuildString(self, e: ir.BuildString) -> str:
        if len(e.elements) < 2:
            raise CGenError(f"{self.f.name}: BuildString of {len(e.elements)} elements")
        items = [self._obj_operand(x, "BuildString element") for x in e.elements]
        t = self.tmp("PyObject *")
        self.emit(f"{{ PyObject *tp_av[{len(items)}] = {{{', '.join(items)}}};")
        self.emit(f"  {t} = tp_build_string(tp_av, {len(items)}); }}")
        self._own_release(*items)
        self.emit(f"if ({t} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        return t

    def _assign_array(self, s: ir.Assign, at: Type) -> None:
        """`local = NewArray | CopyArray`: build into a zeroed temporary, then (only on success)
        free the old array and move the temporary in."""
        v = s.value
        pfx = _ARRAY_PFX[at]
        if v.type is not at:
            raise CGenError(f"{self.f.name}: {s.target} is {at}, assigned {v.type}")
        dst = _ident("l", s.target)
        new = self.tmp(_ARRAY_CT[at])
        if isinstance(v, ir.NewArray):
            if v.length.type is not Type.I64:
                raise CGenError(f"{self.f.name}: NewArray length of type {v.length.type}")
            if v.iota:
                if at is not Type.I64_ARRAY or v.fill is not None:
                    raise CGenError(f"{self.f.name}: iota NewArray must be I64_ARRAY with no fill")
                n = self.ev(v.length)
                self.check(f"{pfx}_iota(&{new}, {n})")
            else:
                if v.fill is None or v.fill.type is not _ARRAY_ELEM[at]:
                    raise CGenError(f"{self.f.name}: NewArray fill must be a "
                                    f"{_ARRAY_ELEM[at]} (got {None if v.fill is None else v.fill.type})")
                fill = self.ev(v.fill)                      # `[fill] * n`: fill first, then n
                n = self.ev(v.length)
                self.check(f"{pfx}_new(&{new}, {n}, {fill})")
        elif isinstance(v, ir.CopyArray):
            if self._buffer(v.src):
                raise CGenError(f"{self.f.name}: CopyArray of buffer array {v.src}")
            st, sptr = self._array(v.src)
            if st is not at:
                raise CGenError(f"{self.f.name}: CopyArray of {st} into {at}")
            self.check(f"{pfx}_copy(&{new}, {sptr})")
        else:
            raise CGenError(f"{self.f.name}: local array {s.target} may only be assigned a "
                            f"NewArray or CopyArray, not {type(v).__name__}")
        self.emit(f"{pfx}_free(&{dst});")
        self.emit(f"{dst} = {new}; {new} = ({_ARRAY_CT[at]}){{0}};")

    # statements

    def stmts(self, body) -> None:
        for s in body:
            m = getattr(self, "st_" + type(s).__name__, None)
            if m is None:
                raise CGenError(f"{self.f.name}: no lowering for {type(s).__name__}")
            m(s)

    def st_Assign(self, s: ir.Assign) -> None:
        if s.target in self.local_arrays:
            self._assign_array(s, self.local_arrays[s.target])
            return
        if s.target in self.virtual:
            self._assign_virtual(s)
            return
        t = self.types.get(s.target)
        if t is None:
            raise CGenError(f"{self.f.name}: assignment to unknown local {s.target}")
        if s.value.type is not t:
            raise CGenError(f"{self.f.name}: {s.target} is {t}, assigned {s.value.type}")
        v = self.ev(s.value)
        if t is Type.OBJ:
            self._own_move(_ident("l", s.target), v)
        elif t is Type.INT:
            self.emit(f"tp_int_assign(&{_ident('l', s.target)}, &{v});")
        else:
            self.emit(f"{_ident('l', s.target)} = {v};")

    def st_StoreIndex(self, s: ir.StoreIndex) -> None:
        at, ptr = self._array(s.array)
        local = s.array in self.local_arrays
        if not local and not self.arrays[s.array].stored:
            raise CGenError(f"{self.f.name}: store into {s.array}, declared stored=False")
        if s.value.type is not _ARRAY_ELEM[at]:
            raise CGenError(f"{self.f.name}: store of {s.value.type} into {at}")
        v = self.ev(s.value)                    # CPython: value, then container, then index
        buffer = self._buffer(s.array)
        slot = self._slot(at, ptr, s.index, s.proven, 1, buffer)
        if buffer:
            self.emit(f"tp_buf_st_{_BUFFER_SFX[at]}({ptr}, {slot}, {v});")
            return
        self.emit(f"{ptr}->data[{slot}] = {v};")
        if not local:                           # a local array has no list to write back to
            self.emit(f"{ptr}->dirty[{slot}] = 1;")

    def st_StoreItem(self, s: ir.StoreItem) -> None:
        v = self._obj_operand(s.value, "StoreItem value")          # STORE_SUBSCR: value, container, index
        o = self._obj_operand(s.obj, "StoreItem")
        k = self._obj_operand(s.index, "StoreItem index")
        self.emit(f"tp_s = tp_setitem({o}, {k}, {v});")
        self._own_release(o, k, v)
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")

    def st_ForObj(self, s: ir.ForObj) -> None:
        if not s.targets or len(set(s.targets)) != len(s.targets) or (not s.unpack and len(s.targets) != 1):
            raise CGenError(f"{self.f.name}: ForObj targets {s.targets!r} (unpack={s.unpack})")
        for name in s.targets:
            if self.types.get(name) is not Type.OBJ:
                raise CGenError(f"{self.f.name}: ForObj target {name} must be an OBJ local")
        o = self._obj_operand(s.iter, "ForObj iterable")
        it = self.tmp("PyObject *")          # the iterator: owned by this statement across iterations
        self.emit(f"{it} = tp_get_iter({o});")                # GET_ITER
        self._own_release(o)
        self.emit(f"if ({it} == NULL) {{ tp_rc = -1; goto tp_exit; }}")
        item = self.tmp("PyObject *")
        self.emit("for (;;) {")
        self.depth += 1
        self.poll()
        self.emit(f"{item} = tp_iter_next({it});")            # FOR_ITER (StopIteration ends the loop)
        self.emit(f"if ({item} == NULL) {{ if (PyErr_Occurred()) {{ tp_rc = -1; goto tp_exit; }} break; }}")
        if not s.unpack:
            self._own_move(_ident("l", s.targets[0]), item)
        else:
            n = len(s.targets)
            self.emit(f"{{ PyObject *tp_u[{n}] = {{0}};")
            self.emit(f"  tp_s = tp_unpack({item}, {n}, tp_u);")  # UNPACK_SEQUENCE; nothing owned on error
            self._own_release(item)
            self.emit("  if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
            for i, name in enumerate(s.targets):
                local = _ident("l", name)
                self.emit(f"  {{ PyObject *tp_old = {local}; {local} = tp_u[{i}]; tp_release(&tp_old); }}")
            self.emit("}")
        self.brk.append(None)
        self.stmts(s.body)
        self.brk.pop()
        self.depth -= 1
        self.emit("}")
        self._own_release(it)               # after the loop and after a `break`; a return/error exits via tp_exit

    def st_FieldSet(self, s: ir.FieldSet) -> None:
        slot = self.mg.slot(self.f.name, s.cls, s.field)
        v = self._obj_operand(s.value, "FieldSet value")      # CPython: value, then the object
        o = self._obj_operand(s.obj, "FieldSet")
        self.emit(f"tp_s = {self._current(o, s.cls)};")
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")
        self.emit("if (tp_s) {")
        self.emit(f"    tp_s = tp_field_set({o}, {slot}, {v});")
        self.emit("} else {")
        self.depth += 1
        self._slow(s.cls)                                   # FieldSet is never in a pure function
        self.emit(f"tp_s = tp_setattr({o}, tp_st->names[{self.mg.name_index(s.field)}], {v});")
        self.depth -= 1
        self.emit("}")
        self._own_release(o, v)
        self.emit("if (tp_s < 0) { tp_rc = -1; goto tp_exit; }")

    def st_ExprStmt(self, s: ir.ExprStmt) -> None:
        v = self.ev(s.value)
        if v is None:
            return
        if s.value.type is Type.OBJ:
            self._own_release(v)
        elif s.value.type is Type.INT:
            self.emit(f"tp_int_release(&{v});")
        else:
            self.emit(f"(void){v};")

    def _cond(self, e: ir.Expr) -> str:
        if e.type is not Type.BOOL:
            raise CGenError(f"{self.f.name}: condition of type {e.type} (use Truth/CompareObj)")
        return self.ev(e)

    def st_If(self, s: ir.If) -> None:
        c = self._cond(s.cond)
        self.emit(f"if ({c}) {{")
        self.depth += 1
        self.stmts(s.then)
        self.depth -= 1
        if s.orelse:
            self.emit("} else {")
            self.depth += 1
            self.stmts(s.orelse)
            self.depth -= 1
        self.emit("}")

    @property
    def snapshot(self) -> bool:
        """Holds an array copy-in or an entry-globals snapshot, so must not run user code (#141)."""
        return bool(self.f.entry_globals) or any(isinstance(p, ir.ArrayParam) for p in self.f.params)

    def poll(self, every_time: bool = False) -> None:
        """At the top of each loop iteration (#141): signals, pending calls, GIL hand-off.

        `every_time` skips the countdown: the caller already runs it once per TP_POLL_INTERVAL
        iterations (a chunked range loop, #206/#280), so the slow poll is due at every call."""
        if not self.snapshot:
            # A function-local countdown (kept in a register), not the module counter: a load and
            # store of a global on every iteration of a short inner loop cost fannkuch ~38%.
            if self.fast:
                # the poll can run a signal handler or another compiled call: count this fast tree
                body = ("tp_depth_py_begin(tp_t, tp_fd); "
                        "tp_s = tp_poll_slow(tp_t, tp_st->breaker); tp_depth_py_end(tp_t, tp_fd); "
                        "if (tp_s != 0) { tp_rc = -1; goto tp_exit; }")
                self.emit(f"{{ {body} }}" if every_time else f"if (tp_pc_tick(&tp_pc)) {{ {body} }}")
                return
            if every_time:
                self.emit("if (tp_poll_slow(tp_t, tp_st->breaker) != 0) { tp_rc = -1; goto tp_exit; }")
                return
            self.emit("if (tp_pc_tick(&tp_pc) && tp_poll_slow(tp_t, tp_st->breaker) != 0) "
                      "{ tp_rc = -1; goto tp_exit; }")

    def st_While(self, s: ir.While) -> None:
        self.emit("for (;;) {")
        self.depth += 1
        self.poll()
        c = self._cond(s.cond)
        self.emit(f"if (!({c})) break;")
        self.brk.append(None)
        self.stmts(s.body)
        self.brk.pop()
        self.depth -= 1
        self.emit("}")

    def _vec_plan(self, s: ir.ForRange):
        """The element-wise buffer store loop `for i in range(a, b): x[i] = f(y[i], ..., k)` that
        may run two F64 lanes at a time (TP_BUF_VEC2, tp_runtime.h; design/typedpython.md
        "Vectorised buffer loops"). Returns the store statement, or None. Only step 1, one
        statement, a proven store at the loop variable into an F64 buffer array, and a value built
        from proven loads at the same variable, F64 locals and constants, + - * and negation.
        No division (it raises), no I64 (checked overflow), no other statement."""
        if s.step.value != 1 or len(s.body) != 1 or not isinstance(s.body[0], ir.StoreIndex):
            return None
        st = s.body[0]

        def at_var(ix, proven):
            return proven and isinstance(ix, ir.Local) and ix.name == s.var

        def ok(e):
            if e.type is not Type.F64:
                return False
            if isinstance(e, ir.Const):
                return True
            if isinstance(e, ir.Local):
                return e.name != s.var and self.types.get(e.name) is Type.F64
            if isinstance(e, ir.Index):
                a = self.arrays.get(e.array)
                return (a is not None and a.buffer and a.type is Type.F64_ARRAY
                        and at_var(e.index, e.proven))
            if isinstance(e, ir.BinOp):
                # a numpy operation reports FP flags per element (tp_np_f64); NEON lanes do not
                return (e.op in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL)
                        and not e.np and ok(e.left) and ok(e.right))
            if isinstance(e, ir.UnaryOp):
                return e.op is ir.UnaryOpKind.NEG and ok(e.operand)
            return False

        a = self.arrays.get(st.array)
        if a is None or not a.buffer or a.type is not Type.F64_ARRAY or not a.stored:
            return None
        if not at_var(st.index, st.proven) or not ok(st.value):
            return None
        return st

    def _vec_expr(self, e: ir.Expr, base: str, hoist: list[str]) -> str:
        if isinstance(e, (ir.Const, ir.Local)):
            v = self.tmp("double")
            hoist.append(f"{v} = {self.ev(e)};")
            return f"vdupq_n_f64({v})"
        if isinstance(e, ir.Index):
            _, ptr = self._array(e.array)
            return f"tp_buf_ld2_f64({ptr}, {base})"
        if isinstance(e, ir.UnaryOp):
            return f"vnegq_f64({self._vec_expr(e.operand, base, hoist)})"
        fn = {ir.BinOpKind.ADD: "vaddq_f64", ir.BinOpKind.SUB: "vsubq_f64",
              ir.BinOpKind.MUL: "vmulq_f64"}[e.op]
        return f"{fn}({self._vec_expr(e.left, base, hoist)}, {self._vec_expr(e.right, base, hoist)})"

    def _emit_vec_loop(self, st: ir.StoreIndex, ts: str, tk: str, limit: str) -> None:
        """Four lanes per step (two independent 2-lane loads, then two stores) while tk + 4 <=
        limit, then two lanes while tk + 2 <= limit; the scalar loop that follows takes the rest."""
        hoist: list[str] = []
        self.emit("#ifdef TP_BUF_VEC2")
        base = f"(Py_ssize_t)((uint64_t){ts} + {tk})"
        base2 = f"(Py_ssize_t)((uint64_t){ts} + {tk} + 2u)"
        e1 = self._vec_expr(st.value, base, hoist)
        e2 = self._vec_expr(st.value, base2, hoist)
        for h in hoist:
            self.emit(h)
        _, ptr = self._array(st.array)
        v1, v2 = "tp_vv1", "tp_vv2"
        self.emit(f"for (; {tk} + 4u <= {limit}; {tk} += 4u) {{")
        self.emit(f"    float64x2_t {v1} = {e1};")
        self.emit(f"    float64x2_t {v2} = {e2};")
        self.emit(f"    tp_buf_st2_f64({ptr}, {base}, {v1});")
        self.emit(f"    tp_buf_st2_f64({ptr}, {base2}, {v2});")
        self.emit("}")
        self.emit(f"for (; {tk} + 2u <= {limit}; {tk} += 2u) {{")
        self.emit(f"    tp_buf_st2_f64({ptr}, {base}, {e1});")
        self.emit("}")
        self.emit("#endif")

    def st_ForRange(self, s: ir.ForRange) -> None:
        if self.types.get(s.var) is not Type.I64:
            raise CGenError(f"{self.f.name}: range variable {s.var} must be an I64 local")
        if not isinstance(s.step, ir.Const) or s.step.type is not Type.I64 or s.step.value == 0:
            raise CGenError(f"{self.f.name}: range step must be a non-zero I64 Const")
        if s.start.type is not Type.I64 or s.stop.type is not Type.I64:
            raise CGenError(f"{self.f.name}: range bounds must be I64")
        step = s.step.value
        _i64_lit(step)
        ts, te = self.tmp("int64_t"), self.tmp("int64_t")
        a = self.ev(s.start)
        self.emit(f"{ts} = {a};")                     # range() evaluates its arguments once
        b = self.ev(s.stop)
        self.emit(f"{te} = {b};")
        tn, tk = self.tmp("uint64_t"), self.tmp("uint64_t")
        mag = f"UINT64_C({abs(step)})"
        var = _ident("l", s.var)
        # Iteration count computed in unsigned arithmetic, as range's length: no overflow for any
        # start/stop/step, and the variable stays between start and stop.
        if step > 0:
            self.emit(f"if ({ts} < {te}) {{")
            self.emit(f"    {tn} = ((uint64_t){te} - (uint64_t){ts} - 1u) / {mag} + 1u;")
            sign = "+"
        else:
            self.emit(f"if ({ts} > {te}) {{")
            self.emit(f"    {tn} = ((uint64_t){ts} - (uint64_t){te} - 1u) / {mag} + 1u;")
            sign = "-"
        self.depth += 1
        par_open = s.par is not None
        if par_open:
            self._emit_region(s, ts, tn, abs(step))
        vec = self._vec_plan(s) if step == 1 else None
        if self.snapshot:
            # no polling in a snapshot holder: a plain loop
            self.emit(f"{tk} = 0;")
            if vec is not None:
                self._emit_vec_loop(vec, ts, tk, tn)
            self.emit(f"for (; {tk} < {tn}; {tk}++) {{")
            self.depth += 1
            self.brk.append(None)
        else:
            # Chunked polling (#206): the trip count is known, so poll once per TP_POLL_INTERVAL
            # iterations. `continue` stays inside the chunk loop; `break` must leave both loops.
            te2, label = self.tmp("uint64_t"), f"tp_brk{self.label_n}"
            self.label_n += 1
            self.emit(f"for ({tk} = 0; {tk} < {tn}; ) {{")
            self.depth += 1
            self.poll(every_time=True)
            self.emit(f"{te2} = {tn} - {tk} > (uint64_t)TP_POLL_INTERVAL ? {tk} + (uint64_t)TP_POLL_INTERVAL : {tn};")
            if vec is not None:
                self._emit_vec_loop(vec, ts, tk, te2)
            self.emit(f"for (; {tk} < {te2}; {tk}++) {{")
            self.depth += 1
            self.brk.append(label)
        self.emit(f"{var} = (int64_t)((uint64_t){ts} {sign} {tk} * {mag});")
        self.stmts(s.body)
        label = self.brk.pop()
        self.depth -= 1
        self.emit("}")
        if label is not None:
            self.depth -= 1
            self.emit("}")
            if label in self.used_labels:
                self.emit(f"{label}: ;")
        if par_open:
            self.emit("}")                  # the sequential `else` of the parallel region
        self.depth -= 1
        self.emit("}")

    def _par_collect(self, e: ir.Expr, scalars: set, arrays: set) -> None:
        if isinstance(e, ir.Local):
            scalars.add(e.name)
        elif isinstance(e, ir.Index):
            arrays.add(e.array)
            self._par_collect(e.index, scalars, arrays)
        elif isinstance(e, ir.ToFloat):
            self._par_collect(e.operand, scalars, arrays)
        elif isinstance(e, ir.BinOp):
            self._par_collect(e.left, scalars, arrays)
            self._par_collect(e.right, scalars, arrays)
        elif isinstance(e, ir.UnaryOp):
            self._par_collect(e.operand, scalars, arrays)
        elif not isinstance(e, ir.Const):
            raise CGenError(f"{self.f.name}: {type(e).__name__} in a parallel loop body")

    def _emit_region(self, s: ir.ForRange, ts: str, tn: str, mag: int) -> None:
        """SPEC N-15: opens `if (<big enough and threads> ) { region } else {`; the caller closes the
        else with the sequential loop it emits next. The body is the verified `par` loop body
        (StoreIndex statements of F64 arithmetic), outlined into `tp_pbody_*`, which declares the
        names it uses as locals copied from a context struct, so the existing statement and
        expression emitters produce its code unchanged. Nothing in it can fail, deopt or build an
        exception (the verifier's rule); cgen refuses text that tries."""
        f, info = self.f, s.par
        if not f.parallel or not isinstance(info, ir.ParInfo) or s.step.value <= 0:
            raise CGenError(f"{f.name}: ForRange.par on a loop that cannot be parallel (unmarked "
                            "function, bad step or no ParInfo)")
        scalars: set[str] = set()
        arrays: set[str] = set()
        reds: list[tuple[str, str, ir.Expr]] = []         # (accumulator, op name, the other operand)
        if info.reduce:
            if info.stored or len(info.reduce) != len(s.body):
                raise CGenError(f"{f.name}: parallel reduction loop with stores or a missing reduction")
            for st, (acc, opn) in zip(sorted(s.body, key=lambda x: getattr(x, "target", "")), info.reduce):
                v = st.value if isinstance(st, ir.Assign) else None
                if (not isinstance(v, ir.BinOp) or st.target != acc or v.op.name != opn
                        or opn not in ("ADD", "MUL") or v.type is not Type.I64
                        or self.types.get(acc) is not Type.I64 or acc == s.var):
                    raise CGenError(f"{f.name}: parallel reduction statement is not `acc = acc op e`")
                if isinstance(v.left, ir.Local) and v.left.name == acc:
                    other = v.right
                elif isinstance(v.right, ir.Local) and v.right.name == acc:
                    other = v.left
                else:
                    raise CGenError(f"{f.name}: parallel reduction of {acc} does not read {acc}")
                reds.append((acc, opn, other))
                self._par_collect(other, scalars, arrays)
            if any(x in scalars for x, _, _ in reds):
                raise CGenError(f"{f.name}: a parallel reduction reads its accumulator")
        else:
            for st in s.body:
                if not isinstance(st, ir.StoreIndex):
                    raise CGenError(f"{f.name}: parallel loop body has a {type(st).__name__}")
                arrays.add(st.array)
                self._par_collect(st.value, scalars, arrays)
        scalars.discard(s.var)
        for a in arrays:
            if a not in self.arrays and a not in self.local_arrays:
                raise CGenError(f"{f.name}: parallel loop array {a} is not an array")
            if a in self.arrays and self.arrays[a].buffer:
                raise CGenError(f"{f.name}: parallel loop over buffer array {a}")
        for x in scalars:
            if self.types.get(x) not in (Type.F64, Type.I64):
                raise CGenError(f"{f.name}: parallel loop scalar {x} is not f64/i64")
        k = len(self.par_defs)
        tag = _ident("tp_par", f"{f.name}{'_fast' if self.fast else ''}_{k}")
        ctx_t, body_f = f"{tag}_ctx", f"{tag}_body"
        fields = ["    int64_t tp_ts;"]
        decls = ["    int64_t " + _ident("l", s.var) + " = 0;"]
        sets = []
        for a in sorted(arrays):
            at = self.arrays[a].type if a in self.arrays else self.local_arrays[a]
            ident = _ident("l", a)
            fields.append(f"    {_ARRAY_CT[at]} *{ident};")
            if a in self.arrays:                           # a parameter is already a pointer
                decls.append(f"    {_ARRAY_CT[at]} *{ident} = c->{ident};")
                sets.append(f"tp_pr.{ident} = {ident};")
            else:                                          # a local array is a struct: the worker's copy
                decls.append(f"    {_ARRAY_CT[at]} {ident} = *c->{ident};")
                sets.append(f"tp_pr.{ident} = &{ident};")
        for x in sorted(scalars):
            ident, ct = _ident("l", x), _CT[self.types[x]]
            fields.append(f"    {ct} {ident};")
            decls.append(f"    {ct} {ident} = c->{ident};")
            sets.append(f"tp_pr.{ident} = {ident};")
        if reds:
            fields += ["    unsigned char *tp_pst;"] + [f"    int64_t *tp_part{j};" for j in range(len(reds))]
        saved = (self.lines, self.temps, self.depth, self.brk)
        self.lines, self.temps, self.depth, self.brk = [], [], 2, []
        try:
            self.stmts(s.body)
            body_lines, body_temps = self.lines, self.temps
        finally:
            self.lines, self.temps, self.depth, self.brk = saved
        text = "\n".join(body_lines)
        if reds:
            # the one failure path a reduction body has: the sequential overflow check, which here
            # marks the chunk bad (the worker's own tp_rc / tp_exit); nothing else may remain
            text = re.sub(r"^\s*tp_s = tp_(add|sub|mul)_i64\(.*\);\n?", "", text, flags=re.M)
            text = re.sub(r"^\s*if \(tp_s != 0\) \{ tp_rc = tp_s; goto tp_exit; \}\n?", "", text, flags=re.M)
        for banned in ("goto", "tp_rc", "tp_s ", "return", "tp_st", "tp_dict", "PyObject", "tp_release"):
            if banned in text:
                raise CGenError(f"{f.name}: parallel loop body emitted `{banned}`")
        sign_var = _ident("l", s.var)
        out = [f"typedef struct {{", *fields, f"}} {ctx_t};", "",
               f"static void {body_f}(const void *tp_c, unsigned tp_ci, uint64_t tp_k0, uint64_t tp_k1)", "{",
               f"    const {ctx_t} *c = (const {ctx_t} *)tp_c;",
               "    uint64_t tp_k;", *decls]
        if reds:
            out += ["    int tp_rc = 0;", "    int tp_s = 0;"]
            out += [f"    int64_t {_ident('l', acc)} = {'1' if opn == 'MUL' else '0'};" for acc, opn, _ in reds]
            out.append("    (void)tp_rc; (void)tp_s;")
        for ct, n in body_temps:
            out.append(f"    {ct}{'' if ct.endswith('*') else ' '}{n} = 0;")
        out.append("    (void)c;" + "".join(f" (void){_ident('l', x)};" for x in sorted(scalars)))
        out.append(f"    for (tp_k = tp_k0; tp_k < tp_k1; tp_k++) {{")
        out.append(f"        {sign_var} = (int64_t)((uint64_t)c->tp_ts + tp_k * UINT64_C({mag}));")
        out += body_lines
        out.append("    }")
        if reds:
            out += [f"    c->tp_part{j}[tp_ci] = {_ident('l', acc)};" for j, (acc, _, _) in enumerate(reds)]
            out += ["    c->tp_pst[tp_ci] = TP_PAR_OK;", "    return;", "tp_exit:",
                    "    c->tp_pst[tp_ci] = TP_PAR_BAD;"]
        out += ["}", ""]
        self.par_defs.append("\n".join(out))
        w = max(1, info.weight)
        pok = self.tmp("int") if reds else None
        if reds:
            self.emit(f"{pok} = 0;")
        self.emit(f"if ({tn} >= (uint64_t)((TP_PAR_MIN_WORK + {w - 1}) / {w}) && tp_par_threads() > 1) {{")
        self.depth += 1
        self.emit(f"{ctx_t} tp_pr;")
        if reds:
            self.emit("unsigned char tp_pst[TP_PAR_HARD_CAP] = {0};")
            for j in range(len(reds)):
                self.emit(f"int64_t tp_pp{j}[TP_PAR_HARD_CAP];")
        self.emit(f"tp_pr.tp_ts = {ts};")
        for line in sets:
            self.emit(line)
        if reds:
            self.emit("tp_pr.tp_pst = tp_pst;")
            for j in range(len(reds)):
                self.emit(f"tp_pr.tp_part{j} = tp_pp{j};")
        self.emit(f"(void)tp_par_run({body_f}, &tp_pr, {tn});")
        if reds:
            # Combine in chunk order with the sequential checks. All or none: an overflow anywhere
            # (a chunk, or here) leaves every accumulator untouched and the sequential loop runs.
            self.emit(f"{pok} = 1;")
            for j, (acc, opn, _) in enumerate(reds):
                self.emit(f"int64_t tp_rv{j} = {_ident('l', acc)};")
                self.emit(f"{pok} = {pok} && tp_par_combine(TP_PAR_OP_{opn}, &tp_rv{j}, tp_pp{j}, tp_pst) == 0;")
            self.emit(f"if ({pok}) {{")
            for j, (acc, _, _) in enumerate(reds):
                self.emit(f"    {_ident('l', acc)} = tp_rv{j};")
            self.emit(f"    {_ident('l', s.var)} = (int64_t)((uint64_t){ts} + ({tn} - 1u) * UINT64_C({mag}));")
            self.emit("} else {")
            self.emit("    tp_par_rerun_note();")
            self.emit("}")
            self.depth -= 1
            self.emit("}")
            self.emit(f"if (!{pok}) {{")
            return
        self.emit(f"{_ident('l', s.var)} = (int64_t)((uint64_t){ts} + ({tn} - 1u) * UINT64_C({mag}));")
        self.depth -= 1
        self.emit("} else {")

    def st_Return(self, s: ir.Return) -> None:
        rt = self.f.returns
        if rt is Type.NONE:
            if s.value is not None and not (isinstance(s.value, ir.Const) and s.value.value is None):
                raise CGenError(f"{self.f.name}: returns None but Return has a value")
            self.emit("tp_rc = 0; goto tp_exit;")
            return
        if s.value is None or s.value.type is not rt:
            raise CGenError(f"{self.f.name}: Return type mismatch (function returns {rt})")
        v = self.ev(s.value)
        if rt is Type.OBJ:
            self._own_move_out(v)
        else:
            self.emit(f"*tp_out = {v};")
        self.emit("tp_rc = 0; goto tp_exit;")

    def st_Break(self, s) -> None:
        target = self.brk[-1] if self.brk else None
        if target:
            self.used_labels.add(target)
        self.emit(f"goto {target};" if target else "break;")

    def st_Continue(self, s) -> None:
        self.emit("continue;")

    # whole function

    def impl(self) -> str:
        f = self.f
        prologue = []
        for p in f.params:
            if not isinstance(p, ir.ArrayParam) and p.type is Type.OBJ:
                # adopt the borrowed argument: the local owns its own reference (rule 1)
                prologue.append(f"    {_ident('l', p.name)} = Py_NewRef({_ident('p', p.name)});")
        self.stmts(f.body)
        if f.returns is Type.NONE:
            self.emit("tp_rc = 0;")
        else:
            self.emit(f'PyErr_SetString(PyExc_SystemError, {c_string("compiled function " + f.name + " ended without a return")});')
            self.emit("tp_rc = -1;")
        self.emit("goto tp_exit;")

        out = [_impl_signature(f, fast=self.fast), "{",
               "    int tp_rc = 0;",
               "    int tp_s = 0;",
               "    int tp_pc = TP_POLL_INTERVAL; /* loop poll countdown (#141) */",
               ]
        body_text = "\n".join(self.lines)
        if not self.fast or "tp_st" in body_text:
            out.append("    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);")
        if not self.fast or "tp_dict" in body_text:
            out.append("    PyObject *tp_dict = PyModule_GetDict(tp_module); /* borrowed: owned by the module */")
        params = {p.name for p in f.params} | {g.name for g in f.entry_globals}
        for name, t in self.local_arrays.items():
            out.append(f"    {_ARRAY_CT[t]} {_ident('l', name)} = {{0}};")
        for name, t in self.types.items():
            if name in params and t is not Type.OBJ:
                continue
            init = "NULL" if t is Type.OBJ else "{0, NULL}" if t is Type.INT else "0"
            ct = _CT[t]
            out.append(f"    {ct}{'' if ct.endswith('*') else ' '}{_ident('l', name)} = {init};")
        for ct, n in self.temps:
            init = ("NULL" if ct == "PyObject *" else "{0}" if ct in _CT_ARRAY_REV
                    else "{0, NULL}" if ct == "tp_int" else "0")
            sep = "" if ct.endswith("*") else " "
            out.append(f"    {ct}{sep}{n} = {init};")
        # (a fast twin declares tp_st / tp_dict only when its body reads them: PyModule_GetState and
        # PyModule_GetDict are external calls, and a leaf must stay free of them to inline well)
        out.append("    (void)tp_s; (void)tp_pc;" + (" (void)tp_fd;" if self.fast else " (void)tp_ts;") + " (void)tp_t;"
                   + (" (void)tp_st;" if not self.fast or "tp_st" in body_text else "")
                   + (" (void)tp_dict;" if not self.fast or "tp_dict" in body_text else "")
                   + "".join(f" (void){_ident('l', n)};" for n in self.types if n not in params)
                   + "".join(f" (void){_ident('l', n)};" for n in self.local_arrays))
        # Depth guard (issue #57): nothing is owned yet, so a refusal returns directly and counts
        # nothing; every other exit goes through tp_exit, which leaves exactly once.
        for p in f.params:
            if isinstance(p, ir.Param) and p.cls is not None:
                k = self._class(p.cls)
                out.append(f"    if ({_param_cls_fail(_ident('p', p.name), k, p.optional, f.owner is not None and p is f.params[0])}) return 1;")
        k_bound = self.mg.bounded.get(f.name)
        if self.fast:
            # Fast twin of a bounded function (module docstring, "Fast entry"): the caller proved
            # room for every frame this call tree can have, so there is nothing to count or refuse.
            pass
        else:
            if k_bound is not None:
                # Precheck: room for the k frames of this whole call tree, then run its uncounted
                # twin. Without room, fall through to the counted path below, which raises
                # RecursionError at exactly the call where the interpreter would.
                out.append(f"    if (tp_depth_room(tp_ts, tp_t, {k_bound})) {{")
                out.append("        int tp_fr;")
                out.append("        tp_trace_fast_hit();")
                out.append(f"        tp_fr = {_ident('tp_fimpl', f.name)}({_impl_call_args(f, '1')});")
                out.append("        tp_depth_done(tp_t);")
                out.append("        return tp_fr;")
                out.append("    }")
            out.append("    if (tp_enter_call(tp_ts, tp_t) != 0) return -1;")
        # Eval breaker (#141): a snapshot-holding function never polls and defers others' polls.
        if self.snapshot:
            out.append("    tp_t->snapshot_depth++;")
        elif not self.fast:
            out.append("    if (tp_poll(tp_t, tp_st->breaker) != 0) { tp_rc = -1; goto tp_exit; }")
        out += prologue
        out += self.lines
        out.append("tp_exit:")
        out += self._own_release_all()
        if self.snapshot:
            out.append("    tp_t->snapshot_depth--;")
        if not self.fast:
            out.append("    tp_leave_call(tp_t);")
        out.append("    return tp_rc;")
        out.append("}")
        out.append("")
        return "\n".join(out)

    def generate(self) -> str:
        """The impl (preceded by its fast twin when bounded) and the wrapper."""
        parts = []
        if self.f.name in self.mg.bounded:
            twin = _FnGen(self.mg, self.f, fast=True)
            twin_text = twin.impl()
            parts += twin.par_defs
            parts.append(twin_text)
        impl_text = self.impl()
        parts += self.par_defs
        parts.append(impl_text)
        parts.append(self.wrapper())
        return "\n".join(parts)

    def wrapper(self) -> str:
        f = self.f
        name_k = self.mg.name_index(f.name)
        n = len(f.params)
        L = [f"static PyObject *{_ident('tp_wrap', f.name)}(PyObject *tp_module, PyObject *const *tp_args, "
             "Py_ssize_t tp_nargs, PyObject *tp_kwnames)", "{",
             "    int tp_s = 0, tp_x = 0;",
             "    PyThreadState *tp_ts = PyThreadState_GetUnchecked(); /* the one lookup of this call (#125) */",
             "    tp_tls *tp_t = tp_tls_get(); /* and its one TLS-address lookup (#125) */"]
        if f.returns not in (Type.NONE,):
            init = "NULL" if f.returns is Type.OBJ else "0"
            L.append(f"    {_CT[f.returns]} tp_r = {init};")
        args = []
        arrays = []         # list parameters: copy-in / write-back under a critical section
        buffers = []        # buffer parameters: a Py_buffer export held for the whole call (#149)
        if any(isinstance(p, ir.ArrayParam) and p.buffer is None for p in f.params):
            L.append("    tp_cs tp_lock = {0};")
        for i, p in enumerate(f.params):
            if isinstance(p, ir.ArrayParam):
                L.append(f"    {'tp_buf' if p.buffer else _ARRAY_CT[p.type]} tp_a{i} = {{0}};")
                args.append(f"&tp_a{i}")
                (buffers if p.buffer else arrays).append((i, p))
            elif p.type is Type.OBJ:
                args.append(f"tp_args[{i}]")            # borrowed for the call's duration
            else:
                L.append(f"    {_CT[p.type]} tp_a{i} = 0;")
                args.append(f"tp_a{i}")
        if f.returns is not Type.NONE:
            args.append("&tp_r")
        # PyBuffer_Release first (cannot fail), then the lists' write-back and the critical section
        exits = [f"    tp_buf_exit(&tp_a{i});" for i, p in buffers]
        exits += [f"    if ({_ARRAY_PFX[p.type]}_exit(&tp_a{i}) < 0) tp_x = -1;" for i, p in arrays]
        if arrays:
            exits.append("    tp_cs_end(&tp_lock);       /* after the write-back; a no-op if never taken */")
        has_exits = bool(arrays or buffers)
        L.append("    (void)tp_x;")
        # arity / keywords -> the interpreted function raises CPython's own TypeError
        # tracing, profiling or monitoring active (SPEC N-14): the interpreted original, before anything
        L.append(f"    if ({_traced_call(name_k)}) goto tp_deopt;")
        L.append(f"    if (tp_nargs != {n} || (tp_kwnames != NULL && PyTuple_GET_SIZE(tp_kwnames) != 0)) goto tp_deopt;")
        # fixed-layout classes: not compiled -> interpreted; Param.cls guards (before any effect)
        classes = self.mg.uses(f)
        cls_params = [(i, p) for i, p in enumerate(f.params)
                      if isinstance(p, ir.Param) and p.cls is not None]
        for cname in classes:
            self.mg.cls(f.name, cname)
        if classes:
            L.insert(3, "    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);")
            L.append("    if (!(" + " && ".join(f"tp_st->cls_ok[{self.mg.class_index[n]}]"
                                                 for n in classes) + ")) goto tp_deopt;")
        for i, p in cls_params:
            L.append(f"    if ({_param_cls_fail(f'tp_args[{i}]', self._class(p.cls), p.optional, f.owner is not None and i == 0)}) goto tp_deopt;")
        if classes and f.entry_globals and not f.pure:
            # module docstring, rule 8: a closed impure function must not reach a slow path
            for cname in classes:
                L.append(f"    tp_s = {self._current('NULL', cname)};")
                L.append("    if (tp_s < 0) goto tp_error;")
                L.append("    if (!tp_s) goto tp_deopt;")
            for cname in self.mg.globals_read(f):
                L.append(f"    tp_s = {self._global_ok(cname)};")
                L.append("    if (tp_s < 0) goto tp_error;")
                L.append("    if (!tp_s) goto tp_deopt;")
        # scalar guards: exact type (and i64 range)
        for i, p in enumerate(f.params):
            if not isinstance(p, ir.ArrayParam) and p.type in _UNBOX:
                L.append(f"    tp_s = {_UNBOX[p.type]}(tp_args[{i}], &tp_a{i});")
                L.append("    if (tp_s < 0) goto tp_error;")
                L.append("    if (tp_s != 0) goto tp_deopt;")
        # buffer arrays: exact type, then one Py_buffer export each, held until every exit runs
        # `exits` (a failed guard leaves the struct zeroed, so its exit is a no-op)
        for i, p in buffers:
            fmt1, fmt2 = _BUFFER_FMT[p.type]
            kind = _BUFFER_KINDS[p.buffer]
            enter = "tp_buf_enter_np" if p.buffer == "numpy" else "tp_buf_enter"
            L.append(f"    tp_s = {enter}(tp_args[{i}], "
                     f"((tp_state *)PyModule_GetState(tp_module))->buf_types[{kind}], "
                     f"{fmt1}, {fmt2}, {int(p.stored)}, &tp_a{i});")
            L.append("    if (tp_s < 0) goto tp_error;")
            L.append("    if (tp_s != 0) goto tp_deopt;")
        # aliasing guard, then copy-in
        if len(arrays) > 1:
            objs = ", ".join(f"tp_args[{i}]" for i, _ in arrays)
            L.append(f"    {{ PyObject *const tp_objs[{len(arrays)}] = {{{objs}}};")
            L.append(f"      if (tp_any_same(tp_objs, {len(arrays)})) goto tp_deopt; }}")
        if arrays:
            # Free-threaded (#159): the lists' critical section(s) from before the first copy-in to
            # after the last write-back (every exit runs `exits`). A non-list argument deopts here,
            # as tp_*_array_enter would, without being locked.
            L.append("    if (" + " || ".join(f"!PyList_CheckExact(tp_args[{i}])" for i, _ in arrays)
                     + ") goto tp_deopt;")
            second = f"tp_args[{arrays[1][0]}]" if len(arrays) == 2 else "NULL"
            L.append(f"    tp_cs_begin(&tp_lock, tp_args[{arrays[0][0]}], {second});")
        for i, p in arrays:
            L.append(f"    tp_s = {_ARRAY_PFX[p.type]}_enter(tp_args[{i}], &tp_a{i});")
            L.append("    if (tp_s < 0) goto tp_error;")
            L.append("    if (tp_s != 0) goto tp_deopt;")
        gnames = []
        if f.entry_globals:
            L.insert(3, "    PyObject *tp_dict = PyModule_GetDict(tp_module); /* borrowed */")
            if not classes:
                L.insert(3, "    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);")
        for j, g in enumerate(f.entry_globals):
            # entry read of a module global: exact type or deopt (still before any effect)
            k = self.mg.name_index(g.name)
            L.append(f"    {_CT[g.type]} tp_g{j} = 0;")
            L.append(f"    {{ PyObject *tp_go = tp_global(tp_dict, tp_st->names[{k}]);")
            L.append("      if (tp_go == NULL) {")
            L.append("          if (!PyErr_ExceptionMatches(PyExc_NameError)) goto tp_error;")
            L.append("          PyErr_Clear(); goto tp_deopt; }")
            L.append(f"      tp_s = {_UNBOX[g.type]}(tp_go, &tp_g{j}); Py_DECREF(tp_go); }}")
            L.append("    if (tp_s < 0) goto tp_error;")
            L.append("    if (tp_s != 0) goto tp_deopt;")
            gnames.append(f"tp_g{j}")
        if gnames:
            at = len(args) - (1 if f.returns is not Type.NONE else 0)
            args[at:at] = gnames
        L.append(f"    tp_s = {_ident('tp_impl', f.name)}(tp_module, tp_ts, tp_t{''.join(', ' + a for a in args)});")
        if f.pure:
            L.append("    if (tp_s == 1) goto tp_deopt;")
        else:
            # cgen proved there is no deopting node in an impure function; never redo after effects
            L.append("    if (tp_s == 1) { PyErr_SetString(PyExc_SystemError, "
                     "\"typedpython: deopt after an effect\"); goto tp_error; }")
        L.append("    if (tp_s < 0) goto tp_error;")
        # ok: write back, then box
        L += exits
        if f.returns is Type.OBJ:
            L.append("    if (tp_x < 0) { tp_release(&tp_r); return NULL; }")
            L.append("    return tp_r;")
        else:
            L.append("    if (tp_x < 0) return NULL;")
            if f.returns is Type.NONE:
                L.append("    return Py_NewRef(Py_None);")
            else:
                L.append(f"    return {_BOX[f.returns]}(tp_r);")
        L.append("tp_error:")
        if has_exits:
            # keep CPython's exception; write-back still runs (partial updates stay visible)
            L.append("    { PyObject *tp_exc = PyErr_GetRaisedException();")
            L += ["  " + e for e in exits]
            L.append("      if (tp_x < 0) { PyObject *tp_exc2 = PyErr_GetRaisedException();")
            L.append("                      PyException_SetContext(tp_exc2, tp_exc); /* steals tp_exc */")
            L.append("                      PyErr_SetRaisedException(tp_exc2); }")
            L.append("      else PyErr_SetRaisedException(tp_exc); }")
        L.append("    return NULL;")
        L.append("tp_deopt:")
        L += exits
        L.append("    if (tp_x < 0) return NULL;")
        L.append(f"    return tp_cg_redo(tp_module, {name_k}, tp_args, tp_nargs, tp_kwnames);")
        L.append("}")
        L.append("")
        return "\n".join(L)


# --- fixed parts of the module -------------------------------------------------------------------

_TEMPLATE_METHOD_TYPE = r'''
/* Compiled methods (module docstring, "Methods"). The type of the object that replaces a function
 * in a compiled class's dict. IMMUTABLE after tp_exec: the type has Py_TPFLAGS_IMMUTABLETYPE and each
 * object's fields are written once, by tp_method_new, before it is published in the class dict. */
typedef PyObject *(*tp_method_fn)(PyObject *module, PyObject *const *args, Py_ssize_t nargs,
                                  PyObject *kwnames);

typedef struct {
    PyObject_HEAD
    vectorcallfunc vectorcall;
    PyObject *module;       /* strong: the extension module (its state holds the class guards) */
    PyObject *func;         /* strong: the original Python function (`__wrapped__`) */
    tp_method_fn fn;        /* the generated wrapper: guards, impl, or the interpreted redo */
} tp_method_obj;

static PyObject *tp_method_vectorcall(PyObject *callable, PyObject *const *args, size_t nargsf,
                                      PyObject *kwnames)
{
    tp_method_obj *m = (tp_method_obj *)callable;
    return m->fn(m->module, args, PyVectorcall_NARGS(nargsf), kwnames);
}

/* Binds like a function (func_descr_get): C.m is the object itself, obj.m a bound method. */
static PyObject *tp_method_get(PyObject *self, PyObject *obj, PyObject *type)
{
    (void)type;
    if (obj == NULL || obj == Py_None) {
        return Py_NewRef(self);
    }
    return PyMethod_New(self, obj);
}

/* The attributes a function shows are the original function's (the five of SPEC N-11 / API.md
 * "Methods"); `__wrapped__` is a member. Looked up before the type's own `__doc__` / `__module__`. */
static PyObject *tp_method_getattro(PyObject *self, PyObject *name)
{
    if (PyUnicode_Check(name)
        && (PyUnicode_CompareWithASCIIString(name, "__name__") == 0
            || PyUnicode_CompareWithASCIIString(name, "__qualname__") == 0
            || PyUnicode_CompareWithASCIIString(name, "__doc__") == 0
            || PyUnicode_CompareWithASCIIString(name, "__module__") == 0)) {
        return PyObject_GetAttr(((tp_method_obj *)self)->func, name);
    }
    return PyObject_GenericGetAttr(self, name);
}

static PyObject *tp_method_repr(PyObject *self)
{
    PyObject *q = PyObject_GetAttrString(((tp_method_obj *)self)->func, "__qualname__"), *r;
    if (q == NULL) {
        return NULL;
    }
    r = PyUnicode_FromFormat("<compiled function %S at %p>", q, (void *)self);
    Py_DECREF(q);
    return r;
}

static int tp_method_traverse(PyObject *self, visitproc visit, void *arg)
{
    tp_method_obj *m = (tp_method_obj *)self;
    Py_VISIT(Py_TYPE(self));
    Py_VISIT(m->module);
    Py_VISIT(m->func);
    return 0;
}

static int tp_method_clear(PyObject *self)
{
    tp_method_obj *m = (tp_method_obj *)self;
    Py_CLEAR(m->module);
    Py_CLEAR(m->func);
    return 0;
}

static void tp_method_dealloc(PyObject *self)
{
    PyTypeObject *tp = Py_TYPE(self);
    PyObject_GC_UnTrack(self);
    (void)tp_method_clear(self);
    tp->tp_free(self);
    Py_DECREF(tp);
}

static PyMemberDef tp_method_members[] = {
    {"__vectorcalloffset__", Py_T_PYSSIZET, offsetof(tp_method_obj, vectorcall), Py_READONLY, NULL},
    {"__wrapped__", Py_T_OBJECT_EX, offsetof(tp_method_obj, func), Py_READONLY, NULL},
    {NULL, 0, 0, 0, NULL}
};

static PyType_Slot tp_method_slots[] = {
    {Py_tp_dealloc, (void *)tp_method_dealloc},
    {Py_tp_traverse, (void *)tp_method_traverse},
    {Py_tp_clear, (void *)tp_method_clear},
    {Py_tp_repr, (void *)tp_method_repr},
    {Py_tp_getattro, (void *)tp_method_getattro},
    {Py_tp_descr_get, (void *)tp_method_get},
    {Py_tp_call, (void *)PyVectorcall_Call},
    {Py_tp_members, (void *)tp_method_members},
    {0, NULL}
};

static PyType_Spec tp_method_spec = {
    "typedpython.compiled_function", sizeof(tp_method_obj), 0,
    Py_TPFLAGS_DEFAULT | Py_TPFLAGS_HAVE_GC | Py_TPFLAGS_IMMUTABLETYPE
        | Py_TPFLAGS_DISALLOW_INSTANTIATION | Py_TPFLAGS_METHOD_DESCRIPTOR
        | Py_TPFLAGS_HAVE_VECTORCALL,
    tp_method_slots
};

/* Install the descriptor for method `name` of `cls`: the class's own entry must be a plain Python
 * function. The original is stored in `table` under `qname` whatever it is. 0 = installed (*out is
 * the strong reference the module state keeps), 1 = not installed (absent or not a function: the
 * class is then not compiled), -1 = error. Init time only. */
static int tp_method_install(PyObject *type, PyObject *module, PyObject *cls, const char *name,
                             PyObject *qname, tp_method_fn fn, PyObject *table, PyObject **out)
{
    PyObject *orig = NULL;
    tp_method_obj *d = NULL;
    int found, rc = -1;

    found = tp_own_attr((PyTypeObject *)cls, name, &orig);
    if (found < 0) {
        return -1;
    }
    if (found == 0) {
        return 1;
    }
    if (PyDict_SetItem(table, qname, orig) < 0) {
        goto done;
    }
    if (!PyFunction_Check(orig)) {      /* exactly a Python function: no decorator wrapper */
        rc = 1;
        goto done;
    }
    d = PyObject_GC_New(tp_method_obj, (PyTypeObject *)type);
    if (d == NULL) {
        goto done;
    }
    d->vectorcall = tp_method_vectorcall;
    d->module = Py_NewRef(module);
    d->func = orig;
    orig = NULL;                        /* moved into the descriptor */
    d->fn = fn;
    PyObject_GC_Track((PyObject *)d);
    if (PyObject_SetAttrString(cls, name, (PyObject *)d) < 0) {
        Py_DECREF((PyObject *)d);
        goto done;
    }
    *out = (PyObject *)d;               /* the module state keeps the reference */
    rc = 0;
done:
    Py_XDECREF(orig);
    return rc;
}
'''

_TEMPLATE_REDO = r'''
/* Deopt: count it, then call __typedpython_interpreted__[name] with the original arguments. */
static PyObject *tp_cg_redo(PyObject *tp_module, int tp_name, PyObject *const *tp_args,
                            Py_ssize_t tp_nargs, PyObject *tp_kwnames)
{
    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);
    PyObject *tp_dict = PyModule_GetDict(tp_module); /* borrowed: owned by the module */
    PyObject *tp_count = NULL, *tp_one = NULL, *tp_next = NULL, *tp_table = NULL, *tp_f = NULL;
    PyObject *tp_result = NULL;
    int tp_k;
    tp_one = PyLong_FromLong(1);
    if (tp_one == NULL) goto tp_done;
    /* The counter is a read-modify-write of a module global: under the module dict's critical
     * section, or concurrent deopts lose counts (#159). */
    tp_k = 0;
    Py_BEGIN_CRITICAL_SECTION(tp_dict);
    tp_k = PyDict_GetItemStringRef(tp_dict, "__typedpython_deopts__", &tp_count);
    if (tp_k == 0) PyErr_SetString(PyExc_RuntimeError, "typedpython: __typedpython_deopts__ is missing");
    if (tp_k > 0) {
        tp_next = PyNumber_Add(tp_count, tp_one);
        if (tp_next == NULL || PyDict_SetItemString(tp_dict, "__typedpython_deopts__", tp_next) < 0) tp_k = -1;
    }
    Py_END_CRITICAL_SECTION();
    if (tp_k <= 0) goto tp_done;
    tp_k = PyDict_GetItemStringRef(tp_dict, "__typedpython_interpreted__", &tp_table);
    if (tp_k == 0) PyErr_SetString(PyExc_RuntimeError, "typedpython: __typedpython_interpreted__ is missing");
    if (tp_k <= 0) goto tp_done;
    if (!PyDict_Check(tp_table)) {
        PyErr_SetString(PyExc_TypeError, "typedpython: __typedpython_interpreted__ is not a dict");
        goto tp_done;
    }
    tp_k = PyDict_GetItemRef(tp_table, tp_st->names[tp_name], &tp_f);
    if (tp_k == 0) PyErr_Format(PyExc_RuntimeError, "typedpython: no interpreted function %R", tp_st->names[tp_name]);
    if (tp_k <= 0) goto tp_done;
    tp_result = PyObject_Vectorcall(tp_f, tp_args, (size_t)tp_nargs, tp_kwnames);
tp_done:
    tp_release(&tp_count); tp_release(&tp_one); tp_release(&tp_next);
    tp_release(&tp_table); tp_release(&tp_f);
    return tp_result;
}
'''

def _state_functions(mg: _ModGen) -> str:
    """tp_traverse / tp_clear / tp_free of the module state. tp_clear also drops the dict watcher
    (module docstring, rule 5) while the module dict still exists: CPython calls m_clear and m_free
    before it releases md_dict (moduleobject.c module_clear / module_dealloc)."""
    L = [
        "static int tp_traverse(PyObject *tp_module, visitproc visit, void *arg)",
        "{",
        "    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);",
        "    size_t i;",
        "    if (tp_st == NULL) return 0;",
        "    Py_VISIT(tp_st->breaker);",
        "    Py_VISIT(tp_st->builtins);",
        "    for (i = 0; i < sizeof(tp_st->ints) / sizeof(tp_st->ints[0]); i++) Py_VISIT(tp_st->ints[i]);",
        "    Py_VISIT(tp_st->mtype);",
        "    for (i = 0; i < sizeof(tp_st->mdescrs) / sizeof(tp_st->mdescrs[0]); i++) Py_VISIT(tp_st->mdescrs[i]);",
        "    for (i = 0; i < sizeof(tp_st->buf_types) / sizeof(tp_st->buf_types[0]); i++) Py_VISIT(tp_st->buf_types[i]);",
        "    for (i = 0; i < sizeof(tp_st->names) / sizeof(tp_st->names[0]); i++) Py_VISIT(tp_st->names[i]);",
        "    for (i = 0; i < sizeof(tp_st->orig_code) / sizeof(tp_st->orig_code[0]); i++) Py_VISIT(tp_st->orig_code[i]);",
        "    for (i = 0; i < sizeof(tp_st->kwnames) / sizeof(tp_st->kwnames[0]); i++) Py_VISIT(tp_st->kwnames[i]);",
        "    for (i = 0; i < sizeof(tp_st->classes) / sizeof(tp_st->classes[0]); i++) Py_VISIT(tp_st->classes[i]);",
        "    for (i = 0; i < sizeof(tp_st->descrs) / sizeof(tp_st->descrs[0]); i++) Py_VISIT(tp_st->descrs[i]);",
        "    for (i = 0; i < sizeof(tp_st->cls_rt) / sizeof(tp_st->cls_rt[0]); i++) Py_VISIT(tp_st->cls_rt[i].init);",
        "    return 0;",
        "}",
        "",
        "static int tp_clear(PyObject *tp_module)",
        "{",
        "    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);",
        "    size_t i;",
        "    if (tp_st == NULL) return 0;",
        "    tp_globals_unwatch_builtins(tp_st->builtins, tp_st->watch_id1);",
        "    if (tp_st->watch_id1 > 0) tp_globals_unwatch(PyModule_GetDict(tp_module), &tp_st->watch_id1);",
        "    tp_release(&tp_st->breaker);",
        "    tp_release(&tp_st->builtins);",
        "    for (i = 0; i < sizeof(tp_st->ints) / sizeof(tp_st->ints[0]); i++) tp_release(&tp_st->ints[i]);",
        "    for (i = 0; i < sizeof(tp_st->gc) / sizeof(tp_st->gc[0]); i++) tp_gcache_clear(&tp_st->gc[i]);",
        "    for (i = 0; i < sizeof(tp_st->mdescrs) / sizeof(tp_st->mdescrs[0]); i++) tp_release(&tp_st->mdescrs[i]);",
        "    tp_release(&tp_st->mtype);",
        "    for (i = 0; i < sizeof(tp_st->buf_types) / sizeof(tp_st->buf_types[0]); i++) tp_release(&tp_st->buf_types[i]);",
        "    for (i = 0; i < sizeof(tp_st->orig_code) / sizeof(tp_st->orig_code[0]); i++) tp_release(&tp_st->orig_code[i]);",
        "    for (i = 0; i < sizeof(tp_st->names) / sizeof(tp_st->names[0]); i++) tp_release(&tp_st->names[i]);",
        "    for (i = 0; i < sizeof(tp_st->kwnames) / sizeof(tp_st->kwnames[0]); i++) tp_release(&tp_st->kwnames[i]);",
        "    for (i = 0; i < sizeof(tp_st->cls_ok) / sizeof(tp_st->cls_ok[0]); i++) tp_st->cls_ok[i] = 0;",
        "    for (i = 0; i < sizeof(tp_st->classes) / sizeof(tp_st->classes[0]); i++) tp_release(&tp_st->classes[i]);",
        "    for (i = 0; i < sizeof(tp_st->cls_rt) / sizeof(tp_st->cls_rt[0]); i++) tp_class_rt_clear(&tp_st->cls_rt[i], NULL, 0);",
        "    for (i = 0; i < sizeof(tp_st->descrs) / sizeof(tp_st->descrs[0]); i++) tp_release(&tp_st->descrs[i]);",
        "    return 0;",
        "}",
        "",
        "static void tp_free(void *tp_module) { (void)tp_clear((PyObject *)tp_module); }",
        "",
    ]
    return "\n".join(L)


def _par_stats_function() -> str:
    """`__typedpython_parallel__()`: (regions that used workers, workers started, failed thread
    creations, reduction regions rerun sequentially after an overflow) since import, so a test can prove the parallel path ran (SPEC N-15)."""
    return "\n".join([
        "static PyObject *tp_par_stats_fn(PyObject *tp_module, PyObject *tp_unused)",
        "{",
        "    int64_t tp_v[4];",
        "    (void)tp_module; (void)tp_unused;",
        "    tp_par_stats(tp_v);",
        "    return Py_BuildValue(\"(LLLL)\", (long long)tp_v[0], (long long)tp_v[1], (long long)tp_v[2], (long long)tp_v[3]);",
        "}",
        "",
        "static PyMethodDef tp_par_stats_def = {\"__typedpython_parallel__\", tp_par_stats_fn, METH_NOARGS, NULL};",
        "",
    ])


def _class_info_function(mg: _ModGen) -> str:
    """`__typedpython_class_info__()`: {class name: {compiled, captured_tag, tag, current, slow,
    refreshed, watched}}, introspection of the guards (module docstring)."""
    L = [
        "static PyObject *tp_class_info(PyObject *tp_module, PyObject *tp_unused)",
        "{",
        "    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);",
        "    PyObject *tp_out = PyDict_New(), *tp_d;",
        "    PyObject *tp_c;",
        "    (void)tp_unused;",
        "    if (tp_out == NULL) return NULL;",
    ]
    for cd in mg.module.classes:
        k = mg.class_index[cd.name]
        L += [
            f"    tp_c = tp_st->classes[{k}];",
            "    tp_d = Py_BuildValue(\"{s:O,s:I,s:I,s:O,s:n,s:n,s:O,s:n,s:O}\",",
            f"        \"compiled\", tp_st->cls_ok[{k}] ? Py_True : Py_False,",
            f"        \"captured_tag\", tp_class_rt_tag(&tp_st->cls_rt[{k}]),",
            "        \"tag\", tp_class_tag(tp_c),",
            f"        \"current\", (tp_c != NULL && tp_class_tag(tp_c) == tp_class_rt_tag(&tp_st->cls_rt[{k}])) ? Py_True : Py_False,",
            f"        \"slow\", tp_class_rt_slow(&tp_st->cls_rt[{k}]), \"refreshed\", "
            f"tp_class_rt_refreshed(&tp_st->cls_rt[{k}]),",
            "        \"watched\", tp_st->watch_id1 > 0 ? Py_True : Py_False,",
            f"        \"methods\", (Py_ssize_t){len(mg.methods_of[cd.name])},",
            f"        \"methods_current\", (tp_c != NULL && tp_class_tag(tp_c) == tp_atomic_load_u32(&tp_st->cls_rt[{k}].mtag)) ? Py_True : Py_False);",
            f"    if (tp_d == NULL || PyDict_SetItemString(tp_out, {c_string(cd.name)}, tp_d) < 0) {{",
            "        Py_XDECREF(tp_d); Py_DECREF(tp_out); return NULL; }",
            "    Py_DECREF(tp_d);",
        ]
    L += [
        "    return tp_out;",
        "}",
        "",
        "static PyMethodDef tp_class_info_def = {\"__typedpython_class_info__\", tp_class_info, METH_NOARGS, NULL};",
        "",
    ]
    return "\n".join(L)


def _exec_function(mg: _ModGen) -> str:
    L = [
        "/* Exec slot: run the original source in this module's dict, then swap in the C functions. */",
        "static int tp_exec(PyObject *tp_module)",
        "{",
        "    tp_state *tp_st = (tp_state *)PyModule_GetState(tp_module);",
        "    PyObject *tp_dict = PyModule_GetDict(tp_module); /* borrowed: owned by the module */",
        "    PyObject *tp_code = NULL, *tp_res = NULL, *tp_table = NULL, *tp_zero = NULL;",
        "    PyObject *tp_modname = NULL, *tp_f = NULL, *tp_cf = NULL, *tp_c = NULL;",
        "    int tp_rc = -1, tp_k, tp_k2;",
        "    size_t i;",
        "    for (i = 0; tp_name_strings[i] != NULL; i++) {",
        "        tp_st->names[i] = PyUnicode_InternFromString(tp_name_strings[i]);",
        "        if (tp_st->names[i] == NULL) goto tp_done;",
        "    }",
    ]
    for k, kw in enumerate(mg.kwtuples):
        items = ", ".join(f"tp_st->names[{mg.name_index(x)}]" for x in kw)
        L.append(f"    tp_st->kwnames[{k}] = PyTuple_Pack({len(kw)}, {items});")
        L.append(f"    if (tp_st->kwnames[{k}] == NULL) goto tp_done;")
    for k, v in enumerate(mg.int_consts):
        L.append(f"    tp_st->ints[{k}] = PyLong_FromLongLong({_i64_lit(v)});")
        L.append(f"    if (tp_st->ints[{k}] == NULL) goto tp_done;")
    L += [
        "    tp_k = PyDict_ContainsString(tp_dict, \"__builtins__\");",
        "    if (tp_k < 0) goto tp_done;",
        "    if (tp_k == 0) {",
        "        PyObject *tp_b = PyImport_ImportModule(\"builtins\");",
        "        if (tp_b == NULL) goto tp_done;",
        "        tp_k = PyDict_SetItemString(tp_dict, \"__builtins__\", PyModule_GetDict(tp_b));",
        "        tp_release(&tp_b);",
        "        if (tp_k < 0) goto tp_done;",
        "    }",
        "    {   /* the builtins dict the module's functions run with (cached builtin reads, SPEC N-13) */",
        "        PyObject *tp_b = NULL;",
        "        tp_k = PyDict_GetItemStringRef(tp_dict, \"__builtins__\", &tp_b);",
        "        if (tp_k < 0) goto tp_done;",
        "        if (tp_k > 0 && PyModule_Check(tp_b)) {",
        "            PyObject *tp_bd = Py_NewRef(PyModule_GetDict(tp_b));",
        "            tp_release(&tp_b);",
        "            tp_b = tp_bd;",
        "        }",
        "        if (tp_k > 0 && tp_b != NULL && PyDict_Check(tp_b)) tp_st->builtins = tp_b; else tp_release(&tp_b);",
        "    }",
        "    tp_code = Py_CompileString(\"lambda: None\", \"<typedpython eval breaker>\", Py_eval_input);",
        "    if (tp_code == NULL) goto tp_done;",
        "    tp_st->breaker = PyEval_EvalCode(tp_code, tp_dict, tp_dict);",
        "    tp_release(&tp_code);",
        "    if (tp_st->breaker == NULL) goto tp_done;",
        "    tp_code = Py_CompileString(tp_source, tp_source_path, Py_file_input);",
        "    if (tp_code == NULL) goto tp_done;",
        "    tp_res = PyEval_EvalCode(tp_code, tp_dict, tp_dict);",
        "    if (tp_res == NULL) goto tp_done;",
    ]
    kinds = {p.buffer for f in mg.module.functions for p in f.params
             if isinstance(p, ir.ArrayParam) and p.buffer}
    # buffer arrays (#149): the exact types the entry guards compare against, taken after the
    # module source ran (so its own imports are done). Not importable: NULL, and every guard deopts.
    if "array" in kinds:
        L.append('    if (tp_import_attr("array", "array", &tp_st->buf_types[0]) < 0) goto tp_done;')
    if "numpy" in kinds:
        for slot, attr in ((1, "ndarray"), (2, "float64"), (3, "int64"), (4, "bool_")):
            L.append(f'    if (tp_import_attr("numpy", "{attr}", &tp_st->buf_types[{slot}]) < 0) goto tp_done;')
    L += [
        "    tp_table = PyDict_New();",
        "    if (tp_table == NULL || PyDict_SetItemString(tp_dict, \"__typedpython_interpreted__\", tp_table) < 0) goto tp_done;",
    ]
    for cd in mg.module.classes:
        j = mg.class_index[cd.name]
        L += [
            f"    tp_k = PyDict_GetItemStringRef(tp_dict, {c_string(cd.name)}, &tp_c);",
            "    if (tp_k < 0) goto tp_done;",
            "    if (tp_k == 1) {",
            f"        tp_k = tp_class_capture(tp_c, tp_fields_{j}, {len(cd.fields)}, "
            f"&tp_st->slots[{mg.slot_base[cd.name]}], &tp_st->descrs[{mg.slot_base[cd.name]}], "
            f"&tp_st->cls_rt[{j}]);",
            "        if (tp_k < 0) goto tp_done;",
            f"        if (tp_k == 1) {{ tp_st->classes[{j}] = tp_c; tp_c = NULL; tp_st->cls_ok[{j}] = 1; }}",
            "    }",
            "    tp_release(&tp_c);",
        ]
    if mg.n_methods:
        L += [
            "    tp_st->mtype = PyType_FromModuleAndSpec(tp_module, &tp_method_spec, NULL);",
            "    if (tp_st->mtype == NULL) goto tp_done;",
        ]
    for cd in mg.module.classes:
        ms = mg.methods_of[cd.name]
        if not ms:
            continue
        j = mg.class_index[cd.name]
        L += [f"    if (tp_st->cls_ok[{j}]) do {{"]
        for i, f in enumerate(ms):
            L += [
                f"        tp_k = tp_method_install(tp_st->mtype, tp_module, tp_st->classes[{j}], "
                f"{c_string(f.name[len(cd.name) + 1:])}, tp_st->names[{mg.name_index(f.name)}], "
                f"{_ident('tp_wrap', f.name)}, tp_table, &tp_st->mdescrs[{mg.method_base[cd.name] + i}]);",
                "        if (tp_k < 0) goto tp_done;",
                f"        if (tp_k > 0) {{ tp_st->cls_ok[{j}] = 0; break; }}",
            ]
        L += [
            f"        if (!tp_class_retag((PyTypeObject *)tp_st->classes[{j}], &tp_st->cls_rt[{j}])) "
            f"tp_st->cls_ok[{j}] = 0;",
            "    } while (0);",
        ]
    if mg.needs_watch():
        # after the source ran: from here a rebinding of a class name or of a cached global moves
        # tp_globals_epoch; the builtins dict is watched by the same watcher (cached builtin reads)
        L += [
            "    tp_globals_watch(tp_dict, tp_watch_cb, &tp_st->watch_id1);",
            "    if (tp_globals_watch_builtins(tp_st->builtins, tp_st->watch_id1) < 0) {",
            "        /* cannot watch the builtins: a patched builtin would go unseen, so cache nothing */",
            "        tp_globals_unwatch(tp_dict, &tp_st->watch_id1);",
            "    }",
        ]
    if mg.module.classes:
        L += [
            "    tp_cf = PyCFunction_NewEx(&tp_class_info_def, tp_module, NULL);",
            "    if (tp_cf == NULL || PyDict_SetItemString(tp_dict, \"__typedpython_class_info__\", tp_cf) < 0) goto tp_done;",
            "    tp_release(&tp_cf);",
        ]
    if mg.has_par:
        L += [
            "    tp_cf = PyCFunction_NewEx(&tp_par_stats_def, tp_module, NULL);",
            "    if (tp_cf == NULL || PyDict_SetItemString(tp_dict, \"__typedpython_parallel__\", tp_cf) < 0) goto tp_done;",
            "    tp_release(&tp_cf);",
        ]
    L += [
        "    tp_zero = PyLong_FromLong(0);",
        "    if (tp_zero == NULL || PyDict_SetItemString(tp_dict, \"__typedpython_deopts__\", tp_zero) < 0) goto tp_done;",
        "    tp_modname = PyModule_GetNameObject(tp_module);",
        "    if (tp_modname == NULL) goto tp_done;",
    ]
    for j, f in enumerate(mg.plain):
        k = mg.name_index(f.name)
        L += [
            f"    tp_k = PyDict_GetItemRef(tp_dict, tp_st->names[{k}], &tp_f);",
            f"    if (tp_k == 0) PyErr_Format(PyExc_ImportError, \"typedpython: the module source does not define %R\", tp_st->names[{k}]);",
            "    if (tp_k <= 0) goto tp_done;",
            f"    if (PyDict_SetItem(tp_table, tp_st->names[{k}], tp_f) < 0) goto tp_done;",
            f"    tp_cf = PyCFunction_NewEx(&tp_methods[{j}], tp_module, tp_modname);",
            f"    if (tp_cf == NULL || PyDict_SetItem(tp_dict, tp_st->names[{k}], tp_cf) < 0) goto tp_done;",
            "    tp_release(&tp_f); tp_release(&tp_cf);",
        ]
    L += [
        "#ifdef TP_TRACE_FAST",
        f"    tp_cf = PyCFunction_NewEx(&tp_methods[{len(mg.plain)}], tp_module, tp_modname);",
        "    if (tp_cf == NULL || PyDict_SetItemString(tp_dict, \"__tp_trace_fast_hits__\", tp_cf) < 0) goto tp_done;",
        "    tp_release(&tp_cf);",
        "#endif",
        # SPEC N-14: strong references to the originals' code objects, read by tp_traced at every entry
        f"    for (tp_k = 0; tp_k < {len(mg.names)}; tp_k++) {{",
        "        tp_k2 = PyDict_GetItemRef(tp_table, tp_st->names[tp_k], &tp_f);",
        "        if (tp_k2 < 0) goto tp_done;",
        "        if (tp_k2 > 0 && PyFunction_Check(tp_f)) tp_st->orig_code[tp_k] = Py_NewRef(PyFunction_GetCode(tp_f));",
        "        tp_release(&tp_f);",
        "    }",
        "    tp_rc = 0;",
        "tp_done:",
        "    tp_release(&tp_code); tp_release(&tp_res); tp_release(&tp_table); tp_release(&tp_zero);",
        "    tp_release(&tp_modname); tp_release(&tp_f); tp_release(&tp_cf); tp_release(&tp_c);",
        "    return tp_rc;",
        "}",
        "",
    ]
    return "\n".join(L)
