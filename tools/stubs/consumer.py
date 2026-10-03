"""A small consumer of the generated Kotlin-named stubs, type-checked by `check-stubs.sh` (issue #31).

It is the code a user writes against the binder, and it must type-check against the stubs generated
for the real Compose jars: a Modifier chain started from the class and continued from an instance,
`Checkbox`, `Text`, and a container with its trailing `content` lambda.

Names: a declaration is reachable by its Kotlin name and by its Pythonic (snake_case) alias, and the
stubs carry both (issue #131), so both spellings are written below. Keywords: a stub can name a
parameter only once, and it names it the way `inspect.signature` does -- by the Pythonic keyword
(`on_checked_change`). The Kotlin keyword (`onCheckedChange=`) still works at run time; a checker
reports it, so it is not written here.

The last four lines are **meant** to be rejected; they carry `# type: ignore[...]` and mypy runs
with `warn_unused_ignores`, so a stub that stopped rejecting one fails the check too.
"""

from typing import assert_type

from androidx.compose.foundation.layout import (
    Arrangement,
    Column,
    Row,
    fill_max_width,
    fillMaxWidth,
    padding__Dp,
)
from androidx.compose.material.icons import Icons
from androidx.compose.material3 import Checkbox, Icon, Text
from androidx.compose.ui import Modifier
from androidx.compose.ui.graphics.vector import ImageVector

# A chain started from the class, the way Kotlin writes `Modifier.padding(16.dp)`.
chain: Modifier = Modifier.fillMaxWidth().padding(16.0).size(24.0)
# ... and continued from an instance, with keyword arguments.
more: Modifier = chain.padding(horizontal=8.0, vertical=4.0).padding(start=1.0, bottom=2.0)
# The explicit table-key spelling, as an attribute and as a module function.
explicit: Modifier = chain.padding__Dp(4.0)
by_function: Modifier = padding__Dp(chain, 4.0)
by_extension: Modifier = fillMaxWidth(chain, fraction=0.5)

# Issue #131: the Pythonic alias of a module function and of a method is the same declaration.
by_alias: Modifier = fill_max_width(chain, fraction=0.5)
assert_type(fill_max_width(chain), Modifier)
pythonic_chain: Modifier = Modifier.fill_max_width().padding(16.0).fill_max_width(0.5)

checked = True


def on_change(value: bool) -> None:
    global checked
    checked = value


Checkbox(checked, on_change, modifier=more, enabled=True)
Checkbox(checked=False, on_checked_change=None)
Text("hello", modifier=Modifier.padding(8.0))


def content() -> None:
    Text("inside")


Column(modifier=chain, content=lambda scope: None)
Row(content=lambda scope: None)
# `SpaceBetween` is a `HorizontalOrVertical`: a `Vertical` slot and a `Horizontal` slot both take it (#71).
Column(vertical_arrangement=Arrangement.SpaceBetween, content=lambda scope: None)
Row(horizontal_arrangement=Arrangement.SpaceBetween, content=lambda scope: None)

# An extension property on a type nested in an object (issue #68): `Icons.Default` is `Icons.Filled`.
# `assert_type`, not an annotation: an `Any` (the stub before #53/#67) would satisfy an annotation.
assert_type(Icons.Default.Add, ImageVector)
Icon(Icons.Default.Add, content_description=None)

# Rejected on purpose. (`padding("sixteen")` is not among them: one `padding` overload takes a
# `PaddingValues`, a type the stubs cannot name -- `PaddingValues(...)` the factory owns the name -- so
# it is `Any` and accepts anything.)
Modifier.fill_max_width("wide")  # type: ignore[arg-type]
Checkbox("not a bool", on_change)  # type: ignore[call-overload]
Checkbox(checked, on_change, enabled="yes")  # type: ignore[call-overload]
Column(chain)  # type: ignore[call-arg]
