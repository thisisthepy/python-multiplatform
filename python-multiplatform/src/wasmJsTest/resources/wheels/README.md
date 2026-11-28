# Compiled wasm wheel fixtures

`WasmCompiledWheelTest` loads these wheels into the wasm interpreter (CPython 3.15t, Emscripten)
to prove that a wheel another toolchain compiled for `pyemscripten_2026_0` links against
`python.wasm` and runs. They are test fixtures, checked in so the test needs no download (leader,
2026-11-27). The test task extracts every `.whl` here into one `site-packages` next to the test
bundle; `-PwasmWheelsDir=<dir>` points it at another directory.

| Wheel | sha256 | Module | Expected |
|---|---|---|---|
| `wasmdemo-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl` (1892 bytes) | `df57a28e3a61f4eb8bc5fe84e4a09bdc76e8e720bd6c348345c3fb86c772298a` | `wasmdemo.wmod` (C) | `answer()` is 42; `unwind()` is 7 through `setjmp`/`longjmp`, so it imports the `__c_longjmp` tag |
| `wasmcpp-0.1.0-cp315-cp315t-pyemscripten_2026_0_wasm32.whl` (2213 bytes) | `6a41eb2a1be0421fcf35c862aefeacb11bbdbfc80f34ac2881d739df9a3c96ef` | `wasmcpp.wcpp` (C++) | `catch_it()` throws `std::runtime_error("boom 3")` inside the module and returns `"boom 3"`, so it imports the `__cpp_exception` tag and libc++abi's `__cxa_throw` |

`theCheckedInWheelsAreTheOnesTheReadmeDescribes` fails when a wheel here is not in the table above
(through `PINNED_WHEELS` in the test) or its hash differs. Update this table and `PINNED_WHEELS`
together.

## How they were built

Both by pypackpack's Emscripten adapter (pypackpack #82, PR #180), at pypackpack commit `2f3ddd0`
(branch `feat/82-wasm-compile`), built and handed over by the toolchain teammate on 2026-11-27:

```bash
PPP_WASM_REAL_BUILD=1 \
EMSDK=<emsdk 5.0.3> \
PPP_WASM_RUNTIME_MANIFEST=<manifest from .github/scripts/wasm/pack_pmp_runtime.py> \
PPP_WASM_WHEEL_OUT=<dir> \
./gradlew :packpack:test --tests '*EmscriptenRealBuildTest*'
```

- Emscripten 5.0.3, this repository's pin.
- Against the 3.15t headers of this repository's wasm build at `e5c4a058` (CPython `d220409a`).
- Flags: `-fwasm-exceptions -sSUPPORT_LONGJMP=wasm -pthread -fPIC -sSIDE_MODULE=1`.

Rebuilding the C wheel at that commit gave a byte-identical file. A new Emscripten or ABI changes
the hashes; ask for a rebuild with the same command and update the table.
