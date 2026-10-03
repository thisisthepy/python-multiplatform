package org.thisisthepy.python.multiplatform.demo

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import org.thisisthepy.python.multiplatform.demo.ui.App

/**
 * `./gradlew :sample:run`.
 *
 * The interpreter comes up before the window because every section of the screen reads from it;
 * `build.gradle.kts` gives this task the `PYTHONHOME` that `Py_Initialize` needs to find a
 * stdlib, and the same prefix is where `manager.loadLibPython` finds `libpython` when the library
 * is on the classpath as class directories rather than as `desktopJar`.
 *
 * This used to be a page of raw `PyLong_FromLongLong` / `PyRun_SimpleString` calls, including a
 * pointer round-tripped through a `Double`. None of that is how the library is meant to be used
 * any more, and one of those calls (`PyRun_SimpleString`) swallows the error indicator, which is
 * exactly what `Python3.exec` exists to avoid.
 */
fun main() = application {
    PythonDemo.start()

    // Echoed to stdout as well as to the window: `:sample:run` is the only desktop check there
    // is, and a Compose window cannot be read by a build log.
    println("runtime : ${PythonDemo.runtimeSummary()}")
    println("eval    : ${PythonDemo.DEFAULT_EXPRESSION} -> ${PythonDemo.evaluate(PythonDemo.DEFAULT_EXPRESSION)}")
    println("table   : ${UpcallDemo.tableSummary()}")
    println("upcall  : ${UpcallDemo.callFromPython()}")
    // Sections 5-7. The screen runs these on a button press; echoing them here is what makes
    // `:sample:run` able to say whether they worked, since a window cannot be read by a build log.
    println("proxies : ${PythonDemo.proxyInstallReport()}")
    println(PythonDemo.classProxy())
    println(PythonDemo.staticSurface())
    println(PythonDemo.awaitFastPath())
    println(PythonDemo.awaitSuspending())

    Window(
        onCloseRequest = { exitApplication() },
        title = "Python Multiplatform",
    ) {
        App()
    }
}
