"""A small consumer of the generated Kotlin-name stubs, type-checked by `check-stubs.sh` (issue #31).

It is the code a user writes against the binder -- Kotlin names, Kotlin parameter names -- and it
must type-check against the stubs generated for the real Compose jars: a Modifier chain started from
the class and continued from an instance, `Checkbox`, `Text`, and a container with its trailing
`content` lambda. The last four lines are **meant** to be rejected; they carry `# type: ignore[...]`
and mypy runs with `warn_unused_ignores`, so a stub that stopped rejecting one fails the check too.
"""

from androidx.compose.foundation.layout import Column, Row, fillMaxWidth, padding__Dp
from androidx.compose.material3 import Checkbox, Text
from androidx.compose.ui import Modifier

# A chain started from the class, the way Kotlin writes `Modifier.padding(16.dp)`.
chain: Modifier = Modifier.fillMaxWidth().padding(16.0).size(24.0)
# ... and continued from an instance, and with Kotlin parameter names.
more: Modifier = chain.padding(horizontal=8.0, vertical=4.0).padding(start=1.0, bottom=2.0)
# The explicit table-key spelling, as an attribute and as a module function.
explicit: Modifier = chain.padding__Dp(4.0)
by_function: Modifier = padding__Dp(chain, 4.0)
by_extension: Modifier = fillMaxWidth(chain, fraction=0.5)

checked = True


def on_change(value: bool) -> None:
    global checked
    checked = value


Checkbox(checked, on_change, modifier=more, enabled=True)
Checkbox(checked=False, onCheckedChange=None)
Text("hello", modifier=Modifier.padding(8.0))


def content() -> None:
    Text("inside")


Column(modifier=chain, content=lambda scope: None)
Row(content=lambda scope: None)

# Rejected on purpose. (`padding("sixteen")` is not among them: one `padding` overload takes a
# `PaddingValues`, a type the stubs cannot name -- `PaddingValues(...)` the factory owns the name -- so
# it is `Any` and accepts anything.)
Modifier.fillMaxWidth("wide")  # type: ignore[arg-type]
Checkbox("not a bool", on_change)  # type: ignore[call-overload]
Checkbox(checked, on_change, enabled="yes")  # type: ignore[call-overload]
Column(chain)  # type: ignore[call-arg]
