package fixture

import python.multiplatform.ffi.PyObject
import python.multiplatform.ffi.Python3

/** `Py_eval_input`. */
private const val PY_EVAL_INPUT: Int = 258

/**
 * The DEMO 8 probe of `:sample` (docs/platforms/ios-app-bundle.md), from a consumer built only
 * against published artifacts: a stdlib module, a stdlib extension module (a `.fwork` framework) and
 * the payload's module, all read out of the installed bundle. Printed for `simctl launch
 * --console-pty`, and returned for the screen.
 */
fun runProbe(): String {
    val lines = mutableListOf<String>()
    fun line(label: String, body: () -> String) {
        val value = try {
            body()
        } catch (t: Throwable) {
            "THREW ${t::class.simpleName}: ${t.message}"
        }
        println("DEMO $label | $value")
        lines += "$label | $value"
    }

    println("DEMO ---- begin ----")
    line("0 initialize") { Python3.initialize(); "ok" }
    line("1 runtime") { Python3.version.substringBefore(' ') + "  ·  sys.platform=" + Python3.platform }
    line("8 bundle") {
        evaluate(
            "(__import__('json').dumps(__import__('example_py').greeting()), __import__('_json').__file__, " +
                "__import__('example_py').__file__, __import__('sys').prefix, __import__('sys').executable)",
        )
    }
    println("DEMO ---- end ----")
    return lines.joinToString("\n")
}

private fun evaluate(expression: String): String {
    val globals = Python3.import("__main__").dict
    val result: PyObject = Python3.eval(expression, PY_EVAL_INPUT, globals, globals)
    return "${result.Type.name}: $result"
}
