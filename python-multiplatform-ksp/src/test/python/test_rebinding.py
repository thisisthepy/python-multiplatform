"""Rebinding a name to a different type, and mixed-element containers (design §3.1, #22).

Both are forbidden-list items: warnings under `checked`, errors under `compiled`. They need the
inferred type of an assignment target, which Pyrefly's type report does not give directly; the
gate reads each target back through a probe load (see typedpython.rebinding).
"""
import pytest

from typedpython import gate, rebinding

REBIND = "forbidden/rebinding"
MIXED = "forbidden/mixed-container"


def found(write, source, mode="checked"):
    return gate.check([write("user.py", source)], mode=mode)


def by_rule(diagnostics, rule):
    return [d for d in diagnostics if d.rule == rule]


def test_rebinding_to_another_type_is_reported(write):
    result = found(write, """
        def f() -> None:
            x = 1
            x = "a"
            print(x)
    """)
    [d] = by_rule(result, REBIND)
    assert (d.line, d.severity) == (4, "warning")
    assert "int" in d.message and "str" in d.message


def test_compiled_mode_makes_it_an_error(write):
    result = found(write, """
        def f() -> None:
            x = 1
            x = "a"
            print(x)
    """, mode="compiled")
    assert [d.severity for d in by_rule(result, REBIND)] == ["error"]


def test_augmented_assignment_that_changes_the_type(write):
    result = found(write, """
        def f() -> float:
            total = 0
            total += 0.5
            return total
    """)
    assert by_rule(result, REBIND), result


def test_module_level_rebinding(write):
    result = found(write, """
        limit = 10
        limit = "ten"
        print(limit)
    """)
    assert by_rule(result, REBIND), result


@pytest.mark.parametrize("source", [
    # same type again
    "def f() -> None:\n    x = 1\n    x = 2\n    print(x)\n",
    # a declared union governs; Pyrefly checks each assignment against it
    "def f() -> None:\n    x: int | str = 1\n    x = 'a'\n    print(x)\n",
    # an annotated parameter reassigned within its type
    "def f(n: int) -> int:\n    n = n + 1\n    return n\n",
])
def test_not_rebinding(write, source):
    assert by_rule(gate.check([write("user.py", source)]), REBIND) == []


def test_mixed_list_is_reported(write):
    result = found(write, """
        def f() -> None:
            mixed = [1, "a"]
            print(mixed)
    """)
    [d] = by_rule(result, MIXED)
    assert d.line == 3 and "int | str" in d.message


def test_mixed_dict_values_are_reported(write):
    result = found(write, """
        def f(a: int) -> None:
            d = {"k": a, "j": "s"}
            print(d)
    """)
    assert by_rule(result, MIXED), result


@pytest.mark.parametrize("source", [
    "def f() -> None:\n    nums = [1, 2]\n    print(nums)\n",
    "def f() -> None:\n    pair = (1, 'a')\n    print(pair)\n",          # tuples are typed per slot
    "def f() -> None:\n    xs: list[int | str] = [1, 'a']\n    print(xs)\n",  # declared on purpose
])
def test_not_mixed(write, source):
    assert by_rule(gate.check([write("user.py", source)]), MIXED) == []


def test_probe_keeps_every_line_number():
    source = "def f() -> None:\n    x = 1  # one\n    x = 'a'\n    y, z = 1, 2\n"
    probed, probes = rebinding.probe(source)
    assert probed.count("\n") == source.count("\n")
    assert {p.name for p in probes} >= {"x", "y", "z"}
    for p in probes:
        line = probed.splitlines()[p.line - 1]
        assert line[p.column - 1:].startswith(p.name), (p, line)


def test_no_candidates_means_no_second_pyrefly_run(write, monkeypatch):
    calls = []
    real = gate.pyrefly.run

    def counting(*args, **kwargs):
        calls.append(args)
        return real(*args, **kwargs)

    monkeypatch.setattr(gate.pyrefly, "run", counting)
    gate.check([write("user.py", "def f(a: int) -> int:\n    b = a + 1\n    return b\n")])
    assert len(calls) == 1


def test_rebinding_inside_a_package_with_relative_imports(write):
    write("pkg/__init__.py", "")
    write("pkg/b.py", "def make() -> int:\n    return 1\n")
    path = write("pkg/a.py", """
        from .b import make

        def f() -> None:
            x = make()
            x = "s"
            print(x)
    """)
    result = gate.check([path])
    assert by_rule(result, REBIND), result
    assert not [d for d in result if d.severity == "error"], result


def test_pyrefly_runs_once_when_a_file_has_a_candidate(write, monkeypatch):
    """The probed copy is the only Pyrefly run: errors, any-flow and types all come from it."""
    calls = []
    real = gate.pyrefly.run

    def counting(*args, **kwargs):
        calls.append(args)
        return real(*args, **kwargs)

    monkeypatch.setattr(gate.pyrefly, "run", counting)
    result = found(write, """
        def f() -> None:
            x = 1
            x = "a"
            print(x)
    """)
    assert by_rule(result, REBIND)
    assert len(calls) == 1


def test_error_column_after_a_probe_points_at_the_original_source(write):
    """`x = 1; y = undefined_name`: the probe goes after `x = 1`, left of the error."""
    source = "x = 1\nx = 2\nx = 1; y = undefined_name\n"
    path = write("user.py", source)
    result = gate.check([path])
    [d] = [d for d in result if d.rule == "pyrefly/unknown-name"]
    original_column = source.splitlines()[2].index("undefined_name") + 1
    assert (d.line, d.column) == (3, original_column)


def test_error_left_of_a_probe_keeps_its_column(write):
    source = "x = 1\nx = 2\nprint(undefined_name); x = 3\n"
    path = write("user.py", source)
    [d] = [d for d in gate.check([path]) if d.rule == "pyrefly/unknown-name"]
    assert (d.line, d.column) == (3, source.splitlines()[2].index("undefined_name") + 1)


def test_probe_loads_are_not_any_flow_findings(write):
    """The probe load `; x` has type Unknown here, but it is a discarded value, not a finding."""
    path = write("user.py", "x = eval('1')\nx = eval('2')\nprint(x)\n")
    flows = {(d.line, d.column) for d in gate.check([path]) if d.rule == "any-flow"}
    assert flows == {(3, 7)}  # the user's own load of x, not the probe loads on lines 1 and 2
