# Generating `.pyi` — the reference implementation, the input, and what a stub can actually say

`pythonx` adapts Kotlin generically rather than wrapping function by function (`docs/design/ecosystem.md`
§5b): a module `__getattr__` finds the corresponding binding, adapts it once and caches it. Nothing
an editor can see is ever enumerated. `.pyi` is what pays that back — the stubs carry the fully
enumerated Pythonic surface the adapter will produce, so an IDE sees everything while the runtime
enumerates nothing. Generation belongs to the Gradle plugin, the way PyREPL did it; that is settled
and this file does not revisit it.

What this file does is answer the three questions that were open under that decision: **what the
generator reads**, **what it can honestly write**, and **where the result goes**.

Everything under "Observed" was read from working copies or run as a command on 2026-08-14, with the
path or the command given. Everything under "Judged" is a decision, and the alternatives it was
chosen over are named. Where a claim could not be checked it says so rather than being smoothed over.

---

## 0. What was read, what was run, and what was not

### Read

| what | where | what it settled |
|---|---|---|
| PyREPL's generator | `/Volumes/macMini/PyREPL/app/build.gradle.kts` (627 lines, HEAD `899c6bf`, 2024-09-01, remote `github.com/thisisthepy/PyREPL`) | §1 in full |
| toolchain's mutated copy | `/Volumes/macMini/thisisthepy/toolchain/toolchain/src/main/kotlin/org/thisisthepy/python/multiplatform/toolchain/dependency/lang/kotlin/meta/createMetaClass.kt` (248), `meta/MetaPackageBuildTask.kt` (64), `kotlin/decompileKotlinMeta.kt` (145) | §1.3 — and it corrects `docs/design/ecosystem.md` |
| the artefact walker | `python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/artifact/{ArtifactScanner,KotlinMetadata,ArtifactRendering,JvmDescriptors,PythonArtifactBindingsTask}.kt` | §2 |
| the KSP producer | `python-multiplatform-ksp/src/main/kotlin/python/multiplatform/ksp/{FragmentScanner,Model,TypeShape,KsAdapters}.kt` | §2 |
| the runtime proxy renderer | `python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/PythonProxySource.kt` | §2.2, §6 |
| plugin wiring | `python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/PythonBindingsPlugin.kt` lines 145–344 | §6 |
| the Python package being stubbed | `/Volumes/macMini/thisisthepy/pythonx-compose/pythonx/**` | §5 |
| Compose desktop jars | `/Volumes/macMini/thisisthepy/pythonx-compose/pythonx/compose/lite/release/app/*.jar` | §5 |

### Run

mypy **2.3.0** in a throwaway venv at `/Volumes/macMini/tmp/pyicheck` (nothing was installed into
the repository, and no repository file other than this one was touched). Eight checks, reported in
§4.3, §4.4 and §6.3. CPython 3.13 for the two runtime attribute-lookup checks in §4.3.

### Not read, and therefore not claimed

- **`github.com/thisisthepy/PyREPL`'s files.** The repository page was fetched and exists — public,
  described as *"UI-Enabled Python REPL for Android/iOS/Desktop"*, 7 stars, 9 forks — but the fetch
  returned no file contents. **Everything said about PyREPL here comes from the local clone at
  `/Volumes/macMini/PyREPL`**, whose `origin` is that URL. Whether the clone is current with the
  remote was not checked.
- **`toolchain-legacy` has no stub generator.** `grep -rIl pyi` over the tree returns one file,
  `toolchain/android/toolchaincl.py`, and the hit is inside the word `copying`. It is a pure-Python
  kivy fork and contributes nothing here.
- **PyCharm / IntelliJ.** Every claim in §4 about what a checker infers was measured **with mypy
  only**. PyCharm's Python plugin has its own inference engine and was not tested; neither was
  pyright (no `node` on this machine). This matters more than usual, because the IDE a user of this
  stack is in is most likely IntelliJ or Android Studio. **Marked as needing verification
  throughout, and not asserted.**
- **klib.** Everything here is the JVM path. `docs/design/ecosystem.md` §5b records that
  `LibraryAbiReader` returns Kotlin qualified names, extension-receiver flags and unerased types
  from a klib, which is the same information §2 says a stub needs — but no klib was read for this
  document.

---

## 1. Observed — PyREPL's generator

### 1.1 The pipeline

`app/build.gradle.kts` registers one task, `createKotlinMetaPackageForPython`, and hooks it onto the
IDE's own import step:

    project.tasks.getByName("prepareKotlinIdeaImport").dependsOn(createKotlinMetaPackageForPython)

`resolveDependenciesForPython()` (line 211) walks `kotlinExtension.sourceSets`, and for each source
set makes a **resolvable copy** of its `implementation` and `api` configurations:

    configurations.create(configuration.name + "Resolved") { extendsFrom(configuration); isCanBeResolved = true }

then takes `resolvedConfiguration.lenientConfiguration.artifacts`. Unresolved dependencies that name
a sibling project are recursed into with `isSubModule = true`, which switches the copy to `api` only
— the same visibility rule Gradle itself applies. Everything else is collected as genuinely
unresolved and printed.

The output directory is derived per source set: from `sourceSet.kotlin.srcDirs`, take the one under
`<name>/kotlin`, and emit into its sibling `generated/meta` — i.e. `src/<sourceSet>/generated/meta/`,
**inside the source tree, not the build directory**. That directory is then registered as a Python
source root through the only mechanism the project had for one:

    chaquopy { sourceSets { getByName("main") { srcDirs(
        "src/androidMain/python", "src/androidMain/generated/meta",
        "src/commonMain/python", "src/commonMain/generated/meta") } } }

A `build/kotlin-python/meta_json.lock` records the resolved artefact set (group, name, version,
file) as JSON so a changed dependency graph can be detected. **It is computed and then ignored** —
`outputs.upToDateWhen { false }` and the `if (!isChanged) return@doLast` guard is commented out, so
the task runs every time.

`processJarForMetaPackage` reads every `.class` in a jar with ASM into a `ClassNode` map, then emits
for each class whose binary name has no `$` and which is not `private`/`protected`.

### 1.2 What it writes

One `__init__.pyi` **per Java package**, at `createInitPythonFile` (line 368) — the file is named
after the package directory, and every class in that package is *appended* to it. Bodies are `...`;
there is no runtime in the file at all.

    class Version:

        id: String

        def main(self, *args, **kwargs): ...

Filters, from `generateMetaPythonClass` (line 375): drop `private`/`protected`; drop fields starting
`$` or `Companion`; drop methods starting `<`; **drop any member whose name contains `-`** — the
value-class mangling suffix, discarded rather than decoded; skip numeric (anonymous) nested classes;
emit `...` for a class that ends up empty.

Types come from `toReadableType` (line 486), which maps JVM descriptors to **JNI-ish names**: `I` →
`jint`, `J` → `jlong`, `Z` → `jboolean`, `V` → `jvoid`, `[X` → `X[]`. Only fields get a type
annotation; every method is `def name(self, *args, **kwargs): ...`. `class $className:` is emitted
unconditionally, so a Kotlin file facade appears as a Python class named `StringsKt`.

Collisions between `commonMain` and a platform source set are handled by `checkForOverlappingClass`
(line 525): find `__init__.pyi` paths present in both trees, rename **both** to
`_<Class>.pyi` and `_<Class>_<target>.pyi`, and write a new `__init__.pyi` that re-exports both with
`from ._X import *`. `findCommonDir` and `reCreateFile` split on the literal `"\\__init__.pyi"`, so
**this path is Windows-only** and silently does nothing on macOS or Linux — `docs/design/ecosystem.md`
already flagged the separators; what is added here is that the failure is silent rather than an
error.

### 1.3 The two lineages — and a correction to `docs/design/ecosystem.md`

`docs/design/ecosystem.md` §2 says `dba17dd` "extracted PyREPL's meta-generation" into `createMetaClass.kt`
and that `toolchain`'s copy is "the same code mutated into a *runtime* generator". The extraction is
right; the implied direction is not.

`git show f0dd368:app/build.gradle.kts` — the commit in **toolchain's own history** that first added
the generator, one day before the extraction — already emits `.py`, not `.pyi`:

    fun createMetaPythonFile(node: ClassNode, outputDir: File): File {
        ...
        return File(packageDir, "$className.py")     // f0dd368, and today's createMetaClass.kt
    }

So the runtime `.py` variant is not a mutation of the stub variant. **The lineage that became
`toolchain` never carried the `.pyi` generator at all**; PyREPL's `.pyi` version is a separate
development on the PyREPL side (per-package `__init__.pyi` instead of per-class `.py`, `...` bodies
instead of `jclass` delegation, the overlap/re-export pass, and a `-` filter applied to fields as
well as methods). Anyone porting "PyREPL's generator" must take it from
`/Volumes/macMini/PyREPL/app/build.gradle.kts` and not from `toolchain`, and the diff between them
is larger than "delete the bodies".

One thing in `toolchain` is worth keeping regardless of lineage: `decompileKotlinMeta.kt` already
reaches for **`kotlinx-metadata-jvm`** and dispatches on `KotlinClassMetadata.Class` /
`FileFacade` / `MultiFileClassPart` / `MultiFileClassFacade`. It is a standalone `main()`, wired into
no task, and its output is `def name(self): pass` with no types — but the instinct is the same one
this repository's `KotlinMetadata.kt` acted on, two years later.

### 1.4 What survives the port

| PyREPL | keep? | why |
|---|---|---|
| resolvable copy of `implementation`/`api` per source set | **superseded** | `PythonArtifactBindingsTask` already takes `configuration.incoming.artifacts`, which carries coordinates as well as files |
| per-package `__init__.pyi` | **keep** | it is what makes `from androidx.compose.material3 import Text` resolve, and §6.3 confirms a directory of `.pyi` with no `.py` and no `__init__.py` resolves |
| `...` bodies | **keep** | a stub never has to compile or run |
| `def f(self, *args, **kwargs)` | **reject** | it is the whole of what makes these stubs useless for anything but name completion. Parameters are the point (§3) |
| JNI-ish type map (`jint`, `jlong`) | **reject** | not Python types; a checker infers nothing from them |
| drop names containing `-` | **reject** | `docs/design/kotlin-extensions-in-python.md` §2.2: it discards 52 of the 177 chainable `Modifier` extensions, and metadata supplies the real name |
| `class StringsKt:` for a file facade | **reject** | there is no `StringsKt` in the Kotlin namespace; a top-level function must be a module-level `def` |
| commonMain/platform overlap → rename + re-export | **keep the idea, rewrite** | the shape is right and the implementation is Windows-only and silent (§1.2) |
| `prepareKotlinIdeaImport` dependency | **keep** | §6.2 |
| `meta_json.lock` | **reject** | Gradle's own up-to-date checking does this, and PyREPL's was disabled anyway |

---

## 2. The input — judged

### 2.1 Observed: what the two producers keep today

Both producers exist and both already read the right *sources*. Neither keeps enough of what it read.

`python.multiplatform.ksp.CallableEntryModel` (`Model.kt` lines 9–24) and
`python.multiplatform.gradle.artifact.ArtifactCallable` (`ArtifactRendering.kt` lines 26–37) are
field-for-field near-identical:

| field | `CallableEntryModel` | `ArtifactCallable` |
|---|---|---|
| `name` | Kotlin FQN | Kotlin FQN |
| `arity` | yes | yes |
| `paramTags` | `List<Tag>` | `List<String>` |
| `returnTag` | `Tag` | `String` |
| `kind` | `"FUNCTION"`, `"METHOD"`, … | — (always FUNCTION) |
| `lambdaBody` | generated Kotlin source text | generated Kotlin source text |
| `isSuspend` | yes | — (always false) |
| `imports` | — | extension-call aliases |

`Tag` (`TypeShape.kt` line 9) is `INT | FLOAT | BOOLEAN | STRING | BYTES | UNIT | OBJECT` — seven
values, and `OBJECT` means "a handle, contents unknown". The runtime's `ExposedCallable`, which
`PythonProxySource.render` consumes, carries the same and nothing more.

**What is therefore absent from every existing representation:**

- parameter **names** — `FragmentScanner` does `function.parameters.map { it.type.toShape() }` and
  drops `it.name`; `ArtifactScanner` does the same to `KmValueParameter`
- **defaults** — `KmValueParameter.declaresDefaultValue` is read by nobody
- **nullability** — `resolveKotlinType` returns `null` for a nullable type outright
  (`KotlinMetadata.kt` line 206), so the fact never leaves the scanner
- the **declared Kotlin type** — `Dp` becomes `Tag.FLOAT`, `List<String>` becomes `Tag.OBJECT`
- whether a parameter is an **extension receiver** — known inside `buildCallableFromFunction`, spent
  on choosing `receiver.alias(...)`, then discarded
- **overload grouping** — both producers *delete* the information: `ArtifactScanner.scanClassNode`
  line 230 keeps only names with exactly one candidate; `FragmentScanner` keeps the first
- value-class identity — `ValueClassInfo` exists (`KotlinMetadata.kt` line 168) and is consumed
  inside a closure to build a wrap/unwrap expression, never surfaced

### 2.2 Judged: the input is a third representation, upstream of both renderers

**Neither producer's output can drive a `.pyi` generator, and neither should be widened to try.**

The reasoning is the one `PythonProxySource`'s KDoc already gives for a different split: an
`ExposedCallable` is *"exactly the inputs"* for the runtime proxy, and that is the point — it was cut
down to exactly what a trampoline needs. A `.pyi` needs a strict superset of what a *call site* needs,
because a stub describes a signature and a call site only has to produce one. Growing
`CallableEntryModel` to carry parameter names and defaults would put fields into the model that
`renderFragmentSource` must ignore, on a path where every field today is load-bearing.

So: **one declaration model, produced by each scanner, consumed by two renderers.**

    KSP (KSFunctionDeclaration)  ─┐
    ASM + kotlin-metadata-jvm    ─┼─►  DeclarationModel  ─┬─►  binding renderer  (exists)
    LibraryAbiReader (klib)      ─┘                       └─►  .pyi renderer     (this document)

`DeclarationModel` carries, per declaration: Kotlin qualified name; owner; whether it is a top-level
function, a member, a constructor, a property; the extension receiver's Kotlin type or `null`; an
ordered parameter list of `(name, KotlinTypeModel, declaresDefault)`; the return `KotlinTypeModel`;
`isSuspend`; and the annotations that matter (`@Composable`, `@Deprecated`). `KotlinTypeModel`
carries a qualified name, nullability, type arguments, and — when the classifier is a value class —
its underlying `KotlinTypeModel` plus the two visibility booleans `ValueClassInfo` already computes.

Three properties this buys, each of which is a defect in the current shape:

1. **The two renderers cannot drift.** `docs/design/kotlin-extensions-in-python.md` §4.5 requires this and
   the current code cannot deliver it: a `.pyi` written from `ArtifactCallable` would state
   `padding(a0: float)` while the binding calls `Modifier.padding(Dp(...))`.
2. **Overloads survive to where they are needed.** The binder drops an ambiguous name because it
   cannot dispatch (`docs/roadmap/ROADMAP.md` §16c: `Assert.assertEquals` has eight). A stub *can* state all of
   them (§3.6, measured). Those are different decisions and today they are the same line of code.
3. **What is declined stays visible.** A model entry can be marked "not bound, reason X" and still
   be stubbed — or deliberately not stubbed, which is the honest choice when the runtime cannot call
   it. Today a declined declaration returns `null` and vanishes.

**Rejected alternatives:**

| alternative | why not |
|---|---|
| generate `.pyi` from the runtime `UpcallTable` (mirroring `PythonProxySource`) | the table holds `ExposedCallable`, i.e. tags and arity. Every parameter would be `a0, a1, a2` with no type. It is also the wrong time: `PythonProxySource` renders at run time *because* the installed set is only known then, and a stub has to exist before anything runs |
| widen `CallableEntryModel` / `ArtifactCallable` in place | puts fields on the hot path that its renderer must ignore, and does not fix the KSP/ASM asymmetry (`kind` and `isSuspend` on one, `imports` on the other) |
| a second scan, independent of the binder's | two readers of the same jars that can disagree — precisely the failure `PythonProxySource` §"Where this is generated" refuses ("a second generator that can disagree with the first") |
| emit `.pyi` from KSP as well as from the plugin | KSP does not see third-party artefacts at all, which is the entire reason the artefact walker exists (`docs/design/ecosystem.md` §5b). The plugin sees both |

### 2.3 What each producer has to supply

| producer | has it today | needs |
|---|---|---|
| KSP | `KSFunctionDeclaration` gives names, defaults (`KSValueParameter.hasDefault`), nullability, type arguments, annotations | nothing new read — only *kept*. `KsAdapters.renderWithArguments` already walks type arguments |
| artefact walker | `KmFunction` gives `valueParameters[].name`, `.declaresDefaultValue`, `KmType.isNullable`, `.arguments`, `receiverParameterType`; `ValueClassInfo` gives the rest | nothing new read — only *kept*. `functionsOf` currently throws away `KmValueParameter` and keeps `.type` |
| Java classes in a walked jar (no `@Metadata`) | JVM descriptors only | **no Kotlin types, no parameter names, no nullability.** §3.2 |
| klib | not implemented | `LibraryAbiReader`, per `docs/design/ecosystem.md` §5b — unverified here |

That the first two need **no new reading** is the strongest argument for this shape: the information
is being fetched and then dropped, one function before it would be used.

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
| `T?` | `T \| None` | `KmType.isNullable`, `KSType.isMarkedNullable` | **not bindable today** — `resolveKotlinType` declines nullable outright. §3.3 |
| `List<T>` `MutableList<T>` | `list[T]` | Km/KS type args | not bindable today (the type gate) |
| `Set<T>` | `set[T]` | | |
| `Map<K, V>` | `dict[K, V]` | | |
| `Collection<T>` `Iterable<T>` | `Iterable[T]` | | read-only shape; `Sequence` would over-promise indexing |
| `Array<T>` | `list[T]` | | judged: the marshaller's Python view is a list |
| `IntArray` `LongArray` `…` | `list[int]` etc. | desc/Km | `ByteArray` is the exception above |
| `() -> Unit` | `Callable[[], None]` | Km classifier `kotlin/Function0` | §3.5 |
| `(A) -> B` | `Callable[[A], B]` | `kotlin/FunctionN` type args | |
| `suspend (…) -> T` | — | `KmFunction.isSuspend`, `KSType.isSuspendFunctionType` | declined by both producers; must not be stubbed |
| value class on the allowlist | `Dp \| float` | Km + `list` | §3.4 |
| value class off the allowlist | `TextUnit` | Km | §3.4 |
| any other class/interface | its own stub name | Km/KS | requires that class to have a stub in the same namespace, which for a `TypeTag.OBJECT` handle is a promise the runtime does not yet keep — §7 |
| a generic type *parameter* (`fun <T> f(x: T)`) | — | | `BindingPolicy` rejects these before the binder sees them; stubbing what cannot be called would be a lie. §7 |

### 3.2 Java declarations have no types to map

A class in a walked jar with no `@Metadata` goes down `ArtifactScanner.javaStaticCandidates`, which
has only the JVM descriptor. That path can produce `int`, `str`, `bytes`, `bool`, `float`, `None` and
nothing else — no nullability, and **no parameter names** (`MethodNode.parameters` is present only
when the jar was compiled with `-parameters`, which is not the default and cannot be assumed).

Judged: for a Java-sourced declaration, emit positional-only parameters —

    def assertEquals(__a0: int, __a1: int, /) -> None: ...

— rather than inventing names. A wrong keyword name is worse than no keyword name, because it
type-checks at the call site and fails at run time. Reference return types from Java get `| None`,
because Java can return null and nothing in the bytecode says otherwise.

This is a real asymmetry between a Kotlin jar and a Java jar and it should be visible in the output,
not hidden.

### 3.3 Nullability

`String?` → `str | None` is mechanical once the fact is carried (§2.2). The thing to record is that
**nothing is nullable today**: `resolveKotlinType` returns `null` for `type.isNullable`, so a
declaration with one nullable parameter is not bound at all. The stub generator therefore has
nothing to be nullable about until the binder's own gate moves, and the mapping row above is
provision, not description.

`| None` is preferred over `Optional[...]`: it is the 3.10+ spelling, and a `.pyi` may use it
regardless of the runtime's target version because a stub is never executed. `from __future__ import
annotations` is not needed in a `.pyi` for the same reason.

### 3.4 Value classes — how the allowlist shows up

`docs/design/kotlin-extensions-in-python.md` §4.4 decided this and measured why a machine rule fails: the
rule "coerce iff the value class has a public constructor taking exactly its underlying type"
selects 36 of 111 and **admits `Color`**, whose public `ULong` constructor stores while the factory
a Kotlin author actually writes (`Color(Int)`) shifts by 32. Whether a wrapper packs is a semantic
fact with no bytecode witness.

In a stub the distinction is one union member:

| Kotlin parameter | `.pyi` | why |
|---|---|---|
| `Dp` (on the allowlist) | `Dp \| float` | public constructor, wraps a plain `Float`, and it *is* the identity on that float. 62% of value-class parameter occurrences on `Modifier` |
| `TextUnit` (rejected) | `TextUnit` | packed `Long`; raw `16` decodes as `Unspecified` — silently no value at all |
| `Color` (rejected despite passing the constructor test) | `Color` | `Color(int)` is `ULong(value) shl 32`; raw `0xFFFF0000` is transparent black through the constructor and red through the factory |
| every `packedValue` class (`Offset`, `Size`, `DpSize`, `IntSize`, `IntOffset`, `DpOffset`, `TransformOrigin`, `CornerRadius`, `Constraints`) | the proxy alone | a user-meaningful number is not the stored number, and none of these failures raises |

**Measured, §4.4's asymmetry is expressible and enforced.** In the mypy run of §4.4,
`Text("hi", font_size=16)` produced

    error: Argument "font_size" to "Text" has incompatible type "int"; expected "TextUnit"

while `Modifier.padding(16)` in the same file checked clean. So the stub is not merely documentation
of the asymmetry — a checker acts on it, and the user finds out at edit time rather than seeing a
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
  restriction — a composable lambda may only be invoked in a composable context — has no Python
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
3 genuinely overlap — `p(8)` matches `all` and also matches `horizontal` positionally — and the
checker silently takes the **first** match.

**Judged: overload order is part of the generated output and must be deterministic and
arity-ascending.** In the run above, arity-ascending put `all: Dp` before `horizontal, vertical`, and
`p(8)` selected `all` — the right answer, but only because of the order. Two further consequences:

- The stub's order must be **the same order the Python-side dispatcher resolves in**. If the runtime
  dispatcher (`docs/design/kotlin-extensions-in-python.md` §6, unsolved) picks differently, the stub lies
  about which Kotlin function runs. The two orders must come from one place in the generator.
- **Defaults are emitted as `= ...`.** 254 of 435 `Modifier` extension parameters declare one and
  metadata carries `declaresDefaultValue` but not the *value*. `= ...` is the correct stub spelling
  for "has a default I cannot name" and is what makes the keyword-only overloads above work.

Kotlin parameter names go through the same snake_case conversion as everything else
(`paddingValues` → `padding_values`), which `pythonx-compose` already does by hand: `text.py` maps
`font_size` → `fontSize`, `letter_spacing` → `letterSpacing`, and 14 more.

---

## 4. Extension functions — and the metaclass proposal does not survive contact

This is the section the Compose surface turns on: 604 public top-level extension functions over 136
receivers, and 177 chainable ones on `Modifier` alone.

### 4.1 Placement (unchanged from `docs/design/kotlin-extensions-in-python.md` §4.1)

| receiver | count | placement |
|---|---|---|
| ordinary class or interface | 514 | method on the receiver's stub class |
| Compose value class | 41 | method on the value class's stub |
| Kotlin collection | 29 | module-level function |
| Kotlin primitive or `String` | 20 | module-level function — Python cannot attach a method to `int` |

Nothing measured here disturbs that. Chaining is free because each of the 177 returns `Modifier`, and
§4.4's run confirms `Modifier.size(4).padding(2)` type-checks as `Modifier`.

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
mypy — it still resolves the class's own method and produces the same twelve errors. **mypy does not
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

**Measured** — the same twenty-line user file that produced ten errors against §4.2 produces exactly
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
| `Text("hi", font_size=16)` | **error** — `int` is not `TextUnit` (intended, §3.4) |
| `Modifier.padding("nope")` | **error**, with all overload variants listed (intended) |

`ClassVar` is not load-bearing for inference — a plain `padding: _Modifier_padding` gives byte-identical
results — but it is the honest annotation, and it stops a checker accepting `m.padding = something`.

The point that makes this legitimate rather than a trick: **a `.pyi` completely replaces the `.py`
for a checker, so the stub's mechanism and the runtime's mechanism do not have to agree — only the
types do.** The runtime uses the hybrid descriptor of §4.3, which binds `self` correctly for both
spellings; the stub says "a callable attribute", which is what a checker can act on. The one
inaccuracy is that a checker believes `Modifier.padding` and `m.padding` are the same object, and
nothing observable depends on that.

**Rejected:**

| alternative | why not |
|---|---|
| the metaclass of §4.2 as written | measured not to work, in both mypy and CPython (§4.3) |
| metaclass whose members are `@property -> Protocol` | works at run time, does not work in mypy — the class's own member still wins. Would give a *correct runtime* with a *wrong IDE*, which is the worst of the two |
| bind the module-level name `Modifier` to the companion instance | already rejected in §4.2 of the other document and still right: it destroys `Modifier` as an annotation, and the stubs exist so that annotation works |
| drop the `Modifier.padding(...)` spelling, require `Modifier().padding(...)` | changes the API away from Kotlin's for a reason that is an implementation detail of Python attribute lookup |
| emit both the metaclass **and** the Protocol attributes | the class attribute shadows the metaclass in both engines, so the metaclass is dead text that a reader will believe |

**Cost, stated:** one Protocol class per `(receiver, name)` pair. For `Modifier` that is 130 extra
class definitions in `pythonx/compose/ui/__init__.pyi`; across the 507 distinct `(receiver, name)`
pairs in corpus A, ~507 if every receiver is stubbed. They are generated, never read by a human, and
`.pyi` files are not executed — but a 500-class stub file is a real indexing cost for an IDE and it
has not been measured.

**Not verified: PyCharm.** Callback protocols are standard typing and PyCharm supports `Protocol`,
but whether its inference resolves `Modifier.padding(16)` through a `ClassVar[Protocol]` was not
tested. **This is the single most load-bearing unverified claim in this document** — §4.4 is chosen
over §4.2 on mypy evidence alone, and if PyCharm follows the runtime MRO rule the way mypy does then
§4.4 works there too, while if it has its own metaclass handling the conclusion could differ.
Verify before implementing.

### 4.5 Member extensions are still out

74 public member extensions on `Modifier` (`RowScope.weight`, `ColumnScope.align`,
`BoxScope.matchParentSize`) need a dispatch receiver Kotlin supplies implicitly from the enclosing
lambda. `docs/design/kotlin-extensions-in-python.md` §6 records that no shape has been chosen. Nothing here
changes that, and **they must not be stubbed as if they were plain methods on `Modifier`** — a stub
that says `Modifier.weight(1.0)` checks is a stub that promises a call the runtime cannot make.

---

## 5. Package mapping

### 5.1 Observed: the shape of `pythonx-compose`

`/Volumes/macMini/thisisthepy/pythonx-compose/pythonx/` contains **only** `compose/`, and there is
**no `pythonx/__init__.py`** — `pythonx` is an implicit namespace package, so several distributions
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
| `pythonx.compose.layout` | `androidx.compose.foundation.layout` | **no — `foundation.` is dropped** |
| `pythonx.compose.wrapper`, `.native`, `.lite`, `.test` | none | no counterpart |

The `layout` entry is not inferred. `pythonx/compose/layout/arrangement.py` line 7 reads
`jclass("androidx.compose.foundation.layout.Arrangement")`, and `pythonx/compose/layout/__init__.py`
re-exports `Arrangement` from it.

### 5.3 Judged: a rule plus a manifest the Python package owns

**`androidx.` → `pythonx.` is the default rule; deviations come from a manifest, and the manifest
belongs to the Python package, not to the plugin.**

The reason is ownership, not convenience. Which Kotlin package a `pythonx` module wraps is
`pythonx-compose`'s design decision — the plugin has no basis on which to invent
`pythonx.compose.layout` for `androidx.compose.foundation.layout`, and hard-coding Compose's
particular renames into a general-purpose Gradle plugin would make every other library's mapping
unreachable.

So: a data file inside the Python distribution — e.g. `pythonx/compose/pythonx-map.toml` — declaring

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

## 6. Where the files go, and how an IDE picks them up

### 6.1 Observed: what makes stubs take effect

Five mypy runs in `/Volumes/macMini/tmp/pyicheck`, each reported above or below:

1. **A `.pyi` beside a `.py` wins.** The fixture's `pythonx/compose/ui/__init__.py` contains nothing
   but `def __getattr__(name): raise AttributeError(name)` — the shape the real adapter will have —
   and every name in §4.4's table resolved from `__init__.pyi`. This is the deployment that matters:
   the dynamic adapter ships, the stub ships beside it, and the checker never sees the dynamism.
2. **`py.typed` is mandatory once the package is installed.** With `pythonx/` copied into the venv's
   `site-packages` and `pythonx/compose/py.typed` present, everything resolved. Deleting **only**
   `py.typed`:

       error: Skipping analyzing "pythonx.compose.ui": module is installed, but missing library
              stubs or py.typed marker  [import-untyped]
       note: Revealed type is "Any"        (every line)

   The marker goes in the top-level *regular* package of the distribution — here `pythonx/compose/`,
   because `pythonx` is a namespace package (§5.1). That placement was the one tested and it works.
3. **A stub-only tree with no `.py` and no `__init__.py` resolves.** A directory containing only
   `androidx/compose/material3/__init__.pyi` on `MYPYPATH` gave
   `Revealed type is "def (text: str, color: int =, fontSize: int =)"` and rejected `Text(3)`. So
   the Kotlin-FQN stubs of §5.3 — which describe modules that exist only in `sys.modules` — need no
   accompanying files.
4. Namespace packages need no `__init__.py` anywhere above the stub; mypy's `--namespace-packages`
   is on by default.

### 6.2 Judged: build directory, plus a registered root

PyREPL wrote into the **source tree** (`src/<sourceSet>/generated/meta/`) and got away with it
because chaquopy defines a Python source-directory concept a Gradle plugin can add to. This
repository has no equivalent: the current plugin puts generated *Kotlin* into
`build/generated/pythonArtifactBindings/<sourceSet>` and registers it via
`sourceSet.kotlin.srcDir(task)` (`PythonBindingsPlugin.addKotlinSourceDirectory`), which works only
because the Kotlin compiler is the consumer. **Nothing compiles a `.pyi`.**

So:

- **Emit to `build/generated/pythonStubs/<sourceSet>/`.** Generated output does not belong in the
  source tree; `.gitignore` churn and stale files from a removed dependency are exactly what
  `PythonArtifactBindingsTask` already deletes the whole output directory to avoid.
- **Keep PyREPL's `prepareKotlinIdeaImport` dependency.** It is the one hook that makes the stubs
  exist before the IDE indexes, and it costs one line.
- **Register the directory wherever the environment has a mechanism**: chaquopy `sourceSets.srcDirs`
  when chaquopy is applied; a `.pth` file in the venv for a desktop run; the interpreter path list
  for PyCharm. This is per-environment and none of it was verified.
- **For a published `pythonx` wheel the stubs belong inside the wheel** — `.pyi` beside `.py` plus
  `py.typed`, both verified in §6.1 — built by `pypackpack`'s bundle stage, not regenerated per
  consumer build. The plugin's per-build generation covers the consumer's own Kotlin and the
  third-party jars *its* build resolves; a shipped `pythonx-compose` covers Compose once.

Two audiences, two destinations, one generator.

### 6.3 The commonMain/platform overlap

PyREPL's answer (§1.2) is the right shape: when the same qualified name is contributed by
`commonMain` and by a platform source set, rename both and write an `__init__.pyi` that re-exports
each. Its implementation splits on `"\\__init__.pyi"` and so does nothing outside Windows. The port
must use `File.separator` — or better, operate on `Path` objects and never on path strings, which is
what made the bug invisible.

`docs/design/ecosystem.md` §5b's `JClass`/`KClass`/`ObjcClass` rule bears on this: a target that has no JVM
has no `JClass` and says so. The stubs must reproduce that per source set rather than emitting a
union that promises every platform's surface everywhere.

---

## 7. Open

- **PyCharm.** §4.4 rests entirely on mypy. Verify `ClassVar[Protocol]` resolution, `@overload`
  ordering, and `.pyi`-beside-`.py` precedence in PyCharm before implementing. If PyCharm does model
  metaclass data descriptors, §4.2's shape becomes viable again there and the two engines would want
  different stubs — which would be a genuinely new problem.
- **The 500-class stub cost.** ~507 callback Protocols for corpus A's `(receiver, name)` pairs, and
  130 for `Modifier` alone in one file. Not measured for IDE indexing time or for mypy's own runtime.
- **Whether any real Compose overload set collapses.** §3.6 shows four `padding` overloads coexisting,
  but two Kotlin overloads can map to identical Python signatures once `Dp | float` widening is
  applied. The generator must detect that and drop or qualify, and how often it happens across the
  33 multi-overload `Modifier` names is unknown — it needs the scan to be run.
- **Stub order vs dispatcher order.** §3.6: the `@overload` order and the Python-side dispatcher's
  resolution order must come from one place. The dispatcher does not exist
  (`docs/design/kotlin-extensions-in-python.md` §6).
- **What to stub for a `TypeTag.OBJECT` return.** `PythonProxySource`'s KDoc records that such a
  value crosses as a bare handle integer, not as an instance of the class rendered for it. A stub
  saying `-> Modifier` would then be wrong for exactly the chaining case §4.1 depends on. Needs the
  handle-to-proxy wrapping that section lists as not yet done.
- **Generic declarations.** `BindingPolicy` rejects them, so they are not stubbed; if the binder ever
  accepts them, `TypeVar` is the mapping and nothing here covers it.
- **klib.** §0. The `pythonx.*` surface on iOS and androidNative has no producer yet.
- **The manifest of §5.3** does not exist and has not been agreed with `pythonx-compose`.
- **Member extensions** (§4.5) — 74 on `Modifier` — remain unstubbable until the scope-passing shape
  is chosen.
