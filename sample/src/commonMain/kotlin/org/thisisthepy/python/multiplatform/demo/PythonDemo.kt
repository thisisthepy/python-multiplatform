package org.thisisthepy.python.multiplatform.demo

import org.thisisthepy.python.multiplatform.demo.bindings.awaitFastPathDemo
import org.thisisthepy.python.multiplatform.demo.bindings.awaitSuspendingDemo
import org.thisisthepy.python.multiplatform.demo.bindings.classProxyDemo
import org.thisisthepy.python.multiplatform.demo.bindings.installPythonProxies
import org.thisisthepy.python.multiplatform.demo.bindings.staticSurfaceDemo
import python.multiplatform.currentPlatform
import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.types.basic.PyInt
import python.multiplatform.ffi.types.collections.PyList

/**
 * Everything the demo screen calls, with no Compose in sight.
 *
 * Sections 1 and 2 are pure `commonMain` against the library's object model -- that is the point
 * of them. Section 3 (upcalls) goes through [UpcallDemo], which is `expect`/`actual` because KSP
 * writes `python.multiplatform.generated.FunctionTable` into each *target's* own compilation, so
 * `commonMain` -- compiled by every target and seeing none of their generated directories --
 * cannot name it.
 */
object PythonDemo {

    /** `Py_eval_input`: compile an *expression* rather than a sequence of statements. */
    private const val PY_EVAL_INPUT: Int = 258

    /** Seeded into `__main__` by [start] so the default expression has something to chew on. */
    private val kotlinNumbers = listOf(2L, 3L, 5L, 7L, 11L)

    /** The expression the screen starts with. */
    const val DEFAULT_EXPRESSION: String = "sum(kotlin_numbers) * 2"

    private var started = false

    private var proxyReport = "not started"

    /**
     * Brings up the interpreter, publishes a Kotlin-built object into Python, and installs the
     * generated upcall table. Idempotent; the platform entry points differ in when they can call
     * it (Android has to unpack a stdlib first).
     */
    fun start() {
        if (started) return
        Python3.initialize()

        // Kotlin -> Python through the object model rather than through a formatted string: a
        // real `list` of real `int`s, handed to `__main__` as a module attribute so that ordinary
        // Python code sees it as a global.
        //
        // No `close()` anywhere below, and none is missing. ROADMAP §4 is closed: each wrapper
        // registers a cleaner in its constructor, and the collector releases the CPython
        // reference once the Kotlin object becomes unreachable.
        val numbers = PyList.fromList(kotlinNumbers.map { PyInt.from(it) })
        Python3.import("__main__").setAttr("kotlin_numbers", numbers)

        UpcallDemo.install()
        // Strictly after `install()`: the proxy module is rendered from whatever `UpcallTable` and
        // `ClassLookup` hold at the moment it runs, so installing it against an empty table would
        // generate a module with nothing in it and no error anywhere.
        proxyReport = installPythonProxies()
        started = true
    }

    /** What [installPythonProxies] said during [start] -- including "this target has no shim". */
    fun proxyInstallReport(): String = proxyReport

    /**
     * Python constructs a Kotlin object, calls a method, reads a property, writes a property, and
     * is refused the one whose Kotlin setter is `private`.
     *
     * Named differently from the top-level function it delegates to on purpose: a member with the
     * same name would shadow the import and recurse into itself, which
     * [UpcallDemo.optOutHeld]'s actuals already had to work around once.
     */
    fun classProxy(): String = classProxyDemo()

    /** The companion's properties and function, reached through the class object's metaclass. */
    fun staticSurface(): String = staticSurfaceDemo()

    /** `await` over a `suspend fun` whose body never suspends: no `Future`, no event loop. */
    fun awaitFastPath(): String = awaitFastPathDemo()

    /** `await` over one that really does, settled from a Kotlin thread. */
    fun awaitSuspending(): String = awaitSuspendingDemo()

    /** One line naming what is actually loaded, so a broken bring-up is visible immediately. */
    fun runtimeSummary(): String = buildString {
        append(Python3.version.substringBefore(' '))
        append("  ·  sys.platform=").append(Python3.platform)
        append("  ·  ").append(currentPlatform.name)
    }

    /**
     * Evaluates [expression] against `__main__`'s globals and describes the result *as Python
     * sees it* -- the type name comes from the object's own `PyType`, not from a Kotlin guess.
     */
    fun evaluate(expression: String): String = try {
        val globals = Python3.import("__main__").dict
        val result: PyObject = Python3.eval(expression, PY_EVAL_INPUT, globals, globals)
        "${result.Type.name}: $result"
    } catch (t: Throwable) {
        // A Python-level failure arrives as a PyException carrying the real exception type and
        // message: Python3.exec deliberately avoids PyErr_Print, which would swallow both.
        "${t::class.simpleName}: ${t.message}"
    }
}

/**
 * The upcall half, one `actual` per target because the generated table is per-target.
 *
 * Every member is a one-line delegation on every platform. It was not always: Android's actual
 * used to answer "unavailable" to all of them, because the bindings plugin could not be applied to
 * a module carrying an Android plugin below AGP 8.10 (ROADMAP §13). What still differs by platform
 * is *who makes the call*: desktop and Android resolve and invoke from Python, through a boundary
 * shim (`ctypes` on desktop, a `PyMethodDef` on Android and iOS); iOS's [callFromPython] makes the
 * same call from Kotlin instead, even though the shim exists here too -- see `bindings/UpcallDemo.ios.kt`.
 */
expect object UpcallDemo {

    /** Whether the generated table is reachable from this target at all. */
    val available: Boolean

    /** The Python-visible name of the entry [callFromPython] resolves. */
    val entryName: String

    fun install()

    /** Bumps the Kotlin counter Python reads back. */
    fun press()

    fun callFromPython(): String

    fun tableSummary(): String

    /** Whether `@PythonInternal` kept an annotated declaration out of the generated table. */
    fun optOutHeld(): Boolean
}
