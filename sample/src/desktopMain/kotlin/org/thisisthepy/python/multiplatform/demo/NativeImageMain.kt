package org.thisisthepy.python.multiplatform.demo

import org.thisisthepy.python.multiplatform.demo.bindings.DemoCounter
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_ARGS_ENTRY_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_ENTRY_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.UPCALL_EXCLUDED_NAME
import org.thisisthepy.python.multiplatform.demo.bindings.awaitFastPathDemo
import org.thisisthepy.python.multiplatform.demo.bindings.awaitSuspendingDemo
import org.thisisthepy.python.multiplatform.demo.bindings.classProxyDemo
import org.thisisthepy.python.multiplatform.demo.bindings.installCtypesBridge
import org.thisisthepy.python.multiplatform.demo.bindings.installGeneratedUpcallTable
import org.thisisthepy.python.multiplatform.demo.bindings.installPythonProxies
import org.thisisthepy.python.multiplatform.demo.bindings.staticSurfaceDemo
import org.thisisthepy.python.multiplatform.demo.bindings.upcallTableSummary
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.withGIL
import python.native.ffi.PyRun_SimpleString
import python.native.ffi.UpcallStub
import kotlin.system.exitProcess

/** How many times [DemoCounter.press] runs before Python is asked what the count is. */
private const val PRESSES = 7

/**
 * A headless entry point for the GraalVM native-image build path. `main.kt`'s `fun main() =
 * application { ... }` requires a running AWT/Compose window, which is not what this build path
 * is verifying -- it is verifying that Python can resolve a Kotlin declaration by name and call
 * it, inside a closed-world binary. Kept separate so a Compose window is never a precondition
 * for that question.
 *
 * The table this resolves against is **generated**: [installGeneratedUpcallTable] installs
 * `python.multiplatform.generated.FunctionTable`, which `python-multiplatform-ksp` emitted from
 * `ExposedToPython.kt` because `build.gradle.kts` applies the bindings plugin. This file used to
 * carry a hand-written `FunctionTableFragment` with a comment saying the generator did not exist
 * yet; a hand-written fragment keeps passing whether or not KSP ran at all, so what the
 * native-image check measured was narrower than what it appeared to measure.
 *
 * [DemoCounter.press] is called [PRESSES] times first, so the number Python reads back is one
 * this process produced rather than a constant a stub could also have returned.
 */
fun main() {
    println("=== GraalVM native-image upcall verification ===")

    Python3.initialize()

    installGeneratedUpcallTable()
    println("KOTLIN: table = ${upcallTableSummary()}")
    repeat(PRESSES) { DemoCounter.press() }

    val resolveAddr = UpcallStub.resolveHandleStubAddr
    val invokeAddr = UpcallStub.invokeHandleStubAddr
    val invokeWithArgsAddr = UpcallStub.invokeWithArgsStubAddr
    println("KOTLIN: resolve stub @ 0x${resolveAddr.toString(16)}")
    println("KOTLIN: invoke  stub @ 0x${invokeAddr.toString(16)}")
    println("KOTLIN: args    stub @ 0x${invokeWithArgsAddr.toString(16)}")

    // Python resolves the entry to a handle by name exactly once, then calls back through that
    // handle -- the ObjC-selector-cache shape docs/design/upcall-design.md argues for, reached here via
    // ctypes function pointers instead of a generated proxy type.
    // Not a tty under Gradle/native-image, so sys.stdout is block-buffered: without an explicit
    // flush before the process exits (there is no Py_Finalize call on this path), every print()
    // below is silently lost even on success.
    val script = """
        import sys
        import ctypes

        try:
            resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)($resolveAddr)
            invoke = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_long)($invokeAddr)
            invoke_args = ctypes.CFUNCTYPE(
                ctypes.py_object, ctypes.c_long, ctypes.py_object
            )($invokeWithArgsAddr)

            handle = resolve(b"$UPCALL_ENTRY_NAME")
            print("PYTHON: resolved handle =", handle)
            assert handle != -1, "name lookup failed"

            result = invoke(handle)
            print("PYTHON: invoke result =", result)
            assert result == $PRESSES, f"expected $PRESSES, got {result}"

            # The argument-carrying trampoline, in the same closed world: a str and an int in,
            # a str back. This is the descriptor that has to be in reachability-metadata.json --
            # under native-image an undeclared one dies here with MissingForeignRegistrationError,
            # not at build time.
            args_handle = resolve(b"$UPCALL_ARGS_ENTRY_NAME")
            print("PYTHON: resolved args handle =", args_handle)
            assert args_handle != -1, "argument-taking name lookup failed"

            described = invoke_args(args_handle, ("presses x3 = ", 3))
            print("PYTHON: invoke_args result =", repr(described))
            assert described == "presses x3 = ${PRESSES * 3}", f"got {described!r}"

            excluded = resolve(b"$UPCALL_EXCLUDED_NAME")
            print("PYTHON: @PythonInternal entry resolves to", excluded)
            assert excluded == -1, "an opted-out declaration reached the table"

            print("PYTHON: UPCALL_OK")
        finally:
            sys.stdout.flush()
            sys.stderr.flush()
    """.trimIndent()

    val status = withGIL { PyRun_SimpleString(script) }
    if (status != 0) {
        println("KOTLIN: PyRun_SimpleString reported failure (status=$status)")
        exitProcess(1)
    }
    println("KOTLIN: upcall verification finished")

    verifyGeneratedProxies()
}

/**
 * The second half: the *generated Python proxy module* inside the same closed-world binary.
 *
 * Everything above reaches the boundary the way a test harness does -- a name to a handle, a handle
 * to a tuple. What a user writes instead is `Greeter('x').greet(2)`, and that goes through Python
 * source `PythonProxySource.render` produces at run time and `exec`s. That is the part with a
 * native-image question attached, and it is not the obvious one: the source is generated from a
 * table that is already statically emitted, so no reflection is reintroduced. What *is* new here is
 * `_pm_release` and `_pm_cancel` -- two more Panama upcall stub shapes than the first half binds --
 * plus `asyncio`, a thread, and `PyMethodDef`-free `ctypes` callbacks fired from the interpreter.
 * A missing descriptor in `reachability-metadata.json` shows up at the first call as
 * `MissingForeignRegistrationError` and never at build time, which is why this runs rather than
 * merely compiling.
 *
 * Each check is asserted in Kotlin rather than in the script, because the reports are strings the
 * demo screen also shows: one place decides what "correct" is.
 */
private fun verifyGeneratedProxies() {
    println("=== generated proxy verification ===")

    installCtypesBridge()
    val installed = installPythonProxies()
    println("KOTLIN: $installed")
    mustBe(installed.startsWith("installed:"), "the proxy module did not install: $installed")

    val classReport = classProxyDemo()
    println(classReport)
    mustBe(classReport.contains("hello Kotlin! hello Kotlin!"), "the constructor or the method did not run")
    mustBe(classReport.contains("hello Python!"), "assigning `subject` from Python did not reach Kotlin")
    mustBe(classReport.contains("private set held"), "a `private set` was writable from Python")

    val staticReport = staticSurfaceDemo()
    println(staticReport)
    mustBe(staticReport.contains("Greeter.built               ->  1\n"), "the companion property did not read 1")
    mustBe(staticReport.contains("Greeter.built = 100         ->  100"), "the companion setter did not reach Kotlin")
    mustBe(staticReport.contains("Greeter.forget()            ->  100"), "the companion function did not run")
    mustBe(staticReport.contains("companion val is read-only"), "a companion `val` was writable from Python")
    mustBe(staticReport.contains("companion is class-only"), "an instance reached a companion member")

    val fastReport = awaitFastPathDemo()
    println(fastReport)
    mustBe(fastReport.contains("hello fast path!"), "the awaited fast-path call did not return")
    mustBe(fastReport.contains("Futures created     ->  0"), "the fast path built a Future")

    val slowReport = awaitSuspendingDemo()
    println(slowReport)
    mustBe(slowReport.contains("hello slow path! hello slow path!"), "the awaited suspending call did not return")
    mustBe(slowReport.contains("completer thread            ->  clean"), "the Kotlin completer thread failed")
    mustBe(!slowReport.contains("Futures created     ->  0"), "the suspending call never reached the Future path")

    println("PYTHON: PROXY_OK")
}

/** Prints and exits non-zero, so a failure here cannot be read as a passing build. */
private fun mustBe(condition: Boolean, message: String) {
    if (!condition) {
        println("KOTLIN: FAILED -- $message")
        exitProcess(1)
    }
}
