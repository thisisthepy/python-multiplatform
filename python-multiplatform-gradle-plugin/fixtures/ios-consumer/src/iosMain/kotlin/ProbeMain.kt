package fixture.iosconsumer

import python.multiplatform.ffi.Python3

private const val PY_EVAL_INPUT: Int = 258

/**
 * Called from the Xcode app's `App.init`. Prints one line a launcher can grep:
 *
 *     IOS_CONSUMER_PROBE stdlib=ok ext=ok payload=ok
 *
 * `stdlib` is a pure-Python stdlib module out of the app's `python-multiplatform-home/`, `ext` is an
 * extension module (`_json`, a `.fwork` placeholder that must resolve to a framework) and `payload`
 * is `probe_payload` out of `<app>/python/`. The expression names every import so that a failure
 * reports which one.
 */
fun runProbe() {
    val line = try {
        Python3.initialize()
        val globals = Python3.import("__main__").dict
        val expression =
            "'IOS_CONSUMER_PROBE stdlib=' + ('ok' if __import__('json').dumps([1]) == '[1]' else 'bad')" +
                " + ' ext=' + ('ok' if __import__('_json').__file__.endswith('.fwork') else 'bad')" +
                " + ' payload=' + __import__('probe_payload').status()"
        "${Python3.eval(expression, PY_EVAL_INPUT, globals, globals)}"
    } catch (t: Throwable) {
        "IOS_CONSUMER_PROBE THREW ${t::class.simpleName}: ${t.message}"
    }
    println(line)
}
