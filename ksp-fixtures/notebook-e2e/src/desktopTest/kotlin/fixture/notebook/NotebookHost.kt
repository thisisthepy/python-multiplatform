package fixture.notebook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import python.multiplatform.compose.PythonContent
import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.pythonx.PythonxAdapter
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.upcall.UpcallBootstrap
import python.multiplatform.generated.FunctionTable
import python.multiplatform.generated.artifacts.ArtifactTable
import python.multiplatform.reflection.UpcallTable
import kotlin.test.fail

/**
 * The host the notebook talks to: one interpreter, the pythonx-compose **wheel** on `sys.path`, and a
 * scene drawing `PythonContent("pythonx.compose.runtime", "app_root")` -- the line pythonx-compose's
 * SPEC S5.4 says a host is configured with.
 *
 * The interpreter and the upcall table are installed once per test JVM and never cleared: a
 * `pythonx.compose.*` module keeps the names it resolved in its own dict, and reinstalling the table
 * would invalidate the proxies behind them. What a notebook session would carry between cells is reset
 * per test instead ([install] calls [reset]): a fresh `app_root` state and a fresh `import main`.
 *
 * Python code runs in `__main__`, as a notebook kernel's cells do.
 */
internal object NotebookHost {

    /** `Py_eval_input`. */
    private const val PY_EVAL_INPUT = 258

    /** The upper bound on frames a change may take to reach the screen (TextFieldStateRenderTest's). */
    const val MAX_FRAMES = 4

    private var installed = false
    private var installFailure: Throwable? = null

    /** Called from every test's `@BeforeTest`. Fails -- never skips -- when the wheel is not there. */
    fun install() {
        installFailure?.let { throw AssertionError("the notebook host could not be installed: ${it.message}", it) }
        if (!installed) {
            try {
                installOnce()
            } catch (t: Throwable) {
                installFailure = t
                throw t
            }
            installed = true
        }
        reset()
    }

    private fun installOnce() {
        System.getProperty("notebookE2e.wheelError")?.let { fail("notebook-e2e: $it") }
        val sitePackages = System.getProperty("notebookE2e.sitePackages")
            ?: fail("notebook-e2e: no notebookE2e.sitePackages system property; run this through Gradle's desktopTest")
        val wheel = System.getProperty("notebookE2e.wheel") ?: "(unknown wheel)"
        val appDir = System.getProperty("notebookE2e.appDir")
            ?: fail("notebook-e2e: no notebookE2e.appDir system property; run this through Gradle's desktopTest")
        if (!java.io.File(sitePackages, "pythonx/compose/runtime/__init__.py").isFile) {
            fail("notebook-e2e: $wheel was not unpacked into $sitePackages (no pythonx/compose/runtime/__init__.py there)")
        }

        Python3.initialize(silent = true)
        UpcallTable.clear()
        UpcallTable.install(FunctionTable.fragments + ArtifactTable.fragments)
        check(UpcallBootstrap.publishToGlobals()) { "UpcallBootstrap.publishToGlobals() failed" }
        PythonProxySource.install()
        PythonxAdapter.install(setOf("androidx.compose.ui.unit.Dp"))

        Python3.exec(
            """
            import sys, os, functools
            sys.path[0:0] = [${pyLiteral(sitePackages)}, ${pyLiteral(appDir)}]

            import pythonx.compose
            import pythonx.compose.runtime as _nb_rt
            _nb_site = os.path.realpath(${pyLiteral(sitePackages)}) + os.sep
            for _nb_m in (pythonx.compose, _nb_rt):
                assert os.path.realpath(_nb_m.__file__).startswith(_nb_site), \
                    _nb_m.__name__ + ' was imported from ' + _nb_m.__file__ + ', not from the installed wheel ' + ${pyLiteral(wheel)}
            _nb_real_app = _nb_rt.app

            # `Modifier.padding(8)` starts from an empty Modifier; pythonx-compose asks the application
            # for the Kotlin function that makes one (pythonx/compose/ui/modifier.py).
            import pythonx.compose.ui.modifier as _nb_modifier
            _nb_modifier.install('fixture.notebook.emptyModifier')

            # A counter of every Python function that starts while it is on (sys.monitoring, every
            # thread). The callback and both switches are C callables, so turning it on and off from
            # Kotlin runs no Python bytecode of its own and records nothing.
            _nb_tool = next(_i for _i in range(6) if sys.monitoring.get_tool(_i) is None)
            sys.monitoring.use_tool_id(_nb_tool, 'notebook-e2e')
            _nb_started = {}
            sys.monitoring.register_callback(_nb_tool, sys.monitoring.events.PY_START, _nb_started.__setitem__)
            _nb_monitor_on = functools.partial(sys.monitoring.set_events, _nb_tool, sys.monitoring.events.PY_START)
            _nb_monitor_off = functools.partial(sys.monitoring.set_events, _nb_tool, 0)
            """.trimIndent(),
        )
    }

    /**
     * What a fresh notebook session sees after cell 6 (`import main`): a new root state, the real `@app`
     * (a negative test may have stubbed it), and `main` imported again so its state starts over.
     */
    fun reset() {
        Python3.exec(
            """
            _nb_monitor_off()
            _nb_rt.app = _nb_real_app
            _nb_rt.__dict__.pop('app_root', None)
            sys.modules.pop('main', None)
            import main
            """.trimIndent(),
        )
    }

    /** Runs a notebook cell in `__main__`. */
    fun cell(source: String) = Python3.exec(source.trimIndent())

    fun pyStr(expression: String): String = Python3.import("__main__").getAttr("__dict__").let { globals ->
        Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
    }

    fun pyInt(expression: String): Int = pyStr(expression).toInt()

    /** The host: `PythonContent` reading pythonx-compose's root state. */
    fun hostScene(width: Int, height: Int): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f)) {
            PythonContent(module = "pythonx.compose.runtime", attribute = "app_root")
        }

    /** What [content] draws from Kotlin, the control a Python-drawn screen is compared with. */
    fun controlPixels(width: Int, height: Int, content: @Composable () -> Unit): IntArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f), content = content)
        try {
            return pixelsOf(scene.render())
        } finally {
            scene.close()
        }
    }

    /**
     * What a window's frame clock does on its own: deliver the snapshot's pending writes, then draw.
     * Nothing else -- no host call -- happens between a Python write and the frame that shows it.
     */
    fun nextFrame(render: () -> Image): IntArray {
        Snapshot.sendApplyNotifications()
        return pixelsOf(render())
    }

    /** Up to [MAX_FRAMES] frames, stopping at the first one [done] accepts. Returns it and the count. */
    fun framesUntil(render: () -> Image, done: (IntArray) -> Boolean): Pair<IntArray, Int> {
        var frames = 0
        var last: IntArray
        do {
            last = nextFrame(render)
            frames++
        } while (frames < MAX_FRAMES && !done(last))
        return last to frames
    }

    /** One redeclaration observed: the screen before the cell, after it, and what Kotlin draws for it. */
    class Redeclaration(val before: IntArray, val after: IntArray, val frames: Int, val expected: IntArray) {
        val matchesExpected: Boolean get() = after.contentEquals(expected)
        val changedPixels: Int get() = differing(before, after)
        val pixelsOffExpected: Int get() = differing(after, expected)

        fun describe(): String =
            "after $frames frame(s) $changedPixels pixels changed and $pixelsOffExpected differ from the Kotlin control " +
                "(ink before ${inkOf(before)}, after ${inkOf(after)}, control ${inkOf(expected)})"
    }

    /**
     * Runs [cellSource] -- a cell that redeclares the root -- against a live [scene], then lets up to
     * [MAX_FRAMES] frames pass. The positive tests assert [Redeclaration.matchesExpected]; the
     * negative ones disable the wiring and assert that this same observation reports no change.
     */
    fun redeclare(scene: ImageComposeScene, cellSource: String, expected: IntArray): Redeclaration {
        val before = pixelsOf(scene.render())
        cell(cellSource)
        val (after, frames) = framesUntil({ scene.render() }) { it.contentEquals(expected) }
        return Redeclaration(before, after, frames, expected)
    }

    /**
     * Runs [block] with the Python-function counter on, and returns what started: one line per code
     * object (`qualname file:line`). Empty means no Python function ran.
     */
    fun pythonFunctionsStartedDuring(block: () -> Unit): List<String> {
        cell("_nb_started.clear()")
        val main = Python3.import("__main__")
        val on = main.getAttr("_nb_monitor_on")
        val off = main.getAttr("_nb_monitor_off")
        on().close()
        try {
            block()
        } finally {
            off().close()
            on.close()
            off.close()
        }
        val count = pyInt("len(_nb_started)")
        if (count == 0) return emptyList()
        return pyStr(
            "'\\n'.join(sorted('%s %s:%d' % (_c.co_qualname, _c.co_filename, _c.co_firstlineno) for _c in _nb_started))",
        ).lines()
    }

    fun pixelsOf(image: Image): IntArray {
        val bitmap = Bitmap.makeFromImage(image)
        return IntArray(bitmap.width * bitmap.height) { bitmap.getColor(it % bitmap.width, it / bitmap.width) }
    }

    fun inkOf(pixels: IntArray): Int = pixels.count { it != 0 }

    fun differing(a: IntArray, b: IntArray): Int {
        check(a.size == b.size) { "frames of different sizes: ${a.size} vs ${b.size}" }
        return a.indices.count { a[it] != b[it] }
    }

    /** A Python string literal for [s]. */
    private fun pyLiteral(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
}
