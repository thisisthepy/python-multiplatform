package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.window.ComposeUIViewController
import org.thisisthepy.python.multiplatform.demo.ui.App


/**
 * The interpreter is brought up *before* the first composition rather than from inside it. The
 * old form passed `{ Python3.initialize() }` as `App`'s content lambda, so initialisation ran
 * from inside a composable body -- on every recomposition, and only once the user had expanded
 * the section that hosted it.
 *
 * On iOS the shipped framework carries no stdlib. An app built through `iosApp/`'s "Install Python
 * Standard Library and Payload" phase carries one in its bundle and the library finds it there
 * (SPEC L-10); see `python-multiplatform/src/iosMain/README.md`.
 */
fun MainViewController() = run {
    // Outside the content lambda on purpose: that lambda is `@Composable`, so anything in it runs
    // again on every recomposition.
    PythonDemo.start()
    dumpDemoSections()
    ComposeUIViewController { App() }
}

/**
 * Prints what each of `App`'s seven cards *would* show, to stdout.
 *
 * Sections 2 and 5-7 are behind buttons, and a simulator launched by `simctl` has nobody to press
 * them: `simctl` can install and launch, but it cannot tap. Without this the only sections a run
 * can be said to have exercised are the four that render eagerly, which is how "iOS compiles" and
 * "iOS runs" stayed indistinguishable for as long as they did. The UI is untouched -- this prints
 * the same calls the buttons make, so a divergence between platforms shows up in a log rather than
 * only under someone's thumb.
 *
 * Each line is caught separately on purpose. These sections fail independently on other targets
 * already (section 7's slow path is unwired here by design), and one throwing must not hide the
 * six that would have answered.
 */
private fun dumpDemoSections() {
    fun line(label: String, body: () -> String) {
        val value = try {
            body()
        } catch (t: Throwable) {
            "THREW ${t::class.simpleName}: ${t.message}"
        }
        println("DEMO $label | $value")
    }

    println("DEMO ---- begin ----")
    line("1 runtime") { PythonDemo.runtimeSummary() }
    line("2 eval") { PythonDemo.evaluate(PythonDemo.DEFAULT_EXPRESSION) }
    line("3 upcall.available") { UpcallDemo.available.toString() }
    line("3 upcall.entry") { UpcallDemo.entryName }
    line("3 upcall.press") { UpcallDemo.press(); "pressed" }
    line("3 upcall.call") { UpcallDemo.callFromPython() }
    line("4 table") { UpcallDemo.tableSummary() }
    line("4 optOutHeld") { UpcallDemo.optOutHeld().toString() }
    line("5 proxyInstall") { PythonDemo.proxyInstallReport() }
    line("5 classProxy") { PythonDemo.classProxy() }
    line("6 staticSurface") { PythonDemo.staticSurface() }
    line("7 awaitFast") { PythonDemo.awaitFastPath() }
    line("7 awaitSuspending") { PythonDemo.awaitSuspending() }
    // SPEC L-10 (issue #59): a stdlib module, a stdlib extension module (a .fwork framework), and the
    // payload's module, all read out of the installed bundle -- docs/platforms/ios-app-bundle.md.
    line("8 bundle") {
        PythonDemo.evaluate(
            "(__import__('json').dumps(__import__('example_py').greeting()), __import__('_json').__file__, " +
                "__import__('example_py').__file__, __import__('sys').prefix, __import__('sys').executable)"
        )
    }
    println("DEMO ---- end ----")
}
