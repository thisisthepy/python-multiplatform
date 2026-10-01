# Kotlin extension functions, value classes, and the Python surface

Compose is the first library whose API this binder has to reach that is *mostly* extension
functions over value-class parameters. `Modifier.padding(16.dp)` is the shape, and neither half of
it — the extension receiver nor the `Dp` — survives into JVM bytecode as itself.

This file records what the Compose artefacts actually contain, what the artefact walker binds from
them today, and what the Python surface should look like. Everything under "Measured" was counted
from real jars on 2026-08-14 and the commands are reproducible from §1. Everything under a
"Judged" heading is a decision, and the alternatives that were rejected are named.

## Status — what this document has been overtaken by, and where

This file was written at commit `f378ce87` (2026-08-14 15:28). Two commits landed within the hour
that invalidated parts of it. Nothing measured here has been deleted; each superseded claim is left
in place with what replaced it, because the old measurement is why the replacement was written.

| what | said here | now |
|---|---|---|
| §2.3, the "JVM params" column | nine values | **all nine were one low**; `$default` was not counted. Corrected in §2.3, with the miss recorded |
| §3, what the walker binds from Compose | **zero**, behind three independent gates | **314 declarations, 104 of them `Modifier` extensions** (`958c0082`). Original funnel kept; §3.2 is the re-measurement |
| §3, the metadata-kind (`k=1`) gate | open problem | closed by `15fc5a62`, 25 minutes after this file was committed |
| §3/§6, the type gate | open problem | closed by `f455ef41`; `TypeTag.OBJECT` was never the missing piece (§3.2) |
| §3.1/§6, overload dispatch | unsolved, costing 33 of 130 `Modifier` names | rule chosen and implemented (`ArtifactScanner.disambiguateOverloads`); §3.1 records it |
| §4.2, the `Modifier` metaclass | proposed | **measured not to work**, at run time *and* in mypy. See `docs/design/pyi-generation-design.md` §4.3–§4.4 |

Still true and untouched: all of §2 except the one column, §4.1, §4.3, §4.4, §4.6, §5, and two of
§6's five open items.

---

## 1. What was measured, and with what

The build pins Compose Multiplatform **1.6.11** (`gradle/libs.versions.toml`, `compose-plugin`),
so the primary corpus is the 19 `org.jetbrains.compose.*` desktop jars the Gradle cache had
resolved. As a cross-check the same tool was run over the 19 `classes.jar` extracted from the
Jetpack AAR set the cache also holds (`androidx.compose.ui` 1.6.7, `foundation` 1.6.7,
`material3` 1.2.1).

    corpus A   19 jars   org.jetbrains.compose.*  1.6.11 (desktop)      51,605 JVM methods
    corpus B   19 jars   androidx.compose.*       1.6.7 / 1.2.1 (AAR)

The tool is ASM 9.9 (already in the cache, the version the plugin uses) plus
**`kotlin-metadata-jvm` 2.0.20**, which was *not* in the Gradle cache and was fetched from Maven
Central. Metadata is what makes the counts trustworthy: it gives the Kotlin name, the Kotlin
visibility, whether a parameter is an extension receiver, the unerased parameter types, and
whether a parameter has a default. None of those are recoverable from descriptors, which is the
whole point of this document. Scratch tree: `/Volumes/macMini/tmp/composescan`.

The current worker's numbers in §3 are **not** a re-implementation of its filter. The repository's
own `ArtifactScanner.kt`, `JvmDescriptors.kt` and `ArtifactRendering.kt` were copied out
unmodified, compiled standalone with `kotlin-compiler-embeddable` 2.0.20 and run over the same
jars. Sanity check that the harness works: the same binary returns 9 entries from
`kotlin-stdlib-2.0.20.jar` (all `kotlin.jvm.internal` plumbing).

**§3.2's re-measurement does not use this harness.** A standalone copy has to be re-copied every time
the walker changes, and it was already stale by the time §3's zero was recorded. The replacement is
`ArtifactScannerTest.composeModifierExtensionsSurviveBothGates`, which runs *the* `ArtifactScanner`
against the same cached jars on every build — no copy, and no way for the number to go unrecounted.
It walks 6 of the 19 jars rather than all of them; §3.2 says which, and why the two counts are not
comparable.

---

## 2. Measured — the size of the surface

### 2.1 Overall

| | corpus A |
|---|---|
| JVM methods | 51,605 |
| Kotlin functions carrying `@Metadata` | 13,721 |
| … public | 9,954 |
| … public **and top-level** (file facade / multi-file part) | 1,411 |
| … public top-level **extension** functions | **604**, over 136 distinct receiver types |
| … of those 604, JVM name is value-class-mangled | **151 (25.0%)** |

### 2.2 `Modifier`

| | corpus A | corpus B |
|---|---|---|
| functions with a `Modifier` extension receiver | 330 | 321 |
| … public | 251 | 252 |
| … internal | 40 | 37 |
| … private | 39 | 32 |
| public **top-level** (the chainable API) | **177** | — |
| … mangled | **52 (29.4%)** | — |
| public **member** extensions (need a dispatch receiver) | 74, over 32 owner classes | — |

The two ecosystems agree to within one function, so the numbers are a property of Compose and not
of one packaging of it.

Within the 177:

- 130 distinct Kotlin names; **33 names carry more than one overload** (max 5).
- 435 parameters, of which **254 (58%) declare a default**; 82 of the 177 have at least one.
- 1 is `inline`; none is `operator` or `infix`.
- Parameter composition: 31 take only primitives/`String` (or nothing), 52 take at least one value
  class, 79 take at least one object or collection, 66 take at least one lambda.

### 2.3 The named composables

Metadata arity excludes the synthetic parameters; the JVM descriptor does not.

| Kotlin | JVM name | mangled | Kotlin params | JVM params | as first recorded |
|---|---|---|---|---|---|
| `material3.Text(String, …)` | `Text-fLXpl1I` | yes | 16 | **20** | 19 |
| `material3.Text(String, …)` | `Text--4IGK_g` | yes | 17 | **21** | 20 |
| `material3.Text(AnnotatedString, …)` | `Text--4IGK_g` | yes | 17 | **21** | 20 |
| `material3.Text(AnnotatedString, …)` | `Text-IbK3jfQ` | yes | 18 | **22** | 21 |
| `material3.Button` | `Button` | no | 10 | **13** | 12 |
| `material3.Card` (clickable) | `Card` | no | 9 | **12** | 11 |
| `material3.Card` (container) | `Card` | no | 6 | **9** | 8 |
| `foundation.layout.Column` | `Column` | no | 4 | **7** | 6 |
| `foundation.layout.Row` | `Row` | no | 4 | **7** | 6 |

**The last column is what this document first recorded, and every one of its nine rows was one
low.** `docs/design/pythonx-adapter-design.md` §5.2 found this by re-counting with `javap -p`; the count
has since been reproduced a third time, independently, against the same 1.6.11 jars. What was
missed is **`$default`**. The trailing JVM parameters of `Button` are `Composer, int, int` and of
`Text-fLXpl1I` are `Composer, int, int, int` — `$composer`, one or two `$changed` masks, and a
`$default` mask.

`$default` is easy to miss because Compose does not carry it the way the rest of Kotlin does.
`javap -p` over `ButtonKt` and `TextKt` shows **no `Button$default` or `Text$default` bridge method
exists at all**: the Compose compiler declares the mask as a trailing parameter of the function
itself instead. Reproduce with:

    unzip -o -q "$GRADLE_CACHE/.../material3-desktop-1.6.11.jar" 'androidx/compose/material3/ButtonKt.class'
    javap -p androidx/compose/material3/ButtonKt.class

The conclusion drawn from this table — *never derive arity from a descriptor; metadata arity is the
source* (§4.6) — is unaffected, and the correction strengthens it: the error was made by counting
descriptors by hand, which is the exact failure the rule forbids a machine to make. The `+2/+3/+4/+5`
distribution in §2.6 was **not** re-derived and may be shifted the same way; treat it as unconfirmed.

`Text` is mangled because `color`, `fontSize`, `letterSpacing` and `lineHeight` are value classes;
`Button`, `Card`, `Column`, `Row` are not mangled because none of their parameters is.

**`Text--4IGK_g` appears twice in the same class with different descriptors.** That is not an
anomaly — across corpus A, **108 mangled JVM names are ambiguous within their own class**. The
mangling suffix is a hash of the *value-class parameter signature only*, so it is shared by
unrelated functions: `-3ABfNKs` is worn by `padding`, `size`, `width`, `height`, `requiredSize`,
`requiredWidth`, `requiredHeight`, `times` and `surfaceColorAtElevation`, all of which take a
single `Dp`; `-VpY3zN4` is worn by thirteen functions taking two `Dp`s.

This retires an approach rather than complicating it. Any binder that *looks a function up by
name* — Java reflection, a Python `__dict__` sweep, the 2024 `pythonx-compose` probe that scanned
`ButtonKt.__dict__` for keys beginning `"Button-"` — cannot address a Compose function
unambiguously even in principle. This binder does not look functions up by name: it emits Kotlin
source that says `Modifier.padding(16.dp)` and lets `kotlinc` do the mangling. The mangled name
never appears in anything we generate.

### 2.4 Value classes

111 value classes in corpus A. Underlying types: 65 `Int`, 25 `Long`, 3 `IntArray`, 3 `Float`,
3 `Any`, 2 `FloatArray`, 1 `ULong`, 1 `Short`, 7 wrapping another reference type (`Composer` ×2,
`Constraints`, `Shape`, `AtomicReference`, `MutableState`, `Operations`), 1 generic.

The ones that matter, with their real underlying type and the visibility of the constructor that
takes it:

| value class | wraps | field | constructor |
|---|---|---|---|
| `ui.unit.Dp` | `Float` | `value` | **PUBLIC**`(Float)` |
| `ui.unit.TextUnit` | `Long` | `packedValue` | INTERNAL |
| `ui.unit.DpSize` / `DpOffset` / `IntSize` / `IntOffset` | `Long` | `packedValue` | INTERNAL |
| `ui.unit.Constraints` | `Long` | `value` | INTERNAL |
| `ui.geometry.Offset` / `Size` / `CornerRadius` | `Long` | `packedValue` | INTERNAL |
| `ui.graphics.Color` | `ULong` | `value` | **PUBLIC**`(ULong)` |
| `ui.graphics.TransformOrigin` | `Long` | `packedValue` | INTERNAL |
| `ui.semantics.Role` | `Int` | `value` | PRIVATE |
| `ui.text.style.TextAlign` | `Int` | `value` | INTERNAL |

**`Sp` does not exist.** The premise that `Dp` and `Sp` both wrap `Float`, and therefore collide
under raw-number coercion, is wrong twice over. `16.sp` produces a `TextUnit`, and `TextUnit` is a
**packed** `Long`:

    getSp(float) = pack(0x1_0000_0000L, value)      // observed in TextUnitKt bytecode

The high word is the unit tag (`1` = Sp, `2` = Em, `0` = Unspecified) and the low word is the raw
float bits. A raw Python `16` handed to a `TextUnit` parameter therefore has tag `0` and decodes
as `TextUnit.Unspecified` — not "16 of the wrong unit", but *silently no value at all*.

Only **three** value classes in all of Compose wrap a `Float`: `Dp`, `text.style.BaselineShift`,
and `LineHeightStyle.Alignment`. Ambiguity between float-shaped units is a non-problem; packing is
the problem.

`Color` is the same trap in a different place. Its constructor is public and takes `ULong`, but
what a Kotlin author writes is the *factory function* `ColorKt.Color(Int)`, whose whole body is a
32-bit left shift:

    Color(int) = ULong(value) shl 32                // observed in ColorKt bytecode

So `0xFFFF0000` means red **through the factory** and means transparent-black **through the
constructor**. The 2024 notebook's `color=0xFFFF0000` worked only because a hand-written Kotlin
shim (`Material3.kt`) declared `color: Long` and called `Color(color)` on the Kotlin side.

Value classes appearing as parameters of the 177 public top-level `Modifier` extensions, by
occurrence: `Dp` 56, `Role` 11, `Color` 8, `TextUnit` 4, `TransformOrigin` 4, `DpSize` 2,
`BlurredEdgeTreatment` 2, `MarqueeAnimationMode` 1, `CompositingStrategy` 1, `PointerEventType` 1.
**`Dp` alone is 62% of all value-class parameter occurrences on `Modifier`.**

### 2.5 Name collisions — far rarer than assumed, and the first count was wrong

Counted naively over *every* public extension function, 41 `(receiver, name)` pairs appear in more
than one package, which would have made a "require qualification on collision" rule
unaffordable. That count is an artefact. Most of it is **member** extension functions — `Dp.toPx`
is declared inside the `Density` interface, `Modifier.align` inside `RowScope` — which metadata
records again in every implementing class. They are not competing top-level declarations and
Kotlin's import scope never sees them as such.

Restricted to public **top-level** extensions, which is the set Kotlin's import scope actually
governs:

- 507 distinct `(receiver, name)` pairs
- **5** are declared in more than one package:

| receiver.name | packages |
|---|---|
| `Modifier.contextMenuOpenDetector` | `foundation`, `material` |
| `Modifier.minimumInteractiveComponentSize` | `material`, `material3` |
| `Double.times` | `ui.geometry`, `ui.unit` |
| `Float.times` | `ui.geometry`, `ui.unit` |
| `Int.times` | `ui.geometry`, `ui.unit` |

- 11 more pairs are split across two file facades *within* one package, which is an overload set,
  not a collision.

Two of the five are `material` versus `material3`, which an application does not mix. The rule
costs five names in the whole library.

### 2.6 `@Composable`

500 public top-level `@Composable` functions in corpus A (counted on the exact annotation
descriptor; matching on the substring `Composable` inflates this to 524 by catching
`ComposableTarget` and friends). 183 are mangled.

Every one of them gains synthetic JVM parameters — `$composer`, one or two `$changed` bitmasks,
zero to two `$default` bitmasks:

    +2 params : 80      +3 params : 349      +4 params : 62      +5 params : 9

**Unconfirmed since §2.3's correction.** This distribution was counted the same way §2.3's JVM-params
column was, and that column was one low on all nine rows. It has not been re-derived. If it is
shifted identically, `+2` means "composable with no defaulted parameter" and the nine functions of
§2.3 are all `+3` or `+4`, which is what re-counting them gave.

**The overlap with extension handling is one function.** Of the 500, 41 are extension functions,
and exactly one of those is a `Modifier` extension (`ui.ExternalDrag_desktopKt.onExternalDrag`).
The remaining 40 sit on `Transition`, `InfiniteTransition`, `InteractionSource`, `RowScope`,
`ColumnScope` and window scopes. Composables and chainable modifiers are, empirically, two
disjoint problems.

---

## 3. Measured — what the artefact walker bound from Compose, and what it binds now

**This section is kept as it was measured, because the zero is why the work that follows it
happened.** It describes commit `f378ce87` (2026-08-14 15:28). Two gates are named in the funnel
below and a third in §3.1; all three are gone, and §3.2 is the re-measurement. Read §3 and §3.1 as
the diagnosis and §3.2 as the outcome — not as a description of the walker today.

**Zero. From all 19 jars of corpus A, and all 19 of corpus B.**

Not "a small subset", not "the unmangled ones". `ArtifactScanner.scanJar` returns an empty list for
every Compose artefact. Running the funnel stage by stage over corpus A's 51,605 JVM methods:

| after | surviving |
|---|---|
| all JVM methods | 51,605 |
| class binary name has no `$` | 30,897 |
| class is public, not synthetic/annotation | 27,514 |
| `@Metadata` kind is `class` (k=1) or absent | 21,220 |
| method is public **and** static | 3,381 |
| not synthetic, not bridge | 935 |
| not `<init>` / `<clinit>` | 935 |
| no `$` in method name | 918 |
| **no `-` in method name** | **190** |
| every parameter and return type admitted by `boundaryTypeOf` | **0** |
| unique name within its class | 0 |

Two independent gates each zero Compose on their own.

**The metadata-kind gate.** A Kotlin top-level function compiles into a file facade (`k=2`) or a
multi-file part (`k=5`), and the walker declines both because their JVM names are not names Kotlin
can write. That drops **all 1,411 public top-level Compose functions**, mangled or not —
`ButtonKt.Button` and `PaddingKt.padding` alike. The entire top-level Compose API is behind this
one gate.

> **Closed by `15fc5a62`, 25 minutes after this was written.** The gate was one line —
> `kotlinMetadataKind(node)?.let { if (it != KOTLIN_KIND_CLASS) return emptyList() }` — and it is
> gone: `ArtifactScanner.scanClassNode`'s `when` now dispatches on the decoded
> `KotlinClassMetadata`, with arms for `FileFacade` (`k=2`) and `MultiFileClassFacade` (`k=4`,
> which fetches each part by name and reads the part's own `kmPackage`). The `KOTLIN_KIND_CLASS`
> constant survives only in `metadataKinds`, a test-facing helper that reads the kind byte without
> decoding the payload. The paragraph above was true of the commit it describes and of nothing
> after it.

**The type gate.** Of the 190 methods that reach it, none survives: every one takes or returns
something that is not a primitive, `String`, `byte[]` or `void`.

> **Closed by `f455ef41`, and the diagnosis here was one step short.** "An object-handle boundary
> type that `boundaryTypeOf` does not have" (§6) named the wrong missing piece.
> `python.multiplatform.reflection.TypeTag.OBJECT` **already existed**, and so did the entire
> runtime path behind it: `UpcallTrampoline.marshalResult` already put a returned Kotlin object into
> `HandleTable` and handed Python the integer, and `toKotlinObject` already resolved it back. All of
> that predates this document — it is checkable in the tree at `f378ce87`. What the *walker* lacked
> was not a marshalling category but a **Kotlin type name to cast the handle to**, which a JVM
> descriptor genuinely cannot supply (`Ljava/util/List;` has no Kotlin spelling Kotlin will accept
> written out). `@Metadata`'s classifier *is* that name — `androidx/compose/ui/Modifier`, `/` for
> `.` and nothing else to guess — so the object case lives in `resolveKotlinType`
> (`KotlinMetadata.kt`) and the descriptor-only `boundaryTypeOf` still has no `OBJECT` arm, on
> purpose. The gate was real; "no boundary type exists" was not the reason it was shut.

**The `-` filter is not what is blocking Compose.** It removes 728 of the 918 methods that reach
it, but the 190 it lets through die at the next gate anyway. This matters for how the gap is
described: the mangling filter is being replaced right now by metadata-driven understanding, and
when it is gone the Compose number will still be zero until the facade gate and the type gate move
too. The 52-of-177 mangling figure in §2.2 is therefore not a measure of what is unreachable — it
is a measure of **how much surface the metadata work opens that a descriptor-only walker could
never have named**.

`Modifier.padding` is the whole argument in one function:

| JVM name | mangled | Kotlin parameters |
|---|---|---|
| `padding` | no | `paddingValues: PaddingValues` |
| `padding-3ABfNKs` | yes | `all: Dp` |
| `padding-VpY3zN4` | yes | `horizontal: Dp = …, vertical: Dp = …` |
| `padding-qDBjuR0` | yes | `start: Dp = …, top: Dp = …, end: Dp = …, bottom: Dp = …` |

The one unmangled overload is the one a Python user is least likely to want.

### 3.1 The rule that will hurt next: dropping ambiguous overloads

The walker drops a name entirely when more than one binding would carry it. Applied to `Modifier`
that costs **33 of 130 names**, and the casualty list is the API's centre of gravity:

    absoluteOffset background blur border clickable combinedClickable composed
    consumeWindowInsets contextMenuOpenDetector focusOrder graphicsLayer height
    minimumInteractiveComponentSize offset onClick padding paddingFrom paddingFromBaseline
    pointerInput progressSemantics pullRefresh requiredHeight requiredSize requiredWidth
    scale scrollable selectable shadow size toggleable transformable triStateToggleable width

Overload dispatch is a larger problem than name collision (§2.5: five cases) and is unsolved here.
Python's own answer — one function that inspects its arguments — is available because the Python
side is a dispatcher, not a one-to-one mirror; see §4.4.

**Solved by `f455ef41` — by distinguishing, not by choosing.** The principle that made the drop-rule
right survives intact and is worth restating, because it is what rules out the obvious fix: nothing
may arbitrate. `org.junit.Assert.assertEquals` has eight bindable overloads and a sort order picks
the deprecated `(double, double)` that always fails, so `assertEquals(3, 3)` from Python would
silently call the one that cannot work. That is still refused.

What the drop-rule did not have was a way to *tell the overloads apart*: with only JVM descriptors
there is no Kotlin parameter type to name one by, and the JVM name cannot do it either — §2.3 counts
108 mangled JVM names that are ambiguous inside their own class. `@Metadata` supplies the declared
Kotlin types, so `ArtifactScanner.disambiguateOverloads` now gives each member of a set a name of its
own:

- a group of **one** keeps the bare Kotlin name — always, so a bare name still means exactly one
  declaration;
- a group of more than one gets `name__<types>`, under the **first of three schemes that separates
  the group**: the simple names of the declared value parameters; then the receiver joined to them
  (for overloads differing only in what they extend, `Int.times` versus `Double.times`); then fully
  qualified names (for two parameter types sharing a simple name);
- a group **no scheme separates** is still dropped. The old rule survives as the floor.

`padding` comes out as `padding__Dp`, `padding__Dp_Dp`, `padding__Dp_Dp_Dp_Dp` and
`padding__PaddingValues` — pinned by
`ArtifactScannerTest.composeModifierExtensionsSurviveBothGates`. The receiver is left out of the
suffix unless it is what separates the group, since spelling every `Modifier` extension
`padding__Modifier_Dp` restates the one parameter the reader already knows from where the name is
attached.

One further change came with it: **the grouping moved from the class to the whole jar.** Ambiguity is
counted over `ArtifactScanner.scanJar`'s complete result rather than inside one `scanClassNode` call,
because an overload set is a property of a *package* and Kotlin lets one live in two files. §2.5's
"11 more pairs are split across two file facades within one package" is exactly that case: two
`ClassNode`s, one Kotlin name, invisible to a per-class grouping, which would have emitted both under
the same table key.

The dispatcher §4.4 asks for is still wanted and still cannot live here — `UpcallTable` is keyed by
name and `ExposedCallable` carries one fixed arity, so one name reaches one signature by
construction. It belongs in `pythonx` (`docs/design/pythonx-adapter-design.md` §4.1) and selects among
these names. This layer's job is to make that choice *possible*, not to make it.

### 3.2 Re-measured at `958c0082`: 314

The successor to the zero above, out of the same Gradle cache and the same
`org.jetbrains.compose.*` 1.6.11 release — though over a subset of the jars, which is why the counts
below are not the ones in §2 with different values:

    compose: 314 declarations bound, 104 of them Modifier extensions
    compose: 45 public top-level Modifier extensions declined, 43 of them for a function-typed parameter

That is not a transcription of a one-off run.
`ArtifactScannerTest.composeModifierExtensionsSurviveBothGates` re-derives it on every build,
walking the real jars out of the Gradle cache and printing the counts as well as asserting a floor,
so the number cannot quietly rot into a passing assertion that measures nothing. The run reproduced
here: 18 tests, 0 failures.

**This is a different corpus from §2's, and the two counts must not be subtracted.** Read carefully:

| | §2 (corpus A) | §3.2 |
|---|---|---|
| jars walked | 19, every `org.jetbrains.compose.*` the cache held | **6** — `foundation-layout`, `ui-unit`, `foundation`, `ui`, `material`, `material3`, all 1.6.11 desktop |
| jars on the resolution classpath | — | 15 (the six plus `ui-geometry`, `ui-graphics`, `ui-text`, `ui-util`, `runtime`, `runtime-saveable`, `animation-core`, `collection`, `annotation`, and `kotlin-stdlib`) |
| package filter | none | `androidx.compose` |
| unit counted | **declarations**, and for `Modifier` the 177 public top-level extensions over 130 distinct Kotlin names | **bindings**, after overload disambiguation |

So the 104 and the 177 are not the same quantity twice. `padding` alone contributes **four** entries
to the 104 and **four** declarations to the 177 but only **one** name to the 130; and the six jars
are a subset of the nineteen. The declined count is in a third unit again — 45 distinct Kotlin
*names*, deduplicated — so 104 + 45 sums to nothing meaningful. **The number of distinct Kotlin
names among the 104 bindings was not measured**, and no comparison against 130 is claimed here.

The classpath is separate from the walked set for a reason §1's tooling did not have to face:
`androidx.compose.foundation.layout.padding` takes a `Dp` that lives in `androidx.compose.ui.unit`,
so a value class or an extension receiver has to be resolvable from jars that are not themselves
being bound.

**What the remaining 45 are, counted rather than asserted.** 43 of them declare a function-typed
parameter. A Python callable cannot become a Kotlin `FunctionN` at this boundary —
`UpcallTrampoline.toKotlinObject` would hand the cast a `PyObject` — so binding them would produce
entries that always fail, and declining them is correct rather than pending. §2.2's "66 of the 177
take at least one lambda" is the same fact seen from the other side. This is the honest remaining
limit on the `Modifier` chain and it is not a metadata problem.

The chain itself runs. `WalkedArtifactComposeModifierTest` assembles
`Modifier.padding(16.dp).size(24.dp)` **from Python**, out of Compose's own jars, under Compose's own
names — no `Composer` anywhere, because §2.6 already established that a `Modifier` extension is not a
composable. What Python holds is an integer handle per link, and **nothing releases them**: a bare
handle returned from a `CallableKind.FUNCTION` reaches Python as an `int`, which has nothing to hang
a finaliser off. Three handles leak per run of that test, deliberately, because owning them is what
§4.1's proxy is for and §4.1's proxy does not exist yet.

---

## 4. Judged — the Python surface

### 4.1 An extension function becomes a method on its receiver's proxy

Adopted, as proposed. Chaining then costs nothing: each of the 177 `Modifier` extensions returns
`Modifier`, so `Modifier.padding(16).background(...)` is ordinary Python method chaining with no
combinator machinery. This is what the 2024 attempt never reached —
`pythonx/compose/ui/modifier.py` is a copy of the Button wrapper in which `padding()` composes
nothing and `fill_max_size()` returns `self`.

Not every receiver can take a method, and the split was counted:

| receiver of the 604 public top-level extensions | count | Python placement |
|---|---|---|
| ordinary class or interface | 514 | method on the proxy type |
| Compose value class | 41 | method on the value-class proxy |
| Kotlin collection | 29 | module-level function |
| Kotlin primitive or `String` | 20 | module-level function |

Python cannot attach a method to `int`, so `Int.toDp` and its 19 siblings become
`pythonx.compose.ui.unit.to_dp(x)`. That is a real loss of fidelity and it is confined to 20
functions.

### 4.2 `Modifier` as one name for both the type and the empty modifier

`Modifier` in Kotlin is an interface, and `Modifier` as an *expression* is `Modifier.Companion`.
The relevant observation is that **`Modifier$Companion` implements `androidx.compose.ui.Modifier`**
— it is an instance of the very type it is the companion of, and it is the empty modifier that
starts every chain.

So Python does not need to reconcile two things. It needs one class whose *class object* also
behaves like an instance. A metaclass gives that directly:

```python
class _ModifierMeta(type):
    def padding(cls, *args, **kwargs): return cls.EMPTY.padding(*args, **kwargs)
    ...

class Modifier(metaclass=_ModifierMeta):
    def padding(self, *args, **kwargs) -> "Modifier": ...
```

`Modifier.padding(16)` resolves on the metaclass and delegates to the companion singleton;
`m.padding(16)` resolves on the instance; `def f(m: Modifier)` still names a type. All three
spellings work, and the generated `.pyi` can state all three (§4.5).

> **"All three spellings work" was not measured, and it is false.** `docs/design/pyi-generation-design.md`
> §4.3 ran it, in CPython 3.13 and in mypy 2.3.0, and the metaclass loses in both.
>
> **At run time**, `Modifier.padding` is the *instance* method, not the metaclass one. That is
> `type.__getattribute__`'s documented order: a metaclass attribute wins only if it is a **data**
> descriptor, and a plain `def` on a metaclass is a non-data descriptor, so the class's own MRO is
> searched first. `Modifier.padding(16)` binds `16` to `self` and raises `TypeError`. It raises only
> because the two arities differ by one — for a pair that happened to match it would bind *silently*,
> which is the worse failure.
>
> **In mypy**, the same stub gives ten errors in twenty lines; every class-object spelling fails and
> only the instance ones pass.
>
> Two fixes work at run time and neither fixes the checker: a `@property` on the metaclass *is* a
> data descriptor and does win, as does a hybrid descriptor in the class body with no metaclass at
> all — but **mypy does not model metaclass data-descriptor precedence** and still resolves the
> class's own method. That combination is the worst available: a correct runtime with a wrong IDE.
>
> What resolves it is to stop making the stub mirror the runtime mechanism. Pick a runtime that
> works (either of the two above) and declare each operation in the stub as a **class attribute whose
> type is a Protocol with an overloaded `__call__`** — `padding: ClassVar[_Modifier_padding]` — which
> checks clean for all three spellings. `docs/design/pyi-generation-design.md` §4.4 adopts that and measured
> it against mypy; PyCharm is listed there as unverified. The *observation* this section is built on
> is untouched — `Modifier$Companion`
> implements `androidx.compose.ui.Modifier`, so one name really can serve as both the type and the
> empty modifier, and the three spellings really are the requirement. Only the mechanism proposed
> for reaching them is retired.

**Rejected: binding the module-level name `Modifier` to the companion instance.** It is simpler at
runtime and reads identically in the common case, but it destroys `Modifier` as an annotation —
a type checker cannot use an instance in `m: Modifier` — and the stubs exist precisely so that a
type checker can.

### 4.3 Collisions demand qualification; they do not get silently resolved

Confirmed, and now with a price attached: **five names** in the whole of Compose (§2.5). The
default binding is the unqualified name; when two packages contribute the same
`(receiver, name)`, neither wins and both are reachable only through a qualified accessor. The
same reasoning as dropping ambiguous overloads — a silent choice is a wrong call that never
reports itself — but the cost here is now known to be negligible rather than assumed to be.

What is lost relative to Kotlin is real and should be said plainly: Kotlin's import scope means an
extension is invisible unless imported, so an application that imports only `material3` never sees
`material`'s `minimumInteractiveComponentSize`. Python attaches everything discovered, so the
surface is the union across all resolved artefacts. Qualification is the price of that union.

### 4.4 Value classes: accept both spellings, but do not infer which by machine

This was framed as a usability choice once mangling stopped being an obstacle. It is not only that.
Measurement says a blanket rule in either direction is wrong.

**The evidence against blanket raw coercion.** For `TextUnit`, raw `16` decodes as `Unspecified`
(§2.4). For `Color`, raw `0xFFFF0000` is not red. For `Offset`, `Size`, `DpSize`, `IntSize`,
`IntOffset`, `DpOffset`, `TransformOrigin`, `CornerRadius`, `Constraints` — every `packedValue`
class, 20 of the 25 `Long`-backed ones — a user-meaningful number is not the stored number. None of
these failures raises; they render wrong.

**The evidence against blanket `dp(16)`.** `Dp` is 62% of value-class parameter occurrences on
`Modifier`, it wraps a plain `Float`, and its constructor is public and *is* the identity on that
float. Forcing a wrapper call there buys no safety at all.

**A machine rule was sought and found insufficient.** "Coerce iff the value class has a public
constructor taking exactly its underlying type" is checkable from metadata and selects 36 of 111.
It correctly excludes `TextUnit`, `Offset`, `Size`, `DpSize`, `DpOffset`, `TextAlign` (INTERNAL)
and `Role` (PRIVATE). **It admits `Color`, which is the second-worst case in the library.** `Color`
passes because its `ULong` constructor is public; it fails in practice because what a Kotlin author
writes is the shifting factory `Color(Int)`, and metadata cannot distinguish a constructor that
stores from one that packs. Whether a wrapper packs is a semantic fact with no bytecode witness.

Adopted:

1. **Every value-class parameter accepts its proxy** — `Modifier.padding(dp(16))` always works.
2. **Raw primitives are accepted only for an explicit allowlist**, seeded with `Dp` and extended by
   hand with evidence. `Dp` covers most of the ergonomic benefit on its own.
3. **Everything not on the allowlist rejects a raw number with an error that names the constructor
   to call.** `Modifier.padding` accepting `16` while `Text(font_size=16)` raises "expected
   TextUnit; write sp(16)" is an inconsistency, and it is the honest one: the two are different
   because `Dp` and `TextUnit` are genuinely different, and hiding that produces a UI that silently
   renders with no font size.
4. **`Color` is on the reject list despite passing the constructor test**, and the allowlist
   mechanism is manual precisely so that this exception has somewhere to live.

Residual risk, stated rather than mitigated: the allowlist is a human judgement per type, and a new
Compose release adding a float-wrapping unit will not be caught automatically. The failure mode of
getting it wrong is a silent visual defect, not an exception.

### 4.5 `.pyi`

Generation belongs to the Gradle plugin (`docs/design/ecosystem.md` §5b), and it reads the same metadata
as the binder, so the stub and the binding cannot drift. Extension-as-method appears as an ordinary
method, and the metaclass of §4.2 is expressible:

> **The stub below is the one that was measured to fail.** mypy resolves `Modifier.padding` to the
> instance method and rejects every class-object call; see §4.2's note and
> `docs/design/pyi-generation-design.md` §4.3. It is kept here because the three bullets that follow it are
> about `@overload`, `Dp | float` and defaults, and all three survive unchanged into the shape that
> does work — `padding: ClassVar[_Modifier_padding]` where `_Modifier_padding` is a Protocol whose
> `__call__` carries exactly these overloads (`docs/design/pyi-generation-design.md` §4.4). What changes is
> where the overloads are written, not which overloads there are.

```python
class _ModifierMeta(type):
    @overload
    def padding(cls, all: Dp | float) -> Modifier: ...
    @overload
    def padding(cls, horizontal: Dp | float = ..., vertical: Dp | float = ...) -> Modifier: ...
    @overload
    def padding(cls, padding_values: PaddingValues) -> Modifier: ...

class Modifier(metaclass=_ModifierMeta):
    @overload
    def padding(self, all: Dp | float) -> Modifier: ...
    ...
```

Three things follow from the measurements:

- **`@overload` is mandatory, not optional.** 33 of 130 `Modifier` names carry more than one
  overload, and they are the load-bearing ones (§3.1). A generator that emits one signature per
  name will mistype `padding`, `size`, `background`, `border` and `clickable`.
- **`Dp | float` is how the allowlist shows up in the stub**, and a type on the reject list is
  stubbed as the proxy alone. The stub is then the documentation for §4.4's asymmetry.
- **Defaults must be carried.** 58% of `Modifier` extension parameters declare one; a stub with
  everything required would be wrong about most of the API. Metadata's `declaresDefaultValue` is
  the source; the *value* is not in metadata, so stubs emit `= ...`.

### 4.6 `@Composable` needs no new machinery here

Two measurements settle this. First, the extension and composable problems barely intersect: 1 of
500 (§2.6). Second, `docs/design/ecosystem.md` §5b already decided that a composable's synthetic
parameters are threaded as ordinary values by the Python adapter. Nothing in extension-as-method
disturbs that, because a `Modifier` extension is not composable and a composable is not a
`Modifier` extension.

What does need care is the arity mismatch: metadata reports Kotlin arity, the descriptor reports
2–5 more. Any code that derives arity from the descriptor will be wrong for all 500. Metadata
arity is the correct source, and the generated Kotlin passes the composer explicitly.

The "2–5" comes from §2.6's distribution, which §2.3's correction leaves unconfirmed; the nine
functions re-counted in §2.3 are all `+3` or `+4`. The rule this paragraph states is what the
correction *proves* rather than what it weakens — the original nine descriptor counts were done by
hand and were all one low, for the same reason a machine would be: a `$default` mask is
indistinguishable from an ordinary trailing `int` unless you already know the Kotlin arity.

Two things this paragraph did not yet know. **`$default` is the third synthetic**, and Compose
carries it as a *declared* trailing parameter rather than emitting a `Button$default` bridge — so
"call the synthetic to omit an argument" is not available for a composable at all
(`docs/design/pythonx-adapter-design.md` §4.5). And **the generated Kotlin cannot pass the composer
explicitly**: `docs/design/pythonx-adapter-design.md` §5.3 finds that Kotlin source can neither call a
`@Composable` from a non-`@Composable` lambda nor name `$composer`/`$changed`/`$default`, which are
not parameters of the Kotlin declaration. That is a property of this generator, not of Compose, and
it is why composables remain blocked while the `Modifier` chain of §3.2 runs.

---

## 5. What the 2024 attempt paid, for comparison

`pythonx-compose` needed a **hand-written Kotlin shim** to make Compose reachable at all:
`Material3.kt`, 228 lines, 20 functions, **16 `@JvmName` annotations** whose only job is to
suppress mangling, 4 wrappers taking `color: Long` so that `Color(...)` could be applied on the
Kotlin side, and 3 taking raw `Float` sizes.

That shim also invented API. `Card(modifier=…, corner_radius=20, color=…)` in `UI.ipynb` is not
Compose — `material3.Card` has no `corner_radius` parameter in either of its overloads (§2.3). The
shim absorbed shape and colour into flat keyword arguments per component, by hand, which is exactly
the per-function wrapping `docs/design/ecosystem.md` §5b rules out.

The modifier chain was never built. `pythonx/compose/ui/modifier.py` lines 20–22 probe
`ButtonKt.__dict__` for a key starting `"Button-"` — a name-based lookup, which §2.3 shows cannot
be made unambiguous for Compose no matter how carefully it is written.

---

## 6. Open

Two of the five below have closed since this list was written, and one has moved rather than closed.
They are kept with their status rather than deleted, since each is the reason a piece of work
happened.

- **Scoped modifiers.** *Still open.* 74 public member extensions on `Modifier` (`RowScope.weight`,
  `ColumnScope.align`, `BoxScope.matchParentSize`, `LazyItemScope.fillParentMaxWidth`, …) need a
  dispatch receiver that Kotlin supplies implicitly from the enclosing lambda. Python has no
  implicit receiver, so `Modifier.weight(1f)` cannot work as written; the scope has to be passed
  into the content callable and the call spelled against it. No shape has been chosen. The walker
  still declines them explicitly — `ArtifactScanner`'s `KotlinClassMetadata.Class` arm filters
  extensions out, so nothing arrives half-bound — and `docs/design/pyi-generation-design.md` §4.5 adds the
  consequence for stubs: they **must not** be stubbed as plain methods on `Modifier`, because a stub
  that says `Modifier.weight(1.0)` checks is a stub promising a call the runtime cannot make.
- **Overload dispatch.** *Moved, not closed.* The "drop ambiguous names" rule that cost 33 of 130
  `Modifier` names is gone; §3.1 records the rule that replaced it and why nothing arbitrates. What
  remains open is the half this layer cannot host: a Python-side dispatcher that inspects argument
  count and type, selecting among `padding__Dp` / `padding__Dp_Dp` / `padding__PaddingValues`. It
  lives in `pythonx` (`docs/design/pythonx-adapter-design.md` §4.1) and still interacts with §4.4 exactly as
  described — if the raw `16` is what selects the `Dp` overload, then raw-primitive acceptance is
  also overload resolution.
- **The type gate.** *Closed*, by `f455ef41`. `Shape`, `Brush` and `PaddingValues` bind now, and the
  paragraph above misnamed what was missing: the boundary *type* (`TypeTag.OBJECT`) and its whole
  runtime (`HandleTable`, `marshalResult`, `toKotlinObject`) already existed; the walker lacked a
  Kotlin type name to cast to, which `@Metadata`'s classifier supplies. See §3's note on the type
  gate. What is left of the 45 declined `Modifier` extensions is **43 with a function-typed
  parameter** (§3.2) — that is the lambda half of "66 take a lambda", and it is a real boundary
  limit, not a missing type.
- **Handle ownership.** *New, opened by closing the type gate.* Every object crossing to Python is a
  strong root in `HandleTable`, and an entry bound by this walker returns it as a bare `int` with
  nothing to hang a finaliser off. A chained `Modifier.padding(...).size(...)` leaks one handle per
  intermediate link until §4.1's proxy exists to own them. Known, bounded, and true of every
  `OBJECT`-returning KSP entry already — but it is now on the Compose path.
- **Whether the allowlist of §4.4 should be data or code**, and where a downstream consumer adds to
  it for a library this repository has never seen. *Still open; a candidate answer exists.*
  `docs/design/pyi-generation-design.md` §5.3 proposes it fall out of the package mapping — the same
  manifest the Python package already owns. Nothing in the plugin implements an allowlist yet:
  `grep -rn allowlist` over the Kotlin sources finds nothing, so §4.4's rules are decided and not
  built.
- **klib.** *Still open, and untouched.* Everything here is JVM, and §3.2's 314 is still JVM only.
  `docs/design/ecosystem.md` §5b records that `LibraryAbiReader` exposes extension receivers and unerased
  types from klibs, which suggests the same design carries to iOS and androidNative, but no Compose
  klib has been read yet — `docs/design/pyi-generation-design.md` §2.3 still lists the klib producer as
  "not implemented".
