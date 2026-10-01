package fixture.library

import python.multiplatform.ffi.PyObject
import python.multiplatform.reflection.PythonInternal

fun add(a: Long, b: Long): Long = a + b

fun greet(name: String): String = "Hello, $name!"

fun scale(value: Int, by: Int): Int = value * by

fun noArgs() {
    // exercises the Unit-return path
}

@PythonInternal
fun hiddenFromPython(): String = "must not appear in the generated table"

class Counter(var count: Long = 0) {
    fun increment(by: Long): Long {
        count += by
        return count
    }

    fun label(prefix: String): String = "$prefix$count"

    @PythonInternal
    fun hiddenMethod(): Long = -1L
}

/** Holds Python references directly, the way a real exposed class would -- exercises the
 * generator's `tp_traverse` field-detection path (docs/design/object-lifetime.md). */
class RefHolder(var primary: PyObject?, var secondary: PyObject?)

/**
 * A `PyObject`-typed parameter reached from a proxy that renders on the *plain* owner: [Counter]
 * has no `PyObject` field, so `hasTraverse` is false for it and its proxy is a `_PmObject`. That is
 * the shape `99acd830` filed -- `_pm_unwrap` turns any `_PmObject` into its raw handle, and this
 * cast is what fails when it does.
 */
fun echoObject(value: PyObject?): PyObject? = value

/**
 * A parameter declared as a Kotlin class, beside [RefHolder.primary]'s parameter declared as a
 * `PyObject`. Both cross as `TypeTag.OBJECT` and the two need opposite unwrapping decisions in the
 * generated proxy, which is what `ProxyObjectArgumentTest` pins: a proxy passed here must still be
 * unwrapped to its handle and resolved back to the Kotlin object, or this cast fails.
 */
fun describeHolder(holder: RefHolder): String =
    "primary=${holder.primary != null},secondary=${holder.secondary != null}"

@PythonInternal
class HiddenClass {
    fun shouldNeverBeReachable(): Int = 0
}
