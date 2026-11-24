"""IR to C for the AOT mode (SPEC N-17, #448, #451; agent proposal, not owner-confirmed).

Input: the verified IR of the front end's AOT mode (`frontend.lower(path, aot=True)`,
`verify.verify(m, aot=True)`, `ir.py`). Output: one C translation unit that includes `runtime/tp_aot.h`
and nothing else (no interpreter header, no interpreter API).

The calling convention: a function returns `int` (0 ok, -1 an exception is recorded in `tpa_exc`) and
writes its result through an out pointer (one per element of a returned tuple), as the default mode's
impl functions do, without the deopt code 1: nothing in an AOT build can deopt, so an operation that could
(an int operation the IR does not mark as proved to stay inside 64 bits) is a compile error instead (rule
`aot/int-unproven`).

Lists (agent proposal, not owner-confirmed; design note 4.1, 6.2, 6.3). A `list[float]` / `list[int]` is a
pointer to a counted native array (`tpa_f64_list`, `tpa_i64_list`), non-atomic count. Rules:
  * a creation (`[a, b]`, `[v] * n`, `list(range(n))`, `a[:]`) and a call that returns a list give a
    fresh list with count 1, which the receiver owns;
  * a list parameter is borrowed: the caller keeps it alive for the call, and it is the caller's own
    list, so two parameters may be one list;
  * binding a list name to a local (`b = a`) adds an owner; rebinding a local releases the old list
    after the new one is held; a returned list is a new reference (a local's own is retained, so the
    release below is uniform);
  * every function leaves through ONE cleanup point (`tp_cleanup`): every exit, a `return` in the
    middle, the end of the body and each failing operation (`goto tp_cleanup` with `rc = -1`), releases
    every list local, so an uncaught exception reaching `main` leaves nothing allocated.
  An array-valued temporary (a creation or a call result) is never live across an operation that can
  fail: it is created, then stored or returned at once, so it needs no release of its own.

A `list[int]` is RAW (`tpa_i64_list`, elements `int64_t`, everything above) or BIG-CAPABLE (`tpa_int_list`, elements
`tpa_int`), decided per group of names by `aot/rep.py` (#465; leader decision 2026-11-16, not owner-confirmed). A
big-capable list is the same counted heap object: a creation retains each element it takes, an element read is a new
reference (into a TAGGED slot), an element store takes the new element and releases the old one, and the release of the
list releases every element and then frees it, at the same cleanup point. A raw list generates exactly what it did.

Ints (#462; design decision D1 (a), representation approved by the leader on 2026-11-16, not owner-confirmed; the
rest agent proposal, not owner-confirmed). An int is RAW (`int64_t`, as above) or TAGGED (`tpa_int` of
runtime/tp_aot_int.h), decided per program by `aot/rep.py`. A TAGGED value is an owned reference with the lifetime of a
list: a local is released at the function's cleanup point and when it is rebound (`tpa_int_set`), a parameter is
borrowed (a parameter the body assigns is copied into an owned local at entry), a returned value is a new reference
the caller owns. A TAGGED temporary is a slot of the function, empty (`TPA_INT_ZERO`) until written, cleared before each
write (so a loop reuses it) and released at the cleanup point; an operation writes its slot only on success, so the
cleanup point never releases a dangling word. A RAW expression generates exactly what it generated before this step: a
program whose ints are all proved is byte-identical to the output of the previous step and does not include the
big-integer header. In strict mode (`--strict-int`, `strict_int=True`) nothing is TAGGED and an operation that is not
proved is the finding `aot/int-unproven`, as before.

Every node the emitter cannot give CPython's exact result for is a finding, never an approximation.
"""
from __future__ import annotations

import ast
import dataclasses
import functools
import re

from typedpython import ir, resume
from typedpython.aot import alloc as alloc_mod
from typedpython.aot import classes as classes_mod
from typedpython.aot import rep as rep_mod
from typedpython.aot import unicode as unicode_mod
from typedpython.aot.alloc import AllocPlan
from typedpython.aot.program import MAIN, Program
from typedpython.aot.rep import RAW, TAGGED
from typedpython.aotcheck import Finding
from typedpython.ir import Type

CTYPE = {Type.I64: "int64_t", Type.F64: "double", Type.BOOL: "int"}
# a native list type -> (the runtime's name for it, its element's C type, its element type)
LIST = {Type.F64_ARRAY: ("f64", "double", Type.F64), Type.I64_ARRAY: ("i64", "int64_t", Type.I64)}
FAIL = "{ rc = -1; goto tp_cleanup; }"


# the kinds of list the runtime has: `f64`, `i64` (a raw `list[int]`) and `int` (a big-capable `list[int]`: tagged
# elements, tp_aot_int.h `tpa_int_list`)
LIST_C = {"f64": "tpa_f64_list", "i64": "tpa_i64_list", "int": "tpa_int_list"}


def list_ptr(kind: str) -> str:
    return f"{LIST_C[kind]} *"


def lfn(kind: str, op: str) -> str:
    """The runtime function `op` (alloc, fill, copy, iota, retain, release) of a list of `kind`."""
    return f"tpa_int_list_{op}" if kind == "int" else f"tpa_{kind}_{op}"
_EXACT_FLOAT = 2**53
_I64_MIN = -2**63

_AST_OP = {
    ir.BinOpKind.ADD: ast.Add, ir.BinOpKind.SUB: ast.Sub, ir.BinOpKind.MUL: ast.Mult,
    ir.BinOpKind.TRUEDIV: ast.Div, ir.BinOpKind.FLOORDIV: ast.FloorDiv, ir.BinOpKind.MOD: ast.Mod,
}
_CMP_CODE = {
    ir.CompareKind.LT: "TPA_LT", ir.CompareKind.LE: "TPA_LE", ir.CompareKind.EQ: "TPA_EQ",
    ir.CompareKind.NE: "TPA_NE", ir.CompareKind.GT: "TPA_GT", ir.CompareKind.GE: "TPA_GE",
}


def mangle(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9_]", lambda m: f"_x{ord(m.group()):x}_", name)


def _i64(v: int) -> str:
    if v == _I64_MIN:
        return "(-INT64_C(9223372036854775807) - 1)"
    return f"INT64_C({v})" if v >= 0 else f"(-INT64_C({-v}))"


def _unwrap(c: str) -> str:
    """`(a == b)` as `a == b` for an `if (...)`: one pair of outer parentheses, when the first closes at
    the end (clang warns about `if ((a == b))`)."""
    if c.startswith("(") and c.endswith(")"):
        depth = 0
        for i, ch in enumerate(c):
            depth += (ch == "(") - (ch == ")")
            if depth == 0:
                return c[1:-1] if i == len(c) - 1 else c
    return c


def _f64(v: float) -> str:
    if v != v:
        return "NAN"
    if v in (float("inf"), float("-inf")):
        return "HUGE_VAL" if v > 0 else "(-HUGE_VAL)"
    return f"({v!r})"


def _plain_text(text: str) -> bool:
    """Tier S1: may `text` be written as its UTF-8 bytes (`tpa_print_str`)? Unless it holds a surrogate, which
    must go through the str runtime's encoder (surrogateescape or CPython's UnicodeEncodeError)."""
    return not any(0xD800 <= ord(c) <= 0xDFFF for c in text)


def _c_string(text: str) -> tuple[str, int]:
    """A C string literal for the UTF-8 bytes of `text` and their count. Printable ASCII is written as it
    is, anything else (and `"`, `\\`, `?` against trigraphs) as a three-digit octal escape."""
    data = text.encode("utf-8")
    out = []
    for b in data:
        c = chr(b)
        if 0x20 <= b < 0x7F and c not in '"\\?':
            out.append(c)
        else:
            out.append(f"\\{b:03o}")
    return '"' + "".join(out) + '"', len(data)


# --- the position of a finding -------------------------------------------------------------------

def _roots(stmt: ast.stmt) -> list[ast.expr]:
    if isinstance(stmt, (ast.If, ast.While)):
        return [stmt.test]
    if isinstance(stmt, ast.For):
        return [stmt.iter]
    if isinstance(stmt, ast.Assign):
        return [stmt.value, *stmt.targets]                # a target's own index (`count[r - 1] = x`) is an operation too
    if isinstance(stmt, ast.AugAssign):
        return [stmt.value, stmt.target]
    if isinstance(stmt, (ast.Return, ast.AnnAssign, ast.Expr)):
        return [stmt.value] if stmt.value is not None else []
    return []


def _ir_text(e: ir.Expr) -> str:
    if isinstance(e, ir.Local):
        return e.name
    if isinstance(e, ir.Const):
        return e.name if e.name is not None else repr(e.value)
    if isinstance(e, ir.BinOp):
        return f"{_ir_text(e.left)}{e.op.value}{_ir_text(e.right)}"
    if isinstance(e, ir.UnaryOp):
        return f"{e.op.value}{_ir_text(e.operand)}"
    if isinstance(e, (ir.ToFloat, ir.ToInt, ir.Box)):
        return _ir_text(e.operand)
    if isinstance(e, ir.Call):
        return f"{e.function}({','.join(_ir_text(a) for a in e.args)})"
    if isinstance(e, ir.Index):
        return f"{e.array}[{_ir_text(e.index)}]"
    if isinstance(e, ir.Len):
        return f"len({e.array})"
    return "?"


def _plain(c: ast.AST) -> str:
    """The text of an operation: `x += e` is the operation `x + e`."""
    if isinstance(c, ast.AugAssign):
        return ast.unparse(ast.BinOp(left=c.target, op=c.op, right=c.value))
    return ast.unparse(c)


def _norm(s: str) -> str:
    return re.sub(r"[\s()]", "", s)


# --- one function --------------------------------------------------------------------------------

def assigned_names(f: ir.Function) -> set[str]:
    """Every name the body of `f` assigns (an assignment, a loop variable, an unpacking target)."""
    out: set[str] = set()
    for s in f.body:
        for n in rep_mod.walk(s):
            if isinstance(n, ir.Assign):
                out.add(n.target)
            elif isinstance(n, (ir.ForRange, ir.ForStr, ir.ForObjList)):
                out.add(n.var)
            elif isinstance(n, ir.Unpack):
                out.update(n.targets)
    return out


# the names of the big-integer runtime: a program whose generated text names none of them does not include it
_INT_USE = re.compile(r"\b(?:tpa_int|tpa_argv_int|tpa_truediv_i64|tpa_cmp_int_f64|tpa_cmp_f64_int|tpa_slot_int|"
                      r"tpa_print_int|TPA_INT_)")


def needs_int_runtime(text: str) -> bool:
    return _INT_USE.search(text) is not None


class _Gen:
    def __init__(self, module: ir.Module, program: Program, strict: bool = False,
                 alloc_plan: AllocPlan | None = None) -> None:
        self.list_elem_classes: set[str] = set()      # tier S2a: element classes of the lists of instances made
        self.module = module
        self.program = program
        self.strict = strict
        self.reps = rep_mod.infer(module, strict=strict)
        self.fns = {f.name: f for f in module.functions}
        self.bodies = program.bodies()
        self.findings: list[Finding] = []
        self.chunks: list[str] = []
        self.argv_int = False           # some function reads `int(sys.argv[i])`: the entry point must keep the arguments whole
        self.uses_str = False           # tier S1: some function has a str value (links tp_aot_str.h)
        self.str_lits: dict[str, str] = {}      # tier S1: literal text -> the name of its static object
        self.ucd: set[str] = set()              # tier S1b: the Unicode tables the program's str methods read (aot/unicode.py)
        tagged_fields = {k for k, v in self.reps.field_reps.items() if v == TAGGED}
        class_lines = {c.name: c.lineno for c in program.classes}
        self.cls_analysis = classes_mod.analyze_classes(module, program.rel, class_lines, tagged_fields)
        self.findings.extend(self.cls_analysis.findings)
        self.alloc_plan = alloc_plan if alloc_plan is not None else alloc_mod.analyze_alloc(module, self.cls_analysis)

    def cls_ptr(self, cls: str) -> str:
        return f"tpa_cls_{mangle(cls)} *"

    # -- tier S1 (PR-S1a): str literals

    def str_lit(self, text: str) -> str:
        """The C expression of the immortal static string `text` (runtime/tp_aot_str.h)."""
        self.uses_str = True
        if text == "":
            return "(&tpa_str__empty)"
        if len(text) == 1 and ord(text) < 0x100:
            return f"(&tpa_str__latin1[{ord(text)}].h)"
        name = self.str_lits.get(text)
        if name is None:
            name = self.str_lits[text] = f"tps_{len(self.str_lits)}"
        return f"(&{name}.h)"

    def emit_str_lits(self) -> list[str]:
        """The static objects of the literals, in first-use order: the code points of each in the narrowest kind."""
        out = []
        for text, name in self.str_lits.items():
            cps = [ord(c) for c in text]
            kind = 1 if max(cps) < 0x100 else 2 if max(cps) < 0x10000 else 4
            ct = {1: "uint8_t", 2: "uint16_t", 4: "uint32_t"}[kind]
            out.append(f"static TPA_UNUSED struct {{ tpa_str h; {ct} d[{len(cps)}]; }} {name} = "
                       f"{{ TPA_STR_HEAD({len(cps)}, {kind}), {{")
            for i in range(0, len(cps), 16):
                out.append("    " + ", ".join(f"0x{c:x}" for c in cps[i:i + 16]) + ",")
            out.append("} };")
        return out

    def field_ctype(self, cls: str, fd: ir.FieldDecl) -> str:
        if fd.type is Type.F64:
            return "double"
        if fd.type is Type.BOOL:
            return "uint8_t"
        if fd.type is Type.I64:
            return "tpa_int" if self.reps.field(cls, fd.name) == TAGGED else "int64_t"
        if fd.type is Type.F64_ARRAY:
            return "tpa_f64_list *"
        if fd.type is Type.I64_ARRAY:
            return "tpa_int_list *" if self.reps.field_list_big(cls, fd.name) else "tpa_i64_list *"
        if fd.type in (Type.STR, Type.STR_OPT):
            self.uses_str = True
            return "tpa_str *"
        if fd.type is Type.OBJ_LIST:
            return "tpa_obj_list *"                        # tier S2a: a list of instances (fd.cls their class)
        assert fd.cls is not None
        return self.cls_ptr(fd.cls)

    # -- PR-O2b scope regions (docs/design/typedpython-aot.md 6.3.2 item 2, "Step 2 as implemented")

    def region_struct(self, fn_name: str, cls: str) -> str:
        """The C type of the region objects of class `cls` in function `fn_name`: no count, and a field of a
        class that is region in the same function is a region pointer (so the field types are per function)."""
        return f"tpa_reg_{mangle(fn_name)}__{mangle(cls)}"

    def reg_field_ctype(self, fn_name: str, cls: str, fd: ir.FieldDecl) -> str:
        if fd.type is Type.OBJ:
            assert fd.cls is not None
            if self.alloc_plan.is_region(fn_name, fd.cls):
                return f"{self.region_struct(fn_name, fd.cls)} *"
            return self.cls_ptr(fd.cls)
        return self.field_ctype(cls, fd)

    def emit_region_decls(self) -> list[str]:
        """One struct per region (function, class), after the heap structs (a heap-typed field names one), and
        its `_free` / `_reset`: a class whose region objects own heap references (lists, big ints, heap-rc
        objects) first releases them with a walk over every object of the region (`_drop`); a region pointer
        field is never released (the object it names is freed with its own region)."""
        keys = sorted(self.alloc_plan.regions)
        if not keys:
            return []
        out: list[str] = []
        for fn_name, cls in keys:
            rs = self.region_struct(fn_name, cls)
            out.append(f"typedef struct {rs} {rs};")
        out.append("")
        for fn_name, cls in keys:
            rs = self.region_struct(fn_name, cls)
            decl = self.cls_analysis.classes[cls]
            out.append(f"struct {rs} {{")
            for fd in decl.field_decls:
                ft = self.reg_field_ctype(fn_name, cls, fd)
                sep = "" if ft.endswith("*") else " "
                out.append(f"    {ft}{sep}f_{mangle(fd.name)};")
            out.append("};")
        out.append("")
        for fn_name, cls in keys:
            rs = self.region_struct(fn_name, cls)
            decl = self.cls_analysis.classes[cls]
            owned: list[str] = []
            for fd in decl.field_decls:
                if fd.type is Type.OBJ:
                    if fd.cls is not None and not self.alloc_plan.is_region(fn_name, fd.cls):
                        owned.append(f"        tpa_cls_{mangle(fd.cls)}_release(p->f_{mangle(fd.name)});")
                else:
                    self._emit_field_release(owned, "        ", cls, fd)
            if owned:
                out.append(f"static void {rs}_drop(tpa_region *r) TPA_UNUSED;")
                out.append(f"static void {rs}_drop(tpa_region *r)")
                out.append("{")
                out.append(f"    TPA_REGION_FOREACH({rs}, p, r) {{")
                out += owned
                out.append("    }")
                out.append("}")
            for op in ("free", "reset"):
                out.append(f"static void {rs}_{op}(tpa_region *r) TPA_UNUSED;")
                out.append(f"static void {rs}_{op}(tpa_region *r)")
                out.append("{")
                if owned:
                    out.append(f"    {rs}_drop(r);")
                out.append(f"    tpa_region_{op}(r);")
                out.append("}")
        out.append("")
        return out

    def gc_class(self, cls: str) -> bool:
        """PR-O2c: `cls` is cyclic-capable, its heap objects are tracked by the cycle collector."""
        return self.cls_analysis.is_cyclic(cls)

    def emit_class_decls(self) -> list[str]:
        """Headerless struct definitions, retain/release helpers (PR-O1, docs/design/typedpython-aot.md 6.3.1).
        PR-O2c: the struct of a cyclic-capable class starts with the collector's header (`tpa_gc_head gc;`
        before `rc`, the layout `tp_aot_gc.h` reads the count through), its release untracks the object when
        the count reaches zero, and the class gets its `_gc_traverse` (the fields of cyclic-capable classes,
        the collector's edges), `_gc_clear` (every owned field emptied and released) and `_gc_type`."""
        if not self.module.classes:
            return []
        out: list[str] = []
        for decl in self.module.classes:
            mc = mangle(decl.name)
            out.append(f"typedef struct tpa_cls_{mc} tpa_cls_{mc};")
        out.append("")
        for decl in self.module.classes:
            mc = mangle(decl.name)
            out.append(f"struct tpa_cls_{mc} {{")
            if self.gc_class(decl.name):
                out.append("    tpa_gc_head gc;")
            out.append("    tpa_rc_t rc;")
            for fd in decl.field_decls:
                ft = self.field_ctype(decl.name, fd)
                sep = "" if ft.endswith("*") else " "
                out.append(f"    {ft}{sep}f_{mangle(fd.name)};")
            out.append("};")
        out.append("")
        for decl in self.module.classes:
            mc = mangle(decl.name)
            out.append(f"static inline void tpa_cls_{mc}_retain(tpa_cls_{mc} *p) TPA_UNUSED;")
            out.append(f"static inline void tpa_cls_{mc}_retain(tpa_cls_{mc} *p) {{ if (p != NULL) {{ TPA_RC_INC(p); }} }}")
            out.append(f"static void tpa_cls_{mc}_release(tpa_cls_{mc} *p) TPA_UNUSED;")
        out.append("")
        for decl in self.module.classes:
            mc = mangle(decl.name)
            tf = self.cls_analysis.tail_field.get(decl.name)
            # review of #508, F1: a class with a field of a class type releases it by a call, which nests; its
            # release counts the depth and parks the object past TPA_TRASH_LIMIT (`tpa_trash_*`, tp_aot.h), the
            # parked object freed later by `_drop`. A class with no such field cannot nest and is unchanged.
            # (tier S2a: a list of instances releases them by calls too, one list frame per level)
            trash = any(fd.type in (Type.OBJ, Type.OBJ_LIST) and fd.cls is not None for fd in decl.field_decls)
            if trash:
                out.append(f"static void tpa_cls_{mc}_drop(void *v);")
            out.append(f"static void tpa_cls_{mc}_release(tpa_cls_{mc} *p)")
            out.append("{")
            if tf is not None:
                out.append("    while (p != NULL) {")
                out.append("        if (!TPA_RC_DEC_IS_ZERO(p)) return;")
                if self.gc_class(decl.name):
                    out.append("        tpa_gc_untrack(&p->gc);")
                if trash:
                    out.append(f"        if (!tpa_trash_enter(p, tpa_cls_{mc}_drop)) return;")
                out.append(f"        tpa_cls_{mc} *next = p->f_{mangle(tf)};")
                for fd in decl.field_decls:
                    if fd.name == tf:
                        continue
                    self._emit_field_release(out, "        ", decl.name, fd)
                out.append("        free(p);")
                if trash:
                    out.append("        tpa_trash_leave();")
                out.append("        p = next;")
                out.append("    }")
            else:
                out.append("    if (p == NULL) return;")
                out.append("    if (!TPA_RC_DEC_IS_ZERO(p)) return;")
                if self.gc_class(decl.name):
                    out.append("    tpa_gc_untrack(&p->gc);")
                if trash:
                    out.append(f"    if (!tpa_trash_enter(p, tpa_cls_{mc}_drop)) return;")
                for fd in decl.field_decls:
                    self._emit_field_release(out, "    ", decl.name, fd)
                out.append("    free(p);")
                if trash:
                    out.append("    tpa_trash_leave();")
            out.append("}")
            if trash:
                # a parked object: its count is zero and it is untracked; free its fields and itself (and continue
                # down the tail field as the release loop would)
                out.append(f"static void tpa_cls_{mc}_drop(void *v)")
                out.append("{")
                out.append(f"    tpa_cls_{mc} *p = (tpa_cls_{mc} *)v;")
                if tf is not None:
                    out.append(f"    tpa_cls_{mc} *next = p->f_{mangle(tf)};")
                for fd in decl.field_decls:
                    if fd.name == tf:
                        continue
                    self._emit_field_release(out, "    ", decl.name, fd)
                out.append("    free(p);")
                if tf is not None:
                    out.append(f"    tpa_cls_{mc}_release(next);")
                out.append("}")
        out.append("")
        for decl in self.module.classes:
            if self.gc_class(decl.name):
                out += self._emit_gc_functions(decl)
        return out

    def _emit_gc_functions(self, decl: ir.ClassDecl) -> list[str]:
        """PR-O2c, one cyclic-capable class: `traverse` visits every field that holds a tracked object (a field
        of a cyclic-capable class; NULL skipped), `clear` empties every owned field before releasing what it held
        (so a release that reaches this object again finds nothing), and the type record the collector calls."""
        mc = mangle(decl.name)
        out: list[str] = []
        out.append(f"static void tpa_cls_{mc}_gc_traverse(tpa_gc_head *h, tpa_gc_visit visit, void *arg) TPA_UNUSED;")
        out.append(f"static void tpa_cls_{mc}_gc_traverse(tpa_gc_head *h, tpa_gc_visit visit, void *arg)")
        out.append("{")
        out.append(f"    tpa_cls_{mc} *p = (tpa_cls_{mc} *)h;")
        edges = [fd for fd in decl.field_decls
                 if fd.type in (Type.OBJ, Type.OBJ_LIST) and fd.cls is not None and self.gc_class(fd.cls)]
        for fd in edges:
            mf = mangle(fd.name)
            if fd.type is Type.OBJ_LIST:        # tier S2a: a list of a cyclic-capable class is tracked (tp_aot_gc.h)
                out.append(f"    if (p->f_{mf} != NULL) visit(&TPA_GC_LIST_OF(p->f_{mf})->gc, arg);")
            else:
                out.append(f"    if (p->f_{mf} != NULL) visit(&p->f_{mf}->gc, arg);")
        if not edges:
            out.append("    (void)p; (void)visit; (void)arg;")
        out.append("}")
        out.append(f"static void tpa_cls_{mc}_gc_clear(tpa_gc_head *h) TPA_UNUSED;")
        out.append(f"static void tpa_cls_{mc}_gc_clear(tpa_gc_head *h)")
        out.append("{")
        out.append(f"    tpa_cls_{mc} *p = (tpa_cls_{mc} *)h;")
        cleared = 0
        for fd in decl.field_decls:
            cleared += self._emit_field_clear(out, "    ", decl.name, fd)
        if not cleared:
            out.append("    (void)p;")
        out.append("}")
        out.append(f"static const tpa_gc_type tpa_cls_{mc}_gc_type = {{ tpa_cls_{mc}_gc_traverse, tpa_cls_{mc}_gc_clear }};")
        out.append("")
        return out

    def _emit_field_clear(self, out: list[str], ind: str, cls: str, fd: ir.FieldDecl) -> int:
        """`clear` of one field: the slot is emptied first, then what it held is released. Returns 1 for an
        owned field, 0 for a scalar."""
        mf = mangle(fd.name)
        if fd.type is Type.OBJ and fd.cls is not None:
            ptr, rel = self.cls_ptr(fd.cls), f"tpa_cls_{mangle(fd.cls)}_release"
        elif fd.type is Type.F64_ARRAY:
            ptr, rel = "tpa_f64_list *", "tpa_f64_release"
        elif fd.type in (Type.STR, Type.STR_OPT):
            ptr, rel = "tpa_str *", "tpa_str_release"
        elif fd.type is Type.OBJ_LIST:
            ptr, rel = "tpa_obj_list *", "tpa_obj_list_release"
        elif fd.type is Type.I64_ARRAY:
            big = self.reps.field_list_big(cls, fd.name)
            ptr, rel = ("tpa_int_list *", "tpa_int_list_release") if big else ("tpa_i64_list *", "tpa_i64_release")
        elif fd.type is Type.I64 and self.reps.field(cls, fd.name) == TAGGED:
            out.append(f"{ind}tpa_int_clear(&p->f_{mf});")
            return 1
        else:
            return 0
        out.append(f"{ind}{{")
        out.append(f"{ind}    {ptr}f = p->f_{mf};")
        out.append(f"{ind}    p->f_{mf} = NULL;")
        out.append(f"{ind}    {rel}(f);")
        out.append(f"{ind}}}")
        return 1

    def _emit_field_release(self, out: list[str], ind: str, cls: str, fd: ir.FieldDecl) -> None:
        mf = mangle(fd.name)
        if fd.type is Type.OBJ and fd.cls is not None:
            out.append(f"{ind}tpa_cls_{mangle(fd.cls)}_release(p->f_{mf});")
        elif fd.type is Type.F64_ARRAY:
            out.append(f"{ind}tpa_f64_release(p->f_{mf});")
        elif fd.type in (Type.STR, Type.STR_OPT):
            out.append(f"{ind}tpa_str_release(p->f_{mf});")
        elif fd.type is Type.OBJ_LIST:
            out.append(f"{ind}tpa_obj_list_release(p->f_{mf});")
        elif fd.type is Type.I64_ARRAY:
            fn = "tpa_int_list_release" if self.reps.field_list_big(cls, fd.name) else "tpa_i64_release"
            out.append(f"{ind}{fn}(p->f_{mf});")
        elif fd.type is Type.I64 and self.reps.field(cls, fd.name) == TAGGED:
            out.append(f"{ind}tpa_int_release(p->f_{mf});")

    def emit_class_ctors(self) -> list[str]:
        """Constructor wrappers `tpa_cls_<C>_new(...)` (after function prototypes so they can call `__init__`)."""
        if not self.module.classes:
            return []
        out: list[str] = []
        for decl in self.module.classes:
            mc = mangle(decl.name)
            init_fn = self.fns.get(f"{decl.name}.__init__")
            c_params: list[str] = []
            call_args: list[str] = ["obj"]
            if init_fn is not None:
                for p in init_fn.params[1:]:
                    if isinstance(p, ir.ArrayParam) and p.type in LIST:
                        c_params.append(f"{list_ptr(self.array_kind(init_fn.name, p.name, p.type))}a_{mangle(p.name)}")
                    elif isinstance(p, ir.Param) and p.type is Type.OBJ and p.cls is not None:
                        c_params.append(f"{self.cls_ptr(p.cls)}a_{mangle(p.name)}")
                    elif isinstance(p, ir.Param) and p.type in (Type.STR, Type.STR_OPT):
                        c_params.append(f"tpa_str *a_{mangle(p.name)}")          # borrowed, as `__init__` takes it
                    elif isinstance(p, ir.Param) and p.type is Type.STR_LIST:
                        c_params.append(f"tpa_str_list *a_{mangle(p.name)}")
                    elif isinstance(p, ir.Param) and p.type is Type.OBJ_LIST:
                        c_params.append(f"tpa_obj_list *a_{mangle(p.name)}")      # tier S2a, borrowed
                    elif isinstance(p, ir.Param) and p.type in CTYPE:
                        ct = "tpa_int" if self.tagged_param(init_fn, p) else CTYPE[p.type]
                        c_params.append(f"{ct} a_{mangle(p.name)}")
                    call_args.append(f"a_{mangle(p.name)}")
            c_params.append(f"tpa_cls_{mc} **out")
            out.append(f"static int tpa_cls_{mc}_new({', '.join(c_params)}) TPA_UNUSED;")
            out.append(f"static int tpa_cls_{mc}_new({', '.join(c_params)})")
            out.append("{")
            if self.gc_class(decl.name):
                # PR-O2c: the collector's safepoint, before the allocation: every argument is held by the caller
                # (an owned temporary, an owned local, a borrowed parameter its caller holds, a field of a live
                # object) and `__init__` has not started
                out.append(f"    tpa_cls_{mc} *obj;")
                out.append("    tpa_gc_safepoint();")
                out.append(f"    obj = (tpa_cls_{mc} *)calloc(1, sizeof(tpa_cls_{mc}));")
            else:
                out.append(f"    tpa_cls_{mc} *obj = (tpa_cls_{mc} *)calloc(1, sizeof(tpa_cls_{mc}));")
            out.append("    if (obj == NULL) {")
            out.append('        tpa_raise(TPA_MemoryError, "");')
            out.append("        return -1;")
            out.append("    }")
            out.append("    obj->rc = 1;")
            if self.gc_class(decl.name):
                out.append(f"    tpa_gc_track(&obj->gc, &tpa_cls_{mc}_gc_type);")
            for fd in decl.field_decls:
                if fd.type is Type.I64 and self.reps.field(decl.name, fd.name) == TAGGED:
                    out.append(f"    obj->f_{mangle(fd.name)} = TPA_INT_ZERO;")
            if init_fn is not None:
                out.append(f"    if (tpf_{mangle(init_fn.name)}({', '.join(call_args)}) != 0) {{")
                out.append(f"        tpa_cls_{mc}_release(obj);")
                out.append("        return -1;")
                out.append("    }")
            out.append("    *out = obj;")
            out.append("    return 0;")
            out.append("}")
        out.append("")
        return out

    def tuple_kinds(self, f: ir.Function) -> tuple[Type, ...] | None:
        """The element types of the tuple `f` returns, when it does and they are scalars."""
        if f.returns is not Type.OBJ:
            return None
        kinds = ir.aot_tuple_kinds(f)
        return kinds if kinds is not None and all(k in CTYPE or k is Type.STR for k in kinds) else None     # (str: S2a)

    def obj_list_wrappers(self) -> list[str]:
        """Tier S2a: the `void *` retain / release a list of each element class carries."""
        out = []
        for cls in sorted(self.list_elem_classes):
            mc = mangle(cls)
            out.append(f"static void tpa_cls_{mc}__vret(void *p) {{ tpa_cls_{mc}_retain((tpa_cls_{mc} *)p); }}")
            out.append(f"static void tpa_cls_{mc}__vrel(void *p) {{ tpa_cls_{mc}_release((tpa_cls_{mc} *)p); }}")
        return out

    @functools.cached_property
    def grown(self) -> frozenset[str]:
        return frozenset(self.grown_kinds())

    def grown_kinds(self) -> list[str]:
        """Tier S2a: the list kinds ("f64", "i64", "int") some function changes the length of, sorted."""
        kinds = set()
        for f in self.fns.values():
            types = {p.name: p.type for p in f.params if isinstance(p, ir.ArrayParam)}
            types.update(f.locals)
            for s in f.body:
                for n in rep_mod.walk(s):
                    if isinstance(n, ir.LIST_GROW_NODES + ir.LIST_SHRINK_NODES) \
                            and (types.get(n.array) in LIST or types.get(n.array) is Type.STR_LIST):
                        # (a list of class instances, OBJ_LIST, is always growable: no define)
                        kinds.add(self.array_kind(f.name, n.array, types[n.array]))
        return sorted(kinds)

    def array_kind(self, fn: str, name: str, t: Type) -> str:
        """The kind of the list `name` of function `fn` (of array type `t`)."""
        if t is Type.STR_LIST:
            return "str"                                     # tier S2a
        if t is Type.I64_ARRAY:
            return "int" if self.reps.list_big(fn, name) else "i64"
        return LIST[t][0]

    def ret_kind(self, f: ir.Function) -> str:
        """The kind of the list function `f` returns."""
        if f.returns is Type.I64_ARRAY:
            return "int" if self.reps.return_big(f.name) else "i64"
        return LIST[f.returns][0]

    def tagged_param(self, f: ir.Function, p: object) -> bool:
        return isinstance(p, ir.Param) and p.type is Type.I64 and self.reps.name(f.name, p.name) == TAGGED

    def owned_param(self, f: ir.Function, p: object) -> bool:
        """A TAGGED or class parameter the body assigns: the caller's value is borrowed, so the body works on an owned copy."""
        if self.tagged_param(f, p):
            return p.name in assigned_names(f)                                   # type: ignore[attr-defined]
        if isinstance(p, ir.Param) and p.type is Type.OBJ and p.cls is not None:
            return p.name in assigned_names(f)
        if isinstance(p, ir.Param) and p.type in (Type.STR, Type.STR_LIST, Type.STR_OPT, Type.OBJ_LIST):     # tier S1, S2a
            return p.name in assigned_names(f)
        return False

    def param_c(self, f: ir.Function, p: object) -> str:
        """The C name of a parameter (`pv_` for the borrowed original of an owned one)."""
        return ("pv_" if self.owned_param(f, p) else "v_") + mangle(p.name)       # type: ignore[attr-defined]

    def out_ctype(self, f: ir.Function, kind: Type, rep: str) -> str:
        if kind is Type.STR:
            self.uses_str = True
            return "tpa_str *"                      # tier S2a: a str element of a returned tuple, a new reference
        return "tpa_int" if kind is Type.I64 and rep == TAGGED else CTYPE[kind]

    def signature(self, f: ir.Function) -> str:
        params = []
        for p in f.params:
            if isinstance(p, ir.ArrayParam) and p.type in LIST:
                params.append(f"{list_ptr(self.array_kind(f.name, p.name, p.type))}v_{mangle(p.name)} TPA_UNUSED")
            elif isinstance(p, ir.Param) and p.type is Type.OBJ and p.cls is not None:
                params.append(f"{self.cls_ptr(p.cls)}{self.param_c(f, p)} TPA_UNUSED")
            elif isinstance(p, ir.Param) and p.type in (Type.STR, Type.STR_OPT):
                self.uses_str = True
                params.append(f"tpa_str *{self.param_c(f, p)} TPA_UNUSED")     # borrowed from the caller
            elif isinstance(p, ir.Param) and p.type is Type.STR_LIST:
                self.uses_str = True
                params.append(f"tpa_str_list *{self.param_c(f, p)} TPA_UNUSED")
            elif isinstance(p, ir.Param) and p.type is Type.OBJ_LIST:
                params.append(f"tpa_obj_list *{self.param_c(f, p)} TPA_UNUSED")      # tier S2a, borrowed
            elif isinstance(p, ir.Param) and p.type in CTYPE:
                ct = "tpa_int" if self.tagged_param(f, p) else CTYPE[p.type]
                params.append(f"{ct} {self.param_c(f, p)} TPA_UNUSED")
            else:
                params.append(f"int v_{mangle(p.name)} TPA_UNUSED")        # reported by `_Fn.run`
        kinds = self.tuple_kinds(f)
        if f.returns in LIST:
            params.append(f"{list_ptr(self.ret_kind(f))}*out")
        elif f.returns is Type.OBJ and f.return_cls is not None:
            params.append(f"{self.cls_ptr(f.return_cls)}*out")
        elif f.returns in (Type.STR, Type.STR_OPT):
            self.uses_str = True
            params.append("tpa_str **out")                                    # a new reference the caller owns
        elif f.returns is Type.STR_LIST:
            self.uses_str = True
            params.append("tpa_str_list **out")
        elif f.returns is Type.OBJ_LIST:
            params.append("tpa_obj_list **out")                               # tier S2a, a new reference
        elif kinds is not None:
            params += [f"{self.out_ctype(f, k, self.reps.element(f.name, i))} *out{i}" for i, k in enumerate(kinds)]
        elif f.returns in CTYPE:
            params.append(f"{self.out_ctype(f, f.returns, self.reps.returns(f.name))} *out")
        return f"static int tpf_{mangle(f.name)}({', '.join(params) or 'void'})"

    def prototype(self, f: ir.Function) -> str:
        return self.signature(f) + " TPA_UNUSED;"


class _Fn:
    def __init__(self, g: _Gen, f: ir.Function) -> None:
        self.g = g
        self.f = f
        self.lines: list[str] = []
        self.depth = 1
        self.temps: list[tuple[str, str, str]] = []        # (C type, name, initialiser)
        self.cur: int | None = None
        self.cleanup_used = False
        self.bind_kind: str | None = None       # the kind of the list a creation on the right of an assignment or a return becomes
        body = g.bodies.get(f.name)
        self.orig: list[ast.stmt] = resume.statements(body) if body is not None else []   # type: ignore[arg-type]
        self.found: list[Finding] = []
        self.types: dict[str, Type] = {}
        for p in f.params:
            if isinstance(p, (ir.Param, ir.ArrayParam)):
                self.types[p.name] = p.type
        self.types.update(f.locals)
        self.tagged_slots: list[str] = []        # TAGGED temporaries: released at the cleanup point
        self.str_slots: list[str] = []           # `char *` temporaries (a formatted TAGGED int): freed there too
        self.obj_slots: list[tuple[str, str]] = []   # (cls, slot_name) class temporaries: released at cleanup
        self.list_slots: list[tuple[str, str]] = []  # (kind, slot_name) retained mutable list field reads
        self.pystr_slots: list[str] = []         # tier S1: `tpa_str *` temporaries, each owns its value; released at cleanup
        self.objlist_slots: list[str] = []       # tier S2a: `tpa_obj_list *` temporaries, released at cleanup
        self.strlist_slots: list[str] = []       # tier S1b: `tpa_str_list *` temporaries, the same
        self.assigned: set[str] = assigned_names(f)
        self.regions = g.alloc_plan.fn_regions(f.name)      # PR-O2b: class -> its region in this function
        self.borrowed_locals: set[str] = self._find_borrowed_locals(f)

    def _find_borrowed_locals(self, f: ir.Function) -> set[str]:
        """Elision condition 3: a class local initialized once at top level from an immutable field of
        an unrebound owner (param or local) may be borrowed with no retain/release."""
        if not f.local_classes:
            return set()
        counts: dict[str, int] = {}
        for s in f.body:
            for n in rep_mod.walk(s):
                if isinstance(n, ir.Assign):
                    counts[n.target] = counts.get(n.target, 0) + 1
        out: set[str] = set()
        for s in f.body:
            if isinstance(s, ir.Assign) and s.target in f.local_classes and counts.get(s.target, 0) == 1 \
                    and not self.is_region_local(s.target):
                v = s.value
                if (isinstance(v, ir.FieldGet) and v.type is Type.OBJ
                        and self.g.cls_analysis.field_elidable(v.cls, v.field)
                        and isinstance(v.obj, ir.Local) and v.obj.name not in self.assigned
                        and not self.is_region_local(v.obj.name)):
                    out.add(s.target)
        return out

    # -- PR-O2b scope regions

    def is_region_local(self, name: str) -> bool:
        """A local of a class that is region in this function (never a parameter, never a virtual local): its
        C type is the region pointer, it is never counted and never released."""
        return (name in self.f.local_classes and not self.is_virtual(name)
                and self.f.local_classes[name][0] in self.regions)

    def expr_region_cls(self, e: ir.Expr) -> str | None:
        """The class of `e` when `e` is a region pointer (the emitter's twin of `alloc._RegionAnalysis.region_valued`)."""
        if isinstance(e, ir.Local):
            return self.f.local_classes[e.name][0] if self.is_region_local(e.name) else None
        if isinstance(e, ir.FieldGet):
            if self.expr_region_cls(e.obj) is None:
                return None
            fd = self.g.cls_analysis.field_map.get((e.cls, e.field))
            return fd.cls if fd is not None and fd.cls is not None and fd.cls in self.regions else None
        if isinstance(e, ir.New):
            return e.cls if e.cls in self.regions else None
        return None

    def reg_ptr(self, cls: str) -> str:
        return f"{self.g.region_struct(self.f.name, cls)} *"

    def region_var(self, cls: str) -> str:
        return f"rg_{mangle(cls)}"

    def no_region(self, e: ir.Expr, what: str) -> bool:
        """The analysis keeps region pointers away from `what`; a slip is a finding, never wrong C."""
        if self.expr_region_cls(e) is not None:
            self.bad("aot/region", what, f"a region pointer reaches {what}, which the region analysis must prevent")
            return False
        return True

    def region_value_ok(self, e: ir.Expr, cls: str, what: str) -> bool:
        """`e`, stored into a region pointer of class `cls` (a region local or a region object's field), is a region
        pointer of that class or None."""
        if (isinstance(e, ir.Const) and e.value is None) or self.expr_region_cls(e) == cls:
            return True
        self.bad("aot/region", what, f"a heap pointer reaches {what}, which the region analysis must prevent")
        return False

    def loop_prologue(self, loop: ir.Stmt) -> None:
        """The top of an iteration of `loop`: every region this iteration owns is released and reset."""
        for cls, info in self.regions.items():
            if info.loop is loop:
                self.emit(f"{self.g.region_struct(self.f.name, cls)}_reset(&{self.region_var(cls)});")

    def oslot(self, cls: str) -> str:
        t = self.tmp(self.g.cls_ptr(cls), "NULL")
        self.obj_slots.append((cls, t))
        return t

    def is_virtual(self, name: str) -> bool:
        return self.g.alloc_plan.is_virtual(self.f.name, name)

    def vfield(self, name: str, fname: str) -> str:
        return f"v_{mangle(name)}__f_{mangle(fname)}"

    # -- representation

    def rep(self, e: ir.Expr) -> str:
        return self.g.reps.of(self.f.name, e)

    def tagged_name(self, name: str) -> bool:
        return self.types.get(name) is Type.I64 and self.g.reps.name(self.f.name, name) == TAGGED

    def trusted(self, node: ir.Index | ir.StoreIndex) -> bool:
        """May the proved index `node` skip its bounds check? (aot/rep.py "The sound re-proof")"""
        return self.g.reps.trusted(self.f.name, node)

    def tslot(self) -> str:
        """A TAGGED temporary: empty until written, released at the cleanup point."""
        t = self.tmp("tpa_int", "TPA_INT_ZERO")
        self.tagged_slots.append(t)
        return t

    def sslot(self) -> str:
        t = self.tmp("char *", "NULL")
        self.str_slots.append(t)
        return t

    # -- tier S1 (PR-S1a): str values. `ev` of a STR node is a borrowed `tpa_str *`: a name, a literal, or a slot that
    # owns the value until the slot is next written or the function leaves.

    def pslot(self) -> str:
        """A str temporary, emptied now; an operation writes it only on success (`@` in `pstr`)."""
        self.g.uses_str = True
        t = self.tmp("tpa_str *", "NULL")
        self.pystr_slots.append(t)
        self.emit(f"tpa_str_clear(&{t});")
        return t

    def pstr(self, call: str) -> str:
        t = self.pslot()
        self.emit(f"if ({call.replace('@', '&' + t)}) {self.fail()}")
        return t

    def olslot(self) -> str:
        """A list-of-instances temporary, emptied now (tier S2a)."""
        t = self.tmp("tpa_obj_list *", "NULL")
        self.objlist_slots.append(t)
        self.emit(f"tpa_obj_list_release({t}); {t} = NULL;")
        return t

    def obj_fns(self, cls: str) -> str:
        """The retain and release a list of `cls` carries (generated `void *` wrappers, `_Gen.obj_list_wrappers`)."""
        self.g.list_elem_classes.add(cls)
        return f"tpa_cls_{mangle(cls)}__vret, tpa_cls_{mangle(cls)}__vrel"

    def ev_ObjListDisplay(self, e: ir.ObjListDisplay) -> str:
        vals = [self.ev(x) for x in e.elements]            # (each a borrowed instance, held by its own slot)
        t = self.olslot()
        alloc = "tpa_obj_list_alloc_gc" if self.g.gc_class(e.cls) else "tpa_obj_list_alloc"     # (review B4)
        self.emit(f"if ({alloc}({len(vals)}, {self.obj_fns(e.cls)}, &{t})) {self.fail()}")
        for i, v in enumerate(vals):
            self.emit(f"tpa_cls_{mangle(e.cls)}_retain({v}); {t}->d[{i}] = {v};")
        return t

    def ev_ObjListLen(self, e: ir.ObjListLen) -> str:
        v = self.ev(e.value)
        t = self.tmp("int64_t")
        self.emit(f"{t} = ({v})->len;")                     # read in evaluation order (the list can change length)
        return t

    def ev_ObjListIndex(self, e: ir.ObjListIndex) -> str:
        lst = self.ev(e.value)
        idx = self.index_c(e.index, "tpa_int_to_ssize") if self.rep(e.index) != TAGGED else None
        if idx is None:                                     # an index outside int64: IndexError, as a list read's
            v, n = self.ev(e.index), self.tmp("int64_t")
            self.emit(f"if (tpa_slot_int(({lst})->len, {v}, 0, &{n})) {self.fail()}")
            idx = n
        t = self.oslot(e.cls)
        self.emit("{")
        self.emit("    void *tpa_v_ = NULL;")
        self.emit(f"    if (tpa_obj_list_get({lst}, {idx}, &tpa_v_)) {self.fail()}")
        self.emit(f"    tpa_cls_{mangle(e.cls)}_release({t}); {t} = ({self.g.cls_ptr(e.cls).strip()})tpa_v_;")
        self.emit("}")
        return t

    def st_ObjListStore(self, s: ir.ObjListStore) -> None:
        v = self.ev(s.value)
        idx = self.ev(s.index)
        if self.rep(s.index) == TAGGED:
            n = self.tmp("int64_t")
            self.emit(f"if (tpa_slot_int(v_{mangle(s.array)}->len, {idx}, 1, &{n})) {self.fail()}")
            idx = n
        self.emit(f"if (tpa_obj_list_store(v_{mangle(s.array)}, {idx}, {v})) {self.fail()}")

    def st_StrListStore(self, s: ir.StrListStore) -> None:
        v = self.ev(s.value)
        idx = self.ev(s.index)
        if self.rep(s.index) == TAGGED:
            n = self.tmp("int64_t")
            self.emit(f"if (tpa_slot_int(v_{mangle(s.array)}->len, {idx}, 1, &{n})) {self.fail()}")
            idx = n
        self.emit(f"if (tpa_str_list_store(v_{mangle(s.array)}, {idx}, {v})) {self.fail()}")

    def st_ForObjList(self, s: ir.ForObjList) -> None:
        """`for var in value` over a list of instances: the list is held for the loop, its length read at every step,
        `var` takes a new reference to each item in turn (CPython's list iterator)."""
        held = self.tmp("tpa_obj_list *", "NULL")
        self.objlist_slots.append(held)
        self.emit(f"tpa_obj_list_set(&{held}, {self.ev(s.value)});")
        cls = self.f.local_classes.get(s.var, (None, False))[0]
        if cls is None:
            self.bad("aot/type", s.var, f"the class of the loop variable `{s.var}` is not known")
            return
        k = self.tmp("int64_t")
        self.emit(f"for ({k} = 0; {k} < {held}->len; {k}++) {{")
        self.depth += 1
        self.emit(f"tpa_cls_{mangle(cls)}_retain(({self.g.cls_ptr(cls).strip()}){held}->d[{k}]);")
        self.emit(f"tpa_cls_{mangle(cls)}_release(v_{mangle(s.var)});")
        self.emit(f"v_{mangle(s.var)} = ({self.g.cls_ptr(cls).strip()}){held}->d[{k}];")
        self.loop_prologue(s)
        self.stmts(s.body)
        self.depth -= 1
        self.emit("}")

    def lslot(self) -> str:
        """A list-of-str temporary, emptied now (tier S1b)."""
        self.g.uses_str = True
        t = self.tmp("tpa_str_list *", "NULL")
        self.strlist_slots.append(t)
        self.emit(f"tpa_str_list_clear(&{t});")
        return t

    def ev_StrListDisplay(self, e: ir.StrListDisplay) -> str:
        parts = [self.str_value(x, "an element of a list of str") for x in e.elts]
        if not parts:
            return self.lpstr("tpa_str_list__alloc(0, @)")
        t = self.lslot()
        self.emit("{")
        self.emit(f"    tpa_str *parts_[{len(parts)}];")
        for i, v in enumerate(parts):
            self.emit(f"    parts_[{i}] = {v};")
        self.emit(f"    if (tpa_str_list_from(parts_, {len(parts)}, &{t})) {self.fail()}")
        self.emit("}")
        return t

    def ev_StrListLen(self, e: ir.StrListLen) -> str:
        v = self.ev(e.value)
        if "str" in self.g.grown:            # tier S2a: a length that can change is read in evaluation order
            t = self.tmp("int64_t")
            self.emit(f"{t} = ({v})->len;")
            return t
        return f"(({v})->len)"

    def ev_StrListIndex(self, e: ir.StrListIndex) -> str:
        lst = self.ev(e.value)
        i = self.raw_index(e.index)
        return self.pstr(f"tpa_str_list_get({lst}, {i}, @)")

    def ssize(self, e: ir.Expr) -> str:
        """An int argument CPython converts to Py_ssize_t (OverflowError outside 64 bits)."""
        v = self.ev(e)
        if self.rep(e) == RAW:
            return v
        n = self.tmp("int64_t")
        self.emit(f"if (tpa_int_to_ssize({v}, &{n})) {self.fail()}")
        return n

    def bounds(self, start: ir.Expr | None, end: ir.Expr | None) -> tuple[str, str, str]:
        """find / startswith / endswith bounds: (start, end, the TPA_SLICE_* flags of the given ones), clamped."""
        s = "0" if start is None else self.clamp(start)
        en = "0" if end is None else self.clamp(end)
        has = [f for f, b in (("TPA_SLICE_START", start), ("TPA_SLICE_STOP", end)) if b is not None]
        return s, en, " | ".join(has) or "0"

    def ev_StrMethod(self, e: ir.StrMethod) -> str:
        s = self.str_value(e.recv, f"the receiver of str.{e.name}")
        a = list(e.args)
        n = e.name
        if n == "split":
            sep = None if not a or a[0] is None else self.str_value(a[0], "split's sep")
            maxsplit = "-1" if len(a) < 2 or a[1] is None else self.ssize(a[1])
            if sep is None:
                self.g.ucd.add("space")
                return self.lpstr(f"tpa_str_split_ws({s}, {maxsplit}, @)")
            return self.lpstr(f"tpa_str_split_sep({s}, {sep}, {maxsplit}, @)")
        if n == "join":
            return self.pstr(f"tpa_str_join_list({s}, {self.ev(a[0])}, @)")
        if n in ("strip", "lstrip", "rstrip"):
            how = {"strip": 3, "lstrip": 1, "rstrip": 2}[n]
            if not a or a[0] is None:
                self.g.ucd.add("space")
                return self.pstr(f"tpa_str_strip_ws({s}, {how}, @)")
            chars = self.str_value(a[0], f"{n}'s chars")
            return self.pstr(f"tpa_str_strip({s}, {chars}, {how}, @)")
        if n in ("lower", "upper"):
            self.g.ucd.add(n)
            return self.pstr(f"tpa_str_{n}({s}, @)")
        if n in ("isalpha", "isdigit"):
            self.g.ucd.add(n[2:])
            return f"tpa_str_{n}({s})"
        if n == "find":
            sub = self.str_value(a[0], "find's sub")
            st, en, has = self.bounds(a[1] if len(a) > 1 else None, a[2] if len(a) > 2 else None)
            t = self.tmp("int64_t")
            self.emit(f"{t} = tpa_str_find_range({s}, {sub}, {st}, {en}, {has});")
            return t
        if n == "replace":
            old = self.str_value(a[0], "replace's old")
            new = self.str_value(a[1], "replace's new")
            count = "-1" if len(a) < 3 or a[2] is None else self.ssize(a[2])
            return self.pstr(f"tpa_str_replace({s}, {old}, {new}, {count}, @)")
        if n in ("startswith", "endswith"):
            prefixes = [self.str_value(x, f"{n}'s prefix") for x in a[2:]]      # evaluated first, as in the source
            st, en, has = self.bounds(a[0], a[1])
            suffix = 1 if n == "endswith" else 0
            tests = [f"tpa_str_tailmatch({s}, {x}, {st}, {en}, {has}, {suffix})" for x in prefixes]
            return "(" + " || ".join(tests) + ")"
        return self.bad("aot/str-method", n, f"str.{n} has no AOT form")

    def lpstr(self, call: str) -> str:
        t = self.lslot()
        self.emit(f"if ({call.replace('@', '&' + t)}) {self.fail()}")
        return t

    def str_value(self, e: ir.Expr, what: str) -> str:
        if e.type is not Type.STR:
            return self.bad("aot/type", what, f"{what} is a {getattr(e.type, 'value', '?')}, not a str")
        return self.ev(e)

    def clamp(self, e: ir.Expr) -> str:
        """A slice bound or step: an int64 as it is, an int outside 64 bits clamped (CPython's _PyEval_SliceIndex)."""
        v = self.ev(e)
        return v if self.rep(e) == RAW else f"tpa_int_clamp_index({v})"

    def str_of(self, x: ir.Expr, repr_: bool) -> str:
        """`str(x)` / `repr(x)` of an int, float, bool, str or list of str as a borrowed str."""
        if x.type is Type.STR_LIST:
            return self.pstr(f"tpa_str_list_repr({self.ev(x)}, @)")      # str() of a list is its repr
        if x.type is Type.STR_OPT:
            return self.pstr(f"tpa_str_of_opt({self.ev(x)}, {1 if repr_ else 0}, @)")
        if x.type is Type.STR:
            v = self.ev(x)
            return self.pstr(f"tpa_str_repr({v}, @)") if repr_ else v
        v = self.ev(x)
        if x.type is Type.I64:
            if self.rep(x) == TAGGED:
                return self.pstr(f"tpa_str_from_int({v}, @)")
            return self.pstr(f"tpa_str_from_i64({v}, @)")
        if x.type is Type.F64:
            return self.pstr(f"tpa_str_from_f64({v}, @)")
        if x.type is Type.BOOL:
            return self.pstr(f"tpa_str_from_bool({v}, @)")
        return self.bad("aot/type", "str", f"str() of a {getattr(x.type, 'value', '?')} has no AOT form")

    def ev_StrNarrow(self, e: ir.StrNarrow) -> str:
        return f"TPA_STR_NARROW({self.ev(e.value)})"

    def ev_StrConcat(self, e: ir.StrConcat) -> str:
        a = self.str_value(e.left, "the left operand of +")
        b = self.str_value(e.right, "the right operand of +")
        return self.pstr(f"tpa_str_concat({a}, {b}, @)")

    def ev_StrRepeat(self, e: ir.StrRepeat) -> str:
        if e.count_first:
            n_val = self.ev(e.count)
            s = self.str_value(e.value, "the str operand of *")
        else:
            s = self.str_value(e.value, "the str operand of *")
            n_val = self.ev(e.count)
        if self.rep(e.count) == TAGGED:            # CPython's OverflowError for a count outside 64 bits
            n = self.tmp("int64_t")
            self.emit(f"if (tpa_int_to_count({n_val}, &{n})) {self.fail()}")
        else:
            n = n_val
        return self.pstr(f"tpa_str_repeat({s}, {n}, @)")

    def ev_StrCompare(self, e: ir.StrCompare) -> str:
        if Type.STR_OPT in (e.left.type, e.right.type):       # tier S2a: None equals only None
            a, b = self.ev(e.left), self.ev(e.right)
            return f"({'!' if e.op is ir.CompareKind.NE else ''}tpa_str_eq_opt({a}, {b}))"
        a = self.str_value(e.left, "a str comparison")
        b = self.str_value(e.right, "a str comparison")
        if e.op is ir.CompareKind.EQ:
            return f"tpa_str_eq({a}, {b})"
        if e.op is ir.CompareKind.NE:
            return f"(!tpa_str_eq({a}, {b}))"
        return f"(tpa_str_cmp({a}, {b}) {e.op.value} 0)"

    def ev_StrContains(self, e: ir.StrContains) -> str:
        needle = self.str_value(e.needle, "the left operand of `in`")
        hay = self.str_value(e.hay, "the right operand of `in`")
        return f"({'!' if e.negate else ''}tpa_str_contains({hay}, {needle}))"

    def ev_StrLen(self, e: ir.StrLen) -> str:
        return f"(({self.str_value(e.value, 'the argument of len()')})->len)"

    def ev_StrIndex(self, e: ir.StrIndex) -> str:
        s = self.str_value(e.value, "the indexed str")
        i = self.raw_index(e.index)                # an int outside 64 bits: CPython's IndexError
        return self.pstr(f"tpa_str_index({s}, {i}, @)")

    def ev_StrSlice(self, e: ir.StrSlice) -> str:
        s = self.str_value(e.value, "the sliced str")
        vals, has = [], []
        for b, flag in ((e.start, "TPA_SLICE_START"), (e.stop, "TPA_SLICE_STOP"), (e.step, "TPA_SLICE_STEP")):
            if b is None:
                vals.append("0")
            else:
                vals.append(self.clamp(b))
                has.append(flag)
        return self.pstr(f"tpa_str_slice({s}, {vals[0]}, {vals[1]}, {vals[2]}, {' | '.join(has) or '0'}, @)")

    def ev_StrOf(self, e: ir.StrOf) -> str:
        return self.str_of(e.value, e.repr)

    def ev_StrOrd(self, e: ir.StrOrd) -> str:
        s = self.str_value(e.value, "the argument of ord()")
        return self.fallible("int64_t", f"tpa_str_ord({s}, @)")

    def ev_StrChr(self, e: ir.StrChr) -> str:
        v = self.ev(e.value)
        if self.rep(e.value) == TAGGED:
            return self.pstr(f"tpa_str_chr_int({v}, @)")
        return self.pstr(f"tpa_str_chr_i64({v}, @)")

    def ev_ArgvStr(self, e: ir.ArgvStr) -> str:
        self.g.argv_int = True                      # the entry keeps the arguments whole (UTF-16 on Windows)
        i = self.raw_index(e.index)
        return self.pstr(f"tpa_str_argv({i}, @)")

    def ev_StrParseInt(self, e: ir.StrParseInt) -> str:
        s = self.str_value(e.value, "the argument of int()")
        return self.fallible_int(f"tpa_str_to_int({s}, @)")

    def ev_StrWrite(self, e: ir.StrWrite) -> str:
        s = self.str_value(e.value, "the argument of write()")
        if e.err:
            self.emit(f"tpa_str_write_err({s});")
        else:
            self.emit(f"if (tpa_str_print({s})) {self.fail()}")
        return f"(({s})->len)"

    def fstring_parts(self, f: ir.FString) -> list[str] | None:
        """Every part of an f-string as a borrowed str, evaluated in order (all of them before anything is built)."""
        parts = []
        for part in f.parts:
            if isinstance(part, ir.Const) and isinstance(part.value, str):
                parts.append(self.g.str_lit(part.value))
            elif isinstance(part, ir.FormatField):
                if part.precision is not None:
                    v = self.ev(part.value)
                    parts.append(self.pstr(f"tpa_str_format_f64_fixed({v}, {part.precision}, @)"))
                else:
                    parts.append(self.str_of(part.value, part.repr))
            else:
                self.bad("aot/f-string", "f-string", "an f-string part has a form the AOT mode does not know")
                return None
        return parts

    def join(self, parts: list[str]) -> str:
        if not parts:
            return self.g.str_lit("")
        if len(parts) == 1:
            return parts[0]
        self.emit("{")
        self.emit(f"    tpa_str *parts_[{len(parts)}];")
        for i, v in enumerate(parts):
            self.emit(f"    parts_[{i}] = {v};")
        t = self.pslot()
        self.emit(f"    if (tpa_str_join(parts_, {len(parts)}, &{t})) {self.fail()}")
        self.emit("}")
        return t

    def ev_FString(self, e: ir.FString) -> str:
        """An f-string that is a str value: every field is evaluated, in order, then the string is made."""
        parts = self.fstring_parts(e)
        return "NULL" if parts is None else self.join(parts)

    def fallible_int(self, call: str) -> str:
        """A TAGGED result: the slot is emptied, then written only by a successful call (`@` is its address)."""
        t = self.tslot()
        self.emit(f"tpa_int_clear(&{t});")
        self.emit(f"if ({call.replace('@', '&' + t)}) {self.fail()}")
        return t

    def tagged(self, e: ir.Expr) -> str:
        """The C expression of the int `e` as a borrowed TAGGED value (a RAW one is converted)."""
        if self.rep(e) == RAW and isinstance(e, ir.Const) and e.type is Type.I64 \
                and -(2**62) <= int(e.value) < 2**62:
            return f"tpa_int_small({_i64(int(e.value))})"
        v = self.ev(e)
        if self.rep(e) == TAGGED or e.type is not Type.I64:
            if e.type is Type.BOOL:
                return f"tpa_int_small((int64_t)({v}))"
            return v
        return self.fallible_int(f"tpa_int_from_i64({v}, @)")

    def raw_index(self, e: ir.Expr) -> str:
        """An int64 C expression for an index or a count: a RAW value as it is, a TAGGED one through the check that
        raises the reference's IndexError when it does not fit."""
        v = self.ev(e)
        if self.rep(e) == RAW:
            return v
        t = self.tmp("int64_t")
        self.emit(f"if (tpa_int_to_index({v}, &{t})) {self.fail()}")
        return t

    # -- helpers

    def line(self) -> int:
        if self.cur is not None and 0 <= self.cur < len(self.orig):
            return self.orig[self.cur].lineno
        return self.f.source_line

    def display(self) -> str:
        return "__main__" if self.f.name == MAIN else self.f.name

    def bad(self, rule: str, name: str, message: str) -> str:
        self.found.append(Finding(self.g.program.rel, self.line(), "error", None, rule, name, message,
                                  self.display()))
        return "0"

    def emit(self, s: str) -> None:
        self.lines.append("    " * self.depth + s)

    def tmp(self, ctype: str, init: str = "0") -> str:
        n = f"t{len(self.temps)}"
        self.temps.append((ctype, n, init))
        return n

    def list_tmp(self, kind: str) -> str:
        return self.tmp(list_ptr(kind).strip(), "NULL")

    def kind(self, name: str) -> str:
        """The kind of the list name `name` of this function."""
        return self.g.array_kind(self.f.name, name, self.types[name])

    def value_kind(self, e: ir.Expr) -> str | None:
        """The kind of the list a list name or a call gives (None for a creation or a copy, which take their target's)."""
        if isinstance(e, ir.Local) and e.name in self.types and self.types[e.name] in LIST:
            return self.kind(e.name)
        if isinstance(e, ir.Call):
            callee = self.g.fns.get(e.function)
            if callee is not None and callee.returns in LIST:
                return self.g.ret_kind(callee)
        return None

    def kinds_agree(self, e: ir.Expr, kind: str, what: str) -> bool:
        """A list name or a call result bound to a list of `kind` must be a list of that kind: the classification merges
        them, so a mismatch is a defect of the analysis, reported instead of generating a wrong access."""
        got = self.value_kind(e)
        if got is not None and got != kind:
            self.bad("aot/type", what, f"a list of kind {got} is used where one of kind {kind} is needed (the list "
                     "classification did not merge them)")
            return False
        return True

    def fail(self) -> str:
        self.cleanup_used = True
        return FAIL

    def leave(self) -> None:
        """Leave the function with status 0 through the one cleanup point."""
        self.cleanup_used = True
        self.emit("rc = 0;")
        self.emit("goto tp_cleanup;")

    def capture(self, fn):
        """Run `fn()` one level deeper and return (its result, the lines it emitted)."""
        saved, self.lines = self.lines, []
        self.depth += 1
        try:
            r = fn()
            return r, self.lines
        finally:
            self.depth -= 1
            self.lines = saved

    def fallible(self, ctype: str, call: str) -> str:
        t = self.tmp(ctype)
        self.emit(f"if ({call.replace('@', '&' + t)}) {self.fail()}")
        return t

    def ctype(self, t: Type, what: str) -> str:
        if t in CTYPE:
            return CTYPE[t]
        self.bad("aot/type", what, f"`{what}` has the type {t.value}, which is outside the first AOT step")
        return "int64_t"

    # -- finding the source text of an operation

    def expr_text(self, e: ir.Expr, op_cls: type) -> str | None:
        if self.cur is None or not 0 <= self.cur < len(self.orig):
            return None
        stmt = self.orig[self.cur]
        cands: list[ast.AST] = []
        for root in _roots(stmt):
            for n in ast.walk(root):
                if isinstance(n, ast.BinOp) and isinstance(n.op, op_cls) and op_cls is not ast.USub:
                    cands.append(n)
                elif isinstance(n, ast.UnaryOp) and isinstance(n.op, ast.USub) and op_cls is ast.USub:
                    cands.append(n)
                elif (op_cls is ast.USub and isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
                      and n.func.id == "abs" and len(n.args) == 1):
                    cands.append(n)             # abs(x) is `-x` where x is negative
        if isinstance(stmt, ast.AugAssign) and isinstance(stmt.op, op_cls) and op_cls is not ast.USub:
            cands.append(stmt)
        if len(cands) > 1:
            want = _norm(_ir_text(e))
            exact = [c for c in cands if _norm(_plain(c)) == want]
            cands = exact if exact else cands
        if len(cands) > 1 and len({_plain(c) for c in cands}) == 1:
            cands = cands[:1]                  # the same text twice (`(i + j) * (i + j + 1)`): either names it
        if len(cands) != 1:
            return None
        return ast.get_source_segment(self.g.program.source, cands[0])

    def unproven(self, e: ir.Expr, rule: str, symbol: str, op_cls: type, why: str) -> str:
        text = self.expr_text(e, op_cls)
        what = f"`{text}`" if text else f"the `{symbol}` at line {self.line()}"
        return self.bad(rule, symbol, f"the int operation {what} is not proved to stay inside 64 bits"
                        f"{why}; big integers arrive in a later step")

    # -- expressions

    def ev(self, e: ir.Expr) -> str:
        m = getattr(self, "ev_" + type(e).__name__, None)
        if m is None:
            return self.node_error(e)
        return m(e)

    def node_error(self, e: object) -> str:
        n = type(e).__name__
        if isinstance(e, ir.CallObject):
            callee = e.callee.name if isinstance(e.callee, ir.Global) else "an object"
            if callee in self.g.fns:
                return self.bad("aot/call", callee, f"the call of `{callee}` passes arguments whose types differ from "
                                "its annotations (CPython passes them unconverted), so it is not a direct call")
            return self.bad("aot/call", callee, f"the call of {callee} is not compiled without CPython (the first AOT step "
                            "calls the program's functions, float, the math functions and print)")
        if isinstance(e, ir.Global):
            return self.bad("aot/global", e.name, f"`{e.name}` is read as a Python global, which has no form without CPython")
        if isinstance(e, ir.Box):
            return self.bad("aot/object", n, "a value is turned into a Python object, which has no form without CPython")
        return self.bad("aot/ir-node", n, f"the IR node {n} needs CPython objects and has no AOT form in the first step")

    def ev_Const(self, e: ir.Const) -> str:
        if e.type is Type.I64:
            return _i64(int(e.value))
        if e.type is Type.F64:
            return _f64(float(e.value))
        if e.type is Type.BOOL:
            return "1" if e.value else "0"
        if e.type is Type.NONE:
            return "0"
        if e.type in (Type.OBJ, Type.STR_OPT) and e.value is None:
            return "NULL"
        if e.type is Type.STR and isinstance(e.value, str):
            return self.g.str_lit(e.value)
        return self.bad("aot/str", "str", "a string object other than a str value has no AOT form")

    def ev_Is(self, e: ir.Is) -> str:
        left = self.ev(e.left)
        right = self.ev(e.right)
        return f"({left} != {right})" if e.negate else f"({left} == {right})"

    def ev_FieldGet(self, e: ir.FieldGet) -> str:
        fd = self.g.cls_analysis.field_map.get((e.cls, e.field))
        if fd is None:
            return self.bad("aot/type", e.field, f"unknown field `{e.cls}.{e.field}`")
        if isinstance(e.obj, ir.Local) and self.is_virtual(e.obj.name):
            vf = self.vfield(e.obj.name, e.field)
            if fd.type in (Type.F64, Type.BOOL):
                return vf
            if fd.type is Type.I64:
                if self.g.reps.field(e.cls, e.field) == TAGGED:
                    t = self.tslot()
                    self.emit(f"tpa_int_set(&{t}, {vf});")
                    return t
                return vf
            if fd.type in LIST:
                kind = "f64" if fd.type is Type.F64_ARRAY else (
                    "int" if self.g.reps.field_list_big(e.cls, e.field) else "i64")
                t = self.tmp(list_ptr(kind), "NULL")
                self.list_slots.append((kind, t))
                self.emit(f"{lfn(kind, 'release')}({t}); {t} = {vf}; {lfn(kind, 'retain')}({t});")
                return t
            if fd.type in (Type.STR, Type.STR_OPT):
                t = self.pslot()
                self.emit(f"tpa_str_set(&{t}, {vf});")
                return t
            if fd.type is Type.OBJ_LIST:                 # (not reached: a class with a list is never virtual)
                t = self.olslot()
                self.emit(f"tpa_obj_list_set(&{t}, {vf});")
                return t
            assert fd.cls is not None
            t = self.oslot(fd.cls)
            mc = mangle(fd.cls)
            self.emit(f"tpa_cls_{mc}_release({t}); {t} = {vf}; tpa_cls_{mc}_retain({t});")
            return t
        region_obj = self.expr_region_cls(e.obj) is not None      # PR-O2b: the object lives in a region
        obj = self.ev(e.obj)
        mf = mangle(e.field)
        if fd.type in (Type.F64, Type.BOOL):
            return f"({obj}->f_{mf})"
        if fd.type is Type.I64:
            if self.g.reps.field(e.cls, e.field) == TAGGED:
                t = self.tslot()
                self.emit(f"tpa_int_set(&{t}, {obj}->f_{mf});")
                return t
            return f"({obj}->f_{mf})"
        if fd.type is Type.OBJ_LIST:
            # tier S2a: a list field is never immutable (review B4/S1): the list is held by a counted slot while it is
            # in use, so a call that rebinds the field cannot free it under the expression
            t = self.olslot()
            self.emit(f"tpa_obj_list_set(&{t}, {obj}->f_{mf});")
            return t
        if fd.type in (Type.STR, Type.STR_OPT):
            # a field that may be rebound while the value is in use is held by a slot; an immutable field of an
            # object the function cannot drop is borrowed (as a list field, PR-O1 elision rule 3)
            if (not region_obj and self.g.cls_analysis.field_elidable(e.cls, e.field)
                    and isinstance(e.obj, ir.Local) and e.obj.name not in self.assigned):
                return f"({obj}->f_{mf})"
            t = self.pslot()
            self.emit(f"tpa_str_set(&{t}, {obj}->f_{mf});")
            return t
        if fd.type in LIST:
            kind = "f64" if fd.type is Type.F64_ARRAY else (
                "int" if self.g.reps.field_list_big(e.cls, e.field) else "i64")
            if (not region_obj and self.g.cls_analysis.field_elidable(e.cls, e.field)
                    and isinstance(e.obj, ir.Local) and e.obj.name not in self.assigned):
                return f"({obj}->f_{mf})"
            t = self.tmp(list_ptr(kind), "NULL")
            self.list_slots.append((kind, t))
            self.emit(f"{lfn(kind, 'release')}({t}); {t} = {obj}->f_{mf}; {lfn(kind, 'retain')}({t});")
            return t
        assert fd.cls is not None
        if region_obj:
            if fd.cls in self.regions:
                return f"({obj}->f_{mf})"          # a region pointer: no count, nothing to retain
            # an owned heap object held by a region object: a mutable field, retained into a temporary
            t = self.oslot(fd.cls)
            mc = mangle(fd.cls)
            self.emit(f"tpa_cls_{mc}_release({t}); {t} = {obj}->f_{mf}; tpa_cls_{mc}_retain({t});")
            return t
        if (self.g.cls_analysis.field_elidable(e.cls, e.field)
                and isinstance(e.obj, ir.Local) and e.obj.name not in self.assigned):
            return f"({obj}->f_{mf})"
        # Mutable class field read, or any field of a cyclic-capable class (PR-O2c): MUST retain into a
        # temporary (elision conditions 3 and 4).
        t = self.oslot(fd.cls)
        mc = mangle(fd.cls)
        self.emit(f"tpa_cls_{mc}_release({t}); {t} = {obj}->f_{mf}; tpa_cls_{mc}_retain({t});")
        return t

    def ev_New(self, e: ir.New) -> str:
        if e.cls in self.regions:
            return self._emit_region_new(e)
        init_fn = self.g.fns.get(f"{e.cls}.__init__")
        args: list[str] = []
        if init_fn is not None:
            ok = True
            for p, a in zip(init_fn.params[1:], e.args):
                if isinstance(p, ir.ArrayParam):
                    if not (isinstance(a, ir.Local) and a.type is p.type and a.name in self.types):
                        ok = False
                        self.bad("aot/type", init_fn.name, f"the argument for list parameter `{p.name}` of "
                                 f"`{init_fn.name}` is not a list of its type")
                    elif self.kind(a.name) != self.g.array_kind(init_fn.name, p.name, p.type):
                        ok = False
                        self.bad("aot/type", init_fn.name, f"the list `{a.name}` and the parameter `{p.name}` of "
                                 f"`{init_fn.name}` are lists of different kinds")
                    else:
                        args.append(f"v_{mangle(a.name)}")
                elif isinstance(p, ir.Param) and self.g.tagged_param(init_fn, p):
                    args.append(self.tagged(a))
                else:
                    if not self.no_region(a, f"the {e.cls} constructor (a heap object)"):
                        ok = False
                    args.append(self.ev(a))
                    if a.type is Type.I64 and self.rep(a) == TAGGED:
                        ok = False
                        self.bad("aot/type", init_fn.name, f"a TAGGED int is passed for the RAW parameter "
                                 f"`{p.name}` of `{init_fn.name}` (the representation did not converge)")
            if not ok:
                return "NULL"
        else:
            for a in e.args:
                if not self.no_region(a, f"the {e.cls} constructor (a heap object)"):
                    return "NULL"
                args.append(self.ev(a))
        t = self.oslot(e.cls)
        mc = mangle(e.cls)
        self.emit(f"tpa_cls_{mc}_release({t}); {t} = NULL;")
        self.emit(f"if (tpa_cls_{mc}_new({', '.join(args + ['&' + t])})) {self.fail()}")
        return t

    def _is_class_local(self, name: str) -> bool:
        if name in self.f.local_classes:
            return True
        return any(isinstance(p, ir.Param) and p.name == name and p.cls is not None for p in self.f.params)

    def ev_Local(self, e: ir.Local) -> str:
        if e.type not in CTYPE and e.type not in LIST and e.type not in (Type.STR, Type.STR_LIST, Type.STR_OPT,
                                                                          Type.OBJ_LIST) \
                and not (e.type is Type.OBJ and self._is_class_local(e.name)):
            self.ctype(e.type, e.name)
        return f"v_{mangle(e.name)}"

    def ev_ToFloat(self, e: ir.ToFloat) -> str:
        v = self.ev(e.operand)
        if e.operand.type is Type.F64:
            return v
        if e.operand.type is Type.I64 and self.rep(e.operand) == TAGGED:
            # float(n): correctly rounded, OverflowError "int too large to convert to float" (tpa_int_to_f64)
            return self.fallible("double", f"tpa_int_to_f64({v}, @)")
        if e.operand.type in (Type.I64, Type.BOOL):
            return f"((double){v})"                     # round to nearest even, as CPython's int to float
        return self.bad("aot/type", "float", f"float() of a {e.operand.type.value} is outside the first AOT step")

    def lazy_float(self, x: ir.Expr) -> tuple[str, bool]:
        """An operand of a float operation: its value, and whether its conversion from a TAGGED int is still to do.
        CPython evaluates both operands of `a + b` first and converts the int where the operation runs (so a call on
        the right runs, and may raise, before an int on the left is found too large for a float); `float(n)` written
        in the source converts at once, and so does a call argument."""
        if isinstance(x, ir.ToFloat) and not x.explicit and x.operand.type is Type.I64 \
                and self.rep(x.operand) == TAGGED:
            return self.ev(x.operand), True
        return self.ev(x), False

    def float_args(self, xs) -> list[str]:
        """The values of the operands `xs` of one float operation: all evaluated, then each TAGGED int converted in order."""
        vals = [self.lazy_float(x) for x in xs]
        return [self.fallible("double", f"tpa_int_to_f64({v}, @)") if pending else v for v, pending in vals]

    def ev_And(self, e: ir.And) -> str:
        return self.shortcut(e, "&&", "")

    def ev_Or(self, e: ir.Or) -> str:
        return self.shortcut(e, "||", "!")

    def shortcut(self, e, c_op: str, negate: str) -> str:
        a = self.ev(e.left)
        b, pre = self.capture(lambda: self.ev(e.right))
        if not pre:
            return f"({a} {c_op} {b})"
        t = self.tmp("int")
        self.emit(f"{t} = {a};")
        self.emit(f"if ({negate}{t}) {{")
        self.lines.extend(pre)
        self.emit(f"    {t} = {b};")
        self.emit("}")
        return t

    def ev_Cond(self, e: ir.Cond) -> str:
        """`then if cond else orelse`: only the chosen branch runs (a branch that emits statements, an element
        read or a fallible operation, makes this an `if` statement into a temporary)."""
        if e.type in (Type.STR, Type.STR_OPT):
            c = self.ev(e.cond)
            a, pre_a = self.capture(lambda: self.ev(e.then))
            b, pre_b = self.capture(lambda: self.ev(e.orelse))
            t = self.tmp("tpa_str *", "NULL")          # borrows the chosen branch's value for this statement
            self.emit(f"if ({_unwrap(c)}) {{")
            self.lines.extend(pre_a)
            self.emit(f"    {t} = {a};")
            self.emit("} else {")
            self.lines.extend(pre_b)
            self.emit(f"    {t} = {b};")
            self.emit("}")
            return t
        if e.type not in CTYPE:
            return self.ctype(e.type, "conditional expression") and "0"
        c = self.ev(e.cond)
        if e.type is Type.I64 and self.rep(e) == TAGGED:
            a, pre_a = self.capture(lambda: self.tagged(e.then))
            b, pre_b = self.capture(lambda: self.tagged(e.orelse))
            t = self.tslot()
            self.emit(f"if ({_unwrap(c)}) {{")
            self.lines.extend(pre_a)
            self.emit(f"    tpa_int_set(&{t}, {a});")
            self.emit("} else {")
            self.lines.extend(pre_b)
            self.emit(f"    tpa_int_set(&{t}, {b});")
            self.emit("}")
            return t
        a, pre_a = self.capture(lambda: self.ev(e.then))
        b, pre_b = self.capture(lambda: self.ev(e.orelse))
        if not pre_a and not pre_b:
            return f"({c} ? {a} : {b})"
        t = self.tmp(CTYPE[e.type])
        self.emit(f"if ({_unwrap(c)}) {{")
        self.lines.extend(pre_a)
        self.emit(f"    {t} = {a};")
        self.emit("} else {")
        self.lines.extend(pre_b)
        self.emit(f"    {t} = {b};")
        self.emit("}")
        return t

    def ev_TupleIndex(self, e: ir.TupleIndex) -> str:
        """`t[i]` of a split tuple: the index, its slot (IndexError("tuple index out of range")), then the chosen
        element local, read as `ev_Cond` reads a branch."""
        i = self.raw_index(e.index)
        k = self.tmp("int64_t")
        self.emit(f"if (tpa_tuple_slot({len(e.elements)}, {i}, &{k})) {self.fail()}")
        if e.type is Type.I64 and self.rep(e) == TAGGED:
            t = self.tslot()
            self.emit(f"switch ({k}) {{")
            for n, x in enumerate(e.elements):
                v, pre = self.capture(lambda x=x: self.tagged(x))
                self.emit(f"case {n}:")
                self.lines.extend(pre)
                self.emit(f"    tpa_int_set(&{t}, {v});")
                self.emit("    break;")
            self.emit("}")
            return t
        if e.type is not Type.STR and e.type not in CTYPE:
            return self.ctype(e.type, "tuple element") and "0"
        t = self.tmp("tpa_str *", "NULL") if e.type is Type.STR else self.tmp(CTYPE[e.type])
        self.emit(f"switch ({k}) {{")             # (a str element is borrowed from its local for this statement)
        for n, x in enumerate(e.elements):
            v, pre = self.capture(lambda x=x: self.ev(x))
            self.emit(f"case {n}:")
            self.lines.extend(pre)
            self.emit(f"    {t} = {v};")
            self.emit("    break;")
        self.emit("}")
        return t

    def ev_UnaryOp(self, e: ir.UnaryOp) -> str:
        t = e.operand.type
        if e.op is ir.UnaryOpKind.NOT and t is Type.BOOL:
            return f"(!{self.ev(e.operand)})"
        if e.op is ir.UnaryOpKind.POS and t in (Type.I64, Type.F64):
            return self.ev(e.operand)
        if e.op is ir.UnaryOpKind.NEG and t is Type.F64:
            return f"(-{self.ev(e.operand)})"
        if e.op is ir.UnaryOpKind.NEG and t is Type.I64:
            if self.rep(e) == TAGGED:
                return self.fallible_int(f"tpa_int_neg({self.tagged(e.operand)}, @)")
            v = self.ev(e.operand)
            if e.proven:
                return f"tpa_pneg_i64({v})"
            return self.unproven(e, "aot/int-unproven", "-", ast.USub, "")
        if e.op is ir.UnaryOpKind.INVERT and t is Type.I64:
            if self.rep(e) == TAGGED:
                return self.fallible_int(f"tpa_int_invert({self.tagged(e.operand)}, @)")
            return f"(~({self.ev(e.operand)}))"
        return self.bad("aot/operator", e.op.value, f"the unary `{e.op.value}` on a {t.value} is outside the first AOT step")

    def ev_Compare(self, e: ir.Compare) -> str:
        lt, rt = e.left.type, e.right.type
        a, b = self.ev(e.left), self.ev(e.right)
        op = e.op.value
        scalar = (Type.I64, Type.F64, Type.BOOL)
        if lt not in scalar or rt not in scalar:
            return self.bad("aot/type", op, f"a comparison of a {lt.value} and a {rt.value} is outside the first AOT step")
        tl = lt is Type.I64 and self.rep(e.left) == TAGGED
        tr = rt is Type.I64 and self.rep(e.right) == TAGGED
        if tl or tr:
            return self.compare_tagged(e, a, b, tl, tr)
        if lt is rt or (Type.BOOL in (lt, rt) and Type.F64 not in (lt, rt)):
            return f"({a} {op} {b})"
        code = _CMP_CODE[e.op]
        # an int against a float compares exactly (CPython's float_richcompare), a bool as the int 0 or 1
        if lt is Type.F64:
            return f"tpa_cmp_f64_i64({a}, (int64_t)({b}), {code})"
        return f"tpa_cmp_i64_f64((int64_t)({a}), {b}, {code})"

    def compare_tagged(self, e: ir.Compare, a: str, b: str, tl: bool, tr: bool) -> str:
        """A comparison with a TAGGED int operand, exact for every value: int with int through the runtime's
        comparison, int with float exactly (never through a conversion of the int), a bool as the int 0 or 1."""
        lt, rt = e.left.type, e.right.type
        code = _CMP_CODE[e.op]
        if tl and tr:
            if e.op is ir.CompareKind.EQ:
                return f"tpa_int_eq({a}, {b})"
            if e.op is ir.CompareKind.NE:
                return f"(!tpa_int_eq({a}, {b}))"
            return f"(tpa_int_cmp({a}, {b}) {e.op.value} 0)"
        if tl:                                      # the right operand is a RAW int, a bool or a float
            if rt is Type.F64:
                return f"tpa_cmp_int_f64({a}, {b}, {code})"
            return f"(tpa_int_cmp_i64({a}, (int64_t)({b})) {e.op.value} 0)"
        if lt is Type.F64:                          # the left operand is a float, the right a TAGGED int
            return f"tpa_cmp_f64_int({a}, {b}, {code})"
        swapped = {"<": ">", "<=": ">=", ">": "<", ">=": "<=", "==": "==", "!=": "!="}[e.op.value]
        return f"(tpa_int_cmp_i64({b}, (int64_t)({a})) {swapped} 0)"

    def ev_BinOp(self, e: ir.BinOp) -> str:
        lt, rt = e.left.type, e.right.type
        k = e.op
        if e.np or e.inplace:
            return self.bad("aot/ir-node", "BinOp", "a numpy or in-place operation is outside the first AOT step")
        if lt is Type.F64 and rt is Type.F64 and e.type is Type.F64:
            a, b = self.float_args((e.left, e.right))
            if k in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL):
                return f"({a} {k.value} {b})"
            fn = {
                ir.BinOpKind.TRUEDIV: "truediv",
                ir.BinOpKind.FLOORDIV: "floordiv",
                ir.BinOpKind.MOD: "mod",
                ir.BinOpKind.POW: "pow",
            }.get(k)
            if fn is None:
                return self.bad("aot/operator", k.value, f"the float operator `{k.value}` is outside the first AOT step")
            return self.fallible("double", f"tpa_{fn}_f64({a}, {b}, @)")
        if lt is Type.I64 and rt is Type.I64:
            return self.int_op(e)
        return self.bad("aot/type", k.value, f"the operator `{k.value}` on a {lt.value} and a {rt.value} is outside "
                        "the first AOT step")

    def int_op(self, e: ir.BinOp) -> str:
        k = e.op
        sym = k.value
        if k is ir.BinOpKind.TRUEDIV:
            la, ra = e.left, e.right
            if (isinstance(la, ir.Const) and isinstance(ra, ir.Const)
                    and abs(la.value) <= _EXACT_FLOAT and abs(ra.value) <= _EXACT_FLOAT):
                return self.fallible("double", f"tpa_truediv_small_i64({self.ev(la)}, {self.ev(ra)}, @)")
            if not self.g.strict:
                # int / int of anything: the correctly rounded quotient, exactly as CPython (no conversion of the
                # operands to double first); two RAW operands within plus or minus 2**53 are divided directly
                if self.rep(la) == RAW and self.rep(ra) == RAW:
                    a, b = self.ev(la), self.ev(ra)
                    return self.fallible("double", f"tpa_truediv_i64({a}, {b}, @)")
                a, b = self.tagged(la), self.tagged(ra)
                return self.fallible("double", f"tpa_int_truediv({a}, {b}, @)")
            self.ev(la), self.ev(ra)
            text = self.expr_text(e, ast.Div)
            what = f"`{text}`" if text else f"the `/` at line {self.line()}"
            return self.bad("aot/int-true-division", "/", f"the int true division {what} is not proved to have both "
                            "operands inside plus or minus 2**53, where the C division equals CPython's correctly "
                            "rounded quotient; big integers arrive in a later step")
        if self.rep(e) == TAGGED:
            fn = {
                "+": "add", "-": "sub", "*": "mul", "//": "floordiv", "%": "mod",
                "&": "and", "|": "or", "^": "xor", "<<": "lshift", ">>": "rshift", "**": "pow",
            }.get(sym)
            if fn is not None:
                a, b = self.tagged(e.left), self.tagged(e.right)
                return self.fallible_int(f"tpa_int_{fn}({a}, {b}, @)")
            return self.bad("aot/operator", sym, f"the int operator `{sym}` is outside the first AOT step")
        if k in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL):
            a, b = self.ev(e.left), self.ev(e.right)
            if not e.proven:
                return self.unproven(e, "aot/int-unproven", sym, _AST_OP[k], "")
            name = {"+": "add", "-": "sub", "*": "mul"}[sym]
            return f"tpa_p{name}_i64({a}, {b})"
        if k in (ir.BinOpKind.AND, ir.BinOpKind.OR, ir.BinOpKind.XOR):
            a, b = self.ev(e.left), self.ev(e.right)
            return f"({a} {sym} {b})"
        if k is ir.BinOpKind.RSHIFT:
            return self.fallible("int64_t", f"tpa_rshift_i64({self.ev(e.left)}, {self.ev(e.right)}, @)")
        if k is ir.BinOpKind.LSHIFT:
            a, b = self.ev(e.left), self.ev(e.right)
            if not e.proven:
                return self.unproven(e, "aot/int-unproven", "<<", ast.LShift, "")
            return self.fallible("int64_t", f"tpa_plshift_i64({a}, {b}, @)")
        if k is ir.BinOpKind.MOD:
            # the result lies between 0 and the divisor, so it always fits in 64 bits (not marked `proven`)
            return self.fallible("int64_t", f"tpa_mod_i64({self.ev(e.left)}, {self.ev(e.right)}, @)")
        if k is ir.BinOpKind.FLOORDIV:
            r = e.right
            a, b = self.ev(e.left), self.ev(r)
            # a divisor that is a constant other than -1 cannot make INT64_MIN // d overflow (d = 0 raises)
            if e.proven or (isinstance(r, ir.Const) and r.value != -1):
                return self.fallible("int64_t", f"tpa_floordiv_i64({a}, {b}, @)")
            return self.unproven(e, "aot/int-unproven", "//", ast.FloorDiv,
                                 " (the divisor may be -1, and INT64_MIN // -1 is 2**63)")
        return self.bad("aot/operator", sym, f"the int operator `{sym}` is outside the first AOT step")

    def ev_MathCall(self, e: ir.MathCall) -> str:
        args = self.float_args(e.args)
        if any(a.type is not Type.F64 for a in e.args):
            return self.bad("aot/type", e.func.value, "a math function takes floats")
        if e.func in (ir.MathFunc.ISNAN, ir.MathFunc.ISINF, ir.MathFunc.ISFINITE):
            return f"tpa_math_{e.func.value}({args[0]})"
        if e.func in (ir.MathFunc.FLOOR, ir.MathFunc.CEIL, ir.MathFunc.TRUNC):
            return self.fallible_int(f"tpa_math_{e.func.value}({args[0]}, @)")
        return self.fallible("double", f"tpa_math_{e.func.value}({', '.join(args)}, @)")

    # -- lists

    def list_of(self, name: str, what: str) -> tuple[str, Type] | None:
        t = self.types.get(name)
        if t not in LIST:
            self.bad("aot/type", name, f"`{name}` is not a list, but is used by {what}")
            return None
        return f"v_{mangle(name)}", t

    def ev_Len(self, e: ir.Len) -> str:
        found = self.list_of(e.array, "len()")
        if not found:
            return "0"
        if self.kind(e.array) in self.g.grown:
            # tier S2a: a length that can change is read here, in evaluation order (`print(len(a), a.pop())` prints
            # the length before the pop), as an element read is
            t = self.tmp("int64_t")
            self.emit(f"{t} = {found[0]}->len;")
            return t
        return f"({found[0]}->len)"

    def ev_Index(self, e: ir.Index) -> str:
        """`a[i]`: the element is read into a temporary at this point, in order, so that a call evaluated
        later in the same statement (which may store into the list) cannot change what was read."""
        found = self.list_of(e.array, "indexing")
        idx = self.ev(e.index)
        if found is None:
            return "0"
        arr, t = found
        if self.kind(e.array) == "int":
            return self.index_big(e, arr, idx)
        elem = LIST[t][1]
        out = self.tmp(elem)
        if self.rep(e.index) == TAGGED:
            # an index that fits is used with the usual check; one that does not raises the reference's IndexError
            slot = self.tmp("int64_t")
            self.emit(f"if (tpa_slot_int({arr}->len, {idx}, 0, &{slot})) {self.fail()}")
            self.emit(f"{out} = {arr}->d[{slot}];")
        elif e.proven and self.trusted(e):
            self.emit(f"{out} = {arr}->d[{idx}];")
        else:
            slot = self.tmp("int64_t")
            self.emit(f"if (tpa_slot({arr}->len, {idx}, 0, &{slot})) {self.fail()}")
            self.emit(f"{out} = {arr}->d[{slot}];")
        return out

    def index_big(self, e: ir.Index, arr: str, idx: str) -> str:
        """`a[i]` of a big-capable list: the element is a new reference, held by a TAGGED slot. The read goes through
        `tpa_int_list_load` (tp_aot_int.h), which takes the list's lock in a library build (agent proposal, not
        owner-confirmed; design note section 7)."""
        out = self.tslot()
        if self.rep(e.index) == TAGGED:
            slot = self.tmp("int64_t")
            self.emit(f"if (tpa_slot_int({arr}->len, {idx}, 0, &{slot})) {self.fail()}")
            self.emit(f"tpa_int_list_load({arr}, {slot}, &{out});")
        elif e.proven and self.trusted(e):
            self.emit(f"tpa_int_list_load({arr}, {idx}, &{out});")
        else:
            slot = self.tmp("int64_t")
            self.emit(f"if (tpa_slot({arr}->len, {idx}, 0, &{slot})) {self.fail()}")
            self.emit(f"tpa_int_list_load({arr}, {slot}, &{out});")
        return out

    def ev_NewArray(self, e: ir.NewArray) -> str:
        if e.type not in LIST:
            return self.bad("aot/type", "list", f"a list of {e.type.value} is outside the AOT mode")
        kind = self.bind_kind
        if kind is None:
            return self.bad("aot/type", "list", "a list is created where no list name receives it")
        t = self.list_tmp(kind)
        if e.iota:
            n = self.length_value(e.length, "tpa_int_to_range_len")
            self.emit(f"if ({lfn(kind, 'iota')}({n}, &{t})) {self.fail()}")
        else:
            assert e.fill is not None
            if kind == "int":
                fill = self.tagged(e.fill)               # `[v] * n` evaluates v, then n
            else:
                fill = self.ev(e.fill)
                if e.type is Type.I64_ARRAY and e.fill.type is Type.I64 and self.rep(e.fill) == TAGGED:
                    self.bad("aot/type", "list", "a TAGGED value fills a raw list (the list classification did not "
                             "converge)")
            n = self.length_value(e.length, "tpa_int_to_count")
            self.emit(f"if ({lfn(kind, 'fill')}({n}, {fill}, &{t})) {self.fail()}")
        return t

    def length_value(self, e: ir.Expr, convert: str) -> str:
        """The int64 length of a new list: a RAW value as it is; a TAGGED one that fits is used, and one that does
        not raises what the reference raises (`convert`, tp_aot_int.h)."""
        n = self.ev(e)
        if self.rep(e) == RAW:
            return n
        t = self.tmp("int64_t")
        self.emit(f"if ({convert}({n}, &{t})) {self.fail()}")
        return t

    def ev_CopyArray(self, e: ir.CopyArray) -> str:
        found = self.list_of(e.src, "a `[:]` copy")
        if found is None:
            return "NULL"
        arr, t = found
        src, dst = self.kind(e.src), self.bind_kind
        if dst is None:
            return self.bad("aot/type", "list", "a copy is made where no list name receives it")
        if src == dst:
            fn = lfn(dst, "copy")
        elif (src, dst) == ("i64", "int"):
            fn = "tpa_int_list_from_i64"                    # a raw list copied into a big-capable one: each element converted
        else:
            return self.bad("aot/type", e.src, f"a list of kind {src} is copied into one of kind {dst} (the list "
                            "classification did not converge)")
        out = self.list_tmp(dst)
        self.emit(f"if ({fn}({arr}, &{out})) {self.fail()}")
        return out

    def ev_ListDisplay(self, e: ir.ListDisplay) -> str:
        if e.type not in LIST:
            return self.bad("aot/type", "list", f"a list of {e.type.value} is outside the AOT mode")
        kind = self.bind_kind
        if kind is None:
            return self.bad("aot/type", "list", "a list is created where no list name receives it")
        if kind == "int":
            vals = [self.tagged(v) for v in e.elements]      # every element is evaluated before the list exists
        else:
            vals = [self.ev(v) for v in e.elements]
            if e.type is Type.I64_ARRAY and any(v.type is Type.I64 and self.rep(v) == TAGGED for v in e.elements):
                self.bad("aot/type", "list", "a TAGGED value is displayed in a raw list (the list classification did "
                         "not converge)")
        t = self.list_tmp(kind)
        self.emit(f"if ({lfn(kind, 'alloc')}({len(vals)}, &{t})) {self.fail()}")
        for i, v in enumerate(vals):
            self.emit(f"tpa_int_list_init({t}, {i}, {v});" if kind == "int" else f"{t}->d[{i}] = {v};")
        return t

    def call_args(self, callee: ir.Function, e: ir.Call) -> list[str] | None:
        if len(callee.params) != len(e.args):
            self.bad("aot/type", e.function, f"the arguments of `{e.function}` do not match its parameters")
            return None
        out: list[str] = []
        ok = True
        for p, a in zip(callee.params, e.args):
            if not self.no_region(a, f"the call of {e.function}"):
                ok = False
            if isinstance(p, ir.ArrayParam):
                if not (isinstance(a, ir.Local) and a.type is p.type and a.name in self.types):
                    ok = False
                    self.bad("aot/type", e.function, f"the argument for list parameter `{p.name}` of `{e.function}` "
                             "is not a list of its type")
                elif self.kind(a.name) != self.g.array_kind(callee.name, p.name, p.type):
                    ok = False
                    self.bad("aot/type", e.function, f"the list `{a.name}` and the parameter `{p.name}` of "
                             f"`{e.function}` are lists of different kinds (the list classification did not merge them)")
                else:
                    out.append(f"v_{mangle(a.name)}")
            elif not isinstance(p, ir.Param) or (p.type is not a.type
                                                  and not (p.type is Type.STR_OPT and a.type is Type.STR)):
                ok = False
                self.ev(a)
                self.bad("aot/type", e.function, f"the arguments of `{e.function}` do not match its parameter types")
            elif self.g.tagged_param(callee, p):
                out.append(self.tagged(a))                 # a RAW value is converted; the callee borrows the argument
            else:
                out.append(self.ev(a))
                if a.type is Type.I64 and self.rep(a) == TAGGED:
                    ok = False
                    self.bad("aot/type", e.function, f"a TAGGED int is passed for the RAW parameter `{p.name}` of "
                             f"`{e.function}` (the representation did not converge)")
        return out if ok else None

    def ev_Call(self, e: ir.Call) -> str:
        callee = self.g.fns.get(e.function)
        if callee is None or e.redo:
            return self.bad("aot/call", e.function, f"the call of `{e.function}` has no AOT form")
        args = self.call_args(callee, e)
        if args is None:
            return "0"
        name = f"tpf_{mangle(e.function)}"
        if callee.returns is Type.NONE:
            self.emit(f"if ({name}({', '.join(args)})) {self.fail()}")
            return "0"
        if callee.returns in LIST:
            t = self.list_tmp(self.g.ret_kind(callee))
            self.emit(f"if ({name}({', '.join(args + ['&' + t])})) {self.fail()}")
            return t                                       # a new reference: the receiver owns it
        if callee.returns in (Type.STR, Type.STR_OPT):
            t = self.pslot()
            self.emit(f"if ({name}({', '.join(args + ['&' + t])})) {self.fail()}")
            return t                                       # the slot owns the new reference
        if callee.returns is Type.STR_LIST:
            t = self.lslot()
            self.emit(f"if ({name}({', '.join(args + ['&' + t])})) {self.fail()}")
            return t
        if callee.returns is Type.OBJ_LIST:
            t = self.olslot()
            self.emit(f"if ({name}({', '.join(args + ['&' + t])})) {self.fail()}")
            return t
        if callee.returns is Type.OBJ and callee.return_cls is not None:
            mc = mangle(callee.return_cls)
            t = self.oslot(callee.return_cls)
            self.emit(f"tpa_cls_{mc}_release({t}); {t} = NULL;")
            self.emit(f"if ({name}({', '.join(args + ['&' + t])})) {self.fail()}")
            return t
        if callee.returns in CTYPE:
            if callee.returns is Type.I64 and self.g.reps.returns(callee.name) == TAGGED:
                t = self.tslot()                           # the callee writes a new reference; the slot owns it
                self.emit(f"tpa_int_clear(&{t});")
            else:
                t = self.tmp(CTYPE[callee.returns])
            self.emit(f"if ({name}({', '.join(args + ['&' + t])})) {self.fail()}")
            return t
        return self.bad("aot/call", e.function, f"`{e.function}` returns a tuple, which only `a, b = {e.function}(...)` "
                        "can use")

    def ev_ArgvInt(self, e: ir.ArgvInt) -> str:
        """`int(sys.argv[i])`: the value is any int CPython parses, so it is TAGGED. Strict mode: not proved to stay
        inside 64 bits, a finding as before."""
        if self.g.strict:
            self.ev(e.index)
            text = None
            if self.cur is not None and 0 <= self.cur < len(self.orig):
                calls = [n for root in _roots(self.orig[self.cur]) for n in ast.walk(root)
                         if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "int"]
                if len(calls) == 1:
                    text = ast.get_source_segment(self.g.program.source, calls[0])
            what = f"`{text}`" if text else f"the `int(sys.argv[...])` at line {self.line()}"
            return self.bad("aot/int-unproven", "int", f"the int operation {what} is not proved to stay inside 64 "
                            "bits; big integers arrive in a later step")
        self.g.argv_int = True
        idx = self.raw_index(e.index)                      # sys.argv[i]: i a TAGGED int that does not fit is an IndexError
        t = self.tslot()
        self.emit(f"tpa_int_clear(&{t});")
        self.emit(f"if (tpa_argv_int({idx}, &{t})) {self.fail()}")
        return t

    def ev_ArgvLen(self, e: ir.ArgvLen) -> str:
        return "((int64_t)tpa_argc)"

    def ev_FloatToInt(self, e: ir.FloatToInt) -> str:
        return self.fallible_int(f"tpa_int_from_f64({self.ev(e.operand)}, @)")

    def ev_BoolToInt(self, e: ir.BoolToInt) -> str:
        return f"((int64_t)({self.ev(e.operand)}))"

    def ev_StrToInt(self, e: ir.StrToInt) -> str:
        lit, n = _c_string(e.text)
        return self.fallible_int(f"tpa_int_parse_literal({lit}, {n}U, @)")

    def ev_TimeCall(self, e: ir.TimeCall) -> str:
        return f"tpa_time_{e.func.value}()"

    def st_TimeSleep(self, s: ir.TimeSleep) -> None:
        v = self.ev(s.seconds)
        self.emit(f"if (tpa_time_sleep({v}) != 0) {{ {self.fail()} }}")

    def st_SysExit(self, s: ir.SysExit) -> None:
        if s.code is None:
            self.emit("tpa_exit_i64(0);")
        elif self.rep(s.code) == TAGGED:
            v = self.ev(s.code)
            self.emit(f"tpa_exit_int({v});")
        else:
            v = self.ev(s.code)
            self.emit(f"tpa_exit_i64({v});")

    # -- statements

    def stmts(self, ss) -> None:
        for s in ss:
            self.stmt(s)

    def stmt(self, s: ir.Stmt) -> None:
        if s.stmt is not None:
            self.cur = s.stmt
        m = getattr(self, "st_" + type(s).__name__, None)
        if m is None:
            n = type(s).__name__
            self.bad("aot/ir-node", n, f"the IR statement {n} needs CPython objects and has no AOT form in the first step")
            return
        m(s)

    def block(self, ss) -> None:
        self.depth += 1
        self.stmts(ss)
        self.depth -= 1

    def list_value(self, e: ir.Expr, want: Type) -> tuple[str, bool]:
        """(the C expression of the list `e`, whether it is a new reference the receiver owns): a list
        name is borrowed, a creation or a call gives an owned list."""
        if e.type is not want or want not in LIST:
            self.bad("aot/type", "list", f"a {getattr(e.type, 'value', '?')} is used where a {want.value} list is needed")
            return "NULL", True
        if isinstance(e, (ir.Local, ir.FieldGet)):
            return self.ev(e), False
        return self.ev(e), True

    def st_Assign(self, s: ir.Assign) -> None:
        want = self.types.get(s.target)
        if want is Type.STR_OPT:                       # tier S2a: a str, None or another `str | None`
            self.emit(f"tpa_str_set(&v_{mangle(s.target)}, {self.ev(s.value)});")
            return
        if want is Type.OBJ_LIST:                       # tier S2a: a list of class instances, shared by the name
            if s.value.type is not Type.OBJ_LIST:
                self.bad("aot/type", s.target, f"`{s.target}` is a list of class instances but is assigned a "
                         f"{s.value.type.value}")
                return
            self.emit(f"tpa_obj_list_set(&v_{mangle(s.target)}, {self.ev(s.value)});")
            return
        if want is Type.STR_LIST:
            if s.value.type is not Type.STR_LIST:
                self.bad("aot/type", s.target, f"`{s.target}` is a list of str but is assigned a {s.value.type.value}")
                return
            self.emit(f"tpa_str_list_set(&v_{mangle(s.target)}, {self.ev(s.value)});")
            return
        if want is Type.STR:
            target = f"v_{mangle(s.target)}"
            v = s.value
            if isinstance(v, ir.StrConcat) and isinstance(v.left, ir.Local) and v.left.name == s.target:
                # `s += t` (or `s = s + t`): the name owns its reference (every str name does: a parameter the body
                # assigns works on an owned copy), so a count of 1 means nothing else holds the string and it grows
                # in place (design 4.3.1; review of #510, F1)
                b = self.str_value(v.right, "the right operand of +")
                self.emit(f"if (tpa_str_iadd(&{target}, {b})) {self.fail()}")
                return
            val = self.str_value(v, s.target)
            self.emit(f"tpa_str_set(&{target}, {val});")       # the new reference is held before the old is released
            return
        if want in LIST:
            x = self.kind(s.target)
            if not self.kinds_agree(s.value, x, s.target):
                return
            self.bind_kind = x
            try:
                val, owned = self.list_value(s.value, want)
            finally:
                self.bind_kind = None
            if not owned:
                self.emit(f"{lfn(x, 'retain')}({val});")        # a second name for the one list
            self.emit(f"{lfn(x, 'release')}(v_{mangle(s.target)});")   # the old binding, after the new one is held
            self.emit(f"v_{mangle(s.target)} = {val};")
            return
        if want is Type.OBJ and s.target in self.f.local_classes:
            cls = self.f.local_classes[s.target][0]
            if self.is_virtual(s.target) and isinstance(s.value, ir.New):
                self._emit_virtual_init(s.target, cls, s.value)
                return
            val = self.ev(s.value)
            if self.is_region_local(s.target):
                # a region pointer: a plain assignment, nothing is counted (PR-O2b)
                if self.region_value_ok(s.value, cls, f"the region local `{s.target}`"):
                    self.emit(f"v_{mangle(s.target)} = {val};")
                return
            if not self.no_region(s.value, f"the heap local `{s.target}`"):
                return
            if s.target in self.borrowed_locals:
                self.emit(f"v_{mangle(s.target)} = {val};")
            else:
                mc = mangle(cls)
                tmp = self.tmp(self.g.cls_ptr(cls), "NULL")
                self.emit(f"{tmp} = {val};")
                self.emit(f"tpa_cls_{mc}_retain({tmp});")
                self.emit(f"tpa_cls_{mc}_release(v_{mangle(s.target)});")
                self.emit(f"v_{mangle(s.target)} = {tmp};")
            return
        if want is None or want is not s.value.type:
            self.bad("aot/type", s.target, f"`{s.target}` is assigned a {s.value.type.value} but is a "
                     f"{want.value if want else 'name the front end did not type'}")
            return
        self.ctype(want, s.target)
        if self.tagged_name(s.target):
            # a TAGGED name owns a reference: take the new one, give up the old one
            self.emit(f"tpa_int_set(&v_{mangle(s.target)}, {self.tagged(s.value)});")
            return
        v = self.ev(s.value)
        if want is Type.I64 and self.rep(s.value) == TAGGED:
            self.bad("aot/type", s.target, f"a TAGGED int is assigned to the RAW name `{s.target}` (the representation "
                     "did not converge)")
            return
        self.emit(f"v_{mangle(s.target)} = {v};")

    def _eval_new_args(self, cls: str, e: ir.New, init_fn: ir.Function | None
                       ) -> list[tuple[str, bool, str | None]] | None:
        """The arguments of a site with a simple `__init__` (virtual or region), evaluated in order:
        (C value, whether it is TAGGED, the kind of a list argument). None after a finding."""
        eval_args: list[tuple[str, bool, str | None]] = []
        if init_fn is None:
            return eval_args
        ok = True
        for p, a in zip(init_fn.params[1:], e.args):
            if isinstance(p, ir.ArrayParam):
                if not (isinstance(a, ir.Local) and a.type is p.type and a.name in self.types):
                    ok = False
                    self.bad("aot/type", init_fn.name, f"the argument for list parameter `{p.name}` of "
                             f"`{init_fn.name}` is not a list of its type")
                elif self.kind(a.name) != self.g.array_kind(init_fn.name, p.name, p.type):
                    ok = False
                    self.bad("aot/type", init_fn.name, f"the list `{a.name}` and the parameter `{p.name}` of "
                             f"`{init_fn.name}` are lists of different kinds")
                else:
                    eval_args.append((f"v_{mangle(a.name)}", False, self.kind(a.name)))
            elif a.type is Type.I64 and self.rep(a) == TAGGED:
                eval_args.append((self.tagged(a), True, None))
            else:
                eval_args.append((self.ev(a), False, None))
        return eval_args if ok else None

    def _emit_init_stores(self, cls: str, e: ir.New, init_fn: ir.Function | None,
                          eval_args: list[tuple[str, bool, str | None]], lval, region_obj: bool) -> None:
        """The field stores of a simple `__init__`, in its order, into `lval(field)`: the per-field locals of a
        virtual object or the fields of a fresh region object. A class field of a region object whose class is
        region in this function takes the region pointer as it is; every other class field, list field and big
        int field takes an owned reference (the fresh object held nothing, so the release of the old value is a
        no-op that keeps one code shape)."""
        init_map = self.g.alloc_plan.init_maps.get(cls, ())
        for fname, src in init_map:
            fd = self.g.cls_analysis.field_map.get((cls, fname))
            if fd is None:
                continue
            vf = lval(fname)
            if fd.type in (Type.F64, Type.BOOL):
                val = eval_args[src][0] if isinstance(src, int) else self.ev(src)
                self.emit(f"{vf} = {val};")
            elif fd.type is Type.I64:
                if self.g.reps.field(cls, fname) == TAGGED:
                    if isinstance(src, int):
                        val, is_tagged, _ = eval_args[src]
                        tval = val if is_tagged else self.fallible_int(f"tpa_int_from_i64({val}, @)")
                    else:
                        tval = self.tagged(src)
                    self.emit(f"tpa_int_set(&{vf}, {tval});")
                else:
                    if isinstance(src, int):
                        val, is_tagged, _ = eval_args[src]
                        if is_tagged:
                            assert init_fn is not None
                            p = init_fn.params[1 + src]
                            self.bad("aot/type", init_fn.name, f"a TAGGED int is passed for the RAW parameter "
                                     f"`{p.name}` of `{init_fn.name}` (the representation did not converge)")
                            continue
                    else:
                        val = self.ev(src)
                    self.emit(f"{vf} = {val};")
            elif fd.type in (Type.STR, Type.STR_OPT):
                val = eval_args[src][0] if isinstance(src, int) else self.ev(src)
                self.emit(f"tpa_str_set(&{vf}, {val});")
            elif fd.type is Type.OBJ_LIST:                                   # tier S2a: the field shares the list
                val = eval_args[src][0] if isinstance(src, int) else self.ev(src)
                self.emit(f"tpa_obj_list_set(&{vf}, {val});")
            elif fd.type in LIST:
                assert isinstance(src, int)
                val, _, arg_kind = eval_args[src]
                kind = arg_kind or ("f64" if fd.type is Type.F64_ARRAY else (
                    "int" if self.g.reps.field_list_big(cls, fname) else "i64"))
                self.emit(f"{lfn(kind, 'retain')}({val});")
                self.emit(f"{lfn(kind, 'release')}({vf});")
                self.emit(f"{vf} = {val};")
            else:
                assert fd.cls is not None
                arg = e.args[src] if isinstance(src, int) else src
                val = eval_args[src][0] if isinstance(src, int) else self.ev(src)
                if region_obj and fd.cls in self.regions:
                    if self.region_value_ok(arg, fd.cls, f"the field `{cls}.{fname}`"):
                        self.emit(f"{vf} = {val};")
                    continue
                if not self.no_region(arg, f"the field `{cls}.{fname}`"):
                    continue
                mc = mangle(fd.cls)
                tmp = self.tmp(self.g.cls_ptr(fd.cls), "NULL")
                self.emit(f"{tmp} = {val};")
                self.emit(f"tpa_cls_{mc}_retain({tmp});")
                self.emit(f"tpa_cls_{mc}_release({vf});")
                self.emit(f"{vf} = {tmp};")

    def _emit_virtual_init(self, target: str, cls: str, e: ir.New) -> None:
        """Scalar-replaced constructor: evaluate args in order and assign directly to virtual field locals."""
        init_fn = self.g.fns.get(f"{cls}.__init__")
        eval_args = self._eval_new_args(cls, e, init_fn)
        if eval_args is None:
            return
        self._emit_init_stores(cls, e, init_fn, eval_args, lambda fname: self.vfield(target, fname), False)

    def _emit_region_new(self, e: ir.New) -> str:
        """A site of a region class (PR-O2b): the arguments are evaluated first, in order (a failing one leaves
        before anything is allocated), then the object is bump-allocated, zeroed, in the region of its class;
        its big-int fields are set to the small int 0 before any store can fail (the word 0 is not a value), so
        a conversion that fails between two stores leaves an object whose unset fields release as nothing. The
        result is a region pointer in a temporary that nothing counts or releases."""
        cls = e.cls
        init_fn = self.g.fns.get(f"{cls}.__init__")
        eval_args = self._eval_new_args(cls, e, init_fn)
        if eval_args is None:
            return "NULL"
        rs = self.g.region_struct(self.f.name, cls)
        t = self.tmp(f"{rs} *", "NULL")
        self.emit(f"if (({t} = ({rs} *)tpa_region_alloc(&{self.region_var(cls)}, sizeof({rs}))) == NULL) "
                  f"{self.fail()}")
        decl = self.g.cls_analysis.classes[cls]
        for fd in decl.field_decls:
            if fd.type is Type.I64 and self.g.reps.field(cls, fd.name) == TAGGED:
                self.emit(f"{t}->f_{mangle(fd.name)} = TPA_INT_ZERO;")
            elif fd.type in (Type.STR, Type.STR_OPT, Type.OBJ_LIST):
                self.emit(f"{t}->f_{mangle(fd.name)} = NULL;")      # (region memory is not zeroed)
        self._emit_init_stores(cls, e, init_fn, eval_args, lambda fname: f"{t}->f_{mangle(fname)}", True)
        return t

    def st_FieldSet(self, s: ir.FieldSet) -> None:
        fd = self.g.cls_analysis.field_map.get((s.cls, s.field))
        if fd is None:
            self.bad("aot/type", s.field, f"unknown field `{s.cls}.{s.field}`")
            return
        region_obj = self.expr_region_cls(s.obj) is not None      # as ev_FieldGet: a region object, however it is named
        if isinstance(s.obj, ir.Local) and self.is_virtual(s.obj.name):
            lval = self.vfield(s.obj.name, s.field)
        else:
            obj = self.ev(s.obj)
            lval = f"{obj}->f_{mangle(s.field)}"
        if fd.type in (Type.F64, Type.BOOL):
            val = self.ev(s.value)
            self.emit(f"{lval} = {val};")
        elif fd.type is Type.I64:
            if self.g.reps.field(s.cls, s.field) == TAGGED:
                val = self.tagged(s.value)
                self.emit(f"tpa_int_set(&{lval}, {val});")
            else:
                val = self.ev(s.value)
                self.emit(f"{lval} = {val};")
        elif fd.type in (Type.STR, Type.STR_OPT):
            val = self.ev(s.value) if fd.type is Type.STR_OPT else self.str_value(s.value, f"the value stored into .{s.field}")
            self.emit(f"tpa_str_set(&{lval}, {val});")         # the new reference is held before the old is released
        elif fd.type is Type.OBJ_LIST:
            val = self.ev(s.value)
            self.emit(f"tpa_obj_list_set(&{lval}, {val});")    # tier S2a (the new reference first)
        elif fd.type in LIST:
            kind = "f64" if fd.type is Type.F64_ARRAY else (
                "int" if self.g.reps.field_list_big(s.cls, s.field) else "i64")
            self.bind_kind = kind
            try:
                val, owned = self.list_value(s.value, fd.type)
            finally:
                self.bind_kind = None
            if not owned:
                self.emit(f"{lfn(kind, 'retain')}({val});")
            self.emit(f"{lfn(kind, 'release')}({lval});")
            self.emit(f"{lval} = {val};")
        else:
            assert fd.cls is not None
            mc = mangle(fd.cls)
            val = self.ev(s.value)
            if region_obj and fd.cls in self.regions:
                # a region pointer field of a region object: a plain store (PR-O2b)
                if self.region_value_ok(s.value, fd.cls, f"the field `{s.cls}.{s.field}`"):
                    self.emit(f"{lval} = {val};")
                return
            if not self.no_region(s.value, f"the field `{s.cls}.{s.field}`"):
                return
            tmp = self.tmp(self.g.cls_ptr(fd.cls), "NULL")
            self.emit(f"{tmp} = {val};")
            self.emit(f"tpa_cls_{mc}_retain({tmp});")
            self.emit(f"tpa_cls_{mc}_release({lval});")
            self.emit(f"{lval} = {tmp};")

    def st_StoreIndex(self, s: ir.StoreIndex) -> None:
        """`a[i] = v`: CPython evaluates the value, then the list, then the index; an out-of-range index
        raises after that (IndexError("list assignment index out of range"))."""
        found = self.list_of(s.array, "an element store")
        big = found is not None and self.kind(s.array) == "int"
        val = self.tagged(s.value) if big else self.ev(s.value)
        idx = self.ev(s.index)
        if found is None:
            return
        arr, t = found
        if not big and t is Type.I64_ARRAY and s.value.type is Type.I64 and self.rep(s.value) == TAGGED:
            self.bad("aot/type", s.array, f"a TAGGED value is stored into the raw list `{s.array}` (the list "
                     "classification did not converge)")
            return

        def put(at: str) -> None:
            # a big-capable list takes a new reference to the element and gives up the one it held (under the list's
            # lock in a library build, `tpa_int_list_store`)
            self.emit(f"tpa_int_list_store({arr}, {at}, {val});" if big else f"{arr}->d[{at}] = {val};")
        if self.rep(s.index) == TAGGED:
            slot = self.tmp("int64_t")
            self.emit(f"if (tpa_slot_int({arr}->len, {idx}, 1, &{slot})) {self.fail()}")
            put(slot)
        elif s.proven and self.trusted(s):
            put(idx)
        else:
            slot = self.tmp("int64_t")
            self.emit(f"if (tpa_slot({arr}->len, {idx}, 1, &{slot})) {self.fail()}")
            put(slot)

    # --- tier S2a: lists that change length (design 4.3.2) ---

    def grow_list(self, name: str, what: str) -> tuple[str, str] | None:
        """(C name, kind) of the list `name` an append / insert / extend / pop / clear / reverse works on: a
        list[float] / list[int] or a list[str]."""
        if self.types.get(name) is Type.STR_LIST:
            self.g.uses_str = True
            return f"v_{mangle(name)}", "str"
        if self.types.get(name) is Type.OBJ_LIST:
            return f"v_{mangle(name)}", "obj"                    # tier S2a: a list of class instances
        found = self.list_of(name, what)
        return None if found is None else (found[0], self.kind(name))

    @staticmethod
    def lop(kind: str, op: str) -> str:
        """The runtime function of the list method `op` for `kind` (a list of str: tpa_str_list_*, where `clear` is
        `lclear`, tpa_str_list_clear being the slot helper)."""
        if kind == "str":
            return f"tpa_str_list_{'lclear' if op == 'clear' else op}"
        if kind == "obj":
            return f"tpa_obj_list_{op}"
        return lfn(kind, op)

    def list_item_c(self, e: ir.Expr, name: str) -> str:
        """An item given to a list method: borrowed TAGGED for a big-capable list (the list takes its own reference),
        the native value for a raw or float list."""
        if self.types.get(name) is Type.STR_LIST:
            return self.str_value(e, f"the item given to a method of `{name}`")
        if self.types.get(name) is Type.OBJ_LIST:
            return self.ev(e)                                     # an instance, borrowed (the list retains it)
        if self.types.get(name) in LIST and self.kind(name) == "int":
            return self.tagged(e)
        if e.type is Type.I64 and self.rep(e) == TAGGED:
            self.bad("aot/type", name, f"a TAGGED value is stored into the raw list `{name}` (the list classification "
                     "did not converge)")
        return self.ev(e)

    def st_ListAppend(self, s: ir.ListAppend) -> None:
        v = self.list_item_c(s.value, s.array)
        found = self.grow_list(s.array, "an append")
        if found is not None:
            self.emit(f"if ({self.lop(found[1], 'append')}({found[0]}, {v})) {self.fail()}")

    def st_ListInsert(self, s: ir.ListInsert) -> None:
        idx = self.index_c(s.index)                         # CPython evaluates the index, then the item
        v = self.list_item_c(s.value, s.array)
        found = self.grow_list(s.array, "an insert")
        if found is not None:
            self.emit(f"if ({self.lop(found[1], 'insert')}({found[0]}, {idx}, {v})) {self.fail()}")

    def st_ListExtend(self, s: ir.ListExtend) -> None:
        found = self.grow_list(s.array, "an extend")
        other = self.grow_list(s.other, "an extend source")
        if found is not None and other is not None:
            if other[1] != found[1]:
                self.bad("aot/type", s.array, f"`{s.array}.extend({s.other})` joins lists of two kinds (the list "
                         "classification did not merge them)")
                return
            self.emit(f"if ({self.lop(found[1], 'extend')}({found[0]}, {other[0]})) {self.fail()}")

    def slice_args(self, s) -> str:
        vals, has = [], []
        for b, flag in ((s.start, "TPA_SLICE_START"), (s.stop, "TPA_SLICE_STOP"), (s.step, "TPA_SLICE_STEP")):
            if b is None:
                vals.append("0")
            else:
                vals.append(self.clamp(b))
                has.append(flag)
        return ", ".join(vals) + ", " + (" | ".join(has) or "0")

    def st_ListDelete(self, s: ir.ListDelete) -> None:
        if s.is_slice:
            args = self.slice_args(s)
            found = self.grow_list(s.array, "a del")
            if found is not None:
                self.emit(f"if ({self.lop(found[1], 'delslice')}({found[0]}, {args})) {self.fail()}")
            return
        v = self.ev(s.index)
        found = self.grow_list(s.array, "a del")
        if found is None:
            return
        if self.rep(s.index) == TAGGED:            # an index outside int64: IndexError, as an element store's
            slot = self.tmp("int64_t")
            self.emit(f"if (tpa_slot_int({found[0]}->len, {v}, 1, &{slot})) {self.fail()}")
            v = slot
        self.emit(f"if ({self.lop(found[1], 'delitem')}({found[0]}, {v})) {self.fail()}")

    def st_ListSetSlice(self, s: ir.ListSetSlice) -> None:
        args = self.slice_args(s)
        found = self.grow_list(s.array, "a slice assignment")
        other = self.grow_list(s.other, "a slice assignment source")
        if found is None or other is None:
            return
        if other[1] != found[1]:
            self.bad("aot/type", s.array, f"`{s.array}[...] = {s.other}` joins lists of two kinds (the list "
                     "classification did not merge them)")
            return
        self.emit(f"if ({self.lop(found[1], 'setslice')}({found[0]}, {args}, {other[0]})) {self.fail()}")

    def st_ListClear(self, s: ir.ListClear) -> None:
        found = self.grow_list(s.array, "a clear")
        if found is not None:
            self.emit(f"{self.lop(found[1], 'clear')}({found[0]});")

    def st_ListReverse(self, s: ir.ListReverse) -> None:
        found = self.grow_list(s.array, "a reverse")
        if found is not None:
            self.emit(f"{self.lop(found[1], 'reverse')}({found[0]});")

    def search_value(self, value: ir.Expr, kind: str, name: str) -> str | None:
        """The searched value in the form the list's kind compares: TAGGED for a big-capable list, a RAW int64 for a
        raw one (a TAGGED value searched in a raw list is not in the AOT mode yet)."""
        if kind == "int":
            return self.tagged(value)
        if kind == "str":
            return self.str_value(value, f"the str searched in `{name}`")
        if kind == "obj":
            return self.ev(value)
        if kind == "i64" and not (value.type is Type.I64 and self.rep(value) == TAGGED):
            return self.ev(value)
        self.ev(value)
        self.bad("aot/type", name, f"the raw list `{name}` is searched for an int that is not proved inside int64 (not "
                 "in the AOT mode yet)")
        return None

    def st_ListRemove(self, s: ir.ListRemove) -> None:
        found = self.grow_list(s.array, "a remove")
        if found is None:
            return
        v = self.search_value(s.value, found[1], s.array)
        if v is not None:
            self.emit(f"if ({self.lop(found[1], 'remove')}({found[0]}, {v})) {self.fail()}")

    def index_c(self, e: ir.Expr, convert: str = "tpa_int_to_ssize") -> str:
        """An int argument of insert / pop (a Py_ssize_t parameter: OverflowError outside it) or a repeat count
        (`convert` tpa_int_to_count) as an int64."""
        v = self.ev(e)
        if self.rep(e) != TAGGED:
            return v
        n = self.tmp("int64_t")
        self.emit(f"if ({convert}({v}, &{n})) {self.fail()}")
        return n

    def ev_ListPop(self, e: ir.ListPop) -> str:
        idx = self.index_c(e.index) if e.index is not None else "0"
        found = self.grow_list(e.array, "a pop")
        if found is None:
            return "0"
        kind = found[1]
        has = 0 if e.index is None else 1
        if kind == "obj":                                         # tier S2a: the instance's reference moves out
            cls = self.f.list_classes.get(e.array) or next(
                (p.cls for p in self.f.params if isinstance(p, ir.Param) and p.name == e.array), None)
            t = self.oslot(cls)
            self.emit("{")
            self.emit("    void *tpa_v_ = NULL;")
            self.emit(f"    if (tpa_obj_list_pop({found[0]}, {has}, {idx}, &tpa_v_)) {self.fail()}")
            self.emit(f"    tpa_cls_{mangle(cls)}_release({t}); {t} = ({self.g.cls_ptr(cls).strip()})tpa_v_;")
            self.emit("}")
            return t
        t = (self.tslot() if kind == "int" else self.pslot() if kind == "str"
             else self.tmp("double" if kind == "f64" else "int64_t"))
        self.emit(f"if ({self.lop(kind, 'pop')}({found[0]}, {has}, {idx}, &{t})) {self.fail()}")
        return t

    def list_make_into(self, e: ir.Expr, names: tuple[str, ...], call: str) -> str:
        """A new list made from the lists `names` (tier S2a): a list[str] into a list-of-str slot, a list[float] /
        list[int] into a list temporary of the receiving name's kind (as a copy)."""
        found = [self.grow_list(n, f"a {type(e).__name__}") for n in names]
        if any(x is None for x in found):
            return "NULL"
        kinds = {x[1] for x in found}                                   # type: ignore[index]
        if e.type is Type.STR_LIST:
            return self.lpstr(call.format(*[x[0] for x in found], fn=self.lop("str", "")) )   # type: ignore[index]
        dst = self.bind_kind
        if dst is None:
            return self.bad("aot/type", "list", "a list is made where no list name receives it")
        if kinds != {dst}:
            return self.bad("aot/type", names[0], f"a list of kind {sorted(kinds)} is made into one of kind {dst} (the "
                            "list classification did not merge them)")
        out = self.list_tmp(dst)
        text = call.format(*[x[0] for x in found], fn=self.lop(dst, "")).replace("@", "&" + out)  # type: ignore[index]
        self.emit(f"if ({text}) {self.fail()}")
        return out

    def ev_ListConcat(self, e: ir.ListConcat) -> str:
        return self.list_make_into(e, (e.left, e.right), "{fn}concat({0}, {1}, @)")

    def ev_ListRepeat(self, e: ir.ListRepeat) -> str:
        n = self.index_c(e.count, "tpa_int_to_count")
        return self.list_make_into(e, (e.src,), "{fn}repeat({0}, " + n + ", @)")

    def ev_ListSlice(self, e: ir.ListSlice) -> str:
        vals, has = [], []
        for b, flag in ((e.start, "TPA_SLICE_START"), (e.stop, "TPA_SLICE_STOP"), (e.step, "TPA_SLICE_STEP")):
            if b is None:
                vals.append("0")
            else:
                vals.append(self.clamp(b))
                has.append(flag)
        return self.list_make_into(e, (e.src,), "{fn}slice({0}, " + ", ".join(vals) + ", "
                                   + (" | ".join(has) or "0") + ", @)")

    def ev_ListCompare(self, e: ir.ListCompare) -> str:
        a = self.grow_list(e.left, "a comparison")
        b = self.grow_list(e.right, "a comparison")
        if a is None or b is None:
            return "0"
        if a[1] != b[1] or a[1] not in ("i64", "int", "str"):
            self.bad("aot/type", e.left, f"`{e.left}` and `{e.right}` are lists of two kinds (the list classification "
                     "did not merge them)")
            return "0"
        op = ["EQ", "NE", "LT", "LE", "GT", "GE"].index(e.op.name)
        t = self.tmp("int")
        self.emit(f"if (({t} = {self.lop(a[1], 'cmp')}({a[0]}, {b[0]}, {op})) < 0) {self.fail()}")
        return t

    def ev_ListStr(self, e: ir.ListStr) -> str:
        """repr of a list[float] / list[int], a new str held by a slot (borrowed by the expression that reads it)."""
        found = self.list_of(e.array, "str()")
        if found is None:
            return "NULL"
        self.g.uses_str = True
        return self.pstr(f"{lfn(self.kind(e.array), 'list_repr') if self.kind(e.array) != 'int' else 'tpa_int_list_repr'}"
                         f"({found[0]}, @)")

    def ev_ListSearch(self, e: ir.ListSearch) -> str:
        found = self.grow_list(e.array, f"list.{e.op}")
        if found is None:
            return "0"
        kind = found[1]
        v = self.search_value(e.value, kind, e.array)
        if v is None:
            return "0"
        if e.op == "count":
            return f"{self.lop(kind, 'count')}({found[0]}, {v})"
        if e.op == "in":
            return f"{self.lop(kind, 'contains')}({found[0]}, {v})"
        t = self.tmp("int64_t")
        self.emit(f"if ({self.lop(kind, 'index')}({found[0]}, {v}, &{t})) {self.fail()}")
        return t

    def st_ExprStmt(self, s: ir.ExprStmt) -> None:
        if isinstance(s.value, ir.Const):
            return                # a docstring or another constant: nothing to evaluate
        v = self.ev(s.value)      # a fallible operation or a call has been emitted; what remains has no effect
        if s.value.type in LIST:
            callee = self.g.fns.get(s.value.function) if isinstance(s.value, ir.Call) else None
            if callee is None:
                self.bad("aot/type", "list", "a list value is dropped that is not a call result")
                return
            self.emit(f"{lfn(self.g.ret_kind(callee), 'release')}({v});")      # a returned list nobody binds

    def st_Print(self, s: ir.Print) -> None:
        """`print(...)`: every argument (every field of an f-string), then `sep` and `end`, is evaluated first, in
        order (a fallible one may leave), then the text is written. A TAGGED int is formatted where CPython formats it:
        a field of an f-string when the f-string is built (so nothing of the call is written if it raises), a plain
        argument when `print` writes it (so the arguments before it are already written when the digit limit
        raises). A str is encoded when it is written (tier S1): the arguments before one that cannot be encoded are
        written, with the separator, and it writes nothing, as CPython's text stream does."""
        args: list[list[tuple[str, object]]] = []
        for a in s.args:
            if isinstance(a, ir.Const) and a.type is Type.OBJ and isinstance(a.value, str):
                args.append([("str", a.value)])
            elif isinstance(a, ir.Const) and a.type is Type.STR and isinstance(a.value, str) and _plain_text(a.value):
                args.append([("str", a.value)])                         # ASCII-safe text: written as its UTF-8
            elif isinstance(a, ir.FString) and self.fstring_by_parts(a):
                items: list[tuple[str, object]] = []
                for part in a.parts:
                    if isinstance(part, ir.Const) and isinstance(part.value, str):
                        items.append(("str", part.value))
                    elif isinstance(part, ir.FormatField) and part.value.type in CTYPE:
                        v = self.ev(part.value)
                        if part.precision is None:
                            if part.value.type is Type.I64 and self.rep(part.value) == TAGGED:
                                buf, n = self.sslot(), self.tmp("size_t")
                                self.emit(f"if (tpa_int_to_str({v}, &{buf}, &{n})) {self.fail()}")
                                items.append(("owned", (buf, n)))
                            else:
                                items.append((CTYPE[part.value.type], v))
                        else:
                            items.append(("fixed", (v, part.precision)))
                    else:
                        self.bad("aot/f-string", "f-string", "an f-string field has a form the AOT mode does not know")
                        return
                args.append(items)
            elif isinstance(a, ir.FString):
                # a str field, `!r`, or text that is not plain: the whole f-string is made first, as CPython makes it
                parts = self.fstring_parts(a)
                if parts is None:
                    return
                args.append([("pystr", self.join(parts))])
            elif a.type is Type.STR:
                args.append([("pystr", self.ev(a))])
            elif a.type is Type.STR_LIST:
                args.append([("pystr", self.str_of(a, True))])
            elif a.type is Type.STR_OPT:
                args.append([("pystr", self.str_of(a, False))])
            elif a.type in CTYPE:
                v = self.ev(a)
                if a.type is Type.I64 and self.rep(a) == TAGGED:
                    args.append([("tagged", v)])
                else:
                    args.append([(CTYPE[a.type], v)])
            else:
                known = len(self.found)
                self.ev(a)                 # names the operation that made the object, when it can
                if len(self.found) == known:
                    self.bad("aot/print-arg", "print", "print takes ints, floats, bools and strs only")
                return
        sep = None if s.sep is None else self.str_value(s.sep, "print's sep")
        end = None if s.end is None else self.str_value(s.end, "print's end")
        for i, items in enumerate(args):
            if i:
                if sep is None:
                    self.emit("tpa_print_sep();")
                else:
                    self.emit(f"if (tpa_str_print({sep})) {self.fail()}")
            for kind, val in items:
                if kind == "str":
                    lit, n = _c_string(val)                              # type: ignore[arg-type]
                    self.emit(f"tpa_print_str({lit}, {n});")
                elif kind == "pystr":
                    self.emit(f"if (tpa_str_print({val})) {self.fail()}")
                elif kind == "int64_t":
                    self.emit(f"tpa_print_i64({val});")
                elif kind == "double":
                    self.emit(f"tpa_print_f64({val});")
                elif kind == "fixed":
                    v, prec = val                                        # type: ignore[misc]
                    self.emit(f"tpa_print_f64_fixed({v}, {prec});")
                elif kind == "tagged":
                    self.emit(f"if (tpa_print_int({val})) {self.fail()}")
                elif kind == "owned":
                    buf, n = val                                         # type: ignore[misc]
                    self.emit(f"tpa_print_str({buf}, {n});")
                    self.emit(f"free({buf});")
                    self.emit(f"{buf} = NULL;")
                else:
                    self.emit(f"tpa_print_bool({val});")
        if end is None:
            self.emit("tpa_print_end();")
        else:
            self.emit(f"if (tpa_str_print({end})) {self.fail()}")

    def fstring_by_parts(self, f: ir.FString) -> bool:
        """May `print` write this f-string piece by piece (the form before tier S1)? Only when every part is plain
        text or a number or bool field without `!r`: then nothing in it can fail to encode."""
        for part in f.parts:
            if isinstance(part, ir.Const):
                if not (isinstance(part.value, str) and _plain_text(part.value)):
                    return False
            elif not (isinstance(part, ir.FormatField) and part.value.type in CTYPE and not part.repr):
                return False
        return True

    def st_ForStr(self, s: ir.ForStr) -> None:
        """`for var in value` over a str: the string is held for the loop, `var` takes each character in turn."""
        is_list = s.value.type is Type.STR_LIST
        held = self.tmp("tpa_str_list *" if is_list else "tpa_str *", "NULL")
        (self.strlist_slots if is_list else self.pystr_slots).append(held)
        v = self.ev(s.value)
        self.emit(f"{'tpa_str_list_set' if is_list else 'tpa_str_set'}(&{held}, {v});")
        k = self.tmp("int64_t")
        ch = self.tmp("tpa_str *", "NULL")
        self.emit(f"for ({k} = 0; {k} < {held}->len; {k}++) {{")
        self.depth += 1
        get = "tpa_str_list_get" if is_list else "tpa_str_index"
        self.emit(f"if ({get}({held}, {k}, &{ch})) {self.fail()}")
        self.emit(f"tpa_str_release(v_{mangle(s.var)});")
        self.emit(f"v_{mangle(s.var)} = {ch};")
        self.loop_prologue(s)
        self.stmts(s.body)
        self.depth -= 1
        self.emit("}")

    def st_If(self, s: ir.If) -> None:
        self.emit(f"if ({_unwrap(self.ev(s.cond))}) {{")
        self.block(s.then)
        if s.orelse:
            self.emit("} else {")
            self.block(s.orelse)
        self.emit("}")

    def st_While(self, s: ir.While) -> None:
        c, pre = self.capture(lambda: self.ev(s.cond))
        if not pre:
            self.emit(f"while ({_unwrap(c)}) {{")
        else:
            self.emit("for (;;) {")
            self.lines.extend(pre)
            self.emit(f"    if (!({_unwrap(c)})) break;")
        self.depth += 1
        self.loop_prologue(s)
        self.stmts(s.body)
        self.depth -= 1
        self.emit("}")

    def st_ForRange(self, s: ir.ForRange) -> None:
        if self.types.get(s.var) is not Type.I64 or s.start.type is not Type.I64 or s.stop.type is not Type.I64:
            self.bad("aot/type", "range", "a range loop needs int bounds and an int variable")
            return
        if self.rep(s.start) == TAGGED or self.rep(s.stop) == TAGGED:
            self.for_range_tagged(s)
            return
        step = s.step.value
        ts, te = self.tmp("int64_t"), self.tmp("int64_t")
        tn, tk = self.tmp("uint64_t"), self.tmp("uint64_t")
        self.emit(f"{ts} = {self.ev(s.start)};")                     # range() evaluates its arguments once, in order
        self.emit(f"{te} = {self.ev(s.stop)};")
        mag = f"UINT64_C({abs(step)})"
        if step > 0:
            self.emit(f"if ({ts} < {te}) {{")
            self.emit(f"    {tn} = ((uint64_t){te} - (uint64_t){ts} - 1u) / {mag} + 1u;")
            sign = "+"
        else:
            self.emit(f"if ({ts} > {te}) {{")
            self.emit(f"    {tn} = ((uint64_t){ts} - (uint64_t){te} - 1u) / {mag} + 1u;")
            sign = "-"
        self.emit(f"    for ({tk} = 0; {tk} < {tn}; {tk}++) {{")
        value = f"(int64_t)((uint64_t){ts} {sign} {tk} * {mag})"
        if self.tagged_name(s.var):
            # RAW bounds but a TAGGED variable (the name is also assigned TAGGED values elsewhere)
            self.emit(f"        tpa_int_clear(&v_{mangle(s.var)});")
            self.emit(f"        if (tpa_int_from_i64({value}, &v_{mangle(s.var)})) {self.fail()}")
        else:
            self.emit(f"        v_{mangle(s.var)} = {value};")
        self.depth += 2
        self.loop_prologue(s)
        self.stmts(s.body)
        self.depth -= 2
        self.emit("    }")
        self.emit("}")

    def for_range_tagged(self, s: ir.ForRange) -> None:
        """`for var in range(start, stop, step)` with a TAGGED bound: exact for any bounds (a loop that leaves early
        from a range of 10**30 values runs the iterations it runs). range() evaluates its arguments once, in order, so
        both bounds are held in slots of their own (the body may assign the names they were read from); the counter
        is a third slot, advanced at the top of every iteration but the first, which is where `continue` goes."""
        if not self.tagged_name(s.var):
            self.bad("aot/type", "range", f"the loop variable `{s.var}` of a range with a TAGGED bound is RAW "
                     "(the representation did not converge)")
            return
        step = int(s.step.value)
        start = self.tagged(s.start)
        stop = self.tagged(s.stop)
        cur, end, nxt = self.tslot(), self.tslot(), self.tslot()
        first = self.tmp("int", "0")
        self.emit(f"tpa_int_set(&{cur}, {start});")
        self.emit(f"tpa_int_set(&{end}, {stop});")
        self.emit(f"{first} = 1;")
        if -(2**62) <= step < 2**62:
            stepv = f"tpa_int_small({_i64(step)})"
        else:
            stepv = self.tslot()
            self.emit(f"tpa_int_clear(&{stepv});")
            self.emit(f"if (tpa_int_from_i64({_i64(step)}, &{stepv})) {self.fail()}")
        test = "<" if step > 0 else ">"
        self.emit("for (;;) {")
        self.emit(f"    if ({first}) {{")
        self.emit(f"        {first} = 0;")
        self.emit("    } else {")
        self.emit(f"        tpa_int_clear(&{nxt});")
        self.emit(f"        if (tpa_int_add({cur}, {stepv}, &{nxt})) {self.fail()}")
        self.emit(f"        tpa_int_set(&{cur}, {nxt});")
        self.emit("    }")
        self.emit(f"    if (!(tpa_int_cmp({cur}, {end}) {test} 0)) break;")
        self.emit(f"    tpa_int_set(&v_{mangle(s.var)}, {cur});")
        self.depth += 1
        self.loop_prologue(s)
        self.stmts(s.body)
        self.depth -= 1
        self.emit("}")

    def st_Return(self, s: ir.Return) -> None:
        returns = self.f.returns
        if s.value is None:
            if returns is not Type.NONE:
                self.bad("aot/type", "return", "a bare return in a function that returns a value")
            self.leave()
            return
        if returns is Type.OBJ and self.f.return_cls is not None:
            cls = self.f.return_cls
            mc = mangle(cls)
            val = self.ev(s.value)
            if not self.no_region(s.value, "a return"):
                return
            tmp = self.tmp(self.g.cls_ptr(cls), "NULL")
            self.emit(f"{tmp} = {val};")
            self.emit(f"tpa_cls_{mc}_retain({tmp});")
            self.emit(f"*out = {tmp};")
            self.leave()
            return
        if returns in LIST:
            x = self.g.ret_kind(self.f)
            if not self.kinds_agree(s.value, x, "return"):
                return
            self.bind_kind = x
            try:
                val, owned = self.list_value(s.value, returns)
            finally:
                self.bind_kind = None
            if not owned:
                self.emit(f"{lfn(x, 'retain')}({val});")   # the caller gets its own reference
            self.emit(f"*out = {val};")
            self.leave()
            return
        if returns is Type.OBJ_LIST:
            v = self.ev(s.value)
            self.emit(f"tpa_obj_list_retain({v});")
            self.emit(f"*out = {v};")
            self.leave()
            return
        if returns is Type.STR_LIST:
            v = self.ev(s.value)
            self.emit(f"tpa_str_list_retain({v});")
            self.emit(f"*out = {v};")
            self.leave()
            return
        if returns in (Type.STR, Type.STR_OPT):
            v = self.ev(s.value) if returns is Type.STR_OPT else self.str_value(s.value, "the return value")
            self.emit(f"tpa_str_retain({v});")                         # the caller gets its own reference
            self.emit(f"*out = {v};")
            self.leave()
            return
        if s.value.type is Type.NONE or returns is Type.NONE:
            self.ev(s.value)
            self.leave()
            return
        if s.value.type is not returns:
            self.bad("aot/type", "return", f"a {s.value.type.value} is returned from a function declared "
                     f"to return {returns.value}")
            return
        if returns is Type.I64 and self.g.reps.returns(self.f.name) == TAGGED:
            v = self.tagged(s.value)
            self.emit(f"tpa_int_retain({v});")                         # the caller gets its own reference
            self.emit(f"*out = {v};")
            self.leave()
            return
        v = self.ev(s.value)
        if returns is Type.I64 and self.rep(s.value) == TAGGED:
            self.bad("aot/type", "return", "a TAGGED int is returned from a RAW function (the representation did not "
                     "converge)")
            return
        self.emit(f"*out = {v};")
        self.leave()

    def st_ReturnTuple(self, s: ir.ReturnTuple) -> None:
        kinds = self.g.tuple_kinds(self.f)
        if kinds is None or len(kinds) != len(s.values) or any(v.type is not k for v, k in zip(s.values, kinds)):
            for v in s.values:
                self.ev(v)
            self.bad("aot/type", "return", "a tuple of ints, floats and bools of one shape is returned")
            return
        vals = []
        for i, v in enumerate(s.values):
            if v.type is Type.I64 and self.g.reps.element(self.f.name, i) == TAGGED:
                vals.append(("int", self.tagged(v)))
            elif v.type is Type.STR:
                vals.append(("str", self.str_value(v, f"element {i} of the returned tuple")))
            else:
                vals.append(("", self.ev(v)))
        for i, (held, v) in enumerate(vals):     # (every value evaluated first: nothing is written on a failure)
            if held == "int":
                self.emit(f"tpa_int_retain({v});")
            elif held == "str":
                self.emit(f"tpa_str_retain({v});")                         # the caller gets its own reference
            self.emit(f"*out{i} = {v};")
        self.leave()

    def st_Unpack(self, s: ir.Unpack) -> None:
        call = s.call
        callee = self.g.fns.get(call.function)
        if callee is None:
            self.bad("aot/call", call.function, f"the call of `{call.function}` has no AOT form")
            return
        kinds = self.g.tuple_kinds(callee)
        args = self.call_args(callee, call)
        if kinds is None or args is None or len(kinds) != len(s.targets):
            self.bad("aot/type", call.function, f"`{call.function}` does not return a tuple of {len(s.targets)} scalars")
            return
        outs = []
        for i, k in enumerate(kinds):
            if k is Type.I64 and self.g.reps.element(callee.name, i) == TAGGED:
                outs.append((True, self.tslot()))
            elif k is Type.STR:
                outs.append((False, self.pslot()))      # tier S2a: owns the element the callee writes (emptied now)
            else:
                outs.append((False, self.tmp(CTYPE[k])))
        for is_tagged, t in outs:
            if is_tagged:
                self.emit(f"tpa_int_clear(&{t});")
        self.emit(f"if (tpf_{mangle(call.function)}({', '.join(args + ['&' + t for _, t in outs])})) {self.fail()}")
        for target, k, (is_tagged, t) in zip(s.targets, kinds, outs):
            if self.types.get(target) is not k:
                self.bad("aot/type", target, f"`{target}` is not a {k.value}, which `{call.function}` returns there")
                continue
            if self.tagged_name(target):
                if is_tagged:
                    self.emit(f"tpa_int_set(&v_{mangle(target)}, {t});")
                else:
                    conv = self.tslot()
                    self.emit(f"tpa_int_clear(&{conv});")
                    self.emit(f"if (tpa_int_from_i64({t}, &{conv})) {self.fail()}")
                    self.emit(f"tpa_int_set(&v_{mangle(target)}, {conv});")
            elif is_tagged:
                self.bad("aot/type", target, f"a TAGGED int is unpacked into the RAW name `{target}` (the representation "
                         "did not converge)")
            elif k is Type.STR:
                self.emit(f"tpa_str_set(&v_{mangle(target)}, {t});")
            else:
                self.emit(f"v_{mangle(target)} = {t};")

    def st_Break(self, s: ir.Break) -> None:
        self.emit("break;")

    def st_Continue(self, s: ir.Continue) -> None:
        self.emit("continue;")

    # -- the function

    def run(self) -> list[str] | None:
        f = self.f
        for p in f.params:
            ok = ((isinstance(p, ir.Param) and p.type in CTYPE and p.cls is None)
                  or (isinstance(p, ir.Param) and p.type in (Type.STR, Type.STR_LIST, Type.STR_OPT, Type.OBJ_LIST))
                  or (isinstance(p, ir.Param) and p.type is Type.OBJ and p.cls is not None)
                  or (isinstance(p, ir.ArrayParam) and p.type in LIST and p.buffer is None))
            if not ok:
                self.bad("aot/type", getattr(p, "name", "?"), f"the parameter `{getattr(p, 'name', '?')}` is not an "
                         "int, float, bool, list[float], list[int] or compiled class")
        if f.returns not in CTYPE and f.returns not in LIST and f.returns is not Type.NONE \
                and f.returns not in (Type.STR, Type.STR_LIST, Type.STR_OPT, Type.OBJ_LIST) \
                and not (f.returns is Type.OBJ and f.return_cls is not None) \
                and self.g.tuple_kinds(f) is None:
            self.bad("aot/type", f.name, f"the return type {f.returns.value} is outside the AOT mode (int, float, "
                     "bool, None, list[float], list[int], compiled class and a tuple of ints, floats and bools)")
        for p in f.entry_globals:
            self.bad("aot/global", p.name, f"the module global `{p.name}` is read at entry, which has no AOT form")
        for name, t in f.locals.items():
            if t in (Type.STR, Type.STR_LIST, Type.STR_OPT):
                self.g.uses_str = True
            elif t is Type.OBJ_LIST:
                pass                                                     # tier S2a
            elif t not in LIST and not (t is Type.OBJ and name in f.local_classes):
                self.ctype(t, name)
        owned = [p for p in f.params if self.g.owned_param(f, p)]
        for p in owned:                      # a TAGGED, class or str parameter the body assigns: work on an owned copy
            if isinstance(p, ir.Param) and p.type in (Type.STR, Type.STR_OPT):
                self.emit(f"tpa_str_set(&v_{mangle(p.name)}, pv_{mangle(p.name)});")
            elif isinstance(p, ir.Param) and p.type is Type.STR_LIST:
                self.emit(f"tpa_str_list_set(&v_{mangle(p.name)}, pv_{mangle(p.name)});")
            elif isinstance(p, ir.Param) and p.type is Type.OBJ_LIST:
                self.emit(f"tpa_obj_list_set(&v_{mangle(p.name)}, pv_{mangle(p.name)});")
            elif isinstance(p, ir.Param) and p.type is Type.OBJ and p.cls is not None:
                mc = mangle(p.cls)
                self.emit(f"v_{mangle(p.name)} = pv_{mangle(p.name)};")
                self.emit(f"tpa_cls_{mc}_retain(v_{mangle(p.name)});")
            else:
                self.emit(f"tpa_int_set(&v_{mangle(p.name)}, pv_{mangle(p.name)});")     # type: ignore[union-attr]
        self.stmts(f.body)
        if self.found:
            return None
        decls = []
        releases = []
        for p in owned:
            if isinstance(p, ir.Param) and p.type in (Type.STR, Type.STR_OPT):
                decls.append(f"    tpa_str *v_{mangle(p.name)} TPA_UNUSED = NULL;")
                releases.append(f"    tpa_str_release(v_{mangle(p.name)});")
            elif isinstance(p, ir.Param) and p.type is Type.STR_LIST:
                decls.append(f"    tpa_str_list *v_{mangle(p.name)} TPA_UNUSED = NULL;")
                releases.append(f"    tpa_str_list_release(v_{mangle(p.name)});")
            elif isinstance(p, ir.Param) and p.type is Type.OBJ_LIST:
                decls.append(f"    tpa_obj_list *v_{mangle(p.name)} TPA_UNUSED = NULL;")
                releases.append(f"    tpa_obj_list_release(v_{mangle(p.name)});")
            elif isinstance(p, ir.Param) and p.type is Type.OBJ and p.cls is not None:
                mc = mangle(p.cls)
                decls.append(f"    tpa_cls_{mc} *v_{mangle(p.name)} TPA_UNUSED = NULL;")
                releases.append(f"    tpa_cls_{mc}_release(v_{mangle(p.name)});")
            else:
                decls.append(f"    tpa_int v_{mangle(p.name)} TPA_UNUSED = TPA_INT_ZERO;")        # type: ignore[union-attr]
                releases.append(f"    tpa_int_release(v_{mangle(p.name)});")                       # type: ignore[union-attr]
        for name, t in f.locals.items():
            if t in (Type.STR, Type.STR_OPT):
                decls.append(f"    tpa_str *v_{mangle(name)} TPA_UNUSED = NULL;")
                releases.append(f"    tpa_str_release(v_{mangle(name)});")
                continue
            if t is Type.STR_LIST:
                decls.append(f"    tpa_str_list *v_{mangle(name)} TPA_UNUSED = NULL;")
                releases.append(f"    tpa_str_list_release(v_{mangle(name)});")
                continue
            if t is Type.OBJ_LIST:
                decls.append(f"    tpa_obj_list *v_{mangle(name)} TPA_UNUSED = NULL;")
                releases.append(f"    tpa_obj_list_release(v_{mangle(name)});")
                continue
            if t in LIST:
                kind = self.kind(name)
                decls.append(f"    {list_ptr(kind)}v_{mangle(name)} TPA_UNUSED = NULL;")
                releases.append(f"    {lfn(kind, 'release')}(v_{mangle(name)});")
            elif t is Type.OBJ and name in f.local_classes:
                cls = f.local_classes[name][0]
                if self.is_virtual(name):
                    decl = self.g.cls_analysis.classes.get(cls)
                    if decl is not None:
                        for fd in decl.field_decls:
                            vf = self.vfield(name, fd.name)
                            if fd.type in (Type.F64, Type.BOOL):
                                decls.append(f"    {CTYPE[fd.type]} {vf} TPA_UNUSED = 0;")
                            elif fd.type is Type.I64:
                                if self.g.reps.field(cls, fd.name) == TAGGED:
                                    decls.append(f"    tpa_int {vf} TPA_UNUSED = TPA_INT_ZERO;")
                                    releases.append(f"    tpa_int_release({vf});")
                                else:
                                    decls.append(f"    int64_t {vf} TPA_UNUSED = 0;")
                            elif fd.type in LIST:
                                kind = "f64" if fd.type is Type.F64_ARRAY else (
                                    "int" if self.g.reps.field_list_big(cls, fd.name) else "i64")
                                decls.append(f"    {list_ptr(kind)}{vf} TPA_UNUSED = NULL;")
                                releases.append(f"    {lfn(kind, 'release')}({vf});")
                            elif fd.type in (Type.STR, Type.STR_OPT):
                                decls.append(f"    tpa_str *{vf} TPA_UNUSED = NULL;")
                                releases.append(f"    tpa_str_release({vf});")
                            elif fd.type is Type.OBJ_LIST:
                                decls.append(f"    tpa_obj_list *{vf} TPA_UNUSED = NULL;")
                                releases.append(f"    tpa_obj_list_release({vf});")
                            else:
                                assert fd.cls is not None
                                fmc = mangle(fd.cls)
                                decls.append(f"    tpa_cls_{fmc} *{vf} TPA_UNUSED = NULL;")
                                releases.append(f"    tpa_cls_{fmc}_release({vf});")
                elif self.is_region_local(name):
                    decls.append(f"    {self.reg_ptr(cls)}v_{mangle(name)} TPA_UNUSED = NULL;")     # never counted
                else:
                    mc = mangle(cls)
                    decls.append(f"    tpa_cls_{mc} *v_{mangle(name)} TPA_UNUSED = NULL;")
                    if name not in self.borrowed_locals:
                        releases.append(f"    tpa_cls_{mc}_release(v_{mangle(name)});")
            elif self.tagged_name(name):
                decls.append(f"    tpa_int v_{mangle(name)} TPA_UNUSED = TPA_INT_ZERO;")
                releases.append(f"    tpa_int_release(v_{mangle(name)});")
            else:
                decls.append(f"    {CTYPE[t]} v_{mangle(name)} TPA_UNUSED = 0;")
        for ct, n, init in self.temps:
            decls.append(f"    {ct} {n} TPA_UNUSED = {init};")
        for n in self.tagged_slots:
            releases.append(f"    tpa_int_release({n});")
        for n in self.str_slots:
            releases.append(f"    free({n});")
        for n in self.pystr_slots:
            releases.append(f"    tpa_str_release({n});")
        for n in self.strlist_slots:
            releases.append(f"    tpa_str_list_release({n});")
        for n in self.objlist_slots:
            releases.append(f"    tpa_obj_list_release({n});")
        for cls, n in self.obj_slots:
            releases.append(f"    tpa_cls_{mangle(cls)}_release({n});")
        for kind, n in self.list_slots:
            releases.append(f"    {lfn(kind, 'release')}({n});")
        for cls in sorted(self.regions):
            # PR-O2b: one region per (function, class), freed once here on every exit (the heap references its
            # objects own are released first, by the class's walk); a per-iteration region is also reset at the
            # top of each iteration of its loop (`loop_prologue`)
            decls.append(f"    tpa_region {self.region_var(cls)} TPA_UNUSED = TPA_REGION_INIT;")
            releases.append(f"    {self.g.region_struct(f.name, cls)}_free(&{self.region_var(cls)});")
        tail = ["tp_cleanup:"] if self.cleanup_used else []
        return [self.g.signature(f), "{", "    int rc = 0;", *decls, *self.lines, *tail, *releases, "    return rc;", "}"]


# --- the module ----------------------------------------------------------------------------------

# Issue #464: turn floating-point contraction off in the generated C itself (not only by compiler flags),
# so `--emit-c` or an external build without aot/cc.py flags still computes CPython's floats.
FP_CONTRACT_PRAGMA: tuple[str, ...] = (
    "#if defined(__clang__)",
    "#pragma STDC FP_CONTRACT OFF",
    "#elif defined(__GNUC__)",
    "#pragma STDC FP_CONTRACT OFF",
    '#pragma GCC optimize("fp-contract=off")',
    "#elif defined(_MSC_VER)",
    "#pragma float_control(precise, on)",
    "#pragma fp_contract(off)",
    "#else",
    "#pragma STDC FP_CONTRACT OFF",
    "#endif",
)


def emit(module: ir.Module, program: Program, *, strict_int: bool = False,
         alloc_plan: AllocPlan | None = None) -> tuple[str | None, list[Finding]]:
    """The C text of the module and no findings, or None and the findings (every finding of every
    function). `strict_int`: no TAGGED int, an operation that is not proved is the finding `aot/int-unproven`."""
    g = _Gen(module, program, strict_int, alloc_plan=alloc_plan)
    bodies: list[list[str]] = []
    for f in g.fns.values():
        fn = _Fn(g, f)
        text = fn.run()
        g.findings.extend(fn.found)
        if text is not None:
            bodies.append(text)
    if MAIN not in g.fns:
        return None, g.findings or [Finding(program.rel, 1, "error", None, "aot/entry", "__main__",
                                            "the `__main__` block was not compiled", None)]
    if g.findings:
        return None, g.findings
    class_decls = g.emit_class_decls() + g.emit_region_decls() + g.obj_list_wrappers()     # (wrappers: tier S2a)
    class_ctors = g.emit_class_ctors()
    prototypes = [g.prototype(f) for f in g.fns.values()]
    body_text = "\n".join(class_decls + prototypes + class_ctors + [line for b in bodies for line in b])
    out = [
        "/* Generated by typedpython.aot (SPEC N-17, #448). No interpreter header, no interpreter API. */",
        # tier S2a: a list kind whose length the program changes takes the growable layout (none: the C as before)
        *[f"#define TPA_GROW_{k.upper()} 1" for k in g.grown_kinds()],
        '#include "tp_aot.h"',
    ]
    if needs_int_runtime(body_text) or g.uses_str:
        out.append('#include "tp_aot_int.h"')
    if g.uses_str:
        out += unicode_mod.tables_c(g.ucd)           # tier S1b: the tables the str methods read, from this interpreter
        out.append('#include "tp_aot_str.h"')        # tier S1: only a program with a str value links the str runtime
    gc = bool(g.cls_analysis.cyclic_classes)
    if gc:
        out.append('#include "tp_aot_gc.h"')      # PR-O2c: only a program with a cyclic-capable class links the collector
    out += ["", *FP_CONTRACT_PRAGMA, ""]
    out += g.emit_str_lits()
    out += class_decls
    out += prototypes
    out.append("")
    out += class_ctors
    for b in bodies:
        out += [*b, ""]
    if g.argv_int:
        # `int(sys.argv[i])` must see every argument exactly: on Windows the arguments arrive as UTF-16 (`wmain`), and
        # anywhere else as the bytes of the command line (decoded as the reference decodes them, tp_aot_int.h)
        head = [
            "#if defined(_WIN32)",
            "#if defined(_MSC_VER)",
            "int wmain(int argc, wchar_t **argv)",
            "#else",
            '#error "int(sys.argv[i]) needs the program arguments as UTF-16: build with MSVC or clang-cl on Windows"',
            "#endif",
            "#else",
            "int main(int argc, char **argv)",
            "#endif",
            "{",
            "    int rc;",
            "    tpa_argc = argc;",
            "#if defined(_WIN32)",
            "    tpa_wargv = argv;",
            "#else",
            "    tpa_argv = argv;",
            "#endif",
        ]
    else:
        head = [
            "int main(int argc, char **argv)",
            "{",
            "    int rc;",
            "    tpa_argc = argc;",
            "    tpa_argv = argv;",
        ]
    out += head
    out += [
        "    setvbuf(stdout, NULL, _IOFBF, 1 << 16);",
    ]
    if gc:
        out.append("    tpa_exit_hook = tpa_gc_exit;")  # sys.exit collects the cycles alive too (REVIEW-503 note 2)
    out += [
        f"    rc = tpf_{mangle(MAIN)}();",
    ]
    if gc:
        out.append("    tpa_gc_exit();")          # every frame has released its locals: the cycles that remain are freed
    out += [
        "    if (rc != 0) {",
        "        return tpa_report_uncaught();",
        "    }",
        "    fflush(stdout);",
        "    return 0;",
        "}",
        "",
    ]
    return "\n".join(out), []


def emit_functions(module: ir.Module, program: Program, names: set[str], *, strict_int: bool = False
                   ) -> tuple[_Gen, list[list[str]], list[Finding]]:
    """The library's share of the emitter (#455; agent proposal, not owner-confirmed): only the functions named
    in `names` (the exports and what they reach) are emitted, each as the same `static int tpf_<name>(...)` an
    executable has, and there is no entry. The representation of the ints is decided over those functions alone (a
    function nothing reachable calls has no say). Returns the generator (its `fns` are the emitted functions), the
    function bodies, and every finding of those functions."""
    reachable = dataclasses.replace(module, functions=tuple(f for f in module.functions if f.name in names))
    g = _Gen(reachable, program, strict_int)
    bodies: list[list[str]] = []
    for f in g.fns.values():
        fn = _Fn(g, f)
        text = fn.run()
        g.findings.extend(fn.found)
        if text is not None:
            bodies.append(text)
    return g, bodies, g.findings
