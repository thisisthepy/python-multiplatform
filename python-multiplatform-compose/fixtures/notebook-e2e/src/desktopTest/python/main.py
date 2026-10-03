"""The application module `UI.ipynb` imports as `main` (cell 6), written with pythonx-compose's API.

It follows the notebook's own spelling, as pythonx-compose ships it: the screen is *declared* with
`@app` (there is no `main.App.update(...)`), and the UI state is the root's `remember_saveable`
value, which the app attaches to the function itself (`App.messages`), so the notebook reads and
writes it as `main.App.messages.getValue()` / `setValue(...)` (cells 9-13).

`calls` counts how often each root ran, so a test can tell a recomposition from a redeclaration and
an idle frame from a poll. It is the test's instrument, not part of the notebook.
"""

from pythonx.compose.runtime import Composable, app, remember_saveable
from pythonx.compose.material3 import Text

INITIAL_MESSAGE = "안녕하세요, Python 앱입니다."

calls = {"App": 0}


@app
@Composable
def App():
    calls["App"] += 1
    App.messages = messages = remember_saveable(INITIAL_MESSAGE)
    Text(messages.getValue())
