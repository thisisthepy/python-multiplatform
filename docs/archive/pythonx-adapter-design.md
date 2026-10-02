# `pythonx` — adapting generated Kotlin bindings without wrapping them one at a time

> **Superseded** by [SPEC.md](../SPEC.md) U-7/U-8,
> [`KotlinSurface.kt`](../../python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/KotlinSurface.kt)
> and [`docs/design/kotlin-extensions-in-python.md`](../design/kotlin-extensions-in-python.md) on
> 2026-10-03; kept for history.
>
> **What is superseded:** the binder serving `pythonx` itself — it synthesised `pythonx.*` modules
> (with `__path__ = []`, so the real on-disk package could not load) and renamed members and
> parameters to snake_case (§3's rule). Since `238119b7` the binder serves Kotlin-named modules under
> Kotlin names only, with keyword arguments by Kotlin parameter name and Kotlin defaults; its layer is
> `python_multiplatform.binding`
> ([`PythonxAdapter.kt`](../../python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/pythonx/PythonxAdapter.kt)),
> and `pythonx` is a real package in the pythonx-compose repository (`AGENTS.md` §12.1–12.2).
> Wherever this file says `pythonx._adapt`, `pythonx._bind_composable` or "the `pythonx` adapter",
> read "the binding layer" for mechanics and "pythonx-compose" for naming.
>
> **Still true, and where it lives now:** the binding-layer mechanics — lazy finder plus module
> `__getattr__` (§2.3, §4.1), extension functions as receiver methods through a hybrid descriptor
> (§4.2), the value-class allowlist (§4.4, now an empty runtime seam `allow_raw_primitive` that
> pythonx-compose fills), the composer stack and the `$default` mask computed per call (§5.6) — are
> in `PythonxAdapter.kt`, whose KDoc still cites this file's section numbers. The `@Composable`
> measurements (§5.2, §5.6) are summarised in `docs/design/kotlin-extensions-in-python.md` §2.3 and
> §4.6 and pinned by `ComposableBindingTest` and `ksp-fixtures/compose`. The `Modifier` and material3
> render proofs (§9, §10) are the `ksp-fixtures/compose` render tests cited by SPEC B-5/B-6. The
> naming conventions (§3) and the `Modifier` metaclass (§4.3) belong to pythonx-compose.

The 2024 `pythonx-compose` wrote a Python class per Compose component. 37 files, 28 of them empty,
and the one that mattered most — `pythonx/compose/ui/modifier.py` — is a copy of the Button wrapper
in which `padding()` composes nothing and `fill_max_size()` returns `self`. The chain was never
built. That is the cost this document exists to remove.

`docs/design/ecosystem.md` §5b already decided the shape: `pythonx.*` is ours, `androidx.*` is the original
Kotlin, the wrapping happens in Python, and the layer *adapts generically* rather than enumerating.
This file works out what "generically" has to mean given what the runtime actually offers today, and
names the places where it does not offer enough.

Everything under **Read** was read from the working copies on 2026-08-14 and the file is named.
Everything under **Measured** was produced by running a tool and the command is given. Everything
under **Judged** is a decision with its alternatives named. Everything under **Open** is not decided,
and no sentence in this file fills one of those in.

Sources read for this document:

    PythonMultiplatform  python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/PythonProxySource.kt
                         python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/UpcallTrampoline.kt
                         python-multiplatform/src/commonMain/kotlin/python/multiplatform/reflection/{ExposedCallable,ReflectedClass,ObjectReference}.kt
                         python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/artifact/{ArtifactScanner,ArtifactRendering,JvmDescriptors,KotlinMetadata}.kt
                         docs/design/ecosystem.md §5b, docs/design/kotlin-extensions-in-python.md
    pythonx-compose      UI.ipynb (all 57 cells), pythonx/compose/runtime/__init__.py,
                         pythonx/compose/material3/{buttons,cards,text}.py, pythonx/compose/ui/{__init__,modifier,alignment}.py,
                         pythonx/compose/layout/{__init__,arrangement}.py, pythonx/compose/wrapper/__init__.py

---

## 1. Read — what the 2024 code did, and the part of it that cannot have run

Two conventions coexist in `pythonx-compose`, and they disagree.

**The library source is strict snake_case with a hand-written map back to camelCase.**
`pythonx/compose/material3/text.py` declares `font_size`, `letter_spacing`, `on_text_layout`,
`soft_wrap`, `max_lines`, `min_lines` and then rebuilds a kwargs dict spelling them `fontSize`,
`letterSpacing`, `onTextLayout`, `softWrap`, `maxLines`, `minLines`. `buttons.py` does the same for
`on_click`, `content_padding`, `interaction_source`, five times over — the five button variants are
five copies of one 60-line body differing only in the name they look up.

**The notebook is not that code.** `docs/design/ecosystem.md` §3 established that `UI.ipynb` runs against
PyREPL's hand-written Kotlin shim, and the parameter names confirm it independently: the notebook
writes `onclick`, the library writes `on_click`. **The library wins**; see §3's naming decision.

The call these wrappers make cannot reach real AndroidX:

```python
self.__kotlin_composable(**kwargs, c=self.composer, changed=1)
```

`c` and `changed` are the names PyREPL's `Material3.kt` gave its own explicit parameters. A real
Compose composable's synthetic parameters are named `$composer` and `$changed`, which are not Python
identifiers, so no keyword call can address them. Whatever `pythonx/compose/material3/*.py` was
tested against, it was not `androidx.compose.material3`. (Read, not run — no chaquopy runtime was
available here.)

One more piece is worth keeping. `pythonx/compose/wrapper/__init__.py` is four lines:

```python
class KotlinWrapper:
    def __new__(cls, kotlin_object):
        kotlin_object.__class__ = cls
        return kotlin_object
```

It reclasses a live Java object onto a Python class to give it methods. That trick is chaquopy-only
and does not survive the move to this repository's binder — a Kotlin object reaches Python as an
`ObjectReference` integer (`HandleTable`), and an `int` has no assignable `__class__`. Every place
`alignment.py` and `arrangement.py` use it needs a different mechanism.

---

## 2. Read — what `PythonProxySource` already does, and what it does not

### 2.1 Injection into `sys.modules` is why no import hook is needed *after* `install()`

`_pm_module(name)` builds a `types.ModuleType`, puts it in `sys.modules` under the full dotted Kotlin
name, and recursively creates and links its parents. CPython's import machinery answers a
`sys.modules` hit before it consults any finder, so `from fixture.library import greet` works with no
`sys.meta_path` entry at all.

Entry names come from the producers as Kotlin fully-qualified names — `ArtifactScanner`
builds `"$owner.${function.kotlinName}"`, where `owner` is the *package* for a file facade or
multi-file part. So `androidx.compose.material3.Text` is the name a bound Compose composable would
carry, and `import androidx.compose.material3` is what it makes importable. That is
`docs/design/ecosystem.md` §5b's "a Kotlin FQN means the original Kotlin", already implemented.

### 2.2 Judged — what the `pythonx` prefix adds: nothing, and that is the point

`pythonx` is an ordinary Python package. It does not need `sys.modules` injection, a finder, or any
generation, because it is source we write. The two namespaces do not mix (§5b), and mechanically they
do not even use the same publication route: `androidx.*` is injected, `pythonx.*` is imported.

What `pythonx` *does* need from `PythonProxySource` is that the Kotlin FQN it delegates to is
reachable at the moment `pythonx` asks. Which is §2.3.

### 2.3 Judged — laziness needs two mechanisms, and the existing measurement does not forbid either

`PythonProxySource.install()` is eager and whole-table: `render` walks every entry, emits one
`_pm_h_N = _pm_lookup(...)` and one `def` per entry, and the caller `exec`s the lot. For the fixture
tables this repository has, that is right. For Compose it is not: `docs/design/kotlin-extensions-in-python.md`
§2.1 counts 1,411 public top-level functions in the desktop corpus alone, and §2.6 counts 500 public
top-level composables. Every one would be a `_pm_resolve` string lookup and a Python function object
built before the first frame renders. (The install cost has **not** been measured — `GeneratedProxyCostTest`
prices per-call and per-read, not install. That measurement is missing.)

Two different things have to become lazy, and they need different hooks:

| what | hook | why the other one cannot do it |
|---|---|---|
| the module `androidx.compose.material3` existing at all | `sys.meta_path` finder | `import x.y.z` fails before any attribute is touched, so a module `__getattr__` never runs |
| the attribute `Text` inside it | module `__getattr__` (PEP 562) | a finder runs once per module; it cannot defer the 200 names inside one |

**The 551–587 ns figure in `PythonProxySource`'s KDoc does not argue against this.** That measurement
is about a `__getattr__` that answers *every* read of a live Kotlin property, where the raised-and-discarded
`AttributeError` is paid per access. A lazy binder's `__getattr__` fires once, `setattr`s the result into the
module dict, and is never consulted for that name again — every later read is an ordinary dict hit. The
descriptor-versus-hook decision recorded there is about live properties and should not be transplanted.
(Judged from the mechanism; the one-time cost of a lazy `__getattr__` on this repository's own boundary
has not been measured.)

### 2.4 Read — the metadata the Python side cannot obtain

This is the finding that constrains everything in §4. The two names a host must bind are
`_pm_resolve(name_bytes) -> handle` and `_pm_invoke(handle, args_tuple) -> result`. That is the whole
Python-facing surface. `_pm_resolve` answers with an integer; there is no way to ask anything *about*
a declaration.

And `ExposedCallable` does not carry it either:

| what an adapter needs | in `ExposedCallable`? | where it is lost |
|---|---|---|
| parameter **names** | **no** — the class has `name`, `arity`, `paramTypes`, `returnType`, `kind`, `isSuspend`, `callable` | never collected; `kotlin-metadata-jvm` has `KmValueParameter.name`, KSP has it too |
| whether a parameter is an **extension receiver** | **no** | `ResolvedFunction.isExtension` exists and is used to pick the call syntax, then dropped — `ArtifactCallable` has no such field |
| the receiver's **type** | **no** | same place |
| the declared **type name** of a parameter (`Dp` vs `Color`) | **no** — collapsed to a `TypeTag` | `resolveKotlinType` resolves a value class to its *underlying* `BoundaryType`, so a `Dp` parameter is `TypeTag.FLOAT` |
| whether a parameter **has a default** | **no** | `declaresDefaultValue` is readable from metadata and is not read |
| the **overload set** for a name | **no** | `ArtifactScanner` drops a name entirely when more than one binding would carry it (`groupBy { it.name }.filterValues { it.size == 1 }`) |

Consequences, in order of how much they hurt:

1. **`Text(text=..., color=...)` cannot be assembled.** Mapping keyword arguments onto positional
   slots requires parameter names, and nothing on either side of the boundary has them. This is not a
   `pythonx` design choice — it is arithmetic. Without parameter names the Pythonic API is positional
   only, which for a 16-parameter composable is not an API.
2. **"An extension function becomes a method on its receiver's proxy"** (`kotlin-extensions` §4.1)
   has no input. The table cannot say which entries are extensions or on what.
3. **The value-class allowlist** (`kotlin-extensions` §4.4) cannot be enforced in Python, because
   Python cannot see that a parameter is a `Dp` rather than a `Float`. See §4.4 — it turns out the
   allowlist mostly does not need to be enforced there.

### 2.5 Open — where `pythonx`'s own `.py` files live on iOS, androidNative and wasm

`PythonProxySource`'s KDoc gives, as its first reason for rendering Python at run time rather than
shipping a `.py`, that **there is no resource path to put a `.py` on**: Kotlin/Native has no
`getResourceAsStream`, and `sys.path` on iOS, androidNative and wasm points into the per-platform
CPython trees under `src/nativeInterop/cinterop/lib/…`. Getting a generated file into each of those
and onto `sys.path` before the first import is called "a per-platform packaging problem this
repository has not solved for anything."

`pythonx` is exactly that problem, one size larger: not one generated file but a package of
hand-written ones. `grep` finds no `sys.path` manipulation and no site-packages staging anywhere in
the Kotlin sources; the only hits are benchmarks reading `sys.path` as a test fixture.

Candidates, none chosen and none tried:

- `pypackpack`'s `bundle` type `resource`, which its SPEC defines as the handoff format to this
  repository — and which `docs/design/ecosystem.md` §4 item 5 records as a three-line placeholder.
- Freezing `pythonx` into the CPython trees the way the stdlib already is.
- Embedding the source as Kotlin string constants and `exec`ing it, i.e. the route
  `PythonProxySource` took, applied to hand-written code. This works everywhere today and is
  hostile to editing, diffing and shipping a package to PyPI, which `pyproject.toml` says
  `pycomposeui` is meant to do.

Until one exists, `pythonx` is a desktop-and-Android library whatever else this document decides.

---

## 3. Measured — the name conventions `UI.ipynb` actually uses

Every identifier in the notebook's 57 cells, classified. This is the empirical basis for §4.1's rule,
and it is worth reading before assuming "snake_case everything".

| Kotlin | notebook | class |
|---|---|---|
| `Text`, `Button`, `Card`, `Column`, `Row`, `Spacer`, `TextField`, `Icon` | same | composable → **PascalCase kept** |
| `Modifier`, `Alignment`, `Arrangement` | same | type → **PascalCase kept** |
| `Alignment.Horizontal.End`, `Arrangement.Center` | same | nested type / constant → **PascalCase kept** |
| `rememberSaveable` | `remember_saveable` | free function → **snake_case** |
| `fontSize` | `font_size` | parameter → snake_case |
| `contentDescription` | `content_description` | parameter → snake_case |
| `verticalArrangement`, `horizontalArrangement`, `horizontalAlignment` | `vertical_arrangement`, `horizontal_arrangement`, `horizontal_alignment` | parameter → snake_case |
| `onClick` | `on_click` in `pythonx`, **`onclick`** in the notebook | parameter → snake_case; **`pythonx` is the reference, not the notebook** |
| `State.getValue` / `setValue` | `getValue()` / `setValue()` | method on a Kotlin object → **camelCase kept** |
| — | `corner_radius`, `text_state` | **invented**; `material3.Card` has no `corner_radius` in either overload |

Five Kotlin-derived multi-word parameters are snake_case; one (`onclick`) is not. The library source
in `pythonx-compose` spells that same parameter `on_click` in all five button wrappers.

**Decided: `pythonx` is the reference, and the notebook is not.** `on_click` is what the rule
produces and what the library already writes, so `onclick` is not a spelling to support, alias or
carry forward — it is one cell of a teaching notebook disagreeing with the library it teaches. A
table derived from `pythonx` needs no exception list here, which is the point: an exception that
exists only in prose is one the generator and the adapter cannot both honour.

**Judged — the rule.**

    type, object, enum entry, composable          PascalCase, unchanged
    function, method, property, parameter         snake_case
    package segment                               unchanged (already lowercase)

`getValue`/`setValue` are *not* grandfathered: they are ordinary methods and become `get_value`/
`set_value`. Keeping them would mean the rule is "snake_case except on objects that came back from
Kotlin", which is a rule a reader has to know the provenance of a value to apply.

The composable exception is not a special case for Compose. A `@Composable` is PascalCase because it
is a *declaration of a UI element*, and both Kotlin and the notebook treat it as a type-like name;
PEP 8 has no opinion that outranks matching the documentation of the library being wrapped. The cost
is that `pythonx` has two casing rules keyed on a property of the declaration, and the adapter must
know which — `@Composable` is an annotation the walker can read, so this is answerable, but it is one
more thing the table would have to carry (§2.4).

**Judged — the conversion runs Kotlin → Python, and the runtime runs it backwards.**

The `.pyi` generator (plugin, build time) converts Kotlin → Python: it has the Kotlin name and emits
the Python one. The adapter (runtime) is handed the Python name by the caller and must find the
Kotlin one. Those are opposite directions of one function, and snake→camel is not injective in
general (`URLDecode`, `zIndex`, a Kotlin name that already contains an underscore).

Two ways to close it, and the second is the recommendation:

- **Rule-based reverse.** `__getattr__` converts `fill_max_width` → `fillMaxWidth` and calls
  `_pm_resolve` once. Cheap, no enumeration, `dir()` returns nothing useful, and irregular names fail.
- **Rule-based forward, at build time, plus a generated name map for the exceptions.** The plugin
  already walks every declaration to emit stubs; it can detect the cases where the forward rule is
  not invertible and emit only those into a small map the adapter consults on miss.

Whichever is chosen, there is a checkable invariant that should be a test: **every name the `.pyi`
generator emits must resolve through the adapter's reverse rule.** If it does not, the stub promises
an API the runtime does not have, which is the failure mode that makes stubs worse than none.

---

## 4. Judged — the adaptation layer

### 4.1 What a module `__getattr__` looks up and what it caches

```python
# pythonx/_adapt.py — one implementation, no per-declaration code
def __getattr__(name):                      # in pythonx/compose/material3/__init__.py
    kotlin = _kotlin_name(name, _MODULE)    # 'androidx.compose.material3.Text'
    obj    = _adapt(kotlin)                 # resolve + build the callable
    globals()[name] = obj                   # never consulted again for this name
    return obj
```

Three things are cached, at three lifetimes:

| cached | key | lifetime |
|---|---|---|
| the adapted callable | Python name, in the module dict | until interpreter finalisation |
| the handle from `_pm_resolve` | inside the closure | same |
| the receiver-method table for a proxy type | Kotlin receiver type name | built once per type, see §4.2 |

The `pythonx.compose.material3` module is a **facade over `androidx.compose.material3`**, not a
mirror of it: names it does not adapt fall through to `AttributeError` and the Kotlin FQN import is
always available as an escape hatch. That is the property that keeps `pythonx` from having 37 files.

### 4.2 Attaching an extension function as a method on its receiver

`kotlin-extensions` §4.1 adopted this and it is right: each of the 177 public top-level `Modifier`
extensions returns `Modifier`, so `Modifier.padding(16).background(...)` is ordinary Python method
chaining with no combinator machinery.

The mechanism in Python is unremarkable — a proxy class per receiver type, and a `__getattr__` on
*its type* that resolves `receiver_type + '.' + kotlin_name`, binds, and installs the method on the
class so the second instance pays nothing.

The mechanism in Kotlin is missing (§2.4 rows 2 and 3). The adapter is handed
`androidx.compose.foundation.layout.padding` with `arity = 2` and `paramTypes = [OBJECT, FLOAT]`, and
nothing tells it that slot 0 is a receiver of type `androidx.compose.ui.Modifier` rather than an
ordinary first parameter. **Something has to carry `isExtension` and the receiver's Kotlin type name
across the boundary before §4.1 can be built at all.** `ArtifactScanner` has both at the moment it
constructs the call expression and discards them one line later.

### 4.3 The `Modifier` metaclass

`kotlin-extensions` §4.2 established the shape and the reason: `Modifier$Companion` *implements*
`androidx.compose.ui.Modifier`, so one Python class whose class object also behaves like an instance
covers all three spellings — `Modifier.padding(16)`, `m.padding(16)`, `def f(m: Modifier)`.

Two additions from reading the 2024 code:

- **It replaces `from pythonx.compose.ui import Modifier, modifier`.** The notebook imports a
  lowercase `modifier` instance beside the class precisely because `Modifier()` and `Modifier` were
  two different things there. The metaclass removes the second name, and removing it is the point:
  `modifier` as a shared module-level mutable-looking singleton is a trap in a UI library.
- **It is generic, not `Modifier`-specific.** The same metaclass shape applies to any Kotlin
  interface whose companion implements it. Whether any *other* Compose type has that shape was not
  counted.

### 4.4 Value classes — the coercion already happens, and it happens in Kotlin

**Read.** `resolveKotlinType` (`KotlinMetadata.kt`) resolves a value-class parameter to the
`BoundaryType` of its *underlying* type, and `valueClassBoundaryType` renders the read as
`androidx.compose.ui.unit.Dp((args[0] as Double).toFloat())`. So on the parameter side the wrapping is
already generated Kotlin, and `TypeTag` for a `Dp` parameter is `FLOAT`.

This changes `kotlin-extensions` §4.4's conclusion less than it changes where it is enforced:

- **A raw Python number reaching a `Dp` parameter is already correct**, with no Python-side rule, and
  `Modifier.padding(16)` needs nothing built for it.
- **`TextUnit` and every `packedValue` class is not a hazard on this path — it is unbound.** Their
  constructors are `INTERNAL` (`kotlin-extensions` §2.4), and `resolveKotlinType` returns `null` for a
  parameter whose value class has no public constructor. The silent-`Unspecified` failure §4.4 warns
  about cannot occur through a walked binding, because there is no walked binding.
- **`Color` is unbound too, for a second reason.** Its constructor is public but takes `ULong`, and
  `kotlinPrimitiveBoundaryTypeOf` has no `kotlin/ULong` entry, so the underlying type resolves to
  `null`. The "constructor is public" test that §4.4 worried would admit `Color` does not admit it
  here. (Read from source. Not re-run over Compose — `kotlin-extensions` §3 already reports the
  walker binds zero from these jars, for reasons upstream of this.)

**Judged.** The allowlist stays a build-time concept, not a Python one. The Python side gets a
`dp(16)`-style proxy only where a *proxy object* is genuinely needed — that is, where a value class
appears in **return** position, or where a Python user wants to hold one — and unwraps it to the
underlying primitive before the call. The asymmetry §4.4 accepted (`padding(16)` fine,
`font_size=16` rejected) arrives for free: `padding` binds and `Text`'s `fontSize` does not.

The residual risk §4.4 names — a future Compose release adding a public-constructor float wrapper
that packs — is unchanged and still lives at the plugin.

### 4.5 Open — defaults, and why they are the load-bearing blocker

**Measured** (`javap -p` over `material3-desktop-1.6.11.jar` and `foundation-layout-desktop-1.6.11.jar`
from the Gradle cache, i.e. `kotlin-extensions-in-python.md`'s corpus A):

| function | Kotlin params | required params |
|---|---|---|
| `material3.Text` | 16–18 | 1 |
| `material3.Button` | 10 | 2 (`onClick`, `content`) |
| `material3.Card` (container) | 6 | 1 (`content`) |
| `foundation.layout.Column` | 4 | 1 (`content`) |

`kotlin-extensions` §2.2 counts the same property across the `Modifier` surface: **254 of 435
parameters (58%) declare a default**, and 82 of the 177 functions have at least one.

A Python caller writing `Text("hi", color=red)` is skipping fourteen parameters, and the values it
would have to supply instead are `TextStyle.Default`, `TextDecoration` and friends — objects the type
gate does not bind, so Python cannot construct them to pass them. **"Supply every argument" is not
available.** Defaults are therefore not an ergonomic nicety here; without them the bound surface is
unusable even where it binds.

`ExposedCallable` has one `arity` and one `callable`. What could close the gap, none verified:

| candidate | why it might not work |
|---|---|
| one entry per **arity prefix** (Kotlin defaults are trailing, so calling with the first *k* arguments is always legal) | *N*+1 entries instead of 2^*N*, and it cannot express "pass `text` and `font_size`, skip `modifier` and `color`", which is how Compose is written |
| **exhaustive presence branching** in the generated lambda | 2^15 branches for `Text` |
| calling the compiler's **`$default` synthetic** from generated Kotlin | it is `ACC_SYNTHETIC`, so Kotlin source cannot name it; and `javap -p` over `ButtonKt` shows **no `Button$default` exists at all** — a `@Composable` carries its `$default` mask as a declared trailing parameter instead (§5.2), which is a different mechanism |
| a **generated Kotlin wrapper per function** that restates the defaults | the defaults are expressions in the callee's scope; metadata carries the *flag*, never the value (`kotlin-extensions` §4.5) |

The `@Composable` case may be luckier than the general case precisely because of §5.2 — the mask is a
real parameter there. Whether that is reachable is §5.4.

---

## 5. `@Composable`

### 5.1 Read — what the 2024 `Composable` class does

`pythonx/compose/runtime/__init__.py` is a decorator class holding a single `__composer` registered
from Kotlin (`ComposeApp.__init__(composer)`), and `__call__` refuses to run if it is unset:

```python
if self.composer is None:
    raise RuntimeError("Composer for Composable must be registered before Composition starts.")
```

It then inspects `self.compose.__code__.co_varnames` to find where `content` sits, pops it from args
or kwargs, wraps a raw `ComposableLambdaImpl` in `KotlinComposable` if that is what arrived, and calls
through. `KotlinComposable.compose` is the interesting line:

```python
ComposableWrapper(self.content, args, self.composer, 1)
```

`ComposableWrapper` is `io.github.thisisthepy.pycomposeui.RuntimeKt.composableWrapper` — a
hand-written Kotlin function that takes a `ComposableLambda`, the argument list, a `Composer` **as an
ordinary value**, and a `changed` int. That is the shape `docs/design/ecosystem.md` §5b describes as "the
Python wrapper passes the composer as a value".

So the 2024 design has exactly one composer for the whole application, set once, and every composable
call uses it with `changed = 1`. Whether that produces correct recomposition was not verified here
and is not obviously true — `$changed` is a per-call-site bitmask about which arguments are known
static, and a constant `1` tells the runtime something specific about slot 0.

### 5.2 Measured — the synthetic parameters, and a correction to `kotlin-extensions` §2.3

`javap -p`, corpus A jars, top-level parameter counts split at the top level (generics ignored):

| function | Kotlin params | JVM params, counted here | JVM params, `kotlin-extensions` §2.3 |
|---|---|---|---|
| `Text-fLXpl1I` | 16 | **20** | 19 |
| `Text--4IGK_g` (String) | 17 | **21** | 20 |
| `Text--4IGK_g` (AnnotatedString) | 17 | **21** | 20 |
| `Text-IbK3jfQ` | 18 | **22** | 21 |
| `Button` | 10 | **13** | 12 |
| `Card` (clickable) | 9 | **12** | 11 |
| `Card` (container) | 6 | **9** | 8 |
| `Column` | 4 | **7** | 6 |
| `Row` | 4 | **7** | 6 |

Every one of the nine rows is one higher than recorded. The trailing parameters of `Button` are
`Composer, int, int` and of `Text-fLXpl1I` are `Composer, int, int, int` — that is `$composer`, one or
two `$changed` masks, and **`$default`**. The distribution in §2.6 (`+2: 80, +3: 349, +4: 62, +5: 9`)
was not re-derived; if it is shifted the same way, `+2` would mean "composable with no defaulted
parameter" and the nine functions above would all be `+3` or `+4`, which is what was measured. The
design conclusion §4.6 draws — *never derive arity from the descriptor; metadata arity is the source*
— is unaffected and strengthened.

Reproduce with:

    unzip -o -q "$GRADLE_CACHE/.../material3-desktop-1.6.11.jar" 'androidx/compose/material3/ButtonKt.class'
    javap -p androidx/compose/material3/ButtonKt.class

### 5.3 Read — the contradiction inside `docs/design/ecosystem.md`

§4 item 1 says: *"`@Composable` cannot be an exposed callable … a generated entry for a widget would
not compile … This is the smallest and most blocking item."*

§5b says: *"a `@Composable` function is, after compilation, an ordinary function taking `$composer`
and `$changed`; a binding generated from the artefact exposes that signature as it is … So nothing on
the Kotlin side needs a `@Composable` callable type."*

**§5b's resolution does not hold given how bindings are produced.** The workers generate *Kotlin
source* and let `kotlinc` compile it (agent-rules §12, and `ArtifactRendering.renderEntry` shows the
generated lambda body verbatim). Kotlin source cannot:

- call a `@Composable` from a non-`@Composable` lambda — the Compose plugin rejects it; and
- name `$composer`, `$changed` or `$default`, which are not parameters of the Kotlin declaration at
  all. They exist only in the compiled form, which is the form §5b's argument reasons about and the
  form the generator does not emit.

Both facts are properties of the generator, not of Compose, which is why §5b's reading of the
bytecode is correct and its conclusion still does not follow. The 2024 code got away with it because
chaquopy calls *JVM methods by name and descriptor*, where the synthetic parameters are ordinary
arguments — the very approach `kotlin-extensions` §2.3 retires, since 108 mangled JVM names are
ambiguous within their own class.

### 5.4 Open — the one candidate that might reconcile them

`androidx.compose.runtime.internal.composableLambdaInstance` builds a `ComposableLambda` from a
`@Composable` block, and `ComposableLambda.invoke(p1, composer, changed)` is an ordinary
non-composable method. That is precisely the door `RuntimeKt.composableWrapper` walked through in
2024. So a generated fragment could, in principle, be:

```kotlin
private val bindButton = composableLambdaInstance(/* key */ 0, /* tracked */ true) { args: Array<Any?> ->
    androidx.compose.material3.Button(onClick = { … }, modifier = args[1] as Modifier, …)
}
// the ExposedCallable stays `(Array<Any?>) -> Any?`:
{ args -> bindButton.invoke(args, args.last() as Composer, 1) }
```

which would keep `ExposedCallable.callable` unchanged, let `kotlinc` supply `$composer`, `$changed`
**and `$default`** at a real call site — closing §4.5 for composables specifically — and let Python
pass the composer as a value exactly as §5b says.

**Not verified. Nothing here was compiled.** What has to be checked, in order:

1. That `composableLambdaInstance` is callable from generated code at all — it is in an `internal`
   package and annotated `@ComposeCompilerApi`; whether that is an opt-in warning or a hard error for
   a consumer's generated source is unknown.
2. That the group key can be generated stably. Compose identifies slots by key; a colliding or
   changing key across builds corrupts the slot table rather than failing.
3. That `changed = 1` is not simply wrong. It is a bitmask over argument staticness, and a constant
   is a claim about arguments the generator has not inspected. The conservative value (`0`, meaning
   "nothing known") is the thing to try first.
4. That an `Array<Any?>` parameter does not defeat skipping. Compose skips a composable when its
   arguments compare equal; a fresh array per call never does, so **every Python-driven composable
   would recompose on every frame regardless of whether anything changed.** This is a performance
   property, measurable, and it should be measured before the shape is adopted.

Until (1) is answered, `docs/design/ecosystem.md` §4 item 1 remains the blocking item it says it is, and
§5b's paragraph asserting otherwise should be treated as unresolved rather than as settled.

### 5.5 Judged — arity is where the real risk sits, and it is not a collision risk

`kotlin-extensions` §2.6 measured that of 500 composables, 41 are extension functions and exactly one
is a `Modifier` extension. Composables and chainable modifiers are two disjoint problems, so the
adapter can treat them as two code paths with one shared name resolver and no arbitration between
them. The `Modifier`-extension composable (`ui.ExternalDrag_desktopKt.onExternalDrag`) is a single
name and can be excluded by name if it is ever in the way.

### 5.6 Closed — Python calls a real composable, and §5.4's candidate was not what did it

**`Text('hi')` written in Python draws pixels through `androidx.compose.material3.Text`.**
`:ksp-fixtures:compose`'s `ComposableRenderTest` renders a 200×60 `ImageComposeScene`: the Python
body drew **71** non-background pixels, an empty body drew **0**, and `Text('hi hi hi hi hi')` drew
**376**. Nothing is stubbed — `material3-desktop-1.6.11.jar` is walked, the composer is real, the
composition is real.

Four things had to be settled, and three of them contradict what was written above.

**1. `@Composable` is not a runtime-visible annotation.** `ArtifactScanner.isComposable` read
`MethodNode.visibleAnnotations` on the stated grounds that "`@Composable` is `RUNTIME`-retained".
It is not: `androidx.compose.runtime.Composable` is `@Retention(AnnotationRetention.BINARY)`, and
`javap -v androidx/compose/material3/TextKt.class` shows every composable carrying
`RuntimeInvisibleAnnotations: androidx.compose.runtime.Composable`. So the predicate answered
`false` for all 500 of them and `DeclarationModel.isComposable` — which drives §3.6's PascalCase
rule — had never once been true. It reads both lists now.

**2. The trailing shape is derivable, and was checked rather than assumed.** §5.2 measured nine
signatures by hand; `ComposableBindingTest.everyComposableJvmSignatureIsKotlinParamsThenComposerThenInts`
now checks **281** public top-level composables across three jars, of which **271** carry a
`$default`. The rule: the parameter at index *n* is the `Composer`, everything after it is `int`,
and there are `ceil(n/10)` `$changed` masks (Compose's `SLOTS_PER_INT`) followed by `ceil(n/31)`
`$default` masks (`BITS_PER_INT`) exactly when the declaration defaults something. No composable
contradicted it.

**3. The mask encoding, read out of the callee.** `javap -c` on `Text-fLXpl1I` shows the prologue
testing `$default & 2` before `modifier = Modifier.Companion`, `& 4` before
`color = Color.Unspecified`, `& 8` before the next — so **bit *i* is parameter *i***, with a bit
reserved for every parameter including ones that default nothing (`text` owns bit 0 and nothing sets
it). `$changed` is passed as **0**, the conservative value: it is a claim about argument staticness
that this caller cannot make, and the callee's own `composer.changed(...)` branch is what runs when
it is zero.

Load-bearing, and checked by breaking it: shifting the mask one bit left made the render fail with
`Parameter specified as non-null is null: … graphicsLayer-Ap8cVGQ`, i.e. a default that was no
longer substituted reached Compose as `null`.

**4. §5.4's `composableLambdaInstance` route was not needed, and a plainer obstacle than the one
§5.3 named is what decided it.** §5.3 says generated Kotlin cannot name `$composer`, which is true.
But the *fallback* it assumes — call the JVM method directly — does not fail on the hyphen or on the
synthetic parameters. It fails earlier:

    probe.kt:2:35: error: unresolved reference 'TextKt'.

A Kotlin file facade has **no Kotlin name at all** (measured with `kotlinc` 2.4.20-Beta2 against the
real jar), so there is nothing to write. Java cannot spell `Text-fLXpl1I` either. What is emitted
instead is **one `.class` per artefact fragment** (`ComposableThunks.kt`): a
`public static Object t<i>(Object[])` that unboxes the boundary's argument array and does one
`INVOKESTATIC` with the name and descriptor copied verbatim from the `MethodNode` being walked. That
is not "looking a method up by name" in the sense agent-rules §12 retires — there is no runtime
lookup, no reflection, and the JVM's own linker resolves it at class-load time exactly as `kotlinc`'s
own `INVOKESTATIC` would.

So §4.5's table gains a row it did not have: **pass the mask as a value.** It is available because
§5.2's finding — the mask is a *declared* parameter — means it never had to be a compile-time
constant. Which arguments a call wrote is something Python knows, so the arithmetic is one integer
per call in `pythonx._bind_composable`, for a declaration of any width. `MAX_OMITTABLE_PARAMETERS`
and its 2^n cost apply to the sentinel mechanism and are untouched; a composable uses neither.

**The composer still needs one hand-written `@Composable`**, and exactly one:
`:ksp-fixtures:compose`'s `PythonComposition`. It names no composable and takes no
composable-specific parameter — it registers `currentComposer` as an ordinary object handle, pushes
it onto `pythonx`'s stack and runs the Python body — so it is O(1) in the number of bound
composables, unlike 2024's wrapper-per-widget. `docs/design/ecosystem.md` §5b's "the Python wrapper passes
the composer as a value" survives intact; §4 item 1's "this is the smallest and most blocking item"
is closed.

**What is still open.** Recomposition and skipping (§5.4 item 4, §6) — the Python body is re-`exec`ed
on every composition pass and nothing has measured it. Extension composables are declined: Compose
counts receivers in `$changed`'s slots but assigns `$default` bits over value parameters, and which
numbering a receiver shifts has not been measured (§2.6 counts one `Modifier`-extension composable in
500, so the cost of waiting is one declaration). And a composable with more than 31 parameters would
exercise the second `$default` word, which the arithmetic writes and nothing has observed.

---

## 6. Open — the lifetime of a Python callable across recomposition

`docs/design/ecosystem.md` records this as unverified. This section says what "verify" means, because the
mechanism turns out to be half-present already.

**Read — a Python callable can already cross into Kotlin.** `UpcallTrampoline.toKotlinObject` handles
`TypeTag.OBJECT` in the *parameter* direction by testing whether Python sent an integer: if it did,
it is a `HandleTable` handle for a Kotlin object; if it did not, the value is wrapped as
`PyObject(value, borrowed = true)` and the wrapper takes its own reference. So a `content=lambda: …`
reaches a Kotlin parameter declared `PyObject` today, with a reference of its own.

**Read — the walker will not produce such a parameter.** `resolveKotlinType` has no `OBJECT`
fallback, so an artefact-walked binding can never take one; only a KSP-generated entry from the
consumer's own Kotlin can (KSP has `isPyObjectType`). A `content:` parameter on a walked
`androidx.compose.material3.Button` is therefore unreachable regardless of everything else in §5.

**What has to be verified, and none of it has been.** Compose stores a lambda in the slot table and
calls it again on a later recomposition, possibly many frames later. That crosses three boundaries
this repository has measured separately and never together:

1. **Release.** When Compose drops the slot, does anything tell the Kotlin holder? `RememberObserver.onForgotten`
   is the only hook that reports it. A `PyObject` held by an object that does not implement it is
   leaked for the life of the process — the same class of defect
   `ProxyHandleLifetimeTest` was written for, in the opposite direction (Kotlin object held by
   Python). A test of the same shape is needed: compose, drop, recompose, assert the Python refcount
   came back.
2. **The GIL on the release path.** `Py_DecRef` requires the GIL. `onForgotten` runs on whatever
   thread Compose is applying changes on. Whether that thread holds the GIL — and if not, whether the
   decref can be queued to one that does — is not answered anywhere in `docs/design/object-lifetime.md` or
   `docs/investigations/gil-parking-investigation.md` for this direction.
3. **Re-entrancy.** The call is Kotlin composition → Python callable → Kotlin composable → Python
   `content` lambda. The GIL is held across a Kotlin composition that may itself block or hop
   threads. `docs/investigations/gil-parking-investigation.md` exists because this repository has already been bitten
   by GIL parking; this is a new place to be bitten.
4. **Identity across recomposition.** A Python `lambda` is a new object every time the enclosing
   Python function runs, so `equals` is false and Compose cannot skip. Combined with §5.4 item 4, the
   likely outcome is that nothing ever skips. Whether that is tolerable is a measurement, not an
   opinion, and the measurement does not exist.

Item 1 is the one that fails silently and should be tested first.

---

## 7. Judged — what is Python code and what is a generated artefact

The dividing rule: **if it differs per Kotlin declaration it is generated or resolved at run time; if
it is the same rule for every declaration it is `pythonx` Python source.** Everything the 2024 attempt
got wrong is on the wrong side of that line — a file per component is per-declaration code written by
hand.

| product | who makes it | when | where it lives | consumer |
|---|---|---|---|---|
| `FunctionTableFragment` (consumer's own source) | KSP processor | build | generated Kotlin, compiled into the app | `UpcallTable` |
| `ArtifactTable` fragments (resolved jars/klibs, AndroidX included) | Gradle plugin artefact walker | build | generated Kotlin, compiled into the app | `UpcallTable` |
| `.pyi` stubs, carrying the Pythonic names §3 produces | Gradle plugin (long term: `toolchain`) | build | `src/<sourceSet>/generated/meta/` | the IDE only — never imported |
| proxy modules under Kotlin FQNs (`androidx.compose.material3`) | `PythonProxySource.render` | run time, `exec` | `sys.modules` | `pythonx`, and any user who wants the original |
| `_pm_settle` / `_pm_watch` / `_pm_module` / `_pm_static_property` support | `PythonProxySource.support` | run time, `exec` | `__main__` | the generated proxies |
| `_pm_resolve` / `_pm_invoke` | per-platform bootstrap (Kotlin/C) | run time | `__main__` | everything above |
| **`pythonx/`** — name conversion, module `__getattr__`, receiver-method attachment, the `Modifier` metaclass, value-class proxies, the composable call convention, the `content=` adapter | **written by hand, once** | — | a shipped Python package (§2.5: no route on 3 of 5 targets) | the application author |

Two consequences worth stating explicitly.

**`pythonx.compose.material3` must not be a file per component.** It is a module with a
`__getattr__` and a package name. If a future `pythonx` grows `buttons.py`, `cards.py`, `text.py`,
the design has failed in exactly the way the 2024 one did, and the count of empty files is the
symptom to watch for.

**`pythonx` may contain genuinely non-mechanical code, and that is where the line actually bends.**
The notebook needs `main.App`, `App.update(NewComposable)` for hot-swapping a composable from a
Jupyter cell, and `App.messages` as live state. None of that is a mapping of a Kotlin declaration —
it is an application-lifecycle facility. It belongs in `pythonx` as hand-written Python (over a small
hand-written Kotlin surface), and it should be kept visibly separate from the adapter so that "wrote
a wrapper by hand" stays a visible event rather than a habit.

---

## 8. Open

Collected, in the order that unblocks the most.

1. **Parameter names across the boundary** (§2.4). Nothing keyword-based is possible without them.
   Either extend `ExposedCallable`, or emit a side table with one more bootstrap entry point; the
   trade is per-entry footprint on five targets against a second thing that can disagree with the
   first. Not decided.
2. **`isExtension` and the receiver type across the boundary** (§2.4, §4.2). `ArtifactScanner` has
   both and drops them. Without them `kotlin-extensions` §4.1 has no input.
3. **Defaults** (§4.5). Four candidates, all with a named reason they may not work. This is the item
   that decides whether a bound Compose surface is usable at all.
4. **`composableLambdaInstance` as the composable call route** (§5.4), and the four checks it needs.
   Answering (1) of those four resolves the contradiction in §5.3 one way or the other.
5. **Where `pythonx`'s `.py` files live on iOS, androidNative and wasm** (§2.5). Today: nowhere.
6. **Python-callable lifetime across recomposition** (§6), starting with `RememberObserver.onForgotten`.
7. **Install cost** (§2.3). `PythonProxySource.install()` has never been measured, on any table size.
   The argument for laziness is currently structural, not measured.
8. **`kotlin-extensions` §2.3's JVM parameter column** (§5.2) is one low on all nine rows; whether
   §2.6's `+2/+3/+4/+5` distribution shares the error was not re-derived.
9. **Scoped modifiers** — inherited unchanged from `kotlin-extensions` §6. 74 public member
   extensions on `Modifier` need a dispatch receiver Python has no implicit form for. No shape chosen.
10. **Overload dispatch** — inherited unchanged. The walker currently drops 33 of 130 `Modifier`
    names. A Python-side dispatcher needs (1) to exist first, since it selects on argument names.

---

## 9. Measured — the `Modifier` extensions still declined, and `pointerInput` opened

`ArtifactScannerTest.composeModifierExtensionsSurviveBothGates` and
`FunctionSlotBindingTest.mostFunctionTypedModifierExtensionsAreNoLongerDeclinedForBeingFunctionTyped`
were run against the real Compose 1.7.0 desktop jars (`ANDROID_HOME=... ./gradlew
:python-multiplatform-gradle-plugin:test --tests ...`, 2026-08-17) to get the *current* decline list
by name rather than assume the count in either test's own KDoc is still current. It is not: **nine**
public top-level `Modifier` extensions have zero binding overload today, not the "44"/"8" figures
recorded when those tests were written. One more name (`pullRefresh`) has
a declined *overload* beside a bound one and so is not in this list — a caller can already reach
`pullRefresh(modifier, state, enabled)`, just not the callback-based overload.

| name | JVM signature (`javap -p`, Compose 1.7.0) | decline reason (`ArtifactScanner`, measured) |
|---|---|---|
| `pointerInput` | `Modifier.pointerInput(Any?, suspend PointerInputScope.() -> Unit)` → `Function2<PointerInputScope, Continuation<Unit>, Any?>` | suspend function-typed parameter — **now bound**, see below |
| `dragAndDropSource` | `dragAndDropSource(Modifier, (DrawScope)->Unit, suspend DragAndDropSourceScope.() -> Unit)` → `Function2<..., Continuation<Unit>, Any?>` | suspend function-typed parameter, same family as `pointerInput` |
| `draggable` | `draggable(Modifier, DraggableState, ..., onDragStarted: suspend CoroutineScope.(Offset)->Unit, onDragStopped: suspend CoroutineScope.(Float)->Unit, ...)` → both `Function3<CoroutineScope, X, Continuation<Unit>, Any?>` | suspend function-typed parameter |
| `composed` | `composed(Modifier, (InspectorInfo)->Unit, factory: @Composable Modifier.() -> Modifier)` → `factory` compiles to `Function3<Modifier, Composer, Integer, Modifier>` | `@Composable` function-typed parameter of a declaration that is not itself `@Composable`: `ArtifactScanner`'s generated fragment is compiled without the Compose plugin |
| `swipeable` | `swipeable-pPrIpRY<T>(Modifier, SwipeableState<T>, ..., thresholds: (T, T) -> ThresholdConfig, ...)` | the `thresholds` slot's declared type argument is the type parameter `T`, which has no name a cast could spell |
| `modifierLocalProvider` | `modifierLocalProvider<T>(Modifier, ProvidableModifierLocal<T>, () -> T)` | same: `() -> T`'s `T` has no name |
| `anchoredDraggable` | `anchoredDraggable<T>(Modifier, AnchoredDraggableState<T>, ...)` | not function-typed at all — the *required* `state` parameter's own type carries the unnameable `T`, so `resolveKotlinType` answers "no boundary type" before the function-slot grammar is ever asked |
| `layoutId` | `layoutId(Modifier, layoutId: Any)` | not function-typed — `resolveKotlinType` has no boundary type for a bare `kotlin.Any`/`java.lang.Object` parameter (no hint of which `TypeTag` an arbitrary Python value crossing there should marshal as) |
| `contextMenuOpenDetector` | `contextMenuOpenDetector(Modifier, key: Any?, enabled: Boolean, onOpen: (Offset)->Unit)` | not function-typed — same as `layoutId`, `resolveKotlinType` has no boundary type for `kotlin.Any` |

So the nine split into three unrelated limits, not one:

- **suspend lambda** (`pointerInput`, `dragAndDropSource`, `draggable`, and `pullRefresh`'s declined
  overload) — §9.1 opens one of these.
- **generic type parameter with no spellable name** (`swipeable`, `modifierLocalProvider`,
  `anchoredDraggable`) — a walker limit, not a suspend one; see §9.2.
- **compile-model mismatch** (`composed`) and **unconstrained parameter type** (`layoutId`, `contextMenuOpenDetector`) — see §9.2.

### 9.1 `Modifier.pointerInput` — judged reachable, and reached

The blocking claim in `FunctionSlotBindingTest`'s KDoc is narrower than it reads: "a Python callable
cannot answer `COROUTINE_SUSPENDED`" is true of a *bare* Python callable substituted for the whole
suspend lambda, but `pointerInput`'s block does not need to *be* the suspend function — it needs to
*call into* one, once per event, and the suspension all happens inside `awaitPointerEventScope {
awaitPointerEvent() }`, which is ordinary Kotlin the compiler already knows how to build a state
machine for. Nothing about Python has to suspend at all if the loop that suspends is hand-written
Kotlin and Python is only ever asked a synchronous question at each iteration — exactly the shape
`PythonCallables.PythonFunction` already crosses for every *other* `Function0..Function5` slot in this
codebase (§4.2's `content=` in this file, among others).

That is `fixture.compose.pythonPointerInput` (`ksp-fixtures/compose/src/desktopMain/kotlin/fixture/
compose/PythonPointerInput.kt`): a hand-written, ordinarily-compiled `suspend` Kotlin function —

    fun pythonPointerInput(modifier: Modifier, onEvent: PyObject): Modifier =
        modifier.pointerInput(Unit) {
            try {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        onEvent(type, x, y).close()   // ordinary, non-suspending PyObject.invoke
                    }
                }
            } finally { onEvent.close() }
        }

bound by KSP exactly the way `fixture.compose.emptyModifier` already is (`FragmentScanner
.topLevelFunctionEntry`, a plain top-level function, no artefact walker involved — `Modifier.
pointerInput` itself is untouched and still declines, correctly, for the reason in the table above).
It is a plain parameter rather than an extension receiver on purpose:
`FragmentScanner.topLevelFunctionEntry` calls a declaration by its qualified name over
`function.parameters` alone and never reads `KSFunctionDeclaration.extensionReceiver`, so `fun
Modifier.pythonPointerInput(...)` would silently drop the receiver from the generated call. Changing
that is shared infrastructure and out of scope here; `pythonPointerInput(modifier, onEvent)` is the
shape that works today.

**Proven, not asserted.** `ksp-fixtures/compose/src/desktopTest/kotlin/fixture/compose/
PointerInputRenderTest.kt` drives a real `Move`/`Press`/`Release` sequence through
`ImageComposeScene.sendPointerEvent` — the same entry point `CallbackDrivenRenderTest` uses for
`Checkbox` — at a `Text` sized by the walked `size__Dp`, whose displayed string is
`str(len(_tap_events))`. The test asserts, in Python, that at least one `press` event arrived with
coordinates inside the 48×48 target box (not a stub value), and separately that a *fresh* scene shows
a different digit once the callback has run — the same two-part shape (`_events` list, then a second
scene) `CallbackDrivenRenderTest` uses for `Checkbox`/`Switch`, for the same reason: the scene that
received the tap does not itself recompose from a Python list mutation Compose's snapshot system
cannot see. Confirmed red first: with `PythonPointerInput.kt` removed, both tests fail with
`PyException: cannot import name 'pythonPointerInput' from 'fixture.compose'`; restored, both pass
(`:ksp-fixtures:compose:desktopTest`, 50 tests / 0 failures, up from 48 before this file).

### 9.2 `draggable` and `dragAndDropSource` — reached, the same way `pointerInput` was

Both measured against the real Compose 1.7.0 desktop jars (`javap -p`, and — since there is no sources
jar for this Compose version — bytecode disassembly of the internal node classes where the declared
signature alone did not settle how the slot is actually driven), 2026-08-17.

- **`draggable`** (`androidx.compose.foundation.gestures.DraggableKt.draggable`) is not one suspend
  slot but two different shapes: `state: DraggableState`'s own `onDelta` (built by the top-level
  `DraggableState(onDelta: (Float) -> Unit): DraggableState` factory, itself a plain function, not
  `@Composable`) is an *ordinary, non-suspend* `Function1<Float, Unit>` the internal drag node calls
  directly, once per raw pointer delta — the same shape every other plain callback in this codebase
  already crosses. `onDragStarted: suspend CoroutineScope.(Offset) -> Unit` and
  `onDragStopped: suspend CoroutineScope.(Float) -> Unit` are `draggable`'s own parameters and are the
  `pointerInput` shape: each invoked once per gesture, by a coroutine whose body never actually
  suspends. `fixture.compose.pythonDraggable`
  (`ksp-fixtures/compose/src/desktopMain/kotlin/fixture/compose/PythonDraggable.kt`) builds a
  `DraggableState` from one `PyObject` callback and passes two hand-written trivial-suspend lambdas for
  the other two, calling all three synchronously. **Lifetime is not solved here and the file says so**:
  unlike `pointerInput`'s single suspend loop, there is no one coroutine whose `finally` covers this
  function's whole lifetime — `onDelta` is held by the `DraggableState` for as long as the internal drag
  node stays attached, which this function has no hook into without wrapping itself in a `@Composable`
  (a different fix; see `composed` below). The three `PyObject` references are never closed; each call
  to `pythonDraggable` leaks them. Measured, not guessed: `DraggableRenderTest` composes it once per
  scene, so the leak is bounded (three references) for that test.

  Proven, not asserted: `ksp-fixtures/compose/src/desktopTest/kotlin/fixture/compose
  /DraggableRenderTest.kt` drives a real horizontal drag (`Move`/`Press`/several `Move` steps past the
  default touch slop/`Release`) through `ImageComposeScene.sendPointerEvent`. Python asserts, in Python,
  that `on_drag_started` fired exactly once with a real position inside the 48×48 box, that
  `on_delta` fired at least once and its accumulated total is strictly positive (not a zero-stub), and
  that `on_drag_stopped` fired exactly once with a `float`; a fresh scene shows the accumulated total as
  a changed digit (ink 58→131px, 140px changed). The negative control is a press-and-release entirely
  outside the 48×48 box: none of the three callbacks fire. Confirmed red first: with
  `PythonDraggable.kt` removed, both tests fail with `cannot import name 'pythonDraggable'`; restored,
  both pass.

- **`dragAndDropSource`** (`androidx.compose.foundation.draganddrop.DragAndDropSourceKt
  .dragAndDropSource`) turns out to be the *same* mechanism `pointerInput` uses, not merely the same
  declared shape: disassembling `DragAndDropSourceNode`'s constructor shows it `delegate`s a
  `SuspendingPointerInputFilterKt.SuspendingPointerInputModifierNode(dragAndDropSourceHandler)` — the
  identical node `Modifier.pointerInput` itself delegates to. `DragAndDropSourceScope` (the `block`
  parameter's receiver) extends `PointerInputScope`, so `fixture.compose.pythonDragAndDropSource`'s body
  is `pythonPointerInput`'s loop, verbatim, on a different receiver type — including the same
  `finally`-based lifetime, since it is the same underlying coroutine. `drawDragDecoration` (the other,
  non-suspend parameter) is a hardcoded no-op here; this proves the suspend slot's events reach Python,
  not that `startTransfer`/`drawDragDecoration` do anything real (a `DragAndDropTransferData` needs a
  `java.awt.datatransfer.Transferable`, a real drag payload and a different, unmeasured claim). Required
  `@OptIn(ExperimentalFoundationApi::class)`, and one thing surfaced only at runtime, not in `javap`:
  Compose Multiplatform desktop prints `"Compose Multiplatform doesn't support Modifier
  .dragAndDropSource yet"` (once per composition of the modifier) — a warning from elsewhere in
  `dragAndDropSource`'s own implementation, not an exception, and it does not stop the delegated
  `SuspendingPointerInputModifierNode` from receiving real events: the render test's ink still moves
  (58→59px, 68px changed) and Python's assertions on real (kind, x, y) values still pass.

  Proven the same way as `pointerInput`: `DragAndDropSourceRenderTest.kt`, structurally identical to
  `PointerInputRenderTest.kt` (a tap-in-box positive claim, a tap-outside-box negative control).
  Confirmed red first (`cannot import name 'pythonDragAndDropSource'` with the file removed); green
  restored.

### 9.3 `layoutId` — reached, and one more limit found along the way

`layoutId(Modifier, id: Any)` declines for the reason §9's table gives: its one non-`Modifier`
parameter is `kotlin.Any`, which `resolveKotlinType` has no `TypeTag` for. `fixture.compose
.pythonLayoutIdString(modifier: Modifier, id: String): Modifier` fixes the parameter's tag exactly the
way the table's KDoc predicted — one concrete-`String` wrapper, no suspend or generic-type problem
involved at all.

Proving what it tagged is where a second, previously unmeasured limit showed up. A first version paired
it with a hand-written `@Composable fun pythonLayoutIdProbe(...)`, meant to read `Measurable.layoutId`
back and place a child accordingly — bound as a plain top-level function the way `pythonPointerInput`
is. **It does not bind, confirmed by running it**: `cannot import name 'pythonLayoutIdProbe'`, silently
(no compile error, no KSP warning surfaced by the "e: "-line check) — `BindingPolicy.isComposable`
excludes *every* `@Composable` top-level function from `FragmentScanner`'s plain-function path on
purpose, per its own KDoc: a `@Composable` in the consumer's own source cannot be called from the
generated, non-composable fragment source, and KSP must not try. `PythonComposition` is not a
counterexample to this — Python never calls it; `ImageComposeScene`'s own composable content lambda
does, from a context that already has a composer. This is a more definite answer than `composed`'s
"unverified whether a composer is admitted at all" below: for a plain hand-written composable in this
module's own source, the answer is no, independent of any per-declaration contract question.

`LayoutIdRenderTest.kt`'s working version reads `layoutId` back the same way `PythonComposition` itself
is reached: a `Layout` built in the *Kotlin test*, wrapping `PythonComposition`, whose one measurable is
whatever single `Text` Python's body composed. It places that child at x=0 if the child's `layoutId`
equals an `expectTag` known only to the Kotlin test (never crossed to Python) or at x=60 otherwise.
Positive case (`aMatchingLayoutIdPlacesTheChildAtTheLeft`): tag and probe both `"python-tag"`, ink at
x∈[0,20). Negative control (`aMismatchedLayoutIdPlacesTheChildAtTheRight`): tag `"python-tag"`, probe
`"a-different-tag"`, ink at x∈[60,80) instead — ruling out a stub that always reports a match or drops
the string Python actually sent. Confirmed red first (`cannot import name 'pythonLayoutIdString'` with
`PythonLayoutId.kt` removed); green restored. `pythonLayoutIdInt` was judged not worth building
alongside it: the `String` case is what needed a second crossing path to be found at all, and `Int`
would exercise nothing new.

### 9.4 `pullRefresh`'s callback overload and the generic-type group — still not attempted; `composed` — reached

- **`pullRefresh`'s callback overload** (`androidx.compose.material.pullrefresh.PullRefreshKt
  .pullRefresh(Modifier, onPull: (Float) -> Float, onRelease: suspend (Float) -> Float, enabled:
  Boolean)`) turns out **not** to be the same shape §9's table assumed. Disassembling it (no sources jar
  for this Compose version) shows it builds a `PullRefreshNestedScrollConnection(onPull, onRelease,
  enabled)` and installs it with `Modifier.nestedScroll` — `onPull`/`onRelease` are driven by
  `NestedScrollConnection` callbacks from a *scrollable descendant's* pre-scroll/pre-fling reports, not
  by pointer events this modifier's own suspend body awaits directly the way `pointerInput`,
  `draggable` and `dragAndDropSource` all do. `onPull` also has to *return* the `Float` amount consumed,
  which `pythonDraggable`'s `onDelta` (returning `Unit`) does not. Reaching it the way §9.2 reaches
  `draggable` is very likely still possible — `onPull`'s marshalling is one more step than a `Unit`
  callback, and `onRelease` is the same trivial-suspend-lambda shape as `onDragStopped` — but proving it
  needs a real nested-scroll parent/child composed inside the test (a scrollable container whose
  over-scroll at rest actually reaches `PullRefreshNestedScrollConnection`), which is a materially larger
  test harness than a bare tagged `Modifier` under a pointer. Not attempted here for that reason, not a
  suspend-shape reason.
- **`composed`** — **attempted, reached, and proven.** Its `factory` compiles to the *same* lowered
  shape (`Function3<Modifier, Composer, Integer, Modifier>`, confirmed by `javap -p
  androidx.compose.ui.ComposedModifierKt`) `PythonCallables` already crosses for every composable
  `content=` slot, so the missing piece was never the crossing but a call site — which
  `ksp-fixtures/compose` already has the Compose plugin to compile. The verdict this section left open
  — "still unverified whether `composed`'s own contract... admits a composer at all" — is answered
  **yes**, and the premise in that sentence was backwards: `composed` itself is not `@Composable` (the
  same `javap` output shows no synthetic `$composer`/`$changed` on the `composed` static method), which
  does not make a composer *unavailable* to `factory` — it means `BindingPolicy.isComposable` (which
  excludes only a declaration *itself* annotated `@Composable`) never sees `pythonComposed` and does not
  exclude it, exactly as this paragraph predicted. `fixture.compose.pythonComposed`
  (`ksp-fixtures/compose/src/desktopMain/kotlin/fixture/compose/PythonComposed.kt`) is a plain,
  non-`@Composable` top-level function bound by KSP; its body is `modifier.composed { ... }`, and the
  composable-typed lambda literal is where `currentComposer` resolves, exactly as `PythonComposition`'s
  does. What "admits a composer" turned out to mean, and what needed proving rather than inferring from
  bytecode, is whether that composer is wired into a *real, positionally-stable slot table* rather than
  a placeholder that merely satisfies the type — `ComposedRenderTest
  .theSameCompositionReusesTheRememberedSlotAcrossRecompositions` measures it: a `remember { }` inside
  the `composed { }` lambda allocates once (`remembersAllocated == 1`) and is *reused* across six
  forced recompositions of the same node, even though `PythonComposition` re-`exec`s the Python source
  on every pass and so calls `pythonComposed` itself afresh each time, building a brand-new
  `ComposedModifier` Kotlin object — the slot survives because `Modifier.composed`'s own
  materialize-time group is positional, not object-identity-based, which is the mechanism that lets a
  real `Modifier.clickable`'s internal `remember(interactionSource) { ... }` survive a modifier chain
  rebuilt from scratch every recomposition. A placeholder composer could not have produced that
  persistence. `ComposedRenderTest.aFactoryProducedModifierIsAppliedAndItsSizeCrosses` is the render
  proof that the `Modifier` `factory` returns is the one actually measured, not a stub: a Python
  `factory` calling the walked `size__Dp(receiver, 70.0)` measures a 70px-wide placeable, a second one
  with `33.0` measures 33px, and a negative-control identity factory (`return receiver`, no resize)
  measures 8px (`Text("x")`'s own intrinsic width) — ruling out both "the result is ignored" and "any
  factory produces the same fixed size." Not solved: `factory`'s one Python reference is captured by
  the closure and never released (no `RememberObserver` hook exists for a bare captured `PyObject` the
  way `content=`'s `PythonCallableScope` has one) — the same documented, bounded shape as
  `pythonDraggable`'s leak above, out of scope for this question.
- **`swipeable`, `modifierLocalProvider`, `anchoredDraggable`** share a limit `pythonPointerInput`'s
  technique does not reach: the unspellable part is a **type parameter** (`T` in `SwipeableState<T>`,
  `ProvidableModifierLocal<T>`, `AnchoredDraggableState<T>`), not a suspend modifier. A hand-written
  wrapper can dodge this the same way `PyObject`-typed parameters dodge every other type problem at
  this boundary, but only per concrete `T` — `pythonSwipeableFloat(modifier, state:
  SwipeableState<Float>, ...)`, one declaration per instantiation a caller wants, not one wrapper that
  covers the whole generic surface the way `pythonPointerInput` covers every `pointerInput` call site.
  Not attempted, and not the same shape of fix.

## §10 The zero-byte material3 stubs, ten of them judged

The downstream package carries twenty-nine files under `material3`, twenty-seven of them zero
bytes. They were never wrappers that got emptied: nothing had established whether the adaptation
layer produces those declarations at all, which is a different question from the one the deletions
answered. Ten are answered now, by render tests in `ksp-fixtures/compose` (`M3ProofRenderTest`), on
the evidence model already in use -- pixels for components that leave background visible, distinct
colours for the ones whose own container covers the scene.

| declaration | evidence |
|---|---|
| `HorizontalDivider` | 200 px against 0 for an empty body |
| `RadioButton` | selected 254 px, deselected 166 px -- the argument reaches the component |
| `LinearProgressIndicator` | 800 px at `progress=0.75` |
| `CircularProgressIndicator` | 414 px at `progress=0.75` |
| `Surface` | 192 px with content, 0 without |
| `Scaffold` | 34 distinct colours with content, 1 without |
| `Slider` | 1062 px, and the pixel sets differ between two values |
| `TopAppBar` | 37 distinct colours with a title, 1 without |
| `FloatingActionButton` | 173 distinct colours with content, 140 without |
| `TabRow` + `Tab` | 22 distinct colours with a text slot, 2 without |
| `Checkbox` | checked 400 px, unchecked 144 px |
| `Switch` | checked 1492 px, unchecked 1496 px |
| `BottomAppBar` | 34 distinct colours with content, 1 without |
| `NavigationBar` | 34 distinct colours with content, 1 without |
| `NavigationRail` | 34 distinct colours with content, 1 without |
| `ExtendedFloatingActionButton` | 174 distinct colors with content, 141 without |
| `Snackbar` | 85 distinct colors with content, 55 without |
| `AlertDialog` | 198 distinct colors with content, 101 without |
| `NavigationDrawerItem` | 127 distinct colors with content, 94 without |
| `SearchBar` | 127 distinct colors with content, 94 without |

Two things came out of it that are worth more than the ten.

**A component that animates forever cannot be rendered by this harness.** The indeterminate
progress indicators run an infinite transition, so the composition never goes idle and
`ImageComposeScene.render` never returns. It presents as a slow suite: thirty-seven minutes with
seven seconds of CPU, diagnosed from a thread dump rather than from the log, which said nothing at
all. Both tests use the determinate overload instead. Anything else that animates without input
will hang the same way.

**A Python callable is refused for a slot typed `() -> Float`, while one for `() -> Unit` is
accepted.** The dispatcher lists all three overloads and rejects the call. Every function slot
exercised before this was a callback returning `Unit`, so nothing had touched the value-returning
case. `aValueReturningFunctionSlotDoesNotYetAcceptAPythonCallable` pins it by asserting the
dispatcher's own message; when that test fails, the coercion has been added and the two progress
tests should move to the non-deprecated overload in the same commit.

Seven components remain unjudgeable in this harness, and the reasons why:

1. **`Typography` / `Shapes` / `dynamicLightColorScheme`** (`typography.py`, `shape.py`, `dynamic_color.py`): `Typography` and `Shapes` are ordinary (non-`value`) classes, not data classes, and `ArtifactScanner.constructorCandidates` used to bind only `value`-class constructors -- both were simply never scanned, not declined. That gate is now general: every public, non-abstract, non-generic class's constructor is attempted, and `ArtifactScanner.dropCollidingConstructors` declines one only when it would shadow a bound factory function or a same-named constant (`TextStyle.Default` is the case that forced the second rule -- see `ArtifactScannerTest.aTypographyOrShapesConstructorBindsButATextStyleConstructorDoesNot`). Neither `Typography` nor `Shapes` has that problem, so `Typography(…)` and `Shapes(…)` are both callable from Python now. Neither is convenient, though: `Typography` declares 15 constructor parameters, over `ArtifactScanner.MAX_OMITTABLE_PARAMETERS` (6)'s cap on how many defaulted parameters a generated call is willing to omit (`§4.5`, `2^n` presence branches per omission set), so every `Typography(…)` call must name all 15 -- `M3ProofRenderTest.typographyChangesFontRender` does exactly that. `Shapes`' 5 parameters stay under the cap. `ColorScheme` is exported because it has a top-level factory function, but calling it fails at runtime because its 36 parameters exceed the 31-bit default omission limit. `dynamicLightColorScheme` is an Android-only API and absent from the desktop jar.

   **What widening the gate costs, measured rather than assumed.** Comparing the bound-name sets
   generated for `ksp-fixtures:compose` before and after, the corpus goes from 1417 to 1497 names.
   Seven of the additions are paid for: these were bound before, as value-class constructors, and
   the constant-collision rule now declines them --
   `androidx.compose.ui.unit.Dp`, `androidx.compose.ui.unit.TextUnitType`,
   `androidx.compose.ui.text.font.FontStyle`, `androidx.compose.ui.text.style.BaselineShift`,
   `androidx.compose.ui.draw.BlurredEdgeTreatment`,
   `androidx.compose.ui.hapticfeedback.HapticFeedbackType` and
   `androidx.compose.ui.input.pointer.PointerButton`. In every one of the seven the constructor
   takes an opaque `Int`/`Float` and the constants it would shadow (`Dp.Hairline`,
   `FontStyle.Italic`, `HapticFeedbackType.LongPress`, ...) are the names a Python caller actually
   wants, so the trade is worth taking -- but it *is* a trade, and `Dp(8.0)` stops being callable
   from Python. What would remove the trade rather than choose a side is making a bound bare name
   serve as both a callable and a namespace; that is an adapter change, not a scanner one, and is
   not attempted here.
2. **`DatePicker` / `TimePicker` / `SwipeToDismiss` / `BottomSheet`** (`date_picker.py`, `time_picker.py`, `swipe_to_dismiss.py`, `bottom_sheet.py`): Previously thought to be blocked by complex state objects. However, testing confirms that their state factory functions (`remember_date_picker_state`, `remember_time_picker_state`, `remember_swipe_to_dismiss_box_state`) **are** successfully bound and exported. Passing them from Python to the respective composables works flawlessly in the render harness.
3. **`DropdownMenu`** (`menus.py`): Renders in a separate popup window layer. As confirmed by tests, `ImageComposeScene.render()` fundamentally does not capture these detached layers, resulting in 0 distinct colors despite being fully functional.

### §9.5 `anchoredDraggable`, through a concrete type

The three modifiers declined for an unspellable type parameter are not equally blocked, and one of
them is now bound. `anchoredDraggable` takes its anchors and its state over a generic `T`; a wrapper
that fixes `T` to `String` binds as an ordinary top-level function, the way the identifier wrapper
fixed its own tag type. String was chosen because the states a swipe moves between are discrete
names -- "start", "end" -- and a caller writes them literally.

Proven the way the drag wrapper was: a real gesture crosses the boundary, Python asserts it received
the anchor it settled toward rather than a stub, and a gesture outside the box invokes nothing. Ink
moves 58 to 59 pixels, 68 pixels change. Removing the wrapper fails both tests.

It carries the same unfixed lifetime as the plain drag wrapper -- the confirm callback is held by the
state object and never closed -- and that is stated in its own documentation rather than discovered
later. `DraggableLeakTest` already pins the shape of that leak and the reason interning cannot reach
it.

`modifierLocalProvider` is now bound too: the same technique applies (one wrapper per concrete type). See §9.7 for details on its lifetime and validation. `swipeable` is now bound too -- see §9.6.

### §9.6 `swipeable`, through a concrete type, and a second slot that needed dodging entirely

`swipeable` (`androidx.compose.material.SwipeableKt.swipeable-pPrIpRY`) has the same unspellable-`T`
shape `anchoredDraggable` does -- `javap -p` against the real Compose Material 1.7.0 desktop jar (no
sources jar for this version) confirms the table's signature exactly:

    <T> Modifier swipeable-pPrIpRY(Modifier, SwipeableState<T>, Map<Float, ? extends T> anchors,
        Orientation, boolean enabled, boolean reverseDirection, MutableInteractionSource,
        Function2<? super T, ? super T, ? extends ThresholdConfig> thresholds,
        ResistanceConfig, float velocityThreshold)

`fixture.compose.pythonSwipeableString` fixes `T` to `String`, the same choice and the same reason as
`anchoredDraggable`'s wrapper. It is a plain top-level function, bound by KSP exactly like
`pythonAnchoredDraggableString`; `Modifier.swipeable` itself stays declined, correctly.

`thresholds: (T, T) -> ThresholdConfig` is where this wrapper is not a copy of `anchoredDraggable`'s.
It is a **value-returning** function slot -- it must produce a `ThresholdConfig`, not `Unit` -- and §10
already measured that a value-returning slot (`() -> Float`) is refused by the dispatcher where a
`() -> Unit` slot is accepted. Substituting a `PyObject` here would hit that same refusal, and even if
it didn't, the callable would have to construct a `ThresholdConfig`, itself unspellable from Python.
So `thresholds` never crosses to Python at all: `pythonSwipeableString` takes a `thresholdFraction:
Float` -- an ordinary marshalled value, not a callable -- and Kotlin closes over it to build the lambda
itself, `{ _, _ -> FractionalThreshold(thresholdFraction) }`. Nothing about `FractionalThreshold` uses
the `(T, T)` pair it is handed, so fixing the fraction ahead of time loses nothing the real slot would
have used them for. `SwipeableState`'s own `confirmStateChange: (T) -> Boolean` is a second
value-returning slot, sidestepped the same way `anchoredDraggable`'s `confirmValueChange` is: `Python`
is invoked as a one-way notification and the Kotlin lambda always returns `true`, never carrying a
Python return value back into Compose.

Proven the same way: `SwipeableRenderTest` drives a real drag past the fractional threshold and asserts,
in Python, that `_on_value_change` received `"end"` (not a stub), and that a press outside the box
invokes nothing. Ink moves 58 to 28 pixels, 72 pixels change. Confirmed red first: with
`PythonSwipeable.kt` removed, both tests fail with `cannot import name 'pythonSwipeableString'`;
restored, both pass.

Same unfixed lifetime as the other two: `onValueChange` is held by the `SwipeableState` and never
closed; `DraggableLeakTest` already pins the shape of that leak family and is not repeated here.

`Modifier.swipeable` and the `SwipeableState` constructor it built on are themselves
`@Deprecated` in this Compose version (`w:`, not `e:` -- "Material's Swipeable has been replaced by
Foundation's AnchoredDraggable APIs"), which is why `@OptIn(ExperimentalMaterialApi::class)` alone was
enough to compile; the deprecation did not need suppressing to reach green.

### §9.7 `modifierLocalProvider` and `modifierLocalConsumer`, providing structural proofs

The last outstanding generic `T` rejection in §9.2 was `modifierLocalProvider`. It is now bound using the same concrete type wrapper technique (for `String`), exposing `pythonModifierLocalProviderString` and `pythonModifierLocalConsumerString`.

This binding provides a proof that values actually traverse the composition structural tree: the value is provided in a parent composable and read in a child composable. The test (`ModifierLocalRenderTest`) confirms that the child consumer reads the provided value across the layout tree, and includes a negative control proving that when unprovided or detached, the consumer reads the `defaultValue`.

Same unfixed lifetime as the others: the `onRead` callback passed to `pythonModifierLocalConsumerString` is held by the consumer block and never closed.
