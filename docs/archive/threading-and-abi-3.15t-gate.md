# Why free-threading was gated on 3.15t (2026-08-10 reasoning)

> **Superseded** by [`../design/threading-and-abi.md`](../design/threading-and-abi.md) on 2026-10-03; kept for history.
> The conclusion below ("free-threading starts at 3.15t") was wrong for this project: 3.14t runs the whole
> desktop suite with `-PpythonFreeThreaded=true` (ROADMAP §9, SPEC T-2). The premise that blocked 3.14t
> (no Stable ABI on free-threaded builds) does not bind here because `Py_LIMITED_API` is not defined.

## Why 3.15t and not 3.14t

Free-threaded builds **do not support the Limited API or the Stable ABI** in 3.13 or 3.14. All ~330
bindings here target the Stable ABI, so a free-threaded 3.14 build cannot be used without abandoning
it and recompiling per Python version, which would defeat the version-parameterised acquisition
pipeline.

[PEP 803](https://peps.python.org/pep-0803/), **Final**, targeting 3.15, introduces `abi3t`, a
Stable ABI variant for free-threaded builds, with `abi3t` / `abi3.abi3t` wheel tags and forward
compatibility across all later versions. 3.15 is therefore the first release where free-threading
and the Stable ABI coexist.

### What abi3t requires of us

| Requirement | Our status |
|---|---|
| `PyObject` / `PyVarObject` become incomplete types; no field access | **Compatible.** Verified: `PyObject` appears only as `CPointer<PyObject>` and is never dereferenced. The only `.pointed` uses in the tree are commented-out `JNIEnv` code in `JniExport.kt`. |
| Extensions may not embed `PyObject` in their own structs | Compatible, nothing does. |
| `PyModExport` hook (PEP 793) instead of static `PyModuleDef` | Not yet relevant. We embed CPython rather than building an extension module. **It becomes relevant for the upcall work**, where Kotlin classes are exposed to Python. |
| No backwards compatibility with 3.14 or earlier | Was accepted at the time; no longer applies, 3.14t is used on desktop (ROADMAP §9). |


## Prebuilt availability (snapshot), as of the 20260807 python-build-standalone release

| Platform | GIL | Free-threaded |
|---|---|---|
| macOS arm64 / x86_64 | ✅ | ✅ (incl. `3.15.0rc1`) |
| Linux x86_64 | ✅ | ✅ (incl. `3.15.0rc1`) |
| Windows x86_64 | ✅ | ✅ (incl. `3.15.0rc1`) |
| Android (python.org) | ✅ | ❌ none published |
| iOS (Python-Apple-support) | ✅ | ❌ none published |

Desktop free-threaded builds exist today, including 3.15 release candidates. **Mobile has none**, so
enabling free-threading before mobile artefacts appear would split the threading model across
platforms, and with it the object-lifetime and thread-state design layered on top.

Switching the default (originally) required all three of the following; ROADMAP §9 shows 1 is not needed for 3.14t on desktop, while 2 still holds:

1. CPython 3.15 final, for `abi3t`
2. Free-threaded mobile artefacts from python.org and Python-Apple-support
3. Adequate free-threaded wheel coverage for the C extensions users care about

`gradle.properties` already carries `pythonFreeThreaded`, defaulting to `false`, so the switch is a
configuration change once those hold.

