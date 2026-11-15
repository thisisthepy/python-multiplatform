# KLIB `.knm` Metadata, `@Composable` Calls, and Default Arguments on Kotlin/Native

Status: **Agent proposal, not confirmed** (owner decision required before implementing Step 2 or Step 3).

Related issue: `thisisthepy/python-multiplatform#191` (Phase B: Kotlin/Native and Wasm `.klib` artifact scanner parity with JVM `.jar` artifacts), `thisisthepy/pythonx-compose#110` (Compose Multiplatform on Kotlin/Native and GraalVM native image).

---

## 1. Context and Scope

`KlibScanner` (`python-multiplatform-gradle-plugin/.../artifact/KlibScanner.kt`) scans `.klib` archives so Kotlin/Native and Kotlin/Wasm compilations produce the same generated `ArtifactFragment_<pkg>.kt` registration tables and `.pyi` stubs as JVM `.jar` compilations (`ArtifactScanner`).

Step 1 (`thisisthepy/python-multiplatform#191` Phase B, A3) extends `KlibScanner` using `org.jetbrains.kotlin.library.abi.LibraryAbiReader` (`kotlin-compiler-embeddable:2.0.20`) with everything `LibraryAbiReader` exposes directly:

- Cross-klib handle class resolution (`classpath: List<File>` merged across the compilation's `.klib` artifacts).
- Public class constructors (`AbiFunction.isConstructor`).
- Top-level and nested `object` / `Companion` singleton handles (`STATIC_GETTER`, `isCompanionHandle = true`), constants (`STATIC_GETTER`), and methods (`FUNCTION`).
- Member properties (`GETTER` and `SETTER` on `final` classes, interfaces, and enums).

Two gaps remain between `LibraryAbiReader` and full Compose Multiplatform `.klib` coverage (`compose-runtime`, `compose-ui`, `compose-foundation`, `compose-material3`, and the `org.thisisthepy.compose.designsystem:*` KLIBs):

1. **Gap A (Metadata missing from `LibraryAbiReader` in `2.0.20`):** Parameter names (needed for keyword dispatch and default omission), extension function receiver types (`Modifier.padding`, `Modifier.fillMaxSize`), `@JvmInline value class` underlying types (`Dp`, `Sp`, `Color`, `TextUnit`), exact visibility (`public` vs `protected` on non-final classes), and `COMPANION_OBJECT` vs nested `OBJECT` class kind.
2. **Gap B (`@Composable` invocation and default-argument bitmasks on Kotlin/Native):** How the generated `ArtifactFragment_<pkg>.kt` invokes a `@Composable` function from a `.klib` with an explicit `(Composer?, Int)` pair and a Python-computed `$default` bitmask when Kotlin/Native (`konanc`) compiles Kotlin source and KLIB IR directly to native machine code without JVM `.class` bytecode.

---

## 2. Step 2 Measurement: `kotlin-compiler-embeddable` `2.4.20-Beta2` vs `.knm` Metadata (A2 vs A1)

### 2.1 Direct inspection of `kotlin-compiler-embeddable:2.4.20-Beta2`

We inspected `org.jetbrains.kotlin.library.abi.*` in both `kotlin-compiler-embeddable-2.0.20.jar` and `kotlin-compiler-embeddable-2.4.20-Beta2.jar` with `javap`:

| API surface in `org.jetbrains.kotlin.library.abi` | `2.0.20` | `2.4.20-Beta2` | Sufficient for Gap A? |
|---|---|---|---|
| `AbiValueParameter` parameter name (`getName`) | Absent (`getType`, `isVararg`, `hasDefaultArg`, `isCrossinline`, `isNoinline`) | Absent (`getKind`, `getType`, `isVararg`, `hasDefaultArg`, `isCrossinline`, `isNoinline`) | **No.** Keyword argument names (`text`, `modifier`, `onClick`) and named default calls (`$paramName = ...`) cannot be recovered from `LibraryAbiReader` in either version. |
| Extension receiver type | `hasExtensionReceiverParameter(): Boolean` on `AbiFunction`, but the receiver `AbiValueParameter` is not distinguished or is merged without a marker | Added `AbiValueParameter.getKind(): AbiValueParameterKind` (`REGULAR`, `EXTENSION_RECEIVER`, `CONTEXT`) | Partial (`2.4.20-Beta2` only). |
| `value class` underlying property and type (`Dp`, `Color`) | `AbiClass.isValue(): Boolean` only; underlying property/type is not exposed if constructor/property is private or internal | Unchanged (`AbiClass.isValue(): Boolean` only) | **No.** `Color(value: ULong)` and `TextUnit(packedValue: Long)` cannot be unwrapped without the underlying type. |
| `public` vs `protected` on `open` / `abstract` classes | `LibraryDeserializer$FileDeserializer.computeVisibilityStatus` maps `PROTECTED` on non-final classes to `VisibilityStatus.PUBLIC` | Unchanged | **No.** `AbiDeclaration` does not expose whether a member of an `open` class is `public` or `protected`. |
| Gradle plugin classpath compatibility | Matches Gradle 8.11.1's embedded Kotlin `2.0.20` runtime | Conflicts with Gradle 8.11.1's embedded `kotlin-stdlib:2.0.20` unless isolated and compiled with `-Xskip-metadata-version-check` | `2.0.20` stays native to Gradle 8.11.1. |

**Conclusion on A2:** Bumping `kotlin-compiler-embeddable` to `2.4.20-Beta2` does **not** expose parameter names, value-class underlying types, or `public` vs `protected` visibility on non-final classes, because `LibraryAbiReader` is a binary ABI dump tool that intentionally strips parameter names (which are not part of the KLIB IR binary signature).

### 2.2 Direct inspection of `.knm` readers already inside `kotlin-compiler-embeddable:2.0.20` (A1)

Inside `kotlin-compiler-embeddable-2.0.20.jar` (which is already on `klibScannerRuntime` in `python-multiplatform-gradle-plugin/build.gradle.kts`), the official KLIB metadata reader is present and public:

- `org.jetbrains.kotlin.library.KotlinLibraryLayout` / `org.jetbrains.kotlin.library.MetadataLibrary` (returned by `ToolingSingleFileKlibResolveStrategy.resolve(klibFile, DummyLogger)`).
- `org.jetbrains.kotlin.library.metadata.KlibMetadataDeserializationUtilsKt.parsePackageFragment(ByteArray): ProtoBuf.PackageFragment`
- `org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl( fragment.strings, fragment.qualifiedNames )`
- `org.jetbrains.kotlin.metadata.deserialization.TypeTable( pkg.typeTable )`
- `org.jetbrains.kotlin.metadata.deserialization.Flags` (`Flags.VISIBILITY`, `Flags.MODALITY`, `Flags.CLASS_KIND`, `Flags.IS_INLINE_CLASS`, `Flags.DECLARES_DEFAULT_VALUE`, `Flags.IS_VAR`, `Flags.IS_SUSPEND`)
- `org.jetbrains.kotlin.metadata.deserialization.ProtoTypeTableUtilKt` (`returnType`, `receiverType`, `type`, `inlineClassUnderlyingType`, `supertypes`)
- `org.jetbrains.kotlin.library.metadata.KlibMetadataProtoBuf` (protobuf extensions `classAnnotation`, `functionAnnotation`, `propertyAnnotation`, `constructorAnnotation`, `paramAnnotation`, `typeAnnotation`)

We verified this in `jshell` against `kotlinx-coroutines-core-androidnativearm64-1.8.1.klib` (104 `.knm` package fragments across `kotlinx.coroutines`, `kotlinx.coroutines.channels`, `kotlinx.coroutines.flow`, `kotlinx.coroutines.sync`, and `kotlinx.coroutines.selects`):

- Parameter names are present on every function and constructor (`nr.getString(vp.name)` -> `"context"`, `"start"`, `"block"` on `CoroutineScope.launch`).
- Extension receiver types are present on every extension function and property (`ProtoTypeTableUtilKt.receiverType(fn, typeTable)` -> `kotlinx/coroutines/CoroutineScope`).
- Value-class underlying property name and type are present on every `@JvmInline value class` (`cls.inlineClassUnderlyingPropertyName` and `ProtoTypeTableUtilKt.inlineClassUnderlyingType(cls, typeTable)` -> `ChannelResult.holder: kotlin/Any?`).
- Exact visibility is preserved (`Flags.VISIBILITY.get(flags)` distinguishes `ProtoBuf.Visibility.PUBLIC` from `PROTECTED`, `INTERNAL`, and `PRIVATE`, eliminating the non-final class restriction in `B-2`).
- Companion objects carry `Flags.CLASS_KIND.get(cls.flags) == ProtoBuf.Class.Kind.COMPANION_OBJECT`, distinguishing named companions (`Job.Key`, `Channel.Factory`) from regular nested `object`s.
- Annotations (`@Composable`, `@Deprecated(level = HIDDEN)`, `@PublishedApi`) are readable directly from the `KlibMetadataProtoBuf.*Annotation` extensions on each `ProtoBuf.Class`, `ProtoBuf.Constructor`, `ProtoBuf.Function`, and `ProtoBuf.Property`.

**Step 2 Proposal (Agent proposal, not confirmed):**
Use `KlibMetadataDeserializationUtilsKt.parsePackageFragment` + `NameResolverImpl` + `Flags` from the existing `kotlin-compiler-embeddable:2.0.20` dependency (zero new dependencies, zero custom protobuf parsers) to enrich `KlibScanner` with parameter names, extension receivers, value-class underlying types, exact `PUBLIC` visibility on open/abstract classes, and `COMPANION_OBJECT` kind.

---

## 3. Step 3 Design: K/N `@Composable` Calls and Default Arguments (Blocker B)

### 3.1 Why JVM's `ComposableThunks` does not apply on Kotlin/Native

On the JVM (`python-multiplatform-gradle-plugin/.../artifact/ComposableThunks.kt`), `@Composable` functions and functions with >6 defaulted parameters cannot be called with their lowered `(..., Composer?, Int, Int)` signature from Kotlin source because the K2 frontend (FIR) runs before the Compose compiler plugin's IR lowering phase and rejects extra `$composer` / `$changed` / `$default` arguments at source level.

`ComposableThunks.kt` solves this on the JVM by emitting a tiny JVM `.class` file (`__ComposableThunk_<hash>.class`, bytecode version 52) containing a static `invoke` method that executes an `INVOKESTATIC` (or `INVOKEVIRTUAL` / `INVOKEINTERFACE`) directly against the Compose-lowered JVM descriptor `(..., Landroidx/compose/runtime/Composer;II)V` or `$default` bridge. The generated Kotlin source fragment `ArtifactFragment_<pkg>.kt` calls `__ComposableThunk_<hash>.invoke(...)`, which FIR sees as a normal static Java method taking `(..., Composer?, Int, Int)`.

On **Kotlin/Native (`macosArm64`, `linuxX64`, `iosArm64`, `iosSimulatorArm64`) and Kotlin/Wasm (`wasmJs`)**, `konanc` does not read `.class` files. Every dependency is a `.klib` containing Kotlin IR and `.knm` metadata, and the consumer app (`ArtifactFragment_<pkg>.kt`) is compiled from Kotlin source through K2 FIR -> Kotlin IR -> Compose IR lowering -> LLVM native machine code.

### 3.2 Requirements for K/N `@Composable` and `$default` binding

Any K/N mechanism for `ArtifactFragment_<pkg>.kt` must satisfy four requirements:

1. **Dynamic `(Composer?, Int)` forwarding from Python:** At run time, the Python bridge receives `(composer, changed)` from the active Compose frame (for example `CompositionLocal` or the `@Composable` lambda callback bridge `Function2<Composer, Int, Unit>`) and passes `composer` (`args[n] as androidx.compose.runtime.Composer?`) and `changed` (`args[n + 1] as Int`) in the `Array<Any?>` argument vector.
2. **Default-argument omission at scale:** Compose widgets have up to 18 parameters on `Text`, 17 on `OutlinedTextField`, and 48 on `ColorScheme`. A user in Python calls `Text("Hello")` passing 1 argument and omitting 15 defaults.
3. **Value-class (`Dp`, `Color`, `TextUnit`, `TextAlign`) and interface-receiver (`Modifier`, `RowScope`, `ColumnScope`, `BoxScope`) compatibility:** Many Compose widgets take `@JvmInline value class` parameters with default values (such as `color: Color = Color.Unspecified`, `fontSize: TextUnit = TextUnit.Unspecified`).
4. **No reflection at run time:** Kotlin/Native does not have `java.lang.reflect.Method` or `MethodHandle`. All dispatch must compile down to static calls in `ArtifactFragment_<pkg>.kt` or its KLIB/IR counterpart.

### 3.3 Candidate Designs (Agent proposals, not confirmed)

#### Candidate B1: Pure Kotlin Source Generation (`@Composable` Lambda Cast + `applyDefaultOmission` or Named Arguments)

In Kotlin source, the Compose compiler plugin transforms any `@Composable () -> Unit` lambda into an object implementing `Function2<Composer, Int, Unit>` (or `ComposableLambda`). Therefore, a non-`@Composable` lambda in `ArtifactFragment_<pkg>.kt` can invoke a `@Composable` function by constructing a `@Composable` lambda and casting it to `(androidx.compose.runtime.Composer?, Int) -> Any?`:

```kotlin
{ args: Array<Any?> ->
    val block: @androidx.compose.runtime.Composable () -> Unit = {
        androidx.compose.material3.Text(
            text = args[0] as String,
        )
    }
    @Suppress("UNCHECKED_CAST")
    (block as (androidx.compose.runtime.Composer?, Int) -> Unit).invoke(
        args[1] as androidx.compose.runtime.Composer?,
        args[2] as Int,
    )
}
```

- **How defaults work in B1:**
  - For functions with `<= 6` defaulted parameters (`MAX_OMITTABLE_PARAMETERS = 6`), `ArtifactRendering.applyDefaultOmission` generates a `when (defaultMask)` with `2^k` branches, each omitting the subset of defaulted parameters using Kotlin named arguments.
- **Advantages:**
  - Pure Kotlin source generation in `ArtifactRendering.kt`.
  - Requires zero compiler plugin changes, zero KLIB generation, and zero IR manipulation.
  - The Compose compiler plugin on the consumer module handles all `$composer`, `$changed`, `$default`, and value-class unboxing inside the `@Composable { ... }` block automatically!
- **Limitations:**
  - **Combinatorial explosion beyond 6 defaulted parameters:** `2^k` named-argument branches is 64 branches for `k = 6`, 32,768 branches for `k = 15` (`Text`), and `2^48` for `ColorScheme`. Thus, raw `2^k` branching cannot bind `Text`, `Button` (9 defaults), `IconButton` (6 defaults), `TextField` (16 defaults), or `Scaffold` (10 defaults).
  - **Can B1 avoid `2^k` without IR?** Only if `KlibScanner` restricts omitted subsets to common prefix/keyword patterns, which violates `SPEC.md` `B-8` (arbitrary keyword omission via `defaultMask`).

#### Candidate B2: Synthetic Dependency KLIB (`__ComposableThunk.klib`) Generated at Build Time

Analogous to how `ComposableThunks.kt` generates a JVM `.jar`/`.class` with lowered static bridges, the Gradle task generates a tiny `.klib` (or patches the `.knm` / IR of a helper KLIB) whose `.knm` metadata declares non-`@Composable` bridge functions `__ComposableThunk_<hash>.invoke(..., composer: Composer?, changed: Int, default0: Int)` and whose KLIB IR contains `IrCall` nodes targeting the real `@Composable` function's lowered IR symbol.

- **Advantages:**
  - `ArtifactFragment_<pkg>.kt` stays identical between JVM and K/N (both call `__ComposableThunk_<hash>.invoke(...)`).
  - O(1) generated code per function regardless of parameter count (`k = 15` or `k = 48`).
- **Limitations:**
  - Constructing valid KLIB IR (`IrFile`, `IrSimpleFunction`, `IrCall`, serialized protobuf IR tables, signatures, and target-specific Konan manifest properties) across Kotlin versions (`2.0.20` in the Gradle plugin vs `2.4.20-Beta2` in the consumer build) is brittle: KLIB IR binary format versions change between minor Kotlin releases (`2.0` -> `2.4`), so a KLIB serialized by `kotlin-compiler-embeddable:2.0.20` will be rejected by a `2.4.20-Beta2` `konanc` compiler with an ABI version mismatch.

#### Candidate B3: `@Composable` Source Stub + Minimal `KotlinCompilerPluginSupportPlugin` (`IrGenerationExtension`) in Consumer Build (Recommended Proposal)

Instead of generating a `.class` file (JVM) or serializing KLIB IR across Kotlin versions (B2), we combine source generation with a tiny `IrGenerationExtension` registered on the consumer compilation after the Compose compiler plugin's IR lowering phase (or we emit a source intrinsic call that the IR extension rewrites):

1. **In `ArtifactFragment_<pkg>.kt` (generated Kotlin source):**
   For each bound `@Composable` function (or non-composable function with `> 6` defaults on K/N), `ArtifactRendering` emits a source declaration annotated with a marker or calling a generated intrinsic stub where FIR sees a normal Kotlin call:
   - Specifically, `ArtifactFragment_<pkg>.kt` emits:
     1. A private `@Composable` reference holder (so FIR resolves the exact overload and links the KLIB symbol cleanly, and Compose compiler plugin lowers the reference or target).
     2. In the `IrGenerationExtension` (which runs inside the consumer's own `konanc` process, using the consumer's exact Kotlin IR version), any call to the marker stub `__pymp_composable_call(args, composer, changed, default0, ...)` is replaced with an `IrCall` to the lowered `@Composable` (or `$default`) function in IR.
   - Wait: how does Compose compiler plugin lower `@Composable` functions in KLIBs on Kotlin/Native?
     - In Kotlin/Native, `.klib` archives store **pre-Compose-lowering IR** (or post-lowering IR depending on Kotlin version: since Kotlin 2.0 K2 Compose compiler, `@Composable` functions in `.klib` have their original parameters in `.knm` metadata, and the Compose compiler plugin's `ComposerParamTransformer` transforms both KLIB declarations' IR symbols and call sites during the consumer's IR compilation!).
     - Because `ComposerParamTransformer` runs inside the consumer's `konanc` IR phase, an `IrGenerationExtension` registered with ` IrGenerationExtension.registerExtension` (ordered `LAST` / after Compose's `ComposerParamTransformer`, or generating the `$default` dispatch in IR before `ComposerParamTransformer`) has direct access to the exact `IrSimpleFunction` produced by Compose's `ComposerParamTransformer` (with `$composer: Composer?`, `$changed: Int`, `$default: Int` value parameters already added!).

#### Candidate B4: Pure Source Hybrid (B1 for `<= 6` defaults + Curried/Staged `@Composable` Default Helpers in Generated Kotlin Source)

Before introducing any compiler plugin (B3), can pure Kotlin source (B1) handle `> 6` defaulted parameters without `2^k` branches?

Let's check how Kotlin compiles default arguments when calling a function from Kotlin source:
- In Kotlin source, you can only omit a default argument by **statically omitting it at the call site**. You cannot pass a runtime boolean to decide whether a single parameter uses its default unless you branch (`if ((mask and (1 shl i)) != 0) f() else f(x = v)`).
- Because a single call site must statically choose which subset of parameters is present, passing an arbitrary subset of `k` parameters at a single call site requires `2^k` distinct call sites in pure Kotlin source.
- However, what if for functions with `k > 6` defaulted parameters in pure source (B1), we inspect how defaults are defined or used?
  - In `.knm` metadata, a parameter only records `Flags.DECLARES_DEFAULT_VALUE = true` (the default value expression itself is compiled into the callee's `$default` body in IR, not stored as a constant in `.knm`).
  - Therefore, the caller cannot synthesize the callee's default value expressions in source without calling the callee with those parameters omitted (or calling its `$default` bridge in IR).
  - That proves mathematically:
    - **Pure Kotlin source (B1)** can support **all `@Composable` functions with `<= 6` defaulted parameters** (`2^6 = 64` branches maximum) immediately with zero compiler plugin!
    - **Supporting arbitrary subsets of `> 6` defaulted parameters (`Text`, `TextField`, `Scaffold`, `Button`) on Kotlin/Native** requires either **B3** (a small `IrGenerationExtension` that emits the `IrCall` to the lowered `$default` / `@Composable` `IrSimpleFunction` after `ComposerParamTransformer`) or restricting pure-source `when (defaultMask)` generation for `k > 6` to common argument subsets until B3 lands.

### 3.4 Comparison Summary

| Criterion | B1: Pure Source (`@Composable` lambda cast + `applyDefaultOmission`) | B2: Synthetic `.klib` at Gradle task time | B3: Consumer `IrGenerationExtension` (post-Compose IR rewrite) |
|---|---|---|---|
| New compiler plugin / IR module needed? | **No** (changes only `ArtifactRendering.kt` and `KlibScanner.kt`) | No plugin, but requires KLIB IR serializer in Gradle task | **Yes** (small `kotlin-compiler-embeddable` `IrGenerationExtension` subproject) |
| Kotlin version coupling (`2.0.20` vs `2.4.20-Beta2`) | **None** (emits plain `.kt` source) | **High** (KLIB IR binary format breaks across Kotlin versions) | **Low** (`IrGenerationExtension` runs in consumer compiler) |
| `@Composable` with `0..6` defaulted params (`Column`, `Row`, `Box`, `Card`, `Checkbox`, `Switch`, `Slider`, `IconButton`) | **Supported (`2^k <= 64` branches)** | Supported | Supported |
| `@Composable` with `> 6` defaulted params (`Text` (15), `Button` (9), `TextField` (16), `Scaffold` (10)) | Cannot emit `2^15` branches in source | Supported | **Supported (O(1) single `IrCall` to lowered `$default` symbol)** |
| Value-class parameters (`Dp`, `Color`, `TextUnit`) | **Handled automatically by Kotlin compiler** | Must manually box/unbox in KLIB IR | Handled by IR lowering |

### 3.5 Phased Recommendation (Agent proposal, not confirmed)

1. **Immediate (Step 1, in this PR):** Land A3 in `KlibScanner` (cross-klib `classpath`, constructors, `object`/`Companion` handles, constants & functions, and member properties) using `LibraryAbiReader`.
2. **Next (Step 2 / A1):** Add `.knm` `ProtoBuf.PackageFragment` enrichment in `KlibScanner` using `KlibMetadataDeserializationUtilsKt.parsePackageFragment` (already in `kotlin-compiler-embeddable:2.0.20`) to recover parameter names, extension receivers (`Modifier.*`), value-class underlying types (`Dp`, `Color`, `TextUnit`), `PUBLIC` vs `PROTECTED` visibility, and `COMPANION_OBJECT` kind.
3. **Then (Step 3 / Blocker B):**
   - **Option 3A (Two-stage: B1 first, then B3):** Implement B1 (`@Composable` lambda cast in `ArtifactRendering.kt` when targeting K/N) immediately so `@Composable` functions with `<= 6` defaults work in pure Kotlin source, and follow with B3 (`IrGenerationExtension`) for `> 6` defaulted parameters (`Text`, `Button`, `Scaffold`, `TextField`).
   - **Option 3B (Direct B3):** Implement B3 directly so both `<= 6` and `> 6` defaulted `@Composable` and regular functions use the same lowered `$default` IR call on K/N, matching JVM's `ComposableThunks` 1:1.

Owner / leader decision requested between **Option 3A** and **Option 3B** before implementing Step 3.
