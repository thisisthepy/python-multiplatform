# mingwX64Main

The Windows Kotlin/Native desktop target (SPEC P-1a, #191). It depends on `desktopNativeMain`, so
everything there (the packaged `python-multiplatform-home` lookup, the free-threaded build facts) is
shared with macOS and Linux. What differs on Windows, and where it is handled:

| Rule | Why | Where |
|---|---|---|
| cinterop defines `Py_GIL_DISABLED=1` | Windows ships one `pyconfig.h` for the GIL and the free-threaded build and leaves the define to the compiler command line. Without it every object header has the GIL build's layout against `python315t.dll`. | `python-multiplatform/build.gradle.kts`, the `python` cinterop |
| cinterop passes `-mcrc32` | The MinGW-w64 headers `Python.h` reaches declare an inline wrapper over `__builtin_ia32_crc32qi`; cinterop compiles a stub for every inline function and fails without the feature. Nothing calls it. | same |
| Binaries link `libs/python315t.lib` | The MSVC import library from python-build-standalone; lld links it in MinGW mode. The DLL is found at run time beside the executable or on `PATH`. | `getTest(...).linkerOpts`; `mingwX64Test` puts the prefix on `PATH` |
| C `long` is 32 bits | Windows is LLP64. A binding that takes `long` is `Int` here and `Long` elsewhere, so shared code converts with `convert()` rather than `toLong()`. | `nativeMain` (`PyBool_FromLong`) |
| No `dlopen` / `dlsym` | Windows has no global symbol table. A CPython symbol is looked up with `GetProcAddress` on the loaded `python3XYt.dll`. | `SymbolLookup.mingwX64.kt` (the POSIX leaves each carry the `dlsym` version) |
| Paths use `/` | `GetModuleFileNameW` answers `\`; `executableDirectory()` converts, because `DesktopNativePythonHome` splits on `/` on every desktop native target. Windows accepts both. | `env/ExecutableDir.mingwX64.kt` |
| The stdlib is `Lib/` | The Windows prefix layout. `PythonHomeCheck` accepts it (L-3). | `commonMain` |

Not verified yet: a run. Compiled for `mingwX64` (main and tests) on macOS; `mingwX64Test` runs in
`desktop.yml` (`desktop-native-windows`).
