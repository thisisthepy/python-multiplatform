package fixture.desktopnative

import python.multiplatform.ffi.Python3

private const val PY_EVAL_INPUT: Int = 258

/**
 * One line a CI step can compare:
 *
 *     DESKTOP_NATIVE_PROBE stdlib=ok ext=ok ft=ok
 *
 * `stdlib` is a pure-Python module (`json`). `ext` is an extension module loaded from the directory
 * CPython keeps them in, `lib-dynload` on POSIX and `DLLs` on Windows (whichever of the two is on
 * `sys.path`): the first file there with an extension-module suffix
 * (`importlib.machinery.EXTENSION_SUFFIXES`), imported, its `__file__` such a file. `ft` is the
 * free-threaded build running without a GIL (`Py_GIL_DISABLED` is 1 and `sys._is_gil_enabled()` is
 * false). Every name is imported inside the expression, so a failure says which.
 *
 * Not `_json`: python-build-standalone links most extension modules into libpython itself
 * (`_json` is in `sys.builtin_module_names` and has no `__file__`; batch run 44945907475). Its
 * `lib-dynload` holds the few that stay shared, `_dbm` and `_tkinter` in 3.15.0+20261013.
 */
fun probeLine(): String = try {
    Python3.initialize()
    val globals = Python3.import("__main__").dict
    val expression =
        "'DESKTOP_NATIVE_PROBE stdlib=' + ('ok' if __import__('json').dumps([1]) == '[1]' else 'bad')" +
            " + ' ext=' + ('ok' if __import__(sorted(n for d in __import__('sys').path" +
            " if __import__('os').path.basename(d) in ('lib-dynload', 'DLLs') and __import__('os').path.isdir(d)" +
            " for n in __import__('os').listdir(d)" +
            " if n.endswith(tuple(__import__('importlib.machinery').machinery.EXTENSION_SUFFIXES)))[0].split('.')[0])" +
            ".__file__.endswith(tuple(__import__('importlib.machinery').machinery.EXTENSION_SUFFIXES)) else 'bad')" +
            " + ' ft=' + ('ok' if __import__('sysconfig').get_config_var('Py_GIL_DISABLED') == 1" +
            " and not __import__('sys')._is_gil_enabled() else 'bad')"
    "${Python3.eval(expression, PY_EVAL_INPUT, globals, globals)}"
} catch (t: Throwable) {
    "DESKTOP_NATIVE_PROBE THREW ${t::class.simpleName}: ${t.message}"
}

fun main() {
    println(probeLine())
}
