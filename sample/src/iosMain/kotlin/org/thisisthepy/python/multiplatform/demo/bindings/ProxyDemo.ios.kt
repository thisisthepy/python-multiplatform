package org.thisisthepy.python.multiplatform.demo.bindings

import python.multiplatform.ffi.Python3
import python.multiplatform.ffi.upcall.PythonProxySource
import python.multiplatform.ffi.withGIL
import python.native.ffi.UpcallEntry
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * iOS has a boundary shim, and it is not `ctypes`.
 *
 * `UpcallEntry.publish` installs real `PyMethodDef`-backed builtins into a namespace, which is the
 * only route on this target: this project's `Python.framework` ships no `_ctypes` (it exports
 * `PyInit__abc` through `PyInit_time` and carries no `lib-dynload`), so `ctypes.CDLL(None)` --
 * desktop's whole approach -- cannot be used here in either direction.
 *
 * ### There used to be a bridge here, and there is not any more
 *
 * The version of this file that came with the sample wrote nine lines of Python between `publish`
 * and `install`: it captured the native `_pm_bind`/`_pm_resolve` under other names, synthesised
 * `_pm_invoke` out of `_pm_bind` because `publish` did not install one, and re-decoded
 * `_pm_resolve`'s argument because the generated support sent `bytes` where the `METH_O` entry
 * point read `str`. It was compile-checked only, and it was papering over a library defect: the
 * generated proxy module could not install on any target but desktop.
 *
 * Both halves are fixed in the library now -- `UpcallEntry.publish` installs `_pm_invoke`, and
 * `pm_resolve` accepts `str` and `bytes` alike -- and `PythonProxyInstallTest` has moved to
 * `commonTest`, where the iOS simulator runs it. So this is the whole of what a host owes on this
 * target: publish, then install.
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
        "installed over PyMethodDef: ${source.lineSequence().count()} lines, $classes proxy classes"
    }
} catch (t: Throwable) {
    "${t::class.simpleName}: ${t.message}"
}

/**
 * The really-suspending `await`, settled from a Kotlin/Native [Worker] (SPEC U-5; #164 item 11,
 * leader decision 2026-11-21).
 *
 * The same shape as the Android and desktop demos, with a `Worker` where they start a
 * `java.lang.Thread`: the Worker polls, under `withGIL`, for the moment the loop has committed to
 * the slow path (`created` is bumped by the counted `create_future`) and resumes the parked
 * continuation there, holding the GIL as `PendingCall` requires. Any thread may do this as long as
 * it holds `withGIL` while it resumes; the library proves it for a bare `pthread`
 * (`PythonProxyNativeDeliveryTest`) and for a `Worker` (`AsyncUpcallWorkerDeliveryTest`), and a
 * `kotlinx.coroutines` dispatcher thread is a `Worker` underneath. The `Worker` API is marked
 * obsolete by Kotlin and is still the stdlib's one thread primitive on this target; an app with
 * `kotlinx.coroutines` uses `Dispatchers.Default` the same way.
 */
@OptIn(ObsoleteWorkersApi::class)
actual fun awaitSuspendingDemo(): String {
    val worker = Worker.start(name = "kotlin-greeting-completer")
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

        val completion = worker.execute(TransferMode.SAFE, { Unit }) {
            try {
                var polls = 0
                while (polls++ < 20_000) {
                    val delivered = withGIL {
                        evalToString("_pm_demo_slow['created']") != "0" && PendingGreetings.deliver()
                    }
                    if (delivered) return@execute null
                    platform.posix.usleep(1_000u)
                }
                "IllegalStateException: no suspending Kotlin call ever parked a continuation"
            } catch (t: Throwable) {
                "${t::class.simpleName}: ${t.message}"
            }
        }
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
        val completerFailure = completion.result

        val created = evalToString("_pm_demo_slow['created']")
        val error = evalToString("_pm_demo_slow['error']")
        listOf(
            "await g.greetLater(2)       ->  ${evalToString("_pm_demo_slow['value']")}",
            "asyncio Futures created     ->  $created  " +
                if (created == "0") "(fast path -- this run proves nothing)" else "(slow path: settled from a Kotlin/Native Worker)",
            "raised                      ->  $error",
            "completer thread            ->  ${completerFailure ?: "clean"}",
        ).joinToString("\n")
    } catch (t: Throwable) {
        "${t::class.simpleName}: ${t.message}"
    } finally {
        worker.requestTermination()
    }
}
