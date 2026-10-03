# `:ksp-fixtures:notebook-e2e`: pythonx-compose's `UI.ipynb`, end to end

Issue #26. A desktop host draws a Python-declared root through `python-multiplatform-compose`
(`PythonAppView(module = "pythonx.compose.runtime", attribute = "app_root")`, #18), with the **pythonx-compose wheel
installed**, and runs the notebook's scenarios against it. Each scenario is its own test, so a
regression fails under the notebook cell's number.

The specification is the user's notebook, `pythonx-compose/UI.ipynb` (read in place, never copied).
Where pythonx-compose has since decided a different spelling (its `docs/INTENT.md` §5 and
`docs/SPEC.md`), the tests use the decided one: `@app` and an `app_root` state instead of
`main.App.update(...)`; `.value` instead of `getValue()`/`setValue()`; Kotlin parameter names in
snake_case; `Color(0xFF...)`; `DefaultIcons.Add` without parentheses;
`TextField(state=..., modifier=Modifier.padding(8))`.

## Building the wheel

Build the wheel with uv from a clean copy of pythonx-compose's `develop`, so nothing is written into
that checkout:

```bash
mkdir -p .tmp/pxc-src
git -C ../pythonx-compose archive origin/develop | tar -x -C .tmp/pxc-src
(cd .tmp/pxc-src && uv build --wheel --out-dir ../pythonx-compose-dist)
```

The result is `.tmp/pythonx-compose-dist/pythonx_compose-<version>-py3-none-any.whl`.

## Running

```bash
JAVA_HOME=<JDK 21> ./gradlew :ksp-fixtures:notebook-e2e:desktopTest --rerun \
    -PpythonxComposeWheel=.tmp/pythonx-compose-dist --console=plain > .tmp/notebook-e2e.log 2>&1; echo "EXIT=$?"
```

| Input | Gradle property | Environment |
|---|---|---|
| the wheel: a `.whl` file, or a directory holding `pythonx_compose-*.whl` (relative paths are from the repository root) | `-PpythonxComposeWheel=<path>` | `PYTHONX_COMPOSE_WHEEL` |
| the version to pick from that directory (or to require of the file) | `-PpythonxComposeVersion=<version>` | `PYTHONX_COMPOSE_VERSION` |
| do not run the scenarios | `-PnotebookE2e.skip=true` | `NOTEBOOK_E2E_SKIP=true` |

**Without a wheel the tests fail, by name, with a message that says how to give one.** They do not
skip. Only `-PnotebookE2e.skip=true` disables `desktopTest`, and the build log then says that the
scenarios did not run. Which of the two CI wants is CI's decision; no workflow runs this module yet.

"Installing" is unpacking the pure-Python wheel into `build/pythonx-compose/site-packages`
(`installPythonxComposeWheel`), which is all installing a pure-Python wheel amounts to; the embedded
interpreter has no installer of its own. The tests then require `pythonx.compose.__file__` to be
inside that directory, so a stray source checkout on `sys.path` cannot stand in for the wheel.

## Scenarios

| Notebook cells | Test | What must hold |
|---|---|---|
| 5 | `NotebookRootTest.cell05_theImportsResolveFromTheInstalledWheel` | the cell's imports (decided spellings) resolve |
| 6–7 | `…cell06to07_importingMainDrawsTheAppItDeclares` | `import main` declares the root; the host draws exactly `Text(INITIAL)`; an idle frame does not rerun it |
| 9–13 | `…cell09to13_theNotebookAndTheScreenShareOneState` | the notebook reads the shown state, writes it, the screen follows; restoring restores the screen |
| 15–16 | `…cell15to16_redeclaringTheRootChangesTheScreenWithNoUpdateCall` | a cell redeclaring the root with `@app` puts it on screen within 4 frames, pixel-equal to Kotlin |
| (negative) | `…negative_withAppStubbedOutTheRedeclarationDoesNotReachTheScreen` | with `@app` stubbed to not write `app_root`, the same observation reports no change |
| (negative) | `…negative_aHostHandedTheRootOnceDoesNotFollowARedeclaration` | with the host given `main.App` instead of the state, the same observation reports no change |
| 19, 20 | `NotebookPracticeTest.cell19_text`, `cell20_textWithAFontSize` | red text; at 30 sp |
| 22, 23 | `…cell22_anEmptyBlackButton`, `cell23_aButtonHoldingText` | black `Button`, empty and with text |
| 25 | `…cell25_aCard` | red `Card` with white text |
| 28, 29, 30 | `…cell28_defaultIconsAddIsAnImageVector`, `cell29_anIcon`, `cell30_anIconInsideAButton` | `DefaultIcons.Add` is an `ImageVector`; `Icon` draws it, alone and in a button |
| 35, 36, 38 | `…cell35_aColumnOfTwoButtons`, `cell36_aColumnAlignedToTheEnd`, `cell38_aRow` | `Column`, `Column(horizontal_alignment=Alignment.Horizontal.End)`, `Row` |
| 37 (signature) | `…cell37_aRowWithAnArrangement` | `Row(horizontal_arrangement=Arrangement.Center)` |
| 40 | `…cell40_aCardSpacedBySpacers` | `Card`/`Column`/`Row` spaced by `Spacer(modifier=Modifier.padding(...))` |
| 31–33 | `NotebookTextFieldTest.cell32to33_aTextFieldKeepsInputMethodCompositionInComposeAndTheNotebookReadsTheResult` | see below |

Every practice cell is compared **pixel for pixel** with the same composables drawn from Kotlin, so a
pass means the screen shows what the cell declared.

### The text field (cells 31–33, pythonx-compose #10)

`TextField(state=remember_text_field_state(""))` is focused by a click (keyboard focus traversal if
the click does not do it), then receives a Hangul input-method sequence (composing ㅎ, 하, 한, then
committing 한) with a frame after each event. While it types, a `sys.monitoring` counter of every
Python function started (`PY_START`, all threads) must stay at zero and the root must not rerun.
Kotlin's `TextFieldState` must hold the composing text with composing range `0..1` at each step and
`한` with no composing range after the commit; the notebook then reads `fields[-1].text == "한"`.
Finally `set_text_and_place_cursor_at_end("x")` from outside the composition must reach the screen
within 4 frames.

**How the input method is driven.** `ImageComposeScene` (Compose Multiplatform 1.11.1) cannot take
input-method composition: it builds its scene with a `PlatformContext.Empty` delegate that never
hands out the text field's `PlatformTextInputMethodRequest`, has no parameter to replace it, and its
public input API is `sendKeyEvent` (key presses) and pointer events. `InputMethodScene` therefore
builds the same `CanvasLayersComposeScene` (`@InternalComposeUiApi`) with its own `PlatformContext`
that records the request, and delivers each event the way the desktop window's
`InputMethodSession.inputMethodTextChanged` does in 1.11.1: one `request.editText { commitText(committed, 1);
if (composing.isNotEmpty()) setComposingText(composing, 1) }`. Not exercised: the AWT layer above it
(decoding an `InputMethodEvent` into those two strings), which needs a window.

## Gaps: notebook content not expressed here

| Notebook | Why it is not in a test |
|---|---|
| cells 2–3 (`print`, numpy) | not UI |
| cell 5: `remember_saveable`, `DefaultCoroutineScope`, `MainCoroutineScope` | undecided in pythonx-compose (INTENT §4.1, SPEC §8); nothing provides them |
| cell 5/25/35/…: lower-case `modifier`, `modifier=modifier` | INTENT §5.4 replaces it with `modifier=Modifier`. The binder has no conversion for a proxy **class** passed as an argument (`Modifier.empty()` is used only for the class-spelling *method call*, `PythonxAdapter.kt` `_BoundMember`), so `Card(modifier=Modifier)` is not expected to work; the argument is left out, which is the same Kotlin default (`Modifier`). Not run to confirm. |
| cell 22/23/30/35: `corner_radius=` on `Button`; cell 25/40: on `Card` | Kotlin's counterpart is `shape=RoundedCornerShape(...)` from `androidx.compose.foundation.shape`, which this fixture does not walk (its package list is `:ksp-fixtures:compose`'s). Adding `"androidx.compose.foundation.shape"` to `artifactIncludePackages` is the next step; not tried. |
| cell 22/23/25/…: `color=` on `Button`/`Card` | written as Kotlin's `colors=ButtonColors(...)` / `CardColors(...)` constructors. `ButtonDefaults.buttonColors(...)`, the usual Kotlin spelling, is a `@Composable` object function, which the walker declines (SPEC B-1). |
| cell 27: `help(DefaultIcons)` | prints documentation; nothing to assert on screen |
| cells 42, 48: empty; "채팅 화면 디자인하기" | no content |
| cells 44–47: `from ws import client` | a websocket client outside pythonx-compose; marked "재 구현 필요" in the notebook |
| cells 51–56: Llama 3.1 streaming into the UI | needs a model and `main.run_llama3`; the state half (writing `.value` from a loop) is cell 12's mechanism |
| cell 54: `onclick=lambda: {...}` driving a state change | a click from Python reaching a Kotlin callback is `:ksp-fixtures:compose`'s `CallbackDrivenRenderTest`; not repeated here |
