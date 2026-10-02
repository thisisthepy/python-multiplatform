"""`@typedpython.compiled` → Cython pure-Python mode → a CPython extension (design §4.3, stage 1).

The contract is **same results as CPython** (INTENT §1.4). C types are used only where that
holds:

- `float` locals and parameters become C `double` — the same IEEE double CPython uses.
- An `int` parameter becomes a C `long long` only after the wrapper has checked, *before the body
  runs*, that the argument fits; otherwise the call goes to the interpreted function. No effect has
  happened yet, so taking that path is always safe.
- `int` locals of a **pure** function (no stores outside its own locals, no calls except to pure
  builtins, `math`, and other pure compiled functions) are C `long long` with overflow checking.
  On overflow the whole call is redone by the interpreted function: since nothing outside the
  function was touched, redoing it is unobservable. That is how `int` keeps arbitrary precision.
- `int` locals of an impure function stay Python objects, except `range` loop indices whose bounds
  are themselves C-typed: an index never exceeds its bound, so it cannot overflow.
- An argument whose runtime type is not exactly the declared one (an `int` passed to a `float`
  parameter, a `bool` to an `int` one) also takes the interpreted path, since CPython would
  compute with the value it was given.

Everything else in the module is compiled by Cython as ordinary Python, which it supports
unchanged.
"""
import ast
import importlib.machinery
import importlib.util
import json
import subprocess
import sys
import tempfile
import textwrap
from dataclasses import dataclass, field
from pathlib import Path
from types import ModuleType

from typedpython import gate, pyrefly, rebinding

MARKER = "# typedpython: compiled"
PURE_BUILTINS = {"range", "len", "abs", "min", "max", "float", "int", "bool", "round", "divmod"}
C_TYPE = {"int": "cython.longlong", "float": "cython.double", "bool": "cython.bint", "list": "list"}
INTERPRETED_SUFFIX = "_typedpython_interpreted"


class CompileError(RuntimeError):
    pass


@dataclass
class Built:
    source: Path
    extension: Path
    typed_functions: list[str] = field(default_factory=list)

    def load(self) -> ModuleType:
        name = self.source.stem
        loader = importlib.machinery.ExtensionFileLoader(name, str(self.extension))
        spec = importlib.util.spec_from_file_location(name, str(self.extension), loader=loader)
        assert spec is not None
        module = importlib.util.module_from_spec(spec)
        loader.exec_module(module)
        return module

    def load_interpreted(self) -> ModuleType:
        return _load_source(self.source.stem + INTERPRETED_SUFFIX, self.source)


def compile_module(path: Path, out_dir: Path) -> Built:
    path = Path(path).resolve()
    out_dir = Path(out_dir).resolve()
    errors = [d for d in gate.check([path], mode="compiled") if d.severity == "error"]
    if errors:
        listing = "\n".join(f"{d.path}:{d.line}:{d.column}: {d.message} [{d.rule}]" for d in errors)
        raise CompileError(f"{path.name} does not pass the gate in compiled mode:\n{listing}")

    source = path.read_text()
    tree = ast.parse(source, filename=str(path))
    targets = _targets(tree, source)
    local_types = _local_types(path, source, tree, targets)

    plans = [_plan(fn, local_types.get(fn.name, {}), {t.name for t in targets}) for fn in targets]
    pure = _pure_fixpoint(plans)
    generated = _emit(source, tree, plans, pure)

    out_dir.mkdir(parents=True, exist_ok=True)
    interpreted = out_dir / (path.stem + INTERPRETED_SUFFIX + ".py")
    interpreted.write_text(source)
    extension = _build(path.stem, generated, out_dir)
    return Built(path, extension, [p.name for p in plans])


# --- what to compile -----------------------------------------------------------------------------

def _targets(tree: ast.Module, source: str) -> list[ast.FunctionDef]:
    first = next((line.strip() for line in source.splitlines() if line.strip()), "")
    whole_module = first == MARKER
    return [
        node for node in tree.body
        if isinstance(node, ast.FunctionDef)
        and (whole_module or any(_is_compiled_decorator(d) for d in node.decorator_list))
    ]


def _is_compiled_decorator(node: ast.expr) -> bool:
    return (isinstance(node, ast.Name) and node.id == "compiled") or (
        isinstance(node, ast.Attribute) and node.attr == "compiled"
        and isinstance(node.value, ast.Name) and node.value.id == "typedpython"
    )


def _local_types(
    path: Path, source: str, tree: ast.Module, targets: list[ast.FunctionDef],
) -> dict[str, dict[str, set[str]]]:
    """Inferred types of each target function's assigned locals, from Pyrefly (via probes)."""
    scopes = rebinding._scopes(tree)
    wanted_scopes = {scopes.index(fn) for fn in targets}
    wanted = [a for a in rebinding.assignments(tree) if a.scope in wanted_scopes]
    if not wanted:
        return {}
    probed, probes = rebinding.probe(source, wanted)
    with tempfile.TemporaryDirectory(prefix="typedpython-compile-") as work:
        copies = gate._mirror(Path(work), {str(path): probed})
        roots = pyrefly.import_roots([str(path)])
        [copy] = copies
        report = pyrefly.run([Path(copy)], [Path(work) / "0", Path(copy).parent, *roots])
        types = report.expression_types.get(copy, {})

    found: dict[str, dict[str, set[str]]] = {}
    for p in probes:
        spelled = types.get((p.line, p.column))
        if spelled is None or p.assignment is None:
            continue
        function = scopes[p.assignment.scope]
        assert isinstance(function, ast.FunctionDef)
        found.setdefault(function.name, {}).setdefault(p.name, set()).add(rebinding._base(spelled))
    return found


# --- planning ------------------------------------------------------------------------------------

@dataclass
class Plan:
    name: str
    node: ast.FunctionDef
    params: dict[str, str]         # parameter -> "int" | "float" | "bool"
    locals: dict[str, str]         # local -> "int" | "float" | "bool"
    safe_indices: set[str]         # int locals that are bounded range indices
    returns: str | None
    impure_reason: str | None
    calls: set[str]                # names of other module functions it calls
    float_lists: set[str] = field(default_factory=set)  # list[float] params unboxed inside


def _plan(fn: ast.FunctionDef, inferred: dict[str, set[str]], module_functions: set[str]) -> Plan:
    params = {
        a.arg: _scalar(a.annotation) for a in fn.args.args
        if _scalar(a.annotation) is not None
    }
    params = {k: v for k, v in params.items() if v is not None}
    annotated = {
        s.target.id: _scalar(s.annotation) for s in ast.walk(fn)
        if isinstance(s, ast.AnnAssign) and isinstance(s.target, ast.Name)
    }
    locals_: dict[str, str] = {k: v for k, v in annotated.items() if v is not None}
    for name, kinds in inferred.items():
        if name in params or name in locals_ or len(kinds) != 1:
            continue
        kind = next(iter(kinds))
        if kind in C_TYPE:
            locals_[name] = kind

    loop_targets: set[str] = set()
    for node in ast.walk(fn):
        if isinstance(node, ast.For) and isinstance(node.target, ast.Name) and _is_range(node.iter):
            loop_targets.add(node.target.id)
            locals_.setdefault(node.target.id, "int")
    # A loop target assigned anywhere else is not a plain index.
    reassigned = {
        t.id for node in ast.walk(fn) if isinstance(node, (ast.Assign, ast.AugAssign))
        for t in ast.walk(node) if isinstance(t, ast.Name) and isinstance(t.ctx, ast.Store)
    }
    c_ints = {k for k, v in {**params, **locals_}.items() if v == "int"}
    safe = {
        node.target.id for node in ast.walk(fn)
        if isinstance(node, ast.For) and isinstance(node.target, ast.Name)
        and node.target.id in loop_targets and node.target.id not in reassigned
        and _is_range(node.iter) and all(_bounded(arg, c_ints) for arg in node.iter.args)  # type: ignore[attr-defined]
    }

    impure, calls = _impurity(fn, module_functions)
    plan = Plan(fn.name, fn, params, locals_, safe, _scalar(fn.returns), impure, calls)
    plan.float_lists = _float_lists(fn, plan)
    return plan


def _float_lists(fn: ast.FunctionDef, plan: "Plan") -> set[str]:
    """`list[float]` parameters whose elements stay exact floats for the whole call.

    The wrapper checks at entry that every element is exactly a `float`. Inside, that stays true if
    the function hands the lists to nothing (no calls except pure builtins and `math`) and every
    value it stores into them is statically a float. Then each element load can be a C double.
    """
    candidates = {
        a.arg for a in fn.args.args
        if isinstance(a.annotation, ast.Subscript) and _is_list(a.annotation)
        and isinstance(a.annotation.slice, ast.Name) and a.annotation.slice.id == "float"
    }
    if not candidates or plan.calls:
        return set()
    if plan.impure_reason not in (None, "stores into an attribute or element"):
        return set()
    doubles = {k for k, v in {**plan.params, **plan.locals}.items() if v == "float"}
    for node in ast.walk(fn):
        if isinstance(node, (ast.Assign, ast.AugAssign)):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            for t in targets:
                if isinstance(t, ast.Subscript) and isinstance(t.value, ast.Name):
                    if t.value.id not in candidates:
                        return set()
                    value_is_float = _is_float(node.value, doubles, candidates) or (
                        isinstance(node, ast.AugAssign) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div))
                    )
                    if not value_is_float:
                        return set()
                elif isinstance(t, (ast.Subscript, ast.Attribute)):
                    return set()
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
                and isinstance(node.func.value, ast.Name) and node.func.value.id in candidates:
            return set()  # a method call (append, sort, ...) on the list
    # Every use of a candidate is `name[...]` or `len(name)`: it is never passed on whole.
    subscripted = {
        id(n.value) for n in ast.walk(fn)
        if isinstance(n, ast.Subscript) and isinstance(n.value, ast.Name)
    } | {  # len() reads the list without touching its elements
        id(n.args[0]) for n in ast.walk(fn)
        if isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "len"
        and len(n.args) == 1 and isinstance(n.args[0], ast.Name)
    }
    for n in ast.walk(fn):
        if isinstance(n, ast.Name) and n.id in candidates and id(n) not in subscripted:
            return set()
    return candidates


FLOAT_FUNCTIONS = {"sqrt", "exp", "log", "sin", "cos", "tan", "atan2", "hypot", "fabs", "pow"}


def _is_float(node: ast.expr, doubles: set[str], float_lists: set[str]) -> bool:
    """Statically a Python float (never an int or complex), by Python's arithmetic rules."""
    if isinstance(node, ast.Constant):
        return type(node.value) is float
    if isinstance(node, ast.Name):
        return node.id in doubles
    if isinstance(node, ast.Subscript) and isinstance(node.value, ast.Name):
        return node.value.id in float_lists
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd)):
        return _is_float(node.operand, doubles, float_lists)
    if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub, ast.Mult, ast.Div)):
        return _is_float(node.left, doubles, float_lists) or _is_float(node.right, doubles, float_lists)
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) \
            and isinstance(node.func.value, ast.Name) and node.func.value.id == "math":
        return node.func.attr in FLOAT_FUNCTIONS
    return False


def _scalar(annotation: ast.expr | None) -> str | None:
    if isinstance(annotation, ast.Name) and annotation.id in C_TYPE:
        return annotation.id
    if _is_list(annotation):
        return "list"
    return None


def _is_list(annotation: ast.expr | None) -> bool:
    """`list` or `list[...]`: typed as Cython's builtin list, so indexing is a direct load."""
    if isinstance(annotation, ast.Name):
        return annotation.id == "list"
    return isinstance(annotation, ast.Subscript) and isinstance(annotation.value, ast.Name) \
        and annotation.value.id == "list"


def _is_range(node: ast.expr) -> bool:
    return isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "range" \
        and not node.keywords and 1 <= len(node.args) <= 3


def _bounded(node: ast.expr, c_ints: set[str]) -> bool:
    """A range bound that is itself a checked C integer, a literal, or one of those plus/minus a literal."""
    if isinstance(node, ast.Constant) and type(node.value) is int:
        return -2**62 < node.value < 2**62
    if isinstance(node, ast.Name):
        return node.id in c_ints
    if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Sub)):
        return _bounded(node.left, c_ints) and isinstance(node.right, ast.Constant) \
            and type(node.right.value) is int and abs(node.right.value) < 2**30
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "len":
        return True
    return False


def _impurity(fn: ast.FunctionDef, module_functions: set[str]) -> tuple[str | None, set[str]]:
    calls: set[str] = set()
    for node in ast.walk(fn):
        if isinstance(node, (ast.Global, ast.Nonlocal)):
            return f"declares {type(node).__name__.lower()}", calls
        if isinstance(node, (ast.Yield, ast.YieldFrom, ast.Await)):
            return "is a generator or coroutine", calls
        if isinstance(node, (ast.Assign, ast.AugAssign, ast.AnnAssign, ast.Delete)):
            targets = node.targets if isinstance(node, (ast.Assign, ast.Delete)) else [node.target]
            for t in targets:
                if any(isinstance(n, (ast.Attribute, ast.Subscript)) for n in ast.walk(t)):
                    return "stores into an attribute or element", calls
        if isinstance(node, ast.Call):
            f = node.func
            if isinstance(f, ast.Name) and f.id in PURE_BUILTINS:
                continue
            if isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) and f.value.id == "math":
                continue
            if isinstance(f, ast.Name) and f.id in module_functions:
                calls.add(f.id)
                continue
            return f"calls `{ast.unparse(f)}`", calls
    return None, calls


def _pure_fixpoint(plans: list[Plan]) -> set[str]:
    pure = {p.name for p in plans if p.impure_reason is None}
    changed = True
    while changed:
        changed = False
        for p in plans:
            if p.name in pure and not p.calls <= pure:
                pure.discard(p.name)
                changed = True
    return pure


# --- emitting ------------------------------------------------------------------------------------

def _emit(source: str, tree: ast.Module, plans: list[Plan], pure: set[str]) -> str:
    lines = source.splitlines(keepends=True)
    stdlib_math = any(
        isinstance(n, ast.Import) and any(a.name == "math" and a.asname is None for a in n.names)
        for n in tree.body
    ) and not any(
        isinstance(n, ast.Name) and n.id == "math" and isinstance(n.ctx, ast.Store) for n in ast.walk(tree)
    )
    # Replace each target from the bottom up so earlier line numbers stay valid.
    for plan in sorted(plans, key=lambda p: p.node.lineno, reverse=True):
        start = (plan.node.decorator_list[0].lineno if plan.node.decorator_list else plan.node.lineno) - 1
        end = plan.node.end_lineno
        assert end is not None
        lines[start:end] = [_function(
            plan, plan.name in pure, "".join(lines[plan.node.lineno - 1:end]), stdlib_math,
        )]

    header = textwrap.dedent(f'''\
        import cython
        import importlib.util as _tp_importlib_util
        import os as _tp_os

        __typedpython_deopts__ = 0
        _tp_interpreted_module = None


        def _tp_interpreted():
            global _tp_interpreted_module
            if _tp_interpreted_module is None:
                here = _tp_os.path.dirname(__file__)
                stem = __name__.rsplit(".", 1)[-1]
                path = _tp_os.path.join(here, stem + "{INTERPRETED_SUFFIX}.py")
                spec = _tp_importlib_util.spec_from_file_location(stem + "{INTERPRETED_SUFFIX}", path)
                module = _tp_importlib_util.module_from_spec(spec)
                spec.loader.exec_module(module)
                _tp_interpreted_module = module
            return _tp_interpreted_module


        from cython.cimports.libc.math import sqrt as _tp_libc_sqrt


        @cython.cfunc
        @cython.inline
        @cython.locals(x=cython.double)
        @cython.returns(cython.double)
        @cython.exceptval(-1.0, check=True)
        def _tp_sqrt(x):
            # math.sqrt raises for negatives (NaN and inf pass through); C sqrt would return NaN.
            if x < 0.0:
                raise ValueError("math domain error")
            return _tp_libc_sqrt(x)


        def _tp_fallback(name, args):
            global __typedpython_deopts__
            __typedpython_deopts__ += 1
            return getattr(_tp_interpreted(), name)(*args)

    ''')
    return header + "".join(lines)


def _function(plan: Plan, is_pure: bool, original: str, stdlib_math: bool) -> str:
    """The C-typed body as `_tp_c_<name>` plus a Python-visible wrapper with the original name."""
    fn = plan.node
    names = [a.arg for a in fn.args.args]
    simple = not (fn.args.vararg or fn.args.kwarg or fn.args.kwonlyargs or fn.args.posonlyargs
                  or fn.args.defaults)
    if not simple:
        return original  # compiled by Cython as plain Python; typed handling needs a plain signature

    c_locals = dict(plan.params)
    for name, kind in plan.locals.items():
        if name in c_locals:
            continue
        if kind == "int" and not is_pure and name not in plan.safe_indices:
            continue  # stays a Python int: an impure function cannot be redone on overflow
        c_locals[name] = kind

    decorators = ["@cython.ccall"]
    if c_locals:
        decorators.append("@cython.locals(" + ", ".join(f"{k}={C_TYPE[v]}" for k, v in c_locals.items()) + ")")
    if plan.returns in C_TYPE:
        decorators.append(f"@cython.returns({C_TYPE[plan.returns]})")
    if is_pure:
        decorators.append("@cython.overflowcheck(True)")

    body = ast.parse(textwrap.dedent(original)).body[0]
    assert isinstance(body, ast.FunctionDef)
    body = _Unbox(plan.float_lists, stdlib_math).visit(body)
    ast.fix_missing_locations(body)
    body.name = f"_tp_c_{plan.name}"
    body.decorator_list = []
    for arg in body.args.args:
        arg.annotation = None
    body.returns = None
    c_function = "\n".join(decorators) + "\n" + ast.unparse(body) + "\n"

    guards = []
    for name, kind in plan.params.items():
        guards.append(f"type({name}) is not {kind}")
        if name in plan.float_lists:
            guards.append(f"not all(type(_tp_e) is float for _tp_e in {name})")
        if kind == "int":
            guards.append(f"not (-9223372036854775808 <= {name} <= 9223372036854775807)")
    args = ", ".join(names)
    call_args = f"({args},)" if len(names) == 1 else f"({args})"
    guard = " or ".join(guards) or "False"
    wrapper = [f"def {plan.name}({args}):"]
    wrapper.append(f"    if {guard}:")
    wrapper.append(f"        return _tp_fallback({plan.name!r}, {call_args})")
    if is_pure:
        wrapper.append("    try:")
        wrapper.append(f"        return _tp_c_{plan.name}({args})")
        wrapper.append("    except OverflowError:")
        wrapper.append(f"        return _tp_fallback({plan.name!r}, {call_args})")
    else:
        wrapper.append(f"    return _tp_c_{plan.name}({args})")
    return c_function + "\n\n" + "\n".join(wrapper) + "\n"


class _Unbox(ast.NodeTransformer):
    """Element loads of float-element lists become C doubles; `math.sqrt` becomes C `sqrt`."""

    def __init__(self, float_lists: set[str], stdlib_math: bool) -> None:
        self.float_lists = float_lists
        self.stdlib_math = stdlib_math

    def visit_AugAssign(self, node: ast.AugAssign) -> ast.stmt:
        node = self.generic_visit(node)  # type: ignore[assignment]
        t = node.target
        if isinstance(t, ast.Subscript) and isinstance(t.value, ast.Name) and t.value.id in self.float_lists:
            load = ast.Subscript(ast.Name(t.value.id, ast.Load()), t.slice, ast.Load())
            # floats are immutable, so `a[i] -= e` is exactly `a[i] = a[i] - e`
            return ast.Assign([t], ast.BinOp(self._double(load), node.op, node.value))
        return node

    def visit_Subscript(self, node: ast.Subscript) -> ast.expr:
        node = self.generic_visit(node)  # type: ignore[assignment]
        if isinstance(node.ctx, ast.Load) and isinstance(node.value, ast.Name) \
                and node.value.id in self.float_lists:
            return self._double(node)
        return node

    def visit_Call(self, node: ast.Call) -> ast.expr:
        node = self.generic_visit(node)  # type: ignore[assignment]
        f = node.func
        if self.stdlib_math and isinstance(f, ast.Attribute) and isinstance(f.value, ast.Name) \
                and f.value.id == "math" and f.attr == "sqrt" and len(node.args) == 1 and not node.keywords:
            return ast.Call(ast.Name("_tp_sqrt", ast.Load()), node.args, [])
        return node

    @staticmethod
    def _double(node: ast.expr) -> ast.expr:
        return ast.Call(
            ast.Attribute(ast.Name("cython", ast.Load()), "cast", ast.Load()),
            [ast.Attribute(ast.Name("cython", ast.Load()), "double", ast.Load()), node], [],
        )


# --- building ------------------------------------------------------------------------------------

BUILD_SCRIPT = """
import json, sys
from setuptools import Distribution, Extension
from Cython.Build import cythonize

cfg = json.loads(sys.argv[1])
extensions = cythonize(
    [Extension(cfg["name"], [cfg["source"]], extra_compile_args=["-O3"])],
    build_dir=cfg["build"], quiet=True, language_level=3,
    compiler_directives={"annotation_typing": False, "binding": False},
)
dist = Distribution({"ext_modules": extensions})
cmd = dist.get_command_obj("build_ext")
cmd.build_lib = cfg["out"]
cmd.build_temp = cfg["build"] + "/tmp"
cmd.ensure_finalized()
cmd.run()
"""


def _build(name: str, generated: str, out_dir: Path) -> Path:
    build = out_dir / "build"
    build.mkdir(parents=True, exist_ok=True)
    source = build / f"{name}.py"
    source.write_text(generated)
    cfg = json.dumps({"name": name, "source": str(source), "build": str(build), "out": str(out_dir)})
    result = subprocess.run([sys.executable, "-c", BUILD_SCRIPT, cfg], capture_output=True, text=True)
    if result.returncode != 0:
        raise CompileError(f"Cython build of {name} failed:\n{result.stdout}\n{result.stderr}")
    built = sorted(out_dir.glob(f"{name}.*.so")) + sorted(out_dir.glob(f"{name}.*.pyd"))
    if not built:
        raise CompileError(f"Cython build of {name} produced no extension:\n{result.stdout}")
    return built[0]


def _load_source(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, str(path))
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
