package fixture.iosconsumer

import python.multiplatform.ffi.Python3

private const val PY_EVAL_INPUT: Int = 258

/**
 * Called from the Xcode app's `App.init`. Prints one line a launcher can grep:
 *
 *     IOS_CONSUMER_PROBE stdlib=ok ext=ok payload=ok ft=ok
 *
 * `stdlib` is a pure-Python stdlib module out of the app's `python-multiplatform-home/`, `ext` is an
 * extension module (`_json`, a `.fwork` placeholder that must resolve to a framework) and `payload`
 * is `probe_payload` out of `<app>/python/`. `ft` is the free-threaded 3.15t build the plugin staged
 * (#328): `Py_GIL_DISABLED` is 1 and `sys.abiflags` is `t`, so a GIL framework or a GIL stdlib
 * (`lib/python3.15/`) reports `bad`. The expression names every import so that a failure
 * reports which one.
 */
fun runProbe() {
    val line = try {
        Python3.initialize()
        val globals = Python3.import("__main__").dict
        val expression =
            "'IOS_CONSUMER_PROBE stdlib=' + ('ok' if __import__('json').dumps([1]) == '[1]' else 'bad')" +
                " + ' ext=' + ('ok' if __import__('_json').__file__.endswith('.fwork') else 'bad')" +
                " + ' payload=' + __import__('probe_payload').status()" +
                " + ' ft=' + ('ok' if __import__('sysconfig').get_config_var('Py_GIL_DISABLED') == 1" +
                " and __import__('sys').abiflags == 't' else 'bad')"
        "${Python3.eval(expression, PY_EVAL_INPUT, globals, globals)}"
    } catch (t: Throwable) {
        "IOS_CONSUMER_PROBE THREW ${t::class.simpleName}: ${t.message}"
    }
    println(line)
}
