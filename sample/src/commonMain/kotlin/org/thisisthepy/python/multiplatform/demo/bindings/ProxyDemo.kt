package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3

/**
 * The half of the boundary this sample never exercised: **Python driving a Kotlin class**.
 *
 * Sections 3 and 4 of the demo screen reach the raw table -- a name resolved to a handle, a handle
 * invoked with a tuple. That is the floor of the design, and it was all the sample could show
 * because `DemoCounter` is an `object`: no constructor, no receiver, no companion, nothing
 * suspending. Everything `python-multiplatform` grew on top of that floor
 * ([python.multiplatform.ffi.upcall.PythonProxySource]) had no caller outside the library's own
 * `desktopTest`, which is exactly the position the sample was in before ROADMAP §13 -- and §13 is
 * the section that records four defects found by giving that code a real consumer.
 *
 * The Python below is written the way a user would write it. There is no `_pm_invoke` in it, no
 * handle arithmetic and no tuple building: `Greeter('Kotlin')` constructs, `g.greet(2)` calls,
 * `g.subject = 'Python'` assigns, `Greeter.built` reads a companion property, and
 * `await g.greetNow(1)` awaits. The generated proxy module is what turns each of those into the
 * raw call underneath.
 *
 * Every function here needs [installPythonProxies] to have succeeded first, and says so rather
 * than raising if it has not -- a demo screen that throws tells the reader less than one that
 * reports which half is missing.
 */

/** `Py_eval_input`: compile an *expression* rather than a sequence of statements. */
private const val PY_EVAL_INPUT: Int = 258

/**
 * The Python module [Greeter] and the functions in this file land in: a Kotlin package name is a
 * Python module name, unchanged. Written out as a constant because the demo's Python source has to
 * spell it, and a typo would read as "the proxy was never generated".
 */
internal const val BINDINGS_MODULE: String = "org.thisisthepy.python.multiplatform.demo.bindings"

/**
 * Binds whatever raw entry points this target can offer into `__main__`, then installs the
 * generated proxy module over them.
 *
 * `expect`/`actual` for the same reason [callKotlinFromPython] is: the bootstrap -- getting the
 * addresses of `_pm_resolve` and `_pm_invoke` into the interpreter -- is the one part of the upcall
 * path that is not `commonMain`. Everything after it, including all of the Python below, is shared.
 *
 * @return a one-line description of what happened, suitable for putting on screen. Targets with no
 *   boundary shim say so instead of failing.
 */
expect fun installPythonProxies(): String

/**
 * Awaits [Greeter.greetLater], whose Kotlin body really does suspend, while something on the Kotlin
 * side resumes it.
 *
 * `expect`/`actual` because the *resumption* has to come from a thread the interpreter is not
 * running on, and this sample has no common way to start one. [awaitFastPathDemo] below is the
 * shared half of the same question and needs no thread at all.
 */
expect fun awaitSuspendingDemo(): String

/** Evaluates [expression] against `__main__` and returns `str()` of the result. */
internal fun evalToString(expression: String): String {
    val globals = Python3.import("__main__").dict
    return Python3.eval(expression, PY_EVAL_INPUT, globals, globals).toString()
}

/**
 * Constructs a Kotlin object from Python, calls a method on it, and reads and writes its
 * properties -- including the one whose Kotlin setter is `private`.
 */
fun classProxyDemo(): String = runPython(
    """
    from $BINDINGS_MODULE import Greeter

    _pm_demo_cls = {}
    g = Greeter('Kotlin')
    _pm_demo_cls['greet'] = g.greet(2)
    _pm_demo_cls['subject'] = g.subject
    g.subject = 'Python'
    _pm_demo_cls['after_assign'] = g.greet(1)
    _pm_demo_cls['greetings'] = g.greetings
    try:
        g.greetings = 99
        _pm_demo_cls['private_set'] = 'assignment SUCCEEDED -- private set leaked'
    except AttributeError:
        _pm_demo_cls['private_set'] = 'AttributeError (private set held)'
    """,
) {
    listOf(
        "Greeter('Kotlin').greet(2)  ->  ${evalToString("_pm_demo_cls['greet']")}",
        "g.subject                   ->  ${evalToString("_pm_demo_cls['subject']")}",
        "g.subject = 'Python'; greet ->  ${evalToString("_pm_demo_cls['after_assign']")}",
        "g.greetings                 ->  ${evalToString("_pm_demo_cls['greetings']")}  (2 + 1, counted in Kotlin)",
        "g.greetings = 99            ->  ${evalToString("_pm_demo_cls['private_set']")}",
    ).joinToString("\n")
}

/**
 * Reads and writes the companion's properties through the class object, calls its function, and
 * checks that an instance can reach neither -- which is Kotlin's own rule for a companion member.
 */
fun staticSurfaceDemo(): String = runPython(
    """
    from $BINDINGS_MODULE import Greeter

    _pm_demo_st = {'punctuation': Greeter.PUNCTUATION, 'built': Greeter.built}
    Greeter.built = 100
    _pm_demo_st['after_assign'] = Greeter.built
    _pm_demo_st['forgot'] = Greeter.forget()
    _pm_demo_st['after_forget'] = Greeter.built
    try:
        Greeter.PUNCTUATION = '?'
        _pm_demo_st['const'] = 'assignment SUCCEEDED -- the val is writable'
    except AttributeError:
        _pm_demo_st['const'] = 'AttributeError (companion val is read-only)'
    try:
        _pm_demo_st['on_instance'] = 'instance saw it: ' + str(Greeter('x').built)
    except AttributeError:
        _pm_demo_st['on_instance'] = 'AttributeError (companion is class-only, as in Kotlin)'
    """,
) {
    listOf(
        "Greeter.PUNCTUATION         ->  ${evalToString("_pm_demo_st['punctuation']")}",
        "Greeter.built               ->  ${evalToString("_pm_demo_st['built']")}",
        "Greeter.built = 100         ->  ${evalToString("_pm_demo_st['after_assign']")}",
        "Greeter.forget()            ->  ${evalToString("_pm_demo_st['forgot']")}, then built=${evalToString("_pm_demo_st['after_forget']")}",
        "Greeter.PUNCTUATION = '?'   ->  ${evalToString("_pm_demo_st['const']")}",
        "Greeter('x').built          ->  ${evalToString("_pm_demo_st['on_instance']")}",
    ).joinToString("\n")
}

/**
 * `await` over a `suspend fun` whose body never reaches a suspension point.
 *
 * The call site is an ordinary `await`, and the assertion worth making is what did *not* happen:
 * `create_future` is counted, and a fast-path call must not construct one. That is a stronger
 * statement than "no Future came back" -- it says the event loop was never involved at all.
 */
fun awaitFastPathDemo(): String = runPython(
    """
    import asyncio
    from $BINDINGS_MODULE import Greeter

    _pm_demo_fast = {'created': 0}
    _pm_demo_fast['loop'] = asyncio.new_event_loop()
    _pm_demo_fast['orig'] = _pm_demo_fast['loop'].create_future


    def _pm_demo_count_futures():
        _pm_demo_fast['created'] += 1
        return _pm_demo_fast['orig']()


    _pm_demo_fast['loop'].create_future = _pm_demo_count_futures
    _pm_demo_fast['g'] = Greeter('fast path')
    try:
        _pm_demo_fast['value'] = _pm_demo_fast['loop'].run_until_complete(
            _pm_demo_fast['g'].greetNow(1)
        )
    finally:
        _pm_demo_fast['loop'].close()
    """,
) {
    val created = evalToString("_pm_demo_fast['created']")
    listOf(
        "await g.greetNow(1)         ->  ${evalToString("_pm_demo_fast['value']")}",
        "asyncio Futures created     ->  $created  " +
            if (created == "0") "(fast path: the loop was never involved)" else "(NOT the fast path)",
    ).joinToString("\n")
}

/**
 * Runs [source], then [describe], reporting a Python-level failure rather than throwing it.
 *
 * A `PyException` carries the real Python exception type and message
 * ([python.multiplatform.ffi.Python3.exec] deliberately avoids `PyErr_Print`, which swallows both),
 * so putting it on screen says more than a stack trace would.
 */
private fun runPython(source: String, describe: () -> String): String = try {
    Python3.exec(source.trimIndent())
    describe()
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}
