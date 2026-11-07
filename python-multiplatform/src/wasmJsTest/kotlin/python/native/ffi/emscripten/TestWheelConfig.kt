@file:JsModule("./cpython-config.mjs")

package python.native.ffi.emscripten

/**
 * Where the `KotlinJsTest` `doFirst` in `python-multiplatform/build.gradle.kts` extracted the
 * pinned wheels `python-multiplatform/scripts/wasm/build-cpython.sh wheels` downloads, or `null` when none were found.
 * Test-only: appended to the test bundle's `cpython-config.mjs`, never to a consumer's.
 */
external val PMP_TEST_SITE_PACKAGES: JsString?

/** `true` under `-PrequireWasmRuntime=true`, where a missing wheel is a failure rather than a skip. */
external val PMP_TEST_WHEELS_REQUIRED: JsBoolean?

/** The directory the build looked in for `*.whl`, for the skip and failure messages. */
external val PMP_TEST_WHEELS_DIR: JsString?
