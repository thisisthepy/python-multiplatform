# Upcall Table Design: Fragment Generation, Collection, and Tree Shaking

This document resolves the two open questions from
[`upcall-design.md`](upcall-design.md) and docs/roadmap/ROADMAP.md §7:

1. How do per-module function-table fragments find each other at runtime,
   given that Kotlin/Native has no `ServiceLoader` and no useful reflection?
2. How do we avoid defeating dead-code elimination?

## Status of claims

| Claim | Basis |
|---|---|
| KSP in app module sees generated code from library modules | **Shipped.** `python-multiplatform-ksp/`, with `ksp-fixtures/` running its generated table |
| `getDeclarationsFromPackage` is the discovery API | **Demonstrated** |
| Two-round processing lets the app discover its own fragment too | **Demonstrated** |
| `.klib` fragment discovery works on a real Kotlin/Native target | **Demonstrated.** `ksp-fixtures/{library,app}` on `androidNativeArm64`: `AppProcessor` round 2 found `Fragment_ksp_fixture_library` from the library's compiled `.klib`, `FunctionTable` compiled, and a real test binary linked (`linkDebugTestAndroidNativeArm64`). Compiled and linked only -- not run, since `androidNativeArm64` targets a device this workspace does not execute tests against |
| A KSP-generated table passes the same runtime API a hand-written one does | **Demonstrated.** `ksp-fixtures/app`'s `GeneratedTableTest` (11 tests, desktop) runs `UpcallTableTest`'s exact scenarios -- constructor/method/getter/setter round trip through `HandleTable`, `@PythonInternal` exclusion, narrow-`Int`/`Float` boundary widening, `tp_traverse` field detection -- against a table KSP generated, not a hand-written fragment |
| Kotlin/Native LLVM DCE operates at function-level granularity | Web research; not measured directly. What *was* measured is the net effect (next row), which is consistent with it but does not isolate the mechanism |
| Lambda references are eliminated when unreachable | Web research (LLVM `GlobalDCE`); not measured |
| Binary-size cost of a full table on K/N | **Measured**, §4 below: ~1.84 KB/entry (stripped) for 200 trivial exposed functions |
| `@EagerInitialization` is deprecated and should not be used | Confirmed (deprecated, slated for removal) |
| A fragment object can be `internal` as sketched below | **Wrong.** `internal` is enforced per Kotlin module; the app module's generated code referencing a library's fragment is a different module even inside one Gradle build, and fails with "cannot access ... it is internal". Fragments must be `public`. See §1's implementation note |

---

## 1  Fragment generation (per module)

A KSP processor scans every source file in the current compilation unit.
For each eligible `public` declaration it records the qualified name, the
callable reference, arity, parameter-type tags, and return-type tag.

The processor emits a **fragment object** into a well-known package:

```kotlin
// generated in package: python.multiplatform.generated.fragments
// file: Fragment_com_example_mylib.kt

package python.multiplatform.generated.fragments

internal object Fragment_com_example_mylib : FunctionTableFragment {
    override fun entries(): List<ExposedCallable> = listOf(
        ExposedCallable(
            name = "com.example.mylib.greet",
            callable = { args -> greet(args[0] as String) },
            arity = 1,
            paramTypes = listOf(ParamType.STRING),
            returnType = ReturnType.STRING,
        ),
        // ...
    )
}
```

### What is scanned

Per [`binding-policy.md`](binding-policy.md):

| Declaration | Included | Notes |
|---|---|---|
| `public` top-level functions | ✅ | |
| `public` top-level properties | ✅ | module attribute: `STATIC_GETTER`/`STATIC_SETTER` |
| `public` classes + constructors | ✅ | constructor only if concrete, non-`inner` |
| `public` member functions | ✅ | |
| `public` properties | ✅ | getter; setter only for `var` |
| `companion object` members | ✅ | under the *owner's* name, no receiver |
| `object` declarations | ✅ | the same shape; no constructor |
| `interface` | ✅ | members only; no constructor, receiver arrives as a handle |
| `enum class` | ✅ | one `STATIC_GETTER` per entry, plus `name`/`ordinal`/`valueOf` |
| nested classes / interfaces / enums / objects | ✅ | under `Outer.Inner`; the walk stops at a non-exposed owner |
| `annotation class` | ❌ | reading one back needs runtime reflection; an instance has nothing to attach to |
| `enum` `values()` / `entries` | ❌ | return collections; `ReflectedClass.enumEntryNames` carries the same data |
| generic declarations (`class Box<T>`, `fun <T> f`) | ❌ | `args[0] as T` / `as Box` do not compile |
| abstract / sealed class constructors | ❌ | "Cannot create an instance of an abstract class" |
| `internal` / `private` / `protected` | ❌ | `internal` is `Modifier.INTERNAL` in KSP |
| `suspend` functions | ❌ | hidden `Continuation` parameter |
| extension functions | ❌ | no receiver available from Python |
| compiler-generated (`copy`, `componentN`) | ❌ | KSP reports these two for a `data class`; not `equals`/`hashCode`/`toString` |
| `expect` declarations | ❌ | `actual` is found on the platform side |
| annotated `@PythonInternal` | ❌ | opt-out annotation |
| library's own packages (`python.multiplatform.*`) | ❌ | excluded by KSP option |

Everything in this table is asserted against a KSP-generated fragment in
`ksp-fixtures/app/src/desktopTest/.../GeneratedDeclarationKindsTest.kt`, not against a
hand-written one.

### What the fragment holds

Each `ExposedCallable` carries:

- **`name`**: the Python-visible qualified name
- **`callable`**: a lambda wrapping the target — *not* a `KFunction` reference
  (see §4 on tree shaking for why)
- **`arity`**: parameter count
- **`paramTypes`**: list of type tags (`INT`, `FLOAT`, `STRING`, `BYTES`, `OBJECT`)
- **`returnType`**: return-type tag

The lambda form `{ args -> greet(args[0] as String) }` is deliberate. On the JVM,
`KFunction.call()` boxes arguments and does per-call arity/type checking (50–100 ns
overhead per call, measured in this project's benchmarks). On Kotlin/Native,
`KFunction.call()` is not available at all. A generated lambda that directly invokes
the target function:

- avoids boxing on both platforms
- compiles to a direct call on Kotlin/Native (the compiler inlines non-capturing lambdas)
- creates a **static, traceable reference** that the linker can reason about (see §4)

On the JVM, the generated code can use `MethodHandle` instead of a lambda for even
lower overhead, but this is an optimisation within the same design — the fragment
shape stays the same.

### Fragment naming convention

The well-known package is `python.multiplatform.generated.fragments`.

Fragment objects are named `Fragment_<sanitised_module_name>`, where the module name
is passed as a KSP option `python.multiplatform.moduleName`. Periods and hyphens are
replaced with underscores.

This naming convention is not load-bearing for discovery — the aggregator finds
fragments by scanning the entire well-known package — but it prevents collisions
and makes generated code legible.

---

## 2  Fragment collection (the aggregator)

### The problem, concretely

On Kotlin/Native:

- There is no `ServiceLoader`.
- A top-level `object` that nobody references is never initialised (lazy init
  is the default since K/N 1.7.20; `@EagerInitialization` is deprecated).
- Runtime reflection cannot enumerate class members.

So a fragment that registers itself in its own initialiser simply never runs.
The fragments must be referenced explicitly by something that *is* reachable.

### The solution: a build-time aggregator in the app module

This was the suspected answer. **It is now verified experimentally.**

A throwaway project once stood at `ksp-experiment/`. It was removed once the real processor
shipped; what it demonstrated is now carried by `ksp-fixtures/`, whose tests run against a table
KSP actually generated. The shape it established
demonstrates the complete flow:

```
ksp-experiment/
├── ksp-processor/    — the KSP processor (fragment + aggregator)
├── library-module/   — a library with public functions
└── app-module/       — the application, depends on library-module
```

The library module's KSP generates `Fragment_library_module` into the well-known
package. The app module's KSP:

1. **Round 1**: generates `Fragment_app_module` for the app's own functions.
2. **Round 2**: calls `resolver.getDeclarationsFromPackage("...fragments")`
   and finds **both** `Fragment_app_module` (just generated) and
   `Fragment_library_module` (compiled into the library's JAR on the classpath).
3. Generates a `FunctionTable` that explicitly references every fragment.

**Verified output** (exit code 0):
```
Entries discovered: [experiment.app.appFunctionOne, experiment.app.main,
                     experiment.library.libraryFunctionOne,
                     experiment.library.libraryFunctionTwo]
```

### Why this works

KSP processes one module at a time and cannot see other modules' *source* files.
But `resolver.getDeclarationsFromPackage` queries the compilation classpath, which
includes compiled `.class` files (or `.klib` metadata on Native) from dependencies.

When the library module builds:
1. KSP generates `Fragment_library_module.kt` in the well-known package
2. The Kotlin compiler compiles it into the library's JAR (or `.klib`)

When the app module builds:
1. The library JAR/klib is on the compilation classpath
2. KSP's `getDeclarationsFromPackage` sees the compiled `Fragment_library_module`
3. The aggregator generates a `FunctionTable` with explicit references to all fragments

The generated `FunctionTable` looks like:

```kotlin
package python.multiplatform.generated

object FunctionTable {
    private val entries: Map<String, ExposedCallable> = buildMap {
        Fragment_library_module.entries().forEach { put(it.name, it) }
        Fragment_app_module.entries().forEach { put(it.name, it) }
    }

    fun lookup(name: String): ExposedCallable? = entries[name]
}
```

Because `FunctionTable` textually references every `Fragment_*` object, the
Kotlin/Native compiler sees them as reachable. No `ServiceLoader`, no
`@EagerInitialization`, no runtime reflection needed.

### Platform uniformity

The same aggregator design works on all four targets:

| Platform | Fragment discovery | Why it works |
|---|---|---|
| **JVM (Desktop)** | classpath (JAR) | `getDeclarationsFromPackage` sees JARs |
| **Android** | classpath (JAR/AAR) | same mechanism |
| **GraalVM Native Image** | classpath at build time | generated table is statically reachable → no reflection config needed |
| **Kotlin/Native (iOS)** | klib metadata | `getDeclarationsFromPackage` sees `.klib` dependencies |

On JVM/Android, `ServiceLoader` *could* work as a fallback, but using the same
aggregator everywhere eliminates a platform split and avoids runtime classpath
scanning.

### What the user has to do

**Library author:**
```kotlin
plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    ksp("python.multiplatform:ksp-processor:$version")
}
ksp {
    arg("python.multiplatform.role", "library")
    arg("python.multiplatform.moduleName", "my_library")
}
```

**App developer:**
```kotlin
plugins {
    id("com.google.devtools.ksp")
}
dependencies {
    implementation("com.example:my-kotlin-library:1.0")
    ksp("python.multiplatform:ksp-processor:$version")
}
ksp {
    arg("python.multiplatform.role", "app")
    arg("python.multiplatform.moduleName", "my_app")
}
```

Ideally the `role` distinction could be inferred (the app module is the one that
produces a final binary / framework / APK), but KSP has no built-in way to detect
this. A Gradle plugin wrapping the KSP configuration could set the role
automatically based on `plugins.hasPlugin("application")` or
`plugins.hasPlugin("com.android.application")`.

### Reaching `FunctionTable` from shared code: `@InstallsUpcallTable`

`FunctionTable` is emitted into the compilation that generated it, so only that target's own
source set can name it. An **intermediate** source set — `commonMain`, `iosMain`,
`androidNativeMain` — is one the generating leaves *depend on*, and it gets `Unresolved reference
'FunctionTable'`. `androidMain` is the exception, being the Android target's own source set; a
per-platform exception is exactly what shared code cannot be written against.

Moving generation to `kspCommonMainMetadata` would fix the visibility and break the scan: a
fragment has to see the target's own declarations, and a common-only scan exposes only what is
common. So generation stays in the leaves and the *seam* is generated instead:

```kotlin
// commonMain
@InstallsUpcallTable
expect fun installGeneratedUpcallTable()
```

The processor (app role, round 1) finds the annotated `expect` — a leaf compilation resolves its
whole source set closure, which is the same reason the fragment scanner sees `commonMain`
declarations — and writes one `actual` per leaf compilation, into the `expect`'s own package:

```kotlin
@python.multiplatform.reflection.PythonInternal
actual fun installGeneratedUpcallTable() {
    python.multiplatform.generated.FunctionTable.installInto()
}
```

`@PythonInternal` because the `actual` is an ordinary public top-level function in the user's
package, and an incremental round that fed the scanner its own previous output would otherwise
offer Python a callable that reinstalls the table underneath its caller.

A `library`-role module carrying the annotation is an error rather than a silent skip: it
aggregates nothing, so there is no `FunctionTable` for the `actual` to call, and the alternative
diagnostic is an unimplemented `expect` reported against the user's `commonMain` with nothing
naming the cause.

`expect`/`actual` rather than a common interface plus a runtime registry, for the reason §4 and §9
give: the fragment reference chain has to stay static or the Kotlin/Native linker is free to drop
what nothing names. The generated `actual` is that name.

### Discovery key: well-known package, not annotation

The aggregator does **not** scan for annotations. It calls
`resolver.getDeclarationsFromPackage("python.multiplatform.generated.fragments")`
and filters for objects whose name starts with `Fragment_`.

This is simpler and faster than annotation scanning (`getSymbolsWithAnnotation`),
which KSP documents as potentially expensive because it must scan the entire
classpath.

An alternative — keying on a marker interface `FunctionTableFragment` — would also
work. The aggregator could filter by supertype instead of name prefix. Either way,
the well-known package is the primary discovery mechanism.

---

## 3  What about `getDeclarationsFromPackage` being `@KspExperimental`?

`resolver.getDeclarationsFromPackage` is annotated `@KspExperimental` in KSP 2.x.
This means it may change in future KSP releases. However:

1. It is the *documented* API for cross-module discovery. KSP's own documentation
   recommends it for this use case.
2. No alternative exists that is non-experimental and works cross-module.
3. It has been stable in practice since KSP 1.x.

If it is removed or changed, the fallback is resource-file-based discovery: each
module's KSP writes a metadata file (e.g., JSON) to `META-INF/`, and the aggregator
reads those files from the classpath. This is more complex but equally viable.
The current design should abstract the discovery step so that switching mechanisms
does not change the fragment or table shape.

---

## 4  Tree shaking

### The problem

If the aggregator generates a `FunctionTable` that references every fragment, and
every fragment references every `public` callable in its module, then:

- The linker sees the entire public API as reachable
- Dead-code elimination cannot remove unused functions
- An app that calls three Kotlin functions from Python ships the entire library

This is the opposite of pay-for-what-you-use.

### Analysis: how bad is it, actually?

Before optimising, it is worth understanding the cost structure:

**What a fragment reference actually keeps alive:**

A fragment entry like `ExposedCallable("greet", { args -> greet(args[0] as String) }, ...)`
creates a lambda that closes over nothing (it calls the function by name). On
Kotlin/Native, non-capturing lambdas compile to singleton objects. The cost per
entry is:

| Component | Approximate cost |
|---|---|
| The lambda object (singleton) | ~16–32 bytes |
| The string literal (name) | ~40–80 bytes |
| The `ExposedCallable` wrapper | ~32 bytes |
| **The target function itself** | **varies — this is the real cost** |

The metadata overhead per entry is ~100–150 bytes. For a library with 300 public
functions, that's ~30–45 KB of metadata. This is noise.

The real cost is keeping the target functions alive. If `greet()` calls
`formatMessage()` which calls `translateString()` which pulls in an i18n library,
then the reference to `greet` keeps the entire dependency chain alive.

**But:** Kotlin/Native's LLVM backend performs function-level DCE with
`-ffunction-sections` + `--gc-sections`. Each function occupies its own section.
If `greet()` is referenced but `calculateTax()` is not, only `greet()` and its
transitive dependencies survive. The fragments reference every function, but the
linker still eliminates functions that are not referenced *by other code* in the
binary.

Wait — **that reasoning is wrong.** The fragment *does* reference every function
via the lambda. The lambda `{ args -> greet(args[0] as String) }` is a static
reference to `greet`. If the fragment is reachable (which it is, via the
aggregator), then *every lambda in it* is reachable, and therefore *every target
function* is reachable.

### The fundamental tension

The blacklist model ("all public unless opted out") means the fragment references
everything public. The aggregator references every fragment. Therefore the full
table keeps the entire public surface alive by construction.

There is no way to have all three of:
1. Blacklist model (all public exposed by default)
2. Full table referenced at build time (needed for K/Native)
3. Tree shaking of unused entries

You must give up at least one. The options:

### Option A: Accept the cost

**How:** Keep the current design unchanged. Every public function is in the table,
the table is reachable, nothing is eliminated.

**Cost:** For a library with 300 public functions averaging 50 bytes of machine
code each, that's ~15 KB of code that cannot be eliminated. For a library with
larger functions or transitive dependencies, the cost grows. On Kotlin/Native
where the baseline binary is 2–5 MB (Kotlin runtime + GC), 15–100 KB may be
acceptable.

**Advantages:**
- Simplest design
- No user-facing complexity
- Python can call any public function without configuration

**When this is right:** When the library is small or medium-sized, or when the
app uses most of the library's surface anyway.

**Measurement needed:** Build a release Kotlin/Native binary with and without the
full table and measure the delta. If it's under 100 KB for a realistic library,
accept it and move on. **This has not been done.**

### Option B: Fine-grained fragments

**How:** Instead of one fragment per module, generate one fragment per file (or
per class). The aggregator still references all of them, but if the linker can
trace through the `FunctionTable.entries` map and determine that certain
`Fragment_*` objects are never *looked up* at runtime, it could eliminate them.

**Problem:** The linker cannot do this. `FunctionTable` calls
`Fragment_X.entries()` unconditionally during its initialisation. All fragments
are reachable regardless of which names are actually looked up at runtime.

Making fragments lazily initialised (`by lazy { ... }`) would defer the call,
but `lazy` still captures a reference to the lambda that calls
`Fragment_X.entries()`, so the fragment is still reachable.

**Verdict:** Fine-grained fragments alone do not help. The aggregator's explicit
reference is the root cause.

### Option C: Opt-in at the call site (Python side)

**How:** Instead of generating a table of everything, require the Python code
(or a configuration file) to declare which Kotlin functions it wants to use.
The KSP processor reads this configuration and generates a table containing
only those entries.

```python
# python_bindings.txt (read by KSP)
com.example.mylib.greet
com.example.mylib.Calculator
```

**Advantages:**
- Perfect tree shaking: only referenced functions survive
- Binary contains exactly what is needed

**Disadvantages:**
- Contradicts the blacklist model ("all public, minus opt-out")
- User must maintain a manifest — fragile, especially during development
- Breaks the "write Kotlin, import from Python" promise

**Verdict:** This is a different product. The user request says the blacklist
model is not to be re-litigated.

### Option D: Lazy table with runtime resolution (JVM only)

**How:** On the JVM (where dead-code elimination is not an issue because classes
are loaded lazily), skip the aggregator entirely. Use `ServiceLoader` or simple
classpath scanning to discover fragments at runtime.

On Kotlin/Native, keep the aggregator but accept the cost (Option A).

**Advantages:**
- JVM apps pay no tree-shaking cost (not that they care — JVM doesn't tree-shake)
- Kotlin/Native pays the cost, but that's where it matters least (the library
  and app are typically small on iOS)

**Disadvantages:**
- Platform split in the collection mechanism
- `ServiceLoader` on JVM adds classpath scanning overhead at import time

**Verdict:** Not worth the complexity. The aggregator is simpler on all platforms.

### Option E: Two-tier table (recommended)

**How:** Split the table into a **skeleton** (names and metadata only) and a
**body** (the callable lambdas). The skeleton is always present. The body is
loaded lazily, per fragment, on first access.

```kotlin
// Generated skeleton — always present, small
object Fragment_mylib_Skeleton {
    val names: List<String> = listOf(
        "com.example.mylib.greet",
        "com.example.mylib.calculate",
    )
}

// Generated body — loaded only when a name from this fragment is actually called
object Fragment_mylib_Body {
    fun resolve(name: String): ((Array<Any?>) -> Any?)? = when (name) {
        "com.example.mylib.greet" -> { args -> greet(args[0] as String) }
        "com.example.mylib.calculate" -> { args -> calculate(args[0] as Long, args[1] as Long) }
        else -> null
    }
}
```

The `FunctionTable` references all skeletons (keeping them alive — ~100 bytes
per entry, names and metadata only). When Python does a `lookup("com.example.mylib.greet")`,
the table identifies which fragment owns the name and calls `Fragment_mylib_Body.resolve()`
for the first time, which triggers lazy initialisation of the body object.

**Key insight:** If no name from `Fragment_mylib` is ever looked up, the body
object is never initialised, and the lambdas inside it are never created.
**But the body class is still on the classpath and still reachable in principle.**

On the JVM, this is fine — class loading is lazy, so `Fragment_mylib_Body` is
never loaded if never referenced at runtime.

On Kotlin/Native, `object` initialisation is lazy (since 1.7.20), so the body's
`when` expression is never evaluated if the object is never accessed. **However,**
the body class itself is still compiled into the binary. The lambdas inside the
`when` arms are only created when that arm is executed, but the function references
they point to must still be compiled.

So on Kotlin/Native, Option E reduces the *runtime* cost (no lambda objects for
unused fragments) but does **not** reduce the *binary size*. The compiled code for
`greet()` is still in the binary because the `when` arm statically references it.

**Verdict:** Marginal improvement. Not worth the complexity unless runtime memory
is the concern rather than binary size.

### Recommended approach

**Start with Option A (accept the cost). Measure before optimising.**

The reasoning:

1. **The cost may be small.** The metadata overhead is ~100–150 bytes per entry.
   The function code itself varies, but on Kotlin/Native with a 2–5 MB baseline
   binary, even 100 KB of "extra" code is 2–5% — noticeable but not fatal.

2. **The cost may be unavoidable.** Given the constraints (blacklist model, no
   reflection, build-time table), there may be no mechanism that keeps a static
   reference to a function while allowing the linker to remove it. This is a
   fundamental property of static linking.

3. **The measurement is easy.** Build a Kotlin/Native release binary with and
   without the table. Diff the sizes. If the delta is under 100 KB for a
   realistic library (50–200 public functions), the problem is not worth solving.

4. **If the cost is unacceptable**, the only real mitigation is to change the
   exposure model — from "all public" to "explicitly annotated" — which is a
   product decision, not a technical one. The document noted in the user request
   says this is not to be re-litigated, so it stays as Option A for now.

### Measured

`ksp-fixtures/library` got 200 synthetic top-level functions (`fun bulkN(x: Long): Long
= x + N`), and `ksp-fixtures/app`'s `androidNativeArm64` debug test binary was linked
before and after (`linkDebugTestAndroidNativeArm64`), then stripped with the NDK's
`aarch64-linux-android-strip` from the same toolchain Kotlin/Native already downloads:

| | bytes | delta |
|---|---|---|
| baseline (unstripped) | 7,578,480 | |
| +200 entries (unstripped) | 8,343,184 | 764,704 (≈3.8 KB/entry) |
| baseline (stripped) | 3,264,056 | |
| +200 entries (stripped) | 3,632,696 | 368,640 (**≈1.84 KB/entry**) |

Read this as a floor, not a typical case: each synthetic function is a one-line
arithmetic expression, so almost the entire 1.84 KB/entry is the `ExposedCallable`
object, its lambda, its name string, and Kotlin/Native's per-function metadata --
not the function body. A real library's functions would add their own body size on
top of this floor. For a 200-function library that floor alone is ~360 KB against a
~3.26 MB stripped baseline (≈11%) — noticeable, in line with Option A's prediction,
and not disqualifying for a mobile/desktop target. This closes the "measurement
needed" item; whether ~11% for 200 entries is acceptable for a *real* library (whose
function bodies dominate the delta) is still a product judgement, not resettled here.

---

## 5  Detailed flow: from `ksp(...)` to Python `import`

### Build time

```
┌──────────────┐    KSP     ┌────────────────────────────────────────┐
│ library-mod  │ ────────── │ Fragment_mylib.kt                      │
│ src/         │  (role=    │   package ...generated.fragments       │
│  mylib/*.kt  │  library)  │   object Fragment_mylib {              │
│              │            │     fun entries() = listOf(...)        │
│              │            │   }                                    │
└──────────────┘            └────────────────────────────────────────┘
        │                                       │
        │ compile                               │ compiled into JAR/klib
        ▼                                       ▼
┌──────────────┐            ┌────────────────────────────────────────┐
│ app-module   │    KSP     │ FunctionTable.kt                       │
│ depends on   │ ────────── │   package ...generated                 │
│ library-mod  │  (role=    │   object FunctionTable {               │
│              │   app)     │     private val entries = buildMap {   │
│              │            │       Fragment_mylib.entries()...      │
│              │            │       Fragment_app.entries()...        │
│              │            │     }                                  │
│              │            │     fun lookup(name) = entries[name]   │
│              │            │   }                                    │
└──────────────┘            └────────────────────────────────────────┘
```

### Runtime

```
Python: from kotlin import greet
  │
  ├─ CPython import mechanism invokes _kotlin_bridge module init
  │
  ├─ Module init calls FunctionTable (which initialises the object,
  │  pulling in all fragments)
  │
  ├─ Python: greet("world")
  │    │
  │    ├─ tp_getattro on the module proxy
  │    │    First time: FunctionTable.lookup("greet") → handle
  │    │    Cached in proxy's __dict__
  │    │
  │    ├─ Invoke handle with marshalled args
  │    │    JVM: MethodHandle.invokeExact(...)
  │    │    K/N: direct function call (lambda inlined)
  │    │
  │    └─ Marshal return value back to Python
  │
  └─ Result: "Hello, world!"
```

---

## 6  KSP processor design

The project ships a single KSP processor module. It contains one
`SymbolProcessorProvider` that delegates to two internal processors
based on a KSP option:

| Option | Value | Processor | What it does |
|---|---|---|---|
| `python.multiplatform.role` | `library` | `FragmentProcessor` | Scan module, emit fragment |
| `python.multiplatform.role` | `app` | `AppProcessor` | Emit fragment (round 1), then discover all fragments and emit `FunctionTable` (round 2) |

Both processors use the same fragment-generation logic. The `AppProcessor`
additionally runs the aggregation step in KSP's second processing round
(triggered by the new file generated in round 1).

### KSP options

| Option | Required | Description |
|---|---|---|
| `python.multiplatform.role` | Yes | `library` or `app` |
| `python.multiplatform.moduleName` | Yes | Sanitised module name for the fragment |
| `python.multiplatform.excludePackages` | No | Comma-separated packages to skip (e.g., the library's own packages) |

### Multi-round processing

The `AppProcessor` exploits KSP's multi-round processing:

1. **Round 1:** Generate `Fragment_<app_module>` for the app's own functions.
   This produces a new file, which triggers round 2.
2. **Round 2:** Call `resolver.getDeclarationsFromPackage("...fragments")`.
   This now sees both the freshly generated app fragment and all dependency
   fragments (compiled into JARs/klibs on the classpath). Generate `FunctionTable`.

This was verified in the experiment: the round-2 query correctly returns
declarations from both sources.

---

## 7  The Gradle plugin

**Built:** `python-multiplatform-gradle-plugin/`, an included build (registered in the root
`settings.gradle.kts` under `pluginManagement`) publishing the id
`io.github.thisisthepy.python.multiplatform.bindings`. A consumer writes:

```kotlin
plugins {
    id("io.github.thisisthepy.python.multiplatform.bindings")
}

// only if the module produces the final binary without `application`/`com.android.application`:
pythonBindings { role.set("app") }
```

and gets: the KSP plugin applied, the processor added to every target's main compilation
(`kspDesktop`, `kspAndroidNativeArm64`, ... — never a `...Test` one, never
`kspCommonMainMetadata`, both of which would emit a second fragment under the same name), the role
inferred, and `moduleName` derived. `ksp-fixtures/{library,app}` are the worked example; their
build files lost the two `add("ksp<Target>", ...)` lines and the `ksp { arg(...) }` block each.

Two things the original sketch below did not anticipate:

- **`moduleName` carries the Maven group, not just the project path.** Fragment objects from every
  artifact share one package, so two independent libraries both called `:core` would emit the same
  object name into it. `:ksp-fixtures:app` with group `io.github.thisisthepy` derives
  `io_github_thisisthepy_ksp_fixtures_app`.
- **The plugin does not compile against KSP's Gradle plugin.** `kotlin-dsl` builds against
  Gradle's embedded Kotlin (2.0.20 on Gradle 8.11.1) and KSP 2.3.11 is compiled with Kotlin 2.3:
  "Class 'com.google.devtools.ksp.gradle.KspExtension' was compiled with an incompatible version
  of Kotlin". The dependency is `runtimeOnly` — a plugin id resolves from a classpath resource,
  not from a compiled type — and the two `ksp { arg(...) }` calls go through one reflective
  lookup of `arg(String, String)`.
- **Which KSP configurations count is decided by name, and Android names them differently.** AGP's
  source-set names put the build type last (`kspAndroidTestDebug`), so an `endsWith("Test")` check
  put the processor on the unit-test compilation, which emitted a duplicate fragment that shadowed
  `main`'s inside that compilation. `Test` is matched as a camel-case word now; see ROADMAP §13 and
  `ksp-fixtures/android`, the fixture that carries an Android plugin.

The original sketch, for the record:

The KSP options (`role`, `moduleName`, `excludePackages`) are mechanical.
A Gradle plugin (`python-multiplatform-bindings`) could:

1. Apply the KSP plugin automatically
2. Add the processor dependency
3. Infer the role from the module type:
   - `com.android.application` or `application` plugin → `app`
   - everything else → `library`
4. Derive `moduleName` from the Gradle project path
5. Set `excludePackages` based on the module's own `group`/`namespace`

This would reduce the user's configuration to:

```kotlin
plugins {
    id("python-multiplatform-bindings")
}
```

This is a usability concern for later. The KSP processor works without it.

---

## 8  What was NOT verified

### Kotlin/Native target with KSP

The experiment uses JVM-only modules. KSP processes Kotlin/Multiplatform
modules identically for all targets (it operates on Kotlin IR, not
platform-specific bytecode), so the fragment generation should work the
same way on Native targets. However, this was not tested end-to-end.

Specifically not tested:
- Whether `getDeclarationsFromPackage` works correctly when the dependency
  is a `.klib` rather than a `.jar`
- Whether the generated `FunctionTable` compiles on Kotlin/Native targets
- Whether the aggregator's explicit references actually prevent the K/N
  linker from stripping the fragments

**Recommendation:** Before implementing, build a minimal KMP project
(commonMain + nativeMain) with the same two-module structure and verify
compilation on at least one Native target (e.g., `macosArm64`).

### Transitive dependencies

Only tested direct dependency (app → library). Whether
`getDeclarationsFromPackage` discovers fragments from transitive
dependencies (app → libA → libB, where libB has fragments) was not tested.

It should work because transitive classes are on the compilation classpath,
but "should work" has been wrong three times in this project.

### Published Maven artifacts

Only tested with `project()` dependencies. When a library is published to
Maven and consumed as `implementation("group:artifact:version")`, the
generated fragment classes are in the published JAR. KSP should see them
the same way. Not tested.

### Binary-size impact

The actual KB cost of keeping unused functions alive on Kotlin/Native has
not been measured. This is the most important open measurement.

### GraalVM Native Image compatibility

The aggregator design avoids reflection entirely — `FunctionTable` has
static references to all fragments, which have static references to all
callables. Under GraalVM's closed-world analysis, this should work without
any `reflect-config.json`. Not tested.

---

## 9  Alternatives considered and rejected

### `@EagerInitialization`

Kotlin/Native's `@EagerInitialization` forces a top-level property to
initialise at program startup. This could make fragments self-registering.

**Rejected because:**
- Deprecated and slated for removal
- Does not solve the discovery problem (who calls `register()`?)
- Defeats tree shaking by definition (if it's eagerly initialised, it's reachable)

### Resource files (META-INF)

Each module's KSP writes a metadata file (e.g., JSON listing function names)
to `META-INF/python-multiplatform/`. The aggregator reads these resources
from the classpath.

**Not needed because** `getDeclarationsFromPackage` works for this use case.
Resource files are more complex (need classpath I/O in the processor) and
less type-safe (JSON parsing vs. direct class references).

**However**, resource files remain a viable fallback if
`getDeclarationsFromPackage` is removed or changed (it is `@KspExperimental`).
The design should keep the discovery step abstract enough to swap.

### Manual registration

Require the user to call `FunctionTable.register(Fragment_mylib)` in their
`main()`. Simplest possible design.

**Rejected because** it contradicts the "add the KSP plugin and nothing else"
goal. Every new library dependency would require a new registration call.

### Compiler plugin instead of KSP

A Kotlin compiler plugin (IR plugin) could inject fragment registration
directly, without generating source files. More powerful but:

- Much harder to write and maintain
- Compiler plugin API is less stable than KSP
- Not needed — KSP is sufficient for this use case

---

## 10  Summary of the design

| Aspect | Decision |
|---|---|
| Fragment generation | KSP processor per module, one fragment object per module |
| Fragment package | `python.multiplatform.generated.fragments` |
| Discovery mechanism | `resolver.getDeclarationsFromPackage` in the app module's KSP |
| Aggregation | `FunctionTable` generated in app module, explicit references to all fragments |
| Platform uniformity | Same mechanism on JVM, Android, GraalVM, and Kotlin/Native |
| Tree shaking | Accept the cost for now; measure before optimising |
| User experience | Add KSP plugin + processor dependency; set role and module name |
| Callable representation | Generated lambda (not `KFunction.call`) |
| Discovery key | Well-known package name, not annotation |

## 11  Open questions for implementation

Numbers 1, 2, 3, 4 and 6 are resolved; the resolution is recorded next to each so the
reasoning survives without the implementation session. 5 is still open.

1. **~~Measure binary-size impact on Kotlin/Native.~~ Resolved.** ≈1.84 KB/entry
   stripped, §4 above. Over the 100 KB guess for a 200-entry table (368,640 bytes
   measured) but the guess was for a *typical* function; the measured entries were
   deliberately trivial one-liners to isolate the table's own floor cost from
   whatever a real function body adds on top.

2. **~~Verify `.klib` discovery.~~ Resolved: it works.** `ksp-fixtures/{library,app}`
   on `androidNativeArm64` -- round 2's `getDeclarationsFromPackage` found the
   library's fragment from its compiled `.klib`, and the app's generated
   `FunctionTable` compiled *and linked* into a real test binary. Not run (no
   device), but the mechanism up through linking is confirmed, not inferred.

3. **~~Decide annotation name.~~ Resolved: `@PythonInternal`,** matching
   `binding-policy.md` (the "`@PythonHidden`" alternative from an earlier
   conversation was not carried into that document and there is nothing to
   reconcile).

4. **~~Handle `main()`.~~ Resolved.** `BindingPolicy.isExposedTopLevelFunction`
   excludes a function named `main` with zero parameters or a single `Array<...>`
   parameter.

5. **~~Incremental KSP.~~ Answered by measurement: the aggregator is not the cause,
   and narrowing it changes nothing.**

   `Dependencies(false)` (no files, not aggregating) means "never regenerate this
   file", which would leave a newly-added fragment undiscovered; `ALL_FILES` is
   correct but reprocesses on every change. The open question was whether something
   narrower would restore incrementality. It does not.

   Measured with `ksp.incremental.log=true` on `:ksp-fixtures:app`, a two-file module,
   modifying one file:

   | aggregator `Dependencies` | dirty / all |
   |---|---|
   | `ALL_FILES` | 100.00% |
   | `Dependencies(aggregating = true, <generated fragment files>)` | 100.00% |

   The source-to-output map explains it. A module's own `Fragment_<module>` is an
   *aggregating* output over every source file in the module -- correctly so, because
   blacklist exposure means any file can add an entry -- so it is keyed by KSP's
   `AnyChanges` wildcard and by all sources. Any change regenerates it, and KSP then
   marks every source that maps to it dirty. Whatever the aggregator declares is
   downstream of that.

   A classpath-only change behaves the same way: adding a function to
   `:ksp-fixtures:library` dirtied both app files with `Modified` empty and
   `CP changes` listing `Fragment_ksp_fixture_library`.

   `ALL_FILES` therefore stays: the narrower form buys nothing measurable, and whether
   it stays correct when a dependency's fragment appears with no local source change
   was never established. The only route to real incrementality is **per-file
   fragments** -- one isolating output per source file -- which changes fragment
   naming, `UpcallTable`'s per-module idempotency (`moduleName`) and where duplicate
   names are detected. That is a design change, and it would also be the lever for
   §4's tree-shaking option B.

6. **~~KSP for KMP.~~ Resolved for the targets this project applies it to.** Per-target
   configurations (`add("kspDesktop", ...)`, `add("kspAndroidNativeArm64", ...)`) work
   as documented; `ksp { arg(...) }` applies shared options to all of them. A Gradle
   convenience plugin to apply this automatically (§7) remains unbuilt.
