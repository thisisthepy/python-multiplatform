# Generating `.pyi`, the Pythonic stub product (archived)

> **Superseded** by [`docs/design/pyi-generation-design.md`](../design/pyi-generation-design.md) and
> [SPEC.md](../SPEC.md) B-7 on 2026-10-03; the Pythonic stub product belongs to pythonx-compose; kept
> for history.
>
> These are the parts of `pyi-generation-design.md` (as of 2026-10-02) that designed a second,
> **Pythonic** stub product inside this repository's Gradle plugin: stubs under `pythonx.*`,
> snake_case names, `Dp | float` unions, keyword-only `content`, `@overload` sets, `Modifier` as a
> class of callback-Protocol attributes, and a `pythonx-map.toml` manifest mapping `androidx.*` to
> `pythonx.*`. It was implemented (`PythonNames.kt`, `PythonTypes.kt`, `StubManifest.kt`,
> `renderPythonicStubs`) and removed in `238119b7`, because the binder must never export a Kotlin
> namespace under another name (`docs/INTENT.md` §2.2, `AGENTS.md` §12.1). The plugin now emits only
> Kotlin-named stubs. The measurements below (mypy 2.3.0 runs, the metaclass failure, overload
> ordering, `py.typed` placement) are still valid inputs for whoever builds the Pythonic stubs in
> pythonx-compose.
>
> Section numbers are the original ones, so references such as "§4.4" in the code's history still
> resolve here. Paths in backticks are relative to the repository root. Cross-references to sections
> that were not moved (§0–§2, §3.2, §3.3, §4.1, §4.5, §6.2, §6.3) point at
> `docs/design/pyi-generation-design.md`.

---

## Original introduction

`pythonx` adapts Kotlin generically rather than wrapping function by function (`docs/design/ecosystem.md`
§5b): a module `__getattr__` finds the corresponding binding, adapts it once and caches it. Nothing
an editor can see is ever enumerated. `.pyi` is what pays that back, the stubs carry the fully
enumerated Pythonic surface the adapter will produce, so an IDE sees everything while the runtime
enumerates nothing. Generation belongs to the Gradle plugin, the way PyREPL did it; that is settled
and this file does not revisit it.

What this file does is answer the three questions that were open under that decision: **what the
generator reads**, **what it can honestly write**, and **where the result goes**.

Everything under "Observed" was read from working copies or run as a command on 2026-08-14, with the
path or the command given. Everything under "Judged" is a decision, and the alternatives it was
chosen over are named. Where a claim could not be checked it says so rather than being smoothed over.


---

## 3. Kotlin → Python type mapping

### 3.1 The table

`source` names where the fact comes from: `Km` = `kotlin-metadata-jvm`, `KS` = KSP, `desc` = JVM
descriptor, `list` = the hand-maintained allowlist of §3.4.

| Kotlin | `.pyi` | source | note |
|---|---|---|---|
| `Boolean` | `bool` | Km/KS | |
| `Byte` `Short` `Int` `Long` | `int` | Km/KS | the boundary carries every one as `Long`; Python has one integer type, so the narrowing is invisible and correct |
| `Float` `Double` | `float` | Km/KS | |
| `String` | `str` | Km/KS | |
| `ByteArray` | `bytes` | Km/KS | |
| `Unit` | `None` | Km/KS | return position only |
| `Nothing` | `typing.Never` | Km/KS | a function returning it never returns; `Never` is the exact Python spelling |
| `T?` | `T \| None` | `KmType.isNullable`, `KSType.isMarkedNullable` | **not bindable today**, `resolveKotlinType` declines nullable outright. §3.3 |
| `List<T>` `MutableList<T>` | `list[T]` | Km/KS type args | not bindable today (the type gate) |
| `Set<T>` | `set[T]` | | |
| `Map<K, V>` | `dict[K, V]` | | |
| `Collection<T>` `Iterable<T>` | `Iterable[T]` | | read-only shape; `Sequence` would over-promise indexing |
| `Array<T>` | `list[T]` | | judged: the marshaller's Python view is a list |
| `IntArray` `LongArray` `…` | `list[int]` etc. | desc/Km | `ByteArray` is the exception above |
| `() -> Unit` | `Callable[[], None]` | Km classifier `kotlin/Function0` | §3.5 |
| `(A) -> B` | `Callable[[A], B]` | `kotlin/FunctionN` type args | |
| `suspend (…) -> T` | - | `KmFunction.isSuspend`, `KSType.isSuspendFunctionType` | declined by both producers; must not be stubbed |
| value class on the allowlist | `Dp \| float` | Km + `list` | §3.4 |
| value class off the allowlist | `TextUnit` | Km | §3.4 |
| any other class/interface | its own stub name | Km/KS | requires that class to have a stub in the same namespace, which for a `TypeTag.OBJECT` handle is a promise the runtime does not yet keep, §7 |
| a generic type *parameter* (`fun <T> f(x: T)`) | - | | `BindingPolicy` rejects these before the binder sees them; stubbing what cannot be called would be a lie. §7 |

> Archive note: this table maps the **declared Kotlin type**; it was implemented as `PythonTypes.kt`
> for the Pythonic product and removed with it. The current stubs annotate the **boundary** type
> instead, see `docs/design/pyi-generation-design.md` §3.1.

### 3.4 Value classes, how the allowlist shows up

`docs/design/kotlin-extensions-in-python.md` §4.4 decided this and measured why a machine rule fails: the
rule "coerce iff the value class has a public constructor taking exactly its underlying type"
selects 36 of 111 and **admits `Color`**, whose public `ULong` constructor stores while the factory
a Kotlin author actually writes (`Color(Int)`) shifts by 32. Whether a wrapper packs is a semantic
fact with no bytecode witness.

In a stub the distinction is one union member:

| Kotlin parameter | `.pyi` | why |
|---|---|---|
| `Dp` (on the allowlist) | `Dp \| float` | public constructor, wraps a plain `Float`, and it *is* the identity on that float. 62% of value-class parameter occurrences on `Modifier` |
| `TextUnit` (rejected) | `TextUnit` | packed `Long`; raw `16` decodes as `Unspecified`, silently no value at all |
| `Color` (rejected despite passing the constructor test) | `Color` | `Color(int)` is `ULong(value) shl 32`; raw `0xFFFF0000` is transparent black through the constructor and red through the factory |
| every `packedValue` class (`Offset`, `Size`, `DpSize`, `IntSize`, `IntOffset`, `DpOffset`, `TransformOrigin`, `CornerRadius`, `Constraints`) | the proxy alone | a user-meaningful number is not the stored number, and none of these failures raises |

**Measured, §4.4's asymmetry is expressible and enforced.** In the mypy run of §4.4,
`Text("hi", font_size=16)` produced

    error: Argument "font_size" to "Text" has incompatible type "int"; expected "TextUnit"

while `Modifier.padding(16)` in the same file checked clean. So the stub is not merely documentation
of the asymmetry, a checker acts on it, and the user finds out at edit time rather than seeing a
label render with no font size.

What a stub cannot carry is §4.4's *third* clause, the error message that names the constructor to
call (`"expected TextUnit; write sp(16)"`). mypy's own message names the type but not the remedy.
Judged: accept it. The runtime raises the full message; the stub gets the user to the runtime less
often.

**Where the allowlist lives** is `docs/design/kotlin-extensions-in-python.md` §6's open question, and §5.3
here proposes an answer that falls out of the package mapping: the same manifest.

### 3.5 Function types, and Compose's `content`

`() -> Unit` → `Callable[[], None]`, mechanically, from `kotlin/Function0`'s type arguments. 66 of
the 177 chainable `Modifier` extensions take at least one lambda, and every `@Composable` container
takes `content`.

Two things the stub should do and one it should not:

- **`content` is keyword-only in Python** even though it is the last positional parameter in Kotlin.
  Compose's trailing-lambda syntax has no Python equivalent, and `Column(modifier, lambda: ...)`
  reads badly. Emitting `*, content: Callable[[], None]` forces `content=`. Checked in §4.4's run:
  `Column(modifier=Modifier.size(4), content=lambda: Text("x"))` resolves clean.
- **`@Composable` on a lambda parameter is not expressible and should not be faked.** A
  `@Composable () -> Unit` and a `() -> Unit` are the same Python `Callable[[], None]`. Kotlin's
  restriction, a composable lambda may only be invoked in a composable context, has no Python
  counterpart, and inventing a distinct alias would give a checker a rule it cannot enforce.
  `docs/design/ecosystem.md` §5b already decided the composer is threaded as an ordinary value.
- **Do not stub the synthetic parameters.** `$composer` and `$changed` are in the JVM descriptor and
  are not in `KmFunction`. `docs/design/kotlin-extensions-in-python.md` §4.6: metadata arity is the correct
  source, and any code deriving arity from the descriptor is wrong for all 500 composables.

### 3.6 Overloads

Python has `@overload`, and `docs/design/kotlin-extensions-in-python.md` §4.5 already calls it mandatory:
33 of `Modifier`'s 130 names carry more than one, and they are the load-bearing ones
(`padding`, `size`, `background`, `border`, `clickable`, …).

**Measured** (`/Volumes/macMini/tmp/pyicheck/ovstub.pyi`, all four real `Modifier.padding`
overloads from §3 of that document, as a callback protocol):

```python
class _padding(Protocol):
    @overload
    def __call__(self, paddingValues: PaddingValues) -> Modifier: ...
    @overload
    def __call__(self, all: Dp | float) -> Modifier: ...
    @overload
    def __call__(self, horizontal: Dp | float = ..., vertical: Dp | float = ...) -> Modifier: ...
    @overload
    def __call__(self, start: Dp | float = ..., top: Dp | float = ...,
                 end: Dp | float = ..., bottom: Dp | float = ...) -> Modifier: ...
```

All four coexist. `p(8)`, `p(horizontal=8, vertical=4)`, `p(start=1, bottom=2)` and
`p(PaddingValues())` each resolve to `Modifier`; `p("bad")` is rejected with all four variants
listed. mypy issued **no** overlap diagnostic.

That last fact is the one to be careful about. mypy reports overlapping overloads only when their
*return types* are incompatible, and every `Modifier` extension returns `Modifier`. So variants 2 and
3 genuinely overlap, `p(8)` matches `all` and also matches `horizontal` positionally, and the
checker silently takes the **first** match.

**Judged: overload order is part of the generated output and must be deterministic and
arity-ascending.** In the run above, arity-ascending put `all: Dp` before `horizontal, vertical`, and
`p(8)` selected `all`, the right answer, but only because of the order. Two further consequences:

- The stub's order must be **the same order the Python-side dispatcher resolves in**. If the runtime
  dispatcher (`docs/design/kotlin-extensions-in-python.md` §6, unsolved) picks differently, the stub lies
  about which Kotlin function runs. The two orders must come from one place in the generator.
- **Defaults are emitted as `= ...`.** 254 of 435 `Modifier` extension parameters declare one and
  metadata carries `declaresDefaultValue` but not the *value*. `= ...` is the correct stub spelling
  for "has a default I cannot name" and is what makes the keyword-only overloads above work.

Kotlin parameter names go through the same snake_case conversion as everything else
(`paddingValues` → `padding_values`), which `pythonx-compose` already does by hand: `text.py` maps
`font_size` → `fontSize`, `letter_spacing` → `letterSpacing`, and 14 more.

## 4. Extension functions, and the metaclass proposal does not survive contact

This is the section the Compose surface turns on: 604 public top-level extension functions over 136
receivers, and 177 chainable ones on `Modifier` alone.

(§4.1, Placement, stayed in `docs/design/pyi-generation-design.md`.)

### 4.2 The proposal under test

`docs/design/kotlin-extensions-in-python.md` §4.2 and §4.5 propose that `Modifier` be one class whose class
object also behaves like an instance, via a metaclass, so that `Modifier.padding(16)`,
`m.padding(16)` and `def f(m: Modifier)` all work:

```python
class _ModifierMeta(type):
    @overload
    def padding(cls, all: Dp | float) -> Modifier: ...
    ...

class Modifier(metaclass=_ModifierMeta):
    @overload
    def padding(self, all: Dp | float) -> Modifier: ...
    ...
```

### 4.3 Observed: it does not work, at run time or in a checker

**Run time** (CPython 3.13, `/Volumes/macMini/tmp/pyicheck`):

```python
class Meta(type):
    def padding(cls, v): return ("META", v)
class Modifier(metaclass=Meta):
    def padding(self, v): return ("INSTANCE", self, v)

Modifier.padding        # -> <function Modifier.padding at 0x100f6b4c0>
Modifier.padding(16)    # -> TypeError: Modifier.padding() missing 1 required positional argument: 'v'
```

This is `type.__getattribute__`'s documented order: a metaclass attribute wins **only if it is a data
descriptor**; otherwise the class's own MRO is searched first, and a plain function on the metaclass
is a non-data descriptor. So `Modifier.padding` is the *unbound instance method*, and `16` is being
bound to `self`. It raises here only because the arities differ by one; it would bind silently for
any pair that happened to match.

**mypy 2.3.0**, on exactly the stub of §4.2:

    reveal_type(Modifier.padding)
    note: Revealed type is "Overload(def (self: Modifier, all: Dp | float) -> Modifier, ...)"

    Modifier.padding(16)
    error: No overload variant of "padding" of "Modifier" matches argument type "int"
    note: Revealed type is "Any"

The checker reproduces the runtime rule: the metaclass declaration is invisible, `Modifier.padding`
is the instance method, and every class-object spelling fails. Ten errors in a twenty-line file, and
the only lines that passed were the instance ones.

**The fix that works at run time does not fix the checker.** A `property` on the metaclass *is* a
data descriptor and does win:

```python
class Meta2(type):
    @property
    def padding(cls): return cls.EMPTY.padding
Modifier2.padding(16)     # -> ('INSTANCE', <Modifier2 object>, 16)   correct
```

as does a hybrid descriptor in the class body with no metaclass at all:

```python
class hybrid:
    def __init__(self, fn): self.fn = fn
    def __get__(self, obj, owner): return self.fn.__get__(obj if obj is not None else owner.EMPTY, owner)
Modifier3.padding(16)     # -> ('BOUND-TO', <Modifier3 object>, 16)   correct
Modifier3().padding(16)   # -> correct
```

But declaring the metaclass side as `@property -> _PaddingCall` in the stub changes nothing for
mypy, it still resolves the class's own method and produces the same twelve errors. **mypy does not
model metaclass data-descriptor precedence.**

### 4.4 Judged: callback-Protocol attributes, and the stub need not mirror the runtime mechanism

The shape that satisfies all three spellings is to declare each operation on the class as an
**attribute whose type is a Protocol with an overloaded `__call__`**:

```python
class _Modifier_padding(Protocol):
    @overload
    def __call__(self, all: Dp | float) -> Modifier: ...
    @overload
    def __call__(self, *, horizontal: Dp | float = ..., vertical: Dp | float = ...) -> Modifier: ...

class Modifier:
    padding: ClassVar[_Modifier_padding]
    size: ClassVar[_Modifier_size]
    background: ClassVar[_Modifier_background]
```

**Measured**, the same twenty-line user file that produced ten errors against §4.2 produces exactly
the two intended ones:

| expression | revealed / result |
|---|---|
| `Modifier.padding` | `_Modifier_padding` |
| `Modifier.padding(16)` | `Modifier` |
| `Modifier.padding(dp(16))` | `Modifier` |
| `Modifier.padding(horizontal=8, vertical=4)` | `Modifier` |
| `Modifier.padding(16).background(Color(0xFFFF0000))` | `Modifier` |
| `Modifier.size(4).padding(2)` | `Modifier` |
| `m.padding(16)` where `m: Modifier` | `Modifier` |
| `takes_modifier(Modifier.padding(16))` where `def takes_modifier(m: Modifier)` | clean |
| `Text("hi", font_size=16)` | **error**, `int` is not `TextUnit` (intended, §3.4) |
| `Modifier.padding("nope")` | **error**, with all overload variants listed (intended) |

`ClassVar` is not load-bearing for inference, a plain `padding: _Modifier_padding` gives byte-identical
results, but it is the honest annotation, and it stops a checker accepting `m.padding = something`.

The point that makes this legitimate rather than a trick: **a `.pyi` completely replaces the `.py`
for a checker, so the stub's mechanism and the runtime's mechanism do not have to agree, only the
types do.** The runtime uses the hybrid descriptor of §4.3, which binds `self` correctly for both
spellings; the stub says "a callable attribute", which is what a checker can act on. The one
inaccuracy is that a checker believes `Modifier.padding` and `m.padding` are the same object, and
nothing observable depends on that.

**Rejected:**

| alternative | why not |
|---|---|
| the metaclass of §4.2 as written | measured not to work, in both mypy and CPython (§4.3) |
| metaclass whose members are `@property -> Protocol` | works at run time, does not work in mypy, the class's own member still wins. Would give a *correct runtime* with a *wrong IDE*, which is the worst of the two |
| bind the module-level name `Modifier` to the companion instance | already rejected in §4.2 of the other document and still right: it destroys `Modifier` as an annotation, and the stubs exist so that annotation works |
| drop the `Modifier.padding(...)` spelling, require `Modifier().padding(...)` | changes the API away from Kotlin's for a reason that is an implementation detail of Python attribute lookup |
| emit both the metaclass **and** the Protocol attributes | the class attribute shadows the metaclass in both engines, so the metaclass is dead text that a reader will believe |

**Cost, stated:** one Protocol class per `(receiver, name)` pair. For `Modifier` that is 130 extra
class definitions in `pythonx/compose/ui/__init__.pyi`; across the 507 distinct `(receiver, name)`
pairs in corpus A, ~507 if every receiver is stubbed. They are generated, never read by a human, and
`.pyi` files are not executed, but a 500-class stub file is a real indexing cost for an IDE and it
has not been measured.

**Not verified: PyCharm.** Callback protocols are standard typing and PyCharm supports `Protocol`,
but whether its inference resolves `Modifier.padding(16)` through a `ClassVar[Protocol]` was not
tested. **This is the single most load-bearing unverified claim in this document**, §4.4 is chosen
over §4.2 on mypy evidence alone, and if PyCharm follows the runtime MRO rule the way mypy does then
§4.4 works there too, while if it has its own metaclass handling the conclusion could differ.
Verify before implementing.

(§4.5, Member extensions, stayed in `docs/design/pyi-generation-design.md`.)

---

## 5. Package mapping

### 5.1 Observed: the shape of `pythonx-compose`

`/Volumes/macMini/thisisthepy/pythonx-compose/pythonx/` contains **only** `compose/`, and there is
**no `pythonx/__init__.py`**, `pythonx` is an implicit namespace package, so several distributions
can contribute subpackages under it. `pythonx/compose/` has `__init__.py`, `layout/`, `lite/`,
`material3/`, `native/`, `runtime/`, `test/`, `ui/`, `ui/unit/`, `wrapper/`.

### 5.2 Observed: it is not a rule

`unzip -l` over the Compose Multiplatform desktop jars vendored at
`pythonx/compose/lite/release/app/` shows that the `org.jetbrains.compose.*` artefacts contain
`androidx.compose.*` **packages**:

    foundation-layout-desktop-1.6.11-*.jar  ->  androidx/compose/foundation/layout
    ui-unit-desktop-1.6.11-*.jar            ->  androidx/compose/ui/unit
    material-desktop-1.6.11-*.jar           ->  androidx/compose/material

so the Kotlin namespace is `androidx.compose.*` on desktop and Android alike, and one mapping covers
both. Against the actual directories:

| `pythonx` module | Kotlin package | mechanical? |
|---|---|---|
| `pythonx.compose.material3` | `androidx.compose.material3` | yes |
| `pythonx.compose.runtime` | `androidx.compose.runtime` | yes |
| `pythonx.compose.ui` | `androidx.compose.ui` | yes |
| `pythonx.compose.ui.unit` | `androidx.compose.ui.unit` | yes |
| `pythonx.compose.layout` | `androidx.compose.foundation.layout` | **no, `foundation.` is dropped** |
| `pythonx.compose.wrapper`, `.native`, `.lite`, `.test` | none | no counterpart |

The `layout` entry is not inferred. `pythonx/compose/layout/arrangement.py` line 7 reads
`jclass("androidx.compose.foundation.layout.Arrangement")`, and `pythonx/compose/layout/__init__.py`
re-exports `Arrangement` from it.

### 5.3 Judged: a rule plus a manifest the Python package owns

**`androidx.` → `pythonx.` is the default rule; deviations come from a manifest, and the manifest
belongs to the Python package, not to the plugin.**

The reason is ownership, not convenience. Which Kotlin package a `pythonx` module wraps is
`pythonx-compose`'s design decision, the plugin has no basis on which to invent
`pythonx.compose.layout` for `androidx.compose.foundation.layout`, and hard-coding Compose's
particular renames into a general-purpose Gradle plugin would make every other library's mapping
unreachable.

So: a data file inside the Python distribution, e.g. `pythonx/compose/pythonx-map.toml`, declaring

    [modules]
    "pythonx.compose.layout"  = "androidx.compose.foundation.layout"
    "pythonx.compose.ui.unit" = "androidx.compose.ui.unit"

    [value-classes]
    raw-primitive-allowed = ["androidx.compose.ui.unit.Dp"]

The plugin reads it from the resolved Python package and emits `pythonx.*` stubs accordingly; with
no manifest it emits only the Kotlin-FQN stubs. This also gives
`docs/design/kotlin-extensions-in-python.md` §6's open question ("whether the allowlist should be data or
code, and where a downstream consumer adds to it") a location: the same manifest, in the same
package, next to the code whose surface it describes.

**Consequence: there are two stub products, from one model.**

| product | namespace | content | mechanical? |
|---|---|---|---|
| Kotlin-FQN stubs | `androidx.compose.material3`, `junit.runner`, … | 1:1 with the modules `PythonProxySource` injects into `sys.modules`; Kotlin names, no snake_case, no extension-as-method | yes |
| Pythonic stubs | `pythonx.compose.material3`, … | snake_case, extension-as-method (§4.4), value-class allowlist (§3.4), keyword-only `content` (§3.5) | needs the manifest |

The first is the direct descendant of PyREPL's output and is the only thing an IDE could ever see for
those modules, since they exist only as runtime `sys.modules` entries and never as files. The second
is what a user actually imports.

**Not verified:** no such manifest exists in `pythonx-compose` today, and this proposal has not been
agreed with that repository. It is a judgement about where the information has to live, not a
description of anything.

---

## 6. Where the files go, the parts about the Pythonic product

From §6.1 (measurements 3 and 4 stayed in `docs/design/pyi-generation-design.md`):

Five mypy runs in `/Volumes/macMini/tmp/pyicheck`, each reported above or below:

1. **A `.pyi` beside a `.py` wins.** The fixture's `pythonx/compose/ui/__init__.py` contains nothing
   but `def __getattr__(name): raise AttributeError(name)`, the shape the real adapter will have,
   and every name in §4.4's table resolved from `__init__.pyi`. This is the deployment that matters:
   the dynamic adapter ships, the stub ships beside it, and the checker never sees the dynamism.
2. **`py.typed` is mandatory once the package is installed.** With `pythonx/` copied into the venv's
   `site-packages` and `pythonx/compose/py.typed` present, everything resolved. Deleting **only**
   `py.typed`:

       error: Skipping analyzing "pythonx.compose.ui": module is installed, but missing library
              stubs or py.typed marker  [import-untyped]
       note: Revealed type is "Any"        (every line)

   The marker goes in the top-level *regular* package of the distribution, here `pythonx/compose/`,
   because `pythonx` is a namespace package (§5.1). That placement was the one tested and it works.

From §6.2:

- **For a published `pythonx` wheel the stubs belong inside the wheel**: `.pyi` beside `.py` plus
  `py.typed`, both verified in §6.1, built by `pypackpack`'s bundle stage, not regenerated per
  consumer build. The plugin's per-build generation covers the consumer's own Kotlin and the
  third-party jars *its* build resolves; a shipped `pythonx-compose` covers Compose once.

Two audiences, two destinations, one generator.

---

## 7. Open, the items that belonged to the Pythonic product

- **PyCharm.** §4.4 rests entirely on mypy. Verify `ClassVar[Protocol]` resolution, `@overload`
  ordering, and `.pyi`-beside-`.py` precedence in PyCharm before implementing. If PyCharm does model
  metaclass data descriptors, §4.2's shape becomes viable again there and the two engines would want
  different stubs, which would be a genuinely new problem.
- **The 500-class stub cost.** ~507 callback Protocols for corpus A's `(receiver, name)` pairs, and
  130 for `Modifier` alone in one file. Not measured for IDE indexing time or for mypy's own runtime.
- **Whether any real Compose overload set collapses.** §3.6 shows four `padding` overloads coexisting,
  but two Kotlin overloads can map to identical Python signatures once `Dp | float` widening is
  applied. The generator must detect that and drop or qualify, and how often it happens across the
  33 multi-overload `Modifier` names is unknown, it needs the scan to be run.
- **Stub order vs dispatcher order.** §3.6: the `@overload` order and the Python-side dispatcher's
  resolution order must come from one place. The dispatcher does not exist
  (`docs/design/kotlin-extensions-in-python.md` §6).
  *Archive note (2026-10-03): the dispatcher now exists, `_Overloads` in the binding layer
  `python_multiplatform.binding` (`PythonxAdapter.kt`) serves the base name of an overload set.*
- **klib.** §0. The `pythonx.*` surface on iOS and androidNative has no producer yet.
- **The manifest of §5.3** does not exist and has not been agreed with `pythonx-compose`.
