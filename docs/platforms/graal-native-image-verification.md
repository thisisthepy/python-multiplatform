# GraalVM Native Image Upcall Verification

`AGENTS.md` states the condition this document answers: *"JVM 에서만 통과하는 업콜은 완료가 아니다."*
The §7 design chose a build-time generated table precisely because runtime reflection is impossible
under GraalVM's closed-world assumption, and that choice is only confirmed by building the image.

This file is both the **procedure** (how to reproduce) and the **record** of the last run.

Status: a manual verification (SPEC U-6). The image itself is not run by any automated test; what
guards it automatically is `ReachabilityMetadataTest.kt` (SPEC C-2), which checks that every
`FunctionDescriptor` the desktop backend links is declared in the generated reachability metadata.
The record below is dated 2026-08-17 and has not been re-run since this document was last reviewed
(the "Worktree" path in it predates the rule that worktrees live under `.worktrees/`).

---

## Procedure

### Toolchain

Liberica Native Image Kit, already unpacked on the external volume:

```
/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4
```

```
$ .../Contents/Home/bin/native-image --version
native-image 25.0.4 2026-07-21
GraalVM Runtime Environment Liberica-NIK-25.0.4-1 (build 25.0.4+10-LTS)
Substrate VM Liberica-NIK-25.0.4-1 (build 25.0.4+10-LTS, serial gc)
```

It is deliberately **not on `PATH`**, and `GRAALVM_HOME` is **unset**. Nothing needs them: the build
resolves the launcher through Gradle's toolchain service
(`javaToolchains.launcherFor { languageVersion = 25 }` in `sample/build.gradle.kts`) and derives
`bin/native-image` from the resolved installation path. The only requirement is that Gradle be told
where the installation lives.

### Why `JAVA_HOME` must stay on JDK 21

Gradle itself runs on **JDK 21** (`/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home`);
only `native-image` runs on 25. Pointing `JAVA_HOME` at JDK 25 makes the Gradle daemon's Kotlin
build-script parser fail in `JavaVersion.parse("25.0.4")`. So JDK 25 is supplied as a *toolchain
candidate*, not as the daemon's JVM:

```bash
JAVA_HOME=/Users/ibrew/Library/Java/JavaVirtualMachines/jdk-21.0.12+8/Contents/Home \
./gradlew <task> --console=plain \
  -Porg.gradle.java.installations.paths=/Volumes/macMini/toolchains/bellsoft-liberica-vm-full-openjdk25-25.0.4
```

### The three tasks

| Task | What it does |
|---|---|
| `:sample:runNativeImageUpcallDemo` | Runs `NativeImageMain.kt` on the **plain JVM**. The control. |
| `:sample:nativeCompile` | Builds `upcall-native-demo` with `native-image --no-fallback`. |
| `:sample:runNativeUpcallDemo` | Runs the binary and **asserts on the `UPCALL_OK` / `PROXY_OK` markers**, not on the exit code. |

`:sample` does not use the `org.graalvm.buildtools.native` plugin's lifecycle tasks. The plugin only
registers `nativeCompile`/`nativeRun` when the `java` plugin's `sourceSets.main` exists, which a
Kotlin Multiplatform `jvm()` target never creates; the build therefore invokes `native-image`
directly through an `Exec` task and keeps the plugin only for toolchain resolution. This is a gap in
the plugin's KMP support, not a native-image limitation.

---

## Last verification run

- **Date:** 2026-08-17
- **Branch / commit:** `work/graal` off `develop` @ `4687070a` (*Merge branch 'work/syspath' into develop*)
- **Worktree:** `/Volumes/macMini/worktrees/graal`
- **Machine note:** other agents were building concurrently — load average was **10.39** and physical
  memory was nearly exhausted (129 MB unused of 16 GB) when `nativeCompile` started. It still
  succeeded, and the pass/fail results below are unaffected by contention. **The timings, however,
  are not clean benchmarks and should not be quoted as such.**

### Step 1 — JVM control

```
:sample:runNativeImageUpcallDemo   EXIT=0   BUILD SUCCESSFUL in 42s
```

### Step 2 — Native image build

```
:sample:nativeCompile              EXIT=0   BUILD SUCCESSFUL in 46s
```

```
[2/8] Performing analysis...
    5,427 types,   6,490 fields, and  25,554 methods found reachable
    1,822 types,      51 fields, and   1,308 methods registered for reflection
       28 downcalls and 5 upcalls registered for foreign access
Finished generating 'upcall-native-demo' in 43.5s.
```

Artifact: `sample/build/native/nativeCompile/upcall-native-demo` — **26,317,504 bytes (25.1 MB)**,
`Mach-O 64-bit executable arm64`.

The `28 downcalls and 5 upcalls` the image registered match **exactly** the counts in the
*generated* `reachability-metadata.json`
(`python-multiplatform/build/generated/native-image-metadata/desktop/...`, `foreign.downcalls` = 28,
`foreign.upcalls` = 5). The image consumed the generated metadata rather than a checked-in file that
could have rotted out of sync — which is the failure mode `generateDesktopReachabilityMetadata`
exists to prevent, since an undeclared descriptor builds clean and dies at the first call with
`MissingForeignRegistrationError`.

### Step 3 — Native image execution

```
:sample:runNativeUpcallDemo        EXIT=0   BUILD SUCCESSFUL in 3s
```

Full output of the binary:

```
=== GraalVM native-image upcall verification ===
INFO: Python initialized successfully!
KOTLIN: table = 41 entries, 4 classes, from io_github_thisisthepy_sample
KOTLIN: resolve stub @ 0x103d30000
KOTLIN: invoke  stub @ 0x103d30040
KOTLIN: args    stub @ 0x103d30080
PYTHON: resolved handle = 4294967324
PYTHON: invoke result = 7
PYTHON: resolved args handle = 4294967325
PYTHON: invoke_args result = 'presses x3 = 21'
PYTHON: @PythonInternal entry resolves to -1
PYTHON: UPCALL_OK
KOTLIN: upcall verification finished
=== generated proxy verification ===
KOTLIN: installed: 587 lines of generated Python, 1 proxy classes
Greeter('Kotlin').greet(2)  ->  hello Kotlin! hello Kotlin!
g.subject                   ->  Kotlin
g.subject = 'Python'; greet ->  hello Python!
g.greetings                 ->  3  (2 + 1, counted in Kotlin)
g.greetings = 99            ->  AttributeError (private set held)
Greeter.PUNCTUATION         ->  !
Greeter.built               ->  1
Greeter.built = 100         ->  100
Greeter.forget()            ->  100, then built=0
Greeter.PUNCTUATION = '?'   ->  AttributeError (companion val is read-only)
Greeter('x').built          ->  AttributeError (companion is class-only, as in Kotlin)
await g.greetNow(1)         ->  hello fast path!
asyncio Futures created     ->  0  (fast path: the loop was never involved)
await g.greetLater(2)       ->  hello slow path! hello slow path!
asyncio Futures created     ->  1  (slow path: settled from a Kotlin thread)
raised                      ->  None
completer thread            ->  clean
PYTHON: PROXY_OK
```

### Step 4 — JVM vs native diff

The two runs are the **same commit and the same entry point**, so they are directly comparable. This
is the control that matters; comparing against a ROADMAP snapshot from an older commit (as an
earlier revision of this document did) conflates "changed since then" with "differs from the JVM".

Diffing the two 32-line output blocks:

```
3,5c3,5
< KOTLIN: resolve stub @ 0x119607f40      (JVM)
< KOTLIN: invoke  stub @ 0x11964e9c0
< KOTLIN: args    stub @ 0x11964e640
---
> KOTLIN: resolve stub @ 0x103d30000      (native image)
> KOTLIN: invoke  stub @ 0x103d30040
> KOTLIN: args    stub @ 0x103d30080
```

**29 of 32 lines are byte-identical.** The only three that differ are raw runtime stub addresses,
which carry no semantics. Every line that asserts behaviour — the table size, the resolved handles,
`invoke result = 7`, `invoke_args result = 'presses x3 = 21'`, the `-1` for `@PythonInternal`, all
of the proxy assertions, and both markers — is identical.

Incidental observation: under native-image the three Panama upcall stubs land at **contiguous**
addresses 0x40 apart, whereas on the JVM they are scattered. Consistent with stubs being laid out
into one image region at build time rather than allocated on demand.

### Step 5 — Closed-world proof

Running through Gradle leaves open the objection that a JVM was somewhere in the picture. It was
not. The binary was executed **directly, with `JAVA_HOME` removed from the environment**:

```bash
env -u JAVA_HOME PYTHONHOME=.../extracted/3.14.7/macos-aarch64/python \
  ./sample/build/native/nativeCompile/upcall-native-demo
# EXIT=0, both UPCALL_OK and PROXY_OK present
```

And it links no JVM at all:

```
$ otool -L upcall-native-demo
    /usr/lib/libSystem.B.dylib
    /System/Library/Frameworks/Foundation.framework/Versions/C/Foundation
    /usr/lib/libz.1.dylib
    /System/Library/Frameworks/CoreFoundation.framework/Versions/A/CoreFoundation
    /usr/lib/libobjc.A.dylib
```

No `libjvm`, no `libjli`. `libpython` is `dlopen`ed at run time. The build passes `--no-fallback`, so
a fallback image (which would have been a JVM launcher in disguise) would have failed the build
rather than been produced silently.

---

## Result

| Question | Answer |
|---|---|
| Native image builds | **YES** (`EXIT=0`, 25.1 MB Mach-O arm64) |
| Python -> Kotlin upcall works inside the image | **YES** (`UPCALL_OK`; `invoke result = 7` produced by this process, not a constant) |
| Argument-carrying trampoline works | **YES** (`invoke_args result = 'presses x3 = 21'`) |
| Generated Python proxy layer works | **YES** (`PROXY_OK`, including `await` over a `suspend fun` settled from a Kotlin thread) |
| `@PythonInternal` opt-out still enforced | **YES** (resolves to `-1`) |
| Behaviour differs from JVM | **NO** (29/32 lines identical; the 3 differing lines are runtime addresses) |
| Reflection registration needed for the upcall path | **NO** — the path uses none, which is the §7 design premise holding |

Nothing is outstanding on the desktop target. The untested surface is other targets: this verifies
**desktop only**, as `sample/build.gradle.kts` wires `nativeCompile` from the `desktop` compilation.
