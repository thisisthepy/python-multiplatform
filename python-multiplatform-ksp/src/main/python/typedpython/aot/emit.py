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
import re

from typedpython import ir, resume
from typedpython.aot import rep as rep_mod
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
            elif isinstance(n, ir.ForRange):
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
    def __init__(self, module: ir.Module, program: Program, strict: bool = False) -> None:
        self.module = module
        self.program = program
        self.strict = strict
        self.reps = rep_mod.infer(module, strict=strict)
        self.fns = {f.name: f for f in module.functions}
        self.bodies = program.bodies()
        self.findings: list[Finding] = []
        self.chunks: list[str] = []
        self.argv_int = False           # some function reads `int(sys.argv[i])`: the entry point must keep the arguments whole

    def tuple_kinds(self, f: ir.Function) -> tuple[Type, ...] | None:
        """The element types of the tuple `f` returns, when it does and they are scalars."""
        if f.returns is not Type.OBJ:
            return None
        kinds = ir.aot_tuple_kinds(f)
        return kinds if kinds is not None and all(k in CTYPE for k in kinds) else None

    def array_kind(self, fn: str, name: str, t: Type) -> str:
        """The kind of the list `name` of function `fn` (of array type `t`)."""
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
        """A TAGGED parameter the body assigns: the caller's value is borrowed, so the body works on an owned copy."""
        return self.tagged_param(f, p) and p.name in assigned_names(f)           # type: ignore[attr-defined]

    def param_c(self, f: ir.Function, p: object) -> str:
        """The C name of a parameter (`pv_` for the borrowed original of an owned one)."""
        return ("pv_" if self.owned_param(f, p) else "v_") + mangle(p.name)       # type: ignore[attr-defined]

    def out_ctype(self, f: ir.Function, kind: Type, rep: str) -> str:
        return "tpa_int" if kind is Type.I64 and rep == TAGGED else CTYPE[kind]

    def signature(self, f: ir.Function) -> str:
        params = []
        for p in f.params:
            if isinstance(p, ir.ArrayParam) and p.type in LIST:
                params.append(f"{list_ptr(self.array_kind(f.name, p.name, p.type))}v_{mangle(p.name)} TPA_UNUSED")
            elif isinstance(p, ir.Param) and p.type in CTYPE:
                ct = "tpa_int" if self.tagged_param(f, p) else CTYPE[p.type]
                params.append(f"{ct} {self.param_c(f, p)} TPA_UNUSED")
            else:
                params.append(f"int v_{mangle(p.name)} TPA_UNUSED")        # reported by `_Fn.run`
        kinds = self.tuple_kinds(f)
        if f.returns in LIST:
            params.append(f"{list_ptr(self.ret_kind(f))}*out")
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
        return self.bad("aot/str", "str", "a string value is only supported as a literal argument of print "
                        "(strings arrive in a later step)")

    def ev_Local(self, e: ir.Local) -> str:
        if e.type not in CTYPE and e.type not in LIST:
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
            fn = {ir.BinOpKind.TRUEDIV: "truediv", ir.BinOpKind.FLOORDIV: "floordiv", ir.BinOpKind.MOD: "mod"}.get(k)
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
            if k in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL, ir.BinOpKind.FLOORDIV, ir.BinOpKind.MOD):
                a, b = self.tagged(e.left), self.tagged(e.right)
                fn = {"+": "add", "-": "sub", "*": "mul", "//": "floordiv", "%": "mod"}[sym]
                return self.fallible_int(f"tpa_int_{fn}({a}, {b}, @)")
            return self.bad("aot/operator", sym, f"the int operator `{sym}` is outside the first AOT step")
        if k in (ir.BinOpKind.ADD, ir.BinOpKind.SUB, ir.BinOpKind.MUL):
            a, b = self.ev(e.left), self.ev(e.right)
            if not e.proven:
                return self.unproven(e, "aot/int-unproven", sym, _AST_OP[k], "")
            name = {"+": "add", "-": "sub", "*": "mul"}[sym]
            return f"tpa_p{name}_i64({a}, {b})"
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
        return f"({found[0]}->len)" if found else "0"

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
            elif not isinstance(p, ir.Param) or p.type is not a.type:
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
        if isinstance(e, ir.Local):
            return self.ev_Local(e), False
        return self.ev(e), True

    def st_Assign(self, s: ir.Assign) -> None:
        want = self.types.get(s.target)
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

    def st_ExprStmt(self, s: ir.ExprStmt) -> None:
        v = self.ev(s.value)      # a fallible operation or a call has been emitted; what remains has no effect
        if s.value.type in LIST:
            callee = self.g.fns.get(s.value.function) if isinstance(s.value, ir.Call) else None
            if callee is None:
                self.bad("aot/type", "list", "a list value is dropped that is not a call result")
                return
            self.emit(f"{lfn(self.g.ret_kind(callee), 'release')}({v});")      # a returned list nobody binds

    def st_Print(self, s: ir.Print) -> None:
        """`print(...)`: every argument (every field of an f-string) is evaluated first, in order (a fallible
        one may leave), then the text is written. A TAGGED int is formatted where CPython formats it: a field of an
        f-string when the f-string is built (so nothing of the call is written if it raises), a plain argument when
        `print` writes it (so the arguments before it are already written when the digit limit raises)."""
        args: list[list[tuple[str, object]]] = []
        for a in s.args:
            if isinstance(a, ir.Const) and a.type is Type.OBJ and isinstance(a.value, str):
                args.append([("str", a.value)])
            elif isinstance(a, ir.FString):
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
                    self.bad("aot/print-arg", "print", "print takes ints, floats, bools and string literals only")
                return
        for i, items in enumerate(args):
            if i:
                self.emit("tpa_print_sep();")
            for kind, val in items:
                if kind == "str":
                    lit, n = _c_string(val)                              # type: ignore[arg-type]
                    self.emit(f"tpa_print_str({lit}, {n});")
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
        self.emit("tpa_print_end();")

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
        self.block(s.body)
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
                vals.append((True, self.tagged(v)))
            else:
                vals.append((False, self.ev(v)))
        for i, (is_tagged, v) in enumerate(vals):
            if is_tagged:
                self.emit(f"tpa_int_retain({v});")
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
                  or (isinstance(p, ir.ArrayParam) and p.type in LIST and p.buffer is None))
            if not ok:
                self.bad("aot/type", getattr(p, "name", "?"), f"the parameter `{getattr(p, 'name', '?')}` is not an "
                         "int, float, bool, list[float] or list[int]")
        if f.returns not in CTYPE and f.returns not in LIST and f.returns is not Type.NONE \
                and self.g.tuple_kinds(f) is None:
            self.bad("aot/type", f.name, f"the return type {f.returns.value} is outside the AOT mode (int, float, "
                     "bool, None, list[float], list[int] and a tuple of ints, floats and bools)")
        for p in f.entry_globals:
            self.bad("aot/global", p.name, f"the module global `{p.name}` is read at entry, which has no AOT form")
        for name, t in f.locals.items():
            if t not in LIST:
                self.ctype(t, name)
        owned = [p for p in f.params if self.g.owned_param(f, p)]
        for p in owned:                      # a TAGGED parameter the body assigns: work on an owned copy of the borrowed one
            self.emit(f"tpa_int_set(&v_{mangle(p.name)}, pv_{mangle(p.name)});")     # type: ignore[union-attr]
        self.stmts(f.body)
        if self.found:
            return None
        decls = []
        releases = []
        for p in owned:
            decls.append(f"    tpa_int v_{mangle(p.name)} TPA_UNUSED = TPA_INT_ZERO;")        # type: ignore[union-attr]
            releases.append(f"    tpa_int_release(v_{mangle(p.name)});")                       # type: ignore[union-attr]
        for name, t in f.locals.items():
            if t in LIST:
                kind = self.kind(name)
                decls.append(f"    {list_ptr(kind)}v_{mangle(name)} TPA_UNUSED = NULL;")
                releases.append(f"    {lfn(kind, 'release')}(v_{mangle(name)});")
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


def emit(module: ir.Module, program: Program, *, strict_int: bool = False) -> tuple[str | None, list[Finding]]:
    """The C text of the module and no findings, or None and the findings (every finding of every
    function). `strict_int`: no TAGGED int, an operation that is not proved is the finding `aot/int-unproven`."""
    g = _Gen(module, program, strict_int)
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
    prototypes = [g.prototype(f) for f in g.fns.values()]
    body_text = "\n".join(prototypes + [line for b in bodies for line in b])
    out = [
        "/* Generated by typedpython.aot (SPEC N-17, #448). No interpreter header, no interpreter API. */",
        '#include "tp_aot.h"',
    ]
    if needs_int_runtime(body_text):
        out.append('#include "tp_aot_int.h"')
    out += ["", *FP_CONTRACT_PRAGMA, ""]
    out += prototypes
    out.append("")
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
        f"    rc = tpf_{mangle(MAIN)}();",
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
