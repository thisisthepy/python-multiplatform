package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.withGIL
import python.native.ffi.UpcallEntry

/**
 * Android's bootstrap is `PyMethodDef`s like iOS's, with C shims behind `ml_meth`.
 *
 * ### What this file used to say, and what was actually wrong
 *
 * It reported "unavailable on Android", and named the reason: `androidMain`'s `UpcallEntry.publish`
 * installed `_pm_resolve`, `_pm_bind`, `_pm_release` and `_pm_cancel` but no `_pm_invoke`, and the
 * generated module's entry-point guard refuses to install without it. That was right as far as it
 * went, and it was written from reading the source, which is why it was only half the story.
 *
 * `_pm_invoke` was added as `pmp_upcall_invoke_free_meth` in `artMain/cinterop/jni_onload.def` and
 * the guard passed -- and the very next line failed on both emulators with `TypeError: bad argument
 * type for built-in operation`. `pmp_upcall_resolve_meth` read its argument with
 * `PyUnicode_AsUTF8` alone, and [PythonProxySource]'s `_pm_lookup` sends `bytes` (desktop's
 * `ctypes.CFUNCTYPE(c_long, c_char_p)` refuses a `str`, so `bytes` is the only spelling that works
 * on every host). Both C shims are fixed now, and `PythonProxyInstallTest` runs on `pmp_api26` and
 * `pmp_api36` rather than asserting a refusal.
 *
 * So this is the whole of what a host owes on this target, and it is the same two lines iOS owes:
 * publish, then install.
 */
actual fun installPythonProxies(): String = try {
    val globals = Python3.import("__main__").dict
    if (!UpcallEntry.publish(globals.pointer)) {
        "the PyMethodDef bootstrap could not be published"
    } else {
        val source = PythonProxySource.install()
        // `_PmModule` is support scaffolding and `_pm_t_N` is a generated metaclass; neither is a
        // proxy for a Kotlin type, which is what this number is meant to say.
        val classes = source.lineSequence().count {
            it.startsWith("class ") && !it.startsWith("class _Pm") && !it.startsWith("class _pm_t_")
        }
        "installed over PyMethodDef via JNI: ${source.lineSequence().count()} lines, " +
            "$classes proxy classes"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}

/**
 * `await` over a `suspend fun` that really suspends, resumed from a Kotlin thread.
 *
 * The same shape as desktop's, and Android is the other target that can have it: `androidMain` is
 * Kotlin/JVM, so `java.lang.Thread` is available and the parked lambda needs no decision about
 * which memory model it crosses (which is why `ProxyDemo.ios.kt` declines this one).
 *
 * What is different underneath is the whole point of running it here. On desktop the resumption
 * reaches Python through a Panama upcall stub; on ART it reaches C in `jni_onload.def` and comes
 * back through `CallStaticLongMethod` -- and the completer thread is one CPython started no more
 * than ART did, so it exercises `pmp_attach` as well as the delivery.
 *
 * The completer waits for a `Future` to have been constructed before it resumes, for the reason
 * `python-multiplatform`'s own `AsyncUpcallDeliveryTest` records: resuming while `AsyncUpcall` is
 * still deciding what to return makes `PendingCall.isDone` true early, the value is handed back
 * directly, and the run exercises the path it was written to avoid.
 *
 * Every touch of Kotlin state from the completer is inside [withGIL] -- `commonMain/README.md`
 * names the GIL as the barrier that makes the boundary's non-synchronised fields visible across
 * threads, and this thread is one CPython has never seen.
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
