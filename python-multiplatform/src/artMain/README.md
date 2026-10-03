# artMain — rules

androidNative sources shared only by `androidNativeArm64` and `androidNativeX64`. iOS depends on
`nativeMain` directly and never sees this, which is what makes it the right home for JNI code.

## This is where JNI lives, and only here

`nativeMain` is shared with iOS. Anything JNI-shaped placed there compiles for iOS as well,
where it is dead weight at best. Exports, `JNI_OnLoad`, and the cinterop shim belong here.

## `JNI_OnLoad` binds by registration

`jni_onload.def` builds the `JNINativeMethod` table and calls `RegisterNatives`, pointing
declarations in `androidMain` straight at CPython's own C functions. No trampoline sits in the
hot path.

Keep the count argument in step with the table. `RegisterNatives(env, clazz, methods, N)` with
the wrong `N` fails silently or reads past the array.

## Composed functions go here

A composed function does a whole binder operation on the native side and crosses the boundary
once. Measured against the per-call equivalent on device:

| | per-call | composed | |
|---|---|---|---|
| `getAttr` | 3929.84 ns | 713.34 ns | 5.5x |
| `list → LongArray`, 1000 elems | 50065.89 ns | 4532.55 ns | 11.0x |
| `Python3.exec` | 17799.46 ns | 10123.94 ns | 1.8x |

They must take and return primitives, or fill a caller-provided array: Kotlin/Native objects
cannot cross to the JVM. And they run Python, so they use ordinary JNI — a GC-blocking
convention would be wrong regardless of speed.
