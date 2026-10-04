package fixture.app

import python.multiplatform.ffi.PyObject
import python.multiplatform.reflection.PythonInternal

/*
 * The Kotlin surface compiled TypedPython code calls through the binder (issue #45). The Python
 * side is `src/desktopTest/typedpython/tp_kotlin_bridge.py`; the test is
 * `TypedPythonKotlinCallTest`. Every call records itself in [TpBridgeLedger], so the test can
 * compare what Kotlin saw from the interpreted run with what it saw from the compiled one.
 */

/**
 * A Kotlin object Python creates through its constructor, receives as a typed `TpSample` proxy,
 * calls a method on, and hands back ([tpAccumulate]).
 *
 * Created by calling the class, not through a factory function: a Kotlin function returning a
 * Kotlin object reaches Python as a generic `_PmObject` owner, not as the class's rendered proxy,
 * so its methods are not reachable (`PythonProxySource`'s "What is still not rendered" table; the
 * first run of this test hit it). The rendered class's own constructor is the path that gives a
 * typed proxy today.
 */
class TpSample(val index: Long, val weight: Double) {
    init {
        TpBridgeLedger.samples += 1
    }

    fun weigh(factor: Double): Double {
        TpBridgeLedger.weighs += 1
        return weight * factor + index
    }
}

/** Takes the proxy back (a parameter declared as a Kotlin class, so the binder unwraps it to the object). */
fun tpAccumulate(sample: TpSample, amount: Double): Double {
    TpBridgeLedger.accumulates += 1
    TpBridgeLedger.received += amount
    TpBridgeLedger.indexSum += sample.index
    return amount / (1.0 + sample.weight)
}

/** Throws once [value] passes [limit]; the binder turns the throw into a Python `RuntimeError(message)`. */
fun tpCheck(value: Double, limit: Double): Double {
    TpBridgeLedger.checks += 1
    if (value > limit) throw IllegalStateException("tpCheck: $value exceeds $limit")
    return value
}

/**
 * Receives a Python object as a [PyObject]. The trampoline wraps it with a reference of its own
 * (`borrowed = true`), which the wrapper's cleaner gives back once the Kotlin collector finds the
 * wrapper unreachable -- so the token's `sys.getrefcount` is only meaningful after a JVM collection
 * and the cleaner have run (`TypedPythonKotlinCallTest.settledCounts`).
 *
 * Not closed here, so the test also covers the cleaner path. Closing it would be equally correct:
 * `close()` gives back the wrapper's one reference and the cleaner then has nothing left to release
 * (`closeArgument`, `PyObjectArgumentReleaseTest`). The JVM crash once blamed on that (issue #98) came
 * from a test wrapping fake addresses as owned objects, not from the binder.
 */
fun tpTouch(token: PyObject?): Long {
    TpBridgeLedger.touches += 1
    return if (token == null) 0L else 1L
}

/** What Kotlin observed. Not exposed to Python: only the test reads and resets it. */
@PythonInternal
object TpBridgeLedger {
    var samples: Long = 0
    var weighs: Long = 0
    var accumulates: Long = 0
    var checks: Long = 0
    var touches: Long = 0

    /** Sum of every `amount` [tpAccumulate] received -- a Kotlin-side effect that depends on Python's float math. */
    var received: Double = 0.0
    var indexSum: Long = 0

    fun reset() {
        samples = 0
        weighs = 0
        accumulates = 0
        checks = 0
        touches = 0
        received = 0.0
        indexSum = 0
    }

    /** Every field, `received` as its exact bits, in one comparable string. */
    fun snapshot(): String =
        "samples=$samples weighs=$weighs accumulates=$accumulates checks=$checks touches=$touches " +
            "received=$received (bits ${received.toRawBits().toULong().toString(16)}) indexSum=$indexSum"
}
