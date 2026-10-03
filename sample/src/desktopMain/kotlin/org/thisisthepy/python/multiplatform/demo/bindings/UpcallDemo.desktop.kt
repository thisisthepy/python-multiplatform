package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.withGIL
import python.native.ffi.UpcallStub

/** `Py_eval_input`: compile an expression rather than a sequence of statements. */
private const val PY_EVAL_INPUT: Int = 258

/**
 * Resolve once, invoke many. Both handles are computed here and live on in `__main__`, so every
 * call afterwards passes an integer and never a string again -- which is the whole argument of
 * `docs/design/upcall.md` (a selector is fast because it is interned, not because a table
 * exists).
 *
 * `ctypes` stands in for the generated proxy type a finished binder would install. Two stub
 * shapes are in play and the difference is the point:
 *
 * - `(long) -> long` reaches zero-argument entries and carries nothing but the handle. That is
 *   all this demo could do before ROADMAP §13. Bound as `_pm_invoke0`, because the *other* one
 *   has to be called `_pm_invoke`: that is the name
 *   [python.multiplatform.ffi.upcall.PythonProxySource] generates its calls against, and its
 *   entry-point guard refuses to install a proxy module without it.
 * - `(long, PyObject *) -> PyObject *` is the argument-carrying trampoline. It is the shape a
 *   `PyCFunction` slot takes, so the same stub serves the proxy type when that lands.
 *
 * `_pm_release` and `_pm_cancel` are bound for a reason that has nothing to do with the raw calls
 * below: `PythonProxySource`'s `_pm_watch` checks for both before it will arm a cancellation
 * notice on a `Future`, and a host that has not bound them gets cancellation observed at
 * completion instead of at `Future.cancel()`. They cost one `CFUNCTYPE` each and no new stub
 * shape -- `(long) -> int` is already built for `tp_clear`.
 *
 * `internal` rather than `private` because `UpcallDemo.desktop.kt` next door calls it, and
 * `internal` rather than `public` because a `public` top-level function in this module would be
 * scanned into the generated table -- exposure is a blacklist here.
 */
internal fun installCtypesBridge() {
    val resolveAddr = UpcallStub.resolveHandleStubAddr
    val invokeAddr = UpcallStub.invokeHandleStubAddr
    val invokeWithArgsAddr = UpcallStub.invokeWithArgsStubAddr
    val releaseAddr = UpcallStub.releaseObjectStubAddr
    val cancelAddr = UpcallStub.cancelCallStubAddr
    Python3.exec(
        """
        import ctypes

        _pm_resolve = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_char_p)($resolveAddr)
        _pm_invoke0 = ctypes.CFUNCTYPE(ctypes.c_long, ctypes.c_long)($invokeAddr)
        _pm_invoke = ctypes.CFUNCTYPE(ctypes.py_object, ctypes.c_long, ctypes.py_object)(
            $invokeWithArgsAddr
        )
        _pm_release = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_long)($releaseAddr)
        _pm_cancel = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_long)($cancelAddr)
        _pm_handle = _pm_resolve(b"$UPCALL_ENTRY_NAME")
        _pm_args_handle = _pm_resolve(b"$UPCALL_ARGS_ENTRY_NAME")
        """.trimIndent(),
    )
}

actual fun callKotlinFromPython(): String = try {
    val globals = Python3.import("__main__").dict
    val handle = Python3.eval("_pm_handle", PY_EVAL_INPUT, globals, globals).toString()
    if (handle == "-1") {
        "the name was not in the table (handle -1)"
    } else {
        val value = Python3.eval("_pm_invoke0(_pm_handle)", PY_EVAL_INPUT, globals, globals)
        // The argument-carrying call: Python builds a real tuple, Kotlin reads a String and a
        // Long out of it and hands a String back.
        val described = Python3.eval(
            "_pm_invoke(_pm_args_handle, ('presses x3 = ', 3))",
            PY_EVAL_INPUT, globals, globals,
        )
        "handle $handle -> $value  ·  with args -> $described"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}

/**
 * Installs the generated proxy module over the `ctypes` bridge above.
 *
 * [PythonProxySource.install] renders Python from whatever `UpcallTable` and `ClassLookup` hold at
 * this moment, so it has to run after [installGeneratedUpcallTable] -- which is the order
 * `UpcallDemo.install()` calls them in.
 */
actual fun installPythonProxies(): String = try {
    val source = PythonProxySource.install()
    // `_PmModule` is support scaffolding and `_pm_t_N` is a generated metaclass; neither is a
    // proxy for a Kotlin type, which is what this number is meant to say.
    val classes = source.lineSequence()
        .count { it.startsWith("class ") && !it.startsWith("class _Pm") && !it.startsWith("class _pm_t_") }
    "installed: ${source.lineSequence().count()} lines of generated Python, $classes proxy classes"
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}

/**
 * `await` over a `suspend fun` that really suspends.
 *
 * The Kotlin body of [Greeter.greetLater] parks its continuation, so the boundary has nothing to
 * hand back and returns an `asyncio.Future` instead; the `await` in the generated `async def`
 * resolves once a **Kotlin thread** resumes the continuation and the completion settles that
 * Future. Which is why this cannot be `commonMain`: the resumption has to come from a thread the
 * interpreter is not running on, or the demo silently takes the fast path and proves nothing.
 *
 * The completer waits for a `Future` to have been constructed before it resumes, for the reason
 * `python-multiplatform`'s own `AsyncUpcallDeliveryTest` records: resuming while `AsyncUpcall`
 * is still deciding what to return makes `PendingCall.isDone` true early, the value is handed back
 * directly, and the run exercises the path it was written to avoid.
 *
 * Every touch of Kotlin state from the completer is inside [withGIL]. That is not decoration:
 * `commonMain/README.md` names the GIL as the barrier that makes the boundary's non-synchronised
 * fields visible across threads, and this thread is one CPython has never seen.
 */
actual fun awaitSuspendingDemo(): String {
    var completerFailure: Throwable? = null

    val completer = Thread {
        try {
            var polls = 0
            while (polls++ < 20_000) {
                val delivered = withGIL {
                    // `created` is bumped by the counted `create_future` below, so a non-zero
                    // value means AsyncUpcall has already committed to the slow path.
                    evalToString("_pm_demo_slow['created']") != "0" && PendingGreetings.deliver()
                }
                if (delivered) return@Thread
                Thread.sleep(1)
            }
            throw IllegalStateException("no suspending Kotlin call ever parked a continuation")
        } catch (t: Throwable) {
            completerFailure = t
        }
    }
    completer.name = "kotlin-greeting-completer"

    return try {
        Python3.exec(
            """
            import asyncio
            from $BINDINGS_MODULE import Greeter

            _pm_demo_slow = {'created': 0, 'value': None, 'error': None}
            _pm_demo_slow['loop'] = asyncio.new_event_loop()
            _pm_demo_slow['orig'] = _pm_demo_slow['loop'].create_future


            def _pm_demo_slow_count():
                _pm_demo_slow['created'] += 1
                return _pm_demo_slow['orig']()


            _pm_demo_slow['loop'].create_future = _pm_demo_slow_count
            _pm_demo_slow['g'] = Greeter('slow path')
            """.trimIndent(),
        )

        completer.start()
        Python3.exec(
            """
            try:
                _pm_demo_slow['value'] = _pm_demo_slow['loop'].run_until_complete(
                    _pm_demo_slow['g'].greetLater(2)
                )
            except BaseException as _e:
                _pm_demo_slow['error'] = type(_e).__name__ + ': ' + str(_e)
            finally:
                _pm_demo_slow['loop'].close()
            """.trimIndent(),
        )
        completer.join(40_000)

        val created = evalToString("_pm_demo_slow['created']")
        val error = evalToString("_pm_demo_slow['error']")
        listOf(
            "await g.greetLater(2)       ->  ${evalToString("_pm_demo_slow['value']")}",
            "asyncio Futures created     ->  $created  " +
                if (created == "0") "(fast path -- this run proves nothing)" else "(slow path: settled from a Kotlin thread)",
            "raised                      ->  $error",
            "completer thread            ->  ${completerFailure?.let { "${it::class.simpleName}: ${it.message}" } ?: "clean"}",
        ).joinToString("\n")
    } catch (t: Throwable) {
        "${t::class.simpleName}: ${t.message}"
    }
}
