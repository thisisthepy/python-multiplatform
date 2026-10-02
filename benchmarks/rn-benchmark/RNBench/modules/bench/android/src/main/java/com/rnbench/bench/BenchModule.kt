package com.rnbench.bench

import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.module.annotations.ReactModule

/**
 * The native half of the boundary. **Every method body here is as close to empty as the shape
 * allows**, because what is being timed is the crossing, not the callee.
 *
 * The one exception is [nativeLoopNs], which exists precisely to say how much the callee costs
 * when nothing is crossed, so that the crossing can be read net of it.
 *
 * On the New Architecture this class is reached through JSI: the JS call lands in the generated
 * `NativeBenchSpecJSI` C++ shim, which does a JNI call into these methods. That means an Android
 * JS -> native call pays **two** boundaries -- JSI/C++ and C++/JNI -- which is the single most
 * important structural fact when comparing this with the reference project, where an upcall pays
 * one on iOS/androidNative and two on Android/ART for exactly the same reason.
 */
@ReactModule(name = BenchModule.NAME)
class BenchModule(reactContext: ReactApplicationContext) : NativeBenchSpec(reactContext) {

    override fun getName(): String = NAME

    /** The boundary's floor: sync (it returns a value), no arguments, constant result. */
    override fun zeroArgs(): Double = 0.0

    override fun noop() {
        // Deliberately empty -- and deliberately *not* the floor row. Being `void` makes this
        // `VoidKind`, which `JavaTurboModule` dispatches onto the native method queue rather than
        // running on the JS thread, so timing it from JS times an enqueue.
    }

    override fun addInts(a: Double, b: Double): Double = a + b

    override fun echoString(s: String): String = s

    override fun stringLength(s: String): Double = s.length.toDouble()

    override fun sumArray(xs: ReadableArray): Double {
        var total = 0.0
        val n = xs.size()
        for (i in 0 until n) {
            total += xs.getDouble(i)
        }
        return total
    }

    override fun sumObject(o: ReadableMap): Double =
        o.getDouble("a") + o.getDouble("b") + o.getDouble("c")

    override fun nativeLoopNs(iterations: Double): Double {
        val n = iterations.toLong()
        if (n <= 0L) return 0.0
        // `sink` is read back into the return value so the loop cannot be eliminated.
        var sink = 0.0
        val t0 = System.nanoTime()
        for (i in 0L until n) {
            sink += addInts(3.0, 4.0)
        }
        val elapsed = System.nanoTime() - t0
        // The multiply-by-zero keeps `sink` live without perturbing the result.
        return elapsed.toDouble() / n.toDouble() + sink * 0.0
    }

    override fun pingCallback(value: Double, cb: Callback) {
        cb.invoke(value)
    }

    override fun pingPromise(value: Double, promise: Promise) {
        promise.resolve(value)
    }

    companion object {
        const val NAME = "Bench"
    }
}
