"""Any reaching user code from an untyped dependency (design §2.3, §4.2)."""
from conftest import FIXTURES, errors
from typedpython import gate

DEPS = FIXTURES / "deps"


def test_untyped_result_flowing_into_user_code_is_an_error(write):
    path = write("user.py", """
        from untyped_dep import load

        def total(text: str) -> None:
            value = load(text)
            print(value)
    """)
    found = errors(gate.check([path], search_paths=[DEPS]))
    flow = [d for d in found if d.rule == "any-flow"]
    assert flow, found
    assert all(d.path == str(path) for d in flow)


def test_cast_at_the_boundary_resolves_it(write):
    path = write("user.py", """
        from typing import cast
        from untyped_dep import load

        def total(text: str) -> int:
            value = cast(int, load(text))
            return value + 1
    """)
    assert gate.check([path], search_paths=[DEPS]) == []


def test_the_dependency_itself_is_not_checked(write):
    path = write("user.py", """
        from typing import cast
        from untyped_dep import load

        def total(text: str) -> int:
            return cast(int, load(text))
    """)
    found = gate.check([path], search_paths=[DEPS])
    assert not [d for d in found if d.path.endswith("untyped_dep.py")], found
