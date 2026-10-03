"""The §3.1 forbidden list: warning under `checked`, error under `compiled`."""
import pytest

from conftest import rules
from typedpython import forbidden, gate

FLAGGED = {
    "forbidden/eval": "def f(s: str) -> None:\n    eval(s)\n",
    "forbidden/exec": "def f(s: str) -> None:\n    exec(s)\n",
    "forbidden/dynamic-attr": "def f(o: object, n: str) -> None:\n    getattr(o, n)\n",
    "forbidden/monkeypatch": "import json\n\ndef f() -> None:\n    json.dumps = print\n",
}


@pytest.mark.parametrize("rule", sorted(FLAGGED))
def test_rule_is_found(rule):
    assert rule in {f.rule for f in forbidden.scan(FLAGGED[rule], "user.py")}


@pytest.mark.parametrize("call", ["setattr(o, n, 1)", "delattr(o, n)"])
def test_other_dynamic_attribute_calls(call):
    source = f"def f(o: object, n: str) -> None:\n    {call}\n"
    assert {f.rule for f in forbidden.scan(source, "user.py")} == {"forbidden/dynamic-attr"}


def test_monkeypatching_a_module_level_class():
    source = (
        "class Greeter:\n    pass\n\n"
        "def patch() -> None:\n    Greeter.hello = 1\n"
    )
    assert {f.rule for f in forbidden.scan(source, "user.py")} == {"forbidden/monkeypatch"}


@pytest.mark.parametrize("source", [
    "def f(o: object) -> None:\n    getattr(o, 'name')\n",
    "class C:\n    def __init__(self) -> None:\n        self.x = 1\n",
    "def f() -> None:\n    class Local:\n        pass\n    obj = Local()\n    obj.y = 2\n",
])
def test_static_forms_are_not_flagged(source):
    assert forbidden.scan(source, "user.py") == []


def test_findings_carry_a_location():
    [finding] = forbidden.scan("x = 1\neval('x')\n", "user.py")
    assert (finding.line, finding.column) == (2, 1)


def test_severity_follows_the_mode(write):
    path = write("user.py", "def f(s: str) -> None:\n    eval(s)\n")
    checked = gate.check([path], mode="checked")
    compiled = gate.check([path], mode="compiled")
    assert [d.severity for d in checked if d.rule == "forbidden/eval"] == ["warning"]
    assert [d.severity for d in compiled if d.rule == "forbidden/eval"] == ["error"]
    assert rules(checked) == rules(compiled)
