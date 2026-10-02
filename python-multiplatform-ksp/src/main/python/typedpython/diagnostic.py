from dataclasses import dataclass, asdict
from typing import Literal

Severity = Literal["error", "warning"]


@dataclass(frozen=True, order=True)
class Diagnostic:
    path: str
    line: int
    column: int
    rule: str
    severity: Severity
    message: str

    def to_json(self) -> dict[str, object]:
        return asdict(self)
