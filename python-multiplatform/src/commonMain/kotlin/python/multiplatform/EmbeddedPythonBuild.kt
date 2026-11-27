package python.multiplatform


/**
 * Whether the CPython this platform embeds is a free-threaded build (PEP 703, `Py_GIL_DISABLED`).
 *
 * A property of the platform, not of the whole build. The maintainer's decision on issue #158
 * (2026-10-04): "Free-threaded is the default and the only build. The GIL build is not kept as an
 * option. Baseline is CPython 3.15t." The desktop half landed first (leader, 2026-11-13: "flip the
 * desktop default to 3.15t now, before the #377 alpha03 release"); Android, androidNative and iOS
 * followed under #328 with the project's own 3.15t builds (release `cpython-<version>t-mobile`; leader
 * 2026-11-13: no opt-in flag on mobile). wasm followed under #175 with the project's own 3.15t
 * built with pthreads (`scripts/wasm/build-cpython.sh`; leader, Decision 10 (a) a1, 2026-11-26). So:
 *
 * | Source set          | Targets                         | Embeds        |
 * |---------------------|---------------------------------|---------------|
 * | `desktopMain`       | desktop JVM                     | 3.15t         |
 * | `desktopNativeMain` | `macosArm64`, `linuxX64`        | 3.15t         |
 * | `androidMain`       | Android (ART)                   | 3.15t         |
 * | `artMain`           | `androidNativeArm64`, `X64`     | 3.15t         |
 * | `iosMain`           | iOS                             | 3.15t         |
 * | `wasmJsMain`        | `wasmJs`                        | 3.15t         |
 *
 * It used to be one `BuildConfig.pythonFreeThreaded` shared by every target, which could not
 * express the transition: flipping it for desktop made Android look for `lib/python3.15t`, a
 * directory its GIL prefix did not have. Every platform is free-threaded since #175, and it stays
 * an `expect` for now (agent proposal, not confirmed: collapse it to a constant in a follow-up;
 * the desktop and wasm cycle-collection tests still branch on it). A plain (non-`inline`) `expect val`, so the
 * intermediate-source-set crash in `commonMain/README.md` does not apply.
 *
 * Everything CPython names after itself follows from this through [Versions.abiFlags]: the shared
 * library (`libpython3.15t`), the stdlib directory (`lib/python3.15t/`), and the PYTHONHOME check.
 */
internal expect val embedsFreeThreadedPython: Boolean
