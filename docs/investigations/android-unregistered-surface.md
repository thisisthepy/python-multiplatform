# Android unregistered surface

## Conclusion

**The unregistered surface is empty.** Every `external fun` in `androidMain/.../bindings.kt` has a
`JNINativeMethod` entry in `artMain/cinterop/jni_onload.def`, except four that are unregistered on
purpose:

| declaration | why it is not in the table |
|---|---|
| `ffiAllocUtf8`, `ffiFreeUtf8`, `ffiReadUtf8` | hand-written JNI exports (`artMain/.../JNIOnLoadExporter.kt`) with the correct `JNIEnv*, jclass` prologue |
| `echoCriticalNamed` | exists to be name-linked, so a benchmark can measure that path |

Why it matters: an unregistered `external fun` does not fail cleanly. It falls back to the
`@CName` export in `nativeMain`, which has no `JNIEnv*`/`jclass` prologue, so the arguments arrive
shifted by two registers and the process dies on a truncated pointer, a `jstring` handed to a
Kotlin/Native `String` parameter, or a boxed `java.lang.Long`.

**Status: acted on, and enforced by a test.**
`python-multiplatform/src/desktopTest/.../native/ffi/JniCallConventionClassificationTest.kt`
`everyDeclarationIsRegisteredOrIsOneOfTheFourNamedExceptions` parses `bindings.kt` and
`jni_onload.def` on every desktop build and fails if a declaration has no entry or the exception
set above goes stale. The rule is written in `androidMain/README.md` ("Every `external fun` here
needs a table entry — no exceptions by reachability"). The migration this report recommended
(strings first, then core object model, then exceptions, then the rest) is complete.

## Numbers (all historical)

| | at the time of this report | later |
|---|---|---|
| unregistered `external fun` | 304 of 375 | 178 of 365 (187 registered) after the string half |
| `jstring`-carrying declarations | 52 | 6, none on a live path |
| reported "reachable from `commonMain`" | 71 | **0** when the last 157 were registered |

The ordering recommended (reachable, string-taking first) was right; the counts were not. By the
time the last 157 were registered, earlier passes had already taken the whole reachable surface,
so "71 reachable" had been consumed long before. Current counts are not recorded here because they
change with every binding; the test above re-derives them. The ROADMAP §2 pass recorded
363 of 367 registered, with the four exceptions above.

## How to reproduce

```bash
./gradlew :python-multiplatform:desktopTest --tests 'python.native.ffi.JniCallConventionClassificationTest' \
    --console=plain > .tmp/jni-classification.log 2>&1; echo "EXIT=$?"
```

(Desktop is enough: the test is a source-level parse, not a device run. Device-side registration is
exercised by `androidInstrumentedTest/.../native/ffi/JniWiringTest.kt`.)

## What stays useful

The call-graph traces (public `commonMain` API → `EmbedAPI.<fn>` → `bindings.<fn>`) are the record
of which API path reaches which C function; they are in the archived original, with the
per-function reachability lists:
[`docs/archive/investigations/android-unregistered-surface.md`](../archive/investigations/android-unregistered-surface.md).

Related: [`jni-call-convention-audit.md`](jni-call-convention-audit.md) (which convention a
registered function may use).
