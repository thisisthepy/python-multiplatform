# Generating `.pyi`, the reference implementation, the input, and what a stub can actually say

> **Revised 2026-10-03.** This file now describes only the stub product this repository ships: `.pyi`
> under the **Kotlin** module paths, with Kotlin names (SPEC B-7). The design of a second, Pythonic
> product, `pythonx.*` stubs, snake_case, `Dp | float`, `@overload` sets, the `Modifier`
> callback-Protocol shape and the `pythonx-map.toml` manifest, was removed from the plugin in
> `238119b7` and moved to
> [`docs/archive/pyi-generation-pythonic-stubs.md`](../archive/pyi-generation-pythonic-stubs.md);
> that product belongs to pythonx-compose. Section numbers are kept so that the code's KDoc
> references still resolve; sections that moved say so where they used to be.
>
> **Revised again for issue #31 (typed stubs).** The stub no longer annotates every object as `int`:
> it names the declared Kotlin type with a stub class per bound type, puts each extension function on
> its receiver's class as well, and states function types, nullability, value classes and overload
> sets (§3.1, §3.3–§3.6, §4.1). Everything is still under Kotlin names; the "`Dp | float`, `@overload`
> sets, `Modifier` callback-Protocol shape" that the paragraph above says left the plugin are back
> **as Kotlin-name typing**, not as a Pythonic product -- no `pythonx`.
>
> **Revised again for issue #131.** snake_case is back -- not as a `pythonx` product, but as the
> Pythonic aliases the runtime serves on the Kotlin-named modules themselves (SPEC U-12): parameters
> under their Pythonic keyword, and `alias = kotlinName` beside each module-level name (§3.6).

The binder serves Kotlin-named modules (`androidx.compose.foundation.layout`, `junit.runner.Version`,
a consumer's own package) that exist only as `sys.modules` entries made at run time
(`PythonProxySource`, and lazily `python_multiplatform.binding`). Nothing an editor can see is ever
enumerated. `.pyi` is what pays that back: the stubs carry the fully enumerated surface **under the
Kotlin names**, so an IDE sees every bound declaration while the runtime enumerates nothing. Making
that surface Pythonic is pythonx-compose's job, which reads `inspect.signature` and
`python_multiplatform.describe` at run time (`KotlinSurface.kt`) and ships its own stubs. Generation
belongs to the Gradle plugin, the way PyREPL did it (`AGENTS.md` §12.5); that is settled and this file
does not revisit it.

What this file answers: **what the generator reads**, **what it writes**, and **where the result
goes**. The generator is `PythonStubsTask` + `renderKotlinFqnStubs` (`PyiRendering.kt`, in
`python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/stubs/`); its tests are
`PyiRenderingTest`, `KotlinNamesOnlyStubTest` and `ksp-fixtures/artifact`'s `WalkedArtifactStubTest`.

Everything under "Observed" was read from working copies or run as a command on 2026-08-14, with the
path or the command given, unless dated otherwise. Everything under "Judged" is a decision, and the
alternatives it was chosen over are named. "Current" statements were checked against the code on
2026-10-03 by reading it; no Gradle build was run for this revision.

---

## 0. What was read, what was run, and what was not

### Read

| what | where | what it settled |
|---|---|---|
| PyREPL's generator | `/Volumes/macMini/PyREPL/app/build.gradle.kts` (627 lines, HEAD `899c6bf`, 2024-09-01, remote `github.com/thisisthepy/PyREPL`) | §1 in full |
| toolchain's mutated copy | `/Volumes/macMini/thisisthepy/toolchain/toolchain/src/main/kotlin/org/thisisthepy/python/multiplatform/toolchain/dependency/lang/kotlin/meta/createMetaClass.kt` (248), `meta/MetaPackageBuildTask.kt` (64), `kotlin/decompileKotlinMeta.kt` (145) | §1.3, and it corrects `docs/design/ecosystem.md` |
| the artefact walker | `python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/artifact/{ArtifactScanner,KotlinMetadata,ArtifactRendering,JvmDescriptors,PythonArtifactBindingsTask}.kt` | §2 |
| the KSP producer | `python-multiplatform-ksp/src/main/kotlin/python/multiplatform/ksp/{FragmentScanner,Model,TypeShape,KsAdapters}.kt` | §2 |
| the runtime proxy renderer | `python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/PythonProxySource.kt` | §2.2, §6 |
| plugin wiring | `python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/PythonBindingsPlugin.kt` lines 145–344 | §6 |
| the Python package being stubbed | `/Volumes/macMini/thisisthepy/pythonx-compose/pythonx/**` | §5 (now archived) |
| Compose desktop jars | `/Volumes/macMini/thisisthepy/pythonx-compose/pythonx/compose/lite/release/app/*.jar` | §5 (now archived) |

### Run

mypy **2.3.0** in a throwaway venv at `/Volumes/macMini/tmp/pyicheck` (nothing was installed into
the repository, and no repository file other than this one was touched). Eight checks, reported in
§4.3, §4.4 and §6.1, §4.3, §4.4 and most of §6.1 are now in the archive file named at the top.
CPython 3.13 for the two runtime attribute-lookup checks in §4.3.

### Not read, and therefore not claimed

- **`github.com/thisisthepy/PyREPL`'s files.** The repository page was fetched and exists, public,
  described as *"UI-Enabled Python REPL for Android/iOS/Desktop"*, 7 stars, 9 forks, but the fetch
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
  from a klib, which is the same information §2 says a stub needs, but no klib was read for this
  document.

---

## 1. Observed, PyREPL's generator

### 1.1 The pipeline

`app/build.gradle.kts` registers one task, `createKotlinMetaPackageForPython`, and hooks it onto the
IDE's own import step:

    project.tasks.getByName("prepareKotlinIdeaImport").dependsOn(createKotlinMetaPackageForPython)

`resolveDependenciesForPython()` (line 211) walks `kotlinExtension.sourceSets`, and for each source
set makes a **resolvable copy** of its `implementation` and `api` configurations:

    configurations.create(configuration.name + "Resolved") { extendsFrom(configuration); isCanBeResolved = true }

then takes `resolvedConfiguration.lenientConfiguration.artifacts`. Unresolved dependencies that name
a sibling project are recursed into with `isSubModule = true`, which switches the copy to `api` only
the same visibility rule Gradle itself applies. Everything else is collected as genuinely
unresolved and printed.

The output directory is derived per source set: from `sourceSet.kotlin.srcDirs`, take the one under
`<name>/kotlin`, and emit into its sibling `generated/meta`, i.e. `src/<sourceSet>/generated/meta/`,
**inside the source tree, not the build directory**. That directory is then registered as a Python
source root through the only mechanism the project had for one:

    chaquopy { sourceSets { getByName("main") { srcDirs(
        "src/androidMain/python", "src/androidMain/generated/meta",
        "src/commonMain/python", "src/commonMain/generated/meta") } } }

A `build/kotlin-python/meta_json.lock` records the resolved artefact set (group, name, version,
file) as JSON so a changed dependency graph can be detected. **It is computed and then ignored**,
`outputs.upToDateWhen { false }` and the `if (!isChanged) return@doLast` guard is commented out, so
the task runs every time.

`processJarForMetaPackage` reads every `.class` in a jar with ASM into a `ClassNode` map, then emits
for each class whose binary name has no `$` and which is not `private`/`protected`.

### 1.2 What it writes

One `__init__.pyi` **per Java package**, at `createInitPythonFile` (line 368), the file is named
after the package directory, and every class in that package is *appended* to it. Bodies are `...`;
there is no runtime in the file at all.

    class Version:

        id: String

        def main(self, *args, **kwargs): ...

Filters, from `generateMetaPythonClass` (line 375): drop `private`/`protected`; drop fields starting
`$` or `Companion`; drop methods starting `<`; **drop any member whose name contains `-`**, the
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
**this path is Windows-only** and silently does nothing on macOS or Linux, `docs/design/ecosystem.md`
already flagged the separators; what is added here is that the failure is silent rather than an
error.

### 1.3 The two lineages, and a correction to `docs/design/ecosystem.md`

`docs/design/ecosystem.md` §2 says `dba17dd` "extracted PyREPL's meta-generation" into `createMetaClass.kt`
and that `toolchain`'s copy is "the same code mutated into a *runtime* generator". The extraction is
right; the implied direction is not.

`git show f0dd368:app/build.gradle.kts`, the commit in **toolchain's own history** that first added
the generator, one day before the extraction, already emits `.py`, not `.pyi`:

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
no task, and its output is `def name(self): pass` with no types, but the instinct is the same one
this repository's `KotlinMetadata.kt` acted on, two years later.

### 1.4 What survives the port

| PyREPL | keep? | why |
|---|---|---|
| resolvable copy of `implementation`/`api` per source set | **superseded** | `PythonArtifactBindingsTask` already takes `configuration.incoming.artifacts`, which carries coordinates as well as files |
| per-package `__init__.pyi` | **keep** | it is what makes `from androidx.compose.material3 import Text` resolve, and §6.1 confirms a directory of `.pyi` with no `.py` and no `__init__.py` resolves |
| `...` bodies | **keep** | a stub never has to compile or run |
| `def f(self, *args, **kwargs)` | **reject** | it is the whole of what makes these stubs useless for anything but name completion. Parameters are the point (§3) |
| JNI-ish type map (`jint`, `jlong`) | **reject** | not Python types; a checker infers nothing from them |
| drop names containing `-` | **reject** | `docs/design/kotlin-extensions-in-python.md` §2.2: it discards 52 of the 177 chainable `Modifier` extensions, and metadata supplies the real name |
| `class StringsKt:` for a file facade | **reject** | there is no `StringsKt` in the Kotlin namespace; a top-level function must be a module-level `def` |
| commonMain/platform overlap → rename + re-export | **keep the idea, rewrite** | the shape is right and the implementation is Windows-only and silent (§1.2) |
| `prepareKotlinIdeaImport` dependency | **keep** | §6.2 |
| `meta_json.lock` | **reject** | Gradle's own up-to-date checking does this, and PyREPL's was disabled anyway |

---

## 2. The input, judged

### 2.1 Observed (2026-08-14): what the two producers kept then

> Current: the loss described here is repaired for the stub path by `DeclarationModel` (§2.2). The
> table is kept because it is the reason the model exists.

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
| `kind` | `"FUNCTION"`, `"METHOD"`, … |, (always FUNCTION) |
| `lambdaBody` | generated Kotlin source text | generated Kotlin source text |
| `isSuspend` | yes |, (always false) |
| `imports` | - | extension-call aliases |

`Tag` (`TypeShape.kt` line 9) is `INT | FLOAT | BOOLEAN | STRING | BYTES | UNIT | OBJECT`, seven
values, and `OBJECT` means "a handle, contents unknown". The runtime's `ExposedCallable`, which
`PythonProxySource.render` consumes, carries the same and nothing more.

**What is therefore absent from every existing representation:**

- parameter **names**: `FragmentScanner` does `function.parameters.map { it.type.toShape() }` and
  drops `it.name`; `ArtifactScanner` does the same to `KmValueParameter`
- **defaults**: `KmValueParameter.declaresDefaultValue` is read by nobody
- **nullability**: `resolveKotlinType` returns `null` for a nullable type outright
  (`KotlinMetadata.kt` line 206), so the fact never leaves the scanner
- the **declared Kotlin type**: `Dp` becomes `Tag.FLOAT`, `List<String>` becomes `Tag.OBJECT`
- whether a parameter is an **extension receiver**, known inside `buildCallableFromFunction`, spent
  on choosing `receiver.alias(...)`, then discarded
- **overload grouping**: both producers *delete* the information: `ArtifactScanner.scanClassNode`
  line 230 keeps only names with exactly one candidate; `FragmentScanner` keeps the first
- value-class identity, `ValueClassInfo` exists (`KotlinMetadata.kt` line 168) and is consumed
  inside a closure to build a wrap/unwrap expression, never surfaced

### 2.2 Judged: the input is a third representation, upstream of both renderers

**Neither producer's output can drive a `.pyi` generator, and neither should be widened to try.**

The reasoning is the one `PythonProxySource`'s KDoc already gives for a different split: an
`ExposedCallable` is *"exactly the inputs"* for the runtime proxy, and that is the point, it was cut
down to exactly what a trampoline needs. A `.pyi` needs a strict superset of what a *call site* needs,
because a stub describes a signature and a call site only has to produce one. Growing
`CallableEntryModel` to carry parameter names and defaults would put fields into the model that
`renderFragmentSource` must ignore, on a path where every field today is load-bearing.

So: **one declaration model, produced by each scanner, consumed by two renderers.**

    KSP (KSFunctionDeclaration)  ─┐
    ASM + kotlin-metadata-jvm    ─┼─►  DeclarationModel  ─┬─►  binding renderer  (exists)
    LibraryAbiReader (klib)      ─┘                       └─►  .pyi renderer     (this document)

**Current (2026-10-03):** implemented as `DeclarationModel` / `DeclaredParameter` /
`KotlinTypeModel` / `ValueClassModel` in
`python-multiplatform-gradle-plugin/src/main/kotlin/python/multiplatform/gradle/model/StubModel.kt`.
It is produced by `ArtifactScanner.scanDeclarations` (jars) and `KlibScanner` (klibs, through the
isolated worker), in the same walk that produces the bindings. **KSP does not produce it**, so the
consumer's own KSP-bound declarations get no stub. Two differences from the plan below: the model
carries each declaration's **boundary tags** (`receiverBoundaryTag`, `DeclaredParameter.boundaryTag`,
`returnBoundaryTag`) and its table key (`bindingName`) or `declineReason`, because the stub annotates
what crosses, not what Kotlin declares (§3.1); and of the annotations it keeps only `isComposable`
(no `@Deprecated`).

`DeclarationModel` carries, per declaration: Kotlin qualified name; owner; whether it is a top-level
function, a member, a constructor, a property; the extension receiver's Kotlin type or `null`; an
ordered parameter list of `(name, KotlinTypeModel, declaresDefault)`; the return `KotlinTypeModel`;
`isSuspend`; and the annotations that matter (`@Composable`, `@Deprecated`). `KotlinTypeModel`
carries a qualified name, nullability, type arguments, and, when the classifier is a value class,
its underlying `KotlinTypeModel` plus the two visibility booleans `ValueClassInfo` already computes.

Three properties this buys, each of which is a defect in the current shape:

1. **The two renderers cannot drift.** `docs/design/kotlin-extensions-in-python.md` §4.5 requires this and
   the current code cannot deliver it: a `.pyi` written from `ArtifactCallable` would state
   `padding(a0: float)` while the binding calls `Modifier.padding(Dp(...))`.
2. **Overloads survive to where they are needed.** The binder drops an ambiguous name because it
   cannot dispatch (`docs/roadmap/ROADMAP.md` §16c: `Assert.assertEquals` has eight). A stub *can* state all of
   them (§3.6). Those are different decisions and today they are the same line of code. *(Since
   then the binder stopped dropping them: each member gets its own `name__<types>` key, §3.6.)*
3. **What is declined stays visible.** A model entry can be marked "not bound, reason X" and still
   be stubbed, or deliberately not stubbed, which is the honest choice when the runtime cannot call
   it. Today a declined declaration returns `null` and vanishes.

**Rejected alternatives:**

| alternative | why not |
|---|---|
| generate `.pyi` from the runtime `UpcallTable` (mirroring `PythonProxySource`) | the table holds `ExposedCallable`, i.e. tags and arity. Every parameter would be `a0, a1, a2` with no type. It is also the wrong time: `PythonProxySource` renders at run time *because* the installed set is only known then, and a stub has to exist before anything runs |
| widen `CallableEntryModel` / `ArtifactCallable` in place | puts fields on the hot path that its renderer must ignore, and does not fix the KSP/ASM asymmetry (`kind` and `isSuspend` on one, `imports` on the other) |
| a second scan, independent of the binder's | two readers of the same jars that can disagree, precisely the failure `PythonProxySource` §"Where this is generated" refuses ("a second generator that can disagree with the first") |
| emit `.pyi` from KSP as well as from the plugin | KSP does not see third-party artefacts at all, which is the entire reason the artefact walker exists (`docs/design/ecosystem.md` §5b). The plugin sees both |

### 2.3 What each producer has to supply

| producer | has it today | needs |
|---|---|---|
| KSP | `KSFunctionDeclaration` gives names, defaults (`KSValueParameter.hasDefault`), nullability, type arguments, annotations | nothing new read, only *kept*. `KsAdapters.renderWithArguments` already walks type arguments |
| artefact walker | `KmFunction` gives `valueParameters[].name`, `.declaresDefaultValue`, `KmType.isNullable`, `.arguments`, `receiverParameterType`; `ValueClassInfo` gives the rest | nothing new read, only *kept*. `functionsOf` currently throws away `KmValueParameter` and keeps `.type` |
| Java classes in a walked jar (no `@Metadata`) | JVM descriptors only | **no Kotlin types, no parameter names, no nullability.** §3.2 |
| klib | **current:** `KlibScanner` builds `DeclarationModel` through `LibraryAbiReader` | what it cannot fill it declines on, and a declined declaration is not stubbed (`PythonStubsTask`); no klib declaration is bound at run time yet (SPEC B-2), so in practice no klib stub is emitted |

That the first two need **no new reading** is the strongest argument for this shape: the information
is being fetched and then dropped, one function before it would be used.

## 3. Kotlin → Python type mapping

### 3.1 The table, the declared Kotlin type, a stub class per bound type

**Current (issue #31).** A stub says what the Kotlin declaration says, wherever the runtime serves
that type. `renderKotlinFqnStubs` (`PyiRendering.kt`) maps each `KotlinTypeModel` with its boundary
tag as the tiebreaker, and the Kotlin signature stays in the docstring:

    def padding__Dp(receiver: androidx.compose.ui.Modifier, /, all: androidx.compose.ui.unit.Dp | float) -> androidx.compose.ui.Modifier:
        """Kotlin: androidx.compose.ui.Modifier.padding(all: androidx.compose.ui.unit.Dp): androidx.compose.ui.Modifier"""
        ...

| Kotlin | `.pyi` | why |
|---|---|---|
| `Boolean` | `bool` | |
| `Byte` `Short` `Int` `Long` | `int` | the boundary carries every one as `Long`; Python has one integer type |
| `Float` `Double` | `float` | |
| `String`, `Char` | `str` | |
| `ByteArray` | `bytes` | |
| `Unit` | `None` | |
| `Any` | `typing.Any` | |
| any other class or interface | **a stub class in the module of its own Kotlin package** (`class Modifier` in `androidx/compose/ui/__init__.pyi`), referenced as `androidx.compose.ui.Modifier` from other modules and bare from its own | the value crosses as a `HandleTable` integer and the binding layer wraps it in a proxy class named for the declared type (`PythonxAdapter._wrap`). A nested class is nested in its outer class's stub |
| a class that extends another | `class BitmapPainter(Painter)` | the nearest supertype the scanner read off the jar (`DeclarationModel.returnSupertypes`, the same list `_is_a` accepts a value for); only for types some bound declaration returns |
| value class bound as its primitive (`Dp`) | `Dp \| float` as a parameter, `float` as a result | a result is the raw number (`_wrap` only wraps `OBJECT`); the raw module accepts the number, and the binding layer's allowlist (`allow_raw_primitive`) decides at run time, which no stub can know |
| value class the binder cannot open (`TextUnit`) | its own class | it crosses as an object handle |
| `(A, B) -> R` | `Callable[[A, B], R]` | `kotlin.FunctionN`'s type arguments; `@Composable` and a receiver are not expressible and not faked |
| `T?` | `T \| None` | `KotlinTypeModel.isNullable` |
| type arguments of a non-function class | dropped (`State<Boolean>` is `State`) | the proxy class is one per Kotlin class |

`typing` is imported as `_t` (`import typing as _t`) because a Kotlin declaration may be called `Any`
or `Callable`; other modules are imported whole (`import androidx.compose.ui`) and referenced
qualified, so a class never collides with a name the importing module defines.

**A class whose name is also a function in its module falls back to `typing.Any`.** Compose has
`PaddingValues(...)` the factory and `PaddingValues` the interface, `Color(...)` and `Color`, `Dp(...)`
and `Dp`. One Python module cannot define both, and the runtime resolves the name to the function
(`_adapt` asks `_BY_PACKAGE` first), so the type is not reachable there either. In Compose 1.11.1 this
turns `PaddingValues`, `TextStyle`, `Color`, `AnnotatedString` and `Dp` itself into `Any` (`Dp` then
collapses to its `float`). It is the honest loss of this design, not a bug to chase: the alternatives
are a differently named class (a renamed spelling the binder does not have) or a private side module.

**Stub paths are case-unique** (issue #44). `androidx.compose.ui.graphics.Shadow` (a class the runtime
exposes as a module) and `...graphics.shadow` (a package) would be one directory on macOS and Windows.
The rule: a class module whose path equals a sibling's under case folding (directories above a module
count) is emitted in its parent package's `__init__.pyi` -- as the class `Shadow`, its functions as
`@staticmethod`s, nested class modules as nested classes -- and the package keeps `shadow/__init__.pyi`.
In each colliding group the all-lower-case spelling is the package and keeps its path. A class module
without a collision still gets its own `<Class>/__init__.pyi`. The runtime import path is unchanged; only
the stub layout moves, and `from androidx.compose.ui.graphics import Shadow` resolves to the class.
If the parent package already defines a function of the same name the class cannot sit beside it, and
nothing is emitted for that module (the runtime resolves the name to the function too).
`StubCaseLayoutTest` asserts no two emitted paths are equal under `lowercase()`;
`PythonStubsTask` still warns (`caseCollidingPaths`) as a backstop, and the CI job runs on macOS as well.

### 3.2 Java declarations have no types to map

A class in a walked jar with no `@Metadata` goes down `ArtifactScanner.javaStaticCandidates`, which
has only the JVM descriptor. That path can produce `int`, `str`, `bytes`, `bool`, `float`, `None` and
nothing else, no nullability, and **no parameter names** (`MethodNode.parameters` is present only
when the jar was compiled with `-parameters`, which is not the default and cannot be assumed).

Judged: for a Java-sourced declaration, emit positional-only parameters,

    def assertEquals(__a0: int, __a1: int, /) -> None: ...

rather than inventing names. A wrong keyword name is worse than no keyword name, because it
type-checks at the call site and fails at run time. Reference return types from Java get `| None`,
because Java can return null and nothing in the bytecode says otherwise.

This is a real asymmetry between a Kotlin jar and a Java jar and it should be visible in the output,
not hidden.

> **Correction (2026-10-03):** the sentence above about `| None` on Java reference returns describes
> the plan, not the code. The current stub annotates a Java return by its boundary tag like any other
> (`def id() -> str:` for `junit.runner.Version.id`, asserted by `PyiRenderingTest` and
> `WalkedArtifactStubTest`); no `| None` is added. Positional-only `__a<index>` parameters for a Java
> declaration without `-parameters` are implemented as described (`parameterNamesKnown == false`,
> `PyiRenderingTest.aJavaDeclarationGetsAnonymousPositionalParameters`), under the overload-suffixed
> table key: `def assertEquals__Long_Long(__a0: int, __a1: int, /) -> None:` (§3.6).

### 3.3 Nullability

**Current.** The binder's gate: a nullable **object** binds (the handle is already nullable at the
boundary; `UpcallTrampoline.toKotlin` maps Python `None` to `null`), and `String?` and `ByteArray?`
bind; a nullable numeric or `Boolean` primitive and a nullable value class stay declined
(`resolveKotlinBoundary` and `nullablePrimitiveBoundaryTypeOf` in `KotlinMetadata.kt`). The stub marks
every nullable it renders `T | None`, for parameters and results alike
(`TypedStubTest.aFunctionTypeIsCallableAndANullableTypeIsOptional`).

### 3.4 Value classes

**Current.** A value class the binder binds as its primitive (`Dp`: public constructor, wraps a
`Float`) is `Dp | float` as a parameter and the raw `float` as a result; the class `Dp` is stubbed in
`androidx.compose.ui.unit` with a docstring saying what it wraps. A value class whose wrapper cannot be
opened from outside its module (`TextUnit`, every `packedValue` class) crosses as an object handle and
is an ordinary class in parameters and results. `Color`'s public constructor takes `ULong`, which
`kotlinPrimitiveBoundaryTypeOf` has no entry for, so it is an object handle too.

The raw-primitive **allowlist** (`docs/design/kotlin-extensions-in-python.md` §4.4: `Dp` yes, `TextUnit`
and `Color` no) is not a stub concern. It is enforced at run time by the binding layer, where it starts
**empty** and is filled by pythonx-compose through `allow_raw_primitive` (`PythonxAdapter.kt`), so the
stub states the widest thing the raw module accepts (`Dp | float`) and the layer above narrows it.
The mypy evidence for the asymmetry (`Text(fontSize=16)` rejected, `padding(16)` accepted) is archive
§3.4; it needs the allowlist in the stub, which belongs to the Pythonic layer.

### 3.5 Function types, and Compose's `content`

**Current.** A function-typed parameter crosses as `OBJECT` (`ArtifactScanner.functionSlotOrNull`):
the runtime turns a Python callable into a Kotlin `FunctionN`. The stub says `Callable[[A], R]` from
`kotlin.FunctionN`'s type arguments (arguments the callback *receives* are rendered as results, the
callback's own result as a parameter); `Unit` is `None`. `@Composable` on a lambda and a function type
with a receiver (`ColumnScope.() -> Unit`, which the metadata spells as a leading argument) are not
expressible and are not faked. The Kotlin type stays in the docstring.

- **The synthetic parameters are not stubbed.** Parameters come from `KmFunction` metadata, so
  `$composer`, `$changed` and `$default` never appear (`docs/design/kotlin-extensions-in-python.md`
  §4.6: metadata arity is the correct source).
- **A required parameter after a defaulted one is keyword-only**: exactly as `inspect.signature` on a
  binder-made callable reports it (`KotlinSurface.kt`): `def Column(modifier: Modifier = ...,
  verticalArrangement: Any = ..., horizontalAlignment: Any = ..., *, content: Callable[[ColumnScope],
  None])`, and the defaults stay marked. The one exception is a declaration with an unwritable
  parameter name, where a positional-only prefix is already in play and a default is marked only when
  no required parameter follows it. `StubSignatureAgreesWithRuntimeTest` compares name, kind, default
  and type against `inspect.signature` for every stubbed function of three real modules.

**Suspend functions are not stubbed** (`renderKotlinFqnStubs` filters `isSuspend`), although the
runtime makes them awaitable (SPEC U-5). §7.

### 3.6 Overloads

**Current.** `ArtifactScanner.disambiguateOverloads` gives every member of an overload set its own
table key, `name__<types>` (`padding__Dp`, `padding__Dp_Dp`, `padding__Dp_Dp_Dp_Dp`,
`padding__PaddingValues`; a group of one keeps the bare name;
`docs/design/kotlin-extensions-in-python.md` §3.1). The stub emits **one `def` per table key**, and
also the **base name** the binding layer serves (`padding`, `_Overloads` in `PythonxAdapter.kt`) as an
`@overload` set **in table-key order**: the artefact table is sorted by name (`ArtifactScanner.scanJar`),
the binding layer indexes candidates in that order and `_Overloads` tries them in it, and a checker
takes the first match, so the stub's order and the dispatcher's are one order. For `padding` that is
`all`, `horizontal/vertical`, `start/top/end/bottom`, `paddingValues`. A base name is stubbed whenever a
key has an overload suffix, even for a group of one (the runtime serves it), and not when a declaration
already has that exact name.

`WalkedArtifactStubTest.everyStubbedFunctionIsATableKeyAndEveryTableKeyIsStubbed` asserts both
directions: every stubbed `def` is a table key or the base name of one, and every key is stubbed.

Python's one `int`/`float` and a class that fell back to `Any` cannot tell two Kotlin overloads apart
(`lerp(Int, Int, Float)` and `lerp(Float, Float, Float)`), so mypy reports every later overload as
`overload-cannot-match`. The runtime dispatcher tells them apart and the explicit spelling reaches each,
so each overload after the first carries `# type: ignore[overload-cannot-match]`
(`TypedStubTest.everyOverloadAfterTheFirstSilencesTheCheckersNeverMatchedDiagnostic`).

Parameters are keyword-capable under the keyword `inspect.signature` shows: the Pythonic one where an
alias is served (`all`, `fraction`, `alignment_line`), else the Kotlin name; each module-level name also
gets `alias = kotlinName` (issue #131, SPEC U-12;
`KotlinNamesOnlyStubTest.aKotlinNameKeepsItsDefAndGainsItsPythonicAlias`). A name
that is not a Python identifier or is a Python keyword, or an unknown name, forces positional-only up
to and including that parameter, spelled `__a<index>`
(`KotlinNamesOnlyStubTest.aParameterNamedLikeAPythonKeywordForcesAPositionalOnlyPrefix`). A Kotlin
default is `= ...`, because metadata carries `declaresDefaultValue` but not the value.

## 4. Extension functions

### 4.1 Placement (unchanged from `docs/design/kotlin-extensions-in-python.md` §4.1)

| receiver | count | placement |
|---|---|---|
| ordinary class or interface | 514 | method on the receiver's stub class |
| Compose value class | 41 | method on the value class's stub |
| Kotlin collection | 29 | module-level function |
| Kotlin primitive or `String` | 20 | module-level function, Python cannot attach a method to `int` |

Nothing measured here disturbs that. Chaining is free because each of the 177 returns `Modifier`, and
the mypy run in archive §4.4 confirms `Modifier.size(4).padding(2)` type-checks as `Modifier` in a Pythonic stub.

> **Current (issue #31):** every extension function stays a module-level `def` in the module of its
> declaring package, with the receiver as the first positional-only parameter named `receiver`
> (`def fillMaxWidth(receiver: androidx.compose.ui.Modifier, /, fraction: float = ...) ->
> androidx.compose.ui.Modifier:`), **and** an extension whose receiver is a class is also a **callable
> attribute of that receiver's stub class**, because the runtime attaches it there (`PythonxAdapter._attach`,
> SPEC U-7) under both the Kotlin name and every `name__Types` key:
>
>     class _Modifier_padding(_t.Protocol):
>         @_t.overload
>         def __call__(self, all: float) -> Modifier: ...
>         ...
>     class Modifier:
>         padding: _t.ClassVar[_Modifier_padding]
>         padding__Dp: _t.ClassVar[_Modifier_padding__Dp]
>
> A plain method cannot say both `Modifier.padding(16)` and `m.padding(16)` (the first binds `16` to
> `self`); a `ClassVar` of a `Protocol` with `__call__` can, and is the shape archive §4.4 measured
> with mypy. The receivers that stay module-level only are the ones the runtime cannot attach to:
> Kotlin primitives and `String`, function types, and value classes (never a proxy instance). The
> protocol classes are private (`_Receiver_name`), carry the Kotlin name unchanged and exist only for a
> type checker. `Modifier.padding(...)` at run time needs `register_empty` for `Modifier`; the stub does
> not know whether a consumer called it.

### 4.2–4.4 Moved

The `Modifier` metaclass proposal, its failure in CPython and mypy, and the callback-Protocol
attribute shape that replaced it are a Pythonic stub design: archive §4.2–§4.4.

### 4.5 Member extensions are still out

74 public member extensions on `Modifier` (`RowScope.weight`, `ColumnScope.align`,
`BoxScope.matchParentSize`) need a dispatch receiver Kotlin supplies implicitly from the enclosing
lambda. `docs/design/kotlin-extensions-in-python.md` §6 records that no shape has been chosen. Nothing here
changes that, and **they must not be stubbed as if they were plain methods on `Modifier`**, a stub
that says `Modifier.weight(1.0)` checks is a stub that promises a call the runtime cannot make.

---

## 5. Package mapping

There is none. A stub lives at the Kotlin module path the runtime publishes onto,
`androidx/compose/foundation/layout/__init__.pyi`, `junit/runner/Version/__init__.pyi` for a static on
a class, and nothing is emitted under `pythonx/`
(`KotlinNamesOnlyStubTest.theStubLivesUnderTheKotlinModulePathAndNothingLivesUnderPythonx`,
`WalkedArtifactStubTest.theStubsKeepKotlinNamesAddPythonicAliasesAndExportNothingUnderPythonx`). The `androidx.` →
`pythonx.` default rule, the observation that `pythonx.compose.layout` wraps
`androidx.compose.foundation.layout`, and the `pythonx-map.toml` manifest are in archive §5.

### 5.3 The Kotlin-FQN stubs, the one product

Of the two products the original §5.3 named, only the first remains:

| product | namespace | content |
|---|---|---|
| Kotlin-FQN stubs | `androidx.compose.material3`, `junit.runner`, … | 1:1 with the table keys `PythonProxySource` publishes into `sys.modules`; Kotlin names, declared-type annotations with a stub class per bound type, extension functions also as callable attributes of their receiver's class, the Kotlin signature in each docstring |

It is the direct descendant of PyREPL's output and the only thing an IDE can see for those modules,
since they exist only as runtime `sys.modules` entries. The Pythonic stubs a user of `pythonx`
imports against are pythonx-compose's product.

---

## 6. Where the files go, and how an IDE picks them up

### 6.1 Observed: what makes stubs take effect

Of the mypy runs in `/Volumes/macMini/tmp/pyicheck`, the two that bear on the Kotlin-FQN product
(measurements 1 and 2, about `.pyi` beside a `pythonx` `.py` and `py.typed` placement, are in archive
§6):

3. **A stub-only tree with no `.py` and no `__init__.py` resolves.** A directory containing only
   `androidx/compose/material3/__init__.pyi` on `MYPYPATH` gave
   `Revealed type is "def (text: str, color: int =, fontSize: int =)"` and rejected `Text(3)`. So
   the Kotlin-FQN stubs of §5.3, which describe modules that exist only in `sys.modules`, need no
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

**Current:** implemented as written for the first two bullets, `PythonStubsTask` writes
`build/generated/pythonStubs/<sourceSet>/`, deleting it first, and `generatePythonStubs` is a
dependency of `prepareKotlinIdeaImport` (`PythonBindingsPlugin.configureStubGeneration`). No
environment registration is implemented. A `pythonx` wheel's own stubs are pythonx-compose's (archive
§6).

### 6.3 The commonMain/platform overlap

PyREPL's answer (§1.2) is the right shape: when the same qualified name is contributed by
`commonMain` and by a platform source set, rename both and write an `__init__.pyi` that re-exports
each. Its implementation splits on `"\\__init__.pyi"` and so does nothing outside Windows. The port
must use `File.separator`, or better, operate on `Path` objects and never on path strings, which is
what made the bug invisible.

`docs/design/ecosystem.md` §5b's `JClass`/`KClass`/`ObjcClass` rule bears on this: a target that has no JVM
has no `JClass` and says so. The stubs must reproduce that per source set rather than emitting a
union that promises every platform's surface everywhere.

**Current:** not implemented, `PythonStubsTask` is registered per source set and nothing detects or
merges an overlap.

---

## 7. Open

- **A class whose name is a function in its module is `Any`** (§3.1): `PaddingValues`, `TextStyle`,
  `Color`, `AnnotatedString`, `Dp` in Compose 1.11.1.
- **Supertypes are the nearest one only**: and only for types a bound declaration returns (§3.1); an
  interface a class implements besides its first listed supertype is not in the stub.
- **Type arguments of a non-function class are dropped** (§3.1).
- **Suspend functions** (§3.5) are bound and awaitable but not stubbed.
- **The consumer's own KSP-bound declarations** are not stubbed: KSP does not produce
  `DeclarationModel` (§2.2), although §2.3 records it would need to read nothing new.
- **Generic declarations.** `BindingPolicy` rejects them, so they are not stubbed; if the binder ever
  accepts them, `TypeVar` is the mapping and nothing here covers it.
- **klib.** The scanner produces declarations (§2.3), but none is bound at run time yet (SPEC B-2),
  so none is stubbed.
- **Member extensions** (§4.5), 74 on `Modifier`, remain unstubbable until the scope-passing shape
  is chosen.
- **IDE pick-up** (§6.2): registering `build/generated/pythonStubs/<sourceSet>/` with an interpreter
  or IDE is per-environment and none of it is implemented or verified; PyCharm was never tested
  (§0). Only mypy has seen the typed shape (§8).
- **commonMain/platform overlap** (§6.3) is not implemented.

## 8. Checked with mypy, and published

- `tools/stubs/check-stubs.sh <stub-dir>` runs `mypy --strict` over `tools/stubs/consumer.py` (a
  `Modifier` chain from the class and from an instance, `Checkbox`, `Text`, `Column` with its keyword
  `content`, and four lines that must be rejected) against stubs generated for the real Compose jars
  (`:ksp-fixtures:compose:generatePythonStubs`, which `ksp-fixtures/compose` now enables). Errors inside
  the stubs are reported as well, not only in the consumer.
- `.github/workflows/stubs.yml` runs the stub task and that check on every push to `develop`, uploads
  the stubs as the workflow artifact `kotlin-stubs` (with a README naming the Compose version and the
  producing commit) and attaches `kotlin-stubs.zip` to the release of every `v*` tag (issue #32).
