package fixture.desktopnative

import python.multiplatform.ffi.Python3

private const val PY_EVAL_INPUT: Int = 258

/**
 * One line a CI step can compare:
 *
 *     DESKTOP_NATIVE_PROBE stdlib=ok ext=ok ft=ok
 *
 * `stdlib` is a pure-Python module (`json`), `ext` an extension module out of `lib-dynload`
 * (`_json`'s `__file__` is a shared object), `ft` the free-threaded build (`Py_GIL_DISABLED` is 1 and
 * `sys.abiflags` is `t`). Every name is imported inside the expression, so a failure says which.
 */
fun probeLine(): String = try {
    Python3.initialize()
    val globals = Python3.import("__main__").dict
    val expression =
        "'DESKTOP_NATIVE_PROBE stdlib=' + ('ok' if __import__('json').dumps([1]) == '[1]' else 'bad')" +
            " + ' ext=' + ('ok' if __import__('_json').__file__.endswith(('.so', '.dylib')) else 'bad')" +
            " + ' ft=' + ('ok' if __import__('sysconfig').get_config_var('Py_GIL_DISABLED') == 1" +
            " and __import__('sys').abiflags == 't' else 'bad')"
    "${Python3.eval(expression, PY_EVAL_INPUT, globals, globals)}"
} catch (t: Throwable) {
    "DESKTOP_NATIVE_PROBE THREW ${t::class.simpleName}: ${t.message}"
}

fun main() {
    println(probeLine())
}
