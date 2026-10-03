# nativeMain — rules

Kotlin/Native sources shared by iOS **and** androidNative. Reaches CPython through cinterop, in
the same process, with no boundary to cross.

## Shared with iOS — check before adding anything

`artMain` (androidNative-only) depends on this, and so does `iosMain`. Anything added here is
compiled for iPhones too. JNI exports, `@CName` symbols intended for ART, and anything else
Android-shaped belong in `artMain`.

## There is no boundary here

Kotlin and CPython are in one binary. A call is a call. This is the fastest platform in the
project for FFI, and composition — which exists to avoid crossings — buys nothing. `actual`
implementations of composed operations should just do the work directly.

The same is true in the other direction: an upcall from Python into Kotlin is a plain function
call, not a runtime transition. The upcall cost that shapes the JVM design does not exist here.

## Python reaches Kotlin through a `PyMethodDef`, not through `ctypes`

`UpcallEntry.kt` is the entry point Python calls in. It does **not** use the `@CName` +
`ctypes.CDLL(None)` route the design doc predicted, because neither half of that survives on iOS:
the `Python.framework` here has no `_ctypes` (no `lib-dynload` at all), and a `@CName` alias is
never emitted into a Kotlin/Native framework or test executable — only into the androidNative
`.so`. Measurements are in `docs/design/upcall.md`.

So an entry point that Python must be able to call needs a `PyMethodDef` with a `staticCFunction`
in `ml_meth`. Two rules come with that, and both have crashed this repo before:

- The `PyMethodDef` and its `ml_name` must outlive every callable built from it — `nativeHeap`,
  never freed. A `PyCFunction` object stores the `PyMethodDef *`; it does not copy it.
- An entry point reached from C takes its own `PyGILState_Ensure` **unconditionally**. `withGIL`'s
  depth counter records scopes Kotlin opened, and C is free to have dropped the GIL inside one.

## Lifetime

`PyAutoCloseable` uses `kotlin.native.ref.createCleaner`, which takes the resource as an
explicit argument precisely so the cleanup block does not capture the object. Keep it that way:
a cleanup action that reaches the wrapper keeps the wrapper reachable, and then it never runs.
