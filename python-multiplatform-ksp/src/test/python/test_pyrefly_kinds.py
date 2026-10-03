"""Each Pyrefly Any-leak kind the gate raises to error (design §4.2)."""
import pytest

from conftest import errors, rules
from typedpython import gate

CASES = {
    "implicit-any-parameter": """
        def f(x, y: int) -> int:
            return y
    """,
    "unannotated-return": """
        def f(y: int):
            return y
    """,
    "unknown-variable-type": """
        import json
        def f() -> None:
            d = json.loads("1")
            z = d["k"]
    """,
    "no-any-return-implicit": """
        import json
        def f() -> int:
            d = json.loads("1")
            return d["k"]
    """,
    "no-any-return-explicit": """
        from typing import Any
        def f(v: Any) -> int:
            return v
    """,
    "explicit-any": """
        from typing import Any
        def f(v: Any) -> None:
            pass
    """,
}


@pytest.mark.parametrize("kind", sorted(CASES))
def test_kind_is_an_error(write, kind):
    path = write("user.py", CASES[kind])
    found = gate.check([path])
    assert f"pyrefly/{kind}" in rules(errors(found)), found


def test_ordinary_type_error_is_an_error(write):
    path = write("user.py", """
        def f(y: int) -> int:
            return y + "a"
    """)
    assert errors(gate.check([path])), "a plain type error must still fail the gate"


def test_fully_typed_code_is_clean(write):
    path = write("user.py", """
        def area(w: int, h: int) -> int:
            scale = 2
            return w * h * scale

        class Box:
            def __init__(self, w: int) -> None:
                self.w = w

            def double(self) -> int:
                return self.w * 2
    """)
    assert gate.check([path]) == []
