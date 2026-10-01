# Android FFM Design Document (PanamaPort Analysis)

This document provides an implementation design for replacing the existing Kotlin/Native JNI bridge on Android with a Panama-style FFM approach, based on an analysis of the PanamaPort reference implementation.

## 1. The Bootstrap Problem

**Question:** Calling `libLLVM.so` from Java itself requires an FFI mechanism, but FFI is what LLVM is being used to build. How does PanamaPort break this circularity?

**Answer:** PanamaPort breaks the circularity by bypassing the need for a native stub entirely for simple function signatures. In `BulkLinker.java` (lines 436-492), the method `requireNativeStub` evaluates whether an LLVM trampoline is necessary. For functions whose signatures map cleanly to primitive types and pointers (like `LONG_AS_WORD` or `BOOL_AS_INT`) and use standard JNI or `@CriticalNative` (`CallType.CRITICAL`), `requireNativeStub` evaluates to `false`.

For these functions, the "irreducible primitive" is `ArtMethodUtils.registerNativeMethod()`. This method takes a Java `Method` object and a raw C memory address (from `dlsym`), and directly injects the C pointer into the internal Android Runtime (ART) `ArtMethod` struct, replacing its native entry point. This bottoms out entirely on Unsafe memory writes and reflection to locate the struct in memory. It requires zero native FFI machinery to bootstrap.

## 2. ART Method Patching

**Question:** How does `ArtMethodUtils.registerNativeMethod` actually work? What ART-internal structures does it touch, how does it locate them, and how version-sensitive is this across Android releases?

**Answer:** 
It works by directly modifying the C++ `ArtMethod` struct used by ART. 
1. It locates the `ArtMethod` memory address by parsing the `java.lang.reflect.Method` object using `Reflection.getArtMethod(Executable)` (which uses Unsafe to extract the hidden `artMethod` pointer).
2. It overwrites the `data_` (or `entry_point_from_jni_`) field of the struct with the raw C function pointer using `AndroidUnsafe.putWordN()`.

This is **extremely version-sensitive**. In `ArtMethodUtils.java` (lines 48-115), PanamaPort hardcodes four different memory layouts for the `ArtMethod` struct:
- `art_method_8xx_layout` (Android 8.0, 8.1)
- `art_method_9_layout` (Android 9)
- `art_method_11_10_layout` (Android 10, 11)
- `art_method_16p1_12_layout` (Android 12 to 15/16)

These layouts explicitly track C++ field additions/removals across ART versions (e.g., `dex_cache_resolved_methods_` or `hotness_count_` unions).

## 3. Hidden API Restrictions

**Question:** Android restricts reflective access to non-SDK interfaces. How does PanamaPort get around that, and what are the consequences?

**Answer:** PanamaPort completely bypasses Android's Hidden API checks by avoiding standard Java reflection (`Class.getDeclaredMethods`, `VMRuntime.setHiddenApiExemptions`) entirely. 
In `Reflection.java` (lines 48-100 and 350-650), it manually parses the internal C++ `mirror::Class` struct (`ClassMirror`) to extract the native arrays of `methods` and `fields`. It then uses Unsafe to instantiate a fake `MethodHandleImpl`, sets its internal pointer to the hidden `ArtMethod`, and uses `MethodHandles.reflectAs` to convert it back into a usable `Method` or `Field` object.

**Consequences:** 
- It requires **no manifest flags**.
- It **will break** on any future Android version where the internal memory layout of `mirror::Class` or `MethodHandleImpl` changes, requiring continuous maintenance of offset tables.

## 4. Minimum Viable Subset for THIS Project

### Downcalls
Required for ~330 CPython functions. Since these signatures only use `void`, `Int`, `Long`, `Double`, opaque pointers, and `String` (which can be passed as `const char*` pointers), they map perfectly to Java `@CriticalNative` methods (using `long` for pointers). LLVM is not required for this.

### Upcalls
Required for letting Python call back into Kotlin. 
PanamaPort's upcall path (`_AndroidLinkerImpl.java` lines 954-1100) generates an LLVM C stub that:
1. Obtains the `JNIEnv*` (via `ENVGetter.java` which looks up thread-locals or calls `AttachCurrentThread`).
2. Packages the native registers/arguments.
3. Invokes JNI `CallStaticVoidMethod` on a generated Java stub.
4. The Java stub converts the raw pointers to `MemorySegment` objects, invokes the target `MethodHandle`, and writes the return value back to native memory using raw Unsafe writes.
5. The LLVM stub checks for exceptions via JNI `ExceptionCheck` and aborts if one is found.

### VarHandle
**No, a VarHandle backport is NOT unavoidable.** 
In PanamaPort's own upcall path (`_AndroidLinkerImpl.java` lines 1334-1353), the return value is written back to native memory using `UpcallHelper.putInt`, `putAddress`, etc., which rely exclusively on `AndroidUnsafe.putIntN`. It does not use `VarHandle` for argument marshalling. Since this target project has its own `NativePointer` abstraction, we can avoid `VarHandle` entirely and use direct Unsafe accesses.

### Struct-by-value and variadic calls
CPython's Stable ABI intentionally avoids struct-by-value to maintain binary compatibility, relying exclusively on opaque pointers (e.g., `PyObject*`, `PyThreadState*`). Variadic calls (like `PyArg_ParseTuple`) exist, but modern bridges generally prefer array-based or vectorcall alternatives (`PyObject_Vectorcall`, `PyObject_CallObject` with tuples). The Stable ABI does not strictly force us to implement struct-by-value or variadic FFI handling.

### Memory-lifetime management
Upcall stubs must outlive individual calls, effectively matching the lifetime of the Python interpreter, as CPython stores these function pointers in long-lived structures (like `PyTypeObject.tp_new`). The minimum required is a simple list of allocated native memory blocks (stubs) that are freed upon `Py_Finalize`.

**What can be dropped from PanamaPort:**
- Everything in the `LLVM/` module (JIT is unnecessary, see Question 6).
- All 40 files in `Core/src/openjdk/` (`Arena`, `MemorySegment`, `GroupLayout`, etc.).
- The `VarHandles` module.

**What is kept:**
- `Unsafe/src/main/java/com/v7878/unsafe/ArtMethodUtils.java` (Method patching).
- `Unsafe/src/main/java/com/v7878/unsafe/Reflection.java` (Hidden API bypass).
- `Unsafe/src/main/java/com/v7878/unsafe/foreign/LibDL.java` (ELF parsing to locate `dlopen`/`dlsym`).
- `AndroidUnsafe.java`.

## 5. A Cheaper Bootstrap for this Project?

**Question:** Could the existing Kotlin/Native layer supply `dlopen`/`dlsym` and the LLVM entry points directly?

**Answer:** Yes, technically Kotlin/Native could export a generic `@CName("my_dlopen")` and `@CName("my_dlsym")` wrapper. 
**Why it would NOT work (or is highly undesirable):** The primary motivation for adopting a Panama-style FFM approach on Android is to entirely eliminate the Kotlin/Native Android build target (and the associated JNI bridge maintenance). If we retain `libmultiplatform_python3.13.so` simply to bootstrap FFI, we fail to eliminate the Kotlin/Native Android compilation step. Furthermore, as shown in Question 1, `dlopen` can be bootstrapped via `LibDL.java` parsing the Android linker's ELF symbol table, meaning the Kotlin/Native bridge is completely unnecessary.

## 6. Is LLVM Even Necessary Here?

### Downcalls
**No.** The ~330 CPython functions have a small set of fixed signature shapes. Rather than generating one `@CriticalNative` declaration per CPython function, which fails to support a unified `FfiBackend` because method patching leaves nowhere to pass the target function pointer, we will use a small set of pregenerated native trampolines. 

Based on an analysis of the ~330 functions in `EmbedAPI.kt`, there are exactly 14 distinct ABI shapes across the entire Stable ABI (e.g., 123 functions use `I_I`, 87 use `II_I`). All signatures use either exclusively integer-class arguments (pointers/ints) or exclusively floating-class arguments, with no mixed-class orderings to worry about. We only need 14 pregenerated C trampolines to cover the entire Downcall API, allowing both Desktop and Android to share the same dynamic `FfiBackend` interface.
- **Binary size:** Drops `libLLVM.so` dependency. 14 tiny C trampolines compile to almost nothing.
- **Startup cost:** Instant (no JIT compilation).
- **Android-version fragility:** Avoids relying on the undocumented/restricted `libLLVM.so` on Android.

### Upcalls
**No.** CPython upcalls (callbacks) also have a small set of signature shapes (e.g., `PyCFunction` is `II_I`, `destructor` is `I_V`, `getter` is `II_I`, `newfunc` is `III_I`, etc.), which collapse into just 4 distinct shapes (`I_V`, `I_I`, `II_I`, `III_I`), perfectly overlapping with the downcall trampolines. 

The real reason LLVM is unnecessary for upcalls is not just the small number of shapes, but that **routing happens through data, not through the function pointer**. Every standard callback receives enough context to identify the Kotlin target—usually the `PyObject *self` instance data (e.g., `destructor`, `reprfunc`), the `PyTypeObject *type` (e.g., `newfunc`), or the `void *closure` (e.g., `getter`, `setter`). Because the identity of the target is carried in the arguments, a handful of shared static trampolines can serve unlimited Kotlin targets. This is exactly how PyO3, JPype, and JEP work. 

The ONLY case that would require runtime code generation (LLVM or similar) is handing a bare Kotlin function pointer, with no context argument, to a third-party C API (like `qsort`). CPython's object model does not do this.

**Hybrid viability:** A tiny NDK-compiled C library containing ~14 static trampolines can serve both dynamic downcalls and data-routed upcalls. This is the most robust, performant, and maintainable solution, and it enables a unified multiplatform architecture.

## 7. Licensing

**Question:** Which of the 40 vendored OpenJDK files (under GPLv2) in `Core/src/openjdk/` would a reimplementation actually need?

**Answer:** **None.** 
Because this project has its own `NativePointer` abstraction and does not need to expose a public FFM API, we do not need `MemorySegment`, `Arena`, `MemorySessionImpl`, or `ValueLayout`. Memory reads/writes can be done directly using `Unsafe`, exactly as PanamaPort's internal `UpcallHelper` does. 

## 8. Recommended Plan

**Stage 1: Core Patching Machinery**
- Port `AndroidUnsafe`, `Reflection`, `ArtMethodUtils`, and `LibDL` from PanamaPort.
- Verify that `LibDL` can locate `dlopen`/`dlsym` and that `ArtMethodUtils` can bind `Py_GetVersion` to a `@CriticalNative` Java method.
- *Risk/Effort:* Moderate. *Verification:* Requires a real Android emulator/device to verify `ArtMethodUtils` struct offsets.

**Stage 2: Downcall Generation**
- Create the NDK C project containing the 14 generic C trampolines.
- Write the Kotlin initialization code that binds these trampolines via `ArtMethodUtils` to power the shared `FfiBackend`.
- *Risk/Effort:* Low. *Verification:* Emulator/device required.

**Stage 3: Upcall Trampolines**
- Create a minimal NDK C project containing generic C trampolines for the required CPython callback shapes (e.g., `PyCFunction`).
- Implement the JNI lookup to route the C call back to the Kotlin `NativePointer` callback registry.
- *Risk/Effort:* Moderate. (Requires re-introducing a tiny NDK build, but vastly simpler than LLVM JIT).

**Stage 4: Migration & Cleanup**
- Reroute all Python calls in the Kotlin shared code to use the new `@CriticalNative` downcalls.
- Delete the old Kotlin/Native Android JNI bridge code and Android-specific Kotlin/Native build targets.
- *Risk/Effort:* High effort, low risk. *Verification:* Run the existing multiplatform test suite on Android.

## 9. Current State

Desktop's reflective Panama backend is fully implemented and `compileKotlinDesktop` now succeeds. The next step is promoting an `FfiBackend` interface into `jvmMain` so that Android can implement it using the shape-trampoline architecture designed above, unifying the JVM-side FFI.

---

## Open constraint: GraalVM Native Image

A design discussion recorded in an earlier session (`JNI Free 구현`, now installed under this
project's Claude Code sessions) establishes a target this document had not accounted for:
**GraalVM Native Image support is a goal for the desktop side.**

That has two consequences, both of which cut against work already committed here.

### Runtime reflection is unavailable

Native Image works under a closed-world assumption, so runtime reflection does not work unless every
reflective access is registered at build time. This rules out the obvious way of exposing Kotlin to
Python — walking classes reflectively at run time — and is why that discussion landed on a
**build-time generated class/function table**, reached through a single upcall entry point.

The registration policy decided there is worth carrying forward: every `public` declaration is
exported automatically, with an explicit `@PythonInternal`-style annotation to opt *out*. Opt-in
annotation on every exported member was considered and rejected as too noisy.

Two problems from that discussion remain unresolved:

- **Tree shaking.** A table that references every public function keeps all of them reachable, which
  defeats Native Image's dead-code elimination and inflates the binary. No answer was reached.
- **Dispatch cost.** `KFunction.call` runs about 50–100 ns because it re-checks arity and types and
  boxes arguments on every call; `MethodHandle` is roughly 10–20 ns once the JIT has optimised it.
  The stated target is Objective-C message dispatch, about 5–10 ns on a cache hit.

### The reflective Panama backend conflicts with it

`desktopMain/.../Panama.kt` reaches `java.lang.foreign` and `jdk.incubator.foreign` entirely
through `Class.forName` plus `MethodHandles`, precisely so it compiles on any JDK. Under Native
Image that is a problem twice over: the reflective lookups need build-time registration, and more
fundamentally **GraalVM requires FFM downcalls themselves to be registered at compile time** —
an unregistered one fails at run time with
`ForeignFunctionsRuntime$UnregisteredForeignStubException: Cannot perform downcall ... as it was not
registered at compilation time`.

So "works on every JDK via reflection" and "works under Native Image" pull in opposite directions,
and the current backend satisfies only the first.

The shape-trampoline design helps here rather than hurting: the signature census found the whole
CPython Stable ABI collapses into **14 shapes**, and registering fourteen downcalls at build time is
entirely practical, whereas registering arbitrary runtime-constructed signatures is not.

**This needs a decision before the desktop backend is built on further**: is Native Image support
confirmed? If so, the backend has to move from runtime reflective selection to build-time
registration of the fourteen shapes.
