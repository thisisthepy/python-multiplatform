# Upcalls — Python → Kotlin

How Python code imports Kotlin packages, constructs Kotlin classes, calls their functions (including
`suspend` ones) and reads their properties. The opposite direction is
[`downcall-design.md`](downcall-design.md); what is exposed at all is
[`binding-policy.md`](binding-policy.md); object lifetimes and cycle collection are
[`object-lifetime.md`](object-lifetime.md); thread state is [`threading-and-abi.md`](threading-and-abi.md).

## 0. Status and how to read this document

This is a **design record**. The behavioural contract is [`SPEC.md`](../SPEC.md) §5 (U-1 … U-8) and
the boundary is [`INTENT.md`](../INTENT.md) §2.1, §2.2, §2.6; where this document and those disagree,
they win. It replaces three earlier documents (`upcall-design.md`, `upcall-table-design.md`,
`upcall-async-design.md`); their history is in git.

What is implemented, in one paragraph: a KSP processor generates a function table for every `public`
declaration of a module at build time (U-1); one entry point per platform marshals arguments into it
(U-2); generated Python proxies make Kotlin packages importable under their own names with classes,
properties, companions and statics (U-3, U-4); `suspend` functions are awaitable (U-5); upcalls work in
a GraalVM native image (U-6, manually verified); every function on a Kotlin-named module carries Kotlin's
own surface (U-8). It runs on desktop, Android (ART), iOS, androidNative and wasmJs, with the exceptions
listed in §8.

Conventions used below:

- **Measured** means it was run in this repository and the test that prints or asserts it is named.
  **Inferred** means it was read from code or documentation and not run. **(unverified)** marks a
  statement this consolidation could not check against code or tests.
- Every measurement carries its conditions (platform, warmup, run count, commit where recorded).
  Nothing in the test suite asserts a duration: wall-clock thresholds on a shared machine, an emulator or
  a Node host are flake generators. Tests assert structure (the call reached Kotlin, the figure is
  positive) and print the numbers.
- The authoritative, harness-cut cost figures are [`cost-table.md`](../investigations/cost-table.md)
  (`./benchmarks/cost-table.sh --runs 3`). Hand-cut tables here are records of the round they were
  taken in; prefer the cost table where they disagree.

---

## 1. Decisions

### 1.1 No runtime reflection

Two reasons, the second more fundamental:

1. **GraalVM native image** is closed-world: runtime reflection does not work without registration.
2. **Kotlin/Native has effectively no reflection** (`::class.simpleName`, no member enumeration). iOS
   and androidNative have no reflective path regardless of GraalVM.

A multiplatform library therefore has **no option but a table generated at build time**
(AGENTS.md §12.3, INTENT §2.1). The same choice is what makes cycle collection possible (§6).

### 1.2 Resolve a name once, then pass a handle

Objective-C is fast not because it has a table but because a selector is an interned pointer:
`objc_msgSend` finds the IMP in the class's method cache in a few instructions without touching a string.
The equivalent here:

| When | What happens |
|---|---|
| Once per name | Python passes a name (`"demo.Counter.increment"`); **Kotlin resolves it** in the table and returns a `CallableHandle` |
| Every call after that | The handle crosses, never the string |

Passing a string per call costs `PyUnicode` → UTF-8 (an allocation) + the boundary + hashing + comparison —
hundreds of ns — whatever the table looks like. `UpcallTable.resolve` returns a `CallableHandle` whose raw
value packs the table **epoch** (high 32 bits) and the index (low 32), so a handle cached across a table
reinstall is rejected rather than calling a different function
(`python-multiplatform/src/commonMain/kotlin/python/multiplatform/reflection/ExposedCallable.kt`,
`UpcallTable.kt`). The in-Kotlin part of the comparison is measured by
`commonTest/.../reflection/UpcallOverheadTest.kt` (`testNameResolutionVersusCachedHandle`, 200-entry
table); that test's numbers are a lower bound on the gap because the boundary is not in them.

A table entry is a **generated lambda**, not a `KFunction`: `KFunction.call` re-checks arity and types and
boxes on every call (50–100 ns, measured in this project's benchmarks) and does not exist on
Kotlin/Native, while `{ args -> greet(args[0] as String) }` compiles to a direct call there and gives the
linker a static reference (§2.6).

### 1.3 Route by data, so a few trampolines serve everything

Every CPython callback convention passes the relevant object as an argument (checked against the headers):

| Slot | Signature | What identifies the target |
|---|---|---|
| `PyCFunction` | `(PyObject *self, PyObject *args)` | `self` |
| `destructor` | `(PyObject *self)` | `self` |
| `getter` / `setter` | `(PyObject *self, void *closure)` | the explicit `closure` |
| `initproc` | `(PyObject *self, args, kwargs)` | `self` |
| `newfunc` | `(PyTypeObject *type, args, kwargs)` | `type` |

So routing is done **with data, not function pointers**: a handful of shared trampolines serve an
unbounded number of Kotlin targets, and **no runtime code generation** (LLVM JIT, libffi closures) is
needed. JPype, PyO3 and JEP all work this way. The only case that would need runtime code generation is a
pure C callback with no context argument at all (a `qsort` comparator); the CPython object model has none.

Argument passing needs exactly **one** carrier shape (§3.2), already inside the downcall vocabulary.

### 1.4 One entry point per platform, one table behind all of them

Behind the per-platform entry point every platform uses the same generated table and the same
`commonMain` trampoline (`UpcallTrampoline`); a platform contributes only the few lines that give Python
the address of an entry point (§3.7). INTENT §2.6.

### 1.5 Exposure is a blacklist

Every `public` declaration is registered; `@PythonInternal` opts a class or member out. A whitelist
(`@PythonAPI`) was rejected (AGENTS.md §12.7; [`binding-policy.md`](binding-policy.md)). This is the
premise the tree-shaking decision (§2.6) rests on and must not be re-litigated there.

### 1.6 Kotlin namespaces, Kotlin names and their Pythonic aliases

A Kotlin package is importable under its own fully-qualified name and nothing else: no namespace is
renamed and no synthetic `pythonx` module exists (INTENT §2.2–2.3, AGENTS.md §12.1–12.2). Functions on a
Kotlin-named module take keyword arguments by **Kotlin parameter names**, honour Kotlin defaults for
omitted parameters, and expose their signature through `inspect.signature` and
`python_multiplatform.describe` (SPEC U-8; contract in the KDoc of
`python-multiplatform/src/commonMain/kotlin/python/multiplatform/ffi/upcall/KotlinSurface.kt`). Beside
every Kotlin name, the declaration, member or keyword is also reachable by its Pythonic (snake_case)
alias when that alias is unambiguous (SPEC U-12, issue #131). This was removed in `238119b7` on a
misreading of §12.1 and restored; §12.1 now says it governs namespaces only. See §4.3.

---

## 2. Table generation

### 2.1 Fragments, one per module

The KSP processor (`python-multiplatform-ksp/`) is shipped as an artifact and runs in **every** module that
exposes Kotlin to Python — including the user's own modules, since exposing the user's classes is the
point. Each module emits one **fragment object** into the well-known package
`python.multiplatform.generated.fragments`:

```kotlin
// GENERATED by python-multiplatform-ksp. Do not edit.
package python.multiplatform.generated.fragments

object Fragment_io_github_thisisthepy_ksp_fixtures_library : python.multiplatform.reflection.FunctionTableFragment {
    override val moduleName: String = "io_github_thisisthepy_ksp_fixtures_library"
    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(name = "fixture.library.greet", arity = 1, paramTypes = listOf(TypeTag.STRING),
            returnType = TypeTag.STRING, paramNames = listOf("name"), callable = { args -> fixture.library.greet(args[0] as String) }),
        // ...
    )
    override fun classes(): List<ReflectedClass> = listOf(/* ... */)
}
```

(Shape from `SourceRendering.kt`'s `renderFragmentSource`; abbreviated.)

- **Name**: `Fragment_<moduleName>`, where `moduleName` is the KSP option
  `python.multiplatform.moduleName` with `.` and `-` replaced by `_` (`Constants.kt`). The Gradle plugin
  derives it from the **Maven group plus the project path** (`:ksp-fixtures:app` with group
  `io.github.thisisthepy` → `io_github_thisisthepy_ksp_fixtures_app`), because fragments from every
  artifact share one package and two libraries both called `:core` would otherwise collide. The name is
  not load-bearing for discovery (the whole package is scanned) but prevents collisions.
- **Visibility: `public`.** An `internal` fragment does not work: `internal` is enforced per Kotlin
  module, and the app module's aggregator referencing a library's fragment is another module even inside
  one Gradle build ("cannot access … it is internal").
- **Each `ExposedCallable` carries** the Python-visible qualified name, `arity`, a `TypeTag` per
  parameter and for the return, a `CallableKind` (where the arguments are: `FUNCTION`, `CONSTRUCTOR`,
  `METHOD`, `GETTER`, `SETTER`, `STATIC_GETTER`, `STATIC_SETTER`), `isSuspend` (§5.3), the Kotlin
  `paramNames`, declared type names, extension/receiver data, `paramHasDefault`, and the lambda.
  `ReflectedClass` entries carry class metadata and, for classes with `PyObject`-typed properties, a
  generated `tp_traverse` body (§6).
- The library's own packages (`python.native.ffi`, `python.multiplatform.ffi`, `…reflection`, `…ref`)
  and the generated packages are always excluded; `python.multiplatform.excludePackages` adds more.

### 2.2 What is scanned

| Declaration | Exposed | Notes |
|---|---|---|
| `public` top-level functions | yes | `main()` with no parameters or one `Array<…>` is excluded (`BindingPolicy.isExposedTopLevelFunction`) |
| `public` top-level properties | yes | module attribute: `STATIC_GETTER` / `STATIC_SETTER` |
| `public` classes + constructors | yes | constructor only if concrete and non-`inner` (`isConstructible`) |
| `public` member functions | yes | |
| `public` properties | yes | getter; setter only for a `var` whose setter is itself public (`private set` / `internal set` are read-only from Python, `isExposedSetter`) |
| `companion object` members | yes | under the **owner's** name, no receiver |
| `object` declarations | yes | same shape, no constructor |
| `interface` | yes | members only; not constructible; receiver arrives as a handle |
| `enum class` | yes | one `STATIC_GETTER` per entry, plus `name`/`ordinal`/`valueOf` |
| nested classes / interfaces / enums / objects | yes | under `Outer.Inner`; the walk stops at a non-exposed owner |
| `suspend` functions | **yes** | body wrapped in `PendingCall.start { }`, `isSuspend = true` (§5.3) |
| `annotation class` | no | reading one back needs runtime reflection; an instance has nothing to attach to |
| enum `values()` / `entries` | no | return collections; `ReflectedClass.enumEntryNames` carries the same data |
| generic declarations (`class Box<T>`, `fun <T> f`) | no | `args[0] as T` does not compile |
| abstract / sealed class constructors | no | "Cannot create an instance of an abstract class" |
| `internal` / `private` / `protected` | no | |
| extension functions / extension properties | no (KSP) | the artifact walker does bind extensions, as methods on the receiver's proxy ([`kotlin-extensions-in-python.md`](kotlin-extensions-in-python.md); SPEC U-7 is `partial` and not asserted for KSP-generated proxies) |
| `@Composable` functions in the module's own source | no | a generated non-composable lambda cannot call one; the artifact walker binds composables instead (`BindingPolicy.isComposable` KDoc) |
| declarations whose type is a `suspend` function type, or one nested in a generic argument | no, **with a KSP warning** | `"has a type with no exposable classifier"` (`FragmentScanner.warnUnexposableType`; `GeneratedSuspendTest`) |
| compiler-generated `copy`, `componentN` | no | the subset KSP reports for a `data class` (no `equals`/`hashCode`/`toString`) |
| `expect` declarations | no | the `actual` is found on the platform side |
| `@PythonInternal` | no | opt-out |

Asserted against KSP-generated fragments, not hand-written ones:
`ksp-fixtures/app/src/desktopTest/.../GeneratedDeclarationKindsTest.kt`,
`GeneratedSuspendTest.kt`, `GeneratedTableTest.kt` (which runs `UpcallTableTest`'s scenarios — constructor
/ method / getter / setter round trip through `HandleTable`, `@PythonInternal` exclusion, narrow
`Int`/`Float` widening, `tp_traverse` field detection — against a generated table), and
`python-multiplatform-ksp/src/test/.../SourceRenderingTest.kt`.

### 2.3 Aggregation in the app module

Kotlin/Native has no `ServiceLoader`; a top-level `object` that nothing references is never initialised
(lazy initialisation is the default since K/N 1.7.20); and reflection cannot enumerate. So a fragment that
registers itself in its own initialiser never runs. Fragments must be referenced explicitly by something
reachable.

The **app-role** module's KSP therefore generates an aggregator that names every fragment:

```kotlin
package python.multiplatform.generated

object FunctionTable {
    val fragments: List<FunctionTableFragment> = listOf(
        python.multiplatform.generated.fragments.Fragment_my_library,
        python.multiplatform.generated.fragments.Fragment_my_app,
    )
    fun installInto() { python.multiplatform.reflection.UpcallTable.install(fragments) }
}
```

(`renderAggregatorSource`.) `UpcallTable.install` clears the table (bumping the epoch) and registers each
fragment; a fragment already registered under the same `moduleName` is a no-op, and **a name claimed by two
different fragments is an error**, detected before anything is mutated (SPEC U-1).

**Discovery** uses `resolver.getDeclarationsFromPackage("python.multiplatform.generated.fragments")`, in two
KSP rounds:

1. **Round 1** — generate the app's own `Fragment_<app>`. The new file triggers round 2.
2. **Round 2** — `getDeclarationsFromPackage` now sees the app's fresh fragment **and** every dependency's
   fragment, which is on the compilation classpath as compiled classes (JAR) or klib metadata. Generate
   `FunctionTable`.

KSP cannot see other modules' *sources*, but this query reads the classpath, which is why it works. The key
is the well-known package, not an annotation: `getSymbolsWithAnnotation` is documented as potentially
expensive because it scans the whole classpath; the package query is cheap. (A marker-interface filter on
`FunctionTableFragment` would also work; the package is the primary mechanism.)

`getDeclarationsFromPackage` is `@KspExperimental`. It is the documented cross-module API, nothing
non-experimental does the job, and it has been stable since KSP 1.x. Discovery sits behind the
`FragmentDiscovery` interface (`FragmentDiscovery.kt`) so a fallback — each module writing a metadata file
under `META-INF/`, read by the aggregator — can replace it without changing the fragment or table shape.

The same mechanism serves every target:

| Platform | Discovery source | Status |
|---|---|---|
| JVM desktop | JARs on the classpath | `ksp-fixtures/app` desktop tests |
| Android | JAR/AAR classpath | `ksp-fixtures/android` (`GeneratedAndroidTableTest`, unit and instrumented) |
| GraalVM native image | classpath at build time; the table is statically reachable, so no reflection config | verified manually, [`graal-native-image-verification.md`](../platforms/graal-native-image-verification.md) (SPEC U-6) |
| Kotlin/Native | klib metadata | `ksp-fixtures/{library,app}` on `androidNativeArm64`: round 2 found the library's fragment in its `.klib`, `FunctionTable` compiled and linked, and `ksp-fixtures/app/src/androidNativeArm64Test/.../NativeSmokeTest.kt` runs it (SPEC U-3) |

`ServiceLoader` could work on JVM/Android, but one aggregator everywhere avoids a platform split and runtime
classpath scanning.

### 2.4 Reaching `FunctionTable` from shared code: `@InstallsUpcallTable`

`FunctionTable` is emitted into each leaf compilation, so only that target's own source set can name it;
an intermediate source set (`commonMain`, `iosMain`, `androidNativeMain`) gets
`Unresolved reference 'FunctionTable'`. Generating in `kspCommonMainMetadata` instead would fix visibility
and break the scan (a common-only scan sees only common declarations). So generation stays in the leaves
and the **seam** is generated:

```kotlin
// commonMain
@InstallsUpcallTable
expect fun installGeneratedUpcallTable()
```

The app-role processor finds the annotated `expect` (a leaf compilation resolves its whole source-set
closure) and writes one `actual` per leaf compilation into the `expect`'s package:

```kotlin
@python.multiplatform.reflection.PythonInternal
actual fun installGeneratedUpcallTable() { python.multiplatform.generated.FunctionTable.installInto() }
```

`@PythonInternal` because the `actual` is a public top-level function in the user's package and an
incremental round could otherwise expose "reinstall the table" to Python. A `library`-role module carrying
the annotation is an error (it aggregates nothing). `expect`/`actual` rather than an interface plus a
runtime registry, because the reference chain must stay static or the Kotlin/Native linker may drop what
nothing names. The seam's shape is checked in `InstallSeam.kt` (top-level, no parameters, returns `Unit`,
public or internal).

### 2.5 The processor, its options, and the Gradle plugin

One `SymbolProcessorProvider` (`PythonBindingProcessorProvider.kt`) with two roles:

| `python.multiplatform.role` | Does |
|---|---|
| `library` | scan the module, emit its fragment |
| `app` | emit its fragment (round 1), discover all fragments and emit `FunctionTable` and the `@InstallsUpcallTable` actuals (round 2) |

| KSP option | Required | Meaning |
|---|---|---|
| `python.multiplatform.role` | yes | `library` or `app` |
| `python.multiplatform.moduleName` | yes | sanitised fragment name |
| `python.multiplatform.excludePackages` | no | comma-separated packages to skip, on top of the always-excluded ones |

**The Gradle plugin is built**: `python-multiplatform-gradle-plugin/`, id
`io.github.thisisthepy.python.multiplatform.bindings`. Applying it applies KSP, adds the processor to every
target's **main** KSP configuration (never a `…Test` one and never `kspCommonMainMetadata`, either of which
would emit a second fragment under the same name), infers the role (`application` /
`com.android.application` → `app`, else `library`; override with `pythonBindings { role.set("app") }`) and
derives `moduleName`. Notes from building it:

- **It does not compile against KSP's Gradle plugin.** `kotlin-dsl` builds against Gradle's embedded
  Kotlin (2.0.20 on Gradle 8.11.1) and KSP 2.3.11 is compiled with Kotlin 2.3 ("compiled with an
  incompatible version of Kotlin"). The dependency is `runtimeOnly` and the `ksp { arg(...) }` calls go
  through one reflective lookup of `arg(String, String)`.
- **Test configurations are matched as a camel-case word `Test`.** AGP puts the build type last
  (`kspAndroidTestDebug`), so an `endsWith("Test")` check once put the processor on a unit-test
  compilation, which emitted a duplicate fragment shadowing `main`'s. `ksp-fixtures/android` carries the
  Android plugin for this reason ([ROADMAP](../roadmap/ROADMAP.md) §13).

### 2.6 Tree shaking — decision: accept the cost (measured)

If the aggregator references every fragment and every fragment references every `public` callable, the
linker sees the entire public API as reachable. Each lambda `{ args -> greet(args[0] as String) }` is a
static reference to `greet`; a reachable fragment makes every target reachable. Function-level DCE
(`-ffunction-sections` + `--gc-sections`) cannot help, because the references are real. Three things
cannot all hold: (1) blacklist exposure, (2) a full table referenced at build time (required on
Kotlin/Native), (3) tree shaking of unused entries.

**Chosen: give up (3) — accept the cost**, because (1) is settled (§1.5) and (2) is forced by §2.3. The
remaining options, one line each:

| Option | Verdict |
|---|---|
| B. Finer fragments (per file/class) | Does not help alone: `FunctionTable` still references every fragment unconditionally; `by lazy` still captures the reference. (Per-file fragments are still the lever for incremental KSP, §2.7.) |
| C. Opt-in manifest (`python_bindings.txt`) | A different product: contradicts the blacklist and the "write Kotlin, import from Python" promise |
| D. `ServiceLoader` on JVM, aggregator on Native | A platform split for no gain: the JVM does not tree-shake anyway |
| E. Two-tier table (skeleton + lazily initialised body) | Saves runtime lambda objects, **not binary size**: the `when` arms still statically reference every target on Kotlin/Native |

**Measured.** `ksp-fixtures/library` got 200 synthetic top-level functions (`fun bulkN(x: Long): Long = x + N`);
`ksp-fixtures/app`'s `androidNativeArm64` debug test binary was linked before and after
(`linkDebugTestAndroidNativeArm64`) and stripped with the NDK's `aarch64-linux-android-strip`:

| | bytes | delta |
|---|---|---|
| baseline (unstripped) | 7,578,480 | |
| +200 entries (unstripped) | 8,343,184 | 764,704 (≈3.8 KB/entry) |
| baseline (stripped) | 3,264,056 | |
| +200 entries (stripped) | 3,632,696 | 368,640 (**≈1.84 KB/entry**) |

This is a **floor**: each function is a one-line expression, so almost all of the 1.84 KB is the
`ExposedCallable`, its lambda, its name string and Kotlin/Native per-function metadata. A real library adds
its function bodies on top. For 200 entries the floor is ≈11% of a 3.26 MB stripped baseline — noticeable,
not disqualifying. Whether that is acceptable for a real library is a product judgement; the only real
mitigation would be changing the exposure model, which is settled.

### 2.7 Incremental KSP — measured, `ALL_FILES` stays

`Dependencies(false)` would never regenerate the aggregator and miss a newly added fragment;
`Dependencies.ALL_FILES` is correct but reprocesses on every change. A narrower declaration buys nothing.
Measured with `ksp.incremental.log=true` on `:ksp-fixtures:app` (two files), modifying one:

| aggregator `Dependencies` | dirty / all |
|---|---|
| `ALL_FILES` | 100.00% |
| `Dependencies(aggregating = true, <generated fragment files>)` | 100.00% |

A module's own `Fragment_<module>` is an *aggregating* output over every source file — correctly, since under
blacklist exposure any file can add an entry — so any change regenerates it and KSP marks every source that
maps to it dirty. A classpath-only change behaves the same (adding a function to `:ksp-fixtures:library`
dirtied both app files with `CP changes` listing `Fragment_ksp_fixture_library`). The only route to real
incrementality is **per-file fragments**, which changes fragment naming, `UpcallTable`'s per-module
idempotency and where duplicate names are detected — a design change, not done.

### 2.8 Alternatives rejected

| Alternative | Why not |
|---|---|
| `@EagerInitialization` | Deprecated and slated for removal; does not answer who calls `register()`; defeats tree shaking by definition |
| Resource files under `META-INF/` | Not needed while `getDeclarationsFromPackage` works; more complex (classpath I/O) and less type-safe. Kept as the fallback behind `FragmentDiscovery` |
| Manual `FunctionTable.register(Fragment_x)` in `main()` | Contradicts "apply the plugin and nothing else"; every new dependency needs a call |
| A compiler (IR) plugin | Harder to write and maintain, less stable API, and KSP suffices |

### 2.9 What is still not verified for generation

- **Transitive dependencies** (app → libA → libB with a fragment in libB): should work because transitive
  classes are on the compilation classpath. (unverified)
- **Published Maven artifacts** consumed as `implementation("g:a:v")` rather than `project(...)`: should
  work the same way. (unverified)

---

## 3. Runtime path

### 3.1 From install to call

```
Kotlin, once:   UpcallBootstrap.publishToGlobals()          publishes _pm_resolve / _pm_invoke / _pm_bind* / _pm_release / _pm_cancel
                installGeneratedUpcallTable()               -> FunctionTable.installInto() -> UpcallTable.install(fragments)
                PythonProxySource.install()                 renders Python from UpcallTable + ClassLookup, exec's it
Python:         from demo.calc import Counter               sys.modules hit -- no import hook
                c = Counter(10)                             proxy __init__ -> _pm_invoke(handle, (10,)) -> UpcallTrampoline.invoke
                c.increment(5)                              _pm_invoke(method_handle, (c._pm_handle, 5))
```

(`*` `_pm_bind` is published by the `PyMethodDef` bootstraps, not by desktop's.) Names are resolved to
handles when the generated module is executed; the calls afterwards carry only handles (§1.2).
`UpcallBootstrap` is the consumer-facing route (`commonMain/.../ffi/upcall/UpcallBootstrap.kt`); calling it
twice is safe (`UpcallBootstrapTest`). The published names live in `__main__` and do not survive
`Py_Finalize`; the addresses behind them are process-lifetime, so a caller that re-initialises must publish
again (inferred from code; not covered by a test because the suite shares one interpreter).

### 3.2 The trampoline and its one carrier shape

`python.multiplatform.ffi.upcall.UpcallTrampoline` (`commonMain`) depends on nothing platform-specific.
Argument passing needs exactly one C shape:

    PyObject *pm_invoke(long callableHandle, PyObject *args)       // (long, long) -> long

Arity and types travel inside the tuple and the table entry, never in the C signature. A stub specialised
per signature would need one per `(arity, tag-vector)` — unbounded and impossible to pre-generate for a
closed world. The remaining slots were already in the downcall vocabulary:

| Slot | C signature | Carrier shape | |
|---|---|---|---|
| `PyCFunction` | `PyObject *(PyObject *self, PyObject *args)` | `(long, long) -> long` | the argument-passing shape |
| `getter` | `PyObject *(PyObject *self, void *closure)` | `(long, long) -> long` | same one |
| `initproc` / `setter` / `traverse` | `int(long, long, long)` | `(long, long, long) -> int` | pre-existing |
| `inquiry` (`tp_clear`) | `int(PyObject *self)` | `(long) -> int` | pre-existing |
| `destructor` | `void(PyObject *self)` | `(long) -> void` | pre-existing |
| name → handle | `long(const char *)` | `(long) -> long` | pre-existing |
| release / cancel | `int(long)` | `(long) -> int` | pre-existing (`_pm_release`, `_pm_cancel`) |

`newfunc` is unaccounted for only if `tp_new` is ever bound directly; it is not — construction goes
through a `CONSTRUCTOR` entry called from Python (§4.2).

### 3.3 What crosses, per tag

The `Array<Any?>` an entry receives has one representation per `TypeTag`, which the generated cast
(`TypeShape.castExpression`) narrows from:

| `TypeTag` | Python | Kotlin |
|---|---|---|
| `INT` | `int` | `Long` (a declared `Int`/`Short`/`Byte` is narrowed by generated code) |
| `FLOAT` | `float` | `Double` |
| `BOOLEAN` | `bool` | `Boolean` — a distinct Python type, not folded into `INT` |
| `STRING` | `str` | `String` |
| `BYTES` | `bytes` | `ByteArray` (NUL bytes survive; SPEC U-2) |
| `UNIT` | `None` | `Unit` |
| `OBJECT` | `int` handle, **or** any Python object | the Kotlin instance from `HandleTable`, **or** a `PyObject` |
| — | `None` | `null`, whatever the tag |

`OBJECT` is resolved by what Python actually sent: Python cannot hold a Kotlin reference, so a Kotlin
object crosses as an `ObjectReference` integer (`(generation shl 32) | slot`, generation ≥ 1); anything
else is a Python object and reaches a parameter declared `PyObject`.

`BYTES` is correct and slow — one item at a time — because `PyBytes_AsString` is bound as a NUL-terminated
string read and `PyBytes_AsStringAndSize` is in no platform's `EmbedAPI` yet (`UpcallTrampoline.toByteArray`
KDoc). When it is, both directions collapse to one call.

### 3.4 Conventions that are not negotiable

- **Arguments are borrowed.** `PyTuple_GetItem` lends, so a `PyObject` built over one takes
  `borrowed = true`. `borrowed = false` gives back a reference nobody took — one per call — and the free
  lands somewhere unrelated. That bug crashed this repository twice.
- **The result is a new reference**; Python takes ownership. Measured end to end through each platform's
  real entry point: a hundred calls move the returned object's refcount by zero
  (`commonTest/.../native/ffi/UpcallEntryTest.kt`,
  `theReferenceReturnedToPythonIsTakenOverExactlyOnce`).
- **Nothing is thrown out of a trampoline.** The return path is C; a Kotlin exception crossing a Panama
  upcall stub terminates the VM, on Kotlin/Native the process. Every failure leaves as `NULL` with the error
  indicator set; a Kotlin exception becomes a Python exception and `Unit` becomes `None` (SPEC U-2,
  `UpcallTrampolineTest`).

### 3.5 The GIL is not the caller's to promise

`withGIL` skips `PyGILState_Ensure` when the thread's nesting depth is non-zero. That is right for Kotlin
code and **wrong for an entry from C**, because C may have released the GIL inside a scope that is still
open. `ctypes.CFUNCTYPE` does exactly that (unlike `PYFUNCTYPE`); the first version of the trampoline
segfaulted in `_PyThreadState_GET` (`PyErr_Occurred+0x1c`) on a thread whose own counter said it held the
GIL. Every trampoline entry (`invoke`, `releaseObject`, `cancelCall`) therefore takes its own
`PyGILState_Ensure`/`Release` pair **unconditionally** (`UpcallTrampoline.attached`), on every platform.

On Android `PyGILState_Ensure` is bound as **ordinary JNI** (`PyGILState_EnsureN`): a `@FastNative` or
`@CriticalNative` binding would let a thread blocked on the GIL stall the ART collector and deadlock the two
runtimes the moment upcalls exist ([`androidMain/README.md`](../../python-multiplatform/src/androidMain/README.md)).

### 3.6 Handles and their lifetimes

- `CallableHandle` — table index + epoch (§1.2). `-1` is `NONE`; a valid handle is never negative.
- `HandleTable` / `ObjectReference` — a Kotlin object given to Python is rooted in `HandleTable` under a
  generation-tagged handle; releases are generational, so a second release is a no-op and a stale handle
  never frees a reused slot (SPEC M-4, `HandleTableTest`, `ProxyHandleLifetimeTest`,
  `OwnedResultLifetimeTest`). Generated proxies give their handle back in `__del__` (§4.2).

### 3.7 Per-platform entry points

Only the address-publishing step differs; the marshalling is shared.

| Platform | How Python reaches `UpcallTrampoline` | Names published |
|---|---|---|
| **Desktop** | FFM `Linker.upcallStub` (`UpcallStub.invokeWithArgsStubAddr`, `cancelCallStubAddr`, …) wrapped by `ctypes.CFUNCTYPE` in `UpcallBootstrap.desktop.kt` | `_pm_resolve`, `_pm_invoke`, `_pm_release`, `_pm_cancel` |
| **iOS / androidNative** | `python.native.ffi.UpcallEntry` (`nativeMain`): a `PyMethodDef` whose `ml_meth` is a `staticCFunction` over the trampoline, `self` carrying the handle; Kotlin/Native fills the struct, no C glue | all five |
| **Android (ART)** | C shims in `artMain/cinterop/jni_onload.def` (`pmp_upcall_invoke_meth`, `pmp_upcall_invoke_free_meth`, `pmp_upcall_resolve_meth`, …, `pmp_upcall_cancel_meth`) calling `python/native/ffi/UpcallCallbacks` via `CallStaticLongMethod` | all five |
| **wasmJs** | one `@WasmExport("pmp_invoke")` placed in CPython's `__indirect_function_table` with `Table.set`, five `PyCFunction`s over it told apart by an op code in `self` (§3.10) | all five |

Tests: `UpcallEntryTest` (`commonTest`, runs on every target through the per-platform `bindUpcallOrNull`),
`nativeTest/.../UpcallRawEntryPointTest.kt`, `androidInstrumentedTest/.../UpcallThreadAttachTest.kt`.

The `str`/`bytes` contract of `_pm_resolve`: the generated support code sends **bytes**, because desktop's
`ctypes.CFUNCTYPE(c_long, c_char_p)` refuses a `str`; every `PyMethodDef` bootstrap accepts both
(`PyUnicode_AsUTF8`, on failure `PyErr_Clear` then `PyBytes_AsString`). The generated module's own
name → handle helper is called `_pm_lookup` so it never overwrites a bootstrap's `_pm_bind`
(handle → callable). `PythonProxySourceTest` guards that the generated support defines none of the five
bootstrap names. How these were found: when `PythonProxyInstallTest` was first run outside desktop, iOS
failed 14/14 because `UpcallEntry.publish` did not publish `_pm_invoke`, then 11/11 on the `bytes`
argument; ART later failed 11/11 on the same `bytes` gap in its separate C shim
(`TypeError: bad argument type for built-in operation`, on `pmp_api26` and `pmp_api36`). The rule that
came out of it: the generator's assumptions about bootstraps are only proven by running
`PythonProxyInstallTest` on each target.

### 3.8 Android: the boundary runs the other way, and threads attach once

`RegisterNatives` binds a JVM `external fun` to a C function — the **downcall** direction. A
`PyMethodDef`'s `ml_meth` must be a real C function pointer and Kotlin/JVM on ART can produce none. So the
entry points are C functions in `jni_onload.def`, and *they* call Kotlin with `CallStaticLongMethod` against
`UpcallCallbacks`, a class looked up once in `JNI_OnLoad` and held as a global ref. `RegisterNatives` appears
only for the one cold `upcallPublish(long)`. This is the same inversion the cycle-collecting proxy type uses
for `tp_traverse`/`tp_clear` (`ProxyCallbacks`), down to reusing `pmp_attach`.

**A Python worker thread is a bare pthread ART has never seen**, so `GetEnv` fails and the shim must
`AttachCurrentThreadAsDaemon` (SPEC C-5).
`UpcallThreadAttachTest.anUpcallArrivesOnAThreadCPythonCreatedRatherThanFailingToFindTheJvm` upcalls from a
`threading.Thread` and asserts it did not land on the instrumentation thread.

**Attach once per thread, detach at thread death.** Attaching and detaching per call was the most expensive
thing on the Android upcall path (§7.6). The belief that forced it — "ART aborts if a thread exits without
detaching" — is false: `Thread::ThreadExitCallback` (`runtime/thread.cc`, same shape in `android-8.0.0_r1`
and `main`) warns on its first invocation and only reaches `LOG(FATAL)` on a second, which only an `#else`
branch Android does not compile could arm; bionic clears a key's value before its destructor and never
re-reads it. `UpcallThreadAttachTest.aThreadThatExitsWithoutDetachingLeaksItsPeerRatherThanAbortingArt`
lets an attached pthread exit undetached on `pmp_api26` and `pmp_api36` with no abort and no logcat warning.
**The detach is still mandatory**, for a different reason: an attachment never given back leaves its
`java.lang.Thread` in the thread list as a GC root. So the detach happens at thread death, in a
`pthread_key_create` destructor (`pmp_thread_exit_detach`) that defers to its second invocation so it cannot
run before ART's own exit callback.

**Holding attachments is not a new risk.** Peak simultaneous attachments do not change — under the per-call
scheme every worker upcalling at an instant is attached at that instant; the ceiling is the number of live
Python threads either way. What changes is that release must be reliable:
`manyConcurrentWorkersAreEachAttachedOnceAndAllReleased` holds 32 workers on a `threading.Barrier`, checks
each sees one ART thread and no two share one, and requires every peer reclaimable afterwards (green on both
emulators). The instrument reads GC reachability of the peer, not `ThreadGroup.enumerate`, which on API 26
does not report an attached native thread's peer at all; a positive control (peer unreclaimable while its
worker is parked mid-upcall) is kept in the test because it is what caught that.

The C shims take no GIL — CPython holds it when calling a `PyCFunction` — and the trampoline takes its own
pair (§3.5).

### 3.9 iOS and androidNative: the `@CName` + `ctypes.CDLL(None)` route, measured

The original design said Python would reach `@CName` symbols via `ctypes.CDLL(None)` because both live in one
binary. On iOS **neither half holds**:

| Binary | `pm_upcall_invoke` / `pm_upcall_resolve` / `pm_upcall_release_object` |
|---|---|
| androidNative `libmultiplatform_python3.14.so` | **exported** (`T` in `nm -D`) |
| iOS `PythonMultiplatform.framework` | **absent** — a K/N framework exports the Objective-C surface plus the Konan runtime |
| Kotlin/Native test executable, either target | **absent**, also from per-file caches — never emitted |

and this project's iOS `Python.framework` binary has no `_ctypes`, so `import ctypes` raises
`ModuleNotFoundError` (Android's CPython ships `_ctypes.so`). The `@CName` functions
(`pm_upcall_invoke`, `pm_upcall_resolve`, `pm_upcall_release_object`, `pm_upcall_cancel_call`) are kept for a
C host and for `ctypes` on Android; `UpcallEntry.invokeAddress` publishes the same addresses without the
link (`UpcallRawEntryPointTest.theRawEntryPointsAreCallableCFunctionsOfTheDocumentedShape`). The real route is
the `PyMethodDef` of §3.7.

### 3.10 wasmJs: one export, five names

`@WasmExport` is valid only in the compilation that produces the `.wasm`, so the embedding application
declares `@WasmExport("pmp_invoke") fun pmpInvoke(selfPtr: Int, argsPtr: Int): Int =
UpcallEntry.invokeMethod(selfPtr, argsPtr)`; this repository reproduces that file in
`wasmJsTest/.../UpcallExports.kt` so the suite takes the application's path. `wasmJsMain`'s `UpcallEntry`
puts the export in CPython's table (`pmpRegisterUpcall`) and builds `PyCFunction_NewEx(def, self, NULL)`
objects over it.

A `PyCFunction` carries `self` as well as the function pointer, so one export already backs any number of
distinct callables. Putting a value no handle can take in `self` turns it into a dispatcher — **no second
export, and no op argument**:

| `self` | Name | Shape |
|---|---|---|
| `-2` | `_pm_resolve` | `(name: str \| bytes) -> handle`, `-1` if none |
| `-3` | `_pm_invoke` | `(handle, args_tuple) -> result` |
| `-4` | `_pm_bind` | `(handle) -> callable` |
| `-5` | `_pm_release` | `(handle) -> int` |
| `-6` | `_pm_cancel` | `(handle) -> int` |
| other | bound callable | `UpcallTrampoline.invoke(self, args)` |

Negative ops are safe because `CallableHandle.isValid` is `raw >= 0` and `ObjectReference`'s generation is
≥ 1; `-1` is not an op because it is `CallableHandle.NONE` and must still reach the trampoline's own refusal.
The application's file and `verifyWasmAbiSignatures` (317 `@WasmImport` + 3 glue) are unchanged by it.
Removing the `UpcallEntry.publish` call makes `PythonProxyInstallTest` fail 11/11 with the generated module's
guard ("the raw upcall entry points are not bound …"), so the passing run depends on the bootstrap.

The bare mechanism (`call_indirect`, no arguments) was measured at 3.1 ns in the former standalone
`wasm-experiment` (against 10.9 ns for `addFunction` around a JS closure); that experiment is now
`wasmJsTest/.../WasmUpcallRouteOverheadTest.kt`, measured from Python as in production. **3.1 ns must not be
quoted for an upcall that carries arguments** — that is ~300 ns (§7.2).

---

## 4. Generated Python proxies

### 4.1 Rendered at run time, not shipped as a `.py`

`PythonProxySource.render` (`commonMain/.../ffi/upcall/PythonProxySource.kt`) renders Python from the
installed table; `install()` executes it. Not a KSP-emitted `.py` resource, because:

1. **There is no resource path that works everywhere.** Kotlin/Native has no `getResourceAsStream`.
   `PythonPayload` now puts a directory on `sys.path` on desktop (jar resource) and Android (APK asset), but
   iOS, androidNative and wasm have no packaging step, so a shipped file would exist on two of five.
2. **KSP does not know what will be installed.** The table is assembled at run time and an application may
   install a subset; build-time Python would describe a table that may not exist.
3. **`ExposedCallable` already carries everything** (`kind`, `arity`, `isSuspend`, names); a second
   generator could disagree with the first.

`render` is a pure function from entries to source text, pinned without an interpreter by
`PythonProxySourceTest`. Nothing here reintroduces reflection, so the native-image argument (§1.1) holds.

### 4.2 Shapes

- **Module functions** — published into `sys.modules` under the Kotlin package (`_pm_module`), so
  `from demo.calc import doubleLater` works with no import hook (CPython checks `sys.modules` before any
  finder). Each is wrapped by `python_multiplatform.kotlin_function` (§4.3).
- **Classes** — a plain Python `class` whose `__init__` calls the `CONSTRUCTOR` entry and stores the handle in
  `self._pm_handle`; methods and properties pass it as `args[0]`. A `private set` property refuses
  assignment. Interfaces and abstract classes have no constructor.
- **Handle release** — `__del__` (`tp_finalize`, run by `tp_dealloc`) calls `_pm_release`, looked up once at
  class-definition time and carried as a default argument (globals are `None` during finalisation). Without
  it, `GeneratedProxyCostTest`'s constructor row ended at 78 002 live handles. Alternatives measured on CPython
  3.13, construct + destruct net of a plain object: `__del__` +66 ns; per-instance holder +158 ns;
  `weakref.ref` registry +260 ns; `weakref.finalize` +555 ns. The accepted weakness: a subclass that defines
  `__del__` without calling `super().__del__()` leaks its handle (measured). `ProxyHandleLifetimeTest` pins
  release, including through a cycle.
- **GC base** — where `ProxyTypeFactory.installGcBase()` succeeds it publishes the cycle-collecting
  `PyType_FromSpec` type as `_pm_proxy_base`, and generated classes subclass it through a `PyMemberDef` named
  `_pm_handle`, so `self._pm_handle = …` writes into the C slot `tp_traverse` reads (§6). Otherwise they fall
  back to `_PmObject`. Every platform's `actual` now implements `installGcBase` (code read; the `expect`'s
  KDoc saying Android and wasm return `false` is stale).
- **Class statics** (companion / `object` properties, enum entries) — a descriptor on a **metaclass** rendered
  beside the class, so instances cannot see them (Kotlin's rule). **Companion functions** go on the metaclass
  too: a `staticmethod` in the class body would be reachable through an instance, and an accessor pair would
  disagree with the Kotlin declaration.
- **Top-level `val`/`var`** — a `property` (a *data* descriptor) on a `ModuleType` subclass generated **per
  module** (one shared type would answer the attribute on every module). The previous shape, a
  `__getattr__`/`__setattr__` pair on one shared subclass, cost ~50× more per read because `__getattr__` is
  the fallback hook: `module_getattro` first raises a fully formatted `AttributeError` that the hook discards
  (§7.7).
- **`suspend` functions** — `async def` wrappers that await the result only if it is awaitable:

      async def _pm_f_0(a0):
          _pm_r = _pm_invoke(_pm_h_0, (a0,))
          if hasattr(_pm_r, '__await__'):
              return await _pm_r
          return _pm_r

### 4.3 Kotlin-named surface

`KotlinSurface` (`commonMain/.../ffi/upcall/KotlinSurface.kt`) installs `python_multiplatform`, the binder's
root module, shared by both installers (`PythonProxySource` and the binding layer `PythonxAdapter`, which
lives at `python_multiplatform.binding`). Its KDoc is the contract (SPEC U-8):

- keyword arguments by **Kotlin parameter names**; an extension receiver is positional-only;
- a parameter with a Kotlin default has `default is python_multiplatform.KOTLIN_DEFAULT`; leaving it out (or
  passing that object) means Kotlin's own default;
- a required parameter after a defaulted one is keyword-only;
- annotations are the declared Kotlin type names; synthetic `$composer`/`$changed`/`$default` never appear;
- an overload set answers `(*args, **kwargs)`;
- `inspect.signature(fn)` (built lazily via `__wrapped__`) and `python_multiplatform.describe(fn)` give the
  metadata. The answer is the same whichever installer ran first (stamped epochs).

No namespace is renamed and the binder creates no `pythonx` module; a real `pythonx` package on
disk is what `import pythonx` loads. Members and keywords also answer to their Pythonic aliases, and the
signature shows the Pythonic keyword (SPEC U-12). Tests: `desktopTest/.../pythonx/KotlinNamedSurfaceTest.kt`,
`ksp-fixtures/compose/.../KotlinSignatureMetadataTest.kt`. `.pyi` stubs are generated by the Gradle plugin
under Kotlin module paths only (SPEC B-7).

### 4.4 Inheritance

A generated proxy is an ordinary Python class, so Python can subclass it and override methods; instances of
such subclasses keep their handle and are collected through cycles (SPEC M-3; e.g.
`desktopTest/.../ref/CycleCollectionTest.kt` `aPythonSubclassOfTheProxyTypeSurvivesBeingUntrackedTwice`).
**A Python override is not visible to Kotlin callers**: nothing dispatches a Kotlin virtual call to a Python
method (no test asserts it, and the table has no such path). The original design's "Kotlin calls the
override through `PyObject_CallMethod`, no new stubs" is therefore not implemented.

### 4.5 Where proxies run

`PythonProxyInstallTest` lives in `commonTest`. Each test target declares `publishesProxyEntryPoints` and
`proxyBootstrapSupportsAsyncio` as constants (`commonTest/.../ffi/upcall/ProxyBootstrap.kt` and its
`actual`s) instead of probing `globals()` at run time, so a target that stops publishing fails loudly instead
of silently taking the other branch.

| Target | `publishesProxyEntryPoints` | `proxyBootstrapSupportsAsyncio` |
|---|---|---|
| desktop | true | true |
| iOS / androidNative (`nativeTest`) | true | true |
| Android ART | true | true |
| wasmJs | true | **false** (§5.8) |

Threaded delivery tests are split by how they create a thread: `desktopTest/.../PythonProxyDeliveryTest.kt`
(`java.lang.Thread`) and `nativeTest/.../PythonProxyNativeDeliveryTest.kt` (`pthread_create`).

---

## 5. `suspend` functions (async upcalls)

SPEC U-5: `await g.greetNow(1)` works; a function that never suspends completes without a Future; cancelling
the Python future cancels the Kotlin coroutine. Implemented on desktop and Kotlin/Native; on ART the sample
demonstrates it (§5.8); `planned` on wasm.

### 5.1 Why a suspend function cannot simply be called

CPython calls a C function pointer whose frame must return a `PyObject *` synchronously; a suspension has
nothing to return. `runBlocking` is not a solution: it blocks the thread the upcall arrived on — on Android a
CPython-created pthread — so the interpreter waiting for the answer is the thing being blocked. The only
option: **return something else now and deliver the value later.**

### 5.2 Three candidates; (C) chosen, with (B) as its mechanism

All three share the Kotlin half — start the coroutine inside the synchronous frame and park the result where
it can be addressed later.

| Candidate | Verdict |
|---|---|
| **(A) handle + polling** (`is_done(h)` / `result(h)`) | **Rejected.** On wasmJs Python and Kotlin share one JS thread and resumption only happens when control returns to the host, so a polling loop that never yields prevents its own progress (inferred). Also burns a core and re-takes the GIL at every switch interval, and KSP cannot expose a generic `Deferred<T>` |
| **(B) callback** — Python passes a callable, Kotlin calls it on completion | Works: a Kotlin-created thread can take the GIL and call Python while the main thread is inside `run_until_complete` (measured, `AsyncCompletionProbeTest.aKotlinCreatedThreadCanResolveAnAsyncioFutureTheInterpreterIsWaitingOn`, which passes only after observing `loop.is_running()`). Hands all synchronisation to the user |
| **(C) asyncio** — Kotlin creates a `Future` on the running loop and resolves it with `loop.call_soon_threadsafe` | **Chosen.** Needs no new binding: `PyImport_ImportModule`, `PyObject_GetAttrString` and `PyObject_Call*` are enough (measured, `AsyncCompletionProbeTest.everyApiThisNeedsIsAlreadyBoundSoAnAsyncioConventionAddsNoNewBinding`). `await kotlin_fn(x)` is syntax Python users already know |

(C) is mechanically (B): `call_soon_threadsafe` is a downcall from the completion thread. `Py_AddPendingCall`
(in the Stable ABI, not bound in `EmbedAPI`) was not needed. The cost of (C): the application must be written
async — the loop must be running.

**The fast path comes before the convention.** `suspend` is a signature, not a promise to suspend: a body
that reaches no suspension point is complete before `PendingCall.start` returns
(`PendingCallTest.aBlockThatNeverSuspendsIsAlreadyCompleteBeforeStartReturns`), and its value is returned
directly with no `Future` and no event loop.

What would reverse this choice: if a target's loop could not be made to yield to its host, (B) would be the
better public surface there; if a completion thread on Android were found to need an ART attach, the
`pmp_thread_exit_detach` destructor (§3.8) would have to be replicated on the completion side. (A Kotlin/JVM
thread is already known to ART, so no attach is expected — inferred; the ART sample in §5.8 resumes from a
`java.lang.Thread` and works.)

### 5.3 KSP: a different body, not an exclusion

`BindingPolicy.isSuspending` selects a body shape:

    { args -> fixture.library.blockingTopLevel(args[0] as Long) }                                        // ordinary
    { args -> python.multiplatform.ffi.upcall.PendingCall.start { fixture.library.suspendingTopLevel(args[0] as Long) } }   // suspend

The braces are a `suspend` lambda; it compiles with the stdlib only. `kind` is unchanged (a suspending
member is still `METHOD`, receiver in `args[0]`) and `returnType` is the **declared** return type, so the
fast path marshals exactly like a synchronous entry. `ExposedCallable.isSuspend` is a flag rather than
`SUSPEND_*` variants of `CallableKind` because the axes are orthogonal: `kind` says where the arguments are,
`isSuspend` what comes back. Pinned by `ksp-fixtures/app/.../GeneratedSuspendTest.kt` against
`ksp-fixtures/library/.../Suspending.kt`, which pairs every suspending declaration (top-level, member,
companion, interface, object) with a non-suspending control in the same scope.

A `suspend` **function type** (`val h: suspend (Long) -> Long`, a parameter or return of that type, nullable,
behind a `typealias`, or nested in a generic argument) is **not exposed** and KSP **warns**
(`FragmentScanner.warnUnexposableType`); `GeneratedSuspendTest` asserts each rejection. (An earlier state
exposed it as an opaque `OBJECT` handle no entry could call. The `BindingPolicy.isSuspending` KDoc still says
that; it is stale.)

### 5.4 `PendingCall`

`commonMain/.../ffi/upcall/PendingCall.kt`, pinned by `PendingCallTest`:

- **No `kotlinx.coroutines` dependency.** `startCoroutine` and `Continuation` are stdlib; the dispatcher a
  user's `suspend fun` needs is already on the user's classpath.
- **The context is the `PendingCall` itself** (a `CoroutineContext.Element`), with no dispatcher, so the body
  runs on the calling thread until its first real suspension — which is where the fast path comes from, and
  why work before the first suspension runs **inside the C frame, holding the GIL**.
- **Nothing escapes**: body and listener failures are parked, as in the trampoline.
- **No new handle machinery**: it is an ordinary object in `HandleTable`.
- **Unsynchronised**, on the boundary's rule that every mutation happens on a GIL-holding thread; a
  completion that reaches Python takes the GIL anyway.

### 5.5 Delivery

`AsyncUpcall.deliver` runs on `UpcallTrampoline.invoke`'s `entry.isSuspend` branch:

| Case | Returned | Cost |
|---|---|---|
| already complete, success | the real value (`marshalResult`) | not even `import asyncio` |
| already complete, failure | `NULL` + error indicator | identical to a synchronous failure |
| really suspended | `asyncio.Future` (new reference) | `get_running_loop` + `create_future` |

On completion: `withGIL { loop.call_soon_threadsafe(_pm_settle, future, ok, payload) }`. `withGIL`, not the
trampoline's unconditional pair, because this is not a C entry: a fresh completion thread has depth 0 and
really ensures; a completion inside a later upcall correctly nests. The Kotlin wrapper and Python each hold a
reference to the `Future`; the completion lambda keeps the wrapper alive until it fires.

**The fast path is wider than "did not suspend".** If the completion thread resumes the continuation while the
upcall frame is still running — `CFUNCTYPE` releases the GIL for the upcall itself — `deliver` sees `isDone`
and returns the value. Correct, but the synchronous-completion ratio is a function of the race, and a test of
the slow path must observe that a `Future` actually crossed (`AsyncUpcallDeliveryTest` records
`type(r).__name__`; the proxy tests count `create_future` calls instead and assert `0` on the fast path —
`PythonProxyInstallTest.theSameGeneratedProxyBuildsNoFutureWhenTheKotlinBodyNeverSuspends`).

**No running loop:** a call that really suspends fails with `RuntimeError: no running event loop`, and the
coroutine has already started (whether it suspends is only known by starting it); its resumption is dropped
(`AsyncUpcallDeliveryTest.aSuspendingEntryThatSuspendsWithNoRunningLoopFailsInsteadOfReturningSomethingUnusable`).
A suspending entry that never suspends works with no loop at all
(`aSuspendingEntryThatNeverSuspendsHandsBackTheRealValueAndBuildsNoFuture`).

### 5.6 Cancellation

**A completion landing on a cancelled `Future` is dropped, not raised.** Measured before the fix
(`AsyncUpcallCancellationTest`): `await` got `CancelledError`; the loop's `call_exception_handler` got one
`InvalidStateError: invalid state`; the completion thread's error indicator was clean and the next unrelated
upcall worked — no contamination, just an unactionable log entry. Two guards, only the second a guarantee:
`AsyncUpcall.resolve` checks `done()` before scheduling (cheap, racy), and the scheduled callback is
`_pm_settle`, which checks `done()` **again on the loop thread**. Removing only `_pm_settle`'s guard turns
exactly the race test red; the race is forced, not left to timing (the Python coroutine spins without
awaiting while Kotlin schedules, then cancels).

**Kotlin-side cancellation is cooperative, by construction.** The continuation `startCoroutine` receives is
the coroutine's *completion*; the suspension point's continuation belongs to whoever suspended (the user's
`suspendCoroutine`, their dispatcher, `kotlinx.coroutines`). `PendingCall` never sees it, the stdlib cannot
reach it, and resuming twice is undefined. Forced cancellation is what a `Job` tree does. What works:
`ensureActive()` (top-level, reads `coroutineContext[PendingCall]`) throws `CancellationException` once the
call is cancelled — the same contract as `kotlinx.coroutines`, where a body that never checks is never
cancelled either. The element is not a `ContinuationInterceptor`, so the fast path is untouched.

    suspend fun slowSum(n: Long): Long {
        var total = 0L
        for (i in 0 until n) { ensureActive(); total += step(i) }
        return total
    }

**Early notification.** Python's `fut.cancel()` reaches Kotlin before completion:

    fut.cancel() -> done callback (call_soon) -> _pm_cancel(handle)          (long) -> int
      -> UpcallTrampoline.cancelCall (unconditional PyGILState_Ensure)
      -> HandleTable.resolveRaw -> PendingCall.cancel() -> next ensureActive() throws

`_pm_cancel` has `_pm_release`'s shape (no new stub shape). The `PendingCall` handle is captured as a default
argument of the done callback rather than set as a `Future` attribute. `AsyncUpcall.armCancellationNotice`
registers the handle only if `_pm_cancel` and `_pm_release` exist in `__main__`; a host without them keeps
completion-time observation and leaks nothing (a `NameError` inside a loop callback would be another
unactionable log). `AsyncUpcallEarlyCancellationTest` was red first, with the old behaviour as its message
("Kotlin did not learn about the cancellation until the call completed"); it asserts
`isCancelled && !isDone` on a call never resumed since cancellation (the old code also set the flag, but only
at completion), then resumes once and requires `CancellationException` and `isDone`.

**Two releasers by design.** `_pm_watch`'s done callback calls `_pm_cancel` if cancelled and always
`_pm_release`; `AsyncUpcall.resolve` releases too, in a `finally`. They cover different holes:

| Releaser | Only it covers |
|---|---|
| Python done callback | a cancelled body that never cooperates — the Kotlin completion never runs |
| Kotlin `resolve` | the loop stopping before the scheduled done callback ran |

Generational release makes the second a no-op and a late `_pm_cancel(stale)` harmless. Measured with
`HandleTable.liveCount` (`AsyncUpcallEarlyCancellationTest`):

| Case | Live handles |
|---|---|
| while a really-suspended call is pending | baseline + 1 |
| after cancel, coroutine not yet finished | baseline (Python released it) |
| after cancel and body exit | baseline |
| after normal completion | baseline |
| fast path | baseline — nothing registered (`theFastPathRegistersNoHandleAtAll`) |

Registration happens after `deliver`'s `isDone` early return, so the fast path touches neither `HandleTable`,
`__main__` nor `add_done_callback`. The slow path pays two `__main__` lookups and one `_pm_watch` call. Not
reclaimed: a call whose `Future` never settles and whose coroutine never ends — that has leaked its
continuation already.

### 5.7 Desktop and Kotlin/Native tests

The desktop tests use `java.lang.Thread` and `java.util.concurrent`, so they cannot move to `commonTest`; the
claims are re-verified on Kotlin/Native with `nativeTest/.../NativeThread.kt`, a thin `pthread_create`
wrapper. Not `Worker`: the question is whether a completion may come from a thread the Kotlin/Native runtime
never attached itself, which `Worker` would sidestep — the same situation
`CycleCollectionTest.testDeallocOnAThreadCPythonCreated` measures for `tp_dealloc`.

| `nativeTest` | Desktop counterpart | Re-verifies |
|---|---|---|
| `AsyncUpcallNativeDeliveryTest` (2 tests) | `AsyncUpcallDeliveryTest` | delivery from a foreign thread while the loop runs; fast path takes no asyncio |
| `AsyncUpcallNativeEarlyCancellationTest` | `AsyncUpcallEarlyCancellationTest` | `_pm_cancel` reaches `ensureActive()` before completion |
| `AsyncUpcallNativeCancellationTest` | `AsyncUpcallCancellationTest` | a completion on a cancelled `Future` is dropped; no error indicator leaks |

All passed on the iOS simulator, three repeated runs. They are in `nativeTest`, which
`androidNativeArm64Test` also runs, and they predate the androidNative full-suite runs recorded in §5.8
(316 tests, 0 failures) — but no per-test androidNative result for them was recorded (unverified).
`AsyncUpcallPortabilityTest` (`commonTest`) keeps only the two paths that return before touching asyncio — fast
path and failure-before-suspend — which need no thread and run on all targets.

### 5.8 Per platform

**Desktop** — everything above (`AsyncUpcallDeliveryTest`, `AsyncUpcallCancellationTest`,
`AsyncUpcallEarlyCancellationTest`, `PythonProxyDeliveryTest`).

**iOS simulator** — asyncio is available. The iOS `Python.framework` binary has no `lib-dynload`, but asyncio
comes from the separately extracted BeeWare stdlib (`build/python-stdlib/ios-simulator/lib/python3.14/` with
`asyncio/` and `lib-dynload/_asyncio…so`, `select…so`, `_socket…so`).
`iosSimulatorArm64Test/.../AsyncioAvailabilityProbeTest.kt` (isolated in its own file) runs
`asyncio.new_event_loop().run_until_complete(...)` and passes. Delivery, early cancellation and the silent drop
pass (§5.7).

**Android (ART)** — proxies, including `async` ones, run on `pmp_api26` and `pmp_api36`
(`connectedDebugAndroidTest`: 336 tests, 0 failures on each; `PythonProxyInstallTest`'s 11 on the real path).
The sample app on both emulators showed:

| Section | Output |
|---|---|
| 3 — upcall | `handle 4294967327 -> 0 · with args -> presses x3 = 0` (called from Python via `_pm_resolve`/`_pm_invoke`) |
| 5 — class proxy | `installed over PyMethodDef via JNI: 466 lines, 2 proxy classes`, `Greeter('Kotlin').greet(2) -> hello Kotlin! hello Kotlin!`, `g.greetings = 99 -> AttributeError (private set held)` |
| 6 — companion | `Greeter.forget() -> 100, then built=0`, `Greeter('x').built -> AttributeError (companion is class-only)` |
| 7 — fast path | `await g.greetNow(1) -> hello fast path!`, `Futures created -> 0` |
| 7 — really suspends | `await g.greetLater(2) -> hello slow path! hello slow path!`, `Futures created -> 1`, `raised -> None`, `completer thread -> clean` |

The last row resumes the parked continuation from a `java.lang.Thread`, as on desktop. (The original record
adds that `pmp_attach` runs on that path too; a `java.lang.Thread` is already known to ART, so that is
(unverified).) The iOS sample cannot yet show that row (a Kotlin/Native completion thread needs a
`Worker` or memory-model decision in the sample; the library's `PythonProxyNativeDeliveryTest` does it with
`pthread_create`).

**wasmJs** — the synchronous proxy surface runs (10 of `PythonProxyInstallTest`'s 11); `await` does not yet:

1. *The trap is fixed.* `import selectors` / `import asyncio` used to kill the Node process with
   `RuntimeError: unreachable`. That was the third symptom: `select.poll().poll(0)` raised
   `SuspendError: trying to suspend without WebAssembly.promising`, which unwound CPython's C frames without
   `Py_END_ALLOW_THREADS`, so the outer `PyGILState_Release` hit
   `Fatal Python error: PyGILState_Release: thread state … must be current`, then `abort()`. Cause: Emscripten
   decides JSPI **at run time** (`if (WebAssembly.Suspending) …`) and wraps only `main` in
   `WebAssembly.promising`; this library never calls `main` (it issues `Py_Initialize` as an ordinary wasm
   call). The same `python.wasm` returns `[]` on Node 22 (no JSPI) and dies on Node 26 (JSPI on by default, no
   flag to disable it); Gradle's runner uses Node 26. `selectors` was the first victim because it *calls*
   `poll(0)` at import time; `asyncio` died only through it. **Fix:** `cpython.mjs`
   (`python-multiplatform/src/wasmJsMain/resources/`) deletes `WebAssembly.promising` and
   `WebAssembly.Suspending` before calling the Emscripten factory, putting Node 26 on the synchronous path —
   which is what this library needs, since every call arrives from Kotlin as a synchronous wasm call. A
   `sys.modules['selectors']` shim was rejected: it only moves the death to the first loop iteration, hands
   users a fake module, and fixes the wrong layer. `wasmJsTest/.../WasmSelectorsImportTest.kt` (4 tests):
   `poll`/`select` return; `DefaultSelector is PollSelector`; `asyncio`, `asyncio.events`,
   `asyncio.base_events` import; a coroutine runs to completion on a selector-free loop.
2. *The default event loop still cannot be built.* `BaseSelectorEventLoop.__init__` →
   `_make_self_pipe` → `socket.socketpair()` → Emscripten `___syscall_listen` → `require('ws')` →
   `MODULE_NOT_FOUND`. Installing `ws` (tried temporarily with `npm("ws", "8.18.0")` and a probe, then
   reverted) only exposes the next wall: `OSError: [Errno 28]` from `lsock.accept()`, because Emscripten's
   socket emulation needs a real WebSocket handshake to populate `pending` and `_fallback_socketpair()` runs
   `bind/listen/connect/accept` inside one synchronous wasm call, so Node's event loop never ticks in between.
   No npm package fixes that, and re-enabling JSPI brings back §1's `SuspendError`. In a browser `listen()`
   does not exist at all (`ENVIRONMENT_IS_NODE` only).
3. *Decision:* `proxyBootstrapSupportsAsyncio` stays **false** on wasmJs. A selector-free loop works as a
   mechanism (the `WasmSelectorsImportTest` run), but shipping one means keeping the whole
   `Runner`/`BaseEventLoop` contract (`call_later`, `shutdown_asyncgens`, `shutdown_default_executor`,
   exception handling, `KeyboardInterrupt`) correct across CPython versions. Whether the library ships a
   custom loop is an open decision (§8). Until then a wasm application can rely on the fast path and on
   failure-before-suspend (`AsyncUpcallPortabilityTest`).

---

## 6. Cycle collection is part of the table's job

A Python proxy holding a Kotlin object that holds a Python object forms a cycle neither collector can break
alone: CPython's traversal stops at the opaque handle, and the JVM sees the handle table as a live root.
Reference counting never frees cycles in any language. The fix makes the Kotlin side's Python references
visible to `tp_traverse`: the proxy's traverse reaches through the handle and enumerates the `PyObject`-typed
properties of the Kotlin object. KSP already walks every exposed class, so a traverse body per class is one
more generated field (`FragmentScanner.traverseBody`, `ClassModel.traverseBody`), not a new mechanism. A
reflection-based binding cannot afford this, which is why mature ones document "do not create cycles"
instead. Generated proxies reach the traversable type through the GC base of §4.2.

Status: SPEC M-3 — implemented on desktop (`desktopTest/.../ref/CycleCollectionTest.kt`,
`ksp-fixtures/app/.../RefHolderCycleCollectionTest.kt`), partial elsewhere. Mechanism and the hard parts
(traverse during collection, `tp_clear` mutating Kotlin state, cycles closing on the Kotlin side) are in
[`object-lifetime.md`](object-lifetime.md).

---

## 7. Measurements

How to reproduce: the cost table is cut by `./benchmarks/cost-table.sh --runs 3` (see
[`cost-table.md`](../investigations/cost-table.md) "다시 뽑는 법"). Individual tests print their own blocks:

| Test | Source set | Prints |
|---|---|---|
| `UpcallBoundaryCostTest` | `commonTest` (`python/native/ffi/`) | one upcall vs. downcall of the same shape vs. trampoline, with controls |
| `GeneratedProxyCostTest` | `commonTest` (`ffi/upcall/`) | proxy layer over the raw call; install cost |
| `UpcallOverheadTest` | `androidInstrumentedTest` | worker vs. instrumentation thread, first upcall on a fresh worker |
| `UpcallOverheadTest` | `commonTest` (`reflection/`) | name resolution vs. cached handle, inside Kotlin |
| `WasmUpcallRouteOverheadTest` | `wasmJsTest` | `Table.set` route vs. `addFunction` around a JS closure |

Rules: run alone, unfiltered, after checking `uptime` (AGENTS.md §8); ranges are min–max over the stated runs;
a single reading is not a measurement.

### 7.1 One upcall, per platform

Current figures, from `cost-table.md` (commit `958c0082b294`, 2026-08-14, Apple M1, `UpcallBoundaryCostTest`
warmup 100 000, 10 000 iterations per loop, 3 runs per target; desktop/iOS/wasmJs/androidNative from
`cut-hostless`, ART from `cut-api36` / `cut-api26`):

| Platform | upcall | downcall, same shape | trampoline alone | upcall / downcall | upcall / trampoline |
|---|---|---|---|---|---|
| desktop (JVM 21.0.12, macOS arm64) | 542.84–576.45 ns | 260.27–265.81 ns | 170.44–247.42 ns | 2.04–2.20x | 5.54–6.46x |
| wasmJs (Node) | 302.41–310.72 ns | 96.53–100.95 ns | 149.50–164.88 ns | 3.07–3.14x | 2.39–2.46x |
| iOS simulator (26.2, arm64) | 2253.20–2312.97 ns | 1585.46–1655.40 ns | 2794.42–2843.16 ns | 1.39–1.42x | 1.14–1.15x |
| androidNative `pmp_api36` | 3348.75–3443.23 ns | 2194.26–2365.61 ns | 3957.01–4069.40 ns | 1.45–1.54x | 1.19–1.20x |
| androidNative `pmp_api26` † | 3234–3310 ns | 2470–2554 ns | 2731–3228 ns | 1.28–1.32x | 1.02–1.20x |
| ART `pmp_api36` ‡ | 995.61–1154.10 ns | 804.27–838.60 ns | 1100.87–1167.17 ns | 1.18–1.43x | 1.10–1.29x |
| ART `pmp_api26` ‡ | 1196.52–1272.28 ns | 1253.41–1295.79 ns | 1800.86–1863.97 ns | 0.92–1.01x | 1.05–1.18x |

† Not in the cost table (it cuts androidNative on `pmp_api36` only); hand-cut at warmup 100 000, five runs.
Its spread is the emulator's: in two rounds a different single run was the outlier in a different row (5554 ns
downcall at warmup 3 000; a 3227.87 ns GIL-held trampoline at 100 000, which alone widens `upcall / trampoline`
from 1.16–1.18x). Read `pmp_api36` for the shape and `pmp_api26` for how much confidence an emulator supports.
No androidNative figure is a hardware number.
‡ The cost table marks both ART cuts "failed"; the failures were in `PhantomCleanerRegistryTest`, not the
benchmark, and were fixed in `c180c46f`.

Controls from the same cut:

| Target | empty Python loop | pure-Python callee | GIL-held trampoline |
|---|---|---|---|
| desktop | 8.67–8.68 ns | 25.77–26.09 ns | 85.52–97.95 ns |
| iOS simulator | 9.84–10.17 ns | 36.80–38.32 ns | 1948.58–2020.43 ns |
| wasmJs | 14.74–15.34 ns | 47.89–49.22 ns | 123.15–129.83 ns |
| androidNative `pmp_api36` | 17.22–17.31 ns | 48.85–58.45 ns | 2790.19–2857.59 ns |
| ART `pmp_api26` | 20.01–20.08 ns | 56.10–65.66 ns | 1073.40–1142.08 ns |
| ART `pmp_api36` | 17.22–18.14 ns | 58.40–65.30 ns | 873.48–911.95 ns |

The previous hand-cut round (warmup 100 000; five full suites per device, eleven on the hosts) read:
desktop 510–560 ns, wasmJs 290–304 ns, iOS 2266–2301 ns, androidNative `pmp_api36` 3228–3275 ns,
`pmp_api26` 3234–3310 ns, ART `pmp_api36` 944–1063 ns, ART `pmp_api26` 1146–1233 ns. (The predecessor document
labelled this list "warmup 3,000"; that was wrong — the 3 000-warmup desktop and wasmJs figures were 674 ns and
322 ns.) In that round each host had exactly one outlying run in eleven, kept rather than dropped: desktop's
GIL-held trampoline once read 186.81 ns against 76–91 ns (its ratio 2.89x against 5.62–7.02x); one wasmJs run
inflated every boundary row together (upcall 432.55, downcall 144.64, trampoline 264.44 ns) while the empty GIL
scope stayed at 24.67 ns, so its ratio (2.99x) stayed in band — the case for preferring the ratio on wasm.

### 7.2 Warmup: why 100 000, and what the old figures were measuring

`UpcallBoundaryCostTest` warmed 3 000 calls and every figure it published was taken before the host JIT
finished tiering up, so the number depended on how much upcall traffic **the rest of the suite** had pushed
through the process first. Diagnosis: on wasmJs, commit `4472f83a` made generated proxies installable, so
`GeneratedProxyCostTest` began driving ~270 000 upcalls before this test, and short-circuiting that one test
restored the figure; the original absolutes (desktop 861–1313 ns, wasmJs 703–1075 ns) fell on re-measurement
with the downcall column standing still. The fix (`7e9c6b8c`) is the warmup count, chosen from a measured
convergence point; bisections, the controlled experiment and the 40-rep sweep that identified host JIT tier-up
and ruled out CPython-side state are in [`downcall-design.md`](downcall-design.md) ("Ratio consistency" and
"The benchmark was measuring the benchmark").

**Check that it is fixed:** at warmup 100 000, short-circuiting `GeneratedProxyCostTest` moves desktop
510–560 → 501–530 ns and wasmJs 290–304 → 287–301 ns (overlapping); at 3 000 the same lever moved desktop
674 → 537 ns and wasmJs 322 → 292 ns. 13 desktop and 12 wasmJs full-suite runs, 3 of each with the lever
pulled.

**JIT hosts fell, AOT hosts did not** — the prediction that separates the explanations:

| re-measured at 100 000 | upcall, old → new | downcall, old → new |
|---|---|---|
| iOS simulator | 2263–2502 → 2266–2301 ns | 1599–1826 → 1599–1610 ns |
| androidNative `pmp_api36` | 3339–3598 → 3228–3275 ns | 2170–2222 → 2155–2229 ns |
| androidNative `pmp_api26` | 3314–4140 → 3234–3310 ns | 2636–5554 → 2470–2554 ns |

against desktop 674 → 537 ns, wasmJs 322 → 292 ns and ART `pmp_api36` 2301–3086 → 944–1063 ns (API 26 barely
moved, 1209–1329 → 1146–1233 ns). ART has a JIT and moved like desktop and wasmJs. "Did not fall" is not "needed
no warmup": from cold, androidNative's first 10 000 upcalls read ~1.3× the plateau; the old Kotlin/Native rows
landed near it only because the rest of the suite had warmed the path.

**Device sweep** — 40 consecutive reps of 10 000 calls from cold; first-rep ratio = rep 1 / mean of reps 21–40:

| Target | rep 1 / plateau (Python-driven upcall) | flat from | margin at 100 000 |
|---|---|---|---|
| iOS simulator | 1.01x | ~10 000 | 10x |
| androidNative `pmp_api36` | 1.27x | ~40 000 | 2.5x |
| androidNative `pmp_api26` | 1.30x | ~30 000 | 3.3x |
| ART `pmp_api26` | 1.93x | ~20 000, noisily | 5x |
| **ART `pmp_api36`** | **8.56x** | **~90 000–100 000** | **~1x — none** |

ART `pmp_api36`'s sweep: 9194.8, 3218.9, 2288.7, 1648.3, 1218.3, 1173.3, 1129.5, 1532.7, 1057.8, 1044.6 ns against
a plateau of 1074.2 — still 9% high at 70 000. Its downcall is flat from ~80 000 and its GIL-held trampoline
from ~50 000, so the Python-driven upcall sets the requirement. The controls are flat from the first rep on all
five device configurations (iOS 10.5 → 10.2 ns and 37.9 → 38.0 ns; ART `pmp_api36` 17.3 → 17.3 ns and
49.7 → 50.4 ns), so the sweep measures the boundary, not the interpreter. The host sweeps behind `WARMUP`'s doc:
40 000 cold / 70 000 warm on desktop, 70 000 on wasm; `overhead/BenchmarkTest`'s worst desktop row
(`PyUnicode_AsUTF8 (8 chars)`) reads 557 ns at 40 000 and 78–84 ns from 70 000. **100 000 is therefore the
constant everywhere**: `UpcallOverheadTest` (ART only) now uses it for its Python-driven loops and Kotlin-driven
downcall rows (previously 3 000 and 2 500), and `overhead/BenchmarkTest` uses it uniformly. With no margin on
ART `pmp_api36`, a slower device or busier host may read the boundary before it settles. The
`GeneratedProxyCostTest` warmup of 5 000 per row is deliberate: nineteen rows warm the shared `_pm_invoke` path
19 × 5 000 before the first timing.

**Filtered runs.** A desktop `--tests` run of this class alone reads 506–550 ns, inside the full-suite band, and
its controls match (8.4–8.6 vs 8.4 ns). **wasmJs is the exception**: filtered it reads 321–353 ns and its
*pure-Python* controls read 23.1 and 83.1 ns against the suite's 15.0 and 47.2 — CPython is itself a wasm
module there, so a short-lived Node process runs the interpreter 55–74% slower. Tripling the warmup to 300 000
does not move it (host-lifetime-bound). wasmJs's ratios survive filtering (2.66–3.13x vs 2.91–3.14x); its
absolutes are a full-suite figure only. The devices do not have the effect (three filtered runs vs five
full-suite runs):

| filtered vs full suite | upcall, filtered | upcall, full | empty Python loop | pure-Python callee |
|---|---|---|---|---|
| iOS simulator | 2320–2322 ns | 2266–2301 ns | 9.9–11.4 vs 9.9–10.3 ns | 36.8–38.0 vs 36.8–38.3 ns |
| androidNative `pmp_api36` | 3275–3294 ns | 3228–3275 ns | 17.21 vs 17.22 ns | 48.9–58.2 vs 48.9–74.2 ns |
| androidNative `pmp_api26` | 3245–3280 ns | 3234–3310 ns | 20.0–23.6 vs 20.0–20.6 ns | 51.7–55.3 vs 51.7–62.0 ns |
| ART `pmp_api36` | 996–1031 ns | 944–1063 ns | 17.2–19.3 vs 17.2–22.8 ns | 49.0–60.0 vs 49.2–63.3 ns |
| ART `pmp_api26` | 1118–1171 ns | 1146–1233 ns | 20.0–20.1 vs 20.0–21.5 ns | 51.7–63.4 vs 51.7–65.6 ns |

So device absolutes are quotable as absolutes: there CPython is native code the host executes, not a wasm
module the host compiles.

**ART rows are measured on the instrumentation thread**, which is fair now that the attach is amortised (§7.6).
On API 26 the worker is 43–249 ns dearer than the instrumentation thread, so the ART row is that much optimistic
against a `threading.Thread` caller.

**androidNative had no test run task** until `:python-multiplatform:androidNativeArm64Test`
(`python-multiplatform/build.gradle.kts`): KGP only registers execution tasks for the host and simctl. The task
pushes `test.kexe` and the CPython prefix (`Py_Initialize` aborts without a stdlib) to `/data/local/tmp`, runs
it under the TeamCity logger and converts the service messages to JUnit XML under
`build/test-results/androidNativeArm64Test/`, on every connected device with a matching ABI. First runs: 252
tests, 0 failures, both emulators, five runs — `artMain` and the androidNative cinterop came up clean.

### 7.3 The trampoline column needs a control

`UpcallTrampoline` takes its GIL pair unconditionally (§3.5), so driving it from a bare Kotlin thread pays a real
GIL acquisition per call while Python drives it already holding the GIL. Uncontrolled, the boundary prices
**negative** on devices: at warmup 100 000, five runs each, upcall / trampoline was 0.79–0.82x on the iOS
simulator, 0.82–0.85x and 0.77–0.78x on androidNative `pmp_api36`/`pmp_api26`, 0.86–0.95x and 0.65–0.70x on ART
`pmp_api36`/`pmp_api26`. It is measured twice, differing in that one thing, with an empty
`Python3.withPython { }` for scale (hand-cut round, warmup 100 000):

| | desktop | wasmJs | iOS sim | androidNative 36 | androidNative 26 | ART 36 | ART 26 |
|---|---|---|---|---|---|---|---|
| trampoline, caller holds nothing | 164–181 ns | 146–164 ns | 2794–2862 ns | 3789–3893 ns | 4174–4267 ns | 1093–1148 ns | 1693–1822 ns |
| trampoline, GIL already held | 76–91 ns | 122–129 ns | 1962–1990 ns | 2699–2760 ns | 2731–3228 ns | 793–907 ns | 1012–1087 ns |
| `Python3.withPython { }`, empty | 50–57 ns | 24–28 ns | 720–731 ns | 932–1002 ns | 1112–1221 ns | 276–303 ns | 498–549 ns |

The **GIL-held** row is the comparable denominator (§7.1 uses it). Desktop and wasmJs never showed the effect
because their GIL scope is 24–57 ns against 276–1221 ns on a device. At the old 3 000 warmup desktop's empty
scope read 124–152 ns and wasmJs's 79–81 ns — the cheapest operation is the one an unwarmed loop overstates
most. wasmJs's GIL-held trampoline is dearer than desktop's although its GIL scope is half the price, so wasm
pays for the trampoline body, not the GIL.

### 7.4 Where the differences come from

- **iOS and androidNative: the scaffolding, not the boundary.** There is no runtime boundary on these targets —
  a `staticCFunction` in the same binary. What is expensive is per-call scaffolding every C API call shares: an
  empty `withPython` scope is 720–1002 ns there against 50–57 ns on desktop and 24–28 ns on wasm, and the same
  inflation hits the downcall denominator, which is why the ratio sits near 1.4. (In the round that measured
  `Py_IncRef + Py_DecRef` — two calls, two GIL scopes — it cost 1551–2032 ns on iOS and 2010–2132 ns on
  androidNative against 110–120 ns on desktop and 44–49 ns on wasm; those device figures were unwarmed, so the
  multiple is not quotable, but the gap is wide enough that the conclusion survives.) Work to move either number
  belongs in the scaffolding, not in `UpcallEntry`.
- **Desktop: a real boundary.** GIL-held trampoline 76–91 ns against a 510–560 ns upcall (hand-cut round): ~420–480
  ns is the Panama upcall stub plus the `ctypes` shim, the largest relative boundary measured (5.62–7.02x in that
  round, 5.54–6.46x in the cost table). Desktop's `_pm_invoke` is a `ctypes.CFUNCTYPE` (and in this benchmark a
  Python `lambda *a: _pm_invoke(_pm_h, a)`), where other targets bind a `PyCFunction`, so desktop's row is an
  **upper bound** on its boundary — it includes ctypes' argument conversion and, in the benchmark, one extra
  Python call (26.7–27.2 ns).
- **wasmJs: ~300 ns with arguments**, of which 122–129 ns is the trampoline; the rest is the Python-side callable
  and the crossing with a real tuple. Its scaffolding is the cheapest and most stable (24.47–27.52 ns for an
  empty scope across eleven runs), as there is no OS thread machinery; that row reads 34–37 ns when the class is
  run alone, for the reason in §7.2.
- **ART: one JNI upcall per call**, plus the per-thread attach the first time a CPython-created thread calls
  (§7.6). Neither is on the marshalling path.

### 7.5 What a user calls: the generated proxy over the boundary

`GeneratedProxyCostTest` (`commonTest`) prices each proxy surface against the raw `_pm_invoke` call it wraps,
measured through the same object, in the same run, each row a zero-argument Python function called by one
shared loop (the floor is subtracted). Each row is the **minimum of three timed loops**: the quantity is a
difference of tens of ns on a boundary of hundreds and noise is one-sided — with one loop per row, four runs put
the instance-method delta at −17, −31, −60 and −71 ns. The last row of each table is the report's own
resolution: two raw rows are the same call (`_pm_invoke(h, ())`, different handle), so their difference is what
cannot be resolved. Three runs; taken in the round before §7.2's warmup fix, so read internally (raw against
proxy), not against §7.1's absolutes.

| Surface a user writes | raw boundary | through the proxy | ratio | proxy layer adds |
|---|---|---|---|---|
| **desktop** (JVM 21.0.12, macOS arm64) | | | | |
| `demo.calc.ping()` module function | 452–475 ns | 468–485 ns | 1.00–1.03x | +5…+17 ns |
| `c.increment(5)` instance method | 514–558 ns | 537–573 ns | 1.02–1.05x | +14…+27 ns |
| `c.value` property read | 495–527 ns | 509–542 ns | 1.02–1.03x | +14…+19 ns |
| `c.label = 'x'` property write | 522–548 ns | 569–609 ns | 1.09–1.11x | +47…+62 ns |
| `Counter.created` static read (metaclass) | 449–485 ns | 480–510 ns | 1.01–1.07x | +6…+34 ns |
| `Counter.created = 12` static write | 466–507 ns | 511–573 ns | 1.07–1.13x | +34…+66 ns |
| `demo.calc.tally` top-level read | 449–485 ns | 484–521 ns | 1.03–1.07x | +14…+36 ns |
| `demo.calc.tally = 9` top-level write | 466–507 ns | 520–553 ns | 1.08–1.11x | +44…+54 ns |
| `Counter(10)` constructor | 682–761 ns | 588–627 ns | — | not separable (below) |
| *resolution* | | | | *−3…+9 ns* |
| **iOS simulator** (arm64) | | | | |
| `demo.calc.ping()` module function | 1801–1890 ns | 1829–1895 ns | 1.00–1.01x | +6…+28 ns |
| `c.increment(5)` instance method | 2901–2987 ns | 2895–3029 ns | 0.98–1.01x | −44…+42 ns |
| `c.value` property read | 2504–2601 ns | 2510–2620 ns | 0.99–1.00x | −3…+19 ns |
| `c.label = 'x'` property write | 2832–2940 ns | 2925–3092 ns | 1.03–1.05x | +92…+153 ns |
| `Counter.created` static read (metaclass) | 1838–1931 ns | 1906–1982 ns | 1.01–1.03x | +28…+68 ns |
| `Counter.created = 12` static write | 2180–2315 ns | 2277–2365 ns | 1.02–1.04x | +50…+103 ns |
| `demo.calc.tally` top-level read | 1838–1931 ns | 1927–1981 ns | 1.02–1.05x | +48…+99 ns |
| `demo.calc.tally = 9` top-level write | 2180–2315 ns | 2286–2363 ns | 1.02–1.04x | +48…+105 ns |
| `Counter(10)` constructor | 2591–2724 ns | 2769–2864 ns | 1.05–1.06x | +140…+177 ns |
| *resolution* | | | | *+15…+79 ns* |

**The boundary is the whole cost**: the generated layer adds tens of ns (1.00–1.13x); bypassing proxies to call
`_pm_invoke` by hand would recover almost nothing. The desktop constructor row is not reportable: when it was
taken nothing released constructed objects, so both rows ran against a `HandleTable` growing to 78 002 live
handles and the raw baseline moved between 511 and 775 ns (since fixed by `__del__`, §4.2). On the simulator it
is a stable +140…+177 ns (`type.__call__` + `__init__` frame + attribute store). Half the simulator rows are
inside its resolution; desktop resolves to ±10 ns.

**wasmJs** (`wasmJsNodeTest`, one run as printed; harness floor 62.48 ns):

```
  module function   raw 301.91 ns   proxy  318.98 ns   1.05x  (+17.06 ns)
  constructor       raw 719.75 ns   proxy 1070.98 ns   1.48x  (+351.23 ns)
  instance method   raw 339.22 ns   proxy  357.47 ns   1.05x  (+18.24 ns)
  property read     raw 290.94 ns   proxy  308.41 ns   1.06x  (+17.47 ns)
  property write    raw 360.42 ns   proxy  438.77 ns   1.21x  (+78.34 ns)
  static read       raw 228.82 ns   proxy  300.94 ns   1.31x  (+72.12 ns)
  module read       raw 228.82 ns   proxy  324.39 ns   1.41x  (+95.57 ns)
Resolution: two identical raw rows differ by -73.09 ns
HandleTable roots before the timed loops: 1, after: 1
```

The static and module rows (+72…+96 ns) are the size of the resolution (73 ns) and are not numbers; the
constructor's +351 ns is (`type.__call__` + `__init__` + attribute store + `__del__`). Roots 1 → 1 shows ten
thousand `Counter(10)`s all released through `__del__` → `_pm_release` (a mis-dispatched release would have
leaked silently). Android and androidNative also run this `commonTest` file; no proxy-cost rows were recorded
for them (missing, not zero).

**The top-level property fix.** A top-level `val` read used to cost +551…+587 ns over its boundary on desktop
(2.16–2.29x) against +6…+34 ns for the metaclass doing the same job for a class static. A standalone probe, net of
an empty call: plain module attribute 14 ns; PEP 562 module `__getattr__` 214 ns; type-level `__getattr__` hook
(the old shape) 569 ns. After moving to a per-module data descriptor:

| desktop, net of the boundary | before | after |
|---|---|---|
| `demo.calc.tally` read | +551…+587 ns (2.16–2.29x) | +14…+36 ns (1.03–1.07x) |
| `demo.calc.tally = 9` write | +109…+136 ns (1.21–1.29x) | +44…+54 ns (1.08–1.11x) |

`PythonProxyInstallTest` passed unchanged across it, including the read-only refusal; nothing in the source
says which shape is fifty times dearer — the measurement found it.

**The await fast path costs one coroutine frame:**

| | desktop | iOS simulator |
|---|---|---|
| `_pm_invoke(h, (21,))` in the same coroutine loop, no await | 561–617 ns | 3025–3055 ns |
| `await` a pure-Python coroutine that never suspends | 67–70 ns | 84.5–84.8 ns |
| `await demo.calc.doubleNow(21)` through the generated `async def` | 618–647 ns | 3186–3239 ns |
| ratio, and what the wrapper adds | 1.03–1.10x, +24…+57 ns | 1.04–1.06x, +152…+185 ns |
| `asyncio.Future`s constructed during the timed loops | **0** (asserted) | **0** (asserted) |

**Install is a startup cost.** For the 11-entry, 2-class fixture (306 lines / 12.4 kB):

| | desktop | iOS simulator | wasmJs (338 lines, 14 052 chars) |
|---|---|---|---|
| `PythonProxySource.render()` | 226–271 µs | 606–664 µs | 186.63 µs |
| `Python3.exec` of the result | 816–927 µs | 2481–2532 µs | 2155.50 µs |
| `install()` end to end | 1.06–1.20 ms | 3.09–3.20 ms | 2342.14 µs |

Compiling and executing the Python is 75–80% of it; it scales with table size (ten times the surface ≈ tens of
ms, less than `Py_Initialize`).

### 7.6 Android: the attach, priced

All figures below were taken by `UpcallOverheadTest` (`androidInstrumentedTest`) at its old 3 000-call warmup,
two runs per emulator unless stated; they are kept as the record of how the per-call attach was found and
removed. **The within-run differences and ratios are quotable; the absolute ns are not** (they were read before
tier-up and are expected to fall at 100 000).

Per-call attach/detach (before):

| per upcall | API 26 | API 36 |
|---|---|---|
| instrumentation thread (ART already knows it) | 1307–1695 ns | 5307–6693 ns |
| Python worker thread, steady state | 59712–63293 ns | 19820–31219 ns |
| **the attach/detach pair** | **58405–61598 ns** | **14514–24526 ns** |
| upcall / downcall of the same shape, worker | 44.59–47.93x | 11.69–13.23x |

Attach once per thread (after, commit `409da6fc`):

| per upcall | API 26 | API 36 |
|---|---|---|
| instrumentation thread | 1280–1327 ns | 2982–5086 ns |
| Python worker thread, steady state | **1209–1329 ns** | **2301–3086 ns** |
| worker minus instrumentation thread | −118 to +49 ns | −681 to −2000 ns |
| upcall / downcall of the same shape, worker | **0.99–1.09x** | **2.00–2.34x** |

Re-run unchanged over five full suites per emulator after `09bf2397` put `commonTest` on the device (so
`UpcallBoundaryCostTest`'s several hundred thousand upcalls ran first and left the JIT warm — the observation the
warmup fix came from: the API 36 instrumentation row differs fourfold at the same warmup and emulator):

| re-run, 5 suites each | API 26 | API 36 |
|---|---|---|
| instrumentation thread | 1085–1273 ns | 1032–1229 ns |
| Python worker, steady state | 1249–1334 ns | 982–1219 ns |
| worker minus instrumentation | **+43 to +249 ns** | **−50 to +12 ns** |
| first upcall on a fresh worker | 88 250–242 375 ns | 41 208–611 416 ns |

On API 36 the claim sharpens (the gap is tight around zero). On API 26 a worker costs a little more (+43–249 ns
against a ~1250 ns call) — not a return of the per-call attach. **The first upcall on each worker still pays the
attach in full** (56–142 µs in the first round, 41–611 µs in the re-run): once per thread, not once per call. A
worker that upcalls once gains nothing; one that upcalls in a loop is 20–50× better off. The structural assertion
the test enforces — one ART thread per Python worker — is a count and unaffected by warmup.

---

## 8. Open items

Only what is really open; each with what proves the done part.

1. **Async proxies on wasmJs.** Synchronous proxies run (`PythonProxyInstallTest` 10/11 on `wasmJsNodeTest`);
   `import asyncio` works (`WasmSelectorsImportTest`); the default event loop cannot be built (§5.8). Open
   decision: whether the library ships a selector-free loop. SPEC U-5 `planned` on wasm.
2. **Kotlin-side forced cancellation.** Structurally impossible without `kotlinx.coroutines`' `Job` tree; only
   cooperative `ensureActive()` exists (§5.6). Not planned unless that dependency is taken.
3. **A handle left by a dead loop.** The Kotlin `finally` release is meant to cover a loop that stopped before
   its done callback ran; that case was never constructed and observed (unverified).
4. **Silent exclusions without a KSP warning.** A `suspend` function *type* warns (§5.3); a `suspend fun` with
   an extension receiver or type parameters is still dropped silently by the shape checks
   (`BindingPolicy.isExposedFunctionShape`). The warning belongs there too.
5. **Extension functions through KSP.** KSP excludes them; the artifact walker binds them as receiver methods
   (SPEC U-7 `partial`).
6. **Kotlin calling a Python override.** Not implemented (§4.4).
7. **Per-target gaps outside desktop.** U-3/U-4 are asserted on desktop, with smoke coverage elsewhere
   (`NativeSmokeTest`, `GeneratedAndroidTableTest`); the native async tests have no recorded per-test
   androidNative result (§5.7); no proxy-cost rows for Android/androidNative (§7.5); the iOS sample cannot show
   the really-suspending demo (§5.8).
8. **`BYTES` marshalling** is per item until `PyBytes_AsStringAndSize` is bound (§3.3).
9. **Incremental KSP** needs per-file fragments (§2.7). **Transitive and published-artifact discovery** are
   unverified (§2.9).
10. **Native-image verification is manual** (SPEC U-6, N-5); automation is planned.
11. **Proxy-cost re-measurement.** §7.5's tables predate the 100 000 warmup; §7.6's absolutes predate it too.
