"""The C-ABI library output of the AOT mode (SPEC N-17, #455).

Provenance. That a build can also produce a C-ABI library a Kotlin app links without CPython is owner
decision D5 (2026-11-16, docs/design/typedpython-aot.md section 10). Its shape (lists as counted handles, no
library-wide lock, a status return with out pointers, a thread-local error record, atomic list counts, no
module-level mutable state) is a leader decision of 2026-11-16, not owner-confirmed. Everything else here (names,
the description file, the check of exports) is an agent proposal, not owner-confirmed.

`generate_library(module, exports=[...], prefix="p")` lowers the module in the AOT mode like a program, except
that a library has no entry: an `if __name__ == "__main__":` block is not required, and when there is one it is
not compiled (`program.analyze(library=True)` blanks it, line by line, before anything reads the source). It
returns `(c_text, header_text, description)`. The functions compiled are the exports and every function
reachable from them by a direct call (a name that is called, found in the function's source); a function that
nothing reachable calls is not compiled and not emitted, and a finding in it (a front-end rejection, an
unproved int operation, a construct outside the subset) does not fail the library. A finding in anything
reachable does, exactly as in `aot-build`.

Ints inside the library (#462; design decision D1 (a), leader 2026-11-16, not owner-confirmed; the boundary rule below is
an agent proposal, not owner-confirmed). An `int` parameter arrives as an int64 and is converted where the compiled
function holds it as a big-integer-capable value (`aot/rep.py`); inside, the source language's integer rules hold
exactly. An `int` RESULT (also an element of a tuple) that the function holds that way is converted back with a fit
check: when it does not fit int64 the call fails with -1 and the error record `OverflowError` with the fixed message
"the int result of `<function>` does not fit in 64 bits". That check belongs to this ABI and has no counterpart in the
source language (like the null-handle TypeError); the generated header says so. A library whose ints are all proved
inside 64 bits generates the C text, the header and the JSON it did, except that `abi_version` is 3 (and the comment
at the top of the C text says so).

Lists of big ints (#465, ABI version 2; the static classification and the new list kind: leader decision 2026-11-16,
not owner-confirmed; the names, the messages and the JSON keys are agent proposals, not owner-confirmed). A `list[int]`
that `aot/rep.py` finds big-capable (a TAGGED value can be stored into its group) is not an array of int64: the
parameter or the result of an export that is in such a group has the type `int_list` instead of `i64_list`. Which kind
a signature has follows from the classification and from nothing else; a list that crosses the boundary is one group
with the outside, so the caller can only pass a handle of the kind the signature names. `int_list` is an opaque counted
handle like the others, with these functions and no `_data` (its elements are not int64 in memory):

  <p>_int_list *<p>_int_list_new(int64_t n)                              n zeros (an empty list for n < 0)
  int64_t <p>_int_list_len(const <p>_int_list *l)
  void <p>_int_list_retain(<p>_int_list *l), void <p>_int_list_release(<p>_int_list *l)
  int <p>_int_list_get(const <p>_int_list *l, int64_t i, int64_t *out)   0 or -1 and the error record
  int <p>_int_list_set(<p>_int_list *l, int64_t i, int64_t v)            0 or -1 and the error record

`get` and `set` take the index as the source language does (a negative one counts from the end); out of range is an
IndexError with the two messages of the source language ("list index out of range", "list assignment index out of
range" for `set`). `get` of an element that does not fit int64 is the ABI-level OverflowError "the element of the list
does not fit in 64 bits" (no value is written; the same element reads fine after a `set` of a smaller value); like the
int result check above it has no counterpart in the source language, and the header says so.

Dicts (#443, tier S2b, ABI version 3; agent proposal, not owner-confirmed, the leader decides the shape). A `dict[int, int]`,
`dict[int, float]` or `dict[int, bool]` parameter or result is the opaque counted handle `dict_i64_i64`, `dict_i64_f64` or
`dict_i64_bool` (the runtime's `tpa_dict`, whose int keys and values are always big-capable inside, so one Python type has one
form and there is no raw / big split as for lists). Each kind a signature uses has these functions:

  <p>_<k> *<p>_<k>_new(void)                                   an empty dict; clears the error record
  int <p>_<k>_set(<p>_<k> *d, int64_t key, V value)            d[key] = value
  int <p>_<k>_get(const <p>_<k> *d, int64_t key, V *out)       d[key]; KeyError(key) when absent
  int <p>_<k>_contains(const <p>_<k> *d, int64_t key)          1 or 0
  int <p>_<k>_next(const <p>_<k> *d, int64_t *pos, int64_t *key, V *value)   the items in order from *pos = 0: 1, 0 at the end
  int64_t <p>_<k>_len(const <p>_<k> *d), void _retain, void _release

with V int64_t, double or uint8_t. A key or value outside 64 bits cannot be read: `_get` and `_next` fail with the ABI-level
OverflowError "the key of the dict does not fit in 64 bits" / "the value of the dict does not fit in 64 bits" (`_next` has
moved past that item). A NULL handle is the TypeError "argument 'd' is a null dict handle". The library never writes a dict
parameter of an export (`aot/library-dict-write`, checked through the callees it is passed to, see `dict_read_only`), so one
handle may be read by calls on several threads at once; the caller builds a dict with `_new` and `_set` before sharing it.
Other dicts (str, bool or tuple keys; str, dict or set values; sets; lists of dicts) do not cross the boundary yet.

The exported ABI (every name is `<prefix>_...`; C linkage; default visibility, everything else hidden):

  int <p>_<f>(<params>, <out pointers>)        one per export; 0 on success, -1 on failure
  int <p>_init(void), void <p>_shutdown(void)  nothing to initialise in this tier (module constants are
                                               folded at compile time); they exist so the ABI does not change
  int <p>_last_error(const char **class_name, const char **message)
  <p>_f64_list / <p>_i64_list: _new, _len, _data, _retain, _release
  <p>_int_list: _new, _len, _get, _set, _retain, _release      (only in a library whose signatures use it)
  <p>_dict_i64_<v>: _new, _set, _get, _contains, _next, _len, _retain, _release   (only the kinds the signatures use)

with the types `i64` (int64_t), `f64` (double), `bool` (uint8_t, a value other than 0 or 1 is read as true) and the
opaque counted handles `f64_list`, `i64_list`, `int_list`, `dict_i64_i64`, `dict_i64_f64` and `dict_i64_bool`.

The description file `<prefix>.json` (the input of the Kotlin generator; the keys and the vocabulary are stable
while "abi_version" is 3; version 2 added the type `int_list` and its entry under "lists", version 3 the dict types and
"dicts"):

  {"prefix": "<p>",
   "library": "<file name of the shared library on the platform that built it>",
   "abi_version": 3,
   "runtime": {"init": "<p>_init", "shutdown": "<p>_shutdown", "last_error": "<p>_last_error"},
   "functions": [{"name": "<python name>", "symbol": "<p>_<name>", "line": <line of the def>,
                  "params": [{"name": "<python name>", "type": "<type>"}, ...],
                  "returns": ["<type>", ...]}],
   "lists": {"f64_list": {"element": "f64", "new": "...", "len": "...", "data": "...", "retain": "...",
                          "release": "..."},
             "i64_list": {"element": "i64", ...},
             "int_list": {"element": "i64", "new": "...", "len": "...", "get": "...", "set": "...", "retain": "...",
                          "release": "..."}},
   "dicts": {"dict_i64_f64": {"key": "i64", "value": "f64", "new": "...", "set": "...", "get": "...", "contains": "...",
                              "next": "...", "len": "...", "retain": "...", "release": "..."}, ...}}

"dicts" is there only when a signature uses a dict, with one entry per kind used. "lists" has the "int_list" entry (with "get" and "set" and no "data") only when a signature of the library uses that
type; a library with no big-capable list at its boundary has the JSON it had under version 1 except `abi_version`.
`<type>` is one of `VOCABULARY`. "returns" is the list of out
pointers after the parameters, in order: empty for a function that returns None, one element for an int, a
float, a bool or a list, one per element for a fixed tuple. The C signature follows from it: a `<type>` in
params is passed by value (a list as the handle), a `<type>` in returns is an out pointer to that type (to the
handle for a list).
"""
from __future__ import annotations

import ast
import dataclasses
import json
import re
import sys
from pathlib import Path

from typedpython import ir, resume, tuples
from typedpython.aot import AotError, LibraryFiles, _lower
from typedpython.aot import emit as emit_mod
from typedpython.aot import unicode as unicode_mod
from typedpython.aot.emit import CTYPE, LIST, mangle
from typedpython.aot.program import _is_class_annotation, analyze
from typedpython.aotcheck import Finding
from typedpython.ir import Type

ABI_VERSION = 3
VOCABULARY = ("i64", "f64", "bool", "f64_list", "i64_list", "int_list", "dict_i64_i64", "dict_i64_f64", "dict_i64_bool")
_TYPE_NAME = {Type.I64: "i64", Type.F64: "f64", Type.BOOL: "bool", Type.F64_ARRAY: "f64_list",
              Type.I64_ARRAY: "i64_list"}
_C_TYPE = {"i64": "int64_t", "f64": "double", "bool": "uint8_t"}
_PY_NAME = {"i64": "int", "f64": "float", "bool": "bool"}
_ANN_TYPE = {"int": "i64", "float": "f64", "bool": "bool"}
_LIST_ELEMENT = {"f64_list": "f64", "i64_list": "i64", "int_list": "int"}      # the runtime's name: tpa_<x>_list
# tier S2b (ABI version 3): the dicts that cross the boundary, `dict[int, V]`: the value's boundary type and runtime kind
_DICT_KINDS = {"dict_i64_i64": ("i64", "TPA_DV_INT"), "dict_i64_f64": ("f64", "TPA_DV_F64"),
               "dict_i64_bool": ("bool", "TPA_DV_BOOL")}
_HANDLE = (*_LIST_ELEMENT, *_DICT_KINDS)             # the types passed as an opaque counted handle
_RESERVED_EXPORTS = frozenset({"init", "shutdown", "last_error", "f64_list", "i64_list", "int_list", *_DICT_KINDS})
_RESERVED_EXPORT_PREFIXES = ("f64_list_", "i64_list_", "int_list_", *(f"{k}_" for k in _DICT_KINDS))
_RESERVED_PREFIXES = ("tpa", "tpf", "tp")       # the runtime's own names (`tpa_...`, `tpf_...`, `tp_cleanup`)
_IDENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def library_file_name(prefix: str) -> str:
    """The file name of the shared library on the platform running this build."""
    if sys.platform == "win32":
        return f"{prefix}.dll"
    if sys.platform == "darwin":
        return f"lib{prefix}.dylib"
    return f"lib{prefix}.so"


# --- the names and the exports ---------------------------------------------------------------------

def _boundary_dict(kinds) -> bool:
    """`dict[int, int | float | bool]`: the dicts of ABI version 3 (str, bool and tuple keys, str, dict and set values wait)."""
    return (isinstance(kinds, tuple) and len(kinds) == 2 and kinds[0] is Type.I64
            and kinds[1] in (Type.I64, Type.F64, Type.BOOL))


# --- tier S2b: the library never writes a dict that crossed its boundary --------------------------------------------
#
# A dict parameter of an export may be shared by several threads (the caller passes one handle to calls on several
# threads), and the runtime's dict has no lock (per-object locking is tier T, as for aot/library-resize and
# aot/library-mutable-class). So an exported function may only READ a dict parameter: the parameter may appear only as
# the dict of a read (below), as an argument for a parameter of a callee that is read-only by the same rule, and, in the
# exported function itself, as its return value (the same handle goes back to the caller; a callee that returns its
# parameter hands its caller an alias, so there it is not read-only). Every other use (a store, del, a method that
# changes the dict, an assignment to another name, a display or a field that holds it, a node this list does not name)
# is a write. What this criterion cannot see: nothing, by construction; it can refuse a function that does not write
# (a parameter rebound to a new dict and then written, a use in a node added later), which is the safe side.

_DICT_READS = {
    (ir.DictGet, "dict"), (ir.DictContains, "dict"), (ir.DictLen, "dict"), (ir.DictCompare, "left"),
    (ir.DictCompare, "right"), (ir.DictStr, "value"), (ir.ForDict, "value"), (ir.DictHasValue, "dict"),
    (ir.DictHasItem, "dict"), (ir.DictViewStr, "dict"), (ir.DictView, "dict"), (ir.DictCopy, "value"), (ir.DictUpdate, "other"),
}
_DICT_WRITE_WORDS = {
    ir.DictStore: "a store", ir.DictDelete: "del", ir.DictPop: "pop()", ir.DictPopItem: "popitem()",
    ir.DictSetDefault: "setdefault()", ir.DictClear: "clear()", ir.DictUpdate: "update()", ir.Assign: "an assignment",
    ir.DictListDisplay: "a list that holds it", ir.FieldSet: "a field that holds it",
}


def _uses(node, parent=None, field_name=None):
    """`(parent, field, node)` for every IR node under `node`, the field of the parent it is in."""
    yield parent, field_name, node
    for fl in dataclasses.fields(node):
        v = getattr(node, fl.name)
        for x in (v if isinstance(v, tuple) else (v,)):
            if isinstance(x, (ir.Expr, ir.Stmt)):
                yield from _uses(x, node, fl.name)


def _dict_params(fn: ir.Function) -> list[tuple[int, str]]:
    return [(i, q.name) for i, q in enumerate(fn.params) if isinstance(q, ir.Param) and q.type is Type.DICT]


def _first_write(fn: ir.Function, name: str, ro: dict, export: bool) -> str | None:
    """How `fn` writes its dict parameter `name` (None when it only reads it)."""
    for s in fn.body:
        for parent, fl, n in _uses(s):
            if not (isinstance(n, ir.Local) and n.name == name):
                continue
            if (type(parent), fl) in _DICT_READS:
                continue
            if export and isinstance(parent, ir.Return):
                continue
            if isinstance(parent, ir.Call):
                i = next(j for j, a in enumerate(parent.args) if a is n)
                if ro.get((parent.function, i), False):
                    continue
                return f"passed to `{parent.function}`, which writes it"
            return _DICT_WRITE_WORDS.get(type(parent), "a use other than a read")
        for parent, fl, n in _uses(s):
            if isinstance(n, ir.Assign) and n.target == name:
                return "an assignment to it"
    return None


def dict_read_only(proved: ir.Module, names: set[str]) -> dict[tuple[str, int], bool]:
    """`(function, parameter index) -> True` for each dict parameter that the function and its callees only read (the
    greatest fixpoint: a recursive call of a read-only parameter is read-only)."""
    fns = {f.name: f for f in proved.functions if f.name in names}
    ro = {(f.name, i): True for f in fns.values() for i, _ in _dict_params(f)}
    changed = True
    while changed:
        changed = False
        for f in fns.values():
            for i, p in _dict_params(f):
                if ro[(f.name, i)] and _first_write(f, p, ro, export=False) is not None:
                    ro[(f.name, i)] = False
                    changed = True
    return ro


def dict_write_findings(proved: ir.Module, fn: ir.Function, rel: str, line: int, ro: dict) -> list[Finding]:
    out = []
    for _, p in _dict_params(fn):
        how = _first_write(fn, p, ro, export=True)
        if how is not None:
            out.append(_finding(rel, line, "aot/library-dict-write", fn.name,
                                f"`{fn.name}` cannot be exported: it writes its dict parameter `{p}` ({how}); the library "
                                "never writes a dict that crosses its boundary until tier T adds per-dict locking "
                                "(another thread may be reading the same dict)", fn.name))
    return out


def _finding(rel: str, line: int, rule: str, name: str, message: str, function: str | None = None) -> Finding:
    return Finding(rel, line, "error", None, rule, name, message, function)


def prefix_findings(prefix: str, rel: str) -> list[Finding]:
    """`prefix` must be a C identifier (ASCII) that cannot collide with the runtime's own names."""
    if not isinstance(prefix, str) or _IDENT.fullmatch(prefix) is None:
        return [_finding(rel, 1, "aot/export", str(prefix), f"the prefix `{prefix}` is not a C identifier "
                         "(ASCII letters, digits and `_`, not starting with a digit)")]
    if prefix in _RESERVED_PREFIXES or prefix.startswith(("tpa_", "tpf_", "tp_")) or prefix.startswith("__"):
        return [_finding(rel, 1, "aot/export", prefix, f"the prefix `{prefix}` is reserved for the runtime's own names")]
    return []


def _scalar_or_list(ann: ast.expr | None) -> str | None:
    """`int`, `float`, `bool`, `list[int]`, `list[float]`, `dict[int, int | float | bool]` as the vocabulary names; None for
    anything else."""
    if isinstance(ann, ast.Name) and ann.id in _ANN_TYPE:
        return _ANN_TYPE[ann.id]
    if (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "list"
            and isinstance(ann.slice, ast.Name) and ann.slice.id in ("int", "float")):
        return "i64_list" if ann.slice.id == "int" else "f64_list"
    if (isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "dict"
            and isinstance(ann.slice, ast.Tuple) and len(ann.slice.elts) == 2
            and all(isinstance(e, ast.Name) for e in ann.slice.elts)
            and ann.slice.elts[0].id == "int" and ann.slice.elts[1].id in _ANN_TYPE):      # type: ignore[attr-defined]
        return f"dict_i64_{_ANN_TYPE[ann.slice.elts[1].id]}"                            # type: ignore[attr-defined]
    return None


def _return_types(ann: ast.expr | None) -> list[str] | None:
    """The out pointers a return annotation gives: [] for None, one type, or the elements of a tuple of scalars."""
    if isinstance(ann, ast.Constant) and ann.value is None:
        return []
    one = _scalar_or_list(ann)
    if one is not None:
        return [one]
    if isinstance(ann, ast.Subscript) and isinstance(ann.value, ast.Name) and ann.value.id == "tuple":
        items = ann.slice.elts if isinstance(ann.slice, ast.Tuple) else [ann.slice]
        kinds = [_ANN_TYPE.get(i.id) if isinstance(i, ast.Name) else None for i in items]
        if items and all(kinds):
            return kinds                    # type: ignore[return-value]
    return None


def export_findings(program, exports: list[str]) -> tuple[list[Finding], dict[str, ast.FunctionDef]]:
    """The `aot/export` findings of the export list and the function node of each valid export."""
    rel = program.rel
    found: list[Finding] = []
    defs: dict[str, ast.FunctionDef] = {}
    valid: dict[str, ast.FunctionDef] = {}
    for f in program.functions:
        defs.setdefault(f.name, f)
    seen: set[str] = set()
    if not exports:
        found.append(_finding(rel, 1, "aot/export", "exports", "a library exports at least one function (--export)"))
    for name in exports:
        if name in seen:
            found.append(_finding(rel, 1, "aot/export", name, f"`{name}` is exported twice"))
            continue
        seen.add(name)
        if name in program.consts:
            found.append(_finding(rel, 1, "aot/export", name, f"`{name}` is a module constant, not a function"))
            continue
        fn = defs.get(name)
        if fn is None:
            found.append(_finding(rel, 1, "aot/export", name, f"`{name}` is not a function defined at the top level "
                                  f"of {rel}"))
            continue
        if (_IDENT.fullmatch(name) is None or name in _RESERVED_EXPORTS
                or name.startswith(_RESERVED_EXPORT_PREFIXES)):
            found.append(_finding(rel, fn.lineno, "aot/export", name, f"`{name}` cannot be exported: its C symbol "
                                  "must be an ASCII identifier that is not one of the library's own names "
                                  "(init, shutdown, last_error, f64_list..., i64_list..., int_list..., dict_i64_...)", name))
            continue
        problems = []
        a = fn.args
        str_at = [x for x in [*(arg.annotation for arg in a.args), fn.returns]
                  if isinstance(x, ast.Name) and x.id == "str"]
        if str_at:
            # design 4.3.1: the C-ABI string kind is a later step with its own ABI version
            found.append(_finding(rel, fn.lineno, "aot/library-str", name,
                                  f"`{name}` cannot be exported: a str parameter or result has no C-ABI form yet "
                                  "(the library's string kind comes with its own ABI version)", name))
            continue
        class_names = {c.name for c in getattr(program, "classes", ())}
        for arg in a.args:
            if _scalar_or_list(arg.annotation) is None and not (
                    arg.annotation is not None and _is_class_annotation(arg.annotation, class_names)):
                shown = ast.unparse(arg.annotation) if arg.annotation is not None else "no annotation"
                problems.append(f"parameter `{arg.arg}` ({shown})")
        if _return_types(fn.returns) is None and not (
                fn.returns is not None and _is_class_annotation(fn.returns, class_names)):
            shown = ast.unparse(fn.returns) if fn.returns is not None else "no annotation"
            problems.append(f"the return ({shown})")
        if problems:
            found.append(_finding(rel, fn.lineno, "aot/export", name,
                                  f"`{name}` cannot be exported: {', '.join(problems)} is outside the library "
                                  "boundary (parameters int, float, bool, list[int], list[float], dict[int, int], "
                                  "dict[int, float], dict[int, bool]; the result None, one of those, or a tuple of "
                                  "int, float and bool)", name))
            continue
        valid[name] = fn
    return found, valid


def reachable(program, roots: list[str]) -> set[str]:
    """The names of the module's functions the `roots` reach through direct calls: a name that is called
    in a function's own source and is a function of the module (over-approximate where a local shadows it,
    which the program check reports anyway)."""
    defs: dict[str, list[ast.FunctionDef]] = {}
    for f in program.functions:
        defs.setdefault(f.name, []).append(f)
    seen: set[str] = set()
    todo = [r for r in roots if r in defs]
    while todo:
        name = todo.pop()
        if name in seen:
            continue
        seen.add(name)
        for fn in defs[name]:
            for n in ast.walk(fn):
                if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id in defs:
                    todo.append(n.func.id)
    return seen


def _unreachable_spans(program, keep: set[str]) -> list[tuple[int, int]]:
    spans = []
    for f in program.functions:
        if f.name not in keep and f.end_lineno is not None:
            start = min([f.lineno, *(d.lineno for d in f.decorator_list)])
            spans.append((start, f.end_lineno))
    return spans


# --- module-level state ----------------------------------------------------------------------------

def _walk(node, cur):
    """Every IR node under `node`, with the number of the source statement it belongs to."""
    if isinstance(node, ir.Stmt) and node.stmt is not None:
        cur = node.stmt
    yield node, cur
    for fl in dataclasses.fields(node):
        v = getattr(node, fl.name)
        for x in (v if isinstance(v, tuple) else (v,)):
            if isinstance(x, (ir.Expr, ir.Stmt)):
                yield from _walk(x, cur)


def state_findings(fn: ir.Function, rel: str, statements: list[ast.stmt] | None = None) -> list[Finding]:
    """`aot/library-state`: what in the lowered function `fn` reads or writes state that outlives a call (a
    library keeps none: the leader decision of 2026-11-16, not owner-confirmed, is that module-level mutable
    state is a compile error for a library, because exported functions take no lock and run on several threads).

    It looks at the IR, where a module-level name must show up to be read or written at run time:
      * `ir.Global`, a module-level name looked up when it is read;
      * `fn.entry_globals`, module globals read once at entry;
      * a `Local`, an `Assign` target, a `StoreIndex`, `Index`, `Len`, `CopyArray` array or a `ForRange`
        or `Unpack` target that is neither a parameter nor a local of the function (a module-level name);
      * `ir.ArgvLen` and `ir.ArgvInt`, the process's argument vector, which a library does not have.
    A module constant is not one of these: the front end folds it to an `ir.Const` (the AOT mode), so a
    read of it is a value in the code. What it cannot find: state reached only through a construct the
    emitter rejects anyway (an object, a call of something not in the program) is that construct's own finding."""
    known = {p.name for p in fn.params} | set(fn.locals)
    out: list[Finding] = []

    def line(cur: int | None) -> int:
        if statements is not None and cur is not None and 0 <= cur < len(statements):
            return statements[cur].lineno
        return fn.source_line

    def bad(cur: int | None, what: str, name: str) -> None:
        out.append(_finding(rel, line(cur), "aot/library-state", name,
                            f"`{fn.name}` {what}; a library holds no module-level state "
                            "(module constants are folded, and a function that assigns a module-level name makes "
                            "a local)", fn.name))

    for p in fn.entry_globals:
        bad(None, f"reads the module global `{p.name}` at entry", p.name)

    def name_used(cur: int | None, name: str, how: str) -> None:
        if name not in known:
            bad(cur, f"{how} the module-level name `{name}`", name)

    for stmt in fn.body:
        for node, cur in _walk(stmt, None):
            if isinstance(node, ir.Global):
                bad(cur, f"reads the module-level name `{node.name}` when it runs", node.name)
            elif isinstance(node, (ir.ArgvLen, ir.ArgvInt)):
                bad(cur, "reads the process's argument vector (`sys.argv`)", "sys.argv")
            elif isinstance(node, ir.Local):
                name_used(cur, node.name, "reads")
            elif isinstance(node, ir.Assign):
                name_used(cur, node.target, "assigns")
            elif isinstance(node, ir.StoreIndex):
                name_used(cur, node.array, "stores into")
            elif isinstance(node, (ir.Index, ir.Len)):
                name_used(cur, node.array, "reads")
            elif isinstance(node, ir.CopyArray):
                name_used(cur, node.src, "reads")
            elif isinstance(node, ir.ForRange):
                name_used(cur, node.var, "assigns")
            elif isinstance(node, ir.Unpack):
                for t in node.targets:
                    name_used(cur, t, "assigns")
    unique = {(f.line, f.name, f.message): f for f in out}
    return list(unique.values())


# a top-level line of the generated C that declares data: not a preprocessor line, a comment, a typedef, a
# function head or prototype (those have a `(`), a brace
_DATA_DECL = re.compile(r"^(?!#|/\*|typedef\b|[{}]|\s)[A-Za-z_][^()]*(;|=)")


def static_data_findings(c_text: str, rel: str) -> list[Finding]:
    """`aot/library-state` for any top-level data definition in the generated C (the emitter writes none; this
    checks that rather than assuming it)."""
    out = []
    for line in c_text.splitlines():
        if _DATA_DECL.match(line):
            out.append(_finding(rel, 1, "aot/library-state", line.strip(),
                                f"the generated C defines static data (`{line.strip()}`); a library holds no "
                                "module-level state"))
    return out


# --- the C text, the header, the description --------------------------------------------------------

class _Export:
    def __init__(self, name: str, node: ast.FunctionDef, fn: ir.Function, kinds: tuple[Type, ...] | None,
                 reps=None) -> None:
        self.name = name
        self.node = node
        self.fn = fn
        # a list[int] is `int_list` where its group is big-capable (aot/rep.py), `i64_list` where it is raw
        self.params = [(p.name, _dict_type(p.kinds) if p.type is Type.DICT          # type: ignore[union-attr]
                        else "int_list" if p.type is Type.I64_ARRAY and reps is not None
                        and reps.list_big(name, p.name) else _TYPE_NAME[p.type])          # type: ignore[union-attr]
                       for p in fn.params]
        if fn.returns is Type.DICT:
            self.returns = [_dict_type(fn.return_kinds)]
        elif fn.returns in _TYPE_NAME:
            self.returns = ["int_list" if fn.returns is Type.I64_ARRAY and reps is not None and reps.return_big(name)
                            else _TYPE_NAME[fn.returns]]
        elif kinds is not None:
            self.returns = [_TYPE_NAME[k] for k in kinds]
        else:
            self.returns = []
        # which int parameters the compiled function holds as TAGGED values, and which int results it hands back so
        self.tagged_params: set[str] = set()
        self.tagged_results: set[int] = set()
        if reps is not None:
            self.tagged_params = {p.name for p in fn.params
                                  if isinstance(p, ir.Param) and p.type is Type.I64 and reps.name(name, p.name) == "tagged"}
            if fn.returns is Type.I64 and reps.returns(name) == "tagged":
                self.tagged_results = {0}
            elif kinds is not None:
                self.tagged_results = {i for i, k in enumerate(kinds) if k is Type.I64 and reps.element(name, i) == "tagged"}

    @property
    def dict_types(self) -> list[str]:
        return [t for t in [*(t for _, t in self.params), *self.returns] if t in _DICT_KINDS]

    @property
    def uses_int_list(self) -> bool:
        return "int_list" in [t for _, t in self.params] or "int_list" in self.returns

    @property
    def tagged_io(self) -> bool:
        return bool(self.tagged_params or self.tagged_results)

    def c_params(self, p: str) -> list[str]:
        """The C parameter list: the arguments, then the out pointers."""
        out = []
        for name, t in self.params:
            ct = f"{p}_{t} *" if t in _HANDLE else f"{_C_TYPE[t]} "
            out.append(f"{ct}p_{mangle(name)}")
        for nm, t in zip(self.out_names(), self.returns):
            out.append(f"{p}_{t} **{nm}" if t in _HANDLE else f"{_C_TYPE[t]} *{nm}")
        return out

    def out_names(self) -> list[str]:
        return ["out" if len(self.returns) == 1 and (self.fn.returns in _TYPE_NAME or self.fn.returns is Type.DICT)
                else f"out{i}"
                for i in range(len(self.returns))]

    def signature_text(self) -> str:
        returns = ast.unparse(self.node.returns) if self.node.returns is not None else "None"
        return f"{self.name}({ast.unparse(self.node.args)}) -> {returns}"

    def wrapper_tagged(self, p: str) -> list[str]:
        """The exported function of a function whose int parameters or results the compiled code holds as TAGGED
        values: the same shape as `wrapper`, with the conversions of the boundary (an int parameter arrives as int64;
        an int result goes back through a fit check, and one that does not fit fails the call with an OverflowError)
        and one exit that releases what the conversions made."""
        callee = f"tpf_{mangle(self.name)}"
        lines = [f"TPA_EXPORT int {p}_{mangle(self.name)}({', '.join(self.c_params(p)) or 'void'})", "{",
                 "    int rc;"]
        for name, t in self.params:
            if name in self.tagged_params:
                lines.append(f"    tpa_int a_{mangle(name)} TPA_UNUSED = TPA_INT_ZERO;")
        for i, t in enumerate(self.returns):
            if t in _LIST_ELEMENT:
                lines.append(f"    tpa_{_LIST_ELEMENT[t]}_list *r{i} = NULL;")
            elif t in _DICT_KINDS:
                lines.append(f"    tpa_dict *r{i} = NULL;")
            elif i in self.tagged_results:
                lines.append(f"    tpa_int r{i} = TPA_INT_ZERO;")
                lines.append(f"    int64_t v{i} = 0;")
            elif t == "bool":
                lines.append(f"    int r{i} = 0;")
            else:
                lines.append(f"    {_C_TYPE[t]} r{i} = 0;")
        outs = self.out_names()
        list_outs = [o for o, t in zip(outs, self.returns) if t in _HANDLE]

        def failure(indent: str) -> list[str]:
            return [f"{indent}*{o} = NULL;" for o in list_outs]
        for name, t in self.params:
            if t in _HANDLE:
                lines.append(f"    if (p_{mangle(name)} == NULL) {{")
                lines += failure("        ")
                lines.append(f"        return {_null_check(t)}(\"{_c_escape(name)}\");")
                lines.append("    }")
        call_args: list[str] = []
        for name, t in self.params:
            if name in self.tagged_params:
                lines.append(f"    if (tpa_int_from_i64(p_{mangle(name)}, &a_{mangle(name)}) != 0) {{")
                lines.append("        rc = -1;")
                lines += failure("        ")
                lines.append("        goto tp_done;")
                lines.append("    }")
                call_args.append(f"a_{mangle(name)}")
            else:
                call_args.append(f"(p_{mangle(name)} != 0)" if t == "bool" else f"p_{mangle(name)}")
        lines.append(f"    rc = {callee}({', '.join(call_args + [f'&r{i}' for i in range(len(self.returns))])});")
        lines.append("    if (rc != 0) {")
        lines += failure("        ")
        lines.append("        rc = -1;")
        lines.append("        goto tp_done;")
        lines.append("    }")
        for i in sorted(self.tagged_results):
            lines.append(f"    if (tpa_int_to_i64(r{i}, &v{i}) != 0) {{")
            lines.append(f"        rc = tpa_raise(TPA_OverflowError, \"{_overflow_message(self.name)}\");")
            lines += failure("        ")
            lines.append("        goto tp_done;")
            lines.append("    }")
        for i, (o, t) in enumerate(zip(outs, self.returns)):
            if i in self.tagged_results:
                lines.append(f"    *{o} = v{i};")
            elif t == "bool":
                lines.append(f"    *{o} = (uint8_t)(r{i} != 0);")
            else:
                lines.append(f"    *{o} = r{i};")
        lines += ["    tpa_lib_clear_error();", "    rc = 0;", "tp_done:"]
        for name, t in self.params:
            if name in self.tagged_params:
                lines.append(f"    tpa_int_release(a_{mangle(name)});")
        for i in sorted(self.tagged_results):
            lines.append(f"    tpa_int_release(r{i});")
        lines += ["    return rc;", "}"]
        return lines

    def wrapper(self, p: str) -> list[str]:
        """The exported function: the null-handle checks, the call of the compiled function, the out values."""
        if self.tagged_io:
            return self.wrapper_tagged(p)
        callee = f"tpf_{mangle(self.name)}"
        lines = [f"TPA_EXPORT int {p}_{mangle(self.name)}({', '.join(self.c_params(p)) or 'void'})", "{",
                 "    int rc;"]
        temps: list[str] = []
        call_args: list[str] = []
        for name, t in self.params:
            call_args.append(f"(p_{mangle(name)} != 0)" if t == "bool" else f"p_{mangle(name)}")
        for i, t in enumerate(self.returns):
            if t in _LIST_ELEMENT:
                lines.append(f"    tpa_{_LIST_ELEMENT[t]}_list *r{i} = NULL;")
            elif t in _DICT_KINDS:
                lines.append(f"    tpa_dict *r{i} = NULL;")
            elif t == "bool":
                lines.append(f"    int r{i} = 0;")
            else:
                lines.append(f"    {_C_TYPE[t]} r{i} = 0;")
            temps.append(f"&r{i}")
        outs = self.out_names()
        list_outs = [(o, i) for i, (o, t) in enumerate(zip(outs, self.returns)) if t in _HANDLE]

        def failure() -> list[str]:
            return [f"        *{o} = NULL;" for o, _ in list_outs]
        for name, t in self.params:
            if t in _HANDLE:
                lines.append(f"    if (p_{mangle(name)} == NULL) {{")
                lines += failure()
                lines.append(f"        return {_null_check(t)}(\"{_c_escape(name)}\");")
                lines.append("    }")
        lines.append(f"    rc = {callee}({', '.join(call_args + temps)});")
        lines.append("    if (rc != 0) {")
        lines += failure()
        lines.append("        return -1;")
        lines.append("    }")
        for i, (o, t) in enumerate(zip(outs, self.returns)):
            lines.append(f"    *{o} = (uint8_t)(r{i} != 0);" if t == "bool" else f"    *{o} = r{i};")
        lines += ["    tpa_lib_clear_error();", "    return 0;", "}"]
        return lines

    def description(self, p: str) -> dict:
        return {"name": self.name, "symbol": f"{p}_{mangle(self.name)}", "line": self.node.lineno,
                "params": [{"name": n, "type": t} for n, t in self.params], "returns": list(self.returns)}


def _dict_type(kinds) -> str:
    """The vocabulary name of a boundary dict's kinds (int keys; the front end and `_Plan.emit` refuse the others)."""
    return f"dict_i64_{_TYPE_NAME[kinds[1]]}"


def _null_check(t: str) -> str:
    return "tpa_lib_null_dict" if t in _DICT_KINDS else "tpa_lib_null_handle"


def _dict_functions(p: str, kind: str) -> list[str]:
    """The functions of one dict kind of ABI version 3; only a library whose signatures use the kind has them. They
    forward to the runtime's TPA_LIBRARY block of tp_aot_dict.h with the C types of the kind."""
    v, vk = _DICT_KINDS[kind]
    ct, h = _C_TYPE[v], f"{p}_{kind}"
    return [
        f"TPA_EXPORT {h} *{h}_new(void)",
        "{",
        f"    return tpa_lib_dict_new({vk});",
        "}",
        f"TPA_EXPORT int {h}_set({h} *d, int64_t key, {ct} value)",
        "{",
        "    return tpa_lib_dict_set(d, key, &value);",
        "}",
        f"TPA_EXPORT int {h}_get(const {h} *d, int64_t key, {ct} *out)",
        "{",
        "    return tpa_lib_dict_get(d, key, out);",
        "}",
        f"TPA_EXPORT int {h}_contains(const {h} *d, int64_t key)",
        "{",
        "    return tpa_lib_dict_contains(d, key);",
        "}",
        f"TPA_EXPORT int {h}_next(const {h} *d, int64_t *pos, int64_t *key, {ct} *value)",
        "{",
        "    return tpa_lib_dict_next(d, pos, key, value);",
        "}",
        f"TPA_EXPORT int64_t {h}_len(const {h} *d)",
        "{",
        "    return d == NULL ? 0 : d->used;",
        "}",
        f"TPA_EXPORT void {h}_retain({h} *d)",
        "{",
        "    tpa_dict_retain(d);",
        "}",
        f"TPA_EXPORT void {h}_release({h} *d)",
        "{",
        "    tpa_dict_release(d);",
        "}",
        "",
    ]


def _dict_kinds_used(exports) -> list[str]:
    used = {t for e in exports for t in e.dict_types}
    return [k for k in _DICT_KINDS if k in used]


def _c_escape(text: str) -> str:
    return "".join(c if c.isascii() and c.isalnum() or c == "_" else f"\\{ord(c):03o}" if ord(c) < 256 else "?"
                   for c in text)


def _overflow_message(name: str) -> str:
    """The fixed message of an int result that does not fit int64 (an ABI-level rule, see the module docstring)."""
    return f"the int result of `{_c_escape(name)}` does not fit in 64 bits"


def _list_functions(p: str) -> list[str]:
    out: list[str] = []
    for t, ct, zero in (("f64", "double", "0.0"), ("i64", "int64_t", "INT64_C(0)")):
        out += [
            f"TPA_EXPORT {p}_{t}_list *{p}_{t}_list_new(int64_t n)",
            "{",
            f"    {p}_{t}_list *l = NULL;",
            f"    if (tpa_{t}_fill(n, {zero}, &l) != 0) {{",
            "        return NULL;",
            "    }",
            "    tpa_lib_clear_error();",
            "    return l;",
            "}",
            f"TPA_EXPORT int64_t {p}_{t}_list_len(const {p}_{t}_list *l)",
            "{",
            "    return l == NULL ? 0 : l->len;",
            "}",
            f"TPA_EXPORT {ct} *{p}_{t}_list_data({p}_{t}_list *l)",
            "{",
            "    return l == NULL ? NULL : l->d;",
            "}",
            f"TPA_EXPORT void {p}_{t}_list_retain({p}_{t}_list *l)",
            "{",
            f"    tpa_{t}_retain(l);",
            "}",
            f"TPA_EXPORT void {p}_{t}_list_release({p}_{t}_list *l)",
            "{",
            f"    tpa_{t}_release(l);",
            "}",
            "",
        ]
    return out


def _int_list_functions(p: str) -> list[str]:
    """The functions of the `int_list` kind (ABI version 2); only a library whose signatures use the kind has them."""
    return [
        f"TPA_EXPORT {p}_int_list *{p}_int_list_new(int64_t n)",
        "{",
        f"    {p}_int_list *l = NULL;",
        "    if (tpa_int_list_alloc(n, &l) != 0) {",
        "        return NULL;",
        "    }",
        "    tpa_lib_clear_error();",
        "    return l;",
        "}",
        f"TPA_EXPORT int64_t {p}_int_list_len(const {p}_int_list *l)",
        "{",
        "    return l == NULL ? 0 : l->len;",
        "}",
        f"TPA_EXPORT int {p}_int_list_get(const {p}_int_list *l, int64_t i, int64_t *out)",
        "{",
        "    if (l == NULL) {",
        '        return tpa_lib_null_handle("l");',
        "    }",
        "    return tpa_int_list_get_i64(l, i, out);",
        "}",
        f"TPA_EXPORT int {p}_int_list_set({p}_int_list *l, int64_t i, int64_t v)",
        "{",
        "    if (l == NULL) {",
        '        return tpa_lib_null_handle("l");',
        "    }",
        "    return tpa_int_list_set_i64(l, i, v);",
        "}",
        f"TPA_EXPORT void {p}_int_list_retain({p}_int_list *l)",
        "{",
        "    tpa_int_list_retain(l);",
        "}",
        f"TPA_EXPORT void {p}_int_list_release({p}_int_list *l)",
        "{",
        "    tpa_int_list_release(l);",
        "}",
        "",
    ]


def _c_text(g, bodies: list[list[str]], exports: list[_Export], p: str) -> str:
    out = [
        f"/* Generated by typedpython.aot (SPEC N-17, #455): the library `{p}`, ABI version {ABI_VERSION}. Owner decision D5",
        " * (2026-11-16) for the existence of the library output; its shape is a leader decision of 2026-11-16, not",
        " * owner-confirmed. No interpreter header, no interpreter API. */",
        "#ifndef TPA_LIBRARY",
        "#define TPA_LIBRARY 1",
        "#endif",
        # tier S2a: a list kind the library resizes internally (never one that crosses the boundary: aot/library-resize)
        *[f"#define TPA_GROW_{k.upper()} 1" for k in g.grown_kinds()],
        '#include "tp_aot.h"',
        "@INT@",
        *emit_mod.FP_CONTRACT_PRAGMA,
        "",
        f"typedef tpa_f64_list {p}_f64_list;",
        f"typedef tpa_i64_list {p}_i64_list;",
        "@INTLIST@",
    ]
    out += [f"typedef tpa_dict {p}_{k};" for k in _dict_kinds_used(exports)]     # tier S2b, ABI version 3
    out += g.emit_str_lits()            # tier S1: the immortal literals of the functions' str values
    out += [g.prototype(f) for f in g.fns.values()]
    out.append("")
    for b in bodies:
        out += [*b, ""]
    for e in exports:
        out += [*e.wrapper(p), ""]
    out += _list_functions(p)
    if any(e.uses_int_list for e in exports):
        out += _int_list_functions(p)
    for k in _dict_kinds_used(exports):
        out += _dict_functions(p, k)
    out += [
        f"TPA_EXPORT int {p}_init(void)",
        "{",
        "    return 0;",
        "}",
        f"TPA_EXPORT void {p}_shutdown(void)",
        "{",
        "}",
        f"TPA_EXPORT int {p}_last_error(const char **class_name, const char **message)",
        "{",
        "    return tpa_lib_last_error(class_name, message);",
        "}",
        "",
    ]
    text = "\n".join(out)
    # the big-integer runtime is included only where the generated text uses it (a library whose ints are all proved
    # inside 64 bits is byte-identical to what the previous step generated); the include line replaces `@INT@`
    # (and an empty line stands where the old text had one)
    # (the typedef of the `int_list` kind, `@INTLIST@`, is a line of its own only in a library that uses the kind)
    text = text.replace("@INTLIST@\n", f"typedef tpa_int_list {p}_int_list;\n\n" if any(e.uses_int_list for e in exports)
                        else "\n")
    if not g.uses_str and not emit_mod.needs_int_runtime(text.replace("@INT@", "")):
        return text.replace("@INT@", "")
    include = '#include "tp_aot_int.h"\n'
    if g.uses_str:                      # tier S1: the str runtime needs the int runtime's parser and formatter
        tables = "".join(line + "\n" for line in unicode_mod.tables_c(g.ucd))     # tier S1b
        include += tables + '#include "tp_aot_str.h"\n'
    if g.uses_dict:                     # tier S2b: a dict inside the library or at its boundary (ABI version 3)
        include += '#include "tp_aot_dict.h"\n'
    return text.replace("@INT@", include)


_HEADER_TOP = """\
/* {name}.h: the C ABI of the library `{name}`, generated by typedpython.aot from {module} (SPEC N-17, #455).
 * Do not edit. Owner decision D5 (2026-11-16) is that the library output exists; the shape described here
 * (handles, no lock, status returns) is a leader decision of 2026-11-16, not owner-confirmed.
 *
 * Calling
 *   Every function below returns 0 on success and -1 on failure; the results go through the out pointers
 *   that follow the arguments (none for a function that returns None; for a tuple, one per element, in
 *   order). Out pointers must not be NULL. On failure a scalar out value is not written, and a list out
 *   handle is set to NULL.
 *   int is int64_t: any 64-bit value is accepted, and inside the function the integer rules of the source
 *   language apply as they do for a compiled program (an operation the compiler could not prove stays inside
 *   64 bits is not compiled, so there is no wrap-around). float is double. bool is uint8_t: 0 is false and any
 *   other value is read as true (a bool result is 0 or 1).
 *
 * Errors
 *   After a call that returned -1, {name}_last_error gives, on the same thread, the class name of the
 *   exception the source language raises for that call and its message (str of the exception), as
 *   NUL-terminated strings valid until that thread's next call into the library that records or clears an
 *   error. It returns 0 then, and 1 when this thread's last such call succeeded or it made none. A successful
 *   exported function or {name}_f64_list_new / {name}_i64_list_new clears the record; the other list
 *   functions, {name}_init, {name}_shutdown and {name}_last_error leave it as it is. Nothing unwinds across the
 *   boundary. Failures you can meet: ZeroDivisionError, ValueError, OverflowError, IndexError, MemoryError, and
 *   TypeError for a NULL list handle argument. That last check belongs to this ABI and has no counterpart in
 *   the source language: the function fails with -1 and a TypeError whose message names the argument.
 *
 * Lists (list[float] is {name}_f64_list, list[int] is {name}_i64_list)
 *   A list is an opaque, reference-counted handle. Create one with {name}_..._list_new(n) (n elements, all
 *   0.0 or 0; n < 0 gives an empty list, as [0.0] * n does), read its length with _len, and get the address
 *   of element 0 with _data: n contiguous double or int64_t values, valid while you hold the handle (the list
 *   never changes length; for an empty list do not dereference it). A list keeps its identity across calls: a
 *   function that returns one of its list arguments returns the same list.
 *   Ownership: arguments are borrowed for the call. A returned handle, and one from _new, is owned by the
 *   caller, who must call _release once for it. _retain adds an owner, _release removes one and frees the list
 *   with the last. _release of NULL does nothing. The same handle may be passed for two parameters (the two
 *   names then alias, as they do in the source language). Do not use a handle after the release that
 *   gave up your last ownership.
 *
 * Threads
 *   There is no library-wide lock. Exported functions are reentrant and may run on several threads at once;
 *   the error record is per thread; list counts (retain, release, and the retains the library does) are
 *   atomic, so a handle may be shared and released from several threads. Reading and writing the elements of
 *   one list from several threads is memory-safe for every list kind. For {name}_i64_list and {name}_f64_list
 *   (through _data, or inside the library) it is what a C array gives: a racing read sees the old or the new
 *   value, and two writers to one element leave one of the two values. Which of two writers to the same
 *   element wins is the caller's responsibility, for every kind. The library itself writes only the lists a function
 *   stores into, and only those it was given or made. The library holds no module-level state.
 *   A function that prints writes to the process's standard output through the C runtime's stdio. Output
 *   of several threads may interleave per call (stdio locks each call); the buffering is the C runtime's.
 *
 * The functions
 */
"""


_HEADER_INT_OLD = ("(an operation the compiler could not prove stays inside\n"
                   " *   64 bits is not compiled, so there is no wrap-around)")
_HEADER_INT_NEW = ("(an int the compiler could not prove stays inside\n"
                   " *   64 bits is held as an integer of any size, so there is no wrap-around)")
_HEADER_ERR_OLD = "TypeError whose message names the argument.\n *\n * Lists"
_HEADER_ERR_NEW = ("TypeError whose message names the argument.\n"
                   " *   A second check of this kind concerns an int RESULT (also an element of a tuple): the source language's\n"
                   " *   int has no limit, this ABI's is int64_t. A function whose int result does not fit int64_t fails with -1\n"
                   " *   and an OverflowError whose message says that the int result of that function does not fit in 64 bits (no\n"
                   " *   out value is written then); the same call with smaller inputs succeeds. That check, too, belongs to this\n"
                   " *   ABI and has no counterpart in the source language.\n *\n * Lists")


_HEADER_LISTS_END = " *\n * Threads\n"
_HEADER_INT_LIST = """\
 *
 * Lists that may hold big ints ({name}_int_list)
 *   Where the compiler found that a list[int] of a function may hold a value outside 64 bits, that function's
 *   parameter or result is a {name}_int_list instead of a {name}_i64_list (the signatures below say which; a list
 *   that crosses the boundary is one group with the outside, so you can only pass a handle of the kind a signature
 *   names). It is an opaque, reference-counted handle with the ownership rules of the other lists, but its elements
 *   are not int64_t in memory, so there is no _data: read element i with {name}_int_list_get(l, i, &v) and write it
 *   with {name}_int_list_set(l, i, v). Both return 0 or -1 and the error record. An index out of range (a negative
 *   one counts from the end, as in the source language) is an IndexError with the source language's messages
 *   ("list index out of range", and "list assignment index out of range" for _set). _get of an element that does not
 *   fit int64_t is an OverflowError whose message says that the element of the list does not fit in 64 bits (no
 *   value is written); after a _set of a smaller value the same element reads fine. That check belongs to this ABI
 *   and has no counterpart in the source language. {name}_int_list_new(n) makes n zeros (an empty list for n < 0)
 *   and, like the other _new functions, clears the error record; a NULL handle passed to _get or _set is a
 *   TypeError as for an exported function. _len, _retain and _release are as for the other lists.
 *   Which kind a list has in a signature follows from the whole program, not from the function alone: a change in a
 *   helper that several exports share can change another export's parameter or result from {name}_i64_list to
 *   {name}_int_list (or back), so regenerate the header and rebuild the callers together. The two handle kinds are
 *   not interchangeable: passing an i64 handle where an int handle is expected (or the reverse) corrupts memory, and
 *   only the C types, which differ, prevent it. Threads: _get and _set (and the compiled functions) take the list's
 *   own lock for each element access (a small lock inside the list, one per list, held only for a load or a swap of
 *   one element and never while anything is allocated or freed), so concurrent reads and writes of the elements are
 *   memory-safe: a reader sees the old or the new element, and nothing is freed twice or used after being freed. Which
 *   of two writers to the same element wins is the caller's responsibility.
"""


_HEADER_DICTS = """\
 *
 * Dicts ({kinds})
 *   dict[int, int] is {name}_dict_i64_i64, dict[int, float] {name}_dict_i64_f64 and dict[int, bool] {name}_dict_i64_bool:
 *   opaque, reference-counted handles with the ownership rules of the lists (arguments borrowed, a returned handle and one
 *   from _new owned by the caller, _retain / _release, a function that returns its argument returns the same handle).
 *   _new() makes an empty dict and clears the error record. _set(d, key, value) is d[key] = value: a new key goes last, an
 *   existing key keeps its place and takes the value (for a bool value, anything other than 0 is true). _get(d, key, &v)
 *   is d[key]; a missing key is a KeyError whose message is the key, as the source language gives it. _contains(d, key)
 *   is 1 or 0. _len(d) is the number of items (0 for NULL). To read the items in their order (the source language's
 *   insertion order), start with int64_t pos = 0 and call _next(d, &pos, &key, &value) until it returns 0; each 1 gives one
 *   item. Inside the library an int key or value has no limit; a key or value outside int64_t cannot be read: _get or _next
 *   fails with -1 and an OverflowError, nothing is written, and _next has moved past that item, so the next call goes on.
 *   The messages are
 *     "the key of the dict does not fit in 64 bits"
 *     "the value of the dict does not fit in 64 bits"
 *   That check belongs to this ABI and has no counterpart in the source language. A NULL handle is a TypeError naming the argument, for an
 *   exported function and for _set, _get, _contains and _next (which return -1; nothing is written). These functions
 *   except _new leave the error record as it is when they succeed.
 *   Threads: the library never writes a dict that crosses its boundary (a function that would is not exported), so one
 *   handle may be read, and passed to calls, from any number of threads at once. Build a dict with _new and _set before
 *   you share it: a _set while another thread uses the same dict, or while a call holds it, is a data race (the dict has
 *   no lock), which is the caller's to prevent. The counts of _retain and _release are atomic, as for the lists.
"""


def _header_text(p: str, module: str, exports: list[_Export], tagged: bool = False) -> str:
    up = p.upper()
    guard = f"TP_AOT_LIB_{up}_H"
    top = _HEADER_TOP.format(name=p, module=module)
    int_list = any(e.uses_int_list for e in exports)
    if int_list:
        assert _HEADER_LISTS_END in top
        top = top.replace(_HEADER_LISTS_END, _HEADER_INT_LIST.format(name=p) + _HEADER_LISTS_END)
    dicts = _dict_kinds_used(exports)
    if dicts:
        assert _HEADER_LISTS_END in top
        top = top.replace(_HEADER_LISTS_END, _HEADER_DICTS.format(name=p, kinds=", ".join(f"{p}_{k}" for k in dicts))
                          + _HEADER_LISTS_END)
    if tagged:
        assert _HEADER_INT_OLD in top
        top = top.replace(_HEADER_INT_OLD, _HEADER_INT_NEW)
        if any(e.tagged_results for e in exports):
            assert _HEADER_ERR_OLD in top
            top = top.replace(_HEADER_ERR_OLD, _HEADER_ERR_NEW)
    out = [top.rstrip("\n"), f"#ifndef {guard}", f"#define {guard}", "",
           "#include <stdint.h>", "", "#ifdef __cplusplus", 'extern "C" {', "#endif", "",
           "/* Define " + f"{up}_BUILD" + " only when building the library itself on Windows. */",
           "#if defined(_WIN32) || defined(__CYGWIN__)",
           f"#  if defined({up}_BUILD)", f"#    define {up}_API __declspec(dllexport)", "#  else",
           f"#    define {up}_API __declspec(dllimport)", "#  endif",
           "#elif defined(__GNUC__) || defined(__clang__)",
           f"#  define {up}_API __attribute__((visibility(\"default\")))", "#else", f"#  define {up}_API",
           "#endif", "",
           f"typedef struct {p}_f64_list {p}_f64_list;    /* list[float], opaque */",
           f"typedef struct {p}_i64_list {p}_i64_list;    /* list[int], opaque */"]
    if int_list:
        out.append(f"typedef struct {p}_int_list {p}_int_list;    /* list[int] that may hold values outside 64 bits, opaque */")
    for k in dicts:
        out.append(f"typedef struct {p}_{k} {p}_{k};    /* dict[int, {_PY_NAME[_DICT_KINDS[k][0]]}], opaque */")
    out.append("")
    for e in exports:
        returns = ", ".join(e.returns) or "None"
        out.append(f"/* {e.signature_text()}   ({module}, line {e.node.lineno}); results: {returns} */")
        out.append(f"{up}_API int {p}_{mangle(e.name)}({', '.join(e.c_params(p)) or 'void'});")
        out.append("")
    out += [f"/* {p}_init and {p}_shutdown: nothing to initialise in this tier; init returns 0 and may be called more",
            " * than once and from any thread. They exist so that the ABI does not change later. */",
            f"{up}_API int {p}_init(void);", f"{up}_API void {p}_shutdown(void);", "",
            "/* The calling thread's last failure; see Errors above. 0 and both strings set, or 1. */",
            f"{up}_API int {p}_last_error(const char **class_name, const char **message);", ""]
    for t, ct, py in (("f64", "double", "list[float]"), ("i64", "int64_t", "list[int]")):
        out += [f"/* {py}: n elements, all zero (an empty list for n < 0); NULL with a MemoryError record on failure */",
                f"{up}_API {p}_{t}_list *{p}_{t}_list_new(int64_t n);",
                f"/* the length (0 for NULL) */",
                f"{up}_API int64_t {p}_{t}_list_len(const {p}_{t}_list *l);",
                "/* the address of element 0, valid while a handle is held (NULL for NULL) */",
                f"{up}_API {ct} *{p}_{t}_list_data({p}_{t}_list *l);",
                "/* one more owner / one owner less (NULL is a no-op); the last release frees */",
                f"{up}_API void {p}_{t}_list_retain({p}_{t}_list *l);",
                f"{up}_API void {p}_{t}_list_release({p}_{t}_list *l);", ""]
    if int_list:
        out += ["/* list[int] that may hold values outside 64 bits: n elements, all zero (an empty list for n < 0); NULL with a",
                " * MemoryError record on failure */",
                f"{up}_API {p}_int_list *{p}_int_list_new(int64_t n);",
                "/* the length (0 for NULL) */",
                f"{up}_API int64_t {p}_int_list_len(const {p}_int_list *l);",
                "/* element i (negative counts from the end) as an int64_t: 0, or -1 with an IndexError record, or -1 with the",
                " * OverflowError record of an element that does not fit in 64 bits (nothing is written to *out then) */",
                f"{up}_API int {p}_int_list_get(const {p}_int_list *l, int64_t i, int64_t *out);",
                "/* set element i: 0, or -1 with an IndexError record */",
                f"{up}_API int {p}_int_list_set({p}_int_list *l, int64_t i, int64_t v);",
                "/* one more owner / one owner less (NULL is a no-op); the last release frees */",
                f"{up}_API void {p}_int_list_retain({p}_int_list *l);",
                f"{up}_API void {p}_int_list_release({p}_int_list *l);", ""]
    for k in dicts:
        h, ct = f"{p}_{k}", _C_TYPE[_DICT_KINDS[k][0]]
        out += [f"/* dict[int, {_PY_NAME[_DICT_KINDS[k][0]]}]: an empty dict; NULL with a MemoryError record on failure */",
                f"{up}_API {h} *{h}_new(void);",
                "/* d[key] = value: 0, or -1 with the error record */",
                f"{up}_API int {h}_set({h} *d, int64_t key, {ct} value);",
                "/* d[key] into *out: 0, or -1 with a KeyError (or an OverflowError for an int that does not fit) */",
                f"{up}_API int {h}_get(const {h} *d, int64_t key, {ct} *out);",
                "/* key in d: 1 or 0 (-1 for NULL) */",
                f"{up}_API int {h}_contains(const {h} *d, int64_t key);",
                "/* the next item in insertion order from *pos (start at 0): 1, 0 at the end, or -1 (see Dicts above) */",
                f"{up}_API int {h}_next(const {h} *d, int64_t *pos, int64_t *key, {ct} *value);",
                "/* the number of items (0 for NULL) */",
                f"{up}_API int64_t {h}_len(const {h} *d);",
                "/* one more owner / one owner less (NULL is a no-op); the last release frees */",
                f"{up}_API void {h}_retain({h} *d);",
                f"{up}_API void {h}_release({h} *d);", ""]
    out += ["#ifdef __cplusplus", "}", "#endif", "", f"#endif /* {guard} */", ""]
    return "\n".join(out)


def _description(p: str, exports: list[_Export]) -> dict:
    lists = {}
    for t in ("f64", "i64"):
        lists[f"{t}_list"] = {"element": t, "new": f"{p}_{t}_list_new", "len": f"{p}_{t}_list_len",
                              "data": f"{p}_{t}_list_data", "retain": f"{p}_{t}_list_retain",
                              "release": f"{p}_{t}_list_release"}
    if any(e.uses_int_list for e in exports):
        lists["int_list"] = {"element": "i64", "new": f"{p}_int_list_new", "len": f"{p}_int_list_len",
                             "get": f"{p}_int_list_get", "set": f"{p}_int_list_set",
                             "retain": f"{p}_int_list_retain", "release": f"{p}_int_list_release"}
    out = {"prefix": p, "library": library_file_name(p), "abi_version": ABI_VERSION,
           "runtime": {"init": f"{p}_init", "shutdown": f"{p}_shutdown", "last_error": f"{p}_last_error"},
           "functions": [e.description(p) for e in exports], "lists": lists}
    dicts = _dict_kinds_used(exports)
    if dicts:                           # ABI version 3: only the kinds a signature uses
        out["dicts"] = {k: {"key": "i64", "value": _DICT_KINDS[k][0],
                            **{op: f"{p}_{k}_{op}" for op in ("new", "set", "get", "contains", "next", "len", "retain",
                                                              "release")}} for k in dicts}
    return out


# --- the pipeline -----------------------------------------------------------------------------------

class _Plan:
    """What `aot._lower` needs of a library: the functions to compile, and the emitter."""

    def __init__(self, program, prefix: str, nodes: dict[str, ast.FunctionDef], keep: set[str],
                 strict_int: bool = False) -> None:
        self.strict_int = strict_int
        self.program = program
        self.prefix = prefix
        self.nodes = nodes                  # export name -> its def, in the order given
        self.reachable = keep
        self.result: tuple[str, str, dict] | None = None

    def emit(self, proved: ir.Module, program) -> tuple[str | None, list[Finding]]:
        rel = program.rel
        names = {f.name for f in proved.functions if f.name in self.reachable}
        bodies = program.bodies()
        found: list[Finding] = []
        for f in proved.functions:
            if f.name in names:
                stmts = resume.statements(bodies[f.name]) if f.name in bodies else None     # type: ignore[arg-type]
                found += state_findings(f, rel, stmts)
        g, fn_bodies, more = emit_mod.emit_functions(proved, program, names, strict_int=self.strict_int)
        found += more
        exports: list[_Export] = []
        read_only = dict_read_only(proved, names)
        for name, node in self.nodes.items():
            fn = g.fns.get(name)
            if fn is None:
                continue                    # not proved: `_lower` reports it
            mut_cls = [
                q.cls for q in fn.params
                if isinstance(q, ir.Param) and q.type is Type.OBJ and q.cls is not None
                and not g.cls_analysis.class_immutable_for_library(q.cls)
            ]
            if (fn.returns is Type.OBJ and fn.return_cls is not None
                    and not g.cls_analysis.class_immutable_for_library(fn.return_cls)):
                mut_cls.append(fn.return_cls)
            if mut_cls:
                for cname in dict.fromkeys(mut_cls):
                    found.append(_finding(
                        rel, node.lineno, "aot/library-mutable-class", cname,
                        f"`{name}` cannot export mutable class `{cname}` in a library build (TPA_THREADS "
                        "requires immutable-after-init classes until Tier T adds per-object locking)",
                        name,
                    ))
                continue
            # tier S2a (design 4.3.2, option (a) of the S2 review B2): a list kind the program resizes cannot cross the
            # boundary until tier T, since a resize frees the block another thread or a `_data` pointer is reading
            boundary = {g.array_kind(fn.name, q.name, q.type) for q in fn.params
                        if isinstance(q, ir.ArrayParam) and q.type in LIST}
            if fn.returns in LIST:
                boundary.add(g.ret_kind(fn))
            resized = sorted(boundary & g.grown)
            if resized:
                found.append(_finding(
                    rel, node.lineno, "aot/library-resize", name,
                    f"`{name}` exports a list of the kind {', '.join(resized)} whose length the library changes "
                    "(append, insert, extend, pop, clear, remove): a list that crosses the boundary keeps its length "
                    "until tier T adds per-list locking (another thread or a `_data` pointer may be reading it)", name))
                continue
            split = [a.arg for a in node.args.args if tuples.annotation_shape(a.annotation) is not None]
            if split:
                # tier S2a: a tuple parameter is one parameter per element inside the program (typedpython/tuples.py);
                # the library boundary keeps the signature as written, so it does not export one yet
                found.append(_finding(rel, node.lineno, "aot/export", name,
                                      f"`{name}` cannot be exported: its parameter `{split[0]}` is a tuple, which does "
                                      "not cross the library boundary in tier S2a (pass the elements)", name))
                continue
            kinds = g.tuple_kinds(fn)
            ok = (all((isinstance(q, ir.Param) and q.type in CTYPE and q.cls is None)
                      or (isinstance(q, ir.Param) and q.type is Type.DICT and _boundary_dict(q.kinds))
                      or (isinstance(q, ir.ArrayParam) and q.type in LIST and q.buffer is None) for q in fn.params)
                  and (fn.returns in CTYPE or fn.returns in LIST or fn.returns is Type.NONE
                       or (fn.returns is Type.DICT and _boundary_dict(fn.return_kinds))
                       or (kinds is not None and all(k in CTYPE for k in kinds))))     # (a str element: not exported yet)
            if not ok:
                found.append(_finding(rel, node.lineno, "aot/export", name,
                                      f"`{name}` cannot be exported: its lowered signature is outside the library "
                                      "boundary", name))
                continue
            writes = dict_write_findings(proved, fn, rel, node.lineno, read_only)
            if writes:
                found += writes
                continue
            exports.append(_Export(name, node, fn, kinds, g.reps))
        if found:
            return None, found
        c_text = _c_text(g, fn_bodies, exports, self.prefix)
        found = static_data_findings(c_text, rel)
        if found:
            return None, found
        self.result = (c_text, _header_text(self.prefix, rel, exports, emit_mod.needs_int_runtime(c_text)),
                       _description(self.prefix, exports))
        return c_text, []


def _prepare(module: Path, exports: list[str], prefix: str, strict_int: bool):
    """The analysed program and the plan of a library; raises `AotError` with every finding of the export list, the
    prefix and the program's own analysis."""
    module = Path(module)
    exports = list(exports)
    program = analyze(module, library=True)
    findings = prefix_findings(prefix, program.rel)
    more, nodes = export_findings(program, exports)
    findings += more
    keep = reachable(program, list(nodes))
    spans = _unreachable_spans(program, keep)
    findings += [f for f in program.findings if not any(a <= f.line <= b for a, b in spans)]
    if findings:
        raise AotError(findings)
    return program, _Plan(program, prefix, nodes, keep, strict_int)


def explain_library(module: Path, *, exports: list[str], prefix: str, strict_int: bool = False) -> str:
    """`--explain-lists` for a library (#465): the groups of `list[int]` names of the functions it compiles, with the
    list parameters and results of the exports marked as crossing the boundary. Raises `AotError` as
    `generate_library` does."""
    program, plan = _prepare(module, exports, prefix, strict_int)
    out: list[str] = []
    _, found = _lower(program, plan, strict_int, explain=out)
    if found or not out:
        raise AotError(found)
    return out[0]


def generate_library(module: Path, *, exports: list[str], prefix: str,
                     strict_int: bool = False) -> tuple[str, str, dict]:
    """See the module docstring. Raises `AotError` with every finding. `strict_int`: an int operation that is not
    proved inside 64 bits is the finding `aot/int-unproven`, as before #462."""
    program, plan = _prepare(module, exports, prefix, strict_int)
    c_text, found = _lower(program, plan, strict_int)
    if found or c_text is None or plan.result is None:
        raise AotError(found)
    return plan.result


def build_library(module: Path, out_dir: Path, *, exports: list[str], prefix: str,
                  emit_c: Path | None = None, strict_int: bool = False) -> LibraryFiles:
    """See the module docstring. Writes `lib<prefix>.so` (platform name, `library_file_name`), `<prefix>.h` and
    `<prefix>.json` into `out_dir` and returns their paths; `emit_c` keeps the generated C there too."""
    from typedpython.aot import cc

    c_text, header, description = generate_library(module, exports=exports, prefix=prefix, strict_int=strict_int)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if emit_c is not None:
        emit_c = Path(emit_c)
        emit_c.parent.mkdir(parents=True, exist_ok=True)
        emit_c.write_text(c_text, encoding="utf-8")
    lib = cc.compile_library(c_text, out_dir, library_file_name(prefix), keep=emit_c)
    header_path = out_dir / f"{prefix}.h"
    json_path = out_dir / f"{prefix}.json"
    header_path.write_text(header, encoding="utf-8")
    json_path.write_text(json.dumps(description, indent=2) + "\n", encoding="utf-8")
    return LibraryFiles(library=lib, header=header_path, description=json_path)
