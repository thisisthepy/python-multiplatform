package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.thisisthepy.python.multiplatform.demo.ui.App


/**
 * The interpreter comes up before the canvas for the same reason desktop's `main.kt` brings it up
 * before the window: every section of the screen reads from it. Unlike desktop, there is no
 * `PYTHONHOME` to hand it -- `PythonHomeCheck.wasmJs.kt` makes that check a no-op on this target,
 * because the interpreter's prefix is Emscripten's virtual filesystem rather than something read
 * out of the environment. What has to be true instead is that `cpython.mjs`, `python.mjs`,
 * `python.wasm` and the standard library zip are served next to this page, and that the generated
 * Kotlin/Wasm output has had two substitutions applied to it:
 * `:python-multiplatform:stageWasmBrowserRuntime` assembles the first, and this module's
 * `build.gradle.kts` does the second. ROADMAP §10 says what each file is for and why none of it
 * was there when this target was first switched on.
 *
 * Verified in a browser rather than inferred (Chromium 150, headless, software WebGL): sections 1-6
 * print, and Compose renders its canvas on the same page.
 *
 * Sections 5 and 6 (a Python-constructed Kotlin class, its companion) are new here --
 * `ProxyDemo.wasmJs.kt`'s doc says why. Section 7 (`await`) is deliberately never reached: see
 * `ui/App.kt`'s `AwaitSection`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    PythonDemo.start()
    println("runtime : ${PythonDemo.runtimeSummary()}")
    println("eval    : ${PythonDemo.DEFAULT_EXPRESSION} -> ${PythonDemo.evaluate(PythonDemo.DEFAULT_EXPRESSION)}")
    println("table   : ${UpcallDemo.tableSummary()}")
    println("upcall  : ${UpcallDemo.callFromPython()}")
    println("proxies : ${PythonDemo.proxyInstallReport()}")
    println(PythonDemo.classProxy())
    println(PythonDemo.staticSurface())
    // Deliberately not called: PythonDemo.awaitFastPath() and PythonDemo.awaitSuspending() both
    // reach `import asyncio`, which traps this wasm instance rather than raising. See
    // `ProxyDemo.wasmJs.kt`'s `awaitSuspendingDemo` doc.
    println("await   : skipped on wasmJs -- import asyncio traps this target, see ProxyDemo.wasmJs.kt")

    ComposeViewport(document.body!!) {
        App()
    }
}
