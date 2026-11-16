"""IR to C for the AOT mode (SPEC N-17, #448; agent proposal, not owner-confirmed).

Input: the verified IR of the default mode's front end (`ir.py`) for scalar functions. Output: one C
translation unit that includes `runtime/tp_aot.h` and nothing else (no interpreter header, no interpreter API).

The calling convention: a function returns `int` (0 ok, -1 an exception is recorded in `tpa_exc`) and
writes its result through an out pointer, as the default mode's impl functions do, without the deopt
code 1: nothing in an AOT build can deopt, so an operation that could (an int operation the IR does not
mark as proved to stay inside 64 bits) is a compile error instead (rule `aot/int-unproven`).

Every node the emitter cannot give CPython's exact result for is a finding, never an approximation.
"""
from __future__ import annotations

import ast
import re

from typedpython import ir, resume
from typedpython.aot.program import MAIN, PRINT_PREFIX, Program
from typedpython.aotcheck import Finding
from typedpython.ir import Type

CTYPE = {Type.I64: "int64_t", Type.F64: "double", Type.BOOL: "int"}
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
    if isinstance(stmt, (ast.Return, ast.Assign, ast.AnnAssign, ast.Expr)):
        return [stmt.value] if stmt.value is not None else []
    return []


def _ir_text(e: ir.Expr) -> str:
    if isinstance(e, ir.Local):
        return e.name
    if isinstance(e, ir.Const):
        return repr(e.value)
    if isinstance(e, ir.BinOp):
        return f"{_ir_text(e.left)}{e.op.value}{_ir_text(e.right)}"
    if isinstance(e, ir.UnaryOp):
        return f"{e.op.value}{_ir_text(e.operand)}"
    if isinstance(e, (ir.ToFloat, ir.ToInt, ir.Box)):
        return _ir_text(e.operand)
    if isinstance(e, ir.Call):
        return f"{e.function}({','.join(_ir_text(a) for a in e.args)})"
    return "?"


def _norm(s: str) -> str:
    return re.sub(r"[\s()]", "", s)


# --- one function --------------------------------------------------------------------------------

class _Gen:
    def __init__(self, module: ir.Module, program: Program, firsts: list[int]) -> None:
        self.module = module
        self.program = program
        self.firsts = firsts
        self.fns = {f.name: f for f in module.functions if not f.name.startswith(PRINT_PREFIX)}
        self.bodies = program.bodies()
        self.findings: list[Finding] = []
        self.chunks: list[str] = []

    def signature(self, f: ir.Function) -> str:
        params = [f"{CTYPE[p.type]} v_{mangle(p.name)} TPA_UNUSED" for p in f.params]
        if f.returns is not Type.NONE:
            params.append(f"{CTYPE[f.returns]} *out")
        return f"static int tpf_{mangle(f.name)}({', '.join(params) or 'void'})"

    def prototype(self, f: ir.Function) -> str:
        return self.signature(f) + " TPA_UNUSED;"


class _Fn:
    def __init__(self, g: _Gen, f: ir.Function) -> None:
        self.g = g
        self.f = f
        self.lines: list[str] = []
        self.depth = 1
        self.temps: list[tuple[str, str]] = []
        self.cur: int | None = None
        body = g.bodies.get(f.name)
        self.orig: list[ast.stmt] = resume.statements(body) if body is not None else []   # type: ignore[arg-type]
        self.found: list[tuple[Finding, bool]] = []       # (finding, is the INT noise of an impure function)
        self.types = {p.name: p.type for p in f.params if isinstance(p, ir.Param)}
        self.types.update(f.locals)

    # -- helpers

    def line(self) -> int:
        if self.cur is not None and 0 <= self.cur < len(self.orig):
            return self.orig[self.cur].lineno
        from typedpython.aotcheck import _original_line
        return _original_line(self.f.source_line, self.g.firsts)

    def display(self) -> str:
        return "__main__" if self.f.name == MAIN else self.f.name

    def bad(self, rule: str, name: str, message: str, noise: bool = False) -> str:
        self.found.append((Finding(self.g.program.rel, self.line(), "error", None, rule, name, message,
                                   self.display()), noise))
        return "0"

    def emit(self, s: str) -> None:
        self.lines.append("    " * self.depth + s)

    def tmp(self, ctype: str) -> str:
        n = f"t{len(self.temps)}"
        self.temps.append((ctype, n))
        return n

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
        self.emit(f"if ({call.replace('@', '&' + t)}) return -1;")
        return t

    def ctype(self, t: Type, what: str) -> str:
        if t in CTYPE:
            return CTYPE[t]
        if t is Type.INT:
            self.bad("aot/int-object", what, f"`{what}` is held as a CPython int by the front end (SPEC N-13), "
                     "which has no form without CPython", noise=True)
        else:
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
        if isinstance(stmt, ast.AugAssign) and isinstance(stmt.op, op_cls):
            cands.append(stmt)
        if len(cands) > 1:
            want = _norm(_ir_text(e))
            exact = [c for c in cands if _norm(ast.unparse(c)) == want]
            cands = exact if exact else cands
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
        if e.type not in CTYPE:
            self.ctype(e.type, e.name)
        return f"v_{mangle(e.name)}"

    def ev_ToFloat(self, e: ir.ToFloat) -> str:
        v = self.ev(e.operand)
        if e.operand.type is Type.F64:
            return v
        if e.operand.type in (Type.I64, Type.BOOL):
            return f"((double){v})"                     # round to nearest even, as CPython's int to float
        return self.bad("aot/type", "float", f"float() of a {e.operand.type.value} is outside the first AOT step")

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

    def ev_UnaryOp(self, e: ir.UnaryOp) -> str:
        t = e.operand.type
        if e.op is ir.UnaryOpKind.NOT and t is Type.BOOL:
            return f"(!{self.ev(e.operand)})"
        if e.op is ir.UnaryOpKind.POS and t in (Type.I64, Type.F64):
            return self.ev(e.operand)
        if e.op is ir.UnaryOpKind.NEG and t is Type.F64:
            return f"(-{self.ev(e.operand)})"
        if e.op is ir.UnaryOpKind.NEG and t is Type.I64:
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
        if lt is rt or (Type.BOOL in (lt, rt) and Type.F64 not in (lt, rt)):
            return f"({a} {op} {b})"
        code = _CMP_CODE[e.op]
        # an int against a float compares exactly (CPython's float_richcompare), a bool as the int 0 or 1
        if lt is Type.F64:
            return f"tpa_cmp_f64_i64({a}, (int64_t)({b}), {code})"
        return f"tpa_cmp_i64_f64((int64_t)({a}), {b}, {code})"

    def ev_BinOp(self, e: ir.BinOp) -> str:
        lt, rt = e.left.type, e.right.type
        k = e.op
        if e.np or e.inplace:
            return self.bad("aot/ir-node", "BinOp", "a numpy or in-place operation is outside the first AOT step")
        if lt is Type.F64 and rt is Type.F64 and e.type is Type.F64:
            a, b = self.ev(e.left), self.ev(e.right)
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
            self.ev(la), self.ev(ra)
            text = self.expr_text(e, ast.Div)
            what = f"`{text}`" if text else f"the `/` at line {self.line()}"
            return self.bad("aot/int-true-division", "/", f"the int true division {what} is not proved to have both "
                            "operands inside plus or minus 2**53, where the C division equals CPython's correctly "
                            "rounded quotient; big integers arrive in a later step")
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
        args = [self.ev(a) for a in e.args]
        if any(a.type is not Type.F64 for a in e.args):
            return self.bad("aot/type", e.func.value, "a math function takes floats")
        return self.fallible("double", f"tpa_math_{e.func.value}({', '.join(args)}, @)")

    def ev_Call(self, e: ir.Call) -> str:
        callee = self.g.fns.get(e.function)
        if e.function.startswith(PRINT_PREFIX) or callee is None or e.redo:
            return self.bad("aot/call", e.function, f"the call of `{e.function}` has no AOT form")
        args = [self.ev(a) for a in e.args]
        params = [p for p in callee.params]
        if len(params) != len(e.args) or any(not isinstance(p, ir.Param) or p.type is not a.type
                                             for p, a in zip(params, e.args)):
            return self.bad("aot/type", e.function, f"the arguments of `{e.function}` do not match its parameter types")
        name = f"tpf_{mangle(e.function)}"
        if callee.returns is Type.NONE:
            self.emit(f"if ({name}({', '.join(args)})) return -1;")
            return "0"
        t = self.tmp(CTYPE[callee.returns])
        self.emit(f"if ({name}({', '.join(args + ['&' + t])})) return -1;")
        return t

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
            self.bad("aot/ir-node", n, f"the IR statement {n} needs CPython objects or lists and has no AOT form in the first step")
            return
        m(s)

    def block(self, ss) -> None:
        self.depth += 1
        self.stmts(ss)
        self.depth -= 1

    def st_Assign(self, s: ir.Assign) -> None:
        want = self.types.get(s.target)
        if want is None or want is not s.value.type:
            self.bad("aot/type", s.target, f"`{s.target}` is assigned a {s.value.type.value} but is a "
                     f"{want.value if want else 'name the front end did not type'}")
            return
        self.ctype(want, s.target)
        self.emit(f"v_{mangle(s.target)} = {self.ev(s.value)};")

    def st_ExprStmt(self, s: ir.ExprStmt) -> None:
        v = s.value
        if isinstance(v, ir.Call) and v.function.startswith(PRINT_PREFIX):
            self.print_call(v)
            return
        self.ev(v)      # a fallible operation or a call has been emitted; what remains has no effect

    def print_call(self, v: ir.Call) -> None:
        site = self.g.program.prints.get(v.function)
        if site is None or len(site.template) != len(v.args):
            self.bad("aot/print-arg", "print", "this print call does not match the source")
            return
        parts: list[tuple[str, str | None]] = []
        for t, a in zip(site.template, v.args):
            if t is not None:
                parts.append(("str", t))
                continue
            if not (isinstance(a, ir.Box) and a.operand.type in CTYPE):
                self.bad("aot/print-arg", "print", "print takes ints, floats, bools and string literals only")
                return
            parts.append((CTYPE[a.operand.type], self.ev(a.operand)))
        for i, (kind, val) in enumerate(parts):
            if i:
                self.emit("tpa_print_sep();")
            if kind == "str":
                lit, n = _c_string(val)                              # type: ignore[arg-type]
                self.emit(f"tpa_print_str({lit}, {n});")
            elif kind == "int64_t":
                self.emit(f"tpa_print_i64({val});")
            elif kind == "double":
                self.emit(f"tpa_print_f64({val});")
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
        self.emit(f"        v_{mangle(s.var)} = (int64_t)((uint64_t){ts} {sign} {tk} * {mag});")
        self.depth += 2
        self.stmts(s.body)
        self.depth -= 2
        self.emit("    }")
        self.emit("}")

    def st_Return(self, s: ir.Return) -> None:
        if s.value is None:
            if self.f.returns is not Type.NONE:
                self.bad("aot/type", "return", "a bare return in a function that returns a value")
            self.emit("return 0;")
            return
        if s.value.type is Type.NONE or self.f.returns is Type.NONE:
            self.ev(s.value)
            self.emit("return 0;")
            return
        if s.value.type is not self.f.returns:
            self.bad("aot/type", "return", f"a {s.value.type.value} is returned from a function declared "
                     f"to return {self.f.returns.value}")
            return
        v = self.ev(s.value)
        self.emit(f"*out = {v};")
        self.emit("return 0;")

    def st_Break(self, s: ir.Break) -> None:
        self.emit("break;")

    def st_Continue(self, s: ir.Continue) -> None:
        self.emit("continue;")

    # -- the function

    def run(self) -> list[str] | None:
        f = self.f
        for p in f.params:
            if not isinstance(p, ir.Param) or p.type not in CTYPE or p.cls is not None:
                self.bad("aot/type", getattr(p, "name", "?"), f"the parameter `{getattr(p, 'name', '?')}` is not an "
                         "int, float or bool")
        if f.returns not in CTYPE and f.returns is not Type.NONE:
            self.bad("aot/type", f.name, f"the return type {f.returns.value} is outside the first AOT step")
        for p in f.entry_globals:
            self.bad("aot/global", p.name, f"the module global `{p.name}` is read at entry, which has no AOT form")
        for name, t in f.locals.items():
            self.ctype(t, name)
        self.stmts(f.body)
        if any(not noise for _, noise in self.found):
            self.found = [(x, n) for x, n in self.found if not n]
        elif self.found:
            self.found = self.found[:1]
        if self.found:
            return None
        decls = []
        for name, t in f.locals.items():
            decls.append(f"    {CTYPE[t]} v_{mangle(name)} TPA_UNUSED = 0;")
        for ct, n in self.temps:
            decls.append(f"    {ct} {n} TPA_UNUSED = 0;")
        tail = [] if self.lines and self.lines[-1] == "    return 0;" else ["    return 0;"]
        return [self.g.signature(f), "{", *decls, *self.lines, *tail, "}"]


# --- the module ----------------------------------------------------------------------------------

def emit(module: ir.Module, program: Program, firsts: list[int]) -> tuple[str | None, list[Finding]]:
    """The C text of the module and no findings, or None and the findings."""
    g = _Gen(module, program, firsts)
    bodies: list[list[str]] = []
    for f in g.fns.values():
        fn = _Fn(g, f)
        text = fn.run()
        g.findings.extend(x for x, _ in fn.found)
        if text is not None:
            bodies.append(text)
    if MAIN not in g.fns:
        return None, g.findings or [Finding(program.rel, 1, "error", None, "aot/entry", "__main__",
                                            "the `__main__` block was not compiled", None)]
    if g.findings:
        return None, g.findings
    out = [
        "/* Generated by typedpython.aot (SPEC N-17, #448). No interpreter header, no interpreter API. */",
        '#include "tp_aot.h"',
        "",
    ]
    out += [g.prototype(f) for f in g.fns.values()]
    out.append("")
    for b in bodies:
        out += [*b, ""]
    out += [
        "int main(void)",
        "{",
        "    int rc;",
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
