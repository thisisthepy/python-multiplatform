"""TypedPython C back end: fixed-layout classes (SPEC N-11; ir.ClassDecl / FieldGet / FieldSet / New /
IsExact / CheckExact / Is / Param.cls).

IR is built by hand; every end-to-end test compares the compiled function with the interpreted one the
extension keeps in `__typedpython_interpreted__` (INTENT §1.4). Builds go to `.tmp/cgen-tests/` of the
worktree, inside the caller's heavy.sh lock (see test_cgen.py).

Ordering of the red/green record: this file was written before the back end supported these nodes;
before the implementation every end-to-end test failed with `CGenError: no lowering for ...`.
"""
from __future__ import annotations

import subprocess
import sys
import sysconfig
import textwrap
import os
from pathlib import Path

import pytest

import test_cgen as tc
from test_cgen import (PACKAGE, OUT_ROOT, I64, BOOL, OBJ, NONE, c, L, fn, runtime_dir, need_runtime)
from typedpython import ir
from typedpython.ir import (
    Assign, BinOp, BinOpKind, Call, CheckExact, ClassDecl, Compare, CompareKind, Const, FieldGet,
    FieldSet, If, Is, IsExact, Module, New, Param, Return,
)

SOURCE = textwrap.dedent('''\
    """typedpython class test module, the interpreted reference."""

    class Node:
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            self.left = left
            self.right = right

    class Sub(Node):                       # a subclass whose `left` is NOT the slot
        __slots__ = ()

        @property
        def left(self):
            return None

    def mk_sub(l, r):
        s = Sub.__new__(Sub)
        Node.left.__set__(s, l)
        Node.right.__set__(s, r)
        return s

    class Fat:                             # no __slots__: instances have a __dict__
        def __init__(self, left, right):
            self.left = left
            self.right = right

    class Prop:                            # a property shadows a field after creation
        __slots__ = ("left", "right")

    Prop.left = property(lambda self: "shadowed")

    class Other:
        __slots__ = ("left", "right")

    class Stolen:                          # a member descriptor of ANOTHER class under our name
        __slots__ = ("left", "right")

        def __init__(self, left, right):
            pass

    Stolen.left = Other.left

    def make(depth):
        if depth == 0:
            return None
        return Node(make(depth - 1), make(depth - 1))

    def check(node):
        if node is None:
            return 0
        return 1 + check(node.left) + check(node.right)

    def setl(node, v):
        node.left = v

    def left_checked(x):
        return x.left

    def is_node(x):
        return type(x) is Node

    def is_not_none(x):
        return x is not None

    def same(a, b):
        return a is b

    def ret_none():
        return None

    def mkfat(depth):
        if depth == 0:
            return None
        return Fat(mkfat(depth - 1), mkfat(depth - 1))

    def check_fat(node):
        if node is None:
            return 0
        return 1 + check_fat(node.left) + check_fat(node.right)

    def left_of_prop(p):
        return p.left

    def left_of_stolen(p):
        return p.left
    ''')

NODE = ClassDecl("Node", ("left", "right"), True)
FAT = ClassDecl("Fat", ("left", "right"), True)
PROP = ClassDecl("Prop", ("left", "right"), True)
STOLEN = ClassDecl("Stolen", ("left", "right"), True)
CLASSES = (NODE, FAT, PROP, STOLEN)


def f_make(name="make", cls="Node"):
    depth, a, b = L("depth", I64), L("a", OBJ), L("b", OBJ)
    rec = lambda: Call(OBJ, name, (BinOp(I64, BinOpKind.SUB, depth, c(1)),))
    return fn(name, [Param("depth", I64)], OBJ, {"a": OBJ, "b": OBJ},
              [If(Compare(BOOL, CompareKind.EQ, depth, c(0)), (Return(Const(OBJ, None)),)),
               Assign("a", rec()), Assign("b", rec()),
               Return(New(OBJ, cls, (a, b)))],
              pure=True, may_deopt=True)


def f_check(name="check", cls="Node"):
    node, l, r = L("node", OBJ), L("l", OBJ), L("r", OBJ)
    ca = Call(I64, name, (l,))
    cb = Call(I64, name, (r,))
    return fn(name, [Param("node", OBJ, cls=cls, optional=True)], I64, {"l": OBJ, "r": OBJ},
              [If(Is(BOOL, node, Const(OBJ, None)), (Return(c(0)),)),
               Assign("l", FieldGet(OBJ, node, cls, "left")),
               Assign("r", FieldGet(OBJ, node, cls, "right")),
               Return(BinOp(I64, BinOpKind.ADD, BinOp(I64, BinOpKind.ADD, c(1), ca), cb))],
              pure=True, may_deopt=True)


def f_setl():
    node, v = L("node", OBJ), L("v", OBJ)
    return fn("setl", [Param("node", OBJ, cls="Node"), Param("v", OBJ)], NONE, {},
              [FieldSet(node, "Node", "left", v), Return()],
              pure=False, may_deopt=False)


def f_left_checked():
    x = L("x", OBJ)
    return fn("left_checked", [Param("x", OBJ)], OBJ, {},
              [Return(FieldGet(OBJ, CheckExact(OBJ, x, "Node"), "Node", "left"))],
              pure=True, may_deopt=True)


def f_is_node():
    return fn("is_node", [Param("x", OBJ)], BOOL, {}, [Return(IsExact(BOOL, L("x", OBJ), "Node"))],
              pure=True, may_deopt=False)


def f_is_not_none():
    return fn("is_not_none", [Param("x", OBJ)], BOOL, {},
              [Return(Is(BOOL, L("x", OBJ), Const(OBJ, None), negate=True))],
              pure=True, may_deopt=False)


def f_same():
    return fn("same", [Param("a", OBJ), Param("b", OBJ)], BOOL, {},
              [Return(Is(BOOL, L("a", OBJ), L("b", OBJ)))], pure=True, may_deopt=False)


def f_ret_none():
    return fn("ret_none", [], OBJ, {}, [Return(Const(OBJ, None))], pure=True, may_deopt=False)


def f_left_of(name, cls):
    return fn(name, [Param("p", OBJ, cls=cls)], OBJ, {},
              [Return(FieldGet(OBJ, L("p", OBJ), cls, "left"))], pure=True, may_deopt=True)


def functions():
    return (f_make(), f_check(), f_setl(), f_left_checked(), f_is_node(), f_is_not_none(), f_same(),
            f_ret_none(), f_make("mkfat", "Fat"), f_check("check_fat", "Fat"),
            f_left_of("left_of_prop", "Prop"), f_left_of("left_of_stolen", "Stolen"))


def source_file(name: str) -> Path:
    import shutil
    d = OUT_ROOT / name
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    p = d / f"{name}.py"
    p.write_text(SOURCE)
    return p


def generate(name, funcs=None, classes=CLASSES):
    from typedpython import cgen
    funcs = functions() if funcs is None else funcs
    return cgen.generate(Module(name=name, functions=tuple(funcs), classes=tuple(classes)),
                         source_file(name))


def build(name, flags=None, funcs=None, classes=CLASSES):
    from typedpython import cbuild
    need_runtime()
    src = generate(name, funcs, classes)
    kwargs = {} if flags is None else {"flags": flags}
    so = cbuild.build(src, name, OUT_ROOT / name, runtime_dir=runtime_dir(), **kwargs)
    return cbuild.load(name, so), so


@pytest.fixture(scope="module")
def m():
    return build("tp_cgen_cls")[0]


def interp(m, name):
    return m.__typedpython_interpreted__[name]


def tree_size(n):
    return 0 if n is None else 1 + tree_size(n.left) + tree_size(n.right)


# --- behaviour ---------------------------------------------------------------------------------------

def test_make_and_check_match_the_interpreted_results(m):
    for depth in (0, 1, 2, 5, 10):
        before = m.__typedpython_deopts__
        tree = m.make(depth)
        assert type(tree) is m.Node or tree is None
        assert tree_size(tree) == 2 ** depth - 1
        assert m.check(tree) == interp(m, "check")(tree) == 2 ** depth - 1
        assert m.__typedpython_deopts__ == before                    # compiled all the way


def test_compiled_objects_are_ordinary_instances(m):
    t = m.make(2)
    assert type(t) is m.Node and not hasattr(t, "__dict__")
    assert t.left.left is None and t.left.right is None
    assert interp(m, "check")(t) == 3


def test_unset_slot_raises_cpythons_attribute_error(m):
    bare = m.Node.__new__(m.Node)
    with pytest.raises(AttributeError) as want:
        interp(m, "check")(bare)
    with pytest.raises(AttributeError) as got:
        m.check(bare)
    assert str(got.value) == str(want.value)
    assert str(got.value).endswith("Node' object has no attribute 'left'")
    half = m.Node.__new__(m.Node)
    m.Node.left.__set__(half, None)
    with pytest.raises(AttributeError) as got2:
        m.check(half)
    assert str(got2.value).endswith("Node' object has no attribute 'right'")


def test_subclass_instance_takes_the_interpreted_path(m):
    sub = m.mk_sub(m.Node(None, None), None)
    assert interp(m, "check")(sub) == 1                  # the property makes `left` None
    before = m.__typedpython_deopts__
    assert m.check(sub) == 1                              # raw slot would give 2
    assert m.__typedpython_deopts__ == before + 1


def test_nested_subclass_instance_deopts_the_whole_call(m):
    sub = m.mk_sub(m.Node(None, None), None)
    tree = m.Node(sub, m.Node(None, None))
    want = interp(m, "check")(tree)
    before = m.__typedpython_deopts__
    assert m.check(tree) == want == 3
    # the outermost call is redone interpreted; the interpreted body then calls the (compiled) global
    # `check` on each child, so the subclass child deopts again: at least one, never zero
    assert m.__typedpython_deopts__ >= before + 1


def test_other_argument_types_take_the_interpreted_path(m):
    def outcome(f, x):
        try:
            return ("ok", f(x))
        except Exception as e:                            # noqa: BLE001, the type and text are the result
            return (type(e), str(e))

    for bad in (5, "x", object(), m.Fat(None, None), m.Fat(m.Fat(None, None), None)):
        want = outcome(interp(m, "check"), bad)
        before = m.__typedpython_deopts__
        assert outcome(m.check, bad) == want
        assert m.__typedpython_deopts__ >= before + 1


def test_field_set_is_visible_to_python(m):
    n = m.Node(1, 2)
    assert m.setl(n, "x") is None
    assert n.left == "x" and n.right == 2
    assert m.check(m.Node(None, None)) == 1
    keep = object()
    m.setl(n, keep)
    assert n.left is keep


def test_field_set_on_a_subclass_goes_interpreted(m):
    sub = m.mk_sub(None, None)
    before = m.__typedpython_deopts__
    with pytest.raises(AttributeError) as want:
        interp(m, "setl")(sub, 1)
    with pytest.raises(AttributeError) as got:
        m.setl(sub, 1)
    assert str(got.value) == str(want.value)
    assert m.__typedpython_deopts__ == before + 1


def test_check_exact_deopts_on_mismatch_and_reads_otherwise(m):
    n = m.Node("l", "r")
    before = m.__typedpython_deopts__
    assert m.left_checked(n) == "l"
    assert m.__typedpython_deopts__ == before
    sub = m.mk_sub("l", "r")
    assert m.left_checked(sub) is interp(m, "left_checked")(sub) is None
    assert m.__typedpython_deopts__ == before + 1
    with pytest.raises(AttributeError):
        m.left_checked(5)
    assert m.__typedpython_deopts__ == before + 2


def test_is_exact_is_and_none_constants(m):
    assert m.is_node(m.Node(1, 2)) is True
    assert m.is_node(m.mk_sub(1, 2)) is False
    assert m.is_node(None) is False and m.is_node(5) is False
    x = object()
    assert m.same(x, x) is True and m.same(x, object()) is False
    assert m.same(None, None) is True
    assert m.is_not_none(None) is False and m.is_not_none(0) is True
    assert m.ret_none() is None


# --- classes that are not compiled ---------------------------------------------------------------------

def test_class_with_a_dict_is_not_compiled_and_nothing_crashes(m):
    before = m.__typedpython_deopts__
    tree = m.mkfat(4)                                     # New of a class without __slots__
    assert type(tree) is m.Fat and tree.left is not None
    assert m.check_fat(tree) == 15 == interp(m, "check_fat")(tree)
    assert m.__typedpython_deopts__ > before              # the calls took the interpreted path
    assert m.check_fat(None) == 0


def test_property_shadowing_a_field_is_not_compiled(m):
    p = m.Prop()
    before = m.__typedpython_deopts__
    assert m.left_of_prop(p) == "shadowed" == interp(m, "left_of_prop")(p)
    assert m.__typedpython_deopts__ == before + 1


def test_descriptor_of_another_class_is_not_compiled(m):
    s = m.Stolen.__new__(m.Stolen)
    before = m.__typedpython_deopts__
    with pytest.raises(TypeError) as want:
        interp(m, "left_of_stolen")(s)
    with pytest.raises(TypeError) as got:
        m.left_of_stolen(s)
    assert str(got.value) == str(want.value)
    assert m.__typedpython_deopts__ == before + 1


def test_missing_class_is_not_compiled(tmp_path):
    mod, _ = build("tp_cgen_cls_missing", funcs=(f_make(),), classes=(ClassDecl("Nope", ("a",), True),
                                                                      NODE))
    before = mod.__typedpython_deopts__
    assert type(mod.make(2)) is mod.Node                  # Node is fine; the missing one is unused
    assert mod.__typedpython_deopts__ == before


# --- generation contract -------------------------------------------------------------------------------

def test_gen_rejects_check_exact_in_an_impure_function():
    bad2 = fn("bad2", [Param("x", OBJ)], OBJ, {},
              [Return(CheckExact(OBJ, L("x", OBJ), "Node"))], pure=False, may_deopt=False)
    from typedpython import cgen
    with pytest.raises(cgen.CGenError, match="CheckExact"):
        generate("tp_gen_cls_bad", funcs=(bad2,))


def test_gen_rejects_field_set_in_a_pure_function():
    bad = fn("bad", [Param("x", OBJ, cls="Node")], NONE, {},
             [FieldSet(L("x", OBJ), "Node", "left", Const(OBJ, None)), Return()],
             pure=True, may_deopt=False)
    from typedpython import cgen
    with pytest.raises(cgen.CGenError, match="pure"):
        generate("tp_gen_cls_bad2", funcs=(bad,))


def test_gen_rejects_an_impure_caller_of_a_class_guarded_callee():
    setter = f_setl()
    caller = fn("caller", [Param("x", OBJ)], NONE, {},
                [ir.ExprStmt(Call(NONE, "setl", (L("x", OBJ), L("x", OBJ)))), Return()],
                pure=False, may_deopt=False)
    from typedpython import cgen
    with pytest.raises(cgen.CGenError, match="deopt"):
        generate("tp_gen_cls_bad3", funcs=(setter, caller))


def test_gen_rejects_unknown_class_field_or_arity():
    from typedpython import cgen
    unknown = fn("u", [Param("x", OBJ)], OBJ, {}, [Return(New(OBJ, "Nope", ()))],
                 pure=True, may_deopt=False)
    badfield = fn("f", [Param("x", OBJ, cls="Node")], OBJ, {},
                  [Return(FieldGet(OBJ, L("x", OBJ), "Node", "middle"))], pure=True, may_deopt=True)
    arity = fn("a", [], OBJ, {}, [Return(New(OBJ, "Node", (Const(OBJ, None),)))],
               pure=True, may_deopt=False)
    for i, f in enumerate((unknown, badfield, arity)):
        with pytest.raises(cgen.CGenError):
            generate(f"tp_gen_cls_bad4_{i}", funcs=(f,))


def test_gen_uses_only_the_runtime_helpers_for_slots():
    text = generate("tp_gen_cls_only")
    for h in ("tp_field_get", "tp_field_set", "tp_new_fixed", "tp_is_exact", "tp_class_capture"):
        assert h in text, h
    assert "PyMember_" not in text and "tp_alloc" not in text and "->tp_dictoffset" not in text


# --- refcounts ---------------------------------------------------------------------------------------------

def test_reference_counts_are_unchanged_over_many_calls(m):
    shared = object()
    node = m.Node(shared, shared)
    bare = m.Node.__new__(m.Node)

    def churn(rounds):
        for _ in range(rounds):
            m.make(4)
            m.left_checked(node)
            m.setl(node, shared)
            m.is_node(node)
            m.same(node, node)
            m.check(m.Node(None, None))
            m.check(m.mk_sub(None, None))                   # deopt path
            try:
                m.check(bare)
            except AttributeError:
                pass
            try:
                m.setl(5, 5)
            except AttributeError:
                pass

    churn(20)                                               # warm up caches, then measure
    counts = lambda: (sys.getrefcount(shared), sys.getrefcount(node), sys.getrefcount(m.Node),
                      sys.getrefcount(m.Sub), sys.getrefcount(bare))
    base = counts()
    churn(3000)
    assert counts() == base, (base, counts())


# --- the mutation: without the exact-type guard the subclass test must fail ---------------------------

MUTANT_SCRIPT = textwrap.dedent('''\
    import sys
    sys.path.insert(0, {pkg!r})
    from typedpython import cbuild
    m = cbuild.load({name!r}, {so!r})
    sub = m.mk_sub(m.Node(None, None), None)
    want = m.__typedpython_interpreted__["check"](sub)
    got = m.check(sub)
    print("RESULT", want, got, m.__typedpython_deopts__)
    bad = 0 if (want == got and m.__typedpython_deopts__ == 1) else 3
    # the DIRECT native call path: a subclass / a non-Node reached as a child of a Node
    def outcome(f, x):
        try:
            return ("ok", f(x))
        except Exception as e:
            return (type(e).__name__, str(e))
    for child in (sub, 5, m.Fat(None, None)):
        tree = m.Node(child, None)
        w = outcome(m.__typedpython_interpreted__["check"], tree)
        before = m.__typedpython_deopts__
        g = outcome(m.check, tree)
        print("NESTED", type(child).__name__, w, g, m.__typedpython_deopts__ - before)
        if w != g or m.__typedpython_deopts__ <= before:
            bad = 3
    sys.exit(bad)
    ''')


def run_child(name, so):
    script = MUTANT_SCRIPT.format(pkg=str(PACKAGE.parent), name=name, so=str(so))
    return subprocess.run([sys.executable, "-c", script], capture_output=True, text=True)


def test_mutation_unmutated_build_passes_the_subclass_script():
    need_runtime()
    _, so = build("tp_cgen_cls_ok")
    run = run_child("tp_cgen_cls_ok", so)
    assert run.returncode == 0, run.stdout + run.stderr


def test_mutation_without_the_param_guard_the_subclass_test_fails(monkeypatch):
    need_runtime()
    from typedpython import cgen, cbuild
    monkeypatch.setattr(cgen, "_param_cls_fail", lambda *a, **k: "0")      # the guard never fires
    # Since the per-access class check (test_cgen_class_changes.py) every FieldGet also tests
    # `type(obj) is C` itself, so the entry guard alone is no longer the only defence: the mutant
    # removes both (with the per-access check kept, this mutant computes the right results).
    monkeypatch.setattr(cgen, "_class_current_expr", lambda *a, **k: "1")
    src = generate("tp_cgen_cls_mut")
    monkeypatch.undo()
    so = cbuild.build(src, "tp_cgen_cls_mut", OUT_ROOT / "tp_cgen_cls_mut", runtime_dir=runtime_dir())
    run = run_child("tp_cgen_cls_mut", so)
    assert run.returncode != 0, ("the mutant (no exact-type guard on Param.cls) passed the subclass "
                                 "test:\n" + run.stdout + run.stderr)


# --- sanitizers ---------------------------------------------------------------------------------------------

ASAN_SCRIPT = textwrap.dedent('''\
    import sys
    sys.path.insert(0, {pkg!r})
    from typedpython import cbuild
    m = cbuild.load({name!r}, {so!r})
    I = m.__typedpython_interpreted__
    for d in (0, 1, 6):
        t = m.make(d)
        assert m.check(t) == I["check"](t) == 2 ** d - 1
    for _ in range(300):
        t = m.make(5)
        n = m.Node(t, t)
        assert m.check(n) == 2 * 31 + 1
        m.setl(n, None)
        m.left_checked(n)
    sub = m.mk_sub(m.Node(None, None), None)
    assert m.check(sub) == 1 and m.check(m.Node(sub, None)) == I["check"](m.Node(sub, None))
    for bad in (5, "x", object(), m.Fat(None, None)):
        try:
            m.check(bad)
        except AttributeError:
            pass
        try:
            m.setl(bad, 1)
        except AttributeError:
            pass
    bare = m.Node.__new__(m.Node)
    try:
        m.check(bare)
    except AttributeError:
        pass
    assert m.check_fat(m.mkfat(3)) == 7
    assert m.left_of_prop(m.Prop()) == "shadowed"
    print("ASAN-RUN-OK", m.__typedpython_deopts__)
    ''')


def test_class_functions_under_address_and_undefined_sanitizers():
    need_runtime()
    cc = sysconfig.get_config_var("CC") or "cc"
    if sys.platform != "darwin":
        pytest.skip("sanitizer preload recipe written for macOS only")
    probe = subprocess.run(cc.split() + ["-print-file-name=libclang_rt.asan_osx_dynamic.dylib"],
                           capture_output=True, text=True)
    if probe.returncode != 0 or not Path(probe.stdout.strip()).is_file():
        pytest.skip(f"host compiler {cc!r} has no ASan runtime ({probe.stdout.strip()!r})")
    from typedpython import cbuild
    name = "tp_cgen_cls_asan"
    flags = list(cbuild.DEFAULT_FLAGS) + ["-O1", "-g", "-fno-omit-frame-pointer",
                                          "-fsanitize=address,undefined",
                                          "-fno-sanitize-recover=undefined"]
    so = cbuild.build(generate(name), name, OUT_ROOT / name, runtime_dir=runtime_dir(), flags=flags)
    d = so.parent
    launcher_c, launcher = d / "asan_python.c", d / "asan_python"
    launcher_c.write_text("#include <Python.h>\nint main(int argc, char **argv) "
                          "{ return Py_BytesMain(argc, argv); }\n")
    link = subprocess.run(cc.split() + ["-fsanitize=address,undefined", "-g",
                                        f"-I{sysconfig.get_paths()['include']}", str(launcher_c),
                                        f"-L{sysconfig.get_config_var('LIBDIR')}",
                                        f"-lpython{sysconfig.get_config_var('LDVERSION')}",
                                        "-o", str(launcher)], capture_output=True, text=True)
    if link.returncode != 0:
        pytest.skip(f"cannot link a sanitized launcher: {link.stderr[-2000:]}")
    env = dict(os.environ, PYTHONHOME=sys.base_prefix,
               ASAN_OPTIONS="detect_leaks=0:abort_on_error=0:halt_on_error=1",
               UBSAN_OPTIONS="halt_on_error=1:print_stacktrace=1")
    env.pop("DYLD_INSERT_LIBRARIES", None)
    script = ASAN_SCRIPT.format(pkg=str(PACKAGE.parent), name=name, so=str(so))
    run = subprocess.run([str(launcher), "-c", script], capture_output=True, text=True, env=env)
    out = run.stdout + run.stderr
    if "violates platform policy" in out or "Interceptors are not working" in out:
        pytest.skip(f"ASan runtime could not be loaded: {out[-2000:]}")
    assert run.returncode == 0 and "ASAN-RUN-OK" in run.stdout, out[-6000:]
    linked = subprocess.run(["otool", "-L", str(so)], capture_output=True, text=True).stdout
    assert "libclang_rt.asan" in linked, linked
    assert "runtime error" not in out and "AddressSanitizer" not in out, out[-6000:]
