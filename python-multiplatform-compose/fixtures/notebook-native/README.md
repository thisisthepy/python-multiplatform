# `:python-multiplatform-compose:fixtures:notebook-native`: `UI.ipynb` on Kotlin/Native desktop

Issue #191. The scenarios of [`notebook-e2e`](../notebook-e2e/README.md) (pythonx-compose's
`UI.ipynb`, cells 5 to 40) run on the Kotlin/Native desktop path instead of the JVM:

- the host is the same `PythonAppView(module = "pythonx.compose.runtime", attribute = "app_root")`,
  from `python-multiplatform-compose`'s common code;
- the Python side is the same installed pythonx-compose **wheel** and the same application module
  (`../notebook-e2e/src/desktopTest/python/main.py`);
- the bindings come from the fork's Compose klibs, walked by `KlibScanner`. Every composable past
  B1's 6 defaults (`Text`, `Button`, `Card`, `Icon`, `TextField`) is called through the B3 compiler
  plugin's rewrite (`docs/design/klib-composable-and-defaults.md`);
- each screen is drawn offscreen and compared pixel for pixel with the same composables drawn from
  Kotlin, as on the JVM.

The test sources are notebook-e2e's, with two changes in `NotebookHost`: its inputs arrive as
environment variables set by the test task (`NOTEBOOK_E2E_*`) instead of JVM system properties,
and the check that the wheel was unpacked runs in Python.

## Running

Build the wheel as notebook-e2e's README says, then:

```bash
./gradlew :python-multiplatform-compose:fixtures:notebook-native:macosArm64Test --rerun \
    -PpythonxComposeWheel=.cache/pythonx-compose-dist --console=plain > .cache/notebook-native.log 2>&1; echo "EXIT=$?"
```

The inputs (`-PpythonxComposeWheel`, `-PpythonxComposeVersion`, `-PnotebookE2e.skip=true`) and the
rule that a missing wheel is a failure, not a skip, are notebook-e2e's. Linking libpython 3.15t and
`PYTHONHOME` for the test task come from the bindings plugin (SPEC L-9c).

## Targets

| Target | State |
|---|---|
| `macosArm64` | **blocked at link**: the scenarios compile (CI compiles them), but the only `material-icons-core` klib, JetBrains' 1.7.3 (ABI 1.8.0, Kotlin 1.9.24), is not linked by Kotlin/Native 2.4, so every icons symbol is undefined (batch run 44989836640). Unblocked by the fork publishing `material-icons-core` built with its toolchain. |
| `linuxX64` | **blocked**: every scenario runs cell 5, which imports `DefaultIcons` (`Icons.Default`), and `material-icons-core` has no linuxX64 klib. JetBrains' last release (1.7.3) stops at macOS, iOS, JS and wasm, and the Compose fork publishes none. The target is added here once the fork publishes `material-icons-core` for linuxX64. |
| `mingwX64` | blocked on the same klib (the scenes are offscreen, so the missing Windows window layer does not matter here); it also needs #521's mingwX64 library target |
