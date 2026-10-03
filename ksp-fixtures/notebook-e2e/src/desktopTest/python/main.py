"""The application module `UI.ipynb` imports as `main` (cell 6), written with pythonx-compose's API.

The notebook predates two decisions (pythonx-compose `docs/INTENT.md` section 5.1), and this module
follows them rather than the notebook's text:

- The screen is *declared* with `@app`; there is no `main.App.update(...)`. Redeclaring a root in a
  notebook cell is what replaces the screen.
- State is a Compose state read and written through `.value` (`messages.value`), not
  `getValue()` / `setValue()`, and it lives at module level (`main.messages`) rather than as an
  attribute of the root function (`main.App.messages`).

`calls` counts how often each root ran, so a test can tell a recomposition from a redeclaration and
an idle frame from a poll. It is the test's instrument, not part of the notebook.
"""

from pythonx.compose.runtime import Composable, app, state
from pythonx.compose.material3 import Text

INITIAL_MESSAGE = "안녕하세요, Python 앱입니다."

messages = state(INITIAL_MESSAGE)

calls = {"App": 0}


@app
@Composable
def App():
    calls["App"] += 1
    Text(messages.value)
