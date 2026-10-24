"""TypedPython: methods of fixed-layout classes compile (SPEC N-11, issue #155).

A plain instance method of an N-11 class (`def m(self, ...)` in the class body: no decorator, not a
dunder, `self` unannotated or annotated with the class) is an IR function named `Class.method`. The
module init replaces the class dict's function by a descriptor object of a type the extension defines;
the original function stays in `__typedpython_interpreted__["Class.method"]`.

What is shown here, every behavioural test against the interpreted class (the same source loaded as an
ordinary module):

  * frontend: which methods are lowered, which stay interpreted and why;
  * results of compiled methods (pure, impure, recursive through methods) equal the interpreter's,
    through `obj.m(...)`, `C.m(obj, ...)`, `getattr`, bound methods and `functools.partial`;
  * `C.m(5)`, keywords and a wrong arity run the ORIGINAL function (CPython's own errors);
  * a subclass instance and an overriding subclass take the interpreted path with correct results;
  * rebinding or deleting `C.m` after import, `obj.__class__` assignment: same outcomes as CPython;
  * the attributes the descriptor shows, and the observable differences from a function;
  * reference counts stay flat, concurrent calls on shared and private instances (free-threaded);
  * mutation: a `self` guard that is always true is caught;
  * the binary_trees benchmark with `check` as a method of `Node` compiles and matches.

Scenarios that change class state run in a CHILD process on a fresh instance of the compiled module,
next to the plain-source module, and the two outcome lists are compared.

Ordering of the red/green record: this file was written before the implementation; with methods
still interpreted every compiled-behaviour test failed (see the report of issue #155).
"""
from __future__ import annotations

import functools
import gc
import inspect
import json
import shutil
import subprocess
import sys
import textwrap
import types
from pathlib import Path

import pytest

from typedpython import cbuild, cgen, cost, frontend, pipeline, verify

HERE = Path(__file__).resolve().parent
import os  # noqa: E402

# the package the child processes import (the red run of the issue points it at a pristine copy)
MAIN_PYTHON = Path(os.environ.get("TP_MAIN_PYTHON", HERE.parents[1] / "main" / "python"))
REPO = HERE.parents[3]
BINARY_TREES = REPO / "benchmarks" / "typedpython" / "py" / "binary_trees.py"

SOURCE = textwrap.dedent('''\
    # typedpython: compiled
    """typedpython class-method test module."""


    class Node:
        __slots__ = ("left", "right")
        left: "Node | None"
        right: "Node | None"

        def __init__(self, left: "Node | None", right: "Node | None") -> None:
            self.left = left
            self.right = right

        def check(self) -> int:
            """Count the nodes."""
            left: Node | None = self.left
            right: Node | None = self.right
            if left is None or right is None:
                return 1
            return 1 + left.check() + right.check()

        def leaf(self: "Node") -> bool:
            return self.left is None and self.right is None

        def height(self) -> int:
            h: int = 0
            left: Node | None = self.left
            if left is not None:
                h = 1 + left.height()
            return h

        def scaled(self, k: float) -> float:
            return k * 2.0

        def tag(self) -> int:
            return 7

        def sum_to(self, n: int) -> int:
            total: int = 0
            for i in range(n):
                total += i
            return total

        def set_left(self, v: "Node | None") -> None:
            self.left = v

        def swap(self) -> None:
            a: Node | None = self.left
            b: Node | None = self.right
            self.left = b
            self.right = a

        def check_swapped(self) -> object:
            self.swap()
            total: object = self.check()
            self.swap()
            return total

        def after(self, cb: object) -> object:
            cb()
            return self.left

        @staticmethod
        def st(x: int) -> int:
            return x + 1

        @classmethod
        def cm(cls) -> int:
            return 1

        @property
        def size(self) -> int:
            return self.check()

        def __repr__(self) -> str:
            return "Node()"

        def uses_super(self) -> str:
            return str(super().__repr__())

        def no_annotation(self, x) -> int:
            return 1


    class Plain:
        def check(self) -> int:
            return 5


    def make(depth: int) -> Node:
        if depth > 0:
            return Node(make(depth - 1), make(depth - 1))
        return Node(None, None)


    def check_of(n: Node) -> int:
        return n.check()


    def leaf_of(n: Node) -> bool:
        return n.leaf()
''')

COMPILED_METHODS = {"Node.check", "Node.leaf", "Node.height", "Node.scaled", "Node.tag", "Node.sum_to",
                    "Node.set_left", "Node.swap", "Node.check_swapped", "Node.after"}


def build_source(d: Path, source: str, name: str = "methmod", runtime_dir=None):
    path = d / f"{name}.py"
    path.write_text(source)
    proved, diags = verify.verify(frontend.lower(path))
    proved, diags = cost.apply(proved, diags)
    c = cgen.generate(proved, path, path.name)
    so = cbuild.build(c, path.stem, d / "out", runtime_dir=runtime_dir)
    return proved, diags, so


@pytest.fixture(scope="module")
def built(tmp_path_factory):
    d = tmp_path_factory.mktemp("methmod")
    proved, diags, so = build_source(d, SOURCE)
    ref = d / "refmod.py"
    ref.write_text(SOURCE)
    return types.SimpleNamespace(proved=proved, diags=diags, so=so, ref=ref, dir=d,
                                 module=cbuild.load("methmod", so))


def compiled_names(proved):
    return {f.name for f in proved.functions}


def deopts(m):
    return m.__typedpython_deopts__


# --- the front end -------------------------------------------------------------------------------

def lower(tmp_path, source):
    path = tmp_path / "lowermod.py"
    path.write_text(source)
    return frontend.lower(path)


def test_the_plain_instance_methods_are_lowered_with_qualified_names(tmp_path):
    m = lower(tmp_path, SOURCE)
    names = {f.name for f in m.functions}
    assert COMPILED_METHODS <= names, (COMPILED_METHODS - names, m.skipped)
    check = next(f for f in m.functions if f.name == "Node.check")
    self_param = check.params[0]
    assert (self_param.name, self_param.type.value, self_param.cls, self_param.optional) == \
        ("self", "obj", "Node", False)
    assert check.owner == "Node"
    assert next(f for f in m.functions if f.name == "make").owner is None


@pytest.mark.parametrize("method, why", [
    ("Node.st", "decorator"),
    ("Node.cm", "decorator"),
    ("Node.size", "decorator"),
    ("Node.__repr__", "dunder"),
    ("Node.__init__", "dunder"),
    ("Node.uses_super", "super"),
    ("Node.no_annotation", "no annotation"),
])
def test_other_methods_stay_interpreted_with_a_reason(tmp_path, method, why):
    m = lower(tmp_path, SOURCE)
    assert method not in {f.name for f in m.functions}
    assert why in m.skipped[method], m.skipped.get(method)


def test_the_methods_of_a_class_that_is_not_fixed_layout_are_not_considered(tmp_path):
    m = lower(tmp_path, SOURCE)
    assert "Plain.check" not in {f.name for f in m.functions}
    assert "Plain.check" not in m.skipped          # the class has its own entry
    assert "Plain" in m.skipped


def test_a_method_named_like_a_module_function_does_not_collide(tmp_path):
    m = lower(tmp_path, SOURCE + textwrap.dedent('''\

        def height(n: Node) -> int:
            return n.height()
    '''))
    names = {f.name for f in m.functions}
    assert {"height", "Node.height"} <= names


def test_a_method_with_a_foreign_self_annotation_stays_interpreted(tmp_path):
    src = textwrap.dedent('''\
        # typedpython: compiled
        class A:
            __slots__ = ("x",)
            x: int

            def __init__(self, x: int) -> None:
                self.x = x

            def get(self: int) -> int:
                return 1
    ''')
    m = lower(tmp_path, src)
    assert "A.get" not in {f.name for f in m.functions}
    assert "self" in m.skipped["A.get"]


def test_a_method_defined_twice_in_the_class_body_stays_interpreted(tmp_path):
    src = textwrap.dedent('''\
        # typedpython: compiled
        class A:
            __slots__ = ("x",)
            x: int

            def __init__(self, x: int) -> None:
                self.x = x

            def get(self) -> int:
                return 1

            def get(self) -> int:
                return 2
    ''')
    m = lower(tmp_path, src)
    assert "A.get" not in {f.name for f in m.functions}
    assert "more than once" in m.skipped["A.get"]


def test_an_at_compiled_class_compiles_its_methods_without_the_module_marker(tmp_path):
    src = textwrap.dedent('''\
        @compiled
        class A:
            __slots__ = ("x",)
            x: int

            def __init__(self, x: int) -> None:
                self.x = x

            def get(self) -> int:
                return 1
    ''')
    m = lower(tmp_path, src)
    assert "A.get" in {f.name for f in m.functions}


def test_the_compiled_set_is_the_methods_and_the_functions(built):
    assert compiled_names(built.proved) >= COMPILED_METHODS | {"make", "check_of", "leaf_of"}
    assert not (compiled_names(built.proved) & {"Node.st", "Node.cm", "Node.size", "Node.__repr__",
                                                 "Node.__init__", "Node.uses_super"})


def test_a_method_calling_a_method_is_a_direct_c_call(built):
    check = next(f for f in built.proved.functions if f.name == "Node.check")
    from typedpython import ir
    calls = []
    stack = [check.body]
    while stack:
        x = stack.pop()
        if isinstance(x, (tuple, list)):
            stack.extend(x)
        elif hasattr(x, "__dataclass_fields__"):
            if isinstance(x, ir.Call):
                calls.append(x.function)
            stack.extend(getattr(x, f) for f in x.__dataclass_fields__)
    assert calls.count("Node.check") == 2, calls
    assert check.pure


# --- the installed descriptor --------------------------------------------------------------------

def node(m, depth=3):
    return m.make(depth)


def test_the_class_dict_holds_a_descriptor_not_a_function(built):
    m = built.module
    entry = m.Node.__dict__["check"]
    assert not isinstance(entry, types.FunctionType)
    assert entry is m.Node.check                       # a function-like descriptor: C.m is itself
    orig = m.__typedpython_interpreted__["Node.check"]
    assert isinstance(orig, types.FunctionType)
    assert entry is not orig


def test_methods_that_stay_interpreted_are_still_functions(built):
    m = built.module
    assert isinstance(m.Node.__dict__["uses_super"], types.FunctionType)
    assert isinstance(m.Node.__dict__["__init__"], types.FunctionType)
    assert isinstance(m.Node.__dict__["st"], staticmethod)
    assert isinstance(m.Node.__dict__["cm"], classmethod)
    assert isinstance(m.Node.__dict__["size"], property)


def test_an_instance_attribute_is_a_real_bound_method(built):
    m = built.module
    n = node(m)
    b = n.check
    assert type(b) is types.MethodType
    assert b.__self__ is n
    assert b.__func__ is m.Node.check
    assert b() == m.__typedpython_interpreted__["Node.check"](n)
    assert "Node.check" in repr(b)
    assert b == n.check and hash(b) == hash(n.check)


def test_the_forwarded_attributes_equal_the_originals(built):
    m = built.module
    orig = m.__typedpython_interpreted__["Node.check"]
    d = m.Node.check
    assert d.__name__ == orig.__name__ == "check"
    assert d.__qualname__ == orig.__qualname__ == "Node.check"
    assert d.__doc__ == orig.__doc__ == "Count the nodes."
    assert d.__module__ == orig.__module__
    assert d.__wrapped__ is orig
    n = node(m)
    assert n.check.__name__ == "check" and n.check.__doc__ == "Count the nodes."
    assert n.check.__qualname__ == "Node.check" and n.check.__wrapped__ is orig
    assert m.Node.leaf.__doc__ is None
    assert inspect.signature(m.Node.check) == inspect.signature(orig)
    assert str(inspect.signature(n.check)) == "() -> int"
    assert "check" in repr(d)


def test_the_attributes_that_are_not_forwarded_are_absent(built):
    d = built.module.Node.check
    for name in ("__code__", "__globals__", "__defaults__", "__kwdefaults__", "__annotations__",
                 "__closure__", "__dict__", "__type_params__"):
        assert not hasattr(d, name), name


def test_a_bound_method_reduces_like_one_of_a_function(built):
    m = built.module
    n = node(m, 1)
    assert n.check.__reduce__() == (getattr, (n, "check"))
    assert callable(m.Node.check) and not inspect.isfunction(m.Node.check)
    assert inspect.ismethod(n.check)


def test_the_descriptor_is_immutable(built):
    d = built.module.Node.check
    with pytest.raises(AttributeError):
        d.__name__ = "other"
    with pytest.raises(AttributeError):
        d.extra = 1
    with pytest.raises(TypeError):
        type(d).extra = 1
    with pytest.raises(TypeError):
        type(d)()


def test_the_descriptor_type_is_one_type_shared_by_all_methods(built):
    m = built.module
    assert type(m.Node.check) is type(m.Node.leaf) is type(m.Node.set_left)


def test_module_functions_expose_less_than_methods_do(built):
    """Recorded difference (API.md): a module-level compiled function is a builtin function and has no
    `__wrapped__`; a method forwards `__doc__`, `__wrapped__` ... from the original function."""
    m = built.module
    assert not hasattr(m.make, "__wrapped__")
    assert hasattr(m.Node.check, "__wrapped__")


# --- behaviour ------------------------------------------------------------------------------------

def test_pure_recursive_methods_agree_with_the_interpreter(built):
    m = built.module
    interp = m.__typedpython_interpreted__
    before = deopts(m)
    for depth in range(0, 9):
        n = node(m, depth)
        assert n.check() == interp["Node.check"](n) == (2 ** (depth + 1) - 1)
        assert n.height() == interp["Node.height"](n) == depth
        assert n.leaf() is interp["Node.leaf"](n)
        assert m.check_of(n) == n.check()
        assert m.leaf_of(n) is n.leaf()
    assert deopts(m) == before, "a compiled method fell back to the interpreter"


def test_scalar_and_loop_methods(built):
    m = built.module
    n = node(m, 1)
    before = deopts(m)
    assert n.scaled(1.5) == 3.0 and type(n.scaled(1.5)) is float
    assert n.sum_to(10) == 45 and n.sum_to(0) == 0
    assert deopts(m) == before
    assert n.scaled(2) == 4.0             # an int where a float was annotated: CPython's result
    assert n.sum_to(10 ** 6) == sum(range(10 ** 6))


def test_impure_methods_write_fields_like_the_interpreter(built):
    m = built.module
    before = deopts(m)
    a, b = m.Node(None, None), m.Node(None, None)
    top = m.Node(a, b)
    top.swap()
    assert top.left is b and top.right is a
    top.set_left(None)
    assert top.left is None
    assert top.check_swapped() == 1
    assert top.left is None and top.right is a
    assert deopts(m) == before


def test_the_unbound_forms_and_wrappers(built):
    m = built.module
    n = node(m, 4)
    expected = m.__typedpython_interpreted__["Node.check"](n)
    assert m.Node.check(n) == expected
    assert getattr(n, "check")() == expected
    assert functools.partial(n.check)() == expected
    assert functools.partial(m.Node.check, n)() == expected
    assert functools.partial(m.Node.scaled, n)(2.0) == 4.0
    assert [f() for f in map(lambda x: x.check, [n, n])] == [expected, expected]
    assert (lambda f: f(n))(m.Node.check) == expected


def test_a_foreign_self_runs_the_original_function(built):
    m = built.module
    before = deopts(m)
    with pytest.raises(AttributeError) as compiled_error:
        m.Node.check(5)
    interp = m.__typedpython_interpreted__["Node.check"]
    with pytest.raises(AttributeError) as original_error:
        interp(5)
    assert repr(compiled_error.value) == repr(original_error.value)
    assert deopts(m) == before + 1
    assert m.Node.sum_to(5, 4) == 6             # CPython: the original never touched self


def test_arity_and_keywords_are_the_originals(built):
    m = built.module
    n = node(m, 2)
    interp = m.__typedpython_interpreted__["Node.check"]
    for call in (lambda f: f(n, 1), lambda f: f(), lambda f: f(self=n), lambda f: f(n, x=1)):
        outcomes = []
        for f in (m.Node.check, interp):
            try:
                outcomes.append(("ok", call(f)))
            except TypeError as e:
                outcomes.append(("raise", str(e)))
        assert outcomes[0] == outcomes[1], outcomes
    with pytest.raises(TypeError, match="takes 1 positional argument but 2 were given"):
        n.check(1)


def test_a_subclass_instance_takes_the_interpreted_path(built):
    m = built.module

    class Sub(m.Node):
        pass

    s = Sub(m.Node(None, None), None)
    before = deopts(m)
    assert s.check() == 1
    assert deopts(m) == before + 1
    t = Sub(m.Node(None, None), m.Node(None, None))
    assert t.check() == 3
    assert m.Node.check(t) == 3
    assert m.check_of(t) == 3


def test_an_overriding_subclass_and_a_mixed_tree(built):
    m = built.module

    class Over(m.Node):
        def check(self):
            return -1000

    class Up(m.Node):
        def check(self):
            return 100 + super().check()

    assert Over(None, None).check() == -1000
    assert Up(m.Node(None, None), m.Node(None, None)).check() == 103
    tree = m.Node(Over(None, None), m.Node(None, None))
    interp = m.__typedpython_interpreted__["Node.check"]
    assert tree.check() == interp(tree) == 1 + (-1000) + 1
    assert m.check_of(tree) == -998
    assert m.Node(Up(m.Node(None, None), m.Node(None, None)), m.Node(None, None)).check() == 105


def test_c_to_c_calls_run_without_deopts(built):
    m = built.module
    n = node(m, 10)
    before = deopts(m)
    assert n.check() == 2 ** 11 - 1
    assert m.check_of(n) == 2 ** 11 - 1
    assert deopts(m) == before


def test_class_info_reports_the_methods(built):
    info = built.module.__typedpython_class_info__()["Node"]
    assert info["compiled"] is True and info["current"] is True


# --- class state changed after import: child processes --------------------------------------------

CHILD = '''
import json, sys
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
sys.path.insert(0, {main!r})
from typedpython import cbuild

comp = cbuild.load("methmod", Path({so!r}))
spec = spec_from_file_location("refmod", {ref!r})
ref = module_from_spec(spec)
spec.loader.exec_module(ref)


def outcome(f):
    try:
        return ["ok", repr(f())]
    except BaseException as e:
        return ["raise", type(e).__name__, str(e)]

{scenario}

out_c = scenario(comp)
out_r = scenario(ref)
print(json.dumps({{"comp": out_c, "ref": out_r, "deopts": comp.__typedpython_deopts__}}))
'''


def run_scenario(built, scenario: str) -> dict:
    script = CHILD.format(main=str(MAIN_PYTHON), so=str(built.so), ref=str(built.ref),
                          scenario=textwrap.dedent(scenario))
    run = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=300)
    assert run.returncode == 0, run.stderr
    return json.loads(run.stdout.strip().splitlines()[-1])


def same(built, scenario: str) -> dict:
    out = run_scenario(built, scenario)
    assert out["comp"] == out["ref"], out
    return out


def test_rebinding_a_method_after_import(built):
    out = same(built, '''
        def scenario(m):
            n = m.make(3)
            res = [outcome(lambda: m.check_of(n)), outcome(lambda: n.check())]
            m.Node.check = lambda self: 99
            res += [outcome(lambda: n.check()), outcome(lambda: m.check_of(n)),
                    outcome(lambda: n.height()), outcome(lambda: m.Node.check(n))]
            m.Node.check = property(lambda self: 7)
            res += [outcome(lambda: n.check), outcome(lambda: m.check_of(n))]
            del m.Node.check
            res += [outcome(lambda: n.check), outcome(lambda: m.check_of(n)),
                    outcome(lambda: n.height())]
            return res
    ''')
    assert out["comp"][2] == ["ok", "99"]


def test_rebinding_a_callee_method_that_a_caller_method_calls(built):
    same(built, '''
        def scenario(m):
            n = m.Node(m.Node(None, None), m.Node(None, None))
            res = [outcome(lambda: n.check_swapped()), outcome(lambda: n.height())]
            m.Node.swap = lambda self: None
            res += [outcome(lambda: n.check_swapped()), outcome(lambda: n.height())]
            m.Node.check = lambda self: 5
            res += [outcome(lambda: n.check_swapped()), outcome(lambda: n.height()),
                    outcome(lambda: m.check_of(n))]
            return res
    ''')


def test_rebinding_the_method_through_the_class_dict_of_a_subclass_chain(built):
    same(built, '''
        def scenario(m):
            class Sub(m.Node):
                pass
            s = Sub(m.Node(None, None), m.Node(None, None))
            res = [outcome(lambda: s.check()), outcome(lambda: m.check_of(s))]
            m.Node.check = lambda self: 11
            res += [outcome(lambda: s.check()), outcome(lambda: m.check_of(s)),
                    outcome(lambda: m.Node.check(s))]
            return res
    ''')


def test_instance_class_assignment(built):
    same(built, '''
        def scenario(m):
            class Twin:
                __slots__ = ("left", "right")
                def check(self):
                    return 4242
            n = m.make(2)
            res = [outcome(lambda: n.check())]
            n.__class__ = m.Node
            c = m.Node(m.Node(None, None), m.Node(None, None))
            c.left.__class__ = Twin
            res += [outcome(lambda: c.check()), outcome(lambda: m.check_of(c)),
                    outcome(lambda: m.Node.check(c.left)), outcome(lambda: c.height())]
            return res
    ''')


def test_unrelated_selves_and_none_children(built):
    same(built, '''
        class Fake:
            left = None
            right = None

        def scenario(m):
            f = Fake()
            n = m.Node(None, m.Node(None, None))
            return [outcome(lambda: m.Node.check(f)), outcome(lambda: m.Node.leaf(f)),
                    outcome(lambda: m.Node.height(None)), outcome(lambda: m.Node.scaled(f, 1.0)),
                    outcome(lambda: m.Node.set_left(f, 1)), outcome(lambda: m.Node.check(n)),
                    outcome(lambda: m.Node.after(n, lambda: 3)),
                    outcome(lambda: m.Node.set_left(n, 5)), outcome(lambda: n.left),
                    outcome(lambda: n.check()), outcome(lambda: m.check_of(n))]
    ''')


def test_a_callback_that_rebinds_the_method_mid_body(built):
    same(built, '''
        def scenario(m):
            n = m.Node(m.Node(None, None), None)
            def cb():
                m.Node.check = lambda self: 77
            res = [outcome(lambda: n.after(cb)), outcome(lambda: n.check()),
                   outcome(lambda: m.check_of(n))]
            return res
    ''')


def test_deep_recursion_raises_what_cpython_raises(built):
    out = run_scenario(built, '''
        def scenario(m):
            n = m.Node(None, None)
            for _ in range(5000):
                n = m.Node(n, m.Node(None, None))
            return [outcome(lambda: n.check()), outcome(lambda: n.height())]
    ''')
    assert out["comp"][0][0] == out["ref"][0][0]
    assert out["comp"] == out["ref"] or out["comp"][0][1] == out["ref"][0][1]


# --- reference counts -----------------------------------------------------------------------------

def test_reference_counts_are_flat_over_many_calls(built):
    m = built.module
    n = node(m, 5)
    other = m.Node(None, None)
    desc = m.Node.__dict__["check"]
    gc.collect()
    base = (sys.getrefcount(n), sys.getrefcount(other), sys.getrefcount(desc),
            sys.getrefcount(m.Node), sys.getrefcount(m.__typedpython_interpreted__["Node.check"]))
    for _ in range(2000):
        n.check()
        m.Node.check(n)
        n.height()
        m.check_of(n)
        n.leaf()
        n.scaled(1.0)
        n.set_left(n.left)
        n.swap()
        n.swap()
        other.set_left(None)
        n.check_swapped()
        with pytest.raises(AttributeError):
            m.Node.check(5)
        b = n.check
        b()
        functools.partial(m.Node.check, n)()
    del b                       # the last bound method holds n and the function
    gc.collect()
    after = (sys.getrefcount(n), sys.getrefcount(other), sys.getrefcount(desc),
             sys.getrefcount(m.Node), sys.getrefcount(m.__typedpython_interpreted__["Node.check"]))
    assert after == base, (base, after)


# --- free-threaded concurrency --------------------------------------------------------------------

THREADS = '''
import sys, threading
from pathlib import Path
sys.path.insert(0, {main!r})
from typedpython import cbuild
m = cbuild.load("methmod", Path({so!r}))
shared = m.make(6)
errors = []
start = threading.Barrier(8)

def reader():
    try:
        start.wait()
        for _ in range(300):
            assert shared.check() == 127
            assert m.check_of(shared) == 127
            assert shared.height() == 6
    except BaseException as e:
        errors.append(repr(e))

def private():
    try:
        mine = m.make(4)
        start.wait()
        for _ in range(300):
            mine.swap()
            assert mine.check_swapped() == 31
            mine.set_left(mine.left)
            assert mine.check() == 31
    except BaseException as e:
        errors.append(repr(e))

def toggler():
    try:
        a, b = m.Node(None, None), m.Node(None, None)
        top = m.Node(a, b)
        box = m.Node(top, None)
        start.wait()
        for i in range(300):
            top.set_left(None if i % 2 else a)
            r = top.check()
            assert r in (1, 3), r
    except BaseException as e:
        errors.append(repr(e))

threads = [threading.Thread(target=f) for f in [reader] * 4 + [private] * 3 + [toggler]]
for t in threads: t.start()
for t in threads: t.join()
print("GIL", sys._is_gil_enabled() if hasattr(sys, "_is_gil_enabled") else "n/a")
print("ERRORS", errors)
'''


def test_concurrent_calls_on_shared_and_private_instances(built):
    script = THREADS.format(main=str(MAIN_PYTHON), so=str(built.so))
    run = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=300)
    assert run.returncode == 0, run.stderr
    assert "ERRORS []" in run.stdout, run.stdout
    if hasattr(sys, "_is_gil_enabled"):
        assert "GIL False" in run.stdout, run.stdout       # the module keeps the GIL off


# --- mutation: the exact-type guard of `self` -------------------------------------------------------

GUARD_PROBE = '''
import sys
from pathlib import Path
sys.path.insert(0, {main!r})
from typedpython import cbuild
m = cbuild.load("methmod", Path({so!r}))

class Sub(m.Node):
    pass

s = Sub(m.Node(None, None), m.Node(None, None))
before = m.__typedpython_deopts__
results = [s.check(), m.Node.leaf(s), m.Node.height(s), m.Node.tag(s)]
print("DEOPTS", m.__typedpython_deopts__ - before, results)
'''


def probe(so) -> str:
    script = GUARD_PROBE.format(main=str(MAIN_PYTHON), so=str(so))
    run = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True, timeout=300)
    assert run.returncode == 0, run.stderr
    return run.stdout.strip().splitlines()[-1]


def test_the_self_guard_is_one_mutation_point():
    header = (cbuild.RUNTIME_DIR / "tp_runtime.h").read_text()
    assert "#define TP_SELF_EXACT(o, cls) tp_is_exact(o, cls)" in header


def test_a_self_guard_that_is_always_true_is_caught(built, tmp_path):
    good = probe(built.so)
    # a subclass instance: four interpreted runs (the guard of `self` decides; `tag` touches no field,
    # so nothing else would send it to the interpreter)
    assert good == "DEOPTS 4 [3, False, 1, 7]", good
    mutant = tmp_path / "runtime"
    shutil.copytree(cbuild.RUNTIME_DIR, mutant)
    text = (mutant / "tp_runtime.h").read_text()
    line = "#define TP_SELF_EXACT(o, cls) tp_is_exact(o, cls)"
    (mutant / "tp_runtime.h").write_text(text.replace(line, "#define TP_SELF_EXACT(o, cls) (1)"))
    (tmp_path / "src").mkdir()
    _, _, so = build_source(tmp_path / "src", SOURCE, runtime_dir=mutant)
    bad = probe(so)
    assert bad != good, "the mutant (self guard always true) was not caught"


# --- the binary_trees benchmark with `check` as a method --------------------------------------------

def method_form(text: str) -> str:
    text = text.replace('''    def __init__(self, left: "Node | None", right: "Node | None") -> None:
        self.left = left
        self.right = right
''', '''    def __init__(self, left: "Node | None", right: "Node | None") -> None:
        self.left = left
        self.right = right

    def check(self) -> int:
        left: Node | None = self.left
        right: Node | None = self.right
        if left is None or right is None:
            return 1
        return 1 + left.check() + right.check()
''')
    text = text.replace('''def check(node: Node) -> int:
    left: Node | None = node.left
    right: Node | None = node.right
    if left is None or right is None:
        return 1
    return 1 + check(left) + check(right)


''', "")
    text = text.replace("check(make(max_depth + 1))", "make(max_depth + 1).check()")
    text = text.replace("check(make(depth))", "make(depth).check()")
    text = text.replace("check(long_lived)", "long_lived.check()")
    assert "def check(node" not in text and "check(make" not in text
    return text


def run_main(path_or_module, n):
    import contextlib
    import io
    old = sys.argv
    sys.argv = ["binary_trees", str(n)]
    buf = io.StringIO()
    try:
        with contextlib.redirect_stdout(buf):
            path_or_module.main()
    finally:
        sys.argv = old
    return buf.getvalue()


def test_binary_trees_with_check_as_a_method_compiles_and_matches(tmp_path):
    text = "# typedpython: compiled\n" + method_form(BINARY_TREES.read_text())
    path = tmp_path / "bt_method.py"
    path.write_text(text)
    result = pipeline.compile_module(path, tmp_path / "out")
    names = {f.name for f in result.module.functions}
    assert {"Node.check", "make"} <= names, (names, result.skipped)
    assert result.extension is not None
    compiled = pipeline.load(result)
    ref_path = tmp_path / "bt_ref.py"
    ref_path.write_text(text)
    import importlib.util
    spec = importlib.util.spec_from_file_location("bt_ref", ref_path)
    ref = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ref)
    before = compiled.__typedpython_deopts__
    assert run_main(compiled, 6) == run_main(ref, 6)
    assert compiled.__typedpython_deopts__ == before
    assert isinstance(compiled.Node.__dict__["check"], types.FunctionType) is False


# --- the five benchmarks compile as they did --------------------------------------------------------

EXPECTED = {
    "binary_trees": {"check", "make"},
    "fannkuch": {"fannkuch"},
    "spectral_norm": {"eval_a", "mul_atv", "mul_av"},
    "wordfreq": {"next_state", "word_of", "main"},   # word_of and main: native ints, #232
    "nbody": set(),
}


@pytest.mark.parametrize("name", sorted(EXPECTED))
def test_the_benchmarks_compile_the_same_functions_as_before(tmp_path, name):
    path = tmp_path / f"{name}.py"
    path.write_text("# typedpython: compiled\n" + (REPO / "benchmarks" / "typedpython" / "py"
                                                    / f"{name}.py").read_text())
    proved, diags = verify.verify(frontend.lower(path))
    proved, diags = cost.apply(proved, diags)
    assert {f.name for f in proved.functions} == EXPECTED[name]
