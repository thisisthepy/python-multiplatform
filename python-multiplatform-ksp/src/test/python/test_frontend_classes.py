"""TypedPython front end: fixed-layout classes, None and identity (SPEC N-11, ir.py contract).

A class is lowered to an `ir.ClassDecl` only when its layout is fixed in CPython too: the user wrote
`__slots__` equal to the annotated fields, no base but object, no metaclass, no decorator but
`@compiled`, and the module never subclasses it, assigns its class attributes or rebinds its name.
Every rule has an accepting and a rejecting test. A rejected class has no ClassDecl, an entry in
`Module.skipped` (keyed by the class name) with the reason, and functions using it lower as before
(`GetAttr` / `CallObject`).

Contract choice (ir.ClassDecl says "`__init__(self, *fields-in-order)`"): `trivial_init` requires the
i-th `__init__` parameter to be stored into the i-th slot of `__slots__`; the statements may come in
any order and the parameter names are free. `ClassDecl.fields` is the `__slots__` order and
`New(cls, args)` takes the call's arguments in that order.
"""
import dataclasses
import textwrap
from pathlib import Path

import pytest

from typedpython import frontend
from typedpython.ir import (
    And, Assign, Box, Call, CallObject, CheckExact, ClassDecl, Const, ExprStmt, FieldGet, FieldSet,
    GetAttr, Global, If, Is, Local, New, Or, Param, Return, Type,
)

I64, F64, BOOL, OBJ, NONE = Type.I64, Type.F64, Type.BOOL, Type.OBJ, Type.NONE
BINARY_TREES = Path("/Volumes/macMini/thisisthepy/PythonMultiplatform/.worktrees/bench"
                    "/benchmarks/typedpython/py/binary_trees.py")

MARK = "# typedpython: compiled\n"

POINT = """
class P:
    __slots__ = ("x", "y")
    x: float
    y: float

    def __init__(self, x: float, y: float) -> None:
        self.x = x
        self.y = y
"""


def lower(tmp_path, source, name="mod.py"):
    path = tmp_path / name
    path.write_text(MARK + textwrap.dedent(source).lstrip("\n"))
    return frontend.lower(path)


def function(module, name):
    found = [f for f in module.functions if f.name == name]
    assert found, f"{name} was not lowered: {module.skipped.get(name)!r}"
    return found[0]


def cls(module, name):
    found = [c for c in module.classes if c.name == name]
    assert found, f"{name} has no ClassDecl: {module.skipped.get(name)!r}"
    return found[0]


def nodes(root, kind):
    found, stack = [], [root]
    while stack:
        x = stack.pop()
        if isinstance(x, (tuple, list)):
            stack.extend(x)
        elif dataclasses.is_dataclass(x) and not isinstance(x, type):
            if isinstance(x, kind):
                found.append(x)
            stack.extend(getattr(x, f.name) for f in dataclasses.fields(x))
    return found


def rejected(tmp_path, source, name, reason):
    m = lower(tmp_path, source)
    assert all(c.name != name for c in m.classes), f"{name} should not be a ClassDecl"
    assert reason in m.skipped[name], m.skipped[name]
    return m


# --- ClassDecl: accepted ---------------------------------------------------------------------------

def test_slots_class_in_marked_module_is_a_classdecl(tmp_path):
    m = lower(tmp_path, POINT)
    assert cls(m, "P") == ClassDecl("P", ("x", "y"), True)
    assert "P" not in m.skipped


def test_decorated_class_in_unmarked_module(tmp_path):
    path = tmp_path / "d.py"
    path.write_text(textwrap.dedent("""
        @compiled
        class P:
            __slots__ = ["a"]
            a: int

            def __init__(self, a: int) -> None:
                self.a = a

        class Q:
            __slots__ = ("b",)
            b: int
    """))
    m = frontend.lower(path)
    assert [c.name for c in m.classes] == ["P"]       # Q is neither decorated nor in a marked module
    assert m.classes[0].fields == ("a",)


def test_fields_keep_the_slots_order_not_the_annotation_order(tmp_path):
    m = lower(tmp_path, """
        class P:
            __slots__ = ("y", "x")
            x: int
            y: int
            def __init__(self, a: int, b: int) -> None:
                self.y = a
                self.x = b
    """)
    assert cls(m, "P") == ClassDecl("P", ("y", "x"), True)


def test_explicit_object_base_and_docstring_are_accepted(tmp_path):
    m = lower(tmp_path, """
        class P(object):
            "doc"
            __slots__ = ("x",)
            x: int
    """)
    assert cls(m, "P").fields == ("x",)
    assert cls(m, "P").trivial_init is False     # no __init__: object's, not an assign-each-slot one


def test_methods_are_allowed_and_stay_interpreted(tmp_path):
    m = lower(tmp_path, POINT + """
    def norm(self) -> float:
        return self.x
""")
    assert cls(m, "P").fields == ("x", "y")


# --- ClassDecl: rejected ---------------------------------------------------------------------------

def test_missing_slots_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            x: int
            def __init__(self, x: int) -> None:
                self.x = x
    """, "P", "`__slots__`")


def test_slots_differing_from_annotations_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x", "z")
            x: int
            y: int
    """, "P", "annotated fields")


def test_slots_not_a_literal_is_rejected(tmp_path):
    rejected(tmp_path, """
        NAMES = ("x",)
        class P:
            __slots__ = NAMES
            x: int
    """, "P", "literal")


def test_duplicate_slot_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x", "x")
            x: int
    """, "P", "twice")


def test_base_class_is_rejected(tmp_path):
    m = rejected(tmp_path, """
        class B:
            pass
        class P(B):
            __slots__ = ("x",)
            x: int
    """, "P", "base")
    assert all(c.name != "B" for c in m.classes)        # B has no slots either


def test_metaclass_keyword_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P(metaclass=type):
            __slots__ = ("x",)
            x: int
    """, "P", "metaclass")


def test_decorator_other_than_compiled_is_rejected(tmp_path):
    rejected(tmp_path, """
        import dataclasses
        @dataclasses.dataclass
        class P:
            __slots__ = ("x",)
            x: int
    """, "P", "decorator")


def test_subclass_in_the_module_rejects_the_base(tmp_path):
    m = rejected(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
        class Q(P):
            __slots__ = ()
    """, "P", "subclass")
    assert m.classes == ()


def test_subclass_nested_in_a_function_still_counts(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
        def make():
            class Q(P):
                pass
            return Q
    """, "P", "subclass")


def test_class_attribute_assignment_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
        P.count = 0
    """, "P", "class attribute")


def test_class_attribute_assignment_in_a_function_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
        def f() -> None:
            setattr(P, "count", 1)
    """, "P", "class attribute")


def test_class_level_value_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
            limit = 3
    """, "P", "class body")


def test_rebound_class_name_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
        P = 3
    """, "P", "bound more than once")


def test_global_rebinding_in_a_function_is_rejected(tmp_path):
    rejected(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
        def f() -> None:
            global P
            P = None
    """, "P", "bound more than once")


@pytest.mark.parametrize("dunder", ["__getattribute__", "__setattr__", "__getattr__", "__new__"])
def test_slot_access_overrides_are_rejected(tmp_path, dunder):
    # these run user code on a slot read/write/construct, which the direct slot access would skip
    rejected(tmp_path, f"""
        class P:
            __slots__ = ("x",)
            x: int
            def {dunder}(self, *a):
                return 0
    """, "P", dunder)


# --- trivial_init ------------------------------------------------------------------------------------

def test_init_with_free_parameter_names_and_statement_order_is_trivial(tmp_path):
    m = lower(tmp_path, """
        class P:
            __slots__ = ("x", "y")
            x: int
            y: int
            def __init__(self, a: int, b: int) -> None:
                self.y = b
                self.x = a
    """)
    assert cls(m, "P").trivial_init is True


@pytest.mark.parametrize("body, why", [
    ("self.x = x", "assigns 1 of 2"),                                    # a slot left unset
    ("self.x = x\n                self.y = y\n                self.x = x", "more than once"),
    ("self.x = x\n                self.y = x + 1", "exactly"),                    # not a plain parameter
    ("self.x = x\n                self.y = y\n                print(1)", "exactly"),    # extra statement
    ("self.x = y\n                self.y = x", "order"),                          # params not in slot order
])
def test_non_trivial_init_keeps_the_class_but_not_new(tmp_path, body, why):  # `why` documents the case
    m = lower(tmp_path, f"""
        class P:
            __slots__ = ("x", "y")
            x: int
            y: int
            def __init__(self, x: int, y: int) -> None:
                {body}
        @compiled
        def make(a: int) -> P:
            return P(a, a)
    """)
    assert cls(m, "P") == ClassDecl("P", ("x", "y"), False)
    f = function(m, "make")
    assert nodes(f, New) == []
    assert f.body == (Return(CallObject(OBJ, Global(OBJ, "P"), (
        Box(OBJ, Local(I64, "a")), Box(OBJ, Local(I64, "a"))))),)
    assert not f.pure


def test_init_with_defaults_or_extra_params_is_not_trivial(tmp_path):
    m = lower(tmp_path, """
        class P:
            __slots__ = ("x",)
            x: int
            def __init__(self, x: int = 0) -> None:
                self.x = x
    """)
    assert cls(m, "P").trivial_init is False


# --- None and identity -------------------------------------------------------------------------------

def test_none_literal_is_an_obj_const(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def nothing() -> P | None:
    return None
""")
    f = function(m, "nothing")
    assert f.body == (Return(Const(OBJ, None)),)
    assert f.pure and not f.may_deopt


def test_is_none_and_is_not_none_and_identity(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(a: P | None, b: P | None) -> int:
    if a is None:
        return 0
    if a is not b:
        return 1
    return 2
""")
    f = function(m, "f")
    none = Const(OBJ, None)
    assert f.body[0] == If(Is(BOOL, Local(OBJ, "a"), none), (Return(Const(I64, 0)),))
    assert f.body[1] == If(Is(BOOL, Local(OBJ, "a"), Local(OBJ, "b"), True), (Return(Const(I64, 1)),))
    assert f.pure and not f.may_deopt


def test_is_in_and_or_conditions(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(a: P | None, b: P | None) -> int:
    if a is None or b is None:
        return 0
    return 1
""")
    f = function(m, "f")
    none = Const(OBJ, None)
    assert f.body[0] == If(Or(BOOL, Is(BOOL, Local(OBJ, "a"), none), Is(BOOL, Local(OBJ, "b"), none)),
                           (Return(Const(I64, 0)),))


def test_identity_of_a_scalar_is_not_lowered(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(a: int) -> bool:
            return a is None
    """)
    assert "f" not in [fn.name for fn in m.functions]
    assert "identity" in m.skipped["f"]


# --- parameters and locals -----------------------------------------------------------------------------

def test_class_parameters_are_obj_with_cls_and_optional(tmp_path):
    m = lower(tmp_path, "from typing import Optional\n" + POINT + """
@compiled
def f(a: P, b: P | None, c: "P | None", d: Optional[P], e: None | P) -> int:
    return 1
""")
    f = function(m, "f")
    assert f.params == (
        Param("a", OBJ, "P", False), Param("b", OBJ, "P", True), Param("c", OBJ, "P", True),
        Param("d", OBJ, "P", True), Param("e", OBJ, "P", True))


def test_annotation_naming_a_rejected_class_is_a_plain_object(tmp_path):
    m = lower(tmp_path, """
        class P:
            x: int
        @compiled
        def f(a: P) -> int:
            return 1
    """)
    assert function(m, "f").params == (Param("a", OBJ),)


# --- field reads -----------------------------------------------------------------------------------------

def test_field_read_of_a_non_optional_parameter_is_proved(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(p: P) -> object:
    return p.x
""")
    f = function(m, "f")
    assert f.body == (Return(FieldGet(OBJ, Local(OBJ, "p"), "P", "x")),)
    assert f.pure and not f.may_deopt                     # a slot read is not an effect, nothing deopts
    assert nodes(f, CheckExact) == [] and nodes(f, GetAttr) == []


def test_field_read_of_an_optional_parameter_is_checked_in_a_pure_function(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(p: P | None) -> object:
    return p.x
""")
    f = function(m, "f")
    assert f.body == (Return(FieldGet(OBJ, CheckExact(OBJ, Local(OBJ, "p"), "P"), "P", "x")),)
    assert f.pure and f.may_deopt


def test_field_read_after_a_rebinding_is_not_trusted(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(p: P, q: P | None) -> object:
    p = q
    return p.x
""")
    f = function(m, "f")
    assert len(nodes(f, CheckExact)) == 1 and f.may_deopt


def test_unproved_field_read_in_an_impure_function_is_skipped(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(p: P | None) -> object:
    print(1)
    return p.x
""")
    assert "f" not in [fn.name for fn in m.functions]
    assert "not proved" in m.skipped["f"]


def test_proved_field_read_in_an_impure_function_is_lowered(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(p: P) -> object:
    print(1)
    return p.x
""")
    f = function(m, "f")
    assert nodes(f, FieldGet) == [FieldGet(OBJ, Local(OBJ, "p"), "P", "x")]
    assert not f.pure and not f.may_deopt


def test_attribute_that_is_not_a_field_stays_getattr(tmp_path):
    m = lower(tmp_path, POINT + """
    def norm(self) -> float:
        return self.x

""" + """
@compiled
def f(p: P) -> object:
    return p.norm
""")
    f = function(m, "f")
    assert nodes(f, GetAttr) and nodes(f, FieldGet) == []
    assert not f.pure


def test_fresh_new_local_is_proved_exact(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(a: float) -> object:
    p = P(a, a)
    return p.y
""")
    f = function(m, "f")
    assert nodes(f, FieldGet) == [FieldGet(OBJ, Local(OBJ, "p"), "P", "y")]
    assert nodes(f, CheckExact) == [] and f.pure and not f.may_deopt


# --- field writes ------------------------------------------------------------------------------------------

def test_field_write_is_an_effect(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(p: P, v: float) -> None:
    p.x = v
""")
    f = function(m, "f")
    assert f.body == (FieldSet(Local(OBJ, "p"), "P", "x", Box(OBJ, Local(F64, "v"))),)
    assert not f.pure and not f.may_deopt


def test_field_write_on_an_unproved_object_is_skipped(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(p: P | None, v: float) -> None:
    p.x = v
""")
    assert "f" not in [fn.name for fn in m.functions]
    assert "not proved" in m.skipped["f"]


def test_write_to_an_attribute_of_an_unknown_object_is_still_skipped(tmp_path):
    m = lower(tmp_path, """
        @compiled
        def f(p: object) -> None:
            p.x = 1
    """)
    assert "attribute" in m.skipped["f"]


# --- construction -------------------------------------------------------------------------------------------

def test_construction_with_a_trivial_init_is_new_in_field_order(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(a: float, b: float) -> object:
    return P(a, b)
""")
    f = function(m, "f")
    assert f.body == (Return(New(OBJ, "P", (Box(OBJ, Local(F64, "a")), Box(OBJ, Local(F64, "b"))))),)
    assert f.pure and not f.may_deopt                     # allocating is not an effect


def test_construction_with_wrong_arity_or_keywords_is_a_plain_call(tmp_path):
    m = lower(tmp_path, POINT + """
@compiled
def f(a: float) -> object:
    return P(a)

@compiled
def g(a: float) -> object:
    return P(x=a, y=a)
""")
    for name in ("f", "g"):
        assert nodes(function(m, name), New) == []
        assert len(nodes(function(m, name), CallObject)) == 1


# --- the binary-trees benchmark ------------------------------------------------------------------------------

def test_binary_trees_with_slots_lowers_make_and_check(tmp_path):
    source = BINARY_TREES.read_text()
    assert "class Node:\n    left:" in source
    source = source.replace("class Node:\n", 'class Node:\n    __slots__ = ("left", "right")\n', 1)
    path = tmp_path / "binary_trees.py"
    path.write_text(MARK + source)
    m = frontend.lower(path)

    assert cls(m, "Node") == ClassDecl("Node", ("left", "right"), True)
    make, check = function(m, "make"), function(m, "check")
    assert make.pure and check.pure
    assert make.params == (Param("depth", I64),)
    assert check.params == (Param("node", OBJ, "Node", False),)
    assert len(nodes(make, New)) == 2
    assert sorted(nodes(check, FieldGet), key=lambda g: g.field) == [
        FieldGet(OBJ, Local(OBJ, "node"), "Node", "left"),
        FieldGet(OBJ, Local(OBJ, "node"), "Node", "right")]
    assert nodes(check, Is)
    assert nodes(check, CallObject) == [] and nodes(check, GetAttr) == []
    # report: make() and check() both may deopt (i64 arithmetic, and `check(left)` passes an
    # `Node | None` local to a parameter guarded `type(x) is Node`)
    print(f"make.may_deopt={make.may_deopt} check.may_deopt={check.may_deopt}")
    assert make.may_deopt and check.may_deopt


def test_binary_trees_without_slots_stays_object_code(tmp_path):
    path = tmp_path / "binary_trees.py"
    path.write_text(MARK + BINARY_TREES.read_text())
    m = frontend.lower(path)
    assert m.classes == ()
    assert "`__slots__`" in m.skipped["Node"]
    # as before: object code. `check` reads attributes (impure), so its int arithmetic would be
    # CPython ints and it cannot return a native int: it stays interpreted; `make` calls `Node`.
    assert "check" not in [f.name for f in m.functions]
    make = function(m, "make")
    assert nodes(make, New) == [] and nodes(make, CallObject) and not make.pure
